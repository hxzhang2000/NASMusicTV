package com.nasmusic.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.AppLog
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 播放管理器（N-4 拆分后的播放核心 + 协调层）
 *
 * 保留：播放核心状态机（ExoPlayer 生命周期、进度轮询、seek 状态）、队列管理、播放模式。
 * 迁出：高质量人声分离编排 → [HqSeparationOrchestrator]（经 PlayerHost 窄接口回调）；
 * 均衡器/频谱 → [PlayerEqualizer]。
 * 本类对现有调用面（ViewModel/PlaybackService/RemoteControlServer）保持 API 零改动。
 */
class PlayerManager(private val applicationContext: Context) {

    companion object {
        private const val TAG = "PlayerManager"
    }

    private var player: ExoPlayer? = null

    // ── N-4 拆分组件 ──

    /** 均衡器/频谱管理（N-4 提取） */
    private val playerEqualizer = PlayerEqualizer(Handler(Looper.getMainLooper()))

    /**
     * 频谱数据仓库 —— 全屏可视化舞台的数据源。
     *
     * [AudioFrame] 的唯一写入方，UI 层只读。
     * 在 PlayerEqualizer 之前初始化并注入，保证 attach 时仓库已就绪。
     */
    val spectrumRepository = com.nasmusic.tv.visualizer.SpectrumRepository().also {
        playerEqualizer.spectrumRepository = it
    }

    /** 睡眠定时器（F2-2）：到期暂停主播放器并刷新通知 */
    val sleepTimer = SleepTimerController(
        onExpired = {
            pause()
            // 通知宿主刷新（PlaybackService 轮询刷新机制兜底）
            onSleepTimerExpired?.invoke()
        }
    )

    /** F2-5：跨曲交叉淡入淡出（双实例方案，进度轮询驱动） */
    val crossfadeController = CrossfadeController(
        context = applicationContext,
        mainPlayerProvider = { player },
        onCrossfadeComplete = { nextIndex ->
            // 窗口结束：主播放器切到新歌（v2.28.1 的 IDLE 安全恢复路径复用）
            transitionToIndex(nextIndex)
        }
    )

    /** F2-5：crossfade 设置（PlayerViewModel/AppPreferences 收集后注入，避免 PlayerManager 依赖 prefs） */
    @Volatile
    var crossfadeEnabled: Boolean = false

    @Volatile
    var crossfadeDurationSec: Int = 4

    /** F2-2：睡眠定时到期回调（PlaybackService 注册以刷新通知） */
    var onSleepTimerExpired: (() -> Unit)? = null

    /** 高质量人声分离编排（N-4 提取），经 PlayerHost 窄接口回调本类播放操作 */
    private val hqOrchestrator = HqSeparationOrchestrator(applicationContext, object : HqSeparationOrchestrator.PlayerHost {
        override fun player(): ExoPlayer? = this@PlayerManager.player
        override fun currentSong(): Song? = _playerState.value.currentSong
        override fun isPlaying(): Boolean = player?.isPlaying == true
        override fun pause() { player?.pause() }
        override fun play() { player?.play() }
        override fun setFastVocalRemoval(enabled: Boolean) {
            vocalRemovalProcessor?.setEnabled(enabled)
        }
    })

    /**
     * 当 ExoPlayer 自动过渡到 streamUrl 为空的歌曲时触发（如恢复队列中的网络歌曲）。
     * 外部（MainViewModel）应解析 streamUrl 后重新播放该索引的歌曲。
     *
     * ⚠️ 本回调由 **UI 侧**（MainViewModel）注册。Android Auto / Wear OS / 蓝牙唤起等
     * 「MainActivity 从未启动」的场景下它为 null，网络歌曲会静默播不出来。
     * 因此新增 [builtinStreamUrlResolver] 作为不依赖 UI 的优先实现，本回调退居回落位置。
     */
    var onNeedResolveStreamUrl: ((index: Int) -> Unit)? = null

    /**
     * 播放失败回调（歌单导入 URL 失效回退链，docs/playlist-import-feature-plan.md §4.1.8 (5)）。
     * 由 MainViewModel 注册：对 id 以 "imported_" 开头且 streamUrl 为 http 的 stub 歌曲，
     * ExoPlayer 报错时调用（复测可达性 → 不可达走补全链）。
     * 非导入歌曲此回调不被触发，行为与改动前完全一致。
     */
    var onPlaybackFailed: ((Song) -> Unit)? = null

    /**
     * 内建 streamUrl 解析器（**无 UI 依赖**），由 `PlaybackService` 注册。
     *
     * @return true 表示已接管本次解析；false 表示无法处理（未注册 / 无网络管理器 /
     *         该曲已有 URL），此时回落到 [onNeedResolveStreamUrl]。
     */
    var builtinStreamUrlResolver: ((index: Int) -> Boolean)? = null

    /**
     * 触发 streamUrl 解析：**优先内建（无 UI 依赖）**，无法处理时回落到 UI 侧回调。
     *
     * 抽出此方法的原因：解析触发点有 4 处（自动过渡 / 播放错误重试 / syncAndPlayCurrent /
     * transitionToIndex），统一走这里可保证行为一致、不遗漏。
     *
     * 向后兼容：`builtinStreamUrlResolver` 未注册（返回 null）时，行为与改动前完全一致。
     */
    private fun requestStreamUrlResolution(index: Int) {
        if (builtinStreamUrlResolver?.invoke(index) == true) return
        onNeedResolveStreamUrl?.invoke(index)
    }

    /**
     * MTV 模式下置 true：阻止 resume()/playQueue()/next() 中的 play() 调用，
     * 防止异步 URL 解析路径在 MTV 模式下意外恢复主播放器（混音根因）。
     * enterMvMode 置 true，exitMvMode 置 false。
     */
    var suppressPlayback = false

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p == null) {
                // player 已释放，停止轮询；下次 setPlayer + onIsPlayingChanged(true) 会重新启动
                return
            }
            // seek 期间不更新进度，防止 ExoPlayer 内部重置位置时覆盖 _progress
            if (!seekPending) {
                _progress.value = p.currentPosition
            }
            val dur = p.duration
            if (dur > 0) _duration.value = dur
            // F2-5：临近曲尾触发 crossfade（1 秒粒度检查，controller 内部有防重）
            maybeTriggerCrossfade(p, dur)
            progressHandler.postDelayed(this, 1000)
        }
    }

    /** F2-5：进度钩子——剩余时长进入 crossfade 窗口时启动淡入淡出 */
    private fun maybeTriggerCrossfade(p: ExoPlayer, dur: Long) {
        if (!crossfadeEnabled || crossfadeDurationSec <= 0) return
        if (crossfadeController.isFading()) return
        if (dur <= 0 || !p.isPlaying) return
        val remaining = dur - p.currentPosition
        if (remaining > crossfadeDurationSec * 1000L) return
        val mode = derivePlayMode(p)
        crossfadeController.maybeStartCrossfade(
            enabled = crossfadeEnabled,
            durationSec = crossfadeDurationSec,
            suppressPlayback = suppressPlayback,
            repeatOne = mode == PlayMode.REPEAT_ONE,
            queueSize = _playerState.value.queue.size,
            currentIndex = _playerState.value.currentIndex,
            nextMediaItemFactory = { nextIdx ->
                val next = _playerState.value.queue.getOrNull(nextIdx)
                if (next == null || next.streamUrl.isNullOrBlank()) null
                else buildMediaItem(next, next.streamUrl)
            }
        )
    }

    /**
     * seek 兜底 timeout：防止 onPositionDiscontinuity(SEEK) 未触发时 seekPending 永久阻塞进度更新。
     * 抽为独立 Runnable 以便 seek 完成后可移除。
     */
    private val seekTimeoutRunnable = Runnable {
        if (seekPending) {
            AppLog.d("NASMusic", "seekTimeout: clearing seekPending (onPositionDiscontinuity not received)")
            seekPending = false
        }
    }

    // ── T3：三元组原子状态 ──────────────────────────────
    // queue/currentIndex/currentSong 由 _playerState 单流承载，所有切歌/换队列
    // 操作以 update{copy(...)} 同帧发布，UI 集中订阅点不再读到错帧状态。
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _progress = MutableStateFlow(0L)
    val progress: StateFlow<Long> = _progress

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering

    private val _playerError = MutableStateFlow<String?>(null)
    val playerError: StateFlow<String?> = _playerError

    // 人声消除处理器引用（由 PlaybackService 注入）
    private var vocalRemovalProcessor: SpectralMaskProcessor? = null

    // ── 分离模式 / HQ 编排转发（N-4：实现迁至 HqSeparationOrchestrator）──

    val separationMode: StateFlow<SeparationMode> get() = hqOrchestrator.separationMode
    val separating: StateFlow<Boolean> get() = hqOrchestrator.separating
    val separationProgress: StateFlow<Pair<Float, String>> get() = hqOrchestrator.separationProgress
    val hqError: StateFlow<String?> get() = hqOrchestrator.hqError
    val hqSuccess: StateFlow<String?> get() = hqOrchestrator.hqSuccess

    /** 由 PlaybackService 注入处理器实例 */
    fun setVocalRemovalProcessor(processor: SpectralMaskProcessor) {
        vocalRemovalProcessor = processor
    }

    /** 获取 ExoPlayer 实例（供 MediaLibraryTree 等需要直接访问 player 的场景） */
    fun getPlayer(): ExoPlayer? = player

    /** 获取当前播放队列的只读副本（供 MediaLibraryTree 构建媒体树） */
    fun getQueueSnapshot(): List<Song> = _playerState.value.queue.toList()

    /** 开关人声消除（实时生效） */
    fun setVocalRemovalEnabled(enabled: Boolean) {
        vocalRemovalProcessor?.setEnabled(enabled)
    }

    /** 查询当前是否启用人声消除 */
    fun isVocalRemovalEnabled(): Boolean {
        return vocalRemovalProcessor?.isEnabled() ?: false
    }

    /** 注入 DemucsSeparator 实例（由 PlaybackService 初始化后调用） */
    fun setDemucsSeparator(separator: DemucsSeparator) {
        hqOrchestrator.setDemucsSeparator(separator)
    }

    /** 注入 AccompanimentCache 实例（由 PlaybackService 初始化后调用） */
    fun setAccompanimentCache(cache: AccompanimentCache) {
        hqOrchestrator.setAccompanimentCache(cache)
    }

    /** 注入 ModelDownloadManager 实例（由 NasMusicApp 初始化后调用） */
    fun setModelDownloadManager(manager: ModelDownloadManager) {
        hqOrchestrator.setModelDownloadManager(manager)
    }

    /** 切换分离模式（快速/高质量） */
    fun setSeparationMode(mode: AppPreferences.SeparationMode) {
        hqOrchestrator.setSeparationMode(mode)
    }

    /** 查询当前是否为高质量模式 */
    fun isHighQualityMode(): Boolean = hqOrchestrator.isHighQualityMode()

    /** 高质量模式下开启人声消除（编排迁至 HqSeparationOrchestrator，N-4） */
    fun enableHighQualityRemoval(): Boolean = hqOrchestrator.enableHighQualityRemoval()

    /** 高质量模式下关闭人声消除：切换回原始文件 + 恢复 DSP 状态 */
    fun disableHighQualityRemoval() = hqOrchestrator.disableHighQualityRemoval()

    /** 清除高质量分离错误信息 */
    fun clearHqError() = hqOrchestrator.clearHqError()

    /** 清除高质量分离成功信息 */
    fun clearHqSuccess() = hqOrchestrator.clearHqSuccess()

    /** 清除伴奏缓存（返回删除的文件数） */
    fun clearAccompanimentCache(): Int = hqOrchestrator.clearAccompanimentCache()

    // ── 升降调 & 变速（仅 K 歌页面使用，由 MainViewModel 调用）──

    /**
     * 设置升降调（半音单位，-12 ~ +12）
     * 使用 ExoPlayer PlaybackParameters 构造函数，不依赖 SonicAudioProcessor。
     */
    fun setPitch(semitones: Int) {
        val pitchFactor = Math.pow(2.0, semitones.toDouble() / 12.0).toFloat()
        player?.let { p ->
            p.playbackParameters = PlaybackParameters(p.playbackParameters.speed, pitchFactor)
        }
    }

    /**
     * 设置播放速度（0.5 ~ 2.0）
     * 使用 ExoPlayer PlaybackParameters 构造函数，变速不变调。
     */
    fun setSpeed(speed: Float) {
        player?.let { p ->
            p.playbackParameters = PlaybackParameters(speed, p.playbackParameters.pitch)
        }
    }

    /** 重置升降调到原调（0 半音） */
    fun resetPitch() {
        player?.let { p ->
            p.playbackParameters = PlaybackParameters(p.playbackParameters.speed, 1.0f)
        }
    }

    /** 重置播放速度到原速 */
    fun resetSpeed() {
        player?.let { p ->
            p.playbackParameters = p.playbackParameters.withSpeed(1.0f)
        }
    }

    /** 查询当前 pitch factor */
    fun currentPitchFactor(): Float = player?.playbackParameters?.pitch ?: 1.0f

    /** 查询当前 speed factor */
    fun currentSpeedFactor(): Float = player?.playbackParameters?.speed ?: 1.0f

    // 随机播放历史记录，避免连续重复
    private val shuffleHistory = mutableListOf<Int>()

    // seek 状态标志：seekTo 后置为 true，onPositionDiscontinuity(reason=SEEK) 后置为 false
    // 用于防止 progressHandler 在 ExoPlayer 内部重置位置时覆盖 _progress
    // @Volatile：seekTo 在主线程，回调在 ExoPlayer 线程，需保证可见性
    @Volatile
    private var seekPending = false

    /**
     * 最近一次"播放出错后重新解析"的队列索引。
     * 同一首歌只重新解析一次：若重解析后仍失败（如解析源不可用），则放弃该曲跳到下一首，防止死循环。
     */
    @Volatile
    private var lastErrorRetryIndex = -1

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // P5 修复：seek 期间仍需同步 _isPlaying（纯状态记录），否则 seek 窗口内暂停/播放
            // 会导致播放按钮卡在错误状态（原实现直接 return，_isPlaying 永久失真直到下次回调）。
            _isPlaying.value = isPlaying
            playerEqualizer.setPlaying(isPlaying)
            // seek 期间跳过进度轮询的启停（有副作用），防止播放按钮闪烁与 ExoPlayer 内部位置重置干扰；
            // 轮询至多多跑 1 秒，由 seekTimeout 兜底恢复。
            if (seekPending) {
                AppLog.d("NASMusic", "playerListener: onIsPlayingChanged=$isPlaying (seekPending, only syncing isPlaying)")
                return
            }
            if (isPlaying) {
                // 先移除已有回调，避免与 setPlayer 中的 post 形成两条进度链导致回调频率翻倍
                progressHandler.removeCallbacks(progressUpdateRunnable)
                progressHandler.post(progressUpdateRunnable)
                // 成功起播说明当前歌曲链接有效，重置重试标记——
                // 同一首歌在播放中再次链接过期时，允许 onPlayerError 再自动重解析一次
                // （原逻辑仅切歌时重置，长歌/直播流场景下第二轮过期只能跳歌）
                lastErrorRetryIndex = -1
                // 播放开始时自动清除分离成功提示（延迟 3 秒让用户看到）（N-4：迁至 HqSeparationOrchestrator）
                hqOrchestrator.scheduleHqSuccessClearOnPlay()
            } else {
                progressHandler.removeCallbacks(progressUpdateRunnable)
                // 暂停时仍更新一次进度
                player?.let { p -> _progress.value = p.currentPosition }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _buffering.value = playbackState == Player.STATE_BUFFERING
            val dur = player?.duration ?: 0
            if (dur > 0) _duration.value = dur
            // 播放器就绪后尝试初始化频谱分析器
            if (playbackState == Player.STATE_READY) {
                playerEqualizer.initSpectrumAnalyzer(player)
                // 会话变更兜底：ExoPlayer 重建/切轨更换 audioSession 时重建均衡器，
                // 避免 EQ 静默失效（此前仅用户下次拖动频段才重建）。
                playerEqualizer.ensureEqualizerForSession(player)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateCurrentSongFromPlayer()
            // 除 playlist 变更（重新解析后 playQueue 重载队列）外，切到新歌曲后允许再次触发"出错重新解析"
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                lastErrorRetryIndex = -1
            }
            // 自动过渡（播放完一首）到 streamUrl 为空的歌曲时（如恢复队列中的网络歌曲），
            // ExoPlayer 会因空 URI 出错。此时暂停并通知外部解析 streamUrl 后再播放。
            // SEEK 原因（playAt 手机遥控直 seek 等）同样需要检测——不依赖 AUTO 才触发解析。
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                val currentSong = _playerState.value.queue.getOrNull(_playerState.value.currentIndex)
                if (currentSong != null && currentSong.streamUrl.isNullOrBlank()) {
                    AppLog.d("PlayerManager", "onMediaItemTransition: auto-transition to empty streamUrl, index=${_playerState.value.currentIndex}, resolving")
                    player?.pause()
                    requestStreamUrlResolution(_playerState.value.currentIndex)
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // B-10 回归修复：只对用户主动 seek（reason=1）更新进度
            // reason=2（SEEK_ADJUSTMENT）是 ExoPlayer 因流不支持 seek 而内部重置位置，
            // 此时不应覆盖 _progress，让 Handler 的轮询自然更新即可
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                _progress.value = newPosition.positionMs
                // seek 完成，立即清除 pending 状态恢复进度轮询；移除兜底 timeout 避免重复清除
                seekPending = false
                progressHandler.removeCallbacks(seekTimeoutRunnable)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val currentSong = _playerState.value.queue.getOrNull(_playerState.value.currentIndex)
            // 空 URI（streamUrl 为空的待解析网络歌曲）触发的错误是预期行为：
            // onMediaItemTransition(AUTO) 已触发 onNeedResolveStreamUrl 异步解析，
            // 此处不应污染错误 UI、不应 ERROR 级别日志、不应自动跳下一首。
            if (currentSong != null && currentSong.streamUrl.isNullOrBlank()) {
                AppLog.d("PlayerManager", "onPlayerError (expected, streamUrl empty): ${error.message}")
                return
            }
            // F2-4：断网期间冻结错误处理——不跳歌不重解析（解析必然失败），
            // 记录断点等待网络恢复后续播。
            if (networkLost) {
                AppLog.w("PlayerManager", "onPlayerError during network loss, freezing (resume point recorded)")
                recordPendingResume()
                return
            }
            AppLog.e("PlayerManager", "Player error: ${error.message}", error)
            _playerError.value = error.message ?: applicationContext.getString(R.string.player_error_playback)
            // 歌单导入 stub（URL 直链）播放失败（§4.1.8 (5) 回退链）：
            // stub id 在 NAS 上不存在，re-resolve 链路对它必然空转（getSongsByIds 查不到），
            // 直接回调 MainViewModel.playbackFailure → 复测可达性 → 不可达走补全链写回歌单。
            // 补全后用户重播该歌单即为真源；当前播放照常跳下一首，不卡死。
            if (currentSong != null && currentSong.id.startsWith("imported_") &&
                !currentSong.streamUrl.isNullOrBlank()
            ) {
                AppLog.w("PlayerManager", "onPlayerError: imported stub '${currentSong.title}' failed, invoking onPlaybackFailed")
                onPlaybackFailed?.invoke(currentSong)
                // 跳下一首（stub 无源可重解析；补全成功后由歌单替换生效）
                val p = player
                val mode = if (p != null) derivePlayMode(p) else PlayMode.REPEAT_ALL
                next(mode)
                return
            }
            // 播放链接可能已过期（入队时预解析的直链有时效，网络歌曲尤甚，约 5 首后集中出现）。
            // 出错时复用 onNeedResolveStreamUrl（→ ViewModel.resolveAndPlayByIndex）重新解析一次再播放；
            // 同一首歌只重试一次，若重解析后仍失败则继续自动跳下一首，避免死循环。
            if (currentSong != null && lastErrorRetryIndex != _playerState.value.currentIndex) {
                AppLog.d("PlayerManager", "onPlayerError: streamUrl likely expired, re-resolving index=${_playerState.value.currentIndex}")
                lastErrorRetryIndex = _playerState.value.currentIndex
                requestStreamUrlResolution(_playerState.value.currentIndex)
                return
            }
            // 自动跳下一首
            val p = player
            val mode = if (p != null) derivePlayMode(p) else PlayMode.REPEAT_ALL
            next(mode)
        }
    }

    // ===================== F2-4 断线续播 =====================

    /** 断网标志（NetworkMonitor 驱动）：断网期间 onPlayerError 冻结跳歌 */
    @Volatile
    var networkLost = false

    /** 断点（index/positionMs/是否在播），网络恢复后 seek 回该点续播 */
    data class ResumePoint(val index: Int, val positionMs: Long, val wasPlaying: Boolean)

    @Volatile
    var pendingResume: ResumePoint? = null
        private set

    /** 记录断点（保留首个断点——断网风暴期间多次错误只记第一次） */
    private fun recordPendingResume() {
        if (pendingResume != null) return
        val p = player ?: return
        pendingResume = ResumePoint(
            index = _playerState.value.currentIndex,
            positionMs = p.currentPosition.coerceAtLeast(0),
            wasPlaying = p.playWhenReady
        )
        // 冻结态：显示缓冲中（UI 不误报错误），暂停播放器防止错误风暴
        _buffering.value = true
        _playerError.value = null
        try { p.pause() } catch (_: Exception) {}
    }

    /**
     * 网络恢复（MainViewModel.onNetworkAvailable 接线）。
     * 返回待恢复断点（调用方负责延迟 2s 去抖后 resolveAndPlay + seek 续播）；
     * 无断点返回 null。
     */
    fun onNetworkRestored(): ResumePoint? {
        networkLost = false
        val rp = pendingResume
        pendingResume = null
        _buffering.value = false
        return rp
    }

    /** 断网进入（MainViewModel.onNetworkLost 接线） */
    fun onNetworkGone() {
        networkLost = true
    }

    fun setPlayer(exoPlayer: ExoPlayer) {
        // 清理旧 player
        player?.removeListener(playerListener)
        progressHandler.removeCallbacks(progressUpdateRunnable)

        player = exoPlayer
        exoPlayer.addListener(playerListener)
        // 仅在播放时启动进度更新
        if (exoPlayer.isPlaying) {
            progressHandler.post(progressUpdateRunnable)
        }
        // 尝试初始化频谱分析器（如果音频会话已就绪）
        playerEqualizer.initSpectrumAnalyzer(exoPlayer)
        AppLog.d("PlayerManager", "setPlayer: player initialized")
    }

    /**
     * 构建带完整 MediaMetadata 的 MediaItem，供蓝牙/锁屏/通知等系统级显示使用。
     *
     * MediaMetadata 包含 title、artist、album、artworkUri 等字段，
     * 蓝牙 AVRCP 和 MediaStyle 通知会读取这些字段显示歌曲信息和封面。
     */
    internal fun buildMediaItem(song: Song, streamUrl: String): MediaItem {
        val artworkUri = song.coverUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist.ifBlank { null })
            .setAlbumTitle(song.album.ifBlank { null })
            .setArtworkUri(artworkUri)
            .setTrackNumber(song.trackNumber.takeIf { it > 0 })
            .setRecordingYear(song.year)
            .setGenre(song.genre)
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(Uri.parse(streamUrl))
            .setMediaMetadata(metadata)
            .build()
    }

    fun playSong(song: Song) {
        val streamUrl = song.streamUrl ?: return
        val p = player
        if (p == null) {
            AppLog.e("PlayerManager", "playSong: player is null!")
            return
        }

        AppLog.d("PlayerManager", "playSong: ${song.title}, currentPlaying=${p.isPlaying}")

        // Check if song is already in current queue — if so, seek to it (gapless path)
        val existingIndex = _playerState.value.queue.indexOf(song)
        if (existingIndex >= 0) {
            // T3：三元组同帧发布（索引 + 当前歌曲）
            _playerState.update { it.copy(currentIndex = existingIndex, currentSong = song) }
            try {
                p.seekTo(existingIndex, 0)
                p.play()
                AppLog.d("PlayerManager", "playSong: seeking to existing queue item $existingIndex")
            } catch (e: Exception) {
                AppLog.e("PlayerManager", "playSong seek failed", e)
            }
        } else {
            // New song — replace queue with single item and preload next if available
            _playerState.update { it.copy(queue = listOf(song), currentIndex = 0, currentSong = song) }
            val mediaItem = buildMediaItem(song, streamUrl)
            try {
                p.setMediaItem(mediaItem)
                // Preload next item if this song is in a known queue context
                p.prepare()
                p.play()
                AppLog.d("PlayerManager", "playSong: playing ${song.title}")
            } catch (e: Exception) {
                AppLog.e("PlayerManager", "playSong failed", e)
            }
        }
        // Initialize duration from API data; player.duration may return
        // C.TIME_UNSET if the stream format lacks duration metadata.
        if (song.durationMs > 0) _duration.value = song.durationMs
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val p = player
        if (p == null) {
            AppLog.e("PlayerManager", "playQueue: player is null!")
            return
        }

        // T3：三元组同帧发布（队列 + 索引 + 当前歌曲）——setMediaItems 后
        // ExoPlayer currentMediaItemIndex 同步等于 startIndex，直接取队列元素。
        _playerState.update {
            it.copy(queue = songs, currentIndex = startIndex, currentSong = songs.getOrNull(startIndex))
        }

        val mediaItems = songs.map { song ->
            buildMediaItem(song, song.streamUrl ?: "")
        }

        try {
            p.setMediaItems(mediaItems, startIndex, 0)
            p.prepare()
            if (!suppressPlayback) p.play()
            AppLog.d("PlayerManager", "playQueue: playing ${songs.size} songs, start=$startIndex")
        } catch (e: Exception) {
            AppLog.e("PlayerManager", "playQueue failed", e)
        }

        // 从歌曲数据初始化时长（player.duration 可能返回 C.TIME_UNSET）
        val currentSong = songs.getOrNull(startIndex)
        if (currentSong != null && currentSong.durationMs > 0) {
            _duration.value = currentSong.durationMs
        }
    }

    /**
     * Android Auto / 车机场景：外部控制器（Media3 MediaSession）设置了播放列表后，
     * **仅同步 PlayerManager 的状态镜像，不触碰 player**。
     *
     * ## 为什么不能直接调 playQueue()
     *
     * Media3 会用 `MediaLibrarySession.Callback.onSetMediaItems` 的**返回值**去设置 player
     * （内部经 MediaUtils.setMediaItemsWithStartIndexAndPosition → player.setMediaItems()）。
     * 若本方法内再调 playQueue()（其内部同样 setMediaItems），会造成重复设置。
     * 因此这里只同步 _playerState，player 交给 Media3 处理。
     *
     * ## 为什么必须同步
     *
     * _playerState 的 T3 三元组（queue/currentIndex/currentSong）是项目内的队列真相源；
     * 而 1000ms 进度轮询只更新 _progress/_duration，**不会**修正 queue —— 若此处不同步，
     * 车机点歌后会出现「UI 显示的歌与实际播放的歌不一致」。
     *
     * T3 三元组按项目约定在**同一次** update{} 中发布。
     *
     * @param songs 已按 mediaId 还原的歌曲列表（与 Media3 将要设置的列表一致）
     * @param startIndex 起始索引，越界时收敛到合法范围
     */
    fun syncQueueFromExternal(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) {
            AppLog.w("PlayerManager", "syncQueueFromExternal: empty songs, ignored")
            return
        }
        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
        _playerState.update {
            it.copy(queue = songs, currentIndex = safeIndex, currentSong = songs.getOrNull(safeIndex))
        }
        songs.getOrNull(safeIndex)?.let { if (it.durationMs > 0) _duration.value = it.durationMs }
        AppLog.d("PlayerManager", "syncQueueFromExternal: ${songs.size} songs, start=$safeIndex")
    }

    /**
     * 回写队列中指定位置歌曲的 streamUrl（Android Auto 兜底解析用）。
     *
     * 场景：车机播放队列中的网络歌曲时，若 onAddMediaItems 的批量解析因超时未拿到 URL，
     * 由 PlaybackService 侧的无 UI 兜底逻辑解析成功后回写本方法。
     *
     * 只改队列中该条目的 streamUrl，不动 currentIndex/currentSong（保持 T3 一致性）。
     */
    fun updateStreamUrl(index: Int, url: String) {
        if (url.isBlank()) return
        val q = _playerState.value.queue.toMutableList()
        val song = q.getOrNull(index) ?: return
        if (song.streamUrl == url) return
        q[index] = song.copy(streamUrl = url)
        _playerState.update { it.copy(queue = q) }
        AppLog.d("PlayerManager", "updateStreamUrl[$index]: ${song.title}")
    }

    /**
     * 用队列中该位置（已带 streamUrl）的歌曲替换对应 MediaItem 并续播。
     *
     * 供 streamUrl 异步解析完成后的续播使用（`PlaybackService` 的无 UI 解析路径，A-13）。
     * 调用前应先用 [updateStreamUrl] 回写 URL，否则本方法直接返回。
     *
     * ⚠️ 这里**不能**用 `setMediaItem(item, index)` —— ExoPlayer 的
     * `setMediaItem(MediaItem, long)` 第二个参数是**起始播放位置（ms）**，不是索引；
     * 传 Int 索引会编译失败（重载只有 `(MediaItem, long)` 与 `(MediaItem, boolean)`），
     * 即便强转成 Long 也只是「从第 N 毫秒开始播当前这一首」，语义完全不同。
     * 正确做法是 [replaceMediaItem] 原地换掉该位置 + [seekTo] 定位。
     */
    fun replayAt(index: Int) {
        val p = player ?: return
        val song = _playerState.value.queue.getOrNull(index) ?: return
        val url = song.streamUrl?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            if (index < p.mediaItemCount) {
                p.replaceMediaItem(index, buildMediaItem(song, url))
            } else {
                // 播放器尚未装载队列（例如 Media3 侧 setMediaItems 失败）→ 用整个队列兜底重建
                p.setMediaItems(
                    _playerState.value.queue.map { buildMediaItem(it, it.streamUrl ?: "") }
                )
            }
            p.seekTo(index, 0L)
            p.prepare()
            p.play()
        }.onFailure { AppLog.w(TAG, "replayAt($index) failed", it) }
    }

    /**
     * 追加歌曲到队列末尾（用于随心听自动续播）。
     */
    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        val p = player ?: return
        val currentQueue = _playerState.value.queue.toMutableList()
        currentQueue.addAll(songs)
        _playerState.update { it.copy(queue = currentQueue) }
        val mediaItems = songs.map { buildMediaItem(it, it.streamUrl ?: "") }
        try {
            p.addMediaItems(mediaItems)
        } catch (e: Exception) {
            AppLog.e("PlayerManager", "addToQueue failed", e)
        }
    }

    fun playPause() {
        player?.let {
            val wasPlaying = it.isPlaying
            AppLog.d("PlayerManager", "playPause: wasPlaying=$wasPlaying, state=${it.playbackState}")
            if (wasPlaying) {
                it.pause()
                AppLog.d("PlayerManager", "playPause: paused")
            } else {
                it.play()
                AppLog.d("PlayerManager", "playPause: playing")
            }
        }
    }

    /**
     * 判断 ExoPlayer 是否处于"play() 无效"的状态（需重新解析/加载媒体）。
     *
     * - STATE_IDLE：未 prepare 或媒体为空（streamUrl 过期/未解析后 setMediaItem 失败）
     * - STATE_ENDED：播放已结束（旧 URL 播完）
     * 这两种状态下调 play() 无效，应触发重新解析 streamUrl。
     */
    fun isPlayerInactive(): Boolean {
        val p = player ?: return true
        return p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED
    }

    /** 暂停主播放器（无条件设 playWhenReady=false，避免 BUFFERING 时 isPlaying=false 跳过暂停） */
    fun pause() {
        player?.pause()
        AppLog.d("PlayerManager", "pause: playWhenReady=${player?.playWhenReady}")
    }

    /** 恢复主播放器播放；MTV 模式下 suppressPlayback=true 时跳过（防混音） */
    fun resume() {
        if (suppressPlayback) {
            AppLog.d("PlayerManager", "resume: suppressed")
            return
        }
        player?.let {
            if (!it.isPlaying && it.playbackState != Player.STATE_IDLE) {
                it.play()
                AppLog.d("PlayerManager", "resume: playing")
            }
        }
    }

    /**
     * 预览下一首歌曲（不推进队列、不触碰 ExoPlayer）。
     * 供 MTV 连播模式预搜下一首 MV 使用。
     */
    fun peekNextSong(playMode: PlayMode): Song? {
        val queue = _playerState.value.queue
        if (queue.isEmpty()) return null
        val currentIdx = _playerState.value.currentIndex
        return when (playMode) {
            PlayMode.SHUFFLE -> {
                if (queue.size == 1) null
                else queue.filterIndexed { i, _ -> i != currentIdx }.random()
            }
            PlayMode.REPEAT_ONE -> {
                val nextIdx = currentIdx + 1
                if (nextIdx < queue.size) queue[nextIdx] else queue.getOrNull(0)
            }
            else -> {
                val nextIdx = currentIdx + 1
                when {
                    nextIdx < queue.size -> queue[nextIdx]
                    playMode == PlayMode.REPEAT_ALL -> queue.getOrNull(0)
                    else -> null
                }
            }
        }
    }

    /**
     * 静默推进队列索引（更新 _currentIndex + _currentSong，不触碰 ExoPlayer、不触发播放）。
     * 供 MTV 连播模式：MV 播完时推进歌曲索引，主播放器保持暂停，退出时 syncAndPlayCurrent 同步。
     * @return 推进后的歌曲；null 表示队列末尾（SEQUENTIAL 模式）无法推进
     */
    fun advanceIndexSilently(playMode: PlayMode): Song? {
        val queue = _playerState.value.queue
        if (queue.isEmpty()) return null
        val currentIdx = _playerState.value.currentIndex
        val nextIdx = when (playMode) {
            PlayMode.SHUFFLE -> {
                if (queue.size == 1) return null
                (0 until queue.size).filter { it != currentIdx }.random()
            }
            PlayMode.REPEAT_ONE -> {
                val i = currentIdx + 1
                if (i < queue.size) i else return null
            }
            else -> {
                val i = currentIdx + 1
                when {
                    i < queue.size -> i
                    playMode == PlayMode.REPEAT_ALL -> 0
                    else -> return null
                }
            }
        }
        _playerState.update { it.copy(currentIndex = nextIdx, currentSong = queue[nextIdx]) }
        val song = queue[nextIdx]
        AppLog.d("PlayerManager", "advanceIndexSilently: $currentIdx -> $nextIdx '${song.title}'")
        return song
    }

    /**
     * 静默回退队列索引（MTV 页面"上一首"按钮用）。
     * @return 回退后的歌曲；null 表示已在队列首位（非 REPEAT_ALL 模式）无法回退
     */
    fun advanceIndexBackward(playMode: PlayMode): Song? {
        val queue = _playerState.value.queue
        if (queue.isEmpty()) return null
        val currentIdx = _playerState.value.currentIndex
        val prevIdx = when {
            currentIdx > 0 -> currentIdx - 1
            playMode == PlayMode.REPEAT_ALL -> queue.size - 1
            else -> return null
        }
        _playerState.update { it.copy(currentIndex = prevIdx, currentSong = queue[prevIdx]) }
        val song = queue[prevIdx]
        AppLog.d("PlayerManager", "advanceIndexBackward: $currentIdx -> $prevIdx '${song.title}'")
        return song
    }

    /**
     * 加载并播放当前索引处的歌曲（退出 MTV 模式时同步主播放器用）。
     * 网络歌曲（streamUrl 为空）触发 onNeedResolveStreamUrl 由 ViewModel 异步解析。
     */
    fun syncAndPlayCurrent() {
        val queue = _playerState.value.queue
        val index = _playerState.value.currentIndex
        val song = queue.getOrNull(index) ?: return
        val p = player ?: return

        _playerState.update { it.copy(currentSong = song) }
        if (song.durationMs > 0) _duration.value = song.durationMs

        val streamUrl = song.streamUrl
        if (streamUrl.isNullOrBlank()) {
            AppLog.d("PlayerManager", "syncAndPlayCurrent: network song, trigger resolve for '${song.title}'")
            requestStreamUrlResolution(index)
        } else {
            try {
                // 恢复完整队列并 seek 到当前索引（不能用 setMediaItem 替换为单曲，
                // 否则 ExoPlayer currentMediaItemIndex=0，updateCurrentSongFromPlayer 会把 _currentIndex 覆盖回 0，
                // 且 seekToNextMediaItem 无处可跳 -> 退出 MTV 后切歌乱套）
                val mediaItems = queue.map { buildMediaItem(it, it.streamUrl ?: "") }
                p.setMediaItems(mediaItems, index, 0)
                p.prepare()
                if (!suppressPlayback) p.play()
                AppLog.d("PlayerManager", "syncAndPlayCurrent: playing '${song.title}' at index=$index queueSize=${queue.size}")
            } catch (e: Exception) {
                AppLog.e("PlayerManager", "syncAndPlayCurrent failed", e)
            }
        }
    }

    /**
     * 出错恢复专用的手动切歌（IDLE 状态安全网）。
     *
     * 出错后 ExoPlayer 处于 STATE_IDLE，seekToNextMediaItem() 既不触发
     * onMediaItemTransition（索引/空 URL 检测全部失效），也不会重新 prepare，
     * 播放器会静默停在原地（用户症状：某首歌跳过后，下一首不自动播放）。
     * 此方法手动同步索引并恢复播放：
     * - 目标歌曲 streamUrl 为空（网络歌曲懒加载）→ 暂停并触发 onNeedResolveStreamUrl 解析
     * - 否则 seekTo + prepare + play（IDLE 下的标准恢复路径）
     */
    fun transitionToIndex(index: Int) {
        val p = player ?: return
        val queue = _playerState.value.queue
        if (index !in queue.indices) return
        val song = queue[index]
        _playerState.update { it.copy(currentIndex = index, currentSong = song) }
        if (song.durationMs > 0) _duration.value = song.durationMs
        if (song.streamUrl.isNullOrBlank()) {
            AppLog.d("PlayerManager", "transitionToIndex: empty streamUrl at $index '${song.title}', resolving")
            p.pause()
            requestStreamUrlResolution(index)
            return
        }
        try {
            p.seekTo(index, 0)
            p.prepare()
            if (!suppressPlayback) p.play()
            AppLog.d("PlayerManager", "transitionToIndex: playing '${song.title}' at index=$index")
        } catch (e: Exception) {
            AppLog.e("PlayerManager", "transitionToIndex failed", e)
        }
    }

    /**
     * ExoPlayer 是否处于 IDLE（出错后未恢复）状态。
     * IDLE 下 next()/previous() 不能依赖 seekToNextMediaItem 的过渡回调，
     * 需走 transitionToIndex 手动恢复。
     */
    private fun isIdle(): Boolean = player?.playbackState == Player.STATE_IDLE

    fun next(playMode: PlayMode) {
        val p = player ?: return
        // F2-5：手动切歌立即中断进行中的 crossfade（不做优雅等待）
        crossfadeController.abort()
        // 出错恢复路径：IDLE 下 seekToNextMediaItem 不触发过渡回调也不 prepare，
        // 统一走手动恢复（含空 URL 网络歌曲的解析触发）
        if (isIdle()) {
            val target = when (playMode) {
                PlayMode.SHUFFLE -> {
                    // 随机模式：排除已播历史后随机选（与 playRandom 同策略，但走手动恢复路径）
                    val available = (0 until _playerState.value.queue.size).filter { it !in shuffleHistory }
                    if (available.isEmpty()) shuffleHistory.clear()
                    ((0 until _playerState.value.queue.size).filter { it !in shuffleHistory }).randomOrNull()
                        ?: ((_playerState.value.currentIndex + 1) % _playerState.value.queue.size.coerceAtLeast(1))
                }
                else -> if (_playerState.value.currentIndex + 1 < _playerState.value.queue.size) _playerState.value.currentIndex + 1 else 0
            }
            if (playMode == PlayMode.SHUFFLE) shuffleHistory.add(target)
            transitionToIndex(target)
            return
        }
        when (playMode) {
            PlayMode.SHUFFLE -> playRandom()
            PlayMode.REPEAT_ONE -> {
                // 用户主动按"下一首"时，跳到下一首（而非重播当前）
                val nextIndex = _playerState.value.currentIndex + 1
                // P1-7 修复（2026-09-16）：T3 三元组要求同帧发布——只改 currentIndex 会让
                // UI 集中订阅点短暂读到「新索引 + 旧歌名」中间态（依赖后续
                // onMediaItemTransition 补齐）。改为与队列元素同帧 copy。
                val queue = _playerState.value.queue
                if (nextIndex < queue.size) {
                    _playerState.update { it.copy(currentIndex = nextIndex, currentSong = queue[nextIndex]) }
                    p.seekTo(nextIndex, 0)
                    if (!suppressPlayback) p.play()
                } else {
                    // 队列末尾，回到第一首
                    _playerState.update { it.copy(currentIndex = 0, currentSong = queue.firstOrNull()) }
                    p.seekTo(0, 0)
                    if (!suppressPlayback) p.play()
                }
            }
            else -> {
                val nextIndex = _playerState.value.currentIndex + 1
                if (nextIndex < _playerState.value.queue.size) {
                    p.seekToNextMediaItem()
                } else if (playMode == PlayMode.REPEAT_ALL) {
                    // P1-7 修复（2026-09-16）：回卷第一首同帧发布三元组
                    // （原先只 seekTo，依赖 transition 回调补齐 currentSong）
                    _playerState.update {
                        it.copy(currentIndex = 0, currentSong = it.queue.firstOrNull())
                    }
                    p.seekTo(0, 0)
                }
            }
        }
    }

    fun previous(playMode: PlayMode) {
        // F2-5：手动切歌立即中断 crossfade
        crossfadeController.abort()
        // 出错恢复路径：IDLE 下 seekToPreviousMediaItem 不触发过渡回调也不 prepare
        if (isIdle()) {
            val queueSize = _playerState.value.queue.size
            if (queueSize == 0) return
            val prevIndex = if (_playerState.value.currentIndex - 1 >= 0) _playerState.value.currentIndex - 1 else queueSize - 1
            transitionToIndex(prevIndex)
            return
        }
        when (playMode) {
            PlayMode.SHUFFLE -> playRandom()
            else -> {
                val prevIndex = _playerState.value.currentIndex - 1
                if (prevIndex >= 0) {
                    player?.seekToPreviousMediaItem()
                } else if (playMode == PlayMode.REPEAT_ALL) {
                    player?.seekTo(_playerState.value.queue.size - 1, 0)
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        AppLog.d("NASMusic", "seekTo: position=$positionMs, player=${player != null}, state=${player?.playbackState}")
        seekPending = true
        // 先移除上一次未触发的兜底 timeout，避免重复清除
        progressHandler.removeCallbacks(seekTimeoutRunnable)
        player?.seekTo(positionMs)
        _progress.value = positionMs
        AppLog.d("NASMusic", "seekTo: after seek, player.currentPosition=${player?.currentPosition}, seekPending=$seekPending")
        // 兜底：1 秒后清除 seekPending（正常路径由 onPositionDiscontinuity(SEEK) 立即清除）
        progressHandler.postDelayed(seekTimeoutRunnable, 1000)
    }

    /**
     * 设置 ExoPlayer 的播放模式（重复/随机）。
     * @param mode 不存储状态，只应用 ExoPlayer 设置
     */
    fun applyPlayMode(mode: PlayMode) {
        player?.shuffleModeEnabled = (mode == PlayMode.SHUFFLE)
        player?.repeatMode = when (mode) {
            PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
            PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun addToQueue(song: Song) {
        val currentQueue = _playerState.value.queue.toMutableList()
        currentQueue.add(song)
        _playerState.update { it.copy(queue = currentQueue) }

        // Add to player queue if already playing
        if (player?.currentMediaItem != null) {
            val mediaItem = buildMediaItem(song, song.streamUrl ?: "")
            player?.addMediaItem(mediaItem)
        }
    }

    /**
     * 替换队列中指定 id 的歌曲（导入 stub 补全后刷新队列，使 UI source badge 立即更新）。
     * 不触发 ExoPlayer 重新播放——只更新内存中的 StateFlow 队列快照。
     * 若替换的是当前播放歌曲，也同步更新 currentSong。
     */
    fun replaceSongInQueue(oldSongId: String, newSong: Song) {
        _playerState.update { st ->
            val idx = st.queue.indexOfFirst { it.id == oldSongId }
            if (idx < 0) return@update st
            val newQueue = st.queue.toMutableList()
            newQueue[idx] = newSong
            val newCurrent = if (st.currentIndex == idx) newSong else st.currentSong
            st.copy(queue = newQueue, currentSong = newCurrent)
        }
    }

    /** 跳转到队列指定索引并播放（手机遥控用） */
    fun playAt(index: Int) {
        val p = player ?: return
        val queue = _playerState.value.queue
        if (index !in queue.indices) return
        _playerState.update { it.copy(currentIndex = index, currentSong = queue[index]) }
        if (queue[index].durationMs > 0) _duration.value = queue[index].durationMs
        try {
            p.seekTo(index, 0)
            p.play()
            AppLog.d("PlayerManager", "playAt: $index '${queue[index].title}'")
        } catch (e: Exception) {
            AppLog.e("PlayerManager", "playAt failed", e)
        }
    }

    /** 移动队列顺序（手机遥控用） */
    fun moveQueueItem(from: Int, to: Int) {
        val queue = _playerState.value.queue.toMutableList()
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val item = queue.removeAt(from)
        queue.add(to, item)
        val currentIdx = _playerState.value.currentIndex
        // T3：队列与索引同帧发布
        _playerState.update {
            it.copy(
                queue = queue,
                currentIndex = when {
                    from == currentIdx -> to
                    from < currentIdx && to >= currentIdx -> currentIdx - 1
                    from > currentIdx && to <= currentIdx -> currentIdx + 1
                    else -> currentIdx
                }
            )
        }
        try { player?.moveMediaItem(from, to) } catch (e: Exception) {
            AppLog.e("PlayerManager", "moveQueueItem failed", e)
        }
    }

    fun removeFromQueue(index: Int) {
        val p = player ?: return
        val state = _playerState.value
        if (index < 0 || index >= state.queue.size) return

        val wasPlaying = p.isPlaying
        // P2-10 修复（2026-09-16）：索引/当前曲目调整抽成纯函数 [computeQueueRemoval]
        // （便于对边界做单测），并对「移除正在播放的项」显式 seekTo 对齐 ExoPlayer。
        // 原实现只改 currentIndex、依赖 onMediaItemTransition 补齐 currentSong；
        // 移除**末尾**正在播放项时 ExoPlayer 会直接进入 STATE_ENDED，随后
        // onPlaybackEnded 的 REPEAT_ALL 分支看到 currentIndex == size-1 便 seekTo(0)，
        // 跳到不该跳的歌（用户预期是继续播新队尾/停止）。
        val r = computeQueueRemoval(state.queue, state.currentIndex, index)
        _playerState.update {
            it.copy(
                queue = r.queue,
                currentIndex = r.currentIndex,
                currentSong = r.queue.getOrNull(r.currentIndex)
            )
        }
        p.removeMediaItem(index)
        // r.seekTo 非 null ⇔ 移除的是当前播放项且队列仍非空 → 必须显式对齐
        r.seekTo?.let { target ->
            p.seekTo(target, 0)
            if (wasPlaying) p.play()
        }
    }

    /**
     * 按 song.id 从队列中移除（用于歌曲列表页的「加入队列」按钮切换）
     *
     * 若队列中存在同 id 歌曲，移除第一个匹配项并返回 true；否则返回 false。
     * 不移除当前正在播放的歌曲（避免误中断播放），若匹配的是当前歌曲则跳过并返回 false。
     */
    fun removeSongFromQueue(song: Song): Boolean {
        val currentQueue = _playerState.value.queue.toMutableList()
        val targetIndex = currentQueue.indexOfFirst { it.id == song.id }
        if (targetIndex < 0) return false
        // 不移除当前正在播放的歌曲
        if (targetIndex == _playerState.value.currentIndex) return false
        removeFromQueue(targetIndex)
        return true
    }

    /**
     * 移动队列中的曲目位置
     * @param fromIndex 当前索引
     * @param toIndex 目标索引
     * @return 移动是否成功
     */
    fun moveItem(fromIndex: Int, toIndex: Int): Boolean {
        val currentQueue = _playerState.value.queue.toMutableList()
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices) return false
        val item = currentQueue.removeAt(fromIndex)
        currentQueue.add(toIndex, item)

        // 同步更新 ExoPlayer 内部队列
        try {
            player?.moveMediaItem(fromIndex, toIndex)
        } catch (e: Exception) {
            AppLog.e("PlayerManager", "moveMediaItem failed", e)
        }

        // T3：队列与索引同帧发布
        // 调整 currentIndex 以跟随当前播放曲目
        val ci = _playerState.value.currentIndex
        _playerState.update {
            it.copy(
                queue = currentQueue,
                currentIndex = when {
                    fromIndex == ci -> toIndex
                    fromIndex < ci && toIndex >= ci -> ci - 1
                    fromIndex > ci && toIndex <= ci -> ci + 1
                    else -> ci
                }
            )
        }

        AppLog.d("PlayerManager", "moveItem: $fromIndex → $toIndex, currentIndex=${_playerState.value.currentIndex}")
        return true
    }

    fun clearQueue() {
        val p = player
        _playerState.update { it.copy(queue = emptyList(), currentIndex = 0, currentSong = null) }
        _progress.value = 0
        _duration.value = 0
        p?.clearMediaItems()
        p?.stop()
    }

    /**
     * 恢复上次播放队列（恢复 UI 状态 + 加载到 ExoPlayer，但不自动播放）
     *
     * 应用启动时从持久化存储恢复队列：
     * 1. 设置 _queue / _currentIndex / _currentSong（UI 状态）
     * 2. 将 MediaItem 加载到 ExoPlayer 并 prepare（使 ExoPlayer 处于"已准备"状态）
     * 3. 不调用 play（用户点击播放时才启动播放）
     *
     * - NAS 歌曲的 streamUrl 需要后端连接后由 MainViewModel 更新并重新 prepare
     * - 网络歌曲的 streamUrl 在播放时由 NetworkMusicManager.resolvePlayUrl() 解析
     * - streamUrl 为空的歌曲使用空 URI，ExoPlayer 会报错但不崩溃，更新后重新 prepare
     *
     * @param songs 队列歌曲列表
     * @param currentIndex 当前播放索引
     */
    fun restoreQueue(songs: List<Song>, currentIndex: Int) {
        if (songs.isEmpty()) return
        val safeIndex = currentIndex.coerceIn(0, songs.lastIndex)
        // T3：三元组同帧发布
        _playerState.update { it.copy(queue = songs, currentIndex = safeIndex, currentSong = songs[safeIndex]) }

        // 仅当当前歌曲有有效的 streamUrl 时，才加载 MediaItems 并 prepare
        // 网络歌曲的 streamUrl 为空（持久化时置空），此时不应调用 prepare，
        // 否则 ExoPlayer 会因空 URI 进入错误状态并触发 onPlayerError 级联跳歌。
        // 网络歌曲的 streamUrl 在用户按播放时由 resolveAndPlayCurrentSong() 解析。
        val currentSong = songs[safeIndex]
        val p = player
        if (p != null && !currentSong.streamUrl.isNullOrBlank()) {
            val mediaItems = songs.map { song ->
                buildMediaItem(song, song.streamUrl ?: "")
            }
            try {
                p.setMediaItems(mediaItems, safeIndex, 0)
                p.prepare()
                AppLog.d("PlayerManager", "restoreQueue: prepared ${songs.size} songs, start=$safeIndex (not playing)")
            } catch (e: Exception) {
                AppLog.e("PlayerManager", "restoreQueue: prepare failed", e)
            }
        } else {
            AppLog.d("PlayerManager", "restoreQueue: skipped prepare (current song streamUrl is empty, index=$safeIndex)")
        }
    }

    /**
     * 播放结束时根据当前 ExoPlayer 的重复/随机模式决定下一个操作。
     * playMode 从 ExoPlayer 的 repeatMode + shuffleModeEnabled 推导。
     */
    fun onPlaybackEnded() {
        val p = player ?: return
        val playMode = derivePlayMode(p)
        when (playMode) {
            PlayMode.REPEAT_ONE -> {
                p.seekTo(0)
                p.play()
            }
            PlayMode.REPEAT_ALL -> {
                // P1-7 修复（2026-09-16）：原实现 playQueue(queue, 0) 整队重放，
                // 若此刻队列已被用户增删（快照过期）会把用户操作回滚；
                // 队列本身未变，直接 seekTo(0) 回卷即可，三元组同帧发布。
                val st = _playerState.value
                if (st.queue.isNotEmpty() && st.currentIndex >= st.queue.size - 1) {
                    _playerState.update { it.copy(currentIndex = 0, currentSong = it.queue.firstOrNull()) }
                    p.seekTo(0, 0)
                    p.play()
                }
            }
            PlayMode.SHUFFLE -> playRandom()
            else -> { /* Stop */ }
        }
    }

    /**
     * 从 ExoPlayer 的当前状态推导 [PlayMode]。
     * 不存储状态，只读取 ExoPlayer 当前值。
     */
    fun derivePlayMode(p: ExoPlayer): PlayMode = when {
        p.shuffleModeEnabled -> PlayMode.SHUFFLE
        p.repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
        p.repeatMode == Player.REPEAT_MODE_ALL -> PlayMode.REPEAT_ALL
        else -> PlayMode.SEQUENTIAL
    }

    private fun playRandom() {
        val p = player ?: return
        val queueSize = _playerState.value.queue.size
        if (queueSize == 0) return

        // 如果所有歌曲都已播放过，清空历史
        if (shuffleHistory.size >= queueSize) {
            shuffleHistory.clear()
        }

        // 排除已播放的
        val available = (0 until queueSize).filter { it !in shuffleHistory }
        if (available.isEmpty()) {
            shuffleHistory.clear()
            val available2 = (0 until queueSize).toList()
            val randomIndex = available2.random()
            shuffleHistory.add(randomIndex)
            _playerState.update { it.copy(currentIndex = randomIndex) }
            p.seekTo(randomIndex, 0)
            p.play()
            return
        }
        val randomIndex = available.random()
        shuffleHistory.add(randomIndex)
        _playerState.update { it.copy(currentIndex = randomIndex) }
        p.seekTo(randomIndex, 0)
        p.play()
    }

    private fun updateCurrentSongFromPlayer() {
        val currentIndex = player?.currentMediaItemIndex ?: 0
        val st = _playerState.value
        val song = if (currentIndex in st.queue.indices) st.queue[currentIndex] else st.currentSong
        // T3：索引与当前歌曲同帧发布（索引越界时保留原 currentSong）
        _playerState.update { it.copy(currentIndex = currentIndex, currentSong = song) }
    }

    /**
     * 清除播放错误状态
     */
    fun clearError() { _playerError.value = null }

    /**
     * 释放资源，清理 Handler 和 listener
     */
    fun release() {
        progressHandler.removeCallbacks(progressUpdateRunnable)
        progressHandler.removeCallbacks(seekTimeoutRunnable)
        // 中止进行中的 crossfade：释放子播放器与 50ms 淡化轮询，避免服务销毁后
        // fadeRunnable 继续向已释放的 player 写 volume（原实现漏调 abort）。
        crossfadeController.abort()
        player?.removeListener(playerListener)
        player = null
        playerEqualizer.release()
        // P10 修复（迁至 HqSeparationOrchestrator.release，N-4）：释放 Demucs 的 ONNX 会话
        // （166MB 模型 + OrtSession），防止播放服务销毁后进程级泄漏。
        hqOrchestrator.release()
    }

    // ── 均衡器/频谱转发（N-4：实现迁至 PlayerEqualizer）──

    /**
     * 初始化均衡器（在 setPlayer 之后调用）
     */
    /**
     * P6：注入 PCM 降级通道（由 PlaybackService 创建，
     * 其 processor 已挂在 AudioSink 处理器链最前）。
     */
    fun setPcmFallbackChannel(channel: com.nasmusic.tv.player.PcmFallbackChannel) {
        playerEqualizer.pcmFallback = channel
    }

    fun initEqualizer(): Boolean = playerEqualizer.initEqualizer(player)

    /**
     * 设置指定频段的增益值
     * @param bandIndex 频段索引 (0-based)
     * @param gainDb 增益值 (dB, 通常 -15 到 +15)
     */
    fun setEqualizerBand(bandIndex: Int, gainDb: Float): Boolean =
        playerEqualizer.setEqualizerBand(player, bandIndex, gainDb)

    /**
     * 批量设置所有频段增益值（用于应用预设）
     * @param gains 各频段增益值数组 (dB)，数组长度需与设备频段数匹配
     */
    fun setEqualizerBands(gains: List<Float>): Boolean =
        playerEqualizer.setEqualizerBands(player, gains)

    /**
     * 获取当前频段增益值
     */
    fun getEqualizerBandLevel(bandIndex: Int): Float = playerEqualizer.getEqualizerBandLevel(bandIndex)

    /**
     * 获取均衡器频段数量
     */
    fun getEqualizerBandCount(): Int = playerEqualizer.getEqualizerBandCount()

    /**
     * 获取频段中心频率（Hz）
     */
    fun getEqualizerCenterFreq(bandIndex: Int): Int = playerEqualizer.getEqualizerCenterFreq(bandIndex)

    /**
     * 禁用均衡器
     */
    fun disableEqualizer() = playerEqualizer.disableEqualizer()
}

/**
 * [PlayerManager.removeFromQueue] 的队列移除结果。
 *
 * @property queue 移除后的新队列
 * @property currentIndex 移除后的当前下标（队列空时为 0）
 * @property seekTo 需要显式 `seekTo` 的目标下标；**null 表示无需对齐 ExoPlayer**
 *   （仅「移除的是当前播放项且队列仍非空」时非 null）
 */
internal data class QueueRemovalResult(
    val queue: List<Song>,
    val currentIndex: Int,
    val seekTo: Int?
)

/**
 * 计算从队列移除某一项后的（新队列, 新当前下标, 是否需 seekTo）。
 *
 * P2-10（2026-09-16）：抽成不依赖 ExoPlayer / Context 的纯函数，使边界可单测。
 * 三条分支与原实现语义一致：
 * - 移除项在当前项之前 → 当前下标前移 1
 * - 移除项就是当前项 → 落到「原下标夹到新队尾」；队列因此变空则归 0；
 *   非空时返回 [QueueRemovalResult.seekTo]，调用方必须显式对齐（否则 ExoPlayer
 *   在移除末尾项后会停在 STATE_ENDED，被 onPlaybackEnded 的 REPEAT_ALL 误判）
 * - 移除项在当前项之后 → 当前下标不变
 *
 * @param removeIndex 必须落在 `queue.indices` 内（调用方保证）
 */
internal fun computeQueueRemoval(
    queue: List<Song>,
    currentIndex: Int,
    removeIndex: Int
): QueueRemovalResult {
    val newQueue = queue.toMutableList().apply { removeAt(removeIndex) }
    return when {
        removeIndex < currentIndex ->
            QueueRemovalResult(newQueue, currentIndex - 1, null)
        removeIndex == currentIndex -> {
            if (newQueue.isEmpty()) {
                QueueRemovalResult(newQueue, 0, null)
            } else {
                val newIndex = removeIndex.coerceAtMost(newQueue.size - 1)
                QueueRemovalResult(newQueue, newIndex, newIndex)
            }
        }
        else -> QueueRemovalResult(newQueue, currentIndex, null)
    }
}

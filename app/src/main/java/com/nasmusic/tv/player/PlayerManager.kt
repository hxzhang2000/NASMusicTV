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
import kotlinx.coroutines.launch

/**
 * 播放管理器（N-4 拆分后的播放核心 + 协调层）
 *
 * 保留：播放核心状态机（ExoPlayer 生命周期、进度轮询、seek 状态）、队列管理、播放模式。
 * 迁出：高质量人声分离编排 → [HqSeparationOrchestrator]（经 PlayerHost 窄接口回调，
 * 延续 R-5 VocalSeparationController 模式）；均衡器/频谱 → [PlayerEqualizer]。
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

    /** 高质量人声分离编排（N-4 提取），经 PlayerHost 窄接口回调本类播放操作 */
    private val hqOrchestrator = HqSeparationOrchestrator(applicationContext, object : HqSeparationOrchestrator.PlayerHost {
        override fun player(): ExoPlayer? = this@PlayerManager.player
        override fun currentSong(): Song? = _currentSong.value
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
     */
    var onNeedResolveStreamUrl: ((index: Int) -> Unit)? = null

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
            progressHandler.postDelayed(this, 1000)
        }
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

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _progress = MutableStateFlow(0L)
    val progress: StateFlow<Long> = _progress

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

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
    fun getQueueSnapshot(): List<Song> = _queue.value.toList()

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
            // seek 期间跳过进度轮询的启停（有副作用），防止播放按钮闪烁与 ExoPlayer 内部位置重置干扰；
            // 轮询至多多跑 1 秒，由 seekTimeout 兜底恢复。
            if (seekPending) {
                AppLog.d("NASMusic", "playerListener: onIsPlayingChanged=$isPlaying (seekPending, only syncing isPlaying)")
                return
            }
            if (isPlaying) {
                progressHandler.post(progressUpdateRunnable)
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
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                val currentSong = _queue.value.getOrNull(_currentIndex.value)
                if (currentSong != null && currentSong.streamUrl.isNullOrBlank()) {
                    AppLog.d("PlayerManager", "onMediaItemTransition: auto-transition to empty streamUrl, index=${_currentIndex.value}, resolving")
                    player?.pause()
                    onNeedResolveStreamUrl?.invoke(_currentIndex.value)
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
            val currentSong = _queue.value.getOrNull(_currentIndex.value)
            // 空 URI（streamUrl 为空的待解析网络歌曲）触发的错误是预期行为：
            // onMediaItemTransition(AUTO) 已触发 onNeedResolveStreamUrl 异步解析，
            // 此处不应污染错误 UI、不应 ERROR 级别日志、不应自动跳下一首。
            if (currentSong != null && currentSong.streamUrl.isNullOrBlank()) {
                AppLog.d("PlayerManager", "onPlayerError (expected, streamUrl empty): ${error.message}")
                return
            }
            AppLog.e("PlayerManager", "Player error: ${error.message}", error)
            _playerError.value = error.message ?: applicationContext.getString(R.string.player_error_playback)
            // 播放链接可能已过期（入队时预解析的直链有时效，网络歌曲尤甚，约 5 首后集中出现）。
            // 出错时复用 onNeedResolveStreamUrl（→ ViewModel.resolveAndPlayByIndex）重新解析一次再播放；
            // 同一首歌只重试一次，若重解析后仍失败则继续自动跳下一首，避免死循环。
            if (currentSong != null && lastErrorRetryIndex != _currentIndex.value) {
                AppLog.d("PlayerManager", "onPlayerError: streamUrl likely expired, re-resolving index=${_currentIndex.value}")
                lastErrorRetryIndex = _currentIndex.value
                onNeedResolveStreamUrl?.invoke(_currentIndex.value)
                return
            }
            // 自动跳下一首
            val p = player
            val mode = if (p != null) derivePlayMode(p) else PlayMode.REPEAT_ALL
            next(mode)
        }
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
    private fun buildMediaItem(song: Song, streamUrl: String): MediaItem {
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
        val existingIndex = _queue.value.indexOf(song)
        if (existingIndex >= 0) {
            _currentIndex.value = existingIndex
            try {
                p.seekTo(existingIndex, 0)
                p.play()
                AppLog.d("PlayerManager", "playSong: seeking to existing queue item $existingIndex")
            } catch (e: Exception) {
                AppLog.e("PlayerManager", "playSong seek failed", e)
            }
        } else {
            // New song — replace queue with single item and preload next if available
            _queue.value = listOf(song)
            _currentIndex.value = 0
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
        _currentSong.value = song
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

        _queue.value = songs
        _currentIndex.value = startIndex

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

        updateCurrentSongFromPlayer()
    }

    /**
     * 追加歌曲到队列末尾（用于随心听自动续播）。
     */
    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        val p = player ?: return
        val currentQueue = _queue.value.toMutableList()
        currentQueue.addAll(songs)
        _queue.value = currentQueue
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
        val queue = _queue.value
        if (queue.isEmpty()) return null
        val currentIdx = _currentIndex.value
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
        val queue = _queue.value
        if (queue.isEmpty()) return null
        val currentIdx = _currentIndex.value
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
        _currentIndex.value = nextIdx
        val song = queue[nextIdx]
        _currentSong.value = song
        AppLog.d("PlayerManager", "advanceIndexSilently: $currentIdx -> $nextIdx '${song.title}'")
        return song
    }

    /**
     * 静默回退队列索引（MTV 页面"上一首"按钮用）。
     * @return 回退后的歌曲；null 表示已在队列首位（非 REPEAT_ALL 模式）无法回退
     */
    fun advanceIndexBackward(playMode: PlayMode): Song? {
        val queue = _queue.value
        if (queue.isEmpty()) return null
        val currentIdx = _currentIndex.value
        val prevIdx = when {
            currentIdx > 0 -> currentIdx - 1
            playMode == PlayMode.REPEAT_ALL -> queue.size - 1
            else -> return null
        }
        _currentIndex.value = prevIdx
        val song = queue[prevIdx]
        _currentSong.value = song
        AppLog.d("PlayerManager", "advanceIndexBackward: $currentIdx -> $prevIdx '${song.title}'")
        return song
    }

    /**
     * 加载并播放当前索引处的歌曲（退出 MTV 模式时同步主播放器用）。
     * 网络歌曲（streamUrl 为空）触发 onNeedResolveStreamUrl 由 ViewModel 异步解析。
     */
    fun syncAndPlayCurrent() {
        val queue = _queue.value
        val index = _currentIndex.value
        val song = queue.getOrNull(index) ?: return
        val p = player ?: return

        _currentSong.value = song
        if (song.durationMs > 0) _duration.value = song.durationMs

        val streamUrl = song.streamUrl
        if (streamUrl.isNullOrBlank()) {
            AppLog.d("PlayerManager", "syncAndPlayCurrent: network song, trigger resolve for '${song.title}'")
            onNeedResolveStreamUrl?.invoke(index)
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

    fun next(playMode: PlayMode) {
        val p = player ?: return
        when (playMode) {
            PlayMode.SHUFFLE -> playRandom()
            PlayMode.REPEAT_ONE -> {
                // 用户主动按"下一首"时，跳到下一首（而非重播当前）
                val nextIndex = _currentIndex.value + 1
                if (nextIndex < _queue.value.size) {
                    _currentIndex.value = nextIndex
                    p.seekTo(nextIndex, 0)
                    if (!suppressPlayback) p.play()
                } else {
                    // 队列末尾，回到第一首
                    _currentIndex.value = 0
                    p.seekTo(0, 0)
                    if (!suppressPlayback) p.play()
                }
            }
            else -> {
                val nextIndex = _currentIndex.value + 1
                if (nextIndex < _queue.value.size) {
                    p.seekToNextMediaItem()
                } else if (playMode == PlayMode.REPEAT_ALL) {
                    p.seekTo(0, 0)
                }
            }
        }
    }

    fun previous(playMode: PlayMode) {
        when (playMode) {
            PlayMode.SHUFFLE -> playRandom()
            else -> {
                val prevIndex = _currentIndex.value - 1
                if (prevIndex >= 0) {
                    player?.seekToPreviousMediaItem()
                } else if (playMode == PlayMode.REPEAT_ALL) {
                    player?.seekTo(_queue.value.size - 1, 0)
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
        val currentQueue = _queue.value.toMutableList()
        currentQueue.add(song)
        _queue.value = currentQueue

        // Add to player queue if already playing
        if (player?.currentMediaItem != null) {
            val mediaItem = buildMediaItem(song, song.streamUrl ?: "")
            player?.addMediaItem(mediaItem)
        }
    }

    /** 跳转到队列指定索引并播放（手机遥控用） */
    fun playAt(index: Int) {
        val p = player ?: return
        val queue = _queue.value
        if (index !in queue.indices) return
        _currentIndex.value = index
        _currentSong.value = queue[index]
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
        val queue = _queue.value.toMutableList()
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val item = queue.removeAt(from)
        queue.add(to, item)
        _queue.value = queue
        val currentIdx = _currentIndex.value
        _currentIndex.value = when {
            from == currentIdx -> to
            from < currentIdx && to >= currentIdx -> currentIdx - 1
            from > currentIdx && to <= currentIdx -> currentIdx + 1
            else -> currentIdx
        }
        try { player?.moveMediaItem(from, to) } catch (e: Exception) {
            AppLog.e("PlayerManager", "moveQueueItem failed", e)
        }
    }

    fun removeFromQueue(index: Int) {
        val p = player ?: return
        val currentQueue = _queue.value.toMutableList()
        if (index < 0 || index >= currentQueue.size) return

        currentQueue.removeAt(index)
        _queue.value = currentQueue

        // 调整 currentIndex
        val currentIdx = _currentIndex.value
        when {
            index < currentIdx -> _currentIndex.value = currentIdx - 1
            index == currentIdx -> {
                // 移除的是当前播放的歌曲，跳到下一首（或停止）
                if (currentQueue.isEmpty()) {
                    _currentIndex.value = 0
                    _currentSong.value = null
                } else {
                    val newIndex = index.coerceAtMost(currentQueue.size - 1)
                    _currentIndex.value = newIndex
                    // 播放新的当前歌曲
                    p.removeMediaItem(index)
                    return
                }
            }
        }
        p.removeMediaItem(index)
    }

    /**
     * 按 song.id 从队列中移除（用于歌曲列表页的「加入队列」按钮切换）
     *
     * 若队列中存在同 id 歌曲，移除第一个匹配项并返回 true；否则返回 false。
     * 不移除当前正在播放的歌曲（避免误中断播放），若匹配的是当前歌曲则跳过并返回 false。
     */
    fun removeSongFromQueue(song: Song): Boolean {
        val currentQueue = _queue.value.toMutableList()
        val targetIndex = currentQueue.indexOfFirst { it.id == song.id }
        if (targetIndex < 0) return false
        // 不移除当前正在播放的歌曲
        if (targetIndex == _currentIndex.value) return false
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
        val currentQueue = _queue.value.toMutableList()
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices) return false
        val item = currentQueue.removeAt(fromIndex)
        currentQueue.add(toIndex, item)
        _queue.value = currentQueue

        // 同步更新 ExoPlayer 内部队列
        try {
            player?.moveMediaItem(fromIndex, toIndex)
        } catch (e: Exception) {
            AppLog.e("PlayerManager", "moveMediaItem failed", e)
        }

        // 调整 currentIndex 以跟随当前播放曲目
        val ci = _currentIndex.value
        _currentIndex.value = when {
            fromIndex == ci -> toIndex
            fromIndex < ci && toIndex >= ci -> ci - 1
            fromIndex > ci && toIndex <= ci -> ci + 1
            else -> ci
        }

        AppLog.d("PlayerManager", "moveItem: $fromIndex → $toIndex, currentIndex=${_currentIndex.value}")
        return true
    }

    fun clearQueue() {
        val p = player
        _queue.value = emptyList()
        _currentIndex.value = 0
        _currentSong.value = null
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
        _queue.value = songs
        _currentIndex.value = safeIndex
        _currentSong.value = songs[safeIndex]

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
                if (_queue.value.isNotEmpty() && _currentIndex.value >= _queue.value.size - 1) {
                    playQueue(_queue.value, 0)
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
        val queueSize = _queue.value.size
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
            _currentIndex.value = randomIndex
            p.seekTo(randomIndex, 0)
            p.play()
            return
        }
        val randomIndex = available.random()
        shuffleHistory.add(randomIndex)
        _currentIndex.value = randomIndex
        p.seekTo(randomIndex, 0)
        p.play()
    }

    private fun updateCurrentSongFromPlayer() {
        val currentIndex = player?.currentMediaItemIndex ?: 0
        _currentIndex.value = currentIndex
        if (currentIndex in _queue.value.indices) {
            _currentSong.value = _queue.value[currentIndex]
        }
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
        player?.removeListener(playerListener)
        player = null
        playerEqualizer.release()
        // P10 修复（迁至 HqSeparationOrchestrator.release，N-4）：释放 Demucs 的 ONNX 会话
        // （166MB 模型 + OrtSession），防止播放服务销毁后进程级泄漏。
        hqOrchestrator.release()
    }

    // ── 均衡器/频谱转发（N-4：实现迁至 PlayerEqualizer）──

    val spectrumData: StateFlow<FloatArray> get() = playerEqualizer.spectrumData

    /**
     * 初始化均衡器（在 setPlayer 之后调用）
     */
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

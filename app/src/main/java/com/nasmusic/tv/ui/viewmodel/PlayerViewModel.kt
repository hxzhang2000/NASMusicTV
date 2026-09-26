package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.player.PlayerState
import com.nasmusic.tv.backend.download.NetworkPlaybackResolver
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 播放控制域 ViewModel（R-1 拆分自 MainViewModel）：
 * 播放/暂停/上下曲/进度/模式、队列操作、流地址解析（懒加载）。
 *
 * 依赖：PlayerManager（核心委托）。playMode 由本类拥有（B-13: UI/设置状态，
 * 不归 PlayerManager——PlayerManager 的 playMode 是方法参数语义）。
 */
class PlayerViewModel(
    app: Application,
    private val playerManager: PlayerManager
) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val backendRegistry = nasMusicApp.backendRegistry
    private val prefs = nasMusicApp.appPreferences
    private val playlistEnricher = nasMusicApp.playlistEnricher

    /** 播放开始（供 PlayHistoryViewModel 记录最近播放），由 MainViewModel 注入 */
    var onRecordPlay: ((Song) -> Unit)? = null
    /** 错误消息通道（经 MainViewModel 的 errorMessage 语义） */
    var showMessage: ((String) -> Unit)? = null

    // B-13: 播放器状态（isPlaying/progress/duration 由 PlayerManager 拥有）
    // T3: queue/currentIndex/currentSong 合并为 playerState 单流（原子同帧发布）
    val playerState: StateFlow<PlayerState> = playerManager.playerState
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val progress: StateFlow<Long> = playerManager.progress
    val duration: StateFlow<Long> = playerManager.duration
    // B-13: playMode 由本类拥有（UI/设置状态，不归 PlayerManager）
    private val _playMode = MutableStateFlow(PlayMode.SEQUENTIAL)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    // F2-2：睡眠定时器状态桥接（PlayerManager.sleepTimer → UI）
    val sleepTimerState: StateFlow<com.nasmusic.tv.player.SleepTimerController.State> =
        playerManager.sleepTimer.state

    /** F2-2：启动/取消睡眠定时 */
    fun startSleepTimer(minutes: Int) = playerManager.sleepTimer.start(minutes)
    fun cancelSleepTimer() = playerManager.sleepTimer.cancel()

    init {
        // F2-5：crossfade 设置注入 PlayerManager（volatile 字段，进度轮询读取）
        viewModelScope.launch {
            prefs.player.crossfadeEnabled.collect { playerManager.crossfadeEnabled = it }
        }
        viewModelScope.launch {
            prefs.player.crossfadeDurationSec.collect { playerManager.crossfadeDurationSec = it }
        }
    }

    /** 播放模式变更监听（MainViewModel 用来同步 MvSearchViewModel 等），由 MainViewModel 注入 */
    var onPlayModeChanged: ((PlayMode) -> Unit)? = null

    // P4 修复：播放解析代数计数器。每次发起新的 resolveAndPlayByIndex（切歌解析）时 +1，
    // 解析完成回写队列前比对代数，若已过期（期间又发生切歌）则丢弃本次结果，
    // 避免用旧队列快照回滚用户后续操作。
    private var resolveGeneration = 0

    /** 跨源替换防死循环：最近一次跨源替换产物的歌曲 id。
     * 若某首歌已做过跨源替换（id 被记录）却再次解析失败，说明替代曲也不可播，
     * 不再触发跨源替换、直接跳下一首，防止 原曲→替代A→替代B→... 无限替换。
     */
    private var lastCrossSourceReplacedId: String? = null

    /** 初始化播放模式（B-13: 从预设置恢复，MainViewModel 启动期调用一次） */
    fun initPlayModeFromSettings(defaultMode: PlayMode) {
        _playMode.value = defaultMode
        playerManager.applyPlayMode(defaultMode)
    }

    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        playerManager.applyPlayMode(mode)
        onPlayModeChanged?.invoke(mode)
    }

    fun togglePlayMode() {
        val modes = PlayMode.entries
        val nextIndex = (modes.indexOf(_playMode.value) + 1) % modes.size
        val newMode = modes[nextIndex]
        setPlayMode(newMode)
    }

    fun playSong(song: Song) {
        AppLog.d("PlayerViewModel", "playSong: ${song.title}, coverUrl=${song.coverUrl ?: "null"}")
        // 2026-09-25 审查修复（#9）：playSong 整队替换也会使挂起的旧队列解析过期
        resolveGeneration++
        playerManager.playSong(song)
        // 歌词由 currentSong.collect 统一触发，避免重复调用
        onRecordPlay?.invoke(song)
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        // 2026-09-25 审查修复（#9 旧快照覆盖）：进入即递增代数，needsResolve 分支的异步解析
        // 在回写 playQueue 前比对代数，期间用户再次 playQueue/playSong/切歌解析则丢弃本次结果
        //（与 resolveAndPlayByIndex 的 P4 守卫同机制）。
        val generation = ++resolveGeneration
        val firstSong = songs[startIndex.coerceIn(0, songs.lastIndex)]
        AppLog.d("PlayerViewModel", "playQueue: ${songs.size} songs, start=$startIndex, first=${firstSong.title}, coverUrl=${firstSong.coverUrl ?: "null"}")

        // 网络/本地歌曲的 streamUrl 为空时需要先回填（历史持久化数据曾统一置空 streamUrl），
        // 否则 ExoPlayer 收到空 URI 不会开始播放。
        // 导入 stub（id 以 imported_ 开头）也需要同步补全：PlaylistEnricher 先搜 NAS（已连时）
        // 再降级网络，命中后返回带真实 streamUrl + 来源标识的 Song。
        val needsResolve = songs.any {
            ((it.isNetworkSong || it.isLocalSong) && it.streamUrl.isNullOrBlank()) ||
            (it.id.startsWith("imported_") && it.streamUrl.isNullOrBlank()) ||
            (!it.isNetworkSong && !it.isLocalSong && !it.id.startsWith("imported_") && it.streamUrl.isNullOrBlank())
        }
        if (needsResolve) {
            // 只解析第一首歌曲的 URL，立即播放；后续歌曲在播放器自动过渡时懒加载。
            // 原实现逐首解析所有歌曲（songs.map），30 首可能耗时 30-90s 才开始播放。
            AppLog.d("PlayerViewModel", "playQueue: needsResolve, resolving first song only: ${firstSong.title}")
            // 立即更新队列状态，避免异步解析期间 UI 读到旧的队列数据
            playerManager.restoreQueue(songs, startIndex)

            viewModelScope.launch {
                val resolvedFirst = when {
                    // 导入 stub：同步补全 → NAS 命中带 streamUrl；网络命中 isNetworkSong=true
                    // 但 streamUrl 置空（需走 networkMusicManager.resolvePlayUrl 二次解析）
                    firstSong.id.startsWith("imported_") && firstSong.streamUrl.isNullOrBlank() -> {
                        try {
                            val enriched = playlistEnricher.enrichSong(firstSong)
                            if (enriched == null) {
                                AppLog.w("PlayerViewModel", "playQueue: enrichment failed for stub '${firstSong.title}'")
                                firstSong
                            } else if (enriched.isNetworkSong && enriched.streamUrl.isNullOrBlank()) {
                                // 网络命中：streamUrl 置空，走 resolvePlayUrl 二次解析
                                val url = nasMusicApp.networkMusicManager.resolvePlayUrl(enriched)
                                if (!url.isNullOrBlank()) enriched.copy(streamUrl = url) else enriched
                            } else {
                                // NAS 命中：streamUrl 已带；或直链类型：streamUrl 已带
                                enriched
                            }
                        } catch (e: Exception) {
                            AppLog.e("PlayerViewModel", "playQueue: enrich failed for stub '${firstSong.title}'", e)
                            firstSong
                        }
                    }
                    firstSong.isNetworkSong && firstSong.streamUrl.isNullOrBlank() -> {
                        try {
                            val url = nasMusicApp.networkMusicManager.resolvePlayUrl(firstSong)
                            if (!url.isNullOrBlank()) firstSong.copy(streamUrl = url) else firstSong
                        } catch (e: Exception) {
                            AppLog.e("PlayerViewModel", "playQueue: resolveUrl failed for ${firstSong.title}", e)
                            firstSong
                        }
                    }
                    // 本地歌曲（含已下载）：path 即本地 file:// URI，直接回填，无需网络
                    firstSong.isLocalSong && firstSong.streamUrl.isNullOrBlank() ->
                        firstSong.path?.takeIf { it.isNotBlank() }
                            ?.let { firstSong.copy(streamUrl = it) } ?: firstSong
                    // NAS 歌曲：AppPreferences.stripVolatileStreamUrl 已清空 streamUrl
                    //（避免 api_key/t=md5/JWT 等凭据落盘），需经 NAS 后端重建
                    //（与 resolveAndPlayCurrentSong 的 NAS 分支同款）。
                    !firstSong.isNetworkSong && !firstSong.isLocalSong &&
                        !firstSong.id.startsWith("imported_") && firstSong.streamUrl.isNullOrBlank() -> {
                        try {
                            val adapter = backendRegistry.getAdapter()
                            val url = adapter?.getSongsByIds(listOf(firstSong.id))?.firstOrNull()?.streamUrl
                            if (!url.isNullOrBlank()) {
                                firstSong.copy(streamUrl = url)
                            } else {
                                AppLog.w("PlayerViewModel", "playQueue: failed to resolve NAS streamUrl for ${firstSong.title}")
                                firstSong
                            }
                        } catch (e: Exception) {
                            AppLog.w("PlayerViewModel", "playQueue: NAS resolve failed for ${firstSong.title}", e)
                            firstSong
                        }
                    }
                    else -> firstSong
                }
                // 检查第一首歌是否仍然无法解析
                if (resolvedFirst.isNetworkSong && resolvedFirst.streamUrl.isNullOrBlank()) {
                    AppLog.w("PlayerViewModel", "playQueue: failed to resolve URL for ${resolvedFirst.title}")
                    showMessage?.invoke(getApplication<Application>().getString(R.string.resolve_url_endpoint_failed))
                }
                // 补全成功后持久化写回歌单（避免下次播放再补全一遍）
                if (resolvedFirst.id != firstSong.id && firstSong.id.startsWith("imported_")) {
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { playlistEnricher.enrichAndPersistEverywhere(firstSong) }
                    }
                }
                // 只更新第一首歌的 streamUrl，其余歌曲保持空 URL，在播放器过渡时按需解析
                val resolved = songs.toMutableList()
                resolved[startIndex.coerceIn(0, songs.lastIndex)] = resolvedFirst
                // 2026-09-25 审查修复（#9）：解析期间用户换队列/切歌 → 丢弃旧快照回写
                if (generation != resolveGeneration) {
                    AppLog.d("PlayerViewModel", "playQueue: generation stale ($generation != $resolveGeneration), drop resolved queue")
                    return@launch
                }
                AppLog.d("PlayerViewModel", "playQueue: first song resolved, starting playback (url=${resolvedFirst.streamUrl?.take(30)}...)")
                playerManager.playQueue(resolved, startIndex)
                onRecordPlay?.invoke(firstSong)
            }
        } else {
            playerManager.playQueue(songs, startIndex)
            onRecordPlay?.invoke(firstSong)
        }
    }

    fun playPause() {
        val song = playerState.value.currentSong
        // 当前歌曲 streamUrl 为空（网络歌曲懒加载 / 恢复队列后未解析）时，
        // 无论 isPlaying 状态如何都先解析再播放——空 URL 的 ExoPlayer 必然无法播放，
        // 此时 isPlaying 若为 true 是误导状态（缓冲/错误残留），直接 play() 无效。
        if (song != null && song.streamUrl.isNullOrBlank()) {
            resolveAndPlayCurrentSong(song)
            return
        }
        // 网络歌曲直链有时效（百度 dlink 8h / Meting 302 过期），
        // ExoPlayer 处于 IDLE/ENDED 时 play() 无效 → 重新解析直链
        if (song != null && song.isNetworkSong && !isPlaying.value && playerManager.isPlayerInactive()) {
            AppLog.d("PlayerViewModel", "playPause: network song URL may be expired, re-resolving '${song.title}'")
            resolveAndPlayCurrentSong(song)
            return
        }
        playerManager.playPause()
    }

    /**
     * 解析当前歌曲的播放链接并播放
     *
     * 用于恢复队列后首次播放：
     * - 本地歌曲（含已下载入库）：本地 file:// URI 永久有效，streamUrl 置空时回退 path
     * - 网络歌曲：已下载优先播本地文件，否则通过 NetworkMusicManager.resolvePlayUrl() 解析
     * - NAS 歌曲：通过 adapter.getSongsByIds() 获取 streamUrl
     */
    private fun resolveAndPlayCurrentSong(song: Song) {
        // 2026-09-25 审查修复（#9）：与 resolveAndPlayByIndex 同款代数守卫，解析窗口内
        // 用户切歌/换队列时，旧解析结果不得整体回滚新队列。
        val generation = ++resolveGeneration
        viewModelScope.launch {
            try {
                val playUrl = when {
                    // 本地歌曲：path 与 streamUrl 存的都是本地 URI，直接回填即可，
                    // 不能落入下方 NAS 分支（local_xxx id 在 NAS 后端必然查不到）
                    song.isLocalSong -> song.streamUrl ?: song.path
                    song.isNetworkSong -> {
                        // 已下载的网络歌曲优先播本地文件（离线可播），未下载才实时解析直链
                        nasMusicApp.downloadRepository.playableLocalUri(song)
                            ?: nasMusicApp.networkMusicManager.resolvePlayUrl(song)
                    }
                    // NAS 歌曲：通过后端获取 streamUrl
                    else -> {
                        val adapter = backendRegistry.getAdapter()
                        if (adapter != null) {
                            val songs = adapter.getSongsByIds(listOf(song.id))
                            songs.firstOrNull()?.streamUrl
                        } else null
                    }
                }

                if (playUrl.isNullOrBlank()) {
                    AppLog.w("PlayerViewModel", "resolveAndPlayCurrentSong: failed to resolve streamUrl for ${song.title}")
                    showMessage?.invoke(getApplication<Application>().getString(R.string.resolve_url_failed_retry))
                    return@launch
                }

                AppLog.d("PlayerViewModel", "resolveAndPlayCurrentSong: resolved ${song.title} → $playUrl")
                // 更新队列中当前歌曲的 streamUrl，然后播放
                val currentQueue = playerState.value.queue
                val currentIndexValue = playerState.value.currentIndex
                val updatedQueue = currentQueue.mapIndexed { index, s ->
                    if (index == currentIndexValue) s.copy(streamUrl = playUrl) else s
                }
                // 2026-09-25 审查修复（#9）：解析窗口内世界已变 → 丢弃本次结果
                if (generation != resolveGeneration) {
                    AppLog.d("PlayerViewModel", "resolveAndPlayCurrentSong: generation stale, drop result for ${song.title}")
                    return@launch
                }
                // 重新加载队列到 ExoPlayer 并播放
                playerManager.playQueue(updatedQueue, currentIndexValue)
            } catch (e: Exception) {
                AppLog.e("PlayerViewModel", "resolveAndPlayCurrentSong failed", e)
                showMessage?.invoke(getApplication<Application>().getString(R.string.play_failed_with_msg, e.message?.take(50)))
            }
        }
    }

    fun next() {
        // 恢复队列后，下一首歌曲的 streamUrl 可能为空，需要先解析
        val queueValue = playerState.value.queue
        val nextIndex = playerState.value.currentIndex + 1
        val targetIndex = if (nextIndex < queueValue.size) nextIndex else 0
        val nextSong = queueValue.getOrNull(targetIndex)
        if (nextSong != null && nextSong.streamUrl.isNullOrBlank()) {
            // streamUrl 为空，先切换索引再解析播放
            resolveAndPlayByIndex(targetIndex)
            return
        }
        playerManager.next(_playMode.value)
    }

    fun previous() {
        // 恢复队列后，上一首歌曲的 streamUrl 可能为空，需要先解析
        val queueValue = playerState.value.queue
        val prevIndex = playerState.value.currentIndex - 1
        val targetIndex = if (prevIndex >= 0) prevIndex else queueValue.lastIndex
        val prevSong = queueValue.getOrNull(targetIndex)
        if (prevSong != null && prevSong.streamUrl.isNullOrBlank()) {
            resolveAndPlayByIndex(targetIndex)
            return
        }
        playerManager.previous(_playMode.value)
    }

    /**
     * 解析单首歌曲的播放链接
     *
     * - 本地歌曲（含已下载入库）：streamUrl 置空时回退 path（本地 file:// URI 永久有效）
     * - 网络歌曲：已下载优先播本地文件，否则通过 NetworkMusicManager.resolvePlayUrl() 实时解析
     * - NAS 歌曲：通过 adapter.getSongsByIds() 获取 streamUrl
     *
     * @param forceRefresh 网络歌曲解析是否强制绕过播放链接缓存。
     *        播放失败重试路径必须传 true，否则会命中「已过期但未到 TTL」的旧缓存。
     */
    private suspend fun resolveStreamUrl(song: Song, forceRefresh: Boolean = false): String? {
        return when {
            song.isLocalSong -> song.streamUrl ?: song.path
            song.isNetworkSong -> resolveNetworkStreamUrl(song, forceRefresh)
            // 导入 stub：同步补全 → NAS 命中直接取 streamUrl；网络命中走 resolvePlayUrl
            song.id.startsWith("imported_") -> {
                val enriched = playlistEnricher.enrichSong(song) ?: return null
                if (enriched.isNetworkSong) {
                    resolveNetworkStreamUrl(enriched, forceRefresh)
                } else {
                    enriched.streamUrl
                }
            }
            else -> {
                val adapter = backendRegistry.getAdapter()
                if (adapter != null) {
                    adapter.getSongsByIds(listOf(song.id)).firstOrNull()?.streamUrl
                } else null
            }
        }
    }

    /**
     * 网络歌曲的档位感知解析（方案 §3.6）。
     *
     * 改造前是 `playableLocalUri(song) ?: resolvePlayUrl(...)` —— 只要该曲下载过
     * （任意档位）就永远播本地文件，导致"切到无损/128"对已下载歌曲完全失效。
     * 现在按**有效档位**查本地：
     * 1. 该档已下载 → 播本地（离线优先，不发网络请求）；
     * 2. 该档未下载 → 走网络解析；
     * 3. 解析降级且降级后的档恰好已下载 → 回退本地（省流量，内容一致）；
     * 4. 解析彻底失败 → 任意已下载档兜底（保证"能播就行"）。
     */
    private suspend fun resolveNetworkStreamUrl(song: Song, forceRefresh: Boolean): String? {
        val repo = nasMusicApp.downloadRepository
        val mgr = nasMusicApp.networkMusicManager
        // §3.6 决策逻辑抽在 NetworkPlaybackResolver（可单测），此处只做接线
        val quality = mgr.effectiveQualityOf(song)
        val decision = NetworkPlaybackResolver.decide(
            effectiveQuality = quality,
            localUriFor = { q -> repo.playableLocalUri(song, q) },
            localUriAny = { repo.playableLocalUriAny(song) },
            resolveOnline = { q -> mgr.resolvePlayUrlDetailed(song, q, forceRefresh) }
        ) ?: return null
        if (!decision.fromLocal) {
            // 在线解析命中：把实际档位回填给 Song，供列表档位徽标显示（§3.5）
            AppLog.d(
                "PlayerViewModel",
                "resolveNetworkStreamUrl: online q=${decision.actualQuality} (requested=$quality) for ${song.title}"
            )
        }
        return decision.url
    }

    fun resolveAndPlayByIndex(targetIndex: Int) {
        // P4 修复：进入即递增代数，标记本次解析为「最新」；解析期间若有新的切歌解析
        // 会再次递增，使本次挂起解析在回写前被判定为过期。
        val generation = ++resolveGeneration
        val queueValue = playerState.value.queue
        val song = queueValue.getOrNull(targetIndex) ?: return
        viewModelScope.launch {
            try {
                // forceRefresh=true：播放失败重试绝不再命中「已过期未到 TTL」的旧缓存，
                // 强制走完整降级链重新解析（链接过期重获的核心修复）。
                var playUrl = resolveStreamUrl(song, forceRefresh = true)
                // 初次解析失败（网络瞬时抖动/端点超时）：延迟 1.5s 自动重试一次
                if (playUrl.isNullOrBlank()) {
                    AppLog.w("PlayerViewModel", "resolveAndPlayByIndex: initial resolve failed for ${song.title}, retrying in 1.5s")
                    delay(1500)
                    playUrl = resolveStreamUrl(song, forceRefresh = true)
                }
                if (playUrl.isNullOrBlank()) {
                    // 重试仍失败：先尝试同源重搜 / 跨源替换，全部失效才跳下一首
                    if (generation != resolveGeneration) return@launch
                    val replaced = tryReplaceByReSearch(song, targetIndex, generation)
                    if (replaced) return@launch
                    // 全部降级失效：不再静默卡在"已切歌未播放"状态，自动跳到下一首
                    if (generation != resolveGeneration) return@launch
                    AppLog.w("PlayerViewModel", "resolveAndPlayByIndex: failed after all fallbacks, skipping ${song.title}")
                    showMessage?.invoke(getApplication<Application>().getString(R.string.resolve_url_auto_skip_with_title, song.title))
                    playerManager.next(_playMode.value)
                    return@launch
                }

                // P4 修复：回写前校验代数。若期间用户又切歌（resolveGeneration 已变），
                // 丢弃本次结果，避免用旧快照回滚队列。
                if (generation != resolveGeneration) {
                    AppLog.d("PlayerViewModel", "resolveAndPlayByIndex: stale resolve discarded for ${song.title}")
                    return@launch
                }

                AppLog.d("PlayerViewModel", "resolveAndPlayByIndex: resolved ${song.title} → $playUrl")
                // 基于「当前最新队列」更新目标歌曲的 streamUrl（而非入口旧快照），然后播放
                val latestQueue = playerState.value.queue
                val updatedQueue = latestQueue.mapIndexed { index, s ->
                    if (index == targetIndex) s.copy(streamUrl = playUrl) else s
                }
                playerManager.playQueue(updatedQueue, targetIndex)
            } catch (e: Exception) {
                AppLog.e("PlayerViewModel", "resolveAndPlayByIndex failed", e)
                showMessage?.invoke(getApplication<Application>().getString(R.string.play_failed_with_msg, e.message?.take(50)))
            }
        }
    }

    /**
     * 解析失败后的降级替换：先同源重搜取另一首，再跨源重搜替换。
     *
     * 层级1（同源重搜）：用 song 的 title+artist 调 NetworkMusicManager.search()（本身已多端点降级），
     *  从结果里排除原曲，逐条做可播校验，取第一条可播的替代曲。
     * 层级2（跨源替换）：若当前歌已是某次跨源替换的产物（lastCrossSourceReplacedId 命中），
     *  则不再跨源，防无限替换；否则调 resolvePlayUrlWithCrossSourceFallback 跨源搜索替换。
     *
     * @return true 已替换并播放；false 无可播替代（调用方应跳下一首）
     */
    private suspend fun tryReplaceByReSearch(song: Song, targetIndex: Int, generation: Int): Boolean {
        if (song.isNetworkSong) {
            val replacement = if (song.id == lastCrossSourceReplacedId) {
                // 防死循环：当前歌已是跨源替换产物且仍失败，直接放弃（走跳曲）
                AppLog.w("PlayerViewModel", "tryReplaceByReSearch: ${song.title} 已是跨源替换产物且仍失败，放弃替换")
                null
            } else {
                tryCrossSourceReplace(song, targetIndex, generation)
            }
            if (replacement != null) {
                if (generation != resolveGeneration) return false
                val latestQueue = playerState.value.queue
                val updatedQueue = latestQueue.mapIndexed { index, s ->
                    if (index == targetIndex) replacement else s
                }
                // 记录本次替换产物 id，防后续对同一首歌无限跨源替换
                lastCrossSourceReplacedId = replacement.id
                AppLog.w("PlayerViewModel", "tryReplaceByReSearch: 用替代曲播放 '${replacement.title}' (${replacement.networkSource})")
                showMessage?.invoke(getApplication<Application>().getString(
                    R.string.cross_source_replace_playing,
                    song.title, replacement.networkSource ?: ""
                ))
                playerManager.playQueue(updatedQueue, targetIndex)
                return true
            }
        }
        return false
    }

    /**
     * 跨源替换：遍历 NetworkMusicManager 的其他已注册源，用 title+artist 重搜并取第一条可播替代曲。
     * 返回带已解析 streamUrl 的替代曲；无可播替代返回 null。
     */
    private suspend fun tryCrossSourceReplace(song: Song, targetIndex: Int, generation: Int): Song? {
        if (generation != resolveGeneration) return null
        val result = nasMusicApp.networkMusicManager.resolvePlayUrlWithCrossSourceFallback(
            song = song,
            forceRefresh = true
        ) ?: return null
        if (generation != resolveGeneration) return null
        return result.replacement
    }

    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)

    /**
     * 恢复上次播放队列（仅恢复 UI 状态，不自动播放）
     *
     * 从 DataStore 读取持久化的队列，调用 PlayerManager.restoreQueue() 设置队列和索引。
     * NAS 歌曲的 streamUrl 暂时为空，等后端连接成功后由 updateRestoredQueueStreamUrls() 更新。
     * 网络歌曲的 streamUrl 在播放时由 NetworkMusicManager.resolvePlayUrl() 解析。
     */
    suspend fun restoreLastQueue() {
        val lastQueue = prefs.queue.getLastQueue() ?: return
        val songs = lastQueue.songs
        if (songs.isNullOrEmpty()) return
        AppLog.d("PlayerViewModel", "restoreLastQueue: ${lastQueue.songs.size} songs, index=${lastQueue.currentIndex}")
        playerManager.restoreQueue(lastQueue.songs, lastQueue.currentIndex)
    }

    /**
     * 后端连接成功后，更新恢复队列中 NAS 歌曲的 streamUrl
     */
    fun updateRestoredQueueStreamUrls() {
        val currentQueue = playerState.value.queue
        if (currentQueue.isEmpty()) return
        val adapter = backendRegistry.getAdapter() ?: return
        // 2026-09-25 审查修复（#9 同族）：解析期间队列可能等长变更（跨源替换/补全/自动过渡），
        // 仅比对 size 会把旧快照整体回滚。改为：递增代数 + 回写前逐项比对 id。
        val generation = ++resolveGeneration

        // 筛选需要更新 streamUrl 的 NAS 歌曲（本地歌曲/已下载不依赖 NAS，排除）
        val nasSongIds = currentQueue.filter { !it.isNetworkSong && !it.isLocalSong }.map { it.id }
        if (nasSongIds.isEmpty()) return

        viewModelScope.launch {
            try {
                val updatedSongs = adapter.getSongsByIds(nasSongIds)
                val songMap = updatedSongs.associateBy { it.id }
                // 合并：NAS 歌曲用更新后的版本（含 streamUrl），网络/本地歌曲保留原样
                val mergedQueue = currentQueue.map { song ->
                    if (!song.isNetworkSong && !song.isLocalSong) {
                        songMap[song.id] ?: song
                    } else {
                        song
                    }
                }
                // 只在队列未变化时更新（逐项比对 id，等长变更也不回滚；代数过期同样丢弃）
                val latestQueue = playerState.value.queue
                if (generation == resolveGeneration &&
                    currentQueue.map { it.id } == latestQueue.map { it.id }
                ) {
                    val currentIndexValue = playerState.value.currentIndex
                    playerManager.restoreQueue(mergedQueue, currentIndexValue)
                    AppLog.d("PlayerViewModel", "updateRestoredQueueStreamUrls: updated ${updatedSongs.size} NAS songs")
                }
            } catch (e: Exception) {
                AppLog.w("PlayerViewModel", "updateRestoredQueueStreamUrls failed: ${e.message}", e)
            }
        }
    }

    /**
     * 批量加入队列（修复 M-8）：只增不删、按 id 去重并跳过已在队列中的歌曲。
     * 用于曲库搜索/发现页的「全部加入队列」——原实现逐首 toggle 会把已入队歌曲反向移除。
     */
    fun addSongsToQueue(songs: List<Song>) {
        val existingIds = playerManager.playerState.value.queue.map { it.id }.toHashSet()
        val toAdd = songs.filter { it.id !in existingIds }.distinctBy { it.id }
        if (toAdd.isNotEmpty()) playerManager.addToQueue(toAdd)
    }

    /**
     * 切换歌曲在队列中的状态：不在队列则加入，在队列则移除。
     * 当前正在播放的歌曲不会被移除（避免误中断播放）。
     */
    fun toggleQueueSong(song: Song) {
        val currentQueue = playerState.value.queue
        val inQueue = currentQueue.any { it.id == song.id }
        if (inQueue) {
            playerManager.removeSongFromQueue(song)
        } else {
            playerManager.addToQueue(song)
        }
    }

    fun clearQueue(onCleared: () -> Unit) {
        playerManager.clearQueue()
        viewModelScope.launch { prefs.queue.clearLastQueue() }
        onCleared()
    }
}

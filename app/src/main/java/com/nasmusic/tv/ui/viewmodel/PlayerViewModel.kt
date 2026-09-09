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
import com.nasmusic.tv.util.AppLog
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

    /** 播放开始（供 PlayHistoryViewModel 记录最近播放），由 MainViewModel 注入 */
    var onRecordPlay: ((Song) -> Unit)? = null
    /** 错误消息通道（经 MainViewModel 的 errorMessage 语义） */
    var showMessage: ((String) -> Unit)? = null

    // B-13: 播放器状态（currentSong/isPlaying/progress/duration 由 PlayerManager 拥有）
    val currentSong: StateFlow<Song?> = playerManager.currentSong
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val progress: StateFlow<Long> = playerManager.progress
    val duration: StateFlow<Long> = playerManager.duration
    val queue: StateFlow<List<Song>> = playerManager.queue
    val currentIndex: StateFlow<Int> = playerManager.currentIndex
    /** 实时频谱数据（96 柱幅值），来自 SpectrumAnalyzer / Visualizer FFT */
    val spectrumData: StateFlow<FloatArray> = playerManager.spectrumData

    // B-13: playMode 由本类拥有（UI/设置状态，不归 PlayerManager）
    private val _playMode = MutableStateFlow(PlayMode.SEQUENTIAL)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    /** 播放模式变更监听（MainViewModel 用来同步 MvSearchViewModel 等），由 MainViewModel 注入 */
    var onPlayModeChanged: ((PlayMode) -> Unit)? = null

    // P4 修复：播放解析代数计数器。每次发起新的 resolveAndPlayByIndex（切歌解析）时 +1，
    // 解析完成回写队列前比对代数，若已过期（期间又发生切歌）则丢弃本次结果，
    // 避免用旧队列快照回滚用户后续操作。
    private var resolveGeneration = 0

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
        playerManager.playSong(song)
        // 歌词由 currentSong.collect 统一触发，避免重复调用
        onRecordPlay?.invoke(song)
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val firstSong = songs[startIndex.coerceIn(0, songs.lastIndex)]
        AppLog.d("PlayerViewModel", "playQueue: ${songs.size} songs, start=$startIndex, first=${firstSong.title}, coverUrl=${firstSong.coverUrl ?: "null"}")

        // 网络歌曲的 streamUrl 需要异步解析，否则 ExoPlayer 收到空 URI 不会开始播放
        val needsResolve = songs.any { it.isNetworkSong && it.streamUrl.isNullOrBlank() }
        if (needsResolve) {
            // 只解析第一首歌曲的 URL，立即播放；后续歌曲在播放器自动过渡时懒加载。
            // 原实现逐首解析所有歌曲（songs.map），30 首可能耗时 30-90s 才开始播放。
            AppLog.d("PlayerViewModel", "playQueue: needsResolve, resolving first song only: ${firstSong.title}")
            // 立即更新队列状态，避免异步解析期间 UI 读到旧的队列数据
            playerManager.restoreQueue(songs, startIndex)

            viewModelScope.launch {
                val resolvedFirst = if (firstSong.isNetworkSong && firstSong.streamUrl.isNullOrBlank()) {
                    try {
                        val url = nasMusicApp.networkMusicManager.resolvePlayUrl(firstSong)
                        if (!url.isNullOrBlank()) firstSong.copy(streamUrl = url) else firstSong
                    } catch (e: Exception) {
                        AppLog.e("PlayerViewModel", "playQueue: resolveUrl failed for ${firstSong.title}", e)
                        firstSong
                    }
                } else {
                    firstSong
                }
                // 检查第一首歌是否仍然无法解析
                if (resolvedFirst.isNetworkSong && resolvedFirst.streamUrl.isNullOrBlank()) {
                    AppLog.w("PlayerViewModel", "playQueue: failed to resolve URL for ${resolvedFirst.title}")
                    showMessage?.invoke(getApplication<Application>().getString(R.string.resolve_url_endpoint_failed))
                }
                // 只更新第一首歌的 streamUrl，其余歌曲保持空 URL，在播放器过渡时按需解析
                val resolved = songs.toMutableList()
                resolved[startIndex.coerceIn(0, songs.lastIndex)] = resolvedFirst
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
        val song = currentSong.value
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
     * - 网络歌曲：通过 NetworkMusicManager.resolvePlayUrl() 解析
     * - NAS 歌曲：通过 adapter.getSongsByIds() 获取 streamUrl
     */
    private fun resolveAndPlayCurrentSong(song: Song) {
        viewModelScope.launch {
            try {
                val playUrl = if (song.isNetworkSong) {
                    nasMusicApp.networkMusicManager.resolvePlayUrl(song)
                } else {
                    // NAS 歌曲：通过后端获取 streamUrl
                    val adapter = backendRegistry.getAdapter()
                    if (adapter != null) {
                        val songs = adapter.getSongsByIds(listOf(song.id))
                        songs.firstOrNull()?.streamUrl
                    } else null
                }

                if (playUrl.isNullOrBlank()) {
                    AppLog.w("PlayerViewModel", "resolveAndPlayCurrentSong: failed to resolve streamUrl for ${song.title}")
                    showMessage?.invoke(getApplication<Application>().getString(R.string.resolve_url_failed_retry))
                    return@launch
                }

                AppLog.d("PlayerViewModel", "resolveAndPlayCurrentSong: resolved ${song.title} → $playUrl")
                // 更新队列中当前歌曲的 streamUrl，然后播放
                val currentQueue = queue.value
                val currentIndexValue = currentIndex.value
                val updatedQueue = currentQueue.mapIndexed { index, s ->
                    if (index == currentIndexValue) s.copy(streamUrl = playUrl) else s
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
        val queueValue = queue.value
        val nextIndex = currentIndex.value + 1
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
        val queueValue = queue.value
        val prevIndex = currentIndex.value - 1
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
     * - 网络歌曲：通过 NetworkMusicManager.resolvePlayUrl() 实时解析
     * - NAS 歌曲：通过 adapter.getSongsByIds() 获取 streamUrl
     */
    private suspend fun resolveStreamUrl(song: Song): String? {
        return if (song.isNetworkSong) {
            nasMusicApp.networkMusicManager.resolvePlayUrl(song)
        } else {
            val adapter = backendRegistry.getAdapter()
            if (adapter != null) {
                adapter.getSongsByIds(listOf(song.id)).firstOrNull()?.streamUrl
            } else null
        }
    }

    fun resolveAndPlayByIndex(targetIndex: Int) {
        // P4 修复：进入即递增代数，标记本次解析为「最新」；解析期间若有新的切歌解析
        // 会再次递增，使本次挂起解析在回写前被判定为过期。
        val generation = ++resolveGeneration
        val queueValue = queue.value
        val song = queueValue.getOrNull(targetIndex) ?: return
        viewModelScope.launch {
            try {
                var playUrl = resolveStreamUrl(song)
                // 初次解析失败（网络瞬时抖动/端点超时）：延迟 1.5s 自动重试一次
                if (playUrl.isNullOrBlank()) {
                    AppLog.w("PlayerViewModel", "resolveAndPlayByIndex: initial resolve failed for ${song.title}, retrying in 1.5s")
                    delay(1500)
                    playUrl = resolveStreamUrl(song)
                }
                if (playUrl.isNullOrBlank()) {
                    // 重试仍失败：不再静默卡在"已切歌未播放"状态，自动跳到下一首
                    if (generation != resolveGeneration) return@launch
                    AppLog.w("PlayerViewModel", "resolveAndPlayByIndex: failed after retry, skipping ${song.title}")
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
                val latestQueue = queue.value
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

    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)

    /**
     * 恢复上次播放队列（仅恢复 UI 状态，不自动播放）
     *
     * 从 DataStore 读取持久化的队列，调用 PlayerManager.restoreQueue() 设置队列和索引。
     * NAS 歌曲的 streamUrl 暂时为空，等后端连接成功后由 updateRestoredQueueStreamUrls() 更新。
     * 网络歌曲的 streamUrl 在播放时由 NetworkMusicManager.resolvePlayUrl() 解析。
     */
    suspend fun restoreLastQueue() {
        val lastQueue = prefs.getLastQueue() ?: return
        val songs = lastQueue.songs
        if (songs.isNullOrEmpty()) return
        AppLog.d("PlayerViewModel", "restoreLastQueue: ${lastQueue.songs.size} songs, index=${lastQueue.currentIndex}")
        playerManager.restoreQueue(lastQueue.songs, lastQueue.currentIndex)
    }

    /**
     * 后端连接成功后，更新恢复队列中 NAS 歌曲的 streamUrl
     */
    fun updateRestoredQueueStreamUrls() {
        val currentQueue = queue.value
        if (currentQueue.isEmpty()) return
        val adapter = backendRegistry.getAdapter() ?: return

        // 筛选需要更新 streamUrl 的 NAS 歌曲
        val nasSongIds = currentQueue.filter { !it.isNetworkSong }.map { it.id }
        if (nasSongIds.isEmpty()) return

        viewModelScope.launch {
            try {
                val updatedSongs = adapter.getSongsByIds(nasSongIds)
                val songMap = updatedSongs.associateBy { it.id }
                // 合并：NAS 歌曲用更新后的版本（含 streamUrl），网络歌曲保留原样
                val mergedQueue = currentQueue.map { song ->
                    if (!song.isNetworkSong) {
                        songMap[song.id] ?: song
                    } else {
                        song
                    }
                }
                // 只在队列未变化时更新（避免覆盖用户操作）
                if (mergedQueue.size == queue.value.size) {
                    val currentIndexValue = currentIndex.value
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
        val existingIds = playerManager.queue.value.map { it.id }.toHashSet()
        val toAdd = songs.filter { it.id !in existingIds }.distinctBy { it.id }
        if (toAdd.isNotEmpty()) playerManager.addToQueue(toAdd)
    }

    /**
     * 切换歌曲在队列中的状态：不在队列则加入，在队列则移除。
     * 当前正在播放的歌曲不会被移除（避免误中断播放）。
     */
    fun toggleQueueSong(song: Song) {
        val currentQueue = queue.value
        val inQueue = currentQueue.any { it.id == song.id }
        if (inQueue) {
            playerManager.removeSongFromQueue(song)
        } else {
            playerManager.addToQueue(song)
        }
    }

    fun clearQueue(onCleared: () -> Unit) {
        playerManager.clearQueue()
        viewModelScope.launch { prefs.clearLastQueue() }
        onCleared()
    }
}

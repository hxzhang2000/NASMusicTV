package com.nasmusic.tv.player

import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * 播放管理器
 * 单例模式，管理播放状态、队列和播放模式
 */
class PlayerManager() {

    private var player: ExoPlayer? = null

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
            player?.let { p ->
                // seek 期间不更新进度，防止 ExoPlayer 内部重置位置时覆盖 _progress
                if (!seekPending) {
                    _progress.value = p.currentPosition
                }
                val dur = p.duration
                if (dur > 0) _duration.value = dur
            }
            progressHandler.postDelayed(this, 1000)
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
    private var vocalRemovalProcessor: VocalRemovalProcessor? = null

    /** 由 PlaybackService 注入处理器实例 */
    fun setVocalRemovalProcessor(processor: VocalRemovalProcessor) {
        vocalRemovalProcessor = processor
    }

    /** 开关人声消除（实时生效） */
    fun setVocalRemovalEnabled(enabled: Boolean) {
        vocalRemovalProcessor?.setEnabled(enabled)
    }

    /** 查询当前是否启用人声消除 */
    fun isVocalRemovalEnabled(): Boolean {
        return vocalRemovalProcessor?.isEnabled() ?: false
    }

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
            // seek 期间忽略播放状态变化，防止播放按钮闪烁
            if (seekPending) {
                AppLog.d("NASMusic", "playerListener: onIsPlayingChanged=$isPlaying ignored (seekPending)")
                return
            }
            _isPlaying.value = isPlaying
            if (isPlaying) {
                progressHandler.post(progressUpdateRunnable)
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
                initSpectrumAnalyzer()
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
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            AppLog.e("PlayerManager", "Player error: ${error.message}", error)
            _playerError.value = error.message ?: "播放错误"
            // 当前歌曲 streamUrl 为空时（如恢复队列中的网络歌曲），不自动跳下一首，
            // 避免级联错误（下一首也可能 streamUrl 为空）。
            // 用户按播放时由 resolveAndPlayCurrentSong() 解析链接后正常播放。
            val currentSong = _queue.value.getOrNull(_currentIndex.value)
            if (currentSong != null && currentSong.streamUrl.isNullOrBlank()) {
                AppLog.d("PlayerManager", "onPlayerError: skipped auto-next (current song streamUrl is empty)")
                return
            }
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
        initSpectrumAnalyzer()
        AppLog.d("PlayerManager", "setPlayer: player initialized")
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
            val mediaItem = MediaItem.fromUri(streamUrl)
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
            MediaItem.fromUri(song.streamUrl ?: "")
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
        val mediaItems = songs.map { MediaItem.fromUri(it.streamUrl ?: "") }
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
                val mediaItems = queue.map { MediaItem.fromUri(it.streamUrl ?: "") }
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
        player?.seekTo(positionMs)
        _progress.value = positionMs
        AppLog.d("NASMusic", "seekTo: after seek, player.currentPosition=${player?.currentPosition}, seekPending=$seekPending")
        // 2 秒后自动清除 seekPending，防止永久阻塞进度更新
        progressHandler.postDelayed({
            AppLog.d("NASMusic", "seekTo: timeout, seekPending=false, player.currentPosition=${player?.currentPosition}")
            seekPending = false
        }, 2000)
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
            val mediaItem = MediaItem.fromUri(song.streamUrl ?: "")
            player?.addMediaItem(mediaItem)
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
                MediaItem.fromUri(song.streamUrl ?: "")
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
        // 停止频谱重试（将所有回调从消息队列中移除）
        spectrumAnalyzerRetryCount = 5
        player?.removeListener(playerListener)
        player = null
        equalizer?.release()
        equalizer = null
        spectrumAnalyzer.release()
    }

    // --- B-4 均衡器支持 ---
    private var equalizer: Equalizer? = null
    private var audioSessionId: Int = 0

    // --- 频谱分析（真实 FFT 可视化） ---
    private val spectrumAnalyzer = SpectrumAnalyzer()
    val spectrumData: StateFlow<FloatArray> = spectrumAnalyzer.spectrumData

    /**
     * 初始化均衡器（在 setPlayer 之后调用）
     */
    fun initEqualizer(): Boolean {
        return try {
            val p = player ?: return false
            audioSessionId = p.audioSessionId
            if (audioSessionId == 0) return false

            // Release old equalizer if exists
            equalizer?.release()
            equalizer = Equalizer(0, audioSessionId)
            equalizer?.enabled = true
            AppLog.d("PlayerManager", "initEqualizer: initialised for session $audioSessionId")

            // 初始化频谱分析器
            initSpectrumAnalyzer()
            true
        } catch (e: Exception) {
            AppLog.e("PlayerManager", "initEqualizer failed", e)
            false
        }
    }

    /**
     * 初始化频谱分析器（使用当前音频会话 ID）
     *
     * 如果音频会话尚未就绪（audioSessionId == 0），
     * 在后续 5 秒内每秒重试一次。
     */
    private var spectrumAnalyzerRetryCount = 0

    private fun initSpectrumAnalyzer() {
        val sessionId = player?.audioSessionId ?: 0
        if (sessionId > 0) {
            audioSessionId = sessionId
            spectrumAnalyzerRetryCount = 0
            AppLog.d("PlayerManager", "initSpectrumAnalyzer: attaching to session $sessionId")
            spectrumAnalyzer.attach(sessionId)
        } else if (spectrumAnalyzerRetryCount < 5) {
            spectrumAnalyzerRetryCount++
            AppLog.w("PlayerManager",
                "initSpectrumAnalyzer: no valid audio session yet, " +
                "retry ${spectrumAnalyzerRetryCount}/5 in 1s")
            progressHandler.postDelayed({
                initSpectrumAnalyzer()
            }, 1000)
        } else {
            AppLog.w("PlayerManager", "initSpectrumAnalyzer: gave up after ${spectrumAnalyzerRetryCount} retries")
        }
    }

    /**
     * 设置指定频段的增益值
     * @param bandIndex 频段索引 (0-based)
     * @param gainDb 增益值 (dB, 通常 -15 到 +15)
     */
    fun setEqualizerBand(bandIndex: Int, gainDb: Float): Boolean {
        return try {
            val eq = equalizer
            if (eq == null) {
                if (!initEqualizer()) return false
            }
            val bands = equalizer?.numberOfBands ?: return false
            if (bandIndex < 0 || bandIndex >= bands) return false
            val gainMillibels = (gainDb * 100).toInt().toShort()
            equalizer?.setBandLevel(bandIndex.toShort(), gainMillibels)
            AppLog.d("PlayerManager", "setEqualizerBand: band=$bandIndex gain=${gainDb}dB")
            true
        } catch (e: Exception) {
            AppLog.e("PlayerManager", "setEqualizerBand failed", e)
            false
        }
    }

    /**
     * 批量设置所有频段增益值（用于应用预设）
     * @param gains 各频段增益值数组 (dB)，数组长度需与设备频段数匹配
     */
    fun setEqualizerBands(gains: List<Float>): Boolean {
        return try {
            val eq = equalizer
            if (eq == null) {
                if (!initEqualizer()) return false
            }
            val eqInstance = equalizer ?: return false
            val bandCount = eqInstance.numberOfBands.toInt()
            val range = eqInstance.bandLevelRange
            val minLevel = range[0]
            val maxLevel = range[1]

            for (i in 0 until minOf(bandCount, gains.size)) {
                val gainMb = (gains[i] * 100).toInt().toShort()
                val clamped = gainMb.coerceIn(minLevel, maxLevel)
                eqInstance.setBandLevel(i.toShort(), clamped)
            }
            AppLog.d("PlayerManager", "setEqualizerBands: applied ${minOf(bandCount, gains.size)} bands")
            true
        } catch (e: Exception) {
            AppLog.e("PlayerManager", "setEqualizerBands failed", e)
            false
        }
    }

    /**
     * 获取当前频段增益值
     */
    fun getEqualizerBandLevel(bandIndex: Int): Float {
        return try {
            val eq = equalizer ?: return 0f
            val level = eq.getBandLevel(bandIndex.toShort())
            level / 100f
        } catch (e: Exception) {
            0f
        }
    }

    /**
     * 获取均衡器频段数量
     */
    fun getEqualizerBandCount(): Int {
        return try {
            equalizer?.numberOfBands?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 获取频段中心频率（Hz）
     */
    fun getEqualizerCenterFreq(bandIndex: Int): Int {
        return try {
            equalizer?.getCenterFreq(bandIndex.toShort())?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 禁用均衡器
     */
    fun disableEqualizer() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
            equalizer = null
            AppLog.d("PlayerManager", "disableEqualizer: disabled")
        } catch (e: Exception) {
            AppLog.e("PlayerManager", "disableEqualizer failed", e)
        }
    }
}

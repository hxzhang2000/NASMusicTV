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

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            player?.let { p ->
                _progress.value = p.currentPosition
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

    // 随机播放历史记录，避免连续重复
    private val shuffleHistory = mutableListOf<Int>()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
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
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateCurrentSongFromPlayer()
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
            android.util.Log.e("PlayerManager", "Player error: ${error.message}", error)
            _playerError.value = error.message ?: "播放错误"
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
        AppLog.d("PlayerManager", "setPlayer: player initialized")
    }

    fun playSong(song: Song) {
        val streamUrl = song.streamUrl ?: return
        val p = player
        if (p == null) {
            android.util.Log.e("PlayerManager", "playSong: player is null!")
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
                android.util.Log.e("PlayerManager", "playSong seek failed", e)
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
                android.util.Log.e("PlayerManager", "playSong failed", e)
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
            android.util.Log.e("PlayerManager", "playQueue: player is null!")
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
            p.play()
            AppLog.d("PlayerManager", "playQueue: playing ${songs.size} songs, start=$startIndex")
        } catch (e: Exception) {
            android.util.Log.e("PlayerManager", "playQueue failed", e)
        }

        // 从歌曲数据初始化时长（player.duration 可能返回 C.TIME_UNSET）
        val currentSong = songs.getOrNull(startIndex)
        if (currentSong != null && currentSong.durationMs > 0) {
            _duration.value = currentSong.durationMs
        }

        updateCurrentSongFromPlayer()
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
                    p.play()
                } else {
                    // 队列末尾，回到第一首
                    _currentIndex.value = 0
                    p.seekTo(0, 0)
                    p.play()
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
        player?.seekTo(positionMs)
        _progress.value = positionMs
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
            android.util.Log.e("PlayerManager", "moveMediaItem failed", e)
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
        player?.removeListener(playerListener)
        player = null
        equalizer?.release()
        equalizer = null
    }

    // --- B-4 均衡器支持 ---
    private var equalizer: Equalizer? = null
    private var audioSessionId: Int = 0

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
            true
        } catch (e: Exception) {
            android.util.Log.e("PlayerManager", "initEqualizer failed", e)
            false
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
            android.util.Log.e("PlayerManager", "setEqualizerBand failed", e)
            false
        }
    }

    /**
     * 批量设置所有频段增益值（用于应用预设）
     * @param gains 各频段增益值数组 (dB)，数组长度需与设备频段数匹配
     */
    fun setEqualizerBands(gains: FloatArray): Boolean {
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
            android.util.Log.e("PlayerManager", "setEqualizerBands failed", e)
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
            android.util.Log.e("PlayerManager", "disableEqualizer failed", e)
        }
    }
}

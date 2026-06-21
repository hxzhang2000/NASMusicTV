package com.nasmusic.tv.player

import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * 播放管理器
 * 单例模式，管理播放状态、队列和播放模式
 */
class PlayerManager private constructor() {

    private var player: ExoPlayer? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            player?.let { p ->
                _progress.value = p.currentPosition
                val dur = p.duration
                if (dur > 0) _duration.value = dur
            }
            progressHandler.postDelayed(this, 500)
        }
    }

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _playMode = MutableStateFlow(PlayMode.SEQUENTIAL)
    val playMode: StateFlow<PlayMode> = _playMode

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

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
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
            _progress.value = newPosition.positionMs
        }
    }

    fun setPlayer(exoPlayer: ExoPlayer) {
        player = exoPlayer
        exoPlayer.addListener(playerListener)
        progressHandler.post(progressUpdateRunnable)
        android.util.Log.d("PlayerManager", "setPlayer: player initialized")
    }

    fun playSong(song: Song) {
        val streamUrl = song.streamUrl ?: return
        val p = player
        if (p == null) {
            android.util.Log.e("PlayerManager", "playSong: player is null!")
            return
        }

        android.util.Log.d("PlayerManager", "playSong: ${song.title}, currentPlaying=${p.isPlaying}")

        // Check if song is already in current queue — if so, seek to it (gapless path)
        val existingIndex = _queue.value.indexOf(song)
        if (existingIndex >= 0) {
            _currentIndex.value = existingIndex
            try {
                p.seekTo(existingIndex, 0)
                p.play()
                android.util.Log.d("PlayerManager", "playSong: seeking to existing queue item $existingIndex")
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
                android.util.Log.d("PlayerManager", "playSong: playing ${song.title}")
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
            android.util.Log.d("PlayerManager", "playQueue: playing ${songs.size} songs, start=$startIndex")
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
            android.util.Log.d("PlayerManager", "playPause: wasPlaying=$wasPlaying, state=${it.playbackState}")
            if (wasPlaying) {
                it.pause()
                android.util.Log.d("PlayerManager", "playPause: paused")
            } else {
                it.play()
                android.util.Log.d("PlayerManager", "playPause: playing")
            }
        }
    }

    fun next() {
        when (_playMode.value) {
            PlayMode.SHUFFLE -> playRandom()
            PlayMode.REPEAT_ONE -> {
                player?.seekTo(0)
                player?.play()
            }
            else -> {
                val nextIndex = _currentIndex.value + 1
                if (nextIndex < _queue.value.size) {
                    player?.seekToNextMediaItem()
                } else if (_playMode.value == PlayMode.REPEAT_ALL) {
                    player?.seekTo(0, 0)
                }
            }
        }
    }

    fun previous() {
        when (_playMode.value) {
            PlayMode.SHUFFLE -> playRandom()
            else -> {
                val prevIndex = _currentIndex.value - 1
                if (prevIndex >= 0) {
                    player?.seekToPreviousMediaItem()
                } else if (_playMode.value == PlayMode.REPEAT_ALL) {
                    player?.seekTo(_queue.value.size - 1, 0)
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        android.util.Log.d("NASMusic", "seekTo: position=$positionMs, player=${player != null}, state=${player?.playbackState}")
        player?.seekTo(positionMs)
        _progress.value = positionMs
    }

    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        player?.shuffleModeEnabled = (mode == PlayMode.SHUFFLE)
        player?.repeatMode = when (mode) {
            PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
            PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun togglePlayMode() {
        val modes = PlayMode.values()
        val currentIndex = modes.indexOf(_playMode.value)
        val nextIndex = (currentIndex + 1) % modes.size
        setPlayMode(modes[nextIndex])
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
        val currentQueue = _queue.value.toMutableList()
        if (index in currentQueue.indices) {
            currentQueue.removeAt(index)
            _queue.value = currentQueue
            player?.removeMediaItem(index)
        }
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

        android.util.Log.d("PlayerManager", "moveItem: $fromIndex → $toIndex, currentIndex=${_currentIndex.value}")
        return true
    }

    fun clearQueue() {
        _queue.value = emptyList()
        player?.clearMediaItems()
    }

    fun updateProgress() {
        player?.let {
            _progress.value = it.currentPosition
            _duration.value = it.duration.coerceAtLeast(0)
        }
    }

    fun onPlaybackEnded() {
        when (_playMode.value) {
            PlayMode.REPEAT_ONE -> {
                player?.seekTo(0)
                player?.play()
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

    private fun playRandom() {
        val queue = _queue.value
        if (queue.isNotEmpty()) {
            val randomIndex = Random.nextInt(queue.size)
            player?.seekTo(randomIndex, 0)
            player?.play()
        }
    }

    private fun updateCurrentSongFromPlayer() {
        val currentIndex = player?.currentMediaItemIndex ?: 0
        _currentIndex.value = currentIndex
        if (currentIndex in _queue.value.indices) {
            _currentSong.value = _queue.value[currentIndex]
        }
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
            android.util.Log.d("PlayerManager", "initEqualizer: initialised for session $audioSessionId")
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
            android.util.Log.d("PlayerManager", "setEqualizerBand: band=$bandIndex gain=${gainDb}dB")
            true
        } catch (e: Exception) {
            android.util.Log.e("PlayerManager", "setEqualizerBand failed", e)
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
            android.util.Log.d("PlayerManager", "disableEqualizer: disabled")
        } catch (e: Exception) {
            android.util.Log.e("PlayerManager", "disableEqualizer failed", e)
        }
    }

    companion object {
        @Volatile
        private var instance: PlayerManager? = null

        fun getInstance(): PlayerManager {
            return instance ?: synchronized(this) {
                instance ?: PlayerManager().also { instance = it }
            }
        }
    }
}

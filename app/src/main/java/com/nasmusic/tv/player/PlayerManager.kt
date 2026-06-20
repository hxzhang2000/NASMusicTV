package com.nasmusic.tv.player

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
        // Replace entire queue with this single song
        _queue.value = listOf(song)
        _currentIndex.value = 0
        val mediaItem = MediaItem.fromUri(streamUrl)
        try {
            p.setMediaItem(mediaItem)
            p.prepare()
            p.play()
            android.util.Log.d("PlayerManager", "playSong: playing ${song.title}")
        } catch (e: Exception) {
            android.util.Log.e("PlayerManager", "playSong failed", e)
        }
        _currentSong.value = song
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

        updateCurrentSongFromPlayer()
    }

    fun playPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
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

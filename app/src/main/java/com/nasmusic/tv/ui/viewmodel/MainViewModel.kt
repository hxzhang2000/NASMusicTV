package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.BuildConfig
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsAvailability
import com.nasmusic.tv.data.model.LyricsSource
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.lyrics.LyricsManager
import com.nasmusic.tv.player.PlayerManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 应用主 ViewModel
 * 管理播放器、歌曲队列、曲库数据、设置等
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val playerManager = PlayerManager.getInstance()
    private val lyricsManager = LyricsManager(app)
    private val prefs = AppPreferences.getInstance(app)

    // --- 导航状态 ---
    private val _currentScreen = MutableStateFlow(Screen.NowPlaying)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // --- 曲库数据 ---
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _albumSongsCache = MutableStateFlow<Map<String, List<Song>>>(emptyMap())

    // --- 加载状态 ---
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // --- 歌词 ---
    private val _currentLyrics = MutableStateFlow<Lyrics?>(null)
    val currentLyrics: StateFlow<Lyrics?> = _currentLyrics.asStateFlow()

    private val _lyricsAvailability = MutableStateFlow(LyricsAvailability())
    val lyricsAvailability: StateFlow<LyricsAvailability> = _lyricsAvailability.asStateFlow()

    // --- 播放器状态（镜像 PlayerManager 的 StateFlow）---
    val currentSong: StateFlow<Song?> = playerManager.currentSong
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val playMode: StateFlow<PlayMode> = playerManager.playMode
    val progress: StateFlow<Long> = playerManager.progress
    val duration: StateFlow<Long> = playerManager.duration
    val queue: StateFlow<List<Song>> = playerManager.queue
    val currentIndex: StateFlow<Int> = playerManager.currentIndex

    // --- 连接状态 ---
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isLibraryLoading = MutableStateFlow(false)
    val isLibraryLoading: StateFlow<Boolean> = _isLibraryLoading.asStateFlow()

    private val _serverDisplayName = MutableStateFlow("")
    val serverDisplayName: StateFlow<String> = _serverDisplayName.asStateFlow()

    // --- 启动连接提示 ---
    private val _showConnectPrompt = MutableStateFlow(false)
    val showConnectPrompt: StateFlow<Boolean> = _showConnectPrompt.asStateFlow()

    // --- 连接结果提示消息（显示几秒后自动清除）---
    private val _connectMessage = MutableStateFlow<String?>(null)
    val connectMessage: StateFlow<String?> = _connectMessage.asStateFlow()

    // --- 应用设置 ---
    val appSettings: StateFlow<AppSettings> = prefs.appSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    // 服务器配置
    val serverConfig: StateFlow<ServerConfig> = prefs.serverConfig.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ServerConfig.Empty
    )

    private var progressUpdateJob: Job? = null
    private var lyricsLoadJob: Job? = null

    init {
        startProgressUpdate()

        viewModelScope.launch {
            // 等待配置加载完成后判断是否显示连接提示
            val config = prefs.serverConfig.first()
            if (config.baseUrl.isNotBlank()) {
                _showConnectPrompt.value = true
            }
        }
    }

    private fun startProgressUpdate() {
        if (progressUpdateJob?.isActive == true) return
        progressUpdateJob = viewModelScope.launch {
            while (true) {
                delay(500)
                playerManager.updateProgress()
            }
        }
    }

    // --- 导航 ---
    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    // --- 连接 ---
    suspend fun connectToServer(config: ServerConfig): Boolean {
        _isLoading.value = true
        return try {
            val success = BackendRegistry.initialize(config)
            if (success) {
                _isConnected.value = true
                _serverDisplayName.value = BackendRegistry.getServerDisplayName()
                prefs.saveServerConfig(config.copy(isConnected = true))
                // 连接成功后加载初始数据
                loadLibrary()
            }
            success
        } catch (e: Exception) {
            false
        } finally {
            _isLoading.value = false
        }
    }

    fun disconnect() {
        BackendRegistry.disconnect()
        _isConnected.value = false
        _serverDisplayName.value = ""
        _albums.value = emptyList()
        _songs.value = emptyList()
        viewModelScope.launch {
            val current = serverConfig.value
            prefs.saveServerConfig(current.copy(isConnected = false))
        }
    }

    /**
     * 使用已保存的服务器配置自动连接
     * @param silent 静默模式（不显示提示消息）
     */
    fun connectToSavedServer(silent: Boolean = false) {
        viewModelScope.launch {
            val config = prefs.serverConfig.first()
            if (config.baseUrl.isBlank()) {
                if (!silent) {
                    _connectMessage.value = "没有已保存的服务器配置"
                    delay(3000)
                    _connectMessage.value = null
                }
                return@launch
            }

            _showConnectPrompt.value = false
            _isLoading.value = true
            try {
                val success = BackendRegistry.initialize(config)
                if (success) {
                    _isConnected.value = true
                    _serverDisplayName.value = BackendRegistry.getServerDisplayName()
                    prefs.saveServerConfig(config.copy(isConnected = true))
                    loadLibrary()
                    if (!silent) {
                        _connectMessage.value = "已连接到 ${BackendRegistry.getServerDisplayName()}"
                        delay(3000)
                        _connectMessage.value = null
                    }
                } else {
                    if (!silent) {
                        _connectMessage.value = "连接失败，请检查服务器设置"
                        delay(3000)
                        _connectMessage.value = null
                    }
                }
            } catch (e: Exception) {
                if (!silent) {
                    _connectMessage.value = "连接失败: ${e.message}"
                    delay(3000)
                    _connectMessage.value = null
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 关闭连接提示对话框
     */
    fun dismissConnectPrompt() {
        _showConnectPrompt.value = false
    }

    // --- 曲库 ---
    private fun loadLibrary() {
        _isLibraryLoading.value = true
        viewModelScope.launch {
            val adapter = BackendRegistry.getAdapter() ?: run {
                _isLibraryLoading.value = false
                return@launch
            }
            try {
                android.util.Log.d("NASMusic", "loadLibrary: loading albums...")
                _albums.value = adapter.getAlbums()
                android.util.Log.d("NASMusic", "loadLibrary: ${_albums.value.size} albums loaded")
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadLibrary albums failed", e)
            }
            try {
                val songLimit = if (BuildConfig.DEBUG) 10 else 100000
                android.util.Log.d("NASMusic", "loadLibrary: loading songs... (limit=$songLimit, debug=${BuildConfig.DEBUG})")
                _songs.value = adapter.getSongs(songLimit)
                android.util.Log.d("NASMusic", "loadLibrary: ${_songs.value.size} songs loaded")
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadLibrary songs failed", e)
            }
            _isLibraryLoading.value = false
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            _isLoading.value = true
            loadLibrary()
            _isLoading.value = false
        }
    }

    fun loadAlbumSongs(albumId: String) {
        viewModelScope.launch {
            val adapter = BackendRegistry.getAdapter() ?: return@launch
            try {
                val songs = adapter.getAlbumSongs(albumId)
                val cache = _albumSongsCache.value.toMutableMap()
                cache[albumId] = songs
                _albumSongsCache.value = cache
            } catch (e: Exception) { /* ignore */ }
        }
    }

    fun getAlbumSongsCache(albumId: String): List<Song> =
        _albumSongsCache.value[albumId] ?: emptyList()

    // --- 播放控制 ---
    fun playSong(song: Song) {
        android.util.Log.d("NASMusic", "playSong: ${song.title}, coverUrl=${song.coverUrl ?: "null"}")
        playerManager.playSong(song)
        loadLyricsForCurrentSong()
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val firstSong = songs[startIndex.coerceIn(0, songs.lastIndex)]
        android.util.Log.d("NASMusic", "playQueue: ${songs.size} songs, start=$startIndex, first=${firstSong.title}, coverUrl=${firstSong.coverUrl ?: "null"}")
        playerManager.playQueue(songs, startIndex)
        loadLyricsForCurrentSong()
    }

    fun playPause() = playerManager.playPause()
    fun next() = playerManager.next()
    fun previous() = playerManager.previous()
    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)

    fun togglePlayMode() = playerManager.togglePlayMode()

    fun setPlayMode(mode: PlayMode) = playerManager.setPlayMode(mode)

    fun addSongToQueue(song: Song) = playerManager.addToQueue(song)

    fun removeFromQueue(index: Int) = playerManager.removeFromQueue(index)

    fun clearQueue() = playerManager.clearQueue()

    private fun loadLyricsForCurrentSong() {
        lyricsLoadJob?.cancel()
        _currentLyrics.value = null
        _lyricsAvailability.value = LyricsAvailability()
        val song = currentSong.value ?: return
        android.util.Log.d("NASMusic", "loadLyrics: loading for ${song.title} by ${song.artist}")
        lyricsLoadJob = viewModelScope.launch {
            try {
                // 先检查可用来源
                val availability = lyricsManager.checkAvailability(song)
                _lyricsAvailability.value = availability
                android.util.Log.d("NASMusic", "loadLyrics: backend=${availability.hasBackend}, network=${availability.hasNetwork}")

                // 自动选择第一个可用来源
                val lyrics = availability.backend ?: availability.network
                _currentLyrics.value = lyrics
                android.util.Log.d("NASMusic", "loadLyrics: source=${lyrics?.source}, lines=${lyrics?.lines?.size}")
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadLyrics failed", e)
            }
        }
    }

    /**
     * 切换歌词来源
     */
    fun switchLyricsSource(source: LyricsSource) {
        val song = currentSong.value ?: return
        android.util.Log.d("NASMusic", "switchLyricsSource: $source")
        viewModelScope.launch {
            try {
                val lyrics = lyricsManager.getLyricsFromSource(song, source)
                _currentLyrics.value = lyrics
                android.util.Log.d("NASMusic", "switchLyricsSource: source=${lyrics?.source}, lines=${lyrics?.lines?.size}")
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "switchLyricsSource failed", e)
            }
        }
    }

    // --- 设置 ---
    fun updateDarkTheme(enabled: Boolean) = viewModelScope.launch {
        prefs.setDarkTheme(enabled)
    }

    fun updateAnimationsEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setAnimationsEnabled(enabled)
    }

    fun updateAutoPlayNext(enabled: Boolean) = viewModelScope.launch {
        prefs.setAutoPlayNext(enabled)
    }

    fun updateDefaultPlayMode(mode: PlayMode) = viewModelScope.launch {
        prefs.setDefaultPlayMode(mode)
        setPlayMode(mode)
    }

    fun updateCacheLyrics(enabled: Boolean) = viewModelScope.launch {
        prefs.setCacheLyrics(enabled)
    }

    fun updateCacheCover(enabled: Boolean) = viewModelScope.launch {
        prefs.setCacheCover(enabled)
    }

    fun updateLyricsOffset(offsetMs: Long) = viewModelScope.launch {
        prefs.setLyricsOffset(offsetMs)
    }
}

/**
 * 应用屏幕枚举
 */
enum class Screen {
    NowPlaying,
    Library,
    Queue,
    Settings,
    ServerConnect
}

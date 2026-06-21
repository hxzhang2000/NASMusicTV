package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.BuildConfig
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsAvailability
import com.nasmusic.tv.data.model.LyricsSource
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.EqualizerPreset
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.player.CoverArtManager
import com.nasmusic.tv.lyrics.LyricsManager
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.util.ArtistSplitter
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

    // --- 详情页状态 ---
    private val _selectedAlbum = MutableStateFlow<Album?>(null)
    val selectedAlbum: StateFlow<Album?> = _selectedAlbum.asStateFlow()

    private val _selectedArtistName = MutableStateFlow<String?>(null)
    val selectedArtistName: StateFlow<String?> = _selectedArtistName.asStateFlow()

    private val _albumSongsCache = MutableStateFlow<Map<String, List<Song>>>(emptyMap())

    // --- 歌唱家拆分映射 ---
    // songId → 拆分后的歌唱家列表（不含去重中间状态，直接展开后的结果）
    private val _songArtistMap = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val songArtistMap: StateFlow<Map<String, List<String>>> = _songArtistMap.asStateFlow()
    // 歌唱家 → 对应的歌曲列表
    private val _artistSongsMap = MutableStateFlow<Map<String, List<Song>>>(emptyMap())
    val artistSongsMap: StateFlow<Map<String, List<Song>>> = _artistSongsMap.asStateFlow()

    // --- B-1 收藏 ---
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()
    private val _favoriteSongs = MutableStateFlow<List<Song>>(emptyList())
    val favoriteSongs: StateFlow<List<Song>> = _favoriteSongs.asStateFlow()

    // --- A-3 流派 ---
    private val _genres = MutableStateFlow<List<Genre>>(emptyList())
    val genres: StateFlow<List<Genre>> = _genres.asStateFlow()

    // --- D-2 网络状态 ---
    private val _isNetworkAvailable = MutableStateFlow(true)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

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

    // --- D-3 常规错误消息（数据加载失败、操作失败等）---
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private fun showError(msg: String) {
        _errorMessage.value = msg
        viewModelScope.launch {
            delay(5000)
            _errorMessage.value = null
        }
    }

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
            _connectMessage.value = "连接失败: ${e.message?.take(50)}"
            viewModelScope.launch {
                delay(3000)
                _connectMessage.value = null
            }
            false
        } finally {
            _isLoading.value = false
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            BackendRegistry.disconnect()
        }
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

            if (!silent) {
                _showConnectPrompt.value = false
            }
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
                    android.util.Log.w("NASMusic", "connectToSavedServer: initialize returned false")
                    if (!silent) {
                        _connectMessage.value = "连接失败，请检查服务器设置"
                        delay(3000)
                        _connectMessage.value = null
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "connectToSavedServer failed", e)
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

    /**
     * 根据当前歌曲列表构建歌唱家拆分映射（A-4）
     */
    private fun buildArtistMaps(songs: List<Song>) {
        val songMap = mutableMapOf<String, List<String>>()
        val artistMap = mutableMapOf<String, MutableList<Song>>()
        for (song in songs) {
            val artists = ArtistSplitter.split(song.artist)
            songMap[song.id] = artists
            for (name in artists) {
                artistMap.getOrPut(name) { mutableListOf() }.add(song)
            }
        }
        _songArtistMap.value = songMap
        _artistSongsMap.value = artistMap
    }

    /**
     * 加载收藏状态（B-1）
     */
    private suspend fun loadFavorites(adapter: BackendAdapter) {
        try {
            val favorites = adapter.getFavorites()
            _favoriteSongs.value = favorites
            _favoriteIds.value = favorites.map { it.id }.toSet()
            android.util.Log.d("NASMusic", "loadFavorites: ${favorites.size} favorites")
        } catch (e: Exception) {
            android.util.Log.e("NASMusic", "loadFavorites failed", e)
            showError("加载收藏失败: ${e.message?.take(50)}")
        }
    }

    /**
     * 加载流派列表（A-3）
     */
    private suspend fun loadGenres(adapter: BackendAdapter) {
        try {
            if (adapter.javaClass.methods.any { it.name == "getGenres" }) {
                _genres.value = adapter.getGenres()
                android.util.Log.d("NASMusic", "loadGenres: ${_genres.value.size} genres")
            }
        } catch (e: Exception) {
            android.util.Log.e("NASMusic", "loadGenres failed", e)
            showError("加载流派列表失败: ${e.message?.take(50)}")
        }
    }

    // --- 曲库 ---
    private fun loadLibrary() {
        _isLibraryLoading.value = true
        viewModelScope.launch {
            val adapter = BackendRegistry.getAdapter() ?: run {
                _isLibraryLoading.value = false
                return@launch
            }
            
            // 第一阶段：快速加载专辑和流派（不阻塞）
            try {
                android.util.Log.d("NASMusic", "loadLibrary: loading albums...")
                _albums.value = adapter.getAlbums()
                android.util.Log.d("NASMusic", "loadLibrary: ${_albums.value.size} albums loaded")
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadLibrary albums failed", e)
                showError("加载专辑列表失败: ${e.message?.take(50)}")
            }
            
            loadGenres(adapter)
            loadFavorites(adapter)
            
            // 第二阶段：分批加载歌曲（限制最大数量，避免内存溢出）
            try {
                val maxSongs = 50000 // 最多加载 50000 首，避免内存问题
                val batchSize = 500
                android.util.Log.d("NASMusic", "loadLibrary: loading songs in batches... (maxSongs=$maxSongs, batchSize=$batchSize)")
                
                val allSongs = mutableListOf<Song>()
                var currentOffset = 0
                var hasMore = true
                
                while (hasMore && allSongs.size < maxSongs) {
                    android.util.Log.d("NASMusic", "loadLibrary: fetching batch at offset=$currentOffset")
                    
                    val batch = adapter.getSongs(batchSize, currentOffset)
                    android.util.Log.d("NASMusic", "loadLibrary: got ${batch.size} songs in this batch")
                    
                    if (batch.isEmpty()) {
                        hasMore = false
                    } else {
                        // 计算还能添加多少首
                        val remaining = maxSongs - allSongs.size
                        val songsToAdd = if (batch.size > remaining) batch.take(remaining) else batch
                        
                        allSongs.addAll(songsToAdd)
                        
                        // 更新 UI（每批都更新，让用户看到进度）
                        _songs.value = allSongs.toList()
                        buildArtistMaps(allSongs)
                        
                        android.util.Log.d("NASMusic", "loadLibrary: total ${allSongs.size} songs loaded so far")
                        
                        // 如果这批少于 batchSize，说明已经是最后一批
                        // 或者已经达到限制
                        if (batch.size < batchSize || allSongs.size >= maxSongs) {
                            hasMore = false
                        } else {
                            currentOffset += batchSize
                            delay(50) // 短暂延迟，让 UI 有时间响应
                        }
                    }
                }
                
                android.util.Log.d("NASMusic", "loadLibrary: finished loading ${allSongs.size} songs total")
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadLibrary songs failed", e)
                showError("加载歌曲列表失败: ${e.message?.take(50)}")
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
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadAlbumSongs failed", e)
                showError("加载专辑歌曲失败: ${e.message?.take(50)}")
            }
        }
    }

    fun getAlbumSongsCache(albumId: String): List<Song> =
        _albumSongsCache.value[albumId] ?: emptyList()

    // --- 详情页导航（A-1, A-2）---
    fun openAlbumDetail(album: Album) {
        _selectedAlbum.value = album
        loadAlbumSongs(album.id)
        _currentScreen.value = Screen.AlbumDetail
    }

    fun openArtistDetail(artistName: String) {
        _selectedArtistName.value = artistName
        _currentScreen.value = Screen.ArtistDetail
    }

    // --- B-1 收藏控制 ---
    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            val adapter = BackendRegistry.getAdapter() ?: return@launch
            try {
                val success = adapter.toggleFavorite(song.id)
                if (success) {
                    val newIds = _favoriteIds.value.toMutableSet()
                    if (song.id in newIds) {
                        newIds.remove(song.id)
                        _favoriteSongs.value = _favoriteSongs.value.filter { it.id != song.id }
                    } else {
                        newIds.add(song.id)
                        _favoriteSongs.value = _favoriteSongs.value + song
                    }
                    _favoriteIds.value = newIds
                }
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "toggleFavorite failed", e)
                showError("切换收藏失败: ${e.message?.take(50)}")
            }
        }
    }

    fun isFavorite(songId: String): Boolean = songId in _favoriteIds.value

    // --- B-2 最近播放 & 播放次数 ---
    fun recordPlay(song: Song) {
        viewModelScope.launch {
            prefs.recordPlay(song.id)
        }
    }

    val recentSongIds = prefs.recentSongIds
    val playCounts = prefs.playCounts

    fun getRecentSongs(): List<Song> {
        val recentIds = prefs.getRecentSongIdsSync()
        val songMap = _songs.value.associateBy { it.id }
        return recentIds.mapNotNull { songMap[it] }
    }

    // --- A-3 流派/年代歌曲加载 ---
    fun getSongsByGenre(genre: String, onResult: (List<Song>) -> Unit) {
        viewModelScope.launch {
            val adapter = BackendRegistry.getAdapter() ?: return@launch
            try {
                onResult(adapter.getSongsByGenre(genre))
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "getSongsByGenre failed", e)
                showError("按流派加载歌曲失败: ${e.message?.take(50)}")
                onResult(emptyList())
            }
        }
    }

    fun getSongsByYearRange(fromYear: Int, toYear: Int, onResult: (List<Song>) -> Unit) {
        viewModelScope.launch {
            val adapter = BackendRegistry.getAdapter() ?: return@launch
            try {
                onResult(adapter.getSongsByYearRange(fromYear, toYear))
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "getSongsByYearRange failed", e)
                showError("按年代加载歌曲失败: ${e.message?.take(50)}")
                onResult(emptyList())
            }
        }
    }

    // --- D-2 网络状态自动重连 ---
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3

    fun onNetworkAvailable() {
        _isNetworkAvailable.value = true
        _connectMessage.value = "网络已恢复"
        viewModelScope.launch {
            delay(2000)
            _connectMessage.value = null
        }
        // 自动重连
        if (!_isConnected.value && reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            android.util.Log.d("NASMusic", "onNetworkAvailable: reconnecting (attempt $reconnectAttempts/$maxReconnectAttempts)")
            connectToSavedServer(silent = true)
        }
    }

    fun onNetworkLost() {
        _isNetworkAvailable.value = false
        reconnectAttempts = 0
        _connectMessage.value = "网络已断开"
        viewModelScope.launch {
            delay(5000)
            _connectMessage.value = null
        }
    }

    // --- 播放控制 ---
    fun playSong(song: Song) {
        android.util.Log.d("NASMusic", "playSong: ${song.title}, coverUrl=${song.coverUrl ?: "null"}")
        playerManager.playSong(song)
        loadLyricsForCurrentSong()
        recordPlay(song)
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val firstSong = songs[startIndex.coerceIn(0, songs.lastIndex)]
        android.util.Log.d("NASMusic", "playQueue: ${songs.size} songs, start=$startIndex, first=${firstSong.title}, coverUrl=${firstSong.coverUrl ?: "null"}")
        playerManager.playQueue(songs, startIndex)
        loadLyricsForCurrentSong()
        recordPlay(firstSong)
    }

    fun playPause() = playerManager.playPause()
    fun next() = playerManager.next()
    fun previous() = playerManager.previous()
    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)

    fun togglePlayMode() = playerManager.togglePlayMode()

    fun setPlayMode(mode: PlayMode) = playerManager.setPlayMode(mode)

    fun addSongToQueue(song: Song) = playerManager.addToQueue(song)

    fun removeFromQueue(index: Int) = playerManager.removeFromQueue(index)

    fun moveQueueItem(fromIndex: Int, toIndex: Int) = playerManager.moveItem(fromIndex, toIndex)

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
                showError("加载歌词失败: ${e.message?.take(50)}")
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
                showError("切换歌词来源失败: ${e.message?.take(50)}")
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

    // --- E-4 缓存管理 ---
    fun clearLyricsCache() {
        viewModelScope.launch {
            lyricsManager.clearCache()
            _connectMessage.value = "歌词缓存已清除"
            delay(2000)
            _connectMessage.value = null
        }
    }

    fun clearCoverCache() {
        viewModelScope.launch {
            val context = getApplication<android.app.Application>()
            CoverArtManager(context).clearCache()
            _connectMessage.value = "封面缓存已清除"
            delay(2000)
            _connectMessage.value = null
        }
    }

    // --- B-4 均衡器 ---
    val equalizerPreset: StateFlow<EqualizerPreset> = prefs.equalizerPreset.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = EqualizerPreset.NORMAL
    )

    val equalizerBands: StateFlow<List<Float>> = prefs.equalizerBands.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    fun setEqualizerPreset(preset: EqualizerPreset) {
        viewModelScope.launch {
            prefs.setEqualizerPreset(preset)
        }
    }

    fun setEqualizerBand(index: Int, value: Float) {
        viewModelScope.launch {
            prefs.setEqualizerBand(index, value)
            // Apply to PlayerManager audio engine
            playerManager.setEqualizerBand(index, value)
        }
    }

    // --- F-1 播放列表 ---
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _selectedPlaylistSongs = MutableStateFlow<List<Song>>(emptyList())
    val selectedPlaylistSongs: StateFlow<List<Song>> = _selectedPlaylistSongs.asStateFlow()

    fun loadPlaylists() {
        viewModelScope.launch {
            val adapter = BackendRegistry.getAdapter() ?: return@launch
            try {
                _playlists.value = adapter.getPlaylists()
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadPlaylists failed", e)
                showError("加载播放列表失败: ${e.message?.take(50)}")
            }
        }
    }

    fun selectPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            val adapter = BackendRegistry.getAdapter() ?: return@launch
            try {
                // Load playlist songs via getAlbumSongs (Subsonic) or equivalent
                val songs = adapter.getAlbumSongs(playlist.id)
                _selectedPlaylistSongs.value = songs
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "selectPlaylist songs failed", e)
                showError("加载播放列表歌曲失败: ${e.message?.take(50)}")
                _selectedPlaylistSongs.value = emptyList()
            }
        }
    }

    fun showCreatePlaylistDialog() {
        // Will be handled by the PlaylistManagementScreen with a TextInputDialog
        viewModelScope.launch {
            val adapter = BackendRegistry.getAdapter() ?: return@launch
            try {
                val name = "New Playlist ${System.currentTimeMillis() % 10000}"
                val result = adapter.createPlaylist(name)
                if (result != null) {
                    _playlists.value = _playlists.value + result
                    _connectMessage.value = "播放列表已创建"
                } else {
                    _connectMessage.value = "创建失败"
                }
            } catch (e: Exception) {
                _connectMessage.value = "创建失败: ${e.message}"
            }
            delay(2000)
            _connectMessage.value = null
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            val adapter = BackendRegistry.getAdapter() ?: return@launch
            try {
                val success = adapter.deletePlaylist(playlist.id)
                if (success) {
                    _playlists.value = _playlists.value.filter { it.id != playlist.id }
                    if (_selectedPlaylistSongs.value.any { it.albumId == playlist.id }) {
                        _selectedPlaylistSongs.value = emptyList()
                    }
                    _connectMessage.value = "播放列表已删除"
                } else {
                    _connectMessage.value = "删除失败"
                }
            } catch (e: Exception) {
                _connectMessage.value = "删除失败: ${e.message}"
            }
            delay(2000)
            _connectMessage.value = null
        }
    }

    fun playPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            val adapter = BackendRegistry.getAdapter() ?: return@launch
            try {
                val songs = adapter.getAlbumSongs(playlist.id)
                if (songs.isNotEmpty()) {
                    playQueue(songs)
                    _currentScreen.value = Screen.NowPlaying
                }
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "playPlaylist failed", e)
                showError("播放播放列表失败: ${e.message?.take(50)}")
            }
        }
    }

    fun removeFromPlaylist(songId: String) {
        viewModelScope.launch {
            val currentSongs = _selectedPlaylistSongs.value
            val playlistId = currentSongs.firstOrNull { it.id == songId }?.albumId ?: return@launch
            val adapter = BackendRegistry.getAdapter() ?: return@launch
            try {
                val success = adapter.removeFromPlaylist(playlistId, songId)
                if (success) {
                    _selectedPlaylistSongs.value = currentSongs.filter { it.id != songId }
                }
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "removeFromPlaylist failed", e)
                showError("从播放列表移除失败: ${e.message?.take(50)}")
            }
        }
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
    ServerConnect,
    AlbumDetail,
    ArtistDetail,
    Equalizer,
    PlaylistManagement
}

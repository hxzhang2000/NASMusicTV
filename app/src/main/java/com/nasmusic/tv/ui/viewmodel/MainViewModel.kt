package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsAvailability
import com.nasmusic.tv.data.model.LyricsHighlightMode
import com.nasmusic.tv.data.model.LyricsSource
import com.nasmusic.tv.data.model.NetworkFavoriteItem
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.EqualizerPreset
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.lyrics.LyricsManager
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.ArtistSplitter
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 歌曲分页加载状态
 */
data class SongsPagingState(
    val songs: List<Song> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 0
)

/**
 * 应用主 ViewModel
 * 管理播放器、歌曲队列、曲库数据、设置等
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val playerManager = nasMusicApp.playerManager
    private val prefs = nasMusicApp.appPreferences
    private val backendRegistry = nasMusicApp.backendRegistry
    private val lyricsManager = LyricsManager(app, backendRegistry, nasMusicApp.networkMusicManager)

    // --- 导航状态 ---
    private val _currentScreen = MutableStateFlow(Screen.NowPlaying)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // --- 曲库数据（B-12: UiState 统一异步状态）---
    private val _albums = MutableStateFlow<UiState<List<Album>>>(UiState.Loading)
    val albums: StateFlow<UiState<List<Album>>> = _albums.asStateFlow()

    private val _songs = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val songs: StateFlow<UiState<List<Song>>> = _songs.asStateFlow()

    // --- 按需加载：歌曲分页状态 ---
    private val _songsPaging = MutableStateFlow(SongsPagingState())
    val songsPaging: StateFlow<SongsPagingState> = _songsPaging.asStateFlow()
    private val pageSize = 200

    // --- 按需加载：艺术家列表（独立 API）---
    private val _artists = MutableStateFlow<UiState<List<Artist>>>(UiState.Success(emptyList()))
    val artists: StateFlow<UiState<List<Artist>>> = _artists.asStateFlow()

    // --- 按需加载：年份列表（独立 API）---
    private val _years = MutableStateFlow<UiState<List<Int>>>(UiState.Success(emptyList()))
    val years: StateFlow<UiState<List<Int>>> = _years.asStateFlow()

    // --- 按需加载：最近播放歌曲（按需批量查询）---
    private val _recentSongs = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val recentSongs: StateFlow<UiState<List<Song>>> = _recentSongs.asStateFlow()

    // --- 按需加载：搜索结果（服务端搜索）---
    private val _searchResults = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val searchResults: StateFlow<UiState<List<Song>>> = _searchResults.asStateFlow()

    // --- 网络音乐搜索结果（NetworkMusicManager 搜索）---
    private val _networkSearchResults = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val networkSearchResults: StateFlow<UiState<List<Song>>> = _networkSearchResults.asStateFlow()
    // 网络搜索关键词（跨页面导航时保留，避免回来后丢失搜索状态）
    private val _networkSearchKeyword = MutableStateFlow("")
    val networkSearchKeyword: StateFlow<String> = _networkSearchKeyword.asStateFlow()

    // --- 网络歌曲收藏 ---
    private val _networkFavorites = MutableStateFlow<List<NetworkFavoriteItem>>(emptyList())
    // 供 UI 使用：转换为 Song 对象列表（设置 isNetworkSong 等标记字段）
    val networkFavoriteSongs: StateFlow<List<Song>> = _networkFavorites.map { favorites ->
        favorites.map { item ->
            Song(
                id = item.songId,
                title = item.title,
                artist = item.artist,
                album = item.album,
                coverUrl = item.coverUrl,
                isNetworkSong = true,
                networkSource = item.networkSource,
                networkId = item.networkId
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    // 网络收藏 ID 集合（用于快速判断是否已收藏）
    val networkFavoriteIds: StateFlow<Set<String>> = _networkFavorites.map { favorites ->
        favorites.map { it.songId }.toSet()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // --- 详情页状态 ---
    private val _selectedAlbum = MutableStateFlow<Album?>(null)
    val selectedAlbum: StateFlow<Album?> = _selectedAlbum.asStateFlow()

    private val _selectedArtistName = MutableStateFlow<String?>(null)
    val selectedArtistName: StateFlow<String?> = _selectedArtistName.asStateFlow()

    private val _albumSongsCache = MutableStateFlow<Map<String, List<Song>>>(emptyMap())
    val albumSongsCache: StateFlow<Map<String, List<Song>>> = _albumSongsCache.asStateFlow()

    // --- 歌唱家拆分映射 ---
    // songId → 拆分后的歌唱家列表（不含去重中间状态，直接展开后的结果）
    private val _songArtistMap = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val songArtistMap: StateFlow<Map<String, List<String>>> = _songArtistMap.asStateFlow()
    // 歌唱家 → 对应的歌曲列表
    private val _artistSongsMap = MutableStateFlow<Map<String, List<Song>>>(emptyMap())
    val artistSongsMap: StateFlow<Map<String, List<Song>>> = _artistSongsMap.asStateFlow()

    // --- B-1 收藏（B-12: UiState）---
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()
    private val _favoriteSongs = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val favoriteSongs: StateFlow<UiState<List<Song>>> = _favoriteSongs.asStateFlow()

    // --- A-3 流派（B-12: UiState）---
    private val _genres = MutableStateFlow<UiState<List<Genre>>>(UiState.Success(emptyList()))
    val genres: StateFlow<UiState<List<Genre>>> = _genres.asStateFlow()

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

    // 歌词高亮模式 — 提升到 ViewModel，跨页面切换保留用户选择
    private val _lyricsHighlightMode = MutableStateFlow(LyricsHighlightMode.LINE_BY_LINE)
    val lyricsHighlightMode: StateFlow<LyricsHighlightMode> = _lyricsHighlightMode.asStateFlow()

    // --- 网络封面 URL（NAS 歌曲切到在线歌词时获取，参与封面轮播）---
    private val _networkCoverUrl = MutableStateFlow<String?>(null)
    val networkCoverUrl: StateFlow<String?> = _networkCoverUrl.asStateFlow()

    // --- B-13: 播放器状态（currentSong/isPlaying/progress/duration 由 PlayerManager 拥有）---
    val currentSong: StateFlow<Song?> = playerManager.currentSong
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val progress: StateFlow<Long> = playerManager.progress
    val duration: StateFlow<Long> = playerManager.duration
    val queue: StateFlow<List<Song>> = playerManager.queue
    val currentIndex: StateFlow<Int> = playerManager.currentIndex

    // B-13: playMode 由 MainViewModel 拥有（UI/设置状态，不归 PlayerManager）
    private val _playMode = MutableStateFlow(PlayMode.SEQUENTIAL)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

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

    private var lyricsLoadJob: Job? = null

    init {
        viewModelScope.launch {
            // 初始化播放模式（B-13: 从预设置恢复）
            val settings = prefs.appSettings.first()
            _playMode.value = settings.defaultPlayMode
            playerManager.applyPlayMode(_playMode.value)

            // 等待配置加载完成后判断是否显示连接提示
            val config = prefs.serverConfig.first()
            if (config.baseUrl.isNotBlank()) {
                // 有已保存的服务器配置，询问用户是否自动连接
                _showConnectPrompt.value = true
            } else {
                // 没有已保存的配置，直接导航到服务器配置界面让用户输入
                _currentScreen.value = Screen.ServerConnect
            }
        }

        // 监听 currentSong 变化，自动切歌时重新加载歌词
        viewModelScope.launch {
            currentSong.collect { song ->
                if (song != null) {
                    loadLyricsForCurrentSong()
                }
            }
        }

        // 监听网络收藏变化（DataStore 持久化，响应式更新）
        viewModelScope.launch {
            prefs.networkFavorites.collect { favorites ->
                _networkFavorites.value = favorites
            }
        }

        // 恢复上次播放队列（仅恢复 UI 状态，不自动播放）
        restoreLastQueue()

        // 监听队列变化，自动持久化到 DataStore
        viewModelScope.launch {
            combine(queue, currentIndex) { songs, index ->
                songs to index
            }.collect { (songs, index) ->
                if (songs.isNotEmpty()) {
                    prefs.saveLastQueue(songs, index)
                }
            }
        }

        // ExoPlayer 自动过渡到 streamUrl 为空的歌曲时（如恢复队列中的网络歌曲），
        // 解析 streamUrl 后重新播放
        playerManager.onNeedResolveStreamUrl = { index ->
            resolveAndPlayByIndex(index)
        }
    }

    // --- 导航 ---
    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    /**
     * 恢复上次播放队列（仅恢复 UI 状态，不自动播放）
     *
     * 从 DataStore 读取持久化的队列，调用 PlayerManager.restoreQueue() 设置队列和索引。
     * NAS 歌曲的 streamUrl 暂时为空，等后端连接成功后由 updateRestoredQueueStreamUrls() 更新。
     * 网络歌曲的 streamUrl 在播放时由 NetworkMusicManager.resolvePlayUrl() 解析。
     */
    private fun restoreLastQueue() {
        val lastQueue = prefs.getLastQueueSync() ?: return
        if (lastQueue.songs.isEmpty()) return
        AppLog.d("NASMusic", "restoreLastQueue: ${lastQueue.songs.size} songs, index=${lastQueue.currentIndex}")
        playerManager.restoreQueue(lastQueue.songs, lastQueue.currentIndex)
    }

    /**
     * 后端连接成功后，更新恢复队列中 NAS 歌曲的 streamUrl
     *
     * 恢复的队列中 NAS 歌曲的 streamUrl 为空（持久化时置空），
     * 需要通过 adapter.getSongsByIds() 重新获取有效的 streamUrl。
     * 网络歌曲不需要更新，播放时由 resolvePlayUrl() 实时解析。
     */
    private fun updateRestoredQueueStreamUrls() {
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
                    AppLog.d("NASMusic", "updateRestoredQueueStreamUrls: updated ${updatedSongs.size} NAS songs")
                }
            } catch (e: Exception) {
                AppLog.w("NASMusic", "updateRestoredQueueStreamUrls failed: ${e.message}", e)
            }
        }
    }

    // --- 连接 ---
    suspend fun connectToServer(config: ServerConfig): Boolean {
        _isLoading.value = true
        return try {
            val success = backendRegistry.initialize(config)
            if (success) {
                _isConnected.value = true
                _serverDisplayName.value = backendRegistry.getServerDisplayName()
                prefs.saveServerConfig(config.copy(isConnected = true))
                // 连接成功后加载初始数据
                loadLibrary()
                // 更新恢复队列中 NAS 歌曲的 streamUrl
                updateRestoredQueueStreamUrls()
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
            try {
                backendRegistry.disconnect()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "disconnect failed", e)
            }
            _isConnected.value = false
            _serverDisplayName.value = ""
            _albums.value = UiState.Loading
            _songs.value = UiState.Loading
            _songsPaging.value = SongsPagingState()
            _artists.value = UiState.Success(emptyList())
            _years.value = UiState.Success(emptyList())
            _recentSongs.value = UiState.Success(emptyList())
            _searchResults.value = UiState.Success(emptyList())
            _genres.value = UiState.Success(emptyList())
            _favoriteSongs.value = UiState.Success(emptyList())
            _playlists.value = UiState.Success(emptyList())
            try {
                val current = serverConfig.value
                prefs.saveServerConfig(current.copy(isConnected = false))
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "disconnect: save config failed", e)
            }
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
                val success = backendRegistry.initialize(config)
                if (success) {
                    _isConnected.value = true
                    _serverDisplayName.value = backendRegistry.getServerDisplayName()
                    prefs.saveServerConfig(config.copy(isConnected = true))
                    loadLibrary()
                    if (!silent) {
                        _connectMessage.value = "已连接到 ${backendRegistry.getServerDisplayName()}"
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
     * 增量构建艺术家映射（避免每次分页都全量重建）
     * @param newSongs 新增的歌曲列表
     */
    private fun buildArtistMapsIncremental(newSongs: List<Song>) {
        val songMap = _songArtistMap.value.toMutableMap()
        val artistMap = _artistSongsMap.value.mapValues { it.value.toMutableList() }.toMutableMap()

        for (song in newSongs) {
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
     * 加载收藏状态（B-1, B-12: UiState）
     */
    private suspend fun loadFavorites(adapter: BackendAdapter) {
        try {
            val favorites = adapter.getFavorites()
            _favoriteSongs.value = UiState.Success(favorites)
            _favoriteIds.value = favorites.map { it.id }.toSet()
            AppLog.d("NASMusic", "loadFavorites: ${favorites.size} favorites")
        } catch (e: Exception) {
            android.util.Log.e("NASMusic", "loadFavorites failed", e)
            _favoriteSongs.value = UiState.Error(
                message = "加载收藏失败: ${e.message?.take(50)}"
            )
        }
    }

    /**
     * 加载流派列表（A-3, B-12: UiState）
     */
    private suspend fun loadGenres(adapter: BackendAdapter) {
        try {
            _genres.value = UiState.Success(adapter.getGenres())
            val data = _genres.value.dataOrNull()
            AppLog.d("NASMusic", "loadGenres: ${data?.size} genres")
        } catch (e: Exception) {
            android.util.Log.e("NASMusic", "loadGenres failed", e)
            _genres.value = UiState.Error(
                message = "加载流派列表失败: ${e.message?.take(50)}"
            )
        }
    }

    // --- 曲库（B-12: UiState）---
    private fun loadLibrary() {
        _isLibraryLoading.value = true
        _albums.value = UiState.Loading
        _songs.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _isLibraryLoading.value = false
                _albums.value = UiState.Error("后端未连接")
                _songs.value = UiState.Error("后端未连接")
                return@launch
            }

            // 并行加载专辑、流派、收藏（秒级响应）
            val albumsDeferred = async {
                try {
                    AppLog.d("NASMusic", "loadLibrary: loading albums...")
                    val loadedAlbums = adapter.getAlbums()
                    _albums.value = UiState.Success(loadedAlbums)
                    AppLog.d("NASMusic", "loadLibrary: ${loadedAlbums.size} albums loaded")
                } catch (e: Exception) {
                    android.util.Log.e("NASMusic", "loadLibrary albums failed", e)
                    _albums.value = UiState.Error(
                        message = "加载专辑列表失败: ${e.message?.take(50)}",
                        retry = { loadLibrary() }
                    )
                }
            }

            val genresDeferred = async { loadGenres(adapter) }
            val favoritesDeferred = async { loadFavorites(adapter) }

            albumsDeferred.await()
            genresDeferred.await()
            favoritesDeferred.await()

            // 不再全量加载歌曲，改为按需分页加载（SONGS Tab 激活时触发）
            _songs.value = UiState.Success(emptyList())
            _songsPaging.value = SongsPagingState()

            _isLibraryLoading.value = false
            AppLog.d("NASMusic", "loadLibrary: initial data loaded (albums/genres/favorites)")
        }
    }

    /**
     * SONGS Tab 首次激活时加载第一页
     */
    fun loadSongsFirstPage() {
        if (_songsPaging.value.songs.isNotEmpty() || _songsPaging.value.isLoading) return
        loadSongsNextPage()
    }

    /**
     * 加载下一页歌曲（滚动到底部时触发）
     */
    fun loadSongsNextPage() {
        val state = _songsPaging.value
        if (state.isLoading || !state.hasMore) return
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: return@launch
            _songsPaging.value = state.copy(isLoading = true)
            try {
                val offset = state.songs.size
                val batch = adapter.getSongs(pageSize, offset)
                val totalCount = if (state.totalCount == 0) adapter.getSongsTotalCount() else state.totalCount
                val newState = SongsPagingState(
                    songs = state.songs + batch,
                    totalCount = totalCount,
                    isLoading = false,
                    hasMore = batch.size == pageSize,
                    currentPage = state.currentPage + 1
                )
                _songsPaging.value = newState
                // 同步更新 _songs（兼容现有 UI 依赖）
                _songs.value = UiState.Success(newState.songs)
                // 增量构建艺术家映射（仅处理新加载的批次，避免全量重建）
                buildArtistMapsIncremental(batch)
                AppLog.d("NASMusic", "loadSongsNextPage: loaded ${batch.size}, total ${newState.songs.size}/$totalCount")
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadSongsNextPage failed", e)
                _songsPaging.value = state.copy(isLoading = false)
                showError("加载歌曲失败: ${e.message?.take(50)}")
            }
        }
    }

    /**
     * ARTISTS Tab 首次激活时加载艺术家列表（独立 API）
     */
    fun loadArtists() {
        if (_artists.value is UiState.Success && (_artists.value as UiState.Success).data.isNotEmpty()) return
        _artists.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _artists.value = UiState.Error("后端未连接")
                return@launch
            }
            try {
                val artistsList = adapter.getArtists()
                _artists.value = UiState.Success(artistsList)
                AppLog.d("NASMusic", "loadArtists: ${artistsList.size} artists loaded")
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadArtists failed", e)
                _artists.value = UiState.Error(
                    message = "加载艺术家失败: ${e.message?.take(50)}",
                    retry = { loadArtists() }
                )
            }
        }
    }

    /**
     * YEARS Tab 首次激活时加载年份列表（独立 API）
     */
    fun loadYears() {
        if (_years.value is UiState.Success && (_years.value as UiState.Success).data.isNotEmpty()) return
        _years.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _years.value = UiState.Error("后端未连接")
                return@launch
            }
            try {
                val yearsList = adapter.getYears()
                _years.value = UiState.Success(yearsList)
                AppLog.d("NASMusic", "loadYears: ${yearsList.size} years loaded")
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadYears failed", e)
                _years.value = UiState.Error(
                    message = "加载年份失败: ${e.message?.take(50)}",
                    retry = { loadYears() }
                )
            }
        }
    }

    /**
     * RECENT Tab 首次激活时按需批量查询最近播放歌曲
     */
    fun loadRecentSongs() {
        if (_recentSongs.value is UiState.Success && (_recentSongs.value as UiState.Success).data.isNotEmpty()) return
        _recentSongs.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _recentSongs.value = UiState.Error("后端未连接")
                return@launch
            }
            try {
                val recentIds = prefs.getRecentSongIdsSync().take(100)
                if (recentIds.isEmpty()) {
                    _recentSongs.value = UiState.Success(emptyList())
                    return@launch
                }
                val songs = adapter.getSongsByIds(recentIds)
                // 按最近播放顺序排序
                val songMap = songs.associateBy { it.id }
                val orderedSongs = recentIds.mapNotNull { songMap[it] }
                _recentSongs.value = UiState.Success(orderedSongs)
                AppLog.d("NASMusic", "loadRecentSongs: ${orderedSongs.size} recent songs loaded")
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadRecentSongs failed", e)
                _recentSongs.value = UiState.Error(
                    message = "加载最近播放失败: ${e.message?.take(50)}",
                    retry = { loadRecentSongs() }
                )
            }
        }
    }

    /**
     * 服务端搜索歌曲（不依赖本地全量数据）
     */
    fun searchSongsOnServer(query: String) {
        if (query.isBlank()) {
            _searchResults.value = UiState.Success(emptyList())
            return
        }
        _searchResults.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _searchResults.value = UiState.Error("后端未连接")
                return@launch
            }
            try {
                val results = adapter.searchSongs(query)
                _searchResults.value = UiState.Success(results)
                AppLog.d("NASMusic", "searchSongsOnServer: ${results.size} results for '$query'")
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "searchSongsOnServer failed", e)
                _searchResults.value = UiState.Error(
                    message = "搜索失败: ${e.message?.take(50)}"
                )
            }
        }
    }

    /**
     * 清除搜索结果
     */
    fun clearSearch() {
        _searchResults.value = UiState.Success(emptyList())
    }

    /**
     * 搜索网络歌曲（通过 NetworkMusicManager，不依赖 NAS 连接）
     *
     * 策略：默认源优先，失败时 fallback 到其他源。
     * 搜索结果为统一 Song 模型（isNetworkSong=true）。
     */
    fun searchNetworkSongs(keyword: String) {
        android.util.Log.i("MetingDiag", "=== MainViewModel.searchNetworkSongs === keyword='$keyword'")
        if (keyword.isBlank()) {
            android.util.Log.i("MetingDiag", "searchNetworkSongs: keyword blank")
            _networkSearchResults.value = UiState.Success(emptyList())
            _networkSearchKeyword.value = ""
            return
        }
        _networkSearchKeyword.value = keyword
        _networkSearchResults.value = UiState.Loading
        viewModelScope.launch {
            try {
                val results = nasMusicApp.networkMusicManager.search(keyword)
                android.util.Log.i("MetingDiag", "searchNetworkSongs: got ${results.size} results for '$keyword'")
                _networkSearchResults.value = UiState.Success(results)
            } catch (e: Exception) {
                android.util.Log.e("MetingDiag", "searchNetworkSongs failed: ${e.message}", e)
                _networkSearchResults.value = UiState.Error(
                    message = "网络搜索失败: ${e.message?.take(50)}"
                )
            }
        }
    }

    /**
     * 清除网络搜索结果
     */
    fun clearNetworkSearch() {
        _networkSearchResults.value = UiState.Success(emptyList())
        _networkSearchKeyword.value = ""
    }

    /**
     * 播放网络歌曲
     *
     * 网络歌曲的 streamUrl 不持久化，播放前实时解析：
     * 1. 通过 NetworkMusicManager.resolvePlayUrl() 获取直联 URL
     * 2. 将解析后的 URL 填入 song.streamUrl
     * 3. 交给 PlayerManager 播放
     *
     * 解析失败时显示错误提示。
     */
    fun playNetworkSong(song: Song) {
        if (!song.isNetworkSong) {
            // 非 network 歌曲，走普通播放流程
            playSong(song)
            return
        }
        viewModelScope.launch {
            try {
                val playUrl = nasMusicApp.networkMusicManager.resolvePlayUrl(song)
                if (playUrl.isNullOrBlank()) {
                    showError("无法解析播放链接，请稍后重试")
                    return@launch
                }
                val playable = song.copy(streamUrl = playUrl)
                AppLog.d("NASMusic", "playNetworkSong: ${song.title} → $playUrl")
                playSong(playable)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "playNetworkSong failed", e)
                showError("播放失败: ${e.message?.take(50)}")
            }
        }
    }

    /**
     * 切换网络歌曲收藏状态
     *
     * 仅对网络歌曲生效（isNetworkSong=true）。收藏信息持久化到 DataStore，
     * 不存储 streamUrl（有时效性），播放时重新解析。
     */
    fun toggleNetworkFavorite(song: Song) {
        if (!song.isNetworkSong) return
        viewModelScope.launch {
            val item = NetworkFavoriteItem(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                coverUrl = song.coverUrl,
                networkSource = song.networkSource ?: return@launch,
                networkId = song.networkId ?: return@launch
            )
            prefs.toggleNetworkFavorite(item)
        }
    }

    /**
     * 判断网络歌曲是否已收藏（同步，用于 UI 快速判断）
     */
    fun isNetworkFavorite(songId: String): Boolean {
        return _networkFavorites.value.any { it.songId == songId }
    }

    fun refreshLibrary() {
        _albums.value = UiState.Loading
        _songs.value = UiState.Loading
        _songsPaging.value = SongsPagingState()
        _artists.value = UiState.Success(emptyList())
        _years.value = UiState.Success(emptyList())
        _recentSongs.value = UiState.Success(emptyList())
        _searchResults.value = UiState.Success(emptyList())
        loadLibrary()
    }

    fun loadAlbumSongs(albumId: String) {
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: return@launch
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
        loadArtistSongs(artistName)
        _currentScreen.value = Screen.ArtistDetail
    }

    private val _artistDetailSongsCache = MutableStateFlow<Map<String, List<Song>>>(emptyMap())
    val artistDetailSongsCache: StateFlow<Map<String, List<Song>>> = _artistDetailSongsCache.asStateFlow()

    fun loadArtistSongs(artistName: String) {
        // 先从 artistSongsMap 中获取已有的歌曲
        val existingSongs = _artistSongsMap.value[artistName]
        if (!existingSongs.isNullOrEmpty()) {
            _artistDetailSongsCache.value = _artistDetailSongsCache.value.toMutableMap().apply {
                put(artistName, existingSongs)
            }
            return
        }
        // 如果没有已有歌曲，从后端加载
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: return@launch
            try {
                // 先找到艺术家 ID
                val artists = _artists.value.dataOrNull() ?: emptyList()
                val artist = artists.find { it.name == artistName }
                if (artist != null) {
                    val songs = adapter.getArtistSongs(artist.id)
                    _artistDetailSongsCache.value = _artistDetailSongsCache.value.toMutableMap().apply {
                        put(artistName, songs)
                    }
                    // 同时更新 artistSongsMap
                    _artistSongsMap.value = _artistSongsMap.value.toMutableMap().apply {
                        put(artistName, songs)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadArtistSongs failed", e)
                showError("加载艺术家歌曲失败: ${e.message?.take(50)}")
            }
        }
    }

    // --- B-1 收藏控制 ---
    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: return@launch
            try {
                val success = adapter.toggleFavorite(song.id)
                if (success) {
                    val newIds = _favoriteIds.value.toMutableSet()
                    if (song.id in newIds) {
                        newIds.remove(song.id)
                        val currentFavs = _favoriteSongs.value.dataOrNull() ?: emptyList()
                        _favoriteSongs.value = UiState.Success(currentFavs.filter { it.id != song.id })
                    } else {
                        newIds.add(song.id)
                        val currentFavs = _favoriteSongs.value.dataOrNull() ?: emptyList()
                        _favoriteSongs.value = UiState.Success(currentFavs + song)
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


    // --- A-3 流派/年代歌曲加载 ---
    fun getSongsByGenre(genre: String, onResult: (List<Song>) -> Unit) {
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: return@launch
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
            val adapter = backendRegistry.getAdapter() ?: return@launch
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
            AppLog.d("NASMusic", "onNetworkAvailable: reconnecting (attempt $reconnectAttempts/$maxReconnectAttempts)")
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
        AppLog.d("NASMusic", "playSong: ${song.title}, coverUrl=${song.coverUrl ?: "null"}")
        playerManager.playSong(song)
        // 歌词由 currentSong.collect 统一触发，避免重复调用
        recordPlay(song)
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val firstSong = songs[startIndex.coerceIn(0, songs.lastIndex)]
        AppLog.d("NASMusic", "playQueue: ${songs.size} songs, start=$startIndex, first=${firstSong.title}, coverUrl=${firstSong.coverUrl ?: "null"}")
        playerManager.playQueue(songs, startIndex)
        // 歌词由 currentSong.collect 统一触发，避免重复调用
        recordPlay(firstSong)
    }

    fun playPause() {
        val song = currentSong.value
        // 恢复队列后，当前歌曲的 streamUrl 可能为空，需要先解析再播放
        if (song != null && song.streamUrl.isNullOrBlank() && !isPlaying.value) {
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
                    AppLog.w("NASMusic", "resolveAndPlayCurrentSong: failed to resolve streamUrl for ${song.title}")
                    showError("无法解析播放链接，请稍后重试")
                    return@launch
                }

                AppLog.d("NASMusic", "resolveAndPlayCurrentSong: resolved ${song.title} → $playUrl")
                // 更新队列中当前歌曲的 streamUrl，然后播放
                val currentQueue = queue.value
                val currentIndexValue = currentIndex.value
                val updatedQueue = currentQueue.mapIndexed { index, s ->
                    if (index == currentIndexValue) s.copy(streamUrl = playUrl) else s
                }
                // 重新加载队列到 ExoPlayer 并播放
                playerManager.playQueue(updatedQueue, currentIndexValue)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "resolveAndPlayCurrentSong failed", e)
                showError("播放失败: ${e.message?.take(50)}")
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
     * 解析指定索引处歌曲的播放链接并播放
     *
     * 用于恢复队列后切换歌曲（next/previous）时，目标歌曲 streamUrl 为空的情况。
     */
    private fun resolveAndPlayByIndex(targetIndex: Int) {
        val queueValue = queue.value
        val song = queueValue.getOrNull(targetIndex) ?: return
        viewModelScope.launch {
            try {
                val playUrl = if (song.isNetworkSong) {
                    nasMusicApp.networkMusicManager.resolvePlayUrl(song)
                } else {
                    val adapter = backendRegistry.getAdapter()
                    if (adapter != null) {
                        adapter.getSongsByIds(listOf(song.id)).firstOrNull()?.streamUrl
                    } else null
                }

                if (playUrl.isNullOrBlank()) {
                    AppLog.w("NASMusic", "resolveAndPlayByIndex: failed to resolve streamUrl for ${song.title}")
                    showError("无法解析播放链接，请稍后重试")
                    return@launch
                }

                AppLog.d("NASMusic", "resolveAndPlayByIndex: resolved ${song.title} → $playUrl")
                // 更新队列中目标歌曲的 streamUrl，然后播放
                val updatedQueue = queueValue.mapIndexed { index, s ->
                    if (index == targetIndex) s.copy(streamUrl = playUrl) else s
                }
                playerManager.playQueue(updatedQueue, targetIndex)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "resolveAndPlayByIndex failed", e)
                showError("播放失败: ${e.message?.take(50)}")
            }
        }
    }
    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)

    fun togglePlayMode() {
        val modes = PlayMode.values()
        val nextIndex = (modes.indexOf(_playMode.value) + 1) % modes.size
        val newMode = modes[nextIndex]
        _playMode.value = newMode
        playerManager.applyPlayMode(newMode)
    }

    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        playerManager.applyPlayMode(mode)
    }

    fun addSongToQueue(song: Song) = playerManager.addToQueue(song)

    fun removeFromQueue(index: Int) = playerManager.removeFromQueue(index)

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

    /**
     * 队列中所有歌曲 id 的集合（供 UI 快速判断某首歌是否在队列中）
     */
    val queueSongIds: StateFlow<Set<String>> = queue
        .map { songs -> songs.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun moveQueueItem(fromIndex: Int, toIndex: Int) = playerManager.moveItem(fromIndex, toIndex)

    fun clearQueue() {
        playerManager.clearQueue()
        _currentLyrics.value = null
        _lyricsAvailability.value = LyricsAvailability()
        // 清除持久化的上次播放队列
        viewModelScope.launch { prefs.clearLastQueue() }
    }

    private fun loadLyricsForCurrentSong() {
        lyricsLoadJob?.cancel()
        _currentLyrics.value = null
        _lyricsAvailability.value = LyricsAvailability()
        val song = currentSong.value ?: return
        AppLog.d("NASMusic", "loadLyrics: loading for ${song.title} by ${song.artist}")
        lyricsLoadJob = viewModelScope.launch {
            try {
                // 先检查可用来源
                val availability = lyricsManager.checkAvailability(song)
                _lyricsAvailability.value = availability
                AppLog.d("NASMusic", "loadLyrics: backend=${availability.hasBackend}, network=${availability.hasNetwork}")

                // 自动选择第一个可用来源
                val lyrics = availability.backend ?: availability.network
                _currentLyrics.value = lyrics
                // 自动检测歌词格式：含逐字时间戳时切到逐字高亮；否则保持用户上次选择（跨页面切换不丢失）
                if (lyrics != null && lyrics.lines.any { it.wordTimestamps.isNotEmpty() }) {
                    _lyricsHighlightMode.value = LyricsHighlightMode.WORD_BY_WORD
                }
                AppLog.d("NASMusic", "loadLyrics: source=${lyrics?.source}, lines=${lyrics?.lines?.size}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程被主动取消（如切歌时 lyricsLoadJob.cancel()），不是错误，不提示
                throw e
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadLyrics failed", e)
                showError("加载歌词失败: ${e.message?.take(50)}")
            }
        }
    }

    /**
     * 获取歌曲的候选封面 URL 列表（统一入口，不区分 NAS/网络歌曲）。
     * NAS 歌曲：后端 3 类封面（歌曲/专辑/艺术家）+ 网络封面（切在线歌词时追加）
     * 网络歌曲：1 张 pic 封面
     */
    fun getCoverCandidates(song: Song): List<String> {
        val candidates = mutableListOf<String>()
        if (song.isNetworkSong) {
            // 网络歌曲：只有 1 张 pic 封面
            song.coverUrl?.let { candidates.add(it) }
        } else {
            // NAS 歌曲：后端 3 类封面
            val adapter = backendRegistry.getAdapter()
            if (adapter != null) {
                candidates.addAll(adapter.getCoverUrlCandidates(song))
            }
            // 如果有网络封面（切换网络歌词时获取），追加到列表
            _networkCoverUrl.value?.let { candidates.add(it) }
        }
        return candidates.distinct().filter { it.isNotBlank() }
    }

    /**
     * 设置歌词高亮模式（用户手动切换逐行/逐字时调用）
     */
    fun setLyricsHighlightMode(mode: LyricsHighlightMode) {
        _lyricsHighlightMode.value = mode
    }

    /**
     * 切换歌词来源
     * 切换到在线歌词时联动获取网络封面，切回内嵌时清除网络封面
     */
    fun switchLyricsSource(source: LyricsSource) {
        val song = currentSong.value ?: return
        AppLog.d("NASMusic", "switchLyricsSource: $source")
        viewModelScope.launch {
            try {
                val lyrics = lyricsManager.getLyricsFromSource(song, source)
                _currentLyrics.value = lyrics
                AppLog.d("NASMusic", "switchLyricsSource: source=${lyrics?.source}, lines=${lyrics?.lines?.size}")

                // 联动网络封面：切换到在线歌词时获取，切回内嵌时清除
                if (source == LyricsSource.NETWORK && lyrics != null && !song.isNetworkSong) {
                    val networkCover = nasMusicApp.networkMusicManager.searchCoverUrl(song.title, song.artist)
                    _networkCoverUrl.value = networkCover
                    AppLog.d("NASMusic", "switchLyricsSource: 网络封面=${networkCover?.take(60)}")
                } else {
                    // 切回内嵌/本地文件来源，清除网络封面
                    _networkCoverUrl.value = null
                }
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

    /**
     * 更新 Meting-API 端点 URL（网络搜索配置）
     * 传入空串则恢复默认端点
     */
    fun updateMetingApiBaseUrl(url: String) = viewModelScope.launch {
        val normalized = url.trim()
        if (normalized.isEmpty()) {
            prefs.setMetingApiBaseUrl(com.nasmusic.tv.backend.network.MetingApiService.DEFAULT_BASE_URL)
        } else {
            prefs.setMetingApiBaseUrl(normalized)
        }
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
            // 直接使用 Coil 全局 ImageLoader 清除缓存，而非新建 CoverArtManager 实例
            val imageLoader = coil.ImageLoader(context)
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
            AppLog.d("MainViewModel", "clearCoverCache: cache cleared")
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
            // 应用频段到 PlayerManager
            playerManager.setEqualizerBands(preset.bandGains)
        }
    }

    fun setEqualizerBand(index: Int, value: Float) {
        viewModelScope.launch {
            prefs.setEqualizerBand(index, value)
            // Apply to PlayerManager audio engine
            playerManager.setEqualizerBand(index, value)
        }
    }

    // --- F-1 播放列表（B-12: UiState）---
    private val _playlists = MutableStateFlow<UiState<List<Playlist>>>(UiState.Success(emptyList()))
    val playlists: StateFlow<UiState<List<Playlist>>> = _playlists.asStateFlow()

    private val _selectedPlaylistSongs = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val selectedPlaylistSongs: StateFlow<UiState<List<Song>>> = _selectedPlaylistSongs.asStateFlow()

    fun loadPlaylists() {
        _playlists.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _playlists.value = UiState.Error("后端未连接")
                return@launch
            }
            try {
                _playlists.value = UiState.Success(adapter.getPlaylists())
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "loadPlaylists failed", e)
                _playlists.value = UiState.Error(
                    message = "加载播放列表失败: ${e.message?.take(50)}",
                    retry = { loadPlaylists() }
                )
            }
        }
    }

    fun selectPlaylist(playlist: Playlist) {
        _selectedPlaylistSongs.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _selectedPlaylistSongs.value = UiState.Error("后端未连接")
                return@launch
            }
            try {
                val songs = adapter.getAlbumSongs(playlist.id)
                _selectedPlaylistSongs.value = UiState.Success(songs)
            } catch (e: Exception) {
                android.util.Log.e("NASMusic", "selectPlaylist songs failed", e)
                _selectedPlaylistSongs.value = UiState.Error(
                    message = "加载播放列表歌曲失败: ${e.message?.take(50)}",
                    retry = { selectPlaylist(playlist) }
                )
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: return@launch
            try {
                val result = adapter.createPlaylist(name)
                if (result != null) {
                    val current = _playlists.value.dataOrNull() ?: emptyList()
                    _playlists.value = UiState.Success(current + result)
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
            val adapter = backendRegistry.getAdapter() ?: return@launch
            try {
                val success = adapter.deletePlaylist(playlist.id)
                if (success) {
                    val current = _playlists.value.dataOrNull() ?: emptyList()
                    _playlists.value = UiState.Success(current.filter { it.id != playlist.id })
                    val selSongs = _selectedPlaylistSongs.value.dataOrNull()
                    if (selSongs != null && selSongs.any { it.albumId == playlist.id }) {
                        _selectedPlaylistSongs.value = UiState.Success(emptyList())
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
            val adapter = backendRegistry.getAdapter() ?: return@launch
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
            val currentSongs = _selectedPlaylistSongs.value.dataOrNull() ?: return@launch
            val playlistId = currentSongs.firstOrNull { it.id == songId }?.albumId ?: return@launch
            val adapter = backendRegistry.getAdapter() ?: return@launch
            try {
                val success = adapter.removeFromPlaylist(playlistId, songId)
                if (success) {
                    _selectedPlaylistSongs.value = UiState.Success(currentSongs.filter { it.id != songId })
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

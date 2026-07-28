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
import com.nasmusic.tv.data.model.PlayRecord
import com.nasmusic.tv.data.model.PlayStatistics
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.HomeDashboardData
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.EqualizerPreset
import com.nasmusic.tv.data.model.MusicSource
import com.nasmusic.tv.data.model.NetworkSubTab
import com.nasmusic.tv.ui.screens.LibraryTab
import com.nasmusic.tv.data.model.Screen
import com.nasmusic.tv.data.model.SongsPagingState
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.data.model.WeatherData
import com.nasmusic.tv.data.model.WeatherForecast
import com.nasmusic.tv.data.model.WeatherMood
import com.nasmusic.tv.data.model.WeatherRadioQueue
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.backend.weather.WeatherApi
import com.nasmusic.tv.backend.weather.WeatherRadioManager
import com.nasmusic.tv.lyrics.LyricsManager
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.ArtistSplitter
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用主 ViewModel
 * 管理播放器、歌曲队列、曲库数据、设置等
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val playerManager = nasMusicApp.playerManager
    val prefs = nasMusicApp.appPreferences
    // --- 封面滤镜设置 ---
    private val _coverFilterEnabled = MutableStateFlow(false)
    val coverFilterEnabled: StateFlow<Boolean> = _coverFilterEnabled.asStateFlow()
    private val _coverFilterBlurRadius = MutableStateFlow(8f)
    val coverFilterBlurRadius: StateFlow<Float> = _coverFilterBlurRadius.asStateFlow()
    private val _coverFilterDarkOverlay = MutableStateFlow(0.3f)
    val coverFilterDarkOverlay: StateFlow<Float> = _coverFilterDarkOverlay.asStateFlow()
    private val backendRegistry = nasMusicApp.backendRegistry
    private val lyricsManager = LyricsManager(app, backendRegistry, nasMusicApp.networkMusicManager)

    // --- 天气电台 ---
    val weatherApi = WeatherApi()
    var weatherRadioManager: WeatherRadioManager? = null
        private set

    private val _weatherData = MutableStateFlow<WeatherData?>(null)
    val weatherData: StateFlow<WeatherData?> = _weatherData.asStateFlow()

    private val _weatherRadioQueue = MutableStateFlow<WeatherRadioQueue?>(null)
    val weatherRadioQueue: StateFlow<WeatherRadioQueue?> = _weatherRadioQueue.asStateFlow()

    private val _currentWeatherMood = MutableStateFlow(WeatherMood.SUNNY)
    val currentWeatherMood: StateFlow<WeatherMood> = _currentWeatherMood.asStateFlow()

    private val _weatherLoading = MutableStateFlow(false)
    val weatherLoading: StateFlow<Boolean> = _weatherLoading.asStateFlow()

    private val _weatherError = MutableStateFlow<String?>(null)
    val weatherError: StateFlow<String?> = _weatherError.asStateFlow()

    private val _weatherForecast = MutableStateFlow<List<WeatherForecast>>(emptyList())
    val weatherForecast: StateFlow<List<WeatherForecast>> = _weatherForecast.asStateFlow()

    private val _weatherIconCode = MutableStateFlow<String?>(null)
    val weatherIconCode: StateFlow<String?> = _weatherIconCode.asStateFlow()

    // --- 导航状态 ---
    private val _currentScreen = MutableStateFlow(Screen.Home)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // --- 首页仪表盘数据 ---
    private val _homeDashboardData = MutableStateFlow(HomeDashboardData())
    val homeDashboardData: StateFlow<HomeDashboardData> = _homeDashboardData.asStateFlow()

    /**
     * 加载首页仪表盘数据
     */
    fun loadHomeDashboard() {
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter()
            if (adapter == null) {
                _homeDashboardData.value = HomeDashboardData()
                return@launch
            }
            try {
                val albums = _albums.value.dataOrNull() ?: emptyList()
                val songs = _songs.value.dataOrNull() ?: emptyList()
                val artists = _artists.value.dataOrNull() ?: emptyList()
                val playlists = adapter.getPlaylists()

                // 最新添加专辑（按年份降序排列）
                val recentlyAdded = albums
                    .filter { it.year != null }
                    .sortedByDescending { it.year }
                    .take(12)
                    .ifEmpty { albums.take(12) }

                // 收藏歌曲
                val favoriteSongs = _favoriteSongs.value.dataOrNull() ?: emptyList()

                _homeDashboardData.value = HomeDashboardData(
                    totalAlbums = albums.size,
                    totalSongs = songs.size,
                    totalArtists = artists.size,
                    totalPlaylists = playlists.size,
                    recentlyAddedAlbums = recentlyAdded,
                    favoriteSongs = favoriteSongs.take(20)
                )
            } catch (e: Exception) {
                com.nasmusic.tv.util.AppLog.e("MainViewModel", "loadHomeDashboard failed", e)
            }
        }
    }

    // --- 曲库数据（B-12: UiState 统一异步状态）---
    private val _albums = MutableStateFlow<UiState<List<Album>>>(UiState.Loading)
    val albums: StateFlow<UiState<List<Album>>> = _albums.asStateFlow()

    private val _songs = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val songs: StateFlow<UiState<List<Song>>> = _songs.asStateFlow()

    // --- 按需加载：歌曲分页状态 ---
    private val _songsPaging = MutableStateFlow(SongsPagingState())
    val songsPaging: StateFlow<SongsPagingState> = _songsPaging.asStateFlow()
    private val pageSize = 200

    /**
     * 后台全量加载是否正在进行中
     */
    private val _isBackgroundLoadingAll = AtomicBoolean(false)

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

    // --- 网络歌单（NetworkMusicManager 获取）---
    private val _networkPlaylists = MutableStateFlow<List<Pair<Playlist, List<Song>>>>(emptyList())
    val networkPlaylists: StateFlow<List<Pair<Playlist, List<Song>>>> = _networkPlaylists.asStateFlow()

    // 榜单轮换状态（Phase 3）
    private val _chartsRotationIndex = MutableStateFlow(0)
    val chartsRotationIndex: StateFlow<Int> = _chartsRotationIndex.asStateFlow()

    private val _playlistSongs = MutableStateFlow<List<Song>>(emptyList())
    val playlistSongs: StateFlow<List<Song>> = _playlistSongs.asStateFlow()

    private val _selectedPlaylistTitle = MutableStateFlow("")
    val selectedPlaylistTitle: StateFlow<String> = _selectedPlaylistTitle.asStateFlow()

    private val _searchNetworkPlatform = MutableStateFlow("netease")
    val searchNetworkPlatform: StateFlow<String> = _searchNetworkPlatform.asStateFlow()

    /**
     * 设置网络搜索平台
     */
    fun setSearchNetworkPlatform(platform: String) {
        _searchNetworkPlatform.value = platform
    }

    // --- 曲库页子 Tab 状态（跨导航记忆） ---
    private val _libraryActiveTab = MutableStateFlow(LibraryTab.ALBUMS)
    val libraryActiveTab: StateFlow<LibraryTab> = _libraryActiveTab.asStateFlow()

    fun selectLibraryTab(tab: LibraryTab) {
        _libraryActiveTab.value = tab
    }

    // --- 网络音乐页子 Tab 状态 ---
    private val _currentNetworkSubTab = MutableStateFlow(NetworkSubTab.DISCOVER)
    val currentNetworkSubTab: StateFlow<NetworkSubTab> = _currentNetworkSubTab.asStateFlow()

    fun selectNetworkSubTab(tab: NetworkSubTab) {
        _currentNetworkSubTab.value = tab
        // 切换到天气子 Tab 时自动加载天气
        if (tab == NetworkSubTab.WEATHER) {
            fetchWeather()
        }
    }

    // --- 网络音乐平台来源 ---
    private val _currentMusicSource = MutableStateFlow(MusicSource.NETEASE)
    val currentMusicSource: StateFlow<MusicSource> = _currentMusicSource.asStateFlow()

    /**
     * 初始化音乐来源（从持久化存储读取）
     */
    private fun initMusicSource() {
        val savedKey = prefs.getMusicSourceSync()
        val source = MusicSource.fromApiKey(savedKey)
        _currentMusicSource.value = source
        _searchNetworkPlatform.value = source.apiKey
    }

    /**
     * 切换音乐平台来源
     *
     * 持久化到 DataStore，同时更新旧的 searchNetworkPlatform 状态保持兼容，
     * 如果有搜索关键词则自动重新搜索。
     */
    fun selectMusicSource(source: MusicSource) {
        if (_currentMusicSource.value == source) return
        _currentMusicSource.value = source
        _searchNetworkPlatform.value = source.apiKey
        viewModelScope.launch {
            prefs.setMusicSource(source.apiKey)
            // 有搜索关键词时自动重新搜索
            if (_networkSearchKeyword.value.isNotBlank()) {
                searchNetworkSongs(_networkSearchKeyword.value)
            }
        }
    }

    // --- 天气电台 ---

    /**
     * 获取当前天气并构建天气电台
     */
    fun fetchWeather() {
        viewModelScope.launch {
            _weatherLoading.value = true
            _weatherError.value = null
            try {
                val apiKey = nasMusicApp.appPreferences.getWeatherApiKeySync()
                val weather = weatherApi.fetchCurrentWeather(
                    openWeatherMapApiKey = apiKey.ifBlank { null }
                )
                if (weather != null) {
                    _weatherData.value = weather
                    // 获取天气图标代码（从 OpenWeatherMap）
                    _weatherIconCode.value = null // 由 Open-Meteo 数据时无图标

                    // 获取天气预报（需要 API Key）
                    if (apiKey.isNotBlank()) {
                        val forecast = weatherApi.fetchForecast(apiKey.ifBlank { null })
                        _weatherForecast.value = forecast
                    }

                    // 延迟初始化 WeatherRadioManager（需要 BackendAdapter 和 NetworkMusicManager）
                    val adapter = backendRegistry.getAdapter()
                    if (weatherRadioManager == null) {
                        weatherRadioManager = WeatherRadioManager(adapter, nasMusicApp.networkMusicManager)
                    }
                    weatherRadioManager?.let { mgr ->
                        val queue = mgr.buildRadio(weather)
                        _weatherRadioQueue.value = queue
                        _currentWeatherMood.value = queue.mood
                    }
                } else {
                    _weatherError.value = if (apiKey.isBlank()) {
                        "无法获取天气信息，请在 设置→网络 中配置 OpenWeatherMap API Key"
                    } else {
                        "无法获取天气信息，请检查网络连接或 API Key 是否有效"
                    }
                }
            } catch (e: Exception) {
                AppLog.e("MainViewModel", "fetchWeather failed", e)
                _weatherError.value = "获取天气失败: ${e.message?.take(50)}"
            } finally {
                _weatherLoading.value = false
            }
        }
    }

    /**
     * 切换天气电台 mood
     */
    fun switchWeatherMood(mood: WeatherMood) {
        if (_currentWeatherMood.value == mood) return
        _currentWeatherMood.value = mood
        viewModelScope.launch {
            _weatherLoading.value = true
            try {
                // 延迟初始化（可能在无后端连接时通过 fetchWeather() 创建）
                val mgr = weatherRadioManager ?: run {
                    val adapter = backendRegistry.getAdapter()
                    WeatherRadioManager(adapter, nasMusicApp.networkMusicManager).also { weatherRadioManager = it }
                }
                val queue = mgr.buildRadioWithMood(mood, _weatherData.value)
                _weatherRadioQueue.value = queue
            } catch (e: Exception) {
                AppLog.e("MainViewModel", "switchWeatherMood failed", e)
                _weatherError.value = "切换心情失败: ${e.message?.take(50)}"
            } finally {
                _weatherLoading.value = false
            }
        }
    }

    /**
     * 播放天气电台全部歌曲
     */
    fun playWeatherRadioAll() {
        val songs = _weatherRadioQueue.value?.songs ?: return
        if (songs.isEmpty()) return
        // 网络歌曲需要解析 streamUrl，但这里统一走 playQueue 的逻辑
        playQueue(songs, 0)
        // 导航到播放页
        _currentScreen.value = Screen.NowPlaying
    }

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

    // --- 歌曲技术信息（编码格式、比特率等）---
    private val _songTechnicalInfo = MutableStateFlow<com.nasmusic.tv.data.model.SongTechnicalInfo?>(null)
    val songTechnicalInfo: StateFlow<com.nasmusic.tv.data.model.SongTechnicalInfo?> = _songTechnicalInfo.asStateFlow()

    // --- 播放统计 ---
    private val _playStatistics = MutableStateFlow(PlayStatistics())
    val playStatistics: StateFlow<PlayStatistics> = _playStatistics.asStateFlow()

    private val _playRecords = MutableStateFlow<List<PlayRecord>>(emptyList())
    val playRecords: StateFlow<List<PlayRecord>> = _playRecords.asStateFlow()

    /**
     * 记录播放事件（歌曲切换或播放完成时调用）
     */
    fun recordPlayEvent(song: Song, durationPlayedMs: Long) {
        if (durationPlayedMs < 5000) return // 少于 5 秒不计入
        val record = PlayRecord(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            coverUrl = song.coverUrl,
            timestamp = System.currentTimeMillis(),
            durationPlayedMs = durationPlayedMs,
            durationTotalMs = song.durationMs
        )
        viewModelScope.launch {
            prefs.addPlayRecord(record)
            // 更新内存中的记录列表
            _playRecords.value = listOf(record) + _playRecords.value.take(499)
            refreshPlayStatistics()
        }
    }

    /**
     * 刷新播放统计
     */
    fun refreshPlayStatistics() {
        viewModelScope.launch {
            val allRecords = _playRecords.value
            if (allRecords.isEmpty()) {
                _playStatistics.value = PlayStatistics()
                return@launch
            }

            val totalPlayTimeMs = allRecords.sumOf { it.durationPlayedMs }
            val uniqueSongs = allRecords.map { it.songId }.distinct().size

            // Top 歌曲按播放次数排序
            val songPlayCounts = allRecords.groupBy { it.songId }
                .mapValues { (_, records) -> records.size }
                .entries.sortedByDescending { it.value }.take(10)
            val topSongs = songPlayCounts.mapNotNull { (songId, _) ->
                allRecords.find { it.songId == songId }
            }

            // Top 歌手
            val artistPlayCounts = allRecords.groupBy { it.artist }
                .mapValues { (_, records) -> records.size }
                .entries
                .filter { it.key.isNotBlank() }
                .sortedByDescending { it.value }
                .take(10)
                .map { it.key to it.value }

            _playStatistics.value = PlayStatistics(
                totalPlayCount = allRecords.size,
                totalPlayTimeMs = totalPlayTimeMs,
                uniqueSongsPlayed = uniqueSongs,
                topSongs = topSongs,
                topArtists = artistPlayCounts,
                recentPlays = allRecords.take(50)
            )
        }
    }

    /**
     * 加载播放记录（应用启动时调用）
     */
    private fun loadPlayRecords() {
        viewModelScope.launch {
            val records = prefs.getPlayRecords()
            _playRecords.value = records
            refreshPlayStatistics()
        }
    }

    /**
     * 清除播放记录
     */
    fun clearPlayRecords() {
        viewModelScope.launch {
            prefs.clearPlayRecords()
            _playRecords.value = emptyList()
            _playStatistics.value = PlayStatistics()
        }
    }

    /**
     * 异步获取当前歌曲的技术信息
     */
    fun loadSongTechnicalInfo() {
        viewModelScope.launch {
            val song = currentSong.value ?: return@launch
            if (song.isNetworkSong) {
                _songTechnicalInfo.value = null
                return@launch
            }
            try {
                val adapter = backendRegistry.getAdapter()
                val info = adapter?.getSongTechnicalInfo(song.id)
                _songTechnicalInfo.value = info
            } catch (e: Exception) {
                AppLog.e("MainViewModel", "loadSongTechnicalInfo failed", e)
                _songTechnicalInfo.value = null
            }
        }
    }

    // --- B-13: 播放器状态（currentSong/isPlaying/progress/duration 由 PlayerManager 拥有）---
    val currentSong: StateFlow<Song?> = playerManager.currentSong
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val progress: StateFlow<Long> = playerManager.progress
    val duration: StateFlow<Long> = playerManager.duration
    val queue: StateFlow<List<Song>> = playerManager.queue
    val currentIndex: StateFlow<Int> = playerManager.currentIndex
    /** 实时频谱数据（96 柱幅值），来自 SpectrumAnalyzer / Visualizer FFT */
    val spectrumData: StateFlow<FloatArray> = playerManager.spectrumData

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
    // 记录上一首歌的 ID，用于在切歌时统计播放记录
    private var lastRecordedSongId: String? = null
    private var lastRecordedSong: Song? = null
    private var lastRecordedPositionMs: Long = 0L

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

        // 监听 currentSong 变化，自动切歌时重新加载歌词，并记录播放历史
        viewModelScope.launch {
            currentSong.collect { song ->
                // 记录上一首歌的播放
                val previousSong = lastRecordedSong
                val previousPosition = lastRecordedPositionMs
                if (previousSong != null && previousPosition > 5000L
                    && previousSong.id != song?.id) {
                    recordPlayEvent(previousSong, previousPosition)
                }

                if (song != null) {
                    loadLyricsForCurrentSong()
                    // 记录当前歌的开始
                    lastRecordedSong = song
                    lastRecordedSongId = song.id
                    // 记录到最近播放列表（自动切歌时也需要更新）
                    recordPlay(song)
                }
            }
        }

        // 每 30 秒更新一次播放位置（用于切歌时记录精确的播放时长）
        viewModelScope.launch {
            while (true) {
                delay(30000)
                lastRecordedPositionMs = progress.value
            }
        }

        // 监听网络收藏变化（DataStore 持久化，响应式更新）
        viewModelScope.launch {
            prefs.networkFavorites.collect { favorites ->
                _networkFavorites.value = favorites
            }
        }

        // 恢复上次播放队列（仅恢复 UI 状态，不自动播放）— 协程异步读取 DataStore，避免阻塞主线程
        viewModelScope.launch {
            restoreLastQueue()
        }

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

        // 异步加载预配置的网络歌单
        viewModelScope.launch {
            loadNetworkPlaylists()
        }

        // 初始化网络音乐平台来源（从持久化存储读取）
        initMusicSource()

        // 当曲库数据加载完成时自动刷新首页仪表盘
        viewModelScope.launch {
            combine(_albums, _songs) { a, s ->
                a.isSuccess && s.isSuccess
            }.collect { loaded ->
                if (loaded && _currentScreen.value == Screen.Home) {
                    loadHomeDashboard()
                }
            }
        }

        // 加载播放记录
        loadPlayRecords()
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
    private suspend fun restoreLastQueue() {
        val lastQueue = prefs.getLastQueue() ?: return
        val songs = lastQueue.songs
        if (songs.isNullOrEmpty()) return
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
                // 导航到首页
                _currentScreen.value = Screen.Home
                loadHomeDashboard()
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
                AppLog.e("MainViewModel", "disconnect failed", e)
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
                AppLog.e("MainViewModel", "disconnect: save config failed", e)
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
                    AppLog.w("NASMusic", "connectToSavedServer: initialize returned false")
                    if (!silent) {
                        _connectMessage.value = "连接失败，请检查服务器设置"
                        delay(3000)
                        _connectMessage.value = null
                    }
                }
            } catch (e: Exception) {
                AppLog.e("NASMusic", "connectToSavedServer failed", e)
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
            AppLog.e("NASMusic", "loadFavorites failed", e)
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
            AppLog.e("NASMusic", "loadGenres failed", e)
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
                    AppLog.e("NASMusic", "loadLibrary albums failed", e)
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

            // 全量加载艺术家列表（数据量通常比专辑少，提前加载让 ARTISTS Tab 免等待）
            loadArtists()

            // 后台逐步加载全量歌曲：每加载一页立即显示，继续加载直到全部完成
            _songsPaging.value = SongsPagingState()
            loadAllSongsBackground()

            _isLibraryLoading.value = false
            AppLog.d("NASMusic", "loadLibrary: initial data loaded (albums/genres/favorites), starting background song loading")
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
        if (state.isLoading || !state.hasMore || _isBackgroundLoadingAll.get()) return
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
                AppLog.e("NASMusic", "loadSongsNextPage failed", e)
                _songsPaging.value = state.copy(isLoading = false)
                showError("加载歌曲失败: ${e.message?.take(50)}")
            }
        }
    }

    /**
     * 后台全量加载歌曲：分页加载，每页立即显示，继续加载直到全部完成。
     * 与 loadSongsNextPage 共享 _songsPaging 状态，互斥运行。
     */
    private fun loadAllSongsBackground() {
        if (!_isBackgroundLoadingAll.compareAndSet(false, true)) return
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _isBackgroundLoadingAll.set(false)
                return@launch
            }
            try {
                var offset = 0
                while (true) {
                    val batch = adapter.getSongs(pageSize, offset)
                    if (batch.isEmpty()) break
                    val current = _songsPaging.value
                    val newState = SongsPagingState(
                        songs = current.songs + batch,
                        isLoading = false,
                        hasMore = batch.size == pageSize,
                        currentPage = current.currentPage + 1
                    )
                    _songsPaging.value = newState
                    _songs.value = UiState.Success(newState.songs)
                    buildArtistMapsIncremental(batch)
                    offset += batch.size
                    if (batch.size < pageSize) break
                }
                AppLog.d("NASMusic", "loadAllSongsBackground: done, ${_songsPaging.value.songs.size} songs")
            } catch (e: Exception) {
                AppLog.e("NASMusic", "loadAllSongsBackground failed", e)
            } finally {
                _isBackgroundLoadingAll.set(false)
            }
        }
    }

    /**
     * 独立从后端获取每个艺术家的歌曲，填充 artistSongsMap。
     * 作为后台任务运行，不阻塞。
     * 当歌曲 Tab 的后台全量加载已能覆盖时，此方法是冗余的，但确保
     * 艺术家 Tab 在歌曲未加载时仍能显示歌曲数量。
     */
    private fun loadArtistSongsMap(adapter: BackendAdapter, artists: List<Artist>) {
        viewModelScope.launch {
            try {
                val artistMap = mutableMapOf<String, MutableList<Song>>()
                // 分批处理，每次最多 5 个并发请求避免压垮后端
                artists.chunked(5).forEach { chunk ->
                    val deferred = chunk.map { artist ->
                        async {
                            try {
                                adapter.getArtistSongs(artist.id)
                            } catch (e: Exception) {
                                AppLog.w("NASMusic", "loadArtistSongsMap: skipped '${artist.name}': ${e.message?.take(50)}")
                                emptyList<Song>()
                            }
                        }
                    }
                    val results = deferred.awaitAll()
                    results.forEachIndexed { index, songs ->
                        if (songs.isNotEmpty()) {
                            artistMap.getOrPut(chunk[index].name) { mutableListOf() }.addAll(songs)
                        }
                    }
                }
                if (artistMap.isNotEmpty()) {
                    val existing = _artistSongsMap.value.mapValues { it.value.toMutableList() }.toMutableMap()
                    artistMap.forEach { (name, songs) ->
                        val existingSongs = existing.getOrPut(name) { mutableListOf() }
                        // 按 song.id 去重，避免与 buildArtistMapsIncremental 的结果重复
                        val existingIds = existingSongs.map { it.id }.toSet()
                        existingSongs.addAll(songs.filter { it.id !in existingIds })
                    }
                    _artistSongsMap.value = existing
                    AppLog.d("NASMusic", "loadArtistSongsMap: ${artistMap.size} artists, ${artistMap.values.sumOf { it.size }} songs")
                }
            } catch (e: Exception) {
                AppLog.e("NASMusic", "loadArtistSongsMap failed", e)
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
                // 对合作歌曲的艺术家名进行拆分（如 "AAA & BBB" → "AAA", "BBB"）
                // 使每个参与者都独立出现在艺术家列表中
                val splitArtists = artistsList.flatMap { artist ->
                    val names = ArtistSplitter.split(artist.name)
                    if (names.size > 1) {
                        names.map { name ->
                            artist.copy(
                                id = "${artist.id}|$name",  // 唯一 ID，用于 Grid key
                                name = name
                            )
                        }
                    } else {
                        listOf(artist)
                    }
                }
                // 合并重复艺术家（同一名字可能来自独立条目和拆分条目）
                val merged = splitArtists.groupBy { it.name }.map { (name, group) ->
                    group.first().copy(
                        songCount = group.maxOf { it.songCount },
                        albumCount = group.sumOf { it.albumCount }
                    )
                }
                _artists.value = UiState.Success(merged)
                AppLog.d("NASMusic", "loadArtists: ${artistsList.size} raw → ${merged.size} after splitting")

                // 独立从后端获取每个艺术家的歌曲，填充 artistSongsMap
                // 这样即使歌曲 Tab 未加载，艺术家 Tab 也能显示歌曲数量
                loadArtistSongsMap(adapter, merged)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "loadArtists failed", e)
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
                AppLog.e("NASMusic", "loadYears failed", e)
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
    fun loadRecentSongs(showLoading: Boolean = true) {
        if (showLoading) _recentSongs.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _recentSongs.value = UiState.Error("后端未连接")
                return@launch
            }
            try {
                val recentIds = prefs.getRecentSongIds().distinct().take(100)
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
                AppLog.e("NASMusic", "loadRecentSongs failed", e)
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
                AppLog.e("NASMusic", "searchSongsOnServer failed", e)
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
        AppLog.i("MetingDiag", "=== MainViewModel.searchNetworkSongs === keyword='$keyword'")
        if (keyword.isBlank()) {
            AppLog.i("MetingDiag", "searchNetworkSongs: keyword blank")
            _networkSearchResults.value = UiState.Success(emptyList())
            _networkSearchKeyword.value = ""
            return
        }
        _networkSearchKeyword.value = keyword
        _networkSearchResults.value = UiState.Loading
        viewModelScope.launch {
            try {
                val results = nasMusicApp.networkMusicManager.search(keyword)
                AppLog.i("MetingDiag", "searchNetworkSongs: got ${results.size} results for '$keyword'")
                _networkSearchResults.value = UiState.Success(results)
            } catch (e: Exception) {
                AppLog.e("MetingDiag", "searchNetworkSongs failed: ${e.message}", e)
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
                networkId = song.networkId ?: return@launch,
                addedAtMs = System.currentTimeMillis()
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

    /**
     * 播放私人电台 — 随机播放网络歌曲
     *
     * 策略：收藏优先，没有收藏时从随机歌单播放。
     * 播放后自动导航到 NowPlaying 页。
     */
    fun playPrivateRadio() {
        viewModelScope.launch {
            // 收藏优先：使用网络收藏歌曲
            val favorites = _networkFavorites.value
            if (favorites.isNotEmpty()) {
                val songs = favorites.map { item ->
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
                val shuffled = songs.shuffled()
                playQueue(shuffled, 0)
                _currentScreen.value = Screen.NowPlaying
                return@launch
            }

            // 无收藏时使用随机歌单
            val playlists = _networkPlaylists.value
            if (playlists.isNotEmpty()) {
                val randomPlaylist = playlists.random()
                if (randomPlaylist.second.isNotEmpty()) {
                    playQueue(randomPlaylist.second, 0)
                    _currentScreen.value = Screen.NowPlaying
                    return@launch
                }
            }

            // 没有收藏也没有歌单
            showError("没有收藏或歌单可供播放，请先搜索并收藏歌曲")
        }
    }

    /**
     * 预配置的网络歌单列表（扩展版，用于榜单轮换）
     *
     * 每个 Triple 为 (id, name, source)。
     * 每日轮换显示 CHART_PAGE_SIZE 个歌单，用 "换一批" 按钮切换到下一组。
     */
    private val preconfiguredPlaylists = listOf(
        Triple("3778678", "热歌榜", "netease"),
        Triple("3779629", "新歌榜", "netease"),
        Triple("19723756", "飙升榜", "netease"),
        Triple("3136952023", "华语流行", "netease"),
        Triple("60198", "欧美流行", "netease"),
        Triple("377165088", "抖音热门", "netease"),
        Triple("2211745987", "经典老歌", "netease"),
        Triple("2884035", "原创音乐榜", "netease"),
        Triple("377733686", "ACG 音乐榜", "netease"),
        Triple("3117263287", "纯音乐榜", "netease"),
        Triple("377237077", "古风榜", "netease"),
        Triple("5390174047", "日语流行", "netease"),
        Triple("5059631514", "K-POP 热榜", "netease"),
        Triple("3248421784", "说唱榜", "netease"),
    )
    private val CHART_PAGE_SIZE = 6

    /**
     * 获取当前时间对应的轮换起始索引（基于当天日期）
     */
    private fun dailyRotationStart(): Int {
        val calendar = java.util.Calendar.getInstance()
        val dayOfYear = calendar.get(java.util.Calendar.DAY_OF_YEAR)
        return dayOfYear % preconfiguredPlaylists.size
    }

    /**
     * 换一批：将榜单轮换索引 +1，重新加载歌单
     */
    fun refreshCharts() {
        val next = (_chartsRotationIndex.value + 1) % preconfiguredPlaylists.size
        _chartsRotationIndex.value = next
        loadNetworkPlaylists()
    }

    /**
     * 加载所有预配置的网络歌单（分页轮换）
     *
     * 默认从 dailyRotationStart() 位置开始取 CHART_PAGE_SIZE 个歌单，
     * 如果 refreshCharts() 调用过则从上一次轮换索引继续。
     */
    fun loadNetworkPlaylists() {
        viewModelScope.launch {
            val results = mutableListOf<Pair<Playlist, List<Song>>>()
            var failCount = 0

            // 计算本次显示的歌单子集
            val startIdx = _chartsRotationIndex.value % preconfiguredPlaylists.size
            val orderedIds = preconfiguredPlaylists.subList(startIdx, preconfiguredPlaylists.size) +
                    preconfiguredPlaylists.subList(0, startIdx)
            val visible = orderedIds.take(CHART_PAGE_SIZE)

            for ((id, name, _) in visible) {
                try {
                    val songs = nasMusicApp.networkMusicManager.getPlaylist(id)
                    if (songs.isEmpty()) {
                        AppLog.d("NASMusic", "loadNetworkPlaylists: skip '$name' (empty)")
                        failCount++
                        continue
                    }
                    // coverUrls: 取前三首歌曲的封面
                    val coverUrls = songs.take(3).mapNotNull { it.coverUrl }
                    val playlist = Playlist(
                        id = id,
                        name = name,
                        coverUrls = coverUrls,
                        songCount = songs.size
                    )
                    results.add(playlist to songs)
                    AppLog.d("NASMusic", "loadNetworkPlaylists: loaded '$name' (${songs.size} songs)")
                } catch (e: Exception) {
                    AppLog.w("NASMusic", "loadNetworkPlaylists: failed for '$name': ${e.message}", e)
                    failCount++
                }
            }
            _networkPlaylists.value = results
            AppLog.d("NASMusic", "loadNetworkPlaylists: done, ${results.size}/${preconfiguredPlaylists.size} playlists loaded")
            // 所有歌单都加载失败时提示用户
            if (results.isEmpty() && failCount == preconfiguredPlaylists.size) {
                showError("网络音乐端点连接失败，请在设置中检查端点配置")
            }
        }
    }

    /**
     * 加载指定网络歌单的歌曲详情
     *
     * @param playlistId 歌单 ID
     * @param playlistTitle 歌单标题（用于 UI 标题显示）
     */
    fun loadPlaylistDetail(playlistId: String, playlistTitle: String) {
        _selectedPlaylistTitle.value = playlistTitle
        viewModelScope.launch {
            try {
                val songs = nasMusicApp.networkMusicManager.getPlaylist(playlistId)
                _playlistSongs.value = songs
                AppLog.d("NASMusic", "loadPlaylistDetail: '$playlistTitle' (${songs.size} songs)")
            } catch (e: Exception) {
                AppLog.e("NASMusic", "loadPlaylistDetail failed for '$playlistTitle': ${e.message}", e)
                _playlistSongs.value = emptyList()
                showError("加载歌单失败: ${e.message?.take(50)}")
            }
        }
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
                AppLog.e("NASMusic", "loadAlbumSongs failed", e)
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
                // 先找到艺术家条目（拆分后的合成 ID 形如 "原ID|AAA"，需提取原始 ID）
                val artists = _artists.value.dataOrNull() ?: emptyList()
                val artist = artists.find { it.name == artistName }
                if (artist != null) {
                    val originalId = artist.id.substringBefore("|", artist.id)
                    val rawSongs = adapter.getArtistSongs(originalId)
                    // 将返回的歌曲按 ArtistSplitter 拆分后，只取包含该艺术家的歌曲
                    val matchingSongs = rawSongs.filter { song ->
                        artistName in ArtistSplitter.split(song.artist)
                    }
                    _artistDetailSongsCache.value = _artistDetailSongsCache.value.toMutableMap().apply {
                        put(artistName, matchingSongs)
                    }
                    // 同时按拆分后的艺术家名更新 artistSongsMap 缓存
                    buildArtistMapsIncremental(matchingSongs)
                }
            } catch (e: Exception) {
                AppLog.e("NASMusic", "loadArtistSongs failed", e)
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
                AppLog.e("NASMusic", "toggleFavorite failed", e)
                showError("切换收藏失败: ${e.message?.take(50)}")
            }
        }
    }

    fun isFavorite(songId: String): Boolean = songId in _favoriteIds.value

    // --- B-2 最近播放 & 播放次数 ---
    fun recordPlay(song: Song) {
        viewModelScope.launch {
            prefs.recordPlay(song.id)
            // 刷新最近播放列表，不显示 loading 以避免闪烁
            loadRecentSongs(showLoading = false)
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
                AppLog.e("NASMusic", "getSongsByGenre failed", e)
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
                AppLog.e("NASMusic", "getSongsByYearRange failed", e)
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

        // 网络歌曲的 streamUrl 需要异步解析，否则 ExoPlayer 收到空 URI 不会开始播放
        val needsResolve = songs.any { it.isNetworkSong && it.streamUrl.isNullOrBlank() }
        if (needsResolve) {
            // 立即更新队列状态，避免异步解析期间 UI 读到旧的队列数据（如恢复队列中的网络歌曲）
            // ExoPlayer 的 prepare 延迟到解析完成后统一进行
            playerManager.restoreQueue(songs, startIndex)

            viewModelScope.launch {
                val resolved = songs.map { song ->
                    if (song.isNetworkSong && song.streamUrl.isNullOrBlank()) {
                        try {
                            val url = nasMusicApp.networkMusicManager.resolvePlayUrl(song)
                            if (!url.isNullOrBlank()) song.copy(streamUrl = url) else song
                        } catch (e: Exception) {
                            AppLog.e("NASMusic", "playQueue: resolveUrl failed for ${song.title}", e)
                            song
                        }
                    } else {
                        song
                    }
                }
                // 检查第一首歌是否仍然无法解析
                val resolvedFirst = resolved.getOrNull(startIndex)
                if (resolvedFirst != null && resolvedFirst.isNetworkSong && resolvedFirst.streamUrl.isNullOrBlank()) {
                    AppLog.w("NASMusic", "playQueue: all endpoints failed to resolve URL for ${resolvedFirst.title}")
                    showError("无法解析播放链接，网络音乐端点连接失败")
                }
                playerManager.playQueue(resolved, startIndex)
                recordPlay(firstSong)
            }
        } else {
            playerManager.playQueue(songs, startIndex)
            recordPlay(firstSong)
        }
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
        val modes = PlayMode.entries
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

    /**
     * 队列中的网络歌曲（用于「继续听」区域）
     *
     * 从当前队列中筛选出网络歌曲（isNetworkSong=true），
     * 不包含当前正在播放的歌曲，最多保留 5 首，
     * 按队列顺序排列（最近即将播放的在前）。
     */
    val recentNetworkSongs: StateFlow<List<Song>> = combine(queue, currentIndex) { songs, index ->
        songs.filterIndexed { i, s -> s.isNetworkSong && i != index }
            .take(5)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 当前播放的网络歌曲（用于「继续听」区域的"正在播放"）
     */
    val currentNetworkSong: StateFlow<Song?> = combine(currentSong, queue) { song, _ ->
        song?.takeIf { it.isNetworkSong }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
                AppLog.e("NASMusic", "loadLyrics failed", e)
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
                AppLog.e("NASMusic", "switchLyricsSource failed", e)
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

    fun updateLyricsFontScale(scale: Float) = viewModelScope.launch {
        prefs.setLyricsFontScale(scale)
    }

    fun updateCoverFilterEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setCoverFilterEnabled(enabled)
    }

    fun updateCoverFilterBlurRadius(radius: Float) = viewModelScope.launch {
        prefs.setCoverFilterBlurRadius(radius)
    }

    fun updateCoverFilterDarkOverlay(overlay: Float) = viewModelScope.launch {
        prefs.setCoverFilterDarkOverlay(overlay)
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

    /**
     * 更新 OpenWeatherMap API Key
     */
    fun updateWeatherApiKey(key: String) = viewModelScope.launch {
        prefs.setWeatherApiKey(key.trim())
    }

    fun updateSpectrumEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setSpectrumEnabled(enabled)
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
            // 同时持久化频段数据到 DataStore，确保 UI 能正确显示 currentBands
            prefs.setEqualizerBands(preset.bandGains)
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
                AppLog.e("NASMusic", "loadPlaylists failed", e)
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
                val songs = adapter.getPlaylistSongs(playlist.id)
                _selectedPlaylistSongs.value = UiState.Success(songs)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "selectPlaylist songs failed", e)
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
                val songs = adapter.getPlaylistSongs(playlist.id)
                if (songs.isNotEmpty()) {
                    playQueue(songs)
                    _currentScreen.value = Screen.NowPlaying
                }
            } catch (e: Exception) {
                AppLog.e("NASMusic", "playPlaylist failed", e)
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
                AppLog.e("NASMusic", "removeFromPlaylist failed", e)
                showError("从播放列表移除失败: ${e.message?.take(50)}")
            }
        }
    }
}

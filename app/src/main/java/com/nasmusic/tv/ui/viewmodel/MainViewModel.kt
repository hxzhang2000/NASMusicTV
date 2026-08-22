package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.network.mv.MvSearchManager
import com.nasmusic.tv.backend.network.baidu.BaiduFileIndexCache
import com.nasmusic.tv.backend.network.baidu.BaiduOAuthClient
import com.nasmusic.tv.backend.network.baidu.BaiduPanApi
import com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.BaiduFileIndex
import com.nasmusic.tv.data.model.BrowseDimension
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsAvailability
import com.nasmusic.tv.data.model.LyricsHighlightMode
import com.nasmusic.tv.data.model.LyricsSource
import com.nasmusic.tv.data.model.NetworkFavoriteItem
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.PlayRecord
import com.nasmusic.tv.data.model.PlayStatistics
import com.nasmusic.tv.data.model.SearchHistoryItem
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.HomeDashboardData
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.EqualizerPreset
import com.nasmusic.tv.data.model.MusicSource
import com.nasmusic.tv.data.model.MvInfo
import com.nasmusic.tv.data.model.MvCandidate
import com.nasmusic.tv.data.model.MvSearchResult
import com.nasmusic.tv.data.model.NetworkSubTab
import com.nasmusic.tv.data.model.RadioStation
import com.nasmusic.tv.data.model.isRadioSong
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
import com.nasmusic.tv.net.RemoteCallbacks
import com.nasmusic.tv.net.RemoteControlServer
import com.nasmusic.tv.net.RemoteSearchResult
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import com.nasmusic.tv.util.ArtistSplitter
import com.nasmusic.tv.util.BackupFileUtils
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
 * MTV（音乐视频）可用状态。
 * 切歌时自动搜索，UI 消费 [MainViewModel.mvState] 决定 MTV 按钮亮/暗。
 */
sealed interface MvAvailability {
    object Idle : MvAvailability            // 无歌曲/不需要搜索
    object Searching : MvAvailability
    data class Ready(val mv: MvInfo, val alternatives: List<MvCandidate> = emptyList()) : MvAvailability
    object NotFound : MvAvailability
}

/**
 * 应用主 ViewModel
 * 管理播放器、歌曲队列、曲库数据、设置等
 */
class MainViewModel(app: Application) : AndroidViewModel(app), RemoteCallbacks {

    private val nasMusicApp = app as NasMusicApp
    private val playerManager = nasMusicApp.playerManager
    private val mvSearchManager = nasMusicApp.mvSearchManager
    val prefs = nasMusicApp.appPreferences
    /** 搜索历史（最近输入 + 最多搜索） */
    val searchHistory = prefs.searchHistory
    // --- 封面滤镜设置 ---
    private val _coverFilterEnabled = MutableStateFlow(false)
    val coverFilterEnabled: StateFlow<Boolean> = _coverFilterEnabled.asStateFlow()
    private val _coverFilterBlurRadius = MutableStateFlow(8f)
    val coverFilterBlurRadius: StateFlow<Float> = _coverFilterBlurRadius.asStateFlow()
    private val _coverFilterDarkOverlay = MutableStateFlow(0.3f)
    val coverFilterDarkOverlay: StateFlow<Float> = _coverFilterDarkOverlay.asStateFlow()
    private val backendRegistry = nasMusicApp.backendRegistry
    private val lyricsManager = LyricsManager(
        app, backendRegistry, nasMusicApp.networkMusicManager,
        kugouBaseUrl = prefs.getLyricsKugouBaseUrlSync(),
        neteaseBaseUrl = prefs.getLyricsNeteaseBaseUrlSync()
    )

    // --- 手机遥控服务器 ---
    private val remoteControlServer = RemoteControlServer()
    private val _remoteControlUrl = MutableStateFlow<String?>(null)
    val remoteControlUrl: StateFlow<String?> = _remoteControlUrl.asStateFlow()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 按需启动遥控服务器（进入 K 歌/MTV 模式时调用，避免常驻浪费资源） */
    fun ensureRemoteControlStarted() {
        if (_remoteControlUrl.value == null) {
            _remoteControlUrl.value = remoteControlServer.start(this)
        }
    }

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
    /** 网络音乐"播放全部"单次加入队列的上限（去重后取前 N 首） */
    private val maxNetworkBatchPlayCount = 30

    /**
     * 后台全量加载是否正在进行中
     */
    private val _isBackgroundLoadingAll = AtomicBoolean(false)

    // --- 随心听：随机歌曲 ---
    private val _randomSongs = MutableStateFlow<List<Song>>(emptyList())
    val randomSongs: StateFlow<List<Song>> = _randomSongs.asStateFlow()
    private var shuffleRefillJob: kotlinx.coroutines.Job? = null

    // --- 按需加载：艺术家列表（独立 API）---
    private val _artists = MutableStateFlow<UiState<List<Artist>>>(UiState.Success(emptyList()))
    val artists: StateFlow<UiState<List<Artist>>> = _artists.asStateFlow()

    // ArtistSplitter 拆分前的原始艺术家列表，用于 Navidrome 多 ID 查询合作歌曲
    private var _rawArtistList: List<Artist> = emptyList()

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

    /** 搜索变异词后缀表：换一批时在原词后追加，用于突破单次搜索 30 首上限 */
    private val searchVariantSuffixes = listOf(
        "翻唱", "live", "现场", "伴奏", "钢琴", "吉他", "remix", "串烧",
        "经典", "怀旧", "演唱会", "DJ版", "纯音乐", "古风", "钢琴版", "吉他版",
        "慢速", "混音", "国语", "粤语", "英文", "日文", "韩文", "原唱"
    )

    /** 换一批时新歌数量达到该值才展示（否则继续尝试下一后缀） */
    private val minNewResultsForShuffle = 5

    /** 换一批单次点击最多尝试的后缀数量（避免搜索结果长期重复时空转） */
    private val maxShuffleAttemptsPerClick = 6

    /** 用户输入的原始搜索词（换一批的基准） */
    private var networkSearchBaseKeyword = ""

    /** 已用过的变异后缀（一轮内不重复，用尽后重置） */
    private val usedSearchVariants = mutableSetOf<String>()

    /** 换一批已展示过的歌曲（歌手, 歌名）集合：跨批次去重，保证每次换一批只出新歌 */
    private val seenNetworkSearchKeys = mutableSetOf<Pair<String, String>>()

    /** 浏览换一批已展示过的歌曲（歌手, 歌名）集合：跨批次去重，筛选条件变化时重置 */
    private val browseSeenKeys = mutableSetOf<Pair<String, String>>()

    /** 天气电台换一批已展示过的歌曲（歌手, 歌名）集合：跨构建去重，mood 变化时重置 */
    private val weatherSeenKeys = mutableSetOf<Pair<String, String>>()

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

    // --- 本地歌单（「我的」Tab，DataStore 持久化，可混合 NAS/网络歌曲）---
    private val _localPlaylists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val localPlaylists: StateFlow<List<LocalPlaylist>> = _localPlaylists.asStateFlow()

    // --- 网络歌单（NetworkMusicManager 获取）---
    private val _networkPlaylists = MutableStateFlow<List<Pair<Playlist, List<Song>>>>(emptyList())
    val networkPlaylists: StateFlow<List<Pair<Playlist, List<Song>>>> = _networkPlaylists.asStateFlow()

    // 网络歌单轮换索引（发现页推荐歌单数据来源）
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
                        // 天气变化 = 新上下文，重置电台已见集合（跨构建去重从头开始）
                        weatherSeenKeys.clear()
                        val queue = buildWeatherRadioDeduped(mgr, WeatherMood.fromWeather(weather), weather)
                        _weatherRadioQueue.value = queue
                        _currentWeatherMood.value = queue.mood
                    }
                } else {
                    _weatherError.value = if (apiKey.isBlank()) {
                        "无法获取天气信息，请在 设置→网络 中配置 OpenWeatherMap API Key"
                    } else {
                        "无法获取天气信息，请检查网络连接或 API Key 是否有效"
                    }
                    // 即使天气获取失败，仍按默认心情（阳光）加载歌曲
                    loadRadioForDefaultMood()
                }
            } catch (e: Exception) {
                AppLog.e("MainViewModel", "fetchWeather failed", e)
                _weatherError.value = "获取天气失败: ${e.message?.take(50)}"
                // 即使天气获取失败，仍按默认心情（阳光）加载歌曲
                loadRadioForDefaultMood()
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
        // mood 变化 = 新上下文，重置电台已见集合（跨构建去重从头开始）
        weatherSeenKeys.clear()
        viewModelScope.launch {
            _weatherLoading.value = true
            try {
                // 延迟初始化（可能在无后端连接时通过 fetchWeather() 创建）
                val mgr = weatherRadioManager ?: run {
                    val adapter = backendRegistry.getAdapter()
                    WeatherRadioManager(adapter, nasMusicApp.networkMusicManager).also { weatherRadioManager = it }
                }
                val queue = buildWeatherRadioDeduped(mgr, mood, _weatherData.value)
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

    /**
     * 构建天气电台并跨构建去重（「换一批」）。
     *
     * 与网络搜索 / 多维度浏览共用 [pickBestFreshBatch]：每次候选都重新构建一次电台
     * （NAS 匹配与网络搜索结果已打乱，故每次基础集合不同），在多个候选中挑选
     * 新歌最多的展示，保证同一 mood 下反复「换一批」只出新歌。
     *
     * mood 变化（新上下文）时调用方负责清空 [weatherSeenKeys]。
     */
    private suspend fun buildWeatherRadioDeduped(
        mgr: WeatherRadioManager,
        mood: WeatherMood,
        weather: WeatherData?
    ): WeatherRadioQueue {
        val (chosen, shown) = pickBestFreshBatch(
            seenKeys = weatherSeenKeys,
            produce = { mgr.buildRadioWithMood(mood, weather) },
            songsOf = { it.songs }
        )
        return chosen.copy(songs = shown)
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
    /** 上一首歌的歌词来源，用于在播放完成时判断是否提交网络歌词到持久化缓存 */
    private var lastRecordedLyricsSource: LyricsSource? = null

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
            }
            // 无配置时不强制跳转，保持首页（用户可自行去 设置 → 服务器 配置）
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
                    // 上一首播放完成 → 如果歌词来源是网络歌词，提交到持久化缓存
                    if (lastRecordedLyricsSource == LyricsSource.NETWORK) {
                        lyricsManager.commitPendingNetworkLyrics(previousSong)
                    }
                }

                if (song != null) {
                    // 记录当前歌词来源（在 loadLyricsForCurrentSong 清除 _currentLyrics 之前）
                    lastRecordedLyricsSource = _currentLyrics.value?.source
                    loadLyricsForCurrentSong()
                    // MTV 连播静默推进索引时跳过搜索（预搜结果已直接设为 Ready）
                    if (skipNextMvSearch) {
                        skipNextMvSearch = false
                    } else {
                        triggerMvSearch(song)
                    }
                    // 记录当前歌的开始
                    lastRecordedSong = song
                    lastRecordedSongId = song.id
                    // 记录到最近播放列表（自动切歌时也需要更新）
                    recordPlay(song)
                } else {
                    // 无当前歌曲（清空队列等）→ 重置 MV 状态
                    _mvState.value = MvAvailability.Idle
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

        // 监听本地歌单变化（DataStore 持久化，响应式更新）
        viewModelScope.launch {
            prefs.localPlaylists.collect { playlists ->
                _localPlaylists.value = playlists
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
     * 天气获取失败时，按默认心情（阳光）加载歌曲。
     */
    private fun loadRadioForDefaultMood() {
        viewModelScope.launch {
            try {
                val mgr = weatherRadioManager ?: run {
                    val adapter = backendRegistry.getAdapter()
                    WeatherRadioManager(adapter, nasMusicApp.networkMusicManager).also { weatherRadioManager = it }
                }
                // 天气获取失败也走同一套跨构建去重：反复「换一批」仍只出新歌
                val queue = buildWeatherRadioDeduped(mgr, WeatherMood.SUNNY, null)
                _weatherRadioQueue.value = queue
                _currentWeatherMood.value = queue.mood
            } catch (e: Exception) {
                AppLog.e("MainViewModel", "loadRadioForDefaultMood failed", e)
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

            // 加载随机歌曲（随心听）
            loadRandomSongs(adapter)

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
     * 加载随机歌曲（随心听）：混合 NAS 后端 + 网络歌曲，首页展示 20 首。
     * - 已连 NAS：从后端拉取随机歌曲
     * - 网络音乐可用：从预设歌单随机抽取
     * - 两者混合，凑满 20 首
     * - 都不可用时 _randomSongs 保持空，首页不显示区块
     */
    private suspend fun loadRandomSongs(adapter: BackendAdapter?) {
        val allSongs = mutableListOf<Song>()
        // 1. NAS 后端随机歌曲（多拉一些确保够用）
        if (adapter != null) {
            try {
                val nasSongs = adapter.getRandomSongs(50)
                AppLog.d("NASMusic", "loadRandomSongs: got ${nasSongs.size} NAS songs")
                allSongs.addAll(nasSongs)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "loadRandomSongs NAS failed", e)
            }
        }
        // 2. 网络歌曲：从多个预设歌单随机抽取，凑够 20 首
        val shuffledPlaylists = preconfiguredPlaylists.shuffled()
        for ((playlistId, playlistName, _) in shuffledPlaylists) {
            if (allSongs.size >= 20) break
            try {
                val networkSongs = nasMusicApp.networkMusicManager.getPlaylist(playlistId)
                if (networkSongs.isNotEmpty()) {
                    AppLog.d("NASMusic", "loadRandomSongs: got ${networkSongs.size} songs from '${playlistName}'")
                    val tagged = networkSongs.map { it.copy(isNetworkSong = true, networkSource = "meting") }
                    allSongs.addAll(tagged)
                }
            } catch (e: Exception) {
                AppLog.e("NASMusic", "loadRandomSongs network failed for '$playlistName'", e)
            }
        }
        // 3. 打乱后取前 20 首。如果拉取失败则保留已有数据，不让区块消失
        if (allSongs.isNotEmpty()) {
            _randomSongs.value = allSongs.shuffled().take(20)
        } else if (_randomSongs.value.isEmpty() && adapter != null) {
            // 有后端但没拉到任何歌曲，可能是临时网络问题，下次刷新会重试
            _randomSongs.value = emptyList()
        }
        // 有后端但已有数据的不清空，保持区块可见
        AppLog.d("NASMusic", "loadRandomSongs: total ${_randomSongs.value.size} songs")
    }

    fun loadRandomSongs() {
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter()
            loadRandomSongs(adapter)
        }
    }

    /**
     * 播放随机歌曲（随心听），启动自动续播。
     */
    fun playRandomSongs(songs: List<Song>, startIndex: Int) {
        _isShufflePlaying = true
        playQueue(songs, startIndex)
        _currentScreen.value = Screen.NowPlaying
        startShuffleRefill()
    }

    private var _isShufflePlaying = false

    private fun startShuffleRefill() {
        shuffleRefillJob?.cancel()
        shuffleRefillJob = viewModelScope.launch {
            while (_isShufflePlaying) {
                val queue = playerManager.queue.value
                val currentIdx = playerManager.currentIndex.value
                val remaining = queue.size - currentIdx
                if (remaining <= 5) {
                    val adapter = backendRegistry.getAdapter() ?: break
                    try {
                        val newSongs = adapter.getRandomSongs(20)
                        if (newSongs.isNotEmpty()) {
                            playerManager.addToQueue(newSongs)
                        }
                    } catch (e: Exception) {
                        AppLog.e("NASMusic", "shuffleRefill failed", e)
                        delay(30000) // 失败后等 30 秒再试
                        continue
                    }
                }
                delay(5000)
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
                // 保存原始艺术家列表（拆分前），用于 Navidrome 多 ID 联合查询合作歌曲
                _rawArtistList = artistsList
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
                // 艺术家歌曲数量由歌曲 Tab 的 buildArtistMapsIncremental 全量加载后自动填充
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
                // 搜索成功后才记录历史（空结果也算成功，记录用户确实搜过的词；
                // 失败/未连接不记录，避免污染热门榜）
                prefs.recordSearch(query)
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
            networkSearchBaseKeyword = ""
            usedSearchVariants.clear()
            return
        }
        // 用户手动搜索：重置换一批状态（基准词 + 已用变异词 + 已见歌曲集合）
        // 搜索历史记录在 doNetworkSearch 成功路径里，失败不记录
        networkSearchBaseKeyword = keyword
        usedSearchVariants.clear()
        seenNetworkSearchKeys.clear()
        doNetworkSearch(keyword)
    }

    /**
     * 换一批：用原搜索词 + 变异后缀重新搜索，突破单次搜索 30 首上限。
     * 跨批次去重：已展示过的歌曲会被过滤；在 [maxShuffleAttemptsPerClick] 个后缀
     * 中挑选新歌最多的批次展示，保证每次点击都有新歌且不会空转。
     */
    fun shuffleNetworkSearch() {
        val base = networkSearchBaseKeyword
        if (base.isBlank()) return
        viewModelScope.launch {
            var failed = false
            val (chosen, shown) = pickBestFreshBatch(
                seenKeys = seenNetworkSearchKeys,
                produce = {
                    val available = searchVariantSuffixes.filterNot { it in usedSearchVariants }
                    if (available.isEmpty()) usedSearchVariants.clear()
                    val suffix = searchVariantSuffixes
                        .filterNot { it in usedSearchVariants }
                        .shuffled()
                        .first()
                    usedSearchVariants.add(suffix)
                    val keyword = "$base $suffix"
                    val results = searchNetworkSongsBlocking(keyword)
                    if (results == null) failed = true
                    keyword to (results ?: emptyList())
                },
                songsOf = { it.second }
            )
            // 全部候选都搜索失败时保留错误态（searchNetworkSongsBlocking 已设置）
            if (failed && shown.isEmpty()) return@launch
            _networkSearchKeyword.value = chosen.first
            _networkSearchResults.value = UiState.Success(shown)
        }
    }

    /**
     * 统一的「换一批」核心逻辑（网络搜索 / 多维度浏览 / 天气电台共用）。
     *
     * 反复调用 [produce] 生成候选（最多 [maxShuffleAttemptsPerClick] 次），用 [songsOf]
     * 取出其中的歌曲列表，过滤掉 [seenKeys] 中已展示过的歌曲，返回新歌最多的候选与
     * 新歌列表；新歌数量达到 [minNewResultsForShuffle] 即提前停止。
     *
     * 若所有候选都没有新歌（已见集合饱和），清空 [seenKeys] 后重新生成一次候选并返回
     * （从头再来，保证每次点击都有内容）。返回前把本次真正展示的新歌记入 [seenKeys]
     * （未展示的候选歌曲保留，之后批次仍可出现）。
     *
     * 调用方负责在「上下文变化」（新搜索词 / 新筛选 / 新 mood）时清空对应的 [seenKeys]。
     *
     * @param seenKeys 该场景的跨批次已见歌曲集合
     * @param produce 生成一个候选（如变异后缀搜索、随机关键词组合、重建天气电台）
     * @param songsOf 从候选 T 中取出歌曲列表
     * @return Pair(选中的候选 T, 本次展示的新歌列表)
     */
    private suspend fun <T> pickBestFreshBatch(
        seenKeys: MutableSet<Pair<String, String>>,
        maxAttempts: Int = maxShuffleAttemptsPerClick,
        minNewResults: Int = minNewResultsForShuffle,
        produce: suspend () -> T,
        songsOf: (T) -> List<Song>
    ): Pair<T, List<Song>> {
        var best: T? = null
        var bestFresh: List<Song> = emptyList()
        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            val candidate = produce()
            val fresh = songsOf(candidate).filterNot { (it.artist.trim() to it.title.trim()) in seenKeys }
            if (fresh.size > bestFresh.size) {
                best = candidate
                bestFresh = fresh
            }
            if (fresh.size >= minNewResults) break
        }
        val chosen = best
        val result = if (chosen == null || bestFresh.isEmpty()) {
            // 所有候选都没有新歌：已见集合饱和，从头再来一批
            seenKeys.clear()
            val freshProduce = produce()
            freshProduce to songsOf(freshProduce)
        } else {
            chosen to bestFresh
        }
        result.second.forEach { seenKeys.add(it.artist.trim() to it.title.trim()) }
        return result
    }

    /** 实际执行网络搜索（换一批与手动搜索共用），失败返回 null 并设置错误态 */
    private suspend fun searchNetworkSongsBlocking(keyword: String): List<Song>? {
        _networkSearchKeyword.value = keyword
        _networkSearchResults.value = UiState.Loading
        return try {
            nasMusicApp.networkMusicManager.search(keyword)
        } catch (e: Exception) {
            AppLog.e("MetingDiag", "doNetworkSearch failed: ${e.message}", e)
            _networkSearchResults.value = UiState.Error(
                message = "网络搜索失败: ${e.message?.take(50)}"
            )
            null
        }
    }

    /** 实际执行网络搜索（换一批与手动搜索共用） */
    private fun doNetworkSearch(keyword: String) {
        viewModelScope.launch {
            val results = searchNetworkSongsBlocking(keyword)
            if (results != null) {
                AppLog.i("MetingDiag", "doNetworkSearch: got ${results.size} results for '$keyword'")
                _networkSearchResults.value = UiState.Success(results)
                // 搜索成功后才记录历史（空结果也算成功；shuffleNetworkSearch 不走此路径，不会重复记录）
                prefs.recordSearch(keyword)
            }
        }
    }

    /**
     * 清除网络搜索结果
     */
    fun clearNetworkSearch() {
        _networkSearchResults.value = UiState.Success(emptyList())
        _networkSearchKeyword.value = ""
        networkSearchBaseKeyword = ""
        usedSearchVariants.clear()
        seenNetworkSearchKeys.clear()
    }

    /**
     * 全部加入列表：将当前搜索结果追加到播放队列末尾（不替换队列），
     * 与队列已有歌曲按（歌手, 歌曲名）去重，保证队列中没有重复歌曲。
     * 每次追加前实时读取队列，反复「换一批 → 全部加入列表」可持续扩充队列。
     */
    fun addAllSearchResultsToQueue() {
        val results = _networkSearchResults.value.dataOrNull() ?: return
        if (results.isEmpty()) return
        val existingKeys = queue.value
            .map { it.artist.trim() to it.title.trim() }
            .toSet()
        val toAdd = results
            .distinctBy { it.artist.trim() to it.title.trim() }
            .filterNot { (it.artist.trim() to it.title.trim()) in existingKeys }
        if (toAdd.isEmpty()) {
            showError("队列已包含全部搜索结果")
            return
        }
        playerManager.addToQueue(toAdd)
        _connectMessage.value = "已加入 ${toAdd.size} 首到队列（跳过 ${results.size - toAdd.size} 首重复）"
        viewModelScope.launch {
            delay(3000)
            _connectMessage.value = null
        }
    }

    // --- 多维度浏览（语种/纯音乐/年代/情怀/风格） ---

    /** 各维度当前选中的选项索引，默认全是 0（"所有"） */
    private val _browseSelections = MutableStateFlow(
        BrowseDimension.entries.map { 0 }
    )
    val browseSelections: StateFlow<List<Int>> = _browseSelections.asStateFlow()

    /** 浏览搜索结果 */
    private val _browseResults = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val browseResults: StateFlow<UiState<List<Song>>> = _browseResults.asStateFlow()

    /** 当前是否正在搜索 */
    private val _isBrowseSearching = MutableStateFlow(false)
    val isBrowseSearching: StateFlow<Boolean> = _isBrowseSearching.asStateFlow()

    /**
     * 设置某个维度的选中选项并自动刷新。
     * @param dimensionIndex BrowseDimension.entries 中的索引
     * @param optionIndex 该维度 options 列表中的索引
     */
    fun selectBrowseOption(dimensionIndex: Int, optionIndex: Int) {
        val current = _browseSelections.value.toMutableList()
        if (dimensionIndex in current.indices) {
            // 筛选条件变化 = 新上下文，重置浏览已见集合（跨批次去重从头开始）
            if (current[dimensionIndex] != optionIndex) {
                browseSeenKeys.clear()
            }
            current[dimensionIndex] = optionIndex
            _browseSelections.value = current
        }
        // 变更后自动刷新（如果有非 ALL 选项）
        refreshBrowseSongs()
    }

    /**
     * 刷新浏览结果：收集非"所有"选项的关键词，随机各取一个，组合搜索。
     *
     * 与网络搜索「换一批」共用同一套跨批次去重逻辑：在多个随机关键词组合中
     * 挑选新歌最多的批次展示，保证每次「换一批」只出新歌。
     */
    fun refreshBrowseSongs() {
        val dimensions = BrowseDimension.entries
        val selections = _browseSelections.value

        // 收集非 ALL 选项的关键词
        val keywordList = mutableListOf<String>()
        for (i in dimensions.indices) {
            val opt = dimensions[i].options.getOrNull(selections.getOrNull(i) ?: 0)
                ?: continue
            if (opt.label == "所有") continue
            if (opt.keywords.isEmpty()) continue
            // 从该选项的关键词列表中随机选一个
            keywordList.add(opt.keywords.random())
        }

        if (keywordList.isEmpty()) {
            _browseResults.value = UiState.Success(emptyList())
            return
        }

        _isBrowseSearching.value = true
        _browseResults.value = UiState.Loading

        viewModelScope.launch {
            try {
                val (_, shown) = pickBestFreshBatch(
                    seenKeys = browseSeenKeys,
                    produce = {
                        // 每次候选都重新随机取关键词，增加组合多样性
                        val combo = mutableListOf<String>()
                        for (i in dimensions.indices) {
                            val opt = dimensions[i].options.getOrNull(selections.getOrNull(i) ?: 0)
                                ?: continue
                            if (opt.label == "所有") continue
                            if (opt.keywords.isEmpty()) continue
                            combo.add(opt.keywords.random())
                        }
                        nasMusicApp.networkMusicManager.searchByKeywords(combo)
                    },
                    songsOf = { it }
                )
                _browseResults.value = UiState.Success(shown)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "refreshBrowseSongs failed: ${e.message}", e)
                _browseResults.value = UiState.Error(
                    message = "浏览搜索失败: ${e.message?.take(50)}"
                )
            } finally {
                _isBrowseSearching.value = false
            }
        }
    }

    /**
     * 播放全部浏览结果。
     * 不触发导航，由调用方（AppRoot）处理 navigateTo(NowPlaying)。
     */
    fun playAllBrowseSongs() {
        val results = _browseResults.value.dataOrNull() ?: return
        if (results.isEmpty()) return
        playNetworkBatch(results, 0)
    }

    /**
     * 播放全部搜索结果。
     *
     * 加入队列前按（歌手, 歌曲名）去重；去重后最多取 30 首，
     * 不足 30 首时有多少加多少。不触发导航，由调用方（AppRoot）处理 navigateTo(NowPlaying)。
     */
    fun playAllSearchResults() {
        val results = _networkSearchResults.value.dataOrNull() ?: return
        if (results.isEmpty()) return
        val deduped = results
            .distinctBy { it.artist.trim() to it.title.trim() }
            .take(maxNetworkBatchPlayCount)
        if (deduped.isEmpty()) return
        playNetworkBatch(deduped, 0)
    }

    /**
     * 批量播放网络歌曲。
     *
     * 性能优化：不再预先串行解析全部歌曲的播放链接（最多 30 首串行网络请求，
     * 会导致"全部播放"后等待很久才更新队列并开始播放）。
     * 改为只即时解析 [startIndex] 处第一首，立即更新队列并开始播放；
     * 后续歌曲沿用已有的 onNeedResolveStreamUrl → resolveAndPlayByIndex 懒加载机制，
     * 在播放到该曲（onMediaItemTransition AUTO）或切歌时按需解析，
     * 与单首网络歌曲及"恢复队列"的播放路径一致。
     * 首首串行解析的总延迟由 30×RTT 降至 1×RTT。
     */
    private fun playNetworkBatch(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        val safeStart = startIndex.coerceIn(0, songs.lastIndex)
        viewModelScope.launch {
            val first = songs[safeStart]
            val resolvedFirst = if (first.streamUrl.isNullOrBlank()) {
                try {
                    val url = nasMusicApp.networkMusicManager.resolvePlayUrl(first)
                    if (!url.isNullOrBlank()) first.copy(streamUrl = url) else first
                } catch (e: Exception) {
                    AppLog.e("NASMusic", "playNetworkBatch: resolve first failed for ${first.title}", e)
                    first
                }
            } else {
                first
            }
            val queue = songs.toMutableList().apply { this[safeStart] = resolvedFirst }
            playQueue(queue, safeStart)
        }
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
     * 加载所有预配置的网络歌单（分页轮换）
     *
     * 从 _chartsRotationIndex 位置开始取 CHART_PAGE_SIZE 个歌单。
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
        // 清掉当前歌手的缓存，确保用新格式重新拉取
        val currentMap = _artistSongsMap.value.toMutableMap()
        currentMap.remove(artistName)
        _artistSongsMap.value = currentMap
        _artistDetailSongsCache.value = _artistDetailSongsCache.value.toMutableMap().apply { remove(artistName) }

        // 从后端加载
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: return@launch
            try {
                // 从原始艺术家列表中找出所有与目标歌手相关的条目
                // 例如 "李宗盛" 可能匹配到独立条目 "李宗盛" 以及合作条目 "李宗盛 & 周华健"
                val rawMatchingIds = _rawArtistList
                    .filter { artistName in ArtistSplitter.split(it.name) }
                    .map { it.id }
                    .distinct()
                    .ifEmpty {
                        // fallback: 从拆分后的列表中提取原始 ID
                        val artists = _artists.value.dataOrNull() ?: emptyList()
                        val artist = artists.find { it.name == artistName }
                        if (artist != null) listOf(artist.id.substringBefore("|", artist.id)) else emptyList()
                    }

                AppLog.d("NASMusic", "loadArtistSongs('$artistName') rawMatchingIds=${rawMatchingIds.size}: $rawMatchingIds")

                // 分别查询每个原始 ID 的歌曲后合并去重（解决 Navidrome 合作歌曲不完整的问题）
                val allSongs = rawMatchingIds.flatMap { id ->
                    try {
                        adapter.getArtistSongs(id, artistName)
                    } catch (e: Exception) {
                        AppLog.w("NASMusic", "loadArtistSongs: ID=$id query failed: ${e.message?.take(50)}")
                        emptyList()
                    }
                }.distinctBy { it.id }

                AppLog.d("NASMusic", "loadArtistSongs('$artistName') allSongs=${allSongs.size} (from ${rawMatchingIds.size} IDs)")
                allSongs.take(3).forEach { s ->
                    AppLog.d("NASMusic", "  song artist='${s.artist}' title='${s.title}' album='${s.album}'")
                }
                // 将返回的歌曲按 ArtistSplitter 拆分后，只取包含该艺术家的歌曲
                val matchingSongs = allSongs.filter { song ->
                    artistName in ArtistSplitter.split(song.artist)
                }
                AppLog.d("NASMusic", "  matchingSongs=${matchingSongs.size} (raw=${allSongs.size})")
                _artistDetailSongsCache.value = _artistDetailSongsCache.value.toMutableMap().apply {
                    put(artistName, matchingSongs)
                }
                // 同时按拆分后的艺术家名更新 artistSongsMap 缓存
                buildArtistMapsIncremental(matchingSongs)
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

    // --- 本地歌单操作（「我的」Tab，DataStore 持久化）---

    /**
     * 创建本地歌单（空名称忽略）
     */
    fun createLocalPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                prefs.createLocalPlaylist(name)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "createLocalPlaylist failed", e)
                showError("创建歌单失败: ${e.message?.take(50)}")
            }
        }
    }

    /**
     * 重命名本地歌单
     */
    fun renameLocalPlaylist(id: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            try {
                prefs.renameLocalPlaylist(id, newName)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "renameLocalPlaylist failed", e)
                showError("重命名歌单失败: ${e.message?.take(50)}")
            }
        }
    }

    /**
     * 删除本地歌单
     */
    fun deleteLocalPlaylist(id: String) {
        viewModelScope.launch {
            try {
                prefs.deleteLocalPlaylist(id)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "deleteLocalPlaylist failed", e)
                showError("删除歌单失败: ${e.message?.take(50)}")
            }
        }
    }

    /**
     * 添加歌曲到本地歌单（已存在则提示）
     */
    fun addSongToPlaylist(playlistId: String, song: Song) {
        viewModelScope.launch {
            try {
                val added = prefs.addSongToPlaylist(playlistId, song)
                if (!added) {
                    showError("歌曲已在歌单中")
                }
            } catch (e: Exception) {
                AppLog.e("NASMusic", "addSongToPlaylist failed", e)
                showError("添加到歌单失败: ${e.message?.take(50)}")
            }
        }
    }

    /**
     * 从本地歌单移除歌曲
     */
    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch {
            try {
                prefs.removeSongFromPlaylist(playlistId, songId)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "removeSongFromPlaylist failed", e)
                showError("从歌单移除失败: ${e.message?.take(50)}")
            }
        }
    }

    /**
     * 播放整个本地歌单（含网络歌曲时自动解析 streamUrl）
     */
    fun playLocalPlaylist(playlist: LocalPlaylist) {
        if (playlist.songs.isEmpty()) return
        playQueue(playlist.songs, 0)
        navigateTo(Screen.NowPlaying)
    }

    // --- 数据备份（设置页入口，含服务器地址但不含密码）---

    /** 当前可用的备份文件列表（按修改时间倒序） */
    private val _backupFiles = MutableStateFlow<List<BackupFileUtils.BackupFile>>(emptyList())
    val backupFiles: StateFlow<List<BackupFileUtils.BackupFile>> = _backupFiles.asStateFlow()

    /** 备份操作结果消息（导出成功/失败、导入成功/失败） */
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    /** 刷新备份文件列表（进入设置页时调用） */
    fun refreshBackupFiles() {
        viewModelScope.launch {
            _backupFiles.value = BackupFileUtils.listBackups(getApplication())
        }
    }

    /** 导出完整备份到 Downloads/NASMusic/（含服务器地址，不含密码/Token） */
    fun exportBackup() {
        viewModelScope.launch {
            try {
                val data = prefs.exportBackupData().copy(
                    mvCacheEntries = mvSearchManager.exportMvCache()
                )
                val json = Gson().toJson(data)
                val result = BackupFileUtils.export(getApplication(), json)
                result.onSuccess { fileName ->
                    _backupFiles.value = BackupFileUtils.listBackups(getApplication())
                    _backupMessage.value = "备份成功：$fileName"
                }.onFailure { e ->
                    AppLog.e("NASMusic", "exportBackup failed", e)
                    _backupMessage.value = "备份失败：${e.message?.take(60) ?: "未知错误"}"
                }
            } catch (e: Exception) {
                AppLog.e("NASMusic", "exportBackup failed", e)
                _backupMessage.value = "备份失败：${e.message?.take(60) ?: "未知错误"}"
            }
        }
    }

    /**
     * 从指定备份文件恢复数据
     * 恢复后服务器未连接（密码不备份），需重新连接
     */
    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = BackupFileUtils.read(getApplication(), uri).getOrThrow()
                val data = Gson().fromJson(json, AppPreferences.BackupData::class.java)
                prefs.importBackupData(data)
                mvSearchManager.importMvCache(data.mvCacheEntries)
                // 刷新受备份影响的 UI 状态
                refreshAfterImport()
                _backupMessage.value = "恢复成功，请重新连接服务器"
            } catch (e: Exception) {
                AppLog.e("NASMusic", "importBackup failed", e)
                _backupMessage.value = "恢复失败：${e.message?.take(60) ?: "未知错误"}"
            }
        }
    }

    /**
     * 从 JSON 字符串恢复备份（用于扫码传输）
     * @return true 恢复成功；false 失败
     */
    suspend fun restoreBackupFromJson(json: String): Boolean {
        return try {
            val data = Gson().fromJson(json, AppPreferences.BackupData::class.java)
            prefs.importBackupData(data)
            mvSearchManager.importMvCache(data.mvCacheEntries)
            refreshAfterImport()
            _backupMessage.value = "恢复成功，请重新连接服务器"
            true
        } catch (e: Exception) {
            AppLog.e("NASMusic", "restoreBackupFromJson failed", e)
            false
        }
    }

    /**
     * 非挂起版本的 [restoreBackupFromJson]，供 BackupTransferServer 回调用。
     *
     * NanoHTTPD 的 `serve()` 是同步的，必须立即返回响应；此方法用 `runBlocking`
     * 在 NanoHTTPD 工作线程上桥接 suspend 调用（非主线程，安全）。
     * 桥接职责集中在 ViewModel，使 BackupTransferServer / BackupTransferDialog
     * 不依赖协程库。
     */
    fun restoreBackupFromJsonBlocking(json: String): Boolean =
        kotlinx.coroutines.runBlocking { restoreBackupFromJson(json) }

    /** 导入备份后刷新相关 StateFlow（收藏、歌单、队列、统计等由 prefs Flow 自动更新） */
    private fun refreshAfterImport() {
        // 服务器连接状态保持断开（密码不备份），其余由 collect 自动同步
        _backupFiles.value = BackupFileUtils.listBackups(getApplication())
    }

    /** 删除指定备份文件 */
    fun deleteBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val result = BackupFileUtils.delete(getApplication(), uri)
                _backupFiles.value = BackupFileUtils.listBackups(getApplication())
                result.onSuccess {
                    _backupMessage.value = "备份已删除"
                }.onFailure { e ->
                    AppLog.e("NASMusic", "deleteBackup failed", e)
                    _backupMessage.value = "删除失败：${e.message?.take(60) ?: "未知错误"}"
                }
            } catch (e: Exception) {
                AppLog.e("NASMusic", "deleteBackup failed", e)
                _backupMessage.value = "删除失败：${e.message?.take(60) ?: "未知错误"}"
            }
        }
    }

    /** 消费备份结果消息（UI 显示后调用） */
    fun consumeBackupMessage() {
        _backupMessage.value = null
    }

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
        if (_isNetworkAvailable.value) return // 已是可用状态，跳过（防止 NetworkMonitor 重复回调）
        _isNetworkAvailable.value = true
        // MTV 模式下不弹提示（MV 视频流请求可能导致网络抖动，频繁弹"网络已恢复"打扰观看）
        if (!_showMv.value) {
            _connectMessage.value = "网络已恢复"
            viewModelScope.launch {
                delay(2000)
                _connectMessage.value = null
            }
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
        if (!_showMv.value) {
            _connectMessage.value = "网络已断开"
            viewModelScope.launch {
                delay(5000)
                _connectMessage.value = null
            }
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
        // 非随心听播放时停止自动续播
        _isShufflePlaying = false
        shuffleRefillJob?.cancel()
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

    private fun resolveAndPlayByIndex(targetIndex: Int) {
        val queueValue = queue.value
        val song = queueValue.getOrNull(targetIndex) ?: return
        viewModelScope.launch {
            try {
                var playUrl = resolveStreamUrl(song)
                // 初次解析失败（网络瞬时抖动/端点超时）：延迟 1.5s 自动重试一次
                if (playUrl.isNullOrBlank()) {
                    AppLog.w("NASMusic", "resolveAndPlayByIndex: initial resolve failed for ${song.title}, retrying in 1.5s")
                    delay(1500)
                    playUrl = resolveStreamUrl(song)
                }
                if (playUrl.isNullOrBlank()) {
                    // 重试仍失败：不再静默卡在"已切歌未播放"状态，自动跳到下一首
                    AppLog.w("NASMusic", "resolveAndPlayByIndex: failed after retry, skipping ${song.title}")
                    showError("无法解析播放链接，自动跳过《${song.title}》")
                    playerManager.next(_playMode.value)
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

    // --- KARAOKE 伴奏模式（人声消除）---
    private val _vocalRemovalEnabled = MutableStateFlow(false)
    val vocalRemovalEnabled: StateFlow<Boolean> = _vocalRemovalEnabled.asStateFlow()

    fun toggleVocalRemoval() {
        val newValue = !_vocalRemovalEnabled.value
        _vocalRemovalEnabled.value = newValue
        playerManager.setVocalRemovalEnabled(newValue)
        AppLog.d("NASMusic", "toggleVocalRemoval -> $newValue")
    }

    // --- MTV 音乐视频 ---
    private val _mvState = MutableStateFlow<MvAvailability>(MvAvailability.Idle)
    val mvState: StateFlow<MvAvailability> = _mvState.asStateFlow()

    /** MTV 页面显隐（进入 MTV 页面时为 true） */
    private val _showMv = MutableStateFlow(false)
    val showMv: StateFlow<Boolean> = _showMv.asStateFlow()

    private var mvSearchJob: Job? = null

    /** 当前歌曲的 MV 播放失败是否已重搜过一次（防止死循环；切歌时在 triggerMvSearch 内重置） */
    private var mvRetryDone = false

    /** 预搜的下一首 MV 结果（null = 未搜到或尚未完成预搜） */
    private var pendingNextResult: MvSearchResult? = null

    /** MTV 连播是否已静默推进队列索引（退出时据此决定 syncAndPlay 还是 resume） */
    private var mvAdvanced = false

    /** 静默推进索引时跳过 currentSong.collect 的 triggerMvSearch（避免覆盖预搜结果） */
    private var skipNextMvSearch = false

    /** 当前搜索会话内「切换」已按次数（达到 2×候选总数后触发重搜） */
    private var mvSwitchCount = 0
    /** 重搜次数（上限 2 次，防止无限重搜） */
    private var mvResearchCount = 0
    /** 重搜时排除的 bvid 集合（已展示过的视频不再出现） */
    private val mvExcludedBvids = mutableSetOf<String>()

    /** MTV 页面短暂提示（切换失败/未找到更多视频），2 秒后自动清除 */
    private val _mvMessage = MutableStateFlow<String?>(null)
    val mvMessage: StateFlow<String?> = _mvMessage.asStateFlow()

    /**
     * 切歌/播放时自动搜索当前歌曲的 MV（MvSearchManager 内部有内存缓存，命中不重复请求）。
     * 置 Searching → searchMvFor → Ready/NotFound；由 UI 按钮消费决定亮/暗。
     */
    fun triggerMvSearch(song: Song) {
        mvRetryDone = false
        pendingNextResult = null // 清除旧预搜
        mvSwitchCount = 0 // 重置切换计数
        mvResearchCount = 0 // 重置重搜计数
        mvExcludedBvids.clear() // 清除排除列表
        mvSearchJob?.cancel()
        _mvState.value = MvAvailability.Searching
        mvSearchJob = viewModelScope.launch {
            val result = try {
                mvSearchManager.searchMvFor(song)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "triggerMvSearch failed", e)
                null
            }
            _mvState.value = if (result != null) MvAvailability.Ready(result.mv, result.alternatives) else MvAvailability.NotFound
            AppLog.d("NASMusic", "triggerMvSearch: ${song.title} -> ${if (result != null) "found ${result.mv.title} + ${result.alternatives.size} alts" else "not found"}")
            // MTV 模式下预搜下一首
            if (result != null && _showMv.value) preSearchNextMv()
        }
    }

    /**
     * 进入 MTV 页面：暂停主播放器 + 显示 MTV 页 + 预搜下一首 MV。
     */
    fun enterMvMode() {
        val ready = _mvState.value as? MvAvailability.Ready ?: return
        mvAdvanced = false
        AppLog.d("NASMusic", "enterMvMode: ${ready.mv.title}")
        ensureRemoteControlStarted()
        playerManager.suppressPlayback = true
        playerManager.pause()
        _showMv.value = true
        preSearchNextMv()
    }

    /**
     * 退出 MTV 页面：隐藏 MTV 页 + 恢复主播放器。
     * 若 MTV 连播已静默推进队列索引（mvAdvanced），用 syncAndPlayCurrent 同步到新歌；
     * 否则 resume 从暂停位置续播。
     */
    fun exitMvMode() {
        AppLog.d("NASMusic", "exitMvMode: mvAdvanced=$mvAdvanced")
        _showMv.value = false
        pendingNextResult = null
        playerManager.suppressPlayback = false // 恢复播放前先解除限制
        if (mvAdvanced) {
            playerManager.syncAndPlayCurrent()
        } else {
            playerManager.resume()
        }
    }

    /**
     * MV 播放失败回调：清缓存 + 重搜一次（同一首歌只重搜一次防死循环）。
     */
    fun onMvPlaybackError() {
        if (mvRetryDone) {
            AppLog.d("NASMusic", "onMvPlaybackError: already retried, skip")
            return
        }
        val song = currentSong.value ?: return
        mvRetryDone = true
        AppLog.d("NASMusic", "onMvPlaybackError: clearCache + re-search '${song.title}'")
        mvSearchManager.clearCache()
        triggerMvSearch(song)
    }

    /**
     * MV 播放结束回调（连播模式）：
     * - 有预搜结果 -> 静默推进队列索引 + 直接设 Ready（无缝切换，无 Searching 闪烁，无混音）
     * - 无预搜结果 -> 静默推进 + 设 NotFound -> AppRoot 自动 exitMvMode -> syncAndPlayCurrent 播下一首
     */
    fun onMvPlaybackEnded() {
        // 标记当前 MV 播放完成（用户认可这个版本）-> 持久缓存 playCount++，下次优先用这个 bvid
        val completedSong = currentSong.value
        val completedMv = (_mvState.value as? MvAvailability.Ready)?.mv
        if (completedSong != null && completedMv != null) {
            mvSearchManager.markCompleted(completedSong.id, completedSong.title, completedSong.artist, completedMv.bvid, completedMv.title)
        }

        val pending = pendingNextResult
        skipNextMvSearch = true // advanceIndexSilently 会更新 _currentSong，跳过 collect 的 triggerMvSearch
        playerManager.advanceIndexSilently(_playMode.value)
        mvAdvanced = true

        if (pending != null) {
            _mvState.value = MvAvailability.Ready(pending.mv, pending.alternatives)
            pendingNextResult = null
            AppLog.d("NASMusic", "onMvPlaybackEnded: seamless switch to '${pending.mv.title}'")
            preSearchNextMv()
        } else {
            _mvState.value = MvAvailability.NotFound // 触发 AppRoot 自动 exitMvMode
            AppLog.d("NASMusic", "onMvPlaybackEnded: no pre-searched MV, exiting to playback")
        }
    }

    /**
     * MTV 页面"上一首"按钮：回退队列索引 + 搜索前一首的 MV（无预搜，走 Searching）。
     */
    fun onMvPrevious() {
        skipNextMvSearch = true
        val prevSong = playerManager.advanceIndexBackward(_playMode.value)
        if (prevSong == null) {
            skipNextMvSearch = false
            return
        }
        mvAdvanced = true
        _mvState.value = MvAvailability.Searching
        mvSearchJob?.cancel()
        mvSearchJob = viewModelScope.launch {
            val result = try {
                mvSearchManager.searchMvFor(prevSong)
            } catch (e: Exception) {
                null
            }
            _mvState.value = if (result != null) MvAvailability.Ready(result.mv, result.alternatives) else MvAvailability.NotFound
            AppLog.d("NASMusic", "onMvPrevious: '${prevSong.title}' -> ${if (result != null) "found" else "not found"}")
            if (result != null) preSearchNextMv()
        }
    }

    /**
     * MTV 页面"下一首"按钮：有预搜则无缝切换，无则同步搜索。
     */
    fun onMvNext() {
        val pending = pendingNextResult
        if (pending != null) {
            skipNextMvSearch = true
            playerManager.advanceIndexSilently(_playMode.value)
            mvAdvanced = true
            _mvState.value = MvAvailability.Ready(pending.mv, pending.alternatives)
            pendingNextResult = null
            AppLog.d("NASMusic", "onMvNext: seamless switch to '${pending.mv.title}'")
            preSearchNextMv()
        } else {
            skipNextMvSearch = true
            val nextSong = playerManager.advanceIndexSilently(_playMode.value)
            if (nextSong == null) { skipNextMvSearch = false; return }
            mvAdvanced = true
            _mvState.value = MvAvailability.Searching
            mvSearchJob?.cancel()
            mvSearchJob = viewModelScope.launch {
                val result = try { mvSearchManager.searchMvFor(nextSong) } catch (e: Exception) { null }
                _mvState.value = if (result != null) MvAvailability.Ready(result.mv, result.alternatives) else MvAvailability.NotFound
                AppLog.d("NASMusic", "onMvNext: '${nextSong.title}' -> ${if (result != null) "found" else "not found"}")
                if (result != null) preSearchNextMv()
            }
        }
    }

    /**
     * 预搜下一首歌曲的 MV（后台协程，不阻塞 UI）。
     * MTV 模式下当前 MV 搜到后调用，结果存入 [pendingNextResult] 供 onMvPlaybackEnded 无缝切换。
     */
    private fun preSearchNextMv() {
        val nextSong = playerManager.peekNextSong(_playMode.value) ?: run {
            pendingNextResult = null
            return
        }
        viewModelScope.launch {
            val result = try {
                mvSearchManager.searchMvFor(nextSong)
            } catch (e: Exception) {
                AppLog.w("NASMusic", "preSearchNextMv failed: ${e.message}", e)
                null
            }
            pendingNextResult = result
            AppLog.d("NASMusic", "preSearchNextMv: '${nextSong.title}' -> ${if (result != null) "found ${result.mv.title}" else "not found"}")
        }
    }

    /**
     * 切换到候选列表中的另一个 MV（MTV 页面用户手动切换）。
     * 按需解析 bvid 直链，旧 MV 变为候选。
     */
    /**
     * MTV 页面「切换」按钮统一入口：
     * - 无候选 -> 直接重搜（排除当前 bvid）
     * - 有候选，已切换 2 轮 -> 重搜（排除所有已展示 bvid）
     * - 有候选，未满 2 轮 -> 切换到下一个候选
     * - 重搜次数已达上限（2 次）-> 提示"未找到更多视频"
     */
    fun onSwitchOrResearch() {
        val ready = _mvState.value as? MvAvailability.Ready ?: return
        val totalVideos = 1 + ready.alternatives.size

        if (ready.alternatives.isEmpty()) {
            if (mvResearchCount >= 2) { showMvMessage("未找到更多视频"); return }
            researchMv(ready)
            return
        }

        mvSwitchCount++
        if (mvSwitchCount > 2 * totalVideos) {
            if (mvResearchCount >= 2) { showMvMessage("未找到更多视频"); return }
            researchMv(ready)
        } else {
            switchToNextCandidate(ready)
        }
    }

    /**
     * MTV 页面「搜B站」按钮：当前 MV 来自百度网盘本地文件（source == "baidu"）时，
     * 强制从非百度源（B 站）重新搜索，替换当前 MV 状态。
     */
    fun onSearchBilibili() {
        val song = currentSong.value ?: return
        _mvState.value = MvAvailability.Searching
        mvSearchJob?.cancel()
        mvSearchJob = viewModelScope.launch {
            val result = try {
                mvSearchManager.searchBilibiliFallback(song)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "onSearchBilibili failed", e)
                null
            }
            if (result != null) {
                _mvState.value = MvAvailability.Ready(result.mv, result.alternatives)
                showMvMessage("已切换到B站搜索结果")
                preSearchNextMv()
            } else {
                _mvState.value = MvAvailability.NotFound
                showMvMessage("B站未找到匹配视频")
            }
            AppLog.d("NASMusic", "onSearchBilibili: '${song.title}' -> ${if (result != null) "found ${result.mv.title}" else "not found"}")
        }
    }

    /** 切换到候选列表中的下一个视频 */
    private fun switchToNextCandidate(ready: MvAvailability.Ready) {
        val targetBvid = ready.alternatives.firstOrNull()?.bvid ?: return
        viewModelScope.launch {
            AppLog.d("NASMusic", "switchToNextCandidate: bvid=$targetBvid")
            val newMv = mvSearchManager.resolveMv(targetBvid)
            if (newMv == null) {
                AppLog.w("NASMusic", "switchToNextCandidate: resolve failed")
                showMvMessage("切换失败，请重试")
                mvSwitchCount-- // 切换未成功，回退计数
                return@launch
            }
            val oldCandidate = MvCandidate(ready.mv.bvid, ready.mv.title, ready.mv.coverUrl)
            val newAlternatives = ready.alternatives.filter { it.bvid != targetBvid } + oldCandidate
            _mvState.value = MvAvailability.Ready(newMv, newAlternatives)
            AppLog.d("NASMusic", "switchToNextCandidate: switched to '${newMv.title}'")
        }
    }

    /** 重搜：排除已展示 bvid + 降低相似度阈值，后台搜索不打断当前播放 */
    private fun researchMv(ready: MvAvailability.Ready) {
        val song = currentSong.value ?: return
        mvExcludedBvids.add(ready.mv.bvid)
        ready.alternatives.forEach { mvExcludedBvids.add(it.bvid) }
        mvResearchCount++
        val minSim = when (mvResearchCount) { 1 -> 0.3f; 2 -> 0.1f; else -> 0f }
        AppLog.d("NASMusic", "researchMv: #${mvResearchCount} exclude=${mvExcludedBvids.size} minSim=$minSim")
        showMvMessage("正在搜索更多视频...")
        mvSearchJob?.cancel()
        mvSearchJob = viewModelScope.launch {
            val result = try {
                mvSearchManager.searchMvFor(song, forceRefresh = true, excludeBvids = mvExcludedBvids.toSet(), minSimilarity = minSim)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "researchMv failed", e)
                null
            }
            if (result != null) {
                mvSwitchCount = 0
                _mvState.value = MvAvailability.Ready(result.mv, result.alternatives)
                showMvMessage("找到 ${1 + result.alternatives.size} 个新视频")
                if (_showMv.value) preSearchNextMv()
            } else {
                mvResearchCount--
                showMvMessage("未找到更多视频")
            }
        }
    }

    private var mvMessageJob: Job? = null
    private fun showMvMessage(msg: String) {
        mvMessageJob?.cancel()
        _mvMessage.value = msg
        mvMessageJob = viewModelScope.launch {
            delay(2000)
            _mvMessage.value = null
        }
    }

    // ---- RemoteCallbacks 实现（手机遥控服务器回调）----

    override fun onCleared() {
        remoteControlServer.stop()
        super.onCleared()
    }

    override fun getQueue(): List<Song> = playerManager.queue.value
    override fun getCurrentIndex(): Int = playerManager.currentIndex.value
    override fun isPlaying(): Boolean = playerManager.isPlaying.value
    override fun getProgressMs(): Long = playerManager.progress.value
    override fun getDurationMs(): Long = playerManager.duration.value

    override fun playAt(index: Int) {
        mainHandler.post { playerManager.playAt(index) }
    }

    override fun moveQueueItem(from: Int, to: Int) {
        mainHandler.post { playerManager.moveItem(from, to) }
    }

    override fun addToQueue(song: Song) {
        mainHandler.post { playerManager.addToQueue(song) }
    }

    override suspend fun search(keyword: String): RemoteSearchResult = coroutineScope {
        val nasDeferred = async(Dispatchers.IO) {
            try { backendRegistry.getAdapter()?.searchSongs(keyword) ?: emptyList() }
            catch (e: Exception) { AppLog.w("NASMusic", "remote search NAS failed: ${e.message}"); emptyList() }
        }
        val netDeferred = async(Dispatchers.IO) {
            try { nasMusicApp.networkMusicManager.search(keyword) }
            catch (e: Exception) { AppLog.w("NASMusic", "remote search network failed: ${e.message}"); emptyList() }
        }
        RemoteSearchResult(nasDeferred.await(), netDeferred.await())
    }

    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        playerManager.applyPlayMode(mode)
    }

    fun addSongToQueue(song: Song) = playerManager.addToQueue(song)

    override fun removeFromQueue(index: Int) = playerManager.removeFromQueue(index)

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

    fun clearQueue() {
        playerManager.clearQueue()
        _currentLyrics.value = null
        _lyricsAvailability.value = LyricsAvailability()
        // 清除持久化的上次播放队列
        viewModelScope.launch { prefs.clearLastQueue() }
    }

    private fun loadLyricsForCurrentSong() {
        lyricsLoadJob?.cancel()
        // 切歌时清空候选缓存和轮次状态
        lyricsManager.clearCachedCandidates()
        _currentLyrics.value = null
        _lyricsAvailability.value = LyricsAvailability()
        val song = currentSong.value ?: return
        AppLog.d("NASMusic", "loadLyrics: loading for ${song.title} by ${song.artist}")
        lyricsLoadJob = viewModelScope.launch {
            try {
                // 1. 先查持久化缓存——快速读取，不阻塞歌词显示
                val cachedLyrics = lyricsManager.getCachedNetworkLyrics(song)
                if (cachedLyrics != null) {
                    _currentLyrics.value = cachedLyrics
                    _lyricsAvailability.value = LyricsAvailability(cached = cachedLyrics)
                    if (cachedLyrics.lines.any { it.wordTimestamps.isNotEmpty() }) {
                        _lyricsHighlightMode.value = LyricsHighlightMode.WORD_BY_WORD
                    }
                    AppLog.d("NASMusic", "loadLyrics: cached hit, shown immediately")
                }

                // 2. 后台检查后端 + 网络可用来源（更新标签状态，不影响已显示的歌词）
                val availability = lyricsManager.checkAvailability(song)
                // 保留已有的缓存状态
                _lyricsAvailability.value = availability.copy(cached = _lyricsAvailability.value?.cached)
                AppLog.d("NASMusic", "loadLyrics: cached=${cachedLyrics != null}, backend=${availability.hasBackend}, network=${availability.hasNetwork}")

                // 3. 无缓存时使用后端或网络歌词
                if (cachedLyrics == null) {
                    val lyrics = availability.backend ?: availability.network
                    if (lyrics != null) {
                        _currentLyrics.value = lyrics
                        if (lyrics.lines.any { it.wordTimestamps.isNotEmpty() }) {
                            _lyricsHighlightMode.value = LyricsHighlightMode.WORD_BY_WORD
                        }
                    }
                    AppLog.d("NASMusic", "loadLyrics: source=${lyrics?.source}, lines=${lyrics?.lines?.size}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
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

    // 网络歌词候选索引：再次按下"在线歌词"按钮时递增，切换不同候选
    private var networkLyricsCandidateIndex = 0
    private var networkLyricsSongId: String? = null

    /**
     * 切换歌词来源
     * 切换到在线歌词时联动获取网络封面，切回内嵌时清除网络封面
     * 如果当前已显示网络歌词，再次按下"在线歌词"按钮 → 取下一个候选歌词
     */
    fun switchLyricsSource(source: LyricsSource) {
        val song = currentSong.value ?: return
        val currentSource = _currentLyrics.value?.source
        AppLog.d("NASMusic", "switchLyricsSource: $source, currentSource=$currentSource")

        // 切歌时重置候选索引
        if (song.id != networkLyricsSongId) {
            networkLyricsCandidateIndex = 0
            networkLyricsSongId = song.id
        }

        // 已显示网络歌词 + 再次按下在线歌词按钮 → 尝试下一个候选
        if (source == LyricsSource.NETWORK && currentSource == LyricsSource.NETWORK) {
            networkLyricsCandidateIndex++
            AppLog.d("NASMusic", "switchLyricsSource: increment candidateIndex to $networkLyricsCandidateIndex")
        } else {
            networkLyricsCandidateIndex = 0
        }

        viewModelScope.launch {
            try {
                val lyrics = lyricsManager.getLyricsFromSource(song, source, networkLyricsCandidateIndex)
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
     * 更新 MTV 视频搜索端点 URL（网络搜索配置）
     * 传入空串则恢复默认端点
     */
    fun updateMvApiBaseUrl(url: String) = viewModelScope.launch {
        val normalized = url.trim()
        if (normalized.isEmpty()) {
            prefs.setMvApiBaseUrl(com.nasmusic.tv.backend.network.mv.BilibiliMvService.DEFAULT_BASE_URL)
        } else {
            prefs.setMvApiBaseUrl(normalized)
        }
    }

    fun updateLyricsKugouBaseUrl(url: String) = viewModelScope.launch {
        val normalized = url.trim()
        if (normalized.isEmpty()) {
            prefs.setLyricsKugouBaseUrl(com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_KUGOU_BASE_URL)
        } else {
            prefs.setLyricsKugouBaseUrl(normalized)
        }
    }

    fun updateLyricsNeteaseBaseUrl(url: String) = viewModelScope.launch {
        val normalized = url.trim()
        if (normalized.isEmpty()) {
            prefs.setLyricsNeteaseBaseUrl(com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_NETEASE_BASE_URL)
        } else {
            prefs.setLyricsNeteaseBaseUrl(normalized)
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

    fun updateVisualizerTheme(theme: com.nasmusic.tv.data.model.VisualizerTheme) = viewModelScope.launch {
        prefs.setVisualizerTheme(theme)
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

    /** 清除 MV 持久缓存（设置页"缓存管理"手动清除用） */
    fun clearMvPersistentCache() {
        viewModelScope.launch {
            mvSearchManager.clearPersistentCache()
            _connectMessage.value = "MV 缓存已清除"
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

    // ===================== 百度网盘 / 网盘 Tab =====================

    /** 百度网盘连接状态 */
    sealed class BaiduConnectionState {
        object Off : BaiduConnectionState()           // 未开启或未登录
        object Connecting : BaiduConnectionState()     // 设备码轮询中
        object LoggedIn : BaiduConnectionState()      // 已登录
        data class Failed(val message: String) : BaiduConnectionState()
    }

    private val _baiduConnectionState = MutableStateFlow<BaiduConnectionState>(BaiduConnectionState.Off)
    val baiduConnectionState: StateFlow<BaiduConnectionState> = _baiduConnectionState.asStateFlow()

    /** 设备码授权结果（供对话框显示） */
    private val _baiduDeviceCode = MutableStateFlow<BaiduOAuthClient.DeviceCodeResult?>(null)
    val baiduDeviceCode: StateFlow<BaiduOAuthClient.DeviceCodeResult?> = _baiduDeviceCode.asStateFlow()

    /** 网盘目录浏览 */
    private val _netdiskCurrentDir = MutableStateFlow("/音乐")
    val netdiskCurrentDir: StateFlow<String> = _netdiskCurrentDir.asStateFlow()
    private val _netdiskDirFiles = MutableStateFlow<List<BaiduFile>>(emptyList())
    val netdiskDirFiles: StateFlow<List<BaiduFile>> = _netdiskDirFiles.asStateFlow()
    /** 网盘根目录是否已从配置同步过（防止 refreshBaiduConnectionState 每次重置浏览位置） */
    private var netdiskDirSynced = false
    private val _netdiskIsLoading = MutableStateFlow(false)
    val netdiskIsLoading: StateFlow<Boolean> = _netdiskIsLoading.asStateFlow()

    /** 网盘搜索 */
    private val _netdiskSearchResults = MutableStateFlow<List<Song>>(emptyList())
    val netdiskSearchResults: StateFlow<List<Song>> = _netdiskSearchResults.asStateFlow()
    private val _netdiskSearchKeyword = MutableStateFlow("")
    val netdiskSearchKeyword: StateFlow<String> = _netdiskSearchKeyword.asStateFlow()

    /** 索引状态 */
    private val _baiduIndexScanned = MutableStateFlow(0)
    val baiduIndexScanned: StateFlow<Int> = _baiduIndexScanned.asStateFlow()
    private val _baiduIndexScanning = MutableStateFlow(false)
    val baiduIndexScanning: StateFlow<Boolean> = _baiduIndexScanning.asStateFlow()
    private val _baiduIndexLastSync = MutableStateFlow(0L)
    val baiduIndexLastSync: StateFlow<Long> = _baiduIndexLastSync.asStateFlow()

    private val baiduOAuth: BaiduOAuthClient get() = nasMusicApp.baiduOAuthClient
    private val baiduApi: BaiduPanApi get() = nasMusicApp.baiduPanApi
    private val baiduIndexCache: BaiduFileIndexCache get() = nasMusicApp.baiduFileIndexCache

    private var deviceCodePollJob: kotlinx.coroutines.Job? = null

    /** 同步刷新连接状态（初始化与开关切换后调用） */
    fun refreshBaiduConnectionState() {
        val cfg = prefs.getBaiduConfigSync()
        _baiduConnectionState.value = when {
            !cfg.isActive -> BaiduConnectionState.Off
            cfg.tokens != null -> BaiduConnectionState.LoggedIn
            else -> BaiduConnectionState.Off
        }
        if (cfg.isActive) {
            // 仅首次启用/登录时同步根目录到配置值；之后保留用户浏览位置，切换页面不重置
            if (!netdiskDirSynced) {
                _netdiskCurrentDir.value = cfg.musicRootDir.ifBlank { "/音乐" }
                netdiskDirSynced = true
            }
            _baiduIndexLastSync.value = baiduIndexCache.load()?.lastSyncAt ?: 0L
        }
        // 通知 NasMusicApp 运行时注册/注销百度 service
        nasMusicApp.refreshBaiduServiceRegistration()
    }

    /** 设置百度源总开关 */
    fun setBaiduEnabled(enabled: Boolean) {
        prefs.setBaiduEnabledSync(enabled)
        refreshBaiduConnectionState()
    }

    /** 启动设备码授权流程：请求设备码并开始轮询 */
    fun startBaiduDeviceCodeFlow() {
        viewModelScope.launch {
            _baiduConnectionState.value = BaiduConnectionState.Connecting
            val code = baiduOAuth.requestDeviceCode()
            if (code == null) {
                _baiduConnectionState.value = BaiduConnectionState.Failed("获取设备码失败")
                return@launch
            }
            _baiduDeviceCode.value = code
            pollDeviceCode(code)
        }
    }

    /** 取消设备码轮询 */
    fun cancelBaiduDeviceCode() {
        deviceCodePollJob?.cancel()
        deviceCodePollJob = null
        _baiduDeviceCode.value = null
        if (_baiduConnectionState.value is BaiduConnectionState.Connecting) {
            _baiduConnectionState.value = BaiduConnectionState.Off
        }
    }

    private fun pollDeviceCode(code: BaiduOAuthClient.DeviceCodeResult) {
        deviceCodePollJob?.cancel()
        deviceCodePollJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
            var interval = code.interval * 1000L
            while (System.currentTimeMillis() < deadline && isActive()) {
                when (val r = baiduOAuth.pollDeviceToken(code.deviceCode)) {
                    is BaiduOAuthClient.PollResult.Success -> {
                        _baiduConnectionState.value = BaiduConnectionState.LoggedIn
                        _baiduDeviceCode.value = null
                        nasMusicApp.refreshBaiduServiceRegistration()
                        // 登录后自动触发首次索引扫描
                        triggerBaiduIndexScanIfNeeded()
                        return@launch
                    }
                    BaiduOAuthClient.PollResult.Pending -> {
                        kotlinx.coroutines.delay(interval)
                    }
                    BaiduOAuthClient.PollResult.Declined -> {
                        _baiduConnectionState.value = BaiduConnectionState.Failed("用户拒绝授权")
                        _baiduDeviceCode.value = null
                        return@launch
                    }
                    is BaiduOAuthClient.PollResult.SlowDown -> {
                        interval = r.newInterval * 1000L
                        kotlinx.coroutines.delay(interval)
                    }
                    is BaiduOAuthClient.PollResult.Failed -> {
                        _baiduConnectionState.value = BaiduConnectionState.Failed(r.message)
                        _baiduDeviceCode.value = null
                        return@launch
                    }
                }
            }
            _baiduConnectionState.value = BaiduConnectionState.Failed("授权超时")
            _baiduDeviceCode.value = null
        }
    }

    private suspend fun isActive(): Boolean =
        kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true

    /** 登出 */
    fun logoutBaidu() {
        viewModelScope.launch {
            baiduOAuth.logout()
            nasMusicApp.refreshBaiduServiceRegistration()
            _baiduConnectionState.value = BaiduConnectionState.Off
        }
    }

    // ---- 网盘目录浏览 ----

    fun listBaiduDir(dir: String) {
        _netdiskCurrentDir.value = dir
        _netdiskIsLoading.value = true
        viewModelScope.launch {
            try {
                val result = baiduApi.listDir(dir)
                _netdiskDirFiles.value = result.files
            } catch (e: Exception) {
                AppLog.e("NASMusic", "listBaiduDir error", e)
                showError("加载目录失败: ${e.message?.take(40)}")
                _netdiskDirFiles.value = emptyList()
            } finally {
                _netdiskIsLoading.value = false
            }
        }
    }

    /**
     * 列出网盘指定路径下的文件（供 [com.nasmusic.tv.ui.components.BaiduDirPickerDialog] 目录树选择使用）。
     * 异常直接上抛，由对话框展示失败态并允许重试。
     */
    suspend fun listBaiduDirs(path: String): List<BaiduFile> =
        baiduApi.listDir(path).files

    fun navigateBaiduDirUp() {
        val current = _netdiskCurrentDir.value
        if (current == "/" || current.isBlank()) return
        val parent = current.substringBeforeLast('/').ifBlank { "/" }
        listBaiduDir(parent)
    }

    fun enterBaiduDir(name: String) {
        val base = _netdiskCurrentDir.value.trimEnd('/')
        listBaiduDir("$base/$name")
    }

    // ---- 网盘搜索 ----

    fun searchBaidu(keyword: String) {
        _netdiskSearchKeyword.value = keyword
        if (keyword.isBlank()) {
            _netdiskSearchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _netdiskIsLoading.value = true
            try {
                val rootDir = prefs.getBaiduMusicRootDirSync().ifBlank { "/" }
                val files = baiduApi.searchAudio(keyword, dir = rootDir)
                _netdiskSearchResults.value = files.map { it.toSong() }
            } catch (e: Exception) {
                AppLog.e("NASMusic", "searchBaidu error", e)
                _netdiskSearchResults.value = emptyList()
            } finally {
                _netdiskIsLoading.value = false
            }
        }
    }

    fun clearNetdiskSearch() {
        _netdiskSearchKeyword.value = ""
        _netdiskSearchResults.value = emptyList()
    }

    /** 播放全部网盘搜索结果 */
    fun playAllNetdiskSearch() {
        val results = _netdiskSearchResults.value
        if (results.isEmpty()) {
            showError("搜索无结果")
            return
        }
        viewModelScope.launch {
            playQueue(results, 0)
        }
    }

    /**
     * 播放当前目录（含子目录）的全部音频。
     *
     * 优先走本地索引（毫秒级）；索引未覆盖/缺失时回退递归 BFS 扫描网盘。
     * @param onPlayAll 收集完成后回调（播放队列由调用方导航到 NowPlaying）
     */
    fun playAllNetdiskDir(dir: String, onPlayAll: (List<Song>) -> Unit) {
        viewModelScope.launch {
            val songs = collectAudioInDir(dir)
            if (songs.isEmpty()) {
                showError("目录下未找到音频文件")
            } else {
                onPlayAll(songs)
            }
        }
    }

    /** 收集目录（含子目录）内全部音频：优先索引，回退 API 递归扫描 */
    private suspend fun collectAudioInDir(dir: String): List<Song> {
        val base = dir.trimEnd('/')
        val index = baiduIndexCache.load()
        val indexed = index?.entries?.filter {
            it.category == BaiduNetdiskConfig.CATEGORY_AUDIO &&
                (it.path == base || it.path.startsWith("$base/"))
        }?.map { it.toSong() }
        if (indexed != null && indexed.isNotEmpty()) return indexed

        // 回退：BFS 递归扫描（索引未建或未覆盖该目录时）
        val songs = mutableListOf<Song>()
        val queue = ArrayDeque<String>()
        val visited = HashSet<String>()
        queue.addLast(base)
        visited.add(base)
        try {
            while (queue.isNotEmpty()) {
                val dirPath = queue.removeFirst()
                val result = baiduApi.listDir(dirPath)
                for (f in result.files) {
                    if (f.isDir) {
                        if (visited.add(f.path)) queue.addLast(f.path)
                    } else if (BaiduPanApi.isAudioFile(f.serverFilename, f.category)) {
                        songs.add(f.toSong())
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("NASMusic", "collectAudioInDir error", e)
        }
        return songs
    }

    // ---- 索引管理 ----

    fun triggerBaiduIndexScanIfNeeded() {
        val index = baiduIndexCache.load()
        val root = prefs.getBaiduMusicRootDirSync().ifBlank { "/音乐" }
        if (index == null || index.rootPath != root) {
            rebuildBaiduIndex()
        } else {
            _baiduIndexScanned.value = index.entries.size
            _baiduIndexLastSync.value = index.lastSyncAt
        }
    }

    fun rebuildBaiduIndex() {
        if (_baiduIndexScanning.value) return
        viewModelScope.launch {
            _baiduIndexScanning.value = true
            _baiduIndexScanned.value = 0
            val root = prefs.getBaiduMusicRootDirSync().ifBlank { "/音乐" }
            val callback = object : BaiduFileIndexCache.ProgressCallback {
                override fun onProgress(scanned: Int) { _baiduIndexScanned.value = scanned }
                override fun onComplete(total: Int) {
                    _baiduIndexScanned.value = total
                    _baiduIndexLastSync.value = System.currentTimeMillis()
                }
                override fun onFailed(message: String) {
                    showError("索引扫描中断: $message")
                }
            }
            try {
                val mvDir = prefs.getBaiduMvDirSync()
                baiduIndexCache.fullScan(root, baiduApi, mvDir, callback)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "rebuildBaiduIndex error", e)
            } finally {
                _baiduIndexScanning.value = false
            }
        }
    }

    // ---- 配置项 ----

    fun setBaiduMusicRootDir(dir: String) {
        prefs.setBaiduMusicRootDirSync(dir)
        _netdiskCurrentDir.value = dir
        // 根目录变更后旧索引失效，触发重建
        rebuildBaiduIndex()
    }

    fun setBaiduMvDir(dir: String?) {
        prefs.setBaiduMvDirSync(dir)
    }

    /** 加载索引中的歌曲（供 NetdiskScreen 首页展示已扫描曲库） */
    fun loadBaiduIndexedSongs(): List<Song> =
        baiduIndexCache.load()?.entries?.map { it.toSong() } ?: emptyList()

    // ===================== 电台（radio-browser）& Jamendo（CC 独立音乐） =====================

    // --- 电台 ---
    private val _radioStations = MutableStateFlow<UiState<List<RadioStation>>>(UiState.Success(emptyList()))
    val radioStations: StateFlow<UiState<List<RadioStation>>> = _radioStations.asStateFlow()
    private val _radioActiveTag = MutableStateFlow<String?>(null)
    val radioActiveTag: StateFlow<String?> = _radioActiveTag.asStateFlow()
    private val _radioActiveQuery = MutableStateFlow("")
    val radioActiveQuery: StateFlow<String> = _radioActiveQuery.asStateFlow()

    /**
     * 加载默认电台列表（中文电台热门）。幂等：当前无筛选且已有数据则跳过。
     */
    fun loadRadioDefault() {
        val tag = _radioActiveTag.value
        val query = _radioActiveQuery.value
        if (tag == null && query.isBlank() && _radioStations.value.dataOrNull()?.isNotEmpty() == true) return
        _radioActiveTag.value = null
        _radioActiveQuery.value = ""
        loadRadioStations(tag = null, query = null, countryCode = "CN")
    }

    /** 按标签加载电台 */
    fun loadRadioTag(tag: String) {
        _radioActiveTag.value = tag
        _radioActiveQuery.value = ""
        loadRadioStations(tag = tag, query = null, countryCode = null)
    }

    /** 搜索电台 */
    fun searchRadio(keyword: String) {
        _radioActiveTag.value = null
        _radioActiveQuery.value = keyword
        loadRadioStations(tag = null, query = keyword, countryCode = null)
    }

    private fun loadRadioStations(tag: String?, query: String?, countryCode: String?) {
        viewModelScope.launch {
            _radioStations.value = UiState.Loading
            try {
                val stations = nasMusicApp.radioBrowserClient.searchStations(
                    query = query, tag = tag, countryCode = countryCode, limit = 50
                )
                _radioStations.value = UiState.Success(stations)
            } catch (e: Exception) {
                AppLog.e("Radio", "loadRadioStations failed: ${e.message}", e)
                _radioStations.value = UiState.Error(message = "电台加载失败")
            }
        }
    }

    /** 播放电台（即点即播直播流，进入播放页显示"直播"态） */
    fun playRadioStation(station: RadioStation) {
        viewModelScope.launch {
            try {
                nasMusicApp.radioBrowserClient.reportClick(station)
            } catch (e: Exception) {
                // 上报失败不影响播放
            }
            playQueue(listOf(station.toSong()))
            navigateTo(Screen.NowPlaying)
        }
    }

    // --- Jamendo ---
    private val _jamendoState = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val jamendoState: StateFlow<UiState<List<Song>>> = _jamendoState.asStateFlow()
    private val _jamendoActiveTag = MutableStateFlow("")
    val jamendoActiveTag: StateFlow<String> = _jamendoActiveTag.asStateFlow()

    /** 是否已配置 Jamendo Client ID（未配置时 JamendoSubTab 显示引导卡） */
    val jamendoConfigured: Boolean
        get() = prefs.getJamendoClientIdSync().isNotBlank()

    /** 加载 Jamendo 热门榜（幂等：已有数据则不重复请求） */
    fun loadJamendoHot() {
        if (_jamendoActiveTag.value.isBlank() && _jamendoState.value.dataOrNull()?.isNotEmpty() == true) return
        _jamendoActiveTag.value = ""
        viewModelScope.launch {
            _jamendoState.value = UiState.Loading
            try {
                val songs = nasMusicApp.jamendoService.hotTracks(limit = 30)
                _jamendoState.value = UiState.Success(songs)
            } catch (e: Exception) {
                AppLog.e("Jamendo", "loadJamendoHot failed: ${e.message}", e)
                _jamendoState.value = UiState.Error(message = "独立音乐加载失败")
            }
        }
    }

    /** 按风格标签加载 Jamendo */
    fun loadJamendoTag(tag: String) {
        if (_jamendoActiveTag.value == tag && _jamendoState.value.dataOrNull()?.isNotEmpty() == true) return
        _jamendoActiveTag.value = tag
        viewModelScope.launch {
            _jamendoState.value = UiState.Loading
            try {
                val songs = nasMusicApp.jamendoService.tracksByTag(tag, limit = 30)
                _jamendoState.value = UiState.Success(songs)
            } catch (e: Exception) {
                AppLog.e("Jamendo", "loadJamendoTag failed: ${e.message}", e)
                _jamendoState.value = UiState.Error(message = "独立音乐加载失败")
            }
        }
    }

    /** 搜索 Jamendo 音乐 */
    fun searchJamendo(keyword: String) {
        _jamendoActiveTag.value = ""
        viewModelScope.launch {
            _jamendoState.value = UiState.Loading
            try {
                val songs = nasMusicApp.jamendoService.search(keyword, limit = 30)
                _jamendoState.value = UiState.Success(songs)
            } catch (e: Exception) {
                AppLog.e("Jamendo", "searchJamendo failed: ${e.message}", e)
                _jamendoState.value = UiState.Error(message = "独立音乐加载失败")
            }
        }
    }

    /** 更新 Jamendo Client ID 并动态注册/注销服务 */
    fun updateJamendoClientId(id: String) {
        viewModelScope.launch {
            prefs.setJamendoClientId(id)
            if (id.isNotBlank()) {
                nasMusicApp.networkMusicManager.registerService(nasMusicApp.jamendoService)
            } else {
                nasMusicApp.networkMusicManager.unregisterService("jamendo")
            }
            _jamendoState.value = UiState.Success(emptyList())
            _jamendoActiveTag.value = ""
        }
    }
}

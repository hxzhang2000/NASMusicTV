package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import com.google.gson.Gson
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.FilterMode
import com.nasmusic.tv.backend.SearchType
import com.nasmusic.tv.backend.SearchAggregator
import com.nasmusic.tv.backend.download.AutoDownloadController
import com.nasmusic.tv.backend.download.DownloadStats
import com.nasmusic.tv.backend.download.SongDownloadManager
import com.nasmusic.tv.backend.download.model.DownloadState
import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.backend.download.model.isDownloadableSong
import com.nasmusic.tv.backend.download.model.dedupeKey
import com.nasmusic.tv.backend.export.ExportState
import com.nasmusic.tv.backend.local.EmbeddedCoverExtractor
import com.nasmusic.tv.backend.local.MusicMerger
import com.nasmusic.tv.backend.local.StorageMonitor
import com.nasmusic.tv.backend.network.mv.MvSearchManager
import com.nasmusic.tv.backend.network.baidu.BaiduFileIndexCache
import com.nasmusic.tv.backend.network.baidu.BaiduOAuthClient
import com.nasmusic.tv.backend.network.baidu.BaiduPanApi
import com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.StorageDevice
import com.nasmusic.tv.data.model.BackupMessage
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.BaiduFileIndex
import com.nasmusic.tv.data.model.VersionInfo
import com.nasmusic.tv.data.model.BrowseDimension
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsAvailability
import com.nasmusic.tv.data.model.LyricsHighlightMode
import com.nasmusic.tv.data.model.LyricsSource
import com.nasmusic.tv.lyrics.LocalLyricsProvider
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
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.MvInfo
import com.nasmusic.tv.data.model.MvCandidate
import com.nasmusic.tv.data.model.MvSearchResult
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
import com.nasmusic.tv.lyrics.LrcParser
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.player.ModelDownloadManager
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val searchHistory = prefs.history.searchHistory
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
        // F-3：改 provider（读 @Volatile 镜像）——构造期零 IO，设置页改歌词源即时生效
        kugouBaseUrlProvider = { prefs.lyrics.getLyricsKugouBaseUrlSync() },
        neteaseBaseUrlProvider = { prefs.lyrics.getLyricsNeteaseBaseUrlSync() }
    )

    // --- 手机遥控服务器 ---
    private val remoteControlServer = RemoteControlServer(app)
    private val _remoteControlUrl = MutableStateFlow<String?>(null)
    val remoteControlUrl: StateFlow<String?> = _remoteControlUrl.asStateFlow()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 按需启动遥控服务器（进入 K 歌/MTV 模式时调用，避免常驻浪费资源） */
    fun ensureRemoteControlStarted() {
        if (_remoteControlUrl.value == null) {
            _remoteControlUrl.value = remoteControlServer.start(this)
        }
    }

    // --- 天气电台（R-1：已拆分至 WeatherRadioViewModel，此处为兼容转发）---
    val weatherRadioVM = WeatherRadioViewModel(app, playerManager, nasMusicApp.networkMusicManager)

    fun playWeatherRadioAll() {
        val songs = weatherRadioVM.weatherRadioQueue.value?.songs ?: return
        if (songs.isEmpty()) return
        // 网络歌曲需要解析 streamUrl，但这里统一走 playQueue 的逻辑
        playQueue(songs, 0)
        // 导航到播放页
        navVM.navigateTo(Screen.NowPlaying)
    }

    // =====================================================================
    // R-1 第三步拆分：Server / Search / NetworkMusic 已迁至子 ViewModel。
    // =====================================================================
    val serverVM = ServerViewModel(app, backendRegistry)
    val searchVM = SearchViewModel(app, backendRegistry, nasMusicApp.searchAggregator)
    val netVM = NetworkMusicViewModel(app)

    init {
        // ---- ServerViewModel 接线 ----
        serverVM.onConnected = {
            loadLibrary()
            // 更新恢复队列中 NAS 歌曲的 streamUrl
            updateRestoredQueueStreamUrls()
            // 导航到首页
            navVM.navigateTo(Screen.Home)
            loadHomeDashboard()
        }
        serverVM.onDisconnected = {
            _albums.value = UiState.Loading
            _songs.value = UiState.Loading
            _songsPaging.value = SongsPagingState()
            _artists.value = UiState.Success(emptyList())
            _years.value = UiState.Success(emptyList())
            _recentSongs.value = UiState.Success(emptyList())
            searchVM.clearSearch()
            _genres.value = UiState.Success(emptyList())
            _favoriteSongs.value = UiState.Success(emptyList())
            _playlists.value = UiState.Success(emptyList())
        }
        serverVM.checkSavedConfigOnStart()
        serverVM.refreshApiVersionsAsync()

        // ---- SearchViewModel 接线 ----
        searchVM.libraryActiveTabProvider = { _libraryActiveTab.value }
        searchVM.librarySearchKeywordProvider = { _librarySearchKeyword.value }
        searchVM.nasLocalSongsProvider = { _songsPaging.value.songs }
        searchVM.localDeviceSongsProvider = { _localSongs.value }
        searchVM.onAddToQueue = { playerManager.addToQueue(it) }
        searchVM.onPlayBatch = { songs, startIndex -> playNetworkBatch(songs, startIndex) }
        searchVM.showMessage = { showError(it) }
        searchVM.showMessageFor = { added, skipped, _ ->
            serverVM.postConnectMessage(
                getApplication<Application>().getString(R.string.added_to_queue_with_skipped, added, skipped)
            )
        }

        // ---- NetworkMusicViewModel 接线 ----
        netVM.onPlayQueue = { songs, startIndex -> playQueue(songs, startIndex) }
        netVM.showMessage = { showError(it) }
        netVM.onMergedDataInvalidated = { updateMergedData() }
        // 启动期恢复百度索引状态 + 触发合并
        netVM.restoreBaiduIndexOnStart { _ -> updateMergedData() }
    }

    // --- 导航状态（R-1 第四步：已迁至 NavigationViewModel，此处转发）---
    val navVM = NavigationViewModel(app)

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
    private val pageSize = 500
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

    // --- 本地音乐（USB / 设备存储，独立数据源）---
    private val _localSongs = MutableStateFlow<List<Song>>(emptyList())
    val localSongs: StateFlow<List<Song>> = _localSongs.asStateFlow()

    /** 合并后的专辑（NAS + 本地，按 albumName 去重） */
    private val _mergedAlbums = MutableStateFlow<List<Album>>(emptyList())
    val mergedAlbums: StateFlow<List<Album>> = _mergedAlbums.asStateFlow()

    /** 合并后的艺术家（NAS + 本地，按 artistName 去重） */
    private val _mergedArtists = MutableStateFlow<List<Artist>>(emptyList())
    val mergedArtists: StateFlow<List<Artist>> = _mergedArtists.asStateFlow()

    // --- 按需加载：年份列表（独立 API）---
    private val _years = MutableStateFlow<UiState<List<Int>>>(UiState.Success(emptyList()))
    val years: StateFlow<UiState<List<Int>>> = _years.asStateFlow()

    // --- 按需加载：最近播放歌曲（按需批量查询）---
    private val _recentSongs = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val recentSongs: StateFlow<UiState<List<Song>>> = _recentSongs.asStateFlow()

    // --- 搜索域（R-1：已迁至 SearchViewModel，仅保留跨导航暂存的状态镜像）---

    /** 浏览换一批已展示过的歌曲（歌手, 歌名）集合：跨批次去重，筛选条件变化时重置 */
    private val browseSeenKeys = mutableSetOf<Pair<String, String>>()

    /** 换一批单次点击最多尝试的后缀数量（避免搜索结果长期重复时空转） */
    private val maxShuffleAttemptsPerClick = 6

    /** 换一批时新歌数量达到该值才展示（否则继续尝试下一后缀） */
    private val minNewResultsForShuffle = 5

    /**
     * 统一的「换一批」核心逻辑（多维度浏览专用；网络搜索/天气电台版已随域迁出）。
     *
     * 反复调用 [produce] 生成候选（最多 [maxShuffleAttemptsPerClick] 次），用 [songsOf]
     * 取出其中的歌曲列表，过滤掉 [seenKeys] 中已展示过的歌曲，返回新歌最多的候选与
     * 新歌列表；新歌数量达到 [minNewResultsForShuffle] 即提前停止。
     *
     * 若所有候选都没有新歌（已见集合饱和），清空 [seenKeys] 后重新生成一次候选并返回
     * （从头再来，保证每次点击都有内容）。返回前把本次真正展示的新歌记入 [seenKeys]
     * （未展示的候选歌曲保留，之后批次仍可出现）。
     *
     * 调用方负责在「上下文变化」（新筛选）时清空对应的 [seenKeys]。
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
        // 修复（M-9）：硬上限防长期挂机场景集合无限增长（饱和 clear 之外的双保险）
        if (seenKeys.size >= 4000) seenKeys.clear()
        result.second.forEach { seenKeys.add(it.artist.trim() to it.title.trim()) }
        return result
    }

    // --- 统一收藏（R-1：收藏数据迁至 NetworkMusicViewModel，此处派生只读视图）---
    // 供 UI 使用：转换为 Song 对象列表（根据 source 标记 isLocalSong / isNetworkSong 字段）
    val networkFavoriteSongs: StateFlow<List<Song>> = netVM.networkFavorites.map { favorites ->
        favorites.map { item ->
            val isLocal = item.networkSource == "local"
            Song(
                id = item.songId,
                title = item.title,
                artist = item.artist,
                album = item.album,
                coverUrl = item.coverUrl,
                isNetworkSong = !isLocal,
                networkSource = item.networkSource.takeIf { !isLocal },
                networkId = item.networkId,
                isLocalSong = isLocal
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    // 网络收藏 ID 集合（用于快速判断是否已收藏）
    val networkFavoriteIds: StateFlow<Set<String>> = netVM.networkFavorites.map { favorites ->
        favorites.map { it.songId }.toSet()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // --- 本地歌单（「我的」Tab，DataStore 持久化，可混合 NAS/网络歌曲）---
    // （R-1：状态与操作已迁至 PlaylistViewModel，见上方转发区）

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

    // --- 曲库页滚动位置记忆（切换 Tab 后恢复上次滚动位置） ---
    private val _albumScrollIndex = MutableStateFlow(0)
    val albumScrollIndex: StateFlow<Int> = _albumScrollIndex.asStateFlow()

    private val _albumScrollOffset = MutableStateFlow(0)
    val albumScrollOffset: StateFlow<Int> = _albumScrollOffset.asStateFlow()

    private val _artistScrollIndex = MutableStateFlow(0)
    val artistScrollIndex: StateFlow<Int> = _artistScrollIndex.asStateFlow()

    private val _artistScrollOffset = MutableStateFlow(0)
    val artistScrollOffset: StateFlow<Int> = _artistScrollOffset.asStateFlow()

    fun saveAlbumScrollPosition(index: Int, offset: Int) {
        _albumScrollIndex.value = index
        _albumScrollOffset.value = offset
    }

    fun saveArtistScrollPosition(index: Int, offset: Int) {
        _artistScrollIndex.value = index
        _artistScrollOffset.value = offset
    }

    // --- 曲库页搜索关键词（跨导航记忆，切换页面后保留搜索框内容与结果） ---
    private val _librarySearchKeyword = MutableStateFlow("")
    val librarySearchKeyword: StateFlow<String> = _librarySearchKeyword.asStateFlow()

    fun setLibrarySearchKeyword(keyword: String) {
        if (_librarySearchKeyword.value != keyword) {
            _librarySearchKeyword.value = keyword
        }
    }

    fun clearLibrarySearchKeyword() {
        if (_librarySearchKeyword.value.isNotBlank()) {
            _librarySearchKeyword.value = ""
        }
    }

    // （R-1：搜索来源点亮状态已迁至 SearchViewModel，转发见下方搜索转发区）

    // --- 网络音乐平台来源 ---
    private val _currentMusicSource = MutableStateFlow(MusicSource.NETEASE)
    val currentMusicSource: StateFlow<MusicSource> = _currentMusicSource.asStateFlow()

    /**
     * 初始化音乐来源（从持久化存储读取）
     */
    private fun initMusicSource() {
        val savedKey = prefs.network.getMusicSourceSync()
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
            prefs.network.setMusicSource(source.apiKey)
            // 有搜索关键词时自动重新搜索（R-1：经 SearchViewModel）
            val kw = searchVM.networkSearchKeyword.value
            if (kw.isNotBlank()) {
                searchVM.searchNetworkSongs(kw)
            }
        }
    }

    // ================= 天气电台（R-1：逻辑已迁至 WeatherRadioViewModel，以上为转发） =================

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
    /**
     * 统一收藏 ID 集合（NAS + 网络/本地），UI 只读这一个，无需自行拼装。
     * - NAS 收藏：由 adapter.getFavorites() 填充 _favoriteIds（服务端存储）
     * - 网络/本地收藏：由 DataStore NetworkFavoriteItem 填充 networkFavoriteIds（本机存储）
     *
     * _favoriteIds 仍保留私有，供 toggleNetworkFavorite 的 NAS 分支读取"当前是否已收藏"。
     */
    val favoriteIds: StateFlow<Set<String>> = combine(_favoriteIds, networkFavoriteIds) { nas, net ->
        nas + net
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val _favoriteSongs = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val favoriteSongs: StateFlow<UiState<List<Song>>> = _favoriteSongs.asStateFlow()

    // --- A-3 流派（B-12: UiState）---
    private val _genres = MutableStateFlow<UiState<List<Genre>>>(UiState.Success(emptyList()))
    val genres: StateFlow<UiState<List<Genre>>> = _genres.asStateFlow()

    // --- D-2 网络状态 ---
    private val _isNetworkAvailable = MutableStateFlow(true)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    // --- 加载状态（R-1：连接加载态归 ServerViewModel，本地镜像合并供 AppRoot 单点订阅）---
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        // 合并 ServerViewModel 的加载态到本地通道
        viewModelScope.launch {
            serverVM.isLoading.collect { _isLoading.value = it }
        }
    }

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

    // --- 播放统计（R-1 第四步：已迁至 PlayHistoryViewModel，此处转发）---
    val playHistoryVM = PlayHistoryViewModel(app)

    fun recordPlayEvent(song: Song, durationPlayedMs: Long) =
        playHistoryVM.recordPlayEvent(song, durationPlayedMs)


    /**
     * 异步获取当前歌曲的技术信息
     */
    fun loadSongTechnicalInfo() {
        viewModelScope.launch {
            val song = playerVM.currentSong.value ?: return@launch
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

    // --- B-13: 播放器状态（R-1 第四步：已迁至 PlayerViewModel，此处转发）---
    val playerVM = PlayerViewModel(app, playerManager)
    /** 实时频谱数据（96 柱幅值），来自 SpectrumAnalyzer / Visualizer FFT */

    // B-13: playMode 由 PlayerViewModel 拥有（UI/设置状态，不归 PlayerManager）

    // --- 连接状态（R-1：已迁至 ServerViewModel，保留本地 connectMessage 通道供多域共用）---
    private val _isLibraryLoading = MutableStateFlow(false)
    val isLibraryLoading: StateFlow<Boolean> = _isLibraryLoading.asStateFlow()

    // --- 连接结果提示消息（显示几秒后自动清除；主通道归 ServerViewModel，此处镜像合并）---
    private val _connectMessage = MutableStateFlow<String?>(null)
    val connectMessage: StateFlow<String?> = _connectMessage.asStateFlow()

    init {
        // 合并 ServerViewModel 的连接消息到本地通道（AppRoot 只订阅一处）
        viewModelScope.launch {
            serverVM.connectMessage.collect { _connectMessage.value = it }
        }
    }

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
            // 初始化播放模式（B-13: 从预设置恢复）——R-1 后由 PlayerViewModel 持有
            val settings = prefs.appSettings.first()
            playerVM.initPlayModeFromSettings(settings.defaultPlayMode)

            // 初始化升降调/变速（从 AppPreferences 恢复）——R-1 后委托 VocalSeparationViewModel
            val savedPitch = prefs.player.pitchSemitones.first()
            val savedSpeed = prefs.player.playbackSpeed.first()
            playerManager.setPitch(savedPitch)
            playerManager.setSpeed(savedSpeed.toFloat())
            AppLog.d("MainViewModel", "init: pitch=$savedPitch, speed=$savedSpeed")

            // 等待配置加载完成后判断是否显示连接提示
            val config = prefs.serverConfig.first()
            if (config.baseUrl.isNotBlank()) {
                // 有已保存的服务器配置，询问用户是否自动连接（R-1：由 ServerViewModel 处理，
                // 但保留此处删除以避免双重触发——init 接线区已调用 checkSavedConfigOnStart()）
            }
            // 无配置时不强制跳转，保持首页（用户可自行去 设置 → 服务器 配置）
        }

        // 后台异步：API 版本号聚合（网络请求，不阻塞启动）
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                refreshApiVersions()
            }
        }

        // 百度网盘启动恢复（R-1：已迁至 NetworkMusicViewModel.restoreBaiduIndexOnStart，
        // 接线区已调用；API 错误回调在此注册，指向子 VM 状态）
        nasMusicApp.baiduPanApi.onApiError = { errno, desc ->
            if (errno == -6) {
                AppLog.w("BaiduAuth", "onApiError: errno=-6 (auth failed), setting state=Failed")
                netVM.onBaiduAuthFailed(desc)
            } else {
                AppLog.d("BaiduAuth", "onApiError: errno=$errno ($desc), not auth-related, ignored")
            }
        }

        // 监听 currentSong 变化，自动切歌时重新加载歌词，并记录播放历史
        viewModelScope.launch {
            playerVM.currentSong.collect { song ->
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
                    // 自动下载判定链（总开关/可下载源/已下载/配额/空间）——切歌即触发，不等播放进度
                    downloadVM.onSongChanged(song)
                    // 记录当前歌词来源（在 loadLyricsForCurrentSong 清除 _currentLyrics 之前）
                    lastRecordedLyricsSource = _currentLyrics.value?.source
                    loadLyricsForCurrentSong()
                    // MTV 连播静默推进索引时跳过搜索（预搜结果已直接设为 Ready）
                    if (!mvVM.shouldSkipNextSearch()) {
                        mvVM.triggerMvSearch(song)
                    }
                    // 记录当前歌的开始
                    lastRecordedSong = song
                    lastRecordedSongId = song.id
                    // 记录到最近播放列表（自动切歌时也需要更新）
                    recordPlay(song)
                } else {
                    // 无当前歌曲（清空队列等）→ 重置 MV 状态
                    mvVM.resetIdle()
                }
            }
        }

        // 每 30 秒更新一次播放位置（用于切歌时记录精确的播放时长）
        viewModelScope.launch {
            while (true) {
                delay(30000)
                lastRecordedPositionMs = playerVM.progress.value
            }
        }

        // 监听下载完成：songDownloadStates 中 Completed 数量增加时即时刷新本地曲库
        // （SongDownloadManager.onCompleted 已把 ScannedSong 插入 local_songs，这里只需 reload）
        // P1-19: distinctUntilChanged 避免相同 count 值触发无谓的 reload（Downloading 高频进度更新时）
        viewModelScope.launch {
            var lastCompletedCount = 0
            downloadVM.songDownloadStates
                .map { states -> states.values.count { it is DownloadState.Completed } }
                .distinctUntilChanged()
                .collect { completedCount ->
                    if (completedCount > lastCompletedCount) {
                        lastCompletedCount = completedCount
                        _localSongs.value = nasMusicApp.localMusicRepository.loadFromCache()
                        updateMergedData()
                        // 刷新下载统计
                        downloadVM.refreshDownloadStats()
                    }
                }
        }

        // 监听网络收藏变化（R-1：已由 NetworkMusicViewModel 内部 collect，MainViewModel 派生视图直接读子 VM）

        // 监听本地歌单变化（已由 PlaylistViewModel 内部 collect）

        // 恢复上次播放队列（仅恢复 UI 状态，不自动播放）— 协程异步读取 DataStore，避免阻塞主线程
        viewModelScope.launch {
            restoreLastQueue()
        }

        // 监听队列变化，自动持久化到 DataStore
        viewModelScope.launch {
            combine(playerVM.queue, playerVM.currentIndex) { songs, index ->
                songs to index
            }.collect { (songs, index) ->
                if (songs.isNotEmpty()) {
                    prefs.queue.saveLastQueue(songs, index)
                }
            }
        }

        // ExoPlayer 自动过渡到 streamUrl 为空的歌曲时（如恢复队列中的网络歌曲），
        // 解析 streamUrl 后重新播放
        playerManager.onNeedResolveStreamUrl = { index ->
            playerVM.resolveAndPlayByIndex(index)
        }

        // 播放模式变化同步给 MvSearchViewModel（playMode 是方法参数语义）
        viewModelScope.launch {
            playerVM.playMode.collect { mvVM.currentPlayMode = it }
        }

        // 初始化网络音乐平台来源（从持久化存储读取）
        initMusicSource()

        // 当曲库数据加载完成时自动刷新首页仪表盘
        viewModelScope.launch {
            combine(_albums, _songs) { a, s ->
                a.isSuccess && s.isSuccess
            }.collect { loaded ->
                if (loaded && navVM.currentScreen.value == Screen.Home) {
                    loadHomeDashboard()
                }
            }
        }

        // 加载播放记录
        playHistoryVM.loadPlayRecords()

        // 本地音乐初始化：先加载缓存，再后台增量扫描，监听 USB 插拔
        viewModelScope.launch {
            try {
                // 1. 立即从缓存加载（毫秒级）
                _localSongs.value = nasMusicApp.localMusicRepository.loadFromCache()
                updateMergedData()
                AppLog.d("MainViewModel", "local music cache loaded: ${_localSongs.value.size} songs")
            } catch (e: Exception) {
                AppLog.e("MainViewModel", "load local music cache failed: ${e.message}", e)
            }

            // 2. 后台增量扫描（不阻塞 UI）
            launch(Dispatchers.IO) {
                try {
                    val result = nasMusicApp.localMusicRepository.incrementalScan()
                    if (result.hasChanges()) {
                        _localSongs.value = nasMusicApp.localMusicRepository.loadFromCache()
                        updateMergedData()
                        AppLog.i("MainViewModel", "local scan: +${result.newSongs.size} new, -${result.deletedPaths.size} deleted")
                    }
                } catch (e: Exception) {
                    AppLog.e("MainViewModel", "local incremental scan failed: ${e.message}", e)
                }
            }

            // 3. 监听 USB 设备插拔
            nasMusicApp.storageMonitor.onDeviceMounted
                .collect { device ->
                    try {
                        val result = nasMusicApp.localMusicRepository.scanUsbDevice(device.path)
                        if (result.hasChanges()) {
                            _localSongs.value = nasMusicApp.localMusicRepository.loadFromCache()
                            updateMergedData()
                            AppLog.i("MainViewModel", "USB mounted scan: ${result.newSongs.size} songs")
                        }
                    } catch (e: Exception) {
                        AppLog.e("MainViewModel", "USB mount scan failed: ${e.message}", e)
                    }
                }
        }
        // 监听 NAS 专辑/艺术家加载完成，重新合并（本地+NAS 去重）
        viewModelScope.launch {
            _albums.collect {
                updateMergedData()
            }
        }
        viewModelScope.launch {
            _artists.collect {
                updateMergedData()
            }
        }
    }

    // --- 导航 ---

    /**
     * 恢复队列与 streamUrl 更新已迁至 PlayerViewModel（restoreLastQueue / updateRestoredQueueStreamUrls）。
     */
    private suspend fun restoreLastQueue() = playerVM.restoreLastQueue()
    private fun updateRestoredQueueStreamUrls() = playerVM.updateRestoredQueueStreamUrls()

    // --- 连接 ---
    /**
     * 连接域已迁至 ServerViewModel（R-1 第三步）。以下为兼容转发。
     */

    suspend fun connectToServer(config: ServerConfig): Boolean = serverVM.connectToServer(config)
    suspend fun refreshApiVersions() = serverVM.refreshApiVersions()

    /**
     * 增量构建艺术家映射（避免每次分页都全量重建）
     * @param newSongs 新增的歌曲列表
     */
    private fun buildArtistMapsIncremental(newSongs: List<Song>) {
        if (newSongs.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val songMap = _songArtistMap.value.toMutableMap()
            val artistMap = _artistSongsMap.value.mapValues { it.value.toMutableList() }.toMutableMap()

            for (song in newSongs) {
                val artists = ArtistSplitter.split(song.artist)
                songMap[song.id] = artists
                for (name in artists) {
                    // 归一化键：同一个人的不同写法（全角/空白/大小写）合并到一块
                    val key = ArtistSplitter.normalizeKey(name)
                    if (key.isBlank()) continue
                    artistMap.getOrPut(key) { mutableListOf() }.add(song)
                }
            }
            _songArtistMap.value = songMap
            _artistSongsMap.value = artistMap
        }
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
                message = getApplication<Application>().getString(R.string.load_favorites_error, e.message?.take(50))
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
                message = getApplication<Application>().getString(R.string.load_genres_error, e.message?.take(50))
            )
        }
    }

    // --- 曲库（B-12: UiState）---
    private fun loadLibrary() {
        _isLibraryLoading.value = true
        _albums.value = UiState.Loading
        _songs.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter()
            if (adapter == null) {
                // 无 NAS 连接时，仍允许本地 + 百度数据展示
                _albums.value = UiState.Success(emptyList())
                _songs.value = UiState.Success(emptyList())
                _isLibraryLoading.value = false
                updateMergedData()
                return@launch
            }

            // 专辑、流派、收藏并行异步加载——不互相阻塞，也不阻塞首页就绪状态
            // 大曲库（3万+首）下 getAlbums 可能需要数十秒，不应阻塞 _isLibraryLoading
            launch {
                try {
                    AppLog.d("NASMusic", "loadLibrary: loading albums...")
                    val loadedAlbums = adapter.getAlbums()
                    _albums.value = UiState.Success(loadedAlbums)
                    AppLog.d("NASMusic", "loadLibrary: ${loadedAlbums.size} albums loaded")
                    loadHomeDashboard()
                } catch (e: Exception) {
                    AppLog.e("NASMusic", "loadLibrary albums failed", e)
                    _albums.value = UiState.Error(
                        message = getApplication<Application>().getString(R.string.load_albums_error, e.message?.take(50)),
                        retry = { loadLibrary() }
                    )
                }
            }

            launch { loadGenres(adapter) }
            launch { loadFavorites(adapter) }

            // 艺术家列表异步加载（不阻塞首页就绪）
            launch { loadArtists() }

            // 后台逐步加载全量歌曲：每加载一页立即显示，继续加载直到全部完成
            _songsPaging.value = SongsPagingState()
            loadAllSongsBackground()

            // 随心听随机歌曲异步加载（不阻塞首页就绪状态）
            launch { loadRandomSongs(adapter) }

            // 首页立即就绪——各数据源异步完成后自动更新 UI
            _isLibraryLoading.value = false
            AppLog.d("NASMusic", "loadLibrary: dispatched all async loads, home ready")
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
                showError(getApplication<Application>().getString(R.string.load_songs_error, e.message?.take(50)))
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
        navVM.navigateTo(Screen.NowPlaying)
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
                _artists.value = UiState.Error(getApplication<Application>().getString(R.string.backend_not_connected))
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
                // 归一化去重：NFKC + trim + 折叠空白 + 小写，
                // 避免 "古天乐" 与 "古天乐 " 这类肉眼同名、字符串不同的条目变成两块
                val mergedMap = linkedMapOf<String, Artist>()
                for (item in splitArtists) {
                    val key = ArtistSplitter.normalizeKey(item.name)
                    if (key.isBlank()) continue
                    val existing = mergedMap[key]
                    mergedMap[key] = if (existing == null) item else existing.copy(
                        songCount = maxOf(existing.songCount, item.songCount),
                        albumCount = existing.albumCount + item.albumCount,
                        coverUrl = existing.coverUrl ?: item.coverUrl
                    )
                }
                val merged = mergedMap.values.toList()
                _artists.value = UiState.Success(merged)
                AppLog.d("NASMusic", "loadArtists: ${artistsList.size} raw → ${merged.size} after splitting")
                // 触发合并（updateMergedData 末尾会解析 NAS + 百度 + 本地缺失的艺术家封面）
                updateMergedData()
            } catch (e: Exception) {
                AppLog.e("NASMusic", "loadArtists failed", e)
                _artists.value = UiState.Error(
                    message = getApplication<Application>().getString(R.string.load_favorites_error, e.message?.take(50)),
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
                _years.value = UiState.Error(getApplication<Application>().getString(R.string.backend_not_connected))
                return@launch
            }
            try {
                val yearsList = adapter.getYears()
                _years.value = UiState.Success(yearsList)
                AppLog.d("NASMusic", "loadYears: ${yearsList.size} years loaded")
            } catch (e: Exception) {
                AppLog.e("NASMusic", "loadYears failed", e)
                _years.value = UiState.Error(
                    message = getApplication<Application>().getString(R.string.load_year_songs_error, e.message?.take(50)),
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
            try {
                // 读取持久化的最近播放完整歌曲对象（含网络歌曲，不依赖 NAS 连接）
                val recent = prefs.history.getRecentSongObjects()
                _recentSongs.value = UiState.Success(recent)
                AppLog.d("NASMusic", "loadRecentSongs: ${recent.size} recent songs loaded")
            } catch (e: Exception) {
                AppLog.e("NASMusic", "loadRecentSongs failed", e)
                _recentSongs.value = UiState.Error(
                    message = getApplication<Application>().getString(R.string.load_favorites_error, e.message?.take(50)),
                    retry = { loadRecentSongs() }
                )
            }
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
     * 确保浏览结果已加载（幂等）：已有成功结果或正在加载中则跳过。
     *
     * 供"重新进入发现页面"触发：主tab/子tab切换回来时若结果仍在
     * （跨导航暂存），不做无谓的重新搜索——与搜索页的 lastSearchedKeyword 缓存
     * 逻辑一致，缓存判断放在 ViewModel 层而非依赖 UI collectAsState 初始值。
     */
    fun ensureBrowseLoaded() {
        val current = _browseResults.value
        // 已有成功结果（暂存内容）→ 不重搜
        if (current is UiState.Success && current.data.isNotEmpty()) return
        // 正在搜索中 → 不重复触发
        if (current is UiState.Loading) return
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
                // 构建组合关键词（与"换一批"逻辑一致），但跨源聚合搜索
                // 构建组合关键词（展开词，给网络/NAS/Jamendo 用，支持换一批多样性）
                fun buildCombo(): String {
                    val combo = mutableListOf<String>()
                    for (i in dimensions.indices) {
                        val opt = dimensions[i].options.getOrNull(selections.getOrNull(i) ?: 0)
                            ?: continue
                        if (opt.label == "所有") continue
                        if (opt.keywords.isEmpty()) continue
                        combo.add(opt.keywords.random())
                    }
                    return combo.filter { it.isNotBlank() }.joinToString(" ").trim()
                }

                // 构建维度标签组合（给百度用，目录+API 效果好）
                fun buildLabelCombo(): String {
                    val labels = mutableListOf<String>()
                    for (i in dimensions.indices) {
                        val opt = dimensions[i].options.getOrNull(selections.getOrNull(i) ?: 0)
                            ?: continue
                        if (opt.label == "所有") continue
                        labels.add(opt.label)
                    }
                    return labels.filter { it.isNotBlank() }.joinToString(" ").trim()
                }

                // 构造一次聚合器（produce 内多次调用复用同一实例）
                val aggregator = nasMusicApp.searchAggregator
                val enabledSources = searchVM.enabledSearchSources.value
                val baiduLabelCombo = buildLabelCombo()

                val (_, shown) = pickBestFreshBatch(
                    seenKeys = browseSeenKeys,
                    produce = {
                        // 跨源聚合器：按当前点亮来源并行搜索（NAS/网络/百度/Jamendo）
                        // 网络/NAS/Jamendo 用展开词（支持换一批多样性）
                        // 百度用维度标签（目录+API 效果好）
                        // filterMode=NONE：发现页宽泛，各源返回什么就展示
                        val keyword = buildCombo()
                        if (keyword.isBlank()) return@pickBestFreshBatch emptyList()
                        aggregator.search(
                            keyword,
                            sources = enabledSources,
                            directoryMode = true,
                            baiduKeyword = baiduLabelCombo,
                            filterMode = FilterMode.NONE
                        ).allResults.map { it.song }
                    },
                    songsOf = { it }
                )
                _browseResults.value = UiState.Success(shown)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "refreshBrowseSongs failed: ${e.message}", e)
                _browseResults.value = UiState.Error(
                    message = getApplication<Application>().getString(R.string.browse_search_failed, e.message?.take(50))
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
        val results = searchVM.networkSearchResults.value.dataOrNull() ?: return
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

    // ================= N-1 保留的胶水转发（非纯透传，含跨域参数拼接） =================
    fun connectToSavedServer(silent: Boolean = false) = serverVM.connectToSavedServer(silent)
    fun playNetworkSong(song: Song) = netVM.playNetworkSong(song, onPlaySong = { playable -> playerVM.playSong(playable) })
    fun toggleNetworkFavorite(song: Song) = netVM.toggleNetworkFavorite(
        song,
        isNasFavorite = song.id in _favoriteIds.value,
        onNasToggle = { nasSong, isFav -> toggleNasFavorite(nasSong, isFav) }
    )
    fun searchSongsOnServer(query: String, force: Boolean = false) = searchVM.searchSongsOnServer(query, force)
    fun addAllSearchResultsToQueue() = searchVM.addAllSearchResultsToQueue(
        existingQueueKeysProvider = {
            playerManager.queue.value.map { it.artist.trim() to it.title.trim() }.toSet()
        }
    )
    fun deleteModel() = downloadVM.deleteModel(onModeFallback = { vocalVM.onModelDeleted() })
    fun onMvPlaybackError() = mvVM.onMvPlaybackError(playerVM.currentSong.value)
    fun onMvPlaybackEnded() = mvVM.onMvPlaybackEnded(playerVM.currentSong.value, playerVM.playMode.value)
    fun onMvPrevious() = mvVM.onMvPrevious(playerVM.playMode.value)
    fun onMvNext() = mvVM.onMvNext(playerVM.playMode.value)
    fun onSwitchOrResearch() = mvVM.onSwitchOrResearch(playerVM.currentSong.value)
    fun onSearchBilibili() = mvVM.onSearchBilibili(playerVM.currentSong.value)

    /** NAS 收藏分支（留在 MainViewModel：依赖本类 _favoriteIds/_favoriteSongs 状态） */
    private fun toggleNasFavorite(song: Song, isCurrentlyFavorite: Boolean) {
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: return@launch
            try {
                val success = adapter.toggleFavorite(song.id, isCurrentlyFavorite)
                if (success) {
                    val newIds = _favoriteIds.value.toMutableSet()
                    if (isCurrentlyFavorite) {
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
showError(getApplication<Application>().getString(R.string.toggle_favorite_error, e.message?.take(50)))
            }
        }
    }

    /**
     * 判断网络歌曲是否已收藏（同步，用于 UI 快速判断）
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

    fun refreshLibrary() {
        _albums.value = UiState.Loading
        _songs.value = UiState.Loading
        _songsPaging.value = SongsPagingState()
        _artists.value = UiState.Success(emptyList())
        _years.value = UiState.Success(emptyList())
        _recentSongs.value = UiState.Success(emptyList())
        searchVM.clearSearch()
        loadLibrary()
    }

    // =====================================================================
    // R-1 第三步转发区：Search / NetworkMusic（百度网盘）
    // =====================================================================

    // ---- SearchViewModel 转发 ----

    // ---- NetworkMusicViewModel（百度网盘）转发 ----
    /** 百度索引缓存（曲库合并/详情取数仍由 MainViewModel 使用） */
    private val baiduIndexCache: BaiduFileIndexCache get() = nasMusicApp.baiduFileIndexCache

    val baiduDeviceCode get() = netVM.baiduDeviceCode
    val netdiskCurrentDir get() = netVM.netdiskCurrentDir
    val netdiskDirFiles get() = netVM.netdiskDirFiles
    val netdiskIsLoading get() = netVM.netdiskIsLoading
    val netdiskSearchResults get() = netVM.netdiskSearchResults
    val netdiskSearchKeyword get() = netVM.netdiskSearchKeyword
    val baiduIndexScanned get() = netVM.baiduIndexScanned
    val baiduIndexScanning get() = netVM.baiduIndexScanning
    val baiduIndexLastSync get() = netVM.baiduIndexLastSync
    val baiduApicExtracting get() = netVM.baiduApicExtracting
    val baiduApicExtracted get() = netVM.baiduApicExtracted
    val baiduApicTotal get() = netVM.baiduApicTotal

    suspend fun listBaiduDirs(path: String) = netVM.listBaiduDirs(path)
    fun playAllNetdiskDir(dir: String, onPlayAll: (List<Song>) -> Unit) = netVM.playAllNetdiskDir(dir, onPlayAll)

    /**
     * 刷新合并后的专辑 / 艺术家列表
      * NAS 数据（albums/artists） + 本地歌曲（localSongs）+ 百度网盘索引 按 name 去重合并
      */
    private fun updateMergedData() {
        viewModelScope.launch(Dispatchers.Default) {
            val nasAlbums = _albums.value.dataOrNull() ?: emptyList()
            val nasArtists = _artists.value.dataOrNull() ?: emptyList()
            val localSongs = _localSongs.value
            val baiduSongs = baiduIndexCache.allSongs()  // 全量加载一次，供 buildBaiduAlbums/buildBaiduArtists 共用

            var mergedAlbums = MusicMerger.mergeAlbums(
                nasAlbums = nasAlbums,
                localAlbums = MusicMerger.buildLocalAlbums(localSongs),
                baiduAlbums = MusicMerger.buildBaiduAlbums(baiduSongs)
            )
            // 应用已解析的封面缓存，避免每次重建丢失 coverUrl
            if (resolvedAlbumCovers.isNotEmpty()) {
                mergedAlbums = mergedAlbums.map { album ->
                    val cached = resolvedAlbumCovers[album.id]
                    if (cached != null && album.coverUrl == null) album.copy(coverUrl = cached) else album
                }
            }
            _mergedAlbums.value = mergedAlbums

            var mergedArtists = MusicMerger.mergeArtists(
                nasArtists = nasArtists,
                localArtists = MusicMerger.buildLocalArtists(localSongs),
                baiduArtists = MusicMerger.buildBaiduArtists(baiduSongs)
            )
            // 应用已解析的艺术家封面缓存
            if (resolvedArtistCovers.isNotEmpty()) {
                mergedArtists = mergedArtists.map { artist ->
                    val cached = resolvedArtistCovers[artist.id]
                    if (cached != null && artist.coverUrl == null) artist.copy(coverUrl = cached) else artist
                }
            }
            _mergedArtists.value = mergedArtists

            // 用全量歌曲反向统计艺术家 songCount（复用已加载的 baiduSongs，避免再次 allSongs()）
            updateArtistSongCounts(baiduSongs)

            // 异步解析缺失封面（百度侧车/APIC → iTunes → 本地ID3 → 歌曲 coverUrl 兜底）
            resolveAlbumCoversAsync(baiduSongs)
            // 异步解析缺失的艺术家封面（iTunes → 百度音乐）。
            // 读 _mergedArtists 覆盖 NAS + 本地 + 百度三个来源；
            // 百度/本地艺术家在 updateMergedData 生成，此前只靠 loadArtists（NAS 连接）触发导致漏解析。
            resolveArtistCoversAsync(baiduSongs)
        }
    }

    /**
     * 用全量歌曲（NAS + 本地 + 百度）反向统计每个艺术家的歌曲数，
     * 使艺术家卡片立即显示正确的 songCount，不必等到详情页加载。
     *
     * @param baiduSongs 已加载的百度歌曲列表（由 updateMergedData 传入，避免重复 allSongs()）
     */
    private fun updateArtistSongCounts(baiduSongs: List<Song> = baiduIndexCache.allSongs()) {
        val allSongs = _songsPaging.value.songs + _localSongs.value + baiduSongs
        // 按 ArtistSplitter 拆分后的艺术家名统计歌曲数
        val artistSongCounts = mutableMapOf<String, Int>()
        for (song in allSongs) {
            if (song.artist.isBlank()) continue
            val names = ArtistSplitter.split(song.artist)
            for (name in names) {
                val key = ArtistSplitter.normalizeKey(name)
                if (key.isNotBlank()) {
                    artistSongCounts[key] = (artistSongCounts[key] ?: 0) + 1
                }
            }
        }
        // 更新 mergedArtists 列表中的 songCount
        val artists = _mergedArtists.value
        if (artistSongCounts.isNotEmpty()) {
            _mergedArtists.value = artists.map { artist ->
                val key = ArtistSplitter.normalizeKey(artist.name)
                val countedSongs = artistSongCounts[key]
                if (countedSongs != null && countedSongs > 0) {
                    artist.copy(songCount = countedSongs)
                } else {
                    artist
                }
            }
        }
    }

    private var coverResolveJob: kotlinx.coroutines.Job? = null
    private var artistCoverResolveJob: kotlinx.coroutines.Job? = null

    /** 已解析的专辑封面缓存（albumId → coverUrl），跨 updateMergedData 保持 */
    private val resolvedAlbumCovers: MutableMap<String, String> by lazy {
    // 从持久缓存预加载已解析的专辑封面（跨会话复用，避免重复网络搜索）
    val m = mutableMapOf<String, String>()
    nasMusicApp.coverUrlPersistentCache.exportAll().forEach { (k, v) ->
        if (k.startsWith("album:")) m[k.removePrefix("album:")] = v
    }
    m
}

    /**
     * 专辑封面解析尝试次数（albumId → 次数）。
     *
     * 收敛护栏：`updateMergedData()` 末尾会无条件调用 `resolveAlbumCoversAsync()`，
     * 而后者解析结束后又回调 `updateMergedData()`，二者形成**无中断条件**的后台循环
     * （持续 merge + 统计 songCount + 对解析不出的专辑重复发网络请求，造成 CPU/电量与流量开销）。
     * 以「每个专辑最多尝试 [albumCoverMaxAttempts] 次」让该链条必然收敛；
     * 新出现的专辑不在表中，仍会被正常解析。
     */
    private val albumCoverAttempts = mutableMapOf<String, Int>()

    /** 单个专辑最多解析封面的次数（含首次），保留 1 次重试以容忍瞬时网络失败 */
    private val albumCoverMaxAttempts = 2

    /** 已解析的艺术家封面缓存（artistId → coverUrl），跨 updateMergedData 保持 */
    private val resolvedArtistCovers: MutableMap<String, String> by lazy {
    // 从持久缓存预加载已解析的艺术家封面（跨会话复用，避免重复网络搜索）
    val m = mutableMapOf<String, String>()
    nasMusicApp.coverUrlPersistentCache.exportAll().forEach { (k, v) ->
        if (k.startsWith("artist:")) m[k.removePrefix("artist:")] = v
    }
    m
}

    /**
     * 艺术家封面解析尝试次数。收敛护栏：`updateMergedData()` 末尾会调 `resolveArtistCoversAsync()`，
     * 后者解析结束若解析到新封面又回调 `updateMergedData()`，可能形成后台循环。
     * 以「每个艺术家最多尝试 [artistCoverMaxAttempts] 次」让链条必然收敛。
     */
    private val artistCoverAttempts = mutableMapOf<String, Int>()

    /** 单个艺术家最多解析封面的次数（含首次），保留 1 次重试以容忍瞬时网络失败 */
    private val artistCoverMaxAttempts = 2

    private fun resolveAlbumCoversAsync(cachedBaiduSongs: List<Song>? = null) {
        // 收敛护栏：只对「仍缺封面」且「尝试次数未达上限」的专辑发起解析。
        // 全部已解析或已达上限时直接返回，切断 updateMergedData ↔ 本方法 的循环。
        val pending = _mergedAlbums.value.filter {
            it.coverUrl == null && (albumCoverAttempts[it.id] ?: 0) < albumCoverMaxAttempts
        }
        if (pending.isEmpty()) return
        // 已有解析 job 在运行 → 不取消、不重启，直接复用。
        // （updateMergedData 每次末尾都会调本方法，若这里 cancel 上一个 job，
        //   解析会被连续触发重建的 updateMergedData 反复取消，永远跑不完，
        //   resolvedAlbumCovers 不增长 → UI 一直无封面。）
        if (coverResolveJob?.isActive == true) return

        coverResolveJob = viewModelScope.launch {
            val allSongs = _songsPaging.value.songs + _localSongs.value + (cachedBaiduSongs ?: baiduIndexCache.allSongs())
            for (album in pending) {
                albumCoverAttempts[album.id] = (albumCoverAttempts[album.id] ?: 0) + 1
            }
            nasMusicApp.albumCoverResolver.resolveCovers(pending, allSongs) { updated ->
                // 仅更新封面缓存，不直接写 _mergedAlbums（避免与 updateMergedData 竞争覆盖）
                var added = 0
                for (album in updated) {
                    if (album.coverUrl != null) {
                        resolvedAlbumCovers[album.id] = album.coverUrl
                        // 仅持久化稳定 HTTP 封面 URL（动态 dlink / data URI 不适合落盘）
                        if (album.coverUrl.startsWith("http")) {
                            nasMusicApp.coverUrlPersistentCache.putAlbumCover(album.id, album.coverUrl)
                        }
                        added++
                    }
                }
                // 每批回调后立即刷新 UI（不必等全部专辑解析完）。
                // 否则 1732 个专辑串行解析要几分钟，期间封面解析了但 UI 一直不刷新。
                if (added > 0) {
                    viewModelScope.launch(Dispatchers.Main) {
                        updateMergedData()
                    }
                }
            }
            // 全部解析完成后，若仍有新封面未刷新（理论上 onUpdated 已刷新），兜底刷新一次
            withContext(Dispatchers.Main) {
                if (resolvedAlbumCovers.isNotEmpty()) updateMergedData()
            }
        }
    }

    private fun resolveArtistCoversAsync(cachedBaiduSongs: List<Song>? = null) {
        // 收敛护栏：只对「仍缺封面」且「尝试次数未达上限」的艺术家发起解析。
        // 读取合并后的 _mergedArtists（NAS + 本地 + 百度），确保百度/本地艺术家也被覆盖。
        // 全部已解析或已达上限时直接返回，切断 updateMergedData ↔ 本方法 的循环。
        val pending = _mergedArtists.value.filter {
            it.coverUrl == null && (artistCoverAttempts[it.id] ?: 0) < artistCoverMaxAttempts
        }
        // P4-only 候选：在线源已耗尽（attempts >= max）但封面仍为 null，
        // 歌曲库后续可能加载了更多歌曲 → 每次歌曲更新后重新用 P4 歌曲封面兜底
        val p4OnlyCandidates = _mergedArtists.value.filter {
            it.coverUrl == null && (artistCoverAttempts[it.id] ?: 0) >= artistCoverMaxAttempts
        }
        if (pending.isEmpty() && p4OnlyCandidates.isEmpty()) return
        // 已有解析在跑 → 不取消（避免重复触发时打断）
        if (artistCoverResolveJob?.isActive == true) return
        artistCoverResolveJob = viewModelScope.launch {
            val before = resolvedArtistCovers.size
            for (artist in pending) {
                artistCoverAttempts[artist.id] = (artistCoverAttempts[artist.id] ?: 0) + 1
            }
            // 全量歌曲（NAS + 本地 + 百度），用于 P4 歌曲封面兜底
            val allSongs = _songsPaging.value.songs + _localSongs.value + (cachedBaiduSongs ?: baiduIndexCache.allSongs())

            // 1. 主解析：P1(网易云) → P2(酷狗) → P3(iTunes) → P4(歌曲封面)
            if (pending.isNotEmpty()) {
                nasMusicApp.artistCoverResolver.resolveCovers(pending, allSongs) { updated ->
                    // 仅更新封面缓存，不直接写 _mergedArtists（避免与 updateMergedData 竞争覆盖）
                    var added = 0
                    for (artist in updated) {
                        if (artist.coverUrl != null) {
                            resolvedArtistCovers[artist.id] = artist.coverUrl
                            // 仅持久化稳定 HTTP 封面 URL（P4 歌曲封面可能是 data URI / content://，不适合落盘）
                            if (artist.coverUrl.startsWith("http")) {
                                nasMusicApp.coverUrlPersistentCache.putArtistCover(artist.id, artist.coverUrl)
                            }
                            added++
                        }
                    }
                    // 每批解析到新封面立即刷新 UI（不必等全部艺术家解析完）。
                    // updateMergedData 内部已 launch(Dispatchers.Default)，此处直接调用即可。
                    if (added > 0 && resolvedArtistCovers.size > before) {
                        updateMergedData()
                    }
                }
            }

            // 2. P4-only 补充：在线源已耗尽，但歌曲库可能已加载更多数据。
            //    不消耗在线源尝试次数，纯本地 O(artists) 查找。
            if (p4OnlyCandidates.isNotEmpty()) {
                nasMusicApp.artistCoverResolver.resolveSongCoversOnly(p4OnlyCandidates, allSongs) { updated ->
                    var added = 0
                    for (artist in updated) {
                        if (artist.coverUrl != null) {
                            resolvedArtistCovers[artist.id] = artist.coverUrl
                            if (artist.coverUrl.startsWith("http")) {
                                nasMusicApp.coverUrlPersistentCache.putArtistCover(artist.id, artist.coverUrl)
                            }
                            added++
                        }
                    }
                    if (added > 0) {
                        updateMergedData()
                    }
                }
            }
        }
    }

    /** 手动刷新本地音乐库（全量重扫） */
    fun refreshLocalMusic() {
        viewModelScope.launch {
            try {
                val scanned = nasMusicApp.localMusicRepository.fullScan()
                _localSongs.value = scanned
                updateMergedData()
                showError(getApplication<Application>().getString(R.string.local_music_refreshed, scanned.size))
            } catch (e: Exception) {
                AppLog.e("MainViewModel", "full local scan failed: ${e.message}", e)
                showError(getApplication<Application>().getString(R.string.refresh_local_music_error, e.message?.take(50)))
            }
        }
    }

    /**
     * 按专辑名（含百度 path 倒数第二段目录名）从候选歌曲中筛选同名专辑歌曲。
     * 复用于本地 / 百度 / 无 NAS 三种来源，统一匹配逻辑。
     */
    private fun filterSongsByAlbumName(albumName: String, candidates: List<Song>): List<Song> {
        val name = albumName.lowercase().trim()
        if (name.isBlank()) return emptyList()
        return candidates.filter { song ->
            val songAlbum = song.album.lowercase().trim()
            val matchAlbum = songAlbum == name
            val matchPath = !matchAlbum && songAlbum.isBlank() && song.path != null && run {
                val segments = song.path.trim('/').split("/")
                segments.getOrNull(segments.size - 2)?.lowercase()?.trim() == name
            }
            matchAlbum || matchPath
        }
    }

    /**
     * 加载专辑详情歌曲。
     *
     * 多源取数：合并专辑（[Album.sourceIds] 携带 NAS / 本地 / 百度全部来源 id）
     * 分别取数后拼接并跨源去重；非合并专辑回退到单源 albumId，行为与旧版一致。
     * 从根上解决 B20：合并后只取 NAS 歌导致本地/百度同名歌在详情页不可见的问题。
     */
    fun loadAlbumSongs(albumId: String) {
        viewModelScope.launch {
            val album = _selectedAlbum.value
            val albumName = album?.name?.lowercase()?.trim().orEmpty()
            // 合并专辑携带多源 id；单源（旧数据/未合并）回退到 albumId，向后兼容
            val sources = album?.sourceIds?.takeIf { it.isNotEmpty() } ?: listOf(albumId)

            val collected = mutableListOf<Song>()
            var nasError: Exception? = null
            val adapter = backendRegistry.getAdapter()

            for (src in sources) {
                when {
                    src.startsWith("local_album_") -> {
                        // 本地专辑详情：同时列出本地 + 百度同名歌（保留原行为）
                        collected += filterSongsByAlbumName(albumName, _localSongs.value)
                        collected += baiduIndexCache.songsByAlbumName(albumName)
                    }
                    src.startsWith("baidu_album_") -> {
                        collected += baiduIndexCache.songsByAlbumName(albumName)
                    }
                    else -> {
                        if (adapter != null) {
                            try {
                                collected += adapter.getAlbumSongs(src)
                            } catch (e: Exception) {
                                nasError = e
                            }
                        } else {
                            // 无 NAS 连接：用已加载歌曲按名兜底（保留原无 NAS 行为）
                            val candidates = _localSongs.value + baiduIndexCache.songsByAlbumName(albumName) + _songsPaging.value.songs
                            collected += filterSongsByAlbumName(albumName, candidates)
                        }
                    }
                }
            }

            // 跨源去重：同一首歌可能同时命中 NAS 与本地/百度（id 不同但曲目标识相同）
            val seen = mutableSetOf<String>()
            val result = collected.filter { song ->
                seen.add("${song.title.lowercase().trim()}|${song.artist.lowercase().trim()}|${song.durationMs}")
            }

            val cache = _albumSongsCache.value.toMutableMap()
            cache[albumId] = result
            _albumSongsCache.value = cache
            AppLog.d("NASMusic", "loadAlbumSongs multi-source: album=$albumName, ${result.size} songs, sources=${sources.size}")
            if (nasError != null) {
                AppLog.e("NASMusic", "loadAlbumSongs NAS source failed", nasError)
                showError(getApplication<Application>().getString(R.string.load_album_songs_error, nasError.message?.take(50)))
            }
        }
    }

    fun getAlbumSongsCache(albumId: String): List<Song> =
        _albumSongsCache.value[albumId] ?: emptyList()

    /**
     * 播放整张专辑（多源）。合并专辑按 [Album.sourceIds] 分别取数并跨源去重，
     * 与 [loadAlbumSongs] 共用 [filterSongsByAlbumName]，从根上解决 B20 播放路径只取 NAS 歌的问题。
     */
    fun playAlbumMultiSource(album: Album) {
        viewModelScope.launch {
            val albumName = album.name.lowercase().trim()
            val sources = album.sourceIds.takeIf { it.isNotEmpty() } ?: listOf(album.id)
            val collected = mutableListOf<Song>()
            val adapter = backendRegistry.getAdapter()
            for (src in sources) {
                when {
                    src.startsWith("local_album_") -> {
                        collected += filterSongsByAlbumName(albumName, _localSongs.value)
                        collected += baiduIndexCache.songsByAlbumName(albumName)
                    }
                    src.startsWith("baidu_album_") -> {
                        collected += baiduIndexCache.songsByAlbumName(albumName)
                    }
                    else -> {
                        if (adapter != null) {
                            try {
                                collected += adapter.getAlbumSongs(src)
                            } catch (e: Exception) {
                                AppLog.e("NASMusic", "playAlbumMultiSource NAS failed", e)
                                showError(getApplication<Application>().getString(R.string.load_album_songs_error, e.message?.take(50)))
                            }
                        } else {
                            val candidates = _localSongs.value + baiduIndexCache.songsByAlbumName(albumName) + _songsPaging.value.songs
                            collected += filterSongsByAlbumName(albumName, candidates)
                        }
                    }
                }
            }
            val seen = mutableSetOf<String>()
            val result = collected.filter { song ->
                seen.add("${song.title.lowercase().trim()}|${song.artist.lowercase().trim()}|${song.durationMs}")
            }
            if (result.isNotEmpty()) {
                playQueue(result)
                navVM.navigateTo(Screen.NowPlaying)
            }
        }
    }

    // --- 详情页导航（A-1, A-2）---
    fun openAlbumDetail(album: Album) {
        _selectedAlbum.value = album
        loadAlbumSongs(album.id)
        navVM.navigateTo(Screen.AlbumDetail)
    }

    fun openArtistDetail(artistName: String) {
        _selectedArtistName.value = artistName
        loadArtistSongs(artistName)
        navVM.navigateTo(Screen.ArtistDetail)
    }

    private val _artistDetailSongsCache = MutableStateFlow<Map<String, List<Song>>>(emptyMap())
    val artistDetailSongsCache: StateFlow<Map<String, List<Song>>> = _artistDetailSongsCache.asStateFlow()

    /** 五个音乐源并行查找的临时容器 */
    private data class FiveSources(
        val nas: List<Song>,
        val paging: List<Song>,
        val local: List<Song>,
        val baidu: List<Song>,
        val network: List<Song>
    )

    fun loadArtistSongs(artistName: String) {
        viewModelScope.launch(Dispatchers.Default) {
            // 注意：不在加载开始时清空 _artistDetailSongsCache，否则在重新加载期间 UI 会显示空列表。
            // 旧数据保持显示，直到新数据就绪后直接覆盖。
            // artistSongsMap 以归一化名为键，此处理由同上，但 artistSongsMap 仅被 LibraryScreen 使用，
            // 用户在 ArtistDetail 页时 LibraryScreen 未组合，不会看到中间空窗，所以可以安全清除。
            _artistSongsMap.value = _artistSongsMap.value.toMutableMap().apply {
                remove(ArtistSplitter.normalizeKey(artistName))
            }

            try {
                // ---- 所有音乐源真正并行查找 ----
                // 快源（内存缓存）几乎瞬时返回，慢源（NAS API、Meting 搜索）并行不阻塞彼此
                val adapter = backendRegistry.getAdapter()
                val (nasSongs, pagingSongs, localDeviceSongs, baiduSongs, networkSongs) = coroutineScope {
                    // 快源：纯内存读取，不放在 async 里直接同步取
                    val fastPaging = async(Dispatchers.Default) { _songsPaging.value.songs }
                    val fastLocal = async(Dispatchers.Default) { _localSongs.value }
                    // 百度索引：按艺术家直接在 raw entry 上过滤，不创建全部 Song 对象
                    val fastBaidu = async(Dispatchers.Default) { baiduIndexCache.songsByArtist(artistName) }

                    // 慢源1: NAS 后端
                    val nasDeferred = async(Dispatchers.IO) {
                        if (adapter != null) {
                            val artistKey = ArtistSplitter.normalizeKey(artistName)
                            val rawMatchingIds = _rawArtistList
                                .filter { ArtistSplitter.containsArtist(it.name, artistName) }
                                .map { it.id }
                                .distinct()
                                .ifEmpty {
                                    val artists = _artists.value.dataOrNull() ?: emptyList()
                                    val artist = artists.find { ArtistSplitter.normalizeKey(it.name) == artistKey }
                                    if (artist != null) listOf(artist.id.substringBefore("|", artist.id)) else emptyList()
                                }
                            AppLog.d("NASMusic", "loadArtistSongs('$artistName') NAS rawMatchingIds=${rawMatchingIds.size}")
                            rawMatchingIds.flatMap { id ->
                                try {
                                    adapter.getArtistSongs(id, artistName)
                                } catch (e: Exception) {
                                    AppLog.w("NASMusic", "loadArtistSongs: NAS ID=$id query failed: ${e.message?.take(50)}")
                                    emptyList()
                                }
                            }
                        } else {
                            emptyList()
                        }
                    }

                    // 慢源2: 网络音乐源（Meting-API 等，最慢，10-15s）
                    val networkDeferred = async(Dispatchers.IO) {
                        try {
                            nasMusicApp.networkMusicManager.search(artistName)
                        } catch (e: Exception) {
                            AppLog.w("NASMusic", "loadArtistSongs: network search failed: ${e.message?.take(50)}")
                            emptyList()
                        }
                    }

                    FiveSources(
                        nas = nasDeferred.await(),
                        paging = fastPaging.await(),
                        local = fastLocal.await(),
                        baidu = fastBaidu.await(),
                        network = networkDeferred.await()
                    )
                }

                // 合并所有源，按 ArtistSplitter 拆分后只取包含该艺术家的歌曲
                // 去重用 dedupeKey（标题+艺术家规范化），而非 id（不同源同一首歌 id 不同）
                // 优先级：NAS > 本地设备 > 百度 > 网络（本地源音质更稳定，优先保留）
                // 注意：baiduSongs 已通过 songsByArtist 预过滤，这里只过滤其余源
                val artistKey = ArtistSplitter.normalizeKey(artistName)
                val allCandidates = (nasSongs + pagingSongs + localDeviceSongs + baiduSongs + networkSongs)
                    .filter { ArtistSplitter.containsArtistWithKey(it.artist, artistKey) }
                val seenKeys = mutableSetOf<String>()
                val finalSongs = allCandidates.filter { seenKeys.add(it.dedupeKey) }

                AppLog.d("NASMusic", "loadArtistSongs('$artistName') final=${finalSongs.size} (nas=${nasSongs.size}, paging=${pagingSongs.size}, local=${localDeviceSongs.size}, baidu=${baiduSongs.size}, network=${networkSongs.size}, beforeDedup=${allCandidates.size})")
                // 诊断：打印重复 dedupeKey 的歌曲，定位去重失败原因
                val duplicates = allCandidates.groupBy { it.dedupeKey }.filter { it.value.size > 1 }
                duplicates.entries.take(5).forEach { (key, songs) ->
                    AppLog.d("NASMusic", "  DUP key='$key' count=${songs.size}: ${songs.joinToString { "[${it.networkSource ?: "local"}/${it.id}] '${it.title}'/'${it.artist}'" }}")
                }
                finalSongs.take(3).forEach { s ->
                    AppLog.d("NASMusic", "  song artist='${s.artist}' title='${s.title}' album='${s.album}'")
                }
                _artistDetailSongsCache.value = _artistDetailSongsCache.value.toMutableMap().apply {
                    put(artistName, finalSongs)
                }
                // 同时按拆分后的艺术家名更新 artistSongsMap 缓存
                buildArtistMapsIncremental(finalSongs)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "loadArtistSongs failed", e)
                showError(getApplication<Application>().getString(R.string.load_favorites_error, e.message?.take(50)))
            }
        }
    }

    // --- B-1 收藏控制 ---
    /**
     * 判断歌曲是否已收藏（同步，用于 UI 快速判断）。
     * 同时查 NAS 收藏（_favoriteIds）与网络/本地收藏（networkFavoriteIds）。
     * 推荐优先用 [favoriteIds] StateFlow 订阅以获得即时刷新；本方法仅用于一次性查询。
     */
    fun isFavorite(songId: String): Boolean =
        songId in _favoriteIds.value || songId in networkFavoriteIds.value

    // --- 本地歌单操作（R-1：已拆分至 PlaylistViewModel，此处为兼容转发）---
    val playlistVM = PlaylistViewModel(app)


    /**
     * 播放整个本地歌单（含网络歌曲时自动解析 streamUrl）
     */
    fun playLocalPlaylist(playlist: LocalPlaylist) {
        if (playlist.songs.isEmpty()) return
        playQueue(playlist.songs, 0)
        navVM.navigateTo(Screen.NowPlaying)
    }

    // --- 数据备份（R-1：已拆分至 BackupViewModel，此处为兼容转发）---
    val backupVM = BackupViewModel(app)

    suspend fun restoreBackupFromJson(json: String): Boolean = backupVM.restoreBackupFromJson(json)

    // --- B-2 最近播放 & 播放次数 ---
    fun recordPlay(song: Song) {
        viewModelScope.launch {
            // 单次 DataStore edit 同时更新 id 列表 + 播放次数 + 完整歌曲对象
            prefs.history.recordPlayWithSong(song)
            // 刷新最近播放列表，不显示 loading 以避免闪烁
            loadRecentSongs(showLoading = false)
        }
    }

    val recentSongIds = prefs.history.recentSongIds
    val playCounts = prefs.history.playCounts


    // --- A-3 流派/年代歌曲加载 ---
    fun getSongsByGenre(genre: String, onResult: (List<Song>) -> Unit) {
        viewModelScope.launch {
            // 本地歌曲按 genre 过滤（不依赖 NAS）
            val localMatches = _localSongs.value.filter { it.genre?.equals(genre, ignoreCase = true) == true }
            val adapter = backendRegistry.getAdapter()
            if (adapter == null) {
                onResult(localMatches)
                return@launch
            }
            try {
                val nasSongs = adapter.getSongsByGenre(genre)
                onResult((nasSongs + localMatches).distinctBy { it.id })
            } catch (e: Exception) {
                AppLog.e("NASMusic", "getSongsByGenre failed", e)
                showError(getApplication<Application>().getString(R.string.load_genre_songs_error, e.message?.take(50)))
                onResult(localMatches)
            }
        }
    }

    fun getSongsByYearRange(fromYear: Int, toYear: Int, onResult: (List<Song>) -> Unit) {
        viewModelScope.launch {
            // 本地歌曲按 year 过滤（不依赖 NAS）
            val localMatches = _localSongs.value.filter { song ->
                song.year != null && song.year in fromYear..toYear
            }
            val adapter = backendRegistry.getAdapter()
            if (adapter == null) {
                onResult(localMatches)
                return@launch
            }
            try {
                val nasSongs = adapter.getSongsByYearRange(fromYear, toYear)
                onResult((nasSongs + localMatches).distinctBy { it.id })
            } catch (e: Exception) {
                AppLog.e("NASMusic", "getSongsByYearRange failed", e)
                showError(getApplication<Application>().getString(R.string.load_year_songs_error, e.message?.take(50)))
                onResult(localMatches)
            }
        }
    }

    // --- D-2 网络状态自动重连 ---
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3
    /** F2-4：断网恢复续播 job（新恢复事件取消旧的等待） */
    private var networkRestoreJob: kotlinx.coroutines.Job? = null

    fun onNetworkAvailable() {
        if (_isNetworkAvailable.value) return // 已是可用状态，跳过（防止 NetworkMonitor 重复回调）
        _isNetworkAvailable.value = true
        // MTV 模式下不弹提示（MV 视频流请求可能导致网络抖动，频繁弹"网络已恢复"打扰观看）
        if (!mvVM.showMv.value) {
            _connectMessage.value = getApplication<Application>().getString(R.string.status_network_restored)
            viewModelScope.launch {
                delay(2000)
                _connectMessage.value = null
            }
        }
        // F2-4：断线续播——取回断点，2 秒去抖后重解析当前歌并 seek 回断点
        val resumePoint = playerManager.onNetworkRestored()
        if (resumePoint != null) {
            networkRestoreJob?.cancel()
            networkRestoreJob = viewModelScope.launch {
                delay(2000) // 网络栈稳定去抖（WiFi 切换可能再抖）
                // 队列仍在且索引有效才续播（断网期间用户可能清了队列）
                val queue = playerManager.getQueueSnapshot()
                if (resumePoint.index in queue.indices) {
                    AppLog.d("NASMusic", "onNetworkAvailable: resuming playback at index=${resumePoint.index}, pos=${resumePoint.positionMs}")
                    // wasPlaying=false 只加载不播；网络歌曲直链必过期 → 统一走重解析路径
                    if (resumePoint.wasPlaying) {
                        playerVM.resolveAndPlayByIndex(resumePoint.index)
                        // 重解析 playQueue 从 0 开始，seek 回断点由进度恢复逻辑兜底
                    }
                }
            }
        }
        // 自动重连
        if (!serverVM.isConnected.value && reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            AppLog.d("NASMusic", "onNetworkAvailable: reconnecting (attempt $reconnectAttempts/$maxReconnectAttempts)")
            serverVM.connectToSavedServer(silent = true)
        }
    }

    fun onNetworkLost() {
        _isNetworkAvailable.value = false
        reconnectAttempts = 0
        // F2-4：标记断网（PlayerManager 冻结错误跳歌，等待恢复续播）
        playerManager.onNetworkGone()
        if (!mvVM.showMv.value) {
            _connectMessage.value = getApplication<Application>().getString(R.string.status_network_disconnected)
            viewModelScope.launch {
                delay(5000)
                _connectMessage.value = null
            }
        }
    }

    // --- 播放控制（R-1 第四步：已迁至 PlayerViewModel，此处转发）---
    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        // 非随心听播放时停止自动续播（随心听状态仍由 MainViewModel 的浏览/发现域持有）
        _isShufflePlaying = false
        shuffleRefillJob?.cancel()
        playerVM.playQueue(songs, startIndex)
    }

    // --- F2-3 智能电台 ---
    /** 智能电台状态（生成中/播放中/耗尽），UI 提示用 */
    val smartRadioState = nasMusicApp.smartRadioManager.state

    /**
     * 从当前歌曲启动智能电台（NowPlaying"智能电台"按钮）。
     * 批次生成后入队播放；NAS 未连接或曲库空给出提示。
     */
    fun startSmartRadioFromCurrent() {
        val seed = playerVM.currentSong.value ?: return
        if (backendRegistry.getAdapter() == null) {
            _connectMessage.value = getApplication<Application>().getString(R.string.smart_radio_need_nas)
            viewModelScope.launch {
                delay(3000)
                _connectMessage.value = null
            }
            return
        }
        _connectMessage.value = getApplication<Application>().getString(R.string.smart_radio_generating)
        nasMusicApp.smartRadioManager.startFromCurrentSong(seed) { batch, ctx ->
            _connectMessage.value = getApplication<Application>().getString(R.string.smart_radio_started, ctx.seed.title)
            viewModelScope.launch {
                delay(2000)
                _connectMessage.value = null
            }
            // 批次入队（从批次第一首播起）
            playQueue(batch, 0)
        }
    }

    fun addSongToQueue(song: Song) = playerManager.addToQueue(song)

    // =====================================================================
    // R-1 第二步拆分：Download / MvSearch / VocalSeparation 已迁至子 ViewModel，
    // 以下为兼容转发层（AppRoot 的既有引用不变）。
    // =====================================================================

    val downloadVM = DownloadViewModel(
        app,
        nasMusicApp.songDownloadManager,
        nasMusicApp.modelDownloadManager,
        nasMusicApp.autoDownloadController
    )
    val vocalVM = VocalSeparationViewModel(app, playerManager)
    val mvVM = MvSearchViewModel(app, mvSearchManager, playerManager)

    init {
        // 下载域刷新本地歌曲时联动合并数据
        downloadVM.onLocalSongsChanged = {
            viewModelScope.launch {
                _localSongs.value = nasMusicApp.localMusicRepository.loadFromCache()
                updateMergedData()
            }
        }
        // 模型下载状态同步给人声分离域（模式切换门槛判断）
        viewModelScope.launch {
            downloadVM.modelDownloaded.collect { downloaded -> vocalVM.setModelDownloaded(downloaded) }
        }
        // K 歌进入时启动遥控服务器
        vocalVM.onEnsureRemoteControlStarted = { ensureRemoteControlStarted() }
        // MV 播放模式同步（playMode 由 MainViewModel 拥有，作为方法参数下发）
        viewModelScope.launch {
            playerVM.playMode.collect { mvVM.currentPlayMode = it }
        }
    }

    // ---- DownloadViewModel 转发 ----
    val songDownloadStates: StateFlow<Map<String, com.nasmusic.tv.backend.download.model.DownloadState>>
        get() = downloadVM.songDownloadStates


    // ---- VocalSeparationViewModel 转发 ----

    fun clearAccompanimentCache() {
        viewModelScope.launch {
            val count = vocalVM.clearAccompanimentCache()
            _connectMessage.value = getApplication<Application>().getString(R.string.status_accompaniment_cache_cleared, count)
            delay(2000)
            _connectMessage.value = null
        }
    }

    // ---- MvSearchViewModel 转发 ----

    fun enterMvMode() {
        ensureRemoteControlStarted()
        mvVM.enterMvMode()
    }
    fun clearMvPersistentCache() {
        mvVM.clearPersistentCache {
            viewModelScope.launch {
                _connectMessage.value = getApplication<Application>().getString(R.string.status_mv_cache_cleared)
                delay(2000)
                _connectMessage.value = null
            }
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

    override fun removeFromQueue(index: Int) = playerManager.removeFromQueue(index)

    // （addSongToQueue/addSongsToQueue/toggleQueueSong 已迁至 PlayerViewModel，见播放控制转发区）

    /**
     * 队列中所有歌曲 id 的集合（供 UI 快速判断某首歌是否在队列中）
     */
    val queueSongIds: StateFlow<Set<String>> = playerVM.queue
        .map { songs -> songs.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * 队列中的网络歌曲（用于「继续听」区域）
     *
     * 从当前队列中筛选出网络歌曲（isNetworkSong=true），
     * 不包含当前正在播放的歌曲，最多保留 5 首，
     * 按队列顺序排列（最近即将播放的在前）。
     */
    val recentNetworkSongs: StateFlow<List<Song>> = combine(playerVM.queue, playerVM.currentIndex) { songs, index ->
        songs.filterIndexed { i, s -> s.isNetworkSong && i != index }
            .take(5)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 当前播放的网络歌曲（用于「继续听」区域的"正在播放"）
     */
    val currentNetworkSong: StateFlow<Song?> = combine(playerVM.currentSong, playerVM.queue) { song, _ ->
        song?.takeIf { it.isNetworkSong }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun clearQueue() {
        playerManager.clearQueue()
        _currentLyrics.value = null
        _lyricsAvailability.value = LyricsAvailability()
        // 清除持久化的上次播放队列
        viewModelScope.launch { prefs.queue.clearLastQueue() }
    }

    private fun loadLyricsForCurrentSong() {
        lyricsLoadJob?.cancel()
        // 切歌时清空候选缓存和轮次状态
        lyricsManager.clearCachedCandidates()
        _currentLyrics.value = null
        _lyricsAvailability.value = LyricsAvailability()
        // 重置网络封面（切歌时清除上一首的网络封面）
        _networkCoverUrl.value = null
        val song = playerVM.currentSong.value ?: return
        AppLog.d("NASMusic", "loadLyrics: loading for ${song.title} by ${song.artist}")
        lyricsLoadJob = viewModelScope.launch {
            try {
                // 0. 本地已下载歌词（最高优先级，不走网络）
                val dlState = songDownloadStates.value[song.downloadKey]
                AppLog.d("NASMusic", "loadLyrics: dlState=${dlState?.javaClass?.simpleName}, key=${song.downloadKey}")
                // 确定本地音频路径：优先用下载状态，否则用 song.path（下载后以 LOCAL 源出现，downloadKey 不匹配）
                val localAudioPath: String? = when {
                    dlState is DownloadState.Completed && dlState.path.isNotBlank() -> dlState.path
                    song.isLocalSong && !song.path.isNullOrBlank() -> {
                        AppLog.d("NASMusic", "loadLyrics: isLocalSong fallback, song.path=${song.path}")
                        song.path
                    }
                    else -> null
                }
                if (localAudioPath != null) {
                    val localLyricPath = (dlState as? DownloadState.Completed)?.lyricPath
                    AppLog.d("NASMusic", "loadLyrics: localAudioPath=$localAudioPath, lyricPath=$localLyricPath")
                    val localLrc = LocalLyricsProvider.getLyricsFromPath(
                        localAudioPath, localLyricPath
                    )
                    AppLog.d("NASMusic", "loadLyrics: localLrc=${localLrc != null}, validLrc=${localLrc?.let { LrcParser.isValidLrc(it) }}")
                    if (localLrc != null && LrcParser.isValidLrc(localLrc)) {
                        val lyrics = LrcParser.parse(localLrc, song.id)
                            .copy(source = LyricsSource.LOCAL_FILE)
                        _currentLyrics.value = lyrics
                        if (lyrics.lines.any { it.wordTimestamps.isNotEmpty() }) {
                            _lyricsHighlightMode.value = LyricsHighlightMode.WORD_BY_WORD
                        }
                        AppLog.d("NASMusic", "loadLyrics: local downloaded lyrics, ${lyrics.lines.size} lines")
                        // 本地歌词已加载，仍异步检查后端/网络可用性（供用户手动切换）
                        val availability = lyricsManager.checkAvailability(song)
                        _lyricsAvailability.value = availability.copy(cached = lyrics)
                        // 自动搜索封面（如果本地无旁路封面且无后端封面）
                        if (getCoverCandidates(song).isEmpty()) {
                            val networkCover = nasMusicApp.networkMusicManager.searchCoverUrl(song.title, song.artist)
                            if (networkCover != null) {
                                _networkCoverUrl.value = networkCover
                            }
                        }
                        return@launch
                    }
                }

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
                _lyricsAvailability.value = availability.copy(cached = _lyricsAvailability.value.cached)
                AppLog.d("NASMusic", "loadLyrics: cached=${cachedLyrics != null}, backend=${availability.hasBackend}, network=${availability.hasNetwork}")

                // 3. 无缓存时使用后端或网络歌词
                if (cachedLyrics == null) {
                    val lyrics = availability.backend ?: availability.network
                    if (lyrics != null) {
                        _currentLyrics.value = lyrics
                        if (lyrics.lines.any { it.wordTimestamps.isNotEmpty() }) {
                            _lyricsHighlightMode.value = LyricsHighlightMode.WORD_BY_WORD
                        }
                        // 如果歌词来自网络（非用户手动切换），暂存以便播放完成后持久化
                        if (lyrics.source == LyricsSource.NETWORK) {
                            // checkAvailability 内部已通过 fetchLyrics 获取文本，
                            // 这里用解析后的行文本重建 LRC 暂存
                            val lrcText = LrcParser.toLrcText(lyrics)
                            if (lrcText.isNotBlank()) {
                                lyricsManager.savePendingNetworkLyrics(song, lrcText)
                            }
                        }
                    }
                    AppLog.d("NASMusic", "loadLyrics: source=${lyrics?.source}, lines=${lyrics?.lines?.size}")
                }

                // 4. 无封面时自动搜索网络封面（所有源歌曲通用）
                if (getCoverCandidates(song).isEmpty()) {
                    val networkCover = nasMusicApp.networkMusicManager.searchCoverUrl(song.title, song.artist)
                    if (networkCover != null) {
                        _networkCoverUrl.value = networkCover
                        AppLog.d("NASMusic", "loadLyrics: auto-search cover found: ${networkCover.take(60)}")
                    } else {
                        AppLog.d("NASMusic", "loadLyrics: auto-search cover: no result")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("NASMusic", "loadLyrics failed", e)
                showError(getApplication<Application>().getString(R.string.load_lyrics_error, e.message?.take(50)))
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

        // 1. 已下载歌曲：优先内嵌封面 → 旁路封面
        val dlState = songDownloadStates.value[song.downloadKey]
        // 确定本地音频路径：优先用下载状态，否则用 song.path（下载后以 LOCAL 源出现，downloadKey 不匹配）
        val localAudioPath: String? = when {
            dlState is DownloadState.Completed && dlState.path.isNotBlank() -> dlState.path
            song.isLocalSong && !song.path.isNullOrBlank() -> song.path
            else -> null
        }
        if (localAudioPath != null) {
            // 1a. 内嵌封面（APIC 帧）→ 提取到缓存文件供 Coil 加载
            val embeddedUri = EmbeddedCoverExtractor.extractCoverUri(
                localAudioPath,
                getApplication<Application>().cacheDir
            )
            if (embeddedUri != null) {
                candidates.add(embeddedUri)
            }
            // 1b. 旁路封面 .jpg 文件（内嵌失败时才有）
            (dlState as? DownloadState.Completed)?.coverPath?.let { cp ->
                if (cp.isNotBlank() && java.io.File(cp).exists()) {
                    candidates.add("file://$cp")
                }
            }
        }

        // 2. 网络/后端封面
        if (song.isNetworkSong) {
            song.coverUrl?.let { candidates.add(it) }
            _networkCoverUrl.value?.let { candidates.add(it) }
        } else {
            val adapter = backendRegistry.getAdapter()
            if (adapter != null) {
                candidates.addAll(adapter.getCoverUrlCandidates(song))
            }
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
        val song = playerVM.currentSong.value ?: return
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
                showError(getApplication<Application>().getString(R.string.switch_lyrics_source_error, e.message?.take(50)))
            }
        }
    }

    // --- 设置 ---
    fun updateDarkTheme(enabled: Boolean) = viewModelScope.launch {
        prefs.player.setDarkTheme(enabled)
    }

    fun updateAnimationsEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.player.setAnimationsEnabled(enabled)
    }

    fun updateAutoPlayNext(enabled: Boolean) = viewModelScope.launch {
        prefs.player.setAutoPlayNext(enabled)
    }

    fun updateDefaultPlayMode(mode: PlayMode) = viewModelScope.launch {
        prefs.player.setDefaultPlayMode(mode)
        playerVM.setPlayMode(mode)
    }

    fun updateCacheLyrics(enabled: Boolean) = viewModelScope.launch {
        prefs.lyrics.setCacheLyrics(enabled)
    }

    fun updateCacheCover(enabled: Boolean) = viewModelScope.launch {
        prefs.lyrics.setCacheCover(enabled)
    }

    fun updateLyricsOffset(offsetMs: Long) = viewModelScope.launch {
        prefs.lyrics.setLyricsOffset(offsetMs)
    }

    fun updateLyricsFontScale(scale: Float) = viewModelScope.launch {
        prefs.lyrics.setLyricsFontScale(scale)
    }

    suspend fun updateLanguage(lang: String) {
        prefs.languagePrefs.setLanguage(lang)
        // 立即应用语言变更（重启 Activity 以重新加载所有资源）
        (getApplication() as? NasMusicApp)?.applyLocale(lang)
    }

    fun updateCoverFilterEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.visualizer.setCoverFilterEnabled(enabled)
    }

    fun updateCoverFilterBlurRadius(radius: Float) = viewModelScope.launch {
        prefs.visualizer.setCoverFilterBlurRadius(radius)
    }

    fun updateCoverFilterDarkOverlay(overlay: Float) = viewModelScope.launch {
        prefs.visualizer.setCoverFilterDarkOverlay(overlay)
    }

    /**
     * 更新 Meting-API 端点 URL（网络搜索配置）
     * 传入空串则恢复默认端点
     */
    fun updateMetingApiBaseUrl(url: String) = viewModelScope.launch {
        val normalized = url.trim()
        if (normalized.isEmpty()) {
            prefs.network.setMetingApiBaseUrl(com.nasmusic.tv.backend.network.MetingApiService.DEFAULT_BASE_URL)
        } else {
            prefs.network.setMetingApiBaseUrl(normalized)
        }
    }

    // F2-5：crossfade 设置（PlayerManager 的 volatile 字段由 PlayerViewModel init 收集注入）
    fun setCrossfadeEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.player.setCrossfadeEnabled(enabled)
    }

    fun setCrossfadeDurationSec(sec: Int) = viewModelScope.launch {
        prefs.player.setCrossfadeDurationSec(sec)
    }

    // F2-6：音质档位（Meting br 参数；AUTO 由端点默认/带宽决策）
    fun setQualityTier(tier: Int) = viewModelScope.launch {
        prefs.player.setQualityTier(tier)
    }

    /**
     * 更新 MTV 视频搜索端点 URL（网络搜索配置）
     * 传入空串则恢复默认端点
     */
    fun updateMvApiBaseUrl(url: String) = viewModelScope.launch {
        val normalized = url.trim()
        if (normalized.isEmpty()) {
            prefs.network.setMvApiBaseUrl(com.nasmusic.tv.backend.network.mv.BilibiliMvService.DEFAULT_BASE_URL)
        } else {
            prefs.network.setMvApiBaseUrl(normalized)
        }
    }

    fun updateLyricsKugouBaseUrl(url: String) = viewModelScope.launch {
        val normalized = url.trim()
        if (normalized.isEmpty()) {
            prefs.lyrics.setLyricsKugouBaseUrl(com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_KUGOU_BASE_URL)
        } else {
            prefs.lyrics.setLyricsKugouBaseUrl(normalized)
        }
    }

    fun updateLyricsNeteaseBaseUrl(url: String) = viewModelScope.launch {
        val normalized = url.trim()
        if (normalized.isEmpty()) {
            prefs.lyrics.setLyricsNeteaseBaseUrl(com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_NETEASE_BASE_URL)
        } else {
            prefs.lyrics.setLyricsNeteaseBaseUrl(normalized)
        }
    }

    /**
     * 更新 OpenWeatherMap API Key
     */
    fun updateWeatherApiKey(key: String) = viewModelScope.launch {
        prefs.weather.setWeatherApiKey(key.trim())
    }

    fun updateSpectrumEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.visualizer.setSpectrumEnabled(enabled)
    }

    fun updateVisualizerTheme(theme: com.nasmusic.tv.data.model.VisualizerTheme) = viewModelScope.launch {
        prefs.visualizer.setVisualizerTheme(theme)
    }

    // --- 歌曲离线下载设置 ---
    fun updateDownloadEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.download.setDownloadEnabled(enabled)
    }

    fun updateAutoDownloadOnPlay(enabled: Boolean) = viewModelScope.launch {
        prefs.download.setAutoDownloadOnPlay(enabled)
    }

    fun updateAutoDownloadLimit(limit: Int) = viewModelScope.launch {
        prefs.download.setAutoDownloadLimit(limit)
    }

    fun updateDownloadLocation(location: String) = viewModelScope.launch {
        prefs.download.setDownloadLocation(location)
    }

    fun updateFontAdjustment(adjustment: Int) = viewModelScope.launch {
        prefs.visualizer.setFontAdjustment(adjustment)
    }

    /** 手动下载单曲（歌曲行 ⬇ 按钮）——已迁至 DownloadViewModel.downloadSong */

    // --- 导出功能（§8.8） ---
    private val exportCoordinator by lazy {
        (getApplication<NasMusicApp>()).exportCoordinator
    }

    /** 导出状态（供设置页 UI 订阅） */
    val exportState: StateFlow<ExportState> = exportCoordinator.state

    /** 导出设备列表（刷新后更新） */
    private val _exportDevices = MutableStateFlow<List<StorageDevice>>(emptyList())
    val exportDevices: StateFlow<List<StorageDevice>> = _exportDevices.asStateFlow()

    /** 设备选择弹窗显隐 */
    private val _showExportDeviceDialog = MutableStateFlow(false)
    val showExportDeviceDialog: StateFlow<Boolean> = _showExportDeviceDialog.asStateFlow()

    /** 显示设备选择弹窗（刷新设备列表后弹出） */
    fun showExportDeviceDialog() {
        viewModelScope.launch {
            val devices = nasMusicApp.storageMonitor.storageDevices.value
            exportCoordinator.refreshDevices(devices)
            _exportDevices.value = exportCoordinator.requestExportDevices()
            _showExportDeviceDialog.value = true
        }
    }

    /** 用户选择设备后触发导出 */
    fun onExportDeviceSelected(device: StorageDevice) {
        _showExportDeviceDialog.value = false
        exportCoordinator.exportTo(device)
    }

    /** 用户取消导出 */
    fun cancelExport() {
        exportCoordinator.cancel()
    }

    /** 重置导出状态（完成后/关闭弹窗后） */
    fun resetExportState() {
        exportCoordinator.reset()
    }

    // --- E-4 缓存管理 ---
    fun clearLyricsCache() {
        viewModelScope.launch {
            lyricsManager.clearCache()
            _connectMessage.value = getApplication<Application>().getString(R.string.status_lyrics_cache_cleared)
            delay(2000)
            _connectMessage.value = null
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    fun clearCoverCache() {
        viewModelScope.launch {
            val context = getApplication<android.app.Application>()
            // 取 Coil 全局 ImageLoader（与 PlaybackService/CoilBitmapLoader 同一实例）。
            // 此前用 coil.ImageLoader(context) 工厂函数会创建全新无配置实例，
            // 其 memoryCache/diskCache 与 UI 实际使用的缓存不是同一个，清除不生效。
            val imageLoader = Coil.imageLoader(context)
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
            AppLog.d("MainViewModel", "clearCoverCache: cache cleared")
            _connectMessage.value = getApplication<Application>().getString(R.string.status_cover_cache_cleared)
            delay(2000)
            _connectMessage.value = null
        }
    }

    // （clearMvPersistentCache / clearAccompanimentCache 已迁至子 ViewModel 转发区，勿重复定义）

    // --- B-4 均衡器 ---
    val equalizerPreset: StateFlow<EqualizerPreset> = prefs.visualizer.equalizerPreset.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = EqualizerPreset.NORMAL
    )

    val equalizerBands: StateFlow<List<Float>> = prefs.visualizer.equalizerBands.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    fun setEqualizerPreset(preset: EqualizerPreset) {
        viewModelScope.launch {
            prefs.visualizer.setEqualizerPreset(preset)
            // 同时持久化频段数据到 DataStore，确保 UI 能正确显示 currentBands
            prefs.visualizer.setEqualizerBands(preset.bandGains)
            // 应用频段到 PlayerManager
            playerManager.setEqualizerBands(preset.bandGains)
        }
    }

    fun setEqualizerBand(index: Int, value: Float) {
        viewModelScope.launch {
            prefs.visualizer.setEqualizerBand(index, value)
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
                _playlists.value = UiState.Error(getApplication<Application>().getString(R.string.backend_not_connected))
                return@launch
            }
            try {
                _playlists.value = UiState.Success(adapter.getPlaylists())
            } catch (e: Exception) {
                AppLog.e("NASMusic", "loadPlaylists failed", e)
                _playlists.value = UiState.Error(
                    message = getApplication<Application>().getString(R.string.load_playlists_error, e.message?.take(50)),
                    retry = { loadPlaylists() }
                )
            }
        }
    }

    fun selectPlaylist(playlist: Playlist) {
        _selectedPlaylistSongs.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _selectedPlaylistSongs.value = UiState.Error(getApplication<Application>().getString(R.string.backend_not_connected))
                return@launch
            }
            try {
                val songs = adapter.getPlaylistSongs(playlist.id)
                _selectedPlaylistSongs.value = UiState.Success(songs)
            } catch (e: Exception) {
                AppLog.e("NASMusic", "selectPlaylist songs failed", e)
                _selectedPlaylistSongs.value = UiState.Error(
                    message = getApplication<Application>().getString(R.string.load_playlist_songs_error, e.message?.take(50)),
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
                    _connectMessage.value = getApplication<Application>().getString(R.string.playlist_created)
                } else {
                    _connectMessage.value = getApplication<Application>().getString(R.string.create_failed)
                }
            } catch (e: Exception) {
                _connectMessage.value = getApplication<Application>().getString(R.string.create_failed_with_msg, e.message)
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
                    _connectMessage.value = getApplication<Application>().getString(R.string.playlist_deleted)
                } else {
                    _connectMessage.value = getApplication<Application>().getString(R.string.delete_failed)
                }
            } catch (e: Exception) {
                _connectMessage.value = getApplication<Application>().getString(R.string.delete_failed_with_msg, e.message)
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
                    navVM.navigateTo(Screen.NowPlaying)
                }
            } catch (e: Exception) {
                AppLog.e("NASMusic", "playPlaylist failed", e)
                showError(getApplication<Application>().getString(R.string.play_playlist_error, e.message?.take(50)))
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
                showError(getApplication<Application>().getString(R.string.remove_from_playlist_error, e.message?.take(50)))
            }
        }
    }

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
                _radioStations.value = UiState.Error(message = getApplication<Application>().getString(R.string.radio_load_failed))
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
            navVM.navigateTo(Screen.NowPlaying)
        }
    }

    // --- Jamendo ---
    private val _jamendoState = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val jamendoState: StateFlow<UiState<List<Song>>> = _jamendoState.asStateFlow()
    private val _jamendoActiveTag = MutableStateFlow("")
    val jamendoActiveTag: StateFlow<String> = _jamendoActiveTag.asStateFlow()

    /** 是否已配置 Jamendo Client ID（未配置时 Jamendo 显示引导卡） */
    val jamendoConfigured: Boolean
        get() = prefs.network.getJamendoClientIdSync().isNotBlank()

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
                _jamendoState.value = UiState.Error(message = getApplication<Application>().getString(R.string.jamendo_load_failed))
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
                _jamendoState.value = UiState.Error(message = getApplication<Application>().getString(R.string.jamendo_load_failed))
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
                _jamendoState.value = UiState.Error(message = getApplication<Application>().getString(R.string.jamendo_load_failed))
            }
        }
    }

    /** 更新 Jamendo Client ID 并动态注册/注销服务 */
    fun updateJamendoClientId(id: String) {
        viewModelScope.launch {
            prefs.network.setJamendoClientId(id)
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

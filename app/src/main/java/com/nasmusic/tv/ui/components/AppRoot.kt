package com.nasmusic.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.EqualizerPreset
import com.nasmusic.tv.data.model.HomeDashboardData
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SearchHistoryItem
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.ui.LocalNavigateBackHandler
import com.nasmusic.tv.ui.screens.AlbumDetailScreen
import com.nasmusic.tv.ui.screens.ArtistDetailScreen
import com.nasmusic.tv.ui.screens.EqualizerScreen
import com.nasmusic.tv.ui.screens.HomeScreen
import com.nasmusic.tv.ui.screens.LibraryScreen
import com.nasmusic.tv.ui.screens.LibraryTab
import com.nasmusic.tv.ui.screens.MineScreen

import com.nasmusic.tv.ui.screens.NowPlayingScreen
import com.nasmusic.tv.ui.screens.PlaylistManagementScreen
import com.nasmusic.tv.ui.screens.PlaylistPickerDialog
import com.nasmusic.tv.ui.screens.QueueScreen
import com.nasmusic.tv.ui.screens.ServerConnectScreen
import com.nasmusic.tv.ui.screens.SettingsScreen
import com.nasmusic.tv.ui.screens.BackupTransferDialog
import com.nasmusic.tv.ui.theme.NASMusicTVTheme
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.data.model.Screen
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppRoot(
    viewModel: MainViewModel,
    isImmersiveMode: androidx.compose.runtime.MutableState<Boolean>,
    onConnect: (ServerConfig) -> Unit
) {
    val context = LocalContext.current
    val isTV = remember {
        context.packageManager.hasSystemFeature("android.software.leanback")
    }
    val currentScreen by viewModel.currentScreen.collectAsState(initial = Screen.Home)
    val currentSong by viewModel.currentSong.collectAsState(initial = null)
    val isPlaying by viewModel.isPlaying.collectAsState(initial = false)
    val playMode by viewModel.playMode.collectAsState(initial = com.nasmusic.tv.data.model.PlayMode.SEQUENTIAL)
    val progress by viewModel.progress.collectAsState(initial = 0L)
    val duration by viewModel.duration.collectAsState(initial = 0L)
    val spectrumData by viewModel.spectrumData.collectAsState(initial = FloatArray(0))
    val lyrics by viewModel.currentLyrics.collectAsState(initial = null)
    val lyricsAvailability by viewModel.lyricsAvailability.collectAsState(initial = com.nasmusic.tv.data.model.LyricsAvailability())
    val lyricsHighlightMode by viewModel.lyricsHighlightMode.collectAsState(initial = com.nasmusic.tv.data.model.LyricsHighlightMode.LINE_BY_LINE)
    val networkCoverUrl by viewModel.networkCoverUrl.collectAsState(initial = null)
    val albums by viewModel.albums.collectAsState(initial = UiState.Loading as UiState<List<Album>>)
    val songs by viewModel.songs.collectAsState(initial = UiState.Loading as UiState<List<Song>>)
    val queue by viewModel.queue.collectAsState(initial = emptyList())
    val currentIndex by viewModel.currentIndex.collectAsState(initial = 0)
    val isLoading by viewModel.isLoading.collectAsState(initial = false)
    val isLibraryLoading by viewModel.isLibraryLoading.collectAsState(initial = false)
    val isConnected by viewModel.isConnected.collectAsState(initial = false)
    val serverDisplayName by viewModel.serverDisplayName.collectAsState(initial = "")
    val backendApiVersion by viewModel.backendApiVersion.collectAsState(initial = "Unknown")
    val serverConfig by viewModel.serverConfig.collectAsState(initial = ServerConfig.Empty)
    val settings by viewModel.appSettings.collectAsState(initial = com.nasmusic.tv.data.model.AppSettings())
    // 封面滤镜状态（跨屏幕共享，用于 NowPlaying + Settings）
    val coverFilterEnabled by viewModel.prefs.coverFilterEnabled.collectAsState(initial = false)
    val coverFilterBlurRadius by viewModel.prefs.coverFilterBlurRadius.collectAsState(initial = 8f)
    val coverFilterDarkOverlay by viewModel.prefs.coverFilterDarkOverlay.collectAsState(initial = 0.3f)
    // 天气 API Key
    val weatherApiKey by viewModel.prefs.weatherApiKey.collectAsState(initial = "")
    // 百度网盘状态（设置页网盘分区）
    val baiduConnectionState by viewModel.baiduConnectionState.collectAsState(initial = com.nasmusic.tv.ui.viewmodel.MainViewModel.BaiduConnectionState.Off)
    val baiduDeviceCode by viewModel.baiduDeviceCode.collectAsState(initial = null)
    val baiduIndexScanned by viewModel.baiduIndexScanned.collectAsState(initial = 0)
    val baiduIndexScanning by viewModel.baiduIndexScanning.collectAsState(initial = false)
    // MTV 页面显隐（进入 MTV 全屏页时为 true）
    val showMv by viewModel.showMv.collectAsState(initial = false)
    // MTV 搜索状态（顶层收集，供 NotFound 自动退出保护与 NowPlaying 分支共用）
    val mvState by viewModel.mvState.collectAsState()
    // 安全兜底：离开 NowPlaying（如媒体键改变播放状态）时自动退出 MTV 全屏，避免主播放器一直暂停
    LaunchedEffect(currentScreen, showMv) {
        if (showMv && currentScreen != Screen.NowPlaying) {
            viewModel.exitMvMode()
        }
    }
    // 安全兜底：切歌后新歌无 MV（NotFound）时自动退出 MTV 全屏，避免卡在无导航栏的播放页
    LaunchedEffect(mvState, showMv) {
        if (showMv && mvState is com.nasmusic.tv.ui.viewmodel.MvAvailability.NotFound) {
            viewModel.exitMvMode()
        }
    }
    // Level 2: 根据当前屏幕和沉浸模式动态设置导航 BACK 键处理函数
    val navBackHandler = LocalNavigateBackHandler.current
    LaunchedEffect(currentScreen, isImmersiveMode.value, showMv) {
        val handler: (() -> Unit)? = when {
            isImmersiveMode.value -> {{ isImmersiveMode.value = false }}
            showMv -> {{ viewModel.exitMvMode() }}
            currentScreen == Screen.NowPlaying -> if (isTV) null else {{ viewModel.navigateTo(Screen.Home) }}
            currentScreen == Screen.Home -> null
            currentScreen == Screen.ServerConnect -> {{ viewModel.navigateTo(Screen.Settings) }}
            else -> {{ viewModel.navigateTo(Screen.Home) }}
        }
        navBackHandler.value = handler
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部导航栏（沉浸模式 / MTV 全屏页时隐藏；TV 与手机一致）
        if (!isImmersiveMode.value && !showMv) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(36.dp)
                            .background(
                                NasMusicColors.Primary,
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "\u266A", color = NasMusicColors.TextPrimary, fontSize = FontSize.subtitle())
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "NAS Music", color = NasMusicColors.TextPrimary, fontSize = FontSize.subtitle())
                }

                // 导航项（外层固定宽度右对齐；内层可横向滑动——手机窄屏滚动浏览全部 tab）
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                NavItem(
                    label = stringResource(R.string.nav_home),
                    selected = currentScreen == Screen.Home,
                    onClick = { viewModel.navigateTo(Screen.Home) }
                )
                NavItem(
                    label = stringResource(R.string.nav_now_playing),
                    selected = currentScreen == Screen.NowPlaying,
                    onClick = { viewModel.navigateTo(Screen.NowPlaying) }
                )
                NavItem(
                    label = stringResource(R.string.nav_library),
                    selected = currentScreen == Screen.Library,
                    onClick = { viewModel.navigateTo(Screen.Library) }
                )
                NavItem(
                    label = stringResource(R.string.nav_mine),
                    selected = currentScreen == Screen.Mine,
                    onClick = { viewModel.navigateTo(Screen.Mine) }
                )
                NavItem(
                    label = stringResource(R.string.nav_queue),
                    selected = currentScreen == Screen.Queue,
                    onClick = { viewModel.navigateTo(Screen.Queue) }
                )
                NavItem(
                    label = stringResource(R.string.nav_settings),
                    selected = currentScreen == Screen.Settings,
                    onClick = { viewModel.navigateTo(Screen.Settings) }
                )
                }
            }
        }
        }

        // 内容区域
        // 封面候选列表（跨屏幕复用，确保首页和 NowPlaying 使用相同的封面解析逻辑）
        val coverCandidates = remember(currentSong?.id, networkCoverUrl) {
            currentSong?.let { viewModel.getCoverCandidates(it) } ?: emptyList()
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (currentScreen) {
                Screen.Home -> {
                    val homeDashboardData by viewModel.homeDashboardData.collectAsState(initial = HomeDashboardData())
                    val weatherData by viewModel.weatherData.collectAsState(initial = null)
                    val weatherLoading by viewModel.weatherLoading.collectAsState(initial = false)
                    val weatherError by viewModel.weatherError.collectAsState(initial = null)
                    val recentSongsState by viewModel.recentSongs.collectAsState(initial = UiState.Success(emptyList()))
                    val recentSongsList = recentSongsState.dataOrNull() ?: emptyList()
                    val randomSongs by viewModel.randomSongs.collectAsState(initial = emptyList())

                    // 进入首页时刷新数据
                    LaunchedEffect(Unit) {
                        viewModel.loadHomeDashboard()
                        viewModel.loadRecentSongs()
                        viewModel.fetchWeather()
                        viewModel.loadRandomSongs()
                    }

                    HomeScreen(
                        isConnected = isConnected,
                        isLibraryLoading = isLibraryLoading,
                        serverDisplayName = serverDisplayName,
                        dashboardData = homeDashboardData,
                        weatherData = weatherData,
                        weatherLoading = weatherLoading,
                        weatherError = weatherError,
                        recentSongs = recentSongsList,
                        currentSong = currentSong,
                        coverCandidates = coverCandidates,
                        onPlaySong = { song ->
                            if (song.isNetworkSong) viewModel.playNetworkSong(song)
                            else viewModel.playQueue(listOf(song))
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onPlayAlbum = { album ->
                            val albumSongs = songs.dataOrNull()?.filter { it.albumId == album.id } ?: emptyList()
                            if (albumSongs.isNotEmpty()) {
                                viewModel.playQueue(albumSongs)
                                viewModel.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onOpenAlbumDetail = { album -> viewModel.openAlbumDetail(album) },
                        onNavigateToLibrary = { viewModel.navigateTo(Screen.Library) },
                        onNavigateToSearch = {
                            viewModel.selectLibraryTab(LibraryTab.SEARCH)
                            viewModel.navigateTo(Screen.Library)
                        },
                        onNavigateToQueue = { viewModel.navigateTo(Screen.Queue) },
                        onNavigateToNowPlaying = { viewModel.navigateTo(Screen.NowPlaying) },
                        onPlayAllRecent = {
                            if (recentSongsList.isNotEmpty()) {
                                viewModel.playQueue(recentSongsList)
                                viewModel.navigateTo(Screen.NowPlaying)
                            }
                        },
                        randomSongs = randomSongs,
                        onPlayRandomSongs = { songs, index ->
                            viewModel.playRandomSongs(songs, index)
                        }
                    )
                }
                Screen.NowPlaying -> {
                    val lyricsFontScale by viewModel.prefs.lyricsFontScale.collectAsState(initial = 1.0f)
                    val vocalRemovalEnabled by viewModel.vocalRemovalEnabled.collectAsState()
                    val pitchSemitones by viewModel.pitchSemitones.collectAsState()
                    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
                    val separationMode by viewModel.separationMode.collectAsState()
                    val separating by viewModel.separating.collectAsState()
                    val separationProgress by viewModel.separationProgress.collectAsState()
                    val hqError by viewModel.hqError.collectAsState()
                    val hqSuccess by viewModel.hqSuccess.collectAsState()
                    val modelDownloaded by viewModel.modelDownloaded.collectAsState()
                    val mvReady = mvState as? com.nasmusic.tv.ui.viewmodel.MvAvailability.Ready
                    if (showMv && mvReady != null) {
                        // MTV 音乐视频全屏页（独立播放器，退出时 MainViewModel 恢复主播放器）
                        MvPlaybackScreen(
                            mv = mvReady.mv,
                            lyrics = lyrics,
                            alternatives = mvReady.alternatives,
                            onExit = { viewModel.exitMvMode() },
                            onPlaybackError = { viewModel.onMvPlaybackError() },
                            onPlaybackEnded = { viewModel.onMvPlaybackEnded() },
                            onSwitchOrResearch = { viewModel.onSwitchOrResearch() },
                            onSearchBilibili = { viewModel.onSearchBilibili() },
                            onPreviousMv = { viewModel.onMvPrevious() },
                            onNextMv = { viewModel.onMvNext() },
                            mvMessage = viewModel.mvMessage.collectAsState().value,
                            // 手机端无需"手机遥控"二维码（自身即控制端）
                            remoteControlUrl = if (isTV) viewModel.remoteControlUrl.collectAsState().value else null
                        )
                    } else {
                        NowPlayingScreen(
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            playMode = playMode,
                            progressMs = progress,
                            durationMs = duration,
                            lyrics = lyrics,
                            lyricsAvailability = lyricsAvailability,
                            coverCandidates = coverCandidates,
                            highlightMode = lyricsHighlightMode,
                            lyricsFontScale = lyricsFontScale,
                            onLyricsFontScaleChange = { viewModel.updateLyricsFontScale(it) },
                            coverFilterEnabled = coverFilterEnabled,
                            coverFilterBlurRadius = coverFilterBlurRadius,
                            coverFilterDarkOverlay = coverFilterDarkOverlay,
                            // 网络歌曲用网络收藏判断，本地歌曲用本地收藏判断
                            isFavorite = currentSong?.let { song ->
                                if (song.isNetworkSong) viewModel.isNetworkFavorite(song.id)
                                else viewModel.isFavorite(song.id)
                            } ?: false,
                            isImmersiveMode = isImmersiveMode.value,
                            onToggleImmersive = { isImmersiveMode.value = !isImmersiveMode.value },
                            onPlayPause = { viewModel.playPause() },
                            onNext = { viewModel.next() },
                            onPrevious = { viewModel.previous() },
                            onTogglePlayMode = { viewModel.togglePlayMode() },
                            // === KARAOKE 人声消除 ===
                            vocalRemovalEnabled = vocalRemovalEnabled,
                            onToggleVocalRemoval = { viewModel.toggleVocalRemoval() },
                            // === MTV 音乐视频 ===
                            mvAvailable = mvState is com.nasmusic.tv.ui.viewmodel.MvAvailability.Ready,
                            onEnterMv = { viewModel.enterMvMode() },
                            // 手机端不启动 HTTP 遥控服务（自身即控制端）
                            remoteControlUrl = if (isTV) viewModel.remoteControlUrl.collectAsState().value else null,
                            onEnterKaraokeMode = { if (isTV) viewModel.ensureRemoteControlStarted() },
                            onSeek = { viewModel.seekTo(it) },
                            onSwitchLyricsSource = { viewModel.switchLyricsSource(it) },
                            onChangeHighlightMode = { viewModel.setLyricsHighlightMode(it) },
                            // 网络歌曲调用 toggleNetworkFavorite，本地歌曲调用 toggleFavorite
                            onToggleFavorite = currentSong?.let { song ->
                                {
                                    if (song.isNetworkSong) viewModel.toggleNetworkFavorite(song)
                                    else viewModel.toggleFavorite(song)
                                }
                            },
                            technicalInfo = viewModel.songTechnicalInfo.collectAsState(initial = null).value,
                            onLoadTechnicalInfo = { viewModel.loadSongTechnicalInfo() },
                            spectrumData = spectrumData,
                            spectrumEnabled = settings.spectrumEnabled,
                            visualizerTheme = settings.visualizerTheme,
                            onSearchArtist = { keyword ->
                                viewModel.searchNetworkSongs(keyword)
                            },
                            onSearchSong = { keyword ->
                                viewModel.searchNetworkSongs(keyword)
                            },
                            // === K 歌页面：升降调 / 变速 ===
                            pitchSemitones = pitchSemitones,
                            playbackSpeed = playbackSpeed,
                            onSetPitch = { viewModel.setPitchSemitones(it) },
                            onSetSpeed = { viewModel.setPlaybackSpeed(it) },
                            onResetPitch = { viewModel.resetPitch() },
                            onResetSpeed = { viewModel.resetSpeed() },
                            // === 分离模式（快速/高质量） ===
                            isHighQualityMode = separationMode == com.nasmusic.tv.data.prefs.AppPreferences.SeparationMode.HIGH_QUALITY,
                            isSeparating = separating,
                            separationProgress = separationProgress,
                            hqError = hqError,
                            onToggleSeparationMode = { viewModel.toggleSeparationMode() },
                            onClearHqError = { viewModel.clearHqError() },
                            hqSuccess = hqSuccess,
                            onClearHqSuccess = { viewModel.clearHqSuccess() },
                            // 高质量分离模型是否已下载（未下载时 K 歌页禁用高质量切换）
                            modelDownloaded = modelDownloaded
                        )
                    }
                }
                Screen.Library -> {
                    val genres by viewModel.genres.collectAsState(initial = UiState.Success(emptyList()))
                    val favoriteIds by viewModel.favoriteIds.collectAsState(initial = emptySet())
                    val networkFavoriteIds by viewModel.networkFavoriteIds.collectAsState(initial = emptySet())
                    val artistsState by viewModel.artists.collectAsState(initial = UiState.Success(emptyList()))
                    val yearsState by viewModel.years.collectAsState(initial = UiState.Success(emptyList()))
                    val songsPaging by viewModel.songsPaging.collectAsState(initial = com.nasmusic.tv.data.model.SongsPagingState())
                    val searchResultsState by viewModel.searchResults.collectAsState(initial = UiState.Success(emptyList()))
                    val albumList = albums.dataOrNull() ?: emptyList()
                    val songList = songs.dataOrNull() ?: emptyList()
                    val genreList = genres.dataOrNull() ?: emptyList()
                    val artistsList = artistsState.dataOrNull() ?: emptyList()
                    val yearsList = yearsState.dataOrNull() ?: emptyList()
                    val searchResultsList = searchResultsState.dataOrNull() ?: emptyList()
                    val isSearching = searchResultsState is UiState.Loading
                    val libraryActiveTab by viewModel.libraryActiveTab.collectAsState()
                    val librarySearchKeyword by viewModel.librarySearchKeyword.collectAsState()
                    val enabledSearchSources by viewModel.enabledSearchSources.collectAsState()
                    val localPlaylists by viewModel.localPlaylists.collectAsState(initial = emptyList())
                    val searchHistory by viewModel.searchHistory.collectAsState(initial = emptyList())
                    var pickerSong by remember { mutableStateOf<Song?>(null) }

                    // ── RADIO Tab state ──
                    val radioStations by viewModel.radioStations.collectAsState(initial = UiState.Success(emptyList()))
                    val radioActiveTag by viewModel.radioActiveTag.collectAsState(initial = null)
                    val radioActiveQuery by viewModel.radioActiveQuery.collectAsState(initial = "")
                    // ── DISCOVER Tab state ──
                    val browseSelections by viewModel.browseSelections.collectAsState(initial = emptyList())
                    val browseResultsState by viewModel.browseResults.collectAsState(initial = UiState.Success(emptyList()))
                    val browseIsLoading by viewModel.isBrowseSearching.collectAsState(initial = false)
                    val browseResultsList = browseResultsState.dataOrNull() ?: emptyList()
                    // Build DiscoverTab-compatible dimensions from BrowseDimension enum
                    val discoverDimensions = remember {
                        com.nasmusic.tv.data.model.BrowseDimension.entries.map { dim ->
                            com.nasmusic.tv.ui.screens.library.BrowseDimension(
                                label = dim.displayName,
                                options = dim.options.map { it.label }
                            )
                        }
                    }
                    val discoverCurrentDimensionValues = remember(browseSelections) {
                        com.nasmusic.tv.data.model.BrowseDimension.entries.mapIndexed { dimIdx, dim ->
                            val selectedIdx = browseSelections.getOrElse(dimIdx) { 0 }
                            dim.displayName to dim.options.getOrElse(selectedIdx) { dim.options.first() }.label
                        }.toMap()
                    }
                    LibraryScreen(
                        albums = albumList,
                        songs = songList,
                        isLoading = isLoading || isLibraryLoading,
                        isConnected = isConnected,
                        genres = genreList,
                        favoriteIds = favoriteIds + networkFavoriteIds,
                        artistSongsMap = viewModel.artistSongsMap.value,
                        artists = artistsList,
                        years = yearsList,
                        songsPaging = songsPaging,
                        searchResults = searchResultsList,
                        isSearching = isSearching,
                        onPlayAlbum = { album ->
                            val albumSongs = songList.filter { it.albumId == album.id }
                            if (albumSongs.isNotEmpty()) {
                                viewModel.playQueue(albumSongs)
                                viewModel.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onPlaySong = { song ->
                            // 网络歌曲需要先解析播放链接，本地歌曲直接播放
                            if (song.isNetworkSong) {
                                viewModel.playNetworkSong(song)
                            } else {
                                viewModel.playQueue(listOf(song))
                            }
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onPlaySongs = { songListParam ->
                            viewModel.playQueue(songListParam)
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onPlayAllSongs = { songs ->
                            if (songs.isNotEmpty()) {
                                viewModel.playQueue(songs)
                                viewModel.navigateTo(Screen.NowPlaying)
                            }
                        },
                        queueSongIds = viewModel.queueSongIds.collectAsState(initial = emptySet()).value,
                        onToggleQueue = { song -> viewModel.toggleQueueSong(song) },
                        onToggleFavorite = { song ->
                            // 网络歌曲走网络收藏，本地歌曲走本地收藏
                            if (song.isNetworkSong) viewModel.toggleNetworkFavorite(song)
                            else viewModel.toggleFavorite(song)
                        },
                        onAddToPlaylist = { song -> pickerSong = song },
                        onOpenAlbumDetail = { album -> viewModel.openAlbumDetail(album) },
                        onOpenArtistDetail = { artist -> viewModel.openArtistDetail(artist) },
                        onSongsByGenre = { genre, callback -> viewModel.getSongsByGenre(genre, callback) },
                        onSongsByYear = { from, to, callback -> viewModel.getSongsByYearRange(from, to, callback) },
                        onLoadSongsFirstPage = { viewModel.loadSongsFirstPage() },
                        onLoadSongsNextPage = { viewModel.loadSongsNextPage() },
                        onLoadArtists = { viewModel.loadArtists() },
                        onLoadYears = { viewModel.loadYears() },
                        onSearch = { query -> viewModel.searchSongsOnServer(query) },
                        onClearSearch = { viewModel.clearSearch() },
                        historyItems = searchHistory,
                        activeTab = libraryActiveTab,
                        onTabSelected = { tab -> viewModel.selectLibraryTab(tab) },
                        filterQuery = librarySearchKeyword,
                        onFilterQueryChange = { keyword -> viewModel.setLibrarySearchKeyword(keyword) },
                        enabledSearchSources = enabledSearchSources,
                        onToggleSearchSource = { source -> viewModel.toggleSearchSource(source) },
                        onEnableAllSearchSources = { viewModel.enableAllSearchSources() },
                        // ── SEARCH Tab ──
                        onSearchTabPlayAll = {
                            val allSongs = searchResultsList
                            if (allSongs.isNotEmpty()) {
                                viewModel.playQueue(allSongs)
                                viewModel.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onSearchTabAddAllToQueue = {
                            // 只加入队列，不播放
                            searchResultsList.forEach { song -> viewModel.toggleQueueSong(song) }
                        },
                        // ── DISCOVER Tab ──
                        discoverDimensions = discoverDimensions,
                        discoverFilteredSongs = browseResultsList,
                        discoverIsLoading = browseIsLoading,
                        discoverCurrentDimensionValues = discoverCurrentDimensionValues,
                        onDiscoverDimensionChanged = { dimensionLabel, optionLabel ->
                            val dimIdx = com.nasmusic.tv.data.model.BrowseDimension.entries.indexOfFirst { it.displayName == dimensionLabel }
                            if (dimIdx >= 0) {
                                val dim = com.nasmusic.tv.data.model.BrowseDimension.entries[dimIdx]
                                val optIdx = dim.options.indexOfFirst { it.label == optionLabel }
                                if (optIdx >= 0) {
                                    viewModel.selectBrowseOption(dimIdx, optIdx)
                                }
                            }
                        },
                        onDiscoverPlayAll = {
                            if (browseResultsList.isNotEmpty()) {
                                viewModel.playQueue(browseResultsList)
                                viewModel.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onDiscoverAddAllToQueue = {
                            // 只加入队列，不播放
                            browseResultsList.forEach { song -> viewModel.toggleQueueSong(song) }
                        },
                        onDiscoverShuffle = {
                            viewModel.refreshBrowseSongs()
                        },
                        onDiscoverEnsureLoaded = {
                            viewModel.ensureBrowseLoaded()
                        },
                        // ── RADIO Tab ──
                        radioStations = radioStations,
                        radioActiveTag = radioActiveTag,
                        radioActiveQuery = radioActiveQuery,
                        onLoadRadioDefault = { viewModel.loadRadioDefault() },
                        onLoadRadioTag = { tag -> viewModel.loadRadioTag(tag) },
                        onSearchRadio = { keyword -> viewModel.searchRadio(keyword) },
                        onPlayRadioStation = { station -> viewModel.playRadioStation(station) }
                    )
                    // 加入歌单选择弹窗
                    pickerSong?.let { song ->
                        PlaylistPickerDialog(
                            playlists = localPlaylists,
                            onPick = { playlist ->
                                viewModel.addSongToPlaylist(playlist.id, song)
                                pickerSong = null
                            },
                            onCreate = { name ->
                                if (name.isNotBlank()) {
                                    viewModel.createLocalPlaylist(name)
                                }
                            },
                            onDismiss = { pickerSong = null }
                        )
                    }
                }
                Screen.Mine -> {
                    val favoriteSongsState by viewModel.favoriteSongs.collectAsState(initial = UiState.Success(emptyList()))
                    val networkFavoriteSongs by viewModel.networkFavoriteSongs.collectAsState(initial = emptyList())
                    val recentSongsState by viewModel.recentSongs.collectAsState(initial = UiState.Success(emptyList()))
                    val recentSongsList = recentSongsState.dataOrNull() ?: emptyList()
                    val localPlaylists by viewModel.localPlaylists.collectAsState(initial = emptyList())
                    // 进入"我的"页时刷新最近播放（首次进入/从播放页返回时更新）
                    LaunchedEffect(Unit) {
                        viewModel.loadRecentSongs()
                    }
                    val queueSongIds by viewModel.queueSongIds.collectAsState(initial = emptySet())
                    MineScreen(
                        favoriteSongsState = favoriteSongsState,
                        networkFavoriteSongs = networkFavoriteSongs,
                        recentSongs = recentSongsList,
                        localPlaylists = localPlaylists,
                        queueSongIds = queueSongIds,
                        onPlaySong = { song ->
                            // 网络歌曲先解析播放链接，本地歌曲直接播放
                            if (song.isNetworkSong) {
                                viewModel.playNetworkSong(song)
                            } else {
                                viewModel.playQueue(listOf(song))
                            }
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onPlayAll = { songs ->
                            if (songs.isNotEmpty()) {
                                viewModel.playQueue(songs)
                                viewModel.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onToggleFavorite = { song ->
                            if (song.isNetworkSong) viewModel.toggleNetworkFavorite(song)
                            else viewModel.toggleFavorite(song)
                        },
                        onToggleQueue = { song -> viewModel.toggleQueueSong(song) },
                        onCreatePlaylist = { name -> viewModel.createLocalPlaylist(name) },
                        onRenamePlaylist = { id, newName -> viewModel.renameLocalPlaylist(id, newName) },
                        onDeletePlaylist = { id -> viewModel.deleteLocalPlaylist(id) },
                        onPlayPlaylist = { playlist -> viewModel.playLocalPlaylist(playlist) },
                        onRemoveSongFromPlaylist = { playlistId, songId -> viewModel.removeSongFromPlaylist(playlistId, songId) },
                        onAddSongToPlaylist = { playlistId, song -> viewModel.addSongToPlaylist(playlistId, song) },
                        // 功能入口（手机端底部导航未覆盖：队列 / 网盘 / 设置）
                        onOpenQueue = { viewModel.navigateTo(Screen.Queue) },
                        onOpenNetdisk = { viewModel.navigateTo(Screen.Netdisk) },
                        onOpenSettings = { viewModel.navigateTo(Screen.Settings) }
                    )
                }
                Screen.Queue -> {
                    QueueScreen(
                        queue = queue,
                        currentIndex = currentIndex,
                        currentSong = currentSong,
                        coverCandidates = coverCandidates,
                        isPlaying = isPlaying,
                        playMode = playMode,
                        onPlaySong = { index ->
                            if (index in queue.indices) {
                                viewModel.playQueue(queue, index)
                                viewModel.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onRemoveSong = { index -> viewModel.removeFromQueue(index) },
                        onClearQueue = { viewModel.clearQueue() },
                        onPlayPause = { viewModel.playPause() },
                        onNext = { viewModel.next() },
                        onPrevious = { viewModel.previous() },
                        onMoveItem = { from, to -> viewModel.moveQueueItem(from, to) }
                    )
                }
                Screen.Settings -> {
                    var showBackupTransferDialog by remember { mutableStateOf(false) }
                    val baiduConfig = viewModel.prefs.getBaiduConfigSync()
                    val separationMode by viewModel.separationMode.collectAsState()
                    val modelDownloaded by viewModel.modelDownloaded.collectAsState()
                    val modelDownloading by viewModel.modelDownloading.collectAsState()
                    val modelDownloadProgress by viewModel.modelDownloadProgress.collectAsState()
                    val modelDownloadedMB by viewModel.modelDownloadedMB.collectAsState()
                    val modelTotalMB by viewModel.modelTotalMB.collectAsState()
                    val modelSizeMB by viewModel.modelSizeMB.collectAsState()
                    val modelDownloadError by viewModel.modelDownloadError.collectAsState()
                    // 进入设置页时刷新模型状态（检查文件是否已下载）
                    LaunchedEffect(Unit) { viewModel.refreshModelStatus() }
                    SettingsScreen(
                        settings = settings,
                        onToggleDarkTheme = { viewModel.updateDarkTheme(it) },
                        onToggleAnimations = { viewModel.updateAnimationsEnabled(it) },
                        onToggleAutoPlayNext = { viewModel.updateAutoPlayNext(it) },
                        onChangePlayMode = { viewModel.updateDefaultPlayMode(it) },
                        onToggleCacheLyrics = { viewModel.updateCacheLyrics(it) },
                        onToggleCacheCover = { viewModel.updateCacheCover(it) },
                        onChangeLyricsOffset = { viewModel.updateLyricsOffset(it) },
                        onClearLyricsCache = { viewModel.clearLyricsCache() },
                        onClearCoverCache = { viewModel.clearCoverCache() },
                        onClearMvCache = { viewModel.clearMvPersistentCache() },
                        onClearAccompanimentCache = { viewModel.clearAccompanimentCache() },
                        onOpenEqualizer = { viewModel.navigateTo(Screen.Equalizer) },
                        onChangeMetingApiBaseUrl = { viewModel.updateMetingApiBaseUrl(it) },
                        mvApiBaseUrl = settings.mvApiBaseUrl,
                        onChangeMvApiBaseUrl = { viewModel.updateMvApiBaseUrl(it) },
                        lyricsKugouBaseUrl = settings.lyricsKugouBaseUrl,
                        onChangeLyricsKugouBaseUrl = { viewModel.updateLyricsKugouBaseUrl(it) },
                        lyricsNeteaseBaseUrl = settings.lyricsNeteaseBaseUrl,
                        onChangeLyricsNeteaseBaseUrl = { viewModel.updateLyricsNeteaseBaseUrl(it) },
                        // Jamendo（CC 独立音乐）
                        jamendoClientId = viewModel.prefs.getJamendoClientIdSync(),
                        onChangeJamendoClientId = { viewModel.updateJamendoClientId(it) },
                        weatherApiKey = weatherApiKey,
                        onChangeWeatherApiKey = { viewModel.updateWeatherApiKey(it) },
                        spectrumEnabled = settings.spectrumEnabled,
                        onToggleSpectrum = { viewModel.updateSpectrumEnabled(it) },
                        visualizerTheme = settings.visualizerTheme,
                        onChangeVisualizerTheme = { viewModel.updateVisualizerTheme(it) },
                        // 数据管理（备份/恢复）
                        backupFiles = viewModel.backupFiles.collectAsState(initial = emptyList()).value,
                        backupMessage = viewModel.backupMessage.collectAsState(initial = null).value,
                        onRefreshBackupFiles = { viewModel.refreshBackupFiles() },
                        onExportBackup = { viewModel.exportBackup() },
                        onImportBackup = { uri -> viewModel.importBackup(uri) },
                        onDeleteBackup = { uri -> viewModel.deleteBackup(uri) },
                        onConsumeBackupMessage = { viewModel.consumeBackupMessage() },
                        onScanTransferBackup = { showBackupTransferDialog = true },
                        // 百度网盘设置
                        baiduEnabled = baiduConfig.enabled,
                        baiduLoggedIn = baiduConnectionState is com.nasmusic.tv.ui.viewmodel.MainViewModel.BaiduConnectionState.LoggedIn,
                        baiduConnecting = baiduConnectionState is com.nasmusic.tv.ui.viewmodel.MainViewModel.BaiduConnectionState.Connecting,
                        baiduConnectionState = baiduConnectionState,
                        baiduDeviceCode = baiduDeviceCode,
                        baiduMusicRootDir = baiduConfig.musicRootDir,
                        baiduMvDir = baiduConfig.mvDir,
                        baiduIndexScanned = baiduIndexScanned,
                        baiduIndexScanning = baiduIndexScanning,
                        onToggleBaiduEnabled = { viewModel.setBaiduEnabled(it) },
                        onStartBaiduDeviceCode = { viewModel.startBaiduDeviceCodeFlow() },
                        onCancelBaiduDeviceCode = { viewModel.cancelBaiduDeviceCode() },
                        onLogoutBaidu = { viewModel.logoutBaidu() },
                        onChangeBaiduMusicRootDir = { viewModel.setBaiduMusicRootDir(it) },
                        onChangeBaiduMvDir = { viewModel.setBaiduMvDir(it) },
                        onListBaiduDirs = { viewModel.listBaiduDirs(it) },
                        onRebuildBaiduIndex = { viewModel.rebuildBaiduIndex() },
                        onNavigateToServerConnect = { viewModel.navigateTo(Screen.ServerConnect) },
                        // 服务器连接设置
                        serverConfig = serverConfig,
                        isConnected = isConnected,
                        serverDisplayName = serverDisplayName,
                        backendApiVersion = backendApiVersion,
                        isConnecting = isLoading,
                        onConnect = onConnect,
                        onDisconnect = { viewModel.disconnect() },
                        // 封面滤镜设置
                    coverFilterEnabled = coverFilterEnabled,
                    coverFilterBlurRadius = coverFilterBlurRadius,
                    coverFilterDarkOverlay = coverFilterDarkOverlay,
                    onToggleCoverFilter = { viewModel.updateCoverFilterEnabled(it) },
                    onChangeCoverBlurRadius = { viewModel.updateCoverFilterBlurRadius(it) },
                    onChangeCoverDarkOverlay = { viewModel.updateCoverFilterDarkOverlay(it) },
                    // 分离模式设置
                    separationMode = separationMode,
                    onChangeSeparationMode = { viewModel.setSeparationMode(it) },
                    // 高质量分离模型下载状态
                    modelDownloaded = modelDownloaded,
                    modelDownloading = modelDownloading,
                    modelDownloadProgress = modelDownloadProgress,
                    modelDownloadedMB = modelDownloadedMB,
                    modelTotalMB = modelTotalMB,
                    modelSizeMB = modelSizeMB,
                    modelDownloadError = modelDownloadError,
                    onDownloadModel = { viewModel.downloadModel() },
                    onDeleteModel = { viewModel.deleteModel() },
                    onRefreshModelStatus = { viewModel.refreshModelStatus() }
                    )
                    // 扫码传输备份弹窗
                    if (showBackupTransferDialog) {
                        BackupTransferDialog(
                            onRestore = { json -> viewModel.restoreBackupFromJsonBlocking(json) },
                            onBackupChanged = { viewModel.refreshBackupFiles() },
                            onDismiss = { showBackupTransferDialog = false }
                        )
                    }
                }
                Screen.ServerConnect -> {
                    ServerConnectScreen(
                        initialConfig = serverConfig,
                        isConnected = isConnected,
                        serverDisplayName = serverDisplayName,
                        isConnecting = isLoading,
                        onConnect = onConnect,
                        onDisconnect = { viewModel.disconnect() }
                    )
                }
                Screen.AlbumDetail -> {
                    val selectedAlbum by viewModel.selectedAlbum.collectAsState(initial = null)
                    val albumSongsCache by viewModel.albumSongsCache.collectAsState(initial = emptyMap())
                    val albumSongs = selectedAlbum?.let { albumSongsCache[it.id] } ?: emptyList()
                    val favoriteIds by viewModel.favoriteIds.collectAsState(initial = emptySet())
                    if (selectedAlbum != null) {
                        AlbumDetailScreen(
                            album = selectedAlbum!!,
                            songs = albumSongs,
                            onPlaySong = { song ->
                                val albumSongs = selectedAlbum?.let { viewModel.getAlbumSongsCache(it.id) } ?: listOf(song)
                                viewModel.playQueue(albumSongs, albumSongs.indexOf(song).coerceAtLeast(0))
                                viewModel.navigateTo(Screen.NowPlaying)
                            },
                            onPlayAll = { songList ->
                                viewModel.playQueue(songList)
                                viewModel.navigateTo(Screen.NowPlaying)
                            },
                            onBack = { viewModel.navigateTo(Screen.Library) },
                            queueSongIds = viewModel.queueSongIds.collectAsState(initial = emptySet()).value,
                            onToggleQueue = { song -> viewModel.toggleQueueSong(song) },
                            favoriteIds = favoriteIds,
                            onToggleFavorite = { song -> viewModel.toggleFavorite(song) }
                        )
                    }
                }
                Screen.ArtistDetail -> {
                    val selectedArtistName by viewModel.selectedArtistName.collectAsState(initial = null)
                    val artistDetailSongsCache by viewModel.artistDetailSongsCache.collectAsState(initial = emptyMap())
                    val artistSongs = selectedArtistName?.let { artistDetailSongsCache[it] } ?: emptyList()
                    val artistsState by viewModel.artists.collectAsState(initial = UiState.Success(emptyList()))
                    val selectedArtist = selectedArtistName?.let { name ->
                        artistsState.dataOrNull()?.find { it.name == name }
                    }
                    val favoriteIds by viewModel.favoriteIds.collectAsState(initial = emptySet())
                    if (selectedArtistName != null) {
                        ArtistDetailScreen(
                            artist = selectedArtist,
                            artistName = selectedArtistName!!,
                            songs = artistSongs,
                            onPlaySong = { song ->
                                viewModel.playQueue(artistSongs, artistSongs.indexOf(song).coerceAtLeast(0))
                                viewModel.navigateTo(Screen.NowPlaying)
                            },
                            onPlayAll = { songList ->
                                viewModel.playQueue(songList)
                                viewModel.navigateTo(Screen.NowPlaying)
                            },
                            onBack = { viewModel.navigateTo(Screen.Library) },
                            queueSongIds = viewModel.queueSongIds.collectAsState(initial = emptySet()).value,
                            onToggleQueue = { song -> viewModel.toggleQueueSong(song) },
                            favoriteIds = favoriteIds,
                            onToggleFavorite = { song -> viewModel.toggleFavorite(song) }
                        )
                    }
                }
                Screen.Equalizer -> {
                    val equalizerPreset by viewModel.equalizerPreset.collectAsState(initial = EqualizerPreset.NORMAL)
                    val equalizerBands by viewModel.equalizerBands.collectAsState(initial = emptyList())
                    EqualizerScreen(
                        presets = EqualizerPreset.values().toList(),
                        currentPreset = equalizerPreset,
                        currentBands = equalizerBands,
                        onSelectPreset = { viewModel.setEqualizerPreset(it) },
                        onAdjustBand = { index, value -> viewModel.setEqualizerBand(index, value) },
                        onBack = { viewModel.navigateTo(Screen.Settings) },
                        visualizerTheme = settings.visualizerTheme
                    )
                }
                Screen.PlaylistManagement -> {
                    val playlistsState by viewModel.playlists.collectAsState(initial = UiState.Success(emptyList()))
                    val selectedPlaylistSongsState by viewModel.selectedPlaylistSongs.collectAsState(initial = UiState.Success(emptyList()))
                    PlaylistManagementScreen(
                        playlists = playlistsState.dataOrNull() ?: emptyList(),
                        selectedPlaylistSongs = selectedPlaylistSongsState.dataOrNull() ?: emptyList(),
                        isLoading = false,
                        onSelectPlaylist = { viewModel.selectPlaylist(it) },
                        onCreatePlaylist = { name -> viewModel.createPlaylist(name) },
                        onDeletePlaylist = { viewModel.deletePlaylist(it) },
                        onPlayPlaylist = { playlist ->
                            viewModel.playPlaylist(playlist)
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onRemoveSong = { songId -> viewModel.removeFromPlaylist(songId) },
                        onBack = { viewModel.navigateTo(Screen.Library) }
                    )
                }
                Screen.Netdisk -> {
                    com.nasmusic.tv.ui.screens.netdisk.NetdiskScreen(
                        viewModel = viewModel,
                        onPlaySong = { song ->
                            viewModel.playNetworkSong(song)
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onPlayAllSongs = { songs ->
                            viewModel.playQueue(songs, 0)
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onBack = { viewModel.navigateTo(Screen.Home) }
                    )
                }
            }
}
    }

}
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier
            .padding(horizontal = 4.dp)
            .defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.12f,
        animationDurationMs = 250,
        containerColor = Color.Transparent,
        focusedContainerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.3f)
                                else NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary,
        pressedScale = 0.96f
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = if (selected) 21.sp else 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary
            )
        }
    }
}

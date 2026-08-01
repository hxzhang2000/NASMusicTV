package com.nasmusic.tv.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.EqualizerPreset
import com.nasmusic.tv.data.model.HomeDashboardData
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.ui.LocalNavigateBackHandler
import com.nasmusic.tv.ui.screens.AlbumDetailScreen
import com.nasmusic.tv.ui.screens.ArtistDetailScreen
import com.nasmusic.tv.ui.screens.EqualizerScreen
import com.nasmusic.tv.ui.screens.HomeScreen
import com.nasmusic.tv.ui.screens.LibraryScreen
import com.nasmusic.tv.ui.screens.LibraryTab
import com.nasmusic.tv.ui.screens.NetworkPlaylistDetailScreen
import com.nasmusic.tv.ui.screens.network.NetworkMusicContainer
import com.nasmusic.tv.ui.screens.NowPlayingScreen
import com.nasmusic.tv.ui.screens.PlaylistManagementScreen
import com.nasmusic.tv.ui.screens.QueueScreen
import com.nasmusic.tv.ui.screens.ServerConnectScreen
import com.nasmusic.tv.ui.screens.SettingsScreen
import com.nasmusic.tv.ui.theme.NASMusicTVTheme
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
    val serverConfig by viewModel.serverConfig.collectAsState(initial = ServerConfig.Empty)
    val settings by viewModel.appSettings.collectAsState(initial = com.nasmusic.tv.data.model.AppSettings())
    // 封面滤镜状态（跨屏幕共享，用于 NowPlaying + Settings）
    val coverFilterEnabled by viewModel.prefs.coverFilterEnabled.collectAsState(initial = false)
    val coverFilterBlurRadius by viewModel.prefs.coverFilterBlurRadius.collectAsState(initial = 8f)
    val coverFilterDarkOverlay by viewModel.prefs.coverFilterDarkOverlay.collectAsState(initial = 0.3f)
    // 天气 API Key
    val weatherApiKey by viewModel.prefs.weatherApiKey.collectAsState(initial = "")
    // Level 2: 根据当前屏幕和沉浸模式动态设置导航 BACK 键处理函数
    val navBackHandler = LocalNavigateBackHandler.current
    LaunchedEffect(currentScreen, isImmersiveMode.value) {
        val handler: (() -> Unit)? = when {
            isImmersiveMode.value -> {{ isImmersiveMode.value = false }}
            currentScreen == Screen.Home || currentScreen == Screen.NowPlaying -> null
            else -> {{ viewModel.navigateTo(Screen.Home) }}
        }
        navBackHandler.value = handler
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部导航栏（沉浸模式下隐藏）
        if (!isImmersiveMode.value) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.padding(end = 32.dp),
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
                        Text(text = "\u266A", color = Color.Black, fontSize = 18.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "NAS Music", color = NasMusicColors.TextPrimary, fontSize = 18.sp)
                }

                Spacer(modifier = Modifier.weight(1f))

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
                    label = stringResource(R.string.nav_queue),
                    selected = currentScreen == Screen.Queue,
                    onClick = { viewModel.navigateTo(Screen.Queue) }
                )
                NavItem(
                    label = "网络音乐",
                    selected = currentScreen == Screen.Network,
                    onClick = { viewModel.navigateTo(Screen.Network) }
                )
                NavItem(
                    label = stringResource(R.string.nav_server),
                    selected = currentScreen == Screen.ServerConnect,
                    onClick = { viewModel.navigateTo(Screen.ServerConnect) }
                )
                NavItem(
                    label = stringResource(R.string.nav_settings),
                    selected = currentScreen == Screen.Settings,
                    onClick = { viewModel.navigateTo(Screen.Settings) }
                )
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
                        onNavigateToNetwork = { viewModel.navigateTo(Screen.Network) },
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
                        visualizerTheme = settings.visualizerTheme
                    )
                }
                Screen.Library -> {
                    val genres by viewModel.genres.collectAsState(initial = UiState.Success(emptyList()))
                    val favoriteIds by viewModel.favoriteIds.collectAsState(initial = emptySet())
                    val favoriteSongsState by viewModel.favoriteSongs.collectAsState(initial = UiState.Success(emptyList()))
                    val recentSongIds = viewModel.recentSongIds.collectAsState(initial = emptyList())
                    val playCounts by viewModel.playCounts.collectAsState(initial = emptyMap())
                    val artistsState by viewModel.artists.collectAsState(initial = UiState.Success(emptyList()))
                    val yearsState by viewModel.years.collectAsState(initial = UiState.Success(emptyList()))
                    val songsPaging by viewModel.songsPaging.collectAsState(initial = com.nasmusic.tv.data.model.SongsPagingState())
                    val searchResultsState by viewModel.searchResults.collectAsState(initial = UiState.Success(emptyList()))
                    val albumList = albums.dataOrNull() ?: emptyList()
                    val songList = songs.dataOrNull() ?: emptyList()
                    val genreList = genres.dataOrNull() ?: emptyList()
                    val favoriteSongsList = favoriteSongsState.dataOrNull() ?: emptyList()
                    val artistsList = artistsState.dataOrNull() ?: emptyList()
                    val yearsList = yearsState.dataOrNull() ?: emptyList()
                    val recentSongsState by viewModel.recentSongs.collectAsState(initial = UiState.Success(emptyList()))
                    val recentSongsList = recentSongsState.dataOrNull() ?: emptyList()
                    val searchResultsList = searchResultsState.dataOrNull() ?: emptyList()
                    val isSearching = searchResultsState is UiState.Loading
                    val libraryActiveTab by viewModel.libraryActiveTab.collectAsState()
                    val playlistsState by viewModel.playlists.collectAsState(initial = UiState.Success(emptyList()))
                    val playlistsList = playlistsState.dataOrNull() ?: emptyList()
                    val playlistSongsState by viewModel.selectedPlaylistSongs.collectAsState(initial = UiState.Success(emptyList()))
                    val playlistSongsList = playlistSongsState.dataOrNull() ?: emptyList()
                    val isPlaylistLoading = playlistsState is UiState.Loading || playlistSongsState is UiState.Loading
                    LibraryScreen(
                        albums = albumList,
                        songs = songList,
                        isLoading = isLoading || isLibraryLoading,
                        isConnected = isConnected,
                        genres = genreList,
                        favoriteIds = favoriteIds,
                        favoriteSongs = favoriteSongsList,
                        recentSongIds = recentSongIds.value,
                        recentSongs = recentSongsList,
                        playCounts = playCounts,
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
                        onToggleFavorite = { song -> viewModel.toggleFavorite(song) },
                        onOpenAlbumDetail = { album -> viewModel.openAlbumDetail(album) },
                        onOpenArtistDetail = { artist -> viewModel.openArtistDetail(artist) },
                        onSongsByGenre = { genre, callback -> viewModel.getSongsByGenre(genre, callback) },
                        onSongsByYear = { from, to, callback -> viewModel.getSongsByYearRange(from, to, callback) },
                        onLoadSongsFirstPage = { viewModel.loadSongsFirstPage() },
                        onLoadSongsNextPage = { viewModel.loadSongsNextPage() },
                        onLoadArtists = { viewModel.loadArtists() },
                        onLoadYears = { viewModel.loadYears() },
                        onLoadRecentSongs = { viewModel.loadRecentSongs() },
                        onSearch = { query -> viewModel.searchSongsOnServer(query) },
                        onClearSearch = { viewModel.clearSearch() },
                        playStatistics = viewModel.playStatistics.collectAsState(initial = com.nasmusic.tv.data.model.PlayStatistics()).value,
                        onClearPlayRecords = { viewModel.clearPlayRecords() },
                        activeTab = libraryActiveTab,
                        onTabSelected = { tab -> viewModel.selectLibraryTab(tab) },
                        // 播放列表
                        playlists = playlistsList,
                        playlistSongs = playlistSongsList,
                        isPlaylistLoading = isPlaylistLoading,
                        onSelectPlaylist = { playlist -> viewModel.selectPlaylist(playlist) },
                        onCreatePlaylist = { name -> viewModel.createPlaylist(name) },
                        onDeletePlaylist = { playlist -> viewModel.deletePlaylist(playlist) },
                        onPlayPlaylist = { playlist -> viewModel.playPlaylist(playlist) },
                        onRemoveFromPlaylist = { songId -> viewModel.removeFromPlaylist(songId) },
                        onLoadPlaylists = { viewModel.loadPlaylists() }
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
                Screen.Settings -> {
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
                        onOpenEqualizer = { viewModel.navigateTo(Screen.Equalizer) },
                        onChangeMetingApiBaseUrl = { viewModel.updateMetingApiBaseUrl(it) },
                        weatherApiKey = weatherApiKey,
                        onChangeWeatherApiKey = { viewModel.updateWeatherApiKey(it) },
                        spectrumEnabled = settings.spectrumEnabled,
                        onToggleSpectrum = { viewModel.updateSpectrumEnabled(it) },
                        visualizerTheme = settings.visualizerTheme,
                        onChangeVisualizerTheme = { viewModel.updateVisualizerTheme(it) },
                    // 封面滤镜设置
                    coverFilterEnabled = coverFilterEnabled,
                    coverFilterBlurRadius = coverFilterBlurRadius,
                    coverFilterDarkOverlay = coverFilterDarkOverlay,
                    onToggleCoverFilter = { viewModel.updateCoverFilterEnabled(it) },
                    onChangeCoverBlurRadius = { viewModel.updateCoverFilterBlurRadius(it) },
                    onChangeCoverDarkOverlay = { viewModel.updateCoverFilterDarkOverlay(it) }
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
                Screen.Network -> {
                    val networkSearchResultsState by viewModel.networkSearchResults.collectAsState(initial = UiState.Success(emptyList()))
                    val networkSearchKeyword by viewModel.networkSearchKeyword.collectAsState(initial = "")
                    val networkFavoriteSongs by viewModel.networkFavoriteSongs.collectAsState(initial = emptyList())
                    val networkFavoriteIds by viewModel.networkFavoriteIds.collectAsState(initial = emptySet())
                    val networkPlaylists by viewModel.networkPlaylists.collectAsState(initial = emptyList())
                    val queueSongIds by viewModel.queueSongIds.collectAsState(initial = emptySet())
                    val currentNetworkSubTab by viewModel.currentNetworkSubTab.collectAsState(initial = com.nasmusic.tv.data.model.NetworkSubTab.DISCOVER)
                    val currentMusicSource by viewModel.currentMusicSource.collectAsState(initial = com.nasmusic.tv.data.model.MusicSource.NETEASE)
                    val recentNetworkSongs by viewModel.recentNetworkSongs.collectAsState(initial = emptyList())
                    val currentNetworkSong by viewModel.currentNetworkSong.collectAsState(initial = null)
                    val isNetworkSearching = networkSearchResultsState is UiState.Loading
                    // 浏览状态
                    val browseSelections by viewModel.browseSelections.collectAsState(initial = emptyList())
                    val browseResults by viewModel.browseResults.collectAsState(initial = UiState.Success(emptyList()))
                    val isBrowseSearching by viewModel.isBrowseSearching.collectAsState(initial = false)
                    // 天气状态
                    val weatherData by viewModel.weatherData.collectAsState(initial = null)
                    val weatherRadioQueue by viewModel.weatherRadioQueue.collectAsState(initial = null)
                    val currentWeatherMood by viewModel.currentWeatherMood.collectAsState(initial = com.nasmusic.tv.data.model.WeatherMood.SUNNY)
                    val weatherLoading by viewModel.weatherLoading.collectAsState(initial = false)
                    val weatherError by viewModel.weatherError.collectAsState(initial = null)
                    NetworkMusicContainer(
                        currentSubTab = currentNetworkSubTab,
                        currentMusicSource = currentMusicSource,
                        searchKeyword = networkSearchKeyword,
                        searchResults = networkSearchResultsState.dataOrNull() ?: emptyList(),
                        isSearching = isNetworkSearching,
                        networkPlaylists = networkPlaylists,
                        networkFavoriteSongs = networkFavoriteSongs,
                        networkFavoriteIds = networkFavoriteIds,
                        queueSongIds = queueSongIds,
                        recentNetworkSongs = recentNetworkSongs,
                        currentNetworkSong = currentNetworkSong,
                        onSelectSubTab = { viewModel.selectNetworkSubTab(it) },
                        onSelectMusicSource = { viewModel.selectMusicSource(it) },
                        onSearch = { query -> viewModel.searchNetworkSongs(query) },
                        onClearSearch = { viewModel.clearNetworkSearch() },
                        onPlayNetworkSong = { song ->
                            viewModel.playNetworkSong(song)
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onToggleNetworkFavorite = { song -> viewModel.toggleNetworkFavorite(song) },
                        onToggleQueue = { song -> viewModel.toggleQueueSong(song) },
                        onPlayAllSongs = { songs ->
                            viewModel.playQueue(songs, 0)
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onPlayAllSearch = {
                            viewModel.playAllSearchResults()
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onLoadPlaylistDetail = { (playlist, songs) -> viewModel.loadPlaylistDetail(playlist.id, playlist.name) },
                        onNavigateToPlaylistDetail = { viewModel.navigateTo(Screen.NetworkPlaylistDetail) },
                        weatherData = weatherData,
                        weatherRadioQueue = weatherRadioQueue,
                        currentWeatherMood = currentWeatherMood,
                        weatherLoading = weatherLoading,
                        weatherError = weatherError,
                        weatherForecast = viewModel.weatherForecast.collectAsState(initial = emptyList()).value,
                        weatherIconCode = viewModel.weatherIconCode.collectAsState(initial = null).value,
                        onSwitchWeatherMood = { mood -> viewModel.switchWeatherMood(mood) },
                        onRefreshWeather = { viewModel.fetchWeather() },
                        onPlayWeatherAll = { viewModel.playWeatherRadioAll() },
                        // 浏览子 Tab
                        browseSelections = browseSelections,
                        browseResults = browseResults,
                        isBrowseSearching = isBrowseSearching,
                        onSelectBrowseOption = { dimIdx, optIdx ->
                            viewModel.selectBrowseOption(dimIdx, optIdx)
                        },
                        onRefreshBrowse = { viewModel.refreshBrowseSongs() },
                        onPlayAllBrowse = {
                            viewModel.playAllBrowseSongs()
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onRefreshCharts = { viewModel.refreshCharts() },
                        onNavigateToScreen = { action ->
                            when (action) {
                                "favorites" -> {
                                    viewModel.selectNetworkSubTab(com.nasmusic.tv.data.model.NetworkSubTab.DISCOVER)
                                }
                                "queue" -> viewModel.navigateTo(Screen.Queue)
                                "radio" -> viewModel.playPrivateRadio()
                            }
                        }
                    )
                }
                Screen.NetworkPlaylistDetail -> {
                    val playlistSongs by viewModel.playlistSongs.collectAsState(initial = emptyList())
                    val playlistTitle by viewModel.selectedPlaylistTitle.collectAsState(initial = "")
                    val networkFavoriteIds by viewModel.networkFavoriteIds.collectAsState(initial = emptySet())
                    val queueSongIds by viewModel.queueSongIds.collectAsState(initial = emptySet())
                    NetworkPlaylistDetailScreen(
                        playlistSongs = playlistSongs,
                        playlistTitle = playlistTitle,
                        onPlaySong = { song ->
                            viewModel.playNetworkSong(song)
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onPlayAll = {
                            viewModel.playQueue(playlistSongs, 0)
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        queueSongIds = queueSongIds,
                        onToggleQueue = { song -> viewModel.toggleQueueSong(song) },
                        networkFavoriteIds = networkFavoriteIds,
                        onToggleFavorite = { song -> viewModel.toggleNetworkFavorite(song) },
                        onBack = { viewModel.navigateTo(Screen.Network) }
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
        contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextSecondary,
        focusedContentColor = NasMusicColors.Primary,
        pressedScale = 0.96f
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = if (selected) 16.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

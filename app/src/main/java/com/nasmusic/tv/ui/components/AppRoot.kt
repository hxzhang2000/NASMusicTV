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
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.ui.LocalNavigateBackHandler
import com.nasmusic.tv.ui.screens.AlbumDetailScreen
import com.nasmusic.tv.ui.screens.ArtistDetailScreen
import com.nasmusic.tv.ui.screens.EqualizerScreen
import com.nasmusic.tv.ui.screens.LibraryScreen
import com.nasmusic.tv.ui.screens.NowPlayingScreen
import com.nasmusic.tv.ui.screens.PlaylistManagementScreen
import com.nasmusic.tv.ui.screens.QueueScreen
import com.nasmusic.tv.ui.screens.ServerConnectScreen
import com.nasmusic.tv.ui.screens.SettingsScreen
import com.nasmusic.tv.ui.theme.NASMusicTVTheme
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.ui.viewmodel.Screen
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppRoot(
    viewModel: MainViewModel,
    isImmersiveMode: androidx.compose.runtime.MutableState<Boolean>,
    onConnect: (ServerConfig) -> Unit
) {
    val currentScreen by viewModel.currentScreen.collectAsState(initial = Screen.Library)
    val currentSong by viewModel.currentSong.collectAsState(initial = null)
    val isPlaying by viewModel.isPlaying.collectAsState(initial = false)
    val playMode by viewModel.playMode.collectAsState(initial = com.nasmusic.tv.data.model.PlayMode.SEQUENTIAL)
    val progress by viewModel.progress.collectAsState(initial = 0L)
    val duration by viewModel.duration.collectAsState(initial = 0L)
    val lyrics by viewModel.currentLyrics.collectAsState(initial = null)
    val lyricsAvailability by viewModel.lyricsAvailability.collectAsState(initial = com.nasmusic.tv.data.model.LyricsAvailability())
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
    // Level 2: 根据当前屏幕和沉浸模式动态设置导航 BACK 键处理函数
    val navBackHandler = LocalNavigateBackHandler.current
    LaunchedEffect(currentScreen, isImmersiveMode.value) {
        val handler: (() -> Unit)? = when {
            isImmersiveMode.value -> {{ isImmersiveMode.value = false }}
            currentScreen != Screen.NowPlaying -> {{ viewModel.navigateTo(Screen.NowPlaying) }}
            else -> null
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
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (currentScreen) {
                Screen.NowPlaying -> {
                    NowPlayingScreen(
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        playMode = playMode,
                        progressMs = progress,
                        durationMs = duration,
                        lyrics = lyrics,
                        lyricsAvailability = lyricsAvailability,
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
                        // 网络歌曲调用 toggleNetworkFavorite，本地歌曲调用 toggleFavorite
                        onToggleFavorite = if (currentSong != null) {
                            {
                                val song = currentSong!!
                                if (song.isNetworkSong) viewModel.toggleNetworkFavorite(song)
                                else viewModel.toggleFavorite(song)
                            }
                        } else null
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
                    val songsPaging by viewModel.songsPaging.collectAsState(initial = com.nasmusic.tv.ui.viewmodel.SongsPagingState())
                    val searchResultsState by viewModel.searchResults.collectAsState(initial = UiState.Success(emptyList()))
                    val networkSearchResultsState by viewModel.networkSearchResults.collectAsState(initial = UiState.Success(emptyList()))
                    val networkSearchKeyword by viewModel.networkSearchKeyword.collectAsState(initial = "")
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
                        onPlayAllAlbums = {
                            if (songList.isNotEmpty()) {
                                viewModel.playQueue(songList)
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
                        networkSearchResults = networkSearchResultsState.dataOrNull() ?: emptyList(),
                        isNetworkSearching = networkSearchResultsState is UiState.Loading,
                        networkSearchKeyword = networkSearchKeyword,
                        onSearchNetwork = { query -> viewModel.searchNetworkSongs(query) },
                        onClearNetworkSearch = { viewModel.clearNetworkSearch() },
                        onPlayNetworkSong = { song ->
                            viewModel.playNetworkSong(song)
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        networkFavoriteSongs = viewModel.networkFavoriteSongs.collectAsState(initial = emptyList()).value,
                        networkFavoriteIds = viewModel.networkFavoriteIds.collectAsState(initial = emptySet()).value,
                        onToggleNetworkFavorite = { song -> viewModel.toggleNetworkFavorite(song) }
                    )
                }
                Screen.Queue -> {
                    QueueScreen(
                        queue = queue,
                        currentIndex = currentIndex,
                        currentSong = currentSong,
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
                        onChangeMetingApiBaseUrl = { viewModel.updateMetingApiBaseUrl(it) }
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
                        onBack = { viewModel.navigateTo(Screen.Settings) }
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

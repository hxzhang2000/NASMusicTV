package com.nasmusic.tv.ui.screens

import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.HighContrastColors
import com.nasmusic.tv.ui.theme.LocalHighContrast
import com.nasmusic.tv.ui.theme.LocalPhoneCompact
import com.nasmusic.tv.ui.components.AlbumSkeletonGrid
import com.nasmusic.tv.ui.components.ArtistSkeletonGrid

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.SearchHistoryItem
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.SearchField
import com.nasmusic.tv.ui.components.songGridColumns
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.data.model.SongsPagingState
import com.nasmusic.tv.ui.components.song.UnifiedSongRow
import com.nasmusic.tv.ui.components.song.SongRowMode
import com.nasmusic.tv.ui.screens.library.SearchTab
import com.nasmusic.tv.ui.screens.library.DiscoverTab
import com.nasmusic.tv.ui.screens.library.RadioTab

import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.util.PinyinUtils
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.clickable

enum class LibraryTab(val titleRes: Int) {
    SEARCH(R.string.library_search),
    DISCOVER(R.string.library_discover),
    ALBUMS(R.string.library_albums),
    ARTISTS(R.string.library_artists_alt),
    SONGS(R.string.library_songs),
    GENRES(R.string.library_genres),
    YEARS(R.string.library_years),
    RADIO(R.string.library_radio)
}

/**
 * 响应式网格列数：
 * - 宽度 >= 1000dp：TV / 大屏（保留原有列数）
 * - 600..1000dp：手机横屏 / 小平板（phoneLandscape）
 * - < 600dp：手机竖屏（phone）
 */
@Composable
private fun adaptiveColumns(tv: Int, phone: Int, phoneLandscape: Int = phone): Int {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp >= 1000 -> tv
        widthDp >= 600 -> phoneLandscape
        else -> phone
    }
}

@Composable
fun LibraryScreen(
    albums: List<Album>,
    songs: List<Song>,
    isLoading: Boolean,
    isConnected: Boolean = false,
    genres: List<Genre> = emptyList(),
    favoriteIds: Set<String> = emptySet(),
    artistSongsMap: Map<String, List<Song>> = emptyMap(),
    artists: List<Artist> = emptyList(),
    years: List<Int> = emptyList(),
    songsPaging: SongsPagingState = SongsPagingState(),
    searchResults: List<Song> = emptyList(),
    isSearching: Boolean = false,
    onPlayAlbum: (Album) -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlaySongs: (List<Song>) -> Unit,
    onPlayAllSongs: (List<Song>) -> Unit,
    // 队列切换
    queueSongIds: Set<String> = emptySet(),
    onToggleQueue: (Song) -> Unit = {},
    // 本地收藏切换（用于 SongsTab 等本地歌曲列表）
    onToggleFavorite: (Song) -> Unit = {},
    // 加入歌单（弹出歌单选择弹窗）
    onAddToPlaylist: (Song) -> Unit = {},
    onOpenAlbumDetail: ((Album) -> Unit)? = null,
    onOpenArtistDetail: ((String) -> Unit)? = null,
    onSongsByGenre: ((String, (List<Song>) -> Unit) -> Unit)? = null,
    onSongsByYear: ((Int, Int, (List<Song>) -> Unit) -> Unit)? = null,
    onLoadSongsFirstPage: () -> Unit = {},
    onLoadSongsNextPage: () -> Unit = {},
    onLoadArtists: () -> Unit = {},
    onLoadYears: () -> Unit = {},
    onSearch: (String) -> Unit = {},
    onClearSearch: () -> Unit = {},
    historyItems: List<SearchHistoryItem> = emptyList(),
    // 子 Tab 跨导航记忆（由 ViewModel 驱动）
    activeTab: LibraryTab = LibraryTab.ALBUMS,
    onTabSelected: (LibraryTab) -> Unit = {},
    // 搜索关键词（跨导航记忆，由 ViewModel 驱动）
    filterQuery: String = "",
    onFilterQueryChange: (String) -> Unit = {},
    // 搜索来源点亮状态（由 ViewModel 驱动）
    enabledSearchSources: Set<MusicSourceType> = emptySet(),
    onToggleSearchSource: (MusicSourceType) -> Unit = {},
    onEnableAllSearchSources: () -> Unit = {},
    // ── SEARCH Tab ──
    onSearchTabPlayAll: () -> Unit = {},
    onSearchTabAddAllToQueue: () -> Unit = {},
    // ── DISCOVER Tab ──
    discoverDimensions: List<com.nasmusic.tv.ui.screens.library.BrowseDimension> = emptyList(),
    discoverFilteredSongs: List<Song> = emptyList(),
    discoverIsLoading: Boolean = false,
    discoverCurrentDimensionValues: Map<String, String> = emptyMap(),
    onDiscoverDimensionChanged: (String, String) -> Unit = { _, _ -> },
    onDiscoverPlayAll: () -> Unit = {},
    onDiscoverAddAllToQueue: () -> Unit = {},
    onDiscoverShuffle: () -> Unit = {},
    onDiscoverEnsureLoaded: () -> Unit = {},
    // ── RADIO Tab ──
    radioStations: UiState<List<com.nasmusic.tv.data.model.RadioStation>> = UiState.Success(emptyList()),
    radioActiveTag: String? = null,
    radioActiveQuery: String = "",
    onLoadRadioDefault: () -> Unit = {},
    onLoadRadioTag: (String) -> Unit = {},
    onSearchRadio: (String) -> Unit = {},
    onPlayRadioStation: (com.nasmusic.tv.data.model.RadioStation) -> Unit = {},
    // Task 8: 滚动位置记忆（跨 Tab 切换保留）
    albumScrollIndex: Int = 0,
    albumScrollOffset: Int = 0,
    artistScrollIndex: Int = 0,
    artistScrollOffset: Int = 0,
    onAlbumScrollPositionChange: (Int, Int) -> Unit = { _, _ -> },
    onArtistScrollPositionChange: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var showSearchDialog by remember { mutableStateOf(false) }

    // Task 14: 首次曲库快捷键提示（3s 自动消失）
    val context = LocalContext.current
    val appPrefs = remember { com.nasmusic.tv.data.prefs.AppPreferences.getInstance(context) }
    val showShortcutHint by appPrefs.showLibraryShortcutHint.collectAsState(initial = true)
    var shortcutHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (showShortcutHint) {
            shortcutHintVisible = true
            kotlinx.coroutines.delay(3000)
            shortcutHintVisible = false
            appPrefs.setShowLibraryShortcutHint(false)
        }
    }

    // Task 8: 滚动位置记忆 — 从 ViewModel 恢复初始位置
    val albumListState = rememberLazyGridState(
        initialFirstVisibleItemIndex = albumScrollIndex,
        initialFirstVisibleItemScrollOffset = albumScrollOffset
    )
    val artistListState = rememberLazyGridState(
        initialFirstVisibleItemIndex = artistScrollIndex,
        initialFirstVisibleItemScrollOffset = artistScrollOffset
    )

    // 滚动位置变化时保存到 ViewModel（节流：仅在滚动停止后保存）
    LaunchedEffect(albumListState) {
        snapshotFlow { albumListState.firstVisibleItemIndex to albumListState.firstVisibleItemScrollOffset }
            .collect { pair: Pair<Int, Int> -> onAlbumScrollPositionChange(pair.first, pair.second) }
    }
    LaunchedEffect(artistListState) {
        snapshotFlow { artistListState.firstVisibleItemIndex to artistListState.firstVisibleItemScrollOffset }
            .collect { pair: Pair<Int, Int> -> onArtistScrollPositionChange(pair.first, pair.second) }
    }

    // Tab 切换时触发按需加载
    LaunchedEffect(activeTab) {
        when (activeTab) {
            LibraryTab.SONGS -> onLoadSongsFirstPage()
            LibraryTab.ARTISTS -> onLoadArtists()
            LibraryTab.YEARS -> onLoadYears()
            LibraryTab.RADIO -> onLoadRadioDefault()
            // DISCOVER：幂等加载（有暂存结果/加载中则跳过，切页不重搜）
            LibraryTab.DISCOVER -> onDiscoverEnsureLoaded()
            LibraryTab.SEARCH -> {}  // SearchTab handles its own loading
            else -> {}
        }
    }

    // 搜索时触发服务端搜索
    LaunchedEffect(filterQuery) {
        if (filterQuery.isNotBlank()) {
            onSearch(filterQuery)
        } else {
            onClearSearch()
        }
    }

    // 按当前 tab 类型过滤数据
    // ALBUMS Tab：搜索时用多源搜索结果按专辑聚合；无搜索时用本地加载的专辑
    val filteredAlbums by remember(filterQuery, albums, searchResults) {
        derivedStateOf {
            if (filterQuery.isBlank()) albums
            else {
                // 多源搜索结果中提取专辑（按 albumId 或 albumName 去重聚合）
                val searchAlbums = searchResults
                    .filter { it.album.isNotBlank() }
                    .groupBy { it.albumId ?: it.album.lowercase() }
                    .map { (_, songs) ->
                        val first = songs.first()
                        Album(
                            id = first.albumId ?: first.album,
                            name = first.album,
                            artist = first.artist,
                            songCount = songs.size,
                            year = first.year
                        )
                    }
                // 合并本地过滤的专辑（可能有多源搜索未覆盖的本地专辑）
                val localFiltered = albums.filter {
                    PinyinUtils.matches(it.name, filterQuery) || PinyinUtils.matches(it.artist, filterQuery)
                }
                // 去重合并：同名同艺术家的只保留一个
                val seen = mutableSetOf<String>()
                (localFiltered + searchAlbums).filter { album ->
                    val key = "${album.name.lowercase()}:${album.artist.lowercase()}"
                    seen.add(key)
                }
            }
        }
    }
    // SONGS Tab：有搜索结果时用搜索结果（SearchType.SONG_NAME_ONLY 过滤后的），否则用分页数据
    val displaySongs by remember(filterQuery, songsPaging.songs, searchResults) {
        derivedStateOf {
            if (filterQuery.isNotBlank()) searchResults
            else songsPaging.songs
        }
    }
    // ARTISTS Tab：搜索时用多源搜索结果按艺术家聚合；无搜索时用本地加载的艺术家
    val filteredArtists by remember(filterQuery, artists, searchResults) {
        derivedStateOf {
            if (filterQuery.isBlank()) artists
            else {
                // 多源搜索结果中提取艺术家（按艺术家名去重）
                val searchArtists = searchResults
                    .groupBy { it.artist.lowercase() }
                    .map { (_, songs) ->
                        val artistName = songs.first().artist
                        Artist(id = artistName.lowercase(), name = artistName, songCount = songs.size)
                    }
                // 合并本地过滤的艺术家
                val localFiltered = artists.filter {
                    PinyinUtils.matches(it.name, filterQuery)
                }
                val seen = mutableSetOf<String>()
                (localFiltered + searchArtists).filter { artist ->
                    val key = artist.name.lowercase()
                    seen.add(key)
                }
            }
        }
    }

    // 艺术家搜索时的歌曲映射：多源搜索结果按艺术家分组，与本地 artistSongsMap 合并
    val displayArtistSongsMap by remember(filterQuery, searchResults, artistSongsMap) {
        derivedStateOf {
            if (filterQuery.isBlank()) artistSongsMap
            else {
                val searchMap = searchResults.groupBy { it.artist }
                // 合并：本地数据 + 搜索结果
                val merged = artistSongsMap.toMutableMap()
                for ((artist, songs) in searchMap) {
                    val existing = merged[artist].orEmpty()
                    // 去重合并
                    val existingIds = existing.map { it.id }.toSet()
                    merged[artist] = existing + songs.filter { it.id !in existingIds }
                }
                merged
            }
        }
    }

    // 播放全部按钮的歌曲列表：按当前 Tab + 搜索状态动态计算
    val playAllSongs by remember(activeTab, filterQuery, songs, displaySongs, searchResults, filteredAlbums, filteredArtists, displayArtistSongsMap) {
        derivedStateOf {
            when (activeTab) {
                LibraryTab.ALBUMS -> {
                    if (filterQuery.isNotBlank()) {
                        val filteredAlbumIds = filteredAlbums.map { it.id }.toSet()
                        val albumSongs = songs.filter { it.albumId in filteredAlbumIds }
                        if (albumSongs.isNotEmpty()) albumSongs else searchResults
                    } else {
                        songs
                    }
                }
                LibraryTab.ARTISTS -> {
                    val listed = filteredArtists.flatMap { displayArtistSongsMap[it.name].orEmpty() }
                    if (listed.isNotEmpty()) listed
                    else if (searchResults.isNotEmpty()) searchResults
                    else songs
                }
                LibraryTab.SONGS -> displaySongs
                LibraryTab.SEARCH, LibraryTab.DISCOVER, LibraryTab.RADIO -> songs
                else -> songs  // GENRES, YEARS
            }
        }
    }
    val showPlayAll = activeTab != LibraryTab.SEARCH && activeTab != LibraryTab.DISCOVER && activeTab != LibraryTab.RADIO && playAllSongs.isNotEmpty()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
            // 顶部标题 + TAB + 播放全部
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.nav_library),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.display(),
                    modifier = Modifier.padding(end = 24.dp)
                )

                // TAB 切换（可横向滑动——手机窄屏滑动浏览全部 tab）
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LibraryTab.values().forEach { tab ->
                        val selected = tab == activeTab
                        FocusableSurface(
                            onClick = { onTabSelected(tab) },
                            modifier = Modifier.padding(horizontal = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            focusedScale = 1.05f,
                            animationDurationMs = 200,
                            containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.2f) else Color.Transparent,
                            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                            contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                            focusedContentColor = NasMusicColors.Primary
                        ) {
                            Text(
                                text = stringResource(tab.titleRes),
                                color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                                fontSize = FontSize.button(),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // 搜索栏 + 播放全部（固定宽度区，避免挤压可滚动 TAB）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    SearchField(
                        query = filterQuery,
                        placeholder = stringResource(R.string.library_search_placeholder),
                        onOpenSearch = { showSearchDialog = true },
                        onClear = { onFilterQueryChange("") },
                        modifier = Modifier.width(240.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    if (showPlayAll) {
                        Box(modifier = Modifier.widthIn(min = 80.dp)) {
                            ButtonChip(
                                text = stringResource(R.string.common_play_all),
                                onClick = { onPlayAllSongs(playAllSongs) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 内容区域（weight(1f) 限制高度，让内部可滚动列表正常工作）
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // SEARCH, DISCOVER, RADIO tabs handle their own loading/empty states
            when (activeTab) {
                LibraryTab.SEARCH -> {
                    SearchTab(
                        searchKeyword = filterQuery,
                        searchResults = searchResults,
                        isSearching = isSearching,
                        favoriteIds = favoriteIds,
                        queueSongIds = queueSongIds,
                        enabledSources = enabledSearchSources,
                        onToggleSource = onToggleSearchSource,
                        onEnableAllSources = onEnableAllSearchSources,
                        onPlaySong = onPlaySong,
                        onToggleFavorite = onToggleFavorite,
                        onToggleQueue = onToggleQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        onPlayAll = onSearchTabPlayAll,
                        onAddAllToQueue = onSearchTabAddAllToQueue
                    )
                }
                LibraryTab.DISCOVER -> {
                    DiscoverTab(
                        dimensions = discoverDimensions,
                        filteredSongs = discoverFilteredSongs,
                        isLoading = discoverIsLoading,
                        favoriteIds = favoriteIds,
                        queueSongIds = queueSongIds,
                        currentDimensionValues = discoverCurrentDimensionValues,
                        onDimensionChanged = onDiscoverDimensionChanged,
                        onPlayAll = onDiscoverPlayAll,
                        onShuffle = onDiscoverShuffle,
                        onAddAllToQueue = onDiscoverAddAllToQueue,
                        onPlaySong = onPlaySong,
                        onToggleFavorite = onToggleFavorite,
                        onToggleQueue = onToggleQueue,
                        onAddToPlaylist = onAddToPlaylist
                    )
                }
                LibraryTab.RADIO -> {
                    RadioTab(
                        radioStations = radioStations,
                        radioActiveTag = radioActiveTag,
                        radioActiveQuery = radioActiveQuery,
                        onLoadDefault = onLoadRadioDefault,
                        onLoadTag = onLoadRadioTag,
                        onSearch = onSearchRadio,
                        onPlayStation = onPlayRadioStation
                    )
                }
                else -> {
                    // 专辑/艺术家/歌曲 tab 搜索时有结果直接展示（多源搜索，不依赖 NAS 连接）
                    val hasSearchResults = filterQuery.isNotBlank() && searchResults.isNotEmpty()
                    val showSearchContent = hasSearchResults && (
                        activeTab == LibraryTab.ALBUMS ||
                        activeTab == LibraryTab.ARTISTS ||
                        activeTab == LibraryTab.SONGS
                    )
                    if (showSearchContent) {
                        // 搜索模式：直接展示搜索结果，跳过加载/连接/空状态检查
                        when (activeTab) {
                            LibraryTab.ALBUMS -> AlbumsTab(
                                albums = filteredAlbums,
                                songs = searchResults,
                                onPlayAlbum = onPlayAlbum,
                                onOpenAlbumDetail = onOpenAlbumDetail,
                                listState = albumListState
                            )
                            LibraryTab.ARTISTS -> ArtistsTab(
                                artists = filteredArtists,
                                artistSongsMap = displayArtistSongsMap,
                                onPlaySongs = onPlaySongs,
                                onOpenArtistDetail = onOpenArtistDetail,
                                listState = artistListState
                            )
                            LibraryTab.SONGS -> SongsTab(
                                songs = displaySongs,
                                favoriteIds = favoriteIds,
                                songsPaging = songsPaging,
                                isSearching = isSearching,
                                onLoadMore = onLoadSongsNextPage,
                                onPlaySong = onPlaySong,
                                queueSongIds = queueSongIds,
                                onToggleQueue = onToggleQueue,
                                onToggleFavorite = onToggleFavorite,
                                onAddToPlaylist = onAddToPlaylist
                            )
                            else -> {}
                        }
                    } else if (isLoading) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // 顶部骨架标题条
                            Box(
                                modifier = Modifier
                                    .width(180.dp)
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NasMusicColors.SurfaceVariant)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            // 按 Tab 类型显示对应骨架
                            when (activeTab) {
                                LibraryTab.ALBUMS -> AlbumSkeletonGrid()
                                LibraryTab.ARTISTS -> ArtistSkeletonGrid()
                                else -> AlbumSkeletonGrid()  // 默认专辑骨架
                            }
                        }

                    } else {
                        // 有数据时直接展示（无论 NAS 是否连接，本地/百度/网络数据同样展示）
                        when (activeTab) {
                            LibraryTab.ALBUMS -> if (filteredAlbums.isEmpty()) {
                                EmptyHint(isConnected = isConnected, tab = LibraryTab.ALBUMS)
                            } else {
                                AlbumsTab(
                                    albums = filteredAlbums,
                                    songs = songs,
                                    onPlayAlbum = onPlayAlbum,
                                    onOpenAlbumDetail = onOpenAlbumDetail,
                                    listState = albumListState
                                )
                            }
                            LibraryTab.ARTISTS -> if (filteredArtists.isEmpty()) {
                                EmptyHint(isConnected = isConnected, tab = LibraryTab.ARTISTS)
                            } else {
                                ArtistsTab(
                                    artists = filteredArtists,
                                    artistSongsMap = displayArtistSongsMap,
                                    onPlaySongs = onPlaySongs,
                                    onOpenArtistDetail = onOpenArtistDetail,
                                    listState = artistListState
                                )
                            }
                            LibraryTab.SONGS -> if (displaySongs.isEmpty() && songsPaging.songs.isEmpty()) {
                                EmptyHint(isConnected = isConnected, tab = LibraryTab.SONGS)
                            } else {
                                SongsTab(
                                    songs = displaySongs,
                                    favoriteIds = favoriteIds,
                                    songsPaging = songsPaging,
                                    isSearching = isSearching,
                                    onLoadMore = onLoadSongsNextPage,
                                    onPlaySong = onPlaySong,
                                    queueSongIds = queueSongIds,
                                    onToggleQueue = onToggleQueue,
                                    onToggleFavorite = onToggleFavorite,
                                    onAddToPlaylist = onAddToPlaylist
                                )
                            }
                            LibraryTab.GENRES -> GenresTab(
                                genres = genres,
                                onSongsByGenre = onSongsByGenre,
                                onPlaySongs = onPlaySongs
                            )
                            LibraryTab.YEARS -> YearsTab(
                                years = years,
                                onSongsByYear = onSongsByYear,
                                onPlaySongs = onPlaySongs
                            )
                            else -> {}  // SEARCH, DISCOVER, RADIO handled above
                        }
                    }
                }
            }

                // Task 14: 遥控器快捷键提示浮层（3s 自动消失）
                androidx.compose.animation.AnimatedVisibility(
                    visible = shortcutHintVisible,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(NasMusicColors.SurfaceVariant)
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "◀ ▶ 导航  |  ◀◀ ▶▶ 切歌  |  确认 播放  |  长按 菜单  |  侧边索引 跳转",
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.button(),
                            maxLines = 1
                        )
                    }
                }
            }   // AnimatedVisibility

        }   // 内容区域 Box

        // 搜索键盘弹窗
        if (showSearchDialog) {
            TextInputDialog(
                title = stringResource(R.string.common_search),
                hint = stringResource(R.string.library_search_hint),
                initialValue = filterQuery,
                onConfirm = { query ->
                    onFilterQueryChange(query)
                    showSearchDialog = false
                    // 只在非搜索 tab 时跳到搜索 tab；其他 tab 留在当前页做维度搜索
                    if (activeTab != LibraryTab.SEARCH &&
                        activeTab != LibraryTab.ALBUMS &&
                        activeTab != LibraryTab.ARTISTS &&
                        activeTab != LibraryTab.SONGS) {
                        onTabSelected(LibraryTab.SEARCH)
                    }
                },
                onDismiss = { showSearchDialog = false },
                showQrCode = true,
                showHistory = true,
                historyItems = historyItems,
                onHistorySelect = { query ->
                    onFilterQueryChange(query)
                    showSearchDialog = false
                    if (activeTab != LibraryTab.SEARCH &&
                        activeTab != LibraryTab.ALBUMS &&
                        activeTab != LibraryTab.ARTISTS &&
                        activeTab != LibraryTab.SONGS) {
                        onTabSelected(LibraryTab.SEARCH)
                    }
                }
            )
        }

    }
}

@Composable
private fun AlbumsTab(
    albums: List<Album>,
    songs: List<Song>,
    onPlayAlbum: (Album) -> Unit,
    onOpenAlbumDetail: ((Album) -> Unit)? = null,
    listState: LazyGridState = rememberLazyGridState()
) {
    val firstItemFocusRequester = remember { FocusRequester() }
    val letterFocusRequester = remember { FocusRequester() }
    var focusedGridIndex by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // Level 1.5: 列表已滚动时按 BACK 先回顶并聚焦第一个
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            if (!atTop) {
                scope.launch {
                    listState.scrollToItem(0)
                    runCatching { firstItemFocusRequester.requestFocus() }
                }
                true
            } else {
                false
            }
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

    // A-Z 分组：按首字母分组，保留组内排序
    val groupedItems = remember(albums) {
        val items = mutableListOf<Pair<Char?, Album>>() // null = header
        albums.groupBy { PinyinUtils.getGroupLetter(it.name) }
            .toSortedMap(compareBy { if (it == '#') '{' else it })
            .forEach { (letter, groupAlbums) ->
                items.add(letter to groupAlbums.first()) // header 标记：用第一个 album 的 letter
                groupAlbums.forEach { album ->
                    items.add(null to album) // null key = 数据行
                }
            }
        items
    }

    // 分组 header 的 index 映射（letter → grid index），供侧边索引用
    val groupHeaderIndices = remember(groupedItems) {
        val map = mutableMapOf<Char, Int>()
        var gridIndex = 0
        groupedItems.forEach { (letter, album) ->
            if (letter != null) {
                map[letter] = gridIndex
            }
            gridIndex++
        }
        map
    }

    Column {
        Text(
            text = stringResource(R.string.library_albums_count, albums.size),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.subtitle(),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Box(modifier = Modifier.fillMaxSize()) {
            // Task 11: 横向滚动边缘渐变提示
            val canScrollHorizontally by remember {
                derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                            val items = listState.layoutInfo.visibleItemsInfo
                            val info = items.firstOrNull { it.index == focusedGridIndex }
                            val lastColumn = items.maxOfOrNull { it.column } ?: 0
                            if (info != null && info.column == lastColumn) {
                                letterFocusRequester.requestFocus()
                                true
                            } else false
                        } else false
                    }
            ) {
                LazyVerticalGrid(
                    state = listState,
                    columns = GridCells.Fixed(adaptiveColumns(6, 3, 6)),
                    modifier = Modifier.fillMaxSize().padding(end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    groupedItems.forEachIndexed { index, (letter, album) ->
                        if (letter != null) {
                            // 分组 header：横跨整行
                            item(key = "header_$letter", span = { GridItemSpan(maxLineSpan) }) {
                                Column {
                                    Text(
                                        text = letter.toString(),
                                        color = NasMusicColors.Primary,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                                    )
                                    val dividerThickness = if (LocalHighContrast.current) 2.dp else 1.dp
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(dividerThickness)
                                            .background(if (LocalHighContrast.current) HighContrastColors.BorderStrong else NasMusicColors.Border)
                                    )
                                }
                            }
                        }
                        // 数据行（key 加 index 防重名：同名专辑可来自多个源）
                        item(key = "album_${index}_${album.id}", span = { GridItemSpan(1) }) {
                            Box(Modifier.onFocusChanged { if (it.isFocused) focusedGridIndex = index }) {
                                AlbumCard(
                                    album = album,
                                    onClick = { onOpenAlbumDetail?.invoke(album) ?: onPlayAlbum(album) },
                                    onPlay = { onPlayAlbum(album) },
                                    focusRequester = if (index == 1) firstItemFocusRequester else null
                                )
                            }
                        }
                    }
                }
            }
            // 侧边 A-Z 索引条
            val activeLetters = remember(groupedItems) {
                groupedItems.mapNotNull { (letter, _) -> letter }.toSet()
            }
            val currentLetter by remember {
                derivedStateOf {
                    listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key?.let { key ->
                        if (key is String && key.startsWith("header_")) key.removePrefix("header_").firstOrNull() else null
                    }
                }
            }
            SideLetterIndex(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
                currentLetter = currentLetter,
                activeLetters = activeLetters,
                contentFocusRequester = firstItemFocusRequester,
                letterFocusRequester = letterFocusRequester,
                onLetterSelect = { letter ->
                    groupHeaderIndices[letter]?.let { idx ->
                        scope.launch { listState.scrollToItem(idx) }
                    }
                }
            )
        }
    }
}

@Composable
private fun ArtistsTab(
    artists: List<Artist>,
    artistSongsMap: Map<String, List<Song>> = emptyMap(),
    onPlaySongs: (List<Song>) -> Unit,
    onOpenArtistDetail: ((String) -> Unit)? = null,
    listState: LazyGridState = rememberLazyGridState()
) {
    val firstItemFocusRequester = remember { FocusRequester() }
    val letterFocusRequester = remember { FocusRequester() }
    var focusedGridIndex by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // Level 1.5: 列表已滚动时按 BACK 先回顶并聚焦第一个
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            if (!atTop) {
                scope.launch {
                    listState.scrollToItem(0)
                    runCatching { firstItemFocusRequester.requestFocus() }
                }
                true
            } else {
                false
            }
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

    // A-Z 分组：按首字母分组，保留组内排序
    val groupedItems = remember(artists) {
        val items = mutableListOf<Pair<Char?, Artist>>() // null = header
        artists.groupBy { PinyinUtils.getGroupLetter(it.name) }
            .toSortedMap(compareBy { if (it == '#') '{' else it })
            .forEach { (letter, groupArtists) ->
                items.add(letter to groupArtists.first()) // header 标记
                groupArtists.forEach { artist ->
                    items.add(null to artist) // null key = 数据行
                }
            }
        items
    }

    // 分组 header 的 index 映射（letter → grid index），供侧边索引用
    val groupHeaderIndices = remember(groupedItems) {
        val map = mutableMapOf<Char, Int>()
        var gridIndex = 0
        groupedItems.forEach { (letter, artist) ->
            if (letter != null) {
                map[letter] = gridIndex
            }
            gridIndex++
        }
        map
    }

    Column {
        Text(
            text = stringResource(R.string.library_artists_count, artists.size),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.subtitle(),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Box(modifier = Modifier.fillMaxSize()) {
            // Task 11: 横向滚动边缘渐变提示
            val canScrollHorizontally by remember {
                derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                            val items = listState.layoutInfo.visibleItemsInfo
                            val info = items.firstOrNull { it.index == focusedGridIndex }
                            val lastColumn = items.maxOfOrNull { it.column } ?: 0
                            if (info != null && info.column == lastColumn) {
                                letterFocusRequester.requestFocus()
                                true
                            } else false
                        } else false
                    }
            ) {
                LazyVerticalGrid(
                    state = listState,
                    columns = GridCells.Fixed(adaptiveColumns(6, 3, 6)),
                    modifier = Modifier.fillMaxSize().padding(end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    groupedItems.forEachIndexed { index, (letter, artist) ->
                        if (letter != null) {
                            // 分组 header：横跨整行
                            item(key = "header_$letter", span = { GridItemSpan(maxLineSpan) }) {
                                Column {
                                    Text(
                                        text = letter.toString(),
                                        color = NasMusicColors.Primary,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                                    )
                                    val dividerThickness = if (LocalHighContrast.current) 2.dp else 1.dp
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(dividerThickness)
                                            .background(if (LocalHighContrast.current) HighContrastColors.BorderStrong else NasMusicColors.Border)
                                    )
                                }
                            }
                        }
                        // 数据行（key 加 index 防重名）
                        item(key = "artist_${index}_${artist.id}", span = { GridItemSpan(1) }) {
                            val artistSongs = artistSongsMap[artist.name] ?: emptyList()
                            val songCount = artistSongs.size
                            // Task 10: 计算专辑数和主要流派
                            val albumCountForArtist = artist.albumCount
                            val primaryGenreForArtist = remember(artistSongs) {
                                artistSongs.mapNotNull { it.genre }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                            }
                            Box(Modifier.onFocusChanged { if (it.isFocused) focusedGridIndex = index }) {
                                ArtistCard(
                                    artist = artist.name,
                                    coverUrl = artist.coverUrl,
                                    songCount = songCount,
                                    albumCount = albumCountForArtist,
                                    primaryGenre = primaryGenreForArtist,
                                    onClick = {
                                        if (onOpenArtistDetail != null) {
                                            onOpenArtistDetail(artist.name)
                                        } else if (artistSongs.isNotEmpty()) {
                                            onPlaySongs(artistSongs)
                                        }
                                    },
                                    onPlay = if (artistSongs.isNotEmpty()) {{ onPlaySongs(artistSongs) }} else null,
                                    focusRequester = if (index == 1) firstItemFocusRequester else null
                                )
                            }
                        }
                    }
                }
            }
            // 侧边 A-Z 索引条
            val activeLetters = remember(groupedItems) {
                groupedItems.mapNotNull { (letter, _) -> letter }.toSet()
            }
            val currentLetter by remember {
                derivedStateOf {
                    listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key?.let { key ->
                        if (key is String && key.startsWith("header_")) key.removePrefix("header_").firstOrNull() else null
                    }
                }
            }
            SideLetterIndex(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
                currentLetter = currentLetter,
                activeLetters = activeLetters,
                contentFocusRequester = firstItemFocusRequester,
                letterFocusRequester = letterFocusRequester,
                onLetterSelect = { letter ->
                    groupHeaderIndices[letter]?.let { idx ->
                        scope.launch { listState.scrollToItem(idx) }
                    }
                }
            )
        }
    }
}

@Composable
private fun SongsTab(
    songs: List<Song>,
    favoriteIds: Set<String> = emptySet(),
    songsPaging: SongsPagingState = SongsPagingState(),
    isSearching: Boolean = false,
    onLoadMore: () -> Unit = {},
    onPlaySong: (Song) -> Unit,
    queueSongIds: Set<String> = emptySet(),
    onToggleQueue: (Song) -> Unit = {},
    onToggleFavorite: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {}
) {
    val listState = rememberLazyGridState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // Level 1.5: 列表已滚动时按 BACK 先回顶并聚焦第一个
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            if (!atTop) {
                scope.launch {
                    listState.scrollToItem(0)
                    runCatching { firstItemFocusRequester.requestFocus() }
                }
                true
            } else {
                false
            }
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

    // 检测是否滚动接近底部，触发加载更多
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = songs.size
            // 提前 20 项加载下一页
            totalItems > 0 && lastVisibleIndex >= totalItems - 20 &&
                    songsPaging.hasMore && !songsPaging.isLoading && !isSearching
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    Column {
        // 标题显示加载进度
        val titleText = if (isSearching) {
            stringResource(R.string.library_searching)
        } else if (songsPaging.totalCount > 0) {
            stringResource(R.string.library_songs_count_with_total, songs.size, songsPaging.totalCount)
        } else if (songsPaging.isLoading) {
            stringResource(R.string.library_songs_loading)
        } else {
            stringResource(R.string.library_songs_count, songs.size)
        }
        Text(
            text = titleText,
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.subtitle(),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (songs.isEmpty() && !songsPaging.isLoading && !isSearching) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.library_no_songs),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.button()
                )
            }
        } else {
            LazyVerticalGrid(
                state = listState,
                columns = songGridColumns(),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(songs, key = { _, it -> it.id }) { index, song ->
                    UnifiedSongRow(
                        song = song,
                        onClick = { onPlaySong(song) },
                        mode = SongRowMode.MODE_ROW,
                        index = index,
                        isFavorited = song.id in favoriteIds,
                        onToggleFavorite = { onToggleFavorite(song) },
                        isInQueue = song.id in queueSongIds,
                        onToggleQueue = { onToggleQueue(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        focusRequester = if (index == 0) firstItemFocusRequester else null
                    )
                }
                // 底部加载指示器
                if (songsPaging.isLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.library_load_more),
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.button()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenresTab(
    genres: List<Genre>,
    onSongsByGenre: ((String, (List<Song>) -> Unit) -> Unit)? = null,
    onPlaySongs: (List<Song>) -> Unit
) {
    val listState = rememberLazyGridState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // Level 1.5: 列表已滚动时按 BACK 先回顶并聚焦第一个
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            if (!atTop) {
                scope.launch {
                    listState.scrollToItem(0)
                    runCatching { firstItemFocusRequester.requestFocus() }
                }
                true
            } else {
                false
            }
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

    Column {
        Text(
            text = stringResource(R.string.library_genres_count, genres.size),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.subtitle(),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (genres.isEmpty()) {
            Text(
                text = stringResource(R.string.library_no_genres),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(top = 24.dp)
            )
        } else {
            LazyVerticalGrid(
                state = listState,
                columns = GridCells.Fixed(adaptiveColumns(4, 2, 3)),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(genres, key = { _, it -> it.name }) { index, genre ->
                    FocusableSurface(
                        onClick = {
                            if (onSongsByGenre != null) {
                                onSongsByGenre(genre.name) { songs ->
                                    if (songs.isNotEmpty()) onPlaySongs(songs)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.06f,
                        animationDurationMs = 200,
                        containerColor = NasMusicColors.Surface,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.Primary,
                        focusRequester = if (index == 0) firstItemFocusRequester else null
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = genre.name,
                                color = NasMusicColors.TextPrimary,
                                fontSize = FontSize.button(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.library_song_count_suffix, genre.songCount),
                                color = LocalFocusableContentColor.current,
                                fontSize = FontSize.body()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YearsTab(
    years: List<Int>,
    onSongsByYear: ((Int, Int, (List<Song>) -> Unit) -> Unit)? = null,
    onPlaySongs: (List<Song>) -> Unit
) {
    val listState = rememberLazyGridState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // Level 1.5: 列表已滚动时按 BACK 先回顶并聚焦第一个
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            if (!atTop) {
                scope.launch {
                    listState.scrollToItem(0)
                    runCatching { firstItemFocusRequester.requestFocus() }
                }
                true
            } else {
                false
            }
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

    Column {
        Text(
            text = stringResource(R.string.library_years_count, years.size),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.subtitle(),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (years.isEmpty()) {
            Text(
                text = stringResource(R.string.library_no_years),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(top = 24.dp)
            )
        } else {
            LazyVerticalGrid(
                state = listState,
                columns = GridCells.Fixed(adaptiveColumns(5, 2, 3)),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(years, key = { _, it -> it }) { index, year ->
                    FocusableSurface(
                        onClick = {
                            // 点击年份时按需加载该年份歌曲
                            if (onSongsByYear != null) {
                                onSongsByYear(year, year) { songs ->
                                    if (songs.isNotEmpty()) onPlaySongs(songs)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.06f,
                        animationDurationMs = 200,
                        containerColor = NasMusicColors.Surface,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.Primary,
                        focusRequester = if (index == 0) firstItemFocusRequester else null
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$year",
                                color = NasMusicColors.TextPrimary,
                                fontSize = FontSize.title()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.library_tap_to_play),
                                color = LocalFocusableContentColor.current,
                                fontSize = FontSize.body()
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.06f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary,
        pressedContainerColor = NasMusicColors.Background,
        focusRequester = focusRequester
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(NasMusicColors.SurfaceVariant)
            ) {
                if (!album.coverUrl.isNullOrBlank()) {
                    AsyncImage(model = album.coverUrl, contentDescription = album.name, modifier = Modifier.fillMaxSize())
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "♪", color = LocalFocusableContentColor.current, fontSize = FontSize.displayLarge())
                    }
                }
                // Task 9: 右上角来源徽标
                if (album.sourceType != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(album.sourceType.color.copy(alpha = 0.9f), shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = album.sourceType.displayName, color = Color.White, fontSize = 9.sp)
                    }
                } else {
                    // 无来源时仍显示歌曲数
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(NasMusicColors.Primary.copy(alpha = 0.95f), shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = stringResource(R.string.library_song_count_short, album.songCount), color = NasMusicColors.TextPrimary, fontSize = FontSize.small())
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = album.name, color = NasMusicColors.TextPrimary, fontSize = FontSize.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Task 9: 年份 + 流派行
            val infoText = buildString {
                if (album.year != null) append(album.year.toString())
                if (!album.genre.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(album.genre)
                }
            }
            if (infoText.isNotEmpty()) {
                Text(text = infoText, color = NasMusicColors.TextSecondary, fontSize = FontSize.caption(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = album.artist.ifBlank { "—" }, color = LocalFocusableContentColor.current, fontSize = FontSize.small(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (onPlay != null) {
                    Text(text = "▶" + stringResource(R.string.player_play), color = NasMusicColors.Primary, fontSize = FontSize.small(), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ArtistCard(
    artist: String,
    coverUrl: String? = null,
    songCount: Int,
    albumCount: Int = 0,
    primaryGenre: String? = null,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.06f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary,
        pressedContainerColor = NasMusicColors.Background,
        focusRequester = focusRequester
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NasMusicColors.SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = artist,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = artist.firstOrNull()?.uppercase() ?: "?",
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.displayLarge()
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = artist, color = NasMusicColors.TextPrimary, fontSize = FontSize.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Task 10: 专辑数 + 歌曲数
            val countText = buildString {
                if (albumCount > 0) append(stringResource(R.string.library_album_count_short, albumCount))
                if (songCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(stringResource(R.string.library_song_count_short, songCount))
                }
            }
            if (countText.isNotEmpty()) {
                Text(text = countText, color = NasMusicColors.TextSecondary, fontSize = FontSize.small(), maxLines = 1)
            }
            // Task 10: 流派标签
            if (!primaryGenre.isNullOrBlank()) {
                Text(text = primaryGenre, color = NasMusicColors.TextSecondary.copy(alpha = 0.7f), fontSize = FontSize.caption(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onPlay != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "▶" + stringResource(R.string.player_play), color = NasMusicColors.Primary, fontSize = FontSize.small())
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ButtonChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.08f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Primary,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.95f
    ) {
        Text(text = text, color = NasMusicColors.TextPrimary, fontSize = FontSize.button(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/**
 * 库内容为空时的提示组件
 *
 * 根据连接状态和可用数据源显示不同提示：
 * - 未连接 NAS 且无任何数据源 → 提示连接服务器或扫描本地音乐
 * - 已连接 NAS 但库为空 → 提示库为空
 */
@Composable
private fun EmptyHint(isConnected: Boolean, tab: LibraryTab = LibraryTab.ALBUMS) {
    val (icon, title, subtitle) = if (!isConnected) {
        Triple(
            Icons.Default.CloudOff,
            stringResource(R.string.common_not_connected),
            stringResource(R.string.library_connect_or_local_hint)
        )
    } else when (tab) {
        LibraryTab.ARTISTS -> Triple(
            Icons.Default.Person,
            stringResource(R.string.library_empty_artists),
            stringResource(R.string.library_empty_artists_hint)
        )
        LibraryTab.SONGS -> Triple(
            Icons.Default.MusicNote,
            stringResource(R.string.library_empty_songs),
            stringResource(R.string.library_empty_songs_hint)
        )
        else -> Triple(
            Icons.Default.Album,
            stringResource(R.string.library_empty_albums),
            stringResource(R.string.library_empty_albums_hint)
        )
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = NasMusicColors.TextSecondary.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.title()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                color = NasMusicColors.TextSecondary.copy(alpha = 0.7f),
                fontSize = FontSize.button()
            )
        }
    }
}

/**
 * 侧边 A-Z 索引条
 *
 * - 触摸：拖拽选择字母，松手回弹
 * - 遥控器：聚焦后上下键选择，确认键跳转
 * - currentLetter：当前可见区域的首字母，高亮显示
 * - activeLetters：实际有数据的字母集合，其余灰显
 * - onLetterSelect：点击/拖拽到某字母时回调，调用方负责 scroll
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SideLetterIndex(
    currentLetter: Char?,
    activeLetters: Set<Char>,
    onLetterSelect: (Char) -> Unit,
    contentFocusRequester: FocusRequester,
    letterFocusRequester: FocusRequester = remember { FocusRequester() },
    modifier: Modifier = Modifier
) {
    val allLetters = remember { PinyinUtils.getAllGroupLetters() }
    var focusedLetter by remember { mutableStateOf<Char?>(null) }

    // 手机横屏时字母排不下，用更小尺寸 + 可滚动
    val isPhone = LocalPhoneCompact.current
    val letterSize = if (isPhone) 14.dp else 20.dp
    val letterFontSize = if (isPhone) 7.sp else 10.sp
    val scrollState = rememberScrollState()

    // 固定每项高度，使触摸映射精确（不再依赖 SpaceBetween 坐标计算）
    val itemHeight = letterSize

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(letterSize + 8.dp)
            .padding(end = 4.dp)
            .then(if (isPhone) Modifier.verticalScroll(scrollState) else Modifier)
            .focusRequester(letterFocusRequester)
            .focusTarget()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val current = focusedLetter ?: currentLetter ?: return@onKeyEvent false
                val idx = allLetters.indexOf(current)
                when (event.key) {
                    Key.DirectionUp -> {
                        val prev = allLetters.getOrNull(idx - 1)
                        if (prev != null) { focusedLetter = prev; onLetterSelect(prev) }
                        true
                    }
                    Key.DirectionDown -> {
                        val next = allLetters.getOrNull(idx + 1)
                        if (next != null) { focusedLetter = next; onLetterSelect(next) }
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        focusedLetter?.let(onLetterSelect)
                        true
                    }
                    Key.DirectionLeft -> {
                        // 从字母索引条返回左侧内容区
                        contentFocusRequester.requestFocus()
                        true
                    }
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        allLetters.forEach { letter ->
            val isActive = letter in activeLetters
            val isCurrent = letter == (focusedLetter ?: currentLetter)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clickable {
                        focusedLetter = letter
                        onLetterSelect(letter)
                    }
                    .then(
                        if (isCurrent) Modifier.background(
                            NasMusicColors.Primary.copy(alpha = 0.3f),
                            RoundedCornerShape(4.dp)
                        ) else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter.toString(),
                    color = when {
                        isCurrent -> NasMusicColors.Primary
                        isActive -> NasMusicColors.TextSecondary
                        else -> NasMusicColors.TextSecondary.copy(alpha = 0.3f)
                    },
                    fontSize = letterFontSize,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

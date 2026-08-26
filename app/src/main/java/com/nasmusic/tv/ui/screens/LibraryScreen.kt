package com.nasmusic.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onSearchTabShuffle: () -> Unit = {},
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
    modifier: Modifier = Modifier
) {
    var showSearchDialog by remember { mutableStateOf(false) }

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

    // 按当前 tab 类型过滤数据（仅对本地已加载数据过滤）
    val filteredAlbums by remember(filterQuery, albums) {
        derivedStateOf {
            if (filterQuery.isBlank()) albums
            else albums.filter { PinyinUtils.matches(it.name, filterQuery) || PinyinUtils.matches(it.artist, filterQuery) }
        }
    }
    // SONGS Tab：有搜索结果时用搜索结果，否则用分页数据
    val displaySongs by remember(filterQuery, songsPaging.songs, searchResults) {
        derivedStateOf {
            if (filterQuery.isNotBlank()) searchResults
            else songsPaging.songs
        }
    }
    // ARTISTS Tab：使用独立 API 加载的艺术家列表
    val filteredArtists by remember(filterQuery, artists) {
        derivedStateOf {
            if (filterQuery.isBlank()) artists
            else artists.filter { PinyinUtils.matches(it.name, filterQuery) }
        }
    }

    // 播放全部按钮的歌曲列表：按当前 Tab + 搜索状态动态计算
    val playAllSongs by remember(activeTab, filterQuery, songs, displaySongs, searchResults, filteredAlbums, filteredArtists, artistSongsMap) {
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
                    val listed = filteredArtists.flatMap { artistSongsMap[it.name].orEmpty() }
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
                    fontSize = 33.sp,
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
                                fontSize = 19.sp,
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
                        placeholder = "搜索歌曲、专辑、歌手...",
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
                        onAddAllToQueue = onSearchTabAddAllToQueue,
                        onShuffleSearch = onSearchTabShuffle
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
                    // NAS-backed tabs: show loading/empty states
                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "加载中...", color = NasMusicColors.TextSecondary, fontSize = 25.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "正在加载曲库...",
                                    color = NasMusicColors.TextSecondary,
                                    fontSize = 21.sp
                                )
                            }
                        }
                    } else if (!isConnected) {
                        // 未连接状态
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = stringResource(R.string.common_not_connected), color = NasMusicColors.TextSecondary, fontSize = 29.sp)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(text = "请先在「服务器」页面配置 NAS 音乐服务", color = NasMusicColors.TextSecondary, fontSize = 21.sp)
                            }
                        }
                    } else if (albums.isEmpty() && songs.isEmpty()) {
                        // 已连接但库为空
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "曲库为空", color = NasMusicColors.TextSecondary, fontSize = 29.sp)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(text = "请在 NAS 音乐服务中添加音乐文件", color = NasMusicColors.TextSecondary, fontSize = 21.sp)
                            }
                        }
                    } else {
                        when (activeTab) {
                            LibraryTab.ALBUMS -> AlbumsTab(
                                albums = filteredAlbums,
                                songs = songs,
                                onPlayAlbum = onPlayAlbum,
                                onOpenAlbumDetail = onOpenAlbumDetail
                            )
                            LibraryTab.ARTISTS -> ArtistsTab(
                                artists = filteredArtists,
                                artistSongsMap = artistSongsMap,
                                onPlaySongs = onPlaySongs,
                                onOpenArtistDetail = onOpenArtistDetail
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
                    onTabSelected(LibraryTab.SEARCH)
                },
                onDismiss = { showSearchDialog = false },
                showQrCode = true,
                showHistory = true,
                historyItems = historyItems,
                onHistorySelect = { query ->
                    onFilterQueryChange(query)
                    showSearchDialog = false
                    onTabSelected(LibraryTab.SEARCH)
                }
            )
        }
        }

    }
}

@Composable
private fun AlbumsTab(
    albums: List<Album>,
    songs: List<Song>,
    onPlayAlbum: (Album) -> Unit,
    onOpenAlbumDetail: ((Album) -> Unit)? = null
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
            text = "专辑 (${albums.size})",
            color = NasMusicColors.TextPrimary,
            fontSize = 23.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyVerticalGrid(
            state = listState,
            columns = GridCells.Fixed(adaptiveColumns(6, 2, 3)),
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(albums, key = { _, it -> it.id }) { index, album ->
                AlbumCard(
                    album = album,
                    onClick = { onOpenAlbumDetail?.invoke(album) ?: onPlayAlbum(album) },
                    onPlay = { onPlayAlbum(album) },
                    focusRequester = if (index == 0) firstItemFocusRequester else null
                )
            }
        }
    }
}

@Composable
private fun ArtistsTab(
    artists: List<Artist>,
    artistSongsMap: Map<String, List<Song>> = emptyMap(),
    onPlaySongs: (List<Song>) -> Unit,
    onOpenArtistDetail: ((String) -> Unit)? = null
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
            text = "艺术家 (${artists.size})",
            color = NasMusicColors.TextPrimary,
            fontSize = 23.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyVerticalGrid(
            state = listState,
            columns = GridCells.Fixed(adaptiveColumns(5, 2, 3)),
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(artists, key = { _, it -> it.id }) { index, artist ->
                val artistSongs = artistSongsMap[artist.name]
                    ?: emptyList()
                val songCount = artistSongs.size
                ArtistCard(
                    artist = artist.name,
                    coverUrl = artist.coverUrl,
                    songCount = songCount,
                    onClick = {
                        // 点击打开详情页，如果没有详情回调则直接播放
                        if (onOpenArtistDetail != null) {
                            onOpenArtistDetail(artist.name)
                        } else if (artistSongs.isNotEmpty()) {
                            onPlaySongs(artistSongs)
                        }
                    },
                    onPlay = if (artistSongs.isNotEmpty()) {{ onPlaySongs(artistSongs) }} else null,
                    focusRequester = if (index == 0) firstItemFocusRequester else null
                )
            }
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
            "搜索中..."
        } else if (songsPaging.totalCount > 0) {
            "歌曲 (${songs.size}/${songsPaging.totalCount})"
        } else if (songsPaging.isLoading) {
            "歌曲 (加载中...)"
        } else {
            "歌曲 (${songs.size})"
        }
        Text(
            text = titleText,
            color = NasMusicColors.TextPrimary,
            fontSize = 23.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (songs.isEmpty() && !songsPaging.isLoading && !isSearching) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无歌曲",
                    color = NasMusicColors.TextSecondary,
                    fontSize = 21.sp
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
                                text = "加载更多...",
                                color = NasMusicColors.TextSecondary,
                                fontSize = 19.sp
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
            text = "风格 (${genres.size})",
            color = NasMusicColors.TextPrimary,
            fontSize = 23.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (genres.isEmpty()) {
            Text(
                text = stringResource(R.string.library_no_genres),
                color = NasMusicColors.TextSecondary,
                fontSize = 21.sp,
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
                                fontSize = 21.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${genre.songCount} 首",
                                color = NasMusicColors.TextSecondary,
                                fontSize = 17.sp
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
            text = "年代 (${years.size})",
            color = NasMusicColors.TextPrimary,
            fontSize = 23.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (years.isEmpty()) {
            Text(
                text = stringResource(R.string.library_no_years),
                color = NasMusicColors.TextSecondary,
                fontSize = 21.sp,
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
                                fontSize = 27.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "点击播放",
                                color = NasMusicColors.TextSecondary,
                                fontSize = 17.sp
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
                        Text(text = "♪", color = NasMusicColors.TextSecondary, fontSize = 41.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(NasMusicColors.Primary.copy(alpha = 0.95f), shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "${album.songCount}首", color = NasMusicColors.TextPrimary, fontSize = 14.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = album.name, color = NasMusicColors.TextPrimary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = album.artist.ifBlank { "—" }, color = NasMusicColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (onPlay != null) {
                    Text(text = "▶" + stringResource(R.string.player_play), color = NasMusicColors.Primary, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
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
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(NasMusicColors.Primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = artist,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))
                    )
                } else {
                    Text(
                        text = artist.firstOrNull()?.uppercase() ?: "?",
                        color = NasMusicColors.Primary,
                        fontSize = 25.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = artist, color = NasMusicColors.TextPrimary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "${songCount}首", color = NasMusicColors.TextSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (onPlay != null) {
                    Text(text = "▶", color = NasMusicColors.Primary, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                }
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
        Text(text = text, color = NasMusicColors.TextPrimary, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

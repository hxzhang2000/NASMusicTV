package com.nasmusic.tv.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.data.model.SongsPagingState
import com.nasmusic.tv.util.PinyinUtils
import com.nasmusic.tv.util.TimeUtils
import kotlinx.coroutines.launch

private enum class LibraryTab(val titleRes: Int) {
    ALBUMS(R.string.library_albums),
    ARTISTS(R.string.library_artists_alt),
    SONGS(R.string.library_songs),
    GENRES(R.string.library_genres),
    YEARS(R.string.library_years),
    FAVORITES(R.string.library_favorites),
    RECENT(R.string.library_recent),
    NETWORK(R.string.library_network)
}

@Composable
fun LibraryScreen(
    albums: List<Album>,
    songs: List<Song>,
    isLoading: Boolean,
    isConnected: Boolean = false,
    genres: List<Genre> = emptyList(),
    favoriteIds: Set<String> = emptySet(),
    favoriteSongs: List<Song> = emptyList(),
    recentSongIds: List<String> = emptyList(),
    recentSongs: List<Song> = emptyList(),
    playCounts: Map<String, Int> = emptyMap(),
    artistSongsMap: Map<String, List<Song>> = emptyMap(),
    artists: List<Artist> = emptyList(),
    years: List<Int> = emptyList(),
    songsPaging: SongsPagingState = SongsPagingState(),
    searchResults: List<Song> = emptyList(),
    isSearching: Boolean = false,
    onPlayAlbum: (Album) -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlaySongs: (List<Song>) -> Unit,
    onPlayAllAlbums: () -> Unit,
    // 队列切换
    queueSongIds: Set<String> = emptySet(),
    onToggleQueue: (Song) -> Unit = {},
    // 本地收藏切换（用于 SongsTab、RecentTab 等本地歌曲列表）
    onToggleFavorite: (Song) -> Unit = {},
    onOpenAlbumDetail: ((Album) -> Unit)? = null,
    onOpenArtistDetail: ((String) -> Unit)? = null,
    onSongsByGenre: ((String, (List<Song>) -> Unit) -> Unit)? = null,
    onSongsByYear: ((Int, Int, (List<Song>) -> Unit) -> Unit)? = null,
    onLoadSongsFirstPage: () -> Unit = {},
    onLoadSongsNextPage: () -> Unit = {},
    onLoadArtists: () -> Unit = {},
    onLoadYears: () -> Unit = {},
    onLoadRecentSongs: () -> Unit = {},
    onSearch: (String) -> Unit = {},
    onClearSearch: () -> Unit = {},
    // 网络音乐搜索
    networkSearchResults: List<Song> = emptyList(),
    isNetworkSearching: Boolean = false,
    networkSearchKeyword: String = "",
    onSearchNetwork: (String) -> Unit = {},
    onClearNetworkSearch: () -> Unit = {},
    onPlayNetworkSong: (Song) -> Unit = {},
    // 网络歌曲收藏
    networkFavoriteSongs: List<Song> = emptyList(),
    networkFavoriteIds: Set<String> = emptySet(),
    onToggleNetworkFavorite: (Song) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(LibraryTab.ALBUMS) }
    var filterQuery by remember { mutableStateOf("") }
    var showSearchDialog by remember { mutableStateOf(false) }

    // Tab 切换时触发按需加载
    LaunchedEffect(activeTab) {
        when (activeTab) {
            LibraryTab.SONGS -> onLoadSongsFirstPage()
            LibraryTab.ARTISTS -> onLoadArtists()
            LibraryTab.YEARS -> onLoadYears()
            LibraryTab.RECENT -> onLoadRecentSongs()
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
                    fontSize = 28.sp,
                    modifier = Modifier.padding(end = 24.dp)
                )

                // TAB 切换
                LibraryTab.values().forEach { tab ->
                    val selected = tab == activeTab
                    FocusableSurface(
                        onClick = { activeTab = tab },
                        modifier = Modifier.padding(horizontal = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.05f,
                        animationDurationMs = 200,
                        containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.2f) else Color.Transparent,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                        contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                        focusedContentColor = NasMusicColors.Primary
                    ) {
                        Text(
                            text = stringResource(tab.titleRes),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // 搜索栏
                SearchBar(
                    query = filterQuery,
                    onOpenSearch = { showSearchDialog = true },
                    onClear = { filterQuery = "" }
                )

                Spacer(modifier = Modifier.width(12.dp))

                if (albums.isNotEmpty()) {
                    ButtonChip(text = stringResource(R.string.common_play_all)) { onPlayAllAlbums() }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 内容区域
            // NETWORK 和 FAVORITES Tab 不依赖 NAS 连接状态，始终可用
            // （收藏数据存储在本地 DataStore，包括本地收藏和网络收藏）
            if (activeTab == LibraryTab.NETWORK) {
                // 网络 Tab：不依赖 NAS 连接状态，始终可用
                NetworkTab(
                    searchResults = networkSearchResults,
                    isSearching = isNetworkSearching,
                    keyword = networkSearchKeyword,
                    onSearch = onSearchNetwork,
                    onClearSearch = onClearNetworkSearch,
                    onPlaySong = onPlayNetworkSong,
                    queueSongIds = queueSongIds,
                    onToggleQueue = onToggleQueue,
                    favoriteSongs = networkFavoriteSongs,
                    networkFavoriteIds = networkFavoriteIds,
                    onToggleFavorite = onToggleNetworkFavorite
                )
            } else if (activeTab == LibraryTab.FAVORITES) {
                // 收藏 Tab：不依赖 NAS 连接状态，始终可用
                // （本地收藏和网络收藏都存储在 DataStore，无需 NAS 后端）
                FavoritesTab(
                    songs = favoriteSongs,
                    favoriteIds = favoriteIds,
                    onPlaySong = onPlaySong,
                    queueSongIds = queueSongIds,
                    onToggleQueue = onToggleQueue,
                    onToggleFavorite = onToggleFavorite,
                    networkFavoriteSongs = networkFavoriteSongs,
                    onToggleNetworkFavorite = onToggleNetworkFavorite
                )
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "加载中...", color = NasMusicColors.TextSecondary, fontSize = 20.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "正在加载曲库...",
                            color = NasMusicColors.TextSecondary,
                            fontSize = 16.sp
                        )
                    }
                }
            } else if (!isConnected) {
                // 未连接状态
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = stringResource(R.string.common_not_connected), color = NasMusicColors.TextSecondary, fontSize = 24.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "请先在「服务器」页面配置 NAS 音乐服务", color = NasMusicColors.TextSecondary, fontSize = 16.sp)
                    }
                }
            } else if (albums.isEmpty() && songs.isEmpty()) {
                // 已连接但库为空
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "曲库为空", color = NasMusicColors.TextSecondary, fontSize = 24.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "请在 NAS 音乐服务中添加音乐文件", color = NasMusicColors.TextSecondary, fontSize = 16.sp)
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
                        onToggleFavorite = onToggleFavorite
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
                    LibraryTab.FAVORITES -> {} // 已在上方独立处理（不依赖 NAS 连接）
                    LibraryTab.RECENT -> RecentTab(
                        songs = recentSongs,
                        recentSongIds = recentSongIds,
                        playCounts = playCounts,
                        onPlaySong = onPlaySong,
                        queueSongIds = queueSongIds,
                        onToggleQueue = onToggleQueue,
                        favoriteIds = favoriteIds,
                        onToggleFavorite = onToggleFavorite
                    )
                    LibraryTab.NETWORK -> {} // 已在上方独立处理
                }
            }
        }

        // 搜索键盘弹窗
        if (showSearchDialog) {
            TextInputDialog(
                title = stringResource(R.string.common_search),
                hint = stringResource(R.string.library_search_hint),
                initialValue = filterQuery,
                onConfirm = { query ->
                    filterQuery = query
                    showSearchDialog = false
                },
                onDismiss = { showSearchDialog = false }
            )
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
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyVerticalGrid(
            state = listState,
            columns = GridCells.Fixed(6),
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
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyVerticalGrid(
            state = listState,
            columns = GridCells.Fixed(5),
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
    onToggleFavorite: (Song) -> Unit = {}
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
            fontSize = 18.sp,
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
                    fontSize = 16.sp
                )
            }
        } else {
            LazyVerticalGrid(
                state = listState,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(songs, key = { _, it -> it.id }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { onPlaySong(song) },
                        isFavorite = song.id in favoriteIds,
                        isInQueue = song.id in queueSongIds,
                        onToggleQueue = { onToggleQueue(song) },
                        isFavorited = song.id in favoriteIds,
                        onToggleFavorite = { onToggleFavorite(song) },
                        focusRequester = if (index == 0) firstItemFocusRequester else null
                    )
                }
                // 底部加载指示器
                if (songsPaging.isLoading) {
                    item(span = { GridItemSpan(2) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "加载更多...",
                                color = NasMusicColors.TextSecondary,
                                fontSize = 14.sp
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
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (genres.isEmpty()) {
            Text(
                text = stringResource(R.string.library_no_genres),
                color = NasMusicColors.TextSecondary,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 24.dp)
            )
        } else {
            LazyVerticalGrid(
                state = listState,
                columns = GridCells.Fixed(4),
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
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${genre.songCount} 首",
                                color = NasMusicColors.TextSecondary,
                                fontSize = 12.sp
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
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (years.isEmpty()) {
            Text(
                text = stringResource(R.string.library_no_years),
                color = NasMusicColors.TextSecondary,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 24.dp)
            )
        } else {
            LazyVerticalGrid(
                state = listState,
                columns = GridCells.Fixed(5),
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
                                fontSize = 22.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "点击播放",
                                color = NasMusicColors.TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesTab(
    songs: List<Song>,
    favoriteIds: Set<String>,
    onPlaySong: (Song) -> Unit,
    queueSongIds: Set<String> = emptySet(),
    onToggleQueue: (Song) -> Unit = {},
    // 本地收藏切换（用于取消 NAS 歌曲收藏）
    onToggleFavorite: (Song) -> Unit = {},
    // 网络收藏
    networkFavoriteSongs: List<Song> = emptyList(),
    onToggleNetworkFavorite: (Song) -> Unit = {}
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

    // 合并本地收藏和网络收藏（本地在前，网络在后）
    val allSongs = songs + networkFavoriteSongs
    val totalCount = allSongs.size

    Column {
        Text(
            text = "收藏 ($totalCount)",
            color = NasMusicColors.TextPrimary,
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (allSongs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无收藏歌曲\n在播放页面点击 ♡ 可收藏",
                    color = NasMusicColors.TextSecondary,
                    fontSize = 16.sp
                )
            }
        } else {
            LazyVerticalGrid(
                state = listState,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(allSongs, key = { _, it -> it.id }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { onPlaySong(song) },
                        isFavorite = !song.isNetworkSong,  // 本地收藏显示 ♥ 标记
                        isInQueue = song.id in queueSongIds,
                        onToggleQueue = { onToggleQueue(song) },
                        // 收藏按钮（本地/网络都可取消收藏，收藏列表中必然已收藏）
                        isFavorited = true,
                        onToggleFavorite = if (song.isNetworkSong) {
                            { onToggleNetworkFavorite(song) }
                        } else {
                            { onToggleFavorite(song) }
                        },
                        focusRequester = if (index == 0) firstItemFocusRequester else null
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentTab(
    songs: List<Song>,
    recentSongIds: List<String>,
    playCounts: Map<String, Int> = emptyMap(),
    onPlaySong: (Song) -> Unit,
    queueSongIds: Set<String> = emptySet(),
    onToggleQueue: (Song) -> Unit = {},
    favoriteIds: Set<String> = emptySet(),
    onToggleFavorite: (Song) -> Unit = {}
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

    // songs 参数已经是 recentSongs（由调用方传入），无需再匹配
    Column {
        Text(
            text = "最近播放 (${songs.size})",
            color = NasMusicColors.TextPrimary,
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (songs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.library_no_recent),
                    color = NasMusicColors.TextSecondary,
                    fontSize = 16.sp
                )
            }
        } else {
            LazyVerticalGrid(
                state = listState,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(songs, key = { _, it -> it.id }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { onPlaySong(song) },
                        playCount = playCounts[song.id],
                        isFavorite = song.id in favoriteIds,
                        isInQueue = song.id in queueSongIds,
                        onToggleQueue = { onToggleQueue(song) },
                        isFavorited = song.id in favoriteIds,
                        onToggleFavorite = { onToggleFavorite(song) },
                        focusRequester = if (index == 0) firstItemFocusRequester else null
                    )
                }
            }
        }
    }
}

/**
 * 网络音乐搜索 Tab
 *
 * 独立于 NAS 连接状态，始终可用。
 * 用户输入关键词后通过 NetworkMusicManager 搜索，结果以 SongRow 展示。
 * 点击歌曲触发 onPlayNetworkSong，由 ViewModel 解析播放链接后播放。
 *
 * 包含 BACK 键回顶支持（Level 1.5）。
 */
@Composable
private fun NetworkTab(
    searchResults: List<Song>,
    isSearching: Boolean,
    keyword: String,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onPlaySong: (Song) -> Unit,
    queueSongIds: Set<String> = emptySet(),
    onToggleQueue: (Song) -> Unit = {},
    // 网络收藏
    favoriteSongs: List<Song> = emptyList(),
    networkFavoriteIds: Set<String> = emptySet(),
    onToggleFavorite: (Song) -> Unit = {}
) {
    var showSearchDialog by remember { mutableStateOf(false) }
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
        // 标题 + 搜索框
        Text(
            text = stringResource(R.string.library_network),
            color = NasMusicColors.TextPrimary,
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        SearchBar(
            query = keyword,
            onOpenSearch = { showSearchDialog = true },
            onClear = { onClearSearch() }
        )
        Spacer(modifier = Modifier.height(12.dp))

        when {
            isSearching -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "搜索中...",
                        color = NasMusicColors.TextSecondary,
                        fontSize = 16.sp
                    )
                }
            }
            keyword.isNotBlank() && searchResults.isEmpty() -> {
                // 有关键词但无结果
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.library_network_no_results),
                        color = NasMusicColors.TextSecondary,
                        fontSize = 16.sp
                    )
                }
            }
            keyword.isNotBlank() -> {
                // 搜索结果列表
                LazyVerticalGrid(
                    state = listState,
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(searchResults, key = { _, it -> it.id }) { index, song ->
                        SongRow(
                            song = song,
                            onClick = { onPlaySong(song) },
                            isInQueue = song.id in queueSongIds,
                            onToggleQueue = { onToggleQueue(song) },
                            isFavorited = song.id in networkFavoriteIds,
                            onToggleFavorite = { onToggleFavorite(song) },
                            focusRequester = if (index == 0) firstItemFocusRequester else null
                        )
                    }
                }
            }
            favoriteSongs.isEmpty() -> {
                // 无关键词且无收藏：空状态
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.library_network_favorites_empty),
                        color = NasMusicColors.TextSecondary,
                        fontSize = 16.sp
                    )
                }
            }
            else -> {
                // 无关键词但有收藏：显示收藏列表
                Column {
                    Text(
                        text = stringResource(R.string.library_network_favorites_title),
                        color = NasMusicColors.TextPrimary,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyVerticalGrid(
                        state = listState,
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(favoriteSongs, key = { _, it -> it.id }) { index, song ->
                            SongRow(
                                song = song,
                                onClick = { onPlaySong(song) },
                                isInQueue = song.id in queueSongIds,
                                onToggleQueue = { onToggleQueue(song) },
                                isFavorited = true,  // 收藏列表中必然已收藏
                                onToggleFavorite = { onToggleFavorite(song) },
                                focusRequester = if (index == 0) firstItemFocusRequester else null
                            )
                        }
                    }
                }
            }
        }
    }

    // 搜索输入对话框
    if (showSearchDialog) {
        TextInputDialog(
            title = stringResource(R.string.library_network),
            hint = stringResource(R.string.library_network_search_hint),
            initialValue = keyword,
            onConfirm = { input ->
                onSearch(input)
                showSearchDialog = false
            },
            onDismiss = { showSearchDialog = false }
        )
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
                        Text(text = "♪", color = NasMusicColors.TextSecondary, fontSize = 36.sp)
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
                    Text(text = "${album.songCount}首", color = Color.Black, fontSize = 9.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = album.name, color = NasMusicColors.TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = album.artist.ifBlank { "—" }, color = NasMusicColors.TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (onPlay != null) {
                    Text(text = "▶" + stringResource(R.string.player_play), color = NasMusicColors.Primary, fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp))
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
                        fontSize = 20.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = artist, color = NasMusicColors.TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "${songCount}首", color = NasMusicColors.TextSecondary, fontSize = 10.sp, modifier = Modifier.weight(1f))
                if (onPlay != null) {
                    Text(text = "▶", color = NasMusicColors.Primary, fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    isFavorite: Boolean = false,
    playCount: Int? = null,
    isInQueue: Boolean = false,
    onToggleQueue: (() -> Unit)? = null,
    // 收藏按钮（本地/网络通用，onToggleFavorite 不为 null 时显示）
    isFavorited: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    // 将行拆分为两个独立的可聚焦区域：左侧内容（播放）和右侧按钮。
    // 外层 Box 用 focusGroup() 让 D-pad 能在区域间导航。
    // 不能让整个 Row 可聚焦（clickable），否则其 bounds 会覆盖按钮，导致按钮无法获得焦点。
    var isRowFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusGroup()
            .scale(animScale.value)
            .clip(RoundedCornerShape(6.dp))
            .background(
                color = if (isRowFocused) NasMusicColors.Primary.copy(alpha = 0.2f) else NasMusicColors.Surface.copy(alpha = 0.5f)
            )
            .border(
                width = if (isRowFocused) 2.dp else 0.dp,
                color = if (isRowFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .onFocusChanged { state ->
                isRowFocused = state.hasFocus
                scope.launch {
                    animScale.animateTo(
                        if (isRowFocused) 1.02f else 1f,
                        tween(200)
                    )
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧可聚焦+可点击区域（点击播放歌曲）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .clickable { onClick() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isFavorite) {
                    Text(text = "♥", color = NasMusicColors.Warning, fontSize = 10.sp, modifier = Modifier.width(16.dp))
                } else {
                    Text(text = String.format("%02d", song.trackNumber), color = NasMusicColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = song.title, color = NasMusicColors.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = song.artist.ifBlank { "—" }, color = NasMusicColors.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(modifier = Modifier.width(10.dp))
                if (playCount != null && playCount > 0) {
                    Text(text = "${playCount}次", color = NasMusicColors.Primary, fontSize = 10.sp, modifier = Modifier.padding(end = 8.dp))
                }
                Text(text = TimeUtils.formatDuration(song.durationMs), color = NasMusicColors.TextSecondary, fontSize = 11.sp)
            }
            // 右侧按钮区（独立可聚焦）
            // 收藏按钮（仅当 onToggleFavorite 不为 null 时显示）
            if (onToggleFavorite != null) {
                Spacer(modifier = Modifier.width(8.dp))
                FavoriteButton(
                    isFavorite = isFavorited,
                    onClick = onToggleFavorite
                )
            }
            // 队列按钮
            if (onToggleQueue != null) {
                Spacer(modifier = Modifier.width(8.dp))
                QueueToggleButton(
                    isInQueue = isInQueue,
                    onClick = onToggleQueue
                )
            }
        }
    }
}

/**
 * 队列切换按钮
 *
 * 视觉：列表图标（☰），在队列中时高亮 Primary 色，不在队列时暗色 TextSecondary。
 * 点击切换加入/移除队列。
 *
 * 注意：不能使用 Surface(onClick=...)，因为它嵌套在 FocusableSurface（同样使用
 * Surface(onClick=...)）内部时，Compose TV 的焦点系统无法将焦点移到内层 Surface。
 * 改用 Box + Modifier.focusable() + Modifier.clickable，使其成为独立的可聚焦节点。
 */
@Composable
fun QueueToggleButton(
    isInQueue: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .size(28.dp)
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .background(
                color = if (isFocused) NasMusicColors.Primary.copy(alpha = 0.2f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch {
                    animScale.animateTo(
                        if (isFocused) 1.1f else 1f,
                        tween(200)
                    )
                }
            }
            .focusable()
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "☰",
            fontSize = 14.sp,
            color = if (isInQueue) NasMusicColors.Primary else NasMusicColors.TextSecondary.copy(alpha = 0.5f)
        )
    }
}

/**
 * 收藏按钮（本地/网络通用）
 *
 * 视觉：♥（已收藏，高亮 Warning 色）/ ♡（未收藏，暗色 TextSecondary）。
 * 点击切换收藏状态。
 *
 * 焦点处理与 QueueToggleButton 一致：Box + focusable() + clickable()，
 * 避免嵌套 Surface 导致的焦点问题。
 */
@Composable
fun FavoriteButton(
    isFavorite: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .size(28.dp)
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .background(
                color = if (isFocused) NasMusicColors.Primary.copy(alpha = 0.2f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch {
                    animScale.animateTo(
                        if (isFocused) 1.1f else 1f,
                        tween(200)
                    )
                }
            }
            .focusable()
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isFavorite) "♥" else "♡",
            fontSize = 16.sp,
            color = if (isFavorite) NasMusicColors.Warning else NasMusicColors.TextSecondary.copy(alpha = 0.5f)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onOpenSearch: () -> Unit,
    onClear: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 搜索输入框（点击弹出虚拟键盘）
        Surface(
            onClick = onOpenSearch,
            modifier = Modifier
                .width(340.dp)
                .height(38.dp)
                .scale(animScale.value)
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) NasMusicColors.FocusRing else NasMusicColors.SurfaceVariant,
                    shape = RoundedCornerShape(10.dp)
                )
                .onFocusChanged {
                    isFocused = it.isFocused
                    scope.launch { animScale.animateTo(if (isFocused) 1.04f else 1f, tween(200)) }
                },
            shape = ClickableSurfaceDefaults.shape(
                shape = RoundedCornerShape(10.dp),
                focusedShape = RoundedCornerShape(10.dp)
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NasMusicColors.Surface.copy(alpha = 0.6f),
                contentColor = NasMusicColors.TextPrimary,
                focusedContainerColor = NasMusicColors.Surface.copy(alpha = 0.8f),
                focusedContentColor = NasMusicColors.TextPrimary
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.97f)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🔍",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = if (query.isEmpty()) "搜索歌曲、专辑、歌手..." else query,
                    color = if (query.isEmpty()) NasMusicColors.TextSecondary else NasMusicColors.TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // 搜索按钮 / 清除按钮
        ButtonChip(
                text = if (query.isNotEmpty()) stringResource(R.string.common_clear) else stringResource(R.string.common_search),
            onClick = {
                if (query.isNotEmpty()) onClear()
                else onOpenSearch()
            }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ButtonChip(text: String, onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.08f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Primary,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
        contentColor = Color.Black,
        focusedContentColor = Color.Black,
        pressedScale = 0.95f
    ) {
        Text(text = text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

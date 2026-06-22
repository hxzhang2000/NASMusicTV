package com.nasmusic.tv.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.SongsPagingState
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
    RECENT(R.string.library_recent)
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
            if (filterQuery.isBlank()) artists.map { it.name }
            else artists.map { it.name }.filter { PinyinUtils.matches(it, filterQuery) }
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
            if (isLoading) {
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
                        onPlaySong = onPlaySong
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
                    LibraryTab.FAVORITES -> FavoritesTab(
                        songs = favoriteSongs,
                        favoriteIds = favoriteIds,
                        onPlaySong = onPlaySong
                    )
                    LibraryTab.RECENT -> RecentTab(
                        songs = recentSongs,
                        recentSongIds = recentSongIds,
                        playCounts = playCounts,
                        onPlaySong = onPlaySong
                    )
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
    Column {
        Text(
            text = "专辑 (${albums.size})",
            color = NasMusicColors.TextPrimary,
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumCard(
                    album = album,
                    onClick = { onOpenAlbumDetail?.invoke(album) ?: onPlayAlbum(album) },
                    onPlay = { onPlayAlbum(album) }
                )
            }
        }
    }
}

@Composable
private fun ArtistsTab(
    artists: List<String>,
    artistSongsMap: Map<String, List<Song>> = emptyMap(),
    onPlaySongs: (List<Song>) -> Unit,
    onOpenArtistDetail: ((String) -> Unit)? = null
) {
    Column {
        Text(
            text = "歌唱家 (${artists.size})",
            color = NasMusicColors.TextPrimary,
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(artists, key = { it }) { artist ->
                val artistSongs = artistSongsMap[artist]
                    ?: emptyList()
                val songCount = artistSongs.size
                ArtistCard(
                    artist = artist,
                    songCount = songCount,
                    onClick = {
                        // 点击打开详情页，如果没有详情回调则直接播放
                        if (onOpenArtistDetail != null) {
                            onOpenArtistDetail(artist)
                        } else if (artistSongs.isNotEmpty()) {
                            onPlaySongs(artistSongs)
                        }
                    },
                    onPlay = if (artistSongs.isNotEmpty()) {{ onPlaySongs(artistSongs) }} else null
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
    onPlaySong: (Song) -> Unit
) {
    val listState = rememberLazyGridState()

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
                items(songs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        onClick = { onPlaySong(song) },
                        isFavorite = song.id in favoriteIds
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
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(genres, key = { it.name }) { genre ->
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
                        focusedContentColor = NasMusicColors.Primary
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
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(years, key = { it }) { year ->
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
                        focusedContentColor = NasMusicColors.Primary
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
    onPlaySong: (Song) -> Unit
) {
    // songs 参数已经是 favoriteSongs（由调用方传入），无需再过滤
    Column {
        Text(
            text = "收藏 (${songs.size})",
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
                    text = "暂无收藏歌曲\n在播放页面点击 ♡ 可收藏",
                    color = NasMusicColors.TextSecondary,
                    fontSize = 16.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(songs, key = { it.id }) { song ->
                    SongRow(song = song, onClick = { onPlaySong(song) }, isFavorite = true)
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
    onPlaySong: (Song) -> Unit
) {
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
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(songs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        onClick = { onPlaySong(song) },
                        playCount = playCounts[song.id]
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, onPlay: (() -> Unit)? = null) {
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
        pressedContainerColor = NasMusicColors.Background
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
private fun ArtistCard(artist: String, songCount: Int, onClick: () -> Unit, onPlay: (() -> Unit)? = null) {
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
        pressedContainerColor = NasMusicColors.Background
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
                Text(
                    text = artist.firstOrNull()?.uppercase() ?: "?",
                    color = NasMusicColors.Primary,
                    fontSize = 20.sp
                )
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
fun SongRow(song: Song, onClick: () -> Unit, isFavorite: Boolean = false, playCount: Int? = null) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        focusedScale = 1.02f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface.copy(alpha = 0.5f),
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = Color.Black,
        pressedScale = 0.98f,
        pressedContainerColor = NasMusicColors.SurfaceVariant,
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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

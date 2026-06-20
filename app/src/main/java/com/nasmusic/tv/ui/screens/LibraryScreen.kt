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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.PinyinUtils
import com.nasmusic.tv.util.TimeUtils
import kotlinx.coroutines.launch

private enum class LibraryTab(val displayName: String) {
    ALBUMS("专辑"),
    ARTISTS("歌唱家"),
    SONGS("歌曲")
}

@Composable
fun LibraryScreen(
    albums: List<Album>,
    songs: List<Song>,
    isLoading: Boolean,
    onPlayAlbum: (Album) -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlaySongs: (List<Song>) -> Unit,
    onPlayAllAlbums: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(LibraryTab.ALBUMS) }
    var filterQuery by remember { mutableStateOf("") }
    var showSearchDialog by remember { mutableStateOf(false) }

    // 按当前 tab 类型过滤数据
    val filteredAlbums by remember(filterQuery, albums) {
        derivedStateOf {
            if (filterQuery.isBlank()) albums
            else albums.filter { PinyinUtils.matches(it.name, filterQuery) || PinyinUtils.matches(it.artist, filterQuery) }
        }
    }
    val filteredSongs by remember(filterQuery, songs) {
        derivedStateOf {
            if (filterQuery.isBlank()) songs
            else songs.filter { PinyinUtils.matches(it.title, filterQuery) || PinyinUtils.matches(it.artist, filterQuery) }
        }
    }
    val allArtists = remember(songs) {
        songs.mapNotNull { it.artist.ifBlank { null } }.distinct().sorted()
    }
    val filteredArtists by remember(filterQuery, allArtists) {
        derivedStateOf {
            if (filterQuery.isBlank()) allArtists
            else allArtists.filter { PinyinUtils.matches(it, filterQuery) }
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
                    text = "曲库",
                    color = NasMusicColors.TextPrimary,
                    fontSize = 28.sp,
                    modifier = Modifier.padding(end = 24.dp)
                )

                // TAB 切换
                LibraryTab.values().forEach { tab ->
                    val selected = tab == activeTab
                    var isFocused by remember { mutableStateOf(false) }
                    val animScale = remember { Animatable(1f) }
                    val scope = rememberCoroutineScope()
                    Surface(
                        onClick = { activeTab = tab },
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .scale(animScale.value)
                            .border(
                                width = if (isFocused) 2.dp else 0.dp,
                                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .onFocusChanged {
                                isFocused = it.isFocused
                                scope.launch { animScale.animateTo(if (isFocused) 1.05f else 1f, tween(200)) }
                            },
                        shape = ClickableSurfaceDefaults.shape(
                            shape = RoundedCornerShape(8.dp),
                            focusedShape = RoundedCornerShape(8.dp)
                        ),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.2f) else Color.Transparent,
                            contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                            focusedContentColor = NasMusicColors.Primary
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.96f)
                    ) {
                        Text(
                            text = tab.displayName,
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
                    ButtonChip(text = "播放全部") { onPlayAllAlbums() }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 内容区域
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "加载中...", color = NasMusicColors.TextSecondary, fontSize = 20.sp)
                }
            } else if (albums.isEmpty() && songs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "尚未连接到服务器", color = NasMusicColors.TextSecondary, fontSize = 24.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "请先在「服务器」页面配置 NAS 音乐服务", color = NasMusicColors.TextSecondary, fontSize = 16.sp)
                    }
                }
            } else {
                when (activeTab) {
                    LibraryTab.ALBUMS -> AlbumsTab(
                        albums = filteredAlbums,
                        songs = songs,
                        onPlayAlbum = onPlayAlbum
                    )
                    LibraryTab.ARTISTS -> ArtistsTab(
                        artists = filteredArtists,
                        songs = songs,
                        onPlaySongs = onPlaySongs
                    )
                    LibraryTab.SONGS -> SongsTab(
                        songs = filteredSongs,
                        onPlaySong = onPlaySong
                    )
                }
            }
        }

        // 搜索键盘弹窗
        if (showSearchDialog) {
            TextInputDialog(
                title = "搜索",
                hint = "输入歌曲名、歌手或拼音首字母",
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
    onPlayAlbum: (Album) -> Unit
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
            items(albums) { album ->
                AlbumCard(album = album, onClick = { onPlayAlbum(album) })
            }
        }
    }
}

@Composable
private fun ArtistsTab(
    artists: List<String>,
    songs: List<Song>,
    onPlaySongs: (List<Song>) -> Unit
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
            items(artists) { artist ->
                ArtistCard(
                    artist = artist,
                    songCount = songs.count { it.artist == artist },
                    onClick = {
                        val artistSongs = songs.filter { it.artist == artist }
                        if (artistSongs.isNotEmpty()) onPlaySongs(artistSongs)
                    }
                )
            }
        }
    }
}

@Composable
private fun SongsTab(
    songs: List<Song>,
    onPlaySong: (Song) -> Unit
) {
    Column {
        Text(
            text = "歌曲 (${songs.size})",
            color = NasMusicColors.TextPrimary,
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(songs) { song ->
                SongRow(song = song, onClick = { onPlaySong(song) })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AlbumCard(album: Album, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch { animScale.animateTo(if (isFocused) 1.06f else 1f, tween(200)) }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(10.dp),
            focusedShape = RoundedCornerShape(10.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NasMusicColors.Surface,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
            focusedContentColor = NasMusicColors.Primary,
            pressedContainerColor = NasMusicColors.Background
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.96f)
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
            Text(text = album.artist.ifBlank { "—" }, color = NasMusicColors.TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ArtistCard(artist: String, songCount: Int, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch { animScale.animateTo(if (isFocused) 1.06f else 1f, tween(200)) }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(10.dp),
            focusedShape = RoundedCornerShape(10.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NasMusicColors.Surface,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
            focusedContentColor = NasMusicColors.Primary,
            pressedContainerColor = NasMusicColors.Background
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.96f)
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
            Text(text = "${songCount}首", color = NasMusicColors.TextSecondary, fontSize = 10.sp)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SongRow(song: Song, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch { animScale.animateTo(if (isFocused) 1.02f else 1f, tween(200)) }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(6.dp),
            focusedShape = RoundedCornerShape(6.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NasMusicColors.Surface.copy(alpha = 0.5f),
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
            focusedContentColor = Color.Black,
            pressedContainerColor = NasMusicColors.SurfaceVariant
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.98f)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = String.format("%02d", song.trackNumber), color = NasMusicColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = song.title, color = NasMusicColors.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = song.artist.ifBlank { "—" }, color = NasMusicColors.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.width(10.dp))
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
            text = if (query.isNotEmpty()) "清除" else "搜索",
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
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch { animScale.animateTo(if (isFocused) 1.08f else 1f, tween(200)) }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(16.dp),
            focusedShape = RoundedCornerShape(16.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NasMusicColors.Primary,
            contentColor = Color.Black,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
            focusedContentColor = Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.95f)
    ) {
        Text(text = text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

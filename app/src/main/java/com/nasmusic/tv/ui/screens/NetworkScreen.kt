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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.CoverCarousel
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 网络音乐主屏幕
 *
 * 页面结构（从上到下）：
 * 1. 搜索栏
 * 2. 平台切换行（网易云 / QQ音乐 / 酷狗）
 * 3. 推荐歌单卡片行（LazyRow 横向滚动）
 * 4. 热歌榜（歌单 ID: 3778678）
 * 5. 新歌榜（歌单 ID: 3779629）
 * 6. 我的收藏
 *
 * 有搜索关键词时：隐藏推荐内容，显示搜索结果。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NetworkScreen(
    networkSearchResults: List<Song>,
    networkSearchKeyword: String,
    networkFavoriteSongs: List<Song>,
    networkFavoriteIds: Set<String>,
    networkPlaylists: List<Pair<Playlist, List<Song>>>,
    playlistSongs: List<Song>,
    searchNetworkPlatform: String,
    isNetworkSearching: Boolean,
    onSearchNetwork: (String) -> Unit,
    onClearNetworkSearch: () -> Unit,
    onPlayNetworkSong: (Song) -> Unit,
    onToggleNetworkFavorite: (Song) -> Unit,
    onLoadPlaylistDetail: (Pair<Playlist, List<Song>>) -> Unit,
    onNavigateToPlaylistDetail: () -> Unit,
    onSearchNetworkPlatform: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSearchDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // Level 1.5: 列表回顶
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

    Column(modifier = modifier.fillMaxWidth()) {
        // 搜索栏
        Text(
            text = stringResource(R.string.nav_network),
            color = NasMusicColors.TextPrimary,
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        NetworkSearchBar(
            query = networkSearchKeyword,
            onOpenSearch = { showSearchDialog = true },
            onClear = { onClearNetworkSearch() }
        )
        Spacer(modifier = Modifier.height(12.dp))

        when {
            isNetworkSearching -> {
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
            networkSearchKeyword.isNotBlank() && networkSearchResults.isEmpty() -> {
                // 有关键词但无结果
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.network_no_results),
                        color = NasMusicColors.TextSecondary,
                        fontSize = 16.sp
                    )
                }
            }
            networkSearchKeyword.isNotBlank() -> {
                // 搜索结果列表
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(networkSearchResults, key = { _, it -> it.id }) { index, song ->
                        SongRow(
                            song = song,
                            onClick = { onPlayNetworkSong(song) },
                            isFavorited = song.id in networkFavoriteIds,
                            onToggleFavorite = { onToggleNetworkFavorite(song) },
                            focusRequester = if (index == 0) firstItemFocusRequester else null
                        )
                    }
                }
            }
            else -> {
                // 无搜索关键词：显示网络音乐推荐主页
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 平台切换行
                    item(key = "platform_switch") {
                        PlatformSwitchRow(
                            selectedPlatform = searchNetworkPlatform,
                            onSelectPlatform = { platform ->
                                onSearchNetworkPlatform(platform)
                            },
                            focusRequester = firstItemFocusRequester
                        )
                    }

                    // 推荐歌单卡片行
                    item(key = "playlist_cards") {
                        Column {
                            Text(
                                text = "推荐歌单",
                                color = NasMusicColors.TextPrimary,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(networkPlaylists, key = { it.first.id }) { (playlist, songs) ->
                                    PlaylistCard(
                                        playlist = playlist,
                                        songs = songs,
                                        onClick = {
                                            onLoadPlaylistDetail(playlist to songs)
                                            onNavigateToPlaylistDetail()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 热歌榜
                    val hotPlaylist = networkPlaylists.find { it.first.id == "3778678" }
                    if (hotPlaylist != null && hotPlaylist.second.isNotEmpty()) {
                        item(key = "hot_songs_header") {
                            Text(
                                text = "热歌榜",
                                color = NasMusicColors.TextPrimary,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                            )
                        }
                        itemsIndexed(
                            hotPlaylist.second.take(50),
                            key = { _, song -> "hot_${song.id}" }
                        ) { index, song ->
                            SongRow(
                                song = song,
                                onClick = { onPlayNetworkSong(song) },
                                isInQueue = false,
                                isFavorited = song.id in networkFavoriteIds,
                                onToggleFavorite = { onToggleNetworkFavorite(song) }
                            )
                        }
                    }

                    // 新歌榜
                    val newPlaylist = networkPlaylists.find { it.first.id == "3779629" }
                    if (newPlaylist != null && newPlaylist.second.isNotEmpty()) {
                        item(key = "new_songs_header") {
                            Text(
                                text = "新歌榜",
                                color = NasMusicColors.TextPrimary,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                            )
                        }
                        itemsIndexed(
                            newPlaylist.second.take(50),
                            key = { _, song -> "new_${song.id}" }
                        ) { index, song ->
                            SongRow(
                                song = song,
                                onClick = { onPlayNetworkSong(song) },
                                isInQueue = false,
                                isFavorited = song.id in networkFavoriteIds,
                                onToggleFavorite = { onToggleNetworkFavorite(song) }
                            )
                        }
                    }

                    // 我的收藏
                    if (networkFavoriteSongs.isNotEmpty()) {
                        item(key = "favorites_header") {
                            Text(
                                text = stringResource(R.string.network_my_favorites),
                                color = NasMusicColors.TextPrimary,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                            )
                        }
                        itemsIndexed(
                            networkFavoriteSongs,
                            key = { _, song -> "fav_${song.id}" }
                        ) { index, song ->
                            SongRow(
                                song = song,
                                onClick = { onPlayNetworkSong(song) },
                                isInQueue = false,
                                isFavorited = true,
                                onToggleFavorite = { onToggleNetworkFavorite(song) }
                            )
                        }
                    }

                    // 底部间距
                    item(key = "bottom_spacer") {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    // 搜索输入对话框
    if (showSearchDialog) {
        TextInputDialog(
            title = stringResource(R.string.nav_network),
            hint = stringResource(R.string.network_search_hint),
            initialValue = networkSearchKeyword,
            onConfirm = { input ->
                onSearchNetwork(input)
                showSearchDialog = false
            },
            onDismiss = { showSearchDialog = false }
        )
    }
}

/**
 * 网络搜索栏（与 LibraryScreen 的 SearchBar 样式一致，但非 private）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetworkSearchBar(
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

        FocusableSurface(
            onClick = {
                if (query.isNotEmpty()) onClear()
                else onOpenSearch()
            },
            modifier = Modifier.height(38.dp),
            shape = RoundedCornerShape(10.dp),
            focusedScale = 1.06f,
            animationDurationMs = 200,
            containerColor = NasMusicColors.Surface.copy(alpha = 0.6f),
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
            contentColor = if (query.isNotEmpty()) NasMusicColors.Warning else NasMusicColors.Primary,
            focusedContentColor = NasMusicColors.Primary
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (query.isNotEmpty()) "清除" else "搜索",
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * 平台切换行
 *
 * 三个按钮：网易云 / QQ音乐 / 酷狗
 * 选中状态使用 Primary 色，未选中使用 TextSecondary。
 * 内部维护标签与服务器值的映射：netease/qq/kugou
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlatformSwitchRow(
    selectedPlatform: String,
    onSelectPlatform: (String) -> Unit,
    focusRequester: FocusRequester? = null
) {
    data class PlatformInfo(val server: String, val labelResId: Int)

    val platforms = remember {
        listOf(
            PlatformInfo("netease", R.string.network_platform_netease),
            PlatformInfo("qq", R.string.network_platform_qq),
            PlatformInfo("kugou", R.string.network_platform_kugou)
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        platforms.forEachIndexed { index, platform ->
            val isSelected = platform.server == selectedPlatform
            val label = stringResource(platform.labelResId)
            FocusableSurface(
                onClick = { onSelectPlatform(platform.server) },
                modifier = if (index == 0 && focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
                shape = RoundedCornerShape(6.dp),
                focusedScale = 1.1f,
                animationDurationMs = 150,
                containerColor = if (isSelected) NasMusicColors.Primary
                                 else NasMusicColors.Surface.copy(alpha = 0.8f),
                focusedContainerColor = if (isSelected) NasMusicColors.Primary
                                         else NasMusicColors.Primary.copy(alpha = 0.3f),
                contentColor = if (isSelected) Color.Black
                               else NasMusicColors.TextSecondary,
                focusedContentColor = if (isSelected) Color.Black else NasMusicColors.Primary
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * 推荐歌单卡片
 *
 * 正方形封面区域（CoverCarousel 轮播），下方展示歌单名称和歌曲数量。
 * 聚焦时放大，点击跳转歌单详情。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistCard(
    playlist: Playlist,
    songs: List<Song>,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.width(180.dp),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.06f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            // 正方形封面区域（CoverCarousel 轮播）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NasMusicColors.SurfaceVariant)
            ) {
                CoverCarousel(
                    coverCandidates = playlist.coverUrls,
                    isPlaying = false,
                    autoCycle = true,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
                // 歌曲数量角标
                if (playlist.songCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(
                                NasMusicColors.Primary.copy(alpha = 0.95f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${playlist.songCount}首",
                            color = Color.Black,
                            fontSize = 9.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = playlist.name,
                color = NasMusicColors.TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${playlist.songCount}首",
                color = NasMusicColors.TextSecondary,
                fontSize = 10.sp
            )
        }
    }
}

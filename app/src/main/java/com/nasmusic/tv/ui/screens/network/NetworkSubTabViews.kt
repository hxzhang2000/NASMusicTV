package com.nasmusic.tv.ui.screens.network

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.CoverCarousel
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.screens.SongRow
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 发现子 Tab — 聚合首页：天气入口 + 推荐歌单 + 快捷功能 + 我的收藏
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DiscoverContent(
    networkPlaylists: List<Pair<Playlist, List<Song>>>,
    networkFavoriteSongs: List<Song>,
    networkFavoriteIds: Set<String>,
    queueSongIds: Set<String>,
    recentNetworkSongs: List<Song> = emptyList(),
    currentNetworkSong: Song? = null,
    onPlayNetworkSong: (Song) -> Unit,
    onToggleNetworkFavorite: (Song) -> Unit,
    onToggleQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    onPlayAllSongs: (List<Song>) -> Unit,
    onLoadPlaylistDetail: (Pair<Playlist, List<Song>>) -> Unit,
    onNavigateToPlaylistDetail: () -> Unit,
    onNavigateToScreen: (String) -> Unit = {}
) {
    val listState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            if (!atTop) {
                scope.launch { listState.scrollToItem(0) }
                true
            } else {
                false
            }
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. 推荐歌单
        if (networkPlaylists.isNotEmpty()) {
            item(key = "trending_header", span = { GridItemSpan(2) }) {
                Text(
                    text = "推荐歌单",
                    color = NasMusicColors.TextPrimary,
                    fontSize = 21.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            item(key = "playlist_cards", span = { GridItemSpan(2) }) {
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

        // 3. 快捷功能入口
        item(key = "feature_shortcuts", span = { GridItemSpan(2) }) {
            FeatureShortcuts(
                favoriteCount = networkFavoriteSongs.size,
                onNavigateToFavorites = { onNavigateToScreen("favorites") },
                onNavigateToQueue = { onNavigateToScreen("queue") },
                onPlayPrivateRadio = { onNavigateToScreen("radio") }
            )
        }

        // 4. 继续听 — 队列中的网络歌曲
        val hasCurrentSong = currentNetworkSong != null
        val hasRecentSongs = recentNetworkSongs.isNotEmpty()
        if (hasCurrentSong || hasRecentSongs) {
            item(key = "continue_listening_header", span = { GridItemSpan(2) }) {
                Text(
                    text = "继续听",
                    color = NasMusicColors.TextPrimary,
                    fontSize = 21.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            // 当前正在播放的网络歌曲
            if (hasCurrentSong) {
                item(key = "now_playing_section", span = { GridItemSpan(2) }) {
                    Text(
                        text = "正在播放",
                        color = NasMusicColors.TextSecondary,
                        fontSize = 17.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                item(key = "now_playing") {
                    SongRow(
                        song = currentNetworkSong!!,
                        onClick = { onPlayNetworkSong(currentNetworkSong!!) },
                        isInQueue = false,
                        isFavorited = currentNetworkSong!!.id in networkFavoriteIds,
                        onToggleFavorite = { onToggleNetworkFavorite(currentNetworkSong!!) },
                        onAddToPlaylist = { onAddToPlaylist(currentNetworkSong!!) }
                    )
                }
            }
            // 队列中的其它网络歌曲
            if (hasRecentSongs) {
                if (hasCurrentSong) {
                    item(key = "up_next_header", span = { GridItemSpan(2) }) {
                        Text(
                            text = "即将播放",
                            color = NasMusicColors.TextSecondary,
                            fontSize = 17.sp,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                }
                recentNetworkSongs.forEachIndexed { index, song ->
                    item(key = "continue_${song.id}") {
                        SongRow(
                            song = song,
                            index = index,
                            onClick = { onPlayNetworkSong(song) },
                            isInQueue = song.id in queueSongIds,
                            onToggleQueue = { onToggleQueue(song) },
                            isFavorited = song.id in networkFavoriteIds,
                            onToggleFavorite = { onToggleNetworkFavorite(song) },
                            onAddToPlaylist = { onAddToPlaylist(song) }
                        )
                    }
                }
            }
        }

        // 5. 我的收藏（最多显示最近 10 条）
        if (networkFavoriteSongs.isNotEmpty()) {
            item(key = "favorites_header", span = { GridItemSpan(2) }) {
                Text(
                    text = stringResource(R.string.network_my_favorites),
                    color = NasMusicColors.TextPrimary,
                    fontSize = 21.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
            }
            val displayFavorites = networkFavoriteSongs.take(10)
            itemsIndexed(
                displayFavorites,
                key = { _, song -> "fav_${song.id}" }
            ) { index, song ->
                SongRow(
                    song = song,
                    index = index,
                    onClick = { onPlayNetworkSong(song) },
                    isInQueue = false,
                    isFavorited = true,
                    onToggleFavorite = { onToggleNetworkFavorite(song) },
                    onAddToPlaylist = { onAddToPlaylist(song) }
                )
            }
        }

        // 底部间距
        item(key = "bottom_spacer", span = { GridItemSpan(2) }) {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}


/**
 * 快捷功能入口行
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FeatureShortcuts(
    favoriteCount: Int,
    onNavigateToFavorites: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onPlayPrivateRadio: () -> Unit
) {
    Column {
        Text(
            text = "快捷功能",
            color = NasMusicColors.TextPrimary,
            fontSize = 21.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 私人电台
            FeatureCard(
                icon = "\uD83C\uDFB5",
                title = "私人电台",
                subtitle = "随机播放好歌",
                onClick = onPlayPrivateRadio
            )
            // 我的收藏
            FeatureCard(
                icon = "\u2764\uFE0F",
                title = "我的收藏",
                subtitle = if (favoriteCount > 0) "共 ${favoriteCount} 首" else "收藏喜欢的歌曲",
                onClick = onNavigateToFavorites
            )
            // 播放队列
            FeatureCard(
                icon = "\uD83D\uDC42",
                title = "播放队列",
                subtitle = "查看和管理队列",
                onClick = onNavigateToQueue
            )
        }
    }
}

/**
 * 快捷功能卡片
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FeatureCard(
    icon: String,
    title: String,
    subtitle: String,
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
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = icon, fontSize = 33.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                fontSize = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = NasMusicColors.TextSecondary,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 天气子 Tab — 占位符
 */
@Composable
fun WeatherPlaceholder() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "\u2601\uFE0F",
                fontSize = 53.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.network_weather_coming_soon),
                color = NasMusicColors.TextSecondary,
                fontSize = 21.sp
            )
        }
    }
}

/**
 * 推荐歌单卡片（从原有 NetworkScreen 搬移）
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
            // 正方形封面区域
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
                            color = NasMusicColors.TextPrimary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = playlist.name,
                color = NasMusicColors.TextPrimary,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${playlist.songCount}首",
                color = NasMusicColors.TextSecondary,
                fontSize = 15.sp
            )
        }
    }
}

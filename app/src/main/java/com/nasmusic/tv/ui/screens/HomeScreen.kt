package com.nasmusic.tv.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.HomeDashboardData
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.WeatherData
import com.nasmusic.tv.data.model.WeatherMood
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 首页仪表盘
 *
 * 连接 NAS 后展示音乐库概览，提供快捷入口和推荐内容。
 * 结构（从上到下）：
 * 1. 欢迎区域（服务器名称 + 统计卡片）
 * 2. 最新添加专辑（横向滚动）
 * 3. 推荐/热门歌曲
 * 4. 天气信息入口
 * 5. 快捷操作（搜索、网络音乐等）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    isConnected: Boolean,
    isLibraryLoading: Boolean,
    serverDisplayName: String,
    dashboardData: HomeDashboardData,
    weatherData: WeatherData?,
    recentSongs: List<Song>,
    onPlaySong: (Song) -> Unit,
    onPlayAlbum: (Album) -> Unit,
    onOpenAlbumDetail: (Album) -> Unit,
    onNavigateToLibrary: () -> Unit = {},
    onNavigateToNetwork: () -> Unit = {},
    onNavigateToQueue: () -> Unit = {},
    onPlayAllRecent: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // Level 1.5: 列表回顶
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

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. 欢迎 + 统计卡片
        item(key = "welcome") {
            WelcomeSection(
                isConnected = isConnected,
                isLibraryLoading = isLibraryLoading,
                serverDisplayName = serverDisplayName,
                dashboardData = dashboardData,
                weatherData = weatherData
            )
        }

        // 2. 快捷操作按钮
        item(key = "quick_actions") {
            QuickActionRow(
                onNavigateToLibrary = onNavigateToLibrary,
                onNavigateToNetwork = onNavigateToNetwork,
                onNavigateToQueue = onNavigateToQueue
            )
        }

        // 3. 最新添加专辑（仅当有数据时显示）
        val recentAlbums = dashboardData.recentlyAddedAlbums
        if (recentAlbums.isNotEmpty()) {
            item(key = "recent_albums_header") {
                SectionHeader(
                    title = stringResource(R.string.library_recently_added),
                    count = recentAlbums.size
                )
            }
            item(key = "recent_albums") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recentAlbums, key = { it.id }) { album ->
                        HomeAlbumCard(
                            album = album,
                            onClick = { onOpenAlbumDetail(album) },
                            onPlay = { onPlayAlbum(album) }
                        )
                    }
                }
            }
        }

        // 4. 最近播放歌曲
        if (recentSongs.isNotEmpty()) {
            item(key = "recent_played_header") {
                SectionHeader(
                    title = stringResource(R.string.library_recent),
                    count = recentSongs.size,
                    onViewAll = { onPlayAllRecent() }
                )
            }
            item(key = "recent_played") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recentSongs.take(20), key = { it.id }) { song ->
                        HomeSongCard(
                            song = song,
                            onClick = { onPlaySong(song) }
                        )
                    }
                }
            }
        }

        // 5. 收藏歌曲展示
        val favoriteSongs = dashboardData.favoriteSongs
        if (favoriteSongs.isNotEmpty()) {
            item(key = "favorites_header") {
                SectionHeader(
                    title = stringResource(R.string.library_favorites),
                    count = favoriteSongs.size
                )
            }
            item(key = "favorites") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(favoriteSongs.take(20), key = { it.id }) { song ->
                        HomeSongCard(
                            song = song,
                            onClick = { onPlaySong(song) }
                        )
                    }
                }
            }
        }

        // 6. 天气信息（仅当有数据时）
        if (weatherData != null) {
            item(key = "weather_card") {
                HomeWeatherCard(weatherData = weatherData)
            }
        }

        // 底部间距
        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 欢迎区域 + 统计卡片
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WelcomeSection(
    isConnected: Boolean,
    isLibraryLoading: Boolean,
    serverDisplayName: String,
    dashboardData: HomeDashboardData,
    weatherData: WeatherData?
) {
    Column {
        // 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(NasMusicColors.Primary, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "\u266A", color = Color.Black, fontSize = 22.sp)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = if (isConnected) serverDisplayName.ifBlank { stringResource(R.string.app_name) }
                           else stringResource(R.string.app_name),
                    color = NasMusicColors.TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isLibraryLoading) stringResource(R.string.common_loading)
                           else if (!isConnected) stringResource(R.string.common_not_connected)
                           else stringResource(R.string.home_ready),
                    color = if (isConnected) NasMusicColors.Success else NasMusicColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 统计卡片行
        if (isConnected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    StatCard(label = stringResource(R.string.home_album_count), value = "${dashboardData.totalAlbums}")
                }
                Box(modifier = Modifier.weight(1f)) {
                    StatCard(label = stringResource(R.string.home_song_count), value = "${dashboardData.totalSongs}")
                }
                Box(modifier = Modifier.weight(1f)) {
                    StatCard(label = stringResource(R.string.home_artist_count), value = "${dashboardData.totalArtists}")
                }
                Box(modifier = Modifier.weight(1f)) {
                    StatCard(label = stringResource(R.string.home_playlist_count), value = "${dashboardData.totalPlaylists}")
                }
            }
        }
    }
}

/**
 * 统计小卡片
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatCard(
    label: String,
    value: String
) {
    FocusableSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.04f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface.copy(alpha = 0.5f),
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = NasMusicColors.Primary
            )
            Text(
                text = label,
                fontSize = 12.sp,
                color = NasMusicColors.TextSecondary
            )
        }
    }
}

/**
 * 快捷操作行
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QuickActionRow(
    onNavigateToLibrary: () -> Unit,
    onNavigateToNetwork: () -> Unit,
    onNavigateToQueue: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            QuickActionButton(
                label = stringResource(R.string.nav_library),
                emoji = "\uD83D\uDCC2",
                onClick = onNavigateToLibrary
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            QuickActionButton(
                label = "网络音乐",
                emoji = "\uD83C\uDFB5",
                onClick = onNavigateToNetwork
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            QuickActionButton(
                label = stringResource(R.string.nav_queue),
                emoji = "\uD83D\uDD00",
                onClick = onNavigateToQueue
            )
        }
    }
}

/**
 * 快捷操作按钮
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QuickActionButton(
    label: String,
    emoji: String,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.06f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface.copy(alpha = 0.5f),
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = emoji, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 节标题
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    onViewAll: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = NasMusicColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = " ($count)",
            color = NasMusicColors.TextSecondary,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.weight(1f))
        if (onViewAll != null) {
            FocusableSurface(
                onClick = onViewAll,
                shape = RoundedCornerShape(6.dp),
                focusedScale = 1.08f,
                animationDurationMs = 150,
                containerColor = Color.Transparent,
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                contentColor = NasMusicColors.Primary,
                focusedContentColor = NasMusicColors.Primary
            ) {
                    Text(
                        text = stringResource(R.string.home_view_all) + " >",
                        fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * 首页专辑卡片（方形封面）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeAlbumCard(
    album: Album,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.width(160.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.06f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface.copy(alpha = 0.6f),
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(6.dp)
        ) {
            // 正方形封面
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NasMusicColors.SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!album.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = album.coverUrl,
                        contentDescription = album.name,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(text = "\u266A", color = NasMusicColors.TextSecondary, fontSize = 32.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = album.name,
                color = NasMusicColors.TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.artist.ifBlank { "\u2014" },
                color = NasMusicColors.TextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 首页歌曲卡片（竖版）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeSongCard(
    song: Song,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.width(160.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.06f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface.copy(alpha = 0.6f),
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(6.dp)
        ) {
            // 封面
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NasMusicColors.SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!song.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = song.coverUrl,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(text = "\u266A", color = NasMusicColors.TextSecondary, fontSize = 32.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = song.title,
                color = NasMusicColors.TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist.ifBlank { "\u2014" },
                color = NasMusicColors.TextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 天气小卡片（首页嵌入）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeWeatherCard(
    weatherData: WeatherData
) {
    val mood = WeatherMood.fromWeather(weatherData)
    val bgColor = Color(0x332DD4BF)

    FocusableSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.01f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface.copy(alpha = 0.3f),
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.1f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = mood.icon, fontSize = 36.sp)
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "${weatherData.temperature.toInt()}\u00B0C",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = NasMusicColors.TextPrimary
                )
                Text(
                    text = "${weatherData.cityName}  \u00B7  ${weatherData.description}",
                    fontSize = 12.sp,
                    color = NasMusicColors.TextSecondary
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "\uD83C\uDFB2 " + stringResource(R.string.home_weather_radio),
                fontSize = 13.sp,
                color = NasMusicColors.Primary
            )
        }
    }
}

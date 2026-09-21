package com.nasmusic.tv.ui.screens

import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.UiMode

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.HomeDashboardData
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.WeatherData
import com.nasmusic.tv.data.model.WeatherMood
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
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
    weatherLoading: Boolean = false,
    weatherError: String? = null,
    recentSongs: List<Song>,
    currentSong: Song? = null,
    coverCandidates: List<String> = emptyList(),
    onPlaySong: (Song) -> Unit,
    onPlayAlbum: (Album) -> Unit,
    onOpenAlbumDetail: (Album) -> Unit,
    onNavigateToLibrary: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToQueue: () -> Unit = {},
    onNavigateToNowPlaying: () -> Unit = {},
    onPlayAllRecent: () -> Unit = {},
    onNavigateToWeatherRadio: () -> Unit = {},
    randomSongs: List<Song> = emptyList(),
    onPlayRandomSongs: (List<Song>, Int) -> Unit = { _, _ -> },
    /** F2-3 首页列表化：智能电台浏览批次（随心听式），onLoadSmartRadio = null 隐藏区块 */
    smartRadioBatch: List<Song> = emptyList(),
    /** 播放批次中第 index 首 */
    onPlaySmartRadioAt: (Int) -> Unit = {},
    /** 生成/换一批（只生成不播放；首次进入自动触发） */
    onLoadSmartRadio: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current
    // v2.36.0：LazyColumn 内容 lambda 不是 @Composable 上下文，形态必须在组合内先读出来
    val uiMode = LocalUiMode.current
    val isPhonePortrait = uiMode == UiMode.PhonePortrait

    // F2-3 首页列表化：首次进入自动生成智能电台批次（只生成不播放；防重入在 MainViewModel）
    LaunchedEffect(Unit) { onLoadSmartRadio?.invoke() }

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
            // v2.36.0 竖屏（方案 §4.1）：页 padding 32→16，窄屏可用宽度 328dp
            .padding(horizontal = if (isPhonePortrait) 16.dp else 32.dp),
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

        // 1.5 当前播放卡片（有歌曲正在播放时显示）
        // v2.36.0 竖屏：与底部 MiniPlayer 重复 → 隐藏（方案 §4.1）
        // v2.36.0 横屏体验修复（用户反馈「横屏不要 mini 播放条，太占空间」）：
        // 这是一条**全宽 72dp** 的横向播放条（48dp 封面 + 歌名/艺术家 + 「正在播放 ▶」）。
        // 手机横屏的可用高度只有 ~439 Compose dp，它一条就吃掉 ~16%，而 TV 顶栏本就有
        // 「正在播放」入口 —— 故手机横屏也一并隐藏。
        // ⚠️ B1 说明：这里**显式**读 `LocalUiMode` 做"横屏独立分支"，理由是用户明确要求；
        //    TV 端行为保持不变（仍是原来的显示条件），故不构成 TV 回归。
        if (currentSong != null && uiMode == UiMode.TV) {
            item(key = "now_playing") {
                NowPlayingCard(
                    song = currentSong,
                    coverCandidates = coverCandidates,
                    onClick = onNavigateToNowPlaying
                )
            }
        }

        // 2. 快捷操作按钮
        // v2.36.2 竖屏：**移除**「曲库 / 搜索 / 播放队列」快捷行 —— 三者均有更近的常驻入口
        // （曲库/队列在底部导航、搜索在顶栏右上角），首页再放一遍属于重复。
        // ⚠️ 仅竖屏隐藏；TV 端无底部导航，快捷行保留（B1：TV 行为逐字不变）。
        if (!isPhonePortrait) {
            item(key = "quick_actions") {
                QuickActionRow(
                    onNavigateToLibrary = onNavigateToLibrary,
                    onNavigateToSearch = onNavigateToSearch,
                    onNavigateToQueue = onNavigateToQueue
                )
            }
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

        // 4.5 随心听（随机歌曲推荐）
        if (randomSongs.isNotEmpty()) {
            item(key = "shuffle_play_header") {
                SectionHeader(
                    title = stringResource(R.string.home_shuffle_play),
                    count = randomSongs.size
                )
            }
            item(key = "shuffle_play") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(randomSongs, key = { it.id }) { song ->
                        HomeSongCard(
                            song = song,
                            onClick = { onPlayRandomSongs(randomSongs, randomSongs.indexOf(song)) }
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

        // 5.5 智能电台（F2-3 首页列表化：随心听式——展示推荐批次，点卡片播歌，"换一批"重新生成）
        if (onLoadSmartRadio != null && smartRadioBatch.isNotEmpty()) {
            item(key = "smart_radio_header") {
                SectionHeader(
                    title = stringResource(R.string.home_smart_radio),
                    count = smartRadioBatch.size,
                    actionLabel = stringResource(R.string.home_smart_radio_next),
                    onAction = onLoadSmartRadio
                )
            }
            item(key = "smart_radio") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(smartRadioBatch, key = { _, s -> s.id }) { index, song ->
                        HomeSongCard(
                            song = song,
                            onClick = { onPlaySmartRadioAt(index) }
                        )
                    }
                }
            }
        }

        // 6. 天气信息（加载/错误/正常三种状态，与网络音乐页面统一）
        item(key = "weather_card") {
            HomeWeatherCard(
                weatherData = weatherData,
                isLoading = weatherLoading,
                errorMessage = weatherError,
                onClick = onNavigateToWeatherRadio
            )
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
                Text(text = "\u266A", color = NasMusicColors.TextPrimary, fontSize = FontSize.title())
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = if (isConnected) serverDisplayName.ifBlank { stringResource(R.string.app_name) }
                           else stringResource(R.string.app_name),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.display(),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isLibraryLoading) stringResource(R.string.common_loading)
                           else if (!isConnected) stringResource(R.string.common_not_connected)
                           else stringResource(R.string.home_ready),
                    color = if (isConnected) NasMusicColors.Success else NasMusicColors.TextSecondary,
                    fontSize = FontSize.body()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 统计卡片：v2.36.0 竖屏改 2×2 网格（4 张横排竖屏必溢出，方案 §4.1）
        if (isConnected) {
            val stats = listOf(
                stringResource(R.string.home_album_count) to "${dashboardData.totalAlbums}",
                stringResource(R.string.home_song_count) to "${dashboardData.totalSongs}",
                stringResource(R.string.home_artist_count) to "${dashboardData.totalArtists}",
                stringResource(R.string.home_playlist_count) to "${dashboardData.totalPlaylists}",
            )
            if (LocalUiMode.current == UiMode.PhonePortrait) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    stats.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowItems.forEach { (label, value) ->
                                Box(modifier = Modifier.weight(1f)) {
                                    StatCard(label = label, value = value)
                                }
                            }
                            // 奇数个时补一个占位，保持等宽
                            if (rowItems.size == 1) {
                                Box(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    stats.forEach { (label, value) ->
                        Box(modifier = Modifier.weight(1f)) {
                            StatCard(label = label, value = value)
                        }
                    }
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
                fontSize = FontSize.display(),
                fontWeight = FontWeight.Bold,
                color = NasMusicColors.Primary
            )
            Text(
                text = label,
                fontSize = FontSize.body(),
                color = LocalFocusableContentColor.current
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
    onNavigateToSearch: () -> Unit,
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
                label = stringResource(R.string.common_search),
                emoji = "\uD83D\uDD0D",
                onClick = onNavigateToSearch
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
        focusedContentColor = NasMusicColors.TextPrimary
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = emoji, fontSize = FontSize.title(), color = LocalFocusableContentColor.current)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                color = LocalFocusableContentColor.current,
                fontSize = FontSize.button(),
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
    onViewAll: (() -> Unit)? = null,
    /** 自定义右侧操作按钮文案（如"换一批"）；设置后优先于 onViewAll 渲染 */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.subtitle(),
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = " ($count)",
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.button()
        )
        Spacer(modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            FocusableSurface(
                onClick = onAction,
                shape = RoundedCornerShape(6.dp),
                focusedScale = 1.08f,
                animationDurationMs = 150,
                containerColor = Color.Transparent,
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                contentColor = NasMusicColors.Primary,
                focusedContentColor = NasMusicColors.Primary
            ) {
                    Text(
                        text = actionLabel,
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.body(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        } else if (onViewAll != null) {
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
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.body(),
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
        // v2.36.0 竖屏收窄到 140dp（方案 §3.4：横向 LazyRow 内一屏能露出更多张）
        modifier = Modifier.width(if (LocalUiMode.current == UiMode.PhonePortrait) 140.dp else 160.dp),
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
                    Text(text = "\u266A", color = LocalFocusableContentColor.current, fontSize = FontSize.display())
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = album.name,
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.body(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.artist.ifBlank { "\u2014" },
                color = LocalFocusableContentColor.current,
                fontSize = FontSize.small(),
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
        // v2.36.0 竖屏收窄到 140dp（方案 §3.4）
        modifier = Modifier.width(if (LocalUiMode.current == UiMode.PhonePortrait) 140.dp else 160.dp),
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
                    Text(text = "\u266A", color = LocalFocusableContentColor.current, fontSize = FontSize.display())
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = song.title,
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.body(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist.ifBlank { "\u2014" },
                color = LocalFocusableContentColor.current,
                fontSize = FontSize.small(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 当前播放小卡片（首页嵌入）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NowPlayingCard(
    song: Song,
    coverCandidates: List<String> = emptyList(),
    onClick: () -> Unit
) {
    // 优先使用 coverCandidates（含后端封面候选+回退），与原 coverUrl 兼容
    val effectiveCoverUrl = coverCandidates.firstOrNull() ?: song.coverUrl

    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.02f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Primary.copy(alpha = 0.12f),
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面缩略图
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NasMusicColors.SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!effectiveCoverUrl.isNullOrBlank()) {
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = effectiveCoverUrl,
                            contentScale = ContentScale.Crop
                        ),
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(text = "\u266A", color = LocalFocusableContentColor.current, fontSize = FontSize.title())
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!song.artist.isNullOrBlank()) {
                    Text(
                        text = song.artist,
                        color = LocalFocusableContentColor.current,
                        fontSize = FontSize.body(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.nav_now_playing) + " \u25B6",
                color = NasMusicColors.Primary,
                fontSize = FontSize.body()
            )
        }
    }
}

/**
 * 天气小卡片（首页嵌入，与网络音乐页面 WeatherInfoCard 统一的状态处理）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeWeatherCard(
    weatherData: WeatherData?,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onClick: () -> Unit = {}
) {
    val bgColor = if (weatherData != null) {
        val mood = WeatherMood.fromWeather(weatherData)
        Color(0x332DD4BF)
    } else {
        NasMusicColors.Surface.copy(alpha = 0.3f)
    }

    FocusableSurface(
        onClick = onClick,
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
            if (isLoading && weatherData == null) {
                Text(
                    text = stringResource(R.string.common_loading),
                    color = LocalFocusableContentColor.current,
                    fontSize = FontSize.button()
                )
            } else if (weatherData != null) {
                val mood = WeatherMood.fromWeather(weatherData)
                Text(text = mood.icon, fontSize = FontSize.displayLarge(), color = LocalFocusableContentColor.current)
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "${weatherData.temperature.toInt()}\u00B0C",
                        fontSize = FontSize.title(),
                        fontWeight = FontWeight.Bold,
                        color = NasMusicColors.TextPrimary
                    )
                    Text(
                        text = "${weatherData.cityName}  \u00B7  ${weatherData.description}",
                        fontSize = FontSize.body(),
                        color = LocalFocusableContentColor.current
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "\uD83C\uDFB2 " + stringResource(R.string.home_weather_radio),
                    fontSize = FontSize.body(),
                    color = NasMusicColors.Primary
                )
            } else if (errorMessage != null) {
                Text(
                    text = "⚠ $errorMessage",
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.body()
                )
            } else {
                Text(
                    text = stringResource(R.string.common_loading),
                    color = LocalFocusableContentColor.current,
                    fontSize = FontSize.button()
                )
            }
        }
    }
}

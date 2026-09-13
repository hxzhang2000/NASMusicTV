package com.nasmusic.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.EqualizerPreset
import com.nasmusic.tv.data.model.HomeDashboardData
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SearchHistoryItem
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.ui.LocalNavigateBackHandler
import com.nasmusic.tv.ui.screens.AlbumDetailScreen
import com.nasmusic.tv.ui.screens.ArtistDetailScreen
import com.nasmusic.tv.ui.screens.EqualizerScreen
import com.nasmusic.tv.ui.screens.HomeScreen
import com.nasmusic.tv.ui.screens.LibraryScreen
import com.nasmusic.tv.ui.screens.LibraryTab
import com.nasmusic.tv.ui.screens.MineScreen

import com.nasmusic.tv.ui.screens.NowPlayingScreen
import com.nasmusic.tv.ui.screens.PlaylistManagementScreen
import com.nasmusic.tv.ui.screens.PlaylistPickerDialog
import com.nasmusic.tv.ui.screens.QueueScreen
import com.nasmusic.tv.ui.screens.ServerConnectScreen
import com.nasmusic.tv.ui.screens.SettingsScreen
import com.nasmusic.tv.ui.screens.BackupTransferDialog
import com.nasmusic.tv.ui.screens.ModelTransferDialog
import com.nasmusic.tv.ui.theme.NASMusicTVTheme
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.components.branches.*
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.data.model.Screen
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppRoot(
    viewModel: MainViewModel,
    isImmersiveMode: androidx.compose.runtime.MutableState<Boolean>,
    onConnect: (ServerConfig) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isTV = remember {
        // 与 MainActivity/NasMusicApp/TextInputDialog 一致：很多非认证 TV 盒子
        // 只上报 android.hardware.type.television 而无 leanback 特性，
        // 单查 leanback 会把 TV 误判为手机（v2.20.0 手机端支持引入的回归，
        // 曾导致 K 歌/MTV 页手机遥控二维码因 remoteControlUrl 被置 null 而消失）
        context.packageManager.hasSystemFeature("android.software.leanback") ||
            context.packageManager.hasSystemFeature("android.hardware.type.television")
    }
    val currentScreen by viewModel.navVM.currentScreen.collectAsState(initial = Screen.Home)
    val currentSong by viewModel.playerVM.currentSong.collectAsState(initial = null)
    val isPlaying by viewModel.playerVM.isPlaying.collectAsState(initial = false)
    val playMode by viewModel.playerVM.playMode.collectAsState(initial = com.nasmusic.tv.data.model.PlayMode.SEQUENTIAL)
    // F-2（修复）：progress/duration 不再顶层收集——PlayerManager 的进度由 1000ms
    // Handler 轮询驱动，顶层收集会每秒驱动 AppRoot 全树重组（含 LazyColumn 状态与
    // D-Pad 焦点搜索）；下沉到 NowPlayingScreen 分支内收集（与 H-3 频谱流下沉同向）。
    val networkCoverUrl by viewModel.networkCoverUrl.collectAsState(initial = null)
    val songDownloadStates by viewModel.songDownloadStates.collectAsState(initial = emptyMap())
    val songs by viewModel.songs.collectAsState(initial = UiState.Loading as UiState<List<Song>>)
    val isLoading by viewModel.isLoading.collectAsState(initial = false)
    val isLibraryLoading by viewModel.isLibraryLoading.collectAsState(initial = false)
    val isConnected by viewModel.serverVM.isConnected.collectAsState(initial = false)
    val serverDisplayName by viewModel.serverVM.serverDisplayName.collectAsState(initial = "")
    val serverConfig by viewModel.serverConfig.collectAsState(initial = ServerConfig.Empty)
    val settings by viewModel.appSettings.collectAsState(initial = com.nasmusic.tv.data.model.AppSettings())
    // 封面滤镜状态（跨屏幕共享，用于 NowPlaying + Settings）
    val coverFilterEnabled by viewModel.prefs.visualizer.coverFilterEnabled.collectAsState(initial = false)
    val coverFilterBlurRadius by viewModel.prefs.visualizer.coverFilterBlurRadius.collectAsState(initial = 8f)
    val coverFilterDarkOverlay by viewModel.prefs.visualizer.coverFilterDarkOverlay.collectAsState(initial = 0.3f)

    // 加入歌单弹窗目标歌曲（提升到 AppRoot 顶层，供 Library / 专辑详情 / 艺术家详情 共用）
    var pickerSong by remember { mutableStateOf<Song?>(null) }
    // 天气 API Key
    // 百度网盘状态（设置页网盘分区）
    // K 歌页面显隐（切 Tab 时保持，退出 K 歌页时清除）
    // MTV 页面显隐（进入 MTV 全屏页时为 true）
    val showMv by viewModel.mvVM.showMv.collectAsState(initial = false)
    // K 歌全屏页显隐（修复：BACK 键需在 K 歌页优先退出 K 歌，而非触发应用退出确认）
    val showKaraoke by viewModel.vocalVM.showKaraoke.collectAsState(initial = false)
    // 全屏可视化舞台显隐（BACK 需在舞台页优先退出，而非触发应用退出确认）
    val showVisualizer by viewModel.visualizerVM.showVisualizer.collectAsState(initial = false)
    // MTV 搜索状态（顶层收集，供 NotFound 自动退出保护与 NowPlaying 分支共用）
    val mvState by viewModel.mvVM.mvState.collectAsState()
    // 安全兜底：切歌后新歌无 MV（NotFound）时自动退出 MTV 全屏，避免卡在无导航栏的播放页
    LaunchedEffect(mvState, showMv) {
        if (showMv && mvState is com.nasmusic.tv.ui.viewmodel.MvAvailability.NotFound) {
            viewModel.mvVM.exitMvMode()
        }
    }
    // Level 2: 根据当前屏幕和沉浸模式动态设置导航 BACK 键处理函数
    val navBackHandler = LocalNavigateBackHandler.current
    LaunchedEffect(currentScreen, isImmersiveMode.value, showMv, showKaraoke, showVisualizer) {
        // C-2 修正说明：初版审查把 when/if-else 分支里的 {{ ... }} 误判为 no-op（lambda 内 lambda）。
        // 实测编译行为：when/if 分支的 { } 按“块”解析，{{ X }} = 块 + 尾部 lambda 表达式，
        // 分支值就是可用的 lambda——原实现功能正常，并非 bug。此处改用具名 lambda 仅作可读性清理。
        val navigateHome: () -> Unit = { viewModel.navVM.navigateTo(Screen.Home) }
        val exitImmersive: () -> Unit = { isImmersiveMode.value = false }
        val exitMv: () -> Unit = { viewModel.mvVM.exitMvMode() }
        val exitKaraoke: () -> Unit = { viewModel.vocalVM.exitKaraoke() }
        val exitVisualizer: () -> Unit = { viewModel.visualizerVM.exitVisualizer() }
        val navSettings: () -> Unit = { viewModel.navVM.navigateTo(Screen.Settings) }
        val navigateMine: () -> Unit = { viewModel.navVM.navigateTo(Screen.Mine) }
        val handler: (() -> Unit)? = when {
            isImmersiveMode.value -> exitImmersive
            // 全屏可视化舞台：BACK 退出舞台，而非应用退出确认
            showVisualizer -> exitVisualizer
            // K 歌页：BACK 退出 K 歌（切回普通 NOW PLAYING），而非应用退出确认
            showKaraoke -> exitKaraoke
            showMv -> exitMv
            currentScreen == Screen.NowPlaying -> if (isTV) null else navigateHome
            currentScreen == Screen.Home -> null
            // ── 设置域子页面：BACK 返回设置主菜单（入口唯一：SettingsBranch）──
            // 修复：均衡器/播放统计此前落入 else 回首页，遥控器返回键无法回到设置
            currentScreen == Screen.ServerConnect -> navSettings
            currentScreen == Screen.Equalizer -> navSettings
            currentScreen == Screen.PlayStats -> navSettings
            // ── "我的"域子页面：BACK 返回我的页（网盘入口唯一：MineBranch）──
            // WeatherRadio 入口在首页，由 else 回首页覆盖，无需专门分支
            currentScreen == Screen.Netdisk -> navigateMine
            else -> navigateHome
        }
        navBackHandler.value = handler
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部导航栏（沉浸模式 / MTV 全屏页时隐藏；TV 与手机一致）
        if (!isImmersiveMode.value && !showMv && !showVisualizer) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.padding(end = 16.dp),
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
                        Text(text = "\u266A", color = NasMusicColors.TextPrimary, fontSize = FontSize.subtitle())
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "NAS Music", color = NasMusicColors.TextPrimary, fontSize = FontSize.subtitle())
                }

                // 导航项（外层固定宽度右对齐；内层可横向滑动——手机窄屏滚动浏览全部 tab）
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                NavItem(
                    label = stringResource(R.string.nav_home),
                    selected = currentScreen == Screen.Home,
                    onClick = { viewModel.navVM.navigateTo(Screen.Home) }
                )
                NavItem(
                    label = stringResource(R.string.nav_now_playing),
                    selected = currentScreen == Screen.NowPlaying,
                    onClick = { viewModel.navVM.navigateTo(Screen.NowPlaying) }
                )
                NavItem(
                    label = stringResource(R.string.nav_library),
                    selected = currentScreen == Screen.Library,
                    onClick = { viewModel.navVM.navigateTo(Screen.Library) }
                )
                NavItem(
                    label = stringResource(R.string.nav_mine),
                    selected = currentScreen == Screen.Mine,
                    onClick = { viewModel.navVM.navigateTo(Screen.Mine) }
                )
                NavItem(
                    label = stringResource(R.string.nav_queue),
                    selected = currentScreen == Screen.Queue,
                    onClick = { viewModel.navVM.navigateTo(Screen.Queue) }
                )
                NavItem(
                    label = stringResource(R.string.nav_settings),
                    selected = currentScreen == Screen.Settings,
                    onClick = { viewModel.navVM.navigateTo(Screen.Settings) }
                )
                }
            }
        }
        }

        // 内容区域
        // 封面候选列表（跨屏幕复用，确保首页和 NowPlaying 使用相同的封面解析逻辑）
        val coverCandidates = remember(currentSong?.id, networkCoverUrl) {
            currentSong?.let { viewModel.getCoverCandidates(it) } ?: emptyList()
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (currentScreen) {
                Screen.Home -> HomeBranch(
                    viewModel = viewModel,
                    isConnected = isConnected,
                    isLibraryLoading = isLibraryLoading,
                    serverDisplayName = serverDisplayName,
                    currentSong = currentSong,
                    coverCandidates = coverCandidates
                )
                Screen.NowPlaying -> NowPlayingBranch(
                    viewModel = viewModel,
                    isTV = isTV,
                    isImmersiveMode = isImmersiveMode,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    playMode = playMode,
                    coverCandidates = coverCandidates,
                    coverFilterEnabled = coverFilterEnabled,
                    coverFilterBlurRadius = coverFilterBlurRadius,
                    coverFilterDarkOverlay = coverFilterDarkOverlay,
                    settings = settings,
                    showMv = showMv,
                    mvState = mvState,
                    isConnected = isConnected
                )
                Screen.Library -> LibraryBranch(
                    viewModel = viewModel,
                    songsState = songs,
                    isLoading = isLoading,
                    isLibraryLoading = isLibraryLoading,
                    isConnected = isConnected,
                    songDownloadStates = songDownloadStates,
                    onPickSongForPlaylist = { song -> pickerSong = song }
                )
                Screen.Mine -> MineBranch(
                    viewModel = viewModel,
                    songDownloadStates = songDownloadStates
                )
                Screen.Queue -> QueueBranch(
                    viewModel = viewModel,
                    currentSong = currentSong,
                    coverCandidates = coverCandidates,
                    isPlaying = isPlaying,
                    playMode = playMode
                )
                Screen.Settings -> SettingsBranch(
                    viewModel = viewModel,
                    settings = settings,
                    isLoading = isLoading,
                    isConnected = isConnected,
                    serverDisplayName = serverDisplayName,
                    serverConfig = serverConfig,
                    coverFilterEnabled = coverFilterEnabled,
                    coverFilterBlurRadius = coverFilterBlurRadius,
                    coverFilterDarkOverlay = coverFilterDarkOverlay,
                    context = context,
                    coroutineScope = coroutineScope,
                    onConnect = onConnect
                )
                Screen.ServerConnect -> ServerConnectBranch(
                    viewModel = viewModel,
                    serverConfig = serverConfig,
                    isConnected = isConnected,
                    serverDisplayName = serverDisplayName,
                    isLoading = isLoading,
                    onConnect = onConnect
                )
                Screen.AlbumDetail -> AlbumDetailBranch(
                    viewModel = viewModel,
                    songDownloadStates = songDownloadStates,
                    onPickSongForPlaylist = { song -> pickerSong = song }
                )
                Screen.ArtistDetail -> ArtistDetailBranch(
                    viewModel = viewModel,
                    songDownloadStates = songDownloadStates,
                    onPickSongForPlaylist = { song -> pickerSong = song }
                )
                Screen.Equalizer -> EqualizerBranch(
                    viewModel = viewModel,
                    settings = settings
                )
                Screen.PlaylistManagement -> PlaylistManagementBranch(
                    viewModel = viewModel
                )
                Screen.Netdisk -> NetdiskBranch(
                    viewModel = viewModel
                )
                Screen.WeatherRadio -> WeatherRadioBranch(
                    viewModel = viewModel,
                    songDownloadStates = songDownloadStates
                )
                Screen.PlayStats -> PlayStatsBranch(
                    viewModel = viewModel
                )
            }
        }

        // 全屏可视化舞台（20 套效果）—— 覆盖层，不新增 Screen 枚举
        if (showVisualizer) {
            VisualizerOverlay(
                viewModel = viewModel,
                isTV = isTV,
                currentSong = currentSong,
                isPlaying = isPlaying
            )
        }

    // 加入歌单弹窗（Library / 专辑详情 / 艺术家详情 共用，提升到 AppRoot 顶层跨页面生效）
    pickerSong?.let { song ->
        val dlgPlaylists by viewModel.playlistVM.localPlaylists.collectAsState(initial = emptyList())
        PlaylistPickerDialog(
            playlists = dlgPlaylists,
            onPick = { playlist ->
                viewModel.playlistVM.addSongToPlaylist(playlist.id, song)
                pickerSong = null
            },
            onCreate = { name ->
                if (name.isNotBlank()) {
                    viewModel.playlistVM.createLocalPlaylist(name)
                }
            },
            onDismiss = { pickerSong = null }
        )
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
        contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary,
        pressedScale = 0.96f
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = if (selected) FontSize.subtitle() else FontSize.button(),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary
            )
        }
    }
}

/**
 * 全屏可视化舞台覆盖层（20 套效果）。
 *
 * 歌词 / 进度在**此处**收集而非 AppRoot 顶层——播放期间进度每秒变化，
 * 若在顶层订阅会驱动整棵 UI 树重组（同 H-3 修复的教训）。
 */
@Composable
private fun VisualizerOverlay(
    viewModel: com.nasmusic.tv.ui.viewmodel.MainViewModel,
    isTV: Boolean,
    currentSong: com.nasmusic.tv.data.model.Song?,
    isPlaying: Boolean
) {
    val vm = viewModel.visualizerVM
    val theme by vm.theme.collectAsState()
    val quality by vm.quality.collectAsState()
    val cover by vm.cover.collectAsState()
    val palette by vm.palette.collectAsState()
    val lyrics by viewModel.currentLyrics.collectAsState(initial = null)
    val progress by viewModel.playerVM.progress.collectAsState(initial = 0L)

    // 无自动导演档：用户选中的主题恒定显示，无需低频重估
    val effectiveTheme = theme

    // 封面加载 + 取色（切歌时一次，异步不阻塞）
    LaunchedEffect(currentSong?.id) {
        val url = currentSong?.let { viewModel.getCoverCandidates(it).firstOrNull() }
        vm.loadCover(url, currentSong?.id)
    }

    VisualizerStage(
        song = currentSong,
        frame = vm.frame,
        cover = cover,
        palette = palette,
        lyrics = lyrics?.lines,
        progressMs = progress,
        theme = effectiveTheme,
        quality = quality,
        isTV = isTV,
        onExit = { vm.exitVisualizer() },
        onNextTheme = { vm.nextTheme() },
        onPrevTheme = { vm.prevTheme() }
    )
}

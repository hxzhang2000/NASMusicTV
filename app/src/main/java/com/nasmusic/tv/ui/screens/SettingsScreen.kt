package com.nasmusic.tv.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.ui.components.BaiduDirPickerDialog
import com.nasmusic.tv.ui.components.ConfirmDialog
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.portraitTouchTarget
import com.nasmusic.tv.ui.components.responsiveDialogSize
import com.nasmusic.tv.ui.screens.netdisk.BaiduAuthDialog
import com.nasmusic.tv.ui.screens.settings.AboutSettingsSection
import com.nasmusic.tv.ui.screens.settings.AboutSettingsState
import com.nasmusic.tv.ui.screens.settings.BaiduPanDialogActions
import com.nasmusic.tv.ui.screens.settings.BaiduPanSettingsActions
import com.nasmusic.tv.ui.screens.settings.BaiduPanSettingsSection
import com.nasmusic.tv.ui.screens.settings.BaiduPanSettingsState
import com.nasmusic.tv.ui.screens.settings.CacheSettingsActions
import com.nasmusic.tv.ui.screens.settings.CacheSettingsSection
import com.nasmusic.tv.ui.screens.settings.CacheSettingsState
import com.nasmusic.tv.ui.screens.settings.DataSettingsActions
import com.nasmusic.tv.ui.screens.settings.DataSettingsSection
import com.nasmusic.tv.ui.screens.settings.DataSettingsState
import com.nasmusic.tv.ui.screens.settings.DownloadSettingsActions
import com.nasmusic.tv.ui.screens.settings.DownloadSettingsSection
import com.nasmusic.tv.ui.screens.settings.DownloadSettingsState
import com.nasmusic.tv.ui.screens.settings.GeneralSettingsActions
import com.nasmusic.tv.ui.screens.settings.GeneralSettingsSection
import com.nasmusic.tv.ui.screens.settings.GeneralSettingsState
import com.nasmusic.tv.ui.screens.settings.NetworkMusicDialogActions
import com.nasmusic.tv.ui.screens.settings.NetworkMusicSection
import com.nasmusic.tv.ui.screens.settings.NetworkMusicSettingsActions
import com.nasmusic.tv.ui.screens.settings.NetworkMusicSettingsState
import com.nasmusic.tv.ui.screens.settings.PlayerSettingsActions
import com.nasmusic.tv.ui.screens.settings.PlayerSettingsSection
import com.nasmusic.tv.ui.screens.settings.PlayerSettingsState
import com.nasmusic.tv.ui.screens.settings.ServerSettingsActions
import com.nasmusic.tv.ui.screens.settings.ServerSettingsSection
import com.nasmusic.tv.ui.screens.settings.ServerSettingsState
import com.nasmusic.tv.ui.screens.settings.SettingsSection
import com.nasmusic.tv.ui.screens.settings.SettingsSectionBackHeader
import com.nasmusic.tv.ui.screens.settings.SettingsSectionList
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.UiMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置屏幕 — 左侧为导航侧边栏（settings-sidebar），右侧为具体选项（settings-content）
 *
 * R-2 拆分：各分区内容已迁至 ui/screens/settings/ 下的 Section Composable
 * （State/Actions data class 分组签名，W0 冻结版）；本文件仅保留侧栏导航、
 * 分区路由、对话框宿主与对外参数签名（AppRoot 引用不变）。
 *
 * v2.36.0：竖屏（`UiMode.PhonePortrait`）改为**两级页** —— 一级为分区列表、
 * 二级为分区内容全屏 + 返回头；`selectedSection` 状态由 `NavigationViewModel` 持有
 * （`AppRoot` 的 BACK handler 需要读它，方案 §6.2 / §8.7 / K2）。
 * TV 与手机横屏**保持现状左右分栏，零改动**（方案 §3.1 B1 硬规则）。
 */

@OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onToggleDarkTheme: (Boolean) -> Unit,
    onToggleAnimations: (Boolean) -> Unit,
    onToggleAutoPlayNext: (Boolean) -> Unit,
    onChangePlayMode: (PlayMode) -> Unit,
    onToggleCacheLyrics: (Boolean) -> Unit,
    onToggleCacheCover: (Boolean) -> Unit,
    onChangeLyricsOffset: (Long) -> Unit,
    onClearLyricsCache: (() -> Unit)? = null,
    onClearCoverCache: (() -> Unit)? = null,
    onClearMvCache: (() -> Unit)? = null,
    onClearAccompanimentCache: (() -> Unit)? = null,
    onOpenEqualizer: (() -> Unit)? = null,
    onOpenPlayStats: (() -> Unit)? = null,
    // F2-5：跨曲交叉淡入淡出
    crossfadeEnabled: Boolean = false,
    crossfadeDurationSec: Int = 4,
    onToggleCrossfade: (Boolean) -> Unit = {},
    onChangeCrossfadeDuration: (Int) -> Unit = {},
    // F2-6：音质档位
    qualityTier: Int = 0,
    onChangeQualityTier: (Int) -> Unit = {},
    /** v2.35.0 多码率：清除全部单曲音质覆盖（方案 §5.3） */
    onClearQualityOverrides: (() -> Unit)? = null,
    onChangeMetingApiBaseUrl: ((String) -> Unit)? = null,
    // MTV 视频搜索端点配置
    mvApiBaseUrl: String = "",
    onChangeMvApiBaseUrl: ((String) -> Unit)? = null,
    // 网络歌词端点配置
    lyricsKugouBaseUrl: String = "",
    onChangeLyricsKugouBaseUrl: ((String) -> Unit)? = null,
    lyricsNeteaseBaseUrl: String = "",
    onChangeLyricsNeteaseBaseUrl: ((String) -> Unit)? = null,
    // Jamendo（CC 独立音乐）Client ID
    jamendoClientId: String = "",
    onChangeJamendoClientId: ((String) -> Unit)? = null,
    // 封面滤镜设置
    coverFilterEnabled: Boolean = false,
    coverFilterBlurRadius: Float = 8f,
    coverFilterDarkOverlay: Float = 0.3f,
    onToggleCoverFilter: (Boolean) -> Unit = {},
    onChangeCoverBlurRadius: (Float) -> Unit = {},
    onChangeCoverDarkOverlay: (Float) -> Unit = {},
    // 天气 API Key 设置
    weatherApiKey: String = "",
    onChangeWeatherApiKey: ((String) -> Unit)? = null,
    // 频谱显示设置
    // 全局字体字号调整
    fontAdjustment: Int = 0,
    onChangeFontAdjustment: (Int) -> Unit = {},
    // 语言设置
    language: String = "system",
    onChangeLanguage: ((String) -> Unit)? = null,
    // 分离模式设置
    separationMode: com.nasmusic.tv.data.prefs.AppPreferences.SeparationMode = com.nasmusic.tv.data.prefs.AppPreferences.SeparationMode.FAST,
    onChangeSeparationMode: ((com.nasmusic.tv.data.prefs.AppPreferences.SeparationMode) -> Unit)? = null,
    // 歌曲离线下载设置
    downloadEnabled: Boolean = true,
    autoDownloadOnPlay: Boolean = false,
    autoDownloadLimit: Int = 50,
    downloadLocation: String = "INTERNAL",
    onToggleDownloadEnabled: ((Boolean) -> Unit)? = null,
    onToggleAutoDownloadOnPlay: ((Boolean) -> Unit)? = null,
    onChangeAutoDownloadLimit: ((Int) -> Unit)? = null,
    onChangeDownloadLocation: ((String) -> Unit)? = null,
    onClearAllDownloads: (() -> Unit)? = null,
    // 下载统计信息
    downloadStats: com.nasmusic.tv.backend.download.DownloadStats = com.nasmusic.tv.backend.download.DownloadStats(),
    // 导出到外接设备
    exportState: com.nasmusic.tv.backend.export.ExportState = com.nasmusic.tv.backend.export.ExportState.Idle,
    onExportToDevice: (() -> Unit)? = null,
    onCancelExport: (() -> Unit)? = null,
    onResetExportState: (() -> Unit)? = null,
    // 高质量分离模型下载状态
    modelDownloaded: Boolean = false,
    modelDownloading: Boolean = false,
    modelDownloadProgress: Float = 0f,
    modelDownloadedMB: Long = 0L,
    modelTotalMB: Long = 0L,
    modelSizeMB: Double = 0.0,
    modelDownloadError: String? = null,
    modelPath: String = "",
    onDownloadModel: (() -> Unit)? = null,
    onDeleteModel: (() -> Unit)? = null,
    onRefreshModelStatus: (() -> Unit)? = null,
    onScanTransferModel: (() -> Unit)? = null,
    // 可视化频谱主题
    visualizerTheme: VisualizerTheme = VisualizerTheme.Default,
    onChangeVisualizerTheme: (VisualizerTheme) -> Unit = {},
    // 数据管理（备份/恢复）
    backupFiles: List<com.nasmusic.tv.util.BackupFileUtils.BackupFile> = emptyList(),
    backupMessage: com.nasmusic.tv.data.model.BackupMessage? = null,
    onRefreshBackupFiles: (() -> Unit)? = null,
    onExportBackup: (() -> Unit)? = null,
    onImportBackup: ((Uri) -> Unit)? = null,
    onDeleteBackup: ((Uri) -> Unit)? = null,
    onConsumeBackupMessage: (() -> Unit)? = null,
    onScanTransferBackup: (() -> Unit)? = null,
    // 歌单导入（2026-09-18：§4.4.2 导入入口 + 最近导入记录 + 消息）
    playlistImportHistory: List<com.nasmusic.tv.data.model.PlaylistImportHistoryItem> = emptyList(),
    playlistImportMessage: com.nasmusic.tv.data.model.BackupMessage? = null,
    onImportPlaylistFile: (() -> Unit)? = null,
    onOpenImportedPlaylist: ((String) -> Unit)? = null,
    onConsumePlaylistImportMessage: (() -> Unit)? = null,
    // 服务器连接设置
    serverConfig: com.nasmusic.tv.data.model.ServerConfig = com.nasmusic.tv.data.model.ServerConfig.Empty,
    isConnected: Boolean = false,
    serverDisplayName: String = "",
    backendApiVersion: String = "Unknown",
    // 全量后端/服务的 API 版本号聚合（供关于页展示）
    apiVersions: List<com.nasmusic.tv.data.model.VersionInfo> = emptyList(),
    isConnecting: Boolean = false,
    onConnect: ((com.nasmusic.tv.data.model.ServerConfig) -> Unit)? = null,
    onDisconnect: (() -> Unit)? = null,
    // 百度网盘设置
    baiduEnabled: Boolean = false,
    baiduLoggedIn: Boolean = false,
    baiduConnecting: Boolean = false,
    baiduConnectionState: com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel.BaiduConnectionState = com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel.BaiduConnectionState.Off,
    baiduDeviceCode: com.nasmusic.tv.backend.network.baidu.BaiduOAuthClient.DeviceCodeResult? = null,
    baiduMusicRootDir: String = com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig.APP_DIR,
    baiduMvDir: String? = null,
    baiduIndexScanned: Int = 0,
    baiduIndexScanning: Boolean = false,
    baiduApicExtracting: Boolean = false,
    baiduApicExtracted: Int = 0,
    baiduApicTotal: Int = 0,
    onToggleBaiduEnabled: ((Boolean) -> Unit)? = null,
    onStartBaiduDeviceCode: (() -> Unit)? = null,
    onCancelBaiduDeviceCode: (() -> Unit)? = null,
    onLogoutBaidu: (() -> Unit)? = null,
    onChangeBaiduMusicRootDir: ((String) -> Unit)? = null,
    onChangeBaiduMvDir: ((String?) -> Unit)? = null,
    onListBaiduDirs: (suspend (String) -> List<BaiduFile>)? = null,
    onRebuildBaiduIndex: (() -> Unit)? = null,
    onNavigateToServerConnect: (() -> Unit)? = null,
    // v2.36.0 竖屏两级页（方案 §4.6 / §8.7）：当前进入的分区（null = 一级列表）
    selectedSection: SettingsSection? = null,
    onOpenSection: (SettingsSection) -> Unit = {},
    onCloseSection: () -> Unit = {},
    // v2.36.0 屏幕方向（L1 全局策略；L2 顶部栏按钮只是快捷改它）
    screenOrientation: String = "auto",
    onChangeScreenOrientation: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var activeSection by remember { mutableStateOf(SettingsSection.GENERAL) }
    // 竖屏只认 PhonePortrait；TV 与手机横屏走现状两栏（B1 硬规则）
    val isPortraitPhone = LocalUiMode.current == UiMode.PhonePortrait
    // 分区路由主体：竖屏用 navVM 的 selectedSection（非空时才会渲染内容），否则用两栏的 activeSection
    val displaySection: SettingsSection = if (isPortraitPhone) {
        selectedSection ?: SettingsSection.GENERAL
    } else {
        activeSection
    }

    // D-Pad 焦点修复：内容区按左键移回左侧导航栏
    // 根因：右侧内容区（LazyColumn）与左侧导航栏（verticalScroll Column）是两个独立滚动容器，
    // 播放设置等内容超长（整段为单个 LazyColumn item，含多组横排按钮）时，
    // 系统几何查找无法从内容区内部"走出"到左栏——表现为按左键焦点卡住或原地不动。
    // 方案：内容区声明为 focusGroup，向左越界（exit）时强制聚焦左侧导航栏当前分区项。
    // 每个分区项持有自己的 FocusRequester；activeSection 变化时重新解析目标项。
    val navFocusRequesters = remember { mutableMapOf<SettingsSection, FocusRequester>() }
    SettingsSection.entries.forEach { section ->
        navFocusRequesters.getOrPut(section) { remember { FocusRequester() } }
    }

    // 网络测试状态
    var isNetworkTesting by remember { mutableStateOf(false) }
    var networkTestStatus by remember { mutableStateOf("") }
    val networkTestScope = rememberCoroutineScope()

    // Meting-API 端点编辑对话框状态
    var showMetingUrlDialog by remember { mutableStateOf(false) }
    var metingUrlError by remember { mutableStateOf<String?>(null) }

    // MTV 视频端点编辑对话框状态
    var showMvUrlDialog by remember { mutableStateOf(false) }
    var mvUrlError by remember { mutableStateOf<String?>(null) }

    // 天气 API Key 编辑对话框状态
    var showWeatherApiKeyDialog by remember { mutableStateOf(false) }

    // 歌词端点编辑对话框状态
    var showLyricsKugouDialog by remember { mutableStateOf(false) }
    var showLyricsNeteaseDialog by remember { mutableStateOf(false) }
    var lyricsUrlError by remember { mutableStateOf<String?>(null) }

    // Jamendo Client ID 编辑对话框
    var showJamendoClientIdDialog by remember { mutableStateOf(false) }

    // P1-17: 清空所有下载确认弹窗
    var showClearDownloadsConfirm by remember { mutableStateOf(false) }

    // 待删除的备份文件（非空时显示确认弹窗）
    var backupToDelete by remember {
        mutableStateOf<com.nasmusic.tv.util.BackupFileUtils.BackupFile?>(null)
    }

    // 百度网盘设备码授权对话框显隐
    var showBaiduAuthDialog by remember { mutableStateOf(false) }

    // 百度网盘目录编辑对话框状态
    var showBaiduMusicRootDialog by remember { mutableStateOf(false) }
    var showBaiduMvDirDialog by remember { mutableStateOf(false) }

    // 百度网盘本地编辑值（参数仅作初始值；编辑后本地立即生效，回调负责持久化）
    var baiduMusicRootLocal by remember { mutableStateOf(baiduMusicRootDir) }
    var baiduMvDirLocal by remember { mutableStateOf(baiduMvDir) }

    // 进入"数据管理"分区时刷新备份文件列表
    LaunchedEffect(activeSection) {
        if (activeSection == SettingsSection.DATA) onRefreshBackupFiles?.invoke()
    }

    // 备份操作结果消息显示后自动消费
    LaunchedEffect(backupMessage) {
        if (backupMessage != null) {
            kotlinx.coroutines.delay(4000)
            onConsumeBackupMessage?.invoke()
        }
    }

    // 歌单导入结果消息显示后自动消费（镜像 backupMessage 4s 消费）
    LaunchedEffect(playlistImportMessage) {
        if (playlistImportMessage != null) {
            kotlinx.coroutines.delay(4000)
            onConsumePlaylistImportMessage?.invoke()
        }
    }

    // 提前解析字符串资源，供非 Composable 回调使用
    val metingUrlInvalidMsg = stringResource(R.string.settings_meting_api_url_invalid)
    val metingUrlHint = stringResource(R.string.settings_meting_api_url_hint)
    val metingUrlTitle = stringResource(R.string.settings_meting_api_url)

    // 网络测试上下文（Composable 作用域内提前获取，供协程内使用）
    val networkTestContext = androidx.compose.ui.platform.LocalContext.current

    // MTV 视频端点对话框字符串资源
    val mvUrlInvalidMsg = stringResource(R.string.settings_mv_api_url_invalid)
    val mvUrlHint = stringResource(R.string.settings_mv_api_url_hint)
    val mvUrlTitle = stringResource(R.string.settings_mv_api_url)

    Row(modifier = modifier.fillMaxSize().padding(if (isPortraitPhone) 16.dp else 32.dp)) {
        // --- 左侧：侧边导航栏（bg2 Surface 背景）---
        // v2.36.0：竖屏两级页不显示侧栏（改为分区列表 → 二级全屏内容）
        if (!isPortraitPhone) {
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(NasMusicColors.Surface)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 头部
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Box(
                    modifier = Modifier.size(32.dp).background(
                        NasMusicColors.Primary,
                        shape = RoundedCornerShape(8.dp)
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = NasMusicColors.TextPrimary, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = stringResource(R.string.nav_settings), color = NasMusicColors.TextPrimary, fontSize = FontSize.title())
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsSection.entries.forEach { section ->
                val selected = section == activeSection
                FocusableSurface(
                    onClick = { activeSection = section },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .focusRequester(navFocusRequesters.getValue(section)),
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.08f,
                    animationDurationMs = 250,
                    containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.18f) else Color.Transparent,
                    contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                    focusedContainerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.3f) else NasMusicColors.SurfaceVariant,
                    focusedContentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                    pressedScale = 0.97f
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = section.icon, contentDescription = null, tint = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = stringResource(section.titleRes), color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary, fontSize = FontSize.button())
                    }
                }
            }
        }
        } // if (!isPortraitPhone) 侧栏结束

        // --- 右侧：具体设置项（R-2：各分区已迁至 settings/ 子包，按域组装 State/Actions）---
        // v2.36.0 竖屏两级页（方案 §4.6）：
        //   一级（selectedSection == null）→ 分区列表
        //   二级（非 null）→ 返回头 + 单列分区内容（复用下方同一份 when 实现）
        if (isPortraitPhone && selectedSection == null) {
            SettingsSectionList(onPick = onOpenSection)
        } else {
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
        if (isPortraitPhone) {
            SettingsSectionBackHeader(section = displaySection, onBack = onCloseSection)
        }
        // focusGroup + 左向 exit 重定向：任何分区内容按左键无法继续左移时，焦点回到导航栏当前分区项
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = if (isPortraitPhone) 0.dp else 24.dp)
                .focusGroup()
                .focusProperties {
                    exit = {
                        if (it == FocusDirection.Left) {
                            if (!isPortraitPhone) navFocusRequesters.getValue(activeSection).requestFocus()
                            FocusRequester.Default
                        } else {
                            FocusRequester.Default
                        }
                    }
                }
        ) {
            when (displaySection) {
                SettingsSection.GENERAL -> item {
                    GeneralSettingsSection(
                        state = GeneralSettingsState(
                            settings = settings,
                            language = language,
                            fontAdjustment = fontAdjustment,
                            screenOrientation = screenOrientation,
                        ),
                        actions = GeneralSettingsActions(
                            onChangeLanguage = onChangeLanguage,
                            onToggleDarkTheme = onToggleDarkTheme,
                            onToggleAnimations = onToggleAnimations,
                            onChangeFontAdjustment = onChangeFontAdjustment,
                            onChangeScreenOrientation = onChangeScreenOrientation,
                        )
                    )
                }
                SettingsSection.PLAYBACK -> item {
                    PlayerSettingsSection(
                        state = PlayerSettingsState(
                            settings = settings,
                            visualizerTheme = visualizerTheme,
                            separationMode = separationMode,
                            modelDownloaded = modelDownloaded,
                            modelDownloading = modelDownloading,
                            modelDownloadProgress = modelDownloadProgress,
                            modelDownloadedMB = modelDownloadedMB,
                            modelTotalMB = modelTotalMB,
                            modelSizeMB = modelSizeMB,
                            modelDownloadError = modelDownloadError,
                            modelPath = modelPath,
                            coverFilterEnabled = coverFilterEnabled,
                            coverFilterBlurRadius = coverFilterBlurRadius,
                            coverFilterDarkOverlay = coverFilterDarkOverlay,
                            // F2-5：crossfade
                            crossfadeEnabled = crossfadeEnabled,
                            crossfadeDurationSec = crossfadeDurationSec,
                            // F2-6：音质档位
                            qualityTier = qualityTier,
                        ),
                        actions = PlayerSettingsActions(
                            onToggleAutoPlayNext = onToggleAutoPlayNext,
                            onChangeVisualizerTheme = onChangeVisualizerTheme,
                            onChangePlayMode = onChangePlayMode,
                            onOpenEqualizer = onOpenEqualizer,
                            onChangeSeparationMode = onChangeSeparationMode,
                            onDownloadModel = onDownloadModel,
                            onDeleteModel = onDeleteModel,
                            onScanTransferModel = onScanTransferModel,
                            onToggleCoverFilter = onToggleCoverFilter,
                            onChangeCoverBlurRadius = onChangeCoverBlurRadius,
                            onChangeCoverDarkOverlay = onChangeCoverDarkOverlay,
                            onToggleCrossfade = onToggleCrossfade,
                            onChangeCrossfadeDuration = onChangeCrossfadeDuration,
                            onChangeQualityTier = onChangeQualityTier,
                            onClearQualityOverrides = onClearQualityOverrides,
                        )
                    )
                }
                SettingsSection.DOWNLOAD -> item {
                    DownloadSettingsSection(
                        state = DownloadSettingsState(
                            downloadStats = downloadStats,
                            downloadEnabled = downloadEnabled,
                            autoDownloadOnPlay = autoDownloadOnPlay,
                            autoDownloadLimit = autoDownloadLimit,
                            downloadLocation = downloadLocation,
                            exportState = exportState,
                        ),
                        actions = DownloadSettingsActions(
                            onToggleDownloadEnabled = onToggleDownloadEnabled,
                            onToggleAutoDownloadOnPlay = onToggleAutoDownloadOnPlay,
                            onChangeAutoDownloadLimit = onChangeAutoDownloadLimit,
                            onChangeDownloadLocation = onChangeDownloadLocation,
                            onClearAllDownloads = onClearAllDownloads,
                            onExportToDevice = onExportToDevice,
                            onCancelExport = onCancelExport,
                            onResetExportState = onResetExportState,
                        ),
                        onClearAllDownloadsRequested = { showClearDownloadsConfirm = true }
                    )
                }
                SettingsSection.SERVER -> item {
                    ServerSettingsSection(
                        state = ServerSettingsState(
                            isConnected = isConnected,
                            serverDisplayName = serverDisplayName,
                        ),
                        actions = ServerSettingsActions(
                            onNavigateToServerConnect = onNavigateToServerConnect,
                            onDisconnect = onDisconnect,
                        )
                    )
                }
                SettingsSection.ABOUT -> item {
                    AboutSettingsSection(
                        state = AboutSettingsState(
                            isConnected = isConnected,
                            serverDisplayName = serverDisplayName,
                            backendApiVersion = backendApiVersion,
                            apiVersions = apiVersions,
                        )
                    )
                }
                SettingsSection.CACHE -> item {
                    CacheSettingsSection(
                        state = CacheSettingsState(settings = settings),
                        actions = CacheSettingsActions(
                            onToggleCacheLyrics = onToggleCacheLyrics,
                            onToggleCacheCover = onToggleCacheCover,
                            onClearLyricsCache = onClearLyricsCache,
                            onClearCoverCache = onClearCoverCache,
                            onClearMvCache = onClearMvCache,
                            onClearAccompanimentCache = onClearAccompanimentCache,
                        )
                    )
                }
                SettingsSection.NETDISK -> item {
                    BaiduPanSettingsSection(
                        state = BaiduPanSettingsState(
                            baiduEnabled = baiduEnabled,
                            baiduLoggedIn = baiduLoggedIn,
                            baiduConnectionState = baiduConnectionState,
                            baiduMusicRootDirLocal = baiduMusicRootLocal,
                            baiduMvDirLocal = baiduMvDirLocal,
                            baiduIndexScanned = baiduIndexScanned,
                            baiduIndexScanning = baiduIndexScanning,
                            baiduApicExtracting = baiduApicExtracting,
                            baiduApicExtracted = baiduApicExtracted,
                            baiduApicTotal = baiduApicTotal,
                        ),
                        actions = BaiduPanSettingsActions(
                            onToggleBaiduEnabled = onToggleBaiduEnabled,
                            onStartBaiduDeviceCode = onStartBaiduDeviceCode,
                            onLogoutBaidu = onLogoutBaidu,
                            onRebuildBaiduIndex = onRebuildBaiduIndex,
                        ),
                        dialogs = BaiduPanDialogActions(
                            onShowBaiduAuthDialog = { showBaiduAuthDialog = true },
                            onShowMusicRootDialog = { showBaiduMusicRootDialog = true },
                            onShowMvDirDialog = { showBaiduMvDirDialog = true },
                        )
                    )
                }
                SettingsSection.NETWORK -> item {
                    NetworkMusicSection(
                        state = NetworkMusicSettingsState(
                            metingApiBaseUrl = settings.metingApiBaseUrl,
                            jamendoClientId = jamendoClientId,
                            mvApiBaseUrl = mvApiBaseUrl,
                            lyricsKugouBaseUrl = lyricsKugouBaseUrl,
                            lyricsNeteaseBaseUrl = lyricsNeteaseBaseUrl,
                            weatherApiKey = weatherApiKey,
                            isNetworkTesting = isNetworkTesting,
                            networkTestStatus = networkTestStatus,
                        ),
                        actions = NetworkMusicSettingsActions(
                            onChangeMetingApiBaseUrl = onChangeMetingApiBaseUrl,
                            onChangeJamendoClientId = onChangeJamendoClientId,
                            onChangeMvApiBaseUrl = onChangeMvApiBaseUrl,
                            onChangeLyricsKugouBaseUrl = onChangeLyricsKugouBaseUrl,
                            onChangeLyricsNeteaseBaseUrl = onChangeLyricsNeteaseBaseUrl,
                            onChangeWeatherApiKey = onChangeWeatherApiKey,
                            onRunNetworkTest = {
                                if (!isNetworkTesting) {
                                    isNetworkTesting = true
                                    networkTestStatus = ""
                                    networkTestScope.launch {
                                        val ctx = networkTestContext
                                        val result = withContext(Dispatchers.IO) {
                                            try {
                                                val url = java.net.URL("https://www.baidu.com")
                                                val conn = url.openConnection() as java.net.HttpURLConnection
                                                conn.connectTimeout = 5000
                                                conn.readTimeout = 5000
                                                conn.requestMethod = "HEAD"
                                                val code = conn.responseCode
                                                conn.disconnect()
                                                if (code in 200..399) "success:${ctx.getString(R.string.settings_network_test_success, code)}"
                                                else "error:${ctx.getString(R.string.settings_network_test_http_error, code)}"
                                            } catch (e: java.net.SocketTimeoutException) {
                                                "error:${ctx.getString(R.string.settings_network_test_timeout)}"
                                            } catch (e: java.net.UnknownHostException) {
                                                "error:${ctx.getString(R.string.settings_network_test_dns_error)}"
                                            } catch (e: java.net.ConnectException) {
                                                "error:${ctx.getString(R.string.settings_network_test_connection_refused)}"
                                            } catch (e: Exception) {
                                                "error:${ctx.getString(R.string.settings_network_test_error, e.message ?: e.javaClass.simpleName)}"
                                            }
                                        }
                                        networkTestStatus = result
                                        isNetworkTesting = false
                                    }
                                }
                            },
                        ),
                        dialogs = NetworkMusicDialogActions(
                            onShowMetingUrlDialog = { metingUrlError = null; showMetingUrlDialog = true },
                            onShowJamendoClientIdDialog = { showJamendoClientIdDialog = true },
                            onShowMvUrlDialog = { mvUrlError = null; showMvUrlDialog = true },
                            onShowLyricsKugouDialog = { showLyricsKugouDialog = true; lyricsUrlError = null },
                            onShowLyricsNeteaseDialog = { showLyricsNeteaseDialog = true; lyricsUrlError = null },
                            onShowWeatherApiKeyDialog = { showWeatherApiKeyDialog = true },
                        )
                    )
                }
                SettingsSection.DATA -> item {
                    DataSettingsSection(
                        state = DataSettingsState(
                            backupFiles = backupFiles,
                            backupMessage = backupMessage,
                            playlistImportHistory = playlistImportHistory,
                            playlistImportMessage = playlistImportMessage,
                        ),
                        actions = DataSettingsActions(
                            onExportBackup = onExportBackup,
                            onImportBackup = onImportBackup,
                            onScanTransferBackup = onScanTransferBackup,
                            onOpenPlayStats = onOpenPlayStats,
                            onImportPlaylistFile = onImportPlaylistFile,
                            onOpenImportedPlaylist = onOpenImportedPlaylist,
                            onConsumePlaylistImportMessage = onConsumePlaylistImportMessage,
                        ),
                        onDeleteRequested = { file -> backupToDelete = file }
                    )
                }
            }
        }
        } // Column（竖屏二级 / 横屏右栏）
        } // else（非竖屏一级列表）
    }

    // ===================== 对话框宿主（R-2：保持在主文件，状态与分区共享） =====================

    // 百度网盘设备码授权对话框
    if (showBaiduAuthDialog && onStartBaiduDeviceCode != null) {
        BaiduAuthDialog(
            deviceCode = baiduDeviceCode,
            connectionState = baiduConnectionState,
            onCancel = {
                onCancelBaiduDeviceCode?.invoke()
                showBaiduAuthDialog = false
            },
            onDismiss = {
                showBaiduAuthDialog = false
            }
        )
    }

    // 百度网盘音乐根目录选择对话框（优先目录树选择，无回调时回退文本输入）
    if (showBaiduMusicRootDialog && onChangeBaiduMusicRootDir != null) {
        if (onListBaiduDirs != null) {
            BaiduDirPickerDialog(
                initialPath = baiduMusicRootLocal.ifBlank { com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig.APP_DIR },
                onListDirs = onListBaiduDirs,
                onConfirm = { path ->
                    baiduMusicRootLocal = path
                    onChangeBaiduMusicRootDir(path)
                    showBaiduMusicRootDialog = false
                },
                onDismiss = { showBaiduMusicRootDialog = false }
            )
        } else {
            TextInputDialog(
                title = stringResource(R.string.settings_netdisk_music_root),
                hint = stringResource(R.string.settings_netdisk_hint_dir),
                initialValue = baiduMusicRootLocal,
                onConfirm = { input ->
                    val trimmed = input.trim()
                    if (trimmed.isNotEmpty()) {
                        baiduMusicRootLocal = trimmed
                        onChangeBaiduMusicRootDir(trimmed)
                    }
                    showBaiduMusicRootDialog = false
                },
                onDismiss = { showBaiduMusicRootDialog = false }
            )
        }
    }

    // 百度网盘 MV 目录选择对话框（优先目录树选择，无回调时回退文本输入）
    if (showBaiduMvDirDialog && onChangeBaiduMvDir != null) {
        if (onListBaiduDirs != null) {
            BaiduDirPickerDialog(
                initialPath = baiduMvDirLocal?.takeIf { it.isNotBlank() }
                    ?: baiduMusicRootLocal.ifBlank { com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig.APP_DIR },
                onListDirs = onListBaiduDirs,
                onConfirm = { path ->
                    baiduMvDirLocal = path
                    onChangeBaiduMvDir(path)
                    showBaiduMvDirDialog = false
                },
                onDismiss = { showBaiduMvDirDialog = false }
            )
        } else {
            TextInputDialog(
                title = stringResource(R.string.settings_netdisk_mv_dir),
                hint = stringResource(R.string.settings_netdisk_hint_dir),
                initialValue = baiduMvDirLocal.orEmpty(),
                onConfirm = { input ->
                    val trimmed = input.trim()
                    baiduMvDirLocal = trimmed.ifEmpty { null }
                    onChangeBaiduMvDir(baiduMvDirLocal)
                    showBaiduMvDirDialog = false
                },
                onDismiss = { showBaiduMvDirDialog = false }
            )
        }
    }

    // 天气 API Key 编辑对话框
    if (showWeatherApiKeyDialog && onChangeWeatherApiKey != null) {
        TextInputDialog(
            title = stringResource(R.string.settings_weather_api_key),
            hint = stringResource(R.string.settings_weather_api_key_hint),
            initialValue = weatherApiKey,
            masked = true,
            onConfirm = { input ->
                onChangeWeatherApiKey(input.trim())
                showWeatherApiKeyDialog = false
            },
            onDismiss = {
                showWeatherApiKeyDialog = false
            }
        )
    }

    // Meting-API 端点编辑对话框
    if (showMetingUrlDialog) {
        TextInputDialog(
            title = metingUrlTitle,
            hint = metingUrlHint,
            initialValue = settings.metingApiBaseUrl,
            onConfirm = { input ->
                val trimmed = input.trim()
                if (trimmed.isEmpty()) {
                    metingUrlError = null
                    onChangeMetingApiBaseUrl?.invoke(
                        com.nasmusic.tv.backend.network.MetingApiService.DEFAULT_BASE_URL
                    )
                    showMetingUrlDialog = false
                } else if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                    metingUrlError = metingUrlInvalidMsg
                } else {
                    metingUrlError = null
                    onChangeMetingApiBaseUrl?.invoke(trimmed)
                    showMetingUrlDialog = false
                }
            },
            onDismiss = {
                showMetingUrlDialog = false
                metingUrlError = null
            }
        )
    }

    // Jamendo Client ID 编辑对话框
    if (showJamendoClientIdDialog) {
        TextInputDialog(
            title = stringResource(R.string.settings_jamendo_client_id),
            hint = stringResource(R.string.settings_jamendo_client_id),
            initialValue = jamendoClientId,
            onConfirm = { input ->
                onChangeJamendoClientId?.invoke(input.trim())
                showJamendoClientIdDialog = false
            },
            onDismiss = {
                showJamendoClientIdDialog = false
            }
        )
    }

    // MTV 视频端点编辑对话框
    if (showMvUrlDialog) {
        TextInputDialog(
            title = mvUrlTitle,
            hint = mvUrlHint,
            initialValue = mvApiBaseUrl,
            onConfirm = { input ->
                val trimmed = input.trim()
                if (trimmed.isEmpty()) {
                    mvUrlError = null
                    onChangeMvApiBaseUrl?.invoke(
                        com.nasmusic.tv.backend.network.mv.BilibiliMvService.DEFAULT_BASE_URL
                    )
                    showMvUrlDialog = false
                } else if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                    mvUrlError = mvUrlInvalidMsg
                } else {
                    mvUrlError = null
                    onChangeMvApiBaseUrl?.invoke(trimmed)
                    showMvUrlDialog = false
                }
            },
            onDismiss = {
                showMvUrlDialog = false
                mvUrlError = null
            }
        )
    }

    // 酷狗歌词端点编辑对话框
    if (showLyricsKugouDialog) {
        val invalidMsg = stringResource(R.string.settings_lyrics_url_invalid)
        val hint = stringResource(R.string.settings_lyrics_url_hint)
        TextInputDialog(
            title = stringResource(R.string.settings_lyrics_kugou_url),
            hint = hint,
            initialValue = lyricsKugouBaseUrl,
            onConfirm = { input ->
                val trimmed = input.trim()
                if (trimmed.isEmpty()) {
                    lyricsUrlError = null
                    onChangeLyricsKugouBaseUrl?.invoke(
                        com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_KUGOU_BASE_URL
                    )
                    showLyricsKugouDialog = false
                } else if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                    lyricsUrlError = invalidMsg
                } else {
                    lyricsUrlError = null
                    onChangeLyricsKugouBaseUrl?.invoke(trimmed)
                    showLyricsKugouDialog = false
                }
            },
            onDismiss = {
                showLyricsKugouDialog = false
                lyricsUrlError = null
            }
        )
    }

    // 网易云歌词端点编辑对话框
    if (showLyricsNeteaseDialog) {
        val invalidMsg = stringResource(R.string.settings_lyrics_url_invalid)
        val hint = stringResource(R.string.settings_lyrics_url_hint)
        TextInputDialog(
            title = stringResource(R.string.settings_lyrics_netease_url),
            hint = hint,
            initialValue = lyricsNeteaseBaseUrl,
            onConfirm = { input ->
                val trimmed = input.trim()
                if (trimmed.isEmpty()) {
                    lyricsUrlError = null
                    onChangeLyricsNeteaseBaseUrl?.invoke(
                        com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_NETEASE_BASE_URL
                    )
                    showLyricsNeteaseDialog = false
                } else if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                    lyricsUrlError = invalidMsg
                } else {
                    lyricsUrlError = null
                    onChangeLyricsNeteaseBaseUrl?.invoke(trimmed)
                    showLyricsNeteaseDialog = false
                }
            },
            onDismiss = {
                showLyricsNeteaseDialog = false
                lyricsUrlError = null
            }
        )
    }

    // 删除备份文件确认弹窗
    backupToDelete?.let { file ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { backupToDelete = null },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            BackHandler { backupToDelete = null }
            Column(
                modifier = Modifier
                    // 520dp 在竖屏（Compose 口径 ≈439dp）会被对话框窗口裁掉 ❌ → §2.4 对话框族
                    // 统一响应式尺寸（TV/横屏仍返回 `width(520.dp)`，逐字等价，B1）
                    .then(responsiveDialogSize(520.dp, scrollable = true))
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.settings_delete_backup_confirm_title),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.subtitle()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_delete_backup_confirm_message, file.displayName),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                ) {
                    FocusableSurface(
                        onClick = { backupToDelete = null },
                        // 44dp 在竖屏只有 36.1 物理 dp ❌ → §2.7 换算抬到 56dp
                        modifier = Modifier.width(140.dp).height(portraitTouchTarget(44.dp)),
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 150,
                        containerColor = NasMusicColors.SurfaceVariant,
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
                        focusedContentColor = NasMusicColors.TextPrimary
                    ) {
                        Text(
                            text = stringResource(R.string.common_cancel),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.button(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)
                        )
                    }
                    FocusableSurface(
                        onClick = {
                            onDeleteBackup?.invoke(file.uri)
                            backupToDelete = null
                        },
                        // 44dp 在竖屏只有 36.1 物理 dp ❌ → §2.7 换算抬到 56dp
                        modifier = Modifier.width(140.dp).height(portraitTouchTarget(44.dp)),
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 150,
                        containerColor = NasMusicColors.Warning,
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContainerColor = NasMusicColors.Warning.copy(alpha = 0.85f),
                        focusedContentColor = NasMusicColors.TextPrimary,
                        requestFocusOnLaunch = true
                    ) {
                        Text(
                            text = stringResource(R.string.settings_delete_backup),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.button(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }

    // P1-17: 清空所有下载确认弹窗
    if (showClearDownloadsConfirm) {
        Dialog(
            onDismissRequest = { showClearDownloadsConfirm = false },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            ConfirmDialog(
                title = "清空所有下载",
                message = "确认删除所有已下载的歌曲文件？此操作不可撤销。",
                destructive = true,
                onConfirm = {
                    onClearAllDownloads?.invoke()
                    showClearDownloadsConfirm = false
                },
                onDismiss = { showClearDownloadsConfirm = false }
            )
        }
    }
}

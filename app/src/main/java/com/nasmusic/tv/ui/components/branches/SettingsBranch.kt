package com.nasmusic.tv.ui.components.branches

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nasmusic.tv.data.model.Screen
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.data.model.*
import com.nasmusic.tv.ui.screens.*
import com.nasmusic.tv.ui.screens.library.*
import com.nasmusic.tv.ui.screens.settings.*
import com.nasmusic.tv.ui.screens.netdisk.*
import com.nasmusic.tv.ui.screens.stats.*
import com.nasmusic.tv.ui.viewmodel.*
import kotlinx.coroutines.launch

/**
 * Settings 分支提取自 AppRoot（Method too large 根治：分支下沉 branches/）。
 * 分支体逐行搬迁，外层共享状态经参数注入。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsBranch(
    viewModel: MainViewModel,
    settings: com.nasmusic.tv.data.model.AppSettings,
    isLoading: Boolean,
    isConnected: Boolean,
    serverDisplayName: String,
    serverConfig: ServerConfig,
    coverFilterEnabled: Boolean,
    coverFilterBlurRadius: Float,
    coverFilterDarkOverlay: Float,
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onConnect: (ServerConfig) -> Unit,
) {
                    val backendApiVersion by viewModel.serverVM.backendApiVersion.collectAsState(initial = "Unknown")
                    val apiVersions by viewModel.serverVM.apiVersions.collectAsState(initial = emptyList())
                    // F2-5：crossfade 设置状态
                    val crossfadeEnabled by viewModel.prefs.player.crossfadeEnabled.collectAsState(initial = false)
                    val crossfadeDurationSec by viewModel.prefs.player.crossfadeDurationSec.collectAsState(initial = 4)
                    // F2-6：音质档位
                    val qualityTier by viewModel.prefs.player.qualityTier.collectAsState(initial = 0)
                    val weatherApiKey by viewModel.prefs.weather.weatherApiKey.collectAsState(initial = "")
                    val baiduConnectionState by viewModel.netVM.baiduConnectionState.collectAsState(initial = com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel.BaiduConnectionState.Off)
                    val baiduDeviceCode by viewModel.baiduDeviceCode.collectAsState(initial = null)
                    val baiduIndexScanned by viewModel.baiduIndexScanned.collectAsState(initial = 0)
                    val baiduIndexScanning by viewModel.baiduIndexScanning.collectAsState(initial = false)
                    val baiduApicExtracting by viewModel.baiduApicExtracting.collectAsState(initial = false)
                    val baiduApicExtracted by viewModel.baiduApicExtracted.collectAsState(initial = 0)
                    val baiduApicTotal by viewModel.baiduApicTotal.collectAsState(initial = 0)
                    var showBackupTransferDialog by remember { mutableStateOf(false) }
                    var showModelTransferDialog by remember { mutableStateOf(false) }
                    var showPlaylistImportDialog by remember { mutableStateOf(false) }
                    // 修复（H-3）：组合内 runBlocking 同步读改为 Flow 订阅
                    val baiduConfig by viewModel.prefs.baidu.baiduConfigFlow.collectAsState(
                        initial = com.nasmusic.tv.data.model.CloudDriveConfig(com.nasmusic.tv.data.model.CloudDriveType.BAIDU)
                    )
                    val jamendoClientId by viewModel.prefs.network.jamendoClientIdFlow.collectAsState(initial = "")
                    val separationMode by viewModel.vocalVM.separationMode.collectAsState()
                    val modelDownloaded by viewModel.downloadVM.modelDownloaded.collectAsState()
                    // === F2-2b 睡眠定时器（常驻按钮状态，本页内收集） ===
                    val sleepTimerSt by viewModel.playerVM.sleepTimerState.collectAsState()
                    val modelDownloading by viewModel.downloadVM.modelDownloading.collectAsState()
                    val modelDownloadProgress by viewModel.downloadVM.modelDownloadProgress.collectAsState()
                    val modelDownloadedMB by viewModel.downloadVM.modelDownloadedMB.collectAsState()
                    val modelTotalMB by viewModel.downloadVM.modelTotalMB.collectAsState()
                    val modelSizeMB by viewModel.downloadVM.modelSizeMB.collectAsState()
                    val modelDownloadError by viewModel.downloadVM.modelDownloadError.collectAsState()
                    val modelPath by viewModel.downloadVM.modelPath.collectAsState()
                    // 进入设置页时刷新模型状态（检查文件是否已下载）和下载统计
                    LaunchedEffect(Unit) {
                        viewModel.downloadVM.refreshModelStatus()
                        viewModel.downloadVM.refreshDownloadStats()
                    }
                    // 歌单导入：最近导入记录 + 导入结果消息
                    val playlistImportHistory by viewModel.playlistImportVM.importHistory.collectAsState(initial = emptyList())
                    val playlistImportMessage by viewModel.playlistImportVM.importMessage.collectAsState(initial = null)
                    // v2.36.0 竖屏设置两级页：当前进入的分区（null = 一级列表）。
                    // 状态归 NavigationViewModel —— AppRoot 的 BACK handler 需要读它（方案 §6.2 / K2）
                    val settingsSection by viewModel.navVM.settingsSection.collectAsState(initial = null)
                    // v2.36.0 屏幕方向（L1 全局策略）
                    val screenOrientation by viewModel.prefs.display.screenOrientation.collectAsState(initial = "auto")
                    // 阶段 9（§9）：照片墙的运行时状态 —— 权限三态 + 目录拒绝原因。
                    // ⚠️ 都来自 visualizerVM 的 StateFlow，**不从 AppSettings 读**：
                    //    权限状态官方禁止落盘（§9.4），目录拒绝是瞬时事实。
                    val photoWallPermissionState by viewModel.visualizerVM.photoPermissionState.collectAsState()
                    val photoWallDirectoryReject by viewModel.visualizerVM.photoDirectoryReject.collectAsState()
                    SettingsScreen(
                        selectedSection = settingsSection,
                        onOpenSection = { viewModel.navVM.openSettingsSection(it) },
                        onCloseSection = { viewModel.navVM.closeSettingsSection() },
                        screenOrientation = screenOrientation,
                        onChangeScreenOrientation = { value ->
                            coroutineScope.launch { viewModel.prefs.display.setScreenOrientation(value) }
                        },
                        settings = settings,
                        onToggleDarkTheme = { viewModel.updateDarkTheme(it) },
                        onToggleAnimations = { viewModel.updateAnimationsEnabled(it) },
                        onToggleAutoPlayNext = { viewModel.updateAutoPlayNext(it) },
                        onChangePlayMode = { viewModel.updateDefaultPlayMode(it) },
                        onToggleCacheLyrics = { viewModel.updateCacheLyrics(it) },
                        onToggleCacheCover = { viewModel.updateCacheCover(it) },
                        onChangeLyricsOffset = { viewModel.updateLyricsOffset(it) },
                        onClearLyricsCache = { viewModel.clearLyricsCache() },
                        onClearCoverCache = { viewModel.clearCoverCache() },
                        onClearMvCache = { viewModel.clearMvPersistentCache() },
                        onClearAccompanimentCache = { viewModel.clearAccompanimentCache() },
                        onOpenEqualizer = { viewModel.navVM.navigateTo(Screen.Equalizer) },
                        onOpenPlayStats = { viewModel.navVM.navigateTo(Screen.PlayStats) },
                        // F2-5：crossfade 设置接线
                        crossfadeEnabled = crossfadeEnabled,
                        crossfadeDurationSec = crossfadeDurationSec,
                        onToggleCrossfade = { viewModel.setCrossfadeEnabled(it) },
                        onChangeCrossfadeDuration = { viewModel.setCrossfadeDurationSec(it) },
                        // F2-6：音质档位接线
                        qualityTier = qualityTier,
                        onChangeQualityTier = { viewModel.setQualityTier(it) },
                        onClearQualityOverrides = { viewModel.clearAllQualityOverrides() },
                        onChangeMetingApiBaseUrl = { viewModel.updateMetingApiBaseUrl(it) },
                        mvApiBaseUrl = settings.mvApiBaseUrl,
                        onChangeMvApiBaseUrl = { viewModel.updateMvApiBaseUrl(it) },
                        lyricsKugouBaseUrl = settings.lyricsKugouBaseUrl,
                        onChangeLyricsKugouBaseUrl = { viewModel.updateLyricsKugouBaseUrl(it) },
                        lyricsNeteaseBaseUrl = settings.lyricsNeteaseBaseUrl,
                        onChangeLyricsNeteaseBaseUrl = { viewModel.updateLyricsNeteaseBaseUrl(it) },
                        // Jamendo（CC 独立音乐）
                        jamendoClientId = jamendoClientId,
                        onChangeJamendoClientId = { viewModel.updateJamendoClientId(it) },
                        weatherApiKey = weatherApiKey,
                        onChangeWeatherApiKey = { viewModel.updateWeatherApiKey(it) },
                        visualizerTheme = settings.visualizerTheme,
                        onChangeVisualizerTheme = { viewModel.updateVisualizerTheme(it) },
                        fontAdjustment = settings.fontAdjustment,
                        onChangeFontAdjustment = { viewModel.updateFontAdjustment(it) },
                        // 语言设置
                        language = settings.language,
                        onChangeLanguage = { lang ->
                            coroutineScope.launch {
                                viewModel.updateLanguage(lang)
                                // 用 finish + 新 Intent 重启，避免 recreate() 导致双 DataStore 冲突。
                                // 不调用 Runtime.exit(0) —— 进程自然回收，避免中断 PlaybackService 播放。
                                val activity = context as? android.app.Activity
                                if (activity != null) {
                                    val pkg = activity.packageName
                                    val mgr = activity.packageManager
                                    val intent = mgr.getLaunchIntentForPackage(pkg)
                                    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    if (intent != null) activity.startActivity(intent)
                                    activity.finish()
                                }
                            }
                        },
                        // 数据管理（备份/恢复）
                        backupFiles = viewModel.backupVM.backupFiles.collectAsState(initial = emptyList()).value,
                        backupMessage = viewModel.backupVM.backupMessage.collectAsState(initial = null).value,
                        onRefreshBackupFiles = { viewModel.backupVM.refreshBackupFiles() },
                        onExportBackup = { viewModel.backupVM.exportBackup() },
                        onImportBackup = { uri -> viewModel.backupVM.importBackup(uri) },
                        onDeleteBackup = { uri -> viewModel.backupVM.deleteBackup(uri) },
                        onConsumeBackupMessage = { viewModel.backupVM.consumeBackupMessage() },
                        onScanTransferBackup = { showBackupTransferDialog = true },
                        // 歌单导入（阶段5）
                        playlistImportHistory = playlistImportHistory,
                        playlistImportMessage = playlistImportMessage,
                        onImportPlaylistFile = { showPlaylistImportDialog = true },
                        onOpenImportedPlaylist = { _ -> viewModel.navVM.navigateTo(Screen.Mine) },
                        onConsumePlaylistImportMessage = { viewModel.playlistImportVM.consumeImportMessage() },
                        // 百度网盘设置
                        baiduEnabled = baiduConfig.enabled,
                        baiduLoggedIn = baiduConnectionState is com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel.BaiduConnectionState.LoggedIn
                            || baiduConnectionState is com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel.BaiduConnectionState.DirMissing,
                        baiduConnecting = baiduConnectionState is com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel.BaiduConnectionState.Connecting,
                        baiduConnectionState = baiduConnectionState,
                        baiduDeviceCode = baiduDeviceCode,
                        baiduMusicRootDir = baiduConfig.musicRootDir,
                        baiduMvDir = baiduConfig.mvDir,
                        baiduIndexScanned = baiduIndexScanned,
                        baiduIndexScanning = baiduIndexScanning,
                        baiduApicExtracting = baiduApicExtracting,
                        baiduApicExtracted = baiduApicExtracted,
                        baiduApicTotal = baiduApicTotal,
                        onToggleBaiduEnabled = { viewModel.netVM.setBaiduEnabled(it) },
                        onStartBaiduDeviceCode = { viewModel.netVM.startBaiduDeviceCodeFlow() },
                        onCancelBaiduDeviceCode = { viewModel.netVM.cancelBaiduDeviceCode() },
                        onLogoutBaidu = { viewModel.netVM.logoutBaidu() },
                        onChangeBaiduMusicRootDir = { viewModel.netVM.setBaiduMusicRootDir(it) },
                        onChangeBaiduMvDir = { viewModel.netVM.setBaiduMvDir(it) },
                        onListBaiduDirs = { viewModel.listBaiduDirs(it) },
                        onRebuildBaiduIndex = { viewModel.netVM.rebuildBaiduIndex() },
                        onNavigateToServerConnect = { viewModel.navVM.navigateTo(Screen.ServerConnect) },
                        // 服务器连接设置
                        serverConfig = serverConfig,
                        isConnected = isConnected,
                        serverDisplayName = serverDisplayName,
                        backendApiVersion = backendApiVersion,
                        apiVersions = apiVersions,
                        isConnecting = isLoading,
                        onConnect = onConnect,
                        onDisconnect = { viewModel.serverVM.disconnect() },
                        // 封面滤镜设置
                    coverFilterEnabled = coverFilterEnabled,
                    coverFilterBlurRadius = coverFilterBlurRadius,
                    coverFilterDarkOverlay = coverFilterDarkOverlay,
                    onToggleCoverFilter = { viewModel.updateCoverFilterEnabled(it) },
                    onChangeCoverBlurRadius = { viewModel.updateCoverFilterBlurRadius(it) },
                    onChangeCoverDarkOverlay = { viewModel.updateCoverFilterDarkOverlay(it) },
                    // 分离模式设置
                    separationMode = separationMode,
                    onChangeSeparationMode = { viewModel.vocalVM.setSeparationMode(it) },
                    // 高质量分离模型下载状态
                    modelDownloaded = modelDownloaded,
                    modelDownloading = modelDownloading,
                    modelDownloadProgress = modelDownloadProgress,
                    modelDownloadedMB = modelDownloadedMB,
                    modelTotalMB = modelTotalMB,
                    modelSizeMB = modelSizeMB,
                    modelDownloadError = modelDownloadError,
                    modelPath = modelPath,
                    onDownloadModel = { viewModel.downloadVM.downloadModel() },
                    onDeleteModel = { viewModel.deleteModel() },
                    onRefreshModelStatus = { viewModel.downloadVM.refreshModelStatus() },
                    onScanTransferModel = { showModelTransferDialog = true },
                    // 歌曲离线下载设置
                    downloadEnabled = settings.downloadEnabled,
                    autoDownloadOnPlay = settings.autoDownloadOnPlay,
                    autoDownloadLimit = settings.autoDownloadLimit,
                    downloadLocation = settings.downloadLocation,
                    onToggleDownloadEnabled = { viewModel.updateDownloadEnabled(it) },
                    onToggleAutoDownloadOnPlay = { viewModel.updateAutoDownloadOnPlay(it) },
                    onChangeAutoDownloadLimit = { viewModel.updateAutoDownloadLimit(it) },
                    onChangeDownloadLocation = { viewModel.updateDownloadLocation(it) },
                    onClearAllDownloads = { viewModel.downloadVM.clearAllDownloads() },
                    downloadStats = viewModel.downloadVM.downloadStats.collectAsState().value,
                    // 导出到外接设备
                    exportState = viewModel.exportState.collectAsState().value,
                    onExportToDevice = { viewModel.showExportDeviceDialog() },
                    onCancelExport = { viewModel.cancelExport() },
                    onResetExportState = { viewModel.resetExportState() },
                    // 照片墙（§7.2）：写值一律走 prefs.photoWall.* 的 setter，
                    // 与「屏幕方向」同款写法（直接 coroutineScope.launch），
                    // 不必在 MainViewModel 上再加 17 个纯转发方法。
                    // ⚠️ 读值不在这里 —— PhotoWallSettingsSection 直接读 settings（AppSettings）。
                    //
                    // 阶段 9：授权相关动作走 `visualizerVM`（它不是纯 setter ——
                    // 「打开图库」要判权限、拉起系统对话框、被拒后回弹），
                    // 状态与逻辑都收口在那个 ViewModel 里，这里只做接线。
                    //
                    // ⚠️ 仍有两个动作**本阶段刻意留空**（不传 ⇒ 按钮点击无反应）：
                    //   onRescan         → 阶段 10（需要聚合器）
                    //   onStartFaceScan / onClearFaceScan → 阶段 11（人脸检测）
                    //   见 docs/photo-spectrum-effect-plan.md §15.3 阶段 8 的偏差记录。
                    photoWallRuntime = PhotoWallRuntimeState(
                        permissionState = photoWallPermissionState,
                        directoryReject = photoWallDirectoryReject,
                    ),
                    photoWallActions = PhotoWallSettingsActions(
                        // ⛔ 图库开关是授权的**唯一触发点**（§6.2）：
                        //    ViewModel 内部会判断「已授权直接开 / 未授权先申请」。
                        onToggleGallery = { v -> viewModel.visualizerVM.setGallerySourceEnabled(v) },
                        onReselectPhotos = { viewModel.visualizerVM.requestGalleryPermission() },
                        onPickDirectory = { viewModel.visualizerVM.requestPhotoDirectoryPick() },
                        onToggleExternal = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setExternalEnabled(v) }
                        },
                        onToggleJellyfin = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setJellyfinEnabled(v) }
                        },
                        onToggleSourceBalance = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setSourceBalance(v) }
                        },
                        onToggleCommonDirsOnly = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setCommonDirsOnly(v) }
                        },
                        onToggleFacesOnly = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setFacesOnly(v) }
                        },
                        onToggleRandomTransition = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setRandomTransition(v) }
                        },
                        onChangeFixedTransition = { id ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setFixedTransition(id) }
                        },
                        onChangeTransitionMs = { ms ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setTransitionMs(ms) }
                        },
                        onChangeHoldMs = { ms ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setHoldMs(ms) }
                        },
                        onChangeScaleMode = { mode ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setScaleMode(mode) }
                        },
                        onToggleKenBurns = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setKenBurns(v) }
                        },
                        onToggleAudioReactive = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setAudioReactive(v) }
                        },
                        onTogglePulseZoom = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setPulseZoom(v) }
                        },
                        onToggleBreathe = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setBreathe(v) }
                        }
                    )
                    )
                    // 扫码传输备份弹窗
                    if (showBackupTransferDialog) {
                        BackupTransferDialog(
                            onRestore = { json -> viewModel.backupVM.restoreBackupFromJsonBlocking(json) },
                            onBackupChanged = { viewModel.backupVM.refreshBackupFiles() },
                            onDismiss = { showBackupTransferDialog = false }
                        )
                    }
                    // 扫码传输模型弹窗
                    if (showModelTransferDialog) {
                        ModelTransferDialog(
                            modelPath = modelPath,
                            modelSizeMB = modelSizeMB,
                            onModelUploaded = { viewModel.downloadVM.refreshModelStatus() },
                            onDismiss = { showModelTransferDialog = false }
                        )
                    }
                    // 歌单扫码上传导入弹窗（阶段5.5，替代 SAF 文件选择器——Android TV 无 DocumentsUI）
                    if (showPlaylistImportDialog) {
                        PlaylistImportUploadDialog(
                            onFileReceived = { name, bytes ->
                                viewModel.playlistImportVM.importRemoteFileBlocking(name, bytes)
                            },
                            onDismiss = { showPlaylistImportDialog = false }
                        )
                    }
}

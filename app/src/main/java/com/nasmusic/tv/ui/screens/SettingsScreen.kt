package com.nasmusic.tv.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nasmusic.tv.NasMusicVersion
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.ui.components.BaiduDirPickerDialog
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.ui.screens.netdisk.BaiduAuthDialog
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置屏幕 — 左侧为导航侧边栏（settings-sidebar），右侧为具体选项（settings-content）
 */
private enum class SettingsSection(val titleRes: Int) {
    GENERAL(R.string.settings_general),
    PLAYBACK(R.string.settings_playback),
    SERVER(R.string.nav_server),
    CACHE(R.string.settings_cache),
    NETWORK(R.string.settings_network),
    NETDISK(R.string.settings_netdisk),
    DATA(R.string.settings_data),
    ABOUT(R.string.settings_about)
}

@OptIn(ExperimentalTvMaterial3Api::class)
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
    onOpenEqualizer: (() -> Unit)? = null,
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
    spectrumEnabled: Boolean = false,
    onToggleSpectrum: (Boolean) -> Unit = {},
    // 可视化频谱主题
    visualizerTheme: VisualizerTheme = VisualizerTheme.COLOR_FLOW,
    onChangeVisualizerTheme: (VisualizerTheme) -> Unit = {},
    // 数据管理（备份/恢复）
    backupFiles: List<com.nasmusic.tv.util.BackupFileUtils.BackupFile> = emptyList(),
    backupMessage: String? = null,
    onRefreshBackupFiles: (() -> Unit)? = null,
    onExportBackup: (() -> Unit)? = null,
    onImportBackup: ((Uri) -> Unit)? = null,
    onDeleteBackup: ((Uri) -> Unit)? = null,
    onConsumeBackupMessage: (() -> Unit)? = null,
    onScanTransferBackup: (() -> Unit)? = null,
    // 服务器连接设置
    serverConfig: com.nasmusic.tv.data.model.ServerConfig = com.nasmusic.tv.data.model.ServerConfig.Empty,
    isConnected: Boolean = false,
    serverDisplayName: String = "",
    backendApiVersion: String = "Unknown",
    isConnecting: Boolean = false,
    onConnect: ((com.nasmusic.tv.data.model.ServerConfig) -> Unit)? = null,
    onDisconnect: (() -> Unit)? = null,
    // 百度网盘设置
    baiduEnabled: Boolean = false,
    baiduLoggedIn: Boolean = false,
    baiduConnecting: Boolean = false,
    baiduConnectionState: com.nasmusic.tv.ui.viewmodel.MainViewModel.BaiduConnectionState = com.nasmusic.tv.ui.viewmodel.MainViewModel.BaiduConnectionState.Off,
    baiduDeviceCode: com.nasmusic.tv.backend.network.baidu.BaiduOAuthClient.DeviceCodeResult? = null,
    baiduMusicRootDir: String = "/音乐",
    baiduMvDir: String? = null,
    baiduIndexScanned: Int = 0,
    baiduIndexScanning: Boolean = false,
    onToggleBaiduEnabled: ((Boolean) -> Unit)? = null,
    onStartBaiduDeviceCode: (() -> Unit)? = null,
    onCancelBaiduDeviceCode: (() -> Unit)? = null,
    onLogoutBaidu: (() -> Unit)? = null,
    onChangeBaiduMusicRootDir: ((String) -> Unit)? = null,
    onChangeBaiduMvDir: ((String?) -> Unit)? = null,
    onListBaiduDirs: (suspend (String) -> List<BaiduFile>)? = null,
    onRebuildBaiduIndex: (() -> Unit)? = null,
    onNavigateToServerConnect: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var activeSection by remember { mutableStateOf(SettingsSection.GENERAL) }

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

    // 提前解析字符串资源，供非 Composable 回调使用
    val metingUrlInvalidMsg = stringResource(R.string.settings_meting_api_url_invalid)
    val metingUrlHint = stringResource(R.string.settings_meting_api_url_hint)
    val metingUrlTitle = stringResource(R.string.settings_meting_api_url)

    // MTV 视频端点对话框字符串资源
    val mvUrlInvalidMsg = stringResource(R.string.settings_mv_api_url_invalid)
    val mvUrlHint = stringResource(R.string.settings_mv_api_url_hint)
    val mvUrlTitle = stringResource(R.string.settings_mv_api_url)

    Row(modifier = modifier.fillMaxSize().padding(32.dp)) {
        // --- 左侧：侧边导航栏（bg2 Surface 背景）---
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
                Text(text = stringResource(R.string.nav_settings), color = NasMusicColors.TextPrimary, fontSize = 27.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsSection.values().forEach { section ->
                val selected = section == activeSection
                FocusableSurface(
                    onClick = { activeSection = section },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
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
                        val icon = when (section) {
                            SettingsSection.GENERAL -> Icons.Default.Settings
                            SettingsSection.PLAYBACK -> Icons.Default.Audiotrack
                            SettingsSection.SERVER -> Icons.Default.Storage
                            SettingsSection.CACHE -> Icons.Default.Settings
                            SettingsSection.NETWORK -> Icons.Default.Settings
                            SettingsSection.NETDISK -> Icons.Default.Settings
                            SettingsSection.DATA -> Icons.Default.Info
                            SettingsSection.ABOUT -> Icons.Default.Info
                        }
                        Icon(imageVector = icon, contentDescription = null, tint = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = stringResource(section.titleRes), color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary, fontSize = 21.sp)
                    }
                }
            }
        }

        // --- 右侧：具体设置项 ---
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 24.dp)) {
            when (activeSection) {
                SettingsSection.GENERAL -> {
                    item { SectionTitle(stringResource(R.string.settings_general)) }
                    item { SettingSwitch(label = stringResource(R.string.settings_dark_theme), description = stringResource(R.string.settings_dark_theme_desc), checked = settings.darkTheme, onClick = { onToggleDarkTheme(!settings.darkTheme) }) }
                    item { SettingSwitch(label = stringResource(R.string.settings_animations), description = stringResource(R.string.settings_animations_desc), checked = settings.animationsEnabled, onClick = { onToggleAnimations(!settings.animationsEnabled) }) }
                }
                SettingsSection.PLAYBACK -> {
                    item { SectionTitle(stringResource(R.string.settings_playback)) }
                    item { SettingSwitch(label = stringResource(R.string.settings_auto_play), description = stringResource(R.string.settings_auto_play_desc), checked = settings.autoPlayNext, onClick = { onToggleAutoPlayNext(!settings.autoPlayNext) }) }
                    item { SettingSwitch(label = stringResource(R.string.settings_spectrum), description = stringResource(R.string.settings_spectrum_desc), checked = spectrumEnabled, onClick = { onToggleSpectrum(!spectrumEnabled) }) }
                    if (spectrumEnabled) {
                        item { VisualizerThemeSelector(current = visualizerTheme, onSelect = { onChangeVisualizerTheme(it) }) }
                    }
                    item { PlayModeSelector(current = settings.defaultPlayMode, onSelect = { onChangePlayMode(it) }) }
                    item { Spacer(modifier = Modifier.height(12.dp)) }
                    item {
                        SettingActionButton(
                            label = stringResource(R.string.settings_equalizer),
                            description = stringResource(R.string.settings_equalizer_desc),
                            onClick = { onOpenEqualizer?.invoke() }
                        )
                    }
                    // ── 封面滤镜分组 ──
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                    item { SubSectionTitle(stringResource(R.string.settings_cover)) }
                    item {
                        SettingSwitch(
                            label = stringResource(R.string.settings_cover_filter),
                            description = stringResource(R.string.settings_cover_filter_desc),
                            checked = coverFilterEnabled,
                            onClick = { onToggleCoverFilter(!coverFilterEnabled) }
                        )
                    }
                    if (coverFilterEnabled) {
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                        item {
                            Text(
                                text = stringResource(R.string.settings_cover_blur_radius, coverFilterBlurRadius.toInt()),
                                color = NasMusicColors.TextPrimary,
                                fontSize = 21.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                        item {
                            // Blur radius buttons
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AdjustButton("-", onClick = {
                                    val new = (coverFilterBlurRadius - 2f).coerceAtLeast(0f)
                                    onChangeCoverBlurRadius(new)
                                })
                                Text(
                                    text = "%.0fpx".format(coverFilterBlurRadius),
                                    color = NasMusicColors.Primary,
                                    fontSize = 27.sp,
                                    modifier = Modifier.width(64.dp).padding(horizontal = 8.dp)
                                )
                                AdjustButton("+", onClick = {
                                    val new = (coverFilterBlurRadius + 2f).coerceAtMost(40f)
                                    onChangeCoverBlurRadius(new)
                                })
                            }
                        }
                        item { Spacer(modifier = Modifier.height(20.dp)) }
                        item {
                            Text(
                                text = stringResource(R.string.settings_cover_dark_overlay, (coverFilterDarkOverlay * 100).toInt()),
                                color = NasMusicColors.TextPrimary,
                                fontSize = 21.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AdjustButton("-", onClick = {
                                    val new = (coverFilterDarkOverlay - 0.1f).coerceAtLeast(0f)
                                    onChangeCoverDarkOverlay(new)
                                })
                                Text(
                                    text = "${(coverFilterDarkOverlay * 100).toInt()}%",
                                    color = NasMusicColors.Primary,
                                    fontSize = 27.sp,
                                    modifier = Modifier.width(64.dp).padding(horizontal = 8.dp)
                                )
                                AdjustButton("+", onClick = {
                                    val new = (coverFilterDarkOverlay + 0.1f).coerceAtMost(1f)
                                    onChangeCoverDarkOverlay(new)
                                })
                            }
                        }
                    }
                }
                SettingsSection.SERVER -> {
                    item { SectionTitle(stringResource(R.string.nav_server)) }
                    item { Spacer(modifier = Modifier.height(12.dp)) }
                    item {
                        val statusText = if (isConnected)
                            stringResource(R.string.server_connected, serverDisplayName)
                        else
                            stringResource(R.string.server_connect_desc)
                        Text(statusText, color = NasMusicColors.TextPrimary, fontSize = 19.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                    }
                    item { Spacer(modifier = Modifier.height(12.dp)) }
                    item {
                        SettingActionButton(
                            label = stringResource(R.string.server_config_title),
                            description = if (isConnected) stringResource(R.string.server_connected, serverDisplayName)
                                else stringResource(R.string.server_connect_desc),
                            onClick = { onNavigateToServerConnect?.invoke() }
                        )
                    }
                    if (isConnected && onDisconnect != null) {
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                        item {
                            SettingActionButton(
                                label = stringResource(R.string.server_disconnect),
                                description = stringResource(R.string.settings_disconnect_desc),
                                onClick = onDisconnect
                            )
                        }
                    }
                }
                SettingsSection.ABOUT -> {
                    item { SectionTitle(stringResource(R.string.settings_about)) }
                    item { AboutRow(label = stringResource(R.string.settings_app_name), value = stringResource(R.string.app_name)) }
                    item { AboutRow(label = stringResource(R.string.about_version), value = NasMusicVersion.DISPLAY) }
                    item { AboutRow(label = stringResource(R.string.settings_build_type), value = NasMusicVersion.BUILD_TYPE) }
                    item { AboutRow(label = stringResource(R.string.about_license), value = stringResource(R.string.about_license_value)) }
                    item { AboutRow(label = stringResource(R.string.settings_supported_backends), value = "Jellyfin / Navidrome / Subsonic / 道理鱼 / 飞牛") }
                    // 当前连接的后端信息
                    if (isConnected) {
                        item { AboutRow(label = "后端类型", value = serverDisplayName) }
                        item { AboutRow(label = "API 版本", value = backendApiVersion) }
                    } else {
                        item { AboutRow(label = "后端状态", value = "未连接") }
                    }
                    item { AboutRow(label = "网络音乐", value = "网易云 / QQ音乐 / 酷狗 / 酷我 / 咪咕（Meting-API多端点") }
                    item { AboutRow(label = "独立音乐", value = "Jamendo（CC授权，需配置Client ID）") }
                    item { AboutRow(label = "电台", value = "radio-browser（全球公开目录，含中文电台）") }
                    item { AboutRow(label = "网盘", value = "百度网盘") }
                    item { AboutRow(label = "歌词", value = "酷狗 / 网易云 / 网络匹配") }
                }
                SettingsSection.CACHE -> {
                    item { SectionTitle(stringResource(R.string.settings_cache)) }
                    // 缓存目录大小（置顶，醒目可见）
                    item {
                        val context = LocalContext.current
                        val cacheDirSize = try {
                            val cacheDir = context.cacheDir
                            val sizeBytes = cacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
                            if (sizeBytes > 1048576L) "${sizeBytes / 1048576} MB"
                            else if (sizeBytes > 1024L) "${sizeBytes / 1024} KB"
                            else "$sizeBytes B"
                        } catch (_: Exception) { "—" }
                        Text(
                            text = "当前缓存目录大小: $cacheDirSize",
                            color = NasMusicColors.TextSecondary,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                        )
                    }
                    // ── 缓存开关（原歌词 tab 的歌词/封面缓存开关） ──
                    item { SubSectionTitle(stringResource(R.string.settings_cache_switch)) }
                    item { SettingSwitch(label = stringResource(R.string.settings_cache_lyrics), description = stringResource(R.string.settings_cache_lyrics_desc), checked = settings.cacheLyrics, onClick = { onToggleCacheLyrics(!settings.cacheLyrics) }) }
                    item { SettingSwitch(label = stringResource(R.string.settings_cache_cover), description = stringResource(R.string.settings_cache_cover_desc), checked = settings.cacheCover, onClick = { onToggleCacheCover(!settings.cacheCover) }) }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                    // ── 缓存清理 ──
                    item { SubSectionTitle(stringResource(R.string.settings_cache_clear)) }
                    if (onClearLyricsCache != null) {
                        item {
                            SettingActionButton(
                                label = stringResource(R.string.settings_clear_lyrics_cache),
                                description = stringResource(R.string.settings_clear_lyrics_cache_desc),
                                onClick = onClearLyricsCache
                            )
                        }
                    }
                    if (onClearCoverCache != null) {
                        item {
                            SettingActionButton(
                                label = stringResource(R.string.settings_clear_cover_cache),
                                description = "清理 Coil 图片加载器的磁盘缓存",
                                onClick = onClearCoverCache
                            )
                        }
                    }
                    if (onClearMvCache != null) {
                        item {
                            SettingActionButton(
                                label = stringResource(R.string.settings_clear_mv_cache),
                                description = stringResource(R.string.settings_clear_mv_cache_desc),
                                onClick = onClearMvCache
                            )
                        }
                    }
                    item {
                        val context = LocalContext.current
                        val cacheDirSize = try {
                            val cacheDir = context.cacheDir
                            val sizeBytes = cacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
                            if (sizeBytes > 1048576L) "${sizeBytes / 1048576} MB"
                            else if (sizeBytes > 1024L) "${sizeBytes / 1024} KB"
                            else "$sizeBytes B"
                        } catch (_: Exception) { "—" }
                        Text(
                            text = "当前缓存目录大小: $cacheDirSize",
                            color = NasMusicColors.TextSecondary,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                        )
                    }
                }
                SettingsSection.NETDISK -> {
                    item { SectionTitle(stringResource(R.string.settings_netdisk)) }

                    // ── 百度网盘（已支持）分组 ──
                    item { SubSectionTitle(stringResource(R.string.settings_netdisk_group_baidu)) }
                    item { SettingSwitch(label = stringResource(R.string.settings_netdisk_enable), description = stringResource(R.string.settings_netdisk_enable_desc), checked = baiduEnabled, onClick = { onToggleBaiduEnabled?.invoke(!baiduEnabled) }) }

                    if (onStartBaiduDeviceCode != null) {
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                        if (baiduLoggedIn) {
                            item {
                                SettingActionButton(
                                    label = stringResource(R.string.settings_netdisk_logged_in),
                                    description = stringResource(R.string.settings_netdisk_logout_desc),
                                    onClick = { onLogoutBaidu?.invoke() }
                                )
                            }
                        } else {
                            item {
                                SettingActionButton(
                                    label = stringResource(R.string.settings_netdisk_login),
                                    description = stringResource(R.string.settings_netdisk_login_desc),
                                    onClick = {
                                        showBaiduAuthDialog = true
                                        onStartBaiduDeviceCode?.invoke()
                                    }
                                )
                            }
                        }
                    }

                    if (onChangeBaiduMusicRootDir != null) {
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                        item {
                            SettingActionButton(
                                label = stringResource(R.string.settings_netdisk_music_root),
                                description = baiduMusicRootLocal,
                                onClick = { showBaiduMusicRootDialog = true }
                            )
                        }
                    }
                    if (onChangeBaiduMvDir != null) {
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                        item {
                            SettingActionButton(
                                label = stringResource(R.string.settings_netdisk_mv_dir),
                                description = baiduMvDirLocal?.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_netdisk_mv_dir_desc),
                                onClick = { showBaiduMvDirDialog = true }
                            )
                        }
                    }
                    if (onRebuildBaiduIndex != null) {
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                        item {
                            Text(
                                text = if (baiduIndexScanning) stringResource(R.string.settings_netdisk_index_scanning)
                                else stringResource(R.string.settings_netdisk_index_desc, baiduIndexScanned),
                                color = NasMusicColors.TextSecondary,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                            )
                        }
                        item {
                            SettingActionButton(
                                label = stringResource(R.string.settings_netdisk_index_rebuild),
                                description = stringResource(R.string.settings_netdisk_index_rebuild_desc),
                                onClick = { onRebuildBaiduIndex?.invoke() }
                            )
                        }
                    }

                    // ── 其他网盘（占位）分组 ──
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                    item { SubSectionTitle(stringResource(R.string.settings_netdisk_group_others)) }
                    com.nasmusic.tv.data.model.CloudDriveType.PLACEHOLDER.forEach { type ->
                        item { PlaceholderRow(name = type.displayName) }
                    }
                }
                SettingsSection.NETWORK -> {
                    item { SectionTitle(stringResource(R.string.settings_network)) }
                    item {
                        Text(
                            text = stringResource(R.string.settings_network_test_desc),
                            color = NasMusicColors.TextSecondary,
                            fontSize = 19.sp,
                            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
                        )
                    }
                    item {
                        FocusableSurface(
                            onClick = {
                                if (!isNetworkTesting) {
                                    isNetworkTesting = true
                                    networkTestStatus = ""
                                    networkTestScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            try {
                                                val url = java.net.URL("https://www.baidu.com")
                                                val conn = url.openConnection() as java.net.HttpURLConnection
                                                conn.connectTimeout = 5000
                                                conn.readTimeout = 5000
                                                conn.requestMethod = "HEAD"
                                                val code = conn.responseCode
                                                conn.disconnect()
                                                if (code in 200..399) "success:网络连通 (HTTP $code)"
                                                else "error:HTTP 响应码 $code"
                                            } catch (e: java.net.SocketTimeoutException) {
                                                "error:连接超时，无法访问外网"
                                            } catch (e: java.net.UnknownHostException) {
                                                "error:DNS 解析失败，无网络连接"
                                            } catch (e: java.net.ConnectException) {
                                                "error:连接被拒绝"
                                            } catch (e: Exception) {
                                                "error:网络异常: ${e.message ?: e.javaClass.simpleName}"
                                            }
                                        }
                                        networkTestStatus = result
                                        isNetworkTesting = false
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = 1.dp,
                                    color = NasMusicColors.Border,
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            shape = RoundedCornerShape(12.dp),
                            focusedScale = 1.03f,
                            animationDurationMs = 200,
                            containerColor = NasMusicColors.SurfaceVariant,
                            contentColor = NasMusicColors.TextPrimary,
                            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                            focusedContentColor = NasMusicColors.TextPrimary,
                            pressedScale = 0.96f,
                            focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = if (isNetworkTesting) NasMusicColors.TextSecondary else NasMusicColors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (isNetworkTesting) stringResource(R.string.settings_network_testing) else stringResource(R.string.settings_network_test),
                                    fontSize = 21.sp
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                if (networkTestStatus.isNotBlank()) {
                                    val isNetSuccess = networkTestStatus.startsWith("success:")
                                    val netMessage = if (isNetSuccess) networkTestStatus.removePrefix("success:") else networkTestStatus.removePrefix("error:")
                                    Text(
                                        text = if (isNetSuccess) "✓ $netMessage" else "✗ $netMessage",
                                        color = if (isNetSuccess) NasMusicColors.Primary else NasMusicColors.Warning,
                                        fontSize = 18.sp
                                    )
                                }
                            }
                        }
                    }

                    // --- 网络搜索：Meting-API 端点配置 ---
                    if (onChangeMetingApiBaseUrl != null) {
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                        item {
                            Text(
                                text = stringResource(R.string.settings_network_search),
                                color = NasMusicColors.Primary,
                                fontSize = 23.sp,
                                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                            )
                        }
                        item {
                            Text(
                                text = stringResource(R.string.settings_meting_api_url_desc),
                                color = NasMusicColors.TextSecondary,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                            )
                        }
                        item {
                            // 预设端点单选列表
                            Text(
                                text = stringResource(R.string.settings_meting_preset_endpoints),
                                color = NasMusicColors.TextPrimary,
                                fontSize = 19.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                            )
                        }
                        val currentNormalized = settings.metingApiBaseUrl.trim().trimEnd('/')
                        com.nasmusic.tv.backend.network.MetingApiService.PRESET_ENDPOINTS.forEach { (name, url) ->
                            val selected = currentNormalized == url.trimEnd('/')
                            item {
                                FocusableSurface(
                                    onClick = {
                                        metingUrlError = null
                                        onChangeMetingApiBaseUrl(url)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    focusedScale = 1.02f,
                                    animationDurationMs = 250,
                                    containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.18f) else NasMusicColors.Surface,
                                    contentColor = NasMusicColors.TextPrimary,
                                    focusedContainerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.3f) else NasMusicColors.Primary.copy(alpha = 0.15f),
                                    focusedContentColor = NasMusicColors.TextPrimary,
                                    pressedScale = 0.98f,
                                    focusBorderColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.5f) else NasMusicColors.FocusRing.copy(alpha = 0.6f)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = name,
                                                color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                                                fontSize = 20.sp
                                            )
                                            Text(
                                                text = url,
                                                color = NasMusicColors.TextSecondary,
                                                fontSize = 17.sp,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                        if (selected) {
                                            Text(
                                                text = "✓",
                                                color = NasMusicColors.Primary,
                                                fontSize = 21.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 自定义端点选项
                        val isPreset = com.nasmusic.tv.backend.network.MetingApiService.PRESET_ENDPOINTS
                            .any { it.second.trimEnd('/') == currentNormalized }
                        val customSelected = !isPreset
                        item {
                            FocusableSurface(
                                onClick = {
                                    metingUrlError = null
                                    showMetingUrlDialog = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                focusedScale = 1.02f,
                                animationDurationMs = 250,
                                containerColor = if (customSelected) NasMusicColors.Primary.copy(alpha = 0.18f) else NasMusicColors.Surface,
                                contentColor = NasMusicColors.TextPrimary,
                                focusedContainerColor = if (customSelected) NasMusicColors.Primary.copy(alpha = 0.3f) else NasMusicColors.Primary.copy(alpha = 0.15f),
                                focusedContentColor = NasMusicColors.TextPrimary,
                                pressedScale = 0.98f,
                                focusBorderColor = if (customSelected) NasMusicColors.Primary.copy(alpha = 0.5f) else NasMusicColors.FocusRing.copy(alpha = 0.6f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.settings_meting_custom_endpoint),
                                            color = if (customSelected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                                            fontSize = 20.sp
                                        )
                                        Text(
                                            text = if (customSelected) settings.metingApiBaseUrl else stringResource(R.string.settings_meting_custom_endpoint_desc),
                                            color = NasMusicColors.TextSecondary,
                                            fontSize = 17.sp,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.settings_meting_api_url_edit),
                                        color = NasMusicColors.Primary,
                                        fontSize = 19.sp
                                    )
                                }
                            }
                        }

                        // 错误提示
                        if (metingUrlError != null) {
                            item {
                                Text(
                                    text = metingUrlError!!,
                                    color = NasMusicColors.Warning,
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                                )
                            }
                        }
                    }

                    // --- 网络搜索：Jamendo Client ID（CC 独立音乐）---
                    if (onChangeJamendoClientId != null) {
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                        item {
                            Text(
                                text = stringResource(R.string.settings_jamendo_client_id),
                                color = NasMusicColors.Primary,
                                fontSize = 23.sp,
                                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                            )
                        }
                        item {
                            Text(
                                text = stringResource(R.string.settings_jamendo_client_id_desc),
                                color = NasMusicColors.TextSecondary,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                            )
                        }
                        item {
                            // 当前值状态卡 + 修改按钮
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FocusableSurface(
                                    onClick = { showJamendoClientIdDialog = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    focusedScale = 1.02f,
                                    animationDurationMs = 250,
                                    containerColor = NasMusicColors.Surface,
                                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
                                    contentColor = NasMusicColors.TextPrimary,
                                    focusedContentColor = NasMusicColors.TextPrimary,
                                    pressedScale = 0.98f
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 14.dp)
                                    ) {
                                        Text(
                                            text = if (jamendoClientId.isBlank())
                                                stringResource(R.string.settings_not_configured_hint)
                                            else jamendoClientId.take(24) + if (jamendoClientId.length > 24) "…" else "",
                                            color = if (jamendoClientId.isBlank()) NasMusicColors.TextSecondary
                                                    else NasMusicColors.Primary,
                                            fontSize = 19.sp,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_tap_to_edit),
                                            color = NasMusicColors.TextSecondary,
                                            fontSize = 16.sp,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // --- 网络搜索：MTV 视频端点配置 ---
                    if (onChangeMvApiBaseUrl != null) {
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                        item {
                            Text(
                                text = stringResource(R.string.settings_mv_api_url),
                                color = NasMusicColors.Primary,
                                fontSize = 23.sp,
                                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                            )
                        }
                        item {
                            Text(
                                text = stringResource(R.string.settings_mv_api_url_desc),
                                color = NasMusicColors.TextSecondary,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                            )
                        }
                        item {
                            Text(
                                text = stringResource(R.string.settings_mv_preset_endpoints),
                                color = NasMusicColors.TextPrimary,
                                fontSize = 19.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                            )
                        }
                        val mvCurrentNormalized = mvApiBaseUrl.trim().trimEnd('/')
                        com.nasmusic.tv.backend.network.mv.BilibiliMvService.PRESET_ENDPOINTS.forEach { (name, url) ->
                            val selected = mvCurrentNormalized == url.trimEnd('/')
                            item {
                                FocusableSurface(
                                    onClick = {
                                        mvUrlError = null
                                        onChangeMvApiBaseUrl(url)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    focusedScale = 1.02f,
                                    animationDurationMs = 250,
                                    containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.18f) else NasMusicColors.Surface,
                                    contentColor = NasMusicColors.TextPrimary,
                                    focusedContainerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.3f) else NasMusicColors.Primary.copy(alpha = 0.15f),
                                    focusedContentColor = NasMusicColors.TextPrimary,
                                    pressedScale = 0.98f,
                                    focusBorderColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.5f) else NasMusicColors.FocusRing.copy(alpha = 0.6f)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = name,
                                                color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                                                fontSize = 20.sp
                                            )
                                            Text(
                                                text = url,
                                                color = NasMusicColors.TextSecondary,
                                                fontSize = 17.sp,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                        if (selected) {
                                            Text(
                                                text = "✓",
                                                color = NasMusicColors.Primary,
                                                fontSize = 21.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 自定义端点选项
                        val mvIsPreset = com.nasmusic.tv.backend.network.mv.BilibiliMvService.PRESET_ENDPOINTS
                            .any { it.second.trimEnd('/') == mvCurrentNormalized }
                        val mvCustomSelected = !mvIsPreset
                        item {
                            FocusableSurface(
                                onClick = {
                                    mvUrlError = null
                                    showMvUrlDialog = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                focusedScale = 1.02f,
                                animationDurationMs = 250,
                                containerColor = if (mvCustomSelected) NasMusicColors.Primary.copy(alpha = 0.18f) else NasMusicColors.Surface,
                                contentColor = NasMusicColors.TextPrimary,
                                focusedContainerColor = if (mvCustomSelected) NasMusicColors.Primary.copy(alpha = 0.3f) else NasMusicColors.Primary.copy(alpha = 0.15f),
                                focusedContentColor = NasMusicColors.TextPrimary,
                                pressedScale = 0.98f,
                                focusBorderColor = if (mvCustomSelected) NasMusicColors.Primary.copy(alpha = 0.5f) else NasMusicColors.FocusRing.copy(alpha = 0.6f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.settings_mv_custom_endpoint),
                                            color = if (mvCustomSelected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                                            fontSize = 20.sp
                                        )
                                        Text(
                                            text = if (mvCustomSelected) mvApiBaseUrl else stringResource(R.string.settings_mv_custom_endpoint_desc),
                                            color = NasMusicColors.TextSecondary,
                                            fontSize = 17.sp,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.settings_mv_api_url_edit),
                                        color = NasMusicColors.Primary,
                                        fontSize = 19.sp
                                    )
                                }
                            }
                        }

                        // 错误提示
                        if (mvUrlError != null) {
                            item {
                                Text(
                                    text = mvUrlError!!,
                                    color = NasMusicColors.Warning,
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                                )
                            }
                        }
                    }

                    // --- 网络歌词端点配置 ---
                    if (onChangeLyricsKugouBaseUrl != null || onChangeLyricsNeteaseBaseUrl != null) {
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                        item {
                            Text(
                                text = stringResource(R.string.settings_lyrics_endpoint),
                                color = NasMusicColors.Primary,
                                fontSize = 23.sp,
                                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                            )
                        }
                        // 酷狗端点
                        item {
                            Text(
                                text = stringResource(R.string.settings_lyrics_kugou_url),
                                color = NasMusicColors.TextPrimary,
                                fontSize = 19.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                        item {
                            FocusableSurface(
                                onClick = { showLyricsKugouDialog = true; lyricsUrlError = null },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                focusedScale = 1.02f,
                                animationDurationMs = 250,
                                containerColor = NasMusicColors.Surface,
                                contentColor = NasMusicColors.TextPrimary,
                                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
                                focusedContentColor = NasMusicColors.TextPrimary,
                                pressedScale = 0.98f,
                                focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = lyricsKugouBaseUrl.ifBlank { stringResource(R.string.settings_lyrics_url_reset) },
                                            color = NasMusicColors.TextSecondary,
                                            fontSize = 17.sp
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.settings_lyrics_url_edit),
                                        color = NasMusicColors.Primary,
                                        fontSize = 19.sp
                                    )
                                }
                            }
                        }
                        // 网易云端点
                        item { Spacer(modifier = Modifier.height(12.dp)) }
                        item {
                            Text(
                                text = stringResource(R.string.settings_lyrics_netease_url),
                                color = NasMusicColors.TextPrimary,
                                fontSize = 19.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                        item {
                            FocusableSurface(
                                onClick = { showLyricsNeteaseDialog = true; lyricsUrlError = null },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                focusedScale = 1.02f,
                                animationDurationMs = 250,
                                containerColor = NasMusicColors.Surface,
                                contentColor = NasMusicColors.TextPrimary,
                                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
                                focusedContentColor = NasMusicColors.TextPrimary,
                                pressedScale = 0.98f,
                                focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = lyricsNeteaseBaseUrl.ifBlank { stringResource(R.string.settings_lyrics_url_reset) },
                                            color = NasMusicColors.TextSecondary,
                                            fontSize = 17.sp
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.settings_lyrics_url_edit),
                                        color = NasMusicColors.Primary,
                                        fontSize = 19.sp
                                    )
                                }
                            }
                        }
                    }

                    // --- 天气 API Key 配置 ---
                    if (onChangeWeatherApiKey != null) {
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                        item {
                            Text(
                                text = stringResource(R.string.settings_weather_api_key),
                                color = NasMusicColors.Primary,
                                fontSize = 23.sp,
                                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                            )
                        }
                        item {
                            Text(
                                text = stringResource(R.string.settings_weather_api_key_desc),
                                color = NasMusicColors.TextSecondary,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                            )
                        }
                        item {
                            FocusableSurface(
                                onClick = { showWeatherApiKeyDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                focusedScale = 1.02f,
                                animationDurationMs = 250,
                                containerColor = NasMusicColors.Surface,
                                contentColor = NasMusicColors.TextPrimary,
                                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
                                focusedContentColor = NasMusicColors.TextPrimary,
                                pressedScale = 0.98f,
                                focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (weatherApiKey.isNotBlank()) "···${weatherApiKey.takeLast(6)}"
                                                   else stringResource(R.string.common_not_set),
                                            color = if (weatherApiKey.isNotBlank()) NasMusicColors.TextPrimary
                                                    else NasMusicColors.TextSecondary,
                                            fontSize = 20.sp
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.settings_weather_api_key_edit),
                                        color = NasMusicColors.Primary,
                                        fontSize = 19.sp
                                    )
                                }
                            }
                        }
                    }
                }
                SettingsSection.DATA -> {
                    item { SectionTitle(stringResource(R.string.settings_data)) }
                    item {
                        Text(
                            text = stringResource(R.string.settings_data_desc),
                            color = NasMusicColors.TextSecondary,
                            fontSize = 19.sp,
                            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
                        )
                    }
                    // 导出备份
                    if (onExportBackup != null) {
                        item {
                            SettingActionButton(
                                label = stringResource(R.string.settings_export_backup),
                                description = stringResource(R.string.settings_export_backup_desc),
                                onClick = { onExportBackup?.invoke() }
                            )
                        }
                    }
                    // 扫码传输（手机下载/上传备份）
                    if (onScanTransferBackup != null) {
                        item {
                            SettingActionButton(
                                label = "扫码传输备份",
                                description = "手机扫码管理备份：下载到手机 / 上传到电视 / 远程恢复",
                                onClick = { onScanTransferBackup?.invoke() }
                            )
                        }
                    }
                    // 备份文件列表
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                    item {
                        Text(
                            text = stringResource(R.string.settings_backup_list),
                            color = NasMusicColors.Primary,
                            fontSize = 23.sp,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                        )
                    }
                    if (backupFiles.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.settings_backup_empty),
                                color = NasMusicColors.TextSecondary,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    } else {
                        backupFiles.forEach { file ->
                            item {
                                BackupFileRow(
                                    file = file,
                                    onRestore = { onImportBackup?.invoke(file.uri) },
                                    onDelete = { backupToDelete = file }
                                )
                            }
                        }
                    }
                    // 从备份列表恢复（电视无系统文件选择器，恢复入口即上方备份文件列表）
                    // 备份结果消息
                    if (backupMessage != null) {
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                        item {
                            Text(
                                text = backupMessage!!,
                                color = if (backupMessage!!.startsWith("恢复") || backupMessage!!.startsWith("备份失败") || backupMessage!!.contains("失败"))
                                    NasMusicColors.Warning else NasMusicColors.Primary,
                                fontSize = 19.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

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
                initialPath = baiduMusicRootLocal.ifBlank { "/" },
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
                    ?: baiduMusicRootLocal.ifBlank { "/" },
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
            androidx.activity.compose.BackHandler { backupToDelete = null }
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.settings_delete_backup_confirm_title),
                    color = NasMusicColors.Warning,
                    fontSize = 23.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_delete_backup_confirm_message, file.displayName),
                    color = NasMusicColors.TextPrimary,
                    fontSize = 19.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                ) {
                    FocusableSurface(
                        onClick = { backupToDelete = null },
                        modifier = Modifier.width(140.dp).height(44.dp),
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
                            fontSize = 19.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)
                        )
                    }
                    FocusableSurface(
                        onClick = {
                            onDeleteBackup?.invoke(file.uri)
                            backupToDelete = null
                        },
                        modifier = Modifier.width(140.dp).height(44.dp),
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
                            fontSize = 19.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = NasMusicColors.Primary,
        fontSize = 23.sp,
        modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
    )
}

/** 分区内的分组小标题（用于"网盘"下区分百度/其他） */
@Composable
private fun SubSectionTitle(text: String) {
    Text(
        text = text,
        color = NasMusicColors.Primary,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 10.dp, start = 4.dp, top = 4.dp)
    )
}

/** 未支持网盘占位行（灰显"敬请期待"，不可聚焦） */
@Composable
private fun PlaceholderRow(name: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .background(NasMusicColors.Surface.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, color = NasMusicColors.TextSecondary, fontSize = 19.sp, modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.settings_netdisk_group_others_desc),
            color = NasMusicColors.TextSecondary.copy(alpha = 0.7f),
            fontSize = 17.sp
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.03f,
        animationDurationMs = 250,
        containerColor = NasMusicColors.Surface,
        contentColor = NasMusicColors.TextPrimary,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.98f,
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, color = NasMusicColors.TextPrimary, fontSize = 21.sp)
                Text(text = description, color = NasMusicColors.TextSecondary, fontSize = 18.sp)
            }
            // Switch indicator
            Text(
                text = if (checked) "✓  开启" else "   关闭",
                color = if (checked) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                fontSize = 19.sp
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayModeSelector(current: PlayMode, onSelect: (PlayMode) -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.settings_play_mode),
            color = NasMusicColors.TextPrimary,
            fontSize = 21.sp,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayMode.values().forEach { mode ->
                val selected = current == mode
                FocusableSurface(
                    onClick = { onSelect(mode) },
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.08f,
                    animationDurationMs = 250,
                    containerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Surface,
                    contentColor = if (selected) androidx.compose.ui.graphics.Color.Black else NasMusicColors.TextPrimary,
                    focusedContainerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Primary.copy(alpha = 0.2f),
                    focusedContentColor = if (selected) androidx.compose.ui.graphics.Color.Black else NasMusicColors.TextPrimary,
                    pressedScale = 0.95f
                ) {
                    Text(text = mode.displayName, color = if (selected) androidx.compose.ui.graphics.Color.Black else NasMusicColors.TextPrimary, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VisualizerThemeSelector(current: VisualizerTheme, onSelect: (VisualizerTheme) -> Unit) {
    Column {
        Text(
            text = "频谱主题",
            color = NasMusicColors.TextPrimary,
            fontSize = 21.sp,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VisualizerTheme.entries.forEach { theme ->
                val selected = current == theme
                FocusableSurface(
                    onClick = { onSelect(theme) },
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.08f,
                    animationDurationMs = 250,
                    containerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Surface,
                    contentColor = if (selected) androidx.compose.ui.graphics.Color.Black else NasMusicColors.TextPrimary,
                    focusedContainerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Primary.copy(alpha = 0.2f),
                    focusedContentColor = if (selected) androidx.compose.ui.graphics.Color.Black else NasMusicColors.TextPrimary,
                    pressedScale = 0.95f
                ) {
                    Text(text = theme.displayName, color = if (selected) androidx.compose.ui.graphics.Color.Black else NasMusicColors.TextPrimary, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingActionButton(
    label: String,
    description: String,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.03f,
        animationDurationMs = 250,
        containerColor = NasMusicColors.Surface,
        contentColor = NasMusicColors.TextPrimary,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.98f,
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, color = NasMusicColors.TextPrimary, fontSize = 21.sp)
                Text(text = description, color = NasMusicColors.TextSecondary, fontSize = 18.sp)
            }
            Text(
                text = stringResource(R.string.common_confirm),
                color = NasMusicColors.Primary,
                fontSize = 19.sp
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BackupFileRow(
    file: com.nasmusic.tv.util.BackupFileUtils.BackupFile,
    onRestore: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FocusableSurface(
            onClick = onRestore,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            shape = RoundedCornerShape(12.dp),
            focusedScale = 1.03f,
            animationDurationMs = 250,
            containerColor = NasMusicColors.Surface,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
            focusedContentColor = NasMusicColors.TextPrimary,
            pressedScale = 0.98f,
            focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = NasMusicColors.Primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = file.displayName, color = NasMusicColors.TextPrimary, fontSize = 20.sp)
                    Text(
                        text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(file.lastModified)),
                        color = NasMusicColors.TextSecondary,
                        fontSize = 17.sp
                    )
                }
                Text(
                    text = stringResource(R.string.settings_import_backup),
                    color = NasMusicColors.Primary,
                    fontSize = 18.sp
                )
            }
        }
        if (onDelete != null) {
            FocusableSurface(
                onClick = onDelete,
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1.06f,
                animationDurationMs = 200,
                containerColor = NasMusicColors.Surface,
                contentColor = NasMusicColors.Warning,
                focusedContainerColor = NasMusicColors.Warning.copy(alpha = 0.18f),
                focusedContentColor = NasMusicColors.Warning,
                pressedScale = 0.96f,
                focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
            ) {
                Text(
                    text = stringResource(R.string.settings_delete_backup),
                    color = NasMusicColors.Warning,
                    fontSize = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxSize().padding(vertical = 20.dp)
                )
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = NasMusicColors.TextSecondary, fontSize = 19.sp, modifier = Modifier.padding(end = 16.dp))
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, color = NasMusicColors.TextPrimary, fontSize = 19.sp)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AdjustButton(text: String, onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.1f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface,
        contentColor = NasMusicColors.TextPrimary,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        focusedContentColor = NasMusicColors.Primary,
        pressedScale = 0.95f,
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = text, color = NasMusicColors.TextPrimary, fontSize = 29.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
    }
}

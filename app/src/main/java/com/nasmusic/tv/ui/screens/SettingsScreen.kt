package com.nasmusic.tv.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nasmusic.tv.NasMusicVersion
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * 设置屏幕 — 左侧为导航侧边栏（settings-sidebar），右侧为具体选项（settings-content）
 */
private enum class SettingsSection(val titleRes: Int) {
    GENERAL(R.string.settings_general),
    PLAYBACK(R.string.settings_playback),
    LYRICS(R.string.settings_lyrics),
    CACHE(R.string.settings_cache),
    NETWORK(R.string.settings_network),
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
    onOpenEqualizer: (() -> Unit)? = null,
    onChangeMetingApiBaseUrl: ((String) -> Unit)? = null,
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

    // 提前解析字符串资源，供非 Composable 回调使用
    val metingUrlInvalidMsg = stringResource(R.string.settings_meting_api_url_invalid)
    val metingUrlHint = stringResource(R.string.settings_meting_api_url_hint)
    val metingUrlTitle = stringResource(R.string.settings_meting_api_url)

    Row(modifier = modifier.fillMaxSize().padding(32.dp)) {
        // --- 左侧：侧边导航栏（bg2 Surface 背景）---
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(NasMusicColors.Surface)
                .padding(20.dp)
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
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = stringResource(R.string.nav_settings), color = NasMusicColors.TextPrimary, fontSize = 22.sp)
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
                            SettingsSection.LYRICS -> Icons.AutoMirrored.Filled.QueueMusic
                            SettingsSection.CACHE -> Icons.Default.Settings
                            SettingsSection.NETWORK -> Icons.Default.Settings
                            SettingsSection.ABOUT -> Icons.Default.Info
                        }
                        Icon(imageVector = icon, contentDescription = null, tint = if (selected) NasMusicColors.Primary else NasMusicColors.TextSecondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = stringResource(section.titleRes), color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary, fontSize = 16.sp)
                    }
                }
            }
        }

        // --- 右侧：具体设置项 ---
        Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 24.dp)) {
            when (activeSection) {
                SettingsSection.GENERAL -> {
                    SectionTitle(stringResource(R.string.settings_general))
                    SettingSwitch(label = stringResource(R.string.settings_dark_theme), description = stringResource(R.string.settings_dark_theme_desc), checked = settings.darkTheme, onClick = { onToggleDarkTheme(!settings.darkTheme) })
                    SettingSwitch(label = stringResource(R.string.settings_animations), description = stringResource(R.string.settings_animations_desc), checked = settings.animationsEnabled, onClick = { onToggleAnimations(!settings.animationsEnabled) })
                }
                SettingsSection.PLAYBACK -> {
                    SectionTitle(stringResource(R.string.settings_playback))
                    SettingSwitch(label = stringResource(R.string.settings_auto_play), description = stringResource(R.string.settings_auto_play_desc), checked = settings.autoPlayNext, onClick = { onToggleAutoPlayNext(!settings.autoPlayNext) })
                    PlayModeSelector(current = settings.defaultPlayMode, onSelect = { onChangePlayMode(it) })
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingActionButton(
                        label = stringResource(R.string.settings_equalizer),
                        description = stringResource(R.string.settings_equalizer_desc),
                        onClick = { onOpenEqualizer?.invoke() }
                    )
                }
                SettingsSection.LYRICS -> {
                    SectionTitle(stringResource(R.string.settings_lyrics))
                    SettingSwitch(label = stringResource(R.string.settings_cache_lyrics), description = stringResource(R.string.settings_cache_lyrics_desc), checked = settings.cacheLyrics, onClick = { onToggleCacheLyrics(!settings.cacheLyrics) })
                    SettingSwitch(label = stringResource(R.string.settings_cache_cover), description = stringResource(R.string.settings_cache_cover_desc), checked = settings.cacheCover, onClick = { onToggleCacheCover(!settings.cacheCover) })
                }
                SettingsSection.ABOUT -> {
                    SectionTitle(stringResource(R.string.settings_about))
                    AboutRow(label = stringResource(R.string.settings_app_name), value = stringResource(R.string.app_name))
                    AboutRow(label = stringResource(R.string.about_version), value = NasMusicVersion.DISPLAY)
                    AboutRow(label = stringResource(R.string.settings_build_type), value = NasMusicVersion.BUILD_TYPE)
                    AboutRow(label = stringResource(R.string.about_license), value = stringResource(R.string.about_license_value))
                    AboutRow(label = stringResource(R.string.settings_supported_backends), value = "Jellyfin / Navidrome")
                }
                SettingsSection.CACHE -> {
                    SectionTitle(stringResource(R.string.settings_cache))
                    if (onClearLyricsCache != null) {
                        SettingActionButton(
                            label = stringResource(R.string.settings_clear_lyrics_cache),
                            description = stringResource(R.string.settings_clear_lyrics_cache_desc),
                            onClick = onClearLyricsCache
                        )
                    }
                    if (onClearCoverCache != null) {
                        SettingActionButton(
                            label = stringResource(R.string.settings_clear_cover_cache),
                            description = "清理 Coil 图片加载器的磁盘缓存",
                            onClick = onClearCoverCache
                        )
                    }
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
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                    )
                }
                SettingsSection.NETWORK -> {
                    SectionTitle(stringResource(R.string.settings_network))
                    Text(
                        text = stringResource(R.string.settings_network_test_desc),
                        color = NasMusicColors.TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
                    )
                    Surface(
                        onClick = {
                            if (!isNetworkTesting) {
                                isNetworkTesting = true
                                networkTestStatus = ""
                                networkTestScope.launch(Dispatchers.IO) {
                                    try {
                                        val url = java.net.URL("https://www.baidu.com")
                                        val conn = url.openConnection() as java.net.HttpURLConnection
                                        conn.connectTimeout = 5000
                                        conn.readTimeout = 5000
                                        conn.requestMethod = "HEAD"
                                        val code = conn.responseCode
                                        conn.disconnect()
                                        networkTestStatus = if (code in 200..399) {
                                            "success:网络连通 (HTTP $code)"
                                        } else {
                                            "error:HTTP 响应码 $code"
                                        }
                                    } catch (e: java.net.SocketTimeoutException) {
                                        networkTestStatus = "error:连接超时，无法访问外网"
                                    } catch (e: java.net.UnknownHostException) {
                                        networkTestStatus = "error:DNS 解析失败，无网络连接"
                                    } catch (e: java.net.ConnectException) {
                                        networkTestStatus = "error:连接被拒绝"
                                    } catch (e: Exception) {
                                        networkTestStatus = "error:网络异常: ${e.message ?: e.javaClass.simpleName}"
                                    }
                                    isNetworkTesting = false
                                }
                            }
                        },
                        enabled = !isNetworkTesting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = NasMusicColors.Border,
                                shape = RoundedCornerShape(12.dp)
                            ),
                        shape = ClickableSurfaceDefaults.shape(
                            shape = RoundedCornerShape(12.dp),
                            focusedShape = RoundedCornerShape(12.dp)
                        ),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = NasMusicColors.SurfaceVariant,
                            contentColor = NasMusicColors.TextPrimary,
                            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                            focusedContentColor = NasMusicColors.TextPrimary
                        ),
                        scale = ClickableSurfaceDefaults.scale(
                            focusedScale = 1f,
                            pressedScale = 0.96f
                        )
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
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (networkTestStatus.isNotBlank()) {
                                val isNetSuccess = networkTestStatus.startsWith("success:")
                                val netMessage = if (isNetSuccess) networkTestStatus.removePrefix("success:") else networkTestStatus.removePrefix("error:")
                                Text(
                                    text = if (isNetSuccess) "✓ $netMessage" else "✗ $netMessage",
                                    color = if (isNetSuccess) NasMusicColors.Primary else NasMusicColors.Warning,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    // --- 网络搜索：Meting-API 端点配置 ---
                    if (onChangeMetingApiBaseUrl != null) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.settings_network_search),
                            color = NasMusicColors.Primary,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                        )
                        Text(
                            text = stringResource(R.string.settings_meting_api_url_desc),
                            color = NasMusicColors.TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                        )

                        // 预设端点单选列表
                        Text(
                            text = stringResource(R.string.settings_meting_preset_endpoints),
                            color = NasMusicColors.TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                        )
                        val currentNormalized = settings.metingApiBaseUrl.trim().trimEnd('/')
                        com.nasmusic.tv.backend.network.MetingApiService.PRESET_ENDPOINTS.forEach { (name, url) ->
                            val selected = currentNormalized == url.trimEnd('/')
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
                                            fontSize = 15.sp
                                        )
                                        Text(
                                            text = url,
                                            color = NasMusicColors.TextSecondary,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    if (selected) {
                                        Text(
                                            text = "✓",
                                            color = NasMusicColors.Primary,
                                            fontSize = 16.sp
                                        )
                                    }
                                }
                            }
                        }

                        // 自定义端点选项
                        val isPreset = com.nasmusic.tv.backend.network.MetingApiService.PRESET_ENDPOINTS
                            .any { it.second.trimEnd('/') == currentNormalized }
                        val customSelected = !isPreset
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
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = if (customSelected) settings.metingApiBaseUrl else stringResource(R.string.settings_meting_custom_endpoint_desc),
                                        color = NasMusicColors.TextSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.settings_meting_api_url_edit),
                                    color = NasMusicColors.Primary,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        // 错误提示
                        if (metingUrlError != null) {
                            Text(
                                text = metingUrlError!!,
                                color = NasMusicColors.Warning,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                            )
                        }
                    }
                }
            }
        }
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
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = NasMusicColors.Primary,
        fontSize = 18.sp,
        modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
    )
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
                Text(text = label, color = NasMusicColors.TextPrimary, fontSize = 16.sp)
                Text(text = description, color = NasMusicColors.TextSecondary, fontSize = 13.sp)
            }
            // Switch indicator
            Text(
                text = if (checked) "✓  开启" else "   关闭",
                color = if (checked) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                fontSize = 14.sp
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
            fontSize = 16.sp,
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
                    Text(text = mode.displayName, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
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
                Text(text = label, color = NasMusicColors.TextPrimary, fontSize = 16.sp)
                Text(text = description, color = NasMusicColors.TextSecondary, fontSize = 13.sp)
            }
            Text(
                text = stringResource(R.string.common_confirm),
                color = NasMusicColors.Primary,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = NasMusicColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(end = 16.dp))
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, color = NasMusicColors.TextPrimary, fontSize = 14.sp)
    }
}

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nasmusic.tv.NasMusicVersion
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * 设置屏幕 — 左侧为导航侧边栏（settings-sidebar），右侧为具体选项（settings-content）
 */
private enum class SettingsSection(val displayName: String) {
    GENERAL("通用"),
    PLAYBACK("播放"),
    LYRICS("歌词"),
    CACHE("缓存"),
    NETWORK("网络"),
    ABOUT("关于")
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
    modifier: Modifier = Modifier
) {
    var activeSection by remember { mutableStateOf(SettingsSection.GENERAL) }

    // 网络测试状态
    var isNetworkTesting by remember { mutableStateOf(false) }
    var networkTestStatus by remember { mutableStateOf("") }
    val networkTestScope = rememberCoroutineScope()

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
                Text(text = "设置", color = NasMusicColors.TextPrimary, fontSize = 22.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsSection.values().forEach { section ->
                val selected = section == activeSection
                var isFocused by remember { mutableStateOf(false) }
                val animScale = remember { Animatable(1f) }
                val scope = rememberCoroutineScope()
                Surface(
                    onClick = { activeSection = section },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .scale(animScale.value)
                        .border(
                            width = if (isFocused) 2.dp else 0.dp,
                            color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .onFocusChanged {
                            isFocused = it.isFocused
                            scope.launch { animScale.animateTo(if (isFocused) 1.08f else 1f, tween(250)) }
                        },
                    shape = ClickableSurfaceDefaults.shape(
                        shape = RoundedCornerShape(12.dp),
                        focusedShape = RoundedCornerShape(12.dp)
                    ),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.18f) else Color.Transparent,
                        contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                        focusedContainerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.3f) else NasMusicColors.SurfaceVariant,
                        focusedContentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary
                    ),
                    scale = ClickableSurfaceDefaults.scale(
                        focusedScale = 1f,
                        pressedScale = 0.97f
                    )
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
                        Text(text = section.displayName, color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary, fontSize = 16.sp)
                    }
                }
            }
        }

        // --- 右侧：具体设置项 ---
        Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 24.dp)) {
            when (activeSection) {
                SettingsSection.GENERAL -> {
                    SectionTitle("通用")
                    SettingSwitch(label = "暗色主题", description = "使用深色背景保护视力", checked = settings.darkTheme, onClick = { onToggleDarkTheme(!settings.darkTheme) })
                    SettingSwitch(label = "界面动画", description = "页面切换和焦点动画", checked = settings.animationsEnabled, onClick = { onToggleAnimations(!settings.animationsEnabled) })
                }
                SettingsSection.PLAYBACK -> {
                    SectionTitle("播放")
                    SettingSwitch(label = "自动播放下一首", description = "歌曲结束后自动播放队列中的下一首", checked = settings.autoPlayNext, onClick = { onToggleAutoPlayNext(!settings.autoPlayNext) })
                    PlayModeSelector(current = settings.defaultPlayMode, onSelect = { onChangePlayMode(it) })
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingActionButton(
                        label = "均衡器",
                        description = "调节各频段增益",
                        onClick = { onOpenEqualizer?.invoke() }
                    )
                }
                SettingsSection.LYRICS -> {
                    SectionTitle("歌词")
                    SettingSwitch(label = "自动缓存歌词", description = "匹配到的歌词自动保存到本地", checked = settings.cacheLyrics, onClick = { onToggleCacheLyrics(!settings.cacheLyrics) })
                    SettingSwitch(label = "自动缓存封面", description = "专辑封面下载并缓存到本地", checked = settings.cacheCover, onClick = { onToggleCacheCover(!settings.cacheCover) })
                }
                SettingsSection.ABOUT -> {
                    SectionTitle("关于")
                    AboutRow(label = "应用名称", value = "NAS Music TV")
                    AboutRow(label = "版本", value = NasMusicVersion.DISPLAY)
                    AboutRow(label = "构建类型", value = NasMusicVersion.BUILD_TYPE)
                    AboutRow(label = "开源协议", value = "GPL v3")
                    AboutRow(label = "支持后端", value = "Jellyfin / Navidrome")
                }
                SettingsSection.CACHE -> {
                    SectionTitle("缓存管理")
                    if (onClearLyricsCache != null) {
                        SettingActionButton(
                            label = "清除歌词缓存",
                            description = "删除所有已缓存的歌词文件",
                            onClick = onClearLyricsCache
                        )
                    }
                    if (onClearCoverCache != null) {
                        SettingActionButton(
                            label = "清除封面缓存",
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
                    SectionTitle("网络检测")
                    Text(
                        text = "测试设备是否能访问互联网",
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
                                text = if (isNetworkTesting) "测试中..." else "测试网络",
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
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged {
                    isFocused = it.isFocused
                    scope.launch { animScale.animateTo(if (isFocused) 1.03f else 1f, tween(250)) }
                },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(12.dp),
            focusedShape = RoundedCornerShape(12.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NasMusicColors.Surface,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
            focusedContentColor = NasMusicColors.TextPrimary
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 0.98f
        )
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
            text = "默认播放模式",
            color = NasMusicColors.TextPrimary,
            fontSize = 16.sp,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayMode.values().forEach { mode ->
                val selected = current == mode
                var isFocused by remember { mutableStateOf(false) }
                val animScale = remember { Animatable(1f) }
                val scope = rememberCoroutineScope()
                Surface(
                    onClick = { onSelect(mode) },
                    modifier = Modifier
                        .scale(animScale.value)
                        .border(
                            width = if (isFocused) 2.dp else 0.dp,
                            color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .onFocusChanged {
                            isFocused = it.isFocused
                            scope.launch { animScale.animateTo(if (isFocused) 1.08f else 1f, tween(250)) }
                        },
                    shape = ClickableSurfaceDefaults.shape(
                        shape = RoundedCornerShape(12.dp),
                        focusedShape = RoundedCornerShape(12.dp)
                    ),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Surface,
                        contentColor = if (selected) androidx.compose.ui.graphics.Color.Black else NasMusicColors.TextPrimary,
                        focusedContainerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Primary.copy(alpha = 0.2f),
                        focusedContentColor = if (selected) androidx.compose.ui.graphics.Color.Black else NasMusicColors.TextPrimary
                    ),
                    scale = ClickableSurfaceDefaults.scale(
                        focusedScale = 1f,
                        pressedScale = 0.95f
                    )
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
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged {
                    isFocused = it.isFocused
                    scope.launch { animScale.animateTo(if (isFocused) 1.03f else 1f, tween(250)) }
                },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(12.dp),
            focusedShape = RoundedCornerShape(12.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NasMusicColors.Surface,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
            focusedContentColor = NasMusicColors.TextPrimary
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 0.98f
        )
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
                text = "执行",
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

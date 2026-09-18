package com.nasmusic.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.nasmusic.tv.R

/**
 * 设置分区枚举（v2.36.0 由 `SettingsScreen.kt` 的 private enum 上移至此并放开可见性）。
 *
 * ⚠️ 上移原因：竖屏两级页需要把「当前进入的分区」提升到 `NavigationViewModel`
 * （`AppRoot` 的 BACK handler 要读它，见方案 §6.2 / §8.7 / K2）——
 * `NavigationViewModel` 在 `ui/viewmodel/`，若枚举留在 `ui/screens/SettingsScreen.kt`，
 * 会出现 viewmodel → screens 的反向依赖；放到 `ui/screens/settings/` 子包后仍属
 * 「设置域」，依赖方向可接受（方案 §8.7 规避方案 ①）。
 *
 * 可见性取 `public`（方案原文写 internal）：`NavigationViewModel` 与 `SettingsScreen`
 * 都是 public 成员，暴露 internal 类型会编译失败（`'public' function exposes its
 * 'internal' parameter type`）。模块内无外部消费方，公开无实际风险。
 *
 * ⚠️ 播放统计（`onOpenPlayStats`）、均衡器（`onOpenEqualizer`）**不是分区**，
 * 是 PLAYBACK / GENERAL 分区内的入口按钮，行为不变。
 */
enum class SettingsSection(val titleRes: Int, val icon: ImageVector) {
    GENERAL(R.string.settings_general, Icons.Default.Settings),
    PLAYBACK(R.string.settings_playback, Icons.Default.Audiotrack),
    DOWNLOAD(R.string.settings_download, Icons.Default.Download),
    SERVER(R.string.nav_server, Icons.Default.Storage),
    CACHE(R.string.settings_cache, Icons.Default.Tune),
    NETWORK(R.string.settings_network, Icons.Default.Wifi),
    NETDISK(R.string.settings_netdisk, Icons.Default.Cloud),
    DATA(R.string.settings_data, Icons.Default.MusicNote),
    ABOUT(R.string.settings_about, Icons.Default.Info)
}

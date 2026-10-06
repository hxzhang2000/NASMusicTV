package com.nasmusic.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
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
    /**
     * 照片墙（§7.1 / §7.2）。
     *
     * ⚠️ 插在 PLAYBACK 之后而非追加到末尾：可视化效果本身就在 PLAYBACK 分区里，
     * 「效果主题」与「照片墙」相邻符合用户预期。本项目**没有**持久化「分区顺序」，
     * 也没有任何测试断言 `entries` 的顺序 ⇒ 插入是安全的。
     */
    PHOTO_WALL(R.string.settings_photo_wall, Icons.Default.PhotoLibrary),
    /**
     * 本地音乐（§5.6 权限瘦身）：总开关（唯一触发音乐权限申请的入口）+ 重扫。
     * ⚠️ 插在 PHOTO_WALL 与 DOWNLOAD 之间：与照片墙同为「本机媒体」域，且
     * 「已下载」是它开关关闭时唯一保留的本地内容（D3），相邻符合语义。
     * 同上：无持久化分区顺序、无 entries 顺序断言 ⇒ 插入安全。
     */
    LOCAL_MUSIC(R.string.settings_local_music, Icons.Default.LibraryMusic),
    DOWNLOAD(R.string.settings_download, Icons.Default.Download),
    SERVER(R.string.nav_server, Icons.Default.Storage),
    CACHE(R.string.settings_cache, Icons.Default.Tune),
    NETWORK(R.string.settings_network, Icons.Default.Wifi),
    NETDISK(R.string.settings_netdisk, Icons.Default.Cloud),
    DATA(R.string.settings_data, Icons.Default.MusicNote),
    ABOUT(R.string.settings_about, Icons.Default.Info)
}

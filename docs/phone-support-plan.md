# 手机端适配开发计划

> NAS Music TV 从 Android TV 向 Android Phone 扩展的技术方案与实施计划。
>
> 创建日期：2026-08-21
> 当前版本：v2.19.0

---

## 1. 现状分析

### 1.1 TV 特有依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `androidx.tv:tv-material` | 1.0.0-alpha10 | TV Material3 主题/组件（Surface、Text） |
| `androidx.tv:tv-foundation` | 1.0.0-alpha10 | TV 基础组件 |
| `androidx.leanback:leanback` | 1.0.0 | TV Leanback 支持 |

### 1.2 TV 特有代码模式

**FocusableSurface 组件**（13+ 个文件使用）：
- `HomeScreen`（7 处）、`EqualizerScreen`、`SettingsScreen`、`ExitConfirmDialog`、`BackupTransferDialog`、`LyricsSettingsDialog`、`NetdiskScreen`、`SongInfoPanel`、`AppRoot`
- 作用：D-Pad 焦点高亮、缩放动画、点击反馈
- 手机端可复用但需要增加触摸点击支持

**D-Pad 键盘事件**（`onPreviewKeyEvent`）：
- `PlayerControls.kt`：左右键 seek 进度条
- `MvPlaybackScreen.kt`：任意键激活控制栏
- `KaraokePlaybackScreen.kt`：任意键激活控制栏

**TopNavigationBar**（`AppRoot.kt` 162-202 行）：
- 8 个导航项水平排列：首页/播放/曲库/我的/队列/网络音乐/网盘/设置
- TV 横屏宽屏下合理，手机竖屏会非常拥挤

**AndroidManifest 限制**：
- `android.software.leanback` required=true → 手机无法安装
- `android.hardware.screen.landscape` required=true → 强制横屏
- `android.hardware.touchscreen` required=false → 已兼容触摸

**TV 特有组件**（tv-material3）：
- `Surface`、`Text`、`MaterialTheme`、`ClickableSurfaceDefaults`
- 手机端需要替换为标准 `androidx.material3`

**Navigation 方式**：
- 手动 `when(currentScreen)` switch，无 Jetpack Navigation
- 手机端可沿用此方案，但需要底部导航栏

### 1.3 TV 特有功能

| 功能 | TV | 手机 | 需要改造 |
|------|-----|------|---------|
| D-Pad 焦点导航 | 核心 | 不需要 | 焦点逻辑可保留但不强制 |
| HDMI-CEC 媒体键 | 核心 | 不需要 | 移除或条件编译 |
| 沉浸模式（全屏无 UI） | 核心 | 可选 | 保留但默认关闭 |
| 退出确认对话框 | 必要（无 BACK 键） | 不需要（系统手势） | 可选保留 |
| 遥控器扫码控制 | 核心 | 不需要 | 条件隐藏 |
| 手机遥控二维码 | 核心 | 不需要 | 条件隐藏 |

---

## 2. 横屏 vs 竖屏分析

### 2.1 各页面适配性

| 页面 | 横屏（TV 风格） | 竖屏（Phone 风格） | 建议 |
|------|----------------|-------------------|------|
| 播放页（NowPlaying） | 封面左 + 歌词右（完美） | 封面上 + 歌词下（自然） | 双布局 |
| 首页（Home） | 仪表盘横排（完美） | 卡片竖排（自然） | 双布局 |
| 曲库（Library） | 4-6 列网格（完美） | 2-3 列网格（自然） | 响应式列数 |
| 队列（Queue） | 宽列表（完美） | 窄列表（自然） | 自适应 |
| 设置（Settings） | 左右分栏（完美） | 单列（自然） | 响应式 |
| 专辑详情 | 封面+列表横排（完美） | 封面+列表竖排（自然） | 双布局 |
| 网络音乐 | 多栏浏览（完美） | 单栏（自然） | 响应式 |
| K 歌页 | 全屏横屏（完美） | 横屏锁定或简化 | 横屏锁定 |
| MTV 视频 | 全屏横屏（完美） | 横屏锁定 | 横屏锁定 |

### 2.2 结论：推荐默认竖屏 + 特定页面横屏

**推荐策略**：默认竖屏 + 播放页/视频/K 歌自动横屏

理由：
1. **音乐 App 主流是竖屏**：Spotify、Apple Music、网易云音乐均以竖屏为主
2. **竖屏单手操作友好**：手机最主要使用姿势
3. **播放页横屏有价值**：封面 + 歌词左右分栏在横屏下体验更好
4. **视频/K 歌必须横屏**：内容本身是横屏比例

实施方式：
- 非播放页：`ActivityInfo.SCREEN_ORIENTATION_PORTRAIT`
- 播放页/MTV/K 歌：`ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE`
- 使用 `WindowSizeClass` 做响应式布局，而非硬编码方向

---

## 3. 手机端导航设计

### 3.1 TV 导航（现状）

```
顶部 Row: [首页] [播放] [曲库] [我的] [队列] [网络音乐] [网盘] [设置]
```

### 3.2 Phone 导航（建议）

**底部导航栏（BottomNavigationBar）**：

```
底部 Bar: [首页] [曲库] [网络] [我的]    ← 4 个核心入口
隐藏入口: [设置] [队列] [网盘]           ← 从"我的"进入或右上角图标
```

理由：
- Google Material Design 指南：底部导航最多 5 个入口
- 首页、曲库、网络音乐、我的 = 核心功能
- 设置/队列/网盘 = 次要功能，从"我的"页面进入

### 3.3 播放页底部 MiniPlayer

手机端需要在所有页面底部常驻一个 MiniPlayer 条：

```
+------------------------------+
| [封面] 歌名 - 歌手    [>] [>>]|  <- MiniPlayer
+------------------------------+
|                              |
|        页面内容区域            |
|                              |
+------------------------------+
| [首页] [曲库] [网络] [我的]   |  <- BottomNav
+------------------------------+
```

---

## 4. 组件替换方案

### 4.1 TV Material3 → Standard Material3

| TV 组件 | 替换为 | 改动量 |
|---------|--------|--------|
| `tv.material3.Text` | `material3.Text` | import 替换 |
| `tv.material3.Surface` | `material3.Surface` | import 替换 |
| `tv.material3.MaterialTheme` | `material3.MaterialTheme` | import 替换 |
| `tv.material3.ClickableSurfaceDefaults` | 自定义 wrapper 或 material3 | 中等 |
| `tv.material3.ExperimentalTvMaterial3Api` | 移除 OptIn | 删除注解 |

### 4.2 FocusableSurface 改造

**当前实现**（TV 专用）：
- D-Pad 焦点高亮边框
- 焦点缩放动画
- OK 键点击触发

**手机端改造**：
- 移除焦点高亮边框（手机不需要）
- 保留缩放动画（触摸反馈）
- 增加 `clickable` modifier（触摸点击）
- 保留 D-Pad 支持（可选，兼容蓝牙键盘）

### 4.3 进度条改造

**当前实现**：D-Pad 左右键 seek，无触摸拖拽

**手机端改造**：
- 增加 `Slider` 支持触摸拖拽
- 保留 D-Pad 左右键 seek（兼容）
- 增加滑动惯性和吸附

---

## 5. 分阶段实施计划

### Phase 1：基础适配（3-5 天）

**目标**：App 可在手机上安装运行，基本 UI 可用

| 任务 | 文件 | 说明 |
|------|------|------|
| 修改 AndroidManifest | `AndroidManifest.xml` | leanback required=false, landscape required=false, portrait required=true |
| TV Material3 → Standard Material3 | `ui/theme/Theme.kt`, 所有 Screen 文件 | 替换 tv.material3 为 material3 |
| 引入 material3 依赖 | `app/build.gradle.kts` | 添加 `implementation("androidx.compose.material3:material3:...")` |
| 移除 D-Pad 强制焦点 | `FocusableSurface.kt` | 手机模式下隐藏焦点边框 |
| 底部导航栏 | `AppRoot.kt` | 手机端使用 BottomNavigationBar 替代顶部导航 |
| MiniPlayer 条 | `AppRoot.kt` | 底部常驻迷你播放器 |
| 响应式列数 | `LibraryScreen.kt` | 根据窗口宽度调整网格列数 |

### Phase 2：交互适配（3-5 天）

**目标**：手机端触摸操作流畅

| 任务 | 文件 | 说明 |
|------|------|------|
| 进度条触摸拖拽 | `PlayerControls.kt` | 替换 D-Pad seek 为 Slider |
| 封面手势操作 | `NowPlayingScreen.kt` | 上滑显示歌词/下滑关闭 |
| 播放页横屏 | `MainActivity.kt` | 播放页自动切换横屏 |
| 搜索页触摸优化 | `NetworkScreen.kt` | 软键盘自动弹出、搜索历史 |
| 队列拖拽排序 | `QueueScreen.kt` | 长按拖拽移动歌曲 |

### Phase 3：功能适配（2-3 天）

**目标**：手机端功能完整

| 任务 | 文件 | 说明 |
|------|------|------|
| 条件隐藏 TV 功能 | 多个文件 | 遥控器扫码、HDMI-CEC 等 |
| 退出手势替代 | `MainActivity.kt` | 系统返回手势替代退出确认 |
| 通知栏媒体控制 | `PlaybackService.kt` | 手机端通知栏操作更频繁 |
| 后台播放保活 | `PlaybackService.kt` | 手机端更严格的后台限制 |
| 横屏锁定 K 歌/MTV | `KaraokePlaybackScreen.kt`, `MvPlaybackScreen.kt` | 视频内容强制横屏 |

### Phase 4：测试与优化（2-3 天）

**目标**：发布就绪

| 任务 | 说明 |
|------|------|
| 多机型适配测试 | 不同屏幕尺寸、分辨率 |
| 横竖屏切换测试 | 播放页横竖屏切换不丢状态 |
| 触摸性能优化 | 列表滚动、动画帧率 |
| 焦点兼容测试 | 蓝牙键盘/手柄兼容 |
| TV 回归测试 | 确保 TV 端功能不受影响 |

**预计总工期：10-16 天**

---

## 6. 关键技术决策

### 6.1 单 Activity vs 多 Activity

**建议：沿用单 Activity + Compose Navigation**

理由：
- 当前架构已是单 Activity + 手动 `when(currentScreen)`
- Compose 的响应式 UI 天然支持屏幕变化
- 多 Activity 会增加状态同步复杂度

### 6.2 代码组织方式

**建议：共享核心逻辑 + 平台特定 UI**

```
app/src/main/java/com/nasmusic/tv/
├── backend/           ← 完全共享（Jellyfin/Navidrome/Subsonic/Network）
├── player/            ← 完全共享（PlayerManager/PlaybackService）
├── data/              ← 完全共享（模型/存储）
├── ui/
│   ├── common/        ← 共享组件（FocusableSurface/MiniPlayer）
│   ├── tv/            ← TV 特有（D-Pad 焦点、遥控器扫码）
│   └── phone/         ← Phone 特有（BottomNav、手势、触摸进度条）
├── util/              ← 完全共享
```

### 6.3 条件判断方式

**建议：运行时检测 + CompositionLocal**

```kotlin
// 检测是否为 TV 设备
val isTV = LocalContext.current.packageManager
    .hasSystemFeature("android.software.leanback")

// 根据设备类型切换 UI
CompositionLocalProvider(LocalIsTV provides isTV) {
    AppContent()
}
```

### 6.4 tv-material 替换策略

**建议：渐进替换，不一次性迁移**

1. 首先让 `tv-material` 和 `material3` 共存
2. 逐步替换 import，每次编译验证
3. 保留 `FocusableSurface` 的双模式支持

### 6.5 改动量评估

| 模块 | 文件数 | 改动量 | 说明 |
|------|--------|--------|------|
| `ui/theme/Theme.kt` | 1 | 中 | 替换 tv-material3 → material3 主题 |
| `ui/components/AppRoot.kt` | 1 | 大 | 底部导航 + MiniPlayer + 条件分支 |
| `ui/components/FocusableSurface.kt` | 1 | 中 | 手机模式下简化 |
| `ui/components/PlayerControls.kt` | 1 | 中 | 触摸进度条 |
| `ui/screens/*.kt` | ~15 | 小 | import 替换 + 偶尔的布局微调 |
| `ui/MainActivity.kt` | 1 | 中 | 屏幕方向管理 |
| `AndroidManifest.xml` | 1 | 小 | 属性修改 |
| `app/build.gradle.kts` | 1 | 小 | 添加 material3 依赖 |
| **总计** | **~20** | | **共享逻辑不变，仅 UI 层改造** |

---

## 7. 风险与注意事项

1. **TV 端回归风险**：tv-material3 替换可能影响 TV 端渲染效果，需逐页验证
2. **横竖屏状态丢失**：播放页横竖屏切换时 ViewModel 状态需正确保留
3. **后台播放限制**：Android 12+ 对后台服务限制更严格，需适配前台服务通知
4. **Compose 性能**：手机端列表滚动更频繁，需关注 LazyColumn 性能
5. **TV 回归测试**：每阶段完成后需在 TV 设备上验证，确保无退化

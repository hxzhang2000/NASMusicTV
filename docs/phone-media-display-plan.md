# 手机端媒体展示与播放保活开发方案

> 版本：v1.0
> 日期：2026-09-05
> 状态：方案设计（待评审）

## 一、概述

### 1.1 目标

解决手机端四个核心问题，并适配全厂商实况窗/灵动岛类展示：

1. 汽车蓝牙连接时车机屏无歌曲信息
2. 应用切后台歌曲停止（有时候）
3. 锁屏页面展示歌曲信息和控制键
4. 全厂商实况窗/灵动岛/超级岛/原子岛/流体云/灵动胶囊/Now Bar 展示歌曲信息和控制键

### 1.2 核心认识：分层适配

所有厂商的"灵动岛"类功能本质都是 **Android 13+ 系统媒体控件（SMSC, System Media Style Controller）的厂商呈现**。适配策略分两层：

| 层级 | 机制 | 覆盖范围 |
|---|---|---|
| **L1 系统级**（统一） | Media3 `MediaLibrarySession` 自动驱动系统 `MediaSession` + 完整 `MediaMetadata`（title/artist/album/artworkUri）+ 前台服务保活 | Android 13+ 所有厂商自动生效（含锁屏、SMSC、多数灵动岛） |
| **L2 厂商级**（可选增强） | 厂商特定 Notification extra 或 SDK 扩展，提升呈现效果（自定义布局、跨设备流转） | 仅目标厂商生效 |

**结论**：先做 L1，90% 场景自动生效。L2 厂商增强按需逐个接入，每个厂商独立评估。

---

## 二、问题清单与根因

### 2.1 汽车蓝牙无歌曲信息

- **根因**：`PlayerManager.playSong` 把 `Song` 转 `MediaItem` 时**未填充 `MediaMetadata`**。Media3 的 `MediaLibrarySession` 向系统 `MediaSessionManager` 发布元数据，但只有 `MediaItem.mediaMetadata` 非空时蓝牙 AVRCP 才能读到。当前通知只传 `title` 字符串，蓝牙屏空白。
- **蓝牙协议**：A2DP 传输音频流，AVRCP（Audio/Video Remote Control Profile）传输元数据（标题/艺术家/封面）和控制命令。AVRCP 读系统 `MediaSession` 的 `metadata`。

### 2.2 切后台歌曲停止

- **根因 1**：[PlaybackService.onTaskRemoved](app/src/main/java/com/nasmusic/tv/player/PlaybackService.kt:163) 直接 `stopSelf()` → 服务销毁 → player release → 停止播放。用户从"最近任务"划掉应用时触发。
- **根因 2**：暂停时若 `stopForeground(STOP_FOREGROUND_REMOVE)` 被调用，服务降为后台，系统低内存时杀进程。
- **根因 3**：厂商省电策略（小米神隐模式、华为应用冻结、OPPO 休眠、vivo 后台清理）在用户未加入白名单时杀后台。需引导用户加入电池优化白名单。

### 2.3 锁屏页面无歌曲信息/控制键

- **根因**：系统锁屏媒体控件由 `MediaSession` 自动驱动，但需要：
  - `MediaSession.setMetadata()` 完整填充（含 artworkUri）
  - `setPlaybackState()` 实时更新
  - `setVisible(true)` 确保对锁屏可见
- Media3 `MediaLibrarySession` 自动同步 player 状态，但元数据依赖 `MediaItem.mediaMetadata`。当前缺口同 2.1。

### 2.4 厂商实况窗/灵动岛

- **根因**：厂商扩展接口未接入。但多数厂商功能基于系统 `MediaSession` 自动驱动，L1 做完后大部分自动生效。

---

## 三、L1 系统级适配（所有厂商基础）

### 3.1 MediaMetadata 填充

`PlayerManager.playSong` 把 `Song` 转 `MediaItem` 时填充完整元数据：

```kotlin
private fun buildMediaItem(song: Song): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(song.title)
        .setArtist(song.artist)
        .setAlbumTitle(song.album)
        .setArtworkUri(song.coverUrl?.toUri())
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setTrackNumber(song.trackNumber)
        .setReleaseYear(song.year)
        .build()
    return MediaItem.Builder()
        .setMediaId(song.id)
        .setUri(song.streamUrl ?: song.id)
        .setMediaMetadata(metadata)
        .build()
}
```

**封面加载策略**：百度 dlink 需 UA 头，系统 `BitmapLoader` 不支持自定义请求头。方案对比：

| 方案 | 优点 | 缺点 | 推荐 |
|---|---|---|---|
| `setArtworkUri(url)` + 自定义 `BitmapLoader` | 统一走 Coil（已有百度 UA 拦截器），无重复加载 | 需实现 `BitmapLoader` | ✅ |
| `setArtworkData(bitmap, FILE_PATH)` | 绕过 URL 加载器，稳定 | 预加载 Bitmap 占内存 | ❌（仅 fallback） |

**推荐**：自定义 `CoilBitmapLoader` 继承 `MediaSession.BitmapLoader`，内部走 Coil `ImageLoader` 执行请求（复用 NasMusicApp 已注入的百度 dlink UA 拦截器），返回 `Bitmap`。

### 3.2 前台服务保活

#### 3.2.1 onTaskRemoved 修复

[PlaybackService.onTaskRemoved](app/src/main/java/com/nasmusic/tv/player/PlaybackService.kt:163) 改为：

```kotlin
override fun onTaskRemoved(rootIntent: Intent?) {
    val player = mediaLibrarySession?.player
    if (player != null && player.isPlaying) {
        // 正在播放 → 继续播放，保留前台服务
        AppLog.d("PlaybackService", "onTaskRemoved: keeping playback alive")
    } else {
        // 已暂停 → 停止服务
        AppLog.d("PlaybackService", "onTaskRemoved: stopped (not playing)")
        stopSelf()
    }
}
```

#### 3.2.2 暂停时保留前台通知

暂停时用 `ServiceCompat.stopForeground(this, STOP_FOREGROUND_DETACH)` 而非 `STOP_FOREGROUND_REMOVE`，保留通知但允许系统在极端情况回收。

#### 3.2.3 AndroidManifest 权限确认

```xml
<service
    android:name=".player.PlaybackService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### 3.3 锁屏控件自动生效

Media3 `MediaLibrarySession` 已自动驱动系统锁屏控件，L1 做完后锁屏自动显示封面+控制键。无需额外代码。

### 3.4 自定义 BitmapLoader

在 `PlaybackService.onCreate` 注入：

```kotlin
mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback)
    .setSessionActivity(pendingIntent)
    .setBitmapLoader(CoilBitmapLoader(this, coilImageLoader))
    .build()
```

`CoilBitmapLoader`：

```kotlin
class CoilBitmapLoader(
    private val context: Context,
    private val imageLoader: ImageLoader
) : MediaSession.BitmapLoader {
    override suspend fun loadBitmap(uri: Uri): Bitmap? {
        return runCatching {
            val request = ImageRequest.Builder(context).data(uri).build()
            imageLoader.execute(request).drawable?.toBitmap()
        }.getOrNull()
    }
    override suspend fun decodeBitmap(data: ByteArray): Bitmap? { ... }
}
```

---

## 四、L2 厂商级适配（暂不开发，仅作技术储备）

> ⚠️ **本章节所述厂商特定适配（华为 HMS LiveView Kit、OPPO 跨设备 SDK、小米 MiuiNotification 反射等）暂不开发**。L1 系统级适配完成后，所有厂商的实况窗/灵动岛/超级岛等功能已通过系统 `MediaSession` 自动生效（覆盖率约 90%）。L2 厂商增强仅作为后续技术储备，待 L1 实测后如某厂商效果显著不佳再单独评估是否启动。
>
> 下述内容保留作为方案参考，不纳入实施阶段。

### 4.1 厂商功能对照表

| 厂商 | 功能名称 | 首发系统 | 机制 | L1 自动生效 | L2 增强 |
|---|---|---|---|---|---|
| 华为 | 实况窗 | HarmonyOS 4 / EMUI 14 | `Notification` + `EXTRA_LIVE_VIEW` extra / HMS LiveView Kit | ⚠️ 部分 | Notification extra 或 HMS SDK |
| 小米 | 超级岛 | HyperOS 1.0 | 系统 `MediaSession` 自动驱动 | ✅ | 可选：`MiuiNotification` 反射（不推荐） |
| vivo / iQOO | 原子岛 | OriginOS 4 / BlueOS | 系统 `MediaSession` 自动驱动 | ✅ | 无需额外 |
| OPPO / 一加 | 流体云 | ColorOS 14 | 系统 `MediaSession` + 跨设备 SDK | ✅ | 跨设备流转需 OPPO SDK |
| 荣耀 | 灵动胶囊 | MagicOS 8.0 | 系统 `MediaSession` 自动驱动 | ✅ | 无需额外 |
| 三星 | Now Bar | One UI 7 | 锁屏底部药丸，系统 `MediaSession` 驱动 | ✅ | 无需额外 |

### 4.2 华为实况窗适配（暂不开发）

```kotlin
// HwLiveViewHelper.kt
fun isHuaweiLiveViewSupported(): Boolean {
    return Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) &&
           Build.VERSION.SDK_INT >= 33
}

fun buildLiveViewNotification(base: Notification, song: Song, bitmap: Bitmap?): Notification {
    // 通过 Notification extras 传 LiveView 数据
    // 华为 HMS LiveView Kit 或反射 EXTRA_LIVE_VIEW
}
```

**决策**：先用 Notification extra，实测效果不理想再接 HMS LiveView Kit（增加 APK 体积）。

### 4.3 其他厂商策略

- **vivo/OPPO/荣耀/三星**：L1 做完后自动生效，**无需额外适配**
- **小米超级岛**：L1 自动生效；`MiuiNotification` 反射脆弱不推荐
- **OPPO 流体云跨设备**：需 OPPO SDK，非核心需求，P2 优先级

### 4.4 厂商反射统一不做

`MiuiNotification`、华为反射调用等厂商反射方案：
- 脆弱，系统更新易失效
- 维护成本高
- 用户体验不稳定

**统一不采用**，依赖 L1 系统级 + L2 官方 SDK。

---

## 五、其他手机系统展示场景

除厂商实况窗外，还有以下场景需要考虑：

### 5.1 Android Auto（车机）

- **机制**：Android Auto 通过 `MediaBrowserServiceCompat` 或 Media3 `MediaLibraryService` 读取媒体树
- **现状（2026-09-17 更新）**：**已适配（阶段 1 + 2.5 + 3 + 4.1，v2.33.0）**。`PlaybackService` 的 `MediaLibrarySession.Callback` 已实现 `onGetLibraryRoot` / `onGetItem` / `onGetChildren`，并额外覆写 `onAddMediaItems` / `onSetMediaItems` 解析 URI；媒体树由 `player/MediaLibraryTree.kt` 提供（根菜单 4 项：当前播放 / 离线下载 / 收藏 / 歌单，各配单色白矢量图标）。**搜索与语音已可用**：`onSearch` / `onGetSearchResult` + `onSetMediaItems` 的 `searchQuery` 分支，Manifest 已补 `MEDIA_PLAY_FROM_SEARCH` action。Manifest 另补 `automotive_app_desc`、`MediaBrowserService` action 与 `androidx.car.app.TintableAttributionIcon` 提供方图标。**未做**：`onPlaybackResumption`（A-10）、艺人专辑节点 + NAS 短期缓存（阶段 2.3 剩余）、强调色定制（4.2）、包验证收紧（4.3）、DHU/真车验收。详见 [`android-auto-plan.md`](./android-auto-plan.md) 与 `technical-overview.md` §10.157
- ~~**适配**：实现 `MediaLibrarySession.Callback` 的 `onGetLibraryRoot` / `onGetChildren`，暴露"最近播放"、"收藏"、"歌单"等节点~~（已完成，见上）
- **优先级**：P1（车机用户需求明确，且 L1 做完后只需补 Callback）

### 5.2 Wear OS（手表）

- **机制**：Wear OS 通过 `MediaBrowserService` 读取媒体，手表端 MediaSession 控制器
- **现状**：未适配
- **适配**：同 Android Auto，实现 `MediaLibrarySession.Callback` 暴露媒体树即可（Media3 自动支持 Wear OS）
- **优先级**：P2（手表用户量小）

### 5.3 系统 SMSC（下拉通知栏媒体控件）

- **机制**：Android 13+ 系统媒体样式通知（SMSC），由 `MediaSession` 自动驱动
- **现状**：L1 做完后自动生效
- **适配**：无需额外，L1 覆盖
- **优先级**：P0（L1 内含）

### 5.4 灭屏显示 AOD（Always-On Display）

- **机制**：Android 14+ 的 `MediaSession` 自动驱动 AOD 媒体信息（部分厂商支持）
- **现状**：L1 做完后自动生效
- **适配**：无需额外
- **厂商差异**：
  - 小米：AOD 显示当前歌曲封面+标题（HyperOS 自动读取 MediaSession）
  - 华为：AOD 显示歌曲标题（HarmonyOS 自动）
  - 三星：AOD 媒体控件（One UI 自动）
  - OPPO/vivo：AOD 自动读取 MediaSession
- **优先级**：P0（L1 内含）

### 5.5 桌面小部件 Widget

- **机制**：`AppWidgetProvider` + RemoteViews，独立于 MediaSession
- **现状**：未提供
- **适配**：开发 `MediaControlWidgetProvider`，显示封面+标题+控制按钮，通过 `RemoteAction` + `PendingIntent` 转发到 `MediaSession`（用 `ACTION_MEDIA_BUTTON`）
- **数据更新**：`PlayerManager` 状态变化时调 `AppWidgetManager.updateAppWidget` 刷新
- **优先级**：P2（用户可选，非核心）

### 5.6 语音助手集成

- **机制**：各厂商语音助手通过系统 `MediaSession` 控制播放
- **现状**：L1 做完后自动生效（`ACTION_MEDIA_BUTTON` 已转发到 MediaSession）
- **厂商**：
  - 小米：小爱同学（"播放音乐"、"下一首"）
  - 华为：Celia
  - OPPO：小布
  - vivo：Jovi
  - 荣耀：YOYO
  - 三星：Bixby
  - Google：Google Assistant
- **适配**：无需额外，L1 覆盖
- **增强**：可选接入 Google Assistant `Action`（`intent action = MEDIA_PLAY_FROM_SEARCH`）
- **优先级**：P0（L1 内含）

### 5.7 省电策略白名单引导

- **问题**：厂商省电策略会杀后台播放服务：
  - 小米：神隐模式（应用耗电监控）
  - 华为：应用冻结（后台限制）
  - OPPO：应用速冻
  - vivo：后台清理
  - 三星：自适应电池
  - 荣耀：应用启动管理
- **适配**：
  - 首次播放时检测是否在电池优化白名单：`PowerManager.isIgnoringBatteryOptimizations`
  - 不在白名单时提示用户加入（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent）
  - 设置页提供"加入白名单"入口
- **厂商特定**：检测厂商后跳转对应设置页（小米自启动管理、华为应用启动管理等），用 `Intent` 跳转厂商特定设置（如 `miui.intent.action.APP_PERM_EDITOR`）
- **优先级**：P1（解决"切后台停歌"的关键补充）

### 5.8 音频焦点 AudioFocus

- **机制**：`AudioManager.requestAudioFocus`，与其他应用（电话、其他音乐 App、导航语音）抢占音频
- **现状**：`PlaybackService` 已设 `AudioAttributes(USAGE_MEDIA)`，Media3 自动管理 AudioFocus（`setHandleAudioBecomingNoisy(true)` + `setAudioAttributes(audioAttributes, true)`）
- **适配**：无需额外，已正确配置
- **验证**：电话来电时暂停播放，挂断后恢复（需 `AudioFocusRequest` 的 `OnAudioFocusChangeListener`，Media3 已内置）
- **优先级**：P0（已实现）

### 5.9 媒体输出切换（Media Routing）

- **机制**：Android 11+ 的 `MediaRouter2`，或厂商特定路由（华为超级终端、小米跨屏协同）
- **现状**：未适配
- **适配**：L1 后蓝牙/扬声器/耳机切换由系统自动处理（`AudioAttributes` 配合）；厂商跨设备流转（华为超级终端、OPPO 流体云跨设备）需厂商 SDK
- **优先级**：
  - 基础路由（蓝牙/扬声器/耳机）：P0（L1 内含，系统自动）
  - 厂商跨设备流转：P2（非核心需求）

### 5.10 跨设备流转

- **机制**：厂商多设备协同
- **厂商**：
  - 华为超级终端：HarmonyOS 多设备协同，需 HMS 分布式能力
  - 小米跨屏协同：HyperOS 多设备联动
  - OPPO 流体云跨设备：ColorOS 14 跨设备流转
  - vivo 互传：OriginOS 跨设备
- **适配**：各厂商 SDK，工作量巨大
- **优先级**：P3（暂不实施，需求不明确）

### 5.11 Android 14/15 前台服务限制

- **机制**：Android 14+ 要求 `foregroundServiceType` 声明，且媒体播放服务需 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限；Android 15 进一步限制后台启动前台服务
- **现状**：`PlaybackService` 已继承 `MediaLibraryService`，Media3 自动处理
- **适配**：
  - `AndroidManifest` 声明 `foregroundServiceType="mediaPlayback"` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限
  - 确保 `PlaybackService` 在 `onCreate` 立即 `startForeground`（Android 14+ 严格限制启动延迟）
- **优先级**：P0（Android 14+ 必须正确配置，否则服务启动失败）

### 5.12 通知栏展开样式

- **机制**：`NotificationCompat.BigTextStyle` / `NotificationCompat.MediaStyle` + `setShowActionsInCompactView`
- **现状**：当前通知只有上/下/播放暂停三按钮，无展开样式
- **适配**：用 `MediaStyleNotificationHelper`（Media3 提供）替代手写 `NotificationCompat`，自动适配 Android 13+ SMSC 样式
- **优先级**：P0（L1 内含，改用 Media3 官方 Helper）

### 5.13 状态栏媒体图标

- **机制**：系统自动显示（`MediaSession` 活跃时状态栏出现媒体图标）
- **现状**：L1 后自动生效
- **适配**：无需额外
- **优先级**：P0（L1 内含）

### 5.14 桌面窗口模式（Android 15 桌面模式）

- **机制**：Android 15 桌面模式支持窗口化，`MediaSession` 跨窗口同步
- **现状**：L1 后自动生效
- **适配**：无需额外
- **优先级**：P0（L1 内含）

### 5.15 歌词展示能力分析

#### 5.15.1 系统级展示（蓝牙/锁屏/SMSC/实况窗/AOD/Auto/Wear）均不支持歌词

| 展示场景 | 支持内容 | 歌词支持 | 原因 |
|---|---|---|---|
| 蓝牙 AVRCP（车机） | 标题/艺术家/专辑/时长/封面 | ❌ | AVRCP 协议只支持 `Song Length` / `Song Title` 等固定属性，无歌词字段 |
| 锁屏 MediaSession 控件 | 封面+标题+控制键 | ❌ | Android 标准锁屏仅渲染 `MediaStyle` 通知，不暴露自定义文本区域 |
| SMSC（下拉通知栏媒体控件） | 封面+标题+控制键 | ❌ | `Notification.MediaStyle` 无歌词扩展点 |
| 厂商实况窗/灵动岛 | 标题+封面+控制键 | ❌ | 均基于 `MediaSession` 自动驱动，无歌词扩展 |
| AOD 灭屏显示 | 标题/封面（厂商差异） | ❌ | AOD 仅渲染静态元数据，无滚动文本 |
| Android Auto | 标题/封面/控制/浏览 | ❌ | 车机 UI 由 Google 定义，不支持歌词 |
| Wear OS | 标题/封面/控制 | ❌ | 手表 UI 同理 |
| 桌面小部件 Widget | 可自定义 RemoteViews | ⚠️ 仅静态行 | RemoteViews 能力有限，无法做逐字动画；且系统小部件刷新频率低（~30s），不适合实时歌词 |

**结论**：歌词**只能在 App 内播放页显示**，系统级展示最多显示标题+艺术家+封面。

---

## 六、涉及文件

| 文件 | 改动 | 优先级 |
|---|---|---|
| `player/PlayerManager.kt` | 新增 `buildMediaItem` 填充 `MediaMetadata` | P0 |
| `player/PlaybackService.kt` | `onTaskRemoved` 不再 stopSelf；注入 `CoilBitmapLoader`；改用 Media3 `MediaStyleNotificationHelper` | P0 |
| `player/CoilBitmapLoader.kt`（新增） | 实现 `MediaSession.BitmapLoader`，走 Coil 加载封面（含百度 UA 拦截器） | P0 |
| `AndroidManifest.xml` | 确认 `foregroundServiceType="mediaPlayback"` + `POST_NOTIFICATIONS` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | P0 |
| `NasMusicApp.kt` | 构造 `CoilBitmapLoader` 注入 PlaybackService | P0 |
| `player/BatteryOptimizationHelper.kt`（新增） | 检测+引导电池优化白名单，厂商特定设置页跳转 | P1 |
| `player/MediaLibraryTree.kt`（新增） | 实现 `MediaLibrarySession.Callback` 的 `onGetLibraryRoot`/`onGetChildren`，暴露最近播放/收藏/歌单（Android Auto/Wear 用） | P1 |
| `player/HwLiveViewHelper.kt`（新增） | 华为实况窗 Notification extra 增强 | **暂不开发**（L2 储备） |
| `player/MediaControlWidgetProvider.kt`（新增） | 桌面小部件 | P2 |
| `player/OppoMultiDeviceHelper.kt`（新增） | OPPO 跨设备流转 | **暂不开发**（L2 储备） |

---

## 七、实施阶段

### 阶段 1（P0，解决问题 1/2/3 + 所有厂商基础 + 5.3/5.4/5.6/5.8/5.11/5.12/5.13/5.14）

1. `PlayerManager.buildMediaItem` 填充 `MediaMetadata`
2. `PlaybackService` 注入 `CoilBitmapLoader` + 修复 `onTaskRemoved` + 改用 `MediaStyleNotificationHelper`
3. `AndroidManifest` 权限确认（`foregroundServiceType` + `POST_NOTIFICATIONS` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`）
4. `NasMusicApp` 构造 `CoilBitmapLoader` 注入

**预期效果**：
- 蓝牙车机屏显示标题/艺术家/封面 ✅
- 锁屏显示封面+控制键 ✅
- 切后台继续播放（正在播放时） ✅
- 所有厂商实况窗/灵动岛自动生效（vivo/OPPO/荣耀/三星/小米确认，华为大概率） ✅
- 语音助手控制 ✅
- AOD 显示 ✅
- 状态栏媒体图标 ✅

### 阶段 2（P1，补充优化）

5. `BatteryOptimizationHelper` 电池白名单引导（解决厂商省电杀后台）
6. `MediaLibraryTree` 实现 `MediaLibrarySession.Callback`（Android Auto/Wear 支持）

> 注：L2 厂商增强（华为实况窗 Notification extra、OPPO 跨设备 SDK 等）暂不开发，待 L1 实测后单独评估。

### 阶段 3（P2，可选增强）

7. 桌面小部件 Widget

> 注：华为超级终端、OPPO 流体云跨设备、小米跨屏协同等厂商多设备协同（原 P3）暂不开发。

---

## 八、验证

### 8.1 L1 验证（所有厂商通用）

- **蓝牙**：连汽车蓝牙，车机屏显示标题/艺术家/封面
- **锁屏**：Android 13+ 锁屏显示封面+控制键
- **切后台**：Home 键 30s 后仍播放；最近任务划掉按 P0 策略继续/暂停保留
- **SMSC**：下拉通知栏媒体控件显示封面+控制键
- **AOD**：灭屏显示歌曲标题/封面（厂商差异）
- **语音助手**：小爱/Celia/小布/Jovi/YOYO/Bixby 语音控制播放
- **状态栏**：播放时状态栏显示媒体图标

### 8.2 厂商实况窗验证

| 厂商 | 机型示例 | 验证点 |
|---|---|---|
| 华为 | Mate 60 / P60（HarmonyOS 4+） | 实况窗显示标题+封面+控制 |
| 小米 | 14 / 13（HyperOS） | 超级岛显示 |
| vivo | X100（OriginOS 4） | 原子岛显示 |
| OPPO | Find X7（ColorOS 14） | 流体云显示 |
| 荣耀 | Magic 6（MagicOS 8） | 灵动胶囊显示 |
| 三星 | S24（One UI 7） | Now Bar 显示 |

### 8.3 Android Auto/Wear 验证（阶段 2 后）

- Android Auto：连车机，浏览"最近播放"/"收藏"/"歌单"节点
- Wear OS：连手表，控制播放

### 8.4 省电白名单验证（阶段 2 后）

- 小米：神隐模式加入白名单后后台不杀
- 华为：应用启动管理设为"手动管理"+ 允许后台活动
- OPPO：应用速冻白名单
- vivo：后台清理白名单

---

## 九、风险与决策点

### 9.1 封面加载方式

- **选项 A**：`setArtworkUri` + 自定义 `CoilBitmapLoader`（统一走 Coil，复用百度 UA 拦截器）
- **选项 B**：`setArtworkData(bitmap)` 预加载（绕过 URL 加载器，稳定但占内存）
- **推荐**：选项 A，避免预加载内存压力；Coil 已有磁盘缓存，重复加载快

### 9.2 onTaskRemoved 行为

- **选项 A**：继续播放（与主流音乐 App 一致）
- **选项 B**：暂停 + 保留前台通知
- **推荐**：选项 A

### 9.3 华为实况窗 SDK（暂不开发）

- **选项 A**：先用 Notification extra，实测后再决定
- **选项 B**：直接接 HMS LiveView Kit
- **当前决策**：**暂不开发**，依赖 L1 系统级自动生效。若 L1 在华为机型实测效果显著不佳再单独评估。

### 9.4 厂商反射

- **不推荐**：`MiuiNotification` 反射、华为反射调用等，脆弱且系统更新易失效

### 9.5 Android Auto/Wear 媒体树

- **选项 A**：实现完整 `MediaLibrarySession.Callback`（最近播放/收藏/歌单/专辑）
- **选项 B**：仅暴露一个"当前队列"节点
- **推荐**：选项 A，提供完整浏览体验

### 9.6 桌面小部件

- **选项 A**：简单小部件（封面+标题+3 控制按钮）
- **选项 B**：可展开大部件（含播放队列）
- **推荐**：先做选项 A，按用户反馈迭代

---

## 十、依赖与工具

### 10.1 Media3 依赖（已存在）

```gradle
implementation "androidx.media3:media3-exoplayer:1.4.0"
implementation "androidx.media3:media3-session:1.4.0"
implementation "androidx.media3:media3-ui:1.4.0"
```

### 10.2 Coil（已存在）

```gradle
implementation "io.coil-kt:coil:2.5.0"
```

### 10.3 可选新增（暂不开发）

- 华为 HMS LiveView Kit（L2 储备，待 L1 实测后评估）
- OPPO 跨设备 SDK（L2 储备）
- 小米 MiuiNotification 反射（不推荐，脆弱）

---

## 十一、验收标准

### 11.1 阶段 1 验收

- ✅ 蓝牙车机屏显示歌曲信息（标题/艺术家/封面）
- ✅ 锁屏显示封面+控制键（Android 13+）
- ✅ 切后台播放中继续播放，划掉最近任务继续播放
- ✅ vivo/OPPO/荣耀/三星/小米实况窗/灵动岛自动显示
- ✅ 华为实况窗显示（L1 自动效果；若不达预期暂不做 L2 增强）
- ✅ 语音助手控制播放
- ✅ AOD 显示歌曲信息

### 11.2 阶段 2 验收

- ✅ 电池优化白名单引导生效
- ✅ Android Auto 车机浏览媒体树
- ✅ Wear OS 手表控制播放

### 11.3 阶段 3 验收

- ✅ 桌面小部件显示封面+控制

> 注：OPPO 跨设备流转、华为超级终端等厂商多设备协同暂不开发。

---

## 十二、备注

### 12.1 当前代码缺口

- ~~[PlaybackService](app/src/main/java/com/nasmusic/tv/player/PlaybackService.kt) 已用 `MediaLibraryService`，但 `MediaLibrarySession.Callback` 为空实现~~ → **2026-09-17 已修复**：Callback 已实现 `onGetLibraryRoot` / `onGetItem` / `onGetChildren` + 覆写 `onAddMediaItems` / `onSetMediaItems`（见 §5.1 与 `technical-overview.md` §10.157）
- `PlayerManager.playSong` 未填充 `MediaItem.mediaMetadata`
- `onTaskRemoved` 直接 `stopSelf` 导致切后台停歌
- 通知用 `NotificationCompat` 手写，应改用 Media3 `MediaStyleNotificationHelper`

### 12.2 Media3 优势

- `MediaLibraryService` 自动驱动系统 `MediaSession`，无需手写 `MediaSessionCompat`
- 自动同步 `MediaMetadata` 到锁屏/SMSC/蓝牙
- 内置 `AudioFocus` 管理和 `AudioBecomingNoisy` 处理
- 支持 Android Auto/Wear 媒体树

### 12.3 厂商兼容性参考

- Android 13+ SMSC 是所有厂商灵动岛的基础
- Media3 1.4+ 已适配 Android 14/15 前台服务限制
- 厂商省电策略是"切后台停歌"的主要非代码原因，需用户配合加白名单

---

## 附录：参考资料

- Media3 官方文档：https://developer.android.com/media/media3
- Android Auto 媒体浏览器：https://developer.android.com/training/cars/media
- Android 13+ 系统媒体控件：https://developer.android.com/guide/topics/media/controls
- 华为实况窗：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/live-view
- 小米超级岛：HyperOS 开发者文档
- OPPO 流体云：ColorOS 开发者文档
- vivo 原子岛：OriginOS 开发者文档
- 荣耀灵动胶囊：MagicOS 开发者文档
- 三星 Now Bar：One UI 开发者文档

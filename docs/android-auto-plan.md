# Android Auto 手机映射开发方案

> 文档版本：**v2.1**（2026-09-17）
> 状态：**阶段 1 已实施并验证**（v2.33.0）。阶段 2/3/4 未实施，见 §七。
> 适用版本：v2.32.7 及之后
> 关联文档：[`phone-media-display-plan.md`](./phone-media-display-plan.md) §5.1、[`unified-source-architecture.md`](./unified-source-architecture.md)
>
> **〔2026-09-17 v2.1 修订说明〕** v2.0 中**已被实施期证据推翻**的三处，已在正文就地更正（均带「实施期更正」标记）：
> ① §3.3 —— `androidx.media.utils.MediaConstants` **在 compile classpath 上不可见**，须改用 `androidx.media3.session.MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT`；
> ② §5.4 —— `replayAt` **不能**用 `setMediaItem(item, index)`（第二参数是起始位置(ms)而非索引），须用 `replaceMediaItem` + `seekTo`；
> ③ §七 阶段 3 —— Media3 **没有** `onPlayFromSearch`，语音搜索最终走 `onSetMediaItems` 的 `requestMetadata.searchQuery`，实现顺序不能反。
> 另：实施期新增 lint 抑制 `MissingIntentFilterForMediaSearch`，理由见 §七 阶段 3 说明块。
> 实施细节与验证记录见 `docs/technical-overview.md` §10.157。

---

## 一、概述

### 1.1 目标

让 NASMusicTV 在 **Android Auto** 车机上可用：手机负责解码与运算，车机仅作为显示与交互终端（即"手机映射/投屏"模式）。

用户在车机上的期望闭环：

1. 车机启动器里能看到 NASMusicTV 图标，点进去是**导航标签页**形式的内容入口
2. 能浏览内容树并点歌播放
3. 能用车机/方向盘按键控制播放、暂停、上一曲、下一曲
4. 能语音说"播放 XXX"直接搜到并播放

### 1.2 手机映射 vs 车机原生（AAOS）——本文只做前者

| | **Android Auto（本方案）** | AAOS（Android Automotive OS） |
|---|---|---|
| 应用装在哪 | **手机** | 车机内嵌 Android 系统 |
| 车机角色 | 外接显示器 + 输入设备 | 完整 Android 设备 |
| 分发形态 | 单个 APK（手机版） | **独立 APK 变体**（`automotive` flavor + `<uses-feature android:name="android.hardware.type.automotive">`） |
| 与现有代码 | 复用现有 phone/TV 包 | 需要新增模块或 product flavor |

**结论**：Android Auto 不需要新增 flavor、不需要改 minSdk，**复用现有 APK 即可**。AAOS 是另一条独立路线，本方案不覆盖。

### 1.3 本方案的核心判断

> **本项目不缺架构，缺的是"接线"。**

`PlaybackService` 早已继承 Media3 的 `MediaLibraryService`，`MediaLibrarySession.Callback` 的浏览回调也早已实现。但由于**三处 Manifest 声明从未补上**，Android Auto 至今**完全发现不了这个应用**——等于内容树写好了却没有门。

工作量分布：**少量声明接线（P0）+ 播放入口实现（P1，含一个必须做的状态同步）+ 内容树扩展（主要工作量）**。

### 1.4 v2.0 相对 v1.0 的确认结果

v1.0 遗留的 4 个未决项 + 2 个"待 DHU 实测"项，本版全部关闭：

| v1.0 遗留 | v2.0 结论 | 依据 |
|---|---|---|
| `onSetMediaItems` 返回 `null` 的语义 | **会直接抛 NPE**，绝不可返回 null | `MediaSessionImpl.java:689-696` `checkNotNull` |
| 返回值是否触发再次 `setMediaItems` | **会**，Media3 用返回值设置 player | `MediaSessionStub.java:992` + `MediaUtils.java:194-213` |
| 是否需引入 `kotlinx-coroutines-guava` | **不需要**，项目已依赖 Guava `SettableFuture` | `CoilBitmapLoader.kt:23-33` 已有范式 |
| 包验证白名单 | Media3 **已内置** Auto/AAOS 判断；仅需补助理包名 | `MediaSession.java:873/887` + `MediaSessionImpl.java:399-415` |
| 根菜单第 4 项动态策略 | **固定 4 项**，第 4 项优雅降级（不做动态探测） | 见 §6.2 |
| 内网/外网双地址 | **本方案不做**，列为未来增强 | 见 §6.3 |

**并在核查中额外发现 2 个 v1.0 遗漏的真实缺陷**（A-13 / A-14，见 §2.2）。

---

## 二、现状盘点（已逐行核对源码）

### 2.1 已具备的能力

| 能力 | 证据 | 说明 |
|---|---|---|
| 服务基类正确 | `PlaybackService.kt:55` `class PlaybackService : MediaLibraryService()` | Media3 原生支持 Android Auto，**无需改用 legacy `MediaBrowserServiceCompat`** |
| 浏览回调已实现 | `PlaybackService.kt:312-355` | `onGetLibraryRoot` / `onGetItem` / `onGetChildren` 均已实现 |
| 媒体树工具类 | `player/MediaLibraryTree.kt` | 已有 root → 「当前队列」两层结构 |
| 封面加载器 | `PlaybackService.kt:359` `setBitmapLoader(CoilBitmapLoader(...))` | 车机端封面可正常显示 |
| 会话跳转 | `PlaybackService.kt:358` `setSessionActivity(pendingIntent)` | 车机点"打开应用"能回到 `MainActivity` |
| 服务已导出 | `AndroidManifest.xml:66-73` `android:exported="true"` | 跨进程绑定可行 |
| 队列入口齐备 | `PlayerManager.kt:553` `playQueue(songs, startIndex)` | 同时维护 `_playerState` 与 ExoPlayer playlist |
| 依赖齐备 | `app/build.gradle.kts:171-174` Media3 **1.2.1**；`androidx.media:media:1.6.0` 与 Guava 均经传递引入 | **无需新增任何依赖** |

### 2.2 缺口清单

| 编号 | 优先级 | 缺口 | 位置 | 后果 |
|---|---|---|---|---|
| **A-1** | **P0** | 无 `res/xml/automotive_app_desc.xml` | 文件不存在 | Android Auto **无法识别为媒体应用** |
| **A-2** | **P0** | Manifest 无 `com.google.android.gms.car.application` meta-data | `AndroidManifest.xml:36-48` | Android Auto **完全发现不了本应用** |
| **A-3** | **P0** | service intent-filter 缺 `android.media.browse.MediaBrowserService` | `AndroidManifest.xml:70-72` | 平台侧 MediaBrowser 客户端找不到服务 |
| **A-4** | **P1** | 未实现 `onSetMediaItems` / `onAddMediaItems` | `PlaybackService.kt` | **车机上点歌无法播放**（§4.2） |
| **A-5** | **P1** | 内容树仅「当前队列」一层 | `MediaLibraryTree.kt:42-48` | 车机体验空洞 |
| **A-6** | **P1** | `onGetChildren` 为**同步阻塞**实现 | `PlaybackService.kt:335-355` | 接入 NAS 后 **ANR**（§5.4） |
| **A-7** | **P1** | 伪分页逻辑 | `PlaybackService.kt:344-351` | Auto **不支持分页**，列表被静默截断（§3.4） |
| **A-8** | **P2** | 未读 root hints | `PlaybackService.kt:312-320` | 根菜单超限项被**静默丢弃**（§3.3） |
| **A-9** | **P2** | 未实现 `onSearch` / `onGetSearchResult` | `PlaybackService.kt` | 语音"播放 XXX"不可用 |
| **A-10** | **P2** | 未实现 `onPlaybackResumption` | `PlaybackService.kt` | 车机连接后无法续播 |
| **A-11** | **P3** | 无 attribution icon | `AndroidManifest.xml` | 媒体卡片显示默认图标 |
| **A-13** | **P1** | **`onNeedResolveStreamUrl` 实现在 ViewModel** | `MainViewModel.kt:790` → `PlayerViewModel.resolveAndPlayByIndex` | **无 UI 时网络歌曲播不了**（§5.8） |
| **A-14** | **P1** | **`findInQueue()` 缺 `setUri()`** | `MediaLibraryTree.kt:96-108` | 配合 `onAddMediaItems` 默认实现会抛 `UnsupportedOperationException`（§4.3） |

#### A-13 详解：streamUrl 解析被绑在 UI 生命周期上

`playerManager.onNeedResolveStreamUrl = { index -> playerVM.resolveAndPlayByIndex(index) }`（`MainViewModel.kt:790`）。

而 `onNeedResolveStreamUrl` 的触发点是「播放到 streamUrl 为空的歌」（`PlayerManager.kt:371/416/746/783`）。

**问题**：Android Auto 场景下用户直接用车机操作，`MainActivity` 可能**从未启动** → `MainViewModel` 未创建 → 回调为 `null` → 网络歌曲**静默播不出来**。同理影响 Wear OS、蓝牙唤起等一切无 UI 场景。

#### A-14 详解：2026-09-07 那次修复漏了一处

`code-review-2026-09-07.md` 的 P0-9 修了 `getQueueItems()` 的 `setUri()`，但**同一文件的 `findInQueue()` 漏改了**：

```kotlin
// MediaLibraryTree.kt:96-108 —— 无 setUri()
private fun findInQueue(mediaId: String): MediaItem? {
    ...
    return MediaItem.Builder()
        .setMediaId(song.id)
        .setMediaMetadata(...)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .build()          // ← 没有 .setUri()
}
```

**为什么这个缺陷至今没暴露**：因为 `findInQueue` 只被 `getItem()` 调用（`MediaLibraryTree.kt:55`），而 Media3 的 `onGetItem` 路径**不要求 URI**；只有当它作为播放请求进入 `onAddMediaItems` 时才会出问题——而 `onAddMediaItems` 目前**还没被覆写**，所以这条路径还没被走到过。

### 2.3 既有文档偏差更正

`phone-media-display-plan.md` §5.1 的描述**已过时**：

> **原文（已失效）**：「现状：`PlaybackService` 已继承 `MediaLibraryService`，但 `MediaLibrarySession.Callback` 为空实现，未暴露媒体树（`onGetLibraryRoot` / `onGetChildren`）」

**实际**：`PlaybackService.kt:312-355` 已完整实现三个浏览回调。真正缺的是 §2.2 的 A-1/A-2/A-3。

> ⚠️ 这也解释了 `code-review-2026-09-07.md:347` 的结论为何至今有效——该报告当时即指出"代码写了但接线没通"，并建议"在 Android Auto Desktop Head Unit 上做一次端到端验证"。**那次验证至今没有做过。**

---

## 三、官方硬性要求（已核实）

### 3.1 Manifest 三处声明

#### （1）应用级：声明支持 Android Auto

位置：`<application>` 内。

```xml
<meta-data
    android:name="com.google.android.gms.car.application"
    android:resource="@xml/automotive_app_desc" />
```

#### （2）服务级：补齐平台 MediaBrowserService action

```xml
<intent-filter>
    <action android:name="androidx.media3.session.MediaLibraryService" />
    <action android:name="android.media.browse.MediaBrowserService" />
</intent-filter>
```

Media3 官方文档明确要求**两个 action 同时注册**：前者供 Media3 客户端发现服务，后者提供平台 `MediaBrowser` 兼容层。

> ⚠️ **这是最易漏的一处**：项目当前只有前者。

#### （3）可选：attribution icon

```xml
<meta-data
    android:name="androidx.car.app.TintableAttributionIcon"
    android:resource="@drawable/ic_car_attribution" />
```

用于媒体卡片等"内容优先"场景。**必须是单色（推荐白色）矢量图**。可复用通知小图标。

### 3.2 `res/xml/automotive_app_desc.xml`

新建文件，内容**必须**为：

```xml
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="media"/>
</automotiveApp>
```

### 3.3 根菜单约束：最多 4 项可浏览子项

Android Auto 与 AAOS 对**根菜单**结构有硬约束，且**通过 root hints 动态传递**：

| Root hint 常量 | 含义 | 官方默认值 |
|---|---|---|
| `MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT` | 根子项数量上限 | **4** |
| `MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_SUPPORTED_FLAGS` | 根子项受支持的标志 | `MediaItem.FLAG_BROWSABLE` |

**实践要点**：

- 系统把根内容渲染为**导航标签页**。超出 limit 的项会被**静默丢弃**——不报错，只是"不见了"。
- 每个标签项配**单色（最好白色）图标** + **简短标签**（过长会被截断）。
- **并非所有 AAOS 版本都会下发 hints**，缺失时按默认值处理。

**读取方式**：root hints 位于 `onGetLibraryRoot` 的 `params.extras`。

```kotlin
override fun onGetLibraryRoot(
    session: MediaLibrarySession,
    controller: MediaSession.ControllerInfo,
    params: MediaLibraryService.LibraryParams?
): ListenableFuture<LibraryResult<MediaItem>> {
    val extras = params?.extras
    val limit = extras?.getInt(
        MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT, DEFAULT_ROOT_LIMIT
    ) ?: DEFAULT_ROOT_LIMIT
    // 按 limit 裁剪根菜单（见 §5.6）
    return Futures.immediateFuture(
        LibraryResult.ofItem(mediaLibraryTree.getLibraryRoot(limit), params)
    )
}
```

> **〔2026-09-17 实施期更正，重要〕** 上表第 1 行的常量**不能**按 `androidx.media.utils.MediaConstants` 直接引用：
> `androidx.media:media:1.6.0` 在本项目里只是 `media3-session` 的 **runtime scope 传递依赖**——
> 它确实进了 APK，但**不在 compile classpath 上**，写 `import androidx.media.utils.MediaConstants`
> 会直接编译失败（实测：`Unresolved reference 'utils'`）。
>
> 正确写法是用 **Media3 自己导出的同名别名**（源码级证据：`media3-session-1.2.1` 的
> `MediaConstants.java:397-398` 就是 `EXTRAS_KEY_ROOT_CHILDREN_LIMIT =
> androidx.media.utils.MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT`，**字面量完全相同**）：
>
> ```kotlin
> import androidx.media3.session.MediaConstants
> // 唯一可用的那个：EXTRAS_KEY_ROOT_CHILDREN_LIMIT
> ```
>
> 这样**不新增任何依赖**。而第 2 行的 `..._SUPPORTED_FLAGS` **Media3 没有导出别名**，
> 且其默认值就是 `FLAG_BROWSABLE`、本应用根菜单 4 项**全部**可浏览 → 显式设置是 no-op，
> **故有意不设置**。若日后确需该常量，再显式加 `implementation("androidx.media:media:1.6.0")`。

### 3.4 ⚠️ Android Auto / AAOS 不支持分页

官方原文：

> 「Android Auto 和 AAOS **不支持分页**。如果您使用 `MediaLibraryService` 和 `MediaLibrarySession` 构建应用，**请不要依赖 `page` 或 `pageSize` 参数的 `onGetChildren` 回调**。」

**对当前代码的影响**：`PlaybackService.kt:344-351` 的分页切片在车机上**不会按预期工作**：

```kotlin
val effectivePageSize = if (pageSize > 0) pageSize else 50
val start = page * effectivePageSize      // ← 车机上 page/pageSize 不可靠
```

必须改为**一次性返回全部**（或用自定安全上限截断）。

### 3.5 响应时限

- **`onGetRoot` 必须快速返回非 null**。官方明确警告：**不要在 `onGetRoot` 中做用户鉴权或耗时操作**，否则调用方超时。映射到 Media3：`onGetLibraryRoot` 必须立即返回，**不能**等待 NAS 连接。
- 耗时逻辑一律放 `onGetChildren` / `onGetItem`（且必须异步，见 §5.4）。

---

## 四、Media3 播放链路（源码级确认）

> 本节结论全部来自 `media3-session-1.2.1-sources.jar`（本机 Gradle 缓存），非推测。

### 4.1 完整调用链

Android Auto 点歌时，Media3 内部的实际流程：

```
车机点歌
  └─ 控制器调用 setMediaItems(items, startIndex, startPositionMs)
      └─ MediaSessionStub.setMediaItem*  (MediaSessionStub.java:980-1037)
          └─ onSetMediaItemsOnHandler  (MediaSessionImpl.java:687-697)
              │   ├─ checkNotNull(callback.onSetMediaItems(...), "...must return a non-null future")
              │   └─ 默认实现转发给 onAddMediaItems  (MediaSession.java:1457-1468)
              ▼
          【我们的 onSetMediaItems 回调】
              ▼
          MediaUtils.setMediaItemsWithStartIndexAndPosition(player, result)  (MediaUtils.java:194-213)
              └─ player.setMediaItems(items, startIndex, startPositionMs)
                  └─ 随后 Media3 自动调用 player.prepare() + player.play()
```

### 4.2 三条铁律

**铁律 1：`onSetMediaItems` 绝不能返回 `null`。**

```java
// MediaSessionImpl.java:689-696
return checkNotNull(
    callback.onSetMediaItems(instance, ..., mediaItems, startIndex, startPositionMs),
    "Callback.onSetMediaItems must return a non-null future");
```

返回 `null` 会立刻抛 NPE。（`onAddMediaItems` 同理，`MediaSessionImpl.java:681-684`。）

**铁律 2：返回的 items 会被 Media3 用于设置 player，因此本方法内不得再动 player。**

```java
// MediaUtils.java:194-213（节选）
public static void setMediaItemsWithStartIndexAndPosition(Player player, MediaItemsWithStartPosition m) {
  if (m.startIndex == C.INDEX_UNSET) {
    player.setMediaItems(m.mediaItems, /* resetPosition= */ true);
  } else {
    player.setMediaItems(m.mediaItems, m.startIndex, m.startPositionMs);
  }
}
```

→ **如果我们在 `onSetMediaItems` 里又调用 `playerManager.playQueue()`（其内部会 `setMediaItems`），就会重复设置两次。**
→ 正确做法：本方法内**只同步 `PlayerManager` 的状态镜像**，player 交给 Media3。

**铁律 3：覆写 `onSetMediaItems` 后，它成为所有点歌路径的统一入口。**

官方 javadoc（`MediaSession.java:1354`）：*"This method will be called, **unless `Callback#onSetMediaItems` is overridden**, in response to the following `MediaControllerCompat` methods: `prepareFromUri` / `playFromUri` / `prepareFromMediaId` / `playFromMediaId` / `prepareFromSearch` / `playFromSearch` / `addQueueItem`"*。

→ 覆写后，legacy Media1 路径（Android Auto 在部分场景走这条）也会**汇聚到 `onSetMediaItems`**，便于统一拦截。

**附带确认**：Media3 会在 items 解析完成后**自动调用 `prepare()` 和 `play()`**（javadoc `MediaSession.java:1369-1371`），**无需我们手动调用**。

### 4.3 `onAddMediaItems` 的默认陷阱（A-14 的成因）

官方 javadoc（`MediaSession.java:1338-1341`）：

> *"By default, **if and only if each of the provided media items has a set `MediaItem.LocalConfiguration`** (for example, a URI), then the callback returns the list unaltered. **Otherwise, the default implementation returns an `UnsupportedOperationException`.**"*

**这意味着**：只要浏览树返回的 item 没有 URI，点歌就会抛 `UnsupportedOperationException`。而 `MediaLibraryTree` 的 `findInQueue()`（A-14）恰好没有 `setUri()`。

**结论**：`onAddMediaItems` **必须覆写**——它同时是 URI 解析入口和防崩保险。

### 4.4 双真相源冲突（本方案最大技术风险）

| 真相源 | 载体 | 位置 |
|---|---|---|
| **PlayerManager**（项目约定） | `_playerState` 的 T3 三元组 `queue` / `currentIndex` / `currentSong` | `PlayerManager.kt` |
| **Media3 MediaSession** | 内部持有 `Player`，ExoPlayer 自己有 playlist | `PlaybackService.kt:282` |

**冲突**：车机点歌 → Media3 用 `onSetMediaItems` 的返回值设置 ExoPlayer playlist。若我们不做处理，`_playerState` 完全不知道队列变了。

**为什么不会自愈**：`PlayerManager.kt:112-129` 的 1000ms 轮询**只更新 `_progress` / `_duration`**：

```kotlin
private val progressUpdateRunnable = object : Runnable {
    override fun run() {
        if (!seekPending) { _progress.value = p.currentPosition }
        val dur = p.duration
        if (dur > 0) _duration.value = dur
        maybeTriggerCrossfade(p, dur)
        progressHandler.postDelayed(this, 1000)
    }
}
```

它**不读也不写 `queue` / `currentIndex` / `currentSong`** → 外部改动 playlist 后 `_playerState` 会一直错下去。

**后果**：UI 显示的歌与实际播放的歌不一致、通知栏曲目错乱、切歌跳到错误位置——即项目记忆里反复强调的「队列与当前歌不一致」类缺陷。

**正确解法**：在 `onSetMediaItems` 中调用一个**只更新状态镜像、不碰 player** 的新方法 `PlayerManager.syncQueueFromExternal()`（见 §5.4），返回解析后的 items 让 Media3 去设置 player。

### 4.5 URI 解析策略

**各来源的 URI 可得性**（已核对代码）：

| 来源 | 浏览时 `Song.streamUrl` | 解析成本 |
|---|---|---|
| **NAS**（Jellyfin 等） | ✅ 已填充（`JellyfinAdapter.kt:1266` `streamUrl = getStreamUrl(id)`） | 零成本 |
| **本地音乐** | ✅ 已是 `content://` / `file://` | 零成本 |
| **网络音乐**（Meting 等） | ❌ 按设计为 `null`，播放时解析 | 需 HTTP 请求 |
| **百度网盘** | ❌ 需 `BaiduNetdiskService.resolvePlayUrl()` | 需 HTTP 请求 |

**设计**：

1. **浏览树中只放稳定的 `mediaId`，不放 URL**（`Song.kt` 注释明确：网络歌曲 streamUrl "不持久化"；NAS 流地址含 token 可能过期）。
2. **URI 解析统一放在 `onAddMediaItems`**（Media3 官方设计的解析入口，返回 `ListenableFuture` 天然支持异步）。
3. **解析结果直接用于播放**，不依赖 `onNeedResolveStreamUrl` 回调——**这是 A-13 的主要修复手段**（详见 §5.8）。

---

## 五、详细设计

### 5.1 文件改动清单

| # | 文件 | 改动类型 | 内容 |
|---|---|---|---|
| 1 | `app/src/main/res/xml/automotive_app_desc.xml` | **新增** | 声明媒体应用（§3.2） |
| 2 | `app/src/main/AndroidManifest.xml` | 修改 | 补 meta-data（A-2）+ 补 action（A-3）；可选 attribution icon |
| 3 | `app/src/main/java/.../player/PlaybackService.kt` | 修改 | 实现 `onSetMediaItems` / `onAddMediaItems` / `onConnect`；异步化 `onGetChildren`；去分页；读 root hints |
| 4 | `app/src/main/java/.../player/PlayerManager.kt` | 修改 | 新增 `syncQueueFromExternal()`；`buildMediaItem` 改 `internal` |
| 5 | `app/src/main/java/.../player/MediaLibraryTree.kt` | **重写** | 结构化 ID + 多级节点；修 A-14 |
| 6 | `app/src/main/java/.../player/BrowseCache.kt` | **新增** | `mediaId → Song` 映射缓存（供 URI 解析与状态同步用） |
| 7 | `app/src/main/java/.../ui/viewmodel/MainViewModel.kt` | 修改 | A-13：`onNeedResolveStreamUrl` 降级为 UI 提示（§5.8） |
| 8 | `docs/phone-media-display-plan.md` | 修改 | 更正 §5.1（§2.3） |

### 5.2 Manifest 改动（精确）

```xml
<application
    android:name=".NasMusicApp"
    ... >

    <!-- A-2 新增：声明支持 Android Auto -->
    <meta-data
        android:name="com.google.android.gms.car.application"
        android:resource="@xml/automotive_app_desc" />

    <!-- 可选：媒体卡片图标（单色矢量） -->
    <meta-data
        android:name="androidx.car.app.TintableAttributionIcon"
        android:resource="@drawable/ic_car_attribution" />

    <activity ... />

    <service
        android:name=".player.PlaybackService"
        android:exported="true"
        android:foregroundServiceType="mediaPlayback">
        <intent-filter>
            <action android:name="androidx.media3.session.MediaLibraryService" />
            <!-- A-3 新增：平台 MediaBrowser 兼容层 -->
            <action android:name="android.media.browse.MediaBrowserService" />
        </intent-filter>
    </service>
</application>
```

> 注意：`exported="true"` 保持不动（跨进程绑定必需）。lint 可能提示"服务已导出但未设 `android:permission`"，官方明确说**可安全忽略**，因为包验证提供了更细的控制（§5.7）。

### 5.3 PlaybackService 代码骨架

```kotlin
// ============ 常量 ============
private companion object {
    const val DEFAULT_ROOT_LIMIT = 4
    const val RESOLVE_TIMEOUT_MS = 5_000L
}

// ============ 浏览：读 root hints（A-8） ============
override fun onGetLibraryRoot(
    session: MediaLibrarySession,
    controller: MediaSession.ControllerInfo,
    params: MediaLibraryService.LibraryParams?
): ListenableFuture<LibraryResult<MediaItem>> {
    // 必须立即返回，不得在此做网络 IO（§3.5）
    // ⚠️ 常量必须用 media3 的别名，不能用 androidx.media.utils.*（compile 期不可见，见 §3.3 更正块）
    val limit = params?.extras?.getInt(
        MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, DEFAULT_ROOT_LIMIT
    ) ?: DEFAULT_ROOT_LIMIT
    return Futures.immediateFuture(
        LibraryResult.ofItem(mediaLibraryTree.getLibraryRoot(limit), params)
    )
}

// ============ 浏览：异步化 + 去分页（A-6 / A-7） ============
override fun onGetChildren(
    session: MediaLibrarySession,
    controller: MediaSession.ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: MediaLibraryService.LibraryParams?
): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    // 注意：不读 page/pageSize —— Android Auto 不支持分页（§3.4）
    val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
    serviceScope.launch {
        try {
            val children = withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
                mediaLibraryTree.loadChildren(parentId)      // suspend，可做网络 IO
            }.orEmpty()
            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
        } catch (e: Throwable) {
            AppLog.w("PlaybackService", "onGetChildren($parentId) failed", e)
            // 超时/失败返回空列表，避免车机端一直转圈
            future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
        }
    }
    return future
}

// ============ 播放入口 1：URI 解析器（A-4） ============
/**
 * 把浏览树来的 MediaItem（只有 mediaId、没有 URI）解析为可播放项。
 * 这是 Media3 官方设计的 URI 解析入口（见 MediaSession.Callback#onAddMediaItems javadoc）。
 *
 * ⚠️ 必须覆写：默认实现在 item 缺 URI 时抛 UnsupportedOperationException（§4.3）。
 * ⚠️ 返回值不能为 null（§4.2 铁律 1）。
 */
override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: MutableList<MediaItem>   // Kotlin 对 Java List 的平台类型映射，以 IDE 补全为准
): ListenableFuture<MutableList<MediaItem>> = resolveItems(mediaItems)

// ============ 播放入口 2：点歌统一入口（A-4） ============
/**
 * 车机点歌入口。三条铁律见 §4.2：
 *  1. 返回值不能为 null（否则 NPE）
 *  2. 返回值会被 Media3 用于 player.setMediaItems()，故此处**不得再动 player**
 *  3. 覆写后它成为所有点歌路径的统一入口
 */
override fun onSetMediaItems(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: MutableList<MediaItem>,
    startIndex: Int,
    startPositionMs: Long
): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    // 1) 状态镜像回流（不碰 player）—— 解决 §4.4 双真相源冲突
    val songs = mediaItems.mapNotNull { browseCache.songOf(it.mediaId) }
    if (songs.isNotEmpty()) {
        playerManager.syncQueueFromExternal(songs, startIndex)
    }
    // 2) 解析 URI 后返回，由 Media3 设置 player 并自动 prepare + play
    return Futures.transform(
        resolveItems(mediaItems),
        { resolved ->
            MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
        },
        MoreExecutors.directExecutor()
    )
}

// ============ 共用的 URI 解析 ============
private fun resolveItems(items: List<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
    val future = SettableFuture.create<MutableList<MediaItem>>()
    serviceScope.launch {
        try {
            val resolved = items.map { item ->
                if (item.localConfiguration != null) return@map item   // 已有 URI，跳过
                val song = browseCache.songOf(item.mediaId) ?: return@map item
                val url = song.streamUrl?.takeIf { it.isNotBlank() }
                    ?: withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                        networkMusicManager.resolvePlayUrl(song)
                    }
                if (url.isNullOrBlank()) {
                    AppLog.w("PlaybackService", "resolve failed: ${song.title}")
                    item                        // 保持原样，交给 onNeedResolveStreamUrl 兜底
                } else {
                    playerManager.buildPlayableItem(song, url)   // 与项目其他播放路径一致
                }
            }.toMutableList()
            future.set(resolved)
        } catch (e: Throwable) {
            future.setException(e)
        }
    }
    return future
}

// ============ 包验证（A-11，方案见 §5.7） ============
override fun onConnect(
    session: MediaSession,
    controller: MediaSession.ControllerInfo
): MediaSession.ConnectionResult {
    if (!isTrustedCaller(session, controller)) {
        AppLog.w("PlaybackService", "reject controller: ${controller.packageName}")
        return MediaSession.ConnectionResult.reject()
    }
    return super.onConnect(session, controller)
}
```

> ⚠️ **签名提示**：Media3 是 Java 库，`List<MediaItem>` 在 Kotlin 中是平台类型。若 IDE 报"override 签名不匹配"，把 `MutableList<MediaItem>` 改为 `List<MediaItem>`（返回类型同理）。**以 IDE 自动补全结果为准。**

### 5.4 PlayerManager 新增 API

```kotlin
/**
 * Android Auto：外部控制器（Media3）设置了播放列表后，仅同步状态镜像，**不触碰 player**。
 *
 * 为什么不能直接调 playQueue()：Media3 会用 onSetMediaItems 的返回值
 * 自行调用 player.setMediaItems()（MediaUtils.setMediaItemsWithStartIndexAndPosition），
 * 此处再调 playQueue() 会造成重复设置（§4.2 铁律 2）。
 *
 * T3 三元组仍按项目约定在同一次 update 中发布。
 */
fun syncQueueFromExternal(songs: List<Song>, startIndex: Int) {
    if (songs.isEmpty()) return
    val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
    _playerState.update {
        it.copy(queue = songs, currentIndex = safeIndex, currentSong = songs.getOrNull(safeIndex))
    }
    songs.getOrNull(safeIndex)?.let { if (it.durationMs > 0) _duration.value = it.durationMs }
}

/** 供 PlaybackService 构造可播放 MediaItem（原 buildMediaItem 改为 internal） */
internal fun buildPlayableItem(song: Song, streamUrl: String): MediaItem =
    buildMediaItem(song, streamUrl)
```

改动点：`buildMediaItem` 由 `private`（`PlayerManager.kt:494`）改为 `internal`，或新增上述 `internal` 包装。

> **〔2026-09-17 实施期补充〕** 实际落地时选了「直接把 `buildMediaItem` 改 `internal`」这条，
> 未再加 `buildPlayableItem` 包装（少一层无意义转发）。另外新增了两个本方案初稿没有的方法，
> 其中 `replayAt` 有一个**容易踩的 ExoPlayer API 陷阱**，务必记牢：
>
> ```kotlin
> fun updateStreamUrl(index: Int, url: String)   // 回写队列中某条的 streamUrl，不动 currentIndex/currentSong
>
> fun replayAt(index: Int) {
>     // ⚠️ 不能写 setMediaItem(item, index) —— ExoPlayer 的
>     //    setMediaItem(MediaItem, long) 第二个参数是「起始播放位置(ms)」，不是索引！
>     //    重载只有 (MediaItem, long) 与 (MediaItem, boolean)，传 Int 直接编译失败；
>     //    就算强转成 Long 也只是「从第 N 毫秒开始播」，语义完全错。
>     //    正确做法：replaceMediaItem 原地换 + seekTo 定位。
>     if (index < p.mediaItemCount) p.replaceMediaItem(index, buildMediaItem(song, url))
>     else p.setMediaItems(queue.map { buildMediaItem(it, it.streamUrl ?: "") })
>     p.seekTo(index, 0L)
>     p.prepare(); p.play()
> }
> ```
>
> 已用 `javap` 核对 media3 1.2.1 的 `Player`：`replaceMediaItem(int, MediaItem)`、
> `seekTo(int, long)`、`getMediaItemCount()`、`setMediaItems(List)` 均存在。
> `else` 分支是兜底——若 Media3 侧 `setMediaItems` 失败导致播放器未装载队列，`replaceMediaItem` 会越界。

### 5.5 `BrowseCache`：mediaId → Song 映射

`onSetMediaItems` / `onAddMediaItems` 需要在**回调线程**把 `mediaId` 映射回 `Song`，而树加载是异步的。因此需要一层缓存：

```kotlin
/**
 * 浏览树节点缓存：mediaId → Song。
 *
 * 职责：
 * - 树加载（loadChildren）时写入
 * - 播放入口（onSetMediaItems / onAddMediaItems）读取，避免在回调里做网络 IO
 *
 * 容量与过期：LRU 2000 条 + 5 分钟软过期即可（车机浏览是"点一下加载一次"的模式）。
 */
class BrowseCache(private val maxSize: Int = 2000) {
    private val map = object : LinkedHashMap<String, Song>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?) =
            size > maxSize
    }
    fun put(song: Song) = synchronized(map) { map[song.id] = song }
    fun putAll(songs: List<Song>) = synchronized(map) { songs.forEach { map[it.id] = it } }
    fun songOf(mediaId: String): Song? = synchronized(map) { map[mediaId] }
}
```

> 若 `mediaId` 采用 §5.6 的 `song/{songId}` 形式，读取时需剥掉前缀。

### 5.6 内容树设计

#### 根菜单：固定 4 项

**不做动态增减**（原因见 §6.2），固定为：

| # | 标签 | 图标建议 | 数据来源 | 依赖内网 |
|---|---|---|---|---|
| 1 | **当前播放** | 播放列表 | `PlayerManager.getQueueSnapshot()` | 否 |
| 2 | **离线下载** | 下载 | 本地已下载歌曲 | 否 |
| 3 | **收藏** | 星标 | 网络音乐收藏缓存（LRU 500） | 否 |
| 4 | **歌单** | 列表 | `BackendAdapter.getPlaylists()` | **是**（不可达时返回空列表） |

> 前 3 项**完全不依赖家庭内网**，保证车机场景下永远有内容可播（§6.1）。

#### 结构化 `mediaId` 约定

```
root
queue                              → 当前播放
download                           → 离线下载
fav                                → 收藏
pl                                 → 歌单列表
pl/{playlistId}                    → 某歌单内歌曲
artist                             → 艺人列表
artist/{artistId}                  → 某艺人歌曲
album                              → 专辑列表
album/{albumId}                    → 某专辑歌曲
song/{songId}                      → 可播放叶子
```

叶子节点 `song/{songId}` 必须：

- `setMediaId("song/${song.id}")`
- `setIsPlayable(true)` + `setIsBrowsable(false)`
- 携带 `title` / `artist` / `albumTitle` / `artworkUri`
- **不设 `setUri()`**（URI 由 §4.5 的解析入口负责）

#### 可用的数据接口（均已存在）

| 数据 | 接口 |
|---|---|
| 队列快照 | `PlayerManager.getQueueSnapshot()` — `PlayerManager.kt:206` |
| 歌单 | `BackendAdapter.getPlaylists()` — `BackendAdapter.kt:169` |
| 艺人 | `BackendAdapter.getArtists()` — `BackendAdapter.kt:96` |
| 专辑 | `BackendAdapter.getAlbums()` — `BackendAdapter.kt:86` |
| 歌曲分页 | `BackendAdapter.getSongs(limit, offset)` — `BackendAdapter.kt:110` |
| 流派 / 年代 | `getSongsByGenre()` / `getSongsByYearRange()` — `BackendAdapter.kt:191-192` |

### 5.7 包验证（A-11）

**关键发现**：Media3 **已内置**车机控制器判断，无需自己维护包名白名单。

```java
// MediaSessionImpl.java:399-415
public boolean isAutomotiveController(ControllerInfo c) {
  return c.getControllerVersion() == ControllerInfo.LEGACY_CONTROLLER_VERSION
      && (c.getPackageName().equals(ANDROID_AUTOMOTIVE_MEDIA_PACKAGE_NAME)   // "com.android.car.media"
       || c.getPackageName().equals(ANDROID_AUTOMOTIVE_LAUNCHER_PACKAGE_NAME));
}
public boolean isAutoCompanionController(ControllerInfo c) {
  return c.getControllerVersion() == ControllerInfo.LEGACY_CONTROLLER_VERSION
      && c.getPackageName().equals(ANDROID_AUTO_PACKAGE_NAME);              // "com.google.android.projection.gearhead"
}
```

> ⚠️ javadoc 明确标注 **"This is not a security validation"**（因为它只比包名、不校验签名）。因此它适合做**功能判断**，做安全校验时需叠加签名比对。

**推荐实现**：

```kotlin
private fun isTrustedCaller(
    session: MediaSession,
    controller: MediaSession.ControllerInfo
): Boolean {
    // 调试期全放行，避免白名单不全导致 DHU 连不上
    if (BuildConfig.DEBUG) return true

    val pkg = controller.packageName
    return controller.uid == Process.SYSTEM_UID                     // 系统 / SystemUI
        || session.isAutomotiveController(controller)               // AAOS（Media3 内置包名判断）
        || session.isAutoCompanionController(controller)            // Android Auto
        || pkg == packageName                                       // 本应用自身
        || pkg == "com.google.android.googlequicksearchbox"         // Google 助理（手机）
        || pkg == "com.google.android.carassistant"                 // Gemini / 助理（AAOS）
}
```

**助理包名来源**：官方文档 [media/implement/assistant](https://developer.android.com/media/implement/assistant) 给出的两个包名与签名指纹。**若后续要做签名级校验**，该页提供了 debug/release 两组证书 SHA-256 指纹，可对照实现。

> 实施建议：**先按上面的宽松白名单上线**，并在 `isTrustedCaller` 拒绝分支打日志。真车/DHU 跑通后再根据日志收紧——避免因白名单不全出现"莫名其妙连不上"。

### 5.8 A-13 修复：streamUrl 解析下沉

**目标**：让网络歌曲在**没有 UI**（`MainActivity` 未启动）时也能播放。

**两层设计**：

**第一层（主路径，覆盖 Android Auto）**：§5.3 的 `resolveItems()` 在 `onAddMediaItems` / `onSetMediaItems` 里直接解析。此路径**完全在 `PlaybackService` 内**，不依赖任何 ViewModel。

**第二层（兜底）**：`onNeedResolveStreamUrl` 保留，但把**最小可用实现**放在 `PlaybackService`，使无 UI 时也能解析：

```kotlin
// PlaybackService.onCreate() 中
playerManager.onNeedResolveStreamUrl = { index ->
    // 无 UI 依赖的兜底解析：解析成功则回写并重播
    serviceScope.launch {
        val song = playerManager.getQueueSnapshot().getOrNull(index) ?: return@launch
        if (!song.streamUrl.isNullOrBlank()) return@launch
        val url = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            networkMusicManager.resolvePlayUrl(song)
        } ?: return@launch
        playerManager.updateStreamUrl(index, url)   // 回写 queue 中的 Song
    }
}
```

对应 `PlayerManager` 需新增：

```kotlin
/** 回写队列中某首歌曲的 streamUrl（A-13 兜底路径用） */
fun updateStreamUrl(index: Int, url: String) {
    val q = _playerState.value.queue.toMutableList()
    val s = q.getOrNull(index) ?: return
    q[index] = s.copy(streamUrl = url)
    _playerState.update { it.copy(queue = q) }
}
```

**`MainViewModel.kt:790` 的改动**：原注册会被上面的实现覆盖，因此改为**在解析完成后做 UI 提示**（可选增强），不再承担核心解析职责：

```kotlin
// MainViewModel.kt —— 由"核心解析"降级为"UI 提示"
// 解析本身已由 PlaybackService 内建（A-13），此处仅做用户可见反馈
playerManager.onStreamUrlResolveFailed = { song ->
    showMessage?.invoke(getApplication<Application>()
        .getString(R.string.resolve_url_auto_skip_with_title, song.title))
}
```

> `PlayerViewModel.resolveAndPlayByIndex`（`PlayerViewModel.kt:274`）中现有的**防竞态代数（generation）+ 重试 + 失败跳曲**逻辑是有效的，建议**下沉到 `PlayerManager`** 后由 UI 与服务共用，避免两套实现漂移。若本次不做下沉，则至少保证 `PlaybackService` 的兜底实现不与它冲突（先判 `streamUrl` 是否已存在）。

---

## 六、现实约束：车机上 NAS 可达吗？

### 6.1 问题

Android Auto 场景下应用运行在**手机上**。开车时：

- 手机通常使用**移动数据**，**不在家庭局域网内**
- 而 NAS 后端地址（Jellyfin / Navidrome）**通常是局域网 IP**（如 `192.168.x.x`）

**结果**：车机上打开「歌单」，请求 NAS → **超时**。用户看到空列表或长时间转圈。

这不是 Android Auto 适配的技术问题，而是**网络拓扑问题**——适配做完了，NAS 内容在车上依然不可用。

### 6.2 分源策略（已定稿）

按「车机场景可用性」分级：

| 内容源 | 车机可用性 | 说明 |
|---|---|---|
| **离线下载** | ✅ 完全可用 | 本地文件，无网络依赖 |
| **本地音乐** | ✅ 完全可用 | MediaStore / 本地文件 |
| **网络音乐**（Meting） | ✅ 可用 | 走公网，与内网无关 |
| **百度网盘** | ✅ 可用 | 走公网 |
| **收藏** | ✅ 可用 | 本地 LRU 缓存 |
| **NAS（Jellyfin/Navidrome）** | ⚠️ **仅当手机能访问后端时** | 需外网可达 |

**定稿决策**：

- 根菜单**固定 4 项**，不做动态增减。
- 第 4 项「歌单」在 NAS 不可达时**优雅降级**（`onGetChildren` 超时后返回空列表），而不是从根菜单消失。
- **理由**：① 标签页数量动态变化会让用户体验不稳定；② 根菜单增减需要可达性探测，而探测本身是网络请求，与 §3.5"`onGetLibraryRoot` 必须快速返回"冲突；③ 固定 4 项也正好贴合 root hints 默认上限。
- 可达性判断复用 `BackendRegistry.isConnected()`（`BackendRegistry.kt:167`）做**快速前置判断**，实际可用性由 `onGetChildren` 的超时兜底。

> `isConnected()` 只表示"适配器已在内存中初始化"，**不代表网络可达**（开车离开家庭网络后适配器仍在内存里，但请求会超时）。因此它只能用于"没配置后端时直接跳过"，真正的可用性以请求结果为准。

### 6.3 内网/外网双地址：本方案不做

**现状**：`AppPreferences.serverConfig`（`AppPreferences.kt:391`）只维护**单个**服务器地址。

**决策：本次不做双地址**。理由：

- 需要新增 `ServerConfig` 字段 + 地址优选/切换逻辑 + 可达性探测，工作量与回归风险都超出 Android Auto 适配的范围
- 车机场景的主要需求（听歌）已可由「离线下载 + 收藏 + 网络音乐」覆盖
- 强行引入会让本方案的验证面显著扩大

**未来若要做**，改造点为：

1. `ServerConfig` 增加 `externalUrl` 字段 + `AppPreferences` 读写
2. `BackendRegistry.initialize()` 时按可达性优选地址（短超时探测）
3. 设置页增加"外网地址"输入项

---

## 七、实施计划

### 阶段 1：最小可用闭环（P0 + P1 关键项）

**目标**：Android Auto 能发现应用、能浏览「当前播放」、能点歌播放、状态一致。

| 步骤 | 内容 | 文件 |
|---|---|---|
| 1.1 | 新建 `automotive_app_desc.xml` | 新增 |
| 1.2 | 补 application meta-data | `AndroidManifest.xml` |
| 1.3 | 补 `android.media.browse.MediaBrowserService` action | `AndroidManifest.xml` |
| 1.4 | 新增 `syncQueueFromExternal()` / `updateStreamUrl()`；`buildMediaItem` 改 `internal` | `PlayerManager.kt` |
| 1.5 | 实现 `onSetMediaItems` / `onAddMediaItems` / `resolveItems` | `PlaybackService.kt` |
| 1.6 | 新增 `BrowseCache` | 新增 |
| 1.7 | 修 A-14（`findInQueue` 补 `setUri`，或统一由 `resolveItems` 覆盖） | `MediaLibraryTree.kt` |
| 1.8 | 去分页 + `onGetChildren` 异步化 | `PlaybackService.kt` |
| 1.9 | A-13 兜底解析 | `PlaybackService.kt` + `MainViewModel.kt` |

**验收**：DHU 里能看到应用 → 进入 → 看到「当前播放」→ 点歌出声 → 方向盘按键可控 → **手机端队列视图与车机一致**。

> **强烈建议在此处停下来做一次完整的 DHU 端到端验证**，再进入阶段 2——避免在未验证的地基上继续堆功能（这正是 2026-09-07 那次审查的教训）。

### 阶段 2：内容树扩展（P1）

| 步骤 | 内容 |
|---|---|
| 2.1 | `MediaLibraryTree` 重写为结构化 ID + 多级节点（§5.6） |
| 2.2 | 接入离线下载 / 收藏 / 网络音乐源 |
| 2.3 | 接入 NAS 源（歌单 / 艺人 / 专辑），带短期缓存 + 超时 |
| 2.4 | 读取 root hints 并按 limit 裁剪（§3.3） |
| 2.5 | 各标签项配单色矢量图标 |

### 阶段 3：搜索与语音（P2）

> **〔2026-09-17 实施期补充：语音搜索的完整机制已探明〕**
>
> 阶段 1 落地时新增了 `automotive_app_desc`，**立刻触发了一条新的 lint error**：
> `MissingIntentFilterForMediaSearch`（`AndroidManifest.xml` 的 `<application>` 上，
> 要求注册 `android.media.action.MEDIA_PLAY_FROM_SEARCH`）。本轮**有意暂不声明**该 intent-filter
> 并加 `tools:ignore` 抑制，理由与后续正确做法如下（均经 `media3-session-1.2.1` 源码核实）：
>
> **① Media3 没有 `MediaSession.Callback.onPlayFromSearch`。**
> `javap` 实测 `MediaSession$Callback` 共 11 个 `default` 方法，与搜索相关的**一个都没有**
> （只有 `onSetMediaItems` / `onAddMediaItems` / `onPlaybackResumption` / `onPlayerCommandRequest` 等）。
> 所以「实现 `onPlayFromSearch`」这条老文档建议对 Media3 **不成立**。
>
> **② 语音搜索最终走的是 `onSetMediaItems`。**
> `MediaSessionLegacyStub.java:395` 的 `onPlayFromSearch(query, extras)` →
> `handleMediaRequest(createMediaItemForMediaRequest(null, null, query, extras), play=true)`
> → `MediaSessionLegacyStub.java:811-817` 调 `sessionImpl.onSetMediaItemsOnHandler(
> controller, ImmutableList.of(mediaItem), C.INDEX_UNSET, C.TIME_UNSET)`。
> 而 `createMediaItemForMediaRequest`（同文件 947-961）构造出的 `MediaItem` 是：
> **`mediaId = ""`（`MediaItem.DEFAULT_MEDIA_ID`）、`requestMetadata.searchQuery = query`、无 URI**。
>
> **③ 因此 3.x 的正确实现位置是 `PlaybackService.onSetMediaItems` 的入口分支**：
> ```kotlin
> val q = mediaItems.firstOrNull()?.requestMetadata?.searchQuery?.toString()
> if (!q.isNullOrBlank()) { /* 搜索 → 构造 items → 返回 MediaItemsWithStartPosition(items, 0, 0L) */ }
> ```
> 搜索可复用 `NetworkMusicManager.search(keyword)` 与 `BackendAdapter.searchSongs(query)`。
> ⚠️ 两个坑：**(a)** 此路径的 `startIndex` / `startPositionMs` 是 `C.INDEX_UNSET`(-1) /
> `C.TIME_UNSET`，**不能**原样透传给 `MediaItemsWithStartPosition`，要归一成 `0` / `0L`；
> **(b)** 只有在这一分支真正实现后，才应把 `MEDIA_PLAY_FROM_SEARCH` 的 intent-filter 加到
> `PlaybackService` 上并移除 `tools:ignore`——**顺序反了会得到「声明了却搜不动」的静默失效**。

| 步骤 | 内容 |
|---|---|
| 3.0 | **（新增）** 在 `onSetMediaItems` 中处理 `requestMetadata.searchQuery`；随后补 `MEDIA_PLAY_FROM_SEARCH` intent-filter 并移除 `tools:ignore="MissingIntentFilterForMediaSearch"` |
| 3.1 | 实现 `onSearch` / `onGetSearchResult`，复用现有搜索（含 TinyPinyin 拼音搜索） |
| 3.2 | 实现 `onPlaybackResumption`，支持车机连接后一键续播 |
| 3.3 | 验证 Google 助理「播放 XXX」链路 |

### 阶段 4：打磨（P3）

| 步骤 | 内容 |
|---|---|
| 4.1 | attribution icon（单色矢量） |
| 4.2 | 强调色定制（可选） |
| 4.3 | 包验证收紧（依据真实 `controller.packageName` 日志，§5.7） |
| 4.4 | 更正 `phone-media-display-plan.md` §5.1（§2.3） |

---

## 八、测试与验证

### 8.1 DHU 环境搭建

DHU 是**官方提供的车机模拟器**，运行在开发机上，**无需真车、无需上架 Google Play**。

```bash
# 1. Android Studio → SDK Manager → SDK Tools
#    勾选 "Android Auto Desktop Head Unit Emulator"
#    安装到 SDK_LOCATION/extras/google/auto/

# 2. 手机端：安装最新版 Android Auto，并开启其开发者模式
#    （Android Auto 设置 → 连点「版本」约 10 次）

# 3. Android Auto → 溢出菜单 → "Start head unit server"
#    通知栏出现前台服务提示即成功

# 4. 手机 USB 连接开发机，屏幕保持解锁

# 5. 端口转发
adb forward tcp:5277 tcp:5277

# 6. 启动 DHU（Windows）
cd "C:/Users/hxzha/AppData/Local/Android/Sdk/extras/google/auto"
./desktop-head-unit.exe
```

**说明**：

- 本项目 adb 不在 PATH，全路径为 `C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- 手机端 **Android 10+ 需先登录 Google Play 并更新 Android Auto 应用**（官方要求）
- 输入模式可选 `touch` / `rotary` / `hybrid`；车机以 rotary（旋钮）为主，**务必用 rotary 模式测一遍焦点导航**
- DHU 配置文件 `[general]` 段可加 `playbackstatus = true` 显示播放状态
- 可用 `keycode media_next` / `media_play_pause` 等命令模拟媒体键

### 8.2 验证清单

| # | 验证项 | 预期 | 阶段 |
|---|---|---|---|
| 1 | 车机启动器可见应用图标 | 图标 + 名称正常 | 1 |
| 2 | 进入后显示导航标签页 | ≤4 个标签，标签不截断 | 1/2 |
| 3 | 「当前播放」能列出队列 | 列表非空、有封面 | 1 |
| 4 | 点歌能播放 | 出声，且**手机 UI 显示的当前歌与车机一致** | 1 |
| 5 | 方向盘上一曲/下一曲 | 正常切歌，**队列不错位** | 1 |
| 6 | 播放/暂停 | 正常，通知栏同步 | 1 |
| 7 | **队列一致性回归** | 车机点歌后，手机端队列视图与车机一致（**§4.4 核心风险点**） | 1 |
| 8 | 网络歌曲点播 | 能解析并出声（**A-13 验证**：先杀掉 App 进程再操作车机） | 1 |
| 9 | NAS 断开时的表现 | 不卡死、不 ANR，显示空列表 | 2 |
| 10 | 大量歌曲节点 | 不被静默截断（§3.4 分页陷阱） | 2 |
| 11 | 语音「播放 XXX」 | 能搜到并播放 | 3 |
| 12 | 车机重连后续播 | 恢复上次内容 | 3 |

> **第 7 项是本方案的关键验证点**——双真相源冲突（§4.4）不会报错，只会"显示的歌和放的歌不一样"，必须专门验证。
> **第 8 项需先杀掉 App 进程**（`adb shell am force-stop com.nasmusic.tv`）再操作车机，才能真正验证 A-13 的修复效果。

### 8.3 真车侧载说明

**DHU 通过 ≠ 真车可用。** 两者发现机制不同：

- Android Auto 在真车上**默认只展示 Google Play 分发的应用**
- 本项目通过 GitHub Release 分发 APK，属侧载应用，需在 **Android Auto 开发者模式**中开启 **"Unknown sources"**（未知来源）后才可能出现在车机上
- 不同 Android Auto 版本该选项的位置与名称可能有差异，**以真车实测为准**

> ⚠️ 按项目既有约定：**上机操作（安装、启动）由用户本人执行**，Agent 不代劳。真车验证请由用户操作并反馈。

### 8.4 回归影响面

改动集中在 `PlaybackService` / `MediaLibraryTree` / `PlayerManager` / `AndroidManifest.xml`，**不触碰播放核心解码逻辑**。仍需回归：

- 手机端播放、通知栏、锁屏控件（`phone-media-display-plan.md` 覆盖的场景）
- **`PlayerManager` 的 T3 三元组一致性**（新增了 `syncQueueFromExternal`，需确认不破坏既有发布约定）
- 电视端（`minSdk 22`）不受影响，但 Manifest 改动需确认 lint 与构建通过
- 单测：`testDebugUnitTest`；`MediaLibraryTree` 的 ID 解析与 `BrowseCache` 适合加单测

**构建命令**（按项目约定）：

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat assembleDebug lintDebug --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
```

---

## 九、风险登记

> v2.0 已无"未决项"。下表为**已识别并有明确应对**的风险。

| 风险 | 等级 | 说明 | 应对 |
|---|---|---|---|
| **双真相源冲突** | **高** | §4.4，静默不一致，不报错 | 阶段 1 实现 `syncQueueFromExternal` + 专项验证（清单 #7） |
| **NAS 车机不可达** | **高** | §6.1，非技术问题，影响功能价值 | 根菜单前 3 项不依赖内网（§6.2）；NAS 项优雅降级 |
| **A-13 无 UI 时解析失败** | **高** | §5.8 | 双层设计：`resolveItems` 主路径 + Service 兜底 |
| **同步阻塞导致 ANR** | 中 | §5.3，接入 NAS 后必现 | 阶段 1 即异步化 + 超时保护 |
| **分页陷阱** | 中 | §3.4，静默截断难排查 | 阶段 1 移除伪分页 |
| **A-14 缺 URI 抛异常** | 中 | §4.3 | 阶段 1 覆写 `onAddMediaItems` + 修 `findInQueue` |
| **Kotlin/Java 签名不匹配** | 低 | `List` vs `MutableList` | 以 IDE 自动补全为准（§5.3 注） |
| **包验证过严** | 低 | 白名单不全导致连不上 | DEBUG 全放行 + 先宽松后收紧（§5.7） |
| **真车侧载限制** | 中 | §8.3，DHU 通过≠真车可见 | 需用户开启 Unknown sources 实测 |
| **Media3 版本** | 低 | 1.2.1 较旧（2023 年） | 本方案所有 API 已在 1.2.1 上核实可用；**不建议为 Android Auto 单独升级 Media3** |

---

## 十、附录

### 10.1 Media3 1.2.1 播放链路源码证据

以下均引自 `media3-session-1.2.1-sources.jar`（本机 Gradle 缓存）：

```java
// MediaSessionImpl.java:687-697 —— 返回值不可为 null
protected ListenableFuture<MediaItemsWithStartPosition> onSetMediaItemsOnHandler(
    ControllerInfo controller, List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
  return checkNotNull(
      callback.onSetMediaItems(instance, resolveControllerInfoForCallback(controller),
          mediaItems, startIndex, startPositionMs),
      "Callback.onSetMediaItems must return a non-null future");
}

// MediaSessionImpl.java:681-685 —— onAddMediaItems 同样
return checkNotNull(
    callback.onAddMediaItems(instance, resolveControllerInfoForCallback(controller), mediaItems),
    "Callback.onAddMediaItems must return a non-null future");

// MediaSession.java:1457-1468 —— 默认 onSetMediaItems 转发给 onAddMediaItems
default ListenableFuture<MediaItemsWithStartPosition> onSetMediaItems(...) {
  return Util.transformFutureAsync(
      onAddMediaItems(mediaSession, controller, mediaItems),
      (mediaItemList) -> Futures.immediateFuture(
          new MediaItemsWithStartPosition(mediaItemList, startIndex, startPositionMs)));
}

// MediaSessionStub.java:992 —— 返回值交给 MediaUtils 消费
MediaUtils::setMediaItemsWithStartIndexAndPosition

// MediaUtils.java:194-213 —— 返回值最终用于设置 player
public static void setMediaItemsWithStartIndexAndPosition(
    Player player, MediaSession.MediaItemsWithStartPosition m) {
  if (m.startIndex == C.INDEX_UNSET) {
    if (player.isCommandAvailable(COMMAND_CHANGE_MEDIA_ITEMS)) {
      player.setMediaItems(m.mediaItems, /* resetPosition= */ true);
    } else if (!m.mediaItems.isEmpty()) {
      player.setMediaItem(m.mediaItems.get(0), /* resetPosition= */ true);
    }
  } else if (player.isCommandAvailable(COMMAND_CHANGE_MEDIA_ITEMS)) {
    player.setMediaItems(m.mediaItems, m.startIndex, m.startPositionMs);
  } else if (!m.mediaItems.isEmpty()) {
    player.setMediaItem(m.mediaItems.get(0), m.startPositionMs);
  }
}

// MediaSessionImpl.java:399-415 —— 内置车机控制器判断（仅比包名，非安全校验）
public boolean isAutomotiveController(ControllerInfo c) {
  return c.getControllerVersion() == ControllerInfo.LEGACY_CONTROLLER_VERSION
      && (c.getPackageName().equals("com.android.car.media")
       || c.getPackageName().equals(ANDROID_AUTOMOTIVE_LAUNCHER_PACKAGE_NAME));
}
public boolean isAutoCompanionController(ControllerInfo c) {
  return c.getControllerVersion() == ControllerInfo.LEGACY_CONTROLLER_VERSION
      && c.getPackageName().equals("com.google.android.projection.gearhead");
}
```

### 10.2 关键 API 与常量

```kotlin
// ⚠️ 实施期更正：下面第 1 个常量请用 media3 的别名 androidx.media3.session.MediaConstants
//    .EXTRAS_KEY_ROOT_CHILDREN_LIMIT（字面量相同、compile 期可用）；
//    androidx.media.utils.MediaConstants 在本项目 compile classpath 上不可见（见 §3.3 更正块）。
// androidx.media.utils.MediaConstants（androidx.media:media:1.6.0，仅 runtime 传递引入）
MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT            // 默认 4
MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_SUPPORTED_FLAGS  // 默认 FLAG_BROWSABLE（本应用未设置，见 §3.3）

// Manifest meta-data 名
"com.google.android.gms.car.application"          // → @xml/automotive_app_desc
"androidx.car.app.TintableAttributionIcon"        // → 单色矢量图标
"com.google.android.gms.car.application.theme"    // → 强调色（可选）

// Service actions
"androidx.media3.session.MediaLibraryService"     // 已有
"android.media.browse.MediaBrowserService"        // ❌ 待补（A-3）

// 需放行的调用方包名
"com.google.android.projection.gearhead"          // Android Auto（Media3 内置判断）
"com.android.car.media"                           // AAOS（Media3 内置判断）
"com.google.android.googlequicksearchbox"         // Google 助理（手机）
"com.google.android.carassistant"                 // Gemini / 助理（AAOS）

// MediaItemsWithStartPosition 公开构造器
MediaItemsWithStartPosition(List<MediaItem> mediaItems, int startIndex, long startPositionMs)
```

### 10.3 参考资料

- [Media apps for cars overview](https://developer.android.com/training/cars/media)
- [Configure manifest files](https://developer.android.com/training/cars/media/configure-manifest)
- [Add support for Android Auto](https://developer.android.com/training/cars/media/auto)
- [Build your content hierarchy](https://developer.android.com/training/cars/media/create-media-browser/content-hierarchy)（根菜单约束、不支持分页）
- [Test with Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu)
- [Media3: Serve content](https://developer.android.com/media/media3/session/serve-content)
- [Google 助理连接所需的包名与签名](https://developer.android.com/media/implement/assistant)
- 项目内：`docs/phone-media-display-plan.md` §5.1、`docs/code-review-2026-09-07.md`（P0-9 / P0-10）

---

## 十一、一页速览

**已完成**：`MediaLibraryService` 基类、浏览回调、媒体树骨架、封面加载、服务导出、`playQueue()` 队列入口、依赖齐备（**无需新增任何依赖**）

**必须做（阶段 1）**：

1. 新建 `res/xml/automotive_app_desc.xml` ← **缺此则应用不可见**
2. Manifest 加 `com.google.android.gms.car.application` meta-data ← **缺此则应用不可见**
3. service intent-filter 加 `android.media.browse.MediaBrowserService` ← **缺此则找不到服务**
4. 实现 `onSetMediaItems`（**返回非 null + 只同步状态不碰 player**）+ `onAddMediaItems`
5. `PlayerManager` 新增 `syncQueueFromExternal()`
6. 移除伪分页 + `onGetChildren` 异步化
7. 修 A-13（解析下沉）/ A-14（`findInQueue` 缺 URI）

**三条铁律**（源码确认）：① 返回值不能为 null（会 NPE）② 返回值会被 Media3 用于设置 player，故不得重复设置 ③ 覆写后成为所有点歌路径的统一入口

**最大技术风险**：双真相源冲突（`PlayerManager._playerState` vs ExoPlayer playlist），静默不一致，靠 `syncQueueFromExternal` 解决

**最大现实风险**：开车时手机不在家庭局域网，NAS 内容在车机上不可用；靠根菜单前 3 项不依赖内网来兜底

**验证手段**：DHU（`adb forward tcp:5277 tcp:5277` + `desktop-head-unit.exe`），无需真车、无需上架

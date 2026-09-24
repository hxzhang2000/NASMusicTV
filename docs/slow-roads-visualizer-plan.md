# 公路漫游（Slow Roads WebView）频谱效果 —— 开发方案

> 目标：在**频谱效果库**里新增第 37 套效果「公路漫游」——效果层不是 Canvas 自绘，
> 而是一个**全屏 WebView**，加载 `https://slow-roads.pages.dev/`，用遥控器控制自动驾驶与场景切换。
>
> 状态：**待开工**（本文件为可开发级方案，未写实现代码；已按 2026-09-24 代码审阅修订）
> 基线：v2.37.0 ｜ 门禁 `testDebugUnitTest` **1177 例 / 115 类 / 0 失败**，`lintDebug` **0 Error / 279 Warning**
> （2026-09-24 实测复核：JUnit XML 汇总 115 类 / 1177 例 / 0 失败 / 0 错误 / 0 跳过，与基线一致；计数漂移先确认是不是本轮新增的）

### 文档版本跟踪

| 文档版本 | 日期 | 变更摘要 | 状态 |
|---|---|---|---|
| v1.0 | 2026-09-24 | 初稿：可开发级方案，基线门禁实测（1177 例 / 115 类 / 0F、lint 0E/279W） | 已完成编写 |
| v1.1 | 2026-09-24 | 按代码现状审阅修订：① F1 修 `VisualizerThemeTest` 断言行号错位（§二 #11、§6.2 M4、§十、T1.3——正确集合 7 行，含 `:89` off.size 与 `:90` on.size）；② F2 降级「手机滑动仍切效果」假设并补缓解与验收（§三、§5.2、§9.11、T3.3、T4.4、§12）；③ 补 PHOTO_WALL 粒子门控豁免事实与守护用例（§二 #10、T1.3）；④ minor：§三 NPE→编译不过、§5.2/§8 防抖口径（180L）、§9.4 largeHeap 行号 :72、G24 覆盖 isFocusableInTouchMode | 待开工 |

> ⛔ 本文件每次修订必须在此表**追加一行**；文档版本号只增不改。

---

## 一、目标与核心结论

### 1.1 需求边界（与最初那份「独立 App 方案」的差异）

最初那套方案写的是「做一个 Android TV App，里面放全屏 WebView」。本项目的实际情况是
**已经有一个电视/手机 App 和一套 36 套效果的频谱库**，所以目标调整为：**作为其中一个效果嵌进去**。

| 项 | 最初方案（独立 App） | 本方案（频谱效果之一） |
|---|---|---|
| 形态 | 独立 Activity + 全屏 WebView | `VisualizerTheme.SLOW_ROADS` 一个枚举项 |
| 进入方式 | 启动即加载 | 播放页进可视化 → 左右键/设置页切到该效果 |
| 全屏 | Activity 自己 `FLAG_FULLSCREEN` | 复用 `VisualizerStage` 已有的全屏覆盖层 |
| 常亮 | Activity `KEEP_SCREEN_ON` | 播放中本来就不息屏，无需额外处理 |
| 遥控映射 | `onKeyDown` 里硬写 | 舞台 `onPreviewKeyEvent` 里**按主题分支**处理 |
| 退出 | 返回键退出 App | 返回键退出可视化（复用既有三级 BACK） |
| 音乐 | 无 | **音乐同时在播**（音效冲突，见 §9.5） |

### 1.2 核心结论

| 问题 | 结论 |
|---|---|
| 游戏有官方接口吗 | **没有**。无 URL 参数、无 JS API，一切只能靠**模拟键盘事件** |
| 能塞进现有渲染器接口吗 | **不能**。`VisualizerRenderer.draw` 是 `DrawScope`（`VisualizerRenderer.kt:21`），WebView 是 View |
| 那怎么接 | **占位渲染器 + 舞台旁路层**（§4）：工厂照常返回渲染器（契约要求），画面由叠在 Canvas 之上的 WebView 承载 |
| 遥控能直接控制游戏吗 | 不能，需「翻译层」：遥控键 → `KeyEvent` / JS 合成 `KeyboardEvent` 双通道发给页面 |
| 能自动巡航吗 | 能，但**必须发一次 F 键**；且「页面加载完 ≠ 游戏就绪」，要探测重试（§7） |
| 能切地图吗 | 能，`E` / `Q`；`C` 切视角 |
| 我那台电视能跑吗 | ⚠️ **高风险** —— Android 5.1.1 的 WebView 可能根本跑不了 WebGL（§9.1），必须先做能力检测与降级 |

### 1.3 游戏控制键（外部可触发的全部入口）

| 功能 | 键 | 本方案绑定 |
|---|---|---|
| 自动驾驶 开/关 | `F` | 遥控器 OK |
| 下一场景 | `E` | 遥控器 ↑ |
| 上一场景 | `Q` | 遥控器 ↓ |
| 切换视角 | `C` | 遥控器 菜单键（可选，阶段 4） |

---

## 二、现状核实（逐条打开源码确认，含 `file:line`）

| # | 事实 | 证据 |
|---|---|---|
| 1 | 效果枚举 **36 项**，`displayName` 是**硬编码中文**（无 i18n） | `data/model/AppSettings.kt:104-155`；显示点 `ui/components/VisualizerStage.kt:360` |
| 2 | 枚举 KDoc 写「34 套」、照片墙注释写「第 36 个」——**计数不自洽**，实际 36 | `AppSettings.kt:100` / `:146` |
| 3 | 工厂是**穷举 `when`**，少一个分支编译不过 | `visualizer/VisualizerRendererFactory.kt:50-87` |
| 4 | 渲染器契约：`DrawScope.draw(frame, ctx)`，**禁在 draw 内分配** | `visualizer/VisualizerRenderer.kt:13-25` |
| 5 | `RendererSwapper.sync()` 对每个主题调 `factory(theme)`，返回值**必须非 null** | `visualizer/RendererSwapper.kt:84` |
| 6 | 舞台三层结构：背景 Box / Canvas 效果层 / 前景 Column | `ui/components/VisualizerStage.kt:259-407` |
| 7 | 舞台按键：`←`上一效果、`→`下一效果、`↑`**吞掉无动作**，其余放行 | `VisualizerStage.kt:227-237` |
| 8 | OK 键在 `Screen.NowPlaying` 下 = **播放/暂停** | `util/MediaKeyHandler.kt:43-55`；入口 `ui/MainActivity.kt:546-566` |
| 9 | 左右步进用的是**过滤后列表**（`selectable` + `quality.supports`） | `ui/viewmodel/VisualizerViewModel.kt:460-478`；工厂 `:95-97` |
| 10 | 画质门控：`BASIC` 全档可用；`ADV` 要求 `maxParticles > 0`（**LOW 档 = 0 ⇒ 不可见**）；`ULTRA` 要求 `allowFramebuffer`（**仅 HIGH**）。⚠️ **例外**：`PHOTO_WALL` 已被显式豁免粒子门控（`AppSettings.kt:229-230`，2026-09-23 用户裁决）——新增 `SLOW_ROADS` **不得**搭车豁免（LOW 档老设备跑 3D 的风险必须挡住） | `AppSettings.kt:225-232` |
| 11 | 测试有 **7 处硬计数断言**（`:51,:57,:58,:90,:96,:103` 断言 `36`，`:89` 断言 `off.size == 35`），新增枚举必挂 | `app/src/test/.../data/model/VisualizerThemeTest.kt:51,57,58,89,90,96,103` |
| 12 | 项目里**没有现成 WebView**；`MvPlaybackScreen` 的 `AndroidView` 是 ExoPlayer `PlayerView` | `ui/components/MvPlaybackScreen.kt:268-278` |
| 13 | `INTERNET` 权限已有；`usesCleartextTraffic=true` 已有 | `AndroidManifest.xml:4` / `:70` |
| 14 | `<application>` **未显式声明** `hardwareAccelerated`（靠默认 true） | `AndroidManifest.xml:62-74` |
| 15 | `proguard-rules.pro` 无任何 webkit 相关规则 | 实读确认 |

---

## 三、最容易搞错的「复用」（先读这一节再动手）

| 想当然的说法 | 事实 | 正确做法 |
|---|---|---|
| 「新增效果 = 写一个 Renderer 子类」 | WebView 是 View，进不了 `DrawScope` | 占位渲染器 + 舞台旁路叠加层（§4.2） |
| 「既然是占位渲染器，工厂就不用加分支了」 | 工厂返回类型**非空**、`when` 必须穷举——少分支**编译不过**；且 `RendererSwapper.sync` 会对每个主题调 `factory(theme)` 并 `onEnter` | 工厂**必须**加分支，返回 `SlowRoadsRenderer()` |
| 「效果名要同步 `strings.xml` / `values-en`」 | 36 个效果名全是枚举 `displayName` 硬编码中文，UI 直接读它 | 直接写中文，**不要**动 strings（与既有 36 套保持一致） |
| 「左右键切场景（原方案就这么写的）」 | 左右已被「切效果」占用，改了就切不动效果了 | 场景切换改用 **↑ / ↓** |
| 「OK 键是空的，拿来开自动驾驶」 | OK 在播放页 = 播放/暂停 | 必须**拦截**，并接受「该效果下 OK 不能暂停」的副作用 |
| 「`MvPlaybackScreen` 有现成 WebView 能抄」 | 那是 `PlayerView`（ExoPlayer），不是 WebView | 从零写宿主 |
| 「`onPageFinished` 后延时 3s 发 F 就行」 | 页面 finish ≠ 游戏就绪（地形/着色器生成可能十几秒） | 探测 canvas 就绪 + 多次重试（§7.2） |
| 「给 `Tier.ULTRA` 最保险」 | ULTRA 只在 HIGH 档可见；用户机器默认 MEDIUM ⇒ **根本看不到** | 用 `Tier.ADV`（MEDIUM/HIGH 可见，LOW 挡住最弱设备） |
| 「WebView 要抢焦点才能收键盘」 | WebView 一拿到焦点，Compose 的左右键就收不到了，效果都切不动 | WebView **设为不可聚焦**，键事件全部由舞台转发 |
| 「WebView 设为不可聚焦就不会挡手势」 | 不可聚焦只解决**键盘焦点**，不解决**触摸**：WebView 是可滚动页面，手机触摸命中 `AndroidView` 后被 WebView 消费，父级 `detectHorizontalDragGestures`（Main pass）收不到拖拽 | 触摸穿透需单独处理：舞台 Box 在 **Initial pass**（`awaitEachGesture` + `PointerEventPass.Initial`）抢先识别横向拖拽，或宿主层禁用 WebView 触摸（§9.11）；**T3.3 真机验证** |

---

## 四、架构设计

### 4.1 三个候选与选型

| 方案 | 做法 | 结论 |
|---|---|---|
| **A（选定）** 占位渲染器 + 舞台旁路层 | 枚举照常加；工厂返回空渲染器；`VisualizerStage` 内 `if (theme == SLOW_ROADS)` 叠一层 `AndroidView { WebView }` | ✅ 对现有 36 套效果**零改动**，生命周期/切换逻辑全部复用 |
| B 把 WebView 画进 Canvas | 用 `drawIntoCanvas` + `nativeCanvas` 间接绘制 | ❌ 做不到（WebView 不是位图源；`drawWebView` 类 API 不存在） |
| C 独立 Activity | 新增 `SlowRoadsActivity` | ❌ 破坏「效果」语义；切歌/退出/返回栈/沉浸模式要全部重做；且不再是「频谱效果」 |

### 4.2 分层（新增 ②b）

```
VisualizerStage (Box, fillMaxSize, 已夺焦)
│
├── ③ 前景层 Column ──────── 歌词 / 效果名 Toast / 歌曲信息 / 底部指示器   ← 不变
│
├── ②b WebView 层 ────────── 仅 theme == SLOW_ROADS 时挂载                ← 新增
│     └─ AndroidView { SlowRoadsWebViewHost.view }
│        · 不可聚焦（isFocusable = false）
│        · 失败/无 WebGL 时叠降级卡片（重试 / 切回上一个效果）
│
├── ② Canvas 效果层 ──────── SlowRoadsRenderer.draw 为空 ⇒ 全透明          ← 新增占位
├── ②' 旧效果淡出层 ──────── 交叉淡入期间（本效果恒硬切）                  ← 不变
└── ① 背景层 Box ─────────── palette.background                            ← 不变
```

⚠️ 前景层（歌词 / 歌曲信息 / 指示器）会**压在游戏画面上**——这是刻意的：
保持「音乐可视化」的一贯观感，用户仍能看到歌词与当前是第几个效果。

### 4.3 生命周期

| 时机 | 动作 |
|---|---|
| `theme` 变为 `SLOW_ROADS` | `LaunchedEffect(theme)` 内创建 host → `load()` |
| `theme` 变为其它 / 舞台 dispose | `host.destroy()`：`webView.stopLoading()` → `loadUrl("about:blank")` → `destroy()` → 置空引用 |
| 画质档变化 | 宿主**不重建**（WebView 与画质无关），仅占位渲染器走 `onEnter` |
| 退出可视化 | `DisposableEffect` onDispose 销毁，防 Activity 泄漏 |

⛔ WebView 必须用 **Activity Context** 创建（不能用 Application），否则主题/对话框异常；
`AndroidView` 的 `factory` 给的就是当前 Context，直接用即可。

---

## 五、遥控器键位映射

### 5.1 冲突分析

| 键 | 现状 | 证据 |
|---|---|---|
| `←` `→` | 切上/下一个效果 | `VisualizerStage.kt:230-231` |
| `↑` | **吞掉、无动作** | `VisualizerStage.kt:233` |
| `↓` | 未处理 → 继续向上传播 | — |
| OK(`DPAD_CENTER`/`ENTER`) | NowPlaying 下播放/暂停 | `MediaKeyHandler.kt:48-51` |
| BACK | 三级 BACK（退出可视化） | `MainActivity.kt` |

### 5.2 最终映射（**仅 `theme == SLOW_ROADS` 时生效**，其余主题行为一字不改）

| 遥控键 | Compose `Key` | 行为 | 发往页面的键 |
|---|---|---|---|
| OK | `DirectionCenter` / `Enter` | 自动驾驶 开/关 | `F` |
| ↑ | `DirectionUp` | 下一场景 | `E` |
| ↓ | `DirectionDown` | 上一场景 | `Q` |
| 菜单 `MENU` | `Menu` | 切换视角（阶段 4，可选） | `C` |
| ← → | `DirectionLeft/Right` | **保持不变**：切上/下一个效果 | — |
| BACK | `Back` | **保持不变**：退出可视化 | — |

实现要点：

- 拦截点放在**舞台 `onPreviewKeyEvent`**（`VisualizerStage.kt:227`）里新增一个前置分支，
  返回 `true` 即消费 —— Compose 预览阶段消费后 `MainActivity.onKeyDown` 收不到，
  OK 键的「播放/暂停」就被本效果接管了（**这是刻意的取舍，需在 README 已知限制里写明**）。
- **丢弃 repeat**：`event.nativeKeyEvent.repeatCount > 0` 直接返回 `true`（消费但不发），
  否则长按 OK 会连续 toggle 把自动驾驶开开关关。
- 防连发：`ACTION_DEBOUNCE_MS = 260ms`（`VisualizerViewModel.SWITCH_DEBOUNCE_MS` 实为 `180L`；本效果取 260 稍保守——toggle 类动作误触发的代价高于切效果）。
- ⚠️ 手机端左右滑动切效果的手势（`VisualizerStage.kt:238-252`）**预期保留，但有已知风险**：
  `isFocusable = false` 只解决键盘焦点，不解决触摸 —— WebView 是可滚动页面，触摸命中
  `AndroidView` 后被 WebView 消费，父级 `detectHorizontalDragGestures`（Main pass）收不到拖拽。
  缓解：舞台 Box 在 **Initial pass** 抢先识别横向拖拽，或宿主层禁用 WebView 触摸（§9.11）。
  **T3.3 挂载后必须真机验证滑动切效果；失效不是可接受态，按上述缓解修。**

---

## 六、实现清单（逐文件 · 含完整签名）

### 6.1 新增文件

| # | 文件 | 预估行数 | 说明 |
|---|---|---|---|
| N1 | `visualizer/slowroads/SlowRoadsConfig.kt` | 45 | 全部常量（URL / 超时 / 键位常量），**纯 Kotlin 无 Android 依赖** |
| N2 | `visualizer/slowroads/SlowRoadsKeyMapper.kt` | 95 | 键位映射纯函数，**可 JVM 单测** |
| N3 | `visualizer/slowroads/SlowRoadsScript.kt` | 130 | 三段 JS（按键 / WebGL 探测 / 静音）常量与组装，**可 JVM 单测** |
| N4 | `visualizer/slowroads/SlowRoadsWebViewHost.kt` | 270 | WebView 宿主：设置、加载、发键、探测、销毁 |
| N5 | `visualizer/renderers/SlowRoadsRenderer.kt` | 32 | 占位渲染器 |
| N6 | `ui/components/SlowRoadsWebViewLayer.kt` | 190 | Compose `AndroidView` 层 + 降级/重试 UI |
| N7 | `app/src/test/.../visualizer/slowroads/SlowRoadsKeyMapperTest.kt` | 120 | 门禁 G22 |
| N8 | `app/src/test/.../visualizer/slowroads/SlowRoadsScriptTest.kt` | 130 | 门禁 G23（含负向自证） |
| N9 | `app/src/test/.../visualizer/slowroads/SlowRoadsWebViewGuardTest.kt` | 110 | 门禁 G24（源码扫描 + 自证） |

### 6.2 改造文件

| # | 文件 | 改造点（`file:line`） | 改动 |
|---|---|---|---|
| M1 | `data/model/AppSettings.kt` | `:154`（`PHOTO_WALL` 之后） | 新增 `SLOW_ROADS("公路漫游", Tier.ADV, "39")`；`:100` KDoc「34 套」是**错的**（实际 36），一并改成新增后的 **37 套** |
| M2 | `visualizer/VisualizerRendererFactory.kt` | `:86`（`PHOTO_WALL` 分支后） | 新增 `VisualizerTheme.SLOW_ROADS -> SlowRoadsRenderer()` + import |
| M3 | `ui/components/VisualizerStage.kt` | `:253` 之后（Canvas 之后、前景 Column 之前） | 挂载 `SlowRoadsWebViewLayer`；`:227` 前置键位分支 |
| M4 | `app/src/test/.../VisualizerThemeTest.kt` | `:51,:57,:58,:89,:90,:96,:103` | **共 7 行，一处都不能漏**：`:51,:57,:58,:90,:96,:103` 断言 36 → 37（`:90` 是 `on.size`）；`:89` 是 `off.size` 35 → **36** |
| M5 | `AndroidManifest.xml` | `:62` | 显式补 `android:hardwareAccelerated="true"`（防止将来被改；WebView/WebGL 强依赖） |
| M6 | `CHANGELOG.md` / `docs/technical-overview.md` | 新节 / 新 §10.184 | §12 |

### 6.3 关键签名（可直接粘）

```kotlin
// N1 —— visualizer/slowroads/SlowRoadsConfig.kt
package com.nasmusic.tv.visualizer.slowroads

/** 「公路漫游」效果的唯一常量来源（改这里，不要散落在各处） */
object SlowRoadsConfig {

    /** 作者本人发布的一手页面（theslowroads.io 是第三方介绍页，github.io 已失效） */
    const val GAME_URL: String = "https://slow-roads.pages.dev/"

    /** 只允许停留在本机站内：shouldOverrideUrlLoading 之外的 host 一律用浏览器/拦截 */
    const val ALLOWED_HOST: String = "slow-roads.pages.dev"

    /** 页面加载超时（超时即降级，不无限转圈） */
    const val LOAD_TIMEOUT_MS: Long = 20_000L

    /** 首次尝试开启自动驾驶的等待起点（onPageFinished 之后） */
    const val AUTO_DRIVE_DELAY_MS: Long = 5_000L

    /** 探测间隔：canvas 未就绪则再等一轮 */
    const val READY_PROBE_INTERVAL_MS: Long = 2_500L

    /** 自动开启的最长等待；超时放弃自动、改为提示用户按 OK */
    const val AUTO_DRIVE_MAX_WAIT_MS: Long = 15_000L

    /** 两次动作最小间隔（防遥控 repeat 风暴） */
    const val ACTION_DEBOUNCE_MS: Long = 260L

    /** WebGL 能力探测超时 */
    const val WEBGL_PROBE_TIMEOUT_MS: Long = 8_000L

    /** 默认是否注入静音（游戏引擎声会盖住音乐） */
    const val MUTE_BY_DEFAULT: Boolean = true
}
```

```kotlin
// N2 —— visualizer/slowroads/SlowRoadsKeyMapper.kt
package com.nasmusic.tv.visualizer.slowroads

import androidx.compose.ui.input.key.Key

/**
 * 遥控器键 → 游戏按键的映射（**纯函数，不依赖 Android/Compose 运行时**，便于 JVM 单测）。
 *
 * ⛔ 只映射「本效果独占」的键；`←`/`→`（切效果）与 BACK **刻意不在其中**，
 *    它们由 `VisualizerStage` 的既有分支处理，本效果不得抢占。
 */
object SlowRoadsKeyMapper {

    enum class Action {
        /** 自动驾驶 开/关 —— 游戏键 F */
        TOGGLE_AUTO_DRIVE,

        /** 下一场景 —— 游戏键 E */
        NEXT_SCENE,

        /** 上一场景 —— 游戏键 Q */
        PREV_SCENE,

        /** 切换视角 —— 游戏键 C */
        TOGGLE_CAMERA,
    }

    /** 动作 → Android 键码（`dispatchKeyEvent` 通道用） */
    fun keyCodeOf(action: Action): Int = when (action) {
        Action.TOGGLE_AUTO_DRIVE -> android.view.KeyEvent.KEYCODE_F
        Action.NEXT_SCENE -> android.view.KeyEvent.KEYCODE_E
        Action.PREV_SCENE -> android.view.KeyEvent.KEYCODE_Q
        Action.TOGGLE_CAMERA -> android.view.KeyEvent.KEYCODE_C
    }

    /** 动作 → JS 注入用的键描述（`evaluateJavascript` 通道用） */
    fun keySpecOf(action: Action): KeySpec = when (action) {
        Action.TOGGLE_AUTO_DRIVE -> KeySpec(key = "f", code = "KeyF", keyCode = 70)
        Action.NEXT_SCENE -> KeySpec(key = "e", code = "KeyE", keyCode = 69)
        Action.PREV_SCENE -> KeySpec(key = "q", code = "KeyQ", keyCode = 81)
        Action.TOGGLE_CAMERA -> KeySpec(key = "c", code = "KeyC", keyCode = 67)
    }

    /** Compose 键 → 动作；未绑定返回 `null` */
    fun mapKey(key: Key): Action? = when (key) {
        Key.DirectionCenter, Key.Enter -> Action.TOGGLE_AUTO_DRIVE
        Key.DirectionUp -> Action.NEXT_SCENE
        Key.DirectionDown -> Action.PREV_SCENE
        Key.Menu -> Action.TOGGLE_CAMERA
        else -> null
    }

    data class KeySpec(val key: String, val code: String, val keyCode: Int)
}
```

```kotlin
// N3 —— visualizer/slowroads/SlowRoadsScript.kt（节选：三段脚本）
package com.nasmusic.tv.visualizer.slowroads

/** 注入页面的三段 JS。**全部为纯字符串常量**，便于单测校验不变量。 */
object SlowRoadsScript {

    /**
     * 合成键盘事件（keydown + keyup）。
     *
     * ⛔ 三条不变量（G23 守护，缺一条就失效）：
     *   ① 必须派发到 **window 与 document 两处**（只发一处时监听另一处的应用收不到）；
     *   ② `keyCode` / `which` 必须显式 `defineProperty` —— `KeyboardEvent` 构造器里传的
     *      `keyCode` 在 Chromium 上**是只读且恒为 0**，不覆盖就识别不出按键；
     *   ③ `code` 必须一并给（`event.code === 'KeyF'` 是现代游戏的常见写法）。
     */
    fun sendKey(spec: SlowRoadsKeyMapper.KeySpec): String = """
        (function(){
          var s = {key:${spec.key.jsStr()}, code:${spec.code.jsStr()}, kc:${spec.keyCode}};
          function mk(type){
            var e = new KeyboardEvent(type, {bubbles:true, cancelable:true, key:s.key, code:s.code});
            Object.defineProperty(e, 'keyCode', {get:function(){return s.kc;}});
            Object.defineProperty(e, 'which',   {get:function(){return s.kc;}});
            return e;
          }
          var d = mk('keydown'), u = mk('keyup');
          window.dispatchEvent(d); window.dispatchEvent(u);
          document.dispatchEvent(d); document.dispatchEvent(u);
          var a = document.activeElement;
          if (a && a !== document.body && a.dispatchEvent) {
            a.dispatchEvent(mk('keydown')); a.dispatchEvent(mk('keyup'));
          }
          return true;
        })()
    """.trimIndent()

    /** WebGL 能力探测：返回 "true" / "false" 字符串给 `ValueCallback` */
    fun probeWebGL(): String = """
        (function(){
          try {
            var c = document.createElement('canvas');
            var gl = c.getContext('webgl2') || c.getContext('webgl') || c.getContext('experimental-webgl');
            return !!(gl && typeof gl.getParameter === 'function');
          } catch (e) { return false; }
        })()
    """.trimIndent()

    /** 游戏是否就绪：存在一个尺寸正常的 canvas 即认为已进入可交互状态 */
    fun probeReady(): String = """
        (function(){
          var c = document.querySelector('canvas');
          return !!(c && c.width > 0 && c.height > 0);
        })()
    """.trimIndent()

    /**
     * 静音：把页面里所有 audio/video 静音，并掐断 WebAudio 输出总增益。
     * ⚠️ 依赖页面尚未创建 AudioContext 时才最有效；已创建的用 destination 增益兜底。
     */
    fun mute(): String = """
        (function(){
          try {
            var m = document.querySelectorAll('audio,video');
            for (var i = 0; i < m.length; i++) { m[i].muted = true; m[i].volume = 0; }
            var AC = window.AudioContext || window.webkitAudioContext;
            if (AC && !AC.__nasmuted) {
              AC.__nasmuted = true;
              var orig = AC.prototype.createGain;
              AC.prototype.createGain = function(){
                var g = orig.apply(this, arguments);
                try { g.gain.value = 0; } catch (e) {}
                return g;
              };
            }
            return true;
          } catch (e) { return false; }
        })()
    """.trimIndent()

    private fun String.jsStr(): String = "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"
}
```

```kotlin
// N5 —— visualizer/renderers/SlowRoadsRenderer.kt
package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRenderer

/**
 * 「公路漫游」占位渲染器 —— **刻意什么都不画**。
 *
 * 画面由 `SlowRoadsWebViewLayer` 的 WebView 承载。本类存在的唯一理由是契约：
 * `RendererSwapper.sync()` 对每个主题都调 `factory(theme)` 并走 `onEnter/onExit`，
 * 返回值必须非 null（`RendererSwapper.kt:84`），且工厂 `when` 必须穷举。
 */
class SlowRoadsRenderer : VisualizerRenderer {
    override val theme: VisualizerTheme = VisualizerTheme.SLOW_ROADS
    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) = Unit
}
```

```kotlin
// N4 —— visualizer/slowroads/SlowRoadsWebViewHost.kt（接口骨架）
package com.nasmusic.tv.visualizer.slowroads

/** 宿主状态（驱动 N6 的降级 UI） */
enum class SlowRoadsState { LOADING, READY, NO_WEBGL, LOAD_FAILED, TIMEOUT }

class SlowRoadsWebViewHost(
    context: android.content.Context,
    private val onStateChange: (SlowRoadsState) -> Unit
) {
    /** 供 `AndroidView` 使用的 View。**必须不可聚焦**，否则舞台收不到遥控按键 */
    val view: android.webkit.WebView

    fun load()
    /** 双通道发键：先 `dispatchKeyEvent`，再 `evaluateJavascript` 兜底 */
    fun sendAction(action: SlowRoadsKeyMapper.Action)
    /** 自动开启自动驾驶（探测就绪 + 重试），用户一旦手动按键即取消本流程 */
    fun scheduleAutoDrive()
    fun cancelAutoDrive()
    fun reload()
    fun destroy()
}
```

```kotlin
// N6 —— ui/components/SlowRoadsWebViewLayer.kt（接线骨架）
@Composable
fun SlowRoadsWebViewLayer(
    /** 由舞台转发的动作（`null` = 本帧无动作）；非空即发键并取消自动巡航 */
    pendingAction: SlowRoadsKeyMapper.Action?,
    onActionConsumed: () -> Unit,
    onFallbackToPrevTheme: () -> Unit,
    modifier: Modifier = Modifier
)
```

---

## 七、发键与自动巡航：为什么不是「延时 3 秒发一次 F」

原方案写 `onPageFinished` 后 `postDelayed(3000)` 发一次 `F`。实测风险是**页面 finish ≠ 游戏就绪**
（Three.js 场景/地形生成可能十几秒），发早了等于白发，且 F 是 toggle —— 多发一次就**抵消**了。

采用**探测 + 竞态保护**：

1. `onPageFinished` → 注入静音（若开启）→ 起 `WEBGL` 探测；
2. 无 WebGL ⇒ 直接 `NO_WEBGL` 降级，**不再尝试发键**；
3. 有 WebGL ⇒ 等 `AUTO_DRIVE_DELAY_MS` 后跑 `probeReady()`：
   - ready ⇒ 发一次 `F`，置 `READY`；
   - 未 ready ⇒ 每 `READY_PROBE_INTERVAL_MS` 再探，直到 `AUTO_DRIVE_MAX_WAIT_MS` 超时转 `TIMEOUT`
     （超时**不**发键，只提示「按 OK 开启自动驾驶」，避免 toggle 抵消）；
4. **任何一次用户手动按键** ⇒ `cancelAutoDrive()`，后续只听用户的。

发键双通道（缺一不可）：

```
通道① view.dispatchKeyEvent(KeyEvent(ACTION_DOWN, KC)) + ACTION_UP
        └─ 优点：走系统输入管线，最贴近真实键盘
        └─ 缺点：老 WebView 可能填不出 event.code
通道② view.evaluateJavascript(SlowRoadsScript.sendKey(spec), null)
        └─ 优点：key/code/keyCode 三者齐全，最稳
        └─ 缺点：合成事件（isTrusted=false）；个别页面校验会忽略 —— 所以必须有通道①
```

---

## 八、关键参数表

| 参数 | 值 | 理由 |
|---|---|---|
| `GAME_URL` | `https://slow-roads.pages.dev/` | 作者一手发布页（其余为第三方/失效） |
| `ALLOWED_HOST` | `slow-roads.pages.dev` | 防止页面内跳转把用户带出 App |
| `Tier` | `ADV` | LOW 档（`maxParticles == 0`）自动隐藏，保护老弱设备；MEDIUM 是默认档 ⇒ 用户看得到 |
| `ordinalLabel` | `"39"` | 接在照片墙 `"38"` 之后（编号**只增不改**，历史编号已固化） |
| `displayName` | `公路漫游` | 与既有 36 套一致：枚举硬编码中文 |
| `LOAD_TIMEOUT_MS` | 20 000 | 电视网络慢，太短会误判失败 |
| `AUTO_DRIVE_DELAY_MS` | 5 000 | 页面 finish 后给场景生成留出时间 |
| `READY_PROBE_INTERVAL_MS` | 2 500 | |
| `AUTO_DRIVE_MAX_WAIT_MS` | 15 000 | 再久用户也以为卡死了 |
| `ACTION_DEBOUNCE_MS` | 260 | 效果切换防抖 `SWITCH_DEBOUNCE_MS` 实为 `180L`；本效果取 260 稍保守（toggle 类动作误触发代价更高） |
| `WEBGL_PROBE_TIMEOUT_MS` | 8 000 | |
| WebSettings | `javaScriptEnabled=true`、`domStorageEnabled=true`、`databaseEnabled=true`、`loadWithOverviewMode=true`、`useWideViewPort=true`、`mediaPlaybackRequiresUserGesture=false`、`cacheMode=LOAD_DEFAULT` | 游戏必需 |
| WebView 焦点 | `isFocusable=false`、`isFocusableInTouchMode=false` | **必须**，否则抢走遥控按键 |
| 背景 | `setBackgroundColor(BLACK)` | 避免加载期白闪 |

---

## 九、风险、能力检测与降级

| # | 风险 | 检测/应对 |
|---|---|---|
| 9.1 | ⚠️ **Android 5.1.1 电视的 WebView 可能没有可用的 WebGL**（最高风险，可能直接判死该设备） | 进入即跑 `probeWebGL()`；失败 ⇒ `NO_WEBGL` 卡片：「当前设备的浏览器内核不支持 3D，无法运行本效果」+「切换效果」按钮。**先手机验、再电视验**（§12） |
| 9.2 | WebView 版本过旧导致 JS 语法报错（`KeyboardEvent` 构造器等） | 发键结果用 `ValueCallback<String>` 回读，异常即降级提示；不用 `@JavascriptInterface`（省掉 ProGuard keep 与注入面） |
| 9.3 | 性能：3D 游戏吃满 CPU/GPU，音乐播放卡 | 降低预期：本效果不保证帧率；设置里画质档降到 LOW 时该效果**自动不可见**（`Tier.ADV` 门控） |
| 9.4 | 内存：WebView + WebGL + ExoPlayer 同驻 | 离开即 `destroy()`；`largeHeap` 已开（`AndroidManifest.xml:72`）；不在本效果里保留其它渲染器的重资源 |
| 9.5 | 🔊 **音效冲突**：游戏有引擎声，会盖住音乐 | 默认注入静音脚本（`MUTE_BY_DEFAULT=true`）；⚠️ 实现前须用 `android-dep-api-verify` 核实 minSdk 22 上有没有更可靠的系统级静音 API，有则优先用 |
| 9.6 | 网络不通 / 被墙 | `onReceivedError` + 超时 ⇒ `LOAD_FAILED` 卡片带「重试」 |
| 9.7 | 页面改版、按键绑定变更 | 键位集中在 `SlowRoadsKeyMapper`（一处改）；README 已知限制写明「依赖第三方页面，可能失效」 |
| 9.8 | OK 键被本效果接管后不能暂停 | 已知取舍；写入 README 已知限制 + 效果 Toast 提示 |
| 9.9 | Android 5.1 hwui 在离屏合成上出现过段错误（既有教训） | 若真机在本效果下闪退，第一步尝试：Canvas 层在本效果下**不启用** `CompositingStrategy.Offscreen` |
| 9.10 | ProGuard/R8 | 本方案不用 `addJavascriptInterface` ⇒ 无需新增 keep；若后续加了，**必须**补 keep 并复跑 release |
| 9.11 | ⚠️ **手机端 WebView 消费触摸 → 滑动切效果失效**（`isFocusable=false` 只管焦点不管触摸） | 舞台 Box 在 Initial pass（`awaitEachGesture`）抢先识别横向拖拽，或宿主层禁用 WebView 触摸；**T3.3 真机验证**（§5.2） |

---

## 十、单测门禁清单

基线：`testDebugUnitTest` **1177 例 / 115 类 / 0 失败**，`lintDebug` **0E / 279W**。

| 门禁 | 类 | 断言要点 |
|---|---|---|
| 既有 | `VisualizerThemeTest` | **7 行**：`:51,:57,:58,:90,:96,:103` 硬断言 36 → **37**（`:90` 是 `on.size`）+ `:89` `off.size` 35 → **36**；`ordinalLabel` 唯一性自动覆盖新项；T1.3 另增 1 例 `LOW.supports(SLOW_ROADS)` 守护用例 |
| **G22** | `SlowRoadsKeyMapperTest`（约 12 例） | ① OK↔F、↑↔E、↓↔Q、MENU↔C 双向一致；② `keyCodeOf` 与 `keySpecOf.keyCode` **必须逐项相等**（两条通道不能打架）；③ `←`/`→`/`Back` 映射到 `null`（**负向自证**：若有人图省事把左右也映射进去，测试必须红）；④ KeySpec 的 `code` 必须以 `Key` 开头 |
| **G23** | `SlowRoadsScriptTest`（约 14 例） | ① 发键脚本**同时**含 `window.dispatchEvent` 与 `document.dispatchEvent`（负向自证：只发 window 的旧写法必须被判出）；② 含 `Object.defineProperty(e, 'keyCode'`（负向自证：只在构造器里传 keyCode 的写法必须被判出）；③ 含 `code:`；④ `probeWebGL` 覆盖 webgl2/webgl/experimental-webgl 三种；⑤ 键名转义：注入 `'` 与 `\` 不破坏 JS 字符串（**负向自证**：未转义的必须被判出）；⑥ 空转断言（脚本常量非空、能被扫到） |
| **G24** | `SlowRoadsWebViewGuardTest`（源码扫描，约 10 例 + 自证） | ① `visualizer/slowroads/` 与 `ui/components/SlowRoadsWebViewLayer.kt` 内出现 `WebView` 的每处，同文件 32 行内必须有 `isFocusable = false`（负向自证：构造一个只 `new WebView` 不设不可聚焦的样本必须命中）；② 占位渲染器 `draw` 体必须为空（`Unit`）；③ 工厂 `when` 覆盖 `SLOW_ROADS`；④ **空转断言**：扫描到的文件数 ≥ 3，否则测试自红 |

⚠️ 源码扫描门禁**必须自带负向自证**（既有教训：只判「0 违规」分不清「真干净」和「没扫到」）。

---

## 十一、提交顺序（每步可独立验证）

| # | 提交内容 | 验收点 |
|---|---|---|
| C1 | 枚举 + 工厂分支 + 占位渲染器 + 测试断言 36→37 | 门禁绿（1177 + 1：T1.3 的 LOW 门控守护用例）/ 编译过 / 效果列表出现「公路漫游」（选中为黑屏，符合预期） |
| C2 | `SlowRoadsConfig` + `SlowRoadsKeyMapper` + `SlowRoadsScript` + G22/G23 | 门禁绿（+26 例）；**纯 JVM，不碰设备** |
| C3 | `SlowRoadsWebViewHost` + `SlowRoadsWebViewLayer` + 舞台挂载 | `assembleDebug` + `lintDebug` 0E；能进到该效果并看到网页（无键位也行） |
| C4 | 舞台键位拦截（OK/↑/↓）+ 自动巡航调度 + G24 | 门禁绿；真机按 OK 能开/关自动驾驶 |
| C5 | WebGL 探测、超时、失败降级卡片、生命周期释放 | 断网 / 无 WebGL 设备上有明确提示而非黑屏；进出 5 次无泄漏 |
| C6 | 文档：CHANGELOG 新节、§10.184、README 已知限制 | 见 §12 |

---

## 十二、上机验收清单

> ⛔ 规则：产物就绪后**交给用户安装**，不在会话里往电视上装包；上机一律 **release 包**；不代启动。
> 设备：电视 `192.168.0.114:5555`（Android 5.1.1 / SDK 22 / armeabi-v7a）｜手机 小米 22081212C（adb `91846823`）

**手机（先做，用来区分「代码问题」还是「设备不支持」）**

1. 播放一首歌 → 进可视化 → 切到「公路漫游」→ 网页加载出来，无白屏
2. 等约 5–10 秒 → 画面**自己开始往前开**（自动巡航生效）
3. 按 OK → 车停下；再按 OK → 又开起来
4. 按 ↑ / ↓ → 场景（地貌/时段）变化
5. 按 ← / → 、手机左右**滑动** → 仍然切的是**频谱效果**（不能被本效果吃掉；滑动失效按 §9.11 缓解后复测）
6. 按返回 → 退出可视化，音乐**不中断**，无残留游戏声
7. 反复进出 5 次 → 无崩溃、无明显内存增长

**电视（后做，预期可能失败）**

8. 切到该效果 → 若提示「不支持 3D」：属**预期降级**，C5 生效（不是 bug）
9. 若白屏/卡死/闪退：记录 `logcat -b crash`，按 §9.1 / §9.9 处理；必要时改判为「电视不可用」并写入 README 已知限制

---

## 十三、开发任务清单（可勾选 · 进度追踪）

> 打勾前**必须复跑该行的验收判据**，不凭记忆勾。
> 状态：`⬜ 未开始` ｜ `🟨 进行中` ｜ `✅ 完成` ｜ `⛔ 放弃（保留行，写明原因）`

### 进度总览

| 阶段 | 提交 | 任务数 | 状态 |
|---|---|---|---|
| 1 骨架 | C1 | 4 | ⬜ |
| 2 纯逻辑 | C2 | 4 | ⬜ |
| 3 宿主与挂载 | C3 | 5 | ⬜ |
| 4 键位与自动巡航 | C4 | 5 | ⬜ |
| 5 降级与健壮性 | C5 | 4 | ⬜ |
| 6 文档与交付 | C6 | 4 | ⬜ |

⛔ 改任务清单后必须同步改这张表；两处不一致时**以 checkbox 实测为准**，并回头修表。
> 计数口径：含每阶段末尾的「阶段完成」验收项（它本身也是一个真机/门禁验收）。

### 阶段 1 · 骨架　**4 项**

- [ ] **T1.1** 枚举项与工厂接线
  - `AppSettings.kt:154` 后加 `SLOW_ROADS("公路漫游", Tier.ADV, "39")`；**选 `ADV` 的理由**：`ULTRA` 只在 HIGH 档可见（用户默认 MEDIUM 会看不到），`BASIC` 会让 LOW 档老设备也去跑 3D
  - 同一处把 `:100` KDoc「34 套」修正为与实际相符的计数
  - `VisualizerRendererFactory.kt:86` 后加分支 + import
  - **验收**：`assembleDebug` 过；设置/指示器列表出现「公路漫游」；选中是黑屏且不崩
- [ ] **T1.2** 占位渲染器 `SlowRoadsRenderer`
  - `draw` 体必须是 `= Unit`；KDoc 写明「为什么必须存在」（`RendererSwapper.kt:84` 契约）
  - **验收**：切到该效果不崩；G24 的「draw 体为空」断言绿
- [ ] **T1.3** 既有测试硬断言 36 → 37
  - `VisualizerThemeTest.kt:51,:57,:58,:90,:96,:103` → 37（`:90` 是 `on.size`）；`:89` `off.size` → 36 —— **共 7 行，一处都不能漏**
  - 顺带增补：`assertFalse(VisualQuality.LOW.supports(VisualizerTheme.SLOW_ROADS))`（守护 ADV 门控；注意 `AppSettings.kt:229-230` 的 PHOTO_WALL 豁免**不得**波及本效果）
  - **验收**：`testDebugUnitTest` 绿（1177 + 1 例，本阶段仅此新增）

- [ ] **阶段完成**：门禁 1178/0（1177 + T1.3 新增 1 例）、lint 0E/279W；切到新效果黑屏但不影响其它 36 套

### 阶段 2 · 纯逻辑（可 JVM 单测）　**4 项**

- [ ] **T2.1** `SlowRoadsConfig` 常量集中
  - 所有时长/URL/开关只此一处；**不得**在实现里写魔法数
  - **验收**：全仓 grep 本效果的时长字面量只出现在 Config
- [ ] **T2.2** `SlowRoadsKeyMapper` + G22
  - OK↔F / ↑↔E / ↓↔Q / MENU↔C；`←`/`→`/`Back` 必须返回 `null`
  - **验收**：G22 全绿，含**负向自证**（把左右键也映射进去的写法必须被判出）
- [ ] **T2.3** `SlowRoadsScript` 三段脚本 + G23
  - 发键脚本必须同时派发 window + document，且 `defineProperty` 覆盖 `keyCode`/`which`
  - 键名转义函数不能漏（负向自证：注入 `'` / `\` 的旧写法必须被判出）
  - **验收**：G23 全绿（约 14 例）

- [ ] **阶段完成**：`testDebugUnitTest` 1204 例 / 0 失败（1178 + 26）；纯 JVM，不上机

### 阶段 3 · WebView 宿主与舞台挂载　**5 项**

- [ ] **T3.1** `SlowRoadsWebViewHost` 设置与加载
  - `isFocusable = false` / `isFocusableInTouchMode = false`（**缺了就切不动效果**）
  - WebSettings 按 §8；`WebViewClient` 拦截非白名单 host；黑底防白闪
  - **验收**：手机切到该效果能看到网页
- [ ] **T3.2** `SlowRoadsWebViewLayer`（Compose 层）
  - `AndroidView` + `DisposableEffect` 释放；状态 → 降级卡片（重试 / 切换效果）
  - ⛔ 用 `evaluateJavascript` 回读，**不要**用 `addJavascriptInterface`（规避 ProGuard 与注入面）
  - **验收**：进出 5 次无崩溃；失败态有卡片不是黑屏
- [ ] **T3.3** `VisualizerStage` 挂载（Canvas 之后、前景 Column 之前）
  - **验收**：`assembleDebug` + `lintDebug` 0E；歌词/指示器仍在最上层且可见；**手机真机：左右滑动仍能切效果**（WebView 触摸消费风险，失效按 §5.2 / §9.11 缓解）
- [ ] **T3.4** G24 源码扫描门禁
  - 每条 `WebView` 构造附近必须有不可聚焦设置；空转断言（扫描文件数 ≥ 3）
  - **验收**：G24 绿；**故意删掉一处 `isFocusable = false` 时必须变红**

- [ ] **阶段完成**：手机上能加载并看到画面；其余 36 套效果行为零变化

### 阶段 4 · 键位与自动巡航　**5 项**

- [ ] **T4.1** 舞台键位前置分支（仅本主题）
  - 在 `VisualizerStage.kt:227` 的 `onPreviewKeyEvent` 里，主题为 `SLOW_ROADS` 时先查 `mapKey`
  - `repeatCount > 0` 直接消费丢弃；`ACTION_DEBOUNCE_MS` 防连发
  - **验收**：按 OK 只在按下的那一次生效；长按不会疯狂 toggle
- [ ] **T4.2** 双通道发键（dispatchKeyEvent + JS 注入）
  - **验收**：手机按 OK 能开/关自动驾驶（只走一条通道不稳，两条都要有）
- [ ] **T4.3** 自动巡航调度（探测 + 重试 + 取消）
  - 按 §7 流程；超时**不**发键（避免 toggle 抵消）
  - 用户手动按键即 `cancelAutoDrive()`
  - **验收**：进入后 5–15s 内自己开起来；手动按过一次后自动流程不再插手
- [ ] **T4.4** 左右键/BACK 行为回归
  - **验收**：本效果下 ←/→ 仍然切效果、BACK 仍然退出可视化（**负向自证**：若被本效果吃掉即失败）；**含手机滑动路径**（§9.11）

- [ ] **阶段完成**：手机 7 条验收全过（§12 手机 1–7）

### 阶段 5 · 降级与健壮性　**4 项**

- [ ] **T5.1** WebGL 能力探测 + `NO_WEBGL` 降级卡片
  - 无 WebGL ⇒ 不发任何键、不空转
  - **验收**：无 WebGL 设备显示明确提示 + 「切换效果」按钮
- [ ] **T5.2** 加载失败 / 超时 / 断网 三态与重试
  - **验收**：断网进入 ⇒ `LOAD_FAILED` + 重试可用；弱网 ⇒ 20s 后转超时提示
- [ ] **T5.3** 静音注入与音效冲突
  - 实现前用 `android-dep-api-verify` 核实 minSdk 22 的系统级静音 API；有则用，无则用 JS 兜底
  - **验收**：进入该效果后音乐不被游戏音盖住；退出后音乐音量正常

- [ ] **阶段完成**：手机 7 条 + 电视 2 条（§12）全过或明确记录降级原因

### 阶段 6 · 文档与交付　**4 项**

- [ ] **T6.1** `CHANGELOG.md` 新增 `v2.38.0` 节（**插到文件最前**）
  - 每条只写「做了什么」；已知限制（依赖第三方页面、OK 键被接管、部分设备不支持）写进 Fixed/已知限制
- [ ] **T6.2** `docs/technical-overview.md` 新增 §10.184（当前最大为 §10.183）
  - 内容：为什么用占位渲染器 + 旁路层、键位冲突分析表、自动巡航状态机
- [ ] **T6.3** `README.md` 功能与已知限制
  - 功能：新增「公路漫游」效果（一句话）
  - **已知限制**：依赖第三方网页、需联网、部分老设备（含 Android 5.1 电视）可能因内核不支持无法运行、本效果下 OK 键不触发暂停

- [ ] **阶段完成**：门禁绿 + release 包产出 → **交用户安装**（不代装）

---

## 十四、待裁决（需要你拍板的 4 件事）

| # | 问题 | 我的默认 | 影响 |
|---|---|---|---|
| 1 | **效果显示名**：「公路漫游」还是直白写「Slow Roads」？ | 「公路漫游」（与「分子」「照片墙」同类命名） | 枚举 `displayName` |
| 2 | **OK 键被本效果接管**（该效果下按 OK 不再暂停音乐） | 接受（遥控上没有更合适的键） | 若不接受，需改用「长按 OK」或放弃自动驾驶遥控开关 |
| 3 | **游戏音效**：默认静音注入 vs 保留引擎声 | 默认静音（音乐优先） | 静音注入在部分内核上可能失效 |
| 4 | **电视跑不动怎么办**：① 降级提示后仍保留该效果 ② 检测到不支持就从列表隐藏 | ① 保留 + 明确提示（保留用户知情权，也便于将来内核升级后可用） | 若电视是你的主要使用场景且确认跑不动，选 ② 更干净 |

---

## 十五、附：原文案要点归档（保留原始调研结论，供实现时对照）

- 官方真实地址 `https://slow-roads.pages.dev/`（页面底部 `from topograph.io © 2025`，开发者 Anslo）
- `theslowroads.io/slow-roads-2/` 是第三方介绍页；`slow-roads.github.io` 已失效
- 控制全部绑在键盘上：自动驾驶 `F`、下一场景 `E`、上一场景 `Q`、视角 `C`
- 无官方接口 ⇒ 一切靠模拟按键；页面改版即失效（属已知限制）
- WebGL 3D 应用，对 GPU 有要求；必须联网

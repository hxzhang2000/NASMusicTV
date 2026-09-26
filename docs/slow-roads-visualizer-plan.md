# 公路漫游（Slow Roads WebView）频谱效果 —— 开发方案

> 目标：在**频谱效果库**里新增第 37 套效果「公路漫游」——效果层不是 Canvas 自绘，
> 而是一个**全屏 WebView**，加载 `https://slow-roads.pages.dev/`，用遥控器控制自动驾驶与场景切换。
>
> 状态：**待开工**（可开发级：2026-09-24 代码审阅修订、4 项裁决落定、§6.4 实现细化补全——照 §六/§6.4 敲代码无需再做设计决策）
> 基线：v2.37.0 ｜ 门禁 `testDebugUnitTest` **1177 例 / 115 类 / 0 失败**，`lintDebug` **0 Error / 279 Warning**
> （2026-09-24 实测复核：JUnit XML 汇总 115 类 / 1177 例 / 0 失败 / 0 错误 / 0 跳过，与基线一致；计数漂移先确认是不是本轮新增的）

### 文档版本跟踪

| 文档版本 | 日期 | 变更摘要 | 状态 |
|---|---|---|---|
| v1.0 | 2026-09-24 | 初稿：可开发级方案，基线门禁实测（1177 例 / 115 类 / 0F、lint 0E/279W） | 已完成编写 |
| v1.1 | 2026-09-24 | 按代码现状审阅修订：① F1 修 `VisualizerThemeTest` 断言行号错位（§二 #11、§6.2 M4、§十、T1.3——正确集合 7 行，含 `:89` off.size 与 `:90` on.size）；② F2 降级「手机滑动仍切效果」假设并补缓解与验收（§三、§5.2、§9.11、T3.3、T4.4、§12）；③ 补 PHOTO_WALL 粒子门控豁免事实与守护用例（§二 #10、T1.3）；④ minor：§三 NPE→编译不过、§5.2/§8 防抖口径（180L）、§9.4 largeHeap 行号 :72、G24 覆盖 isFocusableInTouchMode | 待开工 |
| v1.2 | 2026-09-24 | §十四 4 项待裁决由用户拍板落定：显示名「公路漫游」、接受 OK 键接管、默认静音注入、降级提示后保留效果；「待裁决」改「裁决记录」，方案进入可开工终版 | 已裁决 / 待开工 |
| v1.3 | 2026-09-24 | 补全至可开发层次：新增 §6.4 实现细化（§6.4.1 舞台集成代码：键位前置分支 / pendingAction / Initial pass 滑动缓解；§6.4.2 Host 实现细化：WebView 设置与 Client 行为表 / 状态机 / 发键时序 / destroy 顺序；§6.4.3 Layer 实现代码：key(reloadKey) 防御、Lifecycle 挂接、降级卡片键位化；§6.4.4 交叉淡入交互；§6.4.5 门禁用例名单；§6.4.6 边界定案）；§7 自动巡航超时改转 READY；§9.1 卡片按钮改键位提示；§6.3 N6 签名去掉 onFallbackToPrevTheme；T3.x/T4.3 补实现细节指针 | 已审阅 |
| v1.4 | 2026-09-24 | 代码审阅修订：① M3 行号 :253→:336（Canvas 旧层结束与前景 Column 之间）；② §6.4.2(b) WebViewClient 行为表补 `onRenderProcessGone`（渲染进程崩溃→LOAD_FAILED）；③ §6.4.1(d) pointerInput 布尔 key 加注未来扩展风险；④ §6.4.2(d) sendAction LOADING 态加注 + 降级重试语义复用注释；⑤ §9 风险表补 9.12 WebView 渲染进程崩溃条目；⑥ §6.4.2 补 WebChromeClient 调试建议 | 可开工终版 |
| v1.5 | 2026-09-24 | 代码审阅修订（v1.4 审阅结论全部落定）：**A** §6.4.3 `LocalLifecycleOwner` 依赖缺失会编译失败 → 改 `androidx.compose.ui.platform.LocalLifecycleOwner`（项目未引 `lifecycle-runtime-compose`）；**B** `onRenderProcessGone` 是 API 26 回调，对 Android 5.1（SDK 22）**不生效**，降级为高版本兜底并加版本守卫（§6.4.2-b、§9.12、§6.4.5 G24、§十 G24、T3.4）；**C** `onReceivedHttpError` 不能只记日志（`onReceivedError` 不覆盖 HTTP 错误）→ 主框架 HTTP 错误转 `LOAD_FAILED` + 新增 §9.14 风险项（§6.4.2-b、§9.14、T5.2）；**D** 「系统级静音 API」不存在，删掉 `android-dep-api-verify` 引导，定案 JS 静音为唯一方案（§9.5、§十四-3、T5.3）；**E** crossfade 与代码不符：`swapper.sync` 恒 `false`、硬切，`prevRenderer` 恒 null → §4.2 分层图删「②' 旧效果淡出层」、§6.4.4 改为「旧效果立即硬切」（§4.2、§6.4.4）；**F** §4.3 创建/加载描述与 §6.4.3 一致化；**G** §二 #2 修正：`:146` 「第 36 个」注释**是对的**（PHOTO_WALL 确为第 36 个枚举成员），仅 `:100` KDoc「34 套」有误；**H** §5.1 冲突表补 `isImmersiveMode` 分支；**I** `sendAction` 三态分流定案：LOADING 态 no-op（不发键待 READY）+ 三个降级态（含 NO_WEBGL）按 OK 一律 `load()` 重试（§6.4.2-d、§6.4.6-8、§6.4.3 卡片文案、§9.1、§12）；**J** §6.4.2(a) `databaseEnabled` 加注（API 19+ WebSQL 已移除）；**K** `shouldOverrideUrlLoading` 拦截时叠一次性 Toast | 已审阅 |
| v1.6 | 2026-09-24 | **用户实测级修正（两条硬事实）**：**L** 静音**发 `M` 键**即可（游戏原生静音 toggle），比 JS `createGain` patch 可靠；改为 M 为主、JS 兜底（§1.3、§6.3 N1/N2、§7、§9.5、§十四-3、T5.3、§12）；**M** 打开裸 URL 会先停在 **Begin 欢迎屏，需点击 Begin 才进画面**；进入后 URL 变成带 hash 的场景地址（`…/#A3-f420a738@6.00`，每次不同），**再次打开该 hash URL 可直接进画面**——原 §7「onPageFinished 后探测 canvas 就绪」在 Begin 阶段恒 false，会静默等满超时不发 F，画面静止像卡死 ⇒ 新增 `probeBegin()` / `clickBegin()` 两段 JS + 状态机 Begin 分支 + hash 缓存优化（§1.3、§6.3 N1/N3、§6.4.2-c、§7、§8、§12、T5.2/T5.3、G23 新增用例） | 已审阅 |
| v1.7 | 2026-09-24 | **浏览器实测推翻 v1.6 核心前提**（两轮 `sr-page-check v1` / `sr-begin-check v1` 实测）：**A** Begin 屏**有 canvas**（300×150，CSS 752×384）⇒ v1.6「Begin 阶段无 canvas」的前提是错的，据此写的 `probeBegin`（无 canvas 才判 Begin）在真实页面上**恒 false**，`clickBegin()` 永远走不到，比 v1.5 更糟；**B** `probeReady` 用 `width>0 && height>0` 在 Begin 屏就返回 true ⇒ 会在用户还停 Begin 屏时**白发 F**；⇒ 修正：Begin 按钮用精确 ID `#splash-begin`（实测 `DIV#splash-begin` / class `svelte-hmec5z` / `clickable=true`，Svelte 应用），`probeBegin` 改为「该元素存在且可见」，`clickBegin` 改为 `getElementById(...).click()`，`probeReady` 改为「Begin 元素已消失 且 canvas CSS 尺寸达阈值」（§1.3、§6.3 N3、§6.4.2-c、§7、§9.15、G23、T5.2）；**C** `KeyboardEvent` 构造器传 `keyCode` **实测有效**（传 70 返回 70，不是恒 0）⇒ §6.3 N3 的断言要改（defineProperty 降级为兜底，非必需）；**D** 游戏音频走 **WebAudio**、页面无 `<audio>/<video>` 元素 ⇒ `mute()` 里「遍历 audio/video 设 muted/volume」对游戏本身是**空操作**，真正生效的只有 AudioContext patch（§6.3 N3）；**E** `activeElement = DIV#main`（非 body）⇒ `sendKey` 第三分支「再往 activeElement 发一次」**有效命中 `#main`**，不是摆设；**F** 已进画面时 body 文本仍含 "begin"（版本日志/菜单项）⇒ G23「有 canvas 时 `probeBegin` 必须 false」负向用例**必要且正确**（§十 G23） | 已实测 |
| v1.8 | 2026-09-24 | **方案级推翻：合成发键在真实页面上无效**（浏览器逐键实测 F/E/C）：**G** 游戏**校验 `isTrusted`**——合成 `KeyboardEvent` 恒 `false`，物理键盘 F 能让车动、合成 F 无效（已排除焦点干扰：脚本内先 `window.focus()`+`canvas.focus()`、焦点实测回到 `DIV#main` 仍无效），E/C 同；⇒ **§7 双通道策略作废**：通道②（`evaluateJavascript` 合成事件）**不再是主路径**，唯一可靠主路径是**通道① `dispatchKeyEvent`**（走系统输入管线，Chromium 会转成 `isTrusted=true` 的真实 DOM 事件）；通道②降级为**老内核兜底**且必须回读确认（§7、§6.3 N3、§6.4.2-d、§9.5、§9.16、§十 G22/G23、§十二 3-5、T4.2、T5.3）；**H** `isTrusted` 无法用 JS 伪造（浏览器安全属性，只读），所以没有「绕过合成」的第三条路（§9.16） | 已实测 |

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
| 遥控能直接控制游戏吗 | 不能，需「翻译层」：遥控键 → `KeyEvent`（**主路径**，Chromium 转成 `isTrusted=true`）/ JS 合成 `KeyboardEvent`（老内核兜底，实测 `isTrusted=false` 被游戏忽略）发给页面 |
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
| **静音 开/关** | `M` | **v1.6 实测定案：进入效果时自动注入一次**（游戏原生静音 toggle，见 §7） |

> ⚠️ v1.6 实测：页面有 **Begin 欢迎屏** —— 打开裸 URL 先停在欢迎屏，**必须点击 Begin 才进画面**；
> 进入后 URL 变成带 hash 的场景地址（`https://slow-roads.pages.dev/#A3-f420a738@6.00`，每次不同），
> **再次打开该 hash URL 可直接进画面**（跳过 Begin）。相关机制见 §7、§8、§12。
>
> ⚠️ v1.7 实测更正：**Begin 屏本身也有一个 canvas**（内部 300×150、CSS 752×384，靠 CSS 放大）——
> 它就是 Svelte 应用的 `#splash-begin` 欢迎屏的背景（WebGL 已 init、AudioContext 已 running）。
> 所以 v1.6 的「Begin 阶段无 canvas ⇒ `probeReady` 恒 false」**是错的**，据此写的 `probeBegin()`
> 在真实页面上**恒返回 false**（见 §6.3 N3、§7、§9.15）。真正的 Begin 按钮是
> **`DIV#splash-begin`**（class `svelte-hmec5z`，`clickable=true`），应直接用 `getElementById` 精确定位。

---

## 二、现状核实（逐条打开源码确认，含 `file:line`）

| # | 事实 | 证据 |
|---|---|---|
| 1 | 效果枚举 **36 项**，`displayName` 是**硬编码中文**（无 i18n） | `data/model/AppSettings.kt:104-155`；显示点 `ui/components/VisualizerStage.kt:360` |
| 2 | 枚举 KDoc 写「34 套」，但实际 **36 项**——仅 `:100` KDoc 有误。⚠️ `:146` 照片墙注释写「第 36 个效果」**是对的**：`MOLECULE` 是第 35 个、`PHOTO_WALL` 是第 36 个枚举成员，**不要**把它当成计数错误的证据 | `AppSettings.kt:100` / `:146`（`:104-155` 实列 36 项） |
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
| 「OK 键是空的，拿来开自动驾驶」 | OK 在播放页 = 播放/暂停（`MediaKeyHandler.kt:48-51`） | 必须**拦截**，并接受「该效果下 OK 不能暂停」的副作用。⚠️ v1.5 补充：拦截后本效果下 OK 还承担**降级重试**（失败/超时/无 WebGL 态 → `load()`，§6.4.2-d）与**沉浸模式退出冲突**（`isImmersiveMode` 下 OK 本应退沉浸，`MediaKeyHandler.kt:45-47` / `MainActivity.kt:558-562`）两件事 |
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
│        · 失败/无 WebGL 时叠降级卡片（纯文字键位提示：OK = 重试 / ← = 切效果）
│
├── ② Canvas 效果层 ──────── SlowRoadsRenderer.draw 为空 ⇒ 全透明          ← 新增占位
└── ① 背景层 Box ─────────── palette.background                            ← 不变
```

> ⚠️ **没有「②' 旧效果淡出层」**（v1.5 更正）：`RendererSwapper.sync(theme, quality, crossfade, …)`
> 的 `crossfade` 恒为 `false`（`VisualizerStage.kt:166`），主题切换一律**硬切**，`prevRenderer` 恒为
> `null`。因此 §6.4.4 的「旧效果淡出」不存在，无需任何淡出处理。

⚠️ 前景层（歌词 / 歌曲信息 / 指示器）会**压在游戏画面上**——这是刻意的：
保持「音乐可视化」的一贯观感，用户仍能看到歌词与当前是第几个效果。

### 4.3 生命周期

| 时机 | 动作 |
|---|---|
| `theme` 变为 `SLOW_ROADS` | `SlowRoadsWebViewLayer` 进入组合树 → `remember(reloadKey)` 创建 host → `LaunchedEffect(host)` 内 `load()`（§6.4.3） |
| `theme` 变为其它 / 舞台 dispose | `SlowRoadsWebViewLayer` 离开组合树 → `DisposableEffect(host)` onDispose 触发 `host.destroy()`：`webView.stopLoading()` → `loadUrl("about:blank")` → `onPause()` → `destroy()` → 置空引用 |
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
| OK（沉浸模式下） | **不**播放/暂停，改为退出沉浸模式 | `MediaKeyHandler.kt:45-47` 返回 `false` → `MainActivity.kt:558-562` 置 `isImmersiveMode=false`。⚠️ 可视化页本身不设 `isImmersiveMode`（用 `showVisualizer` 隐藏系统栏，`MainActivity.kt:278-285`），但用户可能在可视化页进入沉浸模式 ⇒ 本效果拦截 OK 时**必须**同时处理此分支，否则该场景下 OK 既切不了自动驾驶也退不出沉浸 |
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
| M3 | `ui/components/VisualizerStage.kt` | `:336` 之后（**旧效果 Canvas `prevRenderer` 块之后**，前景 Column `:338` 之前；注：项目 `crossfade` 恒 false ⇒ `prevRenderer` 恒 null，此块实际不出现，位置照写以防日后改动） | 挂载 `SlowRoadsWebViewLayer`；`:227` 前置键位分支（实现细化见 §6.4.1，含 §9.11 滑动缓解） |
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

    /** v1.6 实测：页面有 Begin 欢迎屏，打开裸 URL 先停在欢迎屏，需点击 Begin 才进画面。
     *  进入后 URL 变成带 hash 的场景地址（每次不同）；再次打开该 hash URL 可跳过 Begin 直接进画面。 */
    const val BEGIN_URL: String = "https://slow-roads.pages.dev/#A3-f420a738@6.00"

    /** Begin 欢迎屏点击后到画面可交互的等待（毫秒），再开始探测 canvas 就绪 */
    const val BEGIN_DONE_DELAY_MS: Long = 2_500L

    /** 允许停留在内的 host 白名单（Begin 场景 hash 不改变 host） */
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

        /** 游戏原生静音 开/关 —— 游戏键 M（v1.6 实测定案，进入效果时自动注入一次） */
        TOGGLE_MUTE,
    }

    /** 动作 → Android 键码（`dispatchKeyEvent` 通道用） */
    fun keyCodeOf(action: Action): Int = when (action) {
        Action.TOGGLE_AUTO_DRIVE -> android.view.KeyEvent.KEYCODE_F
        Action.NEXT_SCENE -> android.view.KeyEvent.KEYCODE_E
        Action.PREV_SCENE -> android.view.KeyEvent.KEYCODE_Q
        Action.TOGGLE_CAMERA -> android.view.KeyEvent.KEYCODE_C
        Action.TOGGLE_MUTE -> android.view.KeyEvent.KEYCODE_M
    }

    /** 动作 → JS 注入用的键描述（`evaluateJavascript` 通道用） */
    fun keySpecOf(action: Action): KeySpec = when (action) {
        Action.TOGGLE_AUTO_DRIVE -> KeySpec(key = "f", code = "KeyF", keyCode = 70)
        Action.NEXT_SCENE -> KeySpec(key = "e", code = "KeyE", keyCode = 69)
        Action.PREV_SCENE -> KeySpec(key = "q", code = "KeyQ", keyCode = 81)
        Action.TOGGLE_CAMERA -> KeySpec(key = "c", code = "KeyC", keyCode = 67)
        Action.TOGGLE_MUTE -> KeySpec(key = "m", code = "KeyM", keyCode = 77)
    }

    /** Compose 键 → 动作；未绑定返回 `null`。⚠️ `TOGGLE_MUTE` **刻意不绑定遥控键**：静音只在进入效果时自动注入一次，不占用遥控（v1.6 定案） */
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
     *      v1.7 实测补充：`activeElement` 实测为 `DIV#main`（**不是 body**），所以③不是摆设；
     *   ② `keyCode` / `which` 用 `defineProperty` 显式覆盖 —— v1.7 实测更正：`KeyboardEvent`
     *      构造器里传 `keyCode` 在**桌面 Chromium 上是有效的**（传 70 返回 70，不是恒 0），
     *      因此 `defineProperty` 在主流内核上是**冗余**的；但**仍保留**（老内核/旧版 Chromium
     *      构造器可能不认 `keyCode`，覆盖后恒稳），文档语义从「必须」降级为「兜底」。
     *   ③ `code` 必须一并给（`event.code === 'KeyF'` 是现代游戏的常见写法）。
     *
     * ⚠️ **v1.8 降级**：本函数产出的合成事件 `isTrusted === false`（浏览器只读安全属性，JS 无法伪造）。
     *   实测游戏校验 `isTrusted` ⇒ 本函数在真实页面上**不生效**（物理键有效、合成键无效）。
     *   ⇒ 本函数**不再是主路径**，仅作老内核兜底（个别 WebView 可能不校验 isTrusted）；
     *   且使用时**必须回读结果确认**（`ValueCallback`），不盲发。主路径见 §7 / §6.4.2-d。
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

    /**
     * 游戏是否就绪：Begin 欢迎屏已被越过，且存在一个 CSS 尺寸达标的 canvas。
     *
     * ⛔ v1.7 实测更正：**不能只用 `width > 0 && height > 0`** —— Begin 欢迎屏本身
     *    就有一个 canvas（内部 300×150、CSS 752×384，靠 CSS 放大），该条件下恒返回
     *    true，会在用户还停在 Begin 屏时就发 F 键（白发）。必须同时排除 Begin 屏
     *    （`#splash-begin` 已消失）再判断 canvas 的 **CSS 尺寸**（`clientWidth/clientHeight`，
     *    不是内部 `width/height`），避免把「渲染分辨率小、靠 CSS 放大」误判成未就绪。
     */
    fun probeReady(): String = """
        (function(){
          try {
            var b = document.getElementById('splash-begin');
            if (b) return false;                       // 仍在 Begin 欢迎屏
            var c = document.querySelector('canvas');
            if (!c) return false;
            return c.clientWidth > 200 && c.clientHeight > 100;
          } catch (e) { return false; }
        })()
    """.trimIndent()

    /**
     * v1.6 实测新增：是否仍停在 **Begin 欢迎屏**（未进入游戏画面）。
     *
     * ⛔ v1.7 实测更正：**原判定（无 canvas 且 body/按钮含 "begin"）是错的** ——
     *    Begin 屏**有** canvas（Svelte 欢迎屏背景，WebGL 已 init、AudioContext 已 running），
     *    且已进画面时 body 文本仍含 "begin"（版本日志/菜单项）。按 v1.6 写法会**恒返回 false**，
     *    `clickBegin()` 永远走不到，画面卡死比 v1.5 更糟。
     *    **改为用精确 ID `#splash-begin` 判定**（实测真实按钮是 `DIV#splash-begin`，
     *    class `svelte-hmec5z`，`clickable=true`；Svelte 应用，非标准 button/a）。
     *    可见性判定用 `offsetParent !== null` + `visibility !== 'hidden'`，避免把已隐藏的
     *    残留节点算成 Begin。
     */
    fun probeBegin(): String = """
        (function(){
          try {
            var b = document.getElementById('splash-begin');
            if (!b) return false;
            var cs = getComputedStyle(b);
            return b.offsetParent !== null && cs.visibility !== 'hidden' && cs.display !== 'none';
          } catch (e) { return false; }
        })()
    """.trimIndent()

    /** v1.7 实测修正：点击 Begin 按钮（Begin 欢迎屏 → 进入游戏画面）。**改为精确 ID 定位** */
    fun clickBegin(): String = """
        (function(){
          try {
            var b = document.getElementById('splash-begin');
            if (b && b.click) { b.click(); return true; }
            // 兜底：ID 定位失败时退回「文本含 begin」遍历（页面改版后可能 ID 变了）
            var els = document.querySelectorAll('button, [role="button"], a, div');
            for (var i = 0; i < els.length; i++) {
              var txt = (els[i].innerText || els[i].textContent || '').toLowerCase();
              if (txt.indexOf('begin') >= 0 && els[i].click) { els[i].click(); return true; }
            }
            var body = document.body;
            if (body && body.click) { body.click(); return true; }
            return false;
          } catch (e) { return false; }
        })()
    """.trimIndent()

    /**
     * 游戏原生静音：**v1.6 实测定案 —— 发 `M` 键**（`sendKey(TOGGLE_MUTE)`）即可，比 JS patch 可靠。
     * ⚠️ `M` 是 toggle，只在进入效果时注入**一次**（§7）。
     * 本函数降级为**兜底**（M 键在某些老内核上可能不触发），正常路径不必调用。
     * ⚠️ **v1.8 更正**：M 键属键盘事件，合成发 M 与合成 F/E/C 一样**被游戏忽略**（校验 `isTrusted`），
     *    所以「发 M」在主路径上必须走**通道① `dispatchKeyEvent`**（Chromium 转成 `isTrusted=true`）；
     *    本函数（AudioContext patch）**不依赖键盘事件**，仍作静音最终兜底（§7、§9.16）。
     *
     * ⛔ v1.7 实测更正：**页面没有 `<audio>/<video>` 元素**（实测 `media: []`）——游戏音频走
     *    **Web Audio API**（实测 `AudioContext` 支持、state = `running`）。所以下面「遍历
     *    audio/video 设 muted/volume」那一段对游戏本身是**空操作**（保留无害，覆盖个别页面
     *    可能出现的 `<video>` 片头），**真正生效的只有 AudioContext patch**。
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
// N4 —— visualizer/slowroads/SlowRoadsWebViewHost.kt（接口骨架；完整实现细化见 §6.4.2）
package com.nasmusic.tv.visualizer.slowroads

/** 宿主状态（驱动 N6 的降级 UI） */
enum class SlowRoadsState { LOADING, READY, NO_WEBGL, LOAD_FAILED, TIMEOUT }

class SlowRoadsWebViewHost(
    context: android.content.Context,
    private val onStateChange: (SlowRoadsState) -> Unit
) {
    /** 供 `AndroidView` 使用的 View。**必须不可聚焦**，否则舞台收不到遥控按键 */
    val view: android.webkit.WebView

    /** v1.6：是否已注入静音（M 是 toggle，只注入一次，见 §7） */
    private var muted: Boolean = false

    /** v1.6：是否已点击 Begin（欢迎屏），避免重复点击 */
    private var beginClicked: Boolean = false

    fun load()
    /** 发键（v1.8 更正）：先 `dispatchKeyEvent`（主路径，Chromium 转成 isTrusted=true），再 `evaluateJavascript` 老内核兜底 */
    fun sendAction(action: SlowRoadsKeyMapper.Action)
    /** 自动开启自动驾驶（Begin 引导 + 探测就绪 + 静音 + 重试），用户一旦手动按键即取消本流程（§7 / §6.4.2-c） */
    fun scheduleAutoDrive()
    fun cancelAutoDrive()
    fun reload()
    fun destroy()
}
```

```kotlin
// N6 —— ui/components/SlowRoadsWebViewLayer.kt（接线骨架；完整实现细化见 §6.4.3）
@Composable
fun SlowRoadsWebViewLayer(
    /** 由舞台转发的动作（`null` = 本帧无动作）；非空即发键并取消自动巡航 */
    pendingAction: SlowRoadsKeyMapper.Action?,
    onActionConsumed: () -> Unit,
    modifier: Modifier = Modifier
)
// v1.3 定案：降级卡片键位化（OK=重试 / ←=切效果，§6.4.6-5 与 §6.4.6-8），不再需要 onFallbackToPrevTheme
```

---


### 6.4 可开发级实现细化（v1.3 补全）

> 目标：实现者照本节敲代码时**不需要再做任何设计决策**。更早的小节与本节冲突时，**以本节为准**。

#### 6.4.1 `VisualizerStage` 集成（M3 的具体改法）

**(a) 新增组合态**（舞台函数体内，与既有 `toastVisible` 等并列）：

```kotlin
// 「公路漫游」独占键位 → 待发动作（onPreviewKeyEvent 写入，WebView 层消费后清空）
val slowRoadsPendingAction = remember { mutableStateOf<SlowRoadsKeyMapper.Action?>(null) }
var lastSlowRoadsKeyMs by remember { mutableLongStateOf(0L) }
```

**(b) 键位前置分支** —— `onPreviewKeyEvent` 内、**既有 `when (e.key)` 之前**；仅 `theme == SLOW_ROADS` 生效，其余主题一字不改：

```kotlin
.onPreviewKeyEvent { e ->
    if (theme == VisualizerTheme.SLOW_ROADS) {
        val action = SlowRoadsKeyMapper.mapKey(e.key)
        if (action != null) {
            if (e.type == KeyEventType.KeyDown) {
                // 长按只认第一次：repeat 消费但不发（§5.2 防连发）
                if (e.nativeKeyEvent.repeatCount == 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastSlowRoadsKeyMs >= SlowRoadsConfig.ACTION_DEBOUNCE_MS) {
                        lastSlowRoadsKeyMs = now
                        slowRoadsPendingAction.value = action
                    }
                }
            }
            return@onPreviewKeyEvent true   // Down/Up 都消费：防 OK 穿透成播放/暂停
        }
        // ←/→/BACK 的 mapKey 为 null ⇒ 不 return，落到下方既有 when（切效果 / 退出可视化）
        // —— T4.4 负向自证依赖这一行为，不得顺手吞掉
    }
    if (e.type == KeyEventType.KeyDown) {
        when (e.key) { /* ……既有分支原样保留…… */ }
    } else {
        false
    }
}
```

**(c) 层挂载** —— 在旧效果 Canvas 块（`VisualizerStage.kt:306-336` 的 `prevRenderer` 块，v1.5 加注：项目 `crossfade` 恒 false ⇒ 此块实际不出现）之后、③ 前景 Column（`:338`）之前：

```kotlin
if (theme == VisualizerTheme.SLOW_ROADS) {
    SlowRoadsWebViewLayer(
        pendingAction = slowRoadsPendingAction.value,
        onActionConsumed = { slowRoadsPendingAction.value = null },
        modifier = Modifier.fillMaxSize()
    )
}
```

**(d) 手机滑动缓解（§9.11）** —— 在既有 `detectHorizontalDragGestures` 的 `pointerInput` **之前**，再挂一个 Initial pass 版本：

```kotlin
.pointerInput(theme == VisualizerTheme.SLOW_ROADS) {
    if (theme != VisualizerTheme.SLOW_ROADS) return@pointerInput
    val threshold = 80.dp.toPx()               // 与既有滑动阈值一致
    awaitEachGesture {
        var total = 0f
        var fired = false
        while (true) {
            val evt = awaitPointerEvent(PointerEventPass.Initial)
            total += evt.changes.firstOrNull()?.positionChange()?.x ?: 0f
            if (!fired && abs(total) > threshold) {
                if (total > 0f) onPrevTheme() else onNextTheme()
                // Initial pass 先于子节点：此处消费后 WebView 拿不到完整手势
                evt.changes.forEach { it.consume() }
                fired = true
            }
            if (evt.changes.all { !it.pressed }) break
        }
    }
}
```

> `pointerInput` 的 key 绑定主题开关：切到其它效果时自动失效回收；既有 Main pass 手势仅服务其余 36 套。
>
> ⚠️ 当前 key 为布尔值（`theme == SLOW_ROADS`），Compose `pointerInput` 在 key 变化时取消并重启手势检测——这恰好是期望行为。
> 若未来有第二个需要 Initial pass 的效果，布尔 key 只有 true/false 两种状态会冲突，届时应改为以 `theme` 本身作为 key。

#### 6.4.2 `SlowRoadsWebViewHost` 实现细化（N4）

**(a) 唯一 WebView 构造点**（G24 只需扫本文件与 Layer）：

```kotlin
@SuppressLint("SetJavaScriptEnabled")
private fun createView(context: Context): WebView = WebView(context).apply {
    isFocusable = false                 // ⛔ 缺了切不动效果（键盘焦点归舞台）
    isFocusableInTouchMode = false      // ⛔ G24 两条都要扫到
    setBackgroundColor(Color.BLACK)     // 防加载期白闪
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        // ⚠️ v1.5 加注：`databaseEnabled`（WebSQL）自 API 19 起已被移除，该开关实际无效；
        //    保留无害，但**不要**指望它给页面提供本地库。真正的持久化走 `domStorageEnabled`。
        useWideViewPort = true
        loadWithOverviewMode = true
        mediaPlaybackRequiresUserGesture = false   // 游戏音频/交互不依赖用户手势
        cacheMode = WebSettings.LOAD_DEFAULT
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
    }
    webViewClient = client
}
```

**(a‑2) WebChromeClient（开发调试用，发布时可移除或降级）**

```kotlin
// 开发阶段：桥接 WebView 内部 JS 日志到 Logcat，便于排查键位注入 / WebGL 探测失败
// 发布时：移除或仅保留 ERROR 级（§9.13）
view.webChromeClient = object : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        Log.d("SlowRoadsWebView", "${consoleMessage.message()} — ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}")
        return true
    }
}
```
```

**(b) `WebViewClient` 行为表**

| 回调 | 行为 |
|---|---|
| `shouldOverrideUrlLoading` | `Uri.parse(url).host == ALLOWED_HOST` → `false`（站内放行）；否则 `true` **拦截**并 `Log.w`（不弹外部浏览器，防止带出 App）。⚠️ v1.5 加注：遥控器上用户无感知，建议同时叠一条一次性 Toast「已拦截外部链接」 |
| `onPageFinished` | 置 `pageFinished = true`；`MUTE_BY_DEFAULT` 时**先 `sendAction(TOGGLE_MUTE)`（发 `M`）+ 紧跟幂等 JS `mute()` 兜底**，置 `muted=true`（见 (c) 状态机）。⚠️ v1.6 时序：静音**提前到 onPageFinished**，不等画面 ready —— 理由①静音是**页面级/全局 toggle**（`SlowRoadsScript.mute()` 直接操作 `audio/video/AudioContext`，不依赖游戏是否进入场景），早静音可避免游戏音效先响起来；理由②**无论 M 是否在 Begin 阶段生效，JS `mute()` 兜底都能保证最终静音**（幂等：`muted=true` 重复设无害、`AC.__nasmuted` 防重复 patch）。⚠️ **F 键仍必须等画面 ready 后发**（§7）：F 是 toggle，Begin 阶段发等于白发（游戏键盘可能未就绪），多发一次就抵消。⚠️ **v1.8 更正**：M 键属键盘事件，合成发 M 无效（游戏校验 `isTrusted`），所以 `sendAction(TOGGLE_MUTE)` 内部**必须走通道① `dispatchKeyEvent`**；JS `mute()` 兜底不受影响（AudioContext patch，不依赖键盘事件），仍是静音最终保证（§7、§9.16） |
| `onReceivedError` | 仅 `request.isForMainFrame` 时转 `LOAD_FAILED` 并取消所有挂起 Runnable（子资源失败忽略）。⚠️ 该回调**只覆盖网络层错误**，不覆盖 HTTP 4xx/5xx（见下一行） |
| `onReceivedHttpError` | v1.5 定案：`request.isForMainFrame` **且** `errorCode >= 400` 时**也转 `LOAD_FAILED`** + 取消挂起 Runnable。⚠️ 原写法「只记日志」是错的：`onReceivedError` **不**覆盖 HTTP 错误，仅记日志会让 404/500 走 `onPageFinished` → WebGL 探测 → 页面无 canvas → 误判 `NO_WEBGL`，把「页面挂了」归因成「设备不支持 3D」。子资源 HTTP 错误仍只记日志 |
| `onRenderProcessGone` | ⚠️ v1.5 更正：本回调是 **API 26** 引入，在目标设备 Android 5.1（SDK 22）上**永远不会被调用**。实现时须 `@RequiresApi(26)` + `if (Build.VERSION.SDK_INT >= 26)` 守卫，作为**高版本兜底**保留（转 `LOAD_FAILED` + 取消挂起 Runnable + 返回 `true`）。对 Android 5.1 的渲染进程崩溃**无效**，该设备只能靠 `onReceivedError` + `LOAD_TIMEOUT_MS` 兜底（§9.12） |

**(c) 状态机**（`SlowRoadsState` 迁移，全部发生在主线程；v1.6 起含 Begin 分支）

```
LOADING ──(LOAD_TIMEOUT_MS 到期仍未 finish)──▶ TIMEOUT（不发键，UI 提示「按 OK 重试」）
LOADING ──(onReceivedError 主框架，网络层错误)──▶ LOAD_FAILED（重试 = 按 OK）
LOADING ──(onReceivedHttpError 主框架且 >=400)──▶ LOAD_FAILED   ← v1.5 新增：见 (b) 行为表
LOADING ──(onPageFinished → [v1.6] 若 MUTE_BY_DEFAULT 且 !muted：先发 M（TOGGLE_MUTE）＋ JS mute() 兜底，置 muted=true → probeWebGL)
        ├─"false" 或 WEBGL_PROBE_TIMEOUT_MS 内无回调 ──▶ NO_WEBGL（静音已注入，无害）
        └─"true" ── 等 AUTO_DRIVE_DELAY_MS ──▶ probeBegin / probeReady 自轮询：
              [v1.7 修正] probeBegin = true（仍停在 Begin 欢迎屏）──▶ clickBegin() ──▶
                      等 BEGIN_DONE_DELAY_MS ──▶ 回到 probeReady
                      ⚠️ v1.7 实测：Begin 屏**有** canvas（#splash-begin 背景），probeReady 必须先
                      排除该元素再判 canvas CSS 尺寸，否则在 Begin 屏就会白发 F
              ready ──▶ 发一次 F（v1.8：通道① 主路径；通道② 仅老内核兜底）──▶ READY
              未 ready 且累计等待 ≤ AUTO_DRIVE_MAX_WAIT_MS ──▶ 间隔 READY_PROBE_INTERVAL_MS 再探
              累计等待 > AUTO_DRIVE_MAX_WAIT_MS ──▶ **转 READY（不发 F）**
任意状态 ──(用户手动 sendAction)──▶ 取消探测链（userTookOver = true），此后只听用户
```

> ⚠️ v1.7 实测更正（`sr-begin-check v1`，2026-09-24）：**v1.6 的前提是错的** —— Begin 欢迎屏
> **本身就有 canvas**（内部 300×150、CSS 752×384，靠 CSS 放大，WebGL 已 init、AudioContext 已 running）。
> 所以 v1.6 的「`probeReady` 在 Begin 阶段恒 false」不成立；按 v1.6 写的 `probeBegin()`（要求「无 canvas」
> 且 body/按钮含 "begin"）在真实页面上**恒返回 false**，`clickBegin()` 永远走不到 —— 比 v1.5 更糟
> （v1.5 至少会等满超时转 READY，v1.6 是卡死在 Begin 屏）。
>
> ⛔ v1.7 的修正：`probeBegin` 改用**精确 ID `#splash-begin`**（实测真实按钮是 `DIV#splash-begin`、
> class `svelte-hmec5z`、`clickable=true`），`probeReady` 必须**先排除 `#splash-begin`** 再判 canvas
> 的 **CSS 尺寸**（`clientWidth/clientHeight`，不是内部 `width/height`）。两处判定都见 §6.3 N3。
> 已进画面时 body 文本仍含 "begin"（版本日志/菜单项）——所以**绝不能**只靠 bodyText 判 Begin。
>
> ⚠️ v1.6 静音时序：**静音提前到 `onPageFinished`**（发 `M` + JS `mute()` 兜底），**不等画面 ready**——
> `mute()` 直接操作 `audio/video/AudioContext`，与游戏是否进入场景无关，早静音可避免游戏音效先响起来；
> 且 JS 兜底幂等，即使 M 在某个阶段未生效也能保证最终静音。**F 键仍必须等画面 ready 后发**（§7）。
> ⚠️ v1.7 实测：游戏音频走 **WebAudio**、页面**无 `<audio>/<video>` 元素**（实测 `media: []`），
> 所以 `mute()` 里「遍历 audio/video」那段对游戏本身是空操作，真正生效的是 AudioContext patch（§6.3 N3）。

> v1.3 定案：**「自动巡航等待超时」转 `READY` 而非 `TIMEOUT`** —— 页面此时已可交互，发键通道完全可用，用户按 OK 手动开即可；`TIMEOUT` 语义收窄为「页面加载超时」（OK = 重载）。探测超时兜底：`evaluateJavascript` 回调在 `WEBGL_PROBE_TIMEOUT_MS` 内未到 ⇒ 判 `NO_WEBGL`（老内核可能连回调都不给）。

**(d) 发键时序**（`sendAction`，同时承担「失败态 OK = 重试」的分流；v1.8 更正：通道①为主 + 通道②兜底）

```kotlin
fun sendAction(action: SlowRoadsKeyMapper.Action) {
    if (destroyed) return
    if (state == SlowRoadsState.LOAD_FAILED || state == SlowRoadsState.TIMEOUT ||
        state == SlowRoadsState.NO_WEBGL) {
        load()                                   // v1.3/v1.5 定案：三个降级态按 OK 都是重试（§6.4.6-8）
        return                                   // NO_WEBGL 可能是 HTTP 错误误判（§9.14），必须允许重试
    }
    if (state == SlowRoadsState.LOADING) return  // v1.5 定案：LOADING 不发键，待 READY（见下方注释）
    userTookOver = true                          // 页面可用才视为用户接管
    mainHandler.removeCallbacksAndMessages(null) // 探测链整链取消
    val kc = SlowRoadsKeyMapper.keyCodeOf(action)
    // v1.8 定案：通道① 是唯一可靠主路径（走系统输入管线，Chromium 转成 isTrusted=true 的真实 DOM 事件）
    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, kc))
    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, kc))
    // v1.8 降级：通道② 不再作主路径（游戏校验 isTrusted，合成事件恒 false、实测无效），
    // 仅作老内核兜底，且必须回读确认（ValueCallback 里判断返回值/异常），不盲发。
    // 若后续确认本项目所有目标设备上通道①都能填出 event.code，可考虑彻底移除通道②。
}
```

> ⚠️ v1.8 实测：合成键 F/E/C 在真实页面（`#A3-f420a738@6.00`、`activeElement=DIV#main`）上全部无效，
> 物理键盘有效；已排除焦点干扰（脚本内 `window.focus()`+`canvas.focus()`）。根因：游戏校验 `isTrusted`，
> 合成事件恒 `false` 无法伪造。通道②**不再是「缺一不可」**。

> **三态分流总表（v1.5 定案，覆盖 §6.4.2-d / §6.4.6-8）**：
>
> | `state` | OK 键行为 | 理由 |
> |---|---|---|
> | `LOADING` | **no-op**（不发键、不接管） | 页面未就绪，发键可能被缓存或与自动巡航 F 竞态抵消；待 `READY` 后再执行 |
> | `LOAD_FAILED` / `TIMEOUT` | `load()` 重试 | v1.3 定案 |
> | `NO_WEBGL` | `load()` 重试 | v1.5 定案：该态可能是 HTTP 错误误判（§9.14），必须允许重试 |
> | `READY` | 通道① 为主（`dispatchKeyEvent`）+ 通道② 兜底（`evaluateJavascript`，须回读确认） | v1.8 更正：通道② 已降级，见 §7、§9.16 |
>
> ⚠️ 降级态（LOAD_FAILED / TIMEOUT / NO_WEBGL）按 OK 转为 `load()`（重试）——此处 `TOGGLE_AUTO_DRIVE`
> 的语义被复用为"重试"。代码路径清晰（前置分支只管映射、Host 内部分流），但实现时须在此处分流处加
> 注释说明语义复用，避免后续维护者误删。

**(e) `destroy()` 顺序**（⛔ 顺序敏感）：

```kotlin
fun destroy() {
    if (destroyed) return
    destroyed = true
    mainHandler.removeCallbacksAndMessages(null)
    view.stopLoading()
    view.loadUrl("about:blank")
    view.onPause()      // 停页面定时器/动画
    view.destroy()      // 必须在 View 从窗口移除之后调用（§6.4.3 的 key(reloadKey) + DisposableEffect 保证）
}
```

> 另暴露 `onPause() / onResume()`（透传 WebView），由 Layer 挂 Lifecycle（§6.4.3 (e)）。

#### 6.4.3 `SlowRoadsWebViewLayer` 实现细化（N6）

```kotlin
// imports（节选）：androidx.compose.runtime.{Composable, DisposableEffect, LaunchedEffect,
//   getValue, setValue, key, mutableIntStateOf, mutableStateOf, remember}
//   androidx.compose.ui.viewinterop.AndroidView / androidx.compose.ui.platform.LocalContext
//   androidx.compose.ui.platform.LocalLifecycleOwner          // ⛔ v1.5 定案：用 compose-ui 的
//   androidx.lifecycle.{Lifecycle, LifecycleEventObserver}    //   （见下方 (f) 依赖说明）

@Composable
fun SlowRoadsWebViewLayer(
    pendingAction: SlowRoadsKeyMapper.Action?,
    onActionConsumed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current          // ⛔ 即 Activity，WebView 必须用它（§4.3）
    var state by remember { mutableStateOf(SlowRoadsState.LOADING) }
    var reloadKey by remember { mutableIntStateOf(0) }

    val host = remember(reloadKey) { SlowRoadsWebViewHost(context) { state = it } }

    // 动作消费：非空即发键并清空（手动按键同时天然取消自动巡航；失败态由 Host 内部转重试）
    LaunchedEffect(pendingAction) {
        pendingAction?.let { host.sendAction(it); onActionConsumed() }
    }

    DisposableEffect(host) {
        onDispose { host.destroy() }            // reloadKey 换 host 时，旧实例由此销毁
    }

    // ⛔ 必须包 key(reloadKey)：AndroidView 的 factory 每个组合节点只跑一次，
    //    不包的话重试时还是旧 WebView 挂在树上
    key(reloadKey) {
        AndroidView(factory = { host.view }, modifier = modifier.fillMaxSize())
    }

    LaunchedEffect(host) { host.load() }

    // 前后台：App 退后台暂停 WebView 定时器/渲染，回前台恢复
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> host.onPause()
                Lifecycle.Event.ON_RESUME -> host.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    when (state) {                              // 降级卡片：压在 WebView 之上、前景层之下；无按钮（§6.4.6-5）
        SlowRoadsState.NO_WEBGL -> FallbackCard("无法加载或当前设备浏览器内核不支持 3D｜按 OK 重试｜按 ← 切换效果")
        SlowRoadsState.LOAD_FAILED -> FallbackCard("网页加载失败｜按 OK 重试｜按 ← 切换效果")
        SlowRoadsState.TIMEOUT -> FallbackCard("加载超时｜按 OK 重试｜按 ← 切换效果")
        else -> Unit                            // LOADING（页面自带加载态）/ READY
    }
}
```

**(f) ⛔ 依赖说明（v1.5 定案，照此写否则 C3 编译失败）**

- `LocalLifecycleOwner` 用 **`androidx.compose.ui.platform.LocalLifecycleOwner`**，**不要**用
  `androidx.lifecycle.compose.LocalLifecycleOwner`。
  - 原因：`app/build.gradle.kts:170-172` **没有** `lifecycle-runtime-compose`，compileClasspath
    （已实测 `deps-debugCompileClasspath.txt`）也没有该工件 ⇒ 后者 import 会直接 `Unresolved reference`。
  - 前者由 `androidx.compose.ui:ui`（BOM `2024.02.00` → compose-ui 1.6.1）提供，**无需新增依赖**。
  - `Lifecycle` / `LifecycleEventObserver` 来自 `lifecycle-common`（经 `lifecycle-runtime-ktx` 传递，
    编译期可用，`deps` 树中已有）。
  - 这正是项目 skill `android-dep-api-verify` 反复强调的「**传递依赖 ≠ compile 期可用**」类坑，
    照抄本小节即可规避。

设计定案（实现者不得再改）：
- **重试 = `reloadKey++`**？**否** —— v1.3 已改为「失败态按 OK → Host 内部 `load()`」，卡片不放按钮、`reloadKey` 仅作保留兜底；`key(reloadKey)` 包裹 `AndroidView` 是**必须**的防御（factory 只跑一次的坑）。
- 卡片为纯文字 `FallbackCard`（效果名 Toast 同款样式：`drawBehind` 圆角底），不抢焦点、不参与按键。

#### 6.4.4 与主题切换的交互

- WebView 层**不参与任何 alpha 动画**：切到本效果为硬切出现（层序 ②b 在最上），切走时 WebView 直接
  销毁，新效果从零开始。**没有「旧效果淡出」** —— 项目 `swapper.sync(theme, quality, crossfade, …)`
  的 `crossfade` 恒为 `false`（`VisualizerStage.kt:166`），主题切换一律硬切，`prevRenderer` 恒为 `null`
  （`VisualizerStage.kt:144`），不存在 §4.2 旧版图画里的「②' 淡出层」。视觉上「旧画面瞬间被游戏画面
  覆盖」，可接受且零额外成本。
- 画质档变化：仅占位渲染器 `onEnter`（§4.3），WebView 层不动。

#### 6.4.5 门禁用例名单（G22/G23/G24 的测试方法名，写测试时照抄）

**G22 `SlowRoadsKeyMapperTest`（约 15 例）**
- `ok_and_enter_map_to_toggle_auto_drive`
- `up_maps_to_next_scene` / `down_maps_to_prev_scene` / `menu_maps_to_toggle_camera`
- `mute_action_exists_and_maps_to_key_m`（v1.6 新增：`TOGGLE_MUTE` → `keyCodeOf` = `KEYCODE_M`、`keySpecOf` = `KeyM`/`77`）
- `key_code_of_matches_key_spec_for_every_action`（两通道不能打架，逐项相等，**含新增的 TOGGLE_MUTE**）
- `key_spec_code_starts_with_key`
- `left_right_and_back_map_to_null`（**负向自证**）
- `every_action_key_is_printable_single_char`
- `key_specs_are_unique_per_action`
- `mute_is_not_bound_to_any_remote_key`（v1.6 新增：`mapKey` 对四个方向键 + OK + MENU 都不返回 `TOGGLE_MUTE`，静音只在进入时自动注入一次，不占遥控）

**G23 `SlowRoadsScriptTest`（约 18 例）**
- `send_key_dispatches_to_window_and_document`（含负向自证：只发 window 的样本必须被判出）
- `send_key_overrides_key_code_via_define_property`（含负向自证：仅构造器传 keyCode 的样本必须被判出）
- `send_key_includes_code_field`
- `probe_webgl_covers_webgl2_webgl_experimental`
- `escaping_survives_quote_and_backslash`（**负向自证**：未转义样本必须被判出）
- `all_scripts_are_non_blank_constants`
- `probe_begin_detects_begin_screen`（v1.6 新增：含 "begin" 文本/按钮 且 无 canvas 的样本必须返回 true）
- `probe_begin_negative_when_canvas_exists`（v1.6 新增：已进入游戏（有 canvas）时 `probeBegin` 必须返回 false，防止误判）
- `probe_begin_negative_when_no_begin_text`（v1.6 新增：无 canvas 但无 "begin" 文本 ⇒ false，防止把「页面已挂」误判成 Begin）
- `click_begin_returns_true_on_begin_button`（v1.6 新增：命中含 "begin" 的按钮）
- `click_begin_is_idempotent`（v1.6 新增：重复调用不抛错、返回布尔）
- `mute_script_is_still_present_as_fallback`（v1.6 新增：M 键已定为主路径，但 JS `mute()` 仍必须保留为兜底）

**G24 `SlowRoadsWebViewGuardTest`（约 11 例，源码扫描）**
- `webview_construction_requires_is_focusable_false_within_32_lines`
- `webview_construction_requires_is_focusable_in_touch_mode_false`
- `sample_without_focusable_false_is_detected`（**负向自证**）
- `on_render_process_gone_is_version_guarded`（v1.5 新增：覆写 `onRenderProcessGone` 处 32 行内必须有 `SDK_INT >= 26` 守卫；负向自证：无守卫的样本必须被判出）—— 防在 Android 5.1 上报 `VerifyError` / 崩溃
- `slow_roads_renderer_draw_body_is_empty`
- `factory_when_covers_slow_roads`
- `scan_is_not_vacuous_at_least_three_files`（空转断言）

#### 6.4.6 边界定案（实现者不得偏离）

1. **切歌不重建 host**（层 key 不含 song）——漫游画面跨歌持续。
2. **前后台**：`LifecycleEventObserver` ON_PAUSE/ON_RESUME → `webView.onPause()/onResume()`。
3. **旋转/配置变化**：MainActivity `configChanges` 已含 orientation，Activity 不重建，层不销毁。
4. **退出可视化 / 切走主题**：`DisposableEffect(host)` destroy（§4.3）。
5. **降级卡片不放可聚焦按钮**：按 OK 重试（Host 内部分流）+ 按 ← 切换效果；语义与 v1.2 裁决 ④ 一致（保留效果、明确提示），规避 TV 焦点流转复杂度。
6. **全主线程**：Host 的 Handler 用主 Looper；`evaluateJavascript` 回调亦在主线程，无需切换。
7. **焦点**：WebView 与卡片永不持焦，舞台 Box 始终持有（G24 守护）。
8. **`sendAction` 三态分流（v1.5 定案）**：`LOAD_FAILED` / `TIMEOUT` / `NO_WEBGL` 三个降级态下按 OK 一律
   = `load()` 重试（§6.4.2-d）；`LOADING` 态 no-op；页面可用后才真正发键。⚠️ v1.4 的
   「`NO_WEBGL` 下按 OK 无意义」已废除——`NO_WEBGL` 可能是 HTTP 错误误判（§9.14），必须允许重试。
   因此 §6.4.3 的三张降级卡片**都**带「按 OK 重试」提示。

---

## 七、发键、Begin 引导与自动巡航：为什么不是「延时 3 秒发一次 F」

原方案写 `onPageFinished` 后 `postDelayed(3000)` 发一次 `F`。实测风险是**页面 finish ≠ 游戏就绪**
（Three.js 场景/地形生成可能十几秒），发早了等于白发，且 F 是 toggle —— 多发一次就**抵消**了。

采用**探测 + Begin 引导 + 竞态保护**（v1.6 实测修订、v1.7 更正）：

1. `onPageFinished` → **[v1.6] 若 `MUTE_BY_DEFAULT` 且未 muted：发一次 `M`（`TOGGLE_MUTE`，v1.8 更正：走通道① `dispatchKeyEvent`）+ 紧跟 JS
   `mute()` 兜底，置 `muted=true`**，然后起 `WEBGL` 探测；
2. 无 WebGL ⇒ 直接 `NO_WEBGL` 降级，**不再尝试发键**（静音已注入，无害）；
3. 有 WebGL ⇒ 等 `AUTO_DRIVE_DELAY_MS` 后跑 `probeBegin()` + `probeReady()`：
   - **probeBegin = true**（仍停在 Begin 欢迎屏）⇒ `clickBegin()`，等 `BEGIN_DONE_DELAY_MS` 后回到
     探测（⚠️ v1.7 更正：Begin 屏**有** canvas，所以必须用 `#splash-begin` 精确判 Begin；
     用 v1.6 的「无 canvas」判法会**恒 false、永远走不到 clickBegin**，画面卡死比 v1.5 更糟）；
   - ready（已进入游戏画面）⇒ 发一次 `F`，置 `READY`；
   - 未 ready 且非 Begin ⇒ 每 `READY_PROBE_INTERVAL_MS` 再探，直到 `AUTO_DRIVE_MAX_WAIT_MS` 超时——
     超时**不**发键，转 `READY`（发键通道可用，用户按 OK 手动开；v1.3 定案，`TIMEOUT` 收窄为「页面加载超时」，见 §6.4.2-c）；
4. **任何一次用户手动按键** ⇒ `cancelAutoDrive()`，后续只听用户的。

⚠️ v1.7 实测（`sr-page-check v1` + `sr-begin-check v1`）：Begin 欢迎屏本身就有 canvas
（内部 300×150、CSS 752×384，靠 CSS 放大，WebGL 已 init、AudioContext 已 running）——它是
Svelte 欢迎屏的背景。因此「Begin 阶段 `probeReady` 恒 false」**不成立**；`probeReady` 必须
**先排除 `#splash-begin` 再判 canvas 的 CSS 尺寸**（不是内部 `width/height`），否则会在用户
还停 Begin 屏时**白发 F**。真正可点的 Begin 按钮是 `DIV#splash-begin`（class `svelte-hmec5z`）。

静音方案（v1.6 实测定案、v1.8 更正）：
- **主路径**：`onPageFinished` 后发一次游戏原生静音键 `M`（`TOGGLE_MUTE`）。这是**最可靠**的做法——
  游戏自己处理静音，不依赖 WebAudio 内部状态，也比 patch `createGain` 稳。
  ⚠️ **v1.8 更正**：发 M 必须走**通道① `dispatchKeyEvent`**（M 属键盘事件，合成发 M 与合成 F/E/C
  一样被游戏忽略，§9.16）。
- **兜底**：紧跟 `evaluateJavascript(SlowRoadsScript.mute())`。因为 `M` 是 **toggle**，若页面已静音
  再发反而开声 ⇒ 必须 M 之后紧跟幂等的 JS `mute()`（`muted=true` 重复设无害、`AC.__nasmuted`
  防重复 patch），保证状态最终是「静音」。⚠️ JS `mute()` 是 AudioContext patch、**不依赖键盘事件**，
  即使通道① 在某内核失效也能兜底静音（§9.16）。
- ⚠️ **F 键必须等画面 ready 后发**（Begin 阶段发 F 无效）；静音则**不必**等 ready ——
  `mute()` 直接操作 `audio/video/AudioContext`，与是否进游戏无关，早静音可避免游戏音效先响起来。
  ⚠️ v1.7 实测：游戏音频走 **WebAudio**、页面**无 `<audio>/<video>` 元素**（实测 `media: []`），
  所以真正生效的是 AudioContext patch；`mute()` 里的 audio/video 循环对游戏本身是空操作（保留无害）。

场景 hash 缓存优化（v1.6 实测，可选项）：
- 每次进入裸 URL 都要点 Begin。用户上次进入后 URL 变成 `…/#A3-f420a738@6.00`（每次不同），
  再次打开该 hash URL 可**跳过 Begin 直接进画面**。
- 可考虑用 `DataStore` 存上次的完整 hash URL，下次直接 `loadUrl` 它 ⇒ 免点 Begin、也更快。
- ⚠️ 风险：hash 可能对应特定场景状态，且服务端可能过期失效；作为**优化**，不做也完全可用
  （`clickBegin()` 已能自动推进），属 §9.15 待验证项。

发键通道（v1.8 实测更正：**双通道策略作废**，只留通道①为主路径）：

```
通道① view.dispatchKeyEvent(KeyEvent(ACTION_DOWN, KC)) + ACTION_UP   ← 唯一主路径
        └─ 优点：走系统输入管线，Chromium 会把原生 KeyEvent 转成 isTrusted=true 的真实 DOM 事件
        └─ ⚠️ v1.8 待验证：Android WebView 是否真能填出 event.code（KeyF/KeyM）；
          老内核可能只填 keyCode 不填 code，而现代游戏（Three.js/Svelte 系）常用 event.code。
          桌面 Chrome 验不出这点，必须真机（尤其 Android 5.1 电视）测
通道② view.evaluateJavascript(SlowRoadsScript.sendKey(spec), null)   ← 已降级为老内核兜底
        └─ ❌ v1.8 实测（浏览器逐键 F/E/C）：合成 KeyboardEvent 在真实页面上**完全不被游戏响应**。
          游戏校验 `isTrusted`（合成事件恒 false，无法伪造），物理键有效、合成键无效。
        └─ 保留理由：个别老 WebView 内核可能不校验 isTrusted，此时通道②仍能兜底；
          但**必须回读结果确认**（不能盲发），且**不得作为主路径**。
        └─ 结论：通道②不再是「缺一不可」，本方案实际可用的是通道①
```

> ⚠️ v1.8 实测（浏览器 console，已进画面 `#A3-f420a738@6.00`，`activeElement=DIV#main`）：
> 物理键盘按 `f` → 车动；合成 F（`isTrusted=false`，已发 window/document/activeElement/canvas 四处，
> 且脚本内先 `window.focus()`+`canvas.focus()`）→ **车不动**；合成 E、合成 C 同（均无反应）。
> 已排除焦点干扰（脚本已把焦点还给画面，焦点实测为 `DIV#main`），故根因是**游戏校验 `isTrusted`**。
> `isTrusted` 是浏览器只读安全属性，JS 无法伪造 ⇒ **没有「绕过合成」的第三条路**。

---

## 八、关键参数表

| 参数 | 值 | 理由 |
|---|---|---|
| `GAME_URL` | `https://slow-roads.pages.dev/` | 作者一手发布页（其余为第三方/失效） |
| `BEGIN_URL` | `https://slow-roads.pages.dev/#A3-f420a738@6.00` | v1.6 实测：**带 hash 的场景地址**。裸 URL 会先停 Begin 欢迎屏、需点 Begin 才进画面；再次打开该 hash URL 可**跳过 Begin 直接进画面**（§7）。默认仍用裸 `GAME_URL` + `clickBegin()`，`BEGIN_URL` 留给 §9.15 的 hash 缓存优化 |
| `BEGIN_ID` | `splash-begin` | v1.7 实测：Begin 按钮的真实元素 ID（`DIV#splash-begin`，class `svelte-hmec5z`，`clickable=true`，Svelte 应用）。`probeBegin`/`clickBegin` 均以此精确定位，不再用「无 canvas + 文本含 begin」的模糊判定 |
| `BEGIN_DONE_DELAY_MS` | 2 500 | v1.6 新增：点击 Begin 后到画面可交互的等待 |
| `MUTE_KEY` | 游戏键 `M` | v1.6 实测定案：静音主路径用游戏原生静音键（§7），比 JS patch 可靠 |
| `ALLOWED_HOST` | `slow-roads.pages.dev` | 防止页面内跳转把用户带出 App（Begin 场景只改 hash 不改 host） |
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
| 9.1 | ⚠️ **Android 5.1.1 电视的 WebView 可能没有可用的 WebGL**（最高风险，可能直接判死该设备） | 进入即跑 `probeWebGL()`；失败 ⇒ `NO_WEBGL` 卡片：「无法加载或当前设备浏览器内核不支持 3D｜按 OK 重试｜按 ← 切换效果」（v1.3 定案：卡片纯文字不放可聚焦按钮；v1.5 加「按 OK 重试」——该态可能是 HTTP 错误误判，§9.14）。**先手机验、再电视验**（§12） |
| 9.2 | WebView 版本过旧导致 JS 语法报错（`KeyboardEvent` 构造器等） | 发键结果用 `ValueCallback<String>` 回读，异常即降级提示；不用 `@JavascriptInterface`（省掉 ProGuard keep 与注入面） |
| 9.3 | 性能：3D 游戏吃满 CPU/GPU，音乐播放卡 | 降低预期：本效果不保证帧率；设置里画质档降到 LOW 时该效果**自动不可见**（`Tier.ADV` 门控） |
| 9.4 | 内存：WebView + WebGL + ExoPlayer 同驻 | 离开即 `destroy()`；`largeHeap` 已开（`AndroidManifest.xml:72`）；不在本效果里保留其它渲染器的重资源 |
| 9.5 | 🔊 **音效冲突**：游戏有引擎声，会盖住音乐 | v1.6 实测定案：**主路径发游戏原生静音键 `M`**（`TOGGLE_MUTE`，`onPageFinished` 后立即注入一次，不等画面 ready），再紧跟幂等 JS `mute()` 兜底（`M` 是 toggle，若页面已静音会反开声）。⚠️ v1.5 关于「JS `createGain` patch」的认知已升级：M 键更可靠、优先用；JS 仅兜底（幂等，即使 M 在某个阶段未生效也能保证最终静音）。⚠️ **v1.7 实测**：游戏音频走 **WebAudio**、页面**无 `<audio>/<video>` 元素**（实测 `media: []`）⇒ `mute()` 里「遍历 audio/video 设 muted/volume」对游戏本身是**空操作**，真正生效的只有 `createGain` patch；验收时**必须听声**确认，不能只看 JS 返回 true。且**仍不存在系统级 API**（`AudioManager.setStreamMute(STREAM_MUSIC)` 会连音乐一起静音且 API 23 起 deprecated）。⚠️ **F 键**才必须等画面 ready 后发（Begin 阶段发 F 无效，§7） |
| 9.6 | 网络不通 / 被墙 | `onReceivedError` + 超时 ⇒ `LOAD_FAILED` 卡片带「重试」 |
| 9.7 | 页面改版、按键绑定变更 | 键位集中在 `SlowRoadsKeyMapper`（一处改）；README 已知限制写明「依赖第三方页面，可能失效」 |
| 9.8 | OK 键被本效果接管后不能暂停 | 已知取舍；写入 README 已知限制 + 效果 Toast 提示 |
| 9.9 | Android 5.1 hwui 在离屏合成上出现过段错误（既有教训） | 若真机在本效果下闪退，第一步尝试：Canvas 层在本效果下**不启用** `CompositingStrategy.Offscreen` |
| 9.10 | ProGuard/R8 | 本方案不用 `addJavascriptInterface` ⇒ 无需新增 keep；若后续加了，**必须**补 keep 并复跑 release |
| 9.11 | ⚠️ **手机端 WebView 消费触摸 → 滑动切效果失效**（`isFocusable=false` 只管焦点不管触摸） | 舞台 Box 在 Initial pass（`awaitEachGesture`）抢先识别横向拖拽，或宿主层禁用 WebView 触摸；**T3.3 真机验证**（§5.2） |
| 9.12 | ⚠️ **WebView 渲染进程崩溃**（Android 5.1 已知风险，默认行为黑屏无通知） | v1.5 更正：`WebViewClient.onRenderProcessGone` 是 **API 26** 回调，在 Android 5.1（SDK 22）上**不会被调用** ⇒ 原方案对它无效。实现：`@RequiresApi(26)` + `Build.VERSION.SDK_INT >= 26` 守卫，作为高版本兜底（转 `LOAD_FAILED` + 取消挂起 Runnable + 返回 `true`）；Android 5.1 的崩溃只能靠 `onReceivedError` + `LOAD_TIMEOUT_MS` 兜底（§6.4.2-b） |
| 9.13 | JS 键位注入失败无日志，调试困难 | 开发阶段设置 `WebChromeClient` 的 `onConsoleMessage`，将 WebView 内部 JS 日志桥接到 `Log.d`；发布时可移除或降级为仅 ERROR 级 |
| 9.14 | ⚠️ **页面 HTTP 错误被误判为「不支持 3D」**（v1.5 发现）：`onReceivedError` 只覆盖网络层错误，不覆盖 HTTP 4xx/5xx。若 `onReceivedHttpError` 只记日志，404/500 会走 `onPageFinished` → WebGL 探测 → 页面无 canvas → 误判 `NO_WEBGL` | 主框架 `onReceivedHttpError`（`errorCode >= 400`）转 `LOAD_FAILED`（§6.4.2-b / (c) 状态机）；`NO_WEBGL` 卡片文案改为「无法加载或设备不支持 3D」，避免错误归因 |
| 9.15 | ⚠️ **Begin 欢迎屏阻塞自动巡航**（v1.6 实测发现、v1.7 更正）：打开裸 URL 先停在 Begin 屏，需点 Begin 才进画面。⚠️ **v1.7 实测更正：Begin 屏本身有 canvas**（内部 300×150、CSS 752×384，靠 CSS 放大，WebGL 已 init、AudioContext 已 running）——所以 v1.6 的「该阶段**无 canvas** ⇒ `probeReady` 恒 false」是**错的**；且已进画面时 body 文本仍含 "begin"（版本日志/菜单项），v1.6 的 `probeBegin`（要求无 canvas + 文本含 begin）在真实页面上**恒返回 false**，`clickBegin()` 永远走不到，比 v1.5 更糟 | ① 状态机加 Begin 分支：`probeBegin()`（v1.7 改为**精确 ID `#splash-begin` 存在且可见**）检测到 Begin 即 `clickBegin()`（`getElementById('splash-begin').click()`），等 `BEGIN_DONE_DELAY_MS` 后回到探测（§6.3 N3、§6.4.2-c、§7）；② `probeReady` 必须**先排除 `#splash-begin` 再判 canvas 的 CSS 尺寸**（`clientWidth/clientHeight`，不是内部 `width/height`），否则在 Begin 屏就白发 F（§6.3 N3）；③ **可优化**：缓存上次的 hash URL，下次直接加载跳过 Begin（待验证项，非必须）；④ 真机验收时把「进入后 5–15s 内自己开起来」改为「先看到进画面、再自动开」 |
| 9.16 | ⚠️ **游戏校验 `isTrusted`，JS 合成键在真实页面上无效**（v1.8 实测）：浏览器逐键实测 **F / E / C** —— 物理键盘按 `f` 能让车动、合成 F 无效；合成 E/C 同（均无反应）。已排除焦点干扰（脚本内 `window.focus()`+`canvas.focus()`，焦点实测 `DIV#main`）。根因：游戏校验 `event.isTrusted`，合成事件恒 `false`，且 `isTrusted` 是浏览器只读安全属性 **无法用 JS 伪造** ⇒ 没有「绕过合成」的第三条路 | ① **唯一可靠主路径是通道① `dispatchKeyEvent`**（走 Android 系统输入管线，Chromium 会把原生 KeyEvent 转成 `isTrusted=true` 的真实 DOM 事件），§7 / §6.4.2-d 已改；② 通道② `evaluateJavascript` 合成事件**降级为老内核兜底**，且必须 `ValueCallback` 回读确认，不盲发；③ ⚠️ **待验证**：通道①在 Android WebView 上是否真能填出 `event.code`（`KeyF`/`KeyM`）—— 老内核可能只填 `keyCode` 不填 `code`，而现代游戏常用 `event.code`；桌面 Chrome 验不出，**必须真机（尤其 Android 5.1 电视）测**；若真机上通道①也无效，本效果无法自动发键，只能靠用户手动（届时 §9.8 的「OK 键接管」也失去意义，需回本表重裁） |

---

## 十、单测门禁清单

基线：`testDebugUnitTest` **1177 例 / 115 类 / 0 失败**，`lintDebug` **0E / 279W**。

| 门禁 | 类 | 断言要点 |
|---|---|---|
| 既有 | `VisualizerThemeTest` | **7 行**：`:51,:57,:58,:90,:96,:103` 硬断言 36 → **37**（`:90` 是 `on.size`）+ `:89` `off.size` 35 → **36**；`ordinalLabel` 唯一性自动覆盖新项；T1.3 另增 1 例 `LOW.supports(SLOW_ROADS)` 守护用例 |
| **G22** | `SlowRoadsKeyMapperTest`（约 15 例） | ① OK↔F、↑↔E、↓↔Q、MENU↔C 双向一致；② `keyCodeOf` 与 `keySpecOf.keyCode` **必须逐项相等**（两条通道不能打架）；③ `←`/`→`/`Back` 映射到 `null`（**负向自证**：若有人图省事把左右也映射进去，测试必须红）；④ KeySpec 的 `code` 必须以 `Key` 开头；⑤ v1.6 新增：`TOGGLE_MUTE` → `KEYCODE_M` / `KeyM` / `77`，且 `mapKey` **不得**把任何遥控键映射到 `TOGGLE_MUTE`（静音只在进入时自动注入一次） |
| **G23** | `SlowRoadsScriptTest`（约 18 例） | ① 发键脚本**同时**含 `window.dispatchEvent` 与 `document.dispatchEvent`（负向自证：只发 window 的旧写法必须被判出）；② 含 `Object.defineProperty(e, 'keyCode'`（v1.7 实测更正：桌面 Chromium 构造器传 keyCode 是有效的，非恒 0，所以此项语义降级为「兜底」——但测试仍保留：去掉 defineProperty 的写法必须被判出，防老内核失效）；③ 含 `code:`；④ `probeWebGL` 覆盖 webgl2/webgl/experimental-webgl 三种；⑤ 键名转义：注入 `'` 与 `\` 不破坏 JS 字符串（**负向自证**：未转义的必须被判出）；⑥ 空转断言（脚本常量非空、能被扫到）；⑦ v1.7 修订：`probeBegin` 正向（`#splash-begin` 存在且可见 ⇒ true）、负向（该元素不存在 ⇒ false、隐藏 ⇒ false——防止把「页面已挂/已隐藏残留」误判成 Begin；⚠️ 原来的「已有 canvas ⇒ false」负向用例**必须删除或改写**，实测 Begin 屏有 canvas，该断言与真实页面矛盾）；⑧ `clickBegin` 必须**先走 `getElementById('splash-begin').click()`**（负向自证：只遍历文本找 begin 的写法必须被判出）、幂等；⑨ `mute` 脚本仍存在（作为 M 键的兜底），且含 AudioContext `createGain` patch（v1.7 实测：页面无 audio/video 元素，只有 patch 真正生效）；⑩ **v1.8 新增**：`sendKey` 的 KDoc/注释必须写明「**合成事件 `isTrusted=false`，本函数不是主路径，仅老内核兜底，须回读确认**」（负向自证：注释缺失或把合成键当主路径的写法必须被判出——防后续维护者误当主路径导致效果静默失效） |
| **G24** | `SlowRoadsWebViewGuardTest`（源码扫描，约 11 例 + 自证） | ① `visualizer/slowroads/` 与 `ui/components/SlowRoadsWebViewLayer.kt` 内出现 `WebView` 的每处，同文件 32 行内必须有 `isFocusable = false`（负向自证：构造一个只 `new WebView` 不设不可聚焦的样本必须命中）；② 占位渲染器 `draw` 体必须为空（`Unit`）；③ 工厂 `when` 覆盖 `SLOW_ROADS`；④ v1.5 新增：覆写 `onRenderProcessGone` 处 32 行内必须有 `SDK_INT >= 26` 守卫（负向自证：无守卫样本必须命中）—— 防 API 26 回调在 Android 5.1 上报 `VerifyError`；⑤ **空转断言**：扫描到的文件数 ≥ 3，否则测试自红 |

⚠️ 源码扫描门禁**必须自带负向自证**（既有教训：只判「0 违规」分不清「真干净」和「没扫到」）。

---

## 十一、提交顺序（每步可独立验证）

| # | 提交内容 | 验收点 |
|---|---|---|
| C1 | 枚举 + 工厂分支 + 占位渲染器 + 测试断言 36→37 | 门禁绿（1177 + 1：T1.3 的 LOW 门控守护用例）/ 编译过 / 效果列表出现「公路漫游」（选中为黑屏，符合预期） |
| C2 | `SlowRoadsConfig` + `SlowRoadsKeyMapper` + `SlowRoadsScript` + G22/G23 | 门禁绿（+33 例：G22 15 + G23 18；v1.6 起比 v1.4 的 +26 多 7 例，因新增 M 键与 Begin 探测用例）；**纯 JVM，不碰设备** |
| C3 | `SlowRoadsWebViewHost` + `SlowRoadsWebViewLayer` + 舞台挂载 | `assembleDebug` + `lintDebug` 0E；能进到该效果并看到网页（无键位也行） |
| C4 | 舞台键位拦截（OK/↑/↓）+ 自动巡航调度（含 Begin 引导 + M 键静音）+ G24 | 门禁绿；真机按 OK 能开/关自动驾驶；**进入后先自动越过 Begin 欢迎屏、游戏无声**（§9.15 / §7） |
| C5 | WebGL 探测、Begin 引导、静音注入、超时、失败降级卡片、生命周期释放 | 断网 / 无 WebGL 设备上有明确提示而非黑屏；Begin 屏能自动进画面（§9.15）；进出 5 次无泄漏 |
| C6 | 文档：CHANGELOG 新节、§10.184、README 已知限制 | 见 §12 |

---

## 十二、上机验收清单

> ⛔ 规则：产物就绪后**交给用户安装**，不在会话里往电视上装包；上机一律 **release 包**；不代启动。
> 设备：电视 `192.168.0.114:5555`（Android 5.1.1 / SDK 22 / armeabi-v7a）｜手机 小米 22081212C（adb `91846823`）

**手机（先做，用来区分「代码问题」还是「设备不支持」）**

1. 播放一首歌 → 进可视化 → 切到「公路漫游」→ 网页加载出来，无白屏
2. 等约 5–10 秒 → 画面**先自动点掉 Begin 进入游戏**，再**自己开始往前开**（自动巡航生效）；且游戏**无声**（M 键静音生效，§7）。⚠️ v1.7 实测：Begin 屏本身有 canvas，判定 Begin 靠 `#splash-begin`，判定 ready 靠「Begin 已消失 + canvas CSS 尺寸达标」；若发现**在 Begin 屏就收到 F 键**（画面未动但车已开/停），说明 `probeReady` 没排除 Begin，必须回 §6.3 N3 修
3. 按 OK → 车停下；再按 OK → 又开起来。⚠️ v1.8：**这一步实测通道① `dispatchKeyEvent` 是否被游戏响应**——若 OK 无效，说明通道①在本机 WebView 上没填出 `event.code`（老内核可能只填 keyCode），按 §9.16 处理
4. 按 ↑ / ↓ → 场景（地貌/时段）变化。⚠️ v1.8：同上，实测通道① 是否对 ↑/↓ 也生效（`KeyE`/`KeyQ`）
5. 按 ← / → 、手机左右**滑动** → 仍然切的是**频谱效果**（不能被本效果吃掉；滑动失效按 §9.11 缓解后复测）
6. 按返回 → 退出可视化，音乐**不中断**，无残留游戏声
7. 反复进出 5 次 → 无崩溃、无明显内存增长

**电视（后做，预期可能失败）**

8. 切到该效果 → 若提示「不支持 3D」：属**预期降级**，C5 生效（不是 bug）。⚠️ v1.5 补充：该提示也可能是**页面 404/500 被误判**（§9.14），先按 OK 重试一次、确认页面确实无法加载后再下结论
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

- [ ] **阶段完成**：`testDebugUnitTest` 1211 例 / 0 失败（1178 + 33）；纯 JVM，不上机

### 阶段 3 · WebView 宿主与舞台挂载　**5 项**

- [ ] **T3.1** `SlowRoadsWebViewHost` 设置与加载（实现细化 §6.4.2）
  - `isFocusable = false` / `isFocusableInTouchMode = false`（**缺了就切不动效果**）
  - WebSettings 按 §8；`WebViewClient` 拦截非白名单 host；黑底防白闪
  - **验收**：手机切到该效果能看到网页
- [ ] **T3.2** `SlowRoadsWebViewLayer`（Compose 层；实现细化 §6.4.3）
  - `AndroidView` + `DisposableEffect` 释放；状态 → 降级卡片（重试 / 切换效果）
  - ⛔ 用 `evaluateJavascript` 回读，**不要**用 `addJavascriptInterface`（规避 ProGuard 与注入面）
  - **验收**：进出 5 次无崩溃；失败态有卡片不是黑屏
- [ ] **T3.3** `VisualizerStage` 挂载（Canvas 之后、前景 Column 之前）
  - **验收**：`assembleDebug` + `lintDebug` 0E；歌词/指示器仍在最上层且可见；**手机真机：左右滑动仍能切效果**（WebView 触摸消费风险，失效按 §5.2 / §9.11 缓解）
- [ ] **T3.4** G24 源码扫描门禁
  - 每条 `WebView` 构造附近必须有不可聚焦设置；空转断言（扫描文件数 ≥ 3）
  - v1.5 补充：覆写 `onRenderProcessGone` 处 32 行内必须有 `SDK_INT >= 26` 守卫（§6.4.2-b / §9.12）
  - **验收**：G24 绿；**故意删掉一处 `isFocusable = false` 时必须变红**

- [ ] **阶段完成**：手机上能加载并看到画面；其余 36 套效果行为零变化

### 阶段 4 · 键位与自动巡航　**5 项**

- [ ] **T4.1** 舞台键位前置分支（仅本主题）
  - 在 `VisualizerStage.kt:227` 的 `onPreviewKeyEvent` 里，主题为 `SLOW_ROADS` 时先查 `mapKey`
  - `repeatCount > 0` 直接消费丢弃；`ACTION_DEBOUNCE_MS` 防连发
  - **验收**：按 OK 只在按下的那一次生效；长按不会疯狂 toggle
- [ ] **T4.2** 发键（通道① `dispatchKeyEvent` 为主 + 通道② JS 注入为老内核兜底）
  - ⚠️ **v1.8 实测更正**：合成事件（通道②）在真实页面上无效（游戏校验 `isTrusted`，合成恒 false），**主路径只能是通道① `dispatchKeyEvent`**（走系统管线，Chromium 转成 `isTrusted=true`）；通道② 仅老内核兜底且须 `ValueCallback` 回读确认（§7、§6.4.2-d、§9.16）
  - **验收**：手机按 OK 能开/关自动驾驶（**通道① 必须生效**）；若通道① 无效（按 OK 车不动），**立即按 §9.16 排查**（真机上 `event.code` 是否被填充）——不得「两条都走」了事
- [ ] **T4.3** 自动巡航调度（探测 + 重试 + 取消；状态机见 §6.4.2-c）
  - 按 §7 流程；超时**不**发键（避免 toggle 抵消）
  - 用户手动按键即 `cancelAutoDrive()`
  - v1.6 补充：探测循环先 `probeBegin()`，命中即 `clickBegin()` 并等 `BEGIN_DONE_DELAY_MS`（§9.15 / §6.4.2-c）
  - **⚠️ v1.7 实测更正**：`probeBegin` 用精确 ID `#splash-begin`（**不要**用「无 canvas + 文本含 begin」，实测恒 false）；`probeReady` 必须**先排除 `#splash-begin` 再判 canvas 的 CSS 尺寸**（不是内部 `width/height`，实测 Begin 屏就有 300×150 的 canvas）
  - **验收**：进入后**先自动点掉 Begin 进入游戏**、再 5–15s 内自己开起来；手动按过一次后自动流程不再插手；**不得**在 Begin 屏就发 F
- [ ] **T4.4** 左右键/BACK 行为回归
  - **验收**：本效果下 ←/→ 仍然切效果、BACK 仍然退出可视化（**负向自证**：若被本效果吃掉即失败）；**含手机滑动路径**（§9.11）

- [ ] **阶段完成**：手机 7 条验收全过（§12 手机 1–7）

### 阶段 5 · 降级与健壮性　**4 项**

- [ ] **T5.1** WebGL 能力探测 + `NO_WEBGL` 降级卡片
  - 无 WebGL ⇒ 不发任何键、不空转；卡片为纯文字（v1.3 定案，**无「切换效果」按钮**，按 ← 切效果）
  - v1.5 补充：`NO_WEBGL` 态按 OK = `load()` 重试（该态可能是 HTTP 错误误判，§9.14 / §6.4.6-8）
  - **验收**：无 WebGL 设备显示「无法加载或当前设备浏览器内核不支持 3D｜按 OK 重试｜按 ← 切换效果」提示
- [ ] **T5.2** 加载失败 / 超时 / 断网 三态与重试
  - **验收**：断网进入 ⇒ `LOAD_FAILED` + 重试可用；弱网 ⇒ 20s 后转超时提示；**页面 404/500 ⇒ `LOAD_FAILED` 而非 `NO_WEBGL`**（§9.14，v1.5 新增验收）
- [ ] **T5.3** 静音注入与音效冲突
  - v1.6 定案：**主路径发游戏原生静音键 `M`**（`TOGGLE_MUTE`，`onPageFinished` 后注入一次，不等画面 ready）+ 紧跟 JS `mute()` 兜底；**不存在系统级 API**（`setStreamMute` 会连音乐一起静音且已 deprecated）
  - ⚠️ v1.7 实测：游戏音频走 **WebAudio**、页面**无 `<audio>/<video>` 元素**（实测 `media: []`）⇒ `mute()` 里「遍历 audio/video」那段对游戏是空操作，**真正生效的是 `createGain` patch**；验收时**必须听声**确认（不能只看 JS 返回 true）
  - ⚠️ **v1.8 更正**：M 键属键盘事件、合成发 M 与合成 F/E/C 一样被游戏忽略（校验 `isTrusted`，§9.16），所以发 M **必须走通道① `dispatchKeyEvent`**；JS `mute()` 兜底是 AudioContext patch、不依赖键盘事件，仍是静音最终保证
  - **验收**：进入该效果后音乐不被游戏音盖住、游戏无声；退出后音乐音量正常
  - **Begin 验证**（v1.6 新增，并入本节）：进入后能**自动越过 Begin 欢迎屏**进入游戏画面，不会停在欢迎屏一动不动（§9.15，v1.7 起按 `#splash-begin` 精确判 Begin）

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

## 十四、裁决记录（2026-09-24 用户拍板，4 项全部落定）

| # | 问题 | 裁决结果 | 落点 |
|---|---|---|---|
| 1 | **效果显示名** | ✅ **「公路漫游」**（与「分子」「照片墙」同类命名） | 枚举 `displayName`（T1.1 / M1，原文即按此编写，无需改动） |
| 2 | **OK 键被本效果接管**（该效果下按 OK 不再暂停音乐） | ✅ **接受**（遥控上没有更合适的键） | §5.2 拦截分支照常实现；§9.8 + T6.3 README 已知限制必须写明 |
| 3 | **游戏音效** | ✅ **默认静音注入**（音乐优先） | `MUTE_BY_DEFAULT = true`（N1）。v1.6 实测定案：**主路径发游戏原生静音键 `M`**（`TOGGLE_MUTE`，`onPageFinished` 后注入一次，不等画面 ready）+ 紧跟 JS `mute()` 兜底；`M` 是 toggle 只注入一次（§7）。⚠️ v1.5 的「JS `createGain` patch」认知已升级——M 键更可靠，JS 仅兜底；**仍不存在系统级 API**（`AudioManager.setStreamMute(STREAM_MUSIC)` 会连音乐一起静音且 API 23 起 deprecated）。⚠️ **v1.7 实测**：游戏音频走 **WebAudio**、页面**无 `<audio>/<video>` 元素**（实测 `media: []`），`mute()` 里「遍历 audio/video」那段对游戏是空操作，真正生效的是 `createGain` patch；验收**必须听声**（§9.5、§6.3 N3、T5.3）。⚠️ **v1.8 连带影响**：M 键也属键盘事件，合成发 M 同样无效 ⇒ **M 键必须走通道① `dispatchKeyEvent`**（§7）；JS `mute()` 兜底**不受影响**（它是 AudioContext patch，不依赖键盘事件，仍作静音最终保证） |
| 4 | **电视跑不动怎么办** | ✅ **选 ①：降级提示后仍保留该效果**（保留知情权，便于将来内核升级后可用） | §9.1 `NO_WEBGL` 卡片：纯文字键位提示（OK = 重试 / ← = 切效果），**不放可聚焦按钮**（v1.3 定案）；**不做**「检测到不支持就从列表隐藏」的逻辑；T6.3 README 已知限制写明 |

> ⛔ 裁决已定：后续实现与文档（T6.1–T6.3）不得偏离本表；确需变更须先在本表追加新裁决行。

---

## 十五、附：原文案要点归档（保留原始调研结论，供实现时对照）

- 官方真实地址 `https://slow-roads.pages.dev/`（页面底部 `from topograph.io © 2025`，开发者 Anslo）
- `theslowroads.io/slow-roads-2/` 是第三方介绍页；`slow-roads.github.io` 已失效
- 控制全部绑在键盘上：自动驾驶 `F`、下一场景 `E`、上一场景 `Q`、视角 `C`
- 无官方接口 ⇒ 一切靠模拟按键；页面改版即失效（属已知限制）
- WebGL 3D 应用，对 GPU 有要求；必须联网

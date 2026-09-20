# 手机竖屏 UI 适配与横竖屏切换开发方案

> 版本：v1.5（D9/D10 拍板：竖屏不隐藏系统栏 + 横竖屏直接硬切）
> 日期：2026-09-19
> 状态：**已实施**（v2.36.0，2026-09-19 落地；提交 `72c9d5e` + `62980d2`）
> - ✅ P0-1 ~ P0-26 全部完成（含 P0-26「按 §2.7 复核全部竖屏 dp 口径」—— 复核结论是
>   此前所有「44dp+ 触摸目标」注释都是口径误用，已统一到 `portraitTouchTarget(...)`）
> - ✅ P1-27 ~ P1-32 全部完成（P1-32 后半的「新增 Screen 必须有 UiMode 分支」门禁
>   **未按原文放 `tools/lint/`**：该工具链下自定义 lint check 因类加载器不一致无法加载，
>   改用同等强度的单测门禁 `ScreenUiModeCoverageTest`，详见 `docs/conventions-adaptive-ui.md` §9）
>   - ⚠️ **P1-27 收尾补漏**：§2.4 对话框族表格点名的 `SettingsScreen:906`（删除备份确认弹窗）
>     首轮实施时漏改，仍写死 `.width(520.dp)` —— 竖屏 Compose 口径 439~501dp **放不下 520dp**，
>     在任何手机竖屏都会被对话框窗口裁掉。现已改为 `responsiveDialogSize(520.dp, scrollable = true)`；
>     同时给 `docs/conventions-adaptive-ui.md` §5 补了「新增对话框后必做的全量自查 grep」
> - ✅ P2-34（触摸反馈与 TV 焦点态并存，顺带修掉「手机点一下按钮永久放大 8%」）、
>   P2-35、P2-38、P2-39 完成
> - ⏸️ **P2-33 后半**（详情页下滑返回手势）—— 标注需实测，与 D9 底部系统手势冲突，
>   在无法上机验证前不引入不可验证的交互
> - ⏸️ **P2-37**（0.82 → 0.88）—— 标为"可选"，且需连带复核 §2.7 全部口径，留待上机后定
> - ⏸️ **P2-36**（平板 `TabletPortrait` 独立分档）—— 标为"可选"，且 §1 已把「平板专属
>   两栏布局」列为**非目标**；当前 `medium` 档已覆盖 sw≥600
> - ⏳ **P2-40** 真机截图验收 —— 按项目约定由用户安装 release 包后人工确认
> - ✅ **真机反馈轮（2026-09-19）**：用户上机验收 v2.36.0 后报出 **7 条问题**（竖屏 6 + 横屏 1），
>   已全部修复，**未引入新版本号**（仍在 v2.36.0 内）。逐条映射见 **§10.4**；
>   其中「按钮看不清」的根因是 `androidx.tv.material3.LocalContentColor` 默认 `Color.Black`
>   而 `FocusableSurface` 未下发（**TV 上同样存在**），维护约定见 `docs/conventions-adaptive-ui.md` §10
> ✅ 5 项阻断已闭环：B1 分支谓词（§3.1）、B2 inset 前提（D9 拍板）、B3 过渡副作用（D10 拍板硬切，直接消除）、B4 首帧同步读（§8.2 镜像）、B5 dp 口径（§2.7）
> 目标版本：v2.36.0 起分批落地
> 关联文档：`docs/phone-support-plan.md`（手机端适配总纲）、`docs/phone-media-display-plan.md`（媒体展示与保活）

> **版本演进**
> - v1.0 初稿
> - v1.1 对照源码核验，修正 6 处技术错误、删掉 3 项已有能力的高估工作量
> - v1.2 补 §八「关键改动代码骨架」、§0.4「必须守住的 2 条既有约束」、§十「测试计划」；
>   任务清单补全文件与行号，共 30 项分 P0/P1/P2
> - **v1.3** 拍板 7 项产品/工程决策、放宽验收标准、新增 1 项设计原则：
>   1. **L2 单击循环切换 + 写 pref** 同步策略（§0.6 / §5.3）——**不要长按**（PM 2026-09-19 拍板，去掉 D1 双轨设计）；
>   2. **高亮模式保留一键入口**（§4.2）——7 Chip 不再无脑收敛为 3 Chip；
>   3. **底部导航触摸目标 ≥ 44dp**（§4.0）；
>   4. **状态栏/导航栏 inset 升 P0**（§5.5(6) / §9）；
>   5. **首帧按 Home Screen 设方向**（§5.5(1) 补完）；
>   6. **`PhoneLandscape = TV 横屏布局复用`** 明文入文档（§3.1）；
>   7. **`AnimatedContent` 切换过渡**（§5.5(7)）——⚠️ **v1.5 已推翻，改硬切**（D10）；
>   8. **「滚动可见 = 合理」设计原则**（§2.6）——验收标准对应放宽（§10.3）。
>
> **v1.4（2026-09-19，源码级复核修订）** —— 新增 §0.8「v1.4 复核修正」，5 项阻断 + 7 处漏页 + 10 处事实：
>   1. **B1 横向手机形态归属**：分支谓词改为**只认 `UiMode.PhonePortrait`**，`TV` 与 `PhoneLandscape` 都走现状（原文 §8.6 骨架把横屏手机也切到新 chrome，与 §10.3 用例 3 冲突）；
>   2. **B2 inset 前提**：新增 **D9** —— 竖屏不隐藏系统栏，否则 `statusBarsPadding()` 是 no-op；
>   3. **B3 过渡副作用**：新增 **D10** —— 过渡期间新旧子树并存会置空单槽 handler、重复拉数据；
>   4. **B4 首帧同步读**：`getScreenOrientationSync()` 改镜像/`@Volatile`，**不再 `runBlocking`**（对齐 R-7）；
>   5. **B5 dp 口径**：新增 §2.7 —— `PHONE_UI_SCALE = 0.82` 下的物理 dp / Compose-dp 换算（原文 360dp、44dp 口径全部偏小）；
>   6. §2.4 / §3.4 / §4.7 补齐 7 处漏页（`SearchTab` 来源行、`RadioTab` 顶部行与列数、详情页头部操作行、骨架页、网盘顶部搜索行等）；
>   7. 纠正 10 处与源码不符的描述（首页卡片 160dp、`WeatherRadioScreen`/`MineScreen` 无网格、`NetdiskScreen:119`/`RadioTab:86` 是搜索框、`JamendoTab` 死代码、`EqualizerScreen` 已竖向等）。
>
> **v1.5（2026-09-19，D9/D10 拍板）**：
>   1. **D9 = 竖屏取消 `hide(systemBars())`**：`PhonePortrait` 下显示状态栏/导航栏；`TV` / `PhoneLandscape` / 沉浸模式 / 全屏页维持隐藏（§5.5(9)、§8.3⑤、P0-20）；
>   2. **D10 = 横竖屏直接硬切**：不做 `AnimatedContent` / `Crossfade`；B3（双子树导致单槽 handler 被置空 + 重复拉数据）随之**整体消除**，原 P0-21/22 两项配套一并取消；
>   3. §5.5(7) 由"必须过渡"改为"本版不做过渡"，(10) 降为"将来若要引入过渡必须做的配套"备忘；§8.6 骨架去掉过渡包装；
>   4. §10.3 用例 7 改为"硬切可用性"（无白帧/异常帧），其余用例不变。

---

## 〇、审阅结论（v1.1 + v1.2 + v1.4）

把 v1.0 的技术断言逐条对照源码与真实编译类路径核验，结论如下。

### 0.1 必须修正的 6 处（已在本版改正）

| # | v1.0 的说法 | 核验结果 | 影响 |
|---|------------|---------|------|
| **C1** | 底部导航「可复用 material3 组件」 | ❌ **`androidx.compose.material3` 不在编译类路径上**（`debugCompileClasspath` 实测只有 `compose.ui` / `compose.foundation` / `compose.material-icons-*`，全仓库 0 处 `import androidx.compose.material3`） | **致命**。`NavigationBar`/`Scaffold`/`Slider`/`ModalBottomSheet` 全部 import 不过。底部导航、MiniPlayer、底部弹层必须用 `compose.foundation` + `androidx.tv.material3` 自建 |
| **C2** | 新建 `PortraitSizes` 列数表 | ❌ 项目**已有**两套约定：`adaptiveColumns(tv, phone, phoneLandscape)`（`BrowseComponents.kt:77`，阈值 `widthDp>=1000` / `>=600`）与 `songGridColumns()`（`CommonComponents.kt:114`，恒为 1 列） | 应扩展现有 helper，不另造一套 |
| **C3** | 顶部导航「现状 8 项」 | ❌ 实际 **6 项**：首页 / 播放 / 曲库 / 我的 / 队列 / 设置（`AppRoot.kt:225-254`） | 导航映射表需按 6 项重写 |
| **C4** | `UiMode` 判定用 `remember { derivedStateOf { configuration.orientation } }` | ❌ `configuration` 不是 `State`，`remember` 会**永久读到初值**，方向变化永不生效 | 必须直接读 `LocalConfiguration.current`（CompositionLocal 内部是 State，配置变更自动重组） |
| **C5** | 设置页分区列表（通用/播放/下载与缓存/…/播放统计/均衡器） | ❌ `SettingsSection` 是 **private enum**，9 个分区：`GENERAL / PLAYBACK / DOWNLOAD / SERVER / CACHE / NETWORK / NETDISK / DATA / ABOUT`。**播放统计、均衡器不是分区**，是分区内的入口按钮（`onOpenPlayStats` / `onOpenEqualizer`） | 竖屏二级页的列表项需按真实枚举重画 |
| **C6** | Manifest 未提现状值 | ⚠️ 实际 `android:screenOrientation="fullSensor"` 且**未声明 `configChanges`** | 删掉强制横屏后默认行为是「跟随传感器且**忽略系统旋转锁**」，与"自动"的预期不符；且旋转会重建 Activity |

### 0.2 应做的减法：3 项工作量可以删掉

| # | v1.0 计划的工作 | 核验结果 |
|---|----------------|---------|
| **S1** | 进度条触摸 seek 改造 | ✅ **已实现**。`PlayerControls.kt:163-193` 已有 `detectTapGestures`（点击跳转）+ `detectDragGestures`（拖动），注释明确写着「手机触摸拖动 seek」 |
| **S2** | 歌曲列表竖屏改单列 | ✅ **已实现**。`songGridColumns()` 恒返回 `GridCells.Fixed(1)`，歌曲本就是单列 |
| **S3** | 播放统计热力图改「横向滚动 + 双指缩放」 | ✅ **已自适应**。`PlayHeatmapChart.kt:77-89` 按可用宽度算 `cell` 并 `coerceIn(3.dp, 16.dp)`，`cell < 9.dp` 时自动隐藏星期标签。竖屏 360dp 下 `cell≈5dp`、总宽 ≈281dp，**能塞下**，只是格子小。改为「可选优化」 |

### 0.3 附带发现（不在本方案范围，建议顺手处理）

- `PlayerControls.kt:133` 有一行遗留调试日志 `AppLog.e("ProgressSection", "focused=${it.isFocused}")`。`AppLog.e` **无 `BuildConfig.DEBUG` 守卫** → release 包每次焦点变化都打日志。建议清理。
- `FocusableSurface.kt` 的 `isTVDevice` 只判 `android.software.leanback`，而 `MainActivity` / `AppRoot` 判的是 `leanback || android.hardware.type.television` → 在只上报 `type.television` 的非认证 TV 盒子上，所有用 `FocusableSurface` 的控件**焦点边框不显示**（v1.4 复核发现，与本方案无关，建议一并修）。

### 0.4 开发期必须守住的 2 条既有约束（v1.0 未提，容易踩）

| # | 约束 | 证据 | 本方案受影响处 |
|---|------|------|---------------|
| **K1** | **禁止在 AppRoot 顶层订阅 `progress` / `duration`** —— 进度由 `PlayerManager` 的 1000ms `Handler` 轮询驱动，顶层收集会每秒驱动 AppRoot 全树重组（含 LazyColumn 状态与 D-Pad 焦点搜索） | `AppRoot.kt:115-117` 的 F-2 修复注释；正确做法见 `NowPlayingBranch.kt:78-81` | MiniPlayer 有进度细线 → **必须在 `MiniPlayer` 内部订阅**（§8.5） |
| **K2** | **页面级 BACK 状态必须放在 `NavigationViewModel`**，不能留在页面局部 `remember` —— `AppRoot` 的 BACK handler 需要读它 | `AppRoot.kt:153-184` 的 handler 由 `navVM.currentScreen` 驱动 | 设置二级页的 `selectedSection`（§6.2 / §8.7） |

### 0.5 核验手段（可复现）

```bash
# C1 的决定性证据
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat :app:dependencies --configuration debugCompileClasspath --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process | grep -i material3
# → 无输出（仅有 material-icons-*）

grep -rn "import androidx.compose.material3" --include=*.kt app/src/main/java | wc -l   # → 0
```

> 这正是项目已有 skill `android-dep-api-verify` 强调的坑：**「传递依赖」≠「compile 期可用」**（先例：`androidx.media:media` 只以 runtime scope 经 media3 进来 → import 编译失败）。`tv-material` 依赖 material3 是 runtime 语义，写代码时不可用。

### 0.6 拍板的 10 项决策（D1–D8 v1.3 拍板 / D9–D10 v1.4 复核新增）

| # | 决策项 | 选择 | 落地位置 | 理由 |
|---|--------|------|---------|------|
| **D1** | L2 顶部栏方向切换与 L1 设置项的同步 | **L2 单击 = 在「竖屏 ⟷ 横屏」二态间循环 + 立即写 pref**；**"自动" 只能从设置项里改**，L2 不进入 "自动" 态；**无长按行为** | §5.3 末尾、§4.0 全局骨架注、§9 P0-6 | 与 Android 系统级"旋转锁定"按钮一致——点按钮 = 强制方向；想"跟着系统走"必须进设置。避免"长按"对老年/儿童用户不友好 |
| **D2** | 播放页歌词区 7 Chip 是否全部收敛为 3 Chip | **保留高亮模式一键入口**（移入 §4.2 组件 ⑥「次级操作 Chip 横排可滚动」）；**字号 A 系列合并为 1 个循环 Chip**（4 档循环，按一下跳下一档）；**睡眠定时单独保留**；**4 个来源标签合并为 1 个循环 Chip**（4 源循环） | §4.2 组件明细 ⑥、§9 P0-13 | "功能收敛 ≠ 没隐藏"——高亮模式是高频切换，移入"更多"菜单会让用户三步才能切，发现度大幅下降 |
| **D3** | 底部导航触摸目标 | **Icon 24dp + 整体高度 56dp + padding(vertical=8dp)**；文字 12sp；触摸热区 ≥ 44dp 宽 | §4.0 PhoneNavBar 骨架、§8.4 代码补、§9 P0-7 | 22dp 图标 + 12sp 文字 ≈ 36dp 总高，**小于 Material 48dp 规范**和 Apple HIG 44dp——必须调到 56dp 容器才能让触摸不误触 |
| **D4** | 状态栏/导航栏 inset | **P0**（不再是 P2）：`PhoneTopBar` 用 `statusBarsPadding()`、`PhoneNavBar` 用 `navigationBarsPadding()`、MiniPlayer 同样处理 | §4.0 全局骨架、§5.5(6)(9) 实现要点、§9 P0-19（前提是 D9） | 刘海/手势导航/三键导航适配是竖屏可用性的硬门槛，不能等 P2 |
| **D5** | 首帧按哪个 Screen 设 `requestedOrientation` | **Home**（冷启动到首页）：`onCreate` 同步设一次，方向 = 解析 L1 pref + `Screen.Home` 计算 | §5.5(1) 补完、§8.3 | 冷启动到 Home 是默认路径；若将来支持"记住上次 Screen"则改为读 pref |
| **D6** | `PhoneLandscape` 形态归属 | **`PhoneLandscape = TV 横屏布局复用`**，仅 `PhonePortrait` 走竖屏新设计 | §3.1、§8.3 | 横屏手机端目前体验 OK（6 项顶部导航 + 380dp 封面 + 歌词列分栏），**不重构**；未来若要"手机端统一体验"则再单独开方案 |
| ~~**D7**~~（v1.5 被 D10 取代） | 横屏↔竖屏切换的视觉过渡 | ❌ **已推翻**：原定 `AnimatedContent` + 200ms Crossfade；v1.5 复核发现会引入 B3 三个副作用（handler 置空 / 重复拉数据 / 滚动位丢失），**改为硬切**（见 D10、§5.5(7)） | §5.5(7) | 保留此行仅作决策沿革记录，实施以 D10 为准 |
| **D8** | 全应用手势约定 | **不使用长按手势**（PM 2026-09-19 拍板）：L2 方向切换走单击；歌曲行上下文菜单改"右侧 ⋮"按钮触发；**封面信息弹层用"封面右上角 ⓘ"图标按钮触发**（PM 2026-09-19 01:23 拍板，不用"查看详情"文字按钮，不用长按）；其他场景同样原则 | §4.0 手势约定、§4.2 封面交互、§5.3 L2、§11 风险表 | 老年/儿童用户友好；与 Material Design 触控规范（48dp 最小触摸目标，按钮优于手势）一致；降低误触风险；ⓘ 图标是国际通用的"信息"语义，用户认知成本低 |
| **D9**（v1.4 新增 / **v1.5 已拍板**） | 竖屏是否继续隐藏系统栏 | ✅ **拍板：竖屏不隐藏系统栏**：`PhonePortrait` 下调用 `WindowInsetsControllerCompat.show(systemBars())` 恢复状态栏/导航栏；`TV` / `PhoneLandscape` / 沉浸模式 / 全屏页维持现状（继续隐藏）。保留 `setDecorFitsSystemWindows(false)`，由 `statusBarsPadding()` / `navigationBarsPadding()` 自行留白 | §5.5(9)、§8.3⑤、§9 P0-20 | `statusBarsPadding()` / `navigationBarsPadding()` 只在系统栏**可见**时才返回非 0 inset。当前 `MainActivity.kt:117-127` 用 `hide(systemBars())`，竖屏下这两个 padding 基本是 no-op——刘海照样压住 Logo，且用户上滑唤出系统栏时内容会跳动。**D4 成立的前提就是 D9** |
| **D10**（v1.4 新增 / **v1.5 已拍板**） | 横竖屏切换的过渡实现 | ✅ **拍板：不做过渡，直接硬切**。§8.6 去掉 `AnimatedContent` / `Crossfade` 包装（裸 `Column`）；接受切换瞬间的硬切观感 | §5.5(7)、§8.6、§11 风险表 | 过渡会在 200ms 内**同时组合新旧两棵子树**：旧子树 `onDispose { listBackHandler.value = null }` 会把新子树刚注册的 handler 置空（旋转后 Level 1.5 回顶静默失效），两棵子树的 `LaunchedEffect(Unit)` 还会各拉一次首页/曲库/天气数据，且新子树 `rememberLazyListState` 会回到顶部。硬切一次消除全部副作用，代价只是观感（PM 已接受） |

### 0.7 v1.3 调整的 1 项设计原则（PM 确认）

> **「滚动可见 = 合理」**：竖屏布局不再追求"无溢出"。凡内容超出可视区但**能通过 `LazyColumn` / `verticalScroll` 滚动到达**，都视为合格。**"无溢出"只用于 Modal / Dialog / 固定高度组件**。
>
> 这一定义直接放松：
> - §2.4"硬编码大尺寸"清单中**非全屏页面**的固定宽度组件，竖屏允许溢出滚动（不再要求全部"折叠"）
> - §4.1 首页、§4.3 曲库、§4.4 我的 等内容型页面，**滚动是预期体验**
> - §4.2 播放页固定区域（顶栏 56dp + 封面 max 320dp + 控制行 64dp + 进度条）**不能溢出**——这是"无溢出"硬指标
> - §4.6 设置页两级页**单列 LazyColumn**，可滚动
> - §10.3 验收"无溢出"标准对应放宽为"无关键内容不可见 + 无 Modal/Dialog 截断"

### 0.8 v1.4 复核修正（2026-09-19，对照源码 + `debugCompileClasspath` 实测）

> 对 v1.3 全文做一次源码级复核（含 `:app:dependencies --configuration debugCompileClasspath` 实测）。
> 结论：**骨架成立、可开工**，但下列 5 项为阻断级，必须先按本节口径修掉；另有 7 处漏页、10 处事实错误一并修正。
> **v1.5 更新**：B3 经 D10 拍板为"硬切"后**直接消除**；其余 4 项已按本节口径改入正文。

**B1 横屏手机的分支自相矛盾（阻断）**

原文 §3.1 D6 说 `PhoneLandscape` = 复用 TV 横屏布局、§10.3 用例 3 要求"横屏与改前一致"，但 §8.6 的骨架写的是 `if (mode == UiMode.TV) TvTopNavBar else PhoneTopBar` + `if (mode != UiMode.TV) { MiniPlayer; PhoneNavBar }`——横屏手机会拿到全新的顶部栏**和**底部导航，与现状（6 项顶部导航、无底部栏）完全不同，**直接违反 §10.3 用例 3**。

修正：分支谓词一律**正向判断 `UiMode.PhonePortrait`**——只有竖屏走新界面，`TV` 与 `PhoneLandscape` 都走**现状代码路径**。注意 `else` ≠ `TV`：现状里横屏手机与 TV 本就不同（`LocalPhoneCompact` 在横屏手机也为 `true`，`MineScreen.kt:130` 横屏本就是单列，`adaptiveColumns` 走 `>=600` 那一支）。详见 §3.1 / §8.6。

**B2 inset 方案的前提缺失（阻断）**

`MainActivity.kt:117-127` 在手机端 `setDecorFitsSystemWindows(false)` + `hide(systemBars())`。系统栏被隐藏时 `statusBars` / `navigationBars` inset 通常报 0，`statusBarsPadding()` 基本是 no-op——刘海照样压住 Logo，且用户上滑唤出系统栏时内容会突然跳动。**D4 是否有效完全取决于 D9**。详见 §5.5(9)。

**B3 过渡会并行保留两棵子树（阻断 → **v1.5 由 D10 拍板硬切后整体消除**）**

`AnimatedContent`（`Crossfade` 同理）在 200ms 内同时组合新旧子树，导致两个真实副作用：① 旧子树的 `DisposableEffect.onDispose { listBackHandler.value = null }` 把新子树刚注册的 handler **置空**（`HomeScreen` / `QueueScreen` / `AlbumDetailScreen` / `ArtistDetailScreen` / `PlaylistManagementScreen` 都是这种单槽写法）→ 旋转后 BACK 的 Level 1.5 静默失效；② 两棵子树的 `LaunchedEffect(Unit)` 各跑一次 → 每次旋转重复请求首页/曲库/天气数据。修正见 D10 + §5.5(10)。**v1.5 已拍板：本项目不做过渡（硬切），本条整体消除**；下列内容保留为"将来若要引入过渡"的备忘。

**B4 首帧同步读不能用 `runBlocking`（阻断）**

原文 §8.2 让 `getScreenOrientationSync()` 走 `runBlocking { dataStore.data.first() }` 并称"参照 `getLanguageSync()`"。但 `getLanguageSync()` 早已改为 **SharedPreferences 镜像 + R-7 第三类修复**（`AppPreferences.kt:308`，`:967` 注释"读 @Volatile 内存镜像（原主线程急切求值 runBlocking）"），主线程零 IO、零 `runBlocking`；对 provider 类键也用 `@Volatile` 镜像 + application scope 常驻收集。修正：照抄镜像范式（§8.2），**不要新引入主线程 `runBlocking`**。

**B5 0.82 缩放导致全部 dp 口径失效（阻断）**

竖屏下 `LocalDensity` 被 `PHONE_UI_SCALE = 0.82` 缩放（`MainActivity.kt:187`），Compose 里的 `X.dp` 实际只占 `X × 0.82` 个**物理 dp**。原文所有"竖屏可用宽度 360dp""44dp 热区""56dp 容器"的口径必须按 **§2.7** 重算，否则容量与热区结论都是错的（物理 44dp = Compose ≥ 53.7dp）。

**其余修正**：§2.4 / §3.4 / §4.7 补齐 7 处漏页（`SearchTab` 来源行、`RadioTab` 顶部行与列数、专辑/艺术家详情头部操作行、`Shimmer` 骨架、网盘顶部搜索行、`ServerConnect` 优先级）；纠正 10 处与源码不符的描述（首页卡片 160dp、`WeatherRadio`/`Mine` 无网格、`Netdisk`/`RadioTab` 的 420/340dp 是搜索框不是侧栏、`JamendoTab` 是死代码、`EqualizerScreen` 已竖向、语言切换不是 `recreate()`，`SettingsSection` 行号 257、D3 与 §8.4 的 `padding(vertical)` 自相矛盾等），逐条见 §2.4 修订表与 §4.7。

---

## 一、需求与目标

| 编号 | 需求 | 现状 | 目标 |
|------|------|------|------|
| R1 | 手机支持竖屏 | `MainActivity.kt:170-179` 强制 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，非 TV 设备打开即横屏 | 竖屏可用且**体验不劣于横屏** |
| R2 | 完整的竖屏 UI 设计 | 仅 `MineScreen` 有 `isPhone` 单列分支，其余页面为 TV 横屏布局 | 逐页面给出组件位置 + 操作方式 |
| R3 | 手动横竖屏切换 | 无任何方向设置 | 全局策略 + 页内快捷切换两级能力 |

**非目标**：平板专属两栏布局（只保证竖屏在平板上"可用不崩"）；TV 端布局不变。

---

## 二、现状盘点（代码证据）

### 2.1 强制横屏的唯一出处

`app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt:170-179`

```kotlin
val isTVDevice = remember {
    packageManager.hasSystemFeature("android.software.leanback") ||
    packageManager.hasSystemFeature("android.hardware.type.television")
}
LaunchedEffect(isTVDevice) {
    if (!isTVDevice) {
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}
```

Manifest 侧：`app/src/main/AndroidManifest.xml:85` → `android:screenOrientation="fullSensor"`，**无 `configChanges`**。

> ⚠️ 两个后果（v1.0 漏了）：
> ① 运行时 `requestedOrientation` 覆盖 manifest 值，但**一旦删掉上面这段，`fullSensor` 就生效** —— 它会忽略用户的系统旋转锁，与"自动"的预期不符；
> ② 未声明 `configChanges` → **方向变化会 destroy/recreate Activity**。

### 2.2 已有的手机端适配基础（复用，勿重造）

| 机制 | 位置 | 说明 |
|------|------|------|
| `CompactSizes.PHONE_UI_SCALE = 0.82f` | `ui/theme/Theme.kt:343-348` | 手机端全局 `LocalDensity` 缩放（dp 与 sp 同步缩） |
| `LocalPhoneCompact` | `ui/theme/Theme.kt:351` | 标记「非 TV」。消费点 5 处：`FontSize.adjustedSize:164`、`LyricsView.kt:71`、`KaraokeLyricsView.kt:61`、`MineScreen.kt:130`、`BrowseComponents.kt:342` |
| `FontSize` 双档 | `ui/theme/Theme.kt:120-166` | TV = 手机档 +6sp，按 `LocalPhoneCompact` 自动切换 |
| **`adaptiveColumns(tv, phone, phoneLandscape)`** | `ui/screens/library/browse/BrowseComponents.kt:77` | **已有的宽度自适应列数 helper**，阈值 `widthDp>=1000→tv` / `>=600→phoneLandscape` / `else→phone` |
| **`songGridColumns()`** | `ui/components/CommonComponents.kt:114` | 恒返回 `GridCells.Fixed(1)` |
| `MineScreen` 单列分支 | `ui/screens/MineScreen.kt:130` | `if (isPhone) LazyColumn(...)` —— 竖屏可直接复用 |
| `FocusableSurface` | `ui/components/FocusableSurface.kt` | 已用 `Modifier.clickable` → **触摸天然可用**，注释即写明「手机：直接响应触摸点击/按下」 |
| `ProgressSection` 触摸 seek | `ui/components/PlayerControls.kt:163-193` | 已有 tap + drag 手势 |
| 顶部导航 / 曲库 Tab 横向滚动 | `AppRoot.kt:222`、`LibraryScreen.kt:362` | 已为窄屏做过让步 |
| 热力图窄屏自适应 | `ui/screens/stats/PlayHeatmapChart.kt:77-89` | 按可用宽度算格子尺寸 |

### 2.3 现有 `adaptiveColumns` 的实际取值（竖屏落点已确定）

| 调用点 | `(tv, phone, phoneLandscape)` | TV(≥1000) | 手机横屏(≥600) | **手机竖屏(<600)** |
|--------|------------------------------|-----------|---------------|-------------------|
| `AlbumGrid.kt:140` | `(6, 3, 6)` | 6 | 6 | **3** |
| `ArtistList.kt:142` | `(6, 3, 6)` | 6 | 6 | **3** |
| `GenreYearLists.kt:83` | `(4, 2, 3)` | 4 | 3 | **2** |
| `GenreYearLists.kt:180` | `(5, 2, 3)` | 5 | 3 | **2** |

> **结论：竖屏网格列数基本无需改动**——`adaptiveColumns` 已经给出合理落点（专辑 3 列 ≈120dp/卡，可接受）。真正需要做的是给**尚未使用该 helper 的网格**补上，以及微调个别数字。这是 v1.0 高估的工作量。

### 2.4 ⚠️ 竖屏阻塞清单：硬编码大尺寸

竖屏可用宽度：**物理 360dp**（1080×2400 @440dpi）→ 因为 `PHONE_UI_SCALE = 0.82`，**Compose-dp 口径 ≈ 439dp**（原文只写 360dp，见 §2.7）；TV 基线 **960dp**。

| 文件:行 | 硬编码 | 竖屏处理 | 优先级 |
|---------|--------|----------|--------|
| `screens/NowPlayingScreen.kt:265` | `.width(380.dp)` 封面列 | 改 `fillMaxWidth().widthIn(max=320.dp).aspectRatio(1f)` | **P0** |
| `screens/SettingsScreen.kt:351` | `.width(240.dp)` 分区导航 | 竖屏改两级页（§4.6） | **P0** |
| `screens/QueueScreen.kt:122` | `.width(340.dp)` 侧卡 | 折为顶部 56dp 横条 | **P0** |
| `screens/NowPlayingScreen.kt:743` | `.width(300.dp)` 封面/信息面板容器（`CoverColumn` 宽度，原文写"信息面板"） | 竖屏改比例式宽度；信息面板另走底部弹层（自建，见 C1） | P1 |
| `screens/ServerConnectScreen.kt:150` | `.width(760.dp)` 表单，**只有纵向滚动** | `fillMaxWidth().widthIn(max=420.dp)` 居中 + 纵向滚动 | **P0**（未连接时的配置入口，竖屏横向溢出即不可用） |
| `screens/netdisk/NetdiskScreen.kt:119` | `.width(420.dp)` **顶部搜索框**（不是侧栏） | 搜索框 `fillMaxWidth().widthIn(max=420.dp)`，顶部行随内容收窄 | P1 |
| `screens/PlaylistManagementScreen.kt:130` | `.width(320.dp)` 左侧歌单栏 | 单列（歌单在上、歌曲在下，各自可滚动） | P1 |
| `screens/library/RadioTab.kt:86` | `.width(340.dp)` **搜索框** + 同行 N 个 preset tag（**无横向滚动**） | 搜索框 `fillMaxWidth`，tag 改 `FlowRow`（换行）或加 `horizontalScroll` | **P0**（tag 会被推出屏幕且滑不到） |
| `screens/library/RadioTab.kt:162` | 电台网格 `GridCells.Fixed(2)` 硬编码 | 改 `adaptiveColumns(3, 1, 2)` | P1 |
| `screens/library/SearchTab.kt:194-240` | `SearchSourceBar`：label + N 个来源 Chip + 「全部」，同一 `Row` **无横向滚动** | 改 `FlowRow`（换行）或加 `horizontalScroll` | **P0**（同上，来源点不到） |
| `screens/library/JamendoTab.kt:111` | `.width(340.dp)` | ⚠️ **该 Composable 全仓库无人引用（死代码）**，本方案不改；若要接入再单独处理 | — |
| `screens/AlbumDetailScreen.kt:96-155`、`screens/ArtistDetailScreen.kt:95-154` | 头部 `Row`：返回 + 标题(weight) + 播放全部 + 加入队列 + 歌单数，**无换行无滚动** | 竖屏拆两行（标题行 / 操作行）或操作行加 `horizontalScroll` | **P0**（按钮会被裁） |
| `components/Shimmer.kt:135,153` | 骨架网格 `GridCells.Fixed(6)`（`AlbumSkeletonGrid` / `ArtistSkeletonGrid`） | 改 `adaptiveColumns(6, 3, 6)`，与真数据列数一致 | P2 |
| 对话框族 480/520/560/600/640/720 | `ConfirmDialog:66`、`ConnectPromptDialog:56`、`ExportDeviceDialog:58`、`ExitConfirmDialog:52`、`PlaylistPickerDialog:95`、`BackupTransferDialog:119`、`ModelTransferDialog:112`、`BaiduAuthDialog:110`、`PlaylistImportUploadDialog:118`、`TextInputDialog:237`、`BaiduDirPickerDialog:104-105`(640×660)、`QualityProbingDialog:64`、`SettingsScreen:906` | 统一 `fillMaxWidth(0.92f) + widthIn(max=420.dp) + heightIn(max=0.8f) + verticalScroll` | P1 |
| `components/KaraokePlaybackScreen.kt:665,723` | 420 / 200 dp | 全屏页强制横屏，**不改** | — |

> `QualityPickerDialog` 已有一次「固定 520dp 无滚动导致溢出」的修复记录（`components/QualityPickerDialog.kt:53` 注释），可直接沿用其做法。
>
> ✅ **实施后复核**：本行点名的 `SettingsScreen:906`（设置页「删除备份」确认弹窗）首轮**漏改**，
> 仍是裸 `.width(520.dp)`；竖屏 Compose 口径只有 439（360dp 屏）~ 501（411dp 屏）dp，**都 < 520**
> → 任何手机竖屏都会被对话框窗口裁掉。已改为 `responsiveDialogSize(520.dp, scrollable = true)`。
> ⚠️ 教训：helper 的「唯一入口」性质靠 grep 守，见 `docs/conventions-adaptive-ui.md` §5 的自查命令。

### 2.5 ⚠️ 关键认知：不能靠 density 缩放解决竖屏

`PHONE_UI_SCALE=0.82` 把 360dp 换算成"等效 439dp"，**与需要的 960dp 差 2 倍多**。缩放只能微调观感，竖屏必须做**结构性布局分支**。

**本版决策：竖屏沿用 `0.82`，暂不分档。**

理由：改缩放会连带影响 `LYRICS_RECOVER_SCALE`（现为常量 `1/0.82`，被 `LyricsView.kt:75`、`KaraokeLyricsView.kt:65` 消费），而 K 歌页是强制横屏、需要的是横屏系数——一旦分档，这个常量必须改成读 `LocalUiMode` 的 composable 函数，把风险引入关键路径。竖屏真正的痛点是布局结构，不是缩放。若要调，放到 P3 打磨项，且必须同时处理 `LYRICS_RECOVER_SCALE`。

### 2.6 ⚠️ 设计原则：「滚动可见 = 合理」（v1.3 PM 拍板）

竖屏设计**不再追求"零溢出"**。这是本版的根本性放宽：

| 类别 | 允许溢出滚动 | 必须无溢出 |
|------|------------|-----------|
| 内容型页面（首页 / 曲库 / 我的 / 队列 / 设置 / 详情） | ✅ 必须用 `LazyColumn` 或 `verticalScroll`，滚动是预期体验 | — |
| 播放页固定区域（顶栏 / 封面 / 进度条 / 控制行 / 模式指示） | — | ✅ 必须严格无溢出（影响每次播放） |
| **Modal / Dialog / 底部弹层 / 全屏覆盖** | — | ✅ 必须严格无溢出（含 `fillMaxWidth(0.92f) + widthIn(max=420.dp) + heightIn(max=0.8f) + verticalScroll`，见 §2.4） |
| **进度条 / 控制按钮** 触摸热区 | — | ✅ 必须 ≥ 44dp 宽 |
| 设置页二级内容 | ✅ 单列 `LazyColumn` | — |

**验收标准对应放宽**（§10.3）："无溢出"标准替换为：
> **"无关键内容不可见 + 无 Modal/Dialog 截断 + 无固定组件被推屏外"**

具体含义：
- ✅ 允许：首页统计卡 + 4 个区块超出可视区 → 滚动可见
- ✅ 允许：曲库 Tab Chip 横向滚动到边
- ✅ 允许：歌单列表、歌曲列表、备份列表纵向滚动
- ❌ 不允许：播放页控制行按钮被裁切（影响操作）
- ❌ 不允许：Dialog 高度超出屏幕且无滚动（已在 §2.4 修复记录中）
- ❌ 不允许：进度条左右时间标签重叠或被裁

**这条原则带来的工作量节约**：
- §2.4 硬编码大尺寸清单中**非 Dialog、非固定顶/底栏**的组件（如 `ServerConnectScreen.kt:150` 的 760dp），可走 `widthIn(max=560.dp)` 单列滚动**而不必**全量改写
- §4.1 首页 2×2 统计网格 + 3 个区块 + 天气卡 = 9 个内容块，**约 1.5 屏高**（1080×2400 @440dpi：物理高 ≈873dp → Compose-dp ≈1064dp，见 §2.7）——用户滚动可达，不再纠结"每个区块高度压缩"

### 2.7 ⚠️ 容量与触摸目标的换算口径（v1.4 新增，必读）

竖屏下 `LocalDensity` 被 `CompactSizes.PHONE_UI_SCALE = 0.82` 缩放（`MainActivity.kt:187`），Compose 里写的 `X.dp` 实际只占 `X × 0.82` 个**物理 dp**。由此必须统一两个口径：

| 口径 | 定义 | 竖屏 1080×2400 @440dpi | 用途 |
|------|------|------------------------|------|
| **物理 dp**（Android 规范口径） | `px / density`；Material 48dp / Apple HIG 44dp 说的都是它 | 宽 **≈360dp**，高 ≈873dp | 触摸目标、字号下限、"能不能看清" |
| **Compose dp**（代码口径） | `px / (density × 0.82)`；`Modifier.width(x.dp)` 用的就是它 | 宽 **≈439dp**，高 ≈1064dp | 所有布局宽高计算 |

三条硬结论：

1. **触摸目标换算**：物理 44dp = Compose **≥ 53.7dp**。D3 的"高 56dp"换算为 56 × 0.82 ≈ **45.9 物理 dp** ✅ 达标；但任何写成 Compose 44dp 的控件，物理只有 36dp ❌ 不达标——**代码里不能再拿 44dp 当热区下限**。
2. **宽度余量比原文宽松**：竖屏 Compose 可用宽 ≈439dp（再减左右 padding），所以 `368dp` 封面列并非"必然溢出到屏幕外"，而是"占掉 ~79% 宽度导致歌词列不可用"——修法不变，但**溢出判定要按 439dp 重算**，否则会把"其实塞得下"的组件误判为必须重构。
3. **两把尺子混用**：`adaptiveColumns` 用的是**未缩放**的 `LocalConfiguration.screenWidthDp`（≈360dp），而布局宽度是 Compose dp（≈439dp）——在 `600 / 1000dp` 阈值附近会出现"列数按横屏算、宽度按竖屏算"的错配。**建议**：`adaptiveColumns` 上移时改为 `UiMode + widthDp` 双输入（§3.4），列数只由其中一个说了算，不再让调用点各自心算。

> 本节**不改变** §2.5"竖屏沿用 0.82"的决策，只要求把数字口径统一到上表。

---

## 三、总体架构：形态因子（Form Factor）抽象

### 3.1 新增 `UiMode`（放 `ui/theme/Theme.kt`）

**保留 `LocalPhoneCompact` 不动**（语义是"非 TV"，现有 5 处消费不受影响），新增：

```kotlin
enum class UiMode { TV, PhonePortrait, PhoneLandscape }

/** 当前形态因子（MainActivity 提供） */
val LocalUiMode = androidx.compose.runtime.staticCompositionLocalOf { UiMode.TV }
```

> **⚠️ v1.3 D6 拍板**：`PhoneLandscape` = **TV 横屏布局复用**。
>
> - 现状横屏手机端体验 OK（6 项顶部导航、380dp 封面 + 歌词列分栏、设置左右分栏）
> - 仅 `PhonePortrait` 走竖屏新设计（底部导航 + MiniPlayer + 两级设置页 + 单列滚动）
> - **不重构**横屏手机端布局，避免与 TV 横屏体验分叉
> - 未来若需要"手机端统一体验"则单独开方案；本方案 `UiMode` 三态保留扩展空间
>
> 这意味着 `if (LocalUiMode.current == UiMode.PhonePortrait) { 竖屏新设计 } else { 现状代码路径 }` 是正确的二分支模板——**不是**三态全分支。
>
> **⚠️ v1.4 B1 修正（硬规则）**：`else` 分支**不是**"TV 横屏布局"的同义词，而是"**原样保留现状**"。现状里横屏手机与 TV 本就不同：`LocalPhoneCompact`（`!isTVDevice`）在横屏手机也为 `true`，5 处消费点（`Theme.kt:164` 字号、`LyricsView.kt:75`、`KaraokeLyricsView.kt:65`、`MineScreen.kt:130` 单列、`BrowseComponents.kt:342`）在横屏手机全部生效，`adaptiveColumns` 也走 `>=600` 那一支。因此：
> - **不要**把横屏手机切到新的 `PhoneTopBar` / `PhoneNavBar`（会破坏 §10.3 用例 3"横屏与改前一致"）
> - **不要**把横屏手机改回 TV 的双栏布局（会破坏 `MineScreen` 等既有横屏体验）
> - 一句话：**新界面只认 `PhonePortrait`，其余一律走 `if` 之外的原有代码**

### 3.2 判定（MainActivity）—— 注意 C4

```kotlin
// ✅ 正确：LocalConfiguration.current 是 CompositionLocal，配置变更触发重组
val configuration = androidx.compose.ui.platform.LocalConfiguration.current
val uiMode = when {
    isTVDevice -> UiMode.TV
    configuration.orientation == Configuration.ORIENTATION_PORTRAIT -> UiMode.PhonePortrait
    else -> UiMode.PhoneLandscape
}
```

```kotlin
// ❌ 错误：configuration 不是 State，remember 会永久读到初值
val uiMode by remember { derivedStateOf { ... configuration.orientation ... } }
```

> 不用 `WindowSizeClass`：需要 `androidx.compose.material3.adaptive` 依赖（未引入，且受 C1 限制）。

### 3.3 提供链（MainActivity `setContent` 内）

```kotlin
CompositionLocalProvider(
    LocalDensity        provides uiDensity,       // 已有
    LocalPhoneCompact   provides (uiMode != UiMode.TV),  // 语义不变
    LocalUiMode         provides uiMode,          // 新增
    LocalFontAdjustment provides settings.fontAdjustment
)
```

### 3.4 列数约定（扩展现有 helper，不另造）

把 `adaptiveColumns` 从 `ui/screens/library/browse/BrowseComponents.kt` **上移到 `ui/components/CommonComponents.kt`**（与 `songGridColumns()` 并列，去掉 `internal` 的包内局限），供全项目复用：

```kotlin
// ui/components/CommonComponents.kt
fun adaptiveColumns(tv: Int, phonePortrait: Int, phoneLandscape: Int = phonePortrait): Int
```

> ⚠️ 参数语义澄清：现有第三参名为 `phoneLandscape`，但它由 `widthDp>=600` 触发，**平板竖屏（sw≥600）也会落进这一支**。上移时建议改名为 `medium` 并在 KDoc 里写明，避免日后误判。

新增/微调建议值（仅列需要动的）：

| 位置 | 现状 | 竖屏目标 | 动作 |
|------|------|---------|------|
| `AlbumGrid.kt:140` | `(6, 3, 6)` | 3 | 不变 |
| `ArtistList.kt:142` | `(6, 3, 6)` | 3 | 不变 |
| `GenreYearLists.kt:83,180` | `(4,2,3)` / `(5,2,3)` | 2 | 不变 |
| ~~`MineScreen` 歌单卡片~~ | 未用 helper | — | ❌ **v1.4 删除**：`MineScreen` 全文件无任何 Grid，歌单是一行一个卡片，无列数可配 |
| ~~`WeatherRadioScreen` 网格~~ | 未用 helper | — | ❌ **v1.4 删除**：该页无网格（天气卡 + `FlowRow` 心情 Chip + 单列 `LazyColumn`） |
| `RadioTab.kt:162` 电台网格 | `GridCells.Fixed(2)` 硬编码 | 1 | **补上** `adaptiveColumns(3, 1, 2)`（v1.4 新增，来自 §2.4） |
| 首页横向卡片宽（`HomeAlbumCard` / `HomeSongCard`） | **`width(160.dp)`** 固定（`HomeScreen.kt:548,609`） | 140 | 按 `LocalUiMode` 取值；⚠️ 原文写 180dp 与源码不符 |

---

## 四、竖屏 UI 设计（逐页组件位置与操作方式）

### 4.0 全局骨架

```
┌──────────────────────────────────────┐
│ ① 顶部栏 56dp                         │
│   [♪ NAS Music]          [🔍] [⟳方向] │
├──────────────────────────────────────┤
│  ② 内容区（weight=1f，可滚动）          │
├──────────────────────────────────────┤
│ ③ MiniPlayer 64dp（有歌且非播放页时）   │
│   [封面48] 歌名/艺术家      [▶] [⏭]    │
├──────────────────────────────────────┤
│ ④ 底部导航栏 56dp                     │
│  首页  曲库  播放  队列  我的  设置     │
└──────────────────────────────────────┘
   ↑ 底部安全区 insets（手势导航栏）
```

| 区域 | 组成 | 操作方式 | 显隐规则 |
|------|------|---------|---------|
| ① 顶部栏 56dp | 左：Logo+应用名；右：搜索、**L2 方向切换图标**（单击在竖/横间循环 + 写 pref，见 §5.3 D1）；**外层 `Modifier.statusBarsPadding()` + `displayCutoutPadding()`** 适配刘海/挖孔（⚠️ **前提：系统栏可见**，见 D9 / §5.5(9)） | 点击 | 与现状 `AppRoot.kt:188` 条件一致：沉浸模式 / MTV / 可视化舞台时隐藏（**不新增 K 歌条件**——K 歌强制横屏，竖屏顶栏不会与之共存） |
| ② 内容区 | 各页面主体，**`LazyColumn` 滚动是预期**（§2.6） | 垂直滑动、点击、**「⋮」按钮触发的上下文菜单**（无长按，D8） | — |
| ③ MiniPlayer 64dp | 封面 48 + 标题/艺术家两行 + 播放/暂停 + 下一首 + 顶部 2dp 进度细线；**外层 `navigationBarsPadding()`** 处理三键导航 | 点击整条 → 播放页；上滑 → 展开播放页（P2，与系统底部手势冲突待实测）；播放/暂停单独热区 | `currentSong != null` **且 `currentScreen != NowPlaying`**（再叠加 `!沉浸 && !showMv && !showKaraoke && !showVisualizer`，见 §8.6） |
| ④ 底部导航 56dp | **6 项**：首页 / 曲库 / 播放 / **队列** / 我的 / 设置；**Icon 24dp + 文字 12sp + 整体 height 56dp**（子项 `fillMaxHeight()`；⚠️ **不要加** `padding(vertical=8dp)`，否则子项热区不足 56dp——D3 与 §8.4 在此矛盾，**以本行为准**）；触摸热区：Compose 56dp ≈ 物理 45.9dp ≥ 44dp（口径见 §2.7）。⚠️ **「队列」是 2026-09-19 真机反馈后新增的第 6 项**（§10.4 竖②）：原设计把队列收敛到「播放页 Chip + 我的页入口」，用户反馈"主按钮中缺少队列，应该加一个" | 点击切换，选中项 Primary 高亮；未选中项 `TextPrimary(#E8EDF5)`（原 `TextSecondary(#8899B0)` 压 `Surface(#162032)` 辨识度不足，同批改亮） | 全屏页隐藏 |

> ⚠️ **MiniPlayer 的进度订阅必须在它自己内部**（`AppRoot.kt:115-117` 的 F-2 修复明确禁止在
> AppRoot 顶层收集 `progress`/`duration`——进度由 `PlayerManager` 的 1000ms Handler 轮询驱动，
> 顶层收集会**每秒驱动 AppRoot 全树重组**，含 LazyColumn 状态与 D-Pad 焦点搜索）。
> 把它写在 `MiniPlayer` composable 内 → 每秒只重组 MiniPlayer 自身。骨架见 §8.5。

**导航收敛（6 项 → 6 项：原为 5 项，真机反馈后把「队列」加回）**

现有 6 项顶部导航（`AppRoot.kt:225-254`）到 `Screen` 枚举的映射，以及竖屏归属：

| 现有顶部导航 | `Screen` | 竖屏位置 |
|-------------|----------|---------|
| 首页 | `Home` | 底部导航 ① |
| 播放 | `NowPlaying` | 底部导航 ③ |
| 曲库 | `Library` | 底部导航 ② |
| 队列 | `Queue` | 底部导航 ④（**2026-09-19 真机反馈后由"播放页 Chip + 我的页入口"升级为直达入口**，§10.4 竖②） |
| 我的 | `Mine` | 底部导航 ⑤ |
| 设置 | `Settings` | 底部导航 ⑥ |

`Screen` 枚举其余 8 项（`ServerConnect` / `AlbumDetail` / `ArtistDetail` / `Equalizer` / `PlaylistManagement` / `Netdisk` / `WeatherRadio` / `PlayStats`）均为二级页，不进底部导航。

> TV 端保留现有 6 项顶部导航，**零改动**；**手机横屏也保留现状 6 项顶部导航**（v1.4 B1 修正）。`AppRoot` 的分支必须写成 `if (uiMode == UiMode.PhonePortrait) 新的顶部/底部栏 else 现状顶部栏`，**不是** `if (uiMode == UiMode.TV)`。

**手势约定**

| 手势 | 行为 |
|------|------|
| 点击卡片 | 播放 / 进入详情 |
| **点击歌曲行右侧「⋮」按钮** | 上下文菜单：加入队列 / 加入歌单 / 下载 / 收藏 / 查看信息（**无长按手势，PM 2026-09-19 拍板 D8**） |
| 播放页左右滑 | 封面 ⇄ 歌词 模式切换 |
| MiniPlayer 上滑 | 展开全屏播放页 |
| 系统侧滑返回 | 走 `OnBackPressedDispatcher`（§六） |

> D-Pad 兼容：新增可点击元素一律用 `FocusableSurface`（已同时支持触摸与焦点），保持外接键盘 / 车机场景可用。

---

### 4.1 首页（`HomeScreen.kt`）

```
┌────────────────────────┐
│ 欢迎语 + 连接状态        │  FontSize.title()
│ NAS-Jellyfin · 已连接   │  FontSize.caption() 灰色
├────────────────────────┤
│ ┌────────┐ ┌────────┐  │  统计卡片 2×2 网格
│ │ 1,234  │ │   87   │  │  （歌曲 / 专辑 / 艺术家 / 歌单）
│ └────────┘ └────────┘  │
│ ┌────────┐ ┌────────┐  │
│ └────────┘ └────────┘  │
├────────────────────────┤
│ [曲库] [搜索] [队列]     │  3 个快捷按钮等宽（weight(1f)）
├────────────────────────┤
│ 最近添加          (23)  │  SectionHeader
│ ◼ ◼ ◼ →                │  横向 LazyRow，卡片 140dp
├────────────────────────┤
│ 最近播放      查看全部  │
│ ◼ ◼ ◼ →                │
├────────────────────────┤
│ 随心听 / 收藏 / 智能电台 │  同上，各一个区块
├────────────────────────┤
│ 🌤 天气电台卡片（全宽）  │
├────────────────────────┤
│        80dp 留白        │  给 MiniPlayer + 底部导航
└────────────────────────┘
```

改动点：

| 组件 | 位置 | 改动 |
|------|------|------|
| `WelcomeSection` | `HomeScreen.kt:287` | 统计卡由横排改 **2 列网格** |
| `NowPlayingCard` | `HomeScreen.kt:664` | 竖屏**隐藏**（与 MiniPlayer 重复）。⚠️ **2026-09-19 真机反馈扩展**：手机**横屏也隐藏**（条件由 `!isPhonePortrait` 改为 `uiMode == UiMode.TV`）—— 它是 72dp 全宽横条，手机横屏可用高度仅 ~439 Compose dp，一条就占 ~16%，而顶栏本就有「正在播放」入口（§10.4 横①） |
| `HomeAlbumCard` / `HomeSongCard` | `:541` / `:603`（内部均为 `width(160.dp)`，原文误记 180） | 卡片宽 **160dp → 140dp** |
| `QuickActionRow` | `:395` | 3 按钮 `Modifier.weight(1f)` 等宽 |

> 「搜索」按钮的行为：`HomeBranch.kt:74-77` 已是 `selectLibraryTab(LibraryTab.SEARCH)` + `navigateTo(Screen.Library)`，竖屏沿用。

---

### 4.2 播放页（`NowPlayingScreen.kt`）★ 核心改造

竖屏下 380dp 封面列 + 歌词列的横排结构不成立。改为**双模式 + 滑动切换**。

#### 模式 A：封面模式（默认）

```
┌────────────────────────┐
│ ⌄         歌名        ⋯  │  ① 顶栏：收起 / 标题 / 更多菜单
├────────────────────────┤
│    ┌──────────────┐    │  ② 封面：fillMaxWidth，
│    │   封面 1:1    │    │     widthIn(max=320.dp).aspectRatio(1f)
│    └──────────────┘    │     点击 → 沉浸模式
├────────────────────────┤
│ 歌名（2行截断）      ♡  │  ③ 歌名 FontSize.title() + 收藏
│ 艺术家 · 专辑            │     点艺术家/歌名 → 网络搜索
│ [FLAC] [来源标签]        │     音质 / 来源 Badge
├────────────────────────┤
│ 01:23  ▬▬▬▬▬▬▬  04:56 │  ④ 进度条（触摸 tap/drag 已支持）
├────────────────────────┤
│  ⇄    ⏮   ▶(64dp)  ⏭   │  ⑤ 控制行
│  ❤  🔊  ☰队列  ⏱定时   │  ⑥ 次级操作 Chip（横向可滚动）
├────────────────────────┤
│         ● ○            │  ⑦ 模式指示器
└────────────────────────┘
```

#### 模式 B：歌词模式

```
┌────────────────────────┐
│ ⌄         歌名      A+  │  顶栏：收起 / 标题 / 字号
├────────────────────────┤
│       （上一行）        │  ② 歌词区 weight(1f)，
│    ► 当前行（高亮）     │     自动滚动 + 逐行/逐字高亮
│       （下一行）        │
├────────────────────────┤
│ [来源▾] [逐行/逐字] [⏱] │  ③ 歌词工具条（3 个 Chip）
├────────────────────────┤
│  ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬  │  ④ 细进度条 2dp
├────────────────────────┤
│  ⏮    ▶     ⏭          │  ⑤ 精简控制行
│         ○ ●            │
└────────────────────────┘
```

**组件明细**

| 编号 | 组件 | 位置 | 触摸 | D-Pad |
|------|------|------|------|-------|
| ② 封面 | `CoverColumn` 改 `fillMaxWidth().widthIn(max=320.dp).aspectRatio(1f)` | 内容区顶部居中 | 单击 → 沉浸模式；**封面右上角"ⓘ"图标按钮 → 歌曲信息弹层**（无长按手势，D8） | OK 键同单击 |
| ③ 歌名区 | 封面下方 16dp | 点歌名/艺术家 → 网络搜索 | — |
| ④ 进度条 | `ProgressSection`（`PlayerControls.kt:85`，`compact=true`） | 全宽，左右 16dp | **已支持 tap + drag seek** | 左右键 ±15s（现有逻辑，`PlayerControls.kt:146`） |
| ⑤ 控制行 | `ControlButtonsRow`（`PlayerControls.kt:285`，`compact=true`） | 居中，播放键 64dp | 点击 | OK 键 |
| ⑥ 次级操作 | Chip 横排可滚动 | 控制行下方 | **高亮模式**（v1.3 D2 关键）/ 收藏 / 音质 / 队列 / 睡眠定时 / 可视化 / K 歌 / MTV / 人声消除 | 焦点横移 |
| ⑦ 模式指示 | 底部 8dp 两圆点 | 居中 | 点圆点切换；**左右滑整页**切换 | 左右键切换 |

> ⚠️ **2026-09-19 真机反馈修正两处（§10.4 竖③ / 竖⑥）**：
> 1. **左右滑**：首轮实现把手势挂在 ⑦ 的 **28dp 指示器**上，且**不分方向、只做 toggle** ——
>    用户实际"发现不了"，反馈为"不支持左右滑"。现手势移到**整块内容区**，**带方向语义**
>    （左滑 → 歌词 / 右滑 → 封面）+ 48dp 位移阈值；⑦ 只保留点击与状态指示。
> 2. **贴底**：首轮封面模式整列 `verticalScroll`，控制区紧跟封面、**屏幕下方空一大片**。
>    现拆为「弹性区（封面 + 歌名，居中）+ 固定贴底区（④ 进度条 / ⑤ 控制行 / ⑥ 次级 Chip）」，
>    封面边长由 `BoxWithConstraints` 的 `maxHeight` **显式扣减预留量**反推
>    （`aspectRatio` 的高度回退只看自身约束，不知道下方还有歌名）。

**⚠️ 必须处理的现状冲突（v1.3 D2 修订）**：`NowPlayingScreen.kt:325-408` 歌词列顶部有 **7 个横排 Chip**（4 个歌词来源 + 高亮模式 + 字号 + 睡眠定时），360dp 下必溢出。**v1.3 方案**：

| Chip | 竖屏处理 | 理由 |
|------|---------|------|
| **4 个来源标签** | 合并为 1 个**循环 Chip**（点一次跳下一个源，显示当前源名） | 4 选 1 的二态选择，可循环替代 |
| **高亮模式（逐行/逐字）** | **保留独立 Chip**（移到控制行右侧「次级操作 Chip 横排」区域，§4.2 组件明细 ⑥） | **高频切换**——移入"更多"菜单会让发现度大幅下降（D2 决策） |
| **字号 A 系列**（4 档循环） | 合并为 1 个**循环 Chip**（显示当前档，按一下跳下一档） | 4 档选 1，循环替代 |
| **睡眠定时** | **保留独立 Chip** | 运行中显示剩余分钟，独立状态指示 |

**最终竖屏歌词模式顶部**：**3 个 Chip（来源 + 字号 + 睡眠）** + **控制行右侧多 1 个 Chip（高亮模式）** = 共 4 个，比 v1.2 的"3 个且高亮模式进更多菜单"多保留 1 个高频入口。

**沉浸模式**：竖屏改为「封面铺满 + 歌词叠在下半屏半透明遮罩上」（不再左右分栏）。退出：点屏幕 / 顶部下滑 / BACK。

---

### 4.3 曲库页（`LibraryScreen.kt`）

```
┌────────────────────────┐
│ 🔍 搜索歌曲/专辑/艺人    │  ① 搜索框全宽（原 .width(240.dp)）
├────────────────────────┤
│ 搜索 发现 专辑 艺人 …→   │  ② Tab Chip 横向滚动
├────────────────────────┤
│ [▶ 播放全部] [＋队列]    │  ③ ActionBar（原在右上角）
├────────────────────────┤
│ ┌────┐ ┌────┐ ┌────┐   │  ④ 内容区（专辑 3 列 / 年代 2 列）
│ └────┘ └────┘ └────┘   │     歌曲：单列 UnifiedSongRow
└────────────────────────┘
```

> Tab 顺序按真实枚举 `LibraryTab`（`LibraryScreen.kt:68-77`）：**SEARCH / DISCOVER / ALBUMS / ARTISTS / SONGS / GENRES / YEARS / RADIO**（v1.0 写反了）。

改动点：
- `LibraryScreen.kt:392` 搜索框 `.width(240.dp)` → `fillMaxWidth`，与「播放全部」拆成两行
- 页级 `padding(horizontal = 32.dp, vertical = 20.dp)`（`:333`）→ 竖屏 16dp
- 专辑/艺术家网格列数 **已由 `adaptiveColumns` 处理，无需改**
- 歌曲列表**已是单列**（`songGridColumns()`），无需改
- 每行操作按钮（收藏 / 队列 / 加入歌单 / 下载）竖屏收敛为右侧「⋮」菜单，避免 4 个图标挤占标题宽度（`UnifiedSongRow` 最多 4 个 `RowActionButton` + 封面 92dp + 序号 36dp + 时长，竖屏必溢出）

子 Tab 单独要做的（v1.4 补齐，详见 §2.4）：
- **RADIO**：顶部行 `搜索框(340dp) + N 个 preset tag` → tag 换行或横向滚动（**P0**）；电台网格 `GridCells.Fixed(2)` → `adaptiveColumns(3, 1, 2)`
- **SEARCH**：`SearchSourceBar` 的来源 Chip 行 → `FlowRow` 或横向滚动（**P0**）
- **ALBUMS/ARTISTS/SONGS/GENRES/YEARS**：靠现有 `adaptiveColumns` / `songGridColumns()`，无需改
- **骨架**：`AlbumSkeletonGrid` / `ArtistSkeletonGrid` 固定 6 列，竖屏应与真数据一致（P2）

---

### 4.4 我的页（`MineScreen.kt`）

**现状 `isPhone` 单列分支（`:130`）可直接复用**，微调：
- ~~歌单卡片补 `adaptiveColumns(4, 2, 3)`~~ → ❌ **v1.4 删除**：`MineScreen` 全文件无 Grid（歌单一行一个卡片），无列数可配
- 页面 padding 32dp → 16dp
- ⚠️ `isPhone` 是 `LocalPhoneCompact`（"非 TV"），**横屏手机也为 true**；本页保持原样即可，**不要**改成 `UiMode` 判断（否则横屏会退回双栏，见 §3.1 B1）

---

### 4.5 队列页（`QueueScreen.kt`）

```
┌────────────────────────┐
│ [封面40] 当前歌名  ▶ ⏭  │  ① 当前播放横条 56dp（原左侧 340dp 卡片）
├────────────────────────┤
│ 播放队列 (12)     清空  │  ② 标题行
├────────────────────────┤
│ ① 歌名A      ⋮         │  ③ 队列单列（UnifiedSongRow MODE_COMPACT）
│ ② 歌名B      ⋮         │
└────────────────────────┘
```

- 现状 `MoveButton`（`:341`）上移/下移 → 竖屏移入「⋮」菜单（上移 / 下移 / 移出队列 / 立即播放）
- 拖动排序放 P3（先用菜单）

---

### 4.6 设置页（`SettingsScreen.kt`）★ 结构性改造

现状「左 240dp 分区导航 + 右内容」在竖屏不成立 → 改**两级页**。

**一级（设置主页）**——按真实 `SettingsSection` 枚举（`SettingsScreen.kt:101-111`，**private enum，需放开为 internal**）：

```
┌────────────────────────┐
│ 设置                    │
├────────────────────────┤
│ ⚙ 通用              ›  │  GENERAL
│ 🎵 播放              ›  │  PLAYBACK
│ 📥 下载              ›  │  DOWNLOAD
│ 🔗 服务器            ›  │  SERVER
│ 💾 缓存              ›  │  CACHE
│ ☁ 网络音乐           ›  │  NETWORK
│ 📦 网盘              ›  │  NETDISK
│ 🗄 数据管理           ›  │  DATA
│ ℹ 关于               ›  │  ABOUT
└────────────────────────┘
```

> 播放统计（`onOpenPlayStats`）、均衡器（`onOpenEqualizer`）**不是分区**，是 `PLAYBACK` / `GENERAL` 分区内的入口按钮，竖屏留在二级页里，行为不变。

**二级（分区内容全屏）**：顶部返回箭头 + 分区名 + 单列 `LazyColumn`。

实现要点：
- 复用 `activeSection` 状态（**实际在 `SettingsScreen.kt:257`**，原文记 264），新增 `selectedSection: SettingsSection? = null` 表示"是否进入二级"
- 竖屏：`selectedSection == null` → 分组列表；非 null → 该分区内容
- TV / 手机横屏：**保持现状左右分栏**（零风险）
- D-Pad 焦点修复逻辑（`:268-276`）只在分栏模式生效
- BACK：二级页 BACK → 回设置列表（见 §六）

---

### 4.7 其余页面

| 页面 | 现状 | 竖屏方案 | 优先级 |
|------|------|---------|--------|
| 专辑详情 `AlbumDetailScreen.kt:54` | 封面左（`CoverImage(size=280)`）+ 歌曲右；头部 `Row` 无换行 | 封面置顶（全宽 max 280dp）+ 标题/艺术家/年份 + ActionBar → 歌曲单列；**头部操作行拆两行或横向滚动**（P0，见 §2.4） | P0 |
| 艺术家详情 `ArtistDetailScreen.kt:54` | 同上（封面圆形 160dp） | 同上，封面改圆形 | P0 |
| 网盘 `netdisk/NetdiskScreen.kt:119` | 顶部 `Row`：48 返回 + 标题 + **搜索框 420dp**（**无侧栏、无面包屑**，原文描述有误） | 搜索框 `fillMaxWidth().widthIn(max=420.dp)`，顶部行随内容收窄；正文单列（`songGridColumns()` 已是 1 列） | P1 |
| 服务器连接 `ServerConnectScreen.kt:150` | 760dp 表单（**只有纵向滚动**） | 单列表单，`fillMaxWidth().widthIn(max=420.dp)` 居中 | **P0**（未连接时的配置入口） |
| 均衡器 `EqualizerScreen.kt:59` | ⚠️ **现状已是竖向 `LazyColumn`**（频段行 = 段名 60dp + dB 值），**没有横排滑块**，原文描述有误 | 基本无需改；可选：频段行加滑动条 | P3（原文 P1） |
| 播放统计 `stats/PlayStatsScreen.kt` | 热力图 | **已自适应**（格子缩小、隐藏星期标签）。可选优化：横向滚动放大 | P3 |
| 天气电台 `WeatherRadioScreen.kt:51` | ⚠️ **无网格**：天气卡（weight 1:2）+ `FlowRow` 心情 Chip + 单列 `LazyColumn` | **无需补 `adaptiveColumns`**（v1.4 删除该任务） | —（原文 P1） |
| 歌单管理 `PlaylistManagementScreen.kt:130` | 侧栏 320dp | 单列（歌单在上、歌曲在下） | P1 |
| 全屏页（MTV / K 歌 / 可视化） | 全屏横屏（`MvPlaybackScreen` / `KaraokePlaybackScreen` / `VisualizerStage`） | **强制横屏**（§5.4） | P0 |
| `library/RadioTab` 丶`library/SearchTab` | 顶部行溢出（见 §2.4） | tag/来源 Chip 换行或横向滚动；电台网格改 `adaptiveColumns` | **P0** |
| `components/Shimmer.kt` | 骨架固定 6 列 | 改 `adaptiveColumns(6, 3, 6)` | P2 |

---

## 五、横竖屏切换功能设计

### 5.1 结论：需要，分两层

| 层级 | 形态 | 解决什么 | 位置 |
|------|------|---------|------|
| L1 全局策略 | 设置项「屏幕方向」：自动 / 竖屏 / 横屏 | "我默认想用哪种" | 设置 → 通用 |
| L2 快捷切换 | 顶部栏方向图标 | "临时想翻一下" | 顶部栏右上角（全屏页除外） |

**默认建议：自动**。理由：① 从强制横屏直接改强制竖屏是行为突变；② 自动模式下竖着拿就是竖屏、横着拿就是横屏，零学习成本，顺带满足 R1；③ 想固定的人用 L1 锁死。

> 若产品侧要强推竖屏，只改一个枚举默认值即可。

### 5.2 模式定义与 `requestedOrientation` 映射

| 选项 | 常量 | 说明 |
|------|------|------|
| 自动 | `SCREEN_ORIENTATION_UNSPECIFIED` | **尊重系统旋转锁**（系统锁定时不转） |
| 竖屏 | `SCREEN_ORIENTATION_USER_PORTRAIT` | 强制竖屏但尊重系统设置（API 18+，minSdk 22 安全） |
| 横屏 | `SCREEN_ORIENTATION_USER_LANDSCAPE` | 同上，横屏 |

> ⚠️ **不要用 `SENSOR` / 不要依赖 manifest 的 `fullSensor`**（C6）——两者都会忽略用户的系统旋转锁。同时把 manifest 的 `android:screenOrientation="fullSensor"` 改为 `unspecified`，避免首帧语义不一致。

### 5.3 存储与设置项

`AppPreferences` 子 pref 范式（`AppPreferences.kt:56-68`：`server` / `player` / `lyrics` / `network` / `baidu` / `download` / `weather` / `visualizer` / `history` / `playlist` / `queue` / `languagePrefs` / `backup`）→ 新增 `DisplayPrefs`：

```kotlin
// data/prefs/AppPreferences.kt
val display: DisplayPrefs by lazy { DisplayPrefs(this) }

class DisplayPrefs internal constructor(private val prefs: AppPreferences) {
    val screenOrientation: Flow<String>          // "auto" | "portrait" | "landscape"
    fun getScreenOrientationSync(): String
    suspend fun setScreenOrientation(value: String)
}
```

> ⚠️ **不要放进 `AppSettings` data class**——它是 Gson 序列化进备份 JSON 的（`data/model/AppSettings.kt` 头部有明确约束），屏幕方向属设备本地偏好，恢复到 TV 上会锁成竖屏。放独立 key 同时规避 Gson 前向兼容问题。

设置项 UI（设置 → 通用）：复用 `SettingsComponents.kt` 中 `SettingSwitch` / `SettingActionButton` 的样式，三选一可用 `PlayModeSelector`（`:128`）同款分段控件。

**L2 顶部栏方向切换图标行为（v1.3 D1 拍板，PM 2026-09-19 移除长按）**：

| 交互 | 行为 | 写 pref? | 同步刷新方向 |
|------|------|---------|------------|
| **单击 L2** | 在「**竖屏 ⟷ 横屏**」二态间循环 + **立即写 pref** | ✅ 写 pref | 立即 `requestedOrientation` 重设 |
| 从「自动」点 L2 单击 | 进入「竖屏」（最常见选择）+ 写 pref；后续单击继续在竖/横间循环 | ✅ 写 pref | 立即 |
| **"自动" 状态** | L2 **不进入**——只能从设置项里改 | n/a | — |

**核心规则**：
- L2 = 强制方向按钮（与 Android 系统级"旋转锁定"按钮行为一致）：点一下 = 强制目标方向 + 持久化
- "自动" = 跟随系统旋转锁，**只能从设置项里进入**——避免"点了按钮但方向被系统旋转锁卡住"的困惑
- **无长按行为**——老年/儿童用户友好；与 Android 设计语言一致（系统级方向按钮也只用单击）

**实现要点**：
```kotlin
// PhoneTopBar.kt
val onToggleOrientation = {
    val current = orientationPref   // "auto" / "portrait" / "landscape"
    val next = when (current) {
        "portrait" -> "landscape"
        "landscape" -> "portrait"
        else -> "portrait"          // "auto" / 空 → 落 portrait（最常见）
    }
    scope.launch {
        display.setScreenOrientation(next)   // 写 pref + 触发 LaunchedEffect 重算 requestedOrientation
    }
}
```

**关键不变量**：
> **L1 设置项是真相之源**。`requestedOrientation` 由 L1 pref 唯一决定（去掉 v1.2 的"L2 临时"分支）。重启后 L2 切过的方向自然保留——这是与系统级按钮的核心差异（系统级按钮不持久化）。

**回归用例**（删去"长按"测试）：
- 单击 L2 切横屏 → 杀进程 → 冷启动后仍是横屏（✅ 写 pref）
- 单击 L2 进入「竖屏」→ 立即生效 + pref 落 portrait（✅）
- 在「自动」下点 L2 → 进入「竖屏」 + pref 落 portrait（✅）
- 想回到"自动" → 进设置 → 选「自动」（✅ 仅设置项可改）

**i18n 字符串变化**（§七同步更新）：
- 删：`np_orientation_toggle_long_press_cd`（长按提示）—— 整条删除
- 改：`nav_phone_orientation_cd` = "切换方向"（单击即可）
- 新增：设置项 `settings_orientation_auto_hint` 强调"自动 = 跟随系统旋转锁"

### 5.4 页面级方向策略

```kotlin
LaunchedEffect(orientationPref, currentScreen, showMv, showKaraoke, showVisualizer, isTVDevice) {
    if (isTVDevice) return@LaunchedEffect
    requestedOrientation = when {
        showMv || showKaraoke || showVisualizer -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        orientationPref == "portrait"  -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        orientationPref == "landscape" -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        else                           -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
```

| 页面 | 方向策略 | 理由 |
|------|---------|------|
| 首页 / 曲库 / 我的 / 设置 / 队列 / 详情 | 跟随全局策略 | 列表型内容 |
| 播放页 | 跟随全局策略 | 已做双模式布局 |
| MTV / K 歌 / 可视化舞台 | **强制横屏** | 内容为横屏比例 |
| 沉浸模式 | 跟随全局策略 | 已改封面铺满 |

> 全屏页退出后由 `LaunchedEffect` key（含 `showMv/showKaraoke/showVisualizer`）自动恢复，**不需要手动记状态**。

**首帧 Screen 默认 = Home（v1.3 D5 拍板）**：冷启动时 `currentScreen` 初值 = `Screen.Home`（`NavigationViewModel.kt:20` 已经是 `MutableStateFlow(Screen.Home)`），首屏方向按 Home 计算。**未来若支持"记住上次 Screen"，需在 `AppPreferences` 加 `lastScreen: String`，`onCreate` 同步读 → `navVM.navigateTo(...)`**——但本方案不做（P3 可选）。

### 5.5 ⚠️ 实现要点与坑

**(1) 首帧初值要在 `onCreate` 同步设置**

`LaunchedEffect` 在首次组合后执行，中间会有一帧处于 manifest 声明值。应在 `onCreate`（`setContent` 之前）同步设一次：

```kotlin
requestedOrientation = resolveOrientation(pref, screen = Screen.Home, isFullScreen = false)
```

**(2) 旋转重建 Activity —— 声明 `configChanges`**

Manifest 当前**未声明**（C6）→ 旋转会 destroy/recreate，导致：
- `onCreate` 里的 `POST_NOTIFICATIONS` / `RECORD_AUDIO` 权限请求**重弹**（`MainActivity.kt:131-161`）
- `BatteryOptimizationHelper.checkAndRequest` **重弹**（`:129`）
- Compose 焦点与动画状态丢失

`MainActivity` 增加：

```xml
android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden"
```

> ⚠️ 三点注意：
> ① **不要加 `uiMode` / `density` / `layoutDirection`** —— 会影响深色模式、字体缩放、RTL 的重建时机；
> ② **语言切换不是 `recreate()`**：实际路径是 `SettingsBranch.kt` 里 `startActivity(launchIntent + CLEAR_TASK|NEW_TASK) + finish()` 整进程重启（原文写 `recreate()` 与源码不符）。该路径不依赖配置重建，声明 `configChanges` 不影响它，但要**显式回归一次语言切换**（§11 风险表）；
> ③ 声明后 `MainActivity.onDestroy` 的 `if (!isFinishing) return` 守卫（`:395`）成为第二道保险，**保留不动**（它同时保护"从最近任务划掉"场景）。

**(3) 旋转绝不能中断播放**

播放真相在 `PlaybackService` + `PlayerManager`（Application 级），与 Activity 生命周期解耦；`MainViewModel` 由 `viewModels()` 持有，配置变更下存活。声明 `configChanges` 后连重建都不发生，风险归零。**回归必须验证：播放中连续旋转 10 次，播放不中断、进度连续、通知栏可控。**

**(4) 不要在 `onResume` 无条件重设方向**，只由 `onCreate` 初值 + `LaunchedEffect` 两处驱动。

**(5) 平板会一起变**：判定只有 `isTVDevice` 一个条件 → 平板走手机路径。竖屏在平板上"可用但偏空"，横屏保持两栏。P3 可选：按 `smallestScreenWidth >= 600dp` 分出 `TabletPortrait` 档。

**(6) 状态栏 / 导航栏 / 刘海 inset 处理（v1.3 D4 升 P0）**

竖屏必须正确处理系统 inset，否则：
- 刘海屏：顶部栏 `Logo + 应用名` 被刘海遮挡
- 挖孔屏：横屏时挖孔在左侧/右侧会盖住内容
- 手势导航：底部导航/MiniPlayer 视觉上与手势条重叠
- 三键导航：底部导航 56dp + 三键导航 48dp = 104dp，竖屏可用空间被砍 1/8

实现：
```kotlin
// PhoneTopBar.kt
Row(modifier = Modifier
    .fillMaxWidth()
    .statusBarsPadding()       // ★ 新增
    .height(56.dp)
    ...
)

// PhoneNavBar.kt  
Row(modifier = Modifier
    .fillMaxWidth()
    .navigationBarsPadding()   // ★ 已有（§8.4），但 MiniPlayer 也要补
    .height(56.dp)
    ...
)

// MiniPlayer.kt
Box(modifier = Modifier
    .fillMaxWidth()
    .navigationBarsPadding()   // ★ 新增
    ...
)
```

**注意**：
- `statusBarsPadding()` / `navigationBarsPadding()` 来自 `androidx.compose.foundation.layout`（编译类路径已确认）
- ✅ **本节已具备生效前提**：D9（v1.5 拍板）——竖屏 `PhonePortrait` 不再隐藏系统栏，`statusBarsPadding()` / `navigationBarsPadding()` 才会返回真实 inset；`TV` / `PhoneLandscape` / 沉浸模式仍隐藏且**不加**这两处 padding（保持现状）
- 横屏（PhoneLandscape / TV）**不要加**——TV 无系统栏；PhoneLandscape 现版本也未处理，**保持现状**
- `WindowCompat.setDecorFitsSystemWindows(window, false)` 已在 `MainActivity.kt:118` 设置（手机端全屏模式），与 inset 处理是配套的
- 建议额外加 `displayCutoutPadding()`：`statusBarsPadding()` 不覆盖挖孔区域，两者叠加才稳

**(7) 横竖屏切换的视觉过渡（v1.3 D7 → v1.5 D10 拍板：不做过渡，直接硬切）**

背景：横屏 ↔ 竖屏切换瞬间，Compose 重组中间帧理论上可能出现 1 帧错位。原 v1.3 用 `AnimatedContent` 兜底，但复核发现它会在 200ms 内**同时组合新旧两棵子树**，代价是三个真实副作用（B3）：

1. 旧子树 `onDispose { listBackHandler.value = null }` 把新子树刚注册的 handler 置空 → 旋转后 BACK 的 Level 1.5（列表回顶）静默失效；
2. 两棵子树的 `LaunchedEffect(Unit)` 各跑一次 → 每次旋转重复请求首页/曲库/天气数据；
3. 新子树的 `rememberLazyListState` 从 0 开始 → 列表滚动位置丢失（`Crossfade` 同理）。

**v1.5 拍板（D10）：不做过渡，直接硬切。** `AppRoot` 用裸 `Column`（不加 `AnimatedContent` / `Crossfade`），切换瞬间允许硬切；上述三个副作用一次性全部消失。

> 若将来确实要加缓动，**必须先完成**下方 (10) 列出的两项配套，再单独评估；§9 里原 D7 的过渡条目已删除。

**(8) 首帧 `currentScreen` 与 `requestedOrientation` 必须用同一份 pref**

冷启动时：
1. `MainActivity.onCreate`（同步）→ 读 `display.getScreenOrientationSync()` → 调 `requestedOrientation = resolveOrientation(pref, Screen.Home, isFullScreenPage=false)`
2. `setContent` 组合 → `LaunchedEffect(L1 pref)` 再设一次（幂等）

**避免**用 `Flow.collectAsState(initial = "auto")` 的初始值「auto」——首帧若 pref 是「portrait」但 `LaunchedEffect` 还没跑，会闪一帧 `UNSPECIFIED` → 视觉错位。
**正确做法**：`onCreate` 同步读一次 + `LaunchedEffect` 异步读同步刷新，两处用同一个解析函数 `resolveOrientation(pref, ...)`。

**(9) 系统栏可见性是 D4 的前提（v1.4 D9，必读）**

`MainActivity.kt:117-127` 在手机端已经 `WindowCompat.setDecorFitsSystemWindows(window, false)` + `WindowInsetsControllerCompat.hide(systemBars())`。当系统栏处于隐藏态时，`WindowInsets.statusBars` / `navigationBars` 通常报 **0**，因此：

- `statusBarsPadding()` 基本是 **no-op** → 刘海/挖孔屏上 `Logo + 应用名` 仍可能被遮挡（而且它**不含** `displayCutout`）
- `navigationBarsPadding()` 基本是 no-op → 三键导航手机需要"上滑唤出"才看得到导航条，此时内容**突然被顶起**（跳动）
- 底部上滑"唤出系统栏"与 MiniPlayer 上滑展开（P2-33）**会抢同一段手势**

**v1.5 拍板（D9）：竖屏不隐藏系统栏。** 具体规则：

| 场景 | `systemBars` | 说明 |
|------|-------------|------|
| `PhonePortrait` 且非沉浸/非全屏页 | **`show()`** | `statusBarsPadding()` / `navigationBarsPadding()` 生效；`PhoneTopBar` 追加 `displayCutoutPadding()` 兜底挖孔 |
| `PhoneLandscape` | `hide()`（现状） | 横屏手机保持改前行为（§3.1 B1） |
| `TV` | 不处理 | TV 无系统栏 |
| 沉浸模式 / MTV / K 歌 / 可视化舞台 | `hide()` | 全屏内容需真正铺满，与现状一致；随 `isImmersiveMode` 切换 |

`setDecorFitsSystemWindows(window, false)` **保持不变**（继续 edge-to-edge），由两处 padding 自行留白，否则会与 padding 双重计算。

实现骨架见 §8.3⑤；`§10.3` 用例 13/14/21（刘海屏、三键导航、系统栏唤出无跳动）必须真机录屏验证，不能只看代码。

**(10) 【备忘：本版不实施】将来若要引入过渡，必须处理"新旧子树并存"（v1.4 B3）**

> 本版已拍板硬切（D10），本节**不实施**，仅作为将来引入过渡时的清单。

`AnimatedContent`（`Crossfade` 同理）在过渡期间会**同时组合**新旧两棵子树 200ms。若不处理，会踩两个坑：

- **单槽 handler 被置空**：`HomeScreen` / `QueueScreen` / `AlbumDetailScreen` / `ArtistDetailScreen` / `PlaylistManagementScreen` 都是 `DisposableEffect { listBackHandler.value = handler; onDispose { listBackHandler.value = null } }`。新子树先注册，200ms 后旧子树 dispose 把它清成 null → **旋转后 BACK 的 Level 1.5（列表回顶）失效**。
  修法：`onDispose` 改成只清自己——
  ```kotlin
  DisposableEffect(Unit) {
      listBackHandler.value = handler
      onDispose { if (listBackHandler.value === handler) listBackHandler.value = null }
  }
  ```
  （`LocalNavigateBackHandler` 由 `AppRoot` 的 `LaunchedEffect` 写入，不受影响；但新增的 `navVM.settingsSection` 若也进 key 列表，需同样注意。）
- **页面级副作用重复执行**：`HomeBranch` 的 `LaunchedEffect(Unit) { loadHomeDashboard / loadRecentSongs / fetchWeather / loadRandomSongs }`、`MineBranch` 的 `loadRecentSongs`、`LibraryScreen` 的 `LaunchedEffect(activeTab)` 按需加载、`HomeScreen` 的 `onLoadSmartRadio` 等，都会因新子树组合而**再跑一遍**（含网络请求）。
  修法：这些 `LaunchedEffect(Unit)` 的 key 改为"数据标识"（如 `LaunchedEffect(currentScreen)`）或把加载收敛到 ViewModel 的幂等入口（大多数 `loadXxx()` 本身幂等，但要显式确认，不能默认）。
- 附带：过渡期间建议禁用内容区交互（`clickable(enabled = !isAnimating)`）避免双重点击（§8.6 已有此条）。
- **若不想做这两项配套 → 直接不做过渡（硬切）**，把"1 帧错位"降级为 P2 观感问题。**宁可硬切，也不要有静默失效的 BACK。**

**(11) 硬切下的页面状态（v1.5 更新）**

硬切**不会**引入双子树，也不改变 `Box(weight(1f))` 内容区的调用点位置，因此页面的 `remember` 状态（滚动位置、展开项）**通常会被保留**（`LocalConfiguration` 变化只触发重组，不销毁子树）。

但这一点必须**真机核实**：若实现时在内容区套了 `key(uiMode)`、或把内容区挪进条件分支，状态就会丢。加固建议（低成本，仍推荐做）：`LibraryScreen` 已有 `albumScrollIndex` / `artistScrollIndex`；可再补 `MineScreen.expandedPlaylistId` 与 `QueueScreen` 滚动位置。其余（如 Home 横向卡片滚动位置）接受重置。

---

## 六、导航与 BACK 键重构（v1.0 缺失）

### 6.1 现状

`AppRoot.kt:153-184` 用 `LaunchedEffect(currentScreen, isImmersiveMode.value, showMv, showKaraoke, showVisualizer)` 动态设置 `LocalNavigateBackHandler`，`MainActivity.kt:324-355` 按 5 级优先级消费：

```
Level 0   沉浸模式          → 退出全屏
Level 1   对话框打开        → 关闭对话框（RegisterDialogBackHandler 注册）
Level 1.5 列表已滚动        → 滚回顶部
Level 2   不在 NowPlaying   → 按 AppRoot 的 handler 导航
Level 3   在 NowPlaying     → 显示退出确认
```

现状 Level 2 分支（`AppRoot.kt:164-182`）中 `Screen.Settings` **无专门分支**，落 `else -> navigateHome`。

### 6.2 竖屏需要新增的分支

| 场景 | 期望 BACK 行为 | 实现 |
|------|---------------|------|
| 设置二级页（`settingsSection != null`） | 回设置列表 | 新增分支，**放在 `Screen.Settings` 之前** |
| 底部导航非首页页（曲库/我的/设置） | 回首页 | 现有 `else -> navigateHome` 覆盖 |
| 播放页（竖屏） | 收起为 MiniPlayer（回首页） | `currentScreen == NowPlaying -> navigateHome`（现状对非 TV 已是此行为） |
| 首页 | 退出确认 | 现状 `Screen.Home -> null` → 落 Level 3，**符合 Android 惯例，保留** |

> 结论：**只需新增「设置二级页」一个分支**，其余现有逻辑已适配。

**⚠️ 但有一个前置决定**：`selectedSection` 若留在 `SettingsScreen` 的局部 `remember` 里，`AppRoot` 的 BACK handler **读不到它**。必须先把该状态提升到 `NavigationViewModel`（与 `currentScreen` 同构），并把它加进 `AppRoot.kt:153` 那个 `LaunchedEffect` 的 key 列表。两种做法与取舍见 **§8.7**。

### 6.3 对话框 BACK 的强制约定

新增的底部弹层（歌曲信息、音质、睡眠定时等）若用 `Box` 覆盖层实现，**必须**走 `RegisterDialogBackHandler`（`ui/DialogBackHandler.kt`）；若是真 `Dialog {}`（独立窗口，Activity dispatcher 收不到），必须在 Dialog 内容内用 `androidx.activity.compose.BackHandler`。两者不可互换——`DialogBackHandler.kt` 的 KDoc 有明确警告。

---

## 七、i18n 与资源（v1.0 缺失）

`app/src/main/res/values/strings.xml` 与 `values-en/strings.xml` **各 898 条，必须同步新增**（CI 不校验一致性，靠人工）。

需新增的字符串（约 20 条）：

| key | zh | en |
|-----|----|----|
| `settings_display_orientation` | 屏幕方向 | Screen orientation |
| `settings_orientation_auto` | 自动 | Auto |
| `settings_orientation_portrait` | 竖屏 | Portrait |
| `settings_orientation_landscape` | 横屏 | Landscape |
| `settings_orientation_hint` | 跟随系统旋转开关（自动模式下被系统旋转锁限制） | Follow system rotation (auto mode respects system rotation lock) |
| `nav_phone_orientation_cd` | 切换方向（单击） | Toggle orientation (tap) |
| `nav_phone_home` / `_library` / `_playing` / `_mine` / `_settings` | 首页/曲库/播放/我的/设置 | 复用现有 `nav_*` |
| `miniplayer_cd_play` / `_pause` / `_next` | 播放/暂停/下一首 | Play/Pause/Next |
| `np_more` | 更多 | More |
| `np_mode_cover` / `np_mode_lyrics` | 封面 / 歌词 | Cover / Lyrics |
| `np_lyrics_source_cycle_cd` | 切换歌词来源 | Switch lyrics source |
| `settings_section_back_cd` | 返回设置 | Back to settings |
| `common_more_actions_cd` | 更多操作 | More actions |

> 现有 `nav_home` / `nav_library` / `nav_mine` / `nav_settings` / `nav_now_playing` 已存在（`AppRoot.kt:226-253` 在用），底部导航可直接复用，**不要新建重复 key**。

---

## 八、关键改动代码骨架（可开工）

> 本节给的是**已对齐现有代码范式**的骨架，不是伪代码。所有引用的类/函数/资源均已核验存在。
> 开发时以骨架为起点，细节按实际调整。

### 8.1 形态判定与方向决策（纯函数，可单测）

新增 `ui/theme/UiMode.kt`：

```kotlin
package com.nasmusic.tv.ui.theme

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

enum class UiMode { TV, PhonePortrait, PhoneLandscape }

/** 当前形态因子（MainActivity 提供） */
val LocalUiMode = staticCompositionLocalOf { UiMode.TV }

/**
 * 纯函数，便于 JVM 单测（不依赖 Compose / Android 运行时）。
 * ⚠️ TV 判定优先——TV 永不进竖屏分支。
 */
fun deriveUiMode(isTV: Boolean, orientation: Int): UiMode = when {
    isTV -> UiMode.TV
    orientation == Configuration.ORIENTATION_PORTRAIT -> UiMode.PhonePortrait
    else -> UiMode.PhoneLandscape
}

/**
 * 纯函数：全局策略 + 全屏页覆盖 → requestedOrientation。
 * ⚠️ 未知/空字符串一律回退 UNSPECIFIED（防御 DataStore 脏数据）。
 */
fun resolveOrientation(pref: String, isFullScreenPage: Boolean): Int = when {
    isFullScreenPage -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    pref == "portrait" -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    pref == "landscape" -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
```

> `ActivityInfo.SCREEN_ORIENTATION_*` 是 `public static final int` **编译期常量**，编译器会内联，
> 因此这两个函数在**纯 JVM 单测**（无需 Robolectric）下可直接断言。

### 8.2 `DisplayPrefs`（按现有子 pref 范式）

子 pref 是**薄委托**：真实键与逻辑在 `AppPreferences.kt`，子类只转发（参照 `LanguagePrefs.kt` 全文 13 行）。

`data/prefs/AppPreferences.kt` 内新增：

```kotlin
val display: DisplayPrefs by lazy { DisplayPrefs(this) }

private val keyScreenOrientation = stringPreferencesKey("display_screen_orientation")

val screenOrientation: Flow<String> = dataStore.data.map { it[keyScreenOrientation] ?: "auto" }

suspend fun setScreenOrientation(value: String) {
    dataStore.edit { it[keyScreenOrientation] = value }
}

// ── R-7 范式（v1.4 B4 修正）：主线程同步读走镜像，不用 runBlocking ──
// 与 getLanguageSync()（AppPreferences.kt:308，镜像 + @Volatile）保持同一套路。
private val displayMirrorPrefs =
    context.getSharedPreferences("display_mirror", Context.MODE_PRIVATE)

@Volatile private var cachedScreenOrientation: String =
    displayMirrorPrefs.getString(KEY_ORIENTATION_MIRROR, null) ?: "auto"

/** 冷启动同步读取（零 IO、零 runBlocking）。仅可在 onCreate 早期调用一次。 */
fun getScreenOrientationSync(): String = cachedScreenOrientation

// 由 application scope 常驻收集刷新镜像（照抄 cachedQualityTier / cachedMetingApiBaseUrl 既有写法）
internal fun bindScreenOrientationMirror(scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch {
        screenOrientation.collect { value ->
            cachedScreenOrientation = value
            displayMirrorPrefs.edit().putString(KEY_ORIENTATION_MIRROR, value).apply()
        }
    }
}
```

新增 `data/prefs/DisplayPrefs.kt`：

```kotlin
package com.nasmusic.tv.data.prefs

import kotlinx.coroutines.flow.Flow

/** 显示域子 Prefs（屏幕方向等设备本地偏好）。 */
class DisplayPrefs internal constructor(private val prefs: AppPreferences) {
    val screenOrientation: Flow<String> = prefs.screenOrientation
    suspend fun setScreenOrientation(value: String) = prefs.setScreenOrientation(value)
    fun getScreenOrientationSync(): String = prefs.getScreenOrientationSync()
}
```

> ⚠️ **v1.4 修正（B4）**：原稿让 `getScreenOrientationSync()` 走 `runBlocking { dataStore.data.first() }`
> 并称"参照 `getLanguageSync()`"。但 `getLanguageSync()` 早已是 **SharedPreferences 镜像 + `@Volatile` 内存快照**
> （`AppPreferences.kt:308`；`:967` 注释明确写着"读 @Volatile 内存镜像（原主线程急切求值 runBlocking）"），
> 主线程零 IO、零 `runBlocking`——项目刻意消除了这个模式（DataStore 首读可能触发文件 IO/迁移 → 主线程 ANR）。
> 因此**照抄镜像范式**（上方骨架），并在 `NasMusicApp.onCreate` 调一次 `bindScreenOrientationMirror(applicationScope)`。
> 首帧若镜像未就绪，回退 `"auto"` 是安全的（与 manifest `unspecified` 一致），代价最多一帧方向闪动。

### 8.3 `MainActivity` 改动

**① `onCreate` 内、`setContent` 之前**（首帧初值，避免 manifest 默认值闪现）：

```kotlin
val isTVDevice = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature("android.hardware.type.television")
if (!isTVDevice) {
    requestedOrientation = resolveOrientation(
        pref = (application as NasMusicApp).appPreferences.display.getScreenOrientationSync(),
        isFullScreenPage = false
    )
}
```

**② 删除**现有 `MainActivity.kt:170-179` 的整段（`isTVDevice` 的 `remember` + 强制 `SENSOR_LANDSCAPE` 的 `LaunchedEffect`）。

**③ `setContent` 内**新增：

```kotlin
// ✅ 直接读 LocalConfiguration.current —— 它是 CompositionLocal，配置变更会触发重组
val configuration = androidx.compose.ui.platform.LocalConfiguration.current
val uiMode = deriveUiMode(isTVDevice, configuration.orientation)

// 全屏页状态：MainActivity 独立收集一份（StateFlow 支持多订阅者，无副作用）
// 目的：方向是**窗口级**属性，归 Activity 管；AppRoot 内那份保持不动
val showMv by viewModel.mvVM.showMv.collectAsState(initial = false)
val showKaraoke by viewModel.vocalVM.showKaraoke.collectAsState(initial = false)
val showVisualizer by viewModel.visualizerVM.showVisualizer.collectAsState(initial = false)

val orientationPref by (application as NasMusicApp).appPreferences.display.screenOrientation
    .collectAsState(initial = "auto")

LaunchedEffect(orientationPref, showMv, showKaraoke, showVisualizer, isTVDevice) {
    if (isTVDevice) return@LaunchedEffect
    requestedOrientation = resolveOrientation(
        pref = orientationPref,
        isFullScreenPage = showMv || showKaraoke || showVisualizer
    )
}
```

**④ `CompositionLocalProvider` 增加一行**：

```kotlin
LocalUiMode provides uiMode,
```

**⑤ 系统栏显隐（v1.5 D9 拍板）**——`onCreate` 按**初始形态**决定，`setContent` 内按 `uiMode` 跟随：

```kotlin
// onCreate：把原有"无条件 hide(systemBars())"改成条件调用
if (!isTVDevice) {
    WindowCompat.setDecorFitsSystemWindows(window, false)      // 保持 edge-to-edge（不动）
    val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    with(WindowInsetsControllerCompat(window, window.decorView)) {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (portrait) show(WindowInsetsCompat.Type.systemBars())
        else hide(WindowInsetsCompat.Type.systemBars())
    }
}

// setContent 内：与方向同一个 LaunchedEffect 或并列一个，key 一致
LaunchedEffect(uiMode, isImmersiveMode.value, showMv, showKaraoke, showVisualizer) {
    if (isTVDevice) return@LaunchedEffect
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    val showBars = uiMode == UiMode.PhonePortrait && !isImmersiveMode.value &&
        !showMv && !showKaraoke && !showVisualizer
    if (showBars) controller.show(WindowInsetsCompat.Type.systemBars())
    else controller.hide(WindowInsetsCompat.Type.systemBars())
}
```

> `window` 就是 `Activity.getWindow()`，在 `MainActivity` 的 composable 作用域内可直接使用；首帧由 `onCreate` 处理，避免"先隐藏后显示"的闪烁。

### 8.4 `PhoneNavBar`

新增 `ui/components/PhoneNavBar.kt`。**零新增依赖**：`compose.foundation`（`Row`/`Column`/`background`/`navigationBarsPadding`）+ `tv-material3`（`Text`/`Icon`）+ 现成 `FocusableSurface`。

```kotlin
package com.nasmusic.tv.ui.components

private data class PhoneNavItem(val screen: Screen, val labelRes: Int, val icon: ImageVector)

// 6 项，与 §4.0 映射表一致
// ⚠️ 「队列」是 2026-09-19 真机反馈后新增的（§10.4 竖②）——原设计为 5 项，
//    队列只从「播放页 Chip / 我的页」进入，用户反馈"主按钮中缺少队列"。
//    图标用 `Icons.AutoMirrored.Filled.QueueMusic`（`Icons.Filled.QueueMusic` 已 deprecated）。
private val PHONE_NAV_ITEMS = listOf(
    PhoneNavItem(Screen.Home,       R.string.nav_home,         Icons.Filled.Home),
    PhoneNavItem(Screen.Library,    R.string.nav_library,      Icons.Filled.LibraryMusic),
    PhoneNavItem(Screen.NowPlaying, R.string.nav_now_playing,  Icons.Filled.PlayArrow),
    PhoneNavItem(Screen.Queue,      R.string.nav_queue,        Icons.AutoMirrored.Filled.QueueMusic),
    PhoneNavItem(Screen.Mine,       R.string.nav_mine,         Icons.Filled.Person),
    PhoneNavItem(Screen.Settings,   R.string.nav_settings,     Icons.Filled.Settings),
)

@Composable
fun PhoneNavBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(NasMusicColors.Surface)
            .navigationBarsPadding()          // 底部手势导航安全区
            .height(56.dp),                   // v1.3 D3: 容器 56dp，子项 fillMaxHeight 后每项触摸热区 = 56dp 高 × 60dp 宽（360dp/6），≥ 44dp ✓
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PHONE_NAV_ITEMS.forEach { item ->
            val selected = item.screen == currentScreen
            FocusableSurface(
                onClick = { onNavigate(item.screen) },
                modifier = Modifier.weight(1f).fillMaxHeight(),   // 不要加 padding(vertical=8.dp)，否则触摸热区 < 56dp 高
                shape = RoundedCornerShape(0.dp),
                focusedScale = 1.0f,          // 手机端不做缩放，避免整条抖动
                animationDurationMs = 150,
                containerColor = Color.Transparent,
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                // ⚠️ 未选中项用 TextPrimary(#E8EDF5) 而非 TextSecondary(#8899B0)：
                //    后者压在 Surface(#162032) 上辨识度不足（2026-09-19 真机反馈「主按钮看不清」，§10.4 竖①）
                contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                focusedContentColor = NasMusicColors.Primary,
                pressedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),   // 手机只保留按下反馈
                pressedScale = 0.96f,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, contentDescription = null, modifier = Modifier.size(24.dp))   // v1.3 D3: 22→24dp
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(item.labelRes),
                        fontSize = 12.sp,                                                       // v1.3 D3: 显式 12sp（不用 FontSize.caption）
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
```

> 核验：`Icons.Filled.Home` / `LibraryMusic` / `PlayArrow` / `Person` 已在 `AppRoot.kt:39-44` 使用；
> `Icons.Filled.Settings` 由 `material-icons-extended` 提供（编译类路径已确认）。
> `navigationBarsPadding()` 属 `androidx.compose.foundation:foundation-layout`（编译类路径已确认）。

### 8.5 `MiniPlayer` —— ⚠️ 进度订阅必须在内部

新增 `ui/components/MiniPlayer.kt`：

```kotlin
@Composable
fun MiniPlayer(
    viewModel: MainViewModel,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️⚠️ 进度/时长必须在**本 composable 内部**订阅，与 NowPlayingBranch.kt:78-81 同向。
    // AppRoot.kt:115-117 的 F-2 修复明确禁止在 AppRoot 顶层收集 progress/duration：
    // 进度由 PlayerManager 的 1000ms Handler 轮询驱动，顶层收集会每秒驱动 AppRoot
    // 全树重组（含 LazyColumn 状态与 D-Pad 焦点搜索）。放在这里 → 每秒只重组 MiniPlayer。
    val progress by viewModel.playerVM.progress.collectAsState(initial = 0L)
    val duration by viewModel.playerVM.duration.collectAsState(initial = 0L)
    val isPlaying by viewModel.playerVM.isPlaying.collectAsState(initial = false)
    val playerState by viewModel.playerVM.playerState.collectAsState()
    val song = playerState.currentSong ?: return

    val fraction = if (duration > 0) (progress.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Box(modifier = modifier.fillMaxWidth().background(NasMusicColors.Surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable(onClick = onExpand),      // 点击整条 → 播放页
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面 48dp（复用现有 CoverCarousel 或 AsyncImage，与 QueueScreen 侧卡一致）
            // 标题 / 艺术家两行（FontSize.button() / FontSize.caption()）
            // 右侧：播放暂停 + 下一首（用 FocusableSurface 包，保留 D-Pad 可用）
            //   viewModel.playerVM.playPause() / .next()
        }
        // 顶部 2dp 进度细线：drawBehind 内读 fraction，避免额外重组
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(fraction)
                .height(2.dp)
                .background(NasMusicColors.Primary)
        )
    }
}
```

> 「上滑展开」用 `Modifier.pointerInput { detectVerticalDragGestures { _, dy -> if (dy < -20) onExpand() } }`
> 叠加在整条 `clickable` 之上。**注意手势冲突**：两者同挂一个 Box 时 `clickable` 会先消费；
> 若实测点击失效，改为「整条只保留 `pointerInput`，在 `detectTapGestures` 的 `onTap` 里调 `onExpand()`」。
> 该细节放 P2-21 实测确定，P0 先只做 `clickable`。

### 8.6 `AppRoot` 结构调整

现状（`AppRoot.kt:186-374`）：

```
Column(fillMaxSize) {
    if (!沉浸 && !showMv && !showVisualizer) { Row { 顶部导航 6 项 } }   // :188-258
    Box(fillMaxWidth().weight(1f)) { when (currentScreen) { ... } }      // :266-364
    if (showVisualizer) VisualizerOverlay(...)                           // :367-374
    // 加入歌单 / 音质选择等 dialog                                       // :376-417
}
```

目标：

```kotlin
// v1.5 D10：**不加任何过渡**（裸 Column），硬切由 PM 拍板
// ⚠️ v1.4 B1：以下分支谓词只能是 `== UiMode.PhonePortrait`，**不能**写 `== / != UiMode.TV`
Column(modifier = Modifier.fillMaxSize()) {
        // ① 顶部栏：按形态分支（⚠️ v1.4 B1：只有 PhonePortrait 用新栏；TV 与 PhoneLandscape 都走现状）
        if (!isImmersiveMode.value && !showMv && !showVisualizer) {
            if (uiMode != UiMode.PhonePortrait) {
                // 现状顶部栏（TV + 手机横屏，逻辑原样搬进 TvTopNavBar，零行为变化）
                TvTopNavBar(currentScreen = currentScreen, onNavigate = viewModel.navVM::navigateTo)
            } else {
                // v1.5 D9: PhoneTopBar 用 statusBarsPadding() + displayCutoutPadding() 处理刘海/挖孔
                // （前提：竖屏不隐藏系统栏，见 §5.5(9) / §8.3⑤）
                PhoneTopBar(
                    onNavigateToSearch = ...,
                    onToggleOrientation = ...,         // 单击循环竖/横 + 写 pref（§5.3 D1，无长按）
                    currentL1Pref = orientationPref,   // 图标状态显示 L1 真值（"自动" / 竖 / 横）
                )
            }
        }

        // ② 内容区：完全不动
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) { when (currentScreen) { ... } }

        // ③ 手机端底部：MiniPlayer + 底部导航
        //    ⚠️ 必须放在 Box(weight(1f)) 之后、VisualizerOverlay 之前
        //    —— 这样可视化舞台仍能盖住底部栏
        if (uiMode == UiMode.PhonePortrait &&
            !isImmersiveMode.value && !showMv && !showKaraoke && !showVisualizer
        ) {
            if (currentScreen != Screen.NowPlaying) {          // 播放页自身即播放器，不重复
                // v1.3 D4: MiniPlayer 用 Modifier.navigationBarsPadding() 处理三键导航
                MiniPlayer(viewModel = viewModel, onExpand = { viewModel.navVM.navigateTo(Screen.NowPlaying) })
            }
            // PhoneNavBar 内部已用 navigationBarsPadding()（§8.4）
            PhoneNavBar(currentScreen = currentScreen, onNavigate = { viewModel.navVM.navigateTo(it) })
        }

        if (showVisualizer) VisualizerOverlay(...)
        // dialog 不动
    }
}
```

> ℹ️ 【备忘，本版不适用】若将来引入过渡，需处理过渡期间连续点击：200ms 内禁用内容区 `clickable`（`LaunchedEffect(uiMode)` 结束后恢复）。本版硬切无此问题。

> ⚠️ **v1.5 补充（必读）**：
> ① `uiMode == UiMode.PhonePortrait` / `uiMode != UiMode.PhonePortrait` 是**硬规则**——写反成 `== / != UiMode.TV` 会让横屏手机切到新 chrome，直接违反 §10.3 用例 3；
> ② **不加任何过渡**（D10 拍板硬切）——裸 `Column` 即可，**不要**引入 `AnimatedContent` / `Crossfade`，否则会带回 B3 的三个副作用；
> ③ 首/尾栏的 `statusBarsPadding()` / `navigationBarsPadding()` 在 D9（竖屏显示系统栏）下生效；`TV` / `PhoneLandscape` 仍不加这两处 padding。

> ⚠️ K 歌页不是 AppRoot 覆盖层——它是 `NowPlayingScreen.kt:171-206` 内部的分支（直接 `return`）。
> 因此隐藏底部栏必须把 `showKaraoke` 写进条件（`AppRoot.kt:140` 已收集该状态）。
> ⚠️ 现状 `AppRoot.kt:188` 的**顶部栏**条件不含 `showKaraoke`（TV 上 K 歌页上方也一直有导航栏）。
> 竖屏因强制横屏不会与 PhoneTopBar 共存，**本次不新增该条件**，保持现状以免改动 TV 行为。

### 8.7 设置页两级化 + BACK 提升

**⚠️ 关键设计决定**：`selectedSection` 若留在 `SettingsScreen` 局部，`AppRoot` 的 BACK handler 读不到。
两种做法：

| 方案 | 做法 | 评价 |
|------|------|------|
| **(a) 提升到 `NavigationViewModel`** ✅ 推荐 | `navVM.settingsSection: StateFlow<SettingsSection?>`；`AppRoot` 的 BACK handler 读它；`LaunchedEffect` key 加 `settingsSection` | BACK 语义属导航，归 navVM 一致；与现有 `currentScreen` 同构 |
| (b) 页内注册 Level 1 | `SettingsScreen` 内 `RegisterDialogBackHandler { selectedSection = null }` | 改动更小，但把「页面级返回」伪装成「对话框级」，优先级语义错位 |

```kotlin
// SettingsScreen.kt
val isPortraitPhone = LocalUiMode.current == UiMode.PhonePortrait
val selectedSection by viewModel.navVM.settingsSection.collectAsState()

if (isPortraitPhone) {
    val section = selectedSection
    if (section == null) {
        SettingsSectionList(onPick = { viewModel.navVM.openSettingsSection(it) })   // 一级：9 行
    } else {
        Column {
            SectionBackHeader(titleRes = section.titleRes, onBack = { viewModel.navVM.closeSettingsSection() })
            SectionContent(section)         // 二级：现有 when(activeSection) 内容，单列
        }
    }
} else {
    // 现状左右分栏，零改动
}
```

```kotlin
// AppRoot.kt:164-182 的 when 内新增（放在 currentScreen == Screen.Settings 之前）
val settingsSection by viewModel.navVM.settingsSection.collectAsState()
...
currentScreen == Screen.Settings && settingsSection != null -> closeSettingsSection
```

⚠️ `SettingsSection` 当前是 `private enum`（`SettingsScreen.kt:101`）→ 需放开为 `internal` 并移到
`ui/screens/settings/` 下（或提到 `SettingsComponents.kt`），供 `NavigationViewModel` 引用。

⚠️ **依赖方向（v1.4 补充）**：`NavigationViewModel` 在 `ui/viewmodel/`，引用 `ui/screens/` 的枚举会造成 viewmodel → screens 的反向依赖。两种规避：① 把枚举提到独立文件 `ui/screens/settings/SettingsSection.kt` 并保持 `internal`（推荐）；② navVM 只存 `Int` 索引，`SettingsSection.entries` 在 UI 侧解析。

---

## 九、实施计划

### P0 — 地基 + 主页面（约 3 天）★ 做完即可验证方向切换

| # | 任务 | 文件 | 骨架 |
|---|------|------|------|
| 1 | 新增 `UiMode` / `LocalUiMode` / `deriveUiMode` / `resolveOrientation`（**纯函数，同时写单测**） | 新增 `ui/theme/UiMode.kt` | §8.1 |
| 2 | `MainActivity`：`onCreate` 同步设方向初值 + `LaunchedEffect` 按 §5.4 更新；**删除** `:170-179` 强制 `SENSOR_LANDSCAPE` | `ui/MainActivity.kt` | §8.3 |
| 3 | Manifest：`screenOrientation` 改 `unspecified` + 声明 `configChanges` | `AndroidManifest.xml:85` | §5.5 |
| 4 | 新增 `DisplayPrefs.screenOrientation`（默认 `auto`） | `data/prefs/AppPreferences.kt` + 新增 `DisplayPrefs.kt` | §8.2 |
| 5 | 设置 → 通用 增加「屏幕方向」三选一 | `settings/GeneralSettingsSection.kt`、`SettingsScreen.kt` | — |
| 6 | `PhoneTopBar`（Logo + 搜索 + **L2 方向切换图标**，单击循环竖/横 + 写 pref，**无长按**，见 §5.3 D1）+ `Modifier.statusBarsPadding()`（D4）+ 抽出 `TvTopNavBar` | `ui/components/AppRoot.kt` → 新增 `PhoneTopBar.kt` | §8.6 |
| 7 | **`PhoneNavBar`**（`compose.foundation` + `tv-material3` 自建，**禁用 material3**；**Icon 24dp + height 56dp，子项 `fillMaxHeight()`、不加 `padding(vertical)`**；触摸热区 Compose 56dp ≈ 物理 45.9dp，D3 + §2.7） | 新增 `ui/components/PhoneNavBar.kt` | §8.4 |
| 8 | **`MiniPlayer`**（⚠️ **进度必须在内部订阅**，见 K1；**外层 `navigationBarsPadding()`** 处理三键导航，D4） | 新增 `ui/components/MiniPlayer.kt` | §8.5 |
| 9 | `AppRoot` 结构调整：**`if (uiMode == UiMode.PhonePortrait)` 正向分支**——`Column { 新顶部栏; Box(weight1f){内容}; MiniPlayer; PhoneNavBar }`，`else` 保持现状；**不加任何过渡（D10 硬切）** | `ui/components/AppRoot.kt:186-374` | §8.6 |
| 10 | `selectedSection` 提升到 `NavigationViewModel`（见 K2） | `ui/viewmodel/NavigationViewModel.kt` | §8.7 |
| 11 | BACK 新增「设置二级页」分支 | `ui/components/AppRoot.kt:164-182` | §8.7 |
| 12 | 设置页两级化（`SettingsSection` 放开为 `internal`，并规避 viewmodel→screens 反向依赖） | `screens/SettingsScreen.kt:101-111,257` | §8.7 |
| 13 | 播放页双模式 + 歌词工具条 4 Chip（**来源循环 + 字号循环 + 睡眠 + 高亮模式移到次级操作 Chip 区**，D2）+ 封面比例式 | `screens/NowPlayingScreen.kt:236-493`、`:325-408` | — |
| 14 | 队列页折叠横条 + 单列 | `screens/QueueScreen.kt:118-125` | — |
| 15 | 首页 2×2 统计网格 + 隐藏 `NowPlayingCard` + 卡片 **160→140dp** | `screens/HomeScreen.kt:287,664,541,603,395` | — |
| 16 | 专辑 / 艺术家详情竖屏 | `screens/AlbumDetailScreen.kt`、`ArtistDetailScreen.kt` | — |
| 17 | 全屏页方向覆盖（MTV / K 歌 / 舞台） | `ui/MainActivity.kt` | §8.3 |
| 18 | i18n：新增约 20 条字符串（**zh + en 同步**） | `res/values/strings.xml`、`res/values-en/strings.xml` | §七 |
| 19 | **状态栏/导航栏/刘海 inset 处理**（PhoneTopBar/MiniPlayer/PhoneNavBar 三处 `statusBarsPadding` / `navigationBarsPadding`，D4 升 P0；⚠️ 需 D9 前提，见 §5.5(9)） | `ui/components/PhoneTopBar.kt`、`PhoneNavBar.kt`、`MiniPlayer.kt` | §5.5(6) |
| 20 | **D9：竖屏显示系统栏**（`PhonePortrait` 进入时 `show(systemBars())`，退出恢复现状；`PhoneTopBar` 追加 `displayCutoutPadding()`） | `ui/MainActivity.kt` | §5.5(9) |
| ~~21~~ | ❌ **已取消**（v1.5 D10 拍板硬切 → 无过渡 → 无双子树，B3 整体消除） | — | §5.5(10) 备忘 |
| ~~22~~ | ❌ **已取消**（同上：无第二棵子树，页面副作用不会重复触发） | — | §5.5(10) 备忘 |
| 23 | **顶部行溢出修复（当前根本滑不到）**：`SearchTab.SearchSourceBar` 来源 Chip 行、`RadioTab` 顶部 preset tag 行 | `screens/library/SearchTab.kt:194-240`、`RadioTab.kt:74-110` | §2.4 |
| 24 | **详情页头部操作行**拆两行 / 横向滚动（返回+标题+播放全部+加入队列+歌单数） | `screens/AlbumDetailScreen.kt:96-155`、`ArtistDetailScreen.kt:95-154` | §2.4 |
| 25 | **服务器连接页竖屏化**（760dp → `fillMaxWidth() + widthIn(max=420.dp)`） | `screens/ServerConnectScreen.kt:150` | §2.4 / §4.7 |
| 26 | **按 §2.7 复核全部竖屏 dp 口径**（含 D3 触摸目标：物理 44dp = Compose ≥ 53.7dp） | 全量竖屏组件 | §2.7 |

**P0 验收（v1.5 修订，§10.3 对齐）**：手机竖屏/横屏均可正常浏览全部页面，无崩溃、**无固定组件被推屏外（关键内容可通过滚动可见即合格，§2.6）**；设置项切换方向即时生效；**L2 单击行为符合 §5.3**（无长按）；旋转 10 次播放不中断；**播放中滚动列表不卡顿（验证 K1 未被破坏）**；**TV 端 UI 零变化 + 手机横屏与改前一致（B1 硬规则）**；**横屏↔竖屏切换为硬切：无白帧 / 无异常帧**（D10，不再要求缓动）；**旋转后 BACK 的 Level 1.5 仍生效（护栏，用例 19）**；**旋转 10 次不产生重复数据请求（护栏，用例 20）**；**竖屏显示状态栏/导航栏、刘海不被遮挡、系统栏唤出无跳动（D9）**；**来源 Chip / preset tag / 详情页操作按钮全部可达（P0-23/24）**。

### P1 — 二级页面（约 2 天）

| # | 任务 | 文件 |
|---|------|------|
| 27 | 对话框族统一响应式宽度（13 处，见 §2.4） | 各 dialog |
| 28 | 网盘 / 歌单管理 单列（服务器连接已升 P0-25） | 见 §4.7 |
| 29 | 歌曲信息面板改底部弹层（自建，走 `RegisterDialogBackHandler`） | `NowPlayingScreen.kt:743`、`components/SongInfoPanel.kt` |
| 30 | `adaptiveColumns` 上移到 `CommonComponents.kt`（改 `UiMode + widthDp` 双输入，见 §2.7/§3.4），并替换 `RadioTab.kt:162` 的 `Fixed(2)` | `components/CommonComponents.kt`、`screens/library/RadioTab.kt` |
| 31 | `Shimmer` 骨架网格改 `adaptiveColumns(6, 3, 6)`（与真数据列数一致） | `components/Shimmer.kt:135,153` |
| 32 | 「自适应布局」工具函数（`AdaptiveLayout(phonePortrait, tv)` lambda 包装）+ 自定义 lint 规则（新增 Screen 必须有 UiMode 分支）+ 维护约定文档 | `components/CommonComponents.kt` + `tools/lint/` + `docs/conventions-adaptive-ui.md` |

### P2 — 打磨（约 1-2 天）

| # | 任务 |
|---|------|
| 33 | 手势：MiniPlayer 上滑展开、播放页左右滑切模式、详情页下滑返回（⚠️ 与 D9 底部系统手势冲突，需实测） |
| 34 | 触摸反馈（涟漪、按压缩感）与 TV 焦点态并存验证 |
| 35 | 播放统计热力图可选放大（横向滚动） |
| 36 | 平板 `sw>=600dp` 可选分档（含 `TabletPortrait`） |
| 37 | 竖屏缩放系数是否从 0.82 调到 0.88（**必须同时处理 `LYRICS_RECOVER_SCALE` 与 §2.7 全部口径**） |
| 38 | 清理 `PlayerControls.kt:133` 遗留 `AppLog.e`（§0.3） |
| 39 | 修 `FocusableSurface` 的 `isTVDevice` 判定（只会 leanback → 加上 `type.television`，§0.3） |
| 40 | 真机截图验收（**由用户安装 release 包后人工确认，不自动启动应用**） |

---

## 十、测试计划（v1.0 缺失）

### 10.1 可单测的纯函数（现有 79 个测试文件，沿用 JUnit4 风格）

把方向决策与形态判定抽成纯函数，放在 `util/` 或 `ui/theme/`，避免依赖 Compose 运行时：

```kotlin
// 建议签名
fun resolveOrientation(pref: String, isFullScreenPage: Boolean): Int
fun deriveUiMode(isTV: Boolean, orientation: Int): UiMode
fun adaptiveColumnsOf(widthDp: Int, tv: Int, phonePortrait: Int, phoneLandscape: Int): Int
```

| 用例 | 断言 |
|------|------|
| `resolveOrientation("auto", false)` | `SCREEN_ORIENTATION_UNSPECIFIED` |
| `resolveOrientation("portrait", false)` | `SCREEN_ORIENTATION_USER_PORTRAIT` |
| `resolveOrientation("portrait", true)` | `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`（全屏页覆盖） |
| `resolveOrientation("", false)` / 未知值 | 回退 `UNSPECIFIED`（防御脏数据） |
| `deriveUiMode(true, PORTRAIT)` | `TV`（TV 永不进竖屏分支） |
| `deriveUiMode(false, PORTRAIT/LANDSCAPE)` | `PhonePortrait` / `PhoneLandscape` |
| `adaptiveColumnsOf(360, 6, 3, 6)` | 3（竖屏） |
| `adaptiveColumnsOf(873, 6, 3, 6)` | 6（横屏） |
| `adaptiveColumnsOf(1000, 6, 3, 6)` | 6（TV 边界，验证 `>=1000`） |
| `adaptiveColumnsOf(599, 6, 3, 6)` | 3（边界下侧） |
| `adaptiveColumnsOf(600, 6, 3, 6)` | 6（边界上侧，验证 `>=600`） |
| `deriveUiMode(false, LANDSCAPE) != UiMode.TV`（B1 回归） | 锁定 §3.1 硬规则：横屏手机不得落入 TV 分支 |
| `PHONE_UI_SCALE` 口径（§2.7） | 常量断言：`56 × 0.82 ≈ 45.9 ≥ 44`；且 `44 / 0.82 ≈ 53.7`（D3 与 §2.7 的回归护栏） |

> v1.5 补充：D9（系统栏显隐）没有纯函数可测，验收靠 `§10.3` 用例 13/14/21；D10 已拍板硬切，原用例 19/20 降为**低成本护栏**（预期直接通过）。

### 10.2 必跑门禁（AGENTS.md 规定的本机三件套）

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat assembleDebug lintDebug testDebugUnitTest --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
```

- 单测基线：**547 例 / 0 失败**（上次记录；以当次实际为准，不得下降）
- lint 基线：**0 Error**（Warning 数历史记录在 254~256 之间浮动，**以当次实际为准，硬指标是不新增 Error**）
- ⚠️ `assembleDebug` 不编译 test 源码 → **改了测试必须跑 `testDebugUnitTest`**
- ⚠️ 低 minSdk 的 API level 错误只有 lint 能抓（`putIfAbsent` API 24+、`java.time` API 26+ 编译过、单测过、电视上 `NoSuchMethodError`）→ 本方案新增代码用 `getOrPut` / `java.util.Calendar`

### 10.3 真机矩阵（由用户执行安装，v1.3 补完 / v1.4 追加 19–24）

**核心验收标准（v1.3 放宽）**：
- ✅ **关键内容可见**：所有页面主要信息（标题、卡片、按钮）都在可视区内或可滚动到达
- ✅ **Modal/Dialog 无截断**：所有弹层/对话框必须完整显示，必要时滚动可见
- ✅ **固定组件不被推屏外**：顶栏、MiniPlayer、底部导航、播放页控制行/进度条/封面必须完整显示
- ✅ **触摸可用**：所有可点击元素触摸热区 ≥ 44dp（**物理 dp** 口径，Compose 需 ≥ 53.7dp，见 §2.7）
- ❌ **不算缺陷**：内容型页面（首页/曲库/我的）需要纵向滚动到达底部——这是预期体验

**v1.4 追加验收（针对 §0.8 的 5 项阻断）**：
- ✅ **B1**：手机横屏逐页与改前像素级对照一致（目录栏/详情页/队列页均不得出现新 chrome）
- ✅ **B2**：刘海屏竖屏 Logo/应用名不被遮挡；手动上滑唤出系统栏时内容**不跳动**
- ✅ **B3**：旋转 5 次后，在首页/曲库/队列/详情页按 BACK 仍能先回列表顶部（Level 1.5 未失效）
- ✅ **B5**：竖屏各页无组件被水平裁切；底部导航 **6 项**触摸热区物理 ≥ 44dp（2026-09-19 真机反馈后由 5 项增至 6 项，§10.4 竖②）

**真机场景矩阵**：

| # | 设备 | 场景 | 验证点 | 通过条件 |
|---|------|------|--------|----------|
| 1 | 电视 192.168.0.114（SDK 22 / 5.1.1 / armeabi-v7a） | 全流程 | UI 零变化、无崩溃 | TV 横屏布局与改前 1 像素不差 |
| 2 | 手机竖屏 | 全流程 | **关键内容可见 + 固定组件完整 + 触摸可用** | 所有页面可滚动到底；顶栏/底部导航/MiniPlayer 不被遮挡 |
| 3 | 手机横屏 | 全流程 | 与改前一致 | 复用 TV 横屏布局，零变化 |
| 4 | 手机竖屏 | 播放中连续旋转 10 次 | 播放不中断、进度连续、通知栏可控 | 10 次旋转期间播放无中断、进度连续 |
| 5 | 手机 | 设置切「自动/竖屏/横屏」 | 即时生效；锁竖屏后进 MTV 仍横屏、退出后回竖屏 | L1 pref 切换 → 方向立即改变 |
| 6 | 手机 | 旋转后检查权限弹窗 | **不重弹**（验证 `configChanges` 生效） | 旋转 5 次无任何权限弹窗重现 |
| 7 | 手机 | **横屏 → 竖屏切换瞬间（硬切）** | 无白帧 / 无残余布局 / 无崩溃（D10） | 录屏检查切换帧：画面直接切到目标布局即可（**允许硬切观感**） |
| 8 | 手机 | **「自动」+ 系统旋转锁** | 尊重系统锁、不强行旋转 | 系统锁竖屏时即使摇横屏，App 不转 |
| 9 | 手机 | **从 MTV / K 歌退出** | 方向恢复到 L1 pref（不是默认横屏） | MTV 强制横屏退出后，L1 是竖屏则立即回竖屏 |
| 10 | 手机 | **冷启动首帧方向** | 首屏直接进入正确方向，无 1 帧 `UNSPECIFIED` 闪现 | 录屏首 500ms 无方向闪烁 |
| 11 | 手机 | **L2 单击切换** | 竖/横循环 + 立即写 pref，**无长按** | 单击竖→横：立即生效 + pref 落 landscape；杀进程重启后仍是横屏（✅ 持久化） |
| 11b | 手机 | **L2 在「自动」下单击** | 进入「竖屏」 + 写 pref | 自动→点 L2→竖屏 + pref 落 portrait；想回自动必须进设置 |
| 13 | 刘海屏手机（如华为 P40 / iPhone 13+） | 竖屏顶部栏 | `statusBarsPadding()` 生效 | Logo/应用名不被刘海遮挡 |
| 14 | 三键导航手机（如老款三星） | 竖屏底部 | `navigationBarsPadding()` 生效 | 底部导航不被三键导航条遮挡 |
| 15 | 低密度手机（sw360dp @ hdpi） | 竖屏首页 | 文字清晰、触摸可用 | 字号下限 ≥ 10sp、触摸热区 ≥ 44dp |
| 16 | 手机 | **7 Chip 改造后验证** | 高亮模式、字号循环、来源循环、睡眠 4 项均可在 ≤ 2 次点击内切换（D2） | 不需要进"更多"菜单 |
| 17 | 手机 | **底部导航触摸** | **6 项**导航（含队列）切换准确、不误触 | 触摸 100 次，误触率 = 0（D3 触摸目标 ≥ 44dp） |
| 18 | 手机 | **对话框 13 处** | 全部 `fillMaxWidth(0.92f) + widthIn(max=420.dp) + heightIn(max=0.8f) + verticalScroll` | 竖屏无截断、可滚条变流 |
| 19 | 手机 | **旋转后 BACK 的 Level 1.5**（护栏，B3 已由 D10 消除） | 首页/曲库/队列/详情页先滚到底 → 旋转 → 按 BACK | 先回列表顶部，**不得**直接触发页面导航/退出 |
| 20 | 手机 | **旋转不重复拉数据**（护栏，B3 已由 D10 消除） | 开日志统计 `loadHomeDashboard` / `fetchWeather` / `loadSongsFirstPage` 调用次数 | 旋转 10 次，每个接口调用次数 **= 1（不是 10）** |
| 21 | 手机 | **系统栏与手势（B2/D9）** | 竖屏手动上滑唤出导航栏 | 内容不跳动；PhoneNavBar 仍可点（不被系统栏盖住）；MiniPlayer 上滑不误触 |
| 22 | 手机竖屏 | **顶部行可滑性**（P0-23/24） | 曲库 → RADIO 的全部 preset tag；曲库 → SEARCH 的全部来源 Chip；专辑/艺术家详情的「播放全部/加入队列」 | 每个按钮都能看见并点击，**无裁切** |
| 23 | 手机横屏 | **逐页对照改前（B1 硬规则）** | 首页/曲库/我的/播放/队列/设置/详情 | 与改前截图逐页一致（**无新顶部栏、无底部导航、无 MiniPlayer**） |
| 24 | 手机 | **dp 口径抽查（B5）** | 底部导航按钮实测物理尺寸；文字最小字号 | 每项物理 ≥ 44dp；字号物理 ≥ 10sp（Compose ≥ 12.2sp） |

> ⛔ 按项目约定：产物就绪后**告知用户**由用户安装测试；**不自动启动应用**（不 `am start` / `monkey` / `input`）。

### 10.4 真机反馈轮（2026-09-19，v2.36.0 内修复，未升版本号）

用户安装 release 包上机验收后报出 **7 条问题**。逐条落点如下（技术细节见
`docs/technical-overview.md` §10.163）：

| # | 用户原话 | 落点 | 结论 |
|---|---------|------|------|
| 竖① | 下方的几个主按钮，都要改成亮色的，因为深色背景，现在根本看不清 | `FocusableSurface.kt` | **根因级**：`androidx.tv.material3.LocalContentColor` 默认 `Color.Black`，而 `FocusableSurface` 只下发了 `LocalFocusableContentColor` → 内容体里的裸 `Icon`/`Text` 全画成黑色。全仓库受影响 **10 处**（4 Icon + 6 Text），**TV 上同样存在** |
| 竖⑤ | 标题行的几个按钮也要改成亮色的 | 同上 | 与竖①**同根因**，一处修复同时覆盖 |
| 竖② | 主按钮中缺少队列，应该加一个，我看有地方 | `PhoneNavBar.kt` | 5 项 → **6 项**，插在「播放」与「我的」之间；`Icons.AutoMirrored.Filled.QueueMusic`。`nav_queue` 字符串**已存在**（`values`/`values-en` 第 23 行） |
| 竖③ | 播放页面分为封面和歌词，要能够支持左右滑动切换 | `NowPlayingScreen.kt` | 手势从底部 **28dp** 的 `PortraitModeIndicator` 上移到**整块内容区**；原实现**不分方向、只做 toggle**（等于"不支持左右滑"）→ 现加方向语义（左滑→歌词 / 右滑→封面）+ 48dp 阈值 |
| 竖④ | 歌曲条目在竖屏模式要将几个内嵌按钮和时长单独加一行 | `UnifiedSongRow.kt` | `MODE_ROW` 竖屏拆两行（第一行封面 64dp + 序号 + 歌名/艺术家；第二行时长 + 操作按钮）。顺带补 `RowActionButton` 触摸目标（原 48×42 dp → 竖屏仅 **39.4×34.4 物理 dp**，P0-26 漏网） |
| 竖⑥ | 封面模式下，控制按钮和进度条应该紧贴屏幕下方 | `NowPlayingScreen.kt` | 原整列 `verticalScroll` → 控制区紧跟封面、下方空一大片。改「弹性区（`BoxWithConstraints` 按剩余高度反推封面边长）+ 固定贴底区」 |
| 横① | 横屏模式就不要 mini 播放条了，太占空间 | `HomeScreen.kt` | 定位到的是**首页 `NowPlayingCard`**（72dp 全宽横条），**不是 `MiniPlayer`**（后者本就只在竖屏渲染）。条件 `!isPhonePortrait` → `uiMode == UiMode.TV`。⚠️ 此处**显式**读 `LocalUiMode` 做横屏独立分支（B1 允许，理由为用户明确要求），TV 端显示条件不变 |

**附带修掉的第二层根因（竖①⑤ 的另一半）**：**粘滞焦点态** —— 手机触摸后 `clickable`/`focusable()`
节点获焦且**焦点粘住**，此前 P2-34 只修了"永久放大 8%"，容器色/内容色仍粘 → 底栏/顶栏图标
**点过一次就永久高亮**。现把焦点相关**全部视觉**收敛到 `activeFocus = isFocused && isTVDevice`。
维护约定见 `docs/conventions-adaptive-ui.md` §11。

**门禁**：新增 `FocusableSurfaceColorContractTest`（2 + 4 例，含负向自证）→ 单测基线 842 → **848 例**。

**验收状态**：⏳ 待用户重新上机确认这 7 条（按项目约定不代装、不自动启动应用）。

> ⚠️ 本表也修正了 §10.3 矩阵中的两处口径：第 17 行的「底部导航 **5 项**」现为 **6 项**；
> 第 24 行 dp 抽查同步纳入新增的队列入口。

---

### 10.5 review 轮（2026-09-19，同日，v2.36.0 内）

对 §10.4 的全部改动做整体复查（4 个改动文件 + 10 项外部事实交叉核实），
**又发现 4 个缺陷 + 1 项性能问题**，全部已修（技术细节见
`docs/technical-overview.md` §10.163.4）：

| # | 缺陷 | 文件 | 修法 |
|---|------|------|------|
| R1 | 竖屏封面模式歌名区预留**写死 `96.dp`** —— 字号 +8 档下「歌名 2 行 + 艺术家 1 行」≈ 115dp → **溢出压住进度条** | `NowPlayingScreen.kt` | 按 `FontSize.title()/small()` **动态计算**；下界 `96.dp` → `0.dp`（宁可封面缩小） |
| R2 | 竖屏歌曲行第一行**固定 `height(88.dp)`** —— 同上，两行文字被裁 | `UnifiedSongRow.kt` | 竖屏 `heightIn(min = 88.dp)`（非竖屏仍 120dp，B1 逐字等价） |
| R3 | 模式指示器圆点触摸目标仅 **6~8 Compose dp**（4.9~6.6 物理 dp）—— ⚠️ **P0-26 自查 grep 的盲区**（正则只覆盖 40~53dp） | `NowPlayingScreen.kt` | 外层 `size(portraitTouchTarget(44.dp))` 承担热区，内层小 `Box` 只做视觉 |
| R4 | 底栏 6 项后英文 `nav_now_playing`（"Now Playing" ≈ 66dp vs 每项 65dp）**被裁** | `PhoneNavBar.kt` + 两语言 strings | 新增短标签 `nav_now_playing_short`（播放 / Playing）仅供底栏 + `overflow = Ellipsis` 兜底。⚠️ **预防性修复，未经真机确认** |
| R5 | `isTVDevice()` 每次组合做 **2 次 `hasSystemFeature`**（143 处 `FocusableSurface` + 每个 `RowActionButton` 组合期调用） | `FocusableSurface.kt` | 加 `remember(context)` 缓存 |

**复查确认「无需改动」的项**（避免过度修改）：`FocusableSurface` 的 `onFocusChanged` 透传语义零变化、
全仓库 0 处 tv-material3 `Surface(`/`Card(` 嵌套、浅色容器调用点均已显式传 `contentColor`、
沉浸层文字全显式色、`CoverCarousel` 无手势、`UnifiedSongGrid` 是死代码、
`HomeScreen` 横屏那条确是 `NowPlayingCard`、**B1 等价性全部成立**。

**新沉淀的维护约定**：`docs/conventions-adaptive-ui.md` **§6.5**
「§8 那条自查 grep 的**盲区**：小尺寸 + `clickable`」——
① 扫描下界必须放到 0；② 尺寸为表达式时正则会**空转报 0 处**（假阴性），
必须取 `size(` 括号配对内容再抽 `.dp` 字面量。§8 自查命令已同步扩下界，§7 清单加一条。

**门禁复跑**：`testDebugUnitTest` **848 例 / 0 失败 / 0 错误**，`lintDebug` **0 Error / 267 Warning**。

> 📌 **后续建议**：
> ① ~~§6.5 那条自查目前仍靠人工跑脚本~~ → **已完成**：已固化为单测门禁
> `app/src/test/java/com/nasmusic/tv/ui/SmallTouchTargetScanTest.kt`
> （与 `ScreenUiModeCoverageTest` 同范式，跑 `testDebugUnitTest` 即生效），
> 与脚本正本 `audit_small_touch_target.py`（项目根，自带 `--selftest`）**逐字同规则**。
> 固化过程中发现并修掉了**两处空转**：脚本/门禁首版都只判「`.size(` **之后**的行」，
> 漏掉 `Modifier.size(8.dp).clickable { }` 这种**同行写法**（手势正则 `^\s*\.` 锚行首）；
> 补同行判定后**必须先排除整行注释**，否则 KDoc 里举例的写法会误报（已补误报防线用例）。
> ② review 顺带发现 `TextInputDialog.kt:132` 的 `isTVDevice` 也是**每次组合 2 次
> `hasSystemFeature`**（未加 `remember`），**刻意未改**（不在本轮改动范围，避免扩大改动面）；
> 全仓库仅此一处与 `FocusableSurface.isTVDevice()` 同类。

---

## 十一、风险与回滚

| 风险 | 影响 | 应对 |
|------|------|------|
| **误用 material3 组件（C1）** | 编译失败 | P0-7 明确用 `compose.foundation` + `tv-material3`；评审时 grep 确认无 `import androidx.compose.material3` |
| **MiniPlayer 顶层订阅进度 → F-2 回归** | 播放期间每秒重组 AppRoot 全树，滚动卡顿、D-Pad 焦点搜索变慢 | 进度/时长必须在 `MiniPlayer` 内部 `collectAsState`（§8.5 骨架已注明）；验收时用 Layout Inspector 或日志确认重组范围 |
| 改 `requestedOrientation` 影响平板 | 平板也放开竖屏 | P0 验收确认"可用不崩"；P2 的平板分档项（§9 P2-36）按 sw600 分档 |
| `configChanges` 掩盖依赖重建的逻辑 | 语言/主题/字体切换异常 | 显式验证语言切换、深浅色切换、字体缩放三项 |
| 旋转后权限弹窗重弹 | 打扰用户 | `configChanges` + 旋转后检查弹窗（§10.3） |
| 全屏页强制横屏与用户竖屏锁冲突 | 退出后方向未恢复 | `LaunchedEffect` key 含 `showMv/showKaraoke/showVisualizer`，自动恢复 |
| 竖屏 7 个歌词 Chip 溢出 | 播放页布局崩 | P0-13 收敛为 4 个（来源循环 + 字号循环 + 睡眠 + 高亮模式移入次级操作 Chip，D2） |
| 底部导航与 TV 顶部导航行为分叉 | 维护两套 | 抽 `TvTopNavBar` / `PhoneNavBar`，`AppRoot` 一处分支 |
| 网格列数竖屏落点不合预期 | 卡片过小 | 已核验：`adaptiveColumns` 落 3 列 ≈120dp/卡，可接受；需补的是 `RadioTab.kt:162`（Fixed(2)）与 `Shimmer` 骨架（v1.4 修正，不再包括 `MineScreen`/`WeatherRadio`） |
| 改缩放系数导致歌词异常 | 歌词字号错乱 | **本版决策：竖屏沿用 0.82，不动**（§2.5），风险出关键路径 |
| **L2 单击/长按语义混淆**（v1.3 D1，PM 2026-09-19 移除长按） | 用户困惑："我明明切过了怎么又回去了" | **去掉长按**：单击=竖/横循环+写 pref；图标状态显示 L1 真值；UI 文案明示"切换方向"（不再提"长按"） |
| ~~`AnimatedContent` 导致列表状态重置~~（**v1.5 关闭**） | — | 已拍板硬切（D10），不存在新旧子树，本条作废；§5.5(11) 已改为"硬切下状态通常保留 + 加固建议" |
| ~~`AnimatedContent` 过渡期间新旧子树并存~~（v1.4 B3，**v1.5 关闭**） | — | 已拍板硬切（D10），B3 整体消除；§5.5(10) 保留为"将来引入过渡"的备忘 |
| ~~`AnimatedContent` 切换期间用户连续点击~~（**v1.5 关闭**） | — | 硬切无动画窗口；若将来引入过渡再看 §8.6 备忘 |
| **硬切观感**（v1.5 D10 新增） | 横竖屏切换瞬间可能有硬切观感（原 D7 想解决的问题） | **PM 已接受**；若后续收到体验反馈，再按 §5.5(10) 的配套方案评估缓动 |
| **首帧方向闪烁**（v1.3 D5） | 冷启动首屏可见 1 帧 manifest 默认值 | `onCreate` 同步调 `requestedOrientation = resolveOrientation(...)`，**不走** `Flow.collectAsState(initial)` 路径 |
| **刘海屏顶部栏被遮挡**（v1.3 D4） | Logo/应用名视觉错位 | `PhoneTopBar` 用 `statusBarsPadding() + displayCutoutPadding()`；**前提是 D9（竖屏显示系统栏）**，否则 padding 恒为 0（§5.5(9)）；P0-19/20 必做 |
| **触摸热区过小误触**（v1.3 D3） | 5 项底部导航误触率高 | Icon 24dp + height 56dp（**不加** `padding(vertical)`）→ 物理 ≈ 45.9dp ≥ 44dp；其余控件按 §2.7 换算，**不能用 Compose 44dp 当热区下限** |
| **横屏手机被改成新 chrome**（v1.4 B1） | 违反 §10.3 用例 3"横屏零变化"，且丢失 `MineScreen` 横屏单列等现状体验 | 分支谓词硬规则：**只认 `UiMode.PhonePortrait`**；`else` 一律走现状代码路径（§3.1 / §8.6）；验收用例 23 逐页对照 |
| **inset 实际未生效**（v1.4 B2） | 刘海遮挡 + 系统栏唤出内容跳动 | 落地 D9（竖屏 `show(systemBars())`）；否则改用 `displayCutout + safeDrawingPadding`（§5.5(9)） |
| **首帧同步读 ANR**（v1.4 B4） | 冷启动主线程 DataStore 首读阻塞 | `getScreenOrientationSync()` 走镜像/`@Volatile`（R-7 范式），**不用 `runBlocking`**（§8.2） |
| **dp 口径错导致热区/容量误判**（v1.4 B5） | 以为达标实际物理只有 0.82 倍 | 全部口径按 §2.7 统一，真机验收用例 24 |
| **顶部行组件被推出屏幕且滑不到**（v1.4 新增） | `SearchTab` 来源 Chip / `RadioTab` preset tag / 详情页操作按钮不可达 | 改 `FlowRow` / `horizontalScroll` / 拆两行；P0-23/24，验收用例 22 |

**回滚策略**：无需 feature flag。设置项默认 `auto` 即"跟随传感器"，用户在设置里选「横屏」即回到改前行为；代码层面回滚 = 恢复 `MainActivity.kt:170-179` 一处 + 撤销 manifest `configChanges`。

---

## 十二、文档同步（按项目约定，实施时必做）

1. `CHANGELOG.md`：**新版本节插到文件最前**（倒序），且**摘要块与「未实施部分」必须与 Added/Changed 一致** —— 这是最容易漏的一处，摘要会被 CI 抽进 release notes
2. `docs/technical-overview.md`：新增 `§10.N`（N 递增，取当前最大值 +1），含来源/根因/修复/测试/验证/版本
3. 版本号唯一来源 = `app/build.gradle.kts`（当前 `versionName = "2.35.0"` / `versionCode = 153`），同步 `CHANGELOG.md`、§10.N 标题与「版本」行
4. 若产生审查报告，`docs/code-review-YYYY-MM-DD.{md,html}` **两份都要改**

---

## 附：核验清单（可复现）

> v1.3 追加：D1-D7 决策项 + 「滚动可见 = 合理」原则已落到对应章节，**新增 8 条 L2 行为 / AnimatedContent / 触摸目标相关的纯函数单测**（§10.1 补）。
> v1.4 追加：本节所有行**重新用 `:app:dependencies --configuration debugCompileClasspath` 与全仓库 grep 复核过一遍**，新增 14 行（标 **v1.4**）。

| 断言 | 核验方式 | 结果 |
|------|---------|------|
| material3 不在编译类路径 | `:app:dependencies --configuration debugCompileClasspath \| grep material3` | 无输出 ✅ |
| 全仓库无 material3 使用 | `grep -rn "import androidx.compose.material3" \| wc -l` | 0 ✅ |
| 顶部导航 6 项 | `AppRoot.kt:225-254` 数 `NavItem(` 调用 | 6 ✅ |
| `adaptiveColumns` 存在与阈值 | `BrowseComponents.kt:77-84` | `>=1000` / `>=600` / else ✅ |
| `songGridColumns()` 恒 1 列 | `CommonComponents.kt:114` | `GridCells.Fixed(1)` ✅ |
| `SettingsSection` 9 分区且 private | `SettingsScreen.kt:101-111` | 确认 ✅ |
| `LibraryTab` 8 项顺序 | `LibraryScreen.kt:68-77` | SEARCH 开头 ✅ |
| `SongRowMode` 三值 | `UnifiedSongRow.kt:61-68` | ROW / CARD / COMPACT ✅ |
| 进度条已支持触摸 | `PlayerControls.kt:163-193` | tap + drag ✅ |
| 热力图已自适应窄屏 | `PlayHeatmapChart.kt:77-89` | `coerceIn(3.dp,16.dp)` ✅ |
| Manifest 无 configChanges | `AndroidManifest.xml:82-89` | 确认 ✅ |
| zh/en 字符串各 898 条 | `grep -c "<string"` | 898 / 898 ✅ |
| **K1** 禁止顶层订阅进度 | `AppRoot.kt:115-117` F-2 注释；`NowPlayingBranch.kt:78-81` 为正确范式 | 确认 ✅ |
| **K2** BACK 状态归 navVM | `AppRoot.kt:153-184` handler 由 `navVM.currentScreen` 驱动 | 确认 ✅ |
| 方案引用的 `nav_*` key 真实存在 | `grep '"nav_home"\|"nav_library"\|"nav_mine"\|"nav_settings"\|"nav_now_playing"' values/strings.xml` | 全部命中（:21-28）✅ |
| 方案引用的 `settings_*` 分区 key 存在 | 同上查 `settings_general` 等 8 个 | 全部命中 ✅ |
| `Icons.Filled.Settings` 可用 | 由 `material-icons-extended`（编译类路径已确认）提供 | 可用 ✅ |
| `navigationBarsPadding()` 可用 | `androidx.compose.foundation:foundation-layout`（编译类路径已确认） | 可用 ✅ |
| `PlayerViewModel` API 满足 MiniPlayer | `PlayerViewModel.kt:47-49`（isPlaying/progress/duration）、`:194` playPause、`:265` next | 确认 ✅ |
| 子 pref 为薄委托范式 | `LanguagePrefs.kt` 全文 13 行，键与逻辑在 `AppPreferences.kt` | 确认 ✅ |
| **v1.4** material3 实测不在编译类路径 | `:app:dependencies --configuration debugCompileClasspath` → grep `material3` | **0 命中** ✅（C1 成立） |
| **v1.4** `androidx.compose.animation` 在编译类路径 | 同上 grep `animation` | `animation:1.6.1` 命中 ✅（BOM 2024.02.00 → compose 1.6.1，D7 的 `AnimatedContent`/`togetherWith` 可用） |
| **v1.4** 首页卡片宽 | `HomeScreen.kt:548,609` | `width(160.dp)`（原文写 180dp ❌） |
| **v1.4** `WeatherRadioScreen` 无网格 | 全文 grep `GridCells` | 0 命中 ✅（原文"网格"❌） |
| **v1.4** `MineScreen` 无网格 | 全文 grep `Grid` | 0 命中 ✅（原文"歌单卡片列数"❌） |
| **v1.4** `NetdiskScreen:119` / `RadioTab:86` 是搜索框 | 读调用点上下文 | 均为 `SearchField(modifier = Modifier.width(420.dp / 340.dp))` ✅（原文"侧栏"❌） |
| **v1.4** `JamendoTab` 是死代码 | 全仓库 grep `JamendoTab` | 仅定义、零引用 ✅ |
| **v1.4** `EqualizerScreen` 已是竖向列表 | `EqualizerScreen.kt:104-218` | `LazyColumn` + 频段行 ✅（原文"横排滑块"❌） |
| **v1.4** 语言切换非 `recreate()` | `SettingsBranch.kt` 的 `onChangeLanguage` | `startActivity(CLEAR_TASK\|NEW_TASK) + finish()` ✅ |
| **v1.4** `getLanguageSync` 无 `runBlocking` | `AppPreferences.kt:308` + `:967` 注释 | 镜像 + `@Volatile` ✅（原文"参照 runBlocking 做法"❌） |
| **v1.4** 单槽 handler 写法确认 | 5 处页面的 `DisposableEffect` | 均为 `onDispose { listBackHandler.value = null }` ✅（B3 风险成立） |
| **v1.4** 顶部行无横向滚动 | `SearchTab.kt:194-240`、`RadioTab.kt:74-110` | 均为无 `horizontalScroll` 的 `Row` ✅（不可滑到，P0） |
| **v1.4** `SettingsScreen` `activeSection` 行号 | grep `var activeSection` | `:257`（原文 264 ❌） |
| **v1.4** 手机端系统栏隐藏 | `MainActivity.kt:117-127` | `hide(systemBars())` ✅（B2 前提成立） |

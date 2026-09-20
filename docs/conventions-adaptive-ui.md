# 自适应 UI 维护约定（TV / 手机竖屏 / 手机横屏）

> 来源：`docs/archive/phone-portrait-ui-plan.md` §3（架构）、§2.7（尺寸口径）、§9 P1-32。
> 本文件是**新增页面/组件时必须遵守的清单**，也是 code review 的检查项。
> 版本基线：v2.36.0。

---

## 1. 形态因子（Form Factor）

唯一判定入口：`ui/theme/UiMode.kt`

```kotlin
enum class UiMode { TV, PhonePortrait, PhoneLandscape }
val LocalUiMode = staticCompositionLocalOf { UiMode.TV }

fun deriveUiMode(isTV: Boolean, orientation: Int): UiMode
```

- 在 `MainActivity.setContent` 中由 `LocalConfiguration.current` + 设备类型推导，
  通过 `CompositionLocalProvider(LocalUiMode provides uiMode)` 下发。
- ⚠️ **判定不能写成 `remember { derivedStateOf { configuration.orientation } }`**
  —— `configuration` 不是 `State`，会永久读到初值（方案 C4）。直接读 `LocalConfiguration.current`。

---

## 2. ⛔ B1 硬规则：分支谓词只写 `PhonePortrait`

```kotlin
// ✅ 正确
if (LocalUiMode.current == UiMode.PhonePortrait) { 竖屏版() } else { 原版() }

// ⛔ 禁止
if (LocalUiMode.current == UiMode.TV) { ... } else { 手机横屏专属逻辑 }
```

原因：**手机横屏与 TV 当前共用同一套布局**。`else` 分支必须逐字等价于改动前的代码，
这样"TV 端 UI 零变化 + 手机横屏与改前一致"才可证。

若某页面确实需要横屏独立分支，必须：
1. 显式写 `UiMode.PhoneLandscape` 判断；
2. 在代码注释里写明**为什么**不能与 TV 共用；
3. 在 `CHANGELOG.md` 与 `docs/technical-overview.md` 的对应 §10.N 里记录。

---

## 3. 列数：只用 `adaptiveColumns` / `adaptiveColumnsOf`

```kotlin
// ui/components/CommonComponents.kt

/** 纯函数，可 JVM 单测 */
fun adaptiveColumnsOf(widthDp: Int, tv: Int, phonePortrait: Int, medium: Int): Int

/** @Composable 版；竖屏直接取 phonePortrait，不再心算宽度 */
@Composable
fun adaptiveColumns(tv: Int, phonePortrait: Int, medium: Int = phonePortrait): Int
```

阈值口径（沿用项目既有约定）：

| `screenWidthDp` | 落点 | 典型设备 |
|---|---|---|
| `>= 1000` | `tv` | Android TV / 大屏 |
| `600 .. 999` | `medium` | 手机横屏 / 小平板 |
| `< 600` | `phonePortrait` | 手机竖屏 |

使用方式：

```kotlin
LazyVerticalGrid(columns = GridCells.Fixed(adaptiveColumns(tv = 6, phonePortrait = 3, medium = 6)))
```

⚠️ `adaptiveColumns` 返回的是 **Int（列数）**，不是 `GridCells`。必须自己包 `GridCells.Fixed(...)`。

⚠️ **不要另造列数 helper**。已有两套，够用：
- `adaptiveColumns`（多列网格，见上）
- `songGridColumns()`（`ui/components/CommonComponents.kt`，**恒 1 列**，歌曲条目专用）

⚠️ **双输入原则**（方案 §2.7 第 3 条）：`LocalConfiguration.screenWidthDp` 是**未缩放**的
Android dp（竖屏手机 ≈ 360），而竖屏布局宽度是 **Compose dp**（`PHONE_UI_SCALE = 0.82`
缩放后 ≈ 439）。只用宽度会在 600/1000 阈值附近错配 —— 所以 `adaptiveColumns` 先看
`LocalUiMode`，竖屏**直接**取 `phonePortrait` 档。

### 各页面现用列数（改动前先对照）

| 位置 | tv | phonePortrait | medium |
|---|---|---|---|
| `browse/AlbumGrid.kt` | 6 | 3 | 6 |
| `browse/ArtistList.kt` | 6 | 3 | 6 |
| `browse/GenreYearLists.kt:83` | 4 | 2 | 3 |
| `browse/GenreYearLists.kt:180` | 5 | 2 | 3 |
| `library/RadioTab.kt` | 3 | 1 | 2 |
| `components/Shimmer.kt`（骨架） | 6 | 3 | 6 |

---

## 4. 二分支包装：`AdaptiveLayout`

```kotlin
AdaptiveLayout(
    phonePortrait = { PortraitVersion() },
    tv = { ExistingVersion() },   // ← TV 与手机横屏共用
)
```

等价于 §2 的手写 `if/else`，用于**整个子树**的分叉；组件级小改动仍建议直接写 `if`。

---

## 5. 对话框 / 弹层：`responsiveDialogSize`

```kotlin
@Composable
fun responsiveDialogSize(
    landscapeWidth: Dp,
    scrollable: Boolean = false,
): Modifier
```

- **竖屏**：`fillMaxWidth(0.92f)` + `widthIn(max = 420.dp)` + `heightIn(max = 80% 屏高)`
- **非竖屏**：`Modifier.width(landscapeWidth)` —— 与原实现逐字等价

用法（放进 Modifier 链）：

```kotlin
Modifier
    .then(responsiveDialogSize(480.dp, scrollable = true))
    .clip(RoundedCornerShape(16.dp))
    …
```

⚠️ `scrollable = true` **只能用于内部没有 `LazyColumn` / `LazyVerticalGrid` 的普通 Column**，
否则触发 `Vertically scrollable component was measured with an infinity maximum height
constraints` 崩溃（嵌套同向滚动容器）。内部已有列表的对话框请传 `false`（默认），
并自行用 `heightIn(max = …)` 限制高度。

⚠️ 直接写死 `fillMaxWidth(0.92f)` 会把 TV 上的 480~720dp 对话框压到 420dp，属**回归**。
必须走本 helper（它内部按 `LocalUiMode` 分叉）。

⛔ **新增对话框后必做一次全量自查**（v2.36.0 踩过：设置页「删除备份」确认弹窗漏改，
仍写死 `.width(520.dp)`，在**所有**手机竖屏上都被窗口裁掉 —— 竖屏 Compose 口径只有
`物理宽 / 0.82`，360dp 屏 ≈439dp、411dp 屏 ≈501dp，**都小于 520**）：

```bash
# 凡出现在 `Dialog { }` 内容根节点上的三位数宽度，逐个确认是否已走 responsiveDialogSize
grep -rnE "\.width\([4-9][0-9]{2}\.dp\)" app/src/main/java/com/nasmusic/tv/ui
```

判定口径：**该宽度是否会成为对话框/弹层内容的根约束**。若它只是页面内某个普通 `Column`
且该 `Column` 只在 `else`（非竖屏）分支渲染，则不必改（如
`NowPlayingScreen` 的 380dp 封面列、`QueueScreen` 的 340dp 侧卡、`TextInputDialog` 的 720dp 主栏
—— 都在非竖屏分支里）。

---

## 6. 尺寸与触摸目标口径（方案 §2.7）

`PHONE_UI_SCALE = 0.82` → **Compose dp × 0.82 = 物理 dp**。

| 口径 | 竖屏 1080×2400 @440dpi |
|------|----------------------|
| **物理 dp**（Material 48 / Apple HIG 44 说的都是它） | 宽 ≈360dp |
| **Compose dp**（`Modifier.size(x.dp)` 用的） | 宽 ≈439dp |

### 6.1 ⛔ 触摸目标：必须用 `portraitTouchTarget(...)`，禁止硬编码 44 / 48

物理 44dp 的触摸目标 ⇒ **Compose 侧 ≥ 53.7dp**（`44 / 0.82`）。

```kotlin
// ✅ 正确
Modifier.size(portraitTouchTarget(48.dp))     // 竖屏 → 56dp，TV/横屏 → 48dp
Modifier.height(portraitTouchTarget(44.dp))
Modifier.size(PHONE_TOUCH_TARGET)             // 竖屏专属组件，直接 56dp

// ❌ 错误（v2.36.0 之前遍布各处）
Modifier.size(48.dp)   // 竖屏只有 39.4 物理 dp
Modifier.height(44.dp) // 竖屏只有 36.1 物理 dp —— 注释里写「44dp+ 触摸目标」是口径误用
```

| Compose dp | 物理 dp | 判定 |
|-----------|--------|------|
| 44 | 36.08 | ❌ |
| 48 | 39.36 | ❌ |
| 52 | 42.64 | ❌ |
| **56** | **45.92** | ✅ ← `PHONE_TOUCH_TARGET` |

⚠️ **两个坑**：

1. **`padding` 会削热区**。`Modifier.height(52.dp).padding(vertical = 4.dp)` 传给
   `FocusableSurface` 时，`clickable` 加在 padding **之后** → 实际热区只有 44dp。
   竖屏下要么抬到 `portraitTouchTarget(52.dp)`，要么**同时把垂直 padding 归零**
   （见 `ExportDeviceDialog` 设备项）。
2. **容器即热区**。`PhoneNavBar` / `PhoneTopBar` / `MiniPlayer` 的做法是「子项
   `fillMaxSize()` / 56dp + 容器不加垂直 padding」，热区等于整块容器，不要再套内边距。

### 6.2 已被证明不达标的旧值

`UiModeTest` 里有回归护栏用例（`PHONE_TOUCH_TARGET 满足物理 44dp 而 44 与 48 不满足`），
断言 `44 × 0.82 = 36.08`、`48 × 0.82 = 39.36`、`52 × 0.82 = 42.64` 均 `< 44`。
**改这些常量会让该用例失败**，这是有意的。

### 6.3 豁免清单（**刻意不改**，不要"顺手修"）

| 位置 | 原因 |
|------|------|
| `KaraokePlaybackScreen` / `MvPlaybackScreen` / `VisualEqualizer` 内的 44 / 48dp | 三者都被 `MainActivity` 的 `isFullScreenPage` 强制 `SENSOR_LANDSCAPE` → **永远不在竖屏渲染** |
| `Shimmer.kt` 骨架屏、各页封面缩略图（40/48dp）、`HomeScreen` 的 40dp logo | **不是触摸目标**（装饰/占位），热区在父行 |
| `Spacer(Modifier.height(40.dp))` 之类 | 纯间距 |

### 6.4 其他

改任何"固定 dp 尺寸"前，先用上表换算核一遍物理尺寸。

⚠️ **不能靠改 density 解决竖屏**（方案 §2.5）：会同时放大 TV 端与所有既有固定尺寸。

### 6.5 ⚠️ §8 那条自查 grep 的**盲区**：小尺寸 + `clickable`

§8 的自查 grep 只覆盖 **40~53dp** 区间：

```
\.(size|height|width)\((\s*)(4[0-9]|5[0-3])\.dp\)
```

**小于 40dp 的写法完全逃过检查** —— 但 `Modifier.size(8.dp).clickable { }` 同样是
6.6 物理 dp 的热区，比 44/48 更糟。**真实案例（2026-09-19 review）**：
竖屏播放页的 `PortraitModeIndicator` 两个模式圆点写成
`.size(if (active) 8.dp else 6.dp) ... .clickable { onSwitch(m) }`，
热区仅 6~8 Compose dp（4.9~6.6 物理 dp），而 P0-26 的全量复核没抓到它。

**三个教训**：

1. **自查 grep 的下界必须放到 0**（`([0-3][0-9]|4[0-9]|5[0-3])`），且必须人工确认
   「这个尺寸是不是触摸目标」。装饰性小尺寸（图标、圆点、进度条）挂 `clickable` 一律可疑。
2. ⚠️ **尺寸是表达式时正则匹配不到**：`size(if (a) 8.dp else 6.dp)` 里没有
   `size(<数字>.dp)` 这样的字面形态。写扫描脚本要**取括号配对内容再抽其中的 `.dp` 字面量**，
   否则会**空转**（报"0 处"，看起来干净，实际什么都没查）。
3. ⚠️ **手势可能写在同一行**：`Modifier.size(8.dp).clickable { }`。用 `^\s*\.` 锚行首的
   手势正则**匹配不到行中的 `.clickable`** → 单行写法整类逃检（**首版脚本与首版门禁都在
   此空转**）。必须另设一条不锚行首的同行正则，做**同行 + 后续行双判定**。
   补同行判定后**必须同时排除整行注释**，否则 KDoc 里举例的
   `` `Box(Modifier.size(8.dp).clickable { })` `` 会被误报。

> 📌 **扫描脚本已入库**：项目根目录 `audit_small_touch_target.py`
> （与 `check_chinese.py` 同级），**必须先跑 `--selftest` 再实跑** ——
> 这就是本条的教训：**源码扫描型护栏不自证，就无法区分"真干净"和"空转"**。
> 判定规则：取 `size(` 括号配对内容 → 抽出全部 `.dp` 字面量 → 全部 < 40dp 才候选
> （无字面量则跳过；`portraitTouchTarget(44.dp)` 因含 44 而被豁免，属受认可写法）；
> 再判**同行**（不锚行首的 `CLICK_SAME_LINE`）或**后续同链**（`^\s*\.` 锚行首）是否有
> `clickable`，链上有 `fillMaxSize` / `weight(` 撑大的跳过；整行注释（`//` / `*` / `/*`）先排除；
> `Spacer(` / `Divider` 需**向前回看 3 行**排除（它们常写在链的开头）。
>
> 📌 **同范式的第二道门禁已固化**：`app/src/test/java/com/nasmusic/tv/ui/SmallTouchTargetScanTest.kt`
> （跑在 `testDebugUnitTest` 里，CI 已阻塞）。脚本只能靠人记得跑，而这条规则在项目里
> **已经漏过一次**，故与 `ScreenUiModeCoverageTest`（§9）同范式做成单测。
> 该门禁含**三层自证**：负向用例 ×2（表达式尺寸、单行字面量）+ 误报防线 ×1（注释里的举例）
> + 空转断言（真实扫描里断言扫到的 `.size(` 链数 > 0）。

**正确写法**：视觉元素与热区分离 —— 外层承担热区，内层只做视觉。

```kotlin
Box(
    modifier = Modifier
        .size(portraitTouchTarget(44.dp))   // 竖屏 56dp ≈ 45.9 物理 dp ✅
        .clickable { onSwitch(m) },
    contentAlignment = Alignment.Center,
) {
    Box(Modifier.size(if (active) 8.dp else 6.dp).clip(CircleShape).background(color))
}
```

---

## 7. 新增 Screen / 组件的检查清单

- [ ] 顶层容器宽度不再写死（`width(760.dp)` 之类）→ 改 `fillMaxWidth()` / `widthIn(max = …)`
- [ ] 页 padding 竖屏用 `16.dp`，非竖屏保持原值（当前基线是 `32.dp`）
- [ ] 一行放不下的「返回 + 标题 + N 个操作」→ 竖屏**拆两行**，操作行加 `horizontalScroll`
- [ ] 多列网格走 `adaptiveColumns(...)`，不写 `GridCells.Fixed(常量)`
- [ ] 对话框走 `responsiveDialogSize(...)`
- [ ] **触摸目标**走 `portraitTouchTarget(x.dp)` 或 `PHONE_TOUCH_TARGET`，**不写裸 `44.dp` / `48.dp`**（§6.1）
- [ ] ⚠️ **小视觉元素（圆点 / 图标 / 进度条）不要直接挂 `clickable`** ——
      改为「外层承担热区 + 内层只做视觉」（§6.5）。`size(8.dp).clickable{}` 这类写法
      由 `SmallTouchTargetScanTest` 门禁兜住（含单行写法），但**表达式尺寸**仍需人工确认
- [ ] 新弹层（`Box` 覆盖层）→ **必须** `RegisterDialogBackHandler(onDismiss)`（方案 §6.3）
- [ ] 页面级 BACK 状态提升到 `NavigationViewModel`（方案 K2），不要藏在页面内部
- [ ] ⚠️ **禁止在 `AppRoot` 顶层订阅 `progress` / `duration`**（方案 K1）：那是 1000ms
      `Handler` 轮询，会导致整棵树每秒重组。需要进度的组件（如 `MiniPlayer`）在**自己内部**订阅
- [ ] 新增 UI 文案**同步** `res/values/strings.xml` 与 `res/values-en/strings.xml`
- [ ] 纯逻辑（列数、方向决策）抽顶层 `internal fun` 并补 JVM 单测

---

## 8. 验证手段（可复现）

```bash
# 编译
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat :app:compileDebugKotlin --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process

# 单测（含 UiModeTest 的列数 / 方向 / dp 护栏 + §9 的 Screen 覆盖门禁）
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat testDebugUnitTest --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process

# lint（低 minSdk 的 API level 错误只有 lint 能抓；基线 0 Error）
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat lintDebug --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process

# 静态自查：是否还有硬编码大宽度
grep -rnE "\.width\([4-9][0-9]{2}\.dp\)" app/src/main/java/com/nasmusic/tv/ui

# 静态自查：是否误引 material3（不在编译类路径，方案 C1）
grep -rn "import androidx.compose.material3" app/src/main/java | wc -l   # 期望 0

# 静态自查：是否还有裸 44/48/52dp 触摸目标（§6.1；豁免项见 §6.3）
# ⚠️ 下界已从 4x 扩到 0x —— 原来的 40~53dp 区间会漏掉 size(8.dp).clickable 这类
#    更糟的写法（见 §6.5）。命中后逐个确认「是不是触摸目标」。
grep -rnE "\.(size|height|width)\((\s*)([0-3][0-9]|4[0-9]|5[0-3])\.dp\)" app/src/main/java/com/nasmusic/tv/ui \
  | grep -vE "Karaoke|MvPlayback|VisualEqualizer|Shimmer|Cover|cover|favicon|Spacer"
# ⚠️ 上面这条对「尺寸是表达式」的写法无能为力（size(if (a) 8.dp else 6.dp)）→
#    改用下面这个脚本（已入库，与 check_chinese.py 同级；自带负向自证）
python audit_small_touch_target.py --selftest   # 先自证脚本有效（7 用例）
python audit_small_touch_target.py              # 再实跑，期望 0 处（当前 347 文件 / 0 处）
# ⚠️ 手工兜底：任何小尺寸（图标 / 圆点 / 进度条）直接挂 clickable 都属违规，
#    应改为「外层 size(portraitTouchTarget(...)) 承担热区 + 内层只做视觉」（§6.5）
# 📌 同规则的单测门禁：SmallTouchTargetScanTest（跑 testDebugUnitTest 即生效，无需记得跑脚本）
```

**B1 回归护栏**：任何"给竖屏加分支"的改动，都要能回答
「`else` 分支是否与改动前逐字一致」。抽取公共子组件（如把原顶部导航抽成
`TvTopNavBar`）是推荐做法 —— 行为零变化，且 diff 可审。

---

## 9. 门禁：新增 Screen 必须有 UiMode 分支（P1-32）

**"忘了做竖屏"编译过、单测过，只有真机才看得见** —— 所以由自动门禁兜住。

### 门禁在哪

`app/src/test/java/com/nasmusic/tv/ui/ScreenUiModeCoverageTest.kt`
（跑在 `testDebugUnitTest` 里，CI 的 `test` job 已阻塞）

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat testDebugUnitTest --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
```

### 触发条件

文件同时满足：① 有**顶层** `fun XxxScreen(`；② 含 `@Composable`；
③ **没有**出现任一 marker（`LocalUiMode` / `UiMode.` / `AdaptiveLayout(` /
`adaptiveColumns(` / `adaptiveColumnsOf(` / `responsiveDialogSize(` /
`portraitTouchTarget(` / `PHONE_TOUCH_TARGET`）→ 判失败。

### 豁免

```kotlin
// NasScreenUiMode-exempt: 全屏页被 isFullScreenPage 强制横屏，永远不在竖屏渲染

package com.nasmusic.tv.ui.components
```

当前豁免：`MvPlaybackScreen.kt`、`KaraokePlaybackScreen.kt`
（都被 `isFullScreenPage` 强制横屏，**永远不在竖屏渲染**）。

### ⚠️ 为什么不是自定义 lint 规则（方案原文建议 `tools/lint/`）

实测在**当前工具链（AGP 9.2.1 + `com.android.tools.lint` 32.2.1）** 下不可行：

- 自定义 check jar 的类由 `com.intellij.util.lang.UrlClassLoader` 加载，
  而 `SourceCodeScanner` 由 `java.net.URLClassLoader` 加载 → 两个类加载器各持一份 `lint-api`
- 结果：`PortraitScreenUiModeDetector cannot be cast to SourceCodeScanner`，
  `:app:lintAnalyzeDebug` 直接失败
- **不是配置错误**：`lintChecks` 依赖树只有 `project :tools:lint`；check jar 里也只有自己的类 +
  `META-INF/services/...IssueRegistry`，**没有**打包 lint-api

单测门禁的判定逻辑与当初设计的 lint 规则**逐条一致**（同一套 marker、同一个豁免标记），
且零新增依赖、零类加载风险。将来若工具链修好，可原样搬回 `tools/lint/`。

### ⚠️ 维护注意

- 判定是**启发式**：只要文件里出现过 marker 就放行，不校验是否真被调用。
  这是有意的取舍 —— 它是"别忘写"的护栏，不是版式正确性证明
- 护栏自身的有效性由**负向用例**守住（无 marker 必判违规、各 marker 必被识别、
  豁免标记必被识别、非 Screen / 嵌套函数 / 非 `@Composable` 不参与判定）。
  改判定逻辑时若把负向用例一起改"绿"了，护栏就废了 —— 请谨慎
- 源码目录定位失败时测试**直接失败**（不会静默跳过），
  `user.dir` 已覆盖「模块目录 / 仓库根 / 上一级」三种情形

---

## 10. ⛔ 内容色契约：`FocusableSurface` 必须同时下发两个 CompositionLocal

**这是真机反馈「下方主按钮 / 标题行按钮看不清」的根因，也是本项目最容易复发的 UI 缺陷之一。**

### 根因

`androidx.tv.material3.LocalContentColor` 的**默认值是 `Color.Black`**：

```kotlin
// androidx/tv/material3/ContentColor.kt
val LocalContentColor = compositionLocalOf { Color.Black }
```

而 tv-material3 的 `Icon` / `Text` **都会回退到它**：

```kotlin
// Icon.kt:67
tint: Color = LocalContentColor.current
// Text.kt:110-113
color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
```

`FocusableSurface` 早期**只**提供自定义的 `LocalFocusableContentColor`，于是它内部凡是
**没显式写 `tint =` / `color =`** 的 `Icon` / `Text` 全部画成**黑色** ——
压在深色底（`Surface #162032` / `SurfaceVariant #1E2D42`）上就是"看不见"。

### 约定

```kotlin
CompositionLocalProvider(
    LocalFocusableContentColor provides targetContentColor,   // 项目自有：给显式读它的组件
    LocalContentColor provides targetContentColor,            // tv-material3 官方：给 Icon/Text 兜底
) { content() }
```

**任何自建容器组件**（`FocusableSurface` 之外新写的 Surface/Card/Button 封装）只要内部可能放
裸 `Icon` / `Text`，就**必须**同时下发这两个 —— 少一个就会出现黑字压深底。

### 检查手段

```bash
# ① 门禁（含 4 组负向自证，防 import 假通过）
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat testDebugUnitTest --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process --tests "*FocusableSurfaceColorContractTest"

# ② 人工复核：确认 FocusableSurface 内容体外层两个 provides 都在
grep -n "LocalContentColor provides\|LocalFocusableContentColor provides" \
  app/src/main/java/com/nasmusic/tv/ui/components/FocusableSurface.kt
```

---

## 11. 焦点视觉：TV / 触摸必须分流（粘滞焦点态）

`Modifier.clickable` / `focusable()` 的节点在**手机**上点一下就会获得焦点，而且**焦点会粘住**
（直到点别处才移走）。若焦点相关的视觉直接用 `isFocused`：

- 缩放：点过一次**永久放大 8%**（P2-34 已修）
- 容器色 / 内容色 / 边框：点过一次**永久高亮**（本轮修）—— 表现为底栏、顶栏图标被点过就"选不回来"

### 约定

```kotlin
val tvDevice = isTVDevice()                    // FocusableSurface.kt 的公共函数
val activeFocus = isFocused && tvDevice        // ⛔ 焦点视觉一律走这个
```

- 缩放动画、边框、`focusedContainerColor`、`focusedContentColor` **全部**改用 `activeFocus`
- 手机只保留**按下**（`isPressed`）的瞬时反馈
- `isTVDevice()` 是公共 `@Composable` 函数，自实现焦点动画的组件（如 `UnifiedSongRow` 的
  `RowActionButton`）**直接复用**，不要再写一份 `packageManager.hasSystemFeature(...)`

### ⚠️ `onFocusChanged` 里别读外层派生的 `activeFocus`

`onFocusChanged { }` 的 lambda **捕获的是本次组合的值**，焦点刚变化时读到的还是旧值。
需要立即生效的动画（如 `RowActionButton` 的 1.15 倍缩放）必须用状态本身：

```kotlin
.onFocusChanged { state ->
    isFocused = state.hasFocus && tvDevice          // ✅
    scope.launch { animScale.animateTo(if (state.hasFocus && tvDevice) 1.15f else 1f, tween(150)) }
}
```

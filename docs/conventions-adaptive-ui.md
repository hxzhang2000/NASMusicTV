# 自适应 UI 维护约定（TV / 手机竖屏 / 手机横屏）

> 来源：`docs/phone-portrait-ui-plan.md` §3（架构）、§2.7（尺寸口径）、§9 P1-32。
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

---

## 6. 尺寸与触摸目标口径（方案 §2.7）

`PHONE_UI_SCALE = 0.82` → **Compose dp × 0.82 = 物理 dp**。

- 物理 44dp 的触摸目标 ⇒ **Compose 侧 ≥ 53.7dp**（`44 / 0.82`）
- 项目常用的 Compose `56.dp` ≈ 物理 `45.9dp` ✅
- 项目常用的 Compose `48.dp` ≈ 物理 `39.4dp` ⚠️ 偏小，仅用于图标按钮且周围有留白时

改任何"固定 dp 尺寸"前，先用上面的换算核一遍物理尺寸。相关换算已被
`UiModeTest` 的 dp 护栏用例守住（`56 × 0.82 ≈ 45.92 ≥ 44`、`44 / 0.82 ≈ 53.66`）。

⚠️ **不能靠改 density 解决竖屏**（方案 §2.5）：会同时放大 TV 端与所有既有固定尺寸。

---

## 7. 新增 Screen / 组件的检查清单

- [ ] 顶层容器宽度不再写死（`width(760.dp)` 之类）→ 改 `fillMaxWidth()` / `widthIn(max = …)`
- [ ] 页 padding 竖屏用 `16.dp`，非竖屏保持原值（当前基线是 `32.dp`）
- [ ] 一行放不下的「返回 + 标题 + N 个操作」→ 竖屏**拆两行**，操作行加 `horizontalScroll`
- [ ] 多列网格走 `adaptiveColumns(...)`，不写 `GridCells.Fixed(常量)`
- [ ] 对话框走 `responsiveDialogSize(...)`
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

# 单测（含 UiModeTest 的列数 / 方向 / dp 护栏）
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
```

**B1 回归护栏**：任何"给竖屏加分支"的改动，都要能回答
「`else` 分支是否与改动前逐字一致」。抽取公共子组件（如把原顶部导航抽成
`TvTopNavBar`）是推荐做法 —— 行为零变化，且 diff 可审。

package com.nasmusic.tv.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.UiMode

/**
 * 公共返回按钮组件
 * 带焦点动画的返回按钮，供各详情屏幕复用
 */
@Composable
fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.08f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.common_back_arrow), color = NasMusicColors.TextPrimary, fontSize = FontSize.button(), modifier = Modifier.padding(end = 6.dp))
            Text(text = stringResource(R.string.common_back), color = NasMusicColors.TextPrimary, fontSize = FontSize.button())
        }
    }
}

/**
 * 公共搜索框组件（统一样式：胶囊形、无独立搜索按钮）
 *
 * 点击整个搜索框触发 [onOpenSearch]（调用方负责弹出输入对话框）；
 * 已有搜索词时框内显示 ✕ 清除按钮，点击触发 [onClear]。
 * 宽度由调用方通过 modifier 指定，高度统一 48dp（**竖屏抬到 [PHONE_TOUCH_TARGET]**，见 §2.7）。
 */
@Composable
fun SearchField(
    query: String,
    placeholder: String,
    onOpenSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableSurface(
        onClick = onOpenSearch,
        modifier = modifier.height(portraitTouchTarget(48.dp)),
        shape = RoundedCornerShape(24.dp),
        containerColor = NasMusicColors.Surface,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = NasMusicColors.TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (query.isBlank()) placeholder else query,
                color = if (query.isBlank()) NasMusicColors.TextSecondary else NasMusicColors.TextPrimary,
                fontSize = FontSize.body(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (query.isNotBlank()) {
                FocusableSurface(
                    onClick = onClear,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("✕", color = NasMusicColors.TextPrimary, fontSize = FontSize.body(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

/**
 * 歌曲列表列数：TV 两列、手机单列（一行一个歌曲条目）
 *
 * 始终返回 1 列：歌曲条目信息较多（标题+艺术家+专辑+时长+操作按钮），
 * 两列布局下歌名被压缩不可读。统一用一列让 SongRow 占满整行。
 *
 * 注意："我的"页面不使用此函数，其 TV/手机版式差异保持不变。
 */
@Composable
fun songGridColumns(): GridCells = GridCells.Fixed(1)

/**
 * 响应式网格列数 —— **纯函数**（可 JVM 单测，不依赖 Compose 运行时）。
 *
 * 阈值口径沿用项目既有约定：
 * - `>= 1000dp`：TV / 大屏 → [tv]
 * - `600..999dp`：手机横屏 / 小平板（`medium`）→ [medium]
 * - `< 600dp`：手机竖屏 → [phonePortrait]
 *
 * ⚠️ 参数语义：第三参原名为 `phoneLandscape`，但它由 `widthDp >= 600` 触发，
 * **平板竖屏（sw >= 600）也会落进这一支**，故改名为 `medium`（方案 §3.4）。
 */
fun adaptiveColumnsOf(widthDp: Int, tv: Int, phonePortrait: Int, medium: Int): Int = when {
    widthDp >= 1000 -> tv
    widthDp >= 600 -> medium
    else -> phonePortrait
}

/**
 * 响应式网格列数（v2.36.0 由 `ui/screens/library/browse/BrowseComponents.kt` 上移，
 * 供全项目复用；与 [songGridColumns] 并列）。
 *
 * ⚠️ 双输入（方案 §2.7 第 3 条）：`LocalConfiguration.screenWidthDp` 是**未缩放**的
 * Android dp（竖屏手机 ≈ 360），而竖屏布局宽度是 **Compose dp**（`PHONE_UI_SCALE = 0.82`
 * 缩放后 ≈ 439）—— 只用宽度会在 600/1000 阈值附近出现"列数按横屏算、宽度按竖屏算"的错配。
 * 因此这里先看 [LocalUiMode]：竖屏手机**直接**取 [phonePortrait]，不再心算宽度。
 */
@Composable
fun adaptiveColumns(tv: Int, phonePortrait: Int, medium: Int = phonePortrait): Int {
    if (LocalUiMode.current == UiMode.PhonePortrait) return phonePortrait
    val widthDp = LocalConfiguration.current.screenWidthDp
    return adaptiveColumnsOf(widthDp, tv, phonePortrait, medium)
}

/**
 * 竖屏触摸目标下限（**Compose dp 口径**，方案 §2.7 第 1 条 / P0-26）。
 *
 * 物理 44dp（Android 无障碍下限）÷ `CompactSizes.PHONE_UI_SCALE`(0.82) ≈ **53.66**，
 * 取整到 **56**：`56 × 0.82 ≈ 45.9 物理 dp ≥ 44` ✅。
 *
 * ⚠️ 竖屏下 `LocalDensity` 被 0.82 缩放，**44 / 48 Compose dp 分别只有 36.1 / 39.4 物理 dp**，
 * 都不达标 —— 代码里不能再拿 44dp 当热区下限。新增竖屏控件请用 [portraitTouchTarget]。
 *
 * 保留为 `Float` 常量是为了让 [com.nasmusic.tv.ui.theme.UiModeTest] 能在纯 JVM 下断言该算术。
 */
const val PHONE_TOUCH_TARGET_DP: Float = 56f

/** [PHONE_TOUCH_TARGET_DP] 的 `Dp` 形式 */
val PHONE_TOUCH_TARGET: Dp = PHONE_TOUCH_TARGET_DP.dp

/**
 * 触摸目标尺寸（v2.36.0 竖屏，方案 §2.7 / P0-26）。
 *
 * - **竖屏**：返回 [PHONE_TOUCH_TARGET]（56 Compose dp ≈ 45.9 物理 dp ≥ 44）✅
 * - **TV / 手机横屏**：原样返回 [landscape] —— 与改动前**逐字等价**（B1）
 *
 * 用法：`Modifier.size(portraitTouchTarget(48.dp))` / `Modifier.height(portraitTouchTarget(44.dp))`
 *
 * ⚠️ 若 [landscape] 已经 ≥ 56dp（如底部导航的 56dp 容器），无需再套本函数。
 */
@Composable
fun portraitTouchTarget(landscape: Dp): Dp =
    if (LocalUiMode.current == UiMode.PhonePortrait) PHONE_TOUCH_TARGET else landscape

/**
 * 自适应布局包装器（v2.36.0 竖屏，方案 §3.4 / P1-32）。
 *
 * ```kotlin
 * AdaptiveLayout(
 *     phonePortrait = { PortraitThing() },
 *     tv = { ExistingThing() },   // ← TV 与「手机横屏」共用这一支
 * )
 * ```
 *
 * ⚠️ **B1 硬规则**：`tv` 分支同时承载 **TV 与手机横屏** —— 两者当前共用同一套布局，
 * 所以**不要**写成三分支（`PhoneLandscape` 单独一支），否则会与改动前的横屏行为不一致。
 * 若某个页面确实需要横屏独立分支，请显式读 [LocalUiMode] 并写明理由。
 */
@Composable
fun AdaptiveLayout(
    phonePortrait: @Composable () -> Unit,
    tv: @Composable () -> Unit,
) {
    if (LocalUiMode.current == UiMode.PhonePortrait) phonePortrait() else tv()
}

/**
 * 对话框 / 弹层的响应式尺寸（v2.36.0 竖屏，方案 §2.4 对话框族 / P1-27）。
 *
 * - **竖屏**：撑满 92% 宽 + 上限 420dp + 高度上限 80% 屏高（可选垂直滚动）
 * - **非竖屏（TV / 手机横屏）**：原样返回 `width(landscapeWidth)` —— 与改动前**逐字等价**（B1）
 *
 * 方案原文要求"13 处统一"，但直接写死 `fillMaxWidth(0.92f)` 会把 TV 上的 480~720dp
 * 对话框压到 420dp，属回归；故这里按 [LocalUiMode] 分叉。
 *
 * ⚠️ `scrollable = true` **只能用于内部没有 LazyColumn / LazyVerticalGrid 的普通 Column**，
 * 否则会触发 `Vertically scrollable component was measured with an infinity maximum height
 * constraints` 崩溃（嵌套同向滚动容器）。
 */
@Composable
fun responsiveDialogSize(
    landscapeWidth: Dp,
    scrollable: Boolean = false,
): Modifier {
    if (LocalUiMode.current != UiMode.PhonePortrait) return Modifier.width(landscapeWidth)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    return Modifier
        .fillMaxWidth(0.92f)
        .widthIn(max = 420.dp)
        .heightIn(max = maxHeight)
        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
}

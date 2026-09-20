package com.nasmusic.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.ScreenOrientationPref

/**
 * 竖屏顶部栏（v2.36.0，方案 §4.0 / §8.6）。
 *
 * 左：Logo + 应用名；右：搜索、**齿轮（设置入口）**、**L2 方向切换图标**
 * （单击在竖/横间循环 + 立即写 pref，D1，**无长按**，D8）。
 *
 * ⚠️ **齿轮为什么在这里**（v2.36.1）：底部导航 [PhoneNavBar] 已移除「设置」项
 * （5 项时每项更宽，且底部 5 个主功能不再被设置挤占），设置入口上移到顶栏右上角、
 * 紧贴横竖屏切换按钮的**左侧**。⛔ 不要再往底部导航加回设置，否则出现两个入口。
 *
 * inset：`statusBarsPadding()` + `displayCutoutPadding()` ——
 * ⚠️ 两者生效的**前提是 D9（竖屏不隐藏系统栏）**，否则 inset 恒为 0（方案 §5.5(9) / B2）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PhoneTopBar(
    orientationPref: String,
    onToggleOrientation: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(NasMusicColors.Surface)
            .statusBarsPadding()
            .displayCutoutPadding()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(NasMusicColors.Primary, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "\u266A", color = NasMusicColors.TextPrimary, fontSize = FontSize.button())
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "NAS Music",
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.button(),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 搜索：与现状 HomeBranch / NowPlayingBranch 的「搜索」按钮行为一致
            PhoneTopBarIconButton(
                contentDescription = stringResource(R.string.common_search),
                onClick = onNavigateToSearch,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            // 设置（齿轮）：v2.36.1 从底部导航上移到这里，位于方向切换按钮**左侧**
            PhoneTopBarIconButton(
                contentDescription = stringResource(R.string.nav_settings_cd),
                onClick = onNavigateToSettings,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            // L2 方向切换：单击 = 竖屏 ⟷ 横屏 二态循环 + 立即写 pref（"自动" 只能从设置项进入）
            OrientationToggleButton(
                orientationPref = orientationPref,
                onToggle = onToggleOrientation,
            )
        }
    }
}

/** L2 图标：显示 L1 真值（自动 / 竖 / 横），让用户一眼看出当前策略 */
private fun orientationIcon(pref: String): String = when (pref) {
    ScreenOrientationPref.PORTRAIT -> "\u25AF"      // ▯ 竖屏
    ScreenOrientationPref.LANDSCAPE -> "\u25AD"     // ▭ 横屏
    else -> "\u21BB"                                 // ↻ 自动
}

/**
 * L2 方向切换按钮（**竖屏顶部栏与手机横屏顶部栏共用**）。
 *
 * 单击在「竖屏 ⟷ 横屏」二态间循环 + 立即写 pref（"自动" 只能从设置项进入，D1 / D8）。
 * 图标显示 L1 真值，让用户一眼看出当前策略。
 *
 * ⚠️ **为什么手机横屏也需要它**：横屏走 `TvTopNavBar`（TV 布局），`PhoneTopBar` 不再渲染
 * → 若不补一个，用户从竖屏点进横屏后就**再也切不回竖屏**（只能靠系统旋转 + 关掉旋转锁）。
 * 故抽出本组件供 `PhoneTopBar` 与 `TvTopNavBar` 两处复用，保证图标、行为、热区**完全一致**。
 *
 * 触摸目标：[PHONE_TOUCH_TARGET]（56 Compose dp）。⚠️ 横屏下 `LocalDensity` **同样**被
 * ×0.82 缩放（该处判据是 `isTVDevice` 而非 `uiMode`），故 56 × 0.82 ≈ 45.9 物理 dp ≥ 44 ✅；
 * 若写成 48dp 则只有 ≈39.4 物理 dp，不达标（同 [PhoneTopBarIconButton] 的教训）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OrientationToggleButton(
    orientationPref: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PhoneTopBarIconButton(
        contentDescription = stringResource(R.string.nav_phone_orientation_cd),
        onClick = onToggle,
        modifier = modifier,
    ) {
        Text(
            text = orientationIcon(orientationPref),
            fontSize = FontSize.button(),
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 顶部栏图标按钮：**[PHONE_TOUCH_TARGET]（56 Compose dp ≈ 45.9 物理 dp）** 触摸目标。
 *
 * ⚠️ 早期写成 48dp，按方案 §2.7 口径只有 48 × 0.82 ≈ **39.4 物理 dp < 44** ❌ ——
 * 「配外层 56dp 容器后视觉热区充足」这种豁免**不被 §2.7 承认**（热区按按钮自身算）。
 * 现取 56dp，恰好填满 56dp 顶栏高度（与 [PhoneNavBar] 同款处理：容器即热区，不加垂直 padding）。
 *
 * ⚠️ 项目无 `androidx.compose.material3`（方案 C1），不用 `IconButton`；
 * 用 [FocusableSurface] + `semantics` 提供无障碍描述。
 *
 * ⚠️ `modifier` 只应用于**布局定位**（如 `padding`）—— 尺寸由本函数内的
 * `.size(PHONE_TOUCH_TARGET)` 固定，外部传入的尺寸会被覆盖（热区不可协商）。
 */
@Composable
private fun PhoneTopBarIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier
            .size(PHONE_TOUCH_TARGET)
            .semantics { this.contentDescription = contentDescription },
        shape = RoundedCornerShape(8.dp),
        containerColor = Color.Transparent,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        // P2-34：手机触摸没有焦点边框，按下高亮是唯一的"已响应"视觉反馈
        pressedContainerColor = NasMusicColors.Primary.copy(alpha = 0.35f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary,
        focusedScale = 1.08f,
        animationDurationMs = 150,
        pressedScale = 0.94f,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

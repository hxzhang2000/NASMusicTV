package com.nasmusic.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Screen
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 竖屏底部导航项（v2.36.1 起 **5 项**）。
 *
 * 沿革：v2.36.0 为 6 项（补了「队列」直达入口）；v2.36.1 起**移除「设置」**——
 * 设置入口改到顶栏右上角的**齿轮按钮**（在横竖屏切换按钮左边，见 [PhoneTopBar]），
 * 底部导航因此每项更宽。⛔ 不要再把设置加回来：会出现两个入口。
 */
private data class PhoneNavItem(val screen: Screen, val labelRes: Int, val icon: ImageVector)

private val PHONE_NAV_ITEMS = listOf(
    PhoneNavItem(Screen.Home, R.string.nav_home, Icons.Default.Home),
    PhoneNavItem(Screen.Library, R.string.nav_library, Icons.Default.LibraryMusic),
    // ⚠️ 用短标签：`maxLines = 1`，英文 "Now Playing"(11 字符) 会被裁掉。
    // TV 顶部导航仍用 nav_now_playing。
    PhoneNavItem(Screen.NowPlaying, R.string.nav_now_playing_short, Icons.Default.PlayArrow),
    PhoneNavItem(Screen.Queue, R.string.nav_queue, Icons.AutoMirrored.Filled.QueueMusic),
    PhoneNavItem(Screen.Mine, R.string.nav_mine, Icons.Default.Person),
)

/**
 * 竖屏底部导航栏（v2.36.0，方案 §4.0 / §8.4）。
 *
 * **零新增依赖**：`compose.foundation`（`Row`/`background`/`navigationBarsPadding`）
 * + `androidx.tv.material3`（`Text`/`Icon`）+ 现成 [FocusableSurface]。
 * ⚠️ 项目**没有** `androidx.compose.material3` 的编译类路径 → 不能用 `NavigationBar`（方案 C1）。
 *
 * 尺寸（方案 D3 + §2.7 dp 口径）：
 * - 容器高 **56 Compose dp** ≈ 45.9 **物理 dp** ≥ 44dp ✅
 * - 子项 `fillMaxHeight()`，**不加 `padding(vertical = 8.dp)`**，否则热区不足 56dp
 * - v2.36.2 竖屏改版：**去掉文字标签**，图标 24dp → **32dp** 等比放大并垂直居中
 *   （占用原「图标+文字」的空间；无障碍语义不受影响 —— 每项的 `contentDescription`
 *   由 [stringResource(item.labelRes)] 提供，见下方 `semantics`）。
 *
 * ⚠️ **v2.36.1 起为 5 项**（移除了「设置」，见 [PHONE_NAV_ITEMS]）；每项 `weight(1f)`
 * → 少一项后剩余各项**自动等分占满整宽**，不需要改任何布局代码。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PhoneNavBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(NasMusicColors.Surface)
            .navigationBarsPadding()   // D9：竖屏显示系统栏 → 三键导航安全区生效
            .height(56.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PHONE_NAV_ITEMS.forEach { item ->
            val selected = item.screen == currentScreen
            FocusableSurface(
                onClick = { onNavigate(item.screen) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(0.dp),
                focusedScale = 1.0f,          // 手机端不做缩放，避免整条抖动
                animationDurationMs = 150,
                containerColor = Color.Transparent,
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                // v2.36.0 竖屏体验修复：未选中项此前用 TextSecondary(#8899B0)，
                // 压在 Surface(#162032) 上偏暗、辨识度差 → 改用 TextPrimary(#E8EDF5)。
                // ⚠️ 图标与文字都不再显式指定颜色：它们从 `LocalContentColor`（由
                // FocusableSurface 下发）继承，否则 `Icon` 会回退到 tv-material3 的默认值
                // **Color.Black**，在深色底上完全看不见。
                contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                focusedContentColor = NasMusicColors.Primary,
                // 手机没有焦点边框，按下高亮是唯一的"已响应"视觉反馈（P2-34 约定）
                pressedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
                pressedScale = 0.96f,
            ) {
                // ⚠️ `fillMaxSize()` 不可省：`FocusableSurface` 内部的 `Box` **没有指定
                // `contentAlignment`**（默认 `Alignment.TopStart`），而 `Column` 默认 wrap-content
                // → 不加这个 modifier 时整块内容会被贴在按钮**左上角**，
                // 下面的 `horizontalAlignment = CenterHorizontally` 完全不起作用
                // （表现为「每个按钮都是左对齐的」，2026-09-20 真机反馈）。
                // ⛔ 不要去改 `FocusableSurface` 的 `Box` 加 `contentAlignment = Center` ——
                // 它是全项目共用的，改了会动到 TV 端所有按钮的内部对齐（B1：TV 行为必须逐字不变）。
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // v2.36.2：无文字后图标即整项，补无障碍标签（视觉去文字 ≠ 去语义）
                        .semantics { contentDescription = context.getString(item.labelRes) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        // v2.36.2：24dp → 32dp（等比放大，占用原文字空间）
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

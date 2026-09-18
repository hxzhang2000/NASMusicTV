package com.nasmusic.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Screen
import com.nasmusic.tv.ui.theme.NasMusicColors

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
 * - Icon 24dp + 文字 12sp
 */
private data class PhoneNavItem(val screen: Screen, val labelRes: Int, val icon: ImageVector)

/** 5 项，与方案 §4.0 导航映射表一致（队列改由播放页 Chip / 我的页进入） */
private val PHONE_NAV_ITEMS = listOf(
    PhoneNavItem(Screen.Home, R.string.nav_home, Icons.Default.Home),
    PhoneNavItem(Screen.Library, R.string.nav_library, Icons.Default.LibraryMusic),
    PhoneNavItem(Screen.NowPlaying, R.string.nav_now_playing, Icons.Default.PlayArrow),
    PhoneNavItem(Screen.Mine, R.string.nav_mine, Icons.Default.Person),
    PhoneNavItem(Screen.Settings, R.string.nav_settings, Icons.Default.Settings),
)

@OptIn(ExperimentalTvMaterial3Api::class)
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
                contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                focusedContentColor = NasMusicColors.Primary,
                pressedScale = 0.96f,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(item.labelRes),
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

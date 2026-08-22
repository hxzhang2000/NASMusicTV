package com.nasmusic.tv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 原唱/伴奏/K歌/MTV 切换按钮
 *
 * 触摸与遥控器双兼容（FocusableSurface）：手机直接点击，TV D-Pad 聚焦 + OK 键。
 *
 * @param label 按钮文字（入口按钮传 "K歌"/"MTV"/"歌词"，K 歌页内根据当前模式传 "原唱"/"伴唱"）
 * @param onClick 点击回调
 * @param compact 紧凑模式（NowPlayingScreen 控制栏用 true，KARAOKE/MTV 全屏页用 false）
 * @param dimmed 半透明禁用态（如 MTV 无可用视频时）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VocalToggleButton(
    label: String,
    onClick: () -> Unit,
    compact: Boolean = false,
    dimmed: Boolean = false
) {
    val buttonSize = if (compact) 48.dp else 72.dp
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .size(buttonSize)
            .alpha(if (dimmed) 0.35f else 1f),
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.12f,
        animationDurationMs = 250,
        containerColor = NasMusicColors.Surface,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = Color.Black,
        pressedContainerColor = NasMusicColors.SurfaceVariant,
        pressedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.90f,
        focusBorderColor = NasMusicColors.FocusRing
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = if (compact) 14.sp else 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
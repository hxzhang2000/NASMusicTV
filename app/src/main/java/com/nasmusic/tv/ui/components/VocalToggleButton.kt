package com.nasmusic.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 原唱/伴奏/K歌 切换按钮（红色 accent）
 *
 * 参考 maidong-ktv 设计：按钮底色始终为红色，不随状态变色，只切换文字。
 *
 * @param label 按钮文字（入口按钮传 "K歌"，K 歌页内根据当前模式传 "原唱"/"伴唱"）
 * @param onClick 点击回调
 * @param compact 紧凑模式（NowPlayingScreen 控制栏用 true，KARAOKE 全屏页用 false）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VocalToggleButton(
    label: String,
    onClick: () -> Unit,
    compact: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val buttonSize = if (compact) 48.dp else 72.dp

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(buttonSize)
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.isFocused
                scope.launch { animScale.animateTo(if (isFocused) 1.12f else 1f, tween(250)) }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(8.dp),
            focusedShape = RoundedCornerShape(8.dp),
            pressedShape = RoundedCornerShape(8.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFFFD3359),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFFE8316F),
            focusedContentColor = Color.White,
            pressedContainerColor = Color(0xFFC42850),
            pressedContentColor = Color.White
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 0.90f
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = if (compact) 14.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

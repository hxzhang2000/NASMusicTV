package com.nasmusic.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 公共可聚焦 Surface 组件
 *
 * 抽取项目中 30+ 处重复的"焦点缩放动画 + 焦点边框 + ClickableSurfaceDefaults 配置"样板代码。
 * 统一管理：
 * - 焦点状态追踪（isFocused）
 * - 缩放动画（Animatable + animateTo + tween）
 * - 焦点边框（2.dp FocusRing / Transparent）
 * - 可选的 FocusRequester 与启动时自动请求焦点
 * - 可选的焦点变化回调
 *
 * @param onClick 点击回调
 * @param modifier 额外 Modifier（会附加在内部 Modifier 之前）
 * @param shape Surface 形状，默认 RoundedCornerShape(8.dp)
 * @param focusedScale 获得焦点时的缩放比例，默认 1.08f
 * @param animationDurationMs 缩放动画时长（毫秒），默认 200
 * @param containerColor 默认容器颜色
 * @param focusedContainerColor 获得焦点时的容器颜色
 * @param contentColor 默认内容颜色
 * @param focusedContentColor 获得焦点时的内容颜色
 * @param pressedScale 按下时的缩放比例，默认 0.96f
 * @param focusRequester 可选的 FocusRequester，用于外部主动请求焦点
 * @param requestFocusOnLaunch 是否在组件首次进入组合时自动请求焦点，默认 false
 * @param showFocusBorder 是否显示焦点边框，默认 true
 * @param onFocusChanged 焦点变化回调，参数为当前是否获得焦点
 * @param content 内容 Composable
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    focusedScale: Float = 1.08f,
    animationDurationMs: Int = 200,
    containerColor: Color = NasMusicColors.Surface,
    focusedContainerColor: Color = NasMusicColors.Primary.copy(alpha = 0.2f),
    contentColor: Color = NasMusicColors.TextPrimary,
    focusedContentColor: Color = NasMusicColors.Primary,
    pressedScale: Float = 0.96f,
    pressedContainerColor: Color? = null,
    pressedContentColor: Color? = null,
    focusRequester: FocusRequester? = null,
    requestFocusOnLaunch: Boolean = false,
    showFocusBorder: Boolean = true,
    focusBorderColor: Color = NasMusicColors.FocusRing,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    if (requestFocusOnLaunch && focusRequester != null) {
        LaunchedEffect(Unit) {
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {
                // 忽略焦点请求失败（例如组件尚未附着）
            }
        }
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .scale(animScale.value)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .then(
                if (showFocusBorder) {
                    Modifier.border(
                        width = if (isFocused) 2.dp else 0.dp,
                        color = if (isFocused) focusBorderColor else Color.Transparent,
                        shape = shape
                    )
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged?.invoke(it.isFocused)
                scope.launch {
                    animScale.animateTo(
                        if (isFocused) focusedScale else 1f,
                        tween(animationDurationMs)
                    )
                }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = focusedContainerColor,
            focusedContentColor = focusedContentColor,
            pressedContainerColor = pressedContainerColor ?: containerColor,
            pressedContentColor = pressedContentColor ?: contentColor
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = pressedScale
        )
    ) {
        content()
    }
}

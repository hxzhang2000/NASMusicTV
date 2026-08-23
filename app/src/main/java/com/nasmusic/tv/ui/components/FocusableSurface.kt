package com.nasmusic.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.AppLog

/**
 * 公共可点击 Surface 组件（TV / 手机双兼容）
 *
 * 抽取项目中 30+ 处重复的"焦点缩放动画 + 焦点边框 + 点击"样板代码。
 * 统一管理：
 * - 焦点状态追踪（isFocused）
 * - 缩放动画（Animatable + animateTo + tween）
 * - 焦点边框（2dp FocusRing / Transparent，仅 TV 显示）
 * - 可选的 FocusRequester 与启动时自动请求焦点
 * - 可选的焦点变化回调
 *
 * 触摸与遥控器双支持：
 * - 手机：`Modifier.clickable` 直接响应触摸点击 / 按下
 * - TV：D-Pad 聚焦（onFocusChanged 驱动缩放 + 边框），OK 键触发 clickable 的键盘点击
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
 * @param pressedContainerColor 按下时的容器颜色（默认回退 containerColor）
 * @param pressedContentColor 按下时的内容颜色（默认回退 contentColor）
 * @param focusRequester 可选的 FocusRequester，用于外部主动请求焦点
 * @param requestFocusOnLaunch 是否在组件首次进入组合时自动请求焦点，默认 false
 * @param showFocusBorder 是否显示焦点边框，默认 true
 * @param focusBorderColor 焦点边框颜色，默认 NasMusicColors.FocusRing
 * @param onFocusChanged 焦点变化回调，参数为当前是否获得焦点
 * @param content 内容 Composable
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // 设备类型：仅 TV 显示焦点边框（手机触摸无焦点概念）
    val isTVDevice = LocalContext.current.packageManager
        .hasSystemFeature("android.software.leanback")

    if (requestFocusOnLaunch && focusRequester != null) {
        LaunchedEffect(Unit) {
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                AppLog.w("FocusableSurface", "requestFocus failed", e)
            }
        }
    }

    // 动画由 isFocused 状态驱动，避免 onFocusChanged 中 scope.launch 的竞态
    LaunchedEffect(isFocused) {
        animScale.animateTo(
            if (isFocused) focusedScale else 1f,
            tween(animationDurationMs)
        )
    }

    // 按下时缩放反馈（TV 与手机一致——TV 遥控器 OK 键按下同样触发 PressInteraction）
    val currentScale = if (isPressed) pressedScale else animScale.value
    // 容器色状态：按下 > 聚焦 > 默认（内容色由各调用点显式设置）
    val targetContainerColor = when {
        isPressed && pressedContainerColor != null -> pressedContainerColor
        isFocused -> focusedContainerColor
        else -> containerColor
    }

    Box(
        modifier = modifier
            .scale(currentScale)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .then(
                if (showFocusBorder && isTVDevice) {
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
            }
            .background(targetContainerColor, shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        content()
    }
}
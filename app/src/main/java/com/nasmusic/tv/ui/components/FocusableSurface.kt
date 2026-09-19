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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.tv.material3.LocalContentColor
import com.nasmusic.tv.ui.theme.ButtonColors
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.AppLog

/**
 * 供 FocusableSurface 子组件读取的当前内容颜色
 */
val LocalFocusableContentColor = staticCompositionLocalOf { NasMusicColors.TextPrimary }

/**
 * 当前设备是否按 **TV** 处理。
 *
 * v2.36.0（方案 §0.3 / P2-39）：仅判 `android.software.leanback` 会漏掉部分电视盒子
 * （它们只声明 `android.hardware.type.television`）→ 两个 feature **任一命中**即视为 TV。
 *
 * 用途：把「焦点相关视觉」（缩放 / 边框 / 容器色 / 内容色）限制在真的有 D-Pad 的设备上。
 * 手机触摸会让 `Modifier.clickable` / `focusable()` 的节点获得焦点且**焦点会粘住**，
 * 若不区分设备，就会出现"点一下按钮永久放大 / 永久高亮"这类没有原因的视觉残留。
 *
 * ⚠️ 用 `remember` 缓存：`PackageManager.hasSystemFeature` 在低版本（本项目电视是
 * Android 5.1.1 / API 22）可能是一次 binder 调用，而本函数被 143 处 `FocusableSurface`
 * 以及 `UnifiedSongRow` / `RowActionButton`（**每个按钮一次**）在组合期调用 ——
 * 不缓存就是"每次重组每个按钮两次 IPC"。设备类型在进程生命周期内不会变，
 * 按 `Context` 缓存即可（Activity 重建 → 新 Context → 重新求值）。
 */
@Composable
fun isTVDevice(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        context.packageManager.run {
            hasSystemFeature("android.software.leanback") ||
                hasSystemFeature("android.hardware.type.television")
        }
    }
}

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
    contentColor: Color = ButtonColors.DefaultContent,
    focusedContentColor: Color = ButtonColors.FocusedContent,
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
    val tvDevice = isTVDevice()

    if (requestFocusOnLaunch && focusRequester != null) {
        LaunchedEffect(Unit) {
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                AppLog.w("FocusableSurface", "requestFocus failed", e)
            }
        }
    }

    // ⚠️ v2.36.0（P2-34 + 竖屏体验修复）：**焦点态只在 TV 上成立**。
    // `Modifier.clickable` 的节点本身是可聚焦的，手指点一下就会让它获得焦点，且**焦点会粘住**
    // （直到点别处才移走）。手机上并没有 D-Pad，用户看不到也不理解"焦点"——
    // 若照搬 TV 的聚焦视觉，会得到三个莫名其妙的现象：
    //   ① 按钮被点过一次后**永久放大 8%**（P2-34 已修）；
    //   ② 点过的那一项**永久保持高亮容器色**（如底栏/顶栏图标）；
    //   ③ 点过的那一项**永久保持聚焦文字色**。
    // 因此把"焦点相关视觉"（缩放 / 边框 / 容器色 / 内容色）统一收敛到 [activeFocus]：
    // 非 TV 设备恒为 false，手机只保留 `pressed*`（按下瞬时反馈）。
    // TV 侧 `tvDevice == true` → `activeFocus == isFocused`，行为与改动前逐字一致。
    val activeFocus = isFocused && tvDevice

    // 动画由 isFocused 状态驱动，避免 onFocusChanged 中 scope.launch 的竞态
    LaunchedEffect(activeFocus) {
        animScale.animateTo(
            if (activeFocus) focusedScale else 1f,
            tween(animationDurationMs)
        )
    }

    // 按下时缩放反馈（TV 与手机一致——TV 遥控器 OK 键按下同样触发 PressInteraction）
    val currentScale = if (isPressed) pressedScale else animScale.value
    // 容器色状态：按下 > 聚焦 > 默认
    val targetContainerColor = when {
        isPressed && pressedContainerColor != null -> pressedContainerColor
        activeFocus -> focusedContainerColor
        else -> containerColor
    }
    // 内容色状态：按下 > 聚焦 > 默认
    val targetContentColor = when {
        isPressed && pressedContentColor != null -> pressedContentColor
        activeFocus -> focusedContentColor
        else -> contentColor
    }

    Box(
        modifier = modifier
            .scale(currentScale)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .then(
                if (showFocusBorder && tvDevice) {
                    Modifier.border(
                        width = if (activeFocus) 2.dp else 0.dp,
                        color = if (activeFocus) focusBorderColor else Color.Transparent,
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
        // ⚠️ v2.36.0（竖屏「按钮看不清」根因修复）：
        // `androidx.tv.material3.LocalContentColor` 的默认值是 **`Color.Black`**
        // （`ContentColor.kt`：`compositionLocalOf { Color.Black }`），而
        // `Icon(tint = LocalContentColor.current)` 与 `Text(color = ... → LocalContentColor.current)`
        // 都会回退到它。本组件此前**只**提供自定义的 [LocalFocusableContentColor]，
        // 于是内部凡是没显式写 `tint =` / `color =` 的 `Icon` / `Text` 都画成了**黑色** ——
        // 在深色底（`Surface #162032`）上就是"看不清"，且这一现象**在 TV 上同样存在**
        // （黑字压在 `SurfaceVariant #1E2D42` 上几乎不可见）。
        // 这里按 Material `Surface` 的语义补上 `LocalContentColor`，让子组件能正确继承内容色。
        // 排查命令（新增组件后建议复跑）：
        //   grep -rn "Icon(" app/src/main/java | grep -v "tint"
        CompositionLocalProvider(
            LocalFocusableContentColor provides targetContentColor,
            LocalContentColor provides targetContentColor,
        ) {
            content()
        }
    }
}
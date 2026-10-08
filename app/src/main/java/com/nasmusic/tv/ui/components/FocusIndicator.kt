package com.nasmusic.tv.ui.components

import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nasmusic.tv.util.AppLog

/**
 * 焦点指示器（全应用唯一共用机制）—— 真机反馈「遥控器导航完全看不到焦点在哪」的修复。
 *
 * 这里有**两件**必须放在一起的事，任何一处单独改都不完整：
 *
 * ## ① 画法：焦点环必须画在背景**之上**
 *
 * Compose 的绘制顺序 = 修饰符链顺序，越靠前越靠下。`FocusableSurface` 此前是
 * `border(...)` → `background(...)` ⇒ 不透明容器色把焦点环**整个盖住**。
 * 焦点环只在容器色为 `Color.Transparent` 的少数几个组件上侥幸可见，
 * 而所有卡片/列表行用的都是不透明 `NasMusicColors.Surface` ⇒ 全应用看不见焦点框。
 *
 * 本修饰符把焦点环追加到链尾，**画在最上层**。
 *
 * ## ② 判据：别再用「设备是不是电视」来决定要不要画焦点
 *
 * `PackageManager.hasSystemFeature(leanback / type.television)` 在非 Google 认证的
 * 电视盒子上经常双双返回 false（这个判据在本项目历史上已被迫补过两次：
 * 先从 leanback 扩到 `type.television`，v2.20.0 又修了「单查 leanback」）。
 * 而它一旦为 false，[FocusableSurface] 的 `activeFocus` 恒为 false ⇒ 缩放、焦点环、
 * 容器色、内容色**全部**关闭，用户只能靠按确认键反推焦点位置。
 *
 * 真正的判据是「**用户正在用方向键导航**」，这个可以直接观测，不依赖嗅探：
 * ① 静态：没有触摸屏（电视盒子 / 机顶盒 / 投影仪的常态，手机恒为 false）
 * ② 动态：进程内收到过任一方向/确认键（见 [DpadInputTracker]）
 *
 * 手机上 `clickable` 节点点一下会获得焦点且焦点粘住，因此在**只靠触摸**时仍需
 * 关闭焦点视觉（`docs/conventions-adaptive-ui.md` §11），这与本判据不冲突。
 */

/** 焦点环实心描边的默认宽度。TV 远距离观看，2dp 太弱（且见上方 ① 的盖住问题）。 */
val FocusRingWidth: Dp = 3.dp

/** 焦点环外侧柔光带的默认总厚度（内含 [FocusRingWidth] 的实心环）。 */
val FocusHaloWidth: Dp = 7.dp

/**
 * 方向/确认类按键 —— 「用户正在用按键导航」的纯函数判据（可单测）。
 *
 * 只收**方向键 + 确认键**：音量/频道等媒体键由 [com.nasmusic.tv.util.MediaKeyHandler]
 * 处理，与焦点导航无关，收进来会让「按了音量键 ⇒ 之后焦点常亮」的误触发面变大。
 */
fun isDirectionalNavigationKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A -> true

    else -> false
}

/**
 * 方向键使用痕迹（进程级，粘住）。
 *
 * ⛔ 只记「有没有用过方向键」，**不记时间也不回收** —— 焦点视觉本身是无害的
 * （只是把已有的焦点画出来），而回收逻辑一旦出错就会在用户已经形成肌肉记忆后
 * 把指示器抽走，那正是本次要修的故障本身。
 */
object DpadInputTracker {
    /** 组合可读 —— 被 [shouldShowFocusVisuals] 在组合期读取，变化即触发重组。 */
    var directionalNavigationSeen by mutableStateOf(false)
        private set

    /** 由 `MainActivity.dispatchKeyEvent` 接线；状态推进规则见 [nextDirectionalSeen]。 */
    fun noteKeyEvent(keyCode: Int, action: Int) {
        val next = nextDirectionalSeen(directionalNavigationSeen, keyCode, action)
        if (next == directionalNavigationSeen) return
        directionalNavigationSeen = next
        AppLog.i("FocusIndicator", "directional key detected (code=$keyCode) → focus visuals on")
    }
}

/**
 * 状态推进纯函数（可单测）：当前标志 + 一个按键事件 ⇒ 新标志。
 *
 * - 只在 **ACTION_DOWN** 上翻转：`dispatchKeyEvent` 会同时收到 down / up，
 *   在 up 上翻转会白改一次 state，且在按键被系统吞掉时给出错误的「在用按键导航」结论。
 * - 一旦为 true 就永远为 true（粘住），理由见 [DpadInputTracker]。
 */
internal fun nextDirectionalSeen(current: Boolean, keyCode: Int, action: Int): Boolean {
    if (current) return true
    if (action != KeyEvent.ACTION_DOWN) return false
    return isDirectionalNavigationKey(keyCode)
}

/**
 * 是否应当显示焦点视觉 —— **全应用唯一判据**（焦点环 / 缩放 / 容器色 / 内容色都走它）。
 *
 * 静态判据（无触摸屏 或 电视 feature 任一）用 [remember] 缓存：PackageManager 查询
 * 在 API 22 上可能是一次 binder 调用，而本函数被 135+ 处组件在组合期调用。
 * 动态判据（[DpadInputTracker.directionalNavigationSeen]）是快照 state 读，参与重组。
 */
@Composable
fun shouldShowFocusVisuals(): Boolean {
    val context = LocalContext.current
    val packageManager = remember(context) { context.packageManager }
    val looksLikeRemoteDevice = remember(packageManager) {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature("android.hardware.type.television") ||
            // 没有触摸屏 = 只能用遥控器/键盘导航。手机恒为 false，
            // 因此不会把手机误判成 TV 而重现「点一下永久高亮」。
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
    return looksLikeRemoteDevice || DpadInputTracker.directionalNavigationSeen
}

/**
 * 焦点环：外侧柔光带 + 内侧实心描边。
 *
 * - **必须挂在修饰符链的末尾**（在 `.background(...)` 之后），否则会被不透明容器色盖住 ——
 *   这正是本次修复的主因。
 * - 两层都用 [Modifier.border]，它自带「描边整条内缩在边界之内」的语义 ⇒
 *   被祖先/自身 `.clip(shape)` 裁剪后仍完整可见，也因此**绝不覆盖组件内的文字**。
 * - `visible = false` 时**原样返回**（不插入任何修饰符节点），与此前 `width = 0.dp` 等价。
 *
 * @param shape 组件形状，环沿其轮廓绘制
 * @param color 环颜色（默认主色青 `NasMusicColors.FocusRing`）
 * @param visible 是否处于焦点态
 * @param ringWidth 实心环宽度（压在柔光带内缘）
 * @param haloWidth 柔光带宽度（整圈的总厚度）
 */
fun Modifier.focusRing(
    shape: Shape,
    color: Color,
    visible: Boolean,
    ringWidth: Dp = FocusRingWidth,
    haloWidth: Dp = FocusHaloWidth,
): Modifier {
    if (!visible) return this
    // 两层都在**边界之内**绘制（Modifier.border 自带内缩语义）：
    //   ① 柔光带（更宽、更淡）—— 3 米外先看到这一圈青色光晕
    //   ② 实心环（更窄、更亮）—— 压住柔光带的内缘，形成清晰的勾边
    // 全部落在组件自身边缘，不覆盖任何文字；被祖先/自身 `.clip(shape)` 裁剪后仍完整可见。
    return this
        .border(width = haloWidth, color = color.copy(alpha = 0.26f), shape = shape)
        .border(width = ringWidth, color = color, shape = shape)
}
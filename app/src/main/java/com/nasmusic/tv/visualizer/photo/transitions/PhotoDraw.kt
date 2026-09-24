package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.visualizer.photo.PhotoRect
import kotlin.math.roundToInt

/**
 * 转场实现的共享绘制工具
 *
 * ## 为什么要有这一层
 *
 * `DrawScope.drawImage` 的重载有 9 个参数（src/dst 各两个 + alpha / blendMode / …），
 * 每个转场都手写一遍既啰嗦又容易把 `srcOffset` 与 `dstOffset` 写反 —— 那会画出
 * 「图倒着走」这类难查的问题。这里只暴露「源矩形 + 目标矩形」这一层语义。
 *
 * ## 零分配
 *
 * `IntOffset` / `IntSize` / `Size` / `Offset` 都是 Compose 的 **value class**（内联），
 * 构造它们**不产生对象** —— 所以本文件的辅助函数可以安全地在每帧调用。
 */

/** 按「源矩形 → 目标矩形」画一张图 */
internal fun DrawScope.drawPhoto(
    image: ImageBitmap,
    src: PhotoRect,
    dst: PhotoRect,
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    if (alpha <= 0f || !src.isValid || !dst.isValid) return
    drawImage(
        image = image,
        srcOffset = IntOffset(src.left.roundToInt(), src.top.roundToInt()),
        srcSize = IntSize(src.width.roundToInt(), src.height.roundToInt()),
        dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
        dstSize = IntSize(dst.width.roundToInt(), dst.height.roundToInt()),
        alpha = alpha.coerceIn(0f, 1f),
        blendMode = blendMode,
    )
}

/** 同上，但目标矩形整体平移 `(offX, offY)` —— 滑动类转场用 */
internal fun DrawScope.drawPhotoAt(
    image: ImageBitmap,
    src: PhotoRect,
    dst: PhotoRect,
    offX: Float,
    offY: Float,
    alpha: Float = 1f,
) {
    if (alpha <= 0f || !src.isValid || !dst.isValid) return
    drawImage(
        image = image,
        srcOffset = IntOffset(src.left.roundToInt(), src.top.roundToInt()),
        srcSize = IntSize(src.width.roundToInt(), src.height.roundToInt()),
        dstOffset = IntOffset((dst.left + offX).roundToInt(), (dst.top + offY).roundToInt()),
        dstSize = IntSize(dst.width.roundToInt(), dst.height.roundToInt()),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

/**
 * 把 [src] 围绕**自身中心**缩放到 [out]。
 *
 * ⚠️ [out] 必须是**独立的实例**（不能传 `src` 自己）—— 否则中心点会边算边变，
 * 缩放会「漂移」。
 */
internal fun scaleRect(src: PhotoRect, scale: Float, out: PhotoRect) {
    val cx = (src.left + src.right) * 0.5f
    val cy = (src.top + src.bottom) * 0.5f
    val hw = src.width * 0.5f * scale
    val hh = src.height * 0.5f * scale
    out.set(cx - hw, cy - hh, cx + hw, cy + hh)
}

/** 线性插值（转场里大量使用；`VisualizerMath.lerp` 在另一个包，避免跨包依赖） */
internal fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * HOLD 段**停留期运动**（§5.6）—— **原地**改写目标矩形。
 *
 * 两层叠加，进度都由控制器语义化后给入（渲染器不需要知道开关状态）：
 * - [motion]：Ken Burns 进度 `0..1`（`photoWallKenBurns` 关闭时控制器恒写 0）
 *   ⇒ 缩放 `1.00 → 1.08`，CROP 下再平移 `0 → 3%`（向右下）
 * - [boost]：音频反应叠加 `0..1`（`photoWallAudioReactive` 关闭时恒 0）
 *   ⇒ 额外缩放 `0 → 2%`（BREATHE 呼吸 / PULSE_ZOOM 脉冲的合成值）
 *
 * ⛔ **`pan` 只在 CROP 下为 true**：FIT 的 dst 是内接矩形，平移会让另一侧
 * **露出黑边**（图移出内接区），缩放则只会吃掉黑边 —— 所以 FIT 只缩放不平移。
 *
 * ⛔ **必须先读后写**（cx/cy/hw/hh 全部在 `set` 之前算完）—— 这是 `scaleRect`
 * 「out 不能是 src 自己」的 KDoc 警告在本函数里的显式化：这里是**刻意**原地写。
 */
internal fun applyHoldMotion(
    dst: PhotoRect,
    motion: Float,
    boost: Float,
    canvasW: Float,
    canvasH: Float,
    pan: Boolean,
) {
    val scale = 1f + KEN_SCALE * motion + BREATHE_SCALE * boost
    val cx = (dst.left + dst.right) * 0.5f
    val cy = (dst.top + dst.bottom) * 0.5f
    val hw = dst.width * 0.5f * scale
    val hh = dst.height * 0.5f * scale
    val offX = if (pan) canvasW * PAN_RATIO * motion else 0f
    val offY = if (pan) canvasH * PAN_RATIO * motion else 0f
    dst.set(cx - hw + offX, cy - hh + offY, cx + hw + offX, cy + hh + offY)

    // 若平移后左/上缘缩进画布（scale 的增量 < 平移量，理论上不会发生：8% > 3%），
    // 也不做钳制 —— 由渲染器的 clipRect 统一兜底，保持本函数纯几何。
}

internal const val KEN_SCALE = 0.08f

internal const val BREATHE_SCALE = 0.02f

internal const val PAN_RATIO = 0.03f

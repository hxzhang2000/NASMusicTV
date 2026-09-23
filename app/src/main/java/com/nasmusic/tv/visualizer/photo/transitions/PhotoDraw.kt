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

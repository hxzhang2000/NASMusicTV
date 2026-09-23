package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import com.nasmusic.tv.visualizer.Easing
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId

/**
 * 条纹 / 分块类（§14.3 第 11–12 项）
 *
 * ## 块数不是随便定的（M4 性能红线，§4.2）
 *
 * 每块一次 `drawImage` ⇒ 块数直接等于 draw call 数。老电视（ARMv7）的 GPU 在
 * **总块数 > 84（12×7）** 时会明显掉帧，所以这里按「每块至少 64 px」反推块数并封顶：
 *
 * | 效果 | 块排列方向 | 上限 | 依据 |
 * |---|---|---|---|
 * | `BLINDS_H` 横向百叶窗 | 沿**屏高**排 | 14 | 1080 / 64 ≈ 16 ⇒ 取 14 |
 * | `BLINDS_V` 竖向百叶窗 | 沿**屏宽**排 | 24 | 1920 / 64 = 30 ⇒ 取 24 |
 *
 * ⚠️ 两块都在 84 以内，且**不是**「按面积算块数」（那会算出 12×7 的网格）——
 * 百叶窗是一维条带，块数只跟一个方向有关。
 *
 * ## 只画一次旧图
 *
 * 旧图整屏画一次，然后循环里**只**用 `clipRect` 逐块画新图。
 * 若在循环里把旧图也画一遍，draw call 会翻倍（14 块 ⇒ 28 次），而画面完全一样。
 */
internal class BlindsTransition(
    override val id: PhotoTransitionId,
    /** `true` = 横向百叶窗（条带水平、沿屏高排列） */
    private val horizontal: Boolean,
) : PhotoTransition {

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val w = geom.canvasW
        val h = geom.canvasH
        val span = if (horizontal) h else w
        val cross = if (horizontal) w else h
        if (span <= 0f) return

        val maxBlocks = if (horizontal) MAX_BLOCKS_H else MAX_BLOCKS_V
        val blocks = minOf(maxBlocks, (span / BLOCK_PX).toInt()).coerceAtLeast(1)
        val size = span / blocks
        val denom = 1f - STAGGER

        for (i in 0 until blocks) {
            val delay = Easing.stagger(i, blocks, STAGGER)
            val local = ((p - delay) / denom).coerceIn(0f, 1f)
            // 每块从中心向两侧张开（§14.3：每块 easeOut）
            val grow = size * Easing.easeOutQuad(local)
            if (grow <= 0f) continue

            val pos = i * size
            val from = pos + (size - grow) * 0.5f
            if (horizontal) {
                clipRect(0f, from, cross, from + grow) { drawPhoto(b, geom.srcB, geom.dstB) }
            } else {
                clipRect(from, 0f, from + grow, cross) { drawPhoto(b, geom.srcB, geom.dstB) }
            }
        }
    }

    private companion object {
        const val BLOCK_PX = 64f
        const val MAX_BLOCKS_H = 14
        const val MAX_BLOCKS_V = 24

        /** 逐块错落延迟（§5.3：0.9s + 0.3s ⇒ 本值 = 0.3 / 1.2） */
        const val STAGGER = 0.25f
    }
}

package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.photo.MaskCache
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoRect
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId

/**
 * 溶解 / 噪点类 P1（§14.3）—— 缓动是 **linear**（溶解需要均匀，类别约定见 `PhotoTransitionId` F 段）。
 */

/**
 * 阈值扫过：一条**竖直扫过线**从左到右推进，线后是按噪声遮罩「溶解成形」的新图。
 *
 * 实现 = `clipRect` 扫过区 + `saveLayer` 内噪声 `DstIn`（与 [NoiseDissolveTransition]
 * 同一套离屏画法，只是外面套了一层扫过裁剪）⇒ 扫过线是硬边、内部是噪声软边。
 */
internal class ThresholdSweepTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.THRESHOLD_SWEEP

    private var mask: ImageBitmap? = null

    private val maskSrc = PhotoRect()

    private var layerBounds: Rect = Rect.Zero

    private val layerPaint = Paint()

    override fun prepare(geom: PhotoGeometry, quality: VisualQuality, masks: MaskCache) {
        mask = masks.noise()
        maskSrc.set(0f, 0f, MaskCache.SIZE.toFloat(), MaskCache.SIZE.toFloat())
        layerBounds = Rect(0f, 0f, geom.canvasW, geom.canvasH)
    }

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val m = mask ?: return
        if (p <= 0f || layerBounds.width <= 0f) return

        clipRect(0f, 0f, geom.canvasW * p, geom.canvasH) {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(layerBounds, layerPaint)
                drawPhoto(b, geom.srcB, geom.dstB)
                drawPhoto(m, maskSrc, geom.dstA, blendMode = BlendMode.DstIn)
                canvas.restore()
            }
        }
    }

    override fun release() {
        mask = null
    }
}

/**
 * 扫描线溶解：把画面横切成 20 条带，条带按**伪随机顺序**各自淡入。
 * 每条带 1 次 `drawImage`（20 次）+ 旧图 1 次 —— 在 84 块红线内。
 * ⛔ 条带顺序用整型散列（确定性 + 零分配），不用 `Random`。
 */
internal class ScanlineDissolveTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.SCANLINE_DISSOLVE

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)
        if (p >= 1f) {
            drawPhoto(b, geom.srcB, geom.dstB)
            return
        }

        val bandH = geom.canvasH / BANDS
        for (i in 0 until BANDS) {
            val phase = hash(i) * PHASE_SPREAD
            val local = ((p - phase) / (1f - PHASE_SPREAD)).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val y0 = i * bandH
            clipRect(0f, y0, geom.canvasW, y0 + bandH) {
                drawPhoto(b, geom.srcB, geom.dstB, alpha = local)
            }
        }
    }

    /** 条带散列 → `0..1`（确定性：同一转场内每次切换顺序一致） */
    private fun hash(band: Int): Float {
        var h = band * 1000003 + 0x9E3779B9.toInt()
        h = h xor (h ushr 13)
        h *= 0x45d9f3b
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private companion object {
        const val BANDS = 20

        /** 最晚条带的起始延迟（最后 30% 留给条带自身的淡入） */
        const val PHASE_SPREAD = 0.7f
    }
}

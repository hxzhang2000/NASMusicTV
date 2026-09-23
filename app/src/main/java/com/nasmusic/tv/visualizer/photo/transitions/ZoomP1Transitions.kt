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
 * 缩放 / 深度类 P1（§14.3）
 *
 * - [CrossZoomTransition] `CROSS_ZOOM`：两图同向放大（旧图放大出画 + 新图追上就位）
 * - [ZoomThroughTransition] `ZOOM_THROUGH`：旧图急速放大「穿过」镜头，新图从远处追近
 * - [DepthBlurTransition] `DEPTH_BLUR`：新图从「远处（略大 + 蒙化）」对焦到位
 *   ⚠️ 真高斯模糊需要 `RenderEffect`（API 33+）；本实现用「噪声遮罩渐显 + 前段略放大」
 *   近似景深感，全平台可用（§4.4 的 M3 近似精神）
 */
internal class CrossZoomTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.CROSS_ZOOM

    private val tmpA = PhotoRect()
    private val tmpB = PhotoRect()

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        clipRect(0f, 0f, geom.canvasW, geom.canvasH) {
            // 旧图持续放大出画（保持不透明 ⇒ 无暗场）
            scaleRect(geom.dstA, lerpF(1.00f, 1.25f, p), tmpA)
            drawPhoto(a, geom.srcA, tmpA)
            // 新图从略小追上就位
            scaleRect(geom.dstB, lerpF(0.80f, 1.00f, p), tmpB)
            drawPhoto(b, geom.srcB, tmpB, alpha = p)
        }
    }
}

internal class ZoomThroughTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.ZOOM_THROUGH

    private val tmpA = PhotoRect()
    private val tmpB = PhotoRect()

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        clipRect(0f, 0f, geom.canvasW, geom.canvasH) {
            // 旧图：前 70% 放大 1 → 2.4 并淡出（「穿过」它）
            if (p < A_END) {
                val q = p / A_END
                scaleRect(geom.dstA, lerpF(1.0f, 2.4f, q), tmpA)
                drawPhoto(a, geom.srcA, tmpA, alpha = 1f - q)
            }
            // 新图：后 70% 从 1.6 缩回 1.0 并淡入（从深处追上来）
            if (p > B_START) {
                val q = (p - B_START) / (1f - B_START)
                scaleRect(geom.dstB, lerpF(1.6f, 1.0f, q), tmpB)
                drawPhoto(b, geom.srcB, tmpB, alpha = q)
            }
        }
        // ⚠️ p ∈ (A_END, B_START + 交叠区) 旧图已淡出、新图未完全显形 ⇒ 中段露出暗底，
        // 这正是「穿越隧道」想要的暗场纵深（与 CROSS_ZOOM 的「全程无暗场」相反）。
    }

    private companion object {
        const val A_END = 0.7f
        const val B_START = 0.3f
    }
}

internal class DepthBlurTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.DEPTH_BLUR

    private var mask: ImageBitmap? = null

    private val maskSrc = PhotoRect()

    private var layerBounds: Rect = Rect.Zero

    private val layerPaint = Paint()

    private val tmpB = PhotoRect()

    override fun prepare(geom: PhotoGeometry, quality: VisualQuality, masks: MaskCache) {
        mask = masks.noise()
        maskSrc.set(0f, 0f, MaskCache.SIZE.toFloat(), MaskCache.SIZE.toFloat())
        layerBounds = Rect(0f, 0f, geom.canvasW, geom.canvasH)
    }

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val m = mask ?: return
        if (p <= 0f || layerBounds.width <= 0f) return

        drawIntoCanvas { canvas ->
            canvas.saveLayer(layerBounds, layerPaint)
            // 前段略大（1.12 → 1.0）：模拟「从焦外拉回焦内」的纵深
            scaleRect(geom.dstB, lerpF(1.12f, 1.0f, p), tmpB)
            drawPhoto(b, geom.srcB, tmpB)
            drawPhoto(m, maskSrc, geom.dstA, alpha = p, blendMode = BlendMode.DstIn)
            canvas.restore()
        }
    }

    override fun release() {
        mask = null
    }
}

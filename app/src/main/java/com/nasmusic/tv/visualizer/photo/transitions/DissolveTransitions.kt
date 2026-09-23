package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.photo.MaskCache
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoRect
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId

/**
 * 溶解 / 噪点类（§14.3 第 13 项）
 *
 * ## 实现方式：离屏层 + 遮罩 `DstIn`
 *
 * ```
 * ① 画旧图（全屏，不透明）
 * ② saveLayer 开一个离屏层
 * ③   在层内画新图（全屏）
 * ④   用噪声遮罩以 DstIn 合成 ⇒ 只保留遮罩 alpha 非零的像素
 * ⑤ restore：把该层叠到旧图之上
 * ```
 *
 * ⛔ **必须自己 `saveLayer`**，不能直接在主画布上用 `DstIn` ——
 * `VisualizerStage` 的 Canvas 本身已经是离屏层（`compositingStrategy = Offscreen`），
 * 在主画布上 `DstIn` 会连**旧图和背景一起裁掉**。
 *
 * ## 为什么是「遮罩 alpha × p」而不是「阈值化」
 *
 * 文档写的是「阈值 `0→1`」，直觉实现是每帧按阈值二值化遮罩 —— 那需要一个
 * `ColorFilter`，而 `ColorFilter` / `ColorMatrix` 在 Compose 里每次构造都要分配对象
 * （每帧一次），且阈值化会产生**硬边**。
 *
 * 改成「把遮罩整体的 alpha 乘以 `p`」后：噪声里 alpha 高的像素先达到可见 ⇒
 * 视觉上就是溶解，且**平滑无硬边**、**零分配**（`alpha` 是 `drawImage` 的普通参数）。
 *
 * ## 每帧分配
 *
 * `saveLayer` 的 bounds 用 [prepare] 里缓存的 [layerBounds]（尺寸变化时重建），
 * 所以稳态每帧**零分配**。⚠️ 前提是调用方在画布尺寸变化时重新调用 `prepare`。
 */
internal class NoiseDissolveTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.NOISE_DISSOLVE

    private var mask: ImageBitmap? = null

    /** 遮罩的源矩形（整张 256×256） */
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
        if (p <= 0f) return
        if (layerBounds.width <= 0f) return

        drawIntoCanvas { canvas ->
            canvas.saveLayer(layerBounds, layerPaint)
            drawPhoto(b, geom.srcB, geom.dstB)
            // alpha = p：噪声强度被整体放大 ⇒ 强处先显现（溶解观感）
            drawPhoto(m, maskSrc, geom.dstA, alpha = p, blendMode = BlendMode.DstIn)
            canvas.restore()
        }
    }

    override fun release() {
        // ⚠️ 只丢引用 —— 遮罩位图由 MaskCache 全进程共享，不能在这里 recycle
        mask = null
    }
}

package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId

/**
 * 色彩 / 光效类（§14.3 第 14–15 项）
 */

/**
 * 光扫：一条高光带扫过画面，同时新图渐显。
 *
 * ⚠️ 高光带**不用 `Brush.linearGradient`** —— `Brush` 是普通对象，构造它会在绘制路径上
 * 产生分配（`DrawScope` 里也没法 `remember` 缓存它）。这里用「中心亮带 + 两侧弱带」
 * 三条实心矩形模拟渐变：`Color` 是 value class，`Offset` / `Size` 也是 ⇒ **零分配**。
 * 三条带在 0.6s 的快速扫动下与真渐变几乎无法区分。
 */
internal class LightSweepTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.LIGHT_SWEEP

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)
        drawPhoto(b, geom.srcB, geom.dstB, alpha = p)

        val band = geom.minDim * BAND_RATIO
        if (band <= 0f) return
        // 从画布左侧外扫到右侧外，p = 1 时带子已离场
        val cx = -band + (geom.canvasW + band * 2f) * p
        val fade = 1f - p
        val h = geom.canvasH

        // 中心亮带
        drawRect(
            color = Color.White.copy(alpha = CORE_ALPHA * fade),
            topLeft = Offset(cx - band, 0f),
            size = Size(band * 2f, h),
        )
        // 前缘 / 后缘弱带
        drawRect(
            color = Color.White.copy(alpha = EDGE_ALPHA * fade),
            topLeft = Offset(cx - band * 1.6f, 0f),
            size = Size(band * 0.6f, h),
        )
        drawRect(
            color = Color.White.copy(alpha = EDGE_ALPHA * fade),
            topLeft = Offset(cx + band, 0f),
            size = Size(band * 0.6f, h),
        )
    }

    private companion object {
        /** 高光带宽 = 0.25 × 短边（§14.3） */
        const val BAND_RATIO = 0.25f
        const val CORE_ALPHA = 0.30f
        const val EDGE_ALPHA = 0.18f
    }
}

/**
 * 频谱擦除：擦除边界 = 实时频谱包络（64 段）。
 *
 * 每列从底部向上擦除，**频谱高的列擦得更快** ⇒ 边界呈柱状包络推进。
 *
 * ⚠️ **无频谱数据时退化为整体淡入**（不是留黑屏）：未播放 / 分析未就绪 / 静音时
 * `spectrum` 可能全为 0，若严格按包络擦除会「什么都不显示」。
 *
 * ⚠️ 每帧最多 64 次 `drawImage`（每列一次）。**电视上需实测帧率** ——
 * 这是 P0 里 draw call 最多的一个（`BLINDS_*` 是 14 / 24 次）。
 */
internal class SpectrumWipeTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.SPECTRUM_WIPE

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val spec = geom.spectrum
        if (spec == null || spec.isEmpty()) {
            drawPhoto(b, geom.srcB, geom.dstB, alpha = p)
            return
        }

        val bars = spec.size
        val barW = geom.canvasW / bars
        val h = geom.canvasH
        for (i in 0 until bars) {
            val col = (p * (1f + spec[i] * GAIN)).coerceIn(0f, 1f)
            val ch = h * col
            if (ch <= 0f) continue
            val x = i * barW
            clipRect(x, h - ch, x + barW, h) { drawPhoto(b, geom.srcB, geom.dstB) }
        }
    }

    private companion object {
        /** 频谱对擦除速度的放大系数：`col = p × (1 + spec × GAIN)`，最大 2× */
        const val GAIN = 1f
    }
}

package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import com.nasmusic.tv.visualizer.VisualizerRandom
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId
import kotlin.math.abs

/**
 * 分块类 P1（§14.3）—— 网格类共用同一条 M4 红线：**总块数 ≤ 84**（12×7，§4.2 / `BlindsTransition`）。
 *
 * 全部沿用「旧图整幅画一次 + 循环里 `clipRect` 逐块画新图」的省 draw call 画法。
 */

/** 网格计算的公共字段（块数在 [prepare] 里按画布尺寸反推并封顶 12×7） */
internal abstract class GridRevealTransition : PhotoTransition {

    protected var cols = 1
        private set
    protected var rows = 1
        private set
    protected var tileW = 0f
        private set
    protected var tileH = 0f
        private set

    override fun prepare(geom: PhotoGeometry, quality: com.nasmusic.tv.data.model.VisualQuality, masks: com.nasmusic.tv.visualizer.photo.MaskCache) {
        cols = minOf(MAX_COLS, (geom.canvasW / BLOCK_PX).toInt().coerceAtLeast(1))
        rows = minOf(MAX_ROWS, (geom.canvasH / BLOCK_PX).toInt().coerceAtLeast(1))
        tileW = geom.canvasW / cols
        tileH = geom.canvasH / rows
    }

    protected companion object {
        const val BLOCK_PX = 64f

        /** M4 红线：12×7 = 84 块（§4.2） */
        const val MAX_COLS = 12
        const val MAX_ROWS = 7
    }
}

/** 棋盘格：黑白两批交替揭示，批内再沿对角线波次推进 */
internal class CheckerboardTransition : GridRevealTransition() {

    override val id: PhotoTransitionId = PhotoTransitionId.CHECKERBOARD

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val denom = 1f - MAX_DELAY
        for (row in 0 until rows) {
            val y0 = row * tileH
            for (col in 0 until cols) {
                // 延迟 = 棋盘批（0.45）+ 对角波次（0.35），最大 0.8 ⇒ 留 0.2 供块内推进
                val parity = (row + col) % 2
                val diag = (col.toFloat() / cols + row.toFloat() / rows) * 0.5f
                val delay = parity * PARITY_DELAY + diag * DIAGONAL_DELAY
                val local = ((p - delay) / denom).coerceIn(0f, 1f)
                if (local <= 0f) continue
                val x0 = col * tileW
                clipRect(x0, y0, x0 + tileW, y0 + tileH) {
                    drawPhoto(b, geom.srcB, geom.dstB)
                }
            }
        }
    }

    private companion object {
        const val PARITY_DELAY = 0.45f
        const val DIAGONAL_DELAY = 0.35f
        const val MAX_DELAY = 0.8f
    }
}

/**
 * 随机方块消融：网格按**随机顺序**逐块显现。
 *
 * 随机序在 [prepare] 生成一次（种子固定 ⇒ 同一转场的顺序跨切换一致）、
 * [onSwapStart] 里用 Fisher–Yates **原地重洗**（零分配：只交换 `IntArray` 元素）。
 * 这样「每次切换都是新顺序」且不违反 onSwapStart 的零分配约束。
 */
internal class BlocksRandomTransition(
    private val random: VisualizerRandom = VisualizerRandom(),
) : GridRevealTransition() {

    override val id: PhotoTransitionId = PhotoTransitionId.BLOCKS_RANDOM

    /** 上限 12×7 = 84，prepare 里按实际块数截断 */
    private val order = IntArray(MAX_COLS * MAX_ROWS)

    override fun prepare(geom: PhotoGeometry, quality: com.nasmusic.tv.data.model.VisualQuality, masks: com.nasmusic.tv.visualizer.photo.MaskCache) {
        super.prepare(geom, quality, masks)
        for (i in order.indices) order[i] = i
    }

    override fun onSwapStart() {
        val n = cols * rows
        // Fisher–Yates 原地重洗（零分配）
        for (i in n - 1 downTo 1) {
            val j = (random.next() * (i + 1)).toInt().coerceIn(0, i)
            val t = order[i]
            order[i] = order[j]
            order[j] = t
        }
    }

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val n = cols * rows
        val visible = p * n
        for (idx in 0 until n) {
            val block = order[idx]
            val col = block % cols
            val row = block / cols
            // 软边：可见阈值后给 3 块的过渡带（硬切换会闪）
            val local = ((visible - idx) / SOFT_EDGE).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val x0 = col * tileW
            val y0 = row * tileH
            clipRect(x0, y0, x0 + tileW, y0 + tileH) {
                drawPhoto(b, geom.srcB, geom.dstB)
            }
        }
    }

    private companion object {
        const val SOFT_EDGE = 3f
    }
}

/** 瓦片错落：对角波次揭示，每块内容从上方滑入（像瓦片依次落下归位） */
internal class TileCascadeTransition : GridRevealTransition() {

    override val id: PhotoTransitionId = PhotoTransitionId.TILE_CASCADE

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val denom = 1f - STAGGER
        for (row in 0 until rows) {
            val y0 = row * tileH
            for (col in 0 until cols) {
                val diag = (col + row).toFloat() / (cols + rows - 2).coerceAtLeast(1)
                val delay = diag * STAGGER
                val local = ((p - delay) / denom).coerceIn(0f, 1f)
                if (local <= 0f) continue
                // 块内滑入段：local 前 60% 从上方滑进，后 40% 原位
                val slide = ((local) / INNER).coerceIn(0f, 1f)
                val offY = -(1f - slide) * tileH
                val x0 = col * tileW
                clipRect(x0, y0, x0 + tileW, y0 + tileH) {
                    drawPhotoAt(b, geom.srcB, geom.dstB, offX = 0f, offY = offY)
                }
            }
        }
    }

    private companion object {
        const val STAGGER = 0.45f
        const val INNER = 0.55f
    }
}

/**
 * 故障风：水平条带以**伪随机 X 偏移**错位，条带内容随推进「跳变」并最终归位。
 *
 * 偏移 = hash(条带 × 步进)，步进 = ⌊p × 12⌋ —— 推进过程中每 ~8% 进度整体跳一次，
 * 模拟故障帧。p → 1 时偏移收敛到 0。
 * ⛔ 不能用 `Random`（每次调用要分配 / 有状态），用整型散列（确定性、零分配）。
 */
internal class GlitchTransition(
    private val random: VisualizerRandom = VisualizerRandom(),
) : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.GLITCH

    private var seed = 0

    override fun onSwapStart() {
        // 只换种子（Int），零分配 —— 每次切换的故障序列都不同
        seed = (random.next() * Int.MAX_VALUE).toInt()
    }

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val shrink = 1f - p // p → 1 时幅度归零（归位）
        // ⛔ 归位之后必须**整幅画一次新图**，不能「跳过条带」。
        //   条带偏移收敛到亚像素时逐带绘制与整幅等价，所以「省掉 12 次 draw」是对的；
        //   但**跳过之后画面就只剩上面那一层旧图** —— 而 `p` 到 1 之后 HOLD 期一直是 1
        //   ⇒ 整个停留期都在显示**旧照片**，直到下一次切换才把它换成新图。
        //   用户看到的就是「入场动画没走完就停了」（§10.182）。
        if (shrink <= SETTLED) {
            drawPhoto(b, geom.srcB, geom.dstB)
            return
        }

        val bandH = geom.canvasH / BANDS
        val step = (p * STEPS).toInt()
        for (i in 0 until BANDS) {
            val h1 = hash(seed, i, step)
            val offset = (h1 - 0.5f) * geom.canvasW * MAX_SHIFT * shrink
            val y0 = i * bandH
            clipRect(0f, y0, geom.canvasW, y0 + bandH) {
                drawPhotoAt(b, geom.srcB, geom.dstB, offX = offset, offY = 0f)
            }
        }
    }

    /** 整型散列 → `0..1` 的 float（xorshift 风格，确定性、零分配） */
    private fun hash(seed: Int, band: Int, step: Int): Float {
        var h = seed * 31 + band * 1000003 + step * 2654435761.toInt()
        h = h xor (h ushr 16)
        h *= 0x45d9f3b
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private companion object {
        const val BANDS = 12
        const val STEPS = 12

        /** 最大错位 = 6% 屏宽 */
        const val MAX_SHIFT = 0.06f

        /**
         * 「归位」阈值：`1 - p ≤ 本值`（即 `p ≥ 0.95`）时条带偏移已收敛到亚像素
         * （`0.06 × 屏宽 × 0.05 ≈ 6 px` 上限，且多数条带远小于此）⇒ 改为整幅绘制。
         */
        const val SETTLED = 0.05f
    }
}

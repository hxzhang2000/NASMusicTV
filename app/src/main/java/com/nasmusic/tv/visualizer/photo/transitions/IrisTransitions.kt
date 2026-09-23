package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId
import com.nasmusic.tv.visualizer.VisualizerRandom
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 遮罩形状类（§14.3 第 9–10 项）
 *
 * 两个都用 `clipPath` 而不是「遮罩位图 + `BlendMode`」：形状是**参数化**的（半径 / 方向随
 * `p` 连续变化），用位图就得为每个 `p` 生成一张 —— 而 `clipPath` 是矢量裁剪，
 * 零位图内存、零逐像素成本。
 */

/**
 * 圆形光圈：半径 `0 → 对角线/2`。
 *
 * ⚠️ 圆用 **64 边形**逼近，而不是 `Path.addOval(Rect)` —— Compose 的 `Rect` 是不可变
 * data class，每帧构造一个来算 `addOval` 会破坏「绘制路径零分配」的约束。
 * 64 边形在 1080p 上的最大弦高误差约 1.3 px，肉眼不可见，而每帧只多 ~128 次三角函数。
 */
internal class IrisCircleTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.IRIS_CIRCLE

    private val circle = Path()

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val r = geom.diagonalHalf * p
        if (r <= 0f) return

        buildCircle(geom.canvasW * 0.5f, geom.canvasH * 0.5f, r)
        clipPath(circle) { drawPhoto(b, geom.srcB, geom.dstB) }
    }

    private fun buildCircle(cx: Float, cy: Float, r: Float) {
        circle.reset()
        for (i in 0 until STEPS) {
            val ang = i * TWO_PI / STEPS
            val x = cx + cos(ang) * r
            val y = cy + sin(ang) * r
            if (i == 0) circle.moveTo(x, y) else circle.lineTo(x, y)
        }
        circle.close()
    }

    private companion object {
        const val STEPS = 64
        const val TWO_PI = (PI * 2.0).toFloat()
    }
}

/**
 * 线性擦除（**8 方向，每次切换随机取 1**，池内算 1 个元素）
 *
 * ⚠️ 方向在 [onSwapStart] 里抽，**不是**在 `prepare` 里 —— `prepare` 只在「切转场 / 切画质」
 * 时被调用，固定转场模式下连续多次切换不会重新调用它，方向就永远不变了。
 *
 * 8 个方向全部用**多边形裁剪**表达（4 主轴 = 矩形推进，4 对角 = 三角形推进），
 * 不区分实现 —— 这样「方向」只是顶点坐标的组合，不会出现「对角方向忘了做」。
 */
internal class WipeLinearTransition(
    private val random: VisualizerRandom = VisualizerRandom(),
) : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.WIPE_LINEAR

    private val poly = Path()

    /** `0..7`：左→右 / 右→左 / 上→下 / 下→上 / 左上→右下 / 右上→左下 / 左下→右上 / 右下→左上 */
    private var dir = 0

    override fun onSwapStart() {
        dir = (random.next() * DIR_COUNT).toInt().coerceIn(0, DIR_COUNT - 1)
    }

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val w = geom.canvasW
        val h = geom.canvasH
        poly.reset()
        when (dir) {
            0 -> {
                val x = w * p
                poly.moveTo(0f, 0f); poly.lineTo(x, 0f); poly.lineTo(x, h); poly.lineTo(0f, h)
            }

            1 -> {
                val x = w * (1f - p)
                poly.moveTo(w, 0f); poly.lineTo(x, 0f); poly.lineTo(x, h); poly.lineTo(w, h)
            }

            2 -> {
                val y = h * p
                poly.moveTo(0f, 0f); poly.lineTo(w, 0f); poly.lineTo(w, y); poly.lineTo(0f, y)
            }

            3 -> {
                val y = h * (1f - p)
                poly.moveTo(0f, h); poly.lineTo(w, h); poly.lineTo(w, y); poly.lineTo(0f, y)
            }

            // 对角线推进距离取 (w + h) * p：p=1 时斜边恰好扫过整个画布
            4 -> {
                val d = (w + h) * p
                poly.moveTo(0f, 0f); poly.lineTo(d, 0f); poly.lineTo(0f, d)
            }

            5 -> {
                val d = (w + h) * p
                poly.moveTo(w, 0f); poly.lineTo(w - d, 0f); poly.lineTo(w, d)
            }

            6 -> {
                val d = (w + h) * p
                poly.moveTo(0f, h); poly.lineTo(d, h); poly.lineTo(0f, h - d)
            }

            else -> {
                val d = (w + h) * p
                poly.moveTo(w, h); poly.lineTo(w - d, h); poly.lineTo(w, h - d)
            }
        }
        poly.close()
        clipPath(poly) { drawPhoto(b, geom.srcB, geom.dstB) }
    }

    private companion object {
        const val DIR_COUNT = 8
    }
}

package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import com.nasmusic.tv.visualizer.VisualizerRandom
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 遮罩形状类 P1（§14.3）—— 全部沿用 `IrisCircleTransition` 的「参数化 `clipPath`」路线：
 * 形状随 `p` 连续变化，用矢量裁剪 ⇒ 零位图内存、零逐像素成本。
 */

/** 多边形光圈的公共基类：形状 = 预生成的单位顶点表（`[-1,1]` 归一），渲染时缩放平移 */
internal abstract class PolygonIrisTransition : PhotoTransition {

    private val poly = Path()

    /** 单位顶点表（顺时针），字段初始化时生成 —— 纯 JVM 数组，无 Android 依赖 */
    protected abstract val unitVertices: FloatArray

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val r = geom.diagonalHalf * p
        if (r <= 0f) return

        val v = unitVertices
        val cx = geom.canvasW * 0.5f
        val cy = geom.canvasH * 0.5f
        poly.reset()
        for (i in v.indices step 2) {
            val x = cx + v[i] * r
            val y = cy + v[i + 1] * r
            if (i == 0) poly.moveTo(x, y) else poly.lineTo(x, y)
        }
        poly.close()
        clipPath(poly) { drawPhoto(b, geom.srcB, geom.dstB) }
    }
}

/** 菱形：4 顶点（上下左右） */
internal class IrisDiamondTransition : PolygonIrisTransition() {

    override val id: PhotoTransitionId = PhotoTransitionId.IRIS_DIAMOND

    override val unitVertices: FloatArray = floatArrayOf(
        0f, -1f, // 上
        1f, 0f,  // 右
        0f, 1f,  // 下
        -1f, 0f, // 左
    )
}

/** 六边形：6 顶点（平顶朝上），顶点角 = −90° + k·60° */
internal class IrisHexagonTransition : PolygonIrisTransition() {

    override val id: PhotoTransitionId = PhotoTransitionId.IRIS_HEXAGON

    override val unitVertices: FloatArray = FloatArray(12) { i ->
        val k = i / 2
        val ang = -PI.toFloat() / 2f + k * PI.toFloat() / 3f
        if (i % 2 == 0) cos(ang) else sin(ang)
    }
}

/** 五角星：10 顶点（外径 1 / 内径 0.45 交替） */
internal class IrisStarTransition : PolygonIrisTransition() {

    override val id: PhotoTransitionId = PhotoTransitionId.IRIS_STAR

    override val unitVertices: FloatArray = FloatArray(20) { i ->
        val outer = i % 2 == 0
        val ang = -PI.toFloat() / 2f + (i / 2) * PI.toFloat() * 2f / 10f
        val r = if (outer) 1f else INNER_RATIO
        if (i % 2 == 0) cos(ang) * r else sin(ang) * r
    }

    private companion object {
        const val INNER_RATIO = 0.45f
    }
}

/**
 * 随机形状池：菱形 / 六边形 / 五角星 / 方形，**每次切换随机取 1**（池内算 1 个元素）。
 * 复用 [PolygonIrisTransition] 的裁剪逻辑，只是顶点表可切换。
 */
internal class ShapeRandomTransition(
    private val random: VisualizerRandom = VisualizerRandom(),
) : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.SHAPE_RANDOM

    private val diamond = IrisDiamondTransition()
    private val hexagon = IrisHexagonTransition()
    private val star = IrisStarTransition()
    private val square = SquareIris()

    private var current: PolygonIrisTransition = diamond

    override fun onSwapStart() {
        current = when ((random.next() * 4).toInt().coerceIn(0, 3)) {
            0 -> diamond
            1 -> hexagon
            2 -> star
            else -> square
        }
    }

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        with(current) { render(a, b, p, geom) }
    }

    /** 方形（旋转 45° 的菱形就是方形家族里最好看的一种；这里用正方形本身） */
    private inner class SquareIris : PolygonIrisTransition() {

        override val id: PhotoTransitionId = PhotoTransitionId.SHAPE_RANDOM

        override val unitVertices: FloatArray = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)
    }
}

/**
 * 时钟擦除：从 12 点方向顺时针扫过一个扇形（0 → 360°）。
 * 扇形 = 圆心 + 弧上 64 段折线（同 `IrisCircleTransition` 的逼近法）。
 */
internal class WipeClockTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.WIPE_CLOCK

    private val wedge = Path()

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val sweep = p * TWO_PI
        val cx = geom.canvasW * 0.5f
        val cy = geom.canvasH * 0.5f
        val r = geom.diagonalHalf
        wedge.reset()
        wedge.moveTo(cx, cy)
        for (i in 0 until STEPS) {
            val ang = START_ANGLE + sweep * i / (STEPS - 1)
            wedge.lineTo(cx + cos(ang) * r, cy + sin(ang) * r)
        }
        wedge.close()
        clipPath(wedge) { drawPhoto(b, geom.srcB, geom.dstB) }
    }

    private companion object {
        const val STEPS = 64
        const val TWO_PI = (PI * 2.0).toFloat()
        const val START_ANGLE = -PI.toFloat() / 2f
    }
}

/**
 * 十字擦除：水平条 + 垂直条从中心向两侧同步张开。
 * 两个 `clipRect` 各画一次新图（并集 = 十字），draw call = 3（旧图 + 2 块）。
 */
internal class WipeCrossTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.WIPE_CROSS

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val w = geom.canvasW
        val h = geom.canvasH
        val halfW = w * 0.5f * p
        val halfH = h * 0.5f * p

        // 水平条（先横后纵：纵条覆盖中点交叉区，视觉上一致）
        clipRect(w * 0.5f - halfW, h * 0.5f - halfH, w * 0.5f + halfW, h * 0.5f + halfH) {
            drawPhoto(b, geom.srcB, geom.dstB)
        }
        clipRect(0f, h * 0.5f - halfH, w, h * 0.5f + halfH) {
            drawPhoto(b, geom.srcB, geom.dstB)
        }
        clipRect(w * 0.5f - halfW, 0f, w * 0.5f + halfW, h) {
            drawPhoto(b, geom.srcB, geom.dstB)
        }
    }
}

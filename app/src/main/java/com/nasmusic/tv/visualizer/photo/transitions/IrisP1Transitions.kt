package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.VisualizerRandom
import com.nasmusic.tv.visualizer.photo.MaskCache
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
    internal abstract val unitVertices: FloatArray

    /**
     * 终态半径（px）：`p = 1` 时多边形恰好**盖住整个画布**所需的最小半径。
     *
     * ⛔ **不能沿用 `geom.diagonalHalf`** —— 那是**圆**的终态半径（圆心到画布四角的距离
     * 恰好等于对角线的一半，所以圆在 `p = 1` 时正好把画布盖满）。多边形的边界比同半径的
     * 圆更靠内：菱形需要 `(宽 + 高) / 2`，五角星因为内径只有 0.45 倍还要更大。
     * 用对角线/2 时**四个角会露在形状外面** ⇒ `p` 走到 1、进入 HOLD 之后旧图仍在四角可见，
     * 看起来就是「入场动画没走完就停了」（与 [com.nasmusic.tv.visualizer.photo.transitions.GlitchTransition]
     * 同一类终帧缺陷，见 `docs/technical-overview.md` §10.182）。
     *
     * 在 [prepare] 里按画布算一次（稳态每帧零分配）。
     */
    private var coverRadius = 0f

    override fun prepare(geom: PhotoGeometry, quality: VisualQuality, masks: MaskCache) {
        coverRadius = polygonCoverRadius(unitVertices, geom.canvasW, geom.canvasH)
    }

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val r = coverRadius * p
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

/**
 * 「以画布中心为原点、把 [unitVertices] 缩放 `r` 倍」的多边形**盖住整个画布矩形**所需的最小 `r`。
 *
 * ## 为什么用采样而不是解析求交
 *
 * 判据是「矩形 ⊂ `r` 倍多边形」。解析做法是「取四角方向，求射线与多边形边界的交点」，
 * 但那只对**凸**多边形成立 —— 五角星是**凹**的（内径 0.45 倍），凹口可能咬进矩形的内部，
 * 而四个角全在形状内并不足以推出整条边都在形状内。
 * 采样（矩形上取一张 `(GRID+1)²` 网格）对凸 / 凹形状一视同仁，且实现只有十几行。
 *
 * ⚠️ 只在 `prepare` 里调用（低频），所以采样成本可以忽略；**不要**搬进 `render`。
 *
 * @return 需要的半径；输入非法时返回 `0f`（调用方据此跳过绘制 —— 画面上只剩旧图，
 *   比整屏黑好）
 */
internal fun polygonCoverRadius(unitVertices: FloatArray, canvasW: Float, canvasH: Float): Float {
    if (unitVertices.size < 6 || canvasW <= 0f || canvasH <= 0f) return 0f
    val hw = canvasW * 0.5f
    val hh = canvasH * 0.5f

    // 起点取 `max(半宽, 半高)`：对现有四种形状这都是**下界**（正方形恰好相等、
    // 菱形/六边形/五角星都更大）⇒ 网格里大部分点一上来就已经在形状内，省掉大量二分。
    var r = maxOf(hw, hh)
    for (gy in 0..GRID) {
        val y = -hh + canvasH * gy / GRID
        for (gx in 0..GRID) {
            val x = -hw + canvasW * gx / GRID
            if (insideUnitPolygon(unitVertices, x / r, y / r)) continue
            r = growUntilInside(unitVertices, x, y, r)
        }
    }
    // 网格最粗处的间距是 `边长 / GRID`，紧贴边界的采样点可能比真值小一点点 ⇒ 留余量
    return r * COVER_MARGIN
}

/** 二分求「使 `(x, y)` 落在 `r` 倍多边形内」的最小 `r`（`r` 越大越容易落在内部，单调） */
private fun growUntilInside(v: FloatArray, x: Float, y: Float, from: Float): Float {
    var lo = from
    var hi = if (from > 1f) from else 1f
    var guard = 0
    while (!insideUnitPolygon(v, x / hi, y / hi) && guard++ < 64) hi *= 2f
    repeat(BISECT_STEPS) {
        val mid = (lo + hi) * 0.5f
        if (insideUnitPolygon(v, x / mid, y / mid)) hi = mid else lo = mid
    }
    return hi
}

/** 点在多边形内（射线法；顶点表为 `[x0, y0, x1, y1, …]`，任意绕向） */
internal fun insideUnitPolygon(v: FloatArray, px: Float, py: Float): Boolean {
    val n = v.size / 2
    var inside = false
    var j = n - 1
    for (i in 0 until n) {
        val xi = v[i * 2]
        val yi = v[i * 2 + 1]
        val xj = v[j * 2]
        val yj = v[j * 2 + 1]
        if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

/** 网格分辨率：`(GRID + 1)² = 2401` 个采样点。1080p 上间距约 22 px，足够钉住形状边界 */
private const val GRID = 48

/** 二分步数：24 步把区间压到 `边长 / 2²⁴`，远超像素精度 */
private const val BISECT_STEPS = 24

/**
 * 覆盖余量。
 *
 * 网格采样只能保证「**采样点**都在形状内」，紧贴边界的采样点可能比真值小一点
 * （1080p 上约 1%）。余量同时兜住「矩形边界上最坏的那一点恰好落在两个采样点之间」。
 * ⚠️ 多算 5% 的半径只是让形状多长 5%，肉眼不可见；少算就是四角露旧图。
 */
private const val COVER_MARGIN = 1.05f

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

/** 五角星：10 顶点（外径 1 / 内径 0.45 交替），顶点角 = −90° + k·36° */
internal class IrisStarTransition : PolygonIrisTransition() {

    override val id: PhotoTransitionId = PhotoTransitionId.IRIS_STAR

    override val unitVertices: FloatArray = FloatArray(20) { i ->
        // ⛔ 交替的是**顶点**（`k = i / 2`），不是**分量**（`i`）——
        //   顶点 k 的 x 分量写在 `i = 2k`、y 分量写在 `i = 2k + 1`，两者必须用**同一个半径**。
        //   曾经写成 `val outer = i % 2 == 0`：x 分量拿外径、y 分量拿内径 ⇒ 10 个顶点退化成
        //   「5 个顶点、y 被压到 0.45 倍」的**扁五边形**，既不是星形，`INNER_RATIO` 也形同虚设
        //   （2026-09-24 上机复验连带发现，见 `docs/technical-overview.md` §10.182）。
        val k = i / 2
        val outer = k % 2 == 0
        val ang = -PI.toFloat() / 2f + k * PI.toFloat() * 2f / 10f
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

    /**
     * ⛔ 必须**把 `prepare` 转发给四个形状**：`PhotoRenderer` 只会对注册表里那一个实例
     * （= 本对象）调 `prepare`，而真正绘制的是 `current` —— 不转发的话 `current.coverRadius`
     * 永远是 0，`render` 会在第一行就返回 ⇒ **这个转场什么都不显示**。
     */
    override fun prepare(geom: PhotoGeometry, quality: VisualQuality, masks: MaskCache) {
        diamond.prepare(geom, quality, masks)
        hexagon.prepare(geom, quality, masks)
        star.prepare(geom, quality, masks)
        square.prepare(geom, quality, masks)
    }

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

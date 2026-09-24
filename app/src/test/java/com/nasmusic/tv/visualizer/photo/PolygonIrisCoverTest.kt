package com.nasmusic.tv.visualizer.photo

import com.nasmusic.tv.visualizer.photo.transitions.IrisDiamondTransition
import com.nasmusic.tv.visualizer.photo.transitions.IrisHexagonTransition
import com.nasmusic.tv.visualizer.photo.transitions.IrisStarTransition
import com.nasmusic.tv.visualizer.photo.transitions.polygonCoverRadius
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * **门禁 G21**（§14.4）—— 多边形光圈的终态必须盖住整个画布。
 *
 * ## 判据
 *
 * `p = 1` 时 `r` 倍单位多边形必须**包含整个画布矩形** —— 因为 `p` 到 1 之后
 * 整个 HOLD 期都是 1，此时若形状盖不满，露出来的就是**上一张照片**（四角 / 凹口处）。
 *
 * ## 为什么不能沿用 `geom.diagonalHalf`
 *
 * `diagonalHalf` 是**圆**的终态半径：圆心到画布四角的距离恰好等于对角线的一半，
 * 所以圆在 `p = 1` 时正好盖满。多边形的边界比同半径的圆更靠内：
 *
 * | 形状 | `p = 1` 时盖满所需半径（1920×1080） |
 * |---|---|
 * | 圆 | 1101.7（= 对角线/2） |
 * | 正方形 | 960 |
 * | 六边形 | ≈ 1102 |
 * | 菱形 | 1500（= (宽 + 高) / 2） |
 * | 五角星 | ≈ 2240（内径只有 0.45 倍 ⇒ 凹口方向需要更大） |
 *
 * ⇒ 菱形 / 五角星（以及 `SHAPE_RANDOM` 抽到它们时）用 `diagonalHalf` 会**露旧图**
 * （2026-09-24 上机复验，见 `docs/technical-overview.md` §10.182）。
 *
 * ## 自证
 *
 * 1. **负向自证**：用 `diagonalHalf` 时菱形 / 五角星必须被判「盖不住」——
 *    证明断言真的有判别力（否则「新半径通过」可能只是因为断言太松）。
 * 2. **独立算法**：覆盖判定用**角度累加**（winding）而不是生产代码的射线法 ——
 *    否则谓词本身写错时测不出来。
 * 3. **上界**：半径不得离谱（否则「无限大」也能通过「盖住」这条判据）。
 */
class PolygonIrisCoverTest {

    // ─────────────────────────── 覆盖判据 ───────────────────────────

    @Test
    fun `多边形光圈终态必须盖住整个画布`() {
        for ((name, unit) in shapes()) {
            for ((w, h) in canvases()) {
                val r = polygonCoverRadius(unit, w, h)
                assertTrue("$name 在 ${w.toInt()}×${h.toInt()} 上半径非法：$r", r > 0f && r.isFinite())
                assertTrue(
                    "$name 在 ${w.toInt()}×${h.toInt()} 上算出的半径 $r 盖不住画布 —— " +
                        "p = 1 之后 HOLD 期会露出旧图",
                    covers(unit, w, h, r),
                )
                assertTrue(
                    "$name 在 ${w.toInt()}×${h.toInt()} 上的半径 $r 离谱地大（上界 ${4f * (w + h)}）—— " +
                        "可能是二分未收敛 / 顶点表退化",
                    r <= 4f * (w + h),
                )
            }
        }
    }

    /**
     * **负向自证**：`covers(...)` 有判别力，且 `geom.diagonalHalf` 对菱形 / 五角星是**不够的**。
     *
     * 两件事：① `covers` 不是恒真（半径小到 1 px 时必须判「盖不住」）；
     * ② 旧写法确实有缺陷 —— 不是「把 `diagonalHalf` 换了个等价写法」。
     *
     * ⚠️ **刻意不拿圆做前置条件**：`IrisCircleTransition` 用的是 64 边形近似，
     * 其**边**的内切半径是 `cos(π/64) = 0.9988` 倍 ⇒ 用 `diagonalHalf` 时四角会差 0.3 px
     * （肉眼不可见，且 §14.3 明确指定了圆用对角线/2）⇒ 拿它做「刚好够」的前提反而会误判。
     */
    @Test
    fun `负向自证 - 半径太小时盖不住 且对角线一半对菱形五角星不够`() {
        val w = 1920f
        val h = 1080f
        val diagonalHalf = hypot(w, h) * 0.5f

        // ① 判别力：半径 1 px 时任何形状都盖不住画布（`covers` 不是恒真）
        for ((name, unit) in shapes()) {
            assertTrue("前置条件：$name 在 r = 1px 时不应被判为覆盖", !covers(unit, w, h, 1f))
        }

        // ② 旧写法：菱形（需 1500）/ 五角星（需 ≈2240）用对角线/2（1101.45）都不够
        for (name in listOf("菱形", "五角星")) {
            val unit = shapes().first { it.first == name }.second
            assertTrue(
                "前置条件：$name 用对角线/2 应盖不住（本门禁的存在理由）",
                !covers(unit, w, h, diagonalHalf),
            )
        }
    }

    /** 新半径必须**明显大于**旧半径才会「刚好盖满」—— 顺带钉住「不是随手乘个大系数」 */
    @Test
    fun `菱形与五角星的终态半径必须大于对角线一半`() {
        val w = 1920f
        val h = 1080f
        val diagonalHalf = hypot(w, h) * 0.5f
        for (name in listOf("菱形", "五角星")) {
            val unit = shapes().first { it.first == name }.second
            val r = polygonCoverRadius(unit, w, h)
            assertTrue("$name 的终态半径 $r 应大于对角线/2 $diagonalHalf", r > diagonalHalf)
            // 也不该大得离谱：五角星的解析下界 ≈ |角方向上的最坏点| / 0.45 ≈ 2240
            assertTrue("$name 的终态半径 $r 过大（上界 ${2.5f * diagonalHalf}）", r < 2.5f * diagonalHalf)
        }
    }

    /** 退化为非法输入时必须返回 `0f`（调用方据此跳过绘制，而不是抛异常 / 拿到 NaN） */
    @Test
    fun `非法输入返回 0`() {
        val unit = IrisDiamondTransition().unitVertices
        for ((w, h) in listOf(0f to 1080f, 1920f to 0f, -1f to 1080f)) {
            assertTrue("画布 $w×$h 应返回 0f", polygonCoverRadius(unit, w, h) == 0f)
        }
        assertTrue("顶点表不足 3 个应返回 0f", polygonCoverRadius(floatArrayOf(0f, 0f, 1f, 1f), 1920f, 1080f) == 0f)
    }

    // ─────────────────────────── 独立实现 ───────────────────────────

    /** 生产形状的单位顶点表（`SHAPE_RANDOM` 池里的方形是 private inner ⇒ 这里复写同一张表） */
    private fun shapes(): List<Pair<String, FloatArray>> = listOf(
        "菱形" to IrisDiamondTransition().unitVertices,
        "六边形" to IrisHexagonTransition().unitVertices,
        "五角星" to IrisStarTransition().unitVertices,
        "正方形" to floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f),
    )

    private fun canvases(): List<Pair<Float, Float>> = listOf(
        1920f to 1080f,
        1080f to 1920f,
        1280f to 720f,
    )

    /**
     * `r` 倍单位多边形是否**包含整个画布矩形**（含边界）。
     *
     * ⛔ 采样比生产代码**更密**（61×61 vs 49×49），否则就是「用生产代码验证生产代码」。
     * ⛔ 内点判定用**角度累加**，与生产代码的射线法是两套算法。
     */
    private fun covers(unit: FloatArray, canvasW: Float, canvasH: Float, r: Float): Boolean {
        if (r <= 0f || !r.isFinite()) return false
        val scaled = FloatArray(unit.size) { i -> unit[i] * r }
        val hw = canvasW * 0.5f
        val hh = canvasH * 0.5f
        for (gy in 0..SAMPLES) {
            val y = -hh + canvasH * gy / SAMPLES
            for (gx in 0..SAMPLES) {
                val x = -hw + canvasW * gx / SAMPLES
                if (!insideByAngleSum(scaled, x, y)) return false
            }
        }
        return true
    }

    /** 点在多边形内（**角度累加**法；与 `insideUnitPolygon` 的射线法是两套算法） */
    private fun insideByAngleSum(v: FloatArray, px: Float, py: Float): Boolean {
        val n = v.size / 2
        var sum = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = v[i * 2] - px
            val ay = v[i * 2 + 1] - py
            val bx = v[j * 2] - px
            val by = v[j * 2 + 1] - py
            sum += atan2((ax * by - ay * bx).toDouble(), (ax * bx + ay * by).toDouble())
        }
        return abs(sum) > PI
    }

    private companion object {
        /** 采样密度：`(SAMPLES + 1)² = 3721` 个点（比生产代码的 49×49 更密） */
        const val SAMPLES = 60
    }
}

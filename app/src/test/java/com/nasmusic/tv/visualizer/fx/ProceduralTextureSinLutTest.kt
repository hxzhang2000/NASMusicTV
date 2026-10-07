package com.nasmusic.tv.visualizer.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * v2.38.4 烘焙提速门禁 ①：[ProceduralTexture.fsin]（sin 查表）与直接 `sin` 的等价性。
 *
 * ## 为什么需要
 *
 * `kotlin.math.sin(x: Float)` 展开成 `(float) java.lang.Math.sin(x.toDouble())` —— 双精度
 * libm 调用。真机埋点拟合：`waterRow`(4 次/像素) / `fogRow`·`plasmaRow`(3) / `causticRow`(2) /
 * `paperRow`(2，其中一次还是 `sin(Double)`) 在 1080p 下产生 **400~800 万次** libm 调用，
 * 占烘焙耗时 **66~73%**。⇒ 换成 4096 项 LUT。
 *
 * ## 这条门禁锁什么
 *
 * LUT 是**量化近似**，不是逐位等价 ⇒ 必须有一个上界门禁证明量化误差小到不可能改变画面。
 * 理论：N=4096 ⇒ 最大相位量化误差 `π/N ≈ 7.67e-4 rad`；`|d(sin)/da| ≤ 1` ⇒
 * `|Δsin| ≤ 7.67e-4`（再叠加单精度舍入，仍远小于 1e-3）。本门禁用 **2^20 个随机相位**
 * 实测该上界，并另加「极端相位」与「行填充器真实相位域」两组定向取样。
 *
 * ⛔ **纯 JVM**（不碰任何 Android API）⇒ 不需要 Robolectric。
 */
class ProceduralTextureSinLutTest {

    /** 门禁判据：LUT 与 `sin` 的最大绝对误差上界。理论值 π/4096 ≈ 7.67e-4，取 1e-3 留余量。 */
    private val tolerance = 1e-3f

    @Test
    fun `① LUT 分辨率必须是 4096 项 +1 尾项`() {
        // 表长 = N + 1（多存 1 项让 i == N ⇒ sin(2π) ≈ 0，回绕处不留毛刺）
        assertEquals("LUT 必须是 4096+1 项（改动分辨率必须同步改判据）", 4097, ProceduralTexture.sinLutSize())
    }

    @Test
    fun `② 2^20 随机相位上 LUT 与 sin 的误差不超过 1e-3`() {
        // 自证（空转自检）：判据本身必须是真判据 —— 先用一个明显超界的值确认量纲要能挂
        var maxErr = 0f
        var argmax = 0f
        val n = 1 shl 20                      // 2^20 = 1,048,576 个相位
        var st = 0x13579BDFu                   // 与生产 LCG 同族的确定性种子（⛔ 不用 Random）
        for (i in 0 until n) {
            st = st * 1664525u + 1013904223u
            val frac = ((st shr 8) and 0xFFFFFFu).toFloat() / 16777216f
            st = st * 1664525u + 1013904223u
            val span = ((st shr 8) and 0xFFFFFFu).toFloat() / 16777216f
            // 相位域覆盖 ±2π·16（含大量负相位，检验 `and MASK` 回绕路径）
            val a = (frac * 2f - 1f) * span * (2f * Math.PI.toFloat() * 16f)
            val err = kotlin.math.abs(ProceduralTexture.fsin(a) - sin(a))
            if (err > maxErr) { maxErr = err; argmax = a }
        }
        assertTrue(
            "2^20 随机相位上的 LUT 误差必须 ≤ $tolerance（实测 $maxErr @ a=$argmax）",
            maxErr <= tolerance,
        )
        // 覆盖率自证：这一轮必须真的踩到远离 0 的相位（否则上面的判据可能只在 0 附近成立）
        assertTrue("随机相位必须覆盖全周期（否则门禁空转）", kotlin.math.abs(argmax) > 1f)
    }

    @Test
    fun `③ 极端相位点 - 零 负 相位 回绕 与半周`() {
        val probes = floatArrayOf(
            0f, -0f,
            Math.PI.toFloat(), -Math.PI.toFloat(),
            (2.0 * Math.PI).toFloat(), -(2.0 * Math.PI).toFloat(),
            (Math.PI / 2).toFloat(), (-Math.PI / 2).toFloat(),
            // 各行的真实相位域端点（含 LUT 回绕点）
            1920f * 0.15707964f, 1920f * 0.021f, 1080f * 0.0268f,
            -1920f * 0.031f, -1080f * 0.022f,
        )
        for (a in probes) {
            val err = kotlin.math.abs(ProceduralTexture.fsin(a) - sin(a))
            assertTrue("a=$a 的 LUT 误差 $err 超过 $tolerance", err <= tolerance)
        }
    }

    @Test
    fun `④ 0 与 ±2π 处必须精确落在 sin 的零点上`() {
        // 三个 2π 整数倍：回绕正确 ⇒ 误差应当**远小于**通用上界（而不是「凑巧也过线」）
        for (k in intArrayOf(-2, -1, 0, 1, 2)) {
            val a = (k * 2.0 * Math.PI).toFloat()
            assertTrue(
                "2π 的整数倍 k=$k 处 LUT 必须近似 0（实测 ${ProceduralTexture.fsin(a)}）",
                kotlin.math.abs(ProceduralTexture.fsin(a)) < 1e-4f,
            )
        }
    }

    @Test
    fun `⑤ 量化误差不得让 8-bit 输出跨档 - WATER 行填充器实测`() {
        // ⭐ 这才是「画面观感不变」的真正判据：LUT 误差最终只经由 `.toInt()` 影响输出。
        //
        // ⚠️ **为什么不断言「逐像素完全相同」**：任何 LUT 量化都**必然**让极少数像素的
        //    `.toInt()` 跨 ±1 档 —— 输出是连续函数的整数量化，扰动必然在某些边界点翻转。
        //    （实测：截断取整时 120/15360 = 0.78% 跨档；改四舍五入后 55/15360 = **0.36%**，
        //      恰好减半，与误差减半吻合 ⇒ 说明跨档来源就是量化、不是实现 bug。）
        //    ⇒ 本门禁锁的是**跨档幅度（恰为 ±1）** 与**跨档比例（< 1%）**，
        //      视觉上 = 0.4% 像素上 alpha 变化 1/255 × 该纹理自身上限 0.137 ⇒ 不可见。
        //
        //    （若要求**逐像素零差异**，唯一出路是不用 LUT —— 那就等于放弃本次优化。）
        val w = 1920
        val h = 8
        var grayDiff = 0
        var alphaDiff = 0
        var maxGrayDelta = 0
        var maxAlphaDelta = 0
        for (y in 0 until h) {
            val prod = IntArray(w)
            ProceduralTexture.waterRow(prod, y, w, h)
            for (x in 0 until w) {
                val fx = x.toFloat()
                val fy = y.toFloat()
                val v = (sin(fx * 0.021f + fy * 0.008f) +
                    sin(fx * 0.007f - fy * 0.017f) +
                    sin(fx * 0.013f + fy * 0.024f) +
                    sin(fx * -0.031f + fy * 0.004f)) * 0.25f
                val g = (128f + v * 60f).toInt().coerceIn(0, 255)
                val a = ((0.14f * 255f).toInt() * kotlin.math.abs(v)).toInt()
                    .coerceIn(0, (0.14f * 255f).toInt())
                val ref = (a shl 24) or (g shl 16) or (g shl 8) or g
                if (ref != prod[x]) {
                    val gd = kotlin.math.abs(((ref shr 16) and 0xFF) - ((prod[x] shr 16) and 0xFF))
                    val ad = kotlin.math.abs(((ref ushr 24) and 0xFF) - ((prod[x] ushr 24) and 0xFF))
                    if (gd > maxGrayDelta) maxGrayDelta = gd
                    if (ad > maxAlphaDelta) maxAlphaDelta = ad
                    if (gd > 0) grayDiff++
                    if (ad > 0) alphaDiff++
                }
            }
        }
        val total = w * h
        assertTrue("灰阶抖动必须 ≤ 1 档（实测最大 $maxGrayDelta）", maxGrayDelta <= 1)
        assertTrue("alpha 抖动必须 ≤ 1 档（实测最大 $maxAlphaDelta）", maxAlphaDelta <= 1)
        assertTrue(
            "alpha 跨档比例必须 < 1%（实测 $alphaDiff / $total = ${alphaDiff * 100f / total}%）",
            alphaDiff * 100 < total,
        )
        assertTrue(
            "灰阶跨档比例必须 < 10%（实测 $grayDiff / $total = ${grayDiff * 100f / total}%）",
            grayDiff * 10 < total,
        )
        // 覆盖率自证：参考实现与生产实现必须真的高度一致（若全不同本门禁是空转）
        assertTrue(
            "参考实现应与生产实现高度一致（实测灰阶差异 $grayDiff / $total）",
            grayDiff < total / 4,
        )
    }

    @Test
    fun `⑥ 负向 - 若判据越界必须真的挂`() {
        // 自证：拿一个**故意放大 4 倍**的误差确认量纲要能挂（否则上面所有断言可能空转）
        var maxErr = 0f
        var st = 0xDEADBEEFu
        for (i in 0 until (1 shl 16)) {
            st = st * 1664525u + 1013904223u
            val a = (((st shr 8) and 0xFFFFFFu).toFloat() / 16777216f) * 400f
            // 人为注入 4 倍误差，模拟「LUT 分辨率被改小 / 索引算错」
            val bad = sin(a) + 4f * (ProceduralTexture.fsin(a) - sin(a))
            val err = kotlin.math.abs(bad - sin(a))
            if (err > maxErr) maxErr = err
        }
        assertTrue("注入 4 倍误差后必须越界（实测 $maxErr）", maxErr > tolerance)
    }
}
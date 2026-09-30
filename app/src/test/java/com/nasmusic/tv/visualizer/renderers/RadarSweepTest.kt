package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * §八 G9 — E30 真实 PPI 门禁：扫掠穿越判定 + 目标池行为（§A9 第 0 条 · P0）。
 *
 * **正向**：直接盯住 [RadarGridRenderer] 的 `internal` 纯函数
 * （[RadarGridRenderer.crossed] / [RadarGridRenderer.stepTargets] / [RadarGridRenderer.activeCount]）。
 * 仿真逻辑之所以抽成纯函数，就是为了能在 JVM 上这样测（不需要 `DrawScope`）。
 *
 * **负向自证**：把「旧写法 / 破坏实现」喂进**与正向相同**的判据必须判失败 ——
 * 否则门禁是空转（§八 开头规矩）。
 *
 * ⚠️ 随机数一律用**可复现**的 `java.util.Random(seed)` 闭包；对照实验里两个池
 * **必须用同 seed 的两个独立 Random**（同一个闭包会被前一个池消耗掉序列，导致
 * 「初值本来就不同」⇒ 对照实验会**因为错误的原因通过**）。
 */
class RadarSweepTest {

    private val SPECTRUM = FloatArray(64) { 0.3f }
    private val DT = 1f / 30f

    /** 可复现随机序列：同 seed 的两个独立实例给出**逐位相同**的序列 */
    private fun rngSeq(seed: Long): () -> Float {
        val r = Random(seed)
        return { r.nextFloat() }
    }

    /**
     * 建池并推进 [frames] 帧。`freezeDrift = true` 时把两个漂移速率清零
     * ⇒ 作为「不漂移」的对照池（⚠️ 必须在 `initPool` **之后**清零，
     * 且帧数要 < 最小寿命 6 s，否则重生会把漂移速率重新随机出来）。
     */
    private fun runPool(
        frames: Int,
        seed: Long,
        energy: Float,
        holdSec: Float,
        freezeDrift: Boolean = false,
    ): FloatArray {
        val rnd = rngSeq(seed)
        val pool = FloatArray(RadarGridRenderer.TARGET_N * RadarGridRenderer.STRIDE)
        RadarGridRenderer.initPool(pool, rnd, SPECTRUM.size)
        if (freezeDrift) {
            var o = 0
            while (o < pool.size) {
                pool[o + RadarGridRenderer.DRIFT_B] = 0f
                pool[o + RadarGridRenderer.DRIFT_R] = 0f
                o += RadarGridRenderer.STRIDE
            }
        }
        var cur = 0f
        repeat(frames) {
            val prev = cur
            cur = RadarGridRenderer.wrapTau(cur + RadarGridRenderer.SWEEP_SPEED * DT)
            RadarGridRenderer.stepTargets(pool, prev, cur, DT, holdSec, energy, SPECTRUM, rnd)
        }
        return pool
    }

    // ═══════════════════ ① 扫掠穿越判定 ═══════════════════

    @Test
    fun `① 基本区间判定 - 左开右闭`() {
        assertTrue(RadarGridRenderer.crossed(0f, 0.5f, 0.25f))
        assertFalse(RadarGridRenderer.crossed(0f, 0.5f, 0.75f))
        // 左开右闭：prev 不命中、cur 命中（同一圈不重复点亮、也不漏点）
        assertFalse("target == prev 不应命中", RadarGridRenderer.crossed(1f, 2f, 1f))
        assertTrue("target == cur 应命中", RadarGridRenderer.crossed(1f, 2f, 2f))
    }

    @Test
    fun `② 跨 0 回绕`() {
        // 扫线从 6.0 扫到 0.3（跨过 2π）：区间 = (6.0, 2π] ∪ (0, 0.3]
        assertTrue("6.2 在 (6.0, 2π] 内", RadarGridRenderer.crossed(6.0f, 0.3f, 6.2f))
        assertTrue("0.1 在 (0, 0.3] 内", RadarGridRenderer.crossed(6.0f, 0.3f, 0.1f))
        assertFalse("3.0 不在区间内", RadarGridRenderer.crossed(6.0f, 0.3f, 3.0f))
    }

    @Test
    fun `③ 一帧扫满一圈时全部命中 - 兜底分支`() {
        val span = RadarGridRenderer.TAU + 0.1f
        for (t in floatArrayOf(0f, 1f, 3f, 5f, 6.2f)) {
            assertTrue("span >= TAU 时 target=$t 必须命中", RadarGridRenderer.crossed(0f, span, t))
        }
    }

    @Test
    fun `④ 归一化 wrapTau`() {
        assertEquals(0f, RadarGridRenderer.wrapTau(0f), 1e-6f)
        assertEquals(1f, RadarGridRenderer.wrapTau(RadarGridRenderer.TAU + 1f), 1e-6f)
        assertEquals(
            RadarGridRenderer.TAU - 1f,
            RadarGridRenderer.wrapTau(-1f), 1e-6f
        )
    }

    // ═══════════════════ ② 扫速与目标数 ═══════════════════

    @Test
    fun `⑤ 扫线匀速 - 一圈 3_9 秒左右`() {
        val period = RadarGridRenderer.TAU / RadarGridRenderer.SWEEP_SPEED
        assertEquals("一圈耗时应在 3.9 ± 0.4 s（真实雷达天线的机械感）", 3.93f, period, 0.4f)
    }

    @Test
    fun `⑥ 活动目标数随能量 8 到 24 且封顶`() {
        assertEquals(8, RadarGridRenderer.activeCount(0f))
        assertEquals(24, RadarGridRenderer.activeCount(1f))
        assertEquals("超范围能量必须封顶在 TARGET_N", 24, RadarGridRenderer.activeCount(5f))
        assertEquals("负能量必须封底在 8", 8, RadarGridRenderer.activeCount(-1f))
    }

    // ═══════════════════ ③ 目标池行为 ═══════════════════

    @Test
    fun `⑦ 目标连续漂移 - 下一圈换位置`() {
        val holdSec = RadarGridRenderer.TAU / RadarGridRenderer.SWEEP_SPEED * 1.15f
        // 三个池都用**同 seed 的独立 Random** ⇒ 初值逐位相同
        val initial = runPool(0, seed = 42L, energy = 0.5f, holdSec = holdSec)   // 0 帧 = 只 initPool
        val drifted = runPool(100, seed = 42L, energy = 0.5f, holdSec = holdSec)
        val frozen = runPool(100, seed = 42L, energy = 0.5f, holdSec = holdSec, freezeDrift = true)

        val active = RadarGridRenderer.activeCount(0.5f)
        var driftedCount = 0
        for (t in 0 until active) {
            val o = t * RadarGridRenderer.STRIDE
            // ⛔ 前提自证：对照池的方位必须**逐位等于初值** —— 否则「漂移池与它不同」
            //    可能是因为初值本来就不同（对照实验会因为错误的原因通过）
            assertEquals(
                "对照池 BEARING 必须等于初值（证明 freezeDrift 真的生效）",
                initial[o + RadarGridRenderer.BEARING],
                frozen[o + RadarGridRenderer.BEARING], 0f
            )
            if (abs(drifted[o + RadarGridRenderer.BEARING] - initial[o + RadarGridRenderer.BEARING]) > 1e-4f) {
                driftedCount++
            }
        }
        assertTrue(
            "100 帧（3.3 s）后必须有目标发生方位漂移（实测 $driftedCount / $active）",
            driftedCount > 0
        )
    }

    @Test
    fun `⑧ 距离始终有界 - 漂移不越界`() {
        val holdSec = RadarGridRenderer.TAU / RadarGridRenderer.SWEEP_SPEED * 1.15f
        // 跑 900 帧（30 s）⇒ 远超过单个目标的最小寿命 6 s，必然经历多次重生
        val pool = runPool(900, seed = 7L, energy = 1f, holdSec = holdSec)
        for (t in 0 until RadarGridRenderer.TARGET_N) {
            val o = t * RadarGridRenderer.STRIDE
            val range = pool[o + RadarGridRenderer.RANGE]
            val bearing = pool[o + RadarGridRenderer.BEARING]
            assertTrue("RANGE $range 越界 [0.25, 1]", range >= RadarGridRenderer.RANGE_MIN - 1e-5f && range <= 1f + 1e-5f)
            assertTrue("BEARING $bearing 越界 [0, TAU]", bearing >= 0f && bearing <= RadarGridRenderer.TAU + 1e-5f)
            val bin = pool[o + RadarGridRenderer.BIN].toInt()
            assertTrue("BIN $bin 越界", bin in SPECTRUM.indices)
        }
    }

    @Test
    fun `⑨ 磷光余辉撑到下一圈 - holdSec 大于周期时 BRIGHT 不归零`() {
        val period = RadarGridRenderer.TAU / RadarGridRenderer.SWEEP_SPEED
        val holdSec = period * 1.15f
        val rnd = rngSeq(100L)
        val pool = FloatArray(RadarGridRenderer.TARGET_N * RadarGridRenderer.STRIDE)
        RadarGridRenderer.initPool(pool, rnd, SPECTRUM.size)
        val active = RadarGridRenderer.activeCount(0.5f)
        // 全部点亮，并把方位角挪到 1.0；扫线**停在 3.0 不动** ⇒ 永不穿越
        for (t in 0 until active) {
            val o = t * RadarGridRenderer.STRIDE
            pool[o + RadarGridRenderer.BRIGHT] = 1f
            pool[o + RadarGridRenderer.BEARING] = 1.0f
        }
        val stuck = 3.0f
        repeat(100) {
            RadarGridRenderer.stepTargets(pool, stuck, stuck, DT, holdSec, 0.5f, SPECTRUM, rnd)
        }
        // 100 帧 = 3.33 s < holdSec 4.52 s ⇒ 余辉应仍有约 0.26
        for (t in 0 until active) {
            val b = pool[t * RadarGridRenderer.STRIDE + RadarGridRenderer.BRIGHT]
            assertTrue("holdSec($holdSec s) > 周期($period s) 时 BRIGHT 必须仍 > 0，实测 $b", b > 0f)
        }
    }

    @Test
    fun `⑩ 寿命到必须重生 - 池不会空`() {
        val rnd = rngSeq(7L)
        val pool = FloatArray(RadarGridRenderer.TARGET_N * RadarGridRenderer.STRIDE)
        RadarGridRenderer.initPool(pool, rnd, SPECTRUM.size)
        // 把所有槽的寿命拨到极小 ⇒ 下一步必然触发重生
        var o = 0
        while (o < pool.size) {
            pool[o + RadarGridRenderer.LIFE] = 1e-3f
            o += RadarGridRenderer.STRIDE
        }
        RadarGridRenderer.stepTargets(pool, 0f, 0.2f, 0.1f, 4.5f, 1f, SPECTRUM, rnd)
        val active = RadarGridRenderer.activeCount(1f)
        for (t in 0 until active) {
            val idx = t * RadarGridRenderer.STRIDE
            assertTrue(
                "重生后 LIFE 必须 > 0（不是置零消失），实测 ${pool[idx + RadarGridRenderer.LIFE]}",
                pool[idx + RadarGridRenderer.LIFE] > 0f
            )
            assertTrue(pool[idx + RadarGridRenderer.RANGE] >= RadarGridRenderer.RANGE_MIN - 1e-5f)
            assertTrue(pool[idx + RadarGridRenderer.RANGE] <= 1f + 1e-5f)
            assertTrue(pool[idx + RadarGridRenderer.BIN].toInt() in SPECTRUM.indices)
        }
    }

    @Test
    fun `⑪ 穿越点亮时 PEAK 取被扫桶的频谱值`() {
        val spectrum = FloatArray(64) { it / 63f }   // 桶号越大值越大
        val rnd = rngSeq(3L)
        val pool = FloatArray(RadarGridRenderer.TARGET_N * RadarGridRenderer.STRIDE)
        RadarGridRenderer.initPool(pool, rnd, spectrum.size)
        // 让扫线一帧扫满整圈 ⇒ 全部命中
        RadarGridRenderer.stepTargets(
            pool, 0f, RadarGridRenderer.TAU + 0.1f, DT, 4.5f, 1f, spectrum, rnd
        )
        val active = RadarGridRenderer.activeCount(1f)
        for (t in 0 until active) {
            val idx = t * RadarGridRenderer.STRIDE
            val bin = pool[idx + RadarGridRenderer.BIN].toInt().coerceIn(0, spectrum.size - 1)
            assertEquals("BRIGHT 必须被点亮", 1f, pool[idx + RadarGridRenderer.BRIGHT], 1e-6f)
            assertEquals(
                "PEAK 必须等于 spectrum[BIN]",
                spectrum[bin], pool[idx + RadarGridRenderer.PEAK], 1e-6f
            )
        }
    }

    // ═══════════════════ 负向自证 ═══════════════════

    /** 负向①：旧写法 `abs(cur − target) < eps` ⇒ 跨 0 回绕漏检 + 区间外误报 */
    @Test
    fun `负向① 旧的距离式穿越判定必须被判失败`() {
        val oldCrossed = { _: Float, cur: Float, target: Float -> abs(cur - target) < 0.1f }
        assertFalse(
            "旧写法必须漏检 crossed(6.0, 0.3, 6.2)（证明门禁真的在抓回绕）",
            oldCrossed(6.0f, 0.3f, 6.2f)
        )
        assertTrue(
            "旧写法对区间外的 0.55 必须误报（证明旧写法确实不对）",
            oldCrossed(0f, 0.5f, 0.55f)
        )
        // 同两个用例喂进生产实现：结果与旧写法**相反**
        assertTrue(RadarGridRenderer.crossed(6.0f, 0.3f, 6.2f))
        assertFalse(RadarGridRenderer.crossed(0f, 0.5f, 0.55f))
    }

    /** 负向②：去掉 RANGE 的 bounce ⇒ 距离必然漂出界 */
    @Test
    fun `负向② 去掉 bounce 后距离必然越界`() {
        var r = 0.9f
        repeat(300) { r += 0.02f / 30f }     // 无 bounce 的裸漂移
        assertTrue("无 bounce 时 RANGE 必然 > 1（证明 bounce 在起作用）", r > 1f)
        // 生产实现里同一路径（30 s、含重生）不会越界 —— 见 ⑧
    }

    /** 负向③：holdSec 远小于周期 ⇒ 余辉撑不到下一圈 */
    @Test
    fun `负向③ 过短的余辉必须让 BRIGHT 归零`() {
        val period = RadarGridRenderer.TAU / RadarGridRenderer.SWEEP_SPEED
        val holdSec = period * 0.1f              // 只有周期的 1/10
        val rnd = rngSeq(5L)
        val pool = FloatArray(RadarGridRenderer.TARGET_N * RadarGridRenderer.STRIDE)
        RadarGridRenderer.initPool(pool, rnd, SPECTRUM.size)
        var o = 0
        while (o < pool.size) {
            pool[o + RadarGridRenderer.BRIGHT] = 1f
            pool[o + RadarGridRenderer.BEARING] = 1.0f
            o += RadarGridRenderer.STRIDE
        }
        val stuck = 3.0f
        repeat(100) {
            RadarGridRenderer.stepTargets(pool, stuck, stuck, DT, holdSec, 0.5f, SPECTRUM, rnd)
        }
        val active = RadarGridRenderer.activeCount(0.5f)
        val allDead = (0 until active).all {
            pool[it * RadarGridRenderer.STRIDE + RadarGridRenderer.BRIGHT] <= 0f
        }
        assertTrue("短 holdSec 必须让余辉全部消失（证明衰减在起作用）", allDead)
    }

    /**
     * 负向④：把「重生」改成「置零」⇒ 池耗尽（E19 踩过的坑）。
     *
     * ⚠️ 必须让破坏池**真的把寿命跑完**（900 帧 = 30 s）才可能耗尽；
     * 只减一帧（`LIFE -= 0.1f`）而 LIFE 初值有 6~20 s，池根本不会空 ——
     * 那样写出来的 `allDead` 恒为 false，用例会因为**错误的原因**失败。
     */
    @Test
    fun `负向④ 置零式重生必须让池耗尽`() {
        val period = RadarGridRenderer.TAU / RadarGridRenderer.SWEEP_SPEED
        val holdSec = period * 1.15f
        val size = RadarGridRenderer.TARGET_N * RadarGridRenderer.STRIDE
        val broken = FloatArray(size)
        val fixed = FloatArray(size)
        // 同 seed 同初值（`{ 0.5f }` 是常量序列 ⇒ 两池逐位相同）
        RadarGridRenderer.initPool(broken, { 0.5f }, SPECTRUM.size)
        RadarGridRenderer.initPool(fixed, { 0.5f }, SPECTRUM.size)
        // 把所有槽的寿命拨到极小 ⇒ 下一步必然到期
        var o = 0
        while (o < size) {
            broken[o + RadarGridRenderer.LIFE] = 1e-3f
            fixed[o + RadarGridRenderer.LIFE] = 1e-3f
            o += RadarGridRenderer.STRIDE
        }
        val rnd = rngSeq(13L)
        var cur = 0f
        repeat(900) {
            val prev = cur
            cur = RadarGridRenderer.wrapTau(cur + RadarGridRenderer.SWEEP_SPEED * DT)
            // 破坏实现：只减不重生（到 0 就置零）
            var i = 0
            while (i < size) {
                broken[i + RadarGridRenderer.LIFE] =
                    (broken[i + RadarGridRenderer.LIFE] - DT).coerceAtLeast(0f)
                i += RadarGridRenderer.STRIDE
            }
            RadarGridRenderer.stepTargets(fixed, prev, cur, DT, holdSec, 1f, SPECTRUM, rnd)
        }
        val brokenDead = (0 until RadarGridRenderer.TARGET_N).all {
            broken[it * RadarGridRenderer.STRIDE + RadarGridRenderer.LIFE] <= 0f
        }
        val fixedAlive = (0 until RadarGridRenderer.TARGET_N).all {
            fixed[it * RadarGridRenderer.STRIDE + RadarGridRenderer.LIFE] > 0f
        }
        assertTrue("置零式「重生」必须让全池耗尽（证明重生逻辑在起作用）", brokenDead)
        assertTrue("对照：生产实现同一路径下全池寿命必须始终 > 0", fixedAlive)
    }
}

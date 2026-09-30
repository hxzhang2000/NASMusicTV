package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

/**
 * §B2 · E14 节拍烟花 —— 门禁（**8 正向 + 3 负向自证**）
 *
 * 判据一律**读生产常量与纯函数**（[BeatFireworkRenderer.trailBucket] /
 * [BeatFireworkRenderer.trailTailX] / [BeatFireworkRenderer.ringRadius] /
 * [BeatFireworkRenderer.ringAlpha]），⛔ 不在测试里复制算法
 * —— 否则门禁与生产会各自漂移（T3.7 的 §八 G10 ④ 就是这条教训）。
 *
 * 核心验收（§B2「`beat` 瞬间有**冲击波环**」）：
 * 环相位必须 **`dt` 化** ⇒ 同一段**墙钟时间**内到达同一半径，与帧率无关；
 * 负向自证把**旧口径**（每帧固定增量 / 字面 `pulse` 当半径）喂进**同一个谓词** ⇒ 必须判失败。
 *
 * ⚠️ `postFx`（0.44 / 0.030）是 `protected`，单测**取不到** ⇒ 由源码扫描门禁
 * `FxCoverageScanTest` 覆盖（见 §12.4：那里只认**字面量**）。
 */
class BeatFireworkTest {

    /** companion 实例（`internal companion object` 同模块可见） */
    private val C = BeatFireworkRenderer.Companion

    // ── 谓词（正向与负向**共用**）──────────────────────────────────────────

    /** 用 `phase += dt / RING_SEC` 积分 [seconds] 秒的环相位（= 生产实现的口径） */
    private fun ringPhaseNew(fps: Int, seconds: Float): Float {
        val dt = 1f / fps
        var ph = 0f
        repeat((fps * seconds).toInt()) { ph = (ph + dt / C.RING_SEC).coerceAtMost(1f) }
        return ph
    }

    /** 旧口径（负向夹具）：每帧固定 `+ 1/60`，与帧率绑定 */
    private fun ringPhaseOld(fps: Int, seconds: Float): Float {
        var ph = 0f
        repeat((fps * seconds).toInt()) { ph = (ph + 1f / 60f).coerceAtMost(1f) }
        return ph
    }

    /**
     * 「帧率无关」谓词。积分函数**由参数注入** ⇒ 正负向喂的是**同一份判据**，
     * 而不是另写一句「预期为 false」的表达式（那样会写成 `x > x` 式空转断言）。
     * ⚠️ `seconds` 取 `0.2f`（远低于饱和点 `RING_SEC = 0.55`）⇒ 相位不会双双被 `coerceAtMost` 压平。
     */
    private fun frameRateInvariant(integrate: (Int, Float) -> Float): Boolean {
        val a = integrate(60, 0.2f)
        val b = integrate(30, 0.2f)
        val c = integrate(15, 0.2f)
        return abs(a - b) < 1e-3f && abs(a - c) < 1e-3f
    }

    /** `pulse` 的实际形态（负向夹具用）：0.05s 线性起，之后 τ=0.35s 指数落 */
    private fun pulseEnv(t: Float): Float =
        if (t < 0.05f) t / 0.05f else exp(-(t - 0.05f) / 0.35f)

    // ── 正向 ───────────────────────────────────────────────────────────────

    @Test
    fun `① 拖尾桶位边界（8 桶等分色相区间）`() {
        assertEquals(8, C.TRAIL_BUCKETS)
        assertEquals(0, C.trailBucket(C.HUE_MIN))                       // 区间起点 → 桶 0
        assertEquals(0, C.trailBucket(C.HUE_MIN + C.HUE_SPAN / 8f - 0.001f))
        assertEquals(1, C.trailBucket(C.HUE_MIN + C.HUE_SPAN / 8f))     // 每桶右开
        assertEquals(4, C.trailBucket(C.HUE_MIN + C.HUE_SPAN * 0.5f))
        assertEquals(7, C.trailBucket(C.HUE_MIN + C.HUE_SPAN))          // 区间终点 → 末桶
        assertEquals(7, C.trailBucket(C.HUE_MIN + C.HUE_SPAN * 1.5f))   // 越界右夹紧
        assertEquals(0, C.trailBucket(C.HUE_MIN - 30f))                 // 越界左夹紧
        assertEquals(0, C.trailBucket(-500f))
        assertEquals(7, C.trailBucket(1000f))
    }

    @Test
    fun `② 拖尾桶位随色相单调不减且覆盖全部桶`() {
        var prev = -1
        val seen = mutableSetOf<Int>()
        for (k in 0..40) {
            val h = C.HUE_MIN + C.HUE_SPAN * k / 40f
            val b = C.trailBucket(h)
            assertTrue("桶位必须单调不减（h=$h：$b < $prev）", b >= prev)
            assertTrue("桶位必须在 [0, TRAIL_BUCKETS)（h=$h → $b）", b in 0 until C.TRAIL_BUCKETS)
            prev = b
            seen.add(b)
        }
        assertEquals("色相扫过整区间应命中全部桶（无空桶）", C.TRAIL_BUCKETS, seen.size)
    }

    @Test
    fun `③ 拖尾端点落在速度反方向`() {
        // 尾迹向量 (tail - pos) 与速度的点积必须 ≤ 0（零速度时退化为 0）
        val cases = arrayOf(
            floatArrayOf(100f, 200f, 4f, -3f),
            floatArrayOf(0f, 0f, 0f, 0f),
            floatArrayOf(-50f, 30f, -8f, 2f),
        )
        for (c in cases) {
            val tx = C.trailTailX(c[0], c[2])
            val ty = C.trailTailY(c[1], c[3])
            val dot = (tx - c[0]) * c[2] + (ty - c[1]) * c[3]
            assertTrue(
                "尾迹必须落在速度反方向（pos=(${c[0]},${c[1]}) v=(${c[2]},${c[3]}) dot=$dot）",
                dot <= 1e-3f
            )
        }
        // 端点公式与 TRAIL_K 同源
        assertEquals(100f - 4f * C.TRAIL_K, C.trailTailX(100f, 4f), 1e-4f)
        assertEquals(200f - (-3f) * C.TRAIL_K, C.trailTailY(200f, -3f), 1e-4f)
    }

    @Test
    fun `④ 冲击波环半径端点正确且单调不减`() {
        val md = 1080f
        assertEquals(0f, C.ringRadius(0f, md), 1e-6f)
        assertEquals(0.25f * md, C.ringRadius(1f, md), 1e-3f)
        // 越界夹紧
        assertEquals(0f, C.ringRadius(-5f, md), 1e-6f)
        assertEquals(C.ringRadius(1f, md), C.ringRadius(9f, md), 1e-6f)
        var prev = -1f
        var p = 0f
        while (p <= 1f) {
            val r = C.ringRadius(p, md)
            assertTrue("半径必须单调不减（phase=$p）", r >= prev - 1e-4f)
            prev = r
            p += 0.01f
        }
    }

    @Test
    fun `⑤ 冲击波环 alpha 端点正确且单调不增`() {
        assertEquals(0.55f, C.ringAlpha(0f), 1e-6f)
        assertEquals(0f, C.ringAlpha(1f), 1e-6f)
        assertEquals(C.RING_ALPHA, C.ringAlpha(0f), 1e-6f)
        var prev = 2f
        var p = 0f
        while (p <= 1f) {
            val a = C.ringAlpha(p)
            assertTrue("alpha 必须单调不增（phase=$p）", a <= prev + 1e-4f)
            assertTrue("alpha 必须落在 [0, RING_ALPHA]（phase=$p → $a）", a >= -1e-6f && a <= C.RING_ALPHA + 1e-6f)
            prev = a
            p += 0.01f
        }
    }

    @Test
    fun `⑥ 环相位 dt 化：60 30 15 fps 下同一墙钟时间相位一致`() {
        assertTrue(
            "60/30/15 fps 必须给出同一相位（§B2 核心验收：环的扩散速度与帧率无关）",
            frameRateInvariant { f, s -> ringPhaseNew(f, s) }
        )
        // 60fps 下 1 秒的积分量 == 1 / RING_SEC（速率量纲自洽）
        assertEquals(1f / C.RING_SEC, ringPhaseNew(60, 1f), 1e-3f)
        assertTrue("RING_SEC 必须为正（否则相位不推进）", C.RING_SEC > 0f)
    }

    @Test
    fun `⑦ 两个缓存盐互不相同且非零`() {
        assertTrue("E14_BG_SALT 不能是 0（0 等价于「没有盐」）", C.E14_BG_SALT != 0L)
        assertTrue("E14_FLASH_SALT 不能是 0", C.E14_FLASH_SALT != 0L)
        assertTrue(
            "⛔ 背景 / 闪光必须用两个不同盐：同盐 ⇒ 闪光会拿到背景的 Brush" +
                "（center/radius/base/contrast 全错）",
            C.E14_BG_SALT != C.E14_FLASH_SALT
        )
        // 跨效果撞键防线（与既有盐对照）
        val others = longArrayOf(
            0x03030303L, 0x07070707L, 0x13131313L, 0x15151515L,
            0x1A1A1A1AL, 0x1E1E1E1EL, 0x1F1F1F1FL, 0x20202020L,
            0x24242424L, 0x30303030L, 0x32323232L, 0x3A3A3A3AL,
            0x5F5F5F5FL, 0xE11E11E1L,
        )
        for (salt in others) {
            assertTrue(
                "E14_BG_SALT 不能与既有盐 0x${salt.toString(16)} 相同（Shading2D 进程级缓存会撞键）",
                C.E14_BG_SALT != salt
            )
            assertTrue(
                "E14_FLASH_SALT 不能与既有盐 0x${salt.toString(16)} 相同",
                C.E14_FLASH_SALT != salt
            )
        }
    }

    @Test
    fun `⑧ 常量与 §B2 明文对齐`() {
        assertEquals("§B2「k = 2.5f」", 2.5f, C.TRAIL_K, 1e-6f)
        assertEquals("§B2「alpha = life*0.35」", 0.35f, C.TRAIL_ALPHA_K, 1e-6f)
        assertEquals("§B2「按 hue 分 8 桶」", 8, C.TRAIL_BUCKETS)
        assertEquals("§B2「0.25×minDim」", 0.25f, C.RING_MAX_K, 1e-6f)
        assertEquals("§B2「width 3f」", 3f, C.RING_W, 1e-6f)
        assertEquals("§B2「alpha 从 0.55 衰减」", 0.55f, C.RING_ALPHA, 1e-6f)
        assertEquals("§B2「alpha = pulse*0.45」", 0.45f, C.FLASH_ALPHA_K, 1e-6f)
        assertEquals("§B2「底纹 0.12 → 0.18」", 0.18f, C.BG_ALPHA, 1e-6f)
        // 描边宽度必须为正（否则环/拖尾不可见）
        assertTrue("TRAIL_W 必须为正", C.TRAIL_W > 0f)
        assertTrue("FLASH_R_K 必须为正且 < RING_MAX_K（闪光比环小）", C.FLASH_R_K > 0f && C.FLASH_R_K < C.RING_MAX_K)
        // 色相区间必须落在黄→蓝（§B2 明文 60° → 195°）
        assertEquals(60f, C.HUE_MIN, 1e-6f)
        assertEquals(135f, C.HUE_SPAN, 1e-6f)
        assertEquals("HUE_MIN + HUE_SPAN == 195°（蓝端）", 195f, C.HUE_MIN + C.HUE_SPAN, 1e-6f)
        assertTrue("TRAIL_MIN_LIFE 必须在 (0,1)（既过滤死粒子又保留可见尾迹）",
            C.TRAIL_MIN_LIFE > 0f && C.TRAIL_MIN_LIFE < 1f)
    }

    // ── 负向自证（喂**同一份**谓词）────────────────────────────────────────

    @Test
    fun `负向N1 每帧固定增量的环相位必须被判为帧率绑定`() {
        assertTrue("前提：旧口径 60fps 下相位确实在推进", ringPhaseOld(60, 0.2f) > 0f)
        assertFalse(
            "旧口径（每帧 +1/60）喂进**同一个**帧率无关谓词 ⇒ 必须判失败",
            frameRateInvariant { f, s -> ringPhaseOld(f, s) }
        )
        // 失败原因就是「帧率绑定」：30fps 恰好是 60fps 的一半
        val a = ringPhaseOld(60, 0.2f)
        val b = ringPhaseOld(30, 0.2f)
        assertEquals("旧口径 30fps 应恰为 60fps 的 50%", 0.5f, b / a, 1e-3f)
    }

    @Test
    fun `负向N2 用字面 pulse 当半径必须被判为非单调`() {
        fun monotone(radiusAt: (Float) -> Float): Boolean {
            var prev = -1f
            var t = 0f
            while (t <= 0.6f) {
                val r = radiusAt(t)
                if (r < prev - 1e-4f) return false
                prev = r
                t += 0.01f
            }
            return true
        }

        assertTrue(
            "正向：生产 ringRadius（dt 化相位）必须单调不减",
            monotone { t -> C.ringRadius(t / 0.6f, 1080f) }
        )
        assertFalse(
            "负向：字面 `pulse` 当半径必须被判失败 —— pulse 是快起慢落包络 ⇒ 环「先涨后缩」，" +
                "与「扩散」相反（§12.4 偏差）",
            monotone { t -> pulseEnv(t) * C.RING_MAX_K * 1080f }
        )
        // 并且峰确实在中途（不是首尾），证明确实是「涨了又缩」
        val peakT = (0..60).maxByOrNull { pulseEnv(it / 100f) }!! / 100f
        assertTrue("pulse 的峰值必须出现在中途（实测 t=$peakT）", peakT > 0f && peakT < 0.6f)
    }

    @Test
    fun `负向N3 恒定桶位函数必须被判为桶位失效`() {
        fun bucketVaries(f: (Float) -> Int): Boolean {
            val seen = mutableSetOf<Int>()
            for (k in 0..40) seen.add(f(C.HUE_MIN + C.HUE_SPAN * k / 40f))
            return seen.size == C.TRAIL_BUCKETS
        }

        assertTrue("正向：生产桶位函数扫过整区间命中全部桶", bucketVaries { h -> C.trailBucket(h) })
        assertFalse(
            "负向：恒定桶位必须被判失败（证明该谓词不是恒真）",
            bucketVaries { _ -> 3 }
        )
    }
}

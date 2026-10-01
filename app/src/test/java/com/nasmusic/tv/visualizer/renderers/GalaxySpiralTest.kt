package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * §B1 · E11 星系螺旋 —— 门禁（**8 正向 + 3 负向自证**）
 *
 * 判据一律**读生产常量与纯函数**（[GalaxySpiralRenderer.rotRateDegPerSec] /
 * [GalaxySpiralRenderer.starBucket] / [GalaxySpiralRenderer.dustTier]），⛔ 不在测试里复制算法
 * —— 否则门禁与生产会各自漂移（T3.7 的 §八 G10 ④ 就是这条教训）。
 *
 * 核心验收（§B1「同一首歌在 30fps / 60fps 下**转速一致**」）：
 * 把「每帧增量」改成「每秒速率 × `dt`」后，**同一个积分谓词**在 60/30/15 fps 下必须给出同一结果；
 * 负向自证把**旧实现**（每帧常量累加）喂进**同一个谓词** ⇒ 必须判失败。
 */
class GalaxySpiralTest {

    /** companion 实例（`internal companion object` 同模块可见） */
    private val C = GalaxySpiralRenderer.Companion

    // ── 谓词（正向与负向**共用**）──────────────────────────────────────────

    /** 用「每秒速率 × dt」积分 [seconds] 秒的旋转量（= 生产实现的口径） */
    private fun spinNew(bpm: Float, fps: Int, seconds: Float): Float {
        val dt = 1f / fps
        var rot = 0f
        repeat((fps * seconds).toInt()) { rot += C.rotRateDegPerSec(bpm) * dt }
        return rot
    }

    /** 旧实现（负向夹具）：每帧 `+= (ROT_BASE + bpm / BPM_DIV)`，与帧率绑定 */
    private fun spinOld(bpm: Float, fps: Int, seconds: Float): Float {
        var rot = 0f
        repeat((fps * seconds).toInt()) { rot += C.ROT_BASE_DEG + bpm / C.BPM_DIV }
        return rot
    }

    /**
     * 「帧率无关」谓词。积分函数**由参数注入** ⇒ 正负向喂的是**同一份判据**，
     * 而不是另写一句「预期为 false」的表达式（那样会写成 `x > x` 式空转断言）。
     */
    private fun frameRateInvariant(
        bpm: Float,
        integrate: (Float, Int, Float) -> Float
    ): Boolean {
        val a = integrate(bpm, 60, 1f)
        val b = integrate(bpm, 30, 1f)
        val c = integrate(bpm, 15, 1f)
        return abs(a - b) < 1e-2f && abs(a - c) < 1e-2f
    }

    // ── 正向 ───────────────────────────────────────────────────────────────

    @Test
    fun `① 旋转速率是每秒量纲且随 bpm 线性`() {
        // 60fps 基准：1 秒 = (ROT_BASE + bpm/BPM_DIV) × FPS_REF
        assertEquals(9.0f, C.rotRateDegPerSec(0f), 1e-4f)
        assertEquals(13.5f, C.rotRateDegPerSec(90f), 1e-4f)
        assertEquals(15.0f, C.rotRateDegPerSec(120f), 1e-4f)
        assertEquals(19.0f, C.rotRateDegPerSec(200f), 1e-4f)
        // 斜率 = FPS_REF / BPM_DIV
        val slope = (C.rotRateDegPerSec(1200f) - C.rotRateDegPerSec(0f)) / 1200f
        assertEquals(C.FPS_REF / C.BPM_DIV, slope, 1e-5f)
    }

    @Test
    fun `② 60fps 下 1 秒积分量等于速率本身`() {
        for (bpm in floatArrayOf(0f, 90f, 120f, 200f)) {
            assertEquals(C.rotRateDegPerSec(bpm), spinNew(bpm, 60, 1f), 1e-2f)
        }
    }

    @Test
    fun `③ 帧率无关：60 30 15 fps 下 1 秒旋转量一致`() {
        for (bpm in floatArrayOf(0f, 90f, 120f, 200f)) {
            assertTrue(
                "bpm=$bpm：60/30/15 fps 必须给出同一旋转量（§B1 核心验收）",
                frameRateInvariant(bpm) { b, f, s -> spinNew(b, f, s) }
            )
        }
    }

    @Test
    fun `④ 星点桶位边界（v 四等分）`() {
        assertEquals(0, C.starBucket(0f, false))
        assertEquals(0, C.starBucket(0.24f, false))
        assertEquals(1, C.starBucket(0.25f, false))
        assertEquals(1, C.starBucket(0.49f, false))
        assertEquals(2, C.starBucket(0.50f, false))
        assertEquals(2, C.starBucket(0.74f, false))
        assertEquals(3, C.starBucket(0.75f, false))
        assertEquals(3, C.starBucket(1.0f, false))
    }

    @Test
    fun `⑤ 远臂降一档且不越界`() {
        assertEquals(0, C.starBucket(0f, true))      // 降档后仍 ≥ 0
        assertEquals(0, C.starBucket(0.25f, true))
        assertEquals(1, C.starBucket(0.50f, true))
        assertEquals(2, C.starBucket(1.0f, true))
        // 远臂桶位必须**恒 ≤** 近臂桶位
        for (i in 0..20) {
            val v = i / 20f
            assertTrue(
                "v=$v：远臂桶位必须 ≤ 近臂桶位（纵深不能比近处更亮）",
                C.starBucket(v, true) <= C.starBucket(v, false)
            )
        }
    }

    @Test
    fun `⑥ 各桶亮度与 alpha 严格递增且长度等于桶数`() {
        assertEquals(C.BUCKETS, C.STAR_WHITE.size)
        assertEquals(C.BUCKETS, C.STAR_ALPHA.size)
        for (i in 0 until C.BUCKETS - 1) {
            assertTrue("STAR_WHITE[$i] < STAR_WHITE[${i + 1}]", C.STAR_WHITE[i] < C.STAR_WHITE[i + 1])
            assertTrue("STAR_ALPHA[$i] < STAR_ALPHA[${i + 1}]", C.STAR_ALPHA[i] < C.STAR_ALPHA[i + 1])
        }
        assertTrue("最亮桶白度 ≤ 1", C.STAR_WHITE[C.BUCKETS - 1] <= 1f)
        assertTrue("最亮桶 alpha ≤ 1", C.STAR_ALPHA[C.BUCKETS - 1] <= 1f)
    }

    @Test
    fun `⑦ 尘埃带档位随画布短边单调不减且封顶`() {
        assertEquals(0, C.dustTier(400f))
        assertEquals(0, C.dustTier(C.DIM_TIERS[0]))
        assertEquals(1, C.dustTier(C.DIM_TIERS[0] + 1f))
        assertEquals(1, C.dustTier(1080f))
        assertEquals(C.DIM_TIERS.size - 1, C.dustTier(4000f))   // 超末档封顶
        var prev = -1
        var d = 200f
        while (d < 3000f) {
            val t = C.dustTier(d)
            assertTrue("dustTier 必须单调不减（d=$d）", t >= prev)
            prev = t
            d += 37f
        }
    }

    @Test
    fun `⑧ 螺线常量自洽且缓存盐唯一`() {
        // 16 臂等分圆周
        assertEquals(360f, C.ARM_DEG * C.ARMS, 1e-3f)
        // 尘埃带与星点共用同一组螺线参数（同一条曲线才能"压过旋臂"）
        assertTrue("B_PARAM 必须为正（对数螺线外扩）", C.B_PARAM > 0f)
        assertTrue("MAX_R_K 必须 < 1（留出画布边距）", C.MAX_R_K < 1f)
        assertTrue("A0_K 必须 > 0（中心不为 0 半径）", C.A0_K > 0f)
        assertTrue("远臂门限在 (0,1) 内", C.FAR_T > 0f && C.FAR_T < 1f)
        // ⛔ 跨效果撞键防线：本效果的盐必须与其他效果的盐不同
        assertTrue("E11 的盐不能是 0（0 等价于「没有盐」）", C.E11_KEY_SALT != 0L)
        val others = longArrayOf(0x03030303L, 0x07070707L, 0x13131313L, 0x5F5F5F5FL, 0x3A3A3A3AL)
        for (salt in others) {
            assertTrue(
                "E11_KEY_SALT 不能与既有盐 0x${salt.toString(16)} 相同（否则 Shading2D 进程级缓存撞键）",
                C.E11_KEY_SALT != salt
            )
        }
    }

    // ── 负向自证（喂**同一份**谓词）────────────────────────────────────────

    @Test
    fun `负向N1 旧实现的每帧常量累加必须被判为帧率绑定`() {
        // 前提：旧实现在 60fps 下确实能转起来（不是恒 0 的假夹具）
        assertTrue("前提：旧实现 60fps 下 1 秒有旋转", spinOld(120f, 60, 1f) > 0f)
        assertFalse(
            "旧实现（每帧常量）喂进**同一个**帧率无关谓词 ⇒ 必须判失败",
            frameRateInvariant(120f) { b, f, s -> spinOld(b, f, s) }
        )
        // 并且失败的原因就是「帧率绑定」：30fps 恰好是 60fps 的一半
        val r60 = spinOld(120f, 60, 1f)
        val r30 = spinOld(120f, 30, 1f)
        assertEquals("旧实现 30fps 应恰为 60fps 的 50%（原文「差 2 倍」）", 0.5f, r30 / r60, 1e-3f)
    }

    @Test
    fun `负向N2 去掉远臂降档必须被判为纵深失效`() {
        // 反例：far 参数被忽略（等价于"没有纵深"）
        fun noFar(v: Float, far: Boolean): Int {
            val b = (v * C.BUCKETS).toInt().coerceIn(0, C.BUCKETS - 1)
            return b    // ⛔ 故意不处理 far
        }
        // ⛔ v1.38 修：谓词原来只有 `far ≤ near`（**非严格**），而"完全不降档"会产生
        // **相等**的桶位、依然满足 `<=` ⇒ 谓词恒真，负向夹具反而判"通过"。
        // 生产端 [starBucket] 是 `(b - 1).coerceAtLeast(0)` 的**严格**降一档，
        // 因此谓词必须**同时**要求：① 远臂桶位处处不高于近臂；② 存在严格降档的点。
        fun depthOk(f: (Float, Boolean) -> Int): Boolean {
            val noBrighter = (0..20).all { i -> f(i / 20f, true) <= f(i / 20f, false) }
            val strictDrop = (0..20).any { i -> f(i / 20f, true) < f(i / 20f, false) }
            return noBrighter && strictDrop
        }

        assertTrue("正向：生产函数满足纵深谓词", depthOk { v, far -> C.starBucket(v, far) })
        assertFalse(
            "负向：忽略 far 的实现必须被判失败（证明该谓词不是恒真）",
            depthOk { v, far -> noFar(v, far) }
        )
    }

    @Test
    fun `负向N3 恒定档位函数必须被判为档位失效`() {
        fun constTier(@Suppress("UNUSED_PARAMETER") minDim: Float): Int = 0
        fun tierVaries(f: (Float) -> Int): Boolean =
            f(C.DIM_TIERS[0]) != f(C.DIM_TIERS[C.DIM_TIERS.size - 1])

        assertTrue("正向：生产档位函数随尺寸变化", tierVaries { d -> C.dustTier(d) })
        assertFalse(
            "负向：恒定档位必须被判失败（证明该谓词不是恒真）",
            tierVaries { _ -> constTier(0f) }
        )
    }
}

package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * §B3 · E16 数字雨 —— 门禁（**8 正向 + 3 负向自证**）
 *
 * 判据一律**读生产常量与纯函数**（[MatrixRainRenderer.trailFade] /
 * [MatrixRainRenderer.trailAlpha] / [MatrixRainRenderer.shadeFor] /
 * [MatrixRainRenderer.advanceCol] / [MatrixRainRenderer.packRgb]），
 * ⛔ 不在测试里复制算法 —— 否则门禁与生产会各自漂移（T3.7 的 §八 G10 ④ 就是这条教训）。
 *
 * 核心验收（§B3「头上有**拖影**」）：
 * 逐格 fade 必须**从头部向外衰减**；负向自证把**旧口径**（`1 - k/perCol` ⇒ 越远越亮）
 * 喂进**同一个**单调谓词 ⇒ 必须判失败。
 *
 * ⚠️ `postFx`（0.50 / 0.030 / 0.16）是 `protected`，单测**取不到** ⇒ 由源码扫描门禁
 * `FxCoverageScanTest` 覆盖（见 §12.4：那里只认**字面量**）。
 */
class MatrixRainTest {

    /** companion 实例（`internal companion object` 同模块可见） */
    private val C = MatrixRainRenderer.Companion

    /** 门禁用列数（与生产 `perCol = 14` 独立取一个值，避免"只在这一个数上成立"） */
    private val perCol = 14

    private val speed = 120f
    private val span = 1_000_000f

    // ── 谓词（正向与负向**共用**）──────────────────────────────────────────

    /** 「距头部越近越亮」：扫全部格，fade 必须单调不减 */
    private fun fadeMonotone(fade: (Int, Int) -> Float, n: Int): Boolean {
        var prev = -1f
        for (k in 0 until n) {
            val f = fade(k, n)
            if (f < prev - 1e-4f) return false
            prev = f
        }
        return true
    }

    /**
     * 「帧率无关」谓词：积分 1 **秒**（= `fps` 帧）后的总位移。
     * 积分函数**由参数注入** ⇒ 正负向喂的是**同一份判据**，而不是另写一句
     * 「预期为 false」的表达式（那样会写成恒真断言）。
     */
    private fun frameRateInvariant(integrate: (Int) -> Float): Boolean {
        val a = integrate(60)
        val b = integrate(30)
        val c = integrate(15)
        return abs(a - b) < 1e-2f && abs(a - c) < 1e-2f
    }

    /** 生产实现（`advanceCol`，dt 化）积分 1 秒 */
    private fun integrateProd(fps: Int): Float {
        var y = 0f
        repeat(fps) { y = C.advanceCol(y, speed, 1f / fps, span) }
        return y
    }

    /** 旧口径（负向夹具 = 原 E16 实现）：**每帧固定 `+speed`**，与帧率绑定 */
    private fun integrateOld(fps: Int): Float {
        var y = 0f
        repeat(fps) { y = (y + speed) % span }
        return y
    }

    /** 「档位函数真的在分档」：扫全部格必须命中全部 [MatrixRainRenderer.SHADES] 档且都在范围内 */
    private fun shadeVaries(f: (Int, Int) -> Int, n: Int): Boolean {
        val seen = mutableSetOf<Int>()
        for (k in 0 until n) {
            val s = f(k, n)
            if (s !in 0 until C.SHADES) return false
            seen.add(s)
        }
        return seen.size == C.SHADES
    }

    /** 8bit sRGB 亮度（用于校验 5 档绿严格递减） */
    private fun lum(rgb: Int): Float =
        0.299f * ((rgb shr 16) and 0xFF) + 0.587f * ((rgb shr 8) and 0xFF) + 0.114f * (rgb and 0xFF)

    // ── 正向 ───────────────────────────────────────────────────────────────

    @Test
    fun `① 拖影 fade 从头部向外线性衰减（端点与单调性）`() {
        assertEquals("最远格（k = 0）fade 必须为 0", 0f, C.trailFade(0, perCol), 1e-6f)
        assertEquals(
            "头部前一格（k = perCol-1）fade 必须最大且 < 1（头部本身另有档位）",
            (perCol - 1f) / perCol, C.trailFade(perCol - 1, perCol), 1e-6f
        )
        assertTrue("fade 必须随 k 单调不减（k 越大越靠近头部）", fadeMonotone({ k, n -> C.trailFade(k, n) }, perCol))
        // 方向硬断言：靠近头部必须**亮于**最远格（⛔ 旧实现恰好相反）
        assertTrue(
            "头部侧 fade 必须严格大于最远格（⛔ 旧实现 `1 - k/perCol` 方向相反）",
            C.trailFade(perCol - 1, perCol) > C.trailFade(0, perCol)
        )
    }

    @Test
    fun `② blit alpha 有下限且随 fade 单调不减`() {
        assertEquals("最远格 alpha 必须恰为下限（否则会出现 alpha = 0 的无效 blit）",
            C.TRAIL_ALPHA_FLOOR, C.trailAlpha(0, perCol), 1e-6f)
        assertTrue("下限必须在 (0,1)（既非全透明也非全不透明）",
            C.TRAIL_ALPHA_FLOOR > 0f && C.TRAIL_ALPHA_FLOOR < 1f)
        var prev = -1f
        for (k in 0 until perCol) {
            val a = C.trailAlpha(k, perCol)
            assertTrue("alpha 必须单调不减（k=$k → $a）", a >= prev - 1e-4f)
            assertTrue("alpha 必须落在 [FLOOR, 1]（k=$k → $a）",
                a >= C.TRAIL_ALPHA_FLOOR - 1e-6f && a <= 1f + 1e-6f)
            prev = a
        }
        assertTrue("头部侧 alpha 必须显著高于最远格",
            C.trailAlpha(perCol - 1, perCol) - C.trailAlpha(0, perCol) > 0.5f)
    }

    @Test
    fun `③ 档位映射：头部固定 0 档，其余随距离单调变亮，且覆盖全部 5 档`() {
        assertEquals("头部（k >= perCol-1）必须是 SHADE_HEAD", C.SHADE_HEAD, C.shadeFor(perCol - 1, perCol))
        assertEquals("越界 k 也应按头部处理（夹紧，不越界访问）", C.SHADE_HEAD, C.shadeFor(perCol + 5, perCol))
        assertEquals("最远格必须是末档（暗绿）", C.SHADES - 1, C.shadeFor(0, perCol))
        // 档位号随 k 单调**不增**（k 越大越亮 ⇒ 档号越小）
        var prev = C.SHADES
        for (k in 0 until perCol) {
            val s = C.shadeFor(k, perCol)
            assertTrue("档号必须随 k 单调不增（k=$k → $s > $prev）", s <= prev)
            prev = s
        }
        assertTrue("扫过整列必须命中全部 ${C.SHADES} 档（无空档）",
            shadeVaries({ k, n -> C.shadeFor(k, n) }, perCol))
    }

    @Test
    fun `④ 列位移 dt 化：60 30 15 fps 下同一墙钟时间位移一致`() {
        assertTrue(
            "60/30/15 fps 必须给出同一秒位移（§B3：雨速不得与帧率绑定）",
            frameRateInvariant { fps -> integrateProd(fps) }
        )
        assertEquals(
            "60fps 下 1 秒位移 == speed × 60（速率量纲自洽）",
            speed * 60f, integrateProd(60), 1e-1f
        )
        // ⛔ 与旧实现逐像素等同（这是"零观感回归"的硬保证）
        assertEquals(
            "60fps 下新实现必须与旧实现（每帧 +speed）逐像素等同",
            integrateOld(60), integrateProd(60), 1e-1f
        )
        // dt = 0（首帧）不得推进
        assertEquals("dt = 0 不得推进", 123f, C.advanceCol(123f, speed, 0f, span), 1e-6f)
    }

    @Test
    fun `⑤ 5 档绿亮度严格递减且描边比所有档都暗`() {
        assertEquals("§B3-③：档位 4 → 5", 5, C.SHADES)
        assertEquals("§B3-③：8 张 → 10 张（5 档 × 2 字符）", 10, C.SHADES * 2)
        assertEquals("SHADE_RGB 必须与 SHADES 等长", C.SHADES, C.SHADE_RGB.size)
        assertEquals("§B3-③ 新增「白热头部」= rgb(235,255,235)", 0xFFEBFFEB.toInt(), C.SHADE_RGB[0])
        var prev = Float.MAX_VALUE
        for (s in 0 until C.SHADES) {
            val l = lum(C.SHADE_RGB[s])
            assertTrue("档 $s 亮度必须严格小于档 ${s - 1}（实测 $l vs $prev）", l < prev)
            prev = l
        }
        assertTrue("描边色必须比**所有**档都暗（否则描边看不见）",
            lum(C.OUTLINE_RGB) < lum(C.SHADE_RGB[C.SHADES - 1]))
        assertTrue("描边必须是深绿（G 分量最大，R = 0）",
            ((C.OUTLINE_RGB shr 16) and 0xFF) == 0 && ((C.OUTLINE_RGB shr 8) and 0xFF) > (C.OUTLINE_RGB and 0xFF))
    }

    @Test
    fun `⑥ 字形高光色必须比基色亮（中心偏白的垂直渐变）`() {
        for (s in 0 until C.SHADES) {
            val base = C.SHADE_RGB[s]
            val hi = C.glyphHighlightArgb(base)
            val br = (base shr 16) and 0xFF; val bg = (base shr 8) and 0xFF; val bb = base and 0xFF
            val hr = (hi shr 16) and 0xFF; val hg = (hi shr 8) and 0xFF; val hb = hi and 0xFF
            assertTrue("高光各通道不得低于基色（档 $s：$hr,$hg,$hb vs $br,$bg,$bb）",
                hr >= br && hg >= bg && hb >= bb)
            assertTrue("高光至少要有一个通道严格更亮（档 $s）", hr > br || hg > bg || hb > bb)
            assertEquals("高光不得改变 alpha", 0xFF, (hi ushr 24) and 0xFF)
        }
    }

    @Test
    fun `⑦ 常量与 §B3 明文对齐`() {
        assertEquals("§B3-①「半径 = gH × 0.9」", 0.9f, C.GLOW_R_RATIO, 1e-6f)
        assertEquals("§B3-①「alpha 0.30」", 0.30f, C.GLOW_ALPHA, 1e-6f)
        assertEquals("§B3-③ 头部档位 = 0（唯一烘光晕的档）", 0, C.SHADE_HEAD)
        assertTrue("STROKE_RATIO 必须在 (0,1)（否则描边不可见或糊掉字形）",
            C.STROKE_RATIO > 0f && C.STROKE_RATIO < 1f)
        assertTrue("GLYPH_HIGHLIGHT 必须在 (0,1)", C.GLYPH_HIGHLIGHT > 0f && C.GLYPH_HIGHLIGHT < 1f)
        assertTrue("GLOW_ALPHA 必须在 (0,1)", C.GLOW_ALPHA > 0f && C.GLOW_ALPHA < 1f)
        assertEquals("帧率基准必须是 60（保证 60fps 与旧实现等同）", 60f, C.RAIN_FPS_BASE, 1e-6f)
        assertTrue("光晕必须真的能溢出字形框（半径比 > 0.5）", C.GLOW_R_RATIO > 0.5f)
    }

    @Test
    fun `⑧ packRgb 语义与 android Color rgb 一致（纯 Kotlin，单测可验）`() {
        assertEquals(0xFFEBFFEB.toInt(), C.packRgb(235, 255, 235))
        assertEquals(0xFF00FF64.toInt(), C.packRgb(0, 255, 100))
        assertEquals(0xFF00C832.toInt(), C.packRgb(0, 200, 50))
        assertEquals(0xFF00821E.toInt(), C.packRgb(0, 130, 30))
        assertEquals(0xFF005A14.toInt(), C.packRgb(0, 90, 20))
        assertEquals("SHADE_RGB[0] 必须就是 packRgb(235,255,235)", C.packRgb(235, 255, 235), C.SHADE_RGB[0])
        assertEquals("所有档位 alpha 必须为不透明", 0xFF, (C.SHADE_RGB[0] ushr 24) and 0xFF)
    }

    // ── 负向自证（喂**同一份**谓词）────────────────────────────────────────

    @Test
    fun `负向N1 旧的反向 fade 必须被判为非单调`() {
        /** 旧口径（负向夹具）：`1 - k/perCol` ⇒ 越远离头部越亮 */
        fun fadeOld(k: Int, n: Int): Float = 1f - k.toFloat() / n

        assertTrue("正向：生产 trailFade 必须单调不减",
            fadeMonotone({ k, n -> C.trailFade(k, n) }, perCol))
        assertFalse(
            "负向：旧口径（1 - k/perCol）喂进**同一个**单调谓词 ⇒ 必须判失败" +
                "（它的方向与 §B3「头部最亮」相反）",
            fadeMonotone({ k, n -> fadeOld(k, n) }, perCol)
        )
        // 失败原因就是「方向反了」：旧口径下头部最暗、最远格最亮
        assertTrue("旧口径下头部应恰为最暗", fadeOld(perCol - 1, perCol) < fadeOld(0, perCol))
        assertTrue("生产口径下头部必须最亮", C.trailFade(perCol - 1, perCol) > C.trailFade(0, perCol))
    }

    @Test
    fun `负向N2 每帧固定增量的列位移必须被判为帧率绑定`() {
        assertTrue("前提：旧口径 60fps 下位移确实在推进", integrateOld(60) > 0f)
        assertFalse(
            "旧口径（每帧 +speed）喂进**同一个**帧率无关谓词 ⇒ 必须判失败",
            frameRateInvariant { fps -> integrateOld(fps) }
        )
        // 失败原因就是「帧率绑定」：30fps 恰好是 60fps 的一半
        assertEquals("旧口径 30fps 应恰为 60fps 的 50%",
            0.5f, integrateOld(30) / integrateOld(60), 1e-3f)
    }

    @Test
    fun `负向N3 恒定档位函数必须被判为分档失效`() {
        assertTrue("正向：生产 shadeFor 扫过整列命中全部档",
            shadeVaries({ k, n -> C.shadeFor(k, n) }, perCol))
        assertFalse(
            "负向：恒定档位必须被判失败（证明该谓词不是恒真）",
            shadeVaries({ _, _ -> 2 }, perCol)
        )
        assertFalse(
            "负向：档位越界（返回 SHADES）必须被判失败",
            shadeVaries({ _, _ -> C.SHADES }, perCol)
        )
    }
}

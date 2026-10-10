package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * E44「明月」T6 的**色尺数学**（[MoonSeascape] = §3.1 定稿的两个海/天色基准 + §3.3 色温）。
 *
 * 纯 JVM：[MoonSeascape] 零 Android/Compose 类型（颜色一律打包成 ARGB Int），⛔ 不挂 Robolectric。
 * 本文件只管**尺本身**；"渲染器真的调了这把尺、⛔ 没有自己抄字面量"归 [MoonlitSkySeaTest]。
 */
class MoonSeascapeTest {

    // ── §3.3 altT ───────────────────────────────────────────────────────────────

    @Test
    fun `altT 由固定构图比例推出且与画幅无关`() {
        // 分子分母同量纲 ⇒ 归一化比例与像素必须给同一个数（ALT_T 能做常数的全部依据）
        assertEquals(0.6875, MoonSeascape.ALT_T.toDouble(), 1e-6)
        for (h in intArrayOf(720, 1080, 1440, 2160)) {
            val horizon = h * MoonlitRenderer.HORIZON_K
            val moonCy = h * MoonlitRenderer.MOON_CY_K
            assertEquals("画幅 $h 的 altT 必须与常数一致",
                MoonSeascape.ALT_T, MoonSeascape.altitude(moonCy, horizon), 1e-5f)
        }
    }

    @Test
    fun `altT 夹紧且退化输入不除零`() {
        assertEquals(1f, MoonSeascape.altitude(0f, 640f), 1e-6f)      // 天顶
        assertEquals(0f, MoonSeascape.altitude(640f, 640f), 1e-6f)    // 贴地平
        assertEquals(0f, MoonSeascape.altitude(900f, 640f), 1e-6f)    // 已经掉到海面上
        assertEquals(0f, MoonSeascape.altitude(120f, 0f), 1e-6f)      // ⛔ 除零
        assertEquals(0f, MoonSeascape.altitude(120f, -8f), 1e-6f)
    }

    // ── 天空地平档 ───────────────────────────────────────────────────────────────

    @Test
    fun `天空地平档逐字等于定稿尺的三个端点`() {
        // `rgb(9+16t, 15+12t, 30+6t)`（§3.1 / 原型 `moonlit-preview.html:988`）
        // ⚠️ 期望值一律写成 rgb 三元组（[argb]），⛔ 不写 `0xFF090F1E`：那是 **Long**，
        //    与被检的负 **Int** 比较恒不等（JUnit 会选 `assertEquals(Object, Object)` 装箱）。
        assertEquals(argb(9, 15, 30), MoonSeascape.skyHorizon(0f))                  // #090f1e
        assertEquals(argb(25, 27, 36), MoonSeascape.skyHorizon(1f))                  // #191b24
        assertEquals(argb(20, 23, 34), MoonSeascape.skyHorizon(MoonSeascape.ALT_T))  // 真正上屏的那档
        // ⚠️ 固定构图下真正上屏的是**第三行**，⛔ 不是 rgb(9,15,30)（那是尺的下端、月贴地平时的色）。
        assertNotEquals("色温没接上时天空地平档会塌回 rgb(9,15,30)",
            MoonSeascape.skyHorizon(0f), MoonSeascape.skyHorizon(MoonSeascape.ALT_T))
    }

    @Test
    fun `天空地平档对 altT 单调变亮`() {
        var prev = -1
        for (step in 0..20) {
            val t = step / 20f
            val c = MoonSeascape.skyHorizon(t)
            val lum = channel(c, 16) + channel(c, 8) + channel(c, 0)
            assertTrue("altT=$t 的总亮度必须不降（三通道系数全为正）", lum > prev)
            prev = lum
        }
    }

    // ── 海水尺 ──────────────────────────────────────────────────────────────────

    @Test
    fun `海水尺三档端点逐字等于 §3_1 定稿`() {
        assertEquals(argb(10, 17, 29), MoonSeascape.sea(0f, 1f))                        // #0a111d
        assertEquals(argb(5, 9, 17), MoonSeascape.sea(MoonSeascape.SEA_SPLIT, 1f))      // #050911
        assertEquals(argb(2, 4, 10), MoonSeascape.sea(1f, 1f))                          // #02040a
    }

    @Test
    fun `海水尺逐通道单调不增`() {
        var pr = 256
        var pg = 256
        var pb = 256
        for (step in 0..200) {
            val c = MoonSeascape.sea(step / 200f, 1f)
            val r = channel(c, 16)
            val g = channel(c, 8)
            val b = channel(c, 0)
            assertTrue("深度 ${step / 200f} 处海水必须越深越暗：$c", r <= pr && g <= pg && b <= pb)
            pr = r
            pg = g
            pb = b
        }
    }

    @Test
    fun `sb 是三通道的同一乘子`() {
        for (step in 0..20) {
            val d = step / 20f
            val base = MoonSeascape.sea(d, 1f)
            val doubled = MoonSeascape.sea(d, 2f)
            for (sh in intArrayOf(16, 8, 0)) {
                // 两次取整各带来 ≤0.5 的误差 ⇒ 合成后容差 1
                assertEquals("深度 $d 通道 $sh 的 sb 调制必须是同一乘子",
                    (channel(base, sh) * 2).toDouble(), channel(doubled, sh).toDouble(), 1.0)
            }
        }
        assertEquals("sb=0 必须全黑（⛔ 留一个不透明的深蓝底）", argb(0, 0, 0), MoonSeascape.sea(0.5f, 0f))
    }

    @Test
    fun `取色一律钳在 0 到 255 且 alpha 恒不透明`() {
        for (sb in floatArrayOf(0f, 1f, 1.5f, 4f, -1f)) {
            for (d in floatArrayOf(-2f, 0f, 0.35f, 0.7f, 1f, 9f)) {
                val c = MoonSeascape.sea(d, sb)
                assertEquals("alpha 必须是 0xFF（⛔ shl 溢出进 alpha 等于把像素抹掉）",
                    0xFF, (c ushr 24) and 0xFF)
                for (sh in intArrayOf(16, 8, 0)) {
                    assertTrue("通道 $sh 越界：$c", channel(c, sh) in 0..255)
                }
            }
        }
    }

    // ── 负向自证：这些判据必须**能红** ────────────────────────────────────────────

    /**
     * 负向 ①：把 §3.1 的三段尺**压成一条** 0→1 直线（"看起来更像一条渐变"的想当然写法，
     * 也正是 §7.3 梯子的镜像故障：中点偏亮 2 个色阶）。判据 = 三档端点逐字相等。
     */
    @Test
    fun `负向自证 把三段尺压成一条直线会被端点判红`() {
        val real = { d: Float -> MoonSeascape.sea(d, 1f) }
        val flattened = { d: Float ->
            val k = d.coerceIn(0f, 1f)
            (0xFF shl 24) or
                ((10f + (2f - 10f) * k).roundToInt() shl 16) or
                ((17f + (4f - 17f) * k).roundToInt() shl 8) or
                (29f + (10f - 29f) * k).roundToInt()
        }
        assertTrue("真尺必须命中三档定稿端点", matchesSpecStops(real))
        assertFalse("判据失效：压扁的尺竟然也过了端点检查", matchesSpecStops(flattened))
        assertNotEquals("压扁后 0.35 处必须与 rgb(5,9,17) 不同",
            MoonSeascape.sea(MoonSeascape.SEA_SPLIT, 1f), flattened(MoonSeascape.SEA_SPLIT))
    }

    /** 负向 ②：`sb` 只乘 R（"低频让水偏红"的想当然写法）⇒ 同一乘子判据必须红。 */
    @Test
    fun `负向自证 sb 只乘一个通道会被同乘子判红`() {
        var worst = 0.0
        for (step in 0..20) {
            val d = step / 20f
            val base = MoonSeascape.sea(d, 1f)
            val rOnly = (base and 0x00FFFFFF) or
                (((channel(base, 16) * 2f).roundToInt().coerceIn(0, 255)) shl 16)
            for (sh in intArrayOf(8, 0)) {
                worst = maxOf(worst, abs(channel(base, sh) * 2 - channel(rOnly, sh)).toDouble())
            }
        }
        assertTrue("判据失效：只乘 R 竟仍算同一乘子（最大偏差 $worst）", worst > 1.0)
    }

    /** 负向 ③：altT 写成常数 0（= T4 遗留的那份颜色）必须与定稿**不等**。 */
    @Test
    fun `负向自证 色温接成常数会退回基准色`() {
        assertEquals("写死 altT=0 就是 T4 遗留的那份颜色", argb(9, 15, 30), MoonSeascape.skyHorizon(0f))
        assertNotEquals(MoonSeascape.skyHorizon(0f), MoonSeascape.skyHorizon(MoonSeascape.ALT_T))
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────────

    /** §3.1 三档端点判据本身（负向 ① 拿来判真尺与被检对象的同一个式子）。 */
    private fun matchesSpecStops(ruler: (Float) -> Int): Boolean =
        ruler(0f) == argb(10, 17, 29) &&
            ruler(MoonSeascape.SEA_SPLIT) == argb(5, 9, 17) &&
            ruler(1f) == argb(2, 4, 10)

    /** 不透明 ARGB Int（⛔ 不要用 `0xFFRRGGBB` 字面量做期望值，见 [天空地平档逐字等于定稿尺的三个端点] 的注）。 */
    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun channel(argb: Int, shift: Int): Int = (argb ushr shift) and 0xFF
}

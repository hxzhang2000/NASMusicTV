package com.nasmusic.tv.visualizer.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §八 G2：光照与材质（含负向自证：光源角反了明暗必须反转） */
class Shading2DTest {

    @Test
    fun `lambert - 面向光源 315° 更亮 背光 135° 更暗`() {
        val lit = Shading2D.lambert(315f)
        val dark = Shading2D.lambert(135f)
        assertTrue("lambert(315°)=$lit 应 > lambert(135°)=$dark", lit > dark)
        // 半兰伯特：背面不会全黑
        assertTrue(dark > 0f)
        assertTrue(lit <= 1f && dark <= 1f)
    }

    @Test
    fun `specular - 峰值出现在光源方向`() {
        var best = -1f
        var bestAngle = -1f
        for (deg in 0 until 360 step 5) {
            val v = Shading2D.specular(deg.toFloat())
            if (v > best) { best = v; bestAngle = deg.toFloat() }
        }
        assertEquals(315f, bestAngle, 2.5f)
        assertTrue(Shading2D.specular(315f) > 0.99f)
        assertEquals(0f, Shading2D.specular(135f), 1e-6f)   // d <= 0 时恒 0
    }

    @Test
    fun `rim - 背光侧最强`() {
        val back = Shading2D.rim(135f)
        val front = Shading2D.rim(315f)
        assertTrue("rim(135°)=$back 应 > rim(315°)=$front", back > front)
        assertTrue(back <= 1f && front >= 0f)
    }

    @Test
    fun `toneMap - Reinhard 压缩不过曝`() {
        assertEquals(0.5f, Shading2D.toneMap(1f), 1e-6f)
        assertTrue(Shading2D.toneMap(10f) < 1f)      // 大值被压进 0..1
        assertTrue(Shading2D.toneMap(0.1f) < 0.1f + 1e-6f)
    }

    @Test
    fun `负向 - 光源角取反的错误实现必须被判失败`() {
        // 模拟"LIGHT_ANGLE_DEG 被写成 135°"的错误实现（LX/LY 取反）：
        fun brokenLambert(normalAngleDeg: Float): Float {
            val a = Math.toRadians(normalAngleDeg.toDouble()).toFloat()
            val lx = 0.70710678f   // ⛔ 315° 的 cos 取反 = 135° 方向
            val ly = 0.70710678f
            val d = kotlin.math.cos(a) * lx + kotlin.math.sin(a) * ly
            return (d * 0.5f + 0.5f).coerceIn(0f, 1f)
        }
        // 错误实现下 315° 反而更暗 —— 正确实现的判据（lit > dark）在此必然反转
        assertTrue(brokenLambert(315f) < brokenLambert(135f))
        // 证明判据能区分：正确实现 315° > 135°，错误实现 315° < 135°（方向相反）
        assertTrue(Shading2D.lambert(315f) > Shading2D.lambert(135f))
    }
}

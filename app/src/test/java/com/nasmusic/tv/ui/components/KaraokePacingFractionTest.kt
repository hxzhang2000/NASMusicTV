package com.nasmusic.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * karaokePacingFraction 单元测试：逐字"前快后慢"覆盖曲线。
 */
class KaraokePacingFractionTest {

    @Test
    fun `boundaries stay at 0 and 1`() {
        assertEquals(0f, karaokePacingFraction(0f), 0f)
        assertEquals(0f, karaokePacingFraction(-0.5f), 0f)
        assertEquals(1f, karaokePacingFraction(1f), 0f)
        assertEquals(1f, karaokePacingFraction(1.5f), 0f)
    }

    @Test
    fun `halfway progress already covers more than half the line`() {
        // 前快后慢：时间过一半时覆盖比例应 > 0.5
        assertTrue(karaokePacingFraction(0.5f) > 0.5f)
    }

    @Test
    fun `quarter progress covers between a quarter and half`() {
        val f = karaokePacingFraction(0.25f)
        assertTrue(f > 0.25f)
        assertTrue(f < 0.5f)
    }

    @Test
    fun `ninety percent progress stays below one`() {
        assertTrue(karaokePacingFraction(0.9f) < 1f)
    }

    @Test
    fun `monotonically increasing`() {
        var prev = 0f
        for (i in 1..100) {
            val p = i / 100f
            val f = karaokePacingFraction(p)
            assertTrue("f($p)=$f should be >= prev=$prev", f >= prev)
            prev = f
        }
    }
}
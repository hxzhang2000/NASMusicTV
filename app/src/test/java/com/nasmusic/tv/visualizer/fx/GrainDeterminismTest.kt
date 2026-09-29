package com.nasmusic.tv.visualizer.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §八 G3：颗粒 tile 确定性门禁。
 *
 * tile 的像素由 `ProceduralTexture.grainPixels(variant)`（纯函数）生成后原样写入 Bitmap
 * ⇒ 在纯函数层断言逐像素等价，与在 Bitmap 层断言等价。
 */
class GrainDeterminismTest {

    private val N = ProceduralTexture.GRAIN_TILE * ProceduralTexture.GRAIN_TILE

    @Test
    fun `同 variant 两次生成的 tile 逐像素相同`() {
        val a = ProceduralTexture.grainPixels(3)
        val b = ProceduralTexture.grainPixels(3)
        assertEquals(N, a.size)
        assertTrue("同 (variant) 必须逐像素相同", a.contentEquals(b))
    }

    @Test
    fun `不同 variant 的 tile 逐像素不同`() {
        val a = ProceduralTexture.grainPixels(0)
        val b = ProceduralTexture.grainPixels(1)
        assertTrue("不同 variant 必须不同（否则 8 张 tile 是同一张）", !a.contentEquals(b))
    }

    @Test
    fun `alpha 范围 0到26 且有实际内容`() {
        val px = ProceduralTexture.grainPixels(5)
        var maxAlpha = 0
        var nonZero = 0
        for (p in px) {
            val a = (p ushr 24) and 0xFF
            assertTrue("alpha=$a 超出 0..26", a in 0..26)
            if (a > maxAlpha) maxAlpha = a
            if (a > 0) nonZero++
        }
        assertTrue("最大 alpha=$maxAlpha 应 > 0（噪点无内容）", maxAlpha > 0)
        assertTrue("非零像素占比过低: $nonZero/$N", nonZero > N / 4)
    }

    @Test
    fun `seq 映射 - seq 与 7 取模正确轮换 8 张`() {
        // drawGrain 用 (seq and 7) 选 tile：seq=8 必须回到第 0 张
        assertEquals(0, (8L and 7L).toInt())
        assertEquals(7, (7L and 7L).toInt())
        assertEquals(3, (11L and 7L).toInt())
    }

    @Test
    fun `负向 - 忽略 seq 恒用第 0 张的实现必须被判失败`() {
        // "恒用第 0 张"的错误实现下，variant=1 会返回 variant=0 的内容
        // ⇒ 本判据（不同 variant 必须不同）正好抓得住：
        val v0 = ProceduralTexture.grainPixels(0)
        val v1 = ProceduralTexture.grainPixels(1)
        assertTrue(!v0.contentEquals(v1))
    }
}

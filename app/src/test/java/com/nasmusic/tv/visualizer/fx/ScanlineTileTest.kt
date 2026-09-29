package com.nasmusic.tv.visualizer.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §八 G4：扫描线 tile 等价性门禁 —— tile 平铺版与"旧逐行 drawLine 版"
 * 在等距 12 个采样点的亮度差 ≤ 1/255（含负向自证：周期改错必须判失败）。
 */
class ScanlineTileTest {

    private val ROW_ALPHA = intArrayOf(41, 15, 0)   // 0.16 / 0.06 / 透明（×255 四舍五入）

    /** 旧版参照：周期 3px 的逐行 alpha（y≡0 → 0.16、y≡1 → 0.06、y≡2 → 0） */
    private fun referenceAlpha(y: Int): Int =
        when (y % 3) {
            0 -> 41      // 0.16
            1 -> 15      // 0.06
            else -> 0
        }

    /** tile 版：TileMode.Repeated 平铺 ⇒ 行 y 的 alpha = tile[y % tileH] 的高位 alpha 字节 */
    private fun tiledAlpha(y: Int, tile: IntArray): Int = (tile[y % tile.size] ushr 24) and 0xFF

    @Test
    fun `tile 内容与规格一致 - 0点16 0点06 透明`() {
        val tile = ProceduralTexture.scanlinePixels()
        assertEquals(3, tile.size)
        for (i in tile.indices) {
            val a = (tile[i] ushr 24) and 0xFF
            assertEquals("tile[$i] 的 alpha 字节", ROW_ALPHA[i], a)
        }
    }

    @Test
    fun `等距 12 采样点 tile 平铺版与旧版亮度差不超过 255 分之 1`() {
        val tile = ProceduralTexture.scanlinePixels()
        val step = 97   // 与 3 互质 ⇒ 12 个采样点覆盖全部相位
        var maxDiff = 0
        repeat(12) { i ->
            val y = i * step
            val diff = Math.abs(tiledAlpha(y, tile) - referenceAlpha(y))
            if (diff > maxDiff) maxDiff = diff
            assertTrue("y=$y 处亮度差 $diff/255 超限", diff <= 1)
        }
        assertTrue(maxDiff <= 1)
    }

    @Test
    fun `负向 - tile 周期改成 4px 后必须判失败`() {
        // 模拟"tile 周期被改成 4px"（多了一行透明 ⇒ 每周期有一条本该压暗的行漏掉）
        val brokenTile = intArrayOf(41, 15, 0, 0)
        val step = 97
        var found = false
        repeat(12) { i ->
            val y = i * step
            val diff = Math.abs(tiledAlpha(y, brokenTile) - referenceAlpha(y))
            if (diff > 1) found = true
        }
        assertTrue("周期改错必须能在 12 个采样点里被抓到（否则门禁空转）", found)
    }
}

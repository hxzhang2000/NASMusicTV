package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E25 催眠 · 布局常量与公式带边界测试（§13.1 HypnoticLayoutTest，§8.1）。
 *
 * 布局契约：左边距 7% + 绘图区 66% + 间隙 9% + 公式带 18% == 100%。
 */
class HypnoticLayoutTest {

    @Test
    fun `layout constants sum to full width with 9 percent gap`() {
        val plotLeft = HypnoticFunctionRenderer.PLOT_CX_F - HypnoticFunctionRenderer.PLOT_W_F / 2f
        val plotRight = HypnoticFunctionRenderer.PLOT_CX_F + HypnoticFunctionRenderer.PLOT_W_F / 2f
        val labelLeft = 1f - HypnoticFunctionRenderer.LABEL_BAND_F
        val gap = labelLeft - plotRight
        assertEquals(0.07f, plotLeft, 0.0001f)
        assertEquals(0.73f, plotRight, 0.0001f)
        assertEquals(0.82f, labelLeft, 0.0001f)
        assertEquals("9% gap between plot and label band", 0.09f, gap, 0.0001f)
    }

    @Test
    fun `label right edge respects safe area at 1080p`() {
        val w = 1920f
        val safeArea = w * 0.05f          // ≥5% 安全边距
        val rightX = w - safeArea - 32f
        assertTrue("rightX=$rightX must stay inside label band", rightX >= w * 0.82f)
    }

    @Test
    fun `label right edge respects safe area at small canvas`() {
        val w = 480f
        val safeArea = w * 0.05f
        val rightX = w - safeArea - 32f
        assertTrue("rightX=$rightX must stay inside label band", rightX >= w * 0.82f)
    }

    @Test
    fun `label band budget is 86 percent of band width`() {
        // FormulaLayout 接收 bandW * 0.86 作为排版预算（缩字号兜底的空间）
        val w = 1920f
        val band = w * HypnoticFunctionRenderer.LABEL_BAND_F
        assertEquals(345.6f, band, 0.01f)
        // 预算 = 345.6 * 0.86 ≈ 297px，10-foot 下 22sp 每行 12~14 字符
        val budget = band * 0.86f
        assertTrue(budget in 290f..300f)
    }

    @Test
    fun `formula never overlaps plot area`() {
        // 排版右缘 = rightX（右对齐），行宽 ≤ bandW*0.86 → 左缘 ≥ 0.82w + 14% 余量 > 0.73w
        val measure = FormulaLayout.MeasureFn { t, s -> t.length * s * 0.6f }
        val w = 1920f
        val rightX = 1792f
        val bandW = w * HypnoticFunctionRenderer.LABEL_BAND_F * 0.86f
        for (def in FunctionLibrary.ALL) {
            val r = FormulaLayout.layout(def.label, rightX, 540f, bandW, 34f, measure)
            for (i in 0 until r.runCount) {
                val runRight = r.runX[i] + r.runText[i].length * r.runSize[i] * 0.6f
                assertTrue(
                    "${def.tag} run $i overflows band: $runRight",
                    runRight <= rightX + 2f
                )
                val runLeft = r.runX[i]
                assertTrue(
                    "${def.tag} run $i intrudes plot area: $runLeft < ${0.73f * w}",
                    runLeft >= 0.73f * w - 2f
                )
            }
        }
    }
}

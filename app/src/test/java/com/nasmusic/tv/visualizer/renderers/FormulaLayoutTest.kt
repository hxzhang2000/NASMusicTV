package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FormulaLayout 数学排版测试（E25 催眠，§13.1）。
 *
 * 测量用字符数近似：width = len * textSize * 0.6，与真实字体无关，
 * 只验证解析结构与几何关系（嵌套层级 / 分式 / 根号 / 宽度预算）。
 */
class FormulaLayoutTest {

    private val measure = FormulaLayout.MeasureFn { text, size -> text.length * size * 0.6f }
    private val EM = 34f
    private val BAND = 400f
    private val RIGHT_X = 900f
    private val CENTER_Y = 300f

    private fun layout(src: String) =
        FormulaLayout.layout(src, RIGHT_X, CENTER_Y, BAND, EM, measure)

    // ── 解析 ──

    @Test
    fun `superscript parses into nested runs`() {
        val exprs = FormulaLayout.parse("y = e^{-x^2}")
        // 期望：基线 "y = e" + 上标 "-x" + 上标的上标 "2"
        val levels = exprs.map { (it as FormulaLayout.Text).level }
        assertEquals(listOf(0, 1, 2), levels)
        val texts = exprs.map { (it as FormulaLayout.Text).text }
        assertEquals(listOf("y = e", "-x", "2"), texts)
    }

    @Test
    fun `frac parses into numerator and denominator`() {
        val exprs = FormulaLayout.parse("y = frac{sin(x)}{x}")
        assertEquals(2, exprs.size)
        val frac = exprs[1] as FormulaLayout.Frac
        assertEquals(listOf("sin(x)"), frac.num.map { it.text })
        assertEquals(listOf("x"), frac.den.map { it.text })
    }

    @Test
    fun `sqrt parses inner content`() {
        val exprs = FormulaLayout.parse("y = sin(√{x})")
        val sqrt = exprs[1] as FormulaLayout.Sqrt
        assertEquals(listOf("x"), sqrt.inner.map { it.text })
    }

    @Test
    fun `unicode superscript equals caret syntax`() {
        val a = FormulaLayout.parse("r²")
        val b = FormulaLayout.parse("r^2")
        // 结构等价：两个基线/上标 run，文本与层级完全相同
        val ta = a.map { it as FormulaLayout.Text }
        val tb = b.map { it as FormulaLayout.Text }
        assertEquals(ta.map { it.text }, tb.map { it.text })
        assertEquals(ta.map { it.level }, tb.map { it.level })
        // 不含 U+00B2 字面（上标转普通字形）
        assertTrue(ta.none { it.text.contains('²') })
    }

    @Test
    fun `unbalanced braces fall back to literal`() {
        // 不抛异常；内容按字面保留
        val exprs = FormulaLayout.parse("y = frac{sin(x}")
        assertTrue(exprs.isNotEmpty())
        val text = exprs.filterIsInstance<FormulaLayout.Text>().joinToString("") { it.text }
        assertTrue(text.contains("sin(x") || text.contains("frac"))
    }

    @Test
    fun `adjacent same level text merges into one run`() {
        val exprs = FormulaLayout.parse("y = sin(x)")
        assertEquals(1, exprs.size)
        assertEquals("y = sin(x)", (exprs[0] as FormulaLayout.Text).text)
    }

    // ── 布局几何 ──

    @Test
    fun `superscript is smaller and raised above baseline`() {
        val r = layout("y = e^{-x^2}")
        fun idx(text: String, level: Int): Int {
            for (i in 0 until r.runCount) {
                if (r.runText[i] == text && r.runLevel[i] == level) return i
            }
            throw AssertionError("run '$text' level $level not found")
        }
        val host = idx("e", 0)
        val sup1 = idx("-x", 1)
        val sup2 = idx("2", 2)
        // 上标字号缩小，嵌套上标更小
        assertTrue(r.runSize[sup1] < r.runSize[host])
        assertTrue(r.runSize[sup2] < r.runSize[sup1])
        // 上标基线高于宿主基线（y 更小），嵌套更高
        assertTrue(r.runY[sup1] < r.runY[host])
        assertTrue(r.runY[sup2] < r.runY[sup1])
    }

    @Test
    fun `frac renders numerator above denominator`() {
        val r = layout("y = frac{sin(x)}{x}")
        // 结构：基线 y =、分子 sin(x)、分母 x
        assertTrue(r.runCount >= 3)
        val numIdx = (0 until r.runCount).first { r.runText[it] == "sin(x)" }
        val denIdx = (0 until r.runCount).last { r.runText[it] == "x" }
        val numY = r.runY[numIdx]
        val denY = r.runY[denIdx]
        assertTrue("numerator should be above denominator", numY < denY)
        // 存在分式装饰线
        assertEquals(1, r.decorCount)
        // 分数线 y 介于分子分母之间
        val lineY = r.decor[1]
        assertTrue(lineY > numY && lineY < denY)
    }

    @Test
    fun `sqrt vinculum extends to content edge`() {
        val r = layout("y = √{x}")
        assertEquals(1, r.decorCount)
        val barY = r.decor[1]
        val content = r.runText.withIndex().first { it.value == "x" }.index
        val innerRight = r.runX[content] + r.runSize[content] * 0.6f
        assertTrue("vinculum should reach content right edge", r.decor[2] >= innerRight - 2f)
        // 横线在内容上方
        assertTrue(barY < r.runY[content])
    }

    @Test
    fun `lines are right aligned within band`() {
        val r = layout("x = 16sin^3t, y = 13cost - 5cos2t - 2cos3t - cos4t")
        assertTrue(r.runCount > 1)
        // 每行右缘 ≤ rightX + 容差（右对齐）
        // 简化验证：所有 run 的 x 不超过 rightX + 1
        var maxX = Float.NEGATIVE_INFINITY
        for (i in 0 until r.runCount) {
            maxX = maxOf(maxX, r.runX[i] + r.runSize[i] * r.runText[i].length * 0.6f)
        }
        assertTrue("runs exceed right edge: $maxX", maxX <= RIGHT_X + 2f)
    }

    @Test
    fun `run count within budget for full library`() {
        for (def in FunctionLibrary.ALL) {
            val r = layout(def.label)
            assertTrue("${def.tag} has ${r.runCount} runs", r.runCount <= FormulaLayout.MAX_RUNS)
            assertTrue("${def.tag} produced no runs", r.runCount > 0)
        }
    }

    @Test
    fun `layout is vertical centered`() {
        val r = layout("y = sin(x)")
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in 0 until r.runCount) {
            top = minOf(top, r.runY[i] - r.runSize[i])
            bottom = maxOf(bottom, r.runY[i])
        }
        val mid = (top + bottom) / 2f
        // 行高按 1.35em 预留（含下行空间），视觉中心略高于几何中心，取 ±8px 容差
        assertTrue("center $mid should be near $CENTER_Y", abs(mid - CENTER_Y) <= 8f)
    }

    private fun abs(v: Float): Float = if (v < 0) -v else v
}

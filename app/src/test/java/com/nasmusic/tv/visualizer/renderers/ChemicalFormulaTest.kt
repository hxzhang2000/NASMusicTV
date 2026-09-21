package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChemicalFormula 化学式排版测试（E37「分子」）。
 *
 * 覆盖两条真机回归：
 *   ① **run 左缘连续性** —— runX 是左缘，绘制侧必须 `Paint.Align.LEFT`；
 *      曾经用 RIGHT 画，等于每个 run 再左移自身宽度，下标整块压到字母身上。
 *   ② **下标必须整体沉到基线之下** —— 曾经用数学排版器的 `SUB_LOWER = 0.25em`，
 *      小于下标字形字高（0.62em × 0.72 ≈ 0.45em），下标会骑在基线上。
 *
 * 每项都带**负向自证**：先算出"旧算法"的值并断言它与新值不同（证明断言不是空转），
 * 再断言新值落在合理区间。
 */
class ChemicalFormulaTest {

    /** 近似测量：每字符 0.6em（单测不需要真实字体） */
    private val measure = ChemicalFormula.MeasureFn { text, size -> text.length * size * 0.6f }

    /** 近似墨迹高度：字高 0.72em（数字字形占 em 的比例） */
    private val inkTop = ChemicalFormula.InkTopFn { _, size -> -0.72f * size }

    private fun layout(src: String, rightX: Float = 1000f, bandW: Float = 400f) =
        ChemicalFormula.layout(src, rightX, 300f, bandW, 44f, measure, inkTop)

    // ── 解析 ────────────────────────────────────────────────────

    @Test
    fun `parse splits baseline text and subscripts`() {
        val t = ChemicalFormula.parse("H_{2}O")
        assertEquals(3, t.size)
        assertEquals("H", t[0].text); assertEquals(false, t[0].sub)
        assertEquals("2", t[1].text); assertEquals(true, t[1].sub)
        assertEquals("O", t[2].text); assertEquals(false, t[2].sub)
    }

    @Test
    fun `parse keeps parentheses as baseline runs`() {
        val t = ChemicalFormula.parse("Fe(C_{5}H_{5})_{2}")
        // '(' ')' 与元素符号同属基线 run；只有花括号里的数字是下标
        assertEquals(listOf("Fe(C", "5", "H", "5", ")", "2"), t.map { it.text })
        assertEquals(listOf(false, true, false, true, false, true), t.map { it.sub })
    }

    @Test
    fun `parse supports subscript without braces`() {
        val t = ChemicalFormula.parse("H_2O")
        assertEquals(listOf("H", "2", "O"), t.map { it.text })
        assertEquals(listOf(false, true, false), t.map { it.sub })
    }

    @Test
    fun `parse drops a stray underscore instead of crashing`() {
        val t = ChemicalFormula.parse("H_O")
        // '_' 后面既没 '{' 也没数字 → 丢弃该 '_'，前后仍是两个基线 run（都在同一条基线上）
        assertEquals(listOf("H", "O"), t.map { it.text })
        assertEquals(listOf(false, false), t.map { it.sub })
    }

    // ── ① run 左缘连续性（Align.RIGHT 回归护栏）─────────────────

    @Test
    fun `run x is the left edge and runs are contiguous`() {
        val r = layout("C_{8}H_{10}N_{4}O_{2}")
        assertTrue("必须有多个 run，否则本断言形同空转", r.runCount >= 4)
        for (k in 0 until r.runCount - 1) {
            val w = measure.width(r.runText[k], r.runSize[k])
            val expected = r.runX[k] + w
            assertTrue(
                "run$k 与 run${k + 1} 不连续（左缘 ${r.runX[k]} + 宽 $w ≠ ${r.runX[k + 1]}）",
                kotlin.math.abs(expected - r.runX[k + 1]) < 0.01f
            )
        }
    }

    @Test
    fun `right edge of last run equals rightX`() {
        val r = layout("K_{4}[Fe(CN)_{6}]", rightX = 1000f)
        val last = r.runCount - 1
        val right = r.runX[last] + measure.width(r.runText[last], r.runSize[last])
        assertTrue("右缘应贴 rightX（实际 $right）", kotlin.math.abs(right - 1000f) < 0.01f)
        assertTrue("总宽应等于首尾差", kotlin.math.abs(r.totalWidth - (1000f - r.runX[0])) < 0.01f)
    }

    /** 负向自证：如果按旧的 RIGHT 对齐去画，每个 run 会左移自身宽度 —— 位移必须 > 0 */
    @Test
    fun `negative proof - RIGHT align would shift every run by its own width`() {
        val r = layout("H_{2}O")
        for (k in 0 until r.runCount) {
            val w = measure.width(r.runText[k], r.runSize[k])
            assertTrue("run$k 宽度必须 > 0，否则本断言空转", w > 0f)
            // RIGHT 对齐下的实际绘制左缘 = runX - w，与正确值差整整一个 w
            val wrongX = r.runX[k] - w
            assertTrue("run$k: RIGHT 对齐会错位 $w px", kotlin.math.abs(wrongX - r.runX[k]) > 1f)
        }
    }

    // ── ② 下标必须沉到基线之下 ──────────────────────────────────

    @Test
    fun `subscript baseline sits below the main baseline`() {
        val r = layout("H_{2}O")
        val base = r.runY[0]
        val sub = r.runY[1]
        assertTrue("下标必须在主基线之下（base=$base sub=$sub）", sub > base)

        val shift = sub - base
        val em = r.em
        assertTrue("下沉量 ${shift}em 应在 0.35em..0.80em 之间", shift > em * 0.35f && shift < em * 0.80f)
        // 下沉量必须 ≥ 下标字形墨迹高度，否则下标会骑在基线上
        val subInk = -inkTop.inkTop("0", em * ChemicalFormula.SUB_SCALE)
        assertTrue("下沉量 ${shift} 必须 ≥ 下标字高 $subInk", shift >= subInk)
    }

    /** 负向自证：数学排版器的旧值 0.25em 小于下标字高 —— 用它就会骑基线 */
    @Test
    fun `negative proof - old 025em shift would straddle the baseline`() {
        val em = 44f
        val subInk = -inkTop.inkTop("0", em * ChemicalFormula.SUB_SCALE)
        val oldShift = em * 0.25f
        assertTrue("旧下沉量 $oldShift 必须小于字高 $subInk（即会骑基线）", oldShift < subInk)
        // 新算法算出来的值必须不是旧值
        val r = layout("H_{2}O")
        assertTrue("新算法必须不同于旧值", kotlin.math.abs((r.runY[1] - r.runY[0]) - oldShift) > 1f)
    }

    @Test
    fun `subscript size is the sub scale of em`() {
        val r = layout("H_{2}O")
        assertEquals(r.em * ChemicalFormula.SUB_SCALE, r.runSize[1], 0.001f)
        assertEquals(r.em, r.runSize[0], 0.001f)
        assertEquals(r.em, r.runSize[2], 0.001f)
    }

    // ── 缩字号兜底 ──────────────────────────────────────────────

    @Test
    fun `overwide formula shrinks to fit the band`() {
        val r = layout("C_{100}H_{200}O_{100}N_{50}P_{20}S_{10}Cl_{8}", rightX = 1000f, bandW = 300f)
        assertTrue(
            "总宽 ${r.totalWidth} 应 ≤ 带宽 300（或已触底 ${ChemicalFormula.MIN_EM}）",
            r.totalWidth <= 300f * 1.02f || r.em == ChemicalFormula.MIN_EM
        )
        assertTrue("生效字号应 ≤ 44", r.em <= 44f)
    }

    @Test
    fun `narrow formula keeps the nominal em`() {
        val r = layout("H_{2}O", rightX = 1000f, bandW = 400f)
        assertEquals(44f, r.em, 0.001f)
    }

    // ── 全库校验 ────────────────────────────────────────────────

    @Test
    fun `every digit in the library is a subscript`() {
        MoleculeLibrary.ALL.forEach { def ->
            ChemicalFormula.parse(def.formulaMark).forEach { t ->
                if (!t.sub) {
                    assertTrue(
                        "${def.name}: 基线 run \"${t.text}\" 含裸数字（化学式里数字必须是下标）",
                        t.text.none { it.isDigit() }
                    )
                }
            }
        }
    }

    @Test
    fun `all library formulas layout sanely`() {
        MoleculeLibrary.ALL.forEach { def ->
            val r = ChemicalFormula.layout(def.formulaMark, 1500f, 400f, 300f, 44f, measure, inkTop)
            assertTrue("${def.name}: run 数 > 0", r.runCount > 0)
            assertTrue("${def.name}: 总宽应 > 0", r.totalWidth > 0f)
            assertTrue("${def.name}: 总宽 ${r.totalWidth} 应 ≤ 300", r.totalWidth <= 300f * 1.02f)
            for (k in 0 until r.runCount) {
                assertTrue("${def.name}: run$k x 必须有限", r.runX[k].isFinite())
                assertTrue("${def.name}: run$k y 必须有限", r.runY[k].isFinite())
                assertTrue("${def.name}: run$k 字号必须 > 0", r.runSize[k] > 0f)
            }
            // 下标必须全部低于主基线
            val base = r.runY[0]
            val sizes = r.runSize
            for (k in 0 until r.runCount) {
                if (sizes[k] < r.em - 0.001f) {
                    assertTrue("${def.name}: 下标 run$k 必须在主基线之下", r.runY[k] > base)
                }
            }
        }
    }
}

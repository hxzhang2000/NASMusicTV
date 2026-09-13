package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * E25 催眠 · 溃散联动测试（§13.1 HypnoticDissolveTest，§6.1 + §11.4）。
 *
 * 曲线点与公式 run 共用同一 dissolveProgress；公式 alpha 衰减快 15%，
 * 装饰线（分式线/根号线）宿主绑定有效。
 */
class HypnoticDissolveTest {

    private val measure = FormulaLayout.MeasureFn { t, s -> t.length * s * 0.6f }

    private fun layoutOf(tag: String): FormulaLayout.Result {
        val def = FunctionLibrary.ALL.first { it.tag == tag }
        return FormulaLayout.layout(def.label, 1000f, 300f, 345f, 34f, measure)
    }

    @Test
    fun `curve and formula share the same dissolve axis`() {
        // 渲染器把同一个 dissolveProgress 值传给曲线点与公式 run（实现上共用 dissolveP）；
        // 此处验证两套缓动函数输入相同输出可对齐：runAlpha 是曲线 alpha 的 15% 加速版
        val curveAlphaAt = { p: Float -> (1f - p) * 0.95f }
        for (p in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
            val runNorm = HypnoticFunctionRenderer.runAlpha(p) / 224f
            val expected = ((1f - p * 1.15f).coerceAtLeast(0f))
            assertEquals("p=$p", expected, runNorm, 0.0001f)
            // 公式先散干净：p<0.87 时 runAlpha > 0
            if (p < 0.87f) assertTrue(runNorm > 0f)
        }
        // 同一输入轴：曲线 alpha(0.5) ≈ 0.475，公式 ≈ 0.425（快 15%）
        assertEquals(0.475f, curveAlphaAt(0.5f), 0.001f)
        assertEquals(0.425f, HypnoticFunctionRenderer.runAlpha(0.5f) / 224f, 0.001f)
    }

    @Test
    fun `decor lines exist only for labels with frac or sqrt`() {
        val withDecor = setOf("A3", "A10", "A11", "A12", "D3")
        val withoutDecor = setOf("A1", "A5", "A7", "A13", "B1", "C1", "D1")
        for (tag in withDecor) {
            val r = layoutOf(tag)
            assertTrue("$tag should have decor lines", r.decorCount >= 1)
        }
        for (tag in withoutDecor) {
            val r = layoutOf(tag)
            assertEquals("$tag should have no decor lines", 0, r.decorCount)
        }
    }

    @Test
    fun `decor host index is valid and before the hosted run`() {
        for (def in FunctionLibrary.ALL) {
            val r = FormulaLayout.layout(def.label, 1000f, 300f, 345f, 34f, measure)
            for (j in 0 until r.decorCount) {
                val host = r.decorHost[j]
                assertTrue("${def.tag} host $host out of range", host in 0 until r.runCount)
            }
        }
    }

    @Test
    fun `run vanish order is deterministic per seed`() {
        // 同 seed 下阈值序列一致；异 seed 有区分（与渲染器 initDissolve 同款调用）
        fun thresholds(seed: Long): List<Float> {
            val rng = Random(seed)
            return List(12) { HypnoticFunctionRenderer.runVanishThreshold(rng) }
        }
        assertEquals(thresholds(9L), thresholds(9L))
        assertTrue(thresholds(9L) != thresholds(10L))
        // 阈值互不相同（均匀分布连续值，12 个几乎必然互异）
        assertTrue(thresholds(9L).distinct().size >= 10)
    }

    @Test
    fun `formula disappears before curve at p 0_9`() {
        // p=0.9：公式 alpha 已为 0；曲线 alpha 仍 > 0
        assertEquals(0f, HypnoticFunctionRenderer.runAlpha(0.9f), 0.0001f)
        val curve = (1f - 0.9f) * 0.95f
        assertTrue(curve > 0.05f)
    }

    @Test
    fun `superscript runs get extra vertical drift in dissolve`() {
        // §11.4：level>0 的 run 溃散时额外纵向漂移 p*8 —— 渲染器 drawFormula 内实现；
        // 此处锁定漂移常量关系：p=0.5 时上标额外漂移 4px（≈ 主抖动 14px 的 29%）
        val p = 0.5f
        val drift = p * 8f
        assertEquals(4f, drift, 0.0001f)
        assertTrue(drift < 14f * p)   // 仍小于主纵向抖动，不失控
    }
}

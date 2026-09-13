package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * FunctionLibrary 测试：清单契约、采样健壮性、随机调度契约（§13.1）。
 */
class FunctionLibraryTest {

    // ── 清单契约 ──

    @Test
    fun `library size and uniqueness`() {
        assertEquals(53, FunctionLibrary.ALL.size)
        assertEquals(53, FunctionLibrary.ALL.map { it.tag }.distinct().size)
        assertEquals(53, FunctionLibrary.ALL.map { it.label }.distinct().size)
        assertTrue(FunctionLibrary.ALL.none { it.label.isBlank() })
    }

    @Test
    fun `soft values within range`() {
        for (def in FunctionLibrary.ALL) {
            assertTrue("${def.tag} soft=${def.soft}", def.soft in 0.3f..1.0f)
        }
    }

    @Test
    fun `library matches documented category counts`() {
        val tags = FunctionLibrary.ALL.map { it.tag }
        assertEquals(27, tags.count { it.startsWith("A") })
        assertEquals(8, tags.count { it.startsWith("B") })
        assertEquals(8, tags.count { it.startsWith("C") })
        assertEquals(10, tags.count { it.startsWith("D") })
    }

    // ── 采样健壮性 ──

    @Test
    fun `sampling produces finite points for every function`() {
        for (def in FunctionLibrary.ALL) {
            val pts = FloatArray(240 * 2)
            val segs = IntArray(16)
            val axis = FloatArray(2)
            val domain = FloatArray(4)
            val segCount = FunctionLibrary.sample(def, 240, pts, segs, axis, domain)
            assertTrue("${def.tag} produced no segments", segCount > 0)
            for (s in 0 until segCount) {
                val start = segs[s] / 65536
                val len = segs[s] and 0xFFFF
                assertTrue("${def.tag} seg $s out of range", start + len <= 240)
                for (k in start until start + len) {
                    val tx = pts[k * 2]
                    val ty = pts[k * 2 + 1]
                    assertTrue("${def.tag} point $k not finite", tx.isFinite() && ty.isFinite())
                    assertTrue("${def.tag} tx=$tx out of [0,1]", tx in -0.001f..1.001f)
                    assertTrue("${def.tag} ty=$ty out of [-1,1]", ty in -1.001f..1.001f)
                }
            }
        }
    }

    @Test
    fun `discontinuous functions split into segments`() {
        fun segCountOf(tag: String): Int {
            val def = FunctionLibrary.ALL.first { it.tag == tag }
            val segs = IntArray(16)
            return FunctionLibrary.sample(def, 240, FloatArray(480), segs, FloatArray(2), FloatArray(4))
        }
        assertTrue("tan should have >= 3 segments", segCountOf("A6") >= 3)
        assertEquals("1/x should have exactly 2 segments", 2, segCountOf("A10"))
        assertEquals("ln should have 1 segment", 1, segCountOf("A16"))
    }

    @Test
    fun `closed parametric curves start and end at the same point`() {
        for (tag in listOf("B1", "B2", "B5")) {
            val def = FunctionLibrary.ALL.first { it.tag == tag }
            val pts = FloatArray(240 * 2)
            val segs = IntArray(16)
            FunctionLibrary.sample(def, 240, pts, segs, FloatArray(2), FloatArray(4))
            val packed = segs[0]
            val start = packed / 65536
            val len = packed and 0xFFFF
            val firstX = pts[start * 2]; val firstY = pts[start * 2 + 1]
            val lastX = pts[(start + len - 1) * 2]; val lastY = pts[(start + len - 1) * 2 + 1]
            val dist = kotlin.math.sqrt((firstX - lastX) * (firstX - lastX) + (firstY - lastY) * (firstY - lastY))
            assertTrue("$tag closed curve gap=$dist", dist < 0.05f)
        }
    }

    @Test
    fun `axis positions are meaningful for cartesian functions`() {
        val def = FunctionLibrary.ALL.first { it.tag == "A7" }   // y = x^2，域 [-4,4]
        val axis = FloatArray(2)
        val domain = FloatArray(4)
        FunctionLibrary.sample(def, 240, FloatArray(480), IntArray(16), axis, domain)
        // x=0 与 y=0 都在域内
        // y=0 可落在归一化边缘略外侧（采样点未精确命中 0），轴绘制已放宽到 ±1.05
        assertTrue(axis[0] in -0.05f..1.05f)
        assertTrue(axis[1] in -1.05f..1.05f)
        assertTrue(axis[1] <= -0.9f)   // y=0 应贴近下边缘
        assertEquals("domain xMin", -4f, domain[0], 0.01f)
        assertEquals("domain yMin", 0f, domain[2], 0.01f)
    }

    @Test
    fun `all labels fit the layout width budget`() {
        // 345px ≈ 18% of 1920；34px 字号；最宽 run 宽度用近似测量
        val measure = FormulaLayout.MeasureFn { t, s -> t.length * s * 0.6f }
        for (def in FunctionLibrary.ALL) {
            val r = FormulaLayout.layout(def.label, 1000f, 300f, 345f, 34f, measure)
            assertTrue("${def.tag} ${r.runCount} runs", r.runCount <= FormulaLayout.MAX_RUNS)
            // 无限行溢出：断行上限 3 行 → 高度 ≤ 3 * 2.1em
            assertTrue("${def.tag} layout too tall", r.totalHeight <= 34f * 2.1f * 3f + 1f)
        }
    }

    // ── 加权洗牌契约 ──

    @Test
    fun `shuffle covers every function once per cycle`() {
        val rng = Random(42)
        val order = IntArray(53) { it }
        FunctionLibrary.weightedShuffle(rng, order, -1)
        assertEquals(53, order.distinct().size)
        assertEquals((0 until 53).toSet(), order.toSet())
    }

    @Test
    fun `cycle boundary never repeats the previous function`() {
        val rng = Random(7)
        val order = IntArray(53) { it }
        FunctionLibrary.weightedShuffle(rng, order, -1)
        for (cycle in 0 until 99) {
            val last = order[52]
            FunctionLibrary.weightedShuffle(rng, order, last)
            assertTrue("cycle $cycle boundary repeats", order[0] != last)
        }
    }

    @Test
    fun `fixed seed produces deterministic sequence`() {
        fun sequence(): List<Int> {
            val rng = Random(123)
            val order = IntArray(53) { it }
            FunctionLibrary.weightedShuffle(rng, order, -1)
            val out = order.toList()
            FunctionLibrary.weightedShuffle(rng, order, order[52])
            return out + order.toList()
        }
        assertEquals(sequence(), sequence())
    }

    @Test
    fun `soft functions are over-represented in the first half`() {
        val rng = Random(2026)
        val order = IntArray(53) { it }
        FunctionLibrary.weightedShuffle(rng, order, -1)
        val head = (53 * 0.6f).toInt()
        val softInHead = order.take(head).count { FunctionLibrary.softs[it] >= 0.9f }
        val softInTail = order.drop(head).count { FunctionLibrary.softs[it] >= 0.9f }
        // 前 60% 中高 soft 占比应高于后 40%（加权生效的统计性证据，固定 seed 保证确定）
        assertTrue(
            "soft in head=$softInHead tail=$softInTail",
            softInHead.toFloat() / head > softInTail.toFloat() / (53 - head)
        )
    }
}

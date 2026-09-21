package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E37 分子频谱：分子库数据一致性校验。
 *
 * 渲染器直接消费 [MoleculeLibrary.ALL] 的索引/数组，任何长度或索引错误都会在
 * draw 期变成越界崩溃或错位绘制 —— 在单测层拦住（对应项目「清单错误由单测拦截」惯例）。
 */
class MoleculeLibraryTest {

    @Test
    fun `all molecules have consistent array lengths`() {
        MoleculeLibrary.ALL.forEach { def ->
            val n = def.elements.size
            assertEquals("${def.name}: coords 应为 n*2", n * 2, def.coords.size)
            assertEquals("${def.name}: bonds 应为 m*2", 0, def.bonds.size % 2)
            assertEquals(
                "${def.name}: bondOrders 数应等于键数",
                def.bonds.size / 2, def.bondOrders.size
            )
        }
    }

    @Test
    fun `all bond indices are valid atom indices`() {
        MoleculeLibrary.ALL.forEach { def ->
            val n = def.elements.size
            for (k in def.bonds.indices) {
                assertTrue(
                    "${def.name}: bond 索引 ${def.bonds[k]} 越界（原子数 $n）",
                    def.bonds[k] in 0 until n
                )
            }
        }
    }

    @Test
    fun `no self bonds and no duplicate bonds`() {
        MoleculeLibrary.ALL.forEach { def ->
            val seen = HashSet<Long>()
            for (k in 0 until def.bonds.size / 2) {
                val a = def.bonds[k * 2]
                val b = def.bonds[k * 2 + 1]
                assertTrue("${def.name}: 自键 ($a,$b)", a != b)
                val key = if (a < b) a.toLong() * 1000 + b else b.toLong() * 1000 + a
                assertTrue("${def.name}: 重复键 ($a,$b)", seen.add(key))
            }
        }
    }

    @Test
    fun `all coords are finite and molecule is not degenerate`() {
        MoleculeLibrary.ALL.forEach { def ->
            var maxR = 0f
            for (k in 0 until def.elements.size) {
                val x = def.coords[k * 2]
                val y = def.coords[k * 2 + 1]
                assertTrue("${def.name}: 坐标必须有限", x.isFinite() && y.isFinite())
                maxR = maxOf(maxR, kotlin.math.sqrt(x * x + y * y))
            }
            assertTrue("${def.name}: 分子外接半径过小", maxR > 10f)
        }
    }

    @Test
    fun `formula marks layout into at least one run`() {
        val measure = FormulaLayout.MeasureFn { text, size -> text.length * size * 0.6f }
        MoleculeLibrary.ALL.forEach { def ->
            val r = FormulaLayout.layout(def.formulaMark, 100f, 100f, 200f, 44f, measure)
            assertTrue("${def.name}: 公式排版 run 数 > 0", r.runCount > 0)
        }
    }

    @Test
    fun `band range partitions spectrum without gaps`() {
        val atomCount = MoleculeLibrary.ALL.maxOf { it.elements.size }
        val bandCount = 64
        var covered = 0
        for (i in 0 until atomCount) {
            val r = MoleculeRenderer.bandRange(i, atomCount, bandCount)
            assertEquals(r[0], covered)
            covered = r[1] + 1
        }
        assertEquals(bandCount, covered)
    }
}

package com.nasmusic.tv.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VisualizerRandom] 数值回归测试（P1#5，2026-09-14）。
 *
 * 伪随机源从 `VisualizerMath` 的进程级单例 `seed` 改为**每渲染器独立实例**后，
 * 随机行为才第一次变得可测——这是本次重构除「消除共享状态」之外的第二个收益。
 *
 * 覆盖：值域、确定性（同种子可复现）、独立性（不同种子不同序列）、LCG 不退化。
 * 全部为纯 JVM，无 Android 依赖。
 *
 * ⚠️ 本机 `testDebugUnitTest` 因 Gradle 测试 worker 环境问题无法运行（exit 268435466），
 * 这些用例**只验证了源码可编译**，实际通过与否须由 CI 判定。
 */
class VisualizerRandomTest {

    @Test
    fun `next stays within half open unit interval`() {
        val rng = VisualizerRandom(seed = 0x2F6E2B1u)
        repeat(10_000) {
            val v = rng.next()
            assertTrue("next() = $v 越界", v >= 0f && v < 1f)
        }
    }

    @Test
    fun `nextSigned stays within half open minus one to one`() {
        val rng = VisualizerRandom(seed = 0xABCDEFu)
        repeat(10_000) {
            val v = rng.nextSigned()
            assertTrue("nextSigned() = $v 越界", v >= -1f && v < 1f)
        }
    }

    @Test
    fun `same seed yields identical sequence`() {
        val a = VisualizerRandom(seed = 12345u)
        val b = VisualizerRandom(seed = 12345u)
        repeat(1_000) {
            assertEquals(a.next(), b.next(), 0f)
        }
    }

    @Test
    fun `different seeds yield different sequences`() {
        val a = VisualizerRandom(seed = 1u)
        val b = VisualizerRandom(seed = 2u)
        // 只比首个值：不同种子的 LCG 轨道本就不同，此处只需证明「不是同一个序列」
        assertNotEquals(a.next(), b.next(), 0f)
    }

    @Test
    fun `zero seed does not collapse to constant zero`() {
        // LCG 状态为 0 时序列恒为 0，构造时必须兜底（见 VisualizerRandom 实现）
        val rng = VisualizerRandom(seed = 0u)
        val first = rng.next()
        val values = HashSet<Float>()
        repeat(100) { values.add(rng.next()) }
        assertTrue("种子 0 退化成常量序列（first=$first，不同值个数=${values.size}）", values.size > 50)
    }

    @Test
    fun `sequence is reasonably uniform`() {
        // 粗粒度分布检查：10 桶，各桶占比应在合理区间（不检验严格均匀，只防严重偏斜）
        val rng = VisualizerRandom(seed = 0x9E3779B9u)
        val buckets = IntArray(10)
        val n = 100_000
        repeat(n) {
            val idx = (rng.next() * 10).toInt().coerceIn(0, 9)
            buckets[idx]++
        }
        val expected = n / buckets.size
        buckets.forEachIndexed { i, c ->
            val ratio = c.toFloat() / expected
            assertTrue("第 $i 桶偏斜过大（count=$c，expected=$expected，ratio=$ratio）",
                ratio in 0.85f..1.15f)
        }
    }
}

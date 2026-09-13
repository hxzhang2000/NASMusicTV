package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.visualizer.RenderContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E25 催眠 · 随机调度契约测试（§13.1 HypnoticScheduleTest）。
 *
 * 核心断言：**切歌（songId 变化）不重置调度与状态**——渲染器结构上不读
 * songId，同 seed 实例在任何 songId 下的调度序列完全一致。
 */
class HypnoticScheduleTest {

    private fun ctx(songId: String? = null): RenderContext = RenderContext().apply {
        this.songId = songId
    }

    /** 固定 seed 下的进入序列：new 实例 → onEnter → 读当前函数下标 */
    private fun firstFunctionOf(seed: Long, songId: String?): Int =
        HypnoticFunctionRenderer(seed).also { it.onEnter(ctx(songId)) }.currentIdx

    @Test
    fun `fixed seed produces deterministic sequence across instances`() {
        for (seed in listOf(1L, 42L, 0x4859_504E_01L)) {
            val a = firstFunctionOf(seed, null)
            val b = firstFunctionOf(seed, null)
            assertEquals("seed=$seed should be deterministic", a, b)
            assertTrue("idx must be valid", a in 0 until FunctionLibrary.ALL.size)
        }
    }

    @Test
    fun `song change does NOT reset phase or order`() {
        // 切歌 = 同一渲染器实例继续 draw，songId 变化不触发任何重置；
        // 调度序列只由构造期 seed 决定 —— songId 不同的两组实例序列完全一致。
        // （重新 onEnter 属于"重进效果"，会重置状态机，但那是用户主动操作，
        //   与切歌路径无关 —— 渲染器结构上不读 songId。）
        val seeds = listOf(1L, 777L, 20260913L)
        for (seed in seeds) {
            val seqA = mutableListOf<Int>()
            val seqB = mutableListOf<Int>()
            // 两次 onEnter（重进效果）的序列：A 歌与 B 歌下完全一致
            val ra = HypnoticFunctionRenderer(seed)
            ra.onEnter(ctx(songId = "song-A")); seqA.add(ra.currentIdx)
            ra.onEnter(ctx(songId = "song-A")); seqA.add(ra.currentIdx)
            val rb = HypnoticFunctionRenderer(seed)
            rb.onEnter(ctx(songId = "song-B")); seqB.add(rb.currentIdx)
            rb.onEnter(ctx(songId = "song-B")); seqB.add(rb.currentIdx)
            assertEquals("songId must not influence scheduling (seed=$seed)", seqA, seqB)
        }
    }

    @Test
    fun `different seeds diversify the first function`() {
        val firsts = (0L until 40L).map { firstFunctionOf(it, null) }
        // 40 个不同 seed 至少产生 5 种不同的首图（完全固定顺序才会只有 1 种）
        assertTrue("first functions too uniform: ${firsts.distinct().size}", firsts.distinct().size >= 5)
        // 不应全部相同
        assertNotEquals(1, firsts.distinct().size)
    }

    @Test
    fun `entering never repeats the immediately previous function across cycles`() {
        // 模拟多周期：onEnter 不重置 rng（成员 val），周期推进由 weightedShuffle 防重
        val rng = kotlin.random.Random(2026)
        val order = IntArray(53) { it }
        FunctionLibrary.weightedShuffle(rng, order, -1)
        for (cycle in 0 until 50) {
            val last = order[52]
            FunctionLibrary.weightedShuffle(rng, order, last)
            assertTrue("cycle $cycle repeats at boundary", order[0] != last)
            // 防重交换不破坏覆盖性
            assertEquals(53, order.distinct().size)
        }
    }
}

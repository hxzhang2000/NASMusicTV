package com.nasmusic.tv.ui.components

import com.nasmusic.tv.data.model.LyricsLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * findCurrentLyricLine 单元测试 —— 全屏可视化舞台顶部歌词行的二分查找。
 *
 * 该函数被 `derivedStateOf` 包裹，在播放期间随进度频繁求值；
 * 语义错误不会崩溃，但会让歌词行错位一整句，因此边界必须钉死。
 */
class FindCurrentLyricLineTest {

    private fun lines(vararg times: Long): List<LyricsLine> =
        times.mapIndexed { i, t -> LyricsLine(time = t, text = "line$i") }

    @Test
    fun `null and empty lyrics return -1`() {
        assertEquals(-1, findCurrentLyricLine(null, 10_000L))
        assertEquals(-1, findCurrentLyricLine(emptyList(), 10_000L))
    }

    @Test
    fun `progress before the first line returns -1`() {
        val l = lines(1_000L, 5_000L, 9_000L)
        assertEquals(-1, findCurrentLyricLine(l, 0L))
        assertEquals(-1, findCurrentLyricLine(l, 999L))
    }

    @Test
    fun `exact boundary belongs to the newer line`() {
        val l = lines(1_000L, 5_000L, 9_000L)
        assertEquals(0, findCurrentLyricLine(l, 1_000L))
        assertEquals(1, findCurrentLyricLine(l, 5_000L))
        assertEquals(2, findCurrentLyricLine(l, 9_000L))
    }

    @Test
    fun `progress between lines keeps the previous line`() {
        val l = lines(1_000L, 5_000L, 9_000L)
        assertEquals(0, findCurrentLyricLine(l, 1_001L))
        assertEquals(0, findCurrentLyricLine(l, 4_999L))
        assertEquals(1, findCurrentLyricLine(l, 5_001L))
        assertEquals(1, findCurrentLyricLine(l, 8_999L))   // 差 1ms 仍属第 2 句
        assertEquals(2, findCurrentLyricLine(l, 9_000L))
    }

    @Test
    fun `progress past the last line clamps to the last index`() {
        val l = lines(1_000L, 5_000L, 9_000L)
        assertEquals(2, findCurrentLyricLine(l, 9_001L))
        assertEquals(2, findCurrentLyricLine(l, 1_000_000L))
    }

    @Test
    fun `single line behaves like an on off switch`() {
        val l = lines(2_000L)
        assertEquals(-1, findCurrentLyricLine(l, 1_999L))
        assertEquals(0, findCurrentLyricLine(l, 2_000L))
        assertEquals(0, findCurrentLyricLine(l, 99_999L))
    }

    @Test
    fun `duplicate timestamps resolve to the last matching line`() {
        val l = lines(1_000L, 1_000L, 1_000L, 2_000L)
        assertEquals(2, findCurrentLyricLine(l, 1_000L))
        assertEquals(3, findCurrentLyricLine(l, 2_000L))
    }

    @Test
    fun `long lyrics stay fast and correct`() {
        // 1000 行，每行间隔 300ms；随机取 10000 个进度点求值
        val count = 1_000
        val l = List(count) { LyricsLine(time = it * 300L, text = "line$it") }

        val start = System.nanoTime()
        var mismatches = 0
        for (k in 0 until 10_000) {
            val progress = (k * 37L) % (count * 300L)
            val expected = (progress / 300L).toInt()
            if (findCurrentLyricLine(l, progress) != expected) mismatches++
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals("binary search must match the arithmetic answer", 0, mismatches)
        // 二分查找：10000 次查询应远快于线性扫描（线性扫描约 4~5ms 起步）
        assertTrue("10000 lookups took ${elapsedMs}ms", elapsedMs < 100)
    }
}

package com.nasmusic.tv.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * PlayHeatmapBuilder 聚合逻辑单测（F2-5）
 *
 * 覆盖：网格维度与星期对齐 / 未来日期留空 / 窗口外数据剔除 / 总量与活跃天数 /
 * 最长连续 / 峰值日 / 四分位分级 / 月份标签 / 与写入端 dateKey 口径一致。
 *
 * 全部固定时区（Asia/Shanghai）与固定结束时刻，避免依赖"今天"。
 */
class PlayHeatmapBuilderTest {

    private val tz = TimeZone.getTimeZone("Asia/Shanghai")

    /** 2026-09-17 是周四（已核对：与 2026-01-01 同为周四） */
    private val endMs = ms(2026, 9, 17, 12)

    private fun ms(y: Int, m: Int, d: Int, h: Int = 12): Long {
        val c = Calendar.getInstance(tz)
        c.clear()
        c.set(y, m - 1, d, h, 0, 0)
        return c.timeInMillis
    }

    private fun build(counts: Map<String, Int>, weeks: Int = PlayHeatmapBuilder.DEFAULT_WEEKS) =
        PlayHeatmapBuilder.build(counts, endMs, weeks, tz)

    private fun PlayHeatmap.day(key: String): HeatmapDay? =
        weeks.flatten().firstOrNull { it?.dateKey == key }

    // ---------- 网格结构 ----------

    @Test
    fun `grid is 53 columns by 7 rows`() {
        val h = build(emptyMap())
        assertEquals(53, h.weeks.size)
        assertTrue(h.weeks.all { it.size == 7 })
    }

    @Test
    fun `row index equals day of week`() {
        val h = build(emptyMap())
        // 2026-09-17 周四 → Calendar.DAY_OF_WEEK = 5 → 行 4（行 0 = 周日）
        val col = h.weeks.first { w -> w.any { it?.dateKey == "2026-09-17" } }
        assertEquals(4, col.indexOfFirst { it?.dateKey == "2026-09-17" })
    }

    @Test
    fun `future days in last column are null`() {
        val h = build(emptyMap())
        // 结束日是周四（行 4），同列的周五、周六尚未发生
        val lastCol = h.weeks.last()
        assertNotNull(lastCol[4])
        assertNull(lastCol[5])
        assertNull(lastCol[6])
    }

    @Test
    fun `window covers exactly 53 weeks ending at end day`() {
        val h = build(emptyMap())
        // 2026-09-17 所在周的周日 = 2026-09-13，往前 52 周 = 2025-09-14
        assertEquals("2025-09-14", h.startDateKey)
        assertEquals("2026-09-17", h.endDateKey)
    }

    // ---------- 数据口径 ----------

    @Test
    fun `empty counts yields empty heatmap`() {
        val h = build(emptyMap())
        assertEquals(0, h.totalPlays)
        assertEquals(0, h.activeDays)
        assertEquals(0, h.longestStreak)
        assertNull(h.bestDay)
        assertTrue(h.isEmpty)
        assertTrue(h.weeks.flatten().filterNotNull().all { it.count == 0 && it.level == 0 })
    }

    @Test
    fun `single day counts land on the right cell`() {
        val h = build(mapOf("2026-09-17" to 5))
        assertEquals(5, h.totalPlays)
        assertEquals(1, h.activeDays)
        assertEquals(5, h.bestDay?.count)
        assertEquals("2026-09-17", h.bestDay?.dateKey)
        assertEquals(5, h.day("2026-09-17")?.count)
        // 唯一非零值 → 三个四分位阈值都等于它 → 归为最浅一档
        assertEquals(1, h.day("2026-09-17")?.level)
    }

    @Test
    fun `data outside the window is ignored`() {
        val h = build(mapOf("2020-01-01" to 100, "2026-09-17" to 2))
        assertEquals(2, h.totalPlays)
        assertEquals(1, h.activeDays)
        assertEquals(2, h.maxCount)
    }

    @Test
    fun `month boundaries are aggregated across months and years`() {
        val counts = mapOf(
            "2025-12-31" to 1,
            "2026-01-01" to 1,
            "2026-01-02" to 1
        )
        val h = build(counts)
        assertEquals(3, h.totalPlays)
        assertEquals(3, h.activeDays)
        assertEquals(3, h.longestStreak) // 跨年不断连
    }

    // ---------- 摘要指标 ----------

    @Test
    fun `longest streak counts consecutive days only`() {
        val counts = mapOf(
            "2026-09-10" to 1, // 孤立一天
            "2026-09-14" to 1,
            "2026-09-15" to 1,
            "2026-09-16" to 2,
            "2026-09-17" to 1
        )
        val h = build(counts)
        assertEquals(5, h.activeDays)
        assertEquals(4, h.longestStreak)
    }

    @Test
    fun `best day picks the peak count`() {
        val h = build(mapOf("2026-09-15" to 7, "2026-09-16" to 3))
        assertEquals("2026-09-15", h.bestDay?.dateKey)
        assertEquals(7, h.bestDay?.count)
        assertEquals(7, h.maxCount)
    }

    @Test
    fun `peak count ties keep the earliest day`() {
        val h = build(mapOf("2026-09-15" to 4, "2026-09-16" to 4))
        assertEquals("2026-09-15", h.bestDay?.dateKey)
    }

    // ---------- 颜色分级 ----------

    @Test
    fun `levels follow quartiles of positive counts`() {
        // 非零值排序 [1, 2, 3, 100] → 四分位阈值 [1, 2, 3]
        val h = build(
            mapOf(
                "2026-09-14" to 1,
                "2026-09-15" to 2,
                "2026-09-16" to 3,
                "2026-09-17" to 100
            )
        )
        assertEquals(1, h.day("2026-09-14")?.level)
        assertEquals(2, h.day("2026-09-15")?.level)
        assertEquals(3, h.day("2026-09-16")?.level)
        assertEquals(PlayHeatmapBuilder.LEVELS, h.day("2026-09-17")?.level)
    }

    @Test
    fun `uniform counts all fall into the lightest level`() {
        val h = build(mapOf("2026-09-15" to 1, "2026-09-16" to 1, "2026-09-17" to 1))
        assertEquals(1, h.day("2026-09-15")?.level)
        assertEquals(1, h.day("2026-09-17")?.level)
    }

    @Test
    fun `levelOf handles zero and negative counts`() {
        val thresholds = intArrayOf(1, 2, 3)
        assertEquals(0, PlayHeatmapBuilder.levelOf(0, thresholds))
        assertEquals(0, PlayHeatmapBuilder.levelOf(-5, thresholds))
        assertEquals(4, PlayHeatmapBuilder.levelOf(999, thresholds))
    }

    // ---------- 月份标签 ----------

    @Test
    fun `month labels cover every month in the window`() {
        val h = build(emptyMap())
        // 窗口 2025-09-14 ~ 2026-09-17 → 覆盖 2025-09 … 2026-09 共 13 个月
        assertEquals(13, h.monthLabels.size)
        assertEquals("9月", h.monthLabels.first().text)
        assertEquals("9月", h.monthLabels.last().text)
        // 列号递增且不重叠
        val cols = h.monthLabels.map { it.weekIndex }
        assertEquals(cols.sorted(), cols)
        assertTrue(cols.zipWithNext().all { (a, b) -> b - a >= 3 })
    }

    @Test
    fun `month label points at the first column of that month`() {
        val h = build(emptyMap())
        val oct = h.monthLabels.first { it.text == "10月" }
        val col = h.weeks[oct.weekIndex]
        val firstOfCol = col.firstOrNull { it != null }
        // 该列第一个有效日期应落在 10 月
        assertTrue(firstOfCol!!.dateKey.startsWith("2025-10"))
    }

    @Test
    fun `short single week window still produces one label`() {
        val h = build(emptyMap(), weeks = 1)
        assertEquals(1, h.weeks.size)
        assertEquals(1, h.monthLabels.size)
        assertEquals(0, h.monthLabels.first().weekIndex)
    }

    // ---------- 与写入端口径一致 ----------

    @Test
    fun `date key format matches repository currentDay`() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(tz)
            // 写入端 PlayStatsRepository.currentDay 与读取端 builder 的 dateKey 必须同口径，
            // 否则统计写得进去、热力图读不出来（全空）
            assertEquals(
                PlayStatsRepository.currentDay(endMs),
                build(emptyMap()).endDateKey
            )
            // 当天 23:59 也不能漂到第二天
            assertEquals("2026-09-17", PlayStatsRepository.currentDay(ms(2026, 9, 17, 23)))
        } finally {
            TimeZone.setDefault(old)
        }
    }
}

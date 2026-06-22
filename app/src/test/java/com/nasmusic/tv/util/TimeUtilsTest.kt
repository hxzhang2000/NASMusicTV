package com.nasmusic.tv.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TimeUtils 单元测试
 */
class TimeUtilsTest {

    @Test
    fun `formatDuration zero returns 0_00`() {
        assertEquals("0:00", TimeUtils.formatDuration(0L))
        assertEquals("0:00", TimeUtils.formatDuration(-1L))
    }

    @Test
    fun `formatDuration less than one minute`() {
        assertEquals("0:01", TimeUtils.formatDuration(1_000L))
        assertEquals("0:30", TimeUtils.formatDuration(30_000L))
        assertEquals("0:59", TimeUtils.formatDuration(59_999L))
    }

    @Test
    fun `formatDuration exactly one minute`() {
        assertEquals("1:00", TimeUtils.formatDuration(60_000L))
    }

    @Test
    fun `formatDuration minutes and seconds`() {
        assertEquals("1:30", TimeUtils.formatDuration(90_000L))
        assertEquals("3:45", TimeUtils.formatDuration(225_000L))
    }

    @Test
    fun `formatDuration hours`() {
        assertEquals("60:00", TimeUtils.formatDuration(3_600_000L))
        assertEquals("90:05", TimeUtils.formatDuration(5_405_000L))
    }

    @Test
    fun `formatDuration trims milliseconds`() {
        assertEquals("2:15", TimeUtils.formatDuration(135_900L))
    }

    @Test
    fun `formatDurationWithMillis zero`() {
        assertEquals("0:00.000", TimeUtils.formatDurationWithMillis(0L))
    }

    @Test
    fun `formatDurationWithMillis less than one minute`() {
        assertEquals("0:01.500", TimeUtils.formatDurationWithMillis(1_500L))
        assertEquals("0:30.050", TimeUtils.formatDurationWithMillis(30_050L))
    }

    @Test
    fun `formatDurationWithMillis with exact values`() {
        assertEquals("1:00.000", TimeUtils.formatDurationWithMillis(60_000L))
        assertEquals("1:30.750", TimeUtils.formatDurationWithMillis(90_750L))
        assertEquals("3:45.100", TimeUtils.formatDurationWithMillis(225_100L))
    }

    @Test
    fun `formatDurationWithMillis with single-digit millis`() {
        assertEquals("0:00.005", TimeUtils.formatDurationWithMillis(5L))
        assertEquals("0:00.010", TimeUtils.formatDurationWithMillis(10L))
    }

    @Test
    fun `formatDurationWithMillis with hours`() {
        assertEquals("60:00.000", TimeUtils.formatDurationWithMillis(3_600_000L))
    }
}

package com.nasmusic.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BandwidthEstimator 单测（F2-6）：滑动窗口 / 清零 / 档位映射。
 */
class BandwidthEstimatorTest {

    private class FakeClock(var now: Long = 0L)

    private fun estimator(clock: FakeClock) =
        BandwidthEstimator(windowMs = 30_000, maxSamples = 8, nowMsProvider = { clock.now })

    @Test
    fun `empty window returns null`() {
        assertNull(estimator(FakeClock()).estimateBps())
    }

    @Test
    fun `simple bps calculation`() {
        val clock = FakeClock()
        val e = estimator(clock)
        // 1MB in 1s = 8Mbps
        e.record(bytes = 1_000_000, durationMs = 1000)
        assertEquals(8_000_000L, e.estimateBps())
    }

    @Test
    fun `multiple samples accumulate`() {
        val clock = FakeClock()
        val e = estimator(clock)
        e.record(bytes = 500_000, durationMs = 500)   // 8Mbps
        e.record(bytes = 500_000, durationMs = 500)   // 8Mbps
        assertEquals(8_000_000L, e.estimateBps())
    }

    @Test
    fun `expired samples evicted`() {
        val clock = FakeClock()
        val e = estimator(clock)
        e.record(bytes = 100, durationMs = 1000)      // 老样本
        clock.now = 60_000                             // 窗口外
        assertNull(e.estimateBps())
    }

    @Test
    fun `max samples capped`() {
        val clock = FakeClock()
        val e = estimator(clock)
        repeat(20) { e.record(bytes = 1000, durationMs = 100) }
        // 上限 8 条：8000 bytes / 800ms = 80kbps
        assertEquals(80_000L, e.estimateBps())
    }

    @Test
    fun `reset clears window`() {
        val clock = FakeClock()
        val e = estimator(clock)
        e.record(bytes = 1_000_000, durationMs = 1000)
        e.reset()
        assertNull(e.estimateBps())
    }

    @Test
    fun `invalid samples ignored`() {
        val e = estimator(FakeClock())
        e.record(bytes = 0, durationMs = 100)
        e.record(bytes = 100, durationMs = 0)
        e.record(bytes = -5, durationMs = -1)
        assertNull(e.estimateBps())
    }

    @Test
    fun `tier mapping thresholds`() {
        assertEquals(999, BandwidthEstimator.tierForBandwidth(20_000_000))
        assertEquals(999, BandwidthEstimator.tierForBandwidth(10_000_001))
        assertEquals(320, BandwidthEstimator.tierForBandwidth(10_000_000))
        assertEquals(320, BandwidthEstimator.tierForBandwidth(2_000_001))
        assertEquals(128, BandwidthEstimator.tierForBandwidth(2_000_000))
        assertEquals(128, BandwidthEstimator.tierForBandwidth(100_000))
        // 无数据保守 320
        assertEquals(320, BandwidthEstimator.tierForBandwidth(null))
    }
}

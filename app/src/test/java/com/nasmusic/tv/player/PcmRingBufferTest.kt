package com.nasmusic.tv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PCM 环形缓冲（P6 降级通道的跨线程交接点）。
 *
 * 关注三件事：**取到的是最新窗口**、**未填满时不返回脏数据**、
 * **version 能让读方跳过无新数据的帧**（暂停时不反复分析同一段）。
 */
class PcmRingBufferTest {

    @Test
    fun `readLatest returns the most recent samples in time order`() {
        val ring = PcmRingBuffer(64)
        val src = FloatArray(20) { it.toFloat() }
        ring.write(src, 20)

        val dst = FloatArray(10)
        assertTrue(ring.readLatest(dst))
        // 最近 10 个 = src[10..19]，按时间顺序
        assertArrayEquals(FloatArray(10) { (it + 10).toFloat() }, dst, 0f)
    }

    @Test
    fun `readLatest fails until enough samples accumulated`() {
        val ring = PcmRingBuffer(64)
        ring.write(FloatArray(5) { 1f }, 5)

        val dst = FloatArray(10) { -1f }
        assertFalse(ring.readLatest(dst))
        assertTrue("数据不足时必须清零，绝不能泄漏旧值", dst.all { it == 0f })
    }

    @Test
    fun `writes wrap around and keep the newest window`() {
        val ring = PcmRingBuffer(8)
        ring.write(FloatArray(8) { 1f }, 8)
        ring.write(FloatArray(8) { 2f }, 8)

        val dst = FloatArray(8)
        assertTrue(ring.readLatest(dst))
        assertArrayEquals(FloatArray(8) { 2f }, dst, 0f)
    }

    @Test
    fun `write longer than capacity keeps the tail`() {
        val ring = PcmRingBuffer(8)
        val src = FloatArray(12) { it.toFloat() }
        ring.write(src, 12)

        val dst = FloatArray(8)
        assertTrue(ring.readLatest(dst))
        assertArrayEquals(FloatArray(8) { (it + 4).toFloat() }, dst, 0f)
    }

    @Test
    fun `version advances on every write so readers can skip stale frames`() {
        val ring = PcmRingBuffer(16)
        val v0 = ring.version()
        ring.write(FloatArray(4), 4)
        val v1 = ring.version()
        ring.write(FloatArray(4), 4)
        val v2 = ring.version()

        assertTrue(v1 > v0)
        assertTrue(v2 > v1)
    }

    @Test
    fun `clear drops content and resets version`() {
        val ring = PcmRingBuffer(16)
        ring.write(FloatArray(16) { 3f }, 16)
        ring.clear()

        assertEquals(0, ring.available())
        assertEquals(0L, ring.version())
        assertFalse(ring.readLatest(FloatArray(4)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non power of two capacity is rejected`() {
        PcmRingBuffer(100)
    }
}

package com.nasmusic.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * 自实现 radix-2 FFT 的数值正确性（P6 降级通道的核心）。
 *
 * 这些断言是「PCM 通道能不能用」的前提 —— FFT 错了，降级后的频谱就是垃圾。
 */
class Radix2FftTest {

    private val n = 1024
    private val rate = 44100

    @Test
    fun `single tone peaks at the matching bin`() {
        val targetBin = 64
        val input = FloatArray(n) { i -> sin(2.0 * Math.PI * targetBin * i / n).toFloat() }
        val out = FloatArray(n / 2)

        Radix2Fft(n).magnitude(input, out)

        var peak = 1
        for (i in 1 until out.size) if (out[i] > out[peak]) peak = i
        assertEquals("峰值必须落在激励频率对应的 bin 上", targetBin, peak)

        // 整周期采样 + 矩形窗：理想幅值 = N/2 × 幅度
        assertEquals(n / 2f, out[targetBin], n / 2f * 0.02f)
    }

    @Test
    fun `bass tone lands in the low frequency half`() {
        // 100Hz @44100 / 1024 → bin ≈ 2.32 → 能量集中在 bin 2
        val input = FloatArray(n) { i -> sin(2.0 * Math.PI * 100.0 * i / rate).toFloat() }
        val out = FloatArray(n / 2)

        Radix2Fft(n).magnitude(input, out)

        var peak = 1
        for (i in 1 until 20) if (out[i] > out[peak]) peak = i
        assertTrue("100Hz 应落在前几个 bin，实际 $peak", peak in 1..4)
    }

    @Test
    fun `dc input peaks at bin zero`() {
        val input = FloatArray(n) { 1f }
        val out = FloatArray(n / 2)

        Radix2Fft(n).magnitude(input, out)

        assertEquals(n.toFloat(), out[0], n * 0.01f)
        for (i in 1 until out.size) {
            assertEquals("直流以外的 bin 应接近 0", 0f, out[i], 1e-3f)
        }
    }

    @Test
    fun `zero input yields all zero magnitudes`() {
        val out = FloatArray(n / 2) { 7f }
        Radix2Fft(n).magnitude(FloatArray(n), out)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `output buffer larger than half size is zero padded`() {
        val out = FloatArray(n) { 9f }
        val input = FloatArray(n) { i -> sin(2.0 * Math.PI * 32 * i / n).toFloat() }

        Radix2Fft(n).magnitude(input, out)

        assertTrue(out[32] > 0f)
        for (i in n / 2 until n) assertEquals(0f, out[i], 0f)
    }

    @Test
    fun `input shorter than size is treated as zero padded`() {
        val out = FloatArray(n / 2)
        Radix2Fft(n).magnitude(FloatArray(100) { 1f }, out)
        // 只有前 100 点非零 → 仍应算出有限值而不崩溃
        assertTrue(out.all { it.isFinite() })
        assertTrue(out[0] > 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non power of two size is rejected`() {
        Radix2Fft(1000)
    }
}

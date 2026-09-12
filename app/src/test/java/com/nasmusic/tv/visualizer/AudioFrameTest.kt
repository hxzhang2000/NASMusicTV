package com.nasmusic.tv.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioFrame 契约测试。
 *
 * 这是分析层与渲染层之间**唯一**的数据契约（§2.2 三条铁律），
 * 数组长度或重置语义一旦漂移，20 套渲染器会集体失准。
 */
class AudioFrameTest {

    @Test
    fun `array sizes follow the contract`() {
        val f = AudioFrame(SpectrumContract.BAR_COUNT, SpectrumContract.WAVE_POINTS)
        assertEquals(64, SpectrumContract.BAR_COUNT)
        assertEquals(128, SpectrumContract.WAVE_POINTS)
        assertEquals(64, f.spectrum.size)
        assertEquals(128, f.waveform.size)
    }

    @Test
    fun `fresh frame is fully zeroed`() {
        val f = AudioFrame(SpectrumContract.BAR_COUNT, SpectrumContract.WAVE_POINTS)
        assertTrue(f.spectrum.all { it == 0f })
        assertTrue(f.waveform.all { it == 0f })
        assertEquals(0f, f.bass, 0f)
        assertEquals(0f, f.mid, 0f)
        assertEquals(0f, f.treble, 0f)
        assertEquals(0f, f.energy, 0f)
        assertEquals(0f, f.sectionEnergy, 0f)
        assertEquals(0f, f.pulse, 0f)
        assertEquals(0f, f.bpm, 0f)
        assertEquals(false, f.beat)
    }

    @Test
    fun `reset zeroes analysis fields but preserves monotonic meta`() {
        val f = AudioFrame(SpectrumContract.BAR_COUNT, SpectrumContract.WAVE_POINTS)
        f.spectrum[7] = 0.9f
        f.waveform[3] = -0.5f
        f.bass = 0.8f
        f.mid = 0.7f
        f.treble = 0.6f
        f.energy = 0.5f
        f.sectionEnergy = 0.4f
        f.pulse = 0.3f
        f.bpm = 128f
        f.beat = true
        f.timeMs = 1_234_567L
        f.seq = 42L

        f.reset()

        // 分析字段全部归零
        assertTrue(f.spectrum.all { it == 0f })
        assertTrue(f.waveform.all { it == 0f })
        assertEquals(0f, f.bass, 0f)
        assertEquals(0f, f.mid, 0f)
        assertEquals(0f, f.treble, 0f)
        assertEquals(0f, f.energy, 0f)
        assertEquals(0f, f.sectionEnergy, 0f)
        assertEquals(0f, f.pulse, 0f)
        assertEquals(0f, f.bpm, 0f)
        assertEquals(false, f.beat)

        // 单调元信息刻意保留：AutoDirector 的最小驻留依赖 timeMs 单调性，
        // 归零会让切歌后的自动导演冻结约 8s（见 AudioFrame.reset KDoc）。
        assertEquals(1_234_567L, f.timeMs)
        assertEquals(42L, f.seq)
    }

    @Test
    fun `bandMean averages the inclusive range`() {
        val f = AudioFrame(SpectrumContract.BAR_COUNT, SpectrumContract.WAVE_POINTS)
        for (i in 0 until 64) f.spectrum[i] = i / 63f

        assertEquals(f.spectrum[0], f.bandMean(0, 0), 1e-6f)
        assertEquals(f.spectrum[63], f.bandMean(63, 63), 1e-6f)
        // 全量均值 = 0..63 的均匀分布均值 = 0.5
        assertEquals(0.5f, f.bandMean(0, 63), 1e-4f)
    }

    @Test
    fun `bandMean tolerates inverted range`() {
        val f = AudioFrame(SpectrumContract.BAR_COUNT, SpectrumContract.WAVE_POINTS)
        assertEquals(0f, f.bandMean(10, 9), 0f)
    }
}

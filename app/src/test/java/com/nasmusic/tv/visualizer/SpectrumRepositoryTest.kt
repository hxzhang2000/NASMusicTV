package com.nasmusic.tv.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpectrumRepository 契约测试 —— [AudioFrame] 的唯一写入方。
 *
 * 重点覆盖 BUG ⑬ 的修复语义：**发布的是帧序号，不是数组副本**。
 * 若有人把这里改回 `StateFlow<FloatArray>`，本测试的节流用例会立刻失败。
 */
class SpectrumRepositoryTest {

    private val bars = FloatArray(SpectrumContract.BAR_COUNT)
    private val wave = FloatArray(SpectrumContract.WAVE_POINTS)

    private fun feed(repo: SpectrumRepository, nowMs: Long, bass: Float = 0.5f) {
        for (i in 0 until SpectrumContract.BAR_COUNT) bars[i] = bass
        repo.onFrame(bars, bars, wave, nowMs)
    }

    @Test
    fun `band split matches the 64-bar boundaries`() {
        val repo = SpectrumRepository()
        fun setBand(bass: Float, mid: Float, treble: Float) {
            for (i in 0 until 64) {
                bars[i] = when {
                    i <= SpectrumContract.BASS_END -> bass      // 0..39
                    i <= SpectrumContract.MID_END -> mid        // 40..55
                    else -> treble                              // 56..63
                }
            }
        }

        // 帧1：各段建立峰值（动态范围增强后首帧即触峰值 → 全 1.0）
        setBand(0.4f, 0.6f, 0.8f)
        repo.onFrame(bars, bars, wave, 1_000L)
        assertEquals(1f, repo.frame.bass, 1e-3f)
        assertEquals(1f, repo.frame.mid, 1e-3f)
        assertEquals(1f, repo.frame.treble, 1e-3f)

        // 帧2：仅 bass 段降到 0.2 → 只有 bass 通道下降，验证频段边界
        setBand(0.2f, 0.6f, 0.8f)
        repo.onFrame(bars, bars, wave, 1_020L)
        assertEquals(0.508f, repo.frame.bass, 5e-3f)   // 0.2 / (0.4×0.985)
        assertEquals(1f, repo.frame.mid, 5e-3f)        // 0.6 仍触峰值
        assertEquals(1f, repo.frame.treble, 5e-3f)
    }

    @Test
    fun `frameSeq advances only at the render throttle`() {
        val repo = SpectrumRepository()

        // t=1000 距 lastEmitMs(0) 已超过 33ms → 发布
        feed(repo, 1_000L)
        assertEquals(1L, repo.frameSeq)
        assertEquals(1L, repo.frame.seq)

        // 10ms 后仍在节流窗口内 → 帧数据已更新，但序号不发布（渲染层无需重建）
        feed(repo, 1_010L)
        assertEquals("inside throttle window frameSeq must not advance", 1L, repo.frameSeq)
        assertEquals("but the frame itself is still refreshed", 2L, repo.frame.seq)

        // 40ms > 33ms → 发布
        feed(repo, 1_040L)
        assertEquals(3L, repo.frameSeq)
    }

    @Test
    fun `linear channel drives the rhythm bands`() {
        val repo = SpectrumRepository()
        val display = FloatArray(SpectrumContract.BAR_COUNT) { 0.9f }
        val linear = FloatArray(SpectrumContract.BAR_COUNT) { 0.2f }
        repo.onFrame(display, linear, wave, 1_000L)

        // spectrum 走显示通道（原值 0.9）
        assertEquals(0.9f, repo.frame.spectrum[10], 1e-4f)
        // 律动通道走线性输入：首帧 0.2 即峰值 → 1.0
        assertEquals(1f, repo.frame.bass, 1e-4f)

        // 帧2：线性降到 0.1 → bass 按线性比例回落（若误用 display 通道则仍触峰值 1.0）
        val linearLow = FloatArray(SpectrumContract.BAR_COUNT) { 0.1f }
        repo.onFrame(display, linearLow, wave, 1_020L)
        assertEquals(0.508f, repo.frame.bass, 5e-3f)   // 0.1 / (0.2×0.985)
        assertEquals(0.9f, repo.frame.spectrum[10], 1e-4f)  // display 通道原样保留
    }

    @Test
    fun `null linear bars fall back to the display channel`() {
        val repo = SpectrumRepository()
        for (i in 0 until 64) bars[i] = 0.3f
        repo.onFrame(bars, null, wave, 1_000L)
        assertEquals(1f, repo.frame.bass, 1e-4f)

        // 帧2：bars 降到 0.15 → 律动随显示通道回落（证明 fallback 生效）
        for (i in 0 until 64) bars[i] = 0.15f
        repo.onFrame(bars, null, wave, 1_020L)
        assertEquals(0.508f, repo.frame.bass, 5e-3f)
        assertEquals(0.508f, repo.frame.mid, 5e-3f)
        assertEquals(0.508f, repo.frame.treble, 5e-3f)
    }

    @Test
    fun `waveform is copied when provided`() {
        val repo = SpectrumRepository()
        for (i in 0 until SpectrumContract.WAVE_POINTS) wave[i] = if (i % 2 == 0) 1f else -1f
        repo.onFrame(bars, bars, wave, 1_000L)

        assertEquals(1f, repo.frame.waveform[0], 1e-4f)
        assertEquals(-1f, repo.frame.waveform[1], 1e-4f)
    }

    @Test
    fun `reset clears frame and re-arms the throttle`() {
        val repo = SpectrumRepository()
        feed(repo, 1_000L)
        feed(repo, 1_040L)
        assertTrue(repo.frameSeq > 0L)

        repo.reset()

        assertEquals(0L, repo.frameSeq)
        assertEquals(0L, repo.frame.seq)
        assertEquals(0f, repo.frame.bass, 0f)
        assertTrue(repo.frame.spectrum.all { it == 0f })
        assertEquals(0f, repo.peakHold.peaks[10], 1e-6f)

        // reset 后 lastEmitMs 归零 → 下一帧立即发布
        feed(repo, 2_000L)
        assertEquals(1L, repo.frameSeq)
    }
}

package com.nasmusic.tv.player

import com.nasmusic.tv.visualizer.SpectrumContract
import com.nasmusic.tv.visualizer.SpectrumRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PCM 频谱通道（v2.37.6 起为唯一通道）共用分析链的契约。
 *
 * 关注两点：
 *  ① PCM 通道复用与旧 Visualizer 通道完全相同的 AGC / 柱映射 / 双通道输出；
 *  ② PCM 的静音判定用绝对值（数字静音即真静音）——
 *    不走旧 Visualizer 的自适应噪声门限（那套门限是给设备底噪准备的，
 *    会把小信号整段吞掉）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SpectrumAnalyzerPcmTest {

    private companion object {
        const val BINS = 512
        const val RATE = 44100
        const val LOW_BAR = 11
    }

    private fun analyzer(): Pair<SpectrumAnalyzer, SpectrumRepository> {
        val a = SpectrumAnalyzer()
        val repo = SpectrumRepository()
        a.repository = repo
        return a to repo
    }

    /** 构造幅值谱：把能量放在低频 bin 2..4（与 SpectrumAnalyzerTest 的落点一致） */
    private fun magnitudes(value: Float): FloatArray {
        val m = FloatArray(BINS)
        for (bin in 2..4) m[bin] = value
        return m
    }

    @Test
    fun `pcm frames go through the shared pipeline`() {
        val (a, repo) = analyzer()
        a.analyze(magnitudes(264f), BINS, RATE)

        // 首帧 AGC 分母即本帧低频峰值 → 归一化为 1.0
        assertTrue(
            "PCM 通道必须落到同一套 AGC / 柱映射上，实际 ${repo.frame.spectrum[LOW_BAR]}",
            repo.frame.spectrum[LOW_BAR] > 0.95f
        )
        assertEquals(SpectrumContract.BAR_COUNT, repo.frame.spectrum.size)
    }

    @Test
    fun `pcm amplitude range retains dynamic range`() {
        val (a, repo) = analyzer()
        // 重 → 轻：与视觉通道一样必须保留动态范围
        a.analyze(magnitudes(264f), BINS, RATE)
        val heavy = repo.frame.spectrum[LOW_BAR]
        a.analyze(magnitudes(66f), BINS, RATE)
        val light = repo.frame.spectrum[LOW_BAR]

        assertTrue("重信号应接近满格，实际 $heavy", heavy > 0.95f)
        assertTrue("轻信号必须明显更低，实际 $light", light < heavy * 0.75f)
    }

    @Test
    fun `pcm silence writes a length-correct all-zero frame`() {
        val (a, repo) = analyzer()
        a.analyze(magnitudes(264f), BINS, RATE)
        a.analyze(FloatArray(BINS), BINS, RATE)

        assertEquals(SpectrumContract.BAR_COUNT, repo.frame.spectrum.size)
        assertTrue(repo.frame.spectrum.all { it == 0f })
        assertTrue(repo.frame.waveform.all { it == 0f })
    }

    @Test
    fun `small pcm signal is visible on the single channel`() {
        // 旧版这里对比 Visualizer（被噪声门限吞掉）vs PCM（可见）——
        // v2.37.6 起只有 PCM 一条通道，断言意图收敛为：小信号必须照常出图。
        val (a, repo) = analyzer()
        a.analyze(magnitudes(0.5f), BINS, RATE)
        assertTrue(
            "小信号在唯一通道必须可见，实际 ${repo.frame.spectrum[LOW_BAR]}",
            repo.frame.spectrum[LOW_BAR] > 0f
        )
    }

    @Test
    fun `degenerate bin counts are ignored instead of crashing`() {
        val (a, repo) = analyzer()
        a.analyze(FloatArray(0), 0, RATE)
        a.analyze(FloatArray(1), 1, RATE)
        assertEquals(SpectrumContract.BAR_COUNT, repo.frame.spectrum.size)
    }
}

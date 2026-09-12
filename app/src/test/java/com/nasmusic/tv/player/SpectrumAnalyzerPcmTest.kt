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
 * PCM 降级通道共用分析链的契约（P6）。
 *
 * 关注两点：
 *  ① PCM 通道必须复用与 Visualizer 完全相同的 AGC / 柱映射 / 双通道输出；
 *  ② PCM 的静音判定**不能**沿用系统 Visualizer 的自适应噪声门限 ——
 *    那套门限是给设备底噪准备的，会把小信号整段吞掉。
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
        a.analyze(magnitudes(264f), BINS, RATE, fromPcm = true)

        // 首帧 AGC 分母即本帧低频峰值 → 归一化为 1.0
        assertTrue(
            "PCM 通道必须落到同一套 AGC / 柱映射上，实际 ${repo.frame.spectrum[LOW_BAR]}",
            repo.frame.spectrum[LOW_BAR] > 0.95f
        )
        assertEquals(SpectrumContract.BAR_COUNT, repo.frame.spectrum.size)
    }

    @Test
    fun `pcm amplitude range matches the visualizer channel`() {
        val (a, repo) = analyzer()
        // 重 → 轻：与视觉通道一样必须保留动态范围
        a.analyze(magnitudes(264f), BINS, RATE, fromPcm = true)
        val heavy = repo.frame.spectrum[LOW_BAR]
        a.analyze(magnitudes(66f), BINS, RATE, fromPcm = true)
        val light = repo.frame.spectrum[LOW_BAR]

        assertTrue("重信号应接近满格，实际 $heavy", heavy > 0.95f)
        assertTrue("轻信号必须明显更低，实际 $light", light < heavy * 0.75f)
    }

    @Test
    fun `pcm silence writes a length-correct all-zero frame`() {
        val (a, repo) = analyzer()
        a.analyze(magnitudes(264f), BINS, RATE, fromPcm = true)
        a.analyze(FloatArray(BINS), BINS, RATE, fromPcm = true)

        assertEquals(SpectrumContract.BAR_COUNT, repo.frame.spectrum.size)
        assertTrue(repo.frame.spectrum.all { it == 0f })
        assertTrue(repo.frame.waveform.all { it == 0f })
    }

    @Test
    fun `pcm channel is not gated by the adaptive noise floor`() {
        // 同一份小信号：Visualizer 通道被噪声门限判定为静音，PCM 通道必须照常出图，
        // 否则降级后的画面会比降级前更"死"。
        val (pv, repoVisual) = analyzer()
        pv.analyze(magnitudes(0.5f), BINS, RATE, fromPcm = false)
        assertTrue(
            "小信号在 Visualizer 通道应被门限吞掉（前提）",
            repoVisual.frame.spectrum.all { it == 0f }
        )

        val (pp, repoPcm) = analyzer()
        pp.analyze(magnitudes(0.5f), BINS, RATE, fromPcm = true)
        assertTrue(
            "小信号在 PCM 通道必须可见，实际 ${repoPcm.frame.spectrum[LOW_BAR]}",
            repoPcm.frame.spectrum[LOW_BAR] > 0f
        )
    }

    @Test
    fun `degenerate bin counts are ignored instead of crashing`() {
        val (a, repo) = analyzer()
        a.analyze(FloatArray(0), 0, RATE, fromPcm = true)
        a.analyze(FloatArray(1), 1, RATE, fromPcm = true)
        assertEquals(SpectrumContract.BAR_COUNT, repo.frame.spectrum.size)
    }
}

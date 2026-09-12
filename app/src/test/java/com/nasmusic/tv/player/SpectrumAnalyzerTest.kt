package com.nasmusic.tv.player

import com.nasmusic.tv.visualizer.SpectrumContract
import com.nasmusic.tv.visualizer.SpectrumRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.pow

/**
 * SpectrumAnalyzer 数据层测试 —— 覆盖历史 BUG ⑨ / ⑨-b / ⑭ 与 ① 的柱数契约。
 *
 * 直接驱动 `processFft`（internal，供测试开放），不经过 Android [android.media.audiofx.Visualizer]
 * 回调——Robolectric 无法产生真实 FFT 数据，而本文件的关注点全部在纯数值链路。
 *
 * **FFT bin → 柱映射**（采样率 44100、512 bins、freqPerBin ≈ 43.07Hz）：
 *   bin 2 (86.1Hz)  → 柱 11，战区增益 2.2
 *   bin 3 (129.2Hz) → 柱 18，战区增益 2.2
 *   bin 4 (172.3Hz) → 柱 25，战区增益 2.2
 * 三根柱全部落在低频区（柱 10..39），正好用来操纵 AGC 分母。
 *
 * 注意 FFT 字节按**有符号**解读（`Byte.toFloat()`），故幅值上限 127；
 * 本测试用 120 / 30 两个量级，避免溢出为负值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SpectrumAnalyzerTest {

    private companion object {
        const val BINS = 512
        const val RATE = 44100

        /** 重鼓点幅值 → 柱值 120 × 2.2 = 264 */
        const val HEAVY: Byte = 120

        /** 轻鼓点幅值 → 柱值 30 × 2.2 = 66，与重鼓点相差 4 倍 */
        const val LIGHT: Byte = 30

        /** 80–200Hz 战区增益 */
        const val WEIGHT = 2.2f

        /** 承载低频能量的柱索引之一（bin 2 落点） */
        const val LOW_BAR = 11

        /** 低频区柱数（0..39） */
        const val BASS_BARS = 40
    }

    private fun fft(magnitude: Byte): ByteArray {
        val b = ByteArray(BINS * 2)
        for (bin in 2..4) {
            b[bin * 2] = magnitude
            b[bin * 2 + 1] = 0
        }
        return b
    }

    private fun analyzer(): Pair<SpectrumAnalyzer, SpectrumRepository> {
        val a = SpectrumAnalyzer()
        val repo = SpectrumRepository()
        a.repository = repo
        return a to repo
    }

    // ───────────────────────── ① 柱数契约 ─────────────────────────

    @Test
    fun `bar count contract is 64 for both analyzer and shared contract`() {
        assertEquals(64, SpectrumContract.BAR_COUNT)
        assertEquals(SpectrumContract.BAR_COUNT, SpectrumAnalyzer.BAR_COUNT)
    }

    @Test
    fun `processed spectrum always has BAR_COUNT entries`() {
        val (a, repo) = analyzer()
        a.processFft(fft(HEAVY), BINS, RATE)
        assertEquals(SpectrumContract.BAR_COUNT, repo.frame.spectrum.size)

        a.processFft(fft(LIGHT), BINS, RATE)
        assertEquals(SpectrumContract.BAR_COUNT, repo.frame.spectrum.size)
    }

    // ───────────────────────── ⑨ AGC 动态范围 ─────────────────────────

    @Test
    fun `heavy beat saturates while light beat stays well below`() {
        val (a, repo) = analyzer()

        a.processFft(fft(HEAVY), BINS, RATE)
        val heavy = repo.frame.spectrum[LOW_BAR]

        a.processFft(fft(LIGHT), BINS, RATE)
        val light = repo.frame.spectrum[LOW_BAR]

        assertTrue("heavy beat should saturate, got $heavy", heavy > 0.95f)
        assertTrue("light beat must be far lower, got $light", light < 0.6f)
        assertTrue(
            "light/heavy must retain dynamic range (AGC denominator is a running peak): " +
                "heavy=$heavy light=$light",
            light < heavy * 0.7f
        )
    }

    @Test
    fun `light beat matches the analytic AGC expectation`() {
        val (a, repo) = analyzer()
        a.processFft(fft(HEAVY), BINS, RATE)
        a.processFft(fft(LIGHT), BINS, RATE)

        // 分母 = 上一帧的低频运行峰值 × 0.995（单帧衰减），分子 = 66
        val denominator = HEAVY.toFloat() * WEIGHT * SpectrumContract.AGC_DECAY
        val normalized = (LIGHT.toFloat() * WEIGHT) / denominator
        val expected = normalized.pow(SpectrumContract.DISPLAY_GAMMA)

        assertEquals(
            "display channel must be normalized by the running peak and gamma ^0.75",
            expected.toDouble(),
            repo.frame.spectrum[LOW_BAR].toDouble(),
            0.02
        )
    }

    @Test
    fun `repeated identical beats stay stable at full scale`() {
        val (a, repo) = analyzer()
        repeat(30) { a.processFft(fft(HEAVY), BINS, RATE) }
        // 连续同强度鼓点：AGC 分母与分子同步 → 恒为满格（这是期望行为，不是回归）
        assertTrue(repo.frame.spectrum[LOW_BAR] > 0.95f)
    }

    // ───────────────────────── ⑨-b 双通道 ─────────────────────────

    @Test
    fun `rhythm channel stays linear while display channel is gamma compressed`() {
        val (a, repo) = analyzer()
        a.processFft(fft(HEAVY), BINS, RATE)
        a.processFft(fft(HEAVY), BINS, RATE)
        a.processFft(fft(LIGHT), BINS, RATE)

        val denominator = HEAVY.toFloat() * WEIGHT * SpectrumContract.AGC_DECAY
        val n = (LIGHT.toFloat() * WEIGHT) / denominator

        val expectedLinearBass = 3f * n / BASS_BARS                       // 三根低频柱
        val gammaBass = 3f * n.pow(SpectrumContract.DISPLAY_GAMMA) / BASS_BARS

        assertEquals(
            "bass must be the linear mean over bars 0..$BASS_BARS, not a gamma-compressed one",
            expectedLinearBass.toDouble(),
            repo.frame.bass.toDouble(),
            0.002
        )
        assertTrue(
            "bass must sit on the linear branch, not the gamma branch " +
                "(linear=$expectedLinearBass gamma=$gammaBass actual=${repo.frame.bass})",
            repo.frame.bass < (expectedLinearBass + gammaBass) / 2f
        )

        // 同一帧里，显示通道确实是 gamma 抬升过的（比线性值大）
        assertTrue(repo.frame.spectrum[LOW_BAR] > n)
    }

    // ───────────────────────── ⑭ 静音 ─────────────────────────

    @Test
    fun `silence writes a length-correct all-zero frame`() {
        val (a, repo) = analyzer()
        a.processFft(fft(HEAVY), BINS, RATE)     // 先建立非零状态
        a.processFft(ByteArray(BINS * 2), BINS, RATE)

        // 旧实现静音时返回 FloatArray(0)，渲染层柱数归零 → 频谱整体消失
        assertEquals(
            "silence must still publish BAR_COUNT entries",
            SpectrumContract.BAR_COUNT,
            repo.frame.spectrum.size
        )
        assertTrue("all bars must be zero on silence", repo.frame.spectrum.all { it == 0f })
        assertTrue("waveform must be zeroed on silence", repo.frame.waveform.all { it == 0f })
        assertEquals(0f, repo.frame.bass, 0f)
    }

    @Test
    fun `silence followed by a beat recovers immediately`() {
        val (a, repo) = analyzer()
        repeat(60) { a.processFft(ByteArray(BINS * 2), BINS, RATE) }   // 1.2s 静音
        a.processFft(fft(HEAVY), BINS, RATE)
        // 旧实现静音时把 runningPeak 压到 1f，恢复后前 1~2s 被压制
        assertTrue(
            "beat right after silence must not be suppressed, got ${repo.frame.spectrum[LOW_BAR]}",
            repo.frame.spectrum[LOW_BAR] > 0.9f
        )
    }

    // ───────────────────────── 防御性 ─────────────────────────

    @Test
    fun `zero bin fft is treated as silence instead of crashing`() {
        val (a, repo) = analyzer()
        a.processFft(ByteArray(0), 0, RATE)      // 不抛异常
        assertEquals(SpectrumContract.BAR_COUNT, repo.frame.spectrum.size)
        assertTrue(repo.frame.spectrum.all { it == 0f })
    }

    @Test
    fun `bin count larger than the allocated buffer is handled`() {
        val a = SpectrumAnalyzer()
        val repo = SpectrumRepository()
        a.repository = repo
        // 未经 attach 预分配 → 由 processFft 内部补分配（越界防御），不得抛 AIOOBE
        a.processFft(fft(HEAVY), BINS, RATE)
        assertTrue(repo.frame.spectrum[LOW_BAR] > 0f)
    }
}

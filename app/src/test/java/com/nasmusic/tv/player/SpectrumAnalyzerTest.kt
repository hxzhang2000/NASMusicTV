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
 * 直接驱动 `analyze`（internal，供测试开放），不经过 Android
 * [android.media.audiofx.Visualizer] 回调——Robolectric 无法产生真实 FFT 数据，
 * 而本文件的关注点全部在纯数值链路。v2.37.6 起频谱只有 PCM 一条通道，输入即幅值谱。
 *
 * **幅值 bin → 柱映射**（采样率 44100、512 bins、freqPerBin ≈ 43.07Hz）：
 *   bin 2 (86.1Hz)  → 柱 11，战区增益 2.2
 *   bin 3 (129.2Hz) → 柱 18，战区增益 2.2
 *   bin 4 (172.3Hz) → 柱 25，战区增益 2.2
 * 三根柱全部落在低频区（柱 10..39），正好用来操纵 AGC 分母。
 *
 * 幅值谱为 Float 数组，bin 2..4 直接填幅值，120 / 30 两个量级。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SpectrumAnalyzerTest {

    private companion object {
        const val BINS = 512
        const val RATE = 44100

        /** 重鼓点幅值 → 柱值 120 × 2.2 = 264 */
        const val HEAVY = 120f

        /** 轻鼓点幅值 → 柱值 30 × 2.2 = 66，与重鼓点相差 4 倍 */
        const val LIGHT = 30f

        /** 80–200Hz 战区增益 */
        const val WEIGHT = 2.2f

        /** 承载低频能量的柱索引之一（bin 2 落点） */
        const val LOW_BAR = 11

        /** 低频区柱数（0..39） */
        const val BASS_BARS = 40
    }

    /** 构造幅值谱：把能量放在低频 bin 2..4 */
    private fun magnitudes(value: Float): FloatArray {
        val m = FloatArray(BINS)
        for (bin in 2..4) m[bin] = value
        return m
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
        a.analyze(magnitudes(HEAVY), BINS, RATE)
        assertEquals(SpectrumContract.BAR_COUNT, repo.frame.spectrum.size)

        a.analyze(magnitudes(LIGHT), BINS, RATE)
        assertEquals(SpectrumContract.BAR_COUNT, repo.frame.spectrum.size)
    }

    // ───────────────────────── ⑨ AGC 动态范围 ─────────────────────────

    @Test
    fun `heavy beat saturates while light beat stays well below`() {
        val (a, repo) = analyzer()

        a.analyze(magnitudes(HEAVY), BINS, RATE)
        val heavy = repo.frame.spectrum[LOW_BAR]

        a.analyze(magnitudes(LIGHT), BINS, RATE)
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
        a.analyze(magnitudes(HEAVY), BINS, RATE)
        a.analyze(magnitudes(LIGHT), BINS, RATE)

        // 分母 = 上一帧的低频运行峰值 × 0.995（单帧衰减），分子 = 66
        val denominator = HEAVY * WEIGHT * SpectrumContract.AGC_DECAY
        val normalized = (LIGHT * WEIGHT) / denominator
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
        repeat(30) { a.analyze(magnitudes(HEAVY), BINS, RATE) }
        // 连续同强度鼓点：AGC 分母与分子同步 → 恒为满格（这是期望行为，不是回归）
        assertTrue(repo.frame.spectrum[LOW_BAR] > 0.95f)
    }

    // ───────────────────────── ⑨-b 双通道 ─────────────────────────

    @Test
    fun `rhythm channel stays linear while display channel is gamma compressed`() {
        val (a, repo) = analyzer()
        a.analyze(magnitudes(HEAVY), BINS, RATE)
        a.analyze(magnitudes(HEAVY), BINS, RATE)
        a.analyze(magnitudes(LIGHT), BINS, RATE)

        // 帧3 时 AGC 分母 = 上一帧的低频运行峰值 × 0.995；
        // 通道内做峰值归一化（0.0188/0.075×0.985 ≈ 0.254）——仍由线性分支主导
        val denominator = HEAVY * WEIGHT * SpectrumContract.AGC_DECAY
        val n = (LIGHT * WEIGHT) / denominator           // 0.2513：单柱线性归一化值
        val linearRatio = 3f * n / BASS_BARS                        // ≈0.0188，线性分支的"原始均值"
        val gammaRatio = 3f * n.pow(SpectrumContract.DISPLAY_GAMMA) / BASS_BARS  // ≈0.0266，gamma 分支

        val actual = repo.frame.bass
        // 归一化后 0.0188/0.075 ≈ 0.25，接近线性均值刻度；若误走 gamma 分支则量级完全不同
        assertTrue(
            "bass must sit on the linear branch, not the gamma branch " +
                "(linearRaw=$linearRatio gamma=$gammaRatio actual=$actual)",
            actual in 0.235f..0.275f
        )
        // 律动相对变化按线性比例（0.25），gamma 分支会把它压缩到 ^0.75（0.35）
        assertTrue(
            "normalized bass should track the linear ratio, not the gamma one: $actual",
            kotlin.math.abs(actual - 0.2513f) < kotlin.math.abs(actual - 0.3543f)
        )
    }

    // ───────────────────────── ⑭ 静音 ─────────────────────────

    @Test
    fun `silence writes a length-correct all-zero frame`() {
        val (a, repo) = analyzer()
        a.analyze(magnitudes(HEAVY), BINS, RATE)     // 先建立非零状态
        a.analyze(FloatArray(BINS), BINS, RATE)

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
        repeat(60) { a.analyze(FloatArray(BINS), BINS, RATE) }   // 1.2s 静音
        a.analyze(magnitudes(HEAVY), BINS, RATE)
        // 旧实现静音时把 runningPeak 压到 1f，恢复后前 1~2s 被压制
        assertTrue(
            "beat right after silence must not be suppressed, got ${repo.frame.spectrum[LOW_BAR]}",
            repo.frame.spectrum[LOW_BAR] > 0.9f
        )
    }

    // ───────────────────────── 防御性 ─────────────────────────

    @Test
    fun `zero bin input is treated as silence instead of crashing`() {
        val (a, repo) = analyzer()
        a.analyze(FloatArray(0), 0, RATE)      // 不抛异常
        assertEquals(SpectrumContract.BAR_COUNT, repo.frame.spectrum.size)
        assertTrue(repo.frame.spectrum.all { it == 0f })
    }

    @Test
    fun `bin count larger than expected is handled`() {
        val a = SpectrumAnalyzer()
        val repo = SpectrumRepository()
        a.repository = repo
        // 防御：上游可能给出比常规更大的 bin 数，不得越界崩溃
        val big = FloatArray(BINS + 8)
        for (bin in 2..4) big[bin] = HEAVY
        a.analyze(big, BINS + 8, RATE)
        assertTrue(repo.frame.spectrum[LOW_BAR] > 0f)
    }
}

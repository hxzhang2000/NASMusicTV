package com.nasmusic.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * `DemucsSeparator.LinearResampler` 的数值正确性（2026-09-14，P0-1 修复的回归网）。
 *
 * 背景：MediaCodec 解码不重采样、`codec.configure` 也改不了 `KEY_SAMPLE_RATE`，
 * 因此 48kHz 等非 44100Hz 源此前会原样进入按 44100Hz 设计的 Demucs 模型，而 WAV 头
 * 硬编码 44100 —— 用户听到的伴奏时长缩短 8.8%、音高升高约 1.5 个半音。
 * `LinearResampler` 在解码阶段把一切归一化到 44100Hz，这些断言是「修好了」的依据。
 *
 * 纯 JVM，无 Android 依赖（同 `Radix2FftTest` 的形态）。
 */
class LinearResamplerTest {

    /** 理想参考实现：out[k] = 输入坐标 k*ratio 处的线性插值 */
    private fun reference(input: FloatArray, k: Long, ratio: Double): Float {
        val p = k * ratio
        val i0 = floor(p).toInt()
        if (i0 < 0) return input[0]
        if (i0 >= input.size - 1) return input[input.size - 1]
        val frac = (p - i0).toFloat()
        return input[i0] + (input[i0 + 1] - input[i0]) * frac
    }

    /**
     * 合法输出帧数上限，**精确整数运算**。
     *
     * 约束：输出 k 的输入坐标 p = k*(inRate/outRate) 必须 <= n-1（插值需要
     * floor(p)+1 号样本，不可外推），等价于 k*inRate <= (n-1)*outRate。
     *
     * 不要写成 `floor((n-1)/ratio)+1`：double 除法在整除边界会算出 3968.999…，
     * 反而少一帧。实测 in=48000 out=44100 n=4321 即此情形（真值 3970）。
     */
    private fun maxOutputCount(n: Int, inRate: Int, outRate: Int): Long =
        if (n <= 0) 0L else (n - 1).toLong() * outRate / inRate + 1

    private fun run(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        val out = ArrayList<Float>(input.size + 16)
        val r = DemucsSeparator.LinearResampler(inRate, outRate) { l, _ -> out.add(l) }
        for (v in input) r.push(v, v)
        r.flush()
        return out.toFloatArray()
    }

    private fun sine(n: Int, freq: Double, rate: Double) =
        FloatArray(n) { sin(2.0 * PI * freq * it / rate).toFloat() }

    private fun freqByZeroCrossings(x: FloatArray, rate: Double): Double {
        var crossings = 0
        var first = -1
        var last = -1
        for (i in 1 until x.size) {
            if (x[i - 1] <= 0f && x[i] > 0f) {
                if (first < 0) first = i else last = i
                crossings++
            }
        }
        if (crossings < 2) return -1.0
        return (crossings - 1) * rate / (last - first).toDouble()
    }

    @Test
    fun `same rate passes through bit-exact`() {
        val n = 20000
        val input = sine(n, 440.0, 44100.0)
        val out = run(input, 44100, 44100)
        assertEquals("同速率帧数必须相等", n, out.size)
        var maxDiff = 0f
        for (i in 0 until n) maxDiff = maxOf(maxDiff, abs(out[i] - input[i]))
        assertEquals("同速率必须逐样本一致", 0f, maxDiff, 0f)
    }

    @Test
    fun `48k to 44k1 equals ideal interpolation bit-exact`() {
        val n = 48000
        val inRate = 48000
        val outRate = 44100
        val ratio = inRate.toDouble() / outRate
        val input = sine(n, 1000.0, inRate.toDouble())
        val out = run(input, inRate, outRate)

        assertEquals(
            "帧数必须达到不可外推的上界",
            maxOutputCount(n, inRate, outRate),
            out.size.toLong()
        )
        // 1 秒 48k 应变成 1 秒 44.1k
        assertEquals(
            "输出/输入 帧数比必须等于 outRate/inRate",
            outRate.toDouble() / inRate,
            out.size.toDouble() / n,
            1e-4
        )
        // 最强的一条：流式实现必须等价于「整段离线按 k*ratio 插值」
        var maxDiff = 0f
        for (k in out.indices) {
            maxDiff = maxOf(maxDiff, abs(out[k] - reference(input, k.toLong(), ratio)))
        }
        assertEquals("必须与理想插值逐点一致", 0f, maxDiff, 0f)
    }

    @Test
    fun `upsampling produces the expected frame counts`() {
        // 22050 -> 44100：帧数翻倍
        assertEquals(44099L, run(sine(22050, 500.0, 22050.0), 22050, 44100).size.toLong())
        // 8000 -> 44100：约 5.51 倍
        assertEquals(44095L, run(sine(8000, 300.0, 8000.0), 8000, 44100).size.toLong())
    }

    @Test
    fun `frequency and duration are preserved across resampling`() {
        val inRate = 48000
        val outRate = 44100
        val out = run(sine(inRate, 1000.0, inRate.toDouble()), inRate, outRate)
        assertEquals("重采样后频率必须保持 1000Hz", 1000.0, freqByZeroCrossings(out, outRate.toDouble()), 5.0)
        assertEquals("重采样后时长必须保持 1.0s", 1.0, out.size.toDouble() / outRate, 0.001)
    }

    @Test
    fun `no discontinuities at sample level`() {
        val inRate = 48000
        val outRate = 44100
        val out = run(sine(inRate, 1000.0, inRate.toDouble()), inRate, outRate)
        var maxStep = 0f
        for (i in 1 until out.size) maxStep = maxOf(maxStep, abs(out[i] - out[i - 1]))
        // 单位幅度 1000Hz 正弦在 44.1kHz 下的理论最大步进 = 2*pi*1000/44100
        val theoretical = (2.0 * PI * 1000.0 / outRate).toFloat()
        assertTrue("相邻步进 $maxStep 不应超过理论值 $theoretical", maxStep < theoretical * 1.1f)
    }

    @Test
    fun `never extrapolates beyond the last input sample`() {
        val n = 3
        val ratio = 48000.0 / 44100.0
        val v = floatArrayOf(0.1f, 0.2f, 0.3f)
        val out = run(v, 48000, 44100)

        // 上限 = floor((n-1)/ratio)+1 = floor(2/1.0884)+1 = 2。
        // 不是丢样本，是数学上界：坐标超过 n-1 就需要不存在的样本。
        assertEquals(maxOutputCount(n, 48000, 44100), out.size.toLong())
        // k=0 落在坐标 0（= v0）；k=1 落在 1.0884，在 v1/v2 之间插值 —— 不是 v1
        assertEquals(v[0], out[0], 0f)
        assertEquals(reference(v, 1L, ratio), out[1], 0f)
    }

    @Test
    fun `tail truncation stays within the mathematical bound`() {
        val cases = listOf(
            Triple(48000, 44100, 4 * 60 * 48000),  // 4 分钟曲目
            Triple(44100, 44100, 44100),
            Triple(8000, 44100, 8000),
            Triple(22050, 44100, 22050),
            Triple(32000, 44100, 32000)
        )
        for ((inR, outR, n) in cases) {
            val ratio = inR.toDouble() / outR
            var count = 0L
            val r = DemucsSeparator.LinearResampler(inR, outR) { _, _ -> count++ }
            for (i in 0 until n) r.push(0f, 0f)
            r.flush()
            val loss = n / ratio - count
            assertTrue(
                "$inR->$outR n=$n 尾部丢失 $loss 应小于 outRate/inRate=${1.0 / ratio}",
                loss < 1.0 / ratio
            )
        }
    }

    @Test
    fun `handles degenerate inputs`() {
        assertEquals("空输入应为 0 帧", 0L, run(FloatArray(0), 48000, 44100).size.toLong())
        assertEquals("单帧输入应为 1 帧", 1L, run(floatArrayOf(0.5f), 48000, 44100).size.toLong())
        assertEquals("单帧且同速率也应为 1 帧", 1L, run(floatArrayOf(0.5f), 44100, 44100).size.toLong())
    }

    @Test
    fun `property test over rate grid and lengths`() {
        val rates = listOf(8000, 16000, 22050, 32000, 44100, 48000, 96000)
        val rnd = java.util.Random(20260914L)
        var combos = 0
        for (inR in rates) {
            for (outR in rates) {
                for (n in intArrayOf(1, 2, 3, 5, 17, 100, 1000, 4321)) {
                    combos++
                    val ratio = inR.toDouble() / outR
                    val input = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
                    val out = run(input, inR, outR)

                    assertEquals(
                        "帧数不符 (in=$inR out=$outR n=$n)",
                        maxOutputCount(n, inR, outR),
                        out.size.toLong()
                    )
                    for (k in out.indices) {
                        assertEquals(
                            "与理想插值不符 (in=$inR out=$outR n=$n k=$k)",
                            reference(input, k.toLong(), ratio),
                            out[k],
                            0f
                        )
                    }
                }
            }
        }
        assertEquals(392L, combos.toLong())
    }

    @Test
    fun `no drift on long input`() {
        // 48000Hz 的 41 秒（约 200 万帧）——验证坐标用「输出序号 × ratio」而非
        // 累加 ratio 的必要性：累加会在千万帧级产生不可控漂移。
        val n = 2_000_000
        val inRate = 48000
        val outRate = 44100
        var count = 0L
        var phase = 0.0
        val r = DemucsSeparator.LinearResampler(inRate, outRate) { _, _ -> count++ }
        for (i in 0 until n) {
            phase += 1.0 / 480.0
            if (phase > 1.0) phase -= 1.0
            r.push((phase * 2 - 1).toFloat(), 0f)
        }
        r.flush()
        assertEquals(maxOutputCount(n, inRate, outRate), count)
    }
}

package com.nasmusic.tv.player

import android.media.audiofx.Visualizer
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 频谱分析器
 *
 * 使用 Android [Visualizer] 从 ExoPlayer 的音频会话捕获实时的 FFT 数据，
 * 通过分段密集映射（Perceptual Frequency Warping）将 512 个 FFT 频段
 * 映射为 32 根感知加权柱子，供 VisualEqualizer 消费。
 *
 * 柱子分配（视觉感知优化）：
 *   [ 0-4 ]  20~80Hz     5根    底鼓下潜（保留物理冲击）
 *   [ 5-19]  80~250Hz    15根   鼓点/贝斯核弹区（主视觉冲击，绝对统治）
 *   [20-27]  250Hz~3kHz  8根    人声/主旋律核心区（清晰度）
 *   [28-31]  3kHz~20kHz  4根    超高频压缩区（点缀，不抢镜）
 *
 * 未连接时发射 FloatArray(0)，UI 自动回落为随机动画。
 * 连接成功后每 50ms 发射 32 柱幅值数组。
 */
class SpectrumAnalyzer {

    private var visualizer: Visualizer? = null
    /** 未连接时发射空数组，连接后每帧发射 FloatArray(BAR_COUNT) */
    private val _spectrumData = MutableStateFlow(FloatArray(0))
    val spectrumData: StateFlow<FloatArray> = _spectrumData

    /** 运行峰值跟踪器，缓慢衰减，保证柱子总是能填满高度 */
    private var runningPeak = 1f
    /**
     * 自适应噪声基底——跟踪设备底噪水平，
     * 无有效信号时快速下降，有信号时极慢上升，
     * 用于 P-1 自适应静音检测。
     */
    private var noiseFloor = 10f

    companion object {
        /** 频谱柱状条数量——32 根，使用感知频率翘曲映射 */
        const val BAR_COUNT = 32
        /** 最小有效幅值 */
        private const val MIN_AMPLITUDE = 0.05f
        /** FFT 采样率，用于将 bin 索引映射为频率 */
        private const val SAMPLING_RATE = 44100
    }

    /**
     * 绑定到指定音频会话
     *
     * @param audioSessionId ExoPlayer 的音频会话 ID（必须 > 0）
     */
    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId <= 0) {
            AppLog.w("SpectrumAnalyzer", "Invalid audioSessionId=$audioSessionId, skipping attach")
            return
        }

        try {
            val vis = Visualizer(audioSessionId)

            // 选择合适的 capture size
            val maxSize = Visualizer.getCaptureSizeRange()[1]  // 通常 1024
            val targetSize = if (maxSize >= 1024) 1024 else maxSize
            vis.captureSize = targetSize
            val numFftBins = targetSize / 2
            AppLog.d("SpectrumAnalyzer", "Attached to session $audioSessionId, captureSize=$targetSize, fftBins=$numFftBins")

            // 重置峰值跟踪器（新音频会话从头开始）
            runningPeak = 1f

            vis.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) { /* 不关心波形 */ }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft == null || fft.size < 2) return
                        val processed = processFft(fft, numFftBins, samplingRate)
                        _spectrumData.value = processed
                    }
                },
                50000,   // 50ms ≈ 20fps
                false,   // 不需要波形
                true     // 需要 FFT
            )

            vis.enabled = true
            visualizer = vis
            AppLog.d("SpectrumAnalyzer", "SpectrumAnalyzer now active, emitting $BAR_COUNT bars")

        } catch (e: SecurityException) {
            AppLog.w("SpectrumAnalyzer", "RECORD_AUDIO permission denied, spectrum unavailable", e)
        } catch (e: UnsupportedOperationException) {
            AppLog.w("SpectrumAnalyzer", "Audio session $audioSessionId invalid or Visualizer unsupported", e)
        } catch (e: Exception) {
            AppLog.e("SpectrumAnalyzer", "Failed to attach Visualizer", e)
        }
    }

    /**
     * 释放 Visualizer 资源，恢复为空数组状态（UI 回落为随机动画）
     */
    fun release() {
        try {
            visualizer?.apply {
                enabled = false
                release()
            }
        } catch (e: Exception) {
            AppLog.e("SpectrumAnalyzer", "Error releasing Visualizer", e)
        }
        visualizer = null
        _spectrumData.value = FloatArray(0)
        AppLog.d("SpectrumAnalyzer", "Released, state reset to empty array")
    }

    // -----------------------------------------------------------------
    // FFT 处理 — 感知频率翘曲 (Perceptual Frequency Warping)
    // -----------------------------------------------------------------

    /**
     * 将原始 FFT bytes 转换为归一化的 32 柱幅值数组
     *
     * 使用分段密集映射代替标准对数映射：
     *   - 低频鼓点区（20~250Hz）：20 根柱子（密集覆盖，视觉冲击）
     *   - 人声主旋律区（250Hz~3kHz）：8 根柱子（精细刻画清晰度）
     *   - 超高频区（3kHz~20kHz）：4 根柱子（严重压缩，点缀不抢镜）
     *
     * @param fft FFT 原始数据（每对 byte = 复数 real, imag）
     * @param numBins FFT 频段数（captureSize / 2）
     * @param samplingRate 采样率
     * @return FloatArray(BAR_COUNT) 归一化幅值，或空数组（当 FFT 数据无效时）
     */
    private fun processFft(fft: ByteArray, numBins: Int, samplingRate: Int): FloatArray {
        // 1) 计算每个 FFT 频段的幅值
        val magnitudes = FloatArray(numBins)
        for (i in 0 until numBins) {
            val real = fft[i * 2].toFloat()
            val imag = fft[i * 2 + 1].toFloat()
            magnitudes[i] = sqrt(real * real + imag * imag)
        }

        // 2) 计算 RMS 和帧最大值（跳过直流分量 i=0）
        var frameMax = 0f
        var sumSq = 0f
        var count = 0
        for (i in 1 until numBins) {
            val mag = magnitudes[i]
            if (mag > frameMax) frameMax = mag
            sumSq += mag * mag
            count++
        }
        val rms = sqrt(sumSq / count.coerceAtLeast(1))

        // 【P-1: 自适应静音门限】跟踪噪声基底，动态判定静音
        //
        // 当 RMS 低于当前噪声基底 → 噪声基底快速下降（跟随静音环境）
        // 当 RMS 高于噪声基底     → 噪声基底极慢上升（防止被音乐拉高）
        // dynamicThreshold = noiseFloor × 3：低于基底 3 倍视为真·静音
        if (rms < noiseFloor) {
            noiseFloor = rms * 0.9f + noiseFloor * 0.1f  // 快速下降
        } else {
            noiseFloor = noiseFloor * 0.999f + rms * 0.001f  // 极慢上升
        }
        val dynamicThreshold = noiseFloor * 3.0f
        if (frameMax < dynamicThreshold) {
            runningPeak = 1f
            return FloatArray(0)
        }

        // 3) 运行峰值更新
        //    高值瞬间拉高，低值缓慢衰减（×0.94 ≈ 3s 降到接近零）
        //    下限 0.001 仅用于防止除零，不干扰动态响应
        runningPeak = maxOf(runningPeak * 0.94f, frameMax, 0.001f)

        // 4) 分段密集映射 + 战区增益：512 FFT bins → 32 根感知加权柱子
        //
        // 柱子布局（┃ = 分段边界）：
        // [0-4] 20~80Hz │ [5-19] 80~250Hz │ [20-27] 250Hz~3kHz │ [28-31] 3kHz~20kHz
        //  └─5根─┘        └───15根───┘        └───8根───┘         └──4根───┘
        val freqPerBin = SAMPLING_RATE.toFloat() / (numBins * 2)
        val result = FloatArray(BAR_COUNT)

        for (bin in 1 until numBins) {  // 跳过直流分量
            val freq = bin * freqPerBin
            val mag = magnitudes[bin]
            val barIndex = getTargetBarIndex(freq)
            val weight = getFrequencyWeight(freq)

            // 区间取加权最大值（保留瞬态峰值 + 战区增益）
            val weighted = mag * weight
            if (weighted > result[barIndex]) {
                result[barIndex] = weighted
            }
        }

        // 5) 【P1: 锚定归一化 + 除零保护】
        //    归一化分母仅以低频区（柱子 5~19，对应 80~250Hz 鼓点核弹区）的峰值为锚点，
        //    确保鼓点永远顶天立地，高频区因物理权重低自然矮小。
        //    下限 0.01f 防止归一化前所有柱子为零导致除零崩溃。
        val lowBandPeak = result.sliceArray(5..19).maxOrNull() ?: 0.1f
        val safeDenominator = maxOf(lowBandPeak, 0.01f)

        for (bar in 0 until BAR_COUNT) {
            val normalized = (result[bar] / safeDenominator).coerceIn(0f, 1f)
            val boosted = sqrt(normalized)
            val enhanced = boosted.pow(1.5f)
            result[bar] = enhanced.coerceIn(MIN_AMPLITUDE, 1f)
        }

        // 数据层不做时间平滑——平滑任务交给 UI 层的 Attack/Release 插值
        AppLog.d("SpectrumAnalyzer",
                "frameMax=$frameMax, runningPeak=$runningPeak, " +
                "result[4]=${"%.2f".format(result[4])}, " +
                "result[15]=${"%.2f".format(result[15])}, " +
                "result[30]=${"%.2f".format(result[30])}")
        return result
    }

    /**
     * 分段密集映射——将物理频率直接映射到目标柱子索引
     *
     * 把"视觉像素"集中在人耳敏感区域（20Hz~3kHz），
     * 超高频严重压缩为点缀。
     */
    private fun getTargetBarIndex(freq: Float): Int {
        return when {
            // [0-4] 极低频 20~80Hz → 5 根
            freq <= 80f -> {
                val f = ((freq - 20f) / (80f - 20f)).coerceIn(0f, 1f)
                (f * 4).toInt().coerceIn(0, 4)
            }
            // [5-19] 鼓点核弹区 80~250Hz → 15 根（极其密集！）
            freq <= 250f -> {
                val f = ((freq - 80f) / (250f - 80f)).coerceIn(0f, 1f)
                (f * 14).toInt().coerceIn(0, 14) + 5
            }
            // [20-27] 人声核心区 250Hz~3kHz → 8 根
            freq <= 3000f -> {
                val f = ((freq - 250f) / (3000f - 250f)).coerceIn(0f, 1f)
                (f * 7).toInt().coerceIn(0, 7) + 20
            }
            // [28-31] 超高频压缩区 3kHz~20kHz → 4 根
            else -> {
                val f = ((freq - 3000f) / (20000f - 3000f)).coerceIn(0f, 1f)
                (f * 3).toInt().coerceIn(0, 3) + 28
            }
        }
    }

    /**
     * 战区增益——根据频段给人声/鼓点更高的物理幅度权重
     *
     * 与分段密集映射形成双重叠加：
     *   密集映射：低频区柱子更多（数量优势）
     *   战区增益：低频区幅值更大（物理优势）
     */
    private fun getFrequencyWeight(freq: Float): Float {
        return when {
            freq <= 200f -> 2.2f   // 鼓点/贝斯 ×2.2（强力增强）
            freq in 201f..3000f -> 1.8f // 人声 ×1.8（清晰度增强）
            else -> 0.3f           // 超高频 ×0.3（物理打骨折）
        }
    }
}

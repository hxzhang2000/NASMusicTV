package com.nasmusic.tv.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spleeter 专用 DSP 层：STFT / iSTFT / Wiener 归一化
 *
 * Spleeter 2-stem 模型要求：
 * - 输入：16-bit PCM 立体声，44100Hz，重采样后对齐
 * - STFT 参数：n_fft=4096, hop_length=1024, win_length=4096, Hanning window
 * - 输出：vocals.wav + accompaniment.wav
 *
 * DSP 流程：
 * 1. 重采样到 44100Hz（如果需要）
 * 2. STFT → 复数频谱
 * 3. ONNX 推理 → soft mask
 * 4. 应用 soft mask → 分离频谱
 * 5. Wiener 迭代归一化（提升质量）
 * 6. iSTFT → 时域信号
 * 7. 写入 WAV 文件
 */
class SpleeterDsp {

    companion object {
        private const val TAG = "SpleeterDsp"

        // Spleeter 标准参数
        const val SAMPLE_RATE = 44100
        const val N_FFT = 4096
        const val HOP_LENGTH = 1024
        const val WIN_LENGTH = 4096
        const val NUM_BINS = N_FFT / 2 + 1

        // Wiener 迭代参数
        private const val WIENER_ITERATIONS = 2
        private const val WIENER_EPSILON = 1e-10f
    }

    // Hanning 窗口系数
    private val window = FloatArray(WIN_LENGTH) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (WIN_LENGTH - 1)))).toFloat()
    }

    // FFT 工作缓冲区
    private var fftReal = FloatArray(N_FFT)
    private var fftImag = FloatArray(N_FFT)

    /**
     * STFT：时域信号 → 复数频谱
     *
     * @param samples 输入样本（浮点，范围 -1.0 ~ 1.0）
     * @return 复数频谱 [real, imag]，shape = [numFrames, numBins]
     */
    fun stft(samples: FloatArray): Array<FloatArray> {
        val numFrames = (samples.size - N_FFT) / HOP_LENGTH + 1
        val real = FloatArray(numFrames * NUM_BINS)
        val imag = FloatArray(numFrames * NUM_BINS)

        for (frame in 0 until numFrames) {
            val offset = frame * HOP_LENGTH

            // 加窗
            for (i in 0 until N_FFT) {
                fftReal[i] = if (offset + i < samples.size) samples[offset + i] * window[i] else 0f
                fftImag[i] = 0f
            }

            // FFT
            fft(fftReal, fftImag)

            // 存储结果
            for (k in 0 until NUM_BINS) {
                real[frame * NUM_BINS + k] = fftReal[k]
                imag[frame * NUM_BINS + k] = fftImag[k]
            }
        }

        return arrayOf(real, imag)
    }

    /**
     * iSTFT：复数频谱 → 时域信号
     *
     * @param real 实部频谱
     * @param imag 虚部频谱
     * @return 时域样本（浮点，范围 -1.0 ~ 1.0）
     */
    fun istft(real: FloatArray, imag: FloatArray): FloatArray {
        val numBins = NUM_BINS
        val numFrames = real.size / numBins

        // 输出缓冲区（overlap-add）
        val outputSize = (numFrames - 1) * HOP_LENGTH + N_FFT
        val output = FloatArray(outputSize)
        val overlap = FloatArray(N_FFT)

        for (frame in 0 until numFrames) {
            // 提取当前帧
            for (k in 0 until numBins) {
                fftReal[k] = real[frame * numBins + k]
                fftImag[k] = imag[frame * numBins + k]
            }
            // 高频填充
            for (k in numBins until N_FFT) {
                fftReal[k] = fftReal[N_FFT - k]
                fftImag[k] = -fftImag[N_FFT - k]
            }

            // iFFT
            ifft(fftReal, fftImag)

            // 加窗 + overlap-add
            for (i in 0 until N_FFT) {
                overlap[i] += fftReal[i] * window[i]
            }

            // 输出当前帧
            val offset = frame * HOP_LENGTH
            for (i in 0 until HOP_LENGTH) {
                output[offset + i] = overlap[i]
            }

            // 移动重叠缓冲区
            for (i in 0 until (N_FFT - HOP_LENGTH)) {
                overlap[i] = overlap[i + HOP_LENGTH]
            }
            for (i in (N_FFT - HOP_LENGTH) until N_FFT) {
                overlap[i] = 0f
            }
        }

        return output.copyOf((numFrames - 1) * HOP_LENGTH + HOP_LENGTH)
    }

    /**
     * 计算 magnitude spectrogram
     *
     * @param real 实部频谱
     * @param imag 虚部频谱
     * @return magnitude spectrogram
     */
    fun magnitude(real: FloatArray, imag: FloatArray): FloatArray {
        val mag = FloatArray(real.size)
        for (i in real.indices) {
            mag[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
        }
        return mag
    }

    /**
     * 计算 phase spectrogram
     *
     * @param real 实部频谱
     * @param imag 虚部频谱
     * @return phase spectrogram
     */
    fun phase(real: FloatArray, imag: FloatArray): FloatArray {
        val ph = FloatArray(real.size)
        for (i in real.indices) {
            ph[i] = kotlin.math.atan2(imag[i].toDouble(), real[i].toDouble()).toFloat()
        }
        return ph
    }

    /**
     * 应用 soft mask 到 magnitude 和 phase，重建复数频谱
     *
     * @param mag magnitude spectrogram
     * @param ph phase spectrogram
     * @param mask soft mask（范围 0.0 ~ 1.0）
     * @return [real, imag] 重建的复数频谱
     */
    fun applyMask(mag: FloatArray, ph: FloatArray, mask: FloatArray): Array<FloatArray> {
        val real = FloatArray(mag.size)
        val imag = FloatArray(mag.size)

        for (i in mag.indices) {
            val maskedMag = mag[i] * mask[i]
            real[i] = maskedMag * cos(ph[i].toDouble()).toFloat()
            imag[i] = maskedMag * sin(ph[i].toDouble()).toFloat()
        }

        return arrayOf(real, imag)
    }

    /**
     * Wiener 归一化：迭代优化分离质量
     *
     * @param masks 初始 masks [vocals, accompaniment]
     * @param mag mixture magnitude
     * @return 优化后的 masks
     */
    fun wienerNormalize(
        masks: Array<FloatArray>,
        mag: FloatArray
    ): Array<FloatArray> {
        var currentMasks = masks.copyOf()

        for (iter in 0 until WIENER_ITERATIONS) {
            val newMasks = Array(currentMasks.size) { FloatArray(mag.size) }

            for (source in currentMasks.indices) {
                for (i in mag.indices) {
                    val numerator = currentMasks[source][i] * mag[i]
                    var denominator = WIENER_EPSILON

                    for (s in currentMasks.indices) {
                        denominator += currentMasks[s][i] * mag[i]
                    }

                    newMasks[source][i] = numerator / denominator
                }
            }

            currentMasks = newMasks
        }

        return currentMasks
    }

    // ── FFT / IFFT (Cooley-Tukey 基数2) ──

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val angle = -2.0 * PI / len
            val wR = cos(angle).toFloat()
            val wI = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var cR = 1.0f; var cI = 0.0f
                for (k in 0 until half) {
                    val tR = cR * real[i + k + half] - cI * imag[i + k + half]
                    val tI = cR * imag[i + k + half] + cI * real[i + k + half]
                    real[i + k + half] = real[i + k] - tR
                    imag[i + k + half] = imag[i + k] - tI
                    real[i + k] = real[i + k] + tR
                    imag[i + k] = imag[i + k] + tI
                    val nR = cR * wR - cI * wI; cI = cR * wI + cI * wR; cR = nR
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun ifft(real: FloatArray, imag: FloatArray) {
        for (i in imag.indices) imag[i] = -imag[i]
        fft(real, imag)
        val n = real.size.toFloat()
        for (i in real.indices) { real[i] /= n; imag[i] = -imag[i] / n }
    }
}

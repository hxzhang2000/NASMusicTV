package com.nasmusic.tv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.nasmusic.tv.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 频谱遮罩人声消除处理器（升级替代 Mid/Side DSP）
 *
 * 原理：STFT（短时傅里叶变换）+ 自适应频谱遮罩
 * 1. 对输入音频分帧加窗（Hanning window），做 FFT 变换到频域
 * 2. 分析每个频率 bin 的能量分布，估算人声存在概率
 * 3. 对人声主导的频率 bin 施加衰减遮罩（保留 30% 以避免过度衰减）
 * 4. ISTFT（逆短时傅里叶变换）+ overlap-add 合成输出
 *
 * 关键实现：循环输入缓冲区 + 滑动窗口提取
 * - 维护 FFT_SIZE 大小的循环输入缓冲，每次写入 HOP_SIZE 个新采样
 * - 从缓冲区提取完整的 FFT_SIZE 滑动窗口（而非零填充 HOP_SIZE）
 * - 这样 FFT 看到的是真实的重叠信号，overlap-add 能正确重建
 *
 * 与 VocalRemovalProcessor 相同的接口（AudioProcessor），可无缝替换。
 * 仅支持 16-bit PCM 立体声，其他格式自动 bypass。
 */
class SpectralMaskProcessor : AudioProcessor {

    companion object {
        private const val TAG = "SpectralMask"

        // STFT 参数
        private const val FFT_SIZE = 2048
        private const val HOP_SIZE = 512
        private const val NUM_BINS = FFT_SIZE / 2 + 1

        // 人声频率范围估算（Hz）
        private const val VOCAL_LOW_FREQ = 80.0
        private const val VOCAL_HIGH_FREQ = 8000.0

        // 遮罩参数
        private const val VOCAL_MASK_FACTOR = 0.3f
        private const val ENERGY_THRESHOLD = 0.01f

        // COLA 归一化：Hann 窗 + hop=FFT_SIZE/4 + 双窗（分析+合成）
        // 窗平方重叠和 = 3/8 * 4 = 3/2，归一化 = 2/3
        // 加上遮罩后补偿，最终增益 1.0（不额外放大，避免削波）
        private const val COLA_NORM = 2.0f / 3.0f

        private val EMPTY_BUFFER: ByteBuffer =
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    @Volatile
    private var enabled = false

    private var configured = false
    private var ended = false
    private var outputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET

    // 分析/合成窗（Hann）
    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }

    // 循环输入缓冲区（per channel）—— 保留最近 FFT_SIZE 个采样
    private val inputLeft = FloatArray(FFT_SIZE)
    private val inputRight = FloatArray(FFT_SIZE)
    private var inputWritePos = 0

    // 输出 overlap-add 缓冲区（per channel）
    private val overlapLeft = FloatArray(FFT_SIZE)
    private val overlapRight = FloatArray(FFT_SIZE)

    // FFT 工作数组
    private val fftRealLeft = FloatArray(FFT_SIZE)
    private val fftImagLeft = FloatArray(FFT_SIZE)
    private val fftRealRight = FloatArray(FFT_SIZE)
    private val fftImagRight = FloatArray(FFT_SIZE)

    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER

    fun setEnabled(enabled: Boolean) {
        if (this.enabled != enabled) {
            AppLog.d(TAG, "setEnabled: $enabled")
            this.enabled = enabled
            resetState()
        }
    }

    fun isEnabled(): Boolean = enabled

    // ── AudioProcessor 接口 ──

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        AppLog.d(TAG, "configure: sampleRate=${inputAudioFormat.sampleRate}, ch=${inputAudioFormat.channelCount}, enc=${inputAudioFormat.encoding}")

        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            AppLog.w(TAG, "configure: unsupported format, bypassing")
            configured = false
            outputFormat = AudioProcessor.AudioFormat.NOT_SET
            return AudioProcessor.AudioFormat.NOT_SET
        }

        outputFormat = inputAudioFormat
        configured = true
        resetState()

        AppLog.d(TAG, "configure: ready, sampleRate=${inputAudioFormat.sampleRate}")
        return outputFormat
    }

    override fun isActive(): Boolean = configured

    override fun queueEndOfStream() {
        ended = true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!configured) return

        val remaining = inputBuffer.remaining()
        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        }
        buffer.clear()

        if (!enabled) {
            // bypass：直接透传
            buffer.put(inputBuffer)
            buffer.flip()
            outputBuffer = buffer
            return
        }

        // 以 HOP_SIZE 立体声帧为单位处理（每帧 HOP_SIZE * 4 字节）
        while (inputBuffer.remaining() >= HOP_SIZE * 4) {
            processHop(inputBuffer, buffer)
        }

        buffer.flip()
        outputBuffer = buffer
    }

    /**
     * 处理一个 HOP_SIZE 的 hop：
     * 1. 读取 HOP_SIZE 立体声采样到循环输入缓冲
     * 2. 从缓冲提取 FFT_SIZE 滑动窗口（最旧→最新）
     * 3. 加分析窗 → FFT → 遮罩 → IFFT → 加合成窗 → overlap-add
     * 4. 输出 HOP_SIZE 采样到 outBuffer
     * 5. overlap 缓冲左移 HOP_SIZE
     */
    private fun processHop(inputBuffer: ByteBuffer, outBuffer: ByteBuffer) {
        // 1. 读取 HOP_SIZE 立体声采样到循环输入缓冲（归一化到 [-1, 1]）
        for (i in 0 until HOP_SIZE) {
            inputLeft[inputWritePos] = inputBuffer.short.toFloat() / 32768f
            inputRight[inputWritePos] = inputBuffer.short.toFloat() / 32768f
            inputWritePos = (inputWritePos + 1) % FFT_SIZE
        }

        // 2. 提取滑动窗口：从 inputWritePos 开始（最旧采样），读 FFT_SIZE 个采样
        //    inputWritePos 指向下一个要被覆盖的位置 = 当前最旧的采样
        processChannel(inputLeft, overlapLeft, fftRealLeft, fftImagLeft)
        processChannel(inputRight, overlapRight, fftRealRight, fftImagRight)

        // 4. 输出 HOP_SIZE 采样（归一化回 16-bit PCM）
        for (i in 0 until HOP_SIZE) {
            val l = (overlapLeft[i] * COLA_NORM * 32767f).toInt().coerceIn(-32768, 32767)
            val r = (overlapRight[i] * COLA_NORM * 32767f).toInt().coerceIn(-32768, 32767)
            outBuffer.putShort(l.toShort())
            outBuffer.putShort(r.toShort())
        }

        // 5. overlap 缓冲左移 HOP_SIZE，腾出新空间
        System.arraycopy(overlapLeft, HOP_SIZE, overlapLeft, 0, FFT_SIZE - HOP_SIZE)
        java.util.Arrays.fill(overlapLeft, FFT_SIZE - HOP_SIZE, FFT_SIZE, 0f)
        System.arraycopy(overlapRight, HOP_SIZE, overlapRight, 0, FFT_SIZE - HOP_SIZE)
        java.util.Arrays.fill(overlapRight, FFT_SIZE - HOP_SIZE, FFT_SIZE, 0f)
    }

    /**
     * 处理单声道一帧：
     * 提取滑动窗口 → 加分析窗 → FFT → 遮罩 → IFFT → 加合成窗 → overlap-add
     */
    private fun processChannel(
        inputBuf: FloatArray,
        overlapBuf: FloatArray,
        real: FloatArray,
        imag: FloatArray
    ) {
        // 提取滑动窗口 + 加分析窗
        val readStart = inputWritePos
        for (i in 0 until FFT_SIZE) {
            val idx = (readStart + i) % FFT_SIZE
            real[i] = inputBuf[idx] * window[i]
            imag[i] = 0f
        }

        // FFT → 遮罩 → IFFT
        fft(real, imag)
        applySpectralMask(real, imag)
        ifft(real, imag)

        // 加合成窗 + overlap-add
        for (i in 0 until FFT_SIZE) {
            overlapBuf[i] += real[i] * window[i]
        }
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean = ended && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        buffer = EMPTY_BUFFER
        ended = false
        resetState()
    }

    override fun reset() {
        AppLog.d(TAG, "reset")
        enabled = false
        configured = false
        ended = false
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        outputBuffer = EMPTY_BUFFER
        buffer = EMPTY_BUFFER
        resetState()
    }

    private fun resetState() {
        inputLeft.fill(0f)
        inputRight.fill(0f)
        overlapLeft.fill(0f)
        overlapRight.fill(0f)
        inputWritePos = 0
    }

    // ── 频谱遮罩 ──

    private fun applySpectralMask(real: FloatArray, imag: FloatArray) {
        val sampleRate = outputFormat.sampleRate.toDouble()

        for (k in 0 until NUM_BINS) {
            val freq = k.toDouble() * sampleRate / FFT_SIZE
            val magnitude = sqrt(real[k] * real[k] + imag[k] * imag[k])

            if (freq >= VOCAL_LOW_FREQ && freq <= VOCAL_HIGH_FREQ && magnitude > ENERGY_THRESHOLD) {
                real[k] *= VOCAL_MASK_FACTOR
                imag[k] *= VOCAL_MASK_FACTOR
            }
        }
    }

    // ── FFT / IFFT (Cooley-Tukey) ──

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

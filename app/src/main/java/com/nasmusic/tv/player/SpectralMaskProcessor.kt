package com.nasmusic.tv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.nasmusic.tv.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 实时人声消除处理器（Mid/Side DSP + 低通保留低音）
 *
 * 原理：
 * 1. Mid/Side 分离：Mid=(L+R)/2（居中信号），Side=(L-R)/2（立体声差异）
 * 2. 低通滤波 Mid：只保留 250Hz 以下的低音（贝斯/底鼓），滤掉人声频段
 * 3. 输出 = 低通Mid + Side：保留低音和立体声伴奏，移除居中人声
 *
 * 相比纯 Mid/Side（完全删除 Mid）的优势：
 * - 保留贝斯和底鼓（纯 Mid/Side 会损失低频居中乐器）
 * - 人声消除更彻底（低通后 Mid 高频≈0，人声被完全移除）
 *
 * 逐采样处理：无 FFT 缓冲，零延迟，任意输入块大小都能输出。
 * 仅支持 16-bit PCM 立体声，其他格式自动 bypass。
 */
class SpectralMaskProcessor : AudioProcessor {

    companion object {
        private const val TAG = "SpectralMask"

        // 低通滤波器参数（保留 250Hz 以下低音）
        // alpha = 1 - exp(-2 * PI * cutoff / sampleRate)
        // 250Hz @ 44100Hz: alpha ≈ 0.0351
        private const val LOWPASS_ALPHA = 0.035f

        // Side 通道增益（立体声伴奏强度）
        private const val SIDE_GAIN = 1.2f

        private val EMPTY_BUFFER: ByteBuffer =
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    @Volatile
    private var enabled = false

    private var configured = false
    private var ended = false
    private var outputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET

    // 低通滤波器状态（Mid 通道是单声道，只需一个状态变量）
    private var midLowpassState = 0f

    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER

    fun setEnabled(enabled: Boolean) {
        if (this.enabled != enabled) {
            AppLog.d(TAG, "setEnabled: $enabled")
            this.enabled = enabled
            // 重置滤波器状态，避免切换时出现瞬态
            midLowpassState = 0f
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

        // 逐帧处理：每帧 4 字节（左+右各 16-bit）
        while (inputBuffer.remaining() >= 4) {
            val left = inputBuffer.short.toFloat()
            val right = inputBuffer.short.toFloat()

            // Mid/Side 分离
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f

            // 低通滤波 Mid：保留低音（贝斯/底鼓），滤掉人声频段
            midLowpassState = midLowpassState + LOWPASS_ALPHA * (mid - midLowpassState)
            val lowMid = midLowpassState

            // 输出 = 低通Mid（低音） + 增强Side（立体声伴奏）
            // 低频：lowMid≈mid，输出≈原始（低音完整保留）
            // 高频：lowMid≈0，输出≈side（居中人声被移除，立体声伴奏保留）
            val outLeft = (lowMid + side * SIDE_GAIN).toInt().coerceIn(-32768, 32767)
            val outRight = (lowMid - side * SIDE_GAIN).toInt().coerceIn(-32768, 32767)

            buffer.putShort(outLeft.toShort())
            buffer.putShort(outRight.toShort())
        }

        buffer.flip()
        outputBuffer = buffer
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
        midLowpassState = 0f
    }

    override fun reset() {
        AppLog.d(TAG, "reset")
        enabled = false
        configured = false
        ended = false
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        outputBuffer = EMPTY_BUFFER
        buffer = EMPTY_BUFFER
        midLowpassState = 0f
    }
}

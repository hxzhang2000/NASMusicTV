package com.nasmusic.tv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.nasmusic.tv.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 实时人声消除处理器（Mid/Side DSP + 原始信号补偿）
 *
 * 原理：
 * 1. Mid/Side 分离：Side = (L - R) / 2 提取立体声差异信号
 *    - 居中人声（主唱）在 Mid 通道，被移除
 *    - 立体声伴奏（乐器）在 Side 通道，被保留
 * 2. 原始信号补偿：混入 30% 原始信号，避免完全空心化
 * 3. 逐采样处理：无 FFT 缓冲，零延迟，任意输入块大小都能输出
 *
 * 优势（相比 STFT 方案）：
 * - 永远产生输出：不会因缓冲块太小而静音
 * - 零延迟：切换瞬时生效，无重叠窗口重建延迟
 * - 实时切换：setEnabled 后立即生效，无缓冲清空造成的断音
 *
 * 仅支持 16-bit PCM 立体声，其他格式自动 bypass。
 */
class SpectralMaskProcessor : AudioProcessor {

    companion object {
        private const val TAG = "SpectralMask"

        // 增益参数
        private const val SIDE_GAIN = 0.7f       // Side 通道增益（人声消除强度）
        private const val ORIGINAL_GAIN = 0.3f   // 原始信号保留比例（避免空心化）

        private val EMPTY_BUFFER: ByteBuffer =
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    @Volatile
    private var enabled = false

    private var configured = false
    private var ended = false
    private var outputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET

    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER

    fun setEnabled(enabled: Boolean) {
        if (this.enabled != enabled) {
            AppLog.d(TAG, "setEnabled: $enabled")
            this.enabled = enabled
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
            // bypass：直接透传（逐字节拷贝，任意大小都能处理）
            buffer.put(inputBuffer)
            buffer.flip()
            outputBuffer = buffer
            return
        }

        // 逐帧 Mid/Side 处理：每帧 4 字节（左 2 字节 + 右 2 字节）
        // 任意输入块大小都能处理，不会因块太小而静音
        while (inputBuffer.remaining() >= 4) {
            val left = inputBuffer.short.toFloat()
            val right = inputBuffer.short.toFloat()

            // Mid/Side 分离：Side = (L - R) / 2
            val side = (left - right) * 0.5f

            // 伴奏 = Side 通道 + 保留部分原始信号
            val outLeft = (side * SIDE_GAIN + left * ORIGINAL_GAIN)
                .toInt().coerceIn(-32768, 32767)
            val outRight = (-side * SIDE_GAIN + right * ORIGINAL_GAIN)
                .toInt().coerceIn(-32768, 32767)

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
    }

    override fun reset() {
        AppLog.d(TAG, "reset")
        enabled = false
        configured = false
        ended = false
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        outputBuffer = EMPTY_BUFFER
        buffer = EMPTY_BUFFER
    }
}

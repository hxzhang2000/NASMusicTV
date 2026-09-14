package com.nasmusic.tv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
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
 *
 * ─────────────────────────────────────────────────────────────────────
 * 🎤 在 K 歌模块中的位置（2026-09-13 标注）
 * ─────────────────────────────────────────────────────────────────────
 * 本类是 K 歌「DSP 路径」的唯一实现 —— 实时人声消除,作为 K 歌始终开启的兜底。
 *
 * K 歌模块双路径（叠加,非互斥,总控见 VocalSeparationViewModel.kt）:
 *   ① DSP 路径（本类,默认/兜底,永远启用）
 *      1 阶 RC 低通 250Hz 截止,无 FFT 缓冲,零延迟
 *   ② HQ 模型路径（HqSeparationOrchestrator + HT-Demucs ONNX,按需启用）
 *      异步推理,输出伴奏 stem,需本地有模型文件
 *
 * 历史备忘：VocalRemovalProcessor 是更精细的 4 阶 Linkwitz-Riley DSP 实现,
 * 因"激进"的人声消除取舍（人声同频段居中乐器一并消除问题更少）已被本类取代。
 * 保留为高保真/离线批处理备选,不要尝试复活注入（CPU 8× 代价且已无引用方）。
 * ─────────────────────────────────────────────────────────────────────
 */
// UnstableApi 属 androidx @RequiresOptIn 机制，须用 androidx.annotation.OptIn。
@androidx.annotation.OptIn(UnstableApi::class)
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
        // P7 修复：reset() 是 Media3 在切歌/重建 AudioSink 时调用的接口方法，
        // 只应重置处理器内部音频状态，不应清零 `enabled`（用户是否开启人声消除的意图）。
        // 原实现 `enabled = false` 导致切歌后伴唱 DSP 静默失效，但 MainViewModel 的
        // `_vocalRemovalEnabled` 仍为 true，UI 与真实状态不一致且无法自愈。
        configured = false
        ended = false
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        outputBuffer = EMPTY_BUFFER
        buffer = EMPTY_BUFFER
        midLowpassState = 0f
    }
}

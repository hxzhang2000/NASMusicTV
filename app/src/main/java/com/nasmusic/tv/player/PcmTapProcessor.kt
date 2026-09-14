package com.nasmusic.tv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.nasmusic.tv.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM 采集处理器 —— P6 降级通道的**采集侧**（AudioSink 处理器链）。
 *
 * 背景：部分国产 TV 的 `Visualizer` 绑定成功却持续返回全 0（方案 §3.5）。
 * 此时改用 AudioSink 里流过的 PCM 数据自己算频谱，绕开系统 Visualizer。
 *
 * 三条铁律：
 *  ① `queueInput()` **只做**降混 + memcpy 到环形缓冲（目标 < 20µs），
 *    **严禁在此做 FFT** —— 阻塞播放线程就是爆音；
 *  ② 音频**原样透传**，本处理器绝不改动任何采样值（与 `SpectralMaskProcessor` 串联时尤其重要）；
 *  ③ 默认 `capturing = false`，只在判定降级后才置 true —— 正常设备零开销。
 *
 * 挂在处理器链**最前**（`arrayOf(pcmTapProcessor, spectralMaskProcessor)`），
 * 取 EQ / 人声消除之前的原始信号，保证频谱不受这些后处理影响。
 *
 * 注：原注释此处写作 "vocalRemovalProcessor" 系 VocalRemovalProcessor 重构
 * 为 SpectralMaskProcessor 后未同步的 stale comment,2026-09-13 校正。
 */
class PcmTapProcessor(
    private val ring: PcmRingBuffer,
    private val tap: PcmSpectrumTap
) : AudioProcessor {

    companion object {
        private const val TAG = "PcmTapProcessor"
        private val EMPTY_BUFFER: ByteBuffer =
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    /** 是否采数据（由 SpectrumAnalyzer 在降级时打开） */
    @Volatile
    var capturing: Boolean = false

    private var configured = false
    private var ended = false
    private var encoding = C.ENCODING_PCM_16BIT
    private var channelCount = 2
    private var blockAlign = 4

    /** 降混后的单声道缓冲（按需扩容，运行期不分配） */
    private var monoBuf = FloatArray(4096)

    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER

    // ── AudioProcessor 接口 ──

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            AppLog.w(TAG, "configure: unsupported encoding=${inputAudioFormat.encoding}, bypassing")
            configured = false
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val bytesPerSample = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) 2 else 4
        encoding = inputAudioFormat.encoding
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        blockAlign = bytesPerSample * channelCount
        tap.sampleRate = inputAudioFormat.sampleRate
        configured = true
        // release 可见（直调 Log.i，避免 R8 折叠）：确认处理器链真的在跑、降级通道可用
        android.util.Log.i(
            TAG,
            "configure: ${inputAudioFormat.sampleRate}Hz ch=$channelCount enc=$encoding capturing=$capturing"
        )
        // 不改变音频格式：返回入参即"透传"
        return inputAudioFormat
    }

    override fun isActive(): Boolean = configured

    override fun queueEndOfStream() {
        ended = true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()

        // ① 采集：只读不改 —— 读完把 position 复位，交给下面的透传
        if (configured && capturing && remaining >= blockAlign) {
            val start = inputBuffer.position()
            try {
                capture(inputBuffer, start, remaining)
            } catch (e: Exception) {
                AppLog.e(TAG, "capture failed", e)
            }
            inputBuffer.position(start)
        }

        // ② 原样透传（媒体3 依赖 position 前进来消费输入）
        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        }
        buffer.clear()
        buffer.put(inputBuffer)
        buffer.flip()
        outputBuffer = buffer
    }

    /** 降混为单声道并写入环形缓冲。**不要在这里做重活**。 */
    private fun capture(src: ByteBuffer, start: Int, bytes: Int) {
        val frames = bytes / blockAlign
        if (frames <= 0) return
        if (monoBuf.size < frames) monoBuf = FloatArray(frames)
        val mono = monoBuf
        val ch = channelCount

        src.position(start)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val inv = 1f / (ch * 32768f)
                for (i in 0 until frames) {
                    var acc = 0f
                    for (c in 0 until ch) acc += src.short.toFloat()
                    mono[i] = acc * inv
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val inv = 1f / ch
                for (i in 0 until frames) {
                    var acc = 0f
                    for (c in 0 until ch) acc += src.float
                    mono[i] = acc * inv
                }
            }
            else -> return
        }
        ring.write(mono, frames)
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = ended && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        buffer = EMPTY_BUFFER
        ended = false
        ring.clear()
        // 修复：flush() 由 Media3 在 seek/切歌/重新配置时调用。
        // 若降级通道已激活（capturing=true），必须保持 FFT 线程存活——
        // 旧实现无条件 tap.stop() 会永久杀死分析线程（且没有任何重启点），
        // 导致切歌/seek 后 PCM 频谱静默、效果再次全 0 静止。
        // 只需清空环形缓冲（版本号归零），pump 会跳过无数据帧，开销为零。
        if (!capturing) {
            tap.stop()
        }
    }

    override fun reset() {
        // 与 SpectralMaskProcessor 的 P7 教训一致：reset() 由 Media3 在切歌/重建
        // AudioSink 时调用，只重置音频状态，**不清零 capturing**（那是降级决策的状态）。
        configured = false
        ended = false
        outputBuffer = EMPTY_BUFFER
        buffer = EMPTY_BUFFER
        ring.clear()
    }
}

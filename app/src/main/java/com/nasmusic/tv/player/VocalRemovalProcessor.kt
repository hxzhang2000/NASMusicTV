package com.nasmusic.tv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.nasmusic.tv.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 人声消除处理器（卡拉OK模式）
 *
 * 基于 Mid-Side 编码 + 分频段处理：
 * 1. 将立体声 L/R 转为 Mid = (L+R)/2, Side = (L-R)/2
 * 2. 对 Mid 信号分频：低通 120Hz（保留贝斯/底鼓）+ 高通 6kHz（保留镲片），跳过人声频段 —— 消除居中人声
 * 3. 对 Side 信号同样分频，将其 vocal 频段（120Hz~6kHz）额外衰减 88% —— 消除偏置/混响残留人声
 * 4. 重组：L_out = newMid + newSide, R_out = newMid - newSide，再乘补偿增益放大伴奏
 *
 * 说明：
 * - 男声基频约 85~180Hz，低通必须压到 ~120Hz 以下才能有效消除居中人声的基频分量。
 * - 所有滤波器均为四阶 Linkwitz-Riley（两个二阶 biquad 级联，-24dB/oct），
 *   过渡带比二阶陡一倍，人声频段边缘的残留更少，分频点相加平坦。
 *
 * 仅支持 16-bit PCM 立体声。其他格式自动 bypass。
 */
class VocalRemovalProcessor : AudioProcessor {

    companion object {
        private const val TAG = "VocalRemoval"
        /** Mid 低通截止（保留底鼓/贝斯，消除男声基频 85~180Hz） */
        private const val LOW_PASS_FREQ = 120.0
        /** Mid 高通截止（保留镲片，消除齿音 5~8kHz） */
        private const val HIGH_PASS_FREQ = 6000.0
        private const val FILTER_Q = 0.707
        /** Side 声道 vocal 频段衰减系数（1.0 = 完全保留，0.0 = 完全消除） */
        private const val SIDE_VOCAL_KEEP = 0.12f
        /** 整体补偿增益：抵消人声消除后电平下降，等效放大伴奏（小心削波，1.5~1.7 较安全） */
        private const val MAKEUP_GAIN = 1.6f
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

    // Mid 声道滤波器（消除居中人声）—— 四阶级联
    private var lpMid: BiquadCascade? = null
    private var hpMid: BiquadCascade? = null
    // Side 声道滤波器（消除偏置/混响残留人声）—— 四阶级联
    private var lpSide: BiquadCascade? = null
    private var hpSide: BiquadCascade? = null

    fun setEnabled(enabled: Boolean) {
        if (this.enabled != enabled) {
            AppLog.d(TAG, "setEnabled: $enabled")
            this.enabled = enabled
            lpMid?.reset()
            hpMid?.reset()
            lpSide?.reset()
            hpSide?.reset()
        }
    }

    fun isEnabled(): Boolean = enabled

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        AppLog.d(TAG, "configure: sampleRate=${inputAudioFormat.sampleRate}, channels=${inputAudioFormat.channelCount}, encoding=${inputAudioFormat.encoding}")

        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            AppLog.w(TAG, "configure: unsupported format, bypassing")
            configured = false
            outputFormat = AudioProcessor.AudioFormat.NOT_SET
            return AudioProcessor.AudioFormat.NOT_SET
        }

        outputFormat = inputAudioFormat
        configured = true

        val sr = inputAudioFormat.sampleRate
        lpMid = BiquadCascade(sr, LOW_PASS_FREQ, FILTER_Q, BiquadFilter.Type.LOW_PASS)
        hpMid = BiquadCascade(sr, HIGH_PASS_FREQ, FILTER_Q, BiquadFilter.Type.HIGH_PASS)
        lpSide = BiquadCascade(sr, LOW_PASS_FREQ, FILTER_Q, BiquadFilter.Type.LOW_PASS)
        hpSide = BiquadCascade(sr, HIGH_PASS_FREQ, FILTER_Q, BiquadFilter.Type.HIGH_PASS)

        AppLog.d(TAG, "configure: ready, sampleRate=$sr")
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
            buffer.put(inputBuffer)
            buffer.flip()
            outputBuffer = buffer
            return
        }

        val lpM = lpMid
        val hpM = hpMid
        val lpS = lpSide
        val hpS = hpSide
        if (lpM == null || hpM == null || lpS == null || hpS == null) {
            buffer.put(inputBuffer)
            buffer.flip()
            outputBuffer = buffer
            return
        }

        while (inputBuffer.remaining() >= 4) {
            val left = inputBuffer.short.toInt()
            val right = inputBuffer.short.toInt()

            val mid = (left + right) / 2
            val side = (left - right) / 2

            val midF = mid.toFloat()
            // 新 Mid = 只保留低频(贝斯) + 高频(镲片)，消除 vocal 频段的居中人声
            val newMid = (lpM.process(midF) + hpM.process(midF)).toInt()

            val sideF = side.toFloat()
            // 新 Side = 原始 Side 减去 vocal 频段残留（衰减 70%）
            val sideLow = lpS.process(sideF)
            val sideHigh = hpS.process(sideF)
            val sideVocal = sideF - sideLow - sideHigh
            val newSide = (sideLow + sideHigh + sideVocal * SIDE_VOCAL_KEEP).toInt()

            buffer.putShort(clamp(((newMid + newSide) * MAKEUP_GAIN).toInt()))
            buffer.putShort(clamp(((newMid - newSide) * MAKEUP_GAIN).toInt()))
        }

        while (inputBuffer.hasRemaining()) {
            buffer.put(inputBuffer.get())
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
        lpMid?.reset()
        hpMid?.reset()
        lpSide?.reset()
        hpSide?.reset()
    }

    override fun reset() {
        AppLog.d(TAG, "reset")
        enabled = false
        configured = false
        ended = false
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        outputBuffer = EMPTY_BUFFER
        buffer = EMPTY_BUFFER
        lpMid = null
        hpMid = null
        lpSide = null
        hpSide = null
    }

    private fun clamp(v: Int): Short =
        v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private class BiquadFilter(
        sampleRate: Int,
        cutoffFreq: Double,
        q: Double,
        type: Type
    ) {
        enum class Type { LOW_PASS, HIGH_PASS }

        private val b0: Float
        private val b1: Float
        private val b2: Float
        private val a1: Float
        private val a2: Float

        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        init {
            val w0 = 2.0 * PI * cutoffFreq / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q)
            val a0 = 1.0 + alpha

            val b0r: Double
            val b1r: Double
            val b2r: Double

            when (type) {
                Type.LOW_PASS -> {
                    b0r = (1.0 - cosW0) / 2.0
                    b1r = 1.0 - cosW0
                    b2r = (1.0 - cosW0) / 2.0
                }
                Type.HIGH_PASS -> {
                    b0r = (1.0 + cosW0) / 2.0
                    b1r = -(1.0 + cosW0)
                    b2r = (1.0 + cosW0) / 2.0
                }
            }

            b0 = (b0r / a0).toFloat()
            b1 = (b1r / a0).toFloat()
            b2 = (b2r / a0).toFloat()
            a1 = (-2.0 * cosW0 / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
        }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
        }
    }

    /**
     * 四阶滤波器：两个二阶 biquad 级联，斜率 -24dB/oct（Linkwitz-Riley 4th order）。
     * 前后级使用相同的 Q=0.707 得到 Linkwitz-Riley 分频特性，分频点相加平坦。
     */
    private class BiquadCascade(
        sampleRate: Int,
        cutoffFreq: Double,
        q: Double,
        type: BiquadFilter.Type
    ) {
        private val stages = arrayOf(
            BiquadFilter(sampleRate, cutoffFreq, q, type),
            BiquadFilter(sampleRate, cutoffFreq, q, type)
        )

        fun process(x: Float): Float {
            var v = x
            for (s in stages) v = s.process(v)
            return v
        }

        fun reset() {
            for (s in stages) s.reset()
        }
    }
}

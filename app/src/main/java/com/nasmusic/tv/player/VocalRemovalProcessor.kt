package com.nasmusic.tv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
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
 * 2. 对 Mid 信号分频：低通 120Hz（保留贝斯/底鼓）+ 高通 8kHz（保留镲片/空气感），
 *    提取 vocal 频段后**深度衰减（保留 15%）而非完全挖空** —— 消除居中，同时避免
 *    与主旋律、吉他等居中乐器同频段的伴奏一起消失（Audacity 官方建议：伴奏变薄就降低处理强度）
 * 3. 对 Side 信号同样分频，将其 vocal 频段保留 50%（仅轻度削减，保住立体声宽度/混响伴奏）
 * 4. 重组：L_out = newMid + newSide, R_out = newMid - newSide，再乘 1.25x 补偿增益
 *
 * 说明：
 * - 男声基频约 85~180Hz，低通必须压到 ~120Hz 以下才能有效消除人声的基频分量。
 * - 人声主要能量集中在 200Hz~4kHz；高通压到 8kHz 以上即可同时保住镲片/空气感
 *   （Audacity 官方建议 High Cut ≥ 8000Hz）。
 * - 所有滤波器均为四阶 Linkwitz-Riley（两个二阶 biquad 级联，-24dB/oct），
 *   过渡带比二阶陡一倍，人声频段边缘的残留更少，分频点相加平坦。
 *
 * 仅支持 16-bit PCM 立体声。其他格式自动 bypass。
 *
 * ─────────────────────────────────────────────────────────────────────
 * ⚠️ 死代码 / 历史实现（2026-09-13 标注）
 * ─────────────────────────────────────────────────────────────────────
 * 状态：全项目无任何调用方（PlaybackService 实际注入的是 SpectralMaskProcessor,
 *       PlayerManager 字段类型也是 SpectralMaskProcessor）。
 *       PcmTapProcessor.kt:21 注释里仍把本类称作 "vocalRemovalProcessor",
 *       系重构后未同步的 stale comment,见该处说明。
 *
 * 设计取向（"精细/温和"）：
 *   - Mid vocal 频段保留 15%,Side vocal 频段保留 50% —— 避免人声同频段
 *     居中乐器（主旋律/吉他/底鼓）一并被消除（"伴奏变薄"问题）
 *   - 4 阶 Linkwitz-Riley biquad 级联,过渡带陡,残人声少
 *   - 滤波器计算量约 8 倍于一阶 RC,TV 设备上对 CPU 不友好
 *
 * 取代实现 SpectralMaskProcessor 的取向（"激进"）：
 *   - 一阶低通 250Hz 直接把 Mid 频段全滤掉,人声消除更彻底
 *   - Side 1.2x 增益放大,补偿立体声宽度
 *   - 低频居中乐器（贝斯/底鼓）会有损失
 *   - 滤波计算量约为本实现的 1/8
 *
 * 选"激进"的产品理由：K 歌用户对"残人声"零容忍,对"低频损失"几乎无感。
 *
 * 保留本文件的价值：
 *   - 算法本身是教科书级实现,文档详尽,适合作为高保真场景 / 离线批处理
 *     / 未来"精细模式"切换的备选实现
 *   - 删除前请确认：grep -r VocalRemovalProcessor app/src 仅 PcmTapProcessor
 *     注释一处引用,需同步修改
 *
 * 复活步骤（备忘）：修改 PlaybackService.kt:197 为 `VocalRemovalProcessor()`,
 * PlayerManager.kt:189 字段类型同步改为 `VocalRemovalProcessor?`,
 * PcmTapProcessor.kt:21 注释同步更新。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 🎤 在 K 歌模块中的位置（2026-09-13 标注）
 * ─────────────────────────────────────────────────────────────────────
 * 本类是历史/精细版 DSP 实现,已被取代,**不要尝试复活注入**。当前 K 歌模块
 * 双路径为（详见 HqSeparationOrchestrator.kt 头部 KDoc）:
 *   ① DSP 路径: SpectralMaskProcessor（1 阶低通 250Hz,在用,实时兜底）
 *   ② HQ 模型路径: HqSeparationOrchestrator + HT-Demucs ONNX（按需启用）
 * ─────────────────────────────────────────────────────────────────────
 */
// UnstableApi 属 androidx @RequiresOptIn 机制，须用 androidx.annotation.OptIn。
@androidx.annotation.OptIn(UnstableApi::class)
class VocalRemovalProcessor : AudioProcessor {

    companion object {
        private const val TAG = "VocalRemoval"
        /** Mid 低通截止（保留底鼓/贝斯，消除男声基频 85~180Hz） */
        private const val LOW_PASS_FREQ = 120.0
        /** Mid 高通截止（保留镲片/空气感；Audacity 建议 ≥ 8000Hz） */
        private const val HIGH_PASS_FREQ = 8000.0
        private const val FILTER_Q = 0.707
        /**
         * Mid 声道 vocal 频段保留系数：1.0 = 完全保留，0.0 = 完全消除。
         *
         * 取 0.15 意味着 vocal 频段深度衰减 85%（而非归零）——完全挖空会把与
         * 人声同频段的居中乐器（主旋律/吉他等）一起抹掉，造成"人声没了音乐也没了"。
         * 参考 Audacity Vocal Reduction 的 Strength 思路（伴奏变薄即降低强度）。
         */
        private const val MID_VOCAL_KEEP = 0.15f
        /**
         * Side 声道 vocal 频段保留系数：仅轻度削减（保留一半），保住立体声宽度伴奏。
         * 网搜共识：Side = 立体声宽度，过度衰减会把左右铺开的乐器（吉他/和声/混响）一并削掉。
         */
        private const val SIDE_VOCAL_KEEP = 0.5f
        /** 整体补偿增益：抵消衰减后电平下降，等效放大伴奏（1.0~1.3 较安全，避免削波） */
        private const val MAKEUP_GAIN = 1.25f
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
            // 新 Mid = 低频段 + 高频段 + vocal 频段保留 15%
            // （深度衰减而非归零，避免与主旋律/吉他同频段的居中乐器一起消失）
            val midLow = lpM.process(midF)
            val midHigh = hpM.process(midF)
            val midVocal = midF - midLow - midHigh
            val newMid = (midLow + midHigh + midVocal * MID_VOCAL_KEEP).toInt()

            val sideF = side.toFloat()
            // 新 Side = 低频段 + 高频段 + vocal 频段保留 50%（轻度衰减，保住立体声宽度伴奏）
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

package com.nasmusic.tv.player

import android.media.audiofx.Visualizer
import android.os.SystemClock
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.visualizer.SpectrumContract
import com.nasmusic.tv.visualizer.SpectrumRepository
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 频谱分析器
 *
 * 使用 Android [Visualizer] 从 ExoPlayer 的音频会话捕获实时 FFT 数据，
 * 通过分段密集映射（Perceptual Frequency Warping）将 FFT 频段映射为
 * 64 根感知加权柱子，写入 [SpectrumRepository] 的 [AudioFrame]。
 *
 * 柱子分配（64 柱，视觉感知优化）：
 *   [ 0- 9]  20~80Hz     10根   底鼓下潜
 *   [10-39]  80~250Hz    30根   鼓点/贝斯核弹区（主视觉冲击）
 *   [40-55]  250Hz~3kHz  16根   人声/主旋律核心区
 *   [56-63]  3kHz~20kHz   8根   超高频压缩区（点缀）
 *
 * **双通道输出（关键设计）**：
 *   - 显示通道 [displayBuf]：gamma ^0.75，抬升小信号保证可见
 *   - 律动通道 [linearBuf] ：线性值，保留真实强弱差异
 *   律动通道必须线性——否则 gamma 会把轻/重鼓点的比值从 5:1 压到 3.3:1，
 *   画面"看着有反应但就是没劲"（历史 BUG ⑨-b）。
 *
 * **零分配**：所有缓冲在 attach 时预分配，运行期仅做标量运算与 arraycopy。
 */
class SpectrumAnalyzer {

    private var visualizer: Visualizer? = null

    /** 频谱数据仓库（唯一写入方）。由 PlayerEqualizer 注入 */
    var repository: SpectrumRepository? = null

    /** 全局运行峰值（保留原逻辑，用于静音判定参考） */
    private var runningPeak = 1f
    /**
     * 自适应噪声基底——跟踪设备底噪水平。
     * 无有效信号时快速下降，有信号时极慢上升。
     */
    private var noiseFloor = 10f
    /**
     * 低频区运行峰值 —— AGC 归一化分母。
     *
     * 关键：分母必须是**慢衰减的历史峰值**，而非当前帧峰值。
     * 若用当前帧峰值，分子分母同步缩放、比值恒为 1，
     * 低频柱会被永久钉死在满格，轻鼓点与重鼓点长得一模一样（历史 BUG ⑨）。
     */
    private var lowRunningPeak = SpectrumContract.AGC_FLOOR

    // ── P6 降级通道与仲裁 ───────────────────────────────
    /** PCM 降级通道；由 PlayerEqualizer 注入。null = 该设备不支持降级 */
    var pcmFallback: PcmFallbackChannel? = null

    /** 播放状态。暂停时不参与降级判定，否则“暂停导致的静音”会被误判为 Visualizer 失效 */
    @Volatile
    var isPlaying: Boolean = false

    /** 当前数据源是否已降级到 PCM 通道 */
    @Volatile
    var usingPcm: Boolean = false
        private set

    /** 连续静音帧数（仅 Visualizer 通道） */
    private var silentFrames = 0
    /** 本轮静音起始时刻 */
    private var silentSinceMs = 0L
    /** 降级抑制截止时刻（两条通道都被证实无信号后不再反复尝试） */
    private var degradeBlockedUntilMs = 0L
    /** PCM 探测窗口起点 */
    private var pcmProbeStartMs = 0L
    /** PCM 激活后是否出现过有效信号 */
    private var pcmSignalSeen = false

    // ── 预分配缓冲（零分配）─────────────────────────────────────
    private var magnitudeBuf = FloatArray(0)
    private var barBuf = FloatArray(SpectrumContract.BAR_COUNT)
    private var displayBuf = FloatArray(SpectrumContract.BAR_COUNT)
    private var linearBuf = FloatArray(SpectrumContract.BAR_COUNT)
    private var waveBuf = FloatArray(SpectrumContract.WAVE_POINTS)

    companion object {
        private const val TAG_LOG = "SpectrumAnalyzer"

        /** 频谱柱数——64 根，使用感知频率翘曲映射 */
        const val BAR_COUNT = SpectrumContract.BAR_COUNT
        /** 最小有效幅值 */
        private const val MIN_AMPLITUDE = SpectrumContract.MIN_AMPLITUDE
        /** FFT 采样率兜底，用于将 bin 索引映射为频率 */
        private const val SAMPLING_RATE = 44100
        /** 低频区柱区间：用于 AGC 锚定（80~250Hz 鼓点核心区） */
        private const val LOW_BAND_FROM = 10
        private const val LOW_BAND_TO = 39

        // ── P6 降级仲裁参数 ──────────────────────────────
        /** PCM 通道静音阈值：PCM 是自己算的，数字静音即真静音，不走自适应噪声门限 */
        private const val PCM_SILENCE_EPS = 1e-3f
        /** Visualizer 连续静音帧数阈值（开发方案 §3.5） */
        private const val DEGRADE_FRAMES = 20
        /** 静音持续时长下限：避免歌曲间奏（几百 ms）误触发降级 */
        private const val DEGRADE_MIN_SPAN_MS = 2_000L
        /** PCM 探测窗口：窗口内始终无信号 → 回滚到 Visualizer */
        private const val PCM_PROBE_MS = 3_000L
        /** 回滚后的抑制时长：两条通道都拿不到信号时，别每几秒折腾一次 */
        private const val DEGRADE_BLOCK_MS = 300_000L
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
            resetPcmArbitration()
            val vis = Visualizer(audioSessionId)

            // 取设备支持的最大值：写死 1024 会浪费支持 2048 的设备
            // （bass 区 bin 数 5 → 11，低频分辨率翻倍）
            val maxSize = Visualizer.getCaptureSizeRange()[1]
            val targetSize = maxSize
            vis.captureSize = targetSize
            val numFftBins = targetSize / 2

            // 缓冲按实际 bins 预分配一次
            if (magnitudeBuf.size < numFftBins) magnitudeBuf = FloatArray(numFftBins)

            // 重置跟踪器（新音频会话从头开始）
            runningPeak = 1f
            lowRunningPeak = SpectrumContract.AGC_FLOOR

            vis.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (waveform == null) return
                        fillWaveform(waveform)
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft == null || fft.size < 2) return
                        processFft(fft, numFftBins, samplingRate)
                    }
                },
                SpectrumContract.CAPTURE_INTERVAL_US,
                true,    // 波形（E12 等波形类效果用，成本可忽略）
                true     // FFT
            )

            vis.enabled = true
            visualizer = vis
            AppLog.d("SpectrumAnalyzer", "Attached: captureSize=$targetSize, fftBins=$numFftBins, bars=$BAR_COUNT")

        } catch (e: SecurityException) {
            AppLog.w("SpectrumAnalyzer", "RECORD_AUDIO permission denied, spectrum unavailable", e)
        } catch (e: UnsupportedOperationException) {
            AppLog.w("SpectrumAnalyzer", "Audio session $audioSessionId invalid or Visualizer unsupported", e)
        } catch (e: Exception) {
            AppLog.e("SpectrumAnalyzer", "Failed to attach Visualizer", e)
        }
    }

    /** 波形：8-bit unsigned（中心 128）→ -1..1，下采样到 WAVE_POINTS */
    private fun fillWaveform(waveform: ByteArray) {
        val n = waveform.size
        val step = (n / SpectrumContract.WAVE_POINTS).coerceAtLeast(1)
        var w = 0
        var i = 0
        while (w < SpectrumContract.WAVE_POINTS && i < n) {
            waveBuf[w] = (waveform[i] - 128) / 128f
            w++
            i += step
        }
    }

    /**
     * 释放 Visualizer 资源
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
        stopPcmFallback()
        silentFrames = 0
        barBuf.fill(0f)
        displayBuf.fill(0f)
        linearBuf.fill(0f)
        waveBuf.fill(0f)
        repository?.reset()
        AppLog.d("SpectrumAnalyzer", "Released")
    }

    // -----------------------------------------------------------------
    // FFT 处理 — 感知频率翘曲 (Perceptual Frequency Warping)
    // -----------------------------------------------------------------

    /**
     * 处理一帧 FFT 数据。
     *
     * 全流程零分配：写入预分配的 [barBuf] / [displayBuf] / [linearBuf]。
     */
    internal fun processFft(fft: ByteArray, numBins: Int, samplingRate: Int) {
        // 防御：attach 时已按设备 captureSize 预分配；若回调携带的 bins 更多
        // （设备能力变化），此处补一次分配而不是越界崩溃。正常运行期为 no-op。
        if (magnitudeBuf.size < numBins) magnitudeBuf = FloatArray(numBins)
        val magnitudes = magnitudeBuf
        for (i in 0 until numBins) {
            val real = fft[i * 2].toFloat()
            val imag = fft[i * 2 + 1].toFloat()
            magnitudes[i] = sqrt(real * real + imag * imag)
        }
        analyze(
            magnitudes, numBins,
            if (samplingRate > 0) samplingRate else SAMPLING_RATE,
            fromPcm = false
        )
    }

    /**
     * 幅值谱 → [SpectrumRepository] 的统一分析链（Visualizer / PCM 两条通道共用）。
     *
     * 共用是刻意的：AGC、感知加权柱映射、双通道输出只应有一份实现 ——
     * 否则两条通道的观感会不一致，这是 P6 引入时最大的坑。
     *
     * 加锁：降级切换瞬间两条通道可能各有一帧在途，避免并发写入共享缓冲。无竞争时销费可忽略。
     *
     * @param fromPcm true = PCM 降级通道。其静音用绝对值判定（数字静音即真静音），
     *                不走自适应噪声门限（那是为系统 Visualizer 的底噪准备的）。
     */
    @Synchronized
    internal fun analyze(magnitudes: FloatArray, numBins: Int, samplingRate: Int, fromPcm: Boolean) {
        if (numBins < 2) return

        // 1) 帧统计（跳过直流分量）
        var frameMax = 0f
        var sumSq = 0f
        for (i in 1 until numBins) {
            val m = magnitudes[i]
            if (m > frameMax) frameMax = m
            sumSq += m * m
        }
        val rms = sqrt(sumSq / (numBins - 1))

        if (fromPcm) {
            onPcmFrame(frameMax)
            if (frameMax <= PCM_SILENCE_EPS) {
                emitSilence()
                return
            }
        } else {
            // 已降级：丢弃 Visualizer 通道的残留帧
            // （enabled=false 可能失败，或回调已在途），避免全 0 帧覆盖 PCM 结果导致画面闪烁
            if (usingPcm) return
            // 2) 自适应静音门限（跟踪噪声基底）
            if (rms < noiseFloor) {
                noiseFloor = rms * 0.9f + noiseFloor * 0.1f          // 快速下降
            } else {
                noiseFloor = noiseFloor * 0.999f + rms * 0.001f      // 极慢上升
            }
            if (frameMax < noiseFloor * 3.0f) {
                onVisualizerSilence()
                emitSilence()
                return
            }
            silentFrames = 0
        }

        // 3) 全局运行峰值（保留原逻辑）
        runningPeak = maxOf(runningPeak * 0.94f, frameMax, 0.001f)

        // 4) 分段密集映射 + 战区增益：FFT bins → 64 根感知加权柱子
        val effectiveRate = if (samplingRate > 0) samplingRate else SAMPLING_RATE
        val freqPerBin = effectiveRate.toFloat() / (numBins * 2)
        val result = barBuf
        result.fill(0f)

        for (bin in 1 until numBins) {           // 跳过直流分量
            val freq = bin * freqPerBin
            val mag = magnitudes[bin]
            val barIndex = getTargetBarIndex(freq)
            val weighted = mag * getFrequencyWeight(freq)
            if (weighted > result[barIndex]) {
                result[barIndex] = weighted      // 区间取加权最大值，保留瞬态峰值
            }
        }

        // 5) AGC 归一化 —— 分母为**低频区运行峰值**（修 ⑨）
        var lowBandPeak = 0f
        for (b in LOW_BAND_FROM..LOW_BAND_TO) {
            if (result[b] > lowBandPeak) lowBandPeak = result[b]
        }
        lowRunningPeak = maxOf(
            lowRunningPeak * SpectrumContract.AGC_DECAY,
            lowBandPeak,
            SpectrumContract.AGC_FLOOR
        )
        val safeDenominator = lowRunningPeak

        // 6) 双通道输出
        for (bar in 0 until BAR_COUNT) {
            val normalized = (result[bar] / safeDenominator).coerceIn(0f, 1f)
            linearBuf[bar] = normalized                                     // 律动：线性
            displayBuf[bar] = normalized.pow(SpectrumContract.DISPLAY_GAMMA)
                .coerceIn(MIN_AMPLITUDE, 1f)                                // 显示：gamma
        }

        emit()
    }

    /**
     * 静音：写入**定长全 0** 数组。
     * 旧实现返回 FloatArray(0) 会让渲染层柱数变 0、频谱整体消失（历史 BUG ⑮）。
     */
    private fun emitSilence() {
        runningPeak = 1f
        barBuf.fill(0f)
        displayBuf.fill(0f)
        linearBuf.fill(0f)
        // 波形一并归零：否则静音期间波形类效果（E12/E16）显示上一帧残影
        waveBuf.fill(0f)
        emit()
    }

    // -----------------------------------------------------------------
    // P6 降级通道仲裁
    // -----------------------------------------------------------------

    /**
     * Visualizer 通道持续静音时累计时长；达到阈值且确有播放 → 切换到 PCM 通道。
     *
     * 为什么不能只看“连续 20 帧”：采集周期 20ms，20 帧仅 400ms ——
     * 歌曲间奏、淡出段落都会被误判，所以额外要求静音**持续时间** ≥ 2s。
     */
    private fun onVisualizerSilence() {
        if (usingPcm) return
        if (!isPlaying) {
            silentFrames = 0
            return
        }
        val now = SystemClock.uptimeMillis()
        if (silentFrames == 0) silentSinceMs = now
        silentFrames++
        if (silentFrames < DEGRADE_FRAMES) return
        if (now - silentSinceMs < DEGRADE_MIN_SPAN_MS) return
        if (now < degradeBlockedUntilMs) return
        degradeToPcm()
    }

    /** PCM 通道帧判定：探测窗口内始终无信号 → 回滚（别让画面比降级前更差） */
    private fun onPcmFrame(frameMax: Float) {
        if (!usingPcm) return
        if (frameMax > PCM_SILENCE_EPS) {
            pcmSignalSeen = true
            return
        }
        if (!pcmSignalSeen && SystemClock.uptimeMillis() - pcmProbeStartMs > PCM_PROBE_MS) {
            rollbackToVisualizer()
        }
    }

    /** 降级：停用系统 Visualizer，启用 PCM 采集 + 后台 FFT */
    private fun degradeToPcm() {
        val channel = pcmFallback ?: return
        AppLog.w(
            TAG_LOG,
            "Visualizer silent ${silentFrames}f/${DEGRADE_MIN_SPAN_MS}ms -> enabling PCM fallback"
        )
        usingPcm = true
        silentFrames = 0
        pcmSignalSeen = false
        pcmProbeStartMs = SystemClock.uptimeMillis()

        try {
            visualizer?.enabled = false
        } catch (e: Exception) {
            AppLog.w(TAG_LOG, "failed to disable Visualizer during degrade", e)
        }
        resetTracking()
        channel.setOnMagnitudes { mag, bins, rate -> analyze(mag, bins, rate, fromPcm = true) }
        channel.activate()
    }

    /** 回滚：PCM 也拿不到信号 → 恢复 Visualizer，并在一段时间内不再尝试降级 */
    private fun rollbackToVisualizer() {
        AppLog.w(TAG_LOG, "PCM silent ${PCM_PROBE_MS}ms -> rolling back to Visualizer")
        usingPcm = false
        pcmFallback?.deactivate()
        degradeBlockedUntilMs = SystemClock.uptimeMillis() + DEGRADE_BLOCK_MS
        silentFrames = 0
        resetTracking()
        try {
            visualizer?.enabled = true
        } catch (e: Exception) {
            AppLog.w(TAG_LOG, "failed to re-enable Visualizer on rollback", e)
        }
    }

    /** 清空跟踪状态：两条通道的量级不同，切换时必须重置，否则旧峰值会压制新信号 */
    private fun resetTracking() {
        runningPeak = 1f
        lowRunningPeak = SpectrumContract.AGC_FLOOR
        noiseFloor = 10f
        barBuf.fill(0f)
        displayBuf.fill(0f)
        linearBuf.fill(0f)
        waveBuf.fill(0f)
    }

    /** 新音频会话 / 重新 attach：回到 Visualizer 优先的初始状态 */
    private fun resetPcmArbitration() {
        usingPcm = false
        silentFrames = 0
        silentSinceMs = 0L
        pcmProbeStartMs = 0L
        pcmSignalSeen = false
        degradeBlockedUntilMs = 0L
        pcmFallback?.deactivate()
    }

    /** 释放降级通道 */
    private fun stopPcmFallback() {
        pcmFallback?.deactivate()
        usingPcm = false
    }


    /**
     * 写入仓库——[SpectrumRepository] 是 [com.nasmusic.tv.visualizer.AudioFrame] 的唯一写入方。
     *
     * 渲染节流（33ms）由仓库负责：它只递增 `frameSeq`，不再向 UI 发布数组副本。
     * 历史实现在此处 `emit(displayBuf.copyOf())`（BUG ⑬），使订阅方每 33ms 强制重组一次；
     * 该数据的唯一消费方（NowPlaying 48dp 小条）已随全屏舞台上线移除，故整条通道删除。
     */
    private fun emit() {
        repository?.onFrame(displayBuf, linearBuf, waveBuf, android.os.SystemClock.uptimeMillis())
    }

    /**
     * 分段密集映射——将物理频率直接映射到目标柱子索引（64 柱版）。
     *
     * 把"视觉像素"集中在人耳敏感区域（20Hz~3kHz），超高频严重压缩为点缀。
     */
    private fun getTargetBarIndex(freq: Float): Int {
        return when {
            // [0-9] 极低频 20~80Hz → 10 根
            freq <= 80f -> {
                val f = ((freq - 20f) / (80f - 20f)).coerceIn(0f, 1f)
                (f * 9).toInt().coerceIn(0, 9)
            }
            // [10-39] 鼓点核弹区 80~250Hz → 30 根
            freq <= 250f -> {
                val f = ((freq - 80f) / (250f - 80f)).coerceIn(0f, 1f)
                (f * 29).toInt().coerceIn(0, 29) + 10
            }
            // [40-55] 人声核心区 250Hz~3kHz → 16 根
            freq <= 3000f -> {
                val f = ((freq - 250f) / (3000f - 250f)).coerceIn(0f, 1f)
                (f * 15).toInt().coerceIn(0, 15) + 40
            }
            // [56-63] 超高频压缩区 3kHz~20kHz → 8 根
            else -> {
                val f = ((freq - 3000f) / (20000f - 3000f)).coerceIn(0f, 1f)
                (f * 7).toInt().coerceIn(0, 7) + 56
            }
        }
    }

    /**
     * 战区增益——根据频段给人声/鼓点更高的物理幅度权重
     */
    private fun getFrequencyWeight(freq: Float): Float {
        return when {
            freq <= 200f -> 2.2f                 // 鼓点/贝斯 ×2.2
            freq in 201f..3000f -> 1.8f          // 人声 ×1.8
            else -> 0.3f                         // 超高频 ×0.3
        }
    }
}

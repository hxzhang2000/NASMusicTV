package com.nasmusic.tv.player

import android.os.SystemClock
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.visualizer.SpectrumContract
import com.nasmusic.tv.visualizer.SpectrumRepository
import kotlin.math.pow

/**
 * 频谱分析器
 *
 * **PCM 唯一通道（v2.37.6 起）**：数据来自 ExoPlayer AudioSink 处理器链最前的
 * [PcmTapProcessor] 透传采样（[PcmSpectrumTap] 后台 FFT），**无需 RECORD_AUDIO 权限**。
 * 原系统 [android.media.audiofx.Visualizer] 通道及其降级仲裁已随 v2.37.6 移除。
 *
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
 * **零分配**：缓冲按需预分配，运行期仅做标量运算与 arraycopy。
 */
class SpectrumAnalyzer {

    /** 频谱数据仓库（唯一写入方）。由 PlayerEqualizer 注入 */
    var repository: SpectrumRepository? = null

    /** 全局运行峰值（保留原逻辑，用于静音判定参考） */
    private var runningPeak = 1f
    /**
     * 低频区运行峰值 —— AGC 归一化分母。
     *
     * 关键：分母必须是**慢衰减的历史峰值**，而非当前帧峰值。
     * 若用当前帧峰值，分子分母同步缩放、比值恒为 1，
     * 低频柱会被永久钉死在满格，轻鼓点与重鼓点长得一模一样（历史 BUG ⑨）。
     */
    private var lowRunningPeak = SpectrumContract.AGC_FLOOR
    /** 中频段（40-55 人声/主旋律核心区）运行峰值 —— 显示通道独立 AGC 分母。
     *  修复"低频主导时中高频柱被全局低频峰值压到不可见"（沉浸辉光只剩约 7 根动）。 */
    private var midRunningPeak = SpectrumContract.AGC_FLOOR
    /** 高频段（56-63 点缀区）运行峰值 */
    private var trebleRunningPeak = SpectrumContract.AGC_FLOOR

    /** PCM 频谱通道；由 PlayerEqualizer 注入。null = 未注入 */
    var pcmFallback: PcmFallbackChannel? = null

    /** 播放状态。暂停时无新 PCM 帧到达，由 [onPlaybackChanged] 主动归零画面 */
    @Volatile
    var isPlaying: Boolean = false

    // ── 预分配缓冲（零分配）─────────────────────────────────────
    private var barBuf = FloatArray(SpectrumContract.BAR_COUNT)
    private var displayBuf = FloatArray(SpectrumContract.BAR_COUNT)
    private var linearBuf = FloatArray(SpectrumContract.BAR_COUNT)
    private var waveBuf = FloatArray(SpectrumContract.WAVE_POINTS)

    // ── 诊断计数器（release 也可见，Log.i 直调不被 R8 折叠）──────
    private var diagLastMs = 0L
    private var diagFrames = 0
    private var diagSilentFrames = 0
    private var diagNonZeroBins = 0
    private var diagMaxBin = -1
    private var diagMaxMag = 0f
    private var diagRate = 0

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

        // ── PCM 静音判定 ──────────────────────────────────
        /** PCM 通道静音阈值：PCM 是自己算的，数字静音即真静音，不走自适应噪声门限 */
        private const val PCM_SILENCE_EPS = 1e-3f
        /** 假信号最小 bin 幅值（统计非零 bin 用） */
        private const val MIN_SIGNAL_EPS = 1e-3f
        /** 合理采样率范围内才信任回调值（创维 TV 实测回调采样率为 44100000 = 44100*1000，必须回退） */
        private const val SAMPLE_RATE_MIN = 8_000
        private const val SAMPLE_RATE_MAX = 192_000
        /** 诊断心跳周期 */
        private const val DIAG_MS = 5_000L
    }

    /**
     * 启动 PCM 频谱通道（唯一通道，v2.37.6 起）。
     *
     * 无需音频会话 ID：PCM 数据由 AudioSink 处理器链最前的 [PcmTapProcessor]
     * 透传采样，[PcmSpectrumTap] 后台 FFT 后经 [pcmFallback] 回调本分析器。
     *
     * 幂等：可重复调用（内部 activate 有守卫），切歌/重建播放器时由
     * PlayerEqualizer 再次触发。
     */
    fun start() {
        val channel = pcmFallback
        if (channel == null) {
            AppLog.w(TAG_LOG, "start: pcmFallback not injected, spectrum unavailable")
            return
        }
        resetTracking()
        channel.setOnMagnitudes { mag, bins, rate -> analyze(mag, bins, rate) }
        channel.activate()
        android.util.Log.i(TAG_LOG, "Started: PCM spectrum channel (bars=$BAR_COUNT)")
    }

    /**
     * 播放状态变化回调（PlayerEqualizer 转发）。
     *
     * 暂停时 ExoPlayer 停止喂 PCM → 不再有新帧到达，画面会冻结在最后一帧；
     * 这里在暂停瞬间主动输出一帧静音，保持"暂停 → 柱子归零"的视觉行为。
     */
    @Synchronized
    fun onPlaybackChanged(playing: Boolean) {
        isPlaying = playing
        if (!playing) {
            emitSilence()
        }
    }

    /**
     * 释放资源：停用 PCM 通道并清空缓冲。
     */
    fun release() {
        pcmFallback?.deactivate()
        barBuf.fill(0f)
        displayBuf.fill(0f)
        linearBuf.fill(0f)
        waveBuf.fill(0f)
        repository?.reset()
        AppLog.d(TAG_LOG, "Released")
    }

    // -----------------------------------------------------------------
    // 幅值谱分析 — 感知频率翘曲 (Perceptual Frequency Warping)
    // -----------------------------------------------------------------

    /**
     * 幅值谱 → [SpectrumRepository] 的分析链（PCM 唯一通道，v2.37.6 起）。
     *
     * AGC、感知加权柱映射、双通道输出只有一份实现 ——
     * 原 Visualizer / PCM 双通道共用时的"观感一致性"约束已随 Visualizer 移除。
     *
     * 加锁：onPlaybackChanged 可能并发触发 emitSilence，避免与 FFT 线程
     * 并发写入共享缓冲。无竞争时销费可忽略。
     *
     * 静音判定用绝对值（数字静音即真静音），不走自适应噪声门限
     * （那是为系统 Visualizer 的底噪准备的，PCM 通道自算数据无底噪）。
     */
    @Synchronized
    internal fun analyze(magnitudes: FloatArray, numBins: Int, samplingRate: Int) {
        if (numBins < 2) return

        // 1) 帧统计（跳过直流分量）
        var frameMax = 0f
        var nonZero = 0
        var maxBin = 1
        for (i in 1 until numBins) {
            val m = magnitudes[i]
            if (m > MIN_SIGNAL_EPS) nonZero++
            if (m > frameMax) {
                frameMax = m
                maxBin = i
            }
        }
        diagNonZeroBins = nonZero
        diagMaxBin = maxBin
        diagMaxMag = frameMax
        diagRate = samplingRate

        // 2) 绝对静音判定：PCM 数字静音即真静音
        if (frameMax <= PCM_SILENCE_EPS) {
            emitSilence()
            return
        }

        // 3) 全局运行峰值（保留原逻辑）
        runningPeak = maxOf(runningPeak * 0.94f, frameMax, 0.001f)

        // 4) 分段密集映射 + 战区增益：FFT bins → 64 根感知加权柱子
        // 采样率防御：部分国产 TV 的 Visualizer 回调采样率失真（创维实测 44100000），
        // 会让 freqPerBin 放大 1000 倍、所有 bin 频率超出 20kHz 上限、频谱整体挤进最末 8 根。
        val effectiveRate = if (samplingRate in SAMPLE_RATE_MIN..SAMPLE_RATE_MAX) samplingRate else SAMPLING_RATE
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

        // 5) AGC 归一化 —— 三段独立运行峰值（修 ⑨ + 辉光 7 柱）
        // 每段取段内峰值 → 慢衰减运行峰值。中高频段（40-55 人声、56-63 超高频）
        // 物理幅值天然比低频低一个数量级（getFrequencyWeight 高频只 ×0.3），
        // 若共用低频全局分母，中高频柱会被压到 <0.05 不可见 ——
        // 沉浸辉光因此"只有低频那几根在动"。三段独立分母让各段都能满幅摆动。
        var lowBandPeak = 0f
        var midBandPeak = 0f
        var trebleBandPeak = 0f
        for (b in LOW_BAND_FROM..LOW_BAND_TO) {
            if (result[b] > lowBandPeak) lowBandPeak = result[b]
        }
        for (b in (LOW_BAND_TO + 1)..SpectrumContract.MID_END) {
            if (result[b] > midBandPeak) midBandPeak = result[b]
        }
        for (b in (SpectrumContract.MID_END + 1) until BAR_COUNT) {
            if (result[b] > trebleBandPeak) trebleBandPeak = result[b]
        }
        lowRunningPeak = maxOf(
            lowRunningPeak * SpectrumContract.AGC_DECAY,
            lowBandPeak,
            SpectrumContract.AGC_FLOOR
        )
        midRunningPeak = maxOf(
            midRunningPeak * SpectrumContract.AGC_DECAY,
            midBandPeak,
            SpectrumContract.AGC_FLOOR
        )
        trebleRunningPeak = maxOf(
            trebleRunningPeak * SpectrumContract.AGC_DECAY,
            trebleBandPeak,
            SpectrumContract.AGC_FLOOR
        )
        val globalDenominator = lowRunningPeak

        // 6) 双通道输出
        //   - 律动通道 [linearBuf]：全局低频分母（历史语义——鼓点相对强弱，勿回退）
        //   - 显示通道 [displayBuf]：分段分母。段峰值若低于全局 5% 视为该段
        //     本帧接近静音，回落全局分母，避免把底噪抬成满格假动。
        for (bar in 0 until BAR_COUNT) {
            val normalized = (result[bar] / globalDenominator).coerceIn(0f, 1f)
            linearBuf[bar] = normalized                                     // 律动：线性（全局分母）
            val segDenom = when (bar) {
                in LOW_BAND_FROM..LOW_BAND_TO -> lowRunningPeak
                in (LOW_BAND_TO + 1)..SpectrumContract.MID_END ->
                    maxOf(midRunningPeak, globalDenominator * 0.05f, SpectrumContract.AGC_FLOOR)
                else ->
                    maxOf(trebleRunningPeak, globalDenominator * 0.05f, SpectrumContract.AGC_FLOOR)
            }
            val disp = (result[bar] / segDenom).coerceIn(0f, 1f)
            displayBuf[bar] = disp.pow(SpectrumContract.DISPLAY_GAMMA)
                .coerceIn(MIN_AMPLITUDE, 1f)                                // 显示：分段 AGC + gamma
        }

        diagFrames++
        diagHeartbeat()

        emit()
    }

    /**
     * 静音：写入**定长全 0** 数组。
     * 旧实现返回 FloatArray(0) 会让渲染层柱数变 0、频谱整体消失（历史 BUG ⑮）。
     */
    private fun emitSilence() {
        runningPeak = 1f
        diagSilentFrames++
        barBuf.fill(0f)
        displayBuf.fill(0f)
        linearBuf.fill(0f)
        // 波形一并归零：否则静音期间波形类效果（E12/E16）显示上一帧残影
        waveBuf.fill(0f)
        diagFrames++
        diagHeartbeat()
        emit()
    }

    // ── 诊断心跳（release 可见）：每 DIAG_MS 打一条原始数据形态 ──
    private fun diagHeartbeat() {
        val now = SystemClock.uptimeMillis()
        if (now - diagLastMs < DIAG_MS) return
        diagLastMs = now
        android.util.Log.i(
            "SpectrumAnalyzer",
            "diag frames=${diagFrames} silent=${diagSilentFrames} nonZero=${diagNonZeroBins} " +
                "maxBin=${diagMaxBin} maxMag=${"%.2f".format(diagMaxMag)} rate=${diagRate}"
        )
    }

    /** 清空跟踪状态：切换数据源/新会话时必须重置，否则旧峰值会压制新信号 */
    private fun resetTracking() {
        runningPeak = 1f
        lowRunningPeak = SpectrumContract.AGC_FLOOR
        midRunningPeak = SpectrumContract.AGC_FLOOR
        trebleRunningPeak = SpectrumContract.AGC_FLOOR
        barBuf.fill(0f)
        displayBuf.fill(0f)
        linearBuf.fill(0f)
        waveBuf.fill(0f)
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

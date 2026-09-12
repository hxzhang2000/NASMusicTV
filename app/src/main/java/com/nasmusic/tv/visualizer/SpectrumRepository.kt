package com.nasmusic.tv.visualizer

import kotlin.math.pow

/**
 * 频谱数据仓库 —— [AudioFrame] 的**唯一写入方**。
 *
 * 职责：
 *   ① 接收 SpectrumAnalyzer 的零分配回调，装配 [AudioFrame]
 *   ② 驱动 [BeatDetector] 计算节拍（使用全部 50Hz 帧，不受渲染节流影响）
 *   ③ 维护 [SectionEnergyTracker] / [PeakHoldTracker]
 *
 * **零分配**：所有数组预分配，运行期只做 arraycopy 与标量写入。
 */
class SpectrumRepository {

    /** 全局唯一的帧数据（渲染层只读） */
    val frame = AudioFrame(SpectrumContract.BAR_COUNT, SpectrumContract.WAVE_POINTS)

    /** 峰值保持帽 */
    val peakHold = PeakHoldTracker(SpectrumContract.BAR_COUNT)

    /** 帧序号——渲染层轮询此值判断是否收到新帧（避免 StateFlow 每帧分配） */
    @Volatile
    var frameSeq: Long = 0
        private set

    val beatDetector = BeatDetector()
    private val section = SectionEnergyTracker()

    private var lastEmitMs = 0L
    private var lastBeatMsForFallback = 0L
    private var fallbackPhase = 0f
    private var lastHeartbeatLogMs = 0L

    /** 各律动通道的峰值跟随器（动态范围增强用，切歌时重置） */
    private val bassPeak = PeakFollower()
    private val midPeak = PeakFollower()
    private val treblePeak = PeakFollower()
    private val energyPeak = PeakFollower()

    /** 动态范围增强：v = 原始值，peak = 当前跟踪峰值 → 输出 v/peak 满幅归一化 */
    private fun boost(v: Float, peak: PeakFollower): Float {
        val p = peak.observe(v)
        if (p <= 1e-4f) return 0f
        // 直接归一到当前峰值：把 0.02~0.06 的原始值拉到 0–1 满幅，帧间差异同步放大
        return (v / p).coerceIn(0f, 1f)
    }

    /**
     * 由 SpectrumAnalyzer 在 Visualizer 回调中调用。
     *
     * @param displayBars 已 gamma 压缩的显示用柱值（长度 = BAR_COUNT）
     * @param linearBars  线性柱值，用于律动通道（可为 null，表示与 displayBars 同源）
     * @param waveform    波形（可 null）
     * @param nowMs       单调时钟
     */
    fun onFrame(
        displayBars: FloatArray,
        linearBars: FloatArray?,
        waveform: FloatArray?,
        nowMs: Long
    ) {
        val f = frame
        val n = minOf(displayBars.size, f.spectrum.size)
        System.arraycopy(displayBars, 0, f.spectrum, 0, n)
        for (i in n until f.spectrum.size) f.spectrum[i] = 0f

        waveform?.let {
            val m = minOf(it.size, f.waveform.size)
            System.arraycopy(it, 0, f.waveform, 0, m)
        }

        // ── 律动通道：必须用线性值，否则 gamma 会把强弱差异压扁 ──
        val src = linearBars ?: displayBars
        // 原始段均值实测只有 0.02~0.06（与曲目响度相关），直接喂渲染器
        // 会显得"死水一潭"（波纹位移仅 ±1px、粒子发射近零）。用峰值跟随
        // 做动态范围增强：把典型值拉伸到 0~1 区间，soft-knee 保持强弱对比。
        val rawBass = meanOf(src, 0, SpectrumContract.BASS_END)
        val rawMid = meanOf(src, SpectrumContract.BASS_END + 1, SpectrumContract.MID_END)
        val rawTreble = meanOf(src, SpectrumContract.MID_END + 1, SpectrumContract.TREBLE_END)
        f.bass = boost(rawBass, bassPeak)
        f.mid = boost(rawMid, midPeak)
        f.treble = boost(rawTreble, treblePeak)
        var total = 0f
        for (i in 0 until n) total += src[i]
        val rawEnergy = total / n.coerceAtLeast(1)
        f.energy = boost(rawEnergy, energyPeak)

        // ── 节拍（全帧率计算，不受渲染节流影响）──
        // 节拍检测使用原始均值（增强后的 0~1 跳变会破坏能量包络判别）
        val info = beatDetector.onFrame(rawBass, nowMs)
        f.beat = info.isBeat
        f.pulse = info.pulse
        f.bpm = info.bpm

        // ── 段落能量与兜底呼吸 ──
        f.sectionEnergy = section.update(f.energy, nowMs)
        if (f.beat) lastBeatMsForFallback = nowMs
        if (nowMs - lastBeatMsForFallback > NO_BEAT_FALLBACK_MS) {
            // 无鼓点曲目（古典/环境音/电台）→ 缓慢正弦呼吸，避免画面"死"掉
            fallbackPhase += 0.026f
            f.pulse = maxOf(f.pulse, (0.5f + 0.5f * kotlin.math.sin(fallbackPhase.toDouble()).toFloat()) * 0.35f)
        }

        f.timeMs = nowMs
        f.seq++

        // 数据链路心跳（release 也可见）：证明 onFrame 在被调用、frame 在更新。
        // 周期长避免刷屏；seq/bass/energy 可判断数据是否有真实波动。
        if (nowMs - lastHeartbeatLogMs >= HEARTBEAT_LOG_MS) {
            lastHeartbeatLogMs = nowMs
            android.util.Log.i(
                "SpectrumRepository",
                "frame alive seq=${f.seq} bass=${"%.4f".format(f.bass)} " +
                    "energy=${"%.4f".format(f.energy)} pulse=${"%.3f".format(f.pulse)} beat=${f.beat}"
            )
        }

        peakHold.update(f.spectrum)

        if (nowMs - lastEmitMs >= SpectrumContract.EMIT_INTERVAL_MS) {
            lastEmitMs = nowMs
            frameSeq = f.seq
        }
    }

    /** 切歌 / 重新连接：重置所有状态，避免上一首的峰值压制新歌 */
    fun reset() {
        beatDetector.reset()
        section.reset()
        peakHold.reset()
        frame.reset()
        // frame.reset() 刻意不动 seq（AutoDirector 依赖 timeMs 单调），
        // 但换歌需要干净的帧序号起点，故由仓库（seq 的所有者）显式同步归零。
        frame.seq = 0L
        lastEmitMs = 0L
        lastBeatMsForFallback = 0L
        fallbackPhase = 0f
        frameSeq = 0
        bassPeak.reset()
        midPeak.reset()
        treblePeak.reset()
        energyPeak.reset()
    }

    private fun meanOf(arr: FloatArray, from: Int, to: Int): Float {
        if (arr.isEmpty()) return 0f
        val a = from.coerceIn(0, arr.lastIndex)
        val b = to.coerceIn(a, arr.lastIndex)
        var sum = 0f
        for (i in a..b) sum += arr[i]
        return sum / (b - a + 1)
    }

    /**
     * 峰值跟随器：瞬时上升、指数衰减。发光度/律动类增强的统一基座——
     * 与"fixed max"归一化不同，它随曲目响度自适应，弱曲放大、强曲封顶。
     */
    private class PeakFollower(
        /** 每帧峰值衰减率：半衰 ≈ 0.69 / (1-0.985) ≈ 46 帧 ≈ 1s（50Hz） */
        private val decay: Float = 0.985f
    ) {
        private var value = 0f

        /** 送入当前采样，返回跟踪峰值 */
        fun observe(v: Float): Float {
            if (v > value) value = v else value *= decay
            return value
        }

        fun reset() {
            value = 0f
        }
    }

    companion object {
        /** 心率日志周期：5s 一条，避免刷屏 */
        private const val HEARTBEAT_LOG_MS = 5_000L

        /** 连续 3s 无节拍 → 启用兜底正弦呼吸 */
        private const val NO_BEAT_FALLBACK_MS = 3_000L

        /** 显示通道 gamma（供 SpectrumAnalyzer 复用，避免常量散落） */
        fun applyDisplayGamma(v: Float): Float =
            v.coerceIn(0f, 1f).pow(SpectrumContract.DISPLAY_GAMMA)
    }
}

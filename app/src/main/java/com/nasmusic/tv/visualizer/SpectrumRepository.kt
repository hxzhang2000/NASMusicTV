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
        f.bass = meanOf(src, 0, SpectrumContract.BASS_END)
        f.mid = meanOf(src, SpectrumContract.BASS_END + 1, SpectrumContract.MID_END)
        f.treble = meanOf(src, SpectrumContract.MID_END + 1, SpectrumContract.TREBLE_END)
        var total = 0f
        for (i in 0 until n) total += src[i]
        f.energy = (total / n.coerceAtLeast(1)).coerceIn(0f, 1f)

        // ── 节拍（全帧率计算，不受渲染节流影响）──
        val info = beatDetector.onFrame(f.bass, nowMs)
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
    }

    private fun meanOf(arr: FloatArray, from: Int, to: Int): Float {
        if (arr.isEmpty()) return 0f
        val a = from.coerceIn(0, arr.lastIndex)
        val b = to.coerceIn(a, arr.lastIndex)
        var sum = 0f
        for (i in a..b) sum += arr[i]
        return sum / (b - a + 1)
    }

    companion object {
        /** 连续 3s 无节拍 → 启用兜底正弦呼吸 */
        private const val NO_BEAT_FALLBACK_MS = 3_000L

        /** 显示通道 gamma（供 SpectrumAnalyzer 复用，避免常量散落） */
        fun applyDisplayGamma(v: Float): Float =
            v.coerceIn(0f, 1f).pow(SpectrumContract.DISPLAY_GAMMA)
    }
}

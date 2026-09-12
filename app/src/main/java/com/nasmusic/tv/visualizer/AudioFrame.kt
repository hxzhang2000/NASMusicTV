package com.nasmusic.tv.visualizer

/**
 * 音频分析层的唯一输出契约。
 *
 * 三条铁律：
 *   ① 全局单例复用 —— 运行期零分配
 *   ② 只有 [SpectrumRepository] 可写；渲染层只读
 *   ③ 渲染层不得持有引用跨帧使用（需历史帧的效果自行预分配环形缓冲拷贝）
 *
 * **双通道设计（关键）**：
 *   - [spectrum] 为显示通道，经过 gamma 压缩，保证小信号可见；
 *   - [bass]/[mid]/[treble]/[energy] 为律动通道，保持线性，保证动态范围。
 *   二者不可混用：用显示通道驱动律动会让强弱差异被 gamma 压扁（历史 BUG ⑨-b）。
 */
class AudioFrame(barCount: Int, wavePoints: Int) {

    // ── 显示通道（已 gamma 压缩）────────────────────────────────
    /** 64 段，0..1。渲染柱长 / 半径直接用这个 */
    val spectrum: FloatArray = FloatArray(barCount)
    /** 128 点，-1..1 时域波形 */
    val waveform: FloatArray = FloatArray(wavePoints)

    // ── 律动通道（线性，未压缩）─────────────────────────────────
    /** 20–250 Hz 线性能量 0..1 —— 节拍主源 */
    var bass: Float = 0f
    /** 250 Hz–3 kHz */
    var mid: Float = 0f
    /** 3 k–20 kHz */
    var treble: Float = 0f
    /** 全频段线性总能量 */
    var energy: Float = 0f
    /** 8s 滑动均值，段落呼吸用 */
    var sectionEnergy: Float = 0f

    // ── 节拍 ───────────────────────────────────────────────────
    /** 本帧命中节拍（仅一帧为 true） */
    var beat: Boolean = false
    /** 0..1 快起慢落脉冲包络 —— 主力律动源 */
    var pulse: Float = 0f
    /** 估算 BPM，0 表示尚未稳定 */
    var bpm: Float = 0f

    // ── 元信息 ─────────────────────────────────────────────────
    /** 单调时钟（动画相位用，非音轨进度） */
    var timeMs: Long = 0L
    /** 帧序号 */
    var seq: Long = 0L

    /**
     * 归零全部**分析字段**（频谱 / 波形 / 律动 / 节拍）。
     *
     * 注意：[timeMs] 与 [seq] **刻意不重置** —— 它们是单调元信息。
     * [AutoDirector] 的最小驻留用 `timeMs` 做时间差判断，切歌重置时归零
     * 会让调度器冻结约 8s；需要清零请显式赋值。
     */
    fun reset() {
        spectrum.fill(0f)
        waveform.fill(0f)
        bass = 0f
        mid = 0f
        treble = 0f
        energy = 0f
        sectionEnergy = 0f
        beat = false
        pulse = 0f
        bpm = 0f
    }

    /** 取频段均值（闭区间） */
    internal fun bandMean(from: Int, to: Int): Float {
        if (to < from) return 0f
        var sum = 0f
        for (i in from..to) sum += spectrum[i]
        return sum / (to - from + 1)
    }
}

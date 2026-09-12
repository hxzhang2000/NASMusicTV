package com.nasmusic.tv.visualizer

/**
 * 峰值保持帽（Peak Hold）—— 经典 Winamp 节奏锚点。
 *
 * 快升慢降：柱子窜上去时峰值立刻跟上，回落时缓慢下坠。
 * 零分配：数组预分配。
 */
class PeakHoldTracker(barCount: Int) {

    val peaks: FloatArray = FloatArray(barCount)

    /** 每帧衰减比例：0.985 ≈ 1.5s 从顶落到 10% */
    private var decay = 0.985f
    /** 峰值帽自身的最小下落速度（px 无关，归一化值） */
    private var minFall = 0.004f

    fun update(spectrum: FloatArray) {
        val n = minOf(peaks.size, spectrum.size)
        for (i in 0 until n) {
            peaks[i] = if (spectrum[i] >= peaks[i]) {
                spectrum[i]
            } else {
                maxOf(spectrum[i], peaks[i] * decay - minFall)
            }
        }
    }

    fun reset() = peaks.fill(0f)

    fun resize(barCount: Int): PeakHoldTracker =
        if (barCount == peaks.size) this else PeakHoldTracker(barCount)
}

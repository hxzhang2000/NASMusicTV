package com.nasmusic.tv.visualizer

/**
 * 8 秒滑动均值跟踪器 —— "段落呼吸"驱动源。
 *
 * 主歌暗、副歌亮，营造 8 秒尺度的情绪起伏。
 * 零分配：环形缓冲预分配。
 */
class SectionEnergyTracker(
    private val windowMs: Long = 8_000L
) {
    private val values = FloatArray(64)
    private val stamps = LongArray(64)
    private var idx = 0
    private var count = 0
    private var sum = 0f

    fun update(energy: Float, nowMs: Long): Float {
        if (count == values.size) {
            sum -= values[idx]
        } else {
            count++
        }
        values[idx] = energy
        stamps[idx] = nowMs
        sum += energy
        idx = (idx + 1) % values.size

        // 丢弃过期样本
        var dropped = true
        while (dropped && count > 0) {
            val oldest = stamps[(idx - count + values.size) % values.size]
            if (nowMs - oldest > windowMs) {
                sum -= values[(idx - count + values.size) % values.size]
                count--
            } else {
                dropped = false
            }
        }
        return if (count == 0) 0f else (sum / count).coerceIn(0f, 1f)
    }

    fun reset() {
        values.fill(0f)
        stamps.fill(0L)
        idx = 0
        count = 0
        sum = 0f
    }
}

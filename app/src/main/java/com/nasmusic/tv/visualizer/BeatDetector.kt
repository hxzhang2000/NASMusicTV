package com.nasmusic.tv.visualizer

import kotlin.math.sqrt

/**
 * 节拍检测器 —— 呼吸感引擎。
 *
 * 算法：低频瞬时能量 vs 历史均值比较（简化版 Frédéric Patin 方案），
 * 阈值系数随历史方差自适应：
 *   - 方差大（强节奏曲）→ 阈值降低，避免漏拍
 *   - 方差小（平稳曲）  → 阈值抬高，抑制误触
 *
 * 全案 20 套效果共用同一实例，结果写入 [AudioFrame]，不重复计算。
 */
class BeatDetector {

    data class BeatInfo(
        val pulse: Float,      // 0..1 快起慢落脉冲
        val isBeat: Boolean,   // 本帧是否命中节拍
        val bpm: Float         // 估算 BPM，0 表示尚未稳定
    )

    private val bassHistory = FloatArray(HISTORY)
    private var hIdx = 0
    private var filled = 0
    private var lastBeatMs = 0L
    private var pulse = 0f
    private val intervals = FloatArray(8)
    private var iIdx = 0
    private var intervalCount = 0

    private var result = BeatInfo(0f, false, 0f)

    fun onFrame(bass: Float, nowMs: Long): BeatInfo {
        // ① 历史均值 + 方差 → 动态阈值系数
        var sum = 0f
        for (x in bassHistory) sum += x
        val avg = sum / HISTORY

        var v = 0f
        for (x in bassHistory) {
            val d = x - avg
            v += d * d
        }
        v /= HISTORY

        // 方差越大阈值越低（适应强节奏曲）；越小阈值越高（抑制噪声误触）
        val c = (-0.0025714f * v * 15f + 1.5142857f).coerceIn(1.15f, 1.9f)

        // ② 节拍判定
        val isBeat = filled >= HISTORY / 2 &&
            bass > avg * c &&
            bass > MIN_BASS &&
            (lastBeatMs <= 0L || nowMs - lastBeatMs > MIN_BEAT_MS)

        if (isBeat) {
            if (lastBeatMs > 0) {
                intervals[iIdx] = (nowMs - lastBeatMs).toFloat()
                iIdx = (iIdx + 1) % intervals.size
                if (intervalCount < intervals.size) intervalCount++
            }
            lastBeatMs = nowMs
            pulse = 1f                          // 快起
        }
        pulse *= PULSE_DECAY                    // 慢落 —— 呼吸感的核心

        bassHistory[hIdx] = bass
        hIdx = (hIdx + 1) % HISTORY
        if (filled < HISTORY) filled++

        result = BeatInfo(pulse, isBeat, estimateBpm())
        return result
    }

    /** 取最近间隔的中位数，抗单次误判 */
    private fun estimateBpm(): Float {
        if (intervalCount < 4) return 0f
        val valid = FloatArray(intervalCount)
        var n = 0
        for (i in 0 until intervals.size) {
            if (intervals[i] > 0f) valid[n++] = intervals[i]
        }
        if (n < 4) return 0f
        val slice = valid.copyOf(n)
        slice.sort()
        val median = slice[n / 2]
        return (60000f / median).coerceIn(60f, 200f)
    }

    fun reset() {
        bassHistory.fill(0f)
        filled = 0
        pulse = 0f
        lastBeatMs = 0L
        intervals.fill(0f)
        iIdx = 0
        intervalCount = 0
        result = BeatInfo(0f, false, 0f)
    }

    companion object {
        /** ≈1s @43fps */
        private const val HISTORY = 43
        /** 冷却 → 上限 250 BPM */
        private const val MIN_BEAT_MS = 240L
        /** 每帧衰减 10% ≈ 250ms 回落 */
        private const val PULSE_DECAY = 0.90f
        /** 静音门限，低于此值不触发节拍 */
        private const val MIN_BASS = 0.12f
    }
}

package com.nasmusic.tv.visualizer.fx

/**
 * 统一频段包络平滑器 —— 替代各效果手写的 EMA
 * （`BatchThreeRenderers.kt:88-89`、`:302-303` 等，系数互不相同且无统一语义）。
 *
 * **attack / release 分离**是关键：鼓点要**快 attack**（跟得上瞬态）、
 * 段落要**慢 release**（不会一帧掉下去）。
 *
 * ⛔ 参数取值见 §7.3，不得在渲染器内另写魔数。
 */
class AudioSmoother(
    private val attack: Float = 0.35f,   // 上升系数（大 = 跟得快）
    private val release: Float = 0.06f   // 下降系数（小 = 落得慢）
) {
    var value: Float = 0f
        private set

    /** 每帧推进一次；零分配 */
    fun update(target: Float): Float {
        val k = if (target >= value) attack else release
        value += (target - value) * k
        return value
    }

    /**
     * M10 修复（2026-10-06，代码审查报告 §4）：dt 参数化变体——固定系数版 [update]
     * 等价于隐含 60fps 基准，帧率不稳时跟随速度随帧率漂移。本变体按
     * `1-(1-k)^(dt·60)` 换算，在 60fps 下与 [update] 逐帧等价。
     * ⭐ 首个生产调用方：E44「明月」`MoonlitRenderer` 的音频头（2026-10-10，§八 / G15）；
     *    **新效果一律用本变体**，不要再走隐含帧率的 [update]。
     */
    fun updateDt(target: Float, dtSec: Float): Float {
        val base = if (target >= value) attack else release
        val k = 1f - Math.pow((1f - base).toDouble(), (dtSec * 60f).toDouble()).toFloat()
        value += (target - value) * k
        return value
    }

    fun reset() { value = 0f }
}

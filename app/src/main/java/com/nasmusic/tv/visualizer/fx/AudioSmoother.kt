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

    fun reset() { value = 0f }
}

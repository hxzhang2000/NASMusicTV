package com.nasmusic.tv.visualizer

/**
 * 可视化专用伪随机源（LCG，线性同余）。
 *
 * **P1#5（2026-09-14）**：原先随机状态是 `VisualizerMath` 内的 `private var seed`
 * ——进程级单例状态，被全部渲染器共享。改为**每渲染器一个实例**后：
 * - 交叉淡入时（`RendererSwapper` 同时绘制新旧两层）两层的随机序列不再互相消耗，
 *   各层图案彼此独立；
 * - 种子可注入 → 渲染器的随机行为可单测（原实现是全局状态，测不了）。
 *
 * ⚠️ **这不是缺陷修复**。原共享实现下每个调用点都是「取一次值立即使用」，不存在
 * 依赖序列位置的状态机，因此**没有任何可见症状**。本次改动解决的是代码卫生问题：
 * `VisualizerMath` 的 KDoc 声称「所有函数均为无副作用的纯计算」，但 `nextRandom()`
 * 会改写单例状态——文档与实现不符。
 *
 * **种子策略（关键）**：默认取时间派生值。**不能**给所有渲染器同一个常量种子 ——
 * 那样每个渲染器的首帧图案会完全一致（交叉淡入时会看到两层图案重合），且每次进入
 * 同一效果都重复同一套图案（原共享实现下每次进入都从不同位置续跑，反而有变化）。
 *
 * 刻意不用 `kotlin.random.Random`：它会产生对象与装箱开销，本类保持零分配
 * （与 `VisualizerMath` 的「绘制循环零分配」约束一致）。
 */
class VisualizerRandom(seed: UInt = defaultSeed()) {

    /** LCG 状态；为 0 会退化成恒 0 序列，故兜底为 1 */
    private var state: UInt = if (seed == 0u) 1u else seed

    /** 下一个值，范围 `[0, 1)` */
    fun next(): Float {
        state = state * 1664525u + 1013904223u
        return (state shr 8).toFloat() / 16777216f
    }

    /** 下一个值，范围 `[-1, 1)` */
    fun nextSigned(): Float = next() * 2f - 1f

    /**
     * 下一个**下标**，范围 `[0, bound)`；`bound <= 0` 时返回 0。
     *
     * 抽转场（`PhotoTransitionPicker`）与抽照片（`PhotoSourceAggregator` 的洗牌）都要用，
     * 集中在这里 ⇒ **随机序列只有一处**，两条业务不会互相消耗（§5.9 / §6.5）。
     *
     * ⚠️ 零分配：不构造 `IntRange`、不装箱。`(next() * bound).toInt()` 理论上等于 `bound`
     * 的边界情况（浮点向上舍入）由 `coerceIn` 兜住 —— `next()` 的返回值 `< 1f`，
     * 但 `x.toFloat() * bound` 在 `bound` 很大时可能舍入到 `bound` 本身。
     */
    fun nextIndex(bound: Int): Int =
        if (bound <= 1) 0 else (next() * bound).toInt().coerceIn(0, bound - 1)

    companion object {
        /**
         * 时间派生种子。
         *
         * 保证不同渲染器实例、不同进入次数拿到的种子都不同。丢弃低 8 位是为了避开
         * 部分平台上 `nanoTime` 低位的规律性。
         */
        fun defaultSeed(): UInt = (System.nanoTime() ushr 8).toUInt()
    }
}

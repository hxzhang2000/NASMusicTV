package com.nasmusic.tv.visualizer.photo

import com.nasmusic.tv.visualizer.VisualizerRandom

/**
 * 转场随机抽取（§5.9「避免连续重复」）
 *
 * ## 职责边界
 *
 * 本类**只负责「从给定池里挑一个，且尽量不重复最近两次」**。
 * 「池里该有谁」（排除音频反应类 / 按降级后标识去重 / 按分期取池）全部由
 * [PhotoTransitionId.randomPool] 负责 —— 两者拆开是为了各自可单测（G2 / G5）。
 *
 * ## 为什么记「最近 2 次」而不是 1 次
 *
 * 只记 1 次（即「不与上一次相同」）在池很小时会退化成 **`A-B-A-B` 严格交替** ——
 * 看起来比随机还机械。记 2 次才压得住（§5.9）。
 *
 * ## 为什么必须有「重试上限 + 放行」
 *
 * 池大小为 2 时，最近两次恰好把池里两个都占满 ⇒ **任何候选都在 `recent` 里**，
 * 没有放行就是**死循环**（主线程卡死）。⇒ 重试 [DEFAULT_RETRIES] 次后无条件放行。
 *
 * ## 池内下标 vs 效果本身
 *
 * [pick] 返回的是 **`pool` 数组的下标**（不是 `PhotoTransitionId.ordinal`）——
 * 调用方自行 `entries[pool[idx]]`。这样 `pool` 可以是任意子集，
 * 本类不需要知道 `pool` 里装的是什么。
 *
 * @param random 共享的随机源（§5.9：**不要**新建 `Random()`，
 *   否则会与「随机洗牌照片」（§6.7）互相消耗随机序列）
 * @param recentCapacity 记忆长度；默认 2。
 *   ⚠️ 暴露它是为了**负向自证**：传 1 时必须能观察到 `A-B-A-B` 严格交替
 *   （证明「记 2 次」这个选择确实有判别力，而不是随便写个数）
 */
class PhotoTransitionPicker(
    private val random: VisualizerRandom,
    private val recentCapacity: Int = DEFAULT_CAPACITY,
) {

    /** 最近 [recentCapacity] 次抽中的**池内下标**；`-1` = 空位。`[0]` 最旧、末位最新 */
    private val recent = IntArray(recentCapacity.coerceAtLeast(1)) { -1 }

    /** 清空记忆（换池 / 换模式 / 重新进入效果时调用，避免拿旧池的下标去避让新池） */
    fun reset() {
        recent.fill(-1)
    }

    /**
     * 从 [pool] 抽一个**池内下标**。
     *
     * @param pool 候选下标池（通常是 `PhotoTransitionId.randomPool(...)` 的结果，
     *   元素是 `ordinal`；⚠️ **池为空时返回 -1** —— 调用方应保持当前转场不变）
     * @return 池内下标 `0..pool.size-1`；池为空时 `-1`
     */
    fun pick(pool: IntArray, retries: Int = DEFAULT_RETRIES): Int {
        val size = pool.size
        if (size == 0) return -1

        // 池里只有一个：没有可避让的余地，直接返回。
        // ⚠️ 这个分支不只是「省 8 次随机数」—— 它让 size == 1 时**不消耗随机序列**，
        // 使「单效果固定档」下的随机序列与用户看到的画面变化解耦。
        if (size == 1) {
            remember(0)
            return 0
        }

        var candidate = random.nextIndex(size)
        var attempt = 0
        while (attempt < retries && isRecent(candidate)) {
            candidate = random.nextIndex(size)
            attempt++
        }
        remember(candidate)
        return candidate
    }

    private fun isRecent(index: Int): Boolean {
        for (i in recent.indices) {
            if (recent[i] == index) return true
        }
        return false
    }

    /** 把 [index] 压入记忆（整体左移一位，最旧的被挤出） */
    private fun remember(index: Int) {
        val n = recent.size
        if (n == 0) return
        // 左移一位：recent[0] 被丢弃，recent[1..] 依次前移
        for (i in 0 until n - 1) {
            recent[i] = recent[i + 1]
        }
        recent[n - 1] = index
    }

    companion object {

        /** §5.9：记最近 2 次 */
        const val DEFAULT_CAPACITY = 2

        /** §5.9：重试 8 次仍命中则放行 */
        const val DEFAULT_RETRIES = 8
    }
}

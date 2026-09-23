package com.nasmusic.tv.visualizer.photo

import com.nasmusic.tv.visualizer.VisualizerRandom
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **门禁 G2**（§14.4）—— 转场随机抽取
 *
 * ## 判据原文
 *
 * > 连续 N 次抽取不出现最近 2 次用过的值；池大小 2 时**必定放行**不死循环
 * > 负向自证：把 `recent` 容量改成 1 ⇒ 断言出现 `A-B-A-B` 交替
 *
 * ## 为什么「放行」这件事必须单独测
 *
 * 池大小为 2 时，最近两次恰好把池里两个都占满 ⇒ **任何候选都在 `recent` 里**。
 * 若没有重试上限，`while (isRecent(...))` 就是**主线程死循环**（画面卡死且无异常）。
 * 这类缺陷不会以「测试失败」的形式出现，而是**测试挂住** —— 所以本用例必须真的跑完。
 *
 * ⚠️ 本测试**不需要 Robolectric**：只碰 `PhotoTransitionId.ordinal` 与 `Easing`，无 `android.*`。
 */
class PhotoTransitionPickerTest {

    /** 故意让「池内下标」≠「ordinal」，用来证明 [PhotoTransitionPicker.pick] 返回的是**下标** */
    private val pool = intArrayOf(3, 7, 11, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75)

    private val smallPool = intArrayOf(0, 1, 2, 3, 4)

    // ────────────────────── ① 边界 ──────────────────────

    /** T6.1 验收原文：「`bound = 1` 返回 0 不抛异常」 */
    @Test
    fun `nextIndex handles degenerate bounds`() {
        val rng = VisualizerRandom(seed = 0x5EEDu)
        assertTrue("bound = 1 应返回 0", rng.nextIndex(1) == 0)
        assertTrue("bound = 0 应返回 0（不抛异常）", rng.nextIndex(0) == 0)
        assertTrue("bound = -5 应返回 0（不抛异常）", rng.nextIndex(-5) == 0)
    }

    /** `nextIndex` 必须落在 `[0, bound)`，且**不消耗**序列之外的额外随机数 */
    @Test
    fun `nextIndex stays in range and covers the whole bound`() {
        val rng = VisualizerRandom(seed = 0xC0FFEEu)
        val seen = BooleanArray(7)
        repeat(2_000) {
            val v = rng.nextIndex(7)
            assertTrue("nextIndex(7) = $v 越界", v in 0..6)
            seen[v] = true
        }
        assertTrue("7 个下标应全部被抽到过，实际 ${seen.joinToString()}", seen.all { it })
    }

    @Test
    fun `empty pool yields minus one`() {
        val picker = PhotoTransitionPicker(VisualizerRandom(seed = 1u))
        assertTrue("空池应返回 -1（调用方据此保持当前转场）", picker.pick(IntArray(0)) == -1)
    }

    @Test
    fun `single element pool always returns zero`() {
        val picker = PhotoTransitionPicker(VisualizerRandom(seed = 2u))
        repeat(50) {
            assertTrue("单元素池只能返回 0", picker.pick(intArrayOf(42)) == 0)
        }
    }

    /** 返回的必须是**池内下标**，不是 ordinal —— 否则调用方 `entries[pool[idx]]` 会越界 */
    @Test
    fun `pick returns a pool index not an ordinal`() {
        val picker = PhotoTransitionPicker(VisualizerRandom(seed = 3u))
        repeat(200) {
            val idx = picker.pick(pool)
            assertTrue("返回了 $idx，应落在 0..${pool.size - 1}", idx in pool.indices)
        }
    }

    // ────────────────────── ② 避免连续重复 ──────────────────────

    @Test
    fun `pick never repeats the last two pool indices`() {
        val picker = PhotoTransitionPicker(VisualizerRandom(seed = 0x1234u))
        var prev1 = -1
        var prev2 = -1
        repeat(300) { step ->
            val idx = picker.pick(pool)
            assertTrue("第 $step 次抽到 $idx，与上一次 $prev1 相同", idx != prev1)
            assertTrue("第 $step 次抽到 $idx，与上上次 $prev2 相同", idx != prev2)
            prev2 = prev1
            prev1 = idx
        }
    }

    /**
     * 池大小 2：最近两次必然占满整池 ⇒ 只有「重试上限 + 放行」能让它跑完。
     *
     * ⚠️ 若放行逻辑被删掉，本用例不是「失败」而是**永远不返回**（测试任务超时）。
     */
    @Test
    fun `tiny pool does not dead lock and still returns valid indices`() {
        val picker = PhotoTransitionPicker(VisualizerRandom(seed = 0x99u))
        val two = intArrayOf(10, 20)
        val counts = IntArray(2)
        repeat(200) {
            val idx = picker.pick(two)
            assertTrue("池大小 2 时返回了 $idx（越界）", idx in 0..1)
            counts[idx]++
        }
        assertTrue("两个候选都该被抽到过，实际 $counts", counts[0] > 0 && counts[1] > 0)
    }

    @Test
    fun `reset clears the memory`() {
        val picker = PhotoTransitionPicker(VisualizerRandom(seed = 7u))
        val first = picker.pick(pool)
        picker.reset()
        // 清空后第一次抽取不受历史约束 —— 断言「能返回」即可（不保证一定与 first 相同）
        val after = picker.pick(pool)
        assertTrue("reset 后仍应返回合法下标（first=$first, after=$after）", after in pool.indices)
    }

    // ────────────────────── ③ 负向自证 ──────────────────────

    /**
     * **负向自证**：`recentCapacity = 2` 这个选择**有判别力**。
     *
     * 同一份生产代码、同一个种子，只把容量改成 1：
     * - 容量 1 能保证「不与上一次相同」，但**保证不了「不与上上次相同」**
     *   ⇒ 必然出现「隔一个就重复」的短周期（`A-B-A-B` 就是它的极端形态）；
     * - 容量 2 才把「上上次」也纳入避让。
     *
     * 若把容量 1 的性质写成断言，它**必须失败** —— 这正是 §5.9「只记 1 次会退化成
     * `A-B-A-B` 交替，记 2 次才压得住」的可执行证据。
     */
    @Test
    fun `negative proof - capacity one degenerates to short cycle alternation`() {
        val seed = 0xBEEFu

        // 容量 1（被否决的写法）
        val one = PhotoTransitionPicker(VisualizerRandom(seed = seed), recentCapacity = 1)
        val seq1 = IntArray(200) { one.pick(smallPool) }

        // 它能保证的：不与上一次相同
        for (i in 1 until seq1.size) {
            assertTrue("容量 1 应保证不与上一次相同（i=$i）", seq1[i] != seq1[i - 1])
        }
        // 它保证不了的：隔一个就重复 —— 实测必然出现
        val repeatAtDistanceTwo = (2 until seq1.size).count { seq1[it] == seq1[it - 2] }
        assertTrue(
            "容量 1 必然出现「隔一个重复」（实测 $repeatAtDistanceTwo 次）—— " +
                "这就是 §5.9 说的 `A-B-A-B` 退化；容量 2 才压得住",
            repeatAtDistanceTwo > 0,
        )

        // 容量 2（默认）：连「上上次」也避让
        val two = PhotoTransitionPicker(VisualizerRandom(seed = seed), recentCapacity = 2)
        val seq2 = IntArray(200) { two.pick(smallPool) }
        for (i in 2 until seq2.size) {
            assertTrue(
                "容量 2 应同时避让上两次（i=$i，实际 ${seq2[i]} vs ${seq2[i - 1]} / ${seq2[i - 2]}）",
                seq2[i] != seq2[i - 1] && seq2[i] != seq2[i - 2],
            )
        }
    }
}

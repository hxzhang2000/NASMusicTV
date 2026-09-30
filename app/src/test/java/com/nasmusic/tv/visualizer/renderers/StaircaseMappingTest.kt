package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.visualizer.SpectrumContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §八 G10 — E32「全柱独立频段映射」门禁。
 *
 * **正向**（6 条规格断言 + 2 条补充）盯住 [StaircaseWaveRenderer] 的 internal 纯函数
 * （[StaircaseWaveRenderer.buildBands] / [StaircaseWaveRenderer.stepsOf] /
 *   [StaircaseWaveRenderer.blockAlpha] / [StaircaseWaveRenderer.shardTier] /
 *   [StaircaseWaveRenderer.shardAlpha]），全部**从生产常量出发**、不复制算法。
 *
 * **负向自证**（4 条）：把旧实现（`n / 2` 半区 / 镜像映射 / `(st+1)/steps` 相对亮度 /
 * 线性分桶）喂进**与正向完全相同**的谓词，必须判失败 —— 否则门禁是空转（§八 开头规矩）。
 */
class StaircaseMappingTest {

    /** LOW / MEDIUM / HIGH 的列数（`COLS_LOW` / `COLS_MED` / `COLS_HIGH`） */
    private val qualities = intArrayOf(
        StaircaseWaveRenderer.COLS_LOW,
        StaircaseWaveRenderer.COLS_MED,
        StaircaseWaveRenderer.COLS_HIGH
    )

    /** 经生产 internal 纯函数建表（感知划分，`k = PERCEPT_K`） */
    private fun buildBand(cols: Int): IntArray = StaircaseWaveRenderer.buildBands(cols)

    /** 对照：**线性**划分（`k = 1.0`）—— 感知分桶的基准 */
    private fun buildBandLinear(cols: Int): IntArray = StaircaseWaveRenderer.buildBands(cols, 1.0)

    /** 「前 8 桶（0–2.5 kHz，含全部基频）」占用的列数 */
    private fun lowBandCols(b: IntArray, cols: Int): Int = (0 until cols).count { b[it] < 8 }

    // ── ④ 的两条谓词（抽成函数 ⇒ 正向与负向喂**同一份判据**，避免「恒 false 表达式」）──

    /** 低频段占用列数**严格多于**线性基线（感知展开生效） */
    private fun beatsLinear(candidate: IntArray, linear: IntArray, cols: Int): Boolean =
        lowBandCols(candidate, cols) > lowBandCols(linear, cols)

    /** 前段步长 **<** 后段步长（低频更密） */
    private fun frontStepSmaller(b: IntArray, cols: Int): Boolean =
        b[cols / 4] - b[0] < b[cols] - b[cols * 3 / 4]

    // ── ① 覆盖全频段 + 严格单调 ────────────────────────────────────────
    @Test
    fun band_covers_full_spectrum_and_monotonic() {
        for (cols in qualities) {
            val b = buildBand(cols)
            assertEquals("cols=$cols: band[0] 应为 0", 0, b[0])
            assertEquals(
                "cols=$cols: band[cols] 应为 ${SpectrumContract.BAR_COUNT}（覆盖全频段，旧实现是 32）",
                SpectrumContract.BAR_COUNT, b[cols]
            )
            for (i in 0 until cols) {
                assertTrue(
                    "cols=$cols: band 必须严格单调递增（band[$i]=${b[i]} vs band[${i + 1}]=${b[i + 1]}）",
                    b[i] < b[i + 1]
                )
            }
        }
    }

    // ── ② 列列独立：命中不同桶数 == cols（⛔ 不再是列数的一半）────────
    @Test
    fun every_column_maps_to_distinct_bucket() {
        val expected = intArrayOf(20, 28, 36)   // LOW / MED / HIGH
        qualities.forEachIndexed { qi, cols ->
            val b = buildBand(cols)
            val distinct = (0 until cols).map { b[it] }.toSet().size
            assertEquals("cols=$cols: 命中不同桶数必须 == $cols（旧实现只有一半）", cols, distinct)
            assertEquals("cols=$cols: 列数必须等于规格值", expected[qi], cols)
        }
    }

    // ── ③ 不重叠无空隙：Σ(band[i+1]−band[i]) == 64 ─────────────────────
    @Test
    fun band_intervals_sum_to_64() {
        for (cols in qualities) {
            val b = buildBand(cols)
            val sum = (0 until cols).sumOf { b[it + 1] - b[it] }
            assertEquals(
                "cols=$cols: 区间和必须 == ${SpectrumContract.BAR_COUNT}（不重叠无空隙）",
                SpectrumContract.BAR_COUNT, sum
            )
        }
    }

    // ── ⑦（补充）每列区间非空：宽度 ≥ 1 ────────────────────────────────
    @Test
    fun every_interval_is_non_empty() {
        for (cols in qualities) {
            val b = buildBand(cols)
            val minWidth = (0 until cols).minOf { b[it + 1] - b[it] }
            assertTrue("cols=$cols: 每列区间宽度必须 ≥ 1，实测最小 $minWidth", minWidth >= 1)
        }
    }

    // ── ④ 感知分桶生效：低频占列数 **严格多于线性**，且前段步长 < 后段 ──
    @Test
    fun perceptual_bucketing_beats_linear() {
        for (cols in qualities) {
            val b = buildBand(cols)
            val lin = buildBandLinear(cols)
            val low = lowBandCols(b, cols)
            val lowLin = lowBandCols(lin, cols)
            // ⛔ 判据是「相对线性基准」而不是写死阈值：`cols / 4` 在 HIGH(36) 下
            //    只占 8 列（36 列铺 64 桶已接近 1 桶/列，"每列至少 1 桶"的下限
            //    把感知展开掩盖掉了）⇒ 写死阈值会**误判**（实测 8 < 36/4 = 9）。
            assertTrue(
                "cols=$cols: 感知划分下前 8 桶占列数($low) 必须严格多于线性($lowLin)",
                beatsLinear(b, lin, cols)
            )
            // 前段平均步长 < 后段平均步长（低频更密）—— 线性划分下两者相等
            val frontStep = b[cols / 4] - b[0]
            val backStep = b[cols] - b[cols * 3 / 4]
            assertTrue(
                "cols=$cols: 前段步长($frontStep) 必须 < 后段步长($backStep)",
                frontStepSmaller(b, cols)
            )
            assertTrue(
                "cols=$cols: 线性划分下前段(${lin[cols / 4]})与后段(${SpectrumContract.BAR_COUNT - lin[cols * 3 / 4]})步长应相等",
                lin[cols / 4] - lin[0] == SpectrumContract.BAR_COUNT - lin[cols * 3 / 4]
            )
        }
    }

    // ── ⑤ 小信号可见：v == MIN_AMPLITUDE ⇒ ≥ 1 格；半静音 ⇒ 0 格 ──────
    @Test
    fun small_signal_still_renders_one_block() {
        // 先钉住常量本身：它变了，下面的数值断言就都该重新推导
        assertEquals(0.02f, SpectrumContract.MIN_AMPLITUDE, 1e-6f)
        val minAmp = SpectrumContract.MIN_AMPLITUDE
        assertTrue(
            "v == MIN_AMPLITUDE 必须至少 1 格（小信号可见），实测 ${StaircaseWaveRenderer.stepsOf(minAmp)}",
            StaircaseWaveRenderer.stepsOf(minAmp) >= 1
        )
        assertEquals(
            "v == MIN_AMPLITUDE/2（真静音）必须是 0 格（才画轮廓格）",
            0, StaircaseWaveRenderer.stepsOf(minAmp * 0.5f)
        )
        assertEquals("v == 0f 必须是 0 格", 0, StaircaseWaveRenderer.stepsOf(0f))
        // 满量程不越界，且恰好等于 STEPS
        assertEquals(
            "v == 1f 必须满档",
            StaircaseWaveRenderer.STEPS.toInt(),
            StaircaseWaveRenderer.stepsOf(1f)
        )
        // 单调不减（信号越大格数越多）
        var prev = 0
        var v = 0f
        while (v <= 1.001f) {
            val s = StaircaseWaveRenderer.stepsOf(v)
            assertTrue("stepsOf 必须随 v 单调不减（v=$v）", s >= prev)
            prev = s
            v += 0.01f
        }
    }

    // ── ⑥ 亮度与 steps 无关（按绝对格位归一，修闪烁）─────────────────
    @Test
    fun block_alpha_independent_of_steps() {
        val a1 = StaircaseWaveRenderer.blockAlpha(st = 5, steps = 1)
        val a8 = StaircaseWaveRenderer.blockAlpha(st = 5, steps = 8)
        val a24 = StaircaseWaveRenderer.blockAlpha(st = 5, steps = 24)
        assertEquals("同一格位在 steps=1/8/24 下 alpha 必须相同", a1, a8, 1e-6f)
        assertEquals("同一格位在 steps=1/8/24 下 alpha 必须相同", a8, a24, 1e-6f)
        // 同一格位在**任意** steps 下都相同
        for (s in 1..StaircaseWaveRenderer.STEPS.toInt()) {
            assertEquals(
                "st=5 的 alpha 与 steps=$s 无关",
                a1, StaircaseWaveRenderer.blockAlpha(st = 5, steps = s), 1e-6f
            )
        }
        // 越高越亮 + 不越 0.95
        var prev = -1f
        for (st in 0 until StaircaseWaveRenderer.STEPS.toInt()) {
            val a = StaircaseWaveRenderer.blockAlpha(st = st, steps = 24)
            assertTrue("格位越高 alpha 必须越大（st=$st 实测 $a）", a > prev)
            assertTrue("alpha 必须 ≤ 0.95（st=$st 实测 $a）", a <= 0.95f)
            prev = a
        }
    }

    // ── ⑧（补充）碎块合批档位契约 ──────────────────────────────────────
    @Test
    fun shard_tier_and_alpha_contract() {
        val steps = StaircaseWaveRenderer.STEPS.toInt()
        val tiers = StaircaseWaveRenderer.SHARD_TIERS
        // 档位落在 [0, tiers)
        for (st in 0 until steps) {
            val t = StaircaseWaveRenderer.shardTier(st)
            assertTrue("st=$st 档位必须 ∈ [0,$tiers)，实测 $t", t in 0 until tiers)
        }
        // 单调不减
        for (st in 0 until steps - 1) {
            assertTrue(
                "档位必须随格位单调不减（st=$st）",
                StaircaseWaveRenderer.shardTier(st) <= StaircaseWaveRenderer.shardTier(st + 1)
            )
        }
        // 会被碎裂的那 2 格（顶部 st = steps-1 / steps-2）必须是**最高档**，
        // 否则碎块比同列正常块暗 —— 违背"顶部最亮"
        assertEquals("顶部格位必须是最高档", tiers - 1, StaircaseWaveRenderer.shardTier(steps - 1))
        assertEquals("次顶格位必须是最高档", tiers - 1, StaircaseWaveRenderer.shardTier(steps - 2))
        // 档位 alpha 单调递增且 ≤ 0.95
        var prev = -1f
        for (t in 0 until tiers) {
            val a = StaircaseWaveRenderer.shardAlpha(t)
            assertTrue("档位 alpha 必须单调递增（tier=$t 实测 $a）", a > prev)
            assertTrue("档位 alpha 必须 ≤ 0.95（tier=$t 实测 $a）", a <= 0.95f)
            prev = a
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 负向自证（4 条）—— 必须**实测挂掉**，否则门禁空转
    // ═══════════════════════════════════════════════════════════════════

    /** 负向①：边界表末位改回 32（`n / 2` 旧口径）⇒ ① 与 ③ 的判据必须判失败 */
    @Test
    fun negative_half_spectrum_band_fails_full_coverage() {
        for (cols in qualities) {
            val b = buildBand(cols).copyOf(cols + 1)
            b[cols] = 32   // 旧口径：只覆盖前 32 个桶
            val coversAll = b[cols] == SpectrumContract.BAR_COUNT
            val sum = (0 until cols).sumOf { b[it + 1] - b[it] }
            assertFalse("末位=32 时「覆盖全频段」判据必须失败", coversAll)
            assertFalse("末位=32 时「区间和 == 64」判据必须失败（实测和 = $sum）", sum == SpectrumContract.BAR_COUNT)
        }
    }

    /** 负向②：映射改回 `src = i / half * (n / 2)`（多列共用同一桶 + 左右镜像）⇒ ② 必须判失败 */
    @Test
    fun negative_old_mirror_mapping_fails_distinct_check() {
        val n = SpectrumContract.BAR_COUNT
        qualities.forEachIndexed { qi, cols ->
            val half = cols / 2f
            val distinct = (0 until cols).map { i ->
                if (i < half) {
                    (i / half * (n / 2)).toInt().coerceIn(0, n - 1)
                } else {
                    ((cols - 1 - i) / half * (n / 2)).toInt().coerceIn(0, n - 1)
                }
            }.toSet().size
            assertFalse(
                "cols=$cols: 旧镜像映射命中 $distinct 个不同桶，必须不通过「列列独立」判据",
                distinct == cols
            )
            assertEquals(
                "cols=$cols: 旧映射命中不同桶恰为列数的一半（规格值）",
                intArrayOf(10, 14, 18)[qi], distinct
            )
        }
    }

    /** 负向③：亮度改回 `(st + 1f) / steps` ⇒ ⑥ 必须判失败（同一 st 在不同 steps 下变了 = 闪烁） */
    @Test
    fun negative_steps_relative_alpha_flickers() {
        fun oldAlpha(st: Int, steps: Int) =
            (0.30f + (st + 1f) / steps * 0.65f).coerceAtMost(0.95f)
        val a1 = oldAlpha(5, 1)
        val a8 = oldAlpha(5, 8)
        assertFalse("旧公式下同一格位 alpha 随 steps 变（=闪烁），证明正向判据在抓真问题", a1 == a8)
        // 生产公式在同样两个 steps 下必须相等（对照）
        assertEquals(
            "生产公式必须与 steps 无关",
            StaircaseWaveRenderer.blockAlpha(5, 1),
            StaircaseWaveRenderer.blockAlpha(5, 8),
            1e-6f
        )
    }

    /** 负向④：分桶指数改回 1.0（线性）⇒ ④ 的两条判据必须判失败 */
    @Test
    fun negative_linear_bucketing_fails_perceptual_check() {
        for (cols in qualities) {
            val lin = buildBandLinear(cols)
            val perc = buildBand(cols)
            // 把**线性表自身**当候选喂进 ④ 的谓词（基线也是它自己）⇒ 必须判失败
            assertFalse(
                "cols=$cols: 候选 == 线性时「低频列数 > 线性」判据必须失败",
                beatsLinear(lin, lin, cols)
            )
            // 「前段步长 < 后段步长」在线性划分下相等 ⇒ 必须判失败
            val front = lin[cols / 4] - lin[0]
            val back = lin[cols] - lin[cols * 3 / 4]
            assertFalse(
                "cols=$cols: 线性划分下前段($front)必须不小于后段($back)",
                frontStepSmaller(lin, cols)
            )
            // 而感知划分必须严格成立（对照，证明上面两条不是恒 false）
            assertTrue("cols=$cols: 感知划分下前段必须 < 后段", frontStepSmaller(perc, cols))
            assertTrue(
                "cols=$cols: 感知划分下低频列数(${lowBandCols(perc, cols)})必须 > 线性(${lowBandCols(lin, cols)})",
                beatsLinear(perc, lin, cols)
            )
        }
    }
}

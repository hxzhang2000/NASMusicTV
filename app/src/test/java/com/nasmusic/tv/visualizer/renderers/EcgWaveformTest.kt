package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * §八 G8 — E24 心搏波形表形态学门禁（§A8 第 0 条 · P0）。
 *
 * **正向**：直接引用生产表 [EcgWaveRenderer.HB]（50 点，一列一采样、索引 = 整数列龄），
 * 断言临床时程与形态（等电位平线 / R 峰不被削平 / T 波单峰 / QRS 升快降慢 / P 波圆钝）。
 * ⛔ 不许在测试里复制表 —— 那门禁就盯不住生产代码了（§A8 明写）。
 *
 * **负向自证**：把旧实现（14 点 `HB_T`/`HB_V` + 线性插值）按同一显示网格重采样，
 * 喂进**与正向完全相同**的谓词函数，必须**不通过** —— 否则门禁是空转（§八 开头规矩）。
 *
 * ⚠️ **两处口径已在实现里统一**：
 * - 宽度比：早期版本用「全表峰值 5%」阈值 ⇒ 新表与旧表**都判 false**（空转）。
 *   现统一为「T 波与 QRS **各自** 5% 峰值高度以上的样本数之比」⇒ 新表 13/7 = 1.86（过）、
 *   旧表 11/9 = 1.22（挂），能区分。⛔ 不要退回全表峰值口径。
 * - 不用 `FloatArray.max()/min()`（Kotlin 1.7 起弃用，2.3.10 下会带构建噪音）⇒ 显式循环。
 *
 * 全部断言用 `assertTrue/assertFalse/assertEquals`（Kotlin 的 `assert` 在测试 JVM 是空操作）。
 */
class EcgWaveformTest {

    private val HB = EcgWaveRenderer.HB

    // ═══════════════════ 正向：生产表形态学 ═══════════════════

    /** ① 表长 50 列，且必须短于最短心搏间隔（防「改 speed 忘重建表」） */
    @Test
    fun `① 表长 50 列且短于最短心搏间隔`() {
        assertEquals(50, HB.size)
        // 列级判据（与 speed 无关，最精确）：心搏占列数必须 < 两次心搏的最短间隔
        assertTrue(
            "HB.size(${HB.size}) 必须 < MIN_GAP_COLS(${EcgWaveRenderer.MIN_GAP_COLS})，否则相邻心搏粘连",
            HB.size < EcgWaveRenderer.MIN_GAP_COLS
        )
        // 时间级判据（与 speed 绑定）：同一件事换算成 ms，改 speed 时这条会先炸
        val durationMs = HB.size / EcgWaveRenderer.SPEED_COLS_PER_SEC * 1000f
        val gapMs = EcgWaveRenderer.MIN_GAP_COLS / EcgWaveRenderer.SPEED_COLS_PER_SEC * 1000f
        assertTrue(
            "心搏时长 ${durationMs}ms 必须 < 最短间隔 ${gapMs}ms",
            durationMs < gapMs
        )
        assertEquals("50 列 @ 90 列/秒 = 555.6ms", 555.6f, durationMs, 0.1f)
    }

    /** ② R 峰精确落在第 19 显示列上、峰值恒 1.0（旧表 0.897 被削平） */
    @Test
    fun `② R 峰落在显示列上且满幅`() {
        assertTrue("峰值判据必须对生产表通过", peakOk(HB))
        assertEquals("R 峰应落在列 19", 19, argMaxRange(HB, 0, HB.size - 1))
        assertEquals(1.0f, HB[19], 1e-6f)
    }

    /** ③ S 谷 = −0.28 且是全表最低点 */
    @Test
    fun `③ S 谷为全表最低点`() {
        assertEquals("S 谷应落在列 22", 22, argMinRange(HB, 0, HB.size - 1))
        assertEquals(-0.28f, minOfArray(HB), 1e-6f)
        assertEquals(-0.28f, HB[22], 1e-6f)
    }

    /** ④ PR 段 [9..15] 与 ST 段 [25..33] 是等电位平线（全 0） */
    @Test
    fun `④ PR 段与 ST 段为等电位平线`() {
        assertTrue("等电位判据必须对生产表通过", isoelectricOk(HB))
    }

    /** ⑤ T 波相对宽度 > QRS 相对宽度 × 1.5（窄尖峰 vs 宽圆峰） */
    @Test
    fun `⑤ T 波相对宽度大于 QRS 的 1_5 倍`() {
        val ratio = widthRatio(HB)
        assertTrue("T/QRS 相对宽度比 $ratio 必须 > 1.5", widthRatioOk(HB))
        // 数值复核：设计区间比 15/9 = 1.67、可测量比 13/7 = 1.86（§A8 文档里的 1.80 是第三种粗记）
        assertTrue("相对宽度比应在 1.6..2.0 之间，实测 $ratio", ratio in 1.6f..2.0f)
    }

    /** ⑥ T 波是单峰圆丘：上升支单调不减、下降支单调不增 */
    @Test
    fun `⑥ T 波为单峰圆丘`() {
        val tPeak = argMaxRange(HB, 34, 48)
        for (i in 34 until tPeak) {
            assertTrue("T 上升支 HB[$i]=${HB[i]} <= HB[${i + 1}]=${HB[i + 1]}", HB[i] <= HB[i + 1] + 1e-6f)
        }
        for (i in tPeak until 48) {
            assertTrue("T 下降支 HB[$i]=${HB[i]} >= HB[${i + 1}]=${HB[i + 1]}", HB[i] >= HB[i + 1] - 1e-6f)
        }
        assertEquals("T 峰应落在列 40（§A8 表格）", 40, tPeak)
    }

    /** ⑦ 查表边界：负列龄与越界一律返回 0（无假信号泄漏） */
    @Test
    fun `⑦ 查表越界返回 0`() {
        assertEquals(0f, EcgWaveRenderer.heartbeatAt(0), 1e-6f)
        assertEquals(0f, EcgWaveRenderer.heartbeatAt(-1), 1e-6f)
        assertEquals(0f, EcgWaveRenderer.heartbeatAt(HB.size), 1e-6f)
        assertEquals(0f, EcgWaveRenderer.heartbeatAt(HB.size + 1), 1e-6f)
        // 哨兵列龄（NO_BEAT 量级）查表自然得 0 ⇒ 开播瞬间不漏 HB[1]=0.016 这种假信号
        assertEquals(0f, EcgWaveRenderer.heartbeatAt(EcgWaveRenderer.NO_BEAT), 1e-6f)
    }

    /** ⑧ 查表严格等于生产表 —— 「无插值、无平滑」这一契约本身 */
    @Test
    fun `⑧ 查表严格等于生产表无插值`() {
        for (i in HB.indices) {
            assertEquals(
                "heartbeatAt($i) 必须严格等于 HB[$i]（任何插值/平滑都会被抓住）",
                HB[i], EcgWaveRenderer.heartbeatAt(i), 0f
            )
        }
    }

    /** ⑨ P 波是圆钝小丘（峰顶跨 2 列），不是三角尖 */
    @Test
    fun `⑨ P 波为圆钝小丘`() {
        val pPeak = peakOfRange(HB, 0, 9)
        assertEquals("P 波峰顶应跨列 4-5（圆钝，非三角）", HB[4], HB[5], 1e-6f)
        assertEquals(pPeak, HB[4], 1e-6f)
        assertEquals(pPeak, HB[5], 1e-6f)
    }

    /** ⑩ QRS 升快降慢：R 峰到 Q 谷比到 S 谷更近 */
    @Test
    fun `⑩ QRS 升快降慢`() {
        val qIdx = argMinRange(HB, 16, 18)
        val rIdx = argMaxRange(HB, 17, 20)
        val sIdx = argMinRange(HB, 20, 24)
        assertEquals("Q 谷应落在列 17", 17, qIdx)
        assertEquals("R 峰应落在列 19", 19, rIdx)
        assertEquals("S 谷应落在列 22", 22, sIdx)
        assertTrue(
            "升支 ${rIdx - qIdx} 列 必须 < 降支 ${sIdx - rIdx} 列（升快降慢）",
            (rIdx - qIdx) < (sIdx - rIdx)
        )
    }

    // ═══════════════════ 负向自证：旧表必须挂 ═══════════════════
    // ⛔ 每个负向用例都复用正向用例的**同一个**谓词函数，否则自证无效。

    /** 负向 ④：旧表 PR/ST 段不是等电位平线 → 判失败 */
    @Test
    fun `负向④ 旧表必须挂等电位判据`() {
        val old = oldTableResampledToCols()
        // 前提自证：旧表在这两段确实有非零采样（下坡 / 上坡），否则自证本身失去意义
        val prNotFlat = (9..15).any { abs(old[it]) > 1e-3f }
        val stNotFlat = (25..33).any { abs(old[it]) > 1e-3f }
        assertTrue("旧表 PR/ST 段应有非零采样（下坡/上坡）", prNotFlat || stNotFlat)
        assertFalse("等电位判据对旧表必须不通过（否则门禁空转）", isoelectricOk(old))
    }

    /** 负向 ②：旧表 R 峰被削平（列 19 采样 ≈ 0.897 ≠ 1.0）→ 判失败 */
    @Test
    fun `负向② 旧表必须挂峰值判据`() {
        val old = oldTableResampledToCols()
        assertFalse("峰值判据对旧表必须不通过（旧采样峰值 0.897）", peakOk(old))
        assertTrue("数值复核：旧表列 19 应 ≈0.897，实测 ${old[19]}", old[19] in 0.86f..0.93f)
    }

    /** 负向 ⑤：旧表 T/QRS 相对宽度比 1.22 < 1.5 → 判失败 */
    @Test
    fun `负向⑤ 旧表必须挂宽度比判据`() {
        val old = oldTableResampledToCols()
        val ratio = widthRatio(old)
        assertFalse("宽度比判据对旧表必须不通过（旧表比 ≈1.22，实测 $ratio）", widthRatioOk(old))
        assertTrue("旧表相对宽度比应 < 1.5，实测 $ratio", ratio < 1.5f)
    }

    /** 负向 ⑧：旧实现确实在做线性插值 → 「严格等于表值」判据必然不通过 */
    @Test
    fun `负向⑧ 旧表必须挂无插值判据`() {
        val old = oldTableResampledToCols()
        val offTable = old.count { v -> OLD_V.none { abs(it - v) <= 1e-6f } }
        assertTrue("旧实现应产出插值值（实测 $offTable 个非表值）", offTable > 0)
        assertFalse("「严格等于表值」判据对旧表必须不通过", allValuesInTable(old, OLD_V))
    }

    // ═══════════════════ 谓词（正向 / 负向共用）═══════════════════

    /** 等电位：PR 段 [9..15] 与 ST 段 [25..33] 全为 0 */
    private fun isoelectricOk(t: FloatArray): Boolean {
        for (i in 9..15) if (abs(t[i]) > 1e-3f) return false
        for (i in 25..33) if (abs(t[i]) > 1e-3f) return false
        return true
    }

    /** R 峰未被削平：全表峰值就落在列 19，且为满幅 1.0 */
    private fun peakOk(t: FloatArray): Boolean =
        abs(peakOf(t) - t[19]) <= 1e-6f && abs(t[19] - 1.0f) <= 1e-6f

    /**
     * T/QRS 相对宽度比。⛔ 分母/分子**各自**取本段 5% 峰值高度做门限 ——
     * 用全表峰值做门限时新旧表都判 false（判据空转，见类 KDoc）。
     */
    private fun widthRatio(t: FloatArray): Float {
        val rPeak = peakOf(t)
        val tPeak = peakOfRange(t, 34, 48)
        var qrsCols = 0
        for (i in 16..24) if (abs(t[i]) >= rPeak * 0.05f) qrsCols++
        var tCols = 0
        for (i in 34..48) if (t[i] >= tPeak * 0.05f) tCols++
        return if (qrsCols == 0) 0f else tCols.toFloat() / qrsCols
    }

    private fun widthRatioOk(t: FloatArray): Boolean = widthRatio(t) > 1.5f

    /** t 的每个值都能在 table 里找到（⇒ 没有插值/平滑） */
    private fun allValuesInTable(t: FloatArray, table: FloatArray): Boolean =
        t.all { v -> table.any { abs(it - v) <= 1e-6f } }

    // ── 显式循环（避开已弃用的 `FloatArray.max()/min()`）──

    private fun peakOf(t: FloatArray): Float {
        var m = t[0]
        for (v in t) if (v > m) m = v
        return m
    }

    private fun minOfArray(t: FloatArray): Float {
        var m = t[0]
        for (v in t) if (v < m) m = v
        return m
    }

    private fun peakOfRange(t: FloatArray, a: Int, b: Int): Float {
        var m = t[a]
        for (i in a..b) if (t[i] > m) m = t[i]
        return m
    }

    private fun argMaxRange(t: FloatArray, a: Int, b: Int): Int {
        var k = a
        for (i in a..b) if (t[i] > t[k]) k = i
        return k
    }

    private fun argMinRange(t: FloatArray, a: Int, b: Int): Int {
        var k = a
        for (i in a..b) if (t[i] < t[k]) k = i
        return k
    }

    // ═══════════════════ 旧实现（照抄改造前，仅供负向自证）═══════════════════

    private val OLD_T = floatArrayOf(
        0.000f, 0.048f, 0.090f, 0.132f, 0.164f, 0.186f,
        0.214f, 0.238f, 0.268f, 0.298f, 0.330f, 0.392f, 0.452f, 0.500f
    )
    private val OLD_V = floatArrayOf(
        0.000f, 0.000f, 0.130f, 0.000f, -0.120f, 0.000f,
        1.000f, -0.060f, -0.300f, -0.040f, 0.080f, 0.240f, 0.060f, 0.000f
    )

    /** 旧 `heartbeatAt`：线性扫描 + 分段线性插值（入参为秒） */
    private fun oldHeartbeatAt(ageSec: Float): Float {
        if (ageSec <= 0f) return 0f
        val last = OLD_T.size - 1
        if (ageSec >= OLD_T[last]) return 0f
        var i = 1
        while (i < last && OLD_T[i] < ageSec) i++
        val t0 = OLD_T[i - 1]
        val t1 = OLD_T[i]
        val v0 = OLD_V[i - 1]
        val v1 = OLD_V[i]
        return v0 + (v1 - v0) * ((ageSec - t0) / (t1 - t0))
    }

    /** 把旧表按显示网格重采样成「一列一个采样」（1 列 = 1000/90 ms） */
    private fun oldTableResampledToCols(): FloatArray {
        val cols = 50
        val out = FloatArray(cols)
        for (c in 0 until cols) out[c] = oldHeartbeatAt(c / EcgWaveRenderer.SPEED_COLS_PER_SEC)
        return out
    }
}

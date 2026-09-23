package com.nasmusic.tv.visualizer.photo

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门禁 **G10** —— 内存预算与降采样（§14.4）
 *
 * 断言内容：双缓冲 + LRU 的 `estimatedBytes` ≤ 预算。
 *
 * 负向自证：把 `maxCached` 调大 ⇒ 断言**超预算**（证明预算确实随容量变化，
 * 而不是写死的常量在自我满足）。
 *
 * ⚠️ 纯 JVM 测试 —— 不依赖 `Bitmap`，所以不起 Robolectric（验一个乘法不需要真位图）。
 * ⚠️ 项目硬约定：一律用 JUnit 的 `assertTrue`（Kotlin `assert` 在测试 JVM 是空操作）。
 */
class PhotoBufferBudgetTest {

    private val budget = PhotoBufferMath.DEFAULT_BUDGET_BYTES

    // ────────────────────── ① 预算口径 ──────────────────────

    @Test
    fun `single bitmap byte count is correct`() {
        // 1920 × 1080 × 4 = 8_294_400
        val argb = PhotoBufferMath.bytesPerBitmap(1920, 1080, allowRgb565 = false)
        assertTrue("1080p ARGB_8888 单张应为 8,294,400，实际 $argb", argb == 8_294_400L)

        // 1920 × 1080 × 2 = 4_147_200
        val rgb565 = PhotoBufferMath.bytesPerBitmap(1920, 1080, allowRgb565 = true)
        assertTrue("1080p RGB_565 单张应为 4,147,200，实际 $rgb565", rgb565 == 4_147_200L)
    }

    @Test
    fun `default double buffer plus lru fits the budget`() {
        val total = PhotoBufferMath.estimatedTotal(1920, 1080, maxCached = 3, allowRgb565 = false)
        assertTrue("三张应为 24,883,200，实际 $total", total == 24_883_200L)
        assertTrue(
            "1080p ARGB_8888 双缓冲 2 + LRU 1 应在预算内（预算 $budget，实际 $total）",
            PhotoBufferMath.isWithinBudget(total, budget),
        )
    }

    @Test
    fun `rgb565 downgrade halves the footprint`() {
        val argb = PhotoBufferMath.estimatedTotal(1920, 1080, maxCached = 3, allowRgb565 = false)
        val rgb565 = PhotoBufferMath.estimatedTotal(1920, 1080, maxCached = 3, allowRgb565 = true)
        assertTrue("RGB_565 应恰好是 ARGB_8888 的一半：$rgb565 vs $argb", rgb565 * 2 == argb)
        assertTrue("降级档更应在预算内", PhotoBufferMath.isWithinBudget(rgb565, budget))
    }

    @Test
    fun `budget scales with maxCached`() {
        val three = PhotoBufferMath.estimatedTotal(1920, 1080, maxCached = 3, allowRgb565 = false)
        val five = PhotoBufferMath.estimatedTotal(1920, 1080, maxCached = 5, allowRgb565 = false)
        assertTrue("容量翻倍口径应线性增长", five > three)
        assertTrue("5 张（= 文档 §八 的 41 MB 口径）仍在预算内", PhotoBufferMath.isWithinBudget(five, budget))
    }

    @Test
    fun `invalid dimensions yield zero rather than throwing`() {
        assertTrue("宽为 0 应返回 0", PhotoBufferMath.bytesPerBitmap(0, 1080, false) == 0L)
        assertTrue("高为负应返回 0", PhotoBufferMath.bytesPerBitmap(1920, -1, false) == 0L)
        assertTrue("容量为 0 应返回 0", PhotoBufferMath.estimatedTotal(1920, 1080, 0, false) == 0L)
        assertTrue("非法尺寸不应超预算", PhotoBufferMath.isWithinBudget(0L, budget))
    }

    // ────────────────────── ② 降采样 ──────────────────────

    @Test
    fun `sample size keeps the result no smaller than target`() {
        // 4000×3000 → 目标 1920×1080：/2 后 2000×1500 仍 ≥ 目标，/4 后 1000×750 < 目标 ⇒ 2
        assertTrue(
            "4000×3000 → 1920×1080 应为 2，实际 ${PhotoBufferMath.computeInSampleSize(4000, 3000, 1920, 1080)}",
            PhotoBufferMath.computeInSampleSize(4000, 3000, 1920, 1080) == 2,
        )
        // 8000×6000 → 1920×1080：/4 后 2000×1500 ≥ 目标，/8 后 1000×750 < 目标 ⇒ 4
        assertTrue(
            "8000×6000 → 1920×1080 应为 4，实际 ${PhotoBufferMath.computeInSampleSize(8000, 6000, 1920, 1080)}",
            PhotoBufferMath.computeInSampleSize(8000, 6000, 1920, 1080) == 4,
        )
    }

    @Test
    fun `image smaller than target is not downsampled`() {
        assertTrue(
            "图比目标小 ⇒ 不降采样（sample = 1）",
            PhotoBufferMath.computeInSampleSize(1000, 800, 1920, 1080) == 1,
        )
        assertTrue(
            "正好等于目标 ⇒ 不降采样",
            PhotoBufferMath.computeInSampleSize(1920, 1080, 1920, 1080) == 1,
        )
    }

    @Test
    fun `invalid arguments fall back to one`() {
        assertTrue("src 非法应返回 1", PhotoBufferMath.computeInSampleSize(0, 100, 1920, 1080) == 1)
        assertTrue("dst 非法应返回 1", PhotoBufferMath.computeInSampleSize(4000, 3000, 0, 0) == 1)
        assertTrue("负值应返回 1", PhotoBufferMath.computeInSampleSize(-1, -1, -1, -1) == 1)
    }

    @Test
    fun `sample size is always a power of two`() {
        for (src in listOf(400, 1000, 1920, 4000, 8000, 12000)) {
            val s = PhotoBufferMath.computeInSampleSize(src, src, 1920, 1080)
            assertTrue("sample 必须是 2 的幂（src=$src，实际 $s）", s > 0 && (s and (s - 1)) == 0)
        }
    }

    // ────────────────────── ③ 负向自证 ──────────────────────

    /**
     * **负向自证**：把容量调大到明显不合理 ⇒ 断言**超预算**。
     *
     * 若本用例变成「仍在预算内」，说明 [PhotoBufferMath.estimatedTotal] 忽略了 `maxCached`
     * （例如把预算写死成常量），那么 G10 的「≤ 预算」断言就变成了**空转自证**。
     */
    @Test
    fun `negative proof - oversized cache exceeds the budget`() {
        val total = PhotoBufferMath.estimatedTotal(1920, 1080, maxCached = 12, allowRgb565 = false)
        assertTrue(
            "12 张 1080p ARGB_8888（99,532,800 B）必须超预算（$budget），实际判定为在预算内 ⇒ 预算没跟容量走",
            !PhotoBufferMath.isWithinBudget(total, budget),
        )
    }

    /**
     * **负向自证**：`computeInSampleSize` 不能**过度**降采样。
     *
     * 1000px 的图缩到目标 300px：正确结果是 2（得 500px，仍 ≥ 300）；
     * 若有人改成「`src/dst` 向上取 2 的幂」（= 4），结果只有 250px **小于目标** ⇒ 糊。
     */
    @Test
    fun `negative proof - sample size must not undershoot the target`() {
        val src = 1000
        val dst = 300
        val sample = PhotoBufferMath.computeInSampleSize(src, src, dst, dst)
        assertTrue(
            "sample 不能过度降采样：$src / $sample 必须 ≥ $dst（实际 ${src / sample}）",
            src / sample >= dst,
        )
        assertTrue("正确结果应为 2（不是向上取整的 4）", sample == 2)
    }
}

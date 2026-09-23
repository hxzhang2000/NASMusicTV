package com.nasmusic.tv.visualizer

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 缓动库验证（§15.3 T5.1 验收：「每个缓动函数端点值正确（`f(0)=0`、`f(1)=1`）」）
 *
 * ## 为什么端点必须逐项断言
 *
 * 缓动函数的端点差一点，转场会在**开始或结束的那一帧跳一下** —— 那是肉眼可见的
 * 瑕疵，但因为只发生在单帧，很难定位到「是缓动的问题」。
 * 端点是最容易抄错的地方（半角/倍角公式、`1 - t` 写成 `t - 1`、忘记短路）。
 *
 * ⚠️ `easeOutElastic` 在端点必须**短路返回**：公式本身在 `t=1` 处算出的是
 * `sin(9.25 × c4)` 这类非零值。
 */
class EasingTest {

    /** 全部「两端应精确落在 0 / 1」的缓动（Back / Elastic 类也在内 —— 它们的超调发生在中间） */
    private val all: List<Pair<String, (Float) -> Float>> = listOf(
        "linear" to Easing::linear,
        "easeInQuad" to Easing::easeInQuad,
        "easeOutQuad" to Easing::easeOutQuad,
        "easeInOutQuad" to Easing::easeInOutQuad,
        "easeInCubic" to Easing::easeInCubic,
        "easeOutCubic" to Easing::easeOutCubic,
        "easeInOutCubic" to Easing::easeInOutCubic,
        "easeInSine" to Easing::easeInSine,
        "easeOutSine" to Easing::easeOutSine,
        "easeInOutSine" to Easing::easeInOutSine,
        "easeInBack" to Easing::easeInBack,
        "easeOutBack" to Easing::easeOutBack,
        "easeInOutBack" to Easing::easeInOutBack,
        "easeOutElastic" to Easing::easeOutElastic,
        "easeOutBounce" to Easing::easeOutBounce,
    )

    // ────────────────────── ① 端点 ──────────────────────

    @Test
    fun `every easing starts at zero and ends at one`() {
        for ((name, f) in all) {
            val at0 = f(0f)
            val at1 = f(1f)
            assertTrue("$name 的 f(0) 应为 0，实际 $at0", abs(at0) < EPS)
            assertTrue("$name 的 f(1) 应为 1，实际 $at1", abs(at1 - 1f) < EPS)
        }
    }

    @Test
    fun `linear is identity`() {
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertTrue("linear($t) 应等于 $t，实际 ${Easing.linear(t)}", abs(Easing.linear(t) - t) < EPS)
        }
    }

    // ────────────────────── ② 中点 / 对称性 ──────────────────────

    @Test
    fun `symmetric easings pass through the midpoint`() {
        // easeInOut* 关于 (0.5, 0.5) 中心对称
        for ((name, f) in listOf(
            "easeInOutQuad" to Easing::easeInOutQuad,
            "easeInOutCubic" to Easing::easeInOutCubic,
            "easeInOutSine" to Easing::easeInOutSine,
            "easeInOutBack" to Easing::easeInOutBack,
        )) {
            val mid = f(0.5f)
            assertTrue("$name 的 f(0.5) 应为 0.5，实际 $mid", abs(mid - 0.5f) < 1e-3f)
        }
    }

    @Test
    fun `easeIn and easeOut are mirrored at the midpoint`() {
        // easeOutQuad(0.5) = 0.75（前快后慢）；easeInQuad(0.5) = 0.25（前慢后快）
        assertTrue("easeOutQuad(0.5) 应为 0.75，实际 ${Easing.easeOutQuad(0.5f)}", abs(Easing.easeOutQuad(0.5f) - 0.75f) < EPS)
        assertTrue("easeInQuad(0.5) 应为 0.25，实际 ${Easing.easeInQuad(0.5f)}", abs(Easing.easeInQuad(0.5f) - 0.25f) < EPS)
        assertTrue("easeOutCubic(0.5) 应为 0.875，实际 ${Easing.easeOutCubic(0.5f)}", abs(Easing.easeOutCubic(0.5f) - 0.875f) < EPS)
    }

    @Test
    fun `easeOut variants lead easeIn variants`() {
        // 「easeOut = 起步快」：同一进度下 easeOut 的值必须大于 easeIn
        for (t in listOf(0.1f, 0.25f, 0.4f)) {
            assertTrue(
                "t=$t 时 easeOutQuad 应大于 easeInQuad（${Easing.easeOutQuad(t)} vs ${Easing.easeInQuad(t)}）",
                Easing.easeOutQuad(t) > Easing.easeInQuad(t),
            )
        }
    }

    // ────────────────────── ③ 超调类 ──────────────────────

    @Test
    fun `back and elastic overshoot but stay near range`() {
        // easeOutBack 会冲过 1；easeOutElastic 也会。但不该离谱（|v| 应在 [-1, 2] 内）
        var backMax = 0f
        var elasticMax = 0f
        for (i in 0..100) {
            val t = i / 100f
            backMax = maxOf(backMax, Easing.easeOutBack(t))
            elasticMax = maxOf(elasticMax, Easing.easeOutElastic(t))
        }
        assertTrue("easeOutBack 应冲过 1（否则不是回弹），实际峰值 $backMax", backMax > 1f)
        assertTrue("easeOutElastic 应冲过 1，实际峰值 $elasticMax", elasticMax > 1f)
        assertTrue("easeOutBack 峰值不该超过 1.2，实际 $backMax", backMax < 1.2f)
        assertTrue("easeOutElastic 峰值不该超过 2，实际 $elasticMax", elasticMax < 2f)
    }

    @Test
    fun `easeOutBounce stays within zero and one`() {
        for (i in 0..100) {
            val t = i / 100f
            val v = Easing.easeOutBounce(t)
            assertTrue("easeOutBounce($t) = $v 越界（应在 0..1）", v >= -EPS && v <= 1f + EPS)
        }
    }

    // ────────────────────── ④ stagger ──────────────────────

    @Test
    fun `stagger spreads delays evenly`() {
        val total = 5
        val maxDelay = 0.3f
        assertTrue("首块无延迟", abs(Easing.stagger(0, total, maxDelay)) < EPS)
        assertTrue("末块延迟 = maxDelay", abs(Easing.stagger(total - 1, total, maxDelay) - maxDelay) < EPS)
        assertTrue("中间块应等分", abs(Easing.stagger(2, total, maxDelay) - maxDelay * 0.5f) < EPS)
    }

    @Test
    fun `stagger handles degenerate inputs`() {
        assertTrue("单块没有错落可言", Easing.stagger(0, 1, 0.3f) == 0f)
        assertTrue("块数为 0 应返回 0（不崩）", Easing.stagger(0, 0, 0.3f) == 0f)
        assertTrue("下标越界应钳制到末块", abs(Easing.stagger(99, 3, 0.3f) - 0.3f) < EPS)
        assertTrue("负下标应钳制到首块", Easing.stagger(-5, 3, 0.3f) == 0f)
    }

    // ────────────────────── ⑤ 负向自证 ──────────────────────

    /**
     * **负向自证**：端点检查**单独不够**。
     *
     * 把 `easeOutQuad` 误写成 `t * t`（= `easeInQuad`）时，端点 `0 / 1` **依然正确**
     * —— 所以只验端点的话，这个错误会一路溜过去，直到用户看到「转场起步发涩」。
     * 本用例证明「中点检查」确实有判别力：错误实现的 `f(0.5) = 0.25`，
     * 与正确值 `0.75` 差 0.5，远超容差。
     */
    @Test
    fun `negative proof - endpoint check alone cannot catch a swapped ease`() {
        val buggy: (Float) -> Float = { t -> t * t }   // 把 easeOutQuad 写成了 easeInQuad

        // 端点：错误实现照样通过
        assertTrue("错误实现的端点也是 0/1（所以端点检查抓不到它）", abs(buggy(0f)) < EPS && abs(buggy(1f) - 1f) < EPS)
        // 中点：错误实现立刻暴露
        assertTrue(
            "中点检查必须判出问题：错误实现 f(0.5) = ${buggy(0.5f)}，正确值 ${Easing.easeOutQuad(0.5f)}",
            abs(buggy(0.5f) - Easing.easeOutQuad(0.5f)) > 0.1f,
        )
    }

    /**
     * **负向自证**：`easeOutElastic` 的端点短路是**必需**的。
     *
     * 这里用不带短路的公式复算一遍，断言它在 `t = 1` 处**超出端点门禁的容差**
     * —— 证明「短路那两行」不是冗余代码。
     *
     * ⚠️ 阈值必须用 `EPS`（端点门禁的容差 1e-4），**不能用「肉眼看着差不多」的 1e-3**：
     * 实测不带短路时 `t = 1` 处算得 `1.0004882812…`，偏差 **4.88e-4** ——
     * 恰好落在 `1e-4` 与 `1e-3` 之间。写 `1e-3` 会让这条负向自证**自己失败**
     * （首轮实测就踩了这个坑），因为那个偏差「看起来足够小」。
     */
    @Test
    fun `negative proof - elastic needs the endpoint short circuit`() {
        val c4 = (2f * Math.PI.toFloat()) / 3f
        val t = 1f
        val raw = Math.pow(2.0, -10.0 * t).toFloat() * kotlin.math.sin((t * 10f - 0.75f) * c4) + 1f
        assertTrue(
            "不带短路时 t=1 处算得 $raw（偏差 ${abs(raw - 1f)}，大于端点门禁容差 $EPS）⇒ 短路两行是必需的",
            abs(raw - 1f) > EPS,
        )
        assertTrue("带短路的实现必须精确等于 1", abs(Easing.easeOutElastic(1f) - 1f) < EPS)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}

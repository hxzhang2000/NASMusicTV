package com.nasmusic.tv.visualizer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 缓动函数库（§5.4）
 *
 * 全部是**纯函数、零分配**（不创建对象、不装箱），可直接用于绘制路径。
 * 参数 `t` 语义为进度 `[0, 1]`；**越界不抛异常**，按公式外推（调用方负责钳制）。
 *
 * ⛔ **端点值必须精确**：`f(0) == 0` 且 `f(1) == 1`（回弹类除外，见各函数说明）。
 * 门禁 `EasingTest` 逐个断言 —— 端点差一点，转场开始/结束时会「跳一下」。
 *
 * ⚠️ 命名沿用 CSS / 通用缓动惯例（`easeInOutQuad` 这类）。
 * 文档 §5.3 里出现过的**裸 `easeInOut` / `easeOut` 不在本库中** ——
 * 统一映射为 `easeInOutQuad` / `easeOutQuad`（§14.3 已注明该修正）。
 */
object Easing {

    // ────────────────────────── 线性 ──────────────────────────

    fun linear(t: Float): Float = t

    // ────────────────────────── Quad ──────────────────────────

    fun easeInQuad(t: Float): Float = t * t

    fun easeOutQuad(t: Float): Float {
        val u = 1f - t
        return 1f - u * u
    }

    fun easeInOutQuad(t: Float): Float =
        if (t < 0.5f) {
            2f * t * t
        } else {
            val u = 1f - t
            1f - 2f * u * u
        }

    // ────────────────────────── Cubic ──────────────────────────

    fun easeInCubic(t: Float): Float = t * t * t

    fun easeOutCubic(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    fun easeInOutCubic(t: Float): Float {
        val u = 1f - t
        return if (t < 0.5f) {
            4f * t * t * t
        } else {
            1f - 4f * u * u * u
        }
    }

    // ────────────────────────── Sine ──────────────────────────

    fun easeInSine(t: Float): Float = 1f - cos(t * HALF_PI)

    fun easeOutSine(t: Float): Float = sin(t * HALF_PI)

    fun easeInOutSine(t: Float): Float = -(cos(PI.toFloat() * t) - 1f) * 0.5f

    // ────────────────────────── Back（回拉）──────────────────────────

    /** ⚠️ 会**越过** 0（起始段先向负方向回拉）—— 端点 `f(0) = 0` 仍成立 */
    fun easeInBack(t: Float): Float {
        val c1 = BACK_C1
        val c3 = c1 + 1f
        return c3 * t * t * t - c1 * t * t
    }

    /** ⚠️ 会**越过** 1（收尾段冲过头再回来）—— 端点 `f(1) = 1` 仍成立 */
    fun easeOutBack(t: Float): Float {
        val c1 = BACK_C1
        val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    fun easeInOutBack(t: Float): Float {
        val c2 = BACK_C1 * 1.525f
        return if (t < 0.5f) {
            val d = 2f * t
            d * d * ((c2 + 1f) * d - c2) * 0.5f
        } else {
            val d = 2f * t - 2f
            (d * d * ((c2 + 1f) * d + c2) + 2f) * 0.5f
        }
    }

    // ────────────────────────── Elastic / Bounce ──────────────────────────

    /**
     * 弹性收尾。
     *
     * ⚠️ `t = 0` / `t = 1` 必须**短路**返回 0 / 1 —— 公式本身在端点会算出
     * `sin(-0.75 × c4)` 这类非零值，不短路就会「起手一抖」。
     */
    fun easeOutElastic(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val c4 = (2f * PI.toFloat()) / 3f
        return 2f.pow(-10f * t) * sin((t * 10f - 0.75f) * c4) + 1f
    }

    /**
     * 弹跳收尾（四段抛物线拼接）。
     *
     * ⚠️ 各段阈值用 `1f / 2.75f` 的**倍数**表达，不要手写小数（`0.36363636` 这类
     * 手写常数一旦抄错，段与段之间会出现断点，视觉上是「弹到一半卡一下」）。
     */
    fun easeOutBounce(t: Float): Float {
        val n = 7.5625f
        val d = 2.75f
        return when {
            t < 1f / d -> n * t * t
            t < 2f / d -> {
                val u = t - 1.5f / d
                n * u * u + 0.75f
            }

            t < 2.5f / d -> {
                val u = t - 2.25f / d
                n * u * u + 0.9375f
            }

            else -> {
                val u = t - 2.625f / d
                n * u * u + 0.984375f
            }
        }
    }

    // ────────────────────────── 错落 ──────────────────────────

    /**
     * 分块错落延迟（§5.3「条纹 / 分块」：基准 0.9s **+ 逐块 stagger 0.3s**）。
     *
     * 返回第 [index] 块的**延迟量**，范围 `[0, maxDelay]`：首块 0，末块 [maxDelay]。
     * 调用方按需缩放（例如把返回的 0.3 当作「0.3 × 总时长的进度偏移」）。
     *
     * @param total 块总数；`<= 1` 时恒返回 0（没有错落可言）
     */
    fun stagger(index: Int, total: Int, maxDelay: Float): Float {
        if (total <= 1) return 0f
        val i = index.coerceIn(0, total - 1)
        return maxDelay * i / (total - 1).toFloat()
    }

    private const val HALF_PI = (PI / 2.0).toFloat()

    /** Back 类的标准超调系数（1.70158 是通用缓动库的约定值） */
    private const val BACK_C1 = 1.70158f
}

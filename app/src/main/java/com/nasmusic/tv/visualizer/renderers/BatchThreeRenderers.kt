package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import kotlin.math.cos
import kotlin.math.sin

/**
 * E30 `RADAR_GRID` — 雷达 · 真实 PPI（Plan Position Indicator）
 *
 * 视觉：一条**匀速**旋转的扫线（`TAU / SWEEP_SPEED` = 3.93 s/圈，**不随音乐变速**）；
 * 扫线经过目标时，回波打在**目标自己的方位角**上（短弧，弧宽 ≈ 波束宽度 `BEAM_RAD`），
 * 靠**磷光余辉**留在原地线性衰减（`holdSec ≈ 1.15 × 周期` ⇒ 撑到下一圈刷新）；
 * 目标**连续漂移** ⇒ **下一圈扫到时已经换位置**；寿命到**重生**（换一批方位/距离，
 * **不是消失** —— E19 踩过「池耗尽」的坑）。
 *
 * 音频映射（保持"音乐可视化"身份，但机制物理正确）：
 * - 回波亮度/粗细 = 被扫到那一刻的 `spectrum[BIN]`（频谱强的目标更亮更粗）；
 * - 目标数量随 energy `8..24`（安静稀疏、激烈满屏），**替代原「32 段弧全亮」**；
 * - treble **缩短余辉**（`holdSec` 除以 `1 + treble×0.8`）⇒ 画面更"脆"，
 *   但**不破坏匀速旋转的机械感**；
 * - ⛔ 方位/距离与频率**解耦**（§十三 裁决项 6）：写死"低频=近、高频=远"会让图案
 *   退化成同心圆、失去散点感。
 *
 * **修掉的真 bug**：旧 `ensureBrush` 在尺寸未变时直接 `return`，而 `SweepGradient` 的
 * 停靠点又是按 `sweepAngle` 算的 ⇒ 扫掠渐变扇**钉死在 252°–360° 从不旋转**。
 * 现停靠点**固定 0.86–1.0**（与 `sweepAngle` 无关），旋转交给 `withTransform`
 * （**inline**，零分配；⛔ 不每帧重建 `Brush`）。
 *
 * 配色：单色雷达绿（固定，不用封面色）+ **三档明度**（§A9 第 2 条）——
 * 静态网格 `gridDim` / 活动回波 `gridColor` / 扫掠 `gridBright`。
 *
 * 性能红线：`drawContent` 内零分配。目标池 `FloatArray(TARGET_N × STRIDE)` 预分配；
 * 两个 `Brush` 只在画布尺寸变化时重建；`nextRandom` 是**构造期捕获**的成员 lambda
 * （写成调用点 lambda 会每帧分配一个实例）；仿真逻辑抽成 `internal` 纯函数
 * （[crossed] / [stepTargets]），JVM 可直接测（§八 G9）。
 */
class RadarGridRenderer : RendererFx() {

    override val theme = VisualizerTheme.RADAR_GRID

    /** §A9 第 3 条 CRT 后处理（暗角 0.52 ≥ §13.5-D9 下限 0.42） */
    override val postFx = PostFx(vignette = 0.52f, grain = 0.034f, scanline = 0.15f)

    // ── §A9 第 2 条：三档明度（静态网格 / 活动回波 / 扫掠）──
    private val gridDim = VisualizerMath.darken(Color(RADAR_GREEN), 0.45f)
    private val gridColor = Color(RADAR_GREEN)
    private val gridBright = VisualizerMath.towardWhite(gridColor, 0.5f)

    /** 扫掠余辉扇渐变（预分配；停靠点固定 0.86–1.0，旋转由 `withTransform` 完成） */
    private var sweepBrush: Brush? = null
    /** §A9 第 1 条：荧光屏中心极淡绿光（预分配） */
    private var centerGlow: Brush? = null
    private var brushCx = -1f
    private var brushCy = -1f
    private var brushMaxR = -1f

    private var sweepAngle = 0f
    private var trebleSmooth = 0f
    private var bassSmooth = 0f

    /** 目标池（零分配）：`TARGET_N × STRIDE` 平铺，字段序见 companion 常量 */
    private val pool = FloatArray(TARGET_N * STRIDE)
    private var poolInitialized = false

    /**
     * ⛔ **构造期捕获**的随机数 lambda：`stepTargets` 每帧都要用它，写成调用点 lambda
     * （`stepTargets(..., { rng.next() })`）会**每帧分配一个 lambda 实例**（§四 G15）。
     * `rng` 是基类的 `protected val`，基类构造先于子类属性初始化 ⇒ 此处引用安全。
     */
    private val nextRandom: () -> Float = { rng.next() }

    override fun onEnterContent(ctx: RenderContext) {
        sweepAngle = 0f
        trebleSmooth = 0f
        bassSmooth = 0f
        sweepBrush = null
        centerGlow = null
        brushCx = -1f
        brushCy = -1f
        brushMaxR = -1f
        poolInitialized = false
    }

    private fun ensureBrushes(cx: Float, cy: Float, maxR: Float) {
        if (sweepBrush != null && brushCx == cx && brushCy == cy && brushMaxR == maxR) return
        brushCx = cx
        brushCy = cy
        brushMaxR = maxR
        // 停靠点写死在 0.86–1.0（**不再依赖 sweepAngle**）⇒ 扇面本身不动
        sweepBrush = Brush.sweepGradient(
            0f to Color.Transparent,
            0.86f to Color.Transparent,
            0.97f to gridBright.copy(alpha = 0.45f),
            1.00f to Color.Transparent,
            center = Offset(cx, cy)
        )
        // §A9 第 1 条：中心绿光（荧光屏纵深）
        centerGlow = Brush.radialGradient(
            0f to gridColor.copy(alpha = 0.30f),
            1f to Color.Transparent,
            center = Offset(cx, cy),
            radius = maxR
        )
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return
        val dtSec = fx.dt

        // ── 信号平滑 ──
        trebleSmooth += (frame.treble - trebleSmooth) * 0.20f
        bassSmooth += (frame.bass - bassSmooth) * 0.10f

        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxR = ctx.minDim * 0.42f
        // ── 低音向心收缩：网格半径整体微缩 ──
        val r = maxR * (1f - bassSmooth * 0.06f)

        ensureBrushes(cx, cy, maxR)

        // ── 扫线推进：**匀速**（treble 只改余辉时长，不改转速）──
        val prev = sweepAngle
        sweepAngle = wrapTau(sweepAngle + SWEEP_SPEED * dtSec)

        // ── 目标池仿真（衰减 / 漂移 / 穿越 / 重生）──
        if (!poolInitialized) {
            initPool(pool, nextRandom, frame.spectrum.size)
            poolInitialized = true
        }
        // treble 缩短余辉：holdSec = 1.15 × 周期 ÷ (1 + treble×0.8)
        val holdSec = (TAU / SWEEP_SPEED) * HOLD_FACTOR / (1f + trebleSmooth * 0.8f)
        stepTargets(pool, prev, sweepAngle, dtSec, holdSec, frame.energy, frame.spectrum, nextRandom)

        // ── §A9 第 1 条：中心绿光（最底层）──
        val glow = centerGlow
        if (glow != null) {
            drawCircle(brush = glow, radius = maxR, center = Offset(cx, cy), alpha = 0.10f)
        }

        // ── 同心圆 × 5（§A9 第 1 条：alpha 按半径 0.28 → 0.14；最外圈由 energy 提亮）──
        var i = 1
        while (i <= RINGS) {
            val rr = r * i / RINGS
            val frac = i.toFloat() / RINGS
            val ringAlpha = (RING_A_INNER - (RING_A_INNER - RING_A_EDGE) * frac) +
                if (i == RINGS) frame.energy * 0.22f else 0f
            drawCircle(
                gridDim, radius = rr, center = Offset(cx, cy),
                style = Stroke(if (i == RINGS) 1.8f else 1f), alpha = ringAlpha
            )
            i++
        }

        // ── 放射线 × 12，扫掠经过的射线短暂亮起 ──
        var k = 0
        while (k < RAYS) {
            val a = k * TAU / RAYS
            var d = sweepAngle - a
            while (d > Math.PI.toFloat()) d -= TAU
            while (d < -Math.PI.toFloat()) d += TAU
            val glowRay = cos(d).coerceIn(0f, 1f).let { g -> g * g }
            val innerR = r * 0.08f
            drawLine(
                gridDim,
                Offset(cx + cos(a) * innerR, cy + sin(a) * innerR),
                Offset(cx + cos(a) * r, cy + sin(a) * r),
                strokeWidth = if (glowRay > 0.3f) 2.2f else 1f,
                alpha = 0.18f + glowRay * 0.7f
            )
            k++
        }

        // ── 扫掠余辉扇：预分配 Brush + withTransform 旋转（零分配、真的跟着转）──
        val brush = sweepBrush
        if (brush != null) {
            withTransform({ rotate(sweepAngle * RAD2DEG, Offset(cx, cy)) }) {
                drawCircle(brush = brush, radius = r, center = Offset(cx, cy), alpha = 0.9f)
            }
        }

        // ── 扫掠前沿线（更亮的主扫线）──
        drawLine(
            gridBright,
            Offset(cx, cy),
            Offset(cx + cos(sweepAngle) * r, cy + sin(sweepAngle) * r),
            strokeWidth = 2.5f, alpha = 0.85f
        )

        // ── 回波：画在**目标自己的方位角**上（磷光余辉 + 距离衰减 = 荧光屏纵深）──
        val active = activeCount(frame.energy)
        var t = 0
        while (t < active) {
            val o = t * STRIDE
            val b = pool[o + BRIGHT]
            if (b > 0.02f) {
                val ang = pool[o + BEARING]
                val range = pool[o + RANGE]
                val rr = r * range
                val peak = pool[o + PEAK]
                val half = BEAM_RAD * (0.7f + peak * 0.6f) * 0.5f
                drawArc(
                    color = gridColor,
                    startAngle = (ang - half) * RAD2DEG,
                    sweepAngle = half * 2f * RAD2DEG,
                    useCenter = false,
                    topLeft = Offset(cx - rr, cy - rr),
                    size = Size(rr * 2f, rr * 2f),
                    style = Stroke(width = 2.5f + peak * 3.5f, cap = StrokeCap.Round),
                    alpha = b * (0.35f + peak * 0.5f) * (1f - 0.35f * range)
                )
            }
            t++
        }

        // ── 中心点 ──
        drawCircle(gridBright, radius = 3.5f, center = Offset(cx, cy), alpha = 0.9f)
    }

    internal companion object {
        const val RADAR_GREEN = 0xFF39D97A.toInt()
        const val RINGS = 5
        const val RAYS = 12
        const val TAU = (2 * Math.PI).toFloat()

        /** 扫线转速（rad/s）**恒定**：`TAU / 1.6` = 3.93 s/圈，真实雷达天线的机械感 */
        const val SWEEP_SPEED = 1.6f

        /** 目标池规模与字段步长 */
        const val TARGET_N = 24
        const val STRIDE = 8
        const val BEARING = 0   // 方位角（rad，[0, TAU)）
        const val RANGE = 1     // 距离比例（[RANGE_MIN, 1]）
        const val DRIFT_B = 2   // 角漂移速率（rad/s，±0.03..0.10 ⇒ 每圈 7–23°）
        const val DRIFT_R = 3   // 距离漂移速率（/s，±0.005..0.02）
        const val BIN = 4       // 绑定的频谱桶（决定回波强度/粗细）
        const val BRIGHT = 5    // 磷光余辉亮度 0..1
        const val PEAK = 6      // 上次被扫到时的频谱值（决定弧宽/线宽）
        const val LIFE = 7      // 剩余寿命（s；到 0 重生）

        const val RAD2DEG = 57.2958f
        const val RANGE_MIN = 0.25f
        /** 余辉系数：`holdSec = HOLD_FACTOR × 周期`（略大于一圈 ⇒ 撑到下一圈刷新） */
        const val HOLD_FACTOR = 1.15f
        /** 波束宽度（rad）≈ 9°：回波短弧的标称弧长 */
        const val BEAM_RAD = 0.157f
        /** §A9 第 1 条：同心圆 alpha 中心 / 边缘 */
        const val RING_A_INNER = 0.28f
        const val RING_A_EDGE = 0.14f

        /** 归一到 `[0, TAU)` */
        internal fun wrapTau(a: Float): Float {
            val m = a % TAU
            return if (m < 0f) m + TAU else m
        }

        /**
         * 扫线本帧从 [prev] 扫到 [cur]（可能跨 0 回绕），是否扫过 [target] 方位。
         * 区间取**左开右闭** `(prev, cur]` —— 同一圈不重复点亮、也不漏点。
         *
         * ⚠️ `dt` 已被基类钳到 0.1 s、转速 1.6 rad/s ⇒ 单帧最多 0.16 rad，`span >= TAU`
         * 实际走不到；但**必须保留这个兜底**（防后人把 [SWEEP_SPEED] 调大），单测覆盖。
         */
        internal fun crossed(prev: Float, cur: Float, target: Float): Boolean {
            val span = cur - prev
            if (span >= TAU) return true          // 一帧扫满一圈 → 全部命中（兜底）
            val d0 = wrapTau(prev)
            val d1 = wrapTau(cur)
            val d = wrapTau(target)
            return if (d1 >= d0) d > d0 && d <= d1 else d > d0 || d <= d1
        }

        /** 活动目标数：安静稀疏、激烈满屏（替代旧「32 段弧全亮」） */
        internal fun activeCount(energy: Float): Int =
            (8 + (energy * 16).toInt()).coerceIn(8, TARGET_N)

        /** 池初始化（每槽固定消耗 8 个随机数 ⇒ 序列可复现，供单测对照） */
        internal fun initPool(pool: FloatArray, nextRandom: () -> Float, bins: Int) {
            var t = 0
            while (t < TARGET_N) {
                respawnInto(pool, t * STRIDE, bins, nextRandom)
                t++
            }
        }

        /**
         * 每帧目标池仿真（零分配）：磷光衰减 → 连续漂移 → 扫线穿越点亮 → 寿命重生。
         * ⛔ 寿命到必须**重生**（换位置），不能置零/移出 —— 那会让画面逐渐空掉（E19）。
         */
        internal fun stepTargets(
            pool: FloatArray,
            prev: Float,
            cur: Float,
            dtSec: Float,
            holdSec: Float,
            energy: Float,
            spectrum: FloatArray,
            nextRandom: () -> Float,
        ) {
            val bins = spectrum.size
            val active = activeCount(energy)
            var t = 0
            while (t < active) {
                val o = t * STRIDE
                // ① 磷光衰减（线性；holdSec 略大于一圈 ⇒ 撑到下一圈）
                pool[o + BRIGHT] = (pool[o + BRIGHT] - dtSec / holdSec).coerceAtLeast(0f)
                // ② 连续漂移 ⇒ 下一圈换位置
                pool[o + BEARING] = wrapTau(pool[o + BEARING] + pool[o + DRIFT_B] * dtSec)
                var range = pool[o + RANGE] + pool[o + DRIFT_R] * dtSec
                if (range < RANGE_MIN) range = RANGE_MIN + (RANGE_MIN - range)   // bounce
                if (range > 1f) range = 1f - (range - 1f)
                pool[o + RANGE] = range
                // ③ 扫线穿越 → 点亮（左开右闭，抗跨 0 回绕）
                if (crossed(prev, cur, pool[o + BEARING])) {
                    pool[o + BRIGHT] = 1f
                    pool[o + PEAK] = spectrum[pool[o + BIN].toInt().coerceIn(0, bins - 1)]
                }
                // ④ 寿命到 → 重生（换一批方位/距离，**不是消失**）
                pool[o + LIFE] -= dtSec
                if (pool[o + LIFE] <= 0f) respawnInto(pool, o, bins, nextRandom)
                t++
            }
        }

        /** 重生一个槽（换位置；与 [initPool] 共用 ⇒ 每槽固定消耗 8 个随机数） */
        private fun respawnInto(pool: FloatArray, o: Int, bins: Int, nextRandom: () -> Float) {
            pool[o + BEARING] = nextRandom() * TAU
            pool[o + RANGE] = RANGE_MIN + nextRandom() * (1f - RANGE_MIN)
            pool[o + DRIFT_B] = (0.03f + nextRandom() * 0.07f) * if (nextRandom() < 0.5f) 1f else -1f
            pool[o + DRIFT_R] = (0.005f + nextRandom() * 0.015f) * if (nextRandom() < 0.5f) 1f else -1f
            pool[o + BIN] = (nextRandom() * bins).toInt().coerceIn(0, bins - 1).toFloat()
            pool[o + BRIGHT] = 0f
            pool[o + PEAK] = 0f
            pool[o + LIFE] = 6f + nextRandom() * 14f
        }
    }
}

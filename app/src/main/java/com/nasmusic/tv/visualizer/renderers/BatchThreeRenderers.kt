package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.SpectrumContract
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.fx.FxLevel
import com.nasmusic.tv.visualizer.fx.ProceduralTexture
import com.nasmusic.tv.visualizer.fx.Shading2D
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
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

/**
 * E31 `ORIGAMI_POLY` — 折纸 · 低多边形
 *
 * 视觉：大面积纯色三角形拼贴（低多边形风格），**折痕受光 + 折叠接触阴影**营造"纸"的立体感。
 * 低音触发几何体"翻折"（三角形绕一条边旋转的 2D 投影），高频触发冷色↔暖色渐变切换。
 *
 * 翻折实现：三角形第三顶点绕**铰边**（由 `triFoldDir` 决定）做 cos 投影——`cos > 0` 是正面（亮面）、
 * `cos < 0` 是背面（暗面），过零瞬间交换明暗 → 立体折纸感成立；折到 90°（`foldK = 0`）时侧面对光
 * ⇒ 再按 [EDGE_ON_DARKEN] 额外压暗 ⇒ 翻折过程有"转过去"的连续感（不再是二值跳变）。
 *
 * §A10 质感五条（本轮新增）：
 * 1. **折痕高光**：每个三角沿**铰边**（折叠时不动的那条边）画 1 条 [CREASE_WHITEN] 提亮的细线
 *    （宽 [CREASE_WIDTH] / alpha [CREASE_ALPHA]）—— 纸的折边受光。
 *    ⛔ 全部合批进**单条** [creasePath]（1 次 `drawPath`），**不是** `triCount` 次 `drawLine`。
 * 2. **接触阴影**：**折叠中**（`fold > 0` 且 `|foldK| < ` [SHADOW_BAND]）的三角，把同形三角沿
 *    **光向反方向**偏移 [SHADOW_DROP] px 画一层 [SHADOW_DARKEN] 压暗的（alpha [SHADOW_ALPHA]）
 *    ⇒ 翻起的纸片在**下面的纸上**留下投影。⛔ 同样合批进单条 [shadowPath]。
 *    绘制顺序 = 填充**之后**（投影落在相邻三角上才看得见"抬起"）。
 * 3. **纸张纹理**：末尾叠 1 层 `ProceduralTexture` 的 `Id.PAPER`（1 次 `drawImage`）
 *    —— 整屏从"塑料色块"变"纸"。
 * 4. **折痕渐变**：⛔ **按 §A10 的降级方案执行** —— 不做逐三角 `linearGradient`（那要每帧新建
 *    `triCount` 个 `Brush`，违反零分配红线，§四 G15）；改为**明暗面按 `Shading2D.lambert(法线)` 动态算**
 *    （原实现是二值 `baseL` / `baseL × 0.62f`；现在是沿「铰边中点 → 自由顶点」方向的连续明暗，
 *    均值与原实现对齐）。
 * 5. **后处理**：`postFx = PostFx(vignette = 0.42f, grain = 0.030f)`；pulse 全屏微光由 `0.06`
 *    降到 [PULSE_ALPHA]（避免峰值过曝，§四 G8 同族问题）。
 *
 * 性能红线：`drawContent` 内零分配。网格结构 + 3 条 `Path` + `Stroke` + 阴影偏移全在
 * `onEnterContent` / 构造期预分配；明暗只多一次 `atan2` + `lambert`（纯计算，无分配）。
 */
class OrigamiPolyRenderer : RendererFx() {

    override val theme = VisualizerTheme.ORIGAMI_POLY

    /** §A10 第 5 条：胶片后处理（暗角 0.42 = §13.5-D9 的下限） */
    override val postFx = PostFx(vignette = 0.42f, grain = 0.030f)

    private companion object {
        const val TAU = (2 * Math.PI).toFloat()
        /** 冷色基相（HSL hue） */
        const val HUE_COLD = 210f
        /** 暖色基相 */
        const val HUE_WARM = 25f
        const val FOLD_TRIGGER = 0.50f
        /** 折叠动画时长（秒） */
        const val FOLD_DUR = 0.9f
        /** 弧度 → 角度（`Shading2D.lambert` 收角度制） */
        const val RAD2DEG = 57.2958f

        // ── §A10-1 折痕高光 ──
        const val CREASE_WIDTH = 1.2f
        const val CREASE_ALPHA = 0.16f
        const val CREASE_WHITEN = 0.55f

        // ── §A10-2 接触阴影 ──
        /** 沿**光向反方向**的偏移量（px）；方向由 `Shading2D.LIGHT_ANGLE_DEG` 唯一推导 */
        const val SHADOW_DROP = 4f
        const val SHADOW_ALPHA = 0.22f
        const val SHADOW_DARKEN = 0.35f
        /** `|foldK| < SHADOW_BAND` 视为"折叠中"（§A10 原文 0.9） */
        const val SHADOW_BAND = 0.9f

        // ── §A10-3 纸张纹理 ──
        /**
         * 纸纹叠加 alpha。⚠️ **不是** §A10 原文的 `0.10` —— 见 §12.4：
         * `ProceduralTexture.paperRow` 自身写出的 alpha 上限只有 `30/255 ≈ 0.118`，
         * 再乘 `0.10` 后**有效值仅 ≈ 0.012**（≈ 3/255 灰阶）⇒ 肉眼几乎不可见，
         * 与验收项「整屏有纸纹质感（放大可见细微纹理）」冲突。取 `0.28` ⇒ 有效 ≈ 0.033。
         */
        const val PAPER_ALPHA = 0.28f

        // ── §A10-4 明暗面（降级方案：lambert 连续明暗）──
        /** 正面：`baseL × (0.80 + 0.40 × lit)` ⇒ 0.80–1.20，均值 1.00（与原二值实现同量级） */
        const val FRONT_BASE = 0.80f
        const val FRONT_LIT = 0.40f
        /** 背面：`baseL × (0.44 + 0.26 × lit)` ⇒ 0.44–0.70，均值 ≈ 0.57（原实现 `× 0.62`） */
        const val BACK_BASE = 0.44f
        const val BACK_LIT = 0.26f
        /** 折到 90°（`foldK = 0`）时侧面对光 ⇒ 再压暗这个比例 */
        const val EDGE_ON_DARKEN = 0.35f

        /** 填充/折痕/投影共用的基准明度与饱和度（= 原实现的 `0.42f * lightness` / `0.38f`） */
        const val MID_LIGHT = 0.42f
        const val SAT = 0.38f

        /** §A10-5 pulse 全屏微光（原 `0.06` ⇒ 峰值不再把整屏提亮） */
        const val PULSE_ALPHA = 0.035f
    }

    /** 三角形网格（`onEnterContent` 预生成；坐标相对 0..1） */
    private var triX = FloatArray(0)   // 每三角 3 顶点 x
    private var triY = FloatArray(0)
    private var triCount = 0
    private var triBaseL = FloatArray(0)   // 基础亮度 0.82..1.12（拼贴感）
    private var triFold = FloatArray(0)    // 折叠相位 0=无，>0 动画进行中
    private var triFoldDir = IntArray(0)   // 折叠方向（决定铰边 / 自由顶点）

    private var bassSmooth = 0f
    private var trebleSmooth = 0f
    private var huePos = 0f                // 冷暖插值 0..1（0=全冷 1=全暖）
    private var beatFlip = false           // 低音触发翻折的节流标志

    private val pathBuf = Path()           // 填充（逐三角 drawPath）
    /** §A10-1 折痕高光合批（⛔ 1 次 `drawPath`，不是 `triCount` 次 `drawLine`） */
    private val creasePath = Path()
    /** §A10-2 接触阴影合批 */
    private val shadowPath = Path()
    private val creaseStroke = Stroke(CREASE_WIDTH)

    /**
     * 阴影偏移（沿**光向反方向**）。⛔ 方向必须由 `Shading2D.LIGHT_ANGLE_DEG` 唯一推导，
     * 渲染器内**不得**另写光源角（§四 G4）；构造期算一次，`Offset` 是 value class ⇒ 零分配。
     */
    private val shadowOff = Offset(
        -Shading2D.lightDir.x * SHADOW_DROP,
        -Shading2D.lightDir.y * SHADOW_DROP
    )

    override fun onEnterContent(ctx: RenderContext) {
        bassSmooth = 0f
        trebleSmooth = 0f
        huePos = 0f
        beatFlip = false

        // ── 画质分档：LOW 4×2 / MEDIUM 6×3 / HIGH 8×4 网格 → 三角数 = cols*rows*2 ──
        val (cols, rows) = when (ctx.quality) {
            VisualQuality.LOW -> 4 to 2
            VisualQuality.MEDIUM -> 6 to 3
            VisualQuality.HIGH -> 8 to 4
        }
        val n = cols * rows * 2
        triX = FloatArray(n * 3)
        triY = FloatArray(n * 3)
        triBaseL = FloatArray(n)
        triFold = FloatArray(n)
        triFoldDir = IntArray(n)
        triCount = n

        var rng = 0x5EED1234u
        fun nextRand(): Float {
            rng = rng * 1664525u + 1013904223u
            return (rng shr 8).toFloat() / 16777216f
        }

        // 规则网格 + 轻微顶点抖动 → 低多边形拼贴感
        var t = 0
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val x0 = col.toFloat() / cols
                val x1 = (col + 1f) / cols
                val y0 = row.toFloat() / rows
                val y1 = (row + 1f) / rows
                // 每格两三角：↙↗ 对角线
                val jx0 = nextRand() * 0.06f - 0.03f
                val jx1 = nextRand() * 0.06f - 0.03f
                val jy0 = nextRand() * 0.06f - 0.03f
                val jy1 = nextRand() * 0.06f - 0.03f
                // 三角 A：(x0,y0)-(x1,y0)-(x0,y1)
                setTri(t, x0 + jx0, y0 + jy0, x1 + jx1, y0 + jy1, x0 + jx0, y1 + jy1)
                triBaseL[t] = 0.82f + nextRand() * 0.30f
                triFold[t] = 0f
                triFoldDir[t] = (nextRand() * 3f).toInt().coerceIn(0, 2)
                t++
                // 三角 B：(x1,y0)-(x1,y1)-(x0,y1)
                setTri(t, x1 + jx1, y0 + jy1, x1 + jx1, y1 + jy1, x0 + jx0, y1 + jy1)
                triBaseL[t] = 0.82f + nextRand() * 0.30f
                triFold[t] = 0f
                triFoldDir[t] = (nextRand() * 3f).toInt().coerceIn(0, 2)
                t++
            }
        }
    }

    private fun setTri(t: Int, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) {
        triX[t * 3] = ax; triY[t * 3] = ay
        triX[t * 3 + 1] = bx; triY[t * 3 + 1] = by
        triX[t * 3 + 2] = cx; triY[t * 3 + 2] = cy
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        // ⛔ `dt` 只能来自基类 `FxFrame`（§四 G13 重复 ⑥：`ctx.nowMs` 三个调用点语义不一致）
        val dtSec = fx.dt

        bassSmooth += (frame.bass - bassSmooth) * 0.15f
        trebleSmooth += (frame.treble - trebleSmooth) * 0.20f

        // ── 冷暖渐变：treble 缓慢推动 huePos（0=冷 1=暖）──
        huePos += (frame.treble - 0.35f) * dtSec * 0.5f
        huePos = huePos.coerceIn(0f, 1f)

        // ── 低音触发翻折：bass 超阈值且不在动画中 → 随机挑几个三角开始折 ──
        if (frame.bass > FOLD_TRIGGER) {
            if (!beatFlip) {
                beatFlip = true
                var started = 0
                var i = 0
                while (i < triCount && started < 3) {
                    // 用帧序号做确定性挑选（零分配）
                    if (triFold[i] <= 0f && ((frame.seq + i) % 7L) == 0L) {
                        triFold[i] = FOLD_DUR
                        started++
                    }
                    i++
                }
            }
        } else {
            beatFlip = false
        }

        val accent = ctx.palette.accent
        val hue = lerpHue(HUE_COLD, HUE_WARM, huePos)
        // §A10-1/2 的合批颜色：每帧算一次 —— 折痕/投影**各自单色**，才能合批成单条 Path
        val midColor = Color.hsl(hue, SAT, MID_LIGHT)
        val creaseColor = VisualizerMath.towardWhite(midColor, CREASE_WHITEN)
        val shadowColor = VisualizerMath.darken(midColor, SHADOW_DARKEN)
        val offX = shadowOff.x
        val offY = shadowOff.y

        creasePath.reset()
        shadowPath.reset()

        var i = 0
        while (i < triCount) {
            // ── 折叠动画推进 ──
            val fold = triFold[i]
            val foldK = if (fold > 0f) {
                triFold[i] = fold - dtSec
                // 归一化进度 0..1，cos 投影：1→-1 扫过一次
                cos((1f - fold / FOLD_DUR) * Math.PI.toFloat())
            } else 1f

            val dir = triFoldDir[i]
            var ax = triX[i * 3] * w
            var ay = triY[i * 3] * h
            var bx = triX[i * 3 + 1] * w
            var by = triY[i * 3 + 1] * h
            var cxp = triX[i * 3 + 2] * w
            var cyp = triY[i * 3 + 2] * h

            // 铰边中点（`Offset` 是 value class ⇒ 零分配）+ 自由顶点（**未投影**的像素坐标
            // ⇒ 法线不随折叠抖动，且 aspect 正确）
            val mid = when (dir) {
                0 -> Offset((ax + bx) * 0.5f, (ay + by) * 0.5f)
                1 -> Offset((bx + cxp) * 0.5f, (by + cyp) * 0.5f)
                else -> Offset((ax + cxp) * 0.5f, (ay + cyp) * 0.5f)
            }
            val freeX = when (dir) {
                0 -> cxp
                1 -> ax
                else -> bx
            }
            val freeY = when (dir) {
                0 -> cyp
                1 -> ay
                else -> by
            }

            // 翻折投影：自由顶点朝铰边中点收缩（dir 0 = 绕 AB / 1 = 绕 BC / 2 = 绕 AC）
            when (dir) {
                0 -> {
                    cxp = mid.x + (cxp - mid.x) * foldK
                    cyp = mid.y + (cyp - mid.y) * foldK
                }
                1 -> {
                    ax = mid.x + (ax - mid.x) * foldK
                    ay = mid.y + (ay - mid.y) * foldK
                }
                else -> {
                    bx = mid.x + (bx - mid.x) * foldK
                    by = mid.y + (by - mid.y) * foldK
                }
            }

            // ── §A10-4 明暗面（降级方案）：沿「铰边中点 → 自由顶点」的连续 lambert ──
            val lit = Shading2D.lambert(atan2(freeY - mid.y, freeX - mid.x) * RAD2DEG)
            val facing = foldK >= 0f
            val shade = if (facing) FRONT_BASE + FRONT_LIT * lit else BACK_BASE + BACK_LIT * lit
            // 折到 90°（foldK = 0）时侧面对光 ⇒ 额外压暗
            val edgeOn = 1f - abs(foldK)
            val lightness = triBaseL[i] * shade * (1f - EDGE_ON_DARKEN * edgeOn)

            val light = (MID_LIGHT * lightness).coerceIn(0.16f, 0.62f)
            val color = Color.hsl(hue, SAT, light)

            pathBuf.reset()
            pathBuf.moveTo(ax, ay)
            pathBuf.lineTo(bx, by)
            pathBuf.lineTo(cxp, cyp)
            pathBuf.close()
            drawPath(pathBuf, color)

            // ── §A10-1 折痕高光：沿**铰边**（折叠时不动的那条边）──
            when (dir) {
                0 -> {
                    creasePath.moveTo(ax, ay); creasePath.lineTo(bx, by)
                }
                1 -> {
                    creasePath.moveTo(bx, by); creasePath.lineTo(cxp, cyp)
                }
                else -> {
                    creasePath.moveTo(ax, ay); creasePath.lineTo(cxp, cyp)
                }
            }

            // ── §A10-2 接触阴影：仅"折叠中"，同形三角沿光向反方向偏移 ──
            if (fold > 0f && abs(foldK) < SHADOW_BAND) {
                shadowPath.moveTo(ax + offX, ay + offY)
                shadowPath.lineTo(bx + offX, by + offY)
                shadowPath.lineTo(cxp + offX, cyp + offY)
                shadowPath.close()
            }
            i++
        }

        // ── §A10-2 接触阴影（⛔ 在填充**之后**：投影落在相邻三角上才看得见"抬起"）──
        drawPath(shadowPath, shadowColor, alpha = SHADOW_ALPHA)
        // ── §A10-1 折痕高光（合批 1 次 drawPath）──
        drawPath(creasePath, creaseColor, alpha = CREASE_ALPHA, style = creaseStroke)

        // ── §A10-3 纸张纹理：整屏从"塑料色块"变"纸"（1 次 drawImage）──
        val iw = w.toInt().coerceIn(1, 4096)
        val ih = h.toInt().coerceIn(1, 4096)
        ProceduralTexture.ensure(iw, ih)
        ProceduralTexture.tile(ProceduralTexture.Id.PAPER)?.let {
            drawImage(it, dstSize = IntSize(iw, ih), alpha = PAPER_ALPHA)
        }

        // ── §A10-5 低音节拍微光（accent 叠加，全屏极弱脉冲；已由 0.06 降到 0.035 防过曝）──
        if (frame.pulse > 0.02f) {
            drawRect(
                accent, topLeft = Offset.Zero, size = size,
                alpha = frame.pulse * PULSE_ALPHA, blendMode = BlendMode.Plus
            )
        }
    }

    private fun lerpHue(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

/**
 * E32 `STAIRCASE_WAVE` — 阶梯 · 阶梯方波
 *
 * 视觉：屏幕边缘一列列垂直堆叠的发光方块，像数字音频方波 / 极简建筑立面。
 * 极度硬朗克制。
 *
 * 律动：低音让方块瞬间向上拉伸（**刻意不做缓动**——硬跳变正是方波美学的灵魂，
 * 与其他频谱效果的平滑曲线形成反差）；高频让方块**崩开**成 2×2 碎块
 * （确定性位移 + 整数度旋转）。
 *
 * **§A11 第 0 条 · 全柱独立频段映射（P0）**：`cols` 列**顺序**（全宽展开，不镜像 ——
 * §十三 裁决项 7）覆盖**全部 64 个频谱桶**，每列一个**互不重叠的桶区间**并取**区间均值**；
 * 边界表按**感知（对数，`PERCEPT_K = 1.6`）**在 `onEnterContent` 建一次（draw 内只读）。
 * ⛔ 旧实现是 `src = i / half * (n / 2)`：① 只映射**前 32 个桶**（中高频完全不可见）；
 * ② 左右**严格镜像**且多列共用同一桶（命中不同桶只有列数的**一半**）；
 * ③ `STEPS = 16` 吃掉小动态（中高频恒 1 格矮柱）。三条叠加 = 用户报的
 * 「只有几个柱在动」——⛔ 这不是幅度问题，**改增益治不好**。
 *
 * **量化与亮度**：`STEPS = 24`；静音门槛 = [SpectrumContract.MIN_AMPLITUDE]
 * （有信号的列**至少 1 格**正常亮度，只有真静音才画 `alpha 0.10` 轮廓格）；
 * 块亮度按**绝对格位**归一（`(st + 1) / STEPS`）—— 旧公式 `(st + 1) / steps`
 * 的分母随列高变化 ⇒ 同一格位在不同高度下 alpha 抖动（**视觉闪烁**）。
 *
 * **性能红线**：draw 内零分配。三条合批 Path（接缝 / 外发光 / 碎块×档位）与
 * 边界表 `IntArray(cols + 1)` 全部**预分配**，仅 `reset()` 与写入。
 */
class StaircaseWaveRenderer : RendererFx() {

    override val theme = VisualizerTheme.STAIRCASE_WAVE

    /** §A11 第 5 条：暗角 + 颗粒 + 轻扫描线（"数字 / 建筑"气质，扫描线比心跳更轻） */
    override val postFx = PostFx(vignette = 0.46f, grain = 0.030f, scanline = 0.10f)

    internal companion object {
        /** 列数：LOW 20 / MED 28 / HIGH 36 */
        const val COLS_LOW = 20
        const val COLS_MED = 28
        const val COLS_HIGH = 36

        /** §A11 0.5：垂直量化档数 `16 → 24`（每档 `1/24 ≈ 0.0417`） */
        const val STEPS = 24f

        /** §A11 0.3：感知（对数）分桶指数；`1.0` = 线性，`> 1` 让低频占更多列 */
        const val PERCEPT_K = 1.6

        /** 方块间隙比例（水平 / 垂直**各算各的**，见 §A11 0.7） */
        const val CELL_GAP = 0.18f

        /** 碎裂触发阈值（treble） */
        const val SHATTER_T = 0.45f

        /** 真静音列的轮廓格 alpha（有信号的列一律 ≥ 1 格正常亮度） */
        const val SILENT_ALPHA = 0.10f

        // ── §A11-1 圆角 + 接缝 ──
        /** 圆角半径（px） */
        const val CORNER_R = 1.5f
        /** 接缝暗线宽度（px） */
        const val SEAM_W = 1.5f
        const val SEAM_ALPHA = 0.20f
        /** 接缝暗线的暗化系数（由 `accent` 派生 ⇒ 保持配色一致） */
        const val SEAM_DARKEN = 0.80f

        // ── §A11-2 外发光 ──
        /** 向外扩（px） */
        const val GLOW_EXPAND = 2f
        const val GLOW_ALPHA = 0.12f

        // ── §A11-3 碎裂位移 + 旋转 ──
        /**
         * 确定性位移表（px）。索引 = `(seq + col + st × 2 + qy × 2 + qx) and 3`
         * ⇒ 4 个碎块朝 4 个不同方向崩开，且逐帧变化。
         */
        val DISP_X = floatArrayOf(-2.5f, 2.5f, -1.0f, 1.5f)
        val DISP_Y = floatArrayOf(2.5f, -2.5f, 1.5f, -1.0f)

        /** 旋转幅度：**整数度**，`(seq % 9 - 4) × 8` ⇒ ±32°（≤ §A11 的 0.6 rad = ±34.4°） */
        const val ROT_STEP_DEG = 8f
        const val ROT_HALF = 4
        const val RAD_PER_DEG = 0.017453292f

        /** 碎块尺寸占比（相对半个方块） */
        const val SHARD_FILL = 0.84f

        /**
         * 碎块合批档数（按格位量化）。⛔ 若逐碎块用 `blockAlpha(st)`，每个碎块的 alpha
         * 都不同 ⇒ **无法合批**（最坏 `4 × 2 × cols = 288` 次 `drawPath`）。
         * 量化到 4 档后，全部碎块只需 **≤ 4 次** `drawPath`（见 §12.4）。
         */
        const val SHARD_TIERS = 4

        /**
         * 背景径向渐变的**缓存盐**（§四 G4「缓存键维度必须 ⊇ 依赖维度」/ §12.4）。
         *
         * ⛔ 为什么必须有：`Shading2D` 是 **object** ⇒ `shadeBrushCached` 的键空间是
         * **进程级共享**的。E03 隧道 / E07 山峦 / E13 液态网格 / E15 涟漪都用
         * `(w, h, accent)` 三元键（E15 早已自带盐），本效果若照抄同一公式就会**撞键**、
         * 拿到别人的 `Brush`（它们的 `base` 是 `darken(accent, 0.55f)`，本效果是 `0.62f`
         * ⇒ 背景会明显偏亮）。**每个调用点必须有自己的盐**，`logs_temp/s17_brushkey.py`
         * 会扫描全仓把撞键照出来。
         */
        const val E32_KEY_SALT = 0x3A3A3A3AL

        /**
         * 信号值 → 量化格数。**小信号可见性**（§A11 0.5）：`v < MIN_AMPLITUDE` 才算真静音
         * （0 格）；有信号的列**至少 1 格**。
         */
        internal fun stepsOf(v: Float): Int =
            if (v < SpectrumContract.MIN_AMPLITUDE) 0
            else (v * STEPS).toInt().coerceIn(1, STEPS.toInt())

        /**
         * 格位亮度（§A11 0.6）：按**绝对格位**归一 —— 分母是常量 [STEPS] 而**不是**当前列高
         * `steps`，同一格位亮度恒定。
         *
         * @param steps ⛔ **故意不使用**（保留形参是为了让门禁能用「同一 `st`、不同 `steps`」
         *   三次调用断言返回值相同 ⇒ 直接证明公式与列高无关）
         */
        @Suppress("UNUSED_PARAMETER")
        internal fun blockAlpha(st: Int, steps: Int): Float {
            val level = (st + 1f) / STEPS
            return (0.22f + level * 0.72f).coerceAtMost(0.95f)
        }

        /** 碎块档位（按绝对格位量化 ⇒ 可合批） */
        internal fun shardTier(st: Int): Int =
            (st * SHARD_TIERS / STEPS.toInt()).coerceIn(0, SHARD_TIERS - 1)

        /** 碎块档位 alpha（取档位上沿，与 [blockAlpha] 同量级） */
        internal fun shardAlpha(tier: Int): Float =
            (0.22f + (tier + 1f) / SHARD_TIERS * 0.72f).coerceAtMost(0.95f)

        /**
         * 感知（对数）分桶边界表（§A11 0.2 / 0.3）—— **纯函数**，门禁可直接调用（§八 G10）。
         *
         * 保证：① `b[0] == 0`；② **严格单调递增**（每列至少 1 个桶）；
         * ③ `b[cols] == BAR_COUNT`（覆盖全频段）；④ `Σ(b[i+1] − b[i]) == BAR_COUNT`。
         *
         * @param k 分桶指数。默认 [PERCEPT_K]（感知划分）；传 `1.0` 即**线性划分** ——
         *   门禁据此把"感知生效"做成**可判定的对照**（低频占列数必须严格多于线性），
         *   而不是写死一个阈值。⛔ 不要让测试自己复制一份算法（会漂移）。
         */
        internal fun buildBands(cols: Int, k: Double = PERCEPT_K): IntArray {
            val n = SpectrumContract.BAR_COUNT
            val b = IntArray(cols + 1)
            var prev = 0
            for (i in 1 until cols) {
                var x = (n * (i.toDouble() / cols).pow(k)).toInt()
                if (x <= prev) x = prev + 1                       // 每列至少 1 桶
                if (x > n - (cols - i)) x = n - (cols - i)         // 给后面每列各留 1 桶（防御）
                b[i] = x
                prev = x
            }
            b[cols] = n
            return b
        }
    }

    /** §A11 0.2 频段边界表（`onEnterContent` 建一次；draw 内只读 ⇒ 零分配） */
    private var band = IntArray(0)
    private var bandCols = 0

    /** §A11-1 接缝暗线：每列 1 段 ⇒ 合批成 **1 次** `drawPath` */
    private val seamPath = Path()

    /** §A11-2 外发光：全部方块合批成 **1 次** `drawPath` */
    private val glowPath = Path()

    /** §A11-3 碎块：按档位合批 ⇒ 最多 [SHARD_TIERS] 次 `drawPath` */
    private val shardPaths = Array(SHARD_TIERS) { Path() }

    /**
     * §A11-1 圆角。`CornerRadius` 是 Compose 的 `@JvmInline value class`（内部只包一个 Long）
     * ⇒ 构造**不进堆**；这里再提到构造期算一次，draw 内只是读字段。
     */
    private val corner = CornerRadius(CORNER_R)

    override fun onEnterContent(ctx: RenderContext) {
        rebuildBands(colsOf(ctx.quality))
    }

    private fun colsOf(q: VisualQuality): Int = when (q) {
        VisualQuality.LOW -> COLS_LOW
        VisualQuality.MEDIUM -> COLS_MED
        VisualQuality.HIGH -> COLS_HIGH
    }

    /** 唯一的建表点（`onEnterContent` 与"画质中途变化"防御共用） */
    private fun rebuildBands(cols: Int) {
        band = buildBands(cols)
        bandCols = cols
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val accent = ctx.palette.accent
        val cols = colsOf(ctx.quality)
        if (bandCols != cols) rebuildBands(cols)

        val cellW = w / cols
        val gapX = cellW * CELL_GAP
        val blockW = cellW - gapX
        val cellH = h * 0.75f / STEPS      // 单元格高度（占 75% 屏高）
        val gapY = cellH * CELL_GAP
        val blockH = cellH - gapY
        val baseY = h                      // 从底边起算

        // ── §A11 第 4 条 · 三段式背景（G9）：① 径向纵深 ② 程序化纹理 tile ③ 星野 tile ──
        // 与 E13 / E15 同范式：背景三段**不随 FxLevel 关闭**（它承担"不再浮在纯黑上"），
        // 按档位关的是 vignette / grain / scanline（见 postFx）。
        val iw = w.toInt().coerceIn(1, 4096)
        val ih = h.toInt().coerceIn(1, 4096)
        ProceduralTexture.ensure(iw, ih)
        drawRect(
            brush = Shading2D.shadeBrushCached(
                key = (w.toRawBits().toLong() shl 32) xor h.toRawBits().toLong() xor
                    accent.toArgb().toLong() xor E32_KEY_SALT,
                center = Offset(w / 2f, h * 0.42f),
                radius = maxOf(w, h) * 0.62f,
                base = VisualizerMath.darken(accent, 0.62f),
                contrast = 0.10f
            )
        )
        // ② PAPER（哑光"混凝土"面）而非 GRAIN / SCANLINE —— 后两者已被 postFx 占用
        ProceduralTexture.tile(ProceduralTexture.Id.PAPER)?.let {
            drawImage(it, dstSize = IntSize(iw, ih), alpha = 0.10f)
        }
        // ③ 星野远景微粒
        ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)?.let {
            drawImage(it, dstSize = IntSize(iw, ih), alpha = 0.16f)
        }

        val s = frame.spectrum
        val shatter = frame.treble > SHATTER_T
        val showGlow = fx.level != FxLevel.OFF

        // §A11-3 碎裂旋转角：按帧序号量化到**整数度**（±32° ≤ 0.6 rad）；每帧 2 次三角函数
        val rotRad = ((frame.seq % 9L).toInt() - ROT_HALF) * ROT_STEP_DEG * RAD_PER_DEG
        val cosR = cos(rotRad)
        val sinR = sin(rotRad)

        seamPath.reset()
        glowPath.reset()
        var t = 0
        while (t < SHARD_TIERS) {
            shardPaths[t].reset()
            t++
        }

        var i = 0
        while (i < cols) {
            // ── §A11 0.1 / 0.2 / 0.4：全宽展开 + 区间均值（列 i 覆盖桶 [band[i], band[i+1])）──
            val lo = band[i]
            val hi = band[i + 1]
            var sum = 0f
            var b = lo
            while (b < hi) {
                sum += s[b]
                b++
            }
            val v = sum / (hi - lo)
            val x = i * cellW + gapX * 0.5f
            val steps = stepsOf(v)

            if (steps <= 0) {
                // 真静音列：只画 1 个轮廓格，保持"建筑立面"轮廓
                drawBlock(x, baseY - cellH, blockW, blockH, accent, SILENT_ALPHA, showGlow)
                addAxisRect(seamPath, x, baseY - cellH, x + SEAM_W, baseY)
            } else {
                var st = 0
                while (st < steps) {
                    val yTop = baseY - (st + 1) * cellH
                    if (shatter && st >= steps - 2) {
                        // §A11-3 顶部 2 块崩开：确定性位移 + 整数度旋转（按档位合批）
                        addAxisRect(
                            glowPath,
                            x - GLOW_EXPAND, yTop - GLOW_EXPAND,
                            x + blockW + GLOW_EXPAND, yTop + blockH + GLOW_EXPAND
                        )
                        addShards(shardPaths[shardTier(st)], x, yTop, blockW, blockH, cosR, sinR, i, st, frame.seq)
                    } else {
                        // §A11 0.6：亮度按**绝对格位**归一（分母是常量 STEPS）
                        drawBlock(x, yTop, blockW, blockH, accent, blockAlpha(st, steps), showGlow)
                    }
                    st++
                }
                // 接缝高度 = 整列高度（同一列内所有方块的左边缘共线 ⇒ 1 段即可）
                addAxisRect(seamPath, x, baseY - steps * cellH, x + SEAM_W, baseY)
            }
            i++
        }

        // ── §A11 第 2 条 · 外发光（全部方块合批 1 次；仅在 FxLevel != OFF 时画）──
        if (showGlow) drawPath(glowPath, accent, alpha = GLOW_ALPHA)

        // ── §A11 第 3 条 · 碎块（按档位合批 ⇒ ≤ SHARD_TIERS 次；未碎裂时整段跳过）──
        if (shatter) {
            t = 0
            while (t < SHARD_TIERS) {
                drawPath(shardPaths[t], accent, alpha = shardAlpha(t))
                t++
            }
        }

        // ── §A11 第 1 条 · 接缝暗线（1 次 drawPath；⛔ 在方块**之后**才看得见）──
        drawPath(seamPath, VisualizerMath.darken(accent, SEAM_DARKEN), alpha = SEAM_ALPHA)
    }

    /**
     * 画一个方块：圆角矩形（§A11 第 1 条）并把它的外发光矩形攒进 [glowPath]。
     *
     * ⚠️ 外发光用**直角**而非圆角：圆角要 `RoundRect`（`data class`，**每块一次堆分配**
     * ⇒ 违反零分配红线）；外扩仅 [GLOW_EXPAND] px 且 alpha 只有 [GLOW_ALPHA]，
     * 圆角差肉眼不可辨。攒进同一条 Path 还顺带保证相邻发光**不会叠加两次 alpha**
     * （同一条 Path 只填一次）。
     */
    private fun DrawScope.drawBlock(
        x: Float, yTop: Float, blockW: Float, blockH: Float,
        color: Color, alpha: Float, showGlow: Boolean
    ) {
        if (showGlow) {
            addAxisRect(
                glowPath,
                x - GLOW_EXPAND, yTop - GLOW_EXPAND,
                x + blockW + GLOW_EXPAND, yTop + blockH + GLOW_EXPAND
            )
        }
        drawRoundRect(
            color = color,
            topLeft = Offset(x, yTop),
            size = Size(blockW, blockH),
            cornerRadius = corner,
            alpha = alpha
        )
    }

    /**
     * §A11 第 3 条 · 碎裂：把 1 个方块拆成 2×2 四块，每块按**确定性位移**
     * （`(seq + col + st × 2 + qy × 2 + qx) and 3` 查 [DISP_X] / [DISP_Y]）
     * 与**整数度旋转**（`cosR` / `sinR`）崩开，四个角点**手工算**后写进 [path]。
     *
     * ⛔ 不用 `withTransform`：它虽然不分配对象（§15.4-A7），但这里手工算 4 个角点
     * 更直接，且省掉一次 `save()` / `restore()`。`Offset` 是 value class ⇒ 零分配。
     */
    private fun addShards(
        path: Path, x: Float, y: Float, w: Float, h: Float,
        cosR: Float, sinR: Float, col: Int, st: Int, seq: Long
    ) {
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val qw = halfW * SHARD_FILL
        val qh = halfH * SHARD_FILL
        val insetX = (halfW - qw) * 0.5f
        val insetY = (halfH - qh) * 0.5f
        var qy = 0
        while (qy < 2) {
            var qx = 0
            while (qx < 2) {
                val idx = ((seq + col + st * 2 + qy * 2 + qx) and 3L).toInt()
                val l = x + qx * halfW + insetX + DISP_X[idx]
                val tp = y + qy * halfH + insetY + DISP_Y[idx]
                addRotRect(path, l, tp, qw, qh, l + qw * 0.5f, tp + qh * 0.5f, cosR, sinR)
                qx++
            }
            qy++
        }
    }

    /**
     * 把**轴对齐矩形**写进 [path]（4 个角点手工写，**零分配**）。
     *
     * ⛔ 为什么不用 `path.addRect(l, t, r, b)`：**Compose 的 `Path` 根本没有这个重载**。
     * 实测 `ui-graphics-android-1.6.1-sources.jar`：`graphics/Path.kt` 与
     * `AndroidPath.android.kt` 都**只**声明 `addRect(rect: Rect)`（`addOval` / `addRoundRect`
     * 同理只收 `Rect` / `RoundRect`），全源码也没有同名的顶层扩展函数 ⇒ 传 4 个 float
     * 报 `Too many arguments for 'fun addRect(rect: Rect)'`。而 `Rect(...)` 又违反
     * §四 G15 / §八 G13 的零分配红线（T1.6.2 已把全仓带参 `Rect(` 清零）
     * ⇒ **唯一合规写法就是手工 `moveTo` / `lineTo` / `close`**。
     *
     * ⚠️ 只有 `android.graphics.Path`（经 `asAndroidPath()` 取到）才有
     * `addRect(l, t, r, b, dir)` / `addOval(l, t, r, b)` 这类 float 重载 —— 别把两者搞混。
     */
    private fun addAxisRect(path: Path, l: Float, t: Float, r: Float, b: Float) {
        path.moveTo(l, t)
        path.lineTo(r, t)
        path.lineTo(r, b)
        path.lineTo(l, b)
        path.close()
    }

    /** 把一个矩形绕 `(cx, cy)` 旋转后写进 [path]（4 个角点手工算，零分配） */
    private fun addRotRect(
        path: Path, l: Float, t: Float, w: Float, h: Float,
        cx: Float, cy: Float, cosR: Float, sinR: Float
    ) {
        val r = l + w
        val b = t + h
        var px = l - cx
        var py = t - cy
        path.moveTo(cx + px * cosR - py * sinR, cy + px * sinR + py * cosR)
        px = r - cx
        py = t - cy
        path.lineTo(cx + px * cosR - py * sinR, cy + px * sinR + py * cosR)
        px = r - cx
        py = b - cy
        path.lineTo(cx + px * cosR - py * sinR, cy + px * sinR + py * cosR)
        px = l - cx
        py = b - cy
        path.lineTo(cx + px * cosR - py * sinR, cy + px * sinR + py * cosR)
        path.close()
    }
}

package com.nasmusic.tv.visualizer.renderers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.CoverPalette
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * E25 `HYPNOTIC_FUNCTION` — 催眠 · 数学函数图像动画
 *
 * 四态状态机：缓慢描线（8.0s）→ 静止凝视（3.0s）→ 溃散蒸发（2.4s）→ 空场（0.5s）→ 随机换函数。
 * 图形内容来自数学定义（[FunctionLibrary] 预采样），[AudioFrame] 仅驱动氛围参数；
 * **不读 songId**——切歌/暂停/静音不影响状态机推进（§7.4）。
 *
 * 布局：绘图区左移（中心 0.40w、宽 0.66w），右侧 18% 屏宽为公式带；
 * 公式经 [FormulaLayout] 预布局为真数学样式（嵌套上标/分式/根号），
 * 溃散期按 run 碎散，与曲线共用同一时间轴（§11.4）。
 *
 * 性能红线：draw 内零分配——pts/screen/jitter/vanishAt/particleBuf/Paint/Path
 * 均为成员；公式 run 与刻度标签在采样期一次性生成。
 */
class HypnoticFunctionRenderer(
    private val seed: Long = System.nanoTime()   // 生产用默认；测试注入固定值断言确定性
) : VisualizerRenderer {

    override val theme = VisualizerTheme.HYPNOTIC_FUNCTION

    companion object {
        const val DRAW_MS = 8000f
        const val HOLD_MS = 3000f
        const val DISSOLVE_MS = 2400f
        const val GAP_MS = 500f

        /** 布局常量（§8.1）：绘图区中心 / 宽 / 公式带宽，间隙 9% 不可压缩 */
        const val PLOT_CX_F = 0.40f
        const val PLOT_W_F = 0.66f
        const val PLOT_H_F = 0.56f      // 相对 minDim
        const val LABEL_BAND_F = 0.18f

        /** y 方向绘制系数：plotH 的 88%（上下各 12% 留白，§4.2） */
        private const val Y_SPAN = 0.44f

        /** 状态迁移纯函数（§13.1 可测）：返回下一态，null = 保持。 */
        internal fun nextPhase(phase: Phase, elapsedMs: Long, drawDone: Boolean): Phase? = when (phase) {
            Phase.DRAW -> if (drawDone || elapsedMs > DRAW_MS * 3f) Phase.HOLD else null
            Phase.HOLD -> if (elapsedMs >= HOLD_MS) Phase.DISSOLVE else null
            Phase.DISSOLVE -> if (elapsedMs >= DISSOLVE_MS) Phase.GAP else null
            Phase.GAP -> if (elapsedMs >= GAP_MS) Phase.DRAW else null
        }

        /** 曲线点蒸发阈值（§6.1：0.35~1.0，上限 1.0 = 部分点随整体 alpha 淡出撑住轮廓） */
        internal fun curveVanishThreshold(rng: Random): Float = 0.35f + rng.nextFloat() * 0.65f

        /** 公式 run 蒸发阈值（§11.4：0.30~0.85，>0.87 永不触发、等效纯淡出） */
        internal fun runVanishThreshold(rng: Random): Float = 0.30f + rng.nextFloat() * 0.55f

        /** 公式 run 溃散 alpha（§11.4：比曲线快 15%） */
        internal fun runAlpha(p: Float): Float = (1f - p * 1.15f).coerceAtLeast(0f) * 224f
    }

    internal enum class Phase { DRAW, HOLD, DISSOLVE, GAP }

    // ── 采样与绘制缓冲 ──
    private var n = 0
    private var pts = FloatArray(0)          // 归一化 n*2
    private var screen = FloatArray(0)       // 屏幕坐标 n*2
    private val segs = IntArray(32)
    private var segCount = 0
    private val axis = FloatArray(2)         // 数学 x=0 / y=0 的归一化位置
    private val domain = FloatArray(4)       // xMin, xMax, yMin, yMax（刻度生成用）
    private var jitterPhase = FloatArray(0)
    private var vanishAt = FloatArray(0)
    private var particleBuf = FloatArray(0)  // HIGH 档粒子 n*6（x,y,vx,vy,life,-）
    private var particlesOn = false

    // ── 随机调度 ──
    private val rng = Random(seed)           // 构造期一次，永不重取（§7.3）
    private var order = IntArray(0)
    private var orderPtr = 0

    /** 当前函数下标（internal 只读：供调度测试断言） */
    internal var currentIdx = -1
        private set

    private var def: FunctionDef? = null

    // ── 公式与刻度（采样期一次性生成）──
    private var formula: FormulaLayout.Result? = null
    private var runVanishAt = FloatArray(0)
    private var runJitter = FloatArray(0)
    private var tickX = FloatArray(0)
    private var tickXLabel = emptyArray<String>()
    private var tickY = FloatArray(0)
    private var tickYLabel = emptyArray<String>()

    // ── 状态机 ──
    private var phase = Phase.DRAW
    private var phaseStartMs = 0L
    private var drawAccumulator = 0f
    private var lastW = 0f
    private var lastH = 0f
    private var needsSample = true
    private var needsRemap = true
    private var particleCount = 0
    private var dissolveP = 0f

    // 刻度标签锚点（主轴屏幕位置；NaN = 主轴不可见，不画标签）
    private var labelAnchorX = Float.NaN
    private var labelAnchorY = Float.NaN

    // ── 复用绘制对象 ──
    private val curvePath = Path()
    private val gridPath = Path()
    private val axisPath = Path()
    private val tickPath = Path()
    private val glowStroke = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val mainStroke = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val labelPaint = Paint().apply {
        isAntiAlias = true
        textSize = 34f
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.LEFT
        alpha = 224
    }
    private val decorPaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 2f
        color = 0xFFFFFFFF.toInt()
    }
    private val tickPaint = Paint().apply {
        isAntiAlias = true
        textSize = 22f
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

    // 色彩缓存（palette 变化才重算，避免每帧 Triple 分配）
    private var cachedAccentArgb = 0
    private var mainColor = Color(0xFF7DE2FF)
    private var glowColor = Color(0xFF7DE2FF)

    // ── 生命周期 ────────────────────────────────────────────────

    override fun onEnter(ctx: RenderContext) {
        phase = Phase.DRAW
        phaseStartMs = 0L
        drawAccumulator = 0f
        dissolveP = 0f
        needsSample = true
        // 洗牌序列：构造期即建，切歌不重置（§7.1/§7.3）
        order = IntArray(FunctionLibrary.ALL.size) { it }
        FunctionLibrary.weightedShuffle(rng, order, -1)
        orderPtr = 0
        pickNext()
    }

    override fun onExit() {
        particlesOn = false
        formula = null
    }

    /** 取下一个函数（周期走完重洗，§7.1） */
    private fun pickNext() {
        if (orderPtr >= order.size) {
            val lastIdx = order[order.size - 1]
            FunctionLibrary.weightedShuffle(rng, order, lastIdx)
            orderPtr = 0
        }
        currentIdx = order[orderPtr++]
        def = FunctionLibrary.ALL[currentIdx]
        needsSample = true
    }

    /** 缓冲容量（画质变化由 RendererSwapper 重建渲染器，这里只做防御） */
    private fun ensureBuffers(quality: VisualQuality) {
        val need = when (quality) {
            VisualQuality.HIGH -> 240
            VisualQuality.MEDIUM -> 180
            else -> 120
        }
        if (n != need) {
            n = need
            pts = FloatArray(n * 2)
            screen = FloatArray(n * 2)
            jitterPhase = FloatArray(n)
            vanishAt = FloatArray(n)
            particleBuf = FloatArray(n * 6)
            needsSample = true
        }
    }

    /** 数学采样（归一化 + 段表 + 轴位置 + 定义域）。与画布尺寸无关 */
    private fun resample() {
        val d = def ?: return
        segCount = FunctionLibrary.sample(d, n, pts, segs, axis, domain)
        for (k in 0 until n) jitterPhase[k] = rng.nextFloat() * 2f * PI.toFloat()
        particlesOn = false
        needsSample = false
        needsRemap = true
    }

    /** 屏幕映射 + 公式布局 + 刻度生成（画布尺寸变化时执行，采样期一次） */
    private fun remap(w: Float, h: Float, safeArea: Float) {
        val d = def ?: return
        val plotW = w * PLOT_W_F
        val plotH = minOf(w, h) * PLOT_H_F
        val ox = w * PLOT_CX_F
        val oy = h / 2f
        for (k in 0 until n) {
            screen[k * 2] = ox + (pts[k * 2] - 0.5f) * plotW
            screen[k * 2 + 1] = oy - pts[k * 2 + 1] * plotH * Y_SPAN
        }
        // 公式带：右缘钳制到 ≥0.82w（§11.3）
        val rightX = (w - safeArea - 32f).coerceAtLeast(w * 0.82f)
        formula = FormulaLayout.layout(
            d.label, rightX, h / 2f, w * LABEL_BAND_F * 0.86f, 34f
        ) { text, size ->
            labelPaint.textSize = size
            labelPaint.measureText(text)
        }
        val r = formula!!
        runVanishAt = FloatArray(r.runCount)
        runJitter = FloatArray(r.runCount)
        buildTicks(w, h, plotW, plotH, ox, oy)
        lastW = w; lastH = h
    }

    /** 刻度生成（§4.4）：π 刻度 / 整数刻度 / 参数极坐标无标签 */
    private fun buildTicks(w: Float, h: Float, plotW: Float, plotH: Float, ox: Float, oy: Float) {
        val d = def ?: return
        val xMin = domain[0]; val xMax = domain[1]
        val yMin = domain[2]; val yMax = domain[3]
        val xRange = xMax - xMin
        val yRange = yMax - yMin

        val piT = PI.toFloat()
        val usePi = d.space == CurveSpace.CARTESIAN &&
            isPiMultiple(xMin) && isPiMultiple(xMax) && abs(xMax) < 12f * piT

        val xs = ArrayList<Float>()
        val xl = ArrayList<String>()
        val ys = ArrayList<Float>()
        val yl = ArrayList<String>()

        if (d.space != CurveSpace.CARTESIAN) {
            // 参数 / 极坐标：只画网格与十字轴，不打数字
            tickX = FloatArray(0); tickXLabel = emptyArray()
            tickY = FloatArray(0); tickYLabel = emptyArray()
            return
        }

        // x 轴刻度
        if (usePi) {
            val kMin = kotlin.math.ceil(xMin / piT - 0.01f).toInt()
            val kMax = kotlin.math.floor(xMax / piT + 0.01f).toInt()
            var k = kMin
            while (k <= kMax && xs.size < 6) {
                val tx = (k * piT - xMin) / xRange
                xs.add(ox + (tx - 0.5f) * plotW)
                xl.add(
                    when (k) {
                        0 -> "0"
                        1 -> "π"
                        -1 -> "-π"
                        else -> "${k}π"
                    }
                )
                k++
            }
        } else {
            val step = niceStep(xRange / 4f)
            var v = kotlin.math.ceil(xMin / step).toInt()
            val vMax = kotlin.math.floor(xMax / step).toInt()
            while (v <= vMax && xs.size < 6) {
                val tx = (v * step - xMin) / xRange
                xs.add(ox + (tx - 0.5f) * plotW)
                xl.add(if (step >= 1f) "${v}" else format1(v * step))
                v++
            }
        }

        // y 轴刻度（恒为整数语义）
        val stepY = niceStep(yRange / 4f)
        var vy = kotlin.math.ceil(yMin / stepY).toInt()
        val vyMax = kotlin.math.floor(yMax / stepY).toInt()
        while (vy <= vyMax && ys.size < 6) {
            val ty = ((vy * stepY - yMin) / yRange) * 2f - 1f
            ys.add(oy - ty * plotH * Y_SPAN)
            yl.add(if (stepY >= 1f) "$vy" else format1(vy * stepY))
            vy++
        }

        tickX = xs.toFloatArray(); tickXLabel = xl.toTypedArray()
        tickY = ys.toFloatArray(); tickYLabel = yl.toTypedArray()
    }

    /** 1/2/5×10^k 的"好看"步长 */
    private fun niceStep(raw: Float): Float {
        if (raw <= 0f) return 1f
        val mag = Math.pow(10.0, kotlin.math.floor(kotlin.math.ln(raw.toDouble()) / kotlin.math.ln(10.0))).toFloat()
        val norm = raw / mag
        return when {
            norm >= 5f -> 5f * mag
            norm >= 2f -> 2f * mag
            else -> mag
        }
    }

    /** v 是否近似 π 的整数倍 */
    private fun isPiMultiple(v: Float): Boolean {
        val r = v / PI.toFloat()
        return abs(r - kotlin.math.round(r)) < 0.01f
    }

    private fun format1(v: Float): String {
        val r = (v * 10f).toInt() / 10f
        return if (r == r.toInt().toFloat()) "${r.toInt()}" else "$r"
    }

    // ── 绘制 ────────────────────────────────────────────────────

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return
        ensureBuffers(ctx.quality)

        val now = ctx.nowMs
        if (phaseStartMs == 0L) {
            phaseStartMs = now
            needsSample = true
        }
        if (needsSample) resample()
        if (needsRemap || w != lastW || h != lastH || formula == null) {
            remap(w, h, ctx.safeAreaPx)
            needsRemap = false
        }

        // 色彩（palette 变化才重算）
        refreshColors(ctx)

        val dt = ((now - phaseStartMs) / 1000f).coerceIn(0f, 0.1f)
        val elapsed = (now - phaseStartMs).coerceAtLeast(0L)

        val axisAlpha = if (phase == Phase.GAP) 0.15f else 1f
        drawGridAndAxes(w, h, axisAlpha)
        drawTickLabels()
        drawFormula(frame, ctx, elapsed)

        when (phase) {
            Phase.DRAW -> drawStroke(frame, ctx, w, h, dt)
            Phase.HOLD -> drawStill(frame, ctx, w, h, elapsed)
            Phase.DISSOLVE -> drawDissolve(frame, ctx, w, h, elapsed, dt)
            Phase.GAP -> drawGap()
        }

        advancePhase(now)
    }

    private fun refreshColors(ctx: RenderContext) {
        val accent = ctx.palette.accent
        val argb = accent.toArgb()
        if (argb != cachedAccentArgb) {
            cachedAccentArgb = argb
            val base = if (ctx.palette == CoverPalette.Fallback) Color(0xFF7DE2FF) else accent
            mainColor = VisualizerMath.neonize(base)
            glowColor = mainColor
        }
    }

    // ── DRAW：描线（§8.4）──
    private fun DrawScope.drawStroke(frame: AudioFrame, ctx: RenderContext, w: Float, h: Float, dt: Float) {
        if (segCount == 0) return
        val speed = 1f + frame.pulse * 0.15f
        drawAccumulator += dt * 1000f * speed
        val progress = (drawAccumulator / DRAW_MS).coerceIn(0f, 1f)
        val idx = (progress * (n - 1)).toInt().coerceIn(0, n - 1)
        val frac = progress * (n - 1) - idx

        curvePath.reset()
        var headX = Float.NaN
        var headY = Float.NaN
        for (s in 0 until segCount) {
            val packed = segs[s]
            val start = packed / 65536
            val len = packed and 0xFFFF
            if (len <= 0) continue
            val end = start + len - 1
            if (end < start) continue
            if (idx < start) break            // 段按序，后面还没描到
            val upTo = min(end, idx)
            curvePath.moveTo(screen[start * 2], screen[start * 2 + 1])
            for (j in start + 1..upTo) {
                curvePath.lineTo(screen[j * 2], screen[j * 2 + 1])
            }
            // 末端插值（消除步进感，§8.4）
            if (idx in start..end - 1 && frac > 0f) {
                val fx = VisualizerMath.lerp(screen[idx * 2], screen[(idx + 1) * 2], frac)
                val fy = VisualizerMath.lerp(screen[idx * 2 + 1], screen[(idx + 1) * 2 + 1], frac)
                curvePath.lineTo(fx, fy)
                headX = fx; headY = fy
            } else if (idx == end) {
                headX = screen[end * 2]; headY = screen[end * 2 + 1]
            }
        }

        val glowA = 0.16f + frame.energy * 0.14f
        drawPath(curvePath, glowColor, alpha = glowA, style = glowStroke, blendMode = BlendMode.Plus)
        drawPath(curvePath, mainColor, alpha = (0.85f + frame.bass * 0.1f).coerceAtMost(1f), style = mainStroke)

        if (headX.isFinite()) {
            val headR = 10f * (1f + frame.treble * 0.3f)
            drawCircle(mainColor, headR, Offset(headX, headY), blendMode = BlendMode.Plus)
            drawCircle(mainColor, headR * 0.5f, Offset(headX, headY))
        }
    }

    // ── HOLD：整体呼吸 + 辉光正弦（§8.5）──
    private fun DrawScope.drawStill(frame: AudioFrame, ctx: RenderContext, w: Float, h: Float, elapsed: Long) {
        if (segCount == 0) return
        val holdT = (elapsed % 3000L) / 1000f
        val breath = 1f + 0.006f * sin(holdT * 2f * PI.toFloat())
        val ox = w * PLOT_CX_F
        val oy = h / 2f

        curvePath.reset()
        for (s in 0 until segCount) {
            val packed = segs[s]
            val start = packed / 65536
            val len = packed and 0xFFFF
            if (len <= 0) continue
            curvePath.moveTo(
                ox + (screen[start * 2] - ox) * breath,
                oy + (screen[start * 2 + 1] - oy) * breath
            )
            for (j in start + 1 until start + len) {
                curvePath.lineTo(
                    ox + (screen[j * 2] - ox) * breath,
                    oy + (screen[j * 2 + 1] - oy) * breath
                )
            }
        }

        val energyGlow = 0.16f + frame.energy * 0.14f
        val glowA = energyGlow * (0.7f + 0.3f * (0.5f + 0.5f * sin(holdT * 2f * PI.toFloat())))
        drawPath(curvePath, glowColor, alpha = glowA, style = glowStroke, blendMode = BlendMode.Plus)
        drawPath(curvePath, mainColor, style = mainStroke)
    }

    // ── DISSOLVE：双档溃散（§6）──
    private fun DrawScope.drawDissolve(frame: AudioFrame, ctx: RenderContext, w: Float, h: Float, elapsed: Long, dt: Float) {
        if (segCount == 0) return
        val d = (elapsed / DISSOLVE_MS).coerceIn(0f, 1f)
        dissolveP = d * d     // 平方缓动

        if (ctx.quality == VisualQuality.HIGH) {
            drawDissolveParticles(frame, dt)
        } else {
            drawDissolvePoints()
        }
    }

    /** 方案 A · 抖动蒸发（LOW/MED，§6.1）。只画段内有效点 */
    private fun DrawScope.drawDissolvePoints() {
        val p = dissolveP
        val step = if (n <= 120) 2 else 1   // LOW 点数减半（§14）
        for (s in 0 until segCount) {
            val packed = segs[s]
            val start = packed / 65536
            val len = packed and 0xFFFF
            var k = start
            val end = start + len
            while (k < end) {
                if (p <= vanishAt[k]) {
                    val ox = sin(p * 18f + jitterPhase[k]) * 26f * p
                    val oy = cos(p * 23f + jitterPhase[k]) * 26f * p
                    val a = ((1f - p) * 0.95f).coerceIn(0f, 1f)
                    drawCircle(
                        mainColor, 3f,
                        Offset(screen[k * 2] + ox, screen[k * 2 + 1] + oy),
                        alpha = a, blendMode = BlendMode.Plus
                    )
                }
                k += step
            }
        }
    }

    /** 方案 B · 粒子化坍缩（HIGH，§6.2） */
    private fun DrawScope.drawDissolveParticles(frame: AudioFrame, dt: Float) {
        val p = dissolveP
        if (!particlesOn) initParticles()
        if (frame.beat) {
            // 节拍脉冲：一次性向外加速（§9）
            var i = 0
            while (i < particleCount * 6) {
                particleBuf[i + 2] *= 1.35f
                particleBuf[i + 3] *= 1.35f
                i += 6
            }
        }
        val gravity = if (p >= 0.45f) 320f else 0f
        var i = 0
        var k = 0
        while (k < particleCount) {
            val vx = particleBuf[i + 2]
            val vy = particleBuf[i + 3]
            val life = particleBuf[i + 4] - dt * 1000f
            particleBuf[i + 4] = life
            if (life > 0f && p <= vanishAt[particlePoint[k]]) {
                particleBuf[i + 3] = vy + gravity * dt
                particleBuf[i] += vx * dt
                particleBuf[i + 1] += (vy + gravity * dt) * dt
                val a = ((1f - p) * 0.95f).coerceIn(0f, 1f)
                drawCircle(
                    mainColor, 3f,
                    Offset(particleBuf[i], particleBuf[i + 1]),
                    alpha = a, blendMode = BlendMode.Plus
                )
            }
            i += 6
            k++
        }
    }

    /** 粒子宿主点下标（粒子 ↔ vanishAt 对应） */
    private var particlePoint = IntArray(0)

    /** 粒子初始化：沿切线方向 ± 扰动（HOLD→DISSOLVE 时一次）。只收段内有效点 */
    private fun initParticles() {
        if (particlePoint.size < n) particlePoint = IntArray(n)
        var cnt = 0
        var i = 0
        for (s in 0 until segCount) {
            val packed = segs[s]
            val start = packed / 65536
            val len = packed and 0xFFFF
            for (k in start until start + len) {
                particleBuf[i] = screen[k * 2]
                particleBuf[i + 1] = screen[k * 2 + 1]
                val j = min(k + 1, start + len - 1)
                val jp = maxOf(k - 1, start)
                var tx = screen[j * 2] - screen[jp * 2]
                var ty = screen[j * 2 + 1] - screen[jp * 2 + 1]
                val mag = kotlin.math.sqrt(tx * tx + ty * ty)
                if (mag > 0.001f) {
                    tx = tx / mag * 60f; ty = ty / mag * 60f
                }
                particleBuf[i + 2] = tx + (rng.nextFloat() - 0.5f) * 80f
                particleBuf[i + 3] = ty + (rng.nextFloat() - 0.5f) * 80f - 30f
                particleBuf[i + 4] = DISSOLVE_MS * (0.7f + rng.nextFloat() * 0.3f)
                particleBuf[i + 5] = 0f
                particlePoint[cnt] = k
                cnt++
                i += 6
                if (cnt >= n) break
            }
            if (cnt >= n) break
        }
        particleCount = cnt
        particlesOn = true
    }

    /** 溃散状态初始化（HOLD→DISSOLVE 迁移时一次） */
    private fun initDissolve() {
        dissolveP = 0f
        particlesOn = false
        for (k in 0 until n) vanishAt[k] = curveVanishThreshold(rng)
        val f = formula
        if (f != null) {
            for (k in 0 until f.runCount) {
                runVanishAt[k] = runVanishThreshold(rng)
                runJitter[k] = rng.nextFloat() * 2f * PI.toFloat()
            }
        }
    }

    // ── GAP：空场（曲线不画，轴降 alpha）──
    private fun drawGap() {
        // 曲线层无内容；坐标轴已按 GAP alpha 绘制
    }

    // ── 坐标轴 / 网格 / 刻度（图层 1-3 / 8）──
    private fun DrawScope.drawGridAndAxes(w: Float, h: Float, alphaScale: Float) {
        val plotW = w * PLOT_W_F
        val plotH = minOf(w, h) * PLOT_H_F
        val ox = w * PLOT_CX_F
        val oy = h / 2f
        val left = ox - plotW / 2f
        val right = ox + plotW / 2f
        val top = oy - plotH / 2f
        val bottom = oy + plotH / 2f
        val axisColor = Color(0xFF8FB3D9)

        // 网格 5×5
        gridPath.reset()
        for (g in 1..4) {
            val gx = left + plotW * g / 5f
            gridPath.moveTo(gx, top); gridPath.lineTo(gx, bottom)
            val gy = top + plotH * g / 5f
            gridPath.moveTo(left, gy); gridPath.lineTo(right, gy)
        }
        drawPath(gridPath, axisColor.copy(alpha = 0.14f * alphaScale), style = mainStroke)

        // 主轴：数学 x=0 / y=0（归一化边界 ±1.05 内可见——y=0 恰在自动范围边缘时
        // ty 可达 ±1.0001，绘制时钳制到绘图区）
        axisPath.reset()
        val tx0 = axis[0]
        val ty0 = axis[1]
        labelAnchorX = Float.NaN
        labelAnchorY = Float.NaN
        if (tx0 in -0.05f..1.05f) {
            val x0 = (ox + (tx0 - 0.5f) * plotW).coerceIn(left, right)
            labelAnchorX = x0
            axisPath.moveTo(x0, top); axisPath.lineTo(x0, bottom)
            axisPath.moveTo(x0, top); axisPath.lineTo(x0 - 6f, top + 12f)
            axisPath.moveTo(x0, top); axisPath.lineTo(x0 + 6f, top + 12f)
        }
        if (ty0 in -1.05f..1.05f) {
            val y0 = (oy - ty0 * plotH * Y_SPAN).coerceIn(top, bottom)
            labelAnchorY = y0
            axisPath.moveTo(left, y0); axisPath.lineTo(right, y0)
            axisPath.moveTo(right, y0); axisPath.lineTo(right - 12f, y0 - 6f)
            axisPath.moveTo(right, y0); axisPath.lineTo(right - 12f, y0 + 6f)
        }
        drawPath(axisPath, axisColor.copy(alpha = 0.55f * alphaScale), style = mainStroke)

        // 刻度短线（沿主轴）
        tickPath.reset()
        val tyA = axis[1]
        if (tyA in -1.05f..1.05f) {
            val y0 = oy - tyA * plotH * Y_SPAN
            for (i in tickX.indices) {
                tickPath.moveTo(tickX[i], y0 - 6f); tickPath.lineTo(tickX[i], y0 + 6f)
            }
        }
        val txA = axis[0]
        if (txA in -0.05f..1.05f) {
            val x0 = ox + (txA - 0.5f) * plotW
            for (i in tickY.indices) {
                tickPath.moveTo(x0 - 6f, tickY[i]); tickPath.lineTo(x0 + 6f, tickY[i])
            }
        }
        drawPath(tickPath, axisColor.copy(alpha = 0.4f * alphaScale), style = mainStroke)
    }

    private fun DrawScope.drawTickLabels() {
        if (phase == Phase.GAP) return
        val nc = drawContext.canvas.nativeCanvas
        // x 轴标签：沿 x 轴下方居中
        if (labelAnchorY.isFinite()) {
            tickPaint.textAlign = Paint.Align.CENTER
            tickPaint.alpha = (255 * 0.6f).toInt()
            for (i in tickXLabel.indices) {
                nc.drawText(tickXLabel[i], tickX[i], labelAnchorY + 22f, tickPaint)
            }
        }
        // y 轴标签：沿 y 轴左侧右对齐
        if (labelAnchorX.isFinite()) {
            tickPaint.textAlign = Paint.Align.RIGHT
            tickPaint.alpha = (255 * 0.6f).toInt()
            for (i in tickYLabel.indices) {
                nc.drawText(tickYLabel[i], labelAnchorX - 10f, tickY[i] + 8f, tickPaint)
            }
        }
    }

    // ── 公式渲染（图层 9，§11）──
    private fun DrawScope.drawFormula(frame: AudioFrame, ctx: RenderContext, elapsed: Long) {
        val f = formula ?: return
        if (phase == Phase.GAP) return
        val nc = drawContext.canvas.nativeCanvas

        when (phase) {
            Phase.DRAW -> {
                val progress = (drawAccumulator / DRAW_MS).coerceIn(0f, 1f)
                val fadeSpan = 300f / DRAW_MS
                for (k in 0 until f.runCount) {
                    val appear = k.toFloat() / f.runCount
                    if (progress < appear) continue
                    val a = ((progress - appear) / fadeSpan).coerceIn(0f, 1f)
                    labelPaint.alpha = (224 * a).toInt()
                    labelPaint.textSize = f.runSize[k]
                    nc.drawText(f.runText[k], f.runX[k], f.runY[k], labelPaint)
                }
                // 装饰线：整体在后半程淡入（粒度无需逐 run 同步）
                val decorA = ((progress - 0.5f) / fadeSpan).coerceIn(0f, 1f) * 224f
                if (decorA > 0f) drawDecor(f, nc, decorA, 0f, 0f)
            }
            Phase.HOLD -> {
                val holdT = (elapsed % 3000L) / 1000f
                val a = (0.82f + 0.13f * (0.5f + 0.5f * sin(holdT * 2f * PI.toFloat())))  // 0.82↔0.95
                for (k in 0 until f.runCount) {
                    labelPaint.alpha = (255 * a).toInt()
                    labelPaint.textSize = f.runSize[k]
                    nc.drawText(f.runText[k], f.runX[k], f.runY[k], labelPaint)
                }
                drawDecor(f, nc, 255f * a, 0f, 0f)
            }
            Phase.DISSOLVE -> {
                val p = dissolveP
                for (k in 0 until f.runCount) {
                    if (p > runVanishAt[k]) continue
                    val ox = sin(p * 19f + runJitter[k]) * 22f * p
                    val supDrift = if (f.runLevel[k] > 0) p * 8f else 0f   // 上标飞更高（§11.4）
                    val oy = cos(p * 21f + runJitter[k]) * 14f * p + supDrift
                    val a = (1f - p * 1.15f).coerceAtLeast(0f) * 224f
                    if (a <= 0f) continue
                    labelPaint.alpha = a.toInt()
                    labelPaint.textSize = f.runSize[k]
                    nc.drawText(f.runText[k], f.runX[k] + ox, f.runY[k] + oy, labelPaint)
                }
                drawDecor(f, nc, (224f * (1f - p * 1.15f).coerceAtLeast(0f)), p, 1f)
            }
            Phase.GAP -> Unit
        }
    }

    /** 装饰线（分式线/根号线）：跟随宿主 run，[p] > 0 时按宿主蒸发阈值消失 */
    private fun drawDecor(f: FormulaLayout.Result, nc: android.graphics.Canvas, alpha: Float, p: Float, dissolve: Float) {
        if (f.decorCount == 0) return
        decorPaint.alpha = alpha.toInt().coerceIn(0, 255)
        for (j in 0 until f.decorCount) {
            val host = f.decorHost[j]
            if (dissolve > 0f && p > runVanishAt[host]) continue
            var ox = 0f
            var oy = 0f
            if (dissolve > 0f) {
                ox = sin(p * 19f + runJitter[host]) * 22f * p
                oy = cos(p * 21f + runJitter[host]) * 14f * p
            }
            nc.drawLine(
                f.decor[j * 4] + ox, f.decor[j * 4 + 1] + oy,
                f.decor[j * 4 + 2] + ox, f.decor[j * 4 + 3] + oy,
                decorPaint
            )
        }
    }

    // ── 状态迁移（§5.2 / §5.3）──
    private fun advancePhase(now: Long) {
        val elapsed = now - phaseStartMs
        val next = nextPhase(phase, elapsed, drawAccumulator >= DRAW_MS)
        if (next == null) return
        if (phase == Phase.DRAW && elapsed > DRAW_MS * 3f) {
            drawAccumulator = DRAW_MS   // 超时补满：保证完整图像再进 HOLD（§5.2）
        }
        if (next == Phase.DISSOLVE) initDissolve()
        if (next == Phase.DRAW) {
            pickNext()
            // 新周期描线累加器必须归零：残留值会让下一张图第一帧就"描线完成"直接出全图
            drawAccumulator = 0f
        }
        enter(next, now)
    }

    private fun enter(p: Phase, now: Long) {
        phase = p
        phaseStartMs = now
    }
}

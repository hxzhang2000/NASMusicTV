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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.CoverPalette
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.fx.ProceduralTexture
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
 *
 * ## §B8 质感升级（T4.8）
 * - **① 网格三级明度**：主刻度（主轴 x=0 / y=0）`AXIS_ALPHA = 0.55f` →
 *   次刻度（沿主轴的刻度短线）`TICK_ALPHA = 0.30f` → 细网格（5×5 内部线）
 *   `GRID_ALPHA = 0.14f`；线宽同步分档（2.6 / 1.8 / 1.2 px）——§B8 的问题描述是
 *   "同色**单线**"，只分 alpha 仍是同宽，故一并分档（见 §12.4）。
 * - **② 曲线"双层 + 法线明暗"**：辉光（10px `Plus`）→ 主线（`MAIN_STROKE_W = 2.2f`，
 *   `MAIN_ALPHA_BASE = 0.9f`）→ **上方偏移 `HIGHLIGHT_OFFSET = 0.8f` px 的高光线**
 *   （`HIGHLIGHT_STROKE_W = 0.9f`，色 `towardWhite(mainColor, HIGHLIGHT_WHITE_MIX = 0.6f)`）
 *   ⇒ 曲线有**受光侧**，不再是一条平色线。
 * - **③ 方格纸底纹**：叠 1 层 `ProceduralTexture.Id.PAPER`（`PAPER_ALPHA = 0.10f`，
 *   1 次 `drawImage`）—— 与"数学函数"气质匹配。
 * - **④ 后处理**：`postFx = PostFx(vignette = 0.44f, grain = 0.028f)`。
 *
 * ## ⛔ 帧率绑定修复（§B8 未列，但与前六批同族）
 * 旧实现的 `dt` 取自 **相位内累计时间**（`(now - phaseStartMs) / 1000f`，钳 0.1s），
 * 既不是帧间差、又被钳到 0.1s ⇒ 描线累加器每帧 `+min(相位已过毫秒, 100)`，
 * **实际描线时长 ≈ 1.4s**（KDoc 声明 8.0s，差约 5.7×）且**随帧率变化**。
 * 迁移后改为基类 `fx.dt`（帧间差、已钳 100ms、首帧为 0）⇒ 描线恢复 8.0s 且帧率无关。
 *
 * ## ⛔ 随机源为什么不复用基类的 `rng`（§12.4 偏差）
 * `FunctionLibrary.weightedShuffle` 的形参是 `kotlin.random.Random`（内部用 `nextInt`），
 * 而基类给的是 `VisualizerRandom`（LCG，**无 `nextInt`**）；且 `HypnoticScheduleTest`
 * 依赖 `seed` 构造参数注入的确定性 ⇒ 保留一个**改名**的 `shuffleRng`（不与基类 `rng` 撞名）。
 */
class HypnoticFunctionRenderer(
    private val seed: Long = System.nanoTime()   // 生产用默认；测试注入固定值断言确定性
) : RendererFx() {

    override val theme = VisualizerTheme.HYPNOTIC_FUNCTION

    /** §B8-④ 收尾后处理（暗角 + 颗粒）。`FxLevel.OFF` 档位下整段零开销 */
    override val postFx = PostFx(vignette = 0.44f, grain = 0.028f)

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

        // ── §B8 质感常量（T4.8）────────────────────────────────────

        /** §B8-① 三级明度：主刻度（主轴）/ 次刻度（刻度短线）/ 细网格（5×5 内部线） */
        const val AXIS_ALPHA = 0.55f
        const val TICK_ALPHA = 0.30f
        const val GRID_ALPHA = 0.14f

        /** §B8-① 线宽同步分档（§B8 只给 alpha；问题描述是"同色单线"⇒ 一并分宽，见 §12.4） */
        const val AXIS_STROKE_W = 2.6f
        const val TICK_STROKE_W = 1.8f
        const val GRID_STROKE_W = 1.2f

        /** §B8-② 曲线：主线（有厚度）+ 上方高光线（受光侧） */
        const val MAIN_STROKE_W = 2.2f
        const val HIGHLIGHT_STROKE_W = 0.9f
        const val MAIN_ALPHA_BASE = 0.9f
        const val HIGHLIGHT_ALPHA_BASE = 0.82f
        const val HIGHLIGHT_WHITE_MIX = 0.6f

        /** §B8-② 高光线相对主线的**上移量**（px）——"受光侧"就来自这一条 */
        const val HIGHLIGHT_OFFSET = 0.8f

        /** §B8-③ 方格纸底纹不透明度 */
        const val PAPER_ALPHA = 0.10f

        /**
         * 描线累加（§B8 dt 化）。⛔ **纯函数** ⇒ 门禁直调它验证"帧率无关"，
         * 不必在测试里复制算法（复制必然漂移）。
         *
         * @param accumulatorMs 已累计毫秒
         * @param dtSec         帧间秒（来自 `fx.dt`，基类已算差并钳 100ms）
         * @param speed         音频驱动的速度系数
         */
        internal fun advanceStroke(accumulatorMs: Float, dtSec: Float, speed: Float): Float =
            accumulatorMs + dtSec * 1000f * speed

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
    // ⛔ 不用基类的 `rng`（VisualizerRandom，无 nextInt）—— weightedShuffle 的形参是
    //    kotlin.random.Random，且 HypnoticScheduleTest 靠 `seed` 注入确定性（见 §12.4）。
    private val shuffleRng = Random(seed)    // 构造期一次，永不重取（§7.3）
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

    /** §B8-② 主线：有厚度的"绳" */
    private val mainStroke = Stroke(width = MAIN_STROKE_W, cap = StrokeCap.Round, join = StrokeJoin.Round)

    /** §B8-② 法线高光线（上移 `HIGHLIGHT_OFFSET` px ⇒ 曲线有受光侧） */
    private val highlightStroke = Stroke(width = HIGHLIGHT_STROKE_W, cap = StrokeCap.Round, join = StrokeJoin.Round)

    /** §B8-① 三级明度的线宽档：主轴（最亮最粗）/ 次刻度 / 细网格（最暗最细） */
    private val axisStroke = Stroke(width = AXIS_STROKE_W, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val tickStroke = Stroke(width = TICK_STROKE_W, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val gridStroke = Stroke(width = GRID_STROKE_W, cap = StrokeCap.Round, join = StrokeJoin.Round)
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

    /** §B8-② 高光线色：`towardWhite(mainColor, 0.6f)`，随 palette 变化重算（零每帧分配） */
    private var highlightColor = Color(0xFF7DE2FF)

    // ── 生命周期 ────────────────────────────────────────────────

    protected override fun onEnterContent(ctx: RenderContext) {
        phase = Phase.DRAW
        // ⛔ 哨兵取 -1 而非 0：`AudioFrame.timeMs` 首帧可能恰为 0（`FrameClock` 同一坑）
        phaseStartMs = -1L
        drawAccumulator = 0f
        dissolveP = 0f
        needsSample = true
        // 洗牌序列：构造期即建，切歌不重置（§7.1/§7.3）
        order = IntArray(FunctionLibrary.ALL.size) { it }
        FunctionLibrary.weightedShuffle(shuffleRng, order, -1)
        orderPtr = 0
        pickNext()
    }

    protected override fun onExitContent() {
        particlesOn = false
        formula = null
    }

    /** 取下一个函数（周期走完重洗，§7.1） */
    private fun pickNext() {
        if (orderPtr >= order.size) {
            val lastIdx = order[order.size - 1]
            FunctionLibrary.weightedShuffle(shuffleRng, order, lastIdx)
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
        for (k in 0 until n) jitterPhase[k] = shuffleRng.nextFloat() * 2f * PI.toFloat()
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

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return
        ensureBuffers(ctx.quality)

        // ⛔ 时钟只从 `fx.nowMs`（= `frame.timeMs`）取，绝不用 `ctx.nowMs`（§四 G13 重复 ⑥）
        val now = fx.nowMs
        if (phaseStartMs < 0L) {
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

        // §B8-③ 方格纸底纹（1 次 drawImage）。⛔ 不随 FxLevel 关闭 —— 它承担"不再浮在纯黑上"
        val iw = w.toInt().coerceIn(1, 4096)
        val ih = h.toInt().coerceIn(1, 4096)
        // ⛔ 不用 `ensure()`：它一次烘**全部 6 张**全屏纹理（1920×1080 × 6 ≈ 1240 万像素
        //    Kotlin 逐像素 + 6480 次 JNI `setPixels`），且因 `ctx.canvasSize` 在 `onEnter`
        //    时还是 `Size.Zero` 而必然同步落在**首帧** ⇒ 真机实测冷启动首帧黑屏 6369 ms。
        //    本效果**只画 PAPER 一张** ⇒ 降到约 1/6。
        // ⚠️ `ensureTiled()` 不能省：`postFx.grain = 0.028f` 经 `OverlayFx.drawGrain`
        //    读 `tile(Id.GRAIN)`，读不到就静默不画；平铺槽只有它会烘。
        ProceduralTexture.ensureTiled()
        ProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.PAPER, iw, ih)
        ProceduralTexture.tile(ProceduralTexture.Id.PAPER)?.let {
            drawImage(it, dstSize = IntSize(iw, ih), alpha = PAPER_ALPHA)
        }

        val elapsed = (now - phaseStartMs).coerceAtLeast(0L)

        val axisAlpha = if (phase == Phase.GAP) 0.15f else 1f
        drawGridAndAxes(w, h, axisAlpha)
        drawTickLabels()
        drawFormula(frame, ctx, elapsed)

        // ⛔ 帧间 dt 一律取 `fx.dt`（基类已算差 + 钳 100ms + 首帧为 0）。
        //    旧写法 `(now - phaseStartMs) / 1000f` 是**相位内累计时间**（不是帧间差），
        //    且被钳到 0.1s ⇒ 描线实际 ≈ 1.4s 且随帧率变化（见类 KDoc）。
        when (phase) {
            Phase.DRAW -> drawStroke(frame, ctx, w, h, fx.dt)
            Phase.HOLD -> drawStill(frame, ctx, w, h, elapsed)
            Phase.DISSOLVE -> drawDissolve(frame, ctx, w, h, elapsed, fx.dt)
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
            // §B8-② 高光线：向白插值 0.6 —— 只在 palette 变化时算，零每帧分配
            highlightColor = VisualizerMath.towardWhite(mainColor, HIGHLIGHT_WHITE_MIX)
        }
    }

    // ── DRAW：描线（§8.4）──
    private fun DrawScope.drawStroke(frame: AudioFrame, ctx: RenderContext, w: Float, h: Float, dt: Float) {
        if (segCount == 0) return
        val speed = 1f + frame.pulse * 0.15f
        drawAccumulator = advanceStroke(drawAccumulator, dt, speed)
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
                // ⛔ 局部量不叫 `fx` —— 那个名字已被基类帧对象 `FxFrame` 占用（避免误读）
                val lerpX = VisualizerMath.lerp(screen[idx * 2], screen[(idx + 1) * 2], frac)
                val lerpY = VisualizerMath.lerp(screen[idx * 2 + 1], screen[(idx + 1) * 2 + 1], frac)
                curvePath.lineTo(lerpX, lerpY)
                headX = lerpX; headY = lerpY
            } else if (idx == end) {
                headX = screen[end * 2]; headY = screen[end * 2 + 1]
            }
        }

        val glowA = 0.16f + frame.energy * 0.14f
        drawPath(curvePath, glowColor, alpha = glowA, style = glowStroke, blendMode = BlendMode.Plus)
        val mainA = (MAIN_ALPHA_BASE + frame.bass * 0.08f).coerceAtMost(0.98f)
        drawPath(curvePath, mainColor, alpha = mainA, style = mainStroke)
        // §B8-② 法线高光线：整体上移 `HIGHLIGHT_OFFSET` px ⇒ 曲线有**受光侧**。
        // `translate` 是 `withTransform` 的薄封装，**不分配对象**（§15.4-A7，仓库既有用法）
        val hiA = (HIGHLIGHT_ALPHA_BASE + frame.energy * 0.08f).coerceAtMost(0.95f)
        translate(0f, -HIGHLIGHT_OFFSET) {
            drawPath(curvePath, highlightColor, alpha = hiA, style = highlightStroke)
        }

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
        drawPath(curvePath, mainColor, alpha = MAIN_ALPHA_BASE, style = mainStroke)
        // §B8-② HOLD 期同样保留受光侧（与 DRAW 期观感一致）
        translate(0f, -HIGHLIGHT_OFFSET) {
            drawPath(curvePath, highlightColor, alpha = HIGHLIGHT_ALPHA_BASE, style = highlightStroke)
        }
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
                particleBuf[i + 2] = tx + (shuffleRng.nextFloat() - 0.5f) * 80f
                particleBuf[i + 3] = ty + (shuffleRng.nextFloat() - 0.5f) * 80f - 30f
                particleBuf[i + 4] = DISSOLVE_MS * (0.7f + shuffleRng.nextFloat() * 0.3f)
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
        for (k in 0 until n) vanishAt[k] = curveVanishThreshold(shuffleRng)
        val f = formula
        if (f != null) {
            for (k in 0 until f.runCount) {
                runVanishAt[k] = runVanishThreshold(shuffleRng)
                runJitter[k] = shuffleRng.nextFloat() * 2f * PI.toFloat()
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
        // §B8-① 细网格（三级明度最低档：0.14，线宽最细）
        drawPath(gridPath, axisColor.copy(alpha = GRID_ALPHA * alphaScale), style = gridStroke)

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
        // §B8-① 主刻度：主轴 x=0 / y=0（最亮、最粗）
        drawPath(axisPath, axisColor.copy(alpha = AXIS_ALPHA * alphaScale), style = axisStroke)

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
        // §B8-① 次刻度：沿主轴的刻度短线（中间档：0.30）
        drawPath(tickPath, axisColor.copy(alpha = TICK_ALPHA * alphaScale), style = tickStroke)
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

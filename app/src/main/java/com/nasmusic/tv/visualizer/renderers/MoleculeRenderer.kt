package com.nasmusic.tv.visualizer.renderers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.CoverPalette
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * E37 `MOLECULE` — 分子 · 化学分子式频谱动画（v2：描线刻画版）。
 *
 * 节奏完全对齐 E25「催眠」四态状态机：**描线刻画（8.0s）→ 凝视（3.0s）→
 * 溃散（2.4s）→ 空场（0.5s）→ 洗牌换下一个分子**。
 *
 * 刻画方式：从 0 号原子（重原子）出发沿键做 **DFS 描线** —— 原子按描线次序
 * 逐个点亮、化学键从"先画出的原子"向"后画的原子"生长，视觉上像一支笔
 * 沿分子骨架游走（对齐催眠的曲线描线手感）。
 *
 * 频谱映射：每个原子 = 一个频段（重原子 → 低频），能量驱动原子半径/光晕/
 * 描边；键的亮度粗细随两端能量变化，键上光点随节拍流动。
 *
 * 布局：绘图区左移（中心 0.40w），右侧文字带显示分子式（[FormulaLayout]
 * 下标排版）+ 中文名（用户指定：左分子、右名称）。
 *
 * 性能红线：draw 内零分配 —— screen/drift/appear/Paint 均为成员；DFS 邻接表
 * 在 [pickNext]（换分子时）一次性构建。
 */
class MoleculeRenderer(
    private val seed: Long = System.nanoTime()   // 生产用默认；测试注入固定值断言确定性
) : VisualizerRenderer {

    override val theme = VisualizerTheme.MOLECULE

    companion object {
        /** 节奏常量（与 E25 催眠完全一致：DRAW/HOLD/DISSOLVE/GAP） */
        const val DRAW_MS = 8000f
        const val HOLD_MS = 3000f
        const val DISSOLVE_MS = 2400f
        const val GAP_MS = 500f

        /** 布局常量（对齐 E25 §8.1）：绘图区中心 / 分子外接半径 / 右侧文字带宽 */
        const val PLOT_CX_F = 0.40f
        const val PLOT_R_F = 0.30f
        const val LABEL_BAND_F = 0.18f
        const val FORMULA_CY_F = 0.40f
        const val NAME_CY_F = 0.52f

        /** 状态迁移纯函数（与催眠 §13.1 同构）：返回下一态，null = 保持 */
        internal fun nextPhase(phase: Phase, elapsedMs: Long): Phase? = when (phase) {
            Phase.DRAW -> if (elapsedMs >= DRAW_MS.toLong()) Phase.HOLD else null
            Phase.HOLD -> if (elapsedMs >= HOLD_MS.toLong()) Phase.DISSOLVE else null
            Phase.DISSOLVE -> if (elapsedMs >= DISSOLVE_MS.toLong()) Phase.GAP else null
            Phase.GAP -> if (elapsedMs >= GAP_MS.toLong()) Phase.DRAW else null
        }

        /** 原子 → 频段区间映射（纯函数）：原子按定义序均分频段，重原子 → 低频 */
        internal fun bandRange(atomIdx: Int, atomCount: Int, bandCount: Int): IntArray {
            val per = bandCount.toFloat() / atomCount
            val from = (atomIdx * per).toInt()
            val to = (((atomIdx + 1) * per).toInt() - 1).coerceAtLeast(from)
            return intArrayOf(from.coerceIn(0, bandCount - 1), to.coerceIn(0, bandCount - 1))
        }
    }

    internal enum class Phase { DRAW, HOLD, DISSOLVE, GAP }

    // ── 分子状态 ──
    private val rng = Random(seed)
    private var order = IntArray(0)
    private var orderPtr = 0
    internal var currentIdx = -1
        private set
    private var def: MoleculeDef? = null

    private var atomCount = 0
    private var screen = FloatArray(0)      // n*2 屏幕坐标
    private var drift = FloatArray(0)       // n*2 溃散漂移向量
    private var appearAt = FloatArray(0)    // n 描线出现阈值 0..0.85（DFS 次序）
    private var centroid = FloatArray(2)    // 定义坐标质心（旋转中心）
    private var fitScale = 1f               // 1/(外接半径+边距)

    // ── 绘图区映射（draw 每帧更新）──
    private var plotCx = 0f
    private var plotCy = 0f
    private var unitPx = 1f                 // 每定义单位的屏幕像素数
    private var labelRightX = 0f            // 右侧文字带右缘

    // ── 状态机 ──
    private var phase = Phase.DRAW
    private var phaseStartMs = 0L
    private var lastW = 0f
    private var lastH = 0f
    private var needsRemap = true

    // ── 右侧文字带 ──
    private var formula: FormulaLayout.Result? = null

    // ── 复用绘制对象 ──
    private val formulaPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.RIGHT
    }
    private val namePaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.RIGHT
    }
    private val atomTextPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFF0B0E16.toInt()
        textAlign = Paint.Align.CENTER
    }

    // 色彩缓存（palette 变化才重算）
    private var cachedAccentArgb = 0
    private var mainColor = Color(0xFF7DE2FF)

    // ── 生命周期 ────────────────────────────────────────────────

    override fun onEnter(ctx: RenderContext) {
        phase = Phase.DRAW
        phaseStartMs = 0L
        needsRemap = true
        order = IntArray(MoleculeLibrary.ALL.size) { it }
        shuffleOrder(-1)
        orderPtr = 0
        pickNext()
    }

    override fun onExit() {
        formula = null
        def = null
    }

    /** 洗牌：Fisher-Yates，首元素与上一个分子相同则与尾元素交换避免连续重复 */
    private fun shuffleOrder(lastIdx: Int) {
        for (i in order.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val t = order[i]; order[i] = order[j]; order[j] = t
        }
        if (order.size > 1 && order[0] == lastIdx) {
            val t = order[0]; order[0] = order[order.size - 1]; order[order.size - 1] = t
        }
    }

    private fun pickNext() {
        if (orderPtr >= order.size) {
            shuffleOrder(order[order.size - 1])
            orderPtr = 0
        }
        currentIdx = order[orderPtr++]
        val d = MoleculeLibrary.ALL[currentIdx]
        def = d
        atomCount = d.elements.size
        if (screen.size < atomCount * 2) {
            screen = FloatArray(atomCount * 2)
            drift = FloatArray(atomCount * 2)
            appearAt = FloatArray(atomCount)
        }
        // 质心与外接半径
        var cx = 0f; var cy = 0f
        for (i in 0 until atomCount) {
            cx += d.coords[i * 2]; cy += d.coords[i * 2 + 1]
        }
        cx /= atomCount; cy /= atomCount
        centroid[0] = cx; centroid[1] = cy
        var maxR = 0f
        for (i in 0 until atomCount) {
            val dx = d.coords[i * 2] - cx
            val dy = d.coords[i * 2 + 1] - cy
            maxR = max(maxR, sqrt(dx * dx + dy * dy))
        }
        fitScale = 1f / (maxR + 30f)
        // DFS 描线次序（换分子时一次，draw 期零分配）
        computeDrawOrder(d)
        // 溃散漂移方向
        for (i in 0 until atomCount) {
            val ang = rng.nextFloat() * 2f * kotlin.math.PI.toFloat()
            val spd = 40f + rng.nextFloat() * 60f
            drift[i * 2] = cos(ang) * spd
            drift[i * 2 + 1] = sin(ang) * spd
        }
        needsRemap = true
    }

    /**
     * DFS 描线次序：从 0 号原子沿键深搜，[appearAt] 按次序铺满 0..0.85
     * （留 15% 给末段原子淡入完成）。孤立原子（不应存在）追加在末尾。
     */
    private fun computeDrawOrder(d: MoleculeDef) {
        val n = atomCount
        val adjCnt = IntArray(n)
        for (k in d.bonds.indices) adjCnt[d.bonds[k]]++
        val adj = Array(n) { IntArray(adjCnt[it]) }
        val ptr = IntArray(n)
        for (k in 0 until d.bonds.size / 2) {
            val a = d.bonds[k * 2]; val b = d.bonds[k * 2 + 1]
            adj[a][ptr[a]++] = b; adj[b][ptr[b]++] = a
        }
        val pos = IntArray(n) { -1 }
        val stack = IntArray(n)
        var sp = 0
        stack[sp++] = 0
        pos[0] = 0
        var seq = 1
        while (sp > 0) {
            val v = stack[sp - 1]
            var next = -1
            for (u in adj[v]) if (pos[u] == -1) { next = u; break }
            if (next == -1) sp-- else { pos[next] = seq++; stack[sp++] = next }
        }
        for (i in 0 until n) if (pos[i] == -1) pos[i] = seq++
        for (i in 0 until n) {
            appearAt[i] = if (n <= 1) 0f else pos[i].toFloat() / (n - 1) * 0.85f
        }
    }

    /** 屏幕映射 + 公式排版（画布尺寸变化或换分子时执行） */
    private fun remap(w: Float, h: Float, safeArea: Float) {
        val d = def ?: return
        // 右缘钳制到 ≥0.82w（对齐 E25 §11.3）；分子式与中文名共用同一条右缘线
        labelRightX = (w - safeArea - 32f).coerceAtLeast(w * 0.82f)
        formula = FormulaLayout.layout(
            d.formulaMark, labelRightX, h * FORMULA_CY_F, w * LABEL_BAND_F * 0.86f, 44f
        ) { text, size ->
            formulaPaint.textSize = size
            formulaPaint.measureText(text)
        }
        lastW = w; lastH = h
    }

    // ── 绘制 ────────────────────────────────────────────────────

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return
        val d = def ?: return

        val now = ctx.nowMs
        if (phaseStartMs == 0L) phaseStartMs = now
        if (needsRemap || w != lastW || h != lastH || formula == null) {
            remap(w, h, ctx.safeAreaPx)
            needsRemap = false
        }
        refreshColors(ctx)

        val elapsed = (now - phaseStartMs).coerceAtLeast(0L)

        // 绘图区映射：旋转绕定义质心 → 质心屏幕位置恒为 (plotCx, plotCy)
        plotCx = w * PLOT_CX_F
        plotCy = h / 2f
        unitPx = minOf(w, h) * PLOT_R_F * fitScale
        val rot = now * 0.00012f * (1f + frame.pulse * 0.5f)
        val cs = cos(rot); val sn = sin(rot)
        for (i in 0 until atomCount) {
            val dx = (d.coords[i * 2] - centroid[0]) * unitPx
            val dy = (d.coords[i * 2 + 1] - centroid[1]) * unitPx
            screen[i * 2] = plotCx + dx * cs - dy * sn
            screen[i * 2 + 1] = plotCy + dx * sn + dy * cs
        }

        when (phase) {
            Phase.DRAW -> drawStrokePhase(frame, elapsed)
            Phase.HOLD -> drawHold(frame, elapsed)
            Phase.DISSOLVE -> drawDissolve(frame, elapsed)
            Phase.GAP -> drawGapOverlay()
        }

        drawLabelBand(frame, elapsed)

        val next = nextPhase(phase, elapsed)
        if (next != null) {
            phase = next
            phaseStartMs = now
        }
    }

    private fun refreshColors(ctx: RenderContext) {
        val argb = ctx.palette.accent.toArgb()
        if (argb != cachedAccentArgb) {
            cachedAccentArgb = argb
            val base = if (ctx.palette == CoverPalette.Fallback) Color(0xFF7DE2FF) else ctx.palette.accent
            mainColor = VisualizerMath.neonize(base)
        }
    }

    /** 原子 i 的归一化频段能量 0..1（频段均分，重原子 → 低频段） */
    private fun atomEnergy(frame: AudioFrame, i: Int): Float {
        val r = bandRange(i, atomCount, frame.spectrum.size)
        return frame.bandMean(r[0], r[1])
    }

    /** DRAW：描线刻画（对齐催眠 §8.4）——原子按 DFS 次序点亮、键沿次序生长 */
    private fun DrawScope.drawStrokePhase(frame: AudioFrame, elapsed: Long) {
        val speed = 1f + frame.pulse * 0.15f
        val progress = ((elapsed / 1000f) * speed / (DRAW_MS / 1000f)).coerceIn(0f, 1f)
        drawBonds(frame, progress, 1f)
        drawAtoms(frame, progress, 1f)
        // 笔头高亮：正在描画的原子（progress 对应的 DFS 序位）
        val headIdx = (progress * (atomCount - 1)).toInt().coerceIn(0, atomCount - 1)
        if (progress < 1f) {
            drawCircle(
                Color.White, 5f + frame.treble * 4f,
                Offset(screen[headIdx * 2], screen[headIdx * 2 + 1]),
                alpha = 0.8f, blendMode = BlendMode.Plus
            )
        }
    }

    /** HOLD：全量绘制 + 呼吸（对齐催眠 §8.5） */
    private fun DrawScope.drawHold(frame: AudioFrame, elapsed: Long) {
        drawBonds(frame, 1f, 1f)
        drawAtoms(frame, 1f, 1f)
    }

    /** DISSOLVE：原子沿漂移向量散开淡出（对齐催眠 §6） */
    private fun DrawScope.drawDissolve(frame: AudioFrame, elapsed: Long) {
        val p = (elapsed / DISSOLVE_MS).coerceIn(0f, 1f)
        val fade = 1f - p
        drawBonds(frame, 1f, fade)
        drawAtoms(frame, 1f, fade, p * p)
    }

    private fun DrawScope.drawGapOverlay() {
        // 空场：分子层不画；文字带由 drawLabelBand 的 GAP 分支处理
    }

    /** 化学键：亮度/粗细随两端原子能量；双键平行双线；键上能量光点流动 */
    private fun DrawScope.drawBonds(frame: AudioFrame, growth: Float, fade: Float) {
        val d = def ?: return
        val nb = d.bonds.size / 2
        val now = frame.timeMs
        for (b in 0 until nb) {
            val a = d.bonds[b * 2]
            val e = d.bonds[b * 2 + 1]
            // 描线期：键在其后画出的原子出现后才开始生长
            val bondReady = max(appearAt[a], appearAt[e])
            if (growth < 1f && growth < bondReady) continue
            val eA = atomEnergy(frame, a)
            val eB = atomEnergy(frame, e)
            val energy = (eA + eB) / 2f
            val ax = screen[a * 2]; val ay = screen[a * 2 + 1]
            val bx = screen[e * 2]; val by = screen[e * 2 + 1]
            // 键生长：从先出现的原子向后出现的原子插值
            val growSpan = 0.12f
            val g = if (growth < 1f) {
                ((growth - bondReady) / growSpan).coerceIn(0f, 1f)
            } else 1f
            val gx = ax + (bx - ax) * g
            val gy = ay + (by - ay) * g
            val alpha = (0.22f + energy * 0.7f) * fade
            if (alpha <= 0.01f) continue
            val width = 2f + energy * 5f
            if (d.bondOrders[b] >= 2f) {
                // 双键：沿法线偏移 ±3.5f 两条平行线
                val nx = -(by - ay); val ny = bx - ax
                val mag = sqrt(nx * nx + ny * ny).coerceAtLeast(0.001f)
                val ox = nx / mag * 3.5f; val oy = ny / mag * 3.5f
                drawLine(mainColor, Offset(ax + ox, ay + oy), Offset(gx + ox, gy + oy),
                    alpha = alpha, strokeWidth = width * 0.7f, cap = StrokeCap.Round)
                drawLine(mainColor, Offset(ax - ox, ay - oy), Offset(gx - ox, gy - oy),
                    alpha = alpha, strokeWidth = width * 0.7f, cap = StrokeCap.Round)
            } else {
                drawLine(mainColor, Offset(ax, ay), Offset(gx, gy),
                    alpha = alpha, strokeWidth = width, cap = StrokeCap.Round)
            }
            // 键上能量光点（能量越足流动越快）
            if (energy > 0.3f && fade > 0.3f && g >= 1f) {
                val t = (now * 0.001f * (0.4f + energy * 0.8f)) % 1f
                val px = ax + (bx - ax) * t
                val py = ay + (by - ay) * t
                drawCircle(
                    Color.White, 2.5f + energy * 4.5f, Offset(px, py),
                    alpha = energy * fade, blendMode = BlendMode.Plus
                )
            }
        }
        // 芳香环离域内圈（苯/咖啡因等）：亮度随中频能量呼吸；环心 = 质心屏幕位置
        val ringR = d.aromaticRingR
        if (ringR > 0f) {
            drawCircle(
                mainColor, ringR * unitPx,
                Offset(plotCx, plotCy),
                alpha = (0.25f + frame.mid * 0.45f) * fade, style = Stroke(width = 3f)
            )
        }
    }

    /** 原子：能量 → 半径/光晕/描边；描线期按 DFS 次序淡入；溃散期漂移淡出 */
    private fun DrawScope.drawAtoms(
        frame: AudioFrame,
        growth: Float,
        fade: Float,
        driftP: Float = 0f,
    ) {
        val d = def ?: return
        val nc = drawContext.canvas.nativeCanvas
        for (i in 0 until atomCount) {
            if (growth < 1f && growth < appearAt[i]) continue
            val e = atomEnergy(frame, i)
            val appear = if (growth < 1f) {
                ((growth - appearAt[i]) / 0.15f).coerceIn(0f, 1f)
            } else 1f
            val alpha = appear * fade
            if (alpha <= 0.01f) continue
            var px = screen[i * 2]
            var py = screen[i * 2 + 1]
            if (driftP > 0f) {
                px += drift[i * 2] * driftP
                py += drift[i * 2 + 1] * driftP
            }
            val el = MoleculeLibrary.ELEMENTS[d.elements[i].toInt()]
            val r = el.baseR * unitPx * (1f + e * 0.8f + frame.pulse * 0.15f)
            val pos = Offset(px, py)

            // 光晕：3 层同心圆 + Plus 混合（零分配，替代径向渐变 Brush）
            val glowA = (0.08f + e * 0.35f) * alpha
            drawCircle(mainColor, r * 2.6f, pos, alpha = glowA * 0.35f, blendMode = BlendMode.Plus)
            drawCircle(mainColor, r * 1.7f, pos, alpha = glowA * 0.6f, blendMode = BlendMode.Plus)
            drawCircle(mainColor, r * 1.15f, pos, alpha = glowA, blendMode = BlendMode.Plus)
            // 本体 + 描边
            drawCircle(el.color, r, pos, alpha = alpha)
            drawCircle(
                mainColor, r, pos,
                alpha = (0.35f + e * 0.55f) * alpha,
                style = Stroke(width = 1.5f + e * 2.5f)
            )
            // 元素符号（圆够大才画）
            if (r >= 9f) {
                atomTextPaint.textSize = r * 1.05f
                atomTextPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
                nc.drawText(el.symbol, px, py + atomTextPaint.textSize * 0.35f, atomTextPaint)
            }
        }
    }

    /** 右侧文字带：分子式（FormulaLayout 下标排版）+ 中文名，随状态机同步淡入淡出 */
    private fun DrawScope.drawLabelBand(frame: AudioFrame, elapsed: Long) {
        val f = formula ?: return
        val d = def ?: return
        if (phase == Phase.GAP) return
        val nc = drawContext.canvas.nativeCanvas
        val w = size.width
        val h = size.height

        val baseA: Float = when (phase) {
            Phase.DRAW -> (elapsed / DRAW_MS).coerceIn(0f, 1f)
            Phase.HOLD -> 0.88f + 0.10f * (0.5f + 0.5f * sin(elapsed * 0.002f))
            Phase.DISSOLVE -> 1f - (elapsed / DISSOLVE_MS).coerceIn(0f, 1f)
            Phase.GAP -> 0f
        }
        if (baseA <= 0.01f) return

        // 分子式 run（下标已由 FormulaLayout 排好）
        for (k in 0 until f.runCount) {
            formulaPaint.textSize = f.runSize[k]
            formulaPaint.alpha = (224 * baseA).toInt().coerceIn(0, 255)
            nc.drawText(f.runText[k], f.runX[k], f.runY[k], formulaPaint)
        }
        // 中文名（分子式下方，右对齐同一条右缘线）
        namePaint.textSize = (minOf(h, w) * 0.045f).coerceIn(24f, 40f)
        namePaint.alpha = (190 * baseA).toInt().coerceIn(0, 255)
        namePaint.color = mainColor.copy(alpha = 1f).toArgb()
        nc.drawText(d.name, labelRightX, h * NAME_CY_F, namePaint)
    }
}

/**
 * 分子定义（纯 JVM 数据，便于单测校验）。
 *
 * @param formulaMark [FormulaLayout] 源标记（`_` 下标 → 真化学式排版）
 * @param name        中文名（右侧文字带第二行）
 * @param elements    每原子元素索引（[MoleculeLibrary.ELEMENTS] 下标）
 * @param coords      n*2 定义坐标（±150 量级；绘制时自适应缩放）
 * @param bonds       m*2 原子索引对
 * @param bondOrders  每键键级（1 = 单键，≥2 = 双键平行双线）
 * @param aromaticRingR > 0 时绘制芳香离域内圈（半径，定义单位）
 */
internal class MoleculeDef(
    val formulaMark: String,
    val name: String,
    val elements: ByteArray,
    val coords: FloatArray,
    val bonds: IntArray,
    val bondOrders: FloatArray,
    val aromaticRingR: Float = 0f,
)

/**
 * 分子库：用户清单全部 50 个分子的 2D 结构 + 中文命名。
 *
 * 坐标为「定义单位」（±150 量级），2D 骨架按化学惯例手工排布：
 * 六元环半径 62、五元环半径 52、键长 ≈54、氢原子距重原子 ≈50。
 * 绘制时由渲染器自适应缩放，无需精确键长。
 */
internal object MoleculeLibrary {

    /** 元素外观：符号 / 本体色（压暗 CPK 配色）/ 基础半径（定义单位） */
    class Element(val symbol: String, val color: Color, val baseR: Float)

    val ELEMENTS = arrayOf(
        Element("C", Color(0xFF9BA8B0), 22f),   // 0
        Element("H", Color(0xFFE8ECF2), 14f),   // 1
        Element("O", Color(0xFFFF6B6B), 20f),   // 2
        Element("N", Color(0xFF7A9BFF), 20f),   // 3
        Element("S", Color(0xFFFFD166), 21f),   // 4
        Element("P", Color(0xFFFFA94D), 21f),   // 5
        Element("F", Color(0xFF8CE99A), 16f),   // 6
        Element("Cl", Color(0xFF63E6BE), 18f),  // 7
        Element("Fe", Color(0xFFE599F7), 24f),  // 8
        Element("Mg", Color(0xFF8CE99A), 24f),  // 9
        Element("B", Color(0xFFFFC078), 19f),   // 10
        Element("K", Color(0xFFB197FC), 23f),   // 11
        Element("Pt", Color(0xFFCED4DA), 24f),  // 12
        Element("Hg", Color(0xFFDEE2E6), 24f),  // 13
    )

    private const val C = 0.toByte()
    private const val H = 1.toByte()
    private const val O = 2.toByte()
    private const val N = 3.toByte()
    private const val S = 4.toByte()
    private const val P = 5.toByte()
    private const val F = 6.toByte()
    private const val Cl = 7.toByte()
    private const val Fe = 8.toByte()
    private const val Mg = 9.toByte()
    private const val B = 10.toByte()
    private const val K = 11.toByte()
    private const val Pt = 12.toByte()
    private const val Hg = 13.toByte()

    /** 六元环顶点（半径 62，顶点朝上） */
    private fun hex(): FloatArray {
        val out = FloatArray(12)
        for (k in 0 until 6) {
            val a = (-90f + k * 60f) * kotlin.math.PI.toFloat() / 180f
            out[k * 2] = 62f * cos(a)
            out[k * 2 + 1] = 62f * sin(a)
        }
        return out
    }

    /** 六元环的 6 条环键（0-1-2-3-4-5-0） */
    private val HEX_BONDS = intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0)

    /** 六元环外接氢（半径 112，与顶点同角度） */
    private fun hexH(): FloatArray {
        val out = FloatArray(12)
        for (k in 0 until 6) {
            val a = (-90f + k * 60f) * kotlin.math.PI.toFloat() / 180f
            out[k * 2] = 112f * cos(a)
            out[k * 2 + 1] = 112f * sin(a)
        }
        return out
    }

    val ALL: List<MoleculeDef> = buildList {
        // ═══ 极简入门型 ═══
        add(MoleculeDef("H_{2}O", "水",
            byteArrayOf(O, H, H),
            floatArrayOf(0f, 28f, -56f, -22f, 56f, -22f),
            intArrayOf(0, 1, 0, 2), floatArrayOf(1f, 1f)))
        add(MoleculeDef("CH_{4}", "甲烷",
            byteArrayOf(C, H, H, H, H),
            floatArrayOf(0f, 0f, 0f, -58f, 0f, 58f, -58f, 0f, 58f, 0f),
            intArrayOf(0, 1, 0, 2, 0, 3, 0, 4), floatArrayOf(1f, 1f, 1f, 1f)))
        add(MoleculeDef("NH_{3}", "氨",
            byteArrayOf(N, H, H, H),
            floatArrayOf(0f, -28f, -50f, 32f, 50f, 32f, 0f, 52f),
            intArrayOf(0, 1, 0, 2, 0, 3), floatArrayOf(1f, 1f, 1f)))
        add(MoleculeDef("CH_{3}OH", "甲醇",
            byteArrayOf(C, O, H, H, H, H),
            floatArrayOf(-40f, 0f, 40f, 0f, 92f, 0f, -40f, -52f, -92f, -26f, -92f, 26f),
            intArrayOf(0, 1, 1, 2, 0, 3, 0, 4, 0, 5), floatArrayOf(1f, 1f, 1f, 1f, 1f)))
        add(MoleculeDef("C_{2}H_{4}", "乙烯",
            byteArrayOf(C, C, H, H, H, H),
            floatArrayOf(-54f, 0f, 54f, 0f, -108f, -45f, -108f, 45f, 108f, -45f, 108f, 45f),
            intArrayOf(0, 1, 0, 2, 0, 3, 1, 4, 1, 5), floatArrayOf(2f, 1f, 1f, 1f, 1f)))
        add(MoleculeDef("C_{2}H_{2}", "乙炔",
            byteArrayOf(C, C, H, H),
            floatArrayOf(-54f, 0f, 54f, 0f, -108f, 0f, 108f, 0f),
            intArrayOf(0, 1, 0, 2, 1, 3), floatArrayOf(3f, 1f, 1f)))
        add(MoleculeDef("CH_{2}O", "甲醛",
            byteArrayOf(C, O, H, H),
            floatArrayOf(0f, 0f, 0f, -58f, -52f, 38f, 52f, 38f),
            intArrayOf(0, 1, 0, 2, 0, 3), floatArrayOf(2f, 1f, 1f)))
        add(MoleculeDef("CO_{2}", "二氧化碳",
            byteArrayOf(O, C, O),
            floatArrayOf(-105f, 0f, 0f, 0f, 105f, 0f),
            intArrayOf(0, 1, 1, 2), floatArrayOf(2f, 2f)))
        add(MoleculeDef("C_{2}H_{5}OH", "乙醇",
            byteArrayOf(C, C, O, H, H, H, H, H, H),
            floatArrayOf(
                -110f, 0f, -20f, 0f, 65f, 0f, 125f, 0f,
                -160f, -45f, -160f, 45f, -110f, -62f, -20f, -62f, -20f, 62f),
            intArrayOf(0, 1, 1, 2, 2, 3, 0, 4, 0, 5, 0, 6, 1, 7, 1, 8),
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        add(MoleculeDef("CH_{3}COOH", "乙酸",
            byteArrayOf(C, H, H, H, C, O, O, H),
            floatArrayOf(
                -105f, 0f, -155f, -40f, -155f, 40f, -105f, -62f,
                -15f, 0f, 45f, -55f, 45f, 55f, 105f, 55f),
            intArrayOf(0, 4, 1, 0, 2, 0, 3, 0, 4, 5, 4, 6, 6, 7),
            floatArrayOf(1f, 1f, 1f, 1f, 2f, 1f, 1f)))
        add(MoleculeDef("O_{3}", "臭氧",
            byteArrayOf(O, O, O),
            floatArrayOf(-70f, 10f, 0f, -25f, 70f, 10f),
            intArrayOf(0, 1, 1, 2), floatArrayOf(2f, 1f)))
        add(MoleculeDef("C_{3}H_{9}N", "三甲胺",
            byteArrayOf(N, C, C, C, H, H, H, H, H, H, H, H, H),
            floatArrayOf(
                0f, -30f, -62f, 22f, 62f, 22f, 0f, 52f,
                -62f, 74f, -104f, 40f, -88f, 92f,
                62f, 74f, 104f, 40f, 88f, 92f,
                0f, 104f, -40f, 92f, 40f, 92f),
            intArrayOf(0, 1, 0, 2, 0, 3, 1, 4, 1, 5, 1, 6, 2, 7, 2, 8, 2, 9, 3, 10, 3, 11, 3, 12),
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        add(MoleculeDef("C_{2}H_{6}S", "二甲硫醚",
            byteArrayOf(S, C, C, H, H, H, H, H, H),
            floatArrayOf(0f, 0f, -80f, 0f, 80f, 0f, -80f, -52f, -122f, -26f, -122f, 26f,
                80f, -52f, 122f, -26f, 122f, 26f),
            intArrayOf(0, 1, 0, 2, 1, 3, 1, 4, 1, 5, 2, 6, 2, 7, 2, 8),
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        add(MoleculeDef("C_{2}H_{6}Hg", "二甲基汞",
            byteArrayOf(C, C, Hg, H, H, H, H, H, H),
            floatArrayOf(-80f, 0f, 80f, 0f, 0f, 0f, -80f, -52f, -122f, -26f, -122f, 26f,
                80f, -52f, 122f, -26f, 122f, 26f),
            intArrayOf(2, 0, 2, 1, 0, 3, 0, 4, 0, 5, 1, 6, 1, 7, 1, 8),
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        add(MoleculeDef("CCl_{2}F_{2}", "氟利昂-12",
            byteArrayOf(C, Cl, Cl, F, F),
            floatArrayOf(0f, 0f, 0f, -62f, 0f, 62f, -62f, 0f, 62f, 0f),
            intArrayOf(0, 1, 0, 2, 0, 3, 0, 4), floatArrayOf(1f, 1f, 1f, 1f)))


        // ═══ 药物与生物分子（稠环 / 杂环 / 链状）═══
        // 对乙酰氨基酚（扑热息痛）：苯环 + 羟基 + 乙酰胺
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{8}H_{9}NO_{2}", "对乙酰氨基酚",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, O, N, C, C, O, H, H, H, H),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], hyd[4], hyd[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    150f, 0f, 204f, 0f,            // 环顶 OH
                    150f, 110f, 150f, 168f, 204f, 196f, 150f, 226f, 96f, 196f, 96f, 138f,
                    96f, 254f, 204f, 254f, 150f, 284f),   // 乙酰胺 3 个 H
                intArrayOf(HEX_BONDS[0], HEX_BONDS[1], HEX_BONDS[2], HEX_BONDS[3],
                    HEX_BONDS[4], HEX_BONDS[5], HEX_BONDS[6], HEX_BONDS[7],
                    HEX_BONDS[8], HEX_BONDS[9], HEX_BONDS[10], HEX_BONDS[11],
                    8, 11, 11, 12,                  // 环-O-H
                    10, 13, 13, 14, 14, 15, 15, 16, 14, 17, 14, 18, 13, 16),
                floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
                aromaticRingR = 38f))
        }
        // 阿司匹林：苯环 + 羧基 + 酯基
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{9}H_{8}O_{4}", "阿司匹林",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, C, O, O, C, O, H, H, H),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], hyd[4], hyd[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    150f, 110f, 150f, 168f, 204f, 196f, 150f, 226f,
                    96f, 196f, 40f, 224f, -14f, 196f, -14f, 254f, 40f, 282f, -60f, 282f),
                intArrayOf(0, 2, 2, 4, 4, 6, 6, 8, 8, 10, 10, 0,
                    0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
                    10, 11, 11, 12, 12, 13, 13, 14, 11, 15, 15, 16,
                    14, 17, 14, 18),
                floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
                aromaticRingR = 38f))
        }
        // 咖啡因：嘌呤骨架（六元环 + 五元环，4 个 N，2 个 C=O，3 个甲基）
        run {
            val ring = hex()
            add(MoleculeDef("C_{8}H_{10}N_{4}O_{2}", "咖啡因",
                byteArrayOf(C, N, C, N, C, C, N, C, N, O, O, C, C, C, H, H, H, H, H, H, H),
                floatArrayOf(
                    ring[0], ring[1], ring[2], ring[3], ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    -40f, -110f, 40f, -110f,            // 五元环上两点（并环共用边 2-3）
                    -108f, -150f, -30f, -172f, 55f, -160f,  // 五元环
                    -108f, 110f, 0f, 135f, 108f, 110f,   // 3 个甲基
                    -150f, 85f, -50f, 168f, 50f, 168f, 150f, 85f, -128f, -186f, 0f, -215f, 88f, -196f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 2, 6, 6, 7, 7, 8, 8, 3, 1, 9, 4, 10, 1, 11, 5, 12, 5, 13, 11, 14, 11, 15, 11, 16, 12, 17, 12, 18, 12, 19, 13, 20),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        }
        // 多巴胺：苯环 + 2 个酚羟基 + 乙胺链
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{8}H_{11}NO_{2}", "多巴胺",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, O, O, N, C, C, H, H, H),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    150f, -50f, 204f, -80f,          // 2 个 OH
                    150f, 50f, 204f, 80f, 258f, 80f, 312f, 80f,   // 乙胺链
                    338f, 40f, 338f, 120f, 312f, 138f, 258f, 138f, 204f, 138f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 8, 11, 11, 12, 10, 13, 13, 14, 10, 15, 15, 16, 16, 17, 16, 18),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
                aromaticRingR = 38f))
        }
        // 血清素：吲哚（苯并五元环 NH）+ 5-羟乙胺
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{10}H_{12}N_{2}O", "血清素",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, C, C, N, C, O, N, C, C, H, H, H),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    -40f, -110f, 40f, -110f,          // 五元环
                    150f, -50f, 204f, -80f,           // OH
                    150f, 50f, 204f, 80f, 258f, 80f,  // 乙胺
                    -108f, -150f, -30f, -172f, 55f, -160f,
                    338f, 40f, 338f, 120f, 312f, 138f, 258f, 138f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 2, 10, 10, 11, 11, 12, 12, 3, 8, 15, 15, 16, 10, 13, 13, 14, 14, 17, 17, 18, 0, 19, 1, 20, 3, 21),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
                aromaticRingR = 38f))
        }

        // ═══ 对称美学型 ═══
        // 六氟化硫：正八面体投影（十字 + 四对角）
        add(MoleculeDef("SF_{6}", "六氟化硫",
            byteArrayOf(S, F, F, F, F, F, F),
            floatArrayOf(0f, 0f, 0f, -85f, 0f, 85f, -85f, 0f, 85f, 0f, -60f, -60f, 60f, 60f),
            intArrayOf(0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6),
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f)))
        // 立方烷：立方体投影（外方 + 内方，对角连线）
        add(MoleculeDef("C_{8}H_{8}", "立方烷",
            byteArrayOf(C, C, C, C, C, C, C, C, H, H, H, H, H, H, H, H),
            floatArrayOf(-62f, -62f, 62f, -62f, 62f, 62f, -62f, 62f,
                -31f, -31f, 31f, -31f, 31f, 31f, -31f, 31f,
                -62f, -100f, 62f, -100f, 100f, 62f, 100f, -62f,
                -31f, -69f, 31f, -69f, 69f, 31f, 69f, -31f),
            intArrayOf(0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4,
                0, 4, 1, 5, 2, 6, 3, 7,
                0, 8, 1, 9, 2, 10, 3, 11, 4, 12, 5, 13, 6, 14, 7, 15),
            floatArrayOf(*FloatArray(20) { 1f })))
        // 金刚烷：笼状投影（三环桥接）
        add(MoleculeDef("C_{10}H_{16}", "金刚烷",
            byteArrayOf(C, C, C, C, C, C, C, C, C, C, H, H, H, H, H, H, H, H, H, H, H, H),
            floatArrayOf(0f, -90f, -78f, -30f, 78f, -30f, -48f, 60f, 48f, 60f, 0f, 0f,
                -26f, -45f, 26f, -45f, 0f, 20f, -52f, 15f, 52f, 15f,
                0f, -140f, -104f, -55f, 104f, -55f, -84f, 60f, 84f, 60f, 0f, 110f,
                -26f, -45f, 26f, -45f, 0f, 20f, -52f, 15f, 52f, 15f),
            intArrayOf(0, 5, 1, 5, 2, 5, 3, 5, 4, 5, 0, 1, 1, 2, 2, 4, 4, 3, 3, 0, 0, 10, 1, 11, 2, 12, 3, 13, 4, 14, 5, 15, 0, 16, 1, 17, 2, 18, 3, 19, 4, 20, 5, 21),floatArrayOf(*FloatArray(22) { 1f })))
        // 三聚氰胺：三嗪环 + 3 个氨基
        run {
            val ring = hex()
            add(MoleculeDef("C_{3}H_{6}N_{6}", "三聚氰胺",
                byteArrayOf(C, N, C, N, C, N, N, N, N, H, H, H, H, H, H, H),
                floatArrayOf(ring[0], ring[1], ring[2], ring[3], ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    124f, -72f, 0f, 144f, -124f, -72f,
                    162f, -94f, 124f, -130f, 0f, 186f, -42f, 186f, 42f, 186f, -124f, -130f, -162f, -94f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0,
                    0, 6, 2, 7, 4, 8,
                    6, 9, 6, 10, 7, 11, 7, 12, 8, 13, 8, 14, 6, 15),
                floatArrayOf(1f, 2f, 1f, 2f, 1f, 2f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        }
        // 二茂铁：三明治投影（上下两个五元环夹 Fe）
        run {
            val top = FloatArray(10); val bot = FloatArray(10)
            for (k in 0 until 5) {
                val a = (-90f + k * 72f) * kotlin.math.PI.toFloat() / 180f
                top[k * 2] = 70f * cos(a); top[k * 2 + 1] = -95f + 40f * sin(a)
                bot[k * 2] = 70f * cos(a); bot[k * 2 + 1] = 95f + 40f * sin(a)
            }
            add(MoleculeDef("Fe(C_{5}H_{5})_{2}", "二茂铁",
                byteArrayOf(Fe, C, C, C, C, C, C, C, C, C, C, H, H, H, H, H, H, H, H, H, H),
                floatArrayOf(0f, 0f,
                    top[0], top[1], top[2], top[3], top[4], top[5], top[6], top[7], top[8], top[9],
                    bot[0], bot[1], bot[2], bot[3], bot[4], bot[5], bot[6], bot[7], bot[8], bot[9],
                    top[0] * 1.6f, top[1] * 1.6f - 55f, top[2] * 1.6f, top[3] * 1.6f - 55f,
                    top[4] * 1.6f, top[5] * 1.6f - 55f, top[6] * 1.6f, top[7] * 1.6f - 55f,
                    top[8] * 1.6f, top[9] * 1.6f - 55f,
                    bot[0] * 1.6f, bot[1] * 1.6f + 55f, bot[2] * 1.6f, bot[3] * 1.6f + 55f,
                    bot[4] * 1.6f, bot[5] * 1.6f + 55f, bot[6] * 1.6f, bot[7] * 1.6f + 55f,
                    bot[8] * 1.6f, bot[9] * 1.6f + 55f),
                intArrayOf(0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8, 0, 9, 0, 10, 1, 2, 2, 3, 3, 4, 4, 5, 5, 1, 6, 7, 7, 8, 8, 9, 9, 10, 10, 6, 1, 11, 2, 12, 3, 13, 4, 14, 5, 15, 6, 16, 7, 17, 8, 18, 9, 19, 10, 20),
                floatArrayOf(*FloatArray(30) { 1f })))
        }
        // 亚铁氰化钾：Fe 中心 + 6 个 CN 八面体投影 + 4 个 K
        add(MoleculeDef("K_{4}[Fe(CN)_{6}]", "亚铁氰化钾",
            byteArrayOf(Fe, C, C, C, C, C, C, N, N, N, N, N, N, K, K, K, K),
            floatArrayOf(0f, 0f, 0f, -70f, 0f, 70f, -70f, 0f, 70f, 0f, -50f, -50f, 50f, 50f,
                0f, -124f, 0f, 124f, -124f, 0f, 124f, 0f, -88f, -88f, 88f, 88f,
                -160f, -160f, 160f, -160f, -160f, 160f, 160f, 160f),
            intArrayOf(0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6,
                1, 7, 2, 8, 3, 9, 4, 10, 5, 11, 6, 12,
                7, 13, 8, 14, 9, 15, 10, 16),
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 3f, 3f, 3f, 3f, 3f, 3f, 1f, 1f, 1f, 1f)))

        // ═══ DNA/RNA 碱基 ═══
        // 腺嘌呤：嘌呤双环（六元 + 五元，4 个 N）
        run {
            val ring = hex()
            add(MoleculeDef("C_{5}H_{5}N_{5}", "腺嘌呤",
                byteArrayOf(C, N, C, N, C, C, N, C, N, H, H, H, H, H, H, H, H, H),
                floatArrayOf(
                    ring[0], ring[1], ring[2], ring[3], ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    -40f, -110f, 40f, -110f,            // 五元环（并环共用边 2-3）
                    -108f, -150f, -30f, -172f, 55f, -160f,
                    0f, 124f,                            // 6 位氨基 N
                    -42f, 166f, 42f, 166f,               // 氨基 H
                    -150f, 85f, -128f, -186f, 0f, -215f, 88f, -196f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 2, 6, 6, 7, 7, 8, 8, 3, 1, 9, 4, 10, 5, 11, 11, 12, 11, 13, 0, 14, 6, 15, 7, 16, 8, 17),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        }
        // 鸟嘌呤：嘌呤双环 + 酮基 + 氨基
        run {
            val ring = hex()
            add(MoleculeDef("C_{5}H_{5}N_{5}O", "鸟嘌呤",
                byteArrayOf(C, N, C, N, C, C, N, C, N, O, H, H, H, H, H, H, H, H, H),
                floatArrayOf(
                    ring[0], ring[1], ring[2], ring[3], ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    -40f, -110f, 40f, -110f,
                    -108f, -150f, -30f, -172f, 55f, -160f,
                    0f, 124f, 0f, 178f,                  // 6 位 C=O
                    -150f, 85f, -128f, -186f, 0f, -215f, 88f, -196f,
                    42f, 166f, 96f, 196f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 2, 6, 6, 7, 7, 8, 8, 3, 1, 9, 4, 10, 5, 11, 11, 12, 0, 13, 6, 14, 7, 15, 8, 16, 4, 17, 17, 18),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        }
        // 胞嘧啶：单环嘧啶 + 酮基 + 氨基
        run {
            val ring = hex()
            add(MoleculeDef("C_{4}H_{5}N_{3}O", "胞嘧啶",
                byteArrayOf(C, N, C, C, N, C, O, N, H, H, H, H, H, H),
                floatArrayOf(
                    ring[0], ring[1], ring[2], ring[3], ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    0f, 124f, 0f, 178f,                  // 2 位 C=O
                    124f, -72f, 162f, -94f, 124f, -130f, // 4 位氨基
                    -124f, -72f, -162f, -94f, -124f, -130f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 5, 6, 6, 7, 3, 8, 8, 9, 8, 10, 0, 11, 1, 12, 2, 13),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        }
        // 胸腺嘧啶：单环嘧啶 + 2 个酮基 + 甲基
        run {
            val ring = hex()
            add(MoleculeDef("C_{5}H_{6}N_{2}O_{2}", "胸腺嘧啶",
                byteArrayOf(C, N, C, C, N, C, O, O, C, H, H, H, H, H, H, H),
                floatArrayOf(
                    ring[0], ring[1], ring[2], ring[3], ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    0f, 124f, 0f, 178f,                  // 2 位 C=O
                    124f, -72f, 178f, -100f,             // 4 位 C=O
                    -124f, -72f, -166f, -102f, -166f, -42f, -208f, -132f,  // 5 位甲基
                    -62f, 138f, 62f, 138f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 5, 6, 6, 7, 3, 8, 8, 9, 2, 10, 10, 11, 10, 12, 10, 13, 1, 14, 4, 15),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        }
        // 尿嘧啶：单环嘧啶 + 2 个酮基
        run {
            val ring = hex()
            add(MoleculeDef("C_{4}H_{4}N_{2}O_{2}", "尿嘧啶",
                byteArrayOf(C, N, C, C, N, C, O, O, H, H, H, H, H, H),
                floatArrayOf(
                    ring[0], ring[1], ring[2], ring[3], ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    0f, 124f, 0f, 178f,
                    124f, -72f, 178f, -100f,
                    -62f, 138f, 62f, 138f,
                    -124f, -72f, -178f, -100f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 5, 6, 6, 7, 3, 8, 8, 9, 1, 10, 4, 11, 2, 12),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        }
        // ═══ 氨基酸代表 ═══
        // 甘氨酸：NH₂-CH₂-COOH
        add(MoleculeDef("C_{2}H_{5}NO_{2}", "甘氨酸",
            byteArrayOf(N, C, C, O, O, H, H, H, H, H),
            floatArrayOf(-120f, 0f, -50f, 0f, 30f, 0f, 88f, -50f, 88f, 50f,
                -120f, -52f, -162f, -26f, -162f, 26f, -50f, -52f, 30f, -62f),
            intArrayOf(0, 1, 1, 2, 2, 3, 2, 4, 0, 5, 0, 6, 0, 7, 1, 8, 2, 9),
            floatArrayOf(1f, 1f, 2f, 1f, 1f, 1f, 1f, 1f, 1f)))
        // 丙氨酸：NH₂-CH(CH₃)-COOH
        add(MoleculeDef("C_{3}H_{7}NO_{2}", "丙氨酸",
            byteArrayOf(N, C, C, O, O, C, H, H, H, H, H, H, H),
            floatArrayOf(-140f, 0f, -60f, 0f, 20f, 0f, 78f, -50f, 78f, 50f,
                -60f, 62f, -102f, 104f, -18f, 104f, -18f, 148f,
                -140f, -52f, -182f, -26f, -182f, 26f, 20f, -62f),
            intArrayOf(0, 1, 1, 2, 2, 3, 2, 4, 1, 5, 5, 6, 5, 7, 5, 8, 0, 9, 0, 10, 0, 11, 2, 12),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))

        // ═══ 香料与气味分子 ═══
        // 香草醛：苯环 + OH + OCH₃ + CHO
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{8}H_{8}O_{3}", "香草醛",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, O, O, C, O, H),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    150f, -50f, 204f, -80f,           // OH
                    150f, 50f, 204f, 80f, 258f, 80f,  // OCH3
                    150f, 110f, 150f, 168f, 204f, 196f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 8, 11, 11, 12, 10, 13, 13, 14, 14, 15),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
                aromaticRingR = 38f))
        }
        // 柠檬烯：环己烯 + 异丙烯基 + 2 个甲基
        add(MoleculeDef("C_{10}H_{16}", "柠檬烯",
            byteArrayOf(C, C, C, C, C, C, C, C, C, C, H, H, H, H, H, H, H, H, H, H, H, H, H, H, H, H),
            floatArrayOf(0f, -70f, 61f, -35f, 61f, 35f, 0f, 70f, -61f, 35f, -61f, -35f,
                122f, -70f, 168f, -95f, 168f, -35f,
                -122f, 70f, -168f, 95f,
                0f, -128f, 122f, 70f,
                30f, -95f, 92f, -60f, -30f, -95f,
                92f, 60f, 30f, 95f, -30f, 95f, -92f, 60f, -92f, -60f,
                199f, -120f, 199f, -10f, 145f, -20f, 191f, 120f, 145f, 120f),
            intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0,
                4, 6, 6, 7, 7, 8,
                1, 9,
                0, 10, 0, 11,
                2, 12,
                3, 13, 3, 14,
                5, 15, 5, 16,
                6, 17,
                7, 18, 7, 19,
                8, 20, 8, 21, 8, 22,
                9, 23, 9, 24, 9, 25),
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
                // 薄荷醇：环己烷 + OH + 异丙基 + 甲基
        add(MoleculeDef("C_{10}H_{20}O", "薄荷醇",
            byteArrayOf(C, C, C, C, C, C, O, C, C, C, C, H, H, H, H, H, H, H, H, H, H, H, H, H, H, H, H, H, H, H, H, H, H, H),
            floatArrayOf(0f, -70f, 61f, -35f, 61f, 35f, 0f, 70f, -61f, 35f, -61f, -35f,
                0f, -128f, -122f, 70f,
                -168f, 95f, -214f, 70f, -214f, 120f,
                -122f, -70f, -168f, -95f,
                30f, -95f, 92f, -60f, -30f, -95f,
                92f, 60f, 30f, 95f, -30f, 95f, -92f, 60f, -92f, -60f,
                0f, 128f, 61f, 163f, -61f, 163f,
                -122f, -128f, -168f, -153f, -214f, -128f, -61f, -163f,
                122f, -70f, 168f, -95f, 168f, -45f,
                -168f, 153f, -260f, 95f, -260f, 145f),
            intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0,
                0, 6, 1, 7, 2, 10, 7, 8, 7, 9,
                0, 11, 1, 12, 2, 13, 3, 14, 3, 15, 4, 16, 4, 17, 5, 18, 5, 19, 6, 20,
                7, 21, 8, 22, 8, 23, 8, 24, 9, 25, 9, 26, 9, 27, 10, 28, 10, 29, 10, 30),
            floatArrayOf(*FloatArray(31) { 1f })))
        // 水杨酸甲酯（冬青油）：苯环 + OH + COOCH₃
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{8}H_{8}O_{3}", "水杨酸甲酯",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, C, O, O, C, O, H),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    150f, 110f, 150f, 168f, 204f, 196f, 150f, 226f,
                    96f, 196f, 40f, 224f, -14f, 196f, -14f, 254f, 40f, 282f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 8, 11, 11, 12, 12, 13, 13, 14, 14, 15, 15, 16),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
                aromaticRingR = 38f))
        }

        // ═══ 环境与工业分子 ═══
        // TNT：苯环 + 3 个硝基
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{7}H_{5}N_{3}O_{6}", "TNT",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, N, N, N, O, O, O, O, O, O),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    150f, -50f, 150f, 50f, 0f, 124f,
                    204f, -80f, 204f, 80f, 0f, 178f,
                    204f, -138f, 258f, -50f, 258f, 138f, 204f, 138f, 0f, 232f, -54f, 178f),
                intArrayOf(HEX_BONDS[0], HEX_BONDS[1], HEX_BONDS[2], HEX_BONDS[3],
                    HEX_BONDS[4], HEX_BONDS[5], HEX_BONDS[6], HEX_BONDS[7],
                    HEX_BONDS[8], HEX_BONDS[9], HEX_BONDS[10], HEX_BONDS[11],
                    8, 11, 10, 12, 12, 13,
                    11, 14, 11, 15, 12, 16, 12, 17, 13, 18, 13, 19),
                floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
                aromaticRingR = 38f))
        }
        // DDT：苯环 ×2 + 三氯甲基
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{14}H_{9}Cl_{5}", "DDT",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, C, Cl, Cl, Cl, C, C, C, C, C),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    0f, 124f, -54f, 178f, 54f, 178f, 0f, 232f,
                    -120f, 190f, -120f, 248f, 120f, 190f, 120f, 248f,
                    -180f, 160f, -180f, 218f, 180f, 160f, 180f, 218f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 10, 11, 11, 12, 11, 13, 11, 14, 11, 15, 15, 16, 15, 17, 15, 18, 15, 19),
                floatArrayOf(*FloatArray(15) { 1f }),
                aromaticRingR = 38f))
        }
        // ═══ 收尾：更多经典结构 ═══
        // 尿素：CO(NH₂)₂
        add(MoleculeDef("CH_{4}N_{2}O", "尿素",
            byteArrayOf(C, O, N, N, H, H, H, H),
            floatArrayOf(0f, 0f, 0f, -62f, -104f, 35f, 104f, 35f,
                -104f, 93f, -158f, 10f, 104f, 93f, 158f, 10f),
            intArrayOf(0, 1, 0, 2, 0, 3, 2, 4, 2, 5, 3, 6, 3, 7),
            floatArrayOf(2f, 1f, 1f, 1f, 1f, 1f, 1f)))
        // 丙酮：CH₃-CO-CH₃
        add(MoleculeDef("C_{3}H_{6}O", "丙酮",
            byteArrayOf(C, C, C, O, H, H, H, H, H, H),
            floatArrayOf(-90f, 0f, 0f, 0f, 90f, 0f, 0f, -62f,
                -90f, -52f, -132f, -26f, -132f, 26f,
                90f, -52f, 132f, -26f, 132f, 26f),
            intArrayOf(0, 1, 1, 2, 1, 3, 0, 4, 0, 5, 0, 6, 2, 7, 2, 8, 2, 9),
            floatArrayOf(1f, 1f, 2f, 1f, 1f, 1f, 1f, 1f, 1f)))
        // 乙醚：C₂H₅-O-C₂H₅
        add(MoleculeDef("C_{4}H_{10}O", "乙醚",
            byteArrayOf(C, C, O, C, C, H, H, H, H, H, H, H, H, H),
            floatArrayOf(-140f, 0f, -70f, 0f, 0f, 0f, 70f, 0f, 140f, 0f,
                -140f, -52f, -182f, -26f, -182f, 26f, -70f, -52f,
                70f, -52f, 70f, 52f, 140f, -52f, 182f, -26f, 182f, 26f),
            intArrayOf(0, 1, 1, 2, 2, 3, 3, 4,
                0, 5, 0, 6, 0, 7, 1, 8,
                3, 9, 3, 10, 4, 11, 4, 12, 4, 13),
            floatArrayOf(*FloatArray(13) { 1f })))
        // 乙酸乙酯：CH₃COOCH₂CH₃
        add(MoleculeDef("C_{4}H_{8}O_{2}", "乙酸乙酯",
            byteArrayOf(C, C, O, C, O, C, C, H, H, H, H, H, H, H, H),
            floatArrayOf(-140f, 0f, -60f, 0f, 0f, -55f, 0f, 55f, 60f, 55f, 120f, 55f, 180f, 55f,
                -140f, -52f, -182f, -26f, -182f, 26f, -60f, -55f,
                120f, -5f, 120f, 115f, 180f, -5f, 180f, 115f),
            intArrayOf(0, 1, 1, 2, 1, 3, 3, 4, 4, 5, 5, 6,
                0, 7, 0, 8, 0, 9, 2, 10,
                5, 11, 5, 12, 6, 13, 6, 14),
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        // 葡萄糖（吡喃环简化）：六元环 + 5 个 OH + CH₂OH
        run {
            val ring = hex()
            add(MoleculeDef("C_{6}H_{12}O_{6}", "葡萄糖",
                byteArrayOf(C, C, C, C, C, C, O, O, O, O, O, O, C, H, H, H, H, H, H, H, H, H, H, H, H, H),
                floatArrayOf(ring[0], ring[1], ring[2], ring[3], ring[4], ring[5], ring[6], ring[7], ring[8], ring[9], ring[10], ring[11],
                    150f, -87f, 150f, -143f,   // C1-OH
                    178f, 0f, 234f, 0f,        // C2-OH
                    150f, 87f, 150f, 143f,     // C3-OH
                    0f, 124f, 0f, 180f,        // C4-OH
                    -150f, 87f, -150f, 143f,   // C5-OH
                    -178f, 0f, -234f, 0f,      // C6（CH2OH 的 O）
                    -150f, -87f, -150f, -143f, // 环氧
                    0f, -124f, 0f, -180f,
                    178f, 55f, 234f, 55f, 178f, -55f, 234f, -55f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0,
                    0, 6, 1, 7, 2, 8, 3, 9, 4, 10, 5, 12,
                    6, 13, 7, 14, 8, 15, 9, 16, 10, 17,
                    0, 18, 1, 19, 2, 20, 3, 21, 4, 24, 5, 25, 12, 22, 12, 23),
                floatArrayOf(*FloatArray(25) { 1f })))
        }
        // 维生素 C：五元内酯环 + 侧链
        add(MoleculeDef("C_{6}H_{8}O_{6}", "维生素C",
            byteArrayOf(C, C, C, C, C, O, O, O, O, O, O, C, H, H, H, H, H, H, H),
            floatArrayOf(0f, -70f, 66f, -22f, 41f, 57f, -41f, 57f, -66f, -22f,
                0f, -128f, 132f, -45f, 66f, 115f, -66f, 115f, -132f, -45f,
                0f, 115f, 0f, 173f,
                199f, -70f, 199f, -10f, 145f, -20f,
                105f, 148f, 27f, 148f, -27f, 148f, -105f, 148f),
            intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 0,
                0, 5, 1, 6, 2, 7, 3, 8, 4, 9,
                3, 10, 10, 11,
                0, 12, 0, 13, 1, 14, 1, 15, 2, 16, 2, 17, 4, 18),
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        // ═══ 苯系与高辨识度经典分子 ═══
        // 苯：六元环 + 离域内圈
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{6}H_{6}", "苯",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, H),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], hyd[4], hyd[5], ring[6], ring[7], hyd[6], hyd[7],
                    ring[8], ring[9], hyd[8], hyd[9], ring[10], ring[11], hyd[10], hyd[11]),
                intArrayOf(0, 2, 2, 4, 4, 6, 6, 8, 8, 10, 10, 0,
                    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
                floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
                aromaticRingR = 38f))
        }
        // 甲苯：苯环 + 甲基
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{7}H_{8}", "甲苯",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, H, C),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], hyd[4], hyd[5], ring[6], ring[7], hyd[6], hyd[7],
                    ring[8], ring[9], ring[10], ring[11],
                    150f, 0f, 190f, -40f, 190f, 40f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 8, 12),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f),
                aromaticRingR = 38f))
        }
        // 苯酚：苯环 + 羟基
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{6}H_{5}OH", "苯酚",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, O),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], hyd[4], hyd[5], ring[6], ring[7], hyd[6], hyd[7],
                    ring[8], ring[9], ring[10], ring[11],
                    150f, 0f, 204f, 0f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 8, 11),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f),
                aromaticRingR = 38f))
        }
        // 苯甲醛：苯环 + 醛基
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{7}H_{6}O", "苯甲醛",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, C, O),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], hyd[4], hyd[5], ring[6], ring[7], hyd[6], hyd[7],
                    ring[8], ring[9], ring[10], ring[11],
                    150f, 0f, 150f, -58f, 204f, 0f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 8, 11, 11, 12),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
                aromaticRingR = 38f))
        }
        // 苯乙烯：苯环 + 乙烯基
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{8}H_{8}", "苯乙烯",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, C, C, H, H),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], hyd[4], hyd[5], ring[6], ring[7], hyd[6], hyd[7],
                    ring[8], ring[9], ring[10], ring[11],
                    150f, 0f, 204f, 0f, 258f, 0f, 258f, -45f, 258f, 45f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 8, 11, 11, 12, 12, 13, 12, 14),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
                aromaticRingR = 38f))
        }
        // 苯胺：苯环 + 氨基
        run {
            val ring = hex(); val hyd = hexH()
            add(MoleculeDef("C_{6}H_{5}NH_{2}", "苯胺",
                byteArrayOf(C, H, C, H, C, H, C, H, C, H, C, N, H),
                floatArrayOf(ring[0], ring[1], hyd[0], hyd[1], ring[2], ring[3], hyd[2], hyd[3],
                    ring[4], ring[5], hyd[4], hyd[5], ring[6], ring[7], hyd[6], hyd[7],
                    ring[8], ring[9], ring[10], ring[11],
                    150f, 0f, 150f, -50f, 150f, 50f),
                intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 8, 11, 11, 12),floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
                aromaticRingR = 38f))
        }
        add(MoleculeDef("PtCl_{2}(NH_{3})_{2}", "顺铂",
            byteArrayOf(Pt, Cl, Cl, N, N, H, H, H, H, H, H),
            floatArrayOf(0f, 0f, -70f, 0f, 70f, 0f, 0f, -70f, 0f, 70f,
                -42f, -98f, 42f, -98f, -42f, 98f, 42f, 98f, -52f, -46f, 52f, -46f),
            intArrayOf(0, 1, 0, 2, 0, 3, 0, 4, 3, 5, 3, 6, 4, 7, 4, 8, 3, 9, 3, 10),
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
    }
}

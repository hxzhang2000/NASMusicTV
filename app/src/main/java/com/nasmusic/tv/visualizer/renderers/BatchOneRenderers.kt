package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.cos
import kotlin.math.sin

/**
 * E26 `VECTOR_WAVES` — 声弦 · 线性声波
 *
 * 视觉：纯黑背景上数十根平行细线构成整个屏幕，音乐播放时线条随频率起伏变形。
 * 低音驱动大幅波浪（心电图式），高频驱动细密锯齿扰动。
 *
 * 实现：每根线采样 [AudioFrame.waveform] 的一个相位窗口（相邻线错开窗口起点，
 * 形成"波浪扫过线阵"的空间相位感）；叠加以 [AudioFrame.spectrum] 高频桶调制的
 * 细密正弦扰动（spectrum 高频 bin 数值本身提供锯齿般的细碎抖动）。
 *
 * 性能红线：draw 内零分配。所有线段写入两条复用 Path（辉光层 + 主线层共用
 * 同一条 Path，两次描边），一次 drawPath 画完全部线。
 */
class VectorWavesRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.VECTOR_WAVES

    /** 线条颜色：取封面色，进入时由 accent 提亮 */
    private var lineColor = Color.White

    /** 复用 Path（所有线段合成一条，两次描边出辉光 + 主线） */
    private val path = Path()

    /** 各线的基础间距相位（onEnter 时预生成，跨帧不变） */
    private var linePhase: FloatArray? = null
    private var lineCount = 0

    /** 平滑后的低音（波浪幅度用，逐帧 EMA 防跳动） */
    private var bassSmooth = 0f
    /** 平滑后的高频（锯齿扰动幅度用） */
    private var trebleSmooth = 0f

    override fun onEnter(ctx: RenderContext) {
        bassSmooth = 0f
        trebleSmooth = 0f
        linePhase = null
        lineCount = 0
    }

    private fun ensureLines(qualityCount: Int) {
        if (linePhase != null && lineCount == qualityCount) return
        lineCount = qualityCount
        val arr = FloatArray(qualityCount)
        for (i in 0 until qualityCount) arr[i] = i * 0.618f
        linePhase = arr
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        // ── 画质分档：LOW 12 根 / MEDIUM 20 根 / HIGH 28 根 ──
        val targetCount = when (ctx.quality) {
            VisualQuality.LOW -> 12
            VisualQuality.MEDIUM -> 20
            VisualQuality.HIGH -> 28
        }
        ensureLines(targetCount)

        // ── 封面色（accent 直接用，无需提亮——脉冲用 Plus 混合自然提亮）──
        lineColor = ctx.palette.accent

        // ── 律动信号平滑（EMA，避免逐帧跳变）──────────────────
        bassSmooth += (frame.bass - bassSmooth) * 0.18f
        trebleSmooth += (frame.treble - trebleSmooth) * 0.25f

        val n = lineCount
        val gap = h / (n + 1f)
        val amp = h * 0.16f                       // 波浪最大振幅
        val tSec = frame.timeMs * 0.001f

        path.reset()

        val phases = linePhase!!
        var li = 0
        while (li < n) {
            val baseY = gap * (li + 1)
            // 每根线采样波形的不同相位窗口：窗口中心随线序号移动
            val waveCenter = ((li * 37) % frame.waveform.size)
            val waveAmp = amp * (0.35f + 0.65f * bassSmooth)
            // 空间调制：每根线的振幅由频谱对应桶加权（低频桶幅度大）
            val band = frame.spectrum[(li * 7) % frame.spectrum.size]
            val jitter = trebleSmooth * band * 6f     // 高频锯齿扰动幅度（px）
            val phase = phases[li]

            // 横向采样：64 步走完整根线（LOW 档 48 步）
            val steps = if (ctx.quality == VisualQuality.LOW) 48 else 64
            var s = 0
            while (s <= steps) {
                val u = s.toFloat() / steps
                val x = u * w
                // 主波浪：波形采样（环形索引，相邻线相位错开）
                val wi = (waveCenter + (u * 40f).toInt()) % frame.waveform.size
                val wy = frame.waveform[wi]
                // 高频细密锯齿：正弦 × 频谱高频桶调制
                val ripple = sin(u * 37f + phase + tSec * 2.4f) * jitter
                val y = baseY + wy * waveAmp + ripple
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                s++
            }
            li++
        }

        // ── 绘制：辉光层（宽淡 Plus）+ 主线层（细亮）────────────
        drawPath(
            path, lineColor,
            style = Stroke(width = w * 0.004f + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            alpha = 0.16f, blendMode = BlendMode.Plus
        )
        drawPath(
            path, lineColor,
            style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            alpha = 0.85f, blendMode = BlendMode.Plus
        )
        // 静音时也不至于全黑：极弱基础亮度已由 0.85 alpha 主线保证（waveform 全 0 时是直线阵）
    }
}

/**
 * E27 `PULSING_POLYGONS` — 几何环 · 动态几何环
 *
 * 视觉：屏幕中央线框多边形（三角/六边/八边）+ 同心圆环，无填充。
 * 低音让几何环产生规律脉冲缩放（心跳感）；中高频让几何环缓慢旋转，
 * 边缘顶点随频谱做断点闪烁（跳过绘制 = 天然断点）。
 *
 * 实现：
 *  - 脉冲缩放用 [AudioFrame.pulse]（快起慢落包络，E24 已验证的心跳主力）；
 *  - 旋转角速度 = 平滑 treble 积分（不能直接映射角度——高频抖动会让旋转一顿一顿）；
 *  - 断点闪烁：每个顶点按 spectrum 对应桶做「跳过/保留」绘制，顶点间画成短段。
 *
 * 性能红线：draw 内零分配。多边形顶点复用 FloatArray，Path 复用。
 */
class PulsingPolygonsRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.PULSING_POLYGONS

    private var accentColor = Color.White
    private val path = Path()

    /** 旋转角速度积分（rad）——由平滑 treble 驱动 */
    private var angle = 0f
    private var trebleSmooth = 0f
    private var lastMs = 0L

    /** 顶点缓冲（预分配，最大 8 边形 × 3 层） */
    private val vx = FloatArray(8)
    private val vy = FloatArray(8)

    override fun onEnter(ctx: RenderContext) {
        angle = 0f
        trebleSmooth = 0f
        lastMs = 0L
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now

        // ── 封面色 ──
        accentColor = ctx.palette.accent

        // ── 旋转积分：treble 平滑后作为角速度 ──
        trebleSmooth += (frame.treble - trebleSmooth) * 0.15f
        angle += (0.12f + trebleSmooth * 1.6f) * dtSec
        if (angle > TAU) angle -= TAU

        // ── 脉冲缩放：pulse 快起慢落 → 心跳感 ──
        val pulseScale = 1f + frame.pulse * 0.22f

        val cx = w * 0.5f
        val cy = h * 0.5f
        val baseR = ctx.minDim * 0.30f

        // ── 三层：外八边形 / 中六边形 / 内三角 ──
        drawPolygonLayer(
            cx, cy, baseR * 1.15f * pulseScale, 8, angle * 0.7f,
            frame, accentColor, alpha = 0.9f, strokeW = 2.2f
        )
        drawPolygonLayer(
            cx, cy, baseR * 0.78f * pulseScale, 6, -angle * 1.1f,
            frame, accentColor, alpha = 0.75f, strokeW = 1.8f
        )
        drawPolygonLayer(
            cx, cy, baseR * 0.45f * pulseScale, 3, angle * 1.6f,
            frame, accentColor, alpha = 0.6f, strokeW = 1.5f
        )

        // ── 同心圆环 × 2（最低音桶驱动半径微缩放）──
        val ringPulse = 1f + frame.bass * 0.10f
        drawCircle(accentColor, radius = baseR * 0.62f * ringPulse, center = Offset(cx, cy),
            style = Stroke(1.2f), alpha = 0.45f)
        drawCircle(accentColor, radius = baseR * 1.02f * ringPulse, center = Offset(cx, cy),
            style = Stroke(1.2f), alpha = 0.35f)
    }

    /**
     * 画一层线框多边形，顶点按 spectrum 分桶做断点闪烁。
     * 顶点值低于阈值时该顶点被跳过 → 与相邻顶点的连线断开（断点效果）。
     */
    private fun DrawScope.drawPolygonLayer(
        cx: Float, cy: Float, radius: Float, sides: Int, rot: Float,
        frame: AudioFrame, color: Color, alpha: Float, strokeW: Float
    ) {
        // 顶点亮度 = spectrum 对应桶（几何断点闪烁的核心）
        var i = 0
        while (i < sides) {
            val a = rot + i * TAU / sides
            vx[i] = cx + cos(a) * radius
            vy[i] = cy + sin(a) * radius
            i++
        }
        path.reset()
        var j = 0
        while (j < sides) {
            val k = (j + 1) % sides
            // 顶点 j 的亮度决定 j→k 这条边是否绘制（断点闪烁）
            val bin = (j * frame.spectrum.size / sides).coerceIn(0, frame.spectrum.size - 1)
            val v = frame.spectrum[bin]
            val flickerOn = v > BREAKPOINT_THRESHOLD ||
                (v > BREAKPOINT_FAINT && ((frame.seq + j) and 1L) == 0L)
            if (flickerOn) {
                if (path.isEmpty) path.moveTo(vx[j], vy[j]) else path.lineTo(vx[j], vy[j])
                path.lineTo(vx[k], vy[k])
            }
            j++
        }
        // 闭合问题：跳段时 Path 不闭合，天然断点；全亮时最后一段连回起点（k=0 即回起点）
        drawPath(
            path, color,
            style = Stroke(strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
            alpha = alpha, blendMode = BlendMode.Plus
        )
    }

    private companion object {
        const val TAU = (2 * Math.PI).toFloat()
        const val BREAKPOINT_THRESHOLD = 0.10f
        const val BREAKPOINT_FAINT = 0.04f
    }
}

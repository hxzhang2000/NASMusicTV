package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.cos
import kotlin.math.sin

/**
 * 柱状频谱基类：提供镜像展开、三层辉光、峰值帽与残影包络。
 *
 * **关于"拖尾残影"（技法 T1）**：Compose Canvas 每帧自动清空，
 * 真正保留帧缓冲需额外 ImageBitmap（见 [MilkdropRenderer]）。
 * 此处改用**残影包络**：维护一根 env[] 柱子，快起慢落，
 * 视觉上等价拖尾，且零分配、零填充率开销。
 */
abstract class BarSpectrumRenderer : VisualizerRenderer {

    protected var env = FloatArray(128)
    protected var lastPeak = 0f

    override fun onEnter(ctx: RenderContext) {
        if (env.size < ctx.quality.barCount) env = FloatArray(ctx.quality.barCount)
        env.fill(0f)
        lastPeak = 0f
    }

    /** 更新残影包络（快起慢落） */
    protected fun updateEnv(frame: AudioFrame, n: Int, decay: Float = 0.82f) {
        for (i in 0 until n) {
            val v = frame.spectrum[i]
            env[i] = if (v >= env[i]) v else maxOf(v, env[i] * decay)
        }
    }

    /** 镜像展开：低频居中，向两侧递减 → 对称饱满 */
    protected fun mirrored(i: Int, half: Int): Float {
        val src = if (i < half) i else (half * 2 - 1 - i)
        return env.getOrElse(src) { 0f }
    }
}

// ═══════════════════════════════════════════════════════════════════
// E01 沉浸辉光
// ═══════════════════════════════════════════════════════════════════

/**
 * E01 `IMMERSIVE_BLOOM` — 沉浸辉光
 *
 * 柱状频谱 + 镜像对称 + 倒影 + 三层辉光 + 峰值帽。
 * 效果库的基准实现，最稳妥的通用款。
 */
class BloomRenderer : BarSpectrumRenderer() {

    override val theme = VisualizerTheme.IMMERSIVE_BLOOM

    // 复用 Path：辉光最多 3 层 × 1 + 主体 + 倒影 + 峰值帽 → 每帧 6 次 drawPath
    // 替代原 ~768 次 drawRoundRect/drawRect（同样的"大批量合并"范式见 E13 液态网格）
    private val glowPaths = Array(3) { Path() }
    private val barPath = Path()
    private val reflPath = Path()
    private val capPath = Path()

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val n = ctx.quality.barCount
        updateEnv(frame, n)

        val w = size.width
        val h = size.height
        val half = n / 2
        val total = half * 2
        val slot = w / total
        val barW = slot * 0.74f
        val radius = barW * 0.4f
        val maxH = h * 0.70f * (1f + frame.pulse * 0.18f)
        val baseline = h * 0.76f
        val accent = ctx.palette.accent
        val glowLayers = ctx.quality.glowLayers
        val capColor = VisualizerMath.towardWhite(accent, 0.65f)

        // 每帧无条件重置 Path（复用实例、零分配，见 E13 液态网格范式）
        for (p in glowPaths) p.reset()
        barPath.reset()
        reflPath.reset()
        capPath.reset()

        var iMin = Int.MAX_VALUE
        var iMax = -1
        for (i in 0 until total) {
            val v = mirrored(i, half)
            val minH = maxH * 0.04f * (0.2f + frame.energy * 0.8f)
            val wave = sin(i * 0.3f + frame.timeMs * 0.003f) * maxH * 0.025f * (0.3f + frame.energy * 0.7f)
            val bh = VisualizerMath.barHeight(v, maxH, minH) + wave
            val x = i * slot + (slot - barW) / 2

            // 3 层辉光：宽淡 → 窄亮（每层同色同 alpha，并入对应 Path）
            for (layer in 0 until glowLayers) {
                val spread = (glowLayers - layer) * slot * 0.22f
                val gx = x - spread / 2
                glowPaths[layer].addRoundRect(
                    RoundRect(gx, baseline - bh, gx + barW + spread, baseline, CornerRadius(radius, radius))
                )
            }
            // 主体
            barPath.addRoundRect(RoundRect(x, baseline - bh, x + barW, baseline, CornerRadius(radius, radius)))
            // 倒影
            reflPath.addRoundRect(
                RoundRect(x, baseline + 4f, x + barW, baseline + 4f + bh * 0.35f, CornerRadius(radius, radius))
            )
            // 峰值帽（快升慢降的残影包络顶点）
            if (v > 0.02f) {
                capPath.addRect(Rect(x, baseline - bh - 6f, x + barW, baseline - bh - 3f))
                if (i < iMin) iMin = i
                if (i > iMax) iMax = i
            }
        }

        // 一次性绘制全部（比原 ~768 次独立绘制合并为 6 次）
        for (layer in 0 until glowLayers) {
            val alpha = (0.16f + frame.pulse * 0.40f) / (layer + 1)
            drawPath(glowPaths[layer], accent, alpha = alpha)
        }
        drawPath(barPath, accent, alpha = 1f)
        drawPath(reflPath, accent, alpha = 0.30f)
        if (iMin <= iMax) {
            drawPath(capPath, capColor, alpha = 0.9f)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// E03 隧道穿越
// ═══════════════════════════════════════════════════════════════════

/**
 * E03 `TUNNEL_FLY` — 隧道穿越
 *
 * 同心环沿 Z 轴迎面飞来。WebGL 霓虹隧道的 2D 平替，性价比最高。
 */
class TunnelRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.TUNNEL_FLY

    private var offset = 0f
    // 频谱驱动的小圆点按 z 深度分 3 桶合并（保留逐环 fade，否则丢纵深），
    // 替代最多 ~192 次独立 drawCircle；3 次 drawPath 换取原视觉层次
    private val dotPaths = Array(3) { Path() }

    override fun onEnter(ctx: RenderContext) { offset = 0f }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        offset += 1.2f + frame.bass * 6f
        if (frame.beat) offset += 8f

        val cx = size.width / 2
        val cy = size.height / 2
        val baseR = ctx.minDim * 0.36f
        val maxZ = RINGS * 60f
        val accent = ctx.palette.accent

        // 频谱驱动的小圆点：按 z 深度分 3 桶合并（桶中心 alpha 近似原 fade*0.7f）
        for (p in dotPaths) p.reset()
        val dotColor = VisualizerMath.towardWhite(accent, 0.4f + frame.pulse * 0.4f)
        for (i in 0 until RINGS) {
            var z = (i * 60f + offset) % maxZ
            if (z < 1f) z = 1f
            val s = VisualizerMath.scaleAt(z, 320f)
            val fade = 1f - z / maxZ
            if (fade <= 0.02f) continue

            val r = baseR * s
            drawCircle(
                color = accent,
                radius = r,
                center = Offset(cx, cy),
                alpha = fade * (0.5f + frame.treble * 0.5f),
                style = Stroke(width = (3f * s).coerceIn(0.6f, 4f))
            )
            // 频谱驱动的隧道壁起伏
            if (i % 3 == 0) {
                val n = 24
                for (k in 0 until n) {
                    val a = k * 6.2831853f / n
                    val v = frame.spectrum.getOrElse((k * 2) % frame.spectrum.size) { 0f }
                    val rr = r * (1f + v * 0.18f)
                    val px = cx + cos(a) * rr
                    val py = cy + sin(a) * rr
                    val rDot = 3f * s + v * 3f
                    dotPaths[(fade * 3f).toInt().coerceIn(0, 2)].addOval(
                        Rect(px - rDot, py - rDot, px + rDot, py + rDot))
                }
            }
        }
        // 恢复原始的 SrcOver：合并前是逐点 drawCircle，没有 Plus 叠加（Plus 会让重叠处过曝）
        for (b in 0 until 3) {
            drawPath(dotPaths[b], dotColor, alpha = (b + 0.5f) / 3f * 0.7f)
        }
    }

    private companion object { const val RINGS = 24 }
}

// ═══════════════════════════════════════════════════════════════════
// E05 圆形频谱环（默认主题）
// ═══════════════════════════════════════════════════════════════════

/**
 * E05 `CIRCULAR_RING` — 圆形频谱环（默认主题）
 *
* 中心霓虹圆盘 → 频谱条紧贴圆盘**向外辐射** → 外围细线环 → **长条刺破外圈**。
 * 「约束 + 突破」的张力：常态被环收住，鼓点一来穿刺而出。
 */
class CircularRingRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.CIRCULAR_RING

    private var rotation = 0f
    private var peaks = FloatArray(64)
    // 峰值帽按 hue 分 8 桶合并为 Path，替代 ~64 次 drawCircle。
    // 桶数越多色差越小：8 桶时与所在条的 hue 最多差约 8°，肉眼基本无色阶断层
    private val peakPaths = Array(8) { Path() }

    override fun onEnter(ctx: RenderContext) {
        rotation = 0f
        if (peaks.size < ctx.quality.barCount) peaks = FloatArray(ctx.quality.barCount)
        peaks.fill(0f)
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        rotation += 0.15f + frame.bass * 0.9f
        if (frame.beat) rotation += 2.5f

        val D = ctx.minDim
        val cx = size.width / 2
        val cy = size.height / 2
        // 基准半径（不含节拍缩放）——整体放大 1.2×
        val rCover = D * 0.16f
        val rStart = rCover + D * 0.008f
        val rRing = D * 0.36f
        val maxLen = (rRing - rStart) * 1.5f      // 能量 0.67 时触环，>0.67 刺破
        val scale = 1f + frame.pulse * 0.08f

        val n = ctx.quality.barCount
        for (p in peakPaths) p.reset()

        // 用户指定色系：hue 随角度流动：黄(60°)→绿(90°)→蓝(195°)，加白色高光
        val baseHue0 = 60f
        val baseHue1 = 195f
        val sat = 1.0f
        val lit = 0.55f + frame.pulse * 0.30f

        // ① 频谱条（画在环之下）
        for (i in 0 until n) {
            val a = VisualizerMath.rad(i * 360f / n + rotation)
            val v = frame.spectrum.getOrElse(i) { 0f }
            val len = VisualizerMath.barHeight(v, maxLen, 3f) * scale
            val inner = VisualizerMath.polar(cx, cy, rStart, a)
            val outer = VisualizerMath.polar(cx, cy, rStart + len, a)
            val cosA = cos(VisualizerMath.rad(i * 360f / n + rotation))
            val sinA = sin(VisualizerMath.rad(i * 360f / n + rotation))
            val wdt = 3f + frame.bass * 3f

            // 本根条的角度占比 → 渐变 hue；亮度随音量微调
            val t = (i.toFloat() / n).coerceIn(0f, 1f)
            val hue = VisualizerMath.hueGradient(baseHue0, baseHue1, t)
            val barColor = VisualizerMath.hsl(hue, sat, lit)
            val highlight = VisualizerMath.hsl(hue, sat, minOf(lit + 0.25f, 1f))

            val outerR = rStart + len
            if (outerR > rRing) {
                // 分段：环内段 + 刺出段（更细更亮，锐利如针）
                val t2 = ((rRing - rStart) / len).coerceIn(0f, 1f)
                val split = Offset(cx + cosA * (rStart + len * t2), cy + sinA * (rStart + len * t2))
                for (l in 0 until ctx.quality.glowLayers) {
                    val sp = (ctx.quality.glowLayers - l) * 2.5f
                    drawLine(barColor, inner, split, wdt + sp, StrokeCap.Round,
                        alpha = (0.08f + frame.pulse * 0.22f) / (l + 1))
                }
                drawLine(barColor, inner, split, wdt, StrokeCap.Round, alpha = 0.95f)
                drawLine(highlight, split, outer,
                    wdt * 0.6f, StrokeCap.Round, alpha = 1f)
            } else {
                for (l in 0 until ctx.quality.glowLayers) {
                    val sp = (ctx.quality.glowLayers - l) * 2.5f
                    drawLine(barColor, inner, outer, wdt + sp, StrokeCap.Round,
                        alpha = (0.08f + frame.pulse * 0.22f) / (l + 1))
                }
                drawLine(barColor, inner, outer, wdt, StrokeCap.Round, alpha = 0.95f)
            }

            // 峰值帽（极坐标版）——近似所在条的颜色，按 hue 分 8 桶合并为 Path
            peaks[i] = if (v >= peaks[i]) v else maxOf(v, peaks[i] * 0.985f - 0.004f)
            val pr = rStart + VisualizerMath.barHeight(peaks[i], maxLen, 3f) * scale
            val bucket = ((t * 8).toInt()).coerceIn(0, 7)
            peakPaths[bucket].addOval(Rect(
                cx + cosA * pr - 2.5f, cy + sinA * pr - 2.5f,
                cx + cosA * pr + 2.5f, cy + sinA * pr + 2.5f
            ))
        }

        // ①.5 峰值帽 → 8 条 Path 一次绘制（颜色按桶内中间 hue）
        for (s in 0 until 8) {
            val hue = baseHue0 + (s + 0.5f) * (baseHue1 - baseHue0) / 8f
            drawPath(peakPaths[s],
                VisualizerMath.hsl(hue, sat, minOf(lit + 0.25f, 1f)), alpha = 0.75f)
        }

        // ② 外圈细线环 —— 渐变环（用四个扇区补角度渐变，比单色更炫）
        val ringR = rRing * (1f + frame.energy * 0.02f) * scale
        drawGradientRing(cx, cy, ringR, baseHue0, baseHue1, sat, lit,
            alpha = 0.30f + frame.pulse * 0.45f,
            width = 1.5f + frame.pulse * 1.5f)

        // ③ 节拍爆环 —— 亮白冲击感
        if (frame.beat) {
            drawCircle(VisualizerMath.hsl(60f, 1.0f, 0.98f),
                ringR * (1f + frame.pulse * 0.4f), Offset(cx, cy),
                alpha = 0.65f, style = Stroke(width = 4.5f))
        }

        // ④ 中央霓虹圆盘（去封面展示：实心渐变圆 + 光晕，随能量变色）
        val coverR = rCover * (1f + frame.pulse * 0.03f)
        val glowHue = VisualizerMath.hueGradient(baseHue0, baseHue1, (frame.energy * 0.7f).coerceIn(0f, 1f))
        drawCircle(VisualizerMath.hsl(glowHue, sat, 0.40f), coverR * 1.12f, Offset(cx, cy),
            alpha = 0.40f + frame.pulse * 0.3f)
        drawCircle(VisualizerMath.hsl(glowHue, sat, lit), coverR, Offset(cx, cy),
            alpha = 0.60f + frame.pulse * 0.3f)
        drawCircle(VisualizerMath.towardWhite(VisualizerMath.hsl(glowHue, sat, lit), 0.45f),
            coverR * 0.55f, Offset(cx, cy), alpha = 0.65f + frame.energy * 0.35f)
    }

    /** 渐变细线环：把环按 8 个扇区平分 hue，逐段描线形成周向渐变 */
    private fun DrawScope.drawGradientRing(
        cx: Float, cy: Float, r: Float,
        hue0: Float, hue1: Float, sat: Float, lit: Float,
        alpha: Float, width: Float
    ) {
        val segments = 8
        for (s in 0 until segments) {
            val a0 = s * 360f / segments
            val a1 = (s + 1) * 360f / segments
            val tMid = (s + 0.5f) / segments
            val hue = VisualizerMath.hueGradient(hue0, hue1, tMid)
            val c = VisualizerMath.hsl(hue, sat, minOf(lit + 0.15f, 1f))
            drawArc(
                color = c,
                startAngle = a0,
                sweepAngle = 360f / segments,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2f, r * 2f),
                alpha = alpha,
                style = Stroke(width = width)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// E06 径向星芒
// ═══════════════════════════════════════════════════════════════════

/**
 * E06 `RADIAL_BURST` — 径向星芒
 *
 * 从中心放射 N 条线，长度=对应频段能量，末端加圆点。极简但干净。
 */
class RadialBurstRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.RADIAL_BURST

    // 端点圆点按频谱值分 3 桶合并（保留 alpha 随 v 变化），替代 ~128 次 drawCircle
    private val tipPaths = Array(3) { Path() }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r0 = ctx.minDim * 0.13f
        val maxLen = ctx.minDim * 0.40f * (1f + frame.pulse * 0.05f)
        val accent = ctx.palette.accent
        val n = ctx.quality.barCount

        for (p in tipPaths) p.reset()
        for (i in 0 until n) {
            val a = VisualizerMath.rad(i * 360f / n)
            val v = frame.spectrum.getOrElse(i) { 0f }
            val len = VisualizerMath.barHeight(v, maxLen, 6f)
            val p0 = VisualizerMath.polar(cx, cy, r0, a)
            val p1 = VisualizerMath.polar(cx, cy, r0 + len, a)
            drawLine(accent, p0, p1, 2.5f + v * 3.5f, StrokeCap.Round, alpha = 0.95f)
            // 端点圆 → 按 v 分桶合并（原 alpha = 0.6f + v * 0.4f）
            val r = 4f + frame.bass * 6f
            tipPaths[(v.coerceIn(0f, 0.999f) * 3f).toInt()].addOval(
                Rect(p1.x - r, p1.y - r, p1.x + r, p1.y + r))
        }
        for (b in 0 until 3) {
            drawPath(tipPaths[b], VisualizerMath.towardWhite(accent, 0.6f),
                alpha = 0.6f + (b + 0.5f) / 3f * 0.4f)
        }
        drawCircle(accent, ctx.minDim * 0.055f * (1f + frame.pulse * 0.25f),
            Offset(cx, cy), alpha = 0.6f + frame.energy * 0.4f)
    }
}

// ═══════════════════════════════════════════════════════════════════
// E07 频率山峦
// ═══════════════════════════════════════════════════════════════════

/**
 * E07 `FREQUENCY_MOUNTAIN` — 频率山峦
 *
 * 半透明多层填充面积图叠加，极光/山峦感（比 E02 更柔和、更氛围）。
 */
class FrequencyMountainRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.FREQUENCY_MOUNTAIN

    private val path = Path()

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        val accent = ctx.palette.accent
        val secondary = ctx.palette.secondary
val layers = 5
        val baseY = h * 0.76f
        val n = ctx.quality.barCount

        for (l in 0 until layers) {
            val t = l.toFloat() / layers
            val col = if (l % 2 == 0) accent else secondary
            val yOff = l * h * 0.025f
            val amp = h * 0.38f * (1f - t * 0.25f) * (1f + frame.bass * 0.5f)

            path.reset()
            for (i in 0 until n) {
                val x = w * i / (n - 1).coerceAtLeast(1)
                val v = frame.spectrum.getOrElse(i) { 0f }
                val y = baseY - yOff - v * amp
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.lineTo(w, baseY + h)
            path.lineTo(0f, baseY + h)
            path.close()
drawPath(path, col, alpha = 0.16f)
            drawPath(path, col, alpha = 0.14f, style = Stroke(width = 2f))
        }
    }
}

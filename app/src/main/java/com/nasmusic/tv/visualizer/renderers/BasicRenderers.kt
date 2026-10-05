package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.fx.FxLevel
import com.nasmusic.tv.visualizer.fx.Shading2D
import kotlin.math.cos
import kotlin.math.sin
// ═══════════════════════════════════════════════════════════════════
// E05 圆形频谱环（默认主题）
// ═══════════════════════════════════════════════════════════════════

/**
 * E05 `CIRCULAR_RING` — 圆形频谱环（默认主题）
 *
* 中心霓虹圆盘 → 频谱条紧贴圆盘**向外辐射** → 外围细线环 → **长条刺破外圈**。
 * 「约束 + 突破」的张力：常态被环收住，鼓点一来穿刺而出。
 */
class CircularRingRenderer : RendererFx() {

    override val theme = VisualizerTheme.CIRCULAR_RING

    // §A2-6：收尾后处理（§13.5-D9：暗角 ≥ 0.42 下限，取 0.44）
    override val postFx = PostFx(vignette = 0.44f, grain = 0.028f)

    private var rotation = 0f
    private var peaks = FloatArray(64)
    // 峰帽按 hue 分 8 桶合并为 Path，替代 ~64 次 drawCircle。
    // 桶数越多色差越小：8 桶时与所在条的 hue 最多差约 22.5°/2，肉眼基本无色阶断层
    private val peakPaths = Array(8) { Path() }
    // §A2-5：仅 hue 最高档（蓝端）的峰帽有辉光 —— 单独 1 条 Path（单色可行；8 桶全加
    // 则 8 个 alpha 无法共用一条 Path，另开 8 条又超预算 —— §A2 选项 ②，draw 8 → 9）
    private val peakGlowPath = Path()
    // §A2-3：sweep 渐变环 Brush 按 (w,h) 缓存（sweepGradient 构造分配 List，⛔ 不可每帧）
    private val ringBrushCache = SizeCache()
    // §A2-4：中心盘径向渐变 Brush 按 (w,h,量化hue) 缓存 —— hue 连续值会打爆缓存，
    // 量化到 12 档（相邻档差 30°，能量渐变时逐帧跨档 ≈ 平滑；同档内零分配）
    /** sweep 环 Brush 的构造器（构造期捕获常量 ⇒ get() 调用点零 lambda 分配，§15.4-A4） */
    private val ringBrushBuilder: (Float, Float) -> Brush = { bw, bh ->
        val stops = Array(9) { k: Int ->
            val tt = k / 8f
            val hh = VisualizerMath.hueGradient(60f, 195f, tt)
            tt to VisualizerMath.hsl(hh, 1.0f, 0.78f)
        }
        Brush.sweepGradient(*stops, center = Offset(bw / 2f, bh / 2f))
    }

    override fun onEnterContent(ctx: RenderContext) {
        rotation = 0f
        if (peaks.size < ctx.quality.barCount) peaks = FloatArray(ctx.quality.barCount)
        peaks.fill(0f)
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        rotation += 0.15f + frame.bass * 0.9f
        if (frame.beat) rotation += 2.5f

        val D = ctx.minDim
        val w = size.width
        val h = size.height
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
        peakGlowPath.reset()

        // 用户指定色系：hue 随角度流动：黄(60°)→绿(90°)→蓝(195°)，加白色高光
        val baseHue0 = 60f
        val baseHue1 = 195f
        val sat = 1.0f
        val lit = 0.55f + frame.pulse * 0.30f

        // ① 频谱条（画在环之下）—— §A2-1 圆柱化 + §A2-2 假光晕删除
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
            // §A2-1：圆柱 = 沿切向错开 ±0.35×wdt 的暗侧/亮侧两笔（G2①「沿法线明暗」的 2-draw 等价）
            val tx = -sinA
            val ty = cosA
            val toff = wdt * 0.35f
            val darkCol = VisualizerMath.hsl(hue, sat, (lit - 0.22f).coerceAtLeast(0.04f))
            val litCol = VisualizerMath.hsl(hue, sat, minOf(lit + 0.20f, 1f))

            val outerR = rStart + len
            val barEnd = if (outerR > rRing) {
                val t2 = ((rRing - rStart) / len).coerceIn(0f, 1f)
                VisualizerMath.polar(cx, cy, rStart + len * t2, a)
            } else outer
            // §A2-2：原 glowLayers 循环（同色加宽描边 ×N，G5 假光晕）整段删除 ⇒
            // 换 1 次宽笔低 alpha 光晕；⛔ LOW 档不画（省 draw）
            if (fx.level != FxLevel.OFF) {
                drawLine(barColor, inner, barEnd, wdt * 2.6f, StrokeCap.Round,
                    alpha = 0.10f + frame.pulse * 0.18f)
            }
            drawLine(darkCol,
                Offset(inner.x - tx * toff, inner.y - ty * toff),
                Offset(barEnd.x - tx * toff, barEnd.y - ty * toff),
                wdt, StrokeCap.Round, alpha = 0.9f)
            drawLine(litCol,
                Offset(inner.x + tx * toff, inner.y + ty * toff),
                Offset(barEnd.x + tx * toff, barEnd.y + ty * toff),
                wdt, StrokeCap.Round, alpha = 1f)
            if (outerR > rRing) {
                // 刺破段保留（更细更亮，锐利如针）
                val split = barEnd
                drawLine(highlight, split, outer, wdt * 0.6f, StrokeCap.Round, alpha = 1f)
            }

            // 峰值帽（极坐标版）——近似所在条的颜色，按 hue 分 8 桶合并为 Path
            peaks[i] = if (v >= peaks[i]) v else maxOf(v, peaks[i] * 0.985f - 0.004f)
            val pr = rStart + VisualizerMath.barHeight(peaks[i], maxLen, 3f) * scale
            val bucket = ((t * 8).toInt()).coerceIn(0, 7)
            // T1.6.2（§四 G15）：float addOval 零 Rect 分配（真圆不变）
            peakPaths[bucket].asAndroidPath().addOval(
                cx + cosA * pr - 2.5f, cy + sinA * pr - 2.5f,
                cx + cosA * pr + 2.5f, cy + sinA * pr + 2.5f,
                android.graphics.Path.Direction.CCW)
            if (bucket == 7) {
                // §A2-5：最高档峰帽的辉光（同几何 5px 半径，低 alpha 单独一层）
                peakGlowPath.asAndroidPath().addOval(
                    cx + cosA * pr - 6f, cy + sinA * pr - 6f,
                    cx + cosA * pr + 6f, cy + sinA * pr + 6f,
                    android.graphics.Path.Direction.CCW)
            }
        }

        // ①.5 峰值帽 → 8 条 Path 一次绘制（颜色按桶内中间 hue）+ 最高档辉光层（压在帽下）
        drawPath(peakGlowPath,
            VisualizerMath.hsl((baseHue0 + baseHue1) / 2f, sat, 0.85f), alpha = 0.18f)
        for (s in 0 until 8) {
            val hue = baseHue0 + (s + 0.5f) * (baseHue1 - baseHue0) / 8f
            drawPath(peakPaths[s],
                VisualizerMath.hsl(hue, sat, minOf(lit + 0.25f, 1f)), alpha = 0.75f)
        }

        // ② 外圈细线环 —— §A2-3：8 段 drawArc 拼接 → 1 次 sweepGradient + Stroke
        //（段间色阶台阶消除，draw 8 → 1；Brush 按 (w,h) 缓存 ⛔ 不可每帧新建）
        val ringR = rRing * (1f + frame.energy * 0.02f) * scale
        val ringBrush = ringBrushCache.get(w, h, ringBrushBuilder)
        drawCircle(
            brush = ringBrush,
            radius = ringR,
            center = Offset(cx, cy),
            alpha = 0.30f + frame.pulse * 0.45f,
            style = Stroke(width = 1.5f + frame.pulse * 1.5f)
        )

        // ③ 节拍爆环 —— 亮白冲击感
        if (frame.beat) {
            drawCircle(VisualizerMath.hsl(60f, 1.0f, 0.98f),
                ringR * (1f + frame.pulse * 0.4f), Offset(cx, cy),
                alpha = 0.65f, style = Stroke(width = 4.5f))
        }

        // ④ 中央圆盘 —— §A2-4：3 层同心圆（同心台阶）→ 1 次径向渐变 + 0.5px 亮边
        // glowHue 连续依赖 energy ⇒ 量化 12 档进缓存键（连续值会打爆 Brush 缓存），
        // 呼吸感由 alpha 承担；「0.85r 透明」的收边由 shadeBrushCached 的 1f 端 darken 表达
        val glowHueRaw = VisualizerMath.hueGradient(baseHue0, baseHue1, (frame.energy * 0.7f).coerceIn(0f, 1f))
        val glowHue = (glowHueRaw / 30f).toInt().coerceIn(0, 11) * 30f
        val coverR = rCover * (1f + frame.pulse * 0.03f)
        // 键 = (w,h,量化hue) 三维；lit 恒 0.55（pulse 呼吸交给 alpha）⇒ 键空间 12 ≤ 16 槽
        val discBrush = Shading2D.shadeBrushCached(
            key = (w.toRawBits().toLong() shl 32) xor h.toRawBits().toLong() xor
                glowHue.toRawBits().toLong(),
            center = Offset(w / 2f, h / 2f),
            radius = minOf(w, h) * 0.20f,
            base = VisualizerMath.hsl(glowHue, sat, 0.55f),
            contrast = 0.50f)
        drawCircle(brush = discBrush, radius = coverR * 1.12f, center = Offset(cx, cy),
            alpha = 0.40f + frame.pulse * 0.3f)
        drawCircle(brush = discBrush, radius = coverR, center = Offset(cx, cy),
            alpha = 0.60f + frame.pulse * 0.3f)
        drawCircle(
            color = VisualizerMath.towardWhite(VisualizerMath.hsl(glowHue, sat, lit), 0.45f),
            radius = coverR, center = Offset(cx, cy),
            alpha = 0.65f + frame.energy * 0.35f,
            style = Stroke(width = 0.5f))

        // 后处理已由基类按 `postFx` 在 drawContent 之后统一施加（§A2-6）
    }

    /** 角向渐变细线环已由 §A2-3 的 sweepGradient 环取代（原 8 段 drawArc 版删除） */
}

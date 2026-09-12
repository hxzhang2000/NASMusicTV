package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.ImageBitmap
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

// ═══════════════════════════════════════════════════════════════════
// E10 万花筒
// ═══════════════════════════════════════════════════════════════════

/**
 * E10 `MIRROR_KALEIDO` — 万花筒
 *
 * 只绘制 1/8 扇区内容，然后旋转 45° 循环 8 次 + 镜像翻转。
 */
class KaleidoRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.MIRROR_KALEIDO

    private var rotation = 0f
    private var flip = false

    override fun onEnter(ctx: RenderContext) { rotation = 0f; flip = false }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        rotation += 0.2f + frame.bass * 1.2f
        if (frame.beat) flip = !flip

        val cx = size.width / 2
        val cy = size.height / 2
        val accent = ctx.palette.accent
        val n = ctx.quality.barCount
        val r0 = ctx.minDim * 0.10f
        val maxLen = ctx.minDim * 0.26f * (1f + frame.bass * 0.25f)
        val sectorBars = 16

        for (k in 0 until 8) {
            withTransform({
                translate(cx, cy)
                rotate(k * 45f + rotation)
                if (flip && k % 2 == 1) scale(-1f, 1f)
            }) {
                for (i in 0 until sectorBars) {
                    // 扇区内 0..45°
                    val a = VisualizerMath.rad(i * 45f / sectorBars)
                    val v = frame.spectrum.getOrElse((i * n / sectorBars).coerceAtMost(n - 1)) { 0f }
                    val len = VisualizerMath.barHeight(v, maxLen, 4f)
                    val p0 = Offset(cos(a) * r0, sin(a) * r0)
                    val p1 = Offset(cos(a) * (r0 + len), sin(a) * (r0 + len))
                    drawLine(accent, p0, p1, 2f + v * 4f, StrokeCap.Round, alpha = 0.85f)
                    drawCircle(VisualizerMath.towardWhite(accent, 0.4f), 2f + v * 4f, p1, alpha = 0.6f)
                }
            }
        }
        drawCircle(accent, ctx.minDim * 0.04f * (1f + frame.pulse * 0.3f),
            Offset(cx, cy), alpha = 0.45f + frame.energy * 0.4f)
    }
}

// ═══════════════════════════════════════════════════════════════════
// E11 星系螺旋
// ═══════════════════════════════════════════════════════════════════

/**
 * E11 `GALAXY_SPIRAL` — 星系螺旋
 *
 * 4 条对数螺旋臂 r = a·e^(bθ)；星点沿臂分布、越远角速度越慢（开普勒感）。
 */
class GalaxySpiralRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.GALAXY_SPIRAL

    private var rotation = 0f

    override fun onEnter(ctx: RenderContext) { rotation = 0f }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        rotation += 0.15f + frame.bpm / 1200f

        val cx = size.width / 2
        val cy = size.height / 2
        val a0 = ctx.minDim * 0.03f
        val b = 0.18f
        val accent = ctx.palette.accent
        val n = ctx.quality.barCount
        val perArm = if (ctx.quality == com.nasmusic.tv.data.model.VisualQuality.HIGH) 80 else 45
        val maxR = ctx.minDim * 0.42f

        for (arm in 0 until 4) {
            val armOffset = arm * 90f
            for (i in 0 until perArm) {
                val t = i.toFloat() / perArm
                val theta = t * 4.2f
                val r = a0 * kotlin.math.exp(b * theta * 6f)
                if (r > maxR) continue
                // 越远角速度越慢
                val ang = VisualizerMath.rad(armOffset + theta * 57.3f + rotation * (1f - t * 0.55f))
                val freqIdx = ((t * n).toInt()).coerceIn(0, n - 1)
                val v = frame.spectrum.getOrElse(freqIdx) { 0f }
                val p = VisualizerMath.polar(cx, cy, r, ang)
                drawCircle(
                    color = VisualizerMath.towardWhite(accent, v * 0.6f),
                    radius = 1f + v * 3.5f,
                    center = p,
                    alpha = 0.25f + v * 0.65f
                )
            }
        }
        drawCircle(accent, ctx.minDim * 0.06f * (1f + frame.energy * 0.45f),
            Offset(cx, cy), alpha = 0.4f + frame.pulse * 0.5f)
    }
}

// ═══════════════════════════════════════════════════════════════════
// E12 频谱瀑布
// ═══════════════════════════════════════════════════════════════════

/**
 * E12 `SPECTRO_WATERFALL` — 频谱瀑布
 *
 * 横轴=频率、纵轴=时间（自上而下滚动），能量→色相。
 *
 * **性能红线**：必须缓存为 ImageBitmap，每帧整体上移 + 底部画新行。
 * 禁止逐格 drawRect（60×64 = 3840 次调用必崩）。
 */
class WaterfallRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.SPECTRO_WATERFALL

    private var prev: ImageBitmap? = null
    private var curr: ImageBitmap? = null
    private val paint = androidx.compose.ui.graphics.Paint()
    private val rows = 200

    override fun onEnter(ctx: RenderContext) {
        prev?.let { /* 交由 GC 回收，切换效果时 onExit 已置空 */ }
        val w = 128
        prev = ImageBitmap(w, rows)
        curr = ImageBitmap(w, rows)
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val p = prev ?: return
        val c = curr ?: return

        // ① 把上一帧整体上移 1px 画到当前缓冲（乒乓，禁止自我绘制）
        val cb = androidx.compose.ui.graphics.Canvas(c)
        cb.drawImage(p, androidx.compose.ui.geometry.Offset(0f, -1f), paint)

        // ② 底部画新行：色相映射 低=深蓝 → 高=品红
        val n = ctx.quality.barCount
        val y = (rows - 1).toFloat()
        val cw = c.width.toFloat() / n
        for (i in 0 until n) {
            val v = frame.spectrum.getOrElse(i) { 0f }
            val hue = 220f - v * 220f
            paint.color = VisualizerMath.hsl(hue, 0.95f, 0.25f + v * 0.45f)
            cb.drawRect(
                androidx.compose.ui.geometry.Rect(i * cw, y, (i + 1) * cw, y + 1f),
                paint
            )
        }

        // ③ 铺满画布 + 交换缓冲
        drawImage(c, dstSize = androidx.compose.ui.unit.IntSize(
            size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)
        ))
        prev = c
        curr = p
    }

    override fun onExit() { prev = null; curr = null }
}

// ═══════════════════════════════════════════════════════════════════
// E13 液态网格
// ═══════════════════════════════════════════════════════════════════

/**
 * E13 `LIQUID_GRID` — 液态网格
 *
 * 网格顶点被三频正弦叠加推动，形成液体表面（波纹 shader 的 2D 离散平替）。
 * 密度按画质档位控制——这是本套效果唯一的性能风险点。
 */
class LiquidGridRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.LIQUID_GRID

    private var cols = 24
    private var rows = 14
    private var xs: FloatArray = FloatArray(0)
    private var ys: FloatArray = FloatArray(0)

    override fun onEnter(ctx: RenderContext) {
        cols = ctx.quality.gridCols
        rows = ctx.quality.gridRows
        val count = cols * rows
        if (xs.size < count) {
            xs = FloatArray(count)
            ys = FloatArray(count)
        }
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        if (xs.isEmpty()) onEnter(ctx)
        val w = size.width
        val h = size.height
        val t = frame.timeMs
        val accent = ctx.palette.accent
        val scale = 1f + (if (frame.beat) 0.06f else 0f)
        val drawLines = ctx.quality != com.nasmusic.tv.data.model.VisualQuality.LOW

        // 计算顶点位移
        var k = 0
        for (gy in 0 until rows) {
            for (gx in 0 until cols) {
                val x = gx.toFloat()
                val y = gy.toFloat()
                val wave =
                    kotlin.math.sin(x * 0.3f + t * 0.002f) * frame.bass * 30f +
                    kotlin.math.sin(y * 0.5f + t * 0.003f) * frame.mid * 20f +
                    kotlin.math.sin((x + y) * 0.8f + t * 0.01f) * frame.treble * 8f
                val px = (gx.toFloat() / (cols - 1).coerceAtLeast(1)) * w
                val py = (gy.toFloat() / (rows - 1).coerceAtLeast(1)) * h * 0.85f + h * 0.07f
                val cx0 = w / 2
                val cy0 = h / 2
                xs[k] = cx0 + (px - cx0) * scale + wave * 0.6f
                ys[k] = cy0 + (py - cy0) * scale + wave
                k++
            }
        }

        // 连线（低档跳过）
        if (drawLines) {
            for (gy in 0 until rows) {
                for (gx in 0 until cols) {
                    val i = gy * cols + gx
                    if (gx < cols - 1) {
                        drawLine(accent, Offset(xs[i], ys[i]), Offset(xs[i + 1], ys[i + 1]),
                            1f, alpha = 0.28f)
                    }
                    if (gy < rows - 1) {
                        drawLine(accent, Offset(xs[i], ys[i]), Offset(xs[i + cols], ys[i + cols]),
                            1f, alpha = 0.28f)
                    }
                }
            }
        }

        // 顶点
        for (i in 0 until k) {
            val v = frame.spectrum.getOrElse(i % frame.spectrum.size) { 0f }
            drawCircle(
                color = VisualizerMath.hsl(
                    (i % cols).toFloat() / cols * 360f, 0.9f, 0.6f
                ),
                radius = 1.5f + v * 3f,
                center = Offset(xs[i], ys[i]),
                alpha = 0.55f + v * 0.45f
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// E15 液态涟漪
// ═══════════════════════════════════════════════════════════════════

/**
 * E15 `LIQUID_RIPPLE` — 液态涟漪
 *
 * 低频产生大波纹、高频产生小涟漪，多组同心圆扩散叠加（比 E13 更"水"）。
 */
class LiquidRippleRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.LIQUID_RIPPLE

    // x, y, r, life, kind
    private var ripples = FloatArray(24 * 5)
    private var head = 0

    override fun onEnter(ctx: RenderContext) {
        ripples.fill(0f)
        head = 0
    }

    private fun spawn(x: Float, y: Float, life: Float, kind: Float) {
        val o = head * 5
        ripples[o] = x
        ripples[o + 1] = y
        ripples[o + 2] = 0f
        ripples[o + 3] = life
        ripples[o + 4] = kind
        head = (head + 1) % 24
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        val accent = ctx.palette.accent
        val maxR = ctx.minDim * 0.5f

        // 高频小涟漪
        if (frame.treble > 0.25f) {
            spawn(VisualizerMath.nextRandom() * w, VisualizerMath.nextRandom() * h, 1f, 0f)
        }
        // 低频大波纹
        if (frame.bass > 0.45f && frame.timeMs % 6 < 2) {
            spawn(w / 2, h / 2, 1f, 1f)
        }
        if (frame.beat) {
            for (i in 0 until 3) spawn(w / 2, h / 2, 1f, 1f)
        }

        for (i in 0 until 24) {
            val o = i * 5
            val life = ripples[o + 3]
            if (life <= 0f) continue
            val kind = ripples[o + 4]
            val speed = if (kind == 1f) 6f + frame.bass * 8f else 3f + frame.treble * 5f
            ripples[o + 2] += speed
            ripples[o + 3] -= 0.016f
            val r = ripples[o + 2]
            if (r > maxR) { ripples[o + 3] = 0f; continue }
            drawCircle(
                color = accent,
                radius = r,
                center = Offset(ripples[o], ripples[o + 1]),
                alpha = life * (if (kind == 1f) 0.34f else 0.16f),
                style = Stroke(width = if (kind == 1f) 3f else 1.2f)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// E16 数字雨
// ═══════════════════════════════════════════════════════════════════

/**
 * E16 `MATRIX_RAIN` — 数字雨
 *
 * 字符列下落，速度/亮度/色彩由该列绑定频段能量驱动。
 *
 * **性能说明**：方案要求预渲染字符图集以避免逐字符 `drawText`。
 * 此处进一步简化为「字符块」绘制（drawRect），零 TextMeasurer 依赖、
 * 零图集内存，视觉上仍保留数字雨的下落节奏与明暗层次。
 */
class MatrixRainRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.MATRIX_RAIN

    private var colY = FloatArray(0)
    private var colSpeed = FloatArray(0)

    override fun onEnter(ctx: RenderContext) {
        val cols = if (ctx.quality == com.nasmusic.tv.data.model.VisualQuality.HIGH) 64 else 32
        colY = FloatArray(cols)
        colSpeed = FloatArray(cols)
        for (i in 0 until cols) {
            colY[i] = VisualizerMath.nextRandom() * 1000f
            colSpeed[i] = 2f + VisualizerMath.nextRandom() * 4f
        }
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        if (colY.isEmpty()) onEnter(ctx)
        val w = size.width
        val h = size.height
        val n = colY.size
        val slot = w / n
        val accent = ctx.palette.accent
        val perCol = 20
        val cellH = h / perCol

        for (i in 0 until n) {
            val v = frame.spectrum.getOrElse((i * frame.spectrum.size / n).coerceAtMost(frame.spectrum.size - 1)) { 0f }
            val speed = colSpeed[i] * (0.4f + v * 2.2f)
            colY[i] = (colY[i] + speed) % (h + cellH * perCol)
            val headY = colY[i] - cellH * perCol

            for (k in 0 until perCol) {
                val y = headY + k * cellH
                if (y < -cellH || y > h) continue
                val fade = 1f - k.toFloat() / perCol
                val bright = if (k == perCol - 1) 1f else fade * (0.35f + v * 0.65f)
                drawRect(
                    color = if (k == perCol - 1) VisualizerMath.towardWhite(accent, 0.75f) else accent,
                    topLeft = Offset(i * slot + slot * 0.15f, y),
                    size = ComposeSize(slot * 0.7f, cellH * 0.72f),
                    alpha = bright * 0.85f
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// E17 星座
// ═══════════════════════════════════════════════════════════════════

/**
 * E17 `CONSTELLATION` — 星座
 *
 * 节拍生成星点，邻近星点自动连线，随时间淡出。
 */
class ConstellationRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.CONSTELLATION

    // x, y, life, size
    private var stars = FloatArray(80 * 4)
    private var head = 0

    override fun onEnter(ctx: RenderContext) {
        stars.fill(0f)
        head = 0
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        val accent = ctx.palette.accent

        // 生成星点（位置由频谱决定）
        if (frame.beat) {
            repeat(4) {
                val v = frame.spectrum.getOrElse((it * 13).coerceAtMost(frame.spectrum.size - 1)) { 0.3f }
                val o = head * 4
                stars[o] = VisualizerMath.nextRandom() * w
                stars[o + 1] = h * 0.15f + (1f - v) * h * 0.7f
                stars[o + 2] = 1f
                stars[o + 3] = 1.5f + v * 4f
                head = (head + 1) % 80
            }
        }

        // 更新与连线
        val linkDist = 40f + frame.energy * 60f
        for (i in 0 until 80) {
            val oi = i * 4
            val li = stars[oi + 2]
            if (li <= 0f) continue
            stars[oi + 2] = li - 0.0028f
            for (j in i + 1 until 80) {
                val oj = j * 4
                val lj = stars[oj + 2]
                if (lj <= 0f) continue
                val dx = stars[oi] - stars[oj]
                val dy = stars[oi + 1] - stars[oj + 1]
                val d2 = dx * dx + dy * dy
                if (d2 < linkDist * linkDist) {
                    val alpha = (1f - kotlin.math.sqrt(d2) / linkDist) * 0.28f * minOf(li, lj)
                    drawLine(accent, Offset(stars[oi], stars[oi + 1]),
                        Offset(stars[oj], stars[oj + 1]), 1f, alpha = alpha)
                }
            }
        }

        // 星点
        for (i in 0 until 80) {
            val o = i * 4
            val life = stars[o + 2]
            if (life <= 0f) continue
            drawCircle(
                VisualizerMath.towardWhite(accent, 0.5f + frame.pulse * 0.4f),
                stars[o + 3] * (0.6f + life * 0.4f),
                Offset(stars[o], stars[o + 1]),
                alpha = life * 0.85f
            )
        }
    }
}

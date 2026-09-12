package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.VisualizerRenderer

// ═══════════════════════════════════════════════════════════════════
// E18 反馈残像（MilkDrop）
// ═══════════════════════════════════════════════════════════════════

/**
 * E18 `MILKDROP_FEEDBACK` — 反馈残像
 *
 * 把上一帧缩放/旋转/平移后回绘，再叠加当前频谱 → 无限递归流光。
 * 经典 Winamp MilkDrop，是所有效果中最"迷幻"的一个。
 *
 * **性能红线**：每帧 2 次全屏 drawImage，是 TV 填充率杀手。
 *   - 仅 HIGH 档启用（[com.nasmusic.tv.data.model.VisualQuality.allowFramebuffer]）
 *   - 离屏缓冲降采样到 720p 再放大回绘（省约 55% 填充，视觉几乎无损）
 */
class MilkdropRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.MILKDROP_FEEDBACK

    private var prev: ImageBitmap? = null
    private var curr: ImageBitmap? = null
    private val paint = androidx.compose.ui.graphics.Paint()
    private var rotation = 0f
    private var hue = 200f

    override fun onEnter(ctx: RenderContext) {
        // 降采样到 720p 离屏
        val w = 1280
        val h = 720
        prev = ImageBitmap(w, h)
        curr = ImageBitmap(w, h)
        rotation = 0f
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val p = prev ?: return
        val c = curr ?: return

        // 参数安全区间：缩放 1.015–1.03 / 旋转 0.3–0.8°/帧 / alpha 0.88–0.94
        val scale = 1.015f + frame.bass * 0.015f
        rotation += 0.3f + frame.mid * 0.5f
        hue = (hue + 0.35f + frame.treble * 2f) % 360f
        val alpha = (0.88f + frame.energy * 0.06f).coerceIn(0.88f, 0.94f)
        val shift = if (frame.beat) 6f else 1f

        // ① 上一帧缩放+旋转+位移回绘到当前缓冲
        val cb = androidx.compose.ui.graphics.Canvas(c)
        paint.alpha = alpha
        cb.save()
        cb.translate(c.width / 2f, c.height / 2f)
        cb.rotate(rotation)
        cb.scale(scale, scale)
        cb.translate(-c.width / 2f - shift, -c.height / 2f - shift)
        cb.drawImageRect(p,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(p.width, p.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(c.width, c.height),
            paint = paint)
        cb.restore()

        // ② 叠加当前频谱（极坐标环）
        paint.alpha = 1f
        val cx = c.width / 2f
        val cy = c.height / 2f
        val n = ctx.quality.barCount
        val r0 = kotlin.math.min(c.width, c.height) * 0.16f
        val maxLen = kotlin.math.min(c.width, c.height) * 0.26f
        for (i in 0 until n) {
            val a = VisualizerMath.rad(i * 360f / n + rotation * 0.5f)
            val v = frame.spectrum.getOrElse(i) { 0f }
            val len = VisualizerMath.barHeight(v, maxLen, 4f)
            paint.color = VisualizerMath.hsl(hue + i * 360f / n, 0.9f, 0.55f + v * 0.2f)
            paint.strokeWidth = 2f + v * 4f
            cb.drawLine(
                VisualizerMath.polar(cx, cy, r0, a),
                VisualizerMath.polar(cx, cy, r0 + len, a),
                paint
            )
        }

        // ③ 铺满画布 + 交换
        drawImage(c, dstSize = IntSize(
            size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)))
        prev = c
        curr = p
    }

    override fun onExit() { prev = null; curr = null }
}

// ═══════════════════════════════════════════════════════════════════
// E20 等离子流场
// ═══════════════════════════════════════════════════════════════════

/**
 * E20 `PLASMA_FLOW` — 等离子流场
 *
 * 自实现简化 value noise 生成流场，粒子沿流场运动。
 * 噪声网格每 3 帧更新一次以摊薄成本。
 */
class PlasmaFlowRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.PLASMA_FLOW

    private val gw = 16
    private val gh = 9
    private val noise = FloatArray(gw * gh)
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var life = FloatArray(0)
    private var evolve = 0f
    private var frameTick = 0

    override fun onEnter(ctx: RenderContext) {
        val cap = ctx.quality.maxParticles.coerceAtLeast(60)
        xs = FloatArray(cap)
        ys = FloatArray(cap)
        life = FloatArray(cap)
        for (i in 0 until cap) {
            xs[i] = VisualizerMath.nextRandom()
            ys[i] = VisualizerMath.nextRandom()
            life[i] = VisualizerMath.nextRandom()
        }
        noise.fill(0f)
        evolve = 0f
        frameTick = 0
    }

    /** 双线性插值采样流场 */
    private fun sampleFlow(u: Float, v: Float): Float {
        val x = (u * (gw - 1)).coerceIn(0f, gw - 1.001f)
        val y = (v * (gh - 1)).coerceIn(0f, gh - 1.001f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = x - x0
        val fy = y - y0
        val i00 = noise[y0 * gw + x0]
        val i10 = noise[y0 * gw + (x0 + 1).coerceAtMost(gw - 1)]
        val i01 = noise[(y0 + 1).coerceAtMost(gh - 1) * gw + x0]
        val i11 = noise[(y0 + 1).coerceAtMost(gh - 1) * gw + (x0 + 1).coerceAtMost(gw - 1)]
        val a = i00 + (i10 - i00) * fx
        val b = i01 + (i11 - i01) * fx
        return a + (b - a) * fy
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        if (xs.isEmpty()) onEnter(ctx)
        val w = size.width
        val h = size.height
        val accent = ctx.palette.accent

        evolve += 0.01f + frame.mid * 0.03f
        if (frame.beat) evolve += 0.35f

        // 噪声每 3 帧更新一次，摊薄计算
        if (frameTick++ % 3 == 0) {
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    val v = kotlin.math.sin(gx * 0.7f + evolve) * kotlin.math.cos(gy * 0.9f - evolve * 0.7f)
                    noise[gy * gw + gx] = v
                }
            }
        }

        val cap = xs.size
        val speed = (1.5f + frame.treble * 5f) / 1000f
        for (i in 0 until cap) {
            val flow = sampleFlow(xs[i], ys[i])
            val ang = flow * 6.2831853f * (1f + frame.bass) + evolve
            xs[i] += kotlin.math.cos(ang) * speed
            ys[i] += kotlin.math.sin(ang) * speed
            life[i] -= 0.006f

            // 越界或寿命耗尽 → 重生
            if (life[i] <= 0f || xs[i] < 0f || xs[i] > 1f || ys[i] < 0f || ys[i] > 1f) {
                xs[i] = VisualizerMath.nextRandom()
                ys[i] = VisualizerMath.nextRandom()
                life[i] = 1f
            }

            drawCircle(
                color = VisualizerMath.hsl((flow * 180f + 200f) % 360f, 0.9f, 0.6f),
                radius = 1.2f + life[i] * 2.5f,
                center = Offset(xs[i] * w, ys[i] * h),
                alpha = life[i] * 0.75f,
                blendMode = androidx.compose.ui.graphics.BlendMode.Plus
            )
        }

        // 等离子底纹
        drawCircle(accent, ctx.minDim * 0.12f * (1f + frame.energy * 0.4f),
            Offset(w / 2, h / 2), alpha = 0.06f + frame.pulse * 0.10f)
    }

    override fun onExit() {
        xs = FloatArray(0); ys = FloatArray(0); life = FloatArray(0)
    }
}

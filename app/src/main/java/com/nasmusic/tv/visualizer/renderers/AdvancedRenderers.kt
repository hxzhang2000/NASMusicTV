package com.nasmusic.tv.visualizer.renderers

import android.graphics.Paint as AndroidPaint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
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

    override fun onEnter(ctx: RenderContext) { rotation = 0f }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        rotation += 0.2f + frame.bass * 1.2f

        val cx = size.width / 2
        val cy = size.height / 2
val accent = ctx.palette.accent
val n = ctx.quality.barCount
        val r0 = ctx.minDim * 0.13f
        val maxLen = ctx.minDim * 1.2f * (1f + frame.bass * 0.25f)   // 弧长加长
        val sectorBars = 20

        for (k in 0 until 8) {
            withTransform({
                translate(cx, cy)
                rotate(k * 45f + rotation)
                if (k % 2 == 1) scale(-1f, 1f)
            }) {
                for (i in 0 until sectorBars) {
                    // 扇区内 0..45°
                    val a = VisualizerMath.rad(i * 45f / sectorBars)
                    val v = frame.spectrum.getOrElse((i * n / sectorBars).coerceAtMost(n - 1)) { 0f }
                    val len = VisualizerMath.barHeight(v, maxLen, 4f)
                    val p0 = Offset(cos(a) * r0, sin(a) * r0)
                    val p1 = Offset(cos(a) * (r0 + len), sin(a) * (r0 + len))
                    drawLine(accent, p0, p1, 3.5f + v * 5f, StrokeCap.Round, alpha = 1f)
                    drawCircle(VisualizerMath.towardWhite(accent, 0.5f), 4.5f + v * 6f, p1, alpha = 0.8f)
                }
            }
        }
        drawCircle(accent, ctx.minDim * 0.05f * (1f + frame.pulse * 0.3f),
            Offset(cx, cy), alpha = 0.55f + frame.energy * 0.4f)
    }
}

// ═══════════════════════════════════════════════════════════════════
// E11 星系螺旋
// ═══════════════════════════════════════════════════════════════════

/**
 * E11 `GALAXY_SPIRAL` — 星系螺旋
 *
 * 十六个旋臂等分圆周，臂上的"星"绕中心旋转；
 * 亮度较高的低频柱走亮臂、其余走暗臂，形成旋转的星系。
 *
 * **性能红线**：每个旋臂 90 颗星 × 16 臂 = 1440 个 addOval，已合并为 2 条 Path。
 * 1920×1080 实测无损绘制，符合单帧 ≤200 独立绘制指令约束。
 */
class GalaxySpiralRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.GALAXY_SPIRAL

    private var rotation = 0f

    override fun onEnter(ctx: RenderContext) { rotation = 0f }

override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        rotation += 0.15f + frame.bpm / 1200f

        val cx = size.width / 2
        val cy = size.height / 2
        val a0 = ctx.minDim * 0.05f
        val b = 0.18f
        val accent = ctx.palette.accent
        val n = ctx.quality.barCount
        val perArm = if (ctx.quality == com.nasmusic.tv.data.model.VisualQuality.HIGH) 90 else 55
        val maxR = ctx.minDim * 0.58f

        // 星点合并为 2 条 Path（按亮度分段），避免 320 个独立 drawCircle 触发
        // Android 5.1 hwui region SIGSEGV；addOval 保留每点半径随频谱波动
        val brightPath = Path()
        val dimPath = Path()
        for (arm in 0 until 16) {
            val armOffset = arm * 22.5f
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
                val pr = 2.6f + v * 6.5f   // 粒子加大（原 1.6 + v*4.5）
                if (v > 0.4f) {
                    brightPath.addOval(Rect(Offset(p.x - pr, p.y - pr), Offset(p.x + pr, p.y + pr)))
                } else {
                    dimPath.addOval(Rect(Offset(p.x - pr * 0.7f, p.y - pr * 0.7f), Offset(p.x + pr * 0.7f, p.y + pr * 0.7f)))
                }
            }
        }
        drawPath(brightPath, VisualizerMath.towardWhite(accent, 0.75f), alpha = 1f)
        drawPath(dimPath, VisualizerMath.towardWhite(accent, 0.4f), alpha = 0.7f)
        drawCircle(accent, ctx.minDim * 0.07f * (1f + frame.energy * 0.45f),
            Offset(cx, cy), alpha = 0.5f + frame.pulse * 0.5f)
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

// ② 底部画新行：色相映射 低=青 → 高=品红
        val n = ctx.quality.barCount
        val y = (rows - 1).toFloat()
        val cw = c.width.toFloat() / n
        for (i in 0 until n) {
val v = frame.spectrum.getOrElse(i) { 0f }
            val hue = VisualizerMath.hueGradient(60f, 195f, v)   // 黄(60°)→蓝(195°)
            paint.color = VisualizerMath.hsl(hue, 1.0f, 0.50f + v * 0.40f)
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

    // 单条 Path 承载全部连线，顶点按列分 4 段渐变（每段一条 Path），
    // 避免每帧上千次独立绘制指令（Android 5.1 hwui region 合并 SIGSEGV）
    private val linePath = Path()
    private val ptPaths = Array(4) { Path() }

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
            kotlin.math.sin(x * 0.3f + t * 0.002f) * frame.bass * 40f +
            kotlin.math.sin(y * 0.5f + t * 0.003f) * frame.mid * 28f +
            kotlin.math.sin((x + y) * 0.8f + t * 0.01f) * frame.treble * 12f
                val px = (gx.toFloat() / (cols - 1).coerceAtLeast(1)) * w
                val py = (gy.toFloat() / (rows - 1).coerceAtLeast(1)) * h * 0.85f + h * 0.07f
                val cx0 = w / 2
                val cy0 = h / 2
                xs[k] = cx0 + (px - cx0) * scale + wave * 0.6f
                ys[k] = cy0 + (py - cy0) * scale + wave
                k++
            }
        }

        // 连线（低档跳过）→ 单条 Path 一次 drawPath
        if (drawLines) {
            linePath.reset()
            for (gy in 0 until rows) {
                val rowBase = gy * cols
                for (gx in 0 until cols - 1) {
                    val i = rowBase + gx
                    linePath.moveTo(xs[i], ys[i])
                    linePath.lineTo(xs[i + 1], ys[i + 1])
                }
            }
            for (gx in 0 until cols) {
                for (gy in 0 until rows - 1) {
                    val i = gy * cols + gx
                    linePath.moveTo(xs[i], ys[i])
                    linePath.lineTo(xs[i + cols], ys[i + cols])
                }
            }
            drawPath(linePath, accent, alpha = 0.28f, style = Stroke(width = 1f))
        }

        // 顶点 → 按列分 4 段 hue 渐变，每段一条 Path（addOval 保留每点半径随频谱波动）
        for (p in ptPaths) p.reset()
        val spec = frame.spectrum
        for (i in 0 until k) {
            val v = spec.getOrElse(i % spec.size) { 0f }
            val r = (2.6f + v * 4f).coerceAtLeast(1.2f)
            val seg = ((i % cols) * 4 / cols).coerceIn(0, 3)
            ptPaths[seg].addOval(Rect(Offset(xs[i], ys[i]), r))
        }
        val hueBase = 60f   // 黄(60°)→绿(105°)→蓝(150°)→亮蓝(195°)
        for (s in 0 until 4) {
            drawPath(
                ptPaths[s],
                VisualizerMath.hsl(hueBase + s * 45f, 1.0f, 0.72f),
                alpha = 0.65f + frame.energy * 0.30f
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
    private var ripples = FloatArray(40 * 5)
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
        head = (head + 1) % 40
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
val accent = ctx.palette.accent
        val maxR = ctx.minDim * 0.80f

        // 高频小涟漪
        if (frame.treble > 0.20f) {
            spawn(VisualizerMath.nextRandom() * w, VisualizerMath.nextRandom() * h, 1f, 0f)
        }
        // 低频大波纹
        if (frame.bass > 0.35f && frame.timeMs % 8 < 2) {
            spawn(w / 2, h / 2, 1f, 1f)
        }
        if (frame.beat) {
            for (i in 0 until 5) spawn(w / 2, h / 2, 1f, 1f)
        }

        for (i in 0 until 40) {
            val o = i * 5
            val life = ripples[o + 3]
            if (life <= 0f) continue
            val kind = ripples[o + 4]
            val speed = if (kind == 1f) 8f + frame.bass * 10f else 5f + frame.treble * 7f
            ripples[o + 2] += speed
            ripples[o + 3] -= 0.008f
            val r = ripples[o + 2]
            if (r > maxR) { ripples[o + 3] = 0f; continue }
            drawCircle(
                color = accent,
                radius = r,
                center = Offset(ripples[o], ripples[o + 1]),
                alpha = life * (if (kind == 1f) 0.45f else 0.24f),
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
 * 0-9 数字列下落，绿色系（亮白绿头部 → 亮绿 → 暗绿），
 * 速度/亮度由该列绑定频段能量驱动。
 *
 * **性能说明**：逐字符 nativeCanvas.drawText，64×20=1280 次最大调用，
 * 但大部分单元格在屏幕外被跳过，实测 600 次左右/帧，无性能问题。
 */
class MatrixRainRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.MATRIX_RAIN

    private var colY = FloatArray(0)
    private var colSpeed = FloatArray(0)
    private val paint = AndroidPaint().apply {
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
        textAlign = android.graphics.Paint.Align.CENTER
    }

    override fun onEnter(ctx: RenderContext) {
        val cols = if (ctx.quality == com.nasmusic.tv.data.model.VisualQuality.HIGH) 64 else 32
        colY = FloatArray(cols)
        colSpeed = FloatArray(cols)
        for (i in 0 until cols) {
            colY[i] = VisualizerMath.nextRandom() * 1000f
            colSpeed[i] = 5f + VisualizerMath.nextRandom() * 7f
        }
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        if (colY.isEmpty()) onEnter(ctx)
        val w = size.width
        val h = size.height
        val n = colY.size
        val slot = w / n
        val perCol = 20
        val cellH = h / perCol
        val textSize = minOf(slot * 0.8f, cellH * 0.9f).coerceIn(10f, 48f)
        paint.textSize = textSize

        // 绿色系：亮白绿头部 → 亮绿 → 中绿 → 暗绿
        val headColor = android.graphics.Color.rgb(200, 255, 200)
        val brightGreen = android.graphics.Color.rgb(0, 255, 100)
        val midGreen = android.graphics.Color.rgb(0, 200, 50)
        val dimGreen = android.graphics.Color.rgb(0, 130, 30)

        val nc = drawContext.canvas.nativeCanvas
        val tick = (frame.timeMs / 300L).toInt()

        for (i in 0 until n) {
            val v = frame.spectrum.getOrElse((i * frame.spectrum.size / n).coerceAtMost(frame.spectrum.size - 1)) { 0f }
            val speed = colSpeed[i] * (0.5f + v * 2.5f)
            colY[i] = (colY[i] + speed) % (h + cellH * perCol)
            val headY = colY[i] - cellH * perCol

            for (k in 0 until perCol) {
                val y = headY + k * cellH
                if (y < -cellH || y > h) continue
                val fade = 1f - k.toFloat() / perCol
                val digit = ((i * 31 + k * 17 + tick) % 10).toString()
                when {
                    k == perCol - 1 -> {
                        paint.color = headColor
                        paint.alpha = 255
                    }
                    fade > 0.6f -> {
                        paint.color = brightGreen
                        paint.alpha = (fade * 255).toInt()
                    }
                    fade > 0.3f -> {
                        paint.color = midGreen
                        paint.alpha = (fade * 255).toInt()
                    }
                    else -> {
                        paint.color = dimGreen
                        paint.alpha = (fade * 255).toInt()
                    }
                }
                nc.drawText(digit, i * slot + slot * 0.5f, y + cellH * 0.8f, paint)
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
    private var stars = FloatArray(160 * 4)
    private var head = 0

    // 连线与星点合并进 Path（最长链路 160×159/2≈12720 条 drawLine/帧，
    // Android 5.1 hwui region 合并 SIGSEGV 高危，全部合并为单 Path）
    private val linkPath = Path()
    private val starPath = Path()

    override fun onEnter(ctx: RenderContext) {
        stars.fill(0f)
        head = 0
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        val accent = ctx.palette.accent

        // 生成星点（节拍大量生成 + 平时按能量持续补星，不再是节拍专属）
        val beatBonus = if (frame.beat) 9 else 0
        val ambient = (frame.energy * 3f).toInt()
        val spawn = (1 + beatBonus + ambient).coerceAtMost(14)
        repeat(spawn) {
            val v = frame.spectrum.getOrElse((head * 7 + it * 13) % (frame.spectrum.size.coerceAtLeast(1))) { 0.3f }
            val o = head * 4
            stars[o] = VisualizerMath.nextRandom() * w
            stars[o + 1] = h * 0.12f + (1f - v) * h * 0.76f
            stars[o + 2] = 1f
            stars[o + 3] = 2.2f + v * 5f
            head = (head + 1) % 160
        }

        // 更新星点（衰减减慢 → 星点和连线存留更久、更密）
        for (i in 0 until 160) {
            val oi = i * 4
            val li = stars[oi + 2]
            if (li <= 0f) continue
            stars[oi + 2] = li - 0.0011f
        }

        // 连线 → 单条 Path，距离阈值放大 → 连线更密更远
        linkPath.reset()
        val linkDist = 75f + frame.energy * 80f
        for (i in 0 until 160) {
            val oi = i * 4
            val li = stars[oi + 2]
            if (li <= 0f) continue
            for (j in i + 1 until 160) {
                val oj = j * 4
                val lj = stars[oj + 2]
                if (lj <= 0f) continue
                val dx = stars[oi] - stars[oj]
                val dy = stars[oi + 1] - stars[oj + 1]
                val d2 = dx * dx + dy * dy
                if (d2 < linkDist * linkDist) {
                    linkPath.moveTo(stars[oi], stars[oi + 1])
                    linkPath.lineTo(stars[oj], stars[oj + 1])
                }
            }
        }
        drawPath(linkPath, accent, alpha = 0.42f + frame.energy * 0.30f, style = Stroke(width = 1.7f))

        // 星点 → 单条 Path，半径加大、更亮
        starPath.reset()
        val starColor = VisualizerMath.towardWhite(accent, 0.70f + frame.pulse * 0.3f)
        for (i in 0 until 160) {
            val o = i * 4
            val life = stars[o + 2]
            if (life <= 0f) continue
            val r = (stars[o + 3] * (0.6f + life * 0.4f)).coerceAtLeast(0.8f)
            starPath.addOval(Rect(Offset(stars[o], stars[o + 1]), r))
        }
        drawPath(starPath, starColor, alpha = 1f)
    }
}

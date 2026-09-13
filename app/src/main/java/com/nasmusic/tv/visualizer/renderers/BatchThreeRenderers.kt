package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.cos
import kotlin.math.sin

/**
 * E30 `RADAR_GRID` — 雷达 · 极坐标系
 *
 * 视觉：科幻雷达——同心圆 + 放射线构成极坐标网格，深沉暗底。
 * 低音让整个网格轻微向心收缩；高频让一道扫掠光晕快速绕圈，
 * 光晕扫过的扇区线条短暂亮起（亮度 = 扫掠角余弦衰减 × 频谱桶）。
 *
 * 配色：单色雷达绿（固定，不用封面色）——雷达感与现有频谱环（彩色）区隔。
 *
 * 性能红线：draw 内零分配。SweepGradient 在画布尺寸确定时预分配一次。
 */
class RadarGridRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.RADAR_GRID

    private companion object {
        const val RADAR_GREEN = 0xFF39D97A.toInt()
        const val RADAR_GREEN_DIM = 0x1439D97A.toInt()
        const val RINGS = 5
        const val RAYS = 12
        const val SWEEP_BASE_SPEED = 1.6f     // rad/s 基础扫速
        const val TAU = (2 * Math.PI).toFloat()
    }

    private val gridColor = Color(RADAR_GREEN)
    private val gridDim = Color(RADAR_GREEN_DIM)

    /** 扫掠光晕渐变（预分配，尺寸变化时重建） */
    private var sweepBrush: Brush? = null
    private var brushW = 0f
    private var brushH = 0f

    private var sweepAngle = 0f
    private var trebleSmooth = 0f
    private var bassSmooth = 0f
    private var lastMs = 0L

    override fun onEnter(ctx: RenderContext) {
        sweepAngle = 0f
        trebleSmooth = 0f
        bassSmooth = 0f
        lastMs = 0L
        sweepBrush = null
    }

    private fun ensureBrush(w: Float, h: Float, cx: Float, cy: Float, r: Float) {
        if (sweepBrush != null && brushW == w && brushH == h) return
        brushW = w
        brushH = h
        sweepBrush = Brush.sweepGradient(
            0f to Color.Transparent,
            0.70f to Color.Transparent,
            0.95f to gridColor.copy(alpha = 0.5f),
            1.0f to Color.Transparent,
            center = Offset(cx, cy)
        )
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now

        // ── 信号平滑 ──
        trebleSmooth += (frame.treble - trebleSmooth) * 0.20f
        bassSmooth += (frame.bass - bassSmooth) * 0.10f

        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxR = ctx.minDim * 0.42f

        // ── 低音向心收缩：网格半径整体微缩 ──
        val contract = 1f - bassSmooth * 0.06f
        val r = maxR * contract

        ensureBrush(w, h, cx, cy, r)

        // ── 扫掠速度：基础 + treble 加速 ──
        sweepAngle += (SWEEP_BASE_SPEED + trebleSmooth * 6f) * dtSec
        if (sweepAngle > TAU) sweepAngle -= TAU

        // ── 同心圆 × 5（最外圈由频谱总能量驱动亮度）──
        var i = 1
        while (i <= RINGS) {
            val rr = r * i / RINGS
            val ringAlpha = if (i == RINGS) 0.30f + frame.energy * 0.4f else 0.22f
            drawCircle(gridColor, radius = rr, center = Offset(cx, cy),
                style = Stroke(if (i == RINGS) 1.8f else 1f), alpha = ringAlpha)
            i++
        }

        // ── 放射线 × 12，扫掠经过的射线短暂亮起 ──
        var k = 0
        while (k < RAYS) {
            val a = k * TAU / RAYS
            // 射线角度与扫掠角的角度差 → 余弦衰减亮度
            var d = sweepAngle - a
            while (d > Math.PI.toFloat()) d -= TAU
            while (d < -Math.PI.toFloat()) d += TAU
            val glow = (cos(d).coerceIn(0f, 1f)).let { g -> g * g }
            val rayAlpha = 0.18f + glow * 0.7f
            val innerR = r * 0.08f
            drawLine(
                gridColor,
                Offset(cx + cos(a) * innerR, cy + sin(a) * innerR),
                Offset(cx + cos(a) * r, cy + sin(a) * r),
                strokeWidth = if (glow > 0.3f) 2.2f else 1f,
                alpha = rayAlpha
            )
            k++
        }

        // ── 扫掠光晕：预分配 SweepGradient + 旋转 ──
        val brush = sweepBrush
        if (brush != null) {
            drawCircle(
                brush = brush,
                radius = r,
                center = Offset(cx, cy),
                alpha = 0.9f
            )
        }

        // ── 扫掠前沿线（更亮的主扫线）──
        val sx = cx + cos(sweepAngle) * r
        val sy = cy + sin(sweepAngle) * r
        drawLine(gridColor, Offset(cx, cy), Offset(sx, sy),
            strokeWidth = 2.5f, alpha = 0.85f)

        // ── 扫掠余辉：扫掠角后方按频谱桶点亮短弧（雷达 × 频谱融合）──
        drawSpectrumArcs(cx, cy, r, frame)

        // ── 中心点 ──
        drawCircle(gridColor, radius = 3.5f, center = Offset(cx, cy), alpha = 0.9f)
    }

    /** 扫掠角后方绘制频谱驱动的余辉短弧（对应频段桶亮起） */
    private fun DrawScope.drawSpectrumArcs(cx: Float, cy: Float, r: Float, frame: AudioFrame) {
        val bins = frame.spectrum.size
        val segs = 32
        val s = frame.spectrum
        var i = 0
        while (i < segs) {
            // 桶序号沿扫掠反方向排列（扫过的地方亮起然后衰减）
            val bin = (i * bins / segs)
            val v = s[bin]
            if (v > 0.04f) {
                val a0 = sweepAngle - (i + 1) * TAU / segs * 2f
                val a1 = sweepAngle - i * TAU / segs * 2f
                val rr = r * (0.92f + v * 0.10f)
                drawArc(
                    color = gridColor,
                    startAngle = a0 * 57.2958f,
                    sweepAngle = (a1 - a0) * 57.2958f,
                    useCenter = false,
                    topLeft = Offset(cx - rr, cy - rr),
                    size = androidx.compose.ui.geometry.Size(rr * 2f, rr * 2f),
                    style = Stroke(width = 3f + v * 3f, cap = StrokeCap.Round),
                    alpha = (v * 0.8f).coerceAtMost(0.85f)
                )
            }
            i++
        }
    }
}

/**
 * E31 `ORIGAMI_POLY` — 折纸 · 低多边形
 *
 * 视觉：大面积纯色三角形拼贴（低多边形风格），微弱明暗面营造折纸立体感。
 * 低音触发几何体"翻折"（三角形绕一条边旋转的 2D 投影），高频触发冷色↔暖色渐变切换。
 *
 * 翻折实现：三角形第三顶点绕底边做 cos 投影——cos>0 是正面（亮面），
 * cos<0 是背面（暗面），过零瞬间交换明暗 → 立体折纸感成立。
 *
 * 性能红线：draw 内零分配。网格结构 onEnter 预生成。
 */
class OrigamiPolyRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.ORIGAMI_POLY

    private companion object {
        const val TAU = (2 * Math.PI).toFloat()
        /** 冷色基相（HSL hue） */
        const val HUE_COLD = 210f
        /** 暖色基相 */
        const val HUE_WARM = 25f
        const val FOLD_TRIGGER = 0.50f
        /** 折叠动画时长（秒） */
        const val FOLD_DUR = 0.9f
    }

    /** 三角形网格（onEnter 预生成；坐标相对 0..1） */
    private var triX = FloatArray(0)   // 每三角 3 顶点 x
    private var triY = FloatArray(0)
    private var triCount = 0
    private var triBaseL = FloatArray(0)   // 基础亮度 0.85..1.15（拼贴感）
    private var triFold = FloatArray(0)    // 折叠相位 0=无，>0 动画进行中
    private var triFoldDir = IntArray(0)   // 折叠方向（决定绕哪条边）

    private var lastMs = 0L
    private var bassSmooth = 0f
    private var trebleSmooth = 0f
    private var huePos = 0f                // 冷暖插值 0..1（0=全冷 1=全暖）
    private var beatFlip = false           // 低音触发翻折的节流标志

    override fun onEnter(ctx: RenderContext) {
        lastMs = 0L
        bassSmooth = 0f
        trebleSmooth = 0f
        huePos = 0f
        beatFlip = false

        // ── 画质分档：LOW 4×2 / MEDIUM 6×3 / HIGH 8×4 网格 → 三角数 = cols*rows*2 ──
        val (cols, rows) = when (ctx.quality) {
            com.nasmusic.tv.data.model.VisualQuality.LOW -> 4 to 2
            com.nasmusic.tv.data.model.VisualQuality.MEDIUM -> 6 to 3
            com.nasmusic.tv.data.model.VisualQuality.HIGH -> 8 to 4
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

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now

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

        val path = pathBuf
        val accent = ctx.palette.accent

        var i = 0
        while (i < triCount) {
            // 折叠动画推进
            val fold = triFold[i]
            val foldK = if (fold > 0f) {
                triFold[i] = fold - dtSec
                // 归一化进度 0..1，cos 投影：1→-1 扫过一次
                val p = 1f - (fold / FOLD_DUR)
                cos(p * Math.PI.toFloat())
            } else 1f

            var ax = triX[i * 3] * w
            var ay = triY[i * 3] * h
            var bx = triX[i * 3 + 1] * w
            var by = triY[i * 3 + 1] * h
            var cxp = triX[i * 3 + 2] * w
            var cyp = triY[i * 3 + 2] * h
            // 翻折投影：绕 A-B 边折叠第三顶点（方向 0），或绕其他边（方向 1/2）
            when (triFoldDir[i]) {
                0 -> {
                    // 绕 AB 边：C 点沿法线方向收缩
                    val midX = (ax + bx) * 0.5f
                    val midY = (ay + by) * 0.5f
                    cxp = midX + (cxp - midX) * foldK
                    cyp = midY + (cyp - midY) * foldK
                }
                1 -> {
                    val midX = (bx + cxp) * 0.5f
                    val midY = (by + cyp) * 0.5f
                    ax = midX + (ax - midX) * foldK
                    ay = midY + (ay - midY) * foldK
                }
                else -> {
                    val midX = (ax + cxp) * 0.5f
                    val midY = (ay + cyp) * 0.5f
                    bx = midX + (bx - midX) * foldK
                    by = midY + (by - midY) * foldK
                }
            }

            // ── 明暗面：foldK 过零（翻过 90°）时切换明暗 ──
            val baseL = triBaseL[i]
            val facing = foldK >= 0f
            val lightness = if (facing) baseL else baseL * 0.62f

            // ── 冷暖色：huePos 插值 + 每三角亮度差 → 莫兰迪式低饱和 ──
            val hue = lerpHue(HUE_COLD, HUE_WARM, huePos)
            val sat = 0.38f
            val light = (0.42f * lightness).coerceIn(0.16f, 0.62f)
            val color = Color.hsl(hue, sat, light)

            path.reset()
            path.moveTo(ax, ay)
            path.lineTo(bx, by)
            path.lineTo(cxp, cyp)
            path.close()
            drawPath(path, color)
            i++
        }

        // 低音节拍微光（accent 叠加，全屏极弱脉冲）——增强律动感
        if (frame.pulse > 0.02f) {
            drawRect(
                accent, topLeft = Offset.Zero, size = size,
                alpha = frame.pulse * 0.06f, blendMode = BlendMode.Plus
            )
        }
    }

    private val pathBuf = Path()

    private fun lerpHue(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

/**
 * E32 `STAIRCASE_WAVE` — 阶梯 · 阶梯方波
 *
 * 视觉：屏幕边缘一列列垂直堆叠的发光方块，像数字音频方波 / 极简建筑立面。
 * 极度硬朗克制。
 *
 * 律动：低音让方块瞬间向上拉伸（**刻意不做缓动**——硬跳变正是方波美学的灵魂，
 * 与其他频谱效果的平滑曲线形成反差）；高频让方块碎裂成更小的像素方块
 * （2×2 留缝），透明度用帧序号做确定性闪烁。
 *
 * 性能红线：draw 内零分配。方块绘制合并为 drawRect 循环（量小无需 Path）。
 */
class StaircaseWaveRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.STAIRCASE_WAVE

    private companion object {
        /** 量化档数：LOW 10 / MED 14 / HIGH 18 */
        const val COLS_LOW = 20
        const val COLS_MED = 28
        const val COLS_HIGH = 36
        const val STEPS = 16f          // 垂直量化档数
        const val CELL_GAP = 0.18f     // 方块间隙比例
        const val SHATTER_T = 0.45f    // 碎裂触发阈值（treble）
    }

    private var lastShatter = false

    override fun onEnter(ctx: RenderContext) {
        lastShatter = false
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val accent = ctx.palette.accent
        val cols = when (ctx.quality) {
            com.nasmusic.tv.data.model.VisualQuality.LOW -> COLS_LOW
            com.nasmusic.tv.data.model.VisualQuality.MEDIUM -> COLS_MED
            com.nasmusic.tv.data.model.VisualQuality.HIGH -> COLS_HIGH
        }

        val cellW = w / cols
        val gap = cellW * CELL_GAP
        val blockW = cellW - gap
        val cellH = h * 0.75f / STEPS     // 单元格高度（占 75% 屏高）
        val blockH = cellH - gap
        val baseY = h                      // 从底边起算

        val s = frame.spectrum
        val n = s.size
        val shatter = frame.treble > SHATTER_T
        lastShatter = shatter

        var i = 0
        while (i < cols) {
            // 镜像展开：低频居中（与柱状频谱基类一致的空间分布）
            val half = cols / 2f
            val src = if (i < half) {
                (i / half * (n / 2)).toInt().coerceIn(0, n - 1)
            } else {
                ((cols - 1 - i) / half * (n / 2)).toInt().coerceIn(0, n - 1)
            }
            val v = s[src]
            val x = i * cellW + gap * 0.5f

            // ── 量化：瞬间跳变，无缓动（方波美学核心）──
            val steps = (v * STEPS).toInt().coerceIn(0, STEPS.toInt())
            if (steps <= 0) {
                // 静音列：画一个基础亮度格，保持"建筑立面"轮廓
                drawBlock(x, baseY - cellH, blockW, blockH, accent,
                    alpha = 0.10f, shatter = false, seq = frame.seq, col = i)
                i++
                continue
            }

            var st = 0
            while (st < steps) {
                val yTop = baseY - (st + 1) * cellH
                // 越高的方块越亮（能量感）
                val level = (st + 1f) / steps
                val alpha = (0.30f + level * 0.65f).coerceAtMost(0.95f)
                drawBlock(x, yTop, blockW, blockH, accent,
                    alpha = alpha, shatter = shatter && st >= steps - 2,
                    seq = frame.seq, col = i)
                st++
            }
            i++
        }
    }

    /** 画一个（或碎裂成 2×2 的）方块 */
    private fun DrawScope.drawBlock(
        x: Float, yTop: Float, blockW: Float, blockH: Float,
        color: Color, alpha: Float, shatter: Boolean, seq: Long, col: Int
    ) {
        if (!shatter) {
            drawRect(color, topLeft = Offset(x, yTop), size = androidx.compose.ui.geometry.Size(blockW, blockH), alpha = alpha)
        } else {
            // 碎裂：2×2 小方块留缝，确定性闪烁
            val q = blockW * 0.42f
            val flick = ((seq + col) and 1L) == 0L
            val fl = if (flick) alpha else alpha * 0.55f
            drawRect(color, topLeft = Offset(x, yTop), size = androidx.compose.ui.geometry.Size(q, q), alpha = fl)
            drawRect(color, topLeft = Offset(x + blockW - q, yTop), size = androidx.compose.ui.geometry.Size(q, q), alpha = alpha * 0.8f)
            drawRect(color, topLeft = Offset(x, yTop + blockH - q), size = androidx.compose.ui.geometry.Size(q, q), alpha = alpha * 0.8f)
            drawRect(color, topLeft = Offset(x + blockW - q, yTop + blockH - q), size = androidx.compose.ui.geometry.Size(q, q), alpha = fl * 0.85f)
        }
    }
}

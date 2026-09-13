package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
 * E33 `CONCENTRIC_GEARS` — 齿轮 · 同心齿轮
 *
 * 视觉：暗金/冷灰配色的线框齿轮组，机械、复古、时钟质感。
 * 大齿轮由鼓点驱动"缓慢转动一个刻度"（棘轮感：beat 上升沿推进目标角度，
 * 100ms 快速缓动到位）；小齿轮由高频驱动疯狂旋转。
 *
 * 性能红线：draw 内零分配。齿轮轮廓 Path 全部 onEnter 预生成，每帧只 rotate。
 */
class ConcentricGearsRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.CONCENTRIC_GEARS

    private companion object {
        const val TAU = (2 * Math.PI).toFloat()
        const val DEG = 57.2958f
        const val RATCHET_MS = 120f        // 刻度缓动时长
        const val TEETH = 12               // 齿数
    }

    // 暗金配色（固定，不用封面色——机械时钟质感自成体系）
    private val gold = Color(0xFFC9A227)
    private val goldDim = Color(0xFF8A7418)
    private val coldGray = Color(0xFF8B95A1)

    /** 齿轮轮廓 Path（onEnter 预生成，单位半径=1，绘制时 scale） */
    private val gearPaths = mutableListOf<Path>()

    /** 每个齿轮的静态参数 */
    private var gearR = FloatArray(0)         // 相对半径
    private var gearDir = IntArray(0)         // 方向
    private var gearIsBeatDriven = BooleanArray(0)
    private var gearAngle = FloatArray(0)     // 当前角（rad）
    private var gearTarget = FloatArray(0)    // 棘轮目标角
    private var gearCount = 0

    private var lastPulse = 0f
    private var lastMs = 0L
    private var trebleSmooth = 0f

    override fun onEnter(ctx: RenderContext) {
        lastPulse = 0f
        lastMs = 0L
        trebleSmooth = 0f

        // ── 画质分档：LOW 3 / MED 4 / HIGH 5 个齿轮 ──
        gearCount = when (ctx.quality) {
            com.nasmusic.tv.data.model.VisualQuality.LOW -> 3
            com.nasmusic.tv.data.model.VisualQuality.MEDIUM -> 4
            com.nasmusic.tv.data.model.VisualQuality.HIGH -> 5
        }

        // 齿轮轮廓预生成（单位半径）
        gearPaths.clear()
        repeat(gearCount) {
            gearPaths.add(buildGearPath(TEETH))
        }

        gearR = FloatArray(gearCount)
        gearDir = IntArray(gearCount)
        gearIsBeatDriven = BooleanArray(gearCount)
        gearAngle = FloatArray(gearCount)
        gearTarget = FloatArray(gearCount)
        for (i in 0 until gearCount) {
            gearR[i] = 0.42f - i * 0.075f      // 0.42 / 0.345 / 0.27 / 0.195 / 0.12
            gearDir[i] = if (i % 2 == 0) 1 else -1
            // 大齿轮（前 2 个）beat 驱动棘轮；小齿轮 treble 连续旋转
            gearIsBeatDriven[i] = i < 2
        }
    }

    /** 生成齿轮轮廓（单位半径）：外齿 + 内圈 */
    private fun buildGearPath(teeth: Int): Path {
        val p = Path()
        val seg = teeth * 4
        var i = 0
        while (i < seg) {
            // 每齿 4 段：齿根→齿升→齿顶→齿降
            val phase = i % 4
            val tooth = i / 4
            val baseA = tooth * TAU / teeth
            val step = TAU / teeth
            val rOut = 1.0f
            val rIn = 0.86f
            val a = when (phase) {
                0 -> baseA
                1 -> baseA + step * 0.22f
                2 -> baseA + step * 0.50f
                else -> baseA + step * 0.72f
            }
            val r = when (phase) {
                0, 3 -> rIn
                else -> rOut
            }
            val x = cos(a) * r
            val y = sin(a) * r
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            i++
        }
        p.close()
        return p
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now

        trebleSmooth += (frame.treble - trebleSmooth) * 0.20f

        val cx = w * 0.5f
        val cy = h * 0.5f
        val unit = ctx.minDim * 0.46f

        // ── 棘轮：beat 上升沿（pulse 涨跳）推进大齿轮目标角 ──
        val pulse = frame.pulse
        if (pulse - lastPulse > 0.18f) {
            var g = 0
            while (g < gearCount) {
                if (gearIsBeatDriven[g]) {
                    // 每拍推进一个齿距（12 齿 → 30°）
                    gearTarget[g] += TAU / TEETH
                }
                g++
            }
        }
        lastPulse = pulse

        // 大齿轮向目标角快速缓动（120ms 到位 → 机械棘轮感）
        var g = 0
        while (g < gearCount) {
            if (gearIsBeatDriven[g]) {
                val diff = gearTarget[g] - gearAngle[g]
                gearAngle[g] += diff * (dtSec * 1000f / RATCHET_MS).coerceIn(0f, 1f)
            } else {
                // 小齿轮：treble 疯狂旋转
                gearAngle[g] += (2.5f + trebleSmooth * 9f) * gearDir[g] * dtSec
            }
            if (gearAngle[g] > TAU) gearAngle[g] -= TAU
            if (gearAngle[g] < -TAU) gearAngle[g] += TAU
            g++
        }

        // ── 绘制：从大到小，暗金→冷灰交替 ──
        g = 0
        while (g < gearCount) {
            val r = gearR[g] * unit
            val color = if (g % 2 == 0) gold else coldGray
            val alpha = (0.9f - g * 0.10f).coerceAtLeast(0.5f)
            val path = gearPaths[g]

            withTransform({
                translate(cx, cy)
                rotate(gearAngle[g] * DEG)
                scale(r, r, pivot = Offset.Zero)
            }) {
                drawPath(path, color, style = Stroke(1.6f / r), alpha = alpha,
                    blendMode = BlendMode.Plus)
                // 内圈（轴心装饰）
                drawCircle(color, radius = 0.30f, style = Stroke(1.2f / r), alpha = alpha * 0.8f)
                // 辐条 × 4
                var spoke = 0
                while (spoke < 4) {
                    val a = spoke * TAU / 4
                    drawLine(
                        color, Offset(cos(a) * 0.32f, sin(a) * 0.32f),
                        Offset(cos(a) * 0.78f, sin(a) * 0.78f),
                        strokeWidth = 1.0f / r, alpha = alpha * 0.7f,
                        blendMode = BlendMode.Plus
                    )
                    spoke++
                }
            }
            g++
        }

        // ── 中心轴点：pulse 脉动 ──
        drawCircle(goldDim, radius = unit * (0.03f + frame.pulse * 0.012f),
            center = Offset(cx, cy), alpha = 0.9f)
    }
}

/**
 * E34 `FRACTAL_TREE` — 分形 · 极简分形树
 *
 * 视觉：屏幕底部中心一根不断分叉的极简树状结构（细线，无叶）。
 * 低音让主干变粗、分支向外生长（深度逐层展开）；高频让分支尖端闪烁电弧。
 *
 * 实现：**不每帧递归**。onEnter 把分叉拓扑拍平进数组（每段记录深度/父段/角度系数），
 * 每帧只做端点计算 + 深度门控绘制。生长 = bass 驱动"展开深度"整数推进。
 *
 * 性能红线：draw 内零分配。
 */
class FractalTreeRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.FRACTAL_TREE

    private companion object {
        const val MAX_DEPTH = 8
        /** 展开深度缓动速率 */
        const val DEPTH_SPEED = 1.8f
        const val ARC_JAG = 3             // 电弧折线段数
    }

    /** 拓扑段数组（onEnter 预生成，最大 2^9-1 = 511 段） */
    private var segDepth = IntArray(0)      // 深度 0=主干
    private var segParent = IntArray(0)     // 父段索引（-1=根）
    private var segSide = IntArray(0)       // -1 左枝 / +1 右枝 / 0 主干
    private var segAngleK = FloatArray(0)   // 相对父段的角度偏移系数
    private var segCount = 0

    /** 每帧计算的端点（供递归画线） */
    private var endX = FloatArray(0)
    private var endY = FloatArray(0)
    private var endAng = FloatArray(0)

    /** 当前展开深度（0..MAX_DEPTH，bass 驱动缓慢推进） */
    private var depthF = 0f

    private var lastMs = 0L

    /** 当前展开深度上限（onEnter 时固化，避免每帧扫描） */
    private var maxDepth = 8

    override fun onEnter(ctx: RenderContext) {
        depthF = 0f
        lastMs = 0L

        // ── 画质分档：LOW 深度 6 / MED 7 / HIGH 8 ──
        maxDepth = when (ctx.quality) {
            com.nasmusic.tv.data.model.VisualQuality.LOW -> 6
            com.nasmusic.tv.data.model.VisualQuality.MEDIUM -> 7
            com.nasmusic.tv.data.model.VisualQuality.HIGH -> MAX_DEPTH
        }

        // 生成拓扑（前序遍历）
        val cap = (1 shl (maxDepth + 1)) - 1
        segDepth = IntArray(cap)
        segParent = IntArray(cap)
        segSide = IntArray(cap)
        segAngleK = FloatArray(cap)
        endX = FloatArray(cap)
        endY = FloatArray(cap)
        endAng = FloatArray(cap)
        segCount = 0

        var rng = 0xA11CEu
        fun nextRand(): Float {
            rng = rng * 1664525u + 1013904223u
            return (rng shr 8).toFloat() / 16777216f
        }

        // 迭代式前序生成（深度优先，父段先于子段）
        fun addSeg(depth: Int, parent: Int, side: Int, angleK: Float) {
            if (segCount >= cap) return
            segDepth[segCount] = depth
            segParent[segCount] = parent
            segSide[segCount] = side
            segAngleK[segCount] = angleK
            segCount++
        }

        // 用显式栈模拟递归（Kotlin 局部函数递归也可，这里用数组栈避免深层调用）
        val stackDepth = IntArray(cap)
        val stackParent = IntArray(cap)
        val stackSide = IntArray(cap)
        val stackAngleK = FloatArray(cap)
        var sp = 0
        stackDepth[sp] = 0; stackParent[sp] = -1; stackSide[sp] = 0; stackAngleK[sp] = 0f
        sp++
        while (sp > 0) {
            sp--
            val d = stackDepth[sp]
            val par = stackParent[sp]
            val side = stackSide[sp]
            val ak = stackAngleK[sp]
            val idx = segCount
            addSeg(d, par, side, ak)
            if (d < maxDepth) {
                // 子段压栈：先压右（后画），再压左（先画）→ 左枝先序
                // 右枝
                stackDepth[sp] = d + 1; stackParent[sp] = idx
                stackSide[sp] = 1
                stackAngleK[sp] = (0.45f + nextRand() * 0.30f)
                sp++
                // 左枝
                stackDepth[sp] = d + 1; stackParent[sp] = idx
                stackSide[sp] = -1
                stackAngleK[sp] = -(0.45f + nextRand() * 0.30f)
                sp++
            }
        }
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now

        // ── 生长：bass 驱动展开深度（含 energy 底盘保证安静歌也缓慢生长）──
        val growRate = 0.15f + frame.bass * DEPTH_SPEED
        depthF = (depthF + growRate * dtSec).coerceAtMost(maxDepth.toFloat())
        val depthInt = depthF.toInt()
        val depthFrac = depthF - depthInt

        val accent = ctx.palette.accent

        // ── 主干基准 ──
        val trunkLen = h * 0.22f
        val baseX = w * 0.5f
        val baseY = h * 0.88f

        // ── 逐段计算端点（前序序保证父先于子）──
        var i = 0
        while (i < segCount) {
            val d = segDepth[i]
            val par = segParent[i]
            val lenK = 0.72f                       // 每层长度衰减
            val px: Float; val py: Float; val pa: Float
            if (par < 0) {
                px = baseX; py = baseY; pa = -1.5707964f   // -90°（向上）
            } else {
                px = endX[par]; py = endY[par]; pa = endAng[par]
            }
            // 角度：父角 + 分叉系数 + 随 music 轻微摆动
            val sway = frame.treble * 0.10f * d
            val ang = pa + segAngleK[i] * (0.85f + 0.15f * frame.mid) + sway * segSide[i]
            // 深度门控：正在展开的层做分数长度（生长感）
            val grow = if (d <= depthInt) 1f else if (d == depthInt + 1) depthFrac * 0.8f else 0f
            if (grow <= 0f) {
                endX[i] = px; endY[i] = py; endAng[i] = ang
                i++
                continue
            }
            val len = trunkLen * lenK.pow(d) * grow * (0.9f + 0.2f * frame.bass)
            endX[i] = px + cos(ang) * len
            endY[i] = py + sin(ang) * len
            endAng[i] = ang
            i++
        }

        // ── 绘制：逐段画线，主干粗、末梢细 ──
        i = 0
        while (i < segCount) {
            val d = segDepth[i]
            val par = segParent[i]
            val grow = if (d <= depthInt) 1f else if (d == depthInt + 1) depthFrac * 0.8f else 0f
            if (grow <= 0f) { i++; continue }
            val px: Float; val py: Float
            if (par < 0) { px = baseX; py = baseY } else { px = endX[par]; py = endY[par] }
            val ex = endX[i]; val ey = endY[i]

            // 主干变粗：低音驱动；逐层变细
            val baseW = (2.6f - d * 0.28f).coerceAtLeast(0.8f)
            val width = baseW * (1f + frame.bass * 0.9f)
            val alpha = (0.9f - d * 0.08f).coerceAtLeast(0.4f)

            drawLine(accent, Offset(px, py), Offset(ex, ey), strokeWidth = width, alpha = alpha)

            // ── 电弧：末梢段（最外两层）+ treble 超阈值时确定性抖动 ──
            if (d >= depthInt - 1 && depthInt >= 2 && frame.treble > 0.40f) {
                drawArcJitter(px, py, ex, ey, width, accent, frame.seq, i)
            }
            i++
        }
    }

    /** 末梢电弧：3 段折线确定性抖动（零分配） */
    private fun DrawScope.drawArcJitter(
        x0: Float, y0: Float, x1: Float, y1: Float,
        baseW: Float, color: Color, seq: Long, idx: Int
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val nx = -dy
        val ny = dx
        val nLen = kotlin.math.sqrt(nx * nx + ny * ny).coerceAtLeast(0.001f)
        var prevX = x0
        var prevY = y0
        var s = 1
        while (s <= ARC_JAG) {
            val t = s.toFloat() / ARC_JAG
            val jx = x0 + dx * t
            val jy = y0 + dy * t
            // 确定性抖动：帧序号 + 段索引做种子
            val seed = (seq * 31 + idx * 7 + s) and 0xFFFFL
            val mag = ((seed % 200L) / 200f - 0.5f) * baseW * 2.2f
            val ox = nx / nLen * mag
            val oy = ny / nLen * mag
            val cx = jx + ox
            val cy = jy + oy
            drawLine(color, Offset(prevX, prevY), Offset(cx, cy),
                strokeWidth = baseW * 0.6f, alpha = 0.85f, blendMode = BlendMode.Plus)
            prevX = cx; prevY = cy
            s++
        }
    }

    private fun Float.pow(n: Int): Float {
        var r = 1f
        var b = this
        var e = n
        while (e > 0) {
            if (e and 1 == 1) r *= b
            b *= b
            e = e shr 1
        }
        return r
    }
}

/**
 * E35 `LIGHT_BEAMS` — 光轴 · 旋转光轴
 *
 * 视觉：几束极细光束从屏幕边缘向中心射出（激光灯交叉扫射），带轻微扇形区域。
 * 低音改变光束仰角（缓变），高频让光束瞬间碎裂成虚线（手动分段，避开
 * dashPathEffect 的每帧分配）。
 *
 * 性能红线：draw 内零分配。
 */
class LightBeamsRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.LIGHT_BEAMS

    private companion object {
        const val TAU = (2 * Math.PI).toFloat()
        const val BEAMS = 6
        const val SEGMENTS = 14            // 虚线分段数
        const val ELEV_SMOOTH = 0.06f      // 仰角低通
        const val MAX_ELEV_DEG = 14f
    }

    /** 每束光的静态参数 */
    private var beamPhase = FloatArray(0)
    private var beamSpeed = FloatArray(0)
    private var beamBaseAng = FloatArray(0)
    private var beamCount = 0

    private var elevSmooth = 0f
    private var lastMs = 0L
    private var trebleSmooth = 0f

    override fun onEnter(ctx: RenderContext) {
        elevSmooth = 0f
        lastMs = 0L
        trebleSmooth = 0f

        beamCount = when (ctx.quality) {
            com.nasmusic.tv.data.model.VisualQuality.LOW -> 4
            com.nasmusic.tv.data.model.VisualQuality.MEDIUM -> 6
            com.nasmusic.tv.data.model.VisualQuality.HIGH -> 8
        }
        beamPhase = FloatArray(beamCount)
        beamSpeed = FloatArray(beamCount)
        beamBaseAng = FloatArray(beamCount)
        var i = 0
        while (i < beamCount) {
            beamPhase[i] = i * 2.399f        // 黄金角错相位
            beamSpeed[i] = 0.35f + (i % 3) * 0.18f
            beamBaseAng[i] = i * TAU / beamCount
            i++
        }
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now

        elevSmooth += (frame.bass - elevSmooth) * ELEV_SMOOTH
        trebleSmooth += (frame.treble - trebleSmooth) * 0.25f

        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxLen = kotlin.math.sqrt(w * w + h * h) * 0.55f
        val accent = ctx.palette.accent
        val secondary = ctx.palette.secondary
        val tSec = frame.timeMs * 0.001f

        // ── 逐束绘制 ──
        var i = 0
        while (i < beamCount) {
            // 旋转：各束不同速慢转 + treble 加速
            val ang = beamBaseAng[i] + tSec * beamSpeed[i] * (0.5f + trebleSmooth * 0.8f) +
                beamPhase[i] * 0.1f
            // 仰角扰动：低音驱动（低通后）+ 每束相位差
            val elev = elevSmooth * MAX_ELEV_DEG * 0.01745f * sin(tSec * 0.6f + beamPhase[i])
            val finalAng = ang + elev
            val color = if (i % 2 == 0) accent else secondary
            val broken = frame.treble > 0.50f   // 碎裂触发

            val dx = cos(finalAng)
            val dy = sin(finalAng)
            // 从边缘向中心：起点在半径 1.1×外接圆处，终点在中心附近
            val startR = maxLen * 1.05f
            val endR = maxLen * 0.06f

            if (!broken) {
                // 连续光束：宽淡辉光 + 细亮芯线
                drawLine(
                    color,
                    Offset(cx + dx * startR, cy + dy * startR),
                    Offset(cx + dx * endR, cy + dy * endR),
                    strokeWidth = 7f, alpha = 0.10f, blendMode = BlendMode.Plus
                )
                drawLine(
                    color,
                    Offset(cx + dx * startR, cy + dy * startR),
                    Offset(cx + dx * endR, cy + dy * endR),
                    strokeWidth = 1.6f, alpha = 0.75f, blendMode = BlendMode.Plus
                )
                // 扇形区域（极弱）
                drawFan(cx, cy, finalAng, maxLen, color, 0.05f + frame.energy * 0.05f)
            } else {
                // 碎裂虚线：手动分段 + 确定性闪烁（避开 dashPathEffect 每帧分配）
                val segLen = (startR - endR) / SEGMENTS
                var s = 0
                while (s < SEGMENTS) {
                    val r0 = startR - s * segLen
                    val r1 = r0 - segLen * 0.55f   // 55% 占空比
                    // 确定性明暗
                    val seed = (frame.seq * 17 + i * 31 + s) and 0xFFFFL
                    val flick = ((seed % 5L) < 3L)
                    if (flick) {
                        val a = 0.55f + (seed % 4L) / 4f * 0.35f
                        drawLine(
                            color,
                            Offset(cx + dx * r0, cy + dy * r0),
                            Offset(cx + dx * r1, cy + dy * r1),
                            strokeWidth = 1.8f, alpha = a.toFloat(),
                            blendMode = BlendMode.Plus
                        )
                    }
                    s++
                }
            }
            i++
        }

        // ── 中心光核：pulse 脉动 ──
        val coreR = 6f + frame.pulse * 14f
        drawCircle(accent, radius = coreR * 1.8f, center = Offset(cx, cy), alpha = 0.12f, blendMode = BlendMode.Plus)
        drawCircle(accent, radius = coreR, center = Offset(cx, cy), alpha = 0.55f, blendMode = BlendMode.Plus)
    }

    /** 极弱扇形（光束的面积感） */
    private fun DrawScope.drawFan(
        cx: Float, cy: Float, ang: Float, r: Float, color: Color, alpha: Float
    ) {
        if (alpha <= 0.01f) return
        val halfW = 0.045f
        val p = pathBuf
        p.reset()
        p.moveTo(cx, cy)
        val steps = 6
        var s = 0
        while (s <= steps) {
            val a = ang - halfW + (2 * halfW) * s / steps
            p.lineTo(cx + cos(a) * r, cy + sin(a) * r)
            s++
        }
        p.close()
        drawPath(p, color, alpha = alpha)
    }

    private val pathBuf = Path()
}

/**
 * E36 `FERMAT_SPIRAL` — 螺旋 · 费马螺旋（向日葵）
 *
 * 视觉：数百个小点按黄金角 137.5° 螺旋分布（葵花籽排列），极度规则舒适。
 * 低音让整体螺旋向外膨胀松散（黄金角动态偏移 1~3°）；高频让内圈点组整体旋转。
 * 点亮度按环序映射 spectrum 频段（内圈高频外圈低频）。
 *
 * 性能红线：draw 内零分配。点位 onEnter 预计算存 FloatArray。
 */
class FermatSpiralRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.FERMAT_SPIRAL

    private companion object {
        const val GOLDEN_ANGLE = 2.39996f      // 137.507°
        const val POINTS_LOW = 160
        const val POINTS_MED = 260
        const val POINTS_HIGH = 380
        const val RING_BUCKETS = 4
    }

    /** 预计算点位（相对半径 0..1 × 点序号） */
    private var ptR = FloatArray(0)          // 相对半径（0..1，对应最大半径）
    private var ptAng = FloatArray(0)        // 基础角
    private var ptCount = 0

    /** 内圈旋转组：内圈 K 个点作为一组整体旋转（视觉等价、零逐点开销） */
    private var innerGroupRot = 0f
    private var trebleSmooth = 0f
    private var bassSmooth = 0f
    private var goldenOffset = 0f
    private var lastMs = 0L

    override fun onEnter(ctx: RenderContext) {
        innerGroupRot = 0f
        trebleSmooth = 0f
        bassSmooth = 0f
        goldenOffset = 0f
        lastMs = 0L

        ptCount = when (ctx.quality) {
            com.nasmusic.tv.data.model.VisualQuality.LOW -> POINTS_LOW
            com.nasmusic.tv.data.model.VisualQuality.MEDIUM -> POINTS_MED
            com.nasmusic.tv.data.model.VisualQuality.HIGH -> POINTS_HIGH
        }
        ptR = FloatArray(ptCount)
        ptAng = FloatArray(ptCount)
        var i = 0
        while (i < ptCount) {
            ptR[i] = kotlin.math.sqrt(i + 0.5f) / kotlin.math.sqrt(ptCount.toFloat())
            ptAng[i] = i * GOLDEN_ANGLE
            i++
        }
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now

        trebleSmooth += (frame.treble - trebleSmooth) * 0.20f
        bassSmooth += (frame.bass - bassSmooth) * 0.10f

        // ── 膨胀：低音驱动半径放大 ──
        val inflate = 1f + bassSmooth * 0.28f
        // ── 松散：黄金角动态偏移（低音越大越松散）──
        goldenOffset += (bassSmooth * 1.5f) * dtSec
        val maxR = ctx.minDim * 0.40f * inflate

        val cx = w * 0.5f
        val cy = h * 0.5f
        val accent = ctx.palette.accent
        val secondary = ctx.palette.secondary

        // ── 内圈旋转组角度：treble 驱动 ──
        innerGroupRot += (0.4f + trebleSmooth * 2.4f) * dtSec

        // spectrum 桶边界（内圈=高频桶，外圈=低频桶）
        val bins = frame.spectrum.size
        val s = frame.spectrum

        var i = 0
        while (i < ptCount) {
            val rr = ptR[i]
            val bin = ((1f - rr) * (bins - 1)).toInt().coerceIn(0, bins - 1)  // 内圈高频
            val v = s[bin]

            // 角度：基础角 + 松散偏移（随半径放大外圈偏移更多）+ 内圈组旋转
            val innerRot = if (rr < 0.35f) innerGroupRot else 0f
            val ang = ptAng[i] + goldenOffset * rr * 3f + innerRot
            val radius = rr * maxR
            val x = cx + cos(ang) * radius
            val y = cy + sin(ang) * radius

            // ── 颜色与亮度：内圈 accent，外圈 accent/secondary 混合，亮度=v ──
            val color = if (rr < 0.5f) accent else secondary
            val alpha = (0.22f + v * 0.75f).coerceAtMost(0.95f)
            val dotR = (1.4f + v * 2.6f) * (0.8f + rr * 0.4f)

            drawCircle(color, radius = dotR, center = Offset(x, y), alpha = alpha)
            i++
        }

        // ── 中心装饰：极小光点 ──
        drawCircle(accent, radius = 2.5f + frame.pulse * 4f, center = Offset(cx, cy), alpha = 0.9f)
    }
}

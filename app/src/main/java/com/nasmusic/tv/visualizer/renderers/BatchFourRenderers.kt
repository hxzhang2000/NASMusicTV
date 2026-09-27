package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.cos
import kotlin.math.sin

/**
 * E33 `CONCENTRIC_GEARS` — 齿轮 · 啮合齿轮系（行星轮）
 *
 * 视觉：暗金/冷灰配色的线框齿轮系，机械、复古、时钟质感。
 * 布局为行星轮系：中央太阳轮（12 齿）作驱动轮，2~4 个行星轮（6 齿）沿太阳轮
 * 齿距整数倍的角位布置并与之啮合。齿数 ∝ 半径（模数恒定、节距一致），啮合
 * 中心距 = 齿顶 + 齿根 + 齿隙。太阳轮由鼓点棘轮驱动（beat 上升沿每拍推进一个
 * 齿距，~120ms 快速缓动到位）；行星轮按齿数比反向锁定跟随（|ω|·N 恒定、初始
 * 半齿相位差 → 齿/槽永远交错啮合）。高频只做全局调速（共享蠕行 + 棘轮提速），
 * 不引入任何独立自转，啮合相位永不破坏。
 *
 * 性能红线：draw 内零分配。齿轮单位顶点 onEnter 预生成，每帧手算世界坐标烘焙旋转
 * （照手动旋转模式，绝不用 withTransform({ rotate })——其默认 pivot=画布中心，
 * 会让所有齿轮绕同一固定点公转）。
 */
class ConcentricGearsRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.CONCENTRIC_GEARS

    private companion object {
        const val TAU = (2 * Math.PI).toFloat()
        const val RATCHET_MS = 120f        // 刻度缓动时长
        const val SUN_TEETH = 12           // 太阳轮齿数（驱动轮）
        const val SAT_TEETH = 6            // 行星轮齿数（齿数比 2:1，模数一致）
        const val SUN_R = 0.36f            // 太阳轮绘制半径（×unit）
        const val SAT_R = 0.18f            // 行星轮绘制半径（×unit）
        const val ROOT_K = 0.86f           // 齿根系数（path 齿根 = 0.86 × 外径）
        const val MESH_CLEAR = 0.005f      // 齿顶-齿根间隙（×unit）
        const val UNIT_K = 0.67f           // unit = minDim × 0.67（整机外缘 ≈ 0.47·minDim < 0.48）
    }

    // 暗金配色（固定，不用封面色——机械时钟质感自成体系）
    private val gold = Color(0xFFC9A227)
    private val goldDim = Color(0xFF8A7418)
    private val coldGray = Color(0xFF8B95A1)

    /** 每个齿轮的单位顶点数组（onEnter 预生成，[x0,y0,x1,y1,...]，单位半径） */
    private val gearVerts = mutableListOf<FloatArray>()

    /** draw 内复用的单例 Path（零分配：每帧 reset 重填世界坐标顶点） */
    private val gearPath = Path()

    /** 每个齿轮的静态参数 */
    private var gearR = FloatArray(0)         // 绘制半径（×unit）
    private var gearOffX = FloatArray(0)      // 中心偏移（×unit，相对屏幕中心）
    private var gearOffY = FloatArray(0)
    private var gearRatio = FloatArray(0)     // 角速度比（太阳轮=1，行星轮=-N_sun/N_sat 反向锁定）
    private var gearPhase = FloatArray(0)     // 初始相位（rad，半齿差 → 保证啮合）
    private var gearCount = 0

    private var sunAngle = 0f        // 太阳轮当前角（整组齿轮的唯一驱动角）
    private var sunTarget = 0f       // 棘轮目标角
    private var lastPulse = 0f
    private var lastMs = 0L
    private var trebleSmooth = 0f

    override fun onEnter(ctx: RenderContext) {
        lastPulse = 0f
        lastMs = 0L
        trebleSmooth = 0f
        sunAngle = 0f
        sunTarget = 0f

        // ── 画质分档：LOW 3 / MED 4 / HIGH 5 个齿轮（太阳轮 + 2/3/4 行星轮）──
        val sats = when (ctx.quality) {
            com.nasmusic.tv.data.model.VisualQuality.LOW -> 2
            com.nasmusic.tv.data.model.VisualQuality.MEDIUM -> 3
            else -> 4
        }
        gearCount = sats + 1

        // 啮合中心距 = 太阳轮齿顶 + 行星轮齿根 + 齿隙（×unit）
        val centerDist = SUN_R + ROOT_K * SAT_R + MESH_CLEAR

        // 齿轮单位顶点预生成（单位半径，齿数 ∝ 半径 → 模数恒定）
        // 顶点存为 FloatArray [x0,y0,x1,y1,...]，draw 时手算世界坐标烘焙旋转
        gearVerts.clear()
        gearVerts.add(buildGearVerts(SUN_TEETH))
        var i = 0
        while (i < sats) {
            gearVerts.add(buildGearVerts(SAT_TEETH))
            i++
        }

        gearR = FloatArray(gearCount)
        gearOffX = FloatArray(gearCount)
        gearOffY = FloatArray(gearCount)
        gearRatio = FloatArray(gearCount)
        gearPhase = FloatArray(gearCount)

        // 太阳轮（驱动轮，屏幕中心）
        gearR[0] = SUN_R
        gearOffX[0] = 0f
        gearOffY[0] = 0f
        gearRatio[0] = 1f
        gearPhase[0] = 0f

        // 行星轮：角位取太阳轮齿距整数倍（各接触点相位一致），锁定跟随 + 半齿初始相位
        i = 0
        while (i < sats) {
            val k = i + 1
            val a = i * TAU / sats          // 0°/180° / 0°/120°/240° / 0°/90°/180°/270°
            gearR[k] = SAT_R
            gearOffX[k] = cos(a) * centerDist
            gearOffY[k] = sin(a) * centerDist
            gearRatio[k] = -SUN_TEETH.toFloat() / SAT_TEETH   // 反向、齿数比锁定
            gearPhase[k] = TAU / (2 * SAT_TEETH)              // = π/N_sat（半齿相位差）
            i++
        }
    }

    /** 生成齿轮单位顶点数组（[x0,y0,x1,y1,...]，半径 1.0/0.86 交替） */
    private fun buildGearVerts(teeth: Int): FloatArray {
        val seg = teeth * 4
        val verts = FloatArray(seg * 2)
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
            verts[i * 2] = cos(a) * r
            verts[i * 2 + 1] = sin(a) * r
            i++
        }
        return verts
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
        val unit = ctx.minDim * UNIT_K

        // ── 棘轮：beat 上升沿（pulse 涨跳）推进太阳轮目标角（一个齿距）──
        val pulse = frame.pulse
        if (pulse - lastPulse > 0.18f) {
            sunTarget += TAU / SUN_TEETH
        }
        lastPulse = pulse

        // ── 驱动角：棘轮缓动（120ms 到位）+ 极慢匀速基线 ──
        //   ⚠️ 不接 treble 调速：treble 帧间抖动会让角速度忽快忽慢，啮合观感像"乱跑"；
        //   齿轮只绕自身轴心匀速旋转 + 节拍推进，啮合相位恒定不被打散
        val ease = (dtSec * 1000f / RATCHET_MS).coerceIn(0f, 1f)
        sunAngle += (sunTarget - sunAngle) * ease
        sunAngle += 0.30f * dtSec        // 匀速基线 ~17°/s，平滑可见
        if (sunAngle > TAU) { sunAngle -= TAU; sunTarget -= TAU }
        if (sunAngle < -TAU) { sunAngle += TAU; sunTarget += TAU }

        // ── 轨道环（极淡，行星轮系结构感；treble 微调亮度不参与转速）──
        val orbitR = SUN_R + ROOT_K * SAT_R + MESH_CLEAR
        drawCircle(goldDim, radius = orbitR * unit, center = Offset(cx, cy),
            style = Stroke(1f), alpha = (0.10f + trebleSmooth * 0.06f).coerceAtMost(0.18f),
            blendMode = BlendMode.Plus)

        // ── 绘制：太阳轮（最亮）→ 行星轮交替暗金/冷灰 ──
        //   ⚠️ 旋转烘焙进顶点（手算世界坐标），绝不用 withTransform({ rotate })：
        //   Compose DrawTransform.rotate 默认 pivot=画布中心，会让所有齿轮绕同一固定点
        //   公转（用户反馈"整体绕右下角旋转"的根因）。照手动旋转模式：cos/sin
        //   矩阵作用于单位顶点，齿轮绕自身 (x,y) 自转。
        var g = 0
        while (g < gearCount) {
            val r = gearR[g] * unit
            val color = if (g % 2 == 0) gold else coldGray
            val alpha = (0.95f - g * 0.06f).coerceAtLeast(0.55f)
            val verts = gearVerts[g]
            // 行星轮角 = 齿数比锁定跟随驱动角（啮合永不破）+ 初始相位
            val angle = sunAngle * gearRatio[g] + gearPhase[g]
            val x = cx + gearOffX[g] * unit
            val y = cy + gearOffY[g] * unit
            val cosA = cos(angle)
            val sinA = sin(angle)

            // 齿轮轮廓：单位顶点 → 世界坐标烘焙旋转，复用单例 Path（零分配）
            gearPath.reset()
            var vi = 0
            while (vi < verts.size) {
                val ux = verts[vi]
                val uy = verts[vi + 1]
                val wx = x + r * (ux * cosA - uy * sinA)
                val wy = y + r * (ux * sinA + uy * cosA)
                if (vi == 0) gearPath.moveTo(wx, wy) else gearPath.lineTo(wx, wy)
                vi += 2
            }
            gearPath.close()
            drawPath(gearPath, color, style = Stroke(1.6f), alpha = alpha,
                blendMode = BlendMode.Plus)

            // 轴毂：显式绝对中心，无 transform（半径/stroke 均为屏幕像素）
            drawCircle(color, radius = r * 0.30f, center = Offset(x, y),
                style = Stroke(1.2f), alpha = alpha * 0.8f, blendMode = BlendMode.Plus)

            // 辐条 ×4：手算旋转后端点（spoke 本地角 + 齿轮角）
            var spoke = 0
            while (spoke < 4) {
                val sa = spoke * TAU / 4 + angle
                val cs = cos(sa)
                val ss = sin(sa)
                drawLine(color,
                    Offset(x + r * 0.32f * cs, y + r * 0.32f * ss),
                    Offset(x + r * 0.78f * cs, y + r * 0.78f * ss),
                    strokeWidth = 1.0f, alpha = alpha * 0.7f, blendMode = BlendMode.Plus)
                spoke++
            }
            g++
        }

        // ── 各齿轮轴心点：pulse 脉动（轴静止不随齿轮旋转，独立绘制）──
        g = 0
        while (g < gearCount) {
            val r = gearR[g] * unit
            val axR = r * (0.05f + frame.pulse * 0.02f)
            drawCircle(goldDim, radius = axR,
                center = Offset(cx + gearOffX[g] * unit, cy + gearOffY[g] * unit),
                alpha = 0.9f, blendMode = BlendMode.Plus)
            g++
        }
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


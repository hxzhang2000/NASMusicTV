package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
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
 * E28 `BAUHAUS_SHAPES` — 构成 · 包豪斯几何拼贴
 *
 * 视觉：扁平化设计，画面中漂浮纯色基础几何体（半圆/三角/长条矩形），
 * 莫兰迪色系（低饱和对比色）。几何体随音乐缓慢旋转、平移、遮挡重叠。
 *
 * 律动：低音让大色块放大；高频让小色块快速翻转（scale(-1,1) 垂直翻转，
 * 比 3D 旋转便宜得多且视觉等价）；中频驱动缓慢平移漂移。
 *
 * 性能红线：draw 内零分配。全部 Path 与颜色表在 onEnter 预生成，
 * 每帧只更新成员数组中的位置/相位并复用 Path。
 */
class BauhausShapesRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.BAUHAUS_SHAPES

    /** 莫兰迪色系（固定调色板，与封面无关——包豪斯配色自成体系） */
    private val colors = intArrayOf(
        0xFFA8B5A2.toInt(),  // 灰绿
        0xFFC7A08B.toInt(),  // 陶土
        0xFF9AA7B8.toInt(),  // 雾蓝
        0xFFD6C6AF.toInt(),  // 米杏
        0xFFB5A2A8.toInt(),  // 灰粉
        0xFF8F9E8B.toInt(),  // 橄榄
        0xFFC2B8A3.toInt()   // 亚麻
    )

    /** 形状种类与常量 */
    private companion object {
        const val SHAPE_SEMICIRCLE = 0
        const val SHAPE_TRIANGLE = 1
        const val SHAPE_BAR = 2
        const val SHAPE_CIRCLE = 3
        const val SHAPE_QUARTER = 4
        const val MAX_SHAPES = 18
        const val TAU = (2 * Math.PI).toFloat()
        const val DEG2RAD = 0.0174533f
        const val FLIP_TRIGGER = 0.55f
    }

    /** 每个形状的静态属性（onEnter 预生成） */
    private var shapeType = IntArray(0)
    private var shapeColorIdx = IntArray(0)
    private var shapeSize = FloatArray(0)
    private var shapeBaseX = FloatArray(0)
    private var shapeBaseY = FloatArray(0)
    private var shapeSpeed = FloatArray(0)     // 漂移速度系数
    private var shapePhase = FloatArray(0)     // 初始相位

    /** 每帧更新的动态状态 */
    private var shapeFlip = FloatArray(0)      // 翻转插值 0..1（1 = 已翻转）
    private var shapeRot = FloatArray(0)       // 累计旋转角

    /** 复用 Path（每帧 reset） */
    private val path = Path()

    private var lastMs = 0L
    private var bassSmooth = 0f
    private var trebleSmooth = 0f

    /** 确定性随机游标（onEnter 重置，形状初始化用） */
    private var rng = 12345u

    private fun nextRand(): Float {
        rng = rng * 1664525u + 1013904223u
        return (rng shr 8).toFloat() / 16777216f
    }

    override fun onEnter(ctx: RenderContext) {
        lastMs = 0L
        bassSmooth = 0f
        trebleSmooth = 0f
        rng = 12345u

        // ── 画质分档：LOW 8 / MEDIUM 13 / HIGH 18 ──
        val n = when (ctx.quality) {
            com.nasmusic.tv.data.model.VisualQuality.LOW -> 8
            com.nasmusic.tv.data.model.VisualQuality.MEDIUM -> 13
            com.nasmusic.tv.data.model.VisualQuality.HIGH -> MAX_SHAPES
        }
        shapeType = IntArray(n)
        shapeColorIdx = IntArray(n)
        shapeSize = FloatArray(n)
        shapeBaseX = FloatArray(n)
        shapeBaseY = FloatArray(n)
        shapeSpeed = FloatArray(n)
        shapePhase = FloatArray(n)
        shapeFlip = FloatArray(n)
        shapeRot = FloatArray(n)

        for (i in 0 until n) {
            // 大色块（低音驱动）放后段，小色块（高频翻转）放前段
            val isBig = i >= n * 2 / 3
            shapeType[i] = (nextRand() * 5f).toInt().coerceIn(0, 4)
            shapeColorIdx[i] = (nextRand() * colors.size).toInt().coerceIn(0, colors.size - 1)
            shapeSize[i] = if (isBig) 0.16f + nextRand() * 0.10f else 0.05f + nextRand() * 0.07f
            shapeBaseX[i] = 0.10f + nextRand() * 0.80f
            shapeBaseY[i] = 0.10f + nextRand() * 0.80f
            shapeSpeed[i] = 0.4f + nextRand() * 0.8f
            shapePhase[i] = nextRand() * (2f * Math.PI).toFloat()
            shapeFlip[i] = 0f
            shapeRot[i] = nextRand() * (2f * Math.PI).toFloat()
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

        bassSmooth += (frame.bass - bassSmooth) * 0.12f
        trebleSmooth += (frame.treble - trebleSmooth) * 0.22f

        val tSec = frame.timeMs * 0.001f
        val n = shapeType.size

        // ── 先画大色块（背景层），再画小色块（前景层）──
        var i = n - 1
        while (i >= 0) {
            drawShape(i, w, h, tSec, dtSec, frame)
            i--
        }
    }

    private fun DrawScope.drawShape(
        i: Int, w: Float, h: Float, tSec: Float, dtSec: Float, frame: AudioFrame
    ) {
        val isBig = i >= shapeType.size * 2 / 3
        val phase = shapePhase[i]
        val speed = shapeSpeed[i]

        // ── 缓慢漂移：中频驱动的 Lissajous 平移 ──
        val drift = 0.03f + frame.mid * 0.04f
        val x = (shapeBaseX[i] + sin(tSec * 0.35f * speed + phase) * drift) * w
        val y = (shapeBaseY[i] + cos(tSec * 0.27f * speed + phase * 1.7f) * drift) * h

        // ── 旋转：慢速自转 + 高频微抖 ──
        shapeRot[i] += (0.10f * speed + trebleSmooth * 0.5f * speed) * dtSec
        if (shapeRot[i] > TAU) shapeRot[i] -= TAU

        // ── 翻转（仅小色块）：treble 超阈值触发一次快速翻转动画 ──
        if (!isBig && frame.treble > FLIP_TRIGGER && shapeFlip[i] <= 0f) {
            shapeFlip[i] = 1f
        }
        if (shapeFlip[i] > 0f) {
            shapeFlip[i] -= dtSec * 2.5f   // 0.4s 翻转动画
            if (shapeFlip[i] < 0f) shapeFlip[i] = 0f
        }
        val flipPhase = shapeFlip[i]
        val scaleY = if (flipPhase > 0f) {
            // 翻转动画：cos 曲线 1→-1→1，过零时看起来像翻面
            cos(flipPhase * Math.PI.toFloat())
        } else 1f

        // ── 缩放：大色块由低音驱动放大 ──
        val bassBoost = if (isBig) 1f + bassSmooth * 0.35f else 1f + frame.pulse * 0.08f
        val s = shapeSize[i] * minOf(w, h) * bassBoost

        val color = Color(colors[shapeColorIdx[i]])
        val type = shapeType[i]

        withTransform({
            translate(x, y)
            rotate(shapeRot[i] * 57.2958f, pivot = Offset.Zero)
            scale(1f, scaleY, pivot = Offset.Zero)
        }) {
            path.reset()
            when (type) {
                SHAPE_SEMICIRCLE -> {
                    // 半圆：折线近似弧（零分配，避免 Compose Rect 不可变无法复用的问题）
                    path.moveTo(-s, 0f)
                    var a = 180
                    while (a >= 0) {
                        val rad = a * DEG2RAD
                        path.lineTo(-cos(rad) * s, -sin(rad) * s)
                        a -= 15
                    }
                    path.close()
                    drawPath(path, color)
                }
                SHAPE_TRIANGLE -> {
                    path.moveTo(0f, -s)
                    path.lineTo(s * 0.87f, s * 0.5f)
                    path.lineTo(-s * 0.87f, s * 0.5f)
                    path.close()
                    drawPath(path, color)
                }
                SHAPE_BAR -> {
                    // 长条矩形
                    path.moveTo(-s * 1.6f, -s * 0.28f)
                    path.lineTo(s * 1.6f, -s * 0.28f)
                    path.lineTo(s * 1.6f, s * 0.28f)
                    path.lineTo(-s * 1.6f, s * 0.28f)
                    path.close()
                    drawPath(path, color)
                }
                SHAPE_CIRCLE -> {
                    drawCircle(color, radius = s * 0.8f)
                }
                SHAPE_QUARTER -> {
                    // 四分之一圆（折线近似）
                    path.moveTo(0f, 0f)
                    path.lineTo(s, 0f)
                    var a = 90
                    while (a >= 0) {
                        val rad = a * DEG2RAD
                        path.lineTo(cos(rad) * s, -sin(rad) * s)
                        a -= 15
                    }
                    path.close()
                    drawPath(path, color)
                }
            }
            // 描边细节：大色块加一圈细描边增强构成感
            if (isBig && type != SHAPE_CIRCLE) {
                drawPath(path, Color(0x33000000), style = Stroke(1.5f))
            }
        }
    }
}

/**
 * E29 `ORBITAL_RINGS` — 轨道 · 环绕轨道
 *
 * 视觉：太阳系结构——几道倾斜椭圆轨道交织，轨道上有发光光球运行。
 * 低音让轨道轻微倾斜晃动（陀螺仪感）；高频让光球拖出流线尾巴；
 * 光球运行速度与节拍能量同步。
 *
 * 实现：
 *  - 椭圆轨道 = 参数方程 + rotate 变换，倾角由低通 bass 驱动（必须滤波，
 *    原始 bass 逐帧跳动会晃成筛子）；
 *  - 光球角速度 = 基础速度 + energy/beat 调制，按轨道独立积分；
 *  - 拖尾 = 每球环形历史缓冲（零分配，E24 同款惯例）存最近位置，逐点降透明度。
 *
 * 性能红线：draw 内零分配。缓冲与数组全部 onEnter/首帧分配。
 */
class OrbitalRingsRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.ORBITAL_RINGS

    private companion object {
        const val TAU = (2 * Math.PI).toFloat()
        const val MAX_ORBITS = 6
        const val MAX_BALLS = 8
        const val TRAIL_LEN = 40          // 拖尾点数
        const val TILT_FOLLOW = 0.06f     // 倾角低通系数（越大越跟随、越小越平滑）
        const val TILT_MAX_DEG = 9f       // 最大晃动角（保守，TV 上防不适）
    }

    /** 轨道参数（onEnter 预生成） */
    private var orbitRx = FloatArray(0)      // 长半轴（相对 minDim）
    private var orbitSquash = FloatArray(0)  // 短半轴/长半轴（压扁率）
    private var orbitTilt = FloatArray(0)    // 静态倾角（rad）
    private var orbitSpeed = FloatArray(0)   // 基础角速度（rad/s）
    private var orbitDir = IntArray(0)       // 方向 +1/-1
    private var orbitColorIdx = IntArray(0)
    private var orbitCount = 0

    /** 光球状态 */
    private var ballAngle = FloatArray(0)    // 当前轨道角
    private var ballOrbit = IntArray(0)      // 属于哪条轨道

    /** 拖尾历史（环形缓冲：[ball][point] → x,y 相对坐标 0..1） */
    private var trailX = FloatArray(0)
    private var trailY = FloatArray(0)
    private var trailHead = 0

    private var lastMs = 0L
    private var tiltSmooth = 0f
    private var wobblePhase = 0f

    override fun onEnter(ctx: RenderContext) {
        lastMs = 0L
        tiltSmooth = 0f
        wobblePhase = 0f

        // ── 画质分档：LOW 3 轨 3 球 / MEDIUM 4 轨 5 球 / HIGH 6 轨 7 球 ──
        val (orbits, balls) = when (ctx.quality) {
            com.nasmusic.tv.data.model.VisualQuality.LOW -> 3 to 3
            com.nasmusic.tv.data.model.VisualQuality.MEDIUM -> 4 to 5
            com.nasmusic.tv.data.model.VisualQuality.HIGH -> MAX_ORBITS to 7
        }
        orbitCount = orbits

        orbitRx = FloatArray(orbits)
        orbitSquash = FloatArray(orbits)
        orbitTilt = FloatArray(orbits)
        orbitSpeed = FloatArray(orbits)
        orbitDir = IntArray(orbits)
        orbitColorIdx = IntArray(orbits)

        var rng = 987654321u
        fun nextRand(): Float {
            rng = rng * 1664525u + 1013904223u
            return (rng shr 8).toFloat() / 16777216f
        }

        for (i in 0 until orbits) {
            val t = (i + 1f) / orbits
            orbitRx[i] = 0.14f + t * 0.30f                       // 0.14..0.44
            orbitSquash[i] = 0.30f + nextRand() * 0.30f           // 0.30..0.60
            orbitTilt[i] = nextRand() * TAU
            orbitSpeed[i] = 0.25f + (1f - t) * 0.45f              // 内轨快外轨慢（开普勒味）
            orbitDir[i] = if (nextRand() > 0.5f) 1 else -1
            orbitColorIdx[i] = i % 3
        }

        ballAngle = FloatArray(balls)
        ballOrbit = IntArray(balls)
        for (b in 0 until balls) {
            ballOrbit[b] = b % orbits
            ballAngle[b] = nextRand() * TAU
        }
        trailX = FloatArray(balls * TRAIL_LEN)
        trailY = FloatArray(balls * TRAIL_LEN)
        trailHead = 0
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now

        val cx = w * 0.5f
        val cy = h * 0.5f
        val unit = ctx.minDim

        // ── 倾角晃动：低通 bass 驱动（陀螺仪感，必须滤波）──
        tiltSmooth += (frame.bass - tiltSmooth) * TILT_FOLLOW
        wobblePhase += dtSec * 0.7f
        val wobble = tiltSmooth * TILT_MAX_DEG * 0.0174533f

        val accent = ctx.palette.accent
        val secondary = ctx.palette.secondary

        // ── 轨道（线框椭圆）──
        var i = 0
        while (i < orbitCount) {
            drawOrbit(i, cx, cy, unit, wobble, accent, alpha = 0.35f)
            i++
        }

        // ── 中心恒星：低音脉动 ──
        val sunR = unit * (0.045f + frame.pulse * 0.025f)
        drawCircle(accent, radius = sunR * 1.8f, center = Offset(cx, cy), alpha = 0.18f)
        drawCircle(accent, radius = sunR, center = Offset(cx, cy), alpha = 0.85f)

        // ── 光球角速度积分 + 拖尾写入 ──
        val balls = ballAngle.size
        var b = 0
        while (b < balls) {
            val orb = ballOrbit[b]
            // 角速度：基础 + energy 调制（节拍同步感）
            val omega = orbitSpeed[orb] * (0.6f + frame.energy * 1.5f) * orbitDir[orb]
            ballAngle[b] += omega * dtSec
            if (ballAngle[b] > TAU) ballAngle[b] -= TAU
            if (ballAngle[b] < 0f) ballAngle[b] += TAU
            b++
        }

        // ── 拖尾推进：每帧整体推进一格（与球同步采样）──
        trailHead = (trailHead + 1) % TRAIL_LEN

        // ── 画光球 + 拖尾 ──
        b = 0
        while (b < balls) {
            val orb = ballOrbit[b]
            val ang = ballAngle[b]

            // 当前位置（含 wobble 倾角）
            val pos = orbitPoint(orb, ang, cx, cy, unit, wobble)
            trailX[b * TRAIL_LEN + trailHead] = pos.x
            trailY[b * TRAIL_LEN + trailHead] = pos.y

            // 拖尾：从最老到最新，逐点降透明度（高频时更亮更长）
            val trailGain = 0.25f + frame.treble * 0.75f
            val color = if (orbitColorIdx[orb] == 0) accent else secondary
            var k = 1
            while (k <= TRAIL_LEN) {
                val idx = (trailHead + TRAIL_LEN - k) % TRAIL_LEN
                val tx = trailX[b * TRAIL_LEN + idx]
                val ty = trailY[b * TRAIL_LEN + idx]
                if (tx != 0f || ty != 0f) {
                    val fade = (1f - k.toFloat() / TRAIL_LEN) * trailGain
                    if (k == 1) {
                        // 球体
                        drawCircle(color, radius = unit * 0.012f, center = Offset(tx, ty), alpha = 0.95f)
                        drawCircle(color, radius = unit * 0.024f, center = Offset(tx, ty), alpha = 0.25f)
                    } else if (k % 2 == 0) {
                        drawCircle(color, radius = unit * 0.004f, center = Offset(tx, ty),
                            alpha = fade * 0.55f)
                    }
                }
                k++
            }
            b++
        }
    }

    /** 椭圆轨道上取点（含 wobble 倾角 + 静态倾角） */
    private fun orbitPoint(
        orb: Int, ang: Float, cx: Float, cy: Float, unit: Float, wobble: Float
    ): Offset {
        val rx = orbitRx[orb] * unit
        val ry = rx * orbitSquash[orb]
        // 椭圆参数方程（局部坐标）
        val lx = cos(ang) * rx
        val ly = sin(ang) * ry
        // 先静态倾角，再叠加动态 wobble（两轴不同相 → 陀螺仪感）
        val tilt = orbitTilt[orb]
        val cosT = cos(tilt + wobble * 0.7f)
        val sinT = sin(tilt + wobble * 0.7f)
        val x1 = lx * cosT - ly * sinT
        val y1 = lx * sinT + ly * cosT
        // 第二轴反向 wobble（不同相 → 立体进动感）
        val cosT2 = cos(tilt * 0.6f - wobble)
        val sinT2 = sin(tilt * 0.6f - wobble)
        val x2 = x1 * cosT2 - y1 * sinT2
        val y2 = x1 * sinT2 + y1 * cosT2
        return Offset(cx + x2, cy + y2)
    }

    /** 画一条椭圆轨道线框 */
    private fun DrawScope.drawOrbit(
        orb: Int, cx: Float, cy: Float, unit: Float, wobble: Float,
        color: Color, alpha: Float
    ) {
        val steps = 48
        pathBuf.reset()
        var s = 0
        while (s <= steps) {
            val ang = s.toFloat() / steps * TAU
            val p = orbitPoint(orb, ang, cx, cy, unit, wobble)
            if (s == 0) pathBuf.moveTo(p.x, p.y) else pathBuf.lineTo(p.x, p.y)
            s++
        }
        drawPath(pathBuf, color, style = Stroke(1.2f), alpha = alpha)
    }

    private val pathBuf = Path()
}

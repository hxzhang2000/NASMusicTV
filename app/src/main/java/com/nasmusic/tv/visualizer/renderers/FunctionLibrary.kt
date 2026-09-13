package com.nasmusic.tv.visualizer.renderers

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * E25「催眠」函数库 —— 53 条可动画化数学函数（定义 + 采样 + 加权洗牌）。
 *
 * 纯 JVM（不 import Android 类），可单测；渲染器只在采样期调用，draw 内零接触。
 *
 * 坐标系分三类：
 *   - [CurveSpace.CARTESIAN]  y = f(x)，u = x
 *   - [CurveSpace.PARAMETRIC] x = f(t), y = g(t)，u = t
 *   - [CurveSpace.POLAR]      r = f(θ)，u = θ，采样时转直角坐标
 *
 * [FunctionDef.uGap] 为可选的双段定义域（第二段起点）：A10 的 1/x 与
 * C7 的双纽线用两条独立区段表达，采样器跨 gap 直接切段、不连线。
 *
 * 采样输出**归一化坐标**（x∈[0,1]、y∈[-1,1]，各轴独立自动范围），
 * 屏幕映射由渲染器在采样期一次完成（§4.2）。
 */
class FunctionDef(
    val label: String,          // 公式源标记（FormulaLayout 语法，§2.5）
    val space: CurveSpace,
    val uMin: Float,
    val uMax: Float,
    val uGap: Float? = null,    // 第二段定义域起点（null = 单段）
    val soft: Float,            // 催眠适配度 0.3..1.0，加权洗牌用
    val tag: String             // 调试/日志用短标识
)

enum class CurveSpace { CARTESIAN, PARAMETRIC, POLAR }

object FunctionLibrary {

    // ── 函数清单（53 条，A 27 + B 8 + C 8 + D 10）────────────────

    val ALL: List<FunctionDef> = buildList {
        // ── A 类 · 笛卡尔单值函数 ──
        add(FunctionDef("y = sin(x)", CurveSpace.CARTESIAN, -2f * PI.toFloat(), 2f * PI.toFloat(), soft = 0.90f, tag = "A1"))
        add(FunctionDef("y = cos(x)", CurveSpace.CARTESIAN, -2f * PI.toFloat(), 2f * PI.toFloat(), soft = 0.90f, tag = "A2"))
        add(FunctionDef("y = frac{sin(x)}{x}", CurveSpace.CARTESIAN, -8f * PI.toFloat(), 8f * PI.toFloat(), soft = 0.95f, tag = "A3"))
        add(FunctionDef("y = |sin(x)|", CurveSpace.CARTESIAN, -3f * PI.toFloat(), 3f * PI.toFloat(), soft = 0.70f, tag = "A4"))
        add(FunctionDef("y = sin(x/2) + sin(x/3)", CurveSpace.CARTESIAN, -6f * PI.toFloat(), 6f * PI.toFloat(), soft = 0.98f, tag = "A5"))
        add(FunctionDef("y = tan(x)", CurveSpace.CARTESIAN, -1.5f * PI.toFloat(), 1.5f * PI.toFloat(), soft = 0.50f, tag = "A6"))
        add(FunctionDef("y = x^2", CurveSpace.CARTESIAN, -4f, 4f, soft = 0.85f, tag = "A7"))
        add(FunctionDef("y = x^3", CurveSpace.CARTESIAN, -3f, 3f, soft = 0.80f, tag = "A8"))
        add(FunctionDef("y = x^3 - x", CurveSpace.CARTESIAN, -3.5f, 3.5f, soft = 0.90f, tag = "A9"))
        add(FunctionDef("y = frac{1}{x}", CurveSpace.CARTESIAN, -4f, 4f, uGap = 0f, soft = 0.55f, tag = "A10"))
        add(FunctionDef("y = x + frac{1}{x}", CurveSpace.CARTESIAN, -4f, 4f, uGap = 0f, soft = 0.60f, tag = "A11"))
        add(FunctionDef("y = √{x}", CurveSpace.CARTESIAN, 0f, 16f, soft = 0.80f, tag = "A12"))
        add(FunctionDef("y = e^{-x^2}", CurveSpace.CARTESIAN, -5f, 5f, soft = 1.00f, tag = "A13"))
        add(FunctionDef("y = arctan(x)", CurveSpace.CARTESIAN, -10f, 10f, soft = 0.95f, tag = "A14"))
        add(FunctionDef("y = tanh(x)", CurveSpace.CARTESIAN, -5f, 5f, soft = 0.90f, tag = "A15"))
        add(FunctionDef("y = ln(x)", CurveSpace.CARTESIAN, 0.05f, 148.4f, soft = 0.60f, tag = "A16"))
        add(FunctionDef("y = x · e^{-x}", CurveSpace.CARTESIAN, -1.5f, 7f, soft = 0.85f, tag = "A17"))
        add(FunctionDef("y = e^{x}", CurveSpace.CARTESIAN, -4f, 3f, soft = 0.40f, tag = "A18"))
        add(FunctionDef("y = e^{-x^2} · cos(12x)", CurveSpace.CARTESIAN, -4.5f, 4.5f, soft = 1.00f, tag = "A19"))
        add(FunctionDef("y = e^{-x^2} · sin(8x)", CurveSpace.CARTESIAN, -4.5f, 4.5f, soft = 1.00f, tag = "A20"))
        add(FunctionDef("y = x · sin(x)", CurveSpace.CARTESIAN, -6f * PI.toFloat(), 6f * PI.toFloat(), soft = 0.85f, tag = "A21"))
        add(FunctionDef("y = cos(x^2)", CurveSpace.CARTESIAN, -4f, 4f, soft = 0.90f, tag = "A22"))
        add(FunctionDef("y = cos(x) · cos(2x)", CurveSpace.CARTESIAN, -2f * PI.toFloat(), 2f * PI.toFloat(), soft = 0.80f, tag = "A23"))
        add(FunctionDef("y = sin(x) + .5sin(3x) + .25sin(5x)", CurveSpace.CARTESIAN, -3f * PI.toFloat(), 3f * PI.toFloat(), soft = 0.70f, tag = "A24"))
        add(FunctionDef("y = sin(x) + .33sin(2x) + .2sin(3x)", CurveSpace.CARTESIAN, -3f * PI.toFloat(), 3f * PI.toFloat(), soft = 0.65f, tag = "A25"))
        add(FunctionDef("y = sinh(x)", CurveSpace.CARTESIAN, -3f, 3f, soft = 0.50f, tag = "A26"))
        add(FunctionDef("y = cosh(x)", CurveSpace.CARTESIAN, -4f, 4f, soft = 0.70f, tag = "A27"))
        // ── B 类 · 参数曲线 ──
        add(FunctionDef("x = cos^3t, y = sin^3t", CurveSpace.PARAMETRIC, 0f, 2f * PI.toFloat(), soft = 0.90f, tag = "B1"))
        add(FunctionDef("x = sin(3t), y = cos(2t)", CurveSpace.PARAMETRIC, 0f, 2f * PI.toFloat(), soft = 0.90f, tag = "B2"))
        add(FunctionDef("x = sin(5t), y = cos(4t)", CurveSpace.PARAMETRIC, 0f, 2f * PI.toFloat(), soft = 0.85f, tag = "B3"))
        add(FunctionDef("x = sin(7t), y = cos(6t)", CurveSpace.PARAMETRIC, 0f, 2f * PI.toFloat(), soft = 0.80f, tag = "B4"))
        add(FunctionDef("x = 16sin^3t, y = 13cost - 5cos2t - 2cos3t - cos4t", CurveSpace.PARAMETRIC, 0f, 2f * PI.toFloat(), soft = 0.75f, tag = "B5"))
        add(FunctionDef("x = t·sin t, y = t·cos t", CurveSpace.PARAMETRIC, 0f, 5f * PI.toFloat(), soft = 0.90f, tag = "B6"))
        add(FunctionDef("x = t, y = t·sin t·cos t", CurveSpace.PARAMETRIC, -4f * PI.toFloat(), 4f * PI.toFloat(), soft = 0.70f, tag = "B7"))
        add(FunctionDef("x = t - sin t, y = 1 - cos t", CurveSpace.PARAMETRIC, 0f, 4f * PI.toFloat(), soft = 0.70f, tag = "B8"))
        // ── C 类 · 极坐标 ──
        add(FunctionDef("r = 1 - sin θ", CurveSpace.POLAR, 0f, 2f * PI.toFloat(), soft = 0.85f, tag = "C1"))
        add(FunctionDef("r = cos 2θ", CurveSpace.POLAR, 0f, 2f * PI.toFloat(), soft = 0.90f, tag = "C2"))
        add(FunctionDef("r = cos 3θ", CurveSpace.POLAR, 0f, 2f * PI.toFloat(), soft = 0.85f, tag = "C3"))
        add(FunctionDef("r = cos 4θ", CurveSpace.POLAR, 0f, 2f * PI.toFloat(), soft = 0.85f, tag = "C4"))
        add(FunctionDef("r = 0.15·θ", CurveSpace.POLAR, 0f, 6f * PI.toFloat(), soft = 0.90f, tag = "C5"))
        add(FunctionDef("r = 0.06 · e^{0.25θ}", CurveSpace.POLAR, 0f, 8f * PI.toFloat(), soft = 0.90f, tag = "C6"))
        add(FunctionDef("r^2 = cos 2θ", CurveSpace.POLAR, -0.25f * PI.toFloat(), 1.25f * PI.toFloat(), soft = 0.80f, tag = "C7"))
        add(FunctionDef("r = cos θ - 2cos 2θ", CurveSpace.POLAR, 0f, 2f * PI.toFloat(), soft = 0.70f, tag = "C8"))
        // ── D 类 · 视觉彩蛋 ──
        add(FunctionDef("y = cos(φx), φ = 1.618…", CurveSpace.CARTESIAN, -3f * PI.toFloat(), 3f * PI.toFloat(), soft = 0.95f, tag = "D1"))
        add(FunctionDef("y = cos(πx)", CurveSpace.CARTESIAN, -4f, 4f, soft = 0.85f, tag = "D2"))
        add(FunctionDef("y = sin(√{x})", CurveSpace.CARTESIAN, 0f, 36f, soft = 0.90f, tag = "D3"))
        add(FunctionDef("y = sin(x)+sin(1.7x)+sin(2.3x)", CurveSpace.CARTESIAN, -2f * PI.toFloat(), 2f * PI.toFloat(), soft = 0.60f, tag = "D4"))
        add(FunctionDef("y = x · cos(x^2)", CurveSpace.CARTESIAN, -2.2f, 2.2f, soft = 0.85f, tag = "D5"))
        add(FunctionDef("y = arctan(x^2 - 2)", CurveSpace.CARTESIAN, -4f, 4f, soft = 0.90f, tag = "D6"))
        add(FunctionDef("y = e^{-x^2} · cos(20x)", CurveSpace.CARTESIAN, -3.5f, 3.5f, soft = 0.75f, tag = "D7"))
        add(FunctionDef("y = 2sin(x) + sin(5x)", CurveSpace.CARTESIAN, -2f * PI.toFloat(), 2f * PI.toFloat(), soft = 0.60f, tag = "D8"))
        add(FunctionDef("x = cos t·cos t^2, y = sin t·cos t^2", CurveSpace.PARAMETRIC, 0f, 6f * PI.toFloat(), soft = 0.85f, tag = "D9"))
        add(FunctionDef("r = sin(5θ)·cos(3θ)", CurveSpace.POLAR, 0f, 2f * PI.toFloat(), soft = 0.80f, tag = "D10"))
    }

    /** 按下标取 soft（洗牌用，避免渲染器反复遍历） */
    val softs: FloatArray = FloatArray(ALL.size) { ALL[it].soft }

    // ── 求值 ────────────────────────────────────────────────────

    private val PHI = 1.6180339f

    /** 笛卡尔求值：u=x → y。域外/未定义返回 NaN（采样器守卫切段） */
    fun evalCartesian(def: FunctionDef, x: Float): Float = when (def.tag) {
        "A1" -> sin(x)
        "A2" -> cos(x)
        "A3" -> if (abs(x) < 0.05f) 1f else sin(x) / x          // sinc 原点取极限，避免 NaN
        "A4" -> abs(sin(x))
        "A5" -> sin(x / 2f) + sin(x / 3f)
        "A6" -> tanSafe(x)
        "A7" -> x * x
        "A8" -> x * x * x
        "A9" -> x * x * x - x
        "A10" -> 1f / x
        "A11" -> x + 1f / x
        "A12" -> if (x < 0f) Float.NaN else sqrt(x)
        "A13" -> exp(-x * x)
        "A14" -> atan(x)
        "A15" -> tanhApprox(x)
        "A16" -> if (x <= 0f) Float.NaN else ln(x)
        "A17" -> x * exp(-x)
        "A18" -> exp(x)
        "A19" -> exp(-x * x) * cos(12f * x)
        "A20" -> exp(-x * x) * sin(8f * x)
        "A21" -> x * sin(x)
        "A22" -> cos(x * x)
        "A23" -> cos(x) * cos(2f * x)
        "A24" -> sin(x) + 0.5f * sin(3f * x) + 0.25f * sin(5f * x)
        "A25" -> sin(x) + 0.33f * sin(2f * x) + 0.2f * sin(3f * x)
        "A26" -> kotlin.math.sinh(x)
        "A27" -> kotlin.math.cosh(x)
        "D1" -> cos(PHI * x)
        "D2" -> cos(kotlin.math.PI.toFloat() * x)
        "D3" -> if (x < 0f) Float.NaN else sin(sqrt(x))
        "D4" -> sin(x) + sin(1.7f * x) + sin(2.3f * x)
        "D5" -> x * cos(x * x)
        "D6" -> atan(x * x - 2f)
        "D7" -> exp(-x * x) * cos(20f * x)
        "D8" -> 2f * sin(x) + sin(5f * x)
        else -> Float.NaN
    }

    /** 参数曲线求值：out[0]=x, out[1]=y */
    fun evalParametric(def: FunctionDef, t: Float, out: FloatArray) {
        when (def.tag) {
            "B1" -> { out[0] = cos(t) * cos(t) * cos(t); out[1] = sin(t) * sin(t) * sin(t) }
            "B2" -> { out[0] = sin(3f * t); out[1] = cos(2f * t) }
            "B3" -> { out[0] = sin(5f * t); out[1] = cos(4f * t) }
            "B4" -> { out[0] = sin(7f * t); out[1] = cos(6f * t) }
            "B5" -> {
                out[0] = 16f * sin(t) * sin(t) * sin(t)
                out[1] = 13f * cos(t) - 5f * cos(2f * t) - 2f * cos(3f * t) - cos(4f * t)
            }
            "B6" -> { out[0] = t * sin(t); out[1] = t * cos(t) }
            "B7" -> { out[0] = t; out[1] = t * sin(t) * cos(t) }
            "B8" -> { out[0] = t - sin(t); out[1] = 1f - cos(t) }
            "D9" -> {
                val c = cos(t * t)
                out[0] = cos(t) * c; out[1] = sin(t) * c
            }
            else -> { out[0] = Float.NaN; out[1] = Float.NaN }
        }
    }

    /** 极坐标求值：θ → r（r²=cos2θ 类取算术根，负值域由 NaN 守卫切段） */
    fun evalPolar(def: FunctionDef, theta: Float): Float = when (def.tag) {
        "C1" -> 1f - sin(theta)
        "C2" -> cos(2f * theta)
        "C3" -> cos(3f * theta)
        "C4" -> cos(4f * theta)
        "C5" -> 0.15f * theta
        "C6" -> 0.06f * exp(0.25f * theta)
        "C7" -> {
            val c = cos(2f * theta)
            if (c < 0f) Float.NaN else sqrt(c)
        }
        "C8" -> cos(theta) - 2f * cos(2f * theta)
        "D10" -> sin(5f * theta) * cos(3f * theta)
        else -> Float.NaN
    }

    /** tan 渐近线附近返回 NaN（|y| 截断交给采样器，此处防溢出） */
    private fun tanSafe(x: Float): Float {
        val c = cos(x)
        return if (abs(c) < 1e-4f) Float.NaN else sin(x) / c
    }

    /** tanh：kotlin.math 有 tanh，但为统一 Float 精度走近似（最大误差 <1e-6） */
    private fun tanhApprox(x: Float): Float {
        val e2 = exp(2f * x)
        return (e2 - 1f) / (e2 + 1f)
    }

    // ── 采样（§4.2 / §4.3）────────────────────────────────────

    /**
     * 采样 [def] 到归一化坐标 + 段表。
     *
     * @param n        采样点数
     * @param pts      n*2：x∈[0,1]（沿数学 x 线性）、y∈[-1,1]（自动范围，各轴独立归一）
     * @param segs     段表，打包 start * 65536 + len
     * @param axisOut  输出 2 项：[0] = 数学 x=0 的 tx，[1] = 数学 y=0 的 ty（画坐标轴用）
     * @param domainOut 输出 4 项：实际采用的数学范围 [xMin, xMax, yMin, yMax]（刻度生成用）
     * @return 段数
     *
     * 断笔规则（§4.3）：NaN/Inf 开新段；归一化后相邻跳变 > 1.5 视为跨渐近线开新段
     * （0.6 会误伤 ln 前段的真实陡峭；1.5 只拦真正的跨渐近线翻转）；
     * |y-yMid| > 1.5·range 的点剔除（tan 渐近线附近的大值尾巴）。
     *
     * uGap 语义为**断点**：第一段 [uMin, gap)，第二段 (gap, uMax]，点数按段长比例
     * 分配。A10 的 1/x 用 gap=0（跨 x=0 切段）；C7 的负值域由 NaN 守卫自然切段。
     */
    fun sample(def: FunctionDef, n: Int, pts: FloatArray, segs: IntArray, axisOut: FloatArray, domainOut: FloatArray): Int {
        // ── 构造采样段：uGap 切两段，点数按段长比例分配 ──
        val gap = def.uGap
        val ranges = if (gap != null && gap > def.uMin && gap < def.uMax) {
            val total = def.uMax - def.uMin
            val n1 = (n * ((gap - def.uMin) / total)).toInt().coerceIn(1, n - 1)
            listOf(floatArrayOf(def.uMin, gap, n1.toFloat()), floatArrayOf(gap, def.uMax, (n - n1).toFloat()))
        } else {
            listOf(floatArrayOf(def.uMin, def.uMax, n.toFloat()))
        }

        var xMin = Float.POSITIVE_INFINITY
        var xMax = Float.NEGATIVE_INFINITY
        var yMin = Float.POSITIVE_INFINITY
        var yMax = Float.NEGATIVE_INFINITY
        val valid = BooleanArray(n)
        val tmp = FloatArray(2)

        var i = 0
        for (r in ranges) {
            val uLo = r[0]; val uHi = r[1]; val cnt = r[2].toInt()
            for (k in 0 until cnt) {
                if (i >= n) break
                val t = if (cnt > 1) k.toFloat() / (cnt - 1) else 0f
                val u = uLo + (uHi - uLo) * t
                val vx: Float
                val vy: Float
                when (def.space) {
                    CurveSpace.CARTESIAN -> { vx = u; vy = evalCartesian(def, u) }
                    CurveSpace.PARAMETRIC -> {
                        evalParametric(def, u, tmp)
                        vx = tmp[0]; vy = tmp[1]
                    }
                    CurveSpace.POLAR -> {
                        val rad = evalPolar(def, u)
                        vx = rad * cos(u); vy = rad * sin(u)
                    }
                }
                val ok = vx.isFinite() && vy.isFinite()
                valid[i] = ok
                if (ok) {
                    if (vx < xMin) xMin = vx
                    if (vx > xMax) xMax = vx
                    if (vy < yMin) yMin = vy
                    if (vy > yMax) yMax = vy
                    pts[i * 2] = vx; pts[i * 2 + 1] = vy
                }
                i++
            }
        }

        if (!xMin.isFinite() || !yMin.isFinite() || xMax <= xMin || yMax <= yMin) {
            return 0   // 全部无效：渲染器保留上一张图或空场
        }

        val yMid = (yMin + yMax) / 2f
        val yRange = yMax - yMin

        // 坐标轴位置：数学 x=0 / y=0 在归一化空间的位置（渲染器判断是否可见）
        axisOut[0] = (0f - xMin) / (xMax - xMin)
        axisOut[1] = ((0f - yMin) / yRange) * 2f - 1f
        domainOut[0] = xMin; domainOut[1] = xMax
        domainOut[2] = yMin; domainOut[3] = yMax

        // ── 第二遍：归一化 + 截断 + 段表 ──
        var segCount = 0
        var segStart = -1
        var prevTy = Float.NaN
        for (k in 0 until n) {
            var inSeg = valid[k]
            if (inSeg) {
                val ty = ((pts[k * 2 + 1] - yMin) / yRange) * 2f - 1f
                // 渐近线大值尾巴剔除（|y-yMid| > 1.5·range）
                if (abs((pts[k * 2 + 1] - yMid) / yRange) > 1.5f) inSeg = false
                // 相邻跳变：跨渐近线（tan 类；1.2 阈值——实测 tan 渐近线 Δty≈1.50、
                // ln 前段真实陡峭 ≈0.65，0.6/1.5 都会出错）
                if (inSeg && segStart >= 0 && abs(ty - prevTy) > 1.2f) inSeg = false
                if (inSeg) {
                    if (segStart < 0) segStart = k
                    prevTy = ty
                    pts[k * 2] = (pts[k * 2] - xMin) / (xMax - xMin)
                    pts[k * 2 + 1] = ty
                }
            }
            if (!inSeg && segStart >= 0) {
                if (segCount < segs.size) segs[segCount] = segStart * 65536 + (k - segStart)
                segCount++
                segStart = -1
                prevTy = Float.NaN
            }
        }
        if (segStart >= 0 && segCount < segs.size) {
            segs[segCount] = segStart * 65536 + (n - segStart)
            segCount++
        }
        // 只返回已写入段表的段数（超出容量的段丢弃，防御调用方读越界）
        return minOf(segCount, segs.size)
    }

    // ── 加权洗牌（§7.2 三步）────────────────────────────────────

    /**
     * 三步加权洗牌：① Fisher-Yates 全洗；② **全局**按 `soft + rng*0.25` 降序重排
     * （柔和曲线偏向靠前，扰动保证不死板；后段同样是降序延续，保持完全由权重决定）；
     * ③ 跨周期防重（首条 != 上一周期末条）。
     *
     * ⚠️ 步骤 ② 必须是全局排序：只在"前 60%"内部排序无法把尾部的高 soft 换到前面，
     * 加权会完全失效（实测 head/tail 密度无差异）。
     * 每 13.9s 才调用一次，允许临时分配。
     *
     * @param order    输入须为 0..size-1 的恒等序列（原地重洗）
     * @param prevLast 上一周期最后一条的下标；首轮传 -1
     */
    fun weightedShuffle(rng: Random, order: IntArray, prevLast: Int) {
        val size = order.size
        // ① Fisher-Yates
        for (i in size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = order[i]; order[i] = order[j]; order[j] = tmp
        }
        // ② 全局按 score = soft + rng*0.25 降序（选择排序，零 Comparator 分配）
        val scores = FloatArray(size) { softs[order[it]] + rng.nextFloat() * 0.25f }
        for (k in 0 until size) {
            var best = k
            for (j in k + 1 until size) {
                if (scores[j] > scores[best]) best = j
            }
            if (best != k) {
                val t = order[k]; order[k] = order[best]; order[best] = t
                val s = scores[k]; scores[k] = scores[best]; scores[best] = s
            }
        }
        // ③ 跨周期防重
        if (prevLast >= 0 && size > 1 && order[0] == prevLast) {
            val tmp = order[0]; order[0] = order[1]; order[1] = tmp
        }
    }
}

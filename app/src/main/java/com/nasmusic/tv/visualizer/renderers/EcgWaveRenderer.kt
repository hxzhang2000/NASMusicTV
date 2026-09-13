package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.roundToInt

/**
 * E24 `ECG_WAVE` — 心跳 · 心电图式滚动频谱
 *
 * 视觉：经典 CRT 绿线心电图。一条连续折线从屏幕最右侧生成扫描点，
 * 已绘制的波形冻结并整体向左匀速平移，最左端超出屏幕被裁掉。
 *
 * **只取鼓点**：折线不由宽频时域波形 [AudioFrame.waveform] 驱动（那会把人声、
 * 背景乐器一并画进来）。仅在**鼓点命中**时注入一个完整的心搏复合波，
 * 相邻鼓点之间折线贴中心基线走平。
 *
 * **鼓点判定（双通道，抗漏帧）**：
 *   ① [AudioFrame.beat] —— 官方标志，但只在 1 帧内为 true；而节拍检测跑 50Hz、
 *      渲染数据只 30fps 更新（`EMIT_INTERVAL_MS`），在 30Hz 屏幕上会整拍漏掉；
 *   ② [AudioFrame.pulse] 上升沿 —— pulse 是「快起慢落」包络（约 250ms 回落），
 *      跨帧存活，因此漏掉的单帧标志也能被补回来。
 *   两者取或，再经最短间隔 [MIN_GAP_MS] 去密。
 *
 * **强弱差异**：幅度取 [AudioFrame.bassRaw]（**未**峰值归一化的低频原始能量）
 * 除以自身慢速均值，得到相对强度；绝不能用 [AudioFrame.bass]——它经 `boost()`
 * 峰值跟随归一化后，在鼓点瞬间**恒为 1.0**，会让每一次鼓点长得一模一样。
 *
 * **相对中心线上下跳动**：基线固定在屏幕垂直中线，心搏波相对基线上下都有振幅
 * （P 波 / R 波向上，Q 波 / S 波下探）。
 *
 * **丢拍（控密度）**：心搏复合波被拉长到约 0.50s，因此要求相邻两次渲染间隔
 * ≥ [MIN_GAP_MS]（800ms ≈ 75 BPM 上限）；在这个窗口内检出的鼓点直接丢弃，
 * 保证每个心搏完整、彼此不粘连（连续鼓点/鼓花不会堆成一团）。
 *
 * **峰顶无帽**：不绘制任何节拍点/扫描头圆点。
 *
 * 性能红线：draw 内零分配；history 为成员，仅在画布尺寸变化时重新分配；
 * 滚动与心搏展开按列虚拟时间基准匀速，不依赖帧率。
 */
class EcgWaveRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.ECG_WAVE

    // ── CRT 绿配色（固定绿色，不取封面色）──────────────────────
    private val lineColor = Color(0xFF33FF7A)   // 主绿线
    private val gridColor = Color(0xFF0E5A2E)   // 暗绿栅格

    // ── 历史缓冲（环形，零 arraycopy）──────────────────────────
    private var history: FloatArray? = null
    private var cols = 0
    private var rightPtr = -1

    /** 当前最右列的虚拟时间（ms）：每写一列前进 [colStepMs]；与真实帧率解耦 */
    private var colMs = 0f
    /** 累计未满一列的位移（避免 roundToInt 造成的系统性漂移） */
    private var colAccum = 0f
    /** 当前心搏的起点（虚拟时间） */
    private var beatAtMs = NONE
    /** 上一次真正渲染心搏的时刻（虚拟时间），用于丢拍 */
    private var lastFireMs = NONE
    /** 本次心搏幅度（0.45..1），命中瞬间快照，整段心搏共用 */
    private var spikeAmp = 1f
    /** 低频原始能量的慢速均值（强弱归一化基准） */
    private var bassAvg = 0f
    /** 上一帧 pulse，用于检测上升沿 */
    private var lastPulse = 0f

    private var lastMs = 0L

    /** 滚动速度（列/秒）。cols≈屏宽/2，90 列/秒 ≈ 全屏 10–17s 扫过 */
    private val speed = 90f
    private val colStepMs = 1000f / speed

    /** 复用 Path（零分配） */
    private val path = Path()

    override fun onEnter(ctx: RenderContext) {
        rightPtr = -1
        colMs = 0f
        colAccum = 0f
        beatAtMs = NONE
        lastFireMs = NONE
        spikeAmp = 1f
        bassAvg = 0f
        lastPulse = 0f
        lastMs = 0L
        // history 在首帧按 canvasSize 分配（onEnter 时 size 可能仍为 0）
    }

    private fun ensureCapacity(w: Float) {
        val need = maxOf(256, (w / 2f).roundToInt())
        if (history == null || history!!.size != need) {
            history = FloatArray(need)
            cols = need
            rightPtr = -1
        }
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return
        ensureCapacity(w)

        val hist = history!!

        // ── 时间步进（小数累积，长期不漂移）────────────────────
        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
        colAccum += speed * dtSec
        var n = colAccum.toInt()
        if (n < 0) n = 0
        colAccum -= n

        // ── 低频原始能量慢速均值（强弱归一化基准）──────────────
        val raw = frame.bassRaw
        if (bassAvg <= 0f) bassAvg = raw else bassAvg += (raw - bassAvg) * BASS_AVG_ALPHA

        // ── 鼓点判定：官方标志 + pulse 上升沿（补 30fps 漏掉的单帧标志）──
        val pulse = frame.pulse
        val pulseRising = pulse - lastPulse > PULSE_RISE_EPS
        lastPulse = pulse
        if ((frame.beat || pulseRising) && (colMs - lastFireMs) >= MIN_GAP_MS) {
            // 相对强度：bassRaw / 近端均值 → 典型 ≈1，鼓点越重越大
            val rel = if (bassAvg > 1e-5f) raw / bassAvg else 1f
            // 映射到 0.45..1，保留强弱差异（避免"每次鼓点一样高"）
            spikeAmp = (0.45f + (rel - 1f) * 0.35f).coerceIn(0.45f, 1f)
            beatAtMs = colMs
            lastFireMs = colMs
        }

        // ── 写入新列：心搏波随虚拟时间展开，无鼓点则走平基线 ──
        var k = 0
        while (k < n) {
            val ageSec = (colMs - beatAtMs) * 0.001f
            rightPtr = (rightPtr + 1) % cols
            hist[rightPtr] = heartbeatAt(ageSec) * spikeAmp
            colMs += colStepMs
            k++
        }

        // Float 精度保护：长时间播放后 colMs 过大 → 心搏计时失真，整体回绕
        if (colMs > REBASE_AT_MS) {
            colMs -= REBASE_AT_MS
            beatAtMs -= REBASE_AT_MS
            lastFireMs -= REBASE_AT_MS
        }

        val midY = h * 0.5f     // 基线：屏幕垂直中线
        val amp = h * AMP_FRACTION

        // ── CRT 栅格（暗绿）────────────────────────────────────
        drawGrid(w, h, midY)

        // ── 主折线（最旧 → 最新，左 → 右）────────────────────
        path.reset()
        var i = 0
        while (i < cols) {
            val idx = (rightPtr + 1 + i) % cols
            val x = (i.toFloat() / (cols - 1).coerceAtLeast(1)) * w
            val y = midY - hist[idx] * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            i++
        }
        // 辉光层（宽、淡、Plus）
        drawPath(
            path, lineColor,
            style = Stroke(width = w * 0.006f + 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Plus, alpha = 0.18f
        )
        // 主绿线
        drawPath(
            path, lineColor,
            style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Plus, alpha = 0.95f
        )
    }

    private fun DrawScope.drawGrid(w: Float, h: Float, midY: Float) {
        val a = 0.18f
        // 中线
        drawLine(gridColor, Offset(0f, midY), Offset(w, midY),
            strokeWidth = 1.5f, alpha = a, blendMode = BlendMode.Plus)
        val rows = 4
        for (r in 1..rows) {
            val off = midY * r / (rows + 1)
            drawLine(gridColor, Offset(0f, midY - off), Offset(w, midY - off),
                strokeWidth = 0.8f, alpha = a * 0.5f, blendMode = BlendMode.Plus)
            drawLine(gridColor, Offset(0f, midY + off), Offset(w, midY + off),
                strokeWidth = 0.8f, alpha = a * 0.5f, blendMode = BlendMode.Plus)
        }
        val seg = 8
        for (c in 1 until seg) {
            val xx = w * c / seg
            drawLine(gridColor, Offset(xx, 0f), Offset(xx, h),
                strokeWidth = 0.8f, alpha = a * 0.35f, blendMode = BlendMode.Plus)
        }
    }

    /**
     * 心搏复合波（P-QRS-T，分段线性插值）：输入距鼓点的时间（秒），输出 -1..1。
     * 形状：P 小峰向上 → PR 段回平 → Q 下探 → **R 主峰向上** → S 下探 → T 圆峰 → 回基线。
     * 总跨度约 0.50s，比单根尖刺更像真实心电描记；无分配（查表 + 线性插值）。
     */
    private fun heartbeatAt(ageSec: Float): Float {
        if (ageSec <= 0f) return 0f
        val t = HB_T
        val last = t.size - 1
        if (ageSec >= t[last]) return 0f
        var i = 1
        while (i < last && t[i] < ageSec) i++
        val t0 = t[i - 1]
        val t1 = t[i]
        val v0 = HB_V[i - 1]
        val v1 = HB_V[i]
        return v0 + (v1 - v0) * ((ageSec - t0) / (t1 - t0))
    }

    private companion object {
        /** 哨兵：远早于任何虚拟时间 → 初始无心搏 */
        const val NONE = -1.0e9f

        /** 主峰高度占半屏高的比例（原 0.40 → 0.26，压制整体高度） */
        const val AMP_FRACTION = 0.26f

        /** 相邻两次心搏最短间隔（虚拟 ms）≈ 75 BPM 上限，连续鼓点在此窗口内丢弃 */
        const val MIN_GAP_MS = 800f

        /** pulse 上升沿阈值：> 该值视为一次鼓点（补漏帧） */
        const val PULSE_RISE_EPS = 0.18f

        /** 低频均值 EMA 系数（每帧），≈0.8s 时间常数 */
        const val BASS_AVG_ALPHA = 0.025f

        /** colMs 回绕阈值（Float 精度保护，约 16 分钟） */
        const val REBASE_AT_MS = 1.0e6f

        /** 心搏波归一化时间点（秒），总跨度 0.50s */
        val HB_T = floatArrayOf(
            0.000f, 0.048f, 0.090f, 0.132f, 0.164f, 0.186f,
            0.214f, 0.238f, 0.268f, 0.298f, 0.330f, 0.392f, 0.452f, 0.500f
        )
        /** 对应幅值（-1..1）：R 主峰 +1.0，S 下探 -0.30，T 圆峰 +0.24 */
        val HB_V = floatArrayOf(
            0.000f, 0.000f, 0.130f, 0.000f, -0.120f, 0.000f,
            1.000f, -0.060f, -0.300f, -0.040f, 0.080f, 0.240f, 0.060f, 0.000f
        )
    }
}

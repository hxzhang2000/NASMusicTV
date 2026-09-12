package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.sin
import kotlin.math.cos

// ═══════════════════════════════════════════════════════════════════
// E22 极光（写实星空版）
// ═══════════════════════════════════════════════════════════════════

/**
 * E22 `AURORA` — 极光 · 写实自然星空
 *
 * 参照"写实自然星空下的极光"画面重构：
 *
 * 空间布局：
 * - 背景层：底部深海暗青（墨绿）→ 中部午夜蓝 → 顶部近黑，过渡自然无边界
 * - 漫天星尘：明暗交错、微微闪烁；少量高亮星带十字星芒（低频呼吸旋转）
 * - 极光主体：集中在画面中右侧，从右下向中央蜿蜒、向上向右卷曲的"丝带/轻纱"
 * - 底部地平线：柔和的绿色光晕，与极光根部交融，仿佛光从地平线升起

 * 动态联动：
 * - 低频（Bass）：光带变浓、变亮变厚、整体缓慢呼吸（放大/缩小）
 * - 高频（Treble）：极光边缘轻纱产生细微流动波纹 + 星尘高频闪烁
 * - 情感爆发（high energy）：极光短暂从青绿幻化出紫红 / 冰蓝，增强冲击
 */
class AuroraRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.AURORA

    // 极光光丝带数量（同一条带又分多段描边，模拟轻纱层次）
    private val bandCount = 5

    // 光带底部基准 X（小数比例，集中在右侧，向右延伸再向中央蜿蜒）
    private val baseXs = floatArrayOf(0.55f, 0.66f, 0.78f, 0.88f, 0.95f)

    // 每条丝带的波动相位
    private val phases = FloatArray(5)

    override fun onEnter(ctx: RenderContext) {
        for (i in phases.indices) phases[i] = i * 2.1f
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        val t = frame.timeMs * 0.001f  // 秒

        // ── 情绪 / 频谱驱动量 ────────────────────────────────
        val bass = frame.bass.coerceIn(0f, 1f)
        val treble = frame.treble.coerceIn(0f, 1f)
        val energy = frame.energy.coerceIn(0f, 1f)
        val pulse = frame.pulse.coerceIn(0f, 1f)

        // 音乐触发的"呼吸"：低频驱动光带整体缓慢胀缩
        val breath = 1f + bass * 0.18f + sin(t * 2.0f + sin(t * 1.3f)) * 0.03f
        // 情感爆发: 高频+能量高时，极光短暂幻化紫红/冰蓝
        val burst = (treble * 0.6f + energy * 0.4f)
        val erupt = (burst * burst).coerceIn(0f, 1f)   // 0..1，爆发强度

        // ── ① 背景层：深海暗青 → 午夜蓝 → 近黑 ──────────
        drawBackground(w, h, bass)

        // ── ② 极光丝带（主体层）─────────────────────────
        drawAuroraRibbons(w, h, t, bass, treble, energy, burst, erupt, breath)

        // ── ③ 底部地平线柔光晕（右侧更浓，与极光根部交融）─
        drawHorizonGlow(w, h, bass, hueOf(bass, erupt))

        // ── ④ 漫天星尘 + 十字星芒 ────────────────────────
        drawStars(w, h, t, treble, bass, energy, ctx.quality)
    }

    // ── 背景渐变 ────────────────────────────────────────────

    private fun DrawScope.drawBackground(w: Float, h: Float, bass: Float) {
        // 分带模拟平滑渐变：底 墨绿暗青 → 中 午夜蓝 → 顶 近黑
        // 三档色：底(170°, 墨绿) / 中(218°, 午夜蓝) / 顶(238°, 近黑)
        val strips = 26
        val stripH = h / strips
        val hue0 = 170f
        val hue1 = 218f
        val hue2 = 238f
        val midT = 0.42f          // 午夜蓝起点（占底部到这里的比例）
        val l0 = 0.05f + bass * 0.02f  // 底部亮度（随低音微增）
        val l1 = 0.028f
        val l2 = 0.012f

        for (i in 0 until strips) {
            val tt = i.toFloat() / strips     // 0=顶, 1=底
            val fromBottom = 1f - tt          // 0=顶, 1=底
            val hh: Float
            val ll: Float
            when {
                fromBottom >= midT -> {       // 下段：底墨绿 → 中午夜蓝
                    val u = (fromBottom - midT) / (1f - midT)   // 0=中, 1=底
                    hh = lerp(hue1, hue0, u)
                    ll = lerp(l1, l0, u)
                }
                else -> {                     // 上段：中午夜蓝 → 顶近黑
                    val u = fromBottom / midT                  // 0=顶, 1=中
                    hh = lerp(hue2, hue1, u)
                    ll = lerp(l2, l1, u)
                }
            }
            drawRect(
                color = VisualizerMath.hsl(hh, 0.55f, ll),
                topLeft = Offset(0f, (strips - i) * stripH),
                size = androidx.compose.ui.geometry.Size(w, stripH),
                blendMode = BlendMode.SrcOver
            )
        }
    }

    // ── 极光丝带 ────────────────────────────────────────────

    private fun DrawScope.drawAuroraRibbons(
        w: Float, h: Float, t: Float,
        bass: Float, treble: Float, energy: Float,
        burst: Float, erupt: Float, breath: Float
    ) {
        for (b in 0 until bandCount) {
            val phase = phases[b] + t * 0.22f * (1f + treble * 0.8f)

            // 底部基准位置（右侧密集），随音乐向中央蜿蜒
            val baseX = w * baseXs[b]

            // 光带主色：以荧光绿为基调，爆发时幻化（紫红 / 冰蓝）
            val colorHue = hueOf(bass, erupt)
            val saturation = 0.95f
            // 体积感: 亮度带上下层次
            val coreLight = 0.55f + bass * 0.12f

            // 光带的垂直主体：底部贴近地平线，向上扭曲、收窄、透明
            val topY = h * (0.30f - erupt * 0.06f - bass * 0.05f)   // 高度由音乐决定
            val segments = 30
            for (s in 0 until segments) {
                val segT = s.toFloat() / segments  // 0=底, 1=顶
                val u = 1f - segT                  // 底部=1

                // 侧向蜿蜒：正弦叠加 → 轻纱随风吹动
                val sway = sin(phase * 1.0f + segT * 3.2f) * w * 0.035f +
                        sin(segT * 7.0f + phase * 1.5f) * w * 0.018f
                // 向右上卷曲趋势（顶部往右回卷）
                val curl = segT * segT * w * 0.06f
                val x = baseX + sway + curl

                // 透明度: 底部浓 → 向上消隐（轻纱质感）
                val alpha = fpow16(u) * (0.30f + bass * 0.25f + treble * 0.06f)

                // 宽度: 底部宽（光幕）→ 顶部细（飘散）
                val segWidth = w * (0.055f + 0.03f * u) * breath * (1f + treble * 0.08f)

                // 每段高度
                val yBottom = h - segT * (h - topY) + 2f
                val segHeight = (h - topY) / segments

                // 分段色相：底部纯绿，向上偏蓝青（模拟光带内部层次）
                val segHue = colorHue + segT * 13f
                val segSat = saturation * (0.7f + 0.3f * u)
                val segLight = coreLight * (0.5f + 0.5f * u)

                drawOval(
                    color = VisualizerMath.hsl(segHue, segSat, segLight),
                    topLeft = Offset(x - segWidth / 2, yBottom - segHeight),
                    size = androidx.compose.ui.geometry.Size(segWidth, segHeight * 1.9f),
                    alpha = alpha,
                    blendMode = BlendMode.Plus
                )

                // 边缘高亮细线：模拟极光边缘发光 / 轻纱描边
                if (s % 2 == 0) {
                    val edgeAlpha = fpow16(u) * (0.12f + treble * 0.1f)
                    drawLine(
                        color = VisualizerMath.hsl(segHue + 8f, 1.0f, 0.8f),
                        start = Offset(x - segWidth / 2, yBottom - segHeight * 0.6f),
                        end = Offset(x + segWidth / 2, yBottom - segHeight * 0.6f),
                        strokeWidth = (segHeight * 0.25f).coerceAtLeast(0.5f),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        alpha = edgeAlpha * (0.4f + treble * 0.3f),
                        blendMode = BlendMode.Plus
                    )
                }
            }

            // 光带核心亮线（比段更亮的中轴）
            val coreHue = colorHue + 6f
            val coreBottom = h - 4f
            val coreTop = h - (h - topY) * (0.55f + bass * 0.25f)
            val coreLineWidth = w * (0.012f + bass * 0.012f) * breath
            drawLine(
                color = VisualizerMath.hsl(coreHue, 1.0f, 0.85f),
                start = Offset(baseX, coreBottom),
                end = Offset(baseX + w * 0.02f, coreTop),
                strokeWidth = coreLineWidth,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                alpha = 0.30f + bass * 0.35f + burst * 0.25f,
                blendMode = BlendMode.Plus
            )

            // 根部亮点（发源处）
            drawCircle(
                color = VisualizerMath.hsl(colorHue, 1.0f, 0.85f),
                radius = w * (0.035f + bass * 0.03f) * breath,
                center = Offset(baseX, h - 2f),
                alpha = 0.4f + bass * 0.35f + burst * 0.2f,
                blendMode = BlendMode.Plus
            )
        }
    }

    private fun DrawScope.drawHorizonGlow(w: Float, h: Float, bass: Float, hue: Float) {
        // 一条横跨底部、中部偏右的柔和光晕，模拟地平线光
        for (i in 0 until 10) {
            val ttl = i.toFloat() / 10f
            val alpha = (0.02f + (1f - ttl) * 0.05f + bass * 0.02f)
            drawOval(
                color = VisualizerMath.hsl(hue, 0.85f, 0.5f),
                topLeft = Offset(w * 0.25f, h - (1f - ttl) * (h * 0.30f)),
                size = androidx.compose.ui.geometry.Size(w * (0.5f + ttl * 0.3f), h * 0.06f * (1f - ttl * 0.4f)),
                alpha = alpha,
                blendMode = BlendMode.Plus
            )
        }
    }

    // ── 星空 ────────────────────────────────────────────────

    private fun DrawScope.drawStars(
        w: Float, h: Float, t: Float,
        treble: Float, bass: Float, energy: Float,
        quality: VisualQuality
    ) {
        // 星尘（细密，明暗交错，微微闪烁）
        val dustCount = when (quality) {
            VisualQuality.HIGH -> 150
            VisualQuality.MEDIUM -> 100
            else -> 62
        }
        for (i in 0 until dustCount) {
            // 用确定性 hash 生成每颗星，避免帧间跳变
            val seed = i.toFloat() * 7.731f
            val sx = frac(seed * 0.618f) * w
            val sy = frac(seed * 1.317f) * h * 0.92f
            // 明暗交错：一部分亮一部分暗
            val base = frac(seed * 2.131f)
            val bright = if (base > 0.72f) 1f else if (base > 0.35f) 0.45f else 0.16f
            // 微微闪烁（叠加高频 → 高频时闪得更快更亮）
            val tw = sin(t * (0.8f + treble * 3.0f) + seed * 12.0f) * 0.5f + 0.5f
            drawCircle(
                color = VisualizerMath.hsl(200f, 0.5f, 0.8f),
                radius = (0.5f + bright * 0.9f + tw * 0.3f + treble * 1.2f),
                center = Offset(sx, sy),
                alpha = (0.15f + bright * 0.4f + tw * 0.25f) * (1f - sy / h * 0.5f),
                blendMode = BlendMode.Plus
            )
        }

        // 少数高亮星 + 十字星芒（低频时旋转、呼吸）
        val spikeCount = when (quality) {
            VisualQuality.HIGH -> 10
            VisualQuality.MEDIUM -> 7
            else -> 5
        }
        for (i in 0 until spikeCount) {
            val seed = i * 11.37f
            val sx = frac(seed * 0.517f) * w
            val sy = frac(seed * 0.943f) * h * 0.5f
            val rot = sin(t * 0.3f + i) * 0.6f          // 缓慢旋转
            val mag = 3f + sin(t * 0.7f + i) * 1.2f + bass * 2f   // 十字芒长度
            val spread = mag * (0.6f + treble)

            // 十字四臂
            val c = VisualizerMath.hsl(190f, 0.5f, 0.9f)
            drawLine(c, Offset(sx - mag, sy), Offset(sx + mag, sy), strokeWidth = 0.8f,
                alpha = 0.35f, blendMode = BlendMode.Plus)
            drawLine(c, Offset(sx, sy - mag), Offset(sx, sy + mag), strokeWidth = 0.8f,
                alpha = 0.35f, blendMode = BlendMode.Plus)
            // 斜向短芒
            drawLine(c, Offset(sx - spread, sy - spread), Offset(sx + spread, sy + spread), strokeWidth = 0.5f,
                alpha = 0.15f, blendMode = BlendMode.Plus)
            drawLine(c, Offset(sx - spread, sy + spread), Offset(sx + spread, sy - spread), strokeWidth = 0.5f,
                alpha = 0.15f, blendMode = BlendMode.Plus)
            // 中心亮点
            drawCircle(
                color = c,
                radius = 1f + sin(t * 0.9f + i) * 0.4f + bass * 0.6f,
                center = Offset(sx, sy),
                alpha = 0.5f + energy * 0.3f,
                blendMode = BlendMode.Plus
            )
        }
    }

    // ── 工具 ────────────────────────────────────────────────

    /** 极光主色调：常态荧光绿，爆发时短暂幻化紫红/冰蓝 */
    private fun hueOf(bass: Float, erupt: Float): Float {
        // 常态: 荧光绿 (~135)
        val g = 132f
        // 爆发: 向紫红(~300) 或冰蓝(~200) 偏移，用 erupt 控制
        // 用 sin 让爆发左右摇摆于紫红/冰蓝，增强变化
        val burstHue = if (sin(erupt * 6.2f) > 0f) 300f else 205f
        return lerp(g, burstHue, erupt)
    }

    private inline fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private inline fun frac(x: Float): Float = x - kotlin.math.floor(x)

    private fun fpow16(x: Float): Float {
        val u = x.coerceIn(0f, 1f)
        return u * u * (0.6f + 0.4f * u)
    }
}
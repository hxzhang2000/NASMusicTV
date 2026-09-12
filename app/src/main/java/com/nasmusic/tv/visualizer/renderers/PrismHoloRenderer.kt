package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.abs
import kotlin.math.sin

// ═══════════════════════════════════════════════════════════════════
// E21 棱镜 · 全息虹彩
// ═══════════════════════════════════════════════════════════════════

/**
 * E21 `PRISM_HOLO` — 棱镜 · 全息虹彩流体
 *
 * 视觉风格：全息虹彩 / 光线穿透毛玻璃折射 / 棱镜色散。
 *
 * - 底层：低饱和莫兰迪打底（灰褐 → 米色 → 浅灰），梦幻、治愈、氛围
 * - 主体：多条流体色带像丝绸/水波缓慢流动，全息霓虹色（粉紫、淡蓝、鹅黄）
 *   交织交缠，用正弦叠加制造三维隧道式的纵深卷曲
 * - 折射光晕：底部一抹彩虹色光晕（棱镜色散），像毛玻璃边缘折射
 * - 动态：
 *   · 低频（Bass）：整条光带缓慢呼吸放大/收缩，光影明暗起伏
 *   · 中频（Mid）：驱动渐变深浅混合，色带扭动幅度
 *   · 高频（Treble）：色带边缘泛起细碎波纹、闪烁流光
 *   · 频谱融入：中间主光带随频率拉伸、扭曲，能量感"隐形"
 *   · 一切平滑缓慢，无锐利跳动（正弦缓动）
 */
class PrismHoloRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.PRISM_HOLO

    private val phases = FloatArray(6) { i -> i * 2.4f }

    override fun onEnter(ctx: RenderContext) {
        for (i in phases.indices) phases[i] = i * 2.4f
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        val t = frame.timeMs * 0.001f

        val bass = frame.bass.coerceIn(0f, 1f)
        val mid = frame.mid.coerceIn(0f, 1f)
        val treble = frame.treble.coerceIn(0f, 1f)
        val energy = frame.energy.coerceIn(0f, 1f)

        // 呼吸：低频驱动整体慢慢放大/收缩 + 一个缓慢正弦叠加
        val breath = 1f + bass * 0.16f + sin(t * 1.2f) * 0.03f

        // ① 莫兰迪底层渐变
        drawMorandiBase(w, h, bass)

        // ② 主体：全息虹彩流体色带（三组交织，模拟隧道纵深）
        drawIridescentRibbons(w, h, t, bass, mid, treble, energy, breath, ctx.quality, frame)

        // ③ 棱镜色散折射光晕（底部彩虹 + 边缘流光）
        drawPrismGlow(w, h, t, bass, treble, energy)

        // ④ 莫兰迪暗角（让边缘流入暗色，聚焦中央）
        drawVignette(w, h, bass)
    }

    // ── ① 莫兰迪底层 ──────────────────────────────────────

    private fun DrawScope.drawMorandiBase(w: Float, h: Float, bass: Float) {
        // 低饱和莫兰迪色阶：顶 灰褐 → 中 米色 → 底 浅棕灰，柔和暗调
        val strips = 24
        val stripH = h / strips
        val hue0 = 32f   // 灰褐
        val hue1 = 40f   // 米
        val hue2 = 22f   // 浅棕
        val s0 = 0.16f
        val s1 = 0.14f
        val l0 = 0.10f + bass * 0.02f
        val l1 = 0.07f
        val l2 = 0.05f
        val midT = 0.45f

        for (i in 0 until strips) {
            val tt = i.toFloat() / strips      // 0=顶, 1=底
            val fromBottom = 1f - tt
            val hh: Float; val ss: Float; val ll: Float
            if (fromBottom >= midT) {
                val u = (fromBottom - midT) / (1f - midT)
                hh = lerp(hue1, hue0, u)
                ss = lerp(s1, s0, u)
                ll = lerp(l1, l0, u)
            } else {
                val u = fromBottom / midT
                hh = lerp(hue2, hue1, u)
                ss = s1
                ll = lerp(l2, l1, u)
            }
            drawRect(
                color = VisualizerMath.hsl(hh, ss, ll),
                topLeft = Offset(0f, (strips - i) * stripH),
                size = Size(w, stripH),
                blendMode = BlendMode.SrcOver
            )
        }
    }

    // ── ② 全息虹彩流体色带 ────────────────────────────────

    private fun DrawScope.drawIridescentRibbons(
        w: Float, h: Float, t: Float,
        bass: Float, mid: Float, treble: Float, energy: Float,
        breath: Float, quality: VisualQuality, frame: AudioFrame
    ) {
        // 三条主色带 + 两条次带，全息霓虹色（粉紫 / 淡蓝 / 鹅黄）
        val mainColors = intArrayOf(305, 215, 48)      // 粉紫 / 淡蓝 / 鹅黄
        val subColors = intArrayOf(270, 190)           // 紫 / 青

        // 主带：宽、柔和、靠中，随音乐拉伸扭结
        val seqs = frame.spectrum
        val seqLen = frame.spectrum.size.coerceAtLeast(1)

        for (i in mainColors.indices) {
            val hue = mainColors[i].toFloat()
            val phase = phases[i] + t * 0.20f * (1f + mid * 0.7f)
            // 横向基准：均匀分布，略靠中偏下（隧道内聚焦）
            val baseCx = w * (0.22f + i * 0.28f)
            // 谱段能量 → 主带高度/拉伸（"隐形能量"：映射到中间光带）
            val spec = seqs.getOrElse((seqLen * (0.35f + i * 0.2f)).toInt().coerceAtMost(seqLen - 1)) { 0f }
            val bandEnergy = (0.5f + spec * 0.7f + energy * 0.3f).coerceIn(0.35f, 1.2f)

            // 顶端高度：低频拉高主带
            val topY = h * (0.22f - bass * 0.06f - bandEnergy * 0.06f) / (breath)
            val segments = 26

            for (s in 0 until segments) {
                val segT = s.toFloat() / segments    // 0=底, 1=顶
                val u = 1f - segT

                // 三维隧道式卷曲：横向波浪 + 纵向螺旋扭曲
                val tunnelX = sin(phase + segT * 2.4f) * w * 0.05f +
                        sin(segT * 5.0f - phase * 0.6f) * w * 0.02f
                val swirl = segT * segT * w * 0.045f * (0.6f + mid)   // 底部宽顶部收，隧道感
                val cx = baseCx + tunnelX + swirl

                // 色带宽度：底部宽、顶部细，随呼吸与频段伸缩
                val width = w * (0.10f + 0.05f * u) * breath * (0.7f + bandEnergy * 0.35f) * (1f + treble * 0.05f)

                val yBottom = h - segT * (h - topY) + 2f
                val segHeight = (h - topY) / segments

                // 全息渐变：同一条带内色相流动 → 霓虹虹彩
                val flowHue = hue + sin(segT * 6.0f + phase) * 20f
                val sat = 0.7f * (0.75f + 0.25f * u)
                val light = (0.42f + 0.28f * u + bass * 0.10f + bandEnergy * 0.06f).coerceIn(0.2f, 0.95f)
                // 透明度：中段浓，上下淡
                val alpha = (0.14f + fpow16(u) * 0.22f + treble * 0.05f).coerceIn(0f, 0.9f)

                drawOval(
                    color = VisualizerMath.hsl(flowHue, sat, light),
                    topLeft = Offset(cx - width / 2, yBottom - segHeight),
                    size = Size(width, segHeight * 1.8f),
                    alpha = alpha,
                    blendMode = BlendMode.Plus
                )

                // 边缘流光：高频触发色带边缘泛起细碎波纹
                val ripple = sin(segT * 8.0f * (1f + treble * 2f) + phase * 1.7f)
                if (ripple > 0.55f) {
                    val edgeLight = (0.10f + treble * 0.14f) * u
                    drawLine(
                        color = VisualizerMath.hsl((flowHue + 30f) % 360f, 0.9f, 0.85f),
                        start = Offset(cx - width / 2, yBottom - segHeight * 0.5f + ripple * 2f),
                        end = Offset(cx - width / 2 + width * (0.2f + ripple * 0.3f), yBottom - segHeight * 0.5f),
                        strokeWidth = (segHeight * 0.18f).coerceAtLeast(0.5f),
                        cap = StrokeCap.Round,
                        alpha = edgeLight,
                        blendMode = BlendMode.Plus
                    )
                }
            }

            // 主带中心高亮（隐形能量核心）
            val coreBottom = h - 4f
            val coreTop = h - (h - topY) * (0.6f + bass * 0.2f)
            drawLine(
                color = VisualizerMath.hsl(hue, 0.95f, 0.8f),
                start = Offset(baseCx, coreBottom),
                end = Offset(baseCx + w * 0.02f, coreTop),
                strokeWidth = w * (0.012f + bass * 0.012f) * breath,
                cap = StrokeCap.Round,
                alpha = 0.15f + bass * 0.2f + bandEnergy * 0.12f,
                blendMode = BlendMode.Plus
            )
        }

        // 次带：淡、细，像毛玻璃折射的残影（更好地融合在底里）
        val subCount = if (quality == VisualQuality.LOW) 1 else subColors.size
        for (i in 0 until subCount) {
            val hue = subColors[i].toFloat()
            val phase = phases[i + 3] + t * 0.22f
            val baseCx = w * (0.16f + i * 0.34f)
            val topY = h * (0.26f + sin(phase * 0.6f) * 0.05f)
            val segments = 20
            // 次带极弱、窄
            for (s in 0 until segments) {
                val segT = s.toFloat() / segments
                val u = 1f - segT
                val cx = baseCx + sin(phase + segT * 2.6f) * w * 0.05f + segT * segT * w * 0.03f
                val width = w * 0.035f
                val yBottom = h - segT * (h - topY) + 2f
                val segHeight = (h - topY) / segments
                drawOval(
                    color = VisualizerMath.hsl(hue + sin(segT * 4f + phase) * 14f, 0.6f, 0.5f),
                    topLeft = Offset(cx - width / 2, yBottom - segHeight),
                    size = Size(width, segHeight * 1.5f),
                    alpha = 0.06f + fpow16(u) * 0.08f,
                    blendMode = BlendMode.Plus
                )
            }
        }
    }

    // ── ③ 棱镜色散折射光晕 ────────────────────────────────

    private fun DrawScope.drawPrismGlow(w: Float, h: Float, t: Float, bass: Float, treble: Float, energy: Float) {
        // 底部一抹彩虹色光晕（棱镜色散），像毛玻璃底部折射
        val prismHues = floatArrayOf(0f, 45f, 120f, 200f, 280f, 330f) // 光谱
        val cx = w * 0.5f
        val cy = h * (0.985f)
        val baseR = w * (0.30f + bass * 0.06f)
        // 彩虹层：中心偏冷，向外沿光谱色
        for (i in prismHues.indices) {
            val u = i.toFloat() / (prismHues.size - 1)
            val r = baseR * (0.65f + u * 1.5f)
            val alpha = (0.05f + (1f - u) * 0.06f + treble * 0.03f).coerceIn(0.01f, 0.18f)
            drawOval(
                color = VisualizerMath.hsl(prismHues[i], 0.85f, 0.6f),
                topLeft = Offset(cx - r, cy - r * 0.18f),
                size = Size(r * 2f, r * 0.36f),
                alpha = alpha,
                blendMode = BlendMode.Plus
            )
        }
        // 来回流动的一抹柔光（随时间在彩虹上滑动）
        val glide = abs(sin(t * 0.4f))
        val glideHue = lerp(210f, 0f, glide)
        drawOval(
            color = VisualizerMath.hsl(glideHue, 0.8f, 0.65f),
            topLeft = Offset(cx - baseR * 0.5f, cy - baseR * 0.08f),
            size = Size(baseR, baseR * 0.16f),
            alpha = 0.06f + energy * 0.06f,
            blendMode = BlendMode.Plus
        )
    }

    // ── ④ 莫兰迪暗角 ──────────────────────────────────────

    private fun DrawScope.drawVignette(w: Float, h: Float, bass: Float) {
        // 四边压暗，聚焦中央、营造纵深
        val dark = VisualizerMath.hsl(35f, 0.3f, 0.02f)
        drawRect(dark, topLeft = Offset(0f, 0f), size = Size(w, h * 0.18f), alpha = 0.5f)
        drawRect(dark, topLeft = Offset(0f, h * 0.9f), size = Size(w, h * 0.1f), alpha = 0.5f)
        drawRect(dark, topLeft = Offset(0f, 0f), size = Size(w * 0.12f, h), alpha = 0.5f)
        drawRect(dark, topLeft = Offset(w * 0.88f, 0f), size = Size(w * 0.12f, h), alpha = 0.5f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun fpow16(x: Float): Float {
        val u = x.coerceIn(0f, 1f)
        return u * u * (0.6f + 0.4f * u)
    }
}
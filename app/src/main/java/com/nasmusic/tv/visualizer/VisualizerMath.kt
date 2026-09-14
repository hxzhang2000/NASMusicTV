package com.nasmusic.tv.visualizer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 可视化公共数学工具。
 *
 * 本类中的函数均为无副作用的纯计算，运行期零分配。
 *
 * 注：伪随机数**不在本类**（P1#5，2026-09-14）——原 `private var seed` 是进程级单例
 * 状态，与「纯计算」的定位矛盾且被所有渲染器共享，已迁出为每渲染器独立的
 * [VisualizerRandom] 实例。
 */
object VisualizerMath {

    /**
     * 伪 3D 透视投影：z = 0 为屏幕平面，z 越大看起来越远越小。
     *
     * @param f 焦距，越大透视越弱
     */
    fun project(x: Float, y: Float, z: Float, f: Float, cx: Float, cy: Float): Offset {
        val s = f / (f + z)
        return Offset(cx + x * s, cy + y * s)
    }

    /** 投影缩放系数 */
    fun scaleAt(z: Float, f: Float): Float = f / (f + z)
    /**
     * 快起慢落包络：target 突变时瞬时到达，随后按 decay 缓慢回落。
     * 这是"呼吸感"的核心——反过来（慢起快落）会变成廉价的抽动。
     */
    fun envelope(current: Float, target: Float, decay: Float): Float =
        if (target >= current) target else maxOf(target, current * decay)

    /** 0..1 → 高度，带最小可见量 */
    fun barHeight(v: Float, maxH: Float, minH: Float = 2f): Float =
        minH + v * (maxH - minH)

    /** 线性插值 */
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** 区间映射并夹紧 */
    fun map(v: Float, inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
        if (inMax == inMin) return outMin
        val t = ((v - inMin) / (inMax - inMin)).coerceIn(0f, 1f)
        return outMin + t * (outMax - outMin)
    }

    /**
     * 极坐标取点。angleRad 为弧度，0 指向正右方。
     */
    fun polar(cx: Float, cy: Float, radius: Float, angleRad: Float): Offset =
        Offset(cx + cos(angleRad) * radius, cy + sin(angleRad) * radius)

    /** 角度转弧度 */
    fun rad(deg: Float): Float = deg * (PI.toFloat() / 180f)

    /**
     * HSL 取色（hue: 0–360, saturation/lightness: 0–1）。
     * 用于彩虹渐变类效果；每帧调用次数多时建议预先建表。
     */
    fun hsl(hue: Float, saturation: Float, lightness: Float, alpha: Float = 1f): Color =
        Color.hsl(hue % 360f, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f), alpha)

    /**
     * 霓虹化：把可能灰扑扑的封面色增强为高饱和、明快的霓虹色。
     * 从封面取色后调用（每首歌一次），保证可视化整体鲜艳、不闷。
     *
     * @param minSat   饱和度下限（目标 < 下限时拉高）
     * @param targetL  目标亮度（过暗过亮都向它收敛）
     */
    fun neonize(color: Color, minSat: Float = 0.85f, targetL: Float = 0.72f): Color {
        val (h, s, l) = rgbToHsl(color)
        val sat = maxOf(s, minSat)
        val lit = (l * 0.4f + targetL * 0.6f).coerceIn(0.50f, 0.85f)
        return Color.hsl(h, sat, lit, color.alpha)
    }

    /** 压暗（背景用）：[e] 越小越暗 */
    fun darken(color: Color, e: Float = 0.35f): Color {
        val (h, s, l) = rgbToHsl(color)
        val dark = (l * e).coerceIn(0.06f, 0.24f)
        // 背景压暗时降饱和但仍带一点色相，避免纯黑死板（略提亮 → 荧光更映衬）
        return Color.hsl(h, (s * 0.6f).coerceIn(0.35f, 0.65f), dark, color.alpha)
    }

    /** RGB → HSL。返回 [hue(0-360), sat(0-1), light(0-1)] */
    fun rgbToHsl(color: Color): Triple<Float, Float, Float> {
        val r = color.red
        val g = color.green
        val b = color.blue
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val l = (maxC + minC) / 2f
        val d = maxC - minC
        if (d <= 0.0001f) return Triple(0f, 0f, l)
        val s = d / (1f - kotlin.math.abs(2f * l - 1f))
        val h = when (maxC) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return Triple(if (h < 0f) h + 360f else h, s, l)
    }

    /**
     * RGB → 色相（0-360），**无堆分配**（P1#6，2026-09-14）。
     *
     * [rgbToHsl] 返回 `Triple`，而 [com.nasmusic.tv.visualizer.renderers.LyricsDotMatrixRenderer]
     * 在**每帧绘制路径**上只需要色相，却为此每帧分配一个 `Triple`（违反本项目
     * "绘制循环零分配"铁律）。本函数只算色相并返回基本类型 `Float`，零分配。
     *
     * 数值与 [rgbToHsl] 的色相分量**逐位一致**，含无彩色（`d <= 0.0001f`）返回 `0f`
     * 的分支。需要饱和/亮度时仍请用 [rgbToHsl]（其调用方均为每首歌一次，非每帧）。
     */
    fun hueOf(color: Color): Float {
        val r = color.red
        val g = color.green
        val b = color.blue
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val d = maxC - minC
        if (d <= 0.0001f) return 0f
        val h = when (maxC) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    /** 连续色相渐变（青→蓝→粉紫 等）。[t] 0→1 对应 hue0→hue1，走最短色相弧线 */
    fun hueGradient(hue0: Float, hue1: Float, t: Float): Float {
        var d = (hue1 - hue0) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return (hue0 + d * t.coerceIn(0f, 1f) + 360f) % 360f
    }

    /** 颜色向白色插值（用于"刺破"高亮段） */
    fun towardWhite(color: Color, amount: Float): Color =
        Color(
            red = lerp(color.red, 1f, amount),
            green = lerp(color.green, 1f, amount),
            blue = lerp(color.blue, 1f, amount),
            alpha = color.alpha
        )
}

package com.nasmusic.tv.visualizer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 可视化公共数学工具。
 *
 * 所有函数均为无副作用的纯计算，运行期零分配。
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

    /** 颜色向白色插值（用于"刺破"高亮段） */
    fun towardWhite(color: Color, amount: Float): Color =
        Color(
            red = lerp(color.red, 1f, amount),
            green = lerp(color.green, 1f, amount),
            blue = lerp(color.blue, 1f, amount),
            alpha = color.alpha
        )

    /** 极简确定性伪随机（避免每帧 Random 对象分配） */
    private var seed = 0x2F6E2B1u

    fun nextRandom(): Float {
        seed = seed * 1664525u + 1013904223u
        return (seed shr 8).toFloat() / 16777216f
    }

    fun nextRandomSigned(): Float = nextRandom() * 2f - 1f

    /** 重置伪随机序列（进入效果时调用，保证可复现） */
    fun resetRandom(newSeed: UInt = 0x2F6E2B1u) { seed = newSeed }
}

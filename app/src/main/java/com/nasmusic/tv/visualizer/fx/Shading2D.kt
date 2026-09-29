package com.nasmusic.tv.visualizer.fx

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import com.nasmusic.tv.visualizer.VisualizerMath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 2D 光照与材质工具。**纯计算 + 少量 draw，全部零分配**。
 *
 * ⛔ [LIGHT_ANGLE_DEG] 是**全库唯一**主光方向 —— 所有高光、明暗、投影必须由它推导。
 */
object Shading2D {

    /** 主光方向：315°（右上：0°=右、顺时针、屏幕 y 向下的极角约定 ⇒ cos=+0.7071 / sin=−0.7071）。⛔ 不得在渲染器内另写光源角 */
    const val LIGHT_ANGLE_DEG = 315f

    private const val DEG = (PI / 180.0).toFloat()

    /** cos(315°) / sin(315°) 的常量（预算，避免每帧算）。⚠️ 必须与 [LIGHT_ANGLE_DEG] 的极角换算一致 */
    private const val LX = 0.70710678f
    private const val LY = -0.70710678f

    /** 主光单位向量 */
    val lightDir: Offset get() = Offset(LX, LY)

    /**
     * 沿法线的明暗（Lambert 半兰伯特化）：返回 0..1。
     * @param normalAngleDeg 该点表面**外法线**方向（角度制，0° 指向正右方，
     *   与 [VisualizerMath.polar] 的极角约定一致）。
     *   ⚠️ 圆环上某点的外法线 = 该点极角（`atan2(y-cy, x-cx)`）转角度。
     */
    fun lambert(normalAngleDeg: Float): Float {
        val a = normalAngleDeg * DEG
        val d = cos(a) * LX + sin(a) * LY
        return (d * 0.5f + 0.5f).coerceIn(0f, 1f)   // 半兰伯特：背面不会全黑
    }

    /** 镜面高光强度 0..1：法线越对准光向越强 */
    fun specular(normalAngleDeg: Float, shininess: Float = 24f): Float {
        val a = normalAngleDeg * DEG
        val d = cos(a) * LX + sin(a) * LY
        if (d <= 0f) return 0f
        return d.pow(shininess).coerceIn(0f, 1f)
    }

    /** 边缘光强度 0..1：**背向**光源时最强（模拟环境反射的轮廓亮线） */
    fun rim(normalAngleDeg: Float): Float {
        val a = normalAngleDeg * DEG
        val d = cos(a) * LX + sin(a) * LY
        val l = (d * 0.5f + 0.5f).coerceIn(0f, 1f)
        return 1f - l * l
    }

    /**
     * 双层描边（"倒角"）：亮侧沿光向偏移画高光描边、暗侧反向偏移画暗描边。
     * 成本 = 2 次 `drawPath` + 2 次 `canvas.save/restore`（`translate` 是 `inline`，见 §15.4-A7）。
     * ⛔ 只对**闭合轮廓**有意义（齿轮齿廓 / 折纸三角 / 节点 / 行星）。
     */
    fun DrawScope.bevelStroke(
        path: Path,
        base: Color,
        lightAlpha: Float = 0.55f,
        darkAlpha: Float = 0.45f,
        width: Float = 1.6f
    ) {
        val off = width * 0.5f
        translate(-LX * off, -LY * off) {
            drawPath(path, VisualizerMath.towardWhite(base, 0.55f),
                alpha = lightAlpha, style = Stroke(width = width))
        }
        translate(LX * off, LY * off) {
            drawPath(path, VisualizerMath.darken(base, 0.55f),
                alpha = darkAlpha, style = Stroke(width = width))
        }
    }

    /**
     * 接触阴影：主体下方（沿光向**反**方向）偏移一层暗椭圆。
     * 成本 = 1 次 `drawOval`。
     */
    fun DrawScope.contactShadow(
        center: Offset,
        radius: Float,
        dropK: Float = 0.12f,
        alpha: Float = 0.28f
    ) {
        val ox = -LX * radius * dropK
        val oy = -LY * radius * dropK
        val rx = radius * 0.92f
        val ry = radius * 0.72f      // 压扁 → 像地面投影而非圆盘
        drawOval(
            color = Color.Black,
            topLeft = Offset(center.x + ox - rx, center.y + oy - ry),
            size = androidx.compose.ui.geometry.Size(rx * 2f, ry * 2f),
            alpha = alpha
        )
    }

    // 缓存：键 → Brush（draw 期只查表，零分配；构建只在键未命中时发生一次）
    private val brushKeys = LongArray(16)
    private val brushValues = arrayOfNulls<Brush>(16)
    private var brushCount = 0

    /**
     * **静态主体**的球面渐变（太阳 / 中心光源）。
     *
     * ⛔ 为什么是 `Cached`：`Brush.radialGradient` 的首参是
     * `vararg colorStops: Pair<Float, Color>`（`Brush.kt:296-306`），
     * 每帧新建会分配 Pair + 两个 `List` ⇒ 违反零分配红线。
     * **移动主体请用 `ProceduralTexture.sphereSprite`**，不要用本方法。
     *
     * @param key 调用方的缓存键（惯例 = `(w.toLong() shl 32) or h.toLong()`，
     *   建议再混入 base 的 argb —— **缓存键的维度必须 ⊇ 被缓存对象实际依赖的维度**），
     *   与 `RadarGridRenderer.ensureBrush` 同范式。
     */
    fun shadeBrushCached(
        key: Long,
        center: Offset,
        radius: Float,
        base: Color,
        contrast: Float = 0.42f
    ): Brush {
        for (i in 0 until brushCount) {
            if (brushKeys[i] == key) return brushValues[i]!!
        }
        val brush = Brush.radialGradient(
            0f to VisualizerMath.towardWhite(base, contrast),
            1f to VisualizerMath.darken(base, contrast),
            center = center,
            radius = radius
        )
        if (brushCount >= brushKeys.size) {   // 简单老化：缓存满则清空重来（键数量有限）
            brushCount = 0
        }
        brushKeys[brushCount] = key
        brushValues[brushCount] = brush
        brushCount++
        return brush
    }

    /** Reinhard 色调映射：`c / (1 + c)`。⛔ `Plus` 叠加层数 ≥ 3 时必须调用 */
    fun toneMap(c: Float): Float = c / (1f + c)
}

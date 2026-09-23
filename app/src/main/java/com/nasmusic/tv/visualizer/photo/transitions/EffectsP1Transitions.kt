package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId
import kotlin.math.roundToInt

/**
 * 风格化类 P1 + 音频反应类 P1（§14.3）
 *
 * - [KaleidoTransition] `KALEIDO`：竖条镜像万花筒
 * - [ChromaticSplitTransition] `CHROMATIC_SPLIT`：RGB 三通道错位叠加、随推进合拢
 * - [RgbSlideTransition] `RGB_SLIDE`：三通道从三个方向滑入合拢
 * - [SpectrumBarsTransition] `SPECTRUM_BARS`：量化为阶梯的频谱柱（★ 默认不进随机池）
 * - [BeatCutTransition] `BEAT_CUT`：节拍硬切（窗口为 0，★ 默认不进随机池）
 * - [CinematicBarsTransition] `CINEMATIC_BARS`：电影黑边收缩（**串行**）
 */

/**
 * 万花筒：画面竖切 6 条，奇数条**水平镜像**，逐条按对角波次显现。
 * 镜像用 `DrawScope.scale(-1f, 1f, pivot)`（内部 save/restore，零分配）。
 */
internal class KaleidoTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.KALEIDO

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val segW = geom.canvasW / SEGS
        val denom = 1f - STAGGER
        val cy = geom.canvasH * 0.5f
        for (i in 0 until SEGS) {
            val delay = i.toFloat() / (SEGS - 1) * STAGGER
            val local = ((p - delay) / denom).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val x0 = i * segW
            clipRect(x0, 0f, x0 + segW, geom.canvasH) {
                if (i % 2 == 1) {
                    // 奇数条水平镜像（绕该条中轴翻转后整幅绘制，裁剪后只露出该条）
                    scale(-1f, 1f, pivot = Offset(x0 + segW * 0.5f, cy)) {
                        drawPhoto(b, geom.srcB, geom.dstB, alpha = local)
                    }
                } else {
                    drawPhoto(b, geom.srcB, geom.dstB, alpha = local)
                }
            }
        }
    }

    private companion object {
        const val SEGS = 6
        const val STAGGER = 0.45f
    }
}

/**
 * RGB 三通道错位（共用底座）：三份**单通道染色**的新图副本以 `BlendMode.Plus` 相加 ——
 * 三份对齐时其和恰好等于原图（R+G+B 互补），错位时呈色散。
 *
 * ⛔ `ColorFilter.lighting(...)` 与 `Paint` 在**字段初始化**时创建（实例构造期），
 * 不在绘制路径分配。`BlendMode.PLUS` 映射 `PorterDuff.Mode.ADD`，API 22 可用。
 */
internal abstract class ChannelGhostTransition : PhotoTransition {

    /** 三份单通道滤镜（字段初始化 = 构造期分配，稳态零分配） */
    protected val redFilter = ColorFilter.lighting(Color(0xFFFF0000), Color(0xFF000000))
    protected val greenFilter = ColorFilter.lighting(Color(0xFF00FF00), Color(0xFF000000))
    protected val blueFilter = ColorFilter.lighting(Color(0xFF0000FF), Color(0xFF000000))

    /** 子类给出三份副本的偏移（`p` 已缓动） */
    protected abstract fun offsets(p: Float, geom: PhotoGeometry, out: FloatArray)

    /** 副本 alpha（随推进变化） */
    protected abstract fun ghostAlpha(p: Float): Float

    private val off = FloatArray(6)

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        offsets(p, geom, off)
        val alpha = ghostAlpha(p)
        if (alpha <= 0.01f) return

        drawGhost(b, geom, off[0], off[1], redFilter, alpha)
        drawGhost(b, geom, off[2], off[3], greenFilter, alpha)
        drawGhost(b, geom, off[4], off[5], blueFilter, alpha)
    }

    private fun DrawScope.drawGhost(
        b: ImageBitmap,
        geom: PhotoGeometry,
        dx: Float,
        dy: Float,
        filter: ColorFilter,
        alpha: Float,
    ) {
        val src = geom.srcB
        val dst = geom.dstB
        drawImage(
            image = b,
            srcOffset = IntOffset(src.left.roundToInt(), src.top.roundToInt()),
            srcSize = IntSize(src.width.roundToInt(), src.height.roundToInt()),
            dstOffset = IntOffset((dst.left + dx).roundToInt(), (dst.top + dy).roundToInt()),
            dstSize = IntSize(dst.width.roundToInt(), dst.height.roundToInt()),
            alpha = alpha.coerceIn(0f, 1f),
            blendMode = BlendMode.Plus,
            colorFilter = filter,
        )
    }
}

/** RGB 色彩分离：水平错位，`p → 1` 三份副本合拢到原位 */
internal class ChromaticSplitTransition : ChannelGhostTransition() {

    override val id: PhotoTransitionId = PhotoTransitionId.CHROMATIC_SPLIT

    override fun offsets(p: Float, geom: PhotoGeometry, out: FloatArray) {
        val d = geom.canvasW * MAX_SHIFT * (1f - p)
        out[0] = -d; out[1] = 0f
        out[2] = 0f; out[3] = 0f
        out[4] = d; out[5] = 0f
    }

    override fun ghostAlpha(p: Float): Float = p

    private companion object {
        const val MAX_SHIFT = 0.06f
    }
}

/** 色彩分离滑入：三通道分别从左 / 上 / 右滑入，在原位合拢成完整图 */
internal class RgbSlideTransition : ChannelGhostTransition() {

    override val id: PhotoTransitionId = PhotoTransitionId.RGB_SLIDE

    override fun offsets(p: Float, geom: PhotoGeometry, out: FloatArray) {
        // 位移按 easeOut 收敛（缓动已由时钟做 easeOutQuad，这里线性即可）
        val k = 1f - p
        val dx = geom.canvasW * 0.4f * k
        val dy = geom.canvasH * 0.4f * k
        out[0] = -dx; out[1] = 0f
        out[2] = 0f; out[3] = -dy
        out[4] = dx; out[5] = 0f
    }

    override fun ghostAlpha(p: Float): Float = p
}

/**
 * 频谱条带切换（★ `audioReactive`，默认不进随机池）：与 `SPECTRUM_WIPE` 的差别是
 * **柱高被量化成阶梯**（更像「频谱柱」）且条数压到 24（省 draw call）。
 * 无频谱数据时退化为整体淡入。
 */
internal class SpectrumBarsTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.SPECTRUM_BARS

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val spec = geom.spectrum
        if (spec == null || spec.isEmpty()) {
            drawPhoto(b, geom.srcB, geom.dstB, alpha = p)
            return
        }

        val barW = geom.canvasW / BARS
        val h = geom.canvasH
        val sampleStep = spec.size.toFloat() / BARS
        for (i in 0 until BARS) {
            val s = spec[(i * sampleStep).toInt().coerceIn(0, spec.size - 1)]
            val col = (p * (1f + s * GAIN)).coerceIn(0f, 1f)
            // 量化成 STEPS 级阶梯（0.125 步长）—— 「条带」的形状感来自这里
            val quantized = (col * STEPS).toInt().coerceIn(0, STEPS).toFloat() / STEPS
            val ch = h * quantized
            if (ch <= 0f) continue
            val x = i * barW
            clipRect(x, h - ch, x + barW, h) { drawPhoto(b, geom.srcB, geom.dstB) }
        }
    }

    private companion object {
        const val BARS = 24
        const val STEPS = 8
        const val GAIN = 1f
    }
}

/**
 * 节拍硬切（★ `audioReactive`，默认不进随机池；§5.8：窗口为 0）。
 *
 * ⚠️ 固定档选中它时 `PhotoTransitionClock` 会把 ENTER/EXIT 窗口压到 0（p 恒为 1 或瞬间跳变）；
 * 但**备份导入**可把任何值写进 `photoWallFixedTransition`，这条分支必须防住中间态：
 * `p < 0.5` 画旧图、`p >= 0.5` 画新图，绝不产生半透明混合（那就不是「硬切」了）。
 */
internal class BeatCutTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.BEAT_CUT

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        if (p < HALF) {
            drawPhoto(a, geom.srcA, geom.dstA)
        } else {
            drawPhoto(b, geom.srcB, geom.dstB)
        }
    }

    private companion object {
        const val HALF = 0.5f
    }
}

/**
 * 电影黑边收缩（**串行**，`requiresSequential`）：上下黑边合拢遮住旧图 → 全黑瞬间换图 → 黑边打开露出新图。
 * 黑边 = 顶部/底部各一条实心矩形；照片按剩余可见区裁剪绘制。
 */
internal class CinematicBarsTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.CINEMATIC_BARS

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        // 合拢量：0 → 1 → 0（中点全黑）
        val close = if (p < HALF) p * 2f else (1f - p) * 2f
        val barH = geom.canvasH * 0.5f * close
        val visibleTop = barH
        val visibleBottom = geom.canvasH - barH

        if (visibleBottom > visibleTop) {
            // 串行模型：中点前只有旧图，中点后只有新图
            if (p < HALF) {
                clipRect(0f, visibleTop, geom.canvasW, visibleBottom) {
                    drawPhoto(a, geom.srcA, geom.dstA)
                }
            } else {
                clipRect(0f, visibleTop, geom.canvasW, visibleBottom) {
                    drawPhoto(b, geom.srcB, geom.dstB)
                }
            }
        }

        // 黑边（压在最上层；中点处合拢成全黑）
        if (barH > 0f) {
            drawRect(
                color = Color.Black,
                topLeft = Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(geom.canvasW, visibleTop),
            )
            drawRect(
                color = Color.Black,
                topLeft = Offset(0f, visibleBottom),
                size = androidx.compose.ui.geometry.Size(geom.canvasW, barH),
            )
        }
    }

    private companion object {
        const val HALF = 0.5f
    }
}

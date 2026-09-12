package com.nasmusic.tv.visualizer

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import com.nasmusic.tv.data.model.VisualQuality

/**
 * 每帧传给渲染器的上下文（不含频谱数据，频谱在 [AudioFrame] 中）。
 *
 * **零分配约束**：实例由 UI 层持有并复用，每帧只更新字段、不重新创建。
 * 渲染器不得缓存本对象跨帧使用。
 */
class RenderContext {

    var quality: VisualQuality = VisualQuality.Default
    var palette: CoverPalette = CoverPalette.Fallback
    var cover: ImageBitmap? = null
    var canvasSize: Size = Size.Zero
    /** overscan 安全边距，≥ 5% */
    var safeAreaPx: Float = 0f
    var nowMs: Long = 0L
    /** 当前歌曲标题（E19 粒子文字用） */
    var caption: String? = null

    val width: Float get() = canvasSize.width
    val height: Float get() = canvasSize.height
    /** 短边——多数效果用它做基准半径，保证竖屏也协调 */
    val minDim: Float get() = minOf(canvasSize.width, canvasSize.height)

    /** 更新字段（复用实例，零分配） */
    fun update(
        quality: VisualQuality,
        palette: CoverPalette,
        cover: ImageBitmap?,
        canvasSize: Size,
        safeAreaPx: Float,
        nowMs: Long,
        caption: String?
    ) {
        this.quality = quality
        this.palette = palette
        this.cover = cover
        this.canvasSize = canvasSize
        this.safeAreaPx = safeAreaPx
        this.nowMs = nowMs
        this.caption = caption
    }
}

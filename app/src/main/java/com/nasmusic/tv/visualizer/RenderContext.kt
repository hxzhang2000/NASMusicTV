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
    /** 当前歌曲 id（E23 歌词点阵：用于检测切歌并重置行状态） */
    var songId: String? = null
    /** 当前歌曲标题（E19 粒子文字用） */
    var caption: String? = null
    /** 当前歌词行（E23 歌词点阵用） */
    var currentLyricLine: String? = null
    /** 下一句歌词（E23 两行滚动用） */
    var nextLyricLine: String? = null
    /** 当前行内演唱进度 0..1（E23 歌词点阵用，配合前快后慢曲线实现逐字感） */
    var lyricLineProgress: Float = 0f
    /** 当前行是否有逐字时间戳（E23 歌词点阵用，true=可用精确逐字，false=用进度曲线近似） */
    var lyricHasWordTimestamps: Boolean = false
    /** 当前歌词行索引（E23 两行滚动用，用于检测行切换） */
    var lyricLineIndex: Int = -1
    /** 当前行逐字时间戳列表（E23 逐字消散/凝聚用） */
    var lyricWordTimestamps: List<Long> = emptyList()
    /** 全曲歌词中最长句的字符数（E23 歌词点阵：据此计算自适应字体大小，保证最长行也不超宽） */
    var lyricMaxLineChars: Int = 0
    /** 全曲歌词中最长句的实际文本（E23 歌词点阵：用于精确测量宽度，占满 80% 屏宽） */
    var longestLyricLine: String? = null

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
        caption: String?,
        currentLyricLine: String? = null,
        nextLyricLine: String? = null,
        lyricLineProgress: Float = 0f,
        lyricHasWordTimestamps: Boolean = false,
        lyricLineIndex: Int = -1,
        lyricWordTimestamps: List<Long> = emptyList(),
        lyricMaxLineChars: Int = 0,
        longestLyricLine: String? = null,
        songId: String? = null
    ) {
        this.quality = quality
        this.palette = palette
        this.cover = cover
        this.canvasSize = canvasSize
        this.safeAreaPx = safeAreaPx
        this.nowMs = nowMs
        this.caption = caption
        this.currentLyricLine = currentLyricLine
        this.nextLyricLine = nextLyricLine
        this.lyricLineProgress = lyricLineProgress
        this.lyricHasWordTimestamps = lyricHasWordTimestamps
        this.lyricLineIndex = lyricLineIndex
        this.lyricWordTimestamps = lyricWordTimestamps
        this.lyricMaxLineChars = lyricMaxLineChars
        this.longestLyricLine = longestLyricLine
        this.songId = songId
    }
}

package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRandom
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * VINTAGE_TV — 怀旧老电视效果
 *
 * 视觉特征：
 * 1. 老电视黑屏背景（非纯黑，带微弱灰蓝色调）
 * 2. 中间显示一行白色歌词（当前正在唱的那行）
 * 3. 画面有随机闪点噪点（模拟老电视雪花噪点）
 * 4. 偶尔出现白色竖条（模拟老电视同步信号丢失/干扰条）
 * 5. 歌词文字有轻微抖动（模拟老电视画面抖动）
 * 6. 扫描线效果（水平暗纹）
 */
class VintageTvRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.VINTAGE_TV

    // ── 随机源 ──────────────────────────────────────────────
    private val rng = VisualizerRandom()

    // ── 歌词渲染相关 ────────────────────────────────────────
    private var lyricPaint: AndroidPaint? = null
    private var lyricBitmap: ImageBitmap? = null
    private var lastLyricText: String? = null
    private var lastCanvasW = 0f
    private var lastCanvasH = 0f
    private var lastFontSize = 0f

    // ── 噪点/闪点 ──────────────────────────────────────────
    private var noiseBuffer: FloatArray? = null
    private var noiseBufferSize = 0
    private var lastNoiseUpdateMs = 0L

    // ── 竖条干扰 ──────────────────────────────────────────
    private var verticalBars: MutableList<VerticalBar> = mutableListOf()
    private var nextBarTimeMs = 0L

    // ── 抖动/扫描线 ────────────────────────────────────────
    private var scanlinePhase = 0f
    private var lastFrameMs = 0L

    // ── 切歌检测 ──────────────────────────────────────────
    private var boundSongId: String? = null
    private var boundCaption: String? = null

    // ── 常量 ──────────────────────────────────────────────
    private companion object {
        // 老电视黑屏背景色：非纯黑，带微弱灰蓝色调（模拟CRT关机/无信号状态）
        const val BG_COLOR = 0xFF0D1018 // #0D1018 - 深灰蓝，非纯黑
        
        // 扫描线
        const val SCANLINE_COUNT = 240 // 扫描线数量
        const val SCANLINE_OPACITY = 0.08f
        const val SCANLINE_SPEED = 0.0005f // 扫描线移动速度
        
        // 噪点
        const val NOISE_DENSITY = 0.0015f // 噪点密度（0-1）
        const val NOISE_SIZE_MIN = 1f
        const val NOISE_SIZE_MAX = 2f
        const val NOISE_UPDATE_INTERVAL_MS = 100L // 噪点更新间隔
        
        // 竖条干扰
        const val BAR_MIN_INTERVAL_MS = 3000L // 最小间隔 3 秒
        const val BAR_MAX_INTERVAL_MS = 8000L // 最大间隔 8 秒
        const val BAR_DURATION_MS = 80L // 单个竖条持续时间
        const val BAR_WIDTH_MIN = 2f
        const val BAR_WIDTH_MAX = 6f
        const val BAR_COUNT_MAX = 3 // 同时最多 3 条
        
        // 文字抖动
        const val JITTER_AMPLITUDE = 1.5f // 抖动幅度（像素）
        const val JITTER_FREQUENCY = 0.008f // 抖动频率
        
        // 字体
        const val BASE_FONT_SIZE_RATIO = 0.045f // 基础字体大小占屏高比例
        const val MIN_FONT_SIZE_RATIO = 0.035f
        const val MAX_FONT_SIZE_RATIO = 0.065f
    }

    override fun onEnter(ctx: RenderContext) {
        // 初始化画笔
        lyricPaint = AndroidPaint().apply {
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = AndroidPaint.Align.CENTER
            color = android.graphics.Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
        }
        
        // 重置状态
        lastLyricText = null
        lastCanvasW = 0f
        lastCanvasH = 0f
        lastFontSize = 0f
        noiseBuffer = null
        noiseBufferSize = 0
        lastNoiseUpdateMs = 0L
        verticalBars.clear()
        nextBarTimeMs = 0L
        scanlinePhase = 0f
        lastFrameMs = 0L
        boundSongId = null
        boundCaption = null
    }

    override fun onExit() {
        // ImageBitmap 由 Compose 管理，无需手动释放
        lyricBitmap = null
        noiseBuffer = null
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val now = ctx.nowMs
        if (lastFrameMs == 0L) lastFrameMs = now
        val dt = (now - lastFrameMs) / 1000f
        lastFrameMs = now

        // ── 切歌检测：重置状态 ────────────────────────────────
        if (ctx.songId != boundSongId || ctx.caption != boundCaption) {
            boundSongId = ctx.songId
            boundCaption = ctx.caption
            lastLyricText = null // 强制重新生成歌词位图
            verticalBars.clear()
            nextBarTimeMs = now + (BAR_MIN_INTERVAL_MS + (rng.next() * (BAR_MAX_INTERVAL_MS - BAR_MIN_INTERVAL_MS)).toLong())
        }

        // ── 背景：老电视黑屏色 ───────────────────────────────
        drawRect(Color(BG_COLOR), topLeft = Offset.Zero, size = size)

        // ── 扫描线效果 ──────────────────────────────────────
        drawScanlines(w, h, dt)

        // ── 随机噪点/闪点 ────────────────────────────────────
        drawNoise(w, h, now)

        // ── 竖条干扰 ────────────────────────────────────────
        drawVerticalBars(w, h, now)

        // ── 歌词文字（带抖动） ──────────────────────────────
        drawLyrics(w, h, ctx, frame, now)
    }

    /**
     * 绘制扫描线（水平暗纹，模拟CRT扫描线）
     */
    private fun DrawScope.drawScanlines(w: Float, h: Float, dt: Float) {
        scanlinePhase = (scanlinePhase + SCANLINE_SPEED * dt * 1000f) % 1f
        
        val lineSpacing = h / SCANLINE_COUNT
        val lineHeight = lineSpacing * 0.4f
        
        for (i in 0 until SCANLINE_COUNT) {
            val y = (i * lineSpacing + scanlinePhase * lineSpacing) % h
            val alpha = SCANLINE_OPACITY * (0.5f + 0.5f * sin((i * 0.1f + scanlinePhase * 10f)))
            
            if (alpha > 0.01f) {
                drawLine(
                    Color(0xFF000000),
                    Offset(0f, y),
                    Offset(w, y),
                    strokeWidth = lineHeight,
                    alpha = alpha,
                    blendMode = BlendMode.SrcOver
                )
            }
        }
    }

    /**
     * 绘制随机噪点（模拟老电视雪花噪点）
     */
    private fun DrawScope.drawNoise(w: Float, h: Float, nowMs: Long) {
        // 定期更新噪点缓冲区
        if (noiseBuffer == null || nowMs - lastNoiseUpdateMs >= NOISE_UPDATE_INTERVAL_MS) {
            updateNoiseBuffer(w, h)
            lastNoiseUpdateMs = nowMs
        }

        val buffer = noiseBuffer!!
        val count = noiseBufferSize
        
        for (i in 0 until count) {
            val idx = i * 3
            val x = buffer[idx]
            val y = buffer[idx + 1]
            val size = buffer[idx + 2]
            val alpha = 0.3f + rng.next() * 0.5f // 0.3-0.8 透明度变化
            
            if (size > 0.5f) {
                drawRect(
                    Color(0xFFFFFFFF),
                    topLeft = Offset(x - size / 2, y - size / 2),
                    size = Size(size, size),
                    alpha = alpha
                )
            }
        }
    }

    /**
     * 更新噪点缓冲区
     */
    private fun updateNoiseBuffer(w: Float, h: Float) {
        val estimatedCount = (w * h * NOISE_DENSITY).roundToInt().coerceAtLeast(100).coerceAtMost(2000)
        if (noiseBuffer == null || noiseBuffer!!.size < estimatedCount * 3) {
            noiseBuffer = FloatArray(estimatedCount * 3)
        }
        val buffer = noiseBuffer!!
        var count = 0
        
        for (i in 0 until estimatedCount) {
            val x = rng.next() * w
            val y = rng.next() * h
            val size = NOISE_SIZE_MIN + rng.next() * (NOISE_SIZE_MAX - NOISE_SIZE_MIN)
            
            val idx = count * 3
            buffer[idx] = x
            buffer[idx + 1] = y
            buffer[idx + 2] = size
            count++
        }
        noiseBufferSize = count
    }

    /**
     * 绘制竖条干扰（模拟老电视同步丢失/干扰条）
     */
    private fun DrawScope.drawVerticalBars(w: Float, h: Float, nowMs: Long) {
        // 生成新的竖条
        if (nowMs >= nextBarTimeMs && verticalBars.size < BAR_COUNT_MAX) {
            val availableSlots = BAR_COUNT_MAX - verticalBars.size
            val barCount = 1 + (rng.next() * (availableSlots + 1)).toInt()
            for (i in 0 until barCount) {
                val x = rng.next() * w * 0.8f + w * 0.1f // 避开最边缘
                val width = BAR_WIDTH_MIN + rng.next() * (BAR_WIDTH_MAX - BAR_WIDTH_MIN)
                val alpha = 0.6f + rng.next() * 0.4f // 0.6-1.0
                verticalBars.add(VerticalBar(x, width, alpha, nowMs))
            }
            // 下一次出现时间
            nextBarTimeMs = nowMs + (BAR_MIN_INTERVAL_MS + (rng.next() * (BAR_MAX_INTERVAL_MS - BAR_MIN_INTERVAL_MS)).toLong())
        }

        // 绘制并更新现有竖条
        val iterator = verticalBars.iterator()
        while (iterator.hasNext()) {
            val bar = iterator.next()
            val elapsed = nowMs - bar.startTimeMs
            
            if (elapsed >= BAR_DURATION_MS) {
                iterator.remove()
            } else {
                // 淡入淡出
                val progress = elapsed / BAR_DURATION_MS.toFloat()
                val fadeAlpha = when {
                    progress < 0.2f -> progress / 0.2f // 淡入
                    progress > 0.8f -> (1f - progress) / 0.2f // 淡出
                    else -> 1f
                } * bar.alpha
                
                if (fadeAlpha > 0.01f) {
                    drawRect(
                        Color(0xFFFFFFFF),
                        topLeft = Offset(bar.x, 0f),
                        size = Size(bar.width, h),
                        alpha = fadeAlpha,
                        blendMode = BlendMode.Plus
                    )
                }
            }
        }
    }

    /**
     * 绘制歌词（带抖动效果）
     */
    private fun DrawScope.drawLyrics(w: Float, h: Float, ctx: RenderContext, frame: AudioFrame, nowMs: Long) {
        val lyricText = ctx.currentLyricLine?.takeIf { it.isNotBlank() }
            ?: ctx.caption?.takeIf { it.isNotBlank() }
            ?: "NASMusicTV"
        
        // 计算字体大小（自适应屏幕）
        val fontSize = (h * BASE_FONT_SIZE_RATIO).coerceIn(h * MIN_FONT_SIZE_RATIO, h * MAX_FONT_SIZE_RATIO)
        
        // 检查是否需要重新生成位图（文本变化、尺寸变化、字体大小变化）
        val needRebuild = lastLyricText != lyricText 
            || abs(w - lastCanvasW) > 1f 
            || abs(h - lastCanvasH) > 1f
            || abs(fontSize - lastFontSize) > 0.5f
        
        if (needRebuild || lyricBitmap == null) {
            rebuildLyricBitmap(lyricText, w, h, fontSize)
            lastLyricText = lyricText
            lastCanvasW = w
            lastCanvasH = h
            lastFontSize = fontSize
        }

        val bitmap = lyricBitmap!!
        
        // 计算抖动偏移
        val timeSec = nowMs * 0.001f
        val jitterX = sin(timeSec * JITTER_FREQUENCY * 1000f) * JITTER_AMPLITUDE
        val jitterY = cos(timeSec * JITTER_FREQUENCY * 1000f * 1.3f) * JITTER_AMPLITUDE * 0.5f
        
        // 低音增强抖动
        val bassBoost = 1f + frame.bass * 0.5f
        
        // 绘制歌词位图（中心位置 + 抖动）
        val bitmapW = bitmap.width.toFloat()
        val bitmapH = bitmap.height.toFloat()
        val dstX = ((w - bitmapW) / 2f + jitterX * bassBoost).roundToInt()
        val dstY = ((h - bitmapH) / 2f + jitterY * bassBoost).roundToInt()
        
        drawImage(
            bitmap,
            dstOffset = IntOffset(dstX, dstY)
        )
    }

    /**
     * 重建歌词位图（离屏渲染，避免每帧分配）
     */
    private fun rebuildLyricBitmap(text: String, canvasW: Float, canvasH: Float, fontSize: Float) {
        val paint = lyricPaint!!
        paint.textSize = fontSize
        
        // 测量文本宽度
        val textWidth = paint.measureText(text)
        val maxWidth = canvasW * 0.85f
        
        // 如果文本过长，截断并加省略号
        val displayText = if (textWidth <= maxWidth) {
            text
        } else {
            var trimmed = text
            while (trimmed.isNotEmpty() && paint.measureText(trimmed + "…") > maxWidth) {
                trimmed = trimmed.dropLast(1)
            }
            trimmed + "…"
        }
        
        // 获取字体度量
        val fm = paint.fontMetrics
        val fontHeight = kotlin.math.ceil(fm.bottom - fm.top).toInt()
        val baseline = kotlin.math.ceil(-fm.top).toInt()
        
        // 位图尺寸（留白边距）
        val paddingX = kotlin.math.ceil(fontSize * 0.5f).toInt()
        val paddingY = kotlin.math.ceil(fontSize * 0.3f).toInt()
        val bmpW = maxOf(kotlin.math.ceil(paint.measureText(displayText) + paddingX * 2).toInt(), 100)
        val bmpH = maxOf(fontHeight + paddingY * 2, 50)
        
        // 创建 Android Bitmap 用于绘制文本
        val androidBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val androidCanvas = AndroidCanvas(androidBmp)
        
        // 绘制文本（居中）
        val x = (bmpW - paint.measureText(displayText)) / 2f
        val y = baseline + paddingY
        androidCanvas.drawText(displayText, x, y.toFloat(), paint)
        
        // 转换为 ImageBitmap (使用扩展函数 asImageBitmap)
        lyricBitmap = androidBmp.asImageBitmap()
    }

    /**
     * 竖条干扰数据类
     */
    private data class VerticalBar(
        val x: Float,
        val width: Float,
        val alpha: Float,
        val startTimeMs: Long
    )
}
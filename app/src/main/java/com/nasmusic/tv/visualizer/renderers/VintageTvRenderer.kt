package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
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
 * 视觉特征（v2.37.3 复古纸感配色，2026-09-27 用户定稿）：
 * 1. 做旧背景 **#DBB98E**（偏黄牛皮纸色调，非黑底）+ 黑色墨水歌词
 * 2. 中间显示当前歌词（大字、多行换行、水平垂直严格居中、黑色）
 * 3. 深色纸纹噪点（噪点色 = 背景暗化色，随浅底变化）
 * 4. **滚动暗带**：垂直同步不良，每 6~10s 一条暗带从屏底上扫
 *    （竖条干扰已按用户要求整体移除 —— 2026-09-27）
 * 5. 扫描线（240 条水平暗纹）
 * 6. **暗角 vignette + 圆角屏面**：按 4:3 画面区定界（全屏定界时四角落在 pillarbox
 *    黑边之下看不见）；平滑渐变从中性起即压暗、无「亮盘」，中部平均、仅最外沿加深
 * 7. **4:3 柱状黑边 → 胶片边缘（pillarbox 换切孔条带：纯色片基上开「居中方孔」，
 *    孔距加宽到与孔等宽的黑区，左右条带完全一致、互为平移复制无镜像）
 *    + 胶片向上滚动（切孔行与片格分隔线同相、匀速向上走片，左右条带同 offset）+ 台标 OSD**
 *    （"CH 3" 等，1s 亮/1s 灭）
 * 8. 歌词 **RGB 色差重影**（红左青右，随低音增强）+ 微抖动
 *
 * 约束：
 * - **draw 内零分配**：状态字段 onEnter 重置；位图/Brush/Path 均预构建或按尺寸缓存
 * - **相位一律 dt 累加**（PhotoTransitionClock 契约）：禁止 `nowMs × 系数` ——
 *   大时间基数下 float 精度丢失会把正弦抖动冻结/跳变
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

    // ── F：RGB 色差着色器（onEnter 一次，draw 复用） ─────────
    private var tintRed: ColorFilter? = null
    private var tintCyan: ColorFilter? = null

    // ── 噪点/闪点 ──────────────────────────────────────────
    private var noiseBuffer: FloatArray? = null
    private var noiseBufferSize = 0
    private var lastNoiseUpdateMs = 0L

    // ── 抖动/扫描线 ────────────────────────────────────────
    private var scanlinePhase = 0f
    private var lastFrameMs = 0L
    private var jitterPhaseX = 0f
    private var jitterPhaseY = 0f

    // ── D：滚动亮带 ────────────────────────────────────────
    private var rollBrush: Brush? = null
    private var rollBrushBandH = 0f
    private var rollBandH = 0f
    private var nextRollMs = 0L
    private var rollStartMs = 0L
    private var rollDurationMs = 0L

    // ── C：暗角 + 圆角屏面 ─────────────────────────────────
    private var vignetteBrush: Brush? = null
    private var vignetteW = 0f
    private var cornerPath: Path? = null
    private var cornerW = 0f
    private var cornerH = 0f

    // ── G：胶片边缘（pillarbox 换切孔条带，左右两侧完全一致的平移复制）──
    private var filmHoleL: LinearGradient? = null // 左切孔透光（x 自左条带原点量起，亮边在孔左）
    private var filmHoleR: LinearGradient? = null // 右切孔透光（配色方向与左完全相同，仅 x 平移）
    private val filmPaint = AndroidPaint().apply { isAntiAlias = true }     // 纯色件（每处先 setColor）
    private val filmGradPaint = AndroidPaint().apply { isAntiAlias = true } // 渐变件（恒 alpha=255，只换 shader）
    private val filmRect = RectF() // 切孔/高光/落影复用矩形（帧内零分配）
    private var filmCacheW = -1f
    private var filmCacheSide = -1f
    // G：胶片向上走片相位（像素，dt 累加并按 pitch 取模 ⇒ 恒在 [0, pitch)，左右条带共用）
    private var filmScrollPx = 0f

    // ── G：台标 OSD ────────────────────────────────────────
    private var osdBitmap: ImageBitmap? = null
    private var osdValidW = -1f
    private var osdValidH = -1f
    private var osdChannel: String? = null

    // ── 切歌检测 ──────────────────────────────────────────
    private var boundSongId: String? = null
    private var boundCaption: String? = null

    // ── 常量 ──────────────────────────────────────────────
    private companion object {
        // 背景：做旧底（2026-09-27 二次定稿，用户指定 #DBB98E 偏黄牛皮纸）
        const val BG_COLOR = 0xFFDBB98E // #DBB98E - 偏黄

        // 扫描线（H：加深）
        const val SCANLINE_COUNT = 240
        const val SCANLINE_OPACITY = 0.12f
        const val SCANLINE_SPEED = 0.0005f

        // 稀疏纸纹噪点 —— 颜色 = 背景暗化色（浅底上白噪点不可见，必须用深色颗粒）
        const val NOISE_DENSITY = 0.0015f
        const val NOISE_SIZE_MIN = 1f
        const val NOISE_SIZE_MAX = 2f
        const val NOISE_UPDATE_INTERVAL_MS = 100L
        const val NOISE_COLOR = 0xFF6E6044 // #F5EDD9 背景的暗化色（做旧纸纹颗粒）
        const val NOISE_ALPHA_MIN = 0.12f
        const val NOISE_ALPHA_MAX = 0.40f

        // B：文字抖动 —— 角速度（rad/s），相位 dt 累加（禁 nowMs×系数）
        const val JITTER_AMPLITUDE = 1.5f
        const val JITTER_OMEGA_X = 8.0f    // ≈1.27Hz
        const val JITTER_OMEGA_Y = 10.4f   // X × 1.3

        // D：滚动暗带（浅底上用暗带表达同步滚动，亮带在黄白底上不可见）
        const val ROLL_BAND_FRACTION = 0.07f
        const val ROLL_PEAK_ALPHA = 0.12f
        const val ROLL_DURATION_MIN_MS = 4000L
        const val ROLL_DURATION_MAX_MS = 5500L
        const val ROLL_INTERVAL_MIN_MS = 6000L
        const val ROLL_INTERVAL_MAX_MS = 10000L
        const val ROLL_INITIAL_DELAY_MIN_MS = 5000L
        const val ROLL_INITIAL_DELAY_MAX_MS = 9000L

        // C：暗角 + 圆角
        const val VIGNETTE_EDGE_COLOR = 0x80000000 // 黑 alpha ≈0.50（画面区外沿）
        const val CORNER_RADIUS_FRACTION = 0.04f

        // F：色差重影
        const val ABERR_ALPHA = 0.35f
        const val ABERR_BASE_PX = 2f

        // G：4:3 胶片边缘（切孔条带）+ OSD
        const val ASPECT = 4f / 3f
        const val OSD_TEXT_FRACTION = 0.03f
        const val OSD_BLINK_MS = 1000L
        val OSD_CHANNELS = arrayOf("CH 3", "AV 1", "VIDEO 2")

        // G：胶片边缘几何 —— 全部为**条带宽 side 的比例**（1080p side=240px，4K/手机等比）
        // 横向分段恒等：孔外边距 35% + 方孔 30% + 内侧片基 35% = 100%（方孔居黑边正中）。
        // 框线 2.5% 画在内侧 35% 边距之上、外缘高光 1% 画在外侧 35% 边距之上 —— 都不与切孔重叠。
        const val FILM_HOLE_W_FRAC = 0.30f       // 切孔宽（与孔高相等 ⇒ 接近正方形）
        const val FILM_HOLE_H_FRAC = 0.30f       // 切孔高
        const val FILM_PITCH_FRAC = 0.60f        // 切孔纵向间距 p（黑区间隙 = p − 孔高 = 30%，与孔等宽）
        const val FILM_OUTER_FRAC = 0.35f        // 孔外边距 = 内侧片基边距 = 35%（两侧对称 ⇒ 孔正中）
        const val FILM_FRAME_FRAC = 0.025f       // 与图片区之间的框线宽
        const val FILM_RX_FRAC = 0.05f           // 切孔圆角 rx（≈5% side，方孔仍读作圆角孔）
        const val FILM_SEP_FRAC = 0.009375f      // 片格分隔线高（1.5/160）
        const val FILM_BEVEL_X_FRAC = 0.015625f  // 切孔高光/落影横向偏移（2.5/160）
        const val FILM_BEVEL_Y_FRAC = 0.009375f  // 切孔高光/落影纵向偏移（1.5/160）
        const val FILM_EDGE_FRAC = 0.01f         // 屏幕外缘暖色高光宽（1.6/160）
        const val FILM_SUBLINE_FRAC = 0.00625f   // 框线内侧亮线宽（1/160）

        // G：胶片向上走片速度 —— **每秒 side 的倍数**（放片机匀速走片，无缓动、不随音频）。
        // 1080p side=240px：0.30 ⇒ 72px/s，pitch = 240×0.60 = 144px ⇒ 约 2.0s 走一格。
        const val FILM_SCROLL_SPEED = 0.30f

        // G：胶片边缘配色（预览定稿：片基纯色 #17150F / 切孔 #D8C596→#F7EECF）
        // 片基取原横向渐变中段的暖炭黑 —— 左右两侧同一纯色，无镜像渐变。
        val FILM_BASE_FLAT = 0xFF17150F.toInt()
        val FILM_HOLE_DIM = 0xFFD8C596.toInt()
        val FILM_HOLE_LIT = 0xFFF7EECF.toInt()
        val FILM_BEVEL_COLOR = 0xFFFBF4DF.toInt()   // 切孔高光边（左上，两侧同向）
        val FILM_SHADOW_COLOR = 0x99000000.toInt()  // 切孔落影（右下，两侧同向；黑 60%）
        val FILM_FRAME_COLOR = 0xFF4B4539.toInt()   // 图片区框线
        val FILM_FRAME_LINE_COLOR = 0xFF6F6553.toInt() // 框线内侧亮线
        val FILM_SEP_COLOR = 0xFF302E27.toInt()     // 片格分隔线
        val FILM_EDGE_COLOR = 0x4DBBB98E.toInt()    // 外缘高光 = #DBB98E @30%

        // 字体
        const val BASE_FONT_SIZE_RATIO = 0.08f
        const val MIN_FONT_SIZE_RATIO = 0.06f
        const val MAX_FONT_SIZE_RATIO = 0.12f

        // 台标墨黑（黄白底配黑字）
        val TEXT_COLOR = 0xFF000000.toInt()
        // 歌词灰黑 #34322B（2026-09-27 用户定稿：比纯黑浅一档）
        val LYRIC_TEXT_COLOR = 0xFF34322B.toInt()
    }

    override fun onEnter(ctx: RenderContext) {
        // 初始化画笔（黑色墨水字，黄白底）
        lyricPaint = AndroidPaint().apply {
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = AndroidPaint.Align.CENTER
            color = LYRIC_TEXT_COLOR
            typeface = Typeface.DEFAULT_BOLD
        }

        // F：色差着色器（SrcIn 只取位图 alpha → 与字色无关，红/青纯净；每帧复用零分配）
        tintRed = ColorFilter.tint(Color(0xFFFF4444), BlendMode.SrcIn)
        tintCyan = ColorFilter.tint(Color(0xFF44FFFF), BlendMode.SrcIn)

        // G：台标随进入随机（切歌不换台，保持"没换台"的观感）
        osdChannel = OSD_CHANNELS[(rng.next() * OSD_CHANNELS.size).toInt().coerceIn(0, OSD_CHANNELS.size - 1)]

        // 重置状态
        lastLyricText = null
        lastCanvasW = 0f
        lastCanvasH = 0f
        lastFontSize = 0f
        noiseBuffer = null
        noiseBufferSize = 0
        lastNoiseUpdateMs = 0L
        scanlinePhase = 0f
        lastFrameMs = 0L
        jitterPhaseX = 0f
        jitterPhaseY = 0f
        rollBrush = null
        rollBrushBandH = 0f
        rollBandH = 0f
        nextRollMs = 0L
        rollStartMs = 0L
        rollDurationMs = 0L
        vignetteBrush = null
        vignetteW = 0f
        cornerPath = null
        cornerW = 0f
        cornerH = 0f
        filmHoleL = null
        filmHoleR = null
        filmCacheW = -1f
        filmCacheSide = -1f
        filmScrollPx = 0f
        osdBitmap = null
        osdValidW = -1f
        osdValidH = -1f
        boundSongId = null
        boundCaption = null
    }

    override fun onExit() {
        // ImageBitmap 由 Compose 管理，此处仅断引用供 GC
        lyricBitmap = null
        noiseBuffer = null
        tintRed = null
        tintCyan = null
        rollBrush = null
        vignetteBrush = null
        cornerPath = null
        filmHoleL = null
        filmHoleR = null
        filmCacheW = -1f
        filmCacheSide = -1f
        osdBitmap = null
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val now = ctx.nowMs
        if (lastFrameMs == 0L) lastFrameMs = now
        // 掉帧时限制 dt，避免相位/扫描线一次跳太远
        val dt = ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.25f)
        lastFrameMs = now

        // ── 切歌检测：重置状态 ────────────────────────────────
        if (ctx.songId != boundSongId || ctx.caption != boundCaption) {
            boundSongId = ctx.songId
            boundCaption = ctx.caption
            lastLyricText = null // 强制重新生成歌词位图
        }

        // ① 背景：老电视黑屏色
        drawRect(Color(BG_COLOR), topLeft = Offset.Zero, size = size)

        // ② 扫描线（H 加深）
        drawScanlines(w, h, dt)

        // ③ 稀疏噪点
        drawNoise(w, h, now)

        // ④ 滚动暗带（D，位于噪点之上、歌词之下）
        drawRollBand(w, h, now)

        // ⑤ 歌词：色差重影 + 居中 + dt 相位抖动（F/B）
        drawLyrics(w, h, ctx, frame, now, dt)

        // ⑥ 暗角 + 圆角屏面（C，按 4:3 画面区定界的玻璃层）
        drawVignette(w, h)
        drawCornerMask(w, h)

        // ⑦ 4:3 胶片边缘 + 台标 OSD（G，最外层画框；dt 驱动胶片向上走片）
        drawPillarbox(w, h, dt)
        drawOsd(w, h, now)
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
            val alpha = NOISE_ALPHA_MIN + rng.next() * (NOISE_ALPHA_MAX - NOISE_ALPHA_MIN)

            if (size > 0.5f) {
                drawRect(
                    Color(NOISE_COLOR),
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
     * D：滚动暗带（老电视垂直同步不良的标志性特征）
     *
     * 每 6~10s 触发：带高 h×7% 的渐变暗带从屏底外侧匀速上扫（4~5.5s 走完全屏）。
     * 浅底上用黑色渐变暗带表达滚动 —— 亮带在牛皮纸底上不可见。
     * 渐变 Brush 按带高局部坐标预建一次，绘制用 canvas.save/translate 平移（零分配）。
     */
    private fun DrawScope.drawRollBand(w: Float, h: Float, nowMs: Long) {
        if (rollBandH <= 0f) rollBandH = h * ROLL_BAND_FRACTION
        if (rollBrush == null || rollBrushBandH != rollBandH) {
            rollBrush = Brush.verticalGradient(
                colors = listOf(   // Perf-exempt: rollBrush 按带高缓存（带高变化才重建），非每帧分配
                    Color.Transparent,
                    Color.Black.copy(alpha = ROLL_PEAK_ALPHA),
                    Color.Transparent
                ),
                startY = 0f,
                endY = rollBandH
            )
            rollBrushBandH = rollBandH
        }

        if (rollStartMs == 0L) {
            if (nextRollMs == 0L) {
                nextRollMs = nowMs + ROLL_INITIAL_DELAY_MIN_MS +
                    (rng.next() * (ROLL_INITIAL_DELAY_MAX_MS - ROLL_INITIAL_DELAY_MIN_MS)).toLong()
                return
            }
            if (nowMs < nextRollMs) return
            rollStartMs = nowMs
            rollDurationMs = ROLL_DURATION_MIN_MS +
                (rng.next() * (ROLL_DURATION_MAX_MS - ROLL_DURATION_MIN_MS)).toLong()
        }

        val p = (nowMs - rollStartMs) / rollDurationMs.toFloat()
        if (p >= 1f) {
            // 扫完 → 进入间歇
            rollStartMs = 0L
            nextRollMs = nowMs + ROLL_INTERVAL_MIN_MS +
                (rng.next() * (ROLL_INTERVAL_MAX_MS - ROLL_INTERVAL_MIN_MS)).toLong()
            return
        }

        val y = h - p * (h + rollBandH)
        val canvas = drawContext.canvas
        canvas.save()
        canvas.translate(0f, y)
        drawRect(brush = rollBrush!!, topLeft = Offset.Zero, size = Size(w, rollBandH))
        canvas.restore()
    }

    /**
     * 绘制歌词：RGB 色差重影垫底 + 白字主体 + dt 相位抖动
     *
     * 水平居中链（勿破坏）：行中心 = 位图中心 = 屏幕中心。
     * paint.align = CENTER ⇒ drawText 的 x 必须给位图中心 bmpW/2（见 rebuildLyricBitmap）。
     */
    private fun DrawScope.drawLyrics(
        w: Float, h: Float, ctx: RenderContext, frame: AudioFrame, nowMs: Long, dt: Float
    ) {
        val lyricText = ctx.currentLyricLine?.takeIf { it.isNotBlank() }
            ?: ctx.caption?.takeIf { it.isNotBlank() }
            ?: "NASMusicTV"

        // 计算字体大小（自适应屏幕）
        val fontSize = (h * BASE_FONT_SIZE_RATIO).coerceIn(h * MIN_FONT_SIZE_RATIO, h * MAX_FONT_SIZE_RATIO)

        // G：歌词换行宽度按 4:3 画面区约束（保证黑边内完整可见）
        val pictureW = minOf(w, h * ASPECT)

        // 检查是否需要重新生成位图（文本变化、尺寸变化、字体大小变化）
        val needRebuild = lastLyricText != lyricText
            || abs(pictureW - lastCanvasW) > 1f
            || abs(h - lastCanvasH) > 1f
            || abs(fontSize - lastFontSize) > 0.5f

        if (needRebuild || lyricBitmap == null) {
            rebuildLyricBitmap(lyricText, pictureW, h, fontSize)
            lastLyricText = lyricText
            lastCanvasW = pictureW
            lastCanvasH = h
            lastFontSize = fontSize
        }

        val bitmap = lyricBitmap!!

        // B：抖动相位 dt 累加（⛔ 禁 nowMs×系数：float 精度丢失会冻结/跳变）
        jitterPhaseX += dt * JITTER_OMEGA_X
        jitterPhaseY += dt * JITTER_OMEGA_Y
        val jitterX = sin(jitterPhaseX) * JITTER_AMPLITUDE
        val jitterY = cos(jitterPhaseY) * JITTER_AMPLITUDE * 0.5f

        // 低音增强抖动
        val bassBoost = 1f + frame.bass * 0.5f

        // 严格居中（行中心 = 位图中心 = 屏幕中心）+ 抖动微偏移
        val bitmapW = bitmap.width.toFloat()
        val bitmapH = bitmap.height.toFloat()
        val dstX = ((w - bitmapW) / 2f + jitterX * bassBoost).roundToInt()
        val dstY = ((h - bitmapH) / 2f + jitterY * bassBoost).roundToInt()

        // F：RGB 色差重影 —— 红左青右，错位随低音增强（着色器 onEnter 预建）
        val aberr = (ABERR_BASE_PX + frame.bass * 2f).roundToInt()
        val redFilter = tintRed
        val cyanFilter = tintCyan
        if (redFilter != null) {
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(dstX - aberr, dstY),
                alpha = ABERR_ALPHA,
                colorFilter = redFilter
            )
        }
        if (cyanFilter != null) {
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(dstX + aberr, dstY),
                alpha = ABERR_ALPHA,
                colorFilter = cyanFilter
            )
        }
        drawImage(bitmap, dstOffset = IntOffset(dstX, dstY))
    }

    /**
     * 重建歌词位图（离屏渲染，避免每帧分配）
     * 支持多行换行显示
     */
    private fun rebuildLyricBitmap(text: String, canvasW: Float, canvasH: Float, fontSize: Float) {
        val paint = lyricPaint!!
        paint.textSize = fontSize
        // 边缘少量虚化（2026-09-27 用户定稿）：模糊半径 ≈ 字号 2.5%（86px 字 ≈ 2px），
        // 只软化字形边缘；measureText 不受 maskFilter 影响，换行计算仍准确
        paint.maskFilter = BlurMaskFilter(maxOf(fontSize * 0.025f, 1f), BlurMaskFilter.Blur.NORMAL)

        // 最大宽度：画面区宽度 90%
        val maxWidth = canvasW * 0.9f

        // 获取字体度量
        val fm = paint.fontMetrics
        val fontHeight = kotlin.math.ceil(fm.bottom - fm.top).toInt()
        val lineHeight = kotlin.math.ceil(fontHeight * 1.3f).toInt() // 行高 = 字体高度 * 1.3

        // 将文本按宽度拆分成多行
        val lines = wrapText(text, paint, maxWidth)

        // 计算实际最大行宽（用于位图宽度和水平居中）
        var actualMaxLineWidth = 0f
        for (line in lines) {
            val lineW = paint.measureText(line)
            if (lineW > actualMaxLineWidth) actualMaxLineWidth = lineW
        }

        // 位图尺寸：基于实际文本宽度 + 少量内边距
        val paddingX = kotlin.math.ceil(fontSize * 0.5f).toInt()
        val paddingY = kotlin.math.ceil(fontSize * 0.3f).toInt()
        val bmpW = maxOf(kotlin.math.ceil(actualMaxLineWidth + paddingX * 2).toInt(), 100)
        val bmpH = maxOf(lines.size * lineHeight + paddingY * 2, 50)

        // 创建 Android Bitmap 用于绘制文本
        val androidBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val androidCanvas = AndroidCanvas(androidBmp)

        // ⛔ 水平：paint.align = CENTER，drawText 以 x 为中心展开 —— x 必须给
        //    位图中心 bmpW/2，不能给「左边缘」坐标，否则每行向左偏半个行宽
        //    （历史 bug：x=(bmpW-lineWidth)/2 导致整体偏左 + 长行左侧被裁）。
        // 垂直：整块文本（首行字形顶 → 末行字形底）在位图内精确居中。
        //    blockHeight = fontHeight + (n-1)*lineHeight（非 n*lineHeight，
        //    末行只有 descent、首行只有 ascent，用 n*lineHeight 会偏高）。
        val blockHeight = fontHeight + (lines.size - 1) * lineHeight
        val firstBaselineY = (bmpH - blockHeight) / 2f - fm.top

        for (i in lines.indices) {
            androidCanvas.drawText(lines[i], bmpW / 2f, firstBaselineY + i * lineHeight, paint)
        }

        // 转换为 ImageBitmap (使用扩展函数 asImageBitmap)
        lyricBitmap = androidBmp.asImageBitmap()
    }

    /**
     * C：暗角 vignette（径向渐变，四角压暗模拟 CRT 玻璃边缘）
     *
     * ⚠️ 必须按 **4:3 画面区**定界，不能按全屏对角线 —— 全屏半径下画面区四角
     * 只到半径 0.82 处，且屏幕四角整块落在 pillarbox 黑边之下，真机反馈
     * 「四个暗角看不出来」。现收敛到画面区：半径 = 画面区半对角线。
     * 梯度改为**中性起即极淡压暗、平滑到外沿**（原「0.45 半径内全透明」会在
     * 中间画出明显的亮圆边界 —— 真机反馈「像太阳」）；整体颜色相对平均，
     * 仅最外沿有色差。边缘 alpha 0.5，保证画面内黑字可读。
     */
    private fun DrawScope.drawVignette(w: Float, h: Float) {
        if (vignetteBrush == null || vignetteW != w) {
            val pictureW = minOf(w, h * ASPECT)
            vignetteBrush = Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.03f),
                0.5f to Color.Black.copy(alpha = 0.08f),
                0.8f to Color.Black.copy(alpha = 0.18f),
                1f to Color(VIGNETTE_EDGE_COLOR),
                center = Offset(w / 2f, h / 2f),
                radius = sqrt(pictureW * pictureW / 4f + h * h / 4f)
            )
            vignetteW = w
        }
        drawRect(vignetteBrush!!)
    }

    /**
     * C：圆角屏面遮罩（CRT 玻璃四角）
     * 路径 = 画面区矩形 − 圆角矩形（EvenOdd）→ 只填四角外沿；按尺寸缓存。
     * ⚠️ 按画面区定界（非全屏）：全屏圆角整块落在 pillarbox 黑边之下看不见
     * （与 vignette 同一真机反馈）。
     */
    private fun DrawScope.drawCornerMask(w: Float, h: Float) {
        if (cornerPath == null || cornerW != w || cornerH != h) {
            val pictureW = minOf(w, h * ASPECT)
            val left = (w - pictureW) / 2f
            val r = minOf(pictureW, h) * CORNER_RADIUS_FRACTION
            cornerPath = Path().apply {
                fillType = PathFillType.EvenOdd
                moveTo(left, 0f)
                lineTo(left + pictureW, 0f)
                lineTo(left + pictureW, h)
                lineTo(left, h)
                close()
                // T1.6.2（§四 G15）：android addRoundRect float 重载，零 RoundRect/Rect 分配
                // （与旧 RoundRect(Rect(...), r, r) 逐像素等价；EvenOdd 填充在 compose Path 上已设）
                asAndroidPath().addRoundRect(
                    left, 0f, left + pictureW, h, r, r, android.graphics.Path.Direction.CCW)
            }
            cornerW = w
            cornerH = h
        }
        drawPath(cornerPath!!, Color.Black)
    }

    /**
     * G：4:3 柱状黑边 → 胶片边缘（切孔条带，左右两侧完全一致）
     * 16:9 屏显示 4:3 画面 = 左右条带各 (w − h×4/3)/2（1920×1080 → 240px）。
     * 两条带**互为平移复制、无镜像**：片基同一纯色、方孔同形同距同居中、孔内透光与
     * 高光/落影方向完全相同 —— 唯一随侧别变化的是「贴图片区的框线」与「贴屏幕外缘的
     * 高光」（这是条带各自朝向决定的，非光向翻转）。切孔行**同相**（同 y）。
     * 几何全按条带宽比例换算 → 分辨率无关（1080p/4K/手机横屏同形）；不加逐像素颗粒，
     * 片基为纯色以守住 60fps 零分配。
     * 竖屏（手机）时 h×4/3 > w → 无条带，自动退化为满屏。
     *
     * **胶片向上滚动**：切孔行 + 片格分隔线共用同一个 [filmScrollPx]（同一段片基上印的，
     * 相位恒定），匀速上移；片基纯色、框线/内亮线、外缘高光是**屏幕空间**的，不参与偏移。
     */
    private fun DrawScope.drawPillarbox(w: Float, h: Float, dt: Float) {
        val side = (w - minOf(w, h * ASPECT)) / 2f
        if (side <= 1f) return
        ensureFilmGradients(w, side)

        // 走片相位：时间源与滚动暗带同源（ctx.nowMs → 每帧 dt），按 KDoc 约束 **dt 累加**
        // （⛔ 禁 nowMs × 系数：大时间基数下 float 精度丢失会让走片冻结/跳变），
        // 每帧对 pitch 取模 ⇒ 恒在 [0, pitch)，永不为负/NaN；帧内只用基本类型局部量，零分配。
        // 左右两条带共用同一 offset ⇒ 行恒同相、两侧互为平移复制。
        val pitch = side * FILM_PITCH_FRAC
        filmScrollPx = (filmScrollPx + FILM_SCROLL_SPEED * side * dt) % pitch

        val holeL = filmHoleL ?: return
        val holeR = filmHoleR ?: return
        val nc = drawContext.canvas.nativeCanvas
        drawFilmStrip(nc, 0f, side, h, left = true, hole = holeL, scrollPx = filmScrollPx)
        drawFilmStrip(nc, w - side, side, h, left = false, hole = holeR, scrollPx = filmScrollPx)
    }

    /**
     * 切孔透光渐变（仅按条带位置缓存两份）按 (w, side) 缓存 —— 只有转屏/分辨率变化才重建，
     * 其余帧直接复用；渐变为横向，纵向（孔行位置）不参与缓存键。
     * ⚠️ 两份渐变**颜色顺序完全相同**（亮边在孔的左侧），仅起点 x 随条带平移 ⇒ 两侧一致。
     * 片基为纯色（[FILM_BASE_FLAT]），不再需要渐变着色器。
     */
    private fun ensureFilmGradients(w: Float, side: Float) {
        if (filmHoleL != null && filmHoleR != null && filmCacheW == w && filmCacheSide == side) return
        filmCacheW = w
        filmCacheSide = side
        val outer = side * FILM_OUTER_FRAC
        val holeW = side * FILM_HOLE_W_FRAC

        // 切孔透光：两侧同方向（LIT→DIM 自左向右），只按各自条带原点平移
        filmHoleL = LinearGradient(
            outer, 0f, outer + holeW, 0f,
            intArrayOf(FILM_HOLE_LIT, FILM_HOLE_DIM), null, Shader.TileMode.CLAMP
        )
        val holeXr = w - side + outer // 右条带同一相对位置（x0 = w − side）
        filmHoleR = LinearGradient(
            holeXr, 0f, holeXr + holeW, 0f,
            intArrayOf(FILM_HOLE_LIT, FILM_HOLE_DIM), null, Shader.TileMode.CLAMP
        )
    }

    /**
     * 单侧胶片条带（drawPillarbox 调两次，两侧**完全一致的平移复制** —— 同一套几何常量、
     * 同一透光渐变方向、同一高光/落影符号，无任何镜像分支）。
     *
     * 横向分段（占条带宽）：外边距 35% → 方孔 30% → 内侧片基 35% = 100%（方孔居黑边正中）；
     * 框线 2.5% 叠在内侧边距上、外缘高光 1% 叠在外侧边距上，均不与切孔重叠。
     * 纵向：分隔线在 y = k·p − [scrollPx]，切孔中心 = (k+½)·p − [scrollPx]（两者同相，
     * 一起匀速**向上**滚动；左右条带传同一 [scrollPx] ⇒ 行同相）。
     * k 起点 = −1（p = 0.60·side、孔高 0.30·side ⇒ k = −2 的孔底 ≤ −1.5p + 0.15·side < 0
     * 恒不可见，k = −1 起已足够；上移后 [0, h] 两端仍被行覆盖，不留缺口）。
     * 唯一随 [left] 变化的是两处**屏幕空间定位**：框线贴图片区一侧、暖色高光贴屏幕外缘一侧
     * （朝向决定，非光向翻转）。绘制顺序：片基 → 分隔线 → 切孔（高光/落影/透光）→ 框线 → 外缘高光。
     * ⚠️ 片基纯色、框线/内亮线、外缘高光均**不加 [scrollPx]**（屏幕空间的静止件）。
     */
    private fun drawFilmStrip(
        nc: AndroidCanvas,
        x0: Float,
        side: Float,
        h: Float,
        left: Boolean,
        hole: LinearGradient,
        scrollPx: Float
    ) {
        val holeW = side * FILM_HOLE_W_FRAC
        val holeH = side * FILM_HOLE_H_FRAC
        val pitch = side * FILM_PITCH_FRAC
        val outer = side * FILM_OUTER_FRAC
        val rx = side * FILM_RX_FRAC
        val bevelX = side * FILM_BEVEL_X_FRAC
        val bevelY = side * FILM_BEVEL_Y_FRAC
        val x1 = x0 + side
        // 孔列：两侧同一相对位置（距各自条带左缘 35%）⇒ 平移复制，非镜像
        val holeX = x0 + outer
        // 高光偏移朝左 + 上；落影朝右 + 下 —— **两侧同一符号**（无 left 依赖）
        val dx = -bevelX

        // ① 片基：暖炭黑纯色（两侧同色，FILM_BASE_FLAT）
        filmPaint.color = FILM_BASE_FLAT
        nc.drawRect(x0, 0f, x1, h, filmPaint)

        // ② 片格分隔线（落在两孔正中）—— 与切孔同一 offset 上移：y = k·p − offset（同相）。
        //    k 从 −1 起（顶端上方一格，比原点高一格）⇒ 上移后 [0, h] 两端仍被行覆盖；
        //    scrollPx ∈ [0, p) ⇒ y 为有限负值（无 NaN/负余数），底端由 while 条件自然延到 h。
        val sepH = max(1f, side * FILM_SEP_FRAC)
        filmPaint.color = FILM_SEP_COLOR
        var y = -pitch - scrollPx
        while (y < h) {
            nc.drawRect(x0, y, x1, minOf(y + sepH, h), filmPaint)
            y += pitch
        }

        // ③ 切孔：左上高光边 → 右下落影 → 透光渐变（渐变件独立，不会被落影 alpha 污染）。
        //    三者方向两侧**完全相同**（dx = −bevelX 与 left 无关）⇒ 平移复制、无镜像。
        //    中心 cy = (k+½)·p − offset —— 与分隔线**同相**一起向上滚动（同一段片基上印的）；
        //    k 从 −1 起（顶端上方一格；p = 0.60·side、孔高 0.30·side ⇒ k = −2 恒不可见）
        //    保证上移后顶端仍有行，bottom ≤ 0 的整行直接跳过
        //    （纯原生裁剪思路，省掉每帧 3 次离屏 roundRect，零分配）。
        var cy = pitch * 0.5f - pitch - scrollPx
        while (cy - holeH * 0.5f < h) {
            val top = cy - holeH * 0.5f
            val bottom = cy + holeH * 0.5f
            if (bottom > 0f) {
                filmPaint.color = FILM_BEVEL_COLOR
                filmRect.set(holeX + dx, top - bevelY, holeX + holeW + dx, bottom - bevelY)
                nc.drawRoundRect(filmRect, rx, rx, filmPaint)
                filmPaint.color = FILM_SHADOW_COLOR
                filmRect.set(holeX - dx, top + bevelY, holeX + holeW - dx, bottom + bevelY)
                nc.drawRoundRect(filmRect, rx, rx, filmPaint)
                filmGradPaint.shader = hole
                filmRect.set(holeX, top, holeX + holeW, bottom)
                nc.drawRoundRect(filmRect, rx, rx, filmGradPaint)
                filmGradPaint.shader = null
            }
            cy += pitch
        }

        // ④ 框线（贴图片区一侧）：主色 + 内侧亮线（亮线宽不超过框线，防止越界入画）
        val frameW = side * FILM_FRAME_FRAC
        val subW = minOf(max(1f, side * FILM_SUBLINE_FRAC), frameW)
        filmPaint.color = FILM_FRAME_COLOR
        if (left) nc.drawRect(x1 - frameW, 0f, x1, h, filmPaint)
        else nc.drawRect(x0, 0f, x0 + frameW, h, filmPaint)
        filmPaint.color = FILM_FRAME_LINE_COLOR
        if (left) nc.drawRect(x1 - subW, 0f, x1, h, filmPaint)
        else nc.drawRect(x0, 0f, x0 + subW, h, filmPaint)

        // ⑤ 屏幕外缘暖色高光（#DBB98E @30%，贴各自条带的屏幕外缘一侧 —— 朝向定位，非光向翻转）
        val edgeW = max(1f, side * FILM_EDGE_FRAC)
        filmPaint.color = FILM_EDGE_COLOR
        if (left) nc.drawRect(x0, 0f, x0 + edgeW, h, filmPaint)
        else nc.drawRect(x1 - edgeW, 0f, x1, h, filmPaint)
    }

    /**
     * G：台标 OSD（"CH 3" 等，1s 亮 / 1s 灭；黑描边暖白填充，位图缓存）
     */
    private fun DrawScope.drawOsd(w: Float, h: Float, nowMs: Long) {
        ensureOsd(w, h)
        val bmp = osdBitmap ?: return
        if ((nowMs / OSD_BLINK_MS) % 2L != 0L) return // 灭的半秒不画
        val pictureW = minOf(w, h * ASPECT)
        val pictureLeft = (w - pictureW) / 2f
        drawImage(
            image = bmp,
            dstOffset = IntOffset(
                (pictureLeft + pictureW * 0.04f).roundToInt(),
                (h * 0.045f).roundToInt()
            )
        )
    }

    /** G：台标位图（首绘/尺寸变化才重建，其余帧直接复用） */
    private fun ensureOsd(w: Float, h: Float) {
        if (osdBitmap != null && osdValidW == w && osdValidH == h) return
        val textSize = h * OSD_TEXT_FRACTION
        val paint = AndroidPaint().apply {
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = AndroidPaint.Align.LEFT
            typeface = Typeface.MONOSPACE
            this.textSize = textSize
        }
        val channel = osdChannel ?: OSD_CHANNELS[0]
        val fm = paint.fontMetrics
        val padX = textSize * 0.5f
        val bw = (paint.measureText(channel) + padX * 2).toInt().coerceAtLeast(1)
        val bh = ((fm.bottom - fm.top) + textSize).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(bmp)
        val baseline = -fm.top + textSize * 0.5f
        // 白描边 + 墨黑填充（黄白底上给黑字一圈分离边）
        paint.style = AndroidPaint.Style.STROKE
        paint.strokeWidth = textSize * 0.12f
        paint.color = android.graphics.Color.WHITE
        c.drawText(channel, padX, baseline, paint)
        paint.style = AndroidPaint.Style.FILL
        paint.color = TEXT_COLOR
        c.drawText(channel, padX, baseline, paint)
        osdBitmap = bmp.asImageBitmap()
        osdValidW = w
        osdValidH = h
    }

    /**
     * 将文本按最大宽度拆分成多行
     */
    private fun wrapText(text: String, paint: AndroidPaint, maxWidth: Float): List<String> {
        val words = text.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return listOf(text)

        val lines = mutableListOf<String>()
        var currentLine = ""

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val testWidth = paint.measureText(testLine)

            if (testWidth <= maxWidth) {
                currentLine = testLine
            } else {
                // 当前行已满，换行
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                }
                // 如果单个词就超过宽度，强制按字符拆分
                if (paint.measureText(word) > maxWidth) {
                    lines.addAll(splitLongWord(word, paint, maxWidth))
                    currentLine = ""
                } else {
                    currentLine = word
                }
            }
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        return if (lines.isEmpty()) listOf(text) else lines
    }

    /**
     * 拆分过长的单个词（按字符强制拆分）
     */
    private fun splitLongWord(word: String, paint: AndroidPaint, maxWidth: Float): List<String> {
        val parts = mutableListOf<String>()
        var current = ""

        for (char in word) {
            val test = current + char
            if (paint.measureText(test) <= maxWidth) {
                current = test
            } else {
                if (current.isNotEmpty()) {
                    parts.add(current)
                }
                current = char.toString()
            }
        }

        if (current.isNotEmpty()) {
            parts.add(current)
        }

        return parts
    }
}

package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.cos
import kotlin.math.sin

// ═══════════════════════════════════════════════════════════════════
// E23 歌词点阵（两行滚动 · 全息粒子版 · 性能优化）
// ═══════════════════════════════════════════════════════════════════

/**
 * E23 `LYRICS_DOT_MATRIX` — 全息粒子歌词（两行滚动版）
 *
 * 动画流程：
 * 1. 初始：两行同时凝聚
 * 2. 第一行演唱（卡拉OK逐字点亮）
 * 3. 第一行唱完 → 从左到右逐字消散（稍慢）
 * 4. 第二行上移到第一行位置 + 新行从下方凝聚（从左到右逐字凝聚，稍慢）
 * 5. 循环
 */
class LyricsDotMatrixRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.LYRICS_DOT_MATRIX

    // ── 单行粒子数据（两行各一份） ──────────────────────────
    // 每粒子 8 float：x, y, vx, vy, tx, ty, size, phase
    private var line0: FloatArray = FloatArray(0)  // 上行（正在唱 / 正在消散）
    private var line1: FloatArray = FloatArray(0)  // 下行（待唱 / 正在上移）
    private var line0Count = 0
    private var line1Count = 0

    // 每个粒子的字索引（用于逐字消散/凝聚）
    private var line0CharIdx: IntArray = IntArray(0)
    private var line1CharIdx: IntArray = IntArray(0)
    // 行内归一化 x（0..1，用于卡拉OK点亮）
    private var line0LocalX: FloatArray = FloatArray(0)
    private var line1LocalX: FloatArray = FloatArray(0)

    // 行文字缓存
    private var line0Text: String = ""
    private var line1Text: String = ""

    // 6 条 Path：3 档亮度 × 2 层（批量绘制）
    private val paths = Array(6) { Path() }

    // ── 状态机 ──────────────────────────────────────────────
    private enum class Phase {
        INIT_COALESCE,      // 初始：两行同时凝聚
        STEADY,             // 稳定：第一行演唱中，第二行待机
        SCATTERING_TOP,     // 第一行逐字消散
        SHIFTING,           // 行上移 + 新行凝聚
    }
    private var phase = Phase.INIT_COALESCE
    private var phaseStartMs = 0L

    // 显示的行索引（第一行对应第几句歌词）
    private var displayedLineIndex = -1
    /** 已绑定的歌曲 id：renderer 实例跨歌曲复用，需靠它判断切歌 */
    private var boundSongId: String? = null
    /** 上一首歌的标题：songId 为 null 时作为兜底判据依据 */
    private var boundCaption: String? = null

    // 行偏移（用于行移动动画，0=正常位置，-1=上移一行）
    private var rowOffsetY = 0f  // 像素偏移量

    // 行高（像素）
    private var lineHeightPx = 0f
    private var fontSizePx = 0f
    // 字号缓存键：字体大小只依赖最长行(整曲常量)与画布尺寸，尺寸/歌词不变时整首歌只算一次
    private var cachedFontW = 0f
    private var cachedFontH = 0f
    private var cachedLongest: String? = null
    private var topY = 0f    // 上行 Y 中心
    private var bottomY = 0f // 下行 Y 中心

    // 动画时长
    private val initCoalesceMs = 1200L        // 初始两行凝聚（放慢，便于看清）
    private val scatterPerCharMs = 90L   // 每字消散延迟
    private val scatterFlyMs = 600L      // 单字飞散时长
    private val shiftMs = 700L           // 行上移动画时长
    private val coalescePerCharMs = 160L // 每字凝聚延迟（放慢）
    private val coalesceArriveMs = 1200L // 单字凝聚到达时长（放慢）

    // 即将进入的下下行文本（在 SHIFTING 阶段使用）
    private var incomingNextText: String = ""

    // 固定每行粒子数上限（无画质等级，直接取最高密度）
    private fun capacityFor(ctx: RenderContext): Int = 3600

    // ── 自适应字号 ──────────────────────────────────────────
    // 规则（用户需求）：
    //   1. 找全曲最长的一句歌词（取实际文本，而非仅字数，测量更准）
    //   2. 字号 = 让这一句占满屏幕宽度 80% 时的字号
    //   3. 约束：字体整体高度 ≤ 屏幕高度 40%（预留两行显示空间）
    //   4. 硬性区间 [MIN, MAX]：粒子数固定（3600/行），字号过大→粒子太稀→文字空洞
    private fun computeFontSize(ctx: RenderContext, w: Float, h: Float): Float {
        // 优先用整曲最长行；无歌词时回退到当前文本 / 标题
        val longest = ctx.longestLyricLine
            ?.takeIf { it.isNotBlank() }
            ?: ctx.currentLyricLine?.takeIf { it.isNotBlank() }
            ?: ctx.caption?.takeIf { it.isNotBlank() }

        val paint = AndroidPaint().apply {
            isAntiAlias = false
            textSize = REF_FONT // 用固定参考字号测量，得每 px 折合跨度
            typeface = Typeface.DEFAULT_BOLD
            isFakeBoldText = true
        }
        val fm = paint.fontMetrics
        val refLineHeight = fm.bottom - fm.top      // 参考字号下的行高
        val refGlyphAdvance = if (longest.isNullOrEmpty()) REF_FONT else {
            paint.measureText(longest).coerceAtLeast(REF_FONT * 0.1f)
        }

        // 宽度约束：占满屏宽 80%
        val fontFromWidth = REF_FONT * (w * 0.80f) / refGlyphAdvance
        // 高度约束：行高 ≤ 屏幕高 40%
        val fontFromHeight = REF_FONT * (h * 0.40f) / refLineHeight

        val base = minOf(fontFromWidth, fontFromHeight)
        // 硬性区间夹稳，防止极端长/短歌词 + 粒子稀释导致文字空洞
        // MIN_FONT_S/MAX_FONT_S 是屏高比例，须乘以 h 换算成真实字号
        return base.coerceIn(h * MIN_FONT_S, h * MAX_FONT_S)
    }

    override fun onEnter(ctx: RenderContext) {
        val cap = capacityFor(ctx)
        line0 = FloatArray(cap * STRIDE)
        line1 = FloatArray(cap * STRIDE)
        line0CharIdx = IntArray(cap)
        line1CharIdx = IntArray(cap)
        line0LocalX = FloatArray(cap)
        line1LocalX = FloatArray(cap)
        line0Count = 0
        line1Count = 0
        line0Text = ""
        line1Text = ""
        phase = Phase.INIT_COALESCE
        phaseStartMs = 0L
        displayedLineIndex = -1
        rowOffsetY = 0f
        boundSongId = null
        boundCaption = null
        // 切歌/重进：重置字号缓存，下一帧用新歌最长行重算一次
        cachedFontW = 0f
        cachedFontH = 0f
        cachedLongest = null
    }

    override fun onExit() {
        line0 = FloatArray(0)
        line1 = FloatArray(0)
        line0CharIdx = IntArray(0)
        line1CharIdx = IntArray(0)
        line0LocalX = FloatArray(0)
        line1LocalX = FloatArray(0)
        line0Count = 0
        line1Count = 0
    }

    // ── 文字采样 ────────────────────────────────────────────

    /**
     * 对单行文字进行采样，将结果写入目标数组。
     * @return 采样到的粒子数
     */
    private fun sampleLine(
        text: String,
        target: FloatArray,
        charIndices: IntArray,
        localXs: FloatArray,
        cap: Int,
        canvasW: Float,
        fontSize: Float,
        targetY: Float   // 行的目标 Y 位置（文字垂直中心）
    ): Int {
        if (text.isBlank()) return 0

        val paint = AndroidPaint().apply {
            isAntiAlias = false
            textSize = fontSize
            color = android.graphics.Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            isFakeBoldText = true
        }

        val maxLineWidth = canvasW * 0.88f
        val displayText = if (paint.measureText(text) <= maxLineWidth) {
            text
        } else {
            var trimmed = text
            while (trimmed.isNotEmpty() && paint.measureText(trimmed + "…") > maxLineWidth) {
                trimmed = trimmed.dropLast(1)
            }
            trimmed + "…"
        }

        val textWidth = paint.measureText(displayText)

        // 标准基线计算：让字形在 bitmap 内完整绘制
        // baseline 置于字形顶部处，则字形占据 [0, fontHeight] 区间，绝无截断
        val fm = paint.fontMetrics
        val fontHeight = fm.bottom - fm.top          // 正数（最高字形到最低字形）
        // baseline 放在 y = -fm.top 处，使字形顶部贴合 y=0
        val baselineY = -fm.top
        // bitmap 高度：精确等于字形总高度 + 少量上下留白
        val bmpH = (fontHeight + fontSize * 0.4f).toInt().coerceAtLeast(40)
        // 宽度留白
        val bmpW = (textWidth * 1.15f).toInt().coerceAtLeast(100)
        // T7 修复（2026-09-13）：try-finally 包裹 Bitmap 创建与回收，避免异常路径泄漏
        // 采样结果（n/minX/maxX/minY/maxY）与 bmp 声明在 try 外，
        // 供 finally 回收与 try 之后的坐标映射段访问
        var n = 0
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var bmp: Bitmap? = null
        try {
        val b = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        bmp = b
        val canvas = AndroidCanvas(b)

        val xOff = (bmpW - textWidth) / 2
        canvas.drawText(displayText, xOff, -fm.top, paint)

        // 自适应步长
        val estPixels = fontSize * textWidth * 0.20f
        val idealStep = kotlin.math.sqrt(estPixels / cap.toFloat())
            .coerceIn(1f, 3f).toInt().coerceAtLeast(1)
        val step = idealStep

        // 计算每个字符的 x 范围（用于字索引映射）
        val charXBounds = FloatArray(displayText.length + 1)
        charXBounds[0] = xOff
        for (i in displayText.indices) {
            charXBounds[i + 1] = xOff + paint.measureText(displayText.substring(0, i + 1))
        }

        // ── 采样 ──────────────────────────────────────────────
        // 关键修复：不能按行从顶部扫到底部时用 cap 截断（会丢失字形下半部）。
        // 改为两遍采样：
        //   第一遍：收集全部有效像素的坐标到临时数组；
        //   第二遍：按字形自然密度均匀抽取 cap 个，保证垂直方向完整覆盖。

        // 第一遍：统计有效像素数量（先估算容量上限）
        // 有效像素总数，按 step 网格统计
        var totalPixels = 0
        // 一次遍历统计个数
        // 先用较小的整型数组容不下全部，因此用两个 pass：先数数，再按比例抽取。
        // 为节省内存，先快速数总数并用均匀行距抽样。

        // 收集所有有效像素的行（y）坐标分布，用于按行配额
        // 记录每一行（按 step 步进）的有效像素计数
        val maxRows = (bmpH + step - 1) / step
        val rowPixelCount = IntArray(maxRows)
        for (y in 0 until bmpH step step) {
            var cnt = 0
            for (x in 0 until bmpW step step) {
                if (b.getPixel(x, y) and 0xFF000000.toInt() != 0) cnt++
            }
            rowPixelCount[y / step] = cnt
            totalPixels += cnt
            if (cnt > 0) {
                if (y < minY) minY = y.toFloat()
                if (y > maxY) maxY = y.toFloat()
            }
        }

        // 第二遍：按每行像素占比配额采样，垂直方向完整覆盖
        val quota = calculateRowQuota(rowPixelCount, totalPixels, cap)
        var curRowFill = 0
        for (ri in rowPixelCount.indices) {
            val y = ri * step
            if (rowPixelCount[ri] == 0) continue
            val targetCount = quota[ri]
            if (targetCount <= 0) continue
            var taken = 0
            var filled = 0
            val takeEvery = (rowPixelCount[ri].toFloat() / targetCount).coerceAtLeast(1f)
            for (x in 0 until bmpW step step) {
                if (curRowFill >= cap) break
                if (b.getPixel(x, y) and 0xFF000000.toInt() != 0) {
                    taken++
                    // 该行内等距抽取 targetCount 个
                    if (((taken - 1).toFloat() % takeEvery) < 0.5f) {
                        val o = n * STRIDE
                        target[o + TX] = x.toFloat()
                        target[o + TY] = y.toFloat()
                        target[o + SIZE] = 1.0f
                        target[o + PHASE] = VisualizerMath.nextRandom() * 6.2831853f

                        var ci = 0
                        while (ci < displayText.length && x >= charXBounds[ci + 1]) ci++
                        charIndices[n] = ci.coerceAtMost(displayText.length - 1)
                        localXs[n] = ((x - xOff) / textWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)

                        if (x < minX) minX = x.toFloat()
                        if (x > maxX) maxX = x.toFloat()

                        n++
                        curRowFill++
                        filled++
                        if (filled >= targetCount) break
                    }
                }
            }
            if (curRowFill >= cap) break
        }

        } finally {
            bmp?.recycle()
        }

        if (n == 0) return 0

        // 将粒子从 bitmap 坐标映射到画布坐标
        // 水平居中
        val xCenter = (minX + maxX) / 2f
        // 垂直居中到 targetY
        val yCenter = (minY + maxY) / 2f

        for (i in 0 until n) {
            val o = i * STRIDE
            val bmpX = target[o + TX]
            val bmpY = target[o + TY]

            // 映射：bitmap 中心 → canvas 中心（targetY）
            val canvasX = canvasW / 2f + (bmpX - xCenter)
            val canvasY = targetY + (bmpY - yCenter)

            target[o + TX] = canvasX
            target[o + TY] = canvasY
        }

        return n
    }

    /** 为一行粒子初始化凝聚起始位置（从四周飞来） */
    private fun initCoalesce(arr: FloatArray, count: Int, w: Float, h: Float, fromBelow: Boolean = false) {
        for (i in 0 until count) {
            val o = i * STRIDE
            val tx = arr[o + TX]
            val ty = arr[o + TY]

            val angle: Float
            val dist: Float
            if (fromBelow) {
                // 从下方飞入
                angle = -3.14159f / 2f + VisualizerMath.nextRandomSigned() * 0.8f
                dist = h * 0.4f + VisualizerMath.nextRandom() * h * 0.2f
            } else {
                // 从四周飞入
                angle = VisualizerMath.nextRandom() * 6.2831853f
                dist = (w * 0.35f + h * 0.2f) * (0.5f + VisualizerMath.nextRandom() * 0.5f)
            }

            val startX = tx + cos(angle) * dist
            val startY = ty + sin(angle) * dist

            arr[o + X] = startX
            arr[o + Y] = startY

            val dx = tx - startX
            val dy = ty - startY
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
            val sp = 4f + VisualizerMath.nextRandom() * 4f
            val perpX = -dy / len * 2f
            val perpY = dx / len * 2f
            arr[o + VX] = dx / len * sp + perpX
            arr[o + VY] = dy / len * sp + perpY
        }
    }

    // ── 主绘制 ──────────────────────────────────────────────

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        val cap = line0.size / STRIDE

        // ── 自适应字体大小（缓存）─────────────────────────────
        // 字号只依赖最长行(整曲常量)与画布尺寸，尺寸/歌词不变时整首歌只算一次，
        // 不再每帧 new Paint + measureText（用户需求：字体大小整首歌不变，无需重复计算）
        val longestKey = ctx.longestLyricLine?.takeIf { it.isNotBlank() }
            ?: ctx.currentLyricLine?.takeIf { it.isNotBlank() }
            ?: ctx.caption?.takeIf { it.isNotBlank() }
        if (w != cachedFontW || h != cachedFontH || longestKey != cachedLongest) {
            fontSizePx = computeFontSize(ctx, w, h)
            lineHeightPx = fontSizePx * 1.4f
            cachedFontW = w
            cachedFontH = h
            cachedLongest = longestKey
        }

        val lyricLine = ctx.currentLyricLine?.takeIf { it.isNotBlank() }
        val nextLine = ctx.nextLyricLine?.takeIf { it.isNotBlank() }
        val text = lyricLine ?: (ctx.caption?.takeIf { it.isNotBlank() } ?: "NASMusicTV")
        val nextText = nextLine ?: ""
        val hasLyrics = lyricLine != null
        val currentIdx = ctx.lyricLineIndex

        // 两行的 Y 中心位置：对称分布在垂直中心两侧，避开 0.5h 的干扰
        topY = h * 0.40f
        bottomY = h * 0.60f

        // ── 状态切换逻辑 ────────────────────────────────────
        // 切歌重置：renderer 实例由 swapper 跨歌曲复用（不会重新 onEnter），
        // 若不在歌曲变化时回到初始态，displayedLineIndex 会停在上首歌的行号，
        // 新歌 idx 从 0 开始 -> diff 为负 -> 永不触发行切换，画面卡在旧歌词。
        // songId 为 null 时（本地扫描歌曲可能没有 id）用标题兜底，
        // 否则两首歌 id 均为 null 时不会触发重置。
        if (ctx.songId != boundSongId || ctx.caption != boundCaption) {
            boundSongId = ctx.songId
            boundCaption = ctx.caption
            displayedLineIndex = -1
            phase = Phase.INIT_COALESCE
            phaseStartMs = frame.timeMs
            rowOffsetY = 0f
            line0Text = ""
            line1Text = ""
        }

        if (displayedLineIndex < 0 && phase == Phase.INIT_COALESCE) {
            // 首次初始化
            if (lyricLine != null && currentIdx >= 0) {
                displayedLineIndex = currentIdx
                line0Text = text
                line1Text = nextText
                line0Count = sampleLine(text, line0, line0CharIdx, line0LocalX, cap, w, fontSizePx, topY)
                line1Count = sampleLine(nextText, line1, line1CharIdx, line1LocalX, cap, w, fontSizePx, bottomY)
                initCoalesce(line0, line0Count, w, h)
                initCoalesce(line1, line1Count, w, h)
                phaseStartMs = frame.timeMs
            } else {
                // 无歌词时显示标题作为占位
                displayedLineIndex = 0
                line0Text = text
                line1Text = ""
                line0Count = sampleLine(text, line0, line0CharIdx, line0LocalX, cap, w, fontSizePx, topY)
                line1Count = 0
                initCoalesce(line0, line0Count, w, h)
                phaseStartMs = frame.timeMs
            }
        }

        // 检测行切换（当实际播放行前进了 1 行或更多）
        if (hasLyrics && currentIdx >= 0 && displayedLineIndex >= 0) {
            val diff = currentIdx - displayedLineIndex
            if (diff >= 1 && phase == Phase.STEADY) {
                // 保存下下行文本（此时 ctx.nextLyricLine 是新当前行的下一行）
                incomingNextText = nextText
                // 开始消散第一行
                phase = Phase.SCATTERING_TOP
                phaseStartMs = frame.timeMs
            }
        }

        // ── 推进状态机 ──────────────────────────────────────
        val elapsed = frame.timeMs - phaseStartMs
        // 行偏移（SHIFTING 阶段控制 line0 上移）
        var shiftProgress = 0f

        when (phase) {
            Phase.INIT_COALESCE -> {
                if (elapsed >= initCoalesceMs) {
                    phase = Phase.STEADY
                    phaseStartMs = frame.timeMs
                }
            }
            Phase.STEADY -> {
                rowOffsetY = 0f
            }
            Phase.SCATTERING_TOP -> {
                // 计算消散总时长
                val maxCharIdx = line0Text.length.coerceAtLeast(1) - 1
                val totalScatterMs = maxCharIdx * scatterPerCharMs + scatterFlyMs
                if (elapsed >= totalScatterMs) {
                    // 消散完成，准备 SHIFTING
                    // 1. 将原来的 line1（第二行）"提升"为新的 line0（第一行）
                    promoteLine1ToLine0()
                    // 2. 采样新行到 line1（从下方进入）
                    line1Text = incomingNextText
                    line1Count = sampleLine(incomingNextText, line1, line1CharIdx, line1LocalX,
                        cap, w, fontSizePx, bottomY)
                    if (line1Count > 0) {
                        initCoalesce(line1, line1Count, w, h, fromBelow = true)
                    }
                    displayedLineIndex++
                    phase = Phase.SHIFTING
                    phaseStartMs = frame.timeMs
                }
            }
            Phase.SHIFTING -> {
                // 行上移进度：0 → 1
                shiftProgress = (elapsed.toFloat() / shiftMs).coerceIn(0f, 1f)
                // line0 从 bottomY 上移到 topY（偏移从 0 到 -lineHeightPx）
                rowOffsetY = -lineHeightPx * easeOutCubic(shiftProgress)

                if (elapsed >= shiftMs) {
                    // 动画结束，实际调整粒子 Y 坐标
                    applyLine0Shift()
                    rowOffsetY = 0f
                    phase = Phase.STEADY
                    phaseStartMs = frame.timeMs
                }
            }
        }

        // ── 更新粒子 ────────────────────────────────────────
        updateLine0(frame, elapsed, w, h)
        updateLine1(frame, elapsed, w, h)

        // ── 批量绘制 ────────────────────────────────────────
        val lineProgress = if (hasLyrics) ctx.lyricLineProgress.coerceIn(0f, 1f) else 1f
        val kProgress = karaokePacing(lineProgress)

        val accent = ctx.palette.accent
        val baseHue = VisualizerMath.rgbToHsl(accent).first
        val activeHue = if (baseHue in 0f..60f || baseHue in 300f..360f) 300f else 195f

        val bassPulse = 1f + frame.bass * 0.05f
        val t = frame.timeMs * 0.004f

        for (p in paths) p.reset()

        // 绘制上行
        if (line0Count > 0) {
            val line0Singing = phase != Phase.SCATTERING_TOP
            addLineToPaths(
                arr = line0,
                count = line0Count,
                charIndices = line0CharIdx,
                localXs = line0LocalX,
                yOffset = rowOffsetY,
                isSinging = line0Singing,
                isScattering = phase == Phase.SCATTERING_TOP,
                scatterElapsedMs = if (phase == Phase.SCATTERING_TOP) elapsed else 0L,
                textLen = line0Text.length,
                kProgress = kProgress,
                hasLyrics = hasLyrics,
                bassPulse = bassPulse,
                globalT = t,
                frame = frame,
                activeHue = activeHue
            )
        }

        // 绘制下行
        if (line1Count > 0) {
            val line1Singing = false // 下行永远不处于"正在唱"状态
            val line1Coalescing = phase == Phase.SHIFTING
            val line1CoalesceElapsed = if (line1Coalescing) elapsed else 0L

            addLineToPaths(
                arr = line1,
                count = line1Count,
                charIndices = line1CharIdx,
                localXs = line1LocalX,
                yOffset = rowOffsetY,
                isSinging = line1Singing,
                isScattering = false,
                scatterElapsedMs = 0L,
                textLen = line1Text.length,
                kProgress = 0f,
                hasLyrics = hasLyrics && line1Text.isNotBlank(),
                bassPulse = bassPulse,
                globalT = t,
                frame = frame,
                activeHue = activeHue,
                isCoalescing = line1Coalescing,
                coalesceElapsedMs = line1CoalesceElapsed
            )
        }

        // 实际绘制 Paths（暗 → 中 → 亮，Plus 混合）
        // 暗档
        drawPath(paths[1], VisualizerMath.hsl(200f, 0.3f, 0.7f),
            alpha = 0.10f, blendMode = BlendMode.Plus)
        drawPath(paths[0], VisualizerMath.hsl(200f, 0.25f, 0.65f),
            alpha = 0.45f, blendMode = BlendMode.Plus)

        // 中档
        val midHue = VisualizerMath.hueGradient(200f, activeHue, 0.6f)
        drawPath(paths[3], VisualizerMath.hsl(midHue, 0.75f, 0.7f),
            alpha = 0.22f, blendMode = BlendMode.Plus)
        drawPath(paths[2], VisualizerMath.hsl(midHue, 0.85f, 0.65f),
            alpha = 0.60f, blendMode = BlendMode.Plus)

        // 亮档
        drawPath(paths[5], VisualizerMath.hsl(activeHue, 1.0f, 0.75f),
            alpha = 0.18f + frame.pulse * 0.1f, blendMode = BlendMode.Plus)
        drawPath(paths[4], VisualizerMath.hsl(activeHue, 1.0f, 0.7f),
            alpha = 0.80f + frame.pulse * 0.18f, blendMode = BlendMode.Plus)
    }

    // ── 将一行粒子添加到 Path 中 ────────────────────────────

    private fun addLineToPaths(
        arr: FloatArray,
        count: Int,
        charIndices: IntArray,
        localXs: FloatArray,
        yOffset: Float,
        isSinging: Boolean,
        isScattering: Boolean,
        scatterElapsedMs: Long,
        textLen: Int,
        kProgress: Float,
        hasLyrics: Boolean,
        bassPulse: Float,
        globalT: Float,
        frame: AudioFrame,
        activeHue: Float,
        isCoalescing: Boolean = false,
        coalesceElapsedMs: Long = 0L
    ) {
        for (i in 0 until count) {
            val o = i * STRIDE
            val ci = charIndices[i]
            val localX = localXs[i]
            val baseSize = arr[o + SIZE]
            val phaseVal = arr[o + PHASE]

            // 计算该字的可见度（消散/凝聚）
            val visibility = when {
                isScattering -> {
                    val charDelay = ci * scatterPerCharMs
                    val charElapsed = scatterElapsedMs - charDelay
                    if (charElapsed <= 0) 1f
                    else (1f - charElapsed.toFloat() / scatterFlyMs).coerceIn(0f, 1f)
                }
                isCoalescing -> {
                    val charDelay = ci * coalescePerCharMs
                    val charElapsed = coalesceElapsedMs - charDelay
                    if (charElapsed <= 0) 0f
                    else (charElapsed.toFloat() / coalesceArriveMs).coerceIn(0f, 1f)
                }
                else -> 1f
            }

            if (visibility <= 0.01f) continue

            // 卡拉OK亮度（仅正在唱的行）
            val brightness = when {
                !hasLyrics -> 0.9f
                !isSinging -> 0.25f + frame.energy * 0.08f  // 待唱行：暗
                else -> {
                    val dist = localX - kProgress
                    when {
                        dist < -0.02f -> 0.78f
                        dist <= 0.02f -> 1.0f
                        else -> 0.18f + frame.energy * 0.07f
                    }
                }
            }

            val tier = when {
                brightness * visibility > 0.88f -> 2
                brightness * visibility > 0.45f -> 1
                else -> 0
            }

            // 整体呼吸：所有粒子用同一全局相位同步缩放（避免各粒子独立相位造成的“抖动感”）
            val breath = 1f + sin(globalT * 2.0f) * 0.10f * (0.25f + brightness * 0.75f)
            val size = baseSize * breath * breath * bassPulse * (0.85f + brightness * 0.4f) * visibility

            val px = arr[o + X]
            // 节奏性律动：所有粒子同步以低音（bass）驱动上下起伏，
            // 叠加一个依赖粒子横向位置的固定波相位，形成整行的规整波浪，
            // 鼓点（bass 峰值）到来时浮动幅度增大，节奏感强、不发散。
            val bassCue = frame.bass.coerceIn(0f, 1f)
            val amp = 2.2f * (0.35f + bassCue * 2.2f)
            val floatY = sin(globalT * 2.0f + localX * 10f) * amp
            val py = arr[o + Y] + yOffset + floatY

            if (size < 0.3f) continue

            val coreIdx = tier * 2
            paths[coreIdx].addOval(Rect(Offset(px - size, py - size), size * 2f))

            if (tier >= 1) {
                val glowIdx = tier * 2 + 1
                val glowR = size * (if (tier == 2) 2.2f else 1.4f)
                paths[glowIdx].addOval(Rect(Offset(px - glowR, py - glowR), glowR * 2f))
            }
        }
    }

    // ── 粒子物理更新 ────────────────────────────────────────

    private fun updateLine0(frame: AudioFrame, elapsedMs: Long, w: Float, h: Float) {
        val d = line0
        val n = line0Count
        if (n == 0) return

        when (phase) {
            Phase.INIT_COALESCE -> {
                val progress = (elapsedMs.toFloat() / initCoalesceMs).coerceIn(0f, 1f)
                updateCoalescing(d, n, progress)
            }
            Phase.STEADY, Phase.SHIFTING -> {
                // STEADY 和 SHIFTING 阶段，line0 都是稳定/演唱状态
                //（SHIFTING 时 line0 是新提升上来的行，已经在目标位置附近了）
                updateHolding(d, n, frame, w, h)
            }
            Phase.SCATTERING_TOP -> {
                // 逐字飞散
                for (i in 0 until n) {
                    val o = i * STRIDE
                    val ci = line0CharIdx[i]
                    val charDelay = ci * scatterPerCharMs
                    val charElapsed = elapsedMs - charDelay
                    if (charElapsed <= 0) {
                        // 还没到这个字，保持稳定
                        updateHoldingSingle(d, o, frame, w, h)
                    } else {
                        // 开始飞散
                        val p = (charElapsed.toFloat() / scatterFlyMs).coerceIn(0f, 1f)
                        d[o + VX] += VisualizerMath.nextRandomSigned() * 0.5f
                        d[o + VY] += -0.15f + VisualizerMath.nextRandomSigned() * 0.3f
                        d[o + VX] *= 0.98f
                        d[o + VY] *= 0.985f
                        d[o + X] += d[o + VX] * (1f + p * 0.6f)
                        d[o + Y] += d[o + VY] * (1f + p * 0.6f)
                    }
                }
            }
        }
    }

    private fun updateLine1(frame: AudioFrame, elapsedMs: Long, w: Float, h: Float) {
        val d = line1
        val n = line1Count
        if (n == 0) return

        when (phase) {
            Phase.INIT_COALESCE -> {
                val progress = (elapsedMs.toFloat() / initCoalesceMs).coerceIn(0f, 1f)
                updateCoalescing(d, n, progress)
            }
            Phase.STEADY, Phase.SCATTERING_TOP -> {
                updateHolding(d, n, frame, w, h)
            }
            Phase.SHIFTING -> {
                // 逐字凝聚（从左到右）
                for (i in 0 until n) {
                    val o = i * STRIDE
                    val ci = line1CharIdx[i]
                    val charDelay = ci * coalescePerCharMs
                    val charElapsed = elapsedMs - charDelay
                    if (charElapsed <= 0) {
                        // 还没到这个字，保持在起始位置不动
                        // （已经在 initCoalesce 中设置了起始位置）
                    } else {
                        // 开始凝聚
                        val p = (charElapsed.toFloat() / coalesceArriveMs).coerceIn(0f, 1f)
                        val accel = 0.01f + p * 0.05f
                        val drag = 0.82f + p * 0.08f
                        d[o + VX] += (d[o + TX] - d[o + X]) * accel + VisualizerMath.nextRandomSigned() * 0.3f
                        d[o + VY] += (d[o + TY] - d[o + Y]) * accel + VisualizerMath.nextRandomSigned() * 0.3f
                        d[o + VX] *= drag
                        d[o + VY] *= drag
                        d[o + X] += d[o + VX]
                        d[o + Y] += d[o + VY]
                    }
                }
            }
        }
    }

    private fun updateCoalescing(d: FloatArray, n: Int, progress: Float) {
        val accel = 0.012f + progress * 0.06f
        val drag = 0.80f + progress * 0.10f
        for (i in 0 until n) {
            val o = i * STRIDE
            d[o + VX] += (d[o + TX] - d[o + X]) * accel + VisualizerMath.nextRandomSigned() * 0.4f
            d[o + VY] += (d[o + TY] - d[o + Y]) * accel + VisualizerMath.nextRandomSigned() * 0.4f
            d[o + VX] *= drag
            d[o + VY] *= drag
            d[o + X] += d[o + VX]
            d[o + Y] += d[o + VY]
        }
    }

    private fun updateHolding(d: FloatArray, n: Int, frame: AudioFrame, w: Float, h: Float) {
        // 稳定后粒子不再抖动，精确吸附到目标位置
        // 呼吸感由绘制时的 breath 计算保留
        // 注意：不做任何低音推挤，否则会破坏两行的垂直位置（文字会飘离目标行）
        for (i in 0 until n) {
            val o = i * STRIDE
            // 直接让粒子贴紧目标位置（强力吸附，无抖动）
            d[o + X] += (d[o + TX] - d[o + X]) * 0.25f
            d[o + Y] += (d[o + TY] - d[o + Y]) * 0.25f
            d[o + VX] *= 0.5f
            d[o + VY] *= 0.5f
        }
    }

    private fun updateHoldingSingle(d: FloatArray, o: Int, frame: AudioFrame, w: Float, h: Float) {
        // 稳定后粒子不再抖动
        d[o + X] += (d[o + TX] - d[o + X]) * 0.25f
        d[o + Y] += (d[o + TY] - d[o + Y]) * 0.25f
        d[o + VX] *= 0.5f
        d[o + VY] *= 0.5f
    }

    // ── 辅助函数 ────────────────────────────────────────────

    /**
     * 按每行有效像素占比，把 cap 个粒子配额分配给各行。
     * 确保字形密集的上/中部和下/底部都按实际占比得到粒子，垂直方向完整覆盖。
     */
    private fun calculateRowQuota(rowPixelCount: IntArray, totalPixels: Int, cap: Int): IntArray {
        val quota = IntArray(rowPixelCount.size)
        if (totalPixels <= 0 || cap <= 0) return quota
        for (ri in rowPixelCount.indices) {
            val cnt = rowPixelCount[ri]
            if (cnt <= 0) continue
            quota[ri] = (cnt.toLong() * cap / totalPixels).toInt().coerceAtLeast(1)
        }
        return quota
    }

    /**
     * 将 line1 提升为 line0（行切换时使用）。
     * 复制所有粒子数据，TY 保持在下行位置（SHIFTING 阶段通过 rowOffsetY 做上移动画）。
     */
    private fun promoteLine1ToLine0() {
        // 复制粒子数组
        System.arraycopy(line1, 0, line0, 0, line1Count * STRIDE)
        // 复制字索引
        System.arraycopy(line1CharIdx, 0, line0CharIdx, 0, line1Count)
        // 复制 localX
        System.arraycopy(line1LocalX, 0, line0LocalX, 0, line1Count)
        // 复制计数和文字
        line0Count = line1Count
        line0Text = line1Text
    }

    /**
     * SHIFTING 结束后调用：将 line0 的粒子 Y 坐标实际上移一行，
     * 这样 rowOffsetY 回到 0 时粒子正好在上行位置。
     */
    private fun applyLine0Shift() {
        for (i in 0 until line0Count) {
            val o = i * STRIDE
            line0[o + TY] -= lineHeightPx
            line0[o + Y] -= lineHeightPx
        }
    }

    private fun karaokePacing(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return 1f - (1f - u) * (1f - u) * (1f - u)
    }

    private fun easeOutCubic(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return 1f - (1f - u) * (1f - u) * (1f - u)
    }

    companion object {
        private const val X = 0
        private const val Y = 1
        private const val VX = 2
        private const val VY = 3
        private const val TX = 4
        private const val TY = 5
        private const val SIZE = 6
        private const val PHASE = 7
        private const val STRIDE = 8
        /** 字号测量参考值：在参考字号下测字宽/行高，再换算成真实字号 */
        private const val REF_FONT = 100f
        /** 字号下限（屏高比例）：太小时字形笔画挤作一团难辨 */
        private const val MIN_FONT_S = 0.05f
        /** 字号上限（屏高比例）：超过则粒子太稀、文字空洞（3600 粒子/行固定） */
        private const val MAX_FONT_S = 0.16f
    }
}

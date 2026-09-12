package com.nasmusic.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.LyricsLine
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.CoverPalette
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.RendererSwapper
import com.nasmusic.tv.visualizer.VisualizerRenderer
import com.nasmusic.tv.visualizer.VisualizerRendererFactory

/**
 * 全屏可视化舞台（三层结构）。
 *
 * ```
 * ③ 前景层：顶部歌词 / 左下歌曲信息 / 底部指示器+控制栏
 * ② 效果层：Canvas —— VisualizerRenderer.draw(frame)
 * ① 背景层：纯暗色底（不叠加封面图，突出频谱本身）
 * ```
 *
 * 绘制循环用 `withFrameNanos` 驱动，**不走 Compose 重组**——
 * 频谱以 30fps 变化，若每帧重组会白白消耗性能。
 */
@Composable
fun VisualizerStage(
    song: Song?,
    frame: AudioFrame,
    cover: ImageBitmap?,
    palette: CoverPalette,
    lyrics: List<LyricsLine>?,
    progressMs: Long,
    theme: VisualizerTheme,
    quality: VisualQuality,
    isTV: Boolean,
    onExit: () -> Unit,
    onNextTheme: () -> Unit,
    onPrevTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val safeArea = 0.05f

    // TV 焦点：进入舞台立即夺焦。VisualizerOverlay 直接覆盖在 UI 树上，
    // 若不夺焦，D-Pad 事件仍路由到下方被遮挡界面的焦点节点，onPreviewKeyEvent 收不到，
    // 表现为"遥控器左右键无法切换效果"（真机实测）。
    val focusRequester = remember { FocusRequester() }

    // ── 渲染器生命周期（自动导演档 600ms 交叉淡入）────────────
    val renderCtx = remember { RenderContext() }
    val swapper = remember { RendererSwapper() }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // 淡入透明度：由绘制循环逐帧写入，在绘制阶段读取 → 只重绘不重组
    val fadeAlpha = remember { mutableFloatStateOf(1f) }
    val prevAlpha = remember { mutableFloatStateOf(0f) }
    // 渲染器 draw 异常一次性告警标记（避免逐帧刷日志）
    val rendererFailed = remember { mutableStateOf(false) }
    // 正在淡出的旧渲染器（null = 无旧层）；用 State 以便出现/消失时重组
    val prevRenderer = remember { mutableStateOf<VisualizerRenderer?>(null) }

    // 歌词级常量（最长行字数 / 最长行文本）：只随 lyrics 变化。
    // 每帧重算是 O(N) 全量扫描，必须缓存。
    val lyricMetrics = remember(lyrics) { computeLyricMetrics(lyrics) }

    // 主题 / 画质变化 → 同步渲染器（自动导演走淡入，手动切换硬切）
    LaunchedEffect(theme, quality) {
        val lyricInfo = computeLyricInfo(lyrics, progressMs, lyricMetrics)
        renderCtx.update(quality, palette, cover, Size.Zero, 0f,
            System.currentTimeMillis(), song?.title,
            lyricInfo.line, lyricInfo.nextLine, lyricInfo.progress,
            lyricInfo.hasWords, lyricInfo.lineIndex, lyricInfo.wordStartTimes,
            lyricInfo.maxLineChars, lyricInfo.longestLine, song?.id)
        // crossfade 能力保留在 RendererSwapper（有单测覆盖）；自动导演档删除后
        // UI 层已无使用场景，因此恒为 false（原为死参数，现收敛到调用处）
        if (swapper.sync(theme, quality, false, renderCtx, System.currentTimeMillis())) {
            prevRenderer.value = swapper.previous
            if (swapper.isCrossfading) {
                fadeAlpha.floatValue = 0f
                prevAlpha.floatValue = 1f
            } else {
                fadeAlpha.floatValue = 1f
                prevAlpha.floatValue = 0f
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { swapper.release() }
    }

    // ── 绘制循环（不触发重组）──────────────────────────────────
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { ns ->
                tick = ns
                if (swapper.isCrossfading) {
                    val ms = ns / 1_000_000L
                    swapper.advance(ms)
                    fadeAlpha.floatValue = swapper.currentAlpha(ms)
                    prevAlpha.floatValue = swapper.previousAlpha(ms)
                    prevRenderer.value = swapper.previous
                }
            }
        }
    }

    // ── 效果名 Toast ───────────────────────────────────────────
    var toastVisible by remember { mutableStateOf(true) }
    LaunchedEffect(theme) {
        toastVisible = true
        kotlinx.coroutines.delay(2500)
        toastVisible = false
    }

    // 进入舞台立即把焦点交给舞台 Box（TV 遥控器 D-Pad 依赖焦点路由）
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    when (e.key) {
                        Key.DirectionLeft -> { onPrevTheme(); true }
                        Key.DirectionRight -> { onNextTheme(); true }
                        // 上键：无动作（控制栏已移除）
                        Key.DirectionUp -> true
                        else -> false
                    }
                } else false
            }
            .pointerInput(Unit) {
                // 手机：左右滑动切换（阈值 80dp）
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { _, amount -> totalDrag += amount },
                    onDragEnd = {
                        val threshold = with(density) { 80.dp.toPx() }
                        when {
                            totalDrag > threshold -> onPrevTheme()
                            totalDrag < -threshold -> onNextTheme()
                        }
                    }
                )
            }
    ) {
        // ① 背景层：纯暗色底（不再叠加封面图，突出频谱效果本身；暗底衬托霓虹荧光）
        // 每帧只算一次：新层与渐出旧层共用。
        // 此前两个 Canvas 各算一次（且内部含全量扫描），直接翻倍开销。
        val lyricInfo = computeLyricInfo(lyrics, progressMs, lyricMetrics)

        Box(
            Modifier
                .fillMaxSize()
                .background(palette.background.copy(alpha = 0.72f))
        )

        // ② 效果层（新效果；交叉淡入时从透明渐显）
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                // T9：叠加发光需要离屏层，否则部分 API 版本退化为 SrcOver
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = fadeAlpha.floatValue
                }
        ) {
            canvasSize = Size(size.width, size.height)
            // 计算当前歌词行 & 行内进度（给 E23 歌词点阵用）
            // 复用实例，零分配
            renderCtx.update(quality, palette, cover, canvasSize,
                minOf(size.width, size.height) * safeArea, frame.timeMs, song?.title,
                lyricInfo.line, lyricInfo.nextLine, lyricInfo.progress,
                lyricInfo.hasWords, lyricInfo.lineIndex, lyricInfo.wordStartTimes,
                lyricInfo.maxLineChars, lyricInfo.longestLine, song?.id)
            val cur = swapper.current
            // tick 参与读取以确保每帧重绘
            if (tick >= 0L && cur != null && fadeAlpha.floatValue > ALPHA_EPS) {
                // 渲染器绘制异常若直接抛出会中断整个绘制线程 → 电视上可能表现为
                // native 崩溃（Skia 收到非法几何/状态）。捕获后跳过该帧并告警。
                try {
                    with(cur) { draw(frame, renderCtx) }
                } catch (t: Throwable) {
                    if (!rendererFailed.value) {
                        rendererFailed.value = true
                        android.util.Log.w("VisualizerStage",
                            "renderer draw failed, skipped frame: ${cur::class.simpleName}", t)
                    }
                }
            }
        }

        // ② 效果层（旧效果；仅交叉淡入期间存在，1→0 淡出）
        prevRenderer.value?.let { old ->
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = prevAlpha.floatValue
                    }
            ) {
                renderCtx.update(quality, palette, cover, Size(size.width, size.height),
                    minOf(size.width, size.height) * safeArea, frame.timeMs, song?.title,
                    lyricInfo.line, lyricInfo.nextLine, lyricInfo.progress,
                    lyricInfo.hasWords, lyricInfo.lineIndex, lyricInfo.wordStartTimes,
                    lyricInfo.maxLineChars, lyricInfo.longestLine, song?.id)
                if (tick >= 0L && prevAlpha.floatValue > ALPHA_EPS) {
                    try {
                        with(old) { draw(frame, renderCtx) }
                    } catch (t: Throwable) {
                        if (!rendererFailed.value) {
                            rendererFailed.value = true
                            android.util.Log.w("VisualizerStage",
                                "prev renderer draw failed, skipped frame: ${old::class.simpleName}", t)
                        }
                    }
                }
            }
        }

        // ③ 前景层
        Column(Modifier.fillMaxSize()) {
            // 顶部歌词行
            LyricTopBar(
                lyrics = lyrics,
                progressMs = progressMs,
                fallback = song?.let { "${it.title} — ${it.artist ?: ""}" },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp, start = 48.dp, end = 48.dp)
            )

            Spacer(Modifier.weight(1f))

            // 效果名 Toast
            AnimatedVisibility(
                visible = toastVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = theme.displayName,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 20.sp,
                    modifier = Modifier
                        // drawBehind 画圆角矩形：避免 RoundedCornerShape clip 触发
                        // Android 5.1 hwui Region 段错误（见 ThemeIndicator 注释）
                        .drawBehind {
                            drawRoundRect(
                                color = Color.Black.copy(alpha = 0.35f),
                                cornerRadius = CornerRadius(12.dp.toPx())
                            )
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            // 左下：歌曲信息
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, bottom = 8.dp)
            ) {
                Text(
                    text = song?.title ?: "",
                    color = Color.White,
                    fontSize = 24.sp,
                    maxLines = 1
                )
                Text(
                    text = song?.artist ?: "",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 16.sp,
                    maxLines = 1
                )
            }

            // 底部：指示器（控制栏已移除）
            ThemeIndicator(
                themes = VisualizerTheme.selectable,
                current = theme,
                quality = quality,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.size(24.dp))
        }

        // 左上（仅手机）：返回按钮
        if (!isTV) {
            IconButton(
                onClick = onExit,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "返回", tint = Color.White)
            }
        }
    }
}

/** 交叉淡入下限：低于此值直接跳过绘制（省掉全透明帧的空跑） */
private const val ALPHA_EPS = 0.004f

/** 顶部歌词行 —— 脱离频谱刷新作用域，仅在行变化时重组 */
@Composable
private fun LyricTopBar(
    lyrics: List<LyricsLine>?,
    progressMs: Long,
    fallback: String?,
    modifier: Modifier = Modifier
) {
    // 二分查找本身 O(log n)，无需 derivedStateOf：
    // 原写法把 progressMs 放进 remember key，每帧重建 State 对象，等于没缓存。
    val index = findCurrentLyricLine(lyrics, progressMs)

    val text = when {
        lyrics.isNullOrEmpty() -> fallback ?: ""
        index < 0 -> fallback ?: ""
        else -> lyrics[index].text
    }
    if (text.isBlank()) return

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                // drawBehind 画圆角矩形：避免 RoundedCornerShape clip 触发
                // Android 5.1 hwui Region 段错误（见 ThemeIndicator 注释）
                .drawBehind {
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.35f),
                        cornerRadius = CornerRadius(8.dp.toPx())
                    )
                }
                .padding(horizontal = 24.dp, vertical = 6.dp)
        )
    }
}

/**
 * 查找当前应显示的歌词行索引（二分，避免长歌词逐帧 O(n)）。
 * 返回最后一个 time <= 进度 的行；全部早于进度返回 -1。
 */
internal fun findCurrentLyricLine(lines: List<LyricsLine>?, progressMs: Long): Int {
    if (lines.isNullOrEmpty()) return -1
    var lo = 0
    var hi = lines.lastIndex
    var result = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].time <= progressMs) {
            result = mid
            lo = mid + 1
        } else hi = mid - 1
    }
    return result
}

/** 当前歌词行信息（给 E23 歌词点阵用） */
internal data class LyricInfo(
    val line: String?,
    val nextLine: String?,
    val progress: Float,
    val hasWords: Boolean,
    val lineIndex: Int,
    val wordStartTimes: List<Long>,
    val maxLineChars: Int = 0,
    val longestLine: String? = null
)

/**
 * 计算当前歌词行、行内进度、是否有逐字时间戳。
 * 用于 E23 歌词点阵效果的逐字点亮动画。
 */
/** 整曲歌词级常量：只随歌词内容变化，与播放进度无关 */
internal data class LyricMetrics(val maxChars: Int, val longest: String?)

/**
 * 汇总整曲歌词的最长句（E23 自适应字号用）。
 * 为 O(N) 全量扫描，必须由调用方缓存，不能每帧调用。
 */
internal fun computeLyricMetrics(lines: List<LyricsLine>?): LyricMetrics {
    if (lines.isNullOrEmpty()) return LyricMetrics(0, null)
    var maxChars = 0
    var longest: String? = null
    for (l in lines) {
        val len = l.text?.length ?: 0
        if (len > maxChars) {
            maxChars = len
            longest = l.text
        }
    }
    return LyricMetrics(maxChars, longest)
}

/**
 * 计算当前歌词行、行内进度、是否有逐字时间戳。
 * 用于 E23 歌词点阵效果的逐字点亮动画。
 *
 * @param metrics 整曲常量（由 computeLyricMetrics 缓存），避免每帧重算
 */
internal fun computeLyricInfo(
    lines: List<LyricsLine>?,
    progressMs: Long,
    metrics: LyricMetrics = LyricMetrics(0, null)
): LyricInfo {
    if (lines.isNullOrEmpty()) return LyricInfo(null, null, 0f, false, -1, emptyList())
    val idx = findCurrentLyricLine(lines, progressMs)
    if (idx < 0) return LyricInfo(null, null, 0f, false, -1, emptyList())
    val current = lines[idx]
    val next = lines.getOrNull(idx + 1)
    val duration = (next?.time ?: current.time + 3000L) - current.time
    val elapsed = progressMs - current.time
    val progress = if (duration > 0) (elapsed.toFloat() / duration).coerceIn(0f, 1f) else 1f
    val hasWords = !current.wordTimestamps.isNullOrEmpty()
    val wordStarts = current.wordTimestamps.map { it.startMs - current.time }
    return LyricInfo(
        current.text, next?.text, progress, hasWords, idx, wordStarts,
        metrics.maxChars, metrics.longest ?: current.text
    )
}

/** 底部圆点指示器（含 AUTO 档） */
@Composable
private fun ThemeIndicator(
    themes: List<VisualizerTheme>,
    current: VisualizerTheme,
    quality: VisualQuality,
    modifier: Modifier = Modifier
) {
    // 圆点用 Canvas drawCircle 绘制，而非 21 个 CircleShape clip：
    // Android 5.1 (创维 rtd299o) 的 hwui Region::createTJunctionFreeRegion 对
    // 大量圆形 clip 的拓扑合并存在段错误（RenderThread SIGSEGV，真机三次复现）。
    // drawCircle 是普通绘制指令，不产生 RenderNode clip region。
    val density = LocalDensity.current
    val spacingDp = 17.dp
    val radiusDp = 5.dp
    val smallRadiusDp = 3.5.dp
    val totalWidthDp = (themes.size - 1).coerceAtLeast(0) * spacingDp.value + 2 * radiusDp.value
    Canvas(
        modifier = modifier
            .padding(vertical = 10.dp)
            .size(totalWidthDp.dp, 10.dp)
    ) {
        val cx0 = radiusDp.toPx()
        val cy = size.height / 2
        themes.forEachIndexed { i, t ->
            val supported = quality.supports(t)
            val active = t == current
            drawCircle(
                color = when {
                    !supported -> Color.White.copy(alpha = 0.18f)
                    active -> NasMusicColors.Primary
                    else -> Color.White.copy(alpha = 0.42f)
                },
                radius = if (active) radiusDp.toPx() else smallRadiusDp.toPx(),
                center = Offset(cx0 + i * spacingDp.toPx(), cy)
            )
        }
    }
}

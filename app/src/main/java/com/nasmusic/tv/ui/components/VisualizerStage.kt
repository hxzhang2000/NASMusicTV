package com.nasmusic.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
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
 * ① 背景层：封面 + 暗化遮罩
 * ```
 *
 * 绘制循环用 `withFrameNanos` 驱动，**不走 Compose 重组**——
 * 频谱以 30fps 变化，若每帧重组会白白消耗性能。
 */
@Composable
fun VisualizerStage(
    song: Song?,
    isPlaying: Boolean,
    frame: AudioFrame,
    cover: ImageBitmap?,
    coverUrl: String?,
    palette: CoverPalette,
    lyrics: List<LyricsLine>?,
    progressMs: Long,
    theme: VisualizerTheme,
    quality: VisualQuality,
    /** 自动导演档：主题变化时走 600ms 交叉淡入；手动切换传 false（硬切） */
    crossfade: Boolean = false,
    isTV: Boolean,
    onExit: () -> Unit,
    onNextTheme: () -> Unit,
    onPrevTheme: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val safeArea = 0.05f

    // ── 渲染器生命周期（自动导演档 600ms 交叉淡入）────────────
    val renderCtx = remember { RenderContext() }
    val swapper = remember { RendererSwapper() }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // 淡入透明度：由绘制循环逐帧写入，在绘制阶段读取 → 只重绘不重组
    val fadeAlpha = remember { mutableFloatStateOf(1f) }
    val prevAlpha = remember { mutableFloatStateOf(0f) }
    // 正在淡出的旧渲染器（null = 无旧层）；用 State 以便出现/消失时重组
    val prevRenderer = remember { mutableStateOf<VisualizerRenderer?>(null) }

    // 主题 / 画质变化 → 同步渲染器（自动导演走淡入，手动切换硬切）
    LaunchedEffect(theme, quality, crossfade) {
        renderCtx.update(quality, palette, cover, Size.Zero, 0f,
            System.currentTimeMillis(), song?.title)
        if (swapper.sync(theme, quality, crossfade, renderCtx, System.currentTimeMillis())) {
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

    // ── 控制栏自动隐藏（3s）────────────────────────────────────
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            kotlinx.coroutines.delay(3000)
            controlsVisible = false
        }
    }

    // ── 效果名 Toast ───────────────────────────────────────────
    var toastVisible by remember { mutableStateOf(true) }
    LaunchedEffect(theme) {
        toastVisible = true
        kotlinx.coroutines.delay(2500)
        toastVisible = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    when (e.key) {
                        Key.DirectionLeft -> { onPrevTheme(); true }
                        Key.DirectionRight -> { onNextTheme(); true }
                        Key.DirectionUp -> { controlsVisible = true; true }
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
                            else -> controlsVisible = true
                        }
                    }
                )
            }
    ) {
        // ① 背景层：封面 + 暗化遮罩（≥0.85，因 blur 在 API<31 为 no-op）
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.42f }
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.background.copy(alpha = 0.88f))
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
            // 复用实例，零分配
            renderCtx.update(quality, palette, cover, canvasSize,
                minOf(size.width, size.height) * safeArea, frame.timeMs, song?.title)
            val cur = swapper.current
            // tick 参与读取以确保每帧重绘
            if (tick >= 0L && cur != null && fadeAlpha.floatValue > ALPHA_EPS) {
                with(cur) { draw(frame, renderCtx) }
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
                    minOf(size.width, size.height) * safeArea, frame.timeMs, song?.title)
                if (tick >= 0L && prevAlpha.floatValue > ALPHA_EPS) {
                    with(old) { draw(frame, renderCtx) }
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
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
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

            // 底部：指示器 + 控制栏
            ThemeIndicator(
                themes = VisualizerTheme.selectable,
                current = theme,
                quality = quality,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                StageControls(
                    isPlaying = isPlaying,
                    isTV = isTV,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrev = onPrev,
                    onExit = onExit
                )
            }
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
    val index = remember(lyrics, progressMs) {
        derivedStateOf { findCurrentLyricLine(lyrics, progressMs) }
    }.value

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
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
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

/** 底部圆点指示器（含 AUTO 档） */
@Composable
private fun ThemeIndicator(
    themes: List<VisualizerTheme>,
    current: VisualizerTheme,
    quality: VisualQuality,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        themes.forEach { t ->
            val supported = quality.supports(t)
            val active = t == current
            Box(
                Modifier
                    .size(if (active) 10.dp else 7.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !supported -> Color.White.copy(alpha = 0.18f)
                            active -> NasMusicColors.Primary
                            else -> Color.White.copy(alpha = 0.42f)
                        }
                    )
            )
        }
    }
}

/** 舞台控制栏（3s 自动隐藏） */
@Composable
private fun StageControls(
    isPlaying: Boolean,
    isTV: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onExit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.SkipPrevious, "上一首", tint = Color.White)
        }
        IconButton(onClick = onPlayPause) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                "播放/暂停", tint = Color.White
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.SkipNext, "下一首", tint = Color.White)
        }
        if (isTV) {
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.GraphicEq, "退出频谱", tint = Color.White)
            }
        }
    }
}

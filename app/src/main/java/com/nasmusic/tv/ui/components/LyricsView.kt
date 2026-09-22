package com.nasmusic.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsHighlightMode
import com.nasmusic.tv.ui.theme.LyricsTheme
import com.nasmusic.tv.ui.theme.LocalLyricsTheme
import com.nasmusic.tv.ui.theme.NasMusicBrushes
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.delay

/** 拖拽跳转指示条的纵向位置（顶部 fade mask 下方） */
private val INDICATOR_OFFSET_Y = 88.dp

/**
 * 歌词视图
 * 支持按当前播放时间滚动显示歌词行
 * 支持逐行/逐字高亮模式切换
 * 使用 TV 标准 Surface 焦点管理，避免与焦点系统冲突
 *
 * @param fadeMaskColor 上下渐隐遮罩的底色。传 null（默认）沿用主题的深蓝底色
 *   [NasMusicBrushes.topFadeMask] / [NasMusicBrushes.bottomFadeMask]；
 *   沉浸播放页为纯黑底，传黑色可让渐隐与背景无缝融合。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LyricsView(
    lyrics: Lyrics?,
    currentTimeMs: Long,
    modifier: Modifier = Modifier,
    highlightMode: LyricsHighlightMode = LyricsHighlightMode.LINE_BY_LINE,
    isPlaying: Boolean = true,
    fontSizeMultiplier: Float = 1.0f,
    fadeMaskColor: Color? = null,
    onSeekToLine: ((Long) -> Unit)? = null
) {
    // 手机紧凑模式：恢复原始密度，保持歌词字号不被全局缩放（用户独立调节）
    if (com.nasmusic.tv.ui.theme.LocalPhoneCompact.current) {
        val baseDensity = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                density = baseDensity.density * com.nasmusic.tv.ui.theme.CompactSizes.LYRICS_RECOVER_SCALE,
                fontScale = baseDensity.fontScale
            )
        ) {
            LyricsViewInner(lyrics, currentTimeMs, modifier, highlightMode, isPlaying, fontSizeMultiplier, fadeMaskColor, onSeekToLine)
        }
    } else {
        LyricsViewInner(lyrics, currentTimeMs, modifier, highlightMode, isPlaying, fontSizeMultiplier, fadeMaskColor, onSeekToLine)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LyricsViewInner(
    lyrics: Lyrics?,
    currentTimeMs: Long,
    modifier: Modifier = Modifier,
    highlightMode: LyricsHighlightMode = LyricsHighlightMode.LINE_BY_LINE,
    isPlaying: Boolean = true,
    fontSizeMultiplier: Float = 1.0f,
    fadeMaskColor: Color? = null,
    onSeekToLine: ((Long) -> Unit)? = null
) {
    if (lyrics == null || lyrics.isEmpty) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = NasMusicColors.TextSecondary,
                    modifier = Modifier.size(64.dp).padding(bottom = 16.dp)
                )
                Text(
                    text = stringResource(R.string.player_no_lyrics),
                    style = LocalLyricsTheme.current.normalLine,
                    color = NasMusicColors.TextSecondary
                )
            }
        }
        return
    }

    val listState = rememberLazyListState()

    // 空白带修复（2026-09-22 真机截图分析）：首尾 120dp spacer 在手机横屏
    //（高度 ~411dp）上吃掉近 1/3 视口——横屏收敛为 40dp；TV 竖向空间充裕保持 120。
    val isPhoneLandscape = com.nasmusic.tv.ui.theme.LocalUiMode.current ==
        com.nasmusic.tv.ui.theme.UiMode.PhoneLandscape
    val edgeSpacer = if (isPhoneLandscape) 40.dp else 120.dp

    // —— 手势拖拽跳转（2026-09-22 新增，手机端横竖屏）：拖动浏览歌词时顶部出现
    //    虚线指示条，松手后跳到虚线处歌词的起始时间播放。isDragSeeking 期间
    //    暂停自动跟行；tap（未滚动）不触发，普通浏览不受影响。
    var isDragSeeking by remember { mutableStateOf(false) }
    var seekTargetLine by remember { mutableStateOf(-1) }
    // 修复（17:51 真机反馈）：LazyColumn 的 scrollable 发出的是 DragInteraction
    // 而非 PressInteraction——collectIsPressedAsState 永远收不到事件，
    // isDragSeeking 不会激活，拖拽跳转整体无效。改 collectIsDraggedAsState。
    val listDragged by listState.interactionSource.collectIsDraggedAsState()

    // 逐字模式下使用本地高频时钟插值，平滑过渡（避免 1000ms progress 导致逐字跳动）
    // 基于上次已知 currentTimeMs（1秒锚点）+ 实际流逝时间估算当前进度
    var lyricTickMs by remember(lyrics) { mutableLongStateOf(currentTimeMs) }
    LaunchedEffect(currentTimeMs, isPlaying, highlightMode, lyrics) {
        if (highlightMode == LyricsHighlightMode.WORD_BY_WORD && isPlaying) {
            // 记录锚点：当前已知 progress + 系统时间
            var anchorProgress = currentTimeMs
            var anchorSystemMs = System.currentTimeMillis()
            lyricTickMs = anchorProgress
            while (true) {
                delay(50)  // 50ms 刷新（20fps）
                val elapsed = System.currentTimeMillis() - anchorSystemMs
                lyricTickMs = anchorProgress + elapsed
                // currentTimeMs 更新时（每秒一次），重新校准锚点
                if (currentTimeMs != anchorProgress) {
                    anchorProgress = currentTimeMs
                    anchorSystemMs = System.currentTimeMillis()
                }
            }
        } else {
            // 非逐字模式或暂停，直接使用 currentTimeMs
            lyricTickMs = currentTimeMs
        }
    }

    // 找到当前歌词行索引（逐字模式用高频时钟，逐行模式用原始 progress）
    val effectiveTimeMs = if (highlightMode == LyricsHighlightMode.WORD_BY_WORD && isPlaying) {
        lyricTickMs
    } else {
        currentTimeMs
    }

    val currentIndex = lyrics.lines
        .indexOfFirst { it.time > effectiveTimeMs }
        .let { if (it == -1) lyrics.lines.size - 1 else it - 1 }
        .coerceAtLeast(0)

    LaunchedEffect(currentIndex, isDragSeeking) {
        // 拖拽跳转中暂停自动跟行；松手跳转后 currentIndex 变化会自然滚回当前行
        if (currentIndex >= 0 && !isDragSeeking) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    // 手指拖动歌词列表 → 进入拖拽跳转模式（tap 无滚动不触发）
    LaunchedEffect(listDragged, listState.isScrollInProgress) {
        if (listDragged && listState.isScrollInProgress && !isDragSeeking) {
            isDragSeeking = true
            seekTargetLine = -1
        }
    }

    // 拖拽中：计算虚线指示处的目标行（LazyColumn item index = 行 index + 1，头部有 spacer）
    if (isDragSeeking) {
        val indicatorPx = with(androidx.compose.ui.platform.LocalDensity.current) { INDICATOR_OFFSET_Y.toPx() }.toInt()
        val info = listState.layoutInfo
        seekTargetLine = info.visibleItemsInfo.firstOrNull { item ->
            item.index in 1..lyrics.lines.size &&
                item.offset <= indicatorPx && item.offset + item.size > indicatorPx
        }?.let { it.index - 1 } ?: -1
    }

    // 松手：跳到目标行起始时间播放（DragInteraction.Stop = 手指抬起）
    LaunchedEffect(listDragged) {
        if (!listDragged && isDragSeeking) {
            isDragSeeking = false
            val target = seekTargetLine
            seekTargetLine = -1
            if (target in lyrics.lines.indices) {
                onSeekToLine?.invoke(lyrics.lines[target].time)
            }
        }
    }

    // Box 叠加：下方是滚动歌词，上下各一层 fade mask
    // 沉浸播放页为纯黑底 → 用同色遮罩，渐隐边缘与背景无缝
    val topFadeMask = remember(fadeMaskColor) {
        if (fadeMaskColor != null) {
            Brush.verticalGradient(colors = listOf(fadeMaskColor, Color.Transparent))
        } else {
            NasMusicBrushes.topFadeMask
        }
    }
    val bottomFadeMask = remember(fadeMaskColor) {
        if (fadeMaskColor != null) {
            Brush.verticalGradient(colors = listOf(Color.Transparent, fadeMaskColor))
        } else {
            NasMusicBrushes.bottomFadeMask
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        // --- 滚动歌词列表（使用 TV 焦点管理，移除了与焦点冲突的 pointerInput）---
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = edgeSpacer))
            }
            itemsIndexed(lyrics.lines) { index, line ->
                val isCurrent = index == currentIndex
                val played = index < currentIndex
                val near = kotlin.math.abs(index - currentIndex) <= 1
                val isSeekTarget = isDragSeeking && index == seekTargetLine

                val textColor = when {
                    isSeekTarget -> NasMusicColors.Primary
                    isCurrent -> NasMusicColors.Primary
                    played -> NasMusicColors.TextSecondary.copy(alpha = 0.45f)
                    near -> NasMusicColors.TextPrimary
                    else -> NasMusicColors.TextSecondary
                }
                val fontSize = when {
                    isCurrent -> 40.sp * fontSizeMultiplier
                    near -> 28.sp * fontSizeMultiplier
                    else -> 22.sp * fontSizeMultiplier
                }

                // 逐字模式：与 KARAOKE 页一致，使用平滑双层裁剪推进（半个字粒度），
                // 而非逐字跳变。progress 由该行开始/结束时间按当前进度比例计算。
                if (isCurrent && highlightMode == LyricsHighlightMode.WORD_BY_WORD) {
                    val nextLineTime = if (index + 1 < lyrics.lines.size) {
                        lyrics.lines[index + 1].time
                    } else {
                        line.time + 3000L // 默认3秒
                    }
                    val progress = lineProgress(line.time, nextLineTime, effectiveTimeMs)
                    KaraokeLineText(
                        text = line.text,
                        progress = progress,
                        fontSize = 40.sp * fontSizeMultiplier,
                        textAlign = TextAlign.Center,
                        baseColor = textColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                vertical = if (isCurrent) 22.dp else 14.dp,
                                horizontal = 32.dp
                            )
                    )
                } else {
                    Text(
                        text = line.text,
                        color = textColor,
                        fontSize = fontSize,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                vertical = if (isCurrent) 22.dp else 14.dp,
                                horizontal = 32.dp
                            )
                    )
                }
            }
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = edgeSpacer))
            }
        }

        // —— 拖拽跳转指示：顶部虚线 + 目标行时间（松手跳到该行起始时间）——
        if (isDragSeeking) {
            val targetTime = lyrics.lines.getOrNull(seekTargetLine)?.time
            val timeLabel = if (targetTime != null && targetTime >= 0) {
                val m = (targetTime / 60000).toString().padStart(2, '0')
                val s = ((targetTime % 60000) / 1000).toString().padStart(2, '0')
                "$m:$s"
            } else {
                "—"
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = INDICATOR_OFFSET_Y - 14.dp)
            ) {
                Text(
                    text = timeLabel,
                    color = NasMusicColors.Primary,
                    style = LocalLyricsTheme.current.normalLine,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                        .background(NasMusicColors.Surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                )
                Canvas(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(start = 88.dp, end = 24.dp)
                        .height(2.dp)
                        .fillMaxWidth()
                ) {
                    drawLine(
                        color = NasMusicColors.Primary,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    )
                }
            }
        }

        // --- 顶部渐隐 mask ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.TopCenter)
                .background(topFadeMask)
        )

        // --- 底部渐隐 mask ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.BottomCenter)
                .background(bottomFadeMask)
        )
    }
}



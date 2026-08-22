package com.nasmusic.tv.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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
import com.nasmusic.tv.ui.theme.NasMusicBrushes
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.delay

/**
 * 歌词视图
 * 支持按当前播放时间滚动显示歌词行
 * 支持逐行/逐字高亮模式切换
 * 使用 TV 标准 Surface 焦点管理，避免与焦点系统冲突
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LyricsView(
    lyrics: Lyrics?,
    currentTimeMs: Long,
    modifier: Modifier = Modifier,
    highlightMode: LyricsHighlightMode = LyricsHighlightMode.LINE_BY_LINE,
    isPlaying: Boolean = true,
    fontSizeMultiplier: Float = 1.0f
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
            LyricsViewInner(lyrics, currentTimeMs, modifier, highlightMode, isPlaying, fontSizeMultiplier)
        }
    } else {
        LyricsViewInner(lyrics, currentTimeMs, modifier, highlightMode, isPlaying, fontSizeMultiplier)
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
    fontSizeMultiplier: Float = 1.0f
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
                    style = LyricsTheme.normalLine,
                    color = NasMusicColors.TextSecondary
                )
            }
        }
        return
    }

    val listState = rememberLazyListState()

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

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    // Box 叠加：下方是滚动歌词，上下各一层 fade mask
    Box(modifier = modifier.fillMaxSize()) {
        // --- 歌词来源 badge ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(NasMusicColors.Surface.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = NasMusicColors.Primary,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = lyrics.source.displayName,
                    color = NasMusicColors.TextSecondary,
                    fontSize = 16.sp
                )
            }
        }

        // --- 滚动歌词列表（使用 TV 焦点管理，移除了与焦点冲突的 pointerInput）---
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 120.dp))
            }
            itemsIndexed(lyrics.lines) { index, line ->
                val isCurrent = index == currentIndex
                val played = index < currentIndex
                val near = kotlin.math.abs(index - currentIndex) <= 1

                val textColor = when {
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
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 120.dp))
            }
        }

        // --- 顶部渐隐 mask ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.TopCenter)
                .background(NasMusicBrushes.topFadeMask)
        )

        // --- 底部渐隐 mask ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.BottomCenter)
                .background(NasMusicBrushes.bottomFadeMask)
        )
    }
}



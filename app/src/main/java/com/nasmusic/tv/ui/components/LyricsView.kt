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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.ui.theme.LyricsTheme
import com.nasmusic.tv.ui.theme.NasMusicBrushes
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 歌词视图
 * 支持按当前播放时间滚动显示歌词行
 * 使用 TV 标准 Surface 焦点管理，避免与焦点系统冲突
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LyricsView(
    lyrics: Lyrics?,
    currentTimeMs: Long,
    modifier: Modifier = Modifier
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
                    text = "暂无歌词",
                    style = LyricsTheme.normalLine,
                    color = NasMusicColors.TextSecondary
                )
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 找到当前歌词行索引
    val currentIndex = lyrics.lines
        .indexOfFirst { it.time > currentTimeMs }
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
                    fontSize = 11.sp
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
                    isCurrent -> 40.sp
                    near -> 28.sp
                    else -> 22.sp
                }

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



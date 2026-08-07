package com.nasmusic.tv.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsLine
import com.nasmusic.tv.data.model.WordTimestamp
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.delay

/**
 * KARAOKE 模式专用歌词视图
 *
 * 与普通 LyricsView 的区别：
 * - 不使用滚动列表，固定显示两行，两行一组整组替换
 * - 第一行播放中逐字高亮；播完后整行保留为黄色，直接播放第二行
 * - 第二行也播完后，整组替换为后两行，重新从第一行开始
 * - 强制逐字高亮模式
 * - 两行同字号 50sp（两行同时显示）
 * - 第一行左对齐，第二行右对齐，左右留出距离
 *
 * @param lyrics 歌词对象
 * @param progressMs 当前播放进度（毫秒）
 * @param isPlaying 是否正在播放（用于逐字高亮时钟插值）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KaraokeLyricsView(
    lyrics: Lyrics?,
    progressMs: Long,
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (lyrics == null || lyrics.isEmpty) {
        Column(
            modifier = modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "暂无歌词",
                fontSize = 20.sp,
                color = NasMusicColors.TextSecondary
            )
        }
        return
    }

    // 逐字高亮：使用本地高频时钟插值（50ms 刷新），平滑过渡
    var lyricTickMs by remember(lyrics) { mutableLongStateOf(progressMs) }
    LaunchedEffect(progressMs, isPlaying, lyrics) {
        if (isPlaying) {
            var anchorProgress = progressMs
            var anchorSystemMs = System.currentTimeMillis()
            lyricTickMs = anchorProgress
            while (true) {
                delay(50)
                val elapsed = System.currentTimeMillis() - anchorSystemMs
                lyricTickMs = anchorProgress + elapsed
                if (progressMs != anchorProgress) {
                    anchorProgress = progressMs
                    anchorSystemMs = System.currentTimeMillis()
                }
            }
        } else {
            lyricTickMs = progressMs
        }
    }

    // 找到当前歌词行索引
    val currentIndex = lyrics.lines
        .indexOfFirst { it.time > lyricTickMs }
        .let { if (it == -1) lyrics.lines.size - 1 else it - 1 }
        .coerceAtLeast(0)

    // 两行一组显示：pairStart 取偶数行下标，整组替换
    val pairStart = currentIndex - (currentIndex % 2)
    val firstLine = lyrics.lines.getOrNull(pairStart)
    val secondLine = lyrics.lines.getOrNull(pairStart + 1)
    // 第一行是否已播完（正在唱第二行）
    val firstDone = currentIndex > pairStart

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 72.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // 第一行：播放中 -> 逐字高亮；已播完 -> 整行黄色保留（不消失）
        firstLine?.let { line ->
            val firstText = if (!firstDone) {
                buildKaraokeAnnotatedString(
                    line = line,
                    currentTimeMs = lyricTickMs,
                    nextLineTime = secondLine?.time ?: (line.time + 3000L)
                )
            } else {
                buildAnnotatedString {
                    pushStyle(SpanStyle(color = Color.Yellow))
                    append(line.text)
                    pop()
                }
            }
            Text(
                text = firstText,
                fontSize = 50.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(12.dp))

        // 第二行：未开始 -> 灰色预览；播放中 -> 逐字高亮
        secondLine?.let { line ->
            val secondText = if (firstDone) {
                buildKaraokeAnnotatedString(
                    line = line,
                    currentTimeMs = lyricTickMs,
                    nextLineTime = lyrics.lines.getOrNull(pairStart + 2)?.time
                        ?: (line.time + 3000L)
                )
            } else {
                buildAnnotatedString {
                    pushStyle(SpanStyle(color = NasMusicColors.TextSecondary))
                    append(line.text)
                    pop()
                }
            }
            Text(
                text = secondText,
                fontSize = 50.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 构建逐字高亮 AnnotatedString
 *
 * 已播放的字 -> 黄色高亮
 * 未播放的字 -> 白色
 */
private fun buildKaraokeAnnotatedString(
    line: LyricsLine,
    currentTimeMs: Long,
    nextLineTime: Long
): AnnotatedString {
    if (line.text.isEmpty()) return AnnotatedString("")

    // 获取逐字时间戳：优先使用原始数据，否则估算
    val wordTimestamps = if (line.wordTimestamps.isNotEmpty()) {
        line.wordTimestamps
    } else {
        estimateWordTimestamps(line, nextLineTime)
    }

    if (wordTimestamps.isEmpty()) return AnnotatedString(line.text)

    return buildAnnotatedString {
        var lastEnd = 0
        for (word in wordTimestamps) {
            val wordStart = line.text.indexOf(word.word, lastEnd)
            if (wordStart < 0) {
                if (lastEnd < line.text.length) {
                    append(line.text.substring(lastEnd))
                }
                break
            }
            if (wordStart > lastEnd) {
                append(line.text.substring(lastEnd, wordStart))
            }
            val wordPlayed = word.startMs <= currentTimeMs
            val style = if (wordPlayed) {
                SpanStyle(color = Color.Yellow)
            } else {
                SpanStyle(color = Color.White)
            }
            pushStyle(style)
            append(word.word)
            pop()
            lastEnd = wordStart + word.word.length
        }
        if (lastEnd < line.text.length) {
            append(line.text.substring(lastEnd))
        }
    }
}

/**
 * 估算逐字时间戳（用于标准 LRC 格式，无逐字数据时）
 */
private fun estimateWordTimestamps(line: LyricsLine, nextLineTime: Long): List<WordTimestamp> {
    if (line.text.isEmpty()) return emptyList()
    val lineDuration = if (nextLineTime > line.time) nextLineTime - line.time else 3000L
    val charDuration = lineDuration / line.text.length
    return line.text.mapIndexed { index, char ->
        WordTimestamp(
            word = char.toString(),
            startMs = line.time + index * charDuration
        )
    }
}

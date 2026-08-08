package com.nasmusic.tv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.delay

/**
 * KARAOKE 模式专用歌词视图
 *
 * 与普通 LyricsView 的区别：
 * - 不使用滚动列表，固定显示两行，两行一组整组替换
 * - 两行颜色完全一致：底色白色，播放进度以黄色平滑推进（非逐字跳变）
 * - 平滑进度按行时长比例连续移动，边界可落在半个字上
 * - 第一行播完后整行保留黄色，直接播放第二行；两行都播完后整组替换
 * - 两行同字号 50sp（两行同时显示）；第一行左对齐，第二行右对齐
 *
 * @param lyrics 歌词对象
 * @param progressMs 当前播放进度（毫秒）
 * @param isPlaying 是否正在播放（用于平滑进度时钟插值）
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

    // 平滑进度：使用本地高频时钟插值（50ms 刷新），进度条连续移动
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
        // 第一行：播放中 -> 黄色平滑推进；已播完 -> 整行黄色保留（不消失）
        firstLine?.let { line ->
            val firstLineEndMs = secondLine?.time ?: (line.time + 3000L)
            val firstProgress = if (firstDone) {
                1f
            } else {
                lineProgress(line.time, firstLineEndMs, lyricTickMs)
            }
            KaraokeLineText(
                text = line.text,
                progress = firstProgress,
                fontSize = 50.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(12.dp))

        // 第二行：未开始 -> 白色预览（与第一行颜色一致）；播放中 -> 黄色平滑推进
        secondLine?.let { line ->
            val secondLineEndMs = lyrics.lines.getOrNull(pairStart + 2)?.time
                ?: (line.time + 3000L)
            val secondProgress = if (firstDone) {
                lineProgress(line.time, secondLineEndMs, lyricTickMs)
            } else {
                0f
            }
            KaraokeLineText(
                text = line.text,
                progress = secondProgress,
                fontSize = 50.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 计算一行歌词的平滑播放进度（0f..1f）
 *
 * [lineStartMs] 到 [lineEndMs] 为该行的完整时长，[currentMs] 落在其中时
 * 返回线性比例；整行完成后返回 1f 保留黄色，行未开始时返回 0f（纯白预览）。
 */
private fun lineProgress(lineStartMs: Long, lineEndMs: Long, currentMs: Long): Float {
    if (lineEndMs <= lineStartMs) return 0f
    return ((currentMs - lineStartMs).toFloat() / (lineEndMs - lineStartMs).toFloat())
        .coerceIn(0f, 1f)
}

/**
 * 单行 KARAOKE 渲染：双层叠加实现平滑黄色进度
 *
 * - 底层（白色）：整行歌词
 * - 顶层（黄色）：与底层完全相同的歌词，按 [progress]（0..1）从左到右裁剪揭示，
 *   进度边界落在字符中间时即实现"半个字被覆盖"，非逐字跳变
 *
 * 使用 TextLayoutResult 定位边界像素，文本左对齐/右对齐均正确。
 */
@Composable
private fun KaraokeLineText(
    text: String,
    progress: Float,
    fontSize: TextUnit = 50.sp,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier
) {
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }

    Box(modifier = modifier) {
        // 底色层：白色（未播放部分 / 预览行）
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            textAlign = textAlign,
            color = Color.White,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth()
        )

        // 顶层层：黄色，按平滑进度裁剪揭示
        val lr = layout
        if (lr != null && progress > 0f) {
            Text(
                text = text,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                textAlign = textAlign,
                color = Color.Yellow,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        val contentScope = this
                        // 已覆盖的字符数（含小数 -> 半个字）
                        val coveredChars = progress * text.length
                        var remaining = coveredChars

                        // 逐可视行处理：先覆盖本行全部区域，再进入下一行
                        for (line in 0 until lr.lineCount) {
                            val lineStart = lr.getLineStart(line)
                            val lineEnd = lr.getLineEnd(line)
                            val lineLen = lineEnd - lineStart
                            if (lineLen <= 0) continue
                            if (remaining <= 0f) break

                            val take = minOf(remaining, lineLen.toFloat())
                            val boundaryX = if (take >= lineLen) {
                                // 本行已整体覆盖 -> 边界直接落在这行最右侧
                                lr.getLineRight(line)
                            } else {
                                val base = take.toInt()
                                val frac = take - base
                                val boundaryOffset = lineStart + base
                                // 边界落在半个字中间：在两个字符位置间插值
                                val x1 = lr.getHorizontalPosition(boundaryOffset, usePrimaryDirection = true)
                                val x2 = lr.getHorizontalPosition(boundaryOffset + 1, usePrimaryDirection = true)
                                x1 + (x2 - x1) * frac
                            }

                            clipRect(
                                left = 0f,
                                top = lr.getLineTop(line),
                                right = boundaryX,
                                bottom = lr.getLineBottom(line)
                            ) {
                                contentScope.drawContent()
                            }
                            remaining -= take
                        }
                    }
            )
        }
    }
}
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
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.delay
import kotlin.math.pow

/**
 * KARAOKE 模式专用歌词视图
 *
 * 与普通 LyricsView 的区别：
 * - 不使用滚动列表，固定显示两行：当前正在唱的行 + 下一行（滚动窗口逐行推进，不整组替换）
 * - 槽位固定不跳动：偶数索引行始终显示在顶部，奇数索引行始终显示在底部。
 *   下一句始终停留在另一个槽位作白色预览，轮到时原地变黄，无整行跳动
 * - 当前行播放进度为黄色平滑推进（非逐字跳变），按行时长比例连续移动，
 *   边界可落在半个字上
 * - 两行同字号 50sp；顶部槽位右对齐，底部槽位右对齐
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
    // 手机紧凑模式：恢复原始密度，保持歌词字号不被全局缩放
    if (com.nasmusic.tv.ui.theme.LocalPhoneCompact.current) {
        val baseDensity = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                density = baseDensity.density * com.nasmusic.tv.ui.theme.CompactSizes.LYRICS_RECOVER_SCALE,
                fontScale = baseDensity.fontScale
            )
        ) {
            KaraokeLyricsViewInner(lyrics, progressMs, isPlaying, modifier)
        }
    } else {
        KaraokeLyricsViewInner(lyrics, progressMs, isPlaying, modifier)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun KaraokeLyricsViewInner(
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
                text = stringResource(R.string.karaoke_no_lyrics),
                fontSize = FontSize.button(),
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

    // 滚动窗口：偶数索引行固定在顶部、奇数索引行固定在底部，另一槽位显示下一行。
    // 槽位不跳动：句2 开始播放时，顶部槽位内容换为句3（句2 已在底部），
    // 句3 播放时底部槽位换为句4，逐行滚动。
    val onTopIsCurrent = currentIndex % 2 == 0
    val topLineIndex = if (onTopIsCurrent) currentIndex else currentIndex + 1
    val bottomLineIndex = if (onTopIsCurrent) currentIndex + 1 else currentIndex
    val topLine = lyrics.lines.getOrNull(topLineIndex)
    val bottomLine = lyrics.lines.getOrNull(bottomLineIndex)
    // 当前行结束时间 = 下一行开始时间；最后一行无下一行时按 +3000ms 估算
    val currentLineEndMs = lyrics.lines.getOrNull(currentIndex + 1)?.time
        ?: ((lyrics.lines[currentIndex].time) + 3000L)

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 72.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // 手机紧凑模式使用更小字号，保证两行歌词在窄屏上都能放下
        val lineFontSize = if (com.nasmusic.tv.ui.theme.LocalPhoneCompact.current) 34.sp else 50.sp
        // 顶部槽位：偶数索引行 -> 黄色平滑推进；奇数索引行 -> 白色预览（下一句）
        topLine?.let { line ->
            val progress = if (onTopIsCurrent) {
                lineProgress(line.time, currentLineEndMs, lyricTickMs)
            } else {
                0f
            }
            KaraokeLineText(
                text = line.text,
                progress = progress,
                fontSize = lineFontSize,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(12.dp))

        // 底部槽位：奇数索引行 -> 黄色平滑推进；偶数索引行 -> 白色预览（下一句）
        bottomLine?.let { line ->
            val progress = if (!onTopIsCurrent) {
                lineProgress(line.time, currentLineEndMs, lyricTickMs)
            } else {
                0f
            }
            KaraokeLineText(
                text = line.text,
                progress = progress,
                fontSize = lineFontSize,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 逐字高亮的"前快后慢"覆盖节奏
 *
 * 卡拉OK 逐字本质上每个字唱的时间并不均等——业界通常把行拆成独立字时长
 * (ASS `\k` 逐字时间戳 / 逐字 LRC),且句尾常拖音。本项目 LRC 只有整行起止时间,
 * 因此用一个平滑幂函数近似这种"每字时长不均"：`progress^0.6` 让行进到一半时
 * 已覆盖约 66% 的字（前面唱得快），剩余 34% 的字用后半段时间慢慢唱完（后面慢、
 * 句尾有拖音感）。这是一个内建常量曲线，不依赖每字时间戳，所有逐字渲染共用。
 */
private const val KARAOKE_PACING_EXPONENT = 0.6f

/**
 * 将行内进度（0..1）映射为逐字覆盖比例（0..1）。
 * 指数 < 1 => 前快后慢：行内时间过半时覆盖比例已过半数。进度 0/1 边界保持严格 0/1。
 */
internal fun karaokePacingFraction(progress: Float): Float {
    if (progress <= 0f) return 0f
    if (progress >= 1f) return 1f
    return progress.pow(KARAOKE_PACING_EXPONENT)
}

/**
 * 计算一行歌词的平滑播放进度（0f..1f）
 *
 * [lineStartMs] 到 [lineEndMs] 为该行的完整时长，[currentMs] 落在其中时
 * 返回线性比例；整行完成后返回 1f 保留黄色，行未开始时返回 0f（纯白预览）。
 */
internal fun lineProgress(lineStartMs: Long, lineEndMs: Long, currentMs: Long): Float {
    if (lineEndMs <= lineStartMs) return 0f
    return ((currentMs - lineStartMs).toFloat() / (lineEndMs - lineStartMs).toFloat())
        .coerceIn(0f, 1f)
}

/**
 * 逐可视行的覆盖比例（0f..1f）。
 *
 * [rowLengths] = 各可视行的字符数（`getLineEnd - getLineStart`），
 * [coveredChars] = 已唱到的字符位置（含小数，0.5 = 半个字）。
 *
 * **契约：前一行必须走完才轮到下一行** —— 第 k 行比例 < 1 时，第 k+1 行必为 0f。
 * 折行歌词（一句被排成 2~3 个可视行）正是靠这条契约才「走完第一折行再走第二折行」。
 */
internal fun karaokeRowCoverage(rowLengths: List<Int>, coveredChars: Float): List<Float> {
    var remaining = coveredChars
    val out = ArrayList<Float>(rowLengths.size)
    for (len in rowLengths) {
        if (len <= 0 || remaining <= 0f) {
            out += 0f
            continue
        }
        val take = minOf(remaining, len.toFloat())
        out += (take / len.toFloat()).coerceIn(0f, 1f)
        remaining -= take
    }
    return out
}

/**
 * 某个可视行内「已覆盖 [coveredInRow] 个字符」对应的裁剪右边界 x。
 *
 * [xOfOffset] 把**行内**字符下标映射为该字符在布局坐标系里的 x（左缘），
 * [rowRight] 是该行文本的右缘（`TextLayoutResult.getLineRight`）。
 *
 * ⚠️ **为什么不能无脑用 `xOfOffset(base + 1)` 当插值终点**（v2.36.7 修复的根因）：
 * `base + 1 == rowLength` 时，该 offset 恰好是**软换行边界**。AOSP `Layout.getPrimaryHorizontal`
 * 内部先 `getLineForOffset(offset)`（二分条件 `getLineStart(guess) > offset` → 边界 offset
 * 归属**下一行**），于是返回的是**下一可视行行首的 x**。居中对齐时它远小于本行右缘
 * ⇒ 插值终点跑到本行左半边 ⇒ 边界随进度**向左倒带**（用户看到「第一折行没走完就返回」）。
 * 故行尾字符一律改用 [rowRight] 作终点。
 *
 * 另外 `coerceAtLeast(0f)` 兜底：任何情况下边界都不允许回退（RTL / 极端对齐）。
 */
internal fun karaokeRowBoundaryX(
    coveredInRow: Float,
    rowLength: Int,
    xOfOffset: (Int) -> Float,
    rowRight: Float,
): Float {
    if (rowLength <= 0) return rowRight
    if (coveredInRow >= rowLength) return rowRight
    if (coveredInRow <= 0f) return xOfOffset(0)
    val base = coveredInRow.toInt().coerceIn(0, rowLength - 1)
    val frac = (coveredInRow - base).coerceIn(0f, 1f)
    val x1 = xOfOffset(base)
    val x2 = if (base + 1 >= rowLength) rowRight else xOfOffset(base + 1)
    return x1 + (x2 - x1).coerceAtLeast(0f) * frac
}

/**
 * 单行 KARAOKE 渲染：双层叠加实现平滑进度
 *
 * - 底层（[baseColor]）：整行歌词
 * - 顶层（[highlightColor]）：与底层完全相同的歌词，按 [progress]（0..1）从左到右裁剪揭示，
 *   进度边界落在字符中间时即实现"半个字被覆盖"，非逐字跳变
 *
 * 使用 TextLayoutResult 定位边界像素，文本左对齐/右对齐均正确。
 * 折行（一个可视行放不下）时**按可视行逐行推进**：前一行整体覆盖后才进入下一行，
 * 边界像素由 [karaokeRowBoundaryX] 计算（行尾不回退，见该函数的说明）。
 */
@Composable
internal fun KaraokeLineText(
    text: String,
    progress: Float,
    fontSize: TextUnit = 50.sp,
    textAlign: TextAlign = TextAlign.Start,
    baseColor: Color = Color.White,
    highlightColor: Color = Color.Yellow,
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
            color = baseColor,
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
                color = highlightColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        val contentScope = this
                        // 已覆盖的字符数（含小数 -> 半个字）。
                        // 逐字节奏前快后慢：同样行内进度下，句首字先亮、句尾字慢慢拖亮。
                        val coveredChars = karaokePacingFraction(progress) * text.length

                        // 逐可视行处理：先覆盖本行全部区域，再进入下一行
                        val rowCount = lr.lineCount
                        val rowLengths = (0 until rowCount).map { lr.getLineEnd(it) - lr.getLineStart(it) }
                        val rowCoverage = karaokeRowCoverage(rowLengths, coveredChars)
                        for (line in 0 until rowCount) {
                            val rowLen = rowLengths[line]
                            val coveredInRow = rowCoverage.getOrElse(line) { 0f } * rowLen
                            if (rowLen <= 0 || coveredInRow <= 0f) continue

                            val lineStart = lr.getLineStart(line)
                            val boundaryX = karaokeRowBoundaryX(
                                coveredInRow = coveredInRow,
                                rowLength = rowLen,
                                xOfOffset = { i ->
                                    lr.getHorizontalPosition(lineStart + i, usePrimaryDirection = true)
                                },
                                rowRight = lr.getLineRight(line),
                            )

                            clipRect(
                                left = 0f,
                                top = lr.getLineTop(line),
                                right = boundaryX,
                                bottom = lr.getLineBottom(line)
                            ) {
                                contentScope.drawContent()
                            }
                        }
                    }
            )
        }
    }
}
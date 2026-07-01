package com.nasmusic.tv.lyrics

import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsLine
import com.nasmusic.tv.data.model.LyricsSource
import com.nasmusic.tv.data.model.WordTimestamp
import com.nasmusic.tv.util.AppLog

/**
 * LRC 歌词解析器
 * 支持标准 LRC 格式和增强 LRC 格式
 */
object LrcParser {

    /**
     * 解析 LRC 格式歌词文本
     */
    fun parse(lrcText: String, songId: String = "", offset: Long = 0): Lyrics {
        val lines = mutableListOf<LyricsLine>()
        val cleanedText = lrcText.replace("\r\n", "\n").replace("\r", "\n")

        // Parse offset from header if present
        var globalOffset = offset
        OFFSET_REGEX.find(cleanedText)?.let { match ->
            globalOffset += match.groupValues[1].toLongOrNull() ?: 0L
        }

        cleanedText.lines().forEach { line ->
            val match = LINE_REGEX.find(line)
            if (match != null) {
                val rawText = match.groupValues[2].trim()
                val timeMatches = TIME_REGEX.findAll(line)

                // Check if line contains embedded word timestamps (karaoke format)
                val wordMatches = WORD_TIMESTAMP_REGEX.findAll(rawText).toList()
                val wordTimestamps = if (wordMatches.isNotEmpty()) {
                    AppLog.d("LrcParser", "Found ${wordMatches.size} word timestamps in line: ${rawText.take(50)}...")
                    wordMatches.map { wm ->
                        val wMinutes = wm.groupValues[1].toLong()
                        val wSeconds = wm.groupValues[2].toLong()
                        val wMillis = wm.groupValues[3].let {
                            if (it.length == 2) it.toLong() * 10 else it.toLong()
                        }
                        val wTimeMs = wMinutes * 60 * 1000 + wSeconds * 1000 + wMillis + globalOffset
                        WordTimestamp(
                            word = wm.groupValues[4],
                            startMs = wTimeMs
                        )
                    }
                } else {
                    emptyList()
                }

                // Compute display text: strip word timestamp markers for karaoke lines
                val displayText = if (wordTimestamps.isNotEmpty()) {
                    wordTimestamps.joinToString("") { it.word }
                } else {
                    rawText
                }

                timeMatches.forEach { timeMatch ->
                    val minutes = timeMatch.groupValues[1].toLong()
                    val seconds = timeMatch.groupValues[2].toLong()
                    val millis = timeMatch.groupValues[3].let {
                        if (it.length == 2) it.toLong() * 10 else it.toLong()
                    }

                    val timeMs = (minutes * 60 * 1000 + seconds * 1000 + millis + globalOffset).coerceAtLeast(0)
                    lines.add(LyricsLine(
                        time = timeMs,
                        text = displayText,
                        wordTimestamps = wordTimestamps
                    ))
                }
            }
        }

        // Sort by time
        lines.sortBy { it.time }
        val lyricsWithWords = lines.count { it.wordTimestamps.isNotEmpty() }
        AppLog.d("LrcParser", "Parsed ${lines.size} lines, $lyricsWithWords lines with word timestamps")

        return Lyrics(
            songId = songId,
            lines = lines,
            source = LyricsSource.EMBEDDED,
            offset = globalOffset
        )
    }

    /**
     * 将歌词对象转换为 LRC 文本
     */
    fun toLrcText(lyrics: Lyrics): String {
        val sb = StringBuilder()
        if (lyrics.offset != 0L) {
            sb.append("[offset:${lyrics.offset}]\n")
        }
        lyrics.lines.forEach { line ->
            val minutes = line.time / 60000
            val seconds = (line.time % 60000) / 1000
            val millis = line.time % 1000
            val text = if (line.wordTimestamps.isNotEmpty()) {
                line.wordTimestamps.joinToString("") { wt ->
                    val wMin = wt.startMs / 60000
                    val wSec = (wt.startMs % 60000) / 1000
                    val wMs = wt.startMs % 1000
                    "<${wMin.toInt()}:${wSec.toInt()}.${(wMs / 10).toInt()}>${wt.word}"
                }
            } else {
                line.text
            }
            sb.append(String.format("[%02d:%02d.%02d]%s\n", minutes, seconds, millis / 10, text))
        }
        return sb.toString()
    }

    /**
     * 获取当前时间对应的歌词行索引
     */
    fun getCurrentLineIndex(lyrics: Lyrics, currentTimeMs: Long): Int {
        if (lyrics.lines.isEmpty()) return -1

        var left = 0
        var right = lyrics.lines.size - 1

        while (left <= right) {
            val mid = (left + right) / 2
            when {
                lyrics.lines[mid].time <= currentTimeMs -> {
                    if (mid == lyrics.lines.size - 1 || lyrics.lines[mid + 1].time > currentTimeMs) {
                        return mid
                    }
                    left = mid + 1
                }
                else -> right = mid - 1
            }
        }

        return -1
    }

    /**
     * 检查文本是否为有效的 LRC 格式
     */
    fun isValidLrc(text: String): Boolean {
        return text.contains(VALID_LRC_REGEX)
    }

    private val OFFSET_REGEX = Regex("\\[offset:(-?\\d+)\\]")
    private val LINE_REGEX = Regex("(\\[\\d{1,2}:\\d{2}\\.\\d{2,3}\\])+(.+)")
    private val TIME_REGEX = Regex("\\[(\\d{1,2}):(\\d{2})\\.(\\d{2,3})\\]")
    private val WORD_TIMESTAMP_REGEX = Regex("<(\\d{1,2}):(\\d{2})\\.(\\d{2,3})>([^<]+)")
    private val VALID_LRC_REGEX = Regex("\\[\\d{1,2}:\\d{2}\\.\\d{2,3}\\]")
}

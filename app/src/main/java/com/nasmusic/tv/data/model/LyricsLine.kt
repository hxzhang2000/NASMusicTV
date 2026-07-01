package com.nasmusic.tv.data.model

/**
 * 歌词高亮模式
 */
enum class LyricsHighlightMode {
    LINE_BY_LINE,  // 逐行高亮
    WORD_BY_WORD   // 逐字高亮
}

/**
 * 单个歌词词条（含时间戳）
 */
data class WordTimestamp(
    val word: String,
    val startMs: Long
)

/**
 * 单行歌词
 */
data class LyricsLine(
    val time: Long,
    val text: String,
    val wordTimestamps: List<WordTimestamp> = emptyList()
)

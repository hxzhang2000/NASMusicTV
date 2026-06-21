package com.nasmusic.tv.util

/**
 * 歌唱家拆分工具
 *
 * 将后端返回的原始 artist 字段（如 "张三 & 李四"）拆分为独立歌唱家列表。
 * 分隔符优先级按长度降序匹配，确保长分隔符优先于短分隔符。
 *
 * 分隔符列表（优先级顺序）：
 * 1. " feat. " — 常见合作标记（需前导空白）
 * 2. " ft. " — feat 的缩写
 * 3. " with " — 英文合作标记
 * 4. "&"、"、"、"/"、"×" — 中英文并列分隔符
 * 5. " vs "、" vs. " — 对唱标记
 */
object ArtistSplitter {

    private val delimiters = listOf(
        Regex("\\s+feat\\.", RegexOption.IGNORE_CASE),
        Regex("\\s+ft\\.", RegexOption.IGNORE_CASE),
        Regex("\\s+with\\s+", RegexOption.IGNORE_CASE),
        Regex("\\s*[&/、×]\\s*"),
        Regex("\\s+vs\\.?\\s+", RegexOption.IGNORE_CASE),
    )

    /**
     * 将原始 artist 字符串拆分为独立歌唱家列表。
     * 如果无法拆分，返回包含原始字符串的单元素列表。
     */
    fun split(artist: String): List<String> {
        if (artist.isBlank()) return emptyList()
        for (delim in delimiters) {
            val parts = artist.split(delim).map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size > 1) return parts.distinct()
        }
        return listOf(artist.trim())
    }

    /**
     * 判断 artist 字段是否包含多个歌唱家
     */
    fun isMultiArtist(artist: String): Boolean = split(artist).size > 1
}

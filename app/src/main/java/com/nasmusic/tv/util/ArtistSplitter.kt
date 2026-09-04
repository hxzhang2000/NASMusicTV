package com.nasmusic.tv.util

import java.text.Normalizer

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
 * 4. "&"、"、"、"/"、"×"、"，"、"＆"、"," 及全角变体（"／"、"；"、"｜"、"＋"）— 各式中英文并列分隔符
 * 5. " + " — 半角加号（需两侧空白，避免误伤含加号的单人艺名）
 * 6. " vs "、" vs. " — 对唱标记
 *
 * 同名判定统一走 [normalizeKey]：NFKC 归一化（全角转半角）+ 首尾 trim +
 * 内部连续空白折叠 + 小写。避免 "古天乐" 与 "古天乐 " / "古天乐／萱萱"
 * 这类肉眼相同、字符串不同的名字被当成两个艺术家。
 */
object ArtistSplitter {

    private val whitespace = Regex("\\s+")

    private val delimiters = listOf(
        Regex("\\s+feat\\.?", RegexOption.IGNORE_CASE),
        Regex("\\s+ft\\.", RegexOption.IGNORE_CASE),
        Regex("\\s+with\\s+", RegexOption.IGNORE_CASE),
        // 中英文并列分隔符（含全角变体：／ ； ｜ ＋）
        Regex("\\s*[&/、×，＆,／;；|｜＋]\\s*"),
        // 半角加号只在两侧有空白时才当分隔符，避免误拆单人艺名
        Regex("\\s+\\+\\s+"),
        Regex("\\s+vs\\.?\\s+", RegexOption.IGNORE_CASE),
    )

    /**
     * 艺术家名归一化键：用于「是否同一艺术家」的判定。
     *
     * - NFKC：全角字符转半角（"ＧＵＴ" → "GUT"）
     * - trim：去掉首尾空白（含全角空格 U+3000）
     * - 折叠内部连续空白为一个半角空格
     * - lowercase：大小写不敏感
     */
    fun normalizeKey(name: String): String {
        if (name.isBlank()) return ""
        return Normalizer.normalize(name, Normalizer.Form.NFKC)
            .replace(whitespace, " ")
            .trim()
            .lowercase()
    }

    /**
     * 将原始 artist 字符串拆分为独立歌唱家列表。
     * 迭代拆分：先用第一个分隔符拆分，再对每个部分用后续分隔符继续拆分。
     * 如果无法拆分，返回包含原始字符串的单元素列表。
     *
     * 返回的是用于展示的名字（原大小写、已 trim）；重名去重按 [normalizeKey] 判定，
     * 保留首次出现的写法。
     */
    fun split(artist: String): List<String> {
        if (artist.isBlank()) return emptyList()
        var results = listOf(artist.trim())
        for (delim in delimiters) {
            results = results.flatMap { part ->
                val sub = part.split(delim).map { it.trim() }.filter { it.isNotBlank() }
                if (sub.size > 1) sub else listOf(part)
            }
        }
        // 按归一化键去重，保留首次出现的展示名
        val seen = linkedSetOf<String>()
        val out = mutableListOf<String>()
        for (part in results) {
            val key = normalizeKey(part)
            if (key.isBlank()) continue
            if (seen.add(key)) out.add(part.trim())
        }
        return out
    }

    /**
     * 判断 artist 字段是否包含多个歌唱家
     */
    fun isMultiArtist(artist: String): Boolean = split(artist).size > 1

    /**
     * 判断某首歌的原始 artist 字段是否属于指定艺术家（归一化比较）。
     *
     * 例如 song.artist = "古天乐/萱萱"、artistName = "古天乐" → true；
     * artistName = "古天乐/萱萱"（未拆分的合唱名）→ false，调用方应先拆分再入库。
     */
    fun containsArtist(rawArtistField: String, artistName: String): Boolean {
        val key = normalizeKey(artistName)
        if (key.isBlank()) return false
        return split(rawArtistField).any { normalizeKey(it) == key }
    }
}

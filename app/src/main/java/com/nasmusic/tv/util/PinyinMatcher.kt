package com.nasmusic.tv.util

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongWithPinyin

/**
 * 拼音匹配工具类
 *
 * 匹配逻辑：子串匹配 OR 拼音全拼匹配 OR 拼音首字母匹配
 * - 中文输入完全保留现有子串匹配行为
 * - 拼音/首字母输入作为额外匹配路径补充
 * - **仅 TV 端启用拼音匹配**（手机端触屏输入汉字方便，无需拼音）
 */
object PinyinMatcher {

    /**
     * 判断关键词是否匹配歌曲（子串 + 拼音全拼 + 拼音首字母）
     *
     * @param song 歌曲对象
     * @param keyword 搜索关键词（已 trim + lowercase）
     * @param isTVDevice 是否为 TV 设备（true=启用拼音匹配，false=仅子串匹配）
     * @param pinyinCache 拼音缓存 Map<songId, SongWithPinyin>（可选，为 null 时实时计算）
     * @return 是否匹配
     */
    fun matches(
        song: Song,
        keyword: String,
        isTVDevice: Boolean,
        pinyinCache: Map<String, SongWithPinyin>? = null
    ): Boolean {
        if (keyword.isBlank()) return false

        // 1. 标准子串匹配（标题/歌手/文件名）——中文输入走这里，完全保留现有行为
        if (matchesSubstring(song, keyword)) return true

        // 2. 非 TV 设备：仅子串匹配，不启用拼音匹配
        if (!isTVDevice) return false

        // 3. 获取拼音信息（优先缓存）
        val pinyin = pinyinCache?.get(song.id)
            ?: SongWithPinyin(song)

        // 4. 拼音全拼匹配
        if (pinyin.pinyin.contains(keyword)) return true
        if (pinyin.artistPinyin.contains(keyword)) return true

        // 5. 拼音首字母匹配
        if (pinyin.initials.contains(keyword)) return true
        if (pinyin.artistInitials.contains(keyword)) return true

        return false
    }

    /**
     * 子串匹配：标题/歌手/文件名包含关键词
     */
    private fun matchesSubstring(song: Song, keyword: String): Boolean {
        return song.title.lowercase().contains(keyword) ||
            song.artist.lowercase().contains(keyword) ||
            song.path?.lowercase()?.contains(keyword) == true
    }

    /**
     * 分词匹配：关键词按空格分割后，匹配任一分词即可
     *
     * 例如: "zjl 周杰" -> 匹配首字母 "zjl" OR 精确子串 "周杰"
     */
    fun matchesMultipleWords(
        song: Song,
        keyword: String,
        isTVDevice: Boolean,
        pinyinCache: Map<String, SongWithPinyin>? = null
    ): Boolean {
        val parts = keyword.trim().split("\\s+".toRegex())
        if (parts.isEmpty()) return false

        // 只要有一个分词匹配即返回 true
        return parts.any { part ->
            matches(song, part, isTVDevice, pinyinCache)
        }
    }
}

package com.nasmusic.tv.util

import com.github.promeg.pinyinhelper.Pinyin

/**
 * 拼音首字母匹配工具
 *
 * 将中文文本转换为拼音首字母（"周杰伦" → "zjl"），支持：
 * 1. 直接子串匹配（输入中文时使用）
 * 2. 拼音首字母匹配（输入拼音首字母时使用）
 *
 * 使用 TinyPinyin 库（不依赖 ICU），兼容 API 22+。
 */
object PinyinUtils {

    /**
     * 获取文本的拼音全拼。
     * 中文字符转换为完整拼音（"周杰伦" → "zhoujielun"），
     * 英文字母和数字原样保留（小写），拼音之间无分隔符。
     */
    fun toPinyin(text: String): String {
        if (text.isBlank()) return ""
        return buildString {
            for (c in text) {
                if (c.code in 0x4E00..0x9FFF) {
                    // CJK 统一表意文字：取完整拼音
                    val py = Pinyin.toPinyin(c)
                    if (py.isNotEmpty()) {
                        append(py.lowercase())
                    }
                } else if (c.isLetterOrDigit()) {
                    append(c.lowercaseChar())
                }
                // 其他字符（空格、标点等）跳过
            }
        }
    }

    /**
     * 获取文本的拼音首字母。
     * 中文字符转换为拼音首字母（"你好" → "nh"），
     * 英文字母和数字原样保留（小写）。
     */
    fun toPinyinInitials(text: String): String {
        if (text.isBlank()) return ""
        return buildString {
            for (c in text) {
                if (c.code in 0x4E00..0x9FFF) {
                    // CJK 统一表意文字：取拼音首字母
                    val py = Pinyin.toPinyin(c)
                    if (py.isNotEmpty()) {
                        append(py.first().lowercaseChar())
                    }
                } else if (c.isLetterOrDigit()) {
                    append(c.lowercaseChar())
                }
                // 其他字符（空格、标点等）跳过
            }
        }
    }

    /**
     * @deprecated Use [toPinyinInitials] instead. Kept for backward compatibility.
     */
    fun getInitials(text: String): String = toPinyinInitials(text)

    /**
     * 判断文本是否匹配查询条件。
     * 匹配规则（任一满足即可）：
     * 1. 文本本身包含查询子串（不区分大小写）
     * 2. 文本的拼音首字母包含查询子串
     */
    fun matches(text: String, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        val t = text.trim().lowercase()

        // 直接子串匹配（含中文匹配）
        if (t.contains(q)) return true

        // 拼音首字母匹配
        val initials = toPinyinInitials(text)
        return initials.isNotEmpty() && initials.contains(q)
    }

    /**
     * 获取文本的分组首字母（用于 A-Z 分组索引）。
     * - 中文：取拼音首字母大写（"周杰伦" → 'Z'）
     * - 英文/字母：首字符大写（"Taylor Swift" → 'T'）
     * - 数字/符号/空：返回 '#'
     *
     * 用于专辑/艺术家列表的 A-Z 分组展示与侧边索引跳转。
     */
    fun getGroupLetter(text: String): Char {
        if (text.isBlank()) return '#'
        val firstChar = text.trim().first()
        return when {
            firstChar.code in 0x4E00..0x9FFF -> {
                // CJK 统一表意文字：取拼音首字母大写
                val py = Pinyin.toPinyin(firstChar)
                if (py.isNotEmpty()) py.first().uppercaseChar() else '#'
            }
            firstChar.isLetter() -> firstChar.uppercaseChar()
            else -> '#'
        }
    }

    /**
     * 索引条所有可能的字母列表（A-Z + #），用于侧边索引条渲染。
     * 实际显示时由调用方过滤掉无数据的字母。
     */
    fun getAllGroupLetters(): List<Char> = ('A'..'Z').toList() + '#'
}

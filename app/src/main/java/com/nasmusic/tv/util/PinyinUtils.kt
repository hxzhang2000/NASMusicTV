package com.nasmusic.tv.util

import android.icu.text.Transliterator
import android.os.Build

/**
 * 拼音首字母匹配工具
 *
 * 将中文文本转换为拼音首字母（"周杰伦" → "zjl"），支持：
 * 1. 直接子串匹配（输入中文时使用）
 * 2. 拼音首字母匹配（输入拼音首字母时使用）
 *
 * 底层使用 android.icu.text.Transliterator（API 24+），
 * 低于 API 24 的设备仅支持直接子串匹配。
 */
object PinyinUtils {

    private val transliterator: Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { Transliterator.getInstance("Han-Latin; Latin-ASCII") } catch (e: Exception) { null }
        } else null
    }

    private const val SPACE_REGEX = "\\s+"

    /**
     * 将文本转换为拼音（API 26+ 使用 Transliterator，低版本 fallback 仅保留 ASCII 字符）。
     */
    fun toPinyin(text: String): String {
        if (text.isBlank()) return ""
        val trans = transliterator
        return if (trans != null) {
            trans.transliterate(text).split(SPACE_REGEX.toRegex()).joinToString("") { it.trim() }
        } else {
            // 低版本 fallback：仅保留 ASCII 字符
            text.filter { it.isLetterOrDigit() || it.isWhitespace() }
        }
    }

    /**
     * 获取文本的拼音首字母。
     * 中文字符转换为拼音首字母（"你好" → "nh"），
     * 英文字母和数字原样保留（小写）。
     */
    fun getInitials(text: String): String {
        if (text.isBlank() || Build.VERSION.SDK_INT < 24) return ""
        return try {
            val pinyin = android.icu.text.Transliterator
                .getInstance("Han-Latin/Names")
                .transliterate(text)
            if (pinyin.isBlank()) return ""
            pinyin.trim().split("\\s+".toRegex()).joinToString("") { seg ->
                seg.firstOrNull()?.let { firstChar ->
                    val c = firstChar.lowercaseChar()
                    if (c in 'a'..'z' || c in '0'..'9') c.toString() else ""
                } ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

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
        val initials = getInitials(text)
        val fullPinyin = toPinyin(text)
        android.util.Log.d("NASMusic", "PinyinUtils.matches: text='$text', query='$q', initials='$initials', fullPinyin='$fullPinyin'")
        return (initials.isNotEmpty() && initials.contains(q)) ||
               (fullPinyin.isNotEmpty() && fullPinyin.lowercase().contains(q))
    }
}

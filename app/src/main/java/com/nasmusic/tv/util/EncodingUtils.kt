package com.nasmusic.tv.util

import java.nio.charset.Charset

/**
 * 字符串编码修复工具
 * 处理后端返回的乱码模式：
 * 1. 字符串中任意位置的 U+FFFD（GBK 被当作 UTF-8 解码）
 * 2. 末尾的 �?（U+FFFD + ?）
 * 3. GB2312/GBK 字节被当作 Latin-1 解码（如 ÎÒÊÇÕæµÄ°®Äã）
 */
object EncodingUtils {

    /**
     * 修复字符串编码问题
     * 处理乱码模式：
     * 1. 字符串中任意位置的 U+FFFD（GBK 被当作 UTF-8 解码）
     * 2. 末尾的 �?（U+FFFD + ?）
     * 3. GB2312/GBK 字节被当作 Latin-1 解码（如 ÎÒÊÇÕæµÄ°®Äã）
     */
    fun fixEncoding(text: String?): String? {
        if (text.isNullOrBlank()) return text

        var fixed: String = text

        // 第一步：检测并处理字符串中任意位置的 U+FFFD（替换字符）
        // 原因：GBK 字节被当作 UTF-8 解码时，部分无效序列变成 U+FFFD，
        // 部分字节恰好形成合法 UTF-8（如 κ=U+03BA, л=U+043B）
        // 正确做法：用 UTF-8 编码回字节，再用 GBK 解码
        if ('\uFFFD' in text) {
            try {
                val rawBytes = text.toByteArray(Charsets.UTF_8)
                val gbkDecoded = String(rawBytes, Charset.forName("GBK"))
                // 如果 GBK 解码后包含中文字符（CJK 统一表意文字），
                // 且不再有 U+FFFD，说明 GBK 解码正确
                if ('\uFFFD' !in gbkDecoded && gbkDecoded.any { it.code in 0x4E00..0x9FFF }) {
                    AppLog.d("EncodingUtils", "fixEncoding: U+FFFD GBK fallback: '${fixed.take(30)}' -> '${gbkDecoded.take(30)}'")
                    return gbkDecoded
                }
            } catch (_: Exception) { }
        }

        // 第二步：移除末尾的乱码模式：�?（U+FFFD + ?）或单独的 ?
        while (fixed.endsWith("?") || fixed.endsWith("\uFFFD?") || fixed.endsWith("\uFFFD")) {
            if (fixed.endsWith("\uFFFD?")) {
                fixed = fixed.dropLast(2) // 移除 U+FFFD 和 ?
            } else {
                fixed = fixed.dropLast(1)
            }
        }

        // 第三步：检测 GB2312/GBK 编码被当作 Latin-1 解码的情况
        // 特征：字符串包含大量 Latin-1 扩展字符（0x80-0xFF 范围）
        val latin1Count = fixed.count { it.code in 0x80..0xFF }
        val totalCount = fixed.length

        // 如果超过 30% 的字符是 Latin-1 扩展字符，尝试从 Latin-1 转换到 GB2312
        if (latin1Count > 0 && latin1Count.toFloat() / totalCount > 0.3f) {
            try {
                val bytes = fixed.toByteArray(Charsets.ISO_8859_1)
                val decoded = String(bytes, charset("GB2312"))
                if (decoded.any { it.code in 0x4E00..0x9FFF }) {
                    AppLog.d("EncodingUtils", "fixEncoding: converted Latin-1 to GB2312: '$fixed' -> '$decoded'")
                    fixed = decoded
                }
            } catch (e: Exception) {
                try {
                    val bytes = fixed.toByteArray(Charsets.ISO_8859_1)
                    val decoded = String(bytes, charset("GBK"))
                    if (decoded.any { it.code in 0x4E00..0x9FFF }) {
                        AppLog.d("EncodingUtils", "fixEncoding: converted Latin-1 to GBK: '$fixed' -> '$decoded'")
                        fixed = decoded
                    }
                } catch (e2: Exception) { }
            }
        }

        if (fixed.isBlank()) return text
        if (fixed != text) {
            AppLog.d("EncodingUtils", "fixEncoding: result: '${text.take(30)}' -> '${fixed.take(30)}'")
        }

        return fixed
    }
}

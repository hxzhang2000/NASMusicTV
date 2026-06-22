package com.nasmusic.tv.util

/**
 * 字符串编码修复工具
 * 处理后端返回的乱码模式：
 * 1. 末尾的 �?（U+FFFD + ?）
 * 2. GB2312/GBK 字节被当作 Latin-1 解码（如 ÎÒÊÇÕæµÄ°®Äã）
 */
object EncodingUtils {

    /**
     * 修复字符串编码问题
     * 处理乱码模式：
     * 1. 末尾的 �?（U+FFFD + ?）
     * 2. GB2312/GBK 字节被当作 Latin-1 解码（如 ÎÒÊÇÕæµÄ°®Äã）
     */
    fun fixEncoding(text: String?): String? {
        if (text.isNullOrBlank()) return text

        // 第一步：移除末尾的乱码模式：�?（U+FFFD + ?）或单独的 ?
        var fixed: String = text
        while (fixed.endsWith("?") || fixed.endsWith("\uFFFD?") || fixed.endsWith("\uFFFD")) {
            if (fixed.endsWith("\uFFFD?")) {
                fixed = fixed.dropLast(2) // 移除 U+FFFD 和 ?
            } else {
                fixed = fixed.dropLast(1)
            }
        }

        // 第二步：检测 GB2312/GBK 编码被当作 Latin-1 解码的情况
        // 特征：字符串包含大量 Latin-1 扩展字符（0x80-0xFF 范围）
        val latin1Count = fixed.count { it.code in 0x80..0xFF }
        val totalCount = fixed.length

        // 如果超过 30% 的字符是 Latin-1 扩展字符，尝试从 Latin-1 转换到 GB2312
        if (latin1Count > 0 && latin1Count.toFloat() / totalCount > 0.3f) {
            try {
                // 将字符串当作 Latin-1 编码的字节
                val bytes = fixed.toByteArray(Charsets.ISO_8859_1)
                // 尝试用 GB2312 解码
                val decoded = String(bytes, charset("GB2312"))
                // 如果解码后包含中文字符，使用这个结果
                if (decoded.any { it.code in 0x4E00..0x9FFF }) {
                    AppLog.d("EncodingUtils", "fixEncoding: converted Latin-1 to GB2312: '$fixed' -> '$decoded'")
                    fixed = decoded
                }
            } catch (e: Exception) {
                // GB2312 解码失败，尝试 GBK
                try {
                    val bytes = fixed.toByteArray(Charsets.ISO_8859_1)
                    val decoded = String(bytes, charset("GBK"))
                    if (decoded.any { it.code in 0x4E00..0x9FFF }) {
                        AppLog.d("EncodingUtils", "fixEncoding: converted Latin-1 to GBK: '$fixed' -> '$decoded'")
                        fixed = decoded
                    }
                } catch (e2: Exception) {
                    // 都失败了，保持原样
                }
            }
        }

        // 如果移除后变为空，返回原字符串
        if (fixed.isBlank()) return text

        // 如果有变化，记录日志
        if (fixed != text) {
            AppLog.d("EncodingUtils", "fixEncoding: result: '${text.take(30)}' -> '${fixed.take(30)}'")
        }

        return fixed
    }
}

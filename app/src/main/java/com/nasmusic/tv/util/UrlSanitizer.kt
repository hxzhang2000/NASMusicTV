package com.nasmusic.tv.util

/**
 * F-1（重构方案 2026-09）：日志凭证脱敏公共函数。
 *
 * AppLog 的 w/e 级别在 release 构建仍输出（proguard 只剥离 android.util.Log.d/v），
 * 任何含 URL 的 w/e 日志必须先过本函数，替换以下查询参数的值：
 *   api_key（Jellyfin token）、token（通用）、access_token（百度/OAuth），
 *   t=（Subsonic md5(password+salt)）、s=（salt）、password、u=（用户名）、p=（密码）。
 *
 * 依据 DaoliyuAdapter/BaiduPanApi 既有 sanitizeUrl 实现统一收编为公共 util。
 */
object UrlSanitizer {

    /** 敏感查询参数名（小写匹配） */
    private val SENSITIVE_PARAMS = setOf(
        "api_key", "token", "access_token", "t", "s", "u", "p", "password", "apikey"
    )

    /** 传入空/空串安全返回 */
    fun sanitize(url: String?): String {
        if (url.isNullOrBlank()) return url ?: ""
        val queryStart = url.indexOf('?')
        if (queryStart < 0 || queryStart == url.length - 1) {
            return url
        }
        val prefix = url.substring(0, queryStart + 1)
        val query = url.substring(queryStart + 1)
        val fragmentIdx = query.indexOf('#')
        val fragment = if (fragmentIdx >= 0) query.substring(fragmentIdx) else ""
        val pairs = (if (fragmentIdx >= 0) query.substring(0, fragmentIdx) else query)
            .split('&')
        val sb = StringBuilder(prefix)
        for ((i, pair) in pairs.withIndex()) {
            if (pair.isBlank()) continue
            if (i > 0 && sb.length > prefix.length) sb.append('&')
            val eq = pair.indexOf('=')
            if (eq < 0) {
                sb.append(pair)
            } else {
                val key = pair.substring(0, eq)
                if (key.lowercase() in SENSITIVE_PARAMS) {
                    sb.append(key).append("=***")
                } else {
                    sb.append(pair)
                }
            }
        }
        return sb.toString() + fragment
    }
}

package com.nasmusic.tv.backend.impl

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLEncoder

/**
 * 飞牛音乐（fnOS）服务地址归一化与端点拼装
 *
 * 协议依据：参考项目 fn-music-tv `core/data/.../server/ServerUrlNormalizer.kt`
 * 与 `.../api/TrimMusicApi.kt`（详见 docs/feiniu-backend-improvement-plan.md）。
 *
 * 三件事：
 * 1. 把用户手填的任意形态地址归一化为 canonical API 基址
 *    `<scheme>://<host>:<port>/music/api/v1/`（**尾部带斜杠**）；
 * 2. 补全默认端口（fnOS 音乐服务 HTTP 5666 / HTTPS 5667）；
 * 3. 统一拼装端点与查询参数（含 URL 编码）。
 *
 * ⚠️ 与参考项目的一处有意偏离：参考项目对「显式 http:// 但无端口」补 80、
 *    「显式 https 无端口」补 443。本实现统一补 5666 / 5667 —— 本适配器是
 *    **飞牛音乐专用**，用户填 `http://192.168.1.100` 指的一定是音乐服务而非
 *    80 端口的 Web 管理页。显式端口永远优先，不影响高级用法。
 */
object FeiniuUrl {

    /** fnOS 音乐服务默认 HTTP 端口 */
    const val DEFAULT_HTTP_PORT = 5666

    /** fnOS 音乐服务默认 HTTPS 端口 */
    const val DEFAULT_HTTPS_PORT = 5667

    /** 音乐 API 路径前缀（规范化后 apiBase 的结尾部分，带首尾斜杠） */
    const val API_PATH = "/music/api/v1/"

    /** 归一化失败时返回的空串（调用方需判空） */
    private const val INVALID = ""

    /**
     * 归一化用户输入的服务器地址为 canonical API 基址。
     *
     * 可接受的输入示例：
     * - `192.168.1.100`            → `http://192.168.1.100:5666/music/api/v1/`
     * - `http://192.168.1.100`     → `http://192.168.1.100:5666/music/api/v1/`
     * - `http://192.168.1.100:5666`→ `http://192.168.1.100:5666/music/api/v1/`
     * - `http://host/music`        → `http://host:5666/music/api/v1/`
     * - `http://host/music/api/v1` → `http://host:5666/music/api/v1/`
     * - `https://host`             → `https://host:5667/music/api/v1/`
     *
     * @return canonical API 基址（结尾带 `/`）；输入非法时返回空串
     */
    fun normalize(input: String): String {
        val value = input.trim()
        if (value.isEmpty()) return INVALID

        // 仅接受 http/https，缺失 scheme 时按 http 处理
        val schemeMatch = Regex("^(https?)://", RegexOption.IGNORE_CASE).find(value)
        if (schemeMatch == null) {
            val bare = value.trimEnd('/')
            if (bare.isEmpty()) return INVALID
            val explicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").find(bare)?.value
            // 其他协议（ftp:// 等）一律拒绝
            if (explicitScheme != null) return INVALID
        }
        val candidate = if (schemeMatch == null) "http://${value.trimEnd('/')}" else value

        val parsed = candidate.toHttpUrlOrNull() ?: return INVALID
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return INVALID

        val isHttps = parsed.scheme == "https"
        // HttpUrl.port 会把缺省端口也解析出来（80/443），无法区分"是否显式指定"，
        // 因此用 URI 判断——URI.port 在未显式指定时返回 -1。
        val explicitPort = runCatching { URI(candidate).port }.getOrDefault(-1)
        val port = when {
            explicitPort >= 0 -> explicitPort
            isHttps -> DEFAULT_HTTPS_PORT
            else -> DEFAULT_HTTP_PORT
        }

        val path = parsed.encodedPath.trimEnd('/')
        val prefix = when {
            path.endsWith("/music/api/v1") -> path
            path.endsWith("/music") -> "$path/api/v1"
            path.isEmpty() || path == "/" -> "/music/api/v1"
            else -> "$path/music/api/v1"
        }

        // IPv6 字面量需加方括号
        val host = if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host
        return "${parsed.scheme}://$host:$port$prefix/"
    }

    /**
     * 拼装完整端点 URL。
     *
     * @param apiBase 已归一化的基址（结尾可带可不带 `/`）
     * @param path 相对路径，如 `track/list`（前置 `/` 会被忽略）
     * @param query 查询参数，值为 null 的项自动跳过
     */
    fun endpoint(apiBase: String, path: String, vararg query: Pair<String, Any?>): String {
        val base = if (apiBase.endsWith("/")) apiBase else "$apiBase/"
        val sb = StringBuilder(base).append(path.removePrefix("/"))
        val params = query.filter { it.second != null }
        if (params.isNotEmpty()) {
            sb.append('?')
            params.forEachIndexed { index, (name, rawValue) ->
                if (index > 0) sb.append('&')
                sb.append(name).append('=').append(URLEncoder.encode(rawValue.toString(), "UTF-8"))
            }
        }
        return sb.toString()
    }

    /**
     * 封面地址：`static/cover?coverId=<id>&size=<px>`
     *
     * 飞牛按 **coverId** 取封面，不是按曲目 ID —— 这是与常见 NAS 后端最大的差异之一。
     * @return 完整 URL；coverId 为空时返回 null（交由 UI 降级占位图）
     */
    fun coverUrl(apiBase: String, coverId: String?, size: Int = DEFAULT_COVER_SIZE): String? {
        if (coverId.isNullOrBlank()) return null
        return endpoint(apiBase, "static/cover", "coverId" to coverId, "size" to size)
    }

    /** 播放流地址：`track/stream?guid=<guid>`（guid 是查询参数，不是路径段） */
    fun streamUrl(apiBase: String, guid: String): String =
        endpoint(apiBase, "track/stream", "guid" to guid)

    /** 取 apiBase 的 host（用于认证头的 host 白名单匹配）；解析失败返回空串 */
    fun hostOf(apiBase: String): String =
        apiBase.toHttpUrlOrNull()?.host ?: ""

    private const val DEFAULT_COVER_SIZE = 512
}

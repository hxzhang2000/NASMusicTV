package com.nasmusic.tv.backend

import com.nasmusic.tv.util.AppLog

/**
 * 当前后端认证头的进程级单例（供播放 / 封面链路消费）
 *
 * ## 为什么需要它
 *
 * `BackendAdapter.streamHeaders` 定义了"播放流需要注入的 HTTP 头"，但历史上
 * **没有任何消费方**：Jellyfin 把 `api_key` 拼在 URL query 里、Navidrome 用
 * Subsonic 的 `u/p/t` 参数，凭据都在 URL 上，所以不需要请求头。
 *
 * 飞牛音乐（fnOS）是第一个必须走请求头的后端 —— 其 `track/stream` 与
 * `static/cover` 都要求 `Authorization: <userToken>`。若不打通这条链路，
 * 播放与封面会全部 401。
 *
 * ## 消费方
 *
 * `BaiduHttpDataSourceFactory` 的拦截器在发起请求前调用 [forHost]：
 * 该方法同时服务于 ExoPlayer（播放）与 Coil（封面），一次注入覆盖两条链路。
 *
 * ## provider 形态（2026-09-15 修复 F-2）
 *
 * 快照形态会漏掉「静默重登换新令牌」：adapter 内部换新后快照仍是旧值，
 * 播放 / 封面会持续 401 直到重新连接。因此这里存的是 **provider**
 * （每次请求实时读取 `adapter.streamHeaders`），令牌刷新即时生效。
 *
 * ## 安全底线
 *
 * **只在 host 精确匹配时注入**。飞牛的流地址可能 302 到 CDN 或其他域名，
 * 令牌绝不能随重定向泄漏到第三方。非后端域名一律返回空 Map。
 * （跨域重定向另由 OkHttp 自带逻辑剥离 Authorization，双保险。）
 */
object BackendAuthHeaders {

    private const val TAG = "BackendAuthHeaders"

    private val lock = Any()

    /** 认证头实时提供者；未连接时为 null */
    @Volatile
    private var provider: (() -> Map<String, String>)? = null

    /** 认证头允许注入的 host（精确匹配）；未连接时为空串，任何 host 都不匹配 */
    @Volatile
    private var host: String = ""

    /**
     * 绑定认证头 provider（BackendRegistry 连接成功后调用）
     *
     * @param newProvider 返回当前认证头；每次请求实时调用
     * @param newHost 允许注入的 host（精确匹配）。为空串表示不注入任何请求
     */
    fun update(newProvider: () -> Map<String, String>, newHost: String) {
        synchronized(lock) {
            provider = if (newHost.isBlank()) null else newProvider
            host = newHost
        }
        // 只记 host，不触达任何令牌值
        AppLog.d(TAG, "update: host=$newHost")
    }

    /** 解绑（断开连接 / 登出时调用） */
    fun clear() {
        synchronized(lock) {
            provider = null
            host = ""
        }
        AppLog.d(TAG, "clear")
    }

    /**
     * 取该 host 应注入的认证头（每次调用实时读取 provider）。
     *
     * @return host 精确匹配且已连接时返回当前认证头，否则返回空 Map
     */
    fun forHost(candidateHost: String): Map<String, String> {
        if (candidateHost.isBlank()) return emptyMap()
        val currentHost = host
        if (currentHost.isBlank() || !currentHost.equals(candidateHost, ignoreCase = true)) return emptyMap()
        val currentProvider = provider ?: return emptyMap()
        return try {
            currentProvider()
        } catch (e: Exception) {
            AppLog.w(TAG, "provider failed: ${e.message}")
            emptyMap()
        }
    }

    /** 当前是否已配置认证头（调试/自检用，不含敏感值） */
    fun isConfigured(): Boolean = host.isNotBlank() && provider?.invoke()?.isNotEmpty() == true
}

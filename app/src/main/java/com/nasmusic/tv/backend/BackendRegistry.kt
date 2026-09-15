package com.nasmusic.tv.backend

import android.content.Context
import com.nasmusic.tv.backend.impl.DaoliyuAdapter
import com.nasmusic.tv.backend.impl.FeiniuAdapter
import com.nasmusic.tv.backend.impl.JellyfinAdapter
import com.nasmusic.tv.backend.impl.NavidromeAdapter
import com.nasmusic.tv.backend.impl.SubsonicAdapter
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit

/**
 * 后端注册中心
 * 管理并创建不同类型的后端适配器
 */
/**
 * 后端注册中心
 *
 * @param appContext 应用上下文（Application，不会泄漏）。仅用于极少数需要持久化
 *                   设备标识的适配器（如飞牛的 deviceId）；可为 null，此时适配器退化为
 *                   进程内稳定值。
 */
class BackendRegistry(private val appContext: Context? = null) {

    private val TYPE_JELLYFIN = ServerConfig.TYPE_JELLYFIN
    private val TYPE_NAVIDROME = ServerConfig.TYPE_NAVIDROME
    private val TYPE_SUBSONIC = ServerConfig.TYPE_SUBSONIC
    private val TYPE_DAOLIYU = ServerConfig.TYPE_DAOLIYU
    private val TYPE_FEINIU = ServerConfig.TYPE_FEINIU

    companion object {
        /**
         * R-6（方案A）：跨适配器共享的 OkHttp 资源。
         *
         * 历史问题：5 个适配器各自 lazy 创建独立 OkHttpClient（独立连接池 + dispatcher 线程池），
         * 后端切换频繁时新旧适配器短暂共存，累积多套线程池导致电视 WiFi 栈过载（见 close() 注释）。
         *
         * 共享后：
         * - [sharedConnectionPool]：5 空闲连接、5 分钟 keep-alive，所有适配器复用
         * - [sharedDaemonExecutor]：守护线程的缓存线程池（防 OkHttp 非守护线程阻止进程退出）
         * - [sharedDispatcher]：全局并发上限（maxRequests=16 / perHost=8）
         *
         * ⚠️ 适配器 close() 必须【禁止】调用 executorService.shutdown() / evictAll()
         * （会废掉全局线程池/清掉其他适配器的连接），只清理自身认证态。
         */
        internal val sharedDaemonExecutor: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newCachedThreadPool { r ->
                Thread(r, "NAS-OkHttp-Shared").apply { isDaemon = true }
            }

        internal val sharedConnectionPool: ConnectionPool = ConnectionPool(
            maxIdleConnections = 5,
            keepAliveDuration = 5,
            timeUnit = TimeUnit.MINUTES
        )

        internal val sharedDispatcher: okhttp3.Dispatcher = okhttp3.Dispatcher(sharedDaemonExecutor).apply {
            maxRequests = 16
            maxRequestsPerHost = 8
        }
    }

    private val lock = Any()
    private var currentAdapter: BackendAdapter? = null
    private var currentConfig: ServerConfig? = null
    private var serverDisplayName: String = ""

    /**
     * 获取所有支持的后端类型
     */
    val supportedTypes: List<String> get() = listOf(TYPE_JELLYFIN, TYPE_NAVIDROME, TYPE_SUBSONIC, TYPE_DAOLIYU, TYPE_FEINIU)

    /**
     * 初始化后端连接
     */
    suspend fun initialize(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        val adapter = when (config.backendType) {
            TYPE_JELLYFIN -> JellyfinAdapter()
            TYPE_NAVIDROME -> NavidromeAdapter()
            TYPE_SUBSONIC -> SubsonicAdapter()
            TYPE_DAOLIYU -> DaoliyuAdapter()
            TYPE_FEINIU -> FeiniuAdapter(appContext)
            else -> return@withContext false
        }

        // F-1：username 不落日志（debug 亦脱敏），只记布尔存在性
        AppLog.d("BackendRegistry", "initialize: type=${config.backendType}, baseUrl=${UrlSanitizer.sanitize(config.baseUrl)}, hasUser=${config.username.isNotEmpty()}, hasPw=${config.password.isNotEmpty()}, hasToken=${config.apiToken.isNotEmpty()}")

        val success = try {
            val ok = adapter.initialize(
                baseUrl = config.baseUrl,
                apiToken = config.apiToken,
                username = config.username,
                password = config.password
            )
            AppLog.d("BackendRegistry", "initialize: result=$ok")
            ok
        } catch (e: Exception) {
            AppLog.e("BackendRegistry", "initialize: exception during adapter.initialize()", e)
            try { adapter.close() } catch (_: Exception) {}
            false
        }

        if (success) {
            // 替换旧 adapter：先提取旧对象（锁内），再释放资源（锁外）
            // 关键：必须释放旧 adapter 的 OkHttp 资源（连接池 + dispatcher 线程池），
            // 否则网络重连风暴会累积多套 OkHttpClient，导致电视 WiFi 栈过载。
            // JellyfinAdapter.close() / NavidromeAdapter.close() 负责实际的 shutdown + evictAll。
            val oldAdapter = synchronized(lock) {
                val old = currentAdapter
                currentAdapter = adapter
                currentConfig = config
                serverDisplayName = adapter.serverName
                old
            }
            if (oldAdapter != null) {
                AppLog.d("BackendRegistry", "initialize: replacing existing adapter, releasing old one")
                releaseAdapter(oldAdapter)
            }
            // 同步播放 / 封面链路的认证头（飞牛音乐等需要 Authorization 的后端）。
            // 其他后端 streamHeaders 为空 Map，注入后行为不变。
            BackendAuthHeaders.update({ adapter.streamHeaders }, hostOf(config.baseUrl))
        } else {
            try { adapter.close() } catch (_: Exception) {}
        }

        success
    }

    /**
     * 取 URL 的 host，用于认证头的 host 白名单匹配。
     *
     * 采用 URI 解析并按需补 scheme（`toHttpUrl` 对无 scheme 的输入会失败，
     * 而用户配置里 `192.168.1.100` 这种写法是合法的）。
     */
    private fun hostOf(url: String): String {
        val host = hostOfUrl(url)
        if (host.isEmpty() && url.isNotBlank()) {
            AppLog.d("BackendRegistry", "hostOf: cannot parse host, url omitted")
        }
        return host
    }

    /**
     * 获取当前活动的后端适配器
     */
    fun getAdapter(): BackendAdapter? = synchronized(lock) { currentAdapter }

    /**
     * 获取当前配置
     */
    fun getConfig(): ServerConfig? = synchronized(lock) { currentConfig }

    /**
     * 获取服务端显示名称
     */
    fun getServerDisplayName(): String = synchronized(lock) { serverDisplayName }

    /**
     * 是否已连接
     */
    fun isConnected(): Boolean = synchronized(lock) { currentAdapter != null }

    /**
     * 断开连接并释放网络资源
     */
    suspend fun disconnect() {
        val adapter = synchronized(lock) {
            val a = currentAdapter
            currentAdapter = null
            currentConfig = null
            serverDisplayName = ""
            a
        }
        // 断开后必须清空认证头，否则旧令牌会继续注入到播放 / 封面请求
        BackendAuthHeaders.clear()
        adapter?.let { releaseAdapter(it) }
    }

    /**
     * 测试连接（不改变当前连接状态）
     * 返回 Pair(是否成功, 服务器名称/错误信息)
     */
    suspend fun testConnection(config: ServerConfig): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val adapter = when (config.backendType) {
            TYPE_JELLYFIN -> JellyfinAdapter()
            TYPE_NAVIDROME -> NavidromeAdapter()
            TYPE_SUBSONIC -> SubsonicAdapter()
            TYPE_DAOLIYU -> DaoliyuAdapter()
            TYPE_FEINIU -> FeiniuAdapter(appContext)
            else -> return@withContext Pair(false, "不支持的后端类型")
        }

        val success = adapter.initialize(
            baseUrl = config.baseUrl,
            apiToken = config.apiToken,
            username = config.username,
            password = config.password
        )

        if (success) {
            val serverName = adapter.serverName
            // 登出释放临时 session 防止泄漏，结果不影响返回值
            try { adapter.logout() } catch (e: Exception) { AppLog.w("BackendRegistry", "testConnection: logout failed", e) }
            // 关闭连接池防止连接泄漏
            try { adapter.close() } catch (e: Exception) { AppLog.w("BackendRegistry", "testConnection: close failed", e) }
            Pair(true, serverName)
        } else {
            // 即使失败也尝试 logout（部分 Jellyfin 可能已创建 session）
            try { adapter.logout() } catch (e: Exception) { AppLog.w("BackendRegistry", "testConnection failed: logout", e) }
            try { adapter.close() } catch (e: Exception) { AppLog.w("BackendRegistry", "testConnection failed: close", e) }
            Pair(false, "连接失败，请检查地址和凭据")
        }
    }

    /**
     * 释放适配器资源（logout + close），不操作锁状态
     *
     * 必须在 IO 线程执行，避免 [disconnect] 从 Main dispatcher（viewModelScope.launch）
     * 调用时在 [kotlinx.coroutines.runBlocking] 中阻塞主线程。
     */
    private suspend fun releaseAdapter(adapter: BackendAdapter) = withContext(Dispatchers.IO) {
        try {
            adapter.logout()
        } catch (e: Exception) {
            AppLog.w("BackendRegistry", "releaseAdapter: logout failed", e)
        }
        try {
            adapter.close()
        } catch (e: Exception) {
            AppLog.w("BackendRegistry", "releaseAdapter: close failed", e)
        }
    }

    /**
     * 获取后端类型的友好名称
     */
    fun getTypeName(type: String): String = when (type) {
        TYPE_JELLYFIN -> "Jellyfin"
        TYPE_NAVIDROME -> "Navidrome"
        TYPE_SUBSONIC -> "Subsonic"
        TYPE_DAOLIYU -> "道理鱼音乐"
        TYPE_FEINIU -> "飞牛音乐"
        else -> type
    }
}

/**
 * 从用户输入的服务器地址解析 host（lenient：无 scheme 时补 `http://`）。
 *
 * 关键差异：IPv6 字面量需**剥掉方括号** —— `java.net.URI.getHost()` 返回
 * `[2001:db8::1]`，而 OkHttp 请求的 `url.host` 是 `2001:db8::1`；不剥离会
 * 导致认证头 host 匹配永不命中（播放 / 封面 401，2026-09-15 修复 F-3）。
 * 解析失败返回空串。
 */
internal fun hostOfUrl(url: String): String {
    if (url.isBlank()) return ""
    val withScheme = if (url.contains("://")) url else "http://$url"
    return try {
        java.net.URI(withScheme).host?.removeSurrounding("[", "]") ?: ""
    } catch (e: Exception) {
        ""
    }
}

package com.nasmusic.tv.backend

import com.nasmusic.tv.backend.impl.JellyfinAdapter
import com.nasmusic.tv.backend.impl.NavidromeAdapter
import com.nasmusic.tv.data.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后端注册中心
 * 单例，管理并创建不同类型的后端适配器
 */
object BackendRegistry {

    private const val TYPE_JELLYFIN = ServerConfig.TYPE_JELLYFIN
    private const val TYPE_NAVIDROME = ServerConfig.TYPE_NAVIDROME

    private var currentAdapter: BackendAdapter? = null
    private var currentConfig: ServerConfig? = null
    private var serverDisplayName: String = ""

    /**
     * 获取所有支持的后端类型
     */
    val supportedTypes: List<String> get() = listOf(TYPE_JELLYFIN, TYPE_NAVIDROME)

    /**
     * 初始化后端连接
     */
    suspend fun initialize(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        val adapter = when (config.backendType) {
            TYPE_JELLYFIN -> JellyfinAdapter()
            TYPE_NAVIDROME -> NavidromeAdapter()
            else -> return@withContext false
        }

        android.util.Log.d("BackendRegistry", "initialize: type=${config.backendType}, baseUrl=${config.baseUrl}, username=${config.username}, hasPw=${config.password.isNotEmpty()}, hasToken=${config.apiToken.isNotEmpty()}")
        val success = adapter.initialize(
            baseUrl = config.baseUrl,
            apiToken = config.apiToken,
            username = config.username,
            password = config.password
        )
        android.util.Log.d("BackendRegistry", "initialize: result=$success")

        if (success) {
            currentAdapter = adapter
            currentConfig = config
            serverDisplayName = when (adapter) {
                is JellyfinAdapter -> adapter.getServerName()
                is NavidromeAdapter -> adapter.getServerName()
                else -> config.backendType.uppercase()
            }
        }

        success
    }

    /**
     * 获取当前活动的后端适配器
     */
    fun getAdapter(): BackendAdapter? = currentAdapter

    /**
     * 获取当前配置
     */
    fun getConfig(): ServerConfig? = currentConfig

    /**
     * 获取服务端显示名称
     */
    fun getServerDisplayName(): String = serverDisplayName

    /**
     * 是否已连接
     */
    fun isConnected(): Boolean = currentAdapter != null

    /**
     * 断开连接并释放网络资源
     */
    suspend fun disconnect() {
        currentAdapter?.let { adapter ->
            try {
                adapter.logout()
            } catch (_: Exception) {}
            try {
                adapter.close()
            } catch (_: Exception) {}
        }
        currentAdapter = null
        currentConfig = null
        serverDisplayName = ""
    }

    /**
     * 测试连接（不改变当前连接状态）
     * 返回 Pair(是否成功, 服务器名称/错误信息)
     */
    suspend fun testConnection(config: ServerConfig): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val adapter = when (config.backendType) {
            TYPE_JELLYFIN -> JellyfinAdapter()
            TYPE_NAVIDROME -> NavidromeAdapter()
            else -> return@withContext Pair(false, "不支持的后端类型")
        }

        val success = adapter.initialize(
            baseUrl = config.baseUrl,
            apiToken = config.apiToken,
            username = config.username,
            password = config.password
        )

        if (success) {
            val serverName = when (adapter) {
                is JellyfinAdapter -> adapter.getServerName()
                is NavidromeAdapter -> adapter.getServerName()
                else -> config.backendType.uppercase()
            }
            // 登出释放临时 session 防止泄漏，结果不影响返回值
            try { adapter.logout() } catch (_: Exception) {}
            // 关闭连接池防止连接泄漏
            try { adapter.close() } catch (_: Exception) {}
            Pair(true, serverName)
        } else {
            // 即使失败也尝试 logout（部分 Jellyfin 可能已创建 session）
            try { adapter.logout() } catch (_: Exception) {}
            try { adapter.close() } catch (_: Exception) {}
            Pair(false, "连接失败，请检查地址和凭据")
        }
    }

    /**
     * 获取后端类型的友好名称
     */
    fun getTypeName(type: String): String = when (type) {
        TYPE_JELLYFIN -> "Jellyfin"
        TYPE_NAVIDROME -> "Navidrome"
        else -> type
    }
}

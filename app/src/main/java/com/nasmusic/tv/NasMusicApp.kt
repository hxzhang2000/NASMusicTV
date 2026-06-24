package com.nasmusic.tv

import android.app.Application
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.network.MetingApiService
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.player.PlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application 类 — 手动 DI 容器
 * 持有所有全局单例实例
 */
class NasMusicApp : Application() {

    lateinit var backendRegistry: BackendRegistry
        private set
    lateinit var appPreferences: AppPreferences
        private set
    lateinit var playerManager: PlayerManager
        private set
    lateinit var networkMusicManager: NetworkMusicManager
        private set

    /**
     * 应用级协程作用域，用于 onDestroy 等生命周期之后的异步操作
     * 使用 SupervisorJob 确保子协程失败不会取消其他子协程
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        appPreferences = AppPreferences(this)
        backendRegistry = BackendRegistry()
        playerManager = PlayerManager()
        // 网络音乐管理器：注册所有网络源，默认源与 Meting 端点均由 AppSettings 动态提供
        val services = mapOf(
            "meting" to MetingApiService(
                baseUrlProvider = { appPreferences.getMetingApiBaseUrlSync() }
            )
        )
        networkMusicManager = NetworkMusicManager(
            services = services,
            defaultSourceProvider = { appPreferences.getDefaultNetworkSourceSync() }
        )
    }

    companion object {
        lateinit var instance: NasMusicApp
            private set
    }
}

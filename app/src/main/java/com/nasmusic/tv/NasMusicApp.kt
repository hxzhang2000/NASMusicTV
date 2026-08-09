package com.nasmusic.tv

import android.app.Application
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.network.MetingApiService
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.backend.network.mv.BilibiliMvService
import com.nasmusic.tv.backend.network.mv.MvSearchManager
import com.nasmusic.tv.backend.network.mv.MvPersistentCache
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    lateinit var mvSearchManager: MvSearchManager
        private set

    /**
     * 应用级协程作用域，用于 onDestroy 等生命周期之后的异步操作
     * 使用 SupervisorJob 确保子协程失败不会取消其他子协程
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appPreferences = AppPreferences(this)
        backendRegistry = BackendRegistry()
        playerManager = PlayerManager()
        // 网络音乐管理器：注册所有网络源，默认源与 Meting 端点均由 AppSettings 动态提供
        val services = mapOf(
            "meting" to MetingApiService(
                baseUrlProvider = { appPreferences.getMetingApiBaseUrlSync() },
                serverProvider = { appPreferences.getMusicSourceSync() }
            )
        )
        networkMusicManager = NetworkMusicManager(
            services = services,
            defaultSourceProvider = { appPreferences.getDefaultNetworkSourceSync() }
        )
        // MV（音乐视频）搜索管理器：v1 仅 Bilibili 官方 API，端点由设置页动态提供
        mvSearchManager = MvSearchManager(
            services = listOf(
                BilibiliMvService(
                    baseUrlProvider = { appPreferences.getMvApiBaseUrlSync() }
                )
            ),
            persistentCache = MvPersistentCache(this)
        )
        // 启动时清理超过 30 天的搜索历史
        applicationScope.launch {
            try {
                appPreferences.purgeExpiredSearchHistory()
            } catch (e: Exception) {
                AppLog.w("NasMusicApp", "Failed to purge search history", e)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
    }
}

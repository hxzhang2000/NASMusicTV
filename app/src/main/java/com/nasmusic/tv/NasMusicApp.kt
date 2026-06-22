package com.nasmusic.tv

import android.app.Application
import com.nasmusic.tv.backend.BackendRegistry
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
    }

    companion object {
        lateinit var instance: NasMusicApp
            private set
    }
}

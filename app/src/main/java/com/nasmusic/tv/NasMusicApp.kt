package com.nasmusic.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.network.JamendoService
import com.nasmusic.tv.backend.network.MetingApiService
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.backend.radio.RadioBrowserClient
import com.nasmusic.tv.backend.network.baidu.BaiduCoverProvider
import com.nasmusic.tv.backend.network.baidu.BaiduFileIndexCache
import com.nasmusic.tv.backend.network.baidu.BaiduHttpDataSourceFactory
import com.nasmusic.tv.backend.network.baidu.BaiduLyricsProvider
import com.nasmusic.tv.backend.network.baidu.BaiduMvFileService
import com.nasmusic.tv.backend.network.baidu.BaiduNetdiskService
import com.nasmusic.tv.backend.network.baidu.BaiduOAuthClient
import com.nasmusic.tv.backend.network.baidu.BaiduPanApi
import com.nasmusic.tv.backend.network.baidu.BaiduStreamFactory
import com.nasmusic.tv.backend.network.mv.BilibiliMvService
import com.nasmusic.tv.backend.network.mv.MvSearchManager
import com.nasmusic.tv.backend.network.mv.MvPersistentCache
import com.nasmusic.tv.data.model.CloudDriveType
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Application 类 — 手动 DI 容器
 * 持有所有全局单例实例
 */
class NasMusicApp : Application(), ImageLoaderFactory {

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

    // ---- 百度网盘组件（懒构造，仅在用户开启百度源时实例化）----
    /** 百度专用 OkHttpClient（守护线程池 + 信任所有证书 + 百度 UA 拦截器复用） */
    val baiduOkHttpClient: OkHttpClient by lazy { BaiduOAuthClient.buildClient() }
    val baiduOAuthClient: BaiduOAuthClient by lazy { BaiduOAuthClient(baiduOkHttpClient, appPreferences) }
    val baiduPanApi: BaiduPanApi by lazy { BaiduPanApi(baiduOkHttpClient, baiduOAuthClient) }
    val baiduStreamFactory: BaiduStreamFactory by lazy { BaiduStreamFactory(baiduPanApi, baiduOAuthClient) }
    val baiduFileIndexCache: BaiduFileIndexCache by lazy { BaiduFileIndexCache(this) }
    val baiduLyricsProvider: BaiduLyricsProvider by lazy { BaiduLyricsProvider(baiduPanApi, baiduOkHttpClient) }
    val baiduCoverProvider: BaiduCoverProvider by lazy { BaiduCoverProvider(baiduPanApi, baiduOkHttpClient) }
    val baiduNetdiskService: BaiduNetdiskService by lazy {
        BaiduNetdiskService(
            oauth = baiduOAuthClient,
            api = baiduPanApi,
            streamFactory = baiduStreamFactory,
            lyricsProvider = baiduLyricsProvider,
            coverProvider = baiduCoverProvider,
            indexCache = baiduFileIndexCache,
            prefs = appPreferences
        )
    }
    val baiduMvFileService: BaiduMvFileService by lazy {
        BaiduMvFileService(
            api = baiduPanApi,
            streamFactory = baiduStreamFactory,
            indexCache = baiduFileIndexCache,
            prefs = appPreferences
        )
    }

    // ---- 电台 & Jamendo（纯公共 API，不自建后台）----
    /** radio-browser 电台客户端 */
    val radioBrowserClient: RadioBrowserClient by lazy {
        RadioBrowserClient()
    }
    /** Jamendo（CC 独立音乐）服务：clientId 由设置页配置，未配置时 registerService 跳过 */
    val jamendoService: JamendoService by lazy {
        JamendoService(
            clientIdProvider = { appPreferences.getJamendoClientIdSync() }
        )
    }

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
        // 百度网盘：仅在总开关开启且已登录时注册（运行时切换开关时动态注册/注销）
        if (appPreferences.getBaiduConfigSync().isActive) {
            networkMusicManager.registerService(baiduNetdiskService)
        }
        // Jamendo：仅当已配置 client_id 时注册（未配置时 Jamendo Tab 显示引导）
        if (appPreferences.getJamendoClientIdSync().isNotBlank()) {
            networkMusicManager.registerService(jamendoService)
        }

        // MV（音乐视频）搜索管理器：Bilibili 在线 + 百度本地 MV（百度优先）
        val mvServices = listOf(
            baiduMvFileService,    // 本地 MV 优先（仅对百度歌曲生效，非百度歌曲返回 null）
            BilibiliMvService(
                baseUrlProvider = { appPreferences.getMvApiBaseUrlSync() }
            )
        )
        mvSearchManager = MvSearchManager(
            services = mvServices,
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

    /**
     * 百度网盘开关切换：运行时注册/注销百度 NetworkMusicService。
     * - 开启且已登录：注册
     * - 关闭或登出：注销
     */
    fun refreshBaiduServiceRegistration() {
        val cfg = appPreferences.getBaiduConfigSync()
        if (cfg.isActive) {
            networkMusicManager.registerService(baiduNetdiskService)
        } else {
            networkMusicManager.unregisterService("baidu")
        }
    }

    /**
     * Coil ImageLoader：注入百度 dlink UA 拦截器，使百度网盘封面图片可加载（否则 403）。
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(BaiduHttpDataSourceFactory.createOkHttpClientForCoil())
            .memoryCache {
                MemoryCache.Builder(this).maxSizePercent(0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
    }
}

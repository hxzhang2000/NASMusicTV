package com.nasmusic.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.SearchAggregator
import com.nasmusic.tv.backend.local.LocalMusicRepository
import com.nasmusic.tv.backend.local.MusicScanner
import com.nasmusic.tv.backend.local.StorageMonitor
import com.nasmusic.tv.backend.local.db.LocalMusicDatabase
import com.nasmusic.tv.backend.network.JamendoService
import com.nasmusic.tv.backend.network.MetingApiService
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.backend.radio.RadioBrowserClient
import com.nasmusic.tv.backend.local.AlbumCoverResolver
import com.nasmusic.tv.backend.local.ArtistCoverResolver
import com.nasmusic.tv.backend.local.CoverUrlPersistentCache
import com.nasmusic.tv.backend.local.ItunesCoverSearcher
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
import com.nasmusic.tv.player.ModelDownloadManager
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/**
 * Application 类 — 手动 DI 容器
 * 持有所有全局单例实例
 */
class NasMusicApp : Application(), ImageLoaderFactory {

    companion object {
        /**
         * 读取系统当前 locale（不受 Application.updateConfiguration 影响）。
         *
         * Locale.getDefault() 在进程存活期间可被 Resources.updateConfiguration() 污染，
         * 不能作为"跟随系统"的判断依据。Resources.getSystem() 是系统级 Resources，
         * 其 configuration 总是反映真正的系统 locale。
         */
        fun getSystemLocale(): java.util.Locale {
            val sysConfig = android.content.res.Resources.getSystem().configuration
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                sysConfig.locales[0]
            } else {
                @Suppress("DEPRECATION")
                sysConfig.locale ?: java.util.Locale.getDefault()
            }
        }
    }

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

    /** 跨源搜索聚合器（含本地音乐源） */
    lateinit var searchAggregator: SearchAggregator
        private set

    /** 本地音乐仓库（索引持久化 + 增量扫描 + 索引搜索） */
    lateinit var localMusicRepository: LocalMusicRepository
        private set

    /** 存储设备监听器（USB / SD 卡插拔） */
    lateinit var storageMonitor: StorageMonitor
        private set

    /** 高质量人声分离模型下载管理器（HT-Demucs FT ONNX） */
    lateinit var modelDownloadManager: ModelDownloadManager
        private set

    // ---- 离线下载组件（需求 6/7/8/9/10）----
    /** 下载索引仓库（downloads.db） */
    lateinit var downloadRepository: com.nasmusic.tv.backend.download.DownloadRepository
        private set
    /** 存储空间守护 */
    lateinit var storageGuard: com.nasmusic.tv.backend.download.StorageGuard
        private set
    /** 下载编排器（串行队列 / 状态机 / 进度 / 重试） */
    lateinit var songDownloadManager: com.nasmusic.tv.backend.download.SongDownloadManager
        private set
    /** 播放时自动下载控制器 */
    lateinit var autoDownloadController: com.nasmusic.tv.backend.download.AutoDownloadController
        private set
    /** 导出到外接设备协调器 */
    lateinit var exportCoordinator: com.nasmusic.tv.backend.export.ExportCoordinator
        private set

    /** 懒构造 LyricsManager（供下载器歌词注入；与 MainViewModel 共用同一后端/网络上下文） */
    private val downloadLyricsManager: com.nasmusic.tv.lyrics.LyricsManager by lazy {
        com.nasmusic.tv.lyrics.LyricsManager(
            this,
            backendRegistry,
            networkMusicManager,
            // F-3：改 provider（读 @Volatile 镜像）——设置页改歌词源即时生效，且构造期零 IO
            kugouBaseUrlProvider = { appPreferences.getLyricsKugouBaseUrlSync() },
            neteaseBaseUrlProvider = { appPreferences.getLyricsNeteaseBaseUrlSync() }
        )
    }

    // ---- 百度网盘组件（懒构造，仅在用户开启百度源时实例化）----
    /** 百度专用 OkHttpClient（守护线程池 + 信任所有证书 + 百度 UA 拦截器复用） */
    val baiduOkHttpClient: OkHttpClient by lazy { BaiduOAuthClient.buildClient() }
    val baiduOAuthClient: BaiduOAuthClient by lazy { BaiduOAuthClient(baiduOkHttpClient, appPreferences) }
    val baiduPanApi: BaiduPanApi by lazy { BaiduPanApi(baiduOkHttpClient, baiduOAuthClient) }
    val baiduStreamFactory: BaiduStreamFactory by lazy { BaiduStreamFactory(baiduPanApi, baiduOAuthClient) }
    val baiduFileIndexCache: BaiduFileIndexCache by lazy { BaiduFileIndexCache(this) }
    val baiduLyricsProvider: BaiduLyricsProvider by lazy { BaiduLyricsProvider(baiduPanApi, baiduOkHttpClient, baiduOAuthClient) }
    val baiduCoverProvider: BaiduCoverProvider by lazy {
        BaiduCoverProvider(baiduPanApi, baiduOkHttpClient, baiduOAuthClient)
    }
    val albumCoverResolver: AlbumCoverResolver by lazy {
        AlbumCoverResolver(baiduCoverProvider, baiduOkHttpClient, { title, artist ->
            networkMusicManager.searchCoverUrl(title, artist)
        }, baiduFileIndexCache)
    }
    val artistCoverResolver: ArtistCoverResolver by lazy { ArtistCoverResolver(baiduOkHttpClient) }
    /** 专辑/艺术家封面 URL 持久缓存（JSON 文件，跨会话复用，避免重复网络搜索） */
    val coverUrlPersistentCache: CoverUrlPersistentCache by lazy { CoverUrlPersistentCache(this) }
    val itunesCoverSearcher: ItunesCoverSearcher by lazy { ItunesCoverSearcher(baiduOkHttpClient) }
    val baiduNetdiskService: BaiduNetdiskService by lazy {
        BaiduNetdiskService(
            oauth = baiduOAuthClient,
            api = baiduPanApi,
            streamFactory = baiduStreamFactory,
            lyricsProvider = baiduLyricsProvider,
            coverProvider = baiduCoverProvider,
            indexCache = baiduFileIndexCache,
            prefs = appPreferences,
            networkCoverSearch = { title, artist -> networkMusicManager.searchCoverUrl(title, artist) },
            itunesCoverSearch = { title, artist -> itunesCoverSearcher.searchTrack(title, artist) }
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
        appPreferences = AppPreferences.getInstance(this)
        // R-7（第三类）：启动 provider 键内存镜像收集（DataStore Flow → @Volatile）
        appPreferences.startProviderMirrors(applicationScope)
        // R-7（第一类）：语言镜像一次性迁移（老版本 DataStore 已有语言值，镜像为空时补写）
        appPreferences.migrateLanguageMirrorIfNeeded()
        // 启动时应用语言设置（读 SharedPreferences 镜像，零 IO）
        applyLocale(appPreferences.getLanguageSync())
        backendRegistry = BackendRegistry()
        playerManager = PlayerManager(this)
        // 模型下载管理器（HT-Demucs FT ONNX，与 APK 分离，设置页下载）
        modelDownloadManager = ModelDownloadManager(this)
        playerManager.setModelDownloadManager(modelDownloadManager)
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

        // 本地音乐组件：索引仓库 + USB 插拔监听
        val localMusicDao = LocalMusicDatabase.get(this).localMusicDao()
        localMusicRepository = LocalMusicRepository(this, localMusicDao, MusicScanner(this))
        storageMonitor = StorageMonitor(this)
        storageMonitor.startListening()

        // 离线下载组件（需求 6/7/8/9/10）
        downloadRepository = com.nasmusic.tv.backend.download.DownloadRepository(this)
        val downloadDao = downloadRepository.getDao()
        // 让本地曲库合并下载目录（扫描短路 MMR）与删除判定分支用
        localMusicRepository.attachDownloadSource(
            downloadDao,
            downloadRootProvider = { getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) }
        )
        storageGuard = com.nasmusic.tv.backend.download.StorageGuard(
            this,
            rootProvider = { getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: File(filesDir, "music") }
        )
        val downloadResolver = com.nasmusic.tv.backend.download.StreamUrlResolver(
            adapter = { backendRegistry.getAdapter() },
            network = { song -> networkMusicManager.resolvePlayUrl(song) },
            baidu = { song -> networkMusicManager.resolvePlayUrl(song) }
        )
        val pathBuilder = com.nasmusic.tv.backend.download.DownloadPathBuilder {
            getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: File(filesDir, "music")
        }
        val coverWriter = com.nasmusic.tv.backend.download.CoverFileWriter(
            baiduOkHttpClient,
            networkMusicManager,
            itunesCoverSearcher,
            artistCoverResolver
        )
        songDownloadManager = com.nasmusic.tv.backend.download.SongDownloadManager(
            context = this,
            scope = applicationScope,
            repo = downloadRepository,
            storage = storageGuard,
            paths = pathBuilder,
            resolver = downloadResolver,
            tagWriter = com.nasmusic.tv.backend.download.MediaTagWriter,
            coverWriter = coverWriter,
            lyricsProvider = { song ->
                val lyr = downloadLyricsManager.getLyrics(song)
                if (lyr != null) com.nasmusic.tv.lyrics.LrcParser.toLrcText(lyr) else null
            },
            settings = {
                com.nasmusic.tv.backend.download.model.DownloadSettings(
                    downloadEnabled = appPreferences.appSettings.first().downloadEnabled,
                    autoDownloadOnPlay = appPreferences.appSettings.first().autoDownloadOnPlay,
                    autoDownloadLimit = appPreferences.appSettings.first().autoDownloadLimit,
                    downloadLocation = appPreferences.appSettings.first().downloadLocation
                )
            },
            onNotify = { msg ->
                // 通过 MainViewModel 的 errorMessage 通道提示（无耦合：只发一个 Application 级回调由 UI 层接）
                // 这里直接回调给 MainViewModel.showError，由 MainViewModel 在 init 时注册
                com.nasmusic.tv.util.AppLog.d("NasMusicApp", "download notify: $msg")
            },
            onCompleted = { entity ->
                // §7.5.5 即时入库：把下载完成的实体构建为 ScannedSong 插入 local_songs，
                // 之后刷新 _localSongs（由 MainViewModel 监听 observeCompleted 自行处理，
                // 这里只负责落库，保证 storageType="DOWNLOAD" 且 MusicSourceType.DOWNLOAD 徽章可命中）
                runCatching {
                    val mediaStoreId = com.nasmusic.tv.util.HashUtils.stablePathHash64(entity.audioPath ?: entity.songKey)
                    val scanned = com.nasmusic.tv.backend.local.ScannedSong(
                        mediaStoreId = mediaStoreId,
                        title = entity.title,
                        artist = entity.artist,
                        album = entity.album,
                        albumId = mediaStoreId,
                        duration = entity.durationMs,
                        size = entity.fileSize,
                        dateAdded = entity.completedAt ?: System.currentTimeMillis(),
                        mimeType = when (entity.containerExt.lowercase()) {
                            "mp3" -> "audio/mpeg"
                            "flac" -> "audio/flac"
                            "m4a" -> "audio/mp4"
                            "aac" -> "audio/aac"
                            "ogg" -> "audio/ogg"
                            "opus" -> "audio/opus"
                            "wav" -> "audio/wav"
                            else -> "audio/*"
                        },
                        contentUri = android.net.Uri.fromFile(java.io.File(entity.audioPath ?: return@runCatching)),
                        volumeName = "local_download",
                        storageType = com.nasmusic.tv.data.model.StorageType.DOWNLOAD
                    )
                    localMusicRepository.upsertDownloaded(scanned)
                }.onFailure {
                    com.nasmusic.tv.util.AppLog.w("NasMusicApp", "onCompleted upsert failed: ${it.message}", it)
                }
            }
        )
        autoDownloadController = com.nasmusic.tv.backend.download.AutoDownloadController(
            settings = {
                com.nasmusic.tv.backend.download.model.DownloadSettings(
                    downloadEnabled = appPreferences.appSettings.first().downloadEnabled,
                    autoDownloadOnPlay = appPreferences.appSettings.first().autoDownloadOnPlay,
                    autoDownloadLimit = appPreferences.appSettings.first().autoDownloadLimit,
                    downloadLocation = appPreferences.appSettings.first().downloadLocation
                )
            },
            repo = downloadRepository,
            storage = storageGuard,
            resolver = downloadResolver,
            manager = songDownloadManager,
            notify = { msg -> com.nasmusic.tv.util.AppLog.d("NasMusicApp", "auto-dl: $msg") },
            scope = applicationScope
        )
        exportCoordinator = com.nasmusic.tv.backend.export.ExportCoordinator(
            context = this,
            appPreferences = appPreferences,
            downloadRepository = downloadRepository,
            downloadPathBuilder = pathBuilder
        )

        // 存储守护启动 + 崩溃恢复 + MediaTagWriter 全局配置
        com.nasmusic.tv.backend.download.MediaTagWriter.configure()
        storageGuard.start(applicationScope)
        applicationScope.launch { songDownloadManager.recoverAfterCrash() }

        // 跨源搜索聚合器（注入本地音乐源，检测 TV 设备以启用拼音搜索）
        val isTVDevice = packageManager.hasSystemFeature("android.software.leanback") ||
                packageManager.hasSystemFeature("android.hardware.type.television")
        searchAggregator = SearchAggregator(
            backendRegistry = backendRegistry,
            networkMusicManager = networkMusicManager,
            baiduService = baiduNetdiskService,
            jamendoService = jamendoService,
            localMusicRepository = localMusicRepository,
            isTVDevice = isTVDevice
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
     * 应用语言设置（手动 Configuration 更新，无需 AppCompat）
     * @param lang "system"=跟随系统, "zh"=中文, "en"=English
     */
    fun applyLocale(lang: String) {
        val locale = when (lang) {
            "zh" -> java.util.Locale.SIMPLIFIED_CHINESE
            "en" -> java.util.Locale.US
            else -> getSystemLocale() // 跟随系统：读取真正的系统 locale，而非被污染的 Locale.getDefault()
        }
        val config = android.content.res.Configuration(resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
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

package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.backend.network.MetingApiService
import com.nasmusic.tv.backend.network.mv.BilibiliMvService
import com.nasmusic.tv.backend.photo.PhotoScaleMode
import com.nasmusic.tv.backend.playlist.PlaylistParsers
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.BaiduTokens
import com.nasmusic.tv.data.model.CloudDriveConfig
import com.nasmusic.tv.data.model.CloudDriveType
import com.nasmusic.tv.data.model.EqualizerPreset
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.data.model.NetworkFavoriteItem
import com.nasmusic.tv.data.model.NetworkSource
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.SearchHistoryItem
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.CryptoUtils
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 应用偏好存储
 * 使用 DataStore 持久化服务器配置与通用设置
 *
 * 构造期为 `internal`：生产代码一律走 [getInstance] 单例（DataStore 要求同一文件
 * 只能有一个实例）；单元测试通过 internal 构造器为每个用例构造独立实例，避免
 * 单例跨用例串扰（Robolectric 每个用例有独立 dataDir，共享单例会导致 DataStore
 * 文件锁冲突与标记位污染）。
 */
class AppPreferences internal constructor(private val context: Context) {

    // =====================================================================
    // R-4 门面子域（键不迁移只搬访问器，DataStore 单例不变；调用方渐进迁移，
    // 旧 API 保留至全部调用点迁移后再 @Deprecated）
    // =====================================================================
    val server: ServerPrefs by lazy { ServerPrefs(this) }
    val player: PlayerPrefs by lazy { PlayerPrefs(this) }
    val lyrics: LyricsPrefs by lazy { LyricsPrefs(this) }
    val network: NetworkMusicPrefs by lazy { NetworkMusicPrefs(this) }
    val baidu: BaiduPrefs by lazy { BaiduPrefs(this) }
    val download: DownloadPrefs by lazy { DownloadPrefs(this) }
    val weather: WeatherPrefs by lazy { WeatherPrefs(this) }
    val visualizer: VisualizerPrefs by lazy { VisualizerPrefs(this) }
    val history: HistoryPrefs by lazy { HistoryPrefs(this) }
    val playlist: PlaylistPrefs by lazy { PlaylistPrefs(this) }
    val queue: QueuePrefs by lazy { QueuePrefs(this) }
    val languagePrefs: LanguagePrefs by lazy { LanguagePrefs(this) }
    val backup: BackupPrefs by lazy { BackupPrefs(this) }
    /** 显示域（屏幕方向等**设备本地**偏好；不进 AppSettings 备份 JSON，见 §5.3） */
    val display: DisplayPrefs by lazy { DisplayPrefs(this) }
    /** 照片墙域（§7.3，17 个字段；进 AppSettings 备份 JSON） */
    val photoWall: PhotoWallPrefs by lazy { PhotoWallPrefs(this) }

    companion object {
        private const val TAG = "AppPreferences"

        /** F2-6：音质档位常量（Meting br 值；AUTO 由 BandwidthEstimator 决策） */
        const val QUALITY_TIER_AUTO = 0
        const val QUALITY_TIER_LOSSLESS = 999
        const val QUALITY_TIER_HIGH = 320
        /** v2.35.0 多码率：补齐 192 档 */
        const val QUALITY_TIER_GOOD = 192
        const val QUALITY_TIER_STANDARD = 128

        /** 歌单导入历史最大保留条数（超出按 importedAt 淘汰最旧） */
        const val playlistImportHistoryMaxSize = 20

        /** 照片墙转场时长范围（ms，§7.3：300–2000） */
        val PHOTO_WALL_TRANSITION_MS_RANGE = 300..2000

        /** 照片墙停留时长范围（ms，§7.3：3000–30000；默认 8000） */
        val PHOTO_WALL_HOLD_MS_RANGE = 3_000..30_000

        /** URL 可达性持久化判定窗口（24h）：窗口内的「不可达」标记重启后不重测 */
        const val songReachabilityWindowMs = 24 * 60 * 60 * 1000L

        /** 屏幕方向 SharedPreferences 镜像键（v2.36.0） */
        internal const val KEY_ORIENTATION_MIRROR = "screen_orientation"

        @Volatile
        private var INSTANCE: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> by lazy {
        PreferenceDataStoreFactory.create {
            File(context.filesDir, "datastore/nas_music_tv.preferences_pb")
        }
    }

    // F2-1：月度播放统计仓库（依赖本类 dataStore/gson，recordPlayWithSong 原子联动写入）
    internal val playStatsRepo by lazy { com.nasmusic.tv.data.stats.PlayStatsRepository(this) }

    /** F2-1：供 PlayStatsRepository 读取的 DataStore 数据流（包内可见） */
    internal fun dataStoreData() = dataStore.data

    /** F2-1：供 PlayStatsRepository 复用的 Gson 实例（包内可见） */
    internal fun gson(): Gson = gson

    /**
     * 本机是否为电视 —— 决定「外接存储」开关的**平台相关默认值**（§6.8：电视 `true` / 手机 `false`）。
     *
     * ⚠️ 与 `NasMusicApp` 的 `isTVDevice` / `ui/components/FocusableSurface.isTVDevice()`
     * 用**同一套判据**（`android.software.leanback` / `android.hardware.type.television`），
     * 这里只是不需要 Composable 与 Activity Context 而已。三处判据必须一致，
     * 否则会出现「按电视给的默认值、按手机渲染的设置页」这类错位。
     *
     * ⚠️ `by lazy`：`hasSystemFeature` 在 API 22 上可能是一次 binder 调用，
     * 而本判定在 `appSettings` 每次发射时都会被读到（设备类型进程内不变，缓存安全）。
     */
    private val isTelevisionDevice: Boolean by lazy {
        context.packageManager.run {
            hasSystemFeature("android.software.leanback") ||
                hasSystemFeature("android.hardware.type.television")
        }
    }

    // =====================================================================
    // R-7（方案 2026-09）：
    // 1. 语言键双写（SharedPreferences 镜像 + DataStore 事实源）——
    //    attachBaseContext 只读镜像，零 IO、无 runBlocking（第一类修复）。
    // 2. provider 类键的 @Volatile 内存镜像（DataStore Flow 常驻收集更新）——
    //    get*Sync 读镜像，调用点零改动（第三类修复，路 A）。
    // =====================================================================

    /** 语言镜像（只服务冷启动 attachBaseContext，其余设置项不搞双写） */
    private val mirrorPrefs = context.getSharedPreferences("language_mirror", Context.MODE_PRIVATE)

    /**
     * 显示域镜像（只服务冷启动 `MainActivity.onCreate` 同步读屏幕方向）。
     *
     * 与 [mirrorPrefs] 同构：`@Volatile` 内存镜像 + SharedPreferences 落盘，
     * 冷启动（进程重启、Flow 尚未发射）也能拿到上次的方向，零 IO、零 `runBlocking`。
     */
    private val displayMirrorPrefs = context.getSharedPreferences("display_mirror", Context.MODE_PRIVATE)

    // ---- provider 键内存镜像（Application scope 常驻收集更新）----
    @Volatile private var cachedMusicSource: String = com.nasmusic.tv.data.model.MusicSource.DEFAULT_API_KEY
    @Volatile private var cachedDefaultNetworkSource: String = NetworkSource.DEFAULT.key
    /** F2-6：音质档位镜像（默认 AUTO） */
    @Volatile private var cachedQualityTier: Int = QUALITY_TIER_AUTO
    @Volatile private var cachedJamendoClientId: String = ""
    @Volatile private var cachedMetingApiBaseUrl: String = MetingApiService.DEFAULT_BASE_URL
    @Volatile private var cachedMvApiBaseUrl: String = BilibiliMvService.DEFAULT_BASE_URL
    /** 高质量分离模型自定义下载 URL 镜像（空=用内置默认候选列表） */
    @Volatile private var cachedModelDownloadUrl: String = ""
    @Volatile private var cachedLyricsKugouBaseUrl: String = com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_KUGOU_BASE_URL
    @Volatile private var cachedLyricsNeteaseBaseUrl: String = com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_NETEASE_BASE_URL
    @Volatile private var cachedWeatherApiKey: String = ""

    /** 屏幕方向镜像（v2.36.0：冷启动首帧方向，默认 auto） */
    @Volatile private var cachedScreenOrientation: String =
        displayMirrorPrefs.getString(KEY_ORIENTATION_MIRROR, null) ?: "auto"

    /** 镜像已启动收集标志（ensureMirrorScopeLoaded 只执行一次） */
    @Volatile private var mirrorStarted = false
    private val mirrorLock = Any()

    /**
     * 启动 provider 键镜像收集（由 NasMusicApp.onCreate 注入 applicationScope 调用，仅一次）。
     * 设置页改动 → DataStore 写入 → Flow 发射 → @Volatile 镜像更新 → get*Sync 立即生效。
     */
    fun startProviderMirrors(scope: kotlinx.coroutines.CoroutineScope) {
        synchronized(mirrorLock) {
            if (mirrorStarted) return
            mirrorStarted = true
        }
        // F-7：先做天气 Key 明文→加密一次性迁移（幂等），镜像再从加密键读
        scope.launch { migrateWeatherApiKeyIfNeeded() }
        scope.launch {
            dataStore.data.map { prefs ->
                val enc = prefs[keyWeatherApiKeyEnc]
                if (!enc.isNullOrBlank()) {
                    try { com.nasmusic.tv.util.CryptoUtils.decrypt(enc) } catch (e: Exception) { "" }
                } else ""
            }.distinctUntilChanged().collect { cachedWeatherApiKey = it }
        }
        scope.launch {
            dataStore.data.map { it[keyMusicSource] ?: com.nasmusic.tv.data.model.MusicSource.DEFAULT_API_KEY }
                .distinctUntilChanged().collect { cachedMusicSource = it }
        }
        scope.launch {
            dataStore.data.map { it[keyDefaultNetworkSource] ?: NetworkSource.DEFAULT.key }
                .distinctUntilChanged().collect { cachedDefaultNetworkSource = NetworkSource.fromKey(it)?.key ?: it }
        }
        // F2-6：音质档位镜像（qualityTierProvider 同步读，注册进 MetingApiService）
        scope.launch {
            dataStore.data.map { it[keyQualityTier] ?: QUALITY_TIER_AUTO }
                .distinctUntilChanged().collect { cachedQualityTier = it }
        }
        scope.launch {
            dataStore.data.map { it[keyJamendoClientId] ?: "" }
                .distinctUntilChanged().collect { cachedJamendoClientId = it }
        }
        scope.launch {
            dataStore.data.map { it[keyMetingApiBaseUrl] ?: MetingApiService.DEFAULT_BASE_URL }
                .distinctUntilChanged().collect { cachedMetingApiBaseUrl = it }
        }
        scope.launch {
            dataStore.data.map { it[keyMvApiBaseUrl] ?: BilibiliMvService.DEFAULT_BASE_URL }
                .distinctUntilChanged().collect { cachedMvApiBaseUrl = it }
        }
        scope.launch {
            dataStore.data.map { it[keyLyricsKugouBaseUrl] ?: com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_KUGOU_BASE_URL }
                .distinctUntilChanged().collect { cachedLyricsKugouBaseUrl = it }
        }
        scope.launch {
            dataStore.data.map { it[keyLyricsNeteaseBaseUrl] ?: com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_NETEASE_BASE_URL }
                .distinctUntilChanged().collect { cachedLyricsNeteaseBaseUrl = it }
        }
        scope.launch {
            dataStore.data.map { it[keyModelDownloadUrl] ?: "" }
                .distinctUntilChanged().collect { cachedModelDownloadUrl = it }
        }
        // v2.36.0：屏幕方向镜像（MainActivity.onCreate 同步读，注册进 requestedOrientation 首帧）
        scope.launch {
            dataStore.data.map { it[keyScreenOrientation] ?: "auto" }
                .distinctUntilChanged().collect {
                    cachedScreenOrientation = it
                    displayMirrorPrefs.edit().putString(KEY_ORIENTATION_MIRROR, it).apply()
                }
        }
        // （F-7：weather 镜像改读加密键，见上方 migrateWeatherApiKeyIfNeeded 后的收集块）
    }


    // --- 服务器配置 ---
    private val keyBackendType = stringPreferencesKey("server_backend_type")
    private val keyBaseUrl = stringPreferencesKey("server_base_url")
    private val keyApiToken = stringPreferencesKey("server_api_token")
    private val keyUsername = stringPreferencesKey("server_username")
    private val keyPassword = stringPreferencesKey("server_password")
    private val keyServerConnected = booleanPreferencesKey("server_connected")
    private val keyServerDisplayName = stringPreferencesKey("server_display_name")

    // --- 通用设置 ---
    private val keyDarkTheme = booleanPreferencesKey("settings_dark_theme")
    private val keyAnimations = booleanPreferencesKey("settings_animations")
    private val keyAutoPlayNext = booleanPreferencesKey("settings_auto_play_next")
    private val keyPlayMode = intPreferencesKey("settings_play_mode")
    private val keyCacheLyrics = booleanPreferencesKey("settings_cache_lyrics")
    private val keyCacheCover = booleanPreferencesKey("settings_cache_cover")
    private val keyLyricsOffset = longPreferencesKey("settings_lyrics_offset")
    private val keyDefaultNetworkSource = stringPreferencesKey("settings_default_network_source")
    private val keyMetingApiBaseUrl = stringPreferencesKey("settings_meting_api_base_url")
    private val keyMvApiBaseUrl = stringPreferencesKey("settings_mv_api_base_url")
    private val keyModelDownloadUrl = stringPreferencesKey("settings_model_download_url")
    private val keyLyricsKugouBaseUrl = stringPreferencesKey("settings_lyrics_kugou_base_url")
    private val keyLyricsNeteaseBaseUrl = stringPreferencesKey("settings_lyrics_netease_base_url")
    private val keyJamendoClientId = stringPreferencesKey("settings_jamendo_client_id")

    // --- B-2 最近播放 & 播放次数（序列化为 JSON）---
    private val keyRecentSongs = stringPreferencesKey("recent_songs")
    private val keyRecentSongObjects = stringPreferencesKey("recent_song_objects")
    private val keyPlayCounts = stringPreferencesKey("play_counts")

    // --- 网络歌曲收藏（序列化为 JSON）---
    private val keyNetworkFavorites = stringPreferencesKey("network_favorites")

    // --- 本地歌单（序列化为 JSON，独立于 NAS 后端歌单）---
    private val keyLocalPlaylists = stringPreferencesKey("local_playlists")

    // --- 歌单导入（R：playlist-import-feature-plan）---
    private val keyPlaylistImportHistory = stringPreferencesKey("playlist_import_history")
    /** songId → ReachabilityEntry（Map<String, {result, checkedAt}>，仅记录非 REACHABLE 判定） */
    private val keySongReachability = stringPreferencesKey("song_reachability")

    // --- 上次播放队列（序列化为 JSON，streamUrl 置空不持久化）---
    private val keyLastQueue = stringPreferencesKey("last_queue")

    // --- 天气电台设置 ---
    private val keyWeatherEnabled = booleanPreferencesKey("weather_enabled")
    private val keyWeatherManualCity = stringPreferencesKey("weather_manual_city")
    private val keyWeatherAutoRefresh = booleanPreferencesKey("weather_auto_refresh")
    private val keyWeatherApiKey = stringPreferencesKey("weather_openweathermap_api_key")
    /**
     * F-7：加密存储键（OpenWeatherMap key 属付费资源凭证，泄露可被刷量；
     * 与服务器密码/百度 token 一致走 CryptoUtils AES-GCM。旧明文键保留做一次性迁移读取）。
     */
    private val keyWeatherApiKeyEnc = stringPreferencesKey("weather_openweathermap_api_key_enc")

    // --- 封面滤镜设置（Phase 5） ---
    private val keyCoverFilterEnabled = booleanPreferencesKey("cover_filter_enabled")
    private val keyCoverFilterBlurRadius = doublePreferencesKey("cover_filter_blur_radius")
    private val keyCoverFilterDarkOverlay = doublePreferencesKey("cover_filter_dark_overlay")

    // --- 频谱显示设置 ---
    private val keySpectrumEnabled = booleanPreferencesKey("settings_spectrum_enabled")
    private val keyVisualizerTheme = stringPreferencesKey("settings_visualizer_theme")
    private val keyVisualizerQuality = stringPreferencesKey("visualizer_quality")

    // --- 照片墙（§7.3，17 个字段）---
    private val keyPhotoWallGalleryEnabled = booleanPreferencesKey("photo_wall_gallery_enabled")
    private val keyPhotoWallExternalEnabled = booleanPreferencesKey("photo_wall_external_enabled")
    private val keyPhotoWallJellyfinEnabled = booleanPreferencesKey("photo_wall_jellyfin_enabled")
    private val keyPhotoWallSourceBalance = booleanPreferencesKey("photo_wall_source_balance")
    private val keyPhotoWallDirUri = stringPreferencesKey("photo_wall_dir_uri")
    private val keyPhotoWallCommonDirsOnly = booleanPreferencesKey("photo_wall_common_dirs_only")
    private val keyPhotoWallFacesOnly = booleanPreferencesKey("photo_wall_faces_only")
    private val keyPhotoWallFaceScanDone = booleanPreferencesKey("photo_wall_face_scan_done")
    private val keyPhotoWallRandomTransition = booleanPreferencesKey("photo_wall_random_transition")
    private val keyPhotoWallFixedTransition = stringPreferencesKey("photo_wall_fixed_transition")
    private val keyPhotoWallTransitionMs = intPreferencesKey("photo_wall_transition_ms")
    private val keyPhotoWallHoldMs = intPreferencesKey("photo_wall_hold_ms")
    private val keyPhotoWallScaleMode = stringPreferencesKey("photo_wall_scale_mode")
    private val keyPhotoWallKenBurns = booleanPreferencesKey("photo_wall_ken_burns")
    private val keyPhotoWallAudioReactive = booleanPreferencesKey("photo_wall_audio_reactive")
    private val keyPhotoWallPulseZoom = booleanPreferencesKey("photo_wall_pulse_zoom")
    private val keyPhotoWallBreathe = booleanPreferencesKey("photo_wall_breathe")

    // --- 全局字体字号调整 ---
    private val keyFontAdjustment = intPreferencesKey("settings_font_adjustment")

    // --- 离线下载（需求 6/7/8/9）---
    private val keyDownloadEnabled = booleanPreferencesKey("settings_download_enabled")
    private val keyAutoDownloadOnPlay = booleanPreferencesKey("settings_auto_download_on_play")
    private val keyAutoDownloadLimit = intPreferencesKey("settings_auto_download_limit")
    private val keyDownloadLocation = stringPreferencesKey("settings_download_location")

    // --- 导出到外接设备：SAF 授权持久化 ---
    private val keyExportTreeUri = stringPreferencesKey("export_tree_uri")
    private val keyExportVolumeId = stringPreferencesKey("export_volume_id")

    // --- 首次曲库快捷键提示 ---
    private val keyShowLibraryShortcutHint = booleanPreferencesKey("show_library_shortcut_hint")

    // --- 网络音乐平台来源 ---
    private val keyMusicSource = stringPreferencesKey("music_source")

    // --- B-4 均衡器 ---
    private val keyEqualizerPreset = intPreferencesKey("equalizer_preset")
    private val keyEqualizerBands = stringPreferencesKey("equalizer_bands")

    // --- 语言设置 ---
    private val keyLanguage = stringPreferencesKey("settings_language")

    // --- 语言设置 Flow ---
    val language: Flow<String> = dataStore.data.map { it[keyLanguage] ?: "system" }

    // =====================================================================
    // v2.36.0 显示域：屏幕方向（设备本地偏好，**不进 AppSettings 备份 JSON**）
    //
    // ⚠️ 为什么不放进 AppSettings：`AppSettings` 会被 Gson 序列化进备份 JSON，
    // 屏幕方向属设备本地偏好 —— 从手机备份恢复到电视上会把电视锁成竖屏。
    // =====================================================================

    private val keyScreenOrientation = stringPreferencesKey("display_screen_orientation")

    /** 屏幕方向："auto"（跟随传感器 + 尊重系统旋转锁）/ "portrait" / "landscape" */
    val screenOrientation: Flow<String> = dataStore.data.map { it[keyScreenOrientation] ?: "auto" }

    suspend fun setScreenOrientation(value: String) {
        dataStore.edit { it[keyScreenOrientation] = value }
        displayMirrorPrefs.edit().putString(KEY_ORIENTATION_MIRROR, value).apply()
    }

    /**
     * 冷启动同步读屏幕方向（零 IO、零 `runBlocking`）。
     *
     * R-7 第三类修复（路 A）范式：读 `@Volatile` 内存镜像，镜像由
     * [startProviderMirrors] 里的常驻收集刷新。仅供 `MainActivity.onCreate`
     * 首帧设置 `requestedOrientation` 使用（避免闪一帧 manifest 默认值）。
     */
    fun getScreenOrientationSync(): String = cachedScreenOrientation

    // --- 首次曲库快捷键提示 Flow ---
    val showLibraryShortcutHint: Flow<Boolean> = dataStore.data.map { it[keyShowLibraryShortcutHint] ?: true }

    suspend fun setShowLibraryShortcutHint(show: Boolean) {
        dataStore.edit { it[keyShowLibraryShortcutHint] = show }
    }

    /**
     * 同步获取当前语言设置。
     *
     * R-7 第一类修复：attachBaseContext 无法挂起，改读 SharedPreferences 镜像
     * （[setLanguage] 双写保证一致；镜像未命中时回退默认值并打点）。
     */
    fun getLanguageSync(): String {
        val mirrored = mirrorPrefs.getString("language", null)
        return mirrored ?: "system"
    }

    /** 语言双写：DataStore 事实源 + SharedPreferences 镜像（只服务冷启动） */
    suspend fun setLanguage(lang: String) {
        dataStore.edit { it[keyLanguage] = lang }
        mirrorPrefs.edit().putString("language", lang).apply()
    }

    /**
     * R-7（第一类）：老版本升级迁移——DataStore 已有语言值但镜像为空时补写镜像。
     * 由 NasMusicApp.onCreate 在首次读取镜像前调用（一次性，幂等）。
     */
    fun migrateLanguageMirrorIfNeeded() {
        if (mirrorPrefs.contains("language")) return
        try {
            val stored = runBlocking(Dispatchers.IO) {
                dataStore.data.first()[keyLanguage] ?: "system"
            }
            mirrorPrefs.edit().putString("language", stored).apply()
        } catch (e: Exception) {
            AppLog.w(TAG, "migrateLanguageMirrorIfNeeded failed", e)
        }
    }

    // --- K 歌模式：升降调 & 变速（全局记忆）---
    private val keyPitchSemitones = intPreferencesKey("k_pitch_semitones")
    private val keyPlaybackSpeed = doublePreferencesKey("k_playback_speed")

    val pitchSemitones: Flow<Int> = dataStore.data.map { it[keyPitchSemitones] ?: 0 }
    val playbackSpeed: Flow<Double> = dataStore.data.map { it[keyPlaybackSpeed] ?: 1.0 }

    suspend fun setPitchSemitones(semitones: Int) {
        dataStore.edit { it[keyPitchSemitones] = semitones }
    }

    suspend fun setPlaybackSpeed(speed: Double) {
        dataStore.edit { it[keyPlaybackSpeed] = speed }
    }

    // --- 伴奏分离模式（快速/高质量）---
    private val keySeparationMode = stringPreferencesKey("k_separation_mode")

    /**
     * 分离模式枚举（R-5：已上提为 player 层顶层 [com.nasmusic.tv.player.SeparationMode]，
     * 此处 typealias 保持既有引用兼容；新代码请直接用 player 层类型）
     */
    typealias SeparationMode = com.nasmusic.tv.player.SeparationMode

    val separationMode: Flow<SeparationMode> = dataStore.data.map { prefs ->
        val value = prefs[keySeparationMode] ?: SeparationMode.FAST.value
        SeparationMode.entries.find { it.value == value } ?: SeparationMode.FAST
    }

    suspend fun setSeparationMode(mode: SeparationMode) {
        dataStore.edit { it[keySeparationMode] = mode.value }
    }

    // --- 播放统计 ---
    private val keyPlayRecords = stringPreferencesKey("play_records")

    // --- 搜索历史（序列化为 JSON，30 天 TTL + 上限 200 条）---
    private val keySearchHistory = stringPreferencesKey("search_history")

    // --- 网盘配置（按 CloudDriveType 存取，JSON 序列化 CloudDriveConfig；token 字段加密）---
    private val keyCloudDriveConfig = stringPreferencesKey("cloud_drive_configs")

    private val gson = Gson()
    private val recentSongsMaxSize = 50
    private val networkFavoritesMaxSize = 500
    private val searchHistoryMaxSize = 200
    private val searchHistoryTtlMs = 30L * 24 * 60 * 60 * 1000  // 30 天

    /**
     * 安全解析 JSON 偏好：解析失败时记录告警并返回 null（调用方应跳过回写，保留原数据），
     * 而非返回空集合后被无条件回写覆盖 —— 后者会把用户积累的数据（播放记录/收藏/歌单等）
     * 一次 JSON 异常（写入截断/字段变更）后抹成空。
     *
     * @param keyName 偏好键名（仅用于日志定位）
     * @param json 原始 JSON 字符串
     * @param parse 解析 lambda
     * @return 解析结果；解析失败返回 null
     */
    private inline fun <T> safeParseJson(keyName: String, json: String, parse: () -> T): T? {
        return try {
            parse()
        } catch (e: Exception) {
            AppLog.w(TAG, "JSON 解析失败，跳过回写以保留原数据 [$keyName]: ${e.message?.take(80)}")
            null
        }
    }


    // --- ServerConfig Flow ---
    val serverConfig: Flow<ServerConfig> = dataStore.data.map { prefs ->
        ServerConfig(
            backendType = prefs[keyBackendType] ?: ServerConfig.TYPE_JELLYFIN,
            baseUrl = prefs[keyBaseUrl] ?: "",
            apiToken = CryptoUtils.decrypt(prefs[keyApiToken] ?: ""),
            username = prefs[keyUsername] ?: "",
            password = CryptoUtils.decrypt(prefs[keyPassword] ?: ""),
            isConnected = prefs[keyServerConnected] ?: false,
            displayName = prefs[keyServerDisplayName] ?: ""
        )
    }

    suspend fun saveServerConfig(config: ServerConfig) {
        dataStore.edit { prefs ->
            prefs[keyBackendType] = config.backendType
            prefs[keyBaseUrl] = config.baseUrl
            prefs[keyApiToken] = CryptoUtils.encrypt(config.apiToken)
            prefs[keyUsername] = config.username
            prefs[keyPassword] = CryptoUtils.encrypt(config.password)
            prefs[keyServerConnected] = config.isConnected
            prefs[keyServerDisplayName] = config.displayName
        }
    }

    suspend fun setServerConnected(connected: Boolean, displayName: String = "") {
        dataStore.edit { prefs ->
            prefs[keyServerConnected] = connected
            if (displayName.isNotBlank()) {
                prefs[keyServerDisplayName] = displayName
            }
        }
    }

    suspend fun clearServerConfig() {
        dataStore.edit { prefs ->
            prefs.remove(keyBackendType)
            prefs.remove(keyBaseUrl)
            prefs.remove(keyApiToken)
            prefs.remove(keyUsername)
            prefs.remove(keyPassword)
            prefs[keyServerConnected] = false
            prefs.remove(keyServerDisplayName)
        }
    }

    // --- B-2 最近播放 Flow ---
    val recentSongIds: Flow<List<String>> = dataStore.data.map { prefs ->
        val json = prefs[keyRecentSongs] ?: "[]"
        try {
            gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    // --- B-2 播放次数 Flow ---
    val playCounts: Flow<Map<String, Int>> = dataStore.data.map { prefs ->
        val json = prefs[keyPlayCounts] ?: "{}"
        try {
            gson.fromJson<Map<String, Int>>(json, object : TypeToken<Map<String, Int>>() {}.type)
        } catch (e: Exception) { emptyMap() }
    }

    /**
     * 获取最近播放 ID 列表（一次性读取，调用方需在协程中）
     */
    suspend fun getRecentSongIds(): List<String> {
        return try {
            dataStore.data.first().let { prefs ->
                val recentJson = prefs[keyRecentSongs] ?: "[]"
                gson.fromJson<List<String>>(recentJson, object : TypeToken<List<String>>() {}.type) ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 记录一次播放（B-2）
     * 1. 添加到最近播放列表（去重 + LRU，最多 50 条）
     * 2. 播放次数 +1
     */
    suspend fun recordPlay(songId: String) {
        dataStore.edit { prefs ->
            // 更新最近播放
            val recentJson = prefs[keyRecentSongs] ?: "[]"
            val recentList = safeParseJson("recent_songs", recentJson) {
                gson.fromJson<MutableList<String>>(recentJson, object : TypeToken<MutableList<String>>() {}.type)
            }
            if (recentList != null) {
                val mutableRecent = recentList.toMutableList()
                mutableRecent.remove(songId) // 去重
                mutableRecent.add(0, songId)  // 最新放最前面
                if (mutableRecent.size > recentSongsMaxSize) {
                    mutableRecent.removeAt(mutableRecent.lastIndex)
                }
                prefs[keyRecentSongs] = gson.toJson(mutableRecent)
            }

            // 更新播放次数
            val countsJson = prefs[keyPlayCounts] ?: "{}"
            val counts = safeParseJson("play_counts", countsJson) {
                gson.fromJson<MutableMap<String, Int>>(countsJson, object : TypeToken<MutableMap<String, Int>>() {}.type)
            }
            if (counts != null) {
                counts[songId] = (counts[songId] ?: 0) + 1
                prefs[keyPlayCounts] = gson.toJson(counts)
            }
        }
    }

    // --- B-2b 最近播放完整歌曲对象（含网络歌曲，供"最近播放"区展示/播放）---
    private data class RecentSongObjectsData(val songs: List<Song> = emptyList())

    /** 最近播放区最大保留歌曲数 */
    private val recentSongsObjectsMaxSize = 50

    /**
     * 持久化前的 streamUrl 清理（修复：已下载/本地歌曲恢复后无法播放）：
     * 仅网络歌曲置空（直链有时效，播放时需重新解析）；
     * 本地歌曲（含已下载入库，isLocalSong=true）的 file:// URI 永久有效，必须保留，
     * 否则重启恢复队列 / 最近播放 / 本地歌单场景下无法回放。
     */
    private fun Song.stripVolatileStreamUrl(): Song =
        // 2026-09-25 审查修复（凭据明文落盘）：NAS 歌曲 streamUrl 含长期凭据
        // （Jellyfin api_key / Navidrome·Subsonic t=md5(password+salt) / 道理鱼 JWT），
        // 此前仅 isNetworkSong 置空，NAS 凭据 URL 随队列恢复/最近播放/本地歌单明文写入 DataStore。
        // 现改为仅本地歌曲保留 file:// URI（恢复播放经 adapter.getSongsByIds 重建）。
        // 例外：imported_ 前缀（m3u/json 歌单导入的 URL 直链 stub）——其 streamUrl 是
        // 永久直链、不含凭据，是唯一播放来源（PlaylistImporter.toBareSong 直接落地），
        // 置空会导致导入歌单无法播放（PlaylistImporterTest 已覆盖）。
        if (!isLocalSong && !id.startsWith(PlaylistParsers.IMPORTED_ID_PREFIX)) copy(streamUrl = null) else this

    /**
     * 记录一次最近播放的完整歌曲对象（网络歌曲 streamUrl 置空）。
     * 与 recordPlay 的 id 列表互补：这里存完整元数据，支持网络歌曲/未连 NAS 时展示。
     */
    suspend fun recordRecentSongObject(song: Song) {
        dataStore.edit { prefs ->
            val json = prefs[keyRecentSongObjects] ?: "{\"songs\":[]}"
            val data = safeParseJson("recent_song_objects", json) {
                gson.fromJson<RecentSongObjectsData>(json, RecentSongObjectsData::class.java)
            } ?: return@edit
            val list = data.songs.toMutableList()
            list.removeAll { it.id == song.id } // 去重（保留最新一条）
            // 网络歌曲 streamUrl 置空（直链有时效）；本地歌曲保留 file:// URI
            list.add(0, song.stripVolatileStreamUrl())
            if (list.size > recentSongsObjectsMaxSize) {
                list.removeAt(list.lastIndex)
            }
            prefs[keyRecentSongObjects] = gson.toJson(RecentSongObjectsData(list))
        }
    }

    /**
     * 合并记录播放：单次 DataStore edit 同时更新 id 列表 + 播放次数 + 完整歌曲对象。
     * 替代分别调用 [recordPlay] + [recordRecentSongObject]（两次 DataStore 写）。
     */
    suspend fun recordPlayWithSong(song: Song) {
        dataStore.edit { prefs ->
            val songId = song.id

            // 1. 更新最近播放 id 列表（去重 + LRU，最多 50 条）
            val recentJson = prefs[keyRecentSongs] ?: "[]"
            val recentList = safeParseJson("recent_songs", recentJson) {
                gson.fromJson<MutableList<String>>(recentJson, object : TypeToken<MutableList<String>>() {}.type)
            }
            if (recentList != null) {
                recentList.remove(songId)
                recentList.add(0, songId)
                if (recentList.size > recentSongsMaxSize) {
                    recentList.removeAt(recentList.lastIndex)
                }
                prefs[keyRecentSongs] = gson.toJson(recentList)
            }

            // 2. 更新播放次数
            val countsJson = prefs[keyPlayCounts] ?: "{}"
            val counts = safeParseJson("play_counts", countsJson) {
                gson.fromJson<MutableMap<String, Int>>(countsJson, object : TypeToken<MutableMap<String, Int>>() {}.type)
            }
            if (counts != null) {
                counts[songId] = (counts[songId] ?: 0) + 1
                prefs[keyPlayCounts] = gson.toJson(counts)
            }

            // 3. 更新完整歌曲对象（含网络歌曲，streamUrl 置空）
            val objJson = prefs[keyRecentSongObjects] ?: "{\"songs\":[]}"
            val objData = safeParseJson("recent_song_objects", objJson) {
                gson.fromJson<RecentSongObjectsData>(objJson, RecentSongObjectsData::class.java)
            }
            if (objData != null) {
                val objList = objData.songs.toMutableList()
                objList.removeAll { it.id == songId }
                objList.add(0, song.stripVolatileStreamUrl())
                if (objList.size > recentSongsObjectsMaxSize) {
                    objList.removeAt(objList.lastIndex)
                }
                prefs[keyRecentSongObjects] = gson.toJson(RecentSongObjectsData(objList))
            }

            // 4. 月度统计（F2-1）：与上述键同一次 edit 原子写入
            val now = System.currentTimeMillis()
            playStatsRepo.appendMonthlyPlayInEdit(prefs, songId, now)
            // 5. 按天统计（F2-5 热力图）：同一次 edit，保证与月度口径一致
            playStatsRepo.appendDailyPlayInEdit(prefs, now)
        }
    }

    /**
     * 一次性把 play_records 回填进按天统计（F2-5 热力图）。
     *
     * 幂等且零日常开销：标记已置位时**直接返回，不进入 edit**。
     * 由统计页首次加载时触发（见 PlayStatsViewModel.loadStats）。
     */
    suspend fun backfillDailyStatsOnce() {
        if (dataStore.data.first()[playStatsRepo.keyDailyBackfilled] == true) return
        dataStore.edit { prefs ->
            val json = prefs[keyPlayRecords] ?: return@edit
            val data = safeParseJson("play_records", json) {
                gson.fromJson<PlayRecordsData>(json, PlayRecordsData::class.java)
            } ?: return@edit
            playStatsRepo.backfillDailyInEdit(prefs, data.records)
        }
    }

    /**
     * 获取最近播放歌曲对象（最新在前，streamUrl 为空需播放时重新解析）
     */
    suspend fun getRecentSongObjects(): List<Song> {
        return try {
            dataStore.data.first().let { prefs ->
                val json = prefs[keyRecentSongObjects] ?: "{\"songs\":[]}"
                val data = gson.fromJson<RecentSongObjectsData>(json, RecentSongObjectsData::class.java)
                data.songs
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to read recent song objects", e)
            emptyList()
        }
    }

    // --- B-4 均衡器 ---
    val equalizerPreset: Flow<EqualizerPreset> = dataStore.data.map { prefs ->
        val ordinal = prefs[keyEqualizerPreset] ?: 0
        EqualizerPreset.entries.getOrElse(ordinal) { EqualizerPreset.NORMAL }
    }

    suspend fun setEqualizerPreset(preset: EqualizerPreset) {
        dataStore.edit { it[keyEqualizerPreset] = preset.ordinal }
    }

    val equalizerBands: Flow<List<Float>> = dataStore.data.map { prefs ->
        val json = prefs[keyEqualizerBands] ?: "[]"
        try {
            gson.fromJson<List<Float>>(json, object : TypeToken<List<Float>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    suspend fun setEqualizerBands(bands: List<Float>) {
        dataStore.edit { it[keyEqualizerBands] = gson.toJson(bands) }
    }

    suspend fun setEqualizerBand(index: Int, value: Float) {
        dataStore.edit { prefs ->
            val json = prefs[keyEqualizerBands] ?: "[]"
            val bands = safeParseJson("equalizer_bands", json) {
                gson.fromJson<List<Float>>(json, object : TypeToken<List<Float>>() {}.type)
                    .toMutableList()
            } ?: return@edit
            while (bands.size <= index) bands.add(0f)
            bands[index] = value
            prefs[keyEqualizerBands] = gson.toJson(bands)
        }
    }

    // --- AppSettings Flow ---
    val appSettings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            darkTheme = prefs[keyDarkTheme] ?: true,
            animationsEnabled = prefs[keyAnimations] ?: true,
            autoPlayNext = prefs[keyAutoPlayNext] ?: true,
            defaultPlayMode = PlayMode.fromOrdinal(prefs[keyPlayMode] ?: 0),
            cacheLyrics = prefs[keyCacheLyrics] ?: true,
            cacheCover = prefs[keyCacheCover] ?: true,
            lyricsOffsetMs = prefs[keyLyricsOffset] ?: 0L,
            defaultNetworkSource = prefs[keyDefaultNetworkSource]?.let { NetworkSource.fromKey(it) ?: NetworkSource.fromName(it) } ?: NetworkSource.DEFAULT,
            metingApiBaseUrl = prefs[keyMetingApiBaseUrl] ?: MetingApiService.DEFAULT_BASE_URL,
            mvApiBaseUrl = prefs[keyMvApiBaseUrl] ?: BilibiliMvService.DEFAULT_BASE_URL,
            lyricsKugouBaseUrl = prefs[keyLyricsKugouBaseUrl] ?: com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_KUGOU_BASE_URL,
            lyricsNeteaseBaseUrl = prefs[keyLyricsNeteaseBaseUrl] ?: com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_NETEASE_BASE_URL,
            visualizerTheme = VisualizerTheme.fromKey(prefs[keyVisualizerTheme]),
            visualizerQuality = VisualQuality.fromKey(prefs[keyVisualizerQuality]),
            fontAdjustment = prefs[keyFontAdjustment] ?: 0,
            modelDownloadUrl = prefs[keyModelDownloadUrl] ?: "",
            language = prefs[keyLanguage] ?: "system",
            downloadEnabled = prefs[keyDownloadEnabled] ?: true,
            autoDownloadOnPlay = prefs[keyAutoDownloadOnPlay] ?: false,
            autoDownloadLimit = prefs[keyAutoDownloadLimit] ?: 50,
            downloadLocation = prefs[keyDownloadLocation] ?: "INTERNAL",
            // ── 照片墙（§7.3）──
            // ⚠️ 「外接存储」默认值**按平台**：电视 true / 手机 false（§6.8）。
            //   其余字段的平台默认值相同，直接写字面量。
            photoWallGalleryEnabled = prefs[keyPhotoWallGalleryEnabled] ?: false,
            photoWallExternalEnabled = prefs[keyPhotoWallExternalEnabled] ?: isTelevisionDevice,
            photoWallJellyfinEnabled = prefs[keyPhotoWallJellyfinEnabled] ?: false,
            photoWallSourceBalance = prefs[keyPhotoWallSourceBalance] ?: false,
            photoWallDirUri = prefs[keyPhotoWallDirUri] ?: "",
            photoWallCommonDirsOnly = prefs[keyPhotoWallCommonDirsOnly] ?: true,
            photoWallFacesOnly = prefs[keyPhotoWallFacesOnly] ?: false,
            photoWallFaceScanDone = prefs[keyPhotoWallFaceScanDone] ?: false,
            photoWallRandomTransition = prefs[keyPhotoWallRandomTransition] ?: true,
            // 两个枚举都走各自 fromKey()（**永不返回 null**，见 §10.172 的 Gson 枚举坑）
            photoWallFixedTransition = PhotoTransitionId.fromKey(prefs[keyPhotoWallFixedTransition]),
            photoWallTransitionMs = prefs[keyPhotoWallTransitionMs] ?: 700,
            photoWallHoldMs = prefs[keyPhotoWallHoldMs] ?: 8000,
            photoWallScaleMode = PhotoScaleMode.fromKey(prefs[keyPhotoWallScaleMode]),
            photoWallKenBurns = prefs[keyPhotoWallKenBurns] ?: true,
            photoWallAudioReactive = prefs[keyPhotoWallAudioReactive] ?: false,
            photoWallPulseZoom = prefs[keyPhotoWallPulseZoom] ?: true,
            photoWallBreathe = prefs[keyPhotoWallBreathe] ?: true
        )
    }

    // --- 全局字体字号调整 ---
    suspend fun setFontAdjustment(adjustment: Int) =
        dataStore.edit { it[keyFontAdjustment] = adjustment }

    suspend fun setDarkTheme(enabled: Boolean) = dataStore.edit { it[keyDarkTheme] = enabled }

    suspend fun setDownloadEnabled(v: Boolean) = dataStore.edit { it[keyDownloadEnabled] = v }
    suspend fun setAutoDownloadOnPlay(v: Boolean) = dataStore.edit { it[keyAutoDownloadOnPlay] = v }
    suspend fun setAutoDownloadLimit(v: Int) = dataStore.edit { it[keyAutoDownloadLimit] = v.coerceIn(1, 5000) }
    suspend fun setDownloadLocation(v: String) = dataStore.edit { it[keyDownloadLocation] = v }

    // --- 导出 SAF 授权持久化 ---
    val exportTreeUri: Flow<String?> = dataStore.data.map { it[keyExportTreeUri] }
    val exportVolumeId: Flow<String?> = dataStore.data.map { it[keyExportVolumeId] }
    suspend fun setExportTreeUri(uri: String?) = dataStore.edit { if (uri == null) it.remove(keyExportTreeUri) else it[keyExportTreeUri] = uri }
    suspend fun setExportVolumeId(id: String?) = dataStore.edit { if (id == null) it.remove(keyExportVolumeId) else it[keyExportVolumeId] = id }

    suspend fun setAnimationsEnabled(enabled: Boolean) = dataStore.edit { it[keyAnimations] = enabled }

    suspend fun setAutoPlayNext(enabled: Boolean) = dataStore.edit { it[keyAutoPlayNext] = enabled }

    suspend fun setDefaultPlayMode(mode: PlayMode) = dataStore.edit { it[keyPlayMode] = mode.ordinal }

    suspend fun setCacheLyrics(enabled: Boolean) = dataStore.edit { it[keyCacheLyrics] = enabled }

    suspend fun setCacheCover(enabled: Boolean) = dataStore.edit { it[keyCacheCover] = enabled }

    suspend fun setLyricsOffset(offsetMs: Long) = dataStore.edit { it[keyLyricsOffset] = offsetMs }

    // --- 歌词显示设置 ---
    private val keyLyricsFontScale = doublePreferencesKey("lyrics_font_scale")

    val lyricsFontScale: Flow<Float> = dataStore.data.map { prefs ->
        (prefs[keyLyricsFontScale] ?: 1.0).toFloat()
    }

    suspend fun setLyricsFontScale(scale: Float) {
        dataStore.edit { it[keyLyricsFontScale] = scale.coerceIn(0.7f, 1.6f).toDouble() }
    }

    suspend fun setDefaultNetworkSource(source: NetworkSource) =
        dataStore.edit { it[keyDefaultNetworkSource] = source.key }

    suspend fun setVisualizerTheme(theme: VisualizerTheme) =
        dataStore.edit { it[keyVisualizerTheme] = theme.name }

    suspend fun setVisualizerQuality(quality: VisualQuality) =
        dataStore.edit { it[keyVisualizerQuality] = quality.name }

    // --- 照片墙（§7.3，17 个 setter）---
    //
    // ⚠️ 两个**数值**字段在这里就做范围钳制（与 `setAutoDownloadLimit` 的既有写法一致）：
    //   范围来自 §7.3「转场时长 300–2000 ms」「停留时长 3000–30000 ms」。
    //   在写入侧钳制 ⇒ 无论是设置页的 `+/-` 还是**备份导入**都不会写进越界值。
    suspend fun setPhotoWallGalleryEnabled(v: Boolean) =
        dataStore.edit { it[keyPhotoWallGalleryEnabled] = v }

    suspend fun setPhotoWallExternalEnabled(v: Boolean) =
        dataStore.edit { it[keyPhotoWallExternalEnabled] = v }

    suspend fun setPhotoWallJellyfinEnabled(v: Boolean) =
        dataStore.edit { it[keyPhotoWallJellyfinEnabled] = v }

    suspend fun setPhotoWallSourceBalance(v: Boolean) =
        dataStore.edit { it[keyPhotoWallSourceBalance] = v }

    suspend fun setPhotoWallDirUri(v: String) =
        dataStore.edit { it[keyPhotoWallDirUri] = v }

    suspend fun setPhotoWallCommonDirsOnly(v: Boolean) =
        dataStore.edit { it[keyPhotoWallCommonDirsOnly] = v }

    suspend fun setPhotoWallFacesOnly(v: Boolean) =
        dataStore.edit { it[keyPhotoWallFacesOnly] = v }

    suspend fun setPhotoWallFaceScanDone(v: Boolean) =
        dataStore.edit { it[keyPhotoWallFaceScanDone] = v }

    suspend fun setPhotoWallRandomTransition(v: Boolean) =
        dataStore.edit { it[keyPhotoWallRandomTransition] = v }

    suspend fun setPhotoWallFixedTransition(v: PhotoTransitionId) =
        dataStore.edit { it[keyPhotoWallFixedTransition] = v.name }

    suspend fun setPhotoWallTransitionMs(v: Int) =
        dataStore.edit { it[keyPhotoWallTransitionMs] = v.coerceIn(PHOTO_WALL_TRANSITION_MS_RANGE) }

    suspend fun setPhotoWallHoldMs(v: Int) =
        dataStore.edit { it[keyPhotoWallHoldMs] = v.coerceIn(PHOTO_WALL_HOLD_MS_RANGE) }

    suspend fun setPhotoWallScaleMode(v: PhotoScaleMode) =
        dataStore.edit { it[keyPhotoWallScaleMode] = v.name }

    suspend fun setPhotoWallKenBurns(v: Boolean) =
        dataStore.edit { it[keyPhotoWallKenBurns] = v }

    suspend fun setPhotoWallAudioReactive(v: Boolean) =
        dataStore.edit { it[keyPhotoWallAudioReactive] = v }

    suspend fun setPhotoWallPulseZoom(v: Boolean) =
        dataStore.edit { it[keyPhotoWallPulseZoom] = v }

    suspend fun setPhotoWallBreathe(v: Boolean) =
        dataStore.edit { it[keyPhotoWallBreathe] = v }

    /**
     * P2 修复（2026-09-22 审查）：网络端点校验。返回 null = 非空白但非法
     * （scheme 非 http/https、无 host、超长）；空串 = 输入本身为空白。
     * 非法值由调用方决定回退（设置页保持旧值 / 备份恢复回落默认），杜绝
     * 任意字符串直接成为请求端点（网络功能静默瘫痪 / 备份重定向流量）。
     */
    private fun normalizeEndpointUrl(raw: String): String? {
        val u = raw.trim().trim('`', '\'', '"').trim()
        if (u.isEmpty()) return ""
        val hostPart = u.removePrefix("https://").removePrefix("http://").substringBefore('/')
        val valid = (u.startsWith("http://") || u.startsWith("https://")) &&
            u.length <= 500 &&
            hostPart.isNotBlank() &&
            (hostPart.contains('.') || hostPart.contains(':') || hostPart == "localhost")
        return if (valid) u else null
    }

    suspend fun setMetingApiBaseUrl(url: String) {
        val normalized = normalizeEndpointUrl(url)
        if (url.isNotBlank() && normalized == null) {
            AppLog.w(TAG, "setMetingApiBaseUrl rejected invalid url: " + url.take(80))
            return
        }
        dataStore.edit { it[keyMetingApiBaseUrl] = normalized.orEmpty() }
    }

    suspend fun setMvApiBaseUrl(url: String) {
        val normalized = normalizeEndpointUrl(url)
        if (url.isNotBlank() && normalized == null) {
            AppLog.w(TAG, "setMvApiBaseUrl rejected invalid url: " + url.take(80))
            return
        }
        dataStore.edit { it[keyMvApiBaseUrl] = normalized.orEmpty() }
    }

    // --- 网络音乐平台来源 ---

    /**
     * 同步获取当前音乐平台来源（用于 MetingApiService 的 serverProvider）
     * 在每次请求时同步读取，支持运行时切换平台。
     *
     * R-7 第三类修复（路 A）：读 @Volatile 内存镜像，无 IO 无阻塞。
     */
    fun getMusicSourceSync(): String = cachedMusicSource

    suspend fun setMusicSource(sourceKey: String) {
        dataStore.edit { it[keyMusicSource] = sourceKey }
    }

    // --- Jamendo（CC 独立音乐）设置 ---
    suspend fun setJamendoClientId(id: String) {
        dataStore.edit { it[keyJamendoClientId] = id.trim() }
    }

    /**
     * 同步读取 Jamendo Client ID（用于 JamendoService 每次请求时读取）。
     * R-7 第三类修复（路 A）：读 @Volatile 内存镜像。
     */
    fun getJamendoClientIdSync(): String = cachedJamendoClientId

    // --- 天气电台设置 ---

    /**
     * 获取天气是否启用
     */
    val weatherEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keyWeatherEnabled] ?: true
    }

    suspend fun setWeatherEnabled(enabled: Boolean) {
        dataStore.edit { it[keyWeatherEnabled] = enabled }
    }

    /**
     * 获取手动设置的城市名（空串=自动定位）
     */
    val weatherManualCity: Flow<String> = dataStore.data.map { prefs ->
        prefs[keyWeatherManualCity] ?: ""
    }

    suspend fun setWeatherManualCity(city: String) {
        dataStore.edit { it[keyWeatherManualCity] = city }
    }

    /**
     * 获取天气自动刷新开关
     */
    val weatherAutoRefresh: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keyWeatherAutoRefresh] ?: true
    }

    suspend fun setWeatherAutoRefresh(enabled: Boolean) {
        dataStore.edit { it[keyWeatherAutoRefresh] = enabled }
    }

    /**
     * OpenWeatherMap API Key
     * 当 Open-Meteo 不可用时的备选天气数据源
     * F-7：读加密键（AES-GCM 解密）；解密失败回退空串
     */
    val weatherApiKey: Flow<String> = dataStore.data.map { prefs ->
        val enc = prefs[keyWeatherApiKeyEnc]
        if (!enc.isNullOrBlank()) {
            try { com.nasmusic.tv.util.CryptoUtils.decrypt(enc) } catch (e: Exception) { "" }
        } else ""
    }

    /**
     * R-7 第二类修复：天气 API Key 同步读改 @Volatile 内存镜像。
     * 调用点 WeatherRadioViewModel.fetchWeather 在 Main 协程内——原先此处
     * runBlocking 会冻住主线程触发 ANR。
     */
    fun getWeatherApiKeySync(): String = cachedWeatherApiKey

    /** F-7：写入加密键（同时清除旧明文键） */
    suspend fun setWeatherApiKey(key: String) {
        val trimmed = key.trim()
        dataStore.edit { prefs ->
            prefs[keyWeatherApiKeyEnc] = com.nasmusic.tv.util.CryptoUtils.encrypt(trimmed)
            prefs.remove(keyWeatherApiKey)  // 迁移后清除明文
        }
    }

    /**
     * F-7：一次性迁移——旧明文键有值且加密键为空时，加密搬移。
     * 幂等；由 startProviderMirrors 前（NasMusicApp.onCreate）调用。
     */
    private suspend fun migrateWeatherApiKeyIfNeeded() {
        val prefs = dataStore.data.first()
        val legacy = prefs[keyWeatherApiKey]
        if (!legacy.isNullOrBlank() && prefs[keyWeatherApiKeyEnc].isNullOrBlank()) {
            setWeatherApiKey(legacy)
            AppLog.i(TAG, "weather API key migrated to encrypted storage")
        }
    }

    // --- 封面滤镜设置 ---

    val coverFilterEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keyCoverFilterEnabled] ?: false
    }

    suspend fun setCoverFilterEnabled(enabled: Boolean) {
        dataStore.edit { it[keyCoverFilterEnabled] = enabled }
    }

    val coverFilterBlurRadius: Flow<Float> = dataStore.data.map { prefs ->
        (prefs[keyCoverFilterBlurRadius] ?: 8.0).toFloat()
    }

    suspend fun setCoverFilterBlurRadius(radius: Float) {
        dataStore.edit { it[keyCoverFilterBlurRadius] = radius.toDouble() }
    }

    val coverFilterDarkOverlay: Flow<Float> = dataStore.data.map { prefs ->
        (prefs[keyCoverFilterDarkOverlay] ?: 0.3).toFloat()
    }

    suspend fun setCoverFilterDarkOverlay(overlay: Float) {
        dataStore.edit { it[keyCoverFilterDarkOverlay] = overlay.toDouble() }
    }

    suspend fun setSpectrumEnabled(enabled: Boolean) {
        dataStore.edit { it[keySpectrumEnabled] = enabled }
    }

    // --- F2-5 跨曲交叉淡入淡出 ---
    private val keyCrossfadeEnabled = booleanPreferencesKey("settings_crossfade_enabled")
    private val keyCrossfadeDurationSec = intPreferencesKey("settings_crossfade_duration_sec")

    // --- F2-6 音质分级 ---
    /** 音质档位：AUTO(0，按带宽) / LOSSLESS(999) / HIGH(320) / STANDARD(128) */
    private val keyQualityTier = intPreferencesKey("settings_quality_tier")

    val crossfadeEnabled: Flow<Boolean> = dataStore.data.map { it[keyCrossfadeEnabled] ?: false }
    val crossfadeDurationSec: Flow<Int> = dataStore.data.map { it[keyCrossfadeDurationSec] ?: 4 }

    suspend fun setCrossfadeEnabled(enabled: Boolean) {
        dataStore.edit { it[keyCrossfadeEnabled] = enabled }
    }

    suspend fun setCrossfadeDurationSec(sec: Int) {
        dataStore.edit { it[keyCrossfadeDurationSec] = sec.coerceIn(1, 12) }
    }

    /** F2-6：音质档位（Meting br 参数；AUTO 时不传由 BandwidthEstimator 决策） */
    val qualityTier: Flow<Int> = dataStore.data.map { it[keyQualityTier] ?: QUALITY_TIER_AUTO }

    /**
     * v2.35.0 多码率：档位变化后的缓存失效回调（修 G5）。
     *
     * 由 NasMusicApp 注册为 `{ networkMusicManager.clearPlayUrlCache() }`。
     * 用回调而非直接引用 NetworkMusicManager，避免 data/prefs 反向依赖 backend/network
     * （与项目既有 provider 注入风格一致）。
     */
    @Volatile
    private var onQualityTierChanged: (() -> Unit)? = null

    /** 注册档位变化回调（仅 NasMusicApp 调用一次） */
    fun setOnQualityTierChanged(cb: () -> Unit) {
        onQualityTierChanged = cb
    }

    suspend fun setQualityTier(tier: Int) {
        dataStore.edit { it[keyQualityTier] = tier }
        // 直链是"音源 × 歌曲 × 码率"三元组绑定的时效性资源，档位一变旧链接语义全失效。
        // 缓存上限仅 500 条，全量清除代价可忽略（方案 §2.4）。
        runCatching { onQualityTierChanged?.invoke() }
            .onFailure { AppLog.w(TAG, "onQualityTierChanged failed: ${it.message}") }
    }

    /**
     * 同步获取当前默认网络源（用于 NetworkMusicManager 的 defaultSourceProvider）。
     * R-7 第三类修复（路 A）：读 @Volatile 内存镜像。
     */
    fun getDefaultNetworkSourceSync(): String = cachedDefaultNetworkSource

    /** F2-6：同步读取音质档位（MetingApiService qualityTierProvider 用） */
    fun getQualityTierSync(): Int = cachedQualityTier

    /**
     * 同步获取 Meting-API 端点 URL（用于 MetingApiService 的 baseUrlProvider）。
     * R-7 第三类修复（路 A）：读 @Volatile 内存镜像。
     */
    fun getMetingApiBaseUrlSync(): String = cachedMetingApiBaseUrl

    /**
     * 同步获取 MTV 视频搜索端点 URL（用于 BilibiliMvService 的 baseUrlProvider）。
     * R-7 第三类修复（路 A）：读 @Volatile 内存镜像。
     */
    fun getMvApiBaseUrlSync(): String = cachedMvApiBaseUrl

    /**
     * 同步获取高质量分离模型自定义下载 URL（用于 ModelDownloadManager 的 customUrlProvider）。
     * 空串表示使用内置默认候选列表（hf-mirror → huggingface）。
     */
    fun getModelDownloadUrlSync(): String = cachedModelDownloadUrl

    /** 设置模型自定义下载 URL（空串=恢复默认候选列表） */
    suspend fun setModelDownloadUrl(url: String) {
        val normalized = normalizeEndpointUrl(url)
        if (url.isNotBlank() && normalized == null) {
            AppLog.w(TAG, "setModelDownloadUrl rejected invalid url: " + url.take(80))
            return
        }
        dataStore.edit { it[keyModelDownloadUrl] = normalized.orEmpty() }
    }

    // --- 网络歌词端点（Kugou / Netease）---
    /** R-7 第三类修复（路 A）：读 @Volatile 内存镜像（原主线程急切求值 runBlocking） */
    fun getLyricsKugouBaseUrlSync(): String = cachedLyricsKugouBaseUrl

    /** R-7 第三类修复（路 A）：读 @Volatile 内存镜像（原主线程急切求值 runBlocking） */
    fun getLyricsNeteaseBaseUrlSync(): String = cachedLyricsNeteaseBaseUrl

    suspend fun setLyricsKugouBaseUrl(url: String) {
        val normalized = normalizeEndpointUrl(url)
        if (url.isNotBlank() && normalized == null) {
            AppLog.w(TAG, "setLyricsKugouBaseUrl rejected invalid url: " + url.take(80))
            return
        }
        dataStore.edit { it[keyLyricsKugouBaseUrl] = normalized.orEmpty() }
    }

    suspend fun setLyricsNeteaseBaseUrl(url: String) {
        val normalized = normalizeEndpointUrl(url)
        if (url.isNotBlank() && normalized == null) {
            AppLog.w(TAG, "setLyricsNeteaseBaseUrl rejected invalid url: " + url.take(80))
            return
        }
        dataStore.edit { it[keyLyricsNeteaseBaseUrl] = normalized.orEmpty() }
    }

    // --- 网络歌曲收藏 ---

    /**
     * 网络收藏列表 Flow（响应式，收藏变化时自动更新）
     */
    val networkFavorites: Flow<List<NetworkFavoriteItem>> = dataStore.data.map { prefs ->
        val json = prefs[keyNetworkFavorites] ?: "[]"
        try {
            gson.fromJson<List<NetworkFavoriteItem>>(json, object : TypeToken<List<NetworkFavoriteItem>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    /**
     * 同步获取网络收藏列表（用于 isFavorite 判断，非 Flow）
     */
    suspend fun getNetworkFavorites(): List<NetworkFavoriteItem> {
        return try {
            dataStore.data.first().let { prefs ->
                val json = prefs[keyNetworkFavorites] ?: "[]"
                gson.fromJson<List<NetworkFavoriteItem>>(json, object : TypeToken<List<NetworkFavoriteItem>>() {}.type) ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 切换网络收藏状态（已收藏则取消，未收藏则添加）
     * 新收藏的歌曲添加到列表头部（最新在前）
     * 超过 networkFavoritesMaxSize（500 条）时自动清理最旧的收藏
     */
    suspend fun toggleNetworkFavorite(item: NetworkFavoriteItem) {
        dataStore.edit { prefs ->
            val json = prefs[keyNetworkFavorites] ?: "[]"
            val list = safeParseJson("network_favorites", json) {
                gson.fromJson<MutableList<NetworkFavoriteItem>>(json, object : TypeToken<MutableList<NetworkFavoriteItem>>() {}.type)
            } ?: return@edit  // 解析失败：跳过回写，保留原数据

            val mutable = list.toMutableList()
            val existing = mutable.indexOfFirst { it.songId == item.songId }
            if (existing >= 0) {
                mutable.removeAt(existing)  // 取消收藏
            } else {
                mutable.add(0, item)  // 添加收藏（最新在前）
                // LRU 上限：超过 500 条时移除最旧的（列表末尾）
                while (mutable.size > networkFavoritesMaxSize) {
                    mutable.removeAt(mutable.size - 1)
                }
            }
            prefs[keyNetworkFavorites] = gson.toJson(mutable)
        }
    }

    // --- 本地歌单（DataStore JSON，独立于 NAS 后端歌单）---

    /**
     * 本地歌单列表 Flow（响应式，歌单变化时自动更新）
     */
    val localPlaylists: Flow<List<LocalPlaylist>> = dataStore.data.map { prefs ->
        val json = prefs[keyLocalPlaylists] ?: "[]"
        try {
            gson.fromJson<List<LocalPlaylist>>(json, object : TypeToken<List<LocalPlaylist>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    /**
     * 一次性读取本地歌单列表
     */
    suspend fun getLocalPlaylists(): List<LocalPlaylist> {
        return try {
            dataStore.data.first().let { prefs ->
                val json = prefs[keyLocalPlaylists] ?: "[]"
                gson.fromJson<List<LocalPlaylist>>(json, object : TypeToken<List<LocalPlaylist>>() {}.type) ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 创建本地歌单（最新创建的放最前）
     */
    suspend fun createLocalPlaylist(name: String): LocalPlaylist {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Playlist name must not be empty" }
        lateinit var created: LocalPlaylist
        dataStore.edit { prefs ->
            val json = prefs[keyLocalPlaylists] ?: "[]"
            val list = safeParseJson("local_playlists", json) {
                gson.fromJson<MutableList<LocalPlaylist>>(json, object : TypeToken<MutableList<LocalPlaylist>>() {}.type)
            } ?: mutableListOf()  // 歌单数据损坏时仍可创建新歌单（原数据保留，本函数不覆盖）

            val playlist = LocalPlaylist(
                id = java.util.UUID.randomUUID().toString(),
                name = trimmed,
                createdAt = System.currentTimeMillis()
            )
            val mutable = list.toMutableList()
            mutable.add(0, playlist)
            prefs[keyLocalPlaylists] = gson.toJson(mutable)
            created = playlist
        }
        return created
    }

    /**
     * 重命名本地歌单
     */
    suspend fun renameLocalPlaylist(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val json = prefs[keyLocalPlaylists] ?: "[]"
            val list = safeParseJson("local_playlists", json) {
                gson.fromJson<MutableList<LocalPlaylist>>(json, object : TypeToken<MutableList<LocalPlaylist>>() {}.type)
            } ?: return@edit

            val mutable = list.toMutableList()
            val idx = mutable.indexOfFirst { it.id == id }
            if (idx >= 0) {
                mutable[idx] = mutable[idx].copy(name = trimmed)
                prefs[keyLocalPlaylists] = gson.toJson(mutable)
            }
        }
    }

    /**
     * 删除本地歌单
     */
    suspend fun deleteLocalPlaylist(id: String) {
        dataStore.edit { prefs ->
            val json = prefs[keyLocalPlaylists] ?: "[]"
            val list = safeParseJson("local_playlists", json) {
                gson.fromJson<MutableList<LocalPlaylist>>(json, object : TypeToken<MutableList<LocalPlaylist>>() {}.type)
            } ?: return@edit

            val mutable = list.toMutableList()
            if (mutable.removeAll { it.id == id }) {
                prefs[keyLocalPlaylists] = gson.toJson(mutable)
            }
        }
    }

    /**
     * 添加歌曲到本地歌单（按 song.id 去重；仅网络歌曲 streamUrl 置空不持久化）
     * @return true 添加成功；false 歌单不存在或歌曲已在歌单中
     */
    suspend fun addSongToPlaylist(playlistId: String, song: Song): Boolean {
        var added = false
        dataStore.edit { prefs ->
            val json = prefs[keyLocalPlaylists] ?: "[]"
            val list = safeParseJson("local_playlists", json) {
                gson.fromJson<MutableList<LocalPlaylist>>(json, object : TypeToken<MutableList<LocalPlaylist>>() {}.type)
            } ?: return@edit

            val mutable = list.toMutableList()
            val idx = mutable.indexOfFirst { it.id == playlistId }
            if (idx < 0) {
                added = false
            } else {
                val current = mutable[idx]
                if (current.songs.any { it.id == song.id }) {
                    added = false
                } else {
                    mutable[idx] = current.copy(songs = current.songs + song.stripVolatileStreamUrl())
                    prefs[keyLocalPlaylists] = gson.toJson(mutable)
                    added = true
                }
            }
        }
        return added
    }

    /**
     * 从本地歌单移除歌曲
     */
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String) {
        dataStore.edit { prefs ->
            val json = prefs[keyLocalPlaylists] ?: "[]"
            val list = safeParseJson("local_playlists", json) {
                gson.fromJson<MutableList<LocalPlaylist>>(json, object : TypeToken<MutableList<LocalPlaylist>>() {}.type)
            } ?: return@edit

            val mutable = list.toMutableList()
            val idx = mutable.indexOfFirst { it.id == playlistId }
            if (idx >= 0) {
                val current = mutable[idx]
                mutable[idx] = current.copy(songs = current.songs.filterNot { it.id == songId })
                prefs[keyLocalPlaylists] = gson.toJson(mutable)
            }
        }
    }

    /**
     * 用 newSong 替换歌单中的旧歌曲（补全命中后写回）。
     * oldSongId 不存在则忽略（并发补全时目标可能已被替换）。
     */
    suspend fun replaceSongInPlaylist(playlistId: String, oldSongId: String, newSong: Song) {
        dataStore.edit { prefs ->
            val json = prefs[keyLocalPlaylists] ?: "[]"
            val list = safeParseJson("local_playlists", json) {
                gson.fromJson<MutableList<LocalPlaylist>>(json, object : TypeToken<MutableList<LocalPlaylist>>() {}.type)
            } ?: return@edit

            val mutable = list.toMutableList()
            val idx = mutable.indexOfFirst { it.id == playlistId }
            if (idx < 0) return@edit
            val current = mutable[idx]
            val songIdx = current.songs.indexOfFirst { it.id == oldSongId }
            if (songIdx < 0) return@edit
            val updatedSongs = current.songs.toMutableList().apply {
                this[songIdx] = newSong.stripVolatileStreamUrl()
            }
            mutable[idx] = current.copy(songs = updatedSongs)
            prefs[keyLocalPlaylists] = gson.toJson(mutable)
        }
    }

    // --- 歌单导入历史（最近 20 条；删除歌单时联动清理）---

    val playlistImportHistory: Flow<List<com.nasmusic.tv.data.model.PlaylistImportHistoryItem>> =
        dataStore.data.map { prefs ->
            val json = prefs[keyPlaylistImportHistory] ?: "[]"
            try {
                gson.fromJson<List<com.nasmusic.tv.data.model.PlaylistImportHistoryItem>>(
                    json,
                    object : TypeToken<List<com.nasmusic.tv.data.model.PlaylistImportHistoryItem>>() {}.type
                ) ?: emptyList()
            } catch (e: Exception) { emptyList() }
        }

    suspend fun recordPlaylistImport(item: com.nasmusic.tv.data.model.PlaylistImportHistoryItem) {
        dataStore.edit { prefs ->
            val json = prefs[keyPlaylistImportHistory] ?: "[]"
            val list = safeParseJson("playlist_import_history", json) {
                gson.fromJson<MutableList<com.nasmusic.tv.data.model.PlaylistImportHistoryItem>>(
                    json,
                    object : TypeToken<MutableList<com.nasmusic.tv.data.model.PlaylistImportHistoryItem>>() {}.type
                )
            } ?: mutableListOf()
            val mutable = list.toMutableList()
            mutable.removeAll { it.playlistId == item.playlistId }
            mutable.add(0, item)
            while (mutable.size > playlistImportHistoryMaxSize) {
                mutable.removeAt(mutable.size - 1)
            }
            prefs[keyPlaylistImportHistory] = gson.toJson(mutable)
        }
    }

    /** 删除歌单时联动清理导入历史（无单独删除入口，UI 侧由 consumeHistoryIfDeleted 调用） */
    suspend fun deletePlaylistImportHistory(playlistId: String) {
        dataStore.edit { prefs ->
            val json = prefs[keyPlaylistImportHistory] ?: "[]"
            val list = safeParseJson("playlist_import_history", json) {
                gson.fromJson<MutableList<com.nasmusic.tv.data.model.PlaylistImportHistoryItem>>(
                    json,
                    object : TypeToken<MutableList<com.nasmusic.tv.data.model.PlaylistImportHistoryItem>>() {}.type
                )
            } ?: return@edit
            val mutable = list.toMutableList()
            if (mutable.removeAll { it.playlistId == playlistId }) {
                prefs[keyPlaylistImportHistory] = gson.toJson(mutable)
            }
        }
    }

    // --- URL 可达性持久化（旁路状态，与 Song 解耦；只记录非 REACHABLE 判定）---
    // 内存 5 分钟缓存为运行时权威（UrlReachabilityChecker 内部）；本层仅在启动时
    // 初始化（24h 判定窗口内不重测）与 MARK/CLEAR 两处写时机落库。

    data class ReachabilityEntry(val result: String, val checkedAt: Long)

    /** 当前已知不可达的 URL 直链判定（songId → 判定），供 UI 显示「🔗 URL 已失效」 */
    val songReachability: Flow<Map<String, ReachabilityEntry>> = dataStore.data.map { prefs ->
        val json = prefs[keySongReachability] ?: "{}"
        try {
            gson.fromJson<Map<String, ReachabilityEntry>>(json, object : TypeToken<Map<String, ReachabilityEntry>>() {}.type)
                ?: emptyMap()
        } catch (e: Exception) { emptyMap() }
    }

    suspend fun getSongReachability(): Map<String, ReachabilityEntry> {
        return try {
            dataStore.data.first().let { prefs ->
                val json = prefs[keySongReachability] ?: "{}"
                gson.fromJson<Map<String, ReachabilityEntry>>(json, object : TypeToken<Map<String, ReachabilityEntry>>() {}.type)
                    ?: emptyMap()
            }
        } catch (e: Exception) { emptyMap() }
    }

    /** 标记歌曲 URL 不可达（UI 显示「🔗 URL 已失效」；不修改 songs 列表） */
    suspend fun markSongUnreachable(songId: String, result: String = "NOT_REACHABLE") {
        dataStore.edit { prefs ->
            val json = prefs[keySongReachability] ?: "{}"
            val map = safeParseJson("song_reachability", json) {
                gson.fromJson<MutableMap<String, ReachabilityEntry>>(json, object : TypeToken<MutableMap<String, ReachabilityEntry>>() {}.type)
            } ?: mutableMapOf()
            map[songId] = ReachabilityEntry(result = result, checkedAt = System.currentTimeMillis())
            prefs[keySongReachability] = gson.toJson(map)
        }
    }

    /** 清除歌曲 URL 不可达标记（补全成功替换后调用，避免旧标记残留） */
    suspend fun clearSongUnreachable(songId: String) {
        dataStore.edit { prefs ->
            val json = prefs[keySongReachability] ?: "{}"
            val map = safeParseJson("song_reachability", json) {
                gson.fromJson<MutableMap<String, ReachabilityEntry>>(json, object : TypeToken<MutableMap<String, ReachabilityEntry>>() {}.type)
            } ?: return@edit
            if (map.remove(songId) != null) {
                prefs[keySongReachability] = gson.toJson(map)
            }
        }
    }

    // --- 上次播放队列持久化 ---

    /**
     * 上次播放队列的持久化数据结构
     * @param songs 队列歌曲列表（streamUrl 置空，播放时重新解析）
     * @param currentIndex 当前播放索引
     */
    data class LastQueueData(
        val songs: List<Song>,
        val currentIndex: Int
    )

    /**
     * 保存上次播放队列
     * 仅网络歌曲 streamUrl 置空后序列化（直链有时效）；本地歌曲保留 file:// URI
     */
    suspend fun saveLastQueue(songs: List<Song>, currentIndex: Int) {
        dataStore.edit { prefs ->
            val songsToSave = songs.map { it.stripVolatileStreamUrl() }
            val data = LastQueueData(songsToSave, currentIndex)
            prefs[keyLastQueue] = gson.toJson(data)
        }
    }

    /**
     * 读取上次播放队列（用于应用启动时恢复，调用方需在协程中）
     */
    suspend fun getLastQueue(): LastQueueData? {
        return try {
            dataStore.data.first().let { prefs ->
                val json = prefs[keyLastQueue] ?: return@let null
                gson.fromJson<LastQueueData>(json, LastQueueData::class.java)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 清除上次播放队列（用户主动清空队列时调用）
     */
    suspend fun clearLastQueue() {
        dataStore.edit { prefs ->
            prefs.remove(keyLastQueue)
        }
    }

    // ========== 播放统计 ==========

    private data class PlayRecordsData(
        val records: List<com.nasmusic.tv.data.model.PlayRecord> = emptyList()
    )

    /**
     * 记录一次播放
     */
    suspend fun addPlayRecord(record: com.nasmusic.tv.data.model.PlayRecord) {
        dataStore.edit { prefs ->
            val json = prefs[keyPlayRecords] ?: "{\"records\":[]}"
            val data = safeParseJson("play_records", json) {
                gson.fromJson<PlayRecordsData>(json, PlayRecordsData::class.java)
            } ?: return@edit  // 解析失败：跳过回写，保留原数据
            // 最多保留 500 条记录
            val updated = PlayRecordsData(
                records = (listOf(record) + data.records).take(500)
            )
            prefs[keyPlayRecords] = gson.toJson(updated)
        }
    }

    /**
     * 获取所有播放记录
     */
    suspend fun getPlayRecords(): List<com.nasmusic.tv.data.model.PlayRecord> {
        return try {
            dataStore.data.first().let { prefs ->
                val json = prefs[keyPlayRecords] ?: return emptyList()
                val data = gson.fromJson<PlayRecordsData>(json, PlayRecordsData::class.java)
                data.records
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to read play records", e)
            emptyList()
        }
    }

    /**
     * 清除所有播放记录
     */
    suspend fun clearPlayRecords() {
        dataStore.edit { prefs ->
            prefs.remove(keyPlayRecords)
        }
    }

    // ========== 搜索历史 ==========

    /**
     * 搜索历史列表 Flow（响应式）
     * 列表按 lastSearchedAt 降序（最新在前）
     */
    val searchHistory: Flow<List<SearchHistoryItem>> = dataStore.data.map { prefs ->
        val json = prefs[keySearchHistory] ?: "[]"
        try {
            gson.fromJson<List<SearchHistoryItem>>(json, object : TypeToken<List<SearchHistoryItem>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    /**
     * 一次性读取搜索历史
     */
    suspend fun getSearchHistory(): List<SearchHistoryItem> {
        return try {
            dataStore.data.first().let { prefs ->
                val json = prefs[keySearchHistory] ?: "[]"
                gson.fromJson<List<SearchHistoryItem>>(json, object : TypeToken<List<SearchHistoryItem>>() {}.type) ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 记录一次搜索
     * 1. 同名 query 合并：count+1、lastSearchedAt 更新为当前时间、移到列表头部
     * 2. 清理超过 30 天的条目
     * 3. 超过 200 条上限时裁剪尾部
     */
    suspend fun recordSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val now = System.currentTimeMillis()
        dataStore.edit { prefs ->
            val json = prefs[keySearchHistory] ?: "[]"
            val list = safeParseJson("search_history", json) {
                gson.fromJson<MutableList<SearchHistoryItem>>(json, object : TypeToken<MutableList<SearchHistoryItem>>() {}.type)
            } ?: return@edit

            val mutable = list.toMutableList()
            // 合并同名条目
            val existingIdx = mutable.indexOfFirst { it.query == trimmed }
            if (existingIdx >= 0) {
                val existing = mutable.removeAt(existingIdx)
                mutable.add(0, existing.copy(lastSearchedAt = now, count = existing.count + 1))
            } else {
                mutable.add(0, SearchHistoryItem(query = trimmed, lastSearchedAt = now, count = 1))
            }
            // 30 天 TTL 清理
            val cutoff = now - searchHistoryTtlMs
            mutable.removeAll { it.lastSearchedAt < cutoff }
            // 数量上限裁剪
            if (mutable.size > searchHistoryMaxSize) {
                mutable.subList(searchHistoryMaxSize, mutable.size).clear()
            }
            prefs[keySearchHistory] = gson.toJson(mutable)
        }
    }

    /**
     * 清理超过 30 天的搜索历史条目
     * 在应用启动时调用一次
     */
    suspend fun purgeExpiredSearchHistory() {
        val now = System.currentTimeMillis()
        val cutoff = now - searchHistoryTtlMs
        dataStore.edit { prefs ->
            val json = prefs[keySearchHistory] ?: return@edit
            val list = safeParseJson("search_history", json) {
                gson.fromJson<MutableList<SearchHistoryItem>>(json, object : TypeToken<MutableList<SearchHistoryItem>>() {}.type)
            } ?: return@edit

            val mutable = list.toMutableList()
            val beforeSize = mutable.size
            mutable.removeAll { it.lastSearchedAt < cutoff }
            if (mutable.size != beforeSize) {
                prefs[keySearchHistory] = gson.toJson(mutable)
            }
        }
    }

    // ========== 数据备份 ==========

    /**
     * 备份文件数据结构
     *
     * 注意：不包含敏感字段 —— 密码、API Token、天气 API Key 一律不导出。
     * 恢复后服务器需重新输入密码连接（isConnected=false）。
     */
    // ===================== 网盘配置（CloudDriveConfig，按 CloudDriveType 存取）=====================
    // 存储：keyCloudDriveConfig 存 JSON Map<type.key, CloudDriveConfigJson>，其中 tokens 的 accessToken/refreshToken 用 CryptoUtils 加密。

    /**
     * 同步读取某网盘配置。
     *
     * R-7 第四类：保留 runBlocking（通用同步入口，供非协程上下文兜底；禁止主线程协程内调用）。
     * T2（2026-09-14）第一批：外部 UI/App 调用点迁移到 baiduConfigFlow.first()；
     * 第二批：百度 token/配置便捷方法全部改为 suspend + baiduConfigFlow.first()，
     * BaiduOAuthClient/BaiduNetdiskService/BaiduMvFileService 调用方已同步迁移，本入口仅作通用兜底。
     */
    @androidx.annotation.WorkerThread
    fun getCloudDriveConfigSync(type: CloudDriveType): CloudDriveConfig? {
        return try {
            runBlocking(Dispatchers.IO) {
                val json = dataStore.data.first()[keyCloudDriveConfig] ?: return@runBlocking null
                loadCloudDriveConfigs(json)[type.key]
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "getCloudDriveConfigSync error", e)
            null
        }
    }

    /** 百度配置 Flow（修复 H-3：UI 订阅用，替代组合内 runBlocking 同步读） */
    val baiduConfigFlow: Flow<CloudDriveConfig> = dataStore.data.map { prefs ->
        loadCloudDriveConfigs(prefs[keyCloudDriveConfig] ?: "{}")[CloudDriveType.BAIDU.key]
            ?: CloudDriveConfig(CloudDriveType.BAIDU)
    }

    /** Jamendo Client ID Flow（修复 H-3：同上） */
    val jamendoClientIdFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[keyJamendoClientId] ?: ""
    }

    /** 异步保存某网盘配置（增量合并到现有 Map） */
    suspend fun saveCloudDriveConfig(config: CloudDriveConfig) {
        dataStore.edit { prefs ->
            val json = prefs[keyCloudDriveConfig] ?: "{}"
            val map = loadCloudDriveConfigs(json).toMutableMap()
            map[config.type.key] = config
            prefs[keyCloudDriveConfig] = saveCloudDriveConfigs(map)
        }
    }

    /** 同步保存（OAuth 客户端在非协程上下文调用；R-7 第四类：保留 runBlocking，禁止主线程协程内调用） */
    @androidx.annotation.WorkerThread
    fun saveCloudDriveConfigSync(config: CloudDriveConfig) {
        runBlocking(Dispatchers.IO) { saveCloudDriveConfig(config) }
    }

    // ---- 百度 token 便捷读写（加解密 accessToken/refreshToken；T2 第二批：suspend + baiduConfigFlow.first()）----

    /** 异步读取百度 token（解密） */
    suspend fun getBaiduTokens(): BaiduTokens? {
        val cfg = baiduConfigFlow.first()
        val t = cfg.tokens ?: return null
        return try {
            val decAt = CryptoUtils.decrypt(t.accessToken).ifBlank { return null }
            val decRt = CryptoUtils.decrypt(t.refreshToken).ifBlank { return null }
            AppLog.d(TAG, "getBaiduTokens: token decrypted (len=${decAt.length}), expiresAt=${t.expiresAt}")
            t.copy(accessToken = decAt, refreshToken = decRt)
        } catch (e: Exception) {
            AppLog.w(TAG, "getBaiduTokens decrypt error", e)
            null
        }
    }

    /** 异步保存百度 token（加密） */
    suspend fun saveBaiduTokens(tokens: BaiduTokens) {
        val cfg = baiduConfigFlow.first().copy(
            tokens = tokens.copy(
                accessToken = CryptoUtils.encrypt(tokens.accessToken),
                refreshToken = CryptoUtils.encrypt(tokens.refreshToken)
            )
        )
        saveCloudDriveConfig(cfg)
    }

    /** 清除百度 token（登出/刷新失败降级用） */
    suspend fun clearBaiduTokens() {
        val cfg = baiduConfigFlow.first().copy(tokens = null)
        saveCloudDriveConfig(cfg)
    }

    // ---- 百度配置项便捷存取 ----

    suspend fun getBaiduEnabled(): Boolean = baiduConfigFlow.first().enabled
    suspend fun setBaiduEnabled(enabled: Boolean) =
        saveCloudDriveConfig(baiduConfigFlow.first().copy(enabled = enabled))
    suspend fun getBaiduMusicRootDir(): String {
        val saved = baiduConfigFlow.first().musicRootDir
        val appDir = com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig.APP_DIR
        // 空白或不在沙盒 /apps/NASMusicTV 下的旧路径，自动纠正为沙盒目录
        val corrected = saved.ifBlank { appDir }
        if (corrected != appDir && !corrected.startsWith(appDir)) {
            com.nasmusic.tv.util.AppLog.w("AppPreferences", "musicRootDir='$corrected' outside sandbox, resetting to $appDir")
            setBaiduMusicRootDir(appDir)
            return appDir
        }
        return corrected
    }
    suspend fun setBaiduMusicRootDir(dir: String) =
        saveCloudDriveConfig(baiduConfigFlow.first().copy(musicRootDir = dir))
    suspend fun getBaiduMvDir(): String? {
        val saved = baiduConfigFlow.first().mvDir
        val appDir = com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig.APP_DIR
        // MV 目录不在沙盒下则视为无效，返回 null（调用方会 fallback 到 musicRootDir）
        if (saved != null && saved.isNotBlank() && !saved.startsWith(appDir)) {
            com.nasmusic.tv.util.AppLog.w("AppPreferences", "mvDir='$saved' outside sandbox, clearing")
            setBaiduMvDir(null)
            return null
        }
        return saved
    }
    suspend fun setBaiduMvDir(dir: String?) =
        saveCloudDriveConfig(baiduConfigFlow.first().copy(mvDir = dir))
    suspend fun getBaiduCustomAppKey(): String? = baiduConfigFlow.first().customAppKey
    suspend fun setBaiduCustomAppKey(key: String?) =
        saveCloudDriveConfig(baiduConfigFlow.first().copy(customAppKey = key))
    suspend fun getBaiduCustomSecretKey(): String? = baiduConfigFlow.first().customSecretKey
    suspend fun setBaiduCustomSecretKey(secret: String?) =
        saveCloudDriveConfig(baiduConfigFlow.first().copy(customSecretKey = secret))
    suspend fun getBaiduApiDriftNotified(): Boolean = baiduConfigFlow.first().apiDriftNotified
    suspend fun setBaiduApiDriftNotified(v: Boolean) =
        saveCloudDriveConfig(baiduConfigFlow.first().copy(apiDriftNotified = v))

    // ---- 内部：CloudDriveConfig Map <-> JSON（tokens 加密）----

    private fun loadCloudDriveConfigs(json: String): Map<String, CloudDriveConfig> {
        return try {
            val type = object : TypeToken<Map<String, CloudDriveConfig>>() {}.type
            val raw: Map<String, CloudDriveConfig> = gson.fromJson(json, type) ?: emptyMap()
            raw
        } catch (e: Exception) {
            AppLog.w(TAG, "loadCloudDriveConfigs parse error", e)
            emptyMap()
        }
    }

    private fun saveCloudDriveConfigs(map: Map<String, CloudDriveConfig>): String =
        try { gson.toJson(map) } catch (e: Exception) { "{}" }

    data class BackupData(
        val version: Int = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val serverConfig: ServerConfig? = null,
        val appSettings: AppSettings? = null,
        val networkFavorites: List<NetworkFavoriteItem> = emptyList(),
        val localPlaylists: List<LocalPlaylist> = emptyList(),
        // v3: 歌单导入历史与 URL 可达性状态（旧备份文件恢复时用默认值）
        val playlistImportHistory: List<com.nasmusic.tv.data.model.PlaylistImportHistoryItem> = emptyList(),
        val songReachability: Map<String, ReachabilityEntry> = emptyMap(),
        val lastQueue: LastQueueData? = null,
        val recentSongIds: List<String> = emptyList(),
        val recentSongObjects: List<Song> = emptyList(),
        val playCounts: Map<String, Int> = emptyMap(),
        val playRecords: List<com.nasmusic.tv.data.model.PlayRecord> = emptyList(),
        val searchHistory: List<SearchHistoryItem> = emptyList(),
        val equalizerPreset: EqualizerPreset? = null,
        val equalizerBands: List<Float> = emptyList(),
        // v2: 新增备份项（旧备份文件恢复时用默认值）
        val mvCacheEntries: List<com.nasmusic.tv.data.model.MvCacheEntry> = emptyList(),
        val weatherEnabled: Boolean = true,
        val weatherManualCity: String = "",
        val weatherAutoRefresh: Boolean = true,
        val coverFilterEnabled: Boolean = false,
        val coverFilterBlurRadius: Double = 8.0,
        val coverFilterDarkOverlay: Double = 0.3,
        val musicSource: String = "",
        val lyricsFontScale: Double = 1.0
    )

    /**
     * 导出备份数据（敏感字段已排除：密码、API Token、天气 API Key）
     */
    suspend fun exportBackupData(): BackupData {
        val config = serverConfig.first()
        val ds = dataStore.data.first()
        return BackupData(
            serverConfig = if (config.baseUrl.isNotBlank()) {
                config.copy(apiToken = "", password = "", isConnected = false)
            } else null,
            appSettings = appSettings.first(),
            networkFavorites = getNetworkFavorites(),
            localPlaylists = getLocalPlaylists(),
            playlistImportHistory = runCatching {
                dataStore.data.first().let { prefs ->
                    gson.fromJson<List<com.nasmusic.tv.data.model.PlaylistImportHistoryItem>>(
                        prefs[keyPlaylistImportHistory] ?: "[]",
                        object : TypeToken<List<com.nasmusic.tv.data.model.PlaylistImportHistoryItem>>() {}.type
                    ) ?: emptyList()
                }
            }.getOrDefault(emptyList()),
            songReachability = getSongReachability().filterValues { entry ->
                System.currentTimeMillis() - entry.checkedAt < songReachabilityWindowMs
            },
            lastQueue = getLastQueue(),
            recentSongIds = getRecentSongIds(),
            recentSongObjects = getRecentSongObjects(),
            playCounts = playCounts.first(),
            playRecords = getPlayRecords(),
            searchHistory = getSearchHistory(),
            equalizerPreset = equalizerPreset.first(),
            equalizerBands = equalizerBands.first(),
            // v2: 补全之前遗漏的设置项
            weatherEnabled = ds[keyWeatherEnabled] ?: true,
            weatherManualCity = ds[keyWeatherManualCity] ?: "",
            weatherAutoRefresh = ds[keyWeatherAutoRefresh] ?: true,
            coverFilterEnabled = ds[keyCoverFilterEnabled] ?: false,
            coverFilterBlurRadius = ds[keyCoverFilterBlurRadius] ?: 8.0,
            coverFilterDarkOverlay = ds[keyCoverFilterDarkOverlay] ?: 0.3,
            musicSource = ds[keyMusicSource] ?: "",
            lyricsFontScale = ds[keyLyricsFontScale] ?: 1.0
        )
    }

    /**
     * 导入备份数据并恢复至 DataStore
     * 服务器密码/API Token 不在备份中，恢复后 isConnected=false，需重新输入密码连接
     */
    suspend fun importBackupData(data: BackupData) {
        data.serverConfig?.let { config ->
            saveServerConfig(
                config.copy(apiToken = "", password = "", isConnected = false)
            )
        }
        data.appSettings?.let { settings ->
            dataStore.edit { prefs ->
                prefs[keyDarkTheme] = settings.darkTheme
                prefs[keyAnimations] = settings.animationsEnabled
                prefs[keyAutoPlayNext] = settings.autoPlayNext
                prefs[keyPlayMode] = settings.defaultPlayMode.ordinalOrDefault()
                prefs[keyCacheLyrics] = settings.cacheLyrics
                prefs[keyCacheCover] = settings.cacheCover
                prefs[keyLyricsOffset] = settings.lyricsOffsetMs
                prefs[keyDefaultNetworkSource] = settings.defaultNetworkSource.keyOrDefault()
                // P2 修复（2026-09-22 审查）：恢复路径同样过端点校验——恶意/异常
                // 备份不能把请求流量重定向到任意字符串端点；非法值回落默认。
                prefs[keyMetingApiBaseUrl] =
                    normalizeEndpointUrl(settings.metingApiBaseUrl) ?: MetingApiService.DEFAULT_BASE_URL
                prefs[keyMvApiBaseUrl] =
                    normalizeEndpointUrl(settings.mvApiBaseUrl) ?: BilibiliMvService.DEFAULT_BASE_URL
                prefs[keyModelDownloadUrl] =
                    normalizeEndpointUrl(settings.modelDownloadUrl) ?: ""
                prefs[keyVisualizerTheme] = settings.visualizerTheme.nameOrDefault()
                prefs[keyVisualizerQuality] = settings.visualizerQuality.nameOrDefault()
                // ── 照片墙（§7.3）──
                // ⚠️ 枚举一律走 `nameOrDefault()`：`backupGson` 的容错适配器对
                //   `JsonToken.NULL` 仍会返回 null（只对「名字不认识」做回落），
                //   而 null 经反射写进非空字段后 `.name` 就是 NPE ⇒ 整份备份导入失败。
                //   数值字段一律 `coerceIn`：手改过的备份可能写出越界值。
                prefs[keyPhotoWallGalleryEnabled] = settings.photoWallGalleryEnabled
                prefs[keyPhotoWallExternalEnabled] = settings.photoWallExternalEnabled
                prefs[keyPhotoWallJellyfinEnabled] = settings.photoWallJellyfinEnabled
                prefs[keyPhotoWallSourceBalance] = settings.photoWallSourceBalance
                prefs[keyPhotoWallDirUri] = settings.photoWallDirUri
                prefs[keyPhotoWallCommonDirsOnly] = settings.photoWallCommonDirsOnly
                prefs[keyPhotoWallFacesOnly] = settings.photoWallFacesOnly
                prefs[keyPhotoWallFaceScanDone] = settings.photoWallFaceScanDone
                prefs[keyPhotoWallRandomTransition] = settings.photoWallRandomTransition
                prefs[keyPhotoWallFixedTransition] = settings.photoWallFixedTransition.nameOrDefault()
                prefs[keyPhotoWallTransitionMs] =
                    settings.photoWallTransitionMs.coerceIn(PHOTO_WALL_TRANSITION_MS_RANGE)
                prefs[keyPhotoWallHoldMs] =
                    settings.photoWallHoldMs.coerceIn(PHOTO_WALL_HOLD_MS_RANGE)
                prefs[keyPhotoWallScaleMode] = settings.photoWallScaleMode.nameOrDefault()
                prefs[keyPhotoWallKenBurns] = settings.photoWallKenBurns
                prefs[keyPhotoWallAudioReactive] = settings.photoWallAudioReactive
                prefs[keyPhotoWallPulseZoom] = settings.photoWallPulseZoom
                prefs[keyPhotoWallBreathe] = settings.photoWallBreathe
            }
        }
        dataStore.edit { prefs ->
            prefs[keyNetworkFavorites] = gson.toJson(data.networkFavorites)
            prefs[keyLocalPlaylists] = gson.toJson(data.localPlaylists)
            if (data.playlistImportHistory.isNotEmpty()) {
                prefs[keyPlaylistImportHistory] = gson.toJson(data.playlistImportHistory)
            }
            if (data.songReachability.isNotEmpty()) {
                // 恢复时一并恢复可达性标记：24h 窗口内不重测（避免恢复后全量 HEAD 风暴）
                val clean = data.songReachability.filterValues { entry ->
                    System.currentTimeMillis() - entry.checkedAt < songReachabilityWindowMs
                }
                if (clean.isNotEmpty()) {
                    prefs[keySongReachability] = gson.toJson(clean)
                }
            }
            prefs[keyRecentSongs] = gson.toJson(data.recentSongIds)
            prefs[keyRecentSongObjects] = gson.toJson(
                RecentSongObjectsData(data.recentSongObjects.map { it.stripVolatileStreamUrl() })
            )
            prefs[keyPlayCounts] = gson.toJson(data.playCounts)
            if (data.lastQueue != null) {
                prefs[keyLastQueue] = gson.toJson(
                    data.lastQueue.copy(songs = data.lastQueue.songs.map { it.stripVolatileStreamUrl() })
                )
            }
            data.equalizerPreset?.let { prefs[keyEqualizerPreset] = it.ordinal }
            if (data.equalizerBands.isNotEmpty()) {
                prefs[keyEqualizerBands] = gson.toJson(data.equalizerBands)
            }
            if (data.playRecords.isNotEmpty()) {
                prefs[keyPlayRecords] = gson.toJson(PlayRecordsData(data.playRecords))
            }
            if (data.searchHistory.isNotEmpty()) {
                prefs[keySearchHistory] = gson.toJson(data.searchHistory)
            }
            // v2: 恢复之前遗漏的设置项
            prefs[keyWeatherEnabled] = data.weatherEnabled
            prefs[keyWeatherManualCity] = data.weatherManualCity
            prefs[keyWeatherAutoRefresh] = data.weatherAutoRefresh
            prefs[keyCoverFilterEnabled] = data.coverFilterEnabled
            prefs[keyCoverFilterBlurRadius] = data.coverFilterBlurRadius
            prefs[keyCoverFilterDarkOverlay] = data.coverFilterDarkOverlay
            if (data.musicSource.isNotBlank()) prefs[keyMusicSource] = data.musicSource
            prefs[keyLyricsFontScale] = data.lyricsFontScale
        }
    }
}

// =====================================================================
// 备份导入的**最后一道兜底**（v2.36.2）
// =====================================================================
//
// ⚠️ Gson 用反射写字段，**绕过 Kotlin 的非空检查**：反序列化时若某个枚举名在当前枚举里
// 不存在，默认适配器会返回 `null` 并被直接写进声明为非空的字段（详见 BackupGson.kt）。
// 随后 `settings.visualizerTheme.name` 抛 NPE → `dataStore.edit {}` 事务回滚
// → **整份备份导入失败**（真机故障：老备份里存着已删除的 `CLASSICAL_WAVE`）。
//
// 主修复是让备份走 `backupGson`（容错枚举）。这里再兜一层：**单个字段为 null 也只回落默认值**，
// 绝不因为一个字段让整份备份导入失败。扩展函数接收可空接收者，故对非空类型同样适用、无编译告警。

/** 可视化主题：null → [VisualizerTheme.Default]（`CIRCULAR_RING`） */
private fun VisualizerTheme?.nameOrDefault(): String =
    this?.name ?: VisualizerTheme.Default.name

/** 可视化画质：null → [VisualQuality.Default]（`MEDIUM`） */
private fun VisualQuality?.nameOrDefault(): String =
    this?.name ?: VisualQuality.Default.name

/** 播放模式：null → [PlayMode.SEQUENTIAL] */
private fun PlayMode?.ordinalOrDefault(): Int =
    this?.ordinal ?: PlayMode.SEQUENTIAL.ordinal

/** 网络音乐源：null → [NetworkSource.DEFAULT] */
private fun NetworkSource?.keyOrDefault(): String =
    this?.key ?: NetworkSource.DEFAULT.key

/** 照片墙转场：null → [PhotoTransitionId.Default]（`CROSSFADE`） */
private fun PhotoTransitionId?.nameOrDefault(): String =
    this?.name ?: PhotoTransitionId.Default.name

/** 画面适配：null → [PhotoScaleMode.Default]（`CROP`） */
private fun PhotoScaleMode?.nameOrDefault(): String =
    this?.name ?: PhotoScaleMode.Default.name

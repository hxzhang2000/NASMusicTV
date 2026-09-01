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
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.CryptoUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 应用偏好存储
 * 使用 DataStore 持久化服务器配置与通用设置
 */
class AppPreferences private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AppPreferences"

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

    // --- 上次播放队列（序列化为 JSON，streamUrl 置空不持久化）---
    private val keyLastQueue = stringPreferencesKey("last_queue")

    // --- 天气电台设置 ---
    private val keyWeatherEnabled = booleanPreferencesKey("weather_enabled")
    private val keyWeatherManualCity = stringPreferencesKey("weather_manual_city")
    private val keyWeatherAutoRefresh = booleanPreferencesKey("weather_auto_refresh")
    private val keyWeatherApiKey = stringPreferencesKey("weather_openweathermap_api_key")

    // --- 封面滤镜设置（Phase 5） ---
    private val keyCoverFilterEnabled = booleanPreferencesKey("cover_filter_enabled")
    private val keyCoverFilterBlurRadius = doublePreferencesKey("cover_filter_blur_radius")
    private val keyCoverFilterDarkOverlay = doublePreferencesKey("cover_filter_dark_overlay")

    // --- 频谱显示设置 ---
    private val keySpectrumEnabled = booleanPreferencesKey("settings_spectrum_enabled")
    private val keyVisualizerTheme = stringPreferencesKey("settings_visualizer_theme")

    // --- 全局字体字号调整 ---
    private val keyFontAdjustment = intPreferencesKey("settings_font_adjustment")

    // --- 网络音乐平台来源 ---
    private val keyMusicSource = stringPreferencesKey("music_source")

    // --- B-4 均衡器 ---
    private val keyEqualizerPreset = intPreferencesKey("equalizer_preset")
    private val keyEqualizerBands = stringPreferencesKey("equalizer_bands")

    // --- 语言设置 ---
    private val keyLanguage = stringPreferencesKey("settings_language")

    // --- 语言设置 Flow ---
    val language: Flow<String> = dataStore.data.map { it[keyLanguage] ?: "system" }

    suspend fun setLanguage(lang: String) {
        dataStore.edit { it[keyLanguage] = lang }
    }

    /**
     * 同步获取当前语言设置（用于 AppCompatDelegate.setApplicationLocales）
     */
    fun getLanguageSync(): String {
        return runBlocking(Dispatchers.IO) {
            dataStore.data.first()[keyLanguage] ?: "system"
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
     * 分离模式枚举
     */
    enum class SeparationMode(val value: String) {
        FAST("fast"),          // 快速模式：SpectralMaskProcessor 实时 DSP
        HIGH_QUALITY("hq")     // 高质量模式：HT-Demucs FT ONNX 预分离
    }

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
            gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    // --- B-2 播放次数 Flow ---
    val playCounts: Flow<Map<String, Int>> = dataStore.data.map { prefs ->
        val json = prefs[keyPlayCounts] ?: "{}"
        try {
            gson.fromJson(json, object : TypeToken<Map<String, Int>>() {}.type)
        } catch (e: Exception) { emptyMap() }
    }

    /**
     * 获取最近播放 ID 列表（一次性读取，调用方需在协程中）
     */
    suspend fun getRecentSongIds(): List<String> {
        return try {
            dataStore.data.first().let { prefs ->
                val recentJson = prefs[keyRecentSongs] ?: "[]"
                gson.fromJson(recentJson, object : TypeToken<List<String>>() {}.type) ?: emptyList()
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
            val recentList = try {
                gson.fromJson(recentJson, object : TypeToken<MutableList<String>>() {}.type)
                    ?: mutableListOf()
            } catch (e: Exception) { mutableListOf<String>() }

            val mutableRecent = recentList.toMutableList()
            mutableRecent.remove(songId) // 去重
            mutableRecent.add(0, songId)  // 最新放最前面
            if (mutableRecent.size > recentSongsMaxSize) {
                mutableRecent.removeAt(mutableRecent.lastIndex)
            }
            prefs[keyRecentSongs] = gson.toJson(mutableRecent)

            // 更新播放次数
            val countsJson = prefs[keyPlayCounts] ?: "{}"
            val counts = try {
                gson.fromJson(countsJson, object : TypeToken<MutableMap<String, Int>>() {}.type)
                    ?: mutableMapOf()
            } catch (e: Exception) { mutableMapOf<String, Int>() }

            counts[songId] = (counts[songId] ?: 0) + 1
            prefs[keyPlayCounts] = gson.toJson(counts)
        }
    }

    // --- B-2b 最近播放完整歌曲对象（含网络歌曲，供"最近播放"区展示/播放）---
    private data class RecentSongObjectsData(val songs: List<Song> = emptyList())

    /** 最近播放区最大保留歌曲数 */
    private val recentSongsObjectsMaxSize = 50

    /**
     * 记录一次最近播放的完整歌曲对象（含网络歌曲，streamUrl 置空）。
     * 与 recordPlay 的 id 列表互补：这里存完整元数据，支持网络歌曲/未连 NAS 时展示。
     */
    suspend fun recordRecentSongObject(song: Song) {
        dataStore.edit { prefs ->
            val json = prefs[keyRecentSongObjects] ?: "{\"songs\":[]}"
            val data = try {
                gson.fromJson(json, RecentSongObjectsData::class.java)
            } catch (e: Exception) {
                RecentSongObjectsData()
            }
            val list = data.songs.toMutableList()
            list.removeAll { it.id == song.id } // 去重（保留最新一条）
            // streamUrl 置空，避免持久化过期链接
            list.add(0, song.copy(streamUrl = null))
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
            val recentList = try {
                gson.fromJson(recentJson, object : TypeToken<MutableList<String>>() {}.type)
                    ?: mutableListOf()
            } catch (e: Exception) { mutableListOf<String>() }
            recentList.remove(songId)
            recentList.add(0, songId)
            if (recentList.size > recentSongsMaxSize) {
                recentList.removeAt(recentList.lastIndex)
            }
            prefs[keyRecentSongs] = gson.toJson(recentList)

            // 2. 更新播放次数
            val countsJson = prefs[keyPlayCounts] ?: "{}"
            val counts = try {
                gson.fromJson(countsJson, object : TypeToken<MutableMap<String, Int>>() {}.type)
                    ?: mutableMapOf()
            } catch (e: Exception) { mutableMapOf<String, Int>() }
            counts[songId] = (counts[songId] ?: 0) + 1
            prefs[keyPlayCounts] = gson.toJson(counts)

            // 3. 更新完整歌曲对象（含网络歌曲，streamUrl 置空）
            val objJson = prefs[keyRecentSongObjects] ?: "{\"songs\":[]}"
            val objData = try {
                gson.fromJson(objJson, RecentSongObjectsData::class.java)
            } catch (e: Exception) {
                RecentSongObjectsData()
            }
            val objList = objData.songs.toMutableList()
            objList.removeAll { it.id == songId }
            objList.add(0, song.copy(streamUrl = null))
            if (objList.size > recentSongsObjectsMaxSize) {
                objList.removeAt(objList.lastIndex)
            }
            prefs[keyRecentSongObjects] = gson.toJson(RecentSongObjectsData(objList))
        }
    }

    /**
     * 获取最近播放歌曲对象（最新在前，streamUrl 为空需播放时重新解析）
     */
    suspend fun getRecentSongObjects(): List<Song> {
        return try {
            dataStore.data.first().let { prefs ->
                val json = prefs[keyRecentSongObjects] ?: "{\"songs\":[]}"
                val data = gson.fromJson(json, RecentSongObjectsData::class.java)
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
            gson.fromJson(json, object : TypeToken<List<Float>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    suspend fun setEqualizerBands(bands: List<Float>) {
        dataStore.edit { it[keyEqualizerBands] = gson.toJson(bands) }
    }

    suspend fun setEqualizerBand(index: Int, value: Float) {
        dataStore.edit { prefs ->
            val json = prefs[keyEqualizerBands] ?: "[]"
            val bands: MutableList<Float> = try {
                val list: List<Float> = gson.fromJson(json, object : TypeToken<List<Float>>() {}.type)
                list.toMutableList()
            } catch (e: Exception) { mutableListOf() }
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
            spectrumEnabled = prefs[keySpectrumEnabled] ?: false,
            visualizerTheme = prefs[keyVisualizerTheme]?.let { VisualizerTheme.fromKey(it) } ?: VisualizerTheme.COLOR_FLOW,
            fontAdjustment = prefs[keyFontAdjustment] ?: 0,
            language = prefs[keyLanguage] ?: "system"
        )
    }

    // --- 全局字体字号调整 ---
    suspend fun setFontAdjustment(adjustment: Int) =
        dataStore.edit { it[keyFontAdjustment] = adjustment }

    suspend fun setDarkTheme(enabled: Boolean) = dataStore.edit { it[keyDarkTheme] = enabled }

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

    suspend fun setMetingApiBaseUrl(url: String) =
        dataStore.edit {
            it[keyMetingApiBaseUrl] = url.trim().trim('`', '\'', '"').trim()
        }

    suspend fun setMvApiBaseUrl(url: String) =
        dataStore.edit {
            it[keyMvApiBaseUrl] = url.trim().trim('`', '\'', '"').trim()
        }

    // --- 网络音乐平台来源 ---

    /**
     * 同步获取当前音乐平台来源（用于 MetingApiService 的 serverProvider）
     * 在每次请求时同步读取，支持运行时切换平台
     */
    fun getMusicSourceSync(): String {
        return runBlocking(Dispatchers.IO) {
            try {
                dataStore.data.first()[keyMusicSource] ?: com.nasmusic.tv.data.model.MusicSource.DEFAULT_API_KEY
            } catch (e: Exception) {
                com.nasmusic.tv.data.model.MusicSource.DEFAULT_API_KEY
            }
        }
    }

    suspend fun setMusicSource(sourceKey: String) {
        dataStore.edit { it[keyMusicSource] = sourceKey }
    }

    // --- Jamendo（CC 独立音乐）设置 ---
    suspend fun setJamendoClientId(id: String) {
        dataStore.edit { it[keyJamendoClientId] = id.trim() }
    }

    /**
     * 同步读取 Jamendo Client ID（用于 JamendoService 每次请求时读取）
     */
    fun getJamendoClientIdSync(): String {
        return runBlocking(Dispatchers.IO) {
            try {
                dataStore.data.first()[keyJamendoClientId] ?: ""
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to read jamendo client id", e)
                ""
            }
        }
    }

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
     */
    val weatherApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[keyWeatherApiKey] ?: ""
    }

    fun getWeatherApiKeySync(): String {
        return runBlocking(Dispatchers.IO) {
            try {
                dataStore.data.first()[keyWeatherApiKey] ?: ""
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to read weather API key", e)
                ""
            }
        }
    }

    suspend fun setWeatherApiKey(key: String) {
        dataStore.edit { it[keyWeatherApiKey] = key.trim() }
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

    /**
     * 同步获取当前默认网络源（用于 NetworkMusicManager 的 defaultSourceProvider）
     * 在 NetworkMusicManager.search() 调用时同步读取，避免协程上下文切换
     */
    fun getDefaultNetworkSourceSync(): String {
        return runBlocking(Dispatchers.IO) {
            try {
                val stored = dataStore.data.first()[keyDefaultNetworkSource]
                NetworkSource.fromKey(stored ?: "")?.key ?: stored ?: NetworkSource.DEFAULT.key
            } catch (e: Exception) {
                NetworkSource.DEFAULT.key
            }
        }
    }

    /**
     * 同步获取 Meting-API 端点 URL（用于 MetingApiService 的 baseUrlProvider）
     * 在每次请求时同步读取，支持运行时切换端点
     */
    fun getMetingApiBaseUrlSync(): String {
        return runBlocking(Dispatchers.IO) {
            try {
                dataStore.data.first()[keyMetingApiBaseUrl]
                    ?: MetingApiService.DEFAULT_BASE_URL
            } catch (e: Exception) {
                MetingApiService.DEFAULT_BASE_URL
            }
        }
    }

    /**
     * 同步获取 MTV 视频搜索端点 URL（用于 BilibiliMvService 的 baseUrlProvider）
     * 在每次请求时同步读取，支持运行时切换端点
     */
    fun getMvApiBaseUrlSync(): String {
        return runBlocking(Dispatchers.IO) {
            try {
                dataStore.data.first()[keyMvApiBaseUrl]
                    ?: BilibiliMvService.DEFAULT_BASE_URL
            } catch (e: Exception) {
                BilibiliMvService.DEFAULT_BASE_URL
            }
        }
    }

    // --- 网络歌词端点（Kugou / Netease）---
    fun getLyricsKugouBaseUrlSync(): String {
        return runBlocking(Dispatchers.IO) {
            try {
                dataStore.data.first()[keyLyricsKugouBaseUrl]
                    ?: com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_KUGOU_BASE_URL
            } catch (e: Exception) {
                com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_KUGOU_BASE_URL
            }
        }
    }

    fun getLyricsNeteaseBaseUrlSync(): String {
        return runBlocking(Dispatchers.IO) {
            try {
                dataStore.data.first()[keyLyricsNeteaseBaseUrl]
                    ?: com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_NETEASE_BASE_URL
            } catch (e: Exception) {
                com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_NETEASE_BASE_URL
            }
        }
    }

    suspend fun setLyricsKugouBaseUrl(url: String) =
        dataStore.edit {
            it[keyLyricsKugouBaseUrl] = url.trim().trim('`', '\'', '"').trim()
        }

    suspend fun setLyricsNeteaseBaseUrl(url: String) =
        dataStore.edit {
            it[keyLyricsNeteaseBaseUrl] = url.trim().trim('`', '\'', '"').trim()
        }

    // --- 网络歌曲收藏 ---

    /**
     * 网络收藏列表 Flow（响应式，收藏变化时自动更新）
     */
    val networkFavorites: Flow<List<NetworkFavoriteItem>> = dataStore.data.map { prefs ->
        val json = prefs[keyNetworkFavorites] ?: "[]"
        try {
            gson.fromJson(json, object : TypeToken<List<NetworkFavoriteItem>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    /**
     * 同步获取网络收藏列表（用于 isFavorite 判断，非 Flow）
     */
    suspend fun getNetworkFavorites(): List<NetworkFavoriteItem> {
        return try {
            dataStore.data.first().let { prefs ->
                val json = prefs[keyNetworkFavorites] ?: "[]"
                gson.fromJson(json, object : TypeToken<List<NetworkFavoriteItem>>() {}.type) ?: emptyList()
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
            val list = try {
                gson.fromJson(json, object : TypeToken<MutableList<NetworkFavoriteItem>>() {}.type)
                    ?: mutableListOf()
            } catch (e: Exception) { mutableListOf<NetworkFavoriteItem>() }

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
            gson.fromJson(json, object : TypeToken<List<LocalPlaylist>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    /**
     * 一次性读取本地歌单列表
     */
    suspend fun getLocalPlaylists(): List<LocalPlaylist> {
        return try {
            dataStore.data.first().let { prefs ->
                val json = prefs[keyLocalPlaylists] ?: "[]"
                gson.fromJson(json, object : TypeToken<List<LocalPlaylist>>() {}.type) ?: emptyList()
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
            val list = try {
                gson.fromJson(json, object : TypeToken<MutableList<LocalPlaylist>>() {}.type)
                    ?: mutableListOf()
            } catch (e: Exception) { mutableListOf<LocalPlaylist>() }

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
            val list = try {
                gson.fromJson(json, object : TypeToken<MutableList<LocalPlaylist>>() {}.type)
                    ?: mutableListOf()
            } catch (e: Exception) { mutableListOf<LocalPlaylist>() }

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
            val list = try {
                gson.fromJson(json, object : TypeToken<MutableList<LocalPlaylist>>() {}.type)
                    ?: mutableListOf()
            } catch (e: Exception) { mutableListOf<LocalPlaylist>() }

            val mutable = list.toMutableList()
            if (mutable.removeAll { it.id == id }) {
                prefs[keyLocalPlaylists] = gson.toJson(mutable)
            }
        }
    }

    /**
     * 添加歌曲到本地歌单（按 song.id 去重，streamUrl 置空不持久化）
     * @return true 添加成功；false 歌单不存在或歌曲已在歌单中
     */
    suspend fun addSongToPlaylist(playlistId: String, song: Song): Boolean {
        var added = false
        dataStore.edit { prefs ->
            val json = prefs[keyLocalPlaylists] ?: "[]"
            val list = try {
                gson.fromJson(json, object : TypeToken<MutableList<LocalPlaylist>>() {}.type)
                    ?: mutableListOf()
            } catch (e: Exception) { mutableListOf<LocalPlaylist>() }

            val mutable = list.toMutableList()
            val idx = mutable.indexOfFirst { it.id == playlistId }
            if (idx < 0) {
                added = false
            } else {
                val current = mutable[idx]
                if (current.songs.any { it.id == song.id }) {
                    added = false
                } else {
                    mutable[idx] = current.copy(songs = current.songs + song.copy(streamUrl = null))
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
            val list = try {
                gson.fromJson(json, object : TypeToken<MutableList<LocalPlaylist>>() {}.type)
                    ?: mutableListOf()
            } catch (e: Exception) { mutableListOf<LocalPlaylist>() }

            val mutable = list.toMutableList()
            val idx = mutable.indexOfFirst { it.id == playlistId }
            if (idx >= 0) {
                val current = mutable[idx]
                mutable[idx] = current.copy(songs = current.songs.filterNot { it.id == songId })
                prefs[keyLocalPlaylists] = gson.toJson(mutable)
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
     * streamUrl 置空后序列化，避免持久化过期的播放链接
     */
    suspend fun saveLastQueue(songs: List<Song>, currentIndex: Int) {
        dataStore.edit { prefs ->
            // streamUrl 置空，避免持久化过期链接
            val songsToSave = songs.map { it.copy(streamUrl = null) }
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
                gson.fromJson(json, LastQueueData::class.java)
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
            val data = try {
                gson.fromJson(json, PlayRecordsData::class.java)
            } catch (e: Exception) {
                PlayRecordsData()
            }
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
                val data = gson.fromJson(json, PlayRecordsData::class.java)
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
            gson.fromJson(json, object : TypeToken<List<SearchHistoryItem>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    /**
     * 一次性读取搜索历史
     */
    suspend fun getSearchHistory(): List<SearchHistoryItem> {
        return try {
            dataStore.data.first().let { prefs ->
                val json = prefs[keySearchHistory] ?: "[]"
                gson.fromJson(json, object : TypeToken<List<SearchHistoryItem>>() {}.type) ?: emptyList()
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
            val list = try {
                gson.fromJson(json, object : TypeToken<MutableList<SearchHistoryItem>>() {}.type)
                    ?: mutableListOf()
            } catch (e: Exception) { mutableListOf<SearchHistoryItem>() }

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
            val list = try {
                gson.fromJson(json, object : TypeToken<MutableList<SearchHistoryItem>>() {}.type)
                    ?: mutableListOf()
            } catch (e: Exception) { mutableListOf<SearchHistoryItem>() }

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

    /** 同步读取某网盘配置（runBlocking，仅供 service/oauth 在初始化期使用） */
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

    /** 百度专用：便捷同步读取（兜底默认配置，永不返回 null） */
    fun getBaiduConfigSync(): CloudDriveConfig =
        getCloudDriveConfigSync(CloudDriveType.BAIDU) ?: CloudDriveConfig(CloudDriveType.BAIDU)

    /** 异步保存某网盘配置（增量合并到现有 Map） */
    suspend fun saveCloudDriveConfig(config: CloudDriveConfig) {
        dataStore.edit { prefs ->
            val json = prefs[keyCloudDriveConfig] ?: "{}"
            val map = loadCloudDriveConfigs(json).toMutableMap()
            map[config.type.key] = config
            prefs[keyCloudDriveConfig] = saveCloudDriveConfigs(map)
        }
    }

    /** 同步保存（OAuth 客户端在非协程上下文调用） */
    fun saveCloudDriveConfigSync(config: CloudDriveConfig) {
        runBlocking(Dispatchers.IO) { saveCloudDriveConfig(config) }
    }

    // ---- 百度 token 便捷读写（加解密 accessToken/refreshToken）----

    /** 同步读取百度 token（解密） */
    fun getBaiduTokensSync(): BaiduTokens? {
        val cfg = getBaiduConfigSync() ?: return null
        val t = cfg.tokens ?: return null
        return try {
            t.copy(
                accessToken = CryptoUtils.decrypt(t.accessToken).ifBlank { return null },
                refreshToken = CryptoUtils.decrypt(t.refreshToken).ifBlank { return null }
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "getBaiduTokensSync decrypt error", e)
            null
        }
    }

    /** 同步保存百度 token（加密） */
    fun saveBaiduTokensSync(tokens: BaiduTokens) {
        val cfg = getBaiduConfigSync().copy(
            tokens = tokens.copy(
                accessToken = CryptoUtils.encrypt(tokens.accessToken),
                refreshToken = CryptoUtils.encrypt(tokens.refreshToken)
            )
        )
        saveCloudDriveConfigSync(cfg)
    }

    /** 清除百度 token（登出/刷新失败降级用） */
    fun clearBaiduTokensSync() {
        val cfg = getBaiduConfigSync().copy(tokens = null)
        saveCloudDriveConfigSync(cfg)
    }

    // ---- 百度配置项便捷存取 ----

    fun getBaiduEnabledSync(): Boolean = getBaiduConfigSync().enabled
    fun setBaiduEnabledSync(enabled: Boolean) =
        saveCloudDriveConfigSync(getBaiduConfigSync().copy(enabled = enabled))
    fun getBaiduMusicRootDirSync(): String = getBaiduConfigSync().musicRootDir.ifBlank { "/音乐" }
    fun setBaiduMusicRootDirSync(dir: String) =
        saveCloudDriveConfigSync(getBaiduConfigSync().copy(musicRootDir = dir))
    fun getBaiduMvDirSync(): String? = getBaiduConfigSync().mvDir
    fun setBaiduMvDirSync(dir: String?) =
        saveCloudDriveConfigSync(getBaiduConfigSync().copy(mvDir = dir))
    fun getBaiduCustomAppKeySync(): String? = getBaiduConfigSync().customAppKey
    fun setBaiduCustomAppKeySync(key: String?) =
        saveCloudDriveConfigSync(getBaiduConfigSync().copy(customAppKey = key))
    fun getBaiduCustomSecretKeySync(): String? = getBaiduConfigSync().customSecretKey
    fun setBaiduCustomSecretKeySync(secret: String?) =
        saveCloudDriveConfigSync(getBaiduConfigSync().copy(customSecretKey = secret))
    fun getBaiduApiDriftNotifiedSync(): Boolean = getBaiduConfigSync().apiDriftNotified
    fun setBaiduApiDriftNotifiedSync(v: Boolean) =
        saveCloudDriveConfigSync(getBaiduConfigSync().copy(apiDriftNotified = v))

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
                prefs[keyPlayMode] = settings.defaultPlayMode.ordinal
                prefs[keyCacheLyrics] = settings.cacheLyrics
                prefs[keyCacheCover] = settings.cacheCover
                prefs[keyLyricsOffset] = settings.lyricsOffsetMs
                prefs[keyDefaultNetworkSource] = settings.defaultNetworkSource.key
                prefs[keyMetingApiBaseUrl] = settings.metingApiBaseUrl
                prefs[keyMvApiBaseUrl] = settings.mvApiBaseUrl
                prefs[keySpectrumEnabled] = settings.spectrumEnabled
                prefs[keyVisualizerTheme] = settings.visualizerTheme.name
            }
        }
        dataStore.edit { prefs ->
            prefs[keyNetworkFavorites] = gson.toJson(data.networkFavorites)
            prefs[keyLocalPlaylists] = gson.toJson(data.localPlaylists)
            prefs[keyRecentSongs] = gson.toJson(data.recentSongIds)
            prefs[keyRecentSongObjects] = gson.toJson(
                RecentSongObjectsData(data.recentSongObjects.map { it.copy(streamUrl = null) })
            )
            prefs[keyPlayCounts] = gson.toJson(data.playCounts)
            if (data.lastQueue != null) {
                prefs[keyLastQueue] = gson.toJson(
                    data.lastQueue.copy(songs = data.lastQueue.songs.map { it.copy(streamUrl = null) })
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

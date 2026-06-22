package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.EqualizerPreset
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.util.CryptoUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * 应用偏好存储
 * 使用 DataStore 持久化服务器配置与通用设置
 */
class AppPreferences(private val context: Context) {

    private val Context.dataStore by preferencesDataStore(name = "nas_music_tv")

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

    // --- B-2 最近播放 & 播放次数（序列化为 JSON）---
    private val keyRecentSongs = stringPreferencesKey("recent_songs")
    private val keyPlayCounts = stringPreferencesKey("play_counts")

    // --- B-4 均衡器 ---
    private val keyEqualizerPreset = intPreferencesKey("equalizer_preset")
    private val keyEqualizerBands = stringPreferencesKey("equalizer_bands")

    private val gson = Gson()
    private val recentSongsMaxSize = 50

    // --- ServerConfig Flow ---
    val serverConfig: Flow<ServerConfig> = context.dataStore.data.map { prefs ->
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
        context.dataStore.edit { prefs ->
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
        context.dataStore.edit { prefs ->
            prefs[keyServerConnected] = connected
            if (displayName.isNotBlank()) {
                prefs[keyServerDisplayName] = displayName
            }
        }
    }

    suspend fun clearServerConfig() {
        context.dataStore.edit { prefs ->
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
    val recentSongIds: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val json = prefs[keyRecentSongs] ?: "[]"
        try {
            gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    // --- B-2 播放次数 Flow ---
    val playCounts: Flow<Map<String, Int>> = context.dataStore.data.map { prefs ->
        val json = prefs[keyPlayCounts] ?: "{}"
        try {
            gson.fromJson(json, object : TypeToken<Map<String, Int>>() {}.type)
        } catch (e: Exception) { emptyMap() }
    }

    /**
     * 同步获取最近播放 ID 列表（非 Flow，用于 ViewModel 中一次性读取）
     */
    fun getRecentSongIdsSync(): List<String> {
        return runBlocking {
            try {
                context.dataStore.data.first().let { prefs ->
                    val recentJson = prefs[keyRecentSongs] ?: "[]"
                    gson.fromJson(recentJson, object : TypeToken<List<String>>() {}.type) ?: emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * 记录一次播放（B-2）
     * 1. 添加到最近播放列表（去重 + LRU，最多 50 条）
     * 2. 播放次数 +1
     */
    suspend fun recordPlay(songId: String) {
        context.dataStore.edit { prefs ->
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

    // --- B-4 均衡器 ---
    val equalizerPreset: Flow<EqualizerPreset> = context.dataStore.data.map { prefs ->
        val ordinal = prefs[keyEqualizerPreset] ?: 0
        EqualizerPreset.entries.getOrElse(ordinal) { EqualizerPreset.NORMAL }
    }

    suspend fun setEqualizerPreset(preset: EqualizerPreset) {
        context.dataStore.edit { it[keyEqualizerPreset] = preset.ordinal }
    }

    val equalizerBands: Flow<List<Float>> = context.dataStore.data.map { prefs ->
        val json = prefs[keyEqualizerBands] ?: "[]"
        try {
            gson.fromJson(json, object : TypeToken<List<Float>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    suspend fun setEqualizerBands(bands: List<Float>) {
        context.dataStore.edit { it[keyEqualizerBands] = gson.toJson(bands) }
    }

    suspend fun setEqualizerBand(index: Int, value: Float) {
        context.dataStore.edit { prefs ->
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
    val appSettings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            darkTheme = prefs[keyDarkTheme] ?: true,
            animationsEnabled = prefs[keyAnimations] ?: true,
            autoPlayNext = prefs[keyAutoPlayNext] ?: true,
            defaultPlayMode = PlayMode.fromOrdinal(prefs[keyPlayMode] ?: 0),
            cacheLyrics = prefs[keyCacheLyrics] ?: true,
            cacheCover = prefs[keyCacheCover] ?: true,
            lyricsOffsetMs = prefs[keyLyricsOffset] ?: 0L
        )
    }

    suspend fun setDarkTheme(enabled: Boolean) = context.dataStore.edit { it[keyDarkTheme] = enabled }

    suspend fun setAnimationsEnabled(enabled: Boolean) = context.dataStore.edit { it[keyAnimations] = enabled }

    suspend fun setAutoPlayNext(enabled: Boolean) = context.dataStore.edit { it[keyAutoPlayNext] = enabled }

    suspend fun setDefaultPlayMode(mode: PlayMode) = context.dataStore.edit { it[keyPlayMode] = mode.ordinal }

    suspend fun setCacheLyrics(enabled: Boolean) = context.dataStore.edit { it[keyCacheLyrics] = enabled }

    suspend fun setCacheCover(enabled: Boolean) = context.dataStore.edit { it[keyCacheCover] = enabled }

    suspend fun setLyricsOffset(offsetMs: Long) = context.dataStore.edit { it[keyLyricsOffset] = offsetMs }
}

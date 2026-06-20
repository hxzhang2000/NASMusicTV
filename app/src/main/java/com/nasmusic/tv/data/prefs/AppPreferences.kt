package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    // --- ServerConfig Flow ---
    val serverConfig: Flow<ServerConfig> = context.dataStore.data.map { prefs ->
        ServerConfig(
            backendType = prefs[keyBackendType] ?: ServerConfig.TYPE_JELLYFIN,
            baseUrl = prefs[keyBaseUrl] ?: "",
            apiToken = prefs[keyApiToken] ?: "",
            username = prefs[keyUsername] ?: "",
            password = prefs[keyPassword] ?: "",
            isConnected = prefs[keyServerConnected] ?: false,
            displayName = prefs[keyServerDisplayName] ?: ""
        )
    }

    suspend fun saveServerConfig(config: ServerConfig) {
        context.dataStore.edit { prefs ->
            prefs[keyBackendType] = config.backendType
            prefs[keyBaseUrl] = config.baseUrl
            prefs[keyApiToken] = config.apiToken
            prefs[keyUsername] = config.username
            prefs[keyPassword] = config.password
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

    companion object {
        @Volatile
        private var instance: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context.applicationContext).also { instance = it }
            }
        }
    }
}

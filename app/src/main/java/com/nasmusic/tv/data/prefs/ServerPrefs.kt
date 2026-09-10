package com.nasmusic.tv.data.prefs

import com.nasmusic.tv.data.model.ServerConfig
import kotlinx.coroutines.flow.Flow

/**
 * R-4 服务器配置域子 Prefs（门面模式：键不迁移，DataStore 单例由 AppPreferences 持有）。
 * 访问器从 AppPreferences 原样搬迁；旧 API 在 AppPreferences 保留 @Deprecated 过渡。
 */
class ServerPrefs internal constructor(private val prefs: AppPreferences) {

    /** 服务器配置 Flow */
    val serverConfig: Flow<ServerConfig> = prefs.serverConfig

    suspend fun saveServerConfig(config: ServerConfig) = prefs.saveServerConfig(config)
}

package com.nasmusic.tv.data.model

import java.util.UUID

/**
 * 服务器配置
 */
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val backendType: String,
    val baseUrl: String,
    val apiToken: String = "",
    val username: String = "",
    val password: String = "",
    val isConnected: Boolean = false,
    val displayName: String = ""
) {
    companion object {
        const val TYPE_JELLYFIN = "jellyfin"
        const val TYPE_NAVIDROME = "navidrome"

        val Empty = ServerConfig(
            backendType = TYPE_JELLYFIN,
            baseUrl = "",
            apiToken = "",
            username = "",
            password = ""
        )
    }

    val isValid: Boolean
        get() = baseUrl.isNotBlank() && backendType.isNotBlank()
}

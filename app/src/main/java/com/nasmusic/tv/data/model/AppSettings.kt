package com.nasmusic.tv.data.model

/**
 * 应用通用设置
 */
data class AppSettings(
    val darkTheme: Boolean = true,
    val animationsEnabled: Boolean = true,
    val autoPlayNext: Boolean = true,
    val defaultPlayMode: PlayMode = PlayMode.SEQUENTIAL,
    val cacheLyrics: Boolean = true,
    val cacheCover: Boolean = true,
    val lyricsOffsetMs: Long = 0L,
    // 网络音乐默认源（"meting" / "alapi" / "jiosaavn"）
    val defaultNetworkSource: String = "meting",
    // Meting-API 端点 URL（可自建，默认使用公共服务）
    val metingApiBaseUrl: String = "https://meting.mikus.ink/api"
)

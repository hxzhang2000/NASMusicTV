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
    val lyricsOffsetMs: Long = 0L
)

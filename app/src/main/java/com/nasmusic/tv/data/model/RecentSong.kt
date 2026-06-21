package com.nasmusic.tv.data.model

/**
 * 最近播放记录
 */
data class RecentSong(
    val songId: String,
    val lastPlayedAt: Long = System.currentTimeMillis(),
    val playCount: Int = 1
)

package com.nasmusic.tv.data.model

/**
 * 最近播放记录
 */
data class RecentSong(
    val songId: String,
    val lastPlayedAt: Long,
    val playCount: Int
) {
    companion object {
        fun create(songId: String): RecentSong =
            RecentSong(songId, System.currentTimeMillis(), 1)
    }
}

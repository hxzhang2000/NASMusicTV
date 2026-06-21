package com.nasmusic.tv.data.model

/**
 * 播放列表
 */
data class Playlist(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val durationMs: Long = 0L,
    val coverUrl: String? = null,
    val owner: String = ""
)

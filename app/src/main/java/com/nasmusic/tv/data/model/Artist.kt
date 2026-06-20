package com.nasmusic.tv.data.model

/**
 * 歌手
 */
data class Artist(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val albumCount: Int = 0,
    val songCount: Int = 0
)

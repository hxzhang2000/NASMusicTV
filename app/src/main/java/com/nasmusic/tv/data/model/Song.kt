package com.nasmusic.tv.data.model

/**
 * 歌曲
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String = "",
    val artistId: String? = null,
    val album: String = "",
    val albumId: String? = null,
    val coverUrl: String? = null,
    val streamUrl: String? = null,
    val durationMs: Long = 0L,
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val year: Int? = null,
    val genre: String? = null,
    val bitrate: Int = 0
)

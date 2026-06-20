package com.nasmusic.tv.data.model

/**
 * 专辑
 */
data class Album(
    val id: String,
    val name: String,
    val artist: String = "",
    val artistId: String? = null,
    val coverUrl: String? = null,
    val year: Int? = null,
    val songCount: Int = 0,
    val durationMs: Long = 0L
)

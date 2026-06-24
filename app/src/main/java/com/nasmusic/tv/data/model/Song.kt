package com.nasmusic.tv.data.model

/**
 * 歌曲
 *
 * 统一承载 NAS 本地歌曲与网络搜索歌曲：
 * - NAS 歌曲：isNetworkSong=false，id 为后端原始 ID
 * - 网络歌曲：isNetworkSong=true，id 格式为 "ntwk_${source}_${sourceId}"，
 *   networkSource 标识来源（"meting"/"alapi"/"jiosaavn"），networkId 为源平台原始 ID。
 *   streamUrl 在播放时由 NetworkMusicManager.resolvePlayUrl() 实时解析赋值，不持久化。
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
    val bitrate: Int = 0,
    // 网络歌曲扩展字段（默认值保持向后兼容）
    val isNetworkSong: Boolean = false,
    val networkSource: String? = null,
    val networkId: String? = null
)

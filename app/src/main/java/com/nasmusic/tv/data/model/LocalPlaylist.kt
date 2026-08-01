package com.nasmusic.tv.data.model

/**
 * 本地歌单（存于 DataStore，独立于 NAS 后端歌单）
 *
 * - 可容纳 NAS 歌曲与网络歌曲混合
 * - id 为本地生成的 UUID
 * - songs 存储完整 Song 对象（Gson 序列化；网络歌曲的 streamUrl 持久化前置空）
 */
data class LocalPlaylist(
    val id: String,
    val name: String,
    val songs: List<Song> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

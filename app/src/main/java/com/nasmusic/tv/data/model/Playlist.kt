package com.nasmusic.tv.data.model

/**
 * 播放列表
 *
 * 统一承载 NAS 后端与网络音乐的播放列表数据：
 * - id：后端原始 ID
 * - name：播放列表名称
 * - coverUrls：封面 URL 列表（多张封面用于轮播）
 * - songCount：包含的歌曲数量
 * - owner：所有者（NAS 后端）
 * - durationMs：总时长（毫秒，NAS 后端）
 */
data class Playlist(
    val id: String,
    val name: String,
    val coverUrls: List<String> = emptyList(),
    val songCount: Int = 0,
    val owner: String = "",
    val durationMs: Long = 0L
)

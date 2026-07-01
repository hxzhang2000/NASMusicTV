package com.nasmusic.tv.data.model

/**
 * 网络歌曲收藏项
 *
 * 用于持久化网络搜索歌曲的收藏信息。
 * - songId 直接复用 Song.id（格式为 "ntwk_${source}_${sourceId}"），便于与 Song 关联查询
 * - 不存储 streamUrl（播放链接有时效性），每次播放时由 NetworkMusicManager.resolvePlayUrl() 重新解析
 *
 * @param songId 歌曲 ID（与 Song.id 一致）
 * @param title 歌曲名
 * @param artist 艺术家
 * @param album 专辑名
 * @param coverUrl 封面 URL（端点 URL 或直联 URL）
 * @param networkSource 网络来源（"meting"/"alapi"/"jiosaavn"）
 * @param networkId 源平台原始 ID
 * @param addedAtMs 收藏时间戳（毫秒）
 */
data class NetworkFavoriteItem(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val networkSource: String,
    val networkId: String,
    val addedAtMs: Long
)

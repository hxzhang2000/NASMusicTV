package com.nasmusic.tv.data.model

/**
 * 首页仪表盘数据
 *
 * 聚合 NAS 后端统计信息和已加载的数据，供 HomeScreen 展示。
 */
data class HomeDashboardData(
    val totalAlbums: Int = 0,
    val totalSongs: Int = 0,
    val totalArtists: Int = 0,
    val totalPlaylists: Int = 0,
    val recentlyAddedAlbums: List<Album> = emptyList(),
    val favoriteSongs: List<Song> = emptyList(),
    val recentSongs: List<Song> = emptyList()
)

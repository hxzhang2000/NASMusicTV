package com.nasmusic.tv.data.model

/**
 * 歌曲分页加载状态
 */
data class SongsPagingState(
    val songs: List<Song> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 0
)

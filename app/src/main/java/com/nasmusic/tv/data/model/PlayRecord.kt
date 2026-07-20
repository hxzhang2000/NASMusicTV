package com.nasmusic.tv.data.model

/**
 * 单次播放记录
 *
 * 每次歌曲播放完成或切换时写入。
 * 持久化到 DataStore 用于统计展示。
 */
data class PlayRecord(
    val songId: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val coverUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis(),  // 播放开始时间
    val durationPlayedMs: Long = 0L,                   // 播放时长
    val durationTotalMs: Long = 0L                     // 歌曲总时长
)

/**
 * 播放统计数据摘要
 */
data class PlayStatistics(
    val totalPlayCount: Int = 0,          // 总播放次数
    val totalPlayTimeMs: Long = 0L,       // 总播放时长 (ms)
    val uniqueSongsPlayed: Int = 0,       // 去重歌曲数
    val topSongs: List<PlayRecord> = emptyList(),        // Top N 歌曲
    val topArtists: List<Pair<String, Int>> = emptyList(), // 歌手 → 播放次数
    val recentPlays: List<PlayRecord> = emptyList()      // 最近播放
)

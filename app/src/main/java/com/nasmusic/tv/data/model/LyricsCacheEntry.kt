package com.nasmusic.tv.data.model

/**
 * 持久化歌词缓存条目
 *
 * 只在用户主动切换到网络歌词来源时写入（对应 [MainViewModel.switchLyricsSource]）。
 * 下次播放同一首歌时自动从缓存读取网络歌词，无需重新请求网络。
 *
 * 存储于 `filesDir/lyrics_cache.json`，LRU 2000 条淘汰。
 */
data class LyricsCacheEntry(
    val songId: String,
    val songTitle: String,
    val songArtist: String,
    val lrcText: String,
    val lastPlayedAt: Long = 0L
)
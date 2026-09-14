package com.nasmusic.tv.player

import com.nasmusic.tv.data.model.Song

/**
 * T3 播放器三元组原子状态（2026-09-14）。
 *
 * [queue] / [currentIndex] / [currentSong] 在切歌、换队列时**必须同帧变化**——
 * 此前三个独立 MutableStateFlow 连续赋值非原子，UI 集中订阅点会读到
 * "新队列 + 旧索引 + 旧歌名" 等错帧状态（快速切歌时 UI 闪烁）。
 * 现由 [PlayerManager] 以 `_playerState.update { it.copy(...) }` 一次性发布。
 */
data class PlayerState(
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = 0,
    val currentSong: Song? = null
)

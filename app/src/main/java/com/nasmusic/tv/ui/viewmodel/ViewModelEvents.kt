package com.nasmusic.tv.ui.viewmodel

import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song

/**
 * 跨域事件契约（W0 冻结版，见 docs/archive/codebase-refactoring-plan-2026-09.md）。
 * 新增事件必须回写本文件与文档对应章节。
 *
 * MainViewModel 作为唯一事件路由器（init 中 collect 各子 VM 事件并转发给目标子 VM），
 * 子 VM 之间不直接互调。
 */

sealed interface SearchEvent {
    data class AddToQueue(val song: Song) : SearchEvent          // 搜索结果加入播放队列 → PlayerViewModel
    data class AddAllToQueue(val songs: List<Song>) : SearchEvent
}

sealed interface LibraryEvent {
    data class OpenAlbumDetail(val album: Album) : LibraryEvent  // 详情导航 → NavigationViewModel
    data class OpenArtistDetail(val artist: Artist) : LibraryEvent
    data class PlayRequested(val songs: List<Song>, val startIndex: Int) : LibraryEvent  // 浏览页播放入口 → PlayerViewModel
}

sealed interface WeatherRadioEvent {
    data class PlayRequested(val songs: List<Song>) : WeatherRadioEvent  // 电台播放 → PlayerViewModel
}

sealed interface PlayerEvent {
    data class SongStarted(val song: Song) : PlayerEvent        // 播放记录上报 → PlayHistoryViewModel
    data class ModeChanged(val playMode: PlayMode) : PlayerEvent // 播放模式持久化 → AppPreferences（经 PlayerViewModel 内部）
}

sealed interface NetworkMusicEvent {
    data class PlayRequested(val song: Song) : NetworkMusicEvent // 网络歌曲播放（需先解析流地址）→ PlayerViewModel
    data class PlayBatchRequested(val songs: List<Song>, val startIndex: Int) : NetworkMusicEvent
}

/**
 * 常驻跨域状态暴露（迁移期 MainViewModel 转发用）。
 * 子 VM 通过共享仓库对象读写，不直接持有彼此引用。
 */
interface PlayerCommandHub {
    /** 加入播放队列（追加，不替换） */
    fun addToQueue(songs: List<Song>)
}

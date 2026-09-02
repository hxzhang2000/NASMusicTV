package com.nasmusic.tv.data.model

import com.nasmusic.tv.util.PinyinUtils

/**
 * 歌曲拼音缓存包装器（内部使用）
 *
 * 避免搜索过滤阶段重复计算拼音，仅在 FilterMode.PRECISE 时使用。
 * 每个字段延迟计算一次后缓存，后续访问直接返回缓存值。
 */
class SongWithPinyin(song: Song) {
    val song: Song = song

    val pinyin: String by lazy { PinyinUtils.toPinyin(song.title) }
    val initials: String by lazy { PinyinUtils.toPinyinInitials(song.title) }
    val artistPinyin: String by lazy { PinyinUtils.toPinyin(song.artist) }
    val artistInitials: String by lazy { PinyinUtils.toPinyinInitials(song.artist) }

    companion object {
        /**
         * 为歌曲列表生成拼音缓存（Map<songId, SongWithPinyin>）
         * 用于精确过滤时避免重复计算
         */
        fun fromSongs(songs: List<Song>): Map<String, SongWithPinyin> {
            return songs.associateBy({ it.id }) { SongWithPinyin(it) }
        }
    }
}

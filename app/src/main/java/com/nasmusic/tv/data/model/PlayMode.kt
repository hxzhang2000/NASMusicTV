package com.nasmusic.tv.data.model

/**
 * 播放模式
 */
enum class PlayMode(val displayName: String) {
    SEQUENTIAL("顺序播放"),
    REPEAT_ONE("单曲循环"),
    REPEAT_ALL("列表循环"),
    SHUFFLE("随机播放");

    companion object {
        @JvmStatic
        fun fromOrdinal(ordinal: Int): PlayMode = values().getOrNull(ordinal) ?: SEQUENTIAL
    }
}

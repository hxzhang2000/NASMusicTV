package com.nasmusic.tv.data.model

import androidx.annotation.StringRes
import com.nasmusic.tv.R

/**
 * 播放模式
 */
enum class PlayMode(
    /** 显示名资源 ID（本地化展示用） */
    @StringRes val displayNameRes: Int,
    /** 中文显示名（数据用途，UI 展示走 [displayNameRes]） */
    val displayName: String
) {
    SEQUENTIAL(R.string.play_mode_sequential, "顺序播放"),
    REPEAT_ONE(R.string.play_mode_repeat_one, "单曲循环"),
    REPEAT_ALL(R.string.play_mode_repeat_all, "列表循环"),
    SHUFFLE(R.string.play_mode_shuffle, "随机播放");

    companion object {
        @JvmStatic
        fun fromOrdinal(ordinal: Int): PlayMode = values().getOrNull(ordinal) ?: SEQUENTIAL
    }
}

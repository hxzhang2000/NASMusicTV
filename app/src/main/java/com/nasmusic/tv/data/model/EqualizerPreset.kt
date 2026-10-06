package com.nasmusic.tv.data.model

import androidx.annotation.StringRes
import com.nasmusic.tv.R

/**
 * 均衡器预置方案
 */
enum class EqualizerPreset(
    /** 显示名资源 ID（本地化展示用） */
    @StringRes val displayNameRes: Int,
    val displayName: String,
    val bandGains: List<Float>
) {
    NORMAL(R.string.equalizer_preset_normal, "自然", List(10) { 0f }),
    POP(R.string.equalizer_preset_pop, "流行", listOf(-1f, 2f, 4f, 2f, -1f, 0f, 0f, 0f, 0f, 0f)),
    ROCK(R.string.equalizer_preset_rock, "摇滚", listOf(4f, 2f, -1f, 2f, 4f, 0f, 0f, 0f, 0f, 0f)),
    CLASSICAL(R.string.equalizer_preset_classical, "古典", listOf(4f, 2f, 0f, 2f, 3f, 0f, 0f, 0f, 0f, 0f)),
    JAZZ(R.string.equalizer_preset_jazz, "爵士", listOf(3f, 2f, -1f, 2f, 3f, 0f, 0f, 0f, 0f, 0f)),
    CUSTOM(R.string.equalizer_preset_custom, "自定义", List(10) { 0f });

    companion object {
        fun fromName(name: String): EqualizerPreset? =
            values().find { it.name == name }
    }
}

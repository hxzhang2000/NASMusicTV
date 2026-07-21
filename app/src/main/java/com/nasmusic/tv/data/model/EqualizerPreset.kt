package com.nasmusic.tv.data.model

/**
 * 均衡器预置方案
 */
enum class EqualizerPreset(val displayName: String, val bandGains: List<Float>) {
    NORMAL("自然", List(10) { 0f }),
    POP("流行", listOf(-1f, 2f, 4f, 2f, -1f, 0f, 0f, 0f, 0f, 0f)),
    ROCK("摇滚", listOf(4f, 2f, -1f, 2f, 4f, 0f, 0f, 0f, 0f, 0f)),
    CLASSICAL("古典", listOf(4f, 2f, 0f, 2f, 3f, 0f, 0f, 0f, 0f, 0f)),
    JAZZ("爵士", listOf(3f, 2f, -1f, 2f, 3f, 0f, 0f, 0f, 0f, 0f)),
    CUSTOM("自定义", List(10) { 0f });

    companion object {
        fun fromName(name: String): EqualizerPreset? =
            values().find { it.name == name }
    }
}

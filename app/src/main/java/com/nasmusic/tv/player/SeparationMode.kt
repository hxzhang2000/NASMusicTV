package com.nasmusic.tv.player

/**
 * 人声分离模式（R-5 从 AppPreferences 嵌套枚举上提为 player 层顶层类型，
 * 消除 player→data.prefs 的反向依赖；AppPreferences 保留 typealias 兼容既有引用）。
 */
enum class SeparationMode(val value: String) {
    /** 快速模式：SpectralMaskProcessor 实时 DSP */
    FAST("fast"),

    /** 高质量模式：HT-Demucs FT ONNX 预分离 */
    HIGH_QUALITY("hq");

    companion object {
        fun fromValue(value: String?): SeparationMode =
            entries.find { it.value == value } ?: FAST
    }
}

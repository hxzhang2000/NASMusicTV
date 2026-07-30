package com.nasmusic.tv.data.model

/**
 * 应用通用设置
 */
data class AppSettings(
    val darkTheme: Boolean = true,
    val animationsEnabled: Boolean = true,
    val autoPlayNext: Boolean = true,
    val defaultPlayMode: PlayMode = PlayMode.SEQUENTIAL,
    val cacheLyrics: Boolean = true,
    val cacheCover: Boolean = true,
    val lyricsOffsetMs: Long = 0L,
    // 网络音乐默认源（NetworkSource 枚举，编译期类型安全）
    val defaultNetworkSource: NetworkSource = NetworkSource.DEFAULT,
    // Meting-API 端点 URL（由 AppPreferences.getMetingApiBaseUrlSync() 提供默认值）
    val metingApiBaseUrl: String = "",
    // 频谱显示开关（默认关闭）
    val spectrumEnabled: Boolean = false,
    // 可视化频谱主题
    val visualizerTheme: VisualizerTheme = VisualizerTheme.COLOR_FLOW
)

enum class VisualizerTheme(val displayName: String) {
    COLOR_FLOW("ColorFlow"),
    NEON_PULSE("NeonPulse"),
    CLASSICAL_WAVE("ClassicalWave");

    companion object {
        fun fromKey(key: String): VisualizerTheme? =
            entries.find { it.name == key || it.displayName.equals(key, ignoreCase = true) }
    }
}

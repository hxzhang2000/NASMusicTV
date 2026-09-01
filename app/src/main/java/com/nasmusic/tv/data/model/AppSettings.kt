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
    // MTV 视频搜索端点 URL（由 AppPreferences.getMvApiBaseUrlSync() 提供默认值）
    val mvApiBaseUrl: String = "",
    // 网络歌词酷狗端点 URL（由 AppPreferences.getLyricsKugouBaseUrlSync() 提供默认值）
    val lyricsKugouBaseUrl: String = "",
    // 网络歌词网易云端点 URL（由 AppPreferences.getLyricsNeteaseBaseUrlSync() 提供默认值）
    val lyricsNeteaseBaseUrl: String = "",
    // 频谱显示开关（默认关闭）
    val spectrumEnabled: Boolean = false,
    // 可视化频谱主题
    val visualizerTheme: VisualizerTheme = VisualizerTheme.COLOR_FLOW,
    // 全局字体字号调整（sp，在当前Theme档位基础上增减，默认0）
    val fontAdjustment: Int = 0,
    // 高质量分离模型自定义下载 URL（空=用默认镜像；国内网络AWS CDN被墙时，可指向自建镜像/NAS）
    val modelDownloadUrl: String = "",
    // 语言设置："system"=跟随系统, "zh"=中文, "en"=English
    val language: String = "system"
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

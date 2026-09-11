package com.nasmusic.tv.data.model

/**
 * 应用通用设置
 *
 * ⚠️ Gson 前向兼容约束（适用于本类及 data.model 包内所有 Gson 持久化 data class）：
 * 这些实例会经 Gson 序列化进 DataStore / 备份 JSON。新增字段**必须带默认值**，
 * 且不要使用「非空类型 + 无默认值」——Gson 基于反射构造，不会为 Kotlin 非空参数
 * 自动补默认值：用旧数据反序列化新代码会抛异常/置 null，用新数据反序列化旧代码
 * 同样失败。若确需新增无默认字段，必须同时提供 @JsonAdapter 或自定义
 * InstanceCreator / 迁移逻辑。
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
    val language: String = "system",
    // ── 离线下载（需求 6/7/8/9）──
    val downloadEnabled: Boolean = true,       // 本地下载总开关（关=禁止一切下载，已下载仍可播放）
    val autoDownloadOnPlay: Boolean = false,   // 播放时自动下载
    val autoDownloadLimit: Int = 50,           // 自动下载数量上限（1-5000）
    val downloadLocation: String = "INTERNAL"  // 下载位置（当前仅 INTERNAL，CUSTOM 为 P1 预留）
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

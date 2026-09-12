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
    // 可视化频谱主题（18 套效果 + 自动导演模式）
    val visualizerTheme: VisualizerTheme = VisualizerTheme.Default,
    // 可视化画质档位（HIGH / MEDIUM / LOW）
    val visualizerQuality: VisualQuality = VisualQuality.Default,
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

/**
 * 可视化效果主题（21 套手动效果，无自动导演档）。
 *
 * [tier] 决定该效果在各画质档位下的可用性，见 [VisualQuality.supports]。
 */
enum class VisualizerTheme(
    val displayName: String,
    val tier: Tier,
    val ordinalLabel: String
) {
IMMERSIVE_BLOOM("沉浸辉光", Tier.BASIC, "01"),
    TUNNEL_FLY("隧道穿越", Tier.BASIC, "03"),
    CIRCULAR_RING("圆形频谱环", Tier.BASIC, "05"),
    RADIAL_BURST("径向星芒", Tier.BASIC, "06"),
    FREQUENCY_MOUNTAIN("频率山峦", Tier.BASIC, "07"),
    PARTICLE_STORM("粒子风暴", Tier.ADV, "08"),
    PARTICLE_GALAXY("粒子银河", Tier.ADV, "09"),
    MIRROR_KALEIDO("万花筒", Tier.ADV, "10"),
    GALAXY_SPIRAL("星系螺旋", Tier.ADV, "11"),
    SPECTRO_WATERFALL("频谱瀑布", Tier.ADV, "12"),
    LIQUID_GRID("液态网格", Tier.ADV, "13"),
    BEAT_FIREWORK("节拍烟花", Tier.ADV, "14"),
    LIQUID_RIPPLE("液态涟漪", Tier.ADV, "15"),
    MATRIX_RAIN("数字雨", Tier.ADV, "16"),
    CONSTELLATION("星座", Tier.ADV, "17"),
    MILKDROP_FEEDBACK("反馈残像", Tier.ULTRA, "18"),
    PARTICLE_TEXT("粒子文字", Tier.ULTRA, "19"),
    PLASMA_FLOW("等离子流场", Tier.ULTRA, "20"),
    PRISM_HOLO("棱镜彩虹", Tier.ADV, "21"),
    AURORA("极光", Tier.ADV, "22"),
    LYRICS_DOT_MATRIX("歌词点阵", Tier.ADV, "23"),
    ;

    /** 效果分级：决定画质档位可用性 */
    enum class Tier { BASIC, ADV, ULTRA }

    companion object {
        val Default: VisualizerTheme = CIRCULAR_RING

        /** 历史枚举名 → 新主题。老用户 DataStore 存的是旧名，需平滑迁移 */
private val LEGACY_MAP = mapOf(
        "COLOR_FLOW" to CIRCULAR_RING,
        "NEON_PULSE" to IMMERSIVE_BLOOM,
        "SONIC_TERRAIN" to CIRCULAR_RING,
        "CIRCULAR_NEBULA" to CIRCULAR_RING,
        "CLASSICAL_WAVE" to CIRCULAR_RING,
        // AUTO_DIRECTOR 档已删除：老用户存过该值时回落到默认效果。
        // 与 fromKey 末尾 fallback 行为一致，显式写出是为了固化该迁移意图。
        "AUTO_DIRECTOR" to CIRCULAR_RING,
    )

        fun fromKey(key: String?): VisualizerTheme =
            entries.find { it.name == key }
                ?: LEGACY_MAP[key?.uppercase()]
                ?: Default

        /** 可手动选择的效果（无自动档；用户选了哪个就恒定显示哪个） */
        val selectable: List<VisualizerTheme> = entries
    }
}

/**
 * 可视化画质档位。
 *
 * @param barCount         渲染柱数
 * @param glowLayers       辉光层数
 * @param trail            是否启用拖尾残影
 * @param maxParticles     粒子上限（0 = 禁用粒子效果）
 * @param gridCols/gridRows 液态网格密度
 * @param allowFramebuffer 是否允许帧缓冲回绘（MilkDrop 类效果需要）
 */
enum class VisualQuality(
    val barCount: Int,
    val glowLayers: Int,
    val trail: Boolean,
    val maxParticles: Int,
    val gridCols: Int,
    val gridRows: Int,
    val allowFramebuffer: Boolean
) {
    HIGH(64, 3, true, 350, 32, 18, true),
    MEDIUM(64, 2, true, 150, 24, 14, false),
    LOW(32, 1, false, 0, 16, 10, false),
    ;

    fun supports(theme: VisualizerTheme): Boolean = when (theme.tier) {
        VisualizerTheme.Tier.BASIC -> true
        VisualizerTheme.Tier.ADV -> maxParticles > 0
        VisualizerTheme.Tier.ULTRA -> allowFramebuffer
    }

    companion object {
        val Default: VisualQuality = MEDIUM

        fun fromKey(key: String?): VisualQuality =
            entries.find { it.name.equals(key, ignoreCase = true) } ?: Default
    }
}

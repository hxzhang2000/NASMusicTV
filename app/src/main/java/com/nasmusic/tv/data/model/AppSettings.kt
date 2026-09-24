package com.nasmusic.tv.data.model

import com.nasmusic.tv.backend.photo.PhotoScaleMode
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId

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
    val downloadLocation: String = "INTERNAL",  // 下载位置（当前仅 INTERNAL，CUSTOM 为 P1 预留）

    // ── 照片墙（§7.3，17 个字段，全部带默认值）─────────────────────────────
    //
    // ⛔ **每个字段都必须有默认值**，这不是风格问题而是**正确性要求**：
    //   本类所有参数都有默认值 ⇒ Kotlin 会额外生成一个**无参构造器**，
    //   Gson 反序列化旧备份（缺这些字段）时走的就是它 ⇒ 缺的字段保持默认值。
    //   一旦有任一参数没有默认值，该无参构造器消失，Gson 会退回 `UnsafeAllocator`
    //   （不调构造器）⇒ **所有字段变成 JVM 默认值**（对象类型为 `null`）
    //   ⇒ 声明为非空的 `visualizerTheme` / `photoWallFixedTransition` 变 `null`
    //   ⇒ 备份导入时 `.name` 抛 NPE。详见 `data/prefs/BackupGson.kt` 的 KDoc。
    //
    // ⚠️ 「外接存储」的**平台相关默认值**（电视 `true` / 手机 `false`，§6.8）不在这里，
    //   而是在 `AppPreferences` 的读取处 —— 数据类的默认值只服务「Gson 构造」这一条路。

    /** 手机端「图库」（原总开关降级而来，§7.4）；**打开才申请照片权限** */
    val photoWallGalleryEnabled: Boolean = false,
    /** 外接存储（USB / SD 卡 / SAF 目录）；⚠️ 实际默认值按平台在 `AppPreferences` 决定 */
    val photoWallExternalEnabled: Boolean = false,
    /** Jellyfin 照片库；需 NAS 在线**且已建照片库** */
    val photoWallJellyfinEnabled: Boolean = false,
    /** 来源均衡：关 = 自然混合（按数量加权）；开 = 每次等概率选来源 */
    val photoWallSourceBalance: Boolean = false,
    /** 外接存储的目录（SAF URI，已 `takePersistableUriPermission`）；空 = 未选 */
    val photoWallDirUri: String = "",
    /** 仅扫常见目录（`DCIM` / `Pictures`）—— **仅电视自动探测时生效** */
    val photoWallCommonDirsOnly: Boolean = true,
    /** 仅显示含人像的照片（需先完成人脸检测） */
    val photoWallFacesOnly: Boolean = false,
    /** 人脸检测是否已完成（进度文案见阶段 11） */
    val photoWallFaceScanDone: Boolean = false,
    /** 随机切换转场（打开 = 每次切换重新抽；关 = 用 [photoWallFixedTransition]） */
    val photoWallRandomTransition: Boolean = true,
    /** 指定转场效果（仅随机关闭时生效）；⚠️ 其**流程模型映射仍生效**（选串行项就走串行） */
    val photoWallFixedTransition: PhotoTransitionId = PhotoTransitionId.CROSSFADE,
    /** 转场时长（300–2000 ms） */
    val photoWallTransitionMs: Int = 700,
    /** 停留时长（3000–30000 ms）；✅ 默认 8.0s（2026-09-23 用户确认） */
    val photoWallHoldMs: Int = 8000,
    /** 画面适配：`CROP` 满屏（裁切）/ `FIT` 完整（留黑边）；**四端均暴露** */
    val photoWallScaleMode: PhotoScaleMode = PhotoScaleMode.Default,
    /** 停留期 Ken Burns 缓慢推近 */
    val photoWallKenBurns: Boolean = true,
    /** 音频反应（默认**关** —— 见 §5.5「不卡节拍」决策） */
    val photoWallAudioReactive: Boolean = false,
    /** 随节拍缩放（受 [photoWallAudioReactive] 控制） */
    val photoWallPulseZoom: Boolean = true,
    /** 随低频呼吸（受 [photoWallAudioReactive] 控制） */
    val photoWallBreathe: Boolean = true
)

/**
 * 可视化效果主题（34 套手动效果，无自动导演档）。
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
    ECG_WAVE("心跳", Tier.BASIC, "24"),
    HYPNOTIC_FUNCTION("催眠", Tier.BASIC, "25"),
    VECTOR_WAVES("声弦", Tier.BASIC, "26"),
    PULSING_POLYGONS("几何环", Tier.BASIC, "27"),
    BAUHAUS_SHAPES("构成", Tier.ADV, "28"),
    ORBITAL_RINGS("轨道", Tier.ADV, "29"),
    RADAR_GRID("雷达", Tier.BASIC, "30"),
    ORIGAMI_POLY("折纸", Tier.ADV, "31"),
    STAIRCASE_WAVE("阶梯", Tier.BASIC, "32"),
    CONCENTRIC_GEARS("齿轮", Tier.BASIC, "33"),
    FRACTAL_TREE("分形", Tier.BASIC, "34"),
    LIGHT_BEAMS("光轴", Tier.BASIC, "35"),
    FERMAT_SPIRAL("螺旋", Tier.BASIC, "36"),
    MOLECULE("分子", Tier.BASIC, "37"),

    /**
     * 照片墙（第 36 个效果，§7.5）
     *
     * ⚠️ 归 [Tier.ADV]：照片双缓冲 + 转场叠加在低画质 / 老设备上风险高，必须门控。
     * 副作用：`VisualQuality.LOW`（`maxParticles == 0`）**不支持**本效果
     * ⇒ 若老电视被自动判为 `LOW`，照片墙在列表里看不到。见 `docs/technical-overview.md`。
     *
     * ⚠️ 本效果**不一定出现在 [selectable] 里** —— 三来源开关全关时被过滤掉（§7.4）。
     */
    PHOTO_WALL("照片墙", Tier.ADV, "38"),
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

        /**
         * 可手动选择的效果（无自动档；用户选了哪个就恒定显示哪个）。
         *
         * @param photoWallAvailable 三来源开关之「或」（[com.nasmusic.tv.visualizer.photo.PhotoWallAvailability]）。
         *   三个全关 ⇒ [PHOTO_WALL] **不出现在列表里**：指示器上没有它，
         *   左右键也切不到它（`VisualizerViewModel.step()` 用的是同一份列表）。
         *
         * ⛔ **刻意不给默认值** —— 三个调用点必须显式表态，避免日后漏传导致
         * 「三开关全关时左右键切到一个画不出东西的空效果上」。
         */
        fun selectable(photoWallAvailable: Boolean): List<VisualizerTheme> =
            if (photoWallAvailable) ALL else WITHOUT_PHOTO_WALL

        /** 全部效果（含 [PHOTO_WALL]）—— 预生成，避免每次调用重新 `filter` 分配 */
        private val ALL: List<VisualizerTheme> = entries

        /** 三来源全关时的列表 —— 预生成，同上 */
        private val WITHOUT_PHOTO_WALL: List<VisualizerTheme> = entries.filter { it != PHOTO_WALL }
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
        // 照片墙例外（2026-09-23 用户裁决）：不按粒子预算门控 —— 它没有粒子，
        // 内存由解码降级兜住（LOW = RGB_565 + 长边 1280）。其余 ADV 仍要求粒子预算。
        VisualizerTheme.Tier.ADV ->
            theme == VisualizerTheme.PHOTO_WALL || maxParticles > 0
        VisualizerTheme.Tier.ULTRA -> allowFramebuffer
    }

    companion object {
        val Default: VisualQuality = MEDIUM

        fun fromKey(key: String?): VisualQuality =
            entries.find { it.name.equals(key, ignoreCase = true) } ?: Default
    }
}

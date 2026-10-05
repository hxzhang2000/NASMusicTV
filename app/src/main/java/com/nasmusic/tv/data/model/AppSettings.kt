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
 * 可视化效果主题（21 套手动效果，无自动导演档）。
 *
 * [tier] 决定该效果在各画质档位下的可用性，见 [VisualQuality.supports]。
 *
 * @param needsParticleBudget 本效果的渲染器**是否真的读取 `ctx.quality.maxParticles`**。
 *   ⚠️ 它不是"看起来像不像粒子效果"的观感分类，而是一条可核对的事实：
 *   全仓库只有 `BeatFireworkRenderer` / `WorldGlobeRenderer` 两处读该预算，其余效果的
 *   开销与它无关（2026-10-05 删掉 9 个效果后重数；原名单里的 `ParticleTextRenderer` /
 *   `PlasmaFlowRenderer` 随其效果一同删除）。
 *   门控只消费 ADV 档上的该值（ULTRA 档由 [VisualQuality.allowFramebuffer] 决定），
 *   ULTRA 的粒子效果照实标注是为了让该字段本身可读。
 *   背景见 `docs/technical-overview.md`。
 */
enum class VisualizerTheme(
    val displayName: String,
    val tier: Tier,
    val ordinalLabel: String,
    val needsParticleBudget: Boolean = false
) {
    CIRCULAR_RING("圆形频谱环", Tier.BASIC, "05"),
    LIQUID_GRID("液态网格", Tier.ADV, "13"),
    BEAT_FIREWORK("节拍烟花", Tier.ADV, "14", needsParticleBudget = true),
    LIQUID_RIPPLE("液态涟漪", Tier.ADV, "15"),
    MATRIX_RAIN("数字雨", Tier.ADV, "16"),
    CONSTELLATION("星座", Tier.ADV, "17"),
    MILKDROP_FEEDBACK("反馈残像", Tier.ULTRA, "18"),
    LYRICS_DOT_MATRIX("歌词点阵", Tier.ADV, "23"),
    ECG_WAVE("心跳", Tier.BASIC, "24"),
    HYPNOTIC_FUNCTION("催眠", Tier.BASIC, "25"),
    ORBITAL_RINGS("太阳系", Tier.ADV, "29"),
    RADAR_GRID("雷达", Tier.BASIC, "30"),
    CONCENTRIC_GEARS("齿轮", Tier.BASIC, "33"),
    LIGHT_BEAMS("光轴", Tier.BASIC, "35"),
    MOLECULE("分子", Tier.BASIC, "37"),
    VINTAGE_TV("怀旧", Tier.BASIC, "38"),

    /**
     * 照片墙（第 39 个效果，§7.5）
     *
     * ⚠️ 归 [Tier.ADV]：照片双缓冲 + 转场叠加在老设备上风险高。
     * 但本效果**没有粒子** ⇒ [needsParticleBudget] 为 false，故 `VisualQuality.LOW` **提供**它
     * （2026-09-23 用户裁决，内存由解码降级兜住：LOW = RGB_565 + 长边 1280）。
     *
     * ⚠️ 本效果**不一定出现在 [selectable] 里** —— 三来源开关全关时被过滤掉（§7.4）。
     */
    PHOTO_WALL("照片墙", Tier.ADV, "39"),

    /**
     * DNA 双螺旋（第 40 个效果）
     *
     * 视觉：一条自由弯曲的 DNA 双螺旋缎带蜿蜒于画面中央 —— 青/紫双骨架绕中心
     * Catmull-Rom 样条反相螺旋、4 色碱基对横档连接，深空星野底（同「太阳系」风格）。
     *
     * 归 [Tier.ADV]，但**不**标注 [needsParticleBudget]（渲染器不读 `maxParticles`，
     * 骨架 / 碱基对 / 星野的规模都是常量）⇒ 三档均可选，与数字雨同理。
     * 无照片墙式特殊门控 —— [selectable] 恒含本项，三来源开关不影响。
     */
    DNA("DNA 双螺旋", Tier.ADV, "40"),

    /**
     * 世界（第 41 个效果）
     *
     * 视觉：一颗缓慢呼吸的星球 —— Robinson 投影的世界陆地轮廓（预抽取内嵌，按画质分 3 档
     * 精度）+ 32 座城市光点（大小/亮度按机场年旅客吞吐量 Tier 1–4 分级）+ 按真实航空客流
     * 规模生成的动态大圆航线（主干 + 支线层次）。叠加真实 UTC 晨昏线、极淡星野、径向渐变
     * 与暗角。音乐分频段驱动：整体能量→轻微缩放、低频→轮廓呼吸、中频→航线密度/弧高、
     * 高频→尾迹与光晕；拍点分层（弱/中/强）分别驱动 Tier4 闪烁 / Tier3 支线 / Tier1 主干
     * + 涟漪。⛔ 不渲染任何文字、标签、数据面板，也不响应任何触摸。
     *
     * 归 [Tier.ADV] 且标注 [needsParticleBudget]：地图解码 + 最多 34 条活跃航线 + 分层光晕
     * 的规模由 `ctx.quality.maxParticles` 决定（[com.nasmusic.tv.visualizer.renderers.WorldGlobeRenderer]
     * 真读该预算）⇒ `VisualQuality.LOW`（`maxParticles == 0`）不提供本效果。
     *
     * **音频源复用既有分析层**（[com.nasmusic.tv.visualizer.AudioFrame]），
     * ⛔ 不新增 `RECORD_AUDIO` 权限、不用 `AudioRecord`/`Visualizer` ——
     * 与其余 40 套效果同源，零新增权限、零新增依赖。
     */
    WORLD("世界", Tier.ADV, "41", needsParticleBudget = true),

    /**
     * 星空星轨（第 42 个效果）
     *
     * 视觉：长曝光星轨照片的母题 —— 深蓝天幕（5 段垂直渐变）+ 绕**天极**自转的同心弧星轨
     * + 真实的**时间曝光拖尾**（自有 ping-pong 累积缓冲 + 指数衰减）+ 近黑地景剪影
     * （起伏山脊 + 一棵枯树，地面占底部 1/4）。⛔ **画面中不出现任何人物剪影**。
     *
     * 频率 → 环绕天极的**弧半径**（低音贴天极、高音外扩，半径指数 `t^0.72` 向极聚密）；
     * 幅值 → 弧的**扫掠角 / 线宽 / 亮度**；鼓点（`bassRaw` + `beat`）→ 极点闪光 + 沿同心圆弧
     * 抛射的切向流星；段落响度（`sectionEnergy`）→ 天色向亮蓝紫偏移；`pulse` → 静音时的
     * 辉光呼吸（⛔ 静音**不全黑**，仍可见缓慢自转的星场与残迹）。
     *
     * 归 [Tier.ADV]（双缓冲像素回绘与照片墙同风险档）但**刻意标注
     * [needsParticleBudget] = false** —— 这是该字段唯一被消费的可核对事实：本效果
     * [com.nasmusic.tv.visualizer.renderers.StarrySkyRenderer] **一个粒子都不画**，
     * 拖尾是两张**固定尺寸**的 `ImageBitmap`（按 `fx.level` 三档 1280 / 960 / 640 降分辨率），
     * 规模与 `ctx.quality.maxParticles` 毫无关系；柱数恒读 `frame.spectrum.size`。
     * ⇒ [VisualQuality.supports] 的 ADV 分支恒为 true，**三档画质全部可选**（含 LOW：
     * LOW 只把缓冲宽降到 640 并把弧线隔柱取样，视觉效果保留）。
     *
     * **不**门控 [VisualQuality.allowFramebuffer]（同照片墙的取舍）：门控后默认 MEDIUM
     * 档恒无拖尾，效果就退化成一块星空衬底、失去「长曝光」本体。
     */
    STAR_TRAILS("星空星轨", Tier.ADV, "42", needsParticleBudget = false),

    /**
     * 海边（第 43 个效果）
     *
     * 视觉：俯拍一条俯冲的海岸线 —— 上半屏是**离岸渐远**的深水（底色竖向渐变 + 低分辨率
     * 水体场 + 粼光/焦散网），下半屏是**烘焙好的干沙**；一条**离散浪队列**（`WAVE_POOL`
     * 个槽位）自外海向岸推进，把白浪带一路推上滩，抵滩后**与退水并行**地淡出（泡沫原地
     * 淡出、水线在自适应时长里回退），露出**逐列记忆的湿沙**与退水残沫，再按逐列干燥
     * 时间常数收干。另有沙滩小螃蟹横穿（纯装饰、不参与任何模拟量）。
     *
     * ## ⛔ 归 [Tier.BASIC] 的理由（§3.4）
     * - [VisualQuality.supports] 的现行规则（2026-10-01 用户裁决）是
     *   `ADV -> !needsParticleBudget || maxParticles > 0`。本效果**一个粒子都不画**、
     *   ⛔ **不读** `ctx.quality.maxParticles` ⇒ 即便标 `false` 也是三档全可见；
     *   而 `BASIC -> true` 是**无条件**分支，语义上也更准确 —— 它真的「什么都不消耗」。
     * - ⛔ **不要**由此推断 `allowFramebuffer`：`ULTRA -> allowFramebuffer`，而本效果
     *   **不用帧缓冲**（全部是矢量绘制 + 尺寸变化时烘焙的位图），故与该字段无关。
     *
     * ## ⛔ 第 4 参 `needsParticleBudget = false` 是**可核对的事实**，不是观感分类
     * 全仓库只有 `BeatFireworkRenderer` / `WorldGlobeRenderer` 两处真读该预算（见本枚举
     * KDoc；2026-10-05 删掉 9 个效果后重数）。本效果所有「数量」都是**编译期
     * 常量**（浪槽位 [com.nasmusic.tv.visualizer.renderers.SeasideWaves.WAVE_POOL] = 6、
     * 飞沫 180 点、残沫 52 点、浪花手指 30 根、水洼 16 个），逐帧按 `fx.level` 分档而
     * **不按粒子预算分档**；频谱柱数亦恒读 `frame.spectrum.size`。
     * ⚠️ 因为 [tier] 是 BASIC，[VisualQuality.supports] 的 BASIC 分支**根本不看**这一项 ——
     * 写成 `true` 也不会有任何行为差异。仍显式写 `false`：它是关于渲染器的真话，
     * 且一旦日后有人把它改挂 ADV，这一项必须已经是对的。
     *
     * **不**门控 [VisualQuality.allowFramebuffer]（§3.4 末条）。
     */
    SEASIDE("海边", Tier.BASIC, "43", needsParticleBudget = false),
    ;

    /** 效果分级：决定画质档位可用性 */
    enum class Tier { BASIC, ADV, ULTRA }

    companion object {
        val Default: VisualizerTheme = CIRCULAR_RING

        /** 历史枚举名 → 新主题。老用户 DataStore 存的是旧名，需平滑迁移 */
private val LEGACY_MAP = mapOf(
        "COLOR_FLOW" to CIRCULAR_RING,
        "NEON_PULSE" to CIRCULAR_RING,
        "SONIC_TERRAIN" to CIRCULAR_RING,
        "CIRCULAR_NEBULA" to CIRCULAR_RING,
        "CLASSICAL_WAVE" to CIRCULAR_RING,
        // AUTO_DIRECTOR 档已删除：老用户存过该值时回落到默认效果。
        // 与 fromKey 末尾 fallback 行为一致，显式写出是为了固化该迁移意图。
        "AUTO_DIRECTOR" to CIRCULAR_RING,
        // 以下 11 个效果已从效果库移除：老用户存过的值回落到默认 CIRCULAR_RING
        "IMMERSIVE_BLOOM" to CIRCULAR_RING,
        "RADIAL_BURST" to CIRCULAR_RING,
        "PARTICLE_STORM" to CIRCULAR_RING,
        "PARTICLE_GALAXY" to CIRCULAR_RING,
        "MIRROR_KALEIDO" to CIRCULAR_RING,
        "PRISM_HOLO" to CIRCULAR_RING,
        "AURORA" to CIRCULAR_RING,
        "VECTOR_WAVES" to CIRCULAR_RING,
        "PULSING_POLYGONS" to CIRCULAR_RING,
        "BAUHAUS_SHAPES" to CIRCULAR_RING,
        "FERMAT_SPIRAL" to CIRCULAR_RING,
        // 以下 9 个效果已于 v2.38.2 从效果库移除（30 → 21）：同样回落到默认
        // CIRCULAR_RING。⛔ `fromKey` 末位 fallback 本就等价，补进来是为了
        // 与上一批 11 个保持同一份「迁移意图」清单，并让
        // `docs/visualizer-effects-list.md` 的说明继续成立。
        "TUNNEL_FLY" to CIRCULAR_RING,
        "FREQUENCY_MOUNTAIN" to CIRCULAR_RING,
        "GALAXY_SPIRAL" to CIRCULAR_RING,
        "SPECTRO_WATERFALL" to CIRCULAR_RING,
        "PARTICLE_TEXT" to CIRCULAR_RING,
        "PLASMA_FLOW" to CIRCULAR_RING,
        "ORIGAMI_POLY" to CIRCULAR_RING,
        "STAIRCASE_WAVE" to CIRCULAR_RING,
        "FRACTAL_TREE" to CIRCULAR_RING,
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
        // （2026-10-01 用户裁决，取"方案 C"）ADV 的门槛是**该效果是否真消耗粒子预算**，
        // 不再是"它是不是 ADV"。原先用 `maxParticles > 0` 当 ADV 代理条件是语义错配：
        // 数字雨 / 星座 / DNA / 照片墙…一颗粒子都不画，却被代理条件挡在 LOW 档之外
        // （表现为"能渲染却不可选"，切走即永久回不来）。
        // 照片墙（2026-09-23 用户裁决）没有粒子、内存由解码降级兜住（LOW = RGB_565 + 长边 1280），
        // ⇒ 该裁决现在由同一条规则自然成立，不再需要特例分支。
        VisualizerTheme.Tier.ADV -> !theme.needsParticleBudget || maxParticles > 0
        VisualizerTheme.Tier.ULTRA -> allowFramebuffer
    }

    companion object {
        val Default: VisualQuality = MEDIUM

        fun fromKey(key: String?): VisualQuality =
            entries.find { it.name.equals(key, ignoreCase = true) } ?: Default
    }
}

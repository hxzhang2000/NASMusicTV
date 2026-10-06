package com.nasmusic.tv.visualizer.photo

import com.nasmusic.tv.visualizer.Easing
import com.nasmusic.tv.R
import androidx.annotation.StringRes

/**
 * 照片转场效果（**76 种**，§4.2 完整清单）
 *
 * ## 为什么元信息放在枚举而不是实现类
 *
 * [mechanism] / [baseDurationMs] / [easing] / [requiresSequential] / [audioReactive] / [degradeTo]
 * 都是 `PhotoTransitionPicker`（抽池）与 `PhotoTransitionClock`（定时长 / 定模型 / 定缓动）
 * **在拿到实例之前**就要读的信息。放枚举 ⇒ ① 单一来源；② 可写「枚举 ↔ 注册表一致性」门禁（G4）。
 *
 * ## [phase] 与「已实现」不是一回事
 *
 * [phase] 是**规划分期**（P0 15 种 / P1 28 种 / P2 33 种），枚举一次写全 76 项；
 * 但**代码里实现了几种**由 [PhotoTransitionRegistry] 决定。抽池必须基于「真有实现」的集合
 * —— 否则会抽到一个空转场（画面卡住）。见 [randomPool]。
 *
 * ⚠️ 不要与本文件外的 `PhotoTransitionClock.Phase`（IDLE / ENTER / HOLD / EXIT）混淆：
 * 那个是**一次切换内的阶段**，这里是**交付分期**。
 */
enum class PhotoTransitionId(
    /** 显示名资源 ID（本地化展示用） */
    @StringRes val displayNameRes: Int,
    /** 中文显示名（数据用途，如搜索词/持久化；UI 展示走 [displayNameRes]） */
    val displayName: String,
    val mechanism: PhotoMechanism,
    /** ENTER 基准时长（ms）；EXIT 与同一时段等长，无需独立配置（§5.3） */
    val baseDurationMs: Int,
    val phase: Phase,
    /**
     * 需要「暗场」语义 ⇒ **强制走串行模型**（§5.2 / §5.9）。
     * 交叉模型下画不出「经黑场」这件事。
     */
    val requiresSequential: Boolean = false,
    /** 形态由音频数据驱动 ⇒ **排除出随机池**（§5.9：用户主动选中才生效） */
    val audioReactive: Boolean = false,
    /**
     * 老平台（`sdkInt < 33`）的**降级目标**（§4.4）。
     *
     * ⚠️ `null` 不代表「不降级」—— 多数 M5 效果是在**同一 id 内**走 M3 实现（如 `RIPPLE`
     * 用同心圆遮罩近似），只有 [LIQUIFY] 没有合理近似、必须**换成另一个效果**。
     */
    val degradeTo: PhotoTransitionId? = null,
    /**
     * 进度缓动（§5.3 的「缓动」列 + §14.3 的「缓动」列）。
     *
     * ## 为什么在枚举里
     *
     * `PhotoTransitionClock` 必须**在拿到转场实例之前**把 `progress` 缓动好
     * （§14.2.3：`eased` 是时钟的输出，`PhotoTransition.render` 只收「已缓动的 `p`」）——
     * 所以缓动不能藏在实现类里。
     *
     * ## 为什么不给默认值
     *
     * 76 项**全部显式写出** ⇒ 新增枚举项时漏写缓动会**编译失败**（编译器即门禁）。
     * 给默认值的话，漏写会静默用上 `easeInOutQuad` —— 而 §5.3 里只有 A / D 两类用它是正确的，
     * 溶解类用它会明显变味（溶解需要均匀）。
     *
     * ⚠️ 取值依据：§14.3 的 P0 15 行优先（它是逐项表），其余按 §5.3 的**类别**行；
     * 风格化类（J）§5.3 写的是「按子类型」，按各自机制归入最接近的类别。
     * 唯一与 §5.3 类别行冲突的是 [SPECTRUM_WIPE]（色彩/光效类别行是 `easeOutQuad`，
     * 但 §14.3 逐项表写 `easeInOutSine`）—— **以 §14.3 逐项表为准**。
     */
    val easing: (Float) -> Float,
) {

    // ────────────────────────── A. 淡化类（4）· §5.3：easeInOutQuad ──────────────────────────

    CROSSFADE(R.string.photo_transition_crossfade, "交叉淡化", PhotoMechanism.M1_CLIP, 800, Phase.P0, easing = Easing::easeInOutQuad),
    FADE_BLACK(
        R.string.photo_transition_fade_black, "经黑场", PhotoMechanism.M1_CLIP, 800, Phase.P0,
        requiresSequential = true, easing = Easing::easeInOutQuad,
    ),
    FADE_WHITE(
        R.string.photo_transition_fade_white, "白闪", PhotoMechanism.M1_CLIP, 800, Phase.P1,
        requiresSequential = true, easing = Easing::easeInOutQuad,
    ),
    FADE_COLOR(R.string.photo_transition_fade_color, "主题色过渡", PhotoMechanism.M1_CLIP, 800, Phase.P1, easing = Easing::easeInOutQuad),

    // ────────────────────────── B. 滑动 / 位移类（9）· §5.3：easeOutCubic ──────────────────────────

    SLIDE_LEFT(R.string.photo_transition_slide_left, "左滑", PhotoMechanism.M2_TRANSFORM, 500, Phase.P0, easing = Easing::easeOutCubic),
    SLIDE_RIGHT(R.string.photo_transition_slide_right, "右滑", PhotoMechanism.M2_TRANSFORM, 500, Phase.P0, easing = Easing::easeOutCubic),
    SLIDE_UP(R.string.photo_transition_slide_up, "上滑", PhotoMechanism.M2_TRANSFORM, 500, Phase.P0, easing = Easing::easeOutCubic),
    SLIDE_DOWN(R.string.photo_transition_slide_down, "下滑", PhotoMechanism.M2_TRANSFORM, 500, Phase.P0, easing = Easing::easeOutCubic),
    PUSH(R.string.photo_transition_push, "推挤", PhotoMechanism.M2_TRANSFORM, 500, Phase.P1, easing = Easing::easeOutCubic),
    COVER(R.string.photo_transition_cover, "覆盖", PhotoMechanism.M2_TRANSFORM, 500, Phase.P1, easing = Easing::easeOutCubic),
    REVEAL(R.string.photo_transition_reveal, "揭示", PhotoMechanism.M2_TRANSFORM, 500, Phase.P1, easing = Easing::easeOutCubic),
    SLIDE_DIAGONAL(R.string.photo_transition_slide_diagonal, "对角滑动", PhotoMechanism.M2_TRANSFORM, 500, Phase.P1, easing = Easing::easeOutCubic),
    PARALLAX_SLIDE(R.string.photo_transition_parallax_slide, "视差滑动", PhotoMechanism.M2_TRANSFORM, 500, Phase.P2, easing = Easing::easeOutCubic),

    // ────────────────────────── C. 缩放 / 深度类（7）· §5.3：easeInOutCubic ──────────────────────────

    ZOOM_IN(R.string.photo_transition_zoom_in, "缩放进入", PhotoMechanism.M2_TRANSFORM, 700, Phase.P0, easing = Easing::easeInOutCubic),
    ZOOM_OUT(R.string.photo_transition_zoom_out, "缩放退出", PhotoMechanism.M2_TRANSFORM, 700, Phase.P0, easing = Easing::easeInOutCubic),
    CROSS_ZOOM(R.string.photo_transition_cross_zoom, "交叉缩放", PhotoMechanism.M2_TRANSFORM, 700, Phase.P1, easing = Easing::easeInOutCubic),
    ZOOM_THROUGH(R.string.photo_transition_zoom_through, "穿越", PhotoMechanism.M2_TRANSFORM, 700, Phase.P1, easing = Easing::easeInOutCubic),
    DEPTH_BLUR(
        R.string.photo_transition_depth_blur, "景深虚化过渡", PhotoMechanism.M3_MASK_BITMAP, 700, Phase.P1,
        easing = Easing::easeInOutCubic,
    ),
    PERSPECTIVE_PUSH(
        R.string.photo_transition_perspective_push, "3D 纵深推拉", PhotoMechanism.M2_TRANSFORM, 700, Phase.P2,
        easing = Easing::easeInOutCubic,
    ),
    DOLLY_ZOOM(R.string.photo_transition_dolly_zoom, "希区柯克变焦", PhotoMechanism.M2_TRANSFORM, 700, Phase.P2, easing = Easing::easeInOutCubic),

    // ────────────────────────── D. 遮罩形状类（10）· §5.3：easeInOutQuad ──────────────────────────

    IRIS_CIRCLE(R.string.photo_transition_iris_circle, "圆形光圈", PhotoMechanism.M1_CLIP, 700, Phase.P0, easing = Easing::easeInOutQuad),
    IRIS_DIAMOND(R.string.photo_transition_iris_diamond, "菱形展开", PhotoMechanism.M1_CLIP, 700, Phase.P1, easing = Easing::easeInOutQuad),
    IRIS_STAR(R.string.photo_transition_iris_star, "星形展开", PhotoMechanism.M1_CLIP, 700, Phase.P1, easing = Easing::easeInOutQuad),
    IRIS_HEXAGON(R.string.photo_transition_iris_hexagon, "六边形蜂巢", PhotoMechanism.M1_CLIP, 700, Phase.P1, easing = Easing::easeInOutQuad),
    IRIS_TRIANGLE(R.string.photo_transition_iris_triangle, "三角形展开", PhotoMechanism.M1_CLIP, 700, Phase.P2, easing = Easing::easeInOutQuad),
    SHAPE_RANDOM(R.string.photo_transition_shape_random, "随机形状池", PhotoMechanism.M1_CLIP, 700, Phase.P1, easing = Easing::easeInOutQuad),
    WIPE_LINEAR(R.string.photo_transition_wipe_linear, "线性擦除", PhotoMechanism.M1_CLIP, 700, Phase.P0, easing = Easing::easeInOutQuad),
    WIPE_CLOCK(R.string.photo_transition_wipe_clock, "时钟擦除", PhotoMechanism.M1_CLIP, 700, Phase.P1, easing = Easing::easeInOutQuad),
    WIPE_SPIRAL(R.string.photo_transition_wipe_spiral, "螺旋擦除", PhotoMechanism.M1_CLIP, 700, Phase.P2, easing = Easing::easeInOutQuad),
    WIPE_CROSS(R.string.photo_transition_wipe_cross, "十字擦除", PhotoMechanism.M1_CLIP, 700, Phase.P1, easing = Easing::easeInOutQuad),

    // ────────────────────────── E. 条纹 / 分块类（8）· §5.3：每块 easeOut ⇒ easeOutQuad ──────────────────────────

    // ⚠️ 900ms + 逐块 stagger 300ms ⇒ 基准总时长取 1200
    BLINDS_H(R.string.photo_transition_blinds_h, "横向百叶窗", PhotoMechanism.M4_TILES, 1_200, Phase.P0, easing = Easing::easeOutQuad),
    BLINDS_V(R.string.photo_transition_blinds_v, "竖向百叶窗", PhotoMechanism.M4_TILES, 1_200, Phase.P0, easing = Easing::easeOutQuad),
    CHECKERBOARD(R.string.photo_transition_checkerboard, "棋盘格", PhotoMechanism.M4_TILES, 1_200, Phase.P1, easing = Easing::easeOutQuad),
    BLOCKS_RANDOM(R.string.photo_transition_blocks_random, "随机方块消融", PhotoMechanism.M4_TILES, 1_200, Phase.P1, easing = Easing::easeOutQuad),
    GRID_FLIP(R.string.photo_transition_grid_flip, "网格 3D 翻转", PhotoMechanism.M4_TILES, 1_200, Phase.P2, easing = Easing::easeOutQuad),
    MOSAIC(R.string.photo_transition_mosaic, "马赛克渐显", PhotoMechanism.M4_TILES, 1_200, Phase.P2, easing = Easing::easeOutQuad),
    TILE_CASCADE(R.string.photo_transition_tile_cascade, "瓦片错落", PhotoMechanism.M4_TILES, 1_200, Phase.P1, easing = Easing::easeOutQuad),
    PUZZLE(R.string.photo_transition_puzzle, "拼图碎片", PhotoMechanism.M4_TILES, 1_200, Phase.P2, easing = Easing::easeOutQuad),

    // ────────────────────────── F. 溶解 / 噪点类（6）· §5.3：linear ──────────────────────────

    NOISE_DISSOLVE(R.string.photo_transition_noise_dissolve, "噪声溶解", PhotoMechanism.M3_MASK_BITMAP, 1_000, Phase.P0, easing = Easing::linear),
    THRESHOLD_SWEEP(R.string.photo_transition_threshold_sweep, "阈值扫过", PhotoMechanism.M3_MASK_BITMAP, 1_000, Phase.P1, easing = Easing::linear),
    SCANLINE_DISSOLVE(R.string.photo_transition_scanline_dissolve, "扫描线溶解", PhotoMechanism.M3_MASK_BITMAP, 1_000, Phase.P1, easing = Easing::linear),
    GRAIN_DISSOLVE(R.string.photo_transition_grain_dissolve, "颗粒溶解", PhotoMechanism.M3_MASK_BITMAP, 1_000, Phase.P2, easing = Easing::linear),
    PIXELATE(R.string.photo_transition_pixelate, "像素化过渡", PhotoMechanism.M4_TILES, 1_000, Phase.P2, easing = Easing::linear),
    HALFTONE(R.string.photo_transition_halftone, "半调网点", PhotoMechanism.M3_MASK_BITMAP, 1_000, Phase.P2, easing = Easing::linear),

    // ────────────────────────── G. 扭曲 / 形变类（8）· §5.3：easeInOutSine ──────────────────────────

    // ⚠️ M5 项：老平台走**同 id 内**的 M3 近似（§4.4），不是换效果
    RIPPLE(R.string.photo_transition_ripple, "波纹扭曲", PhotoMechanism.M5_SHADER, 700, Phase.P2, easing = Easing::easeInOutSine),
    WAVE_WARP(R.string.photo_transition_wave_warp, "波浪位移", PhotoMechanism.M5_SHADER, 700, Phase.P2, easing = Easing::easeInOutSine),
    SWIRL(R.string.photo_transition_swirl, "漩涡", PhotoMechanism.M5_SHADER, 700, Phase.P2, easing = Easing::easeInOutSine),
    LIQUIFY(
        R.string.photo_transition_liquify, "液化", PhotoMechanism.M5_SHADER, 700, Phase.P2,
        degradeTo = NOISE_DISSOLVE, easing = Easing::easeInOutSine,
    ),
    KALEIDO(R.string.photo_transition_kaleido, "万花筒转场", PhotoMechanism.M4_TILES, 700, Phase.P1, easing = Easing::easeInOutSine),
    SHATTER(R.string.photo_transition_shatter, "玻璃破碎", PhotoMechanism.M4_TILES, 700, Phase.P2, easing = Easing::easeInOutSine),
    VORONOI(R.string.photo_transition_voronoi, "Voronoi 碎片化", PhotoMechanism.M4_TILES, 700, Phase.P2, easing = Easing::easeInOutSine),
    MELT(R.string.photo_transition_melt, "融化流淌", PhotoMechanism.M5_SHADER, 700, Phase.P2, easing = Easing::easeInOutSine),

    // ────────────────────────── H. 色彩 / 光效类（7）· §5.3：easeOutQuad ──────────────────────────

    LIGHT_SWEEP(R.string.photo_transition_light_sweep, "光扫", PhotoMechanism.M2_TRANSFORM, 600, Phase.P0, easing = Easing::easeOutQuad),
    CHROMATIC_SPLIT(
        R.string.photo_transition_chromatic_split, "RGB 色彩分离", PhotoMechanism.M2_TRANSFORM, 600, Phase.P1,
        easing = Easing::easeOutQuad,
    ),
    RGB_SLIDE(R.string.photo_transition_rgb_slide, "色彩分离滑入", PhotoMechanism.M2_TRANSFORM, 600, Phase.P1, easing = Easing::easeOutQuad),
    EXPOSURE_FLASH(R.string.photo_transition_exposure_flash, "曝光闪白", PhotoMechanism.M1_CLIP, 600, Phase.P1, easing = Easing::easeOutQuad),
    BLOOM_TRANSITION(
        R.string.photo_transition_bloom_transition, "光晕绽放", PhotoMechanism.M2_TRANSFORM, 600, Phase.P2,
        easing = Easing::easeOutQuad,
    ),
    COLOR_BURN(R.string.photo_transition_color_burn, "色彩烧灼", PhotoMechanism.M3_MASK_BITMAP, 600, Phase.P2, easing = Easing::easeOutQuad),
    // ⚠️ 唯一与 §5.3 类别行冲突的项：§14.3 逐项表写 easeInOutSine ⇒ 以逐项表为准
    SPECTRUM_WIPE(R.string.photo_transition_spectrum_wipe, "频谱擦除", PhotoMechanism.M1_CLIP, 700, Phase.P0, easing = Easing::easeInOutSine),

    // ────────────────────────── I. 音频反应类（5）★ 默认不启用 · §5.3：easeInOutSine ──────────────────────────

    SPECTRUM_BARS(
        R.string.photo_transition_spectrum_bars, "频谱条带切换", PhotoMechanism.M4_TILES, 700, Phase.P1,
        audioReactive = true, easing = Easing::easeInOutSine,
    ),
    BEAT_CUT(
        R.string.photo_transition_beat_cut, "节拍硬切", PhotoMechanism.M1_CLIP, 700, Phase.P1,
        audioReactive = true, easing = Easing::easeInOutSine,
    ),
    BASS_BLOOM(
        R.string.photo_transition_bass_bloom, "低频绽放", PhotoMechanism.M1_CLIP, 700, Phase.P2,
        audioReactive = true, easing = Easing::easeInOutSine,
    ),
    WAVEFORM_WIPE(
        R.string.photo_transition_waveform_wipe, "波形擦除", PhotoMechanism.M1_CLIP, 700, Phase.P2,
        audioReactive = true, easing = Easing::easeInOutSine,
    ),
    PULSE_DISSOLVE(
        R.string.photo_transition_pulse_dissolve, "脉动溶解", PhotoMechanism.M3_MASK_BITMAP, 700, Phase.P2,
        audioReactive = true, easing = Easing::easeInOutSine,
    ),

    // ────────────────────────── J. 风格化类（7）· §5.3「按子类型」⇒ 归入最接近的类别 ──────────────────────────

    // 故障风 / 胶片卷动 / 漫画分格 = 分块类 ⇒ easeOutQuad
    GLITCH(R.string.photo_transition_glitch, "故障风", PhotoMechanism.M4_TILES, 800, Phase.P1, easing = Easing::easeOutQuad),
    FILM_ROLL(R.string.photo_transition_film_roll, "胶片卷动", PhotoMechanism.M4_TILES, 800, Phase.P2, easing = Easing::easeOutQuad),
    COMIC_PANEL(R.string.photo_transition_comic_panel, "漫画分格", PhotoMechanism.M4_TILES, 800, Phase.P2, easing = Easing::easeOutQuad),
    // 电影黑边收缩 = 暗场语义的裁剪 ⇒ 淡化类 ⇒ easeInOutQuad
    CINEMATIC_BARS(
        R.string.photo_transition_cinematic_bars, "电影黑边收缩", PhotoMechanism.M1_CLIP, 800, Phase.P1,
        requiresSequential = true, easing = Easing::easeInOutQuad,
    ),
    // 3D 翻页 = 空间变换 ⇒ 缩放/深度类 ⇒ easeInOutCubic
    PAGE_FLIP(R.string.photo_transition_page_flip, "3D 翻页", PhotoMechanism.M2_TRANSFORM, 800, Phase.P2, easing = Easing::easeInOutCubic),
    // 数字雨 / 霓虹描边 = 光效 ⇒ 色彩/光效类 ⇒ easeOutQuad
    MATRIX_OVERLAY(R.string.photo_transition_matrix_overlay, "数字雨覆盖", PhotoMechanism.M2_TRANSFORM, 800, Phase.P2, easing = Easing::easeOutQuad),
    NEON_TRACE(R.string.photo_transition_neon_trace, "霓虹描边", PhotoMechanism.M2_TRANSFORM, 800, Phase.P2, easing = Easing::easeOutQuad),

    // ────────────────────────── K. 有机 / 模拟类（5）· §5.3：easeOutSine ──────────────────────────

    INK_SPREAD(R.string.photo_transition_ink_spread, "泼墨扩散", PhotoMechanism.M3_MASK_BITMAP, 1_200, Phase.P2, easing = Easing::easeOutSine),
    WATERCOLOR(R.string.photo_transition_watercolor, "水彩晕染", PhotoMechanism.M3_MASK_BITMAP, 1_200, Phase.P2, easing = Easing::easeOutSine),
    SAND_DISSOLVE(R.string.photo_transition_sand_dissolve, "沙化", PhotoMechanism.M3_MASK_BITMAP, 1_200, Phase.P2, easing = Easing::easeOutSine),
    BURN(R.string.photo_transition_burn, "火焰燃烧", PhotoMechanism.M3_MASK_BITMAP, 1_200, Phase.P2, easing = Easing::easeOutSine),
    FROST(R.string.photo_transition_frost, "冰冻结晶", PhotoMechanism.M3_MASK_BITMAP, 1_200, Phase.P2, easing = Easing::easeOutSine),
    ;

    /** 交付分期（⛔ 不是「一次切换内的阶段」—— 那是 `PhotoTransitionClock.Phase`） */
    enum class Phase { P0, P1, P2 }

    companion object {

        /**
         * 默认转场。
         *
         * §7.3：`AppSettings.photoWallFixedTransition` 的默认值就是它。
         * ⚠️ 它同时是**枚举的第一个常量** —— `BackupGson` 的第 ③ 级回落（无法识别的名字
         * ⇒ 首个常量）因此恰好落到默认值上，这个巧合是**有意保持**的：
         * 调整枚举顺序会静默改变「无法识别的老名字」的迁移结果。
         */
        val Default: PhotoTransitionId = CROSSFADE

        /**
         * 从持久化字符串解析（DataStore 那条路）。
         *
         * ⚠️ 未命中返回 [Default]（与 `VisualizerTheme.fromKey` / `PhotoScaleMode.fromKey`
         * 同款语义：**永不返回 null**，避免调用方拿到 null 再抛 NPE）。
         *
         * ⛔ 目前**没有历史名映射**（本枚举只增不改名）。日后若重命名任何常量，
         * 必须在此加映射 —— 否则老备份里的旧名只能走 `BackupGson` 的第 ③ 级回落
         * （首个常量），迁移结果未必正确。见 `docs/technical-overview.md` §10.172。
         */
        fun fromKey(key: String?): PhotoTransitionId =
            entries.find { it.name == key } ?: Default

        /**
         * 当前分期应实现的全部效果（`phase.ordinal <= 给定分期`）。
         *
         * ⚠️ 这只是**规划**，不代表真有实现 —— 抽池请用 [randomPool] 的
         * `available` 重载（由 `PhotoTransitionRegistry` 给出真有实现的集合）。
         */
        fun implemented(phase: Phase): List<PhotoTransitionId> =
            entries.filter { it.phase.ordinal <= phase.ordinal }

        /**
         * 当前平台上的**实际标识**。
         *
         * 只有 [degradeTo] 非空的项（目前仅 [LIQUIFY]）会换成另一个效果；
         * 其余 M5 项返回自身（降级发生在实现内部）。
         */
        fun effective(id: PhotoTransitionId, sdkInt: Int): PhotoTransitionId =
            if (sdkInt < SHADER_MIN_SDK) id.degradeTo ?: id else id

        /** AGSL shader 的最低 API（§4.4） */
        const val SHADER_MIN_SDK = 33

        /**
         * 随机池 —— §5.9 的三条规则集中在此，便于单测（门禁 G5）。
         *
         * ① 排除 `audioReactive`（用户主动选中才生效，进池等于违背该决策）；
         * ② 按**平台降级后**的标识去重（老平台上 `LIQUIFY` 会变成 `NOISE_DISSOLVE`
         *    ⇒ 若两者都在池里，同一个效果会被抽到两次）；
         * ③ 池内去重（同一效果只出现一次）。
         *
         * @param available **真有实现**的效果集合（`PhotoTransitionRegistry` 给出）
         * @return `entries` 的 **ordinal** 数组（便于直接 `entries[i]` 取回）
         */
        fun randomPool(available: Collection<PhotoTransitionId>, sdkInt: Int): IntArray {
            val taken = HashSet<PhotoTransitionId>(available.size * 2)
            val out = ArrayList<Int>(available.size)
            for (id in available) {
                if (id.audioReactive) continue
                val eff = effective(id, sdkInt)
                if (!taken.add(eff)) continue
                out.add(id.ordinal)
            }
            return out.toIntArray()
        }

        /**
         * 按分期取池的便捷重载。
         *
         * ⚠️ 只有当「该分期已全部实现」时它才等价于 `randomPool(registry.available(), sdkInt)`。
         * 分期未完成时用另一重载（否则会抽到没有实现的转场）。
         */
        fun randomPool(phase: Phase, sdkInt: Int): IntArray =
            randomPool(implemented(phase), sdkInt)
    }
}

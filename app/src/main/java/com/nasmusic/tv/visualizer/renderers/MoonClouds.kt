package com.nasmusic.tv.visualizer.renderers

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * E44「明月」§六 —— 云场的**全部数学**（缕 / 斑 / `occl` / 五个消费点 / 云色 / 晕规格 / 过境）。
 *
 * ## ⛔ 零 Android import（这条决定本文件为什么存在）
 * [MoonOpBudget] 的先例（`MoonOpBudget.kt:8-20`）说明了同一件事：G3 的判据必须能在**纯 JVM 单测**
 * 里直接调用（播种逐字对账、`occl` 单调、把 `occl` 推到 1 断言水面到下限）。
 * 而这些判据的输入是**云场的内部状态**（缕的 `x/cy/w/h`、斑的 `px/rx/a`），⛔ 不可能从
 * 一个持有 `Paint`/`Shader` 的渲染器里取 ⇒ 唯一可行形态是**状态与绘制分家**：
 * 本文件只有数据与函数，`MoonlitRenderer` 只负责把本文件算出的几何画出来。
 *
 * ## 与原型的关系（逐字对齐的对象）
 * `docs/moonlit-preview.html:293-308`（常量表）、`:665-712`（播种）、`:720-752`（`layoutPuffs`）、
 * `:754-767`（`stepClouds`）、`:769-778`（`occlusion`）、`:1016-1024`（晕）、
 * `:1029-1043`（远层）、`:1102-1146`（近层 + 银边）、`:1310-1340`（`softEllipse` / `cloudCol`）。
 * ⚠️ **播种的 rng 调用次序是契约**（§3.0(f) 第 6 条）：改**调用次数**整场重排 ⇒
 * 与原型截图的 A/B 对照作废。次序见 [MoonCloudField.seedPlume]。
 *
 * ## 颜色量化（偏差 D23）
 * [cloudRgb] 本身是**连续**的；分桶只发生在**渲染层的着色器缓存**上
 * （[kBucket] / [backBucket] / [altBucket] 三把刻度）。⇒ 本文件的判据与量化**无关**，
 * 量化误差由 `MoonlitTest`  brute-force 扫格点钉住并登记 §十五 D23。
 */
internal object MoonClouds {

    // ══════════════════════════════════════════════════════════════════════════
    //  §6.1 数量（⚠️ 单位：缕 ≠ 斑）
    // ══════════════════════════════════════════════════════════════════════════

    /** 远层缕数 `2 / 3 / 3`。 */
    const val PLUME_FAR_LOW = 2
    const val PLUME_FAR_MED = 3
    const val PLUME_FAR_HIGH = 3

    /** 近层缕数 `2 / 3 / 4`。 */
    const val PLUME_NEAR_LOW = 2
    const val PLUME_NEAR_MED = 3
    const val PLUME_NEAR_HIGH = 4

    /** 远层每缕斑数 `4 / 5 / 6`。 */
    const val PUFF_FAR_LOW = 4
    const val PUFF_FAR_MED = 5
    const val PUFF_FAR_HIGH = 6

    /** 近层每缕斑数 `7 / 10 / 11`。 */
    const val PUFF_NEAR_LOW = 7
    const val PUFF_NEAR_MED = 10
    const val PUFF_NEAR_HIGH = 11

    /** ⭐ **播种恒用近层 HIGH 档**（11 斑）—— 切档只是截断读取，⛔ 绝不重播种（云会瞬移）。 */
    const val PUFF_SEED_COUNT = PUFF_NEAR_HIGH

    fun plumes(level: MoonLevel, near: Boolean): Int = when (level) {
        MoonLevel.LOW -> if (near) PLUME_NEAR_LOW else PLUME_FAR_LOW
        MoonLevel.MEDIUM -> if (near) PLUME_NEAR_MED else PLUME_FAR_MED
        MoonLevel.HIGH -> if (near) PLUME_NEAR_HIGH else PLUME_FAR_HIGH
    }

    /** 逐缕读取的斑数（切档不重播种 ⇒ 只改这个上界）。 */
    fun puffs(level: MoonLevel, near: Boolean): Int = when (level) {
        MoonLevel.LOW -> if (near) PUFF_NEAR_LOW else PUFF_FAR_LOW
        MoonLevel.MEDIUM -> if (near) PUFF_NEAR_MED else PUFF_FAR_MED
        MoonLevel.HIGH -> if (near) PUFF_NEAR_HIGH else PUFF_FAR_HIGH
    }

    /** 播种种子（⛔ 不是"随机"：需求 2 的判读要能复现同一场云）。 */
    const val SEED: Int = 20261008

    // ══════════════════════════════════════════════════════════════════════════
    //  时标 / 形状常量（原型 `:300-308`，⚠️ 每一个都是**调过的观感值**，不是物理量）
    // ══════════════════════════════════════════════════════════════════════════

    /** 远层角速度（`/s`）⇒ 横穿约 143 s。⚠️ 0.020→0.011→**0.0070**：所有者「还是太快」。 */
    const val SPD_FAR = 0.0070
    const val SPD_NEAR = 0.0165

    /** 缕的整体呼吸（慢，只做厚薄变化）。 */
    const val WOB = 0.10
    const val WOB_HZ = 0.055

    /** 烟的翻滚：比积云**更慢更柔**。 */
    const val BOIL = 0.20
    const val BOIL_HZ = 0.19

    /** 脊线蛇形摆动频率。 */
    const val SPINE_HZ = 0.045

    /** 沿脊的纵向漂移（⚠️ 0.075→0.045：`thickK` 抬了 1.35 倍，绝对像素会跟着涨 ⇒ 必须同时回收）。 */
    const val DRIFT_Y = 0.045
    const val DRIFT_HZ = 0.031

    /** 缕 α 的上限（0.86(积云)→0.62→**0.78**；0.62 配稀疏散布直接看不见）。 */
    const val CLOUD_A_MAX = 0.78

    /** 缕芯平均叠 2 层 ⇒ 单斑 α 由缕 α 反解（合成后 ≈ 缕 α，⛔ 不是直接拿缕 α 去画每个斑）。 */
    const val BLOB_OVERLAP = 2.0

    /** §6.2 的 60% 缓冲：包络半轴之外再留 `0.6·R` 才算"挨上了"。 */
    const val OCCL_PAD = 0.6

    /** 横站位的**外扩系数**：`cx = (x·1.3 − 0.15)·W` ⇒ 缕从屏外左侧进、右侧出，⛔ 不会"凭空出现"。 */
    const val SPAN_X = 1.3
    const val OFFSET_X = -0.15

    // ── 播种取值范围（near 在前、far 在后；⚠️ 改范围 = 改 rng 调用次数以外的东西，安全）──
    const val LENK_NEAR = 0.50
    const val LENK_NEAR_SPAN = 0.30
    const val LENK_FAR = 0.44
    const val LENK_FAR_SPAN = 0.26
    const val THICK_NEAR = 0.040
    const val THICK_NEAR_SPAN = 0.028
    const val THICK_FAR = 0.033
    const val THICK_FAR_SPAN = 0.022
    const val TILT_NEAR = 0.34
    const val TILT_FAR = 0.20
    const val WAVK_BASE = 0.30
    const val WAVK_SPAN = 0.48

    /** ⚠️ 0.70~2.00 → **0.30~0.78**：所有者「上下运动幅度太大，真实云只有很小的上下运动」。
     *  但 ⛔ 不能归零 —— 蛇形是"烟"区别于"横条"的唯一纵向内容。 */
    const val SPD_JIT_BASE = 0.80
    const val SPD_JIT_SPAN = 0.44
    const val ALPHA_NEAR_BASE = 0.52
    const val ALPHA_NEAR_SPAN = 0.30
    const val ALPHA_FAR_BASE = 0.26
    const val ALPHA_FAR_SPAN = 0.20

    /** 斑：`rK` 必须**盖住相邻间距**，否则读作"一串点"；`sq` 是横纵比（烟丝被风拽长，⛔ 不是拉丝）。 */
    const val PUFF_RK = 0.85
    const val PUFF_RK_SPAN = 0.75
    const val PUFF_SQ = 2.30
    const val PUFF_SQ_SPAN = 1.80

    /** 垂直于脊的偏移（0.55→**0.22**：散得太开就断了）。 */
    const val PUFF_OFF_SPAN = 0.22
    const val PUFF_SPD = 0.85
    const val PUFF_SPD_SPAN = 0.30
    const val PUFF_AK = 0.40
    const val PUFF_AK_SPAN = 0.60

    /** 带位（`cy = horizonY · bandY`）：近层 `0.14..0.76`、远层 `0.42..0.88`（贴地平）。 */
    const val BAND_NEAR = 0.14
    const val BAND_NEAR_SPAN = 0.62
    const val BAND_FAR = 0.42
    const val BAND_FAR_SPAN = 0.46

    // ── `layoutPuffs` 的形状常量 ──
    /** 脊的纵倾放大倍率（`tilt·u·2.2`）。 */
    const val TILT_K = 2.2

    /** 两端收尖：`taper = TAPER_MIN + TAPER_SPAN·sin(π·t^TAPER_POW)`。
     *  ⛔ 等高读作"一条带子"，收尖才读作"一缕烟"。 */
    const val TAPER_MIN = 0.26
    const val TAPER_SPAN = 0.74
    const val TAPER_POW = 0.78

    /** 斑相对脊的受光高度映射分母（`0.5 + (cy − py)/(gh·SPINE_ALT_K)`）。 */
    const val SPINE_ALT_K = 3.2

    // ══════════════════════════════════════════════════════════════════════════
    //  §6.2 occl 的共享核（⛔ 二值门，⛔ "遮了但没画"）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 缕与月盘的**平滑重叠度**（`d²`）—— [occlusion] 与层 6 的 `lit` **共用**这一个式子
     * （§6.2 的同源性：云的受光与它对月的遮挡必须来自同一个几何量，否则会出现
     * "盘已被遮暗、云却还是亮的"）。
     *
     * ⛔ 不许写成 `if (cx 在盘内)` 的二值门：E43 被提交数逼出二值门的那次自我批评
     * （`SeasideOpBudget.kt:486`）不得重演。
     */
    fun overlapD2(
        cx: Double,
        cy: Double,
        w: Double,
        h: Double,
        moonCx: Double,
        moonCy: Double,
        moonR: Double
    ): Double {
        val dx = (cx - moonCx) / (w + moonR * OCCL_PAD)
        val dy = (cy - moonCy) / (h + moonR * OCCL_PAD)
        return dx * dx + dy * dy
    }

    /** 单缕对月盘的遮挡贡献（`d² ≥ 1` ⇒ 0，平滑核 `1 − d²`）。 */
    fun plumeOccl(d2: Double, alphaK: Double): Double =
        if (d2 < 1.0) alphaK * (1.0 - d2) else 0.0

    // ══════════════════════════════════════════════════════════════════════════
    //  §6.3 五个消费点（⛔ 水面不许另算一次遮挡）
    // ══════════════════════════════════════════════════════════════════════════

    /** ① 盘面 α：`1 − 0.92·occl`。⚠️ 只由 `occl` 决定，⛔ 不再乘照度 `f`（§5.4 双重变暗）。 */
    fun diskA(occl: Double): Double = 1.0 - 0.92 * occl

    /** 晕的**未遮挡**基准：照度项 × 高度项（`altT` 越低、贴地平时晕越弱）。 */
    fun haloBase(f: Double, altT: Double): Double =
        clamp(f * 1.15 + 0.05, 0.05, 1.0) * (0.55 + 0.45 * altT)

    /** ② 晕 α：`haloBase·(1 − 0.75·occl)` —— 比盘**先**消失（云一挨边晕就软下去）。 */
    fun haloA(f: Double, altT: Double, occl: Double): Double =
        haloBase(f, altT) * (1.0 - 0.75 * occl)

    /**
     * ③ 逆光边 α：`0.35·clamp(1.6·occl,0,1)·(1−occl)` —— **只在半遮出现**。
     *
     * ⭐ 解析极大值在 `occl = 0.5` 处（`rimA = 0.14`，`G3 MoonlitTest` ⑪ 扫 1000 点钉住），
     * 分母取 **[RIM_A_FULL] = 0.175**（比解析满值高一档）⇒ [rimK] 天然留了余量，
     * ⛔ 不要为了让 `rimK` 能到 1 去改这个分母。
     */
    fun rimA(occl: Double): Double = 0.35 * clamp(occl * 1.6, 0.0, 1.0) * (1.0 - occl)

    /** 银边归一化亮度（[rimA] 的满值口径 = [RIM_A_FULL]）。 */
    fun rimK(rimA: Double): Double = clamp(rimA / RIM_A_FULL, 0.0, 1.0)

    /** [rimK] 的分母（比 [rimA] 的解析满值 0.14 高一档 ⇒ `rimK ≤ 0.8`，⛔ 不是随手取的 0.14）。 */
    const val RIM_A_FULL = 0.175

    /** ④ 倒影 α：`0.44·clamp(1.3f, 0.06, 1) · diskA` —— 与盘**同帧**变暗（需求 3 的机器形态）。 */
    fun reflA(f: Double, occl: Double): Double =
        0.44 * clamp(f * 1.3, 0.06, 1.0) * diskA(occl)

    /** ⑤ 粼光 α：`0.62·clamp(1.45f, 0.04, 1) · diskA² · (1 + 0.25·aTreb)` —— 比倒影**更陡**（平方）。 */
    fun glitA(f: Double, occl: Double, aTreb: Double): Double {
        val d = diskA(occl)
        return 0.62 * clamp(f * 1.45, 0.04, 1.0) * d * d * (1.0 + 0.25 * aTreb)
    }

    /** §5.2+ ④ 过曝芯 α（T7 起替换掉 `BLOOM_A` 那个刻意常量）。⛔ 不乘照度 `f`。 */
    fun bloomA(occl: Double): Double = clamp(0.16 + 0.20 * (1.0 - occl), 0.0, 1.0) * diskA(occl)

    /** §6.5 新月夜极淡边缘 α（T7 起替换掉 `DARK_EDGE_A`）。 */
    fun darkEdgeA(occl: Double): Double = 0.10 * (1.0 - occl)

    // ══════════════════════════════════════════════════════════════════════════
    //  §6.4 银边门槛 / 层 3-6 的图元参数
    // ══════════════════════════════════════════════════════════════════════════

    /** 银边的两道门：档 ≠ LOW（渲染层判）+ `rimK > 0.10` + 逐斑 `back > 0.34`。 */
    const val RIM_MIN_K = 0.10
    const val RIM_MIN_BACK = 0.34

    /** 银边的**几何偏移**（沿朝月方向挪 `rx·0.42`）与半轴缩比。 */
    const val RIM_OFF_K = 0.42
    const val RIM_RX_K = 0.55
    const val RIM_RY_K = 0.70

    /** 银边 α：`back·(0.10 + 0.90·rimK)·(0.60 + 0.40·rimBeat)`，钳 `[0, 0.50]`。 */
    const val RIM_A_CONST = 0.10
    const val RIM_A_K = 0.90
    const val RIM_BEAT_MIN = 0.60
    const val RIM_BEAT_SPAN = 0.40
    const val RIM_A_MAX = 0.50

    /** 远层 α = 近层斑 α × [FAR_A_K]（远层高空气薄，读作**暗带**不是"发光泡泡"）。 */
    const val FAR_A_K = 0.55

    /** 近层体 α：`p.a·(0.70 + 0.85·lit)·(1 + 1.35·back)`，钳 `[0, 0.95]`。 */
    const val NEAR_A_BASE = 0.70
    const val NEAR_A_LIT_K = 0.85
    const val NEAR_A_BACK_K = 1.35
    const val NEAR_A_MAX = 0.95

    /** 靠月光照：`0.56 + 0.44·(1 − 0.55·d²)`。⭐ **0.56 是环境光地板** —— ⛔ 不许归零，
     *  否则满屏云只在月旁才现身（离月远的云也在散射天光）。 */
    const val LIT_FLOOR = 0.56
    const val LIT_SPAN = 0.44
    const val LIT_D2_K = 0.55

    /** 色阶自变量 `k`：`clamp((lit·(0.55+0.45·altT))^1.15 · (1−back) · 0.80, 0, 1)`。 */
    const val TONE_EXP = 1.15
    const val TONE_ALT_K = 0.55
    const val TONE_ALT_SPAN = 0.45
    const val TONE_GAIN = 0.80

    /** 逆光剪影程度 `back`：边缘留 `rx·0.6` 过渡带，⛔ 硬边。 */
    const val BACK_EDGE_K = 0.6
    const val BACK_R_K = 1.15

    /** ⛔ `α < 0.004` 的图元**一个都不提交**（原型的 `op()` 不看 α ⇒ 记账不会自动回收，§3.0(f)）。 */
    const val MIN_ALPHA = 0.004

    /** [softEllipse] 的**内圈半径**（占外半径）—— "芯是否实心"的第一开关，⛔ 不是 0。 */
    const val SOFT_INNER_K = 0.10

    /** 云体（近/远同值）：`coreK = 0.20`、`coreA = 0.34` ⇒ 低拐点 = 平滑雾。 */
    const val CLOUD_CORE_K = 0.20
    const val CLOUD_CORE_A = 0.34

    /** 银边：`coreK = 0.24`、`coreA = 0.40`。 */
    const val RIM_CORE_K = 0.24
    const val RIM_CORE_A = 0.40

    /**
     * 画布侧 `createRadialGradient(0,0, rx·0.10, 0,0, rx)` 的 `coreK` 换算到
     * "0 → 圆心、1 → 外半径"参数化下的**绝对停位**：`0.10 + coreK·0.90`。
     * （与 `MoonlitRenderer.setupDisk` 里 bloom 的 `0.10 → 0.37` 同一换算。）
     */
    fun softStop(coreK: Double): Double = SOFT_INNER_K + coreK * (1.0 - SOFT_INNER_K)

    // ══════════════════════════════════════════════════════════════════════════
    //  层 3 月晕规格（⛔ 不要按 §9.1 初版面积表加层）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * `[外半径占 R, 峰值 α 占 haloA]`。
     *
     * ⚠️ 第五轮起取代 `HALO_R_K`：旧表把渐变**内圈设在盘内** ⇒ 晕的高能量区全被不透明盘面
     * 盖掉（所有者："发光层被月亮贴图完全盖住了"）。现在内圈推到 `moonR·0.96`（[HALO_INNER_K]）。
     * ⚠️ 第六轮按参考图整体提亮：峰值 α ×1.35、外层半径再拉长一档，**层数保持 1/2/3 不动**
     * ⇒ HALO 预算不变，只改强度（改层数才是 §9.2 那次越限的错路）。
     */
    val HALO_LOW: Array<MoonHaloLayer> = arrayOf(MoonHaloLayer(2.60f, 0.42f))
    val HALO_MED: Array<MoonHaloLayer> = arrayOf(MoonHaloLayer(1.62f, 0.46f), MoonHaloLayer(3.20f, 0.16f))
    val HALO_HIGH: Array<MoonHaloLayer> =
        arrayOf(MoonHaloLayer(1.50f, 0.46f), MoonHaloLayer(2.40f, 0.24f), MoonHaloLayer(4.00f, 0.11f))

    fun haloSpec(level: MoonLevel): Array<MoonHaloLayer> = when (level) {
        MoonLevel.LOW -> HALO_LOW
        MoonLevel.MEDIUM -> HALO_MED
        MoonLevel.HIGH -> HALO_HIGH
    }

    /** 晕渐变的**内圈**半径占月盘半径（紧贴月缘外侧 ⇒ 能量 100% 落在肉眼可见的环带里）。 */
    const val HALO_INNER_K = 0.96

    /**
     * 把晕的**画布口径**停位（内圈 `0.96·moonR` → 外圈 `kR·moonR`，停位 `s` 在这个区间内归一化）
     * 换算成 Compose `Brush.radialGradient` 的"0 → 圆心、1 → radius"停位。
     *
     * 换算式 `(0.96 + s·(kR − 0.96)) / kR`；`r < 首停位` 处 Compose 用首色填充 ⇒
     * 与画布"内圈以内平色"同义。
     */
    fun haloStop(s: Double, kR: Double): Double = (HALO_INNER_K + s * (kR - HALO_INNER_K)) / kR

    /** 晕的三个停位（画布口径）：`0 → 满 hot`、`0.30 → 0.92·hot 且 α×0.44`、`1 → 0`。 */
    const val HALO_S1 = 0.30
    const val HALO_K1 = 0.92
    const val HALO_A1 = 0.44
    const val HALO_K2 = 0.85

    // ══════════════════════════════════════════════════════════════════════════
    //  云色（§6.4 的两种云分开算）
    // ══════════════════════════════════════════════════════════════════════════

    /** 侧照云：暗面 → 亮面。⚠️ 第六轮随月色**改金**（旧 196,198,208 是蓝灰，配金月读作两块光源）。 */
    val CLOUD_DARK_R = 44
    val CLOUD_DARK_G = 52
    val CLOUD_DARK_B = 70
    val CLOUD_LIT_R = 206
    val CLOUD_LIT_G = 196
    val CLOUD_LIT_B = 176

    /** 剪影端（比夜空底 `#03050c` 略深一档）。 */
    val CLOUD_SIL_R = 7
    val CLOUD_SIL_G = 9
    val CLOUD_SIL_B = 15

    /** 远层云的**固定**色 `rgb(26,33,50)`（远层高空气薄 ⇒ 只比天底亮一点点的"一层纱"）。 */
    val FAR_RGB_R = 26
    val FAR_RGB_G = 33
    val FAR_RGB_B = 50

    /** 银边色 `rgb(238,228,206)`；外沿 `rgba(226,232,244,0)` 的 RGB 在 source-over 下**无意义**（α=0）。 */
    val RIM_RGB_R = 238
    val RIM_RGB_G = 228
    val RIM_RGB_B = 206

    /** 近层体渐变的外沿色 `rgba(18,24,40,0)`（同上，α=0 ⇒ 只作记录）。 */
    val CLOUD_OUT_R = 18
    val CLOUD_OUT_G = 24
    val CLOUD_OUT_B = 40

    /**
     * 云色（§6.4）—— **两种云**：
     * ① 没挡在盘前 = 被月光**侧照** ⇒ 灰白，靠月越近越亮（`k→1`）；
     * ② 挡在盘前 = **背光剪影** ⇒ 比夜空还暗（`back→1` 时往 [CLOUD_SIL_R] 走），
     *   它的"亮"只体现在**朝月的边缘**（由 [rimA] 单独画，⛔ 不许跟着 `lit` 一起变亮 ——
     *   上一版正是这里错成"盘前云更亮"，云一到月盘前就变一团白雾）。
     *
     * `adj` 三项 `[+16, +6, −24]·k·warm` 是**暖偏**：低空（`altT` 小）时 `warm` 大 ⇒ 更暖。
     */
    fun cloudRgb(k: Double, altT: Double, back: Double): Int {
        val f = clamp(k, 0.0, 1.0)
        val warm = 0.25 + 0.55 * (1.0 - clamp(altT, 0.0, 1.0))
        val bk = clamp(back, 0.0, 1.0)
        return pack(
            channel(CLOUD_DARK_R, CLOUD_LIT_R, CLOUD_SIL_R, f, bk, 16.0 * f * warm),
            channel(CLOUD_DARK_G, CLOUD_LIT_G, CLOUD_SIL_G, f, bk, 6.0 * f * warm),
            channel(CLOUD_DARK_B, CLOUD_LIT_B, CLOUD_SIL_B, f, bk, -24.0 * f * warm)
        )
    }

    private fun channel(dark: Int, lit: Int, sil: Int, f: Double, bk: Double, adj: Double): Double {
        val litv = dark + (lit - dark) * f + adj
        return clamp(litv * (1.0 - bk) + sil * bk, 0.0, 255.0)
    }

    /** 远层云体色（不含 `k`/`back`：远层只有轮廓与暗部）。 */
    fun farRgb(): Int = pack(FAR_RGB_R.toDouble(), FAR_RGB_G.toDouble(), FAR_RGB_B.toDouble())

    /** 银边色。 */
    fun rimRgb(): Int = pack(RIM_RGB_R.toDouble(), RIM_RGB_G.toDouble(), RIM_RGB_B.toDouble())

    // ── 着色器分桶（⛔ 只服务于渲染层的缓存，与本文件的数学无关）──

    /** 三把刻度的桶数：`k` 16 × `back` 16 × `altT` 4。 */
    const val K_BUCKETS = 16
    const val BACK_BUCKETS = 16
    const val ALT_BUCKETS = 4

    /** 桶总数（着色器缓存的槽位数，⛔ 实际建立数远小于此：在场斑数 ≤ 44 且缓漂）。 */
    const val CLOUD_SHADER_SLOTS = K_BUCKETS * BACK_BUCKETS * ALT_BUCKETS

    /** ⚠️ 取**下界**桶（`v·N` 截断）而不是四舍五入：桶 `i` 用的颜色就是 `i/(N−1)` 处的值。 */
    fun kBucket(k: Double): Int = (clamp(k, 0.0, 1.0) * K_BUCKETS).toInt().coerceAtMost(K_BUCKETS - 1)

    fun backBucket(back: Double): Int =
        (clamp(back, 0.0, 1.0) * BACK_BUCKETS).toInt().coerceAtMost(BACK_BUCKETS - 1)

    fun altBucket(altT: Double): Int =
        (clamp(altT, 0.0, 1.0) * ALT_BUCKETS).toInt().coerceAtMost(ALT_BUCKETS - 1)

    /**
     * 桶索引（`k` 最慢、`altT` 最快 ⇒ 与 [cloudRgbAtBucket] 的解包一致）。
     *
     * ⚠️ 索引**必须是纯算术**：它在每帧路径上（44 次/帧），⛔ 不许用字符串键（`PerfBudgetContractTest` ①）。
     */
    fun shaderIndex(k: Double, altT: Double, back: Double): Int =
        ((kBucket(k) * ALT_BUCKETS + altBucket(altT)) * BACK_BUCKETS + backBucket(back))

    /** 桶的**代表值**（解包 [shaderIndex] 的逆运算 ⇒ 缓存里的颜色就是这个函数喂给 [cloudRgb] 的三元组）。 */
    fun bucketTone(index: Int): Double = (index / (ALT_BUCKETS * BACK_BUCKETS)).toDouble() / (K_BUCKETS - 1)

    fun bucketAlt(index: Int): Double =
        ((index / BACK_BUCKETS) % ALT_BUCKETS).toDouble() / (ALT_BUCKETS - 1)

    fun bucketBack(index: Int): Double =
        (index % BACK_BUCKETS).toDouble() / (BACK_BUCKETS - 1)

    /** 桶颜色 = 用**桶代表值**重算一次 [cloudRgb]（⛔ 不是量化输出通道，误差不随值大小跳变）。 */
    fun cloudRgbAtBucket(index: Int): Int = cloudRgb(bucketTone(index), bucketAlt(index), bucketBack(index))

    // ══════════════════════════════════════════════════════════════════════════
    //  §6.6 确定性过境
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 过境缕的带位 = **月心在天空区里的相对高度**（`H` 约掉 ⇒ 与画幅无关的常量）。
     *
     * ⭐ 由 §3.1 的两个 K **推出**（⛔ 不是手抄 `0.3125`）：`0.200 / 0.640 = 0.3125`，
     * 落在近层带 `0.14..0.76` **之内** ⇒ 不是越界特例，观感上就是"一缕恰好在月的高度"。
     * ⚠️ 与 [MoonlitRenderer.MOON_CY_K] 的比值口径同 [MoonSeascape.ALT_T]（同一个构图，两处推导）。
     */
    val TRANSIT_BAND_Y: Double = MoonlitRenderer.MOON_CY_K.toDouble() / MoonlitRenderer.HORIZON_K.toDouble()

    /** 过境缕 = 近层第 0 号缕（⛔ 零新增提交、零新增填充，偏差 D16）。 */
    const val TRANSIT_INDEX = 0

    /** 测试接缝：[MoonCloudField.step] 的 `transitIndex` 传这个值 ⇒ 关掉过境钉（用于逐字几何对账）。 */
    const val TRANSIT_OFF = -1

    // ══════════════════════════════════════════════════════════════════════════
    //  逐斑光照（层 6 用，⛔ 远层不参与）
    // ══════════════════════════════════════════════════════════════════════════

    /** 缕的受光 `lit`（只吃 [overlapD2] ⇒ 与 `occl` 同源）。 */
    fun litOf(d2: Double): Double = clamp(LIT_FLOOR + LIT_SPAN * (1.0 - d2 * LIT_D2_K), 0.0, 1.0)

    /** 斑压在盘上的程度 `back`（`pd` 是斑心到月心的距离；⛔ 除以 0 时按原型的 `|| 1` 处理）。 */
    fun backOf(px: Double, py: Double, rx: Double, moonCx: Double, moonCy: Double, moonR: Double): Double =
        backFromPd(distanceToMoon(px, py, moonCx, moonCy), rx, moonR)

    /**
     * [backOf] 的「距离已算好」版本 —— 层 6 一帧要 44 次，而银边还要**同一个** `pd` 去算朝月
     * 单位向量 ⇒ ⛔ 不许把 `hypot` 算两遍（`hypot` 为防溢出要做尺度归一，是这趟循环里最贵的调用）。
     */
    fun backFromPd(pd: Double, rx: Double, moonR: Double): Double =
        clamp(1.0 - (pd - rx * BACK_EDGE_K) / (moonR * BACK_R_K), 0.0, 1.0)

    /** 斑心到月心的距离（原型的 `Math.hypot(...) || 1` ⇒ 重合时取 **1**，⛔ 不是 0）。 */
    fun distanceToMoon(px: Double, py: Double, moonCx: Double, moonCy: Double): Double {
        val pd = hypot(px - moonCx, py - moonCy)
        return if (pd == 0.0) 1.0 else pd
    }

    /** 色阶自变量 `k`（⚠️ 含 `(1 − back)` ⇒ `back→1` 时 `k→0`，剪影与亮度**不会打架**）。 */
    fun toneOf(lit: Double, altT: Double, back: Double): Double =
        clamp((lit * (TONE_ALT_K + TONE_ALT_SPAN * altT)).pow(TONE_EXP) * (1.0 - back) * TONE_GAIN, 0.0, 1.0)

    /** 近层体 α。 */
    fun nearAlpha(pA: Double, lit: Double, back: Double): Double =
        clamp(pA * (NEAR_A_BASE + NEAR_A_LIT_K * lit) * (1.0 + NEAR_A_BACK_K * back), 0.0, NEAR_A_MAX)

    /** 远层 α。 */
    fun farAlpha(pA: Double): Double = (pA * FAR_A_K).coerceIn(0.0, 1.0)

    /** 银边 α。 */
    fun rimAlpha(back: Double, rimK: Double, rimBeat: Double): Double =
        clamp(back * (RIM_A_CONST + RIM_A_K * rimK) * (RIM_BEAT_MIN + RIM_BEAT_SPAN * rimBeat), 0.0, RIM_A_MAX)

    // ══════════════════════════════════════════════════════════════════════════

    /** 截断到 8 位并封成**不透明** ARGB Int（与 [MoonSeascape] 的 `pack` 同口径，⛔ 不做加法溢出）。 */
    private fun pack(r: Double, g: Double, b: Double): Int =
        (0xFF shl 24) or
            (r.roundToInt().coerceIn(0, 255) shl 16) or
            (g.roundToInt().coerceIn(0, 255) shl 8) or
            b.roundToInt().coerceIn(0, 255)

    private fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

    /** 原型的 `wrap01`：⚠️ **负值要回绕**（`v % 1` 在 JS 里对负数返回负数），`-0.2 → 0.8`。 */
    fun wrap01(v: Double): Double {
        val x = v % 1.0
        return if (x < 0.0) x + 1.0 else x
    }

    internal const val TAU = PI * 2.0
}

/** 层 3 的一圈晕（`[外半径占 R, 峰值 α 占 haloA]`）。 */
internal class MoonHaloLayer(val kR: Float, val kA: Float)

/**
 * 一缕云里的**一个软斑**（§6.1）。
 *
 * ⛔ **不持有 Paint/Shader/Path**：本类是纯数据，全部字段可被单测直接读
 * （G3 的"几何逐字对账"就是把这里 8 个运行时字段和原型快照逐位比）。
 * ⚠️ 用普通 `class` + `var` 而不是 `data class`：`data class` 的 `copy/equals` 是每帧用不到的
 * 生成物，而这里 44×11 个实例是**构造期一次分配、终身复用**。
 */
internal class MoonPuff {
    // ── 播种值（⛔ 每帧不变）──
    /** 沿脊站位 `0..1`，定死为 `(i+0.5)/PUFF_SEED_COUNT` ⇒ 切档只是截断。 */
    var t = 0.0
    var rK = 0.0
    var sq = 0.0
    var off = 0.0
    var spd = 0.0
    var aK = 0.0
    var ph = 0.0
    var ph2 = 0.0

    // ── 运行时（每帧由 [MoonCloudField.layoutPuffs] 写）──
    var px = 0.0
    var py = 0.0
    var rx = 0.0
    var ry = 0.0
    var a = 0.0

    /**
     * ⚠️ 这个 `altT` 是**斑相对本缕脊线的高度**（受光坐标），
     * ⛔ 与 §3.3 的月空地平夹角色温 `MoonSeascape.ALT_T` **同名不同物**（原型也叫 `altT`）。
     */
    var altT = 0.0
}

/** 一缕云（plume）= 一条蛇形脊线 + 沿脊排布的软斑。 */
internal class MoonPlume(val near: Boolean) {
    // ── 播种值 ──
    var x = 0.0
    var bandY = 0.0
    var lenK = 0.0
    var thickK = 0.0
    var tilt = 0.0
    var wavK = 0.0
    var wavN = 0
    var speedK = 0.0
    var alphaK = 0.0
    var wobPhase = 0.0
    val puffs = Array(MoonClouds.PUFF_SEED_COUNT) { MoonPuff() }

    // ── 运行时 ──
    var cx = 0.0
    var cy = 0.0

    /** 半长 / 半厚（`gw/gh`）。 */
    var gw = 0.0
    var gh = 0.0

    /** ⭐ **斑椭圆的 AABB 包络半轴** ⇒ `occl` 用的几何与画出来的**完全同一份**（不会出现"遮了但没画"）。 */
    var w = 0.0
    var h = 0.0
}

/**
 * 云场（§6.1 播种 + 时基推进 + §6.2 遮挡）。
 *
 * ## ⛔ 为什么是**类**而不是 `object`
 * `object` 是进程级单例 ⇒ 两个渲染器实例（或切换效果时的新旧两帧）会**共享同一场云**，
 * 且 `onEnter` 的重播种会互相踩。本类的生命周期严格跟着 [MoonlitRenderer]（每实例一份），
 * 由 `onEnterContent` 调 [reseed]、`onExitContent` 无需释放（纯 JVM 堆、GC 管辖）。
 *
 * ## ⚠️ 与原型的全局 `clouds` / `S.tSec` 的对应
 * 原型把缕表与 `tSec` 放全局；这里 `tSec` 归本类持有（[step] 自己累加），
 * ⛔ 渲染器不再需要第二个时间变量 —— 少一个可能读错时钟的入口（G5）。
 */
internal class MoonCloudField {

    val far = Array(MoonClouds.PLUME_FAR_HIGH) { MoonPlume(near = false) }
    val near = Array(MoonClouds.PLUME_NEAR_HIGH) { MoonPlume(near = true) }

    /** 动画时基（秒）：⛔ 只由 [step] 以 `+= dtSec` 累加，与 [MoonClouds.SPD_NEAR] 一类的**速度**相乘时
     *  也必须乘在**增量**上（G5 ③′）。 */
    var tSec = 0.0
        private set

    /**
     * 播种（⛔ **调用次序就是契约**，逐字复刻 `moonlit-preview.html:665-712`）：
     * 先 3 缕远层、再 4 缕近层；每缕内部 `lenK → thickK → tilt → wavK → wavN → 11 斑 × (rK, sq, off,
     * spd, aK, ph, ph2) → x → bandY → speedK → alphaK → wobPhase`，共 87 次。
     *
     * ⚠️ 改**调用次数**（加一个字段、把 11 斑改成 10）整场重排 ⇒ 与原型截图的 A/B 对照作废
     * （§3.0(f) 第 6 条）。要动次序必须同时重跑 `docs/archive/verification/scripts/moonlit_*`
     * 并更新 `MoonlitTest` 的基准快照。
     */
    fun reseed(seed: Int = MoonClouds.SEED) {
        val rng = MoonLcg(seed)
        var i = 0
        while (i < far.size) {
            seedPlume(far[i], rng)
            i++
        }
        i = 0
        while (i < near.size) {
            seedPlume(near[i], rng)
            i++
        }
        tSec = 0.0
    }

    private fun seedPlume(b: MoonPlume, rng: MoonLcg) {
        val nearFlag = b.near
        b.lenK = (if (nearFlag) MoonClouds.LENK_NEAR else MoonClouds.LENK_FAR) +
            rng.next() * (if (nearFlag) MoonClouds.LENK_NEAR_SPAN else MoonClouds.LENK_FAR_SPAN)
        b.thickK = (if (nearFlag) MoonClouds.THICK_NEAR else MoonClouds.THICK_FAR) +
            rng.next() * (if (nearFlag) MoonClouds.THICK_NEAR_SPAN else MoonClouds.THICK_FAR_SPAN)
        b.tilt = (rng.next() * 2.0 - 1.0) * (if (nearFlag) MoonClouds.TILT_NEAR else MoonClouds.TILT_FAR)
        b.wavK = MoonClouds.WAVK_BASE + rng.next() * MoonClouds.WAVK_SPAN
        b.wavN = 1 + (rng.next() * 2.0).toInt()
        var j = 0
        while (j < MoonClouds.PUFF_SEED_COUNT) {
            val p = b.puffs[j]
            p.t = (j + 0.5) / MoonClouds.PUFF_SEED_COUNT
            p.rK = MoonClouds.PUFF_RK + rng.next() * MoonClouds.PUFF_RK_SPAN
            p.sq = MoonClouds.PUFF_SQ + rng.next() * MoonClouds.PUFF_SQ_SPAN
            p.off = (rng.next() * 2.0 - 1.0) * MoonClouds.PUFF_OFF_SPAN
            p.spd = MoonClouds.PUFF_SPD + rng.next() * MoonClouds.PUFF_SPD_SPAN
            p.aK = MoonClouds.PUFF_AK + rng.next() * MoonClouds.PUFF_AK_SPAN
            p.ph = rng.next() * MoonClouds.TAU
            p.ph2 = rng.next() * MoonClouds.TAU
            j++
        }
        b.x = rng.next()
        b.bandY = (if (nearFlag) MoonClouds.BAND_NEAR else MoonClouds.BAND_FAR) +
            rng.next() * (if (nearFlag) MoonClouds.BAND_NEAR_SPAN else MoonClouds.BAND_FAR_SPAN)
        b.speedK = (if (nearFlag) MoonClouds.SPD_NEAR else MoonClouds.SPD_FAR) *
            (MoonClouds.SPD_JIT_BASE + rng.next() * MoonClouds.SPD_JIT_SPAN)
        b.alphaK = minOf(
            MoonClouds.CLOUD_A_MAX,
            (if (nearFlag) MoonClouds.ALPHA_NEAR_BASE else MoonClouds.ALPHA_FAR_BASE) +
                rng.next() * (if (nearFlag) MoonClouds.ALPHA_NEAR_SPAN else MoonClouds.ALPHA_FAR_SPAN)
        )
        b.wobPhase = rng.next() * MoonClouds.TAU
    }

    /**
     * 推进整场云（⛔ 只用**增量累加**，⛔ 不读墙钟 —— §2.4 / G5）。
     *
     * @param spdMul §八 `mid → 云速度` 的乘子。T7 由渲染器钉 `1.0`、T9 接实数。
     * @param transitIndex 哪一缕近层被钉成过境缕（[MoonClouds.TRANSIT_INDEX]）；
     *                     传 [MoonClouds.TRANSIT_OFF] ⇒ 关掉（⛔ 只用于单测的逐字几何对账，
     *                     生产路径恒为 `TRANSIT_INDEX`）。
     *
     * ⚠️ 第四轮的"云随鼓点抽搐"根因就在第一行：旧写法 `wrap01(baseX + speedK·tSec·spdMul)` 把
     * **绝对时间**乘上**逐帧随音频变化**的系数 ⇒ 摆幅 `speedK·ΔspdMul·tSec` 越跑越大，
     * 系数一回落云就**整段倒退**。改成"速度只作用在增量上、位置靠累加"后，
     * `dtSec ≥ 0` ⇒ ⛔ 永远不会倒退。
     */
    fun step(
        dtSec: Double,
        spdMul: Double,
        w: Double,
        horizonY: Double,
        minDim: Double,
        nFar: Int,
        npFar: Int,
        nNear: Int,
        npNear: Int,
        transitIndex: Int
    ) {
        tSec += dtSec
        stepList(far, nFar, npFar, dtSec, spdMul, w, horizonY, minDim, MoonClouds.TRANSIT_OFF)
        stepList(near, nNear, npNear, dtSec, spdMul, w, horizonY, minDim, transitIndex)
    }

    private fun stepList(
        list: Array<MoonPlume>,
        n: Int,
        np: Int,
        dtSec: Double,
        spdMul: Double,
        w: Double,
        horizonY: Double,
        minDim: Double,
        transitIndex: Int
    ) {
        var i = 0
        while (i < n) {
            val b = list[i]
            b.x = MoonClouds.wrap01(b.x + b.speedK * spdMul * dtSec)
            b.cx = (b.x * MoonClouds.SPAN_X + MoonClouds.OFFSET_X) * w
            // §6.6 确定性过境：⛔ 只改这一行的 cy，⛔ 不改速度/尺寸/α ⇒ 零新增 op、零新增填充。
            // 频率闸门若要加（嫌太密）只能走**换圈判定** `if (b.x < prevX) cycle++`，
            // 在屏外（`cx = −0.15W`）切带位 ⇒ 不可见；⛔ 不许"到点强制 cx = 月左"这种瞬移。
            b.cy = horizonY *
                (if (i == transitIndex) MoonClouds.TRANSIT_BAND_Y else b.bandY)
            b.gw = b.lenK * minDim * 0.5 *
                (1.0 + MoonClouds.WOB * sin(MoonClouds.TAU * MoonClouds.WOB_HZ * tSec + b.wobPhase))
            b.gh = b.thickK * minDim
            layoutPuffs(b, np)
            i++
        }
    }

    /**
     * 沿**蛇形脊线**铺一缕烟，并回写包络半轴（`b.w/b.h` = 斑椭圆的 AABB）。
     *
     * 三条形状法则（§6.1 第五轮，⛔ 别再回到"积云配方"）：
     * ① 脊线会弯（⛔ 直线读作"一道杠"）；② 沿脊**两端收尖**（⛔ 等高读作"一条带子"）；
     * ③ 斑横长竖短（烟丝被风拽长）。
     * ⛔ 所有摆动必须是**有界正弦** —— 任何随 `tSec` 线性放大的项都会变成抽搐。
     */
    private fun layoutPuffs(b: MoonPlume, np: Int) {
        var ex = 0.0
        var ey = 0.0
        var j = 0
        while (j < np) {
            val p = b.puffs[j]
            val u = (p.t - 0.5) * 2.0
            val sy = b.cy + b.gh * (
                b.tilt * u * MoonClouds.TILT_K +
                    b.wavK * sin(
                        MoonClouds.TAU * MoonClouds.SPINE_HZ * tSec * p.spd + p.ph +
                            u * PI * b.wavN
                    ) +
                    MoonClouds.DRIFT_Y * sin(MoonClouds.TAU * MoonClouds.DRIFT_HZ * tSec + p.ph2)
                )
            p.px = b.cx + u * b.gw
            val taper = MoonClouds.TAPER_MIN + MoonClouds.TAPER_SPAN *
                sin(PI * p.t.pow(MoonClouds.TAPER_POW))
            val boil = 1.0 + MoonClouds.BOIL *
                sin(MoonClouds.TAU * MoonClouds.BOIL_HZ * tSec * p.spd + p.ph2)
            val rad = p.rK * b.gh * taper * boil
            p.rx = rad * p.sq
            p.ry = rad
            p.py = sy + p.off * b.gh * taper
            p.altT = clamp01(0.5 + (b.cy - p.py) / (b.gh * MoonClouds.SPINE_ALT_K))
            // ⛔ 不是直接拿缕 α 去画每个斑：芯平均叠 BLOB_OVERLAP 层，反解后合成值 ≈ 缕 α
            p.a = 1.0 - (1.0 - b.alphaK * p.aK).pow(1.0 / MoonClouds.BLOB_OVERLAP)
            if (kAbs(p.px - b.cx) + p.rx > ex) ex = kAbs(p.px - b.cx) + p.rx
            if (kAbs(p.py - b.cy) + p.ry > ey) ey = kAbs(p.py - b.cy) + p.ry
            j++
        }
        b.w = ex
        b.h = ey
    }

    /**
     * §6.2 云遮月的**唯一真源**（⛔ 二值门、⛔ 平均 alpha、⛔ 水面另算一次）。
     *
     * ⭐ 自然云场的实测**分布**（HIGH 档全量、`dt=0.1`、8000 帧、生产几何）：
     * `peak = 1`、`mean ≈ 0.306`、`occl > 0.5` 占 **26%**、`occl ≥ 0.999` 占 **1.7%**。
     * 口径住在 [MoonlitCloudBaseline]（由 `moonlit_cloud_golden.js` 从原型原文跑出），
     * `G3 MoonlitTest` ⑦ 逐五项对账 —— ⛔ 不要在这里再抄一份数，那正是偏差 **D24** 的成因
     * （旧值 0.53 出自一个把 `minDim` 量成 0 的探针，包络小了一整圈 ⇒ 均值低 12 倍）。
     *
     * ⇒ **自然场本身就能把盘遮满**，§6.6 的确定性过境提供的不是「能不能吞月」，
     * 而是**时长与周期性**（同口径 `occl ≥ 0.999` 占 **10%**、`mean ≈ 0.462`）。
     * ⛔ 戏剧性也不靠把核改成阶跃。
     */
    fun occlusion(moonCx: Double, moonCy: Double, moonR: Double, list: Array<MoonPlume>, n: Int): Double {
        var sum = 0.0
        var i = 0
        while (i < n) {
            val b = list[i]
            sum += MoonClouds.plumeOccl(
                MoonClouds.overlapD2(b.cx, b.cy, b.w, b.h, moonCx, moonCy, moonR),
                b.alphaK
            )
            i++
        }
        return clamp01(sum)
    }

    private fun clamp01(v: Double): Double = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v
    private fun kAbs(v: Double): Double = if (v < 0.0) -v else v
}

/**
 * 原型的 `seededRng(seed)`（`moonlit-preview.html:703`）**逐字**移植。
 *
 * `s = (imul(s, 1664525) + 1013904223) >>> 0; return s / 4294967296` ⇒
 * Kotlin 侧的 `Int` 乘加天然回绕（mod 2³²），与 `>>> 0` 的 ToUint32 同值；
 * ⚠️ 取无符号时**必须** `and 0xFFFFFFFFL` 再转 `Double`，
 * 直接 `s.toLong()` 会得到**负数**（Kotlin 的 Int 是有符号的，JS 的 `>>>` 不是）。
 */
internal class MoonLcg(seed: Int) {
    private var s = seed

    fun next(): Double {
        s = s * 1664525 + 1013904223
        return (s.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
}

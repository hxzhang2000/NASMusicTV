package com.nasmusic.tv.visualizer.renderers

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * E44「明月」§七 —— 水面的**全部数学**：地平带（§7.6）/ 光柱 glade（§7.2）/ 粼光浪脊（§7.3）。
 *
 * ## ⛔ 零 Android import（这条决定本文件为什么存在）
 * 与 [MoonClouds] / [MoonOpBudget] 同一条理由：**门禁必须能直接调用产生该量的几何**。
 * G4 的 `GLITTER` 行要的是"最坏帧有多少条划、它们的包围盒铺了几屏"，而这两个数
 * 只能从 `fy / env / hh / len / off / α` 这条链上算出来 ⇒ 链条一旦长在持有 `Paint`/`Shader`
 * 的渲染器里，单测就只能**抄数**（E44 已经为抄数付过两次学费：D10、D24）。
 * 本文件只有数据与函数，[MoonlitRenderer] 只负责把这里算出的几何画出来。
 *
 * ## 与原型的关系（逐字对齐的对象）
 * `docs/moonlit-preview.html:1153-1160`（地平带）、`:1183-1207`（光柱）、
 * `:1221-1273`（粼光双层循环）、`:354-379`（`hash1` / `noise1` / `fbm1`）、
 * `:926-933`（`hotOf` / `whiteOf` 色尺，落在 [MoonDiskBake]）。
 * ⛔ [hash1] 的三个魔数与移位次序**必须逐位一致** —— 换一版哈希 = 换一整场粼光的站位，
 * 与原型截图的 A/B 对照当场作废。
 *
 * ## 单位口径
 * 原型画布是**设备像素**（`resize()` 里 `W = clientWidth × DPR`、⛔ 没有 `ctx.scale(DPR)`），
 * 而 Compose 的 `DrawScope` 同样画在设备像素上 ⇒ 这里的长度量一律"输入什么单位就出什么单位"，
 * ⛔ 不要乘 density。唯一的例外是划厚地板 `1.2 × DPR`：它本来就是**设备像素**的整数下限，
 * 由调用方以 [MoonGlitterFrame.begin] 的 `dpr` 传入（纯文件因此仍零 Android import）。
 */
internal object MoonWater {

    // ══════════════════════════════════════════════════════════════════════════
    //  §7.6 地平带（一次纵向渐变 `drawRect`，⛔ 通铺全宽）
    // ══════════════════════════════════════════════════════════════════════════

    /** 带上沿高出地平线 `0.10·h`、下沿低出 `0.06·h` ⇒ 带高恒为 `0.16·h`。 */
    const val BAND_ABOVE_K = 0.10
    const val BAND_BELOW_K = 0.06

    /** 两个色标在带内的位置（`0` 与 `1` 都是全透明 ⇒ 带两头软着陆，不留硬边）。 */
    const val BAND_STOP_DARK = 0.55
    const val BAND_STOP_LIT = 0.72

    /** 压暗标 `rgba(2,4,9, 0.30 + 0.22·(1−altT))` —— 近地平处大气最厚，所以贴地平时压得最狠。 */
    const val BAND_DARK_A_BASE = 0.30
    const val BAND_DARK_A_SPAN = 0.22
    const val BAND_DARK_R = 2
    const val BAND_DARK_G = 4
    const val BAND_DARK_B = 9

    /** 月色标：`hotOf(0.8)` 配 `α = 0.05·(1−occl)` —— 全带上**唯一**随云遮动的量。 */
    const val BAND_LIT_K = 0.8f
    const val BAND_LIT_A = 0.05

    /**
     * 月色标的 α **量化桶数**（渲染层的着色器缓存键，§6.5/D23 同一套路）。
     *
     * ⚠️ 只有这一支会变 ⇒ 整条渐变可以按桶缓存：压暗标的 α 吃 `altT`，而 `altT` 由**固定构图**
     * （[MoonSeascape.ALT_T]）决定、逐帧恒等；两头是全透明。
     * 桶数 16 ⇒ 最大 α 误差 `BAND_LIT_A / (2·buckets) = 0.05 / 32 = 0.0015625`
     * （判据见 `MoonlitWaterTest` ⑥b：解析式与 1001 点扫描**并**钉，⛔ 不拿扫描值当解析上界 ——
     * 网格碰不到桶边界，扫描值 0.0015375 比真上界小）。
     */
    const val BAND_OCCL_BUCKETS = 16

    /** 压暗标 α（`altT` 定稿为常量 ⇒ 本函数逐帧同值，但 ⛔ 不许在渲染侧写死 0.369）。 */
    fun bandDarkA(altT: Double): Double = BAND_DARK_A_BASE + BAND_DARK_A_SPAN * (1.0 - altT)

    /** 月色标 α。 */
    fun bandLitA(occl: Double): Double = BAND_LIT_A * (1.0 - occl)

    /** 按桶**中心**还原的月色 α（着色器缓存里真正存的那个数）。 */
    fun bandLitABucket(bucket: Int): Double =
        bandLitA((bucket.coerceIn(0, BAND_OCCL_BUCKETS - 1) + 0.5) / BAND_OCCL_BUCKETS)

    /** 云遮 → 桶号（与 [MoonClouds.kBucket] 同口径：先夹再整除，⛔ 不四舍五入）。 */
    fun bandOcclBucket(occl: Double): Int =
        (clamp01(occl) * BAND_OCCL_BUCKETS).toInt().coerceAtMost(BAND_OCCL_BUCKETS - 1)

    /** 压暗标颜色（不透明 `ARGB_8888`，α 由着色器单独给）。 */
    fun bandDarkRgb(): Int = (0xFF shl 24) or (BAND_DARK_R shl 16) or (BAND_DARK_G shl 8) or BAND_DARK_B

    /** 月色标颜色 = §7.2/§5.2 那把尺上的 `hotOf(0.8)`（⛔ 不在这里再抄一遍 moonHsl）。 */
    fun bandLitRgb(): Int = MoonDiskBake.hotOf(BAND_LIT_K)

    // ══════════════════════════════════════════════════════════════════════════
    //  §7.2 光柱（glade）—— 柱头 + 1~3 颗**竖长**底光带椭圆
    // ══════════════════════════════════════════════════════════════════════════

    /** 柱头：贴地平线的压扁亮斑，整根柱子上最亮处（参考图复量：地平线处全宽 ≈0.79·R）。 */
    const val HEAD_CY_K = 0.030
    const val HEAD_RX_K = 0.50
    const val HEAD_RY_K = 0.055
    const val HEAD_A_K = 2.10
    const val HEAD_A_MAX = 0.95
    const val HEAD_TONE_T = 0.22
    const val HEAD_CORE_K = 0.40
    const val HEAD_CORE_A = 0.88

    /** 底光带颗数 `1 / 2 / 3`。⛔ 不是"越多越好"：初版 5 颗扁椭圆吃掉 0.52 屏（§7.2 第六轮四次）。 */
    const val FOG_LOW = 1
    const val FOG_MED = 2
    const val FOG_HIGH = 3

    /**
     * 底光带三档 `[纵向中心·seaH, 半宽·moonR, α 标度]`（原型 `:1199` 的 `FOG` 表）。
     *
     * ⚠️ **顺序是 `cy, rxK, aK`**，不是"由大到小"：`[0.30, 0.85, 1.55]` 读作"贴地平线、最窄、最亮"。
     * 抄错过一次就会把最亮的那颗扔到海面下缘去。
     */
    private val FOG_CY_K = doubleArrayOf(0.30, 0.72, 0.98)
    private val FOG_RX_K = doubleArrayOf(0.85, 1.70, 2.30)
    private val FOG_A_K = doubleArrayOf(1.55, 1.10, 0.75)

    /** 竖长椭圆的半高：第一颗 `0.42·seaH`（一颗就纵向盖满光带），其余 `0.40·seaH`。 */
    const val FOG_RY_FIRST = 0.42
    const val FOG_RY_REST = 0.40

    /** 底光带的两处慢摆：α 抖 `0.85 + 0.30·noise1`，横移 `±0.15·moonR`（原型 `:1203-1204`）。 */
    const val FOG_A_BASE = 0.85
    const val FOG_A_SPAN = 0.30
    const val FOG_A_HZ = 0.30
    const val FOG_A_STEP = 3.7
    const val FOG_X_HZ = 0.35
    const val FOG_X_STEP = 2.1
    const val FOG_X_K = 0.30

    /** 底光带色 / 芯（比柱头**更白更软**：芯 0.10 处就掉到 0.55，读作雾气而不是灯）。 */
    const val FOG_TONE_T = 0.10
    const val FOG_CORE_K = 0.10
    const val FOG_CORE_A = 0.55

    /** 档 ⇒ 底光带颗数。 */
    fun fogCount(level: MoonLevel): Int = when (level) {
        MoonLevel.LOW -> FOG_LOW
        MoonLevel.MEDIUM -> FOG_MED
        MoonLevel.HIGH -> FOG_HIGH
    }

    /**
     * 光柱整段的存在门（原型 `:1183`）：`reflA > 0.004` **且** `tSec > 0`。
     *
     * ⚠️ 第二个条件不是防负数：`tSec = 0` 时 `noise1(i·3.7)` 三颗**同相位** ⇒ 底光带会叠成
     * 一摞完全对齐的透镜（原型注释点名的"横向接缝"）。
     */
    fun gladeVisible(reflA: Double, tSec: Double): Boolean =
        reflA > MoonClouds.MIN_ALPHA && tSec > 0.0

    /** 柱头 α：`clamp(reflA·2.10·lumK, 0, 0.95)`。 */
    fun headAlpha(reflA: Double, lumK: Double): Double =
        (reflA * HEAD_A_K * lumK).coerceAtMost(HEAD_A_MAX)

    fun headRx(moonR: Double): Double = moonR * HEAD_RX_K
    fun headRy(seaH: Double): Double = seaH * HEAD_RY_K
    fun headCy(horizonY: Double, seaH: Double): Double = horizonY + seaH * HEAD_CY_K

    fun fogRxK(i: Int): Double = FOG_RX_K[i.coerceIn(0, FOG_CY_K.size - 1)]
    fun fogRyK(i: Int): Double = if (i == 0) FOG_RY_FIRST else FOG_RY_REST
    fun fogCy(horizonY: Double, seaH: Double, i: Int): Double =
        horizonY + seaH * FOG_CY_K[i.coerceIn(0, FOG_CY_K.size - 1)]
    fun fogRx(moonR: Double, i: Int): Double = moonR * fogRxK(i)
    fun fogRy(seaH: Double, i: Int): Double = seaH * fogRyK(i)

    /** 底光带 α（含逐颗独立的慢呼吸）。 */
    fun fogAlpha(reflA: Double, lumK: Double, i: Int, tSec: Double): Double {
        val aK = FOG_A_K[i.coerceIn(0, FOG_CY_K.size - 1)]
        return clamp01(
            reflA * lumK * aK * (FOG_A_BASE + FOG_A_SPAN * noise1(i * FOG_A_STEP + tSec * FOG_A_HZ))
        )
    }

    /** 底光带横移（以月柱中轴为准，⛔ 不是以屏心）。 */
    fun fogCx(moonCx: Double, moonR: Double, i: Int, tSec: Double): Double =
        moonCx + (noise1(i * FOG_X_STEP + tSec * FOG_X_HZ) - 0.5) * moonR * FOG_X_K

    // ══════════════════════════════════════════════════════════════════════════
    //  §7.3 粼光浪脊（ROWS × 按深度分档的 per）
    // ══════════════════════════════════════════════════════════════════════════

    /** 行数 `24 / 46 / 60`。 */
    const val ROWS_LOW = 24
    const val ROWS_MED = 46
    const val ROWS_HIGH = 60

    /** 近景每行的划数 `3 / 4 / 5`（远端见 [perOf]）。 */
    const val PER_LOW = 3
    const val PER_MED = 4
    const val PER_HIGH = 5

    /** 透视指数（原型 `:1226`，⚠️ 2.30→**1.70**：2.30 把行几乎全堆到地平线附近 ⇒ 画面下缘是黑的）。 */
    const val PERSP_K = 1.70

    /** 光柱底宽系数（`env = GLITTER_W_K·minDim·(0.42 + 0.58·fy)`，⚠️ 0.22→0.42 是远端地板）。 */
    const val GLITTER_W_K = 0.275
    const val ENV_FLOOR = 0.42
    const val ENV_SPAN = 0.58

    /** 划厚 `env·(0.018 + 0.050·fy²)`，⛔ 但不得小于 `1.2 × DPR` 的设备像素地板。 */
    const val HH_K0 = 0.018
    const val HH_K2 = 0.050
    const val HH_FLOOR_PX = 1.2

    /** 整行纵向错开 `±0.275·Δy`（⛔ 行与行严丝合缝对齐正是"梯子"的成因）。 */
    const val JIT_ROW_K = 0.55

    /** 每行条数的两个深度分界（`fy < 0.28 → 1`、`fy < 0.62 → 2`、否则近景档 [PER_LOW]/[PER_MED]/[PER_HIGH]）。 */
    const val PER_T_DEEP = 0.62
    const val PER_T_FAR = 0.28

    /** 划长 `env·(0.14 + 1.35·fbm1)`：0.14 是"发丝"、1.49 的上限是"长波"，两种混在同一行里。 */
    const val LEN_MIN = 0.14
    const val LEN_SPAN = 1.35
    const val LEN_T_HZ = 0.8
    const val LEN_SEED_SCALE = 0.37

    /** 站位 / 外沿 / 摆幅 / 跌落（原型 `:1251-1261`；`*_SCALE` / `*_OFF` 直接进 [hash1] ⇒ 取 Double）。 */
    const val LANE_SCALE = 31.0
    const val LANE_OFF = 5.0
    const val WIDE_MIN = 0.30
    const val WIDE_SPAN = 1.05
    const val WIDE_GAMMA = 1.4
    const val WIDE_SCALE = 41.0
    const val WIDE_OFF = 9.0
    const val WOB_HZ = 1.35
    const val WOB_SCALE = 1.77
    const val WOB_K = 0.35
    const val OFF_K = 0.95
    const val FALL_K = 0.55
    const val FALL_DEN = 0.90

    /** 划的 α：`glitA·(0.55+0.45·hash)·(0.40+0.85·fy)·6.0·fall`，再**外面**乘 `lumK`（原型 `:1264`）。 */
    const val A_SCALAR = 6.0
    const val A_HASH_BASE = 0.55
    const val A_HASH_SPAN = 0.45
    const val A_FY_BASE = 0.40
    const val A_FY_SPAN = 0.85

    /** 逐条纵向抖动 `±0.6·hh`（与整行错开叠加，⛔ 只留行错开会读成一排整齐的横线）。 */
    const val JIT_Y_STEP = 17.0
    const val JIT_Y_OFF = 11.0
    const val JIT_Y_K = 1.2

    /** 种子式 `i·31 + k·7 + 3`（⛔ 改系数 = 换一整场粼光的随机源，与原型截图对照作废）。 */
    const val SEED_I = 31
    const val SEED_K = 7
    const val SEED_BASE = 3

    /** 光柱中轴的整体摆动：慢摆 `0.006·minDim` + 低音偏移 `0.004·minDim`（`aBass` 中性 = 0.4）。 */
    const val SWAY_HZ = 0.055
    const val SWAY_K = 0.006
    const val SWAY_BASS_K = 0.004
    const val SWAY_BASS_NEUTRAL = 0.4

    /** 划色与芯（镜面提白 `whiteOf(0.35)`，⛔ 不是月盘色 —— 盘色亮度上限 lum≈192，α 再大也顶不动）。 */
    const val GLIT_TONE_T = 0.35
    const val GLIT_CORE_K = 0.66
    const val GLIT_CORE_A = 0.95

    fun rowsOf(level: MoonLevel): Int = when (level) {
        MoonLevel.LOW -> ROWS_LOW
        MoonLevel.MEDIUM -> ROWS_MED
        MoonLevel.HIGH -> ROWS_HIGH
    }

    fun perOfLevel(level: MoonLevel): Int = when (level) {
        MoonLevel.LOW -> PER_LOW
        MoonLevel.MEDIUM -> PER_MED
        MoonLevel.HIGH -> PER_HIGH
    }

    /** 第 `i` 行的深度参数 `fy ∈ (0,1)`（靠地平线的行更密）。 */
    fun fyOf(i: Int, rows: Int): Double = ((i + 0.5) / rows).pow(PERSP_K)

    /** 本行与下一行的行距（⛔ 不是 `seaH/rows`：透视把行距也拉成了函数）。 */
    fun dyRowOf(i: Int, rows: Int, seaH: Double): Double =
        seaH * PERSP_K * ((i + 0.5) / rows).pow(PERSP_K - 1.0) / rows

    /** 该行处光柱的半宽包络（越近越宽）。 */
    fun envOf(fy: Double, minDim: Double): Double =
        GLITTER_W_K * minDim * (ENV_FLOOR + ENV_SPAN * fy)

    /** 划厚（含设备像素地板，⛔ 与行距是两条独立的红线，见 [hhToDyRatio]）。 */
    fun hhOf(env: Double, fy: Double, dpr: Double): Double =
        maxOf(HH_FLOOR_PX * dpr, env * (HH_K0 + HH_K2 * fy * fy))

    /** 每行条数（按深度分档）。 */
    fun perOf(fy: Double, perNear: Int): Int = when {
        fy < PER_T_FAR -> 1
        fy < PER_T_DEEP -> 2
        else -> perNear
    }

    /**
     * 一帧的**结构条数**（`Σ per_i`，先于 `α` 早退）⇒ [MoonOpItem.GLITTER] 的 `ops*` 就取自这里。
     *
     * ⭐ 实测最坏帧（`f=1`、`occl=0`）没有任何一条划被 `α < 0.004` 剔掉，
     * 所以这个数与"在场条数"在那一帧**重合**（`MoonlitWaterTest` 钉这一点）。
     */
    fun structuralStreaks(level: MoonLevel): Int {
        val rows = rowsOf(level)
        val perNear = perOfLevel(level)
        var n = 0
        for (i in 0 until rows) n += perOf(fyOf(i, rows), perNear)
        return n
    }

    /** 整根光柱中轴的横向摆动（叠加在每条划自己的 `off` 之上）。 */
    fun sway(tSec: Double, minDim: Double, aBass: Double): Double =
        sin(2.0 * PI * SWAY_HZ * tSec) * minDim * SWAY_K +
            (aBass - SWAY_BASS_NEUTRAL) * minDim * SWAY_BASS_K

    /** `hh / Δy` —— 划厚与行距之比（原型立的"不糊成一坨 / 不露黑底"不变式，`≈1.2` 为定稿口径）。 */
    fun hhToDyRatio(i: Int, rows: Int, seaH: Double, minDim: Double, dpr: Double): Double {
        val fy = fyOf(i, rows)
        return hhOf(envOf(fy, minDim), fy, dpr) / dyRowOf(i, rows, seaH)
    }

    private fun clamp01(v: Double): Double = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v

    // ══════════════════════════════════════════════════════════════════════════
    //  §7.2 的哈希三件套（原型 `:354-379`，逐位移植）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 哈希的三个魔数（原型 `:355-356`）。
     *
     * ⚠️ 必须写成 `0x….toInt()`：这三个字面量都大于 `Int.MAX_VALUE`，Kotlin 对十六进制字面量一律
     * 推断成 `Long`，**即使期望类型是 `Int` 也不会自动收窄**（实测报
     * `Initializer type mismatch: expected 'Int', actual 'Long'`）。`.toInt()` 取的就是低 32 位补码
     * （`0x9E3779B9` → `-1640531527`），与 JS `Math.imul` 的 32 位语义一致。
     * ⛔ 不要改成 `Long` 运算 —— 乘法不再自然回绕，与原型逐位对账当场作废。
     */
    private const val HASH_XOR: Int = 0x9E3779B9.toInt()
    private const val HASH_MUL_A: Int = 0x85EBCA77.toInt()
    private const val HASH_MUL_B: Int = 0xC2B2AE3D.toInt()

    /**
     * 整数哈希（返回 `0..1`）。
     *
     * ⚠️ 原型的 `x | 0` 是**向零截断**，与 [Double.toInt] 同语义；输入一律 `|x| < 2³¹`
     * （最大用到 `seed·41+9 ≈ 7.6×10⁴`），所以不需要 JS 的 mod 2³² 回绕。
     * ⛔ 异或必须写成 `xor`：`|` 在 JS 里优先级**高于** `^`，Kotlin 侧一旦手写成 `or`
     * 就会把常量里为 1 的那些位**或**进去 ⇒ 不同 `x` 撞进同一值（星野实测长出规则点阵）。
     */
    fun hash1(x: Double): Double {
        var h = (x.toInt() xor HASH_XOR) * HASH_MUL_A
        h = h xor (h ushr 13)
        h *= HASH_MUL_B
        h = h xor (h ushr 16)
        return (h.toLong() and 0xFFFFFFFFL) / 4294967296.0
    }

    /** 一维值噪声（smoothstep 插值，⛔ 不是线性 —— 线性插值会在每条划的时长上留下折角）。 */
    fun noise1(x: Double): Double {
        val i = floor(x)
        val f = x - i
        val a = hash1(i)
        val b = hash1(i + 1.0)
        val u = f * f * (3.0 - 2.0 * f)
        return a + (b - a) * u
    }

    /** 3 阶 fbm，返回 `0..1`（归一 `1 + 0.5 + 0.25 = 1.75` ⇒ 权重 `0.5 / 1.75` 等）。 */
    fun fbm1(x: Double, seed: Double): Double {
        var s = 0.0
        var amp = 0.5
        var norm = 0.0
        var o = 0
        while (o < 3) {
            s += amp * noise1(x * (1 shl o) + seed * 7.3 + o * 19.7)
            norm += amp
            amp *= 0.5
            o++
        }
        return s / norm
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  粼光的逐行 / 逐条求值（**复用持有者**，⛔ 不逐帧分配）
    // ══════════════════════════════════════════════════════════════════════════

    /** 一行的派生量（`fy` 只算一次，行内每条划共用）。 */
    internal class MoonGlitterRow {
        var fy = 0.0
        var y = 0.0
        var env = 0.0
        var hh = 0.0
        var jitRow = 0.0
        var per = 0
    }

    /** 一条划的图元参数（`rx/ry` 已是**半轴**，直接喂 [MoonlitRenderer] 的软椭圆）。 */
    internal class MoonGlitterStreak {
        var cx = 0.0
        var cy = 0.0
        var rx = 0.0
        var ry = 0.0
        var alpha = 0.0
        var len = 0.0
        var hh = 0.0
    }

    /**
     * 一帧粼光的**输入快照** + 两个复用持有者。
     *
     * 粼光是**无状态**的（全部由 `i/k/tSec` 现算）⇒ 本类不保存历史，切档、重播、
     * 单测里任意时刻重放同一 `(参数, tSec)` 都得同一场划。
     *
     * ⚠️ 外层成员**一律写 `MoonWater.` 限定**：object 的**嵌套类**拿不到外层 object 的隐式接收者，
     * 裸写 `fyOf(...)` 直接编译不过（[MoonCloudField] 之所以能裸写，是因为它用的是**参数注入**）。
     */
    internal class MoonGlitterFrame {
        var tSec = 0.0
        var minDim = 0.0
        var seaH = 0.0
        var horizonY = 0.0
        var moonCx = 0.0
        var glitA = 0.0
        var lumK = 1.0
        var dpr = 1.0
        var sway = 0.0
        var rows = 0
        var perNear = 0

        val row = MoonGlitterRow()
        val streak = MoonGlitterStreak()

        /** [row] 当前装的是第几行（`-1` = 未装）—— 见 [rowAt] 的复用说明。 */
        private var rowReady = -1

        /** 每帧一次：只写字段，⛔ 不分配。`aBass` 的中性值是 0.4（T9 才接真音频）。 */
        fun begin(
            level: MoonLevel,
            tSec: Double,
            minDim: Double,
            horizonY: Double,
            seaH: Double,
            moonCx: Double,
            glitA: Double,
            lumK: Double,
            dpr: Double,
            aBass: Double,
        ) {
            this.tSec = tSec
            this.minDim = minDim
            this.horizonY = horizonY
            this.seaH = seaH
            this.moonCx = moonCx
            this.glitA = glitA
            this.lumK = lumK
            this.dpr = dpr
            rows = MoonWater.rowsOf(level)
            perNear = MoonWater.perOfLevel(level)
            sway = MoonWater.sway(tSec, minDim, aBass)
            // 行缓存作废：上一帧的 `seaH / minDim / dpr` 可能已经变了（切画幅 / 切档）
            rowReady = -1
        }

        /**
         * 第 `i` 行的派生量（返回**同一个** [row] 实例）。
         *
         * ⚠️ 带**单行缓存**：[streakAt] 每条划都要读行量，而绘制与记账都是「先取一次行、再逐条取划」
         * ⇒ 不缓存会把 `fy / env / hh / Δy / jitRow` 重算 `per` 遍（HIGH 档 137 条 vs 60 行）。
         * 缓存安全的理由：行量只依赖 [begin] 写进的那几个字段，一帧之内不变 ⇒ 键只需行号。
         */
        fun rowAt(i: Int): MoonGlitterRow {
            if (rowReady == i) return row
            rowReady = i
            val fy = MoonWater.fyOf(i, rows)
            row.fy = fy
            row.y = horizonY + seaH * fy
            row.env = MoonWater.envOf(fy, minDim)
            row.hh = MoonWater.hhOf(row.env, fy, dpr)
            row.jitRow = (MoonWater.hash1(i * 13.0 + 7.0) - 0.5) *
                MoonWater.dyRowOf(i, rows, seaH) * MoonWater.JIT_ROW_K
            row.per = MoonWater.perOf(fy, perNear)
            return row
        }

        /** 第 `i` 行第 `k` 条划（返回**同一个** [streak] 实例）。 */
        fun streakAt(i: Int, k: Int): MoonGlitterStreak {
            val r = rowAt(i)
            val fy = r.fy
            val seed = (i * MoonWater.SEED_I + k * MoonWater.SEED_K + MoonWater.SEED_BASE).toDouble()
            val len = r.env * (MoonWater.LEN_MIN + MoonWater.LEN_SPAN *
                MoonWater.fbm1(seed * MoonWater.LEN_SEED_SCALE + tSec * MoonWater.LEN_T_HZ, 5.0))
            val lane = MoonWater.hash1(seed * MoonWater.LANE_SCALE + MoonWater.LANE_OFF) * 2.0 - 1.0
            val wide = MoonWater.WIDE_MIN + MoonWater.WIDE_SPAN *
                MoonWater.hash1(seed * MoonWater.WIDE_SCALE + MoonWater.WIDE_OFF)
                    .pow(MoonWater.WIDE_GAMMA)
            val wob = MoonWater.noise1(seed * MoonWater.WOB_SCALE + tSec * MoonWater.WOB_HZ) - 0.5
            val off = (lane * wide + wob * MoonWater.WOB_K) * r.env * MoonWater.OFF_K
            val fall = exp(-MoonWater.FALL_K * (off / (r.env * MoonWater.FALL_DEN)).pow(2.0))
            val a = (
                glitA *
                    (MoonWater.A_HASH_BASE + MoonWater.A_HASH_SPAN * MoonWater.hash1(seed * 7.0 + 3.0)) *
                    (MoonWater.A_FY_BASE + MoonWater.A_FY_SPAN * fy) * MoonWater.A_SCALAR * fall
                ).coerceIn(0.0, 1.0) * lumK
            streak.cx = moonCx + sway + off
            streak.cy = r.y + r.jitRow +
                (MoonWater.hash1(seed * MoonWater.JIT_Y_STEP + MoonWater.JIT_Y_OFF) - 0.5) *
                r.hh * MoonWater.JIT_Y_K
            streak.rx = len / 2.0
            streak.ry = r.hh / 2.0
            streak.alpha = a
            streak.len = len
            streak.hh = r.hh
            return streak
        }

        /**
         * 本帧在场划的 `len·hh` **包围盒**合计占几屏（⛔ 渲染器不调用，这是 G4 的**生产几何**口径）。
         *
         * 与 [MoonOpItem.GLITTER] 的 `fill*` 同一把尺：只统计过了 `α ≥ 0.004` 的划，
         * 分母是 `screenW × screenH` ⇒ 结果与**画幅尺度**无关。
         * ⚠️ 但与 **`dpr` 有关**：`1.2·dpr` 地板在 `dpr > 0.00173·minDim` 时触发（小画幅高密度屏），
         * 实测矩阵与处置见 [MoonOpItem.GLITTER] 的 KDoc（偏差 **D29**）。
         */
        fun fillFraction(screenW: Double, screenH: Double): Double {
            var sum = 0.0
            for (i in 0 until rows) {
                val r = rowAt(i)
                for (k in 0 until r.per) {
                    val s = streakAt(i, k)
                    if (s.alpha < MoonClouds.MIN_ALPHA) continue
                    sum += s.len * s.hh
                }
            }
            return sum / (screenW * screenH)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  §7.5 节拍涟漪（`RIPPLE`，图元是 `softRing` 而不是描边椭圆）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 环数上界 `LOW 0 / MED 2 / HIGH 4`（§9.1 第 13 行）。
     *
     * ⚠️ **MED 是 2，不是初判的 `≤1`**（偏差 **D17**：`passcost` 半遮态实测到同帧 2 环）。
     * `FxLevel.OFF` ⇒ LOW 整段跳过，本效果里 [MoonLevel.LOW] 与之同源。
     */
    const val RIPPLE_MAX_LOW = 0
    const val RIPPLE_MAX_MEDIUM = 2
    const val RIPPLE_MAX_HIGH = 4

    /** 环的寿命（秒）：`p = t / 1.8` 到 1 结束。⚠️ `t` 只累加 `dtSec × sp`，⛔ 不用墙钟（G5）。 */
    const val RIPPLE_LIFE_SEC = 1.8

    /** 生成位的纵向散布：`y = horizonY + seaH·(0.30..0.75)`（⛔ 不许贴到光柱下缘以外）。 */
    const val RIPPLE_Y_LO_K = 0.30
    const val RIPPLE_Y_SPAN_K = 0.45

    /** 每环自己的速度 `sp = 0.55..1.05` ⇒ 同场几环**不会同步呼吸**。 */
    const val RIPPLE_SP_LO = 0.55
    const val RIPPLE_SP_SPAN = 0.50

    /** 半径展开：`rr = moonR·lerp(0.1, 0.9, p)`（§八"半径 = 0.1R..0.9R 展开"）。 */
    const val RIPPLE_R0_K = 0.1
    const val RIPPLE_R1_K = 0.9

    /** 软环图元的长短半轴系数（相对 `rr`）：`rx = 1.6·rr`、`ry = 0.40·rr`。 */
    const val RIPPLE_RX_K = 1.6
    const val RIPPLE_RY_K = 0.40

    /**
     * α 上限 = **0.075**，⛔ 不要"顺手调亮"（§7.5 第二条）。
     *
     * 这条环实测**正好贴在倒影下缘**：一亮就读作"盘子的投影" ⇒ 直接坐实"水面上浮着一块石头"
     * （也就是 §7.2 那个被第六轮整段推翻的旧模型）。还要再乘 `diskA × glitA` ⇒ 遮月时一起退场。
     */
    const val RIPPLE_A_MAX = 0.075

    /** 软环的两个停位：芯到 `IN_K` 全程透明（⛔ 不是实心斑），峰在 `PK_K`，外缘回 0。 */
    const val RIPPLE_IN_K = 0.58
    const val RIPPLE_PK_K = 0.86

    /** 环色 `rgb(214,206,180)` —— 比月色冷一档的灰米（原型 `:1289` 逐字）。 */
    const val RIPPLE_R = 214
    const val RIPPLE_G = 206
    const val RIPPLE_B = 180

    /** 环色的 ARGB 整值（⛔ 渲染层不重抄 `0xFFD6CEB4`：与 [bandDarkRgb] 同一条色尺纪律）。 */
    fun rippleRgb(): Int = (0xFF shl 24) or (RIPPLE_R shl 16) or (RIPPLE_G shl 8) or RIPPLE_B

    /** 档 ⇒ 环数上界。 */
    fun rippleMax(level: MoonLevel): Int = when (level) {
        MoonLevel.LOW -> RIPPLE_MAX_LOW
        MoonLevel.MEDIUM -> RIPPLE_MAX_MEDIUM
        MoonLevel.HIGH -> RIPPLE_MAX_HIGH
    }

    /** 寿命比 `p`（`≥ 1` 即由调用方回收）。 */
    fun rippleP(tSec: Double): Double = tSec / RIPPLE_LIFE_SEC

    /** 生成位（`u` 是 0..1 的随机量，由渲染器的 `rng` 给，⛔ 不在纯函数里取随机）。 */
    fun rippleY(horizonY: Double, seaH: Double, u: Double): Double =
        horizonY + seaH * (RIPPLE_Y_LO_K + RIPPLE_Y_SPAN_K * clamp01(u))

    /** 每环速度（`u` 同上）。 */
    fun rippleSp(u: Double): Double = RIPPLE_SP_LO + RIPPLE_SP_SPAN * clamp01(u)

    /** 当前半径 `rr`。 */
    fun rippleR(moonR: Double, p: Double): Double =
        moonR * (RIPPLE_R0_K + (RIPPLE_R1_K - RIPPLE_R0_K) * clamp01(p))

    fun rippleRx(rr: Double): Double = rr * RIPPLE_RX_K
    fun rippleRy(rr: Double): Double = rr * RIPPLE_RY_K

    /** α = `clamp(0.075·(1 − p)·diskA·glitA, 0, 1)`（与粼光**同源**：`glitA` 由调用方算一次传进来）。 */
    fun rippleAlpha(p: Double, diskA: Double, glitA: Double): Double =
        (RIPPLE_A_MAX * (1.0 - clamp01(p)) * diskA * glitA).coerceIn(0.0, 1.0)

    /**
     * **还在画**的最大寿命比 —— 环的几何上界不是 `p → 1`，而是 [MoonClouds.MIN_ALPHA] 早退那一点。
     *
     * ⭐ 反解：`α(p) = RIPPLE_A_MAX·(1−p)·diskA_max·glitA_max = MIN_ALPHA`
     * ⇒ `p_max = 1 − MIN_ALPHA / (RIPPLE_A_MAX·diskA(0)·glitA(1,0,1))`。
     * 两个上限**都从生产尺调用**（⛔ 不手抄 `0.775` / `1.0`，粼光那条扫描已经钉过它）。
     * ⚠️ 这一条把"α=0 不回收预算"落成了**可核对**的形式：环在 `p ≥ p_max` 之后一个像素都不铺，
     * 所以记账的半径上界是 `rippleR(moonR, pMax)` 而不是 `0.9·moonR`。
     */
    fun ripplePMax(): Double =
        (1.0 - MoonClouds.MIN_ALPHA /
            (RIPPLE_A_MAX * MoonClouds.diskA(0.0) * MoonClouds.glitA(1.0, 0.0, 1.0)))
            .coerceIn(0.0, 1.0)

    /**
     * `RIPPLE` 行的**生产几何**填充上界（占几屏，记账口径 = 椭圆 `2π·rx·ry`）。
     *
     * ⚠️ 按"**每一槽都在最大可见半径**"落，这是**上界**而非典型帧：同场的几环诞生于不同拍，
     * 相位天然错开 ⇒ 真实最坏帧低一些（原型探针读回的 `0.017 / 0.049` 就是那种典型帧的数，
     * 用它当表就是把门写成下界 —— 与 **D26 / D28** 同一条错法的第三次犯，处置见 §十五）。
     * 与 [MoonGlitterFrame.fillFraction] 同口径：结果与**画幅尺度**无关（`moonR` 由 `minDim` 线性
     * 得出、分母是 `w·h`），前提是 16:9（`moonR ∝ minDim` 而 `w·h ∝ minDim²`）。
     */
    fun rippleFillFraction(level: MoonLevel, moonR: Double, screenW: Double, screenH: Double): Double {
        val rr = rippleR(moonR, ripplePMax())
        val perRing = 2.0 * PI * rippleRx(rr) * rippleRy(rr)
        return rippleMax(level) * perRing / (screenW * screenH)
    }

    /**
     * 节拍涟漪的**定长队列**（⛔ 不是 `MutableList`：HIGH 每拍 push、寿命最长 3.3 s ⇒ 逐帧
     * 装箱 + 搬移就是把 GC 拉进绘制路径；`PerfBudgetContractTest` ③ 也扫不到 `listOf(`）。
     *
     * 三个槽位数组一一对应（`t` 已按 §7.5 的定义乘过 `sp`，于是回收判据就是一个常数比较）。
     * ⚠️ 存的是 `u`（生成位的**比例**）而不是绝对 `y`：画幅中途变了（TV 切换分辨率）时绝对 `y`
     * 会把环留在上一块画布的地平线下 —— 原型存绝对值，Kotlin 侧改存比例，观感逐帧等价
     * （代回同一条 `horizonY + seaH·u`），⛔ 不是"近似"。
     */
    class MoonRippleQueue {

        private val t = DoubleArray(RIPPLE_MAX_HIGH)
        private val sp = DoubleArray(RIPPLE_MAX_HIGH)
        private val u = DoubleArray(RIPPLE_MAX_HIGH)

        var count = 0
            private set

        fun reset() {
            count = 0
        }

        /** 拍帧生成：队列已满（`≥ max`）就**丢**，⛔ 不许无界增长（§7.5 第三条）。 */
        fun push(u: Double, sp: Double, max: Int) {
            if (max <= 0 || count >= max) return
            t[count] = 0.0
            this.sp[count] = sp
            this.u[count] = u
            count++
        }

        /**
         * 推进一帧：`t += dtSec × sp`（⭐ 只累加**增量**，⛔ 不读墙钟、⛔ 不乘绝对时间），
         * 到寿的槽与末位交换后收缩；`count > max` 时整段截到 `max`（与原型
         * `if (S.ripples.length > ripMax) S.ripples.length = ripMax` 同形，切档时兜底）。
         */
        fun advance(dtSec: Double, max: Int) {
            var i = 0
            while (i < count) {
                t[i] += dtSec * sp[i]
                if (t[i] >= RIPPLE_LIFE_SEC) {
                    count--
                    if (i != count) {
                        t[i] = t[count]
                        sp[i] = sp[count]
                        u[i] = u[count]
                    }
                    continue
                }
                i++
            }
            if (count > max) count = max
        }

        /** 第 [i] 环的寿命比 `p`（[MoonWater.rippleP]）。 */
        fun pAt(i: Int): Double = MoonWater.rippleP(t[i])

        /** 第 [i] 环的生成位比例（0..1，交给 [MoonWater.rippleY] 换算成 `y`）。 */
        fun uAt(i: Int): Double = u[i]
    }
}

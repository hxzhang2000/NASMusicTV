package com.nasmusic.tv.visualizer.renderers

/**
 * E44「明月」§八 —— **音频 → 幅度**的换算尺（全部纯函数，零 Android import）。
 *
 * ## 为什么单独一个文件（与 [MoonWater] / [MoonOpBudget] 同一条理由）
 * §八 的七个幅度（`±6%`、`±10%`、`+25%`、`×0.85..1.25`、`×1.25`、`0.42→0.36`）看着都是
 * "顺手一个乘子"，但它们**每一个都可能收费**：改 α 免费、改**几何**收费（§3.0(f) 第 3 条）。
 * 本效果里 `bass` 同时动了两处几何 —— 晕的**半径**（面积按平方涨）与海面的**亮度**
 * （亮度改色不收费）—— 而 G4 的门必须能**直接调用**这两个换算，否则预算表就只能抄数
 * （E44 已经为抄数付过 D10 / D24 / D26 / D28 四次学费）。
 *
 * ## ⛔ 本文件的三条不变式
 * 1. ⛔ **零 Android / Compose import** —— G4 与 G15 都在纯 JVM 单测里直接调这里的函数。
 * 2. ⛔ **中性值 = [NEUTRAL] = 0.4** —— §八 的式子全部写成 `(a − 0.4)` 的**偏移**形态，
 *    于是"无信号"（`aBass = 0.4`）恒等于**乘子 1**：T8 之前钉死的那四个中性常量
 *    （`SB_NEUTRAL` / `SPD_MUL_NEUTRAL` / `TREB_NEUTRAL` / `RIM_BEAT_NEUTRAL`）在本尺上
 *    逐一复现，⇒ **静止帧逐像素等于 T8 行为**（G15 ⑯b 钉这条，它同时是本批改动的观感闸门）。
 * 3. ⛔ **不读 `frame.spectrum`**（§八 末行：律动只用线性通道，拿 gamma 后的频谱驱律动
 *    会把强弱压扁，历史 BUG ⑨-b）；也 ⛔ 不读 `ctx.quality.maxParticles`。
 *
 * ## 分桶（⛔ 不是"性能优化"，是零分配红线）
 * `bass` 驱动的**色**（海面）与**半径**（晕）都长在 `Brush` 里，逐帧新建 Brush 违反
 * `PerfBudgetContractTest` ③；而 E44 是**固定构图** ⇒ 只有音频这一个自由度。做法与
 * §7.6 地平带（[MoonWater.bandOcclBucket]，偏差 D23）**同一套路**：
 * 把连续量量化成桶、按桶缓存渐变、并给出**解析**误差上界 `SPAN / (2·桶数)`
 * （⛔ 不许拿扫描网格当上界，那是 D26 里 `0.0015375` 冒充 `0.0015625` 的同款错误）。
 * 两把尺的桶数**不同**，各自的容差也不同（见 [SEA_SB_BUCKETS] / [HALO_R_BUCKETS]）。
 */
internal object MoonAudio {

    /**
     * §八 所有 `(a − 0.4)` 式子的**无信号基准**（T6/T8 那四个中性钉的统一归宿）。
     * ⚠️ 它是"乘子为 1 的那一点"，⛔ 不是"响度中值" —— 平滑器的 `value` 从 0 起跳，
     * 进入瞬间的真实值低于它，于是首帧的晕略小、海面略暗（幅度 ≤ 6%，不可辨）。
     */
    const val NEUTRAL = 0.4

    // ══════════════════════════════════════════════════════════════════════════
    //  §八 七项幅度（表行顺序 = 本文件顺序，逐行对账）
    // ══════════════════════════════════════════════════════════════════════════

    /** `bass → 海面整体亮度` 的偏移幅度（±10%）。⛔ 不驱动月盘亮度：真实月不因音乐变亮。 */
    const val SEA_BRIGHT_SPAN = 0.10

    /** `bass → 月晕半径` 的偏移幅度（±6%）。⚠️ 它是**半径** ⇒ 填充按**平方**收费（G4 的 HALO 行）。 */
    const val HALO_R_SPAN = 0.06

    /** `treble → 粼光 α` 的增益幅度（+25%）。该乘子长在 [MoonClouds.glitA] 里，本常量只做**对账锚**。 */
    const val GLIT_A_SPAN = 0.25

    /** `mid → 云速` 区间下端（原型 `AMP.cloudSpdMin`）。 */
    const val CLOUD_SPD_MIN = 0.85

    /** `mid → 云速` 区间上端。⚠️ 乘子只许作用在**增量** `dtSec` 上（§3.0(f) 第 5 条 / G5 ③）。 */
    const val CLOUD_SPD_MAX = 1.25

    /** `beat → 逆光云边` 的单帧增益（×1.25）。⛔ 只用 `frame.beat` 那一帧，不自造计时器。 */
    const val RIM_BEAT_K = 1.25

    /**
     * `energy` 那条包络的 **attack**：原型 `moonlit-preview.html:792` 给 `sEnergy` 单独配了
     * 一档比 `bass / mid / treble`（`Smoother(0.35, 0.06)`，`:791`）**更慢**的平滑 `0.28 / 0.05`。
     *
     * ⚠️ 做成常量而不是在渲染器里写 `AudioSmoother(0.28f, 0.05f)`：`AudioSmoother` 类头明写
     * 「⛔ 参数取值见 §7.3，不得在渲染器内另写魔数」，而本文件就是 §八 的那把尺 ——
     * 系数属于尺，不属于绘制现场。
     * ⛔ 不要"统一成默认值省事"：暗角是**全屏叠加**，包络快一档就等于画面每拍眨一下。
     */
    const val ENERGY_ATTACK = 0.28f

    /** `energy` 包络的 **release**（与 [ENERGY_ATTACK] 同条，原型同值）。 */
    const val ENERGY_RELEASE = 0.05f

    /** `energy → 暗角` 的**静默**端 = `postFx` 里那个必须保持字面量的数（0.42）。 */
    const val VIGN_QUIET = 0.42

    /** `energy → 暗角` 的**高潮**端（0.36，即"略微打开画面"）。 */
    const val VIGN_CLIMAX = 0.36

    /** 暗角包络的能度增益：`clamp(aEner·1.4, 0, 1)` ⇒ `aEner ≥ 0.714` 就饱和（原型同值）。 */
    const val VIGN_ENER_K = 1.4

    /** §八 `bass → 海面亮度`：`sb = 1 + 0.10·(aBass − 0.4)`。 */
    fun seaBright(aBass: Double): Double = 1.0 + SEA_BRIGHT_SPAN * (aBass - NEUTRAL)

    /**
     * §八 `bass → 晕半径`：`k = 1 + 0.06·(aBass − 0.4)`。
     *
     * ⚠️ 值域是 **`[0.976, 1.036]`**，⛔ 不是 `±6%` 的字面对称区间 `[0.964, 1.036]`：
     * 中性点是 `0.4` 而非法幅区间的**中点** `0.5`，所以 `aBass = 0` 那一端只走到 −2.4%
     * （要 −6% 得 `aBass = −0.2`，取不到）。§9.2 的 HALO 行按**上沿** `1.036` 收费 ⇒ 不受影响；
     * 但 ⛔ 不要据此以为"晕会缩 6%"（G15 ④ 钉住两端）。
     */
    fun haloRadiusK(aBass: Double): Double = 1.0 + HALO_R_SPAN * (aBass - NEUTRAL)

    /** §八 `mid → 云速度`：`lerp(0.85, 1.25, aMid)`（`aMid` 已夹在 0..1 ⇒ 无需再夹）。 */
    fun cloudSpdMul(aMid: Double): Double =
        CLOUD_SPD_MIN + (CLOUD_SPD_MAX - CLOUD_SPD_MIN) * clampUnit(aMid)

    /**
     * §八 `energy → 暗角强度`：静默 0.42 → 高潮 0.36。
     *
     * ⚠️ **方向**：原型那行写的是 `lerp(AMP.vignHigh, AMP.vignLow, k)`，而它给的两个数是
     * `vignHigh: 0.36`、`vignLow: 0.42` ⇒ 按原型字面实现会得到「越吵、暗角越**重**」，
     * 与 §八 该行明写的「高潮时略微"打开"画面」正相反（文档自己就留了
     * 「⚠️ 参数名与方向易写反」这句警告）。本尺取**§八 的文字意图**为准
     * （单调递减），原型写法登记为偏差。
     * ⭐ 另一端由 G15 钉死：`vignetteA(0) == postFx.vignette == 0.42f` ⇒ 无声帧逐像素不变。
     */
    fun vignetteA(aEner: Double): Double =
        VIGN_QUIET + (VIGN_CLIMAX - VIGN_QUIET) * clampUnit(aEner * VIGN_ENER_K)

    /** §八 `beat → 逆光云边亮度`：只在那一帧 ×1.25。 */
    fun rimBeatK(beat: Boolean): Double = if (beat) RIM_BEAT_K else 1.0

    // ══════════════════════════════════════════════════════════════════════════
    //  分桶（渐变缓存的键）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 海面亮度 `sb` 的桶数 = **32**。
     *
     * 判据与 D23 的地平带**同一条形态**：最大偏移误差必须有解析上界
     * `SEA_BRIGHT_SPAN / (2·桶数) = 0.10/64 = 0.0015625 ≤ 0.002`，
     * 且**刻度砍半（16 桶 ⇒ 0.003125）就越线** ⇒ 32 不是随手挑的。
     * ⚠️ 别用"rgb 取整步长"当判据：海色最亮通道只有 29，取整容差会让 4 桶都算"够细"，
     * 那是一条**永不会塌**的判据（G15 ⑦ 钉的是解析上界，不是取整步长）。
     */
    const val SEA_SB_BUCKETS = 32

    /**
     * 晕半径乘子 `k` 的桶数 = **8**（比海面粗，理由如下）。
     *
     * 半径是**几何**量：误差 `HALO_R_SPAN / (2·桶数) = 0.06/16 = 0.00375` ⇒ 半径相对误差 0.375%、
     * 面积相对误差 0.75%，而晕本身是三层径向渐变的**软边**（没有硬边界可对齐）⇒ 肉眼不可辨。
     * 容差取 `≤ 0.005`（半径相对 0.5%），砍半到 4 桶 ⇒ 0.0075 越线。
     * ⚠️ 为什么不与海面共用 32：每一桶要建 `HALO_LAYERS_MAX`（3）张 `Brush`，
     * 32 桶就是 96 张 native 着色器常驻，为 0.375% 的半径误差付这个账没有依据。
     */
    const val HALO_R_BUCKETS = 8

    /** `bass` → 海面桶号（先夹再 floor，⛔ 不四舍五入；与 [MoonWater.bandOcclBucket] 同口径）。 */
    fun sbBucket(aBass: Double): Int =
        (clampUnit(aBass) * SEA_SB_BUCKETS).toInt().coerceAtMost(SEA_SB_BUCKETS - 1)

    /** `bass` → 晕半径桶号。 */
    fun haloBucket(aBass: Double): Int =
        (clampUnit(aBass) * HALO_R_BUCKETS).toInt().coerceAtMost(HALO_R_BUCKETS - 1)

    /** 桶内还原用的 `sb`（缓存的渐变存的就是这个数，⛔ 不许再拿连续值去画）。 */
    fun seaBrightOfBucket(bucket: Int): Double =
        seaBright(bucketCenter(bucket, SEA_SB_BUCKETS))

    /** 桶内还原用的晕半径乘子。 */
    fun haloKOfBucket(bucket: Int): Double =
        haloRadiusK(bucketCenter(bucket, HALO_R_BUCKETS))

    /** `sb` 的最大偏移误差（解析式，与桶数成反比）。 */
    fun sbMaxError(buckets: Int): Double = SEA_BRIGHT_SPAN / (2.0 * buckets)

    /** 晕半径乘子的最大偏移误差（解析式）。 */
    fun haloKMaxError(buckets: Int): Double = HALO_R_SPAN / (2.0 * buckets)

    /** 桶中心（[sbBucket] 的量化是 floor ⇒ 误差对称地落在 ±半桶）。 */
    private fun bucketCenter(bucket: Int, buckets: Int): Double = (bucket + 0.5) / buckets

    private fun clampUnit(v: Double): Double = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v
}

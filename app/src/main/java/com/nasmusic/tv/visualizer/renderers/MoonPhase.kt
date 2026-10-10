package com.nasmusic.tv.visualizer.renderers

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * E44「明月」的真实月相历算：由 **UTC 毫秒时刻**算出照度、距角、亮 limb 位置角与天平动。
 *
 * 规格见 `docs/moonlit-visualizer-plan.md` §四。⛔ 全部纯函数、`Double` 内部、零对象分配
 * （[of] 例外，它按 §4.5 每 60 s 才调一次）、不读时区/`Calendar`/`ctx`、不用 `java.time`
 * （minSdk 22 不可用）—— 所以能脱离渲染单测（门禁 G2 = `MoonPhaseTest`）。
 *
 * ⚠️ 除历算之外，本文件还带 §4.2 末**相位阴影的几何**（[shadowVertexPx] / [darkAreaFraction] /
 * [skipPhaseShadow]）与 §4.4 的**朝向换算**（[shadowTiltDeg] / [terminatorRotDeg]）：它们同样
 * 是纯数学，放在这里才能让 `MoonlitRenderer` 只负责"把顶点画成闭合 `Path`"，⛔ 不再藏公式。
 *
 * ## 路线（§4.1，实测选型不是偏好）
 *
 * 低精度平黄道坐标 + **日月黄经差**，⛔ 不用《天文年鉴》的朔望截断级数：后者对 2026 年
 * 四个已发表天象复算误差 0.3–1.0 天，本路线六个锚点**全部命中**（残差 ≤ 2.7°，
 * 见 §4.1 表）⇒ 时刻误差 ≤ 0.22 天，照度误差最坏 ±1.5%（发生在上下弦前后）。
 * 这就是本文件的**精度上限**，⛔ 不要对外宣称"精确到时刻"。
 *
 * ## 两个会静默毁掉精度的常数（§4.2，均已被锚点检查抓到过一次）
 *
 * - [MOON_MEAN_LON_RATE] 必须是 **13.17639648 °/日**。误用朔望级数体系里的 13.179056
 *   ⇒ 26 年后偏 ~24°（G2 的负向自证 D1 就是它，实测锚点角差 23.7–28.1°）。
 * - 历元必须是 **J2000.0 = 2000-01-01 12:00 UT**（[J2000_MS]），⛔ 不是 Jan 0.0
 *   （1999-12-31 00:00，差 1.5 日）⇒ 全体相位偏 ~16–21°（负向自证 D2）。
 *
 * ## 朔望退化（§4.4）
 *
 * 位置角 PA 是"过月心与日心的大圆"方向：`e → 0`（重合）或 `e → ±180`（对至）时该大圆
 * **不唯一** ⇒ PA 数学上失稳（实测 2026-10-25→10-26 一夜跳 112°）。⛔ 不是 bug，但画面上
 * 不可接受 ⇒ 用 [paWeight] = `|sin e|` 把 PA 的影响强度压到 0；退化时刻形状本身已是整圆/
 * 整暗，所以加权后无痕。
 */
internal object MoonPhase {

    /** 朔望月长度（日）—— §4.2 的月龄式用 */
    const val SYNODIC_DAYS = 29.530588853

    /** J2000.0 历元 = 2000-01-01 12:00 UT 的 Unix 毫秒 */
    const val J2000_MS = 946_728_000_000L

    /** 月球平黄经速率（°/日）⚠️ 见类头，改一位就毁掉 26 年外的精度 */
    const val MOON_MEAN_LON_RATE = 13.17639648

    private const val MS_PER_DAY = 86_400_000.0
    private const val DEG_TO_RAD = Math.PI / 180.0

    // 平黄道坐标的五个基本量在 d = 0 处的初值（度），§4.2
    private const val MOON_MEAN_LON_0 = 218.31617
    private const val MOON_MEAN_ANOM_0 = 134.96315
    private const val MOON_MEAN_ANOM_RATE = 13.06499300
    private const val SUN_MEAN_ANOM_0 = 357.52910
    private const val SUN_MEAN_ANOM_RATE = 0.98560026
    private const val MEAN_ELONG_0 = 297.85016
    private const val MEAN_ELONG_RATE = 12.19074898
    private const val ARG_LATITUDE_0 = 93.27200
    private const val ARG_LATITUDE_RATE = 13.22935000

    /** 太阳平黄经：初值 + 速率 + 两项中心差 + 恒量修正（§4.2） */
    private const val SUN_MEAN_LON_0 = 280.46646
    private const val SUN_MEAN_LON_RATE = 0.98564760

    /** 黄赤交角（度），λ/β → α/δ 用 */
    private const val OBLIQUITY_DEG = 23.439280

    /** 自 J2000.0 起的天数（含小数）；`utcMs` 必须是 UTC 时刻。 */
    fun daysSinceJ2000(utcMs: Long): Double = (utcMs - J2000_MS) / MS_PER_DAY

    /** 照度分数 `f = (1 − cos e) / 2` ∈ 0..1。⚠️ 盈亏不影响它（`cos(−e) = cos e`，G2 的 D3）。 */
    fun illum(utcMs: Long): Float {
        val d = daysSinceJ2000(utcMs)
        val e = toRad(elongDegAt(d, MOON_MEAN_LON_RATE).toDouble())
        return ((1.0 - cos(e)) / 2.0).toFloat()
    }

    /** 日月黄经差（= 距角）`e` ∈ −180..180；`e > 0` 盈、`e < 0` 亏。 */
    fun elongDeg(utcMs: Long): Float =
        elongDegAt(daysSinceJ2000(utcMs), MOON_MEAN_LON_RATE)

    /** 是否盈（上半月）：`e > 0`。 */
    fun isWaxing(utcMs: Long): Boolean = elongDeg(utcMs) > 0f

    /**
     * 月龄（日）= `wrap360(e) / 360 × [SYNODIC_DAYS]`。
     *
     * ⚠️ 用 `wrap360` 而**不是** `wrap180`：`e = 0` 即朔 ⇒ 月龄 0，望后继续单调增长。
     * 初版方案此处写作 `((e + 180) % 360)`，代进 §4.6 B 的 `e = −26.0°` 得 12.62 日，
     * 与同一张表自己写的 27.39 日矛盾（v1.1 修正，G2 用 B 表原值断言）。
     *
     * ⛔ 只用于日志与真机验收（§十三 V1），不上屏。
     */
    fun ageDays(utcMs: Long): Float {
        val e = wrap360(elongDeg(utcMs).toDouble())
        return (e / 360.0 * SYNODIC_DAYS).toFloat()
    }

    /**
     * 亮 limb 的位置角（度，−180..180）：自**天球北**起、向东为正。
     *
     * 标准式（§4.3）：`tan PA = cos δ_s·sin(α_s − α_m) / [sin δ_s·cos δ_m − cos δ_s·sin δ_m·cos(α_s − α_m)]`，
     * 用 `atan2` 取象限。⚠️ 朔望附近失稳，消费方必须乘 [paWeight]（§4.4）。
     */
    fun brightLimbPaDeg(utcMs: Long): Float {
        val d = daysSinceJ2000(utcMs)
        val moonLon = moonEclLonDeg(d, MOON_MEAN_LON_RATE)
        val moonLat = moonEclLatDeg(d)
        val sunLon = sunEclLonDeg(d)
        // 太阳的 β 取 0（黄道面上），故其赤道坐标只需 λ_s
        val raSun = rightAscension(sunLon, 0.0)
        val decSun = declination(sunLon, 0.0)
        val raMoon = rightAscension(moonLon, moonLat)
        val decMoon = declination(moonLon, moonLat)
        val dra = raSun - raMoon
        val num = cos(decSun) * sin(dra)
        val den = sin(decSun) * cos(decMoon) - cos(decSun) * sin(decMoon) * cos(dra)
        return wrap180(atan2(num, den) / DEG_TO_RAD).toFloat()
    }

    /**
     * 天平动经度 `w`（度）= `λ_m − L`（截断摄动和）。实测范围 −6.2..+4.8（§4.6 B）。
     * 供 §5.1 的 UV 反解：变化超过 0.5° 才重烘圆盘（R4 的抖动保护）。
     */
    fun librationLonDeg(utcMs: Long): Float {
        val d = daysSinceJ2000(utcMs)
        return (moonEclLonDeg(d, MOON_MEAN_LON_RATE) - meanMoonLon(d, MOON_MEAN_LON_RATE)).toFloat()
    }

    /** 天平动纬度 `b`（度）= `−3.85 sin F − 1.37 sin(M − F)`。实测范围 −2.8..+4.9。 */
    fun librationLatDeg(utcMs: Long): Float {
        val d = daysSinceJ2000(utcMs)
        val m = toRad(moonMeanAnomaly(d))
        val f = toRad(argOfLatitude(d))
        return (-3.85 * sin(f) - 1.37 * sin(m - f)).toFloat()
    }

    /**
     * PA 的影响权重 `w = |sin e|` ∈ 0..1：朔望处 → 0，弦月处 → 1（§4.4）。
     *
     * G2 的负向自证 D4 断言 `e ∈ {0, ±180}` 时 `w < 1e-6`，⛔ 否则 §4.4 的退化保护是空话。
     */
    fun paWeight(elongDeg: Float): Float = abs(sin(toRad(elongDeg.toDouble()))).toFloat()

    /**
     * 明暗界线的倾斜角（度）= `baseTilt + wrap180(PA − baseTilt) × w`。
     *
     * `baseTilt` 取 **0f**（§4.4 的决定）：本文要 `MoonPhase` 保持纯函数，
     * 若取"上一帧值"就把时变状态引进来，单测失去判别力。
     */
    fun shadowTiltDeg(brightLimbPaDeg: Float, elongDeg: Float): Float {
        val w = paWeight(elongDeg).toDouble()
        return (wrap180(brightLimbPaDeg.toDouble() - 0.0) * w).toFloat()
    }

    /**
     * `tiltDeg` → 画布旋转（§4.4 末，定稿补齐的换算）。
     *
     * ⚠️ 必须**取负 + 加 90°**：屏幕 y 轴向下、PA 自北向东为正 ⇒ 两者旋向相反；而暗区顶点
     * 定义在局部系 **+X** 轴上（原型「+X 指向太阳」），PA = 0 表示亮 limb 朝**画布上方**。
     * ⛔ 这两个偏置漏一个，月牙朝向就整晚错 90° 或左右镜像（§十三 V3）。
     *
     * ⚠️ **落地偏离**（登记在 §十一 T5）：原型与本文写的是弧度
     * （`-(tiltDeg + 90f) * DEG_TO_RAD`），因为 HTML `canvas.rotate` 收弧度；
     * 而 `android.graphics.Canvas.rotate` 收**度数** ⇒ Kotlin 侧停在度数，⛔ 不要再乘 π/180。
     * 规则本身（取负 + 90）一字未改。
     */
    fun terminatorRotDeg(tiltDeg: Float): Float = -(tiltDeg + 90f)

    // ── §4.2 末 相位阴影的**几何**（纯数学，⛔ 不碰 `android.graphics`）──────────
    //    落在这里而不是另开文件：本文 §四 就是这套几何的规格出处，且 [shadowTiltDeg] /
    //    [terminatorRotDeg] 两个朝向换算已经在下面，⛔ 不要把一条明暗界线拆成三处。

    /** 三层软化阴影的层间距（占 R）—— 原型 `moonlit-preview.html:278` 定稿值 */
    const val TERM_SOFT_K = 0.045f

    /**
     * 阴影 α：**三层同值**（⛔ 不是逐层递减）⇒ 核心区合成不透明度 `1 − (1−0.62)³ = 0.945`。
     * 写成"每层递减"会让核心区只有 0.62，月暗面读作半透玻璃。
     */
    const val SHADOW_ALPHA = 0.62f

    /** 软化层数（§9.1 的 `TERMINATOR = 3`，⚠️ 那是**条件项**：近满月整段跳过 ⇒ 实测 0） */
    const val SHADOW_LAYERS = 3

    /** `f ≥ 0.995` **整段跳过**阴影的上界（§4.2 末，理由见 [skipPhaseShadow]） */
    const val SHADOW_SKIP_F = 0.995f

    /** 暗区顶点的**退化下限**（px）：`|v|` 小于它时按直线弦画，⛔ 零宽椭圆会画出毛刺（原型同阈值） */
    const val SHADOW_VERTEX_EPS = 0.5f

    /**
     * 是否跳过相位阴影。
     *
     * ⛔ `f ≥ 0.995` 必须**整段**跳，不是"画淡一点"：此时 `1−2f` 已是负数，软化项
     * `− i·TERM_SOFT_K·R·sign(1−2f)` 变成**往外推**，三层会把一条本该面积趋于 0 的暗带
     * 长成盘缘的暗斑（原型实测「满月变暗斑」的根因）。
     */
    fun skipPhaseShadow(illum: Float): Boolean = illum >= SHADOW_SKIP_F

    /**
     * 第 [layer] 层（`0 .. `[SHADOW_LAYERS]−1`）阴影的暗区顶点（局部 +X 轴，px）：
     * `v = R·(1−2f) − i·TERM_SOFT_K·R·sign(1−2f)`。
     *
     * ⚠️ 本文**只认 `v = R(1−2f)` 这一个写法**（§4.2 末）：初版另写了 `b = R(2f−1)`，
     * 是同一量在相反轴向上的写法，混用会把暗面与亮面**整个调换**。
     * 用 [darkAreaFraction] 断言 `层 0 恒等于 1−f` 就是抓这条反号的。
     *
     * `sign(1−2f)` 在 `f = 0.5` 时为 0，原型写作 `Math.sign(1−2f || 1)`（JS 的 `0 || 1 = 1`）
     * ⇒ 这里同样取 `+1`，⛔ 不要写成 `signum` 直接返回 0（那一档三层会完全重合，软带消失）。
     */
    fun shadowVertexPx(illum: Float, radius: Float, layer: Int): Float {
        val oneMinus2f = 1f - 2f * illum
        val sgn = if (oneMinus2f < 0f) -1f else 1f
        return radius * oneMinus2f - layer * TERM_SOFT_K * radius * sgn
    }

    /**
     * 顶点 → 暗区面积 / 整盘面积。
     *
     * 闭合形状 = 「−X 侧半盘」+「以 `|v|` 为半短轴的半椭圆」，半椭圆在 `v>0` 时**吃掉亮侧**、
     * `v<0` 时**让回暗侧**，两种情形合并成一个解析式：`(R + v) / (2R)`。
     * ⇒ `v = R(1−2f)` 代进去恰好 `= 1 − f`（这就是 §4.2 那两处写法必须统一的验证）。
     */
    fun darkAreaFraction(vertexPx: Float, radius: Float): Float =
        if (radius <= 0f) 0f else (radius + vertexPx) / (2f * radius)

    /** 一次性取齐六个量，供渲染器的 60 s 锚定（§4.5）。⚠️ 每次调用分配一个对象，⛔ 不进每帧路径。 */
    fun of(utcMs: Long): MoonState = MoonState(
        illum = illum(utcMs),
        elongDeg = elongDeg(utcMs),
        waxing = elongDeg(utcMs) > 0f,
        brightLimbPaDeg = brightLimbPaDeg(utcMs),
        librationLonDeg = librationLonDeg(utcMs),
        librationLatDeg = librationLatDeg(utcMs),
    )

    // ── 内部：日月黄经差的唯一计算点 ──────────────────────────────────────────────

    /**
     * 距角计算核。⚠️ `moonMeanLonRate` 是**故意暴露**的参数：G2 的负向自证 D1 要用
     * 13.179056 调用它、断言 2024 年之后的五个锚点全部偏离 > 3°，以此证明锚点表**有判别力**
     * （2000-01-06 除外，它距历元仅 5.26 日，速率差在此只累积 0.014°）。
     * 生产代码请走 [elongDeg]，⛔ 不要自己传速率。
     */
    internal fun elongDegAt(dDays: Double, moonMeanLonRate: Double): Float =
        wrap180(moonEclLonDeg(dDays, moonMeanLonRate) - sunEclLonDeg(dDays)).toFloat()

    private fun meanMoonLon(d: Double, rate: Double) = MOON_MEAN_LON_0 + rate * d
    private fun moonMeanAnomaly(d: Double) = MOON_MEAN_ANOM_0 + MOON_MEAN_ANOM_RATE * d
    private fun sunMeanAnomaly(d: Double) = SUN_MEAN_ANOM_0 + SUN_MEAN_ANOM_RATE * d
    private fun meanElongation(d: Double) = MEAN_ELONG_0 + MEAN_ELONG_RATE * d
    private fun argOfLatitude(d: Double) = ARG_LATITUDE_0 + ARG_LATITUDE_RATE * d

    /** 月球真黄经 λ_m（度）：平黄经 + 15 项主要摄动（§4.2） */
    private fun moonEclLonDeg(d: Double, rate: Double): Double {
        val l = toRad(meanMoonLon(d, rate))
        val m = toRad(moonMeanAnomaly(d))
        val ms = toRad(sunMeanAnomaly(d))
        val dd = toRad(meanElongation(d))
        val f = toRad(argOfLatitude(d))
        return radToDeg(l) + 6.289 * sin(m) + 1.274 * sin(2 * dd - m) + 0.658 * sin(2 * dd) +
            0.214 * sin(2 * m) - 0.186 * sin(ms) - 0.114 * sin(2 * f) -
            0.058 * sin(2 * dd - ms) - 0.057 * sin(2 * dd + m) + 0.053 * sin(2 * dd + ms) +
            0.046 * sin(2 * dd - m - ms) + 0.041 * sin(m - ms) - 0.035 * sin(dd) -
            0.031 * sin(m + ms) - 0.015 * sin(2 * f - m) - 0.012 * sin(dd + ms)
    }

    /** 月球真黄纬 β_m（度）：13 项（§4.2）。与 λ_m 的平黄经速率无关。 */
    private fun moonEclLatDeg(d: Double): Double {
        val m = toRad(moonMeanAnomaly(d))
        val ms = toRad(sunMeanAnomaly(d))
        val dd = toRad(meanElongation(d))
        val f = toRad(argOfLatitude(d))
        return 5.128 * sin(f) + 0.281 * sin(m + f) + 0.278 * sin(m - f) + 0.173 * sin(2 * dd - f) -
            0.159 * sin(2 * dd - m) - 0.110 * sin(2 * dd + m - f) + 0.062 * sin(2 * dd + ms) +
            0.060 * sin(2 * dd - ms) - 0.058 * sin(2 * dd + ms - f) -
            0.048 * sin(2 * dd - ms + f) - 0.034 * sin(m - f) - 0.032 * sin(m + f) -
            0.021 * sin(2 * dd - f + ms)
    }

    /** 太阳真黄经 λ_s（度）：平黄经 + 两项中心差 + 恒量（§4.2） */
    private fun sunEclLonDeg(d: Double): Double {
        val ms = toRad(sunMeanAnomaly(d))
        return SUN_MEAN_LON_0 + SUN_MEAN_LON_RATE * d + 1.9150 * sin(ms) +
            0.0200 * sin(2 * ms) - 0.00062
    }

    // ── 黄道 → 赤道（§4.3），入出均为弧度 ────────────────────────────────────────

    private fun rightAscension(eclLonDeg: Double, eclLatDeg: Double): Double {
        val lam = toRad(eclLonDeg)
        val bet = toRad(eclLatDeg)
        val eps = toRad(OBLIQUITY_DEG)
        return atan2(sin(lam) * cos(eps) - tan(bet) * sin(eps), cos(lam))
    }

    private fun declination(eclLonDeg: Double, eclLatDeg: Double): Double {
        val lam = toRad(eclLonDeg)
        val bet = toRad(eclLatDeg)
        val eps = toRad(OBLIQUITY_DEG)
        return asin(sin(bet) * cos(eps) + cos(bet) * sin(eps) * sin(lam))
    }

    private fun toRad(deg: Double) = deg * DEG_TO_RAD
    private fun radToDeg(rad: Double) = rad / DEG_TO_RAD

    /** 收进 −180..180（含 −180，不含 180） */
    private fun wrap180(deg: Double): Double = (deg + 180.0).mod(360.0) - 180.0

    /** 收进 0..360 */
    private fun wrap360(deg: Double): Double = deg.mod(360.0)
}

/**
 * 一次历算的全部输出（§4.3 的 `MoonState`）。不可变；⛔ 只允许由 [MoonPhase.of] 构造，
 * 渲染器每 60 s 换一次（§4.5），⛔ 不进每帧路径。
 *
 * @param illum 照度分数 f ∈ 0..1 —— 决定明暗界线椭圆的**形状**
 * @param elongDeg 距角 e ∈ −180..180 —— 正为盈
 * @param waxing `e > 0`
 * @param brightLimbPaDeg 亮 limb 位置角（度，自天球北向东为正），朔望处失稳 ⇒ 用前乘权重
 * @param librationLonDeg 天平动经度（度），驱动圆盘重烘判定
 * @param librationLatDeg 天平动纬度（度）
 */
internal data class MoonState(
    val illum: Float,
    val elongDeg: Float,
    val waxing: Boolean,
    val brightLimbPaDeg: Float,
    val librationLonDeg: Float,
    val librationLatDeg: Float,
)

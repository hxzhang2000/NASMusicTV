package com.nasmusic.tv.visualizer.renderers

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 「世界」效果的太阳几何：晨昏线（昼夜分界）由**真实 UTC 时间**驱动。
 *
 * 「会呼吸的星球」这一观感完全来自这里 —— 晨昏线每 24 小时绕地球转整整一圈，
 * 且转的角度与真实时间一致（不是「随便转」）。
 *
 * ## 使用的公式
 *
 * ### ① 太阳直射点纬度（赤纬）δ
 * ```
 * subsolarLat ≈ -23.44° × cos( 2π × (dayOfYear + 10) / 365.25 )
 * ```
 * 用「近似赤纬」而非完整的岁差/轨道偏心率解：本效果只把晨昏线当作**装饰**，
 * ±1° 的误差肉眼不可见，而完整公式要拉真太阳时、章动、黄赤交角变化，代价与收益不成比例。
 * 幅度 23.44° 保证结果恒在 [-23.44, 23.44] ⊂ [-23.5, 23.5]（真实极值 ±23.44）。
 *
 * `dayOfYear` 用纯整数历法算（见 [dayOfYear]），⛔ 不用 `java.time`
 * （minSdk 22 上不可用）也不用 `java.util.Calendar`（每次调用分配对象）。
 *
 * ### ② 太阳时角 H（度）：0 = 当地正午（太阳在子午线上）
 * ```
 * H = 15° × ( UTC小时 + lon/15 − 12 )        再 wrap 到 [-180, 180]
 * ```
 * 地球自转 360°/24h ⇒ 每 1 小时太阳时角走 15°。
 *
 * ### ③ 晨昏线经度
 * 天顶角余弦为 0 即为晨昏线：
 * ```
 * cos(zenith) = sin(lat)·sin(δ) + cos(lat)·cos(δ)·cos(H) = 0
 *   ⇒ cos(H) = −tan(lat)·tan(δ)
 *   ⇒ |H| = acos( −tan(lat)·tan(δ) )           —— 记作 H₀ ∈ [0, 180]
 * ```
 * `|tan(lat)·tan(δ)| > 1` ⇒ 无解：该纬度此刻要么极昼要么极夜，
 * 不存在晨昏线交点 ⇒ 返回 [Float.NaN]。
 *
 * H₀ 是晨昏线到**太阳子午线**的角距，故：
 * ```
 * 昏线（东侧，太阳落下的那边）经度 = 子午线经度 + H₀
 * 晨线（西侧，太阳升起的那边）经度 = 子午线经度 − H₀
 * ```
 *
 * ### ④ 昼夜判定
 * ①式左端 **> 0** ⇒ 太阳在地平线上（白昼）。这就是 [isDayAtHourAngle]。
 *
 * ⛔ 全部纯函数、零分配、不依赖 Android（`kotlin.math` 里的三角函数在 minSdk 22
 * 上映射到 `java.lang.Math`，API 1 即有）。
 */
internal object WorldTerminator {

    /** 黄赤交角近似值（度）—— 太阳直射点纬度的最大幅度 */
    const val OBLIQUITY_DEG = 23.44f

    /** 每年太阳时角推进的度数（360° / 24h） */
    private const val DEG_PER_HOUR = 15f

    /** 一天的毫秒数 */
    private const val MS_PER_DAY = 86_400_000L

    private const val MS_PER_HOUR = 3_600_000f

    /**
     * 太阳直射点纬度 δ（度），由 UTC 毫秒时间戳算出，近似误差 ±1°。
     *
     * = `-23.44° × cos(2π × (dayOfYear + 10) / 365.25)`；恒在 ±23.44 内。
     */
    fun subsolarLat(utcMs: Long): Float {
        val doy = dayOfYear(utcMs).toDouble()
        val phase = 2.0 * Math.PI * (doy + 10.0) / 365.25
        return (-OBLIQUITY_DEG.toDouble() * cos(phase)).toFloat()
    }

    /**
     * 太阳子午线（直射点所在）经度（度），∈ [-180, 180]。
     *
     * = `wrap180(15° × (12 − UTC小时))` —— UTC 12:00 时为 0°（正午在本初子午线），
     * UTC 00:00 时为 180°。晨昏线整体绕它旋转，旋转速度即 15°/h。
     */
    fun subsolarMeridianLon(utcMs: Long): Float = wrap180(DEG_PER_HOUR * (12f - utcHours(utcMs)))

    /**
     * 太阳时角 H（度），0 = 该经度处于当地正午，∈ [-180, 180]。
     *
     * = `wrap180(15° × (UTC小时 + lon/15 − 12))`，
     * 等价于 `wrap180(lon − 子午线经度)`。
     */
    fun solarHourAngle(lonDeg: Float, utcMs: Long): Float =
        wrap180(DEG_PER_HOUR * (utcHours(utcMs) + lonDeg / DEG_PER_HOUR - 12f))

    /**
     * 晨昏线到**太阳子午线**的角距 H₀（度），∈ [0, 180]；
     * 该纬度此刻无极昼/极夜、无晨昏线交点时返回 [Float.NaN]。
     *
     * = `acos( −tan(lat) × tan(δ) )`，见类 KDoc ③。
     *
     * ⚠️ **返回值不是经度**，只是相对角距 —— 想要经度请用
     * [terminatorLonAt] / [dawnTerminatorLonAt]（它们把子午线经度加上/减去本值）。
     * 本函数是「与时间无关」的纯几何量，渲染层每帧只需算一次 H₀ 就能给所有纬度复用。
     */
    fun terminatorLon(latDeg: Float, subsolarLatDeg: Float): Float {
        val cosH = -tanDeg(latDeg) * tanDeg(subsolarLatDeg)
        if (cosH < -1f || cosH > 1f || cosH.isNaN()) return Float.NaN
        return Math.toDegrees(acos(cosH.toDouble())).toFloat()
    }

    /**
     * **昏线**（东侧，太阳落下的一侧）经度（度），∈ [-180, 180]；
     * 无解时返回 [Float.NaN]（并由 [Float.NaN] 传播到运算结果）。
     */
    fun terminatorLonAt(latDeg: Float, subsolarLatDeg: Float, utcMs: Long): Float =
        wrap180(terminatorLon(latDeg, subsolarLatDeg) + subsolarMeridianLon(utcMs))

    /**
     * **晨线**（西侧，太阳升起的一侧）经度（度），∈ [-180, 180]；
     * 无解时返回 [Float.NaN]。与 [terminatorLonAt] 相差 2×H₀。
     */
    fun dawnTerminatorLonAt(latDeg: Float, subsolarLatDeg: Float, utcMs: Long): Float =
        wrap180(subsolarMeridianLon(utcMs) - terminatorLon(latDeg, subsolarLatDeg))

    /**
     * 天顶角余弦（`cos(zenith)`，∈ [-1, 1]）—— 全部太阳几何的唯一核心量：
     * `[isDayAtHourAngle]` 的判据、[sunFactorAtHourAngle] 的被钳值。
     *
     * = `sin(lat)·sin(δ) + cos(lat)·cos(δ)·cos(H)`。
     */
    private fun cosZenith(latDeg: Float, subsolarLatDeg: Float, hourAngleDeg: Float): Double {
        val latR = Math.toRadians(latDeg.toDouble())
        val decR = Math.toRadians(subsolarLatDeg.toDouble())
        val hR = Math.toRadians(hourAngleDeg.toDouble())
        return sin(latR) * sin(decR) + cos(latR) * cos(decR) * cos(hR)
    }

    /**
     * 给定太阳时角 [hourAngleDeg] 时，该点是否处于白昼。
     *
     * = `sin(lat)·sin(δ) + cos(lat)·cos(δ)·cos(H) > 0`（天顶角余弦 > 0）。
     *
     * ⛔ **不要**拿它当精确的边界判据：正好落在晨昏线上时左端理论上为 0，
     * 实际计算中该瞬间的正负由浮点舍入决定（`cosZ ≈ ±1e-16`），是掷硬币。
     * 需要连续/可比较的量请用 [sunFactorAtHourAngle]。
     *
     * @param lonDeg ⛔ **不参与计算**（时角已由 [hourAngleDeg] 给出）。
     *   保留该参数是为了让 [isDay] / [isDayAtHourAngle] 的调用形状一致。
     */
    fun isDayAtHourAngle(
        lonDeg: Float,
        latDeg: Float,
        subsolarLatDeg: Float,
        hourAngleDeg: Float
    ): Boolean = cosZenith(latDeg, subsolarLatDeg, hourAngleDeg) > 0.0

    /**
     * 太阳高度因子 0..1：正午 1、晨昏线 0、夜 0 = [cosZenith] 钳到 [0, 1]。
     *
     * ⛔ [isDayAtHourAngle] 是**二值**判据，只够回答「画不画夜间遮罩」；要画
     * 「夜侧城市变暗」这类渐变必须用本函数 —— 深夜 0、黄昏 0.3、正午 1 连续过渡。
     * 渲染层每帧对每个城市调一次，零分配。
     *
     * @param lonDeg ⛔ **不参与计算**，理由同 [isDayAtHourAngle]
     */
    fun sunFactorAtHourAngle(
        lonDeg: Float,
        latDeg: Float,
        subsolarLatDeg: Float,
        hourAngleDeg: Float
    ): Float = cosZenith(latDeg, subsolarLatDeg, hourAngleDeg).toFloat().coerceIn(0f, 1f)

    /** 该点此刻的太阳高度因子 0..1（内部用 [solarHourAngle] 从 [utcMs] 求时角） */
    fun sunFactor(lonDeg: Float, latDeg: Float, subsolarLatDeg: Float, utcMs: Long): Float =
        sunFactorAtHourAngle(lonDeg, latDeg, subsolarLatDeg, solarHourAngle(lonDeg, utcMs))

    /**
     * 该点此刻是否处于白昼（内部用 [solarHourAngle] 从 [utcMs] 求时角）。
     *
     * ⛔ 与同包其它文件不同，本函数**必须**带 `utcMs`：昼夜是「位置 + 时刻」的函数，
     * 少一个参数就只能要么固定某一时刻、要么忽略真实时间 —— 两者都会让晨昏线停转。
     * 需要与时间解耦的纯几何版本用 [isDayAtHourAngle]。
     */
    fun isDay(lonDeg: Float, latDeg: Float, subsolarLatDeg: Float, utcMs: Long): Boolean =
        isDayAtHourAngle(lonDeg, latDeg, subsolarLatDeg, solarHourAngle(lonDeg, utcMs))

    // ── 内部工具 ───────────────────────────────────────────────

    /** 角度 wrap 到 [-180, 180]；[Float.NaN] 传播为 [Float.NaN] */
    private fun wrap180(deg: Float): Float {
        if (deg.isNaN()) return Float.NaN
        var x = deg % 360f
        if (x > 180f) x -= 360f
        if (x < -180f) x += 360f
        return x
    }

    /** UTC 毫秒 → 当日已过小时数（[0, 24)），支持负时间戳（1970 之前） */
    private fun utcHours(utcMs: Long): Float {
        var d = utcMs % MS_PER_DAY
        if (d < 0) d += MS_PER_DAY
        return d / MS_PER_HOUR
    }

    /** 角度 → 正切（内部统一走 [Double] 三角函数，[Float] 只在边界处出现） */
    private fun tanDeg(deg: Float): Float = tan(Math.toRadians(deg.toDouble())).toFloat()

    /** 平年各月 1 日的年内偏移（天） */
    private val MONTH_START = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

    /**
     * UTC 毫秒 → 年内第几天（1..366，1 月 1 日 = 1）。
     *
     * 纯整数历法（Howard Hinnant 的 `civil_from_days` 反解）：先把毫秒 floordiv 成
     * 「自 1970-01-01 起的整天数」，再用 400 年 146097 天的循环还原「年 / 年内第几天」。
     *
     * ⛔ 刻意不用 `java.time.LocalDate`（**minSdk 22 不可用**，API 26 才有）与
     * `java.util.Calendar`（每次分配对象、且受默认时区影响 → 不再是纯函数）。
     */
    private fun dayOfYear(utcMs: Long): Int {
        // floorDiv：Kotlin 的 Long 除法向零截断，负数要手动补
        var days = utcMs / MS_PER_DAY
        if (utcMs % MS_PER_DAY < 0) days--
        val z = days + 719468L
        val era = if (z >= 0) z / 146097L else (z - 146096L) / 146097L
        val doe = z - era * 146097L                                  // [0, 146096]
        val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L // [0, 399]
        val y = yoe + era * 400L
        // doy 是「自 3 月 1 日起的天数」，需经 mp 还原成 月/日 才能算年内第几天
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)          // [0, 365]
        val mp = (5L * doy + 2L) / 153L                               // [0, 11]
        val d = (doy - (153L * mp + 2L) / 5L + 1L).toInt()            // [1, 31]
        val m = if (mp < 10L) (mp + 3L).toInt() else (mp - 9L).toInt() // [1, 12]
        val year = if (m <= 2) y + 1L else y
        val leap = (year % 4L == 0L && year % 100L != 0L) || year % 400L == 0L
        return MONTH_START[m - 1] + (if (m > 2 && leap) 1 else 0) + d
    }
}

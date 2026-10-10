package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * 门禁 **G2**：[MoonPhase] 的历算正确性（规格 `docs/moonlit-visualizer-plan.md` §4.6）。
 *
 * 四组向量逐字照抄 §4.6：A 天象锚点、B 连续向量、C 朔望对照、D 负向自证。
 * ⛔ **D 的几例是本类的重点** —— 没有它们，A/B/C 三组即使全绿也证明不了测试有判别力
 * （一条把常数写错的实现，同样能"通过"一组恰好自洽的断言）。
 *
 * 纯 JVM：[MoonPhase] 不碰 `android.*`，所以本类**不需要 Robolectric**，也 ⛔ 不要加
 * `@RunWith(RobolectricTestRunner::class)` —— 加了只会让这十几例慢一个数量级。
 */
class MoonPhaseTest {

    // ══════════════════════ A. 天象锚点 ══════════════════════

    /**
     * 六个**已发表天象**的极大时刻，断言本文算法算出的日月黄经差落在应为值附近。
     *
     * 应为值是"合 = 0° / 冲 = 180°"这个**物理事实**，⛔ 不是本文自己算出的数
     * —— 这正是这组向量有独立判别力的原因（系数改一位就红）。
     */
    @Test
    fun `A 六个天象锚点的距角残差不大于 3 度`() {
        for (anchor in ANCHORS) {
            val e = MoonPhase.elongDeg(anchor.utcMs).toDouble()
            val dev = absDeg(e - anchor.expected)
            assertTrue(
                "${anchor.name}（${anchor.desc}）：距角应为 ${anchor.expected}°，实算 $e° ⇒ 偏离 " +
                    String.format("%.3f", dev) + "° > 3.0°",
                dev <= 3.0,
            )
        }
    }

    /** 同一批锚点上的照度：合 ⇒ 全暗，冲 ⇒ 全亮。 */
    @Test
    fun `A 六个天象锚点的照度落在合与冲两端`() {
        for (anchor in ANCHORS) {
            val f = MoonPhase.illum(anchor.utcMs)
            val ok = if (anchor.expected == 0f) f <= 0.01f else f >= 0.99f
            assertTrue(
                "${anchor.name}：照度应为 ${if (anchor.expected == 0f) "≈0" else "≈1"}，实算 $f",
                ok,
            )
        }
    }

    // ══════════════════════ B. 连续向量 ══════════════════════

    /**
     * 逐字断言 §4.6 B 的八行（北京 21:00，2026 年 10 月）：`f` ±0.005、月龄 ±0.05 日、
     * 天平动 ±0.30°、PA ±2°。
     *
     * ⚠️ **PA 列对 2026-10-26 豁免**（§4.4 朔望退化：该夜 `|sin e| ≈ 0.08`，PA 数学上
     * 失稳、一夜跳 112°）。⛔ 不是 bug，但断言会永久红 ⇒ 原型即此式，照抄。
     */
    @Test
    fun `B 十月连续向量逐字命中`() {
        for (v in VECTORS) {
            val ms = bj2100(v.date)
            assertEquals("${v.date} 照度 f", v.illum, MoonPhase.illum(ms), 0.005f)
            assertEquals("${v.date} 月龄", v.ageDays, MoonPhase.ageDays(ms), 0.05f)
            assertEquals("${v.date} 天平动经度", v.libLon, MoonPhase.librationLonDeg(ms), 0.30f)
            assertEquals("${v.date} 天平动纬度", v.libLat, MoonPhase.librationLatDeg(ms), 0.30f)

            // ⚠️ B 表的 e 列是**混合口径**（望后写成单调增长的 184.8 / 225.7），
            // ⛔ 绝不能直接当 elongDeg() 的期望值断言 ⇒ 一律比角差。
            val e = MoonPhase.elongDeg(ms).toDouble()
            val dev = absDeg(e - v.elongMixed)
            assertTrue("${v.date} 距角 $e° vs 表中 ${v.elongMixed}° ⇒ 角差 $dev > 1.5°", dev <= 1.5)

            if (v.date == "2026-10-26") continue      // §4.4 退化豁免
            val pa = MoonPhase.brightLimbPaDeg(ms).toDouble()
            val paDev = absDeg(pa - v.pa)
            assertTrue("${v.date} PA $pa° vs ${v.pa}° ⇒ 角差 $paDev > 2.0°", paDev <= 2.0)
        }
    }

    /** B 表的盈亏方向（`e > 0` 盈）：十月初是残月（亏），10-11 朔之后转盈。 */
    @Test
    fun `B 向量的盈亏方向在朔两侧翻转`() {
        assertFalse("10-05 亏", MoonPhase.isWaxing(bj2100("2026-10-05")))
        assertFalse("10-08 亏", MoonPhase.isWaxing(bj2100("2026-10-08")))
        assertTrue("10-14 盈", MoonPhase.isWaxing(bj2100("2026-10-14")))
        assertTrue("10-21 盈", MoonPhase.isWaxing(bj2100("2026-10-21")))
        assertFalse("10-29 亏", MoonPhase.isWaxing(bj2100("2026-10-29")))
    }

    /** 月龄必须单调增长并在朔处回绕（§4.2 用 `wrap360` 而非 `wrap180` 的原因）。 */
    @Test
    fun `月龄沿朔望月单调增长并在朔处回绕`() {
        val seq = listOf("2026-10-11", "2026-10-14", "2026-10-18", "2026-10-21", "2026-10-26", "2026-10-29")
        val ages = seq.map { MoonPhase.ageDays(bj2100(it)) }
        for (i in 1 until ages.size) {
            assertTrue("${seq[i]} 月龄 ${ages[i]} 应大于 ${seq[i - 1]} 的 ${ages[i - 1]}", ages[i] > ages[i - 1])
        }
        assertTrue("10-10（朔前）月龄应 >28", MoonPhase.ageDays(bj2100("2026-10-10")) > 28f)
        assertTrue("10-11（朔日）月龄应 <2", MoonPhase.ageDays(bj2100("2026-10-11")) < 2f)
    }

    /** [MoonPhase.of] 与六个单函数必须逐项一致（渲染器 60 s 锚定走 `of`，⛔ 两套结果不能分叉）。 */
    @Test
    fun `of 聚合结果与单函数逐项一致`() {
        val ms = bj2100("2026-10-18")
        val s = MoonPhase.of(ms)
        assertEquals(MoonPhase.illum(ms), s.illum, 0f)
        assertEquals(MoonPhase.elongDeg(ms), s.elongDeg, 0f)
        assertEquals(MoonPhase.isWaxing(ms), s.waxing)
        assertEquals(MoonPhase.brightLimbPaDeg(ms), s.brightLimbPaDeg, 0f)
        assertEquals(MoonPhase.librationLonDeg(ms), s.librationLonDeg, 0f)
        assertEquals(MoonPhase.librationLatDeg(ms), s.librationLatDeg, 0f)
    }

    /** 天平动必须落在实测物理范围（§4.6 B：经度 −6.2..+4.8，纬度 −2.8..+4.9）。 */
    @Test
    fun `天平动全年不超物理包络`() {
        for (i in 0 until 365) {
            val ms = utc(2026, 1, 1, 0, 0) + i * DAY_MS
            val lon = MoonPhase.librationLonDeg(ms)
            val lat = MoonPhase.librationLatDeg(ms)
            assertTrue("第 $i 日经度天平动 $lon° 越出 −8..+8", lon > -8f && lon < 8f)
            assertTrue("第 $i 日纬度天平动 $lat° 越出 −7..+7", lat > -7f && lat < 7f)
        }
    }

    /**
     * 照度必须始终在 0..1，且 2026 年内盈亏标记各翻转一次每朔望月。
     *
     * 判据用 **`isWaxing` 的翻转沿**（逐小时采样）而不是"照度过阈值"：
     * ⛔ 后者在逐日采样下根本数不出来 —— `f` 的最小值在一整天的采样里只到 ~0.003，
     * 拿 `f ≤ 0.001` 当过朔判据实测只数出 7 次（真值 12 次）。
     * 翻转沿则每个朔望月恰好各一次：升沿 = 朔，降沿 = 望。
     */
    @Test
    fun `照度全年恒在 0 到 1 且盈亏翻转沿与朔望次数一致`() {
        val start = utc(2026, 1, 1, 0, 0)
        var shuo = 0
        var wang = 0
        var prev = MoonPhase.isWaxing(start)
        for (i in 1..365 * 24) {
            val ms = start + i * HOUR_MS
            assertTrue("第 $i 小时照度越界", MoonPhase.illum(ms) in 0f..1f)
            val wax = MoonPhase.isWaxing(ms)
            if (wax && !prev) shuo++
            if (!wax && prev) wang++
            prev = wax
        }
        // 2026 年 C 表：朔 12 次（01-19..12-09）、望 13 次（01-03..12-24）
        assertEquals("全年朔次数", 12, shuo)
        assertEquals("全年望次数", 13, wang)
    }

    // ══════════════════════ C. 朔望对照 ══════════════════════

    /**
     * 逐字断言 §4.6 C 的 25 个朔望时刻：朔处 `|e| ≈ 0`、望处 `|e| ≈ 180`。
     *
     * 容差 0.5° 的来由：表值只给到 10 分钟，而 `e` 走 12.19°/日 ⇒ 10 分钟 = 0.085°；
     * 表本身就是本文算法算的（实测最大偏离 0.094°），0.5° 留了近 6 倍余量。
     */
    @Test
    fun `C 全年朔望时刻的距角落在合与冲`() {
        for ((label, type) in SYZYGIES) {
            val e = MoonPhase.elongDeg(beijing(label)).toDouble()
            val dev = if (type == SHUO) abs(e) else 180.0 - abs(e)
            assertTrue("2026-$label $type：偏离合/冲 $dev° > 0.5°（实算 e = $e°）", dev <= 0.5)
        }
    }

    /**
     * ⛔ **相邻朔望间隔判别力**：朔→望、望→朔都必须落在 13.8–15.8 日。
     *
     * 边界来自实测（本表 24 个间隔为 **13.972 ~ 15.569 日**，半朔望月的偏心率高/低速支
     * 各一头），⛔ 不是取 `29.53/2 = 14.77` 再加个对称容差 —— 那样 13.97 那三条会一起红。
     *
     * 这条是 §4.6 C 表初版 `07-26 03:00 望` 那个错的**唯一捕手** —— 单看一行看不出问题
     * （那天 `f = 0.870` 照样"接近满月"），但它距同表的 `07-14 18:00 朔` 只有 11.4 日。
     * 正确值为 **07-29 22:30**（v1.1 已改）。
     */
    @Test
    fun `C 相邻朔望间隔落在半月附近`() {
        for (i in 1 until SYZYGIES.size) {
            val (prevLabel, prevType) = SYZYGIES[i - 1]
            val (curLabel, curType) = SYZYGIES[i]
            val gapDays = (beijing(curLabel) - beijing(prevLabel)) / DAY_MS.toDouble()
            assertTrue(
                "$prevLabel($prevType) → $curLabel($curType) 间隔 $gapDays 日 ∉ 13.8..15.8",
                gapDays in 13.8..15.8,
            )
            assertTrue("$prevLabel 与 $curLabel 类型必须交替", prevType != curType)
        }
    }

    /** 食日交叉核对：C 表的"北京 08-13 01:50 朔"与 A 锚点"UTC 08-12 17:50 日全食"是同一时刻。 */
    @Test
    fun `C 表的食日与 A 锚点同源`() {
        assertEquals(
            "北京写法必须等于 UT 写法（差 8 小时的读图错误 = §十三 V2）",
            utc(2026, 8, 12, 17, 50),
            beijing("08-13 01:50"),
        )
        for (label in listOf("03-03 19:40", "08-28 12:30")) {
            val e = MoonPhase.elongDeg(beijing(label)).toDouble()
            assertTrue("$label 应落在冲：$e°", 180.0 - abs(e) <= 0.5)
        }
        for (label in listOf("02-17 19:50", "08-13 01:50")) {
            val e = MoonPhase.elongDeg(beijing(label)).toDouble()
            assertTrue("$label 应落在合：$e°", abs(e) <= 0.5)
        }
    }

    // ══════════════════════ D. 负向自证 ══════════════════════

    /**
     * **D1**：把月球平黄经速率换成朔望级数体系里的 `13.179056` ⇒ 2026 年锚点必须全红。
     *
     * ⚠️ 判据只覆盖 2024 之后的五个锚点，⛔ 不含 2000-01-06：它距历元仅 5.26 日，
     * 速率差 0.00266 °/日在此只累积 0.014°（实测仍 < 3°），拿它断言"必须失败"会把
     * 正确实现判红。实测偏离：2024-04-08 23.69 / 02-17 28.06 / 03-03 25.17 /
     * 08-12 25.85 / 08-28 25.97（均 > 3° ⇒ 锚点确有判别力）。
     */
    @Test
    fun `D1 错用朔望级数的平黄经速率必须让锚点全红`() {
        for (anchor in ANCHORS.filter { it.name != "2000-01-06" }) {
            val d = MoonPhase.daysSinceJ2000(anchor.utcMs)
            val e = MoonPhase.elongDegAt(d, 13.179056).toDouble()
            val dev = absDeg(e - anchor.expected)
            assertTrue(
                "负向自证失效：${anchor.name} 在错误速率下只偏 $dev°（应 > 3°）⇒ 锚点没有判别力",
                dev > 3.0,
            )
        }
    }

    /**
     * **D2**：把历元从 J2000.0（2000-01-01 12:00 UT）改成 Jan 0.0（1999-12-31 00:00）
     * ⇒ `d` 多算 1.5 日 ⇒ **六个**锚点必须全红（实测偏 16.5–21.2°）。
     *
     * 与 D1 不同，本例对 2000-01-06 同样生效 —— 固定 1.5 日的偏移与锚点远近无关。
     */
    @Test
    fun `D2 历元错用 Jan 0 必须让六个锚点全红`() {
        val jan0 = utc(1999, 12, 31, 0, 0)      // Jan 0.0，比 J2000.0 早 1.5 日
        for (anchor in ANCHORS) {
            val dJan0 = (anchor.utcMs - jan0) / MS_PER_DAY
            val e = MoonPhase.elongDegAt(dJan0, MoonPhase.MOON_MEAN_LON_RATE).toDouble()
            val dev = absDeg(e - anchor.expected)
            assertTrue(
                "负向自证失效：${anchor.name} 在 Jan 0.0 历元下只偏 $dev°（应 > 3°）",
                dev > 3.0,
            )
        }
    }

    /**
     * **D3**：照度对**距角**必须对称（`f(e) = f(−e)`，盈亏不影响亮面比例）。
     *
     * ⛔ 切勿写成时间对称 `illum(t₀+Δ) == illum(t₀−Δ)`：`e(t)` 因轨道偏心率**不是奇函数**，
     * 实测 Δ=3 日两侧 `e` 为 `+39.03 / −40.92` ⇒ 照度差 0.0106（Δ=7 日差 0.074），
     * 那样写会把正确实现判红（§4.6 v1.1 注）。⇒ 只能在盈、亏**两个单调支上各解出**
     * `e = +A` 与 `e = −A` 的时刻，再比 `f`（实测九个 A 的差值恒为 0）。
     */
    @Test
    fun `D3 照度对距角奇对称与盈亏无关`() {
        val newMoon = utc(2026, 8, 12, 17, 50)
        // ⚠️ 二分的右端点 ⛔ 不能取"已发表朔时刻"：本文算法的朔比它早约 2 分钟（锚点残差
        // +0.016° 正是这件事），取它会让连续距角已经回绕到 0.01° ⇒ 与左端点同号 ⇒ "不变号"。
        // 29 日（= 696 小时）处实测连续距角为 354.7°，既没过 360、又覆盖了 −10° 那一支。
        val monthEnd = newMoon + 29 * DAY_MS
        // ⚠️ 分界点必须是 **e 过 180° 的实际时刻**，⛔ 不能拿"已发表望时刻"当分界：
        // 已发表值为 08-28 04:30 UT，而本文算法在 **04:20:46** 就越界了（早 9.2 分钟；
        // 锚点残差 +0.076° 正是这件事），拿 04:30 去当盈支的右端点会读到 −179.9° ⇒ 不变号。
        val full = crossing180(newMoon + MINUTE, monthEnd)
        for (a in 10..90 step 10) {
            val wax = solveElong(a.toDouble(), newMoon + MINUTE, full - MINUTE)
            val wan = solveElong(360.0 - a, full + MINUTE, monthEnd)
            val resolved = absDeg(MoonPhase.elongDeg(wax).toDouble() - a)
            assertTrue("$a°：盈支解出的距角偏离 $resolved > 0.01°", resolved <= 0.01)
            assertEquals("$a°：盈与亏的照度必须相等", MoonPhase.illum(wax), MoonPhase.illum(wan), 1e-4f)
            // 两个距角必须异号（这才是"对 e 对称"的被测对象）
            assertTrue("$a°：盈亏两支的距角必须异号", MoonPhase.elongDeg(wan) < 0f)
        }
    }

    /** **D4**：`w = |sin e|` 在 `e ∈ {0, ±180}` 处必须 ≈ 0，否则 §4.4 的退化保护是空话。 */
    @Test
    fun `D4 位置角权重在朔望处归零`() {
        for (e in floatArrayOf(0f, 180f, -180f)) {
            val w = MoonPhase.paWeight(e)
            assertTrue("e=$e 处权重应 < 1e-6，实为 $w", w < 1e-6f)
        }
        // 另一端必须满权重，否则"归零"只是因为权重恒为 0
        assertEquals("e=+90 处权重应为 1", 1f, MoonPhase.paWeight(90f), 1e-6f)
        assertEquals("e=−90 处权重应为 1", 1f, MoonPhase.paWeight(-90f), 1e-6f)

        // 加权后的倾角：朔望处必须**趋于** 0。⛔ 不能断言"恰为 0"—— `sin(π)` 在浮点下是
        // 1.22e-16 而非 0，`PA=−77°、e=180°` 实算给 −9.4e-15（本条首次实跑就是这么挂的）。
        assertEquals(0f, MoonPhase.shadowTiltDeg(112f, 0f), 1e-12f)
        assertEquals(0f, MoonPhase.shadowTiltDeg(-77f, 180f), 1e-12f)
        assertTrue(abs(MoonPhase.shadowTiltDeg(112f, 0.05f)) < 0.15f)
        assertTrue(abs(MoonPhase.shadowTiltDeg(-77f, 179.95f)) < 0.15f)
        // 弦月处权重接近 1 ⇒ 倾角几乎原样取 PA
        assertEquals(30f, MoonPhase.shadowTiltDeg(30f, 90f), 1e-3f)
        // ⛔ 失稳的 PA 在朔望附近绝不能把倾角带大：拿 B 表那一夜（10-26，PA 一夜跳 112°）实测
        val ms = utc(2026, 10, 26, 13, 0)
        val tilt = abs(MoonPhase.shadowTiltDeg(MoonPhase.brightLimbPaDeg(ms), MoonPhase.elongDeg(ms)))
        assertTrue("10-26 加权后倾角 $tilt° 应 < 20°（§4.4 退化保护）", tilt < 20f)
    }

    /**
     * **D5**：`tiltDeg → 画布旋转` 的 `−(tilt + 90)` 偏置（⛔ 漏一个就整晚错 90° 或左右镜像，
     * §十三 V3）。同时断言符号方向：正 tilt 必须给更负的角。
     *
     * ⚠️ 单位是**度**：`android.graphics.Canvas.rotate` 收度数，原型那句 `× DEG_TO_RAD`
     * 是 HTML canvas 的 API 差异，不是算法规格（§4.4 末 / §十一 T5 落地偏离）。
     */
    @Test
    fun `D5 canvas 旋转必须取负并加九十度`() {
        assertEquals(-90f, MoonPhase.terminatorRotDeg(0f), 1e-6f)
        assertEquals(-180f, MoonPhase.terminatorRotDeg(90f), 1e-6f)
        assertEquals(0f, MoonPhase.terminatorRotDeg(-90f), 1e-6f)
        assertTrue(MoonPhase.terminatorRotDeg(45f) < MoonPhase.terminatorRotDeg(-45f))
    }

    /**
     * **D6**：距角符号在望处翻转，且盈、亏两支各占半个朔望月。
     *
     * 半程比取 0.47..0.53：实测 2026-08-12→09-11 这一月的望在 15.44 日 / 全月 29.42 日
     * = **0.525**（偏心率让上半月可以长达 15.5 日），⛔ 不是 0.5 —— 断言 `== 0.5` 是错的。
     */
    @Test
    fun `D6 距角符号在望处翻转且盈亏各占半朔望月`() {
        val newMoon = utc(2026, 8, 12, 17, 50)
        val nextNew = utc(2026, 9, 11, 3, 50)
        val full = crossing180(newMoon + MINUTE, newMoon + 29 * DAY_MS)   // 见 D3 的端点说明
        val halfRatio = (full - newMoon).toDouble() / (nextNew - newMoon)
        assertTrue("望位于朔后 $halfRatio 半月比，应 0.47..0.53", halfRatio in 0.47..0.53)

        var h = 2L
        while (newMoon + h * HOUR_MS < full - HOUR_MS) {
            assertTrue("朔后第 $h 小时应盈", MoonPhase.isWaxing(newMoon + h * HOUR_MS))
            h += 6
        }
        h = 2L
        while (full + h * HOUR_MS < nextNew - HOUR_MS) {
            assertFalse("望后第 $h 小时应亏", MoonPhase.isWaxing(full + h * HOUR_MS))
            h += 6
        }
    }

    // ══════════════════════ 工具 ══════════════════════

    /** 收进 −180..180 后取绝对值：角差比较专用（⛔ 直接 `abs(a − b)` 在跨 ±180 时会算出 350°）。 */
    private fun absDeg(deg: Double): Double {
        val wrapped = ((deg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return abs(wrapped)
    }

    /** UTC 时刻 → Unix 毫秒。⚠️ 用 `GregorianCalendar` + 显式 UTC 时区，⛔ 不用 `java.time`（minSdk 22）。 */
    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    /** §4.6 B：北京 21:00 = UTC 13:00，入参形如 `2026-10-05` */
    private fun bj2100(date: String): Long {
        val p = date.split('-')
        return utc(p[0].toInt(), p[1].toInt(), p[2].toInt(), 13, 0)
    }

    /** C 表的"北京时刻"（形如 `07-29 22:30`，年份恒 2026）→ UTC 毫秒 */
    private fun beijing(label: String): Long {
        val md = label.substring(0, 5).split('-')
        val hm = label.substring(6).split(':')
        val bjMs = utc(2026, md[0].toInt(), md[1].toInt(), hm[0].toInt(), hm[1].toInt())
        return bjMs - 8 * HOUR_MS
    }

    /**
     * 连续距角 ∈ 0..360。⛔ 二分时不能用 [MoonPhase.elongDeg]：它在望处从 +180 跳到 −180，
     * 区间两端点会同号 ⇒ "永远不变号"（本类的 D3 首次实跑就是这么挂的）。
     */
    private fun elongContinuous(ms: Long): Double {
        val e = MoonPhase.elongDeg(ms).toDouble()
        return if (e < 0) e + 360.0 else e
    }

    /** 在 `[lo, hi]` 上二分求 [elongContinuous] == `target`（区间内必须单调且变号）。 */
    private fun solveElong(target: Double, lo: Long, hi: Long): Long {
        fun f(x: Long) = elongContinuous(x) - target
        var a = lo
        var b = hi
        check(f(a) * f(b) < 0) { "[$lo, $hi] 上 $target° 不变号" }
        repeat(60) {
            val m = a + (b - a) / 2
            if (f(a) * f(m) <= 0) b = m else a = m
        }
        return (a + b) / 2
    }

    /** 望的精确时刻（`e` 连续穿过 180°），作为盈支 / 亏支的分界。 */
    private fun crossing180(lo: Long, hi: Long): Long = solveElong(180.0, lo, hi)

    // ══════════════════════ 测试向量 ══════════════════════

    private data class Anchor(val name: String, val desc: String, val utcMs: Long, val expected: Float)

    /** §4.1 / §4.6 A：六个已发表天象的极大时刻（UTC） */
    private val ANCHORS = listOf(
        Anchor("2000-01-06", "新月", utc(2000, 1, 6, 18, 14), 0f),
        Anchor("2024-04-08", "全食带日全食", utc(2024, 4, 8, 18, 17), 0f),
        Anchor("2026-02-17", "环食", utc(2026, 2, 17, 17, 0), 0f),
        Anchor("2026-03-03", "月全食", utc(2026, 3, 3, 11, 4), 180f),
        Anchor("2026-08-12", "日全食", utc(2026, 8, 12, 17, 50), 0f),
        Anchor("2026-08-28", "月偏食", utc(2026, 8, 28, 4, 30), 180f),
    )

    /** §4.6 B 八行，逐字照抄（`elongMixed` 列是混合口径，比较必须转角差） */
    private data class Vector(
        val date: String,
        val illum: Float,
        val elongMixed: Float,
        val pa: Float,
        val ageDays: Float,
        val libLon: Float,
        val libLat: Float,
    )

    private val VECTORS = listOf(
        Vector("2026-10-05", 0.280f, -63.9f, 106.1f, 24.29f, 3.44f, -0.35f),
        Vector("2026-10-08", 0.051f, -26.0f, 109.6f, 27.39f, 4.74f, 2.26f),
        Vector("2026-10-11", 0.008f, 10.2f, -46.8f, 0.84f, 4.46f, 4.34f),
        Vector("2026-10-14", 0.146f, 44.9f, -74.8f, 3.68f, 2.54f, 4.93f),
        Vector("2026-10-18", 0.488f, 88.6f, -100.1f, 7.27f, -2.48f, 3.05f),
        Vector("2026-10-21", 0.765f, 122.0f, -110.5f, 10.00f, -5.67f, 0.48f),
        Vector("2026-10-26", 0.998f, 184.8f, 26.0f, 15.16f, -3.78f, -2.65f),
        Vector("2026-10-29", 0.849f, 225.7f, 81.7f, 18.51f, 0.62f, -2.42f),
    )

    private val SHUO = "朔"

    /** §4.6 C 二十五组（北京时刻，按时间顺序；`07-29 22:30` 是 v1.1 修正值） */
    private val SYZYGIES = listOf(
        "01-03 18:10" to "望", "01-19 03:40" to SHUO, "02-02 06:10" to "望", "02-17 19:50" to SHUO,
        "03-03 19:40" to "望", "03-19 09:10" to SHUO, "04-02 10:20" to "望", "04-17 19:40" to SHUO,
        "05-02 01:30" to "望", "05-17 04:00" to SHUO, "05-31 16:50" to "望", "06-15 11:00" to SHUO,
        "06-30 07:50" to "望", "07-14 18:00" to SHUO, "07-29 22:30" to "望", "08-13 01:50" to SHUO,
        "08-28 12:30" to "望", "09-11 11:50" to SHUO, "09-27 01:00" to "望", "10-11 00:30" to SHUO,
        "10-26 12:30" to "望", "11-09 15:40" to SHUO, "11-24 23:20" to "望", "12-09 09:20" to SHUO,
        "12-24 09:40" to "望",
    )

    private companion object {
        private const val MINUTE = 60_000L
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        private const val MS_PER_DAY = 86_400_000.0

        /** 负向自证 D2 用：Jan 0.0 = 1999-12-31 00:00 UT */
        private const val JAN_0_0_MS = 946_627_200_000L
    }
}

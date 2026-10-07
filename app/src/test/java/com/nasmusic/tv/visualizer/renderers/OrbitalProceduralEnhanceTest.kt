package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * E29 轨道（太阳系）**程序化质感增强**不变式门禁（`docs/archive/solar-system-upgrade-plan.md` 功能 ①–⑥）。
 *
 * 六项增强的可验算契约：
 *  - **① 晨昏线**：夜侧方位角必须指向**背光一侧**（四象限 + 随机抽样全验），夜色必须相对
 *    原色压暗（同色叠加 = 像素不变，等于没画）；
 *  - **② 大气边缘光**：光晕半径系数必须 > 1（否则峰值落在盘内被盘体盖住 = 没画）；
 *  - **③ 木/土云带**：任意一条带在**扰动幅度上限**下仍须完整落在行星盘内（不靠 clip）；
 *  - **④ 太阳米粒组织**：半径调制量必须远小于 1（⛔ 原 WIP 用恒定系数把太阳整体缩小 8%）；
 *  - **⑤ 卡西尼缝**：内圈系数必须严格小于主环且留得出可见缝；
 *  - **⑥ 彗星**：出现时刻/时长/轨道参数全部来自确定性 hash ⇒ **可回放**；一颗不许跨窗口
 *    （跨窗口 ⇒ 相邻两颗重叠）；相邻间隔落在 24~44 s 且**非恒定**；远日点恒在画面外
 *    （进/出画都在画外 ⇒ 不会在画面内突现突灭）、近日点恒在画面内。
 *
 * ⛔ 三段规矩（沿用 [[OrbitalStarFieldTest]]）：
 *  - 数值 / 行为段**直调生产 API**（`hashUnit` / `cometStartAt` / `cometDuration` /
 *    `cometAnomaly` / `cometRadius` / `nightSideDegrees` / `bandLatCenter` / `bandThickness`
 *    / `bandChordFraction`），**不复制算法**；
 *  - 源码段一律**先剥注释**（判据会命中 KDoc 里举的反例 ⇒ 假 FAIL，本仓库已踩 5+ 次），
 *    并配「原文含 / 剥后不含」成对自证；
 *  - 每条负向自证与正向**喂同一份谓词**（可注入的实现），缺一条就可能空转。
 *
 * ⛔ **不构造 `OrbitalRingsRenderer()`** —— 字段初始化会建 Compose `Path()`
 *   （`android.graphics.Path`）⇒ JVM 单测抛 "not mocked"。
 */
class OrbitalProceduralEnhanceTest {

    /** 每帧函数（⑥ 的扫描面）—— 这些函数体内**一次堆分配都不许有** */
    private val perFrameFns = listOf(
        "draw", "drawPlanetAt", "drawComet", "drawCloudBands", "drawSaturnRing",
        "bakeTerminator", "cometPoint",
    )

    // ═══════════════════════ ① hash：确定性契约 ═══════════════════════

    @Test
    fun `① hashUnit - 同输入恒同输出、值域 0 到 1、跨盐位互不相关`() {
        val production = { i: Int, s: Int -> OrbitalRingsRenderer.hashUnit(i, s) }
        assertTrue("hashUnit 必须落在 [0,1)（区间外会让彗星起始延迟越界）", rangeOk(production, 0..2047))
        assertTrue("hashUnit 必须是纯函数（同 (i, salt) 重复调用得同值）", deterministicOk(0..2047))

        val salts = intArrayOf(
            OrbitalRingsRenderer.COMET_SALT_START, OrbitalRingsRenderer.COMET_SALT_DUR,
            OrbitalRingsRenderer.COMET_SALT_A, OrbitalRingsRenderer.COMET_SALT_E,
            OrbitalRingsRenderer.COMET_SALT_ROT, OrbitalRingsRenderer.COMET_SALT_DIR,
            OrbitalRingsRenderer.COMET_SALT_R, OrbitalRingsRenderer.COMET_SALT_BEND,
        )
        assertEquals("彗星必须用 8 路互不相同的参数源", 8, salts.toSet().size)
        assertTrue(
            "同一序号在不同盐位上必须取到互不相关的值（⛔ 忽略盐 ⇒ 起始延迟与轨道形状同相，" +
                "彗星会呈现肉眼可辨的规律性）",
            decorrelatedOk(table(salts, production))
        )

        // ⛔ 负向自证：同一份谓词喂三种退化实现
        assertFalse("负向自证：恒定 0.5 的 hash 必须被「去相关」判失败", decorrelatedOk(table(salts, { _, _ -> 0.5f })))
        assertFalse(
            "负向自证：忽略盐的实现（各路参数恒相同）必须被「去相关」判失败",
            decorrelatedOk(table(salts, { i, _ -> production(i, 901) }))
        )
        assertFalse("负向自证：值域越上界必须判失败", rangeOk({ i, s -> production(i, s) + 1f }, 0..15))
    }

    // ═══════════════════════ ② 彗星节律（时序） ═══════════════════════

    @Test
    fun `② 彗星节律 - 一颗不跨窗口、相邻间隔 24 到 44 秒且非恒定`() {
        val w = OrbitalRingsRenderer.COMET_WINDOW.toFloat()
        val n = 400
        val starts = FloatArray(n) { OrbitalRingsRenderer.cometStartAt(it) }
        val durs = FloatArray(n) { OrbitalRingsRenderer.cometDuration(it) }

        val startCap = OrbitalRingsRenderer.COMET_START_SPAN.toFloat()
        val durMin = OrbitalRingsRenderer.COMET_DUR_MIN.toFloat()
        val durCap = (OrbitalRingsRenderer.COMET_DUR_MIN + OrbitalRingsRenderer.COMET_DUR_SPAN).toFloat()
        assertTrue("起始延迟必须落在 [0, COMET_START_SPAN)", starts.all { it >= 0f && it < startCap })
        assertTrue("经过时长必须落在 [MIN, MIN+SPAN)", durs.all { it >= durMin && it < durCap })

        assertTrue(
            "⛔ 一颗彗星必须完全落在自己的窗口内 —— 跨窗口会让相邻两颗同时出现（重叠）",
            (0 until n).all { staysInWindow(starts[it], durs[it], w) }
        )
        assertFalse("负向自证：时长 46 秒（> 窗口 45）必须判失败", staysInWindow(0f, w + 1f, w))
        assertFalse("负向自证：起始延迟 + 时长越界必须判失败", staysInWindow(8.5f, 40f, w))

        val gaps = FloatArray(n - 1) { (w + starts[it + 1]) - (starts[it] + durs[it]) }
        assertTrue("相邻两颗的间隔必须落在 24~44 秒（= 45 − start − dur + start′ 的推论）",
            gaps.all { gapWithin(it, 24f, 44f) })
        assertFalse("负向自证：恒定 45 秒节律（无随机起始延迟）必须被判在区间外", gapWithin(45f, 24f, 44f))
        assertFalse("负向自证：0 秒（两颗同时）必须被判在区间外", gapWithin(0f, 24f, 44f))
        assertTrue("间隔必须真的在变（⛔ 非退化：固定节律会被肉眼识破，正是要避免的）",
            gaps.map { (it * 100).toInt() }.toSet().size > 50)
    }

    // ═══════════════════════ ③ 彗星轨道几何 ═══════════════════════

    @Test
    fun `③ 彗星轨道 - 远日点恒在画面外、近日点恒在画面内、开普勒单调`() {
        val aMin = OrbitalRingsRenderer.COMET_A_MIN
        val aCap = OrbitalRingsRenderer.COMET_A_MIN + OrbitalRingsRenderer.COMET_A_SPAN
        val eMin = OrbitalRingsRenderer.COMET_E_MIN
        val eCap = OrbitalRingsRenderer.COMET_E_MIN + OrbitalRingsRenderer.COMET_E_SPAN

        // 半长轴以「画面世界系最外半幅 M」为单位（drawComet 里 × max(半宽/s, 半高/(s·TILT))）。
        // 可见世界椭圆恒被半径 M 的圆包住 ⇒ r_apo > M 即**任意倾角都在画外**（与画幅无关）。
        assertTrue("远日点 r_apo/M 的最坏情形必须 > 1（进画/出画都在画面外 ⇒ 不会在画面内突现突灭）",
            apoapsisClearOk(aMin, eMin))
        assertTrue("近日点 r_peri/M 的最好情形必须 < 0.5（彗星必须扫进画面内侧，不许全程贴画外缘）",
            periapsisInsideOk(aCap, eMin))
        assertTrue("离心率必须显著大于 0（⛔ 近圆轨道看不出「划过」的加速度变化）",
            (eMin + eCap) / 2f > 0.5f)
        assertFalse("负向自证：半长轴系数降到 0.5 ⇒ 远日点落在画内（会被硬裁 ⇒ 闪烁）", apoapsisClearOk(0.5f, eMin))
        assertFalse("负向自证：离心率下限取 0（圆轨道）⇒ 远日点 = 半长轴，仍在画内", apoapsisClearOk(aMin, 0f))
        assertFalse("负向自证：半长轴系数 1.6 ⇒ 近日点也跑到画外缘", periapsisInsideOk(1.6f, eMin))

        // 生产开普勒实现：端点值 + 单调性 + 一阶近似的偏离界
        val a = 1.2f
        val e = 0.7f
        assertEquals("E=0 ⇒ r = a(1−e)（近日点）", a * (1f - e),
            OrbitalRingsRenderer.cometRadius(a, e, 0f), 1e-6f)
        assertEquals("E=π ⇒ r = a(1+e)（远日点）", a * (1f + e),
            OrbitalRingsRenderer.cometRadius(a, e, Math.PI.toFloat()), 1e-5f)
        assertTrue("E(M) 必须在 [0,2π) 单调递增（⛔ 非单调会让彗星来回抖动）",
            anomalyMonotoneOk({ m -> OrbitalRingsRenderer.cometAnomaly(m, e) }))
        assertTrue("|E − M| 必须 ≤ e（一阶开普勒近似的偏离界）",
            anomalyBoundOk(e, { m -> OrbitalRingsRenderer.cometAnomaly(m, e) }))
        assertFalse("负向自证：漏乘 e（E = M + sin M）会偏离到 1.0 ⇒ 必须判失败",
            anomalyBoundOk(e, { m -> m + sin(m) }))
        assertFalse("负向自证：E = −M（反向）必然破坏单调 ⇒ 必须判失败",
            anomalyMonotoneOk({ m -> -m }))
    }

    // ═══════════════════════ ④ 晨昏线（行为段） ═══════════════════════

    @Test
    fun `④ 晨昏线方位角 - 夜侧必须指向背光方向（四象限全验）`() {
        // (ldx, ldy) = 画面中心 − 行星位置 = 光源方向；夜侧取其**反向**
        assertEquals("行星在太阳右侧 ⇒ 夜侧朝右 = 0°", 0f,
            OrbitalRingsRenderer.nightSideDegrees(-1f, 0f), 1e-4f)
        assertEquals("行星在太阳左侧 ⇒ 夜侧朝左 = |180°|", 180f,
            abs(OrbitalRingsRenderer.nightSideDegrees(1f, 0f)), 1e-4f)
        assertEquals("行星在太阳下方 ⇒ 夜侧朝下 = +90°（canvas y 向下、顺时针为正）", 90f,
            OrbitalRingsRenderer.nightSideDegrees(0f, -1f), 1e-4f)
        assertEquals("行星在太阳上方 ⇒ 夜侧朝上 = −90°", -90f,
            OrbitalRingsRenderer.nightSideDegrees(0f, 1f), 1e-4f)

        val production = { ldx: Float, ldy: Float -> OrbitalRingsRenderer.nightSideDegrees(ldx, ldy) }
        assertTrue("旋转该角度后单位圆 +x 必须与「行星 − 太阳」同向（含全部四象限抽样）",
            nightVectorMatches(production))
        assertTrue("方位角必须落在 (−180, 180]", degreesRangeOk(production))
        // ⛔ 负向：不取反向会把夜侧画在**日侧** = 遮罩盖住被照亮的半球
        assertFalse("负向自证：把夜方向算成日方向必须被判失败",
            nightVectorMatches({ ldx, ldy -> Math.toDegrees(kotlin.math.atan2(ldy.toDouble(), ldx.toDouble())).toFloat() }))
        assertFalse("负向自证：x/y 分量写反必须被判失败",
            nightVectorMatches({ ldx, ldy -> OrbitalRingsRenderer.nightSideDegrees(ldy, ldx) }))
    }

    @Test
    fun `④b 夜侧颜色必须相对原色压暗（同色叠加等于没画）`() {
        val shade = OrbitalRingsRenderer.PLANET_NIGHT_SHADE
        val alpha = OrbitalRingsRenderer.PLANET_NIGHT_ALPHA
        assertTrue("压暗系数必须 < 0.6 且 alpha 可见（⛔ shade=1 即原色叠加 ⇒ 像素不变、白画一遍）",
            nightShadeOk(shade, alpha))
        assertEquals("夜侧 alpha 必须与设计值 0.6 一致", 0.6f, alpha, 1e-6f)
        assertEquals("夜侧压暗系数必须与设计值 0.34 一致", 0.34f, shade, 1e-6f)
        assertFalse("负向自证：shade = 1f（原色）必须被判失败", nightShadeOk(1f, alpha))
        assertFalse("负向自证：alpha = 0f（完全透明）必须被判失败", nightShadeOk(shade, 0f))
    }

    // ═══════════════════════ ⑤ 云带恒在行星盘内 ═══════════════════════

    @Test
    fun `⑤ 云带 - 含扰动上限仍完整落在圆盘内且带间留缝`() {
        val cases = listOf(
            BandCase("木星 HIGH", OrbitalRingsRenderer.JUPITER_BAND_COUNT,
                OrbitalRingsRenderer.JUPITER_BAND_LAT_TOP, OrbitalRingsRenderer.JUPITER_BAND_LAT_SPAN,
                OrbitalRingsRenderer.JUPITER_BAND_AMP),
            BandCase("木星 MEDIUM", OrbitalRingsRenderer.JUPITER_BAND_COUNT_MED,
                OrbitalRingsRenderer.JUPITER_BAND_LAT_TOP, OrbitalRingsRenderer.JUPITER_BAND_LAT_SPAN,
                OrbitalRingsRenderer.JUPITER_BAND_AMP),
            BandCase("土星 HIGH", OrbitalRingsRenderer.SATURN_BAND_COUNT,
                OrbitalRingsRenderer.SATURN_BAND_LAT_TOP, OrbitalRingsRenderer.SATURN_BAND_LAT_SPAN,
                OrbitalRingsRenderer.SATURN_BAND_AMP),
            BandCase("土星 MEDIUM", OrbitalRingsRenderer.SATURN_BAND_COUNT_MED,
                OrbitalRingsRenderer.SATURN_BAND_LAT_TOP, OrbitalRingsRenderer.SATURN_BAND_LAT_SPAN,
                OrbitalRingsRenderer.SATURN_BAND_AMP),
        )
        for (c in cases) {
            val thick = OrbitalRingsRenderer.bandThickness(c.count, c.latSpan)
            assertTrue("${c.name}：带厚必须 < 间距（⛔ 相等会连成一片，失去明暗相间的条纹）",
                thick < c.latSpan / c.count)
            var prev = -Float.MAX_VALUE
            for (i in 0 until c.count) {
                val lat = OrbitalRingsRenderer.bandLatCenter(c.count, i, c.latTop, c.latSpan)
                assertTrue("${c.name}：第 $i 条中心纬度必须递增", lat > prev)
                prev = lat
                val top = lat - thick * 0.5f
                val bot = lat + thick * 0.5f
                val chord = OrbitalRingsRenderer.bandChordFraction(top, bot)
                assertTrue(
                    "${c.name}：第 $i 条（lat=$lat chord=$chord）含扰动上限后必须仍在盘内（不靠 clip）",
                    bandInsideDisc(chord, max(abs(top), abs(bot)), c.amp)
                )
            }
            assertTrue(
                "${c.name}：首末条中心必须落在声明的纬度覆盖区内",
                OrbitalRingsRenderer.bandLatCenter(c.count, 0, c.latTop, c.latSpan) > c.latTop &&
                    OrbitalRingsRenderer.bandLatCenter(c.count, c.count - 1, c.latTop, c.latSpan) <
                    c.latTop + c.latSpan
            )
        }
        // 弦宽公式：赤道最宽、随纬度按 sqrt(1−lat²) 收缩
        assertEquals("赤道带弦宽 = BAND_WIDTH_K", OrbitalRingsRenderer.BAND_WIDTH_K,
            OrbitalRingsRenderer.bandChordFraction(0f, 0f), 1e-6f)
        assertEquals("lat=0.6 弦宽 = 0.8 × BAND_WIDTH_K",
            0.8f * OrbitalRingsRenderer.BAND_WIDTH_K,
            OrbitalRingsRenderer.bandChordFraction(0.6f, 0.6f), 1e-6f)
        // ⛔ 负向自证（同一份谓词）
        assertFalse("负向自证：赤道带不收缩（chord=1.3）必须判溢出", bandInsideDisc(1.3f, 0f, 0f))
        assertFalse("负向自证：BAND_WIDTH_K 取 1.0（不留边）叠加扰动后必须判溢出",
            bandInsideDisc(sqrt(1f - 0.4f * 0.4f), 0.4f, 0.055f))
    }

    @Test
    fun `⑤b 大气光晕与米粒组织与卡西尼缝的量纲契约`() {
        // ② 光晕半径必须**大于**行星盘，否则整圈渐变峰值落在盘内、被盘体盖住 = 没画
        val glowR = OrbitalRingsRenderer.ATMOSPHERE_GLOW_R
        assertTrue("光晕半径系数必须 > 1.05（边缘光峰值落在盘缘之外）", glowMarginOk(glowR))
        assertFalse("负向自证：系数 1.0（峰值压在盘缘上）必须判失败", glowMarginOk(1.0f))
        assertTrue("渐变峰值 alpha × 绘制 alpha 必须 ≤ 0.3（微光晕，不许变成白边）",
            OrbitalRingsRenderer.ATMOSPHERE_GLOW_PEAK_A * OrbitalRingsRenderer.ATMOSPHERE_GLOW_ALPHA <= 0.3f)

        // ④ 米粒组织只许围绕 sunR **波动**（⛔ 原 WIP 用恒定 edgeSoft 把太阳整体缩小 8%）
        val contrast = OrbitalRingsRenderer.SUN_GRANULE_CONTRAST
        val gain = OrbitalRingsRenderer.SUN_GRANULE_R_GAIN
        // 太阳噪声是四层正弦（权重和 = 1 ⇒ 值域 ±1）⇒ 半径摆动幅度 = contrast × gain
        assertTrue("半径摆动幅度必须远小于 1（±7% 以内）", sunWobbleOk(contrast, gain))
        assertFalse("负向自证：调制系数 0.5 ⇒ 半径摆动 ±17.5% 必须判失败", sunWobbleOk(contrast, 0.5f))
        assertEquals("米粒离散段数必须是 96（HIGH）", 96, OrbitalRingsRenderer.SUN_GRANULE_STEPS)

        // ⑤ 卡西尼缝 = 主环与内圈之间的空白 ⇒ 内圈系数必须严格小于 1 且留得出可见缝
        val innerK = OrbitalRingsRenderer.SATURN_RING_INNER_K
        assertTrue("卡西尼缝内圈系数必须落在 (0.5, 0.9)（太小 = 变成另一颗行星的环，太接近 1 = 缝看不见）",
            ringInnerOk(innerK))
        assertFalse("负向自证：内圈系数 1.0（与主环重合 = 没有缝）必须判失败", ringInnerOk(1f))
        assertFalse("负向自证：内圈系数 0.3（缩成第二条环带）必须判失败", ringInnerOk(0.3f))
    }

    // ═══════════════════════ ⑥ 逐帧零分配 / 禁 Random（源码段） ═══════════════════════

    @Test
    fun `⑥ 逐帧零分配 - 每帧函数内无 Random Path Brush Rect 数组与变换 lambda`() {
        val body = classBody(codeOfBatchTwo(), "OrbitalRingsRenderer")
        assertTrue("类体必须能切出来（空转自证）", body.isNotEmpty())
        for (fn in perFrameFns) {
            val src = funBody(body, fn)
            assertTrue("$fn 必须能切出来（空转自证）", src.isNotEmpty())
            assertTrue("⛔ $fn 内不得出现堆分配原语或 Random", noAllocOk(src))
        }
        // 成对自证：同一份谓词喂「含这些写法」的片段必须判失败
        assertFalse("负向自证：逐帧 new Path() 必须被判失败", noAllocOk("val p = Path(); p.moveTo(0f, 0f)"))
        assertFalse("负向自证：逐帧 Brush.radialGradient 必须被判失败", noAllocOk("Brush.radialGradient(0f to Color.Red)"))
        assertFalse("负向自证：使用 Random 必须被判失败", noAllocOk("val t = Random.nextFloat()"))
        assertFalse("负向自证：withTransform 捕获 lambda 必须被判失败", noAllocOk("withTransform({ scale(1f, 1f) }) { }"))
        assertFalse("负向自证：arrayOf 逐帧装箱必须被判失败", noAllocOk("val pts = arrayOf(px, py)"))
    }

    @Test
    fun `⑥b 禁 Random 的守卫注释存在且不在代码里`() {
        val raw = codeOfBatchTwoRaw()
        val code = codeOfBatchTwo()
        assertTrue("源码必须写明「禁 Random」的约束（否则后来者会随手补一个随机数 ⇒ 闪烁）",
            raw.contains("禁 Random") || raw.contains("禁 `Random`"))
        assertFalse("⛔ 剥注释后的代码不得出现 Random（判据必须先剥注释，否则会命中 KDoc 正文）",
            Regex("""(?<![A-Za-z0-9_.])Random\b""").containsMatchIn(code))
        assertTrue("对照：原文确实含 Random 字样（说明上面的剥注释自证不是空转）", raw.contains("Random"))
    }

    // ═══════════════════════ ⑦ 生产接线 + 档位门控（源码段） ═══════════════════════

    @Test
    fun `⑦ 生产接线 - 六项增强都真的挂在每帧路径上`() {
        val body = classBody(codeOfBatchTwo(), "OrbitalRingsRenderer")
        val draw = funBody(body, "draw")
        val planet = funBody(body, "drawPlanetAt")
        val layout = funBody(body, "ensureLayout")
        assertTrue("draw / drawPlanetAt / ensureLayout 必须能切出来",
            draw.isNotEmpty() && planet.isNotEmpty() && layout.isNotEmpty())

        // ⑥ 彗星：draw() 末尾必须调用（画在行星之后 = 轨道前景）
        assertTrue("draw 必须调 drawComet(w, h, center, scale)", draw.contains("drawComet(w, h, center, scale)"))
        val comet = funBody(body, "drawComet")
        assertTrue("drawComet 必须复用成员 cometOrbitBuf / cometTailBuf",
            comet.contains("cometOrbitBuf.rewind()") && comet.contains("cometTailBuf.rewind()"))
        assertTrue("drawComet 必须走生产 hash 参数（⛔ 不许内联复制一份随机数）",
            comet.contains("cometStartAt(k)") && comet.contains("cometDuration(k)") &&
                comet.contains("hashUnit(k, COMET_SALT_A)"))
        assertTrue("drawComet 必须用生产开普勒纯函数",
            comet.contains("cometAnomaly(") && comet.contains("cometRadius("))
        assertTrue("彗尾方向必须背日（尾向 = 彗星 − 画面中心）", comet.contains("pos.x - center.x"))
        assertTrue("彗星轨道必须过 project（含 TILT，与行星轨道同一倾斜约定）",
            funBody(body, "cometPoint").contains("project("))

        // ① 晨昏线：遮罩预烘、逐帧只摆原生变换；且必须画在**地表细节之后**
        assertTrue("drawPlanetAt 必须调 bakeTerminator()", planet.contains("bakeTerminator()"))
        assertTrue("晨昏线必须走原生 canvas 变换（无捕获 lambda）",
            planet.contains("cvs.save()") && planet.contains("cvs.translate(px, py)") &&
                planet.contains("cvs.rotate(nightSideDegrees(ldx, ldy))") &&
                planet.contains("cvs.scale(pr, pr)") && planet.contains("cvs.restore()"))
        val bake = funBody(body, "bakeTerminator")
        assertTrue("遮罩必须是单位圆 + 蒙影鼓出量（硬切直径 = 假立体感）",
            bake.contains("PLANET_TERMINATOR_SMOOTH * cos(a)"))
        assertTrue("遮罩只烘一次（幂等守卫）", bake.contains("if (terminatorBaked) return"))
        val detailIdx = planet.indexOf("drawCloudBands(")
        val nightIdx = planet.indexOf("drawPath(terminatorPath")
        assertTrue("⛔ 夜侧遮罩必须画在云带等**地表细节之后**（否则夜侧细节仍全亮）",
            detailIdx in 0 until nightIdx)

        // ② 大气边缘光：Brush 必须在 ensureLayout 构造期建好，逐帧只消费缓存
        assertTrue("ensureLayout 必须缓存 atmoBrush", layout.contains("atmoBrush = Brush.radialGradient("))
        assertTrue("drawPlanetAt 只许消费缓存", planet.contains("val ab = atmoBrush"))

        // ③ 云带：木星 + 土星各一次，条数按档位收敛
        assertEquals("云带必须接线两处（木星 + 土星）", 2, Regex("drawCloudBands\\(").findAll(planet).count())
        assertTrue("云带条数必须按档位切换",
            planet.contains("if (tier == 2) JUPITER_BAND_COUNT else JUPITER_BAND_COUNT_MED") &&
                planet.contains("if (tier == 2) SATURN_BAND_COUNT else SATURN_BAND_COUNT_MED"))

        // ④ 太阳米粒：HIGH 用 bandBuf 生成噪声圆，且保留亮核第二次提交
        assertTrue("HIGH 必须走 bandBuf 噪声圆",
            draw.contains("bandBuf.rewind()") && draw.contains("SUN_GRANULE_STEPS"))
        assertTrue("必须保留 sunCoreHot 亮核（维持原有视觉层级）", draw.contains("sunCoreHot"))

        // ⑤ 卡西尼缝：内圈必须用 moveTo 起独立子路径（⛔ lineTo 续接会多一条径向杂线）
        val ring = funBody(body, "drawSaturnRing")
        assertEquals("主环与内圈各自以 moveTo 起子路径（共 2 处）", 2,
            Regex("if \\(k == 0\\) ringBuf\\.moveTo\\(x, y\\) else ringBuf\\.lineTo\\(x, y\\)").findAll(ring).count())
        assertTrue("内圈半径必须过 SATURN_RING_INNER_K", ring.contains("rx * SATURN_RING_INNER_K"))
        assertEquals("整条环（含内圈）仍只提交一次", 1, Regex("drawPath\\(ringBuf").findAll(ring).count())
    }

    @Test
    fun `⑦b 档位门控 - 每项增强的生效档位与设计一致`() {
        val planet = funBody(classBody(codeOfBatchTwo(), "OrbitalRingsRenderer"), "drawPlanetAt")
        val draw = funBody(classBody(codeOfBatchTwo(), "OrbitalRingsRenderer"), "draw")
        val comet = funBody(classBody(codeOfBatchTwo(), "OrbitalRingsRenderer"), "drawComet")

        // MEDIUM 起：① 晨昏线、③ 云带、⑥ 彗星轨道弧
        assertTrue("晨昏线必须受 tier > 0 门控",
            Regex("""if \(tier > 0\) \{\s*val ldx = center\.x - px""").containsMatchIn(planet))
        assertTrue("云带必须受 tier > 0 门控（LOW 跳过行星细节）",
            Regex("""if \(tier > 0\) \{\s*when \(idx\)""").containsMatchIn(planet))
        assertTrue("彗星轨道弧必须受 tier > 0 门控", Regex("""if \(tier > 0\) \{\s*val steps""").containsMatchIn(comet))
        // 仅 HIGH：② 大气边缘光、④ 米粒组织、⑤ 卡西尼缝、彗头光晕
        assertTrue("大气边缘光必须只吃 tier == 2",
            Regex("""if \(tier == 2\) \{\s*val ab = atmoBrush""").containsMatchIn(planet))
        assertTrue("太阳米粒必须只吃 tier == 2", Regex("""if \(tier == 2\) \{\s*bandBuf\.rewind\(\)""").containsMatchIn(draw))
        assertTrue("卡西尼缝必须只吃 tier == 2",
            Regex("""if \(tier == 2\) \{\s*val rxIn""").containsMatchIn(funBody(
                classBody(codeOfBatchTwo(), "OrbitalRingsRenderer"), "drawSaturnRing")))
        assertTrue("彗头光晕必须只吃 tier == 2",
            Regex("""if \(tier == 2\) \{\s*drawCircle\(cometTailColor""").containsMatchIn(comet))
        assertFalse("⛔ 每帧函数一律不许用 DrawScope 的块变换 lambda（有捕获 ⇒ 分配）",
            planet.contains("withTransform") || comet.contains("withTransform") || draw.contains("withTransform"))
    }

    // ═══════════════════════ 谓词（正向 / 负向**同一份实现接口**） ═══════════════════════

    private class BandCase(val name: String, val count: Int, val latTop: Float, val latSpan: Float, val amp: Float)

    /** ① 值域：所有 (i, salt) 组合都落在 [0,1) */
    private fun rangeOk(fn: (Int, Int) -> Float, kRange: IntRange): Boolean {
        for (i in kRange) {
            var salt = 900
            while (salt < 950) {
                val v = fn(i, salt)
                if (v < 0f || v >= 1f) return false
                salt++
            }
        }
        return true
    }

    /** ① 纯函数：重复调用逐位相同（彗星出现时刻必须可回放） */
    private fun deterministicOk(kRange: IntRange): Boolean {
        for (i in kRange) {
            if (OrbitalRingsRenderer.hashUnit(i, 901) != OrbitalRingsRenderer.hashUnit(i, 901)) return false
            if (OrbitalRingsRenderer.cometStartAt(i) != OrbitalRingsRenderer.cometStartAt(i)) return false
            if (OrbitalRingsRenderer.cometDuration(i) != OrbitalRingsRenderer.cometDuration(i)) return false
        }
        return true
    }

    /** 各盐位取同一批序号的取值表（正向与负向共用同一份结构） */
    private fun table(salts: IntArray, fn: (Int, Int) -> Float): Array<FloatArray> =
        Array(salts.size) { s -> FloatArray(512) { i -> fn(i, salts[s]) } }

    /** ① 去相关：任取两路盐 ⇒ 平均差 ≥ 0.10 且单路散布 ≥ 0.5（常数 / 忽略盐都过不了） */
    private fun decorrelatedOk(tbl: Array<FloatArray>): Boolean {
        for (a in tbl.indices) {
            for (b in a + 1 until tbl.size) {
                var diffSum = 0.0
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                for (i in tbl[a].indices) {
                    diffSum += abs(tbl[a][i] - tbl[b][i])
                    if (tbl[a][i] < lo) lo = tbl[a][i]
                    if (tbl[a][i] > hi) hi = tbl[a][i]
                }
                if (diffSum / tbl[a].size < 0.10) return false
                if (hi - lo < 0.5f) return false
            }
        }
        return true
    }

    /** ② 一颗彗星完全落在窗口 [0, w) 内 */
    private fun staysInWindow(start: Float, dur: Float, w: Float): Boolean =
        start >= 0f && start + dur <= w

    /** ② 相邻间隔落在设计区间内 */
    private fun gapWithin(gap: Float, lo: Float, hi: Float): Boolean = gap >= lo && gap <= hi

    /** ③ 远日点 r_apo = a(1+e) 必须越过可见世界椭圆的外接圆半径 M */
    private fun apoapsisClearOk(aMin: Float, eMin: Float): Boolean = aMin * (1f + eMin) > 1f

    /** ③ 近日点 r_peri = a(1−e) 必须明显落回画面内（< 半幅之半） */
    private fun periapsisInsideOk(aCap: Float, eMin: Float): Boolean = aCap * (1f - eMin) < 0.5f

    /** ③ 一阶开普勒 E = M + e·sin M 在 [0,2π) 单调（彗星不许来回抖动） */
    private fun anomalyMonotoneOk(f: (Float) -> Float): Boolean {
        var prev = f(0f)
        var m = 0f
        while (m < 6.2832f) {
            val cur = f(m)
            if (cur < prev) return false
            prev = cur
            m += 0.001f
        }
        return true
    }

    /** ③ |E − M| ≤ e（一阶近似不许把彗星甩到别处） */
    private fun anomalyBoundOk(e: Float, f: (Float) -> Float): Boolean {
        var m = 0f
        while (m <= 6.2832f) {
            if (abs(f(m) - m) > e + 1e-5f) return false
            m += 0.01f
        }
        return true
    }

    /** ④ 角度值域 */
    private fun degreesRangeOk(deg: (Float, Float) -> Float): Boolean {
        for (i in 0 until 512) {
            val ldx = hashF(i) * 2f - 1f
            val ldy = hashF(i + 4096) * 2f - 1f
            if (abs(deg(ldx, ldy)) > 180.0001f) return false
        }
        return true
    }

    /**
     * ④ `canvas.rotate(deg)` 把单位 +x 映到 `(cos, sin)`，该方向必须等于**背日方向**
     * `−(画面中心 − 行星) / |…|`（即 `行星 − 太阳`）。
     */
    private fun nightVectorMatches(deg: (Float, Float) -> Float): Boolean {
        for (i in 0 until 512) {
            val ldx = hashF(i) * 2f - 1f
            val ldy = hashF(i + 8192) * 2f - 1f
            if (abs(ldx) < 0.02f && abs(ldy) < 0.02f) continue
            val len = sqrt(ldx * ldx + ldy * ldy)
            val rad = Math.toRadians(deg(ldx, ldy).toDouble())
            if (abs(cos(rad.toFloat()) - (-ldx / len)) > 1e-3f) return false
            if (abs(sin(rad.toFloat()) - (-ldy / len)) > 1e-3f) return false
        }
        return true
    }

    private fun hashF(i: Int): Float = OrbitalRingsRenderer.hashUnit(i, 7)

    /** ④b 夜侧颜色：既压暗又有可见 alpha */
    private fun nightShadeOk(shade: Float, alpha: Float): Boolean = shade < 0.6f && alpha > 0.2f

    /** ⑤ 云带在扰动幅度上限下仍在单位圆盘内（赤道方向 x 与极方向 y 同时取到最大） */
    private fun bandInsideDisc(chord: Float, lim: Float, amp: Float): Boolean =
        chord * chord + (lim + amp) * (lim + amp) <= 1f

    /** ⑤b 光晕半径必须大于行星盘 */
    private fun glowMarginOk(glowR: Float): Boolean = glowR > 1.05f

    /** ④ 太阳半径摆动幅度 = CONTRAST × R_GAIN（bandWave 权重和 = 1 ⇒ 值域 ±1） */
    private fun sunWobbleOk(contrast: Float, gain: Float): Boolean = contrast * gain < 0.1f

    /** ⑤ 卡西尼缝内圈系数 */
    private fun ringInnerOk(k: Float): Boolean = k > 0.5f && k < 0.9f

    /** ⑥ 零分配 / 禁 Random（词边界，⛔ 不误伤 `drawRect(` / 成员 `bandBuf`） */
    private fun noAllocOk(src: String): Boolean =
        !Regex("""(?<![A-Za-z0-9_])Rect\s*\(""").containsMatchIn(src) &&
            !Regex("""(?<![A-Za-z0-9_])Path\s*\(""").containsMatchIn(src) &&
            !Regex("""(?<![A-Za-z0-9_.])Random\b""").containsMatchIn(src) &&
            !Regex("""(?<![A-Za-z0-9_.])Brush\.""").containsMatchIn(src) &&
            !Regex("""(?<![A-Za-z0-9_.])radialGradient""").containsMatchIn(src) &&
            !src.contains("withTransform") &&
            !src.contains("arrayOf(") &&
            !src.contains("listOf(") &&
            !src.contains("mutableListOf") &&
            !src.contains("floatArrayOf") &&
            !src.contains("remember {")

    // ═══════════════════════ 源码读取辅助（与 OrbitalStarFieldTest 同一套） ═══════════════════════

    /** 切出 `class <name>` 的类体（到下一个顶层 class / 文件尾） */
    private fun classBody(txt: String, name: String): String {
        val m = Regex("""(?m)^\s*(?:(?:internal|open|abstract|private)\s+)*class\s+$name\b""").find(txt)
            ?: return ""
        val rest = txt.substring(m.range.last + 1)
        val nxt = Regex("""(?m)^\s*(?:(?:internal|open|abstract|private)\s+)*class\s+\w+""").find(rest)
        return if (nxt == null) {
            txt.substring(m.range.first)
        } else {
            txt.substring(m.range.first, m.range.last + 1 + nxt.range.first)
        }
    }

    /** 从**已剥注释**的类体里取某个函数的 `{...}` 体（签名要认 receiver `DrawScope.`） */
    private fun funBody(classBody: String, name: String): String {
        val m = Regex("""fun\s+(?:[A-Za-z0-9_.]+\.)?$name\s*\(""").find(classBody) ?: return ""
        val brace = classBody.indexOf('{', m.range.last)
        if (brace < 0) return ""
        var depth = 0
        var i = brace
        while (i < classBody.length) {
            when (classBody[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return classBody.substring(brace, i + 1)
                }
            }
            i++
        }
        return classBody.substring(brace)
    }

    /** 去注释（行注释 + 嵌套块注释 + 字符串感知）；⛔ 源码判据必须先剥注释 */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '"') {
                sb.append(c); i++
                while (i < src.length) {
                    sb.append(src[i])
                    if (src[i] == '\\' && i + 1 < src.length) {
                        sb.append(src[i + 1]); i += 2; continue
                    }
                    i++
                    if (src[i - 1] == '"') break
                }
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i < src.length && depth > 0) {
                    if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') {
                        depth++; i += 2
                    } else if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') {
                        depth--; i += 2
                    } else {
                        if (src[i] == '\n') sb.append('\n')
                        i++
                    }
                }
                continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun codeOfBatchTwo(): String = stripComments(codeOfBatchTwoRaw())

    private fun codeOfBatchTwoRaw(): String =
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/BatchTwoRenderers.kt").readText()

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        repeat(6) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 app/src/main/java")
    }
}

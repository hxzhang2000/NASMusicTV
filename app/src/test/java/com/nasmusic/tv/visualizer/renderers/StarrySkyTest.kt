package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.graphics.Color
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.SpectrumContract
import com.nasmusic.tv.visualizer.fx.FxLevel
import com.nasmusic.tv.visualizer.fx.ProceduralTexture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * E42 [VisualizerTheme.STAR_TRAILS]「星空星轨」门禁 —— 数值段 + 行为段 + 源码段三段式。
 *
 * - **数值段（①）**：参数表逐项锁死（物理 / 频率映射 / 天极 / 地景 / 底环 + 亮线段）；
 * - **行为段（②③④⑤）**：直调 `internal companion object` 里的**生产纯函数**
 *   （几何映射 / 时基 / 包络 / 亮线段 alpha 斜坡 / 山脊反解 / 枯枝方向）；
 * - **源码段（⑥⑦⑧⑨⑩）**：扫**已剥注释**的正文（禁物、零分配、模板方法、时基、资源、
 *   流星弧线、**累积缓冲机械已删除**、**弧线不得进降采样缓冲**）。
 *
 * ⛔ **v1.4 架构变更**：ping-pong 累积缓冲（`DST_OUT` 衰减 + 双位图 + 缓冲缩放）**整套删除**，
 *   换成「**底环（圆） + 亮线段（线上逐段描出的亮线）**」。原 ③ 段的 `decayAlphaFor` /
 *   `TRAIL_TAU_S` 断言作废，`⑧` 段新增两条锁死用户决定的门禁。
 *
 * ⛔ **绝不构造 `StarrySkyRenderer()`** —— 字段初始化会建 `android.graphics.Path` / `Paint` / `Bitmap`
 *   ⇒ JVM 单测抛 "not mocked"（同 `OrbitalStarFieldTest` 的纪律）。纯逻辑全部收敛在 companion。
 * ⛔ 源码判据一律**先剥注释**：本类的 KDoc 里**故意**举了 `RuntimeShader` / `ctx.nowMs` /
 *   `lineTo` / `Math.random()` / `DST_OUT` 等反例，不剥注释会全部假 FAIL（并加「原文含 / 剥后不含」成对自证）。
 * ⛔ 负向自证必须真的能挂，且尽量与正向**喂同一份谓词**（缺一条就可能空转）。
 */
class StarrySkyTest {

    private val C = StarrySkyRenderer

    /** 判据自证用的画幅（16:9 / 21:9 超宽 / 4:3 / 方 / 竖屏）。 */
    private val LANDSCAPES = arrayOf(
        floatArrayOf(1920f, 1080f),
        floatArrayOf(2560f, 1080f),
        floatArrayOf(1024f, 768f),
        floatArrayOf(1000f, 1000f),
        floatArrayOf(520f, 900f),
    )

    // ══════════════════════════════ ① 数值段 ══════════════════════════════

    @Test
    fun `① 参数表 核心物理与频率映射`() {
        assertEquals("天极自转角速度（度/秒）", 3.2f, C.ROT_DEG_PER_S, 1e-6f)
        assertEquals("鼓点闪光时间常数", 0.18f, C.FLARE_DECAY_S, 1e-6f)
        assertEquals("满幅扫掠角", 46f, C.MAX_SWEEP_DEG, 1e-6f)
        assertEquals("弧可见门限", 0.012f, C.SILENT_FLOOR, 1e-6f)
        assertEquals("内柱半径系数（v1.4：0.055 → 0.10）", 0.10f, C.R_INNER_K, 1e-6f)
        assertEquals("外柱半径系数", 0.78f, C.R_OUTER_K, 1e-6f)
        assertEquals("半径聚密指数", 0.72f, C.RADIUS_SHAPE, 1e-6f)
        assertEquals("弧色插值指数", 0.55f, C.COLOR_SHAPE, 1e-6f)
        assertEquals("包络起音时间常数", 0.025f, C.ATTACK_S, 1e-6f)
        assertEquals("包络释音时间常数", 0.24f, C.RELEASE_S, 1e-6f)
        // ⭐ 半径指数与色指数**必须不同**（外圈要更快转冷，否则读不出「核心白炽 / 外圈淡蓝」）
        assertNotEquals("色指数与半径指数相同 ⇒ 外圈不会更快转冷", C.RADIUS_SHAPE, C.COLOR_SHAPE, 1e-6f)
        // ⭐ 包络是「快攻 / 慢放」：攻 < 放，与 AudioSmoother 的方向相反（故不复用它）
        assertTrue("起音必须快于释音", C.ATTACK_S < C.RELEASE_S)
        // ⭐ v1.4：内环必须离天极**明显**留出暗核。0.055 让最内圈几乎压在极点上，
        //    叠加「几十帧同角度 Plus 叠加」把中心烧成死白 ⇒ 抬高到 0.10。
        assertTrue("内环半径必须 ≥ 0.09·minDim（中心留暗核）：${C.R_INNER_K}", C.R_INNER_K >= 0.09f)
        assertTrue("内环仍必须明显小于外环", C.R_INNER_K < C.R_OUTER_K * 0.5f)
    }

    @Test
    fun `① 参数表 天极画幅自适应与地景`() {
        assertEquals(0.70f, C.POLE_X_K, 1e-6f)
        assertEquals(0.52f, C.POLE_Y_K, 1e-6f)
        assertEquals(0.8f, C.LS_SPAN, 1e-6f)
        assertEquals(1.4f, C.NARROW_ASPECT, 1e-6f)
        assertEquals(0.96f, C.NARROW_FILL_K, 1e-6f)
        assertEquals("地平线 = 0.75h（地面占底部 1/4）", 0.75f, C.HORIZON_K, 1e-6f)
        assertEquals(0.40f, C.TREE_H_K, 1e-6f)
        assertEquals(0.40f, C.TREE_SPLIT_K, 1e-6f)
        assertEquals(0.050f, C.TREE_LEAN_TOP_K, 1e-6f)
        assertEquals(0.38f, C.TREE_LEAN_MID_K, 1e-6f)
        assertEquals(0.032f, C.TREE_HW_BASE_K, 1e-6f)
        assertEquals(0.026f, C.TREE_HW_MID_K, 1e-6f)
        assertEquals(0.011f, C.TREE_HW_TOP_K, 1e-6f)
        assertEquals(0.196f, C.TREE_BRANCH_REACH_K, 1e-6f)
        assertEquals(0.025f, C.TREE_EDGE_GAP_K, 1e-6f)
        // ⭐ 树干必须「下粗上细」的强烈收分
        assertTrue("根部半宽 > 中段 > 分叉处", C.TREE_HW_BASE_K > C.TREE_HW_MID_K && C.TREE_HW_MID_K > C.TREE_HW_TOP_K)
    }

    @Test
    fun `① 参数表 底环 亮线段 spray 视觉修饰`() {
        assertEquals(0.55f, C.STARFIELD_ALPHA, 1e-6f)
        assertEquals(0.30f, C.GLOW_BASE, 1e-6f)
        assertEquals(0.25f, C.GLOW_ENERGY, 1e-6f)
        assertEquals(1.15f, C.SKY_TINT_GAIN, 1e-6f)
        // —— 底环（「用细线画圆」）——
        assertEquals("底环线宽必须是 1.0 dp（细线）", 1.0f, C.RING_W, 1e-6f)
        // ⭐ v1.5：0.26 → 0.10（用户判「轨道线条可以不明显」⇒ 圆退到几乎看不见）
        assertEquals("底环 alpha（v1.5 已降到 0.10）", 0.10f, C.RING_ALPHA, 1e-6f)
        assertEquals("底环圈数必须与柱容量一致", 64, C.RING_BANDS)
        // —— 亮线段 hero（「线上有一段一段的描出来亮线」）——
        // ⭐ v1.5：4 → 3（省下 64 次 drawArc 换 spray 的 4 次 drawPath）
        assertEquals("亮线段必须切成 3 段子弧（v1.5）", 3, C.SEG_K)
        assertEquals("alpha 爬升指数（v1.5 未动）", 1.6f, C.SEG_GAMMA, 1e-6f)
        assertEquals("亮线段线宽下界（v1.5 未动）", 1.0f, C.SEG_W_MIN, 1e-6f)
        assertEquals("亮线段线宽增益（v1.5 未动）", 0.8f, C.SEG_W_GAIN, 1e-6f)
        // ⭐ 爬升指数必须 > 1（凹上升 ⇒ 越靠头越陡、尾巴早早沉回底环）
        assertTrue("SEG_GAMMA 必须 > 1", C.SEG_GAMMA > 1f)
        assertTrue("SEG_K 必须 ≥ 3（才读得出「一段一段」）", C.SEG_K >= 3)
        // ⭐ v1.4：缓冲三档（TRAIL_W_*）与 TRAIL_TAU_S 已随 ping-pong 一起删除 ——
        //    这条用「源码里不再出现它们」在 ⑧ 专条门禁里锁死（编译期引用会直接挂）。
    }

    @Test
    fun `① 参数表 spray 弧场 v1_5`() {
        assertEquals("HIGH 档每带 6 条 spray 弧", 6, C.SPRAY_PER_BAND_FULL)
        assertEquals("MEDIUM 档每带 5 条 spray 弧", 5, C.SPRAY_PER_BAND_LITE)
        assertEquals("LOW 档每带 3 条 spray 弧", 3, C.SPRAY_PER_BAND_OFF)
        // ⭐ 三档必须严格递减（否则画质切换后弧数反增，LOW 设备反而更贵）
        assertTrue(
            "三档必须严格递减（${C.SPRAY_PER_BAND_FULL}/${C.SPRAY_PER_BAND_LITE}/${C.SPRAY_PER_BAND_OFF}）",
            C.SPRAY_PER_BAND_FULL > C.SPRAY_PER_BAND_LITE && C.SPRAY_PER_BAND_LITE > C.SPRAY_PER_BAND_OFF,
        )
        // ⭐ 抖动必须 < 0.5：这是「带间半径不交叉」的唯一来源（sprayRadiusFor 的夹紧是第二道）
        assertEquals("半径抖动系数", 0.45f, C.SPRAY_RADIUS_JITTER, 1e-6f)
        assertTrue("⛔ SPRAY_RADIUS_JITTER 必须 < 0.5f（否则 spray 会跨到邻带）", C.SPRAY_RADIUS_JITTER < 0.5f)
        assertEquals("活跃门限", 0.28f, C.SPRAY_CUTOFF, 1e-6f)
        assertTrue("⛔ SPRAY_CUTOFF 必须落在 (0,1)（= 剔除比例）", C.SPRAY_CUTOFF > 0f && C.SPRAY_CUTOFF < 1f)
        assertEquals("扫掠角下界（度）", 4f, C.SPRAY_SWEEP_MIN_DEG, 1e-6f)
        assertEquals("扫掠角上界（度）", 26f, C.SPRAY_SWEEP_MAX_DEG, 1e-6f)
        assertTrue("扫掠角上界必须 > 下界", C.SPRAY_SWEEP_MAX_DEG > C.SPRAY_SWEEP_MIN_DEG)
        assertEquals("颜色桶数（= 每帧 drawPath 次数）", 4, C.SPRAY_BUCKETS)
        assertEquals("spray 线宽（dp）", 1.0f, C.SPRAY_W, 1e-6f)
        assertEquals("场亮度下界", 0.18f, C.SPRAY_ALPHA_MIN, 1e-6f)
        assertEquals("场亮度上界", 0.55f, C.SPRAY_ALPHA_MAX, 1e-6f)
        // ⭐ 下界必须 > 0：静默段也要留底噪，否则音乐一停整片场凭空消失
        assertTrue("⛔ SPRAY_ALPHA_MIN 必须 > 0（静默段不能全场消失）", C.SPRAY_ALPHA_MIN > 0f)
        assertTrue("场亮度上界必须 > 下界", C.SPRAY_ALPHA_MAX > C.SPRAY_ALPHA_MIN)
        // ⭐ 调色板：桶数与长度必须一致（少一个就是 NoSuchElement，少一个是静默缺色）
        assertEquals("调色板长度必须等于 SPRAY_BUCKETS", C.SPRAY_BUCKETS, C.SPRAY_COLORS.size)
        // ⭐ 每桶颜色必须**互不相同**（否则「颜色各异」读不出来，且门禁抓不到退化成单色）
        //    ⚠️ 比对整个 `Color.value`（ULong，ARGB 打包在**高** 32 位）——
        //    ⛔ 不可 `and 0xFFFFFFFFL`：那样把颜色位全部截掉，四个桶会恒被判为「相同」。
        val distinct = C.SPRAY_COLORS.map { it.value }.toSet()
        assertEquals("四个颜色桶必须互不相同（实测 ${C.SPRAY_COLORS.size - distinct.size} 处重复）", C.SPRAY_BUCKETS, distinct.size)
        // ⭐ 相位在参考图里以冷蓝为主 ⇒ 至少一桶必须是冷色（不是全暖）
        assertTrue("至少一桶必须是冷蓝系（否则读不出参考图的冷调）", C.SPRAY_COLORS.any { it.blue > it.red })
    }

    @Test
    fun `① sprayPerBandFor 三档映射且 HIGH 弧数最多`() {
        assertEquals("FULL 档", C.SPRAY_PER_BAND_FULL, C.sprayPerBandFor(FxLevel.FULL))
        assertEquals("LITE 档", C.SPRAY_PER_BAND_LITE, C.sprayPerBandFor(FxLevel.LITE))
        assertEquals("OFF 档", C.SPRAY_PER_BAND_OFF, C.sprayPerBandFor(FxLevel.OFF))
        // ⭐ 总弧数（门禁用来核对「每帧预算」的说法）
        assertEquals("HIGH 总弧数 = 64 × 6", 384, C.RING_BANDS * C.sprayPerBandFor(FxLevel.FULL))
        assertEquals("MEDIUM 总弧数 = 64 × 5", 320, C.RING_BANDS * C.sprayPerBandFor(FxLevel.LITE))
        assertEquals("LOW 总弧数 = 64 × 3", 192, C.RING_BANDS * C.sprayPerBandFor(FxLevel.OFF))
    }

    @Test
    fun `① 调色板与 ARGB 常量 逐通道核对`() {
        assertEquals(0xFFFFF7EA.toInt(), C.TRAIL_CORE_ARGB)
        assertEquals(0xFF8FA4DF.toInt(), C.TRAIL_COOL_ARGB)
        assertEquals(0xFF9FB0E8.toInt(), C.POLE_ARGB)
        assertEquals(0xFF0B0B12.toInt(), C.GROUND_ARGB)
        // ⭐ v1.6：天幕改为 9 档「两层 + 虚化平台」；位置表与「软/实」判据见 `② 天幕…` 一组，
        //    此处只核对共享位置表严格递增。
        val stops = C.SKY_STOP_POS
        for (i in 1 until stops.size) assertTrue("渐变位置必须递增：$i", stops[i] > stops[i - 1])
        assertEquals("冷色流星 = 暖白与天极色各半", C.mixArgb(C.TRAIL_CORE_ARGB, C.POLE_ARGB, 0.5f), C.METEOR_COLD_ARGB)
    }

    // ══════════ ② v1.7 天幕：⭐ 一整片低色差 3 档垂直渐变 ══════════

    /** 暗版 3 档的 gamma 感知亮度（供多组断言复用）。 */
    private fun dimLums(): FloatArray {
        val s = C.skyStopsDim()
        return FloatArray(s.size) { C.relativeLuminance(s[it].second) }
    }

    /** 亮版 3 档的 gamma 感知亮度。 */
    private fun brightLums(): FloatArray {
        val s = C.skyStopsBright()
        return FloatArray(s.size) { C.relativeLuminance(s[it].second) }
    }

    /**
     * ⭐ 竖直渐变在画高比例 `p` 处的实际颜色（sRGB 分段线性插值，与
     * `Brush.verticalGradient` 的插值口径一致）。⛔ 只用于"山脊以上那一段"的取样。
     */
    private fun skyAt(stops: Array<Pair<Float, Color>>, p: Float): Color {
        require(stops.size >= 2) { "色标不足 2 档，无法插值" }
        if (p <= stops[0].first) return stops[0].second
        if (p >= stops[stops.size - 1].first) return stops[stops.size - 1].second
        for (i in 1 until stops.size) {
            if (p <= stops[i].first) {
                val t = (p - stops[i - 1].first) / (stops[i].first - stops[i - 1].first)
                val a = stops[i - 1].second
                val b = stops[i].second
                return Color(
                    red = a.red + (b.red - a.red) * t,
                    green = a.green + (b.green - a.green) * t,
                    blue = a.blue + (b.blue - a.blue) * t,
                    alpha = 1f,
                )
            }
        }
        return stops.last().second
    }

    @Test
    fun `② 天幕恰好 3 档 位置严格递增且落在 0_1`() {
        val dim = C.skyStopsDim()
        val bright = C.skyStopsBright()
        assertEquals("暗版必须恰好 3 档", C.SKY_STOP_COUNT, dim.size)
        assertEquals("亮版必须恰好 3 档", C.SKY_STOP_COUNT, bright.size)
        assertEquals("SKY_STOP_COUNT 必须是 3", 3, C.SKY_STOP_COUNT)
        assertEquals("位置表长度必须等于色标数", C.SKY_STOP_COUNT, C.SKY_STOP_POS.size)
        for (i in dim.indices) {
            val p = dim[i].first
            assertTrue("位置必须落在 [0,1]（第 $i 档：$p）", p >= 0f && p <= 1f)
            if (i > 0) {
                assertTrue("位置必须严格递增（第 $i 档：${dim[i - 1].first} → $p）", p > dim[i - 1].first)
            }
            assertEquals("位置必须与共享位置表一致（第 $i 档）", C.SKY_STOP_POS[i], p, 0f)
        }
        assertEquals("首档必须从画顶开始", 0f, dim.first().first, 0f)
        assertEquals("末档必须收到画底", 1f, dim.last().first, 0f)
        assertEquals("⛔ 平台结构已删除：位置表不得多于 3 档", 3, C.SKY_STOP_POS.size)
    }

    @Test
    fun `② 天幕 契约① 暗版自上而下严格单调递增 - 无凹陷无平台无回落`() {
        val dim = C.skyStopsDim()
        val lums = dimLums()
        for (i in 1 until dim.size) {
            assertTrue(
                "亮度必须**严格**递增（第 $i 档 ${dim[i - 1].first}→${dim[i].first}：" +
                    "${lums[i - 1]} → ${lums[i]}）⇒ 无平台、无局部凹陷、无回落",
                lums[i] > lums[i - 1],
            )
        }
        // ⛔ 「平台 / 分层」结构的残留物必须真的没了（防止半吊子回流）
        for (gone in listOf("SKY_HAZE_A", "SKY_HAZE_B", "SKY_HAZE_C", "SKY_LAYER_TOP", "SKY_RAMP_IN", "SKY_RAMP_OUT")) {
            assertFalse("⛔ $gone 属于已判失败的「平台 + 分层」结构，必须已删除", codeOfE42().contains(gone))
        }
    }

    @Test
    fun `② 天幕 契约② 总色差小 - 顶到底亮度比不超过门限`() {
        // ⭐ 这就是所有者那句「渐变的色差不用太大」的可算形式。
        //    实测：暗版 3.095×、亮版 2.869×（门限 3.25）。
        val rDim = C.skyLightRatio(dimLums())
        val rBright = C.skyLightRatio(brightLums())
        assertTrue(
            "暗版顶→底亮度比必须 ≤ ${C.SKY_MAX_LIGHT_RATIO}（实测 $rDim）⇒ 整片渐变够平",
            rDim <= C.SKY_MAX_LIGHT_RATIO,
        )
        assertTrue(
            "亮版顶→底亮度比必须 ≤ ${C.SKY_MAX_LIGHT_RATIO}（实测 $rBright）⇒ 响度拉满时也不能变陡",
            rBright <= C.SKY_MAX_LIGHT_RATIO,
        )
        // ⚠️ **成对自证**：换回旧版「底部亮到近淡青紫」的表 ⇒ 同一判据必须判失败。
        //    旧 11 档实测 12.70×、旧 9 档 10.89×，都远超门限 ⇒ 这条断言不是空转。
        val oldBottomBright = C.relativeLuminance(Color(0xFF8C99E0))
        val oldTop = C.relativeLuminance(Color(0xFF070C1E))
        val oldRatio = oldBottomBright / oldTop
        assertTrue("⛔ 旧表在同一判据下必须判失败（实测比值 $oldRatio）⇒ 这条断言不是空转", oldRatio > C.SKY_MAX_LIGHT_RATIO)
    }

    @Test
    fun `② 天幕 契约③ 山脊以上必须够暗 - 读作天空而不是水面`() {
        // ⭐ 主人原话「天空下面是不是一片海？干脆不要了」—— 这条就是那道闸。
        //    `HORIZON_K` 以下被地景剪影遮住，但 `HORIZON_K → 1.00` **仍有一部分可见**。
        for ((tag, stops) in listOf("暗版" to C.skyStopsDim(), "亮版" to C.skyStopsBright())) {
            var maxLin = 0f
            var at = 0f
            var i = 0
            while (i <= 200) {
                val p = C.HORIZON_K + i * (1f - C.HORIZON_K) / 200f
                val l = C.linearLuminance(skyAt(stops, p))
                if (l > maxLin) { maxLin = l; at = p }
                i++
            }
            assertTrue(
                "$tag 山脊以上（${C.HORIZON_K}→1.0）线性亮度必须 < ${C.SKY_MAX_VISIBLE_LIN}" +
                    "（最亮在 p=$at，实测 $maxLin）⇒ 读作天空",
                maxLin < C.SKY_MAX_VISIBLE_LIN,
            )
        }
        // ⚠️ **成对自证**：旧表的可见段是近淡青紫（线性 0.21–0.37）⇒ 必须判失败。
        val oldVisible = C.linearLuminance(Color(0xFF8C99E0))
        assertTrue(
            "⛔ 旧的画底色在同一判据下必须判失败（实测线性 $oldVisible）⇒ 这条断言不是空转",
            oldVisible > C.SKY_MAX_VISIBLE_LIN,
        )
    }

    @Test
    fun `② 天幕 契约④ 无横向色阶 - 只能是竖直渐变`() {
        val src = codeOfE42()
        val rebuild = funBody(src, "rebuildGeometry")
        // ⭐ 「无横向色阶」的机器形式：天幕只能用 `verticalGradient`，⛔ 不得有横向/斜向渐变。
        assertEquals(
            "天幕必须恰好 2 次 verticalGradient（暗版 + 亮版）",
            2, Regex("""Brush\.verticalGradient\(""").findAll(rebuild).count(),
        )
        for (banned in listOf("horizontalGradient", "sweepGradient", "Brush.radialGradient(\n            0f to SKY")) {
            assertFalse("⛔ 天幕不得用 $banned（会引入横向色阶）", rebuild.contains(banned))
        }
    }

    @Test
    fun `② 天幕 亮版位置与暗版逐档相同 且每档不暗于暗版 且上方提亮更多`() {
        val dim = C.skyStopsDim()
        val bright = C.skyStopsBright()
        var strictlyLighter = 0
        for (i in dim.indices) {
            assertEquals("亮版位置必须与暗版相同（第 $i 档）", dim[i].first, bright[i].first, 0f)
            val yd = C.relativeLuminance(dim[i].second)
            val yb = C.relativeLuminance(bright[i].second)
            assertTrue("亮版每档必须不暗于暗版（第 $i 档：$yb < $yd）", yb >= yd - 1e-7f)
            if (yb > yd + 1e-7f) strictlyLighter++
        }
        assertTrue("至少 2 档必须**严格**变亮（实测 $strictlyLighter 档）", strictlyLighter >= 2)
        // ⭐ 相对提亮必须**自上而下递减** ⇒ 响度大时「上面先亮起来」。
        //    ⚠️ 旧版是反向的（越靠地平线提亮越多）—— 那正是「地平线先亮 ⇒ 读作海」的成因之一。
        val gains = dim.indices.map {
            (C.relativeLuminance(bright[it].second) - C.relativeLuminance(dim[it].second)) /
                C.relativeLuminance(dim[it].second)
        }
        for (i in 1 until gains.size) {
            assertTrue(
                "亮版相对提亮必须自上而下递减（第 $i 档 ${(gains[i - 1] * 100).toInt()}% → " +
                    "${(gains[i] * 100).toInt()}%）⇒ 上面先亮",
                gains[i] < gains[i - 1],
            )
        }
    }

    @Test
    fun `⑧ 天幕 Brush 只在 rebuildGeometry 构建 逐帧 2 次 drawRect 且无 shader 模糊`() {
        val src = codeOfE42()
        val rebuild = funBody(src, "rebuildGeometry")
        val draw = funBody(src, "drawContent")
        assertTrue("rebuildGeometry 必须能切出来（空转自证）", rebuild.isNotEmpty())
        assertTrue("drawContent 必须能切出来（空转自证）", draw.isNotEmpty())
        // ⭐ 构建只在尺寸/密度守卫的重建路径里
        assertTrue("暗版天幕必须在 rebuildGeometry 构建", rebuild.contains("skyStopsDim()"))
        assertTrue("亮版天幕必须在 rebuildGeometry 构建", rebuild.contains("skyStopsBright()"))
        assertFalse("⛔ drawContent 不得构建天幕 Brush", draw.contains("Brush.verticalGradient("))
        assertFalse("⛔ drawContent 不得调 skyStopsDim/Bright", draw.contains("skyStopsDim()") || draw.contains("skyStopsBright()"))
        assertFalse("⛔ drawContent 不得 new radialGradient", draw.contains("Brush.radialGradient("))
        // ⭐ 逐帧天幕 = **恰好 2 次** drawRect（暗版 + 响度亮版）
        assertEquals("天幕必须恰好 2 次 drawRect（暗版 + 响度亮版）", 2, Regex("""drawRect\(\s*it""").findAll(draw).count())
        // ⛔⛔ 地平线大气 drawCircle **必须消失**（它是「海面」的一半）
        assertFalse("⛔ 地平线大气 drawCircle 必须已删除（真机读作海面）", draw.contains("hazeBrush"))
        assertFalse("⛔ hazeBrush 字段必须已删除", src.contains("hazeBrush"))
        assertFalse("⛔ HAZE_TINT / HAZE_CORE_A / HAZE_RADIUS_K 必须已删除", src.contains("HAZE_"))
        // ⛔⛔ 地平线辉光带（加性、alpha 0.42、横跨地平线）也必须消失
        assertFalse("⛔ 地平线辉光带必须已删除（加性亮带 = 海面反光）", draw.contains("bandBrush"))
        assertFalse("⛔ bandBrush 字段必须已删除", src.contains("bandBrush"))
        assertFalse("⛔ BAND_UP_K / BAND_DOWN_K / BAND_PEAK_A 必须已删除", src.contains("BAND_UP_K") || src.contains("BAND_PEAK_A"))
        // ⛔ 虚化只能用色标间距表达：这四类 API 一律不得出现
        for (blur in listOf("RenderEffect", "BlurMaskFilter", "RuntimeShader", "ShaderBrush")) {
            assertFalse("⛔ 不得用 $blur 表达虚化（无 shader / 无模糊是本项目红线）", src.contains(blur))
        }
        // ⭐ 天极辉光必须**保留**（它是本效果的识别特征，⛔ 不许跟着一起删掉）
        assertTrue("⛔ 天极辉光必须保留", draw.contains("poleGlowBrush"))
    }

    @Test
    fun `⑧ 天极辉光必须保留且半径不回调 - 画面唯一允许的亮部`() {
        // ⭐ v1.7：地平线两处加性层删除后，天极辉光是画面里**唯一**的亮部。
        //    0.40 是 v1.6 收紧后的值；⛔ 不要再往回调（会变成与海面无关的孤立亮斑）。
        assertTrue("天极辉光半径系数必须仍是 0.40（实测 ${C.POLE_GLOW_R_K}）", C.POLE_GLOW_R_K == 0.40f)
        assertTrue("天极辉光本体色必须保留", C.POLE_ARGB == 0xFF9FB0E8.toInt())
    }

    // ══════════════════════════════ ② 几何纯函数 ══════════════════════════════

    @Test
    fun `② radiusForBar 端点单调 单柱守卫 且无缩放项`() {
        val minDim = 720f
        assertEquals("第 0 柱 = R_INNER_K × minDim", C.R_INNER_K * minDim, C.radiusForBar(0, 64, minDim), 1e-4f)
        assertEquals("第 63 柱 = R_OUTER_K × minDim", C.R_OUTER_K * minDim, C.radiusForBar(63, 64, minDim), 1e-4f)
        // 单调不减（含 LOW 档 32 柱）
        for (count in intArrayOf(2, 32, 64)) {
            var prev = -1f
            for (b in 0 until count) {
                val r = C.radiusForBar(b, count, minDim)
                assertTrue("barCount=$count 时半径必须单调不减（b=$b）", r >= prev)
                prev = r
            }
        }
        // ⛔ 单柱不崩且落在内半径
        assertEquals(C.R_INNER_K * minDim, C.radiusForBar(0, 1, minDim), 1e-4f)
        assertEquals(C.R_INNER_K * minDim, C.radiusForBar(7, 1, minDim), 1e-4f)
        // ⛔ 签名只有 (bar, barCount, minDim)：radiusForRange 才是接受钳制后 rOuter 的那个
        for (b in 0 until 64) {
            assertEquals(
                "radiusForBar 必须等价于未钳制的 radiusForRange（b=$b）",
                C.radiusForRange(b, 64, C.R_INNER_K * minDim, C.R_OUTER_K * minDim),
                C.radiusForBar(b, 64, minDim),
                1e-4f,
            )
        }
        // 窄画幅钳制必须真的生效（把外半径压到 0.3 × 短边 ⇒ 远小于未钳制的 0.78）
        val clampedOuter = minDim * 0.30f
        val clamped = C.radiusForRange(63, 64, C.R_INNER_K * minDim, clampedOuter)
        assertEquals("钳制后最后一柱恰为钳制值", clampedOuter, clamped, 1e-3f)
        assertTrue("钳制后的外半径必须明显小于未钳制值", clamped < C.radiusForBar(63, 64, minDim))
    }

    @Test
    fun `② sweepForAmp 门限 端点 单调`() {
        assertEquals("amp = 0 ⇒ 无弧", 0f, C.sweepForAmp(0f), 1e-6f)
        assertEquals("amp ≤ SILENT_FLOOR ⇒ 无弧", 0f, C.sweepForAmp(C.SILENT_FLOOR), 1e-6f)
        assertEquals("amp = 1 ⇒ MAX_SWEEP_DEG", C.MAX_SWEEP_DEG, C.sweepForAmp(1f), 1e-4f)
        assertEquals("恰好越过门限也要归零", 0f, C.sweepForAmp(C.SILENT_FLOOR - 1e-6f), 1e-6f)
        var prev = -1f
        var a = 0f
        while (a <= 1f) {
            val s = C.sweepForAmp(a)
            assertTrue("扫掠角必须单调不减（amp=$a）", s >= prev)
            assertTrue("扫掠角不得超过 MAX_SWEEP_DEG", s <= C.MAX_SWEEP_DEG + 1e-4f)
            prev = s
            a += 0.01f
        }
    }

    // ══════════ ② v1.4 新增：底环（圆）+ 亮线段（线上的一段亮线）══════════

    @Test
    fun `② 亮线段 alpha 斜坡 严格单调 尾段沉回底环 头段恰为 1`() {
        val alphas = FloatArray(C.SEG_K) { C.segAlphaAt(it) }
        for (k in alphas.indices) {
            assertTrue("子弧 alpha 必须落在 (0,1]（k=$k）：${alphas[k]}", alphas[k] > 0f && alphas[k] <= 1f)
        }
        assertEquals("最头一段必须恰好 1.0（最亮）", 1f, C.segAlphaAt(C.SEG_K - 1), 1e-6f)
        assertTrue("最尾一段必须 ≥ 底环 alpha（已沉回底环）", C.segAlphaAt(0) >= C.RING_ALPHA)
        // ⭐ 严格单调**递增**（「前面亮后面逐渐与原来的线一样了」的可测形态）
        for (k in 1 until C.SEG_K) {
            assertTrue(
                "alpha 必须严格单调爬升（k=$k：${alphas[k - 1]} → ${alphas[k]}）",
                alphas[k] > alphas[k - 1],
            )
        }
        // ⭐ γ > 1 ⇒ 凹上升：尾段**低于**同 γ=1 的线性斜坡（爬升集中在头两段）
        for (k in 0 until C.SEG_K) {
            val linear = C.RING_ALPHA + (1f - C.RING_ALPHA) * ((k + 1).toFloat() / C.SEG_K)
            assertTrue("γ>1 时第 $k 段必须低于线性斜坡（${alphas[k]} vs $linear）", alphas[k] <= linear + 1e-6f)
        }
        assertTrue(
            "γ>1 必须让尾段明显低于线性斜坡（实测差 ${linear(0) - alphas[0]}）",
            linear(0) - alphas[0] > 0.05f,
        )
        // ⭐ 与幅值无关：斜坡函数签名里**根本没有** amp ⇒ 同一柱的 4 段不会帧间跳变
        assertEquals("γ=1 的尾段（负向对照）", C.RING_ALPHA + (1f - C.RING_ALPHA) / C.SEG_K, linear(0), 1e-6f)
    }

    @Test
    fun `② 底环 alpha 恒定且与幅值无关 亮线段线宽落在 1_0 到 1_8 dp`() {
        // ⛔ 底环颜色**不透明**：逐帧的 RING_ALPHA 全部由 drawImage(alpha=) 施加
        //    ⇒ 底环实际 alpha 恒为 RING_ALPHA，门禁可直读本函数判住。
        var t = 0f
        while (t <= 1f) {
            assertEquals("底环颜色必须不透明（t=$t）", 255, C.ringColorArgb(t) ushr 24 and 0xFF)
            assertEquals(
                "RING_ALPHA 落到字节上必须一致（t=$t）",
                (C.RING_ALPHA * 255f).toInt(), C.segColorArgb(t, C.RING_ALPHA) ushr 24 and 0xFF,
            )
            t += 0.125f
        }
        // ⭐ 底环颜色与幅值**无关**（segColorArgb 的第 2 参就是 alpha，不含 amp）
        assertEquals("底环颜色必须恒定（与幅值无关）", C.ringColorArgb(0.3f), C.ringColorArgb(0.3f))
        // ⭐ 线宽：全幅也只有 1.8 dp（v1.3 是 1.2 + 2.2·amp = 3.4 dp，且还要被放大 2 倍）
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        var a = 0f
        while (a <= 1f) {
            val dp = C.segWidthPx(a, 1f)     // density = 1 ⇒ 结果就是 dp
            assertTrue("线宽必须落在 [1.0, 1.8]（amp=$a：$dp）", dp >= 1.0f - 1e-4f && dp <= 1.8f + 1e-4f)
            lo = min(lo, dp)
            hi = max(hi, dp)
            a += 0.01f
        }
        assertEquals("线宽下界必须 = SEG_W_MIN", C.SEG_W_MIN, lo, 1e-3f)
        assertEquals("线宽上界必须 = SEG_W_MIN + SEG_W_GAIN", C.SEG_W_MIN + C.SEG_W_GAIN, hi, 1e-3f)
        assertTrue("满幅线宽必须 ≤ 1.8 dp（$hi）", hi <= 1.8f + 1e-3f)
        // ⛔ 越界输入不得撑破上界 / 跌破下界
        assertEquals("amp 越界必须被钳", C.segWidthPx(1f, 1f), C.segWidthPx(9f, 1f), 1e-6f)
        assertEquals("amp 负值必须被钳", C.segWidthPx(0f, 1f), C.segWidthPx(-9f, 1f), 1e-6f)
        // density 必须线性参与（dp→px）
        assertEquals("density=2 ⇒ px 翻倍", C.segWidthPx(0.5f, 1f) * 2f, C.segWidthPx(0.5f, 2f), 1e-4f)
    }

    @Test
    fun `② 底环与亮线段必须落在同一个半径上 分母恒为 RING_BANDS`() {
        // ⭐ 这是「亮线段精确落在自己的圆上」的唯一保证：两处都调 radiusForRange(slot, RING_BANDS, …)
        val src = codeOfE42()
        val bake = funBody(src, "rebuildRingLayer")
        val seg = funBody(src, "drawSegments")
        assertTrue("rebuildRingLayer 必须能切出来（空转自证）", bake.isNotEmpty())
        assertTrue("drawSegments 必须能切出来（空转自证）", seg.isNotEmpty())
        assertTrue("烘焙侧必须用 RING_BANDS 作半径分母", bake.contains("radiusForRange(i, RING_BANDS, rInner, rOuter)"))
        assertTrue("亮线侧必须用 RING_BANDS 作半径分母", seg.contains("radiusForRange(slot, RING_BANDS, rInner, rOuter)"))
        // ⛔ 两处都必须是**唯一**的 radiusForRange 调用（防止有人另起一份按帧柱数的分母）
        assertEquals("烘焙侧只准有一个 radiusForRange 调用", 1, Regex("""radiusForRange\(""").findAll(bake).count())
        assertEquals("亮线侧只准有一个 radiusForRange 调用", 1, Regex("""radiusForRange\(""").findAll(seg).count())
        // 64 圈 ⇒ 半径必须严格递增（否则两圈重合 ⇒ 该处又是叠加热点）
        val minDim = 720f
        var prevR = -1f
        for (i in 0 until C.RING_BANDS) {
            val r = C.radiusForRange(i, C.RING_BANDS, C.R_INNER_K * minDim, C.R_OUTER_K * minDim)
            assertTrue("底环半径必须严格递增（i=$i：$r ≤ $prevR）", r > prevR)
            prevR = r
        }
    }

    /** γ = 1 的线性斜坡（负向对照：生产实现用 `SEG_GAMMA = 1.6` 的凹斜坡）。 */
    private fun linear(k: Int): Float =
        C.RING_ALPHA + (1f - C.RING_ALPHA) * ((k + 1).toFloat() / C.SEG_K)

    // ══════════ ② v1.5 新增：spray 弧场（密集短弧 = 用户要的「亮弧更多」）══════════

    @Test
    fun `② spray 参数全部确定性 跨帧与 resize 恒等`() {
        // ⭐ 确定性是「每帧几何不变」的前提：spray 是静态烘焙的，
        //    只要哈希确定，场的形态在生命周期内就恒定（否则会看到弧在跳）。
        val minDim = 720f
        val rIn = C.R_INNER_K * minDim
        val rOut = C.R_OUTER_K * minDim
        for (b in 0 until C.RING_BANDS) {
            for (k in 0 until C.SPRAY_PER_BAND_FULL) {
                for (n in 0 until 5) {
                    val a = C.sprayHash(b, k, n)
                    assertEquals("sprayHash 必须确定性（b=$b k=$k n=$n）", a, C.sprayHash(b, k, n), 0f)
                    assertTrue("sprayHash 必须落在 [0,1)（b=$b k=$k n=$n）：$a", a >= 0f && a < 1f)
                }
                assertEquals("sprayActiveFor 必须确定性（b=$b k=$k）", C.sprayActiveFor(b, k), C.sprayActiveFor(b, k))
                assertEquals("sprayPhaseFor 必须确定性（b=$b k=$k）", C.sprayPhaseFor(b, k), C.sprayPhaseFor(b, k), 0f)
                assertEquals("spraySweepFor 必须确定性（b=$b k=$k）", C.spraySweepFor(b, k), C.spraySweepFor(b, k), 0f)
                val anchor = C.radiusForRange(b, C.RING_BANDS, rIn, rOut)
                val gap = C.localGapFor(b, C.RING_BANDS, rIn, rOut)
                assertEquals(
                    "sprayRadiusFor 必须确定性（b=$b k=$k）",
                    C.sprayRadiusFor(b, k, anchor, gap),
                    C.sprayRadiusFor(b, k, anchor, gap),
                    0f,
                )
            }
        }
        // ⭐ 索引排布 `band·40 + k·5 + n` 不得退化：碰撞必须**不成片**。
        //    ⚠️ `hash01` 是 Float 数学，不同输入**可能**取到同一值（约 3e3 个可分辨值
        //    vs 1920 组输入 ⇒ 碰撞不可避免且无害：四条流各自独立，同时撞车概率极低）。
        //    所以只断言「不是成片相同」，这是索引排布退化（如写成 `band+k+n`）能被抓到的那个信号。
        val seen = HashSet<Float>()
        var collisions = 0
        var total = 0
        for (b in 0 until C.RING_BANDS) {
            for (k in 0 until C.SPRAY_PER_BAND_FULL) {
                for (n in 0 until 5) {
                    total++
                    if (!seen.add(C.sprayHash(b, k, n))) collisions++
                }
            }
        }
        assertTrue("哈希碰撞不得成片（$collisions/$total）⇒ 索引排布未退化", collisions < total * 3 / 4)
    }

    @Test
    fun `② spray 半径恒在本带锚点 ±J·gap 内 且带间不交叉`() {
        val minDim = 1080f
        val rIn = C.R_INNER_K * minDim
        val rOut = C.R_OUTER_K * minDim
        // ⭐ 半径必须落在夹紧区间内
        for (b in 0 until C.RING_BANDS) {
            val anchor = C.radiusForRange(b, C.RING_BANDS, rIn, rOut)
            val gap = C.localGapFor(b, C.RING_BANDS, rIn, rOut)
            val span = gap * C.SPRAY_RADIUS_JITTER
            for (k in 0 until C.SPRAY_PER_BAND_FULL) {
                val r = C.sprayRadiusFor(b, k, anchor, gap)
                assertTrue("spray 半径必须 ≥ 锚点 − J·gap（b=$b k=$k：$r）", r >= anchor - span - 1e-3f)
                assertTrue("spray 半径必须 ≤ 锚点 + J·gap（b=$b k=$k：$r）", r <= anchor + span + 1e-3f)
            }
        }
        // ⭐ 逐带「最外侧 spray」必须小于「下一带最内侧 spray」⇒ 半径顺序恒单调
        for (b in 0 until C.RING_BANDS - 1) {
            val maxNow = C.radiusForRange(b, C.RING_BANDS, rIn, rOut) +
                C.localGapFor(b, C.RING_BANDS, rIn, rOut) * C.SPRAY_RADIUS_JITTER
            val minNext = C.radiusForRange(b + 1, C.RING_BANDS, rIn, rOut) -
                C.localGapFor(b + 1, C.RING_BANDS, rIn, rOut) * C.SPRAY_RADIUS_JITTER
            assertTrue(
                "带 $b 的最外 spray（$maxNow）必须小于带 ${b + 1} 的最内 spray（$minNext）" +
                    " ⇒ 否则半径交叉、读成乱网",
                maxNow < minNext,
            )
        }
        // ⭐ 抖动必须真的在动（否则退回 64 圈整齐圆 = 用户判失败的那个「太均匀」）
        var moved = 0
        for (b in 0 until C.RING_BANDS) {
            val anchor = C.radiusForRange(b, C.RING_BANDS, rIn, rOut)
            val gap = C.localGapFor(b, C.RING_BANDS, rIn, rOut)
            for (k in 0 until C.SPRAY_PER_BAND_FULL) {
                if (abs(C.sprayRadiusFor(b, k, anchor, gap) - anchor) > 1e-3f) moved++
            }
        }
        val tot = C.RING_BANDS * C.SPRAY_PER_BAND_FULL
        assertTrue("绝大多数 spray 必须偏离锚点（实测 $moved/$tot）⇒ 半径确实在抖动", moved > tot * 9 / 10)
        // ⛔ 单带 ⇒ gap=0 ⇒ 半径恒等于锚点（函数全域有定义，不崩）
        assertEquals("单带的 gap 必须为 0", 0f, C.localGapFor(0, 1, rIn, rOut), 1e-6f)
        assertEquals("单带的 spray 半径必须等于锚点", 42f, C.sprayRadiusFor(0, 0, 42f, 0f), 1e-6f)
        // ⛔ 负 gap 不得产生负 span（否则 coerceIn 上下界反了，夹紧失效）
        assertEquals("负 gap 必须被夹到 0", 42f, C.sprayRadiusFor(0, 0, 42f, -10f), 1e-6f)
    }

    @Test
    fun `② localGapFor 取两侧较小者 且两端有定义`() {
        val rIn = C.R_INNER_K * 1080f
        val rOut = C.R_OUTER_K * 1080f
        for (b in 0 until C.RING_BANDS) {
            val gap = C.localGapFor(b, C.RING_BANDS, rIn, rOut)
            assertTrue("gap 必须为正（b=$b：$gap）", gap > 0f)
            // ⭐ 必取两侧较小者：半径向外递增 ⇒ 间距向外**递减**
            if (b > 0 && b < C.RING_BANDS - 1) {
                val cur = C.radiusForRange(b, C.RING_BANDS, rIn, rOut)
                val gIn = cur - C.radiusForRange(b - 1, C.RING_BANDS, rIn, rOut)
                val gOut = C.radiusForRange(b + 1, C.RING_BANDS, rIn, rOut) - cur
                assertEquals("必须取 min(内侧间距, 外侧间距)（b=$b）", min(gIn, gOut), gap, 1e-3f)
                assertTrue("间距向外递减 ⇒ 外侧更小（b=$b：$gIn vs $gOut）", gOut <= gIn + 1e-3f)
            }
        }
        // ⭐ 两端：首带只有外侧、末带只有内侧 ⇒ 都必须有值（不能返回 MAX_VALUE 或 NaN）
        assertTrue("首带 gap 必须是有限正数", C.localGapFor(0, C.RING_BANDS, rIn, rOut).isFinite())
        assertTrue("末带 gap 必须是有限正数", C.localGapFor(63, C.RING_BANDS, rIn, rOut).isFinite())
        assertTrue("越界索引不得崩", C.localGapFor(999, C.RING_BANDS, rIn, rOut).isFinite())
    }

    @Test
    fun `② spray 相位 扫掠角 颜色桶 各自落在合法区间`() {
        for (b in 0 until C.RING_BANDS) {
            for (k in 0 until C.SPRAY_PER_BAND_FULL) {
                val phase = C.sprayPhaseFor(b, k)
                assertTrue("相位必须落在 [0,360)（b=$b k=$k：$phase）", phase >= 0f && phase < 360f)
                val sweep = C.spraySweepFor(b, k)
                assertTrue(
                    "扫掠角必须落在 [4°,26°]（b=$b k=$k：$sweep）",
                    sweep >= C.SPRAY_SWEEP_MIN_DEG - 1e-4f && sweep <= C.SPRAY_SWEEP_MAX_DEG + 1e-4f,
                )
                assertTrue(
                    "颜色桶必须落在 0..3（b=$b k=$k）",
                    C.sprayBucketFor(b, k) in 0 until C.SPRAY_BUCKETS,
                )
            }
        }
        // ⭐ 相位必须真的散开（若全落在少数值 ⇒ 场会读成 64 条径向对齐线）
        val phases = HashSet<Int>()
        val sweeps = HashSet<Int>()
        val buckets = HashSet<Int>()
        for (b in 0 until C.RING_BANDS) {
            for (k in 0 until C.SPRAY_PER_BAND_FULL) {
                phases.add((C.sprayPhaseFor(b, k) * 10f).toInt())
                sweeps.add((C.spraySweepFor(b, k) * 10f).toInt())
                buckets.add(C.sprayBucketFor(b, k))
            }
        }
        assertTrue("相位必须高度分散（10° 分箱后 ${phases.size} 个箱）⇒ 不是径向对齐", phases.size > 20)
        assertTrue("扫掠角必须参差（1° 分箱后 ${sweeps.size} 个箱）", sweeps.size > 10)
        assertEquals("四个颜色桶都必须被用到（实测 ${buckets.size} 个）", C.SPRAY_BUCKETS, buckets.size)
    }

    @Test
    fun `② spray 活跃率落在合理区间 且确实有空档`() {
        for ((label, perBand) in listOf(
            "HIGH" to C.SPRAY_PER_BAND_FULL,
            "MEDIUM" to C.SPRAY_PER_BAND_LITE,
            "LOW" to C.SPRAY_PER_BAND_OFF,
        )) {
            var active = 0
            var total = 0
            for (b in 0 until C.RING_BANDS) {
                for (k in 0 until perBand) {
                    total++
                    if (C.sprayActiveFor(b, k)) active++
                }
            }
            val ratio = active.toFloat() / total
            assertTrue(
                "$label 档活跃率必须落在 0.60–0.85（实测 $ratio，$active/$total）" +
                    "：太高 ⇒ 又是整齐的同心圈；太低 ⇒ 亮弧不够多",
                ratio in 0.60f..0.85f,
            )
            // ⭐ 「空档」的来源：必须确实有被剔除的弧
            assertTrue("$label 档必须存在被剔除的弧（空档）", active < total)
        }
    }

    @Test
    fun `② sprayAlphaFor 随能量单调 且静默段仍有底噪`() {
        assertEquals("能量 0 ⇒ 下界（静默也有底噪）", C.SPRAY_ALPHA_MIN, C.sprayAlphaFor(0f), 1e-6f)
        assertEquals("能量 1 ⇒ 上界", C.SPRAY_ALPHA_MAX, C.sprayAlphaFor(1f), 1e-6f)
        var prev = -1f
        var e = 0f
        while (e <= 1f) {
            val a = C.sprayAlphaFor(e)
            assertTrue("场亮度必须单调不减（energy=$e）", a >= prev)
            assertTrue("场亮度必须落在 [MIN,MAX]（energy=$e：$a）", a in C.SPRAY_ALPHA_MIN..C.SPRAY_ALPHA_MAX)
            prev = a
            e += 0.05f
        }
        // ⛔ 越界输入必须被钳（否则负能量会把场画成负 alpha）
        assertEquals("负能量必须被钳到下界", C.sprayAlphaFor(0f), C.sprayAlphaFor(-3f), 1e-6f)
        assertEquals("超能量必须被钳到上界", C.sprayAlphaFor(1f), C.sprayAlphaFor(9f), 1e-6f)
    }

    @Test
    fun `② poleCenterFor 四类画幅 都在画面内 且宽高比趋近 1 时收敛到正中`() {
        val out = FloatArray(2)
        for (wh in LANDSCAPES) {
            val (w, h) = wh[0] to wh[1]
            C.poleCenterFor(w, h, out)
            val px = out[0]
            val py = out[1]
            assertTrue("宽高比 ${w / h} 时天极 x 必须落在画面内：$px", px in 0f..w)
            assertTrue("宽高比 ${w / h} 时天极 y 必须落在画面内：$py", py in 0f..h)
        }
        // 21:9 超宽 ⇒ ls 被钳到 1 ⇒ 天极逼近 POLE_X_K / POLE_Y_K
        C.poleCenterFor(2560f, 1080f, out)
        assertEquals(2560f * C.POLE_X_K, out[0], 1e-2f)
        assertEquals(1080f * C.POLE_Y_K, out[1], 1e-2f)
        // 宽高比 → 1 ⇒ 收敛到正中（ls → 0）
        C.poleCenterFor(1000f, 1000f, out)
        assertEquals(500f, out[0], 1e-3f)
        assertEquals(500f, out[1], 1e-3f)
        // 竖屏（比值 < 1）同样收敛到正中（否则星环会被甩出屏）
        C.poleCenterFor(520f, 900f, out)
        assertEquals(260f, out[0], 1e-3f)
        assertEquals(450f, out[1], 1e-3f)
        // 4:3：居中与右偏之间插值，且确实插进去了
        C.poleCenterFor(1024f, 768f, out)
        assertTrue("4:3 时天极必须落在「正中 ↔ 右偏」之间：${out[0]}", out[0] in 512f..1024f * C.POLE_X_K)
    }

    @Test
    fun `② outerRadiusFor 宽屏取基准 窄屏钳在天极到最远边之内`() {
        val out = FloatArray(2)
        for (wh in LANDSCAPES) {
            val (w, h) = wh[0] to wh[1]
            val minDim = min(w, h)
            C.poleCenterFor(w, h, out)
            val px = out[0]
            val py = out[1]
            val r = C.outerRadiusFor(w, h, px, py, minDim)
            val room = max(max(px, w - px), max(py, h - py))
            assertTrue("半径必须 ≥ 内半径（$w×$h）", r >= C.R_INNER_K * minDim)
            assertTrue("半径必须 ≤ 天极到最远边的距离（$w×$h：r=$r room=$room）", r <= room + 1e-3f)
            if (w / h >= C.NARROW_ASPECT) {
                assertEquals("宽屏必须返回 R_OUTER_K × 短边（$w×$h）", C.R_OUTER_K * minDim, r, 1e-3f)
            } else {
                assertTrue("窄屏必须被钳制（$w×$h）", r < C.R_OUTER_K * minDim + 1e-3f)
            }
        }
        // 方屏是最容易甩出屏的一档：钳制必须真的咬下去
        C.poleCenterFor(1000f, 1000f, out)
        val square = C.outerRadiusFor(1000f, 1000f, out[0], out[1], 1000f)
        assertTrue("方屏外半径必须被钳到 ≤ 0.96 × 半边长", square <= 0.96f * 500f + 1e-3f)
        assertTrue("方屏外半径必须明显小于未钳制值（${C.R_OUTER_K * 1000f}）", square < C.R_OUTER_K * 1000f * 0.7f)
    }

    @Test
    fun `② treeXFor 枝展下界恒成立 且枝尖不出画`() {
        for (wh in LANDSCAPES) {
            val (w, h) = wh[0] to wh[1]
            val minDim = min(w, h)
            val out = FloatArray(2)
            C.poleCenterFor(w, h, out)
            val rOuter = C.outerRadiusFor(w, h, out[0], out[1], minDim)
            val treeX = C.treeXFor(out[0], rOuter, w, h)
            val floor = C.TREE_BRANCH_REACH_K * h + C.TREE_EDGE_GAP_K * w
            assertTrue("树心必须 ≥ 枝展 + 边距下界（$w×$h：$treeX < $floor）", treeX >= floor - 1e-3f)
            assertTrue("树心不得为负（$w×$h）", treeX >= 0f)
            assertTrue("树心必须留在画面内（$w×$h）", treeX < w)
            // 最左枝尖 = 树心 − 枝展，必须还在左边框之内
            assertTrue("最左枝尖不得出画（$w×$h）", treeX - C.TREE_BRANCH_REACH_K * h >= -1e-3f)
        }
        // 1280×720 的构图口径：树心 ≈ 13.5% 宽（落在「星环左侧的空天」）
        val out = FloatArray(2)
        C.poleCenterFor(1280f, 720f, out)
        val treeX = C.treeXFor(out[0], C.outerRadiusFor(1280f, 720f, out[0], out[1], 720f), 1280f, 720f)
        assertEquals("1280×720 时树心 ≈ 13.5% 宽", 0.135f * 1280f, treeX, 4f)
    }

    // ══════════════════════════════ ③ 时基 / 帧率无关 ══════════════════════════════

    @Test
    fun `③ poleAngleDeg 单一时钟推导 与帧率无关 且跨零点归一`() {
        assertEquals("nowMs=10000 t0=0 ⇒ 32°", 32f, C.poleAngleDeg(10_000L, 0L), 1e-3f)
        assertEquals("nowMs=t0 ⇒ 0°", 0f, C.poleAngleDeg(5_000L, 5_000L), 1e-3f)
        // 整圈（112.5s × 3.2°/s = 360°）必须回到 0
        assertEquals("整圈后必须回到 0°", 0f, C.poleAngleDeg(112_500L, 0L), 1e-2f)
        // 负时长（时钟回退）也必须归一到 [0, 360)
        val back = C.poleAngleDeg(0L, 10_000L)
        assertTrue("负时长必须落在 [0,360)：$back", back >= 0f && back < 360f)
        // ⭐ 帧率无关：dt 累加（60fps × 1000ms）与单时钟推导同源
        for (fps in intArrayOf(15, 30, 60)) {
            val steps = fps
            val dtSec = 1f / fps
            var acc = 0f
            repeat(steps) { acc += C.ROT_DEG_PER_S * dtSec }
            assertEquals("$fps fps 累加必须与单时钟推导同源", C.poleAngleDeg(1000L, 0L), acc, 1e-3f)
        }
        // ⭐ 平移不变性：任意 t0 下两时刻的角差都等于 t0 = 0 时的角差
        for (t0 in longArrayOf(0L, 1_234L, 987_654L)) {
            val a = C.poleAngleDeg(400_000L, t0)
            val b = C.poleAngleDeg(100_000L, t0)
            var diff = (a - b) % 360f
            if (diff < 0f) diff += 360f
            var expect = (C.poleAngleDeg(400_000L, 0L) - C.poleAngleDeg(100_000L, 0L)) % 360f
            if (expect < 0f) expect += 360f
            assertEquals("t0=$t0 时角差必须与 t0 无关", expect, diff, 1e-2f)
        }
    }

    // ⛔ v1.4：`decayAlphaFor` / `TRAIL_TAU_S` 随 ping-pong 累积缓冲一起删除，
    //    原 ③ 段那组「指数衰减」断言**整体作废**（架构换成「底环 + 亮线段」，无衰减可言）。

    @Test
    fun `③ flareDecayFor 指数衰减且 tau 守卫不产生 NaN`() {
        assertEquals("dt=0 ⇒ 1（闪光不衰减）", 1f, C.flareDecayFor(0f), 1e-6f)
        assertEquals(
            "dt=0.1 ⇒ exp(−0.1/0.18)",
            Math.exp(-0.1 / 0.18).toFloat(), C.flareDecayFor(0.1f), 1e-5f,
        )
        assertEquals("dt 被钳到 0.1", C.flareDecayFor(0.1f), C.flareDecayFor(9f), 1e-6f)
        // 连续衰减 1s 后必须几乎归零（拍点闪光不得拖第二下）
        var f = 1f
        repeat(60) { f *= C.flareDecayFor(1f / 60f) }
        assertTrue("1 秒后闪光必须几乎归零（实际 $f）", f < 0.01f)
    }

    @Test
    fun `③ envelopeStep 快攻慢放 且 dt 化后帧率无关`() {
        // 攻：从 0 到 1，0.1s 内必须基本到顶
        assertTrue("起音必须快", C.envelopeStep(0f, 1f, 0.1f) > 0.95f)
        // 放：从 1 到 0，0.05s 内几乎不动
        assertTrue("释音必须慢（实际 ${C.envelopeStep(1f, 0f, 0.05f)}）", C.envelopeStep(1f, 0f, 0.05f) > 0.75f)
        // ⭐ 帧率无关：同一时长下 30fps / 60fps / 15fps 结果接近
        val a = releaseAfter(0.30f, 60)
        val b = releaseAfter(0.30f, 30)
        val c = releaseAfter(0.30f, 15)
        assertTrue("60/30fps 的释音包络必须接近（$a vs $b）", abs(a - b) < 0.02f)
        assertTrue("60/15fps 的释音包络必须接近（$a vs $c）", abs(a - c) < 0.12f)
        // 且确实在衰减（不是恒 1 的假实现）
        assertTrue("释音包络必须真的在下降（实际 $a）", a < 0.5f && a > 0.05f)
    }

    /** 生产 [C.envelopeStep] 的逐步积分（释音相：target = 0 < cur = 1）。 */
    private fun releaseAfter(seconds: Float, fps: Int): Float {
        val dt = 1f / fps
        var cur = 1f
        var t = 0f
        while (t < seconds - 1e-6f) {
            cur = C.envelopeStep(cur, 0f, dt)
            t += dt
        }
        return cur
    }

    // ══════════════════════════════ ④ 山脊与画路径同源（G9）══════════════════════════════

    @Test
    fun `④ RIDGE 控制点 x 取 1_3 与 2_3 ⇒ x_t 线性 ridgeYAt 精确命中 Bernstein`() {
        val w = 1920f
        val h = 1080f
        val horizonY = C.HORIZON_K * h
        for (seg in C.RIDGE) {
            val dx = seg.x1 - seg.x0
            // ⭐ 前提：1/3、2/3 的控制点 x 使 x(t) **严格线性**（ridgeYAt 直接反解的全部依据）
            for (k in 0..100) {
                val t = k / 100f
                assertEquals(
                    "x(t) 必须线性（seg=${seg.x0} t=$t）",
                    seg.x0 + dx * t, bezierX(t, seg.x0, seg.x0 + dx / 3f, seg.x0 + dx * 2f / 3f, seg.x1), 1e-5f,
                )
            }
            // ⭐ 核心断言：ridgeYAt 的反解与 Bernstein 正算误差 < 1e-3 px
            for (k in 0..100) {
                val t = k / 100f
                val x = (seg.x0 + dx * t) * w
                assertEquals(
                    "seg=${seg.x0} t=$t 时 ridgeYAt 必须精确命中 Bernstein 正算",
                    horizonY + bernsteinY(seg, t) * h, C.ridgeYAt(x, w, horizonY, h), 1e-3f,
                )
            }
        }
    }

    @Test
    fun `④ RIDGE 段间连续 边界正确 且全程落在地平线上下的窄带内`() {
        // 段 i 的 y1 必须 == 段 i+1 的 y0（否则脊线出现折角台阶）
        for (i in 0 until C.RIDGE.size - 1) {
            assertEquals(
                "段 $i 的 y1 必须等于段 ${i + 1} 的 y0（脊线连续性）",
                C.RIDGE[i].y1, C.RIDGE[i + 1].y0, 1e-6f,
            )
        }
        // 边界：ridgeDy(0) == 首段 y0；ridgeDy(1) == 末段 y1
        assertEquals(C.RIDGE[0].y0, C.ridgeDy(0f), 1e-6f)
        assertEquals(C.RIDGE[C.RIDGE.size - 1].y1, C.ridgeDy(1f), 1e-6f)
        // ⭐ Bernstein 基函数是凸组合 ⇒ 值域必被控制点包住 ⇒ 地面绝不越出这条窄带
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        var u = 0f
        while (u <= 1f) {
            val v = C.ridgeDy(u)
            lo = min(lo, v)
            hi = max(hi, v)
            u += 0.001f
        }
        assertTrue("山脊最高点不得超过 −0.020h（实际 $lo）", lo >= -0.0201f)
        assertTrue("山脊最低点不得超过 +0.026h（实际 $hi）", hi <= 0.0261f)
        // 归一化 x 必须落在 [0,1]（越界要钳到首/末段，函数全域有定义）
        assertEquals(C.ridgeDy(0f), C.ridgeDy(-5f), 1e-6f)
        assertEquals(C.ridgeDy(1f), C.ridgeDy(5f), 1e-6f)
    }

    @Test
    fun `④ ridgeYAt 树根必落在脊线上 跨画幅自洽`() {
        val out = FloatArray(2)
        for (wh in LANDSCAPES) {
            val (w, h) = wh[0] to wh[1]
            val minDim = min(w, h)
            val horizonY = C.HORIZON_K * h
            C.poleCenterFor(w, h, out)
            val rOuter = C.outerRadiusFor(w, h, out[0], out[1], minDim)
            val treeX = C.treeXFor(out[0], rOuter, w, h)
            val treeBase = C.ridgeYAt(treeX, w, horizonY, h)
            // 树根（+埋入 2px）必须仍在脊线的窄带内，且不越过画面底边
            assertTrue("树根必须落在脊线上（$w×$h：$treeBase）", treeBase in horizonY - 0.0201f * h..horizonY + 0.0261f * h)
            assertTrue("树根不得越出画面底边（$w×$h）", treeBase < h)
        }
    }

    /** 与画路径同源的 Bernstein 正算（测试侧独立实现，作为「真值」）。 */
    private fun bernsteinY(seg: StarrySkyRenderer.RidgeSeg, t: Float): Float {
        val k = 1f - t
        return k * k * k * seg.y0 + 3f * k * k * t * seg.c1 + 3f * k * t * t * seg.c2 + t * t * t * seg.y1
    }

    /** 三次 Bezier 的 x 分量正算（测试侧独立实现）。 */
    private fun bezierX(t: Float, x0: Float, c1x: Float, c2x: Float, x1: Float): Float {
        val k = 1f - t
        return k * k * k * x0 + 3f * k * k * t * c1x + 3f * k * t * t * c2x + t * t * t * x1
    }

    // ══════════════════════════════ ⑤ 枯树方向与确定性（G10 数值面）══════════════════════════════

    @Test
    fun `⑤ limbTipY 全部主枝一律向上伸展 不得读成下垂蜘蛛腿`() {
        val y0 = 1000f
        val segLen = 60f
        for (limb in C.LIMBS) {
            val a0 = limb.deg * (Math.PI.toFloat() / 180f)
            for (seedStep in 0 until 4) {
                val seed = limb.seed + seedStep
                val tip = C.limbTipY(0f, y0, a0, segLen, C.LIMB_CURL, seed)
                assertTrue(
                    "枝条（deg=${limb.deg} seed=$seed）必须向上伸展：tip=$tip > y0=$y0",
                    tip < y0,
                )
            }
        }
        // ⭐ 向上幅度必须可观（不只是浮点数上的「略小」）
        val tip = C.limbTipY(0f, y0, 20f * (Math.PI.toFloat() / 180f), segLen, C.LIMB_CURL, 11)
        assertTrue("向上幅度必须可观（实际 ${y0 - tip}px）", y0 - tip > segLen * C.LIMB_SEGS * 0.5f)
        // 枯枝必须比「不回拉的直线」短 —— 回拉把角度收回竖直，横向分量被削掉
        val pulled = C.limbTipY(0f, y0, 54f * (Math.PI.toFloat() / 180f), segLen, C.LIMB_CURL, 51)
        assertTrue("回拉后仍必须上升", pulled < y0)
    }

    @Test
    fun `⑤ limbBudget 出枝高度预算恒为正 逐条主枝都成立`() {
        for (wh in LANDSCAPES) {
            val h = wh[1]
            val treeBase = 1000f
            val treeH = C.TREE_H_K * h
            val splitY = treeBase - treeH * C.TREE_SPLIT_K
            for (limb in C.LIMBS) {
                val oy = splitY * (1f - limb.at) + treeBase * limb.at
                val budget = C.limbBudget(oy, treeBase, treeH)
                assertTrue(
                    "deg=${limb.deg} 在 ${wh[0]}×$h 上预算必须为正（实际 $budget）",
                    budget > 0f,
                )
                assertTrue("预算不得超过树高", budget <= treeH + 1e-3f)
            }
            // ⛔ 出枝点跑到树顶之上（符号写反的现场）时，守卫必须把它拉回 0 而不是负数
            assertEquals("负预算必须被夹到 0", 0f, C.limbBudget(0f, treeBase, treeH), 1e-6f)
        }
        // 预算下界常量本身必须是非负
        assertTrue("TREE_BUDGET_MIN 必须 ≥ 0", C.TREE_BUDGET_MIN >= 0f)
    }

    @Test
    fun `⑤ hash01 确定性 落在 0_1 区间 且不同种子给出不同抖动`() {
        for (i in 0 until 200) {
            val v = C.hash01(i)
            assertTrue("hash01($i) 必须落在 [0,1)：$v", v >= 0f && v < 1f)
            // ⛔ 确定性：同输入必须同输出（否则 resize 后树形会变、单测也测不了）
            assertEquals("hash01 必须确定性（i=$i）", v, C.hash01(i), 0f)
        }
        // 不同种子的抖动必须真的有差异（否则枯枝会变成完全对称的机械图形）
        var distinct = 0
        for (s in intArrayOf(11, 23, 37, 51, 67)) {
            val a = C.hash01(s * 7)
            val b = C.hash01(s * 13)
            if (abs(a - b) > 1e-3f) distinct++
        }
        assertTrue("不同种子必须给出不同抖动（实测 $distinct/5 组不同）", distinct >= 4)
    }

    // ══════════════════════════════ ⑥ 注册与计数（G1）══════════════════════════════

    @Test
    fun `⑥ 注册链 枚举 工厂 计数三处一致`() {
        val t = VisualizerTheme.STAR_TRAILS
        assertEquals("显示名", "星空星轨", t.displayName)
        assertEquals("ordinalLabel", "42", t.ordinalLabel)
        assertEquals("档位", VisualizerTheme.Tier.ADV, t.tier)
        assertEquals("⛔ 效果总数（门禁硬计数）", 30, VisualizerTheme.entries.size)
        assertEquals("从键反查必须回到自己", t, VisualizerTheme.fromKey("STAR_TRAILS"))
        assertTrue("必须出现在可选列表里", VisualizerTheme.selectable(true).contains(t))
        // 工厂分支存在（穷举 when 的完备性：每个枚举都有分支）
        val factorySrc = stripComments(
            readMain("com/nasmusic/tv/visualizer/VisualizerRendererFactory.kt"),
        )
        assertTrue("工厂必须 import 渲染器", factorySrc.contains("import com.nasmusic.tv.visualizer.renderers.StarrySkyRenderer"))
        assertTrue(
            "工厂必须有 when 分支",
            factorySrc.contains("VisualizerTheme.STAR_TRAILS -> StarrySkyRenderer()"),
        )
        val missing = VisualizerTheme.entries.filter { !factorySrc.contains("VisualizerTheme.${it.name} ->") }
        assertTrue("下列枚举在工厂里没有 when 分支：$missing", missing.isEmpty())
    }

    @Test
    fun `⑥ needsParticleBudget 为 false ⇒ supports 三档恒 true 且门是活的`() {
        val t = VisualizerTheme.STAR_TRAILS
        assertFalse("⛔ 本效果不读 maxParticles ⇒ 必须标 false", t.needsParticleBudget)
        for (q in VisualQuality.entries) {
            assertTrue("$q 必须提供星空星轨（needsParticleBudget=false ⇒ ADV 分支恒真）", q.supports(t))
        }
        // 负向：门必须是活的 —— 真读预算的效果在 LOW 仍被挡住
        assertTrue("真读预算的效果（世界）在 LOW 必须被挡", VisualizerTheme.WORLD.needsParticleBudget)
        assertFalse("LOW 不得提供世界（证明上面的『恒 true』不是因为门失效）", VisualQuality.LOW.supports(VisualizerTheme.WORLD))
        assertFalse("LOW 不得提供反馈残像（ULTRA 门）", VisualQuality.LOW.supports(VisualizerTheme.MILKDROP_FEEDBACK))
    }

    @Test
    fun `⑥ 复用正确 未新增 ProceduralTexture Id 柱数恒读 spectrum_size`() {
        // ⛔ 不新增 ProceduralTexture.Id（Id.ordinal 是槽位下标，只能末尾追加）
        val ids = ProceduralTexture.Id.entries.map { it.name }
        assertEquals(
            "ProceduralTexture.Id 不得因本效果新增（实测 $ids）",
            listOf("GRAIN", "SCANLINE", "STARFIELD", "PAPER", "WATER", "CAUSTIC", "PLASMA", "FOG"),
            ids,
        )
        // 柱数：⛔ 恒读 frame.spectrum.size，⛔ 不得硬编码 64
        val src = codeOfE42()
        val arcs = funBody(src, "drawSegments")
        assertTrue("drawSegments 必须能切出来（空转自证）", arcs.isNotEmpty())
        assertTrue("必须读 frame.spectrum", arcs.contains("frame.spectrum"))
        assertTrue("必须读 .size 决定柱数", arcs.contains("spec.size"))
        assertFalse("⛔ drawSegments 不得硬编码柱数 64", Regex("""\b64\b""").containsMatchIn(arcs))
        // ⛔ 循环上界必须来自 spectrum.size（RING_BANDS 只准当**半径分母**，不当柱数）
        assertTrue(
            "avail 必须由 spectrum.size 与 RING_BANDS 取小",
            arcs.contains("val avail = if (spec.size < RING_BANDS) spec.size else RING_BANDS"),
        )
        assertTrue("循环必须走 avail", arcs.contains("while (slot < avail)"))
        assertFalse("⛔ 不得拿 RING_BANDS 直接当循环上界", arcs.contains("while (slot < RING_BANDS)"))
        // 柱容量必须由 SpectrumContract 给出（⛔ 不自造 64）
        assertTrue(
            "柱 / 包络 / 方位角数组容量必须来自 SpectrumContract.BAR_COUNT",
            src.contains("private val az = FloatArray(SpectrumContract.BAR_COUNT)") &&
                src.contains("private val env = FloatArray(SpectrumContract.BAR_COUNT)"),
        )
        assertEquals("SpectrumContract.BAR_COUNT 必须仍是 64（LOW 档 32 是 barCount，不是 spectrum.size）", 64, SpectrumContract.BAR_COUNT)
        assertEquals("RING_BANDS 必须等于 SpectrumContract.BAR_COUNT", SpectrumContract.BAR_COUNT, C.RING_BANDS)
        assertEquals("VisualQuality.LOW.barCount 仍是 32", 32, VisualQuality.LOW.barCount)
    }

    // ══════════════════════════════ ⑦ 源码段：模板方法 / 时基 / rng（G5）══════════════════════════════

    @Test
    fun `⑦ 三条模板方法不覆盖 且零违规`() {
        val body = codeOfE42()
        assertTrue("类体必须能切出来（空转自证）", body.isNotEmpty())
        for (m in listOf("draw", "onEnter", "onExit")) {
            val re = Regex("""override\s+fun\s+(?:[A-Za-z0-9_.]+\.)?$m\s*\(""")
            assertFalse("⛔ 不得覆写 $m（基类为 final）", re.containsMatchIn(body))
        }
        // ⛔ 不得使用 ctx.nowMs（三个调用点语义不一致；本效果用 fx.nowMs）
        assertFalse("⛔ 不得使用 ctx.nowMs", Regex("""\bctx\.nowMs\b""").containsMatchIn(body))
        assertTrue("必须使用 fx.nowMs（单一时钟）", body.contains("fx.nowMs"))
        // ⛔ 不得自建 rng
        assertFalse(
            "⛔ 不得自建 rng（复用基类的 protected rng）",
            Regex("""private\s+val\s+rng\s*=\s*VisualizerRandom\(\)""").containsMatchIn(body),
        )
        assertTrue("必须用继承的 rng 抽柱方位角", funBody(body, "onEnterContent").contains("rng.next()"))
        // 类头：必须继承 RendererFx（RendererBaseContractTest 的扫描锚点）
        val raw = readMain("com/nasmusic/tv/visualizer/renderers/StarrySkyRenderer.kt")
        assertTrue("类头必须是 class StarrySkyRenderer : RendererFx()", raw.contains("class StarrySkyRenderer : RendererFx()"))
    }

    // ══════════════════════════════ ⑧ 源码段：postFx / 禁物 / 零分配 / 资源（G4·G6·G7·G8）══════════════════════════════

    @Test
    fun `⑧ postFx 必须是数值字面量 正负双证`() {
        val postFxRe = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""")
        val numRe = Regex("""=\s*([0-9]*\.?[0-9]+)f""")
        val m = postFxRe.find(codeOfE42())
        assertTrue("必须声明 postFx（否则会被判『未覆盖后处理』）", m != null)
        val nums = numRe.findAll(m!!.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals("门禁原版正则必须取到 2 个字面量", listOf("0.42", "0.026"), nums)
        assertTrue("至少一个通道 > 0 才会被判『已覆盖』", nums.any { it.toFloat() > 0f })
        // 负向：具名常量写法会被**静默判否**（这正是它被禁的原因）
        val named = "override val postFx = PostFx(vignette = VIGNETTE, grain = GRAIN)"
        assertTrue("具名常量仍能匹配到参数表", postFxRe.find(named) != null)
        assertTrue("⛔ 具名常量取不到数值字面量 ⇒ 会被判未覆盖", numRe.findAll(postFxRe.find(named)!!.groupValues[1]).none())
        // 负向：加类型标注 / 写成自定义 getter 会让整条正则失配
        val typed = "override val postFx: PostFx get() = PostFx(vignette = 0.42f, grain = 0.026f)"
        assertTrue("⛔ 带类型标注 + getter 的写法整条正则失配（这才是真正的陷阱）", postFxRe.find(typed) == null)
    }

    @Test
    fun `⑧ 无禁物 shader RenderEffect OpenGL 圆角 clip Difference 一律不得出现`() {
        // ⛔ 扫描面是**整份文件**（含类 KDoc），比只扫类体更严
        val body = codeOfE42File()
        for (bad in FORBIDDEN) {
            assertFalse("⛔ 不得出现 $bad", body.contains(bad))
        }
        // 成对自证：原文（未剥注释）**确实**含这些词 ⇒ 证明「剥注释」这一步真的在干活
        val raw = readSrc()
        for (t in listOf("RuntimeShader", "RenderEffect", "BitmapShader", "ctx.nowMs", "Math.random(")) {
            assertTrue("原文 KDoc 里确实提到 $t（证明剥注释在干活）", raw.contains(t))
            assertFalse("剥注释后 $t 必须消失", body.contains(t))
        }
        // 必须存在的替代实现
        assertTrue("必须复用共享 STARFIELD 纹理", body.contains("tile(ProceduralTexture.Id.STARFIELD)"))
        // ⛔ v1.4：拖尾衰减的 DST_OUT 必须已删除（连同整套 ping-pong；另有专条门禁 ⑧-累积缓冲）
        assertFalse("⛔ DST_OUT 拖尾衰减必须已删除", body.contains("DST_OUT"))
        assertFalse("⛔ PorterDuff 不得再被引用", body.contains("PorterDuff"))
    }

    @Test
    fun `⑧ 累积缓冲机械必须已删除 不得静默回流`() {
        val file = codeOfE42File()
        val src = codeOfE42()
        for (gone in ACCUM_TOKENS) {
            assertFalse("⛔ $gone 必须已删除（v1.4 换掉了 ping-pong 架构）", file.contains(gone))
        }
        // ⛔ 双位图字段也不得复活
        assertFalse(
            "⛔ 不得复活 prev / curr 双位图",
            Regex("""\bprivate\s+var\s+(prev|curr)\s*:""").containsMatchIn(src),
        )
        assertFalse("⛔ 不得再持有 android Canvas 做缓冲", Regex("""var\s+\w*[Cc]anvas\w*\s*:\s*android\.graphics\.Canvas""").containsMatchIn(src))
        // 成对自证：原文（未剥注释）确实提到这些词 ⇒ 证明判据不是空转
        val raw = readSrc()
        for (t in listOf("DST_OUT", "PorterDuffXfermode", "ping-pong")) {
            assertTrue("原文 KDoc 里确实提到 $t（证明剥注释在干活）", raw.contains(t))
        }
        // ⛔ 反向自证：判据能真的挂
        assertFalse(
            "⛔ 把 DST_OUT 写回来必须被判失败",
            noAccumBuffer("val p = Paint(); p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)"),
        )
        assertFalse(
            "⛔ 把双缓冲 Canvas 写回来必须被判失败",
            noAccumBuffer("private var prevCanvas: android.graphics.Canvas? = null\nprivate var currCanvas: android.graphics.Canvas? = null"),
        )
        assertFalse("⛔ 把缓冲缩放写回来必须被判失败", noAccumBuffer("cc.withScale(0.5f, 0.5f, 0f, 0f) { drawArcs() }"))
        assertFalse("⛔ 把缓冲三档写回来必须被判失败", noAccumBuffer("val w = if (level == OFF) TRAIL_W_OFF else TRAIL_W_LITE"))
        assertTrue("成对自证：合规片段必须被判通过", noAccumBuffer("private var rings: ImageBitmap? = null"))
        assertTrue("成对自证：生产类体必须被判通过", noAccumBuffer(src))
    }

    @Test
    fun `⑧ 弧线必须画在原生分辨率画布上 不得进降采样缓冲`() {
        val src = codeOfE42()
        val draw = funBody(src, "drawContent")
        val seg = funBody(src, "drawSegments")
        val bake = funBody(src, "rebuildRingLayer")
        assertTrue("drawContent 必须能切出来（空转自证）", draw.isNotEmpty())
        assertTrue("drawSegments 必须能切出来（空转自证）", seg.isNotEmpty())
        assertTrue("rebuildRingLayer 必须能切出来（空转自证）", bake.isNotEmpty())
        // ⛔ 全文不得有任何缩放 / 离屏层（四个真机缺陷之一就是 960 缓冲被放大成锯齿）
        assertFalse("⛔ 不得再 import/use withScale", src.contains("withScale"))
        assertFalse("⛔ 不得再有画布缩放", Regex("""(?<![A-Za-z0-9_.])scale\s*\(""").containsMatchIn(src))
        assertFalse("⛔ 不得再有 saveLayer", src.contains("saveLayer"))
        assertFalse("⛔ 不得再有缓冲缩放字段", src.contains("bufScale"))
        // ⛔ 亮线段必须直接调 DrawScope.drawArc（原生画布 + 抗锯齿）
        assertTrue("亮线段必须走 DrawScope.drawArc", seg.contains("drawArc("))
        assertTrue("亮线段必须加性叠加", seg.contains("blendMode = BlendMode.Plus"))
        assertTrue("亮线段必须用平头（子弧首尾相接才无缝）", seg.contains("StrokeCap.Butt"))
        assertTrue("亮线段必须按 SEG_K 分子弧", seg.contains("while (k < SEG_K)"))
        // ⛔ 底环缓存必须是**原生尺寸**（画布尺寸），不得按档位降采样
        assertFalse("⛔ 底环烘焙不得含任何缩放", bake.contains("scale"))
        assertFalse("⛔ 底环烘焙不得用缓冲三档常量", bake.contains("TRAIL_W_"))
        assertTrue("底环必须按画布尺寸裁剪（MAX_TEX_PX）", bake.contains("MAX_TEX_PX"))
        assertTrue("底环必须整圈画（drawCircle）", bake.contains("drawCircle("))
        // ⛔ 每帧只 1 次 blit 取底环（不回到「每帧 64 次 drawCircle」）
        assertEquals("drawContent 必须只调一次 ensureRingLayer", 1, Regex("""ensureRingLayer\(""").findAll(draw).count())
        val ensure = funBody(src, "ensureRingLayer")
        assertTrue("ensureRingLayer 必须能切出来", ensure.isNotEmpty())
        assertTrue("烘焙的唯一调用点必须是 ensureRingLayer", ensure.contains("rebuildRingLayer("))
        // 「1 处声明 + 1 处调用」= 2
        assertEquals("rebuildRingLayer 只准「1 处声明 + ensureRingLayer 内 1 处调用」", 2, Regex("""rebuildRingLayer\(""").findAll(src).count())
        // ⛔ 每帧的底环 blit 必须走 Plus（星芒是加性的）且 1:1 不重采样
        assertTrue("底环 blit 必须用 BlendMode.Plus", draw.contains("alpha = RING_ALPHA") && draw.contains("filterQuality = FilterQuality.None"))
        // 负向：喂一份「把弧线画进降采样缓冲」的样本必须被判失败
        assertFalse(
            "⛔ 降采样缓冲版本必须被判失败",
            noScaledArcBuffer("cc.withScale(0.5f, 0.5f, 0f, 0f) { cc.drawArc(r, 0f, 360f, false, p) }"),
        )
        assertFalse("⛔ saveLayer 版本必须被判失败", noScaledArcBuffer("canvas.saveLayer(0f, 0f, 960f, 540f, p)"))
        assertTrue("成对自证：生产实现必须被判通过", noScaledArcBuffer(seg))
    }

    @Test
    fun `⑧ spray 弧必须烘焙成 Path 逐帧只画桶 不得逐弧绘制`() {
        val src = codeOfE42()
        val draw = funBody(src, "drawContent")
        val bake = funBody(src, "rebuildSprayPaths")
        val ensure = funBody(src, "ensureSprayLayer")
        val spray = funBody(src, "drawSpray")
        for ((n, b) in listOf("drawContent" to draw, "rebuildSprayPaths" to bake,
            "ensureSprayLayer" to ensure, "drawSpray" to spray)) {
            assertTrue("$n 必须能切出来（空转自证）", b.isNotEmpty())
        }
        // ⛔ **核心不变量**：逐帧路径里不得出现任何弧构造调用。
        //    384 次逐弧 drawArc 会把真机 29.7fps 直接砍半 —— 这条是整个 v1.5 设计的命门。
        assertFalse("⛔ drawContent 不得逐弧绘制 spray", draw.contains("addArc("))
        assertFalse("⛔ drawSpray 不得逐弧绘制 spray", spray.contains("addArc("))
        assertFalse("⛔ drawSpray 不得逐弧 drawArc", spray.contains("drawArc("))
        assertFalse("⛔ drawSpray 不得逐弧 arcTo", spray.contains("arcTo("))
        // ⛔ drawContent 里的弧构造调用只准来自 drawSegments（英雄亮线段），spray 不在其中
        assertEquals(
            "drawContent 里的 addArc 只应出现在 bake（不得进每帧）",
            0, Regex("""addArc\(""").findAll(draw).count(),
        )
        // ⭐ 每帧形态必须是「一次旋转变换 + 逐桶 drawPath」
        assertTrue("spray 必须在一个 withTransform 里整体旋转", spray.contains("withTransform("))
        assertTrue("必须绕天极旋转（不是画布中心）", spray.contains("rotate(rotDeg, pivot = Offset(poleX, poleY))"))
        assertTrue("必须逐桶 drawPath", spray.contains("drawPath("))
        assertTrue("必须按 SPRAY_BUCKETS 循环", spray.contains("while (b < SPRAY_BUCKETS)"))
        assertTrue("spray 必须加性叠加", spray.contains("blendMode = BlendMode.Plus"))
        assertTrue("spray 必须用预烘焙的描边样式", spray.contains("style = stroke"))
        // ⭐ 烘焙必须在尺寸/密度/画质守卫之内，且守卫是 draw 侧唯一入口
        assertTrue("烘焙的唯一调用点必须是 ensureSprayLayer", ensure.contains("rebuildSprayPaths("))
        assertEquals(
            "rebuildSprayPaths 只准「1 处声明 + ensureSprayLayer 内 1 处调用」",
            2, Regex("""rebuildSprayPaths\(""").findAll(src).count(),
        )
        assertEquals("drawContent 必须只调一次 ensureSprayLayer", 1, Regex("""ensureSprayLayer\(""").findAll(draw).count())
        assertTrue("守卫必须判 w / h / density / perBand 四个键", ensure.contains("sprayW == w") &&
            ensure.contains("sprayH == h") && ensure.contains("sprayStrokePx == SPRAY_W * density") &&
            ensure.contains("sprayPerBand == perBand"))
        // ⭐ 烘焙期必须先 moveTo：Compose 的 addArc 没有 forceMoveTo 参数，
        //    不先 moveTo 会把相邻两段弧连成横穿全场的直线（§4.6.1 的「鱼刺」缺陷）
        assertTrue("每段 spray 弧前必须 moveTo（= 强制新轮廓）", bake.contains("moveTo("))
        assertTrue("moveTo 必须落在 addArc 之前", bake.indexOf("moveTo(") < bake.indexOf("addArc("))
        assertTrue("必须走 companion 的活跃门（空档来源）", bake.contains("sprayActiveFor("))
        for (fn in listOf("sprayRadiusFor(", "sprayPhaseFor(", "spraySweepFor(", "sprayBucketFor(")) {
            assertTrue("烘焙必须调 companion 纯函数 $fn", bake.contains(fn))
        }
        // ⛔ 释放：Path 无 recycle() 可调，只能 reset()；位图仍由 releaseResources 回收
        val release = funBody(src, "releaseResources")
        assertTrue("releaseResources 必须调 resetSprayPaths", release.contains("resetSprayPaths()"))
        val reset = funBody(src, "resetSprayPaths")
        assertTrue("resetSprayPaths 必须能切出来", reset.isNotEmpty())
        assertTrue("resetSprayPaths 必须逐桶 reset", reset.contains("sprayPaths[i].reset()"))
        assertEquals("sprayPaths 必须按 SPRAY_BUCKETS 预分配", 1, Regex("""Array\(SPRAY_BUCKETS\) \{ Path\(\) \}""").findAll(src).count())
        // ⛔ 负向自证：把烘焙搬进每帧 / 改成逐弧绘制，判据必须挂
        assertFalse("⛔ 逐帧烘焙必须被判失败", noLiveSprayBake("fun drawContent() { rebuildSprayPaths(w, h, d, n) }"))
        assertFalse(
            "⛔ 逐弧绘制必须被判失败",
            noLiveSprayBake("fun drawSpray() { while (b < 384) { drawArc(color, 0f, 20f) ; b++ } }"),
        )
        assertTrue("成对自证：生产 drawSpray 必须被判通过", noLiveSprayBake(spray))
    }

    @Test
    fun `⑧ 零分配 draw 路径无字符串模板 无带参 Rect 无每帧容器`() {
        val src = codeOfE42()
        val rawBody = codeOfE42Raw()
        for (fn in DRAW_FNS) {
            val b = funBody(src, fn)
            val raw = funBody(rawBody, fn)
            assertTrue("$fn 必须能切出来（空转自证）", b.isNotEmpty())
            // ⚠️ 逐行判 + 逐行豁免（与 PerfBudgetContractTest 语义一致，见 anyOffendingLine）
            assertTrue("⛔ $fn 内不得出现字符串模板", !anyOffendingLine(b, raw, ::hasStringTemplate))
            assertTrue("⛔ $fn 内不得出现带参 Rect(", !anyOffendingLine(b, raw, ::hasAllocRect))
            assertTrue("⛔ $fn 内不得出现每帧容器分配", !anyOffendingLine(b, raw, ::hasContainerAlloc))
        }
        // 负向：喂同一份谓词的真实反例必须判失败
        // （字符串模板样本用字符字面量拼出来，避免在本文件里写出真的模板）
        val q = '"'
        val dollar = '$'
        val templateSnippet = "val label = $q" + "arc:$dollar" + "i$q"
        assertEquals("模板样本必须真的是字符串模板", "val label = \"arc:\$i\"", templateSnippet)
        assertFalse("负向：字符串模板", !hasStringTemplate(templateSnippet))
        assertFalse("负向：带参 Rect(", !hasAllocRect("cb.drawRect(Rect(0f, 0f, w, h), p)"))
        assertFalse("负向：listOf", !hasContainerAlloc("val xs = listOf(a, b)"))
        assertFalse("负向：sortedBy", !hasContainerAlloc("xs.sortedBy { it.second }"))
        // 所有 Paint / Path / RectF / FloatArray 必须是成员（构造期一次）
        for (m in listOf("ringPaint", "meteorPaint", "groundPaint", "branchPaint",
            "meteorRect", "meteorPath", "ridgePath", "trunkPath", "az", "env")) {
            assertTrue("$m 必须是预分配成员", src.contains("private val $m =") || src.contains("private var $m ="))
        }
        // ⭐ v1.5：spray 的路径池也必须是预分配成员（⛔ 不得逐帧建 Path）
        assertTrue(
            "sprayPaths 必须是按 SPRAY_BUCKETS 预分配的成员",
            src.contains("private val sprayPaths = Array(SPRAY_BUCKETS) { Path() }"),
        )
        assertTrue("spray 描边样式必须是成员字段（逐帧只读不新建）", src.contains("private var sprayStroke: Stroke? = null"))
        // ⛔ 逐帧路径不得 new Path / 重建 stroke
        assertFalse("⛔ drawSpray 不得 new Path", funBody(src, "drawSpray").contains("Path()"))
        assertFalse("⛔ drawSpray 不得 new Stroke", funBody(src, "drawSpray").contains("Stroke("))
        // ⛔ v1.4：已删除的成员不得以任何形式复活
        for (gone in listOf("decayPaint", "arcRect")) {
            assertFalse("⛔ $gone 必须已删除", src.contains(gone))
        }
        // ⛔ 每帧路径不得缩放（v1.3 的缓冲缩放已整体退场）
        val draw = funBody(src, "drawContent")
        assertTrue("drawContent 必须能切出来（空转自证）", draw.isNotEmpty())
        assertFalse("⛔ drawContent 不得带 withScale", draw.contains("withScale"))
        assertFalse("⛔ drawContent 不得缩放画布", Regex("""(?<![A-Za-z0-9_.])scale\s*\(""").containsMatchIn(draw))
    }

    @Test
    fun `⑧ 资源回收 onEnterContent 首行 releaseResources onExitContent 显式 recycle 底环位图`() {
        val src = codeOfE42()
        val enter = funBody(src, "onEnterContent")
        val exit = funBody(src, "onExitContent")
        val release = funBody(src, "releaseResources")
        assertTrue("onEnterContent 必须能切出来", enter.isNotEmpty())
        assertTrue("onExitContent 必须能切出来", exit.isNotEmpty())
        assertTrue("releaseResources 必须能切出来", release.isNotEmpty())
        // ⛔ onEnterContent 首行必须是 releaseResources（RendererSwapper.sync 画质切换会重入 onEnter）
        val firstStmt = enter.substringAfter('{').trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        assertTrue("⛔ onEnterContent 首行必须是 releaseResources()（实际：$firstStmt）", firstStmt == "releaseResources()")
        // ⛔ 重入 onEnter 不得复位时基 / 包络 / 柱方位角（切画质会看到「画面炸一下」）
        for (reset in listOf("t0Set = false", "t0Ms = 0L", "env.fill(", "mCount = 0", "az.fill(")) {
            assertFalse("⛔ onEnterContent 不得复位 $reset", enter.contains(reset))
        }
        assertTrue("柱方位角必须只抽一次（重入时保持）", enter.contains("if (!azReady)"))
        // ⛔ onExitContent 必须走 releaseResources
        assertTrue("onExitContent 必须调 releaseResources()", exit.contains("releaseResources()"))
        // ⛔ v1.4：只剩**一张**原生尺寸底环位图（ping-pong 双缓冲已删）⇒ 恰好一次 recycle
        assertEquals("必须恰好 recycle 一次（底环位图）", 1, Regex("""\.recycle\(\)""").findAll(release).count())
        assertTrue("必须 recycle 底环位图", release.contains("rings?.asAndroidBitmap()?.recycle()"))
        assertTrue("recycle 必须被 try 包住", Regex("""try\s*\{[^}]*recycle\(\)""").containsMatchIn(release))
        // ⭐ v1.5：spray 是 `Path`（⛔ 无 `recycle()` 可调 —— 只有位图才有），只能 `reset()`
        assertTrue("releaseResources 必须清 spray 路径", release.contains("resetSprayPaths()"))
        assertFalse(
            "⛔ 不得对 Path 调 recycle（API 不存在，真调会编译不过）",
            Regex("""sprayPaths\[[^\]]*\][^;\n]*\.recycle\(\)""").containsMatchIn(src),
        )
        // ⛔ 不得替别人释放共享资源
        assertFalse("⛔ 渲染器不得调 ProceduralTexture.release()", src.contains("ProceduralTexture.release("))
        assertFalse("⛔ 渲染器不得调 OverlayFx.release()", src.contains("OverlayFx.release("))
    }

    @Test
    fun `⑧ ProceduralTexture 只烘 STARFIELD 且只在重建路径 不在每帧 drawContent`() {
        val src = codeOfE42()
        val draw = funBody(src, "drawContent")
        assertTrue("drawContent 必须能切出来（空转自证）", draw.isNotEmpty())
        assertFalse("⛔ ensure 不得出现在 drawContent 内", draw.contains("ProceduralTexture.ensureFullscreenOnly("))
        assertFalse(
            "⛔ ensure 不得出现在 onEnterContent 内（统一走 rebuildGeometry）",
            funBody(src, "onEnterContent").contains("ProceduralTexture.ensureFullscreenOnly("),
        )
        val rebuild = funBody(src, "rebuildGeometry")
        assertTrue("rebuildGeometry 必须能切出来", rebuild.isNotEmpty())
        assertTrue(
            "纹理烘焙的唯一调用点必须在 rebuildGeometry（进入 + 尺寸变化）",
            rebuild.contains("ProceduralTexture.ensureFullscreenOnly("),
        )
        assertEquals(
            "ensureFullscreenOnly 必须只有一个调用点",
            1, Regex("""ProceduralTexture\.ensureFullscreenOnly\(""").findAll(src).count(),
        )
        // ⛔ ⭐ 本效果**只画 STARFIELD** ⇒ 绝不许调「一次烘 6 张全屏纹理」的 ensure
        //    （1920×1080×6 ≈ 1240 万像素 Kotlin 逐像素 + 6480 次 JNI setPixels，同步落在首帧
        //      ⇒ 真机实测冷启动首帧黑屏 6369 ms）。
        assertFalse(
            "⛔ E42 不得调 ProceduralTexture.ensure(（那会为 5 张永不画的纹理付费）",
            src.contains("ProceduralTexture.ensure("),
        )
        assertTrue(
            "⛔ 必须只点名 STARFIELD（不得一次烘多张）",
            Regex("""ProceduralTexture\.ensureFullscreenOnly\(\s*ProceduralTexture\.Id\.STARFIELD""").findAll(src).count() == 1,
        )
        // onEnterContent 必须能触发重建（否则首帧没有渐变 / 剪影 / 纹理）
        assertTrue("onEnterContent 必须调 rebuildGeometry", funBody(src, "onEnterContent").contains("rebuildGeometry("))
        // 尺寸变化分支：先释放再重建（否则旧位图泄漏）
        assertTrue("drawContent 必须在尺寸变化时先 releaseResources", draw.contains("releaseResources()"))
        assertTrue("drawContent 必须在尺寸变化时重建几何", draw.contains("rebuildGeometry("))
        // ⛔ v1.4：底环烘焙**不在** rebuildGeometry（那里没有 density）⇒ 走 draw 侧惰性建
        assertFalse("⛔ rebuildGeometry 不得烘焙底环（那里拿不到 Density）", rebuild.contains("rebuildRingLayer("))
    }


    // ══════════════════════════════ ⑨ 源码段：流星弧线 / 地景（G10 源码面）══════════════════════════════

    @Test
    fun `⑨ 流星必须画圆弧 且方向由 dir 决定 不得退回直线弦`() {
        val src = codeOfE42()
        val meteors = funBody(src, "drawMeteors")
        assertTrue("drawMeteors 必须能切出来（空转自证）", meteors.isNotEmpty())
        assertTrue("⛔ 流星尾巴必须走 Path.addArc（几何上是弧）", meteors.contains("addArc("))
        assertTrue("扫掠角必须由 mDir 决定方向", meteors.contains("mDir[i]"))
        assertFalse("⛔ 不得用 moveTo 画流星头尾", meteors.contains("moveTo("))
        assertFalse("⛔ 不得用 lineTo 画流星头尾（直线弦 ⇒ 绕极一圈成『鱼刺』）", meteors.contains("lineTo("))
        assertFalse("⛔ 不得用直线 distanceTo 之类的弦长近似", meteors.contains("hypot("))
        // 弧跨度必须落在「视觉接近直线但几何是弧」的窄区间
        assertTrue("弧跨度下界必须是 0.12 rad（约 7°）", C.METEOR_LEN_MIN >= 0.10f && C.METEOR_LEN_MIN <= 0.15f)
        assertTrue("弧跨度上界必须是 0.34 rad（约 19.5°）", C.METEOR_LEN_MAX >= 0.30f && C.METEOR_LEN_MAX <= 0.38f)
        // 流星池必须是定长 FloatArray（⛔ 不用 List）
        assertTrue("流星池必须是定长数组", src.contains("private val mR = FloatArray(METEOR_MAX)"))
        assertFalse("⛔ 流星不得用 List 承载", Regex("""mutableListOf|ArrayList|List<""").containsMatchIn(meteors))
    }

    @Test
    fun `⑨ 枯枝必须描边加逐段衰减 抖动必须确定性 且画面中不得有人物剪影`() {
        val src = codeOfE42()
        val file = codeOfE42File()
        val limb = funBody(src, "addLimb")
        val ground = funBody(src, "buildGroundPaths")
        assertTrue("addLimb 必须能切出来（空转自证）", limb.isNotEmpty())
        assertTrue("buildGroundPaths 必须能切出来（空转自证）", ground.isNotEmpty())
        // 描边 + 圆头圆角
        assertTrue("枯枝画笔必须是 ROUND 端点", src.contains("strokeCap = Paint.Cap.ROUND"))
        assertTrue("枯枝画笔必须圆角连接", src.contains("strokeJoin = Paint.Join.ROUND"))
        assertTrue("枯枝必须用 Style.STROKE（⛔ 不得堆叠三角形冒充）", src.contains("Paint.Style.STROKE"))
        // 逐段线宽衰减 + 0.7px 下限
        assertTrue("必须逐段衰减线宽", limb.contains("LIMB_TAPER"))
        assertTrue("必须有线宽下限", limb.contains("LIMB_MIN_W"))
        assertEquals("枯枝线宽下限必须 0.7px", 0.7f, C.LIMB_MIN_W, 1e-6f)
        assertTrue("线宽衰减系数必须落在 (0,1)", C.LIMB_TAPER > 0f && C.LIMB_TAPER < 1f)
        // 5 条主枝 + 分叉
        assertEquals("必须恰好 5 条主枝", 5, C.LIMBS.size)
        assertEquals("其中 2 条带二次分叉", 2, C.LIMBS.count { it.depth > 0 })
        assertEquals("主枝段数上限必须覆盖 4+12+12+4+4 = 36", 36, countLimbSegments())
        assertTrue("段数上限必须留有余量（实际 ${C.LIMB_SEG_MAX}）", C.LIMB_SEG_MAX >= countLimbSegments())
        // 抖动必须确定性（扫描面 = 整份文件，已剥注释）
        assertTrue("抖动必须走确定性哈希", limb.contains("hash01("))
        assertTrue("buildGroundPaths 也必须走确定性哈希", ground.contains("hash01(") || limb.contains("hash01("))
        for (nondet in listOf("Math.random(", "kotlin.random.", "Random(", "nextDouble(", "nanoTime")) {
            assertFalse("⛔ 枯枝抖动不得使用非确定源 $nondet", file.contains(nondet))
        }
        // ⛔ 无人物剪影（两轮原型均判失败后整体删除；留死代码等于把已否决的设计带进产品）
        for (person in PERSON_TOKENS) {
            assertFalse("⛔ 不得出现人物剪影标识 $person", file.contains(person))
        }
        assertTrue("成对自证：判据能识别人物剪影代码", !noPersonSilhouette("fun drawPerson() { drawCircle(1f) }"))
    }

    @Test
    fun `⑨ 山脊路径必须与 ridgeYAt 同源 控制点 x 取 1_3 与 2_3`() {
        val ground = funBody(codeOfE42(), "buildGroundPaths")
        assertTrue("必须用三次贝塞尔画脊线", ground.contains("cubicTo("))
        assertTrue("控制点 1 的 x 必须是 x0 + dx/3", ground.contains("g.x0 + dx / 3f"))
        assertTrue("控制点 2 的 x 必须是 x0 + 2dx/3", ground.contains("g.x0 + dx * 2f / 3f"))
        // ⛔ 不得在路径里另写一份独立的 y 插值（那正是「路径与取点不同源」的病根）
        assertFalse("⛔ 不得在路径里用 moveTo 之外的插值取 y", ground.contains("ridgeY("))
        // 树根必须由 ridgeYAt 采样得到
        assertTrue("树根必须采样 ridgeYAt", ground.contains("ridgeYAt("))
        // 地面必须画在星轨之上（先画环与亮线段、再画剪影）
        val draw = funBody(codeOfE42(), "drawContent")
        val ringIdx = draw.indexOf("ensureRingLayer(")
        val segIdx = draw.indexOf("drawSegments(")
        val groundIdx = draw.indexOf("drawGroundForeground()")
        assertTrue("drawContent 必须同时有星轨与地景", ringIdx >= 0 && segIdx >= 0 && groundIdx >= 0)
        assertTrue("⛔ 底环必须在亮线段之前（否则亮线段画在环之下）", segIdx > ringIdx)
        assertTrue("⛔ 地景必须画在星轨之后（否则星轨会穿到地面下方）", groundIdx > segIdx)
    }

    // ══════════════════════════════ ⑩ 负向自证 ═══════════════════════════════

    @Test
    fun `负向① sweepForAmp 改成线性直返 必须判失败`() {
        // 复刻「把 amp 当线性直接返回」的破实现
        val broken = { amp: Float -> C.MAX_SWEEP_DEG * amp }
        assertTrue("对照：破实现在门限处确实画出弧（实际 ${broken(C.SILENT_FLOOR)}）", broken(C.SILENT_FLOOR) > 0f)
        assertEquals("⛔ 门限处生产实现必须为 0（破实现不是）", 0f, C.sweepForAmp(C.SILENT_FLOOR), 1e-6f)
        assertNotEquals("⛔ 门限处两者必须分歧", 0f, broken(C.SILENT_FLOOR), 1e-6f)
        // 门限归一化 ⇒ 中段被压低于线性直返
        assertTrue("中段扫掠角必须低于线性直返", C.sweepForAmp(0.5f) < broken(0.5f))
        assertEquals("中段扫掠角 = MAX × (amp − floor)/(1 − floor)", C.MAX_SWEEP_DEG * (0.5f - C.SILENT_FLOOR) / (1f - C.SILENT_FLOOR), C.sweepForAmp(0.5f), 1e-4f)
    }

    @Test
    fun `负向② treeXFor 去掉 maxOf 下界 必须判失败`() {
        // 复刻「只取环外中点、不加枝展下界」的破实现
        val broken = { px: Float, rOuter: Float -> (px - rOuter) * 0.5f }
        val w = 520f
        val h = 900f
        val out = FloatArray(2)
        C.poleCenterFor(w, h, out)
        val rOuter = C.outerRadiusFor(w, h, out[0], out[1], min(w, h))
        val bad = broken(out[0], rOuter)
        assertTrue("对照：破实现在竖屏下确实算出负的树心（实际 $bad）", bad < 0f)
        assertFalse("⛔ 负树心必须被判失败", bad >= 0f)
        assertTrue("生产实现必须给出正树心", C.treeXFor(out[0], rOuter, w, h) >= 0f)
    }

    @Test
    fun `负向③ RIDGE 控制点改 0_25 与 0_75 必须让反解断言失败`() {
        // ⭐ 这条负向的作用：证明 ④ 的核心断言**依赖**「控制点 x 取 1/3、2/3」这一前提。
        //   线性反解 t = (u − x0)/dx 只在 x(t) 线性时成立；控制点挪到 1/4、3/4 后前提消失。
        val seg = C.RIDGE[0]
        val dx = seg.x1 - seg.x0
        val w = 1920f
        val h = 1080f
        val horizonY = C.HORIZON_K * h

        // 1/4、3/4 方案下：x(t) 不再线性
        var worstX = 0f
        for (k in 1..99) {
            val t = k / 100f
            val xQuarter = bezierX(t, seg.x0, seg.x0 + dx / 4f, seg.x0 + dx * 3f / 4f, seg.x1)
            worstX = max(worstX, abs(xQuarter - (seg.x0 + dx * t)))
        }
        assertTrue("⛔ 1/4 与 3/4 方案下 x(t) 必须偏离线性（实测最大偏差 $worstX）", worstX > 1e-3f)

        // 取 t = 0.25 处的几何点，用「线性反解」去取 y ⇒ 必须偏离 Bernstein 真值
        val xQuarterAt25 = bezierX(0.25f, seg.x0, seg.x0 + dx / 4f, seg.x0 + dx * 3f / 4f, seg.x1)
        val naiveT = ((xQuarterAt25 - seg.x0) / dx).coerceIn(0f, 1f)
        val yTrue = horizonY + bernsteinY(seg, 0.25f) * h
        val yByLinearInversion = horizonY + bernsteinY(seg, naiveT) * h
        assertTrue("对照：线性反解给出的参数确实不等于 0.25（实际 $naiveT）", abs(naiveT - 0.25f) > 1e-3f)
        assertTrue(
            "⛔ 前提被打破后线性反解必须出现可观测偏差（否则 ④ 是空转）：${abs(yTrue - yByLinearInversion)}px",
            abs(yTrue - yByLinearInversion) > 0.5f,
        )

        // ⭐ 而生产实现（1/3、2/3）在**全部**采样点上必须始终精确
        var worst = 0f
        for (s in C.RIDGE) {
            val d = s.x1 - s.x0
            for (k in 0..100) {
                val t = k / 100f
                val got = C.ridgeYAt((s.x0 + d * t) * w, w, horizonY, h)
                worst = max(worst, abs(got - (horizonY + bernsteinY(s, t) * h)))
            }
        }
        assertTrue("⛔ 生产 ridgeYAt 与 Bernstein 真值最大偏差必须 < 1e-3 px（实测 $worst）", worst < 1e-3f)
    }

    @Test
    fun `负向④ 流星改回 lineTo 直线弦 必须被源码判据抓到`() {
        val lineToSnippet = """
            private fun drawMeteors(cb: Canvas, dt: Float) {
                meteorPath.reset()
                meteorPath.moveTo(hx, hy)
                meteorPath.lineTo(tx, ty)
                cb.drawPath(meteorPath, meteorPaint)
            }
        """.trimIndent()
        assertTrue("样本确实用了 lineTo（证明这条负向不是空转）", lineToSnippet.contains("lineTo("))
        assertFalse("⛔ lineTo 片段必须被判失败", meteorIsArcs(lineToSnippet))
        assertFalse("⛔ 缺 addArc 的片段必须被判失败", meteorIsArcs("fun drawMeteors() { val x = 1f }"))
        assertTrue("生产实现必须被判通过", meteorIsArcs(funBody(codeOfE42(), "drawMeteors")))
    }

    @Test
    fun `负向⑤ 引入禁物与人物剪影 必须被源码判据抓到`() {
        assertFalse("⛔ RuntimeShader", noForbiddenApi("val s = RuntimeShader(GLSL)"))
        assertFalse("⛔ RenderEffect", noForbiddenApi("val e = RenderEffect.createBlurEffect(1f, 1f, true)"))
        assertFalse("⛔ GLSurfaceView", noForbiddenApi("class X : GLSurfaceView(ctx)"))
        assertFalse("⛔ BitmapShader", noForbiddenApi("val bs = BitmapShader(bmp, TileMode.CLAMP, TileMode.CLAMP)"))
        assertFalse("⛔ BlendMode.Difference", noForbiddenApi("drawRect(c, blendMode = BlendMode.Difference)"))
        assertFalse("⛔ RoundedCornerShape", noForbiddenApi("val r = RoundedCornerShape(8f)"))
        assertFalse("⛔ 圆角 clip", noForbiddenApi("clipPath(roundRect)"))
        assertFalse("⛔ drawPerson", noPersonSilhouette("fun drawPerson() {}"))
        assertFalse("⛔ drawChild", noPersonSilhouette("fun drawChild() {}"))
        assertFalse("⛔ headCircle", noPersonSilhouette("headCircle.draw()"))
        assertTrue("成对自证：合规片段必须被判通过", noForbiddenApi("drawRect(brush, alpha = 0.4f)"))
        assertTrue("成对自证：地景片段必须被判通过", noPersonSilhouette("fun drawGroundForeground() {}"))
    }

    @Test
    fun `负向⑥ 每帧固定增量与固定系数 EMA 必须被判帧率绑定`() {
        // 破实现 A：每帧固定角增量（不看 dt）
        val perFrame = { frames: Int -> frames * 3.2f / 60f }
        assertEquals("60fps 与 30fps 结果必须差 2 倍", 0.5f, perFrame(30) / perFrame(60), 1e-6f)
        assertEquals(
            "生产实现在同一时刻与帧率无关",
            C.poleAngleDeg(1000L, 0L), C.poleAngleDeg(1000L, 0L), 0f,
        )
        // 破实现 B：每帧固定系数的 EMA（WorldRenderer 形态）
        val perFrameEma = { frames: Int, k: Float -> (1f - k).pow(frames.toFloat()) }
        val at60: Float = perFrameEma(18, 0.06f)
        val at30: Float = perFrameEma(9, 0.06f)
        assertTrue("每帧固定系数 EMA 必须表现出强帧率依赖（$at60 vs $at30）", abs(at60 - at30) > 0.2f)
        assertTrue("生产实现的释音包络必须接近", abs(releaseAfter(0.3f, 60) - releaseAfter(0.3f, 30)) < 0.02f)
    }

    // ══════════════════════════════ 谓词（正向 / 负向共用） ═══════════════════════════════

    private fun noForbiddenApi(src: String): Boolean = FORBIDDEN.none { src.contains(it) }

    private fun noPersonSilhouette(src: String): Boolean = PERSON_TOKENS.none { src.contains(it) }

    private fun meteorIsArcs(body: String): Boolean =
        body.contains("addArc(") && !body.contains("lineTo(") && !body.contains("moveTo(")

    /** v1.4：ping-pong 累积缓冲机械的**唯一**判据（正向 / 负向共用）。 */
    private val ACCUM_TOKENS = listOf(
        "PorterDuff", "DST_OUT", "decayPaint", "prevCanvas", "currCanvas",
        "TRAIL_TAU_S", "decayAlphaFor", "withScale", "bufScale",
        "TRAIL_W_FULL", "TRAIL_W_LITE", "TRAIL_W_OFF", "rebuildBuffers", "releaseBuffers",
    )

    private fun noAccumBuffer(src: String): Boolean = ACCUM_TOKENS.none { src.contains(it) }

    /** 弧线是否被画进了降采样 / 离屏缓冲（v1.3 那四个真机缺陷的根因面）。 */
    private fun noScaledArcBuffer(body: String): Boolean =
        !body.contains("withScale") &&
            !body.contains("saveLayer") &&
            !Regex("""(?<![A-Za-z0-9_.])scale\s*\(""").containsMatchIn(body)

    /**
     * v1.5：spray 弧**不得**逐帧逐弧构造 —— 384 次 `drawArc`/`addArc` 会把
     * 真机 29.7fps 砍半。合规形态是「一次 `withTransform` + 逐桶 `drawPath`」。
     */
    private fun noLiveSprayBake(body: String): Boolean =
        !body.contains("addArc(") &&
            !body.contains("drawArc(") &&
            !body.contains("arcTo(") &&
            !body.contains("rebuildSprayPaths(")

    private fun hasStringTemplate(s: String): Boolean = Regex(""""[^"\n]*\$[^"\n]*"""").containsMatchIn(s)

    private fun hasAllocRect(s: String): Boolean = Regex("""(?<![A-Za-z0-9_])Rect\(\s*[^)\s]""").containsMatchIn(s)

    private fun hasContainerAlloc(s: String): Boolean =
        Regex("""\b(listOf|mutableListOf|arrayListOf|mapOf)\s*\(""").containsMatchIn(s) ||
            Regex("""\.(map|sortedBy|sortedByDescending)\s*\{""").containsMatchIn(s)

    /**
     * 逐行判据，**逐行**放行 `// Perf-exempt:` 标记行 —— 与项目权威门禁
     * `PerfBudgetContractTest.isExemptLine` 的语义保持一致。
     *
     * ⚠️ [rawBody] 是**未剥注释**的同一函数体：豁免标记写在行尾注释里，而判据读的是
     * 剥完注释的 [body]（`Rect(` 之类必须看剥后的文本）。两者行号一一对应
     * （[stripComments] 保证换行不丢），故按行号取豁免表。
     *
     * ⛔ 不用「整段一刀切」的形式：那会让「烘焙期一次性构造」与「每帧路径违规」无法区分，
     *    豁免机制形同虚设（本类的 `rebuildSprayPaths` 正是靠这条放行 `Path.addArc` 所需的
     *    不可变 `Rect(...)`，它只在尺寸/密度变化时执行，**不在**每帧路径上）。
     */
    private fun anyOffendingLine(body: String, rawBody: String, pred: (String) -> Boolean): Boolean {
        val exempt = rawBody.split('\n').map { it.contains("Perf-exempt") }
        return body.split('\n').withIndex().any { (i, line) ->
            (exempt.getOrNull(i) != true) && pred(line)
        }
    }

    private fun countLimbSegments(): Int =
        C.LIMBS.sumOf { limb -> C.LIMB_SEGS * (if (limb.depth > 0) 1 + 2 else 1) }

    // ══════════════════════════════ 源码定位 ═══════════════════════════════

    /** 逐帧 / 重建路径上的函数（判据作用面）。 */
    private val DRAW_FNS = listOf(
        "drawContent", "drawSegments", "drawSpray", "fieldEnergy",
        "drawMeteors", "spawnMeteors", "removeMeteorAt",
        "drawGroundForeground", "addLimb", "buildGroundPaths", "rebuildGeometry",
        "ensureRingLayer", "rebuildRingLayer", "recycleRingLayer",
        "ensureSprayLayer", "rebuildSprayPaths", "resetSprayPaths",
        "releaseResources", "onEnterContent", "onExitContent",
    )

    /** 硬禁物（本项目无 shader 红线 + API 22 段错误 + API 29 才有的混合模式）。 */
    private val FORBIDDEN = listOf(
        "RuntimeShader", "RenderEffect", "GLSurfaceView", "BitmapShader", "Shader.TileMode",
        "BlendMode.Difference", "RoundedCornerShape", "clipPath(", "clip(", "GLES20", "EglCore",
    )

    /** 人形剪影标识（用户两轮判失败后整体删除；防止死代码回流）。 */
    private val PERSON_TOKENS = listOf(
        "drawPerson", "drawChild", "drawFigure", "drawHuman", "drawSilhouettePerson",
        "personPath", "childPath", "figurePath", "headCircle", "torso", "shoulder",
    )

    private fun codeOfE42(): String = stripComments(classBody(readSrc(), "StarrySkyRenderer"))

    private fun codeOfE42Raw(): String = classBody(readSrc(), "StarrySkyRenderer")

    /** 整份源文件（**已剥注释**）—— 禁物扫描面比类体更严（含类 KDoc）。 */
    private fun codeOfE42File(): String = stripComments(readSrc())

    private fun readSrc(): String = readMain("com/nasmusic/tv/visualizer/renderers/StarrySkyRenderer.kt")

    private fun readMain(rel: String): String = File(mainSourceRoot(), rel).readText()

    /** 花括号配对切出 `class <name>` 的类体（含其内部 companion）。 */
    private fun classBody(txt: String, name: String): String {
        val m = Regex("""(?m)^[ \t]*(?:(?:internal|open|abstract|private|sealed|final)[ \t]+)*class[ \t]+$name\b""")
            .find(txt) ?: return ""
        val brace = txt.indexOf('{', m.range.last)
        if (brace < 0) return ""
        var depth = 0
        var i = brace
        while (i < txt.length) {
            when (txt[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return txt.substring(brace, i + 1)
                }
            }
            i++
        }
        return txt.substring(brace)
    }

    /** 从**已剥注释**的类体里取某个函数的 `{...}` 体（签名认 receiver `DrawScope.`）。 */
    private fun funBody(classBody: String, name: String): String {
        val m = Regex("""fun[ \t\r\n]+(?:[A-Za-z0-9_]+\.)?$name[ \t\r\n]*\(""").find(classBody) ?: return ""
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

    /**
     * 去注释（行注释 + **嵌套**块注释 + 字符串感知），块注释内换行保留以保持行号。
     *
     * ⚠️ 本 KDoc 正文刻意**不**写块注释定界符的字面量，否则会提前闭合本注释。
     */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '"') {
                sb.append(c); i++
                while (i < src.length) {
                    sb.append(src[i])
                    if (src[i] == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                    i++
                    if (sb[i - 1] == '"') break
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
                    if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') { depth++; i += 2 }
                    else if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') { depth--; i += 2 }
                    else { if (src[i] == '\n') sb.append('\n'); i++ }
                }
                continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

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
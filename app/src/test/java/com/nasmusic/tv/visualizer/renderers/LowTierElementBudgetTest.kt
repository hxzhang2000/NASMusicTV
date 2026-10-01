package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.data.model.VisualQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 低画质档的**元素预算**门禁（v1.47 真机低画质全效果扫描之后新增）。
 *
 * ## 为什么判据是"元素数"而不是"提交次数"
 * 这三套效果的 `drawPath` 提交次数**本来就已经合批到位**（E11 7 次 / E17 9 次 / E23 6 次），
 * 再省也无从省起；贵的是每次提交里装的互不相连的小图形个数。本机（创维 5.1.1 / API 22 / 1080p）
 * 在同一条歌上的两个实测锚点：
 * - **478 个零散小矩形/帧 ⇒ 7.6 fps**（E16 数字雨合并前）
 * - **≈24 个/帧 ⇒ 59.4 fps**（E16 列条带合并后）
 *
 * ⇒ 数量级上每帧几百个零散元素就是个位数帧率，这正是扫描里 E11/E17/E23 落在 7–11 fps 的成因。
 * ⚠️ 本文件用的是**数量级判据**，不是逐元素精确计数（§九 R18：静态估算只能决定先量哪几套，
 * 不是验收判据 ⇒ 真机帧率仍以屏上角标 / SurfaceFlinger 为准）。
 */
class LowTierElementBudgetTest {

    private companion object {
        /** 绝对预算上界：落在实测曲线的"几十而非几百"一侧（≈30 fps 量级） */
        const val ABS_BUDGET = 400

        /** 相对降幅下界：低档必须比改造前至少省到这个倍数 */
        const val MIN_CUT = 2.0f

        /** E17 的 §7.5 登记值（MEDIUM 档、160 颗星时的路径·文本 JNI 总数） */
        const val CONSTELLATION_MEDIUM_ELEMS = 805f
        const val CONSTELLATION_MEDIUM_STARS = 160f

        /** E23 改造前的每行粒子上限（低档曾与中高档同值），只用于 ②③ 两条判据 */
        const val OLD_CAP_PER_LINE = 3600

        /** E11 改造前的低档每臂星点数（曾与中档同值 55） */
        const val OLD_PER_ARM = 55
        const val OLD_STARS = 160
    }

    @Test
    fun `① 档位函数按档单调且低档严格更省`() {
        val g = GalaxySpiralRenderer
        assertTrue("星系螺旋：每臂星点数必须 HIGH ≥ MEDIUM ≥ LOW",
            g.perArmFor(VisualQuality.HIGH) >= g.perArmFor(VisualQuality.MEDIUM) &&
                g.perArmFor(VisualQuality.MEDIUM) > g.perArmFor(VisualQuality.LOW))
        // ⛔ 中/高档一个都不能动：本轮只放宽低画质的可选性，改中档观感属于越界
        assertEquals("星系螺旋中档必须仍是改造前的 55/臂", 55, g.perArmFor(VisualQuality.MEDIUM))
        assertEquals("星系螺旋高档必须仍是改造前的 90/臂", 90, g.perArmFor(VisualQuality.HIGH))

        val c = ConstellationRenderer
        assertEquals("星座中档星点池必须仍是 §A7 的 160 颗", c.STARS, c.starCapFor(VisualQuality.MEDIUM))
        assertEquals("星座高档星点池必须仍是 §A7 的 160 颗", c.STARS, c.starCapFor(VisualQuality.HIGH))
        assertTrue("星座：低档池必须严格小于中档",
            c.starCapFor(VisualQuality.LOW) < c.starCapFor(VisualQuality.MEDIUM))

        val l = LyricsDotMatrixRenderer
        assertEquals("歌词点阵中档必须仍是 3600/行", 3600, l.capacityForTier(VisualQuality.MEDIUM))
        assertEquals("歌词点阵高档必须仍是 3600/行", 3600, l.capacityForTier(VisualQuality.HIGH))
        assertTrue("歌词点阵：低档必须严格小于中档",
            l.capacityForTier(VisualQuality.LOW) < l.capacityForTier(VisualQuality.MEDIUM))
    }

    @Test
    fun `② 低档每帧元素预算 - 可达的落进绝对上界 不可达的至少降够倍数`() {
        val low = VisualQuality.LOW

        // E11 星点数 = 臂数 × 每臂点数。尘埃带另计 16 臂 × 24 段，但那是**同一条 Path** 里的
        // 连续折线（相邻线段首尾相接 ⇒ 不产生零散矩形），故不计入本判据。
        val galaxy = GalaxySpiralRenderer.ARMS * GalaxySpiralRenderer.perArmFor(low)
        assertTrue("星系螺旋低档星点 $galaxy 个必须 ≤ $ABS_BUDGET", galaxy <= ABS_BUDGET)
        assertTrue("星系螺旋低档必须比改造前（${GalaxySpiralRenderer.ARMS * OLD_PER_ARM} 个）降够 " +
            "${MIN_CUT}×", galaxy * MIN_CUT <= GalaxySpiralRenderer.ARMS * OLD_PER_ARM)

        // E17 线元素：网格分桶后每星只查邻接 9 桶 ⇒ 成对数 ∝ 密度² ⇒ 按 (容量比)² 外推
        val ratio = ConstellationRenderer.starCapFor(low) / CONSTELLATION_MEDIUM_STARS
        val constellation = CONSTELLATION_MEDIUM_ELEMS * ratio * ratio
        assertTrue("星座低档线元素 ≈${constellation.toInt()} 个必须 ≤ $ABS_BUDGET",
            constellation <= ABS_BUDGET)
        assertTrue("星座低档必须比改造前的 805 个降够 ${MIN_CUT}×",
            constellation * MIN_CUT <= CONSTELLATION_MEDIUM_ELEMS)

        // E23 点数 = 每行 cap × 2 行 × (1 层 core + 最多 1 层外辉光)
        val lyrics = LyricsDotMatrixRenderer.capacityForTier(low) * 2 * 2
        assertTrue("歌词点阵低档点数 $lyrics 个必须 ≤ ${OLD_CAP_PER_LINE * 2}（改造前 14400，降 4×）",
            lyrics <= OLD_CAP_PER_LINE * 2)
        // ⛔ 本套**不可能**落进 $ABS_BUDGET：400 个点摊不到一行可读的歌词上。
        // 低档要真上 30 fps 只能走 E16 那条路（把点阵预合成成条带位图、每帧只做 blit），
        // 那是另一次改造，不在本轮；本轮只做「降密度换帧率」。
    }

    @Test
    fun `③ 负向自证 改造前的低档取值必须被同一条判据拒掉`() {
        // 判据若对改造前的数字也放行，就说明判据根本没在工作
        assertTrue("旧的低档 ${GalaxySpiralRenderer.ARMS * OLD_PER_ARM} 个星点必须超标",
            GalaxySpiralRenderer.ARMS * OLD_PER_ARM > ABS_BUDGET)
        val oldRatio = OLD_STARS / CONSTELLATION_MEDIUM_STARS
        assertTrue("旧的 160 颗星（§7.5 登记 805 个线元素）必须超标",
            CONSTELLATION_MEDIUM_ELEMS * oldRatio * oldRatio > ABS_BUDGET)
        assertTrue("旧的 3600 点/行（14400 个椭圆）必须没降够 4×",
            OLD_CAP_PER_LINE * 2 * 2 > OLD_CAP_PER_LINE * 2)
    }

    @Test
    fun `④ 字号上限随粒子上限收缩 且永远不得压到下限以下`() {
        val l = LyricsDotMatrixRenderer
        val hi = l.capacityForTier(VisualQuality.MEDIUM)
        val lo = l.capacityForTier(VisualQuality.LOW)
        // 密度恒定：点阵密度 = 点数 / 字面积 ⇒ 点数减半时线性字号要按 sqrt 收缩
        assertTrue("低档字号上限必须更小（否则减点会让文字出空洞）",
            l.fontMaxRatio(lo) < l.fontMaxRatio(hi))
        val expected = 0.16f * kotlin.math.sqrt(lo.toFloat() / hi.toFloat())
        assertEquals("字号上限必须按 sqrt(cap) 等比收缩", expected, l.fontMaxRatio(lo), 1e-6f)
        // ⛔ 这条是**崩溃门禁**，不是观感门禁：computeFontSize 走 `coerceIn(下限, 上限)`，
        // 上限一旦低于下限就抛 IllegalArgumentException ⇒ 整帧绘制异常。
        assertTrue("低档字号上限 ${l.fontMaxRatio(lo)} 必须仍 ≥ 可读性下限 ${l.MIN_FONT_S}（否则 coerceIn 抛）",
            l.fontMaxRatio(lo) >= l.MIN_FONT_S)
        assertTrue("改造前那档字号上限（0.16）同样必须 ≥ 下限", 0.16f >= l.MIN_FONT_S)
    }
}

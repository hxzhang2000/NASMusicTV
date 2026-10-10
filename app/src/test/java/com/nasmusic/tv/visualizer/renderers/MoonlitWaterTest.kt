package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI

/**
 * E44「明月」**T8 水面门禁**（§7.2 光柱 / §7.3 粼光 / §7.6 地平带 + 层 7 接线）。
 *
 * ## 这一类管什么
 * `MoonOpBudget` 的水面三行（`HORIZON_BAND` / `REFLECTION` / `GLITTER`）从此**不再由探针打印值手抄**：
 * ③④⑤ 三例直接调 [MoonWater] 与 [MoonWater.MoonGlitterFrame] 的**生产几何**把每一行重算一遍，
 * 再与表对账。渲染器画的和账本记的是同一份代码 ⇒ "表与几何各说各话"（D10 / D24 的病根）有门可红。
 *
 * ## 三条纪律（都是本轮踩出来的，⚠️ 全部跑在**剥掉注释**的源码上）
 * 1. **表 ≥ 实测，且按 3 位向上取整**：③b/⑤ 各有一条"表 − 实测 ≤ 千分之一屏"的上界，
 *    防止有人把"向上取整"理解成"多记点更保险"。就近取整已经**当场被这两条抓到三次**
 *    （`GLITTER` MED 与 `REFLECTION` LOW/MED，见 [MoonOpItem] 的 KDoc）。
 * 2. **口径必须写明参照系**：粼光填充与**画幅尺度**无关、但与 **`density` 有关**
 *    （`1.2·density` 设备像素地板，偏差 **D29**）⇒ ④ 钉尺度不变，④b 钉密度矩阵**且**要求
 *    高密度那一截仍落在档位余量内。⛔ 两条不能合成一条"分辨率无关"了事。
 * 3. **负向自证必须真的会塌**：①b（`or` 替 `xor` ⇒ 300 个站位塌成几十种）、②b（每行均摊 `PER`
 *    条 ⇒ LOW 的 ops 顶穿 90）、⑥b（分桶刻度砍半 ⇒ α 量化误差越界）、④b（地板**确实**触发，
 *    否则 D29 那条叙述是空判据）。
 *
 * ## 与原型逐位对账的部分
 * ①②③④⑨ 的全部期望值来自 `docs/moonlit-preview.html:354-379 / :1221-1273` 的同一套算术
 * （JS float64 ⇒ Kotlin `Double`），最坏帧扫描网格 `t = 0..1000 s`、步长 `0.25 s`、4001 帧。
 * ⚠️ 改云场/粼光的任何一段数学都可能让**argmax 换帧** —— 那会红在 ③ 的时间戳断言上，
 * 这是有意的：粼光是无状态的，换帧意味着分布变了，⛔ 不能只把表值改大就算"对账通过"。
 */
class MoonlitWaterTest {

    private val frame = MoonWater.MoonGlitterFrame()

    // ── ① 哈希三件套与原型逐位对账 ─────────────────────────────────────────────

    @Test
    fun `① hash1 noise1 fbm1 与原型逐位一致`() {
        // 原型 `:354-358`（murmur 尾段）。这些数由 node 跑原型原文得到，⛔ 不是凑的。
        assertEquals(0.76327984989620745, MoonWater.hash1(0.0), 1e-15)
        assertEquals(0.51736302836798131, MoonWater.hash1(1.0), 1e-15)
        assertEquals(0.23459113878197968, MoonWater.hash1(-1.0), 1e-15)
        assertEquals(0.42130166315473616, MoonWater.hash1(76009.0), 1e-15)
        assertEquals(0.98202811321243644, MoonWater.hash1(-12345.0), 1e-15)
        assertEquals(0.45706768170930445, MoonWater.hash1(2147483647.0), 1e-15)
        assertEquals(0.98575507593341172, MoonWater.hash1(-2147483648.0), 1e-15)
        assertEquals(0.31022838759236038, MoonWater.hash1(13.7), 1e-15)
        // 原型的 `x | 0` 是**向零截断**（⛔ 不是 floor：floor(-0.3) = -1 会让整场粼光换随机源）
        assertEquals(MoonWater.hash1(0.0), MoonWater.hash1(0.5), 0.0)
        assertEquals(MoonWater.hash1(0.0), MoonWater.hash1(-0.3), 0.0)
        assertEquals(MoonWater.hash1(0.0), MoonWater.hash1(-0.999), 0.0)

        assertEquals(0.86816069480217983, MoonWater.noise1(-3.2), 1e-15)
        assertEquals(0.47951521885050924, MoonWater.noise1(7.13), 1e-15)
        assertEquals(0.75058132741833106, MoonWater.noise1(100.25), 1e-15)
        // 整数格点必须回到 hash1（smoothstep 端点 u=0）
        assertEquals(MoonWater.hash1(4.0), MoonWater.noise1(4.0), 0.0)

        assertEquals(0.53248294744121705, MoonWater.fbm1(0.0, 5.0), 1e-15)
        assertEquals(0.70622611110404654, MoonWater.fbm1(1.3, 5.0), 1e-15)
        assertEquals(0.64705143356802297, MoonWater.fbm1(12.4, 2.0), 1e-15)
        // fbm 归一化后必须落在 0..1（原型 `:375-379` 除的是 0.5+0.25+0.125 = 0.875）
        var f = 0.0
        while (f < 400.0) {
            val v = MoonWater.fbm1(f, 5.0)
            assertTrue("fbm1($f) = $v 越出 0..1", v >= 0.0 && v <= 1.0)
            f += 0.37
        }
    }

    @Test
    fun `①b 负向自证 把 xor 写成 or 则站位塌成几十种`() {
        // 原型是 `^`（异或）。JS 里 `|` 优先级高于 `^`，手抄时极易掉进 `(x|0)|CONST` 的坑。
        val good = HashSet<Double>(512)
        val bad = HashSet<Double>(512)
        for (i in 0 until MoonWater.ROWS_HIGH) {
            for (k in 0 until MoonWater.PER_HIGH) {
                val seed = (i * MoonWater.SEED_I + k * MoonWater.SEED_K + MoonWater.SEED_BASE).toDouble()
                good.add(MoonWater.hash1(seed * MoonWater.WIDE_SCALE + MoonWater.WIDE_OFF))
                bad.add(hash1Or(seed * MoonWater.WIDE_SCALE + MoonWater.WIDE_OFF))
            }
        }
        assertEquals("生产哈希必须 300 个种子给 300 个不同值", 300, good.size)
        assertTrue(
            "若把 xor 写成 or，300 个种子只会剩 ${bad.size} 个值（实测 57）⇒ 同一条划挤在同一个" +
                "`wide` 上，粼光会塌成规则条纹。本例证明 ① 的逐位对账**不是空转**",
            bad.size < 100,
        )
    }

    // ── ② 结构条数 == G4 的 ops 三列 ───────────────────────────────────────────

    @Test
    fun `② 粼光结构条数 == MoonOpItem GLITTER 的 ops`() {
        assertEquals(43, MoonWater.structuralStreaks(MoonLevel.LOW))
        assertEquals(92, MoonWater.structuralStreaks(MoonLevel.MEDIUM))
        assertEquals(137, MoonWater.structuralStreaks(MoonLevel.HIGH))
        assertEquals(MoonWater.structuralStreaks(MoonLevel.LOW), MoonOpItem.GLITTER.opsLow)
        assertEquals(MoonWater.structuralStreaks(MoonLevel.MEDIUM), MoonOpItem.GLITTER.opsMed)
        assertEquals(MoonWater.structuralStreaks(MoonLevel.HIGH), MoonOpItem.GLITTER.opsHigh)
        // 三段拆账（单位是**条**）：LOW = 远 11 行×1 + 中 7 行×2 + 近 6 行×3 = 11+14+18
        assertEquals(listOf(11, 14, 18), perBreakdown(MoonLevel.LOW))
        assertEquals(listOf(22, 26, 44), perBreakdown(MoonLevel.MEDIUM))
        assertEquals(listOf(28, 34, 75), perBreakdown(MoonLevel.HIGH))
    }

    @Test
    fun `②b 负向自证 每行均摊 PER 条则 LOW 的 ops 顶穿门`() {
        // 分档（远 1 / 中 2 / 近 PER）买的正是这个：均摊会把 LOW 从 43 抬到 24×3 = 72。
        assertEquals(72, uniformStreaks(MoonLevel.LOW))
        assertEquals(184, uniformStreaks(MoonLevel.MEDIUM))
        assertEquals(300, uniformStreaks(MoonLevel.HIGH))
        val broken = MoonOpBudget.estimate(MoonLevel.LOW) - MoonOpItem.GLITTER.opsLow + 72
        assertTrue(
            "均摊写法下 LOW 合计 $broken 必须 > ${MoonOpBudget.OPS_MAX_LOW}（⇒ 分档不是装饰，" +
                "它不然 LOW 档就得砍行数）",
            broken > MoonOpBudget.OPS_MAX_LOW,
        )
    }

    // ── ③④ 粼光填充：生产几何 vs 表 ───────────────────────────────────────────

    @Test
    fun `③ 粼光填充 == 生产几何最坏帧 且在场条数 == 结构条数`() {
        for (level in MoonLevel.entries) {
            val s = scanGlitter(level, W_REF, H_REF, DPR_TV)
            val pinned = when (level) {
                MoonLevel.LOW -> Triple(0.038962, 96.5, 43)
                MoonLevel.MEDIUM -> Triple(0.087361, 552.5, 92)
                MoonLevel.HIGH -> Triple(0.136744, 312.5, 137)
            }
            assertEquals(
                "$level 最坏帧填充必须复现原型扫描值（表 ${fillTable(level)}）",
                pinned.first, s.worst, 2e-6,
            )
            assertEquals(
                "$level 的 argmax 换了帧 ⇒ 粼光分布变了，⛔ 不许只把表改大当对账通过",
                pinned.second, s.worstT, 0.0,
            )
            assertEquals(
                "$level 最坏帧必须**一条都不剔**（在场 == 结构 ${pinned.third}），" +
                    "否则 ops 列与填充列不是同一帧的口径",
                pinned.third.toDouble(), s.survivors.toDouble(), 0.0,
            )
            assertEquals(MoonWater.structuralStreaks(level), s.survivors)
            // 均值口径仍在下面（D28 的旧表就是这么写错的），这里只钉"均值 < 最坏帧"
            assertTrue("$level 均值 ${s.mean} 必须 < 最坏帧 ${s.worst}", s.mean < s.worst)
        }
        // 表口径的最坏帧 glitA 必须是 `f=1 / occl=0 / aTreb=1` ⇒ 0.775
        assertEquals(0.775, MoonClouds.glitA(1.0, 0.0, 1.0), 1e-12)
    }

    @Test
    fun `③b 表向上包住最坏帧 且不多记一屏的千分之一`() {
        for (level in MoonLevel.entries) {
            val worst = scanGlitter(level, W_REF, H_REF, DPR_TV).worst
            val table = fillTable(level)
            assertTrue(
                "$level 表 $table 必须 ≥ 生产几何最坏帧 $worst（G4 的前提是「表是上界」）",
                table >= worst,
            )
            assertTrue(
                "$level 表高出实测 ${table - worst} ⇒ 向上取整过了头，记账会系统性偏保守",
                table - worst <= 0.001,
            )
        }
    }

    @Test
    fun `④ 粼光填充与画幅尺度无关`() {
        val sizes = arrayOf(
            intArrayOf(1600, 900), intArrayOf(1920, 1080),
            intArrayOf(2560, 1440), intArrayOf(3840, 2160),
        )
        for (level in MoonLevel.entries) {
            val t = worstT(level)
            val base = fillAt(level, W_REF, H_REF, DPR_TV, t)
            for (sz in sizes) {
                assertEquals(
                    "$level 在 ${sz[0]}×${sz[1]} 的填充必须与 1080p 逐位同（密度取验收机 ${DPR_TV}）",
                    base, fillAt(level, sz[0], sz[1], DPR_TV, t), 2e-6,
                )
            }
        }
    }

    @Test
    fun `④b 密度地板矩阵与档位余量（D29）`() {
        // 1080p：density 1 与 1.5 逐位相同（地板 1.2 / 1.8 < 自然最薄 2.25），2 与 3 才抬。
        for (level in MoonLevel.entries) {
            val pinned = when (level) {
                MoonLevel.LOW -> doubleArrayOf(0.038962, 0.038981, 0.039533)
                MoonLevel.MEDIUM -> doubleArrayOf(0.087361, 0.087397, 0.088381)
                MoonLevel.HIGH -> doubleArrayOf(0.136744, 0.136795, 0.138179)
            }
            val dprs = doubleArrayOf(1.0, 2.0, 3.0)
            for (i in dprs.indices) {
                assertEquals(
                    "$level @1080p density ${dprs[i]} 必须复现扫描值",
                    pinned[i], scanGlitter(level, W_REF, H_REF, dprs[i]).worst, 2e-6,
                )
            }
        }
        // 900p 是最坏组合（minDim 小 ⇒ 地板相对更粗），三档全部重扫。
        assertEquals(0.040063, scanGlitter(MoonLevel.LOW, 1600, 900, 3.0).worst, 2e-6)
        assertEquals(0.089332, scanGlitter(MoonLevel.MEDIUM, 1600, 900, 3.0).worst, 2e-6)
        assertEquals(0.139536, scanGlitter(MoonLevel.HIGH, 1600, 900, 3.0).worst, 2e-6)
        assertEquals(0.039142, scanGlitter(MoonLevel.LOW, 1600, 900, 2.0).worst, 2e-6)
        assertEquals(0.087689, scanGlitter(MoonLevel.MEDIUM, 1600, 900, 2.0).worst, 2e-6)
        assertEquals(0.137203, scanGlitter(MoonLevel.HIGH, 1600, 900, 2.0).worst, 2e-6)
        // 2160p 的地板（3.6）低于自然最薄（4.49）⇒ 与基准逐位相同
        assertEquals(0.136744, fillAt(MoonLevel.HIGH, 3840, 2160, 3.0, 312.5), 2e-6)
        assertTrue(
            "地板必须**真的**触发，否则 D29 那句「表守 density ≤ 1.5 参照系」是空判据",
            scanGlitter(MoonLevel.HIGH, 1600, 900, 3.0).worst > fillTable(MoonLevel.HIGH),
        )

        // 高密度那一截不改进表，但必须留在档位余量内（门守的是**合计**）。
        for (level in MoonLevel.entries) {
            val gridWorst = when (level) {
                MoonLevel.LOW -> 0.040063
                MoonLevel.MEDIUM -> 0.089332
                MoonLevel.HIGH -> 0.139536
            }
            val total = MoonOpBudget.overdrawEstimate(W_REF.toFloat(), H_REF.toFloat(), level)
            val swapped = total - fillTable(level).toFloat() + gridWorst.toFloat()
            val cap = if (level == MoonLevel.HIGH) MoonOpBudget.fillRatchetMax()
            else MoonOpBudget.overdrawMax(level)
            assertTrue(
                "$level 按网格最坏 density 重算合计 = $swapped 必须 ≤ 门 $cap" +
                    "（超出 ⇒ 要么改表要么降 `HH_FLOOR_PX`，⛔ 不许放松门）",
                swapped <= cap,
            )
        }
    }

    // ── ⑤ 光柱：提交数与填充 == REFLECTION 行 ──────────────────────────────────

    @Test
    fun `⑤ 光柱提交与填充 == MoonOpItem REFLECTION 行`() {
        val headArea = 2.0 * PI * MoonWater.headRx(MOON_R_REF) * MoonWater.headRy(SEA_H_REF)
        for (level in MoonLevel.entries) {
            val n = 1 + MoonWater.fogCount(level)
            val ops = when (level) {
                MoonLevel.LOW -> MoonOpItem.REFLECTION.opsLow
                MoonLevel.MEDIUM -> MoonOpItem.REFLECTION.opsMed
                MoonLevel.HIGH -> MoonOpItem.REFLECTION.opsHigh
            }
            assertEquals("$level 提交数 = 1 柱头 + NFOG", ops, n)
            // 底光带按档累加（LOW 只吃第 0 颗，HIGH 吃 0/1/2）
            var fill = headArea
            for (i in 0 until MoonWater.fogCount(level)) {
                fill += 2.0 * PI * MoonWater.fogRx(MOON_R_REF, i) * MoonWater.fogRy(SEA_H_REF, i)
            }
            val screens = fill / (W_REF.toDouble() * H_REF.toDouble())
            val table = when (level) {
                MoonLevel.LOW -> MoonOpItem.REFLECTION.fillLow
                MoonLevel.MEDIUM -> MoonOpItem.REFLECTION.fillMed
                MoonLevel.HIGH -> MoonOpItem.REFLECTION.fillHigh
            }
            val pinned = when (level) {
                MoonLevel.LOW -> 0.073383
                MoonLevel.MEDIUM -> 0.203162
                MoonLevel.HIGH -> 0.378740
            }
            assertEquals("$level 光柱填充必须复现逐颗解析值", pinned, screens, 1e-5)
            assertTrue("$level 表 $table 必须 ≥ 解析 $screens（表是上界）", table >= screens)
            assertTrue("$level 表高出解析 ${table - screens} ⇒ 向上取整过头", table - screens <= 0.001)
        }
    }

    @Test
    fun `⑤b 光柱存在门 新月满遮整段不出场`() {
        assertTrue(MoonWater.gladeVisible(MoonClouds.reflA(1.0, 0.0), 1.0))
        // 门**只**在"新月 + 满遮"这一角闭合成 invisible：
        // reflA = 0.44 · clamp(1.3f,0.06,1)=0.06 · diskA(1)=0.08 = 0.002112 < MIN_ALPHA
        assertEquals(0.002112, MoonClouds.reflA(0.0, 1.0), 1e-12)
        assertFalse(MoonWater.gladeVisible(MoonClouds.reflA(0.0, 1.0), 12.0))
        // ⚠️ 满月满遮**仍然在场**（0.0352）：变暗靠的是与盘**同一个** `occl`（§6.3），
        //    ⛔ 不是靠存在门把整根柱子掐掉 —— 否则云缝里的一点月会让光柱忽闪。
        assertEquals(0.0352, MoonClouds.reflA(1.0, 1.0), 1e-12)
        assertTrue(MoonWater.gladeVisible(MoonClouds.reflA(1.0, 1.0), 12.0))
        // 恰好等于门 ⇒ 不算在场（`>` 而非 `≥`，与逐元隐形剔除同口径）
        assertFalse(MoonWater.gladeVisible(MoonClouds.MIN_ALPHA, 12.0))
        assertTrue(MoonWater.gladeVisible(MoonClouds.MIN_ALPHA + 1e-9, 12.0))
        // tSec = 0 时三颗雾片同相位 ⇒ 即使很亮也不许画（原型点名的"一摞透镜"）
        assertFalse(MoonWater.gladeVisible(0.44, 0.0))
        // 柱头 α：线性增益 2.10，到 0.95 封顶（钳位点 reflA = 0.95/2.10 ≈ 0.4524）
        assertEquals(0.84, MoonWater.headAlpha(0.4, 1.0), 1e-12)
        assertEquals(0.95, MoonWater.headAlpha(0.5, 1.0), 1e-12)
        assertEquals(0.95, MoonWater.headAlpha(1.0, 1.0), 1e-12)
        assertEquals(1, MoonWater.fogCount(MoonLevel.LOW))
        assertEquals(3, MoonWater.fogCount(MoonLevel.HIGH))
        // FOG 表的顺序是 `cy, rxK, aK`：越深越宽、越暗（抄反就是把最亮那颗扔到海面下缘）
        assertTrue(MoonWater.fogRxK(1) > MoonWater.fogRxK(0))
        assertTrue(MoonWater.fogCy(0.0, 1000.0, 1) > MoonWater.fogCy(0.0, 1000.0, 0))
        assertEquals(MoonWater.FOG_RY_FIRST / MoonWater.FOG_RY_REST, MoonWater.fogRyK(0) / MoonWater.fogRyK(1), 1e-12)
    }

    // ── ⑥ 地平带：几何与分桶 ───────────────────────────────────────────────────

    @Test
    fun `⑥ 地平带的几何系数与表一致 只有月色标随 occl 变`() {
        // ⚠️ 这两个数是**顶层 const**（枚举构造参数不能前向引用 companion，见 MoonOpBudget.kt:31），
        //    与 [MoonWater] 的那份是**同一把尺的两个名字** ⇒ 必须留漂移哨兵（同 MOON_HORIZON_FILL 的先例）
        assertEquals(MoonWater.BAND_ABOVE_K, MOON_BAND_ABOVE_K, 1e-12)
        assertEquals(MoonWater.BAND_BELOW_K, MOON_BAND_BELOW_K, 1e-12)
        assertEquals(MoonOpItem.HORIZON_BAND.fillLow, MoonOpBudget.horizonBandFillGeometry(), 1e-12)
        assertEquals(0.160, MoonOpItem.HORIZON_BAND.fillHigh, 1e-12)
        // 带高恒 0.16h、通铺全宽 ⇒ 表三档同值（与画质档无关）
        assertEquals(MoonOpItem.HORIZON_BAND.fillLow, MoonOpItem.HORIZON_BAND.fillMed, 1e-12)
        assertEquals(MoonOpItem.HORIZON_BAND.fillLow, MoonOpItem.HORIZON_BAND.fillHigh, 1e-12)
        // 压暗标吃 altT（固定构图 ⇒ 恒量），月色标吃 occl
        assertEquals(0.36875, MoonWater.bandDarkA(MoonSeascape.ALT_T.toDouble()), 1e-12)
        assertEquals(0.05, MoonWater.bandLitA(0.0), 1e-12)
        assertEquals(0.0, MoonWater.bandLitA(1.0), 1e-12)
        // 月色标 = hotOf(0.8)（⛔ 不是 whiteOf —— §7.6 原文给的就是盘色，带子是"染"不是"亮"）
        assertEquals(MoonDiskBake.hotOf(MoonWater.BAND_LIT_K), MoonWater.bandLitRgb())
        // 接线：两个色标与桶 α 全部来自 MoonWater，⛔ 渲染器不重抄
        val brush = functionBody("bandBrushOf", rendererSource())
        assertTrue("压暗标色必须取 bandDarkRgb()", brush.contains("MoonWater.bandDarkRgb()"))
        assertTrue("月色标色必须取 bandLitRgb()", brush.contains("MoonWater.bandLitRgb()"))
        assertTrue("月色标 α 必须取**桶中心** bandLitABucket（⛔ 用连续 bandLitA = 每帧重建渐变）",
            brush.contains("MoonWater.bandLitABucket(bucket)"))
        assertTrue("压暗标 α 必须走尺上的 ALT_T（⛔ 写死 0.369）",
            brush.contains("MoonWater.bandDarkA(MoonSeascape.ALT_T"))
        val band = functionBody("drawHorizonBand", rendererSource())
        assertTrue("桶号只能由 bandOcclBucket(occl) 量化", band.contains("MoonWater.bandOcclBucket(occl)"))
        assertFalse("⛔ 绘制侧不得直接吃连续 bandLitA（那等于绕过分桶缓存）",
            band.contains("bandLitA("))
    }

    @Test
    fun `⑥b 分桶量化误差有界 负向自证 刻度砍半就越线`() {
        // 最坏点落在**桶边界**上（离中心半个桶）⇒ 误差是解析式 `BAND_LIT_A / (2·桶数)`，
        // 不是扫出来的近似值：16 桶 = 0.0015625、8 桶 = 0.003125。
        assertEquals(0.0015625, maxBucketError(MoonWater.BAND_OCCL_BUCKETS), 1e-15)
        assertEquals(
            "最大 α 误差必须就是解析上界",
            MoonWater.BAND_LIT_A / (2.0 * MoonWater.BAND_OCCL_BUCKETS),
            maxBucketError(MoonWater.BAND_OCCL_BUCKETS),
            1e-15,
        )
        assertTrue(
            "16 桶的最大 α 误差必须 ≤ 0.002（判据与 D23 同套路：钉实测上界，⛔ 不钉随手数）",
            maxBucketError(MoonWater.BAND_OCCL_BUCKETS) <= 0.002,
        )
        val half = maxBucketError(MoonWater.BAND_OCCL_BUCKETS / 2)
        assertEquals(0.003125, half, 1e-15)
        assertTrue(
            "刻度砍到 8 桶误差升到 $half 必须 > 0.002 ⇒ 证明 16 不是随手挑的",
            half > 0.002,
        )
        // 助手与生产公式必须是同一把尺（桶数相同时逐桶相等，⛔ 助手漂出自己的第二份公式）
        for (b in 0 until MoonWater.BAND_OCCL_BUCKETS) {
            assertEquals(
                "桶 $b 的还原 α 必须与 MoonWater.bandLitABucket 逐位同",
                MoonWater.bandLitABucket(b), bucketCenterA(b, MoonWater.BAND_OCCL_BUCKETS), 0.0,
            )
        }
        // 桶号单调，且桶 0 = 无遮、末桶 = 满遮
        assertEquals(0, MoonWater.bandOcclBucket(0.0))
        assertEquals(MoonWater.BAND_OCCL_BUCKETS - 1, MoonWater.bandOcclBucket(1.0))
        assertEquals(MoonWater.BAND_OCCL_BUCKETS - 1, MoonWater.bandOcclBucket(1.7))
        var prev = Double.NaN
        for (b in 0 until MoonWater.BAND_OCCL_BUCKETS) {
            val a = MoonWater.bandLitABucket(b)
            if (!prev.isNaN()) assertTrue("桶 α 必须单调不升", a <= prev + 1e-12)
            prev = a
        }
        // 缓存槽数量必须与桶数**同源**（⛔ 渲染器里重抄字面量 16 = 改桶数时静默越界）
        assertTrue(
            "bandBrushes 的槽数必须引用 MoonWater.BAND_OCCL_BUCKETS",
            rendererSource().contains("arrayOfNulls<Brush>(MoonWater.BAND_OCCL_BUCKETS)"),
        )
    }

    // ── ⑦ 水色尺：一律 whiteOf ─────────────────────────────────────────────────

    @Test
    fun `⑦ 水色走 whiteOf 不走盘色`() {
        // §7.2/§7.3 的取色走原型 `:932` 的 `whiteOf`（月色三元组**逐通道往 255 插值**）。
        // 划档 t=0.35 的解析值 = rgb(255, 222, 166)，与参考图 PIL 复量的最亮水波
        // rgb(248, 220, 150) 同一档 ⇒ 这条对账钉的是**通道值**，不是"看起来白"。
        val glit = MoonDiskBake.whiteOf(MoonWater.GLIT_TONE_T.toFloat())
        assertEquals("划色 R（月色 R 本就贴着 255，t=0 即顶满）", 255, (glit shr 16) and 0xFF)
        assertEquals("划色 G", 222, (glit shr 8) and 0xFF)
        assertEquals("划色 B", 166, glit and 0xFF)
        assertEquals("t=1 必须是纯白", 0xFFFFFF, MoonDiskBake.whiteOf(1f) and 0xFFFFFF)

        // ⛔ 盘色那把尺够不到波峰：`hotOf` 是**等比乘**，R 顶到 255 时 G 只到 204；
        //    再往上乘只会把三通道一起推向截断（往纯白脱色），加 α 则把暗海水一同染亮 ⇒ 发灰。
        val diskFull = MoonDiskBake.hotOf(1f)
        assertEquals("盘色满值 G（= whiteOf(0) 的 G，同一色的两个名字）", 204, (diskFull shr 8) and 0xFF)
        assertTrue("波峰 G 必须高于盘色满值的 G ⇒ 水面**必须**换尺", 222 > 204)
        // 负向自证：若把划色写成 `hotOf(0.35)`，最白通道只剩 89（比实测波峰暗近 3 倍）
        assertEquals(89, (MoonDiskBake.hotOf(MoonWater.GLIT_TONE_T.toFloat()) shr 16) and 0xFF)

        // 三档取样序：划最白 > 柱头 > 底光带（雾气不该比高光白）
        assertTrue(MoonWater.GLIT_TONE_T > MoonWater.HEAD_TONE_T)
        assertTrue(MoonWater.HEAD_TONE_T > MoonWater.FOG_TONE_T)
        // 单调性看 **G**：R 恒 255，插值全发生在 G/B 上
        var prev = -1
        for (t in intArrayOf(0, 10, 22, 35, 60, 100)) {
            val v = (MoonDiskBake.whiteOf(t / 100f) shr 8) and 0xFF
            assertTrue("whiteOf 的 G 必须随 t 单调变白（$t ⇒ $v < $prev）", v >= prev)
            prev = v
        }
        assertEquals(204, (MoonDiskBake.whiteOf(0f) shr 8) and 0xFF)
        // 越界夹住（渲染层直接喂 const，⛔ 不夹）
        assertEquals(MoonDiskBake.whiteOf(0f), MoonDiskBake.whiteOf(-1f))
        assertEquals(MoonDiskBake.whiteOf(1f), MoonDiskBake.whiteOf(255f))

        // 三张芯的参数：芯越靠外越软（雾气 FOG 最软、划最硬）
        assertTrue(MoonWater.GLIT_CORE_A > MoonWater.FOG_CORE_A)
        assertTrue(MoonWater.FOG_CORE_K < MoonWater.GLIT_CORE_K)

        // 接线：水面三张 shader 的色停一律 whiteOf(·_TONE_T)，⛔ 不出现 hotOf（那是盘色尺）
        val src = rendererSource()
        for ((name, tone) in listOf(
            "headShaderOf" to "MoonWater.HEAD_TONE_T",
            "fogShaderOf" to "MoonWater.FOG_TONE_T",
            "glitterShaderOf" to "MoonWater.GLIT_TONE_T",
        )) {
            val b = functionBody(name, src)
            assertTrue("$name 必须取 whiteOf($tone)", b.contains("MoonDiskBake.whiteOf($tone.toFloat())"))
            assertFalse("$name ⛔ 不得用盘色 hotOf", b.contains("hotOf"))
        }
        // 亮度只走 paint α：色停里不许出现 .copy(alpha=…)
        for (name in listOf("headShaderOf", "fogShaderOf", "glitterShaderOf")) {
            assertFalse("$name 不得把亮度烤进色停", functionBody(name, src).contains("copy(alpha"))
        }
    }

    // ── ⑧⑨ 接线与不变式 ───────────────────────────────────────────────────────

    @Test
    fun `⑧ 层 7 顺序 带 柱 划 且排在云之后`() {
        val body = functionBody("drawContent", rendererSource())
        val order = listOf(
            "drawCloudNear(",
            "drawHorizonBand(",
            "drawGlade(",
            "drawGlitter(",
        )
        var prev = -1
        for (token in order) {
            val at = body.indexOf(token)
            assertTrue("层序缺 `$token` 或位置不对（划必须**骑在柱上**，反过来压不出高光）", at > prev)
            prev = at
        }
    }

    @Test
    fun `⑧b 水面不重算遮挡 与盘同帧变暗靠同一个 occl`() {
        val body = functionBody("drawContent", rendererSource())
        // 遮挡只算一次
        assertEquals("occl 只能由 `clouds.occlusion(` 算一次（§6.3 的⛔「水面不许另算一次」）",
            1, Regex("""\.occlusion\(""").findAll(body).count())
        // 两个消费点吃的都是那个 `occl` 变量，⛔ 不许传字面量或再算
        assertTrue("reflA 必须吃 occl", Regex("""reflA\(illum,\s*occl\)""").containsMatchIn(body))
        assertTrue("glitA 必须吃同一个 occl（⭐ T9 起 `glitA` 在**音频头之后**算一次，粼光与涟漪共用）",
            Regex("""glitA\(\s*illum,\s*occl,\s*aTreb\s*\)""").containsMatchIn(body))
        // 满遮时水面必须真的到下限（同源不只是"共用一个数"，方向也得对）
        val diskA = MoonClouds.diskA(1.0)
        assertEquals(MoonClouds.reflA(1.0, 0.0) * diskA, MoonClouds.reflA(1.0, 1.0), 1e-12)
        val d2 = diskA * diskA
        assertEquals(MoonClouds.glitA(1.0, 0.0, MoonAudio.NEUTRAL) * d2,
            MoonClouds.glitA(1.0, 1.0, MoonAudio.NEUTRAL), 1e-12)
    }

    @Test
    fun `⑧c 水面时基只吃 dt 且不逐帧分配`() {
        val src = rendererSource()
        val body = functionBody("drawContent", src)
        assertEquals("水钟只能 += fx.dt 一次", 1, Regex("""waterClockSec\s*\+=\s*fx\.dt""").findAll(body).count())
        assertFalse("水钟不得读墙钟（G5）", body.contains("currentTimeMillis"))
        assertFalse("水钟不得由 fx.nowMs 相减（切后台会跳变）", body.contains("waterClockSec = fx"))
        val glade = functionBody("drawGlade", src)
        val glitterBody = functionBody("drawGlitter", src)
        for (name in listOf("drawHorizonBand", "drawGlade", "drawGlitter")) {
            val b = functionBody(name, src)
            for (bad in listOf("listOf(", "mutableListOf(", "arrayListOf(", "mapOf(", "Brush.verticalGradient", "RadialGradient(")) {
                assertFalse("⛔ `$name` 体内不得出现 $bad（逐帧分配红线）", b.contains(bad))
            }
        }
        assertFalse("⛔ 水面不得画镜像月盘（§7.2 第六轮：那读作'水面浮一块暗石板'）",
            glade.contains("drawImage") || glade.contains("drawMoonDisk"))
        assertFalse("划也不许 blit 盘", glitterBody.contains("drawImage"))
        assertEquals("每帧只 begin 一次粼光", 1, Regex("""glitter\.begin\(""").findAll(glitterBody).count())
        assertFalse("begin 不许进循环（行/条循环里只能读缓存）",
            Regex("""while[\s\S]{0,400}glitter\.begin\(""").containsMatchIn(glitterBody))
        // 三张着色器**只在** *ShaderOf 里建（且委托给唯一的 softShader 入口），⛔ 不在绘制体内
        for (name in listOf("headShaderOf", "fogShaderOf", "glitterShaderOf")) {
            val b = functionBody(name, src)
            assertTrue("$name 必须经 softShader 建张", b.contains("softShader("))
            assertTrue("$name 必须把建好的张存进缓存槽（⛔ 逐帧重建 native 对象）",
                b.contains("Shader = built"))
        }
        assertFalse("⛔ 水面绘制体内不得直接构造 RadialGradient",
            glade.contains("RadialGradient(") || glitterBody.contains("RadialGradient("))
        // ⭐ T9 之后仍然成立、但**理由换了**：`aBass` 由 [drawContent] 的音频头**传参**进来，
        //    绘制体内 ⛔ 不许直接读 `frame.*`（那等于绕过包络吃瞬态，光柱随鼓点晃）。
        assertFalse("粼光体内不得读 frame.*（摆移只能由音频头传参）", glitterBody.contains("frame."))
    }

    @Test
    fun `⑧d MoonWater 零 Android 依赖（G4 能纯 JVM 重算的前提）`() {
        val raw = File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/MoonWater.kt").readText()
        assertFalse("⛔ import android", Regex("""^\s*import\s+android""").containsMatchIn(raw))
        assertFalse("⛔ import androidx", Regex("""^\s*import\s+androidx""").containsMatchIn(raw))
        assertFalse("⛔ 零 Random（对账要求同种子同结果）", raw.contains("kotlin.random.Random"))
        assertFalse("⛔ 零 java.util.Random", raw.contains("java.util.Random"))
        // 水面数学不许回流渲染器：渲染器里不得再出现 len/hh 的公式常数
        val src = rendererSource()
        assertFalse("渲染器不得自己算划厚（0.018 只能住在 MoonWater）", src.contains("0.018"))
        assertFalse("渲染器不得自己算透视（1.70 只能住在 MoonWater）", src.contains("1.70"))
        assertFalse("渲染器不得自己数行（60 行常量必须来自 MoonWater.ROWS_HIGH）",
            Regex("""ROWS\s*=\s*60""").containsMatchIn(src))
    }

    @Test
    fun `⑨ hh 与行距之比的实测三档（LOW 下缘低于文档口径）`() {
        // 原型立的不变式：比值别远大于 1（远大于 = 相邻行互相吞掉糊成一坨）。
        // ⚠️ as-built 实测（1080p / density 1.5）：远端反而**最大**（行距被透视压到 1~2px），
        //    中景最疏，下缘 LOW 只有 0.692 而文档写 ≈1.2 ⇒ 登记偏差，⛔ 不改 ROWS/透视/底宽。
        val ratios = arrayOf(
            doubleArrayOf(1.227, 0.244, 0.692),   // LOW  / 远 中 下
            doubleArrayOf(3.705, 0.462, 1.364),   // MED
            doubleArrayOf(5.819, 0.601, 1.791),   // HIGH
        )
        val levels = arrayOf(MoonLevel.LOW, MoonLevel.MEDIUM, MoonLevel.HIGH)
        for (li in levels.indices) {
            val level = levels[li]
            val rows = MoonWater.rowsOf(level)
            val got = doubleArrayOf(
                MoonWater.hhToDyRatio(0, rows, SEA_H_REF, MIN_DIM_REF, DPR_TV),
                MoonWater.hhToDyRatio(rows / 2, rows, SEA_H_REF, MIN_DIM_REF, DPR_TV),
                MoonWater.hhToDyRatio(rows - 1, rows, SEA_H_REF, MIN_DIM_REF, DPR_TV),
            )
            for (i in got.indices) {
                assertEquals("$level 的第 $i 个比值", ratios[li][i], got[i], 1e-3)
            }
            // 密度不影响这三个点（1080p 的自然最薄 2.25 已高于地板 1.8）
            val den = doubleArrayOf(1.0, 1.5, 2.0)
            for (d in den) {
                assertEquals(
                    "$level @density $d 必须与基准同值（地板不触发）",
                    got[2], MoonWater.hhToDyRatio(rows - 1, rows, SEA_H_REF, MIN_DIM_REF, d), 1e-9,
                )
            }
        }
        // 中景永远最疏 ⇒ 行与行之间**必须**留暗槽（`JIT_ROW_K` 的错开是这一条的另一半）
        assertTrue(MoonWater.hhToDyRatio(MoonWater.rowsOf(MoonLevel.HIGH) / 2, MoonWater.rowsOf(MoonLevel.HIGH), SEA_H_REF, MIN_DIM_REF, DPR_TV) < 1.0)
    }

    // ── 夹具与助手 ─────────────────────────────────────────────────────────────

    /** 验收机画幅（`docs/AGENTS.md` 记的创维真机：1920×1080 / density 240 ⇒ 1.5）。 */
    private fun fillTable(level: MoonLevel): Double = when (level) {
        MoonLevel.LOW -> MoonOpItem.GLITTER.fillLow
        MoonLevel.MEDIUM -> MoonOpItem.GLITTER.fillMed
        MoonLevel.HIGH -> MoonOpItem.GLITTER.fillHigh
    }

    /** 三档拆账 = 远 / 中 / 近**三段的条数**（不是行数）：`rows×per` 逐段求和。 */
    private fun perBreakdown(level: MoonLevel): List<Int> {
        val rows = MoonWater.rowsOf(level)
        val perNear = MoonWater.perOfLevel(level)
        var far = 0
        var mid = 0
        var near = 0
        for (i in 0 until rows) {
            when (MoonWater.perOf(MoonWater.fyOf(i, rows), perNear)) {
                1 -> far++
                2 -> mid++
                else -> near++
            }
        }
        return listOf(far, mid * 2, near * perNear)
    }

    private fun uniformStreaks(level: MoonLevel): Int =
        MoonWater.rowsOf(level) * MoonWater.perOfLevel(level)

    /** 一帧的（包围盒占屏比, 在场条数）。 */
    private fun fillAndCount(w: Int, h: Int): Pair<Double, Int> {
        var sum = 0.0
        var n = 0
        for (i in 0 until frame.rows) {
            val r = frame.rowAt(i)
            for (k in 0 until r.per) {
                val s = frame.streakAt(i, k)
                if (s.alpha < MoonClouds.MIN_ALPHA) continue
                sum += s.len * s.hh
                n++
            }
        }
        return (sum / (w.toDouble() * h)) to n
    }

    private fun begin(level: MoonLevel, w: Int, h: Int, dpr: Double, tSec: Double) {
        val horizonY = h * MoonlitRenderer.HORIZON_K.toDouble()
        frame.begin(
            level, tSec, minOf(w, h).toDouble(), horizonY, h - horizonY,
            w * MoonlitRenderer.MOON_CX_K.toDouble(), WORST_GLIT_A,
            MoonDiskBake.LUM_K.toDouble(), dpr, MoonAudio.NEUTRAL,
        )
    }

    private fun fillAt(level: MoonLevel, w: Int, h: Int, dpr: Double, tSec: Double): Double {
        begin(level, w, h, dpr, tSec)
        return frame.fillFraction(w.toDouble(), h.toDouble())
    }

    /** `fillFraction` 与 [fillAndCount] 必须是同一把尺（两份实现互证）。 */
    private class Scan(val worst: Double, val worstT: Double, val survivors: Int, val mean: Double)

    private fun scanGlitter(level: MoonLevel, w: Int, h: Int, dpr: Double): Scan {
        // 纯函数 ⇒ 同一 (档, 画幅, 密度) 的 4001 帧扫描结果可跨例复用（③/③b/④b 共 21 次扫描，
        // 不去重要付 4000 万条划的代价）
        val key = "$level/$w/$h/$dpr"
        SCAN_CACHE[key]?.let { return it }
        var worst = -1.0
        var worstT = 0.0
        var surv = 0
        var acc = 0.0
        var f = 0
        while (f < FRAMES) {
            val t = f * T_STEP
            begin(level, w, h, dpr, t)
            val (a, n) = fillAndCount(w, h)
            // 两份实现互证：每 500 帧抽查一次（差异是系统性的，抽样足够，且省掉一倍的扫描量）
            if (f % 500 == 0) {
                assertEquals(
                    "fillFraction 与手算必须逐位同（$level @$t）",
                    a, frame.fillFraction(w.toDouble(), h.toDouble()), 0.0,
                )
            }
            acc += a
            if (a > worst) {
                worst = a
                worstT = t
                surv = n
            }
            f++
        }
        val scan = Scan(worst, worstT, surv, acc / FRAMES)
        SCAN_CACHE[key] = scan
        return scan
    }

    /** 各档的最坏帧时刻（由 ③ 的扫描钉死；④ 只在这些时刻复评其他画幅）。 */
    private fun worstT(level: MoonLevel): Double = when (level) {
        MoonLevel.LOW -> 96.5
        MoonLevel.MEDIUM -> 552.5
        MoonLevel.HIGH -> 312.5
    }

    /**
     * 分桶还原的最大 α 误差（给 ⑥b 的砍半负向用）。
     *
     * ⚠️ 取样点取**每个桶的下边界** `o = b/S`：那是离桶心最远的位置（半个桶），
     * 所以本函数返回的是**解析上界** `BAND_LIT_A / (2S)`，不是网格近似值 ——
     * 用等距网格扫会永远差一截，把"钉实测上界"变成"钉一个比真值小的数"。
     */
    private fun maxBucketError(buckets: Int): Double {
        var worst = 0.0
        for (b in 0 until buckets) {
            val approx = bucketCenterA(b, buckets)
            val e = kotlin.math.abs(MoonWater.bandLitA(b / buckets.toDouble()) - approx)
            if (e > worst) worst = e
        }
        return worst
    }

    /** 按桶**中心**还原的月色 α（与 [MoonWater.bandLitABucket] 同式，桶数参数化供砍半负向复用）。 */
    private fun bucketCenterA(bucket: Int, buckets: Int): Double =
        MoonWater.BAND_LIT_A * (1.0 - (bucket + 0.5) / buckets)

    /** ①b 的反例：把 `xor` 手抄成 `or`（原型 `x | 0 | CONST` 之类），哈希立刻塌值。 */
    private fun hash1Or(x: Double): Double {
        var h = (x.toInt() or 0x9E3779B9.toInt()) * 0x85EBCA77.toInt()
        h = h xor (h ushr 13)
        h *= 0xC2B2AE3D.toInt()
        h = h xor (h ushr 16)
        return (h.toLong() and 0xFFFFFFFFL) / 4294967296.0
    }

    private fun rendererSource(): String = stripComments(
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/MoonlitRenderer.kt").readText()
    )

    /** 取 `fun <name>(…)` 的大括号体（同 [MoonlitSkySeaTest] 的夹具，⛔ 不跨类共享以免互相牵制）。 */
    private fun functionBody(name: String, src: String): String {
        val head = Regex("""fun\s+(?:DrawScope\.)?$name\s*\(""").find(src) ?: error("找不到 $name")
        var i = head.range.last
        while (src[i] != '{') i++
        var depth = 0
        var j = i
        while (j < src.length) {
            when (src[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(i + 1, j)
                }
            }
            j++
        }
        error("$name 大括号不配对")
    }

    /** 剥块注释（Kotlin 可嵌套）与行注释，⛔ 必须保持行数一致（定位按原始行号）。 */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') { inString = true; sb.append(c); i++; continue }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                var blockDepth = 1
                while (i < src.length && blockDepth > 0) {
                    if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') { blockDepth++; i += 2 }
                    else if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') { blockDepth--; i += 2 }
                    else {
                        if (src[i] == '\n') sb.append('\n')
                        i++
                    }
                }
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            sb.append(c)
            i++
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

    companion object {
        /** 验收机基准画幅（G4 的三档实测全部来自这台）。 */
        private const val W_REF = 1920
        private const val H_REF = 1080
        private const val DPR_TV = 1.5
        private const val MIN_DIM_REF = 1080.0
        private val SEA_H_REF: Double = H_REF * (1.0 - MoonlitRenderer.HORIZON_K.toDouble())
        private val MOON_R_REF: Double = MIN_DIM_REF * MoonlitRenderer.MOON_R_K.toDouble()

        /** 表口径的最坏帧亮度（`f=1`、`occl=0`、`aTreb=1`）。 */
        private val WORST_GLIT_A: Double = MoonClouds.glitA(1.0, 0.0, 1.0)

        // 扫描网格：与原型探针同一条（0.25 s × 4001 帧 = 1000 s）
        private const val FRAMES = 4001
        private const val T_STEP = 0.25

        /** [scanGlitter] 的记忆化表（粼光无状态 ⇒ 纯输入决定输出，跨例复用安全）。 */
        private val SCAN_CACHE = HashMap<String, Scan>()
    }
}

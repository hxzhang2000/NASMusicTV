package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「世界」效果的纯逻辑测试（无 Robolectric、无 Android 依赖）。
 *
 * 覆盖四块：[WorldCities] 城市表与权重、[WorldProjection] Robinson 投影、
 * [WorldTerminator] 晨昏线几何、[WorldNetwork] + [BeatClassifier] 航线网络。
 *
 * 本文件同时是这几块的**规格说明书**：里面锁死的常数（0.8487、10.2609、
 * 1.6× 的层内权重比、8.4 的层间权重比、0.85/1.6 的节拍分界、
 * [WorldNetwork.MAX_ROUTE_KM] = 15000 km ≈ 134.9°）都是渲染层可以
 * 直接照着调的量，任何一条断言被改动都必须同步更新对应 KDoc。
 */
class WorldLogicTest {

    // ══ ① 城市表 ═══════════════════════════════════════════════

    @Test
    fun `city table has 32 entries with tier counts 8 8 10 6`() {
        assertEquals(32, WorldCities.COUNT)
        assertEquals(WorldCities.COUNT, WorldCities.ALL.size)
        val byTier = IntArray(5)
        for (c in WorldCities.ALL) {
            assertTrue("层级必须在 1..4：${c.name}", c.tier in 1..4)
            byTier[c.tier]++
        }
        assertEquals("Tier1 应 8 座", 8, byTier[1])
        assertEquals("Tier2 应 8 座", 8, byTier[2])
        assertEquals("Tier3 应 10 座", 10, byTier[3])
        assertEquals("Tier4 应 6 座", 6, byTier[4])
    }

    @Test
    fun `all coordinates are in range and unique`() {
        val seen = HashSet<String>()
        WorldCities.ALL.forEach { c ->
            assertTrue("${c.name} 经度越界：${c.lon}", c.lon in -180f..180f)
            assertTrue("${c.name} 纬度越界：${c.lat}", c.lat in -90f..90f)
            assertTrue("${c.name} 吞吐量为正：${c.pax}", c.pax > 0f)
            assertTrue("城市重复：${c.name}", seen.add(c.name))
            assertTrue("拼音键不能为空：${c.name}", c.pinyin.isNotEmpty())
        }
    }

    @Test
    fun `accessors are bounds safe`() {
        assertEquals(4, WorldCities.tierOf(-1))
        assertEquals(4, WorldCities.tierOf(WorldCities.COUNT))
        assertEquals(0f, WorldCities.paxOf(-1), 0f)
        assertEquals(0f, WorldCities.weightOf(999), 0f)
        assertTrue("越界索引不应产生 NaN 距离", WorldCities.distanceKm(0, 999).isNaN())
        // 越界索引退化为自身（=「无可达枢纽」）
        assertEquals(0, WorldCities.nearestHub(0, 0))
    }

    // ── 权重 ───────────────────────────────────────────────────

    @Test
    fun `weight is strictly decreasing across tiers`() {
        val w = FloatArray(WorldCities.COUNT) { WorldCities.weightOf(it) }
        val tier = IntArray(WorldCities.COUNT) { WorldCities.tierOf(it) }
        for (i in 0 until WorldCities.COUNT) {
            for (j in 0 until WorldCities.COUNT) {
                if (tier[i] >= tier[j]) continue
                assertTrue(
                    "层级 ${tier[i]} 必须整体重于层级 ${tier[j]}：" +
                        "${WorldCities.labelOf(i)}=${w[i]} vs ${WorldCities.labelOf(j)}=${w[j]}",
                    w[i] > w[j]
                )
            }
        }
    }

    @Test
    fun `pax modulates weight by at most 1_6x inside a tier`() {
        for (t in 1..4) {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until WorldCities.COUNT) {
                if (WorldCities.tierOf(i) != t) continue
                val w = WorldCities.weightOf(i)
                if (w < lo) lo = w
                if (w > hi) hi = w
            }
            // 上界来自 paxFactor 的钳位 [0.75, 1.20]（比值 1.6）；
            // 下界 1.4 证明层内规模差确实可见（没被钳成常数）
            assertTrue("Tier$t 层内权重极值比 $hi/$lo 不得超过 1.6", hi / lo <= 1.6f + 0.001f)
            assertTrue("Tier$t 层内权重极值比 ${hi / lo} 太小，说明 pax 二次加权失效", hi / lo >= 1.4f)
        }
        // 钳位本身：Tier1 的最强市（纽约 142M）压到 1.20，Tier4 的最弱市（内罗毕 12M）压到 0.75
        assertEquals(8f * 1.20f, WorldCities.weightOf(indexOf("niuyue")), 0.001f)
        assertEquals(1f * 0.75f, WorldCities.weightOf(indexOf("neiluobi")), 0.001f)
    }

    @Test
    fun `tier 1 to 4 weight ratio is about 8`() {
        val t1 = (0 until WorldCities.COUNT).filter { WorldCities.tierOf(it) == 1 }
        val t4 = (0 until WorldCities.COUNT).filter { WorldCities.tierOf(it) == 4 }
        val mean1 = t1.map { WorldCities.weightOf(it) }.average()
        val mean4 = t4.map { WorldCities.weightOf(it) }.average()
        // 「Tier1 ≈ 8× Tier4」落在**均值**上：层内 pax 二次加权是 ±1.6× 的对称微调，
        // 极值比必然是 8×1.6 = 12.8（见 WorldCities 类 KDoc），不能同时精确成立。
        assertEquals("层间权重均值比应 ≈ 8", 8f, (mean1 / mean4).toFloat(), 0.6f)
        // 极值比：8 × 1.6 的上界
        val best1 = t1.maxOf { WorldCities.weightOf(it) }
        val worst4 = t4.minOf { WorldCities.weightOf(it) }
        assertEquals("极值比 = tierBase 比 × 层内极值比", 8f * 1.6f, best1 / worst4, 0.01f)
    }

    // ── 随机 ───────────────────────────────────────────────────

    @Test
    fun `pickWeighted never returns the excluded index`() {
        val rnd = WorldRng(7u)
        repeat(20000) { i ->
            val exclude = i % WorldCities.COUNT
            val picked = WorldCities.pickWeighted(rnd, exclude)
            assertTrue("不能抽中排除项 $exclude（抽到 $picked）", picked != exclude)
            assertTrue("抽中的下标越界：$picked", picked in 0 until WorldCities.COUNT)
        }
    }

    @Test
    fun `pickWeighted is deterministic for a fixed seed`() {
        val a = WorldRng(20260928u)
        val b = WorldRng(20260928u)
        for (i in 0 until 500) {
            assertEquals("同种子必须复现", WorldCities.pickWeighted(a, -1), WorldCities.pickWeighted(b, -1))
        }
        val c = WorldRng(20260929u)
        val d = WorldRng(20260928u)
        var diff = 0
        for (i in 0 until 500) if (WorldCities.pickWeighted(c, -1) != WorldCities.pickWeighted(d, -1)) diff++
        assertTrue("不同种子应产生不同序列，实测差异数 $diff", diff > 20)
    }

    @Test
    fun `tier 1 cities are picked far more often than tier 4`() {
        val rnd = WorldRng(1234567u)
        val hits = IntArray(5)
        val n = 40000
        repeat(n) {
            hits[WorldCities.tierOf(WorldCities.pickWeighted(rnd, -1))]++
        }
        assertEquals("计数必须守恒", n, hits.sum())
        // 权重质量：Tier1 ≈ 52.7% vs Tier4 ≈ 4.7% ⇒ 约 11 倍
        assertTrue("Tier1 命中数 ${hits[1]} 应远多于 Tier4 ${hits[4]}", hits[1] > hits[4] * 5)
        assertTrue("Tier1 应是绝对主力（${hits[1]}/$n）", hits[1] > n * 0.40)
        assertTrue("Tier4 应是长尾（${hits[4]}/$n）", hits[4] < n * 0.10)
    }

    // ── 地理 ───────────────────────────────────────────────────

    @Test
    fun `great circle distance is sane`() {
        for (i in 0 until WorldCities.COUNT) {
            assertEquals("自距必须精确为 0", 0f, WorldCities.distanceKm(i, i), 0f)
        }
        val london = indexOf("lundun")
        val newYork = indexOf("niuyue")
        // 伦敦 ↔ 纽约 实际大圆距离 ≈ 5570 km
        assertEquals(5570f, WorldCities.distanceKm(london, newYork), 200f)
        assertTrue("应在 5000..6000 km", WorldCities.distanceKm(london, newYork) in 5000f..6000f)
        // 对称
        assertEquals(WorldCities.distanceKm(london, newYork), WorldCities.distanceKm(newYork, london), 0f)
    }

    @Test
    fun `nearestHub picks the geographically closest qualifying city`() {
        for (i in 0 until WorldCities.COUNT) {
            for (maxTier in 1..4) {
                val hub = WorldCities.nearestHub(i, maxTier)
                assertNotEquals("不能返回自己（maxTier=$maxTier）", i, hub)
                assertTrue("${WorldCities.labelOf(hub)} 的层级应 ≤ $maxTier", WorldCities.tierOf(hub) <= maxTier)
                val d = WorldCities.distanceKm(i, hub)
                for (j in 0 until WorldCities.COUNT) {
                    if (j == i || WorldCities.tierOf(j) > maxTier) continue
                    assertTrue(
                        "${WorldCities.labelOf(hub)} 不是最近的 Tier≤$maxTier 枢纽" +
                            "（${WorldCities.labelOf(j)} 更近：${WorldCities.distanceKm(i, j)} < $d）",
                        d <= WorldCities.distanceKm(i, j)
                    )
                }
            }
        }
    }

    @Test
    fun `nearestHub for a tier 4 city is a tier 1 or 2 hub and never longer than the tier 3 relay`() {
        for (i in 0 until WorldCities.COUNT) {
            if (WorldCities.tierOf(i) != 4) continue
            val hub2 = WorldCities.nearestHub(i, 2)
            assertTrue("${WorldCities.labelOf(i)} 的 hub 必须是 Tier≤2", WorldCities.tierOf(hub2) <= 2)
            val relay3 = WorldCities.nearestHub(i, 3)
            assertTrue("Tier3 中继不应比 Tier2 枢纽更远", WorldCities.distanceKm(i, relay3) <= WorldCities.distanceKm(i, hub2))
        }
        // 具体抽查：内罗毕 → 迪拜（开罗/东欧都更远），布宜诺斯艾利斯 → 亚特兰大
        // （南美没有任何 Tier1/2 机场，这正是「南美稀疏」的物理原因）
        assertEquals("dibai", WorldCities.ALL[WorldCities.nearestHub(indexOf("neiluobi"), 2)].pinyin)
        assertEquals("yatelanda", WorldCities.ALL[WorldCities.nearestHub(indexOf("buiyinuosiailisi"), 2)].pinyin)
        // 放宽到 Tier3 后中继出现：布宜诺斯艾利斯 → 圣保罗、利马 → 圣保罗、奥克兰 → 悉尼
        assertEquals("shengbaoluo", WorldCities.ALL[WorldCities.nearestHub(indexOf("buiyinuosiailisi"), 3)].pinyin)
        assertEquals("shengbaoluo", WorldCities.ALL[WorldCities.nearestHub(indexOf("lima"), 3)].pinyin)
        assertEquals("xinni", WorldCities.ALL[WorldCities.nearestHub(indexOf("aokelan"), 3)].pinyin)
    }

    @Test
    fun `active flight range per tier`() {
        assertEquals(5..8, WorldCities.activeFlightRange(1))
        assertEquals(3..5, WorldCities.activeFlightRange(2))
        assertEquals(1..3, WorldCities.activeFlightRange(3))
        assertEquals(0..1, WorldCities.activeFlightRange(4))
        assertTrue("非法层级应给空区间", WorldCities.activeFlightRange(0).isEmpty())
    }

    // ══ ② Robinson 投影 ════════════════════════════════════════

    @Test
    fun `robinson x is linear and odd in longitude`() {
        assertEquals("赤道/本初子午线为原点", 0f, WorldProjection.robinsonX(0f, 0f), 0f)
        for (lat in intArrayOf(-80, -45, -10, 0, 10, 45, 80)) {
            for (lon in intArrayOf(-150, -90, -30, 30, 90, 150)) {
                val x = WorldProjection.robinsonX(lon.toFloat(), lat.toFloat())
                // 与经度**成比例** ⇒ 线性（Robinson 在经度方向是直纹圆柱）
                assertEquals(
                    "x 必须与经度成比例 @lat=$lat lon=$lon",
                    WorldProjection.robinsonX(10f, lat.toFloat()) * lon / 10f,
                    x,
                    1e-4f
                )
                assertEquals(
                    "x 必须是奇函数 @lat=$lat lon=$lon",
                    -x,
                    WorldProjection.robinsonX((-lon).toFloat(), lat.toFloat()),
                    1e-5f
                )
            }
        }
        // 高纬收窄（X 系数 < 1）且随 |lat| 单调不增
        var prev = WorldProjection.robinsonX(180f, 0f)
        for (lat in 0..90 step 5) {
            val x = WorldProjection.robinsonX(180f, lat.toFloat())
            assertTrue("x 必须随纬度收窄 @lat=$lat（$prev → $x）", x <= prev + 1e-6f)
            prev = x
        }
    }

    @Test
    fun `robinson x at the equator reaches the classic 0_8487 half width`() {
        // 归一化口径：赤道半宽 = 0.8487（经典 Robinson 常数），极点 x = 0.8487 × 0.5322
        assertEquals(0.8487f, WorldProjection.robinsonX(180f, 0f), 1e-4f)
        assertEquals(-0.8487f, WorldProjection.robinsonX(-180f, 0f), 1e-4f)
        assertEquals(0.8487f * 0.5322f, WorldProjection.robinsonX(180f, 90f), 1e-4f)
        for (lat in -90..90 step 5) {
            for (lon in intArrayOf(-180, -120, -60, 0, 60, 120, 180)) {
                val x = WorldProjection.robinsonX(lon.toFloat(), lat.toFloat())
                assertTrue("|x| 必须 ≤ 1（实际 $x @lon=$lon lat=$lat）", kotlin.math.abs(x) <= 1f)
            }
        }
    }

    @Test
    fun `robinson y is monotone in latitude with poles at plus minus 1`() {
        var prev = WorldProjection.robinsonY(0f, -90f)
        assertEquals("南极必须是 -1", -1f, prev, 1e-6f)
        for (lat in -85..85 step 5) {
            val y = WorldProjection.robinsonY(0f, lat.toFloat())
            assertTrue("y 必须随纬度单调递增（lat=$lat：$prev → $y）", y > prev)
            prev = y
        }
        assertEquals("北极必须是 +1", 1f, WorldProjection.robinsonY(0f, 90f), 1e-6f)
        assertEquals("赤道必须是 0", 0f, WorldProjection.robinsonY(0f, 0f), 0f)
        // 南北对称
        for (lat in 5..85 step 5) {
            assertEquals(
                "南北必须对称 @lat=$lat",
                -WorldProjection.robinsonY(0f, lat.toFloat()),
                WorldProjection.robinsonY(0f, (-lat).toFloat()),
                1e-6f
            )
        }
        // y 与经度无关
        assertEquals(WorldProjection.robinsonY(0f, 37f), WorldProjection.robinsonY(120f, 37f), 0f)
    }

    @Test
    fun `robinson y table sums to the classic 10_2609`() {
        // Y_STEP 是 5° 纬带的**增量**，Σ = 极点原始 y 总长（经典值 10.2609）；
        // 归一化后 Y_CUM[18] 必须精确为 1。
        assertEquals(10.2609f, WorldProjection.Y_POLE_RAW, 0.001f)
    }

    @Test
    fun `robinson matches table values at standard parallels`() {
        // 赤道 X 系数 1.0（→ 0.8487）、45° X 系数 0.8962（→ 0.7607）、极点 0.5322（→ 0.4517）
        assertEquals(0.8487f * 0.8962f, WorldProjection.robinsonX(180f, 45f), 1e-4f)
        assertEquals(0.8487f * 0.7597f, WorldProjection.robinsonX(180f, 65f), 1e-3f)
        // 归一化 y：5° = 0.0620 / 10.2609 ≈ 0.006043
        assertEquals(0.0620f / 10.2609f, WorldProjection.robinsonY(0f, 5f), 1e-5f)
    }

    @Test
    fun `map scale keeps the whole map inside every canvas`() {
        val margin = 0.06f
        val halfW = WorldProjection.HALF_WIDTH
        for (wh in arrayOf(1920 to 1080, 1080 to 1920, 2560 to 1440, 1200 to 2000, 800 to 480)) {
            val (w, h) = wh
            val scale = WorldProjection.mapScale(w.toFloat(), h.toFloat(), margin)
            val usableW = w * (1f - margin) * 0.5f
            val usableH = h * (1f - margin) * 0.5f
            assertTrue("$w×$h：scale 必须为正", scale > 0f)
            assertTrue(
                "$w×$h：地图半宽 ${halfW * scale} 超出可用半宽 $usableW",
                halfW * scale <= usableW + 0.5f
            )
            assertTrue("$w×$h：地图半高 $scale 超出可用半高 $usableH", scale <= usableH + 0.5f)
            // 中心必须是画布中心
            assertEquals(w * 0.5f, WorldProjection.mapCenterX(w.toFloat()), 0f)
            assertEquals(h * 0.5f, WorldProjection.mapCenterY(h.toFloat()), 0f)
        }
    }

    @Test
    fun `map scale is defensive about bad input`() {
        assertEquals(0f, WorldProjection.mapScale(0f, 100f, 0.06f), 0f)
        assertEquals(0f, WorldProjection.mapScale(100f, -5f, 0.06f), 0f)
        // margin ≥ 0.5 必须被钳住，否则算出负数/零
        val a = WorldProjection.mapScale(1080f, 1080f, 0.9f)
        assertTrue("异常 margin 必须钳位（实际 $a）", a > 0f)
        // 同短边 ⇒ 同 scale（1920×1080 与 1080×1920 共享 1080 这条短边）
        assertEquals(
            WorldProjection.mapScale(1920f, 1080f, 0.06f),
            WorldProjection.mapScale(1080f, 1920f, 0.06f),
            0f
        )
    }

    @Test
    fun `map pixel bounds are consistent with map scale on both orientations`() {
        val margin = 0.06f
        for (wh in arrayOf(1920 to 1080, 1080 to 1920)) {
            val (w, h) = wh
            val wf = w.toFloat()
            val hf = h.toFloat()
            val scale = WorldProjection.mapScale(wf, hf, margin)
            val halfW = WorldProjection.HALF_WIDTH * scale
            val left = WorldProjection.mapLeft(wf, hf, margin)
            val right = WorldProjection.mapRight(wf, hf, margin)
            val top = WorldProjection.mapTop(wf, hf, margin)
            val bottom = WorldProjection.mapBottom(wf, hf, margin)

            // ⛔ 核心不变式：边界必须与 mapScale / mapCenter 严格一致
            assertEquals("$w×$h 左边界", WorldProjection.mapCenterX(wf) - halfW, left, 1e-3f)
            assertEquals("$w×$h 右边界", WorldProjection.mapCenterX(wf) + halfW, right, 1e-3f)
            assertEquals("$w×$h 上边界", WorldProjection.mapCenterY(hf) - scale, top, 1e-3f)
            assertEquals("$w×$h 下边界", WorldProjection.mapCenterY(hf) + scale, bottom, 1e-3f)
            assertEquals("$w×$h 地图全宽 = 2 × HALF_WIDTH × scale", 2f * halfW, right - left, 1e-3f)
            assertEquals("$w×$h 地图全高 = 2 × scale", 2f * scale, bottom - top, 1e-3f)

            // 关于画布中心对称，且整体落在画布内（裁剪夜面时直接可用）
            assertEquals("$w×$h 水平居中", wf, left + right, 1e-3f)
            assertEquals("$w×$h 垂直居中", hf, top + bottom, 1e-3f)
            assertTrue("$w×$h 左边界应 > 0（$left）", left > 0f)
            assertTrue("$w×$h 右边界应 < 画布宽（$right）", right < wf)
            assertTrue("$w×$h 上边界应 > 0（$top）", top > 0f)
            assertTrue("$w×$h 下边界应 < 画布高（$bottom）", bottom < hf)
        }

        // 具体数值：1920×1080 下左边界 = 960 − 0.8487 × 507.6 = 529.2
        // （历史上夜面填充就是在这里左右各溢出约 530 px 后被手工 clipRect 兜住的）
        assertEquals(529.2f, WorldProjection.mapLeft(1920f, 1080f, margin), 0.5f)
        assertEquals(1390.8f, WorldProjection.mapRight(1920f, 1080f, margin), 0.5f)
        assertEquals(32.4f, WorldProjection.mapTop(1920f, 1080f, margin), 0.5f)
        assertEquals(1047.6f, WorldProjection.mapBottom(1920f, 1080f, margin), 0.5f)
        // 竖屏：短边 = 宽 ⇒ 两参重载与三参重载必须逐位相同
        assertEquals(WorldProjection.mapLeft(1080f, 1920f, margin), WorldProjection.mapLeft(1080f, margin), 0f)
        assertEquals(WorldProjection.mapRight(1080f, 1920f, margin), WorldProjection.mapRight(1080f, margin), 0f)
        assertEquals(109.2f, WorldProjection.mapLeft(1080f, 1920f, margin), 0.5f)
        // 横屏：短边 = 高 ⇒ 顶/底两参重载与三参重载相同
        assertEquals(WorldProjection.mapTop(1920f, 1080f, margin), WorldProjection.mapTop(1080f, margin), 0f)
        assertEquals(WorldProjection.mapBottom(1920f, 1080f, margin), WorldProjection.mapBottom(1080f, margin), 0f)
        assertEquals(452.4f, WorldProjection.mapTop(1080f, 1920f, margin), 0.5f)

        // 退化输入：某一边 ≤ 0 ⇒ mapScale = 0 ⇒ 该轴的地图退化成「画布中心一个点」。
        // 宽为 0、高为 100 ⇒ 左右都塌到 0，上下塌到 100 的中心 50。
        assertEquals(0f, WorldProjection.mapLeft(0f, 100f, margin), 0f)
        assertEquals(0f, WorldProjection.mapRight(0f, 100f, margin), 0f)
        assertEquals(50f, WorldProjection.mapTop(0f, 100f, margin), 0f)
        assertEquals(50f, WorldProjection.mapBottom(0f, 100f, margin), 0f)
        // 高为 0、宽为 100 ⇒ 上下都塌到 0，左右塌到 100 的中心 50
        assertEquals(50f, WorldProjection.mapLeft(100f, 0f, margin), 0f)
        assertEquals(50f, WorldProjection.mapRight(100f, 0f, margin), 0f)
        assertEquals(0f, WorldProjection.mapTop(100f, 0f, margin), 0f)
        assertEquals(0f, WorldProjection.mapBottom(100f, 0f, margin), 0f)
    }

    @Test
    fun `screen mapping has a single exit and flips the y sign once`() {        val scale = 500f
        assertEquals("x = 中心 + nx×scale", 1100f, WorldProjection.toScreenX(0.2f, scale, 1000f), 1e-3f)
        assertEquals("y = 中心 + ny×scale", 1250f, WorldProjection.toScreenY(0.5f, scale, 1000f), 1e-3f)
        // 归一化 y 为正（北）⇒ 屏幕 y 更小
        assertTrue(
            "北半球必须画在中心之上",
            WorldProjection.nyToScreenY(0.5f, scale, 1000f) < WorldProjection.toScreenY(0f, scale, 1000f)
        )
        assertEquals(
            WorldProjection.toScreenY(-0.5f, scale, 1000f),
            WorldProjection.nyToScreenY(0.5f, scale, 1000f),
            0f
        )
        // 归一化坐标 → 屏幕：y 轴在屏幕上是翻转的
        val lon = 121.47f
        val lat = 31.23f
        val ny = WorldProjection.robinsonY(lon, lat)
        assertTrue("上海在北半球（ny=$ny）", ny > 0f)
        assertTrue(
            "上海（北）必须画在赤道之上",
            WorldProjection.nyToScreenY(ny, scale, 1000f) < WorldProjection.nyToScreenY(0f, scale, 1000f)
        )
    }

    // ══ ③ 晨昏线 ═══════════════════════════════════════════════

    @Test
    fun `terminator has no solution exactly when tan lat times tan dec exceeds 1`() {
        for (latDeg in intArrayOf(-80, -60, -45, -30, -10, 0, 10, 30, 45, 60, 80)) {
            for (decDeg in intArrayOf(-23, -20, -10, -5, 0, 5, 10, 20, 23)) {
                val k = kotlin.math.tan(Math.toRadians(latDeg.toDouble())) *
                    kotlin.math.tan(Math.toRadians(decDeg.toDouble()))
                // |tan(φ)·tan(δ)| == 1 是**退化边界**（晨昏线恰好切过极点）：数学上
                // tan(80°)·tan(10°) 恒等于 1，而 Float / Double 舍入会各落到一侧，
                // 谁在阈值上下是平台相关的 —— 这类格子跳过，不做方向性断言。
                if (kotlin.math.abs(kotlin.math.abs(k) - 1.0) < 1e-4) continue
                val expectedNaN = kotlin.math.abs(k) > 1.0
                val h = WorldTerminator.terminatorLon(latDeg.toFloat(), decDeg.toFloat())
                assertEquals(
                    "lat=$latDeg dec=$decDeg ⇒ |tan·tan|=$k",
                    expectedNaN,
                    h.isNaN()
                )
                if (!expectedNaN) {
                    assertTrue("H₀ 必须在 [0,180]：$h", h in 0f..180f)
                }
            }
        }
        // 赤道上永远有解（H₀ = 90°）
        assertEquals(90f, WorldTerminator.terminatorLon(0f, 23.44f), 0.01f)
        assertEquals(90f, WorldTerminator.terminatorLon(0f, -23.44f), 0.01f)
        // 极昼：无解（|tan(80°)|×|tan(23.44°)| = 5.67×0.4335 = 2.46 > 1）
        assertTrue(WorldTerminator.terminatorLon(80f, 23.44f).isNaN())
        // 赤道附近有解
        assertFalse(WorldTerminator.terminatorLon(0.5f, 23.44f).isNaN())
    }

    @Test
    fun `day side is exactly one half plane at equinox`() {
        val t = 1_757_000_000_000L // 任取固定时刻，保证可复现
        val lon0 = WorldTerminator.subsolarMeridianLon(t)
        assertTrue("子午线经度必须在 [-180,180]：$lon0", lon0 in -180f..180f)
        var dayCount = 0
        var nightCount = 0
        for (lat in intArrayOf(-80, -45, 0, 45, 80)) {
            for (lon in -180..180 step 7) {
                val day = WorldTerminator.isDay(lon.toFloat(), lat.toFloat(), 0f, t)
                val h = kotlin.math.abs(WorldTerminator.solarHourAngle(lon.toFloat(), t))
                assertEquals("春分日 lat=$lat lon=$lon", h < 90f, day)
                if (day) dayCount++ else nightCount++
            }
        }
        // 两个半球都必须出现，否则这条断言是空转
        assertTrue("应有白昼样本", dayCount > 100)
        assertTrue("应有黑夜样本", nightCount > 100)
        // 子午线上是正午（白昼），其正对侧是午夜
        assertTrue(WorldTerminator.isDay(lon0, 0f, 0f, t))
        assertFalse(WorldTerminator.isDay(wrap(lon0 + 180f), 0f, 0f, t))
    }

    @Test
    fun `terminator rotates one full turn per day`() {
        val t0 = 1_757_000_000_000L
        val hourMs = 3_600_000L
        // ① 昏线经度**向西**移动：24 小时整 -360°
        var prev = WorldTerminator.terminatorLonAt(0f, 0f, t0)
        var acc = 0f
        for (h in 1..24) {
            val cur = WorldTerminator.terminatorLonAt(0f, 0f, t0 + h * hourMs)
            acc += wrap(cur - prev)
            prev = cur
        }
        assertEquals("晨昏线 24 小时应转满 -360°", -360f, acc, 1.0f)
        // ② 固定经度处的太阳时角**递增**（该点从上午转到下午）：+15°/h ⇒ +360°/24h
        var accH = 0f
        var prevH = WorldTerminator.solarHourAngle(0f, t0)
        for (h in 1..24) {
            val curH = WorldTerminator.solarHourAngle(0f, t0 + h * hourMs)
            accH += wrap(curH - prevH)
            prevH = curH
        }
        assertEquals("太阳时角 24 小时应走满 +360°", 360f, accH, 0.5f)
    }

    @Test
    fun `subsolar latitude stays within the obliquity over a full year`() {
        val dayMs = 86_400_000L
        val t0 = 1_735_689_600_000L // 2025-01-01 UTC
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        // 逐日 + 逐月抽样：年周期由 dayOfYear 驱动，必须覆盖到两极值
        for (i in 0..366) {
            for (hh in intArrayOf(0, 6, 12, 18)) {
                val d = WorldTerminator.subsolarLat(t0 + i * dayMs + hh * 3_600_000L)
                assertTrue("赤纬必须有限：$d", d.isFinite())
                assertTrue("赤纬必须落在 ±23.5 内：$d", d in -23.5f..23.5f)
                if (d < lo) lo = d
                if (d > hi) hi = d
            }
        }
        assertTrue("全年必须扫到北半球极值（实际 hi=$hi）", hi > 23.0f)
        assertTrue("全年必须扫到南半球极值（实际 lo=$lo）", lo < -23.0f)
        assertTrue("年振幅应接近 2×23.44（实际 ${hi - lo}）", hi - lo > 45f)
        // 春分/秋分附近过零（幅值 → 0）
        var minAbs = Float.MAX_VALUE
        for (i in 0..366) {
            val d = kotlin.math.abs(
                WorldTerminator.subsolarLat(t0 + i * dayMs + 12 * 3_600_000L)
            )
            if (d < minAbs) minAbs = d
        }
        assertTrue("全年应存在赤纬≈0 的时刻（实际 $minAbs）", minAbs < 0.5f)
    }

    @Test
    fun `isDay and terminator are mutually consistent`() {
        val t = 1_757_000_000_000L
        val lon0 = WorldTerminator.subsolarMeridianLon(t)
        for (lat in intArrayOf(-60, -30, 0, 30, 60)) {
            for (dec in intArrayOf(-20, 0, 20)) {
                val h0 = WorldTerminator.terminatorLon(lat.toFloat(), dec.toFloat())
                if (h0.isNaN()) continue
                // ① 晨昏线上太阳高度因子必须 ≈ 0（而不是断言 isDay 的正负 ——
                //    那一瞬间 cosZ 恰为 0，Float 舍入决定归属，是掷硬币）
                val lonEv = wrap(lon0 + h0)
                val f = WorldTerminator.sunFactor(lonEv, lat.toFloat(), dec.toFloat(), t)
                assertTrue("昏线上的太阳高度因子应 ≈ 0（实测 $f）", f < 1e-3f)
                val lonDw = wrap(lon0 - h0)
                val fd = WorldTerminator.sunFactor(lonDw, lat.toFloat(), dec.toFloat(), t)
                assertTrue("晨线上的太阳高度因子应 ≈ 0（实测 $fd）", fd < 1e-3f)
                // ② 昏线内侧（往子午线挪 0.5°）是白昼、外侧是黑夜，且因子单调
                val fIn = WorldTerminator.sunFactor(wrap(lonEv - 0.5f), lat.toFloat(), dec.toFloat(), t)
                val fOut = WorldTerminator.sunFactor(wrap(lonEv + 0.5f), lat.toFloat(), dec.toFloat(), t)
                assertTrue("昏线内侧应是白昼 lat=$lat dec=$dec（fIn=$fIn）", fIn > 0f)
                assertEquals("昏线外侧应是黑夜 lat=$lat dec=$dec（fOut=$fOut）", 0f, fOut, 0f)
                assertTrue("晨线内侧应是白昼 lat=$lat dec=$dec", WorldTerminator.sunFactor(
                    wrap(lonDw + 0.5f), lat.toFloat(), dec.toFloat(), t
                ) > 0f)
                assertEquals("晨线外侧应是黑夜 lat=$lat dec=$dec", 0f, WorldTerminator.sunFactor(
                    wrap(lonDw - 0.5f), lat.toFloat(), dec.toFloat(), t
                ), 0f)
                // ③ isDay 与 sunFactor 在远离晨昏线处必须完全同号
                for (d in floatArrayOf(-20f, -5f, 5f, 20f)) {
                    val lon = wrap(lonEv + d)
                    val day = WorldTerminator.isDay(lon, lat.toFloat(), dec.toFloat(), t)
                    val sf = WorldTerminator.sunFactor(lon, lat.toFloat(), dec.toFloat(), t)
                    assertEquals("isDay 与 sunFactor 必须一致 lat=$lat dec=$dec d=$d", sf > 0f, day)
                }
                // ④ 子午线上时角必须是 0，且太阳高度因子 = cos(|lat − δ|)
                //    （当地正午的太阳高度 = 90° − |纬度差|，这是几何恒等式）
                assertEquals("子午线处时角必须是 0", 0f, WorldTerminator.solarHourAngle(lon0, t), 1e-3f)
                val noonFactor = WorldTerminator.sunFactor(lon0, lat.toFloat(), dec.toFloat(), t)
                val expectedNoon = kotlin.math.cos(
                    kotlin.math.abs(lat.toDouble() - dec.toDouble()) * Math.PI / 180.0
                )
                assertEquals("正午太阳高度因子 lat=$lat dec=$dec", expectedNoon.toFloat(), noonFactor, 1e-3f)
            }
        }
    }

    @Test
    fun `solar hour angle is zero at the subsolar meridian and noon at UTC noon`() {
        val t = 1_757_000_000_000L
        // ① 子午线处的时角必为 0（不依赖「这一刻几点」这个前提）
        for (offsetHours in intArrayOf(0, 1, 5, 11, 13, 23)) {
            val tt = t + offsetHours * 3_600_000L
            val lon0 = WorldTerminator.subsolarMeridianLon(tt)
            assertTrue("子午线经度必须在 [-180,180]：$lon0", lon0 in -180f..180f)
            assertEquals(
                "子午线处时角必须是 0（+$offsetHours h）",
                0f,
                WorldTerminator.solarHourAngle(lon0, tt),
                1e-3f
            )
        }
        // ② 真正的 UTC 正午（当日 12:00）子午线落在本初子午线上
        val noonMs = t - (t % 86_400_000L) + 12 * 3_600_000L
        assertEquals("UTC 12:00 时子午线在 0°", 0f, WorldTerminator.subsolarMeridianLon(noonMs), 0.01f)
        assertEquals(0f, WorldTerminator.solarHourAngle(0f, noonMs), 0.01f)
        // ③ 每小时 +15°（向东为下午）
        val h0 = WorldTerminator.solarHourAngle(0f, t)
        val h1 = WorldTerminator.solarHourAngle(0f, t + 3_600_000L)
        assertEquals(15f, wrap(h1 - h0), 0.01f)
        // 逆时区
        assertEquals("东经 90° 的时角应晚 90°", 90f, wrap(WorldTerminator.solarHourAngle(90f, t) - h0), 0.01f)
    }

    // ══ ④ 节拍分类 ═════════════════════════════════════════════

    @Test
    fun `constant input without a beat never reports strength`() {
        val c = BeatClassifier()
        var s = BeatStrength.NONE
        for (i in 0..600) {
            s = c.update(0.35f, i * 16L, false)
            if (s != BeatStrength.NONE) break
        }
        assertEquals("hasBeat=false 必须恒为 NONE", BeatStrength.NONE, s)
    }

    @Test
    fun `constant input with a beat never reports STRONG`() {
        // 基准收敛到常量电平后 ratio → 1.0 ⇒ 最多 MID，绝不该是 STRONG
        val c = BeatClassifier()
        var sawStrong = false
        var sawMid = false
        for (i in 0..2000) {
            // 每 200ms 放一个「拍点」，但幅度与底噪相同
            val hasBeat = (i % 13 == 0)
            val r = c.update(0.30f, i * 16L, hasBeat)
            if (r == BeatStrength.STRONG) sawStrong = true
            if (r == BeatStrength.MID) sawMid = true
        }
        assertFalse("恒定输入不该出现 STRONG", sawStrong)
        assertTrue("基准收敛后应出现 MID（证明分档不是死代码）", sawMid)
        assertEquals("恒定输入下基准应收敛到该电平", 0.30f, c.baseline(), 0.02f)
    }

    @Test
    fun `a spike far above baseline is STRONG and starts the cooldown`() {
        val c = BeatClassifier()
        for (i in 0..2000) c.update(0.30f, i * 16L, false)
        val base = c.baseline()
        val t = 2001 * 16L
        assertEquals("远高于基准的脉冲应为 STRONG", BeatStrength.STRONG, c.update(1.20f, t, true))
        assertTrue("涟漪强度应饱和到 1", c.lastStrength() > 0.99f)
        // 冷却期内再来一次同样的脉冲 ⇒ NONE
        assertEquals("110ms 冷却内不得重复上报", BeatStrength.NONE, c.update(1.20f, t + 50L, true))
        assertEquals("110ms 冷却内不得重复上报", BeatStrength.NONE, c.update(1.20f, t + 109L, true))
        // 冷却结束后恢复
        assertEquals(
            "冷却结束后应能再次上报",
            BeatStrength.STRONG,
            c.update(1.20f, t + 110L + 400L, true)
        )
        assertTrue("一次脉冲不得把基准顶高太多（$base → ${c.baseline()}）", c.baseline() - base < (1.20f - base) * 0.25f)
    }

    @Test
    fun `a single spike does not permanently inflate later classifications`() {
        val c = BeatClassifier()
        for (i in 0..2000) c.update(0.30f, i * 16L, false)
        val base = c.baseline()
        // 单次巨脉冲
        c.update(3.00f, 2001 * 16L, true)
        // 回到常态电平，间距与真实渲染一致
        var strongCount = 0
        var i = 2002
        while (i < 2600) {
            if (c.update(0.30f, i * 16L, true) == BeatStrength.STRONG) strongCount++
            i++
        }
        assertEquals("基准必须回到常态电平（$base → ${c.baseline()}）", base, c.baseline(), base * 0.05f)
        assertEquals("脉冲之后不得出现持续的假 STRONG（实测 $strongCount 次）", 0, strongCount)
    }

    @Test
    fun `classifier is defensive about garbage input`() {
        val c = BeatClassifier(windowMs = 0L)
        assertEquals(BeatStrength.NONE, c.update(Float.NaN, 0L, true))
        assertEquals(BeatStrength.NONE, c.update(1f, -5000L, false)) // 时钟回退
        assertEquals(BeatStrength.NONE, c.update(-1f, 1000L, false)) // 负能量
        assertTrue("强度必须恒在 [0,1]", c.lastStrength() in 0f..1f)
        // 静默段不得除零爆掉
        val z = BeatClassifier()
        for (i in 0..500) z.update(0f, i * 16L, true)
        assertTrue("静默段强度必须有限", z.lastStrength().isFinite())
    }

    // ══ ⑤ 航线网络 ═════════════════════════════════════════════

    @Test
    fun `classify follows the tier hierarchy`() {
        assertEquals(WorldRouteClass.TRUNK, WorldNetwork.classify(1, 1))
        assertEquals(WorldRouteClass.TRUNK, WorldNetwork.classify(1, 2))
        assertEquals(WorldRouteClass.TRUNK, WorldNetwork.classify(2, 1))
        assertEquals(WorldRouteClass.TRUNK, WorldNetwork.classify(2, 2))
        assertEquals(WorldRouteClass.REGIONAL, WorldNetwork.classify(1, 3))
        assertEquals(WorldRouteClass.REGIONAL, WorldNetwork.classify(3, 1))
        assertEquals(WorldRouteClass.REGIONAL, WorldNetwork.classify(3, 3))
        assertEquals(WorldRouteClass.REGIONAL, WorldNetwork.classify(3, 2))
        assertEquals(WorldRouteClass.FEEDER, WorldNetwork.classify(1, 4))
        assertEquals(WorldRouteClass.FEEDER, WorldNetwork.classify(4, 1))
        assertEquals(WorldRouteClass.FEEDER, WorldNetwork.classify(4, 4))
        assertEquals("Tier4 优先于 Tier3", WorldRouteClass.FEEDER, WorldNetwork.classify(3, 4))
        assertEquals(WorldRouteClass.FEEDER, WorldNetwork.classify(4, 3))
    }

    @Test
    fun `route weight is symmetric and ordered along the hierarchy spine`() {
        for (a in 1..4) {
            for (b in 1..4) {
                assertEquals(
                    "routeWeight 必须对称 ($a,$b)",
                    WorldNetwork.routeWeight(a, b),
                    WorldNetwork.routeWeight(b, a),
                    0f
                )
                assertTrue("权重必须为正", WorldNetwork.routeWeight(a, b) > 0f)
            }
        }
        assertEquals(1.0f, WorldNetwork.routeWeight(1, 1), 1e-6f)
        assertTrue(WorldNetwork.routeWeight(1, 1) > WorldNetwork.routeWeight(1, 2))
        assertTrue(WorldNetwork.routeWeight(1, 2) > WorldNetwork.routeWeight(1, 3))
        assertTrue(WorldNetwork.routeWeight(1, 3) > WorldNetwork.routeWeight(1, 4))
        assertTrue(WorldNetwork.routeWeight(2, 2) > WorldNetwork.routeWeight(2, 3))
        assertTrue(WorldNetwork.routeWeight(2, 3) > WorldNetwork.routeWeight(2, 4))
        assertTrue(WorldNetwork.routeWeight(3, 3) > WorldNetwork.routeWeight(3, 4))
        // 主干最细(T2–T2) 仍重于毛细最粗(T1–T4) ⇒ 「先砍毛细」永远是安全的
        assertTrue(
            WorldNetwork.routeWeight(2, 2) > WorldNetwork.routeWeight(1, 4)
        )
    }

    @Test
    fun `arc boost and brightness decrease along the hierarchy`() {
        assertTrue(WorldNetwork.arcBoost(WorldRouteClass.TRUNK) > WorldNetwork.arcBoost(WorldRouteClass.REGIONAL))
        assertTrue(WorldNetwork.arcBoost(WorldRouteClass.REGIONAL) > WorldNetwork.arcBoost(WorldRouteClass.FEEDER))
        assertEquals(1f, WorldNetwork.arcBoost(WorldRouteClass.TRUNK), 0f)
        assertTrue(
            WorldNetwork.trunkBrightness(WorldRouteClass.TRUNK) >
                WorldNetwork.trunkBrightness(WorldRouteClass.REGIONAL)
        )
        assertTrue(
            WorldNetwork.trunkBrightness(WorldRouteClass.REGIONAL) >
                WorldNetwork.trunkBrightness(WorldRouteClass.FEEDER)
        )
    }

    @Test
    fun `pickRoute never returns a self loop and honours the tier class`() {
        for (seed in 1..2000) {
            val rnd = WorldRng(seed.toUInt())
            val beat = BeatStrength.entries[seed % 4]
            val r = WorldNetwork.pickRoute(rnd, -1, beat)
            assertNotEquals("不能自连", r.from, r.to)
            assertTrue("from 越界 ${r.from}", r.from in 0 until WorldCities.COUNT)
            assertTrue("to 越界 ${r.to}", r.to in 0 until WorldCities.COUNT)
            // ⛔ 等级**恰好**等于 classify（不是「至少一样细」）——拍点不得覆写
            assertEquals(
                "等级必须恒等于 classify(${WorldCities.tierOf(r.from)}, ${WorldCities.tierOf(r.to)})",
                WorldNetwork.classify(WorldCities.tierOf(r.from), WorldCities.tierOf(r.to)),
                r.klass
            )
            assertEquals(
                "weight 必须等于两端 tier 的乘积",
                WorldNetwork.routeWeight(WorldCities.tierOf(r.from), WorldCities.tierOf(r.to)),
                r.weight,
                0f
            )
            assertEquals(WorldNetwork.arcBoost(r.klass), r.arcBoost, 0f)
            assertEquals(WorldNetwork.trunkBrightness(r.klass), r.trunkBias, 0f)
        }
    }

    // ── 缺陷 1 回归：近对跖点航线 ──────────────────────────────

    @Test
    fun `every city has a reachable partner inside the distance cap`() {
        // 这是 clampWithinRange 的「前提」：若某座城连最近的伙伴都超出上限，
        // 兜底分支（放宽等级 / 无视距离）就会变成常态而非死代码。
        var worstName = ""
        var worstKm = 0f
        for (i in 0 until WorldCities.COUNT) {
            var best = Float.MAX_VALUE
            for (j in 0 until WorldCities.COUNT) {
                if (j != i) best = minOf(best, WorldCities.distanceKm(i, j))
            }
            if (best > worstKm) {
                worstKm = best
                worstName = WorldCities.labelOf(i)
            }
        }
        assertTrue(
            "$worstName 的最近伙伴在 $worstKm km，超过上限 ${WorldNetwork.MAX_ROUTE_KM}",
            worstKm <= WorldNetwork.MAX_ROUTE_KM
        )
    }

    @Test
    fun `no generated route exceeds the real world nonstop distance cap`() {
        // 上限本身：15,000 km ≈ 134.9° 球心角（现实中最长的不停站直飞约 15,000–15,500 km）
        assertEquals(
            134.9,
            WorldNetwork.MAX_ROUTE_KM / WorldCities.EARTH_RADIUS_KM.toDouble() * 180.0 / Math.PI,
            0.2
        )
        // 出问题的那一对本身就是「近对跖点」：布宜诺斯艾利斯 ↔ 上海 19,640 km / 176.6°
        val bad = WorldCities.distanceKm(indexOf("buiyinuosiailisi"), indexOf("shanghai"))
        assertTrue("前提失效：这对城市应是 ~19,600 km 的近对跖点（实测 $bad）", bad > 19000f)
        assertTrue(
            "前提失效：这对城市的球心角应是 ~176.6°",
            centralAngleDeg(bad) > 175.0
        )

        var maxKm = 0f
        var maxDeg = 0.0
        var badPairHits = 0
        var n = 0
        // 焦点城市刻意覆盖：Tier4 叶端（布宜诺斯艾利斯）、最南的枢纽（悉尼在 Tier3）、
        // 大洋洲孤点（奥克兰）、以及无焦点
        val focuses = intArrayOf(-1, indexOf("buiyinuosiailisi"), indexOf("aokelan"), indexOf("niuyue"))
        for (focus in focuses) {
            for (beat in BeatStrength.entries) {
                for (seed in 1..2500) {
                    val r = WorldNetwork.pickRoute(WorldRng(seed.toUInt()), focus, beat)
                    val km = WorldCities.distanceKm(r.from, r.to)
                    val deg = centralAngleDeg(km)
                    assertTrue(
                        "超出距离上限：${WorldCities.labelOf(r.from)} → ${WorldCities.labelOf(r.to)} " +
                            "= $km km（$deg°），focus=$focus beat=$beat seed=$seed",
                        km <= WorldNetwork.MAX_ROUTE_KM
                    )
                    assertTrue(
                        "球心角不得超过 136°（实测 $deg°）：" +
                            "${WorldCities.labelOf(r.from)} → ${WorldCities.labelOf(r.to)}",
                        deg <= 136.0
                    )
                    val pair = setOf(WorldCities.ALL[r.from].pinyin, WorldCities.ALL[r.to].pinyin)
                    if (pair == setOf("buiyinuosiailisi", "shanghai")) badPairHits++
                    if (km > maxKm) maxKm = km
                    if (deg > maxDeg) maxDeg = deg
                    n++
                }
            }
        }
        assertEquals("扫描总数", 4 * 4 * 2500, n)
        assertEquals("那条俯冲南极洲的航线绝不能再出现", 0, badPairHits)
        println("route cap sweep: n=$n maxKm=$maxKm maxDeg=$maxDeg")
    }

    // ── 缺陷 2 回归：等级只能由端点决定 ──────────────────────────

    @Test
    fun `route class is purely endpoint driven and never overridden by the beat`() {
        // 焦点城市刻意包含 Tier1（纽约）与 Tier4（开罗）：
        // 旧实现里 `maxOf(want, classify(...))` 会在「Tier1 焦点 + WEAK 拍」下
        // 把 T1↔T3 的跨大西洋支线标成 FEEDER（最细），本测试必须能抓住它。
        val focuses = intArrayOf(
            -1,
            indexOf("niuyue"),
            indexOf("kailuo"),
            indexOf("buiyinuosiailisi"),
            indexOf("fulankefu")
        )
        var checked = 0
        for (focus in focuses) {
            for (beat in BeatStrength.entries) {
                for (seed in 1..1500) {
                    val r = WorldNetwork.pickRoute(WorldRng(seed.toUInt()), focus, beat)
                    val t1 = WorldCities.tierOf(r.from)
                    val t2 = WorldCities.tierOf(r.to)
                    assertEquals(
                        "等级必须恒等于 classify —— 实际 ${r.klass}，" +
                            "${WorldCities.labelOf(r.from)}(T$t1) → ${WorldCities.labelOf(r.to)}(T$t2)，" +
                            "focus=$focus beat=$beat seed=$seed",
                        WorldNetwork.classify(t1, t2),
                        r.klass
                    )
                    checked++
                }
            }
        }
        assertEquals("扫描总数", 5 * 4 * 1500, checked)
    }

    @Test
    fun `hub to tier3 is always regional and hub to hub is always trunk whatever the beat`() {
        var hubTier3 = 0
        var hubHub = 0
        for (i in 0 until WorldCities.COUNT) {
            if (WorldCities.tierOf(i) != 1) continue
            for (beat in BeatStrength.entries) {
                for (seed in 1..400) {
                    val r = WorldNetwork.pickRoute(WorldRng(seed.toUInt()), i, beat)
                    val t1 = WorldCities.tierOf(r.from)
                    val t2 = WorldCities.tierOf(r.to)
                    if (t1 <= 2 && t2 == 3) {
                        hubTier3++
                        assertEquals(
                            "Tier≤2 ↔ Tier3 必须是 REGIONAL（实际 ${r.klass}）：" +
                                "${WorldCities.labelOf(r.from)} → ${WorldCities.labelOf(r.to)}，beat=$beat",
                            WorldRouteClass.REGIONAL,
                            r.klass
                        )
                    }
                    if (t1 <= 2 && t2 <= 2) {
                        hubHub++
                        assertEquals(
                            "Tier≤2 ↔ Tier≤2 必须是 TRUNK，绝不能被削细（实际 ${r.klass}）：" +
                                "${WorldCities.labelOf(r.from)} → ${WorldCities.labelOf(r.to)}，beat=$beat",
                            WorldRouteClass.TRUNK,
                            r.klass
                        )
                    }
                }
            }
        }
        // 这两个格子都不是空转：否则断言是死的
        assertTrue("Tier≤2↔Tier3 样本量不足（$hubTier3）", hubTier3 > 50)
        assertTrue("Tier≤2↔Tier≤2 样本量不足（$hubHub）", hubHub > 200)
    }

    // ── 「枢纽 → 末端」的地理就近 ──────────────────────────────

    @Test
    fun `nearestOfTier returns the closest city of exactly that tier`() {
        // 边界：无解 / 非法层级
        assertEquals(-1, WorldCities.nearestOfTier(-1, 4))
        assertEquals(-1, WorldCities.nearestOfTier(WorldCities.COUNT, 4))
        assertEquals(-1, WorldCities.nearestOfTier(0, 0))
        assertEquals(-1, WorldCities.nearestOfTier(0, WorldCities.TIER_COUNT + 1))
        // 具体抽查：南非没有 Tier1/2 机场，所以「恰好某层」的结果与 nearestHub 的「≤」结果不同 ——
        // 后者会把 T1 也算进来（nearestHub(约翰内斯堡,2) = 迪拜(T1) 6,412 km），
        // 前者必须只在该层内找。
        assertEquals("dibai", WorldCities.ALL[WorldCities.nearestOfTier(indexOf("yuehanneisibao"), 1)].pinyin)
        assertEquals("deli", WorldCities.ALL[WorldCities.nearestOfTier(indexOf("yuehanneisibao"), 2)].pinyin)
        assertEquals("mumbai", WorldCities.ALL[WorldCities.nearestOfTier(indexOf("yuehanneisibao"), 3)].pinyin)
        assertEquals("neiluobi", WorldCities.ALL[WorldCities.nearestOfTier(indexOf("yuehanneisibao"), 4)].pinyin)
        // 逐对穷举：必须真的最近，且层级恰为 target（不是 ≤）
        for (i in 0 until WorldCities.COUNT) {
            for (t in 1..WorldCities.TIER_COUNT) {
                val j = WorldCities.nearestOfTier(i, t)
                if (j < 0) continue
                assertEquals("层级必须恰为 $t", t, WorldCities.tierOf(j))
                assertNotEquals("不能返回自己", i, j)
                val d = WorldCities.distanceKm(i, j)
                for (k2 in 0 until WorldCities.COUNT) {
                    if (k2 == i || WorldCities.tierOf(k2) != t) continue
                    assertTrue(
                        "${WorldCities.labelOf(j)}(T$t) 不是 ${WorldCities.labelOf(i)} 最近的 T$t" +
                            "（${WorldCities.labelOf(k2)} 更近：${WorldCities.distanceKm(i, k2)} < $d）",
                        d <= WorldCities.distanceKm(i, k2)
                    )
                }
            }
        }
    }

    @Test
    fun `nearestOfTier k-ary variant is distance ordered, deterministic and buffer safe`() {
        val k = 3
        val buf = IntArray(k)
        for (i in 0 until WorldCities.COUNT) {
            for (t in 1..WorldCities.TIER_COUNT) {
                // 自己被排除 ⇒ 该层若只有 i 一座则无候选
                val pool = countOfTier(t) - if (WorldCities.tierOf(i) == t) 1 else 0
                val n = WorldCities.nearestOfTier(i, t, k, buf)
                assertEquals("i=${WorldCities.labelOf(i)} t=$t 候选数不对（池 $pool）", minOf(k, pool), n)
                assertTrue("不得返回自己", buf.none { it == i })
                for (p in 0 until n) assertEquals("层级必须恰为 $t", t, WorldCities.tierOf(buf[p]))
                // 距离升序（距离并列时下标小者在前 ⇒ 用 ≤ 而非 <）
                for (p in 1 until n) {
                    assertTrue(
                        "必须距离升序：i=${WorldCities.labelOf(i)} t=$t p=$p",
                        WorldCities.distanceKm(i, buf[p - 1]) <= WorldCities.distanceKm(i, buf[p])
                    )
                }
                // 第 1 个必须与单参版本一致（两者共用同一套「严格 < + 升序遍历」口径）
                assertEquals("k 元的首个必须等于单参版本", WorldCities.nearestOfTier(i, t), buf[0])
                // 确定性：同输入必然同输出（不依赖任何随机源）
                val again = IntArray(k)
                assertEquals(n, WorldCities.nearestOfTier(i, t, k, again))
                for (p in 0 until n) assertEquals("同输入必须同输出 p=$p", buf[p], again[p])
            }
        }
        // k 大于该层可用城市数 ⇒ 返回实际数量，不越界、不留 -1 占位
        val big = IntArray(32)
        for (i in 0 until WorldCities.COUNT) {
            for (t in 1..WorldCities.TIER_COUNT) {
                val pool = countOfTier(t) - if (WorldCities.tierOf(i) == t) 1 else 0
                val n = WorldCities.nearestOfTier(i, t, 32, big)
                assertEquals("i=${WorldCities.labelOf(i)} t=$t", pool, n)
                assertTrue("不得出现 -1 占位", big.none { it < 0 })
            }
        }
        // 缓冲不足 ⇒ 截断而不是越界
        val tiny = IntArray(1)
        assertEquals(1, WorldCities.nearestOfTier(0, 4, 3, tiny))
        // k ≤ 0 / 空缓冲 / 非法入参 ⇒ 0
        assertEquals(0, WorldCities.nearestOfTier(0, 4, 0, tiny))
        assertEquals(0, WorldCities.nearestOfTier(0, 4, -1, tiny))
        assertEquals(0, WorldCities.nearestOfTier(0, 4, 3, IntArray(0)))
        assertEquals(0, WorldCities.nearestOfTier(-1, 4, 3, tiny))
        assertEquals(0, WorldCities.nearestOfTier(0, 0, 3, tiny))
    }

    @Test
    fun `hub to leaf feeder only ever reaches the k nearest tier 4 cities`() {
        val k = WorldNetwork.LEAF_CANDIDATES
        val buf = IntArray(k)
        var checked = 0
        for (hub in 0 until WorldCities.COUNT) {
            if (WorldCities.tierOf(hub) == 4) continue
            val n = WorldCities.nearestOfTier(hub, 4, k, buf)
            assertEquals("每座非 Tier4 城市都该凑满 $k 个 Tier4 候选（${WorldCities.labelOf(hub)}）", k, n)
            // 前置：这 k 座都必须落在距离上限内 —— 否则下面的不变式需要「k 个全超限」的逃生口。
            // 城市表一旦变化（例如新增超远的 Tier4），这条会先失败，且失败信息指向真正的原因。
            for (p in 0 until n) {
                val d = WorldCities.distanceKm(hub, buf[p])
                assertTrue(
                    "${WorldCities.labelOf(hub)} 的第 ${p + 1} 近末端 ${WorldCities.labelOf(buf[p])} = $d km，" +
                        "超出上限 ${WorldNetwork.MAX_ROUTE_KM} ⇒ k-就近不变式将需要逃生口",
                    d <= WorldNetwork.MAX_ROUTE_KM
                )
            }
            val allowed = buf.toHashSet()

            for (beat in BeatStrength.entries) {
                for (seed in 1..500) {
                    val r = WorldNetwork.pickRoute(WorldRng(seed.toUInt()), hub, beat)
                    // ⛔ 只断言**可证明**的那一种来源：起点就是焦点枢纽、终点是 Tier4。
                    //   want=FEEDER 时 [pickEndpoint] 只会返回「焦点」或「某座 Tier4」，
                    //   所以 `r.from == hub` ⇒ preferred == 4..4 ⇒ 走的是就近抽样那条路。
                    // ⚠️ 另一个方向**没有**这个保证：`r.from` 被抽成别的 Tier4 时
                    //   （WEAK 拍下约 40% 的分支），终点是全球随机抽的 Tier1..3 ——
                    //   那是同一族缺陷的另一半（叶端→枢纽的 10% 兜底），见
                    //   `WorldNetwork` 类 KDoc 的「已知残留」一节。
                    if (r.from != hub || WorldCities.tierOf(r.to) != 4) continue
                    assertTrue(
                        "枢纽→末端必须落在最近 $k 座 Tier4 内：${WorldCities.labelOf(hub)} → " +
                            "${WorldCities.labelOf(r.to)}（允许：${allowed.map { WorldCities.labelOf(it) }}），" +
                            "beat=$beat seed=$seed",
                        r.to in allowed
                    )
                    checked++
                }
            }
        }
        assertTrue("样本量不足（$checked）", checked > 3000)
    }

    @Test
    fun `hub leaf partner varies across seeds instead of always the single nearest`() {
        // 就近抽样若退化成「永远最近那一座」，图案会变得机械（种子近乎可预测）。
        // 焦点必须是**非 Tier4** 城市 —— Tier4 焦点走的是「叶端连枢纽」路径，不是这一路。
        for (hub in intArrayOf(
            indexOf("niuyue"), indexOf("yisitanbuer"), indexOf("beijing"),
            indexOf("xinni"), indexOf("chengdu")
        )) {
            assertTrue("测试用的焦点不该是 Tier4：${WorldCities.labelOf(hub)}", WorldCities.tierOf(hub) < 4)
            val buf = IntArray(WorldNetwork.LEAF_CANDIDATES)
            assertEquals(3, WorldCities.nearestOfTier(hub, 4, WorldNetwork.LEAF_CANDIDATES, buf))
            val hits = HashMap<Int, Int>()
            for (seed in 1..2000) {
                val r = WorldNetwork.pickRoute(WorldRng(seed.toUInt()), hub, BeatStrength.WEAK)
                if (r.from != hub || WorldCities.tierOf(r.to) != 4) continue
                hits.merge(r.to, 1, Int::plus)
            }
            assertTrue(
                "${WorldCities.labelOf(hub)} 的末端伙伴不足 2 个（实际 ${hits.keys.size}，" +
                    "最近 3 座 = ${buf.map { WorldCities.labelOf(it) }}）：" +
                    "就近抽样退化成「永远最近那一座」了",
                hits.size >= 2
            )
            println(
                "leaf spread ${WorldCities.labelOf(hub)} (nearest3=" +
                    buf.joinToString("/") { WorldCities.labelOf(it) } + "): " +
                    hits.entries.joinToString("  ") { "${WorldCities.labelOf(it.key)}=${it.value}" }
            )
        }
    }

    @Test
    fun `hub to leaf feeder distance is pulled toward the nearest leaf and never beyond the third`() {
        val k = WorldNetwork.LEAF_CANDIDATES
        val buf = IntArray(k)
        val rows = StringBuilder()
        for (hub in 0 until WorldCities.COUNT) {
            if (WorldCities.tierOf(hub) == 4) continue
            val n = WorldCities.nearestOfTier(hub, 4, k, buf)
            val d1 = WorldCities.distanceKm(hub, buf[0])
            val d2 = WorldCities.distanceKm(hub, buf[1])
            val d3 = WorldCities.distanceKm(hub, buf[2])
            val feeders = ArrayList<Float>()
            for (seed in 1..1500) {
                val r = WorldNetwork.pickRoute(WorldRng(seed.toUInt()), hub, BeatStrength.WEAK)
                if (r.from != hub || WorldCities.tierOf(r.to) != 4) continue
                feeders.add(WorldCities.distanceKm(hub, r.to))
            }
            assertTrue("样本量不足（${WorldCities.labelOf(hub)} 只有 ${feeders.size} 条）", feeders.size > 200)
            val med = median(feeders)
            val max = feeders.max()
            // ① 绝不可能越过第 3 近 —— 「只从最近 k 座里抽」的直接推论
            assertTrue(
                "${WorldCities.labelOf(hub)} 的末端出现了 $max km，超过其第 3 近末端 $d3 km",
                max <= d3 + 1f
            )
            // ② 中位数不得被「较远的那座」支配：权重 0.50/0.30/0.20 ⇒ 中位数落在第 1 或第 2 近
            assertTrue(
                "${WorldCities.labelOf(hub)} 的末端中位距离 $med km 超过其第 2 近末端 $d2 km" +
                    "（d1=$d1 d2=$d2 d3=$d3）",
                med <= d2 + 1f
            )
            rows.append(
                ("  T%d %-16s nearest3=%6.0f/%6.0f/%6.0f km   feeder median=%6.0f  max=%6.0f  n=%d"
                    .format(WorldCities.tierOf(hub), WorldCities.labelOf(hub), d1, d2, d3, med, max, feeders.size)) + "\n"
            )
        }
        println("hub->leaf locality (WEAK beat, 1500 seeds per focus):")
        print(rows)
    }

    @Test
    fun `nearestHubs k-ary variant is the k closest tier-or-coarser cities, deterministic and buffer safe`() {
        val k = 2
        val buf = IntArray(k)
        for (i in 0 until WorldCities.COUNT) {
            for (maxTier in 1..WorldCities.TIER_COUNT) {
                val pool = (0 until WorldCities.COUNT).count { j ->
                    j != i && WorldCities.tierOf(j) <= maxTier
                }
                val n = WorldCities.nearestHubs(i, maxTier, k, buf)
                assertEquals("i=${WorldCities.labelOf(i)} maxTier=$maxTier 候选数不对（池 $pool）", minOf(k, pool), n)
                assertTrue("不得返回自己", buf.none { it == i })
                for (p in 0 until n) {
                    assertTrue(
                        "层级必须 ≤ $maxTier（${WorldCities.labelOf(buf[p])}）",
                        WorldCities.tierOf(buf[p]) <= maxTier
                    )
                }
                for (p in 1 until n) {
                    assertTrue(
                        "必须距离升序：i=${WorldCities.labelOf(i)} maxTier=$maxTier p=$p",
                        WorldCities.distanceKm(i, buf[p - 1]) <= WorldCities.distanceKm(i, buf[p])
                    )
                }
                // 第 1 个必须与单参 nearestHub 一致（两者共用同一套「严格 < + 升序遍历」口径）
                assertEquals(
                    "k 元的首个必须等于 nearestHub",
                    WorldCities.nearestHub(i, maxTier),
                    buf[0]
                )
                // 确定性：同输入必然同输出
                val again = IntArray(k)
                assertEquals(n, WorldCities.nearestHubs(i, maxTier, k, again))
                for (p in 0 until n) assertEquals("同输入必须同输出 p=$p", buf[p], again[p])
            }
        }
        // maxTier = 0 ⇒ 无候选 ⇒ 0；k ≤ 0 / 空缓冲 / 越界入参 ⇒ 0
        assertEquals(0, WorldCities.nearestHubs(0, 0, 2, buf))
        assertEquals(0, WorldCities.nearestHubs(0, 1, 0, buf))
        assertEquals(0, WorldCities.nearestHubs(0, 1, -1, buf))
        assertEquals(0, WorldCities.nearestHubs(0, 1, 2, IntArray(0)))
        assertEquals(0, WorldCities.nearestHubs(-1, 3, 2, buf))
        assertEquals(0, WorldCities.nearestHubs(WorldCities.COUNT, 3, 2, buf))
        // 缓冲不足 ⇒ 截断而不是越界
        assertEquals(1, WorldCities.nearestHubs(0, 4, 2, IntArray(1)))
        // 每座叶端都必须凑满 HUB_CANDIDATES 个候选（否则抽样没有退路）
        val wide = IntArray(WorldNetwork.HUB_CANDIDATES)
        for (leaf in 0 until WorldCities.COUNT) {
            if (WorldCities.tierOf(leaf) != 4) continue
            assertEquals(
                "${WorldCities.labelOf(leaf)} 的近邻枢纽不足 ${WorldNetwork.HUB_CANDIDATES} 座",
                WorldNetwork.HUB_CANDIDATES,
                WorldCities.nearestHubs(leaf, 3, WorldNetwork.HUB_CANDIDATES, wide)
            )
        }
    }

    @Test
    fun `leaf as origin only reaches its k nearest hubs`() {
        // 「叶端不再远求枢纽」的回归测试。焦点覆盖了 origin 叶端的所有来源：
        // 无焦点（起点由 Tier4 池抽）、叶端自己当焦点、Tier1 焦点、Tier3 焦点。
        val k = WorldNetwork.HUB_CANDIDATES
        val buf = IntArray(k)
        var checked = 0
        for (leaf in 0 until WorldCities.COUNT) {
            if (WorldCities.tierOf(leaf) != 4) continue
            val n = WorldCities.nearestHubs(leaf, 3, k, buf)
            assertEquals("每座叶端都该凑满 $k 个近邻枢纽（${WorldCities.labelOf(leaf)}）", k, n)
            // 前置：窗口内必须在距离上限内 ⇒ 不需要「k 个全超限」的逃生口
            for (p in 0 until n) {
                val d = WorldCities.distanceKm(leaf, buf[p])
                assertTrue(
                    "${WorldCities.labelOf(leaf)} 的第 ${p + 1} 近枢纽 ${WorldCities.labelOf(buf[p])} = $d km，" +
                        "超出上限 ${WorldNetwork.MAX_ROUTE_KM} ⇒ 就近不变式将需要逃生口",
                    d <= WorldNetwork.MAX_ROUTE_KM
                )
            }
            val allowed = buf.toHashSet()
            for (focus in intArrayOf(-1, leaf, indexOf("niuyue"), indexOf("fulankefu"))) {
                for (beat in BeatStrength.entries) {
                    for (seed in 1..400) {
                        val r = WorldNetwork.pickRoute(WorldRng(seed.toUInt()), focus, beat)
                        if (r.from != leaf) continue
                        assertTrue(
                            "叶端发起时终点必须落在最近 $k 座 Tier≤3 内：" +
                                "${WorldCities.labelOf(r.from)} → ${WorldCities.labelOf(r.to)}" +
                                "（允许：${allowed.map { WorldCities.labelOf(it) }}），" +
                                "focus=$focus beat=$beat seed=$seed",
                            r.to in allowed
                        )
                        checked++
                    }
                }
            }
        }
        assertTrue("样本量不足（$checked）", checked > 3000)
    }

    @Test
    fun `the two long feeder repro routes are unreachable in either orientation`() {
        // 修复前的两个反例，两个方向都必须够不到：
        //  ① 布宜诺斯艾利斯 ↔ 巴黎 11,072 km（BA 自己最近的 Tier≤3 是圣保罗 1,695 km）
        //  ② 约翰内斯堡 ↔ 纽约   11,855 km（JHB 自己最近的 Tier≤3 是迪拜 6,412 km）
        val repros = arrayOf(
            arrayOf(indexOf("buiyinuosiailisi"), indexOf("bali")),
            arrayOf(indexOf("yuehanneisibao"), indexOf("niuyue"))
        )
        val buf = IntArray(maxOf(WorldNetwork.HUB_CANDIDATES, WorldNetwork.LEAF_CANDIDATES))
        for (pair in repros) {
            val leaf = if (WorldCities.tierOf(pair[0]) == 4) pair[0] else pair[1]
            val hub = if (WorldCities.tierOf(pair[0]) == 4) pair[1] else pair[0]
            val km = WorldCities.distanceKm(leaf, hub)
            assertTrue(
                "前提失效：${WorldCities.labelOf(leaf)} ↔ ${WorldCities.labelOf(hub)} 应是 ~11,000 km 的长程对（实测 $km）",
                km > 10000f
            )
            // 方向一：叶端当起点 ⇒ 终点只能是自己的近邻枢纽
            val n = WorldCities.nearestHubs(leaf, 3, WorldNetwork.HUB_CANDIDATES, buf)
            val leafWindow = buf.copyOf(n).toHashSet()
            assertTrue(
                "${WorldCities.labelOf(hub)} 竟在 ${WorldCities.labelOf(leaf)} 的近邻窗口 $leafWindow 内？",
                hub !in leafWindow
            )
            // 方向二：枢纽当起点 ⇒ 终点只能是自己的最近几座 Tier4
            val m = WorldCities.nearestOfTier(hub, 4, WorldNetwork.LEAF_CANDIDATES, buf)
            val hubWindow = buf.copyOf(m).toHashSet()
            assertTrue(
                "${WorldCities.labelOf(leaf)} 竟在 ${WorldCities.labelOf(hub)} 的 Tier4 窗口 $hubWindow 内？",
                leaf !in hubWindow
            )
            // 端到端：无论焦点与拍点怎么组合，都抽不出这一对
            var emitted = 0
            for (focus in intArrayOf(hub, leaf, -1)) {
                for (beat in BeatStrength.entries) {
                    for (seed in 1..1500) {
                        val r = WorldNetwork.pickRoute(WorldRng(seed.toUInt()), focus, beat)
                        if (setOf(r.from, r.to) == setOf(leaf, hub)) emitted++
                    }
                }
            }
            assertEquals(
                "${WorldCities.labelOf(leaf)} ↔ ${WorldCities.labelOf(hub)}（$km km）不该再出现",
                0,
                emitted
            )
        }
    }

    @Test
    fun `feeder routes are regional and much shorter than trunk routes`() {
        val feeder = ArrayList<Float>()
        val trunk = ArrayList<Float>()
        val beats = arrayOf(BeatStrength.NONE, BeatStrength.NONE, BeatStrength.WEAK, BeatStrength.STRONG)
        for (seed in 1..4000) {
            val r = WorldNetwork.pickRoute(WorldRng(seed.toUInt()), -1, beats[seed % beats.size])
            // 距离闸门对所有等级一视同仁：主干/支线同样不得越界
            assertTrue(
                "任何等级都不得越界：${WorldCities.labelOf(r.from)} → ${WorldCities.labelOf(r.to)} = " +
                    "${WorldCities.distanceKm(r.from, r.to)} km",
                WorldCities.distanceKm(r.from, r.to) <= WorldNetwork.MAX_ROUTE_KM
            )
            when (r.klass) {
                WorldRouteClass.FEEDER -> {
                    // 毛细航线必有一端是 Tier4，另一端必须是 Tier≤3 的中继/枢纽
                    val t1 = WorldCities.tierOf(r.from)
                    val t2 = WorldCities.tierOf(r.to)
                    assertTrue("毛细航线必须含 Tier4（实际 $t1/$t2）", t1 == 4 || t2 == 4)
                    assertTrue("毛细航线的另一端不得高于 Tier3（实际 $t1/$t2）", minOf(t1, t2) <= 3)
                    feeder.add(WorldCities.distanceKm(r.from, r.to))
                }
                WorldRouteClass.TRUNK -> {
                    assertTrue("主干两端都必须是 Tier≤2", WorldCities.tierOf(r.from) <= 2 && WorldCities.tierOf(r.to) <= 2)
                    trunk.add(WorldCities.distanceKm(r.from, r.to))
                }
                else -> Unit
            }
        }
        val mf = if (feeder.isEmpty()) 0f else median(feeder)
        val mt = if (trunk.isEmpty()) 0f else median(trunk)
        assertTrue("样本量不足（feeder=${feeder.size} trunk=${trunk.size}）", feeder.size > 200 && trunk.size > 200)
        assertTrue(
            "毛细中位距离 $mf km 应远低于主干中位距离 $mt km（区域密度不变量）",
            mf < mt * 0.6f
        )
        println("median sweep: feeder n=${feeder.size} median=$mf km   trunk n=${trunk.size} median=$mt km")
    }

    @Test
    fun `strong beats mostly produce trunk routes`() {
        var trunk = 0
        var n = 0
        for (seed in 1..2000) {
            if (WorldNetwork.pickRoute(WorldRng(seed.toUInt()), -1, BeatStrength.STRONG).klass ==
                WorldRouteClass.TRUNK
            ) trunk++
            n++
        }
        assertTrue("STRONG 拍点应产出绝大多数主干航线（实测 $trunk/$n）", trunk > n * 0.9)
    }

    @Test
    fun `mid prefers regional and weak prefers feeder`() {
        var midRegional = 0
        var weakFeeder = 0
        var noneTrunk = 0
        for (seed in 1..2000) {
            val s = seed.toUInt()
            if (WorldNetwork.pickRoute(WorldRng(s), -1, BeatStrength.MID).klass == WorldRouteClass.REGIONAL) midRegional++
            if (WorldNetwork.pickRoute(WorldRng(s), -1, BeatStrength.WEAK).klass == WorldRouteClass.FEEDER) weakFeeder++
            if (WorldNetwork.pickRoute(WorldRng(s), -1, BeatStrength.NONE).klass == WorldRouteClass.TRUNK) noneTrunk++
        }
        assertTrue("MID 应以支线为主（实测 $midRegional/2000）", midRegional > 1200)
        assertTrue("WEAK 应以毛细为主（实测 $weakFeeder/2000）", weakFeeder > 1200)
        assertTrue("NONE 应有可观的自发主干（实测 $noneTrunk/2000）", noneTrunk > 800)
    }

    @Test
    fun `focus city is used as an endpoint`() {
        val focus = indexOf("niuyue")
        var hits = 0
        for (seed in 1..500) {
            val r = WorldNetwork.pickRoute(WorldRng(seed.toUInt()), focus, BeatStrength.STRONG)
            if (r.from == focus || r.to == focus) hits++
        }
        assertTrue("焦点城市应被大量复用（实测 $hits/500）", hits > 200)
        // 非法焦点不得崩溃
        for (bad in intArrayOf(-1, 999, -100)) {
            val r = WorldNetwork.pickRoute(WorldRng(3u), bad, BeatStrength.MID)
            assertNotEquals(r.from, r.to)
        }
    }

    @Test
    fun `max active flights maps quality tiers and degrades with frame time`() {
        assertEquals(12, WorldNetwork.maxActiveFlights(0, 10f))
        assertEquals(22, WorldNetwork.maxActiveFlights(150, 10f))
        assertEquals(34, WorldNetwork.maxActiveFlights(350, 10f))
        assertEquals("未知档位按 HIGH", 34, WorldNetwork.maxActiveFlights(9999, 10f))
        // 33ms 必须严格低于 10ms
        assertTrue(
            "33ms 应严格低于 10ms",
            WorldNetwork.maxActiveFlights(350, 33f) < WorldNetwork.maxActiveFlights(350, 10f)
        )
        // 单调不增 + 值域
        var prev = Int.MAX_VALUE
        for (ms in 0..120 step 2) {
            val n = WorldNetwork.maxActiveFlights(350, ms.toFloat())
            assertTrue("frameMs=$ms 时 $n 超过了上限", n in 6..34)
            assertTrue("必须随 frameMs 单调不增（$ms 时 $n > $prev）", n <= prev)
            prev = n
        }
        // 极端卡顿时也保留下限
        assertEquals(6, WorldNetwork.maxActiveFlights(0, 5000f))
        assertEquals(6, WorldNetwork.maxActiveFlights(350, 5000f))
        // 20ms 是分界：19ms 不降，21ms 开始降（每超 4ms 扣 1 条）
        assertEquals(34, WorldNetwork.maxActiveFlights(350, 19f))
        assertEquals(33, WorldNetwork.maxActiveFlights(350, 21f))
        assertEquals(30, WorldNetwork.maxActiveFlights(350, 33f))
    }

    // ══ 工具 ═══════════════════════════════════════════════════

    private fun indexOf(pinyin: String): Int =
        WorldCities.ALL.indexOfFirst { it.pinyin == pinyin }.also {
            assertTrue("城市表里找不到 $pinyin", it >= 0)
        }

    /** 层级 [tier] 的城市数量（`WorldCities.countOfTier` 是私有的） */
    private fun countOfTier(tier: Int): Int =
        (0 until WorldCities.COUNT).count { WorldCities.tierOf(it) == tier }

    private fun wrap(deg: Float): Float {
        var x = deg % 360f
        if (x > 180f) x -= 360f
        if (x < -180f) x += 360f
        return x
    }

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) * 0.5f
    }

    /** 大圆距离（公里）→ 球心角（度） */
    private fun centralAngleDeg(km: Float): Double =
        km / WorldCities.EARTH_RADIUS_KM.toDouble() * 180.0 / Math.PI
}

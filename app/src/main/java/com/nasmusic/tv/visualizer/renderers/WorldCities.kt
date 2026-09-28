package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.visualizer.VisualizerRandom
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「世界」效果的城市定义。
 *
 * 一座城市 = 一个光点 + 一个可选的航线端点。渲染层只读本表的坐标与权重，
 * **不读** [WorldCity.name] / [WorldCity.pinyin] —— 那两个字段只用于单测报错信息
 * 与 KDoc，⛔ 永远不渲染到画面上（画面上的「城市」必须是纯光点，任何文字都会
 * 破坏海报感，且 32 个汉字在小光点下不可读）。
 *
 * ⛔ 零 Android 依赖：本文件与同包的 [WorldProjection] / [WorldTerminator] / [WorldNetwork]
 * 一样只做纯计算，因此可被普通 JVM 单测（无 Robolectric）覆盖。
 */
internal class WorldCity(
    /** 中文名，**仅调试用**，⛔ 不渲染 */
    val name: String,
    /** 拼音键，**仅调试用**，⛔ 不渲染 */
    val pinyin: String,
    /** 经度（度），东正西负，∈ [-180, 180] */
    val lon: Float,
    /** 纬度（度），北正南负，∈ [-90, 90] */
    val lat: Float,
    /** 层级 1..4；驱动光点大小/亮度、同时活跃航线数、随机抽中的概率 */
    val tier: Int,
    /** 年旅客吞吐量（百万人次）；仅用于 tier 内二次加权，不代表画面的其它含义 */
    val pax: Float
)

/**
 * 「世界」效果的城市表（32 座）与纯逻辑查询。
 *
 * ## 分层依据
 * 按**年航空旅客吞吐量**把 32 座城市划成 4 层（Tier1 8 座 / Tier2 8 座 /
 * Tier3 10 座 / Tier4 6 座）。层级是一切下游行为的唯一驱动量：
 *
 * | 层级 | 光点大小/亮度 | 同时活跃航线数 [activeFlightRange] | 航线等级 |
 * |---|---|---|---|
 * | Tier1 | 最大最亮 | 5..8 | 多为 TRUNK |
 * | Tier2 | 较大 | 3..5 | TRUNK / REGIONAL |
 * | Tier3 | 中等 | 1..3 | REGIONAL |
 * | Tier4 | 最小最暗 | 0..1 | FEEDER |
 *
 * ## 权重公式（[weightOf]）
 *
 * ```
 * weight = tierBase(tier) × paxFactor
 * tierBase : Tier1=8.0  Tier2=4.0  Tier3=2.0  Tier4=1.0     // 相邻层减半
 * paxFactor: clamp(pax / tierMeanPax(tier), 0.75, 1.20)      // 层内规模二次加权
 * ```
 *
 * - **tierBase 决定层级**（1 → 4 减半，Tier1 : Tier4 = 8 : 1），这是「主骨架」；
 * - **paxFactor 只在层内做微调**，钳在 [0.75, 1.20] ⇒ 层内最大/最小比恰为
 *   **1.6×**，即「规模差在光点上可见，但不至于把层级差盖掉」。
 *
 * 两条设计的实测值（由 `WorldLogicTest` 锁死）：
 * - 同层内 `paxFactor` 极值比 = 1.20 / 0.75 = **1.60**；
 * - **均值** Tier1 / Tier4 = 7.96 / 0.95 ≈ **8.4**（≈ 8，符合预期）；
 * - **极值** 最强 Tier1 / 最弱 Tier4 = 9.6 / 0.75 = **12.8**（= 8 × 1.6，
 *   这是 paxFactor 极值比必然带来的上界，两条要求在数学上无法同时精确成立，
 *   故本文件把「≈8」落实在**均值**上，极值比另行文档化）。
 *
 * 分层严格有序：实测 `min(Tier1)=6.00 > max(Tier2)=4.80 > min(Tier2)=3.26 >
 * max(Tier3)=2.40 > min(Tier3)=1.50 > max(Tier4)=1.20`。
 *
 * ## 随机源
 * 全部随机入口都**注入** [VisualizerRandom]（项目既有的零分配 LCG），不使用
 * `kotlin.random.Random`、不持有全局状态 ⇒ 同一种子必然复现同一串航线。
 *
 * ## 坐标微调：孟买 / 利马各向内 ~0.25°
 * 下面两座城市的**真实坐标**落在 [WorldMapData] 内嵌的 Natural Earth 110m 简化轮廓
 * **之外**（110m 的折线会「切」过这些海岸），于是光点画在海面上：
 *
 * | 城市 | 原坐标 | 简化轮廓的判定 | 实测外海距离 | 处理 |
 * |---|---|---|---|---|
 * | 孟买 | 72.87E, 19.09N | **在外**（COARSE + MEDIUM 档） | 16.5 km | → 73.13E（向内 0.26°） |
 * | 利马 | 77.11W, 12.02S | **在外**（MEDIUM 档） | 1.4 km | → 76.86W（向内 0.25°） |
 *
 * 判据与复核脚本：`logs_temp/world_map_gen/city_coast_check.py`
 * （直接解 `WorldMapData` 的编码串，对每座城市做「到最近海岸线段的距离 + even-odd 内外判定」，
 * 三档 LOD 都跑过）。移动量 ≈ 26–27 km，在**任何**出图比例下都小于 1 px（1920×1080
 * 上约 2.39 px/度 ⇒ 0.25° ≈ 0.6 px），而原来那点偏差在 MEDIUM 档是 4–5 px。
 *
 * 权衡：画面上**不渲染任何文字**，所以把坐标挪 26 km 造成的「数据不精确」**完全不可见**；
 * 反过来，一个画在海里的光点是**看得见**的。故取前者。
 * 纽约（1.3 km ≈ 0.03 px）、迪拜（12.9 km）、新加坡（5.5 km）在外海距离上更小，
 * 按同一把尺子衡量属「不可见」，故保持原坐标不动。
 */
internal object WorldCities {

    /**
     * 全部 32 座城市，**按层级分组**排列（Tier1 8 → Tier2 8 → Tier3 10 → Tier4 6）。
     * 顺序即「表下标」，被 [weightOf] / [distanceKm] / [nearestHub] 以索引形式消费；
     * ⚠️ 重排会改变随机序列与测试断言，新增城市请**追加到所属层的末尾**。
     */
    val ALL: Array<WorldCity> = arrayOf(
        // ── Tier 1（8 座）：全球骨干枢纽 ────────────────────────────
        WorldCity("伦敦", "lundun", -0.13f, 51.50f, 1, 84.48f),
        WorldCity("纽约", "niuyue", -74.01f, 40.71f, 1, 142.00f),
        WorldCity("上海", "shanghai", 121.47f, 31.23f, 1, 135.00f),
        WorldCity("东京", "dongjing", 139.78f, 35.55f, 1, 91.68f),
        WorldCity("伊斯坦布尔", "yisitanbuer", 28.74f, 41.26f, 1, 132.00f),
        WorldCity("北京", "beijing", 116.58f, 40.08f, 1, 124.00f),
        WorldCity("迪拜", "dibai", 55.36f, 25.25f, 1, 95.20f),
        WorldCity("亚特兰大", "yatelanda", -84.43f, 33.64f, 1, 106.00f),

        // ── Tier 2（8 座）：区域枢纽 ──────────────────────────────
        WorldCity("巴黎", "bali", 2.55f, 49.01f, 2, 72.03f),
        WorldCity("芝加哥", "zhijiage", -87.90f, 41.98f, 2, 104.00f),
        WorldCity("达拉斯", "dalasi", -97.04f, 32.90f, 2, 103.00f),
        WorldCity("洛杉矶", "luoshanji", -118.41f, 33.94f, 2, 102.00f),
        WorldCity("首尔", "shouer", 126.44f, 37.46f, 2, 74.07f),
        WorldCity("新加坡", "xinjiapo", 103.99f, 1.36f, 2, 69.98f),
        WorldCity("广州", "guangzhou", 113.31f, 23.39f, 2, 83.58f),
        WorldCity("德里", "deli", 77.10f, 28.56f, 2, 78.15f),

        // ── Tier 3（10 座）：次级节点 ─────────────────────────────
        WorldCity("法兰克福", "fulankefu", 8.56f, 50.04f, 3, 63.20f),
        WorldCity("莫斯科", "mosike", 37.41f, 55.97f, 3, 60.00f),
        // ⚠️ 孟买偏内 0.26°（≈27 km，见下方「坐标微调」）
        WorldCity("孟买", "mumbai", 73.13f, 19.09f, 3, 60.00f),
        WorldCity("圣保罗", "shengbaoluo", -46.47f, -23.43f, 3, 50.00f),
        WorldCity("墨西哥城", "moxigecheng", -99.07f, 19.44f, 3, 50.00f),
        WorldCity("多伦多", "duolunduo", -79.63f, 43.68f, 3, 50.00f),
        WorldCity("曼谷", "mangu", 100.75f, 13.69f, 3, 70.00f),
        WorldCity("成都", "chengdu", 103.95f, 30.58f, 3, 85.00f),
        WorldCity("迈阿密", "maiami", -80.29f, 25.79f, 3, 80.00f),
        WorldCity("悉尼", "xinni", 151.18f, -33.94f, 3, 40.00f),

        // ── Tier 4（6 座）：末端节点 ──────────────────────────────
        WorldCity("开罗", "kailuo", 31.41f, 30.11f, 4, 40.00f),
        WorldCity("约翰内斯堡", "yuehanneisibao", 28.24f, -26.13f, 4, 21.00f),
        WorldCity("内罗毕", "neiluobi", 36.93f, -1.32f, 4, 12.00f),
        WorldCity("布宜诺斯艾利斯", "buiyinuosiailisi", -58.38f, -34.60f, 4, 30.00f),
        // ⚠️ 利马偏内 0.25°（≈26 km，见下方「坐标微调」）
        WorldCity("利马", "lima", -76.86f, -12.02f, 4, 20.00f),
        WorldCity("奥克兰", "aokelan", 174.79f, -37.01f, 4, 20.00f)
    )

    /** 城市总数；与 [ALL].size 由 `WorldLogicTest` 锁死 */
    const val COUNT = 32

    /** 层级数（Tier 取值 1..4） */
    const val TIER_COUNT = 4

    // ── 权重表（init 期一次算好，查询零开销）────────────────────────

    /** tierBase：1..4 → 8 / 4 / 2 / 1（下标 = tier - 1） */
    private val TIER_BASE = floatArrayOf(8f, 4f, 2f, 1f)

    /** 每层的 pax 均值，init 期算好 */
    private val TIER_MEAN_PAX = FloatArray(TIER_COUNT)

    /** 权重上限（layer 内 pax 二次加权的钳位上界） */
    private const val PAX_FACTOR_MAX = 1.20f

    /** 权重下限（与 [PAX_FACTOR_MAX] 之比恰为 1.6） */
    private const val PAX_FACTOR_MIN = 0.75f

    /** 每座城市归一化后的权重（未再整体缩放，量纲即「相对份数」） */
    private val WEIGHT = FloatArray(COUNT)

    /** [WEIGHT] 的前缀和，[pickWeighted] 直接线性扫描用 */
    private val WEIGHT_CUM = FloatArray(COUNT)

    init {
        for (c in ALL) TIER_MEAN_PAX[c.tier - 1] += c.pax
        for (k in 0 until TIER_COUNT) TIER_MEAN_PAX[k] /= countOfTier(k + 1)
        var acc = 0f
        for (i in 0 until COUNT) {
            val c = ALL[i]
            val mean = TIER_MEAN_PAX[c.tier - 1]
            val factor = if (mean <= 0f) 1f else (c.pax / mean).coerceIn(PAX_FACTOR_MIN, PAX_FACTOR_MAX)
            WEIGHT[i] = TIER_BASE[c.tier - 1] * factor
            acc += WEIGHT[i]
            WEIGHT_CUM[i] = acc
        }
    }

    private fun countOfTier(tier: Int): Float {
        var n = 0
        for (c in ALL) if (c.tier == tier) n++
        return if (n == 0) 1f else n.toFloat()
    }

    // ── 索引访问（越界返回安全默认值，绝不在绘制期抛异常）────────────

    /** 层级 1..4；`index` 越界返回 4（最弱层，视觉上最不显眼 ⇒ 故障最轻） */
    fun tierOf(index: Int): Int = if (index in 0 until COUNT) ALL[index].tier else 4

    /** 年旅客吞吐量（百万人次）；`index` 越界返回 0 */
    fun paxOf(index: Int): Float = if (index in 0 until COUNT) ALL[index].pax else 0f

    /**
     * 层级归一化权重（Tier1 最高、Tier4 最低），公式见 [WorldCities] 的类 KDoc。
     *
     * = `tierBase(tier) × clamp(pax / tierMeanPax(tier), 0.75, 1.20)`。
     * `index` 越界返回 0（等价于「该城市不可被抽中」）。
     */
    fun weightOf(index: Int): Float = if (index in 0 until COUNT) WEIGHT[index] else 0f

    // ── 随机 ───────────────────────────────────────────────────

    /** [pickWeighted] 的最大重抽次数 */
    private const val PICK_ATTEMPTS = 12

    /**
     * 按 [weightOf] 加权随机抽一座城市，**永不**返回 [exclude] 本身。
     *
     * 实现为「前缀和 + 线性扫描 + 有界拒绝采样」：抽中的城市恰为 `exclude` 时重抽，
     * 最多 [PICK_ATTEMPTS] 次；仍失败则退化为「表中第一个不等于 exclude 的下标」，
     * 保证返回值的**完备性**（32 座城市，抽中任一非排除项的概率 > 0）。
     *
     * 拒绝采样等价于「把 exclude 从候选里去掉后重新归一化」—— 正是期望语义。
     * 且**零分配**（不构造 `IntRange`、不装箱）。
     *
     * @param exclude 需要排除的城市下标；传 `-1` 表示不排除任何城市
     */
    fun pickWeighted(rnd: VisualizerRandom, exclude: Int): Int {
        val total = WEIGHT_CUM[COUNT - 1]
        repeat(PICK_ATTEMPTS) {
            val target = rnd.next() * total
            var i = 0
            while (i < COUNT - 1 && WEIGHT_CUM[i] < target) i++
            if (i != exclude) return i
        }
        // 有界拒绝采样失败（极端种子下的兜底，不影响常态分布）
        var j = 0
        while (j == exclude && j < COUNT) j++
        return j.coerceIn(0, COUNT - 1)
    }

    // ── 地理 ───────────────────────────────────────────────────

    /**
     * 城市 [i] 与 [j] 之间的**大圆**距离（公里）。
     *
     * 用 haversine 而非平面距离：城市对跨经度最大可达 180°，且
     * 「悉尼 ↔ 奥克兰」「布宜诺斯艾利斯 ↔ 利马」这类南美/大洋洲短线必须准。
     * `R = 6371.0088 km`（WGS84 平均半径，IUGG 推荐值）。
     *
     * 纬度边界已由 [WorldProjection] 的 Robinson 投影消化（本函数只管球面距离），
     * 因此这里对纬度不做任何压缩。
     */
    fun distanceKm(i: Int, j: Int): Float {
        if (i == j) return 0f
        val a = if (i in 0 until COUNT) ALL[i] else return Float.NaN
        val b = if (j in 0 until COUNT) ALL[j] else return Float.NaN
        val lat1 = Math.toRadians(a.lat.toDouble())
        val lat2 = Math.toRadians(b.lat.toDouble())
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon.toDouble() - a.lon.toDouble())
        val h = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1) * cos(lat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
        // asin 的定义域是 [-1, 1]；浮点误差可能越界，钳一下
        val c = 2.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        return (EARTH_RADIUS_KM * c).toFloat()
    }

    /** 地球平均半径（公里，WGS84 / IUGG） */
    const val EARTH_RADIUS_KM = 6371.0088f

    /**
     * 城市 [i] 最近的、层级 ≤ [maxTier] 的**枢纽**城市下标。
     *
     * 用途：`WorldNetwork` 里支线/毛细只连附近枢纽 —— 这是「欧美/东亚/中东密、
     * 非洲/南美疏」这一密度观感的**唯一来源**（权重只决定城市亮不亮，
     * 不决定航线跨不跨洲）。
     *
     * @param maxTier 1..4
     * @return 最近城市的下标；**无解时返回 [i] 自身**（唯一的合理解：调用方应
     *   把它当作「没有可达枢纽」而跳过该航线）
     */
    fun nearestHub(i: Int, maxTier: Int): Int = nearestAmong(i, 1, maxTier, i)

    /**
     * 城市 [i] 最近的 [k] 座「层级 ≤ [maxTier]」的枢纽，按**距离升序**写入 [out]。
     *
     * ## 为什么需要 k 个
     * 90 % 的叶端航线走 [nearestHub]（最近那座），剩下 10 % 走本函数取**第 2 近**那座。
     * ⛔ 那 10 % **曾经**退回「全表 Tier1..2 加权随机」，于是叶端会伸手去另一个大洲 ——
     * 布宜诺斯艾利斯 ↔ 巴黎 11,072 km、约翰内斯堡 ↔ 纽约 11,855 km 就是这么来的，
     * 而这两座叶端自己最近的 Tier≤3 只有 1,695 km / 6,412 km。
     * 「10 % 这个比例本身不是缺陷，**它的目标是全球随机的这一点才是**」——
     * 用第 2 近替代就保住了变化，代价是零。
     *
     * ## 契约（与 [nearestOfTier] 的 k 元版本完全同构）
     * - 候选 = `tier ≤ maxTier` 且 `!= i` 的城市；
     * - 排序键 `(距离, 下标)`，比较用**严格** `<` 且按下标升序遍历
     *   ⇒ 距离并列时**下标小者在前**，与随机源无关（同输入必然同输出）；
     * - 实现为「重复 k 次『取最近的那个还没被取的』」，不排序、不分配；
     * - [out] 由调用方提供（长度须 ≥ [k]），本函数**不**返回 `-1`，只返回写入个数。
     *
     * @param k 要取几座；`<= 0` 或缓冲不足时按缓冲长度截断
     * @return 实际写入 [out] 的个数（不足 k 时即该层级的可用城市数）
     */
    fun nearestHubs(i: Int, maxTier: Int, k: Int, out: IntArray): Int =
        fillNearest(i, 1, maxTier, k, out)

    /**
     * 城市 [i] 最近的、层级**恰为** [target] 的城市下标。
     *
     * 与 [nearestHub] 的区别：那边是「层级 ≤ [maxTier] 的枢纽」（叶端连中继/枢纽用），
     * 这边是「层级**正好**是 [target]」（枢纽挂末端用）。
     *
     * 用途：`WorldNetwork` 里「枢纽 → Tier4 末端」这一路的地理就近约束。
     * ⛔ **不能**用「全表 Tier4 加权随机」代替 —— 32 座城里 Tier4 只有 6 座且分布极不均匀
     * （南美/非洲各 1~2 座，东南亚一座都没有），从多伦多随机抽会抽到内罗毕（12,200 km），
     * 凭空造出上万公里的「毛细航线」，与「只与附近枢纽或少数城市连线」直接矛盾。
     *
     * @param target 1..[TIER_COUNT]；越界返回 `-1`
     * @return 最近的下标；表内没有该层级时返回 `-1`（调用方须自己兜底）
     */
    fun nearestOfTier(i: Int, target: Int): Int {
        if (target < 1 || target > TIER_COUNT) return -1
        return nearestAmong(i, target, target, -1)
    }

    /**
     * 城市 [i] 最近的 [k] 座「层级恰为 [target]」的城市，按**距离升序**写入 [out]。
     *
     * 为什么要 k 个而不是 1 个：每次都连最近的那一座，图案会变得机械
     * （同一条线反复出现、种子可预测）。抽最近的几座既能保住地理就近，
     * 又保留了变化 —— 调用方用 [out] 里的下标做**带权抽样**即可。
     *
     * 排序键、确定性纪律与缓冲约定见 [nearestHubs]（两者是同构的，
     * 只是一个把层级钉死为 `target`、另一个放宽成「≤ maxTier」）。
     *
     * @param k 要取几个；`<= 0` 或缓冲不足时按缓冲长度截断
     * @param out 调用方提供的缓冲，长度须 ≥ [k]（本项目用 3）
     * @return 实际写入 [out] 的个数（不足 k 时即该层级的城市总数）
     */
    fun nearestOfTier(i: Int, target: Int, k: Int, out: IntArray): Int =
        fillNearest(i, target, target, k, out)

    /** [nearestHub] / [nearestOfTier] 单参版共用的单元素搜索 */
    private fun nearestAmong(i: Int, minTier: Int, maxTier: Int, fallback: Int): Int {
        if (i !in 0 until COUNT) return fallback
        var best = fallback
        var bestD = Float.MAX_VALUE
        for (j in 0 until COUNT) {
            if (j == i) continue
            val t = ALL[j].tier
            if (t < minTier || t > maxTier) continue
            val d = distanceKm(i, j)
            if (d < bestD) {
                bestD = d
                best = j
            }
        }
        return best
    }

    /** [nearestHubs] / [nearestOfTier] 的 k 元版本共用的「重复 k 次取最近未取」搜索 */
    private fun fillNearest(i: Int, minTier: Int, maxTier: Int, k: Int, out: IntArray): Int {
        if (i !in 0 until COUNT) return 0
        val limit = if (k < out.size) k else out.size
        if (limit <= 0) return 0
        var filled = 0
        while (filled < limit) {
            var best = -1
            var bestD = Float.MAX_VALUE
            for (j in 0 until COUNT) {
                if (j == i) continue
                val t = ALL[j].tier
                if (t < minTier || t > maxTier) continue
                var taken = false
                for (p in 0 until filled) {
                    if (out[p] == j) {
                        taken = true
                        break
                    }
                }
                if (taken) continue
                val d = distanceKm(i, j)
                if (d < bestD) {
                    bestD = d
                    best = j
                }
            }
            if (best < 0) break
            out[filled++] = best
        }
        return filled
    }

    /**
     * 该层级建议的**同时活跃**航线数区间（闭区间，含端点）。
     *
     * ```
     * Tier1 5..8   Tier2 3..5   Tier3 1..3   Tier4 0..1
     * ```
     *
     * ⚠️ [IntRange] 在 Kotlin 里是**步长 1** 的闭区间，`5..8` 有 4 个值；
     * 若要「在 5~8 条之间随机取整数」，用
     * `range.first + rnd.nextIndex(range.count())`（[IntRange.count]）或
     * `range.first + (rnd.next() * range.count()).toInt()`，
     * **不要**直接用 `rnd.nextIndex(range.last + 1) - range.first` 之外的写法。
     *
     * 层级越低允许的并发航线越少 —— Tier4 的光点很小，2 条以上支线叠上去就糊成一团。
     * 非法层级返回空区间。
     */
    fun activeFlightRange(tier: Int): IntRange = when (tier) {
        1 -> 5..8
        2 -> 3..5
        3 -> 1..3
        4 -> 0..1
        else -> IntRange.EMPTY
    }

    /** 调试用：下标 → 「中文名(拼音)」，⛔ 不进画面 */
    fun labelOf(index: Int): String {
        val c = if (index in 0 until COUNT) ALL[index] else return "?"
        return "${c.name}(${c.pinyin})"
    }
}

package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.visualizer.VisualizerRandom
import kotlin.math.ceil
import kotlin.math.exp

/**
 * 「世界」效果用的随机源。
 *
 * ⛔ **不是**新写的随机数发生器，而是项目既有的零分配 LCG（`next()` ∈ [0,1)、
 * `nextIndex(bound)` 不构造 `IntRange`、不用 `kotlin.random.Random`）的别名。
 * 别名的理由：
 * - 「随机序列只有一处」是本项目 `VisualizerRandom` KDoc 里明确的约定
 *   （`PhotoTransitionPicker` / `PhotoSourceAggregator` 都因此被统一到同一处）；
 * - 再写一个 LCG 等于复制一份状态机，两处会各自漂移；
 * - 种子可注入 ⇒ 同种子必然复现同一串航线，可单测。
 *
 * 构造签名是 [VisualizerRandom] 的 `VisualizerRandom(seed: UInt)`，
 * 即 `WorldRng(12345u)`（`seed = 0u` 会被兜底成 1u）。
 */
internal typealias WorldRng = VisualizerRandom

/**
 * 航线等级：**主干 + 支线 + 毛细**三层。
 *
 * 枚举的**声明顺序即强弱顺序**（ordinal 0 最粗 → 2 最细），
 * 供 [WorldNetwork.arcTable] / [WorldNetwork.brightnessTable] 递减取值与渲染层按
 * ordinal 直接建表使用。
 *
 * ⛔ **[WorldNetwork.pickRoute] 绝不按拍点覆写这个等级**。等级**只**由
 * `classify(起点 tier, 终点 tier)` 决定（见该函数 KDoc）。
 * 历史教训：曾经用 `maxOf(拍点想要的等级, 两端实际等级)` 覆写，而 `maxOf` 取的是
 * 枚举 ordinal 的较大者 = **更细**的那个，于是 WEAK 拍点会把
 * 「亚特兰大(T1) → 法兰克福(T3)」这种跨大西洋支线画成最细的 `FEEDER` ——
 * 恰好与本枚举自己的表格（`含 Tier3 = REGIONAL`）矛盾。
 */
internal enum class WorldRouteClass {
    /** 主干：Tier1–Tier1 或 Tier1–Tier2。更粗、更亮、生命周期更长 */
    TRUNK,

    /** 支线：涉及 Tier3。更细、更短命 */
    REGIONAL,

    /** 毛细：涉及 Tier4。最细、最短命 */
    FEEDER
}

/**
 * 一条航线的**不可变**描述（不含动画进度 —— 进度由渲染层的航线池持有）。
 *
 * @param from 起点城市下标（[WorldCities.ALL] 的索引）
 * @param to 终点城市下标，⛔ 恒 `!= from`
 * @param klass 航线等级
 * @param weight 两端 tier 权重的乘积（已归一化到 [0,1]）—— 帧率不足时**先砍它最小的那条**
 * @param arcBoost 弧高倍率：主干更高（视觉上更靠前/更有纵深），支线更贴近地表
 * @param trunkBias 额外亮度倍率
 */
internal class WorldRouteSpec(
    val from: Int,
    val to: Int,
    val klass: WorldRouteClass,
    val weight: Float,
    val arcBoost: Float,
    val trunkBias: Float
)

/** 节拍强弱分档。分档只用于**挑选端点对**（见 [WorldNetwork.pickRoute]）；涟漪强度用 [BeatClassifier.lastStrength] 的连续值。 */
internal enum class BeatStrength { NONE, WEAK, MID, STRONG }

/**
 * 「世界」效果的航线网络模型 —— 「主干 + 支线」层级的全部判定逻辑。
 *
 * ## 层级到航线的映射（**唯一权威**，即 [classify]）
 * ```
 * 两端都 ≤ Tier2            → TRUNK      T1–T1 / T1–T2 / T2–T2
 * 任意一端 = Tier3（无 Tier4）→ REGIONAL
 * 任意一端 = Tier4          → FEEDER     ← Tier4 优先于 Tier3
 * ```
 *
 * ⛔ **等级是「端点驱动」的，拍点无权覆写。** [pickRoute] 恒有
 * `spec.klass == classify(tierOf(from), tierOf(to))`。
 *
 * ## 拍点的作用：**选哪一对端点**（不是「画多粗」）
 * | 拍点 | 采样策略 | 结果等级（无焦点城市时） |
 * |---|---|---|
 * | STRONG | 两端都取 Tier1..2 | 恒 `TRUNK` |
 * | MID | 起点取恰好 Tier3，终点 Tier1..3 | 恒 `REGIONAL` |
 * | WEAK | 起点取恰好 Tier4，终点 Tier1..3 | 恒 `FEEDER` |
 * | NONE | 全部 32 座按权重抽，终点 Tier1..3 | 由抽到的层级对决定 |
 *
 * 这正是原始规格（「强拍：在 Tier 1 城市之间生成主干航线」/「中拍：生成一条 Tier 3
 * 城市之间的支线航线」）。**枢纽↔枢纽的航线在任何拍点下都不会被削细**。
 *
 * ## 区域密度（本文件存在的最主要理由）
 * 权重只决定「城市亮不亮」，**不决定航线跨不跨洲**。要让密度读出
 * 「欧美 / 东亚 / 中东密，非洲 / 南美疏」，靠的是 [pickPeer] 的两条地理就近规则：
 * - **叶端 → 枢纽/中继**：终点取 [WorldCities.nearestHubs] 给出的最近 [HUB_CANDIDATES]
 *   座 Tier ≤ 3（[HUB_BIAS_FEEDER] / [HUB_BIAS_REGIONAL] 决定取第 1 近还是第 2 近）；
 * - **枢纽 → 末端**：终点取 [WorldCities.nearestOfTier] 给出的最近 [LEAF_CANDIDATES]
 *   座 Tier4（见下节）。
 *
 * **枢纽 ↔ 枢纽则刻意不设就近** —— 主干航线天然跨洲长程，那正是「主干」的视觉来源。
 *
 * ## 距离上限（[MAX_ROUTE_KM]）
 * 抽到的终点若超出 [MAX_ROUTE_KM]，[clampWithinRange] 会换成**地理上最近的合格城市**
 * （优先保住「叶端连枢纽/中继」的形状，其次保住等级）。这杜绝了「近对跖点航线」
 * （大圆在此数值病态、弧线会俯冲穿过南极，且对扰动不稳定 —— 对一个必须同种子复现的
 * 视觉效果是硬伤）。
 * 实测（5 个焦点 × 4 拍点 × 4000 种子 = 80,000 条）：上限生效前有 **1.10%** 的航线
 * 越界，最长 19,640 km；生效后 **0%**，最长 14,994 km。
 *
 * ## 「枢纽 → 末端」的地理就近（[LEAF_CANDIDATES]）
 * 规格原文：「支线航线：涉及 Tier 3、Tier 4 城市的航线，更细、更短命，**只与附近枢纽
 * 或少数城市连线**」。而 Tier4 全表只有 6 座且分布极不均匀，若在「起点是 Tier1..3、
 * 想要 FEEDER」时直接从 6 座里加权随机，就会造出上万公里的「毛细航线」——
 * 既违反上面那句话，又因为**又长又细**在视觉上被读成主干，
 * 「主干 + 支线」的层级对比直接消失。
 *
 * 故这一路的终点从 **最近 [LEAF_CANDIDATES]（=3）座 Tier4** 里按
 * [LEAF_WEIGHTS]（0.50 / 0.30 / 0.20）抽样。
 * 实测中位距离（4000 种子，WEAK 拍）：多伦多 8,967 → **6,200 km**、
 * 伦敦 3,516 → **3,516 km**（本就近）、约翰内斯堡 6,412 → **6,412 km**（本就近）。
 *
 * ⛔ **刻意不追求「统一更短」。** 本表在东南亚与北美东部**没有** Tier4 城市，
 * 所以那里的就近毛细航线天然是长的（东京最近的末端是奥克兰 8,841 km）。
 * 「欧美、东亚、中东密集，非洲、南美稀疏」的密度对比**依赖**这种真实的不均匀 ——
 * 稀疏区域就该只有少数又长又细的线，那正是可视化本身。
 * 所以这里**不叠加任何距离上限**：[MAX_ROUTE_KM] 是与本规则正交的现实约束。
 *
 * ## 「叶端 → 枢纽」的地理就近（[HUB_CANDIDATES]）
 * 与上一节对称的另一半。同一个病：90 % 走 [WorldCities.nearestHub] 是就近的，
 * 但**剩下 10 % 原来退回「全表 Tier1..2 加权随机」** —— 叶端因此会伸手去另一个大洲：
 * 布宜诺斯艾利斯 ↔ 巴黎 11,072 km、约翰内斯堡 ↔ 纽约 11,855 km，
 * 而这两座叶端自己最近的 Tier≤3 只有 1,695 km / 6,412 km。
 * 「10 % 这个比例本身**不是**缺陷，**它的目标是全球随机的这一点才是**」。
 * 故那 10 % 改成取 [WorldCities.nearestHubs] 给出的**第 2 近**，
 * 比例（[HUB_BIAS_FEEDER] = 0.90）一字未改，变化来源反而从 1 个变成 2 个。
 *
 * ## ⚠️ 剩余残留：**由枢纽发起的毛细航线**仍可很长
 * 「叶端不再远求枢纽」已双向关闭（两个方向都有就近窗口，见 [isLocalShape]），
 * 但**反过来**由枢纽主动挑末端时，落点未必是「该末端自己觉得近的那座」。
 * 例：纽约的最近 3 座 Tier4 含利马（8,526 km）与布宜诺斯艾利斯（8,059 km），
 * 而这两座自己最近的 Tier≤3 是圣保罗/迈阿密 —— 纽约不在其中。
 *
 * 要让不变量在**两个方向上统一**（「终点也必须回选我」），需要互选（mutual k-NN）：
 * 实测 `hub 窗口 3 / leaf 窗口 2` 只剩 **12 对**、**26 个枢纽里只剩 7 个**还有任何末端
 * （伦敦、纽约、上海、东京、北京、亚特兰大、巴黎、芝加哥、达拉斯、洛杉矶、首尔、
 * 广州、德里、法兰克福、莫斯科、墨西哥城、多伦多、曼谷、成都的毛细航线会**整体消失**），
 * 最长存活航线仍是 8,409 km —— 即**没变短，只是变没了**，与「稀疏区域就该只有少数
 * 又长又细的线」相悖。放宽到 leaf 窗口 4 才有 24 对 / 14 个枢纽，仍远小于不互选的 78 对 / 26 个。
 * 故**本次不采用互选**，把取舍留给所有者；两条规则各自的单测都锁死，
 * 互选若要做，只需在 [pickPeer] ② 的候选上加一次反向判定。
 *
 * ## ⚠️ 刻意的取舍：**Tier4 焦点城市压过拍点**
 * 焦点是 UI 状态（用户正在看的那座城），优先级高于节奏暗示。
 * 后果是：**焦点落在 Tier4 城市时，强拍也会得到一条 `FEEDER`**，
 * 也就是「聚焦末端城市会削弱强拍的视觉响应」（主干线不出现）。
 * 这是「等级纯由端点决定」这条不变量的**必然**结果，也是刻意的 ——
 * 唯一能避免「跨大西洋的毛细航线」的办法就是让等级跟着端点走。
 * ⛔ 不要为了让强拍「看起来更响」而在这里放水：那正是当初 `maxOf` 覆写等级的老 bug。
 *
 * ⛔ 纯逻辑：不碰 Android，不读系统时间，不持有跨帧可变状态（除 [BeatClassifier]）。
 */
internal object WorldNetwork {

    // ── 参数 ───────────────────────────────────────────────────

    /** tier → 权重基数（1..4 → 8 / 4 / 2 / 1），与 [WorldCities.weightOf] 的 tierBase 同源 */
    private val TIER_WEIGHT = floatArrayOf(8f, 4f, 2f, 1f)

    /** 权重归一化分母 = 8 × 8（最强主干） */
    private const val WEIGHT_NORM = 64f

    /** 主干弧高倍率基线 1.0；支线 / 毛细压低 ⇒ 弧更贴近地表（"贴地飞行"） */
    private fun arcTable(k: WorldRouteClass): Float = when (k) {
        WorldRouteClass.TRUNK -> 1.00f
        WorldRouteClass.REGIONAL -> 0.62f
        WorldRouteClass.FEEDER -> 0.38f
    }

    /** 额外亮度倍率 */
    private fun brightnessTable(k: WorldRouteClass): Float = when (k) {
        WorldRouteClass.TRUNK -> 1.00f
        WorldRouteClass.REGIONAL -> 0.72f
        WorldRouteClass.FEEDER -> 0.50f
    }

    /** 叶端「只连最近枢纽」的概率，按等级递减 */
    private const val HUB_BIAS_TRUNK = 0.50f
    private const val HUB_BIAS_REGIONAL = 0.75f
    private const val HUB_BIAS_FEEDER = 0.90f

    /** 有焦点城市时，把焦点城市当端点的概率 */
    private const val FOCUS_BIAS = 0.60f

    /** MID → REGIONAL 的概率（否则退回 TRUNK） */
    private const val PREFER_REGIONAL = 0.70f

    /** WEAK → FEEDER 的概率（否则退回 REGIONAL） */
    private const val PREFER_FEEDER = 0.70f

    /** [pickByTier] 的最大重抽次数（单次命中率 ≥ 0.79，16 次几乎必中） */
    private const val TIER_PICK_ATTEMPTS = 16

    /**
     * 生成航线的**最大大圆距离**（公里）—— [pickRoute] 的硬性后置条件。
     *
     * ## 为什么是这个数
     * 现实中最长的不停站直飞约 15,000–15,500 km（新加坡↔洛杉矶 15,700、
     * 迪拜↔洛杉矶 13,400），所以 15,000 km 是一条**来自现实的界**，不是随手取的整数。
     * 换算成球心角：`15000 / 6371.0088 = 2.3546 rad ≈ **134.9°**`。
     *
     * ## 越过界会怎样（不只是「难看」）
     * 1. **画面**：近对跖点的大圆在球面上数值病态，弧线会俯冲到 ~58°S，
     *    从地图下方兜一圈再上来 —— 诊断预览里那条穿过南极洲的橙色弧就是这么来的。
     * 2. **语义**：19,640 km 布宜诺斯艾利斯→上海曾被标成 `FEEDER`，
     *    与本文件「毛细航线天然短」的不变量直接矛盾。
     * 3. **稳定性**：近对跖点对扰动极敏感，微小的坐标/舍入变化就会翻转弧线绕地球的
     *    方向 —— 对一个**必须同种子复现**的视觉效果是硬伤。
     *
     * @see clampWithinRange
     */
    const val MAX_ROUTE_KM = 15000f

    /** 终点的「无偏好」层级区间：Tier1..3（叶端不会被抽成对端） */
    private val PEER_TIERS_ANY = 1..3

    /** 终点必须恰好是 Tier3（想让起点 Tier1/2 参与并凑出 REGIONAL） */
    private val PEER_TIERS_T3 = 3..3

    /** 终点必须恰好是 Tier4（想让起点 Tier1..3 参与并凑出 FEEDER） */
    private val PEER_TIERS_T4 = 4..4

    /**
     * 「枢纽 → 末端」抽样时，从**最近这么多座** Tier4 里挑。
     *
     * 取 3 而不是 1：每次都连最近的那一座，图案会变得机械（同一条线反复出现、
     * 种子近乎可预测）。取 3 座既保住地理就近，又留下变化。
     * ⛔ 不取 4 及以上：实测第 4 座开始明显远离（东京第 3 座 11,253 km、第 4 座 13,523 km），
     * 就近感会塌掉。
     */
    const val LEAF_CANDIDATES = 3

    /**
     * 「叶端 → 枢纽」抽样时，从**最近这么多座** Tier ≤ 3 里挑。
     *
     * 90 % 走第 1 近（[WorldCities.nearestHub]），10 % 走第 2 近。
     * ⛔ 那 10 % **曾经**退回「全表 Tier1..2 加权随机」，叶端会伸手去另一个大洲 ——
     * 布宜诺斯艾利斯 ↔ 巴黎 11,072 km、约翰内斯堡 ↔ 纽约 11,855 km，
     * 而这两座叶端自己最近的 Tier≤3 只有 1,695 km / 6,412 km。
     * 「10 % 这个比例不是缺陷，**它的目标是全球随机的这一点才是**」——
     * 换成第 2 近就保住了变化，代价为零。
     * ⛔ 不取 3 及以上：实测叶端第 3 近普遍已经跨洲（曼谷 8,997 km、悉尼 11,026 km），
     * 再放宽就近感就没了。实测每座叶端的第 1、2 近都**在 [MAX_ROUTE_KM] 之内**，
     * 所以这一路不需要逃生口。
     */
    const val HUB_CANDIDATES = 2

    /** 「叶端 → 枢纽」的就近窗口上界：Tier ≤ [LOCAL_HUB_MAX_TIER]（Tier3 能当 Tier4 的中继） */
    private const val LOCAL_HUB_MAX_TIER = 3

    /**
     * [LEAF_CANDIDATES] 座末端城市的抽样权重，**越靠前越优先**（下标即最近次序）。
     *
     * ```
     * 最近那座 0.50   第 2 座 0.30   第 3 座 0.20
     * ```
     * 为什么不给均匀权重：均匀抽样下中位距离落在**第 2 近**那座
     * （多伦多 = 布宜诺斯艾利斯 8,967 km，与修复前的随机值几乎一样，等于没修）。
     * 给头部分额 0.50 让中位数落在**最近**那座（多伦多 = 利马 6,200 km），
     * 同时仍留 50% 的概率给另外两座，图案不至于僵死。
     * 三项之和 = 1.0 ⇒ 已归一化，不需要额外除法。
     */
    private val LEAF_WEIGHTS = floatArrayOf(0.50f, 0.30f, 0.20f)

    /** [clampWithinRange] 候选过滤的三档偏好，数字越小越优先（见 [nearestWithinRange]） */
    private const val CAND_ANY = 0
    private const val CAND_CLASS = 1
    private const val CAND_SHAPE = 2

    // ── 分类与量化 ─────────────────────────────────────────────

    /**
     * 按两端 tier 分类 —— **航线等级的唯一权威**。
     *
     * ```
     * 两端都 ≤ Tier2            → TRUNK
     * 任意一端 = Tier3（无 Tier4）→ REGIONAL
     * 任意一端 = Tier4          → FEEDER      ← Tier4 优先于 Tier3
     * ```
     *
     * 非法 tier（≤0 或 >4）按 Tier4 处理 ⇒ 结果是 FEEDER（最细），最保守。
     *
     * ⛔ [pickRoute] 里**不允许**在结果之上再做 `maxOf` / `minOf` 之类的覆写。
     * 拍点只影响「抽哪一对端点」（[peerTierRange]），等级永远等于本函数。
     */
    fun classify(fromTier: Int, toTier: Int): WorldRouteClass = when {
        maxOf(fromTier, toTier) >= 4 -> WorldRouteClass.FEEDER
        maxOf(fromTier, toTier) == 3 -> WorldRouteClass.REGIONAL
        else -> WorldRouteClass.TRUNK
    }

    /**
     * 航线粗细 / 尺寸 = 两端 tier 权重乘积（已归一化到 [0,1]）。
     *
     * = `(tierBase(fromTier) × tierBase(toTier)) / 64`，其中 tierBase = 8/4/2/1。
     * 自变量只有 tier ⇒ 纯查表、零分配，且对两端**对称**。
     *
     * 实测层级脊：T1–T1 = 1.0 > T1–T2 = 0.5 > T1–T3 = 0.25 > T1–T4 = 0.125。
     * ⚠️ 跨等级的两个极值**不可比**（T2–T2 = T1–T3 = 0.25，TRUNK 里最细的
     * 与 REGIONAL 里最粗的等权；T3–T3 = 0.0625 < T1–T4 = 0.125，REGIONAL 里最细的
     * 反而比 FEEDER 里最粗的还细）—— 这是「纯乘积」规则的固有结果，不是缺陷：
     * 真正决定砍谁的是等级本身（见 [maxActiveFlights]），weight 只在同一等级内排序。
     */
    fun routeWeight(fromTier: Int, toTier: Int): Float =
        tierWeight(fromTier) * tierWeight(toTier) / WEIGHT_NORM

    /** 弧高倍率：主干 1.0 基线，支线 / 毛细更贴近地表 */
    fun arcBoost(klass: WorldRouteClass): Float = arcTable(klass)

    /** 额外亮度倍率：主干 1.0，支线 / 毛细递减 */
    fun trunkBrightness(klass: WorldRouteClass): Float = brightnessTable(klass)

    private fun tierWeight(tier: Int): Float =
        if (tier in 1..WorldCities.TIER_COUNT) TIER_WEIGHT[tier - 1] else TIER_WEIGHT[WorldCities.TIER_COUNT - 1]

    // ── 选航线 ─────────────────────────────────────────────────

    /**
     * 选一条新航线。
     *
     * ## 流程
     * 1. 拍点定「想要**采样的端点对**」：[BeatStrength.STRONG] → TRUNK；MID → 70% REGIONAL；
     *    WEAK → 70% FEEDER；NONE → 不限（由两端 tier 自然决定）。
     * 2. 选起点：有焦点城市时以 [FOCUS_BIAS] 的概率直接用焦点城市，否则在
     *    「该等级的合法端点池」内按 [WorldCities.weightOf] 加权抽。
     *    池子：TRUNK = Tier1..2；REGIONAL = 仅 Tier3；FEEDER = 仅 Tier4；
     *    NONE = 全部 32 座（于是自然形成「枢纽↔枢纽 + 叶端↔枢纽」的混合）。
     * 3. 选终点（[pickPeer]）：先由 [preferredPeerTiers] 把终点的**层级**锁死，使这对端点
     *    真的凑得出想要的等级；「枢纽 → 末端」这一路额外受 [LEAF_CANDIDATES] 就近抽样约束；
     *    叶端再加一层「连地理最近枢纽」的高概率偏置。
     * 4. 距离闸门（[clampWithinRange]）：`distanceKm(from, to) > MAX_ROUTE_KM` 时，
     *    换成「上限内最近的合格城市」（同样保住第 3 步的形状/就近约束）。
     * 5. ⛔ **定级 = `classify(tierOf(from), tierOf(to))`，没有任何覆写。**
     *
     * ## 两条硬性后置条件（任意种子 / 任意拍点 / 任意焦点城市都成立）
     * ```
     * spec.klass == classify(tierOf(from), tierOf(to))     // 等级纯由端点决定
     * distanceKm(from, to) <= MAX_ROUTE_KM                // 不出现近对跖点航线
     * ```
     * 外加一条（仅「起点 Tier1..3 + 想要 FEEDER」时）：
     * ```
     * to ∈ nearestOfTier(from, Tier4, LEAF_CANDIDATES)     // 末端必是就近的那几座
     * ```
     *
     * ⚠️ **焦点城市的优先级高于拍点。** 焦点是 UI 状态（用户正在看的那座城），
     * 拍点只是节奏暗示；焦点是 Tier4 时强拍也会得到一条 `FEEDER` ——
     * 这是「等级跟着端点走」的必然代价，理由见类 KDoc 的「刻意的取舍」一节。
     *
     * @param focusCity 当前焦点城市下标；`-1` 或越界表示无焦点
     * @param beat 本次拍点强度
     * @param rnd 随机源（注入 ⇒ 可复现）
     */
    fun pickRoute(rnd: WorldRng, focusCity: Int, beat: BeatStrength): WorldRouteSpec {
        val want: WorldRouteClass? = when (beat) {
            BeatStrength.STRONG -> WorldRouteClass.TRUNK
            BeatStrength.MID -> if (rnd.next() < PREFER_REGIONAL) WorldRouteClass.REGIONAL else WorldRouteClass.TRUNK
            BeatStrength.WEAK -> if (rnd.next() < PREFER_FEEDER) WorldRouteClass.FEEDER else WorldRouteClass.REGIONAL
            BeatStrength.NONE -> null
        }

        val from = pickEndpoint(rnd, focusCity, want)
        val preferred = preferredPeerTiers(WorldCities.tierOf(from), want)
        // 就近候选缓冲。长度取两个窗口的 max：[isLocalShape] 保证同一时刻只有一个窗口生效。
        // ⛔ 不是绘制热路径 —— [pickRoute] 由渲染层的 spawnRoute 调用（每次新航线 1 次
        // + 至多 ROUTE_RETRY 次重抽），且它自己就已经要 new 一个 [WorldRouteSpec]。
        val buf = IntArray(maxOf(LEAF_CANDIDATES, HUB_CANDIDATES))
        val to = clampWithinRange(from, pickPeer(rnd, from, preferred, buf), want, preferred, buf)

        val fromTier = WorldCities.tierOf(from)
        val toTier = WorldCities.tierOf(to)
        val klass = classify(fromTier, toTier)

        return WorldRouteSpec(
            from = from,
            to = to,
            klass = klass,
            weight = routeWeight(fromTier, toTier),
            arcBoost = arcBoost(klass),
            trunkBias = trunkBrightness(klass)
        )
    }

    /** 选起点；[focusCity] 合法时以 [FOCUS_BIAS] 概率直接采用 */
    private fun pickEndpoint(rnd: WorldRng, focusCity: Int, want: WorldRouteClass?): Int {
        if (focusCity in 0 until WorldCities.COUNT && rnd.next() < FOCUS_BIAS) return focusCity
        return when (want) {
            // 主干的端点池 = Tier1..2；支线 = 恰好 Tier3；毛细 = 恰好 Tier4
            WorldRouteClass.TRUNK -> pickByTier(rnd, 1, 2, -1)
            WorldRouteClass.REGIONAL -> pickByTier(rnd, 3, 3, -1)
            WorldRouteClass.FEEDER -> pickByTier(rnd, 4, 4, -1)
            null -> WorldCities.pickWeighted(rnd, -1)
        }
    }

    /**
     * 给定起点层级与「想要的等级」，算出终点的**合法层级区间**。
     *
     * 这是「拍点驱动选点、等级由端点决定」这条规则的落点：区间选对了，
     * `classify(fromTier, peerTier)` 就恒等于 [want]，
     * 等级**自然**从这对端点长出来，无需任何覆写。
     *
     * ```
     * want = null       →  Tier1..3      普通加权配对
     * want = TRUNK      →  Tier1..2      （起点是 Tier3/4 时凑不出 TRUNK，见下）
     * want = REGIONAL   →  起点 ≤ Tier2 ⇒ 恰好 Tier3
     *                       起点 =  Tier3 ⇒ Tier1..3
     *                       起点 =  Tier4 ⇒ ∅（Tier4 端点不可能产出 REGIONAL）
     * want = FEEDER     →  起点 =  Tier4 ⇒ Tier1..3（叶端连中继/枢纽）
     *                       起点 ≤  Tier3 ⇒ 恰好 Tier4（枢纽挂末端）
     * ```
     *
     * 唯一的矛盾来源是「焦点城市的层级与拍点冲突」（例如 WEAK 拍 + Tier1 焦点城）：
     * 此时返回 ∅，[preferredPeerTiers] 退回 `Tier1..3`，等级由 `classify` 自然落定。
     */
    private fun peerTierRange(fromTier: Int, want: WorldRouteClass?): IntRange = when (want) {
        null -> PEER_TIERS_ANY
        WorldRouteClass.TRUNK -> 1..2
        WorldRouteClass.REGIONAL -> when {
            fromTier <= 2 -> PEER_TIERS_T3
            fromTier == 3 -> PEER_TIERS_ANY
            else -> IntRange.EMPTY
        }
        WorldRouteClass.FEEDER -> if (fromTier >= 4) PEER_TIERS_ANY else PEER_TIERS_T4
    }

    /**
     * [peerTierRange] 的**已解析**版本：空区间一律退回 [PEER_TIERS_ANY]。
     *
     * 抽终点（[pickPeer]）与距离闸门（[clampWithinRange]）共用同一份结果，
     * 闸门才知道「叶端应当连枢纽/中继」这条形状约束。
     *
     * ⛔ 用 `first <= last` 判空而不是 `isEmpty`：Kotlin 2.x 给 `IntRange` 加了同名扩展
     * **属性** `isEmpty`，它会盖掉成员函数 `isEmpty()`，直接写 `isEmpty` 编译不过。
     */
    private fun preferredPeerTiers(fromTier: Int, want: WorldRouteClass?): IntRange {
        val r = peerTierRange(fromTier, want)
        return if (r.first <= r.last) r else PEER_TIERS_ANY
    }

    /**
     * 选终点 —— **区域密度观感的唯一来源**。
     *
     * 三条路径。⚠️ **顺序是有语义的，不可交换**：
     * 1. **枢纽 / 次级枢纽 → 末端**（`preferred == 4..4`）：从 [WorldCities.nearestOfTier]
     *    给出的**最近 [LEAF_CANDIDATES] 座 Tier4** 里按 [LEAF_WEIGHTS] 抽样。
     *    ⛔ 同样**不能**走「全表 Tier4 加权随机」：32 座城里 Tier4 只有 6 座且分布
     *    极不均匀（南美 1、非洲 3、亚洲 1、大洋洲 1，东南亚一座都没有）。
     * 2. **叶端 → 枢纽/中继**（`from` 是 Tier3/Tier4）：从 [WorldCities.nearestHubs]
     *    给出的**最近 [HUB_CANDIDATES] 座 Tier ≤ [LOCAL_HUB_MAX_TIER]** 里挑 ——
     *    以 [hubBias] 的概率取第 1 近，否则取第 2 近。
     *    ⛔ 曾经那 10 % 退回「全表 Tier1..2 加权随机」，是长毛细航线的最后来源。
     * 3. 兜底：**枢纽 ↔ 枢纽 / 枢纽 ↔ 次级枢纽**，全球加权随机 ——
     *    这条路就该跨洲（主干航线天然长），是「主干」层级的来源，不受就近约束。
     *
     * ## ⚠️ 为什么 1 必须排在 2 前面
     * 两条分支在「`from` 是 **Tier3** 且想要 FEEDER」时**同时成立**：
     * [peerTierRange] 对 `fromTier ≤ 3` + `FEEDER` 给的是 `4..4`（挂末端），
     * 而 `fromTier ≥ 3` 又让叶端分支成立。
     * 历史口径是**形状优先**：`4..4` 赢，于是 Tier3 城市（例如悉尼、法兰克福）
     * 在弱拍下会去挂末端（悉尼 ↔ 奥克兰 2,159 km），而不是去连它的近邻枢纽。
     * 本次保持该口径不变，否则 Tier3 焦点会整体失去末端航线
     * （实测把 2 排前面会让悉尼/法兰克福 的末端航线从 713 条掉到 **0** 条）。
     * [fillLocalCandidates] 用**同一个优先级**，所以距离闸门与抽样始终同口径。
     *
     * ## 为什么 2 不再检查 [preferred]
     * 叶端（Tier3/Tier4）出场时，**无论终点是 Tier 几，`classify` 都由叶端自己决定**：
     * `classify(4, x) = FEEDER` 对任意 x 成立；`classify(3, x ≤ 3) = REGIONAL`。
     * 所以对叶端放宽到「Tier ≤ 3」在**等级上是可证的零影响**，只改了距离。
     * （旧代码那 10 % 本来也只取 Tier1..2，等级同为 FEEDER/REGIONAL。）
     * 对枢纽端（Tier1..2）则必须继续按 [preferred] 限层，否则 TRUNK 会被削细。
     *
     * `from` 作为 `exclude` 传入 ⇒ 返回值恒 `!= from`。
     * ⛔ 返回值**可能**超过 [MAX_ROUTE_KM]，距离闸门由 [clampWithinRange] 负责
     * （实测两个就近窗口对任何起点都在上限内，故 ①② 实际不会触发闸门）。
     */
    private fun pickPeer(rnd: WorldRng, from: Int, preferred: IntRange, buf: IntArray): Int {
        // ① 枢纽 / 次级枢纽 → 末端：只认最近的 LEAF_CANDIDATES 座 Tier4
        if (preferred == PEER_TIERS_T4) {
            val n = WorldCities.nearestOfTier(from, PEER_TIERS_T4.first, LEAF_CANDIDATES, buf)
            if (n > 0) return buf[weightedIndex(rnd, n)]
        }
        // ② 叶端 → 枢纽/中继：只认最近的 HUB_CANDIDATES 座 Tier≤3
        val fromTier = WorldCities.tierOf(from)
        if (fromTier >= 3) {
            val n = WorldCities.nearestHubs(from, LOCAL_HUB_MAX_TIER, HUB_CANDIDATES, buf)
            if (n > 0) {
                // 恰好消耗一个随机数：≥ bias ⇒ 取第 2 近，否则第 1 近
                val coin = rnd.next()
                val pick = if (n > 1 && coin >= hubBias(fromTier, preferred)) 1 else 0
                return buf[pick]
            }
        }
        // ③ 兜底：枢纽之间的全球配对
        return pickByTier(rnd, preferred.first, preferred.last, from)
    }

    /**
     * 该形状是否是**地理就近**的（因而终点候选必须落在窗口内）。
     *
     * ```
     * preferred == 4..4   → 枢纽挂末端（近 [LEAF_CANDIDATES] 座 Tier4）
     * 起点是 Tier3/Tier4    → 叶端连枢纽（近 [HUB_CANDIDATES] 座 Tier≤3）
     * 其余                  → 枢纽↔枢纽 / 枢纽↔次级枢纽，全球，不受就近约束
     * ```
     * 前两条会同时成立（Tier3 起点 + FEEDER 拍），此时按 [pickPeer] 的**形状优先**口径
     * 取第 1 条 —— [fillLocalCandidates] 与 [pickPeer] 必须用同一个优先级，
     * 否则距离闸门会按另一套形状判断，把就近窗口替换掉。
     */
    private fun isLocalShape(from: Int, preferred: IntRange): Boolean =
        preferred == PEER_TIERS_T4 || WorldCities.tierOf(from) >= 3

    /** 就近形状对应的候选集，写入 [buf]；返回个数。非就近形状返回 0 */
    private fun fillLocalCandidates(from: Int, preferred: IntRange, buf: IntArray): Int = when {
        preferred == PEER_TIERS_T4 ->
            WorldCities.nearestOfTier(from, PEER_TIERS_T4.first, LEAF_CANDIDATES, buf)
        WorldCities.tierOf(from) >= 3 ->
            WorldCities.nearestHubs(from, LOCAL_HUB_MAX_TIER, HUB_CANDIDATES, buf)
        else -> 0
    }

    /**
     * 在 `0..n-1` 里按 [LEAF_WEIGHTS] 抽一个下标（**下标越小 = 越近 = 权重越大**）。
     *
     * 消耗**恰好一个**随机数（`n <= 1` 时不消耗，与候选集只有 1 个一致）。
     * `n` 超过权重表长度时，最后一项之后的权重并入最后一项（构造上不会发生：
     * `n <= LEAF_CANDIDATES == LEAF_WEIGHTS.size`），`coerceAtMost` 只是兜底。
     */
    private fun weightedIndex(rnd: WorldRng, n: Int): Int {
        if (n <= 1) return 0
        val u = rnd.next()
        var acc = 0f
        for (s in 0 until n - 1) {
            acc += LEAF_WEIGHTS[s.coerceAtMost(LEAF_WEIGHTS.size - 1)]
            if (u < acc) return s
        }
        return n - 1
    }

    /**
     * 「取第 2 近而不是最近那座」的概率。数值与历史口径**一字未改**：
     *
     * ```
     * preferred = 4..4      → 不适用（枢纽挂末端已被 [pickPeer] 的就近抽样接管）
     * preferred = 1..2      → 0.50   强拍下叶端（焦点与拍点冲突时）
     * preferred = 1..3      → 起点 Tier4 ? 0.90 : 0.75
     * ```
     * ⛔ 这三个数**不是**「跨洲直飞的比例」，只是「取第 2 近那座的比例」——
     * 目标已经从「全球随机」换成「次近」，所以**变化的来源变多了而不是变少**：
     * 叶端从 1 个确定目标变成 2 个确定目标。
     */
    private fun hubBias(fromTier: Int, preferred: IntRange): Float = when {
        preferred.contains(4) -> 0f
        preferred.first == 1 && preferred.last == 2 -> HUB_BIAS_TRUNK
        fromTier >= 4 -> HUB_BIAS_FEEDER
        else -> HUB_BIAS_REGIONAL
    }

    /**
     * **距离闸门**：把超出 [MAX_ROUTE_KM] 的终点换成「地理上最近的合格城市」。
     *
     * 三级偏好，**确定性**（不消耗随机数 ⇒ 同种子必然复现），由严到宽：
     * 1. [CAND_SHAPE]：上限内、且**形状合法** ——
     *    就近形状（见 [isLocalShape]）要求落在 [pickPeer] 用的那套候选窗口内；
     *    非就近形状要求层级 ∈ [preferred]；
     * 2. [CAND_CLASS]：上限内、且 `classify(fromTier, 其 tier) == want`（保住拍点意图）；
     * 3. [CAND_ANY]：上限内最近的城市 —— 距离上限是硬约束，优先于一切偏好。
     *
     * ⚠️ 第 1 档的形状检查不可省，理由有二：
     * - 只看「同等级」的话，`classify(4, x)` 对**任何** x 都返回 `FEEDER`，
     *   于是从 Tier4 城市出发时会挑到**另一座 Tier4**（约翰内斯堡 → 内罗毕 2,400 km，
     *   迪拜要 6,900 km），凭空造出「叶端↔叶端」这种模型里本不存在的航线；
     * - 不卡就近窗口的话，闸门会把一条超限的支线/毛细航线换成全球随机的城市，
     *   于是 [pickPeer] 辛苦建立的地理就近被闸门悄悄破坏。**两个方向都中过这个招。**
     *
     * 距离并列时取**下标最小**的（严格 `<` + 升序遍历）⇒ 结果唯一。
     *
     * ⛔ **不是「重抽到抽中为止」** —— 那既有无界循环风险，也可能连抽多次仍落空。
     * 这里一次线性扫描（32 座城市）直接给出答案，并保证返回值存在。
     */
    private fun clampWithinRange(
        from: Int,
        to: Int,
        want: WorldRouteClass?,
        preferred: IntRange,
        buf: IntArray
    ): Int {
        if (WorldCities.distanceKm(from, to) <= MAX_ROUTE_KM) return to
        var best = nearestWithinRange(from, want, preferred, buf, CAND_SHAPE)
        if (best < 0) best = nearestWithinRange(from, want, preferred, buf, CAND_CLASS)
        if (best < 0) best = nearestWithinRange(from, want, preferred, buf, CAND_ANY)
        if (best >= 0) return best
        // 理论上不可达：表内任意两座城市的最近距离约 2,150 km（奥克兰↔悉尼），
        // 远小于 15,000 km 的上限（`every city has a reachable partner inside the distance cap`
        // 正好锁着这条前提）。保底仍返回最近的任意城市，使本函数**总能**给出合法下标。
        return nearestAny(from)
    }

    /**
     * 距 [from] 最近、且 `distanceKm(from, ·) <= MAX_ROUTE_KM` 的城市下标。
     *
     * @param mode 候选过滤档位：[CAND_SHAPE] 要求形状合法（就近形状 ⇒ 在 [fillLocalCandidates]
     *   的窗口内；否则 ⇒ 层级 ∈ [preferred]）；[CAND_CLASS] 额外要求
     *   `classify(fromTier, 其 tier) == want`；[CAND_ANY] 不额外要求
     * @param buf 供 [fillLocalCandidates] 写入的调用方缓冲；仅 [CAND_SHAPE] 的就近形状会用到
     * @return 无解返回 `-1`
     */
    private fun nearestWithinRange(
        from: Int,
        want: WorldRouteClass?,
        preferred: IntRange,
        buf: IntArray,
        mode: Int
    ): Int {
        if (from !in 0 until WorldCities.COUNT) return -1
        val fromTier = WorldCities.tierOf(from)
        val local = isLocalShape(from, preferred)
        var localN = 0
        if (local && mode <= CAND_SHAPE) localN = fillLocalCandidates(from, preferred, buf)
        var best = -1
        // 初值取上限 ⇒ 循环里的 `> bestD` 剪枝同时兼做了「不超上限」判定
        var bestD = MAX_ROUTE_KM
        for (j in 0 until WorldCities.COUNT) {
            if (j == from) continue
            val d = WorldCities.distanceKm(from, j)
            if (d > bestD) continue
            if (mode <= CAND_CLASS && classify(fromTier, WorldCities.tierOf(j)) != want) continue
            if (mode <= CAND_SHAPE) {
                if (local) {
                    if (!isInBuf(j, buf, localN)) continue
                } else if (!preferred.contains(WorldCities.tierOf(j))) continue
            }
            bestD = d
            best = j
        }
        return best
    }

    /** [j] 是否在前 [n] 个缓冲元素里 */
    private fun isInBuf(j: Int, buf: IntArray, n: Int): Boolean {
        for (p in 0 until n) if (buf[p] == j) return true
        return false
    }

    /** [clampWithinRange] 的最后一道保底：不限距离，取最近的城市 */
    private fun nearestAny(from: Int): Int {
        if (from !in 0 until WorldCities.COUNT) return 0
        var best = -1
        var bestD = Float.MAX_VALUE
        for (j in 0 until WorldCities.COUNT) {
            if (j == from) continue
            val d = WorldCities.distanceKm(from, j)
            if (d < bestD) {
                bestD = d
                best = j
            }
        }
        if (best >= 0) return best
        return if (from == 0) 1 else 0
    }

    /**
     * 在「层级 ∈ [minTier, maxTier] 且非 [exclude]」的城市里按权重抽一个。
     *
     * 实现为「全表加权抽 + 有界拒绝采样」：单次命中率 = 该层权重和 / 总权重
     * （Tier1..2 ≈ 0.79、恰好 Tier3 ≈ 0.16、恰好 Tier4 ≈ 0.05），
     * 16 次重抽后必然命中（0.05¹⁶ ≈ 0）。零分配、O(1) 均摊。
     * 兜底扫描保证**返回值一定存在**（Tier1 有 8 座，足以在 [minTier, maxTier] ≠ ∅ 时命中）；
     * 若区间本身为空（理论上 [peerTierRange] 已排除），再退化为「全表第一个非 exclude
     * 的正权重城市」，**绝不返回 -1**。
     */
    private fun pickByTier(rnd: WorldRng, minTier: Int, maxTier: Int, exclude: Int): Int {
        repeat(TIER_PICK_ATTEMPTS) {
            val i = WorldCities.pickWeighted(rnd, exclude)
            if (WorldCities.tierOf(i) in minTier..maxTier) return i
        }
        var best = -1
        var bestW = -1f
        for (i in 0 until WorldCities.COUNT) {
            if (i == exclude) continue
            if (WorldCities.tierOf(i) !in minTier..maxTier) continue
            val w = WorldCities.weightOf(i)
            if (w > bestW) {
                bestW = w
                best = i
            }
        }
        if (best >= 0) return best
        for (i in 0 until WorldCities.COUNT) {
            if (i == exclude) continue
            if (WorldCities.weightOf(i) > 0f) return i
        }
        return if (exclude == 0) 1 else 0
    }

    // ── 并发上限 ───────────────────────────────────────────────

    /**
     * 同一对城市之间**允许同时在飞的并行航线数**上限。
     *
     * ## 需求来源
     * 2026-09-29 真机反馈：「不应该画完一条再画一条，应该按繁华比例，两个城市间
     * 可以同时画多条」——原先是「一条飞完再换下一条」，枢纽城市读起来永远只有
     * 单条航线，繁忙度看不出来。
     *
     * ## 「繁华比例」= 两端中**较弱**的那一端（tier 较大者）
     * ```
     * 两端都是 Tier1      → 3   枢纽↔枢纽：最繁忙，最多三条并行
     * 较弱端 = Tier2      → 2   枢纽↔次级枢纽 / 次级↔次级
     * 较弱端 ≥ Tier3      → 1   支线/毛细维持「一条一条来」的观感
     * ```
     *
     * ⛔ **刻意用「较弱端」而不是「较优端」**（后者会让 T1↔T4 也拿到 3 条）：
     *   一条走廊的繁忙度不该只看它最好的那个端点。本文件通篇的立场是
     *   「毛细航线必须稀疏」——它们又长又细，密度一高就会被误读成主干，
     *   「主干 + 支线」的层级对比直接消失（见类 KDoc「地理就近」一节）。
     *   T1↔T4 是典型的「枢纽挂末端」，给它 3 条并行线既不合理也会糊成一片。
     *
     * @param from 起点城市下标
     * @param to 终点城市下标
     * @return 并行航线条数上限，取值 1..3
     */
    fun maxParallelLanes(from: Int, to: Int): Int {
        val a = WorldCities.tierOf(from)
        val b = WorldCities.tierOf(to)
        val weakest = if (a > b) a else b // 两端中「较弱」的那个（tier 越大越次要）
        return when {
            weakest <= 1 -> 3
            weakest == 2 -> 2
            else -> 1
        }
    }

    /** 帧时间预算：超过即开始降级（16.7ms 是 60fps 理论值，留 20% 余量） */
    private const val FRAME_BUDGET_MS = 20f

    /** 每超出一段 [DEGRADE_STEP_MS] 就少留 1 条航线 */
    private const val DEGRADE_STEP_MS = 4f

    /** 并发航线数下限（再卡也保留 10 条，否则画面几乎静止、失去「世界在动」的感觉） */
    const val MIN_ACTIVE_FLIGHTS = 10

    /**
     * 并发航线数上限。
     *
     * 2026-09-29 由 34 提到 46：真机反馈「航线太少，非洲/大洋洲看不到航线」。
     * 根因是枢纽城市只集中在亚欧北美（见 [WorldCities.ALL] 的 2026-09-29 补表说明），
     * 补齐各大洲枢纽后仍需足够的并发数才能让每个大洲都同时有航线在飞。
     * 代价可控：同期已把 WebView 侧像素比锁到 1.0 并去掉每帧材质查找，
     * 真机 WebView 渲染进程从 133% CPU 降到流畅档（见 CHANGELOG 对应条目）。
     */
    const val MAX_ACTIVE_FLIGHTS = 46

    private const val LOW_PARTICLE_BUDGET = 0
    private const val MEDIUM_PARTICLE_BUDGET = 150

    /**
     * 活跃航线数的动态上限。
     *
     * ## 画质档映射（入参是画质档的**粒子预算**）
     * ```
     * LOW     0  → 18
     * MEDIUM 150 → 32
     * HIGH   350 → 46
     * ```
     * 预算阈值用 `<=0` / `<=150` 判定，>150 一律当 HIGH ⇒ 对未知档位前向兼容。
     *
     * ## 帧率降级
     * ```
     * n = 基线 − ceil( max(0, frameMs − 20) / 4 )
     * ```
     * 即每超预算 4ms 少留 1 条；最后 `coerceIn(10, 46)`。
     * 单调性：对 `frameMs` 单调不增（[ceil] 单调不减）⇒ 帧率抖动不会来回切档。
     *
     * ⚠️ **调用方必须按 weight 升序淘汰**（先砍最细的、保留主干），
     * 否则「只降数量」会把主干全砍光、剩下密密麻麻的毛细航线。
     * 本函数只管**数字**，淘汰顺序由渲染层负责。
     */
    fun maxActiveFlights(qualityTierParticles: Int, frameMs: Float): Int {
        val base = when {
            qualityTierParticles <= LOW_PARTICLE_BUDGET -> 18
            qualityTierParticles <= MEDIUM_PARTICLE_BUDGET -> 32
            else -> MAX_ACTIVE_FLIGHTS
        }
        var n = base
        if (frameMs > FRAME_BUDGET_MS) {
            val over = (frameMs - FRAME_BUDGET_MS) / DEGRADE_STEP_MS
            n -= ceil(over).toInt()
        }
        return n.coerceIn(MIN_ACTIVE_FLIGHTS, MAX_ACTIVE_FLIGHTS)
    }
}

/**
 * 节拍强弱分类器。
 *
 * ## 为什么不能直接用 [com.nasmusic.tv.visualizer.AudioFrame.bass]
 * `bass` 走峰值跟随归一化（`v / 峰值跟随`），而峰值跟随在鼓点瞬间就等于当帧原值
 * ⇒ **每一次鼓点的 `bass` 都恰好是 1.0**，强弱差异被完全抹平。
 * 必须用未归一化的 [com.nasmusic.tv.visualizer.AudioFrame.bassRaw] 自行归一化。
 *
 * ## 算法
 * ```
 * ratio = clamp( bassRaw / 慢速基准, 0, 4 )
 * ratio < 0.85 → WEAK      0.85..1.6 → MID      ≥ 1.6 → STRONG
 * ```
 * - **慢速基准用非对称 EMA，而不是滑动均值**：均值会被鼓点**自身**顶高
 *   （单个样本在 60fps 的 4s 窗口里占 1/240，看似很小，但均值没有「快下慢上」的
 *   特性，音乐一响基准就跟上去，后面真正的弱拍全被判成 WEAK）。
 *   这里 time constant = [windowMs]（默认 4s），且
 *   **上行（能量高于基准）只用 1/4 的步长、下行用全量步长** ⇒ 基准「快下慢上」，
 *   一次鼓点最多把基准抬高 `alpha/4 × 冲击幅度`，可忽略。
 * - **ratio 钳在 [0, 4]**：静默段基准趋近 0 会让除法爆掉；上限 4 防止极端瞬态
 *   把 STRONG 之后的回落拉成假 WEAK。
 * - **冷却 110ms**：一次鼓点的能量包络会横跨好几帧（峰值跟随的尾巴），
 *   没有冷却就会连发。冷却期内仍照常更新基准与 [lastStrength]（涟漪要连续），
 *   只是不再上报强度。
 * - `hasBeat == false` ⇒ 返回 [BeatStrength.NONE]，但**仍然**更新基准
 *   （否则非拍点帧不喂数据，基准会与真实电平脱节）。
 */
internal class BeatClassifier(
    /** 基准窗口（毫秒），默认 4s —— 同时充当 EMA 的时间常数 */
    val windowMs: Long = DEFAULT_WINDOW_MS
) {

    /** 慢速基准（EMA） */
    private var baseline = BASELINE_FLOOR

    /** 上一帧时间戳 */
    private var lastMs = 0L

    /** 是否已收到过第一帧（首帧无基准可比） */
    private var started = false

    /** 上次上报（非 NONE）的时刻，用于冷却 */
    private var lastFireMs = 0L

    private var hasFired = false

    /** 连续强度 0..1，供涟漪使用 */
    private var strength = 0f

    /**
     * 喂入一帧的原始低频能量与时间戳，内部更新慢速基准；返回本次强度。
     *
     * @param bassRaw 未归一化的 20–250Hz 能量（[com.nasmusic.tv.visualizer.AudioFrame.bassRaw]）
     * @param nowMs 单调时钟
     * @param hasBeat 本帧是否命中节拍；false ⇒ 返回 [BeatStrength.NONE] 但仍更新基准
     */
    fun update(bassRaw: Float, nowMs: Long, hasBeat: Boolean): BeatStrength {
        val x = if (bassRaw.isFinite() && bassRaw > 0f) bassRaw else 0f
        if (!started) {
            // 首帧直接对齐：没有历史就没有「相对基准」，此时任何分档都是假的
            baseline = if (x > BASELINE_FLOOR) x else BASELINE_FLOOR
            lastMs = nowMs
            started = true
            strength = 0f
            return BeatStrength.NONE
        }

        // 时钟回退 / 重复时间戳时按一帧算，避免 alpha 变成 0 或负数
        val dt = (nowMs - lastMs).coerceAtLeast(FRAME_FALLBACK_MS)
        lastMs = nowMs
        val tau = if (windowMs > 0) windowMs.toDouble() else DEFAULT_WINDOW_MS.toDouble()
        val alpha = (1.0 - exp(-dt.toDouble() / tau)).toFloat()
        // 上行阻尼：鼓点只能把基准顶起 alpha/4；下行全量跟随，保证基准能快速回到真实电平
        baseline += (x - baseline) * (if (x > baseline) alpha * RISE_DAMP else alpha)
        if (baseline < BASELINE_FLOOR) baseline = BASELINE_FLOOR

        val ratio = (x / baseline).coerceIn(0f, RATIO_CLAMP)
        strength = ((ratio - RATIO_WEAK) / (RATIO_STRONG - RATIO_WEAK)).coerceIn(0f, 1f)

        if (!hasBeat) return BeatStrength.NONE
        if (hasFired && nowMs - lastFireMs < COOLDOWN_MS) return BeatStrength.NONE

        val s = when {
            ratio >= RATIO_STRONG -> BeatStrength.STRONG
            ratio >= RATIO_WEAK -> BeatStrength.MID
            else -> BeatStrength.WEAK
        }
        hasFired = true
        lastFireMs = nowMs
        return s
    }

    /** 供渲染层做涟漪强度用的连续值 0..1（[RATIO_WEAK] → 0，[RATIO_STRONG] → 1，超出饱和） */
    fun lastStrength(): Float = strength

    /** 当前慢速基准；⛔ 仅供单测 / 调试断言「基准没被鼓点顶高」 */
    fun baseline(): Float = baseline

    companion object {
        /** 默认基准窗口 4s */
        const val DEFAULT_WINDOW_MS = 4000L

        /** WEAK / MID 与 MID / STRONG 的分界 */
        const val RATIO_WEAK = 0.85f
        const val RATIO_STRONG = 1.6f

        /** ratio 上限，防止极端瞬态 */
        const val RATIO_CLAMP = 4f

        /** 上行阻尼系数（基准被顶高的步长 = alpha × 该值） */
        const val RISE_DAMP = 0.25f

        /** 同一拍点不重复上报的冷却（毫秒） */
        const val COOLDOWN_MS = 110L

        /** 基准下限，防止静默段除零 */
        const val BASELINE_FLOOR = 1e-4f

        /** 时间戳异常时的最小步长（约一帧） */
        const val FRAME_FALLBACK_MS = 16L
    }
}

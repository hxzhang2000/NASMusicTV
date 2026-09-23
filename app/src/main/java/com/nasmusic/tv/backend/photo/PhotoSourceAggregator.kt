package com.nasmusic.tv.backend.photo

import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.visualizer.VisualizerRandom

/**
 * 聚合结果
 *
 * @param photos **最终照片池**，顺序已按 [PhotoSourceAggregator.collect] 的 `balance` 参数决定
 *   （自然混合 = 整体洗牌；来源均衡 = 等概率交错）。上层用**游标顺序消费**（§6.7
 *   「Fisher-Yates + 游标」），不要每次重新随机取 —— 那会让「无重复遍历」失效。
 * @param perSource **去重后**各来源实际贡献的条数。`perSource.values.sum() == photos.size`。
 *   只包含「本平台确实存在且已开启」的来源；关掉的来源计数为 `0`。
 * @param statuses 各来源状态（供设置页显示「为什么不可用」）。关掉的来源为
 *   [PhotoSourceStatus.DISABLED]（由本类填入，[PhotoSource.status] 自身不知道开关状态）。
 */
data class AggregateResult(
    val photos: List<PhotoRef>,
    val perSource: Map<PhotoSourceKind, Int>,
    val statuses: Map<PhotoSourceKind, PhotoSourceStatus>,
)

/**
 * 多来源聚合（§6.8）
 *
 * **设计**：不做「优先级 + 回落链」，做「**集合**」—— 开关打开的来源全部参与，
 * 合并成一个池。天然容错：拔盘 / NAS 断连时其余来源继续工作，无需任何回落逻辑。
 *
 * ## 流程
 *
 * ```
 * 遍历 PhotoSourceKind.entries（固定顺序 → 去重「首次出现」稳定）
 *   ├─ sources 里没有该来源（如电视无图库）→ 跳过（statuses 里也不出现）
 *   ├─ 不在 enabled 里 → statuses = DISABLED，**status() 与 listPhotos() 都不调用**
 *   ├─ status() 不可扫（拒权 / 未连 / 无目录 / 无照片库）→ 记状态，跳过 listPhotos
 *   └─ 可扫 → listPhotos()（异常兜底为空表）
 * → 合并 → 指纹去重（PhotoDedup）→ 排序（洗牌 / 均衡）
 * ```
 *
 * ## 两个刻意的选择
 *
 * 1. **关掉的来源连 `status()` 都不调用** —— 「不扫描」是硬要求（避免未授权时探测内部存储），
 *    单测用假 [PhotoSource] 记录调用次数来验证，不靠断点。
 * 2. **异常一律兜底为空表，不向上抛** —— 一个来源炸掉不能拖垮整面照片墙（§6.8 天然容错）。
 *
 * ## 随机源
 *
 * 复用 [VisualizerRandom]（§6.5：**不要**新建 `kotlin.random.Random`）—— 随机洗牌与随机转场
 * 共用同一个序列，避免两处各自维护一套随机状态。生产环境由上层注入**共享实例**；
 * 默认参数只为单测 / 独立使用提供兜底。
 */
class PhotoSourceAggregator(
    private val sources: Map<PhotoSourceKind, PhotoSource>,
    private val dedup: PhotoDedup = PhotoDedup(),
    private val random: VisualizerRandom = VisualizerRandom(),
) {

    /**
     * 只扫描 [enabled] 里的来源；跨来源去重；按 [balance] 决定池的排序。
     *
     * @param enabled 已打开的来源开关集合。**不在其中的来源不会被扫描**
     *   （`status()` 也不会被调用）。
     * @param balance `false` = **自然混合**（合并后整体洗牌 ⇒ 按各来源数量加权，默认）；
     *   `true` = **来源均衡**（每次等概率选一个未取完的来源 ⇒ 小来源在序列前段出现频率被抬高）。
     */
    suspend fun collect(
        enabled: Set<PhotoSourceKind>,
        balance: Boolean = false,
    ): AggregateResult {
        val statuses = LinkedHashMap<PhotoSourceKind, PhotoSourceStatus>()
        val perKind = LinkedHashMap<PhotoSourceKind, List<PhotoRef>>()

        for (kind in PhotoSourceKind.entries) {
            val source = sources[kind] ?: continue

            if (kind !in enabled) {
                // ⛔ 关掉的来源**完全不接触** —— 连 status() 都不调用
                statuses[kind] = PhotoSourceStatus.DISABLED
                continue
            }

            val status = runCatching { source.status() }
                .getOrDefault(PhotoSourceStatus.UNAVAILABLE)
            statuses[kind] = status
            if (!status.isScannable()) {
                AppLog.d(TAG, "skip ${kind.name}: ${status.name}")
                continue
            }

            val list = runCatching { source.listPhotos() }
                .onFailure { AppLog.e(TAG, "listPhotos failed for ${kind.name}: ${it.message}", it) }
                .getOrDefault(emptyList())
            if (list.isNotEmpty()) perKind[kind] = list
            AppLog.d(TAG, "${kind.name}: ${list.size} photos (${status.name})")
        }

        val merged = ArrayList<PhotoRef>(perKind.values.sumOf { it.size })
        for (kind in PhotoSourceKind.entries) perKind[kind]?.let(merged::addAll)

        val deduped = dedup.distinct(merged)
        val ordered = if (balance) balancedOrder(perKind, deduped) else shuffled(deduped)

        val perSource = LinkedHashMap<PhotoSourceKind, Int>()
        for (kind in PhotoSourceKind.entries) {
            if (kind in statuses) perSource[kind] = 0
        }
        for (ref in ordered) perSource[ref.source] = (perSource[ref.source] ?: 0) + 1

        if (deduped.size != merged.size) {
            AppLog.d(TAG, "dedup: ${merged.size} → ${deduped.size}")
        }
        return AggregateResult(photos = ordered, perSource = perSource, statuses = statuses)
    }

    // ────────────────────────── 排序策略 ──────────────────────────

    /**
     * 自然混合：整体 Fisher-Yates 洗牌。
     *
     * 洗牌后各来源在序列里按**数量加权**分布（Jellyfin 5000 + U 盘 50 ⇒ 约 1% 是 U 盘）。
     * ⚠️ 用注入的 [random]，不是 `list.shuffled()`（后者用全局 `Random.Default`，会破坏
     * 「随机序列只有一处」的约定）。
     */
    private fun shuffled(list: List<PhotoRef>): List<PhotoRef> {
        if (list.size < 2) return list
        val a = list.toMutableList()
        for (i in a.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            if (i != j) {
                val tmp = a[i]
                a[i] = a[j]
                a[j] = tmp
            }
        }
        return a
    }

    /**
     * 来源均衡：每轮**等概率选一个还没取完的来源**，从该来源取一张。
     *
     * 效果：小来源被「提前用完」。Jellyfin 5000 + U 盘 50 ⇒ 前 100 项里两者各约 50 项
     * （**同量级**），之后才只剩 Jellyfin。
     *
     * ⚠️ 与「按数量加权交错」的区别：加权交错只是把序列排得整齐，**不会**提高小来源在
     * 序列前段的密度 —— 而「用户看几分钟就退出」正是本选项要解决的场景。
     *
     * @param bySource 各来源的**原始**列表（未去重）。去重后的 [deduped] 仅用于兜底：
     *   若去重把某个来源清空（理论上不会，指纹去重只在跨来源间生效），该来源自然退出。
     */
    private fun balancedOrder(
        bySource: Map<PhotoSourceKind, List<PhotoRef>>,
        deduped: List<PhotoRef>,
    ): List<PhotoRef> {
        if (bySource.size <= 1) return shuffled(deduped)

        // 每个来源一个桶：先各自打散（否则取出来的是文件系统自然顺序，不够随机）
        val kept = deduped.mapTo(HashSet(deduped.size)) { it.id }
        val buckets = ArrayList<MutableList<PhotoRef>>(bySource.size)
        for (list in bySource.values) {
            val filtered = list.filter { it.id in kept }
            if (filtered.isEmpty()) continue
            buckets.add(shuffled(filtered).toMutableList())
        }
        if (buckets.size <= 1) return shuffled(deduped)

        val out = ArrayList<PhotoRef>(deduped.size)
        val alive = ArrayList<Int>(buckets.size)
        while (true) {
            alive.clear()
            for (i in buckets.indices) if (buckets[i].isNotEmpty()) alive.add(i)
            if (alive.isEmpty()) break
            val idx = alive[nextInt(alive.size)]
            val bucket = buckets[idx]
            out.add(bucket.removeAt(bucket.size - 1))
        }
        return out
    }

    /** `[0, bound)` 的随机下标；用注入的 [VisualizerRandom]，不做取模偏置修正（池规模下无意义） */
    private fun nextInt(bound: Int): Int =
        (random.next() * bound).toInt().coerceIn(0, bound - 1)

    private companion object {
        const val TAG = "PhotoSourceAggregator"
    }
}

/**
 * 该状态下是否值得去调 `listPhotos()`。
 *
 * ⛔ 用**穷举 `when`** 而不是「排除法」—— 新增 [PhotoSourceStatus] 值时编译器会强制这里表态，
 * 避免新状态被静默当成「可扫描」。
 */
private fun PhotoSourceStatus.isScannable(): Boolean = when (this) {
    PhotoSourceStatus.OK -> true
    /** 部分授权**算可用**：用户已选中的那批照片可读（§9.6） */
    PhotoSourceStatus.PARTIAL_PERMISSION -> true

    PhotoSourceStatus.DISABLED,
    PhotoSourceStatus.PERMISSION_DENIED,
    PhotoSourceStatus.NO_DIRECTORY,
    PhotoSourceStatus.NOT_CONNECTED,
    PhotoSourceStatus.NO_PHOTO_LIBRARY,
    PhotoSourceStatus.UNAVAILABLE,
    -> false
}

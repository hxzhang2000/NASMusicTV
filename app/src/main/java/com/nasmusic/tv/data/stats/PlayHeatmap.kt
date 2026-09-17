package com.nasmusic.tv.data.stats

import java.util.Calendar
import java.util.TimeZone

/**
 * 热力图单日格子（F2-2）
 *
 * @param dateKey yyyy-MM-dd（设备本地时区，与 [PlayStatsRepository.currentDay] 同口径）
 * @param count   当天播放次数
 * @param level   颜色分级：0 = 无播放，1…[PlayHeatmapBuilder.LEVELS] = 由少到多
 */
data class HeatmapDay(
    val dateKey: String,
    val count: Int,
    val level: Int
)

/** 月份标签：应显示在第 [weekIndex] 列上方 */
data class HeatmapMonthLabel(val text: String, val weekIndex: Int)

/**
 * 听歌热力图模型（F2-2）
 *
 * [weeks]：列 = 周（由旧到新），每列固定 7 项，**行 0 = 周日 … 行 6 = 周六**；
 * 元素为 null 表示该格落在统计窗口之外（未来日期），UI 应留空不绘制。
 */
data class PlayHeatmap(
    val weeks: List<List<HeatmapDay?>>,
    val monthLabels: List<HeatmapMonthLabel>,
    val totalPlays: Int,
    val activeDays: Int,
    val longestStreak: Int,
    val bestDay: HeatmapDay?,
    val maxCount: Int,
    val startDateKey: String,
    val endDateKey: String
) {
    val isEmpty: Boolean get() = totalPlays == 0
}

/**
 * 听歌热力图聚合器（F2-2）——纯函数，可单测。
 *
 * ⚠️ 刻意**只用 [Calendar]**，不用 `java.time`：minSdk 22 且项目未启用
 * core library desugaring，`LocalDate` 在 API < 26 上会 `NoSuchMethodError`
 * （编译期/单测都发现不了，只有低版本设备会崩）。
 *
 * 分级策略：阈值取**非零播放量的四分位**，而非"相对峰值"——
 * 后者在峰值远高于日常时会把绝大多数格子压成同一档，热力图失去信息量。
 */
object PlayHeatmapBuilder {

    /** 颜色分级数（不含 0 级） */
    const val LEVELS = 4

    /** 默认展示最近 53 周（≈ 一年） */
    const val DEFAULT_WEEKS = 53

    /**
     * @param dailyCounts dateKey -> 播放次数
     * @param endMs       窗口结束时刻（默认"现在"），落在当天即可，内部会归零时分秒
     * @param weeks       列数（周），默认 53
     * @param timeZone    计算所用时区，默认设备时区（测试可注入以固定结果）
     */
    fun build(
        dailyCounts: Map<String, Int>,
        endMs: Long = System.currentTimeMillis(),
        weeks: Int = DEFAULT_WEEKS,
        timeZone: TimeZone = TimeZone.getDefault()
    ): PlayHeatmap {
        val cols = weeks.coerceAtLeast(1)
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = endMs
        clearTime(cal)
        val endDay = cal.clone() as Calendar

        // 回退到本周第一天（周日），再往前 (cols - 1) 周 = 窗口起始周
        cal.add(Calendar.DAY_OF_MONTH, -(cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
        cal.add(Calendar.DAY_OF_MONTH, -7 * (cols - 1))
        val cursor = cal

        // 第一趟：逐格取 (dateKey, count)，同时算总量 / 活跃天数 / 最长连续 / 峰值
        val raw = ArrayList<Array<Pair<String, Int>?>>(cols)
        val positive = ArrayList<Int>()
        var totalPlays = 0
        var activeDays = 0
        var longestStreak = 0
        var streak = 0
        var bestKey: String? = null
        var bestCount = 0
        var maxCount = 0

        for (w in 0 until cols) {
            val col = arrayOfNulls<Pair<String, Int>>(7)
            for (r in 0 until 7) {
                if (cursor.after(endDay)) {
                    cursor.add(Calendar.DAY_OF_MONTH, 1)
                    continue
                }
                val key = dateKey(cursor)
                val count = dailyCounts[key] ?: 0
                col[r] = key to count
                if (count > 0) {
                    totalPlays += count
                    activeDays++
                    streak++
                    if (streak > longestStreak) longestStreak = streak
                    positive.add(count)
                    if (count > maxCount) maxCount = count
                    if (count > bestCount) {
                        bestCount = count
                        bestKey = key
                    }
                } else {
                    streak = 0
                }
                cursor.add(Calendar.DAY_OF_MONTH, 1)
            }
            raw.add(col)
        }

        val thresholds = quartileThresholds(positive.sorted())
        val weeksOut = raw.map { col ->
            col.map { p -> p?.let { (k, c) -> HeatmapDay(k, c, levelOf(c, thresholds)) } }
        }

        return PlayHeatmap(
            weeks = weeksOut,
            monthLabels = buildMonthLabels(raw),
            totalPlays = totalPlays,
            activeDays = activeDays,
            longestStreak = longestStreak,
            bestDay = bestKey?.let { HeatmapDay(it, bestCount, levelOf(bestCount, thresholds)) },
            maxCount = maxCount,
            startDateKey = weeksOut.firstOrNull()?.firstOrNull { it != null }?.dateKey.orEmpty(),
            endDateKey = weeksOut.lastOrNull()?.lastOrNull { it != null }?.dateKey.orEmpty()
        )
    }

    /** 播放次数 → 颜色分级（0 无播放；1…LEVELS） */
    fun levelOf(count: Int, thresholds: IntArray): Int {
        if (count <= 0) return 0
        var level = 1
        for (t in thresholds) if (count > t) level++
        return level.coerceAtMost(LEVELS)
    }

    /**
     * 非零播放量的四分位阈值（升序输入）。
     * 返回空列表时全部非零格子归为 1 级（"只听过一次"的均匀分布）。
     */
    private fun quartileThresholds(sortedPositive: List<Int>): IntArray {
        if (sortedPositive.isEmpty()) return IntArray(LEVELS - 1)
        fun pick(p: Double): Int {
            val idx = ((sortedPositive.size - 1) * p).toInt().coerceIn(0, sortedPositive.lastIndex)
            return sortedPositive[idx]
        }
        return intArrayOf(pick(0.25), pick(0.50), pick(0.75))
    }

    /**
     * 月份标签：出现在"该月第一列"上方。
     * 若与上一个标签距离不足 3 列（窗口开头常出现只剩几天的残月），
     * 则顺延到 `上一标签 + 3` 列，**宁可位置略偏也不丢标签**。
     */
    private fun buildMonthLabels(raw: List<Array<Pair<String, Int>?>>): List<HeatmapMonthLabel> {
        val labels = ArrayList<HeatmapMonthLabel>()
        var prevMonth = -1
        var lastLabelWeek = -1000
        raw.forEachIndexed { w, col ->
            val first = col.firstOrNull { it != null } ?: return@forEachIndexed
            val month = first.first.substring(5, 7).toIntOrNull() ?: return@forEachIndexed
            if (month == prevMonth) return@forEachIndexed
            prevMonth = month
            val at = maxOf(w, lastLabelWeek + 3)
            if (at <= raw.lastIndex) {
                labels.add(HeatmapMonthLabel("${month}月", at))
                lastLabelWeek = at
            }
        }
        return labels
    }

    private fun clearTime(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    /** yyyy-MM-dd；手写补零而非 String.format，规避 Locale 影响 */
    private fun dateKey(cal: Calendar): String {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "$y-${if (m < 10) "0$m" else "$m"}-${if (d < 10) "0$d" else "$d"}"
    }
}

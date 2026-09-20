package com.nasmusic.tv.data.stats

import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放统计仓库（F2-1 / F2-5）：月度 + 按天播放次数 + 聚合查询。
 *
 * 数据模型（均为 DataStore JSON）：
 * - 月度：`{ "2026-09": { songId: count } }`
 * - 按天：`{ "2026-09-17": count }`（F2-5 热力图数据源）
 *
 * 写入路径：与 [AppPreferences.recordPlayWithSong] 同一次 DataStore edit
 * （经 [appendMonthlyPlayInEdit] / [appendDailyPlayInEdit] 注入，保持原子性，
 * 避免统计键与 play_counts 脱节）。
 *
 * 设计取舍（见 docs/archive/feature-dev-plan-2026-09.md §1.6）：
 * - 月度不做历史回填：存量 play_counts 无时间维度，回填只能估。
 * - 按天**做一次性回填**（[backfillDailyInEdit]）：play_records 带 timestamp，
 *   虽是"尽力而为"（上限 500 条），但好过新用户之外的人打开热力图全空白。
 * - 仅保留最近 12 个月 / 最近 400 天，超出的键滚动清理。
 */
class PlayStatsRepository(private val prefs: AppPreferences) {

    companion object {
        private const val TAG = "PlayStatsRepository"
        private const val KEY_NAME = "play_stats_monthly"
        private const val KEY_DAILY_NAME = "play_stats_daily"
        private const val KEY_DAILY_BACKFILLED = "play_stats_daily_backfilled_v1"
        private const val MAX_MONTHS = 12

        /**
         * 按天数据保留天数。热力图窗口是 53 周（371 天），
         * 留 400 天余量，保证窗口最左一列的数据不会被清理掉。
         */
        private const val MAX_DAYS = 400

        /** 当前月键（yyyy-MM，设备本地时区） */
        fun currentMonth(nowMs: Long = System.currentTimeMillis()): String =
            SimpleDateFormat("yyyy-MM", Locale.US).format(Date(nowMs))

        /** 当前日键（yyyy-MM-dd，设备本地时区）——与 [PlayHeatmapBuilder] 的 dateKey 同口径 */
        fun currentDay(nowMs: Long = System.currentTimeMillis()): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))

        /** nowMs 所在月的起始毫秒（月切换检测用） */
        fun monthStartMs(month: String): Long = try {
            SimpleDateFormat("yyyy-MM", Locale.US).parse(month)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    internal val keyPlayStatsMonthly = stringPreferencesKey(KEY_NAME)
    internal val keyPlayStatsDaily = stringPreferencesKey(KEY_DAILY_NAME)
    internal val keyDailyBackfilled = booleanPreferencesKey(KEY_DAILY_BACKFILLED)

    /** 月度统计 Flow：month -> (songId -> count)，解析失败降级空表 */
    val monthlyStats: Flow<Map<String, Map<String, Int>>> = prefs.dataStoreData().map { p ->
        val json = p[keyPlayStatsMonthly] ?: "{}"
        try {
            prefs.gson().fromJson(json, object : TypeToken<Map<String, Map<String, Int>>>() {}.type) ?: emptyMap()
        } catch (e: Exception) {
            AppLog.w(TAG, "monthlyStats parse failed", e)
            emptyMap()
        }
    }

    /**
     * 在 recordPlayWithSong 的 DataStore edit 闭包内追加月度计数（原子写）。
     * 由 AppPreferences 内部调用，避免两次 DataStore 写。
     */
    fun appendMonthlyPlayInEdit(prefsMutable: androidx.datastore.preferences.core.MutablePreferences, songId: String, nowMs: Long) {
        try {
            val month = currentMonth(nowMs)
            val json = prefsMutable[keyPlayStatsMonthly] ?: "{}"
            val type = object : TypeToken<MutableMap<String, MutableMap<String, Int>>>() {}.type
            val map: MutableMap<String, MutableMap<String, Int>> = try {
                prefs.gson().fromJson<MutableMap<String, MutableMap<String, Int>>>(json, type) ?: mutableMapOf()
            } catch (e: Exception) {
                mutableMapOf()
            }
            val monthMap = map.getOrPut(month) { mutableMapOf() }
            monthMap[songId] = (monthMap[songId] ?: 0) + 1
            // 滚动清理：只保留最近 MAX_MONTHS 个月
            if (map.size > MAX_MONTHS) {
                val keep = map.keys.sortedDescending().take(MAX_MONTHS)
                map.keys.retainAll(keep)
            }
            prefsMutable[keyPlayStatsMonthly] = prefs.gson().toJson(map)
        } catch (e: Exception) {
            // 统计失败不影响主写入路径
            AppLog.w(TAG, "appendMonthlyPlayInEdit failed", e)
        }
    }

    /** 指定月的 songId -> count（缺省当月） */
    suspend fun getMonthCounts(month: String = currentMonth()): Map<String, Int> =
        monthlyStats.first()[month] ?: emptyMap()

    // ==================== 按天统计（F2-5 热力图） ====================

    /** 按天统计 Flow：yyyy-MM-dd -> count，解析失败降级空表 */
    val dailyStats: Flow<Map<String, Int>> = prefs.dataStoreData().map { p ->
        val json = p[keyPlayStatsDaily] ?: "{}"
        try {
            prefs.gson().fromJson(json, object : TypeToken<Map<String, Int>>() {}.type) ?: emptyMap()
        } catch (e: Exception) {
            AppLog.w(TAG, "dailyStats parse failed", e)
            emptyMap()
        }
    }

    /**
     * 在 recordPlayWithSong 的 DataStore edit 闭包内追加当天计数（原子写）。
     *
     * 与 [appendMonthlyPlayInEdit] 挂在同一处写入点，保证热力图总量与月度总量口径一致
     * —— 注意 **不要** 再挂到 [AppPreferences.addPlayRecord]（那是播放结束时的另一条路径，
     * 两处都加会让每天的次数翻倍）。
     */
    fun appendDailyPlayInEdit(
        prefsMutable: androidx.datastore.preferences.core.MutablePreferences,
        nowMs: Long
    ) {
        try {
            val day = currentDay(nowMs)
            val map = parseDaily(prefsMutable[keyPlayStatsDaily]).toMutableMap()
            map[day] = (map[day] ?: 0) + 1
            // 滚动清理：保留最近 MAX_DAYS 天（键为 yyyy-MM-dd，字典序即时间序）
            if (map.size > MAX_DAYS) {
                val keep = map.keys.sortedDescending().take(MAX_DAYS)
                map.keys.retainAll(keep)
            }
            prefsMutable[keyPlayStatsDaily] = prefs.gson().toJson(map)
        } catch (e: Exception) {
            // 统计失败不影响主写入路径
            AppLog.w(TAG, "appendDailyPlayInEdit failed", e)
        }
    }

    /**
     * 一次性历史回填（F2-5）：把 [PlayRecord] 列表按 timestamp 聚合进按天统计。
     *
     * 幂等：① 迁移标记已置位则跳过；② 按天统计已有数据（说明增量计数早已生效）则只置位不回填，
     * 避免与增量重复计数。回填上限由 play_records 的 500 条上限决定，属"尽力而为"。
     */
    fun backfillDailyInEdit(
        prefsMutable: androidx.datastore.preferences.core.MutablePreferences,
        records: List<com.nasmusic.tv.data.model.PlayRecord>
    ) {
        try {
            if (prefsMutable[keyDailyBackfilled] == true) return
            val existing = parseDaily(prefsMutable[keyPlayStatsDaily])
            if (existing.isNotEmpty() || records.isEmpty()) {
                prefsMutable[keyDailyBackfilled] = true
                return
            }
            val agg = mutableMapOf<String, Int>()
            for (r in records) {
                val day = currentDay(r.timestamp)
                agg[day] = (agg[day] ?: 0) + 1
            }
            prefsMutable[keyPlayStatsDaily] = prefs.gson().toJson(agg)
            prefsMutable[keyDailyBackfilled] = true
        } catch (e: Exception) {
            AppLog.w(TAG, "backfillDailyInEdit failed", e)
        }
    }

    /** 全部按天计数（热力图数据源） */
    suspend fun getDailyCounts(): Map<String, Int> = dailyStats.first()

    private fun parseDaily(json: String?): Map<String, Int> = try {
        prefs.gson().fromJson<Map<String, Int>>(
            json ?: "{}",
            object : TypeToken<Map<String, Int>>() {}.type
        ) ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }
}

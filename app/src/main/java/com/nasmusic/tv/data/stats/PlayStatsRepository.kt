package com.nasmusic.tv.data.stats

import android.util.Log
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
 * 播放统计仓库（F2-1）：月度播放次数 + 聚合查询。
 *
 * 数据模型：DataStore JSON `{ "2026-09": { songId: count } }`。
 * 写入路径：与 [AppPreferences.recordPlayWithSong] 同一次 DataStore edit
 * （经 [appendMonthlyPlay] 回调注入，保持原子性，避免统计键与 play_counts 脱节）。
 *
 * 设计取舍（见 docs/feature-dev-plan-2026-09.md §1.6）：
 * - 不做历史回填：存量 play_counts 无时间维度，回填只能估。
 * - 仅保留最近 12 个月，超过的月度键滚动清理。
 */
class PlayStatsRepository(private val prefs: AppPreferences) {

    companion object {
        private const val TAG = "PlayStatsRepository"
        private const val KEY_NAME = "play_stats_monthly"
        private const val MAX_MONTHS = 12

        /** 当前月键（yyyy-MM，设备本地时区） */
        fun currentMonth(nowMs: Long = System.currentTimeMillis()): String =
            SimpleDateFormat("yyyy-MM", Locale.US).format(Date(nowMs))

        /** nowMs 所在月的起始毫秒（月切换检测用） */
        fun monthStartMs(month: String): Long = try {
            SimpleDateFormat("yyyy-MM", Locale.US).parse(month)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    internal val keyPlayStatsMonthly = stringPreferencesKey(KEY_NAME)

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
}

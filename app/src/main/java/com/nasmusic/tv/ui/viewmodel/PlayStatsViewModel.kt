package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.stats.PlayHeatmap
import com.nasmusic.tv.data.stats.PlayHeatmapBuilder
import com.nasmusic.tv.data.stats.PlayStatsAggregator
import com.nasmusic.tv.data.stats.StatsBundle
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放统计 ViewModel（F2-1 / F2-2）
 *
 * 数据来源：
 * - 月度：PlayStatsRepository.monthlyStats（当月 songId 次数）
 * - 累计：AppPreferences.playCounts（历史累计，无时间维度）
 * - 按天：PlayStatsRepository.dailyStats（F2-2 热力图，dateKey -> 次数）
 * - 元数据：recentSongObjects（最近 50 首完整 Song 对象，含网络歌曲）
 */
class PlayStatsViewModel(app: Application) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val prefs = nasMusicApp.appPreferences

    private val _monthlyBundle = MutableStateFlow<StatsBundle?>(null)
    val monthlyBundle: StateFlow<StatsBundle?> = _monthlyBundle

    private val _allTimeBundle = MutableStateFlow<StatsBundle?>(null)
    val allTimeBundle: StateFlow<StatsBundle?> = _allTimeBundle

    /** 听歌热力图（F2-2）；null = 尚未加载 */
    private val _heatmap = MutableStateFlow<PlayHeatmap?>(null)
    val heatmap: StateFlow<PlayHeatmap?> = _heatmap

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun loadStats() {
        viewModelScope.launch {
            _loading.value = true
            try {
                // 首次打开时把 play_records 回填进按天统计（幂等，标记置位后零开销）
                runCatching { prefs.backfillDailyStatsOnce() }
                    .onFailure { AppLog.w("PlayStatsViewModel", "backfill daily stats failed", it) }

                val songMeta: List<Song> = prefs.getRecentSongObjects()
                val month = com.nasmusic.tv.data.stats.PlayStatsRepository.currentMonth()

                val monthlyCounts = prefs.playStatsRepo.getMonthCounts(month)
                val allTimeCounts = prefs.history.playCounts.firstOrNull() ?: emptyMap()
                val dailyCounts = prefs.playStatsRepo.getDailyCounts()

                _monthlyBundle.value = withContext(Dispatchers.Default) {
                    PlayStatsAggregator.aggregate(month, monthlyCounts, songMeta)
                }
                _allTimeBundle.value = withContext(Dispatchers.Default) {
                    PlayStatsAggregator.aggregate("all", allTimeCounts, songMeta)
                }
                _heatmap.value = withContext(Dispatchers.Default) {
                    PlayHeatmapBuilder.build(dailyCounts)
                }
            } catch (e: Exception) {
                AppLog.e("PlayStatsViewModel", "loadStats failed", e)
            } finally {
                _loading.value = false
            }
        }
    }
}

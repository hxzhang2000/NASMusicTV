package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.data.model.Song
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
 * 播放统计 ViewModel（F2-1）
 *
 * 数据来源：
 * - 月度：PlayStatsRepository.monthlyStats（当月 songId 次数）
 * - 累计：AppPreferences.playCounts（历史累计，无时间维度）
 * - 元数据：recentSongObjects（最近 50 首完整 Song 对象，含网络歌曲）
 */
class PlayStatsViewModel(app: Application) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val prefs = nasMusicApp.appPreferences

    private val _monthlyBundle = MutableStateFlow<StatsBundle?>(null)
    val monthlyBundle: StateFlow<StatsBundle?> = _monthlyBundle

    private val _allTimeBundle = MutableStateFlow<StatsBundle?>(null)
    val allTimeBundle: StateFlow<StatsBundle?> = _allTimeBundle

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun loadStats() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val songMeta: List<Song> = prefs.getRecentSongObjects()
                val month = com.nasmusic.tv.data.stats.PlayStatsRepository.currentMonth()

                val monthlyCounts = prefs.playStatsRepo.getMonthCounts(month)
                val allTimeCounts = prefs.history.playCounts.firstOrNull() ?: emptyMap()

                _monthlyBundle.value = withContext(Dispatchers.Default) {
                    PlayStatsAggregator.aggregate(month, monthlyCounts, songMeta)
                }
                _allTimeBundle.value = withContext(Dispatchers.Default) {
                    PlayStatsAggregator.aggregate("all", allTimeCounts, songMeta)
                }
            } catch (e: Exception) {
                AppLog.e("PlayStatsViewModel", "loadStats failed", e)
            } finally {
                _loading.value = false
            }
        }
    }
}

package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.data.model.PlayRecord
import com.nasmusic.tv.data.model.PlayStatistics
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 播放记录/统计域 ViewModel（R-1 拆分自 MainViewModel）：
 * 播放事件记录、Top 歌曲/歌手统计、记录加载与清除。
 *
 * 依赖：AppPreferences（经 NasMusicApp 取）。
 */
class PlayHistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val prefs = nasMusicApp.appPreferences

    // --- 播放统计 ---
    private val _playStatistics = MutableStateFlow(PlayStatistics())
    val playStatistics: StateFlow<PlayStatistics> = _playStatistics.asStateFlow()

    private val _playRecords = MutableStateFlow<List<PlayRecord>>(emptyList())
    val playRecords: StateFlow<List<PlayRecord>> = _playRecords.asStateFlow()

    /**
     * 记录播放事件（歌曲切换或播放完成时调用）
     */
    fun recordPlayEvent(song: Song, durationPlayedMs: Long) {
        if (durationPlayedMs < 5000) return // 少于 5 秒不计入
        val record = PlayRecord(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            coverUrl = song.coverUrl,
            timestamp = System.currentTimeMillis(),
            durationPlayedMs = durationPlayedMs,
            durationTotalMs = song.durationMs
        )
        viewModelScope.launch {
            prefs.history.addPlayRecord(record)
            // 更新内存中的记录列表
            _playRecords.value = listOf(record) + _playRecords.value.take(499)
            refreshPlayStatistics()
        }
    }

    /**
     * 刷新播放统计
     */
    fun refreshPlayStatistics() {
        viewModelScope.launch {
            val allRecords = _playRecords.value
            if (allRecords.isEmpty()) {
                _playStatistics.value = PlayStatistics()
                return@launch
            }

            val totalPlayTimeMs = allRecords.sumOf { it.durationPlayedMs }
            val uniqueSongs = allRecords.map { it.songId }.distinct().size

            // Top 歌曲按播放次数排序
            val songPlayCounts = allRecords.groupBy { it.songId }
                .mapValues { (_, records) -> records.size }
                .entries.sortedByDescending { it.value }.take(10)
            val topSongs = songPlayCounts.mapNotNull { (songId, _) ->
                allRecords.find { it.songId == songId }
            }

            // Top 歌手
            val artistPlayCounts = allRecords.groupBy { it.artist }
                .mapValues { (_, records) -> records.size }
                .entries
                .filter { it.key.isNotBlank() }
                .sortedByDescending { it.value }
                .take(10)
                .map { it.key to it.value }

            _playStatistics.value = PlayStatistics(
                totalPlayCount = allRecords.size,
                totalPlayTimeMs = totalPlayTimeMs,
                uniqueSongsPlayed = uniqueSongs,
                topSongs = topSongs,
                topArtists = artistPlayCounts,
                recentPlays = allRecords.take(50)
            )
        }
    }

    /**
     * 加载播放记录（应用启动时调用）
     */
    fun loadPlayRecords() {
        viewModelScope.launch {
            val records = prefs.history.getPlayRecords()
            _playRecords.value = records
            refreshPlayStatistics()
        }
    }

    /**
     * 清除播放记录
     */
    fun clearPlayRecords() {
        viewModelScope.launch {
            prefs.history.clearPlayRecords()
            _playRecords.value = emptyList()
            _playStatistics.value = PlayStatistics()
        }
    }
}

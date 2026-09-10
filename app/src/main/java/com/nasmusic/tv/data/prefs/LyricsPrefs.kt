package com.nasmusic.tv.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * R-4 歌词域子 Prefs（门面：键不迁移，DataStore 单例由 AppPreferences 持有）。
 */
class LyricsPrefs internal constructor(private val prefs: AppPreferences) {

    val lyricsFontScale: Flow<Float> = prefs.lyricsFontScale

    suspend fun setLyricsOffset(offsetMs: Long) = prefs.setLyricsOffset(offsetMs)
    suspend fun setLyricsFontScale(scale: Float) = prefs.setLyricsFontScale(scale)
    suspend fun setCacheLyrics(enabled: Boolean) = prefs.setCacheLyrics(enabled)
    suspend fun setCacheCover(enabled: Boolean) = prefs.setCacheCover(enabled)
    suspend fun setLyricsKugouBaseUrl(url: String) = prefs.setLyricsKugouBaseUrl(url)
    suspend fun setLyricsNeteaseBaseUrl(url: String) = prefs.setLyricsNeteaseBaseUrl(url)

    fun getLyricsKugouBaseUrlSync(): String = prefs.getLyricsKugouBaseUrlSync()
    fun getLyricsNeteaseBaseUrlSync(): String = prefs.getLyricsNeteaseBaseUrlSync()
}

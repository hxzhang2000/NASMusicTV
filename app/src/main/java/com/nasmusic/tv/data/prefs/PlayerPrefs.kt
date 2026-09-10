package com.nasmusic.tv.data.prefs

import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.prefs.AppPreferences.SeparationMode
import kotlinx.coroutines.flow.Flow

/**
 * R-4 播放域子 Prefs（门面：键不迁移，DataStore 单例由 AppPreferences 持有）。
 * 访问器从 AppPreferences 原样搬迁；调用方经 prefs.player.xxx 渐进迁移。
 */
class PlayerPrefs internal constructor(private val prefs: AppPreferences) {

    val pitchSemitones: Flow<Int> = prefs.pitchSemitones
    val playbackSpeed: Flow<Double> = prefs.playbackSpeed
    val separationMode: Flow<SeparationMode> = prefs.separationMode

    suspend fun setPitchSemitones(semitones: Int) = prefs.setPitchSemitones(semitones)
    suspend fun setPlaybackSpeed(speed: Double) = prefs.setPlaybackSpeed(speed)
    suspend fun setSeparationMode(mode: SeparationMode) = prefs.setSeparationMode(mode)
    suspend fun setAutoPlayNext(enabled: Boolean) = prefs.setAutoPlayNext(enabled)
    suspend fun setDefaultPlayMode(mode: PlayMode) = prefs.setDefaultPlayMode(mode)
    suspend fun setAnimationsEnabled(enabled: Boolean) = prefs.setAnimationsEnabled(enabled)
    suspend fun setDarkTheme(enabled: Boolean) = prefs.setDarkTheme(enabled)
}

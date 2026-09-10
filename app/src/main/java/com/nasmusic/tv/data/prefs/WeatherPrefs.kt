package com.nasmusic.tv.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * R-4 天气域子 Prefs（含 F-7 加密键；键不迁移）。
 */
class WeatherPrefs internal constructor(private val prefs: AppPreferences) {

    val weatherApiKey: Flow<String> = prefs.weatherApiKey

    suspend fun setWeatherApiKey(key: String) = prefs.setWeatherApiKey(key)
    fun getWeatherApiKeySync(): String = prefs.getWeatherApiKeySync()
}

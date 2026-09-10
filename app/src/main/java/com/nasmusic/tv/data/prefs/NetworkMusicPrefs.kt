package com.nasmusic.tv.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * R-4 网络音乐域子 Prefs（Meting/Jamendo/MV/默认源；键不迁移）。
 */
class NetworkMusicPrefs internal constructor(private val prefs: AppPreferences) {

    val jamendoClientIdFlow: Flow<String> = prefs.jamendoClientIdFlow

    suspend fun setMusicSource(sourceKey: String) = prefs.setMusicSource(sourceKey)
    suspend fun setJamendoClientId(id: String) = prefs.setJamendoClientId(id)
    suspend fun setMetingApiBaseUrl(url: String) = prefs.setMetingApiBaseUrl(url)
    suspend fun setMvApiBaseUrl(url: String) = prefs.setMvApiBaseUrl(url)

    fun getMusicSourceSync(): String = prefs.getMusicSourceSync()
    fun getDefaultNetworkSourceSync(): String = prefs.getDefaultNetworkSourceSync()
    fun getJamendoClientIdSync(): String = prefs.getJamendoClientIdSync()
    fun getMetingApiBaseUrlSync(): String = prefs.getMetingApiBaseUrlSync()
    fun getMvApiBaseUrlSync(): String = prefs.getMvApiBaseUrlSync()
}

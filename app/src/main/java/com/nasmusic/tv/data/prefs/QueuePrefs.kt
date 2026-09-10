package com.nasmusic.tv.data.prefs

/**
 * R-4 上次播放队列域子 Prefs（键不迁移）。
 */
class QueuePrefs internal constructor(private val prefs: AppPreferences) {

    suspend fun getLastQueue(): AppPreferences.LastQueueData? = prefs.getLastQueue()
    suspend fun saveLastQueue(songs: List<com.nasmusic.tv.data.model.Song>, currentIndex: Int) =
        prefs.saveLastQueue(songs, currentIndex)
    suspend fun clearLastQueue() = prefs.clearLastQueue()
}

package com.nasmusic.tv.data.prefs

import com.nasmusic.tv.backend.network.MetingApiService
import com.nasmusic.tv.backend.network.mv.BilibiliMvService
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

/**
 * R-4 百度网盘域子 Prefs（CloudDriveConfig 按类型存取；键不迁移）。
 */
class BaiduPrefs internal constructor(private val prefs: AppPreferences) {

    val baiduConfigFlow: Flow<com.nasmusic.tv.data.model.CloudDriveConfig> = prefs.baiduConfigFlow

    fun getBaiduConfigSync(): com.nasmusic.tv.data.model.CloudDriveConfig = prefs.getBaiduConfigSync()
    fun getCloudDriveConfigSync(type: com.nasmusic.tv.data.model.CloudDriveType): com.nasmusic.tv.data.model.CloudDriveConfig? =
        prefs.getCloudDriveConfigSync(type)

    fun saveCloudDriveConfigSync(config: com.nasmusic.tv.data.model.CloudDriveConfig) =
        prefs.saveCloudDriveConfigSync(config)

    suspend fun saveCloudDriveConfig(config: com.nasmusic.tv.data.model.CloudDriveConfig) =
        prefs.saveCloudDriveConfig(config)

    fun getBaiduTokensSync(): com.nasmusic.tv.data.model.BaiduTokens? = prefs.getBaiduTokensSync()
    fun saveBaiduTokensSync(tokens: com.nasmusic.tv.data.model.BaiduTokens) = prefs.saveBaiduTokensSync(tokens)
    fun clearBaiduTokensSync() = prefs.clearBaiduTokensSync()

    fun getBaiduEnabledSync(): Boolean = prefs.getBaiduEnabledSync()
    fun setBaiduEnabledSync(enabled: Boolean) = prefs.setBaiduEnabledSync(enabled)
    fun getBaiduMusicRootDirSync(): String = prefs.getBaiduMusicRootDirSync()
    fun setBaiduMusicRootDirSync(dir: String) = prefs.setBaiduMusicRootDirSync(dir)
    fun getBaiduMvDirSync(): String? = prefs.getBaiduMvDirSync()
    fun setBaiduMvDirSync(dir: String?) = prefs.setBaiduMvDirSync(dir)
    fun getBaiduCustomAppKeySync(): String? = prefs.getBaiduCustomAppKeySync()
    fun setBaiduCustomAppKeySync(key: String?) = prefs.setBaiduCustomAppKeySync(key)
    fun getBaiduCustomSecretKeySync(): String? = prefs.getBaiduCustomSecretKeySync()
    fun setBaiduCustomSecretKeySync(secret: String?) = prefs.setBaiduCustomSecretKeySync(secret)
}

/**
 * R-4 本地音乐/下载/导出域子 Prefs（键不迁移）。
 */
class DownloadPrefs internal constructor(private val prefs: AppPreferences) {

    suspend fun setDownloadEnabled(v: Boolean) = prefs.setDownloadEnabled(v)
    suspend fun setAutoDownloadOnPlay(v: Boolean) = prefs.setAutoDownloadOnPlay(v)
    suspend fun setAutoDownloadLimit(v: Int) = prefs.setAutoDownloadLimit(v)
    suspend fun setDownloadLocation(v: String) = prefs.setDownloadLocation(v)

    val exportTreeUri: Flow<String?> = prefs.exportTreeUri
    val exportVolumeId: Flow<String?> = prefs.exportVolumeId
    suspend fun setExportTreeUri(uri: String) = prefs.setExportTreeUri(uri)
    suspend fun setExportVolumeId(id: String) = prefs.setExportVolumeId(id)
}

/**
 * R-4 天气域子 Prefs（含 F-7 加密键；键不迁移）。
 */
class WeatherPrefs internal constructor(private val prefs: AppPreferences) {

    val weatherApiKey: Flow<String> = prefs.weatherApiKey

    suspend fun setWeatherApiKey(key: String) = prefs.setWeatherApiKey(key)
    fun getWeatherApiKeySync(): String = prefs.getWeatherApiKeySync()
}

/**
 * R-4 可视化/频谱域子 Prefs（键不迁移）。
 */
class VisualizerPrefs internal constructor(private val prefs: AppPreferences) {

    val equalizerPreset = prefs.equalizerPreset
    val equalizerBands = prefs.equalizerBands

    suspend fun setEqualizerPreset(preset: com.nasmusic.tv.data.model.EqualizerPreset) = prefs.setEqualizerPreset(preset)
    suspend fun setEqualizerBands(bands: List<Float>) = prefs.setEqualizerBands(bands)
    suspend fun setEqualizerBand(index: Int, value: Float) = prefs.setEqualizerBand(index, value)
    suspend fun setSpectrumEnabled(enabled: Boolean) = prefs.setSpectrumEnabled(enabled)
    suspend fun setVisualizerTheme(theme: com.nasmusic.tv.data.model.VisualizerTheme) = prefs.setVisualizerTheme(theme)
    suspend fun setFontAdjustment(adjustment: Int) = prefs.setFontAdjustment(adjustment)

    // 封面滤镜（Phase 5）
    val coverFilterEnabled = prefs.coverFilterEnabled
    val coverFilterBlurRadius = prefs.coverFilterBlurRadius
    val coverFilterDarkOverlay = prefs.coverFilterDarkOverlay
    suspend fun setCoverFilterEnabled(enabled: Boolean) = prefs.setCoverFilterEnabled(enabled)
    suspend fun setCoverFilterBlurRadius(radius: Float) = prefs.setCoverFilterBlurRadius(radius)
    suspend fun setCoverFilterDarkOverlay(overlay: Float) = prefs.setCoverFilterDarkOverlay(overlay)
}

/**
 * R-4 历史记录域子 Prefs（搜索历史/播放记录/最近播放；键不迁移）。
 */
class HistoryPrefs internal constructor(private val prefs: AppPreferences) {

    val searchHistory = prefs.searchHistory
    val recentSongIds = prefs.recentSongIds
    val playCounts = prefs.playCounts

    suspend fun recordSearch(query: String) = prefs.recordSearch(query)
    suspend fun recordPlayWithSong(song: com.nasmusic.tv.data.model.Song) = prefs.recordPlayWithSong(song)
    suspend fun addPlayRecord(record: com.nasmusic.tv.data.model.PlayRecord) = prefs.addPlayRecord(record)
    suspend fun getPlayRecords(): List<com.nasmusic.tv.data.model.PlayRecord> = prefs.getPlayRecords()
    suspend fun clearPlayRecords() = prefs.clearPlayRecords()
    suspend fun getRecentSongObjects(): List<com.nasmusic.tv.data.model.Song> = prefs.getRecentSongObjects()
    suspend fun purgeExpiredSearchHistory() = prefs.purgeExpiredSearchHistory()

    // 网络收藏（LRU 500）
    val networkFavorites = prefs.networkFavorites
    suspend fun toggleNetworkFavorite(item: com.nasmusic.tv.data.model.NetworkFavoriteItem) =
        prefs.toggleNetworkFavorite(item)
}

/**
 * R-4 本地歌单域子 Prefs（JSON 序列化；键不迁移）。
 */
class PlaylistPrefs internal constructor(private val prefs: AppPreferences) {

    val localPlaylists = prefs.localPlaylists

    suspend fun createLocalPlaylist(name: String) = prefs.createLocalPlaylist(name)
    suspend fun renameLocalPlaylist(id: String, newName: String) = prefs.renameLocalPlaylist(id, newName)
    suspend fun deleteLocalPlaylist(id: String) = prefs.deleteLocalPlaylist(id)
    suspend fun addSongToPlaylist(playlistId: String, song: com.nasmusic.tv.data.model.Song): Boolean =
        prefs.addSongToPlaylist(playlistId, song)
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String) =
        prefs.removeSongFromPlaylist(playlistId, songId)
}

/**
 * R-4 上次播放队列域子 Prefs（键不迁移）。
 */
class QueuePrefs internal constructor(private val prefs: AppPreferences) {

    suspend fun getLastQueue(): AppPreferences.LastQueueData? = prefs.getLastQueue()
    suspend fun saveLastQueue(songs: List<com.nasmusic.tv.data.model.Song>, currentIndex: Int) =
        prefs.saveLastQueue(songs, currentIndex)
    suspend fun clearLastQueue() = prefs.clearLastQueue()
}

/**
 * R-4 语言域子 Prefs（R-7 双写镜像；键不迁移）。
 */
class LanguagePrefs internal constructor(private val prefs: AppPreferences) {

    val language: Flow<String> = prefs.language

    suspend fun setLanguage(lang: String) = prefs.setLanguage(lang)
    fun getLanguageSync(): String = prefs.getLanguageSync()
    fun migrateLanguageMirrorIfNeeded() = prefs.migrateLanguageMirrorIfNeeded()
}

/**
 * R-4 备份域子 Prefs（键不迁移）。
 */
class BackupPrefs internal constructor(private val prefs: AppPreferences) {

    suspend fun exportBackupData(): AppPreferences.BackupData = prefs.exportBackupData()
    suspend fun importBackupData(data: AppPreferences.BackupData) = prefs.importBackupData(data)
}

package com.nasmusic.tv.data.prefs

import kotlinx.coroutines.flow.Flow

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

package com.nasmusic.tv.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * R-4 百度网盘域子 Prefs（CloudDriveConfig 按类型存取；键不迁移）。
 * T2 第二批（2026-09-14）：全部便捷方法改为 suspend + baiduConfigFlow.first()，
 * 移除 runBlocking 同步透传。getBaiduConfigSync 已删除；getCloudDriveConfigSync /
 * saveCloudDriveConfigSync 生产代码已无调用方，仅保留供单测 CloudDriveConfigTest 同步读写。
 */
class BaiduPrefs internal constructor(private val prefs: AppPreferences) {

    val baiduConfigFlow: Flow<com.nasmusic.tv.data.model.CloudDriveConfig> = prefs.baiduConfigFlow

    suspend fun getBaiduTokens(): com.nasmusic.tv.data.model.BaiduTokens? = prefs.getBaiduTokens()
    suspend fun saveBaiduTokens(tokens: com.nasmusic.tv.data.model.BaiduTokens) = prefs.saveBaiduTokens(tokens)
    suspend fun clearBaiduTokens() = prefs.clearBaiduTokens()

    suspend fun getBaiduEnabled(): Boolean = prefs.getBaiduEnabled()
    suspend fun setBaiduEnabled(enabled: Boolean) = prefs.setBaiduEnabled(enabled)
    suspend fun getBaiduMusicRootDir(): String = prefs.getBaiduMusicRootDir()
    suspend fun setBaiduMusicRootDir(dir: String) = prefs.setBaiduMusicRootDir(dir)
    suspend fun getBaiduMvDir(): String? = prefs.getBaiduMvDir()
    suspend fun setBaiduMvDir(dir: String?) = prefs.setBaiduMvDir(dir)
    suspend fun getBaiduCustomAppKey(): String? = prefs.getBaiduCustomAppKey()
    suspend fun setBaiduCustomAppKey(key: String?) = prefs.setBaiduCustomAppKey(key)
    suspend fun getBaiduCustomSecretKey(): String? = prefs.getBaiduCustomSecretKey()
    suspend fun setBaiduCustomSecretKey(secret: String?) = prefs.setBaiduCustomSecretKey(secret)
}

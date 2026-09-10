package com.nasmusic.tv.data.prefs

import kotlinx.coroutines.flow.Flow

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

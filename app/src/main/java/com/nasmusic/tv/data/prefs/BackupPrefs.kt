package com.nasmusic.tv.data.prefs

/**
 * R-4 备份域子 Prefs（键不迁移）。
 */
class BackupPrefs internal constructor(private val prefs: AppPreferences) {

    suspend fun exportBackupData(): AppPreferences.BackupData = prefs.exportBackupData()
    suspend fun importBackupData(data: AppPreferences.BackupData) = prefs.importBackupData(data)
}

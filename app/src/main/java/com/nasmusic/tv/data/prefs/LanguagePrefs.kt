package com.nasmusic.tv.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * R-4 语言域子 Prefs（R-7 双写镜像；键不迁移）。
 */
class LanguagePrefs internal constructor(private val prefs: AppPreferences) {

    val language: Flow<String> = prefs.language

    suspend fun setLanguage(lang: String) = prefs.setLanguage(lang)
    fun getLanguageSync(): String = prefs.getLanguageSync()
    fun migrateLanguageMirrorIfNeeded() = prefs.migrateLanguageMirrorIfNeeded()
}

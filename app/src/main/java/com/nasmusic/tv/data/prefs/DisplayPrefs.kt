package com.nasmusic.tv.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * 显示域子 Prefs（v2.36.0）—— 屏幕方向等**设备本地**偏好。
 *
 * 薄委托范式：真实键与逻辑在 [AppPreferences]，本类只转发（参照 [LanguagePrefs]）。
 * 与其它子 pref 一致，由 `AppPreferences.display` 惰性构造。
 */
class DisplayPrefs internal constructor(private val prefs: AppPreferences) {

    /** 屏幕方向："auto" / "portrait" / "landscape"（默认 auto） */
    val screenOrientation: Flow<String> = prefs.screenOrientation

    suspend fun setScreenOrientation(value: String) = prefs.setScreenOrientation(value)

    /** 冷启动同步读（@Volatile 镜像，零 IO）；仅供 `MainActivity.onCreate` 使用 */
    fun getScreenOrientationSync(): String = prefs.getScreenOrientationSync()
}

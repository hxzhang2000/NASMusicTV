package com.nasmusic.tv.data.prefs

import com.nasmusic.tv.backend.photo.PhotoScaleMode
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId

/**
 * 照片墙域子 Prefs（§7.3，17 个字段）。
 *
 * 薄委托范式：真实键、默认值与读写逻辑都在 [AppPreferences]，本类只转发
 * （参照 [VisualizerPrefs] / [DisplayPrefs]）。
 *
 * ## 为什么只提供 setter，不提供 Flow
 *
 * 设置页**读**值走的是 `AppSettings`（`AppPreferences.appSettings` 已经把它整份发出来，
 * `SettingsScreen` 本来就收这个参数）；只有**写**需要一个入口。
 * ⇒ 多一份 `Flow<Boolean>` 只会多一条要维护的订阅，没有任何收益。
 *
 * ## 与「三个来源开关」的关系
 *
 * `galleryEnabled` / `externalEnabled` / `jellyfinEnabled` 三者是**平级**的，
 * 「照片墙该不该出现在效果列表里」由它们的**或**派生（见 `PhotoWallAvailability`，§7.4）——
 * 本类不承担该判定，只负责存取。
 */
class PhotoWallPrefs internal constructor(private val prefs: AppPreferences) {

    // ── 照片来源 ──────────────────────────────────────────────────────────
    suspend fun setGalleryEnabled(v: Boolean) = prefs.setPhotoWallGalleryEnabled(v)
    suspend fun setExternalEnabled(v: Boolean) = prefs.setPhotoWallExternalEnabled(v)
    suspend fun setJellyfinEnabled(v: Boolean) = prefs.setPhotoWallJellyfinEnabled(v)
    suspend fun setSourceBalance(v: Boolean) = prefs.setPhotoWallSourceBalance(v)
    suspend fun setDirUri(v: String) = prefs.setPhotoWallDirUri(v)
    suspend fun setCommonDirsOnly(v: Boolean) = prefs.setPhotoWallCommonDirsOnly(v)

    // ── 显示筛选（人脸） ──────────────────────────────────────────────────
    suspend fun setFacesOnly(v: Boolean) = prefs.setPhotoWallFacesOnly(v)
    suspend fun setFaceScanDone(v: Boolean) = prefs.setPhotoWallFaceScanDone(v)

    // ── 转场效果 ──────────────────────────────────────────────────────────
    suspend fun setRandomTransition(v: Boolean) = prefs.setPhotoWallRandomTransition(v)
    suspend fun setFixedTransition(v: PhotoTransitionId) = prefs.setPhotoWallFixedTransition(v)
    suspend fun setTransitionMs(v: Int) = prefs.setPhotoWallTransitionMs(v)
    suspend fun setHoldMs(v: Int) = prefs.setPhotoWallHoldMs(v)

    // ── 画面与运动 ────────────────────────────────────────────────────────
    suspend fun setScaleMode(v: PhotoScaleMode) = prefs.setPhotoWallScaleMode(v)
    suspend fun setKenBurns(v: Boolean) = prefs.setPhotoWallKenBurns(v)
    suspend fun setAudioReactive(v: Boolean) = prefs.setPhotoWallAudioReactive(v)
    suspend fun setPulseZoom(v: Boolean) = prefs.setPhotoWallPulseZoom(v)
    suspend fun setBreathe(v: Boolean) = prefs.setPhotoWallBreathe(v)
}

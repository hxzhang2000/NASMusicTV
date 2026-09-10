package com.nasmusic.tv.data.prefs

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

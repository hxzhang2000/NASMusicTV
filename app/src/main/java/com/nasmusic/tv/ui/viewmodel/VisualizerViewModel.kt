package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.visualizer.AudioFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 全屏可视化舞台的 ViewModel。
 *
 * 状态照 `VocalSeparationViewModel` 的 `showKaraoke` 模式：
 * 不新增 Screen 枚举，而是用 StateFlow<Boolean> 驱动的全屏覆盖层。
 */
class VisualizerViewModel(
    private val app: Application,
    private val playerManager: PlayerManager,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _showVisualizer = MutableStateFlow(false)
    val showVisualizer: StateFlow<Boolean> = _showVisualizer.asStateFlow()

    private val _theme = MutableStateFlow(VisualizerTheme.Default)
    val theme: StateFlow<VisualizerTheme> = _theme.asStateFlow()

    private val _quality = MutableStateFlow(VisualQuality.Default)
    val quality: StateFlow<VisualQuality> = _quality.asStateFlow()

    /** 音频帧（来自 SpectrumRepository 单例） */
    val frame: AudioFrame get() = playerManager.spectrumRepository.frame

    private val _cover = MutableStateFlow<androidx.compose.ui.graphics.ImageBitmap?>(null)
    val cover: StateFlow<androidx.compose.ui.graphics.ImageBitmap?> = _cover.asStateFlow()

    private val _palette = MutableStateFlow(com.nasmusic.tv.visualizer.CoverPalette.Fallback)
    val palette: StateFlow<com.nasmusic.tv.visualizer.CoverPalette> = _palette.asStateFlow()

    private val paletteProvider = com.nasmusic.tv.visualizer.CoverPaletteProvider()

    /** 当前已加载封面对应的 key，避免重复加载 */
    private var loadedCoverKey: String? = null

    /**
     * 异步加载封面并取色（技法 T5）。
     * 在 IO 线程执行，未就绪时渲染层使用 [CoverPalette.Fallback]，绝不阻塞。
     */
    fun loadCover(url: String?, key: String?) {
        if (key == null || key == loadedCoverKey) {
            if (key == null) { _cover.value = null; _palette.value = com.nasmusic.tv.visualizer.CoverPalette.Fallback; loadedCoverKey = null }
            return
        }
        loadedCoverKey = key
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ib: androidx.compose.ui.graphics.ImageBitmap? = runCatching {
                val loader = coil.ImageLoader(app)
                val req = coil.request.ImageRequest.Builder(app)
                    .data(url)
                    .allowHardware(false)          // 关闭硬件位图，否则无法取色
                    .build()
                val drawable = loader.execute(req).drawable
                val bmp = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                bmp?.asImageBitmap()
            }.getOrNull()

            if (ib == null) {
                _cover.value = null
                _palette.value = com.nasmusic.tv.visualizer.CoverPalette.Fallback
                return@launch
            }
            _cover.value = ib
            _palette.value = paletteProvider.obtain(key, ib)
        }
    }

    init {
        viewModelScope.launch {
            prefs.appSettings.collect { s ->
                _theme.value = s.visualizerTheme
                _quality.value = s.visualizerQuality
            }
        }
    }

    fun enterVisualizer() {
        _showVisualizer.value = true
    }

    fun exitVisualizer() {
        _showVisualizer.value = false
    }

    /** 当前实际生效的主题名（供 Toast 显示）——用户选中哪个就恒定显示哪个 */
    fun activeThemeName(): String = _theme.value.displayName

fun nextTheme() = step(+1)
    fun prevTheme() = step(-1)

    /**
     * 长按遥控器方向键时系统会连续注入 KeyEvent repeat（约 3-5/s），
     * 每个 repeat 都触发渲染器重建 → 高频 离屏 surface 创建/销毁，
     * 弱 GPU（电视）上实测可导致 native 崩溃。此处节流到 180ms：
     * 只响应「新按键」，忽略 repeat 风暴。
     */
    private var lastStepMs = 0L

    /** 步进切换，自动跳过当前画质不支持的效果 */
    private fun step(dir: Int) {
        val now = System.currentTimeMillis()
        if (now - lastStepMs < SWITCH_DEBOUNCE_MS) return
        lastStepMs = now
        val list = VisualizerTheme.selectable
        if (list.isEmpty()) return
        val from = list.indexOf(_theme.value).let { if (it < 0) 0 else it }
        for (k in 1..list.size) {
            val idx = (from + dir * k + list.size * k) % list.size
            val cand = list[idx]
            if (_quality.value.supports(cand)) {
                _theme.value = cand
                persist()
                return
            }
        }
    }

    fun setTheme(theme: VisualizerTheme) {
        _theme.value = theme
        persist()
    }

    fun setQuality(q: VisualQuality) {
        _quality.value = q
        // 降档后当前效果可能不再支持 → 回落到默认
        if (!q.supports(_theme.value)) {
            _theme.value = VisualizerTheme.Default
        }
        viewModelScope.launch { prefs.setVisualizerQuality(q) }
    }

    private fun persist() {
        viewModelScope.launch { prefs.setVisualizerTheme(_theme.value) }
    }

override fun onCleared() {
        super.onCleared()
        _showVisualizer.value = false
    }

    private companion object {
        /** 连续按键节流：低于此间隔的（重复/连发）切换直接忽略 */
        const val SWITCH_DEBOUNCE_MS = 180L
    }
}

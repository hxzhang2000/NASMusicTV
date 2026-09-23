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
import com.nasmusic.tv.visualizer.photo.PhotoWallAvailability
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

    /**
     * 照片墙可用性（三来源开关之「或」，§7.4）。
     *
     * ⛔ 它是 `PHOTO_WALL` 是否出现在效果列表里的**唯一判据**，且必须与
     * `AppRoot.VisualizerOverlay` 传给 `VisualizerStage` 的值**完全一致**
     * （指示器与左右键切到的是同一份列表，否则会出现「指示器上没有、却切得到」）。
     * ⇒ 因此收口在这里，由 UI 层 `collectAsState()` 订阅，而不是各自算一遍。
     */
    private val _photoWallAvailable = MutableStateFlow(false)
    val photoWallAvailable: StateFlow<Boolean> = _photoWallAvailable.asStateFlow()

    /** 音频帧（来自 SpectrumRepository 单例） */
    val frame: AudioFrame get() = playerManager.spectrumRepository.frame

    private val _cover = MutableStateFlow<androidx.compose.ui.graphics.ImageBitmap?>(null)
    val cover: StateFlow<androidx.compose.ui.graphics.ImageBitmap?> = _cover.asStateFlow()

    private val _palette = MutableStateFlow(com.nasmusic.tv.visualizer.CoverPalette.Fallback)
    val palette: StateFlow<com.nasmusic.tv.visualizer.CoverPalette> = _palette.asStateFlow()

    private val paletteProvider = com.nasmusic.tv.visualizer.CoverPaletteProvider()

    /** 当前已加载封面对应的 key，避免重复加载。
     *  P1#10 修复（2026-09-13）：主线程写(63行)/IO 读(82行) 跨线程,加 @Volatile 保证可见性。 */
    @Volatile private var loadedCoverKey: String? = null

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
        val requestedKey = key
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ib: androidx.compose.ui.graphics.ImageBitmap? = runCatching {
                // 必须用 Coil 全局单例：NasMusicApp.newImageLoader() 注入了百度 dlink
                // UA 拦截器，coil.ImageLoader(app) 会新建无配置实例 → 百度网盘封面 403，
                // 且该实例从不 shutdown，泄漏线程池与缓存（同 MainViewModel 的教训）。
                val loader = coil.Coil.imageLoader(app)
                val req = coil.request.ImageRequest.Builder(app)
                    .data(url)
                    .allowHardware(false)          // 关闭硬件位图，否则无法取色
                    .build()
                val drawable = loader.execute(req).drawable
                val bmp = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                bmp?.asImageBitmap()
            }.getOrNull()

            // 快速切歌时先发的任务可能后返回：只认“仍是当前 key”的结果，
            // 否则会用上一首的封面/配色覆盖当前歌曲。
            if (requestedKey != loadedCoverKey) return@launch

            if (ib == null) {
                _cover.value = null
                _palette.value = com.nasmusic.tv.visualizer.CoverPalette.Fallback
                return@launch
            }
            _cover.value = ib
            _palette.value = paletteProvider.obtain(requestedKey, ib)
        }
    }

    init {
        viewModelScope.launch {
            prefs.appSettings.collect { s ->
                _theme.value = s.visualizerTheme
                _quality.value = s.visualizerQuality
                val available = PhotoWallAvailability.isAvailable(
                    galleryEnabled = s.photoWallGalleryEnabled,
                    externalEnabled = s.photoWallExternalEnabled,
                    jellyfinEnabled = s.photoWallJellyfinEnabled,
                )
                _photoWallAvailable.value = available

                // §7.4 实现要点 3：三来源全关且当前正显示照片墙 ⇒ **平滑切回**默认效果。
                // 不需要额外动画代码 —— 主题一变，`RendererSwapper` 的既有 crossfade 就接管了。
                // ⚠️ 这条同时兜住「冷启动时存档主题是 PHOTO_WALL 但开关已全关」的情况。
                if (!available && _theme.value == VisualizerTheme.PHOTO_WALL) {
                    _theme.value = VisualizerTheme.Default
                    persist()
                }
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
        // ⛔ 必须用「过滤后」的列表：三来源开关全关时列表里没有 PHOTO_WALL，
        //   否则左右键会切到一个画不出东西的空效果上（§7.4 实现要点 1）。
        val list = VisualizerTheme.selectable(photoWallAvailable = _photoWallAvailable.value)
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

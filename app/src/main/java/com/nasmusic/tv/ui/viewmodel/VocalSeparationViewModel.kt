package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.backend.network.mv.MvSearchManager
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 人声分离/K 歌域 ViewModel（R-1 拆分自 MainViewModel）：
 * 人声消除开关、分离模式（快速/高质量）、升降调与变速、K 歌页进出。
 *
 * 依赖：PlayerManager（人声分离控制）、AppPreferences（模式持久化）。
 */
class VocalSeparationViewModel(
    app: Application,
    private val playerManager: PlayerManager
) : AndroidViewModel(app) {

    private val prefs = (app as NasMusicApp).appPreferences

    /** 进入 K 歌/MTV 模式时启动遥控服务器的回调（由 MainViewModel 注入，避免跨域直连） */
    var onEnsureRemoteControlStarted: (() -> Unit)? = null

    // --- KARAOKE 伴奏模式（人声消除）---
    private val _vocalRemovalEnabled = MutableStateFlow(false)
    val vocalRemovalEnabled: StateFlow<Boolean> = _vocalRemovalEnabled.asStateFlow()

    fun toggleVocalRemoval() {
        val newValue = !_vocalRemovalEnabled.value

        if (newValue) {
            // 开启人声消除（伴唱模式）
            // 正在转换中，不允许重复触发
            if (playerManager.separating.value) {
                AppLog.w("VocalSeparationViewModel", "toggleVocalRemoval: separating in progress, ignored")
                return
            }
            // 快速模式：实时 DSP（始终启用，作为兜底）
            playerManager.setVocalRemovalEnabled(true)
            // 高质量模式：额外切换到伴奏文件
            if (playerManager.isHighQualityMode()) {
                playerManager.enableHighQualityRemoval()
            }
        } else {
            // 关闭人声消除（原唱模式）
            // 快速模式：关闭实时 DSP
            playerManager.setVocalRemovalEnabled(false)
            // 高质量模式：额外切换回原始文件
            if (playerManager.isHighQualityMode()) {
                playerManager.disableHighQualityRemoval()
            }
        }

        _vocalRemovalEnabled.value = newValue
        AppLog.d("VocalSeparationViewModel", "toggleVocalRemoval -> $newValue (hq=${playerManager.isHighQualityMode()})")
    }

    // --- 分离模式（快速/高质量）---
    /** 当前分离模式（同步 prefs → PlayerManager） */
    val separationMode: StateFlow<AppPreferences.SeparationMode> = prefs.player.separationMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppPreferences.SeparationMode.FAST
    ).also { flow ->
        // 启动时将持久化的模式同步到 PlayerManager
        viewModelScope.launch {
            val savedMode = flow.value
            playerManager.setSeparationMode(savedMode)
            AppLog.d("VocalSeparationViewModel", "init: loaded separationMode=$savedMode from prefs")
        }
    }
    /** 高质量分离是否正在进行（委托 PlayerManager 状态） */
    val separating: StateFlow<Boolean> = playerManager.separating
    /** 高质量分离进度与阶段描述（委托 PlayerManager 状态） */
    val separationProgress: StateFlow<Pair<Float, String>> = playerManager.separationProgress
    /** 高质量分离错误信息（非空表示最近一次失败，UI 应提示用户） */
    val hqError: StateFlow<String?> = playerManager.hqError
    /** 高质量分离成功信息（非空表示最近一次成功，UI 应提示用户） */
    val hqSuccess: StateFlow<String?> = playerManager.hqSuccess

    /** 清除高质量分离错误信息 */
    fun clearHqError() {
        playerManager.clearHqError()
    }

    /** 清除高质量分离成功信息 */
    fun clearHqSuccess() {
        playerManager.clearHqSuccess()
    }

    /** 设置分离模式（从设置页调用） */
    fun setSeparationMode(mode: AppPreferences.SeparationMode) {
        applySeparationMode(mode)
    }

    /** 切换分离模式（快速↔高质量），持久化到 AppPreferences */
    fun toggleSeparationMode() {
        val currentMode = separationMode.value
        val newMode = if (currentMode == AppPreferences.SeparationMode.FAST) {
            AppPreferences.SeparationMode.HIGH_QUALITY
        } else {
            AppPreferences.SeparationMode.FAST
        }
        applySeparationMode(newMode)
    }

    /** 删除模型后回退快速模式（由 DownloadViewModel 回调） */
    fun onModelDeleted() {
        if (separationMode.value == AppPreferences.SeparationMode.HIGH_QUALITY) {
            applySeparationMode(AppPreferences.SeparationMode.FAST)
        }
    }

    /** 统一分离模式切换逻辑 */
    private fun applySeparationMode(newMode: AppPreferences.SeparationMode) {
        // 切换到高质量模式前检查模型是否已下载
        if (newMode == AppPreferences.SeparationMode.HIGH_QUALITY && !modelDownloaded.value) {
            AppLog.w("VocalSeparationViewModel", "applySeparationMode: model not downloaded, blocked")
            return
        }

        playerManager.setSeparationMode(newMode)
        viewModelScope.launch { prefs.player.setSeparationMode(newMode) }

        if (_vocalRemovalEnabled.value) {
            // K歌模式正在伴唱，切换模式时保持伴唱状态
            if (newMode == AppPreferences.SeparationMode.HIGH_QUALITY) {
                // 快速→高质量：关闭 DSP，切换到伴奏文件
                playerManager.setVocalRemovalEnabled(false)
                playerManager.enableHighQualityRemoval()
            } else {
                // 高质量→快速：切换回原始文件，重新开启 DSP
                playerManager.disableHighQualityRemoval()
                playerManager.setVocalRemovalEnabled(true)
            }
        } else {
            // 非K歌/原唱模式，只切换模式标记
            if (newMode == AppPreferences.SeparationMode.HIGH_QUALITY) {
                // 不主动触发分离，等用户进入K歌时再分离
            } else {
                playerManager.disableHighQualityRemoval()
            }
        }
        AppLog.d("VocalSeparationViewModel", "applySeparationMode -> $newMode (vocalRemoval=${_vocalRemovalEnabled.value})")
    }

    /** 模型下载状态（镜像自 DownloadViewModel 语义：此处仅做模式切换的门槛判断） */
    private val _modelDownloaded = MutableStateFlow(false)
    val modelDownloaded: StateFlow<Boolean> = _modelDownloaded

    /** 由 MainViewModel 在模型下载状态变化时同步（DownloadViewModel 拥有事实源） */
    fun setModelDownloaded(downloaded: Boolean) {
        _modelDownloaded.value = downloaded
    }

    // --- K 歌页面：升降调 & 变速（全局记忆，重启恢复）---

    private val _pitchSemitones = MutableStateFlow(0)
    val pitchSemitones: StateFlow<Int> = _pitchSemitones.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0)
    val playbackSpeed: StateFlow<Double> = _playbackSpeed.asStateFlow()

    /** 从 AppPreferences 加载上次保存的 pitch/speed，并应用到 PlayerManager */
    fun loadPitchSpeedFromPrefs() {
        viewModelScope.launch {
            val savedPitch = prefs.player.pitchSemitones.first()
            val savedSpeed = prefs.player.playbackSpeed.first()
            _pitchSemitones.value = savedPitch
            _playbackSpeed.value = savedSpeed
            // 应用到播放器（仅在 player 已初始化时生效）
            playerManager.setPitch(savedPitch)
            playerManager.setSpeed(savedSpeed.toFloat())
            AppLog.d("VocalSeparationViewModel", "loadPitchSpeedFromPrefs: pitch=$savedPitch, speed=$savedSpeed")
        }
    }

    /** 设置升降调（半音 -12~+12）并持久化 */
    fun setPitchSemitones(semitones: Int) {
        val clamped = semitones.coerceIn(-12, 12)
        _pitchSemitones.value = clamped
        playerManager.setPitch(clamped)
        viewModelScope.launch {
            prefs.player.setPitchSemitones(clamped)
        }
        AppLog.d("VocalSeparationViewModel", "setPitchSemitones -> $clamped")
    }

    /** 设置播放速度（0.5~2.0）并持久化 */
    fun setPlaybackSpeed(speed: Double) {
        val clamped = speed.coerceIn(0.5, 2.0)
        _playbackSpeed.value = clamped
        playerManager.setSpeed(clamped.toFloat())
        viewModelScope.launch {
            prefs.player.setPlaybackSpeed(clamped)
        }
        AppLog.d("VocalSeparationViewModel", "setPlaybackSpeed -> $clamped")
    }

    /** 重置升降调到原调 */
    fun resetPitch() {
        _pitchSemitones.value = 0
        playerManager.resetPitch()
        viewModelScope.launch { prefs.player.setPitchSemitones(0) }
        AppLog.d("VocalSeparationViewModel", "resetPitch -> 0")
    }

    /** 重置播放速度到原速 */
    fun resetSpeed() {
        _playbackSpeed.value = 1.0
        playerManager.resetSpeed()
        viewModelScope.launch { prefs.player.setPlaybackSpeed(1.0) }
        AppLog.d("VocalSeparationViewModel", "resetSpeed -> 1.0")
    }

    // --- K 歌页面状态（切 Tab 时保持，退出 K 歌页时清除） ---
    private val _showKaraoke = MutableStateFlow(false)
    val showKaraoke: StateFlow<Boolean> = _showKaraoke.asStateFlow()

    /** 进入 K 歌页面：开启人声消除 + 启动遥控服务器 */
    fun enterKaraoke() {
        _showKaraoke.value = true
        if (!_vocalRemovalEnabled.value) {
            toggleVocalRemoval()
        }
        onEnsureRemoteControlStarted?.invoke()
        AppLog.d("VocalSeparationViewModel", "enterKaraoke")
    }

    /**
     * 退出 K 歌页面：清除人声消除 + 升降调 + 播放速度，恢复原唱状态。
     * 仅清理音频效果，不改变播放队列或当前歌曲。
     */
    fun exitKaraoke() {
        _showKaraoke.value = false
        // 关闭人声消除（恢复原唱）
        if (_vocalRemovalEnabled.value) {
            toggleVocalRemoval()
        }
        // 重置升降调
        if (_pitchSemitones.value != 0) {
            resetPitch()
        }
        // 重置播放速度
        if (_playbackSpeed.value != 1.0) {
            resetSpeed()
        }
        AppLog.d("VocalSeparationViewModel", "exitKaraoke: reset vocalRemoval, pitch, speed")
    }

    /** 清除伴奏缓存（设置页"缓存管理"手动清除用），返回清除数量 */
    fun clearAccompanimentCache(): Int {
        return playerManager.clearAccompanimentCache()
    }
}

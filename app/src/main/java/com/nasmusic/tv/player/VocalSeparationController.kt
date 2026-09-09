package com.nasmusic.tv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.nasmusic.tv.util.AppLog

/**
 * 人声分离控制器（R-5 提取自 PlayerManager）：
 * 管理快速 DSP（SpectralMaskProcessor）/ 高质量 ONNX（DemucsSeparator）双模式的
 * **模式状态与 DSP 开关**；重编排（HQ 分离的下载/模型加载/切源）仍由 PlayerManager
 * 执行（依赖播放状态机），经 [PlayerAdapter] 窄接口回调，避免环引用。
 *
 * 注意：本类为状态与轻逻辑层；enableHighQualityRemoval 的完整编排
 * （输入解析/临时文件/暂停恢复）保留在 PlayerManager——它与播放状态机耦合，
 * 强行搬移会造成接口爆炸（计划 R-5 的「窄接口」建议正是为此）。
 */
class VocalSeparationController {

    /** PlayerManager 注入的窄接口（只暴露切源所需方法，防强环引用） */
    interface PlayerAdapter {
        /** 切换到伴奏文件播放 */
        fun switchToAccompaniment(accompanimentPath: String)

        /** 切回原声播放 */
        fun switchToOriginal()
    }

    /** 分离模式（R-5：player 层顶层枚举，不再依赖 data.prefs） */
    private val _separationMode = MutableStateFlow(SeparationMode.FAST)
    val separationMode: StateFlow<SeparationMode> = _separationMode.asStateFlow()

    /** 快速 DSP 处理器引用（由 PlaybackService 注入） */
    private var spectralMaskProcessor: SpectralMaskProcessor? = null

    /** PlayerManager 窄接口适配（切源用） */
    private var playerAdapter: PlayerAdapter? = null

    /** 绑定 PlayerManager 窄接口（初始化期调用一次） */
    fun attachPlayerAdapter(adapter: PlayerAdapter) {
        playerAdapter = adapter
    }

    /** 注入快速 DSP 处理器（PlaybackService 创建后调用） */
    fun setSpectralMaskProcessor(processor: SpectralMaskProcessor) {
        spectralMaskProcessor = processor
    }

    /** DSP 开关（实时生效） */
    fun setVocalRemovalEnabled(enabled: Boolean) {
        spectralMaskProcessor?.setEnabled(enabled)
    }

    /** 查询 DSP 当前是否启用 */
    fun isVocalRemovalEnabled(): Boolean {
        return spectralMaskProcessor?.isEnabled() ?: false
    }

    /** 切换分离模式（状态记录；切源编排由 PlayerManager 完成后回写） */
    fun setSeparationMode(mode: SeparationMode) {
        _separationMode.value = mode
        AppLog.d("VocalSeparationController", "setSeparationMode: $mode")
    }

    /** 查询当前是否为高质量模式 */
    fun isHighQualityMode(): Boolean {
        return _separationMode.value == SeparationMode.HIGH_QUALITY
    }

    /** 清除伴奏缓存（切歌/设置页手动清除用），返回清除数量 */
    fun clearAccompanimentCache(cache: AccompanimentCache?): Int {
        val count = cache?.clearAccompaniments() ?: 0
        if (count > 0) {
            // 缓存清空后若在 HQ 模式需切回原声（经窄接口）
            playerAdapter?.switchToOriginal()
        }
        return count
    }

    fun release() {
        spectralMaskProcessor = null
        playerAdapter = null
    }
}

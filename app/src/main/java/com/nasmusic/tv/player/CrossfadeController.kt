package com.nasmusic.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 跨曲交叉淡入淡出控制器（F2-5）——双实例方案。
 *
 * 切歌窗口：主 player 旧歌淡出、crossfadePlayer 新歌淡入（50ms 步进**等功率**斜坡），
 * 窗口结束回调 onCrossfadeComplete → PlayerManager.transitionToIndex 完成切歌。
 *
 * 音量曲线（P1#3 修复，2026-09-14）：由线性斜坡改为**等功率（constant-power）**——
 * 线性斜坡下两路幅度和为 1（`out=1-frac`、`in=frac`），但人耳感知的是功率（幅度²），
 * 中段（frac≈0.5）总功率仅 `0.5²+0.5²=0.5`，听感上表现为 crossfade 中途**音量下陷**。
 * 现改为 `out=cos(frac·π/2)`、`in=sin(frac·π/2)`，两者平方和恒为 1，功率全程恒定；
 * 端点仍精确为 (1,0) → (0,1)，不改变淡入淡出的起止语义。
 *
 * 边界条件（计划 §5.3）：
 * - enabled=false / K歌 MTV 模式（suppressPlayback）/ REPEAT_ONE / 队列仅 1 首 → 不触发
 * - 下一首 streamUrl 为空（网络歌曲懒加载）→ 放弃 crossfade 走普通切换
 * - 手动切歌（next/previous）→ 立即中断进行中的 crossfade，资源彻底释放
 *
 * 音频焦点：crossfadePlayer setAudioAttributes(USAGE_MEDIA, handleAudioFocus=false)——
 * 焦点仍由主 player 独占持有。
 */
class CrossfadeController(
    private val context: Context,
    private val mainPlayerProvider: () -> ExoPlayer?,
    private val onCrossfadeComplete: (Int) -> Unit
) {
    sealed interface State {
        data object Off : State
        /** 淡入淡出进行中（剩余毫秒） */
        data class Fading(val remainingMs: Long) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    private var crossfadePlayer: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var fadeRunnable: Runnable? = null
    private var activeNextIndex: Int = -1

    /**
     * 尝试启动 crossfade（进度轮询钩子调用）。
     * 全部前置条件满足才启动，否则静默跳过（调用方走普通切歌路径）。
     *
     * @param enabled 设置开关
     * @param durationSec 时长（秒），<=0 视为关闭
     * @param suppressPlayback K歌/MTV 模式标志（true 时禁用）
     * @param repeatOne 单曲循环（同曲重叠无意义）
     * @param queueSize 队列长度
     * @param currentIndex 当前索引
     * @param nextMediaItemFactory 下一首 MediaItem 构造（streamUrl 校验通过后调用）
     */
    fun maybeStartCrossfade(
        enabled: Boolean,
        durationSec: Int,
        suppressPlayback: Boolean,
        repeatOne: Boolean,
        queueSize: Int,
        currentIndex: Int,
        nextMediaItemFactory: (nextIndex: Int) -> androidx.media3.common.MediaItem?
    ): Boolean {
        if (!enabled || durationSec <= 0) return false
        if (suppressPlayback || repeatOne) return false
        if (queueSize <= 1) return false
        if (_state.value is State.Fading) return false // 已在进行中
        val nextIndex = currentIndex + 1
        if (nextIndex >= queueSize) return false // 队尾顺序模式不 crossfade
        if (activeNextIndex == nextIndex) return false // 防重复触发

        val main = mainPlayerProvider() ?: return false
        val nextItem = nextMediaItemFactory(nextIndex) ?: return false // streamUrl 空 → 放弃
        if (nextItem.localConfiguration?.uri.toString().isNullOrBlank()) return false

        activeNextIndex = nextIndex
        try {
            val cfPlayer = createCrossfadePlayer()
            crossfadePlayer = cfPlayer
            cfPlayer.setMediaItem(nextItem)
            cfPlayer.volume = 0f
            cfPlayer.prepare()
            cfPlayer.play()

            main.volume = 1f
            val totalSteps = (durationSec * 1000) / STEP_MS
            val volumeStep = 1f / totalSteps
            var step = 0

            val runnable = object : Runnable {
                override fun run() {
                    step++
                    val frac = (step * volumeStep).coerceAtMost(1f)
                    try {
                        // P1#3：等功率曲线（sin²+cos²=1），端点仍为 (1,0)→(0,1)
                        main.volume = kotlin.math.cos(frac * HALF_PI)
                        cfPlayer.volume = kotlin.math.sin(frac * HALF_PI)
                    } catch (e: Exception) {
                        AppLog.w(TAG, "fade step failed: ${e.message}")
                        abort()
                        return
                    }
                    _state.value = State.Fading((durationSec * 1000) - step * STEP_MS)
                    if (step >= totalSteps) {
                        complete()
                    } else {
                        handler.postDelayed(this, STEP_MS)
                    }
                }
            }
            fadeRunnable = runnable
            handler.postDelayed(runnable, STEP_MS)
            AppLog.d(TAG, "crossfade started: index=$currentIndex -> $nextIndex, ${durationSec}s")
            return true
        } catch (e: Exception) {
            AppLog.e(TAG, "crossfade start failed", e)
            abort()
            return false
        }
    }

    /** 窗口结束：主播放器切到新歌（transitionToIndex）并释放 crossfade 资源 */
    private fun complete() {
        val nextIndex = activeNextIndex
        cleanupPlayer(restoreMainVolume = false) // 主 player 音量由 transitionToIndex 后恢复
        _state.value = State.Off
        activeNextIndex = -1
        if (nextIndex >= 0) {
            onCrossfadeComplete(nextIndex)
        }
        // 恢复主播放器音量（切歌完成后）
        mainPlayerProvider()?.volume = 1f
    }

    /** 立即中断（手动切歌/出错）：释放资源，不切歌 */
    fun abort() {
        cleanupPlayer(restoreMainVolume = true)
        _state.value = State.Off
        activeNextIndex = -1
    }

    private fun cleanupPlayer(restoreMainVolume: Boolean) {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
        crossfadePlayer?.let { p ->
            try {
                p.stop()
                p.release()
            } catch (_: Exception) {}
        }
        crossfadePlayer = null
        if (restoreMainVolume) {
            try { mainPlayerProvider()?.volume = 1f } catch (_: Exception) {}
        }
    }

    private fun createCrossfadePlayer(): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        return ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, false) // 焦点由主 player 独占
            .build()
    }

    /** 是否正在 crossfade */
    fun isFading(): Boolean = _state.value is State.Fading

    companion object {
        private const val TAG = "Crossfade"
        const val STEP_MS = 50L

        /** P1#3：等功率曲线的半周期（π/2），供 sin/cos 增益计算复用 */
        private val HALF_PI = (Math.PI / 2.0).toFloat()
    }
}

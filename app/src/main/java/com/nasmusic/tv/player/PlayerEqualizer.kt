package com.nasmusic.tv.player

import android.media.audiofx.Equalizer
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nasmusic.tv.util.AppLog

/**
 * 均衡器与频谱管理（N-4 提取自 PlayerManager）：
 * 管理 Equalizer 实例生命周期、SpectrumAnalyzer 的音频会话绑定与重试。
 *
 * 播放器实例由调用方（PlayerManager）按需传入，本类不持有引用；
 * 频谱重试复用 PlayerManager 的主线程 Handler（与原实现一致）。
 */
// UnstableApi 属 androidx @RequiresOptIn 机制，须用 androidx.annotation.OptIn。
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerEqualizer(private val retryHandler: Handler) {

    companion object {
        // 沿用原 PlayerManager 日志 tag，保持 logcat 过滤习惯不变
        private const val TAG = "PlayerManager"
    }

    private var equalizer: Equalizer? = null
    private var audioSessionId: Int = 0

    private val spectrumAnalyzer = SpectrumAnalyzer()

    /**
     * 频谱数据仓库——全屏可视化舞台的数据源（[AudioFrame] 唯一写入方）。
     * 由外部（NasMusicApp）注入，保证与 UI 层共用同一实例。
     */
    var spectrumRepository: com.nasmusic.tv.visualizer.SpectrumRepository? = null
        set(value) {
            field = value
            spectrumAnalyzer.repository = value
        }

    /**
     * P6 降级通道（PCM自算频谱）—— 由 PlayerManager 注入，
     * 透传给 SpectrumAnalyzer 做仲裁（Visualizer 持续全 0 时才启用）。
     */
    var pcmFallback: com.nasmusic.tv.player.PcmFallbackChannel? = null
        set(value) {
            field = value
            spectrumAnalyzer.pcmFallback = value
        }

    /** 播放状态：降级仲裁需要区分“暂停”与“音频真静音” */
    fun setPlaying(playing: Boolean) {
        spectrumAnalyzer.isPlaying = playing
    }

    /** 供 UI 读取的实时帧（可能为 null，表示尚未注入仓库） */
    val visualizerFrame: com.nasmusic.tv.visualizer.AudioFrame?
        get() = spectrumRepository?.frame

    /**
     * 初始化均衡器（在 setPlayer 之后调用）
     */
    fun initEqualizer(player: ExoPlayer?): Boolean {
        return try {
            val p = player ?: return false
            audioSessionId = p.audioSessionId
            if (audioSessionId == 0) return false

            // Release old equalizer if exists
            equalizer?.release()
            equalizer = Equalizer(0, audioSessionId)
            equalizer?.enabled = true
            AppLog.d(TAG, "initEqualizer: initialised for session $audioSessionId")

            // 初始化频谱分析器
            initSpectrumAnalyzer(player)
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "initEqualizer failed", e)
            false
        }
    }

    /**
     * 会话变更兜底：ExoPlayer 重建 / 切轨可能更换 audioSessionId，
     * 若均衡器仍绑定旧会话则静默失效。此方法在会话变化时重建均衡器。
     *
     * @return 均衡器当前是否可用
     */
    fun ensureEqualizerForSession(player: ExoPlayer?): Boolean {
        val sid = player?.audioSessionId ?: 0
        if (sid <= 0) return equalizer != null
        if (equalizer == null || sid != audioSessionId) {
            AppLog.d(TAG, "ensureEqualizerForSession: session changed ($audioSessionId -> $sid), re-init")
            return initEqualizer(player)
        }
        return true
    }

    /**
     * 初始化频谱分析器（使用当前音频会话 ID）
     *
     * 如果音频会话尚未就绪（audioSessionId == 0），
     * 在后续 5 秒内每秒重试一次。
     */
    private var spectrumAnalyzerRetryCount = 0

    fun initSpectrumAnalyzer(player: ExoPlayer?) {
        val sessionId = player?.audioSessionId ?: 0
        if (sessionId > 0) {
            audioSessionId = sessionId
            spectrumAnalyzerRetryCount = 0
            AppLog.d(TAG, "initSpectrumAnalyzer: attaching to session $sessionId")
            spectrumAnalyzer.attach(sessionId)
        } else if (spectrumAnalyzerRetryCount < 5) {
            spectrumAnalyzerRetryCount++
            AppLog.w(TAG,
                "initSpectrumAnalyzer: no valid audio session yet, " +
                "retry ${spectrumAnalyzerRetryCount}/5 in 1s")
            retryHandler.postDelayed({
                initSpectrumAnalyzer(player)
            }, 1000)
        } else {
            AppLog.w(TAG, "initSpectrumAnalyzer: gave up after ${spectrumAnalyzerRetryCount} retries")
        }
    }

    /**
     * 设置指定频段的增益值
     * @param bandIndex 频段索引 (0-based)
     * @param gainDb 增益值 (dB, 通常 -15 到 +15)
     */
    fun setEqualizerBand(player: ExoPlayer?, bandIndex: Int, gainDb: Float): Boolean {
        return try {
            val eq = equalizer
            if (eq == null) {
                if (!initEqualizer(player)) return false
            }
            val bands = equalizer?.numberOfBands ?: return false
            if (bandIndex < 0 || bandIndex >= bands) return false
            val gainMillibels = (gainDb * 100).toInt().toShort()
            equalizer?.setBandLevel(bandIndex.toShort(), gainMillibels)
            AppLog.d(TAG, "setEqualizerBand: band=$bandIndex gain=${gainDb}dB")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "setEqualizerBand failed", e)
            false
        }
    }

    /**
     * 批量设置所有频段增益值（用于应用预设）
     * @param gains 各频段增益值数组 (dB)，数组长度需与设备频段数匹配
     */
    fun setEqualizerBands(player: ExoPlayer?, gains: List<Float>): Boolean {
        return try {
            val eq = equalizer
            if (eq == null) {
                if (!initEqualizer(player)) return false
            }
            val eqInstance = equalizer ?: return false
            val bandCount = eqInstance.numberOfBands.toInt()
            val range = eqInstance.bandLevelRange
            val minLevel = range[0]
            val maxLevel = range[1]

            for (i in 0 until minOf(bandCount, gains.size)) {
                val gainMb = (gains[i] * 100).toInt().toShort()
                val clamped = gainMb.coerceIn(minLevel, maxLevel)
                eqInstance.setBandLevel(i.toShort(), clamped)
            }
            AppLog.d(TAG, "setEqualizerBands: applied ${minOf(bandCount, gains.size)} bands")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "setEqualizerBands failed", e)
            false
        }
    }

    /**
     * 获取当前频段增益值
     */
    fun getEqualizerBandLevel(bandIndex: Int): Float {
        return try {
            val eq = equalizer ?: return 0f
            val level = eq.getBandLevel(bandIndex.toShort())
            level / 100f
        } catch (e: Exception) {
            0f
        }
    }

    /**
     * 获取均衡器频段数量
     */
    fun getEqualizerBandCount(): Int {
        return try {
            equalizer?.numberOfBands?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 获取频段中心频率（Hz）
     */
    fun getEqualizerCenterFreq(bandIndex: Int): Int {
        return try {
            // getCenterFreq 返回 int，无需 toInt()（消除冗余转换告警）
            equalizer?.getCenterFreq(bandIndex.toShort()) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 禁用均衡器
     */
    fun disableEqualizer() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
            equalizer = null
            AppLog.d(TAG, "disableEqualizer: disabled")
        } catch (e: Exception) {
            AppLog.e(TAG, "disableEqualizer failed", e)
        }
    }

    /**
     * 释放资源：停止频谱重试、释放均衡器与频谱分析器
     */
    fun release() {
        // 停止频谱重试（将所有回调从消息队列中移除）
        spectrumAnalyzerRetryCount = 5
        equalizer?.release()
        equalizer = null
        // 终止降级通道的后台 FFT 线程，避免服务销毁后残留
        pcmFallback?.deactivate()
        spectrumAnalyzer.release()
    }
}

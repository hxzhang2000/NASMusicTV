package com.nasmusic.tv.player

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.nasmusic.tv.util.AppLog
import kotlin.math.cos

/**
 * PCM 频谱抽取器 —— P6 降级通道的**分析侧**。
 *
 * 后台线程每 [INTERVAL_MS]（40ms ≈ 25Hz）从 [PcmRingBuffer] 取 [FFT_SIZE] 点，
 * 加 Hann 窗后做 FFT，把幅值谱交给 [onMagnitudes]。
 *
 * **为什么必须独立线程**：PCM 数据由 `AudioProcessor.queueInput()` 在**播放线程**写入，
 * 若在那里做 FFT（> 1ms）会阻塞音频输出 → 爆音。方案 §3.5 的硬性要求。
 *
 * 回调在后台线程执行，消费方（`SpectrumAnalyzer`）需自行保证线程安全。
 */
class PcmSpectrumTap(private val ring: PcmRingBuffer) {

    /** 幅值谱回调：(magnitudes, numBins, sampleRate)。在 FFT 线程执行 */
    @Volatile
    var onMagnitudes: ((FloatArray, Int, Int) -> Unit)? = null

    /** 由 [PcmTapProcessor.configure] 写入实际采样率 */
    @Volatile
    var sampleRate: Int = 44100

    /** Hann 窗（预分配，避免每次 cos 计算） */
    private val window = FloatArray(FFT_SIZE).also { w ->
        val denom = (FFT_SIZE - 1).coerceAtLeast(1)
        for (i in w.indices) {
            w[i] = (0.5 - 0.5 * cos(2.0 * Math.PI * i / denom)).toFloat()
        }
    }
    private val samples = FloatArray(FFT_SIZE)
    private val windowed = FloatArray(FFT_SIZE)
    private val magnitudes = FloatArray(FFT_SIZE / 2)
    private val fft = Radix2Fft(FFT_SIZE)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var running = false

    private var lastVersion = -1L

    private val pump = object : Runnable {
        override fun run() {
            if (!running) return
            pumpOnce()
            handler?.postDelayed(this, INTERVAL_MS)
        }
    }

    /** 启动分析线程（幂等） */
    fun start() {
        if (running) return
        running = true
        lastVersion = -1L
        try {
            val t = HandlerThread("PcmSpectrumFft", Process.THREAD_PRIORITY_BACKGROUND)
            t.start()
            thread = t
            val h = Handler(t.looper)
            handler = h
            h.post(pump)
            AppLog.d(TAG, "started (fft=$FFT_SIZE, interval=${INTERVAL_MS}ms)")
        } catch (e: Exception) {
            running = false
            thread = null
            handler = null
            AppLog.e(TAG, "start failed", e)
        }
    }

    /** 停止分析线程（幂等） */
    fun stop() {
        if (!running) return
        running = false
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
        AppLog.d(TAG, "stopped")
    }

    val isRunning: Boolean get() = running

    private fun pumpOnce() {
        // 无新数据（暂停 / 已停止喂数据）→ 跳过，避免重复分析同一段旧数据
        val v = ring.version()
        if (v == lastVersion) return
        lastVersion = v

        if (!ring.readLatest(samples)) return   // 数据不足一窗（刚启动）

        val w = window
        for (i in samples.indices) windowed[i] = samples[i] * w[i]
        fft.magnitude(windowed, magnitudes)

        onMagnitudes?.invoke(magnitudes, FFT_SIZE / 2, sampleRate)
    }

    companion object {
        private const val TAG = "PcmSpectrumTap"

        /** FFT 长度：1024（方案 §3.5，自实现 < 1ms） */
        const val FFT_SIZE = 1024

        /** 分析周期：40ms ≈ 25Hz */
        const val INTERVAL_MS = 40L
    }
}

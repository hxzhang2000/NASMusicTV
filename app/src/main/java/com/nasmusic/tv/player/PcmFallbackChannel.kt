package com.nasmusic.tv.player

/**
 * PCM 降级通道（P6）—— 把采集侧（`AudioProcessor`）与分析侧（后台 FFT）打包成一个可启停单元。
 *
 * 由 `PlaybackService` 创建：`processor` 挂到 AudioSink 处理器链最前，
 * 整个对象注入 `PlayerManager`，最终交给 `SpectrumAnalyzer` 做仲裁。
 */
class PcmFallbackChannel {

    /** 环形缓冲：8192 样本 ≈ 186ms @44.1kHz —— 足够 FFT(1024) 取窗且留足余量 */
    private val ring = PcmRingBuffer(CAPACITY)

    /** 分析侧（后台 HandlerThread + radix-2 FFT） */
    private val tap = PcmSpectrumTap(ring)

    /** 采集侧：挂到 `DefaultAudioSink.Builder.setAudioProcessors()` */
    val processor: PcmTapProcessor = PcmTapProcessor(ring, tap)

    /** 注册幅值谱回调（**在 FFT 后台线程执行**，消费方需线程安全） */
    fun setOnMagnitudes(block: (FloatArray, Int, Int) -> Unit) {
        tap.onMagnitudes = block
    }

    /** 激活：开始采集 + 启动 FFT 线程 */
    fun activate() {
        processor.capturing = true
        tap.start()
    }

    /** 停用：停止采集与 FFT 线程 */
    fun deactivate() {
        tap.stop()
        processor.capturing = false
    }

    val isActive: Boolean get() = tap.isRunning

    companion object {
        /** 8192 = 2^13 */
        const val CAPACITY = 8192
    }
}

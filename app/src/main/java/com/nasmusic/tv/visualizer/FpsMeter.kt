package com.nasmusic.tv.visualizer

/**
 * 逐帧帧率计（真机验收用，方案 §11.3.3）。
 *
 * 口径 = **Compose 帧回调的实际到点率**：绘制循环是 `while(true) withFrameNanos {}`，
 * 回调被上一帧的绘制与渲染线程排队顶在后面 ⇒ 回调间隔 ≈ 实际上屏间隔。
 * 与 `dumpsys SurfaceFlinger --latency` 的差别只在采样时刻，量级一致。
 *
 * ⚠️ **滚动窗口**，不是累计平均 —— 切换效果后 0.5 s 内就反映新效果的帧率。
 * 零分配：只有 3 个标量字段。
 */
class FpsMeter(private val windowNs: Long = DEFAULT_WINDOW_NS) {

    /** 最近一个完整窗口的帧率；不足一个窗口时为 0（调用方显示 `--`）。 */
    var fps: Float = 0f
        private set

    private var anchorNs = -1L
    private var count = 0

    /**
     * @param nowNs [android.view.Choreographer] 的帧时间戳（单调、纳秒）
     * @return true = 本帧闭合了一个窗口（[fps] 已刷新，调用方值得把读数推到 UI）
     */
    fun onFrame(nowNs: Long): Boolean {
        if (anchorNs < 0L) {
            anchorNs = nowNs
            count = 1
            return false
        }
        count++
        val span = nowNs - anchorNs
        if (span < windowNs) return false
        fps = (count - 1) * 1_000_000_000f / span
        anchorNs = nowNs
        count = 1
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_NS = 500_000_000L
    }
}

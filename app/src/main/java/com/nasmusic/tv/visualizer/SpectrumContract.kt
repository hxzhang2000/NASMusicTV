package com.nasmusic.tv.visualizer

/**
 * 频谱数据链路的全局常量契约。
 *
 * 渲染侧**不得**自行指定柱数，一律取 `AudioFrame.spectrum.size`，
 * 从根本上杜绝「请求 96 柱 / 实际 32 柱」这类错位（历史 BUG ①）。
 */
object SpectrumContract {

    /** 频谱柱数：64（原 32，翻倍以支撑更细腻的效果） */
    const val BAR_COUNT = 64

    /** 波形采样点数 */
    const val WAVE_POINTS = 128

    /** Visualizer 回调周期（微秒）：20ms → 50Hz 采样。
     *  原为 50000µs（20Hz），而 1024 点窗口仅覆盖 23ms，
     *  每个周期漏掉 54% 的音频，鼓点 attack（5–10ms）极易整拍漏掉。 */
    const val CAPTURE_INTERVAL_US = 20_000

    /** 渲染节流：33ms ≈ 30fps。节拍检测仍使用全部 50Hz 帧 */
    const val EMIT_INTERVAL_MS = 33L

    /** 柱子最小可见幅值 */
    const val MIN_AMPLITUDE = 0.02f

    // ── 频段边界（64 柱感知翘曲映射后）──────────────────────────
    /** 20–250 Hz：底鼓 / 贝斯 —— 节拍主源 */
    const val BASS_END = 39
    /** 250 Hz–3 kHz：军鼓 / 人声 */
    const val MID_END = 55
    /** 3 k–20 kHz：镲片（至 63） */
    const val TREBLE_END = 63

    /** AGC 峰值衰减系数：每帧 0.5% ≈ 3s 时间常数 */
    const val AGC_DECAY = 0.995f

    /** AGC 分母下限，防止除零与长期静音后爆增 */
    const val AGC_FLOOR = 0.05f

    /** 显示通道 gamma：^0.75，抬升小信号保证可见 */
    const val DISPLAY_GAMMA = 0.75f
}

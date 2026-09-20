// 由 extract.py 自动生成。DemucsSeparator 桩类，内含抽取出的 LinearResampler，
// 目的是让真实的 app/src/test/.../LinearResamplerTest.kt 能在独立 JVM 上运行。
package com.nasmusic.tv.player

class DemucsSeparator {
/**
 * 流式线性插值重采样器（立体声，输入输出均为归一化 float）。
 *
 * 为什么需要它：MediaCodec 解码不会重采样，`codec.configure` 也无法改写
 * `KEY_SAMPLE_RATE`，所以 48kHz 源会原样进入按 44100Hz 设计的 Demucs 模型。
 * 若同时把 WAV 头硬编码成 44100，用户听到的伴奏会变速变调（48kHz 源缩短 8.8%、
 * 音高升高约 1.5 个半音）。
 *
 * 实现：输出帧 k 对应输入坐标 `k * ratio`（ratio = inRate / outRate），在该坐标
 * 相邻两个输入帧之间做线性插值。用「输出序号 × ratio」而非「累加 ratio」计算坐标，
 * 避免长曲目（千万帧级）上的浮点累积漂移。
 *
 * 不变式：push 第 i 帧（i 从 0 起）时，所有坐标 < i-1 的输出都已发出，因此本次
 * 只需 prev = v[i-1] 与 cur = v[i] 两个样本即可覆盖坐标区间 [i-1, i)。
 *
 * 说明：线性插值在降采样（如 48k→44.1k）时不做抗混叠滤波，22kHz 以上的镜像分量
 * 会被折叠进来。音乐内容在该频段能量极低（有损编码通常 20kHz 就截止），实际影响
 * 可忽略；换来的是零依赖、可预测的实现，且比「喂错采样率给模型」好得多。
 *
 * 可见性为 `internal`（而非 `private`）的唯一原因：让 `LinearResamplerTest` 能直接
 * 覆盖它。本机 Gradle 测试 worker 无法启动，该测试只在 CI 上跑。
 */
internal class LinearResampler(
    inRate: Int,
    outRate: Int,
    private val sink: (Float, Float) -> Unit
) {
    private val ratio = inRate.toDouble() / outRate.toDouble()

    private var prevL = 0f
    private var prevR = 0f
    private var curL = 0f
    private var curR = 0f
    /** 最近一次 push 的输入帧索引，-1 表示尚未收到任何帧 */
    private var inIndex = -1L
    /** 已产出的输出帧数 */
    private var outIndex = 0L

    fun push(l: Float, r: Float) {
        prevL = curL
        prevR = curR
        curL = l
        curR = r
        inIndex++
        if (inIndex == 0L) {
            // 首帧没有前驱样本，prev 与 cur 都取它自身
            prevL = l
            prevR = r
            return
        }
        // 发出坐标落在 [inIndex-1, inIndex) 的输出（frac ∈ [0,1)，取 prev/cur 插值）
        while (outIndex * ratio < inIndex.toDouble()) {
            val frac = (outIndex * ratio - (inIndex - 1)).toFloat()
            sink(prevL + (curL - prevL) * frac, prevR + (curR - prevR) * frac)
            outIndex++
        }
    }

    /** 冲刷尾部：末帧之后的输出样本只能钳制到最后一个输入样本 */
    fun flush() {
        if (inIndex < 0L) return
        val last = inIndex.toDouble()
        while (outIndex * ratio <= last) {
            val frac = (outIndex * ratio - (last - 1)).toFloat().coerceIn(0f, 1f)
            sink(prevL + (curL - prevL) * frac, prevR + (curR - prevR) * frac)
            outIndex++
        }
    }
}
}

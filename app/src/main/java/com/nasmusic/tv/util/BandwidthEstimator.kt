package com.nasmusic.tv.util

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 下行带宽估计器（F2-6）：滑动窗口记录 HTTP 下载字节数/耗时，
 * 供网络源音质分级 AUTO 档决策（>10Mbps 无损 / 2-10Mbps 320k / <2Mbps 128k）。
 *
 * 数据源：OkHttp 拦截器无侵入统计（调用方在 response body 读取后调用 [record]）。
 * 窗口 30 秒 + 最近 32 条样本；NetworkMonitor 断网事件调用 [reset] 清零。
 */
class BandwidthEstimator(
    private val windowMs: Long = 30_000,
    private val maxSamples: Int = 32,
    private val nowMsProvider: () -> Long = System::currentTimeMillis
) {
    private data class Sample(val bytes: Long, val durationMs: Long, val atMs: Long)

    private val samples = ConcurrentLinkedDeque<Sample>()

    /** 记录一次传输样本 */
    fun record(bytes: Long, durationMs: Long) {
        if (bytes <= 0 || durationMs <= 0) return
        samples.addLast(Sample(bytes, durationMs, nowMsProvider()))
        evictExpired()
    }

    /** 断网/重置：清空窗口 */
    fun reset() = samples.clear()

    /** 窗口内平均带宽（bps）；无样本返回 null */
    fun estimateBps(): Long? {
        evictExpired()
        if (samples.isEmpty()) return null
        val totalBytes = samples.sumOf { it.bytes }
        val totalMs = samples.sumOf { it.durationMs }
        if (totalMs <= 0) return null
        return totalBytes * 8 * 1000 / totalMs
    }

    private fun evictExpired() {
        val cutoff = nowMsProvider() - windowMs
        while (samples.isNotEmpty() && samples.first().atMs < cutoff) {
            samples.removeFirst()
        }
        while (samples.size > maxSamples) {
            samples.removeFirst()
        }
    }

    companion object {
        /** 带宽 → Meting br 档位（AUTO 模式决策） */
        fun tierForBandwidth(bps: Long?): Int = when {
            bps == null -> 320          // 无数据保守取 320
            bps > 10_000_000 -> 999     // >10Mbps 无损
            bps > 2_000_000 -> 320      // 2-10Mbps 高音质
            else -> 128                 // <2Mbps 标准
        }
    }
}

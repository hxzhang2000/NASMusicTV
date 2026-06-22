package com.nasmusic.tv.util

import kotlinx.coroutines.delay
import kotlin.math.pow

/**
 * B-12: 指数退避重试策略配置
 */
data class RetryConfig(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 30_000L,
    val factor: Double = 2.0
)

/**
 * 使用指数退避执行可重试操作。
 *
 * @param config  重试策略，默认最多 3 次，1s→2s→4s 间隔
 * @param onError 每次失败时的回调（可用于日志）
 * @param block   需要重试的操作（抛出 [Exception] 触发重试）
 * @return 操作成功后的结果
 * @throws Exception 所有重试用完后最后一次抛出的异常
 */
suspend fun <T> withRetry(
    config: RetryConfig = RetryConfig(),
    onError: ((attempt: Int, exception: Exception) -> Unit)? = null,
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    for (attempt in 1..config.maxAttempts) {
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            onError?.invoke(attempt, e)
            if (attempt < config.maxAttempts) {
                val delayMs = calculateBackoff(attempt, config).coerceAtMost(config.maxDelayMs)
                delay(delayMs)
            }
        }
    }
    throw lastException ?: IllegalStateException("retry exhausted with no exception")
}

/**
 * 计算第 [attempt] 次重试的等待延迟。
 */
private fun calculateBackoff(attempt: Int, config: RetryConfig): Long =
    (config.baseDelayMs * config.factor.pow((attempt - 1).toDouble())).toLong()

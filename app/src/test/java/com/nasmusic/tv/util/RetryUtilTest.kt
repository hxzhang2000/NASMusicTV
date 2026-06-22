package com.nasmusic.tv.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * B-12: RetryUtil 单元测试
 */
class RetryUtilTest {

    @Test
    fun `withRetry succeeds on first attempt`() = runTest {
        val result = withRetry { "success" }
        assertEquals("success", result)
    }

    @Test
    fun `withRetry succeeds after retries`() = runTest {
        var attempts = 0
        val result = withRetry(
            config = RetryConfig(maxAttempts = 3, baseDelayMs = 10L)
        ) {
            attempts++
            if (attempts < 3) throw RuntimeException("attempt $attempts failed")
            "finally"
        }
        assertEquals("finally", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `withRetry exhausts all attempts and throws`() = runTest {
        var attempts = 0
        val exception = assertFailsWith<RuntimeException> {
            withRetry(
                config = RetryConfig(maxAttempts = 3, baseDelayMs = 10L)
            ) {
                attempts++
                throw RuntimeException("always fail")
            }
        }
        assertEquals("always fail", exception.message)
        assertEquals(3, attempts)
    }

    @Test
    fun `withRetry calls onError for each failure`() = runTest {
        val errors = mutableListOf<Int>()
        assertFailsWith<RuntimeException> {
            withRetry(
                config = RetryConfig(maxAttempts = 3, baseDelayMs = 10L),
                onError = { attempt, _ -> errors.add(attempt) }
            ) {
                throw RuntimeException("fail")
            }
        }
        assertEquals(listOf(1, 2, 3), errors)
    }

    @Test
    fun `withRetry single attempt does not retry`() = runTest {
        var attempts = 0
        assertFailsWith<RuntimeException> {
            withRetry(
                config = RetryConfig(maxAttempts = 1),
                onError = { _, _ -> attempts++ }
            ) {
                throw RuntimeException("fail")
            }
        }
        // onError is still called once for the single attempt
        assertEquals(1, attempts)
    }

    @Test
    fun `withRetry default config has maxAttempts 3`() {
        val config = RetryConfig()
        assertEquals(3, config.maxAttempts)
        assertEquals(1_000L, config.baseDelayMs)
        assertEquals(30_000L, config.maxDelayMs)
        assertEquals(2.0, config.factor, 0.001)
    }

    @Test
    fun `withRetry custom config`() = runTest {
        var attempts = 0
        assertFailsWith<RuntimeException> {
            withRetry(
                config = RetryConfig(maxAttempts = 5, baseDelayMs = 5L, maxDelayMs = 100L, factor = 1.5),
                onError = { _, _ -> attempts++ }
            ) {
                throw RuntimeException("fail")
            }
        }
        assertEquals(5, attempts)
    }

    @Test
    fun `withRetry immediate success no delay`() = runTest {
        var startTime = 0L
        // Just verify it completes quickly
        val result = withRetry(config = RetryConfig(maxAttempts = 1)) {
            "fast"
        }
        assertEquals("fast", result)
    }

    @Test
    fun `RetryConfig equals and hashCode`() {
        val a = RetryConfig(maxAttempts = 5, baseDelayMs = 100L)
        val b = RetryConfig(maxAttempts = 5, baseDelayMs = 100L)
        val c = RetryConfig(maxAttempts = 3)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a.equals(c))
    }
}

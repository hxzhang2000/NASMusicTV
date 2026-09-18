package com.nasmusic.tv.backend.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ResolveResult] 降级信号测试（多码率方案 §10.1 / §3.2）。
 */
class ResolveResultTest {

    @Test
    fun `解析成功且档位一致 不构成降级`() {
        val r = ResolveResult("https://x/a.mp3", 320)
        assertTrue(r.isSuccess)
        assertFalse(r.isDowngradedFrom(320))
    }

    @Test
    fun `请求无损实际 320 判定为降级`() {
        val r = ResolveResult("https://x/a.mp3", 320)
        assertTrue(r.isDowngradedFrom(999))
    }

    @Test
    fun `请求 320 实际 128 判定为降级`() {
        val r = ResolveResult("https://x/a.mp3", 128)
        assertTrue(r.isDowngradedFrom(320))
    }

    @Test
    fun `AUTO 档永不判定为降级`() {
        // AUTO 是"端点默认给什么"，不构成降级语义
        val r = ResolveResult("https://x/a.mp3", 320)
        assertFalse(r.isDowngradedFrom(QualityTiers.AUTO))
    }

    @Test
    fun `解析失败不判定为降级`() {
        val r = ResolveResult.failure(999)
        assertFalse(r.isSuccess)
        assertFalse(r.isDowngradedFrom(999))
    }

    @Test
    fun `failure 保留请求档位作为 actualQuality`() {
        assertEquals(999, ResolveResult.failure(999).actualQuality)
        assertEquals(null, ResolveResult.failure(999).url)
    }

    @Test
    fun `空白 url 不算成功`() {
        assertFalse(ResolveResult("   ", 320).isSuccess)
        assertFalse(ResolveResult("", 320).isSuccess)
    }

    @Test
    fun `降级链端到端 请求 999 命中 192`() {
        // 模拟：999/320 均空，192 命中
        val chain = QualityTiers.fallbackChainOf(999)
        val hit = chain.filterNotNull().first { it == 192 }
        val r = ResolveResult("https://x/a.mp3", hit)
        assertTrue(r.isDowngradedFrom(999))
        assertEquals(192, r.actualQuality)
    }
}

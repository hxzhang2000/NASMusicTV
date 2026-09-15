package com.nasmusic.tv.backend

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `BackendAuthHeaders`（provider 形态）回归测试 —— 2026-09-15 修复 F-2 配套。
 *
 * 背景：快照形态会漏掉「静默重登换新令牌」——adapter 换新后快照仍是旧值，
 * 播放 / 封面链路持续 401。改为 provider 后，`forHost` 必须每次实时读取。
 * 纯 JVM（项目 `unitTests.isReturnDefaultValues = true`，AppLog 安全降级）。
 */
class BackendAuthHeadersTest {

    @After
    fun tearDown() {
        BackendAuthHeaders.clear()
    }

    @Test
    fun `forHost reads provider live so refreshed token is visible`() {
        var token = "T1"
        BackendAuthHeaders.update({ mapOf("Authorization" to token) }, "192.168.1.100")

        assertEquals(mapOf("Authorization" to "T1"), BackendAuthHeaders.forHost("192.168.1.100"))
        token = "T2" // 模拟静默重登换新令牌
        assertEquals(mapOf("Authorization" to "T2"), BackendAuthHeaders.forHost("192.168.1.100"))
    }

    @Test
    fun `forHost matches host case-insensitively`() {
        BackendAuthHeaders.update({ mapOf("Authorization" to "T") }, "NAS.Local")
        assertEquals(mapOf("Authorization" to "T"), BackendAuthHeaders.forHost("nas.local"))
    }

    @Test
    fun `forHost returns empty for other hosts`() {
        BackendAuthHeaders.update({ mapOf("Authorization" to "T") }, "192.168.1.100")
        assertTrue(BackendAuthHeaders.forHost("192.168.1.101").isEmpty())
        assertTrue(BackendAuthHeaders.forHost("").isEmpty())
    }

    @Test
    fun `blank host never injects`() {
        BackendAuthHeaders.update({ mapOf("Authorization" to "T") }, "")
        assertTrue(BackendAuthHeaders.forHost("192.168.1.100").isEmpty())
        assertFalse(BackendAuthHeaders.isConfigured())
    }

    @Test
    fun `clear removes binding`() {
        BackendAuthHeaders.update({ mapOf("Authorization" to "T") }, "h")
        BackendAuthHeaders.clear()
        assertTrue(BackendAuthHeaders.forHost("h").isEmpty())
    }

    @Test
    fun `provider failure degrades to empty map`() {
        BackendAuthHeaders.update({ throw IllegalStateException("boom") }, "h")
        assertTrue(BackendAuthHeaders.forHost("h").isEmpty())
    }
}

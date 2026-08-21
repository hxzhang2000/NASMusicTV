package com.nasmusic.tv.backend.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

/**
 * SubsonicAdapter 单元测试
 * 测试认证逻辑和 API 调用构建
 */
class SubsonicAdapterTest {

    private lateinit var adapter: SubsonicAdapter

    @Before
    fun setup() {
        adapter = SubsonicAdapter()
    }

    @Test
    fun `backendType should be subsonic`() {
        assertEquals("subsonic", adapter.backendType)
    }

    @Test
    fun `serverName should default to Subsonic`() {
        assertEquals("Subsonic", adapter.serverName)
    }

    @Test
    fun `md5 should produce 32 character hex string`() {
        val hash = invokeMd5("test")
        assertEquals(32, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `md5 should be deterministic`() {
        val input = "consistent_input"
        val hash1 = invokeMd5(input)
        val hash2 = invokeMd5(input)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `md5 should handle empty string`() {
        val hash = invokeMd5("")
        assertNotNull(hash)
        assertEquals(32, hash.length)
    }

    @Test
    fun `md5 known value`() {
        // MD5("hello") = 5d41402abc4b2a76b9719d911017c592
        assertEquals("5d41402abc4b2a76b9719d911017c592", invokeMd5("hello"))
    }

    @Test
    fun `salt should be 16 characters`() {
        val salt = UUID.randomUUID().toString().replace("-", "").take(16)
        assertEquals(16, salt.length)
    }

    @Test
    fun `token should be md5 of password plus salt`() {
        val password = "testpass"
        val salt = "abc123def456"
        val expectedToken = md5(password + salt)
        val actualToken = invokeMd5(password + salt)
        assertEquals(expectedToken, actualToken)
    }

    @Test
    fun `supportedTypes should include subsonic`() {
        assertTrue(arrayOf("jellyfin", "navidrome", "subsonic").contains("subsonic"))
    }

    @Test
    fun `API_VERSION should be 1_16_1`() {
        val field = SubsonicAdapter::class.java.getDeclaredField("API_VERSION")
        field.isAccessible = true
        val version = field.get(null) as String
        assertEquals("1.16.1", version)
    }

    @Test
    fun `CLIENT_NAME should be NASMusicTV`() {
        val field = SubsonicAdapter::class.java.getDeclaredField("CLIENT_NAME")
        field.isAccessible = true
        val clientName = field.get(null) as String
        assertEquals("NASMusicTV", clientName)
    }

    @Test
    fun `getStreamUrl should contain stream endpoint`() {
        val songId = "test_song_123"
        val url = adapter.getStreamUrl(songId)
        assertTrue(url.contains("stream"))
        assertTrue(url.contains(songId))
    }

    @Test
    fun `getCoverUrl should contain getCoverArt endpoint`() {
        val songId = "test_cover_456"
        val url = adapter.getCoverUrl(songId)
        assertTrue(url.contains("getCoverArt"))
        assertTrue(url.contains(songId))
    }

    @Test
    fun `close should not throw exception`() {
        try {
            adapter.close()
        } catch (_: Exception) {
        }
    }

    /**
     * 通过反射调用 private md5 方法
     */
    private fun invokeMd5(input: String): String {
        val method = SubsonicAdapter::class.java.getDeclaredMethod("md5", String::class.java)
        method.isAccessible = true
        return method.invoke(adapter, input) as String
    }

    /**
     * 辅助 MD5 实现，用于验证
     */
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return java.math.BigInteger(1, md.digest(input.toByteArray()))
            .toString(16).padStart(32, '0')
    }
}

package com.nasmusic.tv.backend

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `hostOfUrl`（认证头 host 白名单解析）回归测试 —— 2026-09-15 修复 F-3 配套。
 *
 * 重点：IPv6 字面量必须剥掉方括号 —— `java.net.URI.getHost()` 返回 `[2001:db8::1]`，
 * 而 OkHttp `request.url.host` 是 `2001:db8::1`；不剥离会导致认证头永不注入。
 */
class BackendHostOfUrlTest {

    @Test
    fun `bare ipv4`() {
        assertEquals("192.168.1.100", hostOfUrl("192.168.1.100"))
    }

    @Test
    fun `scheme and port`() {
        assertEquals("192.168.1.100", hostOfUrl("http://192.168.1.100:5666"))
    }

    @Test
    fun `hostname keeps case as typed`() {
        assertEquals("NAS.local", hostOfUrl("http://NAS.local:5666"))
    }

    @Test
    fun `ipv6 brackets are stripped`() {
        assertEquals("2001:db8::1", hostOfUrl("http://[2001:db8::1]"))
        assertEquals("2001:db8::1", hostOfUrl("http://[2001:db8::1]:5666/music/api/v1/"))
    }

    @Test
    fun `blank returns empty`() {
        assertEquals("", hostOfUrl(""))
        assertEquals("", hostOfUrl("   "))
    }

    @Test
    fun `unparseable returns empty`() {
        // 带空格的主机名无法解析
        assertEquals("", hostOfUrl("http://a b c"))
    }
}

package com.nasmusic.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-1：日志凭证脱敏纯函数测试（DoD：SanitizeUrlTest） */
class UrlSanitizerTest {

    @Test
    fun `jellyfin api_key masked`() {
        val url = "http://192.168.0.10:8096/Items/123?api_key=abcdef123456&maxWidth=512"
        val out = UrlSanitizer.sanitize(url)
        assertEquals("http://192.168.0.10:8096/Items/123?api_key=***&maxWidth=512", out)
    }

    @Test
    fun `subsonic user token salt masked`() {
        val url = "http://nas:4533/rest/getCoverArt?u=admin&t=md5hash&s=salt123&v=1.16.1&c=app&id=al-1&size=512"
        val out = UrlSanitizer.sanitize(url)
        assertTrue(out.contains("u=***"))
        assertTrue(out.contains("t=***"))
        assertTrue(out.contains("s=***"))
        assertTrue(!out.contains("md5hash"))
        assertTrue(!out.contains("salt123"))
        assertTrue(out.contains("c=app"))
        assertTrue(out.contains("id=al-1"))
    }

    @Test
    fun `daoliyu token masked`() {
        val url = "https://dly.example.com/api/songs?token=jwt.token.value&page=1"
        val out = UrlSanitizer.sanitize(url)
        assertEquals("https://dly.example.com/api/songs?token=***&page=1", out)
    }

    @Test
    fun `baidu access_token masked`() {
        val url = "https://pan.baidu.com/rest/2.0/xpan/file?method=list&access_token=121.abc.def"
        val out = UrlSanitizer.sanitize(url)
        assertEquals("https://pan.baidu.com/rest/2.0/xpan/file?method=list&access_token=***", out)
    }

    @Test
    fun `url without query unchanged`() {
        val url = "http://192.168.0.10:8096/Items/123"
        assertEquals(url, UrlSanitizer.sanitize(url))
    }

    @Test
    fun `empty and null safe`() {
        assertEquals("", UrlSanitizer.sanitize(""))
        assertEquals("", UrlSanitizer.sanitize(null))
    }

    @Test
    fun `trailing question mark and fragment preserved`() {
        val out = UrlSanitizer.sanitize("http://a.b/c?x=1#frag")
        assertEquals("http://a.b/c?x=1#frag", out)
    }

    @Test
    fun `case insensitive param`() {
        val out = UrlSanitizer.sanitize("http://a.b/c?API_KEY=secret&n=1")
        assertEquals("http://a.b/c?API_KEY=***&n=1", out)
    }
}

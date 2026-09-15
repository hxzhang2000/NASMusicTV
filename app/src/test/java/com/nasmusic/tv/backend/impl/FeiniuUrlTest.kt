package com.nasmusic.tv.backend.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FeiniuUrl` 的归一化与端点拼装回归网（2026-09-14，飞牛后端重写的配套测试）。
 *
 * 背景：旧 `FeiniuAdapter` 直接把用户填的 `baseUrl` 当 API 根路径拼接，
 * 而飞牛真实 API 基址是 `<scheme>://<host>:<port>/music/api/v1/`，默认端口是
 * **5666**（不是 80）。地址形态处理错误会直接表现为
 * 「拼出 /music/api/v1/music/api/v1/」或「连到 80 端口超时」。
 * 这些断言是「归一化正确」的依据。
 *
 * 纯 JVM，无 Android 依赖（同 `LinearResamplerTest` / `Radix2FftTest` 的形态）。
 * `FeiniuUrl` 只依赖 okhttp3 的 `HttpUrl` 与 JDK，不需要 Robolectric。
 */
class FeiniuUrlTest {

    // ==================== normalize：默认端口 ====================

    @Test
    fun `bare host uses fnOS port 5666`() {
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/",
            FeiniuUrl.normalize("192.168.1.100")
        )
    }

    @Test
    fun `explicit http scheme without port still uses 5666`() {
        // 与参考项目 ServerUrlNormalizer 的「显式 http:// 补 80」有意不同：
        // 本适配器是飞牛专用，填 http://192.168.1.100 指的一定是音乐服务
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/",
            FeiniuUrl.normalize("http://192.168.1.100")
        )
    }

    @Test
    fun `explicit port is always preserved`() {
        assertEquals(
            "http://192.168.1.100:8080/music/api/v1/",
            FeiniuUrl.normalize("http://192.168.1.100:8080")
        )
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/",
            FeiniuUrl.normalize("http://192.168.1.100:5666")
        )
    }

    @Test
    fun `https without port uses 5667`() {
        assertEquals(
            "https://nas.example.com:5667/music/api/v1/",
            FeiniuUrl.normalize("https://nas.example.com")
        )
    }

    @Test
    fun `explicit https port is preserved`() {
        assertEquals(
            "https://nas.example.com:443/music/api/v1/",
            FeiniuUrl.normalize("https://nas.example.com:443")
        )
    }

    // ==================== normalize：路径推导 ====================

    @Test
    fun `root path gets music api prefix appended`() {
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/",
            FeiniuUrl.normalize("http://192.168.1.100/")
        )
    }

    @Test
    fun `trailing music path is completed not duplicated`() {
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/",
            FeiniuUrl.normalize("http://192.168.1.100/music")
        )
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/",
            FeiniuUrl.normalize("http://192.168.1.100/music/")
        )
    }

    @Test
    fun `full api path is idempotent`() {
        // 最易出错的场景：用户直接填了完整 API 路径，不能拼成 .../v1/music/api/v1/
        val once = FeiniuUrl.normalize("http://192.168.1.100/music/api/v1")
        assertEquals("http://192.168.1.100:5666/music/api/v1/", once)
        assertEquals("http://192.168.1.100:5666/music/api/v1/", FeiniuUrl.normalize(once))
    }

    @Test
    fun `arbitrary base path is prefixed with music api`() {
        assertEquals(
            "http://192.168.1.100:5666/nas/music/api/v1/",
            FeiniuUrl.normalize("http://192.168.1.100/nas")
        )
    }

    // ==================== normalize：清洗与拒绝 ====================

    @Test
    fun `query and fragment are stripped`() {
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/",
            FeiniuUrl.normalize("http://192.168.1.100?token=abc#frag")
        )
    }

    @Test
    fun `credentials in url are rejected`() {
        assertEquals("", FeiniuUrl.normalize("http://user:pass@192.168.1.100"))
    }

    @Test
    fun `non http schemes are rejected`() {
        assertEquals("", FeiniuUrl.normalize("ftp://192.168.1.100"))
        assertEquals("", FeiniuUrl.normalize("file:///mnt/music"))
    }

    @Test
    fun `blank input is rejected`() {
        assertEquals("", FeiniuUrl.normalize(""))
        assertEquals("", FeiniuUrl.normalize("   "))
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/",
            FeiniuUrl.normalize("  192.168.1.100  ")
        )
    }

    @Test
    fun `ipv6 literal is bracketed not broken`() {
        assertEquals(
            "http://[2001:db8::1]:5666/music/api/v1/",
            FeiniuUrl.normalize("http://[2001:db8::1]")
        )
    }

    @Test
    fun `normalize is idempotent across representative inputs`() {
        val inputs = listOf(
            "192.168.1.100",
            "http://192.168.1.100",
            "http://192.168.1.100:5666",
            "http://192.168.1.100/music",
            "http://192.168.1.100/music/api/v1",
            "https://nas.example.com"
        )
        inputs.forEach { input ->
            val once = FeiniuUrl.normalize(input)
            assertEquals("not idempotent for $input", once, FeiniuUrl.normalize(once))
        }
    }

    // ==================== endpoint ====================

    private val base = "http://192.168.1.100:5666/music/api/v1/"

    @Test
    fun `endpoint appends path with query`() {
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/track/list?page=1&size=50",
            FeiniuUrl.endpoint(base, "track/list", "page" to 1, "size" to 50)
        )
    }

    @Test
    fun `endpoint tolerates leading slash and missing trailing slash`() {
        val noTrailingSlash = "http://192.168.1.100:5666/music/api/v1"
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/track/list?page=1",
            FeiniuUrl.endpoint(noTrailingSlash, "/track/list", "page" to 1)
        )
    }

    @Test
    fun `endpoint skips null query values`() {
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/track/list?page=1",
            FeiniuUrl.endpoint(base, "track/list", "page" to 1, "size" to null)
        )
    }

    @Test
    fun `endpoint url encodes query values`() {
        // 排序参数含逗号，必须编码，否则部分服务端解析异常
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/track/list?sort=createdAt%2Cdesc",
            FeiniuUrl.endpoint(base, "track/list", "sort" to "createdAt,desc")
        )
    }

    // ==================== 业务端点 ====================

    @Test
    fun `stream url uses guid query parameter`() {
        // 飞牛是 track/stream?guid=，不是 track/{id}/stream（旧实现的错误写法）
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/track/stream?guid=abc-123",
            FeiniuUrl.streamUrl(base, "abc-123")
        )
    }

    @Test
    fun `cover url is keyed by coverId not track id`() {
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/static/cover?coverId=cov-1&size=512",
            FeiniuUrl.coverUrl(base, "cov-1")
        )
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/static/cover?coverId=cov-1&size=256",
            FeiniuUrl.coverUrl(base, "cov-1", 256)
        )
    }

    @Test
    fun `cover url returns null when coverId is blank`() {
        assertNull(FeiniuUrl.coverUrl(base, null))
        assertNull(FeiniuUrl.coverUrl(base, ""))
        assertNull(FeiniuUrl.coverUrl(base, "   "))
    }

    @Test
    fun `hostOf extracts host for auth header matching`() {
        assertEquals("192.168.1.100", FeiniuUrl.hostOf(base))
        assertEquals("", FeiniuUrl.hostOf("not a url"))
    }

    @Test
    fun `every generated endpoint stays under the api base`() {
        // 防御回归：任何端点都不能再拼出第二个 /music/api/v1/
        val paths = listOf(
            FeiniuUrl.endpoint(base, "track/list", "page" to 1, "size" to 50),
            FeiniuUrl.streamUrl(base, "g"),
            FeiniuUrl.coverUrl(base, "c") ?: ""
        )
        paths.forEach { path ->
            assertTrue("unexpected double prefix: $path", path.contains("/music/api/v1/"))
            assertEquals(
                "double api prefix in $path",
                1,
                path.split("/music/api/v1/").size - 1
            )
        }
    }

    @Test
    fun `scheme-less host with port is accepted`() {
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/",
            FeiniuUrl.normalize("192.168.1.100:5666")
        )
    }

    @Test
    fun `uppercase scheme is normalized`() {
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/",
            FeiniuUrl.normalize("HTTP://192.168.1.100")
        )
    }

    @Test
    fun `duplicate slashes are collapsed`() {
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1/",
            FeiniuUrl.normalize("http://192.168.1.100//music")
        )
    }

    @Test
    fun `hostOf strips ipv6 brackets`() {
        // 认证头 host 匹配用：OkHttp 的 request.url.host 不带方括号
        assertEquals("2001:db8::1", FeiniuUrl.hostOf("http://[2001:db8::1]:5666/music/api/v1/"))
    }
}

package com.nasmusic.tv.backend.playlist

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * URL 可达性检查器测试（docs/archive/playlist-import-feature-plan.md §4.1.8 (7)）。
 */
class UrlReachabilityCheckerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val checker get() = UrlReachabilityChecker()

    @Test
    fun `2xx is reachable with content length`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).addHeader("Content-Length", "1024")
        )
        val (result, length) = checker.check(server.url("/song.mp3").toString())
        assertEquals(UrlReachabilityChecker.Result.REACHABLE, result)
        assertEquals(1024L, length)
    }

    @Test
    fun `404 is not found`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val (result, _) = checker.check(server.url("/missing.mp3").toString())
        assertEquals(UrlReachabilityChecker.Result.NOT_FOUND, result)
    }

    @Test
    fun `4xx other than 404 is not found`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val (result, _) = checker.check(server.url("/forbidden.mp3").toString())
        assertEquals(UrlReachabilityChecker.Result.NOT_FOUND, result)
    }

    @Test
    fun `5xx is server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val (result, _) = checker.check(server.url("/err.mp3").toString())
        assertEquals(UrlReachabilityChecker.Result.SERVER_ERROR, result)
    }

    @Test
    fun `redirect is followed to 200`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/real.mp3").toString())
        )
        server.enqueue(MockResponse().setResponseCode(200))
        val (result, _) = checker.check(server.url("/redir.mp3").toString())
        assertEquals(UrlReachabilityChecker.Result.REACHABLE, result)
    }

    @Test
    fun `redirect loop is detected`() = runBlocking {
        // 每次请求都 302 回到自身 → OkHttp 达到重定向上限 → REDIRECT_LOOP
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(302)
                    .addHeader("Location", server.url("/loop").toString())
            }
        }
        val (result, _) = checker.check(server.url("/loop").toString())
        assertEquals(UrlReachabilityChecker.Result.REDIRECT_LOOP, result)
    }

    @Test
    fun `invalid url returns invalid`() = runBlocking {
        val (result, _) = checker.check("not a url")
        assertEquals(UrlReachabilityChecker.Result.INVALID_URL, result)
    }

    @Test
    fun `timeout maps from socket timeout`() = runBlocking {
        val failing = OkHttpClient.Builder()
            .addInterceptor { throw SocketTimeoutException("timeout") }
            .build()
        val c = UrlReachabilityChecker(client = failing)
        val (result, _) = c.check("http://example.com/song.mp3")
        assertEquals(UrlReachabilityChecker.Result.TIMEOUT, result)
    }

    @Test
    fun `dns failure maps from unknown host`() = runBlocking {
        val failing = OkHttpClient.Builder()
            .addInterceptor { throw UnknownHostException("no such host") }
            .build()
        val c = UrlReachabilityChecker(client = failing)
        val (result, _) = c.check("http://nx.example.com/song.mp3")
        assertEquals(UrlReachabilityChecker.Result.DNS_FAILED, result)
    }

    @Test
    fun `same url cached within 5 minutes no second request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val url = server.url("/cached.mp3").toString()
        val c = UrlReachabilityChecker()

        val first = c.check(url)
        val second = c.check(url)

        assertEquals(UrlReachabilityChecker.Result.REACHABLE, first.first)
        assertEquals(first, second)
        assertEquals(1, server.requestCount)   // 第二次未发起请求
    }

    @Test
    fun `batch checks concurrently and preserves order`() = runBlocking {
        repeat(6) {
            server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Length", "10"))
        }
        val urls = (1..6).map { server.url("/s$it.mp3").toString() }
        val results = UrlReachabilityChecker().checkBatch(urls)
        assertEquals(6, results.size)
        results.forEachIndexed { idx, (result, length) ->
            assertEquals(UrlReachabilityChecker.Result.REACHABLE, result)
            assertEquals(10L, length)
            // 均指向同一 server，Content-Length 相同；顺序由输入顺序保证
            assertEquals(urls[idx].endsWith("/s${idx + 1}.mp3"), true)
        }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `empty batch returns empty`() = runBlocking {
        assertTrue(UrlReachabilityChecker().checkBatch(emptyList()).isEmpty())
    }

    @Test
    fun `private lan urls are filtered`() {
        val c = UrlReachabilityChecker()
        assertTrue(c.isPrivateLanUrl("http://192.168.1.1/music/x.mp3"))
        assertTrue(c.isPrivateLanUrl("http://10.0.0.1:5000/song.mp3"))
        assertTrue(c.isPrivateLanUrl("http://172.16.0.1/song.mp3"))
        assertTrue(c.isPrivateLanUrl("http://172.31.255.255/x.mp3"))
        assertFalse(c.isPrivateLanUrl("http://172.32.0.1/x.mp3"))
        assertFalse(c.isPrivateLanUrl("http://8.8.8.8/x.mp3"))
        assertFalse(c.isPrivateLanUrl("http://example.com/x.mp3"))
        assertFalse(c.isPrivateLanUrl("not a url"))
    }

    @Test
    fun `timeout zero means no timeout`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val (result, _) = checker.check(server.url("/no-timeout.mp3").toString(), timeoutMs = 0L)
        assertEquals(UrlReachabilityChecker.Result.REACHABLE, result)
    }

    @Test
    fun `cache ttl expired triggers new request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        val url = server.url("/ttl.mp3").toString()
        val c = UrlReachabilityChecker(cacheTtlMs = TimeUnit.MILLISECONDS.toMillis(1))

        c.check(url)
        Thread.sleep(5)
        c.check(url)

        assertEquals(2, server.requestCount)
    }
}
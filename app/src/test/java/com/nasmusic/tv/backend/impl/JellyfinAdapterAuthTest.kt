package com.nasmusic.tv.backend.impl

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * S4 回归测试：Jellyfin 会话内 401 重认证（2026-09-14）。
 *
 * 覆盖 [JellyfinAdapter.executeJsonRequest] 新增的 401 自愈路径。报告 §S4 明确要求
 * 「重试只做一次、避免循环」，因此本测试的**核心断言是请求次数上界**——不是
 * 「能不能恢复」，而是「恢复的同时不会打转」。
 *
 * 被测探针选 `getSongsTotalCount()`：它是 `executeJsonRequest` 最薄的调用方
 * （单次 GET `/Items`，返回一个 Int），不会把 JSON 解析失败混进断言。
 *
 * ⚠️ 本机 `testDebugUnitTest` 因 Gradle 测试 worker 环境问题无法运行（exit 268435466），
 * 这些用例**只验证了源码可编译**，实际通过与否须由 CI 判定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JellyfinAdapterAuthTest {

    private lateinit var server: MockWebServer

    /** `/Items` 被调用的次数（断言重试上界用） */
    private val itemsCalls = AtomicInteger(0)

    /** `/Users/AuthenticateByName` 被调用的次数（断言「只登录一次」用） */
    private val authCalls = AtomicInteger(0)

    /** true：`/Items` 的第 1 次返回 401、之后返回 200（模拟 token 过期后重认证成功） */
    private var failItemsOnce = false

    /** true：`/Items` 恒返回 401（模拟凭据已失效，验证不会无限重试） */
    private var itemsAlwaysUnauthorized = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ── 测试用响应体 ────────────────────────────────────────────────────

    private val authOkBody = """
        {"AccessToken":"fresh-token","User":{"Id":"user-1"},"ServerInfo":{"ServerName":"TestJellyfin"}}
    """.trimIndent()

    private val itemsOkBody = """{"TotalRecordCount":42,"Items":[]}"""

    private fun installDispatcher() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/Users/AuthenticateByName") -> {
                        authCalls.incrementAndGet()
                        json(200, authOkBody)
                    }

                    path.startsWith("/Users/Me") ->
                        json(200, """{"Id":"user-1","Name":"Tester"}""")

                    path.startsWith("/System/Info/Public") ->
                        json(200, """{"Version":"10.8.0"}""")

                    path.startsWith("/Items") -> {
                        val n = itemsCalls.incrementAndGet()
                        when {
                            itemsAlwaysUnauthorized -> MockResponse().setResponseCode(401)
                            failItemsOnce && n == 1 -> MockResponse().setResponseCode(401)
                            else -> json(200, itemsOkBody)
                        }
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun newAdapter() = JellyfinAdapter()

    // ── 用例 ────────────────────────────────────────────────────────────

    @Test
    fun `401 triggers reauth then retries once and succeeds`() = runBlocking {
        failItemsOnce = true
        installDispatcher()
        val adapter = newAdapter()
        assertTrue(adapter.initialize(server.url("/").toString(), "", "user", "pass"))
        val authAfterInit = authCalls.get()

        val total = adapter.getSongsTotalCount()

        assertEquals("重认证后应拿到正常响应", 42, total)
        assertEquals("恰好 1 次原始请求 + 1 次重试", 2, itemsCalls.get())
        assertEquals("恰好新增 1 次登录", authAfterInit + 1, authCalls.get())
        adapter.close()
    }

    @Test
    fun `persistent 401 retries exactly once and gives up`() = runBlocking {
        itemsAlwaysUnauthorized = true
        installDispatcher()
        val adapter = newAdapter()
        assertTrue(adapter.initialize(server.url("/").toString(), "", "user", "pass"))
        val authAfterInit = authCalls.get()

        val total = adapter.getSongsTotalCount()

        assertEquals("持续 401 应返回 0 而非抛异常", 0, total)
        assertEquals("重试次数必须是 1 —— 这是防循环的核心断言", 2, itemsCalls.get())
        assertEquals("重认证只做一次，不得反复登录", authAfterInit + 1, authCalls.get())
        adapter.close()
    }

    @Test
    fun `401 without stored credentials never calls login endpoint`() = runBlocking {
        failItemsOnce = true
        installDispatcher()
        val adapter = newAdapter()
        // token-only 会话：只有 token，没有用户名密码
        assertTrue(adapter.initialize(server.url("/").toString(), "stale-token", "", ""))

        val total = adapter.getSongsTotalCount()

        assertEquals(0, total)
        assertEquals("无凭据时不重试，直接放弃", 1, itemsCalls.get())
        assertEquals("从未调用登录端点", 0, authCalls.get())
        adapter.close()
    }

    @Test
    fun `successful request does not trigger reauth`() = runBlocking {
        installDispatcher()
        val adapter = newAdapter()
        assertTrue(adapter.initialize(server.url("/").toString(), "", "user", "pass"))
        val authAfterInit = authCalls.get()

        val total = adapter.getSongsTotalCount()

        assertEquals(42, total)
        assertEquals("无 401 时不应有第二次请求", 1, itemsCalls.get())
        assertEquals("无 401 时不应有额外登录", authAfterInit, authCalls.get())
        adapter.close()
    }

    @Test
    fun `logout clears stored credentials so later 401 cannot reauth`() = runBlocking {
        failItemsOnce = true
        installDispatcher()
        val adapter = newAdapter()
        assertTrue(adapter.initialize(server.url("/").toString(), "", "user", "pass"))
        val authAfterInit = authCalls.get()

        adapter.logout()

        val total = adapter.getSongsTotalCount()

        assertEquals(0, total)
        assertEquals("凭据已清空 → 不再重认证", authAfterInit, authCalls.get())
        adapter.close()
    }
}

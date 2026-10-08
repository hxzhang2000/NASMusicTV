package com.nasmusic.tv.backend.impl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.R
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 飞牛音乐访问码（安全码）链路回归网。
 *
 * 背景（用户故障）：fnOS 上装了飞牛音乐，地址 / 账号 / 密码全对，**就是连不上**。
 * 根因是本适配器此前**完全没有**访问码支持 —— 飞牛开启「外网访问码」保护后，
 * `access_code_verify` 返回 401/403/429，账号密码再正确也登不上。
 * 参考项目契约（`.trellis/spec/backend/android-client-contracts.md:170-173`）要求：
 * 先探测 `{origin}/access_code_verify`，并把 `x-access-code` + `x-access-source: app`
 * 挂到 **登录 / 已认证 API / 封面 / Media3 音频** 四类请求上。
 *
 * 覆盖：
 * 1. 登录与探测请求带访问码两个头（base64 编码正确）；
 * 2. `streamHeaders`（封面 + 播放流链路）在有/无访问码时的内容；
 * 3. 探测的**放行语义**：404 / 5xx / 网络异常都不得阻塞登录；
 * 4. 失败分类（`lastFailure`）被填充且**不含任何凭据**。
 *
 * ⚠️ 断言分类时用 `lastFailure`（资源 id + 协议事实）而**不是**渲染后的文案：
 * 本项目单测不打包 Android 资源（`unitTests.includeAndroidResources` 未开），
 * Robolectric 下 `Context.getString` 恒抛 `Resources$NotFoundException`。
 * `lastErrorDetail` 仍一并断言「不含凭据」——那是本次修复的核心安全底线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeiniuAdapterAccessCodeTest {

    private lateinit var server: MockWebServer

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 记录的登录请求（断言访问码头） */
    private var lastLoginRequest: RecordedRequest? = null

    /** 记录的探测请求（断言访问码头 + 探测先于登录发生） */
    private val probeRequests = mutableListOf<RecordedRequest>()

    /** `access_code_verify` 的返回码（默认 200 = 未开启访问码保护） */
    private var probeCode: Int = 200

    /** 登录接口返回码（默认 200） */
    private var loginCode: Int = 200

    /** 登录信封（默认成功） */
    private var loginBody: String = """{"code":0,"msg":"","data":{"userToken":"tok-123"}}"""

    /** true：探测直接被断开（模拟端点不可达），用于验证「网络异常必须放行」 */
    private var probeDisconnects: Boolean = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith(FeiniuUrl.ACCESS_CODE_VERIFY_PATH) -> {
                        probeRequests.add(request)
                        if (probeDisconnects) {
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                        } else {
                            MockResponse().setResponseCode(probeCode)
                        }
                    }

                    path.endsWith("/user/password-login") -> {
                        lastLoginRequest = request
                        MockResponse()
                            .setResponseCode(loginCode)
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody(loginBody)
                    }

                    path.endsWith("/user/me") ->
                        json(200, """{"code":0,"msg":"","data":{"guid":"g1","name":"n"}}""")

                    path.endsWith("/sys/config") ->
                        json(200, """{"code":0,"msg":"","data":{"serverName":"TestNAS","serverVersion":"1.2.3"}}""")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    /** base64("safe-code", UTF-8, NO_WRAP) —— 与适配器内部编码方式一致 */
    private val encodedSafeCode = "c2FmZS1jb2Rl"

    private fun baseUrl() = server.url("/").toString()

    // ── 1. 登录 / 探测请求带访问码头 ────────────────────────────────────

    @Test
    fun `login request carries base64 access code headers`() = runBlocking {
        val adapter = FeiniuAdapter(context)
        adapter.setAccessCode("safe-code")

        assertTrue(adapter.initialize(baseUrl(), "", "user", "pass"))

        val login = lastLoginRequest
        assertNotNull("登录请求必须发出", login)
        assertEquals(encodedSafeCode, login!!.getHeader("x-access-code"))
        assertEquals("app", login.getHeader("x-access-source"))
        adapter.close()
    }

    @Test
    fun `probe runs before login and carries access code headers`() = runBlocking {
        val adapter = FeiniuAdapter(context)
        adapter.setAccessCode("safe-code")

        adapter.initialize(baseUrl(), "", "user", "pass")

        val probe = probeRequests.firstOrNull()
        assertNotNull("必须先探测 access_code_verify 再登录", probe)
        assertEquals(encodedSafeCode, probe!!.getHeader("x-access-code"))
        assertEquals("app", probe.getHeader("x-access-source"))
        adapter.close()
    }

    @Test
    fun `no access code means no access code headers`() = runBlocking {
        val adapter = FeiniuAdapter(context)

        assertTrue(adapter.initialize(baseUrl(), "", "user", "pass"))

        val login = lastLoginRequest
        assertNotNull(login)
        assertEquals("未填访问码时不得凭空造头", null, login!!.getHeader("x-access-code"))
        assertEquals(null, login.getHeader("x-access-source"))
        adapter.close()
    }

    @Test
    fun `access code is trimmed before encoding`() = runBlocking {
        val adapter = FeiniuAdapter(context)
        adapter.setAccessCode("  safe-code  ")

        adapter.initialize(baseUrl(), "", "user", "pass")

        assertEquals(encodedSafeCode, lastLoginRequest?.getHeader("x-access-code"))
        adapter.close()
    }

    // ── 2. streamHeaders：封面 / 播放流链路 ─────────────────────────────

    @Test
    fun `streamHeaders include access code alongside authorization`() = runBlocking {
        val adapter = FeiniuAdapter(context)
        adapter.setAccessCode("safe-code")

        assertTrue(adapter.initialize(baseUrl(), "", "user", "pass"))

        val headers = adapter.streamHeaders
        assertEquals("tok-123", headers["Authorization"])
        assertEquals(encodedSafeCode, headers["x-access-code"])
        assertEquals("app", headers["x-access-source"])
        adapter.close()
    }

    @Test
    fun `streamHeaders omit access code when not configured`() = runBlocking {
        val adapter = FeiniuAdapter(context)

        assertTrue(adapter.initialize(baseUrl(), "", "user", "pass"))

        val headers = adapter.streamHeaders
        assertEquals("tok-123", headers["Authorization"])
        assertFalse(headers.containsKey("x-access-code"))
        assertFalse(headers.containsKey("x-access-source"))
        adapter.close()
    }

    @Test
    fun `logout clears access code from streamHeaders`() = runBlocking {
        val adapter = FeiniuAdapter(context)
        adapter.setAccessCode("safe-code")
        assertTrue(adapter.initialize(baseUrl(), "", "user", "pass"))

        adapter.logout()

        assertFalse(
            "登出后不得继续注入访问码（凭据）",
            adapter.streamHeaders.containsKey("x-access-code")
        )
        adapter.close()
    }

    // ── 3. 探测判定：阻塞 vs 放行 ──────────────────────────────────────

    @Test
    fun `probe 401 without access code blocks login`() = runBlocking {
        probeCode = 401
        val adapter = FeiniuAdapter(context)

        assertFalse(adapter.initialize(baseUrl(), "", "user", "pass"))

        assertEquals(
            "未填访问码时必须归类为「需要访问码」",
            R.string.feiniu_err_access_code_required,
            adapter.lastFailure?.resId
        )
        assertNull("被阻塞时不得发出登录请求", lastLoginRequest)
        adapter.close()
    }

    @Test
    fun `probe 403 with access code blocks login as invalid`() = runBlocking {
        probeCode = 403
        val adapter = FeiniuAdapter(context)
        adapter.setAccessCode("wrong-code")

        assertFalse(adapter.initialize(baseUrl(), "", "user", "pass"))

        assertEquals(
            "填了访问码还被拒 → 是「访问码错误」，不是「需要访问码」",
            R.string.feiniu_err_access_code_invalid,
            adapter.lastFailure?.resId
        )
        adapter.close()
    }

    @Test
    fun `probe 429 without access code blocks login`() = runBlocking {
        probeCode = 429
        val adapter = FeiniuAdapter(context)

        assertFalse(adapter.initialize(baseUrl(), "", "user", "pass"))
        assertEquals(R.string.feiniu_err_access_code_required, adapter.lastFailure?.resId)
        adapter.close()
    }

    @Test
    fun `probe 404 must not block login`() = runBlocking {
        // 老版本 fnOS 可能没有这个端点：照抄参考项目 `else -> access`，必须放行
        probeCode = 404
        val adapter = FeiniuAdapter(context)

        assertTrue(adapter.initialize(baseUrl(), "", "user", "pass"))
        adapter.close()
    }

    @Test
    fun `probe 500 must not block login`() = runBlocking {
        probeCode = 500
        val adapter = FeiniuAdapter(context)

        assertTrue(adapter.initialize(baseUrl(), "", "user", "pass"))
        adapter.close()
    }

    @Test
    fun `probe network failure must not block login`() = runBlocking {
        // ⚠️ 网络异常必须放行：否则断网 / 防火墙拦截时连地址都验不了
        probeDisconnects = true
        val adapter = FeiniuAdapter(context)

        assertTrue(adapter.initialize(baseUrl(), "", "user", "pass"))
        adapter.close()
    }

    @Test
    fun `probe 200 with valid access code lets login proceed`() = runBlocking {
        val adapter = FeiniuAdapter(context)
        adapter.setAccessCode("safe-code")

        assertTrue(adapter.initialize(baseUrl(), "", "user", "pass"))
        adapter.close()
    }

    // ── 4. 失败分类：可诊断 + 不含凭据 ─────────────────────────────────

    @Test
    fun `envelope error code populates diagnosis without credentials`() = runBlocking {
        loginBody = """{"code":100001,"msg":"invalid password","data":null}"""
        val adapter = FeiniuAdapter(context)
        adapter.setAccessCode("safe-code")

        assertFalse(adapter.initialize(baseUrl(), "", "user", "s3cret-pass"))

        val failure = adapter.lastFailure
        assertEquals(R.string.feiniu_err_envelope_code, failure?.resId)
        assertEquals("必须带上服务端 code", 100001, failure?.facts?.firstOrNull())
        assertEquals("必须带上服务端 msg", "invalid password", failure?.facts?.get(1))
        assertNoCredentials(adapter, "s3cret-pass", "safe-code")
        adapter.close()
    }

    @Test
    fun `http 401 on login is classified as token expired`() = runBlocking {
        loginCode = 401
        loginBody = ""
        val adapter = FeiniuAdapter(context)

        assertFalse(adapter.initialize(baseUrl(), "", "user", "s3cret-pass"))

        assertEquals(R.string.feiniu_err_token_expired, adapter.lastFailure?.resId)
        assertNoCredentials(adapter, "s3cret-pass")
        adapter.close()
    }

    @Test
    fun `http 500 on login carries the status code as fact`() = runBlocking {
        loginCode = 500
        loginBody = ""
        val adapter = FeiniuAdapter(context)

        assertFalse(adapter.initialize(baseUrl(), "", "user", "s3cret-pass"))

        val failure = adapter.lastFailure
        assertEquals(R.string.feiniu_err_http_status, failure?.resId)
        assertEquals("必须带上 HTTP 状态码", 500, failure?.facts?.firstOrNull())
        assertTrue("渲染文案必须含状态码", adapter.lastErrorDetail.contains("500"))
        assertNoCredentials(adapter, "s3cret-pass")
        adapter.close()
    }

    @Test
    fun `envelope msg longer than the cap is truncated`() = runBlocking {
        val longMsg = "x".repeat(500)
        // 100004 = 曲目不可用，落在 dataOf 的 else 分支 → 信封错误分类
        loginBody = """{"code":100004,"msg":"$longMsg","data":null}"""
        val adapter = FeiniuAdapter(context)

        assertFalse(adapter.initialize(baseUrl(), "", "user", "pass"))

        val failure = adapter.lastFailure
        assertEquals(R.string.feiniu_err_envelope_code, failure?.resId)
        val shown = failure?.facts?.get(1) as String
        assertTrue("超长 msg 必须截断，实际长度 ${shown.length}", shown.length <= 61)
        adapter.close()
    }

    @Test
    fun `not-found code does not pollute the connection diagnosis`() = runBlocking {
        // 100005 是正常业务结果（「资源不存在」），不该被当成连接失败的原因
        loginBody = """{"code":100005,"msg":"not found","data":null}"""
        val adapter = FeiniuAdapter(context)

        assertFalse(adapter.initialize(baseUrl(), "", "user", "pass"))

        assertEquals(
            "资源不存在不得覆盖连接诊断",
            R.string.feiniu_err_no_token,
            adapter.lastFailure?.resId
        )
        adapter.close()
    }

    @Test
    fun `invalid base url is classified as invalid address`() = runBlocking {
        val adapter = FeiniuAdapter(context)

        assertFalse(adapter.initialize("ftp://bad url", "", "user", "pass"))

        assertEquals(R.string.feiniu_err_invalid_url, adapter.lastFailure?.resId)
        adapter.close()
    }

    @Test
    fun `unreachable server is classified as network error`() = runBlocking {
        val adapter = FeiniuAdapter(context)
        // 指向没有在监听的端口 → ConnectException
        assertFalse(adapter.initialize("http://127.0.0.1:1", "", "user", "s3cret-pass"))

        val failure = adapter.lastFailure
        assertEquals(R.string.feiniu_err_network, failure?.resId)
        assertTrue("网络错误必须给出短原因", (failure?.facts?.firstOrNull() as? String)?.isNotBlank() == true)
        assertNoCredentials(adapter, "s3cret-pass")
        adapter.close()
    }

    @Test
    fun `successful initialize clears previous diagnosis`() = runBlocking {
        val adapter = FeiniuAdapter(context)
        adapter.setAccessCode("safe-code")

        assertTrue(adapter.initialize(baseUrl(), "", "user", "pass"))

        assertNull("成功连接后不应残留失败分类", adapter.lastFailure)
        assertEquals("成功连接后不应残留错误文案", "", adapter.lastErrorDetail)
        adapter.close()
    }

    /** ⛔ 渲染文案与分类事实都不得含密码 / 访问码（原文或 base64） */
    private fun assertNoCredentials(adapter: FeiniuAdapter, vararg secrets: String) {
        val detail = adapter.lastErrorDetail
        assertTrue("失败文案不得为空", detail.isNotBlank())
        // facts 允许为空（纯定类错误如「令牌失效」没有协议事实），但绝不能含凭据
        val haystacks = listOf(detail, adapter.lastFailure?.facts?.joinToString(" ").orEmpty())
        for (haystack in haystacks) {
            for (secret in secrets) {
                assertFalse("⛔ 文案泄露了凭据：$secret / $haystack", haystack.contains(secret))
            }
            // base64("safe-code")
            assertFalse("⛔ 文案泄露了 base64 访问码：$haystack", haystack.contains(encodedSafeCode))
        }
    }
}
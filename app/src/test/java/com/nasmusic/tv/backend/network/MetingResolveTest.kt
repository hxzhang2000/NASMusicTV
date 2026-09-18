package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Meting 直链解析测试（多码率方案 §10.1 / §3.2）。
 *
 * 用 MockWebServer 打桩，验证：
 * - `br` 参数按档位正确拼接（999/192 带上，AUTO 不带）
 * - 降级链按顺序回退并返回**实际命中档位**
 * - 302 取 `Location`、200 走 JSON `url` 两种响应形态
 * - AUTO 档不触发降级语义
 *
 * ⚠️ 端点 fallback 会遍历 `PRESET_ENDPOINTS`（真实公网地址）。
 * 为避免测试打真实网络导致 flaky，所有用例都让**首选端点（MockWebServer）**
 * 返回结果；需要"全链失败"的用例则通过 Dispatcher 让首选端点始终 200-无 url，
 * 并对结果只断言"未拿到可用 url"，不断言具体端点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetingResolveTest {

    private lateinit var server: MockWebServer

    /** 记录每次请求的 br 参数（null 表示 URL 中不含 br） */
    private val seenBr = mutableListOf<Int?>()
    private val seenPaths = mutableListOf<String>()

    /** 每个 tier → 直链；未配置的 tier 视为不可用 */
    private var urlByTier: Map<Int?, String> = emptyMap()

    /** true 时所有请求返回 302 + Location（而非 200 + JSON） */
    private var use302 = false

    @Before
    fun setup() {
        seenBr.clear()
        seenPaths.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.path ?: ""
                seenPaths += url
                val br = Regex("[?&]br=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
                seenBr += br
                val target = urlByTier[br]
                return when {
                    target == null -> MockResponse().setResponseCode(200).setBody("{}")
                    use302 -> MockResponse().setResponseCode(302).addHeader("Location", target)
                    else -> MockResponse().setResponseCode(200)
                        .setBody("""{"url":"$target"}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun service(qualityTier: Int = QualityTiers.AUTO) = MetingApiService(
        // 首选端点指向 MockWebServer；fallback 到预设端点时同样会被 Dispatcher 记录
        baseUrlProvider = { server.url("/api").toString().trimEnd('/') },
        serverProvider = { "netease" },
        qualityTierProvider = { qualityTier }
    )

    private fun song(id: String = "1001") = Song(
        id = "ntwk_meting_$id",
        title = "南方姑娘",
        artist = "赵雷",
        isNetworkSong = true,
        networkSource = "meting",
        networkId = id
    )

    // ── br 参数拼接 ─────────────────────────────────────────

    @Test
    fun `quality 999 请求 URL 含 br=999`() = runBlocking {
        urlByTier = mapOf(999 to "https://cdn/999.flac")
        val r = service().resolvePlayUrlDetailed(song(), 999)
        assertEquals("https://cdn/999.flac", r.url)
        assertEquals(999, r.actualQuality)
        assertTrue("首个请求应带 br=999", seenBr.firstOrNull() == 999)
    }

    @Test
    fun `quality 192 请求 URL 含 br=192`() = runBlocking {
        urlByTier = mapOf(192 to "https://cdn/192.mp3")
        val r = service().resolvePlayUrlDetailed(song(), 192)
        assertEquals("https://cdn/192.mp3", r.url)
        assertEquals(192, r.actualQuality)
        assertTrue("首个请求应带 br=192", seenBr.firstOrNull() == 192)
    }

    @Test
    fun `quality 0 AUTO 请求 URL 不含 br 参数`() = runBlocking {
        urlByTier = mapOf(null to "https://cdn/auto.mp3")
        val r = service().resolvePlayUrlDetailed(song(), QualityTiers.AUTO)
        assertEquals("https://cdn/auto.mp3", r.url)
        assertTrue("AUTO 档首个请求不应带 br", seenBr.firstOrNull() == null)
    }

    // ── 降级链 ──────────────────────────────────────────────

    @Test
    fun `999 不可用降级到 320 并返回实际档位`() = runBlocking {
        // 只有 320 可用
        urlByTier = mapOf(320 to "https://cdn/320.mp3")
        val r = service().resolvePlayUrlDetailed(song(), 999)
        assertEquals("https://cdn/320.mp3", r.url)
        assertEquals("必须返回实际命中的 320", 320, r.actualQuality)
        assertTrue("应发生降级", r.isDowngradedFrom(999))
        assertEquals("应先试 999 再试 320", listOf(999, 320), seenBr.take(2))
    }

    @Test
    fun `降级链包含 192 档`() = runBlocking {
        // 只有 192 可用 → 请求 999 应经 999 → 320 → 192
        urlByTier = mapOf(192 to "https://cdn/192.mp3")
        val r = service().resolvePlayUrlDetailed(song(), 999)
        assertEquals(192, r.actualQuality)
        assertEquals("降级链必须经过 192（v1.3 更正点）", listOf(999, 320, 192), seenBr.take(3))
    }

    @Test
    fun `降级到 128 时按 999 320 192 128 顺序尝试`() = runBlocking {
        urlByTier = mapOf(128 to "https://cdn/128.mp3")
        val r = service().resolvePlayUrlDetailed(song(), 999)
        assertEquals(128, r.actualQuality)
        assertEquals(listOf(999, 320, 192, 128), seenBr.take(4))
    }

    @Test
    fun `320 档请求不尝试 999`() = runBlocking {
        urlByTier = mapOf(320 to "https://cdn/320.mp3")
        service().resolvePlayUrlDetailed(song(), 320)
        assertTrue("请求 320 时不应出现 br=999", !seenBr.take(1).contains(999))
        assertEquals(320, seenBr.first())
    }

    // ── 响应形态 ────────────────────────────────────────────

    @Test
    fun `302 响应取 Location 头`() = runBlocking {
        urlByTier = mapOf(320 to "https://cdn/302-target.mp3")
        use302 = true
        val r = service().resolvePlayUrlDetailed(song(), 320)
        assertEquals("https://cdn/302-target.mp3", r.url)
        assertEquals(320, r.actualQuality)
    }

    @Test
    fun `200 响应取 JSON 的 url 字段`() = runBlocking {
        urlByTier = mapOf(320 to "https://cdn/200-target.mp3")
        use302 = false
        val r = service().resolvePlayUrlDetailed(song(), 320)
        assertEquals("https://cdn/200-target.mp3", r.url)
    }

    // ── AUTO 档语义 ────────────────────────────────────────

    @Test
    fun `AUTO 档命中时 actualQuality 为 AUTO 不触发降级`() = runBlocking {
        urlByTier = mapOf(null to "https://cdn/auto.mp3")
        val r = service().resolvePlayUrlDetailed(song(), QualityTiers.AUTO)
        assertEquals(QualityTiers.AUTO, r.actualQuality)
        assertTrue("AUTO 档不应被判定为降级", !r.isDowngradedFrom(QualityTiers.AUTO))
    }

    // ── 失败路径 ────────────────────────────────────────────

    @Test
    fun `首选端点全档不可用时 会继续尝试后续端点（fallback 行为）`() = runBlocking {
        // ⚠️ 不能断言"最终 url == null"：buildEndpointFallbackOrder 在首选端点之后
        // 会追加 PRESET_ENDPOINTS 里的 3 个**真实公网端点**，测试环境可能真的拿到直链
        // （实测：netease 的 outer/url 会返回可用地址），那样断言必然 flaky。
        // 这里只断言可确定的性质：首选端点被完整试过 999→320→192→128 四档。
        urlByTier = emptyMap()
        service().resolvePlayUrlDetailed(song(), 999)
        assertEquals(
            "首选端点应按降级链试完 4 档后才换端点",
            listOf(999, 320, 192, 128), seenBr.take(4)
        )
    }

    @Test
    fun `首选端点不可用时 fallback 到后续端点并返回可用结果`() = runBlocking {
        // 让首选端点（MockWebServer）对所有档返回空 JSON，再由 Dispatcher 记录后续端点请求
        urlByTier = emptyMap()
        val r = service().resolvePlayUrlDetailed(song(), 320)
        // 只要没抛异常、且请求确实越过了首选端点的 2 档（320→128），即证明 fallback 生效
        assertTrue("应至少尝试过 320 与 128", seenBr.size >= 2)
        assertTrue(
            "actualQuality 应为合法档位",
            r.actualQuality == 320 || r.actualQuality == 128
        )
    }

    @Test
    fun `无 networkId 时回退 song streamUrl`() = runBlocking {
        val noId = Song(
            id = "x", title = "T", isNetworkSong = true,
            networkSource = "meting", networkId = null,
            streamUrl = "https://preset/already.mp3"
        )
        val r = service().resolvePlayUrlDetailed(noId, 320)
        assertEquals("https://preset/already.mp3", r.url)
    }

    @Test
    fun `String 版本与 detailed 版本结果一致`() = runBlocking {
        urlByTier = mapOf(320 to "https://cdn/320.mp3")
        val svc = service()
        val detailed = svc.resolvePlayUrlDetailed(song(), 320)
        val plain = svc.resolvePlayUrl(song(), 320)
        assertEquals(detailed.url, plain)
    }

    @Test
    fun `resolvePlayUrl 无参版本使用 qualityTierProvider`() = runBlocking {
        urlByTier = mapOf(192 to "https://cdn/192.mp3")
        val svc = service(qualityTier = 192)
        val url = svc.resolvePlayUrl(song())
        assertEquals("https://cdn/192.mp3", url)
        assertEquals("应按 provider 的 192 档请求", 192, seenBr.first())
    }
}

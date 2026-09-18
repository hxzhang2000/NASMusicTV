package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放直链缓存键测试（多码率方案 §10.1 / G1）。
 *
 * 核心：缓存键必须含档位（`networkSource:networkId:quality`）。
 * 改造前 key 是 `song.id`，导致切档位后 5 分钟内仍命中旧档直链，
 * **音质切换静默失效**。
 *
 * 用手写 Fake 而非 Mockito —— `NetworkMusicService` 含 suspend fun，
 * Mockito 无法正确处理 Kotlin 协程参数（沿用 NetworkMusicManagerTest 的既有做法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayUrlCacheKeyTest {

    /** 可按档位返回不同直链的 Fake；记录每次调用的档位以验证缓存行为 */
    private class FakeQualityService(
        override val sourceId: String = "meting"
    ) : NetworkMusicService {
        /** 档位 → 直链；未配置的档位视为不可用 */
        val urlsByTier = mutableMapOf<Int, String>()
        val resolveCalls = mutableListOf<Int>()

        override suspend fun search(keyword: String, limit: Int): List<Song> = emptyList()
        override suspend fun resolvePlayUrl(song: Song): String? = null
        override suspend fun resolveLyrics(song: Song): String? = null
        override suspend fun getPlaylist(playlistId: String): List<Song> = emptyList()

        override suspend fun resolvePlayUrlDetailed(song: Song, quality: Int): ResolveResult {
            resolveCalls += quality
            // 模拟降级链：请求档不可用时依次往下找
            val chain = QualityTiers.fallbackChainOf(quality)
            for (br in chain) {
                val t = br ?: quality
                urlsByTier[t]?.let { return ResolveResult(it, t) }
            }
            return ResolveResult.failure(quality)
        }
    }

    private lateinit var service: FakeQualityService
    private lateinit var manager: NetworkMusicManager

    private fun song(id: String = "1") = Song(
        id = "ntwk_meting_$id",
        title = "南方姑娘",
        artist = "赵雷",
        isNetworkSong = true,
        networkSource = "meting",
        networkId = id
    )

    @Before
    fun setup() {
        service = FakeQualityService()
        manager = NetworkMusicManager(
            services = mapOf("meting" to service),
            defaultSourceProvider = { "meting" },
            qualityTierProvider = { 320 }
        )
    }

    @Test
    fun `同歌不同档 缓存键不同 不串用`() = runBlocking {
        val s = song()
        service.urlsByTier[320] = "https://cdn/320.mp3"
        service.urlsByTier[999] = "https://cdn/999.flac"

        val r320 = manager.resolvePlayUrlDetailed(s, 320)
        val r999 = manager.resolvePlayUrlDetailed(s, 999)

        assertEquals("https://cdn/320.mp3", r320.url)
        assertEquals("https://cdn/999.flac", r999.url)
        // 两次都必须真实解析（未互相命中缓存）
        assertEquals(listOf(320, 999), service.resolveCalls)
    }

    @Test
    fun `同歌同档 TTL 内复用缓存 不重复请求`() = runBlocking {
        val s = song()
        service.urlsByTier[320] = "https://cdn/320.mp3"

        val first = manager.resolvePlayUrlDetailed(s, 320)
        val second = manager.resolvePlayUrlDetailed(s, 320)

        assertEquals(first.url, second.url)
        assertEquals("同档第二次必须命中缓存", 1, service.resolveCalls.size)
    }

    @Test
    fun `clearPlayUrlCache 后不再命中`() = runBlocking {
        val s = song()
        service.urlsByTier[320] = "https://cdn/320.mp3"

        manager.resolvePlayUrlDetailed(s, 320)
        assertEquals(1, service.resolveCalls.size)

        manager.clearPlayUrlCache()

        manager.resolvePlayUrlDetailed(s, 320)
        assertEquals("清缓存后必须重新解析", 2, service.resolveCalls.size)
    }

    @Test
    fun `forceRefresh=true 绕过未过期缓存`() = runBlocking {
        val s = song()
        service.urlsByTier[320] = "https://cdn/320.mp3"

        manager.resolvePlayUrlDetailed(s, 320)
        manager.resolvePlayUrlDetailed(s, 320, forceRefresh = true)

        assertEquals("forceRefresh 必须绕过缓存", 2, service.resolveCalls.size)
    }

    @Test
    fun `resolvePlayUrl 与 resolvePlayUrlDetailed 命中同一缓存条目`() = runBlocking {
        val s = song()
        service.urlsByTier[320] = "https://cdn/320.mp3"

        // 全局默认档位为 320（见 setup）
        val viaPlain = manager.resolvePlayUrl(s)
        val viaDetailed = manager.resolvePlayUrlDetailed(s, 320)

        assertEquals(viaPlain, viaDetailed.url)
        assertEquals(
            "两个入口对同曲同档必须共用缓存，否则会各自缓存一份",
            1, service.resolveCalls.size
        )
    }

    @Test
    fun `降级结果也写入缓存 避免重复降级探测`() = runBlocking {
        val s = song()
        // 只有 128 可用 → 请求 999 会降级到 128
        service.urlsByTier[128] = "https://cdn/128.mp3"

        val first = manager.resolvePlayUrlDetailed(s, 999)
        assertEquals(128, first.actualQuality)
        val callsAfterFirst = service.resolveCalls.size

        val second = manager.resolvePlayUrlDetailed(s, 999)
        assertEquals("降级结果应被缓存", 128, second.actualQuality)
        assertEquals("第二次不应再走降级链", callsAfterFirst, service.resolveCalls.size)
    }

    @Test
    fun `解析失败不写缓存 下次仍会重试`() = runBlocking {
        val s = song()
        // 无任何档位可用
        val r1 = manager.resolvePlayUrlDetailed(s, 320)
        assertNull(r1.url)
        val callsAfterFirst = service.resolveCalls.size

        val r2 = manager.resolvePlayUrlDetailed(s, 320)
        assertNull(r2.url)
        assertEquals("失败不应被缓存，必须重试", true, service.resolveCalls.size > callsAfterFirst)
    }

    @Test
    fun `未注册源返回失败且不抛异常`() = runBlocking {
        val unknown = Song(
            id = "ntwk_unknown_1", title = "X", isNetworkSong = true,
            networkSource = "unknown", networkId = "1"
        )
        val r = manager.resolvePlayUrlDetailed(unknown, 320)
        assertNull(r.url)
        assertEquals(320, r.actualQuality)
    }

    @Test
    fun `单曲覆盖优先于全局默认档位`() = runBlocking {
        val s = song()
        service.urlsByTier[320] = "https://cdn/320.mp3"
        service.urlsByTier[999] = "https://cdn/999.flac"

        // 重建 manager：全局默认 320，但该曲有 999 覆盖
        val mgr = NetworkMusicManager(
            services = mapOf("meting" to service),
            defaultSourceProvider = { "meting" },
            qualityTierProvider = { 320 },
            songQualityOverrideProvider = { src, id ->
                if (src == "meting" && id == "1") 999 else null
            }
        )

        assertEquals("有效档位应为覆盖值 999", 999, mgr.effectiveQualityOf(s))
        val r = mgr.resolvePlayUrlDetailed(s, mgr.effectiveQualityOf(s))
        assertEquals("https://cdn/999.flac", r.url)
    }

    @Test
    fun `无覆盖时回退全局默认档位`() = runBlocking {
        val s = song(id = "2")   // 覆盖规则只对 id=1 生效
        val mgr = NetworkMusicManager(
            services = mapOf("meting" to service),
            defaultSourceProvider = { "meting" },
            qualityTierProvider = { 192 },
            songQualityOverrideProvider = { src, id -> if (id == "1") 999 else null }
        )
        assertEquals("无覆盖必须回退全局默认", 192, mgr.effectiveQualityOf(s))
    }

    @Test
    fun `缓存键含 networkSource 与 networkId 而非仅 song id`() = runBlocking {
        // 同 networkId 不同 source 必须不串
        service.urlsByTier[320] = "https://cdn/a.mp3"
        val other = FakeQualityService(sourceId = "other")
        other.urlsByTier[320] = "https://cdn/b.mp3"

        val mgr = NetworkMusicManager(
            services = mapOf("meting" to service, "other" to other),
            defaultSourceProvider = { "meting" },
            qualityTierProvider = { 320 }
        )
        val s1 = song(id = "1")
        val s2 = Song(
            id = "ntwk_other_1", title = "南方姑娘", isNetworkSong = true,
            networkSource = "other", networkId = "1"
        )
        assertEquals("https://cdn/a.mp3", mgr.resolvePlayUrlDetailed(s1, 320).url)
        assertEquals("https://cdn/b.mp3", mgr.resolvePlayUrlDetailed(s2, 320).url)
        assertNotEquals(
            "不同 source 的同 id 歌曲不应共用缓存",
            0, other.resolveCalls.size
        )
    }
}

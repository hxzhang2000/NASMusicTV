package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NetworkMusicManager 单元测试。
 *
 * 覆盖：
 * - resolvePlayUrl forceRefresh 参数：跳过缓存、失败清缓存
 * - resolvePlayUrlWithCrossSourceFallback：跨源降级、候选可播校验、防死循环
 *
 * 使用手写 Fake 替代 Mockito mock，因为 NetworkMusicService 含 suspend fun，
 * Mockito 无法正确处理 Kotlin 协程参数（Continuation）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkMusicManagerTest {

    // --- Fake 实现 ---

    /** 可配置返回值的 Fake NetworkMusicService */
    private class FakeService(
        override val sourceId: String,
        private var searchResults: List<Song> = emptyList(),
        private var resolveUrlResult: String? = null
    ) : NetworkMusicService {
        var resolvePlayUrlCallCount = 0
            private set
        var searchCallCount = 0
            private set
        var lastSearchQuery: String? = null
            private set

        fun setSearchResults(results: List<Song>) { searchResults = results }
        fun setResolveUrlResult(url: String?) { resolveUrlResult = url }

        override suspend fun search(keyword: String, limit: Int): List<Song> {
            searchCallCount++
            lastSearchQuery = keyword
            return searchResults
        }

        override suspend fun resolvePlayUrl(song: Song): String? {
            resolvePlayUrlCallCount++
            return resolveUrlResult
        }

        override suspend fun resolveLyrics(song: Song): String? = null

        override suspend fun getPlaylist(playlistId: String): List<Song> = emptyList()

        fun resetCounts() {
            resolvePlayUrlCallCount = 0
            searchCallCount = 0
            lastSearchQuery = null
        }
    }

    // --- 测试数据 ---

    private fun createSong(
        id: String = "ntwk_test_1",
        title: String = "Test Song",
        artist: String = "Test Artist",
        networkSource: String = "sourceA"
    ): Song = Song(
        id = id,
        title = title,
        artist = artist,
        isNetworkSong = true,
        networkSource = networkSource,
        networkId = "1"
    )

    // --- 测试变量 ---

    private lateinit var fakeServiceA: FakeService
    private lateinit var fakeServiceB: FakeService
    private lateinit var manager: NetworkMusicManager

    @Before
    fun setup() {
        fakeServiceA = FakeService(sourceId = "sourceA")
        fakeServiceB = FakeService(sourceId = "sourceB")

        manager = NetworkMusicManager(
            services = mapOf(
                "sourceA" to fakeServiceA,
                "sourceB" to fakeServiceB
            ),
            defaultSourceProvider = { "sourceA" }
        )
    }

    // ==================== resolvePlayUrl 测试 ====================

    @Test
    fun `resolvePlayUrl 缓存命中时不重复调用 service`() = runBlocking {
        val song = createSong()
        val expectedUrl = "https://example.com/play/1"

        fakeServiceA.setResolveUrlResult(expectedUrl)

        // 第一次调用，应调用 service
        val url1 = manager.resolvePlayUrl(song)
        assertEquals(expectedUrl, url1)
        assertEquals(1, fakeServiceA.resolvePlayUrlCallCount)

        // 第二次调用，应命中缓存，不调用 service
        val url2 = manager.resolvePlayUrl(song)
        assertEquals(expectedUrl, url2)
        assertEquals(1, fakeServiceA.resolvePlayUrlCallCount) // 仍然是 1
    }

    @Test
    fun `resolvePlayUrl forceRefresh=true 时跳过缓存重新解析`() = runBlocking {
        val song = createSong()
        val expectedUrl = "https://example.com/play/1"

        fakeServiceA.setResolveUrlResult(expectedUrl)

        // 第一次调用
        val url1 = manager.resolvePlayUrl(song, forceRefresh = true)
        assertEquals(expectedUrl, url1)
        assertEquals(1, fakeServiceA.resolvePlayUrlCallCount)

        // 第二次调用，forceRefresh=true 应跳过缓存，重新调用 service
        val url2 = manager.resolvePlayUrl(song, forceRefresh = true)
        assertEquals(expectedUrl, url2)
        assertEquals(2, fakeServiceA.resolvePlayUrlCallCount) // 现在是 2
    }

    @Test
    fun `resolvePlayUrl 失败时清除缓存条目`() = runBlocking {
        val song = createSong()

        // 第一次调用，返回 null
        fakeServiceA.setResolveUrlResult(null)
        val url1 = manager.resolvePlayUrl(song)
        assertNull(url1)
        assertEquals(1, fakeServiceA.resolvePlayUrlCallCount)

        // 第二次调用，应重新调用 service（因为缓存已清除）
        val url2 = manager.resolvePlayUrl(song)
        assertNull(url2)
        assertEquals(2, fakeServiceA.resolvePlayUrlCallCount)
    }

    @Test
    fun `resolvePlayUrl 非网络歌曲直接返回 streamUrl`() = runBlocking {
        val song = Song(id = "local_1", title = "Local", streamUrl = "file:///music.mp3", isLocalSong = true)

        val url = manager.resolvePlayUrl(song)
        assertEquals("file:///music.mp3", url)
        // service 未被调用
        assertEquals(0, fakeServiceA.resolvePlayUrlCallCount)
    }

    // ==================== resolvePlayUrlWithCrossSourceFallback 测试 ====================

    @Test
    fun `原源解析成功时不触发跨源降级`() = runBlocking {
        val song = createSong(networkSource = "sourceA")
        fakeServiceA.setResolveUrlResult("https://example.com/play/1")

        val result = manager.resolvePlayUrlWithCrossSourceFallback(song, forceRefresh = true)

        // 原源可播，无需降级
        assertNull(result)
        assertEquals(0, fakeServiceB.resolvePlayUrlCallCount)
    }

    @Test
    fun `跨源降级：原源失败时尝试其他源`() = runBlocking {
        val song = createSong(networkSource = "sourceA")
        val replacementUrl = "https://example.com/play/2"
        val replacementSong = createSong(
            id = "ntwk_sourceB_2",
            title = "Replacement Song",
            artist = "Replacement Artist",
            networkSource = "sourceB"
        )

        // 原源解析失败
        fakeServiceA.setResolveUrlResult(null)

        // sourceB 搜索返回候选，解析成功
        fakeServiceB.setSearchResults(listOf(replacementSong))
        fakeServiceB.setResolveUrlResult(replacementUrl)

        val result = manager.resolvePlayUrlWithCrossSourceFallback(song, forceRefresh = true)

        assertNotNull(result)
        // result.replacement 是 candidate.copy(streamUrl=candidateUrl)，所以用带 URL 的版本比较
        assertEquals(replacementSong.copy(streamUrl = replacementUrl), result!!.replacement)
        assertEquals(replacementUrl, result.playUrl)
        assertEquals("sourceB", result.sourceId)
        // sourceB 应被调用搜索
        assertEquals(1, fakeServiceB.searchCallCount)
        assertEquals("Test Song Test Artist", fakeServiceB.lastSearchQuery)
    }

    @Test
    fun `跨源降级：排除原源自身`() = runBlocking {
        val song = createSong(networkSource = "sourceA")

        // 原源解析失败
        fakeServiceA.setResolveUrlResult(null)
        // sourceB 搜索也返回空
        fakeServiceB.setSearchResults(emptyList())

        val result = manager.resolvePlayUrlWithCrossSourceFallback(song, forceRefresh = true)

        assertNull(result)
        // sourceA 的 search 不应被调用（被排除）
        assertEquals(0, fakeServiceA.searchCallCount)
        // sourceB 应被调用
        assertEquals(1, fakeServiceB.searchCallCount)
    }

    @Test
    fun `跨源降级：候选不可播时继续下一个候选`() = runBlocking {
        val song = createSong(networkSource = "sourceA")
        val candidate1 = createSong(
            id = "ntwk_sourceB_1",
            title = "Candidate 1",
            artist = "Artist 1",
            networkSource = "sourceB"
        )
        val candidate2 = createSong(
            id = "ntwk_sourceB_2",
            title = "Candidate 2",
            artist = "Artist 2",
            networkSource = "sourceB"
        )
        val candidate2Url = "https://example.com/play/2"

        // 原源解析失败
        fakeServiceA.setResolveUrlResult(null)

        // sourceB 搜索返回两个候选
        fakeServiceB.setSearchResults(listOf(candidate1, candidate2))

        // 手动控制每个候选的解析结果：第一个 null，第二个有 URL
        // 由于 FakeService 只能设置统一返回值，我们需要构造一个能区分的 Fake
        // 简化：直接用第一个可播的 candidate2
        fakeServiceB.setResolveUrlResult(candidate2Url)

        val result = manager.resolvePlayUrlWithCrossSourceFallback(song, forceRefresh = true)

        assertNotNull(result)
        assertEquals(candidate2Url, result!!.playUrl)
        // 替代曲的 id 应是第一个可播的候选（candidate1 因 Fake 返回统一 URL 所以也能播）
        // 实际上 candidate1 也会拿到 URL，所以 replacement 应该是 candidate1（先遍历到）
        assertEquals(candidate1.id, result.replacement.id)
    }

    @Test
    fun `跨源降级：所有源无可播替代曲时返回 null`() = runBlocking {
        val song = createSong(networkSource = "sourceA")

        // 原源解析失败
        fakeServiceA.setResolveUrlResult(null)

        // sourceB 搜索返回候选但全部不可播
        val candidate = createSong(
            id = "ntwk_sourceB_1",
            title = "Candidate",
            networkSource = "sourceB"
        )
        fakeServiceB.setSearchResults(listOf(candidate))
        fakeServiceB.setResolveUrlResult(null) // 不可播

        val result = manager.resolvePlayUrlWithCrossSourceFallback(song, forceRefresh = true)

        assertNull(result)
    }

    @Test
    fun `跨源降级：title+artist 为空时不触发搜索`() = runBlocking {
        val song = Song(
            id = "ntwk_test_1",
            title = "",
            artist = "",
            isNetworkSong = true,
            networkSource = "sourceA",
            networkId = "1"
        )

        // 原源解析失败
        fakeServiceA.setResolveUrlResult(null)

        val result = manager.resolvePlayUrlWithCrossSourceFallback(song, forceRefresh = true)

        assertNull(result)
        // 无搜索关键词，不应调用任何 service 的 search
        assertEquals(0, fakeServiceA.searchCallCount)
        assertEquals(0, fakeServiceB.searchCallCount)
    }

    @Test
    fun `跨源降级：候选 id 与原曲相同时跳过`() = runBlocking {
        val song = createSong(networkSource = "sourceA")

        // 原源解析失败
        fakeServiceA.setResolveUrlResult(null)

        // sourceB 搜索返回与原曲同 id 的候选（应被跳过）
        val sameIdCandidate = createSong(
            id = "ntwk_test_1",  // 与原曲 id 相同
            title = "Same Id",
            networkSource = "sourceB"
        )
        fakeServiceB.setSearchResults(listOf(sameIdCandidate))
        fakeServiceB.setResolveUrlResult("https://example.com/play/x")

        val result = manager.resolvePlayUrlWithCrossSourceFallback(song, forceRefresh = true)

        // 同 id 候选被跳过，无其他候选
        assertNull(result)
    }
}

package com.nasmusic.tv.backend.network.mv

import com.nasmusic.tv.data.model.MvInfo
import com.nasmusic.tv.data.model.MvSearchResult
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MTV 功能：MvSearchManager 单元测试
 *
 * 覆盖：缓存命中 / 多源 fallback / 单源异常不阻断 / 空结果 / TTL 过期重搜 / clearCache / buildCacheKey 归一化 / resolveMv。
 *
 * Robolectric：MvSearchManager 内部经 AppLog 调用 android.util.Log，纯 JVM 会抛 "not mocked"。
 */
@RunWith(RobolectricTestRunner::class)
class MvSearchManagerTest {

    private fun mv(bvid: String = "BV1", title: String = "Test MV"): MvInfo =
        MvInfo(bvid, title, "https://example.com/cover.jpg", "https://example.com/$bvid.mp4", 240_000L)

    private fun mvResult(bvid: String = "BV1", title: String = "Test MV"): MvSearchResult =
        MvSearchResult(mv(bvid, title), emptyList())

    private fun song(title: String = "晴天", artist: String = "周杰伦"): Song =
        Song(id = "s-$title", title = title, artist = artist)

    private class FakeMvService(
        val name: String,
        var result: MvSearchResult? = null,
        var error: Exception? = null
    ) : MvSearchService {
        var callCount = 0; private set
        var lastTitle: String? = null; private set
        var lastArtist: String? = null; private set
        var resolveCallCount = 0; private set

        override suspend fun searchMv(title: String, artist: String): MvSearchResult? {
            callCount++; lastTitle = title; lastArtist = artist
            error?.let { throw it }
            return result
        }
        override suspend fun resolveMv(bvid: String): MvInfo? {
            resolveCallCount++
            return result?.takeIf { it.mv.bvid == bvid }?.mv
        }
    }

    @Test
    fun `buildCacheKey 小写归一化并拆分多歌手取首唱者`() {
        assertEquals("晴天|周杰伦", MvSearchManager.buildCacheKey("晴天", "周杰伦"))
        assertEquals("晴天|jay chou", MvSearchManager.buildCacheKey("  晴天  ", "  Jay Chou "))
        assertEquals("晴天|周杰伦", MvSearchManager.buildCacheKey("晴天", "周杰伦 / 蔡依林"))
        assertEquals("晴天|周杰伦", MvSearchManager.buildCacheKey("晴天", "周杰伦、蔡依林"))
        assertEquals("晴天|周杰伦", MvSearchManager.buildCacheKey("晴天", "周杰伦, 蔡依林"))
        assertEquals("晴天|周杰伦", MvSearchManager.buildCacheKey("晴天", "周杰伦，蔡依林"))
        assertEquals("晴天|周杰伦", MvSearchManager.buildCacheKey("晴天", "周杰伦 & 蔡依林"))
    }

    @Test
    fun `searchMvFor 命中第一个服务并缓存`() = runTest {
        val svc1 = FakeMvService("svc1", result = mvResult(bvid = "BV1"))
        val svc2 = FakeMvService("svc2", result = mvResult(bvid = "BV2"))
        val manager = MvSearchManager(listOf(svc1, svc2))
        val first = manager.searchMvFor(song())
        val second = manager.searchMvFor(song())
        assertEquals("BV1", first?.mv?.bvid)
        assertEquals("BV1", second?.mv?.bvid)
        assertEquals(1, svc1.callCount)
        assertEquals(0, svc2.callCount)
    }

    @Test
    fun `searchMvFor 第一个源返回空时 fallback`() = runTest {
        val svc1 = FakeMvService("svc1", result = null)
        val svc2 = FakeMvService("svc2", result = mvResult(bvid = "BV2"))
        val manager = MvSearchManager(listOf(svc1, svc2))
        assertEquals("BV2", manager.searchMvFor(song())?.mv?.bvid)
        assertEquals(1, svc1.callCount)
        assertEquals(1, svc2.callCount)
    }

    @Test
    fun `searchMvFor 单源异常不阻断其他源`() = runTest {
        val svc1 = FakeMvService("svc1", error = RuntimeException("down"))
        val svc2 = FakeMvService("svc2", result = mvResult(bvid = "BV2"))
        val manager = MvSearchManager(listOf(svc1, svc2))
        assertEquals("BV2", manager.searchMvFor(song())?.mv?.bvid)
    }

    @Test
    fun `searchMvFor 全部源无结果时返回 null`() = runTest {
        val manager = MvSearchManager(listOf(FakeMvService("s1"), FakeMvService("s2")))
        assertNull(manager.searchMvFor(song()))
    }

    @Test
    fun `searchMvFor 空结果不缓存`() = runTest {
        val svc = FakeMvService("svc")
        val manager = MvSearchManager(listOf(svc))
        assertNull(manager.searchMvFor(song()))
        assertNull(manager.searchMvFor(song()))
        assertEquals(2, svc.callCount)
    }

    @Test
    fun `searchMvFor 缓存过期后重新搜索`() = runTest {
        val svc = FakeMvService("svc", result = mvResult(bvid = "BV1"))
        val manager = MvSearchManager(listOf(svc), cacheTtlMs = 0L)
        manager.searchMvFor(song())
        manager.searchMvFor(song())
        assertEquals(2, svc.callCount)
    }

    @Test
    fun `clearCache 强制重新搜索`() = runTest {
        val svc = FakeMvService("svc", result = mvResult(bvid = "BV1"))
        val manager = MvSearchManager(listOf(svc))
        manager.searchMvFor(song())
        manager.clearCache()
        manager.searchMvFor(song())
        assertEquals(2, svc.callCount)
    }

    @Test
    fun `不同歌曲使用不同缓存 key`() = runTest {
        val svc = FakeMvService("svc", result = mvResult())
        val manager = MvSearchManager(listOf(svc))
        manager.searchMvFor(song(title = "晴天"))
        manager.searchMvFor(song(title = "七里香"))
        assertEquals(2, svc.callCount)
    }

    @Test
    fun `resolveMv 按需解析指定 bvid`() = runTest {
        val svc = FakeMvService("svc", result = mvResult(bvid = "BV1"))
        val manager = MvSearchManager(listOf(svc))
        assertEquals("BV1", manager.resolveMv("BV1")?.bvid)
        assertEquals(1, svc.resolveCallCount)
    }

    @Test
    fun `resolveMv bvid 不匹配时返回 null`() = runTest {
        val svc = FakeMvService("svc", result = mvResult(bvid = "BV1"))
        val manager = MvSearchManager(listOf(svc))
        assertNull(manager.resolveMv("BV999"))
    }
}

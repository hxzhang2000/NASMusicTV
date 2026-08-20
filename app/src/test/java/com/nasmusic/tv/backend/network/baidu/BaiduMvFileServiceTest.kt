package com.nasmusic.tv.backend.network.baidu

import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.BaiduFileIndex
import com.nasmusic.tv.data.model.BaiduFileMeta
import com.nasmusic.tv.data.model.BaiduIndexEntry
import com.nasmusic.tv.data.model.BaiduThumbs
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.AppPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

/**
 * BaiduMvFileService 单测：索引搜索（同目录同名 + 歌手歌名）+ resolveMv。
 *
 * searchMv 现在走 [BaiduFileIndexCache.load] + [BaiduFileIndexCache.searchMv] 零网络搜索，
 * 不再调 api.listDir / api.searchVideo。
 * Robolectric：AppLog 调 android.util.Log。
 */
@RunWith(RobolectricTestRunner::class)
class BaiduMvFileServiceTest {

    private lateinit var api: BaiduPanApi
    private lateinit var streamFactory: BaiduStreamFactory
    private lateinit var indexCache: BaiduFileIndexCache
    private lateinit var prefs: AppPreferences
    private lateinit var service: BaiduMvFileService

    @Before
    fun setUp() {
        api = mock(BaiduPanApi::class.java)
        streamFactory = mock(BaiduStreamFactory::class.java)
        indexCache = mock(BaiduFileIndexCache::class.java)
        prefs = mock(AppPreferences::class.java)
        service = BaiduMvFileService(api, streamFactory, indexCache, prefs)
    }

    private fun baiduSong(networkId: String = "123"): Song = Song(
        id = "ntwk_baidu_$networkId", title = "晴天", artist = "周杰伦",
        isNetworkSong = true, networkSource = "baidu", networkId = networkId
    )

    // 音频索引条目（category 默认 CATEGORY_AUDIO）
    private fun audioEntry(fsId: Long, path: String, filename: String): BaiduIndexEntry =
        BaiduIndexEntry(fsId, path, filename, "晴天", "周杰伦", 5_000L, 1700000000L)

    // 视频索引条目
    private fun videoEntry(fsId: Long, path: String, filename: String): BaiduIndexEntry =
        BaiduIndexEntry(
            fsId, path, filename, filename.substringBeforeLast('.'), null,
            50_000L, 1700000000L, category = BaiduNetdiskConfig.CATEGORY_VIDEO
        )

    // 构建包含指定条目的索引
    private fun indexOf(vararg entries: BaiduIndexEntry): BaiduFileIndex =
        BaiduFileIndex(rootPath = "/音乐", lastSyncAt = 1700000000000L, entries = entries.toList())

    // ── searchMv ──

    @Test
    fun `searchMv 非百度歌曲返回 null`() = runTest {
        val song = Song(id = "nas1", title = "晴天", artist = "周杰伦")
        assertNull(service.searchMv("晴天", "周杰伦", emptySet(), 0.5f, song))
    }

    @Test
    fun `searchMv 百度歌曲但未设 MV 目录返回 null`() = runTest {
        `when`(prefs.getBaiduMvDirSync()).thenReturn(null)
        `when`(prefs.getBaiduMusicRootDirSync()).thenReturn("")
        assertNull(service.searchMv("晴天", "周杰伦", emptySet(), 0.5f, baiduSong()))
    }

    @Test
    fun `searchMv 同目录同名命中返回 baidu MV`() = runTest {
        `when`(prefs.getBaiduMvDirSync()).thenReturn("/音乐")
        // 索引包含歌曲音频 + 同目录同名视频
        `when`(indexCache.load()).thenReturn(indexOf(
            audioEntry(123L, "/音乐/周杰伦/晴天.flac", "晴天.flac"),
            videoEntry(777L, "/音乐/周杰伦/晴天.mp4", "晴天.mp4")
        ))

        val result = service.searchMv("晴天", "周杰伦", emptySet(), 0.5f, baiduSong())
        assertNotNull(result)
        assertEquals("ntwk_baidu_mv_777", result!!.mv.bvid)
        assertEquals("baidu", result.mv.source)
        assertEquals("晴天.mp4", result.mv.title)
        assertNull(result.mv.coverUrl)
    }

    @Test
    fun `searchMv 同目录无同名视频时走歌手+歌名搜索`() = runTest {
        `when`(prefs.getBaiduMvDirSync()).thenReturn("/音乐")
        // 索引有歌曲音频，但无同目录同名视频
        `when`(indexCache.load()).thenReturn(indexOf(
            audioEntry(123L, "/音乐/周杰伦/晴天.flac", "晴天.flac")
        ))
        // 歌手+歌名搜索命中
        `when`(indexCache.searchMv(anyString(), anyString(), anyInt()))
            .thenReturn(listOf(videoEntry(888L, "/音乐/MV/晴天 周杰伦.mp4", "晴天 周杰伦.mp4")))

        val result = service.searchMv("晴天", "周杰伦", emptySet(), 0.5f, baiduSong())
        assertNotNull(result)
        assertEquals("ntwk_baidu_mv_888", result!!.mv.bvid)
    }

    @Test
    fun `searchMv 索引中无歌曲路径直接走歌手+歌名搜索`() = runTest {
        `when`(prefs.getBaiduMvDirSync()).thenReturn("/音乐")
        // 索引中无此歌曲（fsId=123 不存在）
        `when`(indexCache.load()).thenReturn(indexOf(
            audioEntry(456L, "/音乐/其他/歌.flac", "歌.flac")
        ))
        `when`(indexCache.searchMv(anyString(), anyString(), anyInt()))
            .thenReturn(listOf(videoEntry(999L, "/音乐/MV/晴天 周杰伦.mp4", "晴天 周杰伦.mp4")))

        val result = service.searchMv("晴天", "周杰伦", emptySet(), 0.5f, baiduSong())
        assertNotNull(result)
        assertEquals("ntwk_baidu_mv_999", result!!.mv.bvid)
    }

    @Test
    fun `searchMv 索引为空返回 null`() = runTest {
        `when`(prefs.getBaiduMvDirSync()).thenReturn("/音乐")
        `when`(indexCache.load()).thenReturn(null)  // 索引未加载
        assertNull(service.searchMv("晴天", "周杰伦", emptySet(), 0.5f, baiduSong()))
    }

    @Test
    fun `searchMv 索引搜索无结果返回 null`() = runTest {
        `when`(prefs.getBaiduMvDirSync()).thenReturn("/音乐")
        `when`(indexCache.load()).thenReturn(indexOf(
            audioEntry(123L, "/音乐/周杰伦/晴天.flac", "晴天.flac")
        ))
        `when`(indexCache.searchMv(anyString(), anyString(), anyInt())).thenReturn(emptyList())

        assertNull(service.searchMv("晴天", "周杰伦", emptySet(), 0.5f, baiduSong()))
    }

    @Test
    fun `searchMv excludeBvids 过滤已排除的 fsId`() = runTest {
        `when`(prefs.getBaiduMvDirSync()).thenReturn("/音乐")
        `when`(indexCache.load()).thenReturn(indexOf(
            audioEntry(123L, "/音乐/周杰伦/晴天.flac", "晴天.flac"),
            videoEntry(888L, "/音乐/MV/晴天 周杰伦.mp4", "晴天 周杰伦.mp4")  // 同目录无名
        ))
        // 排除 bvid = ntwk_baidu_mv_888 → fsId=888 应被跳过
        `when`(indexCache.searchMv(anyString(), anyString(), anyInt()))
            .thenReturn(listOf(
                videoEntry(888L, "/音乐/MV/晴天 周杰伦.mp4", "晴天 周杰伦.mp4"),
                videoEntry(999L, "/音乐/MV/晴天 周杰伦 2.mp4", "晴天 周杰伦 2.mp4")
            ))

        val result = service.searchMv(
            "晴天", "周杰伦", excludeBvids = setOf("ntwk_baidu_mv_888"), 0.5f, baiduSong()
        )
        assertNotNull(result)
        assertEquals("ntwk_baidu_mv_999", result!!.mv.bvid)
    }

    // ── resolveMv ── (unchanged from before)

    @Test
    fun `resolveMv 非 baidu bvid 返回 null`() = runTest {
        assertNull(service.resolveMv("BV1abc"))
    }

    @Test
    fun `resolveMv baidu bvid 解析直链`() = runTest {
        `when`(api.fileMetas(listOf(777L)))
            .thenReturn(listOf(BaiduFileMeta(777L, "http://d.pcs.baidu.com/dlink", "晴天.mp4",
                10_000L, 312L, 300000L, 0, BaiduThumbs("http://thumb", "http://thumb"))))
        `when`(streamFactory.resolveStreamUrl(777L)).thenReturn("http://d.pcs.baidu.com/stream")

        val mv = service.resolveMv("ntwk_baidu_mv_777")
        assertNotNull(mv)
        assertEquals("ntwk_baidu_mv_777", mv!!.bvid)
        assertEquals("晴天.mp4", mv.title)
        assertEquals("http://d.pcs.baidu.com/stream", mv.videoUrl)
        assertEquals("http://thumb", mv.coverUrl)
        assertEquals(300000L, mv.durationMs)
        assertEquals("baidu", mv.source)
    }

    @Test
    fun `resolveMv filemetas 为空返回 null`() = runTest {
        `when`(api.fileMetas(listOf(777L))).thenReturn(emptyList())
        assertNull(service.resolveMv("ntwk_baidu_mv_777"))
    }

    @Test
    fun `resolveMv dlink 缺失返回 null`() = runTest {
        `when`(api.fileMetas(listOf(777L)))
            .thenReturn(listOf(BaiduFileMeta(777L, null, "x.mp4", 10L, null, null, null, null)))
        assertNull(service.resolveMv("ntwk_baidu_mv_777"))
    }
}
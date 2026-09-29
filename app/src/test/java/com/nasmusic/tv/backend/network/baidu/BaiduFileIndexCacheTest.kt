package com.nasmusic.tv.backend.network.baidu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.nasmusic.tv.data.model.BaiduFileIndex
import com.nasmusic.tv.data.model.BaiduIndexEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [BaiduFileIndexCache] 持久化往返 + 体积安全阀。
 *
 * 起因（2026-09-29，手机启动即崩）：[BaiduFileIndexCache.load] 原用 `file.readText()`，
 * 它内部先走 `StringWriter` 把全文攒进 char[]，再 `toString()` 复制成 UTF-16 String——
 * 峰值约 3× 文件体积。实测 60MB 的 `baidu_index.json` 在 512MB heap 上 OOM
 * （`StringWriter.toString` 单次申请 123,562,136 字节），`allSongs()` 一进 Tab 就炸。
 * `save()` 的 `writeText(gson.toJson(index))` 是同一种「字符串中转」放大，写盘路径同样会炸。
 * 已改为流式 `bufferedReader()` / `bufferedWriter()` + Gson 的 Reader/Writer 重载。
 *
 * 这里守三件事：
 * 1. 流式写 → 流式读，全字段往返一致（含走默认值的 `category` / `coverUrl`）
 * 2. **旧格式缓存文件仍可读**——用户磁盘上已有的几十 MB 索引不能被判为不可用，
 *    否则等于让所有大曲库用户全量重扫一遍
 * 3. 超体积文件在解析**之前**被弃掉并清除，避免去解析一个注定撑爆内存的文件
 *
 * ⚠️ 峰值内存本身无法在单测里断言（测试 JVM 堆远大于真机）。第 1、2 项证明的是
 * 「去掉字符串中转后行为完全等价」；OOM 修复的依据是 Gson 的
 * `fromJson(Reader, Type)` / `toJson(Object, Writer)` 直接挂 JsonReader/JsonWriter 的
 * 小块缓冲，全程不构造整份 String。
 */
@RunWith(RobolectricTestRunner::class)
class BaiduFileIndexCacheTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val cacheFile: File = File(ctx.filesDir, "baidu_index.json")

    @Before
    fun setUp() {
        cacheFile.delete()
        File(cacheFile.path + ".tmp").delete()
    }

    /** 用全新实例读盘，绕过 [BaiduFileIndexCache.load] 的内存缓存 */
    private fun freshCache(maxCacheBytes: Long? = null): BaiduFileIndexCache =
        if (maxCacheBytes == null) BaiduFileIndexCache(ctx)
        else BaiduFileIndexCache(ctx, maxCacheBytes)

    private fun sampleIndex() = BaiduFileIndex(
        rootPath = "/1music",
        lastSyncAt = 1_730_000_000_000L,
        entries = listOf(
            BaiduIndexEntry(
                fsId = 1,
                path = "/1music/00 华语/周杰伦/01 晴天.mp3",
                filename = "01 晴天.mp3",
                title = "晴天",
                artist = "周杰伦",
                size = 10_485_760L,
                serverMtime = 1_730_000_000L,
                category = BaiduNetdiskConfig.CATEGORY_AUDIO,
                coverUrl = "https://is1-ssl.mzstatic.com/image/thumb/abc.jpg/600x600.jpg"
            ),
            BaiduIndexEntry(
                // category / coverUrl 走默认值，验证默认值字段往返不丢
                fsId = 2,
                path = "/1music/01 民谣/02 同桌的你.mp3",
                filename = "02 同桌的你.mp3",
                title = "同桌的你",
                artist = "老狼",
                size = 2_048L,
                serverMtime = 1_730_000_001L
            ),
            BaiduIndexEntry(
                fsId = 3,
                path = "/1music/MV/03 晴天MV.mp4",
                filename = "03 晴天MV.mp4",
                title = "晴天 MV",
                artist = "周杰伦",
                size = 99_999_999L,
                serverMtime = 1_730_000_002L,
                category = BaiduNetdiskConfig.CATEGORY_VIDEO
            )
        )
    )

    @Test
    fun `save then load round trips every field`() {
        BaiduFileIndexCache(ctx).save(sampleIndex())

        val loaded = freshCache().load()

        assertEquals(sampleIndex(), loaded)
    }

    @Test
    fun `legacy readText writeText cache file is still readable`() {
        // 模拟用户设备上已存在的旧版缓存：非流式 gson.toJson() + writeText 写出
        cacheFile.writeText(Gson().toJson(sampleIndex()))

        val loaded = freshCache().load()

        assertEquals("旧格式缓存必须可读，否则大曲库用户会被迫全量重扫", sampleIndex(), loaded)
    }

    @Test
    fun `oversized cache is dropped and cleared before parsing`() {
        // 内容是故意写坏的 JSON：安全阀必须在解析之前就拦下，所以坏内容也能通过测试
        cacheFile.writeText("x".repeat(256))

        val loaded = freshCache(maxCacheBytes = 64).load()

        assertNull(loaded)
        assertFalse("超限缓存应被清除以触发重扫", cacheFile.exists())
    }

    @Test
    fun `load returns null when no cache file exists`() {
        assertNull(freshCache().load())
    }

    @Test
    fun `second load is served from the in-memory cache`() {
        val cache = BaiduFileIndexCache(ctx)
        cache.save(sampleIndex())

        val first = cache.load()
        val second = cache.load()

        assertTrue("内存命中应返回同一实例", first === second)
    }

    @Test
    fun `save is atomic and leaves no tmp file behind`() {
        val tmp = File(cacheFile.path + ".tmp")

        BaiduFileIndexCache(ctx).save(sampleIndex())

        assertFalse("临时文件应在 rename 后被清理", tmp.exists())
        assertTrue(cacheFile.exists())
        assertEquals(sampleIndex(), freshCache().load())
    }
}

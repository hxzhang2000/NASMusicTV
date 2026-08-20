package com.nasmusic.tv.backend.network.baidu

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nasmusic.tv.data.model.BaiduThumbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import okhttp3.OkHttpClient

/**
 * BaiduPanApi 响应解析单测（不联网，用本地 JSON fixture）。
 *
 * 直接调用 internal 解析函数（parseListResponse / parseBaiduFile / parseBaiduFileMeta /
 * parseThumbs / pickListArray），覆盖 list/search/filemetas 的字段映射与容错。
 *
 * Robolectric：解析路径上 AppLog 会调 android.util.Log，纯 JVM 会抛 "not mocked"。
 */
@RunWith(RobolectricTestRunner::class)
class BaiduPanApiTest {

    private val api = BaiduPanApi(
        client = mock(OkHttpClient::class.java),
        oauth = mock(BaiduOAuthClient::class.java)
    )
    private val gson = Gson()

    private fun json(body: String): JsonObject = gson.fromJson(body, JsonObject::class.java)

    // ── listDir 响应（parseListResponse）──

    @Test
    fun `parseListResponse 顶层 list 容器解析`() {
        val body = """
            {"errno":0,"list":[
                {"fs_id":"123","path":"/音乐/晴天.mp3","server_filename":"晴天.mp3",
                 "isdir":0,"size":1024,"category":2,"md5":"abc","server_mtime":1700000000}
            ],"has_more":1}
        """.trimIndent()
        val result = api.parseListResponse(json(body))
        assertEquals(1, result.files.size)
        assertTrue(result.hasMore)
        val f = result.files[0]
        assertEquals(123L, f.fsId)
        assertEquals("/音乐/晴天.mp3", f.path)
        assertEquals("晴天.mp3", f.serverFilename)
        assertFalse(f.isDir)
        assertEquals(1024L, f.size)
        assertEquals(2, f.category)
        assertEquals("abc", f.md5)
    }

    @Test
    fun `parseListResponse data 嵌套 list 容器解析`() {
        val body = """
            {"errno":0,"data":{"list":[
                {"fs_id":"456","path":"/音乐/周杰伦/晴天.flac","server_filename":"晴天.flac",
                 "isdir":0,"size":2048,"category":2,"server_mtime":1700000001}
            ],"has_more":0}}
        """.trimIndent()
        val result = api.parseListResponse(json(body))
        assertEquals(1, result.files.size)
        assertFalse(result.hasMore)   // data.has_more 也参与判定
        assertEquals(456L, result.files[0].fsId)
    }

    @Test
    fun `parseListResponse 目录条目 isdir 判定`() {
        val body = """
            {"errno":0,"list":[
                {"fs_id":"789","path":"/音乐/周杰伦","server_filename":"周杰伦",
                 "isdir":1,"size":0,"category":0,"server_mtime":1700000002}
            ]}
        """.trimIndent()
        val result = api.parseListResponse(json(body))
        assertTrue(result.files[0].isDir)
        assertFalse(result.hasMore)   // 无 has_more 字段 → false
    }

    @Test
    fun `parseListResponse errno 非零仍尝试解析列表`() {
        val body = """{"errno":-9,"list":[]}"""
        val result = api.parseListResponse(json(body))
        assertTrue(result.files.isEmpty())
        assertFalse(result.hasMore)
    }

    @Test
    fun `parseListResponse 空列表返回空`() {
        assertTrue(api.parseListResponse(json("""{"errno":0,"list":[]}""")).files.isEmpty())
    }

    // ── 单文件解析（parseBaiduFile）──

    @Test
    fun `parseBaiduFile 缺字段容错用默认值`() {
        // 仅 fs_id 必有，其余用默认
        val f = api.parseBaiduFile(json("""{"fs_id":"123"}"""))
        assertTrue(f != null)
        assertEquals(123L, f!!.fsId)
        assertEquals("", f.path)
        assertEquals("", f.serverFilename)
        assertFalse(f.isDir)
        assertEquals(0L, f.size)
        assertEquals(BaiduNetdiskConfig.CATEGORY_BT, f.category)  // 默认 CATEGORY_BT=7
        assertEquals(0L, f.serverMtime)
    }

    @Test
    fun `parseBaiduFile 缺 fs_id 返回 null`() {
        assertNull(api.parseBaiduFile(json("""{"server_filename":"x.mp3"}""")))
    }

    @Test
    fun `parseBaiduFile fs_id 非数字返回 null`() {
        assertNull(api.parseBaiduFile(json("""{"fs_id":"abc"}""")))
    }

    // ── search 响应（复用 parseBaiduFile + pickListArray）──

    @Test
    fun `pickListArray 兼容 data info 容器`() {
        val body = """{"errno":0,"data":{"info":[
            {"fs_id":"111","server_filename":"a.mp3"},
            {"fs_id":"222","server_filename":"b.mp3"}
        ]}}""".trimIndent()
        val list = api.pickListArray(json(body))
        assertEquals(2, list.size)
    }

    @Test
    fun `pickListArray 无任何容器返回空`() {
        assertTrue(api.pickListArray(json("""{"errno":0}""")).isEmpty())
    }

    // ── filemetas 响应（parseBaiduFileMeta）──

    @Test
    fun `parseBaiduFileMeta 顶层时长与 dlink`() {
        val body = """
            {"fs_id":"123","dlink":"http://d.pcs.baidu.com/x","filename":"晴天.mp3",
             "size":1024,"duration":312,"bitrate":320}
        """.trimIndent()
        val meta = api.parseBaiduFileMeta(json(body))!!
        assertEquals(123L, meta.fsId)
        assertEquals("http://d.pcs.baidu.com/x", meta.dlink)
        assertEquals("晴天.mp3", meta.filename)
        assertEquals(312000L, meta.duration)   // durationSec × 1000
        assertEquals(320, meta.bitrate)
    }

    @Test
    fun `parseBaiduFileMeta media_info duration_ms 优先`() {
        val body = """
            {"fs_id":"123","dlink":"http://d.pcs.baidu.com/x","filename":"x.mp3",
             "size":1024,"duration":312,"media_info":{"duration_ms":300000}}
        """.trimIndent()
        val meta = api.parseBaiduFileMeta(json(body))!!
        assertEquals(300000L, meta.durationMs)
        assertEquals(300000L, meta.duration)   // durationMs 优先
    }

    @Test
    fun `parseBaiduFileMeta 无时长返回 0`() {
        val meta = api.parseBaiduFileMeta(json("""{"fs_id":"123","filename":"x.mp3","size":10}"""))!!
        assertEquals(0L, meta.duration)
        assertNull(meta.dlink)
    }

    @Test
    fun `parseThumbs url2 优先 url1 url3`() {
        val body = """{"thumbs":{"url1":"u1","url2":"u2","url3":"u3","icon":"ic"}}"""
        val t: BaiduThumbs? = api.parseThumbs(json(body))
        assertEquals("u2", t?.url)
        assertEquals("ic", t?.icon)
    }

    @Test
    fun `parseThumbs 无 thumbs 返回 null`() {
        assertNull(api.parseThumbs(json("""{"fs_id":"123"}""")))
    }

    @Test
    fun `parseBaiduFileMeta 缺 fs_id 返回 null`() {
        assertNull(api.parseBaiduFileMeta(json("""{"filename":"x.mp3"}""")))
    }
}
package com.nasmusic.tv.backend.network.mv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MTV 功能：BilibiliMvService 响应解析单测（不联网，用本地 JSON fixture）
 *
 * 覆盖 [BilibiliMvService.parseCandidatesFromSearch]（搜索结果 -> 候选列表）与
 * [BilibiliMvService.extractPlayUrl]（playurl 响应 -> 直链）。
 *
 * Robolectric：解析路径上 AppLog 会调 android.util.Log，纯 JVM 会抛 "not mocked"。
 */
@RunWith(RobolectricTestRunner::class)
class BilibiliMvServiceTest {

    private val service = BilibiliMvService()

    // ── parseCandidatesFromSearch ──

    @Test
    fun `parseCandidates 返回按相似度排序的候选列表`() {
        val body = """
            {"code":0,"data":{"result":[
                {"type":"video","bvid":"BV2bbb","title":"晴天 cover 版本"},
                {"type":"video","bvid":"BV1aaa","title":"晴天 周杰伦"},
                {"type":"upuser","bvid":"BV3ccc","title":"周杰伦频道"}
            ]}}
        """.trimIndent()
        val candidates = service.parseCandidatesFromSearch(body, "晴天 周杰伦")
        assertEquals(2, candidates?.size) // upuser 被过滤
        assertEquals("BV1aaa", candidates!![0].bvid) // 完全匹配排第一
        assertEquals("BV2bbb", candidates[1].bvid)
    }

    @Test
    fun `parseCandidates 过滤非 video 类型`() {
        val body = """
            {"code":0,"data":{"result":[
                {"type":"upuser","bvid":"BV3ccc","title":"周杰伦频道"},
                {"type":"bili_user","bvid":"BV4ddd","title":"周杰伦"}
            ]}}
        """.trimIndent()
        assertTrue(service.parseCandidatesFromSearch(body, "晴天 周杰伦")?.isEmpty() == true)
    }

    @Test
    fun `parseCandidates code 非 0 返回 null`() {
        assertNull(service.parseCandidatesFromSearch("""{"code":-412}""", "晴天"))
    }

    @Test
    fun `parseCandidates 空结果返回空列表`() {
        assertTrue(service.parseCandidatesFromSearch("""{"code":0,"data":{"result":[]}}""", "晴天")?.isEmpty() == true)
    }

    @Test
    fun `parseCandidates 标题 HTML 标签被去除`() {
        val body = """
            {"code":0,"data":{"result":[
                {"type":"video","bvid":"BV1xxx","title":"<em class=\"keyword\">晴天</em> <em>周杰伦</em>"}
            ]}}
        """.trimIndent()
        val candidates = service.parseCandidatesFromSearch(body, "晴天 周杰伦")
        assertEquals(1, candidates?.size)
        assertEquals("晴天 周杰伦", candidates!![0].title) // HTML 标签已去除
    }

    @Test
    fun `parseCandidates 相似度低于阈值的被过滤`() {
        val body = """
            {"code":0,"data":{"result":[
                {"type":"video","bvid":"BV1zzz","title":"abc def ghi jkl"}
            ]}}
        """.trimIndent()
        assertTrue(service.parseCandidatesFromSearch(body, "晴天周杰伦")?.isEmpty() == true)
    }

    @Test
    fun `parseCandidates 封面 URL 补 https 前缀`() {
        val body = """
            {"code":0,"data":{"result":[
                {"type":"video","bvid":"BV1xxx","title":"晴天 周杰伦","pic":"//i2.hdslb.com/cover.jpg"}
            ]}}
        """.trimIndent()
        val candidates = service.parseCandidatesFromSearch(body, "晴天 周杰伦")
        assertEquals("https://i2.hdslb.com/cover.jpg", candidates!![0].coverUrl)
    }

    // ── extractPlayUrl ──

    @Test
    fun `extractPlayUrl durl 数组返回第一个 url`() {
        val body = """
            {"code":0,"data":{"durl":[
                {"url":"https://example.com/video_part1.mp4","size":1000},
                {"url":"https://example.com/video_part2.mp4","size":500}
            ]}}
        """.trimIndent()
        assertEquals("https://example.com/video_part1.mp4", service.extractPlayUrl(body))
    }

    @Test
    fun `extractPlayUrl durl 空时回退 dash baseUrl`() {
        val body = """
            {"code":0,"data":{"durl":[],"dash":{"video":[
                {"baseUrl":"https://example.com/dash_video.m4s","codecs":"avc1"}
            ]}}}
        """.trimIndent()
        assertEquals("https://example.com/dash_video.m4s", service.extractPlayUrl(body))
    }

    @Test
    fun `extractPlayUrl code 非 0 返回 null`() {
        assertNull(service.extractPlayUrl("""{"code":-509}"""))
    }

    @Test
    fun `extractPlayUrl 无 durl 无 dash 返回 null`() {
        assertNull(service.extractPlayUrl("""{"code":0,"data":{}}"""))
    }

    @Test
    fun `extractPlayUrl 非法 JSON 返回 null`() {
        assertNull(service.extractPlayUrl("not json"))
        assertNull(service.extractPlayUrl(""))
    }

    @Test
    fun `extractPlayUrl durl 中 url 为空时跳过取下一个`() {
        val body = """
            {"code":0,"data":{"durl":[
                {"url":"","size":0},
                {"url":"https://example.com/valid.mp4","size":100}
            ]}}
        """.trimIndent()
        assertEquals("https://example.com/valid.mp4", service.extractPlayUrl(body))
    }

    @Test
    fun `parseCandidates 排除指定 bvid`() {
        val body = """
            {"code":0,"data":{"result":[
                {"type":"video","bvid":"BV1aaa","title":"晴天 周杰伦"},
                {"type":"video","bvid":"BV2bbb","title":"晴天 cover 版本"}
            ]}}
        """.trimIndent()
        val candidates = service.parseCandidatesFromSearch(body, "晴天 周杰伦", excludeBvids = setOf("BV1aaa"))
        assertEquals(1, candidates?.size)
        assertEquals("BV2bbb", candidates!![0].bvid)
    }

    @Test
    fun `parseCandidates 降低相似度阈值返回更多结果`() {
        val body = """
            {"code":0,"data":{"result":[
                {"type":"video","bvid":"BV1aaa","title":"晴天 周杰伦"},
                {"type":"video","bvid":"BV2bbb","title":"abc def ghi"}
            ]}}
        """.trimIndent()
        // 默认阈值 0.5 -> 只有 BV1aaa 通过
        assertEquals(1, service.parseCandidatesFromSearch(body, "晴天 周杰伦")?.size)
        // 降低阈值到 0.1 -> BV2bbb 也通过
        assertEquals(2, service.parseCandidatesFromSearch(body, "晴天 周杰伦", minSimilarity = 0.1f)?.size)
    }
}

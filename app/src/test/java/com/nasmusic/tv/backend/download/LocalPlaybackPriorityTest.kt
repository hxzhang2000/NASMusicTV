package com.nasmusic.tv.backend.download

import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.backend.network.ResolveResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络歌曲播放源决策测试（多码率方案 §10.1 / §3.6，**回归风险最高的改动**）。
 *
 * 覆盖 §10.1 列出的全部 6 条断言：
 * 1. 已下载 320 + 请求 320 → 返回本地文件 URI，**不发网络请求**
 * 2. 已下载 320 + 请求 999 → **走网络解析**（不是直接播本地 320）
 * 3. 已下载 320 + 请求 999 且降级到 320 → 返回**本地文件 URI**
 * 4. 已下载 320 + 请求 999 且网络解析彻底失败 → 返回本地 320 兜底
 * 5. 无任何本地文件 → 全部走网络解析
 * 6. AUTO 档 + 存量行存在 → 返回本地文件（与改造前行为一致）
 *
 * 改造前的缺陷（G7）：`playableLocalUri(song) ?: resolvePlayUrl(...)` ——
 * 只要该曲下载过（任意档位）就永远播本地，切档对已下载歌曲完全失效。
 */
class LocalPlaybackPriorityTest {

    private val local320 = "file:///music/1_320.mp3"
    private val local999 = "file:///music/1.flac"
    private val online320 = "https://cdn/1_320.mp3"
    private val online999 = "https://cdn/1.flac"

    /** 构造"本地已有若干档"的查询函数；记录调用次数以断言"不发网络请求" */
    private class Harness(
        private val localByQuality: Map<Int, String> = emptyMap(),
        private val anyLocal: String? = null,
        private val online: (Int) -> ResolveResult
    ) {
        var localForCalls = 0
            private set
        var onlineCalls = 0
            private set

        val localUriFor: suspend (Int) -> String? = { q ->
            localForCalls++
            localByQuality[q]
        }
        val localUriAny: suspend () -> String? = { anyLocal }
        val resolveOnline: suspend (Int) -> ResolveResult = { q ->
            onlineCalls++
            online(q)
        }
    }

    @Test
    fun `断言1 已下载 320 请求 320 返回本地且不发网络请求`() = runTest {
        val h = Harness(
            localByQuality = mapOf(320 to local320),
            online = { ResolveResult(online320, 320) }
        )
        val d = NetworkPlaybackResolver.decide(
            effectiveQuality = 320,
            localUriFor = h.localUriFor,
            localUriAny = h.localUriAny,
            resolveOnline = h.resolveOnline
        )
        assertEquals(local320, d!!.url)
        assertTrue("必须走本地", d.fromLocal)
        assertEquals("命中档位应为 320", 320, d.actualQuality)
        assertEquals("该档已下载时不应发网络请求", 0, h.onlineCalls)
    }

    @Test
    fun `断言2 已下载 320 请求 999 走网络解析而非直接播本地 320`() = runTest {
        val h = Harness(
            localByQuality = mapOf(320 to local320),   // 只有 320 档
            online = { ResolveResult(online999, 999) } // 无损可用
        )
        val d = NetworkPlaybackResolver.decide(
            effectiveQuality = 999,
            localUriFor = h.localUriFor,
            localUriAny = h.localUriAny,
            resolveOnline = h.resolveOnline
        )
        assertEquals("切到无损必须走在线无损流", online999, d!!.url)
        assertFalse("不能仍播本地 320（这是改造前的缺陷）", d.fromLocal)
        assertEquals(999, d.actualQuality)
        assertEquals(1, h.onlineCalls)
    }

    @Test
    fun `断言3 请求 999 降级到 320 且 320 已下载 返回本地`() = runTest {
        val h = Harness(
            localByQuality = mapOf(320 to local320),
            online = { ResolveResult(online320, 320) }  // 降级到 320
        )
        val d = NetworkPlaybackResolver.decide(
            effectiveQuality = 999,
            localUriFor = h.localUriFor,
            localUriAny = h.localUriAny,
            resolveOnline = h.resolveOnline
        )
        assertEquals("降级后的档已下载 → 优先本地（省流量，内容一致）", local320, d!!.url)
        assertTrue(d.fromLocal)
        assertEquals(320, d.actualQuality)
    }

    @Test
    fun `断言3b 请求 999 降级到 192 但 192 未下载 仍用在线降级流`() = runTest {
        val h = Harness(
            localByQuality = mapOf(320 to local320),     // 320 有，192 没有
            online = { ResolveResult("https://cdn/1_192.mp3", 192) }
        )
        val d = NetworkPlaybackResolver.decide(
            effectiveQuality = 999,
            localUriFor = h.localUriFor,
            localUriAny = h.localUriAny,
            resolveOnline = h.resolveOnline
        )
        assertEquals("降级档未下载时用在线降级流", "https://cdn/1_192.mp3", d!!.url)
        assertFalse(d.fromLocal)
        assertEquals(192, d.actualQuality)
    }

    @Test
    fun `断言4 请求 999 网络解析彻底失败 返回本地 320 兜底`() = runTest {
        val h = Harness(
            localByQuality = emptyMap(),
            anyLocal = local320,
            online = { ResolveResult.failure(999) }
        )
        val d = NetworkPlaybackResolver.decide(
            effectiveQuality = 999,
            localUriFor = h.localUriFor,
            localUriAny = h.localUriAny,
            resolveOnline = h.resolveOnline
        )
        assertEquals("解析失败 → 任意已下载档兜底（保证能播就行）", local320, d!!.url)
        assertTrue(d.fromLocal)
    }

    @Test
    fun `断言4b 解析失败且无任何本地文件 返回 null`() = runTest {
        val h = Harness(
            localByQuality = emptyMap(),
            anyLocal = null,
            online = { ResolveResult.failure(999) }
        )
        val d = NetworkPlaybackResolver.decide(
            effectiveQuality = 999,
            localUriFor = h.localUriFor,
            localUriAny = h.localUriAny,
            resolveOnline = h.resolveOnline
        )
        assertNull("无源可播时必须返回 null（上层提示失败）", d)
    }

    @Test
    fun `断言5 无任何本地文件时全部走网络解析`() = runTest {
        val h = Harness(
            localByQuality = emptyMap(),
            anyLocal = null,
            online = { q -> ResolveResult("https://cdn/1_$q.mp3", q) }
        )
        val d = NetworkPlaybackResolver.decide(
            effectiveQuality = 320,
            localUriFor = h.localUriFor,
            localUriAny = h.localUriAny,
            resolveOnline = h.resolveOnline
        )
        assertEquals(online320, d!!.url)
        assertFalse(d.fromLocal)
        assertEquals(1, h.onlineCalls)
    }

    @Test
    fun `断言6 AUTO 档 存量行存在 返回本地 与改造前行为一致`() = runTest {
        val legacy = "file:///music/1.mp3"
        val h = Harness(
            localByQuality = mapOf(QualityTiers.AUTO to legacy),
            online = { ResolveResult(online320, 320) }
        )
        val d = NetworkPlaybackResolver.decide(
            effectiveQuality = QualityTiers.AUTO,
            localUriFor = h.localUriFor,
            localUriAny = h.localUriAny,
            resolveOnline = h.resolveOnline
        )
        assertEquals("AUTO 档命中存量行 → 播本地", legacy, d!!.url)
        assertTrue(d.fromLocal)
        assertEquals(0, h.onlineCalls)
    }

    @Test
    fun `AUTO 档降级判定不触发（AUTO 不是降级）`() = runTest {
        val h = Harness(
            localByQuality = emptyMap(),
            anyLocal = null,
            online = { ResolveResult("https://cdn/x.mp3", 320) }
        )
        val d = NetworkPlaybackResolver.decide(
            effectiveQuality = QualityTiers.AUTO,
            localUriFor = h.localUriFor,
            localUriAny = h.localUriAny,
            resolveOnline = h.resolveOnline
        )
        // AUTO 请求、actualQuality=320 → isDowngradedFrom(AUTO) 恒 false
        assertEquals("https://cdn/x.mp3", d!!.url)
        assertFalse(d.fromLocal)
        assertEquals("AUTO 档不应被判定为降级", 320, d.actualQuality)
    }

    @Test
    fun `同曲多档已下载时 按请求档精确命中对应本地文件`() = runTest {
        val h = Harness(
            localByQuality = mapOf(320 to local320, 999 to local999),
            online = { q -> ResolveResult("https://cdn/1_$q", q) }
        )
        val d999 = NetworkPlaybackResolver.decide(
            effectiveQuality = 999,
            localUriFor = h.localUriFor,
            localUriAny = h.localUriAny,
            resolveOnline = h.resolveOnline
        )
        assertEquals("请求无损必须命中 flac 而非 mp3", local999, d999!!.url)

        val d320 = NetworkPlaybackResolver.decide(
            effectiveQuality = 320,
            localUriFor = h.localUriFor,
            localUriAny = h.localUriAny,
            resolveOnline = h.resolveOnline
        )
        assertEquals("请求 320 必须命中 mp3", local320, d320!!.url)
        assertEquals("两档都在本地 → 全程无网络请求", 0, h.onlineCalls)
    }

    @Test
    fun `降级到已下载档时 不额外发第二次解析请求`() = runTest {
        val h = Harness(
            localByQuality = mapOf(320 to local320),
            online = { ResolveResult(online320, 320) }
        )
        NetworkPlaybackResolver.decide(
            effectiveQuality = 999,
            localUriFor = h.localUriFor,
            localUriAny = h.localUriAny,
            resolveOnline = h.resolveOnline
        )
        assertEquals("降级回退本地不应再解析一次", 1, h.onlineCalls)
    }
}

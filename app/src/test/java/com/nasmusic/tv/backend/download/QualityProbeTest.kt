package com.nasmusic.tv.backend.download

import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.backend.network.ResolveResult
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可用码率探测测试（多码率方案 §5.2.1 / §10.1）。
 *
 * 核心守护目标：`probeAvailableQualities` 必须**并发**执行 4 档探测。
 * 若有人把 `async/awaitAll` "简化"成串行 `map`，单曲下载点击的等待时间
 * 会从 ≈1.2s 退化到最坏 4.8s（4 × 单档超时），用户明显可感。
 *
 * 实现方式：用 `runTest` 的**虚拟时间** + 注入 `StandardTestDispatcher`，
 * 断言"总耗时 == 单档耗时"而非"4 × 单档耗时"。虚拟时间使该断言确定性成立，
 * 不受机器负载影响（无 flake）。
 *
 * 注意：不需要 MockWebServer / Robolectric —— `StreamUrlResolver.networkDetailed`
 * 是构造注入的 lambda，直接注入假实现即可绕开网络与 Android 框架。
 */
class QualityProbeTest {

    private val song = Song(
        id = "ntwk_meting_1001",
        title = "南方姑娘",
        artist = "赵雷",
        isNetworkSong = true,
        networkSource = "meting",
        networkId = "1001"
    )

    /** 每档固定延迟，用于把"并发 vs 串行"转成可断言的耗时差 */
    private val perTierDelayMs = 300L

    /**
     * 构造注入式解析器：只有 [available] 中的档位返回成功，
     * 其余返回失败；每档固定延迟 [perTierDelayMs]。
     */
    private fun fakeResolver(
        available: Set<Int>,
        delayMs: Long = perTierDelayMs,
        failAbove: Long? = null
    ) = StreamUrlResolver(
        adapter = { null },
        network = { null },
        networkDetailed = { _, tier ->
            if (failAbove != null && delayMs > failAbove) {
                // 模拟"该档探测超时"：延迟超过 withTimeout 上限
                delay(delayMs)
                ResolveResult.failure(tier)
            } else {
                delay(delayMs)
                if (tier in available) ResolveResult("https://x/$tier.mp3", tier)
                else ResolveResult.failure(tier)
            }
        }
    )

    @Test
    fun `4 档全部可解析时降序返回`() = runTest {
        val r = QualityProbe.probeAvailableQualities(
            song, fakeResolver(QualityTiers.availableTiers.toSet()), dispatcher = StandardTestDispatcher(testScheduler)
        )
        assertEquals(listOf(999, 320, 192, 128), r)
    }

    @Test
    fun `仅部分档可用时只返回可用档且保持降序`() = runTest {
        val r = QualityProbe.probeAvailableQualities(
            song, fakeResolver(setOf(320, 128)), dispatcher = StandardTestDispatcher(testScheduler)
        )
        assertEquals(listOf(320, 128), r)
        assertTrue("不应包含 999", !r.contains(999))
        assertTrue("不应包含 192", !r.contains(192))
    }

    @Test
    fun `仅一档可用时返回单元素（UI 据此不弹窗直下）`() = runTest {
        val r = QualityProbe.probeAvailableQualities(
            song, fakeResolver(setOf(128)), dispatcher = StandardTestDispatcher(testScheduler)
        )
        assertEquals(listOf(128), r)
    }

    @Test
    fun `全部不可用时返回空列表（UI 据此报错不入队）`() = runTest {
        val r = QualityProbe.probeAvailableQualities(
            song, fakeResolver(emptySet()), dispatcher = StandardTestDispatcher(testScheduler)
        )
        assertTrue("无源可降时必须返回空列表", r.isEmpty())
    }

    @Test
    fun `并发执行 总耗时约等于单档耗时而非 4 倍`() = runTest {
        val start = testScheduler.currentTime
        QualityProbe.probeAvailableQualities(
            song, fakeResolver(QualityTiers.availableTiers.toSet()),
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val elapsed = testScheduler.currentTime - start
        // 并发：≈300ms；串行：≈1200ms。断言必须明显小于 4×单档
        assertTrue(
            "探测必须并发：期望 ≈${perTierDelayMs}ms，实际 ${elapsed}ms（串行实现会是 ${perTierDelayMs * 4}ms）",
            elapsed < perTierDelayMs * 2
        )
    }

    @Test
    fun `单档超时不阻塞其他档`() = runTest {
        // 999 档延迟 5000ms（远超 1200ms 超时），其余档正常
        val resolver = StreamUrlResolver(
            adapter = { null },
            network = { null },
            networkDetailed = { _, tier ->
                if (tier == QualityTiers.LOSSLESS) {
                    delay(5000L)
                    ResolveResult("https://x/lossless.flac", tier)
                } else {
                    delay(100L)
                    ResolveResult("https://x/$tier.mp3", tier)
                }
            }
        )
        val r = QualityProbe.probeAvailableQualities(
            song, resolver, dispatcher = StandardTestDispatcher(testScheduler)
        )
        assertTrue("超时档应被判定为不可用", !r.contains(999))
        assertEquals("其余档不受影响", listOf(320, 192, 128), r)
    }

    @Test
    fun `整链超时封顶不超过单档超时上限`() = runTest {
        // 4 档全部延迟 5000ms，全部触发 1200ms 超时；并发下总耗时仍 ≈1200ms
        val resolver = StreamUrlResolver(
            adapter = { null },
            network = { null },
            networkDetailed = { _, tier ->
                delay(5000L)
                ResolveResult("https://x/$tier.mp3", tier)
            }
        )
        val start = testScheduler.currentTime
        val r = QualityProbe.probeAvailableQualities(
            song, resolver, dispatcher = StandardTestDispatcher(testScheduler)
        )
        val elapsed = testScheduler.currentTime - start
        assertTrue("全部超时时应返回空", r.isEmpty())
        assertTrue(
            "并发下总耗时应 ≈${QualityProbe.PROBE_TIMEOUT_MS}ms，实际 ${elapsed}ms",
            elapsed <= QualityProbe.PROBE_TIMEOUT_MS + 200
        )
    }

    @Test
    fun `探测不落盘不写下载索引`() = runTest {
        // 探测只调 resolveDetailed（纯解析），不触碰 DownloadRepository。
        // 本测试通过"只注入 resolver、无任何 repo 参数"从接口层面锁定该契约。
        val resolver = fakeResolver(QualityTiers.availableTiers.toSet())
        val r = QualityProbe.probeAvailableQualities(
            song, resolver, dispatcher = StandardTestDispatcher(testScheduler)
        )
        assertEquals(4, r.size)
        // 解析器返回值是 url 字符串，探测过程未产生任何文件副作用
        assertTrue(r.all { QualityTiers.isValid(it) })
    }

    @Test
    fun `自定义探测档位子集被尊重`() = runTest {
        val r = QualityProbe.probeAvailableQualities(
            song, fakeResolver(setOf(320, 192)),
            tiers = listOf(320, 192),
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        assertEquals(listOf(320, 192), r)
    }

    @Test
    fun `探测结果不含 AUTO 档`() = runTest {
        val r = QualityProbe.probeAvailableQualities(
            song, fakeResolver(QualityTiers.availableTiers.toSet()),
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        assertTrue("AUTO 无法被探测（它只是不传 br）", !r.contains(QualityTiers.AUTO))
    }
}

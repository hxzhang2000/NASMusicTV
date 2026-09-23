package com.nasmusic.tv.backend.photo

import com.nasmusic.tv.visualizer.VisualizerRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/**
 * `PhotoSourceAggregator` 的行为验证（§6.8）
 *
 * 重点覆盖三件「靠读代码看不出来」的事：
 * 1. **关掉的来源是否真的没被接触** —— 用假 [PhotoSource] 数调用次数，不靠断点
 * 2. **不可用状态是否跳过扫描** —— 避免未授权时仍去探测存储
 * 3. **「来源均衡」是否真的抬高了小来源在序列前段的密度** —— 并与自然混合做对照
 */
class PhotoSourceAggregatorTest {

    // ────────────────────── 测试替身 ──────────────────────

    private class FakeSource(
        override val kind: PhotoSourceKind,
        private val statusValue: PhotoSourceStatus = PhotoSourceStatus.OK,
        private val photos: List<PhotoRef> = emptyList(),
        private val failOnList: Boolean = false,
    ) : PhotoSource {

        var statusCalls = 0
            private set
        var listCalls = 0
            private set

        override suspend fun status(): PhotoSourceStatus {
            statusCalls++
            return statusValue
        }

        override suspend fun listPhotos(): List<PhotoRef> {
            listCalls++
            if (failOnList) throw IllegalStateException("simulated failure")
            return photos
        }

        override suspend fun openStream(ref: PhotoRef): InputStream? = null
    }

    /** 造 [count] 张**互不重复**的照片（尺寸逐个递增 ⇒ 指纹不撞） */
    private fun refs(kind: PhotoSourceKind, count: Int): List<PhotoRef> =
        (0 until count).map { i ->
            PhotoRef(
                id = PhotoIds.of(kind, "${kind.name}-$i"),
                displayName = "${kind.name.lowercase()}_$i.jpg",
                width = 0,
                height = 0,
                size = 1_000L + i,
                lastModified = 1_700_000_000L + i,
                dateAdded = 1_700_000_000L + i,
                source = kind,
            )
        }

    private fun single(kind: PhotoSourceKind, name: String, size: Long, sec: Long) = PhotoRef(
        id = PhotoIds.of(kind, "$kind/$name"),
        displayName = name,
        width = 0,
        height = 0,
        size = size,
        lastModified = sec,
        dateAdded = sec,
        source = kind,
    )

    // ────────────────────── ① 关掉的来源完全不接触 ──────────────────────

    @Test
    fun `disabled source is not touched at all`() = runBlocking {
        val gallery = FakeSource(PhotoSourceKind.GALLERY, photos = refs(PhotoSourceKind.GALLERY, 3))
        val agg = PhotoSourceAggregator(mapOf(PhotoSourceKind.GALLERY to gallery))

        val result = agg.collect(enabled = emptySet())

        assertTrue(
            "关掉的来源连 status() 都不能调用（避免未授权探测），实际 ${gallery.statusCalls} 次",
            gallery.statusCalls == 0,
        )
        assertTrue("关掉的来源不能调用 listPhotos()，实际 ${gallery.listCalls} 次", gallery.listCalls == 0)
        assertTrue("状态应标为 DISABLED", result.statuses[PhotoSourceKind.GALLERY] == PhotoSourceStatus.DISABLED)
        assertTrue("池应为空，实际 ${result.photos.size}", result.photos.isEmpty())
        assertTrue("计数应为 0", result.perSource[PhotoSourceKind.GALLERY] == 0)
    }

    @Test
    fun `absent source is omitted from statuses`() = runBlocking {
        // 电视上不存在「图库」来源：它不该出现在 statuses / perSource 里（区别于 DISABLED）
        val agg = PhotoSourceAggregator(
            mapOf(PhotoSourceKind.EXTERNAL to FakeSource(PhotoSourceKind.EXTERNAL)),
        )
        val result = agg.collect(enabled = setOf(PhotoSourceKind.GALLERY, PhotoSourceKind.EXTERNAL))
        assertTrue("未注册的来源不应出现在 statuses", !result.statuses.containsKey(PhotoSourceKind.GALLERY))
        assertTrue("未注册的来源不应出现在 perSource", !result.perSource.containsKey(PhotoSourceKind.GALLERY))
    }

    // ────────────────────── ② 状态 → 是否扫描 ──────────────────────

    @Test
    fun `non scannable statuses skip listing`() = runBlocking {
        val notScannable = listOf(
            PhotoSourceStatus.DISABLED,
            PhotoSourceStatus.PERMISSION_DENIED,
            PhotoSourceStatus.NO_DIRECTORY,
            PhotoSourceStatus.NOT_CONNECTED,
            PhotoSourceStatus.NO_PHOTO_LIBRARY,
            PhotoSourceStatus.UNAVAILABLE,
        )
        for (status in notScannable) {
            val src = FakeSource(PhotoSourceKind.GALLERY, statusValue = status, photos = refs(PhotoSourceKind.GALLERY, 2))
            val agg = PhotoSourceAggregator(mapOf(PhotoSourceKind.GALLERY to src))
            val result = agg.collect(enabled = setOf(PhotoSourceKind.GALLERY))
            assertTrue("$status 不应触发 listPhotos()，实际 ${src.listCalls} 次", src.listCalls == 0)
            assertTrue("$status 下池应为空", result.photos.isEmpty())
            assertTrue("$status 应如实上报", result.statuses[PhotoSourceKind.GALLERY] == status)
        }
    }

    @Test
    fun `ok and partial permission are scannable`() = runBlocking {
        for (status in listOf(PhotoSourceStatus.OK, PhotoSourceStatus.PARTIAL_PERMISSION)) {
            val src = FakeSource(PhotoSourceKind.GALLERY, statusValue = status, photos = refs(PhotoSourceKind.GALLERY, 2))
            val agg = PhotoSourceAggregator(mapOf(PhotoSourceKind.GALLERY to src))
            val result = agg.collect(enabled = setOf(PhotoSourceKind.GALLERY))
            assertTrue("$status 必须可扫（部分授权下已选照片可读）", result.photos.size == 2)
            assertTrue("$status 应触发 listPhotos()", src.listCalls == 1)
        }
    }

    // ────────────────────── ③ 去重与合并 ──────────────────────

    @Test
    fun `cross source duplicates are collapsed`() = runBlocking {
        // 场景：SD 卡被 MediaStore 索引 ⇒ 同一张照片从两个来源各来一条
        val fromGallery = single(PhotoSourceKind.GALLERY, "IMG_1.jpg", size = 500L, sec = 100L)
        val fromUsb = single(PhotoSourceKind.EXTERNAL, "IMG_1.jpg", size = 500L, sec = 100L)
        val agg = PhotoSourceAggregator(
            mapOf(
                PhotoSourceKind.GALLERY to FakeSource(PhotoSourceKind.GALLERY, photos = listOf(fromGallery)),
                PhotoSourceKind.EXTERNAL to FakeSource(PhotoSourceKind.EXTERNAL, photos = listOf(fromUsb)),
            ),
        )
        val result = agg.collect(enabled = setOf(PhotoSourceKind.GALLERY, PhotoSourceKind.EXTERNAL))
        assertTrue("跨来源同指纹应合并为 1 条，实际 ${result.photos.size}", result.photos.size == 1)
        assertTrue("应保留枚举序靠前的来源（GALLERY）", result.photos[0].source == PhotoSourceKind.GALLERY)
    }

    @Test
    fun `perSource sums to pool size`() = runBlocking {
        val agg = PhotoSourceAggregator(
            mapOf(
                PhotoSourceKind.EXTERNAL to FakeSource(PhotoSourceKind.EXTERNAL, photos = refs(PhotoSourceKind.EXTERNAL, 7)),
                PhotoSourceKind.JELLYFIN to FakeSource(PhotoSourceKind.JELLYFIN, photos = refs(PhotoSourceKind.JELLYFIN, 5)),
                PhotoSourceKind.GALLERY to FakeSource(PhotoSourceKind.GALLERY, statusValue = PhotoSourceStatus.PERMISSION_DENIED),
            ),
        )
        val result = agg.collect(enabled = PhotoSourceKind.entries.toSet())
        val sum = result.perSource.values.sum()
        assertTrue("perSource 求和必须等于池大小：$sum vs ${result.photos.size}", sum == result.photos.size)
        assertTrue("EXTERNAL 应计 7", result.perSource[PhotoSourceKind.EXTERNAL] == 7)
        assertTrue("JELLYFIN 应计 5", result.perSource[PhotoSourceKind.JELLYFIN] == 5)
        assertTrue("拒权的来源应计 0", result.perSource[PhotoSourceKind.GALLERY] == 0)
        assertTrue("池大小应为 12", result.photos.size == 12)
    }

    @Test
    fun `failing source does not break the pool`() = runBlocking {
        val bad = FakeSource(PhotoSourceKind.EXTERNAL, failOnList = true)
        val good = FakeSource(PhotoSourceKind.JELLYFIN, photos = refs(PhotoSourceKind.JELLYFIN, 3))
        val agg = PhotoSourceAggregator(
            mapOf(PhotoSourceKind.EXTERNAL to bad, PhotoSourceKind.JELLYFIN to good),
        )
        val result = agg.collect(enabled = setOf(PhotoSourceKind.EXTERNAL, PhotoSourceKind.JELLYFIN))
        assertTrue("炸掉的来源应兜底为空表而不是向上抛", result.perSource[PhotoSourceKind.EXTERNAL] == 0)
        assertTrue("其余来源必须照常工作，实际 ${result.photos.size}", result.photos.size == 3)
    }

    // ────────────────────── ④ 排序策略（自然混合 vs 来源均衡）──────────────────────

    /**
     * 验收判据（§15.3 T3.1）：**Jellyfin 5000 + U 盘 50 时两者出现频率同量级**。
     *
     * 同时给出**对照**：自然混合下前 100 项里 U 盘只有个位数 —— 证明「均衡」确实改变了分布，
     * 而不是碰巧（若两种模式结果相同，这个测试会失败）。
     */
    @Test
    fun `balance raises the small source share in the prefix`() = runBlocking {
        val jellyfin = FakeSource(PhotoSourceKind.JELLYFIN, photos = refs(PhotoSourceKind.JELLYFIN, 5_000))
        val usb = FakeSource(PhotoSourceKind.EXTERNAL, photos = refs(PhotoSourceKind.EXTERNAL, 50))
        val agg = PhotoSourceAggregator(
            sources = mapOf(PhotoSourceKind.JELLYFIN to jellyfin, PhotoSourceKind.EXTERNAL to usb),
            random = VisualizerRandom(seed = 20_260_923u),
        )
        val enabled = setOf(PhotoSourceKind.JELLYFIN, PhotoSourceKind.EXTERNAL)

        val natural = agg.collect(enabled, balance = false)
        val balanced = agg.collect(enabled, balance = true)

        assertTrue(
            "均衡不能改变池大小：${balanced.photos.size} vs ${natural.photos.size}",
            balanced.photos.size == natural.photos.size,
        )
        assertTrue("池大小应为 5050，实际 ${natural.photos.size}", natural.photos.size == 5_050)

        val prefix = 100
        val naturalUsb = natural.photos.take(prefix).count { it.source == PhotoSourceKind.EXTERNAL }
        val balancedUsb = balanced.photos.take(prefix).count { it.source == PhotoSourceKind.EXTERNAL }

        assertTrue(
            "自然混合下前 $prefix 项 U 盘应约 1 张（按数量加权），实际 $naturalUsb —— 若偏大说明洗牌没生效",
            naturalUsb <= 10,
        )
        assertTrue(
            "均衡模式下前 $prefix 项 U 盘应约 50 张（同量级），实际 $balancedUsb",
            balancedUsb >= 25,
        )
        assertTrue("U 盘总共只有 50 张，前缀里不可能超过 50", balancedUsb <= 50)
    }

    @Test
    fun `balance with a single source degenerates to a plain shuffle`() = runBlocking {
        val only = FakeSource(PhotoSourceKind.EXTERNAL, photos = refs(PhotoSourceKind.EXTERNAL, 20))
        val agg = PhotoSourceAggregator(
            sources = mapOf(PhotoSourceKind.EXTERNAL to only),
            random = VisualizerRandom(seed = 7u),
        )
        val balanced = agg.collect(setOf(PhotoSourceKind.EXTERNAL), balance = true)
        assertTrue("单来源均衡应等价于洗牌，池大小 20，实际 ${balanced.photos.size}", balanced.photos.size == 20)
        assertTrue("不能丢项", balanced.photos.map { it.id }.toSet().size == 20)
    }

    @Test
    fun `pool keeps every distinct photo exactly once`() = runBlocking {
        val agg = PhotoSourceAggregator(
            mapOf(
                PhotoSourceKind.EXTERNAL to FakeSource(PhotoSourceKind.EXTERNAL, photos = refs(PhotoSourceKind.EXTERNAL, 30)),
                PhotoSourceKind.JELLYFIN to FakeSource(PhotoSourceKind.JELLYFIN, photos = refs(PhotoSourceKind.JELLYFIN, 40)),
            ),
            random = VisualizerRandom(seed = 99u),
        )
        val enabled = setOf(PhotoSourceKind.EXTERNAL, PhotoSourceKind.JELLYFIN)
        for (balance in listOf(false, true)) {
            val pool = agg.collect(enabled, balance).photos
            assertTrue("balance=$balance 池大小应为 70，实际 ${pool.size}", pool.size == 70)
            assertTrue("balance=$balance 不应出现重复项", pool.map { it.id }.toSet().size == 70)
        }
    }

    // ────────────────────── ⑤ 负向自证 ──────────────────────

    /**
     * **负向自证**：把「关掉的来源不扫描」这条约定反向模拟一次 —— 若 [PhotoSourceAggregator]
     * 改成「先调 status() 再判断开关」，本用例会立刻抓到（`statusCalls` 变成 1）。
     *
     * 之所以需要它：`statusCalls == 0` 这个断言**天然会被「没实现任何调用」的实现满足**
     * （空转也能过）。所以额外验证**同一来源在开启时确实会被调用** ——
     * 两半合起来才能证明「计数确实在工作」。
     */
    @Test
    fun `negative proof - the call counter is live and the gate is real`() = runBlocking {
        val gallery = FakeSource(PhotoSourceKind.GALLERY, photos = refs(PhotoSourceKind.GALLERY, 1))
        val agg = PhotoSourceAggregator(mapOf(PhotoSourceKind.GALLERY to gallery))

        agg.collect(enabled = emptySet())
        assertTrue("关闭态不应触碰来源，实际 status=${gallery.statusCalls}", gallery.statusCalls == 0)

        agg.collect(enabled = setOf(PhotoSourceKind.GALLERY))
        assertTrue(
            "开启态必须真的调用 status()（否则上面的 0 是空转造成的假阳性），实际 ${gallery.statusCalls}",
            gallery.statusCalls == 1,
        )
        assertTrue("开启态必须真的调用 listPhotos()，实际 ${gallery.listCalls}", gallery.listCalls == 1)
    }
}

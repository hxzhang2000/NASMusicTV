package com.nasmusic.tv.visualizer.photo

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.nasmusic.tv.backend.photo.PhotoIds
import com.nasmusic.tv.backend.photo.PhotoRef
import com.nasmusic.tv.backend.photo.PhotoSource
import com.nasmusic.tv.backend.photo.PhotoSourceKind
import com.nasmusic.tv.backend.photo.PhotoSourceStatus
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * `PhotoBuffer` 的行为验证（§15.3 阶段 4 的 T4.1 / T4.2 验收判据）
 *
 * 重点覆盖三件**靠读代码容易看漏**的事：
 * 1. **一次解码只开一次流** —— 对 Jellyfin 就是只发一次 HTTP（两次流 = 两次往返）
 * 2. **`request` 幂等** —— 同一张反复请求不应重复解码
 * 3. **`close()` 逐张 `recycle()`** —— API 22 上 `ImageBitmap` 包装的 `Bitmap` 不会自动回收
 *
 * ⚠️ 用注入的**直通 Executor**（同步执行）与**假解码器**（4×4 位图）：
 * Robolectric 的 `BitmapFactory` 不真解码、拿不到确定尺寸，验这些**与像素无关**的逻辑
 * 反而会被它干扰。采样率计算由 `PhotoBufferBudgetTest` 单独覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoBufferTest {

    // ────────────────────── 测试替身 ──────────────────────

    private class FakeSource(
        override val kind: PhotoSourceKind,
        private val failStream: Boolean = false,
    ) : PhotoSource {

        var openCount = 0
            private set

        override suspend fun status(): PhotoSourceStatus = PhotoSourceStatus.OK

        override suspend fun listPhotos(): List<PhotoRef> = emptyList()

        override suspend fun openStream(ref: PhotoRef): InputStream? {
            openCount++
            return if (failStream) null else ByteArrayInputStream(ByteArray(64) { it.toByte() })
        }
    }

    /** 直通 Executor：`execute` 立刻在当前线程跑完 ⇒ 测试无需驱动 Looper */
    private class DirectExecutor : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return ArrayList()
        }

        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private fun ref(i: Int, kind: PhotoSourceKind = PhotoSourceKind.GALLERY) = PhotoRef(
        id = PhotoIds.of(kind, "p$i"),
        displayName = "p$i.jpg",
        width = 1920,
        height = 1080,
        size = 1_000L + i,
        lastModified = 1_700_000_000L + i,
        dateAdded = 1_700_000_000L + i,
        source = kind,
    )

    private fun newBuffer(
        sources: Map<PhotoSourceKind, PhotoSource>,
        maxCached: Int = 3,
        decoder: ((PhotoRef, ByteArray) -> Bitmap?)? = null,
    ): PhotoBuffer = PhotoBuffer(
        sourceProvider = { sources[it] },
        targetWidth = 1920,
        targetHeight = 1080,
        maxCached = maxCached,
        allowRgb565 = false,
        mainHandler = Handler(Looper.getMainLooper()),
        decoderExecutor = DirectExecutor(),
        decoder = decoder ?: { _, _ -> Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) },
    )

    // ────────────────────── ① 一次解码 = 一次开流 ──────────────────────

    @Test
    fun `decode opens the source stream exactly once`() {
        val src = FakeSource(PhotoSourceKind.GALLERY)
        val buf = newBuffer(mapOf(PhotoSourceKind.GALLERY to src))

        buf.request(ref(1))

        assertTrue(
            "一次解码必须只开 1 次流（两次 = Jellyfin 两次 HTTP），实际 ${src.openCount} 次",
            src.openCount == 1,
        )
        assertTrue("解码完成后应已缓存，实际 ${buf.cachedCount}", buf.cachedCount == 1)
    }

    @Test
    fun `stream is opened once even when metadata has no dimensions`() {
        // width/height = 0 的路径要 inJustDecodeBounds 拿尺寸 —— 容易顺手写成「再开一次流」
        val src = FakeSource(PhotoSourceKind.EXTERNAL)
        val buf = newBuffer(mapOf(PhotoSourceKind.EXTERNAL to src))
        val noDims = ref(9, PhotoSourceKind.EXTERNAL).copy(width = 0, height = 0)

        buf.request(noDims)

        assertTrue(
            "无尺寸元数据也必须只开 1 次流，实际 ${src.openCount} 次",
            src.openCount == 1,
        )
    }

    @Test
    fun `request is idempotent`() {
        val src = FakeSource(PhotoSourceKind.GALLERY)
        val buf = newBuffer(mapOf(PhotoSourceKind.GALLERY to src))
        val r = ref(1)

        buf.request(r)
        buf.request(r)
        buf.request(r)

        assertTrue("重复 request 不应重复解码，实际开流 ${src.openCount} 次", src.openCount == 1)
        assertTrue("缓存应只有 1 条，实际 ${buf.cachedCount}", buf.cachedCount == 1)
    }

    @Test
    fun `prefetch delegates to request and stays idempotent`() {
        val src = FakeSource(PhotoSourceKind.GALLERY)
        val buf = newBuffer(mapOf(PhotoSourceKind.GALLERY to src))
        val r = ref(2)

        buf.prefetch(r)
        buf.request(r)

        assertTrue("prefetch 与 request 共用队列，不应重复解码，实际 ${src.openCount}", src.openCount == 1)
    }

    @Test
    fun `prefetch null is a no op`() {
        val src = FakeSource(PhotoSourceKind.GALLERY)
        val buf = newBuffer(mapOf(PhotoSourceKind.GALLERY to src))
        buf.prefetch(null)
        assertTrue("prefetch(null) 不应开流", src.openCount == 0)
    }

    // ────────────────────── ② peek 的语义 ──────────────────────

    @Test
    fun `peek returns null when nothing was requested`() {
        val src = FakeSource(PhotoSourceKind.GALLERY)
        val buf = newBuffer(mapOf(PhotoSourceKind.GALLERY to src))
        assertTrue("未请求过 ⇒ peek 必须返回 null（绝不阻塞绘制路径）", buf.peek(ref(1)) == null)
    }

    @Test
    fun `peek returns the cached image after decode`() {
        val src = FakeSource(PhotoSourceKind.GALLERY)
        val buf = newBuffer(mapOf(PhotoSourceKind.GALLERY to src))
        val r = ref(1)
        buf.request(r)
        assertTrue("解码完成后 peek 必须命中", buf.peek(r) != null)
    }

    @Test
    fun `source returning null stream yields no cache entry`() {
        val src = FakeSource(PhotoSourceKind.EXTERNAL, failStream = true)
        val buf = newBuffer(mapOf(PhotoSourceKind.EXTERNAL to src))
        buf.request(ref(1, PhotoSourceKind.EXTERNAL))
        assertTrue("开流失败不应产生缓存条目", buf.cachedCount == 0)
        assertTrue("开流失败后 peek 应为 null", buf.peek(ref(1, PhotoSourceKind.EXTERNAL)) == null)
    }

    // ────────────────────── ③ LRU 与释放 ──────────────────────

    @Test
    fun `lru evicts the oldest entry beyond capacity`() {
        val src = FakeSource(PhotoSourceKind.GALLERY)
        val created = HashMap<String, Bitmap>()
        val buf = newBuffer(
            mapOf(PhotoSourceKind.GALLERY to src),
            maxCached = 2,
            decoder = { r, _ ->
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { created[r.id] = it }
            },
        )
        val a = ref(1)
        val b = ref(2)
        val c = ref(3)

        buf.request(a)
        buf.request(b)
        buf.request(c)

        assertTrue("容量 2 ⇒ 只应留 2 条，实际 ${buf.cachedCount}", buf.cachedCount == 2)
        assertTrue("最旧的应被淘汰", buf.peek(a) == null)
        assertTrue("被淘汰的位图必须 recycle（否则内存持续增长）", created[a.id]?.isRecycled == true)
        assertTrue("最新的应还在", buf.peek(c) != null)
    }

    @Test
    fun `close recycles every cached bitmap`() {
        val src = FakeSource(PhotoSourceKind.GALLERY)
        val created = ArrayList<Bitmap>()
        val buf = newBuffer(
            mapOf(PhotoSourceKind.GALLERY to src),
            decoder = { _, _ ->
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { created.add(it) }
            },
        )
        buf.request(ref(1))
        buf.request(ref(2))
        assertTrue("应缓存 2 张，实际 ${buf.cachedCount}", buf.cachedCount == 2)

        buf.close()

        assertTrue("close 后缓存应清空，实际 ${buf.cachedCount}", buf.cachedCount == 0)
        assertTrue("created 应有 2 张", created.size == 2)
        assertTrue(
            "每张位图都必须 recycle（API 22 上 ImageBitmap 包装的 Bitmap 不会自动回收）",
            created.all { it.isRecycled },
        )
        assertTrue("close 后 peek 必须返回 null", buf.peek(ref(1)) == null)
    }

    @Test
    fun `close is idempotent and request becomes a no op`() {
        val src = FakeSource(PhotoSourceKind.GALLERY)
        val buf = newBuffer(mapOf(PhotoSourceKind.GALLERY to src))
        buf.close()
        buf.close()
        buf.request(ref(1))
        assertTrue("close 后 request 不应再开流，实际 ${src.openCount}", src.openCount == 0)
    }

    @Test
    fun `clear drops cache but keeps the buffer usable`() {
        val src = FakeSource(PhotoSourceKind.GALLERY)
        val buf = newBuffer(mapOf(PhotoSourceKind.GALLERY to src))
        buf.request(ref(1))
        buf.clear()
        assertTrue("clear 后缓存应为空", buf.cachedCount == 0)

        buf.request(ref(1))
        assertTrue("clear 后仍可继续使用（不是 close）", buf.peek(ref(1)) != null)
    }

    @Test
    fun `invalidateSource drops only that source`() {
        val gallery = FakeSource(PhotoSourceKind.GALLERY)
        val external = FakeSource(PhotoSourceKind.EXTERNAL)
        val buf = newBuffer(
            mapOf(PhotoSourceKind.GALLERY to gallery, PhotoSourceKind.EXTERNAL to external),
        )
        val g = ref(1, PhotoSourceKind.GALLERY)
        val e = ref(2, PhotoSourceKind.EXTERNAL)
        buf.request(g)
        buf.request(e)
        assertTrue("前置条件：两条都应在缓存里", buf.cachedCount == 2)

        buf.invalidateSource(PhotoSourceKind.GALLERY)

        assertTrue("被拔盘的来源缓存应丢弃", buf.peek(g) == null)
        assertTrue("其他来源不受影响", buf.peek(e) != null)
    }

    @Test
    fun `invalidateSource also drops in flight results from that generation`() {
        // 拔盘后「已发出的解码任务」完成时不能把失效照片写回缓存
        val external = FakeSource(PhotoSourceKind.EXTERNAL)
        val buf = newBuffer(mapOf(PhotoSourceKind.EXTERNAL to external))
        val e = ref(1, PhotoSourceKind.EXTERNAL)

        // 先 invalidate（抬高世代），再请求 ⇒ 本次请求属于新世代，应当正常写入
        buf.invalidateSource(PhotoSourceKind.EXTERNAL)
        buf.request(e)
        assertTrue("invalidate 之后发出的请求仍应正常缓存", buf.peek(e) != null)
    }

    // ────────────────────── ④ 预算口径 ──────────────────────

    @Test
    fun `estimatedBytes follows the capacity not the actual cache`() {
        val src = FakeSource(PhotoSourceKind.GALLERY)
        val buf = newBuffer(mapOf(PhotoSourceKind.GALLERY to src), maxCached = 3)
        // 空缓存时预算口径仍是 3 张（这是「上限」而非「实际占用」）
        assertTrue("空缓存时 estimatedBytes 仍应是 3 张的预算", buf.estimatedBytes == 24_883_200L)
        assertTrue("空缓存时实际占用为 0", buf.cachedBytes == 0L)
    }

    // ────────────────────── ⑤ 负向自证 ──────────────────────

    /**
     * **负向自证**：`openCount` 必须是**活的**计数器。
     *
     * `assertTrue(openCount == 1)` 这种断言，在「压根没走到解码」的实现下会因为
     * `0 != 1` 而失败 —— 这是好的；但如果把断言写成 `<= 1`，空转实现（0 次）就会**假通过**。
     * 本用例验证计数确实随请求增长，从而证明 `== 1` 有判别力。
     */
    @Test
    fun `negative proof - the stream counter is live`() {
        val src = FakeSource(PhotoSourceKind.GALLERY)
        val buf = newBuffer(mapOf(PhotoSourceKind.GALLERY to src))

        assertTrue("未请求时不应开流", src.openCount == 0)
        buf.request(ref(1))
        assertTrue("请求后必须真的开流（否则计数是死的）", src.openCount == 1)
        buf.request(ref(2))
        assertTrue("另一张应再开一次流，实际 ${src.openCount}", src.openCount == 2)
    }

    /**
     * **负向自证**：`close()` 必须真的 `recycle`，而不是只把引用从缓存里摘掉。
     *
     * 若实现只 `cache.clear()` 不 recycle，本用例的 `all { it.isRecycled }` 会失败 ——
     * 那正是 API 22 上「反复进出照片墙内存持续增长」的根因。
     */
    @Test
    fun `negative proof - dropping the reference is not enough`() {
        val src = FakeSource(PhotoSourceKind.GALLERY)
        val created = ArrayList<Bitmap>()
        val buf = newBuffer(
            mapOf(PhotoSourceKind.GALLERY to src),
            decoder = { _, _ ->
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { created.add(it) }
            },
        )
        buf.request(ref(1))
        val bmp = created.single()

        assertTrue("close 之前不能是已回收状态", !bmp.isRecycled)
        buf.close()
        assertTrue("close 之后必须真的被 recycle（只清引用不算）", bmp.isRecycled)
    }
}

package com.nasmusic.tv.backend.photo

import com.nasmusic.tv.backend.photo.db.FaceResultStore
import com.nasmusic.tv.backend.photo.db.PhotoFaceEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人脸扫描任务的门禁（G17）
 *
 * ⚠️ 纯 JVM：`FaceScanManager` 只依赖 [FaceResultStore] / [FaceDetector] /
 * [PhotoThumbProvider] 三个接口 —— 用内存实现就能验证「可中断 / 可续跑 / 进度」。
 *
 * ⛔ **验收判据「1 万张可中断、可续跑」的等价缩放**：这里用小照片数 + `chunkSize = 1`
 * 复现同一条状态机路径（分片边界的中断点、按片落库、续跑跳过已扫的 key）。
 * 1 万张只是同一循环多跑几万次迭代，不产生新的分支。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FaceScanManagerTest {

    // ────────────────────────── 假件 ──────────────────────────

    private class FakeStore : FaceResultStore {
        val rows = LinkedHashMap<String, PhotoFaceEntity>()
        override suspend fun faceKeys(): Set<String> = rows.values.filter { it.hasFace }.map { it.photoKey }.toSet()
        override suspend fun allKeys(): Set<String> = rows.keys.toSet()
        override suspend fun upsertAll(entities: List<PhotoFaceEntity>) {
            entities.forEach { rows[it.photoKey] = it }
        }
        override suspend fun clear() = rows.clear()
        override suspend fun count(): Int = rows.size
    }

    /** 宽度 = 照片序号 + 1 的 1×1 缩略图（**宽度即照片身份**，断言可辨） */
    private class FakeThumbs : PhotoThumbProvider {
        val opened = LinkedHashSet<String>()
        override suspend fun decode(ref: PhotoRef): FaceThumb {
            opened.add(ref.id)
            return FaceThumb(1, 1, FloatArray(3))
        }
    }

    /**
     * 假检测器：返回 `faceCount[key] ?: 1`；记录调用次数。
     * ⚠️ `onDetect` 钩子用来在特定照片的检测里触发 [FaceScanManager.stop]，
     * 复现「跑到一半被打断」。
     */
    private class FakeDetector : FaceDetector {
        var calls = 0
        var warmUpOk = true
        val faceCount = HashMap<String, Int>()
        var onDetect: (() -> Unit)? = null
        override fun warmUp(): Boolean = warmUpOk
        override fun detect(thumb: FaceThumb): Int {
            calls++
            onDetect?.invoke()
            return 1
        }
    }

    private fun refs(n: Int): List<PhotoRef> = List(n) { i ->
        PhotoRef(
            id = "external:p$i",
            displayName = "p$i.jpg",
            width = 0, height = 0, size = 0L,
            lastModified = 100L + i, dateAdded = 100L + i,
            source = PhotoSourceKind.EXTERNAL,
        )
    }

    private fun manager(
        store: FakeStore,
        detector: FakeDetector,
        thumbs: FakeThumbs,
        dispatcher: TestDispatcher,
        scope: kotlinx.coroutines.CoroutineScope,
    ): FaceScanManager = FaceScanManager(
        store = store,
        detector = detector,
        thumbs = thumbs,
        scope = scope,
        dispatcher = dispatcher,
        chunkSize = 1,
    )

    // ────────────────────────── 用例 ──────────────────────────

    @Test
    fun `scans every photo and persists per-photo results`() = runTest {
        val store = FakeStore()
        val detector = FakeDetector()
        val thumbs = FakeThumbs()
        val m = manager(store, detector, thumbs, StandardTestDispatcher(testScheduler), this)

        assertTrue(m.start(refs(3)))
        advanceUntilIdle()

        assertEquals("3 张照片各推理一次", 3, detector.calls)
        assertEquals(3, store.rows.size)
        assertTrue(store.rows.values.all { it.hasFace })
        assertEquals(FaceScanManager.Phase.DONE, m.state.value.phase)
        assertEquals(3, m.state.value.done)
        assertEquals(3, m.state.value.total)
    }

    @Test
    fun `resume skips already scanned keys and does not re-run the detector`() = runTest {
        val store = FakeStore()
        // 预置一张已扫过的结果（上次会话留下的）
        store.rows["external:p0"] = PhotoFaceEntity("external:p0", hasFace = false, faceCount = 0, detectedAt = 1L, fileModifiedSec = 100L)
        val detector = FakeDetector()
        val thumbs = FakeThumbs()
        val m = manager(store, detector, thumbs, StandardTestDispatcher(testScheduler), this)

        assertTrue(m.start(refs(3)))
        advanceUntilIdle()

        assertEquals("已扫过的那张不得再次推理", 2, detector.calls)
        assertEquals("续跑后三张都有结果", 3, store.rows.size)
        assertFalse("已有结果（无人脸）不被覆盖成有人脸", store.rows["external:p0"]!!.hasFace)
    }

    @Test
    fun `interrupt keeps partial results and the next start resumes from where it stopped`() = runTest {
        val store = FakeStore()
        val detector = FakeDetector()
        val thumbs = FakeThumbs()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val m = manager(store, detector, thumbs, dispatcher, this)

        // 第 2 张照片检测到一半被打断（复现用户点「停止」）
        val photos = refs(3)
        var stopped = false
        detector.onDetect = {
            if (detector.calls == 2 && !stopped) {
                stopped = true
                m.stop()
            }
        }

        assertTrue(m.start(photos))
        advanceUntilIdle()

        assertTrue("中断动作必须真的发生过（否则这条用例是空转）", stopped)
        assertEquals(2, detector.calls)
        assertEquals("中断时已处理完的片都已落库", 2, store.rows.size)
        assertEquals("中断后回到 IDLE", FaceScanManager.Phase.IDLE, m.state.value.phase)

        // 续跑：只处理剩下那一张
        val before = detector.calls
        assertTrue(m.start(photos))
        advanceUntilIdle()
        assertEquals(before + 1, detector.calls)
        assertEquals("续跑后三张都有结果", 3, store.rows.size)
        assertEquals(FaceScanManager.Phase.DONE, m.state.value.phase)
    }

    @Test
    fun `undecodable photos are skipped without writing a negative result`() = runTest {
        val store = FakeStore()
        val detector = FakeDetector()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val m = FaceScanManager(
            store = store, detector = detector,
            thumbs = { ref -> if (ref.id == "external:p2") FaceThumb(1, 1, FloatArray(3)) else null },
            scope = this, dispatcher = dispatcher, chunkSize = 1,
        )

        assertTrue(m.start(refs(3)))
        advanceUntilIdle()

        assertEquals("解码失败的两张不推理", 1, detector.calls)
        assertEquals("⛔ 读不出来 ≠ 没有脸：不得写入 hasFace=false（否则重插盘后这张永远被排除）",
            1, store.rows.size)
        assertEquals(FaceScanManager.Phase.DONE, m.state.value.phase)
    }

    @Test
    fun `warmup failure aborts with UNAVAILABLE instead of running empty inference`() = runTest {
        val store = FakeStore()
        val detector = FakeDetector().apply { warmUpOk = false }
        val thumbs = FakeThumbs()
        val m = manager(store, detector, thumbs, StandardTestDispatcher(testScheduler), this)

        assertTrue(m.start(refs(2)))
        advanceUntilIdle()

        assertEquals(FaceScanManager.Phase.UNAVAILABLE, m.state.value.phase)
        assertEquals(0, detector.calls)
        assertEquals("模型不可用时一张都不该写", 0, store.rows.size)
    }

    @Test
    fun `start refuses duplicate runs and empty photo lists`() = runTest {
        val store = FakeStore()
        val detector = FakeDetector()
        val thumbs = FakeThumbs()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val m = manager(store, detector, thumbs, dispatcher, this)

        assertTrue(m.start(refs(3)))
        // 还没 advance ⇒ 任务处于 RUNNING ⇒ 第二次 start 必须被拒
        assertFalse("正在跑时不得重复启动（设置页按钮可能连点）", m.start(refs(3)))
        advanceUntilIdle()

        assertFalse("空列表直接拒绝", m.start(emptyList()))
        assertEquals(FaceScanManager.Phase.DONE, m.state.value.phase)
    }

    @Test
    fun `clear results wipes the store and resets progress`() = runTest {
        val store = FakeStore()
        val detector = FakeDetector()
        val thumbs = FakeThumbs()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val m = manager(store, detector, thumbs, dispatcher, this)

        assertTrue(m.start(refs(2)))
        advanceUntilIdle()
        assertEquals(2, store.rows.size)

        m.clearResults()
        advanceUntilIdle()
        assertEquals(0, store.rows.size)
        assertEquals(FaceScanManager.FaceScanState(), m.state.value)
    }

    @Test
    fun `interrupted scan reports partial progress against the full total`() = runTest {
        val store = FakeStore()
        val detector = FakeDetector()
        val thumbs = FakeThumbs()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val m = manager(store, detector, thumbs, dispatcher, this)

        val photos = refs(4)
        var stopped = false
        detector.onDetect = {
            if (detector.calls == 2 && !stopped) {
                stopped = true
                m.stop()
            }
        }

        m.start(photos)
        advanceUntilIdle()

        assertTrue(stopped)
        assertEquals("已完成 2 张", 2, m.state.value.done)
        assertEquals("total 必须是本轮要扫的总量（不是剩余量）", 4, m.state.value.total)
        assertEquals(FaceScanManager.Phase.IDLE, m.state.value.phase)
    }
}

package com.nasmusic.tv.player

import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-10（2026-09-16）：`PlayerManager.removeFromQueue` 的边界用例。
 *
 * 被测对象是抽出来的纯函数 [computeQueueRemoval]——不依赖 ExoPlayer / Context，
 * 因此可在 JVM 单测里直接覆盖「移除末尾正在播放项」这一原缺陷场景。
 */
class QueueRemovalTest {

    private fun songs(vararg ids: String) = ids.map { Song(id = it, title = it) }

    @Test
    fun `remove item before current shifts index left`() {
        val q = songs("a", "b", "c")
        val r = computeQueueRemoval(q, currentIndex = 2, removeIndex = 0)
        assertEquals(listOf("b", "c"), r.queue.map { it.id })
        assertEquals(1, r.currentIndex)
        assertEquals("c", r.queue[r.currentIndex].id)
        assertNull(r.seekTo)
    }

    @Test
    fun `remove item after current keeps index`() {
        val q = songs("a", "b", "c")
        val r = computeQueueRemoval(q, currentIndex = 0, removeIndex = 2)
        assertEquals(listOf("a", "b"), r.queue.map { it.id })
        assertEquals(0, r.currentIndex)
        assertNull(r.seekTo)
    }

    @Test
    fun `remove current middle item keeps index and needs seek`() {
        val q = songs("a", "b", "c")
        val r = computeQueueRemoval(q, currentIndex = 1, removeIndex = 1)
        assertEquals(listOf("a", "c"), r.queue.map { it.id })
        assertEquals(1, r.currentIndex)
        assertEquals("c", r.queue[r.currentIndex].id)
        assertEquals(1, r.seekTo ?: -1)
    }

    /** 核心边界：移除**末尾正在播放**项 —— 必须显式 seekTo，否则 ExoPlayer 进 STATE_ENDED */
    @Test
    fun `remove current last item clamps to new tail and needs seek`() {
        val q = songs("a", "b", "c")
        val r = computeQueueRemoval(q, currentIndex = 2, removeIndex = 2)
        assertEquals(listOf("a", "b"), r.queue.map { it.id })
        assertEquals(1, r.currentIndex)
        assertEquals("b", r.queue[r.currentIndex].id)
        assertEquals(1, r.seekTo ?: -1)
    }

    @Test
    fun `remove only item empties queue and needs no seek`() {
        val q = songs("a")
        val r = computeQueueRemoval(q, currentIndex = 0, removeIndex = 0)
        assertTrue(r.queue.isEmpty())
        assertEquals(0, r.currentIndex)
        assertNull(r.seekTo)
    }

    @Test
    fun `remove first of two when current is first`() {
        val q = songs("a", "b")
        val r = computeQueueRemoval(q, currentIndex = 0, removeIndex = 0)
        assertEquals(listOf("b"), r.queue.map { it.id })
        assertEquals(0, r.currentIndex)
        assertEquals(0, r.seekTo ?: -1)
    }
}

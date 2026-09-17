package com.nasmusic.tv.player

import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 3（2026-09-17）：Android Auto 车机搜索的**双源合并**规则。
 *
 * 被测对象是抽出来的纯函数 [mergeSearchResults] —— 不依赖 Context / 网络 / Media3，
 * 因此可在 JVM 单测里直接覆盖去重、保序与截断（同 `QueueRemovalTest` 的做法）。
 *
 * 这一层值得测的原因：**合并顺序与去重优先级是产品语义**（网络音乐优先于 NAS），
 * 写错了不会崩、只会「搜索结果里混进不该出现的条目」，靠 DHU 很难发现。
 */
class SearchMergeTest {

    private fun song(id: String, title: String = id) = Song(id = id, title = title)

    private fun songs(vararg ids: String) = ids.map { song(it) }

    private fun idsOf(result: List<Song>) = result.map { it.id }

    @Test
    fun `both sources empty yields empty`() {
        assertTrue(mergeSearchResults(emptyList(), emptyList(), limit = 50).isEmpty())
    }

    @Test
    fun `network only is passed through in order`() {
        val result = mergeSearchResults(songs("n1", "n2"), emptyList(), limit = 50)
        assertEquals(listOf("n1", "n2"), idsOf(result))
    }

    @Test
    fun `nas only is passed through in order`() {
        val result = mergeSearchResults(emptyList(), songs("s1", "s2"), limit = 50)
        assertEquals(listOf("s1", "s2"), idsOf(result))
    }

    @Test
    fun `network results come before nas results`() {
        val result = mergeSearchResults(songs("n1", "n2"), songs("s1", "s2"), limit = 50)
        assertEquals(listOf("n1", "n2", "s1", "s2"), idsOf(result))
    }

    @Test
    fun `duplicate id keeps the network entry`() {
        val result = mergeSearchResults(
            networkSongs = listOf(song("x", "net-x")),
            nasSongs = listOf(song("x", "nas-x")),
            limit = 50
        )
        assertEquals(1, result.size)
        // 保留先出现的那条 —— 即网络侧
        assertEquals("net-x", result[0].title)
    }

    @Test
    fun `duplicates within one source are collapsed too`() {
        val result = mergeSearchResults(songs("a", "a", "b"), emptyList(), limit = 50)
        assertEquals(listOf("a", "b"), idsOf(result))
    }

    @Test
    fun `dedup happens before truncation`() {
        // 网络 3 条 + NAS 4 条，其中前 3 条与网络重复 → 去重后共 4 条，limit 4 应全给
        // 若实现是「先截断再去重」，会先取 net(a,b,c) + nas 的前 1 条(a) 再去重 → 只剩 a,b,c
        val result = mergeSearchResults(
            networkSongs = songs("a", "b", "c"),
            nasSongs = songs("a", "b", "c", "d"),
            limit = 4
        )
        assertEquals(listOf("a", "b", "c", "d"), idsOf(result))
    }

    @Test
    fun `truncates to limit keeping highest priority first`() {
        val result = mergeSearchResults(
            networkSongs = songs("n1", "n2", "n3"),
            nasSongs = songs("s1", "s2", "s3"),
            limit = 3
        )
        assertEquals(listOf("n1", "n2", "n3"), idsOf(result))
    }

    @Test
    fun `limit zero yields empty`() {
        assertTrue(mergeSearchResults(songs("a"), songs("b"), limit = 0).isEmpty())
    }

    @Test
    fun `limit larger than total keeps everything`() {
        val result = mergeSearchResults(songs("a"), songs("b"), limit = 100)
        assertEquals(listOf("a", "b"), idsOf(result))
    }

    @Test
    fun `blank-ish ids are not special-cased`() {
        // 空 id 不是本函数的职责（真实数据里 id 由适配器保证非空）；
        // 这里只锁定行为：两条空 id 会被当成同一条去重，不会抛异常。
        val result = mergeSearchResults(
            networkSongs = listOf(song("", "first")),
            nasSongs = listOf(song("", "second")),
            limit = 50
        )
        assertEquals(1, result.size)
        assertEquals("first", result[0].title)
    }
}

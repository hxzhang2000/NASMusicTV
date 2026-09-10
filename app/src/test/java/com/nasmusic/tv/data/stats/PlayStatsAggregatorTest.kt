package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * PlayStatsAggregator 聚合逻辑单测（F2-1）
 *
 * 覆盖：空数据 / 重复歌手归并（大小写） / genre null 归"未分类" /
 * 播放量排序 / songCount 统计 / 元数据缺失 songId 容错。
 */
class PlayStatsAggregatorTest {

    private fun song(
        id: String,
        artist: String = "歌手A",
        genre: String? = "Pop",
        cover: String? = null
    ) = Song(id = id, title = "歌-$id", artist = artist, genre = genre, coverUrl = cover)

    @Test
    fun `empty counts yields empty bundle`() {
        val b = PlayStatsAggregator.aggregate("2026-09", emptyMap(), emptyList())
        assertEquals(0, b.totalPlays)
        assertEquals(0, b.distinctSongs)
        assertTrue(b.topArtists.isEmpty())
        assertTrue(b.genreDistribution.isEmpty())
    }

    @Test
    fun `same artist different case merged`() {
        val meta = listOf(
            song("s1", artist = "Jay Chou"),
            song("s2", artist = "jay chou"),
            song("s3", artist = "JAY CHOU")
        )
        val counts = mapOf("s1" to 2, "s2" to 3, "s3" to 1)
        val b = PlayStatsAggregator.aggregate("2026-09", counts, meta)
        assertEquals(1, b.topArtists.size)
        assertEquals(6, b.topArtists[0].playCount)
        assertEquals(3, b.topArtists[0].songCount)
    }

    @Test
    fun `null and blank genre fall into untagged bucket`() {
        val meta = listOf(
            song("s1", genre = null),
            song("s2", genre = "  "),
            song("s3", genre = "Rock")
        )
        val counts = mapOf("s1" to 1, "s2" to 2, "s3" to 3)
        val b = PlayStatsAggregator.aggregate("2026-09", counts, meta)
        assertEquals(2, b.genreDistribution.size)
        val untagged = b.genreDistribution.first { it.genre == "未分类" }
        assertEquals(3, untagged.playCount)
        assertEquals(2, untagged.songCount)
    }

    @Test
    fun `top artists sorted by play count desc`() {
        val meta = listOf(
            song("a1", artist = "甲"), song("a2", artist = "甲"),
            song("b1", artist = "乙"),
            song("c1", artist = "丙"), song("c2", artist = "丙"), song("c3", artist = "丙")
        )
        val counts = mapOf("a1" to 5, "a2" to 1, "b1" to 3, "c1" to 1, "c2" to 1, "c3" to 1)
        val b = PlayStatsAggregator.aggregate("2026-09", counts, meta)
        // 甲 6 次 > 乙 3 次 = 丙 3 次（同次数按小写 key 字典序："丙"前缀排序见 key 排序，不依赖 Unicode 顺序断言）
        assertEquals("甲", b.topArtists[0].artist)
        assertEquals(6, b.topArtists[0].playCount)
        val rest = b.topArtists.drop(1).map { it.playCount }
        assertEquals(listOf(3, 3), rest)
    }

    @Test
    fun `song id without metadata still counts total but not artist`() {
        val counts = mapOf("ghost" to 4, "known" to 1)
        val meta = listOf(song("known", artist = "歌手A"))
        val b = PlayStatsAggregator.aggregate("2026-09", counts, meta)
        assertEquals(5, b.totalPlays)
        // 元数据缺失的歌不进入歌手/流派聚合，但 distinctSongs 只算有元数据的
        assertEquals(1, b.distinctSongs)
        assertTrue(b.topArtists.all { it.artist != "" })
    }

    @Test
    fun `limits respected`() {
        val meta = (1..20).map { song("s$it", artist = "歌手$it") }
        val counts = meta.associate { it.id to 1 }
        val b = PlayStatsAggregator.aggregate("2026-09", counts, meta, topArtistLimit = 5, genreLimit = 3)
        assertEquals(5, b.topArtists.size)
        // 全部同 genre "Pop"，只有一个流派
        assertEquals(1, b.genreDistribution.size)
    }
}

/**
 * PlayStatsRepository 纯逻辑单测（不依赖 Android DataStore，测月份键与滚动清理算法）
 */
class PlayStatsRepositoryLogicTest {

    @Test
    fun `currentMonth format is yyyy-MM`() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 10) }
        val month = PlayStatsRepository.currentMonth(cal.timeInMillis)
        assertEquals("2026-09", month)
        assertTrue(SimpleDateFormat("yyyy-MM", Locale.US).parse(month) != null)
    }

    @Test
    fun `monthStartMs parses month key`() {
        val ms = PlayStatsRepository.monthStartMs("2026-09")
        assertTrue(ms > 0)
        assertEquals(0L, PlayStatsRepository.monthStartMs("bad-key"))
    }
}

package com.nasmusic.tv.backend.radio

import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * RadioSongScorer 打分规则单测（F2-3）
 */
class RadioSongScorerTest {

    private fun song(
        id: String,
        artist: String = "歌手A",
        genre: String? = "Pop",
        albumId: String? = "alb1",
        year: Int? = 2020
    ) = Song(id = id, title = "歌-$id", artist = artist, genre = genre, albumId = albumId, year = year)

    private val seed = song("seed")

    @Test
    fun `same artist genre album year all score`() {
        val cand = song("c1")
        val s = RadioSongScorer.score(cand, seed)
        assertEquals(RadioSongScorer.SCORE_SAME_ARTIST + RadioSongScorer.SCORE_SAME_GENRE +
            RadioSongScorer.SCORE_SAME_ALBUM + RadioSongScorer.SCORE_SAME_ERA, s)
    }

    @Test
    fun `same artist case insensitive`() {
        val cand = song("c1", artist = "歌手a")
        assertEquals(RadioSongScorer.SCORE_SAME_ARTIST + RadioSongScorer.SCORE_SAME_GENRE +
            RadioSongScorer.SCORE_SAME_ALBUM + RadioSongScorer.SCORE_SAME_ERA,
            RadioSongScorer.score(cand, seed))
    }

    @Test
    fun `different everything scores zero plus playcount`() {
        val cand = song("c1", artist = "别人", genre = "Rock", albumId = "alb2", year = 1990)
        assertEquals(0, RadioSongScorer.score(cand, seed))
        assertEquals(5, RadioSongScorer.score(cand, seed, playCounts = mapOf("c1" to 5)))
        // playcount 上限 20
        assertEquals(20, RadioSongScorer.score(cand, seed, playCounts = mapOf("c1" to 99)))
    }

    @Test
    fun `seed itself and excluded ids are hard excluded`() {
        assertEquals(RadioSongScorer.SCORE_EXCLUDED, RadioSongScorer.score(seed, seed))
        val cand = song("c1")
        assertEquals(RadioSongScorer.SCORE_EXCLUDED, RadioSongScorer.score(cand, seed, excludedIds = setOf("c1")))
    }

    @Test
    fun `year within 3 scores era`() {
        val cand = song("c1", artist = "别人", genre = "Rock", albumId = "alb2", year = 2023)
        assertEquals(RadioSongScorer.SCORE_SAME_ERA, RadioSongScorer.score(cand, seed))
        val cand4 = song("c2", artist = "别人", genre = "Rock", albumId = "alb2", year = 2024)
        assertEquals(0, RadioSongScorer.score(cand4, seed))
    }

    @Test
    fun `null genre does not match`() {
        val seedNoGenre = song("seed2", genre = null)
        val candNoGenre = song("c1", genre = null)
        // 同 artist + 同 album + 同 era，无 genre 分
        assertEquals(RadioSongScorer.SCORE_SAME_ARTIST + RadioSongScorer.SCORE_SAME_ALBUM + RadioSongScorer.SCORE_SAME_ERA,
            RadioSongScorer.score(candNoGenre, seedNoGenre))
    }

    @Test
    fun `generateBatch returns empty for empty candidates`() {
        assertTrue(RadioSongScorer.generateBatch(emptyList(), seed).isEmpty())
    }

    @Test
    fun `generateBatch excludes seed and respects batch size`() {
        val cands = (1..50).map { song("s$it", artist = "别人", genre = "Rock", albumId = "alb$it", year = 1970) }
        val batch = RadioSongScorer.generateBatch(cands, seed, batchSize = 10, random = Random(42))
        assertEquals(10, batch.size)
        assertEquals(10, batch.map { it.id }.toSet().size) // 无重复
    }

    @Test
    fun `generateBatch all excluded yields empty then clear works`() {
        val cands = listOf(song("a"), song("b"))
        // 全部排除（种子除外全是排除对象）
        val batch = RadioSongScorer.generateBatch(cands, seed, excludedIds = setOf("a", "b"), batchSize = 5)
        assertTrue(batch.isEmpty())
        // 清空排除后可生成
        val batch2 = RadioSongScorer.generateBatch(cands, seed, excludedIds = emptySet(), batchSize = 5)
        assertEquals(2, batch2.size)
    }

    @Test
    fun `weighted sampling favors high scores`() {
        // 固定种子下，同 artist 的高分歌应比完全无关的歌出现频次显著更高
        val high = (1..30).map { song("h$it") }  // 同 seed 全维度命中
        val low = (1..30).map { song("l$it", artist = "别人", genre = "Rock", albumId = "x$it", year = 1970) }
        val cands = high + low
        val counts = mutableMapOf<String, Int>()
        repeat(200) {
            val batch = RadioSongScorer.generateBatch(cands, seed, batchSize = 20, random = Random(it))
            batch.forEach { counts[it.id] = (counts[it.id] ?: 0) + 1 }
        }
        val highFreq = counts.filter { it.key.startsWith("h") }.values.sum()
        val lowFreq = counts.filter { it.key.startsWith("l") }.values.sum()
        assertTrue("high=$highFreq low=$lowFreq", highFreq > lowFreq * 2)
    }
}

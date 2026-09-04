package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 艺术家同名合并 / 合唱拆分的回归测试
 *
 * 覆盖场景：
 * 1. 合唱名 "古天乐/萱萱" 必须拆成两个艺术家，且合唱歌同时计入两人
 * 2. 同名不同写法（首尾空白 / 全角 / 大小写）必须合并为一块
 */
class MusicMergerTest {

    private fun song(id: String, artist: String, album: String = "") =
        Song(id = id, title = "t_$id", artist = artist, album = album)

    @Test
    fun `buildArtistsFromSongs splits collaboration names`() {
        val songs = listOf(
            song("1", "古天乐/萱萱"),
            song("2", "古天乐")
        )
        val artists = MusicMerger.buildArtistsFromSongs(songs, "search_artist_")

        val names = artists.map { it.name }.toSet()
        assertEquals(setOf("古天乐", "萱萱"), names)
        // 合唱歌同时计入两位艺术家
        assertEquals(2, artists.first { it.name == "古天乐" }.songCount)
        assertEquals(1, artists.first { it.name == "萱萱" }.songCount)
        assertTrue(artists.none { it.name.contains("/") })
    }

    @Test
    fun `buildArtistsFromSongs merges same name with different spelling`() {
        val songs = listOf(
            song("1", "古天乐"),
            song("2", "古天乐 "),        // 尾部空白
            song("3", "　古天乐"),        // 全角前导空格
            song("4", "ＡＢＣ"),          // 全角字母
            song("5", "abc")             // 半角字母
        )
        val artists = MusicMerger.buildArtistsFromSongs(songs, "search_artist_")

        assertEquals(2, artists.size)
        assertEquals(3, artists.first { it.name == "古天乐" }.songCount)
        assertTrue(artists.any { it.name == "ＡＢＣ" || it.name == "abc" })
    }

    @Test
    fun `mergeArtists merges nas and local entries by normalized name`() {
        val nas = listOf(
            Artist(id = "nas-1", name = "古天乐", songCount = 5, albumCount = 1),
            Artist(id = "nas-2", name = "古天乐/萱萱", songCount = 2, albumCount = 1)
        )
        val local = listOf(
            Artist(id = "local_artist_古天乐", name = "古天乐 ", songCount = 3, albumCount = 0)
        )

        val merged = MusicMerger.mergeArtists(nasArtists = nas, localArtists = local)

        // "NAS 古天乐" 与 "本地 古天乐 " 合并为一块，合唱条目保持独立（拆分在 loadArtists 阶段完成）
        assertEquals(2, merged.size)
        val gutianle = merged.first { it.name.trim() == "古天乐" }
        assertEquals(8, gutianle.songCount)
    }

    @Test
    fun `buildLocalArtists delegates to split based aggregation`() {
        val songs = listOf(song("1", "周杰伦 feat. 杨瑞代"))
        val artists = MusicMerger.buildLocalArtists(songs)
        assertEquals(setOf("周杰伦", "杨瑞代"), artists.map { it.name }.toSet())
        assertTrue(artists.all { it.id.startsWith("local_artist_") })
    }
}

package com.nasmusic.tv.backend.download.model

import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载主键档位化测试（多码率方案 §10.1 / §4.2.2）。
 *
 * 覆盖"零重建迁移"的核心断言：AUTO 档的 key 必须与旧格式**完全相等**，
 * 否则升级后每首已下载歌曲都会被重复下载。
 */
class DownloadKeyTest {

    private fun metingSong(id: String = "1234567") = Song(
        id = "ntwk_meting_$id",
        title = "南方姑娘",
        artist = "赵雷",
        isNetworkSong = true,
        networkSource = "meting",
        networkId = id
    )

    private fun jamendoSong() = Song(
        id = "ntwk_jamendo_x",
        title = "A",
        artist = "B",
        isNetworkSong = true,
        networkSource = "jamendo",
        networkId = "x"
    )

    private fun localSong() = Song(id = "local_1", title = "T", isLocalSong = true)

    private fun nasSong() = Song(id = "nas_1", title = "T")

    @Test
    fun `Meting 档位 999 的 key 为下划线前缀加 q 后缀`() {
        // 真实格式是 ntwk_<source>_<id>（下划线），不是 ntwk:<id>（冒号）
        assertEquals("ntwk_meting_1234567:q999", metingSong().downloadKeyOf(999))
    }

    @Test
    fun `Meting 档位 320 的 key`() {
        assertEquals("ntwk_meting_1234567:q320", metingSong().downloadKeyOf(320))
    }

    @Test
    fun `同曲两档 key 不冲突`() {
        val s = metingSong()
        assertNotEquals(s.downloadKeyOf(999), s.downloadKeyOf(320))
        assertNotEquals(s.downloadKeyOf(320), s.downloadKeyOf(192))
        assertNotEquals(s.downloadKeyOf(192), s.downloadKeyOf(128))
    }

    @Test
    fun `AUTO 档 key 与旧格式完全相等（零迁移关键断言）`() {
        val s = metingSong()
        // 存量行（升级前写入、无 :q 后缀）必须能被 AUTO 档命中，
        // 否则升级后"已下载歌曲被重复下载"
        assertEquals(s.downloadKey, s.downloadKeyOf(0))
        assertEquals("ntwk_meting_1234567", s.downloadKeyOf(0))
    }

    @Test
    fun `存量格式与新格式字符串不等 主键不冲突`() {
        val s = metingSong()
        assertNotEquals(s.downloadKeyOf(0), s.downloadKeyOf(999))
    }

    @Test
    fun `非 Meting 源忽略档位`() {
        val s = jamendoSong()
        assertEquals(s.downloadKey, s.downloadKeyOf(320))
        assertTrue(!s.downloadKeyOf(320).contains(":q"))
    }

    @Test
    fun `本地与 NAS 歌曲忽略档位`() {
        assertEquals(localSong().downloadKey, localSong().downloadKeyOf(999))
        assertEquals(nasSong().downloadKey, nasSong().downloadKeyOf(999))
        assertTrue(!localSong().downloadKeyOf(999).contains(":q"))
        assertTrue(!nasSong().downloadKeyOf(999).contains(":q"))
    }

    @Test
    fun `负档位退化为 AUTO 语义`() {
        val s = metingSong()
        assertEquals(s.downloadKey, s.downloadKeyOf(-1))
    }

    @Test
    fun `downloadedKey 与 downloadKeyOf 一致（AUTO）`() {
        // 两者对 AUTO 必须产出同一字符串，否则查询层与写入层会错位
        val s = metingSong()
        assertEquals(s.downloadKey, s.downloadKeyOf(0))
    }
}

package com.nasmusic.tv.backend.download.model

import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载状态表按曲查询测试（多码率方案 §4.2.2 配套回归）。
 *
 * 背景：v2.35.0 起 Meting 源的下载主键带 `:q<quality>` 后缀，
 * UI 若继续用无后缀的 `downloadStates[song.downloadKey]` 查询会**永远 miss**，
 * 导致已下载歌曲显示为未下载（✓ 变 ⬇）。本测试锁定该回归。
 */
class DownloadStateLookupTest {

    private fun song(id: String = "1", source: String = "meting") = Song(
        id = "ntwk_${source}_$id",
        title = "南方姑娘",
        artist = "赵雷",
        isNetworkSong = true,
        networkSource = source,
        networkId = id
    )

    @Test
    fun `AUTO 档精确命中（存量行）`() {
        val s = song()
        val states = mapOf(s.downloadKey to DownloadState.Completed("/x.mp3"))
        assertTrue(states.stateOfSong(s) is DownloadState.Completed)
    }

    @Test
    fun `带档位后缀的键也能命中（回归主用例）`() {
        val s = song()
        // 用户下载了 320，状态表 key 是 ntwk_meting_1:q320
        val states = mapOf("${s.downloadKey}:q320" to DownloadState.Completed("/x.mp3"))
        // 旧写法 downloadStates[s.downloadKey] 会 miss → 显示未下载
        assertTrue(
            "带后缀的键必须能被 stateOfSong 命中",
            states.stateOfSong(s) is DownloadState.Completed
        )
    }

    @Test
    fun `多档并存时进行中优先`() {
        val s = song()
        val states = mapOf(
            "${s.downloadKey}:q320" to DownloadState.Completed("/a.mp3"),
            "${s.downloadKey}:q999" to DownloadState.Downloading(42)
        )
        val st = states.stateOfSong(s)
        assertTrue(st is DownloadState.Downloading)
        assertEquals(42, (st as DownloadState.Downloading).progress)
    }

    @Test
    fun `多档并存时排队优先于完成`() {
        val s = song()
        val states = mapOf(
            "${s.downloadKey}:q320" to DownloadState.Completed("/a.mp3"),
            "${s.downloadKey}:q128" to DownloadState.Queued
        )
        assertTrue(states.stateOfSong(s) is DownloadState.Queued)
    }

    @Test
    fun `无任何记录返回 None`() {
        assertTrue(mapOf<String, DownloadState>().stateOfSong(song()) is DownloadState.None)
    }

    @Test
    fun `不匹配其他歌曲的档位键`() {
        val s = song(id = "1")
        val other = mapOf("ntwk_meting_2:q320" to DownloadState.Completed("/b.mp3"))
        assertTrue(other.stateOfSong(s) is DownloadState.None)
    }

    @Test
    fun `非 Meting 源不做前缀匹配`() {
        val s = song(source = "jamendo")
        val states = mapOf("${s.downloadKey}:q320" to DownloadState.Completed("/c.mp3"))
        // Jamendo 不支持多码率，不应产出带后缀的键；若出现也不应被匹配
        assertTrue(states.stateOfSong(s) is DownloadState.None)
    }

    @Test
    fun `失败状态可被查询到`() {
        val s = song()
        val states = mapOf("${s.downloadKey}:q999" to DownloadState.Failed("HTTP 404"))
        assertTrue(states.stateOfSong(s) is DownloadState.Failed)
    }
}

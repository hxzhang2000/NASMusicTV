package com.nasmusic.tv.backend.playlist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.data.prefs.AppPreferences
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 歌单导入编排测试（docs/playlist-import-feature-plan.md §6 Importer 行 / §4.2）。
 * 走真实 AppPreferences（datastore），验证：取名兜底链、去重、RELATIVE_PATH 计
 * skipped、URL 直链持久化（streamUrl 不被 strip）、进度回调、导入历史记录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistImporterTest {

    private lateinit var prefs: AppPreferences
    private lateinit var importer: PlaylistImporter
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext
        prefs = AppPreferences(app)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        importer = PlaylistImporter(app as android.app.Application, prefs, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun settle() = delay(30)

    // ---------- 取名兜底（§4.2） ----------

    @Test
    fun `playlist name priority user input wins`() {
        assertEquals(
            "用户输入",
            importer.resolvePlaylistName("我的歌单.m3u", "内嵌名", "  用户输入  ")
        )
    }

    @Test
    fun `playlist name falls back to inner name`() {
        assertEquals("内嵌名", importer.resolvePlaylistName("我的歌单.m3u", "  内嵌名  ", null))
    }

    @Test
    fun `playlist name falls back to file base name`() {
        assertEquals("我的歌单", importer.resolvePlaylistName("我的歌单.m3u", null, null))
    }

    @Test
    fun `meaningless file name falls back to default`() {
        val name = importer.resolvePlaylistName("新建文本文档.txt", null, null)
        assertTrue(name.startsWith("导入的歌单 "))
        assertEquals(6 + 11, name.length)   // 前缀 6 字符 + MM-dd HH:mm 11 位
    }

    // ---------- 导入入库 ----------

    @Test
    fun `m3u import persists http url as streamUrl`() = runBlocking {
        val text = "#EXTM3U\n" +
            "#EXTINF:300,海阔天空 - Beyond\n" +
            "http://example.com/song.mp3\n"
        val summary = importer.importBytes(text.toByteArray(), "beyond.m3u")
        settle()

        assertEquals(1, summary.imported)
        assertEquals(0, summary.skipped)
        assertEquals("beyond", summary.playlistName)

        val playlist = prefs.getLocalPlaylists().first { it.id == summary.playlistId }
        val song = playlist.songs.single()
        assertEquals("http://example.com/song.mp3", song.streamUrl)  // URL 直链持久化
        assertFalse(song.isNetworkSong)                              // strip 不清理本地式歌曲
        assertEquals(300_000L, song.durationMs)
    }

    @Test
    fun `relative path entries count as skipped`() = runBlocking {
        val text = "#EXTM3U\n" +
            "#EXTINF:200,Beyond - 海阔天空\n" +
            "music/song.mp3\n"
        val summary = importer.importBytes(text.toByteArray(), "rel.m3u")
        settle()

        assertEquals(0, summary.imported)
        assertEquals(1, summary.skipped)
        val playlist = prefs.getLocalPlaylists().first { it.id == summary.playlistId }
        assertTrue(playlist.songs.isEmpty())
    }

    @Test
    fun `duplicate entries within file count as skipped`() = runBlocking {
        val text = "海阔天空 - Beyond\n海阔天空 - Beyond\n光辉岁月 - Beyond\n"
        val summary = importer.importBytes(text.toByteArray(), "dup.txt")
        settle()

        assertEquals(2, summary.imported)
        assertEquals(1, summary.skipped)
    }

    @Test
    fun `txt import uses inner name from first comment line`() = runBlocking {
        val text = "# 我的歌单：周末开车\n" +
            "海阔天空 - Beyond\n" +
            "光辉岁月 - Beyond\n"
        val summary = importer.importBytes(text.toByteArray(), "新建文本文档.txt")
        settle()

        assertEquals("周末开车", summary.playlistName)
        assertEquals(2, summary.imported)
    }

    @Test
    fun `netease json import with playlist name`() = runBlocking {
        val json = """
            {
              "playlist": {"name": "网易云精选", "id": 1},
              "tracks": [
                {"name": "晴天", "artists": [{"name": "周杰伦"}], "album": {"name": "叶惠美"}, "duration": 269000},
                {"name": "七里香", "artists": [{"name": "周杰伦"}], "album": {"name": "七里香"}, "duration": 258000}
              ]
            }
        """.trimIndent()
        val summary = importer.importBytes(json.toByteArray(), "netease.json")
        settle()

        assertEquals("网易云精选", summary.playlistName)
        assertEquals(2, summary.imported)
        val playlist = prefs.getLocalPlaylists().first { it.id == summary.playlistId }
        assertEquals("周杰伦", playlist.songs.first().artist)
        assertEquals(269, playlist.songs.first().durationMs / 1000)
    }

    @Test
    fun `json v2 import roundtrip`() = runBlocking {
        val jsonParser = JsonPlaylistParser()
        val exported = jsonParser.serialize(
            "自导歌单",
            listOf(
                SongExportEntry(title = "海阔天空", artist = "Beyond", durationSec = 300),
                SongExportEntry(title = "光辉岁月", artist = "Beyond"),
            )
        )
        val summary = importer.importBytes(exported.toByteArray(), "exported.json")
        settle()

        assertEquals("自导歌单", summary.playlistName)
        assertEquals(2, summary.imported)
        assertEquals(300, prefs.getLocalPlaylists().first { it.id == summary.playlistId }
            .songs.first().durationMs / 1000)
    }

    @Test
    fun `unrecognized format reports flag and creates nothing`() = runBlocking {
        val summary = importer.importBytes("随便什么东西".toByteArray(), "unknown.xyz")
        settle()

        assertTrue(summary.unrecognizedFormat)
        assertEquals(0, summary.imported)
        assertTrue(prefs.getLocalPlaylists().isEmpty())
    }

    @Test
    fun `empty bytes are unrecognized`() = runBlocking {
        val summary = importer.importBytes(ByteArray(0), "empty.m3u")
        assertTrue(summary.unrecognizedFormat)
    }

    @Test
    fun `progress reports 0 then 25 step increments then 100`() = runBlocking {
        val text = (1..4).joinToString("\n") { "歌曲$it - 歌手$it" }
        val steps = mutableListOf<Int>()
        val summary = importer.importBytes(text.toByteArray(), "prog.txt", onProgress = { steps.add(it) })
        settle()

        assertEquals(4, summary.imported)
        // 25% 步进：0 → 25 → 50 → 75 → 100（最后强制 100）
        assertTrue(steps.first() == 0)
        assertTrue(steps.last() == 100)
        assertTrue(steps.windowed(2).all { (a, b) -> b > a })
    }

    @Test
    fun `import records history entry`() = runBlocking {
        val text = "#EXTM3U\n" +
            "#EXTINF:200,Beyond - 海阔天空\n" +
            "http://example.com/song.mp3\n"
        val summary = importer.importBytes(text.toByteArray(), "hist.m3u")
        settle()

        val history = prefs.playlistImportHistory.first()
        assertEquals(1, history.size)
        assertEquals(summary.playlistId, history.first().playlistId)
        assertEquals("hist", history.first().playlistName)
        assertEquals(1, history.first().importedCount)
    }

    @Test
    fun `m3u bare url line becomes its own entry`() = runBlocking {
        val text = "http://example.com/song.mp3\n"
        val summary = importer.importBytes(text.toByteArray(), "single.m3u")
        settle()

        assertEquals(1, summary.imported)
        val song = prefs.getLocalPlaylists().first { it.id == summary.playlistId }.songs.single()
        assertEquals("http://example.com/song.mp3", song.streamUrl)
        assertEquals("song", song.title)
    }
}
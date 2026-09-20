package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.data.model.PlaylistImportHistoryItem
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 歌单导入数据层测试（docs/archive/playlist-import-feature-plan.md §6 Prefs 行）：
 * 历史 20 条淘汰；删除歌单联动清历史；备份导出/导入（playlistImportHistory +
 * songReachability）；URL 可达性读写与 24h 判定窗口。
 *
 * 注：datastore 1.0.0 在 Windows + Robolectric 下连续快速写同名文件存在
 * rename 竞态（SingleProcessDataStore.writeData 的 .tmp rename 失败），
 * 与 [ProviderMirrorTest] 用轮询等待同理——连续写之间加短暂 delay 让出句柄。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppPreferencesPlaylistTest {

    private lateinit var prefs: AppPreferences
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AppPreferences(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun settle() = delay(30)

    private fun genItem(index: Int, playlistId: String = "pid_$index") = PlaylistImportHistoryItem(
        playlistId = playlistId,
        playlistName = "歌单 $index",
        importedAt = 1_700_000_000_000L + index,
        importedCount = index
    )

    @Test
    fun `history keeps latest 20 and evicts oldest`() = runBlocking {
        repeat(25) { i ->
            prefs.recordPlaylistImport(genItem(i))
            settle()
        }
        val history = prefs.playlistImportHistory.first()
        assertEquals(20, history.size)
        assertEquals("pid_24", history.first().playlistId)   // 最新在最前
        assertEquals("pid_5", history.last().playlistId)     // 最旧 0..4 被淘汰
    }

    @Test
    fun `record same playlist id replaces entry`() = runBlocking {
        prefs.recordPlaylistImport(genItem(1, "same"))
        settle()
        prefs.recordPlaylistImport(genItem(2, "same").copy(importedCount = 99))
        settle()
        val history = prefs.playlistImportHistory.first()
        assertEquals(1, history.size)
        assertEquals(99, history.first().importedCount)
    }

    @Test
    fun `delete playlist history entry when playlist removed`() = runBlocking {
        prefs.recordPlaylistImport(genItem(1, "pid_a"))
        settle()
        prefs.recordPlaylistImport(genItem(2, "pid_b"))
        settle()
        prefs.deletePlaylistImportHistory("pid_a")
        settle()
        val history = prefs.playlistImportHistory.first()
        assertEquals(listOf("pid_b"), history.map { it.playlistId })
    }

    @Test
    fun `song reachability mark get and clear`() = runBlocking {
        prefs.markSongUnreachable("imported_1", "NOT_FOUND")
        settle()
        prefs.markSongUnreachable("imported_2", "TIMEOUT")
        settle()
        val map = prefs.getSongReachability()
        assertEquals("NOT_FOUND", map["imported_1"]?.result)
        assertEquals("TIMEOUT", map["imported_2"]?.result)

        prefs.clearSongUnreachable("imported_1")
        settle()
        val after = prefs.getSongReachability()
        assertEquals(1, after.size)
        assertEquals("TIMEOUT", after["imported_2"]?.result)
    }

    @Test
    fun `reachability flow mirrors stored map`() = runBlocking {
        prefs.markSongUnreachable("imported_x", "DNS_FAILED")
        settle()
        val flowVal = prefs.songReachability.first()
        assertEquals("DNS_FAILED", flowVal["imported_x"]?.result)
    }

    @Test
    fun `backup export and import roundtrip history and reachability`() = runBlocking {
        prefs.recordPlaylistImport(genItem(1, "pid_roundtrip"))
        settle()
        prefs.markSongUnreachable("imported_rt", "NOT_FOUND")
        settle()

        val data = prefs.exportBackupData()
        assertEquals(1, data.playlistImportHistory.size)
        assertEquals("pid_roundtrip", data.playlistImportHistory.first().playlistId)
        // 24h 窗口内 → 导出保留
        assertEquals("NOT_FOUND", data.songReachability["imported_rt"]?.result)

        // 清空后从备份恢复
        prefs.deletePlaylistImportHistory("pid_roundtrip")
        settle()
        prefs.clearSongUnreachable("imported_rt")
        settle()
        prefs.importBackupData(data)
        settle()

        val history = prefs.playlistImportHistory.first()
        assertEquals("pid_roundtrip", history.first().playlistId)
        assertEquals("NOT_FOUND", prefs.getSongReachability()["imported_rt"]?.result)
    }

    @Test
    fun `reachability entries older than 24h window are dropped on export`() = runBlocking {
        prefs.markSongUnreachable("imported_fresh", "NOT_FOUND")
        settle()
        // 手工写入一条过期条目（checkedAt 在 24h 窗口外）
        prefs.importBackupData(
            prefs.exportBackupData().copy(
                songReachability = mapOf(
                    "imported_stale" to AppPreferences.ReachabilityEntry(
                        result = "TIMEOUT",
                        checkedAt = System.currentTimeMillis() - AppPreferences.songReachabilityWindowMs - 1000
                    ),
                    "imported_fresh" to AppPreferences.ReachabilityEntry(
                        result = "NOT_FOUND",
                        checkedAt = System.currentTimeMillis()
                    )
                )
            )
        )
        settle()
        val exported = prefs.exportBackupData()
        // 过期条目被过滤，新条目保留
        assertTrue(!exported.songReachability.containsKey("imported_stale"))
        assertEquals("NOT_FOUND", exported.songReachability["imported_fresh"]?.result)
    }

    @Test
    fun `replace song in playlist swaps stub by id`() = runBlocking {
        val playlist = prefs.createLocalPlaylist("替换测试")
        settle()
        val stub = Song(
            id = "imported_stub_1",
            title = "待补全",
            artist = "未知"
        )
        prefs.addSongToPlaylist(playlist.id, stub)
        settle()
        val enriched = Song(
            id = "ntwk_meting_123",
            title = "待补全",
            artist = "真实歌手",
            isNetworkSong = true,
            networkSource = "meting",
            networkId = "123"
        )
        prefs.replaceSongInPlaylist(playlist.id, stub.id, enriched)
        settle()

        val updated = prefs.getLocalPlaylists().first { it.id == playlist.id }
        assertEquals(1, updated.songs.size)
        assertEquals("ntwk_meting_123", updated.songs.first().id)
        assertEquals("真实歌手", updated.songs.first().artist)
    }

    @Test
    fun `replace song with missing old id is ignored`() = runBlocking {
        val playlist = prefs.createLocalPlaylist("忽略测试")
        settle()
        prefs.replaceSongInPlaylist(
            playlist.id,
            "not_exists",
            Song(id = "new_id", title = "x")
        )
        settle()
        val updated = prefs.getLocalPlaylists().first { it.id == playlist.id }
        assertTrue(updated.songs.isEmpty())
    }
}
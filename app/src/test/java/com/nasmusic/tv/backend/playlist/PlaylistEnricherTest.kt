package com.nasmusic.tv.backend.playlist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放触发补全链测试（docs/playlist-import-feature-plan.md §6 Enricher 行 / §4.3）。
 * 走真实 AppPreferences（datastore），BackendRegistry / NetworkMusicManager /
 * UrlReachabilityChecker 用 mockito-inline mock（final 类）。
 *
 * 覆盖：非 stub 不动、NAS 精确匹配优先、网络 fallback（artist 空退化）、
 * enrichAndPersist(Everywhere) 写回、onPlaybackFailure 三分支（可达不动 /
 * 真不可达补全 / 暂时不可达标 Unreachable）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistEnricherTest {

    private lateinit var prefs: AppPreferences
    private lateinit var backendRegistry: BackendRegistry
    private lateinit var adapter: BackendAdapter
    private lateinit var networkMusicManager: NetworkMusicManager
    private lateinit var enricher: PlaylistEnricher

    private fun stubSong(title: String, artist: String = "", url: String? = null) = Song(
        id = "imported_${title.hashCode().toUInt()}",
        title = title,
        artist = artist,
        streamUrl = url
    )

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext
        prefs = AppPreferences(app)
        backendRegistry = mock(BackendRegistry::class.java)
        adapter = mock(BackendAdapter::class.java)
        networkMusicManager = mock(NetworkMusicManager::class.java)
        `when`(backendRegistry.getAdapter()).thenReturn(adapter)
        enricher = PlaylistEnricher(backendRegistry, networkMusicManager, prefs)
    }

    // ---------- enrichSong：非 stub 不动 ----------

    @Test
    fun `non-imported song returns null without touching backends`() = runBlocking {
        val regular = Song(id = "nf-123", title = "海阔天空", artist = "Beyond")
        assertNull(enricher.enrichSong(regular))
    }

    // ---------- enrichSong：NAS 精确匹配优先 ----------

    @Test
    fun `NAS exact match returns NAS song with streamUrl`() = runBlocking {
        val nasHit = Song(
            id = "nas-1", title = "海阔天空", artist = "Beyond",
            album = "乐与怒", streamUrl = "http://nas/audio/1.mp3"
        )
        `when`(adapter.searchSongs(anyString())).thenReturn(listOf(nasHit))

        val result = enricher.enrichSong(stubSong("海阔天空", "Beyond"))

        assertEquals(nasHit, result)                       // NAS 命中保留 streamUrl
        assertEquals("http://nas/audio/1.mp3", result?.streamUrl)
    }

    @Test
    fun `NAS result with different normalizeKey is ignored`() = runBlocking {
        val nasHit = Song(id = "nas-2", title = "海阔天空", artist = "王菲")  // 同标题不同歌手
        `when`(adapter.searchSongs(anyString())).thenReturn(listOf(nasHit))
        `when`(networkMusicManager.search(anyString())).thenReturn(emptyList())

        assertNull(enricher.enrichSong(stubSong("海阔天空", "Beyond")))
    }

    @Test
    fun `NAS search error falls through to network`() = runBlocking {
        `when`(adapter.searchSongs(anyString())).thenThrow(RuntimeException("nas down"))
        val netHit = Song(
            id = "ntwk_meting_1", title = "光辉岁月", artist = "Beyond",
            isNetworkSong = true, networkSource = "meting", streamUrl = "http://cdn/x.mp3"
        )
        `when`(networkMusicManager.search(anyString())).thenReturn(listOf(netHit))

        val result = enricher.enrichSong(stubSong("光辉岁月", "Beyond"))

        assertEquals("ntwk_meting_1", result?.id)
        assertNull(result?.streamUrl)                       // 网络命中 streamUrl 置空（播放时解析）
        assertTrue(result?.isNetworkSong == true)
    }

    // ---------- enrichSong：网络 fallback（含 artist 空退化 §4.1.7 (6)） ----------

    @Test
    fun `network fallback exact title plus artist match`() = runBlocking {
        `when`(adapter.searchSongs(anyString())).thenReturn(emptyList())
        val netHit = Song(
            id = "ntwk_meting_2", title = "真的爱你", artist = "Beyond",
            isNetworkSong = true, networkSource = "meting"
        )
        `when`(networkMusicManager.search(anyString())).thenReturn(listOf(netHit))

        val result = enricher.enrichSong(stubSong("真的爱你", "Beyond"))

        assertEquals("ntwk_meting_2", result?.id)
        assertNull(result?.streamUrl)
        assertTrue(result?.isNetworkSong == true)
    }

    @Test
    fun `network fallback ignores same title different artist`() = runBlocking {
        `when`(adapter.searchSongs(anyString())).thenReturn(emptyList())
        val netHit = Song(
            id = "ntwk_meting_3", title = "真的爱你", artist = "张学友",
            isNetworkSong = true, networkSource = "meting"
        )
        `when`(networkMusicManager.search(anyString())).thenReturn(listOf(netHit))

        assertNull(enricher.enrichSong(stubSong("真的爱你", "Beyond")))
    }

    @Test
    fun `blank artist prefers exact title match over top1`() = runBlocking {
        `when`(adapter.searchSongs(anyString())).thenReturn(emptyList())
        val wrong = Song(id = "ntwk_meting_a", title = "晴天", artist = "其他", isNetworkSong = true)
        val exact = Song(id = "ntwk_meting_b", title = "海阔天空", artist = "Beyond", isNetworkSong = true)
        `when`(networkMusicManager.search(anyString())).thenReturn(listOf(wrong, exact))

        val result = enricher.enrichSong(stubSong("海阔天空"))   // artist 为空

        assertEquals("ntwk_meting_b", result?.id)               // title 精确匹配优先
        assertNull(result?.streamUrl)
    }

    @Test
    fun `blank artist falls back to top1 when no exact title match`() = runBlocking {
        `when`(adapter.searchSongs(anyString())).thenReturn(emptyList())
        val hit = Song(id = "ntwk_meting_c", title = "夜曲", artist = "周杰伦", isNetworkSong = true)
        `when`(networkMusicManager.search(anyString())).thenReturn(listOf(hit))

        val result = enricher.enrichSong(stubSong("夜曲"))       // artist 为空

        assertEquals("ntwk_meting_c", result?.id)
    }

    // ---------- enrichAndPersist(Everywhere) 写回 ----------

    @Test
    fun `enrichAndPersist replaces stub inside playlist`() = runBlocking {
        val stub = stubSong("冷雨夜", "Beyond")
        val playlist = prefs.createLocalPlaylist("测试歌单")
        prefs.addSongToPlaylist(playlist.id, stub)

        val nasHit = Song(id = "nas-3", title = "冷雨夜", artist = "Beyond", streamUrl = "http://nas/3.mp3")
        `when`(adapter.searchSongs(anyString())).thenReturn(listOf(nasHit))

        val replaced = enricher.enrichAndPersist(playlist.id, stub)

        assertTrue(replaced)
        val song = prefs.getLocalPlaylists().first { it.id == playlist.id }.songs.single()
        assertEquals("nas-3", song.id)
        assertEquals("http://nas/3.mp3", song.streamUrl)
    }

    @Test
    fun `enrichAndPersist miss leaves playlist untouched`() = runBlocking {
        val stub = stubSong("不在库里的歌", "某人")
        val playlist = prefs.createLocalPlaylist("测试歌单")
        prefs.addSongToPlaylist(playlist.id, stub)
        `when`(adapter.searchSongs(anyString())).thenReturn(emptyList())
        `when`(networkMusicManager.search(anyString())).thenReturn(emptyList())

        val replaced = enricher.enrichAndPersist(playlist.id, stub)

        assertFalse(replaced)
        assertEquals(stub.id, prefs.getLocalPlaylists().first { it.id == playlist.id }.songs.single().id)
    }

    @Test
    fun `enrichAndPersistEverywhere replaces stub in all playlists`() = runBlocking {
        val stub = stubSong("喜欢你", "Beyond")
        val p1 = prefs.createLocalPlaylist("歌单一")
        val p2 = prefs.createLocalPlaylist("歌单二")
        prefs.addSongToPlaylist(p1.id, stub)
        prefs.addSongToPlaylist(p2.id, stub.copy(id = "imported_other"))  // 另一个 stub 不受影响

        val nasHit = Song(id = "nas-4", title = "喜欢你", artist = "Beyond", streamUrl = "http://nas/4.mp3")
        `when`(adapter.searchSongs(anyString())).thenReturn(listOf(nasHit))

        val replaced = enricher.enrichAndPersistEverywhere(stub)

        assertTrue(replaced)
        val s1 = prefs.getLocalPlaylists().first { it.id == p1.id }.songs.single()
        val s2 = prefs.getLocalPlaylists().first { it.id == p2.id }.songs.single()
        assertEquals("nas-4", s1.id)
        assertEquals("imported_other", s2.id)               // 无关 stub 不动
    }

    // ---------- onPlaybackFailure 三分支（§4.1.8 (5) 算法 B） ----------

    @Test
    fun `playback failure reachable leaves song unchanged`() = runBlocking {
        val stub = stubSong("海阔天空", "Beyond", url = "http://example.com/a.mp3")
        val checker = mock(UrlReachabilityChecker::class.java)
        `when`(checker.check("http://example.com/a.mp3"))
            .thenReturn(UrlReachabilityChecker.Result.REACHABLE to null)

        assertFalse(enricher.onPlaybackFailure(stub, checker))
        assertTrue(prefs.getSongReachability().isEmpty())    // 可达不动，也不标 Unreachable
    }

    @Test
    fun `playback failure timeout marks unreachable without enriching`() = runBlocking {
        val stub = stubSong("海阔天空", "Beyond", url = "http://example.com/a.mp3")
        val checker = mock(UrlReachabilityChecker::class.java)
        `when`(checker.check("http://example.com/a.mp3"))
            .thenReturn(UrlReachabilityChecker.Result.TIMEOUT to null)
        `when`(adapter.searchSongs(anyString())).thenReturn(emptyList())
        `when`(networkMusicManager.search(anyString())).thenReturn(emptyList())

        assertFalse(enricher.onPlaybackFailure(stub, checker))

        val reachability = prefs.getSongReachability()
        assertEquals("TIMEOUT", reachability[stub.id]?.result)   // 暂时不可达 → 标记 Unreachable
    }

    @Test
    fun `playback failure not-found triggers enrich and replaces`() = runBlocking {
        val stub = stubSong("谁伴我闯荡", "Beyond", url = "http://example.com/gone.mp3")
        val playlist = prefs.createLocalPlaylist("测试歌单")
        prefs.addSongToPlaylist(playlist.id, stub)

        val checker = mock(UrlReachabilityChecker::class.java)
        `when`(checker.check("http://example.com/gone.mp3"))
            .thenReturn(UrlReachabilityChecker.Result.NOT_FOUND to null)
        val nasHit = Song(id = "nas-5", title = "谁伴我闯荡", artist = "Beyond", streamUrl = "http://nas/5.mp3")
        `when`(adapter.searchSongs(anyString())).thenReturn(listOf(nasHit))

        val changed = enricher.onPlaybackFailure(stub, checker)

        assertTrue(changed)                                     // 真不可达 → 补全写回
        assertEquals("nas-5", prefs.getLocalPlaylists().first { it.id == playlist.id }.songs.single().id)
        assertTrue(prefs.getSongReachability().isEmpty())       // 已替换，不残留 Unreachable 标记
    }

    @Test
    fun `playback failure non-imported song is ignored`() = runBlocking {
        val regular = Song(id = "nf-9", title = "海阔天空", artist = "Beyond", streamUrl = "http://example.com/x.mp3")
        val checker = mock(UrlReachabilityChecker::class.java)

        assertFalse(enricher.onPlaybackFailure(regular, checker))
        assertTrue(prefs.getSongReachability().isEmpty())
    }

    @Test
    fun `playback failure non-http streamUrl is ignored`() = runBlocking {
        val stub = stubSong("本地路径", url = "/storage/emulated/0/Music/a.mp3")
        val checker = mock(UrlReachabilityChecker::class.java)

        assertFalse(enricher.onPlaybackFailure(stub, checker))
        assertTrue(prefs.getSongReachability().isEmpty())
    }
}
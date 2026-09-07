package com.nasmusic.tv.backend.download

import com.nasmusic.tv.data.model.Song
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 单元测试：DownloadPathBuilder
 *
 * 覆盖：
 * - sanitize()：非法字符、空白折叠、80 字符截断、首尾 trim
 * - extOf()：URL 扩展名推断、path 扩展名推断、默认 mp3
 * - build()：路径结构、艺术家/专辑/标题默认值、曲目号前缀
 * - cleanupEmptyDirs()：空目录回收
 */
class DownloadPathBuilderTest {

    private lateinit var tmpRoot: File
    private lateinit var builder: DownloadPathBuilder

    @Before
    fun setup() {
        tmpRoot = File(System.getProperty("java.io.tmpdir"), "download_test_${System.nanoTime()}")
        tmpRoot.mkdirs()
        builder = DownloadPathBuilder { tmpRoot }
    }

    // ── sanitize() ──────────────────────────────────────────────

    @Test
    fun `sanitize removes illegal characters`() {
        val result = DownloadPathBuilder.sanitize("hello\\/:*?\"<>|world")
        assertEquals("hello_________world", result)
    }

    @Test
    fun `sanitize collapses whitespace`() {
        // Note: \t (\u0009) is in the ILLEGAL regex range (\u0000-\u001F), so it becomes '_'
        val result = DownloadPathBuilder.sanitize("hello   world  \t foo")
        assertEquals("hello world _ foo", result)
    }

    @Test
    fun `sanitize trims leading and trailing whitespace`() {
        val result = DownloadPathBuilder.sanitize("  hello  ")
        assertEquals("hello", result)
    }

    @Test
    fun `sanitize trims trailing dots`() {
        val result = DownloadPathBuilder.sanitize("hello...")
        assertEquals("hello", result)
    }

    @Test
    fun `sanitize truncates at 80 characters`() {
        val long = "a".repeat(100)
        val result = DownloadPathBuilder.sanitize(long)
        assertEquals(80, result.length)
    }

    @Test
    fun `sanitize preserves 80 character string`() {
        val exact = "a".repeat(80)
        val result = DownloadPathBuilder.sanitize(exact)
        assertEquals(80, result.length)
    }

    @Test
    fun `sanitize returns blank for empty input`() {
        assertEquals("", DownloadPathBuilder.sanitize(""))
        assertEquals("", DownloadPathBuilder.sanitize("   "))
    }

    // ── extOf() ──────────────────────────────────────────────

    @Test
    fun `extOf detects mp3 from URL`() {
        val song = Song(id = "1", title = "test")
        assertEquals("mp3", builder.extOf("https://example.com/song.mp3", song))
    }

    @Test
    fun `extOf detects flac from URL`() {
        val song = Song(id = "1", title = "test")
        assertEquals("flac", builder.extOf("https://example.com/song.flac?token=abc", song))
    }

    @Test
    fun `extOf detects m4a from URL`() {
        val song = Song(id = "1", title = "test")
        assertEquals("m4a", builder.extOf("https://example.com/song.m4a", song))
    }

    @Test
    fun `extOf falls back to song path for flac`() {
        val song = Song(id = "1", title = "test", path = "/music/song.flac")
        assertEquals("flac", builder.extOf("https://example.com/song?id=123", song))
    }

    @Test
    fun `extOf falls back to song path for m4a`() {
        val song = Song(id = "1", title = "test", path = "/music/song.m4a")
        assertEquals("m4a", builder.extOf("https://example.com/song?id=123", song))
    }

    @Test
    fun `extOf defaults to mp3 when no extension found`() {
        val song = Song(id = "1", title = "test")
        assertEquals("mp3", builder.extOf("https://example.com/stream", song))
    }

    @Test
    fun `extOf detects wav from URL`() {
        val song = Song(id = "1", title = "test")
        assertEquals("wav", builder.extOf("https://example.com/song.wav", song))
    }

    @Test
    fun `extOf detects ogg from URL`() {
        val song = Song(id = "1", title = "test")
        assertEquals("ogg", builder.extOf("https://example.com/song.ogg", song))
    }

    // ── build() ──────────────────────────────────────────────

    @Test
    fun `build creates correct directory structure`() {
        val song = Song(
            id = "1",
            title = "Test Song",
            artist = "周杰伦",
            album = "范特西",
            trackNumber = 3
        )
        val paths = builder.build(song, "mp3")

        assertEquals(File(tmpRoot, "周杰伦"), paths.artistDir)
        assertEquals(File(tmpRoot, "周杰伦/范特西"), paths.albumDir)
        assertEquals("03 - Test Song", paths.baseName)
        assertEquals(File(tmpRoot, "周杰伦/范特西/03 - Test Song.mp3"), paths.finalFile)
        assertTrue(paths.tmpFile.absolutePath.contains(DownloadPathBuilder.TMP_DIR))
    }

    @Test
    fun `build uses default album when album is blank`() {
        val song = Song(id = "1", title = "Test", artist = "Artist", album = "")
        val paths = builder.build(song, "mp3")
        assertEquals(DownloadPathBuilder.DEFAULT_ALBUM, paths.albumDir.name)
    }

    @Test
    fun `build uses default artist when artist is blank`() {
        val song = Song(id = "1", title = "Test", artist = "", album = "Album")
        val paths = builder.build(song, "mp3")
        assertEquals("未知歌手", paths.artistDir.name)
    }

    @Test
    fun `build uses default title when title is blank`() {
        val song = Song(id = "1", title = "", artist = "Artist", album = "Album")
        val paths = builder.build(song, "mp3")
        assertEquals("未命名", paths.baseName)
    }

    @Test
    fun `build omits track number prefix when trackNumber is 0`() {
        val song = Song(id = "1", title = "Song", artist = "A", album = "B", trackNumber = 0)
        val paths = builder.build(song, "mp3")
        assertEquals("Song", paths.baseName)
    }

    @Test
    fun `build formats track number with zero padding`() {
        val song = Song(id = "1", title = "Song", artist = "A", album = "B", trackNumber = 5)
        val paths = builder.build(song, "mp3")
        assertEquals("05 - Song", paths.baseName)
    }

    @Test
    fun `build sanitizes illegal chars in artist`() {
        // ArtistSplitter.split("A/B") splits on '/' → ["A", "B"], firstOrNull() = "A"
        val song = Song(id = "1", title = "T", artist = "A/B", album = "Al")
        val paths = builder.build(song, "mp3")
        assertEquals("A", paths.artistDir.name)
    }

    @Test
    fun `build sanitizes illegal chars in title`() {
        val song = Song(id = "1", title = "T:R", artist = "A", album = "Al")
        val paths = builder.build(song, "mp3")
        assertEquals("T_R", paths.baseName)
    }

    @Test
    fun `build splits multi-artist and uses first`() {
        val song = Song(id = "1", title = "T", artist = "周杰伦 feat. 杨瑞代", album = "Al")
        val paths = builder.build(song, "mp3")
        assertEquals("周杰伦", paths.artistDir.name)
    }

    // ── buildFinalPath() ──────────────────────────────────────

    @Test
    fun `buildFinalPath returns correct path`() {
        val song = Song(id = "1", title = "Song", artist = "A", album = "B", trackNumber = 1)
        val path = builder.buildFinalPath(song, "flac")
        assertEquals(File(tmpRoot, "A/B/01 - Song.flac"), path)
    }

    // ── cleanupEmptyDirs() ──────────────────────────────────────

    @Test
    fun `cleanupEmptyDirs removes empty album and artist dirs`() {
        val artistDir = File(tmpRoot, "Artist")
        val albumDir = File(artistDir, "Album")
        albumDir.mkdirs()

        builder.cleanupEmptyDirs(artistDir, albumDir)

        assertFalse(albumDir.exists())
        assertFalse(artistDir.exists())
    }

    @Test
    fun `cleanupEmptyDirs does not remove non-empty dirs`() {
        val artistDir = File(tmpRoot, "Artist")
        val albumDir = File(artistDir, "Album")
        albumDir.mkdirs()
        File(albumDir, "song.mp3").createNewFile()

        builder.cleanupEmptyDirs(artistDir, albumDir)

        assertTrue(albumDir.exists())
        assertTrue(artistDir.exists())
    }

    @Test
    fun `cleanupEmptyDirs handles non-existent dirs gracefully`() {
        val artistDir = File(tmpRoot, "NonExistent")
        val albumDir = File(artistDir, "Album")
        // Should not throw
        builder.cleanupEmptyDirs(artistDir, albumDir)
    }
}

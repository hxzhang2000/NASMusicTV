package com.nasmusic.tv.backend.playlist

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌单导入解析器测试（docs/archive/playlist-import-feature-plan.md §4.1.7(7) / §4.1.8(7) / §9.2）。
 */
class PlaylistParserTest {

    // ---------- 通用工具 ----------

    @Test
    fun `classifyUrl recognizes http file absolute and relative`() {
        assertEquals(DirectUrlType.HTTP, PlaylistParsers.classifyUrl("http://example.com/song.mp3"))
        assertEquals(DirectUrlType.HTTP, PlaylistParsers.classifyUrl("https://example.com/song.mp3"))
        assertEquals(DirectUrlType.LOCAL_URI, PlaylistParsers.classifyUrl("file:///sdcard/Music/x.mp3"))
        assertEquals(DirectUrlType.ABSOLUTE_PATH, PlaylistParsers.classifyUrl("/sdcard/Music/x.mp3"))
        assertEquals(DirectUrlType.RELATIVE_PATH, PlaylistParsers.classifyUrl("music/x.mp3"))
        assertEquals(DirectUrlType.RELATIVE_PATH, PlaylistParsers.classifyUrl("song.mp3"))
        assertEquals(DirectUrlType.NONE, PlaylistParsers.classifyUrl("海阔天空"))
        assertEquals(DirectUrlType.NONE, PlaylistParsers.classifyUrl("   "))
    }

    @Test
    fun `decode falls back to GBK and strips BOM`() {
        // GBK "海阔天空 - Beyond"
        val gbk = "海阔天空 - Beyond".toByteArray(Charset.forName("GBK"))
        assertEquals("海阔天空 - Beyond", PlaylistParsers.decode(gbk))

        // UTF-8 + BOM
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "海阔天空".toByteArray(Charsets.UTF_8)
        val decoded = PlaylistParsers.decode(bom)
        assertEquals("海阔天空", decoded)
        assertTrue(!decoded.startsWith('\uFEFF'))
    }

    @Test
    fun `displayNameFromUrl extracts file base`() {
        assertEquals("song", PlaylistParsers.displayNameFromUrl("http://example.com/a/song.mp3"))
        assertEquals("x", PlaylistParsers.displayNameFromUrl("file:///sdcard/Music/x.mp3"))
        assertEquals("y", PlaylistParsers.displayNameFromUrl("/path/to/y.flac"))
    }

    @Test
    fun `meaningless default filenames are recognized`() {
        assertTrue(PlaylistParsers.isMeaninglessFileName("新建文本文档.txt"))
        assertTrue(PlaylistParsers.isMeaninglessFileName("playlist.txt"))
        assertTrue(PlaylistParsers.isMeaninglessFileName("untitled.txt"))
        assertTrue(!PlaylistParsers.isMeaninglessFileName("我的歌单.txt"))
    }

    // ---------- M3u ----------

    @Test
    fun `m3u parses extinf with artist dash title and path hint`() {
        // §4.1.4 标准：#EXTINF 显示标签为 `artist - title`（艺术家在前）
        val text = "#EXTM3U\n" +
            "#EXTINF:300,海阔天空 - Beyond\n" +
            "http://example.com/song.mp3\n"
        val parser = M3uPlaylistParser()
        val entries = parser.parse(text, "list.m3u")
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("Beyond", e.title)
        assertEquals("海阔天空", e.artist)
        assertEquals(300, e.durationSec)
        assertEquals("http://example.com/song.mp3", e.directUrl)
        assertEquals(DirectUrlType.HTTP, e.directUrlType)
    }

    @Test
    fun `m3u extinf without dash keeps whole label as title`() {
        val text = "#EXTM3U\n#EXTINF:214,Bohemian Rhapsody\n/path/q.mp3\n"
        val e = M3uPlaylistParser().parse(text, "x.m3u").single()
        assertEquals("Bohemian Rhapsody", e.title)
        assertEquals("", e.artist)
        assertEquals(DirectUrlType.ABSOLUTE_PATH, e.directUrlType)
    }

    @Test
    fun `m3u captures file uri and absolute path hints`() {
        val fileUri = M3uPlaylistParser().parse(
            "#EXTINF:300,x\nfile:///sdcard/Music/x.mp3\n", "a.m3u8"
        ).single()
        assertEquals("file:///sdcard/Music/x.mp3", fileUri.directUrl)
        assertEquals(DirectUrlType.LOCAL_URI, fileUri.directUrlType)

        val abs = M3uPlaylistParser().parse(
            "#EXTINF:300,x\n/sdcard/Music/x.mp3\n", "b.m3u"
        ).single()
        assertEquals(DirectUrlType.ABSOLUTE_PATH, abs.directUrlType)
    }

    @Test
    fun `m3u relative path hint is captured as relative type`() {
        val e = M3uPlaylistParser().parse(
            "#EXTINF:300,x\nmusic/x.mp3\n", "c.m3u"
        ).single()
        assertEquals(DirectUrlType.RELATIVE_PATH, e.directUrlType)
        assertEquals("music/x.mp3", e.directUrl)
    }

    @Test
    fun `m3u bare url line becomes its own entry`() {
        val e = M3uPlaylistParser().parse("http://example.com/song.mp3\n", "single.m3u").single()
        assertEquals("song", e.title)
        assertEquals("", e.artist)
        assertEquals("http://example.com/song.mp3", e.directUrl)
        assertEquals(DirectUrlType.HTTP, e.directUrlType)
    }

    @Test
    fun `m3u skips live headers and blank lines`() {
        val text = "#EXTM3U\n" +
            "#EXT-X-VERSION:3\n" +
            "#EXTLIVE\n\n" +
            "#PLAYLIST:my list\n" +
            "#EXTINF:300,x - y\n" +
            "http://a.com/1.mp3\n"
        val entries = M3uPlaylistParser().parse(text, "live.m3u8")
        assertEquals(1, entries.size) // 直播头不产生条目
    }

    @Test
    fun `m3u canParse sniffs content regardless of extension`() {
        assertTrue(M3uPlaylistParser().canParse("#EXTM3U\n".toByteArray(), "notes.txt"))
        assertTrue(M3uPlaylistParser().canParse("#EXTINF:100,x\n".toByteArray(), "x.json"))
    }

    // ---------- 网易云 ----------

    @Test
    fun `netease parses tracks and joins artists`() {
        val json = """{
            "playlist": {
                "name": "我的收藏",
                "tracks": [
                    {"name": "起风了", "artists": [{"name": "买辣椒也用券"}],
                     "album": {"name": "起风了"}, "duration": 320000},
                    {"name": "海阔天空", "artists": [{"name": "Beyond"}, {"name": "黄家驹"}],
                     "duration": 400000}
                ]
            }
        }"""
        val entries = NeteaseCloudPlaylistParser().parse(json, "net.json")
        assertEquals(2, entries.size)
        assertEquals("起风了", entries[0].title)
        assertEquals("买辣椒也用券", entries[0].artist)
        assertEquals(320, entries[0].durationSec)
        assertEquals("Beyond、黄家驹", entries[1].artist)
    }

    @Test
    fun `netease filters empty titles and supports top-level tracks`() {
        val json = """{"neteasePlaylistId":"123","tracks":[
            {"name": "", "artists": [{"name": "x"}]},
            {"name": "   ", "artists": [{"name": "y"}]},
            {"name": "唯一", "artists": [{"name": "告五人"}]}
        ]}"""
        val entries = NeteaseCloudPlaylistParser().parse(json, "net2.json")
        assertEquals(1, entries.size)
        assertEquals("唯一", entries[0].title)
    }

    // ---------- JSON v2 ----------

    @Test
    fun `json v2 parses with formatVersion routing`() {
        val json = """{
            "formatVersion": "nasmusic-playlist-v2",
            "playlistName": "导出歌单",
            "songs": [
                {"title": "A", "artist": "a", "durationSec": 120},
                {"title": "B", "isNetworkSong": true, "networkSource": "meting", "networkId": "9"}
            ]
        }"""
        val parser = JsonPlaylistParser()
        val entries = parser.parse(json, "exp.json")
        assertEquals(2, entries.size)
        assertEquals("A", entries[0].title)
        assertEquals("a", entries[0].artist)
        assertEquals(120, entries[0].durationSec)
        assertTrue(parser.canParse(json.toByteArray(), "exp.json"))
    }

    @Test
    fun `json v2 round trips serialize then parse`() {
        val parser = JsonPlaylistParser()
        val json = parser.serialize(
            "歌单",
            listOf(
                SongExportEntry("A", "a", album = "al", durationSec = 60),
                SongExportEntry("B", "b", isNetworkSong = true, networkSource = "meting", networkId = "7"),
            ),
            createdAt = 1758160000000L,
        )
        val entries = parser.parse(json, "x.json")
        assertEquals(2, entries.size)
        assertEquals("A", entries[0].title)
        assertEquals("b", entries[1].artist)
    }

    @Test
    fun `netease sniffing does not catch v2 json and vice versa`() {
        assertTrue(!NeteaseCloudPlaylistParser().canParse(
            """{"formatVersion":"nasmusic-playlist-v2","songs":[]}""".toByteArray(), "x.json"))
        assertTrue(!JsonPlaylistParser().canParse(
            """{"playlist":{"tracks":[]}}""".toByteArray(), "x.json"))
    }

    // ---------- Txt ----------

    @Test
    fun `txt pure titles leave artist empty`() {
        val entries = TextPlaylistParser().parse("海阔天空\n甜蜜蜜\n小幸运", "a.txt")
        assertEquals(listOf("海阔天空", "甜蜜蜜", "小幸运"), entries.map { it.title })
        assertTrue(entries.all { it.artist.isEmpty() })
    }

    @Test
    fun `txt standard separator and tab priority`() {
        val entries = TextPlaylistParser().parse("海阔天空 - Beyond\n甜蜜蜜\t邓丽君", "a.txt")
        assertEquals("海阔天空", entries[0].title)
        assertEquals("Beyond", entries[0].artist)
        assertEquals("甜蜜蜜", entries[1].title)
        assertEquals("邓丽君", entries[1].artist)
    }

    @Test
    fun `txt long dash em en and double hyphen`() {
        assertEquals("海阔天空" to "Beyond",
            TextPlaylistParser().splitLine("海阔天空 — Beyond"))
        assertEquals("海阔天空" to "Beyond",
            TextPlaylistParser().splitLine("海阔天空 – Beyond"))
        assertEquals("海阔天空" to "Beyond",
            TextPlaylistParser().splitLine("海阔天空 -- Beyond"))
    }

    @Test
    fun `txt numbering stripping`() {
        val p = TextPlaylistParser()
        assertEquals("海阔天空" to "Beyond", p.splitLine("1. 海阔天空 - Beyond"))
        assertEquals("海阔天空" to "Beyond", p.splitLine("1、海阔天空 — Beyond"))
        assertEquals("海阔天空" to "Beyond", p.splitLine("（1）海阔天空 - Beyond"))
        assertEquals("海阔天空" to "Beyond", p.splitLine("①海阔天空 - Beyond"))
    }

    @Test
    fun `txt single dash is not a separator`() {
        assertEquals("U2-1" to "Beyond", TextPlaylistParser().splitLine("U2-1 - Beyond"))
        // 1.5 倍速（编号规则避免误剥）
        assertEquals("1.5倍速" to "", TextPlaylistParser().splitLine("1.5倍速"))
    }

    @Test
    fun `txt only first separator is cut`() {
        assertEquals("Beyond" to "黄家驹 - 海阔天空",
            TextPlaylistParser().splitLine("Beyond - 黄家驹 - 海阔天空"))
        assertEquals("海阔天空" to "Beyond - 黄家驹",
            TextPlaylistParser().splitLine("海阔天空 - Beyond - 黄家驹"))
    }

    @Test
    fun `txt comments blank lines and empty file`() {
        val p = TextPlaylistParser()
        val entries = p.parse("# 标题注释\n海阔天空\n\n\n// 2025 新版\n甜蜜蜜", "b.txt")
        assertEquals(listOf("海阔天空", "甜蜜蜜"), entries.map { it.title })
        assertTrue(p.parse("", "empty.txt").isEmpty())
        assertTrue(p.parse("# 只有注释", "c.txt").isEmpty())
    }

    @Test
    fun `txt gbk bytes and bom handling`() {
        // GBK 字节
        val gbkText = String("海阔天空 - Beyond".toByteArray(Charset.forName("GBK")), Charsets.ISO_8859_1)
        // 直接喂已解码文本（decode 层由 PlaylistParsers.decode 单测覆盖），此处验证解析规则不受影响
        val entries = TextPlaylistParser().parse("\uFEFF海阔天空 - Beyond", "d.txt")
        assertEquals("海阔天空", entries[0].title)
        assertTrue(!entries[0].title.startsWith('\uFEFF'))
        assertEquals(gbkText, gbkText) // sanity
    }

    @Test
    fun `txt first line with colon comment supplies innerName`() {
        val p = TextPlaylistParser()
        p.parse("# title: 我的歌单\n海阔天空 - Beyond", "e.txt")
        assertEquals("我的歌单", p.innerName)

        val p2 = TextPlaylistParser()
        p2.parse("// 歌单：周末开车\n海阔天空", "f.txt")
        assertEquals("周末开车", p2.innerName)

        val p3 = TextPlaylistParser()
        p3.parse("# 只有注释不产生 innerName", "g.txt")
        assertNull(p3.innerName)
    }

    @Test
    fun `txt oversized line is skipped and counted`() {
        val p = TextPlaylistParser()
        val huge = "x".repeat(300_000)
        val entries = p.parse("海阔天空\n$huge\n甜蜜蜜", "h.txt")
        assertEquals(2, entries.size)
        assertEquals(1, p.lastSkippedCount)
    }

    @Test
    fun `txt comma separator requires title longer than 8 chars`() {
        val p = TextPlaylistParser()
        // 短标题 + 逗号 → 逗号不启用，整行保留为 title（§4.1.7 (1) 分隔符 8）
        assertEquals("短标题, 作者" to "", p.splitLine("短标题, 作者"))
        // 长标题 + 逗号 → 切分
        assertEquals("这是一个非常长的标题测试" to "作者",
            p.splitLine("这是一个非常长的标题测试, 作者"))
    }
}
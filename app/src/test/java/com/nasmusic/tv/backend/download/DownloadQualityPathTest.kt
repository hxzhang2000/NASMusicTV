package com.nasmusic.tv.backend.download

import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 档位感知的扩展名与文件名测试（多码率方案 §10.1 / §4.3）。
 *
 * 关键点：`extOf` / `baseNameWithQuality` 的参数是**实际解析命中的档位**
 * （`ResolveResult.actualQuality`），不是用户请求的档位。
 */
class DownloadQualityPathTest {

    private val builder = DownloadPathBuilder { File(System.getProperty("java.io.tmpdir")) }

    private fun song(trackNumber: Int = 3) = Song(
        id = "ntwk_meting_1",
        title = "南方姑娘",
        artist = "赵雷",
        album = "理想国",
        trackNumber = trackNumber,
        isNetworkSong = true,
        networkSource = "meting",
        networkId = "1"
    )

    // ── extOf ──────────────────────────────────────────────

    @Test
    fun `无损档强制 flac 即使 URL 无扩展名`() {
        // 无损直链常无 .flac 后缀，靠 URL 猜会存成 .mp3 容器
        assertEquals("flac", builder.extOf("https://x.com/stream?id=1", song(), QualityTiers.LOSSLESS))
    }

    @Test
    fun `320 档为 mp3`() {
        assertEquals("mp3", builder.extOf("https://x.com/a.flac", song(), QualityTiers.HIGH))
    }

    @Test
    fun `192 档为 mp3`() {
        assertEquals("mp3", builder.extOf("https://x.com/a", song(), QualityTiers.GOOD))
    }

    @Test
    fun `128 档为 mp3`() {
        assertEquals("mp3", builder.extOf("https://x.com/a", song(), QualityTiers.STANDARD))
    }

    @Test
    fun `AUTO 档沿用 URL 后缀判定`() {
        assertEquals("flac", builder.extOf("https://x.com/a.flac", song(), QualityTiers.AUTO))
        assertEquals("mp3", builder.extOf("https://x.com/a.mp3", song(), QualityTiers.AUTO))
    }

    @Test
    fun `AUTO 档 URL 无扩展名时回退默认 mp3`() {
        assertEquals("mp3", builder.extOf("https://x.com/stream?id=1", song(), QualityTiers.AUTO))
    }

    @Test
    fun `降级场景 请求无损实际 320 落 mp3`() {
        // §4.4：降级后扩展名由**实际**档位决定
        val actual = 320
        assertEquals("mp3", builder.extOf("https://x.com/stream", song(), actual))
    }

    // ── baseNameWithQuality ────────────────────────────────

    @Test
    fun `无损档不加档位后缀`() {
        assertEquals("03 - 南方姑娘", builder.baseNameWithQuality(song(), QualityTiers.LOSSLESS))
    }

    @Test
    fun `320 档追加后缀`() {
        assertEquals("03 - 南方姑娘 (320)", builder.baseNameWithQuality(song(), QualityTiers.HIGH))
    }

    @Test
    fun `192 档追加后缀`() {
        assertEquals("03 - 南方姑娘 (192)", builder.baseNameWithQuality(song(), QualityTiers.GOOD))
    }

    @Test
    fun `128 档追加后缀`() {
        assertEquals("03 - 南方姑娘 (128)", builder.baseNameWithQuality(song(), QualityTiers.STANDARD))
    }

    @Test
    fun `AUTO 档不加后缀`() {
        assertEquals("03 - 南方姑娘", builder.baseNameWithQuality(song(), QualityTiers.AUTO))
    }

    @Test
    fun `无曲目号时不加前缀`() {
        assertEquals("南方姑娘 (320)", builder.baseNameWithQuality(song(trackNumber = 0), QualityTiers.HIGH))
    }

    @Test
    fun `降级场景 请求无损实际 320 文件名含 320 而非 999`() {
        val name = builder.baseNameWithQuality(song(), 320)
        assertTrue("应含 (320)", name.contains("(320)"))
        assertTrue("不应含 999", !name.contains("999"))
    }

    // ── build(quality) ─────────────────────────────────────

    @Test
    fun `同曲两档落盘文件名不同 可共存`() {
        val root = File(System.getProperty("java.io.tmpdir"), "dqp_${System.nanoTime()}")
        root.mkdirs()
        try {
            val b = DownloadPathBuilder { root }
            val flac = b.build(song(), "flac", QualityTiers.LOSSLESS)
            val mp3 = b.build(song(), "mp3", QualityTiers.HIGH)
            assertTrue(flac.finalFile.name.endsWith(".flac"))
            assertTrue(mp3.finalFile.name.endsWith("(320).mp3"))
            assertTrue(flac.finalFile.name != mp3.finalFile.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `档位后缀与 uniqueFile 去重后缀共存`() {
        val root = File(System.getProperty("java.io.tmpdir"), "dqp2_${System.nanoTime()}")
        root.mkdirs()
        try {
            val b = DownloadPathBuilder { root }
            val first = b.build(song(), "mp3", QualityTiers.HIGH)
            // 预置同名文件，模拟"同档重复下载"
            first.finalFile.parentFile?.mkdirs()
            first.finalFile.writeText("x")
            val second = b.build(song(), "mp3", QualityTiers.HIGH)
            assertTrue(
                "应为 03 - 南方姑娘 (320) (2).mp3，实际=${second.finalFile.name}",
                second.finalFile.name.contains("(320)") && second.finalFile.name.contains("(2)")
            )
        } finally {
            root.deleteRecursively()
        }
    }
}

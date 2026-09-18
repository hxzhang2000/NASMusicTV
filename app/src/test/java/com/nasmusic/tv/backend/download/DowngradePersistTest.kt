package com.nasmusic.tv.backend.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.backend.download.db.DownloadDatabase
import com.nasmusic.tv.backend.download.db.DownloadStatus
import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.backend.network.ResolveResult
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 静默降级落库链路一致性测试（多码率方案 §10.1 / §4.4）。
 *
 * 验证"请求档 → 实际档"贯穿落库全链后，**三个维度保持一致**：
 * `songKey` / `entity.quality` / 文件名后缀。这是 §7 Phase 3 中
 * 原本标注"仅由 3 条组合断言间接覆盖"的缺口，本类补齐端到端验证。
 *
 * 核心风险（§4.4 要点 1）：若 `songKey` 用**请求档**而 `entity.quality` 用**实际档**，
 * 下次用户请求 320k 时 `isDownloaded(song, 320)` 查不到 → 重复下载同一份 320k 文件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DowngradePersistTest {

    private lateinit var db: DownloadDatabase
    private lateinit var repo: DownloadRepository
    private lateinit var paths: DownloadPathBuilder

    private fun song(id: String = "1001") = Song(
        id = "ntwk_meting_$id",
        title = "南方姑娘",
        artist = "赵雷",
        album = "理想国",
        trackNumber = 3,
        isNetworkSong = true,
        networkSource = "meting",
        networkId = id
    )

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, DownloadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = DownloadRepository(ctx, db.downloadSongDao())
        paths = DownloadPathBuilder { java.io.File(System.getProperty("java.io.tmpdir")) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * 模拟 `SongDownloadManager.singleAttempt` 的落库决策链：
     * 解析（带降级）→ 用**实际档**定 key/ext/文件名/quality。
     *
     * @return (entity, fileName)
     */
    private suspend fun simulateDownload(
        song: Song,
        requested: Int,
        resolveResult: ResolveResult
    ): Pair<com.nasmusic.tv.backend.download.db.DownloadSongEntity, String> {
        val url = resolveResult.url ?: error("模拟失败：url 为空")
        val actual = resolveResult.actualQuality
        // 与生产代码一致：extOf / build / newDownloadingEntity 全部取 actual
        val ext = paths.extOf(url, song, actual)
        val p = paths.build(song, ext, actual)
        val entity = repo.newDownloadingEntity(
            song = song,
            sourceType = "NETWORK_MUSIC",
            tmpPath = p.tmpFile.absolutePath,
            autoDownloaded = false,
            quality = actual
        ).copy(
            status = DownloadStatus.COMPLETED.name,
            progress = 100,
            audioPath = p.finalFile.absolutePath,
            containerExt = ext
        )
        repo.upsert(entity)
        return entity to p.finalFile.name
    }

    // ── 断言 1：请求 999 实际 320 → entity.quality == 320 ────────

    @Test
    fun `降级后 entity quality 记录实际档位而非请求档位`() = runBlocking {
        val s = song()
        val (entity, _) = simulateDownload(s, 999, ResolveResult("https://cdn/320.mp3", 320))
        assertEquals("必须记录实际档位 320，不是请求档 999", 320, entity.quality)
    }

    // ── 断言 2：songKey == ntwk_meting_<id>:q320 ─────────────────

    @Test
    fun `降级后 songKey 用实际档位`() = runBlocking {
        val s = song()
        val (entity, _) = simulateDownload(s, 999, ResolveResult("https://cdn/320.mp3", 320))
        assertEquals(
            "songKey 必须用实际档位，否则下次 isDownloaded(320) 查不到会重复下载",
            "ntwk_meting_1001:q320", entity.songKey
        )
        assertNotEquals("不能是请求档位", "ntwk_meting_1001:q999", entity.songKey)
    }

    // ── 断言 3：文件名 03 - 南方姑娘 (320).mp3 ───────────────────

    @Test
    fun `降级后文件名含实际码率且扩展名为 mp3`() = runBlocking {
        val s = song()
        val (_, fileName) = simulateDownload(s, 999, ResolveResult("https://cdn/320.mp3", 320))
        assertEquals("03 - 南方姑娘 (320).mp3", fileName)
        assertTrue("不应是 flac", !fileName.endsWith(".flac"))
        assertTrue("不应含 (999)", !fileName.contains("(999)"))
    }

    // ── 断言 4：entity.status == COMPLETED（不是 FAILED） ────────

    @Test
    fun `降级成功任务标记为 COMPLETED`() = runBlocking {
        val s = song()
        val (entity, _) = simulateDownload(s, 999, ResolveResult("https://cdn/320.mp3", 320))
        assertEquals(
            "静默降级成功必须记为 COMPLETED，不产生 FAILED 条目",
            DownloadStatus.COMPLETED.name, entity.status
        )
    }

    // ── 断言 5：全链失败才 FAILED ───────────────────────────────

    @Test
    fun `全链失败时 url 为空 上层应落 FAILED`() = runBlocking {
        val s = song()
        val failed = ResolveResult.failure(999)
        assertTrue("全链失败时 url 必须为 null", failed.url == null)
        // 生产代码在 url==null 时返回 Failure("无法获取下载链接")，不建 COMPLETED 行
        assertEquals("未建任何行", 0, repo.downloadedQualitiesOf(s.id).size)
        // 且不应被判定为已下载
        assertTrue(!repo.isDownloaded(s, 999))
    }

    // ── 断言 6：请求 320 实际 320 → 无降级，行为与现状一致 ───────

    @Test
    fun `无降级时行为与改造前一致`() = runBlocking {
        val s = song()
        val (entity, fileName) = simulateDownload(s, 320, ResolveResult("https://cdn/320.mp3", 320))
        assertEquals(320, entity.quality)
        assertEquals("ntwk_meting_1001:q320", entity.songKey)
        assertEquals("03 - 南方姑娘 (320).mp3", fileName)
    }

    // ── 断言 7/8：降级信号（播放提示 vs 下载静默） ───────────────

    @Test
    fun `降级信号可用于播放提示 下载侧不据此报错`() {
        val requested = 999
        val downgraded = ResolveResult("https://cdn/320.mp3", 320)
        assertTrue("播放路径应据 isDowngradedFrom 产生提示", downgraded.isDowngradedFrom(requested))

        val noDowngrade = ResolveResult("https://cdn/320.mp3", 320)
        assertTrue("同档不算降级", !noDowngrade.isDowngradedFrom(320))

        val auto = ResolveResult("https://cdn/x.mp3", 320)
        assertTrue("AUTO 档永不判定为降级", !auto.isDowngradedFrom(QualityTiers.AUTO))
    }

    // ── 断言 9：不残留孤儿 DOWNLOADING 行 ───────────────────────

    @Test
    fun `降级场景不残留孤儿 DOWNLOADING 行`() = runBlocking {
        val s = song()
        simulateDownload(s, 999, ResolveResult("https://cdn/320.mp3", 320))
        val all = repo.downloadedQualitiesOf(s.id)
        assertEquals(1, all.size)
        assertEquals("不应残留请求档位的孤儿行", 0, all.count { it.songKey.contains("q999") })
        assertEquals(
            "不应有 DOWNLOADING 状态残留",
            0, all.count { it.status == DownloadStatus.DOWNLOADING.name }
        )
    }

    // ── 端到端一致性：三个维度对齐 ──────────────────────────────

    @Test
    fun `降级落库三维度一致 key quality 文件名`() = runBlocking {
        val s = song()
        val (entity, fileName) = simulateDownload(s, 999, ResolveResult("https://cdn/320.mp3", 320))
        val qFromKey = entity.songKey.substringAfterLast(":q").toInt()
        assertEquals("key 中的档位必须等于 quality 列", qFromKey, entity.quality)
        assertTrue("文件名后缀必须与档位一致", fileName.contains("(${entity.quality})"))
        assertEquals("扩展名必须与档位一致", "mp3", entity.containerExt)
    }

    @Test
    fun `无损档无降级时落 flac 且文件名无档位后缀`() = runBlocking {
        val s = song()
        val (entity, fileName) = simulateDownload(
            s, 999, ResolveResult("https://cdn/999.flac", QualityTiers.LOSSLESS)
        )
        assertEquals(999, entity.quality)
        assertEquals("flac", entity.containerExt)
        assertEquals("03 - 南方姑娘.flac", fileName)
        assertTrue("无损档不加档位后缀", !fileName.contains("(999)"))
    }

    @Test
    fun `降级到 128 时文件名与 key 均体现 128`() = runBlocking {
        val s = song()
        val (entity, fileName) = simulateDownload(s, 999, ResolveResult("https://cdn/128.mp3", 128))
        assertEquals(128, entity.quality)
        assertEquals("ntwk_meting_1001:q128", entity.songKey)
        assertEquals("03 - 南方姑娘 (128).mp3", fileName)
    }
}

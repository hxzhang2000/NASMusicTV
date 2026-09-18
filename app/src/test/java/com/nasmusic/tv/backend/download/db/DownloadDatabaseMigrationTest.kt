package com.nasmusic.tv.backend.download.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `downloads.db` v1 → v2 迁移测试（多码率方案 §4.2.4 / §10.1）。
 *
 * **这是"零重建迁移、不丢数据"承诺的硬证据。**
 *
 * 为什么必须有它：Room 打开数据库时会把「实体定义」与「迁移后的实际表结构」
 * 逐字段比对（列名/类型/NOT NULL/默认值/索引名），任何一处不符即抛
 * `IllegalStateException` —— 表现为**每个升级用户一启动就崩**。
 * 而本库**刻意关闭了 `fallbackToDestructiveMigration`**（下载记录不可重建，
 * 否则文件变孤儿），所以没有任何兜底路径。仅靠代码审查无法确认手写的
 * `ALTER TABLE` / `CREATE INDEX` 与 Room 期望一致，必须用真实 v1 库跑一遍。
 *
 * 实现方式说明：不使用 `MigrationTestHelper`，因为它要求把 schema JSON 挂到
 * 测试 assets 上，而开启 `unitTests.isIncludeAndroidResources = true` 会破坏
 * 既有测试 `BaiduMvFileServiceTest`（实测：该类的 `UncaughtExceptionsBeforeTest`
 * 只在开启后出现）。这里改为**手工用 v1 的原始 DDL 建库**（DDL 逐字取自
 * KSP 导出的 `app/schemas/.../1.json`），再用 `Room.databaseBuilder` +
 * `MIGRATION_1_2` 打开 —— 走的正是生产代码的升级路径，
 * Room 的 schema 校验照样生效，且不需要任何全局测试配置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadDatabaseMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    private companion object {
        const val DB_NAME = "migration-test.db"

        /** v1 的原始 DDL，逐字取自 app/schemas/.../DownloadDatabase/1.json（勿手改） */
        val V1_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `download_songs` (" +
                "`songKey` TEXT NOT NULL, `dedupeKey` TEXT NOT NULL, `songId` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, " +
                "`sourceType` TEXT NOT NULL, `networkSource` TEXT, `audioPath` TEXT, " +
                "`tmpPath` TEXT, `coverPath` TEXT, `lyricPath` TEXT, `embedded` INTEGER NOT NULL, " +
                "`fileSize` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`bitrate` INTEGER NOT NULL, `containerExt` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `progress` INTEGER NOT NULL, `errorMsg` TEXT, " +
                "`retryCount` INTEGER NOT NULL, `autoDownloaded` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `completedAt` INTEGER, PRIMARY KEY(`songKey`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_songs_songKey` ON `download_songs` (`songKey`)",
            "CREATE INDEX IF NOT EXISTS `index_download_songs_dedupeKey` ON `download_songs` (`dedupeKey`)",
            "CREATE INDEX IF NOT EXISTS `index_download_songs_status` ON `download_songs` (`status`)",
            "CREATE TABLE IF NOT EXISTS `export_records` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `volume_id` TEXT NOT NULL, " +
                "`rel_path` TEXT NOT NULL, `src_path` TEXT NOT NULL, `size` INTEGER NOT NULL, " +
                "`src_size` INTEGER NOT NULL, `exported_at` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_export_records_volume_id_rel_path` " +
                "ON `export_records` (`volume_id`, `rel_path`)"
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    /**
     * 手工创建 v1 库并写入存量数据，然后关闭。
     *
     * ⚠️ Room 的 Kotlin 默认值**不是 SQL 默认值**：v1 所有 NOT NULL 列都没有
     * `DEFAULT`，因此 INSERT 必须显式给出全部 NOT NULL 列，漏一列即抛
     * `SQLiteConstraintException: Cannot execute for changed row count`。
     */
    private fun seedV1() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        V1_DDL.forEach { db.execSQL(it) }
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
        )
        helper.use { h ->
            val db = h.writableDatabase
            db.execSQL(
                """
                INSERT INTO download_songs
                  (songKey, dedupeKey, songId, title, artist, album, sourceType,
                   networkSource, audioPath, tmpPath, coverPath, lyricPath, embedded,
                   fileSize, durationMs, bitrate, containerExt,
                   status, progress, errorMsg, retryCount, autoDownloaded, createdAt, completedAt)
                VALUES
                  ('ntwk_meting_1001', 'a|b', 'ntwk_meting_1001', '南方姑娘', '赵雷', '理想国',
                   'NETWORK_MUSIC', 'meting', '/m/1.mp3', NULL, NULL, NULL, 0,
                   100, 1000, 320, 'mp3', 'COMPLETED', 100, NULL, 0, 0, 1, 1000),
                  ('ntwk_meting_1002', 'c|d', 'ntwk_meting_1002', '成都', '赵雷', '无法长大',
                   'NETWORK_MUSIC', 'meting', '/m/2.mp3', NULL, NULL, NULL, 0,
                   200, 2000, 128, 'mp3', 'COMPLETED', 100, NULL, 0, 1, 2, 2000),
                  ('nas_500', 'e|f', 'nas_500', '画', '赵雷', '歌手',
                   'NAS', NULL, '/m/3.flac', NULL, NULL, NULL, 0,
                   300, 3000, 900, 'flac', 'COMPLETED', 100, NULL, 0, 0, 3, 3000),
                  ('ntwk_meting_1003', 'g|h', 'ntwk_meting_1003', '未完成', 'X', 'Y',
                   'NETWORK_MUSIC', 'meting', NULL, '/m/4.part', NULL, NULL, 0,
                   0, 4000, 0, 'mp3', 'DOWNLOADING', 40, NULL, 0, 0, 4, NULL)
                """.trimIndent()
            )
        }
    }

    /**
     * 用**生产代码的升级路径**打开：`Room.databaseBuilder` + `MIGRATION_1_2`。
     * Room 会在此校验迁移后的表结构与 `DownloadSongEntity` 定义是否一致，
     * 不一致会抛 `IllegalStateException`（测试失败 = 真实设备会崩溃）。
     */
    private fun openV2(): DownloadDatabase =
        Room.databaseBuilder(context, DownloadDatabase::class.java, DB_NAME)
            .addMigrations(DownloadDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `迁移后存量行数不变 一条都不丢`() {
        seedV1()
        val db = openV2()
        try {
            val dao = db.downloadSongDao()
            assertEquals("存量 4 行必须全部保留", 4, db.query("SELECT COUNT(*) FROM download_songs", null).use { c ->
                c.moveToFirst(); c.getInt(0)
            })
            // 通过 DAO 读（走实体映射，验证列结构与实体一致）
            assertEquals(4, runBlocking { dao.getCompleted() }.size + 1) // 3 COMPLETED + 1 DOWNLOADING
        } finally {
            db.close()
        }
    }

    @Test
    fun `迁移后 songKey 未被改写`() {
        seedV1()
        val db = openV2()
        try {
            val keys = mutableListOf<String>()
            db.query("SELECT songKey FROM download_songs ORDER BY songKey", null).use { c ->
                while (c.moveToNext()) keys.add(c.getString(0))
            }
            assertEquals(
                listOf("nas_500", "ntwk_meting_1001", "ntwk_meting_1002", "ntwk_meting_1003"),
                keys
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `迁移后 quality 列全部为 0（AUTO 档）`() {
        seedV1()
        val db = openV2()
        try {
            db.query("SELECT COUNT(*) FROM download_songs WHERE quality = 0", null).use { c ->
                c.moveToFirst()
                assertEquals("存量行的 quality 必须取列默认值 0", 4, c.getInt(0))
            }
            // 实体映射也应读到 0
            runBlocking { db.downloadSongDao().getCompleted() }.forEach {
                assertEquals(0, it.quality)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `迁移后业务字段原样保留`() {
        seedV1()
        val db = openV2()
        try {
            val e = runBlocking { db.downloadSongDao().get("ntwk_meting_1001") }
            assertTrue("存量行必须可读", e != null)
            assertEquals("南方姑娘", e!!.title)
            assertEquals("赵雷", e.artist)
            assertEquals("/m/1.mp3", e.audioPath)
            assertEquals(100L, e.fileSize)
            // bitrate 是"真实码率"，不能被 quality 覆盖
            assertEquals(320, e.bitrate)
        } finally {
            db.close()
        }
    }

    @Test
    fun `songId 索引存在且可用`() {
        seedV1()
        val db = openV2()
        try {
            db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='index_download_songs_songId'",
                null
            ).use { c ->
                assertTrue("index_download_songs_songId 必须存在（Room 校验会比对索引名）", c.moveToFirst())
            }
            // 走 DAO 的 bySongId（v2 的 downloadedQualitiesOf 用它）
            val list = runBlocking { db.downloadSongDao().bySongId("ntwk_meting_1001") }
            assertEquals(1, list.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `迁移后能与带档位后缀的新行共存 不撞主键`() {
        seedV1()
        val db = openV2()
        try {
            val dao = db.downloadSongDao()
            // 这是 v2 的核心能力：同曲多档共存
            runBlocking {
                dao.upsert(
                    repoEntity("ntwk_meting_1001:q320", quality = 320, ext = "mp3", path = "/m/1_320.mp3")
                )
                dao.upsert(
                    repoEntity("ntwk_meting_1001:q999", quality = 999, ext = "flac", path = "/m/1.flac")
                )
            }
            val all = runBlocking { dao.bySongId("ntwk_meting_1001") }
            assertEquals("存量行 + 两条新档位行必须共存", 3, all.size)
            assertEquals(listOf(999, 320, 0), all.map { it.quality })
        } finally {
            db.close()
        }
    }

    @Test
    fun `迁移后唯一索引仍生效 重复 songKey 被拒`() {
        seedV1()
        val db = openV2()
        try {
            val rejected = try {
                // upsert 是 REPLACE 语义不抛异常；用裸 INSERT 验证唯一约束
                db.openHelper.writableDatabase.execSQL(
                    """
                    INSERT INTO download_songs
                      (songKey, dedupeKey, songId, title, artist, album, sourceType,
                       networkSource, audioPath, tmpPath, coverPath, lyricPath, embedded,
                       fileSize, durationMs, bitrate, containerExt, quality,
                       status, progress, errorMsg, retryCount, autoDownloaded, createdAt, completedAt)
                    VALUES
                      ('ntwk_meting_1001', 'x', 'ntwk_meting_1001', 'dup', 'X', 'Y',
                       'NETWORK_MUSIC', 'meting', '/dup.mp3', NULL, NULL, NULL, 0,
                       1, 1, 320, 'mp3', 320, 'COMPLETED', 100, NULL, 0, 0, 7, 7000)
                    """.trimIndent()
                )
                false
            } catch (e: Exception) {
                true
            }
            assertTrue("songKey 主键唯一约束在迁移后必须仍然生效", rejected)
        } finally {
            db.close()
        }
    }

    @Test
    fun `AUTO 档能命中存量行 升级后不会重复下载`() {
        seedV1()
        val db = openV2()
        try {
            // 模拟 v2 的 isDownloaded(song, AUTO)：查无后缀 key
            val hit = runBlocking { db.downloadSongDao().getCompletedByKey("ntwk_meting_1001") }
            assertTrue("AUTO 档必须能命中存量行，否则升级后会重复下载", hit != null)
            assertEquals(0, hit!!.quality)
        } finally {
            db.close()
        }
    }

    private fun repoEntity(key: String, quality: Int, ext: String, path: String) =
        DownloadSongEntity(
            songKey = key,
            dedupeKey = "a|b",
            songId = "ntwk_meting_1001",
            title = "南方姑娘",
            artist = "赵雷",
            album = "理想国",
            sourceType = "NETWORK_MUSIC",
            networkSource = "meting",
            audioPath = path,
            containerExt = ext,
            quality = quality,
            status = DownloadStatus.COMPLETED.name,
            progress = 100,
            fileSize = 100L,
            durationMs = 1000L,
            bitrate = 320,
            createdAt = 5000L,
            completedAt = 5000L
        )
}

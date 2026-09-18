package com.nasmusic.tv.backend.download.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 下载索引数据库（downloads.db，version = 2）
 *
 * **schema 导出已开启**（v2.35.0）：本库现在有真实迁移 `MIGRATION_1_2`，
 * 导出的 `app/schemas/.../DownloadDatabase/{1,2}.json` 是 `MigrationTestHelper`
 * 验证"迁移后表结构与实体定义一致"的唯一依据。改动表结构时必须同步 bump version，
 * 并让 KSP 重新导出新版本 JSON（**不要手改 JSON**）。
 *
 * **独立建库，绝不并入 LocalMusicDatabase**：
 * - [com.nasmusic.tv.backend.local.db.LocalMusicDatabase] 当前为 v2 且开启 `fallbackToDestructiveMigration(true)`，
 *   任何 schema 变更都会破坏性重建 —— 把下载索引并入会导致 schema 变更时下载记录全丢、
 *   文件变孤儿、用户重复下载。
 * - 下载索引命中"按字段查询/聚合"（COUNT 配额、SUM 占用、按 dedupeKey 查重）+
 *   "持续增长且需局部更新"（进度更新高频），必须用数据库。
 * - 不启用 destructive fallback：未来 schema 变更必须写 Migration，禁止图省事开 fallback。
 *
 * 含两张表：
 * - [DownloadSongEntity]：下载歌曲索引（含状态/进度/路径/去重键）
 * - [ExportRecordEntity]：导出到外接设备的记录（增量判定，见 §8.8）
 */
@Database(
    entities = [
        DownloadSongEntity::class,
        ExportRecordEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class DownloadDatabase : RoomDatabase() {

    abstract fun downloadSongDao(): DownloadSongDao
    abstract fun exportRecordDao(): ExportRecordDao

    companion object {
        @Volatile
        private var INSTANCE: DownloadDatabase? = null

        /**
         * v2.35.0 多码率：新增 `quality` 列 + `songId` 索引。
         *
         * **零重建迁移**：只做一条 `ALTER TABLE ADD COLUMN`（SQLite 非破坏性加列，
         * 不重建表、不回填数据、不重写页）+ 一条 `CREATE INDEX`。
         *
         * 存量行 `quality` 取列默认值 0（= AUTO 档），而 [com.nasmusic.tv.backend.download.model.downloadKeyOf]
         * 对 AUTO 档返回与旧格式完全相同的 key，因此存量行天然被新代码命中，无需回填。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE download_songs ADD COLUMN quality INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_download_songs_songId ON download_songs (songId)"
                )
            }
        }

        fun get(context: Context): DownloadDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                DownloadDatabase::class.java,
                "downloads.db"
            )
                .addMigrations(MIGRATION_1_2)
                // 不启用 fallbackToDestructiveMigration：下载记录不可重建（会导致孤儿文件）
                .build()
                .also { INSTANCE = it }
        }
    }
}

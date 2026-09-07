package com.nasmusic.tv.backend.download.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 下载索引数据库（downloads.db，version = 1）
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
    version = 1,
    exportSchema = false
)
abstract class DownloadDatabase : RoomDatabase() {

    abstract fun downloadSongDao(): DownloadSongDao
    abstract fun exportRecordDao(): ExportRecordDao

    companion object {
        @Volatile
        private var INSTANCE: DownloadDatabase? = null

        fun get(context: Context): DownloadDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                DownloadDatabase::class.java,
                "downloads.db"
            )
                // 不启用 fallbackToDestructiveMigration：下载记录不可重建（会导致孤儿文件）
                .build()
                .also { INSTANCE = it }
        }
    }
}

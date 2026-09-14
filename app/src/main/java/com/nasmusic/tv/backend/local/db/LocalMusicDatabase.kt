package com.nasmusic.tv.backend.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地音乐索引数据库
 *
 * 单例持有。schema 变化时（含升级与降级）回退到破坏性迁移（重建数据库）。
 * 本地索引可由重扫重建，无需维护 Migration 类，避免版本演进时的迁移代码负担。
 *
 * L4 设计说明（2026-09-13 补充）：
 *  本 fallback 仅适用于「可由其他数据源重建的本地索引」类数据库。
 *  若未来新增承载用户数据（如下载历史、收藏、播放列表）的数据库，
 *  **绝对不可启用** `fallbackToDestructiveMigration(true)` —— 重建将导致用户数据丢失。
 *  此类数据库必须维护完整的 Migration 类并在 `addMigrations(...)` 注册。
 */
@Database(
    entities = [LocalSongEntity::class],
    version = 3,
    exportSchema = true
)
abstract class LocalMusicDatabase : RoomDatabase() {

    abstract fun localMusicDao(): LocalMusicDao

    companion object {
        @Volatile private var INSTANCE: LocalMusicDatabase? = null

        fun get(context: Context): LocalMusicDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalMusicDatabase::class.java,
                    "local_music.db"
                )
                .fallbackToDestructiveMigration(true)
                .build()
                .also { INSTANCE = it }
            }
    }
}
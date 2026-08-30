package com.nasmusic.tv.backend.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地音乐索引数据库
 *
 * 单例持有。降级时回退到破坏性迁移（重建数据库）。
 */
@Database(
    entities = [LocalSongEntity::class],
    version = 1,
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
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                .also { INSTANCE = it }
            }
    }
}
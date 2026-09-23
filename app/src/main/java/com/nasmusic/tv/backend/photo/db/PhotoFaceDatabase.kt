package com.nasmusic.tv.backend.photo.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 人脸检测结果库（`photo_face.db`，version = 1）
 *
 * ## ⛔ 为什么独立建库（不并进 `LocalMusicDatabase`）
 *
 * [com.nasmusic.tv.backend.local.db.LocalMusicDatabase] 开了
 * `fallbackToDestructiveMigration(true)` ⇒ **任何 schema 变更都会破坏性重建**。
 * 人脸结果**重建成本极高**（1 万张 ≈ 17 分钟，§10.2），绝不该被音乐索引的 schema 变更
 * 连带清掉。这与 `DownloadDatabase` 的「独立建库」是同一个理由（照它的 KDoc 抄的判断口径）。
 *
 * ## 为什么这里**可以**开 destructive fallback
 *
 * 人脸结果是**纯派生数据**：丢了最多是「仅显示含人像」暂时退化成「显示全部」，
 * 重扫一次即可恢复。⇒ `version = 1` 且允许 `fallbackToDestructiveMigration(true)`，
 * 省掉一整套 Migration 代码。
 *
 * ⛔ **触发条件**：若日后这张表开始承载**不可重建**的数据（例如用户手动标记的人脸、
 * 手工纠正的误检），必须立刻改为显式 Migration 并去掉 fallback —— 与
 * `LocalMusicDatabase` 的那条告诫同构。
 *
 * ## schema 导出
 *
 * `exportSchema = true`：KSP 会把 `1.json` 落到 `app/schemas/...PhotoFaceDatabase/`
 * （`app/build.gradle.kts` 配了 `room.schemaLocation`）。**该 JSON 必须入库**（项目硬约定）。
 */
@Database(entities = [PhotoFaceEntity::class], version = 1, exportSchema = true)
abstract class PhotoFaceDatabase : RoomDatabase() {

    abstract fun photoFaceDao(): PhotoFaceDao

    companion object {
        @Volatile
        private var INSTANCE: PhotoFaceDatabase? = null

        fun get(context: Context): PhotoFaceDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                PhotoFaceDatabase::class.java,
                "photo_face.db"
            )
                // 人脸结果可由重扫重建（见类 KDoc）⇒ 允许破坏性迁移，省掉 Migration
                .fallbackToDestructiveMigration(true)
                .build()
                .also { INSTANCE = it }
        }
    }
}

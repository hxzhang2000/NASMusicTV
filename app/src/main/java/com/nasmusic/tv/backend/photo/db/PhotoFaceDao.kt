package com.nasmusic.tv.backend.photo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * `photo_face` 表访问
 *
 * ⚠️ [faceKeys] 是「仅显示含人像」的**唯一查询入口**（§10.3）：
 * 它只返回 `hasFace = 1` 的 key，由调用方与 `PhotoSource` 的当前结果做**交集**
 * —— 拔盘后表里条目**不清**（重插仍有效），但交集会把盘上已经不存在的照片自然剔除。
 */
@Dao
interface PhotoFaceDao {

    /** 写入 / 覆盖一条结果（重扫同一张照片时应覆盖，不是追加） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: PhotoFaceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<PhotoFaceEntity>)

    /** 所有「检出人脸」的照片 key（`PhotoRef.id`） */
    @Query("SELECT photoKey FROM photo_face WHERE hasFace = 1")
    suspend fun faceKeys(): List<String>

    @Query("SELECT * FROM photo_face WHERE photoKey = :key")
    suspend fun find(key: String): PhotoFaceEntity?

    /** 已检测过的照片 key（用于「续跑」—— 不必重复推理） */
    @Query("SELECT photoKey FROM photo_face")
    suspend fun allKeys(): List<String>

    @Query("SELECT COUNT(*) FROM photo_face")
    suspend fun count(): Int

    @Query("DELETE FROM photo_face")
    suspend fun clear()
}

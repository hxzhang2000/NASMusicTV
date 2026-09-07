package com.nasmusic.tv.backend.download.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 导出到外接设备（USB/SD）的记录（downloads.db / export_records）
 *
 * 用途（见方案 §8.8.6）：
 * - 增量判定：再次导出时跳过"未变化的"（relPath 已存在 且 srcSize 未变 → skipped）
 * - 断点可续：导出中途拔盘后已写记录保留，重插后可继续未完成部分
 *
 * 设计要点：
 * - [volumeId] + [relPath] 唯一索引 → 同一卷上的同一路径只能有一条记录
 * - [srcSize] 用于判断源文件是否变化（变化则覆盖，未变则跳过）
 */
@Entity(
    tableName = "export_records",
    indices = [Index(value = ["volume_id", "rel_path"], unique = true)]
)
data class ExportRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "volume_id") val volumeId: String,    // 卷 UUID 或路径哈希（设备标识）
    @ColumnInfo(name = "rel_path") val relPath: String,      // 相对导出根，如 "周杰伦/七里香/01 - 七里香.mp3"
    @ColumnInfo(name = "src_path") val srcPath: String,      // 本地源文件绝对路径
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "src_size") val srcSize: Long,        // 用于判断源文件是否变化需重导
    @ColumnInfo(name = "exported_at") val exportedAt: Long
)

@Dao
interface ExportRecordDao {

    /** 增量判定的核心查询：某卷上所有已导出记录 */
    @Query("SELECT * FROM export_records WHERE volume_id = :volumeId")
    suspend fun byVolume(volumeId: String): List<ExportRecordEntity>

    @Query("SELECT COUNT(*) FROM export_records WHERE volume_id = :volumeId")
    suspend fun countByVolume(volumeId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rec: ExportRecordEntity)

    @Query("DELETE FROM export_records WHERE volume_id = :volumeId")
    suspend fun clearVolume(volumeId: String)

    @Query("DELETE FROM export_records")
    suspend fun deleteAll()
}

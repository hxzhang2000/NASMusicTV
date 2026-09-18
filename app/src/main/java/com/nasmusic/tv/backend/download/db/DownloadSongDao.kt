package com.nasmusic.tv.backend.download.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 下载歌曲索引 DAO
 *
 * 所有方法均为 suspend，由 Room 自动调度到 IO 线程。
 * 唯一返回 [Flow] 的方法是 [observeCompleted]，用于设置页"已下载 N 首"实时显示。
 */
@Dao
interface DownloadSongDao {

    @Query("SELECT * FROM download_songs WHERE songKey = :key")
    suspend fun get(key: String): DownloadSongEntity?

    @Query("SELECT * FROM download_songs WHERE status = 'COMPLETED' AND dedupeKey = :d LIMIT 1")
    suspend fun findCompletedByDedupe(d: String): DownloadSongEntity?

    /**
     * 该曲全部下载记录（v2.35.0 多码率：走 songId 索引，供档位徽标/去重判定）。
     * 按 quality 降序，最高档在前。
     */
    @Query("SELECT * FROM download_songs WHERE songId = :songId ORDER BY quality DESC")
    suspend fun bySongId(songId: String): List<DownloadSongEntity>

    /** 该曲指定档位的已完成记录（多码率去重判定） */
    @Query("SELECT * FROM download_songs WHERE songKey = :key AND status = 'COMPLETED' LIMIT 1")
    suspend fun getCompletedByKey(key: String): DownloadSongEntity?

    /** 按音频文件路径查下载记录（供 LocalMusicRepository 元数据短路） */
    @Query("SELECT * FROM download_songs WHERE audioPath = :path LIMIT 1")
    suspend fun metadataByPath(path: String): DownloadSongEntity?

    @Query("SELECT * FROM download_songs WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    suspend fun getCompleted(): List<DownloadSongEntity>

    @Query("SELECT * FROM download_songs WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    fun observeCompleted(): Flow<List<DownloadSongEntity>>

    @Query("SELECT COUNT(*) FROM download_songs WHERE status = 'COMPLETED' AND autoDownloaded = 1")
    suspend fun countAutoCompleted(): Int

    @Query("SELECT COALESCE(SUM(fileSize),0) FROM download_songs WHERE status = 'COMPLETED'")
    suspend fun sumCompletedSize(): Long

    @Query("SELECT COUNT(*) FROM download_songs WHERE status = 'COMPLETED'")
    suspend fun countCompleted(): Int

    /** 已下载歌词数（内嵌或旁路 .lrc 文件） */
    @Query("SELECT COUNT(*) FROM download_songs WHERE status = 'COMPLETED' AND (embedded = 1 OR lyricPath IS NOT NULL)")
    suspend fun countLyrics(): Int

    /** 已下载封面数（内嵌或旁路 .jpg 文件） */
    @Query("SELECT COUNT(*) FROM download_songs WHERE status = 'COMPLETED' AND (embedded = 1 OR coverPath IS NOT NULL)")
    suspend fun countCovers(): Int

    @Upsert
    suspend fun upsert(e: DownloadSongEntity)

    @Query("UPDATE download_songs SET status=:status, progress=:progress, errorMsg=:err WHERE songKey=:key")
    suspend fun updateStatus(key: String, status: String, progress: Int, err: String? = null)

    @Query("UPDATE download_songs SET progress=:progress WHERE songKey=:key")
    suspend fun updateProgress(key: String, progress: Int)

    @Query("UPDATE download_songs SET status='FAILED', errorMsg=:err WHERE status IN ('PENDING','DOWNLOADING')")
    suspend fun resetUnfinished(err: String)

    @Query("SELECT * FROM download_songs WHERE status IN ('PENDING','DOWNLOADING')")
    suspend fun getUnfinished(): List<DownloadSongEntity>

    @Query("DELETE FROM download_songs WHERE songKey=:key")
    suspend fun delete(key: String)

    @Query("DELETE FROM download_songs")
    suspend fun deleteAll()
}

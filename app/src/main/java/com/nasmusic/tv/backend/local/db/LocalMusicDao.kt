package com.nasmusic.tv.backend.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 本地音乐索引 DAO
 *
 * 提供索引的增删改查与关键词搜索。
 * 所有方法均为 suspend，由 Room 自动调度到 IO 线程。
 */
@Dao
interface LocalMusicDao {

    @Query("SELECT * FROM local_songs")
    suspend fun getAllSongs(): List<LocalSongEntity>

    @Query("SELECT path FROM local_songs")
    suspend fun getAllPaths(): List<String>

    @Query("SELECT * FROM local_songs WHERE mediaStoreId = :id")
    suspend fun getSongById(id: Long): LocalSongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<LocalSongEntity>)

    @Query("DELETE FROM local_songs WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM local_songs")
    suspend fun deleteAll()

    @Query("DELETE FROM local_songs WHERE storageType = :storageType")
    suspend fun deleteByStorageType(storageType: String)

    /**
     * 关键词搜索：标题 / 艺术家 / 专辑 任意匹配
     * 仅查索引，不触发文件扫描
     */
    @Query("""
        SELECT * FROM local_songs
        WHERE title LIKE '%' || :query || '%'
           OR artist LIKE '%' || :query || '%'
           OR album LIKE '%' || :query || '%'
        ORDER BY title ASC
    """)
    suspend fun search(query: String): List<LocalSongEntity>
}
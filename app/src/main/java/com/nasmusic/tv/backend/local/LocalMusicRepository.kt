package com.nasmusic.tv.backend.local

import android.content.Context
import com.nasmusic.tv.backend.local.db.LocalMusicDao
import com.nasmusic.tv.backend.local.db.LocalSongEntity
import com.nasmusic.tv.data.model.ScanResult
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.StorageType
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地音乐仓库
 *
 * 唯一对外数据入口，提供：
 * - [loadFromCache]：启动时从 Room 缓存加载（毫秒级）
 * - [incrementalScan]：启动时后台增量扫描更新索引
 * - [search]：搜索只查索引，不触发文件扫描
 * - [fullScan]：全量扫描（手动刷新）
 * - [scanUsbDevice]：USB 设备插拔时的定向扫描
 */
class LocalMusicRepository(
    private val context: Context,
    private val dao: LocalMusicDao,
    private val scanner: MusicScanner
) {
    companion object { private const val TAG = "LocalMusicRepo" }

    /** 启动时：从缓存加载（毫秒级） */
    suspend fun loadFromCache(): List<Song> = withContext(Dispatchers.IO) {
        dao.getAllSongs().map { it.toSong() }
    }

    /** 启动时：后台增量扫描更新索引 */
    suspend fun incrementalScan(): ScanResult = withContext(Dispatchers.IO) {
        val scanned = scanner.scanAllMusic()
        // 以 contentUri 字符串作为唯一标识（MediaStore 与 file:// 均唯一）
        val scannedKeys = scanned.map { it.contentUri.toString() }.toSet()
        val cachedKeys = dao.getAllPaths().toSet()

        val newSongs = scanned.filter { it.contentUri.toString() !in cachedKeys }
        val deletedPaths = (cachedKeys - scannedKeys).toList()

        if (newSongs.isNotEmpty()) {
            dao.insertAll(newSongs.map { it.toEntity() })
        }
        if (deletedPaths.isNotEmpty()) {
            dao.deleteByPaths(deletedPaths)
        }

        AppLog.i(TAG, "incremental: +${newSongs.size} new, -${deletedPaths.size} deleted, scanned=${scanned.size}")
        ScanResult(
            newSongs = newSongs.map { it.toSong() },
            deletedPaths = deletedPaths,
            updatedSongs = emptyList()
        )
    }

    /** 搜索：只查询索引，不扫描文件 */
    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        dao.search(query.trim()).map { it.toSong() }
    }

    /** 全量扫描（手动刷新时） */
    suspend fun fullScan(): List<Song> = withContext(Dispatchers.IO) {
        dao.deleteAll()
        val scanned = scanner.scanAllMusic()
        dao.insertAll(scanned.map { it.toEntity() })
        scanned.map { it.toSong() }
    }

    /** USB 设备变更时的定向扫描 */
    suspend fun scanUsbDevice(devicePath: String): ScanResult = withContext(Dispatchers.IO) {
        val scanned = scanner.scanPath(devicePath, StorageType.USB)
        val deletedPaths = dao.getAllSongs()
            .filter { it.storageType == StorageType.USB.name }
            .map { it.path }

        // 重建该 USB 设备的索引（先删旧再插新，避免残留已移除文件）
        if (deletedPaths.isNotEmpty()) {
            dao.deleteByPaths(deletedPaths)
        }
        if (scanned.isNotEmpty()) {
            dao.insertAll(scanned.map { it.toEntity() })
        }

        AppLog.i(TAG, "USB scan $devicePath: ${scanned.size} songs")
        ScanResult(
            newSongs = scanned.map { it.toSong() },
            deletedPaths = deletedPaths,
            updatedSongs = emptyList()
        )
    }

    // ── 转换函数 ──

    private fun ScannedSong.toSong(): Song = Song(
        id = "local_${contentUri.hashCode()}",
        title = title,
        artist = artist,
        album = album,
        albumId = albumId.toString(),
        durationMs = duration,
        coverUrl = if (albumId > 0) "content://media/external/audio/albumart/$albumId" else null,
        streamUrl = contentUri.toString(),
        path = contentUri.toString(),
        isLocalSong = true,
        storageType = storageType.name
    )

    private fun ScannedSong.toEntity(): LocalSongEntity = LocalSongEntity(
        mediaStoreId = mediaStoreId,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        duration = duration,
        size = size,
        dateAdded = dateAdded,
        mimeType = mimeType,
        contentUri = contentUri.toString(),
        volumeName = volumeName,
        storageType = storageType.name,
        // 唯一键：content URI 字符串（MediaStore 与 file:// 均唯一，用于增量去重）
        path = contentUri.toString(),
        lastModified = dateAdded,
        coverPath = null
    )

    private fun LocalSongEntity.toSong(): Song = Song(
        id = "local_$mediaStoreId",
        title = title,
        artist = artist,
        album = album,
        albumId = albumId.toString(),
        durationMs = duration,
        coverUrl = coverPath ?: run {
            if (albumId > 0) "content://media/external/audio/albumart/$albumId"
            else null
        },
        streamUrl = contentUri,
        path = path,
        isLocalSong = true,
        storageType = storageType
    )
}
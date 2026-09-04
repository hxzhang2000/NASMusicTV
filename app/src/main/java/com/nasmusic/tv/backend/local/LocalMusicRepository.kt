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
        val cached = dao.getAllSongs()
        val cachedKeys = cached.map { it.path }.toSet()

        val newSongs = scanned.filter { it.contentUri.toString() !in cachedKeys }

        // B4 修复：USB / 外部 SD 歌曲在对应卷未挂载时不应被判定为「已删除」。
        // MediaStore 在 USB 拔出后不再返回该卷上的条目，若简单做 cachedKeys - scannedKeys
        // 会把所有 USB 歌一次性清空（每次启动丢失 USB 索引）。
        // 仅对「本次扫描覆盖到的卷」内的缓存条目做删除比对；USB/EXTERNAL 条目若
        // 对应卷当前未挂载则保留（挂载时由 scanUsbDevice 定向更新）。
        val mountedVolumeNames = scanned.map { it.volumeName }.filter { it.isNotEmpty() }.toSet()
        val hasInternal = scanned.any { it.storageType == StorageType.INTERNAL }

        val deletedPaths = cached
            .filter { entity ->
                // 内置存储条目：MediaStore 始终返回，直接参与比对
                if (entity.storageType == StorageType.INTERNAL.name) {
                    entity.path !in scannedKeys
                } else {
                    // USB/EXTERNAL：仅当对应卷仍在挂载时参与比对，否则保留（卷可能被拔出）
                    val stillMounted = mountedVolumeNames.contains(entity.volumeName) ||
                        (entity.storageType == StorageType.EXTERNAL.name && hasInternal)
                    stillMounted && entity.path !in scannedKeys
                }
            }
            .map { it.path }

        if (newSongs.isNotEmpty()) {
            dao.insertAll(newSongs.map { it.toEntity() })
        }
        if (deletedPaths.isNotEmpty()) {
            deleteByPathsChunked(deletedPaths)
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
        // B3 修复：先扫描，扫描成功后再重建索引。原实现先 deleteAll() 再 scan，
        // 一旦 scanAllMusic() 抛异常（MediaStore 查询失败 / 权限变更），用户曲库被清空。
        // 现在：扫描失败直接抛回空结果，旧索引保持不变。
        val scanned = scanner.scanAllMusic()
        dao.deleteAll()
        dao.insertAll(scanned.map { it.toEntity() })
        scanned.map { it.toSong() }
    }

    /**
     * B5 修复：分批删除，避免 `IN (:paths)` 超过 SQLite 变量上限（999）导致崩溃。
     * 大曲库（数千首 USB 歌）一次性传参触发 `too many SQL variables` 异常。
     */
    private suspend fun deleteByPathsChunked(paths: List<String>) {
        val chunkSize = 500
        paths.chunked(chunkSize).forEach { chunk ->
            dao.deleteByPaths(chunk)
        }
    }

    /** USB 设备变更时的定向扫描 */
    suspend fun scanUsbDevice(devicePath: String): ScanResult = withContext(Dispatchers.IO) {
        val scanned = scanner.scanPath(devicePath, StorageType.USB)
        val deletedPaths = dao.getAllSongs()
            .filter { it.storageType == StorageType.USB.name }
            .map { it.path }

        // 重建该 USB 设备的索引（先删旧再插新，避免残留已移除文件）
        if (deletedPaths.isNotEmpty()) {
            deleteByPathsChunked(deletedPaths)
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
        // B6 修复：ID 统一为 "local_$mediaStoreId"，与 [LocalSongEntity.toSong] 一致。
        // 原实现用 contentUri.hashCode()，与缓存加载时 LocalSongEntity.toSong 的
        // mediaStoreId 不一致，导致同一首歌扫描时与从缓存加载时 ID 不同，
        // 收藏/播放记录/队列跨会话失效。
        id = "local_$mediaStoreId",
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
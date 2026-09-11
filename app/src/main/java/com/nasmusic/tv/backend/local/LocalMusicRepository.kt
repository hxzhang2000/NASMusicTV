package com.nasmusic.tv.backend.local

import android.content.Context
import com.nasmusic.tv.backend.download.DownloadPathBuilder
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
    private val scanner: MusicScanner,
    private var downloadDao: com.nasmusic.tv.backend.download.db.DownloadSongDao? = null,
    private var downloadRootProvider: () -> java.io.File? = { context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) }
) {
    companion object { private const val TAG = "LocalMusicRepo" }

    /** 注入下载源（供增量扫描合并下载目录 + DOWNLOAD 删除判定分支；NasMusicApp 启动时调用） */
    fun attachDownloadSource(
        downloadDao: com.nasmusic.tv.backend.download.db.DownloadSongDao,
        downloadRootProvider: () -> java.io.File?
    ) {
        this.downloadDao = downloadDao
        this.downloadRootProvider = downloadRootProvider
    }

    /** 启动时：从缓存加载（毫秒级） */
    suspend fun loadFromCache(): List<Song> = withContext(Dispatchers.IO) {
        dao.getAllSongs().map { it.toSong() }
    }

    /** 启动时：后台增量扫描更新索引 */
    suspend fun incrementalScan(): ScanResult = withContext(Dispatchers.IO) {
        val scanned = buildScannedList()
        // 以 contentUri 字符串作为唯一标识（MediaStore 与 file:// 均唯一）
        val scannedKeys = scanned.map { it.contentUri.toString() }.toSet()
        val cached = dao.getAllSongs()
        val cachedKeys = cached.map { it.path }.toSet()

        val newSongs = scanned.filter { it.contentUri.toString() !in cachedKeys }

        // B4 修复：USB / 外部 SD 歌曲在对应卷未挂载时不应被判定为「已删除」。
        val mountedVolumeNames = scanned.map { it.volumeName }.filter { it.isNotEmpty() }.toSet()
        val hasInternal = scanned.any { it.storageType == StorageType.INTERNAL }

        // 空扫描保护：若本次一个文件都没扫到（MediaStore 查询异常 / 权限变更），
        // 跳过全部删除判定，避免把整个本地曲库误判为"已删除"。
        val deletedPaths = if (scanned.isEmpty()) {
            emptyList()
        } else {
            cached
                .filter { entity ->
                    // 内置存储 / 下载目录条目：始终参与比对
                    if (entity.storageType == StorageType.INTERNAL.name ||
                        entity.storageType == StorageType.DOWNLOAD.name) {
                        entity.path !in scannedKeys
                    } else {
                        // USB/EXTERNAL：仅当对应卷仍在挂载时参与比对
                        val stillMounted = mountedVolumeNames.contains(entity.volumeName) ||
                            (entity.storageType == StorageType.EXTERNAL.name && hasInternal)
                        stillMounted && entity.path !in scannedKeys
                    }
                }
                .map { it.path }
        }

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

    /** 合并 MediaStore 通道 + 下载目录文件通道，并按真实文件路径去重（防 MediaStore 重复收录下载目录） */
    private suspend fun buildScannedList(): List<ScannedSong> {
        val scanned = mutableListOf<ScannedSong>()
        scanned += scanner.scanAllMusic()
        // 下载目录：优先走 downloads.db 元数据（metadataResolver），跳过 MMR
        val downloadRoot = downloadRootProvider()
        if (downloadRoot != null && downloadRoot.exists()) {
            scanned += scanner.scanPath(
                downloadRoot.absolutePath,
                StorageType.DOWNLOAD,
                excludeDirs = setOf(DownloadPathBuilder.TMP_DIR),
                metadataResolver = { f -> metadataForDownloadedFile(f) }
            )
        }
        // 关键：防 MediaStore 重复收录下载目录（Android 11+ 通常不索引，但部分 ROM 会）。
        // 修复：原先按 contentUri 字符串去重——MediaStore 通道是
        // content://media/external/audio/media/<id>，下载通道是 file://（同一文件两种 URI），
        // 字符串永不相等 → 同一首歌在曲库出现两次。
        // 现改为按真实文件路径去重（DOWNLOAD 优先），路径缺失时回退 contentUri。
        val indexByKey = HashMap<String, Int>(scanned.size)
        val deduped = ArrayList<ScannedSong>(scanned.size)
        for (item in scanned) {
            val key = dedupeKeyOf(item)
            val existing = indexByKey[key]
            if (existing == null) {
                indexByKey[key] = deduped.size
                deduped.add(item)
            } else if (item.storageType == StorageType.DOWNLOAD &&
                deduped[existing].storageType != StorageType.DOWNLOAD
            ) {
                // 同文件两条通道冲突：优先保留 DOWNLOAD（元数据来自 downloads.db，更准）
                deduped[existing] = item
            }
        }
        return deduped
    }

    /** 去重键：优先规范化真实路径（统一分隔符），缺失时回退 contentUri */
    private fun dedupeKeyOf(item: ScannedSong): String {
        val raw = item.dataPath?.takeIf { it.isNotBlank() }
            ?: return "uri:" + item.contentUri
        return "path:" + raw.replace('\\', '/')
    }

    /** 从 downloads.db 读取下载歌曲元数据，构造 ScannedSong（短路 MMR） */
    private suspend fun metadataForDownloadedFile(file: java.io.File): ScannedSong? {
        // 通过 DownloadDatabase 查该路径的下载记录
        val entity = downloadDao?.let { dao -> runCatching { dao.metadataByPath(file.absolutePath) }.getOrNull() }
        return if (entity != null) {
            scanner.metadataFor(
                path = file.absolutePath,
                title = entity.title,
                artist = entity.artist,
                album = entity.album,
                durationMs = entity.durationMs
            )
        } else {
            // 无下载记录（用户手动放入）：走 MMR
            null
        }
    }

    /** 搜索：只查询索引，不扫描文件 */
    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        dao.search(escapeLike(query.trim())).map { it.toSong() }
    }

    /**
     * 转义 SQL LIKE 通配符：用户输入的 % / _ / \ 应作为字面量匹配
     * （配合 LocalMusicDao.search 的 `ESCAPE '\'`），否则会被当通配符导致误匹配。
     */
    private fun escapeLike(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    /** 全量扫描（手动刷新时） */
    suspend fun fullScan(): List<Song> = withContext(Dispatchers.IO) {
        // B3 修复：先扫描，扫描成功后再重建索引。原实现先 deleteAll() 再 scan，
        // 一旦扫描抛异常（MediaStore 查询失败 / 权限变更），用户曲库被清空。
        // 现在：扫描失败直接抛回空结果，旧索引保持不变。
        // P0-2 修复：使用 buildScannedList() 替代 scanner.scanAllMusic()，
        // 确保下载目录也被扫描（buildScannedList 合并 MediaStore + 下载目录）。
        val scanned = buildScannedList()
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
            .filter { it.storageType == StorageType.USB.name && it.path.startsWith(devicePath) }
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

    /** 下载完成时即时入库（不等下次启动；见 §7.5.5） */
    suspend fun upsertDownloaded(song: ScannedSong) = withContext(Dispatchers.IO) {
        dao.insertAll(listOf(song.toEntity()))
    }

    /** 删除下载歌曲时移除 local_songs 中对应条目（分批删除，避 SQLite 999 变量上限） */
    suspend fun removeByPaths(paths: List<String>) = withContext(Dispatchers.IO) {
        deleteByPathsChunked(paths)
    }

    /** 按 storageType 批量删除（如清除所有下载类歌曲） */
    suspend fun deleteByStorageType(storageType: String) = withContext(Dispatchers.IO) {
        dao.deleteByStorageType(storageType)
    }

    /** 全量加载本地曲库（清除/删除后刷新用） */
    suspend fun loadAll(): List<Song> = withContext(Dispatchers.IO) {
        dao.getAllSongs().map { it.toSong() }
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
        storageType = storageType.name,
        year = year,
        genre = genre
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
        coverPath = null,
        year = year,
        genre = genre
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
        storageType = storageType,
        year = year,
        genre = genre
    )
}
package com.nasmusic.tv.backend.local

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.nasmusic.tv.data.model.StorageType
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地音乐扫描器
 *
 * 双通道扫描：
 * 1. [scanAllMusic]：通过 MediaStore 查询所有卷上的音乐文件（Android 10+ 返回 content URI）
 * 2. [scanPath]：通过文件系统遍历 + MediaMetadataRetriever 提取元数据（USB 挂载点专用）
 *
 * 输出统一的 [ScannedSong] 中间结构，供 [LocalMusicRepository] 落库。
 */
class MusicScanner(private val context: Context) {

    companion object {
        private const val TAG = "MusicScanner"

        /** 支持的音乐文件扩展名（文件系统扫描用） */
        private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "m4a", "ogg", "wav", "aac", "wma")

        /**
         * 扫描目录树的最大递归深度。
         * 防止深层目录结构（或符号链接循环）导致扫描无限递归、主线程 IO 卡死。
         */
        private const val MAX_SCAN_DEPTH = 8
    }

    /**
     * 扫描所有可用卷上的音乐文件（MediaStore 通道）
     */
    suspend fun scanAllMusic(): List<ScannedSong> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<ScannedSong>()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.VOLUME_NAME
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(
                collection, projection, selection, null, sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val volumeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.VOLUME_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val volumeName = cursor.getString(volumeCol) ?: ""
                    val storageType = resolveStorageType(volumeName)

                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    )

                    songs.add(
                        ScannedSong(
                            mediaStoreId = id,
                            title = cursor.getString(titleCol) ?: "Unknown",
                            artist = cursor.getString(artistCol) ?: "Unknown",
                            album = cursor.getString(albumCol) ?: "Unknown",
                            albumId = cursor.getLong(albumIdCol),
                            duration = cursor.getLong(durationCol),
                            size = cursor.getLong(sizeCol),
                            dateAdded = cursor.getLong(dateAddedCol),
                            mimeType = cursor.getString(mimeCol) ?: "",
                            contentUri = uri,
                            volumeName = volumeName,
                            storageType = storageType
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "MediaStore query failed: ${e.message}", e)
        }

        AppLog.d(TAG, "Scanned ${songs.size} songs via MediaStore")
        songs
    }

    /**
     * 扫描指定根路径的音乐文件（USB / SD 卡挂载点 / 应用专属下载目录）
     * 使用文件系统遍历 + MediaMetadataRetriever 提取元数据
     *
     * @param rootPath 根路径
     * @param storageType 存储类型（USB / EXTERNAL / DOWNLOAD）
     * @param excludeDirs 需要跳过的子目录名（如 ".tmp"），防止下载临时文件被扫描
     * @param metadataResolver 命中则跳过 MMR 直接构造元数据（下载歌曲元数据已在 downloads.db，
     *                          避免上千首下载目录让启动扫描卡到秒级）
     */
    suspend fun scanPath(
        rootPath: String,
        storageType: StorageType = StorageType.USB,
        excludeDirs: Set<String> = emptySet(),
        metadataResolver: (suspend (File) -> ScannedSong?)? = null
    ): List<ScannedSong> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<ScannedSong>()
        val root = File(rootPath)
        if (!root.exists() || !root.isDirectory) return@withContext songs

        root.walkTopDown()
            // 跳过 .tmp / 含 .nomedia 的目录（避免 .part 临时文件与下载目录被媒体扫描器读到）
            .onEnter { dir ->
                dir.name !in excludeDirs && !File(dir, ".nomedia").exists()
            }
            .maxDepth(MAX_SCAN_DEPTH)
            .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            .forEach { file ->
                val song = metadataResolver?.invoke(file) ?: scanFile(file, storageType)
                if (song != null) songs.add(song)
            }

        AppLog.d(TAG, "Scanned ${songs.size} songs from $rootPath")
        songs
    }

    /**
     * 从下载索引的元数据直接构造 [ScannedSong]（短路 MMR，供 [scanPath] 的 metadataResolver 注入）。
     *
     * @param path 音频文件绝对路径
     * @param title 标题
     * @param artist 艺术家
     * @param album 专辑
     * @param durationMs 时长
     */
    fun metadataFor(
        path: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ): ScannedSong? {
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        return ScannedSong(
            mediaStoreId = HashUtils.stablePathHash64(path),
            title = title,
            artist = artist,
            album = album,
            albumId = 0L,
            duration = durationMs,
            size = file.length(),
            dateAdded = file.lastModified() / 1000,
            mimeType = guessMimeType(file.extension),
            contentUri = Uri.fromFile(file),
            volumeName = "",
            storageType = StorageType.DOWNLOAD
        )
    }

    private fun scanFile(file: File, storageType: StorageType): ScannedSong? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: file.nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            ScannedSong(
                // 复审 P2 修复：原为 file.absolutePath.hashCode().toLong()（32-bit 哈希拓宽），
                // 大曲库碰撞概率高、@Insert(REPLACE) 下静默覆盖丢歌。改用稳定 64-bit FNV-1a 哈希，
                // 避免主键碰撞。因 id 取值变化，LocalMusicDatabase 已 bump 至 version=2 触发干净重建。
                mediaStoreId = HashUtils.stablePathHash64(file.absolutePath),
                title = title,
                artist = artist,
                album = album,
                albumId = 0L,
                duration = duration,
                size = file.length(),
                dateAdded = file.lastModified() / 1000,
                mimeType = guessMimeType(file.extension),
                contentUri = Uri.fromFile(file),
                volumeName = "",
                storageType = storageType
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to scan ${file.absolutePath}: ${e.message}", e)
            null
        } finally {
            // B 修复：无论成功/异常都释放 MediaMetadataRetriever，防止 TV 上
            // 元数据提取器实例泄漏（原实现仅在成功后 release，异常路径泄漏）。
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun resolveStorageType(volumeName: String): StorageType = when {
        volumeName.contains("usb", ignoreCase = true) -> StorageType.USB
        volumeName.contains("sd", ignoreCase = true) -> StorageType.EXTERNAL
        else -> StorageType.INTERNAL
    }

    private fun guessMimeType(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "wma" -> "audio/x-ms-wma"
        else -> "audio/*"
    }
}

/**
 * 扫描器输出的中间数据结构（不直接给 UI 使用）
 *
 * [LocalMusicRepository] 将其转为 [com.nasmusic.tv.data.model.Song] 或落库为 Entity。
 */
data class ScannedSong(
    val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val size: Long,
    val dateAdded: Long,
    val mimeType: String,
    val contentUri: Uri,
    val volumeName: String,
    val storageType: StorageType
)
package com.nasmusic.tv.backend.local

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.nasmusic.tv.data.model.StorageType
import com.nasmusic.tv.util.AppLog
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
     * 扫描指定根路径的音乐文件（USB / SD 卡挂载点）
     * 使用文件系统遍历 + MediaMetadataRetriever 提取元数据
     */
    suspend fun scanPath(rootPath: String, storageType: StorageType = StorageType.USB): List<ScannedSong> =
        withContext(Dispatchers.IO) {
            val songs = mutableListOf<ScannedSong>()
            val root = File(rootPath)
            if (!root.exists() || !root.isDirectory) return@withContext songs

            root.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
                .forEach { file ->
                    val song = scanFile(file, storageType)
                    if (song != null) songs.add(song)
                }

            AppLog.d(TAG, "Scanned ${songs.size} songs from $rootPath")
            songs
        }

    private fun scanFile(file: File, storageType: StorageType): ScannedSong? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: file.nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            retriever.release()

            ScannedSong(
                mediaStoreId = file.absolutePath.hashCode().toLong(),
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
package com.nasmusic.tv.backend.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import com.nasmusic.tv.backend.local.db.LocalSongEntity
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import java.io.File

/**
 * 本地专辑封面提取器
 *
 * 从本地音频文件内嵌元数据提取专辑封面并缓存到应用缓存目录。
 * 延迟提取策略：扫描时不做封面提取（避免启动慢），在展示时按需提取。
 *
 * 封面优先级：内嵌封面缓存 > MediaStore 专辑封面 URI（Album art）
 */
class LocalCoverExtractor(private val context: Context) {

    companion object { private const val TAG = "LocalCover" }

    /** 从音频文件内嵌元数据提取封面图片 */
    fun extractEmbeddedCover(audioPath: String): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(audioPath)
            val art = retriever.embeddedPicture
            retriever.release()
            art?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (e: Exception) {
            AppLog.w(TAG, "extract embedded cover failed: ${e.message}")
            null
        }
    }

    /** 保存封面位图到缓存目录 */
    private fun saveCoverToCache(mediaStoreId: Long, bitmap: Bitmap): File? {
        return try {
            val cacheDir = File(context.cacheDir, "album_covers").apply { mkdirs() }
            val file = File(cacheDir, "$mediaStoreId.jpg")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file
        } catch (e: Exception) {
            AppLog.w(TAG, "save cover to cache failed: ${e.message}")
            null
        }
    }

    /**
     * 提取并缓存内嵌封面，返回缓存文件绝对路径
     * @return 封面缓存路径，无内嵌封面返回 null
     */
    fun extractAndCache(entity: LocalSongEntity): String? {
        val filePath = entity.path.removePrefix("file://").removePrefix("content://")
        val realFile = File(filePath)
        if (!realFile.exists()) return null
        val bitmap = extractEmbeddedCover(realFile.absolutePath) ?: return null
        return saveCoverToCache(entity.mediaStoreId, bitmap)?.absolutePath
    }

    /**
     * 获取歌曲封面 URL
     * 本地歌曲的封面：优先已提取的缓存路径，回退到 MediaStore 专辑封面 URI
     */
    fun getCoverUrl(song: Song): String? {
        if (!song.isLocalSong) return null
        return song.coverUrl
    }

    /** MediaStore 专辑封面 URI（供 toSong 转换时使用） */
    fun albumArtUri(albumId: Long): String? =
        if (albumId > 0) "content://media/external/audio/albumart/$albumId" else null
}
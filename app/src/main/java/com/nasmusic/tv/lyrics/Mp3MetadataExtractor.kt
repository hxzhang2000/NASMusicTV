package com.nasmusic.tv.lyrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MP3 元数据提取器
 * 从 MP3 文件中提取内嵌歌词、专辑封面等 ID3 标签信息
 */
class Mp3MetadataExtractor(private val context: Context) {

    /**
     * 从 MP3 文件中提取内嵌歌词
     * @param filePath MP3 文件路径（本地文件）
     * @param songId 歌曲ID
     * @return 提取到的歌词，如果没有则返回 null
     */
    suspend fun extractEmbeddedLyrics(filePath: String, songId: String = ""): Lyrics? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)
                // METADATA_KEY_LYRICS is available in API 29+
                val lyricsText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    retriever.extractMetadata(26) // METADATA_KEY_LYRICS = 26
                } else {
                    null
                }

                if (!lyricsText.isNullOrBlank() && LrcParser.isValidLrc(lyricsText)) {
                    return@withContext LrcParser.parse(lyricsText, songId)
                        .copy(source = LyricsSource.EMBEDDED)
                }

                // 尝试提取 USLT (Unsynchronized Lyrics) 标签
                val usltLyrics = extractUsltLyrics(retriever)
                if (!usltLyrics.isNullOrBlank() && LrcParser.isValidLrc(usltLyrics)) {
                    return@withContext LrcParser.parse(usltLyrics, songId)
                        .copy(source = LyricsSource.EMBEDDED)
                }

                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                retriever.release()
            }
        }

    /**
     * 从网络流中提取内嵌歌词
     * @param streamUrl 音频流 URL
     * @param songId 歌曲ID
     * @return 提取到的歌词，如果没有则返回 null
     */
    suspend fun extractEmbeddedLyricsFromStream(streamUrl: String, songId: String = ""): Lyrics? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(streamUrl, HashMap())
                // METADATA_KEY_LYRICS is available in API 29+
                val lyricsText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    retriever.extractMetadata(26) // METADATA_KEY_LYRICS = 26
                } else {
                    null
                }

                if (!lyricsText.isNullOrBlank() && LrcParser.isValidLrc(lyricsText)) {
                    return@withContext LrcParser.parse(lyricsText, songId)
                        .copy(source = LyricsSource.EMBEDDED)
                }

                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                retriever.release()
            }
        }

    /**
     * 从 MP3 文件中提取专辑封面
     * @param filePath MP3 文件路径
     * @return 专辑封面 Bitmap，如果没有则返回 null
     */
    suspend fun extractAlbumArt(filePath: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)
                val artBytes = retriever.embeddedPicture
                if (artBytes != null) {
                    BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                retriever.release()
            }
        }

    /**
     * 从网络流中提取专辑封面
     * @param streamUrl 音频流 URL
     * @return 专辑封面 Bitmap，如果没有则返回 null
     */
    suspend fun extractAlbumArtFromStream(streamUrl: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(streamUrl, HashMap())
                val artBytes = retriever.embeddedPicture
                if (artBytes != null) {
                    BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                retriever.release()
            }
        }

    /**
     * 保存专辑封面到本地缓存
     * @param songId 歌曲ID
     * @param bitmap 专辑封面 Bitmap
     * @return 保存后的文件路径
     */
    suspend fun saveAlbumArtToCache(songId: String, bitmap: Bitmap): String? =
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "album_art").apply { mkdirs() }
                val file = File(cacheDir, "${songId}.jpg")
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                file.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    /**
     * 提取其他 ID3 元数据
     */
    suspend fun extractMetadata(filePath: String): Map<String, String?> =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val metadata = mutableMapOf<String, String?>()
            try {
                retriever.setDataSource(filePath)
                metadata["title"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                metadata["artist"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                metadata["album"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                metadata["albumArtist"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                metadata["genre"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                metadata["year"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                metadata["trackNumber"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                metadata["duration"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                metadata["composer"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                metadata["writer"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_WRITER)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                retriever.release()
            }
            metadata
        }

    /**
     * 尝试提取 USLT (Unsynchronized Lyrics) 标签
     * 部分 MP3 使用 USLT 而非 SYLT 存储歌词
     */
    private fun extractUsltLyrics(retriever: MediaMetadataRetriever): String? {
        // MediaMetadataRetriever 的 METADATA_KEY_LYRICS 已经包含了 USLT 内容
        // 如果上述方法未能获取，可以尝试通过反射或其他方式获取
        // 这里返回 null，依赖标准 API 获取
        return null
    }
}

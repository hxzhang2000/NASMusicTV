package com.nasmusic.tv.lyrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MP3 元数据提取器
 * 从 MP3 文件中提取内嵌歌词、专辑封面等 ID3 标签信息
 */
class Mp3MetadataExtractor(private val context: Context) {

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
}

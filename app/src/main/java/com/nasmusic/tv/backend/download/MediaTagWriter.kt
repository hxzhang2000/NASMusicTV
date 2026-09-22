package com.nasmusic.tv.backend.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.reference.ID3V2Version
import org.jaudiotagger.tag.images.Artwork
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 音频元数据内嵌（见方案 §6）
 *
 * - 白名单：mp3/flac/m4a/mp4/aac/ogg/opus
 * - 不支持的格式（wav/aape/dff/dsf 等）→ [embed] 返回 false，调用方走旁路
 * - 内嵌封面 + 中文歌词（USLT/LYRICS/©lyr）
 * - 内嵌失败不阻断下载，仅 [embedded] = false
 *
 * jaudiotagger 配置：
 * - MP3 统一写 ID3v2.3 + UTF-16（Win 兼容），歌词语言 chi
 * - 全局只配置一次（[configure]）
 */
object MediaTagWriter {
    private const val TAG = "MediaTagWriter"

    private val EMBEDDABLE = setOf("mp3", "flac", "m4a", "mp4", "aac", "ogg", "opus")

    /** 最大封面长边像素（避免内嵌超大图撑爆文件） */
    private const val MAX_COVER_EDGE = 1000
    /** JPEG 压缩质量 */
    private const val JPEG_QUALITY = 85

    /** 全局一次性配置（NasMusicApp.onCreate 调用一次） */
    fun configure() {
        try {
            TagOptionSingleton.getInstance().setID3V2Version(ID3V2Version.ID3_V23)
            TagOptionSingleton.getInstance().setId3v23DefaultTextEncoding(1.toByte())   // UTF-16, Win 兼容
            TagOptionSingleton.getInstance().setLanguage("chi")                          // USLT 语言代码
        } catch (e: Throwable) {
            AppLog.w(TAG, "TagOptionSingleton configure failed: ${e.message}")
        }
    }

    fun supportsEmbedding(file: File): Boolean =
        file.extension.lowercase() in EMBEDDABLE

    /**
     * 把封面 + 歌词 + 基础 tag 写入音频文件。
     *
     * @return true=已内嵌；false=不支持或失败 → 调用方写旁路文件
     */
    fun embed(file: File, song: Song, coverJpeg: ByteArray?, lrcText: String?): Boolean {
        if (!supportsEmbedding(file)) return false
        return try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, song.title)
            tag.setField(FieldKey.ARTIST, song.artist)
            tag.setField(FieldKey.ALBUM, song.album)
            if (song.trackNumber > 0) tag.setField(FieldKey.TRACK, song.trackNumber.toString())
            song.year?.let { tag.setField(FieldKey.YEAR, it.toString()) }
            song.genre?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.GENRE, it) }

            coverJpeg?.let { bytes ->
                runCatching { tag.deleteField(FieldKey.COVER_ART) }
                val artwork: Artwork = ArtworkFactory.getNew().apply {
                    binaryData = bytes
                    mimeType = "image/jpeg"
                    pictureType = 3   // front cover
                }
                tag.setField(artwork)
            }

            lrcText?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.LYRICS, it) }

            audioFile.commit()
            AppLog.d(TAG, "embed OK: ${file.name}")
            true
        } catch (e: Throwable) {
            AppLog.w(TAG, "embed failed: ${file.name} - ${e.message}", e)
            false
        }
    }

    /**
     * 把原始字节数组压缩为长边 ≤ 1000px、JPEG q85 的字节流（用于 cover.jpg 与内嵌共用一份）。
     * 输入为空或解码失败返回 null（调用方跳过封面）。
     */
    fun compressCover(rawBytes: ByteArray?): ByteArray? {
        if (rawBytes == null || rawBytes.isEmpty()) return null
        var src: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            // P3 修复（2026-09-22 审查）：先解码 bounds 并按目标边长算 inSampleSize，
            // 超大封面（4K 图）不再先全尺寸解码再缩放（批量下载时的内存峰值/OOM 面）。
            // 降采样目标与 MAX_COVER_EDGE 一致，输出画质无损。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                var sample = 1
                var halfW = bounds.outWidth
                var halfH = bounds.outHeight
                while (halfW / 2 >= MAX_COVER_EDGE && halfH / 2 >= MAX_COVER_EDGE) {
                    sample *= 2; halfW /= 2; halfH /= 2
                }
                inSampleSize = sample
            }
            src = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts) ?: return null
            scaled = scaleToMaxEdge(src, MAX_COVER_EDGE)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        } catch (e: Exception) {
            AppLog.w(TAG, "compressCover failed: ${e.message}")
            null
        } finally {
            // 回收 native 位图：批量下载时若不回收，native 内存持续增长易 OOM。
            // scaleToMaxEdge 在「无需缩放」时返回同一实例，故需判重避免 double recycle。
            if (scaled != null && scaled !== src) runCatching { scaled.recycle() }
            runCatching { src?.recycle() }
        }
    }

    private fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val maxOf = maxOf(w, h)
        if (maxOf <= maxEdge) return src
        val scale = maxEdge.toFloat() / maxOf
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}

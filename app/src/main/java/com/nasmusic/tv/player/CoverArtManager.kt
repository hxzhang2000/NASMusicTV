package com.nasmusic.tv.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import coil.ImageLoader
import coil.request.ImageRequest
import coil.target.Target
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.lyrics.Mp3MetadataExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 专辑封面管理器
 * 按优先级获取专辑封面：MP3内嵌 -> 后端API -> 默认占位图
 */
class CoverArtManager(private val context: Context) {

    private val mp3Extractor = Mp3MetadataExtractor(context)
    private val imageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        .build()

    /**
     * 获取歌曲的专辑封面 Bitmap
     * 优先级：1. MP3内嵌封面  2. 后端API封面URL  3. 默认占位图
     */
    suspend fun getCoverBitmap(song: Song): Bitmap? = withContext(Dispatchers.IO) {
        // 1. 尝试从 MP3 流中提取内嵌封面
        if (!song.streamUrl.isNullOrBlank()) {
            val embeddedArt = mp3Extractor.extractAlbumArtFromStream(song.streamUrl)
            if (embeddedArt != null) {
                return@withContext embeddedArt
            }
        }

        // 2. 尝试从后端API加载封面
        if (!song.coverUrl.isNullOrBlank()) {
            val networkArt = loadBitmapFromUrl(song.coverUrl)
            if (networkArt != null) {
                return@withContext networkArt
            }
        }

        null
    }

    /**
     * 从 URL 加载 Bitmap
     */
    private suspend fun loadBitmapFromUrl(url: String): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val request = ImageRequest.Builder(context)
                .data(url)
                .target(object : Target {
                    override fun onStart(placeholder: Drawable?) {}

                    override fun onSuccess(result: Drawable) {
                        // Convert drawable to bitmap if possible
                        val bitmap = if (result is android.graphics.drawable.BitmapDrawable) {
                            result.bitmap
                        } else {
                            null
                        }
                        continuation.resume(bitmap)
                    }

                    override fun onError(error: Drawable?) {
                        continuation.resume(null)
                    }
                })
                .build()

            imageLoader.enqueue(request)
        }

    /**
     * 预加载封面到内存缓存
     */
    suspend fun preloadCover(song: Song) {
        if (!song.coverUrl.isNullOrBlank()) {
            val request = ImageRequest.Builder(context)
                .data(song.coverUrl)
                .build()
            imageLoader.execute(request)
        }
    }

    /**
     * 清除 Coil 图片加载器的内存和磁盘缓存 (E-4)
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        try {
            imageLoader.diskCache?.clear()
            imageLoader.memoryCache?.clear()
            android.util.Log.d("CoverArtManager", "clearCache: cache cleared")
        } catch (e: Exception) {
            android.util.Log.e("CoverArtManager", "clearCache failed", e)
        }
    }

    /**
     * 获取当前缓存大小估算值
     */
    fun getCacheSize(): Long {
        return try {
            imageLoader.diskCache?.size ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

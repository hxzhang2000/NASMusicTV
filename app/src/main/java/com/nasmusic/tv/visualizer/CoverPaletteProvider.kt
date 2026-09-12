package com.nasmusic.tv.visualizer

import android.graphics.Bitmap
import androidx.collection.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面取色提供者（技法 T5）。
 *
 * - 切歌时异步计算一次，结果进 LRU 缓存
 * - 未就绪时返回 [CoverPalette.Fallback]，绝不阻塞渲染
 * - 计算在 [Dispatchers.Default] 执行，避免拖慢主线程导致切歌卡顿
 */
class CoverPaletteProvider {

    private val cache = object : LruCache<String, CoverPalette>(64) {}

    /** 同步取缓存，未命中返回 null */
    fun peek(key: String?): CoverPalette? = key?.let { cache.get(it) }

    /**
     * 异步取色。已缓存则直接返回。
     */
    suspend fun obtain(key: String?, bitmap: ImageBitmap?): CoverPalette {
        if (key == null || bitmap == null) return CoverPalette.Fallback
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.Default) {
            runCatching {
                val bmp: Bitmap = bitmap.asAndroidBitmap()
                val palette = Palette.from(bmp).maximumColorCount(16).generate()
                val accent = palette.vibrantSwatch?.rgb
                    ?: palette.lightVibrantSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb
                val secondary = palette.mutedSwatch?.rgb
                    ?: palette.darkMutedSwatch?.rgb
                    ?: accent
                val background = palette.darkMutedSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb
                    ?: 0xFF0A0F14.toInt()

                CoverPalette(
                    accent = Color(accent ?: 0xFF34D399.toInt()),
                    secondary = Color(secondary ?: 0xFF60A5FA.toInt()),
                    background = Color(background)
                )
            }.getOrDefault(CoverPalette.Fallback).also { cache.put(key, it) }
        }
    }

    fun clear() = cache.evictAll()
}

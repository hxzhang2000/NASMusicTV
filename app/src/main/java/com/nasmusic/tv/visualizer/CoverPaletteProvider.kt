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
                val rawAccent = palette.vibrantSwatch?.rgb
                    ?: palette.lightVibrantSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb
                val rawBackground = palette.darkMutedSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb
                    ?: 0xFF04190B.toInt()

                // 用户指定色系：亮蓝(195°) / 亮绿(90°) / 亮黄(60°) / 白色
                // 仅亮度随封面微调，保证任何封面下整体效果都保持明亮色系
                val rawL = VisualizerMath.rgbToHsl(Color(rawAccent ?: 0xFF00BFFF.toInt())).third
                val litMain = (0.55f + rawL * 0.15f).coerceIn(0.50f, 0.72f)
                val litSub = (0.65f + rawL * 0.15f).coerceIn(0.60f, 0.80f)
                val accent = VisualizerMath.hsl(195f, 1.0f, litMain)      // 亮蓝
                val secondary = VisualizerMath.hsl(60f, 1.0f, litSub)     // 亮黄
                val background = VisualizerMath.darken(Color(rawBackground), 0.36f)

                CoverPalette(accent = accent, secondary = secondary, background = background)
            }.getOrDefault(CoverPalette.Fallback).also { cache.put(key, it) }
        }
    }

    fun clear() = cache.evictAll()
}

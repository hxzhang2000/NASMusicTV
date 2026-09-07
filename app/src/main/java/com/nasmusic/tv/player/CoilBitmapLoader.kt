package com.nasmusic.tv.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.concurrent.futures.ResolvableFuture
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil.ImageLoader
import coil.request.ImageRequest
import com.google.common.util.concurrent.ListenableFuture

/**
 * [BitmapLoader] 实现，使用 Coil 加载歌曲封面。
 *
 * 蓝牙 AVRCP、锁屏 MediaStyle、SMSC 等系统级显示通过此接口获取封面 Bitmap。
 * 使用 NasMusicApp 全局 ImageLoader（已注入百度 dlink UA 拦截器）。
 */
@UnstableApi
class CoilBitmapLoader(
    private val imageLoader: ImageLoader,
    private val appContext: android.content.Context
) : BitmapLoader {

    override fun loadBitmap(uri: Uri, options: BitmapFactory.Options?): ListenableFuture<Bitmap> {
        val future = ResolvableFuture.create<Bitmap>()
        val request = ImageRequest.Builder(appContext)
            .data(uri)
            .size(96, 96)
            .allowHardware(false)
            .target { drawable ->
                future.set(drawable.toBitmap())
            }
            .listener(onError = { _, error ->
                future.setException(error.throwable)
            })
            .build()
        imageLoader.enqueue(request)
        return future
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = ResolvableFuture.create<Bitmap>()
        val request = ImageRequest.Builder(appContext)
            .data(data)
            .size(96, 96)
            .allowHardware(false)
            .target { drawable ->
                future.set(drawable.toBitmap())
            }
            .listener(onError = { _, error ->
                future.setException(error.throwable)
            })
            .build()
        imageLoader.enqueue(request)
        return future
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val artworkUri = metadata.artworkUri ?: return null
        return loadBitmap(artworkUri, null)
    }
}

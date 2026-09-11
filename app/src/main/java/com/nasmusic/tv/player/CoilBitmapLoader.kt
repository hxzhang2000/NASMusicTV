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
import com.google.common.util.concurrent.MoreExecutors

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
            .size(512, 512)
            .allowHardware(false)
            .target { drawable ->
                // 守卫：future 可能已被调用方取消/已由错误回调完成，
                // 此时再 set() 会抛 IllegalStateException
                if (!future.isDone) runCatching { future.set(drawable.toBitmap()) }
            }
            .listener(onError = { _, error ->
                if (!future.isDone) runCatching { future.setException(error.throwable) }
            })
            .build()
        return enqueue(request, future)
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = ResolvableFuture.create<Bitmap>()
        val request = ImageRequest.Builder(appContext)
            .data(data)
            .size(512, 512)
            .allowHardware(false)
            .target { drawable ->
                if (!future.isDone) runCatching { future.set(drawable.toBitmap()) }
            }
            .listener(onError = { _, error ->
                if (!future.isDone) runCatching { future.setException(error.throwable) }
            })
            .build()
        return enqueue(request, future)
    }

    /**
     * 入队并把 future 的取消转发到底层请求。
     * 原实现丢弃了 enqueue 返回的 Disposable，future 被取消后底层加载仍继续跑（浪费带宽/解码）。
     */
    private fun enqueue(request: ImageRequest, future: ResolvableFuture<Bitmap>): ListenableFuture<Bitmap> {
        val disposable = imageLoader.enqueue(request)
        runCatching {
            future.addListener(
                { if (future.isCancelled) runCatching { disposable.dispose() } },
                MoreExecutors.directExecutor()
            )
        }
        return future
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val artworkUri = metadata.artworkUri ?: return null
        return loadBitmap(artworkUri, null)
    }
}

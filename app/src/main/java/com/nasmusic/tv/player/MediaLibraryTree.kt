package com.nasmusic.tv.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.nasmusic.tv.NasMusicApp

/**
 * 媒体库树实现
 *
 * 为 Android Auto / Wear OS 提供基本的媒体浏览结构。
 * 暴露当前播放队列作为可浏览的媒体树。
 */
class MediaLibraryTree(
    private val context: android.content.Context
) {
    companion object {
        private const val TAG = "MediaLibraryTree"
        private const val ROOT_ID = "root"
        private const val QUEUE_ID = "queue"
    }

    /**
     * 获取根媒体项
     */
    fun getLibraryRoot(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setTitle("NAS Music TV")
                    .build()
            )
            .build()
    }

    /**
     * 获取子媒体项
     */
    fun getChildren(parentId: String): List<MediaItem> {
        return when (parentId) {
            ROOT_ID -> listOf(getQueueItem())
            QUEUE_ID -> getQueueItems()
            else -> emptyList()
        }
    }

    /**
     * 获取单个媒体项
     */
    fun getItem(mediaId: String): MediaItem? {
        if (mediaId == QUEUE_ID) return getQueueItem()
        return findInQueue(mediaId)
    }

    private fun getQueueItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(QUEUE_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setTitle("当前队列")
                    .build()
            )
            .build()
    }

    private fun getQueueItems(): List<MediaItem> {
        val nasMusicApp = context.applicationContext as? NasMusicApp ?: return emptyList()
        val songs = nasMusicApp.playerManager.getQueueSnapshot()
        return songs.map { song ->
            MediaItem.Builder()
                .setMediaId(song.id)
                .setUri(song.streamUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(song.coverUrl?.let { Uri.parse(it) })
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build()
                )
                .build()
        }
    }

    private fun findInQueue(mediaId: String): MediaItem? {
        val nasMusicApp = context.applicationContext as? NasMusicApp ?: return null
        val songs = nasMusicApp.playerManager.getQueueSnapshot()
        val song = songs.firstOrNull { it.id == mediaId } ?: return null
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(song.coverUrl?.let { Uri.parse(it) })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()
    }
}
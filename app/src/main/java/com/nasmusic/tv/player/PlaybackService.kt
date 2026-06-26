package com.nasmusic.tv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.MainActivity
import com.nasmusic.tv.util.AppLog

/**
 * 后台播放服务
 * 基于 Media3 MediaLibraryService
 * 支持前台通知 (D-1)
 */
class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var lastNotificationState: Pair<String?, Boolean>? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateNotification()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                (application as NasMusicApp).playerManager.onPlaybackEnded()
            }
            updateNotification()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.d("PlaybackService", "onCreate: starting")

        // Create notification channel for Android 8+
        createNotificationChannel()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // 启用 MP3 seek 支持（解决 HTTP 流 seek 后重置到 0 的问题）
        // FLAG_ENABLE_INDEX_SEEKING: 为 VBR MP3 建立时间-字节映射索引
        // FLAG_ENABLE_CONSTANT_BITRATE_SEEKING: 假设 CBR MP3 可通过固定码率计算偏移
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setMp3ExtractorFlags(
                androidx.media3.extractor.mp3.Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING or
                androidx.media3.extractor.mp3.Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
            )
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this, extractorsFactory)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(playerListener)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaLibrarySession = MediaLibrarySession.Builder(
            this, player, object : MediaLibrarySession.Callback {
            }
        )
            .setSessionActivity(pendingIntent)
            .build()

        // Store player reference in manager
        (application as NasMusicApp).playerManager.setPlayer(player)

        // Start as foreground service with initial notification
        startForeground(NOTIFICATION_ID, buildNotification(null, false))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        AppLog.d("PlaybackService", "onDestroy: cleaning up")
        // 释放 PlayerManager 资源（Handler、listener），防止内存泄漏
        try {
            (application as NasMusicApp).playerManager.release()
        } catch (e: Exception) {
            AppLog.w("PlaybackService", "PlayerManager.release failed", e)
        }
        // 释放 MediaSession 和 Player
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        // 移除前台通知
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            AppLog.w("PlaybackService", "stopForeground failed", e)
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务列表移除应用时，停止播放和服务
        AppLog.d("PlaybackService", "onTaskRemoved: stopping service")
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.playback_channel_desc)
            setShowBadge(false)
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val player = mediaLibrarySession?.player ?: return
        val currentMediaItem = player.currentMediaItem
        val title = currentMediaItem?.mediaMetadata?.title?.toString()
            ?: currentMediaItem?.mediaId
            ?: "NAS Music TV"
        val isPlaying = player.isPlaying

        // 比较 (title, isPlaying) 元组，避免暂停/播放状态不刷新
        val stateKey = title to isPlaying
        if (stateKey == lastNotificationState) return
        lastNotificationState = stateKey

        val notification = buildNotification(title, isPlaying)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String?, isPlaying: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 媒体控制按钮（通过 ACTION_MEDIA_BUTTON + KeyEvent 转发给 MediaSession）
        val playPauseKeyCode =
            if (isPlaying) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY
        val playPauseAction = NotificationCompat.Action.Builder(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) getString(R.string.playback_paused) else getString(R.string.playback_playing),
            buildMediaButtonPendingIntent(playPauseKeyCode)
        ).build()

        val prevAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_previous,
            getString(R.string.playback_previous),
            buildMediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        ).build()

        val nextAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_next,
            getString(R.string.playback_next),
            buildMediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_NEXT)
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title ?: "NAS Music TV")
            .setContentText(if (isPlaying) getString(R.string.playback_playing) else getString(R.string.playback_paused))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .build()
    }

    /**
     * 构建媒体按钮 PendingIntent
     * 通过 ACTION_MEDIA_BUTTON Intent + KeyEvent 转发控制指令到 MediaSession
     * MediaLibraryService 会自动处理此 Intent 并调用对应的 Player 方法
     */
    private fun buildMediaButtonPendingIntent(keyCode: Int): PendingIntent {
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setPackage(packageName)
            putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
        }
        return PendingIntent.getBroadcast(
            this, keyCode, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val CHANNEL_ID = "nas_music_playback"
        private const val NOTIFICATION_ID = 1
    }
}

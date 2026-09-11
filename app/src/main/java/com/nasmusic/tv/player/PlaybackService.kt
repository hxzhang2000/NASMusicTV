package com.nasmusic.tv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import coil.Coil
import coil.request.ImageRequest
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.MainActivity
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 后台播放服务
 * 基于 Media3 MediaLibraryService
 * 支持前台通知 (D-1)
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var lastNotificationState: Pair<String?, Boolean>? = null
    private lateinit var mediaLibraryTree: MediaLibraryTree
    private var isForeground = false
    /** 当前歌曲封面 bitmap，由 onMediaItemTransition 异步加载，用于通知 largeIcon */
    private var cachedArtworkBitmap: Bitmap? = null
    /** 封面加载协程 */
    private var artworkLoadJob: Job? = null
    /** 服务级协程作用域 */
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    /** F2-2：睡眠定时器运行中，通知剩余分钟刷新（分钟粒度 key 并入去重状态） */
    private var sleepMinuteTickJob: Job? = null

    private val controlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TOGGLE_PLAY_MODE -> handleTogglePlayMode()
                ACTION_SLEEP_TIMER_CYCLE -> handleSleepTimerCycle()
            }
        }
    }

    /** F2-2b：切换播放模式（通知按钮 / 系统媒体卡片自定义按钮共用） */
    private fun handleTogglePlayMode() {
        AppLog.d("PlaybackService", "control: toggle play mode")
        (application as NasMusicApp).playModeToggleHandler?.invoke()
        // 强制刷新（模式图标变化）
        lastNotificationState = null
        updateNotification()
    }

    /** F2-2b：睡眠定时循环切换（未启动→首档位，运行中→取消） */
    private fun handleSleepTimerCycle() {
        val pm = (application as NasMusicApp).playerManager
        if (pm.sleepTimer.isRunning()) {
            pm.sleepTimer.cancel()
            AppLog.d("PlaybackService", "control: sleep timer cancelled")
        } else {
            val minutes = SLEEP_TIMER_PRESETS.first()
            pm.sleepTimer.start(minutes)
            AppLog.d("PlaybackService", "control: sleep timer started ${minutes}min")
        }
        lastNotificationState = null
        updateNotification()
        restartSleepMinuteTick()
    }

    /**
     * F2-2b：系统媒体卡片（Android 13+ 锁屏/超级岛/下拉媒体控制）自定义按钮布局。
     * 系统媒体控制按钮来自 MediaSession custom layout，而非通知 addAction——
     * 必须通过 SessionCommand + onCustomCommand 供系统 UI 调用。
     */
    private fun buildCustomLayout(): ImmutableList<CommandButton> {
        val pm = (application as NasMusicApp).playerManager
        val sleepLabel = if (pm.sleepTimer.isRunning()) {
            getString(R.string.notif_sleep_timer_remaining, pm.sleepTimer.remainingMinutes())
        } else {
            getString(R.string.notif_sleep_timer_start)
        }
        return ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName(getString(R.string.notif_play_mode))
                .setSessionCommand(SessionCommand(ACTION_TOGGLE_PLAY_MODE, Bundle.EMPTY))
                .setIconResId(android.R.drawable.ic_menu_rotate)
                .build(),
            CommandButton.Builder()
                .setDisplayName(sleepLabel)
                .setSessionCommand(SessionCommand(ACTION_SLEEP_TIMER_CYCLE, Bundle.EMPTY))
                .setIconResId(android.R.drawable.ic_lock_idle_alarm)
                .build()
        )
    }

    /** F2-2b：刷新通知时同步刷新系统媒体卡片的睡眠定时按钮文案 */
    private fun refreshSessionCustomLayout() {
        try {
            mediaLibrarySession?.setCustomLayout(buildCustomLayout())
        } catch (e: Exception) {
            AppLog.w("PlaybackService", "setCustomLayout failed", e)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                ensureMediaSessionActive()
            }
            updateNotification()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                (application as NasMusicApp).playerManager.onPlaybackEnded()
            }
            updateNotification()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            loadArtworkForCurrentItem()
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.d("PlaybackService", "onCreate: starting")

        // F2-2：注册控制广播接收器（playMode/sleepTimer 自定义动作）
        androidx.core.content.ContextCompat.registerReceiver(
            this, controlReceiver,
            android.content.IntentFilter().apply {
                addAction(ACTION_TOGGLE_PLAY_MODE)
                addAction(ACTION_SLEEP_TIMER_CYCLE)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // F2-2：睡眠定时到期 → 刷新通知（显示"已到时"状态）
        (application as NasMusicApp).playerManager.onSleepTimerExpired = {
            lastNotificationState = null
            stopSleepMinuteTick()
            updateNotification()
        }

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
        // DataSource.Factory：按 URL 域名条件注入百度 dlink 请求头（UA: pan.baidu.com + Referer）
        // NAS/Meting/百度共同一链路，非百度域名原样透传（BaiduHttpDataSourceFactory 内部判断）
        val dataSourceFactory = com.nasmusic.tv.backend.network.baidu.BaiduHttpDataSourceFactory.create(this)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        // 人声消除处理器（卡拉OK模式）— 频谱遮罩版本
        val vocalRemovalProcessor = SpectralMaskProcessor()

        // 自定义 RenderersFactory，注入 VocalRemovalProcessor 到 AudioSink
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(vocalRemovalProcessor))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(playerListener)

        // 注入高质量人声分离组件（HT-Demucs FT ONNX 模式）
        // 注意：模型不在 APK 内，需在设置页下载。初始化延迟到 enableHighQualityRemoval 时（PlayerManager 内部检查模型）
        val demucsSeparator = DemucsSeparator(this)
        val accompanimentCache = AccompanimentCache(this)

        // 初始化媒体库树（用于 Android Auto / Wear OS 浏览）
        mediaLibraryTree = MediaLibraryTree(this)

        // Store player reference in manager + inject vocal removal processor
        (application as NasMusicApp).playerManager.setPlayer(player)
        (application as NasMusicApp).playerManager.setVocalRemovalProcessor(vocalRemovalProcessor)
        (application as NasMusicApp).playerManager.setDemucsSeparator(demucsSeparator)
        (application as NasMusicApp).playerManager.setAccompanimentCache(accompanimentCache)

        // 不在 onCreate 中创建 MediaSession 或 startForeground
        // MediaSession 和前台通知延迟到首次播放时创建，避免 app 启动即显示锁屏小窗
        AppLog.d("PlaybackService", "onCreate: player created, MediaSession deferred to first play")
    }

    /**
     * 首次播放时创建 MediaSession 并进入前台模式。
     * 由 Player.Listener.onIsPlayingChanged(isPlaying=true) 触发。
     * 幂等：已创建则跳过。
     */
    private fun ensureMediaSessionActive() {
        if (mediaLibrarySession != null) return

        AppLog.d("PlaybackService", "ensureMediaSessionActive: creating MediaSession + startForeground")

        val player = (application as NasMusicApp).playerManager.getPlayer() ?: return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaLibrarySession = MediaLibrarySession.Builder(
            this, player,
            object : MediaLibrarySession.Callback {
                /** F2-2b：自定义按钮（播放模式/睡眠定时）经 SessionCommand 下发 */
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = SessionCommands.Builder()
                        .add(SessionCommand(ACTION_TOGGLE_PLAY_MODE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_SLEEP_TIMER_CYCLE, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        ACTION_TOGGLE_PLAY_MODE -> handleTogglePlayMode()
                        ACTION_SLEEP_TIMER_CYCLE -> handleSleepTimerCycle()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                override fun onGetLibraryRoot(
                    session: MediaLibrarySession,
                    controller: MediaSession.ControllerInfo,
                    params: MediaLibraryService.LibraryParams?
                ): com.google.common.util.concurrent.ListenableFuture<LibraryResult<MediaItem>> {
                    return Futures.immediateFuture(
                        LibraryResult.ofItem(mediaLibraryTree.getLibraryRoot(), params)
                    )
                }

                override fun onGetItem(
                    session: MediaLibrarySession,
                    controller: MediaSession.ControllerInfo,
                    mediaId: String
                ): com.google.common.util.concurrent.ListenableFuture<LibraryResult<MediaItem>> {
                    val item = mediaLibraryTree.getItem(mediaId)
                    return if (item != null) {
                        Futures.immediateFuture(LibraryResult.ofItem(item, null))
                    } else {
                        Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    }
                }

                override fun onGetChildren(
                    session: MediaLibrarySession,
                    controller: MediaSession.ControllerInfo,
                    parentId: String,
                    page: Int,
                    pageSize: Int,
                    params: MediaLibraryService.LibraryParams?
                ): com.google.common.util.concurrent.ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    val allChildren = mediaLibraryTree.getChildren(parentId)
                    val effectivePageSize = if (pageSize > 0) pageSize else 50
                    val start = page * effectivePageSize
                    val end = minOf(start + effectivePageSize, allChildren.size)
                    val pageChildren = if (start < allChildren.size) {
                        allChildren.subList(start, end)
                    } else {
                        emptyList()
                    }
                    return Futures.immediateFuture(
                        LibraryResult.ofItemList(ImmutableList.copyOf(pageChildren), params)
                    )
                }
            }
        )
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(CoilBitmapLoader(Coil.imageLoader(this), this))
            .build()

        // F2-2b：系统媒体卡片（锁屏/超级岛/下拉媒体控制）自定义按钮布局
        mediaLibrarySession?.setCustomLayout(buildCustomLayout())

        // F2-2b：接管 MediaNotification Provider——消除 media3 默认通知与自建通知（同为 ID=1）
        // 互相覆盖导致的"按钮不刷新"问题；统一由本服务 buildNotification 渲染 5 按钮。
        setMediaNotificationProvider(object : MediaNotification.Provider {
            override fun createNotification(
                session: MediaSession,
                customLayout: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback
            ): MediaNotification {
                val isPlaying = session.player.isPlaying
                return MediaNotification(
                    NOTIFICATION_ID,
                    buildNotification(
                        session.player.currentMediaItem?.mediaMetadata?.title?.toString(),
                        isPlaying
                    )
                )
            }

            override fun handleCustomCommand(
                session: MediaSession,
                action: String,
                extras: Bundle
            ): Boolean {
                return when (action) {
                    ACTION_TOGGLE_PLAY_MODE, ACTION_SLEEP_TIMER_CYCLE -> true
                    else -> false
                }
            }
        })

        // 进入前台模式 + 显示通知
        startForeground(NOTIFICATION_ID, buildNotification(null, false))
        isForeground = true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        AppLog.d("PlaybackService", "onDestroy: cleaning up")
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        stopSleepMinuteTick()
        serviceScope.cancel()
        // 释放 PlayerManager 资源（Handler、listener），防止内存泄漏
        try {
            (application as NasMusicApp).playerManager.release()
        } catch (e: Exception) {
            AppLog.w("PlaybackService", "PlayerManager.release failed", e)
        }
        // 释放 MediaSession（先释放 Session 再释放 Player，防止发布后资源竞争）
        mediaLibrarySession?.run {
            release()
            player.release()
        }
        mediaLibrarySession = null
        // 移除前台通知
        if (isForeground) {
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                AppLog.w("PlaybackService", "stopForeground failed", e)
            }
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务列表移除应用时：
        // - 播放中 → 继续播放（不退出）
        // - 已暂停 → 停止服务
        val player = mediaLibrarySession?.player
        val isPlaying = player?.isPlaying == true
        AppLog.d("PlaybackService", "onTaskRemoved: isPlaying=$isPlaying, hasSession=${mediaLibrarySession != null}")
        if (isPlaying) {
            // 播放中移除任务栏：继续播放，不退出服务
            return
        }
        // 已暂停或未播放：停止服务
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

    /**
     * 异步加载当前歌曲封面 bitmap，加载完成后刷新通知。
     * 首次显示时通知可能无封面，加载完成后 lastNotificationState 被重置以强制刷新。
     */
    private fun loadArtworkForCurrentItem() {
        val mediaItem = mediaLibrarySession?.player?.currentMediaItem
        val artworkUri = mediaItem?.mediaMetadata?.artworkUri
        if (artworkUri == null) {
            cachedArtworkBitmap = null
            return
        }
        artworkLoadJob?.cancel()
        artworkLoadJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val result = Coil.imageLoader(this@PlaybackService).execute(
                    ImageRequest.Builder(this@PlaybackService)
                        .data(artworkUri)
                        .size(256)
                        .allowHardware(false)
                        .build()
                )
                val drawable = result.drawable
                val bitmap = drawable?.toBitmap()
                if (bitmap != null) {
                    cachedArtworkBitmap = bitmap
                    // 重置去重状态，强制下次 updateNotification 重建通知（含新封面）
                    lastNotificationState = null
                    serviceScope.launch(Dispatchers.Main) {
                        updateNotification()
                    }
                }
            } catch (e: Exception) {
                AppLog.w("PlaybackService", "loadArtwork failed: ${e.message}")
            }
        }
    }

    private fun updateNotification() {
        val session = mediaLibrarySession ?: return
        val player = session.player
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
        // F2-2b：同步刷新系统媒体卡片的睡眠定时按钮剩余分钟
        refreshSessionCustomLayout()
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

        // F2-2：播放模式循环切换（自定义 action 广播，无系统键码语义）
        val pm = (application as NasMusicApp).playerManager
        val playModeAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_rotate,
            getString(R.string.notif_play_mode),
            buildControlPendingIntent(ACTION_TOGGLE_PLAY_MODE, RC_PLAY_MODE)
        ).build()

        // F2-2：睡眠定时（运行中显示剩余分钟，点击循环 开启→取消）
        val sleepRunning = pm.sleepTimer.isRunning()
        val sleepLabel = if (sleepRunning) {
            getString(R.string.notif_sleep_timer_remaining, pm.sleepTimer.remainingMinutes())
        } else {
            getString(R.string.notif_sleep_timer_start)
        }
        val sleepAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_lock_idle_alarm,
            sleepLabel,
            buildControlPendingIntent(ACTION_SLEEP_TIMER_CYCLE, RC_SLEEP_TIMER)
        ).build()

        // contentText 显示歌手名（播放状态由 MediaStyle 图标隐含）
        val artist = mediaLibrarySession?.player?.currentMediaItem?.mediaMetadata?.artist?.toString()

        // F2-2：下一首 subText（队尾/无队列不显示）
        val nextUpText = nextSongTitle()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title ?: "NAS Music TV")
            .setContentText(artist)
            .setSubText(nextUpText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .also { builder -> cachedArtworkBitmap?.let { builder.setLargeIcon(it) } }
            .setContentIntent(pendingIntent)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .addAction(playModeAction)
            .addAction(sleepAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaLibrarySession?.sessionCompatToken)
                    // compact view 只显示核心 3 键：prev(0), play/pause(1), next(2)
                    // （修复：原 (1,2,3) 实际显示 play/pause、next、playMode，漏掉 prev）
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .build()
    }

    /** F2-2：下一首标题（队尾或无队列返回 null） */
    private fun nextSongTitle(): String? {
        val pm = (application as NasMusicApp).playerManager
        val queue = pm.getQueueSnapshot()
        if (queue.isEmpty()) return null
        val nextIndex = pm.currentIndex.value + 1
        val next = queue.getOrNull(nextIndex) ?: return null
        return getString(R.string.notif_next_up, next.title)
    }

    /** F2-2：自定义控制动作 PendingIntent（定向本服务内 receiver） */
    private fun buildControlPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).setClass(this, PlaybackService::class.java)
        return PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** F2-2：睡眠定时运行期间每分钟刷新通知剩余分钟 */
    private fun restartSleepMinuteTick() {
        stopSleepMinuteTick()
        val pm = (application as NasMusicApp).playerManager
        if (!pm.sleepTimer.isRunning()) return
        sleepMinuteTickJob = serviceScope.launch {
            while (pm.sleepTimer.isRunning()) {
                kotlinx.coroutines.delay(60_000)
                lastNotificationState = null
                updateNotification()
                refreshSessionCustomLayout()
            }
        }
    }

    private fun stopSleepMinuteTick() {
        sleepMinuteTickJob?.cancel()
        sleepMinuteTickJob = null
    }

    /**
     * 构建媒体按钮 PendingIntent
     * 通过 ACTION_MEDIA_BUTTON Intent + KeyEvent 转发控制指令到 MediaSession
     * MediaLibraryService.onStartCommand 会自动处理此 Intent 并调用对应的 Player 方法
     * 注意：用 PendingIntent.getService 而非 getBroadcast，否则无人接收
     */
    private fun buildMediaButtonPendingIntent(keyCode: Int): PendingIntent {
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setClass(this@PlaybackService, PlaybackService::class.java)
            putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
        }
        return PendingIntent.getService(
            this, keyCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val CHANNEL_ID = "nas_music_playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TOGGLE_PLAY_MODE = "com.nasmusic.tv.action.TOGGLE_PLAY_MODE"
        private const val ACTION_SLEEP_TIMER_CYCLE = "com.nasmusic.tv.action.SLEEP_TIMER_CYCLE"
        /** 睡眠定时器预设档位（分钟） */
        private val SLEEP_TIMER_PRESETS = intArrayOf(15, 30, 60, 90)
        /** 睡眠定时周期切换请求码 */
        private const val RC_SLEEP_TIMER = 9001
        private const val RC_PLAY_MODE = 9002
    }}

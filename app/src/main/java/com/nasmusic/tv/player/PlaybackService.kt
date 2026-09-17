package com.nasmusic.tv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Process
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
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import coil.Coil
import coil.request.ImageRequest
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.MainActivity
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * Android Auto 浏览节点缓存（mediaId → Song）。
     *
     * 媒体树加载时写入，播放入口（onSetMediaItems / onAddMediaItems）同步读取——
     * 那两个回调运行在 Media3 会话线程上，不能在其中做网络 IO。
     */
    private val browseCache = BrowseCache()
    /** A-13：无 UI 的 streamUrl 解析任务（防竞态：新解析取消旧解析） */
    private var uiResolveJob: Job? = null
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
        // L3：经 app 容器的事件流投递，由 UI 侧执行切换（playMode 真相在 PlayerViewModel）。
        // 返回 false = 当前无 UI 订阅（Activity 已销毁 / 应用已退出），事件按设计丢弃——
        // 与旧实现 handler == null 时静默无反应同义，不是故障。
        if (!(application as NasMusicApp).requestPlayModeToggle()) {
            AppLog.w("PlaybackService", "control: toggle play mode dropped (no UI subscriber)")
        }
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
        // 变量名曾用 vocalRemovalProcessor（历史实现名），2026-09-14 随死代码
        // VocalRemovalProcessor 一并正名
        val spectralMaskProcessor = SpectralMaskProcessor()

        // P6：PCM 降级通道 —— 部分国产 TV 的 Visualizer 绑定成功却恒返回全 0，
        // 此时改用 AudioSink 里的 PCM 自算频谱。挂在链最前，取人声消除之前的原始信号。
        val pcmFallback = com.nasmusic.tv.player.PcmFallbackChannel()

        // 自定义 RenderersFactory，注入人声消除处理器 SpectralMaskProcessor 到 AudioSink
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(pcmFallback.processor, spectralMaskProcessor))
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
        mediaLibraryTree = MediaLibraryTree(this, browseCache)

        // Store player reference in manager + inject vocal removal processor
        // 顺序关键：PCM 降级通道必须先于 setPlayer 注入 —— setPlayer 内部会触发
        // initSpectrumAnalyzer→attach；若 TV 的 Visualizer 不可用而 attach 抛异常，
        // SpectrumAnalyzer.degradeToPcm 在 pcmFallback==null 时会直接放弃降级，
        // 导致 frame 恒 0、全部效果静止（P6 实测坑）。
        (application as NasMusicApp).playerManager.setPcmFallbackChannel(pcmFallback)
        (application as NasMusicApp).playerManager.setPlayer(player)
        // A-13：注册无 UI 依赖的 streamUrl 解析器。
        // Android Auto / Wear OS / 蓝牙唤起等场景下 MainActivity 可能从未启动，
        // MainViewModel 注册的 onNeedResolveStreamUrl 为 null，网络歌曲会静默播不出来。
        // 该解析器优先级高于 UI 侧回调，解析失败时才回落到 UI 侧。
        (application as NasMusicApp).playerManager.builtinStreamUrlResolver = { index ->
            resolveStreamUrlWithoutUi(index)
        }
        (application as NasMusicApp).playerManager.setVocalRemovalProcessor(spectralMaskProcessor)
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
                    // 包验证：本服务 exported=true（车机跨进程绑定必需），故校验调用方身份。
                    // 白名单见 isTrustedCaller()；DEBUG 下全放行，避免白名单不全导致 DHU 连不上。
                    if (!isTrustedCaller(session, controller)) {
                        AppLog.w("PlaybackService", "onConnect rejected: ${controller.packageName}")
                        return MediaSession.ConnectionResult.reject()
                    }
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

                /**
                 * 媒体库根节点。
                 *
                 * 从 root hints 读取根菜单上限（Android Auto / AAOS 通过它下发，官方默认 4）。
                 * ⚠️ 必须立即返回，不得在此做网络 IO —— 官方明确要求，否则调用方超时。
                 *
                 * 关于常量来源：官方文档给的是 `androidx.media.utils.MediaConstants
                 * .BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT`，但那来自 `androidx.media:media`，
                 * 而本项目对该库只有 **runtime scope 的传递依赖**（经 `media3-session` 引入），
                 * compile 期不可见。Media3 自己已把**同一个字符串**别名导出为
                 * `MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT`
                 * （源码级证据：`media3-session-1.2.1` 的 `MediaConstants.java:397-398`
                 * 就是 `= androidx.media.utils.MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT`），
                 * 故此处用 Media3 的别名，**不新增依赖**。
                 *
                 * `..._SUPPORTED_FLAGS` 一项有意不设置：其默认值即 `FLAG_BROWSABLE`，
                 * 而本应用根菜单 4 项**全部是**可浏览节点，显式设置是 no-op。
                 */
                override fun onGetLibraryRoot(
                    session: MediaLibrarySession,
                    controller: MediaSession.ControllerInfo,
                    params: MediaLibraryService.LibraryParams?
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    val limit = params?.extras?.getInt(
                        MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT,
                        MediaLibraryTree.DEFAULT_ROOT_LIMIT
                    ) ?: MediaLibraryTree.DEFAULT_ROOT_LIMIT
                    mediaLibraryTree.rootChildrenLimit = limit
                    return Futures.immediateFuture(
                        LibraryResult.ofItem(mediaLibraryTree.getLibraryRoot(), params)
                    )
                }

                /** 单个节点。只读内存，不触发网络请求。 */
                override fun onGetItem(
                    session: MediaLibrarySession,
                    controller: MediaSession.ControllerInfo,
                    mediaId: String
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    val item = mediaLibraryTree.getItem(mediaId)
                    return if (item != null) {
                        Futures.immediateFuture(LibraryResult.ofItem(item, null))
                    } else {
                        Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    }
                }

                /**
                 * 子节点列表。
                 *
                 * ⚠️ 两个关键点：
                 * 1. **不依赖 page / pageSize** —— Android Auto 与 AAOS 官方明确不支持分页，
                 *    该参数不可靠（旧实现据此切片会导致列表被静默截断）。
                 * 2. **必须异步** —— 树加载会访问 NAS 等网络资源，同步实现会阻塞会话线程导致 ANR。
                 */
                override fun onGetChildren(
                    session: MediaLibrarySession,
                    controller: MediaSession.ControllerInfo,
                    parentId: String,
                    page: Int,
                    pageSize: Int,
                    params: MediaLibraryService.LibraryParams?
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                    serviceScope.launch {
                        try {
                            val children = withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
                                mediaLibraryTree.loadChildren(parentId)
                            }.orEmpty()
                            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
                        } catch (e: Exception) {
                            // 超时/异常返回空列表：宁可显示为空，也不要让车机端一直转圈
                            AppLog.w("PlaybackService", "onGetChildren($parentId) failed", e)
                            future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                        }
                    }
                    return future
                }

                /**
                 * 播放入口之一：URI 解析器。
                 *
                 * 浏览树里的叶子节点只带 mediaId、**不带 URI**（见 MediaLibraryTree），
                 * 由本回调解析出可播放 URI。这是 Media3 官方设计的 URI 解析入口。
                 *
                 * ⚠️ 必须覆写：默认实现在 item 缺 LocalConfiguration（URI）时会抛
                 * `UnsupportedOperationException`。
                 * ⚠️ 返回值不能为 null —— Media3 内部 checkNotNull 会直接抛 NPE。
                 */
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> = resolveItems(mediaItems)

                /**
                 * 播放入口之二：车机点歌的统一入口。
                 *
                 * 覆写本方法后，legacy 的 playFromMediaId / playFromUri 等路径也会汇聚到这里
                 * （Media3 官方 javadoc），因此它是唯一的点歌拦截点。
                 *
                 * ⚠️ 三条铁律（均经 media3-session-1.2.1 源码确认）：
                 * 1. 返回值**不能为 null** —— MediaSessionImpl 有 checkNotNull，返回 null 直接 NPE。
                 * 2. 返回值会被 Media3 用于 `player.setMediaItems()`
                 *    （MediaUtils.setMediaItemsWithStartIndexAndPosition），
                 *    故此处**不得再调用 playerManager.playQueue()**，否则会造成重复设置。
                 * 3. 覆写后成为所有点歌路径的统一入口。
                 *
                 * 因此这里只做两件事：同步 PlayerManager 状态镜像 + 解析 URI 后返回。
                 * player 的设置与随后的 prepare/play 均由 Media3 完成。
                 */
                override fun onSetMediaItems(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                    startIndex: Int,
                    startPositionMs: Long
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    // 1) 状态镜像回流（不碰 player）—— 修复「队列与当前歌不一致」的根因。
                    //    必须完整还原才同步：否则 queue 会比 playlist 短，导致索引错位。
                    val songs = browseCache.songsOf(mediaItems.map { it.mediaId })
                    if (songs.isNotEmpty() && songs.size == mediaItems.size) {
                        (application as NasMusicApp)
                            .playerManager.syncQueueFromExternal(songs, startIndex)
                    } else {
                        AppLog.w(
                            "PlaybackService",
                            "onSetMediaItems: resolved ${songs.size}/${mediaItems.size}, skip state sync"
                        )
                    }

                    // 2) 解析 URI 后返回，由 Media3 设置 player 并自动 prepare + play
                    val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                    serviceScope.launch {
                        val resolved = try {
                            resolveItemsSuspend(mediaItems)
                        } catch (e: Exception) {
                            AppLog.w("PlaybackService", "onSetMediaItems resolve failed", e)
                            mediaItems
                        }
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(
                                resolved, startIndex, startPositionMs
                            )
                        )
                    }
                    return future
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

    // ────────────────────────────────────────────────────────────
    // Android Auto：URI 解析与调用方校验
    // ────────────────────────────────────────────────────────────

    /**
     * 把浏览树节点解析为可播放的 [MediaItem]。
     *
     * 解析策略（按成本从低到高）：
     * 1. item 已带 URI（控制器直接给了直链）→ 原样返回
     * 2. `Song.streamUrl` 非空（NAS 浏览时已填充 / 本地文件）→ 零成本，直接构造
     * 3. 否则调 `NetworkMusicManager.resolvePlayUrl()`（网络歌曲 / 百度网盘）
     *
     * 第 3 步走**公网**，与家庭内网无关，因此车机场景下依然可用。
     * 解析失败时返回原 item（无 URI），由播放时的兜底逻辑处理，不抛异常。
     */
    private suspend fun resolveItemsSuspend(items: List<MediaItem>): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val app = application as NasMusicApp
            items.map { item ->
                if (item.localConfiguration != null) return@map item
                val song = browseCache.songOf(item.mediaId) ?: return@map item

                val url = song.streamUrl?.takeIf { it.isNotBlank() }
                    ?: withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                        runCatching { app.networkMusicManager.resolvePlayUrl(song) }.getOrNull()
                    }

                if (url.isNullOrBlank()) {
                    AppLog.w("PlaybackService", "resolveItems: no url for ${song.title}")
                    item
                } else {
                    app.playerManager.buildMediaItem(song, url)
                }
            }
        }

    /**
     * [resolveItemsSuspend] 的 ListenableFuture 包装。
     *
     * ⚠️ 无论成功失败都**必须** set 一个非 null 值：Media3 对这两个回调的返回值有
     * `checkNotNull` 断言，返回 null 会直接抛 NPE（见开发方案 §4.2 铁律 1）。
     */
    private fun resolveItems(items: List<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
        val future = SettableFuture.create<MutableList<MediaItem>>()
        serviceScope.launch {
            val resolved = try {
                resolveItemsSuspend(items)
            } catch (e: Exception) {
                AppLog.w("PlaybackService", "resolveItems failed", e)
                items
            }
            future.set(resolved.toMutableList())
        }
        return future
    }

    /**
     * 调用方校验。
     *
     * 本服务 `exported="true"`（车机跨进程绑定必需），故校验来源。放行范围：
     * - 系统进程 / SystemUI（系统媒体控制、锁屏控件）
     * - AAOS 与 Android Auto 控制器（Media3 内置包名判断）
     * - 本应用自身
     * - Google 助理 / Gemini（手机端与 AAOS 端包名不同，需分别放行）
     *
     * ⚠️ Media3 的 `isAutomotiveController` / `isAutoCompanionController` 官方标注
     * "not a security validation"（只比包名、不校验签名）。对个人音乐应用该强度足够；
     * 若日后需要签名级校验，可对照官方 assistant 文档给出的证书指纹实现。
     */
    private fun isTrustedCaller(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): Boolean {
        // DEBUG 下全放行：避免白名单不全导致 DHU / 真机调试时"莫名连不上"
        if (com.nasmusic.tv.BuildConfig.DEBUG) return true

        val pkg = controller.packageName
        return controller.uid == Process.SYSTEM_UID
            || session.isAutomotiveController(controller)
            || session.isAutoCompanionController(controller)
            || pkg == packageName
            || pkg == PKG_GOOGLE_ASSISTANT
            || pkg == PKG_GOOGLE_ASSISTANT_AUTOMOTIVE
    }

    /**
     * 无 UI 依赖的 streamUrl 解析（A-13）。
     *
     * ## 背景
     *
     * `PlayerManager.onNeedResolveStreamUrl` 的实现注册在 `MainViewModel`，而
     * Android Auto / Wear OS / 蓝牙唤起等场景下 MainActivity 可能**从未启动** →
     * 回调为 null → 队列中的网络歌曲**静默播不出来**。
     *
     * ## 行为
     *
     * 解析成功 → 回写队列 → 续播；解析失败 → 回落 UI 侧回调（若存在），由其重试/跳曲。
     * 解析本身走公网（Meting 等），与家庭内网无关，车机场景下可用。
     *
     * @return true 已接管本次解析；false 表示无法处理，由 PlayerManager 回落
     */
    private fun resolveStreamUrlWithoutUi(index: Int): Boolean {
        val app = application as NasMusicApp
        val song = app.playerManager.getQueueSnapshot().getOrNull(index) ?: return false
        if (!song.streamUrl.isNullOrBlank()) return false

        uiResolveJob?.cancel()
        uiResolveJob = serviceScope.launch {
            val url = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                runCatching { app.networkMusicManager.resolvePlayUrl(song) }.getOrNull()
            }
            if (url.isNullOrBlank()) {
                AppLog.w("PlaybackService", "resolveStreamUrlWithoutUi failed: ${song.title}")
                // 回落 UI 侧（可能为 null）：由其负责重试与自动跳曲
                app.playerManager.onNeedResolveStreamUrl?.invoke(index)
                return@launch
            }
            app.playerManager.updateStreamUrl(index, url)
            app.playerManager.replayAt(index)
        }
        return true
    }

    override fun onDestroy() {
        AppLog.d("PlaybackService", "onDestroy: cleaning up")
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        stopSleepMinuteTick()
        serviceScope.cancel()
        browseCache.clear()
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
        val nextIndex = pm.playerState.value.currentIndex + 1
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

        // ── Android Auto ──
        /** 媒体树加载超时：NAS 不可达时避免车机端一直转圈 */
        private const val BROWSE_TIMEOUT_MS = 8_000L
        /** 单曲 URI 解析超时 */
        private const val RESOLVE_TIMEOUT_MS = 5_000L
        /** Google 助理（手机端）包名 */
        private const val PKG_GOOGLE_ASSISTANT = "com.google.android.googlequicksearchbox"
        /** Gemini / Google 助理（AAOS 端）包名 */
        private const val PKG_GOOGLE_ASSISTANT_AUTOMOTIVE = "com.google.android.carassistant"
        /** 睡眠定时器预设档位（分钟） */
        private val SLEEP_TIMER_PRESETS = intArrayOf(15, 30, 60, 90)
        /** 睡眠定时周期切换请求码 */
        private const val RC_SLEEP_TIMER = 9001
        private const val RC_PLAY_MODE = 9002
    }}

package com.nasmusic.tv.ui.components

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.MvInfo
import com.nasmusic.tv.data.model.MvCandidate
import com.nasmusic.tv.util.QrCodeGenerator
import com.nasmusic.tv.ui.theme.NasMusicBrushes
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.delay

/**
 * MTV（音乐视频）全屏播放页
 *
 * 与 K 歌页（KaraokePlaybackScreen）并列的独立页面，零侵入 K 歌/播放链路：
 * - **独立第二个 ExoPlayer** 播视频，不注册到 PlayerManager、不触碰 DSP/均衡器管线
 * - 进入该页时主播放器已由 MainViewModel.enterMvMode() 暂停（保留播放位置），
 *   退出时 exitMvMode() 恢复——本组件只负责视频播放器自身的生命周期
 * - 播放直链需带 B 站防盗链请求头（Referer + 浏览器 UA，与 BilibiliMvService 一致）
 * - 默认不显示歌词；「歌词」按钮切换，叠加 KaraokeLyricsView 按视频进度粗略对齐
 * - 无「原唱/伴奏」按钮（MV 音频不可分离）
 *
 * 对应 docs/mv-karaoke-feature-proposal.md §3.1 / Step 4
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MvPlaybackScreen(
    mv: MvInfo,
    lyrics: Lyrics?,
    alternatives: List<MvCandidate> = emptyList(),
    onExit: () -> Unit,
    onPlaybackError: () -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    onSwitchOrResearch: () -> Unit = {},
    onSearchBilibili: () -> Unit = {},
    onPreviousMv: () -> Unit = {},
    onNextMv: () -> Unit = {},
    mvMessage: String? = null,
    remoteControlUrl: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showMvLyrics by rememberSaveable { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var mvDurationMs by remember { mutableLongStateOf(0L) }
    var playbackError by remember { mutableStateOf(false) }
    // 播放状态标志（切歌时重置）
    var errorReported by remember { mutableStateOf(false) }
    var endedHandled by remember { mutableStateOf(false) }

    // ── 控制条自动虚化：5 秒无操作 -> 半透明（0.15），遥控器操作/焦点切换 -> 完全显化（1.0）──
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun activateControls() { lastInteraction = System.currentTimeMillis() }
    LaunchedEffect(lastInteraction) {
        controlsVisible = true
        delay(5000)
        if (System.currentTimeMillis() - lastInteraction >= 5000) controlsVisible = false
    }
    val controlsAlpha = if (controlsVisible) 1f else 0.15f

    // 手机遥控二维码（URL 不变只生成一次）
    val qrBitmap = remember(remoteControlUrl) {
        remoteControlUrl?.let { QrCodeGenerator.generateQrBitmap(it, 256) }
    }

    // ── 独立视频 ExoPlayer（页面级复用，不再因 URL 变化重建）──
    // DataSource.Factory 按 URL 域名条件注入：百度 dlink → pan.baidu.com UA/Referer；
    // B 站 → bilibili UA/Referer（BaiduHttpDataSourceFactory 内部拦截器处理，二者共用一链路）
    val exoPlayer = remember(context) {
        AppLog.d(TAG, "create video ExoPlayer (reusable instance)")
        val dataSourceFactory = com.nasmusic.tv.backend.network.baidu.BaiduHttpDataSourceFactory.create(context)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    // ── 生命周期：页面销毁时释放视频播放器 ──
    DisposableEffect(exoPlayer) {
        onDispose {
            AppLog.d(TAG, "release video ExoPlayer")
            exoPlayer.release()
        }
    }

    // ── 加载并自动播放 MV（复用实例，通过 stop+setMediaItem 切歌）──
    LaunchedEffect(exoPlayer, mv.videoUrl) {
        AppLog.d(TAG, "MV LaunchedEffect: videoUrl changed or initial, preparing new media item")
        errorReported = false
        endedHandled = false
        playbackError = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(MediaItem.fromUri(mv.videoUrl))
        exoPlayer.prepare()
        exoPlayer.play()
        AppLog.d(TAG, "MV LaunchedEffect: started playback for ${mv.videoUrl.take(60)}")
    }

    // ── 进度轮询（视频自身进度；同时驱动歌词粗略对齐与播放状态图标）──
    LaunchedEffect(exoPlayer) {
        while (true) {
            isPlaying = exoPlayer.isPlaying
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            mvDurationMs = exoPlayer.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    // ── 播放错误监听（直链失效/风控 → 显示提示不崩溃）──
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateStr = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                AppLog.d(TAG, "onPlaybackStateChanged: $stateStr")
                
                if (playbackState == Player.STATE_BUFFERING) {
                    AppLog.d(TAG, "MV buffering... currentPosition=${exoPlayer.currentPosition}, duration=${exoPlayer.duration}")
                }
                
                if (playbackState == Player.STATE_READY) {
                    AppLog.d(TAG, "MV ready to play. currentPosition=${exoPlayer.currentPosition}")
                }

                if (playbackState == Player.STATE_ENDED && !endedHandled) {
                    endedHandled = true
                    AppLog.d(TAG, "MV playback ended -> advance to next song's MV")
                    onPlaybackEnded()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                AppLog.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying, currentPosition=${exoPlayer.currentPosition}")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val reasonStr = when (reason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                    else -> "UNKNOWN($reason)"
                }
                AppLog.d(TAG, "onMediaItemTransition: reason=$reasonStr, uri=${mediaItem?.localConfiguration?.uri}")
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.e(TAG, "onPlayerError: message=${error.message}, cause=${error.cause?.message}", error)
                AppLog.e(TAG, "onPlayerError: errorCode=${error.errorCode}, currentPosition=${exoPlayer.currentPosition}")
                AppLog.e(TAG, "onPlayerError: dataSource=${exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()?.take(80)}")
                
                playbackError = true
                if (!errorReported) {
                    errorReported = true
                    AppLog.d(TAG, "onPlayerError: invoking onPlaybackError callback")
                    onPlaybackError()
                }
            }
        }
        exoPlayer.addListener(listener)
        AppLog.d(TAG, "Player.Listener added to exoPlayer")
        onDispose {
            AppLog.d(TAG, "Player.Listener removed from exoPlayer")
            exoPlayer.removeListener(listener)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { activateControls(); false }
    ) {
        // ── 全屏视频 ──
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )

        // ── 暗色渐变遮罩：底部加深保证控制条/歌词可读（随控制条虚化）──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(controlsAlpha)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x33000000),
                            Color(0x00000000),
                            Color(0xCC000000)
                        )
                    )
                )
        )

        // ── 手机遥控二维码（右上角，跟随控制条虚化）──
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "扫码遥控",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .size(96.dp)
                    .alpha(if (controlsVisible) 1f else 0f)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(4.dp)
            )
        }

        // ── 歌词浮层（默认隐藏；「歌词」按钮切换）──
        if (showMvLyrics && lyrics != null && lyrics.lines.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 48.dp, bottom = 96.dp)
                    .background(Color(0x66000000), RoundedCornerShape(12.dp))
            ) {
                KaraokeLyricsView(
                    lyrics = lyrics,
                    progressMs = positionMs,
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                )
            }
        }

        // ── 播放失败提示 ──
        if (playbackError) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "视频加载失败，按返回键退出",
                    fontSize = 20.sp,
                    color = Color.White
                )
            }
        }

        // ── 短暂提示（切换失败/搜索结果等，顶部居中 2 秒）──
        if (mvMessage != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .alpha(controlsAlpha)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text(text = mvMessage, fontSize = 18.sp, color = Color.White)
            }
        }

        // ── 底部内容：控制条 + 进度细线（5 秒无操作虚化，焦点/操作时显化）──
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, bottom = 20.dp)
                .onFocusChanged { if (it.hasFocus) activateControls() }
                .alpha(controlsAlpha)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回（退出 MTV 页 → MainViewModel 恢复主播放器）
                MiniIconButton(
                    onClick = { activateControls(); onExit() },
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )

                // 控制组：播放/暂停 + 歌词开关
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    MiniIconButton(
                        onClick = { activateControls(); onPreviousMv() },
                        icon = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous"
                    )
                    Spacer(Modifier.width(20.dp))
                    MiniIconButton(
                        onClick = {
                            activateControls()
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                    Spacer(Modifier.width(20.dp))
                    MiniIconButton(
                        onClick = { activateControls(); onNextMv() },
                        icon = Icons.Filled.SkipNext,
                        contentDescription = "Next"
                    )
                    Spacer(Modifier.width(20.dp))
                    VocalToggleButton(
                        label = "歌词",
                        onClick = { activateControls(); showMvLyrics = !showMvLyrics }
                    )
                    Spacer(Modifier.width(20.dp))
                    VocalToggleButton(
                        label = "切换",
                        onClick = { activateControls(); onSwitchOrResearch() }
                    )
                    // 当前 MV 来自百度网盘本地文件时，提供「搜B站」兜底入口：
                    // 强制从非百度源（B 站）重新搜索，见 MvSearchManager.searchBilibiliFallback
                    if (mv.source == "baidu") {
                        Spacer(Modifier.width(20.dp))
                        VocalToggleButton(
                            label = "搜B站",
                            onClick = { activateControls(); onSearchBilibili() }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 进度细线（样式与 K 歌页一致：轨道 + 渐变填充）
            val mvProgress = if (mvDurationMs > 0L) {
                (positionMs.toFloat() / mvDurationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(NasMusicColors.SurfaceVariant.copy(alpha = 0.6f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(mvProgress)
                        .fillMaxHeight()
                        .background(NasMusicBrushes.progressBar)
                )
            }
        }
    }
}

/**
 * 简易图标按钮（MTV 页控制条，与 KARAOKE 页同款样式）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MiniIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(56.dp)
            .background(Color.Transparent),
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(50),
            focusedShape = RoundedCornerShape(50)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NasMusicColors.Surface,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
            focusedContentColor = Color.Black
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

private const val TAG = "MvPlaybackScreen"

/** 浏览器 UA（与 BilibiliMvService 一致，防盗链校验之一） */
private const val BILIBILI_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
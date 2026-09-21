package com.nasmusic.tv.ui.screens

import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.components.common.SourceBadge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsHighlightMode
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.isRadioSong
import com.nasmusic.tv.ui.RegisterDialogBackHandler
import com.nasmusic.tv.ui.components.LyricsView
import com.nasmusic.tv.ui.components.CoverCarousel
import com.nasmusic.tv.ui.components.ControlButtonsRow
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.KaraokePlaybackScreen
import com.nasmusic.tv.ui.components.ProgressSection
import com.nasmusic.tv.ui.components.SongInfoPanel
import com.nasmusic.tv.ui.components.PHONE_TOUCH_TARGET
import com.nasmusic.tv.ui.components.portraitTouchTarget
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 正在播放屏幕（主界面）
 * 左侧：专辑封面 + 歌曲信息（可聚焦）
 * 右侧：滚动歌词（可聚焦）
 * 底部：播放控制（可聚焦）
 * 使用 TV 标准 Surface 焦点管理模式
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    currentSong: Song?,
    isPlaying: Boolean,
    playMode: PlayMode,
    progressMs: Long,
    durationMs: Long,
    lyrics: Lyrics?,
    lyricsAvailability: com.nasmusic.tv.data.model.LyricsAvailability,
    coverCandidates: List<String> = emptyList(),
    highlightMode: LyricsHighlightMode = LyricsHighlightMode.LINE_BY_LINE,
    isFavorite: Boolean = false,
    isImmersiveMode: Boolean = false,
    onToggleImmersive: () -> Unit = {},
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayMode: () -> Unit,
    onSeek: (Long) -> Unit,
    onSwitchLyricsSource: (com.nasmusic.tv.data.model.LyricsSource) -> Unit,
    onChangeHighlightMode: (LyricsHighlightMode) -> Unit = {},
    onToggleFavorite: (() -> Unit)? = null,
    lyricsFontScale: Float = 1.0f,
    onLyricsFontScaleChange: (Float) -> Unit = {},
    coverFilterEnabled: Boolean = false,
    coverFilterBlurRadius: Float = 8f,
    coverFilterDarkOverlay: Float = 0.3f,
    // 歌曲详情信息
    technicalInfo: com.nasmusic.tv.data.model.SongTechnicalInfo? = null,
    onLoadTechnicalInfo: () -> Unit = {},
    // === K 歌页面状态（由 ViewModel 管理，切 Tab 时保持） ===
    showKaraoke: Boolean = false,
    onEnterKaraoke: () -> Unit = {},
    onEnterVisualizer: () -> Unit = {},
    onExitKaraoke: () -> Unit = {},
    // === KARAOKE 人声消除 ===
    vocalRemovalEnabled: Boolean = false,
    onToggleVocalRemoval: () -> Unit = {},
    // === MTV 音乐视频 ===
    mvAvailable: Boolean = false,
    onEnterMv: () -> Unit = {},
    remoteControlUrl: String? = null,
    /** 点击歌手名跳转到网络搜索 */
    onSearchArtist: (String) -> Unit = {},
    /** 点击歌曲名跳转到网络搜索 */
    onSearchSong: (String) -> Unit = {},
    // === v2.35.0 多码率：音质档位（方案 §5.1） ===
    /** 当前生效档位（单曲覆盖 ?: 全局默认） */
    qualityTier: Int = 0,
    /** 用户确认档位 + 生效范围 */
    onChangeQuality: (tier: Int, scope: com.nasmusic.tv.backend.network.QualityScope) -> Unit = { _, _ -> },
    // === K 歌页面：升降调 / 变速（全局记忆） ===
    pitchSemitones: Int = 0,
    playbackSpeed: Double = 1.0,
    onSetPitch: (Int) -> Unit = {},
    onSetSpeed: (Double) -> Unit = {},
    onResetPitch: () -> Unit = {},
    onResetSpeed: () -> Unit = {},
    // === 分离模式（快速/高质量） ===
    isHighQualityMode: Boolean = false,
    isSeparating: Boolean = false,
    separationProgress: Pair<Float, String> = 0f to "",
    /** 高质量分离错误信息（非空时 UI 应显示错误提示） */
    hqError: String? = null,
    onToggleSeparationMode: () -> Unit = {},
    /** 清除高质量分离错误 */
    onClearHqError: () -> Unit = {},
    /** 高质量分离成功信息（非空时 UI 应显示成功提示） */
    hqSuccess: String? = null,
    /** 清除高质量分离成功信息 */
    onClearHqSuccess: () -> Unit = {},
    /** 高质量分离模型是否已下载（未下载时禁用高质量切换） */
    modelDownloaded: Boolean = false,
    // === F2-2 睡眠定时器 ===
    /** 睡眠定时器状态（null = 未启用） */
    sleepTimerState: com.nasmusic.tv.player.SleepTimerController.State? = null,
    /** 选择档位后启动定时 */
    onSleepTimerStart: (Int) -> Unit = {},
    /** 取消定时 */
    onSleepTimerCancel: () -> Unit = {},
    // === v2.36.0 竖屏（方案 §4.2）===
    /** 竖屏顶栏「收起」：回到首页（播放继续，由 MiniPlayer 承载） */
    onCollapse: () -> Unit = {},
    /** 竖屏「队列」入口 */
    onOpenQueue: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showInfoPanel by remember { mutableStateOf(false) }
    // v2.35.0 多码率：音质选择面板显隐
    var showQualityDialog by remember { mutableStateOf(false) }
    val qualityLabel = if (currentSong?.isNetworkSong == true) {
        stringResource(com.nasmusic.tv.backend.network.QualityTiers.labelResOf(qualityTier))
    } else ""
    // F2-2b：睡眠定时弹窗显隐（按钮在歌词来源行 A+ 右侧）
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    val playPauseFocusRequester = remember { FocusRequester() }

    // ── 全屏 KARAOKE 页面 ──
    if (showKaraoke) {
        KaraokePlaybackScreen(
            currentSong = currentSong,
            isPlaying = isPlaying,
            lyrics = lyrics,
            coverCandidates = coverCandidates,
            coverFilterEnabled = coverFilterEnabled,
            coverFilterBlurRadius = coverFilterBlurRadius,
            progressMs = progressMs,
            durationMs = durationMs,
            vocalRemovalEnabled = vocalRemovalEnabled,
            onToggleVocalRemoval = onToggleVocalRemoval,
            onExitKaraoke = { onExitKaraoke() },
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            pitchSemitones = pitchSemitones,
            playbackSpeed = playbackSpeed,
            onSetPitch = onSetPitch,
            onSetSpeed = onSetSpeed,
            onResetPitch = onResetPitch,
            onResetSpeed = onResetSpeed,
            isHighQualityMode = isHighQualityMode,
            isSeparating = isSeparating,
            separationProgress = separationProgress,
            hqError = hqError,
            onToggleSeparationMode = onToggleSeparationMode,
            onClearHqError = onClearHqError,
            hqSuccess = hqSuccess,
            onClearHqSuccess = onClearHqSuccess,
            modelDownloaded = modelDownloaded,
            playPauseFocusRequester = playPauseFocusRequester,
            remoteControlUrl = remoteControlUrl
        )
        return
    }

    // ── 手机竖屏：双模式（封面 / 歌词）+ 左右滑切换（v2.36.0，方案 §4.2） ──
    // ⚠️ 只在 PhonePortrait 生效；TV 与手机横屏走下方现状布局（B1 硬规则）
    if (com.nasmusic.tv.ui.theme.LocalUiMode.current == com.nasmusic.tv.ui.theme.UiMode.PhonePortrait) {
        NowPlayingPortrait(
            currentSong = currentSong,
            isPlaying = isPlaying,
            playMode = playMode,
            progressMs = progressMs,
            durationMs = durationMs,
            lyrics = lyrics,
            lyricsAvailability = lyricsAvailability,
            coverCandidates = coverCandidates,
            highlightMode = highlightMode,
            isFavorite = isFavorite,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            onTogglePlayMode = onTogglePlayMode,
            onSeek = onSeek,
            onSwitchLyricsSource = onSwitchLyricsSource,
            onChangeHighlightMode = onChangeHighlightMode,
            onToggleFavorite = onToggleFavorite,
            lyricsFontScale = lyricsFontScale,
            onLyricsFontScaleChange = onLyricsFontScaleChange,
            technicalInfo = technicalInfo,
            onLoadTechnicalInfo = onLoadTechnicalInfo,
            onEnterKaraoke = onEnterKaraoke,
            onEnterVisualizer = onEnterVisualizer,
            mvAvailable = mvAvailable,
            onEnterMv = onEnterMv,
            qualityTier = qualityTier,
            qualityLabel = qualityLabel,
            onChangeQuality = onChangeQuality,
            sleepTimerState = sleepTimerState,
            onSleepTimerStart = onSleepTimerStart,
            onSleepTimerCancel = onSleepTimerCancel,
            onSearchSong = onSearchSong,
            onSearchArtist = onSearchArtist,
            coverFilterEnabled = coverFilterEnabled,
            coverFilterBlurRadius = coverFilterBlurRadius,
            coverFilterDarkOverlay = coverFilterDarkOverlay,
            isImmersiveMode = isImmersiveMode,
            onToggleImmersive = onToggleImmersive,
            onCollapse = onCollapse,
            onOpenQueue = onOpenQueue,
            playPauseFocusRequester = playPauseFocusRequester,
        )
        return
    }

    // 进入 NowPlaying 页面时自动聚焦播放/暂停按钮
    LaunchedEffect(Unit) {
        try {
              withFrameNanos { }
              playPauseFocusRequester.requestFocus()
          } catch (_: Exception) {
              // 焦点请求失败时忽略
          }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                // 沉浸模式：整屏纯黑（左半封面右缘渐黑 + 右半歌词区均落在黑底上，无缝衔接）
                // 普通模式：保持原有纵向渐变背景
                if (isImmersiveMode) {
                    Brush.verticalGradient(listOf(Color.Black, Color.Black))
                } else {
                    Brush.verticalGradient(
                        listOf(
                            NasMusicColors.Background,
                            Color(0xFF0A1020)
                        )
                    )
                }
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 24.dp)
        ) {
            // 中部：专辑封面(1/3) + 歌词(2/3)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(if (isImmersiveMode) 24.dp else 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：
                // - 沉浸模式 = 占满左半屏的大封面，图片右缘虚化并渐变到黑
                // - 普通模式 = 封面 + 歌曲信息（原布局）
                if (isImmersiveMode) {
                    ImmersiveCoverHalf(
                        currentSong = currentSong,
                        coverCandidates = coverCandidates,
                        isPlaying = isPlaying,
                        coverFilterEnabled = coverFilterEnabled,
                        coverFilterBlurRadius = coverFilterBlurRadius,
                        onExitImmersive = onToggleImmersive,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .width(380.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 封面内容直接平铺（不可用 weight：verticalScroll 容器内 weight 无效）
                        CoverColumn(
                            currentSong = currentSong,
                            onToggleImmersive = onToggleImmersive,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                            coverCandidates = coverCandidates,
                            isPlaying = isPlaying,
                            coverFilterEnabled = coverFilterEnabled,
                            coverFilterBlurRadius = coverFilterBlurRadius,
                            coverFilterDarkOverlay = coverFilterDarkOverlay,
                            technicalInfo = technicalInfo,
                            onLoadTechnicalInfo = onLoadTechnicalInfo,
                            showInfoPanel = showInfoPanel,
                            onToggleInfoPanel = { showInfoPanel = !showInfoPanel },
                            onSearchSong = onSearchSong,
                            onSearchArtist = onSearchArtist,
                            // v2.35.0 多码率：音质入口放在「信息 + 来源」行
                            qualityLabel = qualityLabel,
                            onOpenQuality = { showQualityDialog = true }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 播放控制按钮（置于封面图下方，歌词框不变）
                        ControlButtonsRow(
                            isPlaying = isPlaying,
                            playMode = playMode,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                            onPrevious = onPrevious,
                            onTogglePlayMode = onTogglePlayMode,
                            showVisualizerButton = currentSong != null,
                            onEnterVisualizer = onEnterVisualizer,
                            showVocalButton = currentSong != null,
                            onEnterKaraoke = onEnterKaraoke,
                            showMvButton = true,
                            mvAvailable = mvAvailable,
                            onEnterMv = onEnterMv,
                            compact = true,
                            playPauseFocusRequester = playPauseFocusRequester
                        )
                    }
                }

                // 右侧：歌词（沉浸模式下占右半屏，纯黑背景上显示歌词）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (isImmersiveMode) Modifier.background(Color.Black)
                            else Modifier
                        )
                ) {
                    // 歌词来源标签和高亮模式切换（可聚焦 — 保留 Surface）
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        val currentSource = lyrics?.source
                        SourceTag(
                            label = com.nasmusic.tv.data.model.LyricsSource.EMBEDDED.displayName,
                            available = lyricsAvailability.hasBackend,
                            selected = currentSource == com.nasmusic.tv.data.model.LyricsSource.EMBEDDED,
                            onClick = { onSwitchLyricsSource(com.nasmusic.tv.data.model.LyricsSource.EMBEDDED) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        SourceTag(
                            label = com.nasmusic.tv.data.model.LyricsSource.LOCAL_FILE.displayName,
                            available = currentSource == com.nasmusic.tv.data.model.LyricsSource.LOCAL_FILE,
                            selected = currentSource == com.nasmusic.tv.data.model.LyricsSource.LOCAL_FILE,
                            onClick = { }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        SourceTag(
                            label = com.nasmusic.tv.data.model.LyricsSource.NETWORK.displayName,
                            available = true,
                            selected = currentSource == com.nasmusic.tv.data.model.LyricsSource.NETWORK,
                            onClick = { onSwitchLyricsSource(com.nasmusic.tv.data.model.LyricsSource.NETWORK) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        SourceTag(
                            label = com.nasmusic.tv.data.model.LyricsSource.CACHED.displayName,
                            available = lyricsAvailability.hasCached,
                            selected = currentSource == com.nasmusic.tv.data.model.LyricsSource.CACHED,
                            onClick = { onSwitchLyricsSource(com.nasmusic.tv.data.model.LyricsSource.CACHED) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        // 高亮模式切换按钮
                        SourceTag(
                            label = if (highlightMode == LyricsHighlightMode.WORD_BY_WORD) stringResource(R.string.player_highlight_word) else stringResource(R.string.player_highlight_line),
                            available = true,
                            selected = highlightMode == LyricsHighlightMode.WORD_BY_WORD,
                            onClick = {
                                val newMode = if (highlightMode == LyricsHighlightMode.WORD_BY_WORD) {
                                    LyricsHighlightMode.LINE_BY_LINE
                                } else {
                                    LyricsHighlightMode.WORD_BY_WORD
                                }
                                onChangeHighlightMode(newMode)
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        // 歌词字体大小切换
                        val fontLabels = listOf("A", "A+", "A++", "A+++")
                        val fontScaleIdx = when (lyricsFontScale) {
                            0.7f -> 0
                            1.0f -> 1
                            1.3f -> 2
                            1.6f -> 3
                            else -> 1
                        }
                        SourceTag(
                            label = fontLabels[fontScaleIdx],
                            available = true,
                            selected = false,
                            onClick = {
                                val scales = listOf(0.7f, 1.0f, 1.3f, 1.6f)
                                val next = (fontScaleIdx + 1) % scales.size
                                onLyricsFontScaleChange(scales[next])
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        // F2-2b：睡眠定时按钮（A+ 右侧同排；未启动"定时 -"、运行中橙色剩余分钟）
                        SourceTag(
                            label = when (val st = sleepTimerState) {
                                is com.nasmusic.tv.player.SleepTimerController.State.Running ->
                                    stringResource(
                                        R.string.np_sleep_timer_on,
                                        ((st.endsAtMs - System.currentTimeMillis() + 59_999) / 60_000)
                                            .toInt().coerceAtLeast(1)
                                    )
                                else -> stringResource(R.string.np_sleep_timer_off)
                            },
                            available = true,
                            selected = sleepTimerState is com.nasmusic.tv.player.SleepTimerController.State.Running,
                            onClick = { showSleepTimerDialog = true }
                        )
                    }

                    // F2-2b：睡眠定时弹窗（输入框 -/+ 步进 + 15/30 快捷档 + 取消）
                    if (showSleepTimerDialog) {
                        SleepTimerPickerDialog(
                            onStart = { min ->
                                showSleepTimerDialog = false
                                onSleepTimerStart(min)
                            },
                            onCancel = {
                                showSleepTimerDialog = false
                                onSleepTimerCancel()
                            },
                            onDismiss = { showSleepTimerDialog = false }
                        )
                    }

                    // v2.35.0 多码率：音质选择面板（方案 §5.1）
                    if (showQualityDialog) {
                        com.nasmusic.tv.ui.components.QualityPickerDialog(
                            currentTier = qualityTier,
                            onConfirm = { tier, scope ->
                                showQualityDialog = false
                                onChangeQuality(tier, scope)
                            },
                            onDismiss = { showQualityDialog = false }
                        )
                    }

                    // 歌词内容区域（沉浸模式移除半透明背景，避免与封面遮罩叠加）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                if (isImmersiveMode) Color.Transparent
                                else NasMusicColors.Surface.copy(alpha = 0.3f),
                                RoundedCornerShape(8.dp)
                            )
                    ) {
                        if (currentSong?.networkSource?.isRadioSong() == true) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.player_radio_live),
                                    fontSize = FontSize.title(),
                                    fontWeight = FontWeight.Bold,
                                    color = NasMusicColors.Primary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        } else {
                            LyricsView(
                                lyrics = lyrics,
                                currentTimeMs = progressMs,
                                highlightMode = highlightMode,
                                isPlaying = isPlaying,
                                fontSizeMultiplier = lyricsFontScale,
                                // 沉浸模式歌词区为纯黑底 → 渐隐遮罩同色，避免出现深蓝渐变边
                                fadeMaskColor = if (isImmersiveMode) Color(0xCC000000) else null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 注：原 48dp 小频谱条已移除——入口改为播放控件上的「频谱」按钮，
            // 进入独立全屏舞台（VisualizerStage），20 套效果更震撼。

            // 进度条（全宽，底部对齐）— Task 2；直播态（电台）禁用 seek
            ProgressSection(
                progressMs = progressMs,
                durationMs = durationMs,
                onSeek = onSeek,
                compact = true,
                isLive = currentSong?.networkSource?.isRadioSong() == true
            )
        }
    }
}

/** 沉浸模式左半屏封面：右缘虚化的默认模糊半径（dp）——未开启「封面滤镜」时使用。 */
private const val IMMERSIVE_EDGE_BLUR_DP = 24f

/**
 * 沉浸模式左半屏封面（占左侧一半空间）。
 *
 * 视觉构成（自下而上三层）：
 * 1. 封面原图，[ContentScale.Crop] 铺满左半区（保持清晰，作为主体）
 * 2. 同一封面的模糊副本，用水平渐变 alpha 遮罩只在右侧显现 —— 形成「右缘虚化」。
 *    `Modifier.blur` 在 API < 31 上是 no-op（电视 SDK 22 即如此），此时该层自动
 *    退化为纯渐变、不会报错，效果等同单层渐变遮罩。
 * 3. 水平渐变黑幕：左侧全透明 → 右缘纯黑，与右半屏歌词区的纯黑背景无缝衔接。
 * 4. 右侧虚化区**竖排**歌曲信息：最右一列 = 歌曲名（白色加粗），其左侧一列 = 艺术家
 *    （主题青色），两列均**上对齐**。放这里是刻意的——第 3 层已把该区域压到 ≥83% 黑，
 *    底色确定为暗色，文字就能固定用亮色，不必担心与任意封面撞色。
 *
 * 点击（TV OK 键 / 手机触摸）退出沉浸模式，回到普通播放页。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ImmersiveCoverHalf(
    currentSong: Song?,
    coverCandidates: List<String>,
    isPlaying: Boolean,
    coverFilterEnabled: Boolean,
    coverFilterBlurRadius: Float,
    onExitImmersive: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 虚化半径沿用用户的「封面滤镜」设置；未开启时给一个温和的默认值
    val edgeBlurDp = if (coverFilterEnabled && coverFilterBlurRadius > 0f) {
        coverFilterBlurRadius
    } else {
        IMMERSIVE_EDGE_BLUR_DP
    }
    // ③ 右缘渐黑：0.55 处仍全透明，1.0 处纯黑 —— 保证与右侧黑底歌词区无缝
    val edgeFadeBrush = remember {
        Brush.horizontalGradient(
            0.55f to Color.Transparent,
            0.80f to Color.Black.copy(alpha = 0.72f),
            1f to Color.Black
        )
    }
    // ② 模糊副本的显现遮罩：0.45 处不可见 → 1.0 处完全显现
    // （DstIn 只保留渐变不透明的区域，从而让虚化"渐进"出现）
    val blurMaskBrush = remember {
        Brush.horizontalGradient(
            0.45f to Color.Transparent,
            0.72f to Color.Black.copy(alpha = 0.85f),
            1f to Color.Black
        )
    }

    FocusableSurface(
        onClick = onExitImmersive,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        // 已占满左半屏，焦点缩放会溢出裁切，故不做缩放反馈
        focusedScale = 1f,
        pressedScale = 1f,
        animationDurationMs = 150,
        containerColor = Color.Black,
        focusedContainerColor = Color.Black,
        contentColor = Color.Transparent,
        focusedContentColor = Color.Transparent,
        showFocusBorder = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        ) {
            // ① 封面原图（清晰主体）
            key(currentSong?.id) {
                CoverCarousel(
                    coverCandidates = coverCandidates,
                    isPlaying = isPlaying,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ② 右缘虚化层（模糊副本 + 水平渐变遮罩）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 遮罩需要离屏层，否则 DstIn 会作用到已绘制的整屏内容
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(brush = blurMaskBrush, blendMode = BlendMode.DstIn)
                    }
            ) {
                key(currentSong?.id) {
                    CoverCarousel(
                        coverCandidates = coverCandidates,
                        isPlaying = isPlaying,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(edgeBlurDp.dp)
                    )
                }
            }

            // ③ 右缘渐变到黑
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(edgeFadeBrush)
            )

            // ④ 右侧虚化区竖排歌曲信息：最右一列 = 歌曲名，其左侧一列 = 艺术家，均上对齐。
            //    选这里的理由：③ 已把该区域压到 ≥83% 黑（0.80 处 0.72 → 1.0 处纯黑），
            //    底色**确定是暗的** → 文字可以固定用亮色（白 / Primary），
            //    不必像压在封面上那样担心任意底色撞色。
            val songTitle = currentSong?.title?.takeIf { it.isNotBlank() }
            val songArtist = currentSong?.artist?.takeIf { it.isNotBlank() }
            if (songTitle != null || songArtist != null) {
                BoxWithConstraints(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight()
                        .padding(top = 36.dp, end = 26.dp, bottom = 36.dp)
                ) {
                    val titleSize = FontSize.title()
                    val artistSize = FontSize.body()
                    val density = LocalDensity.current
                    // 单字行高 = 字号 × 1.15（Compose 1.6 起 includeFontPadding 默认 false，
                    // 无需 PlatformTextStyle）；按可用高度反推最多能放几个字，超出补「…」
                    val titleCharH = with(density) { titleSize.toDp() } * VERTICAL_LINE_HEIGHT_RATIO
                    val artistCharH = with(density) { artistSize.toDp() } * VERTICAL_LINE_HEIGHT_RATIO
                    val maxTitleChars = (maxHeight / titleCharH).toInt().coerceIn(2, 18)
                    val maxArtistChars = (maxHeight / artistCharH).toInt().coerceIn(2, 18)
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 左列：艺术家
                        if (songArtist != null) {
                            VerticalText(
                                text = songArtist,
                                color = NasMusicColors.Primary,
                                fontSize = artistSize,
                                maxChars = maxArtistChars
                            )
                        }
                        // 右列（最右）：歌曲名
                        if (songTitle != null) {
                            VerticalText(
                                text = songTitle,
                                color = Color.White,
                                fontSize = titleSize,
                                fontWeight = FontWeight.Bold,
                                maxChars = maxTitleChars
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 竖排文字的单字行高倍数（见 [VerticalText]：把行高固定下来，竖列才不松散）。 */
private const val VERTICAL_LINE_HEIGHT_RATIO = 1.15f

/**
 * 竖排文字（CJK 习惯：单字自上而下成列，列自右向左排）。
 *
 * Compose **没有原生竖排能力**（`TextStyle` 没有 writing-mode），这里按 **码点** 拆字后逐字
 * 堆一列 `Text`。两个实现要点：
 * - 按码点而不是按 `Char` 拆：`String.toList()` 会把 emoji / 生僻字的**代理对拆成两个乱码**。
 *   用 `Character.codePointAt` + `charCount` 步进（`java.lang.Character` 是 API 1 就有，
 *   minSdk 22 无压力；**不能用 `String.codePoints()`**——它返回 `IntStream`，
 *   `java.util.stream` 是 API 24+）。
 * - 显式设 `lineHeight = 字号 × [VERTICAL_LINE_HEIGHT_RATIO]`：Compose 1.6 起
 *   `includeFontPadding` 默认已是 false（见 ui-text 1.6.1 的 `DefaultIncludeFontPadding`），
 *   所以**不需要** `PlatformTextStyle` 那套实验性 API 就能得到紧凑的竖列。
 *
 * [maxChars] 由调用方按可用高度算出，超出时末字替换为「…」，保证永不溢出封面。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VerticalText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    maxChars: Int,
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier
) {
    val chars = remember(text, maxChars) { splitToCodePoints(text, maxChars) }
    val lineHeight = fontSize * VERTICAL_LINE_HEIGHT_RATIO
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        chars.forEach { ch ->
            Text(
                text = ch,
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                lineHeight = lineHeight,
                maxLines = 1
            )
        }
    }
}

/** 按 Unicode 码点切分 [text]，最多 [maxChars] 个；被截断时最后一个替换为「…」。 */
internal fun splitToCodePoints(text: String, maxChars: Int): List<String> {
    val out = ArrayList<String>(maxChars)
    var i = 0
    while (i < text.length && out.size < maxChars) {
        val cp = Character.codePointAt(text, i)
        out.add(StringBuilder().append(Character.toChars(cp)).toString())
        i += Character.charCount(cp)
    }
    if (i < text.length && out.isNotEmpty()) {
        out[out.size - 1] = "…"
    }
    return out
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CoverColumn(
    currentSong: Song?,
    onToggleImmersive: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    coverCandidates: List<String> = emptyList(),
    isPlaying: Boolean = false,
    coverFilterEnabled: Boolean = false,
    coverFilterBlurRadius: Float = 8f,
    coverFilterDarkOverlay: Float = 0.3f,
    technicalInfo: com.nasmusic.tv.data.model.SongTechnicalInfo? = null,
    onLoadTechnicalInfo: () -> Unit = {},
    showInfoPanel: Boolean = false,
    onToggleInfoPanel: () -> Unit = {},
    onSearchSong: (String) -> Unit = {},
    onSearchArtist: (String) -> Unit = {},
    // v2.35.0 多码率：音质入口（放在「信息 + 来源」同一行，方案 §5.1）
    qualityLabel: String = "",
    onOpenQuality: () -> Unit = {}
) {
    Column(
        modifier = Modifier.width(300.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 歌曲标题 + 收藏按钮
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 歌曲名可聚焦，点击跳转到网络搜索
            val title = currentSong?.title
            if (!title.isNullOrBlank()) {
                FocusableSurface(
                    onClick = { onSearchSong(title) },
                    shape = RoundedCornerShape(6.dp),
                    focusedScale = 1.05f,
                    animationDurationMs = 150,
                    containerColor = Color.Transparent,
                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
                    contentColor = NasMusicColors.TextPrimary,
                    focusedContentColor = NasMusicColors.Primary
                ) {
Text(
                        text = title,
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.title(),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.player_no_song_selected),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.title(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
            if (onToggleFavorite != null && currentSong != null) {
                FavoriteButton(
                    isFavorite = isFavorite,
                    onClick = onToggleFavorite,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        // 任务 3: 专辑名移至封面图上方
        if (!currentSong?.album.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = currentSong?.album ?: "",
                color = NasMusicColors.TextSecondary.copy(alpha = 0.7f),
                fontSize = FontSize.button(),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 封面 / 信息面板切换（按下"信息"后占用封面空间显示歌曲详情）
        if (showInfoPanel) {
            SongInfoPanel(
                song = currentSong,
                technicalInfo = technicalInfo,
                onDismiss = onToggleInfoPanel,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            // 可聚焦的封面容器 — OK 键切换沉浸模式
            FocusableSurface(
                onClick = onToggleImmersive,
                modifier = Modifier.size(240.dp + 40.dp),
                shape = RoundedCornerShape(20.dp),
                focusedScale = 1.05f,
                animationDurationMs = 150,
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                contentColor = Color.Transparent,
                pressedScale = 0.97f
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // 发光光晕
                    Box(
                        modifier = Modifier
                            .size(240.dp + 20.dp)
                            .background(NasMusicColors.AccentGlow, shape = RoundedCornerShape(50.dp))
                    )
                    // 实际封面（使用 CoverCarousel 组件，支持多封面轮播）
                    // 注意：封面滤镜仅在全屏沉浸模式生效，不在普通播放界面应用
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(NasMusicColors.Surface)
                    ) {
                        key(currentSong?.id) {
                            CoverCarousel(
                                coverCandidates = coverCandidates,
                                isPlaying = isPlaying,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 艺术家（Task 3: 专辑名已移至封面图上方，此处只显示艺术家）
        // 可聚焦，点击跳转到网络搜索
        val artist = currentSong?.artist?.takeIf { it.isNotBlank() }
        if (artist != null) {
            FocusableSurface(
                onClick = { onSearchArtist(artist) },
                shape = RoundedCornerShape(6.dp),
                focusedScale = 1.05f,
                animationDurationMs = 150,
                containerColor = Color.Transparent,
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.12f),
                contentColor = NasMusicColors.TextPrimary,
                focusedContentColor = NasMusicColors.Primary
            ) {
Text(
                    text = artist,
                    color = LocalFocusableContentColor.current,
                    fontSize = FontSize.button(),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        } else {
            Text(
                text = "—",
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.button(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 信息按钮（封面/信息切换）+ 歌曲来源 + 音质入口
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            // 手机窄屏下三者（信息 / 来源 / 音质）可能超出 380dp，
            // 加横向滚动保证音质入口始终可达（与 ControlButtonsRow 同样的处理）
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FocusableSurface(
                onClick = {
                    if (!showInfoPanel) onLoadTechnicalInfo()
                    onToggleInfoPanel()
                },
                shape = RoundedCornerShape(6.dp),
                focusedScale = 1.08f,
                animationDurationMs = 150,
                containerColor = if (showInfoPanel) NasMusicColors.Primary.copy(alpha = 0.2f)
                                 else NasMusicColors.Surface.copy(alpha = 0.3f),
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                contentColor = if (showInfoPanel) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                focusedContentColor = NasMusicColors.Primary
            ) {
                Text(
                    text = if (showInfoPanel) stringResource(R.string.player_cover) else stringResource(R.string.player_info),
                    color = if (showInfoPanel) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                    fontSize = FontSize.small(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            // 歌曲来源标识：统一走 MusicSourceType / SourceBadge 体系
            // （修复：原为硬编码 "NET"，百度网盘 / Jamendo / 电台等一律显示 NET）
            if (currentSong != null) {
                Spacer(modifier = Modifier.width(8.dp))
                SourceBadge(song = currentSong)
            }
            // v2.35.0 多码率：音质标识，与「信息 + 来源」同一行（方案 §5.1）。
            //
            // 两种形态：
            // - **网络歌曲**：可点击 → 弹出音质选择面板（显示当前档位标签，如「无损」/「320k」）
            // - **本地 / NAS / 下载歌曲**：只读展示**真实码率**（如「♪ 320k」/「♪ FLAC」），
            //   不可点击 —— 这类歌曲的码率由文件本身决定，无法切换，但用户理应看得到。
            //   数据来自 `Song.bitrate`（NAS 适配器解析时填充；飞牛为 0=未知，不显示）
            //   与 `Song.resolvedQuality`（已下载歌曲的实际落盘档位）。
            //
            // 放在这一行而不是底部控制按钮行：底部行在 380dp 宽度下已容纳 7 个控件，
            // 第 8 个会被裁掉（手机端实测看不到入口）。
            val qualityBadgeText = com.nasmusic.tv.ui.components.qualityBadgeLabel(currentSong, qualityLabel)
            if (qualityBadgeText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                if (currentSong?.isNetworkSong == true) {
                    // 可点击：网络歌曲支持切换档位
                    FocusableSurface(
                        onClick = onOpenQuality,
                        shape = RoundedCornerShape(6.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 150,
                        containerColor = NasMusicColors.Surface.copy(alpha = 0.3f),
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                        contentColor = NasMusicColors.Primary,
                        focusedContentColor = NasMusicColors.Primary
                    ) {
                        Text(
                            text = qualityBadgeText,
                            color = NasMusicColors.Primary,
                            fontSize = FontSize.small(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    // 只读：本地/NAS 歌曲码率固定，展示但不可点击
                    Text(
                        text = qualityBadgeText,
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small(),
                        modifier = Modifier
                            .background(
                                NasMusicColors.Surface.copy(alpha = 0.3f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FavoriteButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.12f,
        animationDurationMs = 150,
        containerColor = if (isFavorite) NasMusicColors.Warning.copy(alpha = 0.2f) else Color.Transparent,
        focusedContainerColor = NasMusicColors.Warning.copy(alpha = 0.3f),
        contentColor = if (isFavorite) NasMusicColors.Warning else NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Warning,
        pressedScale = 0.95f
    ) {
        Text(
            text = if (isFavorite) "♥" else "♡",
            color = LocalFocusableContentColor.current,
            fontSize = FontSize.subtitle(),
            modifier = Modifier.padding(10.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourceTag(
    label: String,
    available: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = { if (available) onClick() },
        modifier = Modifier,
        shape = RoundedCornerShape(6.dp),
        focusedScale = 1.1f,
        animationDurationMs = 150,
        containerColor = if (!available) NasMusicColors.Surface.copy(alpha = 0.3f)
                         else if (selected) NasMusicColors.Primary
                         else NasMusicColors.Surface.copy(alpha = 0.8f),
        focusedContainerColor = if (selected) NasMusicColors.Primary
                                else NasMusicColors.Primary.copy(alpha = 0.3f),
        contentColor = if (!available) NasMusicColors.TextSecondary.copy(alpha = 0.4f)
                       else if (selected) Color.Black
                       else NasMusicColors.TextPrimary,
        focusedContentColor = if (selected) Color.Black else NasMusicColors.Primary,
        pressedScale = 0.95f,
        showFocusBorder = available
    ) {
        Text(
            text = label,
            color = if (!available) NasMusicColors.TextSecondary.copy(alpha = 0.4f)
                    else if (selected) Color.Black
                    else NasMusicColors.TextPrimary,
            fontSize = FontSize.small(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * F2-2b：睡眠定时设置弹窗（紧凑布局，TV D-Pad / 手机点选通用）。
 * 标题"定时关闭"；中间 - [N 分钟] + 步进；下方 15 / 30 / OK / 取消 一行四个紧凑按钮。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SleepTimerPickerDialog(
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var minutes by remember { mutableStateOf(30) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(NasMusicColors.Surface, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.notif_sleep_timer_start),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.subtitle(),
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            // - / N 分钟 / +
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                com.nasmusic.tv.ui.components.FocusableSurface(
                    onClick = { if (minutes > 5) minutes -= 5 },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "-",
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.title(),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.np_sleep_timer_min, minutes),
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.subtitle(),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                com.nasmusic.tv.ui.components.FocusableSurface(
                    onClick = { if (minutes < 300) minutes += 5 },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "+",
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.title(),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // 快捷档：15 / 30
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30).forEach { min ->
                    com.nasmusic.tv.ui.components.FocusableSurface(
                        onClick = {
                            minutes = min
                            onStart(min)
                        },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.np_sleep_timer_min, min),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.body(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            // 确认 / 取消定时
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.nasmusic.tv.ui.components.FocusableSurface(
                    onClick = { onStart(minutes) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "OK",
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.body(),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                // 关闭定时（常驻，未运行时点击为 no-op 安全）
                com.nasmusic.tv.ui.components.FocusableSurface(
                    onClick = onCancel,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.np_sleep_timer_cancel),
                        color = NasMusicColors.Warning,
                        fontSize = FontSize.body(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

// =====================================================================================
// v2.36.0 手机竖屏播放页（方案 §4.2）
//
// 竖屏下 380dp 封面列 + 歌词列的横排结构不成立 → 改为「双模式 + 左右滑切换」：
//   模式 A 封面（默认）：顶栏 / 封面 1:1 / 歌名信息 / 进度条 / 控制行 / 次级操作 Chip / 模式指示
//   模式 B 歌词：顶栏 / 歌词区 weight(1f) / 工具条 3 Chip / 细进度条 2dp / 精简控制行 / 模式指示
//
// D2 决策：7 个歌词 Chip 收敛为 —— 来源循环 Chip + 字号循环 Chip + 睡眠定时（保留独立）
//          + 高亮模式移入「次级操作 Chip 横排」（高频切换，保留一键入口，不进"更多"菜单）。
// =====================================================================================

/** 竖屏播放页的两种模式（方案 §4.2） */
internal enum class PortraitNowPlayingMode { COVER, LYRICS }

/**
 * 横滑方向（v2.36.2）：手势切换动画的出入场方向依据。
 * LEFT = 手指向左滑（内容向左移动，新页从右侧推入）；
 * RIGHT = 手指向右滑（新页从左侧推入）；
 * NONE = 非手势切换（点击指示器等），动画退化为淡入淡出。
 */
internal enum class SwipeDirection { LEFT, RIGHT, NONE }

/** 歌词字号档位（与现状 A/A+/A++/A+++ 一致） */
private val LYRICS_FONT_SCALES = listOf(0.7f, 1.0f, 1.3f, 1.6f)

private fun lyricsFontIndex(scale: Float): Int = when (scale) {
    0.7f -> 0
    1.0f -> 1
    1.3f -> 2
    1.6f -> 3
    else -> 1
}

/** 歌词来源循环顺序（D2：4 个来源标签合并为 1 个循环 Chip） */
private val LYRICS_SOURCE_CYCLE = listOf(
    com.nasmusic.tv.data.model.LyricsSource.EMBEDDED,
    com.nasmusic.tv.data.model.LyricsSource.NETWORK,
    com.nasmusic.tv.data.model.LyricsSource.CACHED,
    com.nasmusic.tv.data.model.LyricsSource.LOCAL_FILE,
)

/**
 * 竖屏封面模式：歌名区行高倍数（字号 → 单行高度）。
 *
 * Compose 未显式设 `lineHeight` 时行高由字体度量决定，实测约 1.2~1.4× 字号，取 1.3 折中。
 */
private const val PORTRAIT_TITLE_LINE_RATIO = 1.3f

/** 竖屏封面模式：歌名区额外余量 = 封面与歌名之间的 12dp 间距 + 8dp 缓冲。 */
private val PORTRAIT_TITLE_SPACING = 20.dp

/** 竖屏左右滑切换模式的最小水平位移（≈3mm；低于此视为误触或竖直滚动） */
private val PORTRAIT_SWIPE_THRESHOLD = 48.dp

/**
 * 竖屏播放页：整块内容区左右滑切换「封面 ⟷ 歌词」（v2.36.0 竖屏体验修复）。
 *
 * ⚠️ 此前手势只挂在底部 28dp 的模式指示器上，而且**不分方向、只做 toggle** ——
 * 用户根本发现不了，实际体验就是"不支持左右滑动切换"。
 * 现在覆盖整块内容区，并带**方向语义**（左滑 → 歌词，右滑 → 封面）+ 位移阈值。
 *
 * v2.36.2：回调改为携带**滑动方向**（`SwipeDirection.LEFT/RIGHT`），供外层
 * `AnimatedContent` 按方向选择横推动画的出入场方向（左滑 → 新页从右推入）。
 * 点击模式指示器等非手势切换走 `SwipeDirection.NONE`（无方向，用淡入淡出兜底）。
 *
 * ⚠️ `pointerInput(Unit)` 不随重组重启。`mode` 由 `by remember { mutableStateOf }` 委托读写，
 * 闭包捕获的是同一个 `MutableState` 实例，读到/写入的永远是当前值，故无需 `rememberUpdatedState`。
 * ⚠️ 只识别水平拖拽：竖直滚动（歌词区）不受影响。
 */
private fun Modifier.portraitModeSwipe(
    onSwipe: (SwipeDirection) -> Unit,
): Modifier = this.pointerInput(Unit) {
    val thresholdPx = PORTRAIT_SWIPE_THRESHOLD.toPx()
    var accumulated = 0f
    detectHorizontalDragGestures(
        onDragStart = { accumulated = 0f },
        onDragEnd = {
            if (accumulated <= -thresholdPx) onSwipe(SwipeDirection.LEFT)
            else if (accumulated >= thresholdPx) onSwipe(SwipeDirection.RIGHT)
            accumulated = 0f
        },
        onDragCancel = { accumulated = 0f },
    ) { _, dragAmount ->
        accumulated += dragAmount
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NowPlayingPortrait(
    currentSong: Song?,
    isPlaying: Boolean,
    playMode: PlayMode,
    progressMs: Long,
    durationMs: Long,
    lyrics: Lyrics?,
    lyricsAvailability: com.nasmusic.tv.data.model.LyricsAvailability,
    coverCandidates: List<String>,
    highlightMode: LyricsHighlightMode,
    isFavorite: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayMode: () -> Unit,
    onSeek: (Long) -> Unit,
    onSwitchLyricsSource: (com.nasmusic.tv.data.model.LyricsSource) -> Unit,
    onChangeHighlightMode: (LyricsHighlightMode) -> Unit,
    onToggleFavorite: (() -> Unit)?,
    lyricsFontScale: Float,
    onLyricsFontScaleChange: (Float) -> Unit,
    technicalInfo: com.nasmusic.tv.data.model.SongTechnicalInfo?,
    onLoadTechnicalInfo: () -> Unit,
    onEnterKaraoke: () -> Unit,
    onEnterVisualizer: () -> Unit,
    mvAvailable: Boolean,
    onEnterMv: () -> Unit,
    qualityTier: Int,
    qualityLabel: String,
    onChangeQuality: (Int, com.nasmusic.tv.backend.network.QualityScope) -> Unit,
    sleepTimerState: com.nasmusic.tv.player.SleepTimerController.State?,
    onSleepTimerStart: (Int) -> Unit,
    onSleepTimerCancel: () -> Unit,
    onSearchSong: (String) -> Unit,
    onSearchArtist: (String) -> Unit,
    coverFilterEnabled: Boolean,
    coverFilterBlurRadius: Float,
    coverFilterDarkOverlay: Float,
    isImmersiveMode: Boolean,
    onToggleImmersive: () -> Unit,
    onCollapse: () -> Unit,
    onOpenQueue: () -> Unit,
    playPauseFocusRequester: FocusRequester,
) {
    var mode by remember { mutableStateOf(PortraitNowPlayingMode.COVER) }
    // v2.36.2：最近一次切换的方向（手势 → LEFT/RIGHT；点击指示器 → NONE），
    // 供 AnimatedContent 按方向选择横推/淡入淡出过渡。
    var lastSwipeDirection by remember { mutableStateOf(SwipeDirection.NONE) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showInfoPanel by remember { mutableStateOf(false) }

    val isRadio = currentSong?.networkSource?.isRadioSong() == true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isImmersiveMode) Brush.verticalGradient(listOf(Color.Black, Color.Black))
                else Brush.verticalGradient(listOf(NasMusicColors.Background, Color(0xFF0A1020)))
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── ① 顶栏 56dp：收起 / 标题 / 更多 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PortraitTopBarButton(
                    label = "\u2304",
                    contentDescription = stringResource(R.string.common_back),
                    onClick = onCollapse,
                )
                Text(
                    text = currentSong?.title ?: stringResource(R.string.player_no_song_selected),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                PortraitTopBarButton(
                    label = "\u22EF",
                    contentDescription = stringResource(R.string.np_more),
                    onClick = { showMoreMenu = true },
                )
            }

            // v2.36.2：左右滑切换模式带**方向感知的横推动画**（左滑 → 新页从右推入，
            // 右滑 → 新页从左推入；点击指示器等非手势切换退化为淡入淡出）。
            // ⚠️ 过渡期新旧两棵子树会**同时组合**约 260ms —— 本分支内的两个模式页均无
            // 组合期副作用（无 LaunchedEffect / 无一次性加载），可安全过渡；
            // 若日后往模式页里加 `LaunchedEffect(Unit)` 一次性加载，需确认其幂等性。
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    when (lastSwipeDirection) {
                        // 左滑：旧页向左推出，新页从右侧推入
                        SwipeDirection.LEFT ->
                            (slideInHorizontally(tween(260)) { it } togetherWith
                                slideOutHorizontally(tween(260)) { -it })
                        // 右滑：旧页向右推出，新页从左侧推入
                        SwipeDirection.RIGHT ->
                            (slideInHorizontally(tween(260)) { -it } togetherWith
                                slideOutHorizontally(tween(260)) { it })
                        // 非手势切换（点击指示器）：淡入淡出兜底
                        SwipeDirection.NONE ->
                            (fadeIn(tween(200)) togetherWith fadeOut(tween(200)))
                    }
                },
                label = "portraitModeSwitch",
            ) { currentMode ->
                if (currentMode == PortraitNowPlayingMode.COVER) {
                // ── 模式 A：封面 ──
                // 竖屏体验修复：进度条 + 控制按钮要「贴屏幕底部」，上方空间全留给封面。
                // 原实现整列 `verticalScroll` → 控制区紧跟封面，屏幕下方空一大片。
                // 现拆为「弹性区（封面 + 歌名，居中）+ 固定贴底区（进度 / 控制 / Chip）」。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        // 左滑 → 歌词（右滑已在封面，无动作）
                        .portraitModeSwipe(
                            onSwipe = { dir ->
                                lastSwipeDirection = dir
                                if (dir == SwipeDirection.LEFT) mode = PortraitNowPlayingMode.LYRICS
                            },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // ② 弹性区：封面 + 歌名，在剩余高度内居中
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 封面边长 = min(可用宽, 弹性区高 − 歌名区预留, 320dp)。
                        // 必须显式扣掉歌名高度：`aspectRatio` 的高度回退只看**自身**约束，
                        // 不知道下面还有歌名 —— 否则封面按高度收缩后仍会把歌名挤出弹性区。
                        //
                        // ⚠️ 预留量按**实际字号**算，不能写死常量：
                        // `FontSize.title()` / `small()` 会被用户在设置里的全局字号调节
                        // （-8 ~ +8 sp，见 `GeneralSettingsSection`）放大 —— 写死 96dp 在 +8 档下
                        // 歌名两行 + 艺术家一行约需 115dp，会溢出弹性区**压到下方进度条上**。
                        // 标题 `maxLines = 2`、艺术家/专辑 `maxLines = 1`。
                        val density = LocalDensity.current
                        val titleReserve = with(density) {
                            FontSize.title().toDp() * PORTRAIT_TITLE_LINE_RATIO * 2 +
                                FontSize.small().toDp() * PORTRAIT_TITLE_LINE_RATIO
                        } + PORTRAIT_TITLE_SPACING
                        val coverSide = minOf(
                            maxWidth,
                            // 不设下限：宁可封面缩小，也不要歌名溢出压住控制区（极窄屏 + 超大字号时）
                            (maxHeight - titleReserve).coerceAtLeast(0.dp),
                            320.dp,
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(coverSide)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(NasMusicColors.SurfaceVariant),
                            ) {
                        key(currentSong?.id) {
                            CoverCarousel(
                                coverCandidates = coverCandidates,
                                isPlaying = isPlaying,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // D8：封面右上角 ⓘ 图标按钮 → 歌曲信息（不用长按）
                        Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                            PortraitTopBarButton(
                                label = "\u24D8",
                                contentDescription = stringResource(R.string.np_song_info_cd),
                                onClick = { showInfoPanel = !showInfoPanel },
                            )
                        }
                        // 点击封面 → 沉浸模式
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(onClick = onToggleImmersive)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // ③ 歌名 / 艺术家 / 专辑 + 收藏
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentSong?.title ?: stringResource(R.string.player_no_song_selected),
                                color = NasMusicColors.TextPrimary,
                                fontSize = FontSize.title(),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    currentSong?.title?.takeIf { it.isNotBlank() }?.let(onSearchSong)
                                },
                            )
                            val artist = currentSong?.artist.orEmpty()
                            Text(
                                text = buildString {
                                    append(artist.ifBlank { "—" })
                                    val album = currentSong?.album.orEmpty()
                                    if (album.isNotBlank()) append(" · ").append(album)
                                },
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.small(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    if (artist.isNotBlank()) onSearchArtist(artist)
                                },
                            )
                        }
                        if (onToggleFavorite != null && currentSong != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            FavoriteButton(isFavorite = isFavorite, onClick = onToggleFavorite)
                        }
                    }
                        }   // 内层 Column（封面 + 歌名）
                    }       // BoxWithConstraints（弹性区）

                    // ④ 进度条（触摸 tap/drag seek 已支持）—— 以下为「固定贴底区」
                    ProgressSection(
                        progressMs = progressMs,
                        durationMs = durationMs,
                        onSeek = onSeek,
                        compact = true,
                        isLive = isRadio,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // ⑤ 控制行（播放键 64dp）
                    ControlButtonsRow(
                        isPlaying = isPlaying,
                        playMode = playMode,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onTogglePlayMode = onTogglePlayMode,
                        showVisualizerButton = false,
                        onEnterVisualizer = onEnterVisualizer,
                        showVocalButton = false,
                        onEnterKaraoke = onEnterKaraoke,
                        showMvButton = false,
                        mvAvailable = mvAvailable,
                        onEnterMv = onEnterMv,
                        compact = true,
                        playPauseFocusRequester = playPauseFocusRequester,
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // ⑥ 次级操作 Chip 横排可滚动（音质 / 定时 / 频谱 / K 歌 / MTV）
                    PortraitSecondaryChips(
                        qualityLabel = qualityLabel,
                        onOpenQuality = { showQualityDialog = true },
                        sleepTimerState = sleepTimerState,
                        onOpenSleepTimer = { showSleepTimerDialog = true },
                        onEnterVisualizer = onEnterVisualizer,
                        onEnterKaraoke = onEnterKaraoke,
                        mvAvailable = mvAvailable,
                        onEnterMv = onEnterMv,
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }
            } else {
                // ── 模式 B：歌词 ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        // 右滑 → 封面（左滑已在歌词，无动作）
                        .portraitModeSwipe(
                            onSwipe = { dir ->
                                lastSwipeDirection = dir
                                if (dir == SwipeDirection.RIGHT) mode = PortraitNowPlayingMode.COVER
                            },
                        ),
                ) {
                    // ③ 歌词工具条：来源循环 / 高亮模式 / 字号循环 / 睡眠定时（右对齐）
                    //
                    // 竖屏体验修复（v2.36.1）：本行原先位于歌词框**下方**，现上移到歌词框**右上方**，
                    // 与横屏 TV 端歌词工具条的位置一致（对照 `NowPlayingScreen.kt` 的 TV 分支：
                    // `Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End)`）。
                    // 同时接收从封面页移来的「逐行/逐字」高亮 Chip —— 该 Chip 属于歌词阅读设置，
                    // 放在封面页属于错位（封面页已移除）。
                    //
                    // ⚠️ 外层必须再套一层 `Box(fillMaxWidth, contentAlignment = CenterEnd)`：
                    // 直接给带 `horizontalScroll` 的 Row 加 `Arrangement.End` 是**无效**的 ——
                    // `horizontalScroll` 会用 `Constraints(maxWidth = Infinity)` 测量内容，
                    // Row 宽度恒等于内容宽度，没有「多余空间」可供 End 分配。
                    // 套 Box 后：Row 先被约束到父宽并右靠，内容窄于父宽时整体贴右；
                    // 内容溢出（超大字号 + 4 个 Chip）时仍可横向滚动，不会截断。
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // 来源循环 Chip
                            val currentSource = lyrics?.source
                            val sourceIdx = LYRICS_SOURCE_CYCLE.indexOf(currentSource).let { if (it < 0) 1 else it }
                            SourceTag(
                                label = currentSource?.displayName
                                    ?: com.nasmusic.tv.data.model.LyricsSource.NETWORK.displayName,
                                available = true,
                                selected = true,
                                onClick = {
                                    // 循环到下一个可用来源
                                    for (step in 1..LYRICS_SOURCE_CYCLE.size) {
                                        val next = LYRICS_SOURCE_CYCLE[(sourceIdx + step) % LYRICS_SOURCE_CYCLE.size]
                                        val usable = when (next) {
                                            com.nasmusic.tv.data.model.LyricsSource.EMBEDDED -> lyricsAvailability.hasBackend
                                            com.nasmusic.tv.data.model.LyricsSource.CACHED -> lyricsAvailability.hasCached
                                            com.nasmusic.tv.data.model.LyricsSource.LOCAL_FILE -> false
                                            else -> true
                                        }
                                        if (usable) {
                                            onSwitchLyricsSource(next)
                                            break
                                        }
                                    }
                                },
                            )
                            // 高亮模式 Chip（逐行 / 逐字，与 TV 端同款：逐字态选中高亮）
                            SourceTag(
                                label = if (highlightMode == LyricsHighlightMode.WORD_BY_WORD) {
                                    stringResource(R.string.player_highlight_word)
                                } else {
                                    stringResource(R.string.player_highlight_line)
                                },
                                available = true,
                                selected = highlightMode == LyricsHighlightMode.WORD_BY_WORD,
                                onClick = {
                                    onChangeHighlightMode(
                                        if (highlightMode == LyricsHighlightMode.WORD_BY_WORD) {
                                            LyricsHighlightMode.LINE_BY_LINE
                                        } else {
                                            LyricsHighlightMode.WORD_BY_WORD
                                        }
                                    )
                                },
                            )
                            // 字号循环 Chip（4 档循环）
                            val fontIdx = lyricsFontIndex(lyricsFontScale)
                            SourceTag(
                                label = listOf("A", "A+", "A++", "A+++")[fontIdx],
                                available = true,
                                selected = false,
                                onClick = {
                                    onLyricsFontScaleChange(LYRICS_FONT_SCALES[(fontIdx + 1) % LYRICS_FONT_SCALES.size])
                                },
                            )
                            // 睡眠定时（保留独立状态指示）
                            SourceTag(
                                label = when (val st = sleepTimerState) {
                                    is com.nasmusic.tv.player.SleepTimerController.State.Running ->
                                        stringResource(
                                            R.string.np_sleep_timer_on,
                                            ((st.endsAtMs - System.currentTimeMillis() + 59_999) / 60_000)
                                                .toInt().coerceAtLeast(1)
                                        )
                                    else -> stringResource(R.string.np_sleep_timer_off)
                                },
                                available = true,
                                selected = sleepTimerState is com.nasmusic.tv.player.SleepTimerController.State.Running,
                                onClick = { showSleepTimerDialog = true },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(NasMusicColors.Surface.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                    ) {
                        if (isRadio) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.player_radio_live),
                                    fontSize = FontSize.title(),
                                    fontWeight = FontWeight.Bold,
                                    color = NasMusicColors.Primary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            LyricsView(
                                lyrics = lyrics,
                                currentTimeMs = progressMs,
                                highlightMode = highlightMode,
                                isPlaying = isPlaying,
                                fontSizeMultiplier = lyricsFontScale,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // ④ 细进度条 2dp
                    PortraitThinProgress(progressMs = progressMs, durationMs = durationMs)

                    Spacer(modifier = Modifier.height(6.dp))

                    // ⑤ 精简控制行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ControlButtonsRow(
                            isPlaying = isPlaying,
                            playMode = playMode,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                            onPrevious = onPrevious,
                            onTogglePlayMode = onTogglePlayMode,
                            showVisualizerButton = false,
                            onEnterVisualizer = onEnterVisualizer,
                            showVocalButton = false,
                            onEnterKaraoke = onEnterKaraoke,
                            showMvButton = false,
                            mvAvailable = mvAvailable,
                            onEnterMv = onEnterMv,
                            compact = true,
                            playPauseFocusRequester = playPauseFocusRequester,
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                }
                }   // if (currentMode == COVER) / else 歌词分支
            }   // AnimatedContent

            // ⑦ 模式指示器（点击切换；左右滑由内容区的 portraitModeSwipe 处理）
            PortraitModeIndicator(
                mode = mode,
                onSwitch = {
                    // 点击指示器属非手势切换：方向置 NONE，AnimatedContent 退化为淡入淡出
                    lastSwipeDirection = SwipeDirection.NONE
                    mode = it
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 歌曲信息底部弹层（D8：由封面右上角 ⓘ 触发）
        if (showInfoPanel) {
            PortraitInfoOverlay(
                currentSong = currentSong,
                technicalInfo = technicalInfo,
                onLoadTechnicalInfo = onLoadTechnicalInfo,
                onDismiss = { showInfoPanel = false },
            )
        }

        // ⋯ 更多菜单
        if (showMoreMenu) {
            PortraitMoreMenu(
                qualityLabel = qualityLabel,
                mvAvailable = mvAvailable,
                onOpenQuality = { showMoreMenu = false; showQualityDialog = true },
                onOpenQueue = { showMoreMenu = false; onOpenQueue() },
                onOpenSleepTimer = { showMoreMenu = false; showSleepTimerDialog = true },
                onEnterVisualizer = { showMoreMenu = false; onEnterVisualizer() },
                onEnterKaraoke = { showMoreMenu = false; onEnterKaraoke() },
                onEnterMv = { showMoreMenu = false; onEnterMv() },
                onDismiss = { showMoreMenu = false },
            )
        }

        if (showSleepTimerDialog) {
            SleepTimerPickerDialog(
                onStart = { min ->
                    showSleepTimerDialog = false
                    onSleepTimerStart(min)
                },
                onCancel = {
                    showSleepTimerDialog = false
                    onSleepTimerCancel()
                },
                onDismiss = { showSleepTimerDialog = false },
            )
        }

        if (showQualityDialog) {
            com.nasmusic.tv.ui.components.QualityPickerDialog(
                currentTier = qualityTier,
                onConfirm = { tier, scope ->
                    showQualityDialog = false
                    onChangeQuality(tier, scope)
                },
                onDismiss = { showQualityDialog = false },
            )
        }
    }
}

/**
 * 竖屏顶栏图标按钮：**[PHONE_TOUCH_TARGET]（56 Compose dp ≈ 45.9 物理 dp ≥ 44）**。
 *
 * ⚠️ 早期写成 48dp 并注释「44dp+ 触摸目标」——那是**物理 dp 口径的误用**：
 * 48 Compose dp 在竖屏只有 39.4 物理 dp ❌（方案 §2.7 第 1 条 / P0-26）。
 * 56dp 恰好填满 56dp 顶栏（容器即热区，不加垂直 padding）。
 */
@Composable
private fun PortraitTopBarButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .size(PHONE_TOUCH_TARGET)
            .semantics { this.contentDescription = contentDescription },
        shape = RoundedCornerShape(8.dp),
        containerColor = Color.Transparent,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        // P2-34：手机触摸没有焦点边框，按下高亮是唯一的"已响应"视觉反馈
        pressedContainerColor = NasMusicColors.Primary.copy(alpha = 0.35f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary,
        focusedScale = 1.06f,
        animationDurationMs = 150,
        pressedScale = 0.94f,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = label, color = LocalFocusableContentColor.current, fontSize = FontSize.subtitle())
        }
    }
}

/**
 * 次级操作 Chip 横排（可滚动）—— 封面模式的次级操作。
 *
 * 2026-09-20 竖屏真机反馈调整（**只影响竖屏**）：
 * - **移出**高亮模式（逐行 / 逐字）→ 归入歌词页工具条（它只对歌词有意义）；
 * - **删除**收藏 → 歌曲名旁已有心形图标，重复；
 * - **删除**播放队列 → 底部主按钮（`PhoneNavBar`）已有直达入口；
 * - 「封面」文案 → **「频谱」**：该 Chip 的 `onClick` 本就是 `onEnterVisualizer`，
 *   文案与行为不符。现复用 [R.string.player_visualizer_short]（TV / 横屏同款文案）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PortraitSecondaryChips(
    qualityLabel: String,
    onOpenQuality: () -> Unit,
    sleepTimerState: com.nasmusic.tv.player.SleepTimerController.State?,
    onOpenSleepTimer: () -> Unit,
    onEnterVisualizer: () -> Unit,
    onEnterKaraoke: () -> Unit,
    mvAvailable: Boolean,
    onEnterMv: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (qualityLabel.isNotBlank()) {
            SourceTag(label = qualityLabel, available = true, selected = false, onClick = onOpenQuality)
        }
        SourceTag(
            label = when (val st = sleepTimerState) {
                is com.nasmusic.tv.player.SleepTimerController.State.Running ->
                    stringResource(
                        R.string.np_sleep_timer_on,
                        ((st.endsAtMs - System.currentTimeMillis() + 59_999) / 60_000).toInt().coerceAtLeast(1)
                    )
                else -> stringResource(R.string.np_sleep_timer_off)
            },
            available = true,
            selected = sleepTimerState is com.nasmusic.tv.player.SleepTimerController.State.Running,
            onClick = onOpenSleepTimer,
        )
        SourceTag(
            label = stringResource(R.string.player_visualizer_short),
            available = true,
            selected = false,
            onClick = onEnterVisualizer,
        )
        SourceTag(
            label = stringResource(R.string.player_karaoke),
            available = true,
            selected = false,
            onClick = onEnterKaraoke,
        )
        if (mvAvailable) {
            SourceTag(label = "MTV", available = true, selected = false, onClick = onEnterMv)
        }
    }
}

/** 歌词模式的 2dp 细进度条（不可 seek；seek 在封面模式的完整进度条上做） */
@Composable
private fun PortraitThinProgress(progressMs: Long, durationMs: Long) {
    val fraction = if (durationMs > 0L) (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(NasMusicColors.SurfaceVariant, RoundedCornerShape(1.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(2.dp)
                .background(NasMusicColors.Primary, RoundedCornerShape(1.dp))
        )
    }
}

/**
 * ⑦ 模式指示器（两圆点，点击切换）。
 *
 * ⚠️ v2.36.0 竖屏体验修复：此前把「左右滑」手势挂在这条 28dp 的指示器上，且不分方向只做 toggle
 * —— 用户发现不了，体验上等于"不支持左右滑"。现手势上移到整块内容区（[portraitModeSwipe]），
 * 这里只保留点击 + 状态指示。
 *
 * ⚠️ P0-26 触摸目标：圆点视觉只有 6~8dp，此前**直接把 `clickable` 挂在圆点上**，
 * 热区仅 6~8dp（物理 4.9~6.6dp），远低于 44dp 无障碍下限 —— 而且 P0-26 的静态自查 grep
 * 只覆盖 40~53dp 区间，**漏掉了这种"小尺寸 + clickable"的写法**。
 * 现改为「外层 `size(portraitTouchTarget(44.dp))` 承担热区（竖屏 56dp ≈ 45.9 物理 dp）
 * + 内层小圆点只做视觉」，两个热区相邻排布，圆点间距随之变为 56dp。
 */
@Composable
private fun PortraitModeIndicator(
    mode: PortraitNowPlayingMode,
    onSwitch: (PortraitNowPlayingMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(PortraitNowPlayingMode.COVER, PortraitNowPlayingMode.LYRICS).forEach { m ->
            val active = m == mode
            Box(
                modifier = Modifier
                    .size(portraitTouchTarget(44.dp))
                    .clickable { onSwitch(m) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (active) 8.dp else 6.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            if (active) NasMusicColors.Primary
                            else NasMusicColors.TextSecondary.copy(alpha = 0.5f)
                        )
                )
            }
        }
    }
}

/** 歌曲信息底部弹层（自建，走 `RegisterDialogBackHandler` 语义的 Box 覆盖层） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PortraitInfoOverlay(
    currentSong: Song?,
    technicalInfo: com.nasmusic.tv.data.model.SongTechnicalInfo?,
    onLoadTechnicalInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(NasMusicColors.Surface)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SongInfoPanel(
                song = currentSong,
                technicalInfo = technicalInfo,
                onDismiss = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
            LaunchedEffect(currentSong?.id) { onLoadTechnicalInfo() }
        }
    }
}

/** ⋯ 更多菜单（底部弹层；只放低频入口，高频项仍在次级 Chip 行） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PortraitMoreMenu(
    qualityLabel: String,
    mvAvailable: Boolean,
    onOpenQuality: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onEnterVisualizer: () -> Unit,
    onEnterKaraoke: () -> Unit,
    onEnterMv: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 方案 §6.3：自建 Box 覆盖层必须走 RegisterDialogBackHandler
    RegisterDialogBackHandler(onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(NasMusicColors.Surface)
                .padding(vertical = 12.dp),
        ) {
            PortraitMenuItem(stringResource(R.string.nav_queue), onOpenQueue)
            if (qualityLabel.isNotBlank()) {
                PortraitMenuItem(qualityLabel, onOpenQuality)
            }
            PortraitMenuItem(stringResource(R.string.notif_sleep_timer_start), onOpenSleepTimer)
            // 文案修正（v2.36.1，仅竖屏）：原用 `np_mode_cover`（"封面"）但 `onClick` 是 `onEnterVisualizer`
            // → 文案与行为不符。改用 TV / 横屏同款 `player_visualizer_short`（"频谱"）。
            PortraitMenuItem(stringResource(R.string.player_visualizer_short), onEnterVisualizer)
            PortraitMenuItem(stringResource(R.string.player_karaoke), onEnterKaraoke)
            if (mvAvailable) PortraitMenuItem("MTV", onEnterMv)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PortraitMenuItem(label: String, onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        focusedScale = 1.0f,
        animationDurationMs = 120,
        containerColor = Color.Transparent,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.18f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary,
    ) {
        Text(
            text = label,
            color = LocalFocusableContentColor.current,
            fontSize = FontSize.body(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

package com.nasmusic.tv.ui.screens

import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.components.common.SourceBadge

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import com.nasmusic.tv.ui.components.LyricsView
import com.nasmusic.tv.ui.components.CoverCarousel
import com.nasmusic.tv.ui.components.ControlButtonsRow
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.KaraokePlaybackScreen
import com.nasmusic.tv.ui.components.ProgressSection
import com.nasmusic.tv.ui.components.SongInfoPanel
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
            // v2.35.0 多码率：音质入口，与「信息 + 来源」同一行（方案 §5.1）。
            // 仅网络歌曲显示；点击弹出音质选择面板。
            // 放在这里而不是底部控制按钮行：底部行在 380dp 宽度下已容纳 7 个控件，
            // 第 8 个会被裁掉（手机端实测看不到入口）。
            if (currentSong?.isNetworkSong == true) {
                Spacer(modifier = Modifier.width(8.dp))
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
                        text = "♪ " + qualityLabel.ifBlank {
                            stringResource(R.string.quality_tier_auto)
                        },
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.small(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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

package com.nasmusic.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import androidx.compose.foundation.Image
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsHighlightMode
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.data.model.isRadioSong
import com.nasmusic.tv.ui.components.LyricsView
import com.nasmusic.tv.ui.components.CoverCarousel
import com.nasmusic.tv.ui.components.ControlButtonsRow
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.KaraokePlaybackScreen
import com.nasmusic.tv.ui.components.ProgressSection
import com.nasmusic.tv.ui.components.SongInfoPanel
import com.nasmusic.tv.ui.components.VisualEqualizer
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.AppLog

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
    /** 实时频谱柱状条数据（来自 Visualizer FFT），null = 随机回退 */
    spectrumData: FloatArray? = null,
    /** 是否启用频谱显示 */
    spectrumEnabled: Boolean = false,
    /** 可视化频谱主题 */
    visualizerTheme: VisualizerTheme = VisualizerTheme.COLOR_FLOW,
    // === KARAOKE 人声消除 ===
    vocalRemovalEnabled: Boolean = false,
    onToggleVocalRemoval: () -> Unit = {},
    // === MTV 音乐视频 ===
    mvAvailable: Boolean = false,
    onEnterMv: () -> Unit = {},
    remoteControlUrl: String? = null,
    // K 歌 / MTV 模式需要手机遥控服务器，由上层按需启动
    onEnterKaraokeMode: () -> Unit = {},
    /** 点击歌手名跳转到网络搜索 */
    onSearchArtist: (String) -> Unit = {},
    /** 点击歌曲名跳转到网络搜索 */
    onSearchSong: (String) -> Unit = {},
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
    modifier: Modifier = Modifier
) {
    var showInfoPanel by remember { mutableStateOf(false) }
    val playPauseFocusRequester = remember { FocusRequester() }

    // ── 是否显示全屏 KARAOKE 页面（与 vocalRemovalEnabled 音频开关分离）──
    // 不依赖 currentSong key：切歌（含自动下一首）时保持 K 歌页面，不跳回普通播放页
    var showKaraoke by remember { mutableStateOf(false) }

    fun enterKaraoke() {
        showKaraoke = true
        // 进入 K 歌页默认开启人声消除（伴唱）
        if (!vocalRemovalEnabled) onToggleVocalRemoval()
        // K 歌使用手机遥控页，按需启动遥控服务器
        onEnterKaraokeMode()
    }

    fun exitKaraoke() {
        showKaraoke = false
        // 退出 K 歌页恢复原唱
        if (vocalRemovalEnabled) onToggleVocalRemoval()
    }

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
            onExitKaraoke = { exitKaraoke() },
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
                Brush.verticalGradient(
                    listOf(
                        NasMusicColors.Background,
                        Color(0xFF0A1020)
                    )
                )
            )
    ) {
        // --- 沉浸模式：全屏封面背景（应用封面滤镜设置）---
        if (isImmersiveMode) {
            val bgUrl = coverCandidates.firstOrNull() ?: currentSong?.coverUrl
            AppLog.d("NowPlayingScreen", "immersiveBg: bgUrl=$bgUrl, coverCandidates=$coverCandidates, coverUrl=${currentSong?.coverUrl}")
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A2E))) {
                if (bgUrl != null) {
                    val painter = rememberAsyncImagePainter(
                        model = bgUrl,
                        onState = { state ->
                            AppLog.d("NowPlayingScreen", "immersiveBg state: ${state.javaClass.simpleName} bgUrl=${bgUrl.take(60)}")
                        }
                    )
                    Image(
                        painter = painter,
                        contentDescription = "Fullscreen Cover Background",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (coverFilterEnabled && coverFilterBlurRadius > 0f)
                                    Modifier.blur(coverFilterBlurRadius.dp)
                                else
                                    Modifier
                            )
                    )
                }
                // 半透明渐变遮罩覆盖整个背景，确保歌词可读
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xCC0C1222),
                                    Color(0x990C1222),
                                    Color(0xCC0C1222)
                                )
                            )
                        )
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 24.dp)
        ) {
            // 中部：专辑封面(1/3) + 歌词(2/3)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：封面 + 歌曲信息（沉浸模式隐藏）
                if (!isImmersiveMode) {
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
                            onSearchArtist = onSearchArtist
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
                            showVocalButton = currentSong != null,
                            onEnterKaraoke = { enterKaraoke() },
                            showMvButton = true,
                            mvAvailable = mvAvailable,
                            onEnterMv = onEnterMv,
                            compact = true,
                            playPauseFocusRequester = playPauseFocusRequester
                        )
                    }
                }

                // 右侧：歌词（沉浸模式下全宽）
                Column(modifier = Modifier.weight(1f)) {
                    // 歌词来源标签和高亮模式切换（可聚焦 — 保留 Surface）
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        val currentSource = lyrics?.source
                        SourceTag(
                            label = stringResource(R.string.player_highlight_backend),
                            available = lyricsAvailability.hasBackend,
                            selected = currentSource == com.nasmusic.tv.data.model.LyricsSource.EMBEDDED,
                            onClick = { onSwitchLyricsSource(com.nasmusic.tv.data.model.LyricsSource.EMBEDDED) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        SourceTag(
                            label = stringResource(R.string.player_highlight_network),
                            available = lyricsAvailability.hasNetwork,
                            selected = currentSource == com.nasmusic.tv.data.model.LyricsSource.NETWORK,
                            onClick = { onSwitchLyricsSource(com.nasmusic.tv.data.model.LyricsSource.NETWORK) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        SourceTag(
                            label = stringResource(R.string.player_highlight_cached),
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
                                    text = "● 电台直播",
                                    fontSize = 27.sp,
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
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 可视化均衡器（仅在非沉浸模式 + 有歌曲 + 开启频谱显示时显示）
            AppLog.d("NowPlayingScreen", "VisualEqualizer check: isImmersiveMode=$isImmersiveMode, isPlaying=$isPlaying, currentSong=${currentSong?.title}, spectrumEnabled=$spectrumEnabled")
            if (!isImmersiveMode && currentSong != null && spectrumEnabled) {
                AppLog.d("NowPlayingScreen", "VisualEqualizer about to render")
                VisualEqualizer(
                    isPlaying = isPlaying,
                    spectrumData = spectrumData,
                    theme = visualizerTheme,
                    barCount = 96,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

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
    onSearchArtist: (String) -> Unit = {}
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
                        fontSize = 27.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.player_no_song_selected),
                    color = NasMusicColors.TextPrimary,
                    fontSize = 27.sp,
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

        // 网络歌曲来源标识
        if (currentSong?.isNetworkSong == true) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .background(
                        NasMusicColors.Primary.copy(alpha = 0.2f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "NET",
                    color = NasMusicColors.Primary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Task 3: 专辑名移至封面图上方
        if (!currentSong?.album.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = currentSong?.album ?: "",
                color = NasMusicColors.TextSecondary.copy(alpha = 0.7f),
                fontSize = 19.sp,
                textAlign = TextAlign.Center,
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
                contentColor = NasMusicColors.TextSecondary,
                focusedContentColor = NasMusicColors.Primary
            ) {
                Text(
                    text = artist,
                    color = NasMusicColors.TextSecondary,
                    fontSize = 21.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        } else {
            Text(
                text = "—",
                color = NasMusicColors.TextSecondary,
                fontSize = 21.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 信息按钮（封面/信息切换）
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
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
                    text = if (showInfoPanel) "封面" else "信息",
                    color = if (showInfoPanel) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
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
        contentColor = if (isFavorite) NasMusicColors.Warning else NasMusicColors.TextSecondary,
        focusedContentColor = NasMusicColors.Warning,
        pressedScale = 0.95f
    ) {
        Text(
            text = if (isFavorite) "♥" else "♡",
            fontSize = 25.sp,
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
                       else NasMusicColors.TextSecondary,
        focusedContentColor = if (selected) Color.Black else NasMusicColors.Primary,
        pressedScale = 0.95f,
        showFocusBorder = available
    ) {
        Text(
            text = label,
            color = if (!available) NasMusicColors.TextSecondary.copy(alpha = 0.4f)
                    else if (selected) Color.Black
                    else NasMusicColors.TextPrimary,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

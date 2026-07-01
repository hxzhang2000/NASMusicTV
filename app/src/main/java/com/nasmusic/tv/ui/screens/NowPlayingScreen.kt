package com.nasmusic.tv.ui.screens

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsHighlightMode
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.components.LyricsView
import com.nasmusic.tv.ui.components.CoverCarousel
import com.nasmusic.tv.ui.components.ControlButtonsRow
import com.nasmusic.tv.ui.components.ProgressSection
import com.nasmusic.tv.ui.components.FocusableSurface
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
    modifier: Modifier = Modifier
) {
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
        // --- 沉浸模式：全屏封面背景 ---
        if (isImmersiveMode && currentSong?.coverUrl != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = currentSong.coverUrl,
                    contentDescription = "Fullscreen Cover Background",
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(30.dp) // 模糊效果，不影响上层歌词
                )
                // 半透明渐变遮罩确保歌词可读
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
                        modifier = Modifier.width(300.dp).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 封面内容垂直居中
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                        CoverColumn(
                            currentSong = currentSong,
                            onToggleImmersive = onToggleImmersive,
                                isFavorite = isFavorite,
                                onToggleFavorite = onToggleFavorite,
                                coverCandidates = coverCandidates,
                                isPlaying = isPlaying
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 播放控制按钮（置于封面图下方，歌词框不变）
                        ControlButtonsRow(
                            isPlaying = isPlaying,
                            playMode = playMode,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                            onPrevious = onPrevious,
                            onTogglePlayMode = onTogglePlayMode,
                            compact = true
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
                        LyricsView(
                            lyrics = lyrics,
                            currentTimeMs = progressMs,
                            highlightMode = highlightMode,
                            isPlaying = isPlaying,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 进度条（全宽，底部对齐）— Task 2
            ProgressSection(
                progressMs = progressMs,
                durationMs = durationMs,
                onSeek = onSeek,
                compact = true,
                currentSongId = currentSong?.id
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
    isPlaying: Boolean = false
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
            Text(
                text = currentSong?.title ?: stringResource(R.string.player_no_song_selected),
                color = NasMusicColors.TextPrimary,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
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
                    fontSize = 10.sp,
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
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

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

        Spacer(modifier = Modifier.height(12.dp))

        // 艺术家（Task 3: 专辑名已移至封面图上方，此处只显示艺术家）
        Text(
            text = currentSong?.artist?.takeIf { it.isNotBlank() } ?: "—",
            color = NasMusicColors.TextSecondary,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

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
            fontSize = 20.sp,
            modifier = Modifier.padding(6.dp)
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
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

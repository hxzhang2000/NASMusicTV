package com.nasmusic.tv.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import android.util.Log
import coil.compose.AsyncImage
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.components.LyricsView
import com.nasmusic.tv.ui.components.ControlButtonsRow
import com.nasmusic.tv.ui.components.ProgressSection
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.NasMusicBrushes
import kotlinx.coroutines.launch

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
    isFavorite: Boolean = false,
    isImmersiveMode: Boolean = false,
    onToggleImmersive: () -> Unit = {},
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayMode: () -> Unit,
    onSeek: (Long) -> Unit,
    onSwitchLyricsSource: (com.nasmusic.tv.data.model.LyricsSource) -> Unit,
    onToggleFavorite: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 歌词高亮模式状态
    var highlightMode by remember { mutableStateOf(com.nasmusic.tv.data.model.LyricsHighlightMode.LINE_BY_LINE) }
    
    // 自动检测歌词格式：如果有逐字时间戳，自动切换到逐字模式
    LaunchedEffect(lyrics) {
        if (lyrics != null && lyrics.lines.any { it.wordTimestamps.isNotEmpty() }) {
            highlightMode = com.nasmusic.tv.data.model.LyricsHighlightMode.WORD_BY_WORD
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
                                onToggleFavorite = onToggleFavorite
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
                            label = "后端",
                            available = lyricsAvailability.hasBackend,
                            selected = currentSource == com.nasmusic.tv.data.model.LyricsSource.EMBEDDED,
                            onClick = { onSwitchLyricsSource(com.nasmusic.tv.data.model.LyricsSource.EMBEDDED) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        SourceTag(
                            label = "网络",
                            available = lyricsAvailability.hasNetwork,
                            selected = currentSource == com.nasmusic.tv.data.model.LyricsSource.NETWORK,
                            onClick = { onSwitchLyricsSource(com.nasmusic.tv.data.model.LyricsSource.NETWORK) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        // 高亮模式切换按钮
                        SourceTag(
                            label = if (highlightMode == com.nasmusic.tv.data.model.LyricsHighlightMode.WORD_BY_WORD) "逐字" else "逐行",
                            available = true,
                            selected = highlightMode == com.nasmusic.tv.data.model.LyricsHighlightMode.WORD_BY_WORD,
                            onClick = {
                                highlightMode = if (highlightMode == com.nasmusic.tv.data.model.LyricsHighlightMode.WORD_BY_WORD) {
                                    com.nasmusic.tv.data.model.LyricsHighlightMode.LINE_BY_LINE
                                } else {
                                    com.nasmusic.tv.data.model.LyricsHighlightMode.WORD_BY_WORD
                                }
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
    onToggleFavorite: (() -> Unit)? = null
) {
    var isCoverFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

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
                text = currentSong?.title ?: "未选择歌曲",
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

        // Task 3: 专辑名移至封面图上方
        if (!currentSong?.album.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = currentSong!!.album,
                color = NasMusicColors.TextSecondary.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 可聚焦的封面容器 — OK 键切换沉浸模式
        Surface(
            onClick = onToggleImmersive,
            modifier = Modifier
                .size(240.dp + 40.dp)
                .scale(animScale.value)
                .border(
                    width = if (isCoverFocused) 2.dp else 0.dp,
                    color = if (isCoverFocused) NasMusicColors.FocusRing else Color.Transparent,
                    shape = RoundedCornerShape(20.dp)
                )
                .onFocusChanged {
                    isCoverFocused = it.isFocused
                    scope.launch { animScale.animateTo(if (isCoverFocused) 1.05f else 1f, tween(150)) }
                },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color.Transparent,
                focusedContainerColor = Color.Transparent
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.97f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                // 发光光晕
                Box(
                    modifier = Modifier
                        .size(240.dp + 20.dp)
                        .background(NasMusicColors.AccentGlow, shape = RoundedCornerShape(50.dp))
                )
                // 实际封面
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(NasMusicColors.Surface)
                ) {
                    key(currentSong?.id) {
                        var attemptCount by remember { mutableStateOf(0) }

                        val effectiveUrl = when (attemptCount) {
                            0 -> currentSong?.coverUrl
                            1 -> currentSong?.coverUrl?.replace("/Images/Primary", "/Images/Backdrop")
                            2 -> currentSong?.coverUrl?.replace("/Images/Primary", "/Images/Backdrop")
                            else -> null
                        }

                        LaunchedEffect(attemptCount) {
                            if (effectiveUrl != null) {
                                Log.d("NASMusic", "Cover attempt ${attemptCount + 1}/3: ${effectiveUrl.take(80)}...")
                            } else {
                                Log.w("NASMusic", "Cover: ${if (attemptCount >= 3) "all attempts exhausted" else "no coverUrl"} for ${currentSong?.title}")
                            }
                        }

                        if (effectiveUrl != null) {
                            key(attemptCount) {
                                AsyncImage(
                                    model = effectiveUrl,
                                    contentDescription = "Album Cover",
                                    modifier = Modifier.fillMaxSize(),
                                    onLoading = { Log.d("NASMusic", "Cover: loading...") },
                                    onSuccess = { Log.i("NASMusic", "Cover: loaded from ${effectiveUrl.take(80)}...") },
                                    onError = {
                                        attemptCount++
                                        Log.e("NASMusic", "Cover attempt ${attemptCount}/3 failed: ${it.result.throwable}")
                                    }
                                )
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(text = "♪", color = NasMusicColors.TextSecondary, fontSize = 120.sp)
                            }
                        }
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
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = onClick,
        modifier = modifier
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch { animScale.animateTo(if (isFocused) 1.12f else 1f, tween(150)) }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(8.dp),
            focusedShape = RoundedCornerShape(8.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isFavorite) NasMusicColors.Warning.copy(alpha = 0.2f) else Color.Transparent,
            contentColor = if (isFavorite) NasMusicColors.Warning else NasMusicColors.TextSecondary,
            focusedContainerColor = NasMusicColors.Warning.copy(alpha = 0.3f),
            focusedContentColor = NasMusicColors.Warning
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.95f)
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
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Surface(
        onClick = { if (available) onClick() },
        modifier = Modifier
            .scale(animScale.value)
            .border(
                width = if (isFocused && available) 2.dp else 0.dp,
                color = if (isFocused && available) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch { animScale.animateTo(if (isFocused) 1.1f else 1f, tween(150)) }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(6.dp),
            focusedShape = RoundedCornerShape(6.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (!available) NasMusicColors.Surface.copy(alpha = 0.3f)
                             else if (selected) NasMusicColors.Primary
                             else NasMusicColors.Surface.copy(alpha = 0.8f),
            contentColor = if (!available) NasMusicColors.TextSecondary.copy(alpha = 0.4f)
                           else if (selected) Color.Black
                           else NasMusicColors.TextSecondary,
            focusedContainerColor = if (selected) NasMusicColors.Primary
                                    else NasMusicColors.Primary.copy(alpha = 0.3f),
            focusedContentColor = if (selected) Color.Black else NasMusicColors.Primary
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.95f)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

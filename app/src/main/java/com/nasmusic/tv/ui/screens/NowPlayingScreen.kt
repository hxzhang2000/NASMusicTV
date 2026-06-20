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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import com.nasmusic.tv.ui.components.PlayerControls
import com.nasmusic.tv.ui.theme.NasMusicColors
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
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayMode: () -> Unit,
    onSeek: (Long) -> Unit,
    onSwitchLyricsSource: (com.nasmusic.tv.data.model.LyricsSource) -> Unit,
    modifier: Modifier = Modifier
) {
    var isProgressBarFocused by remember { mutableStateOf(false) }

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
            .onKeyEvent { event ->
                if (isProgressBarFocused && event.type == KeyEventType.KeyDown && durationMs > 0) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            onSeek((progressMs - 15000).coerceAtLeast(0))
                            true
                        }
                        Key.DirectionRight -> {
                            onSeek((progressMs + 15000).coerceAtMost(durationMs))
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
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
                // 左侧：封面 + 歌曲信息（无 Surface / 无边框 / 无焦点处理）
                Column(
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(modifier = Modifier.weight(1f))

                    // 歌曲标题（放在封面上方，字体缩小）
                    Text(
                        text = currentSong?.title ?: "未选择歌曲",
                        color = NasMusicColors.TextPrimary,
                        fontSize = 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // 发光背景 + 专辑封面
                    Box(
                        modifier = Modifier.size(240.dp + 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // 发光光晕
                        Box(
                            modifier = Modifier
                                .size(240.dp + 20.dp)
                                .background(
                                    NasMusicColors.AccentGlow,
                                    shape = RoundedCornerShape(50.dp)
                                )
                        )
                        // 实际封面（key 绑定 songId，防止 500ms 进度轮询重建 AsyncImage）
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
                                    1 -> currentSong?.coverUrl?.replace(
                                        "/Images/Primary",
                                        "/Images/Backdrop"
                                    )

                                    2 -> currentSong?.coverUrl?.replace(
                                        "/Images/Primary",
                                        "/Images/Backdrop"
                                    )
                                    else -> null
                                }

                                // 只在 attemptCount 变化时打印一次，避免 500ms 进度轮询刷屏
                                LaunchedEffect(attemptCount) {
                                    if (effectiveUrl != null) {
                                        Log.d(
                                            "NASMusic",
                                            "Cover attempt ${attemptCount + 1}/3: ${effectiveUrl.take(80)}..."
                                        )
                                    } else {
                                        Log.w(
                                            "NASMusic",
                                            "Cover: ${if (attemptCount >= 3) "all attempts exhausted" else "no coverUrl"} for ${currentSong?.title}"
                                        )
                                    }
                                }

                                if (effectiveUrl != null) {
                                    // 内层 key(attemptCount) 确保重试时创建新 Coil 请求，
                                    // 避免 Coil 缓存 404 后 onError 不再触发
                                    key(attemptCount) {
                                        AsyncImage(
                                            model = effectiveUrl,
                                            contentDescription = "Album Cover",
                                            modifier = Modifier.fillMaxSize(),
                                            onLoading = { Log.d("NASMusic", "Cover: loading...") },
                                            onSuccess = {
                                                Log.i(
                                                    "NASMusic",
                                                    "Cover: loaded from ${effectiveUrl.take(80)}..."
                                                )
                                            },
                                            onError = {
                                                attemptCount++
                                                Log.e(
                                                    "NASMusic",
                                                    "Cover attempt ${attemptCount}/3 failed: ${it.result.throwable}"
                                                )
                                            }
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "♪",
                                            color = NasMusicColors.TextSecondary,
                                            fontSize = 120.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 艺术家 / 专辑（放在封面下方）
                    Text(
                        text = buildString {
                            if (!currentSong?.artist.isNullOrBlank()) append(currentSong!!.artist)
                            if (!currentSong?.album.isNullOrBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(currentSong!!.album)
                            }
                            if (isEmpty()) append("—")
                        },
                        color = NasMusicColors.TextSecondary,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.weight(1f))
                }

                // 右侧：歌词（无 Surface 边框 / 无焦点处理）
                Column(modifier = Modifier.weight(1f)) {
                    // 歌词来源标签（可聚焦 — 保留 Surface）
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
                    }

                    // 歌词内容区域（无 Surface 边框，纯背景色）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                NasMusicColors.Surface.copy(alpha = 0.3f),
                                RoundedCornerShape(8.dp)
                            )
                    ) {
                        LyricsView(
                            lyrics = lyrics,
                            currentTimeMs = progressMs,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 底部：播放控制（紧凑模式）
            PlayerControls(
                isPlaying = isPlaying,
                playMode = playMode,
                progressMs = progressMs,
                durationMs = durationMs,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onTogglePlayMode = onTogglePlayMode,
                onSeek = onSeek,
                onProgressFocusChanged = { isProgressBarFocused = it },
                compact = true
            )
        }
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

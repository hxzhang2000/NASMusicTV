package com.nasmusic.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.ui.theme.NasMusicBrushes
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.TimeUtils
import kotlinx.coroutines.launch

// ─── Progress Section (standalone) ────────────────────────────────────────────

/**
 * 独立进度条组件，包含进度轨道、滑块和时间标签。
 * 用于 NowPlayingScreen 底部全宽显示，也可复用至其他页面。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProgressSection(
    progressMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onProgressFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    currentSongId: String? = null
) {
    var progressBarSize by remember { mutableStateOf(IntSize.Zero) }
    var isProgressFocused by remember { mutableStateOf(false) }
    val progressSpacer = if (compact) 8.dp else 16.dp
    val timeFont = if (compact) 12.sp else 14.sp
    val timeFontFocused = if (compact) 15.sp else 18.sp
    val progressFocusRequester = remember { FocusRequester() }

    // 当播放新歌曲时请求焦点
    LaunchedEffect(currentSongId) {
        android.util.Log.d("NASMusic", "ProgressSection: requesting focus for song=$currentSongId")
        progressFocusRequester.requestFocus()
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = TimeUtils.formatDuration(progressMs),
            color = if (isProgressFocused) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary,
            fontSize = if (isProgressFocused) timeFontFocused else timeFont
        )
        Spacer(modifier = Modifier.width(progressSpacer))
        // 进度条（Box 组合布局）
        Box(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .onPreviewKeyEvent { event ->
                    // 在焦点导航之前拦截左右键进行 seek
                    if ((event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
                        && event.type == KeyEventType.KeyDown && durationMs > 0) {
                        val delta = if (event.key == Key.DirectionLeft) -15000L else 15000L
                        val newPosition = (progressMs + delta).coerceIn(0, durationMs)
                        android.util.Log.d("NASMusic", "ProgressSection: seek by ${delta}ms, current=$progressMs, new=$newPosition, duration=$durationMs")
                        onSeek(newPosition)
                        true
                    } else false
                }
        ) {
        Surface(
            onClick = { if (durationMs > 0) onSeek(durationMs / 2) },
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(progressFocusRequester)
                .onFocusChanged {
                    isProgressFocused = it.isFocused
                    onProgressFocusChanged(it.isFocused)
                }
                .onSizeChanged { progressBarSize = it },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NasMusicColors.Surface.copy(alpha = 0.15f),
                focusedContainerColor = NasMusicColors.Surface.copy(alpha = 0.15f),
                pressedContainerColor = NasMusicColors.Surface.copy(alpha = 0.15f)
            ),
            shape = ClickableSurfaceDefaults.shape(
                shape = RoundedCornerShape(4.dp),
                focusedShape = RoundedCornerShape(4.dp)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val progress = if (durationMs > 0) progressMs.toFloat() / durationMs else 0f
                val thumbDiameter = 16.dp

                // 背景轨道
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(3.dp))
                        .background(NasMusicColors.SurfaceVariant)
                )
                // 进度填充
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(6.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(3.dp))
                        .background(NasMusicBrushes.progressBar)
                )
                // 滑块圆点
                Box(
                    modifier = Modifier
                        .offset {
                            val trackW = progressBarSize.width.toFloat()
                            val thumbR = thumbDiameter.toPx() / 2f
                            val pos = trackW * progress.coerceIn(0f, 1f)
                            IntOffset(
                                x = (pos - thumbR).roundToInt()
                                    .coerceIn(0, (trackW - thumbDiameter.toPx()).toInt()),
                                y = ((progressBarSize.height - thumbDiameter.toPx()) / 2f).roundToInt()
                            )
                        }
                        .size(thumbDiameter)
                        .clip(CircleShape)
                        .background(if (isProgressFocused) Color.Yellow else NasMusicColors.Primary)
                )
            }
        }
        }
        Spacer(modifier = Modifier.width(progressSpacer))
        Text(
            text = TimeUtils.formatDuration(durationMs),
            color = if (isProgressFocused) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary,
            fontSize = if (isProgressFocused) timeFontFocused else timeFont
        )
    }
}

// ─── Control Buttons Row (standalone) ────────────────────────────────────────

/**
 * 独立播放控制按钮行：上一首、播放/暂停、下一首、播放模式。
 * 供 NowPlayingScreen 在封面下方使用，也可在其他页面复用。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ControlButtonsRow(
    isPlaying: Boolean,
    playMode: PlayMode,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayMode: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious, compact = compact, icon = {
            Icon(imageVector = Icons.Filled.SkipPrevious, contentDescription = "Previous",
                modifier = Modifier.size(if (compact) 22.dp else 32.dp))
        })
        Spacer(modifier = Modifier.width(if (compact) 12.dp else 20.dp))
        IconButton(onClick = onPlayPause, primary = true, compact = compact, icon = {
            Icon(imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = "Play/Pause",
                modifier = Modifier.size(if (compact) 28.dp else 40.dp))
        })
        Spacer(modifier = Modifier.width(if (compact) 12.dp else 20.dp))
        IconButton(onClick = onNext, compact = compact, icon = {
            Icon(imageVector = Icons.Filled.SkipNext, contentDescription = "Next",
                modifier = Modifier.size(if (compact) 22.dp else 32.dp))
        })
        Spacer(modifier = Modifier.width(if (compact) 12.dp else 20.dp))
        IconButton(onClick = onTogglePlayMode, compact = compact, icon = {
            val icon = when (playMode) {
                PlayMode.SHUFFLE -> Icons.Filled.Shuffle
                PlayMode.REPEAT_ONE -> Icons.Filled.RepeatOne
                else -> Icons.Filled.Repeat
            }
            Icon(imageVector = icon, contentDescription = playMode.displayName,
                modifier = Modifier.size(if (compact) 20.dp else 28.dp))
        })
    }
}

// ─── Combined PlayerControls (backward compatible) ───────────────────────────

/**
 * 播放控制栏（组合版）：进度条 + 控制按钮同一行。
 * 保留兼容性，新页面推荐分别使用 [ProgressSection] 和 [ControlButtonsRow]。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    playMode: PlayMode,
    progressMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayMode: () -> Unit,
    onSeek: (Long) -> Unit,
    onProgressFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val contentPadding = if (compact) 24.dp else 4.dp
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = contentPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(if (compact) 0.66f else 0.6f)) {
            ProgressSection(
                progressMs = progressMs,
                durationMs = durationMs,
                onSeek = onSeek,
                onProgressFocusChanged = onProgressFocusChanged,
                compact = compact
            )
        }
        Spacer(modifier = Modifier.width(if (compact) 8.dp else 16.dp))
        ControlButtonsRow(
            isPlaying = isPlaying,
            playMode = playMode,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            onTogglePlayMode = onTogglePlayMode,
            compact = compact
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    compact: Boolean = false,
    icon: @Composable () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val buttonSize = when {
        primary && !compact -> 88.dp
        primary && compact -> 60.dp
        !primary && !compact -> 72.dp
        else -> 48.dp
    }
    val glowElevation = if (compact) 6.dp else 12.dp
    // 主按钮（播放/暂停）添加青色发光阴影效果，对应 HTML box-shadow: 0 4px 20px rgba(45,212,191,0.3)
    val glowModifier: Modifier = if (primary) {
        modifier
            .size(buttonSize)
            .scale(animScale.value)
            .shadow(
                elevation = glowElevation,
                shape = CircleShape,
                ambientColor = NasMusicColors.Primary,
                spotColor = NasMusicColors.Primary
            )
    } else {
        modifier
            .size(buttonSize)
            .scale(animScale.value)
    }
    Surface(
        onClick = onClick,
        modifier = glowModifier
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = CircleShape
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch { animScale.animateTo(if (isFocused) 1.12f else 1f, tween(250)) }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = CircleShape,
            focusedShape = CircleShape,
            pressedShape = CircleShape
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) NasMusicColors.Primary else NasMusicColors.Surface,
            contentColor = if (primary) Color.Black else NasMusicColors.TextPrimary,
            focusedContainerColor = if (primary) NasMusicColors.Primary else NasMusicColors.Primary.copy(alpha = 0.3f),
            focusedContentColor = if (primary) Color.Black else Color.Black,
            pressedContainerColor = NasMusicColors.SurfaceVariant,
            pressedContentColor = NasMusicColors.TextPrimary
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 0.90f
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
    }
}

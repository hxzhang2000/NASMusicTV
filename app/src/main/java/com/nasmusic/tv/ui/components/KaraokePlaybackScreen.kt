package com.nasmusic.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.rememberAsyncImagePainter
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * KARAOKE 全屏播放页面
 *
 * 布局：
 * - 全屏封面背景（ContentScale.Crop + blur + 暗色渐变遮罩）
 * - 中上部：歌曲名 + 歌手名
 * - 下方：歌词区域（置画面下方，字体放大，置于半透明黑色全宽色块中）
 * - 底部：左上角返回按钮 + 右下角控制栏（上一首/播放暂停/下一首/原唱伴唱切换）
 *
 * 无进度条控制。原唱/伴唱按钮只切换音频，不退出页面。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KaraokePlaybackScreen(
    currentSong: Song?,
    isPlaying: Boolean,
    lyrics: Lyrics?,
    coverCandidates: List<String>,
    coverFilterEnabled: Boolean = false,
    coverFilterBlurRadius: Float = 8f,
    /** 当前播放进度（毫秒），驱动歌词定位 —— 必须与播放页传入的同一 progressMs 来源 */
    progressMs: Long,
    vocalRemovalEnabled: Boolean,
    onToggleVocalRemoval: () -> Unit,
    onExitKaraoke: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    playPauseFocusRequester: FocusRequester? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
    ) {
        // ── 全屏封面背景 ──
        val bgUrl = coverCandidates.firstOrNull() ?: currentSong?.coverUrl
        if (bgUrl != null) {
            val painter = rememberAsyncImagePainter(model = bgUrl)
            Image(
                painter = painter,
                contentDescription = "Karaoke Fullscreen Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (coverFilterEnabled && coverFilterBlurRadius > 0f)
                            Modifier.blur(coverFilterBlurRadius.dp)
                        else Modifier
                    )
            )
        }
        // 暗色渐变遮罩
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

        // ── 前景内容 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 上部：歌曲信息
            Column(
                modifier = Modifier.padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = currentSong?.title ?: "",
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    color = NasMusicColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = currentSong?.artist ?: "",
                    fontSize = 20.sp,
                    color = NasMusicColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }

            Spacer(Modifier.weight(1f))

            // 下部：歌词区域 —— 半透明黑色全宽框，上下保留空间
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x66000000), RoundedCornerShape(12.dp))
                    .padding(vertical = 36.dp)
            ) {
                KaraokeLyricsView(
                    lyrics = lyrics,
                    progressMs = progressMs,
                    isPlaying = isPlaying,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(48.dp))
        }

        // ── 底部栏：左下角返回 + 右下角控制 ──
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回按钮（左下角）
            MiniIconButton(
                onClick = onExitKaraoke,
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back"
            )

            // 控制栏（右下角）
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiniIconButton(
                    onClick = onPrevious,
                    icon = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous"
                )
                Spacer(Modifier.width(20.dp))
                MiniIconButton(
                    onClick = onPlayPause,
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    primary = true,
                    focusRequester = playPauseFocusRequester
                )
                Spacer(Modifier.width(20.dp))
                MiniIconButton(
                    onClick = onNext,
                    icon = Icons.Filled.SkipNext,
                    contentDescription = "Next"
                )
                Spacer(Modifier.width(20.dp))
                // 原唱/伴唱切换（只切换音频，不退出页面）
                VocalToggleButton(
                    label = if (vocalRemovalEnabled) "原唱" else "伴唱",
                    onClick = onToggleVocalRemoval
                )
            }
        }
    }

    // 进入 KARAOKE 页面时自动聚焦播放/暂停按钮
    LaunchedEffect(Unit) {
        try {
            playPauseFocusRequester?.requestFocus()
        } catch (_: Exception) {
        }
    }
}

/**
 * 简易图标按钮（用于 KARAOKE 控制栏）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MiniIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null
) {
    val buttonSize = if (primary) 72.dp else 56.dp
    val iconSize = if (primary) 36.dp else 28.dp

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(buttonSize)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(50)),
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(50),
            focusedShape = RoundedCornerShape(50)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) NasMusicColors.Primary else NasMusicColors.Surface,
            contentColor = if (primary) Color.Black else NasMusicColors.TextPrimary,
            focusedContainerColor = if (primary) NasMusicColors.Primary else NasMusicColors.Primary.copy(alpha = 0.3f),
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
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
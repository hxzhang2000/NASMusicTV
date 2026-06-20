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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.theme.NasMusicBrushes
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.TimeUtils
import kotlinx.coroutines.launch

/**
 * 播放队列屏幕
 * 左侧：当前专辑封面 + 歌曲信息 + 迷你控制（对应 HTML queue-sidebar）
 * 右侧：队列列表
 */
@Composable
fun QueueScreen(
    queue: List<Song>,
    currentIndex: Int,
    currentSong: Song?,
    isPlaying: Boolean,
    playMode: PlayMode,
    onPlaySong: (Int) -> Unit,
    onRemoveSong: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxSize().padding(32.dp)) {
        // --- 左侧：当前播放卡片（bg2 = 深紫）---
        Column(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight()
                .padding(end = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NasMusicColors.Surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 专辑封面
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(NasMusicColors.SurfaceVariant)
            ) {
                if (!currentSong?.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = currentSong!!.coverUrl,
                        contentDescription = "Album",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Default.MusicNote, contentDescription = null, tint = NasMusicColors.TextSecondary, modifier = Modifier.size(72.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            // 标题/作者
            Text(text = currentSong?.title ?: "未选择歌曲",
                color = NasMusicColors.Primary,
                fontSize = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currentSong?.artist?.ifBlank { "—" } ?: "—",
                color = NasMusicColors.TextSecondary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(24.dp))
            // 迷你播放控制
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiniIconButton(onClick = onPrevious, icon = Icons.Filled.SkipPrevious, contentDescription = "Previous")
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(NasMusicColors.Primary, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(28.dp))
                }
                MiniIconButton(onClick = onNext, icon = Icons.Filled.SkipNext, contentDescription = "Next")
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(text = playMode.displayName, color = NasMusicColors.TextSecondary, fontSize = 12.sp)
        }

        // --- 右侧：队列列表 ---
        Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "播放队列", color = NasMusicColors.TextPrimary, fontSize = 36.sp)
                Spacer(modifier = Modifier.weight(1f))
                Text(text = "${queue.size} 首", color = NasMusicColors.TextSecondary, fontSize = 16.sp)
                if (queue.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(24.dp))
                    QueueActionButton(text = "清空", onClick = onClearQueue, icon = null)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (queue.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "队列为空", color = NasMusicColors.TextSecondary, fontSize = 24.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "前往曲库添加歌曲", color = NasMusicColors.TextSecondary, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(queue) { song ->
                        val index = queue.indexOf(song)
                        val isCurrent = index == currentIndex
                        var isFocused by remember { mutableStateOf(false) }
                        val animScale = remember { Animatable(1f) }
                        val scope = rememberCoroutineScope()
                        Surface(
                            onClick = { onPlaySong(index) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .scale(animScale.value)
                                .border(
                                    width = if (isFocused) 2.dp else 0.dp,
                                    color = if (isFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .onFocusChanged {
                                    isFocused = it.isFocused
                                    scope.launch { animScale.animateTo(if (isFocused) 1.03f else 1f, tween(250)) }
                                },
                            shape = ClickableSurfaceDefaults.shape(
                                shape = RoundedCornerShape(8.dp),
                                focusedShape = RoundedCornerShape(8.dp)
                            ),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isCurrent) NasMusicColors.Primary.copy(alpha = 0.2f) else NasMusicColors.Surface,
                                contentColor = if (isCurrent) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                                focusedContainerColor = if (isCurrent) NasMusicColors.Primary.copy(alpha = 0.3f) else NasMusicColors.Primary.copy(alpha = 0.2f),
                                focusedContentColor = if (isCurrent) androidx.compose.ui.graphics.Color.Black else NasMusicColors.TextPrimary
                            ),
                            scale = ClickableSurfaceDefaults.scale(
                                focusedScale = 1f,
                                pressedScale = 0.97f
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 14.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isFocused -> NasMusicColors.Primary.copy(alpha = 0.3f)
                                            isCurrent -> NasMusicColors.Primary.copy(alpha = 0.15f)
                                            else -> Color.Transparent
                                        }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format("%02d", index + 1),
                                    color = if (isCurrent) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                                    fontSize = 16.sp,
                                    modifier = Modifier.width(40.dp),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = song.title, color = if (isCurrent) NasMusicColors.Primary else NasMusicColors.TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(text = song.artist.ifBlank { "—" }, color = NasMusicColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(text = TimeUtils.formatDuration(song.durationMs), color = NasMusicColors.TextSecondary, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QueueActionButton(text: String, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch { animScale.animateTo(if (isFocused) 1.08f else 1f, tween(250)) }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(16.dp),
            focusedShape = RoundedCornerShape(16.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NasMusicColors.Surface,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Danger.copy(alpha = 0.85f),
            focusedContentColor = androidx.compose.ui.graphics.Color.White
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 0.95f
        )
    ) {
        Text(text = text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

@Composable
fun MiniIconButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String? = null) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = CircleShape
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch { animScale.animateTo(if (isFocused) 1.15f else 1f, tween(250)) }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = CircleShape,
            focusedShape = CircleShape
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NasMusicColors.SurfaceVariant,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
            focusedContentColor = Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 0.90f
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

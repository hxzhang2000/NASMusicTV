package com.nasmusic.tv.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nasmusic.tv.R
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.FocusableSurface
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
    onMoveItem: (Int, Int) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // Level 1.5: 队列列表已滚动时按 BACK 先回顶并聚焦第一个
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            if (!atTop) {
                scope.launch {
                    listState.scrollToItem(0)
                    runCatching { firstItemFocusRequester.requestFocus() }
                }
                true
            } else {
                false
            }
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

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
            Text(text = currentSong?.title ?: stringResource(R.string.player_no_song_selected),
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
                Text(text = stringResource(R.string.queue_title), color = NasMusicColors.TextPrimary, fontSize = 36.sp)
                Spacer(modifier = Modifier.weight(1f))
                Text(text = "${queue.size} 首", color = NasMusicColors.TextSecondary, fontSize = 16.sp)
                if (queue.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(24.dp))
                    QueueActionButton(text = stringResource(R.string.queue_clear), onClick = onClearQueue, icon = null)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (queue.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = stringResource(R.string.queue_empty), color = NasMusicColors.TextSecondary, fontSize = 24.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = stringResource(R.string.queue_go_to_library), color = NasMusicColors.TextSecondary, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    itemsIndexed(queue, key = { _, song -> song.id }) { index, song ->
                        val isCurrent = index == currentIndex
                        // 与 SongRow 相同的实现方式：
                        // 外层 Box(focusGroup) 承载背景/边框/缩放，onFocusChanged 用 hasFocus 追踪子树焦点
                        // 内部 Row 用兄弟级排列：左侧歌曲内容(clickable) + 右侧操作按钮
                        var isRowFocused by remember { mutableStateOf(false) }
                        val animScale = remember { Animatable(1f) }
                        val scope = rememberCoroutineScope()

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusGroup()
                                .scale(animScale.value)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    color = if (isRowFocused || isCurrent) NasMusicColors.Primary.copy(alpha = 0.2f) else NasMusicColors.Surface.copy(alpha = 0.5f)
                                )
                                .border(
                                    width = if (isRowFocused) 2.dp else 0.dp,
                                    color = if (isRowFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f) else Color.Transparent,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .onFocusChanged { state ->
                                    isRowFocused = state.hasFocus
                                    scope.launch {
                                        animScale.animateTo(
                                            if (isRowFocused) 1.02f else 1f,
                                            tween(200)
                                        )
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 左侧可聚焦+可点击区域（点击播放歌曲）
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                                        .clickable { onPlaySong(index) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = String.format("%02d", index + 1),
                                        color = if (isCurrent) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.width(28.dp),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = song.title, color = if (isCurrent) NasMusicColors.Primary else NasMusicColors.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(text = song.artist.ifBlank { "—" }, color = NasMusicColors.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(text = TimeUtils.formatDuration(song.durationMs), color = NasMusicColors.TextSecondary, fontSize = 11.sp)
                                }
                                // 右侧操作按钮（独立可聚焦）
                                if (index > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    MoveButton(text = "↑", onClick = { onMoveItem(index, index - 1) })
                                }
                                if (index < queue.lastIndex) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    MoveButton(text = "↓", onClick = { onMoveItem(index, index + 1) })
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                MoveButton(text = "✕", onClick = { onRemoveSong(index) })
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
    FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.08f,
        animationDurationMs = 250,
        containerColor = NasMusicColors.Surface,
        contentColor = NasMusicColors.TextPrimary,
        focusedContainerColor = NasMusicColors.Danger.copy(alpha = 0.85f),
        focusedContentColor = androidx.compose.ui.graphics.Color.White,
        pressedScale = 0.95f
    ) {
        Text(text = text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

@Composable
private fun MoveButton(text: String, onClick: () -> Unit) {
    // 使用 Box + focusable + clickable 替代 FocusableSurface，确保在 focusGroup 内可独立聚焦
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .widthIn(min = 36.dp)
            .scale(animScale.value)
            .clip(RoundedCornerShape(6.dp))
            .background(
                color = if (isFocused) NasMusicColors.Primary.copy(alpha = 0.3f) else NasMusicColors.SurfaceVariant
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.isFocused
                scope.launch {
                    animScale.animateTo(
                        if (isFocused) 1.1f else 1f,
                        tween(150)
                    )
                }
            }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            color = if (isFocused) NasMusicColors.Primary else NasMusicColors.TextSecondary
        )
    }
}

@Composable
fun MiniIconButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String? = null) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        focusedScale = 1.15f,
        animationDurationMs = 250,
        containerColor = NasMusicColors.SurfaceVariant,
        contentColor = NasMusicColors.TextPrimary,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
        focusedContentColor = Color.Black,
        pressedScale = 0.90f
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

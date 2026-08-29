package com.nasmusic.tv.ui.components.song

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.sourceType
import com.nasmusic.tv.ui.components.common.CoverImage
import com.nasmusic.tv.ui.components.common.SourceBadge
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.TimeUtils
import kotlinx.coroutines.launch

/**
 * 歌曲行布局模式
 */
enum class SongRowMode {
    /** 水平行：封面 + 文字 + 操作，用于列表 */
    MODE_ROW,
    /** 卡片：封面在上，文字在下，用于网格 */
    MODE_CARD,
    /** 紧凑行：无封面，仅文字，用于队列 */
    MODE_COMPACT
}

/**
 * 统一歌曲行组件
 *
 * 替代 HomeScreen.HomeSongCard 等重复实现。
 * 支持三种布局模式，通过参数控制显示内容。
 *
 * @param song 歌曲数据
 * @param onClick 点击回调（播放歌曲）
 * @param mode 布局模式
 * @param index 列表序号（从 0 开始，显示为 index+1）。null 时显示播放图标
 * @param isFavorited 是否已收藏
 * @param onToggleFavorite 收藏切换回调（null 时不显示收藏按钮）
 * @param isInQueue 是否在播放队列中
 * @param onToggleQueue 队列切换回调（null 时不显示队列按钮）
 * @param onAddToPlaylist 添加到歌单回调（null 时不显示按钮）
 * @param onDelete 删除回调（null 时不显示按钮）。仅用于"可删除"上下文
 *                   （如歌单内移除歌曲），搜索/发现/曲库页不应传入
 * @param focusRequester 焦点请求器
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UnifiedSongRow(
    song: Song,
    onClick: () -> Unit,
    mode: SongRowMode = SongRowMode.MODE_ROW,
    index: Int? = null,
    isFavorited: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    isInQueue: Boolean = false,
    onToggleQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    when (mode) {
        SongRowMode.MODE_ROW -> SongRowModeRow(
            song = song,
            onClick = onClick,
            index = index,
            isFavorited = isFavorited,
            onToggleFavorite = onToggleFavorite,
            isInQueue = isInQueue,
            onToggleQueue = onToggleQueue,
            onAddToPlaylist = onAddToPlaylist,
            onDelete = onDelete,
            focusRequester = focusRequester,
            modifier = modifier
        )
        SongRowMode.MODE_CARD -> SongRowModeCard(
            song = song,
            onClick = onClick,
            focusRequester = focusRequester
        )
        SongRowMode.MODE_COMPACT -> SongRowModeCompact(
            song = song,
            onClick = onClick,
            index = index,
            focusRequester = focusRequester
        )
    }
}

/**
 * MODE_ROW：水平行布局（封面 + 文字 + 操作按钮）
 * 与现有 LibraryScreen.SongRow 样式一致
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongRowModeRow(
    song: Song,
    onClick: () -> Unit,
    index: Int?,
    isFavorited: Boolean,
    onToggleFavorite: (() -> Unit)?,
    isInQueue: Boolean,
    onToggleQueue: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier
) {
    var isRowFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(animScale.value)
            .clip(RoundedCornerShape(6.dp))
            .background(
                color = if (isRowFocused) NasMusicColors.Primary.copy(alpha = 0.2f)
                else NasMusicColors.Surface.copy(alpha = 0.5f)
            )
            .border(
                width = if (isRowFocused) 2.dp else 0.dp,
                color = if (isRowFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f)
                else Color.Transparent,
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
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：封面 + 序号/图标 + 文字（可点击播放）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (focusRequester != null) Modifier.focusRequester(focusRequester)
                        else Modifier
                    )
                    .focusable()
                    .clickable { onClick() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 封面缩略图
                CoverImage(
                    coverUrl = song.coverUrl,
                    contentDescription = song.title,
                    size = 92.dp,
                    cornerRadius = 4.dp
                )
                Spacer(modifier = Modifier.width(14.dp))

                // 序号或播放图标
                if (index != null) {
                        Text(
                        text = String.format("%02d", index + 1),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.button(),
                        modifier = Modifier.width(36.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                } else {
                        Text(
                        text = "▶",
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.button(),
                        modifier = Modifier.width(36.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))

                // 歌曲信息 + 来源标签
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = song.title,
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.button(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        SourceBadge(song = song)
                    }
                        Text(
                        text = song.artist.ifBlank { "-" },
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.button(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))

                // 时长
                Text(
                    text = TimeUtils.formatDuration(song.durationMs),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.button()
                )
            }

            // 右侧操作按钮（独立可聚焦 + 可点击，触屏可点）
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onToggleFavorite != null) {
                    RowActionButton(
                        text = if (isFavorited) "♥" else "♡",
                        color = if (isFavorited) NasMusicColors.Warning else NasMusicColors.TextPrimary,
                        onClick = onToggleFavorite
                    )
                }
                if (onToggleQueue != null) {
                    Spacer(modifier = Modifier.width(10.dp))
                    RowActionButton(
                        text = if (isInQueue) "✓" else "☰",
                        color = if (isInQueue) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                        onClick = onToggleQueue
                    )
                }
                if (onAddToPlaylist != null) {
                    Spacer(modifier = Modifier.width(10.dp))
                    RowActionButton(
                        text = "+",
                        color = NasMusicColors.TextPrimary,
                        onClick = onAddToPlaylist
                    )
                }
                if (onDelete != null) {
                    Spacer(modifier = Modifier.width(10.dp))
                    RowActionButton(
                        text = "✕",
                        color = NasMusicColors.Warning,
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

/**
 * 行内操作小按钮（收藏 / 队列 / 歌单）— Box + focusable + clickable，
 * 触屏与遥控器（D-Pad）均可操作，带焦点放大反馈与触控目标尺寸。
 */
@Composable
private fun RowActionButton(
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .widthIn(min = 48.dp)
            .scale(animScale.value)
            .clip(RoundedCornerShape(8.dp))
            .background(
                color = if (isFocused) color.copy(alpha = 0.25f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.isFocused
                scope.launch {
                    animScale.animateTo(
                        if (isFocused) 1.15f else 1f,
                        tween(150)
                    )
                }
            }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = FontSize.subtitle(),
            color = color
        )
    }
}

/**
 * MODE_CARD：卡片布局（封面在上，文字在下）
 * 与现有 HomeScreen.HomeSongCard 样式一致
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongRowModeCard(
    song: Song,
    onClick: () -> Unit,
    focusRequester: FocusRequester?
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .width(160.dp)
            .scale(animScale.value)
            .clip(RoundedCornerShape(12.dp))
            .background(
                color = if (isFocused) NasMusicColors.Primary.copy(alpha = 0.15f)
                else NasMusicColors.Surface.copy(alpha = 0.6f)
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f)
                else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.hasFocus
                scope.launch {
                    animScale.animateTo(
                        if (isFocused) 1.06f else 1f,
                        tween(200)
                    )
                }
            }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(6.dp)
        ) {
            // 封面
            CoverImage(
                coverUrl = song.coverUrl,
                contentDescription = song.title,
                size = 148.dp,
                cornerRadius = 8.dp
            )
            Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = song.title,
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.body(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist.ifBlank { "-" },
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.small(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * MODE_COMPACT：紧凑行（无封面，仅文字 + 序号）
 * 用于播放队列等空间紧张的场景
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongRowModeCompact(
    song: Song,
    onClick: () -> Unit,
    index: Int?,
    focusRequester: FocusRequester?
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(animScale.value)
            .clip(RoundedCornerShape(6.dp))
            .background(
                color = if (isFocused) NasMusicColors.Primary.copy(alpha = 0.2f)
                else Color.Transparent
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f)
                else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.hasFocus
                scope.launch {
                    animScale.animateTo(
                        if (isFocused) 1.02f else 1f,
                        tween(200)
                    )
                }
            }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号
            if (index != null) {
                    Text(
                    text = "${index + 1}.",
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                    modifier = Modifier.width(30.dp)
                )
            }
            // 歌曲信息
            Column(modifier = Modifier.weight(1f)) {
                    Text(
                    text = song.title,
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist.ifBlank { "-" },
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.small(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 时长
            Text(
                text = TimeUtils.formatDuration(song.durationMs),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.small()
            )
        }
    }
}

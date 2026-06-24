package com.nasmusic.tv.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.components.BackButton
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.util.TimeUtils
import kotlinx.coroutines.launch

/**
 * 专辑详情屏幕
 * 顶部：专辑封面 + 元数据
 * 下方：曲目列表（可逐首播放）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    album: Album,
    songs: List<Song>,
    onPlaySong: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onBack: () -> Unit,
    queueSongIds: Set<String> = emptySet(),
    onToggleQueue: (Song) -> Unit = {},
    favoriteIds: Set<String> = emptySet(),
    onToggleFavorite: (Song) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // Level 1.5: 曲目列表已滚动时按 BACK 先回顶并聚焦第一个
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

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)
    ) {
        // 返回 + 标题行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = album.name,
                color = NasMusicColors.TextPrimary,
                fontSize = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // 左侧：专辑封面
            Box(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(NasMusicColors.SurfaceVariant)
            ) {
                if (!album.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = album.coverUrl,
                        contentDescription = album.name,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "♪", color = NasMusicColors.TextSecondary, fontSize = 64.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.width(24.dp))

            // 右侧：专辑信息 + 曲目列表
            Column(modifier = Modifier.weight(1f)) {
                // 专辑元数据
                Text(
                    text = album.artist.ifBlank { "—" },
                    color = NasMusicColors.TextSecondary,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    if (album.year != null) {
                        Text(
                            text = "${album.year} · ",
                            color = NasMusicColors.TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                    Text(
                        text = "${album.songCount} 首曲目",
                        color = NasMusicColors.TextSecondary,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                // 播放全部按钮
                ButtonChip(text = stringResource(R.string.common_play_all)) {
                    if (songs.isNotEmpty()) onPlayAll(songs)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 曲目列表
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                        // 将行拆分为左侧内容（播放）和右侧队列按钮两个独立可聚焦区域
                        var isRowFocused by remember { mutableStateOf(false) }
                        val animScale = remember { Animatable(1f) }
                        val rowScope = rememberCoroutineScope()

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusGroup()
                                .scale(animScale.value)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    color = if (isRowFocused) NasMusicColors.Primary.copy(alpha = 0.2f) else NasMusicColors.Surface.copy(alpha = 0.5f)
                                )
                                .border(
                                    width = if (isRowFocused) 2.dp else 0.dp,
                                    color = if (isRowFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f) else Color.Transparent,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .onFocusChanged { state ->
                                    isRowFocused = state.hasFocus
                                    rowScope.launch {
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
                                        .clickable { onPlaySong(song) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        color = NasMusicColors.TextSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.width(28.dp),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            color = NasMusicColors.TextPrimary,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = song.artist.ifBlank { "—" },
                                            color = NasMusicColors.TextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = TimeUtils.formatDuration(song.durationMs),
                                        color = NasMusicColors.TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                FavoriteButton(
                                    isFavorite = song.id in favoriteIds,
                                    onClick = { onToggleFavorite(song) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                com.nasmusic.tv.ui.screens.QueueToggleButton(
                                    isInQueue = song.id in queueSongIds,
                                    onClick = { onToggleQueue(song) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

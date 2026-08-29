package com.nasmusic.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.components.BackButton
import com.nasmusic.tv.ui.components.song.UnifiedSongRow
import com.nasmusic.tv.ui.components.song.SongRowMode
import com.nasmusic.tv.ui.components.common.CoverImage
import com.nasmusic.tv.ui.components.common.ActionBar
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
                fontSize = FontSize.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // 左侧：专辑封面
            CoverImage(
                coverUrl = album.coverUrl,
                contentDescription = album.name,
                size = 280.dp,
                cornerRadius = 16.dp
            )

            Spacer(modifier = Modifier.width(24.dp))

            // 右侧：专辑信息 + 曲目列表
            Column(modifier = Modifier.weight(1f)) {
                // 专辑元数据
                Text(
                    text = album.artist.ifBlank { "—" },
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.Button
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    if (album.year != null) {
                        Text(
                            text = "${album.year} · ",
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.Button
                        )
                    }
                    Text(
                        text = "${album.songCount} 首曲目",
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.Button
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                // 操作栏
                ActionBar(
                    songCount = songs.size,
                    onPlayAll = { if (songs.isNotEmpty()) onPlayAll(songs) },
                    onAddAllToQueue = { songs.forEach { song -> onToggleQueue(song) } }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 曲目列表
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                        UnifiedSongRow(
                            song = song,
                            onClick = { onPlaySong(song) },
                            mode = SongRowMode.MODE_ROW,
                            index = index,
                            isFavorited = song.id in favoriteIds,
                            onToggleFavorite = { onToggleFavorite(song) },
                            isInQueue = song.id in queueSongIds,
                            onToggleQueue = { onToggleQueue(song) },
                            focusRequester = if (index == 0) firstItemFocusRequester else null
                        )
                    }
                }
            }
        }
    }
}

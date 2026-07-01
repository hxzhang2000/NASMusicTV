package com.nasmusic.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.BackButton
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 网络推荐播放列表详情页
 * 顶部：返回按钮 + 播放列表标题
 * 下方：LazyVerticalGrid(2 列) 歌曲列表
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NetworkPlaylistDetailScreen(
    playlistSongs: List<Song>,
    playlistTitle: String,
    onPlaySong: (Song) -> Unit = {},
    onPlayAll: () -> Unit = {},
    queueSongIds: Set<String> = emptySet(),
    onToggleQueue: (Song) -> Unit = {},
    networkFavoriteIds: Set<String> = emptySet(),
    onToggleFavorite: (Song) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyGridState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // Level 1.5: 列表已滚动时按 BACK 先回顶并聚焦第一个；已在顶部时返回网络音乐页
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
                onBack()
                true
            }
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        // 返回 + 标题行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = playlistTitle,
                color = NasMusicColors.TextPrimary,
                fontSize = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 全部播放按钮 + 歌曲数量
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (playlistSongs.isNotEmpty()) {
                FocusableSurface(
                    onClick = onPlayAll,
                    shape = RoundedCornerShape(8.dp),
                    focusedScale = 1.08f,
                    animationDurationMs = 150,
                    containerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                    focusedContainerColor = NasMusicColors.Primary,
                    contentColor = androidx.compose.ui.graphics.Color.Black,
                    focusedContentColor = androidx.compose.ui.graphics.Color.Black
                ) {
                    Text(
                        text = "全部播放 ▶",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
            }
            Text(
                text = "${playlistSongs.size} 首曲目",
                color = NasMusicColors.TextSecondary,
                fontSize = 14.sp
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (playlistSongs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无歌曲",
                    color = NasMusicColors.TextSecondary,
                    fontSize = 16.sp
                )
            }
        } else {
            LazyVerticalGrid(
                state = listState,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(playlistSongs, key = { _, song -> song.id }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { onPlaySong(song) },
                        isFavorite = song.id in networkFavoriteIds,
                        isInQueue = song.id in queueSongIds,
                        onToggleQueue = { onToggleQueue(song) },
                        isFavorited = song.id in networkFavoriteIds,
                        onToggleFavorite = { onToggleFavorite(song) },
                        focusRequester = if (index == 0) firstItemFocusRequester else null
                    )
                }
            }
        }
    }
}

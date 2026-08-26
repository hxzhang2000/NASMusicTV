package com.nasmusic.tv.ui.components.song

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.components.songGridColumns
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 统一歌曲网格组件
 *
 * 使用 LazyVerticalGrid 展示歌曲列表，每个 item 使用 UnifiedSongRow(MODE_CARD)。
 * 响应式列数由 songGridColumns() 提供。
 *
 * @param songs 歌曲列表
 * @param modifier 额外 Modifier
 * @param onPlaySong 点击歌曲回调
 * @param onToggleFavorite 收藏切换回调
 * @param onToggleQueue 加入/移出队列回调
 * @param isFavorited 判断歌曲是否已收藏
 * @param isInQueue 判断歌曲是否在队列中
 * @param emptyMessage 空状态提示文字
 * @param header 可选的网格头部内容（跨列显示）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UnifiedSongGrid(
    songs: List<Song>,
    modifier: Modifier = Modifier,
    onPlaySong: (Song) -> Unit = {},
    onToggleFavorite: (Song) -> Unit = {},
    onToggleQueue: (Song) -> Unit = {},
    isFavorited: (String) -> Boolean = { false },
    isInQueue: (String) -> Boolean = { false },
    emptyMessage: String = "暂无歌曲",
    header: @Composable (() -> Unit)? = null
) {
    val listState = rememberLazyGridState()

    LazyVerticalGrid(
        columns = songGridColumns(),
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 头部内容（跨列）
        if (header != null) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                header()
            }
        }

        // 空状态
        if (songs.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emptyMessage,
                        color = NasMusicColors.TextSecondary,
                        fontSize = 20.sp
                    )
                }
            }
        }

        // 歌曲列表
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            UnifiedSongRow(
                song = song,
                mode = SongRowMode.MODE_CARD,
                onClick = { onPlaySong(song) },
                isFavorited = isFavorited(song.id),
                onToggleFavorite = { onToggleFavorite(song) },
                isInQueue = isInQueue(song.id),
                onToggleQueue = { onToggleQueue(song) }
            )
        }

        // 底部间距
        if (songs.isNotEmpty()) {
            item(key = "bottom_spacer", span = { GridItemSpan(maxLineSpan) }) {
                // 底部间距
            }
        }
    }
}

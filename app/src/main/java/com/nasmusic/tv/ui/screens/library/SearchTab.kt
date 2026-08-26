package com.nasmusic.tv.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.common.ActionBar
import com.nasmusic.tv.ui.components.common.LoadingIndicator
import com.nasmusic.tv.ui.components.song.UnifiedSongRow
import com.nasmusic.tv.ui.components.song.SongRowMode
import com.nasmusic.tv.ui.components.songGridColumns
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 统一搜索 Tab（曲库搜索）
 *
 * 搜索结果展示页：搜索输入由曲库页顶部搜索框驱动（跨源融合搜索），
 * 本 Tab 只负责展示搜索结果列表（包含来源标签）。
 * 搜索歌曲使用 UnifiedSongRow(MODE_ROW)，支持收藏/队列操作。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchTab(
    searchKeyword: String,
    searchResults: List<Song>,
    isSearching: Boolean,
    favoriteIds: Set<String>,
    queueSongIds: Set<String> = emptySet(),
    onPlaySong: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onToggleQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onPlayAll: () -> Unit = {},
    onShuffleSearch: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val firstItemFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // 列表回顶
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

    Column(modifier = modifier.fillMaxWidth()) {
        when {
            isSearching -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }
            searchKeyword.isNotBlank() && searchResults.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "未找到相关结果",
                        color = NasMusicColors.TextSecondary,
                        fontSize = 21.sp
                    )
                }
            }
            searchKeyword.isNotBlank() -> {
                // 操作栏
                ActionBar(
                    songCount = searchResults.size,
                    onPlayAll = onPlayAll,
                    onAddAllToQueue = onPlayAll
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 歌曲列表
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = songGridColumns(),
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(searchResults.size, key = { searchResults[it].id }) { index ->
                        val song = searchResults[index]
                        UnifiedSongRow(
                            song = song,
                            mode = SongRowMode.MODE_ROW,
                            onClick = { onPlaySong(song) },
                            isFavorited = song.id in favoriteIds,
                            onToggleFavorite = { onToggleFavorite(song) },
                            isInQueue = song.id in queueSongIds,
                            onToggleQueue = { onToggleQueue(song) },
                            onAddToPlaylist = { onAddToPlaylist(song) },
                            index = index,
                            focusRequester = if (index == 0) firstItemFocusRequester else null
                        )
                    }
                }
            }
            else -> {
                // 无搜索关键词：引导
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "输入关键词搜索音乐",
                        color = NasMusicColors.TextSecondary,
                        fontSize = 21.sp
                    )
                }
            }
        }
    }
}

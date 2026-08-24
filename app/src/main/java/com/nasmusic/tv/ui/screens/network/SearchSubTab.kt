package com.nasmusic.tv.ui.screens.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.SearchHistoryItem
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.SearchField
import com.nasmusic.tv.ui.components.songGridColumns
import com.nasmusic.tv.ui.screens.SongRow
import com.nasmusic.tv.ui.screens.TextInputDialog
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 搜索子 Tab
 *
 * 包含搜索栏输入触发 + 搜索结果列表。
 * 搜索输入使用 TextInputDialog（开启二维码扫码 + 搜索历史）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchSubTab(
    searchKeyword: String,
    searchResults: List<Song>,
    isSearching: Boolean,
    favoriteIds: Set<String>,
    queueSongIds: Set<String> = emptySet(),
    historyItems: List<SearchHistoryItem> = emptyList(),
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onToggleQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onPlayAll: () -> Unit = {},
    onShuffleSearch: () -> Unit = {},
    onAddAllToQueue: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showSearchDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyGridState()
    val firstItemFocusRequester = remember { FocusRequester() }
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
        // 搜索栏（统一样式：点击弹出输入对话框，无独立搜索按钮）
        SearchField(
            query = searchKeyword,
            placeholder = "搜索歌曲、专辑、歌手...",
            onOpenSearch = { showSearchDialog = true },
            onClear = { onClearSearch() }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 内容
        when {
            isSearching -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "搜索中...",
                        color = NasMusicColors.TextSecondary,
                        fontSize = 21.sp
                    )
                }
            }
            searchKeyword.isNotBlank() && searchResults.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.network_no_results),
                        color = NasMusicColors.TextSecondary,
                        fontSize = 21.sp
                    )
                }
            }
            searchKeyword.isNotBlank() -> {
                // 搜索结果
                LazyVerticalGrid(
                    columns = songGridColumns(),
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 操作栏：播放全部 / 全部加入列表 / 换一批（去重由 ViewModel 处理）
                    if (searchResults.isNotEmpty()) {
                        item(key = "search_action_bar", span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FocusableSurface(
                                    onClick = onPlayAll,
                                    shape = RoundedCornerShape(8.dp),
                                    focusedScale = 1.08f,
                                    animationDurationMs = 150,
                                    containerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                                    focusedContainerColor = NasMusicColors.Primary,
                                    contentColor = NasMusicColors.TextPrimary,
                                    focusedContentColor = NasMusicColors.TextPrimary
                                ) {
                                    Text(
                                        text = "全部播放 ▶",
                                        fontSize = 19.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                FocusableSurface(
                                    onClick = onAddAllToQueue,
                                    shape = RoundedCornerShape(8.dp),
                                    focusedScale = 1.08f,
                                    animationDurationMs = 150,
                                    containerColor = NasMusicColors.SurfaceVariant.copy(alpha = 0.6f),
                                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                                    contentColor = NasMusicColors.TextPrimary,
                                    focusedContentColor = NasMusicColors.Primary
                                ) {
                                    Text(
                                        text = "全部加入列表 +",
                                        fontSize = 19.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                FocusableSurface(
                                    onClick = onShuffleSearch,
                                    shape = RoundedCornerShape(8.dp),
                                    focusedScale = 1.08f,
                                    animationDurationMs = 150,
                                    containerColor = NasMusicColors.SurfaceVariant.copy(alpha = 0.6f),
                                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                                    contentColor = NasMusicColors.TextPrimary,
                                    focusedContentColor = NasMusicColors.Primary
                                ) {
                                    Text(
                                        text = "换一批 ↻",
                                        fontSize = 19.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "${searchResults.size} 首",
                                    color = NasMusicColors.TextSecondary,
                                    fontSize = 18.sp
                                )
                            }
                        }
                    }
                    itemsIndexed(searchResults, key = { _, song -> song.id }) { index, song ->
                        SongRow(
                            song = song,
                            index = index,
                            onClick = { onPlaySong(song) },
                            isFavorited = song.id in favoriteIds,
                            onToggleFavorite = { onToggleFavorite(song) },
                            isInQueue = song.id in queueSongIds,
                            onToggleQueue = { onToggleQueue(song) },
                            onAddToPlaylist = { onAddToPlaylist(song) },
                            focusRequester = if (index == 0) firstItemFocusRequester else null
                        )
                    }
                }
            }
            else -> {
                // 无搜索关键词：引导提示
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "输入关键词搜索网络音乐",
                        color = NasMusicColors.TextSecondary,
                        fontSize = 21.sp
                    )
                }
            }
        }
    }

    // 搜索输入对话框
    if (showSearchDialog) {
        TextInputDialog(
            title = stringResource(R.string.nav_network),
            hint = stringResource(R.string.network_search_hint),
            initialValue = searchKeyword,
            onConfirm = { input ->
                onSearch(input)
                showSearchDialog = false
            },
            onDismiss = { showSearchDialog = false },
            showQrCode = true,
            showHistory = true,
            historyItems = historyItems,
            onHistorySelect = { query ->
                onSearch(query)
                showSearchDialog = false
            }
        )
    }
}

/**
 * 搜索栏组件（已迁移至公共 SearchField：统一样式，见 ui/components/CommonComponents.kt）
 */

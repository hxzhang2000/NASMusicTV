package com.nasmusic.tv.ui.screens.network

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.screens.SongRow
import com.nasmusic.tv.ui.screens.TextInputDialog
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 搜索子 Tab
 *
 * 包含搜索栏输入触发 + 搜索结果列表。
 * 搜索输入使用 TextInputDialog（与现有 NetworkScreen 一致）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchSubTab(
    searchKeyword: String,
    searchResults: List<Song>,
    isSearching: Boolean,
    favoriteIds: Set<String>,
    queueSongIds: Set<String> = emptySet(),
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
        // 搜索栏
        SearchBar(
            query = searchKeyword,
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
                        fontSize = 16.sp
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
                        fontSize = 16.sp
                    )
                }
            }
            searchKeyword.isNotBlank() -> {
                // 搜索结果
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 操作栏：播放全部 / 全部加入列表 / 换一批（去重由 ViewModel 处理）
                    if (searchResults.isNotEmpty()) {
                        item(key = "search_action_bar", span = { GridItemSpan(2) }) {
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
                                    contentColor = Color.Black,
                                    focusedContentColor = Color.Black
                                ) {
                                    Text(
                                        text = "全部播放 ▶",
                                        fontSize = 14.sp,
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
                                        fontSize = 14.sp,
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
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "${searchResults.size} 首",
                                    color = NasMusicColors.TextSecondary,
                                    fontSize = 13.sp
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
                        fontSize = 16.sp
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
            onDismiss = { showSearchDialog = false }
        )
    }
}

/**
 * 搜索栏组件（从原有 NetworkScreen 搬移）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onOpenSearch: () -> Unit,
    onClear: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onOpenSearch,
            modifier = Modifier
                .width(340.dp)
                .height(38.dp)
                .scale(animScale.value)
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) NasMusicColors.FocusRing else NasMusicColors.SurfaceVariant,
                    shape = RoundedCornerShape(10.dp)
                )
                .onFocusChanged {
                    isFocused = it.isFocused
                    scope.launch {
                        animScale.animateTo(if (isFocused) 1.04f else 1f, tween(200))
                    }
                },
            shape = ClickableSurfaceDefaults.shape(
                shape = RoundedCornerShape(10.dp),
                focusedShape = RoundedCornerShape(10.dp)
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NasMusicColors.Surface.copy(alpha = 0.6f),
                contentColor = NasMusicColors.TextPrimary,
                focusedContainerColor = NasMusicColors.Surface.copy(alpha = 0.8f),
                focusedContentColor = NasMusicColors.TextPrimary
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.97f)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "\uD83D\uDD0D",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = if (query.isEmpty()) "搜索歌曲、专辑、歌手..." else query,
                    color = if (query.isEmpty()) NasMusicColors.TextSecondary else NasMusicColors.TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        FocusableSurface(
            onClick = {
                if (query.isNotEmpty()) onClear()
                else onOpenSearch()
            },
            modifier = Modifier.height(38.dp),
            shape = RoundedCornerShape(10.dp),
            focusedScale = 1.06f,
            animationDurationMs = 200,
            containerColor = NasMusicColors.Surface.copy(alpha = 0.6f),
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
            contentColor = if (query.isNotEmpty()) NasMusicColors.Warning else NasMusicColors.Primary,
            focusedContentColor = NasMusicColors.Primary
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (query.isNotEmpty()) "清除" else "搜索",
                    fontSize = 13.sp
                )
            }
        }
    }
}

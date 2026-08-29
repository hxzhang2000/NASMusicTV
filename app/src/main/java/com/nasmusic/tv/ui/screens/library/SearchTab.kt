package com.nasmusic.tv.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
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
    // 来源点亮（搜索范围选择）
    enabledSources: Set<MusicSourceType> = emptySet(),
    onToggleSource: (MusicSourceType) -> Unit = {},
    onEnableAllSources: () -> Unit = {},
    onPlaySong: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onToggleQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onPlayAll: () -> Unit = {},
    onAddAllToQueue: () -> Unit = {},
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
        // 来源点亮栏（决定搜索范围）
        SearchSourceBar(
            enabledSources = enabledSources,
            onToggleSource = onToggleSource,
            onEnableAll = onEnableAllSources
        )
        Spacer(modifier = Modifier.height(12.dp))

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
                    onAddAllToQueue = onAddAllToQueue
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

/**
 * 来源点亮栏（点亮模式）
 *
 * 决定"搜索哪些源"：点亮的源参与搜索，熄灭的源不参与。
 * 每个来源一个可切换 chip（点亮 = 亮色，熄灭 = 暗色），外加"全部点亮"按钮。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchSourceBar(
    enabledSources: Set<MusicSourceType>,
    onToggleSource: (MusicSourceType) -> Unit,
    onEnableAll: () -> Unit
) {
    // 可点亮的搜索源（与 MusicSourceType.DEFAULT_SEARCH_SOURCES 一致）
    val searchableSources = MusicSourceType.DEFAULT_SEARCH_SOURCES.toList()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "搜索来源",
            color = NasMusicColors.TextSecondary,
            fontSize = 17.sp,
            modifier = Modifier.padding(end = 10.dp)
        )
        searchableSources.forEach { source ->
            val isEnabled = source in enabledSources
            Spacer(modifier = Modifier.width(8.dp))
            FocusableSurface(
                onClick = { onToggleSource(source) },
                shape = RoundedCornerShape(8.dp),
                focusedScale = 1.08f,
                animationDurationMs = 150,
                containerColor = if (isEnabled) NasMusicColors.Primary
                else NasMusicColors.Primary.copy(alpha = 0.2f),
                focusedContainerColor = NasMusicColors.Primary,
                contentColor = if (isEnabled) Color(0xFF0C1222)
                else NasMusicColors.TextPrimary,
                focusedContentColor = Color(0xFF0C1222)
            ) {
                Text(
                    text = (if (isEnabled) "● " else "○ ") + source.displayName,
                    color = LocalFocusableContentColor.current,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        FocusableSurface(
            onClick = onEnableAll,
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1.08f,
            animationDurationMs = 150,
            containerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
            focusedContainerColor = NasMusicColors.Primary,
            contentColor = NasMusicColors.TextPrimary,
            focusedContentColor = NasMusicColors.TextPrimary
        ) {
            Text(
                text = "全部点亮",
                color = LocalFocusableContentColor.current,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

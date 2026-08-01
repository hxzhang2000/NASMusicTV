package com.nasmusic.tv.ui.screens.network

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.BrowseDimension
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.screens.SongRow
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 多维度浏览子 Tab
 *
 * 纵向排列各维度筛选行 → 操作栏（播放全部 + 换一批）→ 歌曲列表
 *
 * D-Pad: 上下在筛选行/操作栏/列表间导航；左右在行内选择选项。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseSubTab(
    selections: List<Int>,
    browseResults: UiState<List<Song>>,
    isSearching: Boolean,
    networkFavoriteIds: Set<String>,
    queueSongIds: Set<String>,
    onSelectOption: (dimensionIndex: Int, optionIndex: Int) -> Unit,
    onRefresh: () -> Unit,
    onPlayAll: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onToggleQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    modifier: Modifier = Modifier
) {
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

    val dimensions = remember { BrowseDimension.entries }
    val results = browseResults.dataOrNull()
    val hasSelection = selections.indices.any { i ->
        val opt = dimensions[i].options.getOrNull(selections[i])
        opt != null && opt.label != "所有"
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 各维度筛选行
        dimensions.forEachIndexed { dimIdx, dimension ->
            item(key = "dim_${dimension.name}", span = { GridItemSpan(2) }) {
                DimensionRow(
                    dimension = dimension,
                    selectedIndex = selections.getOrNull(dimIdx) ?: 0,
                    onSelectOption = { optIdx ->
                        onSelectOption(dimIdx, optIdx)
                    },
                    focusRequester = if (dimIdx == 0) firstItemFocusRequester else null
                )
            }
        }

        // 操作栏：播放全部 + 换一批
        item(key = "action_bar", span = { GridItemSpan(2) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasSelection && !results.isNullOrEmpty()) {
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
                }
                if (hasSelection) {
                    FocusableSurface(
                        onClick = onRefresh,
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 150,
                        containerColor = NasMusicColors.Surface.copy(alpha = 0.7f),
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                        contentColor = NasMusicColors.TextSecondary,
                        focusedContentColor = NasMusicColors.Primary
                    ) {
                        Text(
                            text = "换一批 ↻",
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                if (hasSelection && results != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${results.size} 首",
                        color = NasMusicColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // 内容区域
        item(key = "content", span = { GridItemSpan(2) }) {
            when {
                isSearching -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "搜索中...",
                            color = NasMusicColors.TextSecondary,
                            fontSize = 15.sp
                        )
                    }
                }
                !hasSelection -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "请至少选择一个筛选条件",
                            color = NasMusicColors.TextSecondary,
                            fontSize = 15.sp
                        )
                    }
                }
                results != null && results.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "未找到相关歌曲，换一批试试",
                            color = NasMusicColors.TextSecondary,
                            fontSize = 15.sp
                        )
                    }
                }
                results != null && results.isNotEmpty() -> {
                    // 歌曲会在下面的歌曲列表 items 中渲染，这里占位
                }
                browseResults is UiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "搜索失败，请重试",
                            color = NasMusicColors.Warning,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }

        // 歌曲列表
        if (results != null && results.isNotEmpty()) {
            gridItemsIndexed(results, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    index = index,
                    onClick = { onPlaySong(song) },
                    isFavorited = song.id in networkFavoriteIds,
                    onToggleFavorite = { onToggleFavorite(song) },
                    isInQueue = song.id in queueSongIds,
                    onToggleQueue = { onToggleQueue(song) },
                    onAddToPlaylist = { onAddToPlaylist(song) },
                    focusRequester = if (index == 0) null else null // 第一个自动聚焦
                )
            }
        }

        // 底部间距
        item(key = "bottom_spacer", span = { GridItemSpan(2) }) {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 单一维度筛选行：维度标签 + 横向选项按钮列表，同行显示
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DimensionRow(
    dimension: BrowseDimension,
    selectedIndex: Int,
    onSelectOption: (Int) -> Unit,
    focusRequester: FocusRequester? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dimension.displayName,
            color = NasMusicColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(end = 10.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(dimension.options, key = { index, _ -> "${dimension.name}_$index" }) { index, option ->
                val isSelected = index == selectedIndex
                ChipButton(
                    label = option.label,
                    isSelected = isSelected,
                    onClick = { onSelectOption(index) },
                    focusRequester = if (index == 0) focusRequester else null
                )
            }
        }
    }
}

/**
 * 单个选项 Chip 按钮
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChipButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected && isFocused -> NasMusicColors.Primary
            isSelected -> NasMusicColors.Primary.copy(alpha = 0.85f)
            isFocused -> NasMusicColors.Primary.copy(alpha = 0.25f)
            else -> NasMusicColors.Surface.copy(alpha = 0.5f)
        },
        label = "chipBg"
    )

    val textColor = when {
        isSelected -> Color.Black
        isFocused -> NasMusicColors.Primary
        else -> NasMusicColors.TextSecondary
    }

    FocusableSurface(
        onClick = onClick,
        modifier = (if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .scale(animScale.value)
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch {
                    animScale.animateTo(if (isFocused) 1.08f else 1f, tween(150))
                }
            },
        shape = RoundedCornerShape(20.dp),
        focusedScale = 1f,
        containerColor = bgColor,
        focusedContainerColor = bgColor,
        contentColor = textColor,
        focusedContentColor = if (isSelected) Color.Black else NasMusicColors.Primary
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

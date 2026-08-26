package com.nasmusic.tv.ui.screens.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.common.LoadingIndicator
import com.nasmusic.tv.ui.components.common.SectionHeader
import com.nasmusic.tv.ui.components.song.UnifiedSongRow
import com.nasmusic.tv.ui.components.song.SongRowMode
import com.nasmusic.tv.ui.components.songGridColumns
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 多维度浏览维度数据
 */
data class BrowseDimension(
    val label: String,
    val options: List<String>
)

/**
 * 统一"发现" Tab
 *
 * 网络音乐多维度筛选 + 推荐歌单 + 热门推荐。
 * 纵向排列各维度筛选行 → 操作栏（播放全部 + 换一批）→ 歌曲列表
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DiscoverTab(
    dimensions: List<BrowseDimension> = emptyList(),
    filteredSongs: List<Song>,
    isLoading: Boolean = false,
    favoriteIds: Set<String> = emptySet(),
    queueSongIds: Set<String> = emptySet(),
    currentDimensionValues: Map<String, String> = emptyMap(),
    onDimensionChanged: (String, String) -> Unit = { _, _ -> },
    onPlayAll: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onPlaySong: (Song) -> Unit = {},
    onToggleFavorite: (Song) -> Unit = {},
    onToggleQueue: (Song) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyGridState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

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
        // 多维度筛选行
        if (dimensions.isNotEmpty()) {
            dimensions.forEach { dimension ->
                DimensionRow(
                    dimension = dimension,
                    selectedValue = currentDimensionValues[dimension.label],
                    onSelected = { onDimensionChanged(dimension.label, it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }
            filteredSongs.isNotEmpty() -> {
                // 操作栏
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
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
                        onClick = onShuffle,
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
                        text = "${filteredSongs.size} 首",
                        color = NasMusicColors.TextSecondary,
                        fontSize = 18.sp
                    )
                }

                // 歌曲列表
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = songGridColumns(),
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredSongs.size, key = { filteredSongs[it].id }) { index ->
                        val song = filteredSongs[index]
                        UnifiedSongRow(
                            song = song,
                            mode = SongRowMode.MODE_ROW,
                            onClick = { onPlaySong(song) },
                            isFavorited = song.id in favoriteIds,
                            onToggleFavorite = { onToggleFavorite(song) },
                            isInQueue = song.id in queueSongIds,
                            onToggleQueue = { onToggleQueue(song) },
                            index = index,
                            focusRequester = if (index == 0) firstItemFocusRequester else null
                        )
                    }
                }
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "选择筛选条件浏览歌曲",
                        color = NasMusicColors.TextSecondary,
                        fontSize = 21.sp
                    )
                }
            }
        }
    }
}

/**
 * 单个维度筛选行
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DimensionRow(
    dimension: BrowseDimension,
    selectedValue: String?,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dimension.label,
            color = NasMusicColors.TextSecondary,
            fontSize = 17.sp,
            modifier = Modifier.width(70.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(dimension.options) { index, option ->
                val isSelected = option == selectedValue
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) NasMusicColors.Primary else Color.Transparent,
                    label = "dim_bg"
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary,
                    label = "dim_text"
                )
                FocusableSurface(
                    onClick = { onSelected(option) },
                    shape = RoundedCornerShape(8.dp),
                    focusedScale = 1.08f,
                    animationDurationMs = 150,
                    containerColor = bgColor,
                    focusedContainerColor = NasMusicColors.Primary,
                    contentColor = textColor,
                    focusedContentColor = NasMusicColors.TextPrimary,
                    modifier = Modifier.onFocusChanged { focusState ->
                        if (focusState.isFocused) onSelected(option)
                    }
                ) {
                    Text(
                        text = option,
                        fontSize = 17.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

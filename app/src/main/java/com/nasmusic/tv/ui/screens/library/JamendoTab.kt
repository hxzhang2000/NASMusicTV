package com.nasmusic.tv.ui.screens.library

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.SearchField
import com.nasmusic.tv.ui.components.song.SongRowMode
import com.nasmusic.tv.ui.components.song.UnifiedSongRow
import com.nasmusic.tv.ui.screens.TextInputDialog
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

private val JAMENDO_PRESET_TAGS = listOf("ambient", "electronic", "jazz", "filmscore", "chillout", "instrumental", "pop", "rock")

/**
 * Jamendo（CC 独立音乐）Tab（曲库子 Tab）
 *
 * 顶部：搜索按钮 + 快捷筛选行（热门 + 预置风格，可横向滑动）
 * 主体：单列 UnifiedSongRow 歌曲列表（封面 / 歌名 / 歌手，支持收藏与加入队列）
 * 未配置 Client ID 时显示引导卡。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun JamendoTab(
    jamendoState: UiState<List<Song>>,
    jamendoActiveTag: String,
    jamendoConfigured: Boolean,
    networkFavoriteIds: Set<String>,
    queueSongIds: Set<String>,
    onLoadHot: () -> Unit,
    onLoadTag: (String) -> Unit,
    onSearch: (String) -> Unit,
    onPlaySong: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onToggleQueue: (Song) -> Unit
) {
    var showSearchDialog by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.LazyListState()

    LaunchedEffect(Unit) {
        onLoadHot()
    }

    if (!jamendoConfigured) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            FocusableSurface(
                onClick = {},
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1f,
                animationDurationMs = 150,
                containerColor = NasMusicColors.Surface.copy(alpha = 0.6f),
                focusedContainerColor = NasMusicColors.Surface.copy(alpha = 0.6f),
                contentColor = NasMusicColors.TextPrimary,
                focusedContentColor = NasMusicColors.TextPrimary
            ) {
                Text(
                    text = stringResource(R.string.network_jamendo_no_key),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.Body,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp)
                )
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶部筛选行 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchField(
                query = jamendoActiveTag,
                placeholder = stringResource(R.string.network_search_hint),
                onOpenSearch = { showSearchDialog = true },
                onClear = { onLoadHot() },
                modifier = Modifier.width(340.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))

            val hotSelected = jamendoActiveTag.isBlank()
            FocusableSurface(
                onClick = onLoadHot,
                shape = RoundedCornerShape(8.dp),
                focusedScale = 1.05f,
                animationDurationMs = 150,
                containerColor = if (hotSelected) NasMusicColors.Primary
                                else NasMusicColors.Surface.copy(alpha = 0.6f),
                focusedContainerColor = NasMusicColors.Primary,
                contentColor = if (hotSelected) Color.Black else NasMusicColors.TextPrimary,
                focusedContentColor = NasMusicColors.TextPrimary
            ) {
                Text(
                    text = stringResource(R.string.network_jamendo_hot),
                    color = if (hotSelected) Color.Black else NasMusicColors.TextPrimary,
                    fontSize = FontSize.Small,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))

            JAMENDO_PRESET_TAGS.forEach { tag ->
                val isSelected = jamendoActiveTag == tag
                FocusableSurface(
                    onClick = { onLoadTag(tag) },
                    shape = RoundedCornerShape(8.dp),
                    focusedScale = 1.05f,
                    animationDurationMs = 150,
                    containerColor = if (isSelected) NasMusicColors.Primary
                                    else NasMusicColors.Surface.copy(alpha = 0.6f),
                    focusedContainerColor = NasMusicColors.Primary,
                    contentColor = if (isSelected) Color.Black else NasMusicColors.TextPrimary,
                    focusedContentColor = NasMusicColors.TextPrimary
                ) {
                    Text(
                        text = tag,
                        color = if (isSelected) Color.Black else NasMusicColors.TextPrimary,
                        fontSize = FontSize.Small,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // ── 歌曲列表 ──
        when (jamendoState) {
            is UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.common_loading), color = NasMusicColors.TextSecondary, fontSize = FontSize.Button)
                }
            }
            is UiState.Error -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.network_jamendo_load_failed),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.Button
                )
            }
            is UiState.Success -> {
                val songs = jamendoState.data
                if (songs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.network_jamendo_no_results),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.Button
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                            UnifiedSongRow(
                                song = song,
                                index = index,
                                onClick = { onPlaySong(song) },
                                mode = SongRowMode.MODE_ROW,
                                isInQueue = song.id in queueSongIds,
                                onToggleQueue = { onToggleQueue(song) },
                                isFavorited = song.id in networkFavoriteIds,
                                onToggleFavorite = { onToggleFavorite(song) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }

    if (showSearchDialog) {
        TextInputDialog(
            title = stringResource(R.string.network_search_hint),
            hint = stringResource(R.string.network_search_hint),
            initialValue = jamendoActiveTag,
            onConfirm = { kw ->
                if (kw.isNotBlank()) onSearch(kw)
                showSearchDialog = false
            },
            onDismiss = { showSearchDialog = false }
        )
    }
}

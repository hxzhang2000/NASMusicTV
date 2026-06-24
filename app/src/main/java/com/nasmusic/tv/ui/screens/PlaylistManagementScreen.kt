package com.nasmusic.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.nasmusic.tv.ui.LocalDialogBackHandler
import com.nasmusic.tv.ui.LocalListBackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.components.BackButton
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.util.TimeUtils
import kotlinx.coroutines.launch

/**
 * 播放列表管理屏幕
 * 左侧：播放列表示
 * 选中后右侧显示该播放列表的歌曲明细
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaylistManagementScreen(
    playlists: List<Playlist>,
    selectedPlaylistSongs: List<Song>,
    isLoading: Boolean,
    onSelectPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onPlayPlaylist: (Playlist) -> Unit,
    onRemoveSong: (String) -> Unit, // songId
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    val playlistListState = rememberLazyListState()
    val playlistFirstFocusRequester = remember { FocusRequester() }
    val songsListState = rememberLazyListState()
    val songsFirstFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // Level 1.5: 任意列表已滚动时按 BACK 先回顶并聚焦第一个
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            val leftScrolled = !(playlistListState.firstVisibleItemIndex == 0 &&
                    playlistListState.firstVisibleItemScrollOffset == 0)
            val rightScrolled = !(songsListState.firstVisibleItemIndex == 0 &&
                    songsListState.firstVisibleItemScrollOffset == 0)
            if (leftScrolled || rightScrolled) {
                scope.launch {
                    if (leftScrolled) {
                        playlistListState.scrollToItem(0)
                    }
                    if (rightScrolled) {
                        songsListState.scrollToItem(0)
                    }
                    // 优先聚焦右侧歌曲列表，其次左侧播放列表
                    if (rightScrolled) {
                        runCatching { songsFirstFocusRequester.requestFocus() }
                    } else {
                        runCatching { playlistFirstFocusRequester.requestFocus() }
                    }
                }
                true
            } else {
                false
            }
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)
        ) {
            // 返回 + 标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = onBack)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.playlist_title),
                    color = NasMusicColors.TextPrimary,
                    fontSize = 24.sp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // 左侧：播放列表示
                Column(
                    modifier = Modifier.width(320.dp).fillMaxHeight()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.library_favorites) + " (${playlists.size})",
                            color = NasMusicColors.TextPrimary,
                            fontSize = 16.sp
                        )
                        ButtonChip(text = "+ " + stringResource(R.string.playlist_create)) { showCreateDialog = true }
                    }

                Spacer(modifier = Modifier.height(12.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = stringResource(R.string.common_loading), color = NasMusicColors.TextSecondary, fontSize = 16.sp)
                    }
                } else if (playlists.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = stringResource(R.string.playlist_empty), color = NasMusicColors.TextSecondary, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(
                        state = playlistListState,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(playlists, key = { _, it -> it.id }) { index, playlist ->
                            FocusableSurface(
                                onClick = { onSelectPlaylist(playlist) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                focusedScale = 1.02f,
                                animationDurationMs = 200,
                                containerColor = NasMusicColors.Surface.copy(alpha = 0.5f),
                                contentColor = NasMusicColors.TextPrimary,
                                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                                pressedScale = 0.98f,
                                focusRequester = if (index == 0) playlistFirstFocusRequester else null
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "♪",
                                        color = NasMusicColors.Primary,
                                        fontSize = 18.sp,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.name,
                                            color = NasMusicColors.TextPrimary,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = stringResource(R.string.playlist_song_count, playlist.songCount),
                                            color = NasMusicColors.TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Row {
                                        ButtonChipSmall(
                                            text = stringResource(R.string.player_play),
                                            onClick = { onPlayPlaylist(playlist) }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        ButtonChipSmall(
                                            text = stringResource(R.string.common_delete),
                                            onClick = { onDeletePlaylist(playlist) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(24.dp))

            // 右侧：选中播放列表的歌曲明细
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                Text(
                    text = stringResource(R.string.playlist_track_list, selectedPlaylistSongs.size),
                    color = NasMusicColors.TextPrimary,
                    fontSize = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedPlaylistSongs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.playlist_select_hint),
                            color = NasMusicColors.TextSecondary,
                            fontSize = 16.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = songsListState,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(selectedPlaylistSongs, key = { _, it -> it.id }) { index, song ->
                            FocusableSurface(
                                onClick = { onRemoveSong(song.id) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(6.dp),
                                focusedScale = 1.02f,
                                animationDurationMs = 200,
                                containerColor = NasMusicColors.Surface.copy(alpha = 0.5f),
                                contentColor = NasMusicColors.TextPrimary,
                                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                                focusedContentColor = Color.Black,
                                pressedContainerColor = NasMusicColors.SurfaceVariant,
                                pressedScale = 0.98f,
                                focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f),
                                focusRequester = if (index == 0) songsFirstFocusRequester else null
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            color = NasMusicColors.TextPrimary,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = song.artist.ifBlank { "—" },
                                            color = NasMusicColors.TextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = TimeUtils.formatDuration(song.durationMs),
                                        color = NasMusicColors.TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }   // right Column
        }   // split Row
    }   // outer Column

    // 创建播放列表对话框
        if (showCreateDialog) {
            TextInputDialog(
                title = stringResource(R.string.playlist_create),
                hint = stringResource(R.string.playlist_create_hint),
                initialValue = "",
                onConfirm = { name ->
                    if (name.isNotBlank()) {
                        onCreatePlaylist(name)
                    }
                    showCreateDialog = false
                },
                onDismiss = { showCreateDialog = false }
            )
        }
    }   // Box
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ButtonChipSmall(text: String, onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.08f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Primary.copy(alpha = 0.8f),
        contentColor = Color.Black,
        focusedContainerColor = NasMusicColors.Primary,
        focusedContentColor = Color.Black,
        pressedScale = 0.95f
    ) {
        Text(text = text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

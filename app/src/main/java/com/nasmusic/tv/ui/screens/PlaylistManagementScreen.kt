package com.nasmusic.tv.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.nasmusic.tv.ui.LocalListBackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.UiMode
import com.nasmusic.tv.ui.components.BackButton
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.song.SongRowMode
import com.nasmusic.tv.ui.components.song.UnifiedSongRow
import com.nasmusic.tv.ui.screens.library.browse.ButtonChip
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

    // v2.36.0 竖屏（方案 §4.6 / P0-20）：320dp 侧栏在 360dp 屏上必溢出
    //   → 竖屏改「播放列表（一级）⇄ 歌曲明细（二级）」；横屏/TV 保持左右分栏
    val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait
    var portraitPlaylistName by remember { mutableStateOf<String?>(null) }

    // Level 1.5: 任意列表已滚动时按 BACK 先回顶并聚焦第一个
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            // 竖屏二级页（歌曲明细）先回一级（播放列表）
            if (isPhonePortrait && portraitPlaylistName != null) {
                portraitPlaylistName = null
                true
            } else {
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
                    fontSize = FontSize.title()
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 左侧：播放列表（竖屏 = 一级页，撑满）
            val playlistListPane: @Composable (Modifier) -> Unit = { m ->
                Column(modifier = m) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.library_favorites) + " (${playlists.size})",
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.button()
                        )
                        ButtonChip(
                            text = "+ " + stringResource(R.string.playlist_create),
                            onClick = { showCreateDialog = true }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = stringResource(R.string.common_loading), color = NasMusicColors.TextSecondary, fontSize = FontSize.button())
                        }
                    } else if (playlists.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = stringResource(R.string.playlist_empty), color = NasMusicColors.TextSecondary, fontSize = FontSize.button())
                        }
                    } else {
                        LazyColumn(
                            state = playlistListState,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(playlists, key = { _, it -> it.id }) { index, playlist ->
                                FocusableSurface(
                                    onClick = {
                                        // 竖屏：进入二级页（歌曲明细）
                                        portraitPlaylistName = playlist.name
                                        onSelectPlaylist(playlist)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    focusedScale = 1.06f,
                                    animationDurationMs = 200,
                                    containerColor = NasMusicColors.Surface,
                                    contentColor = NasMusicColors.TextPrimary,
                                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                                    focusedContentColor = NasMusicColors.Primary,
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
                                            fontSize = FontSize.subtitle(),
                                            modifier = Modifier.padding(end = 12.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = playlist.name,
                                                color = NasMusicColors.TextPrimary,
                                                fontSize = FontSize.button(),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = stringResource(R.string.playlist_song_count, playlist.songCount),
                                                color = LocalFocusableContentColor.current,
                                                fontSize = FontSize.small()
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
                                                onClick = {
                                                    // 竖屏：删掉的正是当前二级页 → 退回一级
                                                    if (portraitPlaylistName == playlist.name) {
                                                        portraitPlaylistName = null
                                                    }
                                                    onDeletePlaylist(playlist)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 右侧：选中播放列表的歌曲明细（竖屏 = 二级页）
            val playlistSongsPane: @Composable (Modifier) -> Unit = { m ->
                Column(modifier = m) {
                    Text(
                        text = stringResource(R.string.playlist_track_list, selectedPlaylistSongs.size),
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.button()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (selectedPlaylistSongs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.playlist_select_hint),
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.button()
                            )
                        }
                    } else {
                        LazyColumn(
                            state = songsListState,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(selectedPlaylistSongs, key = { _, it -> it.id }) { index, song ->
                                UnifiedSongRow(
                                    song = song,
                                    onClick = { onRemoveSong(song.id) },
                                    mode = SongRowMode.MODE_COMPACT,
                                    index = index,
                                    focusRequester = if (index == 0) songsFirstFocusRequester else null
                                )
                            }
                        }
                    }
                }
            }

            if (isPhonePortrait) {
                if (portraitPlaylistName == null) {
                    // 一级：播放列表
                    playlistListPane(Modifier.fillMaxWidth().weight(1f))
                } else {
                    // 二级：歌曲明细（带返回头）
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BackButton(onClick = { portraitPlaylistName = null })
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = portraitPlaylistName.orEmpty(),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.title(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    playlistSongsPane(Modifier.fillMaxWidth().weight(1f))
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    playlistListPane(Modifier.width(320.dp).fillMaxHeight())
                    Spacer(modifier = Modifier.width(24.dp))
                    playlistSongsPane(Modifier.weight(1f).fillMaxHeight())
                }
            }
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
        contentColor = NasMusicColors.TextPrimary,
        focusedContainerColor = NasMusicColors.Primary,
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.95f
    ) {
        Text(text = text, color = NasMusicColors.TextPrimary, fontSize = FontSize.body(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

package com.nasmusic.tv.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.LocalPhoneCompact
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.TimeUtils
import kotlinx.coroutines.launch

/**
 * 我的页面 — 双栏布局
 *
 * 左栏「收藏」：合并本地收藏（favoriteSongs）与网络收藏（networkFavoriteSongs），
 *             按 id 去重，支持播放 / 取消收藏 / 加入队列 / 加入歌单。
 * 右栏「本地歌单」：管理 DataStore 持久化的本地歌单（创建 / 播放 / 重命名 / 删除 /
 *              移除歌曲），歌单可展开查看歌曲列表。
 *
 * 说明：本地歌单可容纳 NAS 歌曲与网络歌曲混合；播放由外层按 isNetworkSong 路由。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MineScreen(
    // 左栏：收藏数据
    favoriteSongsState: UiState<List<Song>>,
    networkFavoriteSongs: List<Song>,
    // 右栏：本地歌单数据
    localPlaylists: List<LocalPlaylist>,
    // 通用
    queueSongIds: Set<String>,
    onPlaySong: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onToggleQueue: (Song) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onPlayPlaylist: (LocalPlaylist) -> Unit,
    onRemoveSongFromPlaylist: (String, String) -> Unit,
    onAddSongToPlaylist: (String, Song) -> Unit,
    // 功能入口（手机端底部导航未覆盖的页面：队列 / 网盘 / 设置）
    onOpenQueue: () -> Unit = {},
    onOpenNetdisk: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    // 收藏合并（本地 + 网络，按 id 去重）
    val favoriteSongsList = favoriteSongsState.dataOrNull() ?: emptyList()
    val mergedFavorites = remember(favoriteSongsList, networkFavoriteSongs) {
        val map = LinkedHashMap<String, Song>()
        favoriteSongsList.forEach { map[it.id] = it }
        networkFavoriteSongs.forEach { map[it.id] = it }
        map.values.toList()
    }

    // 加入歌单弹窗目标歌曲
    var pickerSong by remember { mutableStateOf<Song?>(null) }
    // 歌单展开状态（null = 全部收起）
    var expandedPlaylistId by remember { mutableStateOf<String?>(null) }
    // 新建歌单输入弹窗
    var showCreateDialog by remember { mutableStateOf(false) }
    // 重命名目标歌单
    var renameTarget by remember { mutableStateOf<LocalPlaylist?>(null) }

    // 外层容器：手机上下排布（单列）、TV 左右排布（双列）
    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
    val isPhone = LocalPhoneCompact.current
    if (isPhone) {
        // 手机端：整个页面单个 LazyColumn —— 收藏标题+歌曲 → 歌单标题+卡片+展开歌曲，统一滚动
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ===== 收藏区 =====
            item(key = "fav_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.mine_favorites),
                        color = NasMusicColors.TextPrimary,
                        fontSize = 33.sp,
                        modifier = Modifier.padding(end = 24.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (mergedFavorites.isNotEmpty()) {
                        ButtonChip(
                            text = stringResource(R.string.common_play_all),
                            onClick = { onPlayAll(mergedFavorites) }
                        )
                    }
                }
            }
            if (mergedFavorites.isEmpty()) {
                item(key = "fav_empty") {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.mine_favorites_empty),
                            color = NasMusicColors.TextSecondary,
                            fontSize = 21.sp
                        )
                    }
                }
            } else {
                items(mergedFavorites, key = { "fav_${it.id}" }) { song ->
                    SongRow(
                        song = song,
                        onClick = { onPlaySong(song) },
                        isFavorited = true,
                        onToggleFavorite = { onToggleFavorite(song) },
                        isInQueue = song.id in queueSongIds,
                        onToggleQueue = { onToggleQueue(song) },
                        onAddToPlaylist = { pickerSong = song }
                    )
                }
            }

            // 分隔
            item(key = "section_divider") { Spacer(modifier = Modifier.height(24.dp)) }

            // ===== 歌单区 =====
            item(key = "pl_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.mine_playlists),
                        color = NasMusicColors.TextPrimary,
                        fontSize = 33.sp,
                        modifier = Modifier.padding(end = 24.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    FocusableSurface(
                        onClick = { showCreateDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 250,
                        containerColor = NasMusicColors.Primary.copy(alpha = 0.8f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContainerColor = NasMusicColors.Primary,
                        focusedContentColor = NasMusicColors.TextPrimary,
                        pressedScale = 0.96f
                    ) {
                        Text(
                            text = "+ " + stringResource(R.string.mine_create_playlist),
                            fontSize = 19.sp,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                }
            }
            if (localPlaylists.isEmpty()) {
                item(key = "pl_empty") {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.mine_playlists_empty),
                            color = NasMusicColors.TextSecondary,
                            fontSize = 21.sp
                        )
                    }
                }
            } else {
                localPlaylists.forEach { playlist ->
                    item(key = "pl_card_${playlist.id}") {
                        PlaylistCard(
                            playlist = playlist,
                            expanded = expandedPlaylistId == playlist.id,
                            onToggleExpand = {
                                expandedPlaylistId = if (expandedPlaylistId == playlist.id) null else playlist.id
                            },
                            onPlay = { onPlayPlaylist(playlist) },
                            onRename = { renameTarget = playlist },
                            onDelete = { onDeletePlaylist(playlist.id) }
                        )
                    }
                    // 展开的歌单：歌曲作为独立 item 渲染（随页面统一滚动）
                    if (expandedPlaylistId == playlist.id) {
                        if (playlist.songs.isEmpty()) {
                            item(key = "pl_songs_empty_${playlist.id}") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.mine_playlists_empty),
                                        color = NasMusicColors.TextSecondary,
                                        fontSize = 18.sp
                                    )
                                }
                            }
                        } else {
                            items(playlist.songs, key = { "pl_song_${playlist.id}_${it.id}" }) { song ->
                                PlaylistSongRow(
                                    song = song,
                                    isInQueue = song.id in queueSongIds,
                                    onClick = { onPlaySong(song) },
                                    onToggleQueue = { onToggleQueue(song) },
                                    onRemove = { onRemoveSongFromPlaylist(playlist.id, song.id) },
                                    onAddToPlaylist = { pickerSong = song }
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            FavoritesPane(
                songs = mergedFavorites,
                onPlayAll = onPlayAll,
                onPlaySong = onPlaySong,
                onToggleFavorite = onToggleFavorite,
                onToggleQueue = onToggleQueue,
                queueSongIds = queueSongIds,
                onAddToPlaylist = { pickerSong = it },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            Spacer(modifier = Modifier.width(24.dp))
            PlaylistsPane(
                playlists = localPlaylists,
                expandedPlaylistId = expandedPlaylistId,
                queueSongIds = queueSongIds,
                onToggleExpand = { id ->
                    expandedPlaylistId = if (expandedPlaylistId == id) null else id
                },
                onPlayPlaylist = onPlayPlaylist,
                onRename = { renameTarget = it },
                onDelete = { onDeletePlaylist(it) },
                onPlaySong = onPlaySong,
                onToggleQueue = onToggleQueue,
                onRemoveSong = { playlistId, song -> onRemoveSongFromPlaylist(playlistId, song.id) },
                onAddSongToPlaylist = { pickerSong = it },
                onCreateClick = { showCreateDialog = true },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }

    // ===== 新建歌单输入弹窗 =====
    if (showCreateDialog) {
        TextInputDialog(
            title = stringResource(R.string.mine_create_playlist),
            hint = stringResource(R.string.mine_playlist_name_hint),
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

    // ===== 重命名歌单输入弹窗 =====
    renameTarget?.let { playlist ->
        TextInputDialog(
            title = stringResource(R.string.mine_rename_playlist_title),
            hint = stringResource(R.string.mine_playlist_name_hint),
            initialValue = playlist.name,
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    onRenamePlaylist(playlist.id, name)
                }
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    // ===== 加入歌单选择弹窗 =====
    pickerSong?.let { song ->
        PlaylistPickerDialog(
            playlists = localPlaylists,
            onPick = { playlist ->
                onAddSongToPlaylist(playlist.id, song)
                pickerSong = null
            },
            onCreate = { name ->
                if (name.isNotBlank()) {
                    onCreatePlaylist(name)
                }
            },
            onDismiss = { pickerSong = null }
        )
    }
    }   // 外层 Column（手机上下 / TV 左右）
}

/**
 * 收藏 Pane：标题行（含全部播放）+ 收藏歌曲列表（单列 SongRow）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FavoritesPane(
    songs: List<Song>,
    queueSongIds: Set<String>,
    onPlayAll: (List<Song>) -> Unit,
    onPlaySong: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onToggleQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.mine_favorites),
                color = NasMusicColors.TextPrimary,
                fontSize = 33.sp,
                modifier = Modifier.padding(end = 24.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            if (songs.isNotEmpty()) {
                ButtonChip(
                    text = stringResource(R.string.common_play_all),
                    onClick = { onPlayAll(songs) }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (songs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.mine_favorites_empty),
                    color = NasMusicColors.TextSecondary,
                    fontSize = 21.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(songs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        onClick = { onPlaySong(song) },
                        isFavorited = true,
                        onToggleFavorite = { onToggleFavorite(song) },
                        isInQueue = song.id in queueSongIds,
                        onToggleQueue = { onToggleQueue(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) }
                    )
                }
            }
        }
    }
}

/**
 * 本地歌单 Pane：标题行（含新建歌单）+ 歌单卡片列表
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistsPane(
    playlists: List<LocalPlaylist>,
    expandedPlaylistId: String?,
    queueSongIds: Set<String>,
    onToggleExpand: (String) -> Unit,
    onPlayPlaylist: (LocalPlaylist) -> Unit,
    onRename: (LocalPlaylist) -> Unit,
    onDelete: (String) -> Unit,
    onPlaySong: (Song) -> Unit,
    onToggleQueue: (Song) -> Unit,
    onRemoveSong: (String, Song) -> Unit,
    onAddSongToPlaylist: (Song) -> Unit,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.mine_playlists),
                color = NasMusicColors.TextPrimary,
                fontSize = 33.sp,
                modifier = Modifier.padding(end = 24.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            FocusableSurface(
                onClick = onCreateClick,
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1.08f,
                animationDurationMs = 250,
                containerColor = NasMusicColors.Primary.copy(alpha = 0.8f),
                contentColor = NasMusicColors.TextPrimary,
                focusedContainerColor = NasMusicColors.Primary,
                focusedContentColor = NasMusicColors.TextPrimary,
                pressedScale = 0.96f
            ) {
                Text(
                    text = "+ " + stringResource(R.string.mine_create_playlist),
                    fontSize = 19.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (playlists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.mine_playlists_empty),
                    color = NasMusicColors.TextSecondary,
                    fontSize = 21.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                playlists.forEach { playlist ->
                    item(key = "playlist_${playlist.id}") {
                        PlaylistCard(
                            playlist = playlist,
                            expanded = expandedPlaylistId == playlist.id,
                            onToggleExpand = { onToggleExpand(playlist.id) },
                            onPlay = { onPlayPlaylist(playlist) },
                            onRename = { onRename(playlist) },
                            onDelete = { onDelete(playlist.id) }
                        )
                    }
                    // 展开的歌单：歌曲作为独立 item 渲染（避免塞进单个 item 导致超高无法滚动）
                    if (expandedPlaylistId == playlist.id) {
                        if (playlist.songs.isEmpty()) {
                            item(key = "playlist_empty_${playlist.id}") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.mine_playlists_empty),
                                        color = NasMusicColors.TextSecondary,
                                        fontSize = 18.sp
                                    )
                                }
                            }
                        } else {
                            items(playlist.songs, key = { "playlist_song_${playlist.id}_${it.id}" }) { song ->
                                PlaylistSongRow(
                                    song = song,
                                    isInQueue = song.id in queueSongIds,
                                    onClick = { onPlaySong(song) },
                                    onToggleQueue = { onToggleQueue(song) },
                                    onRemove = { onRemoveSong(playlist.id, song) },
                                    onAddToPlaylist = { onAddSongToPlaylist(song) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个本地歌单卡片
 *
 * 布局与 SongRow 一致：外层 Box 用 focusGroup()，左侧可聚焦区域（点击展开/收起），
 * 右侧为独立可聚焦操作按钮（播放 / 重命名 / 删除）。
 * 展开后的歌曲列表由外层 LazyColumn 作为独立 item 渲染（见 [PlaylistsPane]）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistCard(
    playlist: LocalPlaylist,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var isRowFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .scale(animScale.value)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    color = if (isRowFocused) NasMusicColors.Primary.copy(alpha = 0.2f) else NasMusicColors.Surface.copy(alpha = 0.5f)
                )
                .border(
                    width = if (isRowFocused) 2.dp else 0.dp,
                    color = if (isRowFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .onFocusChanged { state ->
                    isRowFocused = state.hasFocus
                    scope.launch {
                        animScale.animateTo(
                            if (isRowFocused) 1.02f else 1f,
                            tween(200)
                        )
                    }
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(100.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：点击展开/收起
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onToggleExpand() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (expanded) "▾" else "♪",
                        color = NasMusicColors.Primary,
                        fontSize = 25.sp,
                        modifier = Modifier.width(36.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            color = NasMusicColors.TextPrimary,
                            fontSize = 23.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.mine_song_count, playlist.songs.size),
                            color = NasMusicColors.TextSecondary,
                            fontSize = 20.sp
                        )
                    }
                }
                // 右侧操作按钮
                Spacer(modifier = Modifier.width(10.dp))
                PlaylistActionButton(
                    text = stringResource(R.string.mine_play_playlist),
                    color = NasMusicColors.Primary,
                    onClick = onPlay
                )
                Spacer(modifier = Modifier.width(10.dp))
                PlaylistActionButton(
                    text = stringResource(R.string.mine_rename_playlist),
                    color = NasMusicColors.TextSecondary,
                    onClick = onRename
                )
                Spacer(modifier = Modifier.width(10.dp))
                PlaylistActionButton(
                    text = stringResource(R.string.mine_remove_song),
                    color = NasMusicColors.Warning,
                    onClick = onDelete
                )
            }
        }
        // 展开提示（歌曲列表由外层 LazyColumn 作为独立 item 渲染）
        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * 歌单内歌曲行 — 仿 SongRow 结构（focusGroup），提供播放 / 队列 / 移除 / 加入歌单。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistSongRow(
    song: Song,
    isInQueue: Boolean,
    onClick: () -> Unit,
    onToggleQueue: () -> Unit,
    onRemove: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    var isRowFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusGroup()
            .scale(animScale.value)
            .clip(RoundedCornerShape(6.dp))
            .background(
                color = if (isRowFocused) NasMusicColors.Primary.copy(alpha = 0.15f) else Color.Transparent
            )
            .onFocusChanged { state ->
                isRowFocused = state.hasFocus
                scope.launch {
                    animScale.animateTo(
                        if (isRowFocused) 1.02f else 1f,
                        tween(200)
                    )
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(116.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).clickable { onClick() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (song.coverUrl != null) {
                    AsyncImage(
                        model = song.coverUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .padding(end = 14.dp)
                    )
                }
                Text(text = "▶", color = NasMusicColors.Primary, fontSize = 20.sp, modifier = Modifier.width(32.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = song.title, color = NasMusicColors.TextPrimary, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = song.artist.ifBlank { "-" }, color = NasMusicColors.TextSecondary, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = TimeUtils.formatDuration(song.durationMs), color = NasMusicColors.TextSecondary, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            QueueToggleButton(isInQueue = isInQueue, onClick = onToggleQueue)
            Spacer(modifier = Modifier.width(8.dp))
            AddToPlaylistButton(onClick = onAddToPlaylist)
            Spacer(modifier = Modifier.width(8.dp))
            RemoveSongButton(onClick = onRemove)
        }
    }
}

/**
 * 歌单操作小按钮（播放 / 重命名 / 删除）— Box + focusable + clickable，
 * 与 QueueToggleButton 相同的焦点处理方式。
 */
@Composable
private fun PlaylistActionButton(
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .background(
                color = if (isFocused) color.copy(alpha = 0.2f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .scale(animScale.value)
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch {
                    animScale.animateTo(
                        if (isFocused) 1.08f else 1f,
                        tween(150)
                    )
                }
            }
            .focusable()
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 21.sp,
            color = if (isFocused) color else color.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/**
 * 移除歌曲按钮 — 视觉：✕，聚焦高亮 Warning 色。
 */
@Composable
private fun RemoveSongButton(
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .size(44.dp)
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .background(
                color = if (isFocused) NasMusicColors.Warning.copy(alpha = 0.2f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch {
                    animScale.animateTo(
                        if (isFocused) 1.1f else 1f,
                        tween(200)
                    )
                }
            }
            .focusable()
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
Text(
            text = "?",
            fontSize = 18.sp,
            color = if (isFocused) NasMusicColors.Warning else NasMusicColors.TextSecondary.copy(alpha = 0.5f)
        )
    }
}

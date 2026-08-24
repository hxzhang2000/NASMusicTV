package com.nasmusic.tv.ui.screens.netdisk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig
import com.nasmusic.tv.backend.network.baidu.BaiduPanApi
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.SearchField
import com.nasmusic.tv.ui.screens.PlaylistPickerDialog
import com.nasmusic.tv.ui.screens.SongRow
import com.nasmusic.tv.ui.screens.TextInputDialog
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.MainViewModel

/**
 * 网盘 Tab：目录浏览 + 关键词搜索 + 文件操作（播放/加队列/加歌单/查看 MV）
 *
 * 独立 Screen.Netdisk，不并入 Network Tab（浏览优先 vs 搜索优先，两种交互范式不可混用）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NetdiskScreen(
    viewModel: MainViewModel,
    onPlaySong: (Song) -> Unit,
    onPlayAllSongs: (List<Song>) -> Unit,
    onBack: () -> Unit
) {
    val connectionState by viewModel.baiduConnectionState.collectAsState()
    val currentDir by viewModel.netdiskCurrentDir.collectAsState()
    val dirFiles by viewModel.netdiskDirFiles.collectAsState()
    val isLoading by viewModel.netdiskIsLoading.collectAsState()
    val searchKeyword by viewModel.netdiskSearchKeyword.collectAsState()
    val searchResults by viewModel.netdiskSearchResults.collectAsState()
    val localPlaylists by viewModel.localPlaylists.collectAsState(initial = emptyList())
    val favoriteIds by viewModel.networkFavoriteIds.collectAsState(initial = emptySet())
    val queueSongIds by viewModel.queueSongIds.collectAsState(initial = emptySet())

    var showSearchDialog by remember { mutableStateOf(false) }
    var actionSong by remember { mutableStateOf<Song?>(null) }

    // 首次进入：刷新连接状态并加载当前目录（不依赖时序上的首次连接状态，避免空列表）
    LaunchedEffect(Unit) {
        viewModel.refreshBaiduConnectionState()
        viewModel.listBaiduDir(viewModel.netdiskCurrentDir.value)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp)
    ) {
        // 顶部：返回 + 标题 + 搜索框
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
        ) {
            FocusableSurface(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = NasMusicColors.TextPrimary)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text("网盘音乐", color = NasMusicColors.TextPrimary, fontSize = 28.sp)
            Spacer(modifier = Modifier.width(32.dp))

            // 搜索框（统一样式：点击弹出输入对话框，无独立搜索按钮）
            SearchField(
                query = searchKeyword,
                placeholder = "搜索网盘音乐...",
                onOpenSearch = { showSearchDialog = true },
                onClear = { viewModel.clearNetdiskSearch() },
                modifier = Modifier.width(420.dp)
            )
        }

        when {
            connectionState !is MainViewModel.BaiduConnectionState.LoggedIn -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "百度网盘未登录，请前往 设置 → 网盘 开启并登录",
                        color = NasMusicColors.TextSecondary,
                        fontSize = 20.sp
                    )
                }
            }
            // 搜索结果优先展示
            searchKeyword.isNotBlank() -> {
                if (searchResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("无搜索结果", color = NasMusicColors.TextSecondary, fontSize = 20.sp)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 操作栏：播放全部
                        item(key = "netdisk_search_action", span = { GridItemSpan(2) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FocusableSurface(
                                    onClick = { onPlayAllSongs(searchResults) },
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
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "搜索 “$searchKeyword”：${searchResults.size} 首",
                                    color = NasMusicColors.TextSecondary,
                                    fontSize = 18.sp
                                )
                            }
                        }
                        itemsIndexed(searchResults, key = { _, s -> "${s.networkId}_${s.title}" }) { index, song ->
                            SongRow(
                                song = song,
                                index = index,
                                onClick = { onPlaySong(song) },
                                isFavorited = song.id in favoriteIds,
                                onToggleFavorite = { viewModel.toggleNetworkFavorite(song) },
                                isInQueue = song.id in queueSongIds,
                                onToggleQueue = { viewModel.toggleQueueSong(song) },
                                onAddToPlaylist = { actionSong = song }
                            )
                        }
                    }
                }
            }
            else -> {
                // 目录浏览
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Text("目录：", color = NasMusicColors.TextSecondary, fontSize = 18.sp)
                    Text(currentDir, color = NasMusicColors.Primary, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    // 全部播放（含子目录）
                    FocusableSurface(
                        onClick = { viewModel.playAllNetdiskDir(currentDir, onPlayAllSongs) },
                        modifier = Modifier.padding(end = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 150,
                        containerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                        focusedContainerColor = NasMusicColors.Primary,
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.TextPrimary
                    ) {
                        Text("全部播放 ▶", color = NasMusicColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    if (currentDir != "/" && currentDir.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        FocusableSurface(
                            onClick = { viewModel.navigateBaiduDirUp() },
                            modifier = Modifier.padding(end = 8.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("上级", color = NasMusicColors.TextPrimary, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载中...", color = NasMusicColors.TextSecondary, fontSize = 18.sp)
                    }
                } else if (dirFiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("此目录下没有文件", color = NasMusicColors.TextSecondary, fontSize = 18.sp)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(
                            items = dirFiles,
                            key = { _, f -> "f_${f.fsId}" },
                            span = { _, f -> if (f.isDir) GridItemSpan(2) else GridItemSpan(1) }
                        ) { index, file ->
                            if (file.isDir) {
                                FileRow(
                                    file = file,
                                    onClick = { viewModel.enterBaiduDir(file.serverFilename) },
                                    onMore = {}
                                )
                            } else if (BaiduPanApi.isAudioFile(file.serverFilename, file.category)) {
                                val song = file.toSong()
                                SongRow(
                                    song = song,
                                    index = index,
                                    onClick = { onPlaySong(song) },
                                    isFavorited = song.id in favoriteIds,
                                    onToggleFavorite = { viewModel.toggleNetworkFavorite(song) },
                                    isInQueue = song.id in queueSongIds,
                                    onToggleQueue = { viewModel.toggleQueueSong(song) },
                                    onAddToPlaylist = { actionSong = song }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 网盘搜索输入对话框
    if (showSearchDialog) {
        TextInputDialog(
            title = "搜索网盘音乐",
            hint = "输入歌曲名或歌手名搜索网盘音乐",
            initialValue = searchKeyword,
            onConfirm = { input ->
                val kw = input.trim()
                if (kw.isNotBlank()) {
                    viewModel.searchBaidu(kw)
                    showSearchDialog = false
                }
            },
            onDismiss = { showSearchDialog = false }
        )
    }

    // 加入歌单选择弹窗
    actionSong?.let { song ->
        PlaylistPickerDialog(
            playlists = localPlaylists,
            onPick = { playlist ->
                viewModel.addSongToPlaylist(playlist.id, song)
                actionSong = null
            },
            onCreate = { name -> if (name.isNotBlank()) viewModel.createLocalPlaylist(name) },
            onDismiss = { actionSong = null }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FileRow(file: BaiduFile, onClick: () -> Unit, onMore: () -> Unit) {
    val isAudio = !file.isDir && BaiduPanApi.isAudioFile(file.serverFilename, file.category)
    FocusableSurface(
        onClick = onClick,
        onLongClick = onMore,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val icon = if (file.isDir) Icons.Default.Folder else Icons.Default.MusicNote
            val tint = if (file.isDir) NasMusicColors.Primary else
                if (isAudio) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                file.serverFilename,
                color = if (file.isDir || isAudio) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary,
                fontSize = 19.sp,
                modifier = Modifier.weight(1f)
            )
            if (isAudio) {
                Text(formatSize(file.size), color = NasMusicColors.TextSecondary, fontSize = 15.sp)
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
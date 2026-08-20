package com.nasmusic.tv.ui.screens.netdisk

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig
import com.nasmusic.tv.backend.network.baidu.BaiduPanApi
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.screens.PlaylistPickerDialog
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
    onBack: () -> Unit
) {
    val connectionState by viewModel.baiduConnectionState.collectAsState()
    val currentDir by viewModel.netdiskCurrentDir.collectAsState()
    val dirFiles by viewModel.netdiskDirFiles.collectAsState()
    val isLoading by viewModel.netdiskIsLoading.collectAsState()
    val searchKeyword by viewModel.netdiskSearchKeyword.collectAsState()
    val searchResults by viewModel.netdiskSearchResults.collectAsState()
    val localPlaylists by viewModel.localPlaylists.collectAsState(initial = emptyList())

    var searchInput by remember { mutableStateOf("") }
    var actionSong by remember { mutableStateOf<Song?>(null) }

    // 首次进入：刷新连接状态并加载根目录
    LaunchedEffect(Unit) {
        viewModel.refreshBaiduConnectionState()
        if (connectionState is MainViewModel.BaiduConnectionState.LoggedIn) {
            viewModel.listBaiduDir(currentDir)
        }
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

            // 搜索框
            FocusableSurface(
                onClick = {},
                modifier = Modifier.width(420.dp).height(48.dp),
                shape = RoundedCornerShape(24.dp),
                containerColor = NasMusicColors.Surface
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = NasMusicColors.TextSecondary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = searchInput,
                            onValueChange = {
                                searchInput = it
                                if (it.isNotBlank()) {
                                    viewModel.searchBaidu(it)
                                } else {
                                    viewModel.clearNetdiskSearch()
                                }
                            },
                            textStyle = TextStyle(color = NasMusicColors.TextPrimary, fontSize = 18.sp),
                            singleLine = true,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(NasMusicColors.Primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (searchInput.isEmpty()) {
                            Text("搜索网盘音乐...", color = NasMusicColors.TextSecondary, fontSize = 18.sp)
                        }
                    }
                }
            }
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
                Text("搜索 “$searchKeyword”：${searchResults.size} 首", color = NasMusicColors.TextSecondary, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(searchResults) { song ->
                        SongRow(song = song, onClick = { onPlaySong(song) }, onMore = { actionSong = song })
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
                    if (currentDir != "/" && currentDir.isNotBlank()) {
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
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(dirFiles) { file ->
                            FileRow(
                                file = file,
                                onClick = {
                                    if (file.isDir) {
                                        viewModel.enterBaiduDir(file.serverFilename)
                                    } else if (BaiduPanApi.isAudioFile(file.serverFilename, file.category)) {
                                        onPlaySong(file.toSong())
                                    }
                                },
                                onMore = {
                                    if (!file.isDir && BaiduPanApi.isAudioFile(file.serverFilename, file.category)) {
                                        actionSong = file.toSong()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongRow(song: Song, onClick: () -> Unit, onMore: () -> Unit) {
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
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = NasMusicColors.Primary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(song.title, color = NasMusicColors.TextPrimary, fontSize = 19.sp)
                if (song.artist.isNotBlank()) {
                    Text(song.artist, color = NasMusicColors.TextSecondary, fontSize = 15.sp)
                }
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

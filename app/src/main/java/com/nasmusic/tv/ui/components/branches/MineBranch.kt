package com.nasmusic.tv.ui.components.branches

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nasmusic.tv.data.model.Screen
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.data.model.*
import com.nasmusic.tv.ui.screens.*
import com.nasmusic.tv.ui.screens.library.*
import com.nasmusic.tv.ui.screens.settings.*
import com.nasmusic.tv.ui.screens.netdisk.*
import com.nasmusic.tv.ui.screens.stats.*
import com.nasmusic.tv.ui.viewmodel.*

/**
 * Mine 分支提取自 AppRoot（Method too large 根治：分支下沉 branches/）。
 * 分支体逐行搬迁，外层共享状态经参数注入。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun MineBranch(
    viewModel: MainViewModel,
    songDownloadStates: Map<String, com.nasmusic.tv.backend.download.model.DownloadState>
) {
                    val favoriteSongsState by viewModel.favoriteSongs.collectAsState(initial = UiState.Success(emptyList()))
                    val networkFavoriteSongs by viewModel.networkFavoriteSongs.collectAsState(initial = emptyList())
                    val recentSongsState by viewModel.recentSongs.collectAsState(initial = UiState.Success(emptyList()))
                    val recentSongsList = recentSongsState.dataOrNull() ?: emptyList()
                    val localPlaylists by viewModel.playlistVM.localPlaylists.collectAsState(initial = emptyList())
                    // 进入"我的"页时刷新最近播放（首次进入/从播放页返回时更新）
                    LaunchedEffect(Unit) {
                        viewModel.loadRecentSongs()
                    }
                    val queueSongIds by viewModel.queueSongIds.collectAsState(initial = emptySet())
                    // 歌单导入（阶段5）：URL 失效标记 / 补全进度（「我的」页徽标与按钮数据源）
                    val songReachability by viewModel.playlistImportVM.songReachability.collectAsState(initial = emptyMap())
                    val enrichProgress by viewModel.playlistImportVM.enrichProgress.collectAsState(initial = null)
                    MineScreen(
                        favoriteSongsState = favoriteSongsState,
                        networkFavoriteSongs = networkFavoriteSongs,
                        recentSongs = recentSongsList,
                        localPlaylists = localPlaylists,
                        queueSongIds = queueSongIds,
                        onPlaySong = { song ->
                            // 网络歌曲先解析播放链接，本地歌曲直接播放
                            if (song.isNetworkSong) {
                                viewModel.playNetworkSong(song)
                            } else {
                                viewModel.playQueue(listOf(song))
                            }
                            viewModel.navVM.navigateTo(Screen.NowPlaying)
                        },
                        onPlayAll = { songs ->
                            if (songs.isNotEmpty()) {
                                viewModel.playQueue(songs)
                                viewModel.navVM.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onToggleFavorite = { song -> viewModel.toggleNetworkFavorite(song) },
                        onToggleQueue = { song -> viewModel.playerVM.toggleQueueSong(song) },
                        onCreatePlaylist = { name -> viewModel.playlistVM.createLocalPlaylist(name) },
                        onRenamePlaylist = { id, newName -> viewModel.playlistVM.renameLocalPlaylist(id, newName) },
                        onDeletePlaylist = { id ->
                            viewModel.playlistVM.deleteLocalPlaylist(id)
                            // 歌单删除联动清理导入历史（2026-09-18 决策：历史行无删除入口）
                            viewModel.playlistImportVM.consumeHistoryIfDeleted(id)
                        },
                        onPlayPlaylist = { playlist -> viewModel.playLocalPlaylist(playlist) },
                        onRemoveSongFromPlaylist = { playlistId, songId -> viewModel.playlistVM.removeSongFromPlaylist(playlistId, songId) },
                        onAddSongToPlaylist = { playlistId, song -> viewModel.playlistVM.addSongToPlaylist(playlistId, song) },
                        // 功能入口（手机端底部导航未覆盖：队列 / 网盘 / 设置）
                        onOpenQueue = { viewModel.navVM.navigateTo(Screen.Queue) },
                        onOpenNetdisk = { viewModel.navVM.navigateTo(Screen.Netdisk) },
                        onOpenSettings = { viewModel.navVM.navigateTo(Screen.Settings) },
                        // 歌曲下载状态
                        downloadStates = songDownloadStates,
                        onDownloadSong = { song -> viewModel.downloadVM.downloadSong(song) },
                        onDeleteDownloadSong = { song -> viewModel.downloadVM.deleteDownload(song) },
                        // 歌单导入（阶段5）
                        songReachability = songReachability,
                        enrichProgress = enrichProgress,
                        onEnrichPlaylist = { playlistId -> viewModel.playlistImportVM.enrichPlaylist(playlistId) }
                    )
}

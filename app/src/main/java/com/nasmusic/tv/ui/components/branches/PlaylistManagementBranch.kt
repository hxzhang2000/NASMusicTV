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
 * PlaylistManagement 分支提取自 AppRoot（Method too large 根治：分支下沉 branches/）。
 * 分支体逐行搬迁，外层共享状态经参数注入。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlaylistManagementBranch(
    viewModel: MainViewModel,
) {
                    val playlistsState by viewModel.playlists.collectAsState(initial = UiState.Success(emptyList()))
                    val selectedPlaylistSongsState by viewModel.selectedPlaylistSongs.collectAsState(initial = UiState.Success(emptyList()))
                    PlaylistManagementScreen(
                        playlists = playlistsState.dataOrNull() ?: emptyList(),
                        selectedPlaylistSongs = selectedPlaylistSongsState.dataOrNull() ?: emptyList(),
                        isLoading = false,
                        onSelectPlaylist = { viewModel.selectPlaylist(it) },
                        onCreatePlaylist = { name -> viewModel.createPlaylist(name) },
                        onDeletePlaylist = { viewModel.deletePlaylist(it) },
                        onPlayPlaylist = { playlist ->
                            viewModel.playPlaylist(playlist)
                            viewModel.navVM.navigateTo(Screen.NowPlaying)
                        },
                        onRemoveSong = { songId -> viewModel.removeFromPlaylist(songId) },
                        onBack = { viewModel.navVM.navigateTo(Screen.Library) }
                    )
}

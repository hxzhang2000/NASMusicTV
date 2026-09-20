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
 * WeatherRadio 分支提取自 AppRoot（Method too large 根治：分支下沉 branches/）。
 * 分支体逐行搬迁，外层共享状态经参数注入。
 *
 * v2.36.2：歌曲行的内嵌按钮补齐为「⬇ ♡ ☰ +」—— 与曲库 / 专辑详情页一致。
 * 依赖的三处外层状态（收藏集合、队列集合、加入歌单入口）与 [LibraryBranch] 同源。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun WeatherRadioBranch(
    viewModel: MainViewModel,
    songDownloadStates: Map<String, com.nasmusic.tv.backend.download.model.DownloadState>,
    onPickSongForPlaylist: (Song) -> Unit
) {
                    val weatherRadioQueue by viewModel.weatherRadioVM.weatherRadioQueue.collectAsState(initial = null)
                    val weatherData by viewModel.weatherRadioVM.weatherData.collectAsState(initial = null)
                    val currentWeatherMood by viewModel.weatherRadioVM.currentWeatherMood.collectAsState()
                    val weatherLoading by viewModel.weatherRadioVM.weatherLoading.collectAsState(initial = false)
                    // 与 LibraryBranch 同源：favoriteIds 已合并 NAS 收藏与网络收藏
                    val favoriteIds by viewModel.favoriteIds.collectAsState(initial = emptySet())

                    com.nasmusic.tv.ui.screens.WeatherRadioScreen(
                        weatherRadioQueue = weatherRadioQueue,
                        weatherData = weatherData,
                        currentMood = currentWeatherMood,
                        isLoading = weatherLoading,
                        onPlaySong = { _, index ->
                            val songs = weatherRadioQueue?.songs ?: emptyList()
                            if (songs.isNotEmpty()) {
                                viewModel.playQueue(songs, index)
                                viewModel.navVM.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onPlayAll = {
                            viewModel.playWeatherRadioAll()
                        },
                        onSwitchMood = { mood ->
                            viewModel.weatherRadioVM.switchWeatherMood(mood)
                        },
                        onBack = { viewModel.navVM.navigateTo(Screen.Home) },
                        downloadStates = songDownloadStates,
                        onDownloadSong = { song -> viewModel.downloadVM.downloadSong(song) },
                        favoriteIds = favoriteIds,
                        queueSongIds = viewModel.queueSongIds.collectAsState(initial = emptySet()).value,
                        onToggleFavorite = { song -> viewModel.toggleNetworkFavorite(song) },
                        onToggleQueue = { song -> viewModel.playerVM.toggleQueueSong(song) },
                        onAddToPlaylist = { song -> onPickSongForPlaylist(song) },
                        onDeleteDownloadSong = { song -> viewModel.downloadVM.deleteDownload(song) }
                    )
}

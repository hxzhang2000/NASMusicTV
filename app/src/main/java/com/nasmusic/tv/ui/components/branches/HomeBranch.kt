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
 * Home 分支提取自 AppRoot（Method too large 根治：分支下沉 branches/）。
 * 分支体逐行搬迁，外层共享状态经参数注入。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HomeBranch(
    viewModel: MainViewModel,
    isConnected: Boolean,
    isLibraryLoading: Boolean,
    serverDisplayName: String,
    currentSong: Song?,
    coverCandidates: List<String>
) {
                    val homeDashboardData by viewModel.homeDashboardData.collectAsState(initial = HomeDashboardData())
                    val weatherData by viewModel.weatherRadioVM.weatherData.collectAsState(initial = null)
                    val weatherLoading by viewModel.weatherRadioVM.weatherLoading.collectAsState(initial = false)
                    val weatherError by viewModel.weatherRadioVM.weatherError.collectAsState(initial = null)
                    val recentSongsState by viewModel.recentSongs.collectAsState(initial = UiState.Success(emptyList()))
                    val recentSongsList = recentSongsState.dataOrNull() ?: emptyList()
                    val randomSongs by viewModel.randomSongs.collectAsState(initial = emptyList())

                    // 进入首页时刷新数据
                    LaunchedEffect(Unit) {
                        viewModel.loadHomeDashboard()
                        viewModel.loadRecentSongs()
                        viewModel.weatherRadioVM.fetchWeather()
                        viewModel.loadRandomSongs()
                    }

                    HomeScreen(
                        isConnected = isConnected,
                        isLibraryLoading = isLibraryLoading,
                        serverDisplayName = serverDisplayName,
                        dashboardData = homeDashboardData,
                        weatherData = weatherData,
                        weatherLoading = weatherLoading,
                        weatherError = weatherError,
                        recentSongs = recentSongsList,
                        currentSong = currentSong,
                        coverCandidates = coverCandidates,
                        onPlaySong = { song ->
                            if (song.isNetworkSong) viewModel.playNetworkSong(song)
                            else viewModel.playQueue(listOf(song))
                            viewModel.navVM.navigateTo(Screen.NowPlaying)
                        },
                        onPlayAlbum = { album ->
                            viewModel.playAlbumMultiSource(album)
                        },
                        onOpenAlbumDetail = { album -> viewModel.openAlbumDetail(album) },
                        onNavigateToLibrary = { viewModel.navVM.navigateTo(Screen.Library) },
                        onNavigateToSearch = {
                            viewModel.selectLibraryTab(LibraryTab.SEARCH)
                            viewModel.navVM.navigateTo(Screen.Library)
                        },
                        onNavigateToQueue = { viewModel.navVM.navigateTo(Screen.Queue) },
                        onNavigateToNowPlaying = { viewModel.navVM.navigateTo(Screen.NowPlaying) },
                        onNavigateToWeatherRadio = { viewModel.navVM.navigateTo(Screen.WeatherRadio) },
                        onPlayAllRecent = {
                            if (recentSongsList.isNotEmpty()) {
                                viewModel.playQueue(recentSongsList)
                                viewModel.navVM.navigateTo(Screen.NowPlaying)
                            }
                        },
                        randomSongs = randomSongs,
                        onPlayRandomSongs = { songs, index ->
                            viewModel.playRandomSongs(songs, index)
                        }
                    )
}

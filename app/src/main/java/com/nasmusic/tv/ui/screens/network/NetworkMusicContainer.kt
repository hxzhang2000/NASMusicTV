package com.nasmusic.tv.ui.screens.network

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.MusicSource
import com.nasmusic.tv.data.model.NetworkSubTab
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.SearchHistoryItem
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.data.model.WeatherData
import com.nasmusic.tv.data.model.WeatherForecast
import com.nasmusic.tv.data.model.WeatherMood
import com.nasmusic.tv.data.model.WeatherRadioQueue
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.screens.network.components.SourceSelector
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 网络音乐容器 — 子 Tab 布局 + 来源选择器
 *
 * 页面结构：
 * ┌──────────────────────────────────────────┐
 * │  网络音乐                                  │
 * │  [发现] [天气] [搜索]    [来源选择器]  │
 * ├──────────────────────────────────────────┤
 * │                                          │
 * │           子 Tab 内容区域                    │
 * │                                          │
 * └──────────────────────────────────────────┘
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NetworkMusicContainer(
    currentSubTab: NetworkSubTab,
    currentMusicSource: MusicSource,
    // 搜索子 Tab 参数
    searchKeyword: String,
    searchResults: List<Song>,
    isSearching: Boolean,
    historyItems: List<SearchHistoryItem> = emptyList(),
    // 发现子 Tab 参数
    networkPlaylists: List<Pair<Playlist, List<Song>>>,
    networkFavoriteSongs: List<Song>,
    // 通用参数
    networkFavoriteIds: Set<String>,
    queueSongIds: Set<String>,
    // 继续听参数
    recentNetworkSongs: List<Song> = emptyList(),
    currentNetworkSong: Song? = null,
    // 回调
    onSelectSubTab: (NetworkSubTab) -> Unit,
    onSelectMusicSource: (MusicSource) -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onPlayNetworkSong: (Song) -> Unit,
    onToggleNetworkFavorite: (Song) -> Unit,
    onToggleQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    onPlayAllSongs: (List<Song>) -> Unit,
    onPlayAllSearch: () -> Unit = {},
    onShuffleSearch: () -> Unit = {},
    onAddAllToQueue: () -> Unit = {},
    onLoadPlaylistDetail: (Pair<Playlist, List<Song>>) -> Unit,
    onNavigateToPlaylistDetail: () -> Unit,
    // 天气子 Tab 参数
    weatherData: WeatherData? = null,
    weatherRadioQueue: WeatherRadioQueue? = null,
    currentWeatherMood: WeatherMood = WeatherMood.SUNNY,
    weatherLoading: Boolean = false,
    weatherError: String? = null,
    weatherForecast: List<WeatherForecast> = emptyList(),
    weatherIconCode: String? = null,
    onSwitchWeatherMood: (WeatherMood) -> Unit = {},
    onRefreshWeather: () -> Unit = {},
    onPlayWeatherAll: (() -> Unit)? = null,
    // 导航
    // 浏览子 Tab 参数
    browseSelections: List<Int> = emptyList(),
    browseResults: UiState<List<Song>> = UiState.Success(emptyList()),
    isBrowseSearching: Boolean = false,
    onSelectBrowseOption: (Int, Int) -> Unit = { _, _ -> },
    onRefreshBrowse: () -> Unit = {},
    onPlayAllBrowse: () -> Unit = {},
    onNavigateToScreen: (String) -> Unit = {},
    // 电台子 Tab 参数
    radioStations: UiState<List<com.nasmusic.tv.data.model.RadioStation>> = UiState.Success(emptyList()),
    radioActiveTag: String? = null,
    radioActiveQuery: String = "",
    onRadioLoadDefault: () -> Unit = {},
    onRadioLoadTag: (String) -> Unit = {},
    onRadioSearch: (String) -> Unit = {},
    onRadioPlayStation: (com.nasmusic.tv.data.model.RadioStation) -> Unit = {},
    // Jamendo 子 Tab 参数
    jamendoState: UiState<List<Song>> = UiState.Success(emptyList()),
    jamendoActiveTag: String = "",
    jamendoConfigured: Boolean = false,
    onJamendoLoadHot: () -> Unit = {},
    onJamendoLoadTag: (String) -> Unit = {},
    onJamendoSearch: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // 子 Tab 回顶处理
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            false // 让各子 Tab 自己的 back handler 处理
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

    Column(modifier = modifier.fillMaxWidth().padding(end = 8.dp)) {
        // 标题
        Text(
            text = stringResource(R.string.nav_network),
            color = NasMusicColors.TextPrimary,
            fontSize = 23.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 子 Tab 栏 + 来源选择器（同一行）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 子 Tab 按钮行（可横向滑动——手机窄屏滑动浏览全部 tab）
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NetworkSubTab.entries.forEach { tab ->
                    val isSelected = tab == currentSubTab
                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected) NasMusicColors.Primary
                                      else NasMusicColors.Surface.copy(alpha = 0.7f),
                        label = "tabBg"
                    )
                    FocusableSurface(
                        onClick = { onSelectSubTab(tab) },
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.1f,
                        animationDurationMs = 150,
                        containerColor = bgColor,
                        focusedContainerColor = NasMusicColors.Primary,
                        contentColor = if (isSelected) NasMusicColors.Surface
                                       else NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.Surface
                    ) {
                        Text(
                            text = stringResource(tab.displayNameResId),
                            fontSize = 19.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // 来源选择器（tab 行占剩余空间，紧随其后靠右排列）
            SourceSelector(
                selectedSource = currentMusicSource,
                onSelectSource = onSelectMusicSource
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 子 Tab 内容
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            when (currentSubTab) {
                NetworkSubTab.DISCOVER -> {
                    DiscoverContent(
                        networkPlaylists = networkPlaylists,
                        networkFavoriteSongs = networkFavoriteSongs,
                        networkFavoriteIds = networkFavoriteIds,
                        queueSongIds = queueSongIds,
                        recentNetworkSongs = recentNetworkSongs,
                        currentNetworkSong = currentNetworkSong,
                        onPlayNetworkSong = onPlayNetworkSong,
                        onToggleNetworkFavorite = onToggleNetworkFavorite,
                        onToggleQueue = onToggleQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        onPlayAllSongs = onPlayAllSongs,
                        onLoadPlaylistDetail = onLoadPlaylistDetail,
                        onNavigateToPlaylistDetail = onNavigateToPlaylistDetail,
                        onNavigateToScreen = onNavigateToScreen
                    )
                }
                NetworkSubTab.WEATHER -> {
                    WeatherSubTab(
                        weatherData = weatherData,
                        weatherRadioQueue = weatherRadioQueue,
                        currentMood = currentWeatherMood,
                        isLoading = weatherLoading,
                        errorMessage = weatherError,
                        networkFavoriteIds = networkFavoriteIds,
                        queueSongIds = queueSongIds,
                        forecast = weatherForecast,
                        weatherIconCode = weatherIconCode,
                        onPlaySong = onPlayNetworkSong,
                        onPlayAll = { songs -> onPlayAllSongs(songs) },
                        onSwitchMood = onSwitchWeatherMood,
                        onRefresh = onRefreshWeather,
                        onToggleFavorite = onToggleNetworkFavorite,
                        onToggleQueue = onToggleQueue,
                        onAddToPlaylist = onAddToPlaylist
                    )
                }
                NetworkSubTab.SEARCH -> {
                    SearchSubTab(
                        searchKeyword = searchKeyword,
                        searchResults = searchResults,
                        isSearching = isSearching,
                        favoriteIds = networkFavoriteIds,
                        queueSongIds = queueSongIds,
                        historyItems = historyItems,
                        onSearch = onSearch,
                        onClearSearch = onClearSearch,
                        onPlaySong = { song ->
                            onPlayNetworkSong(song)
                        },
                        onToggleFavorite = onToggleNetworkFavorite,
                        onToggleQueue = onToggleQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        onPlayAll = onPlayAllSearch,
                        onShuffleSearch = onShuffleSearch,
                        onAddAllToQueue = onAddAllToQueue
                    )
                }
                NetworkSubTab.RADIO -> {
                    RadioSubTab(
                        state = radioStations,
                        activeTag = radioActiveTag,
                        activeQuery = radioActiveQuery,
                        onLoadDefault = onRadioLoadDefault,
                        onLoadTag = onRadioLoadTag,
                        onSearch = onRadioSearch,
                        onPlayStation = onRadioPlayStation
                    )
                }
                NetworkSubTab.JAMENDO -> {
                    JamendoSubTab(
                        state = jamendoState,
                        activeTag = jamendoActiveTag,
                        configured = jamendoConfigured,
                        networkFavoriteIds = networkFavoriteIds,
                        queueSongIds = queueSongIds,
                        onLoadHot = onJamendoLoadHot,
                        onLoadTag = onJamendoLoadTag,
                        onSearch = onJamendoSearch,
                        onPlaySong = onPlayNetworkSong,
                        onToggleFavorite = onToggleNetworkFavorite,
                        onToggleQueue = onToggleQueue
                    )
                }
                NetworkSubTab.BROWSE -> {
                    BrowseSubTab(
                        selections = browseSelections,
                        browseResults = browseResults,
                        isSearching = isBrowseSearching,
                        networkFavoriteIds = networkFavoriteIds,
                        queueSongIds = queueSongIds,
                        onSelectOption = onSelectBrowseOption,
                        onRefresh = onRefreshBrowse,
                        onPlayAll = onPlayAllBrowse,
                        onPlaySong = onPlayNetworkSong,
                        onToggleFavorite = onToggleNetworkFavorite,
                        onToggleQueue = onToggleQueue,
                        onAddToPlaylist = onAddToPlaylist
                    )
                }
            }
        }
    }
}

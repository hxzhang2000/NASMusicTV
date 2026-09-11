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
 * Library 分支提取自 AppRoot（Method too large 根治：分支下沉 branches/）。
 * 分支体逐行搬迁，外层共享状态经参数注入。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LibraryBranch(
    viewModel: MainViewModel,
    songsState: UiState<List<Song>>,
    isLoading: Boolean,
    isLibraryLoading: Boolean,
    isConnected: Boolean,
    songDownloadStates: Map<String, com.nasmusic.tv.backend.download.model.DownloadState>,
    onPickSongForPlaylist: (Song) -> Unit
) {
                    val albums by viewModel.albums.collectAsState(initial = UiState.Loading as UiState<List<Album>>)
                    val genres by viewModel.genres.collectAsState(initial = UiState.Success(emptyList()))
                    val favoriteIds by viewModel.favoriteIds.collectAsState(initial = emptySet())
                    val artistsState by viewModel.artists.collectAsState(initial = UiState.Success(emptyList()))
                    val mergedAlbumList by viewModel.mergedAlbums.collectAsState(initial = emptyList())
                    val mergedArtistsList by viewModel.mergedArtists.collectAsState(initial = emptyList())
                    val yearsState by viewModel.years.collectAsState(initial = UiState.Success(emptyList()))
                    val songsPaging by viewModel.songsPaging.collectAsState(initial = com.nasmusic.tv.data.model.SongsPagingState())
                    val localSongsList by viewModel.localSongs.collectAsState(initial = emptyList())
                    val searchResultsState by viewModel.searchVM.searchResults.collectAsState(initial = UiState.Success(emptyList()))
                    val albumList = albums.dataOrNull() ?: emptyList()
                    val songList = songsState.dataOrNull() ?: emptyList()
                    val genreList = genres.dataOrNull() ?: emptyList()
                    val artistsList = artistsState.dataOrNull() ?: emptyList()
                    val yearsList = yearsState.dataOrNull() ?: emptyList()
                    // 合并 NAS 分页歌曲 + 本地歌曲（含下载歌曲），供曲库歌曲页展示
                    val mergedSongsPaging = remember(songsPaging, localSongsList) {
                        if (localSongsList.isEmpty()) songsPaging
                        else songsPaging.copy(
                            songs = (songsPaging.songs + localSongsList).distinctBy { it.id },
                            totalCount = songsPaging.totalCount + localSongsList.size
                        )
                    }
                    // 合并 NAS 流派 + 本地歌曲流派（去重）
                    val mergedGenreList = remember(genreList, localSongsList) {
                        val localGenres = localSongsList.mapNotNull { it.genre?.takeIf { g -> g.isNotBlank() } }
                            .groupBy { it }.map { (name, songs) ->
                                com.nasmusic.tv.data.model.Genre(id = "local_$name", name = name, songCount = songs.size)
                            }
                        if (localGenres.isEmpty()) genreList
                        else (genreList + localGenres).distinctBy { it.name }
                    }
                    // 合并 NAS 年代 + 本地歌曲年代（去重）
                    val mergedYearsList = remember(yearsList, localSongsList) {
                        val localYears = localSongsList.mapNotNull { it.year?.takeIf { y -> y > 0 } }.distinct()
                        (yearsList + localYears).distinct().sortedDescending()
                    }
                    val searchResultsList = searchResultsState.dataOrNull() ?: emptyList()
                    val isSearching = searchResultsState is UiState.Loading
                    val libraryActiveTab by viewModel.libraryActiveTab.collectAsState()
                    val librarySearchKeyword by viewModel.librarySearchKeyword.collectAsState()
                    val enabledSearchSources by viewModel.searchVM.enabledSearchSources.collectAsState()
                    val searchHistory by viewModel.searchHistory.collectAsState(initial = emptyList())

                    // ── RADIO Tab state ──
                    val radioStations by viewModel.radioStations.collectAsState(initial = UiState.Success(emptyList()))
                    val radioActiveTag by viewModel.radioActiveTag.collectAsState(initial = null)
                    val radioActiveQuery by viewModel.radioActiveQuery.collectAsState(initial = "")
                    // ── Task 8: 滚动位置记忆 ──
                    val albumScrollIndex by viewModel.albumScrollIndex.collectAsState()
                    val albumScrollOffset by viewModel.albumScrollOffset.collectAsState()
                    val artistScrollIndex by viewModel.artistScrollIndex.collectAsState()
                    val artistScrollOffset by viewModel.artistScrollOffset.collectAsState()
                    // ── DISCOVER Tab state ──
                    val browseSelections by viewModel.browseSelections.collectAsState(initial = emptyList())
                    val browseResultsState by viewModel.browseResults.collectAsState(initial = UiState.Success(emptyList()))
                    val browseIsLoading by viewModel.isBrowseSearching.collectAsState(initial = false)
                    val browseResultsList = browseResultsState.dataOrNull() ?: emptyList()
                    // Build DiscoverTab-compatible dimensions from BrowseDimension enum
                    val discoverDimensions = remember {
                        com.nasmusic.tv.data.model.BrowseDimension.entries.map { dim ->
                            com.nasmusic.tv.ui.screens.library.BrowseDimension(
                                label = dim.displayName,
                                options = dim.options.map { it.label }
                            )
                        }
                    }
                    val discoverCurrentDimensionValues = remember(browseSelections) {
                        com.nasmusic.tv.data.model.BrowseDimension.entries.mapIndexed { dimIdx, dim ->
                            val selectedIdx = browseSelections.getOrElse(dimIdx) { 0 }
                            dim.displayName to dim.options.getOrElse(selectedIdx) { dim.options.first() }.label
                        }.toMap()
                    }
                    LibraryScreen(
                        albums = mergedAlbumList,
                        songs = songList,
                        isLoading = isLoading || isLibraryLoading,
                        isConnected = isConnected,
                        genres = mergedGenreList,
                        favoriteIds = favoriteIds,
                        artistSongsMap = viewModel.artistSongsMap.value,
                        artists = mergedArtistsList,
                        years = mergedYearsList,
                        songsPaging = mergedSongsPaging,
                        searchResults = searchResultsList,
                        isSearching = isSearching,
                        onPlayAlbum = { album ->
                            viewModel.playAlbumMultiSource(album)
                        },
                        onPlaySong = { song ->
                            // 网络歌曲需要先解析播放链接，本地歌曲直接播放
                            if (song.isNetworkSong) {
                                viewModel.playNetworkSong(song)
                            } else {
                                viewModel.playQueue(listOf(song))
                            }
                            viewModel.navVM.navigateTo(Screen.NowPlaying)
                        },
                        onPlaySongs = { songListParam ->
                            viewModel.playQueue(songListParam)
                            viewModel.navVM.navigateTo(Screen.NowPlaying)
                        },
                        onPlayAllSongs = { songs ->
                            if (songs.isNotEmpty()) {
                                viewModel.playQueue(songs)
                                viewModel.navVM.navigateTo(Screen.NowPlaying)
                            }
                        },
                        queueSongIds = viewModel.queueSongIds.collectAsState(initial = emptySet()).value,
                        onToggleQueue = { song -> viewModel.playerVM.toggleQueueSong(song) },
                        onToggleFavorite = { song -> viewModel.toggleNetworkFavorite(song) },
                        onAddToPlaylist = { song -> onPickSongForPlaylist(song) },
                        onOpenAlbumDetail = { album -> viewModel.openAlbumDetail(album) },
                        onOpenArtistDetail = { artist -> viewModel.openArtistDetail(artist) },
                        onSongsByGenre = { genre, callback -> viewModel.getSongsByGenre(genre, callback) },
                        onSongsByYear = { from, to, callback -> viewModel.getSongsByYearRange(from, to, callback) },
                        onLoadSongsFirstPage = { viewModel.loadSongsFirstPage() },
                        onLoadSongsNextPage = { viewModel.loadSongsNextPage() },
                        onLoadArtists = { viewModel.loadArtists() },
                        onLoadYears = { viewModel.loadYears() },
                        onSearch = { query -> viewModel.searchSongsOnServer(query) },
                        onClearSearch = { viewModel.searchVM.clearSearch() },
                        historyItems = searchHistory,
                        activeTab = libraryActiveTab,
                        onTabSelected = { tab -> viewModel.selectLibraryTab(tab) },
                        filterQuery = librarySearchKeyword,
                        onFilterQueryChange = { keyword -> viewModel.setLibrarySearchKeyword(keyword) },
                        enabledSearchSources = enabledSearchSources,
                        onToggleSearchSource = { source -> viewModel.searchVM.toggleSearchSource(source) },
                        onEnableAllSearchSources = { viewModel.searchVM.enableAllSearchSources() },
                        // ── SEARCH Tab ──
                        onSearchTabPlayAll = {
                            val allSongs = searchResultsList
                            if (allSongs.isNotEmpty()) {
                                viewModel.playQueue(allSongs)
                                viewModel.navVM.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onSearchTabAddAllToQueue = {
                            // 只加入队列，不播放（修复 M-8：改用只增不删的批量加入，
                            // 原 toggle 语义会把已在队列中的歌曲反向移除）
                            viewModel.playerVM.addSongsToQueue(searchResultsList)
                        },
                        // ── DISCOVER Tab ──
                        discoverDimensions = discoverDimensions,
                        discoverFilteredSongs = browseResultsList,
                        discoverIsLoading = browseIsLoading,
                        discoverCurrentDimensionValues = discoverCurrentDimensionValues,
                        onDiscoverDimensionChanged = { dimensionLabel, optionLabel ->
                            val dimIdx = com.nasmusic.tv.data.model.BrowseDimension.entries.indexOfFirst { it.displayName == dimensionLabel }
                            if (dimIdx >= 0) {
                                val dim = com.nasmusic.tv.data.model.BrowseDimension.entries[dimIdx]
                                val optIdx = dim.options.indexOfFirst { it.label == optionLabel }
                                if (optIdx >= 0) {
                                    viewModel.selectBrowseOption(dimIdx, optIdx)
                                }
                            }
                        },
                        onDiscoverPlayAll = {
                            if (browseResultsList.isNotEmpty()) {
                                viewModel.playQueue(browseResultsList)
                                viewModel.navVM.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onDiscoverAddAllToQueue = {
                            // 只加入队列，不播放（修复 M-8，同上）
                            viewModel.playerVM.addSongsToQueue(browseResultsList)
                        },
                        onDiscoverShuffle = {
                            viewModel.refreshBrowseSongs()
                        },
                        onDiscoverEnsureLoaded = {
                            viewModel.ensureBrowseLoaded()
                        },
                        // ── RADIO Tab ──
                        radioStations = radioStations,
                        radioActiveTag = radioActiveTag,
                        radioActiveQuery = radioActiveQuery,
                        onLoadRadioDefault = { viewModel.loadRadioDefault() },
                        onLoadRadioTag = { tag -> viewModel.loadRadioTag(tag) },
                        onSearchRadio = { keyword -> viewModel.searchRadio(keyword) },
                        onPlayRadioStation = { station -> viewModel.playRadioStation(station) },
                        // ── Task 8: 滚动位置记忆 ──
                        albumScrollIndex = albumScrollIndex,
                        albumScrollOffset = albumScrollOffset,
                        artistScrollIndex = artistScrollIndex,
                        artistScrollOffset = artistScrollOffset,
                        onAlbumScrollPositionChange = { index, offset -> viewModel.saveAlbumScrollPosition(index, offset) },
                        onArtistScrollPositionChange = { index, offset -> viewModel.saveArtistScrollPosition(index, offset) },
                        // ── 歌曲下载状态 ──
                        downloadStates = songDownloadStates,
                        onDownloadSong = { song -> viewModel.downloadVM.downloadSong(song) },
                        onDeleteDownloadSong = { song -> viewModel.downloadVM.deleteDownload(song) }
                    )
}

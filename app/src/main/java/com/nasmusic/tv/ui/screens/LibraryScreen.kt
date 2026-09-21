package com.nasmusic.tv.ui.screens

import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.components.AlbumSkeletonGrid
import com.nasmusic.tv.ui.components.ArtistSkeletonGrid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.download.model.DownloadState
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.SearchHistoryItem
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongsPagingState
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.SearchField
import com.nasmusic.tv.ui.components.songGridColumns
import com.nasmusic.tv.ui.screens.library.DiscoverTab
import com.nasmusic.tv.ui.screens.library.RadioTab
import com.nasmusic.tv.ui.screens.library.SearchTab
import com.nasmusic.tv.ui.screens.library.browse.AlbumsTab
import com.nasmusic.tv.ui.screens.library.browse.ArtistsTab
import com.nasmusic.tv.ui.screens.library.browse.ButtonChip
import com.nasmusic.tv.ui.screens.library.browse.EmptyHint
import com.nasmusic.tv.ui.screens.library.browse.GenresTab
import com.nasmusic.tv.ui.screens.library.browse.SongsTab
import com.nasmusic.tv.ui.screens.library.browse.YearsTab
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.UiMode
import com.nasmusic.tv.util.ArtistSplitter
import com.nasmusic.tv.util.PinyinUtils
import com.nasmusic.tv.backend.local.MusicMerger

enum class LibraryTab(val titleRes: Int) {
    SEARCH(R.string.library_search),
    DISCOVER(R.string.library_discover),
    ALBUMS(R.string.library_albums),
    ARTISTS(R.string.library_artists_alt),
    SONGS(R.string.library_songs),
    GENRES(R.string.library_genres),
    YEARS(R.string.library_years),
    RADIO(R.string.library_radio)
}

/**
 * 曲库子 TAB 横推动画时长（v2.36.2）：与竖屏播放页的模式切换保持一致的手感。
 */
private const val LIBRARY_TAB_SLIDE_ANIM_MS = 260

/**
 * 曲库子 TAB：在**内容区**左右滑切换 TAB（v2.36.1，用户明确要求「手机端横竖屏都要支持」）。
 *
 * v2.36.4 起：切换改为**真 pager**（跟手 + 相邻页同时平移，见内容区注释），
 * 本 modifier 只负责识别水平拖拽并把位移/松手事件转给外层。
 *
 * ⚠️ 挂在**内容区** `Box(weight(1f))` 上，**不要**挂到 TAB 行上 —— TAB 行自身是横滑条
 * （窄屏必须滑才能看全 8 个 TAB），两者手势会打架。
 *
 * ✅ **空数据也能滑**：内容区 `Box` 的尺寸由 `weight(1f)` 决定，与内部有没有列表无关；
 * 且 `pointerInput` 的命中测试看的是**布局边界**（不看有没有绘制出内容），
 * 故「暂无数据」空态照样接收滑动。⛔ 反过来讲，**别**把这个 modifier 挂到某个具体
 * TAB 的列表上 —— 空态时那个列表可能压根不存在，滑不动。
 *
 * ⚠️ 只识别**水平**拖拽：`detectHorizontalDragGestures` 需先越过水平 touch slop，
 * 且方向判定更偏水平，故列表**竖直**滚动不受影响；内容区里已有的横向滚动子节点
 * （如 Chip 横排、DISCOVER 的维度选择行）位于更深的节点、会**先**消费事件，
 * 因此在那类控件上滑动仍是它们自己响应 —— 这是有意为之。
 *
 * ⚠️ 回调必须用 `rememberUpdatedState` 包裹：`pointerInput` 的 key 是 `enabled`，
 * 只要开关不变协程就不重启，若直接捕获 lambda，会读到**首次组合**那一刻的
 * `activeTab` / `tabPageFraction`（陈旧值）→ 表现为「只能在前两个 TAB 之间来回」。
 * 这是 Compose 里 `pointerInput(Unit)` + 状态闭包的经典陷阱。
 *
 * v2.36.4 真 pager 回调：[onDragStart] 记录起始页索引并清零累积位移，[onDragDelta]
 * 实时上报位移（外层驱动 `tabPageFraction` 让当前页划走、相邻页跟进），
 * [onSettle] 在松手/取消时触发（外层按最近页吸附）。
 */
@Composable
private fun Modifier.libraryTabSwipe(
    enabled: Boolean,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onSettle: () -> Unit,
): Modifier {
    val dragStart by rememberUpdatedState(onDragStart)
    val dragDelta by rememberUpdatedState(onDragDelta)
    val settle by rememberUpdatedState(onSettle)
    return this.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        var accumulated = 0f
        detectHorizontalDragGestures(
            onDragStart = {
                accumulated = 0f
                dragStart()
            },
            onDragEnd = {
                settle()
                accumulated = 0f
            },
            onDragCancel = {
                settle()
                accumulated = 0f
            },
        ) { _, dragAmount ->
            accumulated += dragAmount
            dragDelta(dragAmount)
        }
    }
}

/**
 * 曲库主屏幕（R-3 拆分：ALBUMS/ARTISTS/SONGS/GENRES/YEARS 五个 NAS 浏览 Tab 已迁至
 * ui/screens/library/browse/ 子包；SEARCH/DISCOVER/RADIO 网络音乐 Tab 维持原 library/ 包不动；
 * 详情页复用现役 AlbumDetailScreen/ArtistDetailScreen，本文件仅保留 Tab 容器、
 * 搜索栏与数据过滤派生逻辑，参数签名不变——AppRoot 引用零改动）。
 */
@Composable
fun LibraryScreen(
    albums: List<Album>,
    songs: List<Song>,
    isLoading: Boolean,
    isConnected: Boolean = false,
    genres: List<Genre> = emptyList(),
    favoriteIds: Set<String> = emptySet(),
    artistSongsMap: Map<String, List<Song>> = emptyMap(),
    artists: List<Artist> = emptyList(),
    years: List<Int> = emptyList(),
    songsPaging: SongsPagingState = SongsPagingState(),
    searchResults: List<Song> = emptyList(),
    isSearching: Boolean = false,
    onPlayAlbum: (Album) -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlaySongs: (List<Song>) -> Unit,
    onPlayAllSongs: (List<Song>) -> Unit,
    // 队列切换
    queueSongIds: Set<String> = emptySet(),
    onToggleQueue: (Song) -> Unit = {},
    // 本地收藏切换（用于 SongsTab 等本地歌曲列表）
    onToggleFavorite: (Song) -> Unit = {},
    // 加入歌单（弹出歌单选择弹窗）
    onAddToPlaylist: (Song) -> Unit = {},
    onOpenAlbumDetail: ((Album) -> Unit)? = null,
    onOpenArtistDetail: ((String) -> Unit)? = null,
    onSongsByGenre: ((String, (List<Song>) -> Unit) -> Unit)? = null,
    onSongsByYear: ((Int, Int, (List<Song>) -> Unit) -> Unit)? = null,
    onLoadSongsFirstPage: () -> Unit = {},
    onLoadSongsNextPage: () -> Unit = {},
    onLoadArtists: () -> Unit = {},
    onLoadYears: () -> Unit = {},
    onSearch: (String) -> Unit = {},
    onClearSearch: () -> Unit = {},
    historyItems: List<SearchHistoryItem> = emptyList(),
    // 子 Tab 跨导航记忆（由 ViewModel 驱动）
    activeTab: LibraryTab = LibraryTab.ALBUMS,
    onTabSelected: (LibraryTab) -> Unit = {},
    // 搜索关键词（跨导航记忆，由 ViewModel 驱动）
    filterQuery: String = "",
    onFilterQueryChange: (String) -> Unit = {},
    // 搜索来源点亮状态（由 ViewModel 驱动）
    enabledSearchSources: Set<MusicSourceType> = emptySet(),
    onToggleSearchSource: (MusicSourceType) -> Unit = {},
    onEnableAllSearchSources: () -> Unit = {},
    // ── SEARCH Tab ──
    onSearchTabPlayAll: () -> Unit = {},
    onSearchTabAddAllToQueue: () -> Unit = {},
    // ── DISCOVER Tab ──
    discoverDimensions: List<com.nasmusic.tv.ui.screens.library.BrowseDimension> = emptyList(),
    discoverFilteredSongs: List<Song> = emptyList(),
    discoverIsLoading: Boolean = false,
    discoverCurrentDimensionValues: Map<String, String> = emptyMap(),
    onDiscoverDimensionChanged: (String, String) -> Unit = { _, _ -> },
    onDiscoverPlayAll: () -> Unit = {},
    onDiscoverAddAllToQueue: () -> Unit = {},
    onDiscoverShuffle: () -> Unit = {},
    onDiscoverEnsureLoaded: () -> Unit = {},
    // ── RADIO Tab ──
    radioStations: UiState<List<com.nasmusic.tv.data.model.RadioStation>> = UiState.Success(emptyList()),
    radioActiveTag: String? = null,
    radioActiveQuery: String = "",
    onLoadRadioDefault: () -> Unit = {},
    onLoadRadioTag: (String) -> Unit = {},
    onSearchRadio: (String) -> Unit = {},
    onPlayRadioStation: (com.nasmusic.tv.data.model.RadioStation) -> Unit = {},
    // Task 8: 滚动位置记忆（跨 Tab 切换保留）
    albumScrollIndex: Int = 0,
    albumScrollOffset: Int = 0,
    artistScrollIndex: Int = 0,
    artistScrollOffset: Int = 0,
    onAlbumScrollPositionChange: (Int, Int) -> Unit = { _, _ -> },
    onArtistScrollPositionChange: (Int, Int) -> Unit = { _, _ -> },
    // ── 歌曲下载状态 ──
    downloadStates: Map<String, DownloadState> = emptyMap(),
    onDownloadSong: (Song) -> Unit = {},
    onDeleteDownloadSong: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showSearchDialog by remember { mutableStateOf(false) }
    // v2.36.4 跟手 pager：tabPageFraction 是**连续页索引**（0..N-1），拖动中由手指位移
    // 驱动，相邻页同时组合并按 (页索引 − fraction) × 页宽 平移 —— 当前页划走、相邻页
    // 跟进，与竖屏播放页「封面 ⟷ 歌词」的跟手效果一致（真机反馈 v2.36.3 的
    // 「先滑出再推入」两段式观感不如播放页，本版对齐）。
    val tabScope = rememberCoroutineScope()
    val contentWidthPx = remember { mutableIntStateOf(0) }
    val tabPageFraction = remember { Animatable(LibraryTab.entries.indexOf(activeTab).toFloat()) }
    var tabDragging by remember { mutableStateOf(false) }
    var tabDragBaseIdx by remember { mutableIntStateOf(0) }
    var tabDragAccumPx by remember { mutableFloatStateOf(0f) }

    // 外部跳转（如搜索弹窗确认后跳 SEARCH tab）：瞬时对齐（弹窗遮挡屏幕，无需动画）。
    // 自己 settle 完成的 onTabSelected 不触发此分支（fraction 已等于目标索引）。
    LaunchedEffect(activeTab) {
        val idx = LibraryTab.entries.indexOf(activeTab).toFloat()
        if (tabPageFraction.targetValue != idx) tabPageFraction.snapTo(idx)
    }

    // 松手 / 取消的统一出口：按「四舍五入到最近页」吸附（拖过半页 → 切换，不足半页 → 弹回）。
    // snapTo 先对齐当前跟手位置（无跳变），再 animateTo 吸附；到位后才 onTabSelected，
    // 期间目标页已由 composedRange 组合，视觉连续。
    val settleTabs: () -> Unit = {
        val w = contentWidthPx.intValue.coerceAtLeast(1).toFloat()
        val current = (tabDragBaseIdx - tabDragAccumPx / w)
            .coerceIn(0f, (LibraryTab.entries.size - 1).toFloat())
        val targetIdx = current.roundToInt().coerceIn(0, LibraryTab.entries.size - 1)
        tabScope.launch {
            tabPageFraction.snapTo(current)
            tabDragging = false
            tabPageFraction.animateTo(targetIdx.toFloat(), tween(LIBRARY_TAB_SLIDE_ANIM_MS))
            onTabSelected(LibraryTab.entries[targetIdx])
        }
    }

    // Task 14: 首次曲库快捷键提示（3s 自动消失）
    val context = LocalContext.current
    val appPrefs = remember { com.nasmusic.tv.data.prefs.AppPreferences.getInstance(context) }
    val showShortcutHint by appPrefs.showLibraryShortcutHint.collectAsState(initial = true)
    var shortcutHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (showShortcutHint) {
            shortcutHintVisible = true
            kotlinx.coroutines.delay(3000)
            shortcutHintVisible = false
            appPrefs.setShowLibraryShortcutHint(false)
        }
    }

    // Task 8: 滚动位置记忆 — 从 ViewModel 恢复初始位置
    val albumListState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
        initialFirstVisibleItemIndex = albumScrollIndex,
        initialFirstVisibleItemScrollOffset = albumScrollOffset
    )
    val artistListState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
        initialFirstVisibleItemIndex = artistScrollIndex,
        initialFirstVisibleItemScrollOffset = artistScrollOffset
    )

    // 滚动位置变化时保存到 ViewModel（节流：仅在滚动停止后保存）
    LaunchedEffect(albumListState) {
        snapshotFlow { albumListState.firstVisibleItemIndex to albumListState.firstVisibleItemScrollOffset }
            .collect { pair: Pair<Int, Int> -> onAlbumScrollPositionChange(pair.first, pair.second) }
    }
    LaunchedEffect(artistListState) {
        snapshotFlow { artistListState.firstVisibleItemIndex to artistListState.firstVisibleItemScrollOffset }
            .collect { pair: Pair<Int, Int> -> onArtistScrollPositionChange(pair.first, pair.second) }
    }

    // Tab 切换时触发按需加载
    LaunchedEffect(activeTab) {
        when (activeTab) {
            LibraryTab.SONGS -> onLoadSongsFirstPage()
            LibraryTab.ARTISTS -> onLoadArtists()
            LibraryTab.YEARS -> onLoadYears()
            LibraryTab.RADIO -> onLoadRadioDefault()
            // DISCOVER：幂等加载（有暂存结果/加载中则跳过，切页不重搜）
            LibraryTab.DISCOVER -> onDiscoverEnsureLoaded()
            LibraryTab.SEARCH -> {}  // SearchTab handles its own loading
            else -> {}
        }
    }

    // 搜索时触发服务端搜索
    LaunchedEffect(filterQuery) {
        if (filterQuery.isNotBlank()) {
            onSearch(filterQuery)
        } else {
            onClearSearch()
        }
    }

    // 按当前 tab 类型过滤数据
    // ALBUMS Tab：搜索时用多源搜索结果按专辑聚合；无搜索时用本地加载的专辑
    val filteredAlbums by remember(filterQuery, albums, searchResults) {
        derivedStateOf {
            if (filterQuery.isBlank()) albums
            else {
                // 多源搜索结果中提取专辑（按 albumId 或 albumName 去重聚合）
                val searchAlbums = searchResults
                    .filter { it.album.isNotBlank() }
                    .groupBy { it.albumId ?: it.album.lowercase() }
                    .map { (_, songs) ->
                        val first = songs.first()
                        Album(
                            id = first.albumId ?: first.album,
                            name = first.album,
                            artist = first.artist,
                            songCount = songs.size,
                            year = first.year
                        )
                    }
                // 合并本地过滤的专辑（可能有多源搜索未覆盖的本地专辑）
                val localFiltered = albums.filter {
                    PinyinUtils.matches(it.name, filterQuery) || PinyinUtils.matches(it.artist, filterQuery)
                }
                // 去重合并：同名同艺术家的只保留一个
                val seen = mutableSetOf<String>()
                (localFiltered + searchAlbums).filter { album ->
                    val key = "${album.name.lowercase()}:${album.artist.lowercase()}"
                    seen.add(key)
                }
            }
        }
    }
    // SONGS Tab：有搜索结果时用搜索结果（SearchType.SONG_NAME_ONLY 过滤后的），否则用分页数据
    val displaySongs by remember(filterQuery, songsPaging.songs, searchResults) {
        derivedStateOf {
            if (filterQuery.isNotBlank()) searchResults
            else songsPaging.songs
        }
    }
    // ARTISTS Tab：搜索时用多源搜索结果按艺术家聚合；无搜索时用本地加载的艺术家
    val filteredArtists by remember(filterQuery, artists, searchResults) {
        derivedStateOf {
            if (filterQuery.isBlank()) {
                // 无搜索时也按 normalizeKey 去重，防止数据源因不可见字符导致重复
                val seen = mutableSetOf<String>()
                artists.filter { seen.add(ArtistSplitter.normalizeKey(it.name)) }
            } else {
                // 多源搜索结果中提取艺术家：
                // 必须先按 ArtistSplitter 拆分合唱名（"古天乐/萱萱" → 古天乐、萱萱），
                // 否则合唱名会变成一个独立艺术家块，且详情页按整串匹配永远查不到歌
                val searchArtists = MusicMerger.buildArtistsFromSongs(searchResults, "search_artist_")
                    .filter { PinyinUtils.matches(it.name, filterQuery) }
                // 合并本地过滤的艺术家
                val localFiltered = artists.filter {
                    PinyinUtils.matches(it.name, filterQuery)
                }
                // 归一化去重（NFKC + trim + 小写），同名不同写法只保留一块
                val seen = mutableSetOf<String>()
                (localFiltered + searchArtists).filter { artist ->
                    seen.add(ArtistSplitter.normalizeKey(artist.name))
                }.distinctBy { ArtistSplitter.normalizeKey(it.name) }
            }
        }
    }

    // 艺术家搜索时的歌曲映射：多源搜索结果按「拆分后的艺术家名」分组，与本地 artistSongsMap 合并
    val displayArtistSongsMap by remember(filterQuery, searchResults, artistSongsMap) {
        derivedStateOf {
            if (filterQuery.isBlank()) artistSongsMap
            else {
                val merged = artistSongsMap.toMutableMap()
                for (song in searchResults) {
                    for (name in ArtistSplitter.split(song.artist)) {
                        val key = ArtistSplitter.normalizeKey(name)
                        if (key.isBlank()) continue
                        val existing = merged[key].orEmpty()
                        // 去重合并
                        if (song.id !in existing.map { it.id }.toSet()) {
                            merged[key] = existing + song
                        }
                    }
                }
                merged
            }
        }
    }

    // 播放全部按钮的歌曲列表：按当前 Tab + 搜索状态动态计算
    val playAllSongs by remember(activeTab, filterQuery, songs, displaySongs, searchResults, filteredAlbums, filteredArtists, displayArtistSongsMap) {
        derivedStateOf {
            when (activeTab) {
                LibraryTab.ALBUMS -> {
                    if (filterQuery.isNotBlank()) {
                        val filteredAlbumIds = filteredAlbums.map { it.id }.toSet()
                        val albumSongs = songs.filter { it.albumId in filteredAlbumIds }
                        if (albumSongs.isNotEmpty()) albumSongs else searchResults
                    } else {
                        songs
                    }
                }
                LibraryTab.ARTISTS -> {
                    val listed = filteredArtists.flatMap { displayArtistSongsMap[ArtistSplitter.normalizeKey(it.name)].orEmpty() }
                    if (listed.isNotEmpty()) listed
                    else if (searchResults.isNotEmpty()) searchResults
                    else songs
                }
                LibraryTab.SONGS -> displaySongs
                LibraryTab.SEARCH, LibraryTab.DISCOVER, LibraryTab.RADIO -> songs
                else -> songs  // GENRES, YEARS
            }
        }
    }
    val showPlayAll = activeTab != LibraryTab.SEARCH && activeTab != LibraryTab.DISCOVER && activeTab != LibraryTab.RADIO && playAllSongs.isNotEmpty()

    Box(modifier = modifier.fillMaxSize()) {
        // v2.36.0 竖屏（方案 §4.3 / P0-14）：页 padding 32→16；标题/搜索框/TAB 由「一行」改「三行」
        val uiMode = LocalUiMode.current
        val isPhonePortrait = uiMode == UiMode.PhonePortrait
        Column(
            modifier = Modifier.fillMaxSize().padding(
                horizontal = if (isPhonePortrait) 16.dp else 32.dp,
                vertical = if (isPhonePortrait) 12.dp else 20.dp
            )
        ) {
            // 顶部标题 + TAB + 播放全部
            val libraryTitle: @Composable (Modifier) -> Unit = { m ->
                Text(
                    text = stringResource(R.string.nav_library),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.display(),
                    modifier = m
                )
            }
            val playAllChip: @Composable () -> Unit = {
                if (showPlayAll) {
                    Box(modifier = Modifier.widthIn(min = 80.dp)) {
                        ButtonChip(
                            text = stringResource(R.string.common_play_all),
                            onClick = { onPlayAllSongs(playAllSongs) }
                        )
                    }
                }
            }
            val searchField: @Composable (Modifier) -> Unit = { m ->
                SearchField(
                    query = filterQuery,
                    placeholder = stringResource(R.string.library_search_placeholder),
                    onOpenSearch = { showSearchDialog = true },
                    onClear = { onFilterQueryChange("") },
                    modifier = m
                )
            }
            // TAB 切换（可横向滑动——手机窄屏滑动浏览全部 tab）
            val tabsRow: @Composable (Modifier) -> Unit = { m ->
                Row(
                    modifier = m,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LibraryTab.entries.forEach { tab ->
                        val selected = tab == activeTab
                        FocusableSurface(
                            onClick = {
                                // 点击 TAB 行：动画吸附到目标页（与手势松手同一条动画路径）
                                val targetIdx = LibraryTab.entries.indexOf(tab)
                                if (targetIdx >= 0) {
                                    tabScope.launch {
                                        tabPageFraction.animateTo(
                                            targetIdx.toFloat(),
                                            tween(LIBRARY_TAB_SLIDE_ANIM_MS),
                                        )
                                        onTabSelected(tab)
                                    }
                                }
                            },
                            modifier = Modifier.padding(horizontal = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            focusedScale = 1.05f,
                            animationDurationMs = 200,
                            containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.2f) else Color.Transparent,
                            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                            contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                            focusedContentColor = NasMusicColors.Primary
                        ) {
                            Text(
                                text = stringResource(tab.titleRes),
                                color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                                fontSize = FontSize.button(),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            if (isPhonePortrait) {
                // 竖屏：标题行（标题 + 播放全部）→ 搜索框整行 → TAB 横滑行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    libraryTitle(Modifier.weight(1f))
                    playAllChip()
                }
                Spacer(modifier = Modifier.height(8.dp))
                searchField(Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                tabsRow(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    libraryTitle(Modifier.padding(end = 24.dp))

                    tabsRow(Modifier.weight(1f).horizontalScroll(rememberScrollState()))

                    // 搜索栏 + 播放全部（固定宽度区，避免挤压可滚动 TAB）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        searchField(Modifier.width(240.dp))

                        Spacer(modifier = Modifier.width(12.dp))

                        playAllChip()
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 内容区域（weight(1f) 限制高度，让内部可滚动列表正常工作）
            //
            // v2.36.1：手机（竖屏 + 横屏）在**内容区**左右滑切换子 TAB —— 用户明确要求
            // 「手机端横竖屏都要支持」，故此处是**手机专属**能力。
            // ⚠️ 按 `docs/conventions-adaptive-ui.md` 的 B1 硬规则，这里**显式读 `UiMode.TV`**
            // 并写明理由（而不是写 `!= UiMode.PhonePortrait`）：需求覆盖的是「竖屏 + 横屏」
            // 两个手机形态，TV 端**不加**手势，保持逐字不变（遥控器也没有横向滑动手势）。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { contentWidthPx.intValue = it.width }
                    // v2.36.4 真 pager：拖动中当前页划走、相邻页跟进（见上方说明）
                    .libraryTabSwipe(
                        enabled = uiMode == UiMode.PhonePortrait || uiMode == UiMode.PhoneLandscape,
                        onDragStart = {
                            tabDragging = true
                            tabDragBaseIdx = tabPageFraction.value.roundToInt()
                                .coerceIn(0, LibraryTab.entries.size - 1)
                            tabDragAccumPx = 0f
                        },
                        onDragDelta = { delta ->
                            tabDragAccumPx += delta
                        },
                        onSettle = { settleTabs() },
                    )
            ) {
            // v2.36.4：子 TAB 切换改为**真 pager**（对齐竖屏播放页「封面 ⟷ 歌词」的跟手效果）：
            // 页面位置由连续索引 `tabPageFraction` 唯一决定 —— 拖动中当前页与相邻页
            // **同时组合**并实时平移（当前页划走、相邻页跟进），松手后吸附到最近页。
            // ⚠️ 相邻页常驻组合的代价：最多同时组合 2 个 Tab 子树。各 Tab 的
            // LaunchedEffect 加载均幂等（loadRadioDefault / loadJamendoHot /
            // onDiscoverEnsureLoaded 有暂存跳过；SONGS/ARTISTS/YEARS 首页加载可重复触发）。
            // ⚠️ 页内容 lambda 必须用 `LibraryTab.entries[pageIdx]`（连续索引）而非
            // 外层 `activeTab`，否则拖动中相邻页会渲染成当前页内容。
            // fraction 读取器：拖动中 = 起始页索引 − 累积位移/页宽（左滑增大）；非拖动 = Animatable 当前值。
            // ⚠️ 只在 draw 相位（graphicsLayer）与 derivedStateOf 中读取 —— 不在组合期直接读，
            // 拖动/吸附动画期间仅 draw 失效，不触发逐帧重组。
            val pagerFractionReader: () -> Float = {
                if (tabDragging) {
                    (tabDragBaseIdx - tabDragAccumPx / contentWidthPx.intValue.coerceAtLeast(1))
                        .coerceIn(0f, (LibraryTab.entries.size - 1).toFloat())
                } else {
                    tabPageFraction.value
                }
            }
            // 参与组合的页：fraction 覆盖到的页（当前页 + 拖动方向相邻页）。
            // derivedStateOf：仅当页区间变化（跨页）时才重组，拖动中逐帧位移不重组。
            val composedRange by remember {
                derivedStateOf {
                    val f = pagerFractionReader()
                    maxOf(0, floor(f).toInt())..minOf(LibraryTab.entries.size - 1, ceil(f).toInt())
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                for (pageIdx in composedRange) {
                    val targetTab = LibraryTab.entries[pageIdx]
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // v2.36.4 修复：平移方向必须是 (页索引 − fraction) ——
                            // 手指左划 → fraction 增大 → 当前页向左划走、右邻页从右侧跟入
                            // （写成 (fraction − 页索引) 会整体反向，真机反馈已纠正）。
                            .graphicsLayer {
                                translationX = (pageIdx - pagerFractionReader()) * size.width
                            },
                    ) {
                        // SEARCH, DISCOVER, RADIO tabs handle their own loading/empty states
                        when (targetTab) {
                LibraryTab.SEARCH -> {
                    SearchTab(
                        searchKeyword = filterQuery,
                        searchResults = searchResults,
                        isSearching = isSearching,
                        favoriteIds = favoriteIds,
                        queueSongIds = queueSongIds,
                        enabledSources = enabledSearchSources,
                        onToggleSource = onToggleSearchSource,
                        onEnableAllSources = onEnableAllSearchSources,
                        onPlaySong = onPlaySong,
                        onToggleFavorite = onToggleFavorite,
                        onToggleQueue = onToggleQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        onPlayAll = onSearchTabPlayAll,
                        onAddAllToQueue = onSearchTabAddAllToQueue,
                        downloadStates = downloadStates,
                        onDownloadSong = onDownloadSong
                    )
                }
                LibraryTab.DISCOVER -> {
                    DiscoverTab(
                        dimensions = discoverDimensions,
                        filteredSongs = discoverFilteredSongs,
                        isLoading = discoverIsLoading,
                        favoriteIds = favoriteIds,
                        queueSongIds = queueSongIds,
                        currentDimensionValues = discoverCurrentDimensionValues,
                        onDimensionChanged = onDiscoverDimensionChanged,
                        onPlayAll = onDiscoverPlayAll,
                        onShuffle = onDiscoverShuffle,
                        onAddAllToQueue = onDiscoverAddAllToQueue,
                        onPlaySong = onPlaySong,
                        onToggleFavorite = onToggleFavorite,
                        onToggleQueue = onToggleQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        downloadStates = downloadStates,
                        onDownloadSong = onDownloadSong
                    )
                }
                LibraryTab.RADIO -> {
                    RadioTab(
                        radioStations = radioStations,
                        radioActiveTag = radioActiveTag,
                        radioActiveQuery = radioActiveQuery,
                        onLoadDefault = onLoadRadioDefault,
                        onLoadTag = onLoadRadioTag,
                        onSearch = onSearchRadio,
                        onPlayStation = onPlayRadioStation
                    )
                }
                else -> {
                    // 专辑/艺术家/歌曲 tab 搜索时有结果直接展示（多源搜索，不依赖 NAS 连接）
                    val hasSearchResults = filterQuery.isNotBlank() && searchResults.isNotEmpty()
                    val showSearchContent = hasSearchResults && (
                        targetTab == LibraryTab.ALBUMS ||
                        targetTab == LibraryTab.ARTISTS ||
                        targetTab == LibraryTab.SONGS
                    )
                    if (showSearchContent) {
                        // 搜索模式：直接展示搜索结果，跳过加载/连接/空状态检查
                        when (targetTab) {
                            LibraryTab.ALBUMS -> AlbumsTab(
                                albums = filteredAlbums,
                                songs = searchResults,
                                onPlayAlbum = onPlayAlbum,
                                onOpenAlbumDetail = onOpenAlbumDetail,
                                listState = albumListState
                            )
                            LibraryTab.ARTISTS -> ArtistsTab(
                                artists = filteredArtists,
                                artistSongsMap = displayArtistSongsMap,
                                onPlaySongs = onPlaySongs,
                                onOpenArtistDetail = onOpenArtistDetail,
                                listState = artistListState
                            )
                            LibraryTab.SONGS -> SongsTab(
                                songs = displaySongs,
                                favoriteIds = favoriteIds,
                                songsPaging = songsPaging,
                                isSearching = isSearching,
                                onLoadMore = onLoadSongsNextPage,
                                onPlaySong = onPlaySong,
                                queueSongIds = queueSongIds,
                                onToggleQueue = onToggleQueue,
                                onToggleFavorite = onToggleFavorite,
                                onAddToPlaylist = onAddToPlaylist,
                                downloadStates = downloadStates,
                                onDownloadSong = onDownloadSong,
                                onDeleteDownloadSong = onDeleteDownloadSong
                            )
                            else -> {}
                        }
                    } else if (isLoading) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // 顶部骨架标题条
                            Box(
                                modifier = Modifier
                                    .width(180.dp)
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NasMusicColors.SurfaceVariant)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            // 按 Tab 类型显示对应骨架
                            when (targetTab) {
                                LibraryTab.ALBUMS -> AlbumSkeletonGrid()
                                LibraryTab.ARTISTS -> ArtistSkeletonGrid()
                                else -> AlbumSkeletonGrid()  // 默认专辑骨架
                            }
                        }

                    } else {
                        // 有数据时直接展示（无论 NAS 是否连接，本地/百度/网络数据同样展示）
                        when (targetTab) {
                            LibraryTab.ALBUMS -> if (filteredAlbums.isEmpty()) {
                                EmptyHint(isConnected = isConnected, tab = LibraryTab.ALBUMS)
                            } else {
                                AlbumsTab(
                                    albums = filteredAlbums,
                                    songs = songs,
                                    onPlayAlbum = onPlayAlbum,
                                    onOpenAlbumDetail = onOpenAlbumDetail,
                                    listState = albumListState
                                )
                            }
                            LibraryTab.ARTISTS -> if (filteredArtists.isEmpty()) {
                                EmptyHint(isConnected = isConnected, tab = LibraryTab.ARTISTS)
                            } else {
                                ArtistsTab(
                                    artists = filteredArtists,
                                    artistSongsMap = displayArtistSongsMap,
                                    onPlaySongs = onPlaySongs,
                                    onOpenArtistDetail = onOpenArtistDetail,
                                    listState = artistListState
                                )
                            }
                            LibraryTab.SONGS -> if (displaySongs.isEmpty() && songsPaging.songs.isEmpty()) {
                                EmptyHint(isConnected = isConnected, tab = LibraryTab.SONGS)
                            } else {
                                SongsTab(
                                    songs = displaySongs,
                                    favoriteIds = favoriteIds,
                                    songsPaging = songsPaging,
                                    isSearching = isSearching,
                                    onLoadMore = onLoadSongsNextPage,
                                    onPlaySong = onPlaySong,
                                    queueSongIds = queueSongIds,
                                    onToggleQueue = onToggleQueue,
                                    onToggleFavorite = onToggleFavorite,
                                    onAddToPlaylist = onAddToPlaylist,
                                    downloadStates = downloadStates,
                                    onDownloadSong = onDownloadSong,
                                    onDeleteDownloadSong = onDeleteDownloadSong
                                )
                            }
                            LibraryTab.GENRES -> GenresTab(
                                genres = genres,
                                onSongsByGenre = onSongsByGenre,
                                onPlaySongs = onPlaySongs
                            )
                            LibraryTab.YEARS -> YearsTab(
                                years = years,
                                onSongsByYear = onSongsByYear,
                                onPlaySongs = onPlaySongs
                            )
                            else -> {}  // SEARCH, DISCOVER, RADIO handled above
                        }
                    }
                }
                        }   // when (targetTab)
                    }   // 页面 graphicsLayer Box
                }   // for (pageIdx in composedRange)
            }   // pager 容器 Box

                // Task 14: 遥控器快捷键提示浮层（3s 自动消失）
                androidx.compose.animation.AnimatedVisibility(
                    visible = shortcutHintVisible,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(NasMusicColors.SurfaceVariant)
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "◀ ▶ 导航  |  ◀◀ ▶▶ 切歌  |  确认 播放  |  长按 菜单  |  侧边索引 跳转",
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.button(),
                            maxLines = 1
                        )
                    }
                }
            }   // AnimatedVisibility

        }   // 内容区域 Box

        // 搜索键盘弹窗
        if (showSearchDialog) {
            TextInputDialog(
                title = stringResource(R.string.common_search),
                hint = stringResource(R.string.library_search_hint),
                initialValue = filterQuery,
                onConfirm = { query ->
                    onFilterQueryChange(query)
                    showSearchDialog = false
                    // 只在非搜索 tab 时跳到搜索 tab；其他 tab 留在当前页做维度搜索
                    if (activeTab != LibraryTab.SEARCH &&
                        activeTab != LibraryTab.ALBUMS &&
                        activeTab != LibraryTab.ARTISTS &&
                        activeTab != LibraryTab.SONGS) {
                        onTabSelected(LibraryTab.SEARCH)
                    }
                },
                onDismiss = { showSearchDialog = false },
                showQrCode = true,
                showHistory = true,
                historyItems = historyItems,
                onHistorySelect = { query ->
                    onFilterQueryChange(query)
                    showSearchDialog = false
                    if (activeTab != LibraryTab.SEARCH &&
                        activeTab != LibraryTab.ALBUMS &&
                        activeTab != LibraryTab.ARTISTS &&
                        activeTab != LibraryTab.SONGS) {
                        onTabSelected(LibraryTab.SEARCH)
                    }
                }
            )
        }

    }
}

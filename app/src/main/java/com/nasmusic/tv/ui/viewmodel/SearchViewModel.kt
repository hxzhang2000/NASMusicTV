package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.SearchAggregator
import com.nasmusic.tv.backend.SearchType
import com.nasmusic.tv.backend.FilterMode
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 搜索域 ViewModel（R-1 拆分自 MainViewModel）：
 * NAS+网络+聚合搜索、搜索来源点亮、网络搜索换一批。
 *
 * 依赖：BackendRegistry、SearchAggregator。
 * 搜索结果加入队列/播放经 SearchEvent 路由到 PlayerViewModel。
 */
class SearchViewModel(
    app: Application,
    private val backendRegistry: BackendRegistry,
    private val searchAggregator: SearchAggregator
) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val prefs = nasMusicApp.appPreferences

    // --- 搜索来源点亮状态（跨导航记忆，决定搜索哪些源） ---
    private val _enabledSearchSources = MutableStateFlow(MusicSourceType.DEFAULT_SEARCH_SOURCES)
    val enabledSearchSources: StateFlow<Set<MusicSourceType>> = _enabledSearchSources.asStateFlow()

    // --- 按需加载：搜索结果（服务端搜索）---
    private val _searchResults = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val searchResults: StateFlow<UiState<List<Song>>> = _searchResults.asStateFlow()
    /** 上次搜索的关键词：同词 + 同 searchType + 结果已成功时不重复搜索（跨导航暂存搜索结果） */
    private var lastSearchedKeyword: String? = null
    private var lastSearchType: SearchType = SearchType.SONG_NAME_OR_ARTIST

    // --- 网络音乐搜索结果（NetworkMusicManager 搜索）---
    private val _networkSearchResults = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val networkSearchResults: StateFlow<UiState<List<Song>>> = _networkSearchResults.asStateFlow()
    // 网络搜索关键词（跨页面导航时保留，避免回来后丢失搜索状态）
    private val _networkSearchKeyword = MutableStateFlow("")
    val networkSearchKeyword: StateFlow<String> = _networkSearchKeyword.asStateFlow()

    /** 搜索变异词后缀表：换一批时在原词后追加，用于突破单次搜索 30 首上限 */
    private val searchVariantSuffixes = listOf(
        "翻唱", "live", "现场", "伴奏", "钢琴", "吉他", "remix", "串烧",
        "经典", "怀旧", "演唱会", "DJ版", "纯音乐", "古风", "钢琴版", "吉他版",
        "慢速", "混音", "国语", "粤语", "英文", "日文", "韩文", "原唱"
    )

    /** 换一批时新歌数量达到该值才展示（否则继续尝试下一后缀） */
    private val minNewResultsForShuffle = 5

    /** 换一批单次点击最多尝试的后缀数量（避免搜索结果长期重复时空转） */
    private val maxShuffleAttemptsPerClick = 6

    /** 用户输入的原始搜索词（换一批的基准） */
    private var networkSearchBaseKeyword = ""

    /** 已用过的变异后缀（一轮内不重复，用尽后重置） */
    private val usedSearchVariants = mutableSetOf<String>()

    /** 换一批已展示过的歌曲（歌手, 歌名）集合：跨批次去重，保证每次换一批只出新歌 */
    private val seenNetworkSearchKeys = mutableSetOf<Pair<String, String>>()

    /** 网络音乐"播放全部"单次加入队列的上限（去重后取前 N 首） */
    private val maxNetworkBatchPlayCount = 30

    /** 搜索时当前曲库 Tab（决定 searchType），由 MainViewModel 同步 */
    var libraryActiveTabProvider: () -> com.nasmusic.tv.ui.screens.LibraryTab = { com.nasmusic.tv.ui.screens.LibraryTab.ALBUMS }

    /** 搜索输入提供器（有活动关键词时切源重搜用） */
    var librarySearchKeywordProvider: () -> String = { "" }

    /** NAS 本地缓存歌曲提供器（拼音搜索过滤用） */
    var nasLocalSongsProvider: () -> List<Song> = { emptyList() }

    /** 本地设备歌曲提供器 */
    var localDeviceSongsProvider: () -> List<Song> = { emptyList() }

    /** 加入队列动作（经 MainViewModel 路由到 PlayerViewModel） */
    var onAddToQueue: ((List<Song>) -> Unit)? = null
    /** 播放动作（经事件路由） */
    var onPlayBatch: ((List<Song>, Int) -> Unit)? = null
    /** 消息通道（经 MainViewModel 的 connectMessage 语义） */
    var showMessage: ((String) -> Unit)? = null
    var showMessageFor: ((Int, Int, String) -> Unit)? = null

    /** 切换某来源的点亮/熄灭状态 */
    fun toggleSearchSource(source: MusicSourceType) {
        val current = _enabledSearchSources.value.toMutableSet()
        if (source in current) current.remove(source) else current.add(source)
        _enabledSearchSources.value = current
        // 有活动关键词时按新来源范围立即重新搜索（force 跳过缓存）
        val kw = librarySearchKeywordProvider()
        if (kw.isNotBlank()) searchSongsOnServer(kw, force = true)
    }

    /** 全部点亮（回到默认状态） */
    fun enableAllSearchSources() {
        // 已是全部点亮则跳过重搜，避免冗余网络请求
        if (_enabledSearchSources.value == MusicSourceType.DEFAULT_SEARCH_SOURCES) return
        _enabledSearchSources.value = MusicSourceType.DEFAULT_SEARCH_SOURCES
        // 有活动关键词时按全部来源重新搜索（force 跳过缓存）
        val kw = librarySearchKeywordProvider()
        if (kw.isNotBlank()) searchSongsOnServer(kw, force = true)
    }

    /**
     * 服务端搜索歌曲（不依赖本地全量数据）
     *
     * @param force 设为 true 时跳过缓存（来源点亮切换后强制重搜）；默认 false 走缓存
     */
    fun searchSongsOnServer(query: String, force: Boolean = false) {
        if (query.isBlank()) {
            _searchResults.value = UiState.Success(emptyList())
            return
        }
        // 缓存命中：同一关键词 + 同一 searchType 且结果已是 Success 且非空
        val currentSearchType = when (libraryActiveTabProvider()) {
            com.nasmusic.tv.ui.screens.LibraryTab.ALBUMS -> SearchType.ALBUM
            com.nasmusic.tv.ui.screens.LibraryTab.ARTISTS -> SearchType.ARTIST
            com.nasmusic.tv.ui.screens.LibraryTab.SONGS -> SearchType.SONG_NAME_OR_ARTIST
            else -> SearchType.SONG_NAME_OR_ARTIST
        }
        if (!force && query == lastSearchedKeyword && currentSearchType == lastSearchType) {
            val cached = _searchResults.value
            if (cached is UiState.Success && cached.data.isNotEmpty()) {
                AppLog.d("SearchViewModel", "searchSongsOnServer: cached result for '$query'")
                return
            }
        }
        lastSearchedKeyword = query
        lastSearchType = currentSearchType
        _searchResults.value = UiState.Loading
        viewModelScope.launch {
            // 跨源融合搜索：NAS + 网络音乐 + 百度网盘 + Jamendo + 本地 并行搜索，合并去重
            // 按当前点亮来源搜索（点亮模式）
            try {
                // 拼音搜索时需要本地缓存：NAS 歌曲、本地音乐、百度网盘索引
                // 服务端不认拼音关键词，改用客户端 PinyinUtils.matches() 过滤
                val result = searchAggregator.search(
                    query,
                    sources = _enabledSearchSources.value,
                    filterMode = FilterMode.PRECISE,
                    searchType = currentSearchType,
                    nasLocalSongs = nasLocalSongsProvider(),
                    localDeviceSongs = localDeviceSongsProvider(),
                    baiduLocalSongs = nasMusicApp.baiduFileIndexCache.searchSongs(query, pinyinMatch = true)
                )
                val songs = result.allResults.map { it.song }
                _searchResults.value = UiState.Success(songs)
                // 搜索成功后才记录历史（空结果也算成功，记录用户确实搜过的词；
                // 失败不记录，避免污染热门榜）
                prefs.recordSearch(query)
                AppLog.d(
                    "SearchViewModel",
                    "searchSongsOnServer: ${songs.size} results for '$query' breakdown=${result.sourceBreakdown}"
                )
            } catch (e: Exception) {
                AppLog.e("SearchViewModel", "searchSongsOnServer failed", e)
                _searchResults.value = UiState.Error(
                    message = getApplication<Application>().getString(R.string.network_search_error, e.message?.take(50))
                )
            }
        }
    }

    /**
     * 清除搜索结果
     */
    fun clearSearch() {
        lastSearchedKeyword = null
        _searchResults.value = UiState.Success(emptyList())
    }

    /**
     * 搜索网络歌曲（通过 NetworkMusicManager，不依赖 NAS 连接）
     *
     * 策略：默认源优先，失败时 fallback 到其他源。
     * 搜索结果为统一 Song 模型（isNetworkSong=true）。
     */
    fun searchNetworkSongs(keyword: String) {
        AppLog.i("MetingDiag", "=== SearchViewModel.searchNetworkSongs === keyword='$keyword'")
        if (keyword.isBlank()) {
            AppLog.i("MetingDiag", "searchNetworkSongs: keyword blank")
            _networkSearchResults.value = UiState.Success(emptyList())
            _networkSearchKeyword.value = ""
            networkSearchBaseKeyword = ""
            usedSearchVariants.clear()
            return
        }
        // 用户手动搜索：重置换一批状态（基准词 + 已用变异词 + 已见歌曲集合）
        // 搜索历史记录在 doNetworkSearch 成功路径里，失败不记录
        networkSearchBaseKeyword = keyword
        usedSearchVariants.clear()
        seenNetworkSearchKeys.clear()
        doNetworkSearch(keyword)
    }

    /**
     * 换一批：用原搜索词 + 变异后缀重新搜索，突破单次搜索 30 首上限。
     * 跨批次去重：已展示过的歌曲会被过滤；在 [maxShuffleAttemptsPerClick] 个后缀
     * 中挑选新歌最多的批次展示，保证每次点击都有新歌且不会空转。
     */
    fun shuffleNetworkSearch() {
        val base = networkSearchBaseKeyword
        if (base.isBlank()) return
        viewModelScope.launch {
            var failed = false
            val (chosen, shown) = pickBestFreshBatch(
                seenKeys = seenNetworkSearchKeys,
                produce = {
                    val available = searchVariantSuffixes.filterNot { it in usedSearchVariants }
                    if (available.isEmpty()) usedSearchVariants.clear()
                    val suffix = searchVariantSuffixes
                        .filterNot { it in usedSearchVariants }
                        .shuffled()
                        .first()
                    usedSearchVariants.add(suffix)
                    val keyword = "$base $suffix"
                    val results = searchNetworkSongsBlocking(keyword)
                    if (results == null) failed = true
                    keyword to (results ?: emptyList())
                },
                songsOf = { it.second }
            )
            // 全部候选都搜索失败时保留错误态（searchNetworkSongsBlocking 已设置）
            if (failed && shown.isEmpty()) return@launch
            _networkSearchKeyword.value = chosen.first
            _networkSearchResults.value = UiState.Success(shown)
        }
    }

    /** 实际执行网络搜索（换一批与手动搜索共用），失败返回 null 并设置错误态 */
    private suspend fun searchNetworkSongsBlocking(keyword: String): List<Song>? {
        _networkSearchKeyword.value = keyword
        _networkSearchResults.value = UiState.Loading
        return try {
            nasMusicApp.networkMusicManager.search(keyword)
        } catch (e: Exception) {
            AppLog.e("MetingDiag", "doNetworkSearch failed: ${e.message}", e)
            _networkSearchResults.value = UiState.Error(
                message = getApplication<Application>().getString(R.string.network_search_failed, e.message?.take(50))
            )
            null
        }
    }

    /** 实际执行网络搜索（换一批与手动搜索共用） */
    private fun doNetworkSearch(keyword: String) {
        viewModelScope.launch {
            val results = searchNetworkSongsBlocking(keyword)
            if (results != null) {
                AppLog.i("MetingDiag", "doNetworkSearch: got ${results.size} results for '$keyword'")
                _networkSearchResults.value = UiState.Success(results)
                // 搜索成功后才记录历史（空结果也算成功；shuffleNetworkSearch 不走此路径，不会重复记录）
                prefs.recordSearch(keyword)
            }
        }
    }

    /**
     * 清除网络搜索结果
     */
    fun clearNetworkSearch() {
        _networkSearchResults.value = UiState.Success(emptyList())
        _networkSearchKeyword.value = ""
        networkSearchBaseKeyword = ""
        usedSearchVariants.clear()
        seenNetworkSearchKeys.clear()
    }

    /**
     * 全部加入列表：将当前搜索结果追加到播放队列末尾（不替换队列），
     * 与队列已有歌曲按（歌手, 歌曲名）去重，保证队列中没有重复歌曲。
     * 每次追加前实时读取队列，反复「换一批 → 全部加入列表」可持续扩充队列。
     */
    fun addAllSearchResultsToQueue(existingQueueKeysProvider: () -> Set<Pair<String, String>>) {
        val results = _networkSearchResults.value.dataOrNull() ?: return
        if (results.isEmpty()) return
        val existingKeys = existingQueueKeysProvider()
        val toAdd = results
            .distinctBy { it.artist.trim() to it.title.trim() }
            .filterNot { (it.artist.trim() to it.title.trim()) in existingKeys }
        if (toAdd.isEmpty()) {
            showMessage?.invoke(getApplication<Application>().getString(R.string.queue_contains_all_results))
            return
        }
        onAddToQueue?.invoke(toAdd)
        showMessageFor?.invoke(toAdd.size, results.size - toAdd.size, "added_to_queue_with_skipped")
    }

    /**
     * 播放全部搜索结果。
     *
     * 加入队列前按（歌手, 歌曲名）去重；去重后最多取 30 首，
     * 不足 30 首时有多少加多少。不触发导航，由调用方（AppRoot）处理 navigateTo(NowPlaying)。
     */
    fun playAllSearchResults() {
        val results = _networkSearchResults.value.dataOrNull() ?: return
        if (results.isEmpty()) return
        val deduped = results
            .distinctBy { it.artist.trim() to it.title.trim() }
            .take(maxNetworkBatchPlayCount)
        if (deduped.isEmpty()) return
        onPlayBatch?.invoke(deduped, 0)
    }

    /**
     * 统一的「换一批」核心逻辑（从 MainViewModel 迁入，网络搜索场景专用）。
     * 语义与 MainViewModel.pickBestFreshBatch 一致。
     */
    private suspend fun <T> pickBestFreshBatch(
        seenKeys: MutableSet<Pair<String, String>>,
        maxAttempts: Int = maxShuffleAttemptsPerClick,
        minNewResults: Int = minNewResultsForShuffle,
        produce: suspend () -> T,
        songsOf: (T) -> List<Song>
    ): Pair<T, List<Song>> {
        var best: T? = null
        var bestFresh: List<Song> = emptyList()
        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            val candidate = produce()
            val fresh = songsOf(candidate).filterNot { (it.artist.trim() to it.title.trim()) in seenKeys }
            if (fresh.size > bestFresh.size) {
                best = candidate
                bestFresh = fresh
            }
            if (fresh.size >= minNewResults) break
        }
        val chosen = best
        val result = if (chosen == null || bestFresh.isEmpty()) {
            // 所有候选都没有新歌：已见集合饱和，从头再来一批
            seenKeys.clear()
            val freshProduce = produce()
            freshProduce to songsOf(freshProduce)
        } else {
            chosen to bestFresh
        }
        // 修复（M-9）：硬上限防长期挂机场景集合无限增长
        if (seenKeys.size >= 4000) seenKeys.clear()
        result.second.forEach { seenKeys.add(it.artist.trim() to it.title.trim()) }
        return result
    }
}

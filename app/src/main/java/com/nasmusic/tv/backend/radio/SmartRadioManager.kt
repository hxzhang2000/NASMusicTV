package com.nasmusic.tv.backend.radio

import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 智能电台 Manager（F2-3）：基于当前歌曲（流派+歌手）生成"越听越对味"的随机流。
 *
 * 架构复用 WeatherRadioManager 模式（Manager + 匹配策略 + 队列注入）。
 * 打分策略在 [RadioSongScorer]（纯函数）。
 *
 * 数据源：NAS 曲库（getSongs 分页拉取，网络歌曲无 genre 不适用——首期边界）。
 * 曲库 >2000 首时按 seed 流派先筛（两级查询），否则全量拉取。
 */
class SmartRadioManager(
    private val backendRegistry: BackendRegistry,
    private val scope: CoroutineScope,
    private val playCountsProvider: suspend () -> Map<String, Int> = { emptyMap() },
    /** F2-3 多源化：Meting 歌单采样 provider（null = 无网络源） */
    private val networkPlaylistProvider: (suspend (playlistId: String) -> List<Song>)? = null,
    /** F2-3 多源化：网络歌单 id 列表（与 provider 配套） */
    private val networkPlaylistIds: List<String> = emptyList(),
    /** F2-3 多源化：本地歌曲 provider（含下载歌曲） */
    private val localSongsProvider: (suspend () -> List<Song>)? = null
) {
    sealed interface State {
        data object Idle : State
        data object Generating : State
        data class Playing(val seedTitle: String, val batchIndex: Int) : State
        data object Exhausted : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 已播批次 songId 集（跨批次去重） */
    private val playedIds = mutableSetOf<String>()
    /** 当前批次的 songId 集（skip 时只清这一批，保留跨批次去重历史） */
    private val currentBatchIds = mutableSetOf<String>()
    /** 保护 playedIds/currentBatchIds/cachedLibrary 等共享状态（scope 内与外部调用方可能跨线程） */
    private val stateLock = Any()
    @Volatile private var currentSeed: Song? = null
    @Volatile private var generateJob: Job? = null
    /** 曲库缓存（一次会话内复用；stop() 时清空） */
    @Volatile private var cachedLibrary: List<Song>? = null

    /**
     * F2-3 多源化：无种子启动（首页入口）——偏好加权随机（play_counts），不依赖当前播放。
     */
    fun startFromScratch(
        onBatchReady: (List<Song>, SeedContext) -> Unit
    ) {
        // 用播放次数最多的歌做种子（无历史时用池内第一首）——保证打分有方向
        generateJob?.cancel()
        _state.value = State.Generating
        generateJob = scope.launch {
            try {
                val library = loadLibrary(null)
                if (library.isEmpty()) {
                    _state.value = State.Exhausted
                    return@launch
                }
                val counts = playCountsProvider()
                val seed = library.maxByOrNull { counts[it.id] ?: 0 } ?: library.first()
                currentSeed = seed
                synchronized(stateLock) {
                    playedIds.clear()
                    currentBatchIds.clear()
                }
                val batch = RadioSongScorer.generateBatch(library, seed, counts, playedIds.toSet(), BATCH_SIZE)
                if (batch.isEmpty()) {
                    // T4 修复：clear + toSet() 快照均在锁内
                    val retry = synchronized(stateLock) {
                        playedIds.clear()
                        RadioSongScorer.generateBatch(library, seed, counts, playedIds.toSet(), BATCH_SIZE)
                    }
                    if (retry.isEmpty()) {
                        _state.value = State.Exhausted
                        return@launch
                    }
                    emitBatch(retry, seed, onBatchReady)
                } else {
                    emitBatch(batch, seed, onBatchReady)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "startFromScratch failed", e)
                _state.value = State.Exhausted
            }
        }
    }

    /**
     * 从种子歌曲启动电台。生成第一批并回调 [onBatchReady] 入队播放。
     */
    fun startFromCurrentSong(
        seed: Song,
        onBatchReady: (List<Song>, SeedContext) -> Unit
    ) {
        stopInternal(resetState = true)
        currentSeed = seed
        _state.value = State.Generating
        generateJob = scope.launch {
            try {
                val library = loadLibrary(seed)
                if (library.isEmpty()) {
                    AppLog.w(TAG, "startFromCurrentSong: empty library, exhausted")
                    _state.value = State.Exhausted
                    return@launch
                }
                val counts = playCountsProvider()
                val batch = RadioSongScorer.generateBatch(
                    candidates = library,
                    seed = seed,
                    playCounts = counts,
                    excludedIds = playedIds.toSet(),
                    batchSize = BATCH_SIZE
                )
                if (batch.isEmpty()) {
                    // 曲库耗尽（全部已播）→ 清历史再来一批（换一批语义）
                    // T4 修复：clear + toSet() 快照均在锁内
                    val retry = synchronized(stateLock) {
                        playedIds.clear()
                        RadioSongScorer.generateBatch(library, seed, counts, playedIds.toSet(), BATCH_SIZE)
                    }
                    if (retry.isEmpty()) {
                        _state.value = State.Exhausted
                        return@launch
                    }
                    emitBatch(retry, seed, onBatchReady)
                } else {
                    emitBatch(batch, seed, onBatchReady)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "startFromCurrentSong failed", e)
                _state.value = State.Exhausted
            }
        }
    }

    /**
     * 手动"换一批"：保留种子与跨批次已播历史，仅移除当前批次后重新生成。
     *
     * 修复：原实现走 [startFromCurrentSong] → stopInternal(resetState=true) →
     * playedIds.clear()，把全部历史清空，导致跨批次去重形同虚设（与注释"清当前批次"矛盾）。
     * 同时不再清 cachedLibrary，避免每次换一批都重新分页拉曲库。
     */
    fun skip(onBatchReady: (List<Song>, SeedContext) -> Unit) {
        val seed = currentSeed ?: return
        synchronized(stateLock) {
            playedIds.removeAll(currentBatchIds)
            currentBatchIds.clear()
        }
        generateJob?.cancel()
        _state.value = State.Generating
        generateJob = scope.launch {
            try {
                val library = loadLibrary(seed)
                if (library.isEmpty()) {
                    AppLog.w(TAG, "skip: empty library, exhausted")
                    _state.value = State.Exhausted
                    return@launch
                }
                val counts = playCountsProvider()
                val batch = RadioSongScorer.generateBatch(library, seed, counts, playedIds.toSet(), BATCH_SIZE)
                if (batch.isEmpty()) {
                    // 曲库真正耗尽（全部历史已播）→ 清历史重来
                    // T4 修复：clear + toSet() 快照均在锁内
                    val retry = synchronized(stateLock) {
                        playedIds.clear()
                        RadioSongScorer.generateBatch(library, seed, counts, playedIds.toSet(), BATCH_SIZE)
                    }
                    if (retry.isEmpty()) {
                        _state.value = State.Exhausted
                        return@launch
                    }
                    emitBatch(retry, seed, onBatchReady)
                } else {
                    emitBatch(batch, seed, onBatchReady)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "skip failed", e)
                _state.value = State.Exhausted
            }
        }
    }

    /**
     * F2-3 首页列表化：仅生成批次供首页浏览（不自动播放，用户点卡片后播）。
     * 已有电台上下文（currentSeed）则视为"换一批"（排除已推荐歌曲），
     * 否则无种子启动（偏好加权随机）。回调内不播歌，只更新首页批次列表。
     */
    fun generateOnly(onBatchReady: (List<Song>, SeedContext) -> Unit) {
        val seed = currentSeed
        if (seed != null) startFromCurrentSong(seed, onBatchReady)
        else startFromScratch(onBatchReady)
    }

    /** 停止电台（清缓存与状态） */
    fun stop() = stopInternal(resetState = true)

    private fun stopInternal(resetState: Boolean) {
        generateJob?.cancel()
        generateJob = null
        synchronized(stateLock) {
            playedIds.clear()
            currentBatchIds.clear()
        }
        cachedLibrary = null
        currentSeed = null
        if (resetState) _state.value = State.Idle
    }

    private suspend fun emitBatch(batch: List<Song>, seed: Song, onBatchReady: (List<Song>, SeedContext) -> Unit) {
        // P2-6 修复（2026-09-16）：陈旧任务守卫。
        // stop()/新 generate 会 cancel 旧 generateJob，但取消是协作式的——旧协程可能
        // 恰好越过挂起点执行到此，把已清空的集合重新填充（回写脏数据）。
        // 以「自己仍是当前 generateJob」为唯一准入条件；不满足则直接放弃本批。
        // P2-6（2026-09-16）：用 isActive 判断自身协程是否已被 stop()/新 generate cancel。
        // 不用「selfJob === generateJob」身份比较：generateJob = scope.launch{} 的赋值
        // 与协程体启动存在时序竞态（多线程 dispatcher 下 body 可能先于赋值执行）。
        // isActive 判据无此竞态；锁内二次确认为防「检查通过后才发生 cancel」的窗口。
        val selfJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job] ?: return
        if (!selfJob.isActive) return
        val batchIds = batch.map { it.id }
        val totalPlayed = synchronized(stateLock) {
            if (!selfJob.isActive) return
            currentBatchIds.clear()
            currentBatchIds.addAll(batchIds)
            playedIds.addAll(batchIds)
            playedIds.size
        }
        _state.value = State.Playing(seed.title, totalPlayed / BATCH_SIZE)
        onBatchReady(batch, SeedContext(seed))
    }

    /**
     * 曲库加载（两级策略）：
     * - 有缓存直接用
     * - seed 有 genre 且曲库大：getSongsByGenre 先筛（流派内打分）
     * - 否则全量分页拉取（上限 [LIBRARY_HARD_CAP] 防大库拖垮）
     */
    private suspend fun loadLibrary(seed: Song?): List<Song> {
        cachedLibrary?.let { return it }
        val adapter = backendRegistry.getAdapter()

        val genreFiltered = if (adapter != null && seed != null) {
            seed.genre?.takeIf { it.isNotBlank() }?.let { g ->
                try { adapter.getSongsByGenre(g) } catch (e: Exception) {
                    AppLog.w(TAG, "getSongsByGenre failed: ${e.message}"); emptyList()
                }
            }
        } else null
        val nasLibrary: List<Song> = if (genreFiltered != null && genreFiltered.size >= MIN_GENRE_POOL) {
            genreFiltered
        } else if (adapter != null) {
            // 全量拉取（分页，硬上限）
            val all = mutableListOf<Song>()
            var offset = 0
            while (all.size < LIBRARY_HARD_CAP) {
                val page = try { adapter.getSongs(PAGE_SIZE, offset) } catch (e: Exception) {
                    AppLog.w(TAG, "getSongs page failed: ${e.message}"); break
                }
                if (page.isEmpty()) break
                all.addAll(page)
                offset += page.size
            }
            all.toList()
        } else emptyList()

        // F2-3 多源化：聚合 NAS + 本地歌曲 + Meting 网络歌单采样
        val localSongs = try { localSongsProvider?.invoke() ?: emptyList() } catch (e: Exception) {
            AppLog.w(TAG, "localSongs failed: ${e.message}"); emptyList()
        }
        val networkSongs = loadNetworkSamples()
        val combined = (nasLibrary + localSongs + networkSongs).distinctBy { it.id }
        AppLog.d(TAG, "loadLibrary multi-source: nas=${nasLibrary.size} local=${localSongs.size} net=${networkSongs.size} combined=${combined.size}")
        synchronized(stateLock) { cachedLibrary = combined }
        return combined
    }

    /** F2-3 多源化：Meting 歌单采样（随机抽几个歌单、每单抽 N 首） */
    private suspend fun loadNetworkSamples(): List<Song> {
        val provider = networkPlaylistProvider ?: return emptyList()
        if (networkPlaylistIds.isEmpty()) return emptyList()
        val result = mutableListOf<Song>()
        for (pid in networkPlaylistIds.shuffled().take(NETWORK_PLAYLIST_SAMPLE_COUNT)) {
            try {
                val songs = provider(pid)
                if (songs.isNotEmpty()) {
                    result.addAll(songs.shuffled().take(NETWORK_PLAYLIST_SONG_CAP).map {
                        it.copy(isNetworkSong = true, networkSource = it.networkSource ?: "meting")
                    })
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "network playlist " + pid + " failed: ${e.message}")
            }
        }
        return result
    }

    /** 种子上下文（回调方播放批次时定位种子信息） */
    data class SeedContext(val seed: Song)

    companion object {
        private const val TAG = "SmartRadio"
        const val BATCH_SIZE = 20
        const val PAGE_SIZE = 500
        const val LIBRARY_HARD_CAP = 5000
        /** 流派池最小规模（低于此规模回退全量，流派池太窄电台会单调） */
        const val MIN_GENRE_POOL = 50
        /** F2-3 多源化：网络歌单采样数 */
        const val NETWORK_PLAYLIST_SAMPLE_COUNT = 3
        /** F2-3 多源化：每个网络歌单抽取歌曲上限 */
        const val NETWORK_PLAYLIST_SONG_CAP = 30
    }
}

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
    private val playCountsProvider: suspend () -> Map<String, Int> = { emptyMap() }
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
    private var currentSeed: Song? = null
    private var generateJob: Job? = null
    /** 曲库缓存（一次会话内复用；stop() 时清空） */
    private var cachedLibrary: List<Song>? = null

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
                    excludedIds = playedIds,
                    batchSize = BATCH_SIZE
                )
                if (batch.isEmpty()) {
                    // 曲库耗尽（全部已播）→ 清历史再来一批（换一批语义）
                    playedIds.clear()
                    val retry = RadioSongScorer.generateBatch(library, seed, counts, playedIds, BATCH_SIZE)
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

    /** 手动"换一批"：保留种子，重新生成（清当前批次历史） */
    fun skip(onBatchReady: (List<Song>, SeedContext) -> Unit) {
        val seed = currentSeed ?: return
        startFromCurrentSong(seed, onBatchReady)
    }

    /** 停止电台（清缓存与状态） */
    fun stop() = stopInternal(resetState = true)

    private fun stopInternal(resetState: Boolean) {
        generateJob?.cancel()
        generateJob = null
        playedIds.clear()
        cachedLibrary = null
        currentSeed = null
        if (resetState) _state.value = State.Idle
    }

    private suspend fun emitBatch(batch: List<Song>, seed: Song, onBatchReady: (List<Song>, SeedContext) -> Unit) {
        playedIds.addAll(batch.map { it.id })
        _state.value = State.Playing(seed.title, playedIds.size / BATCH_SIZE)
        onBatchReady(batch, SeedContext(seed))
    }

    /**
     * 曲库加载（两级策略）：
     * - 有缓存直接用
     * - seed 有 genre 且曲库大：getSongsByGenre 先筛（流派内打分）
     * - 否则全量分页拉取（上限 [LIBRARY_HARD_CAP] 防大库拖垮）
     */
    private suspend fun loadLibrary(seed: Song): List<Song> {
        cachedLibrary?.let { return it }
        val adapter = backendRegistry.getAdapter() ?: return emptyList()

        val genreFiltered = seed.genre?.takeIf { it.isNotBlank() }?.let { g ->
            try { adapter.getSongsByGenre(g) } catch (e: Exception) {
                AppLog.w(TAG, "getSongsByGenre failed: ${e.message}"); emptyList()
            }
        }
        val library = if (genreFiltered != null && genreFiltered.size >= MIN_GENRE_POOL) {
            genreFiltered
        } else {
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
        }
        cachedLibrary = library
        return library
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
    }
}

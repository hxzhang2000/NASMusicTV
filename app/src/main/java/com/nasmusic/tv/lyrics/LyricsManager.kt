package com.nasmusic.tv.lyrics

import android.content.Context
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsAvailability
import com.nasmusic.tv.data.model.LyricsSource
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 歌词管理器
 * 负责歌词的获取、缓存和匹配
 *
 * 获取优先级：
 * 1. 本地歌曲：同目录同名 .lrc 侧车文件 → 内嵌 ID3 歌词
 * 2. NAS 歌曲：后端 API 歌词（**优先于持久化缓存**，避免误匹配的网络歌词遮蔽权威歌词）
 * 3. 持久化缓存（仅网络歌词，按 songId 匹配）
 * 4. 网络匹配（标题 + 歌手相关性校验后的模糊搜索）
 *
 * 持久化缓存写入时机：用户主动切换到网络歌词来源（[MainViewModel.switchLyricsSource]）
 * 或自动匹配到的网络歌词在播放完成后提交；后端歌词不参与持久化缓存。
 */
class LyricsManager(
    private val context: Context,
    private val backendRegistry: BackendRegistry,
    private val networkMusicManager: NetworkMusicManager? = null,
    /** 酷狗歌词端点（空字符串使用默认值） */
    kugouBaseUrl: String = "",
    /** 网易云歌词端点（空字符串使用默认值） */
    neteaseBaseUrl: String = "",
    /**
     * F-3（R-7 并行项）：端点动态 provider（优先于上面的急切值）。
     * 传入后设置页改歌词源 URL 即时生效（经 AppPreferences 的 @Volatile 镜像读取，无 IO）。
     */
    kugouBaseUrlProvider: (() -> String)? = null,
    neteaseBaseUrlProvider: (() -> String)? = null
) {

    private val networkProvider = LyricsNetworkProvider(
        // F-3：优先用 provider（即时生效）；未传时回退急切值（向后兼容）
        kugouBaseUrl = (kugouBaseUrlProvider?.invoke() ?: kugouBaseUrl)
            .ifBlank { LyricsNetworkProvider.DEFAULT_KUGOU_BASE_URL },
        // kugouLrcUrl 独立于 kugouBaseUrl：搜索端点用 HTTP（SSL 证书不匹配），
        // 但歌词下载端点 krcs.kugou.com 的 HTTPS 正常
        kugouLrcUrl = LyricsNetworkProvider.DEFAULT_KUGOU_LRC_URL,
        neteaseBaseUrl = (neteaseBaseUrlProvider?.invoke() ?: neteaseBaseUrl)
            .ifBlank { LyricsNetworkProvider.DEFAULT_NETEASE_BASE_URL }
    )
    private val persistentCache = LyricsPersistentCache(context)

    /**
     * 网络歌词暂存区（songId → lrcText）。
     * 用户切到网络歌词时暂存，歌曲播放完成时提交到持久化缓存。
     * 如果用户切歌或切换来源，暂存内容被丢弃（不写入持久化）。
     */
    private val pendingNetworkLyrics = ConcurrentHashMap<String, String>()

    /**
     * 网络歌词候选缓存（songId → 全部候选列表，跨轮次累积）。
     * 首次搜索时获取，后续轮次追加新结果，切换索引时只读缓存不重新请求。
     */
    private val cachedCandidates = ConcurrentHashMap<String, List<String>>()

    /**
     * 当前搜索变异轮次索引（songId → round），用于候选耗尽时换一批重新搜索。
     */
    private val candidateVariantRound = ConcurrentHashMap<String, Int>()

    /** 后台 IO 作用域：歌词缓存落盘等磁盘写操作不得阻塞主线程（否则掉帧/ANR） */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 候选拉取互斥锁：cachedCandidates 的 containsKey→put 非原子，并发调用会重复发起网络请求（缓存惊群） */
    private val candidateFetchMutex = Mutex()

    companion object {
        /** 搜索变异后缀，候选耗尽时依次尝试获取新候选 */
        private val variantSuffixes = listOf("", "歌词", "完整版", "原唱", "歌曲", "lyrics")
    }

    /**
     * 获取歌词 - 按优先级尝试多个来源
     * 1. 持久化缓存（仅网络歌词，按 songId 匹配）
     * 2. 后端API / NetworkMusicManager
     * 3. 网络模糊匹配
     */
    suspend fun getLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        AppLog.d("LyricsManager", "getLyrics: song=${song.title}, artist=${song.artist}, id=${song.id}")

        // 0. 本地歌曲：优先读取同目录同名 .lrc 文件（本地音乐专用，最高优先级）
        //    若无侧车 LRC，fallback 到内嵌歌词（ID3 USLT 帧）
        if (song.isLocalSong) {
            val sidecarText = LocalLyricsProvider.getSidecarLyrics(song)
            if (sidecarText != null) {
                val lyrics = LrcParser.parse(sidecarText, song.id)
                    .copy(source = LyricsSource.LOCAL_FILE)
                // 边界守卫：纯文本/非 LRC 侧车文件会解析出 0 行，不能直接 return（否则阻断后续回退）
                if (lyrics.lines.isNotEmpty()) {
                    AppLog.d("LyricsManager", "getLyrics: sidecar LRC, ${lyrics.lines.size} lines")
                    return@withContext lyrics
                }
                AppLog.w("LyricsManager", "getLyrics: sidecar LRC parsed to 0 lines, fall through")
            }
            val embeddedText = LocalLyricsProvider.getEmbeddedLyrics(song)
            if (embeddedText != null) {
                val lyrics = LrcParser.parse(embeddedText, song.id)
                    .copy(source = LyricsSource.EMBEDDED)
                // 边界守卫：非 LRC 内嵌歌词（USLT 纯文本）解析为空时不能 return，
                // 否则返回非 null 的空歌词，阻断网络回退 → 用户看到空白歌词
                if (lyrics.lines.isNotEmpty()) {
                    AppLog.d("LyricsManager", "getLyrics: embedded ID3 lyrics, ${lyrics.lines.size} lines")
                    return@withContext lyrics
                }
                AppLog.w("LyricsManager", "getLyrics: embedded lyrics parsed to 0 lines, fall through")
            }
        }

        // 1. NAS 歌曲：后端歌词优先于持久化缓存
        //    （缓存中的网络歌词可能是误匹配结果，不应遮蔽后端权威歌词）
        fetchBackendLyrics(song)?.let { backendLyrics ->
            AppLog.d("LyricsManager", "getLyrics: backend hit, ${backendLyrics.lines.size} lines")
            return@withContext backendLyrics
        }

        // 2. 持久化缓存（仅网络歌词，按 songId 匹配）
        val cached = persistentCache.get(song.id)
        if (cached != null) {
            val lyrics = LrcParser.parse(cached.lrcText, song.id)
                .copy(source = LyricsSource.CACHED)
            if (lyrics.lines.isNotEmpty()) {
                AppLog.d("LyricsManager", "getLyrics: found in persistent cache, ${lyrics.lines.size} lines")
                return@withContext lyrics
            }
            AppLog.w("LyricsManager", "getLyrics: cached lyrics parsed to 0 lines, fall through")
        } else {
            AppLog.d("LyricsManager", "getLyrics: no persistent cache")
        }

        // 3. 网络匹配（后端/缓存均未命中）
        val availability = checkAvailability(song)
        val lyrics = availability.backend ?: availability.network
        if (lyrics != null) {
            AppLog.d("LyricsManager", "getLyrics: source=${lyrics.source}, lines=${lyrics.lines.size}")
            return@withContext lyrics
        }

        AppLog.w("LyricsManager", "getLyrics: all sources returned null")
        null
    }

    /**
     * 从 NAS 后端获取歌词（本地/网络歌曲无 NAS 后端，直接返回 null）。
     * 统一入口，供 [getLyrics] 与 [checkAvailability] 复用。
     */
    private suspend fun fetchBackendLyrics(song: Song): Lyrics? {
        if (song.isLocalSong || song.isNetworkSong) return null
        val adapter = backendRegistry.getAdapter() ?: return null
        return try {
            val text = adapter.getLyrics(song.id)
            if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
                LrcParser.parse(text, song.id).copy(source = LyricsSource.EMBEDDED)
            } else null
        } catch (e: Exception) {
            AppLog.w("LyricsManager", "backend getLyrics failed: ${e.message}")
            null
        }
    }

    /**
     * 检查歌词来源可用性
     * 同时尝试后端 API 和网络匹配，两个来源互不影响。
     * 不自动写入持久化缓存——持久化仅在用户主动切换网络歌词时触发。
     */
    suspend fun checkAvailability(song: Song): LyricsAvailability = withContext(Dispatchers.IO) {
        AppLog.d("LyricsManager", "checkAvailability: song=${song.title}, artist=${song.artist}, id=${song.id}")

        // 本地歌曲：无 NAS 后端，直接走网络模糊匹配（本地 LRC 已在 getLyrics 优先处理）
        if (song.isLocalSong) {
            val networkLyrics = try {
                val text = networkProvider.fetchLyrics(song.title, song.artist)
                if (text != null) {
                    LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                } else null
            } catch (e: Exception) {
                AppLog.w("LyricsManager", "local song network fetch failed: ${e.message}")
                null
            }
            val result = LyricsAvailability(backend = null, network = networkLyrics)
            AppLog.d("LyricsManager", "checkAvailability(local song): network=${result.hasNetwork}")
            return@withContext result
        }

        // 网络歌曲：通过 NetworkMusicManager 获取歌词，不走后端 API
        if (song.isNetworkSong && networkMusicManager != null) {
            val networkLyrics = try {
                val text = networkMusicManager.resolveLyrics(song)
                if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
                    LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                } else null
            } catch (e: Exception) {
                AppLog.w("LyricsManager", "network resolveLyrics failed: ${e.message}")
                null
            }
            // 网络歌曲也尝试模糊匹配作为 fallback
            val fuzzyLyrics = if (networkLyrics == null) {
                try {
                    val text = networkProvider.fetchLyrics(song.title, song.artist)
                    if (text != null) {
                        LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                    } else null
                } catch (e: Exception) {
                    AppLog.w("LyricsManager", "network fuzzy fetch failed: ${e.message}")
                    null
                }
            } else null
            val result = LyricsAvailability(backend = null, network = networkLyrics ?: fuzzyLyrics)
            AppLog.d("LyricsManager", "checkAvailability(network song): backend=${result.hasBackend}, network=${result.hasNetwork}")
            return@withContext result
        }

        // NAS 歌曲：先查后端 API
        val backendLyrics = fetchBackendLyrics(song)
        if (backendLyrics != null) {
            // 后端已命中 → 不再无条件发起网络请求（省流量、降延迟、避免误匹配）
            val result = LyricsAvailability(backend = backendLyrics, network = null)
            AppLog.d("LyricsManager", "checkAvailability: backend hit, skip network")
            return@withContext result
        }

        // 后端未命中 → 尝试网络歌词
        val networkLyrics = try {
            val text = networkProvider.fetchLyrics(song.title, song.artist)
            if (text != null) {
                LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
            } else null
        } catch (e: Exception) {
            AppLog.w("LyricsManager", "network fetch failed: ${e.message}")
            null
        }

        val result = LyricsAvailability(backend = null, network = networkLyrics)
        AppLog.d("LyricsManager", "checkAvailability: backend=false, network=${result.hasNetwork}")
        result
    }

    /**
     * 从指定来源获取歌词
     * @param candidateIndex 候选歌词索引（仅 NETWORK 来源有效），用于切换不同候选歌词
     *
     * 注意：此方法不自动写入持久化缓存。
     * 持久化写入由 [MainViewModel.switchLyricsSource] 在网络歌词成功获取后显式调用 [saveNetworkLyricsToCache]。
     */
    suspend fun getLyricsFromSource(song: Song, source: LyricsSource, candidateIndex: Int = 0): Lyrics? = withContext(Dispatchers.IO) {
        when (source) {
            LyricsSource.EMBEDDED -> {
                // NAS 歌曲从后端API获取（网络歌曲的"后端"按钮已置灰，不会到达这里）
                val adapter = backendRegistry.getAdapter()
                if (adapter != null) {
                    try {
                        val text = adapter.getLyrics(song.id)
                        if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
                            LrcParser.parse(text, song.id).copy(source = LyricsSource.EMBEDDED)
                        } else null
                    } catch (e: Exception) { null }
                } else null
            }
            LyricsSource.LOCAL_FILE -> getLocalLrcFile(song)
            LyricsSource.CACHED -> {
                // 从持久化缓存读取网络歌词，标记为 CACHED 来源
                val cached = persistentCache.get(song.id)
                if (cached != null) {
                    LrcParser.parse(cached.lrcText, song.id)
                        .copy(source = LyricsSource.CACHED)
                } else null
            }
            LyricsSource.NETWORK -> candidateFetchMutex.withLock {
                // 互斥 + 双检：containsKey→put 非原子，并发调用（如用户连点）会重复发起 fetchLyricsCandidates
                if (!cachedCandidates.containsKey(song.id)) {
                    val results = networkProvider.fetchLyricsCandidates(song.title, song.artist)
                    cachedCandidates[song.id] = results
                    candidateVariantRound[song.id] = 0
                    AppLog.d("LyricsManager", "NETWORK: first fetch, ${results.size} candidates")
                }
                val candidates = cachedCandidates[song.id] ?: emptyList()

                // 2. 当前候选足够 → 直接返回（用 indices 判定，同时挡住负数索引）
                if (candidateIndex in candidates.indices) {
                    val text = candidates[candidateIndex]
                    pendingNetworkLyrics[song.id] = text
                    return@withLock LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                }

                // 3. 候选耗尽 → 换一批重新搜索
                val currentRound = candidateVariantRound[song.id] ?: 0
                val nextRound = currentRound + 1
                if (nextRound < variantSuffixes.size) {
                    val suffix = variantSuffixes[nextRound]
                    val keyword = if (suffix.isBlank()) song.title else "${song.title} $suffix"
                    AppLog.d("LyricsManager", "NETWORK: candidates exhausted, re-search round=$nextRound keyword='$keyword'")
                    val newResults = networkProvider.fetchLyricsCandidates(keyword, song.artist)
                    if (newResults.isNotEmpty()) {
                        val all = candidates + newResults
                        cachedCandidates[song.id] = all
                        candidateVariantRound[song.id] = nextRound
                        // 边界守卫：candidateIndex 来自调用方（用户反复点“在线歌词”会递增），
                        // 可能超出合并后的候选数量，直接 all[candidateIndex] 会抛 IndexOutOfBoundsException。
                        val safeIndex = candidateIndex.coerceIn(0, all.lastIndex)
                        val text = all[safeIndex]
                        pendingNetworkLyrics[song.id] = text
                        return@withLock LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                    }
                }
                // 所有变异轮次用尽 → 返回 null
                AppLog.w("LyricsManager", "NETWORK: all variants exhausted, no more candidates")
                null
            }
        }
    }

    /**
     * 从持久化缓存读取网络歌词（如果存在），返回 [LyricsSource.CACHED] 来源的歌词对象。
     */
    suspend fun getCachedNetworkLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val cached = persistentCache.get(song.id)
        if (cached != null) {
            val lyrics = LrcParser.parse(cached.lrcText, song.id)
                .copy(source = LyricsSource.CACHED)
            AppLog.d("LyricsManager", "getCachedNetworkLyrics: hit for '${song.title}', id=${song.id}")
            return@withContext lyrics
        }
        AppLog.d("LyricsManager", "getCachedNetworkLyrics: miss for '${song.title}', id=${song.id}")
        null
    }

    /**
     * 将网络歌词暂存到 pending 区，歌曲播放完成时调用 [commitPendingNetworkLyrics] 提交到持久化缓存。
     */
    fun savePendingNetworkLyrics(song: Song, lrcText: String) {
        pendingNetworkLyrics[song.id] = lrcText
        AppLog.d("LyricsManager", "savePendingNetworkLyrics: '${song.title}' by ${song.artist}, id=${song.id}")
    }

    /**
     * 提交 pending 中的网络歌词到持久化缓存（歌曲播放完成时调用，对应 MV 的 markCompleted）。
     * 如果该歌曲有暂存的网络歌词，写入持久化缓存并更新 lastPlayedAt。
     */
    fun commitPendingNetworkLyrics(song: Song) {
        val lrcText = pendingNetworkLyrics.remove(song.id) ?: return
        val entry = com.nasmusic.tv.data.model.LyricsCacheEntry(
            songId = song.id,
            songTitle = song.title,
            songArtist = song.artist,
            lrcText = lrcText,
            lastPlayedAt = System.currentTimeMillis()
        )
        // 写 .lrc 文件 + 全量索引 JSON 序列化（最多 2000 条）属阻塞 IO，
        // 调用方在切歌回调（主线程）上，必须切到 IO 作用域执行，避免掉帧/ANR
        backgroundScope.launch { persistentCache.put(entry) }
        AppLog.d("LyricsManager", "commitPendingNetworkLyrics: queued '${song.title}' by ${song.artist}, id=${song.id}")
    }

    /**
     * 丢弃某首歌的 pending 网络歌词（切歌或切换来源时调用）。
     */
    fun discardPendingNetworkLyrics(songId: String) {
        pendingNetworkLyrics.remove(songId)
    }

    /**
     * 清空候选缓存和搜索轮次（切歌时调用）。
     */
    fun clearCachedCandidates() {
        cachedCandidates.clear()
        candidateVariantRound.clear()
    }

    /**
     * 清除所有持久化缓存
     */
    suspend fun clearCache() {
        persistentCache.clear()
    }

    /**
     * 获取缓存条目数
     */
    fun getCacheSize(): Int {
        return persistentCache.size()
    }

    /**
     * 从本地同名 LRC 文件获取歌词
     * 扫描常见位置：Music 目录、下载目录、应用私有目录
     */
    private fun getLocalLrcFile(song: Song): Lyrics? {
        // 常见 LRC 文件命名格式
        val possibleNames = listOf(
            "${song.title}.lrc",
            "${song.artist} - ${song.title}.lrc",
            "${song.artist}_${song.title}.lrc"
        )

        // 扫描的目录列表
        val scanDirs = listOf(
            File("/storage/emulated/0/Music"),
            File("/storage/emulated/0/Download"),
            File(context.getExternalFilesDir(null), "lyrics"),
            File(context.filesDir, "lyrics")
        )

        for (dir in scanDirs) {
            if (!dir.exists()) continue
            for (name in possibleNames) {
                val file = File(dir, name)
                if (file.exists()) {
                    val text = file.readText()
                    if (LrcParser.isValidLrc(text)) {
                        return LrcParser.parse(text, song.id)
                            .copy(source = LyricsSource.LOCAL_FILE)
                    }
                }
            }
        }
        return null
    }
}
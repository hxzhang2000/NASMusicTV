package com.nasmusic.tv.lyrics

import android.content.Context
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsAvailability
import com.nasmusic.tv.data.model.LyricsSource
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 歌词管理器
 * 负责歌词的获取、缓存和匹配
 *
 * 获取优先级：
 * 1. 持久化缓存（仅网络歌词，用户主动切换后写入，按 songId 匹配）
 * 2. 后端API（NAS 歌曲）/ NetworkMusicManager（网络歌曲）
 * 3. 网络匹配（标题+艺术家模糊搜索）
 *
 * 持久化缓存写入时机：用户主动切换到网络歌词来源（[MainViewModel.switchLyricsSource]）
 * 后端歌词不参与持久化缓存。
 */
class LyricsManager(
    private val context: Context,
    private val backendRegistry: BackendRegistry,
    private val networkMusicManager: NetworkMusicManager? = null
) {

    private val networkProvider = LyricsNetworkProvider()
    private val persistentCache = LyricsPersistentCache(context)

    /**
     * 网络歌词暂存区（songId → lrcText）。
     * 用户切到网络歌词时暂存，歌曲播放完成时提交到持久化缓存。
     * 如果用户切歌或切换来源，暂存内容被丢弃（不写入持久化）。
     */
    private val pendingNetworkLyrics = ConcurrentHashMap<String, String>()

    /**
     * 获取歌词 - 按优先级尝试多个来源
     * 1. 持久化缓存（仅网络歌词，按 songId 匹配）
     * 2. 后端API / NetworkMusicManager
     * 3. 网络模糊匹配
     */
    suspend fun getLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        AppLog.d("LyricsManager", "getLyrics: song=${song.title}, artist=${song.artist}, id=${song.id}")

        // 1. Try persistent cache (network lyrics only, by songId)
        val cached = persistentCache.get(song.id)
        if (cached != null) {
            val lyrics = LrcParser.parse(cached.lrcText, song.id)
                .copy(source = LyricsSource.NETWORK)
            AppLog.d("LyricsManager", "getLyrics: found in persistent cache, ${lyrics.lines.size} lines")
            return@withContext lyrics
        }
        AppLog.d("LyricsManager", "getLyrics: no persistent cache")

        // 2. Check availability (backend API → network fallback)
        // 注意：此处不自动写入持久化缓存，仅获取并返回
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
     * 检查歌词来源可用性
     * 同时尝试后端 API 和网络匹配，两个来源互不影响。
     * 不自动写入持久化缓存——持久化仅在用户主动切换网络歌词时触发。
     */
    suspend fun checkAvailability(song: Song): LyricsAvailability = withContext(Dispatchers.IO) {
        AppLog.d("LyricsManager", "checkAvailability: song=${song.title}, artist=${song.artist}, id=${song.id}")

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

        // NAS 歌曲：检查后端API是否有歌词
        val adapter = backendRegistry.getAdapter()
        val backendLyrics = if (adapter != null) {
            try {
                val text = adapter.getLyrics(song.id)
                if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
                    LrcParser.parse(text, song.id).copy(source = LyricsSource.EMBEDDED)
                } else null
                } catch (e: Exception) {
                    AppLog.w("LyricsManager", "backend getLyrics failed: ${e.message}")
                    null
                }
            } else null

            // 同时尝试网络歌词（不跳过）
        val networkLyrics = try {
            val text = networkProvider.fetchLyrics(song.title, song.artist)
            if (text != null) {
                LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
            } else null
        } catch (e: Exception) {
            AppLog.w("LyricsManager", "network fetch failed: ${e.message}")
            null
        }

        val result = LyricsAvailability(backend = backendLyrics, network = networkLyrics)
        AppLog.d("LyricsManager", "checkAvailability: backend=${result.hasBackend}, network=${result.hasNetwork}")
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
                if (song.isNetworkSong && networkMusicManager != null) {
                    // 网络歌曲没有内嵌歌词，走 NetworkMusicManager 获取在线歌词
                    val text = networkMusicManager.resolveLyrics(song)
                    if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
                        LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                    } else null
                } else {
                    // NAS 歌曲从后端API获取
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
            }
            LyricsSource.LOCAL_FILE -> getLocalLrcFile(song)
            LyricsSource.LOCAL_CACHE -> {
                // 从持久化缓存读取网络歌词（与 getLyrics 的缓存路径一致）
                val cached = persistentCache.get(song.id)
                if (cached != null) {
                    LrcParser.parse(cached.lrcText, song.id)
                        .copy(source = LyricsSource.LOCAL_CACHE)
                } else null
            }
            LyricsSource.CACHED -> {
                // 从持久化缓存读取网络歌词，标记为 CACHED 来源
                val cached = persistentCache.get(song.id)
                if (cached != null) {
                    LrcParser.parse(cached.lrcText, song.id)
                        .copy(source = LyricsSource.CACHED)
                } else null
            }
            LyricsSource.NETWORK -> {
                val candidates = networkProvider.fetchLyricsCandidates(song.title, song.artist)
                AppLog.d("LyricsManager", "getLyricsFromSource NETWORK: ${candidates.size} candidates, index=$candidateIndex")
                val idx = candidateIndex.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
                if (candidates.isNotEmpty() && idx < candidates.size) {
                    val text = candidates[idx]
                    // 暂存到 pending 区，歌曲播放完成时才提交到持久化缓存
                    pendingNetworkLyrics[song.id] = text
                    LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                } else null
            }
            else -> null
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
        persistentCache.put(entry)
        AppLog.d("LyricsManager", "commitPendingNetworkLyrics: '${song.title}' by ${song.artist}, id=${song.id}")
    }

    /**
     * 丢弃某首歌的 pending 网络歌词（切歌或切换来源时调用）。
     */
    fun discardPendingNetworkLyrics(songId: String) {
        pendingNetworkLyrics.remove(songId)
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
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 歌词管理器
 * 负责歌词的获取、缓存和匹配
 *
 * 来源优先级：
 * 1. 本地缓存
 * 2. 后端API（NAS 歌曲）/ NetworkMusicManager（网络歌曲）
 * 3. 网络匹配（标题+艺术家模糊搜索）
 */
class LyricsManager(
    private val context: Context,
    private val backendRegistry: BackendRegistry,
    private val networkMusicManager: NetworkMusicManager? = null
) {

    private val cacheDir: File by lazy {
        File(context.cacheDir, "lyrics").apply { mkdirs() }
    }

    private val networkProvider = LyricsNetworkProvider()
    private val cacheMutex = Mutex()

    /**
     * 获取歌词 - 按优先级尝试多个来源
     * 1. 本地缓存
     * 2. 后端API（Jellyfin歌词端点）
     * 3. 网络匹配
     */
    suspend fun getLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        AppLog.d("LyricsManager", "getLyrics: song=${song.title}, artist=${song.artist}, id=${song.id}")

        // 1. Try local cache
        val cached = getCachedLyrics(song)
        if (cached != null) {
            AppLog.d("LyricsManager", "getLyrics: found in cache, ${cached.lines.size} lines")
            return@withContext cached
        }
        AppLog.d("LyricsManager", "getLyrics: no cache")

        // 2. Check availability (backend API → network fallback)
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
     */
    suspend fun checkAvailability(song: Song): LyricsAvailability = withContext(Dispatchers.IO) {
        AppLog.d("LyricsManager", "checkAvailability: song=${song.title}, artist=${song.artist}, id=${song.id}")

        // 网络歌曲：通过 NetworkMusicManager 获取歌词，不走后端 API
        if (song.isNetworkSong && networkMusicManager != null) {
            val networkLyrics = try {
                val text = networkMusicManager.resolveLyrics(song)
                if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
                    cacheLyrics(song, text)
                    LrcParser.parse(text, song.id).copy(source = LyricsSource.EMBEDDED)
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
                        cacheLyrics(song, text)
                        LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                    } else null
                } catch (e: Exception) {
                    AppLog.w("LyricsManager", "network fuzzy fetch failed: ${e.message}")
                    null
                }
            } else null
            val result = LyricsAvailability(backend = networkLyrics, network = fuzzyLyrics)
            AppLog.d("LyricsManager", "checkAvailability(network song): backend=${result.hasBackend}, network=${result.hasNetwork}")
            return@withContext result
        }

        // NAS 歌曲：检查后端API是否有歌词
        val adapter = backendRegistry.getAdapter()
        val backendLyrics = if (adapter != null) {
            try {
                val text = adapter.getLyrics(song.id)
                if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
                    cacheLyrics(song, text)
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
                cacheLyrics(song, text)
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
     */
    suspend fun getLyricsFromSource(song: Song, source: LyricsSource): Lyrics? = withContext(Dispatchers.IO) {
        when (source) {
            LyricsSource.EMBEDDED -> {
                if (song.isNetworkSong && networkMusicManager != null) {
                    // 网络歌曲的"内嵌"歌词走 NetworkMusicManager（实际为在线歌词接口）
                    val text = networkMusicManager.resolveLyrics(song)
                    if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
                        cacheLyrics(song, text)
                        LrcParser.parse(text, song.id).copy(source = LyricsSource.EMBEDDED)
                    } else null
                } else {
                    // NAS 歌曲从后端API获取
                    val adapter = backendRegistry.getAdapter()
                    if (adapter != null) {
                        try {
                            val text = adapter.getLyrics(song.id)
                            if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
                                cacheLyrics(song, text)
                                LrcParser.parse(text, song.id).copy(source = LyricsSource.EMBEDDED)
                            } else null
                        } catch (e: Exception) { null }
                    } else null
                }
            }
            LyricsSource.LOCAL_FILE -> getLocalLrcFile(song)
            LyricsSource.LOCAL_CACHE -> getCachedLyrics(song)
            LyricsSource.NETWORK -> {
                val text = networkProvider.fetchLyrics(song.title, song.artist)
                if (text != null) {
                    val lyrics = LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                    cacheLyrics(song, text)
                    lyrics
                } else null
            }
            else -> null
        }
    }

    /**
     * 从本地缓存获取歌词
     */
    private suspend fun getCachedLyrics(song: Song): Lyrics? {
        cacheMutex.withLock {
            val cacheFile = getCacheFile(song)
            if (cacheFile.exists()) {
                val text = cacheFile.readText()
                if (LrcParser.isValidLrc(text)) {
                    return LrcParser.parse(text, song.id)
                        .copy(source = LyricsSource.LOCAL_CACHE)
                }
            }
            return null
        }
    }

    /**
     * 缓存歌词到本地
     */
    suspend fun cacheLyrics(song: Song, lrcText: String) {
        cacheMutex.withLock {
            try {
                val cacheFile = getCacheFile(song)
                val tempFile = File(cacheDir, "${cacheFile.name}.tmp")
                tempFile.writeText(lrcText)
                tempFile.renameTo(cacheFile)
            } catch (e: Exception) {
                AppLog.w("LyricsManager", "cacheLyrics failed: ${e.message}")
            }
        }
    }

    /**
     * 获取缓存文件路径
     */
    private fun getCacheFile(song: Song): File {
        val fileName = "${song.artist}_${song.title}.lrc"
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return File(cacheDir, fileName)
    }

    /**
     * 清除所有缓存
     */
    suspend fun clearCache() {
        cacheMutex.withLock {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * 获取缓存大小
     */
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0
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

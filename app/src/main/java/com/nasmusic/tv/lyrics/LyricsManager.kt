package com.nasmusic.tv.lyrics

import android.content.Context
import android.util.Log
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsAvailability
import com.nasmusic.tv.data.model.LyricsSource
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 歌词管理器
 * 负责歌词的获取、缓存和匹配
 */
class LyricsManager(private val context: Context) {

    private val cacheDir: File by lazy {
        File(context.cacheDir, "lyrics").apply { mkdirs() }
    }

    private val networkProvider = LyricsNetworkProvider()
    private val mp3Extractor = Mp3MetadataExtractor(context)

    /**
     * 获取歌词 - 按优先级尝试多个来源
     * 1. 本地缓存
     * 2. 后端API（Jellyfin歌词端点）
     * 3. 网络匹配
     */
    suspend fun getLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        Log.d("LyricsManager", "getLyrics: song=${song.title}, artist=${song.artist}, id=${song.id}")

        // 1. Try local cache
        val cached = getCachedLyrics(song)
        if (cached != null) {
            Log.d("LyricsManager", "getLyrics: found in cache, ${cached.lines.size} lines")
            return@withContext cached
        }
        Log.d("LyricsManager", "getLyrics: no cache")

        // 2. Try backend API (Jellyfin lyrics endpoint)
        val adapter = BackendRegistry.getAdapter()
        Log.d("LyricsManager", "getLyrics: adapter=${adapter?.backendType ?: "null"}")
        if (adapter != null) {
            try {
                val lyricsText = adapter.getLyrics(song.id)
                Log.d("LyricsManager", "getLyrics: backend response=${lyricsText?.take(100) ?: "null"}")
                if (!lyricsText.isNullOrBlank() && LrcParser.isValidLrc(lyricsText)) {
                    cacheLyrics(song, lyricsText)
                    val result = LrcParser.parse(lyricsText, song.id)
                        .copy(source = LyricsSource.EMBEDDED)
                    Log.d("LyricsManager", "getLyrics: backend success, ${result.lines.size} lines")
                    return@withContext result
                }
                Log.w("LyricsManager", "getLyrics: backend returned invalid or empty lyrics")
            } catch (e: Exception) {
                Log.e("LyricsManager", "Backend API lyrics failed", e)
            }
        }

        // 3. Try network
        Log.d("LyricsManager", "getLyrics: trying network provider")
        val networkLyrics = networkProvider.fetchLyrics(song.title, song.artist)
        if (networkLyrics != null) {
            val lyrics = LrcParser.parse(networkLyrics, song.id)
                .copy(source = LyricsSource.NETWORK)
            Log.d("LyricsManager", "getLyrics: network success, ${lyrics.lines.size} lines")
            cacheLyrics(song, networkLyrics)
            return@withContext lyrics
        }
        Log.w("LyricsManager", "getLyrics: all sources returned null")

        null
    }

    /**
     * 检查歌词来源可用性
     */
    suspend fun checkAvailability(song: Song): LyricsAvailability = withContext(Dispatchers.IO) {
        Log.d("LyricsManager", "checkAvailability: song=${song.title}, artist=${song.artist}, id=${song.id}")

        // 检查后端API是否有歌词，同时拿到解析后的结果
        val adapter = BackendRegistry.getAdapter()
        Log.d("LyricsManager", "checkAvailability: adapter=${adapter?.backendType ?: "null"}")
        val backendLyrics = if (adapter != null) {
            try {
                val text = adapter.getLyrics(song.id)
                Log.d("LyricsManager", "checkAvailability: backend response=${text?.take(100) ?: "null"}")
                if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
                    Log.d("LyricsManager", "checkAvailability: backend has valid lyrics")
                    LrcParser.parse(text, song.id).copy(source = LyricsSource.EMBEDDED)
                } else {
                    Log.w("LyricsManager", "checkAvailability: backend returned invalid or empty: text=${text?.take(50) ?: "null"}, isValid=${text != null && LrcParser.isValidLrc(text)}")
                    null
                }
            } catch (e: Exception) {
                Log.e("LyricsManager", "checkAvailability: backend exception", e)
                null
            }
        } else {
            Log.w("LyricsManager", "checkAvailability: no adapter available")
            null
        }

        // 后端没有歌词时才尝试网络
        val network = if (backendLyrics == null) {
            Log.d("LyricsManager", "checkAvailability: trying network provider")
            try {
                val text = networkProvider.fetchLyrics(song.title, song.artist)
                if (text != null) {
                    Log.d("LyricsManager", "checkAvailability: network has lyrics, length=${text.length}")
                    LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                } else {
                    Log.w("LyricsManager", "checkAvailability: network returned null")
                    null
                }
            } catch (e: Exception) {
                Log.e("LyricsManager", "checkAvailability: network exception", e)
                null
            }
        } else {
            Log.d("LyricsManager", "checkAvailability: skip network - backend already has lyrics")
            null
        }

        val result = LyricsAvailability(backend = backendLyrics, network = network)
        Log.d("LyricsManager", "checkAvailability: result: backend=${result.hasBackend}, network=${result.hasNetwork}")
        result
    }

    /**
     * 从指定来源获取歌词
     */
    suspend fun getLyricsFromSource(song: Song, source: LyricsSource): Lyrics? = withContext(Dispatchers.IO) {
        when (source) {
            LyricsSource.EMBEDDED -> {
                // 从后端API获取
                val adapter = BackendRegistry.getAdapter()
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
            LyricsSource.LOCAL_FILE -> getLocalLrcFile(song)
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
    private fun getCachedLyrics(song: Song): Lyrics? {
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

    /**
     * 缓存歌词到本地
     */
    fun cacheLyrics(song: Song, lrcText: String) {
        try {
            val cacheFile = getCacheFile(song)
            cacheFile.writeText(lrcText)
        } catch (e: Exception) {
            e.printStackTrace()
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
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
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

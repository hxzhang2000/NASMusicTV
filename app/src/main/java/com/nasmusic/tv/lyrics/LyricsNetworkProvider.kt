package com.nasmusic.tv.lyrics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 网络歌词提供者
 * 从在线歌词源获取歌词
 *
 * v2.2.0 适配：
 * - OkHttpClient 使用守护线程池（isDaemon = true），防止阻止进程退出（与 MetingApiService 一致）
 * - 日志统一使用 AppLog（Release 构建自动抑制调试日志）
 * - JSON 解析统一使用 Gson（与 MetingApiService 一致）
 */
class LyricsNetworkProvider(
    /** 酷狗搜索端点基 URL（默认 https://mobilecdn.kugou.com） */
    private val kugouBaseUrl: String = DEFAULT_KUGOU_BASE_URL,
    /** 酷狗歌词端点基 URL（默认 https://krcs.kugou.com） */
    private val kugouLrcUrl: String = DEFAULT_KUGOU_LRC_URL,
    /** 网易云搜索端点基 URL（默认 https://music.163.com） */
    private val neteaseBaseUrl: String = DEFAULT_NETEASE_BASE_URL
) {

    companion object {
        private const val TAG = "LyricsNetwork"
        const val DEFAULT_KUGOU_BASE_URL = "https://mobilecdn.kugou.com"
        const val DEFAULT_KUGOU_LRC_URL = "https://krcs.kugou.com"
        const val DEFAULT_NETEASE_BASE_URL = "https://music.163.com"
        /**
         * 守护线程池：防止 OkHttp 非守护线程阻止进程退出
         * 静态变量避免每个实例创建新线程池
         */
        private val daemonExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "LyricsNetwork-OkHttp").apply { isDaemon = true }
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher(daemonExecutor))
            .apply {
                // 日志拦截器仅在 debug 构建启用，避免 release 中 URL 写入 logcat
                if (com.nasmusic.tv.BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 从网络获取歌词（单条，返回第一个匹配结果）
     */
    suspend fun fetchLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        AppLog.d(TAG, "fetchLyrics: title=$title, artist=$artist")
        val candidates = fetchLyricsCandidates(title, artist, maxResults = 1)
        val result = candidates.firstOrNull()
        if (result != null) {
            AppLog.d(TAG, "fetchLyrics: success, length=${result.length}")
        } else {
            AppLog.w(TAG, "fetchLyrics: all keywords exhausted, returning null")
        }
        result
    }

    /**
     * 从网络获取歌词（多条候选）
     * 遍历多种关键词组合、多个来源，收集去重后的候选歌词列表。
     * 应用于：用户反复按下"在线歌词"按钮时切换不同候选。
     */
    suspend fun fetchLyricsCandidates(title: String, artist: String, maxResults: Int = 5): List<String> = withContext(Dispatchers.IO) {
        AppLog.d(TAG, "fetchLyricsCandidates: title=$title, artist=$artist, maxResults=$maxResults")

        val keywords = mutableListOf(title)
        if (artist.isNotBlank()) {
            keywords.add("$title $artist")
            keywords.add("$artist $title")
        }

        val seen = mutableSetOf<String>()
        val results = mutableListOf<String>()

        for (keyword in keywords) {
            if (results.size >= maxResults) break
            AppLog.d(TAG, "fetchLyricsCandidates: trying keyword='$keyword'")

            // 尝试酷狗
            for (lyrics in fetchFromKugou(keyword, maxResults)) {
                if (results.size >= maxResults) break
                if (seen.add(lyrics)) {
                    results.add(lyrics)
                    AppLog.d(TAG, "fetchLyricsCandidates: Kugou candidate #${results.size}, len=${lyrics.length}")
                }
            }

            // 尝试网易云
            for (lyrics in fetchFromNetease(keyword, maxResults)) {
                if (results.size >= maxResults) break
                if (seen.add(lyrics)) {
                    results.add(lyrics)
                    AppLog.d(TAG, "fetchLyricsCandidates: Netease candidate #${results.size}, len=${lyrics.length}")
                }
            }
        }

        AppLog.d(TAG, "fetchLyricsCandidates: total=${results.size}")
        results
    }

    /**
     * 从酷狗音乐获取歌词（多条候选）
     * @param maxResults 搜索时取前 N 个结果
     */
    private suspend fun fetchFromKugou(keyword: String, maxResults: Int = 1): List<String> {
        return try {
            val searchUrl = "${kugouBaseUrl.trimEnd('/')}/api/v3/search/song?keyword=" +
                    URLEncoder.encode(keyword, "UTF-8") +
                    "&page=1&pagesize=$maxResults&showtype=14"
            AppLog.d(TAG, "Kugou search: $searchUrl")

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val searchBody = client.newCall(searchRequest).execute().use { searchResponse ->
                if (!searchResponse.isSuccessful) {
                    AppLog.w(TAG, "Kugou search: HTTP ${searchResponse.code}")
                    return@use null
                }
                val body = searchResponse.body?.string()
                if (body == null) { AppLog.w(TAG, "Kugou search: null body"); return@use null }
                AppLog.d(TAG, "Kugou search: status=${searchResponse.code}, body=${body.take(200)}")
                body
            } ?: return emptyList()

            val hashes = parseKugouHashes(searchBody, maxResults)
            if (hashes.isEmpty()) {
                AppLog.w(TAG, "Kugou search: no hashes found")
                return emptyList()
            }
            AppLog.d(TAG, "Kugou search: hashes=${hashes.joinToString()}")

            val results = hashes.mapNotNull { hash ->
                val lyrics = getLyricsByHash(hash)
                if (lyrics != null) {
                    AppLog.d(TAG, "Kugou: hash=$hash success, len=${lyrics.length}")
                } else {
                    AppLog.w(TAG, "Kugou: hash=$hash returned null")
                }
                lyrics
            }
            results
        } catch (e: Exception) {
            AppLog.e(TAG, "Kugou exception", e)
            emptyList()
        }
    }

    /**
     * 根据酷狗 hash 获取歌词内容
     */
    private suspend fun getLyricsByHash(hash: String): String? {
        return try {
            val lyricUrl = "${kugouLrcUrl.trimEnd('/')}/search?ver=1&man=yes&client=mobi&keyword=&duration=&hash=$hash&album_audio_id="
            AppLog.d(TAG, "Kugou lyrics by hash: $hash")

            val lyricRequest = Request.Builder()
                .url(lyricUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val lyricBody = client.newCall(lyricRequest).execute().use { lyricResponse ->
                if (!lyricResponse.isSuccessful) {
                    AppLog.w(TAG, "Kugou lyrics: HTTP ${lyricResponse.code}")
                    return@use null
                }
                val body = lyricResponse.body?.string()
                if (body == null) { AppLog.w(TAG, "Kugou lyrics: null body"); return@use null }
                body
            } ?: return null

            parseKugouLyrics(lyricBody)
        } catch (e: Exception) {
            AppLog.e(TAG, "Kugou getLyricsByHash exception", e)
            null
        }
    }

    /**
     * 从网易云音乐获取歌词（多条候选）
     * @param maxResults 搜索时取前 N 个结果
     */
    private suspend fun fetchFromNetease(keyword: String, maxResults: Int = 1): List<String> {
        return try {
            val searchUrl = "${neteaseBaseUrl.trimEnd('/')}/api/search/get/web?csrf_token=" +
                    "&s=" + URLEncoder.encode(keyword, "UTF-8") +
                    "&type=1&offset=0&total=true&limit=$maxResults"
            AppLog.d(TAG, "Netease search: $searchUrl")

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.163.com")
                .build()

            val searchBody = client.newCall(searchRequest).execute().use { searchResponse ->
                if (!searchResponse.isSuccessful) {
                    AppLog.w(TAG, "Netease search: HTTP ${searchResponse.code}")
                    return@use null
                }
                val body = searchResponse.body?.string()
                if (body == null) { AppLog.w(TAG, "Netease search: null body"); return@use null }
                AppLog.d(TAG, "Netease search: status=${searchResponse.code}, body=${body.take(200)}")
                body
            } ?: return emptyList()

            val songIds = parseNeteaseSongIds(searchBody, maxResults)
            if (songIds.isEmpty()) {
                AppLog.w(TAG, "Netease search: no songIds found")
                return emptyList()
            }
            AppLog.d(TAG, "Netease search: songIds=${songIds.joinToString()}")

            val results = songIds.mapNotNull { songId ->
                val lyrics = getLyricsBySongId(songId)
                if (lyrics != null) {
                    AppLog.d(TAG, "Netease: songId=$songId success, len=${lyrics.length}")
                } else {
                    AppLog.w(TAG, "Netease: songId=$songId returned null")
                }
                lyrics
            }
            results
        } catch (e: Exception) {
            AppLog.e(TAG, "Netease exception", e)
            emptyList()
        }
    }

    /**
     * 根据网易云 songId 获取歌词内容
     */
    private suspend fun getLyricsBySongId(songId: String): String? {
        return try {
            val lyricUrl = "${neteaseBaseUrl.trimEnd('/')}/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
            AppLog.d(TAG, "Netease lyrics by songId: $songId")

            val lyricRequest = Request.Builder()
                .url(lyricUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.163.com")
                .build()

            val lyricBody = client.newCall(lyricRequest).execute().use { lyricResponse ->
                if (!lyricResponse.isSuccessful) {
                    AppLog.w(TAG, "Netease lyrics: HTTP ${lyricResponse.code}")
                    return@use null
                }
                val body = lyricResponse.body?.string()
                if (body == null) { AppLog.w(TAG, "Netease lyrics: null body"); return@use null }
                body
            } ?: return null

            parseNeteaseLyrics(lyricBody)
        } catch (e: Exception) {
            AppLog.e(TAG, "Netease getLyricsBySongId exception", e)
            null
        }
    }

    /**
     * 解析酷狗搜索响应，提取前 N 个歌曲 hash
     * 使用 Gson 解析（与 MetingApiService 一致）
     */
    private fun parseKugouHashes(response: String, maxResults: Int): List<String> {
        return try {
            val json = JsonParser.parseString(response).asJsonObject
            val data = json.getAsJsonObject("data")
            val info = data?.getAsJsonArray("info") ?: return emptyList()
            info.take(maxResults).mapNotNull { (it as? JsonObject)?.get("hash")?.asString }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 解析酷狗歌词搜索响应，获取实际歌词内容
     * 使用 Gson 解析（与 MetingApiService 一致）
     */
    private fun parseKugouLyrics(response: String): String? {
        return try {
            val json = JsonParser.parseString(response).asJsonObject
            val candidates = json.getAsJsonArray("candidates")
            val candidate = candidates?.firstOrNull() as? JsonObject
            val id = candidate?.get("id")?.asString
            val accessKey = candidate?.get("accesskey")?.asString

            if (id != null && accessKey != null) {
                // 获取实际歌词内容
                val lrcUrl = "${kugouLrcUrl.trimEnd('/')}/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
                val lrcRequest = Request.Builder()
                    .url(lrcUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val lrcBody = client.newCall(lrcRequest).execute().use { lrcResponse ->
                    lrcResponse.body?.string() ?: return@use null
                } ?: return null

                val lrcJson = JsonParser.parseString(lrcBody).asJsonObject
                val lrcContent = lrcJson.get("content")?.asString
                if (!lrcContent.isNullOrBlank()) {
                    // 酷狗歌词是 Base64 编码的
                    try {
                        val decoded = android.util.Base64.decode(lrcContent, android.util.Base64.DEFAULT)
                        String(decoded, Charsets.UTF_8)
                    } catch (e: Exception) {
                        lrcContent
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析网易云搜索响应，提取前 N 个歌曲 ID
     * 使用 Gson 解析（与 MetingApiService 一致）
     */
    private fun parseNeteaseSongIds(response: String, maxResults: Int): List<String> {
        return try {
            val json = JsonParser.parseString(response).asJsonObject
            val result = json.getAsJsonObject("result")
            val songs = result?.getAsJsonArray("songs") ?: return emptyList()
            songs.take(maxResults).mapNotNull { (it as? JsonObject)?.get("id")?.asString }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 解析网易云歌词响应，提取歌词文本
     * 使用 Gson 解析（与 MetingApiService 一致）
     */
    private fun parseNeteaseLyrics(response: String): String? {
        return try {
            val json = JsonParser.parseString(response).asJsonObject
            val lrc = json.getAsJsonObject("lrc")
            lrc?.get("lyric")?.asString
        } catch (e: Exception) {
            null
        }
    }
}

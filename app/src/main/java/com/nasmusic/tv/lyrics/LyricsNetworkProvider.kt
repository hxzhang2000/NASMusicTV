package com.nasmusic.tv.lyrics

import com.google.gson.Gson
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
class LyricsNetworkProvider {

    companion object {
        private const val TAG = "LyricsNetwork"
    }

    /**
     * 守护线程池：防止 OkHttp 非守护线程阻止进程退出
     * 与 MetingApiService / JellyfinAdapter / NavidromeAdapter 保持一致
     */
    private val daemonExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "LyricsNetwork-OkHttp").apply { isDaemon = true }
    }

    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .dispatcher(Dispatcher(daemonExecutor))
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    /**
     * 从网络获取歌词
     * 支持多个歌词源，尝试多种关键词组合
     */
    suspend fun fetchLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        AppLog.d(TAG, "fetchLyrics: title=$title, artist=$artist")

        // 尝试多种关键词组合
        val keywords = mutableListOf(title)
        if (artist.isNotBlank()) {
            keywords.add("$title $artist")
            keywords.add("$artist $title")
        }

        for (keyword in keywords) {
            AppLog.d(TAG, "fetchLyrics: trying keyword='$keyword'")
            val result = fetchFromKugou(keyword)
                ?: fetchFromNetease(keyword)
            if (result != null) {
                AppLog.d(TAG, "fetchLyrics: success for keyword='$keyword', length=${result.length}")
                return@withContext result
            }
            AppLog.w(TAG, "fetchLyrics: no result for keyword='$keyword'")
        }
        AppLog.w(TAG, "fetchLyrics: all keywords exhausted, returning null")
        null
    }

    /**
     * 从酷狗音乐获取歌词
     */
    private suspend fun fetchFromKugou(keyword: String): String? {
        return try {
            val searchUrl = "https://mobilecdn.kugou.com/api/v3/search/song?keyword=" +
                    URLEncoder.encode(keyword, "UTF-8") +
                    "&page=1&pagesize=1&showtype=14"
            AppLog.d(TAG, "Kugou search: $searchUrl")

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val searchResponse = client.newCall(searchRequest).execute()
            val searchBody = searchResponse.body?.string()
            if (searchBody == null) { AppLog.w(TAG, "Kugou search: null body"); return null }
            AppLog.d(TAG, "Kugou search: status=${searchResponse.code}, body=${searchBody.take(200)}")

            val hash = parseKugouHash(searchBody)
            if (hash == null) { AppLog.w(TAG, "Kugou search: no hash found"); return null }
            AppLog.d(TAG, "Kugou search: hash=$hash")

            val lyricUrl = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=&hash=$hash&album_audio_id="
            AppLog.d(TAG, "Kugou lyrics: $lyricUrl")

            val lyricRequest = Request.Builder()
                .url(lyricUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val lyricResponse = client.newCall(lyricRequest).execute()
            val lyricBody = lyricResponse.body?.string()
            if (lyricBody == null) { AppLog.w(TAG, "Kugou lyrics: null body"); return null }
            AppLog.d(TAG, "Kugou lyrics: status=${lyricResponse.code}, body=${lyricBody.take(200)}")

            val result = parseKugouLyrics(lyricBody)
            if (result != null) AppLog.d(TAG, "Kugou: success, length=${result.length}")
            else AppLog.w(TAG, "Kugou: parse returned null")
            result
        } catch (e: Exception) {
            AppLog.e(TAG, "Kugou exception", e)
            null
        }
    }

    /**
     * 从网易云音乐获取歌词
     */
    private suspend fun fetchFromNetease(keyword: String): String? {
        return try {
            val searchUrl = "https://music.163.com/api/search/get/web?csrf_token=" +
                    "&s=" + URLEncoder.encode(keyword, "UTF-8") +
                    "&type=1&offset=0&total=true&limit=1"
            AppLog.d(TAG, "Netease search: $searchUrl")

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.163.com")
                .build()

            val searchResponse = client.newCall(searchRequest).execute()
            val searchBody = searchResponse.body?.string()
            if (searchBody == null) { AppLog.w(TAG, "Netease search: null body"); return null }
            AppLog.d(TAG, "Netease search: status=${searchResponse.code}, body=${searchBody.take(200)}")

            val songId = parseNeteaseSongId(searchBody)
            if (songId == null) { AppLog.w(TAG, "Netease search: no songId found"); return null }
            AppLog.d(TAG, "Netease search: songId=$songId")

            val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
            AppLog.d(TAG, "Netease lyrics: $lyricUrl")

            val lyricRequest = Request.Builder()
                .url(lyricUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.163.com")
                .build()

            val lyricResponse = client.newCall(lyricRequest).execute()
            val lyricBody = lyricResponse.body?.string()
            if (lyricBody == null) { AppLog.w(TAG, "Netease lyrics: null body"); return null }
            AppLog.d(TAG, "Netease lyrics: status=${lyricResponse.code}, body=${lyricBody.take(200)}")

            val result = parseNeteaseLyrics(lyricBody)
            if (result != null) AppLog.d(TAG, "Netease: success, length=${result.length}")
            else AppLog.w(TAG, "Netease: parse returned null")
            result
        } catch (e: Exception) {
            AppLog.e(TAG, "Netease exception", e)
            null
        }
    }

    /**
     * 解析酷狗搜索响应，提取歌曲 hash
     * 使用 Gson 解析（与 MetingApiService 一致）
     */
    private fun parseKugouHash(response: String): String? {
        return try {
            val json = JsonParser.parseString(response).asJsonObject
            val data = json.getAsJsonObject("data")
            val info = data?.getAsJsonArray("info")
            val first = info?.firstOrNull() as? JsonObject
            first?.get("hash")?.asString
        } catch (e: Exception) {
            null
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
                val lrcUrl = "https://krcs.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
                val lrcRequest = Request.Builder()
                    .url(lrcUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val lrcResponse = client.newCall(lrcRequest).execute()
                val lrcBody = lrcResponse.body?.string() ?: return null

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
     * 解析网易云搜索响应，提取歌曲 ID
     * 使用 Gson 解析（与 MetingApiService 一致）
     */
    private fun parseNeteaseSongId(response: String): String? {
        return try {
            val json = JsonParser.parseString(response).asJsonObject
            val result = json.getAsJsonObject("result")
            val songs = result?.getAsJsonArray("songs")
            val first = songs?.firstOrNull() as? JsonObject
            first?.get("id")?.asString
        } catch (e: Exception) {
            null
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

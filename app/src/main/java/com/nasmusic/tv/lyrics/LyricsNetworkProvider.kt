package com.nasmusic.tv.lyrics

import android.util.Log
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 网络歌词提供者
 * 从在线歌词源获取歌词
 */
class LyricsNetworkProvider {

    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 从网络获取歌词
     * 支持多个歌词源，尝试多种关键词组合
     */
    suspend fun fetchLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        AppLog.d("LyricsNetwork", "fetchLyrics: title=$title, artist=$artist")

        // 尝试多种关键词组合
        val keywords = mutableListOf(title)
        if (artist.isNotBlank()) {
            keywords.add("$title $artist")
            keywords.add("$artist $title")
        }

        for (keyword in keywords) {
            AppLog.d("LyricsNetwork", "fetchLyrics: trying keyword='$keyword'")
            val result = fetchFromKugou(keyword)
                ?: fetchFromNetease(keyword)
            if (result != null) {
                AppLog.d("LyricsNetwork", "fetchLyrics: success for keyword='$keyword', length=${result.length}")
                return@withContext result
            }
            Log.w("LyricsNetwork", "fetchLyrics: no result for keyword='$keyword'")
        }
        Log.w("LyricsNetwork", "fetchLyrics: all keywords exhausted, returning null")
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
            AppLog.d("LyricsNetwork", "Kugou search: $searchUrl")

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val searchResponse = client.newCall(searchRequest).execute()
            val searchBody = searchResponse.body?.string()
            if (searchBody == null) { Log.w("LyricsNetwork", "Kugou search: null body"); return null }
            AppLog.d("LyricsNetwork", "Kugou search: status=${searchResponse.code}, body=${searchBody.take(200)}")

            val hash = parseKugouHash(searchBody)
            if (hash == null) { Log.w("LyricsNetwork", "Kugou search: no hash found"); return null }
            AppLog.d("LyricsNetwork", "Kugou search: hash=$hash")

            val lyricUrl = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=&hash=$hash&album_audio_id="
            AppLog.d("LyricsNetwork", "Kugou lyrics: $lyricUrl")

            val lyricRequest = Request.Builder()
                .url(lyricUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val lyricResponse = client.newCall(lyricRequest).execute()
            val lyricBody = lyricResponse.body?.string()
            if (lyricBody == null) { Log.w("LyricsNetwork", "Kugou lyrics: null body"); return null }
            AppLog.d("LyricsNetwork", "Kugou lyrics: status=${lyricResponse.code}, body=${lyricBody.take(200)}")

            val result = parseKugouLyrics(lyricBody)
            if (result != null) AppLog.d("LyricsNetwork", "Kugou: success, length=${result.length}")
            else Log.w("LyricsNetwork", "Kugou: parse returned null")
            result
        } catch (e: Exception) {
            Log.e("LyricsNetwork", "Kugou exception", e)
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
            AppLog.d("LyricsNetwork", "Netease search: $searchUrl")

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.163.com")
                .build()

            val searchResponse = client.newCall(searchRequest).execute()
            val searchBody = searchResponse.body?.string()
            if (searchBody == null) { Log.w("LyricsNetwork", "Netease search: null body"); return null }
            AppLog.d("LyricsNetwork", "Netease search: status=${searchResponse.code}, body=${searchBody.take(200)}")

            val songId = parseNeteaseSongId(searchBody)
            if (songId == null) { Log.w("LyricsNetwork", "Netease search: no songId found"); return null }
            AppLog.d("LyricsNetwork", "Netease search: songId=$songId")

            val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
            AppLog.d("LyricsNetwork", "Netease lyrics: $lyricUrl")

            val lyricRequest = Request.Builder()
                .url(lyricUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.163.com")
                .build()

            val lyricResponse = client.newCall(lyricRequest).execute()
            val lyricBody = lyricResponse.body?.string()
            if (lyricBody == null) { Log.w("LyricsNetwork", "Netease lyrics: null body"); return null }
            AppLog.d("LyricsNetwork", "Netease lyrics: status=${lyricResponse.code}, body=${lyricBody.take(200)}")

            val result = parseNeteaseLyrics(lyricBody)
            if (result != null) AppLog.d("LyricsNetwork", "Netease: success, length=${result.length}")
            else Log.w("LyricsNetwork", "Netease: parse returned null")
            result
        } catch (e: Exception) {
            Log.e("LyricsNetwork", "Netease exception", e)
            null
        }
    }

    private fun parseKugouHash(response: String): String? {
        return try {
            val json = JSONObject(response)
            val data = json.optJSONObject("data")
            val info = data?.optJSONArray("info")
            info?.optJSONObject(0)?.optString("hash")
        } catch (e: Exception) {
            null
        }
    }

    private fun parseKugouLyrics(response: String): String? {
        return try {
            val json = JSONObject(response)
            val candidates = json.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val id = candidate?.optString("id")
            val accessKey = candidate?.optString("accesskey")

            if (id != null && accessKey != null) {
                // 获取实际歌词内容
                val lrcUrl = "https://krcs.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
                val lrcRequest = Request.Builder()
                    .url(lrcUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val lrcResponse = client.newCall(lrcRequest).execute()
                val lrcBody = lrcResponse.body?.string() ?: return null

                val lrcJson = JSONObject(lrcBody)
                val lrcContent = lrcJson.optString("content")
                if (lrcContent.isNotBlank()) {
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

    private fun parseNeteaseSongId(response: String): String? {
        return try {
            val json = JSONObject(response)
            val result = json.optJSONObject("result")
            val songs = result?.optJSONArray("songs")
            songs?.optJSONObject(0)?.optString("id")
        } catch (e: Exception) {
            null
        }
    }

    private fun parseNeteaseLyrics(response: String): String? {
        return try {
            val json = JSONObject(response)
            val lrc = json.optJSONObject("lrc")
            lrc?.optString("lyric")
        } catch (e: Exception) {
            null
        }
    }
}

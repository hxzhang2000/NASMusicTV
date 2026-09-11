package com.nasmusic.tv.lyrics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.EncodingUtils
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
        // mobilecdn.kugou.com 的 HTTPS 证书 hostname 不匹配，OkHttp 会拒绝连接，
        // 改用 HTTP（应用已开启 usesCleartextTraffic）
        const val DEFAULT_KUGOU_BASE_URL = "http://mobilecdn.kugou.com"
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

    /** 搜索命中的候选：保留歌名/歌手，用于相关性校验（原实现只取 hash/id，无法校验） */
    private data class SearchHit(val id: String, val name: String?, val artist: String?)

    /** 归一化：小写 + 去除空白与常见中英标点，便于「相互包含」判定 */
    private fun norm(s: String): String = s.lowercase()
        .replace(Regex("[\\s\\p{Punct}·、，。！？；：“”‘’（）《》【】「」…—～]"), "")

    /**
     * 相关性打分：0 = 不相关（拒绝）。
     * 标题必须相互包含（容忍 “歌名 (Live)” / “歌名 - 现场版” 等后缀）；
     * 歌手在提供时命中加分、不命中仅扣分不拒绝（文件 tag 常有 “A/B”“A feat. B” 等噪声）。
     */
    private fun relevanceScore(hit: SearchHit, title: String, artist: String): Int {
        val cn = norm(hit.name ?: "")
        val ct = norm(title)
        if (cn.isEmpty() || ct.isEmpty()) return 0
        if (!cn.contains(ct) && !ct.contains(cn)) return 0
        var score = 2
        if (artist.isNotBlank()) {
            val ca = norm(hit.artist ?: "")
            val at = norm(artist)
            if (ca.isNotEmpty() && at.isNotEmpty() && (ca.contains(at) || at.contains(ca))) score += 2 else score -= 1
        }
        return score
    }

    /**
     * 解码歌词字节：优先 UTF-8；出现替换字符（U+FFFD）说明实为 GBK，改用 GBK 解码。
     * 最后统一走 [EncodingUtils.fixEncoding] 兜底（与原项目 GBK 修复策略一致）。
     */
    private fun decodeLyricsBytes(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        val text = if ('\uFFFD' in utf8) {
            try { String(bytes, java.nio.charset.Charset.forName("GBK")) } catch (e: Exception) { utf8 }
        } else utf8
        return EncodingUtils.fixEncoding(text) ?: text
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
        val candidates = fetchLyricsCandidates(title, artist, maxResults = 3)
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

            // 尝试酷狗（title/artist 用于相关性校验）
            for (lyrics in fetchFromKugou(keyword, title, artist, maxResults)) {
                if (results.size >= maxResults) break
                if (seen.add(lyrics)) {
                    results.add(lyrics)
                    AppLog.d(TAG, "fetchLyricsCandidates: Kugou candidate #${results.size}, len=${lyrics.length}")
                }
            }

            // 尝试网易云（title/artist 用于相关性校验）
            for (lyrics in fetchFromNetease(keyword, title, artist, maxResults)) {
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
    private suspend fun fetchFromKugou(keyword: String, title: String, artist: String, maxResults: Int = 1): List<String> {
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

            val hits = parseKugouHits(searchBody, maxResults)
            if (hits.isEmpty()) {
                AppLog.w(TAG, "Kugou search: no hits found")
                return emptyList()
            }

            // 相关性校验：标题必须相互包含（否则丢弃），歌手命中优先
            val ranked = hits.map { it to relevanceScore(it, title, artist) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
            if (ranked.isEmpty()) {
                AppLog.w(TAG, "Kugou search: no relevant hit for '$title' / '$artist' (dropped ${hits.size})")
                return emptyList()
            }
            AppLog.d(TAG, "Kugou search: ${ranked.size}/${hits.size} relevant, best=${ranked.first().second}")

            val results = ranked.mapNotNull { (hit, _) ->
                val lyrics = getLyricsByHash(hit.id)
                if (lyrics != null) {
                    AppLog.d(TAG, "Kugou: hash=${hit.id} success, len=${lyrics.length}")
                } else {
                    AppLog.w(TAG, "Kugou: hash=${hit.id} returned null")
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
    private suspend fun fetchFromNetease(keyword: String, title: String, artist: String, maxResults: Int = 1): List<String> {
        return try {
            // 网易云 /api/search/get/web (GET) 已废弃，返回 405；
            // 改用 POST /api/search/get
            val searchUrl = "${neteaseBaseUrl.trimEnd('/')}/api/search/get"
            val formBody = okhttp3.FormBody.Builder()
                .add("s", keyword)
                .add("type", "1")
                .add("offset", "0")
                .add("total", "true")
                .add("limit", maxResults.toString())
                .build()
            AppLog.d(TAG, "Netease search(POST): $searchUrl, s=$keyword")

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .post(formBody)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.163.com")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val searchBody = client.newCall(searchRequest).execute().use { searchResponse ->
                if (!searchResponse.isSuccessful) {
                    AppLog.w(TAG, "Netease search: HTTP ${searchResponse.code}")
                    return@use null
                }
                val body = searchResponse.body?.string()
                if (body == null) { AppLog.w(TAG, "Netease search: null body"); return@use null }
                AppLog.d(TAG, "Netease search(POST): status=${searchResponse.code}, body=${body.take(200)}")
                body
            } ?: return emptyList()

            val hits = parseNeteaseHits(searchBody, maxResults)
            if (hits.isEmpty()) {
                AppLog.w(TAG, "Netease search: no hits found")
                return emptyList()
            }

            // 相关性校验：标题必须相互包含（否则丢弃），歌手命中优先
            val ranked = hits.map { it to relevanceScore(it, title, artist) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
            if (ranked.isEmpty()) {
                AppLog.w(TAG, "Netease search: no relevant hit for '$title' / '$artist' (dropped ${hits.size})")
                return emptyList()
            }
            AppLog.d(TAG, "Netease search: ${ranked.size}/${hits.size} relevant, best=${ranked.first().second}")

            val results = ranked.mapNotNull { (hit, _) ->
                val lyrics = getLyricsBySongId(hit.id)
                if (lyrics != null) {
                    AppLog.d(TAG, "Netease: songId=${hit.id} success, len=${lyrics.length}")
                } else {
                    AppLog.w(TAG, "Netease: songId=${hit.id} returned null")
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
    private fun parseKugouHits(response: String, maxResults: Int): List<SearchHit> {
        return try {
            val json = JsonParser.parseString(response).asJsonObject
            val data = json.getAsJsonObject("data")
            val info = data?.getAsJsonArray("info") ?: return emptyList()
            info.take(maxResults).mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val hash = obj.get("hash")?.asString ?: return@mapNotNull null
                SearchHit(
                    id = hash,
                    // 酷狗 v3 搜索：songname/singername；部分接口返回 filename（"歌手 - 歌名"）
                    name = obj.get("songname")?.asString ?: obj.get("filename")?.asString,
                    artist = obj.get("singername")?.asString ?: obj.get("author_name")?.asString
                )
            }
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
                    // 酷狗歌词是 Base64 编码的；原始 charset 可能为 UTF-8 或 GBK，
                    // 不能一律按 UTF-8 解析（否则 GBK 歌词整段乱码）
                    try {
                        val decoded = android.util.Base64.decode(lrcContent, android.util.Base64.DEFAULT)
                        decodeLyricsBytes(decoded)
                    } catch (e: Exception) {
                        EncodingUtils.fixEncoding(lrcContent) ?: lrcContent
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
    private fun parseNeteaseHits(response: String, maxResults: Int): List<SearchHit> {
        return try {
            val json = JsonParser.parseString(response).asJsonObject
            val result = json.getAsJsonObject("result")
            val songs = result?.getAsJsonArray("songs") ?: return emptyList()
            songs.take(maxResults).mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val name = obj.get("name")?.asString
                // 返回字段随接口版本变化：artists[]（v1）或 ar[]（旧版）
                val artists = obj.getAsJsonArray("artists") ?: obj.getAsJsonArray("ar")
                val artist = artists
                    ?.mapNotNull { (it as? JsonObject)?.get("name")?.asString }
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString("/")
                SearchHit(id = id, name = name, artist = artist)
            }
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
            EncodingUtils.fixEncoding(lrc?.get("lyric")?.asString)
        } catch (e: Exception) {
            null
        }
    }
}

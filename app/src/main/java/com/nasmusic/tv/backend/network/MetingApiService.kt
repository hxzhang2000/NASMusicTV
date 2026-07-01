package com.nasmusic.tv.backend.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.EncodingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Meting-API 网络音乐服务实现
 *
 * Meting-API 是聚合多个音乐平台（网易云/QQ/酷狗等）的搜索接口。
 * 端点示例：{BASE}?server=netease&type=search&id=keyword
 *
 * 响应字段说明（实际 API 返回字段名）：
 * - title：歌曲名
 * - author：艺术家
 * - pic：封面端点 URL（302 重定向到真实图片），Coil 可自动跟随，可直接用作 coverUrl
 * - url：播放端点 URL（302 重定向到真实 mp3），需通过 resolvePlayUrl() 解析
 * - lrc：歌词端点 URL，请求后返回 LRC 文本
 *
 * 注意：响应中没有独立的 id 字段，需从 url 字段中提取 id 查询参数。
 *
 * v2.2.0 适配：
 * - OkHttpClient 使用守护线程池，防止阻止进程退出
 * - 日志使用 AppLog
 * - JSON 解析使用 Gson
 */
class MetingApiService(
    /**
     * Meting-API 基础端点，可在设置页面配置。
     * 默认使用公共服务 https://meting.mikus.ink/api
     */
    private val baseUrlProvider: () -> String
) : NetworkMusicService {

    override val sourceId = "meting"

    /**
     * 守护线程池：防止 OkHttp 非守护线程阻止进程退出
     * 与 JellyfinAdapter / NavidromeAdapter 保持一致
     */
    private val daemonExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "Meting-OkHttp").apply { isDaemon = true }
    }

    /**
     * 信任所有证书的 TrustManager
     *
     * TV 盒子系统版本较低时，可能缺少 Let's Encrypt 等新 CA 的根证书，
     * 导致 SSLHandshakeException。Meting-API 为公开搜索服务，不涉及敏感数据，
     * 此处放宽证书校验以保证可用性。
     */
    private val trustAllManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** 信任所有主机名 */
    private val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher(daemonExecutor))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .applyTrustAllSsl()
            .build()
    }

    /** 不跟随重定向，用于获取 302 Location */
    private val noRedirectClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher(daemonExecutor))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .applyTrustAllSsl()
            .build()
    }

    /** 将 OkHttpClient.Builder 配置为信任所有 SSL 证书 */
    private fun OkHttpClient.Builder.applyTrustAllSsl(): OkHttpClient.Builder {
        try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAllManager), java.security.SecureRandom())
            this.sslSocketFactory(sslContext.socketFactory, trustAllManager)
            this.hostnameVerifier(trustAllHostnameVerifier)
        } catch (e: Exception) {
            AppLog.e(DIAG, "applyTrustAllSsl failed: ${e.message}", e)
        }
        return this
    }

    private val gson = Gson()

    /**
     * 当前基础端点（每次请求动态读取，支持运行时切换）
     * 清理可能混入的非法字符（反引号、引号、首尾空格等）
     */
    private val baseUrl: String
        get() = baseUrlProvider()
            .trim()
            .trim('`', '\'', '"')
            .trim()
            .trimEnd('/')

    companion object {
        private const val TAG = "MetingApiService"
        // 诊断日志 TAG（logcat 过滤关键字：MetingDiag）
        private const val DIAG = "MetingDiag"
        private const val DEFAULT_SERVER = "netease"
        // 默认公共 Meting-API 端点
        const val DEFAULT_BASE_URL = "https://meting.api.redcha.cn/api"

        /**
         * 预设公共 Meting-API 端点列表（供设置页选择）
         *
         * 仅包含已验证支持 type=search 的端点。不同端点返回字段名可能不同：
         * - Mikus / Redcha：返回 title / author
         * - Qijieya：返回 name / artist
         * parseSongs() 已兼容两种字段格式。
         *
         * 这些端点由社区维护，可能随时间失效。用户也可自建端点或输入其他公共端点。
         */
        val PRESET_ENDPOINTS: List<Pair<String, String>> = listOf(
            "Mikus（默认）" to "https://meting.mikus.ink/api",
            "Redcha" to "https://meting.api.redcha.cn/api",
            "Qijieya" to "https://api.qijieya.cn/meting"
        )
    }

    /**
     * 搜索歌曲
     *
     * 端点自动 fallback 策略：
     * 1. 优先使用用户在设置中选中的端点（baseUrl）
     * 2. 如果当前端点搜索失败（异常或无结果），自动尝试其他预设端点
     * 3. 任一端点返回非空结果即返回，用户无感切换
     *
     * 注意：Meting-API 搜索响应中的 url / pic / lrc 字段是 API 端点，不是直联 URL。
     * - url → 需要再请求获取 302 Location（real mp3 URL），由 resolvePlayUrl() 处理
     * - pic → Coil 自动处理 302，可以直接用端点 URL 作为 coverUrl
     * - lrc → 实际就是歌词文本端点，由 resolveLyrics() 请求获取
     */
    override suspend fun search(keyword: String, limit: Int): List<Song> = withContext(Dispatchers.IO) {
        AppLog.i(DIAG, "=== search start === keyword='$keyword'")
        if (keyword.isBlank()) {
            AppLog.i(DIAG, "search: keyword blank, return empty")
            return@withContext emptyList()
        }

        // 构造端点尝试顺序：当前选中的端点优先，然后是其他预设端点
        val currentBase = baseUrl
        val endpoints = buildEndpointFallbackOrder(currentBase)
        AppLog.i(DIAG, "search: endpoint fallback order=${endpoints.map { it.take(40) }}")

        for ((index, endpoint) in endpoints.withIndex()) {
            AppLog.i(DIAG, "search: trying endpoint[$index]='${endpoint.take(60)}'")
            val songs = searchWithEndpoint(keyword, endpoint)
            if (songs.isNotEmpty()) {
                AppLog.i(DIAG, "search: endpoint[$index] returned ${songs.size} songs, success")
                AppLog.i(DIAG, "=== search end ===")
                return@withContext songs
            }
            AppLog.w(DIAG, "search: endpoint[$index] returned empty, trying next")
        }

        AppLog.w(DIAG, "search: all endpoints failed or empty")
        AppLog.i(DIAG, "=== search end ===")
        emptyList()
    }

    /**
     * 构造端点 fallback 顺序
     *
     * 优先级：
     * 1. 当前用户选中的端点（baseUrl）
     * 2. 其他预设端点（按 PRESET_ENDPOINTS 顺序，排除当前端点）
     *
     * 如果当前端点是自定义的（不在预设列表中），也会先尝试它，然后 fallback 到预设端点。
     */
    private fun buildEndpointFallbackOrder(currentBase: String): List<String> {
        val normalized = currentBase.trim().trimEnd('/')
        val result = mutableListOf(normalized)
        // 添加其他预设端点（去重）
        PRESET_ENDPOINTS.forEach { (_, url) ->
            val presetNormalized = url.trim().trimEnd('/')
            if (presetNormalized != normalized) {
                result.add(presetNormalized)
            }
        }
        return result
    }

    /**
     * 使用指定端点搜索歌曲（单次请求，不 fallback）
     *
     * 供 search() 在多端点 fallback 流程中调用。
     */
    private fun searchWithEndpoint(keyword: String, endpoint: String): List<Song> {
        return try {
            val url = "$endpoint?server=$DEFAULT_SERVER&type=search&id=${URLEncoder.encode(keyword, "UTF-8")}"
            AppLog.i(DIAG, "searchWithEndpoint: url='$url'")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                AppLog.i(DIAG, "searchWithEndpoint: response code=${response.code} bodyLen=${body?.length ?: 0}")
                if (!response.isSuccessful || body.isNullOrBlank()) {
                    AppLog.w(DIAG, "searchWithEndpoint: failed code=${response.code} bodyEmpty=${body.isNullOrBlank()}")
                    return@use emptyList()
                }
                parseSongs(body)
            }
        } catch (e: Exception) {
            AppLog.w(DIAG, "searchWithEndpoint error: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    /**
     * 解析播放链接
     *
     * 端点自动 fallback 策略：
     * 1. 优先使用用户在设置中选中的端点（baseUrl）
     * 2. 如果当前端点失败（异常或空结果），自动尝试其他预设端点
     * 3. 任一端点返回非空播放 URL 即返回
     *
     * Meting-API 返回 302 重定向，需要获取 Location header
     */
    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        val netId = song.networkId ?: return@withContext song.streamUrl
        val endpoints = buildEndpointFallbackOrder(baseUrl)
        for (endpoint in endpoints) {
            try {
                val url = "$endpoint?server=$DEFAULT_SERVER&type=url&id=$netId"
                val request = Request.Builder().url(url).build()
                var playUrl: String? = null
                noRedirectClient.newCall(request).execute().use { response ->
                    playUrl = when (response.code) {
                        302 -> response.header("Location")
                        200 -> response.body?.string()?.let { extractUrlFromJson(it) }
                        else -> null
                    }
                }
                if (!playUrl.isNullOrBlank()) {
                    AppLog.d(TAG, "resolvePlayUrl: resolved via '$endpoint' for netId=$netId")
                    return@withContext playUrl
                }
                AppLog.w(TAG, "resolvePlayUrl: empty from '$endpoint' for netId=$netId")
            } catch (e: Exception) {
                AppLog.w(TAG, "resolvePlayUrl: endpoint '$endpoint' failed: ${e.message}")
            }
        }
        AppLog.w(TAG, "resolvePlayUrl: all endpoints failed for netId=$netId")
        null
    }

    /**
     * 获取歌词
     * Meting-API 的 lrc 端点直接返回 LRC 文本
     */
    override suspend fun resolveLyrics(song: Song): String? = withContext(Dispatchers.IO) {
        val netId = song.networkId ?: return@withContext null
        try {
            val url = "$baseUrl?server=$DEFAULT_SERVER&type=lrc&id=$netId"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (text.isNullOrBlank()) null else text
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "resolveLyrics error: ${e.message}", e)
            null
        }
    }

    /**
     * 封面 URL：搜索结果中的 pic 字段已是端点 URL，Coil 可自动跟随 302，
     * 无需额外解析，返回 null 让调用方使用 song.coverUrl。
     */
    override suspend fun resolveCoverUrl(song: Song): String? = null

    /**
     * 按标题+艺术家搜索第一首匹配歌曲的封面 URL。
     * 用于 NAS 歌曲切换到网络歌词时，联动获取网络封面图加入轮播。
     */
    override suspend fun searchCoverUrl(title: String, artist: String): String? {
        val keyword = if (artist.isNotBlank()) "$title $artist" else title
        val items = search(keyword)
        return items.firstOrNull()?.coverUrl
    }

    /**
     * 获取歌单中的所有歌曲。
     *
     * 端点自动 fallback 策略：
     * 1. 优先使用用户在设置中选中的端点（baseUrl）
     * 2. 如果当前端点失败（异常或空结果），自动尝试其他预设端点
     * 3. 任一端点返回非空歌曲列表即返回
     *
     * Meting-API 的 type=playlist 端点直接返回歌曲 JSON 数组，
     * 格式与 type=search 完全一致，可直接复用 parseSongs() 解析。
     *
     * @param playlistId 网易云歌单 ID
     * @return 歌单中的歌曲列表；空列表表示无结果或获取失败
     */
    override suspend fun getPlaylist(playlistId: String): List<Song> = withContext(Dispatchers.IO) {
        val endpoints = buildEndpointFallbackOrder(baseUrl)
        for (endpoint in endpoints) {
            try {
                val url = "$endpoint?server=$DEFAULT_SERVER&type=playlist&id=$playlistId"
                AppLog.i(DIAG, "getPlaylist: trying endpoint='$endpoint' url='$url'")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                var songs: List<Song>? = null
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    AppLog.i(DIAG, "getPlaylist: code=${response.code} bodyLen=${body?.length ?: 0}")
                    if (response.isSuccessful && !body.isNullOrBlank()) {
                        songs = parseSongs(body)
                    }
                }
                if (!songs.isNullOrEmpty()) {
                    AppLog.i(DIAG, "getPlaylist: loaded ${songs.size} songs via '$endpoint'")
                    return@withContext songs
                }
                AppLog.w(DIAG, "getPlaylist: empty from '$endpoint'")
            } catch (e: Exception) {
                AppLog.w(DIAG, "getPlaylist: endpoint '$endpoint' failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        AppLog.w(DIAG, "getPlaylist: all endpoints failed for playlistId=$playlistId")
        emptyList()
    }

    /**
     * 解析 Meting-API 搜索响应
     *
     * 响应格式：JSON 数组，每个元素字段：
     * - title：歌曲名
     * - author：艺术家
     * - album：专辑名
     * - pic：封面端点 URL
     * - url：播放端点 URL（含 id 参数，需提取）
     * - lrc：歌词端点 URL
     */
    private fun parseSongs(body: String): List<Song> {
        return try {
            val type = object : TypeToken<List<JsonObject>>() {}.type
            val items: List<JsonObject> = gson.fromJson(body, type) ?: run {
                AppLog.w(DIAG, "parseSongs: gson returned null, body is not a JSON array")
                return emptyList()
            }
            AppLog.i(DIAG, "parseSongs: json array size=${items.size}")
            if (items.isEmpty()) {
                AppLog.w(DIAG, "parseSongs: array empty")
                return emptyList()
            }
            // 打印第一个元素的所有 key，便于核对字段名
            items.firstOrNull()?.keySet()?.let { keys ->
                AppLog.i(DIAG, "parseSongs: first item keys=$keys")
            }
            val result = items.mapIndexedNotNull { idx, item ->
                try {
                    // 兼容不同端点的字段名：
                    // - Mikus / Redcha 返回 title / author
                    // - Qijieya 返回 name / artist
                    val title = item.get("title")?.asString
                        ?: item.get("name")?.asString
                    val author = item.get("author")?.asString
                        ?: item.get("artist")?.asString
                    val pic = item.get("pic")?.asString
                    val urlField = item.get("url")?.asString
                    val albumField = item.get("album")?.asString
                        ?: item.get("al")?.asString
                    // 编码修复：部分端点可能返回 GBK 被当作 Latin-1 解码的乱码
                    val fixedTitle = EncodingUtils.fixEncoding(title)
                    val fixedAuthor = EncodingUtils.fixEncoding(author)
                    val fixedAlbum = EncodingUtils.fixEncoding(albumField)
                    AppLog.i(DIAG, "parseSongs[$idx]: title=$fixedTitle author=$fixedAuthor album=$fixedAlbum pic=${pic?.take(60)} url=${urlField?.take(80)}")
                    if (fixedTitle == null) {
                        AppLog.w(DIAG, "parseSongs[$idx]: title null, skip")
                        return@mapIndexedNotNull null
                    }
                    val netId = extractIdFromUrl(urlField)
                    if (netId == null) {
                        AppLog.w(DIAG, "parseSongs[$idx]: extractIdFromUrl null for url=$urlField, skip")
                        return@mapIndexedNotNull null
                    }
                    AppLog.i(DIAG, "parseSongs[$idx]: extracted netId=$netId")
                    Song(
                        id = "ntwk_${sourceId}_$netId",
                        title = fixedTitle,
                        artist = fixedAuthor.orEmpty(),
                        album = fixedAlbum.orEmpty(),
                        coverUrl = pic,
                        isNetworkSong = true,
                        networkSource = sourceId,
                        networkId = netId
                    )
                } catch (e: Exception) {
                    AppLog.w(DIAG, "parseSongs[$idx] error: ${e.message}")
                    null
                }
            }
            AppLog.i(DIAG, "parseSongs: result size=${result.size}")
            result
        } catch (e: Exception) {
            AppLog.e(DIAG, "parseSongs error: ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 从 Meting-API 端点 URL 中提取 id 查询参数
     *
     * 示例输入：https://meting.mikus.ink/api?server=netease&type=url&id=2652820720
     * 输出：2652820720
     */
    private fun extractIdFromUrl(url: String?): String? {
        AppLog.i(DIAG, "extractIdFromUrl: input=$url")
        if (url.isNullOrBlank()) {
            AppLog.w(DIAG, "extractIdFromUrl: url null/blank")
            return null
        }
        return try {
            val uri = java.net.URI(url)
            val query = uri.rawQuery
            AppLog.i(DIAG, "extractIdFromUrl: rawQuery=$query")
            if (query == null) {
                AppLog.w(DIAG, "extractIdFromUrl: rawQuery null")
                return null
            }
            query.split("&").forEach { param ->
                val idx = param.indexOf("=")
                if (idx > 0 && param.substring(0, idx) == "id") {
                    val id = param.substring(idx + 1)
                    AppLog.i(DIAG, "extractIdFromUrl: found id=$id")
                    return id
                }
            }
            AppLog.w(DIAG, "extractIdFromUrl: no id param found in query")
            null
        } catch (e: Exception) {
            AppLog.w(DIAG, "extractIdFromUrl: URI parse failed (${e.message}), try regex fallback")
            val regex = Regex("[?&]id=([^&]+)")
            val matched = regex.find(url)?.groupValues?.getOrNull(1)
            AppLog.i(DIAG, "extractIdFromUrl: regex result=$matched")
            matched
        }
    }

    /**
     * 从 JSON 响应中提取 URL（部分接口可能返回 {"url": "..."} 格式）
     */
    private fun extractUrlFromJson(body: String): String? {
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
            json?.get("url")?.asString
        } catch (e: Exception) {
            null
        }
    }
}

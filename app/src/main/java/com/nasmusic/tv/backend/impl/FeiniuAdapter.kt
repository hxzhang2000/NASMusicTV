package com.nasmusic.tv.backend.impl

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongTechnicalInfo
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.EncodingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 飞牛音乐后端适配器
 *
 * 对接飞牛私有云（fnOS）内置音乐服务的自定义 REST API。
 * API 前缀 `/music/api/v1`，认证方式为 Cookie（`music-token=<token>`）。
 * 登录密码需 SHA256 哈希后传输，登录请求需带 `deviceId`。
 *
 * ⚠️ API 端点来自 FeiNiuMusic 项目（github.com/kuilei0926/FeiNiuMusic）逆向工程，
 *    非官方文档但来自实际可用客户端，可信度较高。
 *    标注 [REVERSE_ENGINEERED] 的端点可能随 fnOS 版本变化。
 *    标注 [UNCONFIRMED] 的端点需部署 fnOS 后抓包验证。
 *
 * Song ID 格式：`feiniu_${原始ID}`，跨会话稳定。
 * 流媒体：返回 HLS 相对路径，需拼接 baseUrl + Cookie 注入 ExoPlayer。
 */
class FeiniuAdapter : BackendAdapter {

    companion object {
        private const val TAG = "FeiniuAdapter"
        private const val PAGE_SIZE = 500
        private const val SONG_ID_PREFIX = "feiniu_"
        private const val API_PREFIX = "/music/api/v1"
    }

    override val backendType: String = ServerConfig.TYPE_FEINIU
    override var serverName: String = "飞牛音乐"
    override var apiVersion: String = "Unknown"

    private var baseUrl: String = ""
    private var musicToken: String = ""  // Cookie 值
    private var deviceId: String = ""    // 设备 ID（自动生成）

    private val gson = Gson()

    /** Cookie 存储：自动维护 music-token Cookie */
    private val cookieStore = mutableMapOf<String, List<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }
    }

    private val daemonExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "Feiniu-OkHttp").apply { isDaemon = true }
    }
    private val trustAllManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    private val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher(daemonExecutor))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cookieJar(cookieJar)
            .applyTrustAllSsl()
            .build()
    }

    /** 播放流时注入的 HTTP 头（Cookie 认证） */
    override val streamHeaders: Map<String, String>
        get() = if (musicToken.isNotBlank()) mapOf("Cookie" to "music-token=$musicToken") else emptyMap()

    // ==================== 认证 ====================

    /**
     * REVERSE_ENGINEERED: POST /music/api/v1/user/password-login
     * Body: { username, password: sha256(原始密码), deviceId }
     * Response: { token: "music-token-xxx", ... }
     */
    override suspend fun initialize(
        baseUrl: String,
        apiToken: String,
        username: String,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        this@FeiniuAdapter.baseUrl = baseUrl.removeSuffix("/")

        // deviceId 自动生成（首次连接）
        deviceId = UUID.randomUUID().toString()

        // 如果已有 token，直接使用
        if (apiToken.isNotBlank()) {
            this@FeiniuAdapter.musicToken = apiToken
            // 验证 token 是否有效
            if (verifyToken()) {
                fetchApiVersion()
                return@withContext true
            }
        }

        // 用户名+密码登录
        if (username.isNotBlank()) {
            val result = login(username, password)
            if (result) {
                fetchApiVersion()
                return@withContext true
            }
        }
        false
    }

    /**
     * REVERSE_ENGINEERED: POST /music/api/v1/user/password-login
     * 密码 SHA256 哈希后传输
     */
    private fun login(username: String, password: String): Boolean {
        return try {
            val hashedPassword = sha256(password)
            val jsonBody = gson.toJson(mapOf(
                "username" to username,
                "password" to hashedPassword,
                "deviceId" to deviceId
            ))
            val body = okhttp3.RequestBody.create(
                "application/json".toMediaType(),
                jsonBody
            )
            val request = Request.Builder()
                .url("$baseUrl$API_PREFIX/user/password-login")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLog.w(TAG, "login failed: ${response.code}")
                return false
            }
            val responseBody = response.body?.string() ?: return false
            val json = JsonParser.parseString(responseBody).asJsonObject
            val token = json.get("token")?.asString
                ?: json.getAsJsonObject("data")?.get("token")?.asString
                ?: return false
            musicToken = token
            // 手动注入 Cookie（CookieJar 也会自动管理，但确保一致性）
            val cookieUrl = baseUrl.toHttpUrl() ?: return false
            val cookie = Cookie.Builder()
                .name("music-token")
                .value(token)
                .domain(cookieUrl.host)
                .build()
            cookieStore[cookieUrl.host] = listOf(cookie)
            AppLog.d(TAG, "login success, token=${token.take(20)}...")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "login failed", e)
            false
        }
    }

    /** REVERSE_ENGINEERED: GET /music/api/v1/user/info 验证 token */
    private fun verifyToken(): Boolean {
        return try {
            val json = executeGet("$baseUrl$API_PREFIX/user/info") ?: return false
            val code = json.get("code")?.asInt ?: 0
            code == 0
        } catch (e: Exception) {
            false
        }
    }

    /** UNCONFIRMED: 版本号获取端点待确认 */
    private fun fetchApiVersion() {
        apiVersion = "飞牛音乐 API"
        // ⚠️ UNCONFIRMED: fnOS 系统信息或音乐服务 /music/api/v1/version
        // 待部署 fnOS 后抓包确认具体版本号获取方式
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 尝试访问 API 根路径检查服务是否可用
            val request = Request.Builder()
                .url("$baseUrl$API_PREFIX/track/list?limit=1")
                .build()
            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            AppLog.w(TAG, "testConnection failed", e)
            false
        }
    }

    override suspend fun logout() {
        // 清除 Cookie
        cookieStore.clear()
        musicToken = ""
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // ==================== 专辑 ====================

    /**
     * REVERSE_ENGINEERED: GET /music/api/v1/track/album-detail/list
     * ⚠️ 注意：此端点是"专辑内曲目"，专辑列表可能需要不同端点
     * UNCONFIRMED: 专辑列表端点
     */
    override suspend fun getAlbums(): List<Album> = withContext(Dispatchers.IO) {
        try {
            // ⚠️ UNCONFIRMED: 飞牛可能有独立的专辑列表端点，待抓包确认
            // 暂用 track/album-detail/list 作为替代
            val json = executeGet("$baseUrl$API_PREFIX/track/album-detail/list?page=1&limit=$PAGE_SIZE") ?: return@withContext emptyList()
            val data = extractDataArray(json) ?: return@withContext emptyList()
            data.mapNotNull { parseAlbum(it.asJsonObject) }
        } catch (e: Exception) {
            AppLog.e(TAG, "getAlbums failed", e)
            emptyList()
        }
    }

    /** REVERSE_ENGINEERED: GET /music/api/v1/track/album-detail/list?albumId={id} */
    override suspend fun getAlbumSongs(albumId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val rawId = stripPrefix(albumId)
            val json = executeGet("$baseUrl$API_PREFIX/track/album-detail/list?albumId=$rawId&limit=$PAGE_SIZE") ?: return@withContext emptyList()
            val data = extractDataArray(json) ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, albumId) }
        } catch (e: Exception) {
            AppLog.e(TAG, "getAlbumSongs failed", e)
            emptyList()
        }
    }

    // ==================== 歌手 ====================

    /**
     * REVERSE_ENGINEERED: GET /music/api/v1/track/artist-detail/list
     * ⚠️ 注意：此端点是"歌手内曲目"，歌手列表可能需要不同端点
     * UNCONFIRMED: 歌手列表端点
     */
    override suspend fun getArtists(): List<Artist> = withContext(Dispatchers.IO) {
        try {
            // ⚠️ UNCONFIRMED: 歌手列表端点待确认
            val json = executeGet("$baseUrl$API_PREFIX/track/artist-detail/list?page=1&limit=$PAGE_SIZE") ?: return@withContext emptyList()
            val data = extractDataArray(json) ?: return@withContext emptyList()
            // 去重：从曲目列表中提取歌手
            data.mapNotNull { parseArtistFromTrack(it.asJsonObject) }.distinctBy { it.id }
        } catch (e: Exception) {
            AppLog.e(TAG, "getArtists failed", e)
            emptyList()
        }
    }

    /** REVERSE_ENGINEERED: GET /music/api/v1/track/artist-detail/list?artistId={id} */
    override suspend fun getArtistSongs(artistId: String, artistName: String?): List<Song> = withContext(Dispatchers.IO) {
        try {
            val rawId = stripPrefix(artistId)
            val url = if (artistName != null) {
                "$baseUrl$API_PREFIX/track/artist-detail/list?artistId=$rawId&name=${java.net.URLEncoder.encode(artistName, "UTF-8")}&limit=$PAGE_SIZE"
            } else {
                "$baseUrl$API_PREFIX/track/artist-detail/list?artistId=$rawId&limit=$PAGE_SIZE"
            }
            val json = executeGet(url) ?: return@withContext emptyList()
            val data = extractDataArray(json) ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            AppLog.e(TAG, "getArtistSongs failed", e)
            emptyList()
        }
    }

    // ==================== 歌曲 ====================

    /** REVERSE_ENGINEERED: GET /music/api/v1/track/list?page=1&limit=500 */
    override suspend fun getSongs(limit: Int, offset: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val page = (offset / limit) + 1
            val json = executeGet("$baseUrl$API_PREFIX/track/list?page=$page&limit=$limit") ?: return@withContext emptyList()
            val data = extractDataArray(json) ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            AppLog.e(TAG, "getSongs failed", e)
            emptyList()
        }
    }

    /** REVERSE_ENGINEERED: GET /music/api/v1/track/list?limit=0 → total */
    override suspend fun getSongsTotalCount(): Int = withContext(Dispatchers.IO) {
        try {
            val json = executeGet("$baseUrl$API_PREFIX/track/list?page=1&limit=1") ?: return@withContext 0
            extractTotal(json)
        } catch (e: Exception) {
            0
        }
    }

    /**
     * UNCONFIRMED: 飞牛 API 是否支持 ids 批量查询
     * 暂实现为逐个查询后合并（性能差但功能正确）
     */
    override suspend fun getSongsByIds(ids: List<String>): List<Song> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        try {
            // ⚠️ UNCONFIRMED: 尝试 ids 批量参数，失败回退逐个查
            val rawIds = ids.joinToString(",") { stripPrefix(it) }
            val json = executeGet("$baseUrl$API_PREFIX/track/list?ids=$rawIds")
            if (json != null) {
                val data = extractDataArray(json)
                if (data != null && data.size() > 0) {
                    return@withContext data.mapNotNull { parseSong(it.asJsonObject, null) }
                }
            }
            // 回退：逐个查（低效但保底）
            ids.mapNotNull { id ->
                val rawId = stripPrefix(id)
                val songJson = executeGet("$baseUrl$API_PREFIX/track/list?id=$rawId&limit=1")
                songJson?.let { extractDataArray(it)?.firstOrNull()?.asJsonObject }?.let { parseSong(it, null) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** REVERSE_ENGINEERED: GET /music/api/v1/track/search?query={query} */
    override suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val json = executeGet("$baseUrl$API_PREFIX/track/search?query=$encoded&limit=200") ?: return@withContext emptyList()
            val data = extractDataArray(json) ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            AppLog.e(TAG, "searchSongs failed", e)
            emptyList()
        }
    }

    /**
     * UNCONFIRMED: 最近添加歌曲端点
     * 暂用 track/list + sort=createdAt 作为替代
     */
    override suspend fun getRecentSongs(): List<Song> = withContext(Dispatchers.IO) {
        try {
            // ⚠️ UNCONFIRMED: 飞牛是否有专门的最近添加端点
            val json = executeGet("$baseUrl$API_PREFIX/track/list?page=1&limit=100&sort=createdAt&order=desc") ?: return@withContext emptyList()
            val data = extractDataArray(json) ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 流 / 封面 / 歌词 ====================

    /**
     * REVERSE_ENGINEERED: 返回 HLS 流路径
     * 飞牛返回相对路径（如 /music/api/v1/track/123/stream/index.m3u8）
     * 需拼接 baseUrl + 注入 Cookie header
     */
    override fun getStreamUrl(songId: String): String {
        val rawId = stripPrefix(songId)
        return "$baseUrl$API_PREFIX/track/$rawId/stream"  // ⚠️ REVERSE_ENGINEERED: 实际返回可能含 HLS 路径
    }

    /** REVERSE_ENGINEERED: GET /music/api/v1/track/{id}/cover */
    override fun getCoverUrl(songId: String): String {
        val rawId = stripPrefix(songId)
        return "$baseUrl$API_PREFIX/track/$rawId/cover"  // ⚠️ REVERSE_ENGINEERED
    }

    /**
     * REVERSE_ENGINEERED: GET /music/api/v1/track/{id}/lyrics
     * 如果飞牛原生不支持，需配合 FnMusicEnhance（端口 38200）
     */
    override suspend fun getLyrics(songId: String): String? = withContext(Dispatchers.IO) {
        try {
            val rawId = stripPrefix(songId)
            // ⚠️ REVERSE_ENGINEERED: 先试飞牛原生歌词
            val json = executeGet("$baseUrl$API_PREFIX/track/$rawId/lyrics")
            if (json != null) {
                val lyrics = json.get("lyrics")?.asString
                    ?: json.getAsJsonObject("data")?.get("lyrics")?.asString
                    ?: json.get("data")?.asString
                if (!lyrics.isNullOrBlank()) return@withContext lyrics
            }
            // ⚠️ UNCONFIRMED: 如果原生不支持，尝试 FnMusicEnhance
            // FnMusicEnhance 端口 38200，认证用飞牛 music-token
            null
        } catch (e: Exception) {
            AppLog.e(TAG, "getLyrics failed", e)
            null
        }
    }

    // ==================== 歌单 ====================

    /** REVERSE_ENGINEERED: GET /music/api/v1/playlist/list */
    override suspend fun getPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        try {
            val json = executeGet("$baseUrl$API_PREFIX/playlist/list") ?: return@withContext emptyList()
            val data = extractDataArray(json) ?: return@withContext emptyList()
            data.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val name = EncodingUtils.fixEncoding(obj.get("name")?.asString) ?: "Unknown"
                Playlist(id = SONG_ID_PREFIX + id, name = name, songCount = obj.get("count")?.asInt ?: 0)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * REVERSE_ENGINEERED: GET /music/api/v1/playlist/{id}/songs
     * UNCONFIRMED: 确切路径可能是 /playlist/songs?playlistId={id}
     */
    override suspend fun getPlaylistSongs(playlistId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val rawId = stripPrefix(playlistId)
            // ⚠️ UNCONFIRMED: 确切路径待确认
            val json = executeGet("$baseUrl$API_PREFIX/playlist/$rawId/songs") ?: return@withContext emptyList()
            val data = extractDataArray(json) ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 收藏 ====================

    /** REVERSE_ENGINEERED: POST /music/api/v1/favorite/add 或 /favorite/remove */
    override suspend fun toggleFavorite(songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val rawId = stripPrefix(songId)
            // ⚠️ REVERSE_ENGINEERED: 先尝试 add，如果已收藏则尝试 remove
            val addResult = executePost("$baseUrl$API_PREFIX/favorite/add", gson.toJson(mapOf("trackId" to rawId)))
            if (addResult != null && (addResult.get("code")?.asInt ?: 0) == 0) return@withContext true
            // 回退：尝试 remove
            val removeResult = executePost("$baseUrl$API_PREFIX/favorite/remove", gson.toJson(mapOf("trackId" to rawId)))
            removeResult != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * UNCONFIRMED: 收藏列表端点
     * 推断: GET /music/api/v1/favorite/list
     */
    override suspend fun getFavorites(): List<Song> = withContext(Dispatchers.IO) {
        try {
            // ⚠️ UNCONFIRMED: 收藏列表端点待确认
            val json = executeGet("$baseUrl$API_PREFIX/favorite/list?page=1&limit=500") ?: return@withContext emptyList()
            val data = extractDataArray(json) ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 随机歌曲 ====================

    /**
     * UNCONFIRMED: 随机歌曲端点
     * 推断: GET /music/api/v1/track/list?sort=random
     */
    override suspend fun getRandomSongs(limit: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val json = executeGet("$baseUrl$API_PREFIX/track/list?page=1&limit=$limit&sort=random") ?: return@withContext emptyList()
            val data = extractDataArray(json) ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 技术信息 ====================

    override suspend fun getSongTechnicalInfo(songId: String): SongTechnicalInfo? = withContext(Dispatchers.IO) {
        // ⚠️ UNCONFIRMED: 飞牛 API 是否返回码率/采样率/编码格式
        null
    }

    // ==================== Scrobble ====================

    override suspend fun scrobblePlay(songId: String, timestamp: Long): Boolean = withContext(Dispatchers.IO) {
        // ⚠️ UNCONFIRMED: 飞牛是否有播放统计端点
        false
    }

    // ==================== 内部工具 ====================

    /** 带 Cookie 的 GET 请求，返回 JsonObject */
    private fun executeGet(url: String): JsonObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", "music-token=$musicToken")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLog.w(TAG, "GET failed: ${response.code} url=${url.take(80)}")
                return null
            }
            val body = response.body?.string() ?: return null
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            AppLog.e(TAG, "GET error url=${url.take(80)}", e)
            null
        }
    }

    /** 带 Cookie 的 POST 请求，返回 JsonObject */
    private fun executePost(url: String, jsonBody: String): JsonObject? {
        return try {
            val body = okhttp3.RequestBody.create(
                "application/json".toMediaType(),
                jsonBody
            )
            val request = Request.Builder()
                .url(url)
                .header("Cookie", "music-token=$musicToken")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLog.w(TAG, "POST failed: ${response.code} url=${url.take(80)}")
                return null
            }
            val responseBody = response.body?.string() ?: return null
            if (responseBody.isBlank()) JsonObject() else JsonParser.parseString(responseBody).asJsonObject
        } catch (e: Exception) {
            AppLog.e(TAG, "POST error url=${url.take(80)}", e)
            null
        }
    }

    /** 从飞牛响应中提取 data 数组（飞牛响应结构 { code, data: { list: [...] } } 或 { data: [...] }） */
    private fun extractDataArray(json: JsonObject): com.google.gson.JsonArray? {
        val code = json.get("code")?.asInt
        if (code != null && code != 0) {
            AppLog.w(TAG, "API error: code=$code, message=${json.get("message")?.asString}")
            return null
        }
        // 尝试 data.list（嵌套分页）
        json.getAsJsonObject("data")?.let { dataObj ->
            dataObj.getAsJsonArray("list")?.let { return it }
            dataObj.getAsJsonArray("data")?.let { return it }
        }
        // 尝试顶层 data 数组
        return json.getAsJsonArray("data") ?: json.getAsJsonArray("list")
    }

    /** 从飞牛响应中提取 total 数量 */
    private fun extractTotal(json: JsonObject): Int {
        json.getAsJsonObject("data")?.let { dataObj ->
            dataObj.get("total")?.asInt?.let { return it }
        }
        return json.get("total")?.asInt ?: 0
    }

    /** 去掉 feiniu_ 前缀 */
    private fun stripPrefix(id: String): String {
        return if (id.startsWith(SONG_ID_PREFIX)) id.substring(SONG_ID_PREFIX.length) else id
    }

    /** SHA256 哈希 */
    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /** 解析 Album JSON → Album */
    private fun parseAlbum(obj: JsonObject): Album? {
        val id = obj.get("id")?.asString ?: obj.get("albumId")?.asString ?: return null
        val name = EncodingUtils.fixEncoding(obj.get("name")?.asString ?: obj.get("title")?.asString) ?: "Unknown"
        val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString ?: obj.get("artistName")?.asString) ?: ""
        val songCount = obj.get("count")?.asInt ?: obj.get("songCount")?.asInt ?: 0
        return Album(
            id = SONG_ID_PREFIX + id,
            name = name,
            artist = artist,
            coverUrl = obj.get("coverUrl")?.asString ?: obj.get("image")?.asString,
            year = obj.get("year")?.asInt,
            songCount = songCount
        )
    }

    /** 解析 Artist JSON → Artist */
    private fun parseArtist(obj: JsonObject): Artist? {
        val id = obj.get("id")?.asString ?: obj.get("artistId")?.asString ?: return null
        val name = EncodingUtils.fixEncoding(obj.get("name")?.asString ?: obj.get("artistName")?.asString) ?: "Unknown"
        return Artist(
            id = SONG_ID_PREFIX + id,
            name = name,
            coverUrl = obj.get("coverUrl")?.asString ?: obj.get("image")?.asString
        )
    }

    /** 从曲目 JSON 中提取歌手信息（去重用） */
    private fun parseArtistFromTrack(obj: JsonObject): Artist? {
        val name = EncodingUtils.fixEncoding(obj.get("artist")?.asString ?: obj.get("artistName")?.asString) ?: return null
        val id = obj.get("artistId")?.asString ?: name
        return Artist(id = SONG_ID_PREFIX + id, name = name, coverUrl = null)
    }

    /** 解析 Song JSON → Song */
    private fun parseSong(obj: JsonObject, albumId: String?): Song? {
        val id = obj.get("id")?.asString ?: obj.get("trackId")?.asString ?: return null
        val title = EncodingUtils.fixEncoding(obj.get("title")?.asString ?: obj.get("name")?.asString) ?: "Unknown"
        val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString ?: obj.get("artistName")?.asString) ?: ""
        val album = EncodingUtils.fixEncoding(obj.get("album")?.asString ?: obj.get("albumName")?.asString) ?: ""
        val rawAlbumId = obj.get("albumId")?.asString ?: albumId
        val durationSec = obj.get("duration")?.asLong ?: 0L

        return Song(
            id = SONG_ID_PREFIX + id,
            title = title,
            artist = artist,
            album = album,
            albumId = rawAlbumId?.let { SONG_ID_PREFIX + it },
            coverUrl = obj.get("coverUrl")?.asString ?: obj.get("image")?.asString,
            streamUrl = null,
            durationMs = if (durationSec > 100000) durationSec else durationSec * 1000,
            trackNumber = obj.get("trackNumber")?.asInt ?: obj.get("track")?.asInt ?: 0
        )
    }

    /** 配置信任所有 SSL 证书 */
    private fun OkHttpClient.Builder.applyTrustAllSsl(): OkHttpClient.Builder {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllManager), java.security.SecureRandom())
        this.sslSocketFactory(sslContext.socketFactory, trustAllManager)
        this.hostnameVerifier(trustAllHostnameVerifier)
        return this
    }
}

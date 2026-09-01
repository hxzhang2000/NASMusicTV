package com.nasmusic.tv.backend.impl

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongTechnicalInfo
import com.nasmusic.tv.data.model.VersionInfo
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.EncodingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 道理鱼音乐后端适配器
 *
 * 对接道理鱼音乐服务端的自定义 REST API（Node.js/Express 后端）。
 * 后端默认端口 4000，认证方式为 JWT Token（邮箱+密码登录）。
 *
 * ⚠️ 所有 API 端点均为**推断**，实际端点需部署实例后抓包确认。
 *    标注 [INFERRED] 的端点在确认前可能不正确，需根据实际抓包结果调整。
 *
 * Song ID 格式：`daoliyu_${原始ID}`，跨会话稳定，用于最近播放/收藏/队列持久化。
 */
class DaoliyuAdapter : BackendAdapter {

    companion object {
        private const val TAG = "DaoliyuAdapter"
        private const val PAGE_SIZE = 500
        private const val SONG_ID_PREFIX = "daoliyu_"
    }

    override val backendType: String = ServerConfig.TYPE_DAOLIYU
    override var serverName: String = "道理鱼音乐"
    override var apiVersion: String = "Unknown"

    private var baseUrl: String = ""
    private var token: String = ""      // JWT Token
    private var userId: String = ""     // 当前用户 ID

    private val gson = Gson()

    /** 守护线程池 + 信任所有证书（与 JellyfinAdapter 一致） */
    private val daemonExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "Daoliyu-OkHttp").apply { isDaemon = true }
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
            .applyTrustAllSsl()
            .build()
    }

    // ==================== 认证 ====================

    /**
     * ⚠️ INFERRED: 认证端点和流程需抓包确认
     * 推断流程：POST /api/auth/login { email, password } → { token, user }
     */
    override suspend fun initialize(
        baseUrl: String,
        apiToken: String,
        username: String,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        this@DaoliyuAdapter.baseUrl = baseUrl.removeSuffix("/")

        // 优先使用已有 token
        if (apiToken.isNotBlank()) {
            this@DaoliyuAdapter.token = apiToken
            if (fetchUserInfo()) return@withContext true
        }

        // 使用邮箱+密码登录
        if (username.isNotBlank()) {
            val loginResult = login(username, password)
            if (loginResult != null) {
                this@DaoliyuAdapter.token = loginResult.first
                this@DaoliyuAdapter.userId = loginResult.second
                fetchApiVersion()
                return@withContext true
            }
        }
        false
    }

    /** ⚠️ INFERRED: POST /api/auth/login */
    private fun login(email: String, password: String): Pair<String, String>? {
        return try {
            val jsonBody = gson.toJson(mapOf(
                "email" to email,
                "password" to password
            ))
            val body = okhttp3.RequestBody.create(
                "application/json".toMediaType(),
                jsonBody
            )
            val request = Request.Builder()
                .url("$baseUrl/api/auth/login")  // ⚠️ INFERRED
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLog.w(TAG, "login failed: ${response.code}")
                return null
            }
            val responseBody = response.body?.string() ?: return null
            val json = JsonParser.parseString(responseBody).asJsonObject
            val token = json.get("token")?.asString ?: return null
            val userId = json.getAsJsonObject("user")?.get("id")?.asString ?: ""
            Pair(token, userId)
        } catch (e: Exception) {
            AppLog.e(TAG, "login failed", e)
            null
        }
    }

    /** ⚠️ INFERRED: GET /api/auth/me 或 /api/user/me */
    private fun fetchUserInfo(): Boolean {
        return try {
            val json = executeGet("$baseUrl/api/auth/me") ?: return false  // ⚠️ INFERRED
            val user = json.getAsJsonObject("user") ?: json
            userId = user.get("id")?.asString ?: ""
            serverName = user.get("displayName")?.asString ?: "道理鱼音乐"
            fetchApiVersion()
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "fetchUserInfo failed", e)
            false
        }
    }

    /** ⚠️ INFERRED: GET /health 或 /api/version → 版本号 */
    private fun fetchApiVersion() {
        try {
            val json = executeGet("$baseUrl/health")  // ⚠️ INFERRED
            val version = json?.get("version")?.asString
            apiVersion = if (!version.isNullOrBlank()) "Daoliyu API $version" else "Daoliyu (版本未知)"
        } catch (e: Exception) {
            apiVersion = "Daoliyu (版本未知)"
        }
    }

    override suspend fun getApiVersion(): VersionInfo = withContext(Dispatchers.IO) {
        try {
            val json = executeGet("$baseUrl/health")  // ⚠️ INFERRED
            val version = json?.get("version")?.asString
            if (!version.isNullOrBlank()) {
                VersionInfo.Runtime("Daoliyu", version, "/health", System.currentTimeMillis())
            } else {
                VersionInfo.Disconnected("Daoliyu", "版本未知")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "getApiVersion failed", e)
            VersionInfo.Disconnected("Daoliyu")
        }
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .build()
            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            AppLog.w(TAG, "testConnection failed", e)
            false
        }
    }

    override suspend fun logout() {
        // ⚠️ INFERRED: POST /api/auth/logout
        try {
            executePost("$baseUrl/api/auth/logout", "")
        } catch (e: Exception) {
            AppLog.w(TAG, "logout failed", e)
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // ==================== 专辑 ====================

    /** ⚠️ INFERRED: GET /api/albums?page=1&limit=500 */
    override suspend fun getAlbums(): List<Album> = withContext(Dispatchers.IO) {
        try {
            val json = executeGet("$baseUrl/api/albums?page=1&limit=$PAGE_SIZE") ?: return@withContext emptyList()
            val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("albums") ?: return@withContext emptyList()
            data.mapNotNull { parseAlbum(it.asJsonObject) }
        } catch (e: Exception) {
            AppLog.e(TAG, "getAlbums failed", e)
            emptyList()
        }
    }

    /** ⚠️ INFERRED: GET /api/albums/{id}/songs */
    override suspend fun getAlbumSongs(albumId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            // 去掉 daoliyu_ 前缀
            val rawId = stripPrefix(albumId)
            val json = executeGet("$baseUrl/api/albums/$rawId/songs") ?: return@withContext emptyList()
            val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("songs") ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, albumId) }
        } catch (e: Exception) {
            AppLog.e(TAG, "getAlbumSongs failed", e)
            emptyList()
        }
    }

    // ==================== 歌手 ====================

    /** ⚠️ INFERRED: GET /api/artists?page=1&limit=500 */
    override suspend fun getArtists(): List<Artist> = withContext(Dispatchers.IO) {
        try {
            val json = executeGet("$baseUrl/api/artists?page=1&limit=$PAGE_SIZE") ?: return@withContext emptyList()
            val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("artists") ?: return@withContext emptyList()
            data.mapNotNull { parseArtist(it.asJsonObject) }
        } catch (e: Exception) {
            AppLog.e(TAG, "getArtists failed", e)
            emptyList()
        }
    }

    /** ⚠️ INFERRED: GET /api/artists/{id}/songs */
    override suspend fun getArtistSongs(artistId: String, artistName: String?): List<Song> = withContext(Dispatchers.IO) {
        try {
            val rawId = stripPrefix(artistId)
            // ⚠️ INFERRED: 优先按 ID，回退按名称
            val url = if (artistName != null) {
                "$baseUrl/api/artists/$rawId/songs?name=${java.net.URLEncoder.encode(artistName, "UTF-8")}"
            } else {
                "$baseUrl/api/artists/$rawId/songs"
            }
            val json = executeGet(url) ?: return@withContext emptyList()
            val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("songs") ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            AppLog.e(TAG, "getArtistSongs failed", e)
            emptyList()
        }
    }

    // ==================== 歌曲 ====================

    /** ⚠️ INFERRED: GET /api/songs?page=1&limit=500 */
    override suspend fun getSongs(limit: Int, offset: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val page = (offset / limit) + 1
            val json = executeGet("$baseUrl/api/songs?page=$page&limit=$limit") ?: return@withContext emptyList()
            val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("songs") ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            AppLog.e(TAG, "getSongs failed", e)
            emptyList()
        }
    }

    /** ⚠️ INFERRED: GET /api/songs?limit=0 → total 字段 */
    override suspend fun getSongsTotalCount(): Int = withContext(Dispatchers.IO) {
        try {
            val json = executeGet("$baseUrl/api/songs?page=1&limit=1") ?: return@withContext 0
            json.get("total")?.asInt ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /** ⚠️ INFERRED: GET /api/songs?ids=id1,id2,id3 */
    override suspend fun getSongsByIds(ids: List<String>): List<Song> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        try {
            val rawIds = ids.joinToString(",") { stripPrefix(it) }
            val json = executeGet("$baseUrl/api/songs?ids=$rawIds") ?: return@withContext emptyList()
            val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("songs") ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** ⚠️ INFERRED: GET /api/songs?search={query} */
    override suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val json = executeGet("$baseUrl/api/songs?search=$encoded&limit=200") ?: return@withContext emptyList()
            val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("songs") ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            AppLog.e(TAG, "searchSongs failed", e)
            emptyList()
        }
    }

    /** ⚠️ INFERRED: GET /api/songs?sort=createdAt&order=desc&limit=100 */
    override suspend fun getRecentSongs(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val json = executeGet("$baseUrl/api/songs?sort=createdAt&order=desc&page=1&limit=100") ?: return@withContext emptyList()
            val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("songs") ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 流 / 封面 / 歌词 ====================

    /** ⚠️ INFERRED: 令牌式流 GET /api/songs/{id}/stream?token={token} */
    override fun getStreamUrl(songId: String): String {
        val rawId = stripPrefix(songId)
        return "$baseUrl/api/songs/$rawId/stream?token=$token"  // ⚠️ INFERRED
    }

    /** ⚠️ INFERRED: GET /api/songs/{id}/cover 或 /api/albums/{id}/cover */
    override fun getCoverUrl(songId: String): String {
        val rawId = stripPrefix(songId)
        return "$baseUrl/api/songs/$rawId/cover?token=$token"  // ⚠️ INFERRED
    }

    /** ⚠️ INFERRED: GET /api/songs/{id}/lyrics → 返回 LRC 文本 */
    override suspend fun getLyrics(songId: String): String? = withContext(Dispatchers.IO) {
        try {
            val rawId = stripPrefix(songId)
            val request = Request.Builder()
                .url("$baseUrl/api/songs/$rawId/lyrics")  // ⚠️ INFERRED
                .header("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string()
            if (body.isNullOrBlank()) return@withContext null
            // 如果返回 JSON，提取 lyrics 字段
            if (body.trimStart().startsWith("{")) {
                val json = JsonParser.parseString(body).asJsonObject
                json.get("lyrics")?.asString ?: json.get("data")?.asString ?: body
            } else {
                body  // 直接是 LRC 文本
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "getLyrics failed", e)
            null
        }
    }

    // ==================== 歌单 ====================

    /** ⚠️ INFERRED: GET /api/playlists */
    override suspend fun getPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        try {
            val json = executeGet("$baseUrl/api/playlists") ?: return@withContext emptyList()
            val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("playlists") ?: return@withContext emptyList()
            data.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val name = EncodingUtils.fixEncoding(obj.get("name")?.asString) ?: "Unknown"
                Playlist(id = SONG_ID_PREFIX + id, name = name, songCount = obj.get("songCount")?.asInt ?: 0)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** ⚠️ INFERRED: GET /api/playlists/{id}/songs */
    override suspend fun getPlaylistSongs(playlistId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val rawId = stripPrefix(playlistId)
            val json = executeGet("$baseUrl/api/playlists/$rawId/songs") ?: return@withContext emptyList()
            val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("songs") ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 收藏 ====================

    /** ⚠️ INFERRED: POST /api/songs/{id}/favorite 或 /api/favorites/toggle */
    override suspend fun toggleFavorite(songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val rawId = stripPrefix(songId)
            val result = executePost("$baseUrl/api/songs/$rawId/favorite", "")  // ⚠️ INFERRED
            result != null
        } catch (e: Exception) {
            false
        }
    }

    /** ⚠️ INFERRED: GET /api/favorites */
    override suspend fun getFavorites(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val json = executeGet("$baseUrl/api/favorites?page=1&limit=500") ?: return@withContext emptyList()
            val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("songs") ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 随机歌曲 ====================

    /** ⚠️ INFERRED: GET /api/songs/random?limit=20 */
    override suspend fun getRandomSongs(limit: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val json = executeGet("$baseUrl/api/songs/random?limit=$limit") ?: return@withContext emptyList()  // ⚠️ INFERRED
            val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("songs") ?: return@withContext emptyList()
            data.mapNotNull { parseSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 技术信息 ====================

    /** ⚠️ INFERRED: GET /api/songs/{id} → 含 MediaStreams/bitrate 等 */
    override suspend fun getSongTechnicalInfo(songId: String): SongTechnicalInfo? = withContext(Dispatchers.IO) {
        // ⚠️ INFERRED: 需确认 API 是否返回码率/采样率/编码格式
        null
    }

    // ==================== Scrobble ====================

    /** ⚠️ INFERRED: POST /api/songs/{id}/scrobble */
    override suspend fun scrobblePlay(songId: String, timestamp: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val rawId = stripPrefix(songId)
            val body = gson.toJson(mapOf("timestamp" to timestamp))
            executePost("$baseUrl/api/songs/$rawId/scrobble", body) != null  // ⚠️ INFERRED
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 内部工具 ====================

    /** 带 Authorization header 的 GET 请求，返回 JsonObject */
    private fun executeGet(url: String): JsonObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
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

    /** 带 Authorization header 的 POST 请求 */
    private fun executePost(url: String, jsonBody: String): JsonObject? {
        return try {
            val body = okhttp3.RequestBody.create(
                "application/json".toMediaType(),
                jsonBody
            )
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
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

    /** 去掉 daoliyu_ 前缀，返回原始 ID */
    private fun stripPrefix(id: String): String {
        return if (id.startsWith(SONG_ID_PREFIX)) id.substring(SONG_ID_PREFIX.length) else id
    }

    /** 解析 Album JSON → Album */
    private fun parseAlbum(obj: JsonObject): Album? {
        val id = obj.get("id")?.asString ?: return null
        val name = EncodingUtils.fixEncoding(obj.get("name")?.asString ?: obj.get("title")?.asString) ?: "Unknown"
        val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString ?: obj.get("albumArtist")?.asString) ?: ""
        val year = obj.get("year")?.asInt ?: obj.get("productionYear")?.asInt
        val songCount = obj.get("songCount")?.asInt ?: obj.get("childCount")?.asInt ?: 0
        val coverUrl = if (obj.has("coverUrl")) obj.get("coverUrl")?.asString else getCoverUrl(SONG_ID_PREFIX + id)
        return Album(
            id = SONG_ID_PREFIX + id,
            name = name,
            artist = artist,
            coverUrl = coverUrl,
            year = year,
            songCount = songCount
        )
    }

    /** 解析 Artist JSON → Artist */
    private fun parseArtist(obj: JsonObject): Artist? {
        val id = obj.get("id")?.asString ?: return null
        val name = EncodingUtils.fixEncoding(obj.get("name")?.asString) ?: "Unknown Artist"
        val coverUrl = obj.get("coverUrl")?.asString ?: obj.get("image")?.asString
        return Artist(id = SONG_ID_PREFIX + id, name = name, coverUrl = coverUrl)
    }

    /** 解析 Song JSON → Song */
    private fun parseSong(obj: JsonObject, albumId: String?): Song? {
        val id = obj.get("id")?.asString ?: return null
        val title = EncodingUtils.fixEncoding(obj.get("title")?.asString ?: obj.get("name")?.asString) ?: "Unknown"
        val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString ?: obj.get("artistName")?.asString) ?: ""
        val album = EncodingUtils.fixEncoding(obj.get("album")?.asString ?: obj.get("albumName")?.asString) ?: ""
        val rawAlbumId = obj.get("albumId")?.asString ?: albumId
        val coverUrl = obj.get("coverUrl")?.asString ?: obj.get("image")?.asString
        val durationMs = obj.get("duration")?.asLong?.let { if (it > 100000) it else it * 1000 } ?: 0L
        val trackNumber = obj.get("trackNumber")?.asInt ?: obj.get("track")?.asInt ?: 0

        return Song(
            id = SONG_ID_PREFIX + id,
            title = title,
            artist = artist,
            album = album,
            albumId = rawAlbumId?.let { SONG_ID_PREFIX + it },
            coverUrl = coverUrl,
            streamUrl = null,  // 按需解析
            durationMs = durationMs,
            trackNumber = trackNumber
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

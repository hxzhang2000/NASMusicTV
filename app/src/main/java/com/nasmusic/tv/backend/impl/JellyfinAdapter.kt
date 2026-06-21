package com.nasmusic.tv.backend.impl

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Jellyfin 后端适配器
 * 使用 Jellyfin HTTP API（版本 10.7+）
 */
class JellyfinAdapter : BackendAdapter {

    override val backendType: String = "jellyfin"

    private var baseUrl: String = ""
    private var apiToken: String = ""
    private var userId: String = ""
    private var serverName: String = "Jellyfin"

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun initialize(
        baseUrl: String,
        apiToken: String,
        username: String,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        this@JellyfinAdapter.baseUrl = baseUrl.removeSuffix("/")

        // 优先使用已有 token
        if (apiToken.isNotBlank()) {
            this@JellyfinAdapter.apiToken = apiToken
            val userInfo = fetchCurrentUserInfo()
            if (userInfo != null) {
                userId = userInfo.first
                serverName = userInfo.second
                return@withContext true
            }
        }

        // 否则使用用户名密码登录
        if (username.isNotBlank()) {
            val result = authenticateByName(username, password)
            if (result != null) {
                this@JellyfinAdapter.apiToken = result.first
                userId = result.second
                serverName = result.third
                return@withContext true
            }
        }

        false
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/System/Info/Public")
                .header("X-Emby-Authorization", buildAuthHeader())
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            android.util.Log.w("JellyfinAdapter", "testConnection failed", e)
            false
        }
    }

    override suspend fun getAlbums(): List<Album> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,ProductionYear,RunTimeTicks,ChildCount"
            val url = "$baseUrl/Items?" +
                    "Recursive=true&" +
                    "IncludeItemTypes=MusicAlbum&" +
                    "fields=$fields&" +
                    "UserId=$userId&" +
                    "SortBy=SortName&SortOrder=Ascending&" +
                    "StartIndex=0&Limit=1000"

            val json = executeJsonRequest(url) ?: return@withContext emptyList<Album>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Album>()

            items.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("Id")?.asString ?: return@mapNotNull null
                val name = obj.get("Name")?.asString ?: "Unknown Album"
                val artist = obj.get("AlbumArtist")?.asString ?: ""
                val year = obj.get("ProductionYear")?.asInt
                val childCount = obj.get("ChildCount")?.asInt ?: 0
                val runTime = obj.get("RunTimeTicks")?.asLong ?: 0L
                val imageTag = obj.get("ImageTags")?.asJsonObject?.get("Primary")?.asString

                Album(
                    id = id,
                    name = name,
                    artist = artist,
                    coverUrl = buildCoverUrl(id, imageTag) ?: getCoverUrl(id),
                    year = year,
                    songCount = childCount,
                    durationMs = runTime / 10000
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "getAlbums failed", e)
            emptyList()
        }
    }

    override suspend fun getAlbumSongs(albumId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks"
            val url = "$baseUrl/Items?" +
                    "ParentId=$albumId&" +
                    "IncludeItemTypes=Audio&" +
                    "Recursive=true&" +
                    "fields=$fields&" +
                    "UserId=$userId&" +
                    "SortBy=ParentIndexNumber,IndexNumber,SortName&" +
                    "SortOrder=Ascending&" +
                    "StartIndex=0&Limit=500"

            val json = executeJsonRequest(url) ?: return@withContext emptyList<Song>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Song>()
            val songs = items.mapNotNull { jsonObjectToSong(it.asJsonObject, albumId) }
            android.util.Log.d("JellyfinAdapter", "getAlbumSongs: ${songs.size} songs, hasCover=${songs.count { it.coverUrl != null }}/${songs.size}")
            songs
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "getAlbumSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getArtists(): List<Artist> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/Artists/AlbumArtists?" +
                    "UserId=$userId&" +
                    "SortBy=SortName&SortOrder=Ascending&" +
                    "StartIndex=0&Limit=1000"

            val json = executeJsonRequest(url) ?: return@withContext emptyList<Artist>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Artist>()
            items.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("Id")?.asString ?: return@mapNotNull null
                val name = obj.get("Name")?.asString ?: "Unknown Artist"
                val imageTag = obj.get("ImageTags")?.asJsonObject?.get("Primary")?.asString
                Artist(id = id, name = name, coverUrl = buildCoverUrl(id, imageTag) ?: getCoverUrl(id))
            }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "getArtists failed", e)
            emptyList()
        }
    }

    override suspend fun getArtistSongs(artistId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks"
            val url = "$baseUrl/Items?" +
                    "ArtistIds=$artistId&" +
                    "IncludeItemTypes=Audio&" +
                    "Recursive=true&" +
                    "fields=$fields&" +
                    "UserId=$userId&" +
                    "SortBy=SortName&SortOrder=Ascending&" +
                    "StartIndex=0&Limit=1000"

            val json = executeJsonRequest(url) ?: return@withContext emptyList<Song>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Song>()
            items.mapNotNull { jsonObjectToSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "getArtistSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getSongs(limit: Int, offset: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks,Album,AlbumArtist,Artists,IndexNumber,ParentIndexNumber,ProductionYear,Genres"
            val url = "$baseUrl/Items?" +
                    "IncludeItemTypes=Audio&" +
                    "Recursive=true&" +
                    "fields=$fields&" +
                    "UserId=$userId&" +
                    "SortBy=SortName&SortOrder=Ascending&" +
                    "StartIndex=$offset&Limit=$limit"

            val json = executeJsonRequest(url) ?: return@withContext emptyList<Song>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Song>()
            val songs = items.mapNotNull { jsonObjectToSong(it.asJsonObject, null) }
            android.util.Log.d("JellyfinAdapter", "getSongs: ${songs.size} songs (offset=$offset, limit=$limit)")
            songs
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "getSongs failed", e)
            emptyList()
        }
    }

    override suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/Items?" +
                    "SearchTerm=$encodedQuery&" +
                    "IncludeItemTypes=Audio&" +
                    "Recursive=true&" +
                    "fields=$fields&" +
                    "UserId=$userId&" +
                    "StartIndex=0&Limit=200"

            val json = executeJsonRequest(url) ?: return@withContext emptyList<Song>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Song>()
            items.mapNotNull { jsonObjectToSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "searchSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getRecentSongs(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks,DateCreated"
            val url = "$baseUrl/Items?" +
                    "IncludeItemTypes=Audio&" +
                    "Recursive=true&" +
                    "fields=$fields&" +
                    "UserId=$userId&" +
                    "SortBy=DateCreated&SortOrder=Descending&" +
                    "StartIndex=0&Limit=100"

            val json = executeJsonRequest(url) ?: return@withContext emptyList<Song>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Song>()
            items.mapNotNull { jsonObjectToSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "getRecentSongs failed", e)
            emptyList()
        }
    }

    override fun getStreamUrl(songId: String): String =
        "$baseUrl/Audio/$songId/stream.mp3?api_key=$apiToken"

    override fun getCoverUrl(songId: String): String {
        val url = "$baseUrl/Items/$songId/Images/Primary?maxWidth=512&quality=90&api_key=$apiToken"
        android.util.Log.d("JellyfinAdapter", "getCoverUrl: $url")
        return url
    }

    override suspend fun getLyrics(songId: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/Items/$songId/Lyrics?api_key=$apiToken"
            android.util.Log.d("JellyfinAdapter", "getLyrics: requesting $url")
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            val response = client.newCall(request).execute()
            android.util.Log.d("JellyfinAdapter", "getLyrics: status=${response.code} for song=$songId")
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val lyrics = json?.get("lyrics")?.asString
                    android.util.Log.d("JellyfinAdapter", "getLyrics: lyrics found, length=${lyrics?.length ?: 0}")
                    lyrics
                } else {
                    android.util.Log.w("JellyfinAdapter", "getLyrics: body empty for $songId")
                    null
                }
            } else {
                android.util.Log.w("JellyfinAdapter", "getLyrics: not successful, code=${response.code}")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "getLyrics failed for $songId", e)
            null
        }
    }

    // ========== F-1 扩展接口 ==========

    // --- 播放列表 ---
    override suspend fun getPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/Items?IncludeItemTypes=Playlist&UserId=$userId&Recursive=true&StartIndex=0&Limit=200"
            val json = executeJsonRequest(url) ?: return@withContext emptyList<Playlist>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Playlist>()
            items.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("Id")?.asString ?: return@mapNotNull null
                Playlist(
                    id = id,
                    name = obj.get("Name")?.asString ?: "Unknown",
                    songCount = obj.get("ChildCount")?.asInt ?: 0,
                    owner = obj.get("AlbumArtist")?.asString ?: ""
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "getPlaylists failed", e)
            emptyList()
        }
    }

    override suspend fun createPlaylist(name: String): Playlist? = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply {
                addProperty("Name", name)
                addProperty("UserId", userId)
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/Playlists")
                .header("X-Emby-Authorization", buildAuthHeader())
                .post(body.toRequestBody(jsonMediaType))
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
                val id = json?.get("Id")?.asString
                if (id != null) Playlist(id = id, name = name) else null
            } else null
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "createPlaylist failed", e)
            null
        }
    }

    override suspend fun deletePlaylist(playlistId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/Playlists/$playlistId")
                .header("X-Emby-Authorization", buildAuthHeader())
                .delete()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "deletePlaylist failed", e)
            false
        }
    }

    override suspend fun addToPlaylist(playlistId: String, songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply {
                addProperty("Ids", songId)
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/Playlists/$playlistId/Items")
                .header("X-Emby-Authorization", buildAuthHeader())
                .post(body.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "addToPlaylist failed", e)
            false
        }
    }

    override suspend fun removeFromPlaylist(playlistId: String, songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/Playlists/$playlistId/Items?EntryIds=$songId")
                .header("X-Emby-Authorization", buildAuthHeader())
                .delete()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "removeFromPlaylist failed", e)
            false
        }
    }

    // --- 收藏 ---
    override suspend fun toggleFavorite(songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Jellyfin API: POST /UserFavoriteItems/{itemId} 添加收藏，DELETE /UserFavoriteItems/{itemId} 取消收藏
            // 先检查是否已收藏
            val isCurrentlyFavorite = _favoriteIdsCache.contains(songId)
            val requestBuilder = Request.Builder()
                .url("$baseUrl/UserFavoriteItems/$songId")
                .header("X-Emby-Authorization", buildAuthHeader())

            val request = if (isCurrentlyFavorite) {
                requestBuilder.delete("".toRequestBody(null)).build()
            } else {
                requestBuilder.post("".toRequestBody(null)).build()
            }

            client.newCall(request).execute().use { response ->
                android.util.Log.d("JellyfinAdapter", "toggleFavorite: HTTP ${response.code} for $songId")
                response.isSuccessful
            }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "toggleFavorite failed", e)
            false
        }
    }

    // 缓存收藏 IDs，用于判断当前状态
    private var _favoriteIdsCache: Set<String> = emptySet()

    override suspend fun getFavorites(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks"
            val url = "$baseUrl/Items?Filters=IsFavorite&IncludeItemTypes=Audio&" +
                    "Recursive=true&fields=$fields&UserId=$userId&Limit=1000"
            val json = executeJsonRequest(url) ?: return@withContext emptyList<Song>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Song>()
            val songs = items.mapNotNull { jsonObjectToSong(it.asJsonObject, null) }
            // 更新缓存
            _favoriteIdsCache = songs.map { it.id }.toSet()
            android.util.Log.d("JellyfinAdapter", "getFavorites: ${songs.size} favorites")
            songs
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "getFavorites failed", e)
            emptyList()
        }
    }

    // --- 评分 ---
    override suspend fun setRating(songId: String, rating: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply {
                addProperty("Rating", rating.coerceIn(1, 5))
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/Items/$songId/Rating?api_key=$apiToken")
                .header("X-Emby-Authorization", buildAuthHeader())
                .post(body.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "setRating failed", e)
            false
        }
    }

    // --- 流派 ---
    override suspend fun getGenres(): List<Genre> = withContext(Dispatchers.IO) {
        try {
            // IncludeItemTypes=Audio 确保只返回音乐流派，不包括电影/电视流派
            val url = "$baseUrl/Genres?UserId=$userId&IncludeItemTypes=Audio&Recursive=true&Limit=200"
            val json = executeJsonRequest(url) ?: return@withContext emptyList<Genre>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Genre>()
            items.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("Id")?.asString ?: return@mapNotNull null
                Genre(
                    id = id,
                    name = obj.get("Name")?.asString ?: "Unknown",
                    songCount = obj.get("SongCount")?.asInt?.coerceAtLeast(0)
                        ?: obj.get("ChildCount")?.asInt ?: 0
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "getGenres failed", e)
            emptyList()
        }
    }

    override suspend fun getSongsByGenre(genre: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks"
            val encodedGenre = java.net.URLEncoder.encode(genre, "UTF-8")
            val url = "$baseUrl/Items?IncludeItemTypes=Audio&Recursive=true&" +
                    "fields=$fields&UserId=$userId&Genres=$encodedGenre&Limit=1000"
            val json = executeJsonRequest(url) ?: return@withContext emptyList<Song>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Song>()
            items.mapNotNull { jsonObjectToSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "getSongsByGenre failed", e)
            emptyList()
        }
    }

    override suspend fun getSongsByYearRange(fromYear: Int, toYear: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks"
            // Jellyfin supports single year filter; for range we use Albums by year and collect songs
            // Simplified: filter by first year in range
            val year = fromYear
            val url = "$baseUrl/Items?IncludeItemTypes=Audio&Recursive=true&" +
                    "fields=$fields&UserId=$userId&Years=$year&Limit=1000"
            val json = executeJsonRequest(url) ?: return@withContext emptyList<Song>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Song>()
            items.mapNotNull { jsonObjectToSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "getSongsByYearRange failed", e)
            emptyList()
        }
    }

    // --- Scrobble ---
    override suspend fun scrobblePlay(songId: String, timestamp: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply {
                addProperty("ItemId", songId)
                addProperty("PositionTicks", timestamp * 10000)
                addProperty("PlayMethod", "DirectPlay")
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/Sessions/Playing?api_key=$apiToken")
                .header("X-Emby-Authorization", buildAuthHeader())
                .post(body.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "scrobblePlay failed", e)
            false
        }
    }

    // --- 随机歌曲 ---
    override suspend fun getRandomSongs(limit: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks"
            val url = "$baseUrl/Items?IncludeItemTypes=Audio&Recursive=true&" +
                    "fields=$fields&UserId=$userId&Limit=$limit&SortBy=Random"
            val json = executeJsonRequest(url) ?: return@withContext emptyList<Song>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Song>()
            items.mapNotNull { jsonObjectToSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "getRandomSongs failed", e)
            emptyList()
        }
    }

    /**
     * 登出当前 session，使 Jellyfin 服务端释放 session 资源。
     * 每次 [initialize] 或 [authenticateByName] 在服务端创建一个 session，
     * 多次 [testConnection] 会积累大量 session 直到 HTTP 500。
     * 调用此方法后 adapter 不可再用（需重新 [initialize]）。
     */
    override suspend fun logout() = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || apiToken.isBlank()) return@withContext
        try {
            val request = Request.Builder()
                .url("$baseUrl/Sessions/Logout")
                .header("X-Emby-Authorization", buildAuthHeader())
                .post("".toRequestBody(null))
                .build()
            client.newCall(request).execute().use { response ->
                android.util.Log.d("JellyfinAdapter", "logout: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            android.util.Log.w("JellyfinAdapter", "logout failed", e)
        } finally {
            // 清空凭据防止后续误用
            apiToken = ""
            userId = ""
        }
    }

    /**
     * 释放 OkHttp 连接资源。
     * logout() 处理服务端 session，此处关闭客户端连接池，防止连接泄漏。
     */
    override fun close() {
        try {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            android.util.Log.d("JellyfinAdapter", "close: OkHttp resources released")
        } catch (e: Exception) {
            android.util.Log.w("JellyfinAdapter", "close failed", e)
        }
    }

    fun getServerName(): String = serverName

    // --- 内部辅助方法 ---

    private fun buildAuthHeader(): String {
        val tokenPart = if (apiToken.isNotBlank()) ", Token=\"$apiToken\"" else ""
        return "MediaBrowser Client=\"NASMusicTV\", Device=\"AndroidTV\", " +
                "DeviceId=\"nas-music-tv\", Version=\"1.0.0\"$tokenPart"
    }

    private fun buildCoverUrl(itemId: String, imageTag: String? = null): String? {
        if (imageTag == null) return null
        return "$baseUrl/Items/$itemId/Images/Primary?tag=$imageTag&maxWidth=512&quality=90&api_key=$apiToken"
    }

    private fun executeJsonRequest(url: String): JsonObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("X-Emby-Authorization", buildAuthHeader())
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful) return null
                gson.fromJson(body, JsonObject::class.java)
            }
        } catch (e: Exception) {
            android.util.Log.w("JellyfinAdapter", "executeJsonRequest failed for $url", e)
            null
        }
    }

    /**
     * 修复字符串编码问题
     * 处理 Jellyfin 返回的乱码模式：
     * 1. 末尾的 �?（U+FFFD + ?）
     * 2. GB2312/GBK 字节被当作 Latin-1 解码（如 ÎÒÊÇÕæµÄ°®Äã）
     */
    private fun fixEncoding(text: String?): String? {
        if (text.isNullOrBlank()) return text
        
        // 第一步：移除末尾的乱码模式：�?（U+FFFD + ?）或单独的 ?
        var fixed: String = text
        while (fixed.endsWith("?") || fixed.endsWith("\uFFFD?") || fixed.endsWith("\uFFFD")) {
            if (fixed.endsWith("\uFFFD?")) {
                fixed = fixed.dropLast(2) // 移除 U+FFFD 和 ?
            } else {
                fixed = fixed.dropLast(1)
            }
        }
        
        // 第二步：检测 GB2312/GBK 编码被当作 Latin-1 解码的情况
        // 特征：字符串包含大量 Latin-1 扩展字符（0x80-0xFF 范围）
        val latin1Count = fixed.count { it.code in 0x80..0xFF }
        val totalCount = fixed.length
        
        // 如果超过 30% 的字符是 Latin-1 扩展字符，尝试从 Latin-1 转换到 GB2312
        if (latin1Count > 0 && latin1Count.toFloat() / totalCount > 0.3f) {
            try {
                // 将字符串当作 Latin-1 编码的字节
                val bytes = fixed.toByteArray(Charsets.ISO_8859_1)
                // 尝试用 GB2312 解码
                val decoded = String(bytes, charset("GB2312"))
                // 如果解码后包含中文字符，使用这个结果
                if (decoded.any { it.code in 0x4E00..0x9FFF }) {
                    android.util.Log.d("JellyfinAdapter", "fixEncoding: converted Latin-1 to GB2312: '$fixed' -> '$decoded'")
                    fixed = decoded
                }
            } catch (e: Exception) {
                // GB2312 解码失败，尝试 GBK
                try {
                    val bytes = fixed.toByteArray(Charsets.ISO_8859_1)
                    val decoded = String(bytes, charset("GBK"))
                    if (decoded.any { it.code in 0x4E00..0x9FFF }) {
                        android.util.Log.d("JellyfinAdapter", "fixEncoding: converted Latin-1 to GBK: '$fixed' -> '$decoded'")
                        fixed = decoded
                    }
                } catch (e2: Exception) {
                    // 都失败了，保持原样
                }
            }
        }
        
        // 如果移除后变为空，返回原字符串
        if (fixed.isBlank()) return text
        
        // 如果有变化，记录日志
        if (fixed != text) {
            android.util.Log.d("JellyfinAdapter", "fixEncoding: result: '${text.take(30)}' -> '${fixed.take(30)}'")
        }
        
        return fixed
    }

    private fun jsonObjectToSong(obj: JsonObject, albumIdOverride: String?): Song? {
        val id = obj.get("Id")?.asString ?: return null
        val rawTitle = obj.get("Name")?.asString
        val rawAlbum = obj.get("Album")?.asString
        val rawArtist = obj.getAsJsonArray("Artists")?.firstOrNull()?.asString
        val title = fixEncoding(rawTitle) ?: "Unknown"
        val artist = fixEncoding(rawArtist) ?: fixEncoding(obj.get("AlbumArtist")?.asString) ?: ""
        val album = fixEncoding(rawAlbum) ?: ""
        val albumId = albumIdOverride ?: obj.get("AlbumId")?.asString ?: ""
        val trackNumber = obj.get("IndexNumber")?.asInt ?: 0
        val discNumber = obj.get("ParentIndexNumber")?.asInt ?: 1
        val year = obj.get("ProductionYear")?.asInt
        val runTimeTicks = obj.get("RunTimeTicks")?.asLong ?: 0L
        val durationMs = runTimeTicks / 10000
        val imageTag = obj.get("ImageTags")?.asJsonObject?.get("Primary")?.asString
        val genreArr = obj.getAsJsonArray("Genres")
        val genre = fixEncoding(genreArr?.firstOrNull()?.asString)

        // 调试日志：检查原始数据和修复后的数据
        android.util.Log.d("JellyfinAdapter", "jsonObjectToSong: id=$id")
        android.util.Log.d("JellyfinAdapter", "  rawTitle='${rawTitle?.take(30)}' fixedTitle='${title.take(30)}'")
        android.util.Log.d("JellyfinAdapter", "  rawAlbum='${rawAlbum?.take(30)}' fixedAlbum='${album.take(30)}'")
        android.util.Log.d("JellyfinAdapter", "  rawArtist='${rawArtist?.take(30)}' fixedArtist='${artist.take(30)}'")
        android.util.Log.d("JellyfinAdapter", "  runTimeTicks=$runTimeTicks durationMs=$durationMs")

        return Song(
            id = id,
            title = title,
            artist = artist,
            album = album,
            albumId = albumId,
            coverUrl = buildCoverUrl(albumId.ifBlank { id }, imageTag)
                ?: getCoverUrl(albumId.ifBlank { id }),
            streamUrl = getStreamUrl(id),
            durationMs = durationMs,
            trackNumber = trackNumber,
            discNumber = discNumber,
            year = year,
            genre = genre
        )
    }

    private suspend fun authenticateByName(username: String, password: String): Triple<String, String, String>? {
        return try {
            val bodyJson = JsonObject().apply {
                addProperty("Username", username)
                addProperty("Pw", password)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/Users/AuthenticateByName")
                .header("X-Emby-Authorization", buildAuthHeader())
                .post(bodyJson.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use null
                if (!response.isSuccessful) {
                    android.util.Log.w("JellyfinAdapter", "authenticateByName: HTTP ${response.code} for $baseUrl/Users/AuthenticateByName, body=${body.take(200)}")
                    return@use null
                }
                val json = gson.fromJson(body, JsonObject::class.java)
                val accessToken = json.get("AccessToken")?.asString ?: return@use null
                val userObj = json.getAsJsonObject("User") ?: return@use null
                val uid = userObj.get("Id")?.asString ?: return@use null
                val serverInfo = json.getAsJsonObject("ServerInfo")
                val sName = serverInfo?.get("ServerName")?.asString ?: "Jellyfin"
                Triple(accessToken, uid, sName)
            }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "authenticateByName failed", e)
            null
        }
    }

    private suspend fun fetchCurrentUserInfo(): Pair<String, String>? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/Users/Me")
                .header("X-Emby-Authorization", buildAuthHeader())
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use null
                if (!response.isSuccessful) return@use null
                val json = gson.fromJson(body, JsonObject::class.java)
                val uid = json.get("Id")?.asString ?: return@use null
                val name = json.get("Name")?.asString ?: "Jellyfin"
                Pair(uid, name)
            }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinAdapter", "fetchCurrentUserInfo failed", e)
            null
        }
    }
}

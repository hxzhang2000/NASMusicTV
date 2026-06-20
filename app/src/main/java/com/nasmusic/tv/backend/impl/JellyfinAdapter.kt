package com.nasmusic.tv.backend.impl

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
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
        } catch (e: Exception) { false }
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
        } catch (e: Exception) { emptyList() }
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
        } catch (e: Exception) { emptyList() }
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
        } catch (e: Exception) { emptyList() }
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
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getSongs(limit: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks"
            val url = "$baseUrl/Items?" +
                    "IncludeItemTypes=Audio&" +
                    "Recursive=true&" +
                    "fields=$fields&" +
                    "UserId=$userId&" +
                    "SortBy=SortName&SortOrder=Ascending&" +
                    "StartIndex=0&Limit=$limit"

            val json = executeJsonRequest(url) ?: return@withContext emptyList<Song>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Song>()
            val songs = items.mapNotNull { jsonObjectToSong(it.asJsonObject, null) }
            android.util.Log.d("JellyfinAdapter", "getSongs: ${songs.size} songs, hasCover=${songs.count { it.coverUrl != null }}/${songs.size}")
            songs
        } catch (e: Exception) { emptyList() }
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
        } catch (e: Exception) { emptyList() }
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
        } catch (e: Exception) { emptyList() }
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
                    // Jellyfin 返回 JSON，提取歌词内容
                    val json = gson.fromJson(body, com.google.gson.JsonObject::class.java)
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
        } catch (e: Exception) { null }
    }

    private fun jsonObjectToSong(obj: JsonObject, albumIdOverride: String?): Song? {
        val id = obj.get("Id")?.asString ?: return null
        val title = obj.get("Name")?.asString ?: "Unknown"
        val artistArr = obj.getAsJsonArray("Artists")
        val artist = artistArr?.firstOrNull()?.asString
            ?: obj.get("AlbumArtist")?.asString ?: ""
        val album = obj.get("Album")?.asString ?: ""
        val albumId = albumIdOverride ?: obj.get("AlbumId")?.asString ?: ""
        val trackNumber = obj.get("IndexNumber")?.asInt ?: 0
        val discNumber = obj.get("ParentIndexNumber")?.asInt ?: 1
        val year = obj.get("ProductionYear")?.asInt
        val runTimeTicks = obj.get("RunTimeTicks")?.asLong ?: 0L
        val imageTag = obj.get("ImageTags")?.asJsonObject?.get("Primary")?.asString

        return Song(
            id = id,
            title = title,
            artist = artist,
            album = album,
            albumId = albumId,
            coverUrl = buildCoverUrl(albumId.ifBlank { id }, imageTag)
                ?: getCoverUrl(albumId.ifBlank { id }),
            streamUrl = getStreamUrl(id),
            durationMs = runTimeTicks / 10000,
            trackNumber = trackNumber,
            discNumber = discNumber,
            year = year
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
                if (!response.isSuccessful) return@use null
                val json = gson.fromJson(body, JsonObject::class.java)
                val accessToken = json.get("AccessToken")?.asString ?: return@use null
                val userObj = json.getAsJsonObject("User") ?: return@use null
                val uid = userObj.get("Id")?.asString ?: return@use null
                val serverInfo = json.getAsJsonObject("ServerInfo")
                val sName = serverInfo?.get("ServerName")?.asString ?: "Jellyfin"
                Triple(accessToken, uid, sName)
            }
        } catch (e: Exception) { null }
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
        } catch (e: Exception) { null }
    }
}

package com.nasmusic.tv.backend.impl

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.EncodingUtils
import com.nasmusic.tv.util.RetryConfig
import com.nasmusic.tv.util.withRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.nio.charset.Charset
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
    override var serverName: String = "Jellyfin"
        private set

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        // 使用守护线程的 ExecutorService，防止 OkHttp 线程阻止进程退出
        val daemonExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "Jellyfin-OkHttp").apply { isDaemon = true }
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .dispatcher(okhttp3.Dispatcher(daemonExecutor))
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
            AppLog.w("JellyfinAdapter", "testConnection failed", e)
            false
        }
    }

    override suspend fun getAlbums(): List<Album> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,ProductionYear,RunTimeTicks,ChildCount"
            val allAlbums = mutableListOf<Album>()
            var startIndex = 0
            val pageSize = 1000

            while (true) {
                val url = "$baseUrl/Items?" +
                        "Recursive=true&" +
                        "IncludeItemTypes=MusicAlbum&" +
                        "fields=$fields&" +
                        "UserId=$userId&" +
                        "SortBy=SortName&SortOrder=Ascending&" +
                        "StartIndex=$startIndex&Limit=$pageSize"

                val json = executeJsonRequest(url) ?: break
                val items = json.getAsJsonArray("Items") ?: break

                for (item in items) {
                    val obj = item.asJsonObject
                    val id = obj.get("Id")?.asString ?: continue
                    val name = EncodingUtils.fixEncoding(obj.get("Name")?.asString) ?: "Unknown Album"
                    val artist = EncodingUtils.fixEncoding(obj.get("AlbumArtist")?.asString) ?: ""
                    val year = obj.get("ProductionYear")?.asInt
                    val childCount = obj.get("ChildCount")?.asInt ?: 0
                    val runTime = obj.get("RunTimeTicks")?.asLong ?: 0L
                    val imageTag = obj.get("ImageTags")?.asJsonObject?.get("Primary")?.asString

                    allAlbums.add(
                        Album(
                            id = id,
                            name = name,
                            artist = artist,
                            coverUrl = buildCoverUrl(id, imageTag) ?: getCoverUrl(id),
                            year = year,
                            songCount = childCount,
                            durationMs = runTime / 10000
                        )
                    )
                }

                // 返回数量小于 pageSize 时已加载全部
                if (items.size() < pageSize) break
                startIndex += pageSize
            }

            AppLog.d("JellyfinAdapter", "getAlbums: ${allAlbums.size} albums loaded")
            allAlbums
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getAlbums failed", e)
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
            AppLog.d("JellyfinAdapter", "getAlbumSongs: ${songs.size} songs, hasCover=${songs.count { it.coverUrl != null }}/${songs.size}")
            songs
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getAlbumSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getArtists(): List<Artist> = withContext(Dispatchers.IO) {
        try {
            val allArtists = mutableListOf<Artist>()
            var startIndex = 0
            val pageSize = 1000

            while (true) {
                val url = "$baseUrl/Artists/AlbumArtists?" +
                        "UserId=$userId&" +
                        "SortBy=SortName&SortOrder=Ascending&" +
                        "Fields=ImageTags&" +
                        "StartIndex=$startIndex&Limit=$pageSize"

                val json = executeJsonRequest(url) ?: break
                val items = json.getAsJsonArray("Items") ?: break

                for (item in items) {
                    val obj = item.asJsonObject
                    val id = obj.get("Id")?.asString ?: continue
                    val rawName = obj.get("Name")?.asString
                    val name = EncodingUtils.fixEncoding(rawName) ?: "Unknown Artist"
                    val imageTag = obj.get("ImageTags")?.asJsonObject?.get("Primary")?.asString
                    val coverUrl = buildCoverUrl(id, imageTag) ?: getCoverUrl(id)
                    AppLog.d("NASMusic", "getArtists: raw='${rawName?.take(30)}' fixed='${name.take(30)}'")
                    allArtists.add(Artist(id = id, name = name, coverUrl = coverUrl))
                }

                // 如果返回数量小于 pageSize，说明已加载全部
                if (items.size() < pageSize) break
                startIndex += pageSize
            }

            AppLog.d("NASMusic", "getArtists: total ${allArtists.size} artists loaded")
            allArtists
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getArtists failed", e)
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
            AppLog.e("JellyfinAdapter", "getArtistSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getSongs(limit: Int, offset: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks,Album,AlbumArtist,Artists,ArtistItems,IndexNumber,ParentIndexNumber,ProductionYear,Genres"
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
            AppLog.d("JellyfinAdapter", "getSongs: ${songs.size} songs (offset=$offset, limit=$limit)")
            songs
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getSongsTotalCount(): Int = withContext(Dispatchers.IO) {
        try {
            // 只请求 1 条获取 TotalRecordCount
            val url = "$baseUrl/Items?" +
                    "IncludeItemTypes=Audio&" +
                    "Recursive=true&" +
                    "UserId=$userId&" +
                    "Limit=0"
            val json = executeJsonRequest(url) ?: return@withContext 0
            json.get("TotalRecordCount")?.asInt ?: 0
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getSongsTotalCount failed", e)
            0
        }
    }

    override suspend fun getSongsByIds(ids: List<String>): List<Song> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        try {
            // Jellyfin 支持 ?Ids=id1,id2,id3 批量查询
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks,Album,AlbumArtist,Artists,ArtistItems,IndexNumber,ParentIndexNumber,ProductionYear,Genres"
            val idsParam = ids.joinToString(",")
            val url = "$baseUrl/Items?" +
                    "IncludeItemTypes=Audio&" +
                    "Recursive=true&" +
                    "fields=$fields&" +
                    "UserId=$userId&" +
                    "Ids=$idsParam&" +
                    "Limit=${ids.size}"
            val json = executeJsonRequest(url) ?: return@withContext emptyList()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList()
            items.mapNotNull { jsonObjectToSong(it.asJsonObject, null) }
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getSongsByIds failed", e)
            emptyList()
        }
    }

    override suspend fun getYears(): List<Int> = withContext(Dispatchers.IO) {
        try {
            // 利用 Jellyfin 的 /Items/Filters 端点获取年份列表
            val url = "$baseUrl/Items/Filters?UserId=$userId&IncludeItemTypes=Audio"
            val json = executeJsonRequest(url) ?: return@withContext emptyList()
            val yearsArray = json.getAsJsonArray("Years") ?: return@withContext emptyList()
            val years = mutableListOf<Int>()
            for (i in 0 until yearsArray.size()) {
                val year = yearsArray[i].asInt
                if (year > 0) years.add(year)
            }
            years.sortedDescending()
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getYears failed", e)
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
            AppLog.e("JellyfinAdapter", "searchSongs failed", e)
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
            AppLog.e("JellyfinAdapter", "getRecentSongs failed", e)
            emptyList()
        }
    }

    override fun getStreamUrl(songId: String): String =
        "$baseUrl/Audio/$songId/stream.mp3?api_key=$apiToken"

    override fun getCoverUrl(songId: String): String {
        val url = "$baseUrl/Items/$songId/Images/Primary?maxWidth=512&quality=90&api_key=$apiToken"
        AppLog.d("JellyfinAdapter", "getCoverUrl: $url")
        return url
    }

    override fun getCoverUrlCandidates(song: Song): List<String> {
        val urls = mutableListOf<String>()
        // 1. 歌曲封面（已含 tag 的精确 URL，或无 tag 的 Primary URL）
        song.coverUrl?.let { urls.add(it) }
        // 2. 专辑封面（不带 tag，Jellyfin 会返回该 item 的 Primary 图）
        if (!song.albumId.isNullOrBlank()) {
            urls.add("$baseUrl/Items/${song.albumId}/Images/Primary?maxWidth=512&quality=90&api_key=$apiToken")
        }
        // 3. 艺术家封面
        if (!song.artistId.isNullOrBlank()) {
            urls.add("$baseUrl/Items/${song.artistId}/Images/Primary?maxWidth=512&quality=90&api_key=$apiToken")
        }
        return urls.distinct().filter { it.isNotBlank() }
    }

    override suspend fun getLyrics(songId: String): String? = withContext(Dispatchers.IO) {
        try {
            // Jellyfin 官方歌词端点：GET /Audio/{itemId}/Lyrics
            // 响应为 LyricDto 结构：{ "Lyrics": [{ "Text": "...", "Start": ticks }], "Metadata": {...} }
            val url = "$baseUrl/Audio/$songId/Lyrics"
            AppLog.d("JellyfinAdapter", "getLyrics: requesting $url")
            val request = Request.Builder()
                .url(url)
                .header("X-Emby-Authorization", buildAuthHeader())
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                AppLog.d("JellyfinAdapter", "getLyrics: status=${response.code} for song=$songId")
                if (response.isSuccessful) {
                    val body = response.utf8Body()
                    if (!body.isNullOrBlank()) {
                        // 将 Jellyfin LyricDto JSON 转换为 LRC 格式文本
                        val lrcText = convertJellyfinLyricsToLrc(body)
                        AppLog.d("JellyfinAdapter", "getLyrics: converted to LRC, length=${lrcText?.length ?: 0}")
                        lrcText
                    } else {
                        AppLog.w("JellyfinAdapter", "getLyrics: body empty for $songId")
                        null
                    }
                } else {
                    AppLog.w("JellyfinAdapter", "getLyrics: not successful, code=${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getLyrics failed for $songId", e)
            null
        }
    }

    /**
     * 将 Jellyfin LyricDto JSON 转换为 LRC 格式文本
     * Jellyfin 返回格式：{ "Lyrics": [{ "Text": "歌词", "Start": 10000000 }], "Metadata": {...} }
     * Start 字段是 ticks（10000 ticks = 1 ms）
     * 转换为 LRC 行：[mm:ss.xx]歌词
     */
    private fun convertJellyfinLyricsToLrc(jsonBody: String): String? {
        return try {
            val json = gson.fromJson(jsonBody, JsonObject::class.java) ?: return null
            val lyricsArray = json.getAsJsonArray("Lyrics") ?: return null
            if (lyricsArray.size() == 0) return null

            val lrcBuilder = StringBuilder()

            // 从 Metadata 提取信息，生成 LRC 头部
            val metadata = json.getAsJsonObject("Metadata")
            if (metadata != null) {
                val artist = metadata.get("Artist")?.asString
                val title = metadata.get("Title")?.asString
                if (!artist.isNullOrBlank()) lrcBuilder.append("[ar:$artist]\n")
                if (!title.isNullOrBlank()) lrcBuilder.append("[ti:$title]\n")
            }

            // 将每个 LyricLine 转换为 LRC 行
            for (i in 0 until lyricsArray.size()) {
                val lineObj = lyricsArray[i].asJsonObject
                val text = lineObj.get("Text")?.asString ?: ""
                val startTicks = lineObj.get("Start")?.asLong ?: 0L
                // ticks 转 ms（10000 ticks = 1 ms）
                val startMs = startTicks / 10000
                // ms 转 [mm:ss.xx] 格式
                val totalSeconds = startMs / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                val hundredths = (startMs % 1000) / 10
                lrcBuilder.append("[%02d:%02d.%02d]%s\n".format(minutes, seconds, hundredths, text))
            }

            val result = lrcBuilder.toString()
            if (result.isBlank()) null else result
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "convertJellyfinLyricsToLrc failed", e)
            null
        }
    }

    // ========== F-1 扩展接口 ==========

    // --- 播放列表 ---
    override suspend fun getPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        try {
            // Jellyfin API: GET /Playlists?userId=...
            val url = "$baseUrl/Playlists?UserId=$userId"
            val json = executeJsonRequest(url) ?: return@withContext emptyList<Playlist>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Playlist>()
            items.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("Id")?.asString ?: return@mapNotNull null
                Playlist(
                    id = id,
                    name = EncodingUtils.fixEncoding(obj.get("Name")?.asString) ?: "Unknown",
                    songCount = obj.get("ChildCount")?.asInt ?: 0,
                    owner = EncodingUtils.fixEncoding(obj.get("AlbumArtist")?.asString) ?: ""
                )
            }
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getPlaylists failed", e)
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
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.utf8Body()?.let { gson.fromJson(it, JsonObject::class.java) }
                    val id = json?.get("Id")?.asString
                    if (id != null) Playlist(id = id, name = name) else null
                } else null
            }
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "createPlaylist failed", e)
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
            AppLog.e("JellyfinAdapter", "deletePlaylist failed", e)
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
            AppLog.e("JellyfinAdapter", "addToPlaylist failed", e)
            false
        }
    }

    override suspend fun removeFromPlaylist(playlistId: String, songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Jellyfin API: DELETE /Playlists/{playlistId}/Items?EntryIds={playlistItemId}
            // EntryIds 需要的是播放列表条目 ID（PlaylistItemId），不是原始歌曲 ID
            // 先查询播放列表条目，找到与 songId 匹配的 PlaylistItemId
            val listUrl = "$baseUrl/Playlists/$playlistId/Items?UserId=$userId"
            val listRequest = Request.Builder()
                .url(listUrl)
                .header("X-Emby-Authorization", buildAuthHeader())
                .get()
                .build()
            val entryId = client.newCall(listRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.utf8Body() ?: return@withContext false
                val json = gson.fromJson(body, JsonObject::class.java)
                val items = json?.getAsJsonArray("Items") ?: return@withContext false
                var foundEntryId: String? = null
                for (i in 0 until items.size()) {
                    val item = items[i].asJsonObject
                    val itemId = item.get("Id")?.asString
                    val playlistItemId = item.get("PlaylistItemId")?.asString
                    // 匹配歌曲 ID（Id 字段是原始歌曲 ID，PlaylistItemId 是播放列表条目 ID）
                    if (itemId == songId) {
                        foundEntryId = playlistItemId ?: itemId
                        break
                    }
                }
                foundEntryId
            }

            val effectiveEntryId = entryId ?: songId
            val request = Request.Builder()
                .url("$baseUrl/Playlists/$playlistId/Items?EntryIds=$effectiveEntryId")
                .header("X-Emby-Authorization", buildAuthHeader())
                .delete()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "removeFromPlaylist failed", e)
            false
        }
    }

    // --- 收藏 ---
    // 缓存收藏 IDs，用于判断当前状态（线程安全）
    private val _favoriteIdsCache = mutableSetOf<String>()
    private val favoriteCacheLock = Any()

    override suspend fun toggleFavorite(songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Jellyfin API: POST /Users/{userId}/FavoriteItems/{itemId} 添加收藏
            //               DELETE /Users/{userId}/FavoriteItems/{itemId} 取消收藏
            // 先查询当前收藏状态，避免依赖本地缓存（缓存可能未初始化）
            val isCurrentlyFavorite = queryFavoriteStatus(songId)
            val requestBuilder = Request.Builder()
                .url("$baseUrl/Users/$userId/FavoriteItems/$songId")
                .header("X-Emby-Authorization", buildAuthHeader())

            val request = if (isCurrentlyFavorite) {
                requestBuilder.delete("".toRequestBody(null)).build()
            } else {
                requestBuilder.post("".toRequestBody(null)).build()
            }

            client.newCall(request).execute().use { response ->
                AppLog.d("JellyfinAdapter", "toggleFavorite: HTTP ${response.code} for $songId")
                if (response.isSuccessful) {
                    // 操作成功后更新本地缓存
                    synchronized(favoriteCacheLock) {
                        if (isCurrentlyFavorite) {
                            _favoriteIdsCache.remove(songId)
                        } else {
                            _favoriteIdsCache.add(songId)
                        }
                    }
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "toggleFavorite failed", e)
            false
        }
    }

    /**
     * 查询歌曲当前的收藏状态
     * Jellyfin API: GET /Users/{userId}/Items/{itemId} 返回 UserItemDataDto，
     * 其中 UserData.IsFavorite 表示收藏状态
     */
    private fun queryFavoriteStatus(songId: String): Boolean {
        return try {
            val url = "$baseUrl/Users/$userId/Items/$songId"
            val request = Request.Builder()
                .url(url)
                .header("X-Emby-Authorization", buildAuthHeader())
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.utf8Body() ?: return false
                val json = gson.fromJson(body, JsonObject::class.java) ?: return false
                val userData = json.getAsJsonObject("UserData") ?: return false
                userData.get("IsFavorite")?.asBoolean ?: false
            }
        } catch (e: Exception) {
            AppLog.w("JellyfinAdapter", "queryFavoriteStatus failed for $songId", e)
            false
        }
    }

    override suspend fun getFavorites(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks"
            val allSongs = mutableListOf<Song>()
            var startIndex = 0
            val pageSize = 1000

            while (true) {
                val url = "$baseUrl/Items?Filters=IsFavorite&IncludeItemTypes=Audio&" +
                        "Recursive=true&fields=$fields&UserId=$userId&" +
                        "StartIndex=$startIndex&Limit=$pageSize"
                val json = executeJsonRequest(url) ?: break
                val items = json.getAsJsonArray("Items") ?: break
                for (item in items) {
                    jsonObjectToSong(item.asJsonObject, null)?.let { allSongs.add(it) }
                }
                if (items.size() < pageSize) break
                startIndex += pageSize
            }

            // 更新缓存（线程安全）
            synchronized(favoriteCacheLock) {
                _favoriteIdsCache.clear()
                _favoriteIdsCache.addAll(allSongs.map { it.id })
            }
            AppLog.d("JellyfinAdapter", "getFavorites: ${allSongs.size} favorites")
            allSongs
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getFavorites failed", e)
            emptyList()
        }
    }

    // --- 评分 ---
    override suspend fun setRating(songId: String, rating: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            // Jellyfin API: POST /Users/{userId}/Items/{itemId}/Rating?rating=...
            val body = JsonObject().apply {
                addProperty("Rating", rating.coerceIn(1, 5))
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/Users/$userId/Items/$songId/Rating?api_key=$apiToken")
                .header("X-Emby-Authorization", buildAuthHeader())
                .post(body.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "setRating failed", e)
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
                    name = EncodingUtils.fixEncoding(obj.get("Name")?.asString) ?: "Unknown",
                    songCount = obj.get("SongCount")?.asInt?.coerceAtLeast(0)
                        ?: obj.get("ChildCount")?.asInt ?: 0
                )
            }
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getGenres failed", e)
            emptyList()
        }
    }

    override suspend fun getSongsByGenre(genre: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks"
            val encodedGenre = java.net.URLEncoder.encode(genre, "UTF-8")
            val allSongs = mutableListOf<Song>()
            var startIndex = 0
            val pageSize = 1000

            while (true) {
                val url = "$baseUrl/Items?IncludeItemTypes=Audio&Recursive=true&" +
                        "fields=$fields&UserId=$userId&Genres=$encodedGenre&" +
                        "StartIndex=$startIndex&Limit=$pageSize"
                val json = executeJsonRequest(url) ?: break
                val items = json.getAsJsonArray("Items") ?: break
                for (item in items) {
                    jsonObjectToSong(item.asJsonObject, null)?.let { allSongs.add(it) }
                }
                if (items.size() < pageSize) break
                startIndex += pageSize
            }
            allSongs
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getSongsByGenre failed", e)
            emptyList()
        }
    }

    override suspend fun getSongsByYearRange(fromYear: Int, toYear: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks"
            // Jellyfin Years 参数支持逗号分隔的多个年份
            val years = (fromYear..toYear).joinToString(",")
            val allSongs = mutableListOf<Song>()
            var startIndex = 0
            val pageSize = 1000

            while (true) {
                val url = "$baseUrl/Items?IncludeItemTypes=Audio&Recursive=true&" +
                        "fields=$fields&UserId=$userId&Years=$years&" +
                        "StartIndex=$startIndex&Limit=$pageSize"
                val json = executeJsonRequest(url) ?: break
                val items = json.getAsJsonArray("Items") ?: break
                for (item in items) {
                    jsonObjectToSong(item.asJsonObject, null)?.let { allSongs.add(it) }
                }
                if (items.size() < pageSize) break
                startIndex += pageSize
            }
            allSongs
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getSongsByYearRange failed", e)
            emptyList()
        }
    }

    // --- Scrobble ---
    override suspend fun scrobblePlay(songId: String, timestamp: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            // Jellyfin API: POST /Sessions/Playing 报告播放开始
            val body = JsonObject().apply {
                addProperty("ItemId", songId)
                addProperty("PositionTicks", timestamp * 10000)
                addProperty("PlayMethod", "DirectPlay")
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/Sessions/Playing")
                .header("X-Emby-Authorization", buildAuthHeader())
                .post(body.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "scrobblePlay failed", e)
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
            AppLog.e("JellyfinAdapter", "getRandomSongs failed", e)
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
                AppLog.d("JellyfinAdapter", "logout: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            AppLog.w("JellyfinAdapter", "logout failed", e)
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
            AppLog.d("JellyfinAdapter", "close: OkHttp resources released")
        } catch (e: Exception) {
            AppLog.w("JellyfinAdapter", "close failed", e)
        }
    }

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

    // 从原始字节检测编码并解码：优先 UTF-8，若出现 U+FFFD 或可疑字符则回退 GBK
    private fun Response.utf8Body(): String? {
        val rawBytes = body?.bytes() ?: return null
        // 尝试 UTF-8 解码
        val utf8 = try { String(rawBytes, Charsets.UTF_8) } catch (_: Exception) { return null }
        // 检测 UTF-8 解码结果是否可信：
        // - U+FFFD → 无效 UTF-8
        // - 希腊/西里尔字母 → GBK→UTF-8 误解码的典型特征
        val hasReplacement = '\uFFFD' in utf8
        val hasGreek = utf8.any { it.code in 0x0370..0x03FF }
        val hasCyrillic = utf8.any { it.code in 0x0400..0x04FF }

        val needsGbkFallback = hasReplacement || hasGreek || hasCyrillic

        // 记录原始字节的前20个字节（用于调试编码问题）
        val bytesHex = rawBytes.take(40).joinToString(" ") { "%02X".format(it) }
        AppLog.d("NASMusic", "utf8Body: rawBytes[0..39]=${bytesHex}")
        AppLog.d("NASMusic", "utf8Body: flags: replacement=$hasReplacement greek=$hasGreek cyrillic=$hasCyrillic → needsGbk=$needsGbkFallback")

        if (!needsGbkFallback) {
            AppLog.d("NASMusic", "utf8Body: UTF-8 OK, first50='${utf8.take(50)}'")
            return utf8
        }

        // 尝试 GBK 解码（Jellyfin 服务端 ID3 标签可能以 GBK 存储）
        val gbk = try { String(rawBytes, Charset.forName("GBK")) } catch (_: Exception) { null }
        if (gbk != null && (gbk.any { it.code in 0x4E00..0x9FFF } || '\uFFFD' !in gbk)) {
            AppLog.d("NASMusic", "utf8Body: GBK fallback: utf8='${utf8.take(20)}' → gbk='${gbk.take(20)}'")
            return gbk
        }
        AppLog.d("NASMusic", "utf8Body: GBK fallback failed, keeping UTF-8")
        return utf8
    }

    private suspend fun executeJsonRequest(url: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            withRetry(
                config = RetryConfig(maxAttempts = 3, baseDelayMs = 500L),
                onError = { attempt, e ->
                    AppLog.w("JellyfinAdapter", "executeJsonRequest retry attempt=$attempt for $url", e)
                }
            ) {
                val request = Request.Builder()
                    .url(url)
                    .header("X-Emby-Authorization", buildAuthHeader())
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.utf8Body()
                        if (!body.isNullOrBlank()) {
                            gson.fromJson(body, JsonObject::class.java)
                        } else null
                    } else {
                        AppLog.w("JellyfinAdapter", "executeJsonRequest: ${response.code} for $url")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "executeJsonRequest failed for $url", e)
            null
        }
    }

    private fun jsonObjectToSong(obj: JsonObject, albumIdOverride: String?): Song? {
        val id = obj.get("Id")?.asString ?: return null
        val rawTitle = obj.get("Name")?.asString
        val rawAlbum = obj.get("Album")?.asString
        val rawArtist = obj.getAsJsonArray("Artists")?.firstOrNull()?.asString
        val title = EncodingUtils.fixEncoding(rawTitle) ?: "Unknown"
        val artist = EncodingUtils.fixEncoding(rawArtist) ?: EncodingUtils.fixEncoding(obj.get("AlbumArtist")?.asString) ?: ""
        val album = EncodingUtils.fixEncoding(rawAlbum) ?: ""
        val albumId = albumIdOverride ?: obj.get("AlbumId")?.asString ?: ""
        val trackNumber = obj.get("IndexNumber")?.asInt ?: 0
        val discNumber = obj.get("ParentIndexNumber")?.asInt ?: 1
        val year = obj.get("ProductionYear")?.asInt
        val runTimeTicks = obj.get("RunTimeTicks")?.asLong ?: 0L
        val durationMs = runTimeTicks / 10000
        val imageTag = obj.get("ImageTags")?.asJsonObject?.get("Primary")?.asString
        val genreArr = obj.getAsJsonArray("Genres")
        val genre = EncodingUtils.fixEncoding(genreArr?.firstOrNull()?.asString)
        // 解析 artistId（从 ArtistItems 数组取第一个），用于封面候选列表
        val artistItems = obj.getAsJsonArray("ArtistItems")
        val artistId = artistItems?.firstOrNull()?.asJsonObject?.get("Id")?.asString

        // 调试日志：检查原始数据和修复后的数据
        AppLog.d("JellyfinAdapter", "jsonObjectToSong: id=$id")
        AppLog.d("JellyfinAdapter", "  rawTitle='${rawTitle?.take(30)}' fixedTitle='${title.take(30)}'")
        AppLog.d("JellyfinAdapter", "  rawAlbum='${rawAlbum?.take(30)}' fixedAlbum='${album.take(30)}'")
        AppLog.d("JellyfinAdapter", "  rawArtist='${rawArtist?.take(30)}' fixedArtist='${artist.take(30)}'")
        AppLog.d("JellyfinAdapter", "  runTimeTicks=$runTimeTicks durationMs=$durationMs")

        // 封面 URL 构造逻辑：
        // - imageTag 来自歌曲自身的 ImageTags.Primary，必须用歌曲 id 构造 URL（tag 与 itemId 必须匹配）
        // - 如果歌曲没有 Primary 图片 tag，回退到专辑 id 请求封面（专辑通常有封面）
        // - 如果专辑 id 也为空，用歌曲 id 请求（Jellyfin 会返回 404 或默认图）
        val coverUrl = if (imageTag != null) {
            buildCoverUrl(id, imageTag)
        } else {
            getCoverUrl(albumId.ifBlank { id })
        }

        return Song(
            id = id,
            title = title,
            artist = artist,
            artistId = artistId,
            album = album,
            albumId = albumId,
            coverUrl = coverUrl,
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
                val body = response.utf8Body() ?: return@use null
                if (!response.isSuccessful) {
                    AppLog.w("JellyfinAdapter", "authenticateByName: HTTP ${response.code} for $baseUrl/Users/AuthenticateByName, body=${body.take(200)}")
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
            AppLog.e("JellyfinAdapter", "authenticateByName failed", e)
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
                val name = EncodingUtils.fixEncoding(json.get("Name")?.asString) ?: "Jellyfin"
                Pair(uid, name)
            }
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "fetchCurrentUserInfo failed", e)
            null
        }
    }
}

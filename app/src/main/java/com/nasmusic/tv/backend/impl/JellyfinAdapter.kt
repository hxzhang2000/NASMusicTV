package com.nasmusic.tv.backend.impl

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongTechnicalInfo
import com.nasmusic.tv.data.model.VersionInfo
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.EncodingUtils
import com.nasmusic.tv.util.RetryConfig
import com.nasmusic.tv.util.UrlSanitizer
import com.nasmusic.tv.util.withRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.net.HttpURLConnection
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Jellyfin 后端适配器
 * 使用 Jellyfin HTTP API（版本 10.7+）
 */
class JellyfinAdapter : BackendAdapter {

    override val backendType: String = "jellyfin"
    override var apiVersion: String = "Jellyfin (版本未知)"

    private var baseUrl: String = ""

    /**
     * S4（2026-09-14）：加 `@Volatile`。
     * 写点：`initialize()` / [reauthenticateIfNeeded]（均在 IO 线程）。
     * 读点：`buildAuthHeader()`（IO 线程）**以及** [getStreamUrl] / [getCoverUrl]
     * 这两个非 suspend 方法（可能被主线程调用）——跨线程可见性需要 volatile 保证。
     */
    @Volatile
    private var apiToken: String = ""
    private var userId: String = ""
    override var serverName: String = "Jellyfin"
        private set

    // ── S4：会话内 401 重认证所需状态 ──────────────────────────────────
    /**
     * 登录凭据，由 [initialize] 注入（该方法的调用方 `BackendRegistry` 始终传入
     * `ServerConfig.username/password`，两条初始化路径——复用已有 token 与用户名密码
     * 登录——都会走到）。仅驻留内存，[logout] / [close] 时清空。
     *
     * 取舍说明：内存中保留明文口令是重认证的必要代价（本 adapter 无 `Context`，
     * 无法按需从 `AppPreferences` 解密读取）。风险有界——口令本就以明文经 `ServerConfig`
     * 传入本类，且仅存活于单次会话；持久化副本仍是 `CryptoUtils` 加密的。
     */
    @Volatile
    private var username: String = ""
    @Volatile
    private var password: String = ""

    /** 同一时刻只允许一次重认证在途：401 风暴下避免并发重复登录 */
    private val reauthMutex = Mutex()

    /**
     * token 世代号，重认证成功即自增。
     * 并发的多个请求同时撞 401 时，只有第一个真正发起登录，其余在拿到锁后发现
     * 世代已变，直接复用新 token 重试——即「N 个 401 只触发 1 次登录」。
     */
    @Volatile
    private var tokenGeneration: Int = 0

    /** S4：单次请求的「状态码 + body」，供 401 判定与重试复用 */
    private data class HttpResult(val code: Int, val body: JsonObject?)

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        // R-6：注入共享连接池/Dispatcher（BackendRegistry 持有，切后端不再累积线程池）
        OkHttpClient.Builder()
            .apply {
                // 日志拦截器仅在 debug 构建启用，避免 release 中 URL（含 api_key token）写入 logcat
                if (com.nasmusic.tv.BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .connectionPool(com.nasmusic.tv.backend.BackendRegistry.sharedConnectionPool)
            .dispatcher(com.nasmusic.tv.backend.BackendRegistry.sharedDispatcher)
            .build()
    }

    override suspend fun initialize(
        baseUrl: String,
        apiToken: String,
        username: String,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        this@JellyfinAdapter.baseUrl = baseUrl.removeSuffix("/")

        // S4：留存凭据供会话内 401 重认证使用（不落日志，F-1 约定）
        this@JellyfinAdapter.username = username
        this@JellyfinAdapter.password = password

        // 优先使用已有 token
        if (apiToken.isNotBlank()) {
            this@JellyfinAdapter.apiToken = apiToken
            val userInfo = fetchCurrentUserInfo()
            if (userInfo != null) {
                userId = userInfo.first
                serverName = userInfo.second
                fetchServerVersion()
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
                fetchServerVersion()
                return@withContext true
            }
        }

        false
    }

    /** 从 /System/Info/Public 获取服务端版本号 */
    private fun fetchServerVersion() {
        try {
            val request = Request.Builder()
                .url("$baseUrl/System/Info/Public")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return
                    val json = JsonParser.parseString(body).asJsonObject
                    val version = json.get("Version")?.asString
                    if (!version.isNullOrBlank()) apiVersion = "Jellyfin API $version"
                }
            }
        } catch (_: Exception) {}
    }

    override suspend fun getApiVersion(): VersionInfo = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/System/Info/Public")
                .build()
            val result = client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use VersionInfo.Disconnected("Jellyfin")
                    val json = JsonParser.parseString(body).asJsonObject
                    val version = json.get("Version")?.asString ?: "未知"
                    VersionInfo.Runtime("Jellyfin", version, "/System/Info/Public", System.currentTimeMillis())
                } else {
                    VersionInfo.Disconnected("Jellyfin")
                }
            }
            result
        } catch (e: Exception) {
            AppLog.w("JellyfinAdapter", "getApiVersion failed", e)
            VersionInfo.Disconnected("Jellyfin")
        }
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
            var maxPages = MAX_PAGES

            while (maxPages-- > 0) {
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
                    val imageTags = obj.get("ImageTags")?.asJsonObject
                    val primaryTag = imageTags?.get("Primary")?.asString
                    val backdropTags = obj.get("BackdropImageTags")?.asJsonArray
                    // Primary tag → Backdrop → null（交给 AlbumCoverResolver 兜底）
                    val coverUrl = when {
                        primaryTag != null -> buildCoverUrl(id, primaryTag)
                        backdropTags != null && backdropTags.size() > 0 ->
                            "$baseUrl/Items/$id/Images/Backdrop/0?maxWidth=512&quality=90&api_key=$apiToken"
                        else -> null
                    }

                    allAlbums.add(
                        Album(
                            id = id,
                            name = name,
                            artist = artist,
                            coverUrl = coverUrl,
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
            var maxPages = MAX_PAGES

            while (maxPages-- > 0) {
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
                    val imageTags = obj.get("ImageTags")?.asJsonObject
                    val primaryTag = imageTags?.get("Primary")?.asString
                    val backdropTags = obj.get("BackdropImageTags")?.asJsonArray
                    // 多级回退：Primary tag → Backdrop → null（交给 ArtistCoverResolver 在线源+歌曲封面兜底）
                    // 不再用 getCoverUrl(id) 兜底——该方法对无图艺术家也返回 URL（Jellyfin 返回 404），
                    // 导致 ArtistCoverResolver 因 coverUrl != null 而跳过所有缺图艺术家。
                    val coverUrl = when {
                        primaryTag != null -> buildCoverUrl(id, primaryTag)
                        backdropTags != null && backdropTags.size() > 0 ->
                            "$baseUrl/Items/$id/Images/Backdrop/0?maxWidth=512&quality=90&api_key=$apiToken"
                        else -> null
                    }
                    AppLog.d("NASMusic", "getArtists: raw='${rawName?.take(30)}' fixed='${name.take(30)}' hasCover=${coverUrl != null}")
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

    override suspend fun getArtistSongs(artistId: String, artistName: String?): List<Song> = withContext(Dispatchers.IO) {
        try {
            val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks"
            // 优先按名称查询（Artists 参数匹配 Artists 字符串数组，比 ArtistIds 更可靠）
            // 当 ArtistIds 不匹配时（常见于 AlbumArtist 与 ArtistItems ID 不一致），按名称能正确返回
            val url = if (artistName != null) {
                "$baseUrl/Items?" +
                        "Artists=${java.net.URLEncoder.encode(artistName, "UTF-8")}&" +
                        "IncludeItemTypes=Audio&" +
                        "Recursive=true&" +
                        "fields=$fields&" +
                        "UserId=$userId&" +
                        "SortBy=SortName&SortOrder=Ascending&" +
                        "StartIndex=0&Limit=1000"
            } else {
                "$baseUrl/Items?" +
                        "ArtistIds=$artistId&" +
                        "IncludeItemTypes=Audio&" +
                        "Recursive=true&" +
                        "fields=$fields&" +
                        "UserId=$userId&" +
                        "SortBy=SortName&SortOrder=Ascending&" +
                        "StartIndex=0&Limit=1000"
            }

            val json = executeJsonRequest(url) ?: return@withContext emptyList<Song>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Song>()
            val songs = items.mapNotNull { jsonObjectToSong(it.asJsonObject, null) }
            val label = if (artistName != null) "$artistId|$artistName" else artistId
            AppLog.d("JellyfinAdapter", "getArtistSongs($label): items=${items.size()} songs=${songs.size}")
            songs.take(3).forEach { s ->
                AppLog.d("JellyfinAdapter", "  -> artist='${s.artist}' title='${s.title}'")
            }
            songs
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
                    "Limit=1"
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
            // 主题 C②（2026-09-22 审查，待确认中）：/Items/Filters 为 Emby 旧端点，
            // Jellyfin 现行为 /Items/Filters2。端点暂不变更，先补诊断日志：真机上若
            // 「年代」恒空且出现本行日志，即可确认 404 并切换端点。
            val json = executeJsonRequest(url) ?: run {
                AppLog.w("JellyfinAdapter", "getYears: /Items/Filters null (endpoint may not exist) url=$url")
                return@withContext emptyList()
            }
            val yearsArray = json.getAsJsonArray("Years") ?: run {
                AppLog.w("JellyfinAdapter", "getYears: response has no Years array (endpoint legacy?) url=$url")
                return@withContext emptyList()
            }
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

            // Jellyfin 的 SearchTerm 只搜索 Name/SortName 字段，不搜索 Artists 字段。
            // 需要两条查询并行：
            //   1. SearchTerm=赵传 → 歌名包含"赵传"的歌曲
            //   2. Artists=赵传    → 艺术家为"赵传"的歌曲（Jellyfin 对 Artists 数组逐项精确匹配）
            val nameSearchUrl = "$baseUrl/Items?" +
                    "SearchTerm=$encodedQuery&" +
                    "IncludeItemTypes=Audio&" +
                    "Recursive=true&" +
                    "fields=$fields&" +
                    "UserId=$userId&" +
                    "StartIndex=0&Limit=200"

            val artistSearchUrl = "$baseUrl/Items?" +
                    "Artists=$encodedQuery&" +
                    "IncludeItemTypes=Audio&" +
                    "Recursive=true&" +
                    "fields=$fields&" +
                    "UserId=$userId&" +
                    "StartIndex=0&Limit=200"

            // 并行发起两条查询
            val nameResults = mutableListOf<Song>()
            val artistResults = mutableListOf<Song>()

            val jobs = listOf(
                async {
                    val json = executeJsonRequest(nameSearchUrl)
                    val items = json?.getAsJsonArray("Items")
                    items?.mapNotNull { jsonObjectToSong(it.asJsonObject, null) }
                        ?.let { nameResults.addAll(it) }
                },
                async {
                    val json = executeJsonRequest(artistSearchUrl)
                    val items = json?.getAsJsonArray("Items")
                    items?.mapNotNull { jsonObjectToSong(it.asJsonObject, null) }
                        ?.let { artistResults.addAll(it) }
                }
            )
            jobs.forEach { it.await() }

            // 合并去重（按 song id 去重，歌名搜索结果优先）
            val seen = mutableSetOf<String>()
            val merged = mutableListOf<Song>()
            for (song in (nameResults + artistResults)) {
                if (song.id !in seen) {
                    seen.add(song.id)
                    merged.add(song)
                }
            }
            AppLog.d("JellyfinAdapter", "searchSongs: nameSearch=${nameResults.size}, artistSearch=${artistResults.size}, merged=${merged.size}")
            merged
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

    override suspend fun getSongTechnicalInfo(songId: String): SongTechnicalInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/Items/$songId?api_key=$apiToken"
            val json = executeJsonRequest(url) ?: return@withContext null
            val streams = json.getAsJsonArray("MediaStreams") ?: return@withContext null

            // 查找第一个音频流
            for (i in 0 until streams.size()) {
                val stream = streams[i].asJsonObject
                val type = stream.get("Type")?.asString ?: continue
                if (type != "Audio") continue

                val codec = stream.get("Codec")?.asString ?: ""
                val bitrate = stream.get("BitRate")?.asInt ?: 0
                val sampleRate = stream.get("SampleRate")?.asInt ?: 0
                val channels = stream.get("Channels")?.asInt ?: 0

                // 容器格式（从主对象获取）
                val container = json.get("Container")?.asString ?: ""

                val size = json.get("Size")?.asLong ?: 0L
                val runTimeTicks = json.get("RunTimeTicks")?.asLong ?: 0L

                return@withContext SongTechnicalInfo(
                    codec = codec.uppercase(),
                    bitrate = bitrate / 1000,
                    sampleRate = sampleRate,
                    channels = channels,
                    fileSize = size,
                    durationMs = runTimeTicks / 10000,
                    format = container.uppercase()
                )
            }
            return@withContext null
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getSongTechnicalInfo failed", e)
            null
        }
    }

    override fun getStreamUrl(songId: String): String =
        // 主题 C①决策落地（2026-09-22 审查，用户选方案 B）：stream.mp3 容器后缀
        // 会强制服务端把原始流转码为 mp3（FLAC 库音质损失 + 无谓转码会话）。
        // 改 universal 端点：Container 列出可直通容器（FLAC/mp3/m4a 等原样直播），
        // 清单外冷门格式回落服务端默认转码。
        // 真机回归点：FLAC/mp3/m4a 各验一首；个别老版本 Jellyfin 对 universal
        // 行为异常时回退本提交即可。
        "$baseUrl/Audio/$songId/universal?api_key=$apiToken" +
            "&Container=flac,mp3,m4a,aac,ogg,opus,wav,webma"

    override fun getCoverUrl(songId: String): String {
        val url = "$baseUrl/Items/$songId/Images/Primary?maxWidth=512&quality=90&api_key=$apiToken"
        AppLog.d("JellyfinAdapter", "getCoverUrl: item=$songId")
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
            // Jellyfin API: GET /Items?IncludeItemTypes=Playlist 获取播放列表（全量）
            val url = "$baseUrl/Items?IncludeItemTypes=Playlist&Recursive=true&" +
                    "Fields=SortName&UserId=$userId&SortBy=SortName&SortOrder=Ascending&" +
                    "StartIndex=0&Limit=10000"
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

    override suspend fun getPlaylistSongs(playlistId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            // Jellyfin API: GET /Playlists/{playlistId}/Items 获取播放列表中的歌曲
            val url = "$baseUrl/Playlists/$playlistId/Items?UserId=$userId"
            val json = executeJsonRequest(url) ?: return@withContext emptyList<Song>()
            val items = json.getAsJsonArray("Items") ?: return@withContext emptyList<Song>()
            items.mapNotNull { jsonObjectToSong(it.asJsonObject, playlistId) }
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "getPlaylistSongs failed", e)
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
                add("Ids", gson.toJsonTree(listOf(songId)))
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
    override suspend fun toggleFavorite(songId: String, isCurrentlyFavorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            // 直接使用调用方传入的本地收藏状态，不再调用 queryFavoriteStatus 做二次查询。
            // queryFavoriteStatus 在 UserData 为 null / 网络超时时会错误返回 false，
            // 导致取消收藏时发成 POST（加收藏），永远无法取消。
            val requestBuilder = Request.Builder()
                .url("$baseUrl/Users/$userId/FavoriteItems/$songId")
                .header("X-Emby-Authorization", buildAuthHeader())

            val request = if (isCurrentlyFavorite) {
                // 当前已收藏 → DELETE 取消收藏
                requestBuilder.delete().build()
            } else {
                // 当前未收藏 → POST 添加收藏
                requestBuilder.post("".toRequestBody(null)).build()
            }

            client.newCall(request).execute().use { response ->
                AppLog.d("JellyfinAdapter", "toggleFavorite: ${if (isCurrentlyFavorite) "DELETE" else "POST"} HTTP ${response.code} for $songId")
                response.isSuccessful
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
            var maxPages = MAX_PAGES

            while (maxPages-- > 0) {
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
            // rating 在 query param 中传递，不需要 request body
            val request = Request.Builder()
                .url("$baseUrl/Users/$userId/Items/$songId/Rating?rating=${rating.coerceIn(1, 5)}")
                .header("X-Emby-Authorization", buildAuthHeader())
                .post("".toRequestBody(null))
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
            var maxPages = MAX_PAGES

            while (maxPages-- > 0) {
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
            var maxPages = MAX_PAGES

            while (maxPages-- > 0) {
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
            // 清空凭据防止后续误用（S4：登录凭据一并清空）
            apiToken = ""
            userId = ""
            username = ""
            password = ""
        }
    }

    /**
     * 释放 OkHttp 连接资源。
     * logout() 处理服务端 session，此处关闭客户端连接池，防止连接泄漏。
     */
    override fun close() {
        // R-6：连接池/线程池已共享（BackendRegistry 持有），此处禁止 shutdown/evictAll
        //（否则第一次切换后端就会废掉全局线程池），只清理自身认证态。
        try {
            baseUrl = ""
            apiToken = ""
            userId = ""
            // S4：登录凭据随认证态一并清空，避免 adapter 被替换后仍驻留口令
            username = ""
            password = ""
            AppLog.d("JellyfinAdapter", "close: auth state cleared (shared OkHttp pool retained)")
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

    // 从原始字节检测编码并解码：优先 UTF-8，仅当出现 U+FFFD 时回退 GBK
    private fun Response.utf8Body(): String? {
        val rawBytes = body?.bytes() ?: return null
        // 尝试 UTF-8 解码
        val utf8 = try { String(rawBytes, Charsets.UTF_8) } catch (_: Exception) { return null }
        // 仅当出现 U+FFFD（无效 UTF-8 替换字符）时触发 GBK 回退
        // 希腊/西里尔字母可能在音乐元数据中合法存在（希腊艺术家、俄罗斯乐队名），不应触发回退
        val hasReplacement = '\uFFFD' in utf8

        if (!hasReplacement) {
            return utf8
        }

        // 尝试 GBK 解码（Jellyfin 服务端 ID3 标签可能以 GBK 存储）
        val gbk = try { String(rawBytes, Charset.forName("GBK")) } catch (_: Exception) { null }
        if (gbk != null && '\uFFFD' !in gbk) {
            // 标记哪些响应触发了 GBK 回退，便于排查编码问题
            // 注意：AGENTS.md 已说明部分双重编码情况不可恢复
            AppLog.d("JellyfinAdapter", "utf8Body: GBK fallback applied for ${request.url} (U+FFFD detected in UTF-8)")
            return gbk
        }
        AppLog.w("JellyfinAdapter", "utf8Body: U+FFFD present but GBK fallback failed for ${UrlSanitizer.sanitize(request.url.toString())}, using UTF-8 as-is")
        return utf8
    }

    /**
     * 集中式 GET-JSON 请求（全部读路径的唯一出口）。
     *
     * **S4（2026-09-14）**：会话内 401 自愈。原先 401 与「任何其他失败」一样被折叠成
     * `null`，调用方只看到空列表——服务器强制过期 token / 用户改密后，必须手动断开重连
     * 才能恢复。现在改为：401 → 重认证一次 → 重试原请求一次。
     *
     * 防循环设计（报告 §S4 明确要求「重试只做一次、避免循环」）：
     * 1. 重试是**同一调用内的第二次尝试**（`rawJsonRequest` 调两次），不是递归调用；
     * 2. 第二次请求无论返回什么（含再次 401）都直接返回，不再触发第三次；
     * 3. [authenticateByName] 走 `client.newCall` 直连，**不经过本方法** → 不存在
     *    「重认证本身 401 → 又触发重认证」的自激路径；
     * 4. 无凭据（token-only 会话）时直接放弃，不做无意义的登录尝试。
     */
    private suspend fun executeJsonRequest(url: String): JsonObject? = withContext(Dispatchers.IO) {
        val genAtStart = tokenGeneration
        val first = rawJsonRequest(url)
        if (first.code != HttpURLConnection.HTTP_UNAUTHORIZED) {
            return@withContext first.body
        }

        if (reauthenticateIfNeeded(genAtStart) == null) {
            AppLog.w("JellyfinAdapter", "executeJsonRequest: 401 且重认证不可用/失败，放弃 ${UrlSanitizer.sanitize(url)}")
            return@withContext null
        }
        AppLog.i("JellyfinAdapter", "executeJsonRequest: 401 → 重认证成功，重试一次 ${UrlSanitizer.sanitize(url)}")
        // 第二次尝试：结果照单全收，不再判定 401（防循环，见上方设计说明）
        rawJsonRequest(url).body
    }

    /**
     * 单次 GET-JSON（内含 [withRetry] 的**网络层**重试：超时/IO 异常 3 次退避）。
     * 与 [executeJsonRequest] 的区别：不处理 401，只如实回传状态码与 body。
     */
    private suspend fun rawJsonRequest(url: String): HttpResult = withContext(Dispatchers.IO) {
        try {
            withRetry(
                config = RetryConfig(maxAttempts = 3, baseDelayMs = 500L),
                onError = { attempt, e ->
                    AppLog.w("JellyfinAdapter", "executeJsonRequest retry attempt=$attempt for ${UrlSanitizer.sanitize(url)}", e)
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
                        HttpResult(
                            response.code,
                            if (!body.isNullOrBlank()) gson.fromJson(body, JsonObject::class.java) else null
                        )
                    } else {
                        AppLog.w("JellyfinAdapter", "executeJsonRequest: ${response.code} for ${UrlSanitizer.sanitize(url)}")
                        HttpResult(response.code, null)
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("JellyfinAdapter", "executeJsonRequest failed for ${UrlSanitizer.sanitize(url)}", e)
            HttpResult(-1, null)
        }
    }

    /**
     * S4：按需重认证并更新内存 token。
     *
     * @param genAtStart 调用方发起请求时观察到的 [tokenGeneration]
     * @return 可用的 token；无法重认证时返回 `null`
     *
     * 并发语义：多个请求同时撞 401 时，只有一个真正执行登录（持 [reauthMutex]），
     * 其余在拿到锁后发现世代已变，直接复用新 token —— 即「N 个 401 只触发 1 次登录」。
     */
    private suspend fun reauthenticateIfNeeded(genAtStart: Int): String? {
        if (username.isBlank()) {
            // token-only 会话（用户只填了 token、没存用户名密码）无法重认证
            return null
        }
        return reauthMutex.withLock {
            if (tokenGeneration != genAtStart) {
                // 并发的其他请求已完成重认证，直接复用其新 token，不再打一次登录请求
                return@withLock apiToken
            }
            val result = authenticateByName(username, password) ?: return@withLock null
            apiToken = result.first
            userId = result.second
            serverName = result.third
            tokenGeneration++
            // F-1：token 属敏感值，只记世代号不记内容
            AppLog.i("JellyfinAdapter", "401 重认证成功（token 世代 → $tokenGeneration）")
            apiToken
        }
    }

    private fun jsonObjectToSong(obj: JsonObject, albumIdOverride: String?): Song? {
        val id = obj.get("Id")?.asString ?: return null
        val rawTitle = obj.get("Name")?.asString
        val rawAlbum = obj.get("Album")?.asString
        val rawArtist = obj.getAsJsonArray("Artists")?.mapNotNull { it?.asString }?.joinToString(", ")
                    ?.takeIf { it.isNotBlank() }
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
                    // F-1：错误响应 body 可能回显请求体（含密码），不落入日志
                    AppLog.w("JellyfinAdapter", "authenticateByName: HTTP ${response.code} for ${UrlSanitizer.sanitize(baseUrl)}/Users/AuthenticateByName")
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
                val body = response.utf8Body() ?: return@use null
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

    companion object {
        private const val MAX_PAGES = 1000
    }
}

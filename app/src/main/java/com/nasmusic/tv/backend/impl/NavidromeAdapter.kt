package com.nasmusic.tv.backend.impl

import com.google.gson.Gson
import com.google.gson.JsonObject
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Navidrome 后端适配器
 * 使用 Subsonic API（Navidrome 完全兼容）
 */
class NavidromeAdapter : BackendAdapter {

    override val backendType: String = "navidrome"
    override var apiVersion: String = "Navidrome (版本未知)"

@Volatile
    private var baseUrl: String = ""
@Volatile
    private var username: String = ""
@Volatile
    private var password: String = ""
@Volatile
    private var apiToken: String = ""
    override var serverName: String = "Navidrome"
        private set

    // B11 修复：固定 salt + token，避免 buildCoverUrl 每次生成新 salt
    // 导致封面 URL 不稳定、Coil 缓存 key 失效、同一封面反复下载。
@Volatile
    private var salt: String = ""

    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        // R-6：注入共享连接池/Dispatcher（BackendRegistry 持有，切后端不再累积线程池）
        OkHttpClient.Builder()
            .apply {
                // 日志拦截器仅在 debug 构建启用，避免 release 中 URL 写入 logcat
                if (com.nasmusic.tv.BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectionPool(com.nasmusic.tv.backend.BackendRegistry.sharedConnectionPool)
            .dispatcher(com.nasmusic.tv.backend.BackendRegistry.sharedDispatcher)
            .build()
    }

    /** R-10：Subsonic 公共层（认证/URL 构造/请求执行），初始化时同步认证态 */
    private val restClient = SubsonicRestClient("NavidromeAdapter", client)

    override suspend fun initialize(
        baseUrl: String,
        apiToken: String,
        username: String,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        this@NavidromeAdapter.baseUrl = baseUrl.removeSuffix("/")
        this@NavidromeAdapter.username = username
        this@NavidromeAdapter.password = password
        this@NavidromeAdapter.apiToken = apiToken
        // B11：初始化时固定 salt，与 Subsonic 一致，保证封面/流 URL 稳定可缓存
        // （token = md5(password + salt) 在 buildRestUrl 内现场计算，无需预存）
        this@NavidromeAdapter.salt = System.currentTimeMillis().toString()
        // R-10：公共层同步认证态
        restClient.baseUrl = this@NavidromeAdapter.baseUrl
        restClient.username = username
        restClient.password = password
        restClient.salt = this@NavidromeAdapter.salt

        testConnection()
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("ping")
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use false
                if (!response.isSuccessful) return@use false
                val json = gson.fromJson(body, JsonObject::class.java)
                val subsonic = json.getAsJsonObject("subsonic-response") ?: return@use false
                val status = subsonic.get("status")?.asString ?: return@use false
                val version = subsonic.get("version")?.asString ?: ""
                if (version.isNotBlank()) {
                    serverName = "Navidrome $version"
                    apiVersion = "Subsonic API $version"
                }
                status == "ok"
            }
        } catch (e: Exception) {
            AppLog.w("NavidromeAdapter", "testConnection failed", e)
            false
        }
    }

    override suspend fun getApiVersion(): VersionInfo = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("ping")
            val request = Request.Builder().url(url).build()
            val result = client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use VersionInfo.Disconnected("Navidrome")
                if (!response.isSuccessful) return@use VersionInfo.Disconnected("Navidrome")
                val json = gson.fromJson(body, JsonObject::class.java)
                val subsonic = json.getAsJsonObject("subsonic-response") ?: return@use VersionInfo.Disconnected("Navidrome")
                val status = subsonic.get("status")?.asString ?: return@use VersionInfo.Disconnected("Navidrome")
                val version = subsonic.get("version")?.asString ?: ""
                if (status == "ok" && version.isNotBlank()) {
                    VersionInfo.Runtime("Navidrome", version, "rest/ping.view", System.currentTimeMillis())
                } else {
                    VersionInfo.Disconnected("Navidrome")
                }
            }
            result
        } catch (e: Exception) {
            AppLog.w("NavidromeAdapter", "getApiVersion failed", e)
            VersionInfo.Disconnected("Navidrome")
        }
    }

    override suspend fun getAlbums(): List<Album> = withContext(Dispatchers.IO) {
        try {
            // B10 修复：原硬编码 size=500 无分页，超过 500 张专辑的用户会丢专辑。
            // 改为按页循环拉取（每页 500），直到返回不足一页或达到安全上限。
            val pageSize = 500
            val allAlbums = mutableListOf<Album>()
            var offset = 0
            var maxPages = 100 // 安全上限：最多 5 万张专辑，防止异常循环

            while (maxPages-- > 0) {
                val url = buildRestUrl("getAlbumList2") +
                        "&type=alphabeticalByName&size=$pageSize&offset=$offset"

                val json = executeRequest(url) ?: break
                val subsonic = json.getAsJsonObject("subsonic-response")
                val albumList = subsonic?.getAsJsonObject("albumList2")
                val albums = albumList?.getAsJsonArray("album") ?: break

                if (albums.size() == 0) break

                for (i in 0 until albums.size()) {
                    val obj = albums[i].asJsonObject
                    val id = obj.get("id")?.asString ?: continue
                    val name = EncodingUtils.fixEncoding(obj.get("name")?.asString) ?: "Unknown Album"
                    val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: ""
                    val year = obj.get("year")?.asInt
                    val songCount = obj.get("songCount")?.asInt ?: 0
                    val durationSec = obj.get("duration")?.asLong ?: 0L

                    allAlbums.add(
                        Album(
                            id = id,
                            name = name,
                            artist = artist,
                            coverUrl = buildCoverUrl(id),
                            year = year,
                            songCount = songCount,
                            durationMs = durationSec * 1000
                        )
                    )
                }

                // 本页不足一页 → 已到末页，停止
                if (albums.size() < pageSize) break
                offset += pageSize
            }
            allAlbums
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getAlbums failed", e)
            emptyList()
        }
    }

    override suspend fun getAlbumSongs(albumId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getAlbum") + "&id=$albumId"

            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val album = subsonic?.getAsJsonObject("album")
            val songs = album?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()

            val albumName = EncodingUtils.fixEncoding(album.get("name")?.asString) ?: ""
            val albumArtist = EncodingUtils.fixEncoding(album.get("artist")?.asString) ?: ""

            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown"
                val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: albumArtist
                val track = obj.get("track")?.asInt ?: 0
                val disc = obj.get("discNumber")?.asInt ?: 1
                val year = obj.get("year")?.asInt
                val durationSec = obj.get("duration")?.asLong ?: 0L
                val bitrate = obj.get("bitRate")?.asInt ?: 0
                val coverId = obj.get("coverArt")?.asString ?: ""

                Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = albumName,
                    albumId = albumId,
                    coverUrl = if (coverId.isNotBlank()) buildCoverUrl(coverId) else null,
                    streamUrl = getStreamUrl(id),
                    durationMs = durationSec * 1000,
                    trackNumber = track,
                    discNumber = disc,
                    year = year,
                    bitrate = bitrate
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getAlbumSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getArtists(): List<Artist> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getArtists")

            val json = executeRequest(url) ?: return@withContext emptyList<Artist>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val artistsWrap = subsonic?.getAsJsonObject("artists")
            val indices = artistsWrap?.getAsJsonArray("index")
                ?: return@withContext emptyList<Artist>()

            val result = mutableListOf<Artist>()
            indices.forEach { indexElem ->
                val artists = indexElem.asJsonObject.getAsJsonArray("artist") ?: return@forEach
                artists.forEach { artistElem ->
                    val obj = artistElem.asJsonObject
                    val id = obj.get("id")?.asString ?: return@forEach
                    val name = EncodingUtils.fixEncoding(obj.get("name")?.asString) ?: "Unknown"
                    val albumCount = obj.get("albumCount")?.asInt ?: 0
                    result.add(
                        Artist(
                            id = id,
                            name = name,
                            coverUrl = buildCoverUrl(id),
                            albumCount = albumCount
                        )
                    )
                }
            }
            result
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getArtists failed", e)
            emptyList()
        }
    }

    override suspend fun getArtistSongs(artistId: String, artistName: String?): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getArtist") + "&id=$artistId"
            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val artist = subsonic?.getAsJsonObject("artist")
            val albums = artist?.getAsJsonArray("album")
                ?: return@withContext emptyList<Song>()

            // 并发请求所有专辑的歌曲，supervisorScope 隔离单个请求失败
            val allSongs = supervisorScope {
                albums.map { albumElem ->
                    async {
                        val albumId = albumElem.asJsonObject.get("id")?.asString ?: return@async emptyList<Song>()
                        getAlbumSongs(albumId)
                    }
                }.awaitAll().flatten()
            }
            allSongs
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getArtistSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getSongs(limit: Int, offset: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getSongs") + "&type=alphabeticalByName&size=$limit&offset=$offset"

            val json = executeRequest(url) ?: return@withContext fallbackGetSongs(limit, offset)
            val subsonic = json.getAsJsonObject("subsonic-response") ?: return@withContext fallbackGetSongs(limit, offset)

            // 尝试标准格式: subsonic-response > songs > song[]
            var songs = subsonic.getAsJsonObject("songs")?.getAsJsonArray("song")
            // 尝试替代格式: subsonic-response > song[]（直接数组）
            if (songs == null) {
                songs = subsonic.getAsJsonArray("song")
            }
            // B8 修复：只有当 songs 字段完全缺失（端点异常/格式不兼容）时才走 fallback。
            // 原实现把「songs 存在但为空数组」也当作异常触发 fallback，导致翻到末页时
            // （正常返回空数组）每次都遍历全部专辑做 N+1 请求，大曲库下卡死。
            if (songs == null) {
                return@withContext fallbackGetSongs(limit, offset)
            }
            // songs 存在但为空 → 正常末页，直接返回空列表，不再 fallback
            if (songs.size() == 0) {
                return@withContext emptyList<Song>()
            }

            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown"
                val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: ""
                val album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: ""
                val albumId = obj.get("albumId")?.asString ?: ""
                val track = obj.get("track")?.asInt ?: 0
                val disc = obj.get("discNumber")?.asInt ?: 1
                val year = obj.get("year")?.asInt
                val durationSec = obj.get("duration")?.asLong ?: 0L
                val bitrate = obj.get("bitRate")?.asInt ?: 0
                val coverId = obj.get("coverArt")?.asString ?: ""

                Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = albumId,
                    coverUrl = if (coverId.isNotBlank()) buildCoverUrl(coverId) else null,
                    streamUrl = getStreamUrl(id),
                    durationMs = durationSec * 1000,
                    trackNumber = track,
                    discNumber = disc,
                    year = year,
                    bitrate = bitrate
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getSongs failed", e)
            fallbackGetSongs(limit, offset)
        }
    }

    /**
     * 当 getSongs 端点不可用或返回空时的兜底方案：
     * 遍历所有专辑获取歌曲，再按分页裁剪。
     */
    private suspend fun fallbackGetSongs(limit: Int, offset: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            AppLog.w("NavidromeAdapter", "fallbackGetSongs: falling back to album iteration")
            // size=5000：覆盖绝大多数个人曲库规模，同时留在 Subsonic 服务端典型上限内
            val albumUrl = buildRestUrl("getAlbumList2") + "&type=alphabeticalByName&size=5000&offset=0"
            val json = executeRequest(albumUrl) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val albumList = subsonic?.getAsJsonObject("albumList2")?.getAsJsonArray("album")
                ?: return@withContext emptyList<Song>()

            val albumCount = albumList.size()
            AppLog.w("NavidromeAdapter", "fallbackGetSongs: iterating $albumCount albums (may be slow for large libraries)")

            val allSongs = supervisorScope {
                albumList.map { albumElem ->
                    async {
                        val albumId = albumElem.asJsonObject.get("id")?.asString
                            ?: return@async emptyList<Song>()
                        getAlbumSongs(albumId)
                    }
                }.awaitAll().flatten()
            }

            // 按分页裁剪
            allSongs.drop(offset).take(limit)
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "fallbackGetSongs failed", e)
            emptyList()
        }
    }

    override suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = buildRestUrl("search2") +
                    "&query=$encodedQuery&songCount=200&artistCount=0&albumCount=0"

            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val searchRes = subsonic?.getAsJsonObject("searchResult2")
            val songs = searchRes?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()

            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown"
                val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: ""
                val album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: ""
                val albumId = obj.get("albumId")?.asString ?: ""
                val track = obj.get("track")?.asInt ?: 0
                val durationSec = obj.get("duration")?.asLong ?: 0L
                val coverId = obj.get("coverArt")?.asString ?: ""

                Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = albumId,
                    coverUrl = if (coverId.isNotBlank()) buildCoverUrl(coverId) else null,
                    streamUrl = getStreamUrl(id),
                    durationMs = durationSec * 1000,
                    trackNumber = track
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "searchSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getRecentSongs(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getAlbumList2") + "&type=newest&size=20"
            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val albumList = subsonic?.getAsJsonObject("albumList2")
            val albums = albumList?.getAsJsonArray("album")
                ?: return@withContext emptyList<Song>()

            // 并发请求所有专辑的歌曲，每个专辑最多取 5 首
            val deferredSongs = albums.take(20).map { albumElem ->
                async {
                    val albumId = albumElem.asJsonObject.get("id")?.asString ?: return@async emptyList<Song>()
                    getAlbumSongs(albumId).take(5)
                }
            }
            val recentSongs = deferredSongs.awaitAll().flatten()
            recentSongs.take(100)
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getRecentSongs failed", e)
            emptyList()
        }
    }

    override fun getStreamUrl(songId: String): String =
        buildRestUrl("stream") + "&id=$songId"

    override fun getCoverUrl(songId: String): String =
        buildCoverUrl(songId).also {
            AppLog.d("NavidromeAdapter", "getCoverUrl: id=$songId")
        }

    override fun getCoverUrlCandidates(song: Song): List<String> {
        val urls = mutableListOf<String>()
        // 1. 歌曲/专辑封面（coverArt 字段已填充到 coverUrl）
        song.coverUrl?.let { urls.add(it) }
        // albumId 在 Subsonic 中也是合法的 coverArt id，作为 fallback
        song.albumId?.takeIf { it.isNotBlank() }?.let {
            val albumCoverUrl = buildCoverUrl(it)
            if (albumCoverUrl !in urls) urls.add(albumCoverUrl)
        }
        // 2. 艺术家封面
        song.artistId?.takeIf { it.isNotBlank() }?.let {
            urls.add(buildCoverUrl(it))
        }
        return urls.distinct().filter { it.isNotBlank() }
    }

    override suspend fun getSongTechnicalInfo(songId: String): SongTechnicalInfo? = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getSong") + "&id=$songId"
            val json = executeRequest(url) ?: return@withContext null
            val subsonic = json.getAsJsonObject("subsonic-response") ?: return@withContext null
            val songObj = subsonic.getAsJsonObject("song") ?: return@withContext null

            val codec = songObj.get("contentType")?.asString?.substringAfter("/")?.uppercase()
                ?: songObj.get("suffix")?.asString?.uppercase() ?: ""
            val bitrate = songObj.get("bitRate")?.asInt ?: 0
            val durationSec = songObj.get("duration")?.asLong ?: 0L
            val size = songObj.get("size")?.asLong ?: 0L

            // Subsonic API 不直接返回采样率和声道数，尝试从其他字段推断或留空
            // Navidrome 支持通过 getSong 获取更完整的元数据
            SongTechnicalInfo(
                codec = codec,
                bitrate = bitrate,
                sampleRate = 0,
                channels = 0,
                fileSize = size,
                durationMs = durationSec * 1000,
                format = codec
            )
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getSongTechnicalInfo failed", e)
            null
        }
    }

    override suspend fun getLyrics(songId: String): String? = withContext(Dispatchers.IO) {
        try {
            // 1. 通过 getSong 获取歌曲元数据（artist + title）
            val songUrl = buildRestUrl("getSong") + "&id=$songId"
            val songJson = executeRequest(songUrl) ?: return@withContext null
            val subsonic = songJson.getAsJsonObject("subsonic-response")
            val song = subsonic?.getAsJsonObject("song") ?: return@withContext null
            val artist = EncodingUtils.fixEncoding(song.get("artist")?.asString) ?: return@withContext null
            val title = EncodingUtils.fixEncoding(song.get("title")?.asString) ?: return@withContext null

            // 2. 调用 Subsonic getLyrics 端点（按歌手+标题搜索）
            val encodedArtist = java.net.URLEncoder.encode(artist, "UTF-8")
            val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
            val lyricsUrl = buildRestUrl("getLyrics") + "&artist=$encodedArtist&title=$encodedTitle"
            val lyricsJson = executeRequest(lyricsUrl) ?: return@withContext null
            val lyricsSubsonic = lyricsJson.getAsJsonObject("subsonic-response")
            val lyrics = lyricsSubsonic?.getAsJsonObject("lyrics")
            val value = lyrics?.get("value")?.asString
            if (value != null) EncodingUtils.fixEncoding(value) else null
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getLyrics failed", e)
            null
        }
    }

    /**
     * P1-3 修复（2026-09-22 审查）：原走接口默认 emptyList()，「年代」维度恒空。
     * 与 SubsonicAdapter.getYears 同款：分页拉歌提取年份（上限 20 页 × 500 = 1 万首，
     * 超出部分不保证完整，取舍与 Subsonic 一致）。
     */
    override suspend fun getYears(): List<Int> = withContext(Dispatchers.IO) {
        try {
            val allYears = mutableSetOf<Int>()
            val pageSize = 500
            var offset = 0
            var maxPages = 20
            while (maxPages-- > 0) {
                val batch = getSongs(pageSize, offset)
                if (batch.isEmpty()) break
                batch.filter { it.year != null }.forEach { allYears.add(it.year!!) }
                offset += pageSize
            }
            allYears.sorted()
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getYears failed", e)
            emptyList()
        }
    }

    // ========== F-1 扩展接口 ==========

    // --- 播放列表 ---
    override suspend fun getPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getPlaylists")
            val json = executeRequest(url) ?: return@withContext emptyList<Playlist>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val playlistsWrap = subsonic?.getAsJsonObject("playlists")
            val playlists = playlistsWrap?.getAsJsonArray("playlist")
                ?: return@withContext emptyList<Playlist>()
            playlists.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                Playlist(
                    id = id,
                    name = EncodingUtils.fixEncoding(obj.get("name")?.asString) ?: "Unknown",
                    songCount = obj.get("songCount")?.asInt ?: 0,
                    durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                    owner = EncodingUtils.fixEncoding(obj.get("owner")?.asString) ?: ""
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getPlaylists failed", e)
            emptyList()
        }
    }

    override suspend fun getPlaylistSongs(playlistId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getPlaylist") + "&id=$playlistId"
            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val playlist = subsonic?.getAsJsonObject("playlist")
                ?: return@withContext emptyList<Song>()
            val entries = playlist.getAsJsonArray("entry")
                ?: return@withContext emptyList<Song>()

            val playlistName = EncodingUtils.fixEncoding(playlist.get("name")?.asString) ?: ""

            entries.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown"
                val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: ""
                val albumName = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: ""
                val track = obj.get("track")?.asInt ?: 0
                val disc = obj.get("discNumber")?.asInt ?: 1
                val year = obj.get("year")?.asInt
                val durationSec = obj.get("duration")?.asLong ?: 0L
                val bitrate = obj.get("bitRate")?.asInt ?: 0
                val coverId = obj.get("coverArt")?.asString ?: ""

                Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = albumName,
                    // P1-4 修复（2026-09-22 审查）：原为 playlistId——歌单条目自身的 albumId
                    // 被丢弃，导致从歌单跳专辑 / 按专辑找封面全部错位（SubsonicAdapter 同构
                    // 实现是正确的）。恢复解析条目真实 albumId。
                    albumId = obj.get("albumId")?.asString ?: "",
                    coverUrl = if (coverId.isNotBlank()) buildCoverUrl(coverId) else null,
                    streamUrl = getStreamUrl(id),
                    durationMs = durationSec * 1000,
                    trackNumber = track,
                    discNumber = disc,
                    year = year,
                    bitrate = bitrate
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getPlaylistSongs failed", e)
            emptyList()
        }
    }

    override suspend fun createPlaylist(name: String): Playlist? = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("createPlaylist") + "&name=${java.net.URLEncoder.encode(name, "UTF-8")}"
            val json = executeRequest(url) ?: return@withContext null
            val subsonic = json.getAsJsonObject("subsonic-response")
            val playlist = subsonic?.getAsJsonObject("playlist")
            val id = playlist?.get("id")?.asString ?: return@withContext null
            Playlist(id = id, name = name)
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "createPlaylist failed", e)
            null
        }
    }

    override suspend fun deletePlaylist(playlistId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("deletePlaylist") + "&id=$playlistId"
            val json = executeRequest(url) ?: return@withContext false
            val subsonic = json.getAsJsonObject("subsonic-response")
            subsonic?.get("status")?.asString == "ok"
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "deletePlaylist failed", e)
            false
        }
    }

    override suspend fun addToPlaylist(playlistId: String, songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("updatePlaylist") + "&playlistId=$playlistId&songIdToAdd=$songId"
            val json = executeRequest(url) ?: return@withContext false
            val subsonic = json.getAsJsonObject("subsonic-response")
            subsonic?.get("status")?.asString == "ok"
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "addToPlaylist failed", e)
            false
        }
    }

    override suspend fun removeFromPlaylist(playlistId: String, songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("updatePlaylist") + "&playlistId=$playlistId&songIdToRemove=$songId"
            val json = executeRequest(url) ?: return@withContext false
            val subsonic = json.getAsJsonObject("subsonic-response")
            subsonic?.get("status")?.asString == "ok"
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "removeFromPlaylist failed", e)
            false
        }
    }

    // --- 收藏 ---
    // 本地缓存收藏状态，避免每次 toggle 都拉取全量收藏列表
    private val _favoriteIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var _favoritesLoaded = false

    override suspend fun toggleFavorite(songId: String, isCurrentlyFavorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            // 懒加载收藏列表
            if (!_favoritesLoaded) {
                loadFavorites()
            }
            val isStarred = songId in _favoriteIds
            val method = if (isStarred) "unstar" else "star"
            val url = buildRestUrl(method) + "&id=$songId"
            val json = executeRequest(url)
            val responseSubsonic = json?.getAsJsonObject("subsonic-response")
            val ok = responseSubsonic?.get("status")?.asString == "ok"
            if (ok) {
                if (isStarred) _favoriteIds.remove(songId) else _favoriteIds.add(songId)
            }
            ok
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "toggleFavorite failed", e)
            false
        }
    }

    private suspend fun loadFavorites() {
        val url = buildRestUrl("getStarred2")
        val json = executeRequest(url) ?: return
        val subsonic = json.getAsJsonObject("subsonic-response")
        val starredWrap = subsonic?.getAsJsonObject("starred2")
        val songs = starredWrap?.getAsJsonArray("song") ?: return
        for (i in 0 until songs.size()) {
            songs[i].asJsonObject.get("id")?.asString?.let { _favoriteIds.add(it) }
        }
        _favoritesLoaded = true
    }

    override suspend fun getFavorites(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getStarred2")
            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val starredWrap = subsonic?.getAsJsonObject("starred2")
            val songs = starredWrap?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()
            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                Song(
                    id = id,
                    title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown",
                    artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: "",
                    album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: "",
                    albumId = obj.get("albumId")?.asString ?: "",
                    coverUrl = buildCoverUrl(obj.get("coverArt")?.asString ?: id),
                    streamUrl = getStreamUrl(id),
                    durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                    trackNumber = obj.get("track")?.asInt ?: 0
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getFavorites failed", e)
            emptyList()
        }
    }

    // --- 评分 ---
    override suspend fun setRating(songId: String, rating: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("setRating") + "&id=$songId&rating=$rating"
            val json = executeRequest(url) ?: return@withContext false
            val subsonic = json.getAsJsonObject("subsonic-response")
            subsonic?.get("status")?.asString == "ok"
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "setRating failed", e)
            false
        }
    }

    // --- 流派 ---
    override suspend fun getGenres(): List<Genre> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getGenres")
            val json = executeRequest(url) ?: return@withContext emptyList<Genre>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val genresWrap = subsonic?.getAsJsonObject("genres")
            val genres = genresWrap?.getAsJsonArray("genre")
                ?: return@withContext emptyList<Genre>()
            genres.mapNotNull { item ->
                val obj = item.asJsonObject
                val name = obj.get("value")?.asString ?: return@mapNotNull null
                Genre(
                    id = name,
                    name = name,
                    songCount = obj.get("songCount")?.asInt ?: 0
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getGenres failed", e)
            emptyList()
        }
    }

    override suspend fun getSongsByGenre(genre: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val encodedGenre = java.net.URLEncoder.encode(genre, "UTF-8")
            val url = buildRestUrl("getSongsByGenre") + "&genre=$encodedGenre&size=500"
            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val songsByGenre = subsonic?.getAsJsonObject("songsByGenre")
            val songs = songsByGenre?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()
            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                Song(
                    id = id,
                    title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown",
                    artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: "",
                    album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: "",
                    albumId = obj.get("albumId")?.asString ?: "",
                    coverUrl = buildCoverUrl(obj.get("coverArt")?.asString ?: id),
                    streamUrl = getStreamUrl(id),
                    durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                    trackNumber = obj.get("track")?.asInt ?: 0,
                    year = obj.get("year")?.asInt
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getSongsByGenre failed", e)
            emptyList()
        }
    }

    override suspend fun getSongsByYearRange(fromYear: Int, toYear: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            // Subsonic doesn't have a direct year-range endpoint; paginate through songs
            val pageSize = 500
            val allSongs = mutableListOf<Song>()
            var offset = 0
            var maxPages = 200
            while (maxPages-- > 0) {
                val batch = getSongs(pageSize, offset)
                if (batch.isEmpty()) break
                allSongs.addAll(batch.filter { it.year != null && it.year in fromYear..toYear })
                offset += pageSize
            }
            allSongs
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getSongsByYearRange failed", e)
            emptyList()
        }
    }

    // --- Scrobble ---
    override suspend fun scrobblePlay(songId: String, timestamp: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("scrobble") + "&id=$songId&time=$timestamp"
            val json = executeRequest(url) ?: return@withContext false
            val subsonic = json.getAsJsonObject("subsonic-response")
            subsonic?.get("status")?.asString == "ok"
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "scrobblePlay failed", e)
            false
        }
    }

    // --- 随机歌曲 ---
    override suspend fun getRandomSongs(limit: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getRandomSongs") + "&size=$limit"
            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val randomWrap = subsonic?.getAsJsonObject("randomSongs")
            val songs = randomWrap?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()
            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                Song(
                    id = id,
                    title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown",
                    artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: "",
                    album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: "",
                    albumId = obj.get("albumId")?.asString ?: "",
                    coverUrl = buildCoverUrl(obj.get("coverArt")?.asString ?: id),
                    streamUrl = getStreamUrl(id),
                    durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                    trackNumber = obj.get("track")?.asInt ?: 0,
                    year = obj.get("year")?.asInt
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getRandomSongs failed", e)
            emptyList()
        }
    }

    /**
     * 释放 OkHttp 连接资源。
     * Navidrome 使用无状态认证，无服务端 session 需要清理。
     * 此处关闭客户端连接池，防止连接泄漏。
     */
    override fun close() {
        // R-6：连接池/线程池已共享（BackendRegistry 持有），此处禁止 shutdown/evictAll
        try {
            baseUrl = ""
            username = ""
            password = ""
            apiToken = ""
            salt = ""
            AppLog.d("NavidromeAdapter", "close: auth state cleared (shared OkHttp pool retained)")
        } catch (e: Exception) {
            AppLog.w("NavidromeAdapter", "close failed", e)
        }
    }

    // --- 内部辅助方法 ---

    private fun buildRestUrl(method: String): String = restClient.buildRestUrl(method)

    // 修复：此前未覆盖该方法，沿用接口默认实现恒返回 0（UI“共 M 首”永远显示 0）。
    override suspend fun getSongsTotalCount(): Int = restClient.songsTotalCount()

    private fun buildCoverUrl(coverArtId: String): String = restClient.buildCoverUrl(coverArtId)

    private suspend fun executeRequest(url: String): JsonObject? = restClient.executeRequest(url)

    companion object {
        /**
         * Subsonic API 协议版本号。
         * - 这是 API 协议版本（不是客户端版本），用于服务端兼容性判断
         * - Subsonic API 当前稳定版本为 1.16.1，Navidrome 完全兼容
         * - 升级前提：服务端明确要求更高版本（如新增 API 字段时）
         */
        private const val API_VERSION = "1.16.1"

        /** 客户端标识（Subsonic API c 参数，服务端用于区分客户端类型） */
        private const val CLIENT_NAME = "NASMusicTV"
    }
}

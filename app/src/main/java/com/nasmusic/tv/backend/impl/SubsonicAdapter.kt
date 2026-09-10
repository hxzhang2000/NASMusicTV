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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Subsonic 协议后端适配器
 * 支持所有兼容 Subsonic API 的服务器（Navidrome, lx-server, Airsonic, Madsonic 等）
 *
 * 认证方式：Token + Salt（Subsonic 标准认证）
 * - token = md5(password + salt)
 * - 请求参数：u=username, t=token, s=salt, v=1.16.1, c=NASMusicTV, f=json
 */
class SubsonicAdapter : BackendAdapter {

    override val backendType: String = "subsonic"
    override var apiVersion: String = "Subsonic (版本未知)"

    private var baseUrl: String = ""
    private var username: String = ""
    private var password: String = ""

    // Subsonic 认证参数
    private var apiToken: String = ""
    private var salt: String = ""

    override var serverName: String = "Subsonic"
        private set

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

    /** R-10：Subsonic 公共层（请求执行/md5；URL 构造因行为差异保留自有实现） */
    private val restClient = SubsonicRestClient("SubsonicAdapter", client)

    override suspend fun initialize(
        baseUrl: String,
        apiToken: String,
        username: String,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        this@SubsonicAdapter.baseUrl = baseUrl.removeSuffix("/")
        this@SubsonicAdapter.username = username
        this@SubsonicAdapter.password = password

        // 生成 token 认证参数
        salt = UUID.randomUUID().toString().replace("-", "").take(16)
        this@SubsonicAdapter.apiToken = md5(password + salt)

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
                    serverName = "Subsonic $version"
                    apiVersion = "Subsonic API $version"
                }
                status == "ok"
            }
        } catch (e: Exception) {
            AppLog.w("SubsonicAdapter", "testConnection failed", e)
            false
        }
    }

    override suspend fun getApiVersion(): VersionInfo = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("ping")
            val request = Request.Builder().url(url).build()
            val result = client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use VersionInfo.Disconnected("Subsonic")
                if (!response.isSuccessful) return@use VersionInfo.Disconnected("Subsonic")
                val json = gson.fromJson(body, JsonObject::class.java)
                val subsonic = json.getAsJsonObject("subsonic-response") ?: return@use VersionInfo.Disconnected("Subsonic")
                val status = subsonic.get("status")?.asString ?: return@use VersionInfo.Disconnected("Subsonic")
                val version = subsonic.get("version")?.asString ?: ""
                if (status == "ok" && version.isNotBlank()) {
                    VersionInfo.Runtime("Subsonic", version, "rest/ping.view", System.currentTimeMillis())
                } else {
                    VersionInfo.Disconnected("Subsonic")
                }
            }
            result
        } catch (e: Exception) {
            AppLog.w("SubsonicAdapter", "getApiVersion failed", e)
            VersionInfo.Disconnected("Subsonic")
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
            AppLog.e("SubsonicAdapter", "getAlbums failed", e)
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
            AppLog.e("SubsonicAdapter", "getAlbumSongs failed", e)
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
            AppLog.e("SubsonicAdapter", "getArtists failed", e)
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
            AppLog.e("SubsonicAdapter", "getArtistSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getSongs(limit: Int, offset: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getSongs") + "&type=alphabeticalByName&size=$limit&offset=$offset"

            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response") ?: return@withContext emptyList<Song>()

            // 尝试标准格式: subsonic-response > songs > song[]
            var songs = subsonic.getAsJsonObject("songs")?.getAsJsonArray("song")
            // 尝试替代格式: subsonic-response > song[]（直接数组）
            if (songs == null) {
                songs = subsonic.getAsJsonArray("song")
            }
            songs?.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown"
                val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: ""
                val album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: ""
                val albumId = obj.get("albumId")?.asString ?: ""
                val coverId = obj.get("coverArt")?.asString ?: ""
                val track = obj.get("track")?.asInt ?: 0
                val disc = obj.get("discNumber")?.asInt ?: 1
                val year = obj.get("year")?.asInt
                val durationSec = obj.get("duration")?.asLong ?: 0L
                val bitrate = obj.get("bitRate")?.asInt ?: 0

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
            } ?: emptyList()
        } catch (e: Exception) {
            AppLog.e("SubsonicAdapter", "getSongs failed", e)
            emptyList()
        }
    }

    override suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = buildRestUrl("search3") + "&query=$encodedQuery&songCount=100"

            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val searchResult = subsonic?.getAsJsonObject("searchResult3")
            val songs = searchResult?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()

            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown"
                val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: ""
                val album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: ""
                val albumId = obj.get("albumId")?.asString ?: ""
                val coverId = obj.get("coverArt")?.asString ?: ""
                val durationSec = obj.get("duration")?.asLong ?: 0L

                Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = albumId,
                    coverUrl = if (coverId.isNotBlank()) buildCoverUrl(coverId) else null,
                    streamUrl = getStreamUrl(id),
                    durationMs = durationSec * 1000,
                    trackNumber = obj.get("track")?.asInt ?: 0,
                    discNumber = obj.get("discNumber")?.asInt ?: 1,
                    year = obj.get("year")?.asInt,
                    bitrate = obj.get("bitRate")?.asInt ?: 0
                )
            }
        } catch (e: Exception) {
            AppLog.e("SubsonicAdapter", "searchSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getRecentSongs(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getAlbumList2") + "&type=newest&size=50"

            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val albumList = subsonic?.getAsJsonObject("albumList2")
            val albums = albumList?.getAsJsonArray("album")
                ?: return@withContext emptyList<Song>()

            // 获取最新专辑的歌曲
            val allSongs = supervisorScope {
                albums.take(5).map { albumElem ->
                    async {
                        val albumId = albumElem.asJsonObject.get("id")?.asString ?: return@async emptyList<Song>()
                        getAlbumSongs(albumId)
                    }
                }.awaitAll().flatten()
            }
            allSongs.take(50)
        } catch (e: Exception) {
            AppLog.e("SubsonicAdapter", "getRecentSongs failed", e)
            emptyList()
        }
    }

    override fun getStreamUrl(songId: String): String {
        return buildRestUrl("stream") + "&id=$songId"
    }

    override fun getCoverUrl(songId: String): String {
        return buildCoverUrl(songId)
    }

    override suspend fun getLyrics(songId: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getLyrics") + "&id=$songId"
            val json = executeRequest(url) ?: return@withContext null
            val subsonic = json.getAsJsonObject("subsonic-response")
            val lyrics = subsonic?.getAsJsonObject("lyrics")
            lyrics?.get("value")?.asString
        } catch (e: Exception) {
            AppLog.e("SubsonicAdapter", "getLyrics failed", e)
            null
        }
    }

    // --- 收藏 ---
    override suspend fun toggleFavorite(songId: String, isCurrentlyFavorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            // 先获取当前收藏状态
            val starredSongs = getFavorites()
            val isFavorited = starredSongs.any { it.id == songId }

            // 切换收藏状态
            val method = if (isFavorited) "unstar" else "star"
            val url = buildRestUrl(method) + "&id=$songId"
            val json = executeRequest(url) ?: return@withContext false
            val subsonic = json.getAsJsonObject("subsonic-response")
            subsonic?.get("status")?.asString == "ok"
        } catch (e: Exception) {
            AppLog.e("SubsonicAdapter", "toggleFavorite failed", e)
            false
        }
    }

    override suspend fun getFavorites(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getStarred2")

            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            // B9 修复：getStarred2 端点返回的是 `starred2` 节点，而非 `starred`。
            // 原实现解析 `starred` 导致收藏列表恒空、toggleFavorite 无法取消收藏
            // （每次 getFavorites 都返回空 → isFavorited 恒 false → 永远走 star 分支）。
            // 优先解析 `starred2`，兼容部分实现返回的 `starred`。
            val starredNode = subsonic?.getAsJsonObject("starred2")
                ?: subsonic?.getAsJsonObject("starred")
            val songs = starredNode?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()

            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown"
                val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: ""
                val album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: ""
                val albumId = obj.get("albumId")?.asString ?: ""
                val coverId = obj.get("coverArt")?.asString ?: ""
                val durationSec = obj.get("duration")?.asLong ?: 0L

                Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = albumId,
                    coverUrl = if (coverId.isNotBlank()) buildCoverUrl(coverId) else null,
                    streamUrl = getStreamUrl(id),
                    durationMs = durationSec * 1000,
                    trackNumber = obj.get("track")?.asInt ?: 0,
                    discNumber = obj.get("discNumber")?.asInt ?: 1,
                    year = obj.get("year")?.asInt,
                    bitrate = obj.get("bitRate")?.asInt ?: 0
                )
            }
        } catch (e: Exception) {
            AppLog.e("SubsonicAdapter", "getFavorites failed", e)
            emptyList()
        }
    }

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
                val name = EncodingUtils.fixEncoding(obj.get("name")?.asString) ?: "Unknown"
                val songCount = obj.get("songCount")?.asInt ?: 0
                val durationSec = obj.get("duration")?.asLong ?: 0L

                Playlist(
                    id = id,
                    name = name,
                    songCount = songCount,
                    durationMs = durationSec * 1000
                )
            }
        } catch (e: Exception) {
            AppLog.e("SubsonicAdapter", "getPlaylists failed", e)
            emptyList()
        }
    }

    override suspend fun getPlaylistSongs(playlistId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getPlaylist") + "&id=$playlistId"

            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val playlist = subsonic?.getAsJsonObject("playlist")
            val songs = playlist?.getAsJsonArray("entry")
                ?: return@withContext emptyList<Song>()

            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown"
                val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: ""
                val album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: ""
                val albumId = obj.get("albumId")?.asString ?: ""
                val coverId = obj.get("coverArt")?.asString ?: ""
                val durationSec = obj.get("duration")?.asLong ?: 0L

                Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = albumId,
                    coverUrl = if (coverId.isNotBlank()) buildCoverUrl(coverId) else null,
                    streamUrl = getStreamUrl(id),
                    durationMs = durationSec * 1000,
                    trackNumber = obj.get("track")?.asInt ?: 0,
                    discNumber = obj.get("discNumber")?.asInt ?: 1,
                    year = obj.get("year")?.asInt,
                    bitrate = obj.get("bitRate")?.asInt ?: 0
                )
            }
        } catch (e: Exception) {
            AppLog.e("SubsonicAdapter", "getPlaylistSongs failed", e)
            emptyList()
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
                Genre(id = name, name = name)
            }
        } catch (e: Exception) {
            AppLog.e("SubsonicAdapter", "getGenres failed", e)
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
            AppLog.e("SubsonicAdapter", "getSongsByGenre failed", e)
            emptyList()
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
            AppLog.e("SubsonicAdapter", "getRandomSongs failed", e)
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
            AppLog.e("SubsonicAdapter", "scrobblePlay failed", e)
            false
        }
    }

    // --- 按年份范围查询歌曲 ---
    override suspend fun getSongsByYearRange(fromYear: Int, toYear: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            // Subsonic 没有直接的年份范围端点，需要分页获取歌曲后过滤
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
            AppLog.e("SubsonicAdapter", "getSongsByYearRange failed", e)
            emptyList()
        }
    }

    // --- 按 ID 批量查询歌曲 ---
    override suspend fun getSongsByIds(ids: List<String>): List<Song> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        try {
            // Subsonic 没有直接的批量查询端点。
            // B13 修复：原实现串行逐个 getSong（N+1），队列恢复数十首歌时 RTT 累加成秒级卡顿。
            // 改为并发请求（受信号量限流 8 路，避免打爆服务器），失败单曲不影响整体。
            val semaphore = java.util.concurrent.Semaphore(8)
            val songs = supervisorScope {
                ids.map { id ->
                    async {
                        semaphore.acquire()
                        try {
                            val url = buildRestUrl("getSong") + "&id=$id"
                            val json = executeRequest(url) ?: return@async null
                            val subsonic = json.getAsJsonObject("subsonic-response")
                            val song = subsonic?.getAsJsonObject("song") ?: return@async null
                            val title = EncodingUtils.fixEncoding(song.get("title")?.asString) ?: "Unknown"
                            val artist = EncodingUtils.fixEncoding(song.get("artist")?.asString) ?: ""
                            val album = EncodingUtils.fixEncoding(song.get("album")?.asString) ?: ""
                            val albumId = song.get("albumId")?.asString ?: ""
                            val coverId = song.get("coverArt")?.asString ?: ""
                            val durationSec = song.get("duration")?.asLong ?: 0L
                            Song(
                                id = id,
                                title = title,
                                artist = artist,
                                album = album,
                                albumId = albumId,
                                coverUrl = if (coverId.isNotBlank()) buildCoverUrl(coverId) else null,
                                streamUrl = getStreamUrl(id),
                                durationMs = durationSec * 1000,
                                trackNumber = song.get("track")?.asInt ?: 0,
                                discNumber = song.get("discNumber")?.asInt ?: 1,
                                year = song.get("year")?.asInt,
                                bitrate = song.get("bitRate")?.asInt ?: 0
                            )
                        } catch (e: Exception) {
                            null
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            songs
        } catch (e: Exception) {
            AppLog.e("SubsonicAdapter", "getSongsByIds failed", e)
            emptyList()
        }
    }

    // --- 获取歌曲总数 ---
    override suspend fun getSongsTotalCount(): Int = withContext(Dispatchers.IO) {
        try {
            // 使用 getSongs 获取第一页，检查是否有更多
            val url = buildRestUrl("getSongs") + "&type=alphabeticalByName&size=1&offset=0"
            val json = executeRequest(url) ?: return@withContext 0
            val subsonic = json.getAsJsonObject("subsonic-response") ?: return@withContext 0
            // 尝试从 songs 对象获取总数
            val songs = subsonic.getAsJsonObject("songs")
            songs?.get("totalSongs")?.asInt ?: 0
        } catch (e: Exception) {
            AppLog.e("SubsonicAdapter", "getSongsTotalCount failed", e)
            0
        }
    }

    // --- 获取所有年份 ---
    override suspend fun getYears(): List<Int> = withContext(Dispatchers.IO) {
        try {
            // Subsonic 没有直接的年份列表端点，从歌曲中提取
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
            AppLog.e("SubsonicAdapter", "getYears failed", e)
            emptyList()
        }
    }

    /**
     * 释放 OkHttp 连接资源。
     * Subsonic 使用无状态认证，无服务端 session 需要清理。
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
            AppLog.d("SubsonicAdapter", "close: auth state cleared (shared OkHttp pool retained)")
        } catch (e: Exception) {
            AppLog.w("SubsonicAdapter", "close failed", e)
        }
    }

    // --- 内部辅助方法 ---

    // R-10：buildRestUrl 与 Navidrome 存在真实差异（无 .view 后缀、t=apiToken 而非现场
    // md5 计算——Subsonic 部分服务端用 token 直传），保留自有实现；公共的
    // executeRequest/md5 经 restClient 委托。
    private fun buildRestUrl(method: String): String {
        return "$baseUrl/rest/$method?" +
                "u=$username&" +
                "t=$apiToken&" +
                "s=$salt&" +
                "v=$API_VERSION&" +
                "c=$CLIENT_NAME&" +
                "f=json"
    }

    private fun buildCoverUrl(coverArtId: String): String =
        buildRestUrl("getCoverArt") + "&id=$coverArtId&size=512"

    private suspend fun executeRequest(url: String): JsonObject? = restClient.executeRequest(url)

    private fun md5(input: String): String = restClient.md5(input)

    companion object {
        /**
         * Subsonic API 协议版本号。
         * - 这是 API 协议版本（不是客户端版本），用于服务端兼容性判断
         * - Subsonic API 当前稳定版本为 1.16.1，Navidrome/lx-server 等均兼容
         */
        private const val API_VERSION = "1.16.1"

        /** 客户端标识（Subsonic API c 参数，服务端用于区分客户端类型） */
        private const val CLIENT_NAME = "NASMusicTV"
    }
}

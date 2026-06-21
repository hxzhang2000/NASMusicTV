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

    private var baseUrl: String = ""
    private var username: String = ""
    private var password: String = ""
    private var apiToken: String = ""
    private var serverName: String = "Navidrome"

    private val gson = Gson()
    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

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
                if (version.isNotBlank()) serverName = "Navidrome $version"
                status == "ok"
            }
        } catch (e: Exception) {
            android.util.Log.w("NavidromeAdapter", "testConnection failed", e)
            false
        }
    }

    override suspend fun getAlbums(): List<Album> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getAlbumList2") +
                    "&type=alphabeticalByName&size=500"

            val json = executeRequest(url) ?: return@withContext emptyList<Album>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val albumList = subsonic?.getAsJsonObject("albumList2")
            val albums = albumList?.getAsJsonArray("album")
                ?: return@withContext emptyList<Album>()

            albums.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val name = obj.get("name")?.asString ?: "Unknown Album"
                val artist = obj.get("artist")?.asString ?: ""
                val year = obj.get("year")?.asInt
                val songCount = obj.get("songCount")?.asInt ?: 0
                val durationSec = obj.get("duration")?.asLong ?: 0L

                Album(
                    id = id,
                    name = name,
                    artist = artist,
                    coverUrl = buildCoverUrl(id),
                    year = year,
                    songCount = songCount,
                    durationMs = durationSec * 1000
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("NavidromeAdapter", "getAlbums failed", e)
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

            val albumName = album.get("name")?.asString ?: ""
            val albumArtist = album.get("artist")?.asString ?: ""

            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = obj.get("title")?.asString ?: "Unknown"
                val artist = obj.get("artist")?.asString ?: albumArtist
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
            android.util.Log.e("NavidromeAdapter", "getAlbumSongs failed", e)
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
                    val name = obj.get("name")?.asString ?: "Unknown"
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
            android.util.Log.e("NavidromeAdapter", "getArtists failed", e)
            emptyList()
        }
    }

    override suspend fun getArtistSongs(artistId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getArtist") + "&id=$artistId"
            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val artist = subsonic?.getAsJsonObject("artist")
            val albums = artist?.getAsJsonArray("album")
                ?: return@withContext emptyList<Song>()

            val allSongs = mutableListOf<Song>()
            albums.forEach { albumElem ->
                val albumId = albumElem.asJsonObject.get("id")?.asString ?: return@forEach
                val songs = getAlbumSongs(albumId)
                allSongs.addAll(songs)
            }
            allSongs
        } catch (e: Exception) {
            android.util.Log.e("NavidromeAdapter", "getArtistSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getSongs(limit: Int, offset: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getSongs") + "&type=alphabeticalByName&size=$limit&offset=$offset"

            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val songsWrap = subsonic?.getAsJsonObject("songs")
            val songs = songsWrap?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()

            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = fixEncoding(obj.get("title")?.asString) ?: "Unknown"
                val artist = fixEncoding(obj.get("artist")?.asString) ?: ""
                val album = fixEncoding(obj.get("album")?.asString) ?: ""
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
            android.util.Log.e("NavidromeAdapter", "getSongs failed", e)
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
                val title = obj.get("title")?.asString ?: "Unknown"
                val artist = obj.get("artist")?.asString ?: ""
                val album = obj.get("album")?.asString ?: ""
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
            android.util.Log.e("NavidromeAdapter", "searchSongs failed", e)
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

            val recentSongs = mutableListOf<Song>()
            albums.take(20).forEach { albumElem ->
                val albumId = albumElem.asJsonObject.get("id")?.asString ?: return@forEach
                val songs = getAlbumSongs(albumId)
                recentSongs.addAll(songs.take(5))
                if (recentSongs.size >= 100) return@forEach
            }
            recentSongs
        } catch (e: Exception) {
            android.util.Log.e("NavidromeAdapter", "getRecentSongs failed", e)
            emptyList()
        }
    }

    override fun getStreamUrl(songId: String): String =
        buildRestUrl("stream") + "&id=$songId"

    override fun getCoverUrl(songId: String): String {
        val url = buildRestUrl("getCoverArt") + "&id=$songId&size=512"
        android.util.Log.d("NavidromeAdapter", "getCoverUrl: $url")
        return url
    }

    override suspend fun getLyrics(songId: String): String? {
        android.util.Log.d("NavidromeAdapter", "getLyrics: Navidrome does not support lyrics API, returning null")
        return null
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
                    name = obj.get("name")?.asString ?: "Unknown",
                    songCount = obj.get("songCount")?.asInt ?: 0,
                    durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                    owner = obj.get("owner")?.asString ?: ""
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("NavidromeAdapter", "getPlaylists failed", e)
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
            android.util.Log.e("NavidromeAdapter", "createPlaylist failed", e)
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
            android.util.Log.e("NavidromeAdapter", "deletePlaylist failed", e)
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
            android.util.Log.e("NavidromeAdapter", "addToPlaylist failed", e)
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
            android.util.Log.e("NavidromeAdapter", "removeFromPlaylist failed", e)
            false
        }
    }

    // --- 收藏 ---
    override suspend fun toggleFavorite(songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // First check if already starred; Subsonic star/unstar toggles
            val starredUrl = buildRestUrl("getStarred2")
            val starredJson = executeRequest(starredUrl)
            val subsonic = starredJson?.getAsJsonObject("subsonic-response")
            val starredWrap = subsonic?.getAsJsonObject("starred2")
            val starredSongs = starredWrap?.getAsJsonArray("song") ?: emptyList()
            val isStarred = starredSongs.any {
                it.asJsonObject.get("id")?.asString == songId
            }

            val method = if (isStarred) "unstar" else "star"
            val url = buildRestUrl(method) + "&id=$songId"
            val json = executeRequest(url)
            val responseSubsonic = json?.getAsJsonObject("subsonic-response")
            responseSubsonic?.get("status")?.asString == "ok"
        } catch (e: Exception) {
            android.util.Log.e("NavidromeAdapter", "toggleFavorite failed", e)
            false
        }
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
                    title = obj.get("title")?.asString ?: "Unknown",
                    artist = obj.get("artist")?.asString ?: "",
                    album = obj.get("album")?.asString ?: "",
                    albumId = obj.get("albumId")?.asString ?: "",
                    coverUrl = buildCoverUrl(obj.get("coverArt")?.asString ?: id),
                    streamUrl = getStreamUrl(id),
                    durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                    trackNumber = obj.get("track")?.asInt ?: 0
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("NavidromeAdapter", "getFavorites failed", e)
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
            android.util.Log.e("NavidromeAdapter", "setRating failed", e)
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
            android.util.Log.e("NavidromeAdapter", "getGenres failed", e)
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
                    title = obj.get("title")?.asString ?: "Unknown",
                    artist = obj.get("artist")?.asString ?: "",
                    album = obj.get("album")?.asString ?: "",
                    albumId = obj.get("albumId")?.asString ?: "",
                    coverUrl = buildCoverUrl(obj.get("coverArt")?.asString ?: id),
                    streamUrl = getStreamUrl(id),
                    durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                    trackNumber = obj.get("track")?.asInt ?: 0,
                    year = obj.get("year")?.asInt
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("NavidromeAdapter", "getSongsByGenre failed", e)
            emptyList()
        }
    }

    override suspend fun getSongsByYearRange(fromYear: Int, toYear: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            // Subsonic doesn't have a direct year-range endpoint; fallback to album-based
            val allSongs = getSongs(10000)
            allSongs.filter { song ->
                song.year != null && song.year in fromYear..toYear
            }
        } catch (e: Exception) {
            android.util.Log.e("NavidromeAdapter", "getSongsByYearRange failed", e)
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
            android.util.Log.e("NavidromeAdapter", "scrobblePlay failed", e)
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
                    title = obj.get("title")?.asString ?: "Unknown",
                    artist = obj.get("artist")?.asString ?: "",
                    album = obj.get("album")?.asString ?: "",
                    albumId = obj.get("albumId")?.asString ?: "",
                    coverUrl = buildCoverUrl(obj.get("coverArt")?.asString ?: id),
                    streamUrl = getStreamUrl(id),
                    durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                    trackNumber = obj.get("track")?.asInt ?: 0,
                    year = obj.get("year")?.asInt
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("NavidromeAdapter", "getRandomSongs failed", e)
            emptyList()
        }
    }

    fun getServerName(): String = serverName

    /**
     * 释放 OkHttp 连接资源。
     * Navidrome 使用无状态认证，无服务端 session 需要清理。
     * 此处关闭客户端连接池，防止连接泄漏。
     */
    override fun close() {
        try {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            android.util.Log.d("NavidromeAdapter", "close: OkHttp resources released")
        } catch (e: Exception) {
            android.util.Log.w("NavidromeAdapter", "close failed", e)
        }
    }

    // --- 内部辅助方法 ---

    private fun buildRestUrl(method: String): String {
        val salt = System.currentTimeMillis().toString()
        val token = md5(password + salt)
        return "$baseUrl/rest/$method.view?" +
                "u=$username&" +
                "t=$token&" +
                "s=$salt&" +
                "v=1.16.1&" +
                "c=NASMusicTV&" +
                "f=json"
    }

    private fun buildCoverUrl(coverArtId: String): String =
        buildRestUrl("getCoverArt") + "&id=$coverArtId&size=512"

    private fun executeRequest(url: String): JsonObject? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful) return null
                gson.fromJson(body, JsonObject::class.java)
            }
        } catch (e: Exception) {
            android.util.Log.w("NavidromeAdapter", "executeRequest failed for $url", e)
            null
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return BigInteger(1, md.digest(input.toByteArray()))
            .toString(16).padStart(32, '0')
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
                    android.util.Log.d("NavidromeAdapter", "fixEncoding: converted Latin-1 to GB2312: '$fixed' -> '$decoded'")
                    fixed = decoded
                }
            } catch (e: Exception) {
                // GB2312 解码失败，尝试 GBK
                try {
                    val bytes = fixed.toByteArray(Charsets.ISO_8859_1)
                    val decoded = String(bytes, charset("GBK"))
                    if (decoded.any { it.code in 0x4E00..0x9FFF }) {
                        android.util.Log.d("NavidromeAdapter", "fixEncoding: converted Latin-1 to GBK: '$fixed' -> '$decoded'")
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
            android.util.Log.d("NavidromeAdapter", "fixEncoding: result: '${text.take(30)}' -> '${fixed.take(30)}'")
        }
        
        return fixed
    }
}

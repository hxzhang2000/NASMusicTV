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

        // 验证连接
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
        } catch (e: Exception) { false }
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
        } catch (e: Exception) { emptyList() }
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
        } catch (e: Exception) { emptyList() }
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
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getArtistSongs(artistId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            // Navidrome 先获取歌手专辑，再合并所有专辑歌曲
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
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getSongs(limit: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getSongs") + "&type=alphabeticalByName&size=$limit"

            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val songsWrap = subsonic?.getAsJsonObject("songs")
            val songs = songsWrap?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()

            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = obj.get("title")?.asString ?: "Unknown"
                val artist = obj.get("artist")?.asString ?: ""
                val album = obj.get("album")?.asString ?: ""
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
        } catch (e: Exception) { emptyList() }
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
        } catch (e: Exception) { emptyList() }
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
        } catch (e: Exception) { emptyList() }
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

    fun getServerName(): String = serverName

    // --- 内部辅助方法 ---

    /**
     * 构建 Subsonic API URL
     * 使用 token + salt 认证方式
     */
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
        } catch (e: Exception) { null }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return BigInteger(1, md.digest(input.toByteArray()))
            .toString(16).padStart(32, '0')
    }
}

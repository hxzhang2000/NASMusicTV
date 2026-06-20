package com.nasmusic.tv.backend.navidrome

import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Song
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Navidrome 后端适配器实现 (Subsonic API)
 */
class NavidromeAdapter : BackendAdapter {

    override val backendType: String = "navidrome"

    private var api: NavidromeApi? = null
    private var baseUrl: String = ""
    private var username: String = ""
    private var password: String = ""
    private var salt: String = ""
    private var token: String = ""

    override suspend fun initialize(
        baseUrl: String,
        apiToken: String,
        username: String,
        password: String
    ): Boolean {
        this.baseUrl = baseUrl.trimEnd('/')
        this.username = username
        this.password = password

        // Generate salt and token for Subsonic API
        this.salt = generateSalt()
        this.token = md5(password + salt)

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        api = Retrofit.Builder()
            .baseUrl("${this.baseUrl}/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NavidromeApi::class.java)

        return testConnection()
    }

    override suspend fun testConnection(): Boolean {
        return try {
            val response = api?.ping(username, token, salt)
            response?.body()?.subsonicResponse?.status == "ok"
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getAlbums(): List<Album> {
        return try {
            val response = api?.getAlbumList(username, token, salt, type = "alphabeticalByName", size = 500)
            response?.body()?.subsonicResponse?.albumList?.albums?.map { album ->
                Album(
                    id = album.id,
                    name = album.name,
                    artist = album.artist ?: "未知歌手",
                    artistId = album.artistId,
                    year = album.year ?: 0,
                    coverUrl = buildCoverUrl(album.coverArt ?: album.id),
                    songCount = album.songCount ?: 0,
                    durationMs = (album.duration ?: 0).toLong() * 1000
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getAlbumSongs(albumId: String): List<Song> {
        return try {
            val response = api?.getAlbum(username, token, salt, albumId)
            response?.body()?.subsonicResponse?.album?.songs?.map { mapSong(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getArtists(): List<Artist> {
        return try {
            val response = api?.getArtists(username, token, salt)
            response?.body()?.subsonicResponse?.artists?.indexes?.flatMap { index ->
                index.artists?.map { artist ->
                    Artist(
                        id = artist.id,
                        name = artist.name,
                        coverUrl = buildCoverUrl(artist.coverArt ?: artist.id),
                        albumCount = artist.albumCount ?: 0,
                        songCount = 0
                    )
                } ?: emptyList()
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getArtistSongs(artistId: String): List<Song> {
        return try {
            val response = api?.getArtist(username, token, salt, artistId)
            val artist = response?.body()?.subsonicResponse?.artist
            artist?.albums?.flatMap { album ->
                // Need to fetch each album's songs
                getAlbumSongs(album.id)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getSongs(limit: Int): List<Song> {
        // Navidrome doesn't have a direct "get all songs" endpoint
        // Return songs from all albums
        return getAlbums().flatMap { getAlbumSongs(it.id) }
    }

    override suspend fun searchSongs(query: String): List<Song> {
        return try {
            val response = api?.search(username, token, salt, query, songCount = 50)
            response?.body()?.subsonicResponse?.searchResult?.songs?.map { mapSong(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getRecentSongs(): List<Song> {
        return try {
            val response = api?.getStarred(username, token, salt)
            response?.body()?.subsonicResponse?.starred?.songs?.map { mapSong(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun getStreamUrl(songId: String): String {
        return "$baseUrl/rest/stream.view?id=$songId&u=$username&t=$token&s=$salt&v=1.16.1&c=NASMusicTV"
    }

    override fun getCoverUrl(songId: String): String {
        return buildCoverUrl(songId)
    }

    override suspend fun getLyrics(songId: String): String? {
        // Try to get lyrics via Subsonic API
        return null
    }

    private fun mapSong(song: SubsonicSong): Song {
        return Song(
            id = song.id,
            title = song.title,
            artist = song.artist ?: "未知歌手",
            album = song.album ?: "未知专辑",
            albumId = song.albumId,
            durationMs = (song.duration ?: 0).toLong() * 1000,
            trackNumber = song.track ?: 0,
            year = song.year ?: 0,
            genre = song.genre ?: "",
            coverUrl = buildCoverUrl(song.coverArt ?: song.id),
            streamUrl = getStreamUrl(song.id)
        )
    }

    private fun buildCoverUrl(id: String): String {
        return "$baseUrl/rest/getCoverArt.view?id=$id&u=$username&t=$token&s=$salt&v=1.16.1&c=NASMusicTV"
    }

    private fun generateSalt(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return BigInteger(1, md.digest(input.toByteArray())).toString(16).padStart(32, '0')
    }
}

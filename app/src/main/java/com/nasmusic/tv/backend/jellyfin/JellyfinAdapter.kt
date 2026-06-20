package com.nasmusic.tv.backend.jellyfin

import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Song
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Jellyfin 后端适配器实现
 */
class JellyfinAdapter : BackendAdapter {

    override val backendType: String = "jellyfin"

    private var api: JellyfinApi? = null
    private var baseUrl: String = ""
    private var apiToken: String = ""
    private var userId: String = ""

    override suspend fun initialize(
        baseUrl: String,
        apiToken: String,
        username: String,
        password: String
    ): Boolean {
        this.baseUrl = baseUrl.trimEnd('/')
        this.apiToken = apiToken

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
            .create(JellyfinApi::class.java)

        return testConnection()
    }

    override suspend fun testConnection(): Boolean {
        return try {
            val response = api?.getSystemInfo()
            response?.isSuccessful == true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getAlbums(): List<Album> {
        return try {
            val response = api?.getItems(
                token = apiToken,
                userId = userId,
                itemTypes = JellyfinApi.ITEM_TYPE_MUSIC_ALBUM,
                sortBy = "SortName",
                fields = "PrimaryImageAspectRatio,BasicSyncInfo,ChildCount"
            )
            response?.body()?.items?.map { item ->
                Album(
                    id = item.id,
                    name = item.name,
                    artist = item.albumArtist ?: item.albumArtists?.firstOrNull()?.name ?: "未知歌手",
                    artistId = item.albumArtists?.firstOrNull()?.id,
                    year = item.productionYear ?: 0,
                    coverUrl = buildImageUrl(item.id, item.primaryImageTag),
                    songCount = item.childCount ?: 0,
                    durationMs = item.runTimeTicks?.let { it / 10000 } ?: 0
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getAlbumSongs(albumId: String): List<Song> {
        return try {
            val response = api?.getItems(
                token = apiToken,
                userId = userId,
                itemTypes = JellyfinApi.ITEM_TYPE_AUDIO,
                parentId = albumId,
                sortBy = "IndexNumber,SortName",
                fields = "PrimaryImageAspectRatio,BasicSyncInfo"
            )
            response?.body()?.items?.map { item ->
                mapItemToSong(item)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getArtists(): List<Artist> {
        return try {
            val response = api?.getItems(
                token = apiToken,
                userId = userId,
                itemTypes = JellyfinApi.ITEM_TYPE_MUSIC_ARTIST,
                sortBy = "SortName",
                fields = "PrimaryImageAspectRatio,BasicSyncInfo"
            )
            response?.body()?.items?.map { item ->
                Artist(
                    id = item.id,
                    name = item.name,
                    coverUrl = buildImageUrl(item.id, item.primaryImageTag),
                    albumCount = item.childCount ?: 0,
                    songCount = 0
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getArtistSongs(artistId: String): List<Song> {
        return try {
            val response = api?.getItems(
                token = apiToken,
                userId = userId,
                itemTypes = JellyfinApi.ITEM_TYPE_AUDIO,
                recursive = true,
                sortBy = "Album,SortName",
                fields = "PrimaryImageAspectRatio,BasicSyncInfo"
            )
            response?.body()?.items?.filter { item ->
                item.artists?.any { it.equals(artistId, ignoreCase = true) } == true ||
                item.albumArtists?.any { it.id == artistId } == true
            }?.map { mapItemToSong(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getSongs(limit: Int): List<Song> {
        return try {
            val response = api?.getItems(
                token = apiToken,
                userId = userId,
                itemTypes = JellyfinApi.ITEM_TYPE_AUDIO,
                recursive = true,
                sortBy = "SortName",
                fields = "PrimaryImageAspectRatio,BasicSyncInfo"
            )
            response?.body()?.items?.map { mapItemToSong(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun searchSongs(query: String): List<Song> {
        return try {
            val response = api?.getItems(
                token = apiToken,
                userId = userId,
                itemTypes = JellyfinApi.ITEM_TYPE_AUDIO,
                recursive = true,
                searchTerm = query,
                fields = "PrimaryImageAspectRatio,BasicSyncInfo"
            )
            response?.body()?.items?.map { mapItemToSong(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getRecentSongs(): List<Song> {
        return try {
            val response = api?.getLatestItems(
                token = apiToken,
                userId = userId,
                limit = 50
            )
            response?.body()?.filter { it.type == JellyfinApi.ITEM_TYPE_AUDIO }
                ?.map { mapItemToSong(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun getStreamUrl(songId: String): String {
        return "$baseUrl/Audio/$songId/stream?api_key=$apiToken&static=true"
    }

    override fun getCoverUrl(songId: String): String {
        return "$baseUrl/Items/$songId/Images/Primary?api_key=$apiToken&maxHeight=600"
    }

    override suspend fun getLyrics(songId: String): String? {
        // Jellyfin may support lyrics via API in newer versions
        return null
    }

    private fun mapItemToSong(item: JellyfinItem): Song {
        val artist = item.artists?.firstOrNull()
            ?: item.albumArtists?.firstOrNull()?.name
            ?: item.albumArtist
            ?: "未知歌手"

        return Song(
            id = item.id,
            title = item.name,
            artist = artist,
            album = item.album ?: "未知专辑",
            albumId = item.albumId,
            durationMs = item.runTimeTicks?.let { it / 10000 } ?: 0,
            trackNumber = item.indexNumber ?: 0,
            year = item.productionYear ?: 0,
            genre = item.genres?.firstOrNull() ?: "",
            coverUrl = buildImageUrl(item.id, item.primaryImageTag),
            streamUrl = getStreamUrl(item.id)
        )
    }

    private fun buildImageUrl(itemId: String, imageTag: String?): String? {
        return if (imageTag != null) {
            "$baseUrl/Items/$itemId/Images/Primary?tag=$imageTag&api_key=$apiToken&maxHeight=600"
        } else {
            "$baseUrl/Items/$itemId/Images/Primary?api_key=$apiToken&maxHeight=600"
        }
    }
}

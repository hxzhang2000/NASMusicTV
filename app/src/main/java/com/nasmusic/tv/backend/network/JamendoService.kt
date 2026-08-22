package com.nasmusic.tv.backend.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Jamendo（CC 独立音乐）网络音乐源
 *
 * 官方开放 API v3.0（https://api.jamendo.com/v3.0/），无需登录/OAuth（读接口仅需 client_id）。
 * 实现 [NetworkMusicService] 后经 [NetworkMusicManager.registerService] 接入统一的
 * 搜索/播放/歌词/封面路由。本项目不自建后台——本服务仅直连 Jamendo 官方 API。
 *
 * 配额：非商业应用 35,000 次/月——列表/搜索结果由调用方做 LRU 缓存控制配额。
 */
class JamendoService(
    private val clientIdProvider: () -> String,
    private val baseUrl: String = "https://api.jamendo.com/v3.0",
    private val client: OkHttpClient = JamendoService.defaultHttpClient()
) : NetworkMusicService {

    override val sourceId: String = "jamendo"

    private val gson = Gson()

    companion object {
        private const val TAG = "Jamendo"

        /** 预置风格筛选（JamendoSubTab 顶部快捷筛选） */
        val PRESET_TAGS = listOf("ambient", "electronic", "jazz", "filmscore", "chillout", "instrumental", "pop", "rock")

        /** 搜索结果 LRU 缓存（控制 API 配额） */
        private const val SEARCH_CACHE_CAPACITY = 30
        private const val SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // 搜索结果缓存：key=口径(关键词/标签/专辑) value=(时间, songs)
    private val searchCache = object : LinkedHashMap<String, Pair<Long, List<Song>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<Song>>>): Boolean =
            size > SEARCH_CACHE_CAPACITY
    }

    override suspend fun search(keyword: String, limit: Int): List<Song> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        cached("search:${keyword.lowercase()}:$limit") ?: run {
            val songs = requestTracks { p ->
                p.append("&search=").append(encode(keyword))
                if (limit > 0) p.append("&limit=").append(limit)
            }
            remember("search:${keyword.lowercase()}:$limit", songs)
        }
    }

    /**
     * 官方热度榜
     */
    suspend fun hotTracks(limit: Int = 20): List<Song> = withContext(Dispatchers.IO) {
        cached("hot:$limit") ?: run {
            val songs = requestTracks { p ->
                p.append("&order=popularity_total")
                if (limit > 0) p.append("&limit=").append(limit)
            }
            remember("hot:$limit", songs)
        }
    }

    /**
     * 按风格标签筛选
     */
    suspend fun tracksByTag(tag: String, limit: Int = 20): List<Song> = withContext(Dispatchers.IO) {
        if (tag.isBlank()) return@withContext emptyList()
        cached("tag:${tag.lowercase()}:$limit") ?: run {
            val songs = requestTracks { p ->
                p.append("&tags=").append(encode(tag))
                if (limit > 0) p.append("&limit=").append(limit)
            }
            remember("tag:${tag.lowercase()}:$limit", songs)
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        // Jamendo 搜索结果已含直链 audio URL → 直接返回；若被平台要求追加 client_id，补参
        val direct = song.streamUrl?.takeIf { it.isNotBlank() }
        direct ?: song.networkId?.let { netId ->
            val tracks = requestTracks { p -> p.append("&id=").append(netId) }
            tracks.firstOrNull()?.streamUrl
        }
    }

    override suspend fun resolveLyrics(song: Song): String? = withContext(Dispatchers.IO) {
        val netId = song.networkId ?: return@withContext null
        val clientId = clientIdProvider().trim()
        if (clientId.isBlank()) return@withContext null
        try {
            val url = "$baseUrl/tracks/?client_id=${encode(clientId)}&format=json&id=${encode(netId)}&include=lyrics"
            val body = get(url) ?: return@withContext null
            val results = parseResults(body) ?: return@withContext null
            val track = results.firstOrNull()?.let { JamendoModels.parseTrack(it) } ?: return@withContext null
            val lyrics = track.lyrics?.takeIf { it.isNotBlank() } ?: return@withContext null
            // Jamendo 歌词为纯文本行 → 转基础 LRC（无时间轴，行首 [00:00.00]）
            lyrics.lineSequence().joinToString("\n") { "[00:00.00]$it" }
        } catch (e: Exception) {
            AppLog.w(TAG, "resolveLyrics error: ${e.message}")
            null
        }
    }

    override suspend fun resolveCoverUrl(song: Song): String? = null
    override suspend fun searchCoverUrl(title: String, artist: String): String? = null

    override suspend fun getPlaylist(playlistId: String): List<Song> = emptyList()

    // ── 内部 ──

    private suspend fun requestTracks(appendParams: (StringBuilder) -> Unit): List<Song> {
        val clientId = clientIdProvider().trim()
        if (clientId.isBlank()) {
            AppLog.w(TAG, "requestTracks: jamendo client_id 未配置")
            return emptyList()
        }
        try {
            val params = StringBuilder()
            params.append("&include=musicinfo")
            appendParams(params)
            val url = "$baseUrl/tracks/?client_id=${encode(clientId)}&format=json$params"
            val body = get(url) ?: return emptyList()
            val results = parseResults(body) ?: return emptyList()
            return results.mapNotNull { JamendoModels.parseTrack(it) }.map { it.toSong() }
        } catch (e: Exception) {
            AppLog.w(TAG, "requestTracks error: ${e.message}")
            return emptyList()
        }
    }

    private fun JamendoTrack.toSong(): Song = Song(
        id = "ntwk_jamendo_$id",
        title = name,
        artist = artistName,
        album = albumName,
        coverUrl = image,
        streamUrl = audio,
        durationMs = durationMs,
        isNetworkSong = true,
        networkSource = sourceId,
        networkId = id.toString()
    )

    private suspend fun get(url: String): String? {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                AppLog.w(TAG, "GET ${url.take(100)} -> ${resp.code}")
                return null
            }
            return resp.body?.string()
        }
    }

    private fun parseResults(body: String): List<JsonObject>? {
        return try {
            val obj = gson.fromJson(body, JsonObject::class.java) ?: return null
            val results = obj.getAsJsonArray("results") ?: return null
            val type = object : TypeToken<List<JsonObject>>() {}.type
            gson.fromJson(results, type)
        } catch (e: Exception) {
            AppLog.w(TAG, "parseResults error: ${e.message}")
            null
        }
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun cached(key: String): List<Song>? {
        synchronized(searchCache) {
            val entry = searchCache[key] ?: return null
            if (System.currentTimeMillis() - entry.first > SEARCH_CACHE_TTL_MS) {
                searchCache.remove(key)
                return null
            }
            return entry.second
        }
    }

    private fun remember(key: String, songs: List<Song>): List<Song> {
        synchronized(searchCache) {
            searchCache[key] = System.currentTimeMillis() to songs
        }
        return songs
    }
}
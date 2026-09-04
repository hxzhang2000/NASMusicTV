package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.backend.network.baidu.BaiduCoverProvider
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 专辑封面异步解析器
 *
 * 按优先级链为缺少封面的专辑解析封面：
 * 1. 百度侧车/APIC — 同目录 cover.jpg 或内嵌 ID3 APIC
 * 2. iTunes 在线搜索 — 按 album+artist 搜封面图
 * 3. 本地ID3 — 从本地音频文件重新提取（MediaStore 通常已做，此步为补充）
 * 4. 歌曲 coverUrl — 最终兜底（buildLocalAlbums/buildBaiduAlbums 已做）
 *
 * 只处理 coverUrl 为 null 的专辑；已有封面的跳过。
 * 解析结果通过回调更新到 mergedAlbums 列表。
 */
class AlbumCoverResolver(
    private val baiduCoverProvider: BaiduCoverProvider?,
    private val client: OkHttpClient,
    private val searchCover: suspend (title: String, artist: String) -> String? = { _, _ -> null }
) {
    companion object {
        private const val TAG = "AlbumCoverResolver"
        /** iTunes Search API 端点 */
        private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
        /** 并发解析上限（避免请求过猛） */
        private const val MAX_CONCURRENT = 5
    }

    /**
     * 批量解析专辑封面，对缺少封面的专辑按优先级链尝试解析。
     *
     * @param albums 当前专辑列表
     * @param allSongs 所有可用歌曲（NAS + 本地 + 百度）
     * @param onUpdated 每次解析到一个更好的封面时回调，参数为更新后的完整专辑列表
     */
    suspend fun resolveCovers(
        albums: List<Album>,
        allSongs: List<Song>,
        onUpdated: (List<Album>) -> Unit
    ) = withContext(Dispatchers.IO) {
        // 按 albumName 建立歌曲索引（用于封面解析）
        val albumSongsMap = mutableMapOf<String, MutableList<Song>>()
        for (song in allSongs) {
            val albumName = song.album.trim()
            if (albumName.isNotBlank()) {
                albumSongsMap.getOrPut(albumName.lowercase()) { mutableListOf() }.add(song)
            }
        }
        // 另外按 path 建索引（百度专辑用 path 目录名推断）
        for (song in allSongs) {
            if (song.path != null && song.album.isBlank()) {
                val segments = song.path!!.trim('/').split("/")
                val dirName = segments.getOrNull(segments.size - 2) ?: continue
                if (dirName.isBlank()) continue
                albumSongsMap.getOrPut(dirName.lowercase()) { mutableListOf() }.add(song)
            }
        }

        var updatedAlbums = albums
        var processed = 0

        for (album in albums) {
            // 已有封面 → 跳过
            if (album.coverUrl != null) continue

            val albumKey = album.name.lowercase().trim()
            val songs = albumSongsMap[albumKey] ?: emptyList()
            var resolvedUrl: String? = null

            // P1: 百度侧车/APIC
            if (resolvedUrl == null && baiduCoverProvider != null) {
                resolvedUrl = resolveBaiduCover(songs)
            }

            // P2: iTunes 在线搜索
            if (resolvedUrl == null) {
                resolvedUrl = resolveItunesCover(album.name, album.artist)
            }

            // P2.5: 网络封面（Meting/网易云）— 主要针对无内嵌封面的百度网盘专辑
            //
            // 百度专辑名是从**目录名**推断的（见 MusicMerger.buildBaiduAlbums），目录名常不规范
            // （"周杰伦"、"新建文件夹" 等），直接拿它当检索词命中率很低。
            // 因此按「信息可靠度」依次尝试多组检索词：优先用歌曲标题（来自文件名/ID3，质量更高），
            // 标题失败再退回目录名推断的专辑名；每组都再试一次「不带艺术家」的宽检索。
            // （searchCover 实现见 MetingApiService：artist 为空时按纯标题检索，安全）
            if (resolvedUrl == null && songs.any { it.networkSource == "baidu" }) {
                val repSong = songs.firstOrNull { it.title.isNotBlank() }
                if (repSong != null) {
                    val title = repSong.title.trim()
                    val artist = repSong.artist.trim()
                    val albumName = album.name.trim()
                    val candidates = listOf(
                        title to artist,
                        title to "",
                        albumName to artist,
                        albumName to ""
                    ).distinct().filter { it.first.isNotBlank() }

                    for ((qTitle, qArtist) in candidates) {
                        val hit = runCatching { searchCover(qTitle, qArtist) }.getOrNull()
                        if (!hit.isNullOrBlank()) {
                            resolvedUrl = hit
                            break
                        }
                    }
                }
            }

            // P3: 本地 ID3 — 对于本地歌曲，MediaStore 已提取到 song.coverUrl；
            //     这里取专辑内第一首有 coverUrl 的歌曲封面
            if (resolvedUrl == null) {
                resolvedUrl = songs.firstOrNull { it.coverUrl != null }?.coverUrl
            }

            // P4: 歌曲 coverUrl 兜底 — 已在 buildLocalAlbums/buildBaiduAlbums 中处理
            // 此处 resolvedUrl 可能为 null，表示所有方法都未能解析

            if (resolvedUrl != null) {
                val idx = updatedAlbums.indexOfFirst { it.id == album.id }
                if (idx >= 0) {
                    updatedAlbums = updatedAlbums.toMutableList().apply {
                        this[idx] = album.copy(coverUrl = resolvedUrl)
                    }
                    processed++
                    // 每 MAX_CONCURRENT 个回调一次，避免频繁更新 UI
                    if (processed % MAX_CONCURRENT == 0) {
                        onUpdated(updatedAlbums)
                    }
                }
            }
        }

        // 最终回调
        if (processed > 0) {
            onUpdated(updatedAlbums)
        }

        AppLog.d(TAG, "resolveCovers: processed=$processed/${albums.count { it.coverUrl == null }}")
    }

    /**
     * P1: 百度侧车/APIC 封面
     * 取专辑内第一首百度歌曲，用 BaiduCoverProvider 解析
     */
    private suspend fun resolveBaiduCover(songs: List<Song>): String? {
        val baiduSong = songs.firstOrNull { it.networkSource == "baidu" && it.networkId != null }
            ?: return null
        return try {
            baiduCoverProvider?.getCover(
                fsId = baiduSong.networkId!!.toLong(),
                title = baiduSong.title,
                artist = baiduSong.artist.ifBlank { null },
                path = baiduSong.path
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "resolveBaiduCover failed: ${e.message?.take(50)}")
            null
        }
    }

    /**
     * P2: iTunes Search API 在线封面
     *
     * 请求：GET https://itunes.apple.com/search?term={album+artist}&entity=album&limit=1
     * 响应：JSON，results[0].artworkUrl100 为 100x100 封面图 URL
     * 可替换为 600x600：将 "100x100" 替换为 "600x600"
     */
    private fun resolveItunesCover(albumName: String, artistName: String): String? {
        if (albumName.isBlank()) return null
        return try {
            val query = buildString {
                append(albumName)
                if (artistName.isNotBlank()) {
                    append(" ")
                    append(artistName)
                }
            }.replace(" ", "+")
            val url = "$ITUNES_SEARCH_URL?term=${java.net.URLEncoder.encode(query, "UTF-8")}&entity=album&limit=1"
            val request = Request.Builder().url(url)
                .header("User-Agent", "NASMusicTV/1.0")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return null
                if (results.length() == 0) return null
                val first = results.getJSONObject(0)
                val artworkUrl = first.optString("artworkUrl100", null) ?: return null
                // 替换为更高分辨率
                artworkUrl.replace("100x100", "600x600")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "resolveItunesCover failed for '$albumName': ${e.message?.take(50)}")
            null
        }
    }
}

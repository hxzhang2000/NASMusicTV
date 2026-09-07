package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.ArtistSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 艺术家封面异步解析器
 *
 * 为缺少封面的艺术家按优先级链解析封面：
 * 1. 网易云音乐 API — 按 artist 搜歌手头像（华语歌手覆盖率最高）
 * 2. 酷狗音乐 API — 按 artist 搜歌手头像
 * 3. iTunes — 按 artist 搜艺术家图片（欧美歌手命中率高）
 * 4. 该艺术家名下第一首有封面的歌曲封面（兜底）
 * 5. 首字母占位（UI 层，无封面时显示）
 *
 * 只处理 coverUrl 为 null 的艺术家；已有封面的跳过。
 * 解析结果通过回调更新到 artist 列表。
 */
class ArtistCoverResolver(
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "ArtistCoverResolver"
        /** 网易云音乐搜索 API（歌手：type=100） */
        private const val NETEASE_SEARCH_URL = "https://music.163.com/api/search/get/web"
        /** 酷狗音乐歌手搜索 API */
        private const val KUGOU_SEARCH_URL = "https://msearch.kugou.com/api/v3/search/singer"
        /** iTunes Search API 端点 */
        private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
        /** 并发解析上限（避免请求过猛） */
        private const val MAX_CONCURRENT = 5
    }

    /**
     * 单曲级艺术家封面 URL 解析（暴露现有私有的网易/酷狗/iTunes 链）。
     *
     * 用于下载流程的 [com.nasmusic.tv.backend.download.CoverFileWriter.writeArtistCover]：
     * 在不调用批量 [resolveCovers] 的前提下获取单歌手的封面 URL。
     *
     * 优先级链与 [resolveCovers] 一致：网易云 → 酷狗 → iTunes（不含 P4 歌曲兜底，
     * 因为单曲流程下游会自己用 song.coverUrl 兜底）。
     */
    suspend fun resolveArtistCoverUrl(name: String): String? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        resolveNeteaseArtistCover(name)
            ?: resolveKugouArtistCover(name)
            ?: resolveItunesArtistCover(name)
    }

    /**
     * 批量解析艺术家封面，对缺少封面的艺术家按优先级链尝试解析。
     *
     * 优先级链：网易云音乐 → 酷狗音乐 → iTunes → 该艺术家歌曲封面 → 首字母占位（UI 层）。
     *
     * @param artists 当前艺术家列表
     * @param allSongs 全量歌曲列表（NAS + 本地 + 百度），用于 P4 歌曲封面兜底
     * @param onUpdated 每次解析到一个更好的封面时回调，参数为更新后的完整艺术家列表
     */
    suspend fun resolveCovers(
        artists: List<Artist>,
        allSongs: List<Song>,
        onUpdated: (List<Artist>) -> Unit
    ) = withContext(Dispatchers.IO) {
        var updatedArtists = artists
        var processed = 0

        for (artist in artists) {
            // 已有封面 → 跳过
            if (artist.coverUrl != null) continue
            // 跳过本地艺术家（ID 以 local_ 开头，其封面由 buildLocalArtists 的 useSongCover 兜底）
            if (artist.id.startsWith("local_")) continue

            val artistName = artist.name.trim()
            if (artistName.isBlank()) continue

            var resolvedUrl: String? = null

            // P1: 网易云音乐（华语歌手头像）
            resolvedUrl = resolveNeteaseArtistCover(artistName)

            // P2: 酷狗音乐（歌手头像）
            if (resolvedUrl == null) {
                resolvedUrl = resolveKugouArtistCover(artistName)
            }

            // P3: iTunes（欧美艺术家图片）
            if (resolvedUrl == null) {
                resolvedUrl = resolveItunesArtistCover(artistName)
            }

            // P4: 该艺术家名下第一首有封面的歌曲封面（兜底，避免在线源全失败时空白）
            if (resolvedUrl == null) {
                resolvedUrl = findArtistSongCover(artistName, allSongs)
            }

            if (resolvedUrl != null) {
                val idx = updatedArtists.indexOfFirst { it.id == artist.id }
                if (idx >= 0) {
                    updatedArtists = updatedArtists.toMutableList().apply {
                        this[idx] = artist.copy(coverUrl = resolvedUrl)
                    }
                    processed++
                    // 每 MAX_CONCURRENT 个回调一次，避免频繁更新 UI
                    if (processed % MAX_CONCURRENT == 0) {
                        onUpdated(updatedArtists)
                    }
                }
            }
        }

        // 最终回调
        if (processed > 0) {
            onUpdated(updatedArtists)
        }

        AppLog.d(TAG, "resolveCovers: processed=$processed/${artists.count { it.coverUrl == null }}")
    }

    /**
     * P3: iTunes Search API 在线艺术家封面（欧美歌手命中率高，中文歌手低）
     *
     * 请求：GET https://itunes.apple.com/search?term={artist}&entity=musicArtist&limit=1
     * 响应：JSON，results[0].artistLinkUrl 为艺术家页面 URL
     *       可从 artistLinkUrl 提取艺术家 ID，再获取图片
     *       或直接用 artworkUrl100（如果有）
     */
    private fun resolveItunesArtistCover(artistName: String): String? {
        if (artistName.isBlank()) return null
        return try {
            val query = java.net.URLEncoder.encode(artistName, "UTF-8")
            val url = "$ITUNES_SEARCH_URL?term=$query&entity=musicArtist&limit=1"
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
                // iTunes 音乐艺术家搜索结果中可能没有 artworkUrl100
                // 尝试从 artistLinkUrl 提取艺术家 ID，再获取图片
                val artworkUrl = first.optString("artworkUrl100", null)
                if (!artworkUrl.isNullOrBlank()) {
                    // 替换为更高分辨率
                    return artworkUrl.replace("100x100", "600x600")
                }
                // 尝试从 artistLinkUrl 提取艺术家 ID
                val artistLinkUrl = first.optString("artistLinkUrl", null)
                if (!artistLinkUrl.isNullOrBlank()) {
                    // 从 URL 提取艺术家 ID（格式：https://music.apple.com/us/artist/.../id123456）
                    val idMatch = Regex("/id(\\d+)").find(artistLinkUrl)
                    if (idMatch != null) {
                        val artistId = idMatch.groupValues[1]
                        // 获取艺术家详情（包含图片）
                        return getItunesArtistImage(artistId)
                    }
                }
                null
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "resolveItunesArtistCover failed for '$artistName': ${e.message?.take(50)}")
            null
        }
    }

    /**
     * 通过 iTunes 艺术家 ID 获取图片
     */
    private fun getItunesArtistImage(artistId: String): String? {
        return try {
            val url = "https://itunes.apple.com/lookup?id=$artistId&entity=musicArtist"
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
            AppLog.w(TAG, "getItunesArtistImage failed for ID=$artistId: ${e.message?.take(50)}")
            null
        }
    }

    /**
     * P1: 网易云音乐搜索歌手头像（华语歌手覆盖率最高）。
     *
     * 请求：GET https://music.163.com/api/search/get/web?csrf_token=&s={artist}&type=100&offset=0&limit=10
     * 响应：JSON，result.artists[0].img1v1Url 为歌手头像（p1/p2/p3.music.126.net 域名）。
     */
    private fun resolveNeteaseArtistCover(artistName: String): String? {
        if (artistName.isBlank()) return null
        return try {
            val query = java.net.URLEncoder.encode(artistName, "UTF-8")
            val url = "$NETEASE_SEARCH_URL?csrf_token=&s=$query&type=100&offset=0&limit=10"
            val request = Request.Builder().url(url)
                .header("User-Agent", "NASMusicTV/1.0")
                .header("Referer", "https://music.163.com/")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val result = json.optJSONObject("result") ?: return null
                val artists = result.optJSONArray("artists") ?: return null
                if (artists.length() == 0) return null
                val first = artists.getJSONObject(0)
                // img1v1Url 为歌手头像（或 artist 名下的专辑封面）
                val avatar = first.optString("img1v1Url", null)
                if (avatar.isNullOrBlank()) return null
                // 网易云头像 URL 为 http://p1.music.126.net/...，转 https
                if (avatar.startsWith("http://")) avatar.replace("http://", "https://") else avatar
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "resolveNeteaseArtistCover failed for '$artistName': ${e.message?.take(50)}")
            null
        }
    }

    /**
     * P2: 酷狗音乐搜索歌手头像。
     *
     * 请求：GET https://msearch.kugou.com/api/v3/search/singer?keyword={artist}&page=1&pagesize=10
     * 响应：JSON，data.info[0].img 为歌手头像。
     */
    private fun resolveKugouArtistCover(artistName: String): String? {
        if (artistName.isBlank()) return null
        return try {
            val query = java.net.URLEncoder.encode(artistName, "UTF-8")
            val url = "$KUGOU_SEARCH_URL?keyword=$query&page=1&pagesize=10"
            val request = Request.Builder().url(url)
                .header("User-Agent", "NASMusicTV/1.0")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val data = json.optJSONObject("data") ?: return null
                val info = data.optJSONArray("info") ?: return null
                if (info.length() == 0) return null
                val first = info.getJSONObject(0)
                val avatar = first.optString("img", null) ?: first.optString("singer_img", null)
                if (avatar.isNullOrBlank()) return null
                // 确保 HTTPS
                if (avatar.startsWith("http://")) avatar.replace("http://", "https://") else avatar
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "resolveKugouArtistCover failed for '$artistName': ${e.message?.take(50)}")
            null
        }
    }

    /**
     * P4: 该艺术家名下第一首有封面的歌曲封面（兜底）。
     *
     * 在线源（网易云/酷狗/iTunes）全失败时，从全量歌曲里找该艺术家的第一首有封面歌曲。
     * 用 ArtistSplitter.normalizeKey 归一化匹配，兼容 "古天乐" 与 "古天乐 " 这类差异。
     *
     * @param allSongs 全量歌曲（NAS + 本地 + 百度）
     */
    private fun findArtistSongCover(artistName: String, allSongs: List<Song>): String? {
        if (artistName.isBlank() || allSongs.isEmpty()) return null
        val targetKey = ArtistSplitter.normalizeKey(artistName)
        if (targetKey.isBlank()) return null
        return allSongs.firstOrNull { song ->
            song.coverUrl != null &&
                song.artist.isNotBlank() &&
                ArtistSplitter.normalizeKey(song.artist) == targetKey
        }?.coverUrl
    }
}

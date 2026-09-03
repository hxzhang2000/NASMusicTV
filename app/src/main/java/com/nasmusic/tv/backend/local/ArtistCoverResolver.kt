package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 艺术家封面异步解析器
 *
 * 按优先级链为缺少封面的艺术家解析封面：
 * 1. iTunes 在线搜索 — 按 artist 搜艺术家图片
 * 2. 百度音乐搜索 — 按 artist 搜歌手图片
 *
 * 只处理 coverUrl 为 null 的艺术家；已有封面的跳过。
 * 解析结果通过回调更新到 artist 列表。
 */
class ArtistCoverResolver(
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "ArtistCoverResolver"
        /** iTunes Search API 端点 */
        private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
        /** 百度音乐搜索 API 端点 */
        private const val BAIDU_MUSIC_SEARCH_URL = "https://musicapi.taihe.com/v1/restserver/ting"
        /** 并发解析上限（避免请求过猛） */
        private const val MAX_CONCURRENT = 5
    }

    /**
     * 批量解析艺术家封面，对缺少封面的艺术家按优先级链尝试解析。
     *
     * @param artists 当前艺术家列表
     * @param onUpdated 每次解析到一个更好的封面时回调，参数为更新后的完整艺术家列表
     */
    suspend fun resolveCovers(
        artists: List<Artist>,
        onUpdated: (List<Artist>) -> Unit
    ) = withContext(Dispatchers.IO) {
        var updatedArtists = artists
        var processed = 0

        for (artist in artists) {
            // 已有封面 → 跳过
            if (artist.coverUrl != null) continue
            // 跳过本地艺术家（ID 以 local_ 开头）
            if (artist.id.startsWith("local_")) continue

            val artistName = artist.name.trim()
            if (artistName.isBlank()) continue

            var resolvedUrl: String? = null

            // P1: iTunes 在线搜索（艺术家图片）
            resolvedUrl = resolveItunesArtistCover(artistName)

            // P2: 百度音乐搜索（歌手图片）
            if (resolvedUrl == null) {
                resolvedUrl = resolveBaiduArtistCover(artistName)
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
     * P1: iTunes Search API 在线艺术家封面
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
     * P2: 百度音乐搜索歌手图片
     *
     * 使用百度音乐搜索 API 搜索歌手信息，获取歌手图片
     */
    private fun resolveBaiduArtistCover(artistName: String): String? {
        if (artistName.isBlank()) return null
        return try {
            val query = java.net.URLEncoder.encode(artistName, "UTF-8")
            val url = "$BAIDU_MUSIC_SEARCH_URL?method=baidu.ting.search.catalogSug&query=$query"
            val request = Request.Builder().url(url)
                .header("User-Agent", "NASMusicTV/1.0")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val result = json.optJSONObject("result") ?: return null
                val artistInfo = result.optJSONObject("artist") ?: return null
                val avatar = artistInfo.optString("avatar", null) ?: return null
                // 确保是 HTTPS
                if (avatar.startsWith("http://")) {
                    avatar.replace("http://", "https://")
                } else {
                    avatar
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "resolveBaiduArtistCover failed for '$artistName': ${e.message?.take(50)}")
            null
        }
    }
}

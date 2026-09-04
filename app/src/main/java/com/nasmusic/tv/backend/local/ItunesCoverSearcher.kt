package com.nasmusic.tv.backend.local

import com.nasmusic.tv.util.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * iTunes Search API 封面搜索工具（共享给单曲级与专辑级封面链路）
 *
 * 封装两种实体搜索：
 * - [searchTrack]：按歌曲标题+歌手（entity=musicTrack）搜歌曲封面
 * - [searchAlbum]：按专辑名+歌手（entity=album）搜专辑封面
 *
 * 响应：results[0].artworkUrl100 为 100x100 封面图 URL，替换 100x100→600x600 得高清图。
 */
class ItunesCoverSearcher(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "ItunesCover"
        private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
    }

    /** 按歌曲标题+歌手搜索歌曲封面（entity=musicTrack） */
    suspend fun searchTrack(title: String, artist: String): String? {
        if (title.isBlank()) return null
        val query = buildString {
            append(title)
            if (artist.isNotBlank()) {
                append(" ")
                append(artist)
            }
        }
        return search(query, "musicTrack")
    }

    /** 按专辑名+歌手搜索专辑封面（entity=album） */
    suspend fun searchAlbum(albumName: String, artist: String): String? {
        if (albumName.isBlank()) return null
        val query = buildString {
            append(albumName)
            if (artist.isNotBlank()) {
                append(" ")
                append(artist)
            }
        }
        return search(query, "album")
    }

    private fun search(query: String, entity: String): String? {
        return try {
            // URLEncoder.encode 会把空格编码为 +、中文编码为 %XX；
            // 不要再预先 replace(" ", "+")，否则 + 被二次编码成 %2B 破坏检索词。
            val url = "$ITUNES_SEARCH_URL?term=${java.net.URLEncoder.encode(query, "UTF-8")}&entity=$entity&limit=1"
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
                artworkUrl.replace("100x100", "600x600")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "itunes search failed for '$query': ${e.message?.take(50)}")
            null
        }
    }
}
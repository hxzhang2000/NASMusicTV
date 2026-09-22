package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.ArtistSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 艺术家封面异步解析器
 *
 * 为缺少封面的艺术家按完整优先级链解析封面：
 *
 * **Jellyfin 层**（由 [com.nasmusic.tv.backend.impl.JellyfinAdapter.getArtists] 构造）：
 * 0a. Jellyfin Primary 图片 tag → 带 tag 的精确 URL
 * 0b. Jellyfin Backdrop 图片 → Backdrop URL
 * 0c. 以上均无 → coverUrl = null，交给本解析器
 *
 * **在线源 + 本地兜底**（本解析器）：
 * 1. 网易云音乐 API — 按 artist 搜歌手头像（华语歌手覆盖率最高）
 * 2. 酷狗音乐 API — 按 artist 搜歌手头像
 * 3. iTunes — 按 artist 搜艺术家图片（欧美歌手命中率高）
 * 4. 该艺术家名下第一首有封面的歌曲封面（预构建索引 O(1) 查找）
 *
 * **P4 补充通道**（[resolveSongCoversOnly]）：
 * 当在线源尝试次数耗尽后，每次歌曲库更新仍会重新用 P4 匹配新加载的歌曲封面。
 *
 * **UI 层**：以上均未命中时显示首字母占位。
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

    /** UI 批次回调大小（每解析满 N 个回调一次；与并发数解耦） */
    private const val CALLBACK_BATCH = 5

    /** 单源连续无命中熔断阈值：达到后本轮跳过该源（防被限流的第三方连坐轰炸） */
    private const val SOURCE_FAIL_LIMIT = 8
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
        // P2 修复（2026-09-22 审查）：原实现逐个艺术家串行发最多 3 个阻塞 HTTP 请求
        //（MAX_CONCURRENT=5 实为回调批次大小而非并发，命名误导）——大曲库首刷可达
        // 上千次串行第三方请求，分钟级且易触发限流。改为：
        //   并发池（MAX_CONCURRENT 并发）+ 每槽 150ms 起步节流 + 单源连续 8 次无命中
        //   熔断冷却 + 批次回调语义保留（满 CALLBACK_BATCH 回调一次 + 最终回调）。
        var updatedArtists = artists
        var processed = 0

        // 预构建 P4 索引：normalizeKey(artistName) → 第一首有封面歌曲的 coverUrl
        // O(songs) 构建一次，后续每个艺术家 O(1) 查找（旧实现 O(artists × songs) 遍历）
        val songCoverByArtist = buildSongCoverIndex(allSongs)

        val pending = artists.filter {
            it.coverUrl == null && !it.id.startsWith("local_") && it.name.isNotBlank()
        }
        val neteaseFails = AtomicInteger(0)
        val kugouFails = AtomicInteger(0)
        val itunesFails = AtomicInteger(0)
        val updateMutex = Mutex()
        val permits = Semaphore(MAX_CONCURRENT)
        val totalPending = pending.size

        coroutineScope {
            for ((index, artist) in pending.withIndex()) {
                launch {
                    permits.withPermit {
                        // 起步节流：并发槽错峰发起（5 并发 × 150ms ≈ ≤27 req/min/源，
                        // iTunes 限流量级以内），避免开闸瞬间齐射
                        delay((index % MAX_CONCURRENT) * 150L)
                        val artistName = artist.name.trim()

                        // P1 网易云 → P2 酷狗 → P3 iTunes：单源连续 SOURCE_FAIL_LIMIT
                        // 次无命中则本轮熔断（不再对被限流的源连坐轰炸）
                        var resolvedUrl: String? = null
                        if (neteaseFails.get() < SOURCE_FAIL_LIMIT) {
                            resolvedUrl = resolveNeteaseArtistCover(artistName)
                            if (resolvedUrl == null) neteaseFails.incrementAndGet() else neteaseFails.set(0)
                        }
                        if (resolvedUrl == null && kugouFails.get() < SOURCE_FAIL_LIMIT) {
                            resolvedUrl = resolveKugouArtistCover(artistName)
                            if (resolvedUrl == null) kugouFails.incrementAndGet() else kugouFails.set(0)
                        }
                        if (resolvedUrl == null && itunesFails.get() < SOURCE_FAIL_LIMIT) {
                            resolvedUrl = resolveItunesArtistCover(artistName)
                            if (resolvedUrl == null) itunesFails.incrementAndGet() else itunesFails.set(0)
                        }
                        // P4: 该艺术家名下第一首有封面的歌曲封面（O(1) 查找预构建索引）
                        if (resolvedUrl == null) {
                            resolvedUrl = songCoverByArtist[ArtistSplitter.normalizeKey(artistName)]
                        }

                        if (resolvedUrl != null) {
                            updateMutex.withLock {
                                val idx = updatedArtists.indexOfFirst { it.id == artist.id }
                                if (idx >= 0) {
                                    updatedArtists = updatedArtists.toMutableList().apply {
                                        this[idx] = artist.copy(coverUrl = resolvedUrl)
                                    }
                                    processed++
                                    // 每 CALLBACK_BATCH 个回调一次，避免频繁更新 UI
                                    if (processed % CALLBACK_BATCH == 0) {
                                        onUpdated(updatedArtists)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 最终回调
        if (processed > 0) {
            onUpdated(updatedArtists)
        }

        AppLog.d(TAG, "resolveCovers: processed=$processed/$totalPending, songCoverMap=${songCoverByArtist.size}")
    }

    /**
     * 仅用 P4 歌曲封面兜底（不调在线源），用于在线源已耗尽但歌曲库有新数据时的补充解析。
     *
     * 当 [resolveCovers] 因 [com.nasmusic.tv.ui.viewmodel.MainViewModel.artistCoverMaxAttempts]
     * 限制不再对某艺术家调用在线源时，此方法在每次歌曲库更新后重新扫描，确保 P4 能命中
     * 新加载的歌曲封面。不消耗在线源尝试次数。
     */
    suspend fun resolveSongCoversOnly(
        artists: List<Artist>,
        allSongs: List<Song>,
        onUpdated: (List<Artist>) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (allSongs.isEmpty()) return@withContext

        val songCoverByArtist = buildSongCoverIndex(allSongs)
        var updatedArtists = artists
        var processed = 0

        for (artist in artists) {
            if (artist.coverUrl != null) continue
            if (artist.id.startsWith("local_")) continue

            val key = ArtistSplitter.normalizeKey(artist.name.trim())
            val resolvedUrl = songCoverByArtist[key]

            if (resolvedUrl != null) {
                val idx = updatedArtists.indexOfFirst { it.id == artist.id }
                if (idx >= 0) {
                    updatedArtists = updatedArtists.toMutableList().apply {
                        this[idx] = artist.copy(coverUrl = resolvedUrl)
                    }
                    processed++
                    if (processed % MAX_CONCURRENT == 0) {
                        onUpdated(updatedArtists)
                    }
                }
            }
        }

        if (processed > 0) {
            onUpdated(updatedArtists)
        }

        AppLog.d(TAG, "resolveSongCoversOnly: processed=$processed, songCoverMap=${songCoverByArtist.size}")
    }

    /**
     * 从全量歌曲构建 P4 索引：normalizeKey(artistName) → 第一首有封面歌曲的 coverUrl。
     * 只保留每个艺术家名下第一首有封面的歌曲，避免重复。
     */
    private fun buildSongCoverIndex(allSongs: List<Song>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (song in allSongs) {
            if (song.coverUrl == null || song.artist.isBlank()) continue
            val key = ArtistSplitter.normalizeKey(song.artist)
            if (key.isNotBlank() && key !in map) {
                map[key] = song.coverUrl
            }
        }
        return map
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
     * 注意：[resolveCovers] 已改为使用预构建的 [buildSongCoverIndex] 进行 O(1) 查找，
     * 此方法保留供 [resolveArtistCoverUrl] 单曲流程或其他场景使用。
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

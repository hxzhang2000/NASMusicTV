package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.backend.network.baidu.BaiduCoverProvider
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 专辑封面异步解析器
 *
 * 按优先级链为缺少封面的专辑解析封面：
 * 0. 索引缓存 — 优先复用已持久化的稳定封面（iTunes/网络搜索的 HTTP URL）
 * 1. 百度侧车封面 — 同目录 cover/folder/album/front 图（不取 APIC）
 * 2. iTunes 在线搜索 — 按 album+artist 搜封面图
 * 3. 网络封面搜索 — 按 album+artist 搜（Meting/网易云）
 * 4. 第一首 baidu 歌的内嵌 APIC — 从歌曲文件头部 Range 解析 ID3 APIC 帧
 * 5. 第一首有 coverUrl 的歌曲封面 — 最终兜底
 * 6. 仍为空 → 上层用默认图
 *
 * 只处理 coverUrl 为 null 的专辑；已有封面的跳过。
 * 解析结果通过回调更新到 mergedAlbums 列表。
 */
class AlbumCoverResolver(
    private val baiduCoverProvider: BaiduCoverProvider?,
    private val client: OkHttpClient,
    private val searchCover: suspend (title: String, artist: String) -> String? = { _, _ -> null },
    /** 百度网盘索引缓存（持久化稳定封面 URL 用），未注入时为 null */
    private val baiduIndexCache: com.nasmusic.tv.backend.network.baidu.BaiduFileIndexCache? = null
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

        // P2 修复（2026-09-22 审查）：原实现逐专辑串行（侧车/iTunes/网络搜索/APIC
        // 多个阻塞请求），大曲库首刷分钟级且易触发限流。与 ArtistCoverResolver 同款：
        // Semaphore 并发 + 错峰节流；已封面专辑的索引同步（无网络）保持串行；
        // 索引批量写与批次回调语义保留。setCoverUrls 已收敛进 cacheLock，并发安全。
        var updatedAlbums = albums
        var processed = 0
        // 批量收集 fsId → coverUrl，最后一次性写入索引（避免 O(n2) 反复序列化 46K 条目）
        val pendingIndexUpdates = java.util.concurrent.ConcurrentHashMap<Long, String>()
        val stateMutex = Mutex()
        val permits = Semaphore(MAX_CONCURRENT)

        // 已有封面的专辑：仅同步同专辑 baidu 歌的索引（歌曲列表显示用，无网络请求）
        for (album in albums) {
            if (album.coverUrl == null) continue
            val albumKey = album.name.lowercase().trim()
            val songs = albumSongsMap[albumKey] ?: emptyList()
            if (baiduIndexCache != null) {
                songs.mapNotNull { song ->
                    if (song.networkSource == "baidu") song.networkId?.toLongOrNull() else null
                }.forEach { pendingIndexUpdates[it] = album.coverUrl }
            }
        }

        // 待解析专辑：并发解析
        val pending = albums.filter { it.coverUrl == null }
        val totalPending = pending.size
        coroutineScope {
            for ((index, album) in pending.withIndex()) {
                launch {
                    permits.withPermit {
                        // 错峰节流（对齐 ArtistCoverResolver：5 并发 × 150ms ≈ 27 req/min/源）
                        delay((index % MAX_CONCURRENT) * 150L)
                        val albumKey = album.name.lowercase().trim()
                        val songs = albumSongsMap[albumKey] ?: emptyList()
                        // 专辑内所有 baidu 歌的 fsId（用于索引缓存：同专辑歌曲共享封面 URL）
                        val baiduFsIds = songs.mapNotNull { song ->
                            if (song.networkSource == "baidu") song.networkId?.toLongOrNull() else null
                        }
                        val baiduFsId = baiduFsIds.firstOrNull()  // 用于侧车/APIC（取第一首）
                        val baiduSong = songs.firstOrNull { it.networkSource == "baidu" && it.networkId != null }

                        var resolvedUrl: String? = null

                        // 0. 优先复用索引中已持久化的稳定封面（避免重复网络搜索）
                        if (baiduIndexCache != null && baiduFsId != null) {
                            resolvedUrl = baiduIndexCache.getCoverUrl(baiduFsId)
                        }

                        // P1: 侧车封面（同目录 cover 图，不取 APIC）
                        if (resolvedUrl == null && baiduCoverProvider != null) {
                            resolvedUrl = baiduCoverProvider.findSidecarCoverOnly(baiduSong?.path)
                        }

                        // P2: iTunes 在线搜索（专辑名+歌手）
                        if (resolvedUrl == null) {
                            resolvedUrl = resolveItunesCover(album.name, album.artist)
                        }

                        // P3: 网络封面搜索（专辑名+歌手）— 针对无内嵌封面的百度网盘专辑
                        if (resolvedUrl == null) {
                            resolvedUrl = runCatching {
                                searchCover(album.name.trim(), album.artist.trim())
                            }.getOrNull()
                        }

                        // P4: 取专辑内第一首 baidu 歌的内嵌 APIC
                        if (resolvedUrl == null && baiduCoverProvider != null) {
                            resolvedUrl = baiduCoverProvider.extractApicOnly(baiduFsId)
                        }

                        // P5: 取专辑内第一首有 coverUrl 的歌曲封面
                        if (resolvedUrl == null) {
                            resolvedUrl = songs.firstOrNull { it.coverUrl != null }?.coverUrl
                        }

                        // P6: 仍为空 → 上层走默认图

                        if (resolvedUrl != null) {
                            stateMutex.withLock {
                                // 命中稳定网络封面（非动态 dlink/APIC）→ 收集待批量写入索引
                                if (baiduIndexCache != null && resolvedUrl.startsWith("http")) {
                                    baiduFsIds.forEach { pendingIndexUpdates[it] = resolvedUrl }
                                }
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
                    }
                }
            }
        }

        // 批量写入索引（单次文件 I/O，避免 O(n2) 反复序列化 46K 条目）
        if (pendingIndexUpdates.isNotEmpty() && baiduIndexCache != null) {
            baiduIndexCache.setCoverUrls(pendingIndexUpdates)
        }

        // 最终回调
        if (processed > 0) {
            onUpdated(updatedAlbums)
        }

        AppLog.d(TAG, "resolveCovers: processed=$processed/$totalPending, indexUpdates=${pendingIndexUpdates.size}")
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
            }
            // B16 修复：不再 `.replace(" ", "+")`。URLEncoder.encode 本身会把空格编码为 `+`、
            // 中文编码为 %XX；先 replace 成 `+` 再 encode 会把 `+` 二次编码成 `%2B`，
            // 导致中文搜索词被破坏（iTunes 搜到「字面加号」而非空格分隔的词）。
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
                // B16 修复：返回替换后的高清图（原实现 `replace` 结果被丢弃，
                // 实际永远返回 100x100 低清图）。
                artworkUrl.replace("100x100", "600x600")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "resolveItunesCover failed for '$albumName': ${e.message?.take(50)}")
            null
        }
    }
}

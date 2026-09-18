package com.nasmusic.tv.backend.playlist

import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.AppLog

/**
 * 播放触发的元数据补全（docs/playlist-import-feature-plan.md §4.3）。
 *
 * 职责：把导入歌单里的裸 stub（id 以 "imported_" 开头）升级为可播放的真 Song：
 * 1) [enrichSong]：已连 NAS → [com.nasmusic.tv.backend.BackendAdapter.searchSongs]
 *    精确匹配（normalizeKey title+artist 相等）；未命中 → NetworkMusicManager.search
 *    多源 fallback。artist 为空的条目按 §4.1.7 (6) 退化：优先 title 精确匹配，失败取 Top1。
 * 2) [onPlaybackFailure]：§4.1.8 (5) 播放失败回退——复测 URL 可达性，
 *    真不可达（NOT_FOUND/DNS_FAILED/REDIRECT_LOOP）才走补全写回；
 *    暂时不可达（TIMEOUT/SERVER_ERROR）只标记 Unreachable，不打扰用户。
 *
 * 写回 [AppPreferences.replaceSongInPlaylist]：networkHit 转 isNetworkSong=true 后
 * streamUrl 会被 stripVolatileStreamUrl 置空（播放时实时解析），NAS 命中保留 streamUrl。
 */
class PlaylistEnricher(
    private val backendRegistry: BackendRegistry,
    private val networkMusicManager: NetworkMusicManager,
    private val prefs: AppPreferences,
) {
    companion object {
        private const val TAG = "PlaylistEnricher"
    }

    /**
     * 算法 A（§4.3.2）：stub → 真 Song。
     * @return 命中的 NAS / 网络 Song；未命中或非 stub 返回 null（保留裸条目，UI 标「待补全」）
     */
    suspend fun enrichSong(stub: Song): Song? {
        if (!stub.id.startsWith(PlaylistParsers.IMPORTED_ID_PREFIX)) return null

        // 1) 已连 NAS + searchSongs → 精确匹配（title+artist normalizeKey 相等）
        runCatching {
            backendRegistry.getAdapter()?.searchSongs("${stub.title} ${stub.artist}".trim())
        }.getOrNull()?.firstOrNull { match ->
            PlaylistParsers.normalizeKey(match.title, match.artist) ==
                PlaylistParsers.normalizeKey(stub.title, stub.artist)
        }?.let { return it }

        // 2) NetworkMusicManager.search（多源 fallback）
        val hits = runCatching { networkMusicManager.search(stub.title) }
            .onFailure { AppLog.w(TAG, "enrichSong: network search failed: ${it.message}") }
            .getOrNull().orEmpty()

        if (stub.artist.isBlank()) {
            // §4.1.7 (6)：artist 空 → 优先 title 精确匹配；失败退而取搜索 Top1
            return hits.firstOrNull { hit ->
                PlaylistParsers.normalizeKey(hit.title, "") == PlaylistParsers.normalizeKey(stub.title, "")
            }?.let { it.asPersistableNetworkSong() }
                ?: hits.firstOrNull()?.asPersistableNetworkSong()
        }

        hits.firstOrNull { hit ->
            PlaylistParsers.normalizeKey(hit.title, hit.artist) ==
                PlaylistParsers.normalizeKey(stub.title, stub.artist)
        }?.let { return it.asPersistableNetworkSong() }

        return null
    }

    /** 网络命中转为可持久化形态：isNetworkSong 保持 true，streamUrl 置空（播放时解析） */
    private fun Song.asPersistableNetworkSong(): Song = copy(streamUrl = null)

    /**
     * 补全并写回指定歌单（§4.3.3 [enrichAndPersist]）。
     * @return true 已替换；false 未命中（不改动歌单）
     */
    suspend fun enrichAndPersist(playlistId: String, stub: Song): Boolean {
        val enriched = enrichSong(stub) ?: return false
        prefs.replaceSongInPlaylist(playlistId, stub.id, enriched)
        AppLog.d(TAG, "enrichAndPersist: replaced stub '${stub.title}' in playlist=$playlistId")
        return true
    }

    /**
     * 补全并写回所有含该 stub 的歌单（无 playlistId 上下文的入口使用，
     * 如 [playQueue] 队列快照 / [playNetworkSong] 单曲 / [onPlaybackFailure]）。
     * @return 补全后的 Song；未命中或非 stub 返回 null
     */
    suspend fun enrichAndPersistEverywhere(stub: Song): Song? {
        if (!stub.id.startsWith(PlaylistParsers.IMPORTED_ID_PREFIX)) return null
        val enriched = enrichSong(stub) ?: return null
        val playlists = prefs.getLocalPlaylists()
        var replaced = false
        for (pl in playlists) {
            if (pl.songs.any { it.id == stub.id }) {
                prefs.replaceSongInPlaylist(pl.id, stub.id, enriched)
                replaced = true
            }
        }
        if (replaced) AppLog.d(TAG, "enrichAndPersistEverywhere: replaced '${stub.title}'")
        return enriched
    }

    /**
     * 算法 B（§4.1.8 (5) / §4.3.2）：播放失败回退。
     * 仅处理「imported_ 前缀 + streamUrl 以 http 开头」的条目；复测可达性后分类处理：
     * - REACHABLE：URL 实际可达但本次播放失败（ExoPlayer 临时问题）→ 不改动，留作下次重试
     * - NOT_FOUND / DNS_FAILED / REDIRECT_LOOP：真不可达 → 走补全链写回（未命中保留 stub）
     * - TIMEOUT / SERVER_ERROR / INVALID_URL：暂时/永久不可达 → 标记 Unreachable（UI 显示失效标签）
     *
     * @return true 已补全写回；false 未写回（可达/暂时不可达/未命中）
     */
    suspend fun onPlaybackFailure(stub: Song, checker: UrlReachabilityChecker): Boolean {
        if (!stub.id.startsWith(PlaylistParsers.IMPORTED_ID_PREFIX)) return false
        val url = stub.streamUrl ?: return false
        if (!url.startsWith("http")) return false

        val (result, _) = checker.check(url)
        return when (result) {
            UrlReachabilityChecker.Result.REACHABLE -> {
                AppLog.w(TAG, "onPlaybackFailure: $result but play failed; leave as-is")
                false
            }
            UrlReachabilityChecker.Result.NOT_FOUND,
            UrlReachabilityChecker.Result.DNS_FAILED,
            UrlReachabilityChecker.Result.REDIRECT_LOOP -> {
                // 真不可达 → 补全链（命中替换；未命中保留 stub + UI 双标签）
                enrichAndPersistEverywhere(stub) != null
            }
            UrlReachabilityChecker.Result.TIMEOUT,
            UrlReachabilityChecker.Result.SERVER_ERROR,
            UrlReachabilityChecker.Result.INVALID_URL -> {
                // 暂时不可达 / URL 非法 → 标记 Unreachable（24h 判定窗口自动复测，§4.7.4）
                prefs.markSongUnreachable(stub.id, result.name)
                false
            }
        }
    }
}
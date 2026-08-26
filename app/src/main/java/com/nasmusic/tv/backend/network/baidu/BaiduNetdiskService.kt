package com.nasmusic.tv.backend.network.baidu

import com.nasmusic.tv.backend.network.NetworkMusicService
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 百度网盘网络音乐服务实现
 *
 * 作为 [NetworkMusicService] 接入（与 Meting 同层），不走 [com.nasmusic.tv.backend.BackendAdapter]：
 * - dlink 8h 过期 → streamUrl 播放时解析不持久化
 * - 网盘扁平文件系统 → 无专辑/艺术家/流派的服务端结构
 * - OAuth 鉴权 → 非用户名/密码模型
 *
 * 歌曲唯一值：`ntwk_baidu_${fs_id}`（fs_id 跨重命名/移动稳定）。
 */
class BaiduNetdiskService(
    private val oauth: BaiduOAuthClient,
    private val api: BaiduPanApi,
    private val streamFactory: BaiduStreamFactory,
    private val lyricsProvider: BaiduLyricsProvider,
    private val coverProvider: BaiduCoverProvider,
    private val indexCache: BaiduFileIndexCache,
    private val prefs: AppPreferences
) : NetworkMusicService {

    override val sourceId = "baidu"

    /**
     * 搜索：本地索引 + 百度 search API（参数名 key）合并去重。
     *
     * ⚠️ 注册副作用：注册本 service 后，[com.nasmusic.tv.backend.network.NetworkMusicManager.search()]
     * 在默认源无结果时也会 fallback 调用本方法（期望的多源聚合行为）。
     * 本方法只查索引与根目录范围，不触发全盘扫描。
     *
     * 为什么不只查索引：本地索引是增量/节流扫描，可能不完整（尤其用户新上传的歌）；
     * 必须先查索引拿到零成本命中，再调 API 补全遗漏，最后按 id（ntwk_baidu_${fs_id}）去重。
     */
    override suspend fun search(keyword: String, limit: Int): List<Song> = withContext(Dispatchers.IO) {
        // 1. 查本地索引（毫秒级，可能不完整）
        val localHits = indexCache.search(keyword, limit)

        // 2. 再调百度 search API 补全本地索引缺失的结果
        val apiHits = try {
            val rootDir = prefs.getBaiduMusicRootDirSync().ifBlank { "/" }
            api.searchAudio(keyword, dir = rootDir).map { it.toSong() }
        } catch (e: Exception) {
            AppLog.w(TAG, "search API failed, fallback to index only", e)
            emptyList()
        }

        // 3. 合并去重（id = ntwk_baidu_${fs_id}），本地索引优先保持顺序
        if (apiHits.isEmpty()) return@withContext localHits
        val seen = HashSet<String>(localHits.size + apiHits.size)
        (localHits + apiHits).filter { seen.add(it.id) }
    }

    /**
     * 目录感知搜索（发现页专用）：目录名命中则列出该目录下全部歌曲。
     *
     * 实现 [NetworkMusicService.searchByDirectory] 契约——发现页按"标签/目录"浏览
     * （粤语、经典老歌等），网盘常以这些词作目录名。
     * 与 [search] 的区别：这里优先匹配 path 中的目录段，目录命中返回整个目录的歌曲；
     * 无目录命中才回退文件名/歌手匹配。只查本地索引（发现页的标签浏览场景，
     * 索引已覆盖用户网盘扫描结果），不触发额外的 API 搜索。
     */
    override suspend fun searchByDirectory(keyword: String): List<Song> = withContext(Dispatchers.IO) {
        indexCache.searchByDirectory(keyword)
    }

    /** 解析播放 URL：fs_id -> dlink（复用 NetworkMusicManager 的 playUrlCache，全局 5min TTL） */
    override suspend fun resolvePlayUrl(song: Song): String? {
        val fsId = song.networkId?.toLongOrNull() ?: run {
            AppLog.w(TAG, "resolvePlayUrl: missing networkId for ${song.id}")
            return null
        }
        return streamFactory.resolveStreamUrl(fsId)
    }

    /** 歌词：侧车 LRC → 内嵌 ID3 USLT → 上层网络匹配 fallback */
    override suspend fun resolveLyrics(song: Song): String? {
        val fsId = song.networkId?.toLongOrNull() ?: return null
        return lyricsProvider.getLyrics(fsId, song.title, song.artist.ifBlank { null }, song.path)
    }

    /** 封面：侧车 cover → 内嵌 APIC → 上层网络匹配 fallback */
    override suspend fun resolveCoverUrl(song: Song): String? {
        val fsId = song.networkId?.toLongOrNull() ?: return null
        return coverProvider.getCover(fsId, song.title, song.artist.ifBlank { null }, song.path)
    }

    /** 网络封面搜索由 Meting 等源承担，百度源不单独实现 */
    override suspend fun searchCoverUrl(title: String, artist: String): String? = null

    /** 网盘歌曲不支持网盘歌单（用本地 LocalPlaylist） */
    override suspend fun getPlaylist(playlistId: String): List<Song> = emptyList()

    companion object {
        private const val TAG = "BaiduNetdiskService"
    }
}

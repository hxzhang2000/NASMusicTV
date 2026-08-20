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
     * 搜索：优先本地索引（毫秒级），fallback 百度 search API（参数名 key）。
     *
     * ⚠️ 注册副作用：注册本 service 后，[com.nasmusic.tv.backend.network.NetworkMusicManager.search()]
     * 在默认源无结果时也会 fallback 调用本方法（期望的多源聚合行为）。
     * 本方法只查索引与根目录范围，不触发全盘扫描。
     */
    override suspend fun search(keyword: String, limit: Int): List<Song> = withContext(Dispatchers.IO) {
        // 1. 查本地索引
        val localHits = indexCache.search(keyword, limit)
        if (localHits.isNotEmpty()) return@withContext localHits

        // 2. fallback 百度 search API（参数名 key）
        val rootDir = prefs.getBaiduMusicRootDirSync().ifBlank { "/" }
        val files = api.searchAudio(keyword, dir = rootDir)
        files.map { it.toSong() }
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

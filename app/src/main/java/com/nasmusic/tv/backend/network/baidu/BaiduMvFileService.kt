package com.nasmusic.tv.backend.network.baidu

import com.nasmusic.tv.backend.network.mv.MvSearchService
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.BaiduIndexEntry
import com.nasmusic.tv.data.model.MvCandidate
import com.nasmusic.tv.data.model.MvInfo
import com.nasmusic.tv.data.model.MvSearchResult
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 百度网盘本地 MV 搜索服务
 *
 * 接入 [MvSearchService]：在 [MvSearchManager.services] 列表中排在 [com.nasmusic.tv.backend.network.mv.BilibiliMvService]
 * 之前（本地优先于在线）。当当前歌曲是百度歌曲（`song.networkSource == "baidu"`）时：
 * 1. 从索引查同目录同名视频文件（零网络）
 * 2. 未命中则按歌手+歌名在索引中搜索视频文件（零网络）
 * 3. 仍未命中由 [MvSearchManager] fallback 到 Bilibili 在线搜索
 *
 * 唯一标识：`ntwk_baidu_mv_${mv_fs_id}`（与 B 站 bvid 同字段语义，按前缀路由）。
 */
class BaiduMvFileService(
    private val api: BaiduPanApi,
    private val streamFactory: BaiduStreamFactory,
    private val indexCache: BaiduFileIndexCache,
    private val prefs: AppPreferences
) : MvSearchService {

    override suspend fun searchMv(
        title: String,
        artist: String,
        excludeBvids: Set<String>,
        minSimilarity: Float,
        song: Song?
    ): MvSearchResult? = withContext(Dispatchers.IO) {
        if (song?.networkSource != "baidu") return@withContext null

        val mvDir = prefs.getBaiduMvDirSync() ?: prefs.getBaiduMusicRootDirSync().ifBlank { null }
            ?: return@withContext null

        val index = indexCache.load() ?: return@withContext null
        val excludeFsIds = excludeBvids.mapNotNull { BaiduNetdiskConfig.parseMvFsId(it) }.toSet()

        // 1. 同目录同名：从索引查歌曲路径，再查同目录同名视频文件
        val songPath = index.entries.firstOrNull { it.fsId == song.networkId?.toLongOrNull() }?.path
        if (songPath != null) {
            val parentDir = songPath.substringBeforeLast('/').ifEmpty { "/" }
            val basename = songPath.substringAfterLast('/').substringBeforeLast('.').lowercase()
            val sameDir = index.entries.firstOrNull { entry ->
                entry.category == BaiduNetdiskConfig.CATEGORY_VIDEO &&
                    entry.fsId !in excludeFsIds &&
                    entry.path.substringBeforeLast('/') == parentDir &&
                    entry.filename.substringBeforeLast('.').lowercase() == basename
            }
            if (sameDir != null) return@withContext buildResult(sameDir.toBaiduFile())
        }

        // 2. 歌手+歌名在索引中搜索视频
        val keywords = listOfNotNull(
            title.takeIf { it.isNotBlank() },
            artist.takeIf { it.isNotBlank() }
        ).joinToString(" ")
        if (keywords.isNotBlank()) {
            val candidates = indexCache.searchMv(artist = artist, title = title)
            val best = candidates.firstOrNull { it.fsId !in excludeFsIds }
            if (best != null) return@withContext buildResult(best.toBaiduFile())
        }

        null  // 索引未命中 → MvSearchManager fallback 到 BilibiliMvService
    }

    /** 解析 MV 直链（dlink，需补 access_token + UA） */
    override suspend fun resolveMv(bvid: String): MvInfo? = withContext(Dispatchers.IO) {
        if (!BaiduNetdiskConfig.isBaiduMvBvid(bvid)) return@withContext null
        val fsId = BaiduNetdiskConfig.parseMvFsId(bvid) ?: return@withContext null
        val metas = api.fileMetas(listOf(fsId))
        val meta = metas.firstOrNull() ?: run {
            AppLog.w(TAG, "resolveMv: filemetas empty for fsId=$fsId")
            return@withContext null
        }
        val dlink = meta.dlink ?: return@withContext null
        val videoUrl = streamFactory.resolveStreamUrl(fsId) ?: return@withContext null
        MvInfo(
            bvid = bvid,
            title = meta.filename ?: bvid,
            coverUrl = meta.thumbs?.bestUrl,
            videoUrl = videoUrl,
            durationMs = meta.duration,
            source = "baidu"
        )
    }

    private fun buildResult(file: BaiduFile): MvSearchResult {
        val bvid = BaiduNetdiskConfig.mvBvid(file.fsId)
        val mv = MvInfo(
            bvid = bvid,
            title = file.serverFilename,
            coverUrl = null,
            videoUrl = "",
            source = "baidu"
        )
        return MvSearchResult(mv, emptyList())
    }

    companion object {
        private const val TAG = "BaiduMvFileService"
    }
}

package com.nasmusic.tv.backend

import com.nasmusic.tv.backend.network.JamendoService
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.backend.network.baidu.BaiduNetdiskService
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.RankedSong
import com.nasmusic.tv.data.model.SearchAggregatorResult
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 跨源搜索聚合器
 *
 * 并行搜索 NAS、网络音乐、百度网盘、Jamendo 四个数据源，
 * 合并去重后返回统一结果。单个源超时或异常不影响其他源。
 *
 * 超时策略：NAS 5s、网络音乐 5s、百度网盘 8s、Jamendo 5s
 */
class SearchAggregator(
    private val backendAdapter: BackendAdapter?,
    private val networkMusicManager: NetworkMusicManager?,
    private val baiduService: BaiduNetdiskService?,
    private val jamendoService: JamendoService?
) {
    companion object {
        private const val TAG = "SearchAggregator"
        private const val NAS_TIMEOUT = 5_000L
        private const val NETWORK_TIMEOUT = 5_000L
        private const val BAIDU_TIMEOUT = 8_000L
        private const val JAMENDO_TIMEOUT = 5_000L
    }

    /**
     * 并行搜索所有选定源，合并去重后返回结果
     *
     * @param keyword 搜索关键词
     * @param sources 要搜索的源集合（默认全部）
     * @param directoryMode 是否用"目录感知"搜索（发现页标签浏览用，适用于所有支持目录
     *                      结构的源，如网盘）；false（默认）用普通文件名搜索（搜索页）
     * @return 聚合搜索结果
     */
    suspend fun search(
        keyword: String,
        sources: Set<MusicSourceType> = MusicSourceType.entries.toSet(),
        directoryMode: Boolean = false
    ): SearchAggregatorResult = coroutineScope {
        AppLog.i(TAG, "search: keyword='$keyword' sources=${sources.map { it.name }} directoryMode=$directoryMode")

        if (keyword.isBlank()) {
            return@coroutineScope SearchAggregatorResult(
                allResults = emptyList(),
                sourceBreakdown = emptyMap()
            )
        }

        // 为每个源创建独立协程，并行搜索
        val nasDeferred = async {
            if (MusicSourceType.NAS in sources && backendAdapter != null) {
                try {
                    withTimeoutOrNull(NAS_TIMEOUT) {
                        backendAdapter.searchSongs(keyword)
                            .filter { it.title.isNotBlank() }
                            .map { song ->
                                RankedSong(song = song, source = MusicSourceType.NAS)
                            }
                    } ?: run {
                        AppLog.w(TAG, "NAS search timed out")
                        emptyList()
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "NAS search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        val networkDeferred = async {
            if (MusicSourceType.NETWORK_MUSIC in sources && networkMusicManager != null) {
                try {
                    withTimeoutOrNull(NETWORK_TIMEOUT) {
                        networkMusicManager.search(keyword)
                            .filter { it.title.isNotBlank() }
                            .map { song ->
                                RankedSong(song = song, source = MusicSourceType.NETWORK_MUSIC)
                            }
                    } ?: run {
                        AppLog.w(TAG, "Network search timed out")
                        emptyList()
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Network search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        val baiduDeferred = async {
            if (MusicSourceType.BAIDU_PAN in sources && baiduService != null) {
                try {
                    withTimeoutOrNull(BAIDU_TIMEOUT) {
                        val baiduSongs = if (directoryMode) {
                            // 发现页：目录感知搜索（目录名命中则列出整个目录），走通用网盘契约
                            baiduService.searchByDirectory(keyword)
                        } else {
                            // 搜索页：普通文件名/歌手搜索
                            baiduService.search(keyword)
                        }
                        baiduSongs
                            .filter { it.title.isNotBlank() }
                            .map { song ->
                                RankedSong(song = song, source = MusicSourceType.BAIDU_PAN)
                            }
                    } ?: run {
                        AppLog.w(TAG, "Baidu search timed out")
                        emptyList()
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Baidu search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        val jamendoDeferred = async {
            if (MusicSourceType.JAMENDO in sources && jamendoService != null) {
                try {
                    withTimeoutOrNull(JAMENDO_TIMEOUT) {
                        jamendoService.search(keyword)
                            .filter { it.title.isNotBlank() }
                            .map { song ->
                                RankedSong(song = song, source = MusicSourceType.JAMENDO)
                            }
                    } ?: run {
                        AppLog.w(TAG, "Jamendo search timed out")
                        emptyList()
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Jamendo search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        // 等待所有协程完成
        val nasResults = nasDeferred.await()
        val networkResults = networkDeferred.await()
        val baiduResults = baiduDeferred.await()
        val jamendoResults = jamendoDeferred.await()

        // 合并所有结果
        val allResults = nasResults + networkResults + baiduResults + jamendoResults

        // 同源内去重：相同 title+artist 只保留第一个
        val deduped = deduplicateWithinSource(allResults)

        // 按来源优先级 + 匹配分排序
        val sorted = RankedSong.sortByPriority(deduped)

        // 各源命中数统计
        val sourceBreakdown = sorted.groupBy { it.source }.mapValues { it.value.size }

        AppLog.i(TAG, "search complete: ${sorted.size} results, breakdown=$sourceBreakdown")

        SearchAggregatorResult(
            allResults = sorted,
            sourceBreakdown = sourceBreakdown
        )
    }

    /**
     * 同源内去重：相同 title+artist 归一化后只保留第一个
     * 跨源不去重（不同源的同一首歌保留多条，各自标注来源）
     */
    private fun deduplicateWithinSource(ranked: List<RankedSong>): List<RankedSong> {
        val seen = mutableSetOf<String>()
        return ranked.filter { item ->
            val key = "${item.source.name}:${normalizeKey(item.song.title, item.song.artist)}"
            if (key in seen) {
                false
            } else {
                seen.add(key)
                true
            }
        }
    }

    /**
     * 归一化歌曲标识：小写 + 去除首尾空白 + 合并连续空格
     */
    private fun normalizeKey(title: String, artist: String): String {
        return "${title.trim().lowercase()}|${artist.trim().lowercase()}"
    }
}

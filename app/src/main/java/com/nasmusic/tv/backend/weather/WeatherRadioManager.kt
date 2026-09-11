package com.nasmusic.tv.backend.weather

import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.WeatherData
import com.nasmusic.tv.data.model.WeatherMood
import com.nasmusic.tv.data.model.WeatherRadioQueue
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 天气电台管理器
 *
 * 根据 WeatherData 计算 mood，然后构建歌曲队列。
 * 队列来源：
 * 1. NAS 后端本地歌曲（按 mood 关键词模糊搜索曲库）
 * 2. 网络歌曲（通过 NetworkMusicManager 搜索 mood 关键词）
 */
class WeatherRadioManager(
    /** NAS 后端适配器（可能为 null — 未连接时从网络端匹配歌曲） */
    private val backendAdapter: BackendAdapter?,
    private val networkMusicManager: NetworkMusicManager
) {
    companion object {
        private const val TAG = "WeatherRadioManager"
        /** 天气电台每次构建的歌曲总数 */
        private const val TARGET_SONG_COUNT = 20
        /** NAS 来源比例上限 */
        private const val NAS_MAX_RATIO = 0.5f
        /** NAS 心情命中歌曲的 id 前缀（用于区分来源） */
        private const val NAS_PREFIX = "nas_"
        /** NAS 随机补齐歌曲的 id 前缀（与心情命中区分，避免被误判为命中） */
        private const val NAS_FILLER_PREFIX = "nasf_"
    }

    /** NAS 搜索结果：命中心情关键词的歌曲 + 随机补齐歌曲（分别标记，避免混淆） */
    private data class NasSearchResult(
        val matched: List<Song> = emptyList(),
        val filler: List<Song> = emptyList()
    )

    /**
     * 合并 NAS / 网络结果并计算计数。
     *
     * 修复：随机补齐的 NAS 歌曲不再被当作「心情命中」计入 nasCount。
     * - nasCount：队列中所有 NAS 来源歌曲（命中 + 补齐）
     * - networkCount：队列中网络来源歌曲
     * - moodMatchedCount：真正由心情关键词命中的歌曲数（NAS 命中 + 网络结果）
     */
    private fun mergeAndPack(
        nasResult: NasSearchResult,
        networkSongs: List<Song>,
        limit: Int,
        mood: WeatherMood,
        queries: List<String>
    ): WeatherRadioQueue {
        val seen = mutableSetOf<String>()
        val matched = mutableListOf<Song>()
        val filler = mutableListOf<Song>()
        // 优先：真正命中的（NAS 命中 + 网络结果）；网络结果本身即由心情关键词搜出
        for (s in nasResult.matched) if (seen.add(s.id)) matched.add(s)
        for (s in networkSongs) if (seen.add(s.id)) matched.add(s)
        // 其次：NAS 随机补齐
        for (s in nasResult.filler) if (seen.add(s.id)) filler.add(s)

        val finalSongs = (matched + filler).take(limit)
        val matchedIds = matched.mapTo(mutableSetOf()) { it.id }
        return WeatherRadioQueue(
            songs = finalSongs,
            mood = mood,
            queries = queries,
            nasCount = finalSongs.count {
                it.id.startsWith(NAS_PREFIX) || it.id.startsWith(NAS_FILLER_PREFIX)
            },
            networkCount = finalSongs.count {
                !it.id.startsWith(NAS_PREFIX) && !it.id.startsWith(NAS_FILLER_PREFIX)
            },
            moodMatchedCount = finalSongs.count { it.id in matchedIds }
        )
    }

    /**
     * 根据当前天气构建电台队列
     *
     * @param weather 当前天气数据
     * @param limit 期望的歌曲总数（默认 20）
     */
    suspend fun buildRadio(weather: WeatherData, limit: Int = TARGET_SONG_COUNT): WeatherRadioQueue {
        val mood = WeatherMood.fromWeather(weather)
        val queries = mood.searchQueries

        val nasLimit = (limit * NAS_MAX_RATIO).toInt().coerceAtLeast(5)

        return coroutineScope {
            val nasDeferred = async {
                try {
                    searchNasSongs(queries, nasLimit)
                } catch (e: Exception) {
                    AppLog.w(TAG, "NAS search failed: ${e.message}")
                    NasSearchResult()
                }
            }
            val networkDeferred = async {
                try {
                    searchNetworkSongs(queries, limit - nasLimit)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Network search failed: ${e.message}")
                    emptyList()
                }
            }

            val nasResult = nasDeferred.await()
            val networkSongs = networkDeferred.await()
            mergeAndPack(nasResult, networkSongs, limit, mood, queries)
        }
    }

    /**
     * 切换 mood 并重新构建电台
     */
    suspend fun buildRadioWithMood(
        targetMood: WeatherMood,
        weather: WeatherData?,
        limit: Int = TARGET_SONG_COUNT
    ): WeatherRadioQueue {
        val queries = targetMood.searchQueries

        return coroutineScope {
            val nasDeferred = async {
                try {
                    searchNasSongs(queries, (limit * NAS_MAX_RATIO).toInt().coerceAtLeast(5))
                } catch (e: Exception) {
                    NasSearchResult()
                }
            }
            val networkDeferred = async {
                try {
                    searchNetworkSongs(queries, limit - (limit * NAS_MAX_RATIO).toInt().coerceAtLeast(5))
                } catch (e: Exception) {
                    emptyList()
                }
            }

            val nasResult = nasDeferred.await()
            val networkSongs = networkDeferred.await()
            mergeAndPack(nasResult, networkSongs, limit, targetMood, queries)
        }
    }

    /**
     * 从 NAS 后端搜索匹配 mood 的歌曲
     *
     * 使用 BackendAdapter.getSongs() 获取曲库，然后用关键词模糊匹配。
     * TODO: 如果 BackendAdapter 未来支持 search() 方法，替换为正式搜索
     */
    private suspend fun searchNasSongs(queries: List<String>, maxCount: Int): NasSearchResult {
        // 无后端连接时跳过 NAS 搜索
        val adapter = backendAdapter ?: return NasSearchResult()
        // 获取全部歌曲（带缓存）
        val allSongs = adapter.getSongs()?.toList() ?: return NasSearchResult()
        if (allSongs.isEmpty()) return NasSearchResult()

        val matched = mutableSetOf<Song>()
        // 逐个关键词匹配（跳过空关键词：空串 contains("") 恒真，会把整个曲库当作命中）
        for (query in queries) {
            if (matched.size >= maxCount) break
            val q = query.trim().lowercase()
            if (q.isEmpty()) continue
            val results = allSongs.filter { song ->
                song.title.lowercase().contains(q) ||
                    song.artist.lowercase().contains(q) ||
                    song.album.lowercase().contains(q)
            }
            matched.addAll(results.take(maxCount - matched.size))
        }

        // 命中不足 → 随机补齐。补齐部分单独返回并打上 nasf_ 前缀，
        // 不再与「心情命中」混为一谈（原实现二者统一加 nas_ 前缀，导致命中率虚高）。
        val filler = if (matched.size < maxCount) {
            allSongs.shuffled()
                .filterNot { it in matched }
                .take(maxCount - matched.size)
        } else emptyList()

        // 打乱命中结果，让每次构建的电台基础集合不同（支持「换一批」跨构建去重）
        val matchedTagged = matched.toList().shuffled().take(maxCount).map { it.copy(id = "${NAS_PREFIX}${it.id}") }
        val fillerTagged = filler.shuffled().map { it.copy(id = "${NAS_FILLER_PREFIX}${it.id}") }
        return NasSearchResult(matched = matchedTagged, filler = fillerTagged)
    }

    /**
     * 通过网络音乐管理器搜索匹配 mood 的歌曲
     */
    private suspend fun searchNetworkSongs(queries: List<String>, maxCount: Int): List<Song> {
        if (maxCount <= 0) return emptyList()

        val results = mutableSetOf<Song>()
        for (query in queries) {
            if (results.size >= maxCount) break
            if (query.isBlank()) continue
            try {
                val songs = networkMusicManager.search(query)
                results.addAll(songs.take(maxCount - results.size))
            } catch (e: Exception) {
                AppLog.w(TAG, "Network search for '$query' failed: ${e.message}")
            }
        }
        // 打乱结果，让每次构建的电台基础集合不同（支持「换一批」跨构建去重）
        return results.shuffled().take(maxCount).toList()
    }
}

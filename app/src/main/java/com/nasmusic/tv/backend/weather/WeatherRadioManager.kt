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
                    emptyList()
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

            val nasSongs = nasDeferred.await()
            val networkSongs = networkDeferred.await()

            // 合并并去重（按 id）
            val seenIds = mutableSetOf<String>()
            val combined = mutableListOf<Song>()

            // NAS 优先
            for (song in nasSongs) {
                if (seenIds.add(song.id)) combined.add(song)
            }
            // 网络补充
            for (song in networkSongs) {
                if (seenIds.add(song.id)) combined.add(song)
            }

            // 截取目标长度
            val finalSongs = combined.take(limit)

            WeatherRadioQueue(
                songs = finalSongs,
                mood = mood,
                queries = queries,
                nasCount = finalSongs.count { it.id.startsWith("nas_") },
                networkCount = finalSongs.count { !it.id.startsWith("nas_") }
            )
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
                    emptyList()
                }
            }
            val networkDeferred = async {
                try {
                    searchNetworkSongs(queries, limit - (limit * NAS_MAX_RATIO).toInt().coerceAtLeast(5))
                } catch (e: Exception) {
                    emptyList()
                }
            }

            val nasSongs = nasDeferred.await()
            val networkSongs = networkDeferred.await()

            val seenIds = mutableSetOf<String>()
            val combined = mutableListOf<Song>()
            for (song in nasSongs) {
                if (seenIds.add(song.id)) combined.add(song)
            }
            for (song in networkSongs) {
                if (seenIds.add(song.id)) combined.add(song)
            }

            val finalSongs = combined.take(limit)

            WeatherRadioQueue(
                songs = finalSongs,
                mood = targetMood,
                queries = queries,
                nasCount = finalSongs.count { it.id.startsWith("nas_") },
                networkCount = finalSongs.count { !it.id.startsWith("nas_") }
            )
        }
    }

    /**
     * 从 NAS 后端搜索匹配 mood 的歌曲
     *
     * 使用 BackendAdapter.getSongs() 获取曲库，然后用关键词模糊匹配。
     * TODO: 如果 BackendAdapter 未来支持 search() 方法，替换为正式搜索
     */
    private suspend fun searchNasSongs(queries: List<String>, maxCount: Int): List<Song> {
        // 无后端连接时跳过 NAS 搜索
        val adapter = backendAdapter ?: return emptyList()
        // 获取全部歌曲（带缓存）
        val allSongs = adapter.getSongs()?.toList() ?: return emptyList()
        if (allSongs.isEmpty()) return emptyList()

        val matched = mutableSetOf<Song>()
        // 逐个关键词匹配
        for (query in queries) {
            if (matched.size >= maxCount) break
            val q = query.lowercase()
            val results = allSongs.filter { song ->
                song.title.lowercase().contains(q) ||
                    song.artist.lowercase().contains(q) ||
                    song.album.lowercase().contains(q)
            }
            matched.addAll(results.take(maxCount - matched.size))
        }

        // 如果匹配不够，用随机歌曲补齐（但不计入 nasCount）
        if (matched.size < maxCount) {
            val remaining = allSongs.shuffled().take(maxCount - matched.size)
            matched.addAll(remaining)
        }

        // 打乱匹配结果，让每次构建的电台基础集合不同（支持「换一批」跨构建去重）
        // 标记为 NAS 来源（id 增加 nas_ 前缀以区分网络搜索）
        return matched.toList().shuffled().take(maxCount).map { it.copy(id = "nas_${it.id}") }
    }

    /**
     * 通过网络音乐管理器搜索匹配 mood 的歌曲
     */
    private suspend fun searchNetworkSongs(queries: List<String>, maxCount: Int): List<Song> {
        if (maxCount <= 0) return emptyList()

        val results = mutableSetOf<Song>()
        for (query in queries) {
            if (results.size >= maxCount) break
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

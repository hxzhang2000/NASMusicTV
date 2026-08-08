package com.nasmusic.tv.backend.network.mv

import com.nasmusic.tv.data.model.MvInfo
import com.nasmusic.tv.data.model.MvSearchResult
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * MV 搜索管理器
 *
 * 统一入口：MainViewModel 切歌时调用 [searchMvFor]，UI 消费结果。
 * - 多源 fallback：依次尝试 [services] 中的每个源（v1 仅 Bilibili），任一返回非空即停
 * - 内存缓存：`ConcurrentHashMap` + TTL（直链有有效期，默认 45 分钟），命中直接返回
 * - 不缓存空结果（NotFound 不缓存，下次切歌/进入页面时重搜）
 *
 * 对应 docs/mv-karaoke-feature-proposal.md Step 1
 */
class MvSearchManager(
    private val services: List<MvSearchService>,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS
) {

    private val cache = ConcurrentHashMap<String, CachedResult>()

    /**
     * 按歌曲搜索 MV。
     *
     * 组合关键词为 `歌曲名 歌手`，先查缓存，未命中则依次访问每个源。
     *
     * @return 匹配到的 MV；null 表示未找到或全部源失败（UI 置暗）
     */
    suspend fun searchMvFor(song: Song): MvSearchResult? {
        val key = buildCacheKey(song.title, song.artist)
        // 清理过期条目
        val now = System.currentTimeMillis()
        cache.entries.removeAll { (_, v) -> now - v.timestamp >= cacheTtlMs }

        // 缓存命中
        cache[key]?.let {
            AppLog.d(TAG, "searchMvFor: cache hit for '$key'")
            return it.result
        }

        return withContext(Dispatchers.IO) {
            for (svc in services) {
                try {
                    AppLog.d(TAG, "searchMvFor: trying service ${svc::class.java.simpleName} for '$key'")
                    val result = svc.searchMv(song.title, song.artist)
                    if (result != null) {
                        cache[key] = CachedResult(result, now)
                        AppLog.d(TAG, "searchMvFor: success for '$key' -> ${result.mv.title} + ${result.alternatives.size} alternatives")
                        return@withContext result
                    }
                } catch (e: Exception) {
                    // 单个源失败不阻断其他源
                    AppLog.w(TAG, "searchMvFor: service ${svc::class.java.simpleName} error: ${e.message}", e)
                }
            }
            AppLog.w(TAG, "searchMvFor: no MV found for '$key'")
            null
        }
    }

    /**
     * 按需解析指定 bvid 的直链（MTV 页面切换视频时调用）。
     * 不走缓存（直链有时效，切换时需重新解析）。
     */
    suspend fun resolveMv(bvid: String): MvInfo? {
        return withContext(Dispatchers.IO) {
            for (svc in services) {
                try {
                    val mv = svc.resolveMv(bvid)
                    if (mv != null) return@withContext mv
                } catch (e: Exception) {
                    AppLog.w(TAG, "resolveMv: service error: ${e.message}", e)
                }
            }
            null
        }
    }

    /** 清空缓存（播放失败时调用，强制重搜一次） */
    fun clearCache() = cache.clear()

    companion object {
        private const val TAG = "MvSearchManager"
        /** 缓存过期时间（毫秒），45 分钟 */
        private const val DEFAULT_CACHE_TTL_MS = 45 * 60 * 1000L

        /**
         * 构造缓存 key：歌曲名|歌手（首唱者），小写归一化。
         * 供单测验证 key 组合逻辑。
         */
        fun buildCacheKey(title: String, artist: String): String =
            "${title.trim().lowercase()}|${artist.trim().split('/', '、', ',', '，', '&')[0].trim().lowercase()}"
    }

    private data class CachedResult(val result: MvSearchResult, val timestamp: Long)
}
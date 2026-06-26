package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网络音乐管理器
 *
 * 多源路由层：管理多个 [NetworkMusicService] 实现，按默认源路由请求。
 *
 * 设计要点：
 * - search 采用 fallback 策略：默认源失败时依次尝试其他源
 * - resolvePlayUrl/resolveLyrics/resolveCoverUrl 不 fallback，按 song.networkSource 精确路由
 * - 默认源可通过 [setDefaultSource] 动态切换，由设置页面驱动
 *
 * v2.2.0 适配：手动 DI（在 NasMusicApp.onCreate 初始化），不使用 getInstance()
 */
class NetworkMusicManager(
    private val services: Map<String, NetworkMusicService>,
    private val defaultSourceProvider: () -> String
) {

    companion object {
        private const val TAG = "NetworkMusicManager"
        /** 播放链接缓存过期时间（毫秒），5 分钟 */
        private const val PLAY_URL_CACHE_TTL_MS = 5 * 60 * 1000L
    }

    /**
     * 播放链接缓存条目
     * @param url 播放链接
     * @param timestamp 缓存时间戳（毫秒）
     */
    private data class CachedPlayUrl(
        val url: String,
        val timestamp: Long
    )

    /** 播放链接内存缓存：songId → CachedPlayUrl */
    private val playUrlCache = mutableMapOf<String, CachedPlayUrl>()

    /** 获取当前默认源 ID */
    val defaultSource: String
        get() = defaultSourceProvider()

    /**
     * 搜索歌曲
     *
     * 策略：先查默认源；若默认源返回空或异常，依次尝试其他源（fallback）。
     * 任一源返回非空结果即返回，不再尝试后续源。
     */
    suspend fun search(keyword: String): List<Song> = withContext(Dispatchers.IO) {
        android.util.Log.i("MetingDiag", "=== NetworkMusicManager.search === keyword='$keyword' defaultSource='${defaultSourceProvider()}'")
        if (keyword.isBlank()) return@withContext emptyList()

        val ordered = orderedServices()
        android.util.Log.i("MetingDiag", "search: orderedServices=${ordered.map { it.sourceId }}")
        for (svc in ordered) {
            try {
                android.util.Log.i("MetingDiag", "search: trying source=${svc.sourceId}")
                val results = svc.search(keyword)
                if (results.isNotEmpty()) {
                    android.util.Log.i("MetingDiag", "search '$keyword' hit source=${svc.sourceId} count=${results.size}")
                    return@withContext results
                }
                android.util.Log.i("MetingDiag", "search '$keyword' empty on source=${svc.sourceId}, trying next")
            } catch (e: Exception) {
                android.util.Log.e("MetingDiag", "search '$keyword' error on source=${svc.sourceId}: ${e.message}", e)
            }
        }
        android.util.Log.w("MetingDiag", "search '$keyword' no results from any source")
        emptyList()
    }

    /**
     * 解析播放链接
     *
     * 按 song.networkSource 精确路由，不 fallback。
     * 网络歌曲的 streamUrl 不持久化，每次播放实时解析。
     *
     * 缓存策略：同一歌曲 5 分钟内复用缓存的播放链接，避免重复网络请求。
     * 播放链接有时效性，缓存过期后重新解析。
     */
    suspend fun resolvePlayUrl(song: Song): String? {
        if (!song.isNetworkSong) return song.streamUrl
        val src = song.networkSource ?: return null
        val svc = services[src] ?: run {
            AppLog.w(TAG, "resolvePlayUrl: unknown source=$src")
            return null
        }

        // 检查缓存：未过期则直接返回
        val now = System.currentTimeMillis()
        val cached = playUrlCache[song.id]
        if (cached != null && now - cached.timestamp < PLAY_URL_CACHE_TTL_MS) {
            AppLog.d(TAG, "resolvePlayUrl: cache hit for songId=${song.id}, age=${now - cached.timestamp}ms")
            return cached.url
        }

        return try {
            val url = svc.resolvePlayUrl(song)
            if (url != null) {
                // 写入缓存
                playUrlCache[song.id] = CachedPlayUrl(url, now)
                AppLog.d(TAG, "resolvePlayUrl: cached new url for songId=${song.id}")
            }
            url
        } catch (e: Exception) {
            AppLog.w(TAG, "resolvePlayUrl error: ${e.message}", e)
            null
        }
    }

    /**
     * 获取歌词
     *
     * 按 song.networkSource 精确路由，不 fallback。
     */
    suspend fun resolveLyrics(song: Song): String? {
        if (!song.isNetworkSong) return null
        val src = song.networkSource ?: return null
        val svc = services[src] ?: return null
        return try {
            svc.resolveLyrics(song)
        } catch (e: Exception) {
            AppLog.w(TAG, "resolveLyrics error: ${e.message}", e)
            null
        }
    }

    /**
     * 获取封面 URL
     *
     * 按 song.networkSource 精确路由。
     * 若服务返回 null，调用方应使用 song.coverUrl。
     */
    suspend fun resolveCoverUrl(song: Song): String? {
        if (!song.isNetworkSong) return song.coverUrl
        val src = song.networkSource ?: return null
        val svc = services[src] ?: return null
        return try {
            svc.resolveCoverUrl(song)
        } catch (e: Exception) {
            AppLog.w(TAG, "resolveCoverUrl error: ${e.message}", e)
            null
        }
    }

    /**
     * 按标题+艺术家搜索网络封面 URL。
     * 用于 NAS 歌曲切换到"在线歌词"来源时，联动获取网络封面加入轮播候选列表。
     * 仅 MetingApiService 实现该方法；返回 null 表示未找到。
     */
    suspend fun searchCoverUrl(title: String, artist: String): String? {
        // 依次尝试各服务，第一个返回非 null 即采用
        for (svc in orderedServices()) {
            if (svc !is MetingApiService) continue
            return try {
                svc.searchCoverUrl(title, artist)
            } catch (e: Exception) {
                AppLog.w(TAG, "searchCoverUrl error: ${e.message}", e)
                null
            } ?: continue
        }
        return null
    }

    /**
     * 获取所有已注册源 ID（用于设置页面展示可选项）
     */
    fun availableSources(): List<String> = services.keys.toList()

    /**
     * 构造按优先级排序的服务列表：默认源在前，其余按 Map 迭代顺序
     */
    private fun orderedServices(): List<NetworkMusicService> {
        val def = defaultSourceProvider()
        val defSvc = services[def]
        val others = services.filterKeys { it != def }.values
        return if (defSvc != null) listOf(defSvc) + others else others.toList()
    }
}

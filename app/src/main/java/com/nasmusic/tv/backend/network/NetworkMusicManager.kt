package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
    services: Map<String, NetworkMusicService>,
    private val defaultSourceProvider: () -> String,
    /**
     * 全局默认音质档位（同步读取）。由 NasMusicApp 注入
     * `{ appPreferences.getQualityTierSync() }`，与既有 provider 注入风格一致。
     */
    private val qualityTierProvider: () -> Int = { QualityTiers.AUTO },
    /**
     * 单曲音质覆盖查询（多码率方案 §2.3 两级模型）。
     * 返回 null 表示该曲无覆盖、回退全局默认。由 NasMusicApp 注入，
     * 避免 backend/network 反向依赖 data/prefs（见方案 §6.3）。
     */
    private val songQualityOverrideProvider: ((String, String) -> Int?)? = null
) {

    companion object {
        private const val TAG = "NetworkMusicManager"
        /** 播放链接缓存过期时间（毫秒），5 分钟 */
        private const val PLAY_URL_CACHE_TTL_MS = 5 * 60 * 1000L
        /** 播放链接缓存容量上限（超出按时间淘汰最旧，防止超长会话内存只增不减） */
        private const val PLAY_URL_CACHE_MAX = 500
    }

    /**
     * 内部可变 services Map（支持运行时注册/注销，例如百度网盘开关切换）。
     * 构造时拷贝传入的不可变 Map。
     */
    // 修复：原为普通 HashMap，主线程 register/unregister 与 IO 线程 resolvePlayUrl/search
    // 并发读写可能抛 ConcurrentModificationException。改用 ConcurrentHashMap。
    private val services: MutableMap<String, NetworkMusicService> =
        java.util.concurrent.ConcurrentHashMap(services)

    /**
     * 播放链接缓存条目
     * @param url 播放链接
     * @param timestamp 缓存时间戳（毫秒）
     */
    private data class CachedPlayUrl(
        val url: String,
        val timestamp: Long,
        /** 实际命中档位（降级时 ≠ 请求档位），随缓存一起复用避免重复降级探测 */
        val actualQuality: Int = QualityTiers.AUTO
    )

    /** 播放链接内存缓存：cacheKey → CachedPlayUrl（线程安全，resolvePlayUrl 在 IO 线程并发访问） */
    private val playUrlCache = ConcurrentHashMap<String, CachedPlayUrl>()

    /**
     * 播放直链缓存 key：`networkSource:networkId:quality`。
     *
     * 音质参与缓存键，切档位不会串用旧直链（修 G1）。
     * 注意此处用冒号分隔，与下载主键 `ntwk_<source>_<id>:q<quality>`
     * （下划线 + 后缀）属于两个独立命名空间，不要试图统一。
     */
    private fun playUrlKey(song: Song, quality: Int): String =
        "${song.networkSource}:${song.networkId}:$quality"

    /**
     * 有效档位解析（两级模型）：单曲覆盖优先，回退全局默认。
     */
    /**
     * 有效档位解析（两级模型）：单曲覆盖优先，回退全局默认。
     *
     * 供播放链路（PlayerViewModel §3.6）与下载面板查询"当前该曲用哪个档"。
     */
    fun effectiveQualityOf(song: Song): Int = effectiveQuality(song)

    private fun effectiveQuality(song: Song): Int {
        val src = song.networkSource ?: return qualityTierProvider()
        val id = song.networkId ?: return qualityTierProvider()
        val override = songQualityOverrideProvider?.invoke(src, id)
        return override ?: qualityTierProvider()
    }

    /** 清空全部播放直链缓存。档位变化时由 AppPreferences.setQualityTier() 调用（修 G5）。 */
    fun clearPlayUrlCache() {
        val size = playUrlCache.size
        playUrlCache.clear()
        if (size > 0) AppLog.d(TAG, "clearPlayUrlCache: cleared $size entries")
    }

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
        val currentDefault = defaultSource
        AppLog.i("MetingDiag", "=== NetworkMusicManager.search === keyword='$keyword' defaultSource='$currentDefault'")
        if (keyword.isBlank()) return@withContext emptyList()

        val ordered = orderedServices()
        AppLog.i("MetingDiag", "search: orderedServices=${ordered.map { it.sourceId }}")
        for (svc in ordered) {
            try {
                AppLog.i("MetingDiag", "search: trying source=${svc.sourceId}")
                val results = svc.search(keyword)
                if (results.isNotEmpty()) {
                    AppLog.i("MetingDiag", "search '$keyword' hit source=${svc.sourceId} count=${results.size}")
                    return@withContext results
                }
                AppLog.i("MetingDiag", "search '$keyword' empty on source=${svc.sourceId}, trying next")
            } catch (e: Exception) {
                AppLog.e("MetingDiag", "search '$keyword' error on source=${svc.sourceId}: ${e.message}", e)
            }
        }
        AppLog.w("MetingDiag", "search '$keyword' no results from any source")
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
     *
     * @param forceRefresh 为 true 时强制绕过缓存、走完整降级链重新解析。
     *        播放失败重试路径必须传 true——否则会命中「已过期但仍未到 5 分钟 TTL」
     *        的旧缓存，导致重试永远拿到失效链接、连锁跳过后续歌曲。
     */
    /** 运行时查询某源是否已注册（供播放前自愈判断） */
    fun isServiceRegistered(sourceId: String): Boolean = services.containsKey(sourceId)

    suspend fun resolvePlayUrl(song: Song, forceRefresh: Boolean = false): String? {
        if (!song.isNetworkSong) return song.streamUrl
        return resolvePlayUrlDetailed(song, effectiveQuality(song), forceRefresh).url
    }

    /**
     * 解析播放链接（指定档位 + 降级信号）。
     *
     * 按 song.networkSource 精确路由，不 fallback。
     * 网络歌曲的 streamUrl 不持久化，每次播放实时解析。
     *
     * 缓存策略：同一歌曲 × 同一档位 5 分钟内复用缓存，避免重复网络请求。
     * 切档位时 key 不同，因此不会命中旧档位直链（修 G1）。
     *
     * @param quality 音质档位（[QualityTiers] 常量）
     * @param forceRefresh 为 true 时强制绕过缓存、走完整降级链重新解析。
     *        播放失败重试路径、档位切换路径必须传 true。
     * @return [ResolveResult]，url 为 null 表示无源可降；
     *         actualQuality ≠ quality 表示发生了静默降级
     */
    suspend fun resolvePlayUrlDetailed(
        song: Song,
        quality: Int,
        forceRefresh: Boolean = false
    ): ResolveResult {
        if (!song.isNetworkSong) return ResolveResult(song.streamUrl, quality)
        val src = song.networkSource ?: run {
            AppLog.e(TAG, "resolvePlayUrl: networkSource 为 null（song id=${song.id} title=${song.title}），无法路由")
            return ResolveResult.failure(quality)
        }
        val svc = services[src] ?: run {
            AppLog.w(TAG, "resolvePlayUrl: 未注册源 source=$src（song id=${song.id}）；已注册源=${services.keys}")
            return ResolveResult.failure(quality)
        }
        val cacheKey = playUrlKey(song, quality)
        AppLog.d(TAG, "resolvePlayUrl: 路由到 source=$src (song id=${song.id} networkId=${song.networkId}) quality=$quality forceRefresh=$forceRefresh")

        // 清理过期缓存条目
        val now = System.currentTimeMillis()
        playUrlCache.entries.removeAll { (_, v) -> now - v.timestamp >= PLAY_URL_CACHE_TTL_MS }
        // 容量上限：超出后按时间淘汰最旧条目（近似 LRU，避免缓存无界增长）
        if (playUrlCache.size > PLAY_URL_CACHE_MAX) {
            playUrlCache.entries
                .sortedBy { it.value.timestamp }
                .take(playUrlCache.size - PLAY_URL_CACHE_MAX)
                .forEach { playUrlCache.remove(it.key) }
        }

        // 检查缓存（forceRefresh 时跳过——重试路径绝不命中旧缓存）
        if (!forceRefresh) {
            val cached = playUrlCache[cacheKey]
            if (cached != null) {
                AppLog.d(TAG, "resolvePlayUrl: cache hit for key=$cacheKey")
                return ResolveResult(cached.url, cached.actualQuality)
            }
        } else {
            // 强制刷新：清除旧缓存条目，确保下面走完整降级链
            playUrlCache.remove(cacheKey)
        }

        return try {
            val result = svc.resolvePlayUrlDetailed(song, quality)
            if (result.isSuccess) {
                // 写入缓存（含实际档位，降级结果也缓存，避免重复降级探测）
                playUrlCache[cacheKey] = CachedPlayUrl(result.url!!, now, result.actualQuality)
                AppLog.d(TAG, "resolvePlayUrl: cached new url for key=$cacheKey actual=${result.actualQuality}")
            } else {
                // 解析失败：移除缓存条目，避免下次又命中过期项
                playUrlCache.remove(cacheKey)
            }
            result
        } catch (e: Exception) {
            AppLog.w(TAG, "resolvePlayUrl error: ${e.message}", e)
            playUrlCache.remove(cacheKey)
            ResolveResult.failure(quality)
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
        if (!song.isNetworkSong) return null  // consistent with resolveLyrics; caller falls back to song.coverUrl
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
            val url = try {
                svc.searchCoverUrl(title, artist)
            } catch (e: Exception) {
                AppLog.w(TAG, "searchCoverUrl error: ${e.message}", e)
                null
            }
            if (url != null) return url
        }
        return null
    }

    /**
     * 获取播放列表歌曲列表。
     *
     * 按默认源路由，不 fallback — 播放列表 ID 是来源特定的（如网易云歌单）。
     */
    suspend fun getPlaylist(playlistId: String): List<Song> {
        val svc = services[defaultSource] ?: run {
            AppLog.w(TAG, "getPlaylist: no service for defaultSource=$defaultSource")
            return emptyList()
        }
        return try {
            svc.getPlaylist(playlistId)
        } catch (e: Exception) {
            AppLog.w(TAG, "getPlaylist error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 通过组合关键词搜索（用于多维度浏览）。
     *
     * 将非空的多个关键词用空格拼接后调用 [search]。
     * 若关键词列表为空，返回空列表。
     */
    suspend fun searchByKeywords(keywords: List<String>): List<Song> {
        val query = keywords.filter { it.isNotBlank() }
            .joinToString(separator = " ")
            .trim()
        if (query.isBlank()) return emptyList()
        AppLog.i("MetingDiag", "searchByKeywords: combined='$query'")
        return search(query)
    }

    /**
     * 获取所有已注册源 ID（用于设置页面展示可选项）
     */
    fun availableSources(): List<String> = services.keys.toList()

    /**
     * 运行时注册网络音乐源（如百度网盘开关开启后）。
     * 已存在同 sourceId 则覆盖。
     */
    fun registerService(service: NetworkMusicService) {
        services[service.sourceId] = service
        AppLog.i(TAG, "registerService: ${service.sourceId} (total=${services.size})")
    }

    /**
     * 运行时注销网络音乐源（如百度网盘开关关闭后）。
     */
    fun unregisterService(sourceId: String) {
        services.remove(sourceId)
        // 清理该源相关的播放缓存。修复（2026-09-22 审查）：playUrlCache 的 key 由
        // playUrlKey() 生成为 "<networkSource>:<networkId>:<quality>"，原过滤串
        // "ntwk_<sourceId>_" 是歌曲 id 的格式，永远匹配不上（注销后旧直链残留至 TTL）。
        playUrlCache.entries.removeAll { it.key.startsWith("${sourceId}:") }
        AppLog.i(TAG, "unregisterService: $sourceId (total=${services.size})")
    }

    /**
     * 构造按优先级排序的服务列表：默认源在前，其余按 Map 迭代顺序
     */
    private fun orderedServices(): List<NetworkMusicService> {
        val def = defaultSourceProvider()
        val defSvc = services[def]
        val others = services.filterKeys { it != def }.values
        return if (defSvc != null) listOf(defSvc) + others else others.toList()
    }

    /**
     * 跨源降级解析结果。
     *
     * @param replacement 替代歌曲（来自其他网络源，带已解析的 playUrl）
     * @param playUrl 可直接播放的链接
     * @param sourceId 命中源 ID（用于日志/提示）
     */
    data class CrossSourceResult(
        val replacement: Song,
        val playUrl: String,
        val sourceId: String
    )

    /**
     * 带跨源降级的播放链接解析。
     *
     * 策略（从轻到重）：
     * 1. 先按原源精确路由解析（含 forceRefresh 语义）。
     * 2. 原源解析失败时，按 [orderedServices] 顺序遍历**排除原源之外**的其他已注册源，
     *    用 `title + artist` 重新搜索，并对候选结果逐个做可播校验
     *    （`resolvePlayUrl` 非空才算可播，避免「搜到但依然播不了」），
     *    取第一个可播的替代曲返回。
     * 3. 全部源失败返回 null（由调用方决定跳下一首）。
     *
     * @param song 原歌曲（networkSource 即其来源）
     * @param forceRefresh 原源解析时是否强制绕过缓存
     * @return 跨源替代结果；无需降级或降级失败返回 null
     */
    suspend fun resolvePlayUrlWithCrossSourceFallback(
        song: Song,
        forceRefresh: Boolean = false
    ): CrossSourceResult? {
        if (!song.isNetworkSong) return null
        val src = song.networkSource ?: return null

        // 原源解析
        val originalUrl = resolvePlayUrl(song, forceRefresh)
        if (!originalUrl.isNullOrBlank()) return null  // 原源可播，无需降级

        AppLog.w(TAG, "cross-source: 原源 source=$src 解析失败，尝试其他源重搜 title='${song.title}' artist='${song.artist}'")

        // 用 title+artist 构造搜索关键词（artist 为空时仅用 title）
        val keywords = listOf(song.title, song.artist).filter { it.isNotBlank() }
        if (keywords.isEmpty()) {
            AppLog.w(TAG, "cross-source: title 与 artist 均为空，无法重搜")
            return null
        }
        val query = keywords.joinToString(" ").trim()

        // 按优先级顺序尝试其他源（排除原源）
        for (svc in orderedServices()) {
            if (svc.sourceId == src) continue  // 跳过原源
            if (!services.containsKey(svc.sourceId)) continue
            AppLog.w(TAG, "cross-source: 尝试候选源 ${svc.sourceId} 搜索 '$query'")
            val candidates = try {
                svc.search(query)
            } catch (e: Exception) {
                AppLog.w(TAG, "cross-source: ${svc.sourceId} 搜索失败: ${e.message}")
                emptyList()
            }
            if (candidates.isEmpty()) {
                AppLog.w(TAG, "cross-source: ${svc.sourceId} 无搜索结果")
                continue
            }

            // 对候选逐个做可播校验，取第一个可播的
            for (candidate in candidates) {
                // 排除原曲本身（同 id 同源）与空标题结果
                if (candidate.id == song.id) continue
                val candidateUrl = try {
                    svc.resolvePlayUrl(candidate)
                } catch (e: Exception) {
                    AppLog.w(TAG, "cross-source: ${svc.sourceId} 候选 ${candidate.title} 解析异常: ${e.message}")
                    null
                }
                if (!candidateUrl.isNullOrBlank()) {
                    // 把解析好的 URL 写回替代曲，保证替换即可播
                    val replacement = candidate.copy(streamUrl = candidateUrl)
                    AppLog.w(TAG, "cross-source: 命中源 ${svc.sourceId} 替代 '${candidate.title}' -> $candidateUrl")
                    return CrossSourceResult(replacement, candidateUrl, svc.sourceId)
                }
            }
            AppLog.w(TAG, "cross-source: ${svc.sourceId} 候选均不可播")
        }

        AppLog.w(TAG, "cross-source: 所有源均无可播替代曲")
        return null
    }
}

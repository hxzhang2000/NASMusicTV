package com.nasmusic.tv.backend.playlist

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * HTTP URL 可达性检查器（docs/archive/playlist-import-feature-plan.md §4.1.8 (3)）。
 *
 * - 导入时后台批量测试（不可达标 Unreachable），不阻塞导入
 * - 5 分钟内存缓存（与 NetworkMusicManager 一致），单 URL 只测一次
 * - 局域网段不预判（isPrivateLanUrl → ExoPlayer 首次播放自然判定）
 *
 * 实现模仿 [com.nasmusic.tv.backend.download.SongDownloadManager.headContentLength]
 * 的 OkHttp HEAD 写法；client 由调用方注入（默认按同款配置新建）。
 */
class UrlReachabilityChecker(
    private val client: OkHttpClient = defaultClient(),
    private val cacheTtlMs: Long = 5 * 60 * 1000L,
) {
    enum class Result {
        REACHABLE,         // 2xx/3xx → 可播放
        NOT_FOUND,         // 404 → 资源不存在
        SERVER_ERROR,      // 5xx → 服务端错误,可重试
        TIMEOUT,           // 5s 内未响应
        DNS_FAILED,        // 域名解析失败(NAS 关机/网络断)
        REDIRECT_LOOP,     // 重定向循环
        INVALID_URL,       // URL 非法(无法 parse)
    }

    private data class CacheEntry(val result: Result, val length: Long?, val checkedAt: Long, val ttlMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * 单个 URL 测试。
     *
     * @param url 待测 URL
     * @param timeoutMs 单次超时(默认 5000ms);0 表示不超时
     * @return Result + Content-Length(若有);命中缓存直接返回
     */
    suspend fun check(url: String, timeoutMs: Long = 5000L): Pair<Result, Long?> {
        // 1. 查内存缓存(默认 5 分钟 TTL;seedCache 灌入的持久化判定用其自身 TTL)
        cache[url]?.let { entry ->
            if (System.currentTimeMillis() - entry.checkedAt < entry.ttlMs) {
                return entry.result to entry.length
            }
            cache.remove(url, entry)
        }
        val (result, length) = runCheck(url, timeoutMs)
        cache[url] = CacheEntry(result, length, System.currentTimeMillis(), cacheTtlMs)
        return result to length
    }

    /**
     * 预热：把持久化的判定（AppPreferences.songReachability，§4.7.4）直接灌入内存缓存，
     * 使重启后窗口内的「不可达」标记不重复触发 HEAD 测试。
     *
     * @param url 与持久化条目对应的歌曲 streamUrl（必须是导入歌单仍存在的 URL 直链）
     * @param result 持久化的判定结果（AppPreferences.ReachabilityEntry.result 字符串）
     * @param checkedAt 持久化时的判定时间戳（ms）；窗口外的条目由调用方过滤后不再 seed
     * @param ttlMs 该条目的剩余有效时长（默认 24h = 持久化判定窗口）
     */
    fun seedCache(url: String, result: Result, checkedAt: Long, ttlMs: Long = PERSISTED_TTL_MS) {
        cache[url] = CacheEntry(result, null, checkedAt, ttlMs)
    }

    /**
     * 批量并发测试(并发上限 4),按输入顺序返回。
     * 任一 URL 失败不抛异常——由 Result 表达(测试要点 §4.1.8 (7))。
     */
    suspend fun checkBatch(
        urls: List<String>,
        timeoutMs: Long = 5000L,
    ): List<Pair<Result, Long?>> {
        if (urls.isEmpty()) return emptyList()
        val semaphore = Semaphore(MAX_CONCURRENCY)
        return coroutineScope {
            urls.map { url ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { check(url, timeoutMs) }
                }
            }.awaitAll()
        }
    }

    /**
     * 是否为局域网段 URL（§4.1.8 (6)）：
     * 192.168.x / 10.x / 172.16-31.x。此类 URL 可达性由 ExoPlayer 承担，不预判。
     */
    fun isPrivateLanUrl(url: String): Boolean {
        val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return false
        return isPrivateIpv4(host)
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val nums = parts.mapNotNull { it.toIntOrNull() }
        if (nums.size != 4) return false
        val (a, b) = nums[0] to nums[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168)
    }

    /** 执行一次 HTTP HEAD（在 IO 线程）；异常全部归类为 [Result]，不向外抛。 */
    private suspend fun runCheck(url: String, timeoutMs: Long): Pair<Result, Long?> {
        // 2. URL 合法性
        val parsed = runCatching { url.toHttpUrl() }.getOrNull()
            ?: return Result.INVALID_URL to null

        // 3. OkHttp HEAD(自动 followRedirects=true)
        val req = Request.Builder().url(parsed).method("HEAD", null).build()
        return withContext(Dispatchers.IO) {
            try {
                val call = if (timeoutMs > 0) {
                    // 每次新 call 用独立 timeout，避免修改共享 client 配置
                    client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(req)
                } else {
                    client.newCall(req)
                }
                call.execute().use { resp ->
                    val length = resp.header("Content-Length")?.toLongOrNull()
                    val result = when {
                        resp.isRedirect -> Result.REDIRECT_LOOP     // follow 后仍重定向(OkHttp 达到上限)
                        resp.isSuccessful -> Result.REACHABLE       // 2xx
                        resp.code == 404 -> Result.NOT_FOUND
                        resp.code in 500..599 -> Result.SERVER_ERROR
                        else -> Result.NOT_FOUND                   // 其他 4xx 一律视为不可达
                    }
                    result to length
                }
            } catch (e: SocketTimeoutException) {
                Result.TIMEOUT to null
            } catch (e: UnknownHostException) {
                Result.DNS_FAILED to null
            } catch (e: SSLException) {
                Result.DNS_FAILED to null    // 局域网 NAS 多见
            } catch (e: ConnectException) {
                Result.DNS_FAILED to null
            } catch (e: Exception) {
                // OkHttp 4.x 重定向上限超限抛 ProtocolException("Too many follow-up requests: N")
                if (e.javaClass.simpleName.contains("TooManyFollowRequests") ||
                    e.message?.contains("Too many follow") == true
                ) {
                    Result.REDIRECT_LOOP to null
                } else {
                    Result.SERVER_ERROR to null
                }
            }
        }
    }

    companion object {
        /** 批量并发上限（§4.1.8 (4)：并发 4） */
        const val MAX_CONCURRENCY = 4

        /** 持久化判定 seed 的 TTL（与 AppPreferences.songReachabilityWindowMs 一致，24h） */
        const val PERSISTED_TTL_MS = 24 * 60 * 60 * 1000L

        /** 默认 client：连读超时 5s、跟随重定向、失败重连（与 SongDownloadManager 同风格，不注入认证头——公网 URL 无鉴权语义）。 */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
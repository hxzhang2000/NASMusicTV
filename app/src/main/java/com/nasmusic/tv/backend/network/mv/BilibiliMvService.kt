package com.nasmusic.tv.backend.network.mv

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nasmusic.tv.data.model.MvInfo
import com.nasmusic.tv.data.model.MvCandidate
import com.nasmusic.tv.data.model.MvSearchResult
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Bilibili MV（音乐视频）搜索服务实现
 *
 * 三步取流：
 * 1. 搜索 → 拿 bvid：`GET {base}/x/web-interface/wbi/search/type?search_type=video&keyword=<title+artist>`
 *    （未登录有风控风险，失败时回退非 wbi 变体 `/x/web-interface/search/type`）
 * 2. 拿 cid：`GET {base}/x/web-interface/view?bvid=<bvid>` → cid/title/pic/duration
 * 3. 拿播放直链：`GET {base}/x/player/playurl?bvid=<bvid>&cid=<cid>&fnval=1` → durl[].url
 *
 * 防盗链：所有请求需带 `Referer: https://www.bilibili.com` + 浏览器 UA。
 * 直链有有效期（小时级），由 MvSearchManager 的内存缓存 TTL 负责过期重建。
 * 网络/解析/风控错误一律吞掉返回 null（MTV 按钮置暗即可，不打扰用户）。
 *
 * 端点可配置：设置页「网络 > 视频端点」选取，baseUrlProvider 每次请求时读取
 * （支持官方端点或自建代理/镜像，路径结构保持一致）。
 *
 * 对应 docs/mv-karaoke-feature-proposal.md §2.2 / Step 1
 */
class BilibiliMvService(
    /**
     * 运行时获取 B 站 API 基础端点（如 https://api.bilibili.com）。
     * 由 AppPreferences.getMvApiBaseUrlSync() 提供，支持设置页运行时切换。
     */
    private val baseUrlProvider: () -> String = { DEFAULT_BASE_URL }
) : MvSearchService {

    /** 守护线程池：防止 OkHttp 非守护线程阻止进程退出（与 MetingApiService 一致） */
    private val daemonExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "BiliMv-OkHttp").apply { isDaemon = true }
    }

    /** 信任所有证书的 TrustManager（旧 TV 盒子可能缺新 CA 根证书，与 MetingApiService 一致） */
    private val trustAllManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher(daemonExecutor))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .applyTrustAllSsl()
            .build()
    }

    private val gson = Gson()

    /** 当前基础端点（每次请求动态读取，清理非法字符，与 MetingApiService 一致） */
    private val baseUrl: String
        get() = baseUrlProvider()
            .trim()
            .trim('`', '\'', '"')
            .trim()
            .trimEnd('/')

    override suspend fun searchMv(title: String, artist: String): MvSearchResult? = withContext(Dispatchers.IO) {
        try {
            val keyword = buildKeyword(title, artist)
            if (keyword.isBlank()) {
                AppLog.w(TAG, "searchMv: empty keyword")
                return@withContext null
            }
            AppLog.d(TAG, "searchMv: keyword='$keyword'")

            val candidates = searchCandidates(keyword)
            if (candidates.isEmpty()) {
                AppLog.w(TAG, "searchMv: no candidates for '$keyword'")
                return@withContext null
            }

            // 解析最佳候选的直链；失败则尝试下一个
            val best = candidates[0]
            val mv = resolveMvInternal(best.bvid) ?: run {
                if (candidates.size > 1) {
                    AppLog.w(TAG, "searchMv: best resolve failed, trying ${candidates[1].bvid}")
                    resolveMvInternal(candidates[1].bvid)?.also { resolved ->
                        return@withContext MvSearchResult(resolved, candidates.filter { it.bvid != candidates[1].bvid })
                    }
                }
                AppLog.w(TAG, "searchMv: resolve failed for all candidates")
                return@withContext null
            }

            AppLog.i(TAG, "searchMv: OK '${mv.title}' url=${mv.videoUrl.take(60)} candidates=${candidates.size}")
            MvSearchResult(mv, candidates.drop(1))
        } catch (e: Exception) {
            AppLog.e(TAG, "searchMv failed: ${e.message}", e)
            null
        }
    }

    override suspend fun resolveMv(bvid: String): MvInfo? = withContext(Dispatchers.IO) {
        resolveMvInternal(bvid)
    }

    /** 解析指定 bvid 的直链（searchMv 和 resolveMv 共用） */
    private fun resolveMvInternal(bvid: String): MvInfo? {
        val videoInfo = getVideoInfo(bvid) ?: return null
        val playUrl = getPlayUrl(videoInfo) ?: return null
        return MvInfo(
            bvid = bvid,
            title = videoInfo.title,
            coverUrl = videoInfo.pic,
            videoUrl = playUrl,
            durationMs = videoInfo.durationSeconds * 1000L
        )
    }

    /**
     * 组合搜索关键词：标题 艺术家。
     * 艺术家若有多个（逗号/斜杠分隔）只取第一个，避免关键词过长降低匹配率。
     */
    private fun buildKeyword(title: String, artist: String): String {
        val t = title.trim()
        val firstArtist = artist.trim().split('/', '、', ',', '，', '&')[0].trim()
        return if (firstArtist.isEmpty()) t else "$t $firstArtist"
    }

    /**
     * Step 1 - 搜索：先试 wbi 搜索接口，失败（风控 412/异常）回退非 wbi 变体。
     * 返回按相似度排序的候选列表（含 bvid / 标题 / 封面）。
     */
    private fun searchCandidates(keyword: String): List<MvCandidate> {
        val query = "search_type=video&keyword=${URLEncoder.encode(keyword, "UTF-8")}"
        val wbiBody = execGet("$baseUrl/x/web-interface/wbi/search/type?$query")
        wbiBody?.let { parseCandidatesFromSearch(it, keyword)?.let { return it } }
        // 回退：非 wbi 变体
        val legacyBody = execGet("$baseUrl/x/web-interface/search/type?$query")
        return legacyBody?.let { parseCandidatesFromSearch(it, keyword) } ?: emptyList()
    }

    /**
     * 解析搜索结果：返回按相似度排序的候选列表（≥ 阈值），含 bvid / 标题 / 封面。
     * null 表示接口异常；空列表表示正常但无匹配。
     */
    internal fun parseCandidatesFromSearch(body: String, keyword: String): List<MvCandidate>? {
        try {
            val json = gson.fromJson(body, JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 0) {
                AppLog.w(TAG, "search: code=${json.get("code")}")
                return null
            }
            val resultArr = json.getAsJsonObject("data")?.getAsJsonArray("result") ?: return null
            data class ScoredCandidate(val candidate: MvCandidate, val score: Float)
            val scored = mutableListOf<ScoredCandidate>()
            for (item in resultArr) {
                val obj = item?.asJsonObject ?: continue
                // 仅保留 video 类型（过滤掉 upuser/bili_user 等混杂结果）
                if (obj.get("type")?.asString != "video") continue
                val bvid = obj.get("bvid")?.asString ?: continue
                val title = stripHtml(obj.get("title")?.asString ?: "")
                val pic = obj.get("pic")?.asString?.let { if (it.startsWith("//")) "https:$it" else it }
                val score = similarity(title, keyword)
                if (score >= MIN_SIMILARITY) {
                    scored.add(ScoredCandidate(MvCandidate(bvid, title, pic), score))
                }
            }
            AppLog.d(TAG, "search: ${scored.size} candidates for '$keyword'")
            return scored.sortedByDescending { it.score }.map { it.candidate }
        } catch (e: Exception) {
            AppLog.w(TAG, "search parse failed: ${e.message}", e)
            return null
        }
    }

    /**
     * Step 2 — 拿视频信息（cid/title/pic/duration）。
     * 对应 `GET /x/web-interface/view?bvid=` 响应 data 字段。
     */
    private fun getVideoInfo(bvid: String): VideoInfo? {
        val body = execGet("$baseUrl/x/web-interface/view?bvid=$bvid") ?: return null
        return try {
            val json = gson.fromJson(body, JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 0) return null
            val data = json.getAsJsonObject("data") ?: return null
            val cid = data.get("cid")?.asLong ?: return null
            VideoInfo(
                bvid = bvid,
                cid = cid,
                title = data.get("title")?.asString ?: "",
                pic = data.get("pic")?.asString,
                durationSeconds = data.get("duration")?.asLong ?: 0L
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "view parse failed: ${e.message}", e)
            null
        }
    }

    /**
     * Step 3 — 拿播放直链。fnval=1 → durl 分片列表。
     * 优先取 durl[0].url（含防盗链 Referer 校验，需请求头）。若 durl 缺失回退 DASH baseUrl。
     */
    private fun getPlayUrl(info: VideoInfo): String? {
        val body = execGet("$baseUrl/x/player/playurl?bvid=${info.bvid}&cid=${info.cid}&fnval=1")
            ?: return null
        return try {
            extractPlayUrl(body)
        } catch (e: Exception) {
            AppLog.w(TAG, "playurl parse failed: ${e.message}", e)
            null
        }
    }

    /** 从 playurl 响应提取直链：优先 durl[].url，其次 dash.video[].baseUrl */
    internal fun extractPlayUrl(body: String): String? {
        try {
            val json = gson.fromJson(body, JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 0) return null
            val data = json.getAsJsonObject("data") ?: return null

            val durl = data.getAsJsonArray("durl")
            if (durl != null) {
                for (item in durl) {
                    val url = item?.asJsonObject?.get("url")?.asString
                    if (!url.isNullOrBlank()) return url
                }
            }
            val dash = data.getAsJsonObject("dash")
            val dashVideo = dash?.getAsJsonArray("video")
            if (dashVideo != null) {
                for (item in dashVideo) {
                    val url = item?.asJsonObject?.get("baseUrl")?.asString
                    if (!url.isNullOrBlank()) return url
                }
            }
            return null
        } catch (e: Exception) {
            AppLog.w(TAG, "extractPlayUrl parse failed: ${e.message}", e)
            return null
        }
    }

    /** 执行 GET，带 B 站防盗链必需的 Referer + 浏览器 UA；出错返回 null */
    private fun execGet(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Referer", "https://www.bilibili.com")
                .header("User-Agent", UA)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.w(TAG, "GET ${url.requestUrl()} failed: ${resp.code}")
                    null
                } else {
                    resp.body?.string()
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "GET ${url.requestUrl()} error: ${e.message}", e)
            null
        }
    }

    private fun String.requestUrl(): String = take(90)

    private fun OkHttpClient.Builder.applyTrustAllSsl(): OkHttpClient.Builder {
        try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAllManager), java.security.SecureRandom())
            this.sslSocketFactory(sslContext.socketFactory, trustAllManager)
            this.hostnameVerifier(trustAllHostnameVerifier)
        } catch (e: Exception) {
            AppLog.e(TAG, "applyTrustAllSsl failed: ${e.message}", e)
        }
        return this
    }

    /** 去掉 B 站搜索结果标题中的 <em class="keyword"> 高亮标签 */
    private fun stripHtml(s: String): String = s.replace(Regex("<[^>]+>"), "").trim()

    /**
     * 标题相似度（0..1）：
     * - 完全相等 → 1.0
     * - 包含关系（标题含关键词 或 关键词含标题）→ 0.9/0.8
     * - 否则按关键词字符在标题中的顺序覆盖率打分
     */
    private fun similarity(target: String, keyword: String): Float {
        val t = target.lowercase()
        val k = keyword.lowercase()
        if (t.isBlank() || k.isBlank()) return 0f
        if (t == k) return 1f
        if (t.contains(k)) return 0.9f
        if (k.contains(t)) return 0.8f
        var pos = 0
        var matched = 0
        for (c in k) {
            val found = t.indexOf(c, pos)
            if (found >= 0) { matched++; pos = found + 1 }
        }
        return matched.toFloat() / k.length
    }

    /** video info 中间结果 */
    private data class VideoInfo(
        val bvid: String,
        val cid: Long,
        val title: String,
        val pic: String?,
        val durationSeconds: Long
    )

    companion object {
        private const val TAG = "BilibiliMvService"

        // 默认 B 站官方 API 端点
        const val DEFAULT_BASE_URL = "https://api.bilibili.com"

        /** 预设视频端点列表（供设置页选择） */
        val PRESET_ENDPOINTS: List<Pair<String, String>> = listOf(
            "B站官方 API（默认）" to DEFAULT_BASE_URL
        )

        /** 浏览器 UA（B 站防盗链检查之一） */
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** 标题相似度最低阈值（低于则视为无匹配） */
        private const val MIN_SIMILARITY = 0.5f
    }
}
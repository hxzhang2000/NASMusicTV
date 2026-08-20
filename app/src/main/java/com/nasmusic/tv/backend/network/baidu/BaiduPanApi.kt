package com.nasmusic.tv.backend.network.baidu

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.BaiduFileMeta
import com.nasmusic.tv.data.model.BaiduThumbs
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * 百度网盘文件 API 封装
 *
 * ⚠️ 列表/搜索走 [BaiduNetdiskConfig.FILE_BASE]，元数据/dlink 走 [BaiduNetdiskConfig.MULTIMEDIA_BASE]，
 * 两者端点不同勿混用（详见方案 §3.2）。
 *
 * ⚠️ search 参数名是 `key` 非 `word`（BoxPlayer dirfilelist.ts:99 实证）。
 *
 * 所有方法在 access_token 缺失时返回空结果，不抛异常。
 */
class BaiduPanApi(
    private val client: OkHttpClient,
    private val oauth: BaiduOAuthClient,
    private val gson: Gson = Gson()
) {

    /** list 响应 */
    data class BaiduListResult(
        val files: List<BaiduFile>,
        /** 顶层 has_more：=1 时继续翻页 */
        val hasMore: Boolean
    )

    /** 列出目录 */
    suspend fun listDir(
        dir: String,
        start: Int = 0,
        limit: Int = BaiduNetdiskConfig.PAGE_SIZE,
        order: String = "name",
        desc: Int = 0
    ): BaiduListResult = withContext(Dispatchers.IO) {
        val token = oauth.getValidAccessToken() ?: return@withContext BaiduListResult(emptyList(), false)
        val url = buildUrl(BaiduNetdiskConfig.FILE_BASE, token) {
            addQueryParameter("method", BaiduNetdiskConfig.METHOD_LIST)
            addQueryParameter("dir", dir)
            addQueryParameter("order", order)
            addQueryParameter("desc", desc.toString())
            addQueryParameter("start", start.toString())
            addQueryParameter("limit", limit.toString())
            addQueryParameter("web", "1")     // 返回缩略图 thumbs
            addQueryParameter("folder", "0")
        }
        execute(url) { json -> parseListResponse(json) } ?: BaiduListResult(emptyList(), false)
    }

    /**
     * 递归列出全部音频文件（建索引用，主路径：BFS 逐目录 list + 节流，
     * 由 [BaiduFileIndexCache] 协调；此方法作为可选加速封装 listall 端点，未验证）。
     */
    suspend fun listAllAudio(rootPath: String): List<BaiduFile> = withContext(Dispatchers.IO) {
        val token = oauth.getValidAccessToken() ?: return@withContext emptyList()
        val url = buildUrl(BaiduNetdiskConfig.FILE_BASE, token) {
            addQueryParameter("method", BaiduNetdiskConfig.METHOD_LISTALL)
            addQueryParameter("path", rootPath)
            addQueryParameter("recursion", "1")
            addQueryParameter("web", "1")
        }
        execute(url) { json ->
            val list = pickListArray(json)
            list.mapNotNull { parseBaiduFile(it) }
                .filter { !it.isDir && it.category == BaiduNetdiskConfig.CATEGORY_AUDIO }
        } ?: emptyList()
    }

    /** 关键词搜索音频（参数名 key 非 word） */
    suspend fun searchAudio(
        keyword: String,
        dir: String = "/",
        start: Int = 0,
        limit: Int = BaiduNetdiskConfig.SEARCH_PAGE_SIZE
    ): List<BaiduFile> = search(keyword, dir, start, limit, BaiduNetdiskConfig.CATEGORY_AUDIO)

    /** 关键词搜索视频（MV 用） */
    suspend fun searchVideo(
        keyword: String,
        dir: String = "/",
        start: Int = 0,
        limit: Int = BaiduNetdiskConfig.SEARCH_PAGE_SIZE
    ): List<BaiduFile> = search(keyword, dir, start, limit, BaiduNetdiskConfig.CATEGORY_VIDEO)

    private suspend fun search(
        keyword: String,
        dir: String,
        start: Int,
        limit: Int,
        category: Int
    ): List<BaiduFile> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        val token = oauth.getValidAccessToken() ?: return@withContext emptyList()
        val url = buildUrl(BaiduNetdiskConfig.FILE_BASE, token) {
            addQueryParameter("method", BaiduNetdiskConfig.METHOD_SEARCH)
            addQueryParameter("key", keyword)   // ⚠️ key 非 word
            addQueryParameter("dir", dir)
            addQueryParameter("num", limit.toString())
            addQueryParameter("recursion", "1")
            addQueryParameter("web", "1")
            addQueryParameter("category", category.toString())
        }
        execute(url) { json ->
            val list = pickListArray(json)
            list.mapNotNull { parseBaiduFile(it) }
        } ?: emptyList()
    }

    /** 获取文件元数据 + dlink（fsids 作为 JSON 数组参数） */
    suspend fun fileMetas(fsIds: List<Long>): List<BaiduFileMeta> = withContext(Dispatchers.IO) {
        if (fsIds.isEmpty()) return@withContext emptyList()
        val token = oauth.getValidAccessToken() ?: return@withContext emptyList()
        val fsidsJson = gson.toJson(fsIds)  // [123,456]
        val url = buildUrl(BaiduNetdiskConfig.MULTIMEDIA_BASE, token) {
            addQueryParameter("method", BaiduNetdiskConfig.METHOD_FILEMETAS)
            addQueryParameter("fsids", fsidsJson)
            addQueryParameter("dlink", "1")
            addQueryParameter("thumb", "1")
            addQueryParameter("extra", "1")
            addQueryParameter("needmedia", "1")
            addQueryParameter("detail", "1")
        }
        execute(url) { json ->
            val list = pickListArray(json)
            list.mapNotNull { parseBaiduFileMeta(it) }
        } ?: emptyList()
    }

    // ---- 内部工具 ----

    private inline fun buildUrl(
        base: String,
        token: String,
        block: okhttp3.HttpUrl.Builder.() -> Unit
    ): String {
        val builder = base.toHttpUrl().newBuilder()
            .addQueryParameter("access_token", token)
            .apply(block)
        return builder.build().toString()
    }

    private inline fun <T> execute(url: String, parser: (JsonObject) -> T): T? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", BaiduNetdiskConfig.BAIDU_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return null
                if (!resp.isSuccessful) {
                    AppLog.w(TAG, "request failed url=${url.take(120)} code=${resp.code} body=${body.take(200)}")
                    return null
                }
                val json = gson.fromJson(body, JsonObject::class.java) ?: return null
                val errno = json.get("errno")?.asInt ?: 0
                if (errno != 0) {
                    AppLog.w(TAG, "errno=$errno ${BaiduNetdiskConfig.describeErrno(errno)} url=${url.take(120)}")
                    // errno 非零时通常 list 字段缺失，仍尝试解析（容错）
                }
                parser(json)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "execute error url=${url.take(120)}", e)
            null
        }
    }

    /** 响应容器兼容：data.list || data.info || 顶层 list || 顶层 info */
    internal fun pickListArray(json: JsonObject): List<JsonObject> {
        val dataObj = json.getAsJsonObject("data")
        val arr = json.getAsJsonArray("list")
            ?: json.getAsJsonArray("info")
            ?: dataObj?.getAsJsonArray("list")
            ?: dataObj?.getAsJsonArray("info")
            ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }
    }

    internal fun parseListResponse(json: JsonObject): BaiduListResult {
        val files = pickListArray(json).mapNotNull { parseBaiduFile(it) }
        val hasMore = json.get("has_more")?.asInt == 1 ||
            json.getAsJsonObject("data")?.get("has_more")?.asInt == 1
        return BaiduListResult(files, hasMore)
    }

    internal fun parseBaiduFile(o: JsonObject): BaiduFile? {
        return try {
            val fsId = o.get("fs_id")?.asString?.toLongOrNull() ?: return null
            BaiduFile(
                fsId = fsId,
                path = o.get("path")?.asString ?: "",
                serverFilename = o.get("server_filename")?.asString
                    ?: o.get("filename")?.asString ?: "",
                isDir = o.get("isdir")?.asInt == 1,
                size = o.get("size")?.asLong ?: 0L,
                category = o.get("category")?.asInt ?: BaiduNetdiskConfig.CATEGORY_BT,
                md5 = o.get("md5")?.asString,
                serverMtime = o.get("server_mtime")?.asLong ?: 0L
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "parseBaiduFile error", e)
            null
        }
    }

    internal fun parseBaiduFileMeta(o: JsonObject): BaiduFileMeta? {
        return try {
            val fsId = o.get("fs_id")?.asString?.toLongOrNull() ?: return null
            val mediaInfo = o.getAsJsonObject("media_info")
            BaiduFileMeta(
                fsId = fsId,
                dlink = o.get("dlink")?.asString,
                filename = o.get("filename")?.asString,
                size = o.get("size")?.asLong ?: 0L,
                durationSec = o.get("duration")?.asLong,
                durationMs = mediaInfo?.get("duration_ms")?.asLong,
                bitrate = o.get("bitrate")?.asInt ?: mediaInfo?.get("bitrate")?.asInt,
                thumbs = parseThumbs(o)
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "parseBaiduFileMeta error", e)
            null
        }
    }

    internal fun parseThumbs(o: JsonObject): BaiduThumbs? {
        val thumbs = o.getAsJsonObject("thumbs") ?: return null
        return BaiduThumbs(
            url = thumbs.get("url2")?.asString ?: thumbs.get("url1")?.asString ?: thumbs.get("url3")?.asString,
            icon = thumbs.get("icon")?.asString ?: thumbs.get("url")?.asString
        )
    }

    companion object {
        private const val TAG = "BaiduPanApi"

        /**
         * 判断文件是否为音频（category == AUDIO 或扩展名在白名单）。
         * 对照 BoxPlayer musicScanner.ts 的 30 个音频扩展名。
         */
        val AUDIO_EXTENSIONS = setOf(
            ".mp3", ".flac", ".wav", ".ape", ".ogg", ".aac", ".aif", ".aiff", ".cda",
            ".dsf", ".dts", ".dtshd", ".eac3", ".m1a", ".m2a", ".m4a", ".m4b", ".m4r",
            ".mka", ".mpa", ".mpc", ".opus", ".ra", ".tak", ".tta", ".wma", ".wv",
            ".amr", ".ac3", ".au"
        )

        fun isAudioFile(filename: String, category: Int): Boolean =
            category == BaiduNetdiskConfig.CATEGORY_AUDIO ||
                AUDIO_EXTENSIONS.any { filename.lowercase().endsWith(it) }

        /** ExoPlayer 原生支持的音频扩展名（APE/DSF 等可能播放失败，UI 需容错提示） */
        val EXOPLAYER_NATIVE_AUDIO = setOf(
            ".mp3", ".flac", ".wav", ".ogg", ".aac", ".m4a", ".mka", ".opus", ".amr", ".ac3", ".au"
        )
    }
}

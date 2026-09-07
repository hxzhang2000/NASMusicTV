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
import okhttp3.FormBody
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
    /** 当百度 API 返回 errno!=0 时回调，由 MainViewModel 注册，参数为用户可读错误描述 */
    var onApiError: ((Int, String) -> Unit)? = null

    /** list 响应 */
    data class BaiduListResult(
        val files: List<BaiduFile>,
        /** 顶层 has_more：=1 时继续翻页 */
        val hasMore: Boolean,
        /** API 返回的 errno（0=成功，非0=失败，-6=token失效） */
        val errno: Int = 0
    )

    /** 列出目录 */
    suspend fun listDir(
        dir: String,
        start: Int = 0,
        limit: Int = BaiduNetdiskConfig.PAGE_SIZE,
        order: String = "name",
        desc: Int = 0
    ): BaiduListResult = withContext(Dispatchers.IO) {
        val token = oauth.getValidAccessToken() ?: run {
            AppLog.w(TAG, "listDir: no valid token, returning LOCAL_ERRNO_NO_TOKEN")
            return@withContext BaiduListResult(emptyList(), false, errno = BaiduNetdiskConfig.LOCAL_ERRNO_NO_TOKEN)
        }
        AppLog.d(TAG, "listDir: dir=$dir start=$start limit=$limit")
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
        executeWithErrno(url) { json -> parseListResponse(json) }
    }

    /**
     * 创建目录。
     *
     * 百度 xpan/file?method=create，POST 请求，body 传 path/isdir/size/rtype。
     * errno=0 创建成功，errno=-8 目录已存在（视为成功）。
     *
     * @param dir 要创建的目录路径（如 /apps/NASMusicTV）
     * @return errno（0 或 -8 表示成功）
     */
    suspend fun createDir(dir: String): Int = withContext(Dispatchers.IO) {
        val token = oauth.getValidAccessToken() ?: run {
            AppLog.w(TAG, "createDir: no valid token, returning LOCAL_ERRNO_NO_TOKEN")
            return@withContext BaiduNetdiskConfig.LOCAL_ERRNO_NO_TOKEN
        }
        val url = buildUrl(BaiduNetdiskConfig.FILE_BASE, token) {
            addQueryParameter("method", BaiduNetdiskConfig.METHOD_CREATE)
        }
        val formBody = FormBody.Builder()
            .add("path", dir)
            .add("isdir", "1")
            .add("rtype", "0")   // 不允许重命名重名目录，重名时返回 -8
            .build()
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", BaiduNetdiskConfig.BAIDU_UA)
                .post(formBody)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return@withContext -1
                if (!resp.isSuccessful) {
                    AppLog.w(TAG, "createDir failed code=${resp.code} body=${body.take(200)}")
                    return@withContext -1
                }
                val json = gson.fromJson(body, JsonObject::class.java)
                val errno = json?.get("errno")?.asInt ?: 0
                if (errno == 0) {
                    AppLog.i(TAG, "createDir: created dir=$dir")
                } else if (errno == -8) {
                    AppLog.i(TAG, "createDir: dir already exists dir=$dir")
                } else {
                    val desc = BaiduNetdiskConfig.describeErrno(errno)
                    AppLog.w(TAG, "createDir: errno=$errno $desc dir=$dir")
                    onApiError?.invoke(errno, desc)
                }
                errno
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "createDir error dir=$dir", e)
            -1
        }
    }

    /**
     * 递归列出全部音频文件（建索引用）。
     *
     * 使用 listall + recursion=1 端点，自动分页（has_more/cursor），
     * 每次请求最多返回 10000 条（百度官方上限）。
     *
     * @param rootPath 音乐根目录
     * @param onProgress 进度回调（已获取的条目数）
     * @return 音频文件列表（含 coverThumb）
     */
    suspend fun listAllAudioPaged(
        rootPath: String,
        onProgress: ((Int) -> Unit)? = null
    ): List<BaiduFile> = withContext(Dispatchers.IO) {
        val token = oauth.getValidAccessToken() ?: return@withContext emptyList()
        val allFiles = mutableListOf<BaiduFile>()
        var start = 0
        val limit = 10000  // 百度 listall 上限

        while (true) {
            // ⚠️ listall 必须走 xpan/multimedia 端点（百度官方文档 2026-09-02）。
            // 此前误用 FILE_BASE(xpan/file)，百度静默降级为 list 语义，不递归，
            // 导致只拿到根目录第一层 ~4493 首，4 万+ 歌曲无法索引。
            val url = buildUrl(BaiduNetdiskConfig.MULTIMEDIA_BASE, token) {
                addQueryParameter("method", BaiduNetdiskConfig.METHOD_LISTALL)
                addQueryParameter("path", rootPath)
                addQueryParameter("recursion", "1")
                addQueryParameter("web", "1")
                addQueryParameter("start", start.toString())
                addQueryParameter("limit", limit.toString())
            }
            val result = execute(url) { json ->
                val list = pickListArray(json)
                val files = list.mapNotNull { parseBaiduFile(it) }
                    .filter { !it.isDir && BaiduPanApi.isAudioFile(it.serverFilename, it.category) }
                val hasMore = json.get("has_more")?.asInt == 1 ||
                    json.getAsJsonObject("data")?.get("has_more")?.asInt == 1
                val cursor = json.get("cursor")?.asInt ?:
                    json.getAsJsonObject("data")?.get("cursor")?.asInt ?: 0
                PagedResult(files, hasMore, cursor)
            } ?: break

            allFiles.addAll(result.files)
            onProgress?.invoke(allFiles.size)
            AppLog.d(TAG, "listAllAudioPaged: start=$start, got=${result.files.size}, total=${allFiles.size}, hasMore=${result.hasMore}")

            if (!result.hasMore) break
            start = result.cursor
        }

        allFiles
    }

    private data class PagedResult(
        val files: List<BaiduFile>,
        val hasMore: Boolean,
        val cursor: Int
    )

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
            val firstDlink = list.firstOrNull()?.get("dlink")?.asString?.take(90)
            AppLog.e(TAG, "fileMetas: fsIds=$fsIds → listSize=${list.size} firstDlink=${firstDlink}")
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
                    val desc = BaiduNetdiskConfig.describeErrno(errno)
                    AppLog.w(TAG, "errno=$errno $desc url=${url.take(120)}")
                    onApiError?.invoke(errno, desc)
                }
                parser(json)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "execute error url=${url.take(120)}", e)
            null
        }
    }

    /** 专供 listDir 使用的执行方法，返回包含 errno 的 BaiduListResult */
    private inline fun executeWithErrno(url: String, parser: (JsonObject) -> BaiduListResult): BaiduListResult {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", BaiduNetdiskConfig.BAIDU_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return BaiduListResult(emptyList(), false, errno = -1)
                if (!resp.isSuccessful) {
                    AppLog.w(TAG, "request failed url=${url.take(200)} code=${resp.code} body=$body")
                    return BaiduListResult(emptyList(), false, errno = -1)
                }
                val json = gson.fromJson(body, JsonObject::class.java) ?: return BaiduListResult(emptyList(), false, errno = -1)
                val errno = json.get("errno")?.asInt ?: 0
                if (errno != 0) {
                    val desc = BaiduNetdiskConfig.describeErrno(errno)
                    AppLog.w(TAG, "errno=$errno $desc url=${url.take(200)}")
                    AppLog.w(TAG, "full response body: $body")
                    onApiError?.invoke(errno, desc)
                }
                parser(json).copy(errno = errno)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "executeWithErrno error url=${url.take(200)}", e)
            BaiduListResult(emptyList(), false, errno = -1)
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
                serverMtime = o.get("server_mtime")?.asLong ?: 0L,
                coverThumb = parseThumbs(o)?.url
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

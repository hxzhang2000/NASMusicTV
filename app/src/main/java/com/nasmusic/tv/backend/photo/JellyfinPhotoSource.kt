package com.nasmusic.tv.backend.photo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Jellyfin 照片库来源（**手机 + 电视**）
 *
 * ## 接口
 *
 * - 列表：`/Items?IncludeItemTypes=Photo&Recursive=true&fields=Width,Height&UserId=…`
 * - 取图：`/Items/{id}/Images/Primary?maxWidth=1920&quality=90`
 *
 * ⚠️ `maxWidth=1920` 是刻意的 —— 音频封面用的是 512，照片放大到全屏会糊。
 *
 * ## 认证
 *
 * Jellyfin 用 `api_key` **查询参数**认证。`baseUrl` / `apiToken` / `userId` 都是
 * `JellyfinAdapter` 的私有状态，外部拿不到 ⇒ 通过
 * [BackendAdapter.buildAuthenticatedUrl] + [BackendAdapter.currentUserId] 两个
 * **新增的通用成员**取得（不是为了照片专门开的后门，任何需要任意路径的功能都能用）。
 *
 * Navidrome 等未覆盖 `buildAuthenticatedUrl` 的后端返回 `null`
 * ⇒ 本来源直接判 `UNAVAILABLE`（照片墙不是 Jellyfin 专属能力，但确实只有它支持）。
 *
 * ## 宽高 / 时间
 *
 * - 宽高：`fields=Width,Height` **免费**返回 ⇒ 填真实值（§14.2.1 宽高契约）
 * - 时间：`DateCreated` 是 ISO 8601 ⇒ 必须自己转成**秒**
 *   ⛔ 不能用 `java.time`（需要 API 26，本项目 `minSdk = 22`）
 *   ⇒ 用 `SimpleDateFormat` 手工归一化（见 [isoToEpochSeconds]）
 * - `size`：需要 `MediaSources` 才有，而 **JELLYFIN 项不参与跨来源去重**（§6.8）
 *   ⇒ 不为此多请求字段，填 `0`
 */
class JellyfinPhotoSource(
    private val adapterProvider: () -> BackendAdapter?,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val client: OkHttpClient = defaultClient(),
) : PhotoSource {

    override val kind: PhotoSourceKind = PhotoSourceKind.JELLYFIN

    /**
     * 上次扫描得到的条目总数；`-1` = 尚未扫描过。
     *
     * 用途：让 [status] 保持**轻量**（设置页会高频调用，不能每次发网络请求）。
     * 扫描过一次之后就能准确区分「已连但无照片库」与「可用」。
     */
    @Volatile
    private var lastKnownTotal: Int = UNKNOWN_TOTAL

    override suspend fun status(): PhotoSourceStatus = withContext(Dispatchers.IO) {
        val adapter = adapterProvider() ?: return@withContext PhotoSourceStatus.NOT_CONNECTED
        // 后端不支持任意路径访问（如 Navidrome）⇒ 照片通道不可用
        if (adapter.buildAuthenticatedUrl(PATH_ITEMS) == null) {
            return@withContext PhotoSourceStatus.UNAVAILABLE
        }
        if (lastKnownTotal == 0) PhotoSourceStatus.NO_PHOTO_LIBRARY else PhotoSourceStatus.OK
    }

    override suspend fun listPhotos(): List<PhotoRef> = withContext(Dispatchers.IO) {
        val adapter = adapterProvider() ?: run {
            lastKnownTotal = UNKNOWN_TOTAL
            return@withContext emptyList()
        }

        val out = ArrayList<PhotoRef>(256)
        var startIndex = 0
        var total = -1
        var pages = 0

        while (pages < MAX_PAGES && out.size < MAX_PHOTOS) {
            val page = fetchPage(adapter, startIndex, pageSize) ?: break
            if (total < 0) total = page.total
            if (page.items.isEmpty()) break
            out.addAll(page.items)
            startIndex += pageSize
            pages++
            // 已经取完
            if (startIndex >= total) break
        }

        lastKnownTotal = total.coerceAtLeast(0)
        AppLog.d(TAG, "Scanned ${out.size} photos via Jellyfin (total=$total)")
        out
    }

    override suspend fun openStream(ref: PhotoRef): InputStream? = withContext(Dispatchers.IO) {
        val itemId = PhotoIds.payloadOf(ref.id) ?: return@withContext null
        val adapter = adapterProvider() ?: return@withContext null
        val url = adapter.buildAuthenticatedUrl(
            "$PATH_ITEMS/$itemId/Images/Primary",
            "maxWidth=$MAX_IMAGE_WIDTH&quality=90",
        ) ?: return@withContext null

        runCatching {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                response.close()
                null
            } else {
                // ⚠️ 调用方关闭该流即释放 OkHttp 连接（byteStream 的 close 会关闭 body）
                response.body?.byteStream()
            }
        }.getOrNull()
    }

    // ────────────────────────── 内部实现 ──────────────────────────

    private class Page(val items: List<PhotoRef>, val total: Int)

    private fun fetchPage(adapter: BackendAdapter, startIndex: Int, limit: Int): Page? {
        val query = buildString {
            append("IncludeItemTypes=Photo")
            append("&Recursive=true")
            append("&fields=Width,Height")
            append("&SortBy=SortName&SortOrder=Ascending")
            append("&StartIndex=$startIndex")
            append("&Limit=$limit")
            val uid = adapter.currentUserId
            if (uid.isNotEmpty()) append("&UserId=$uid")
        }
        val url = adapter.buildAuthenticatedUrl(PATH_ITEMS, query) ?: return null
        return runCatching {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.w(TAG, "photo query HTTP ${resp.code}")
                    return@use null
                }
                val body = resp.body?.string() ?: return@use null
                val root = JsonParser.parseString(body).asJsonObject
                val total = root.intOr("TotalRecordCount", 0)
                val arr = root.getAsJsonArray("Items")
                if (arr == null) {
                    Page(emptyList(), total)
                } else {
                    val items = ArrayList<PhotoRef>(arr.size())
                    for (el in arr) {
                        val ref = elementToRef(el.asJsonObject)
                        if (ref != null) items.add(ref)
                    }
                    Page(items, total)
                }
            }
        }.getOrElse {
            AppLog.e(TAG, "photo query failed: ${it.message}", it)
            null
        }
    }

    private fun elementToRef(o: JsonObject): PhotoRef? {
        val id = o.stringOr("Id", null) ?: return null
        val name = o.stringOr("Name", null) ?: id
        // Jellyfin 里也可能混入 HEIC / RAW ⇒ 仍要过白名单（电视解不了）
        if (!isSupportedPhotoName(name)) return null
        val created = isoToEpochSeconds(o.stringOr("DateCreated", null))
        return PhotoRef(
            id = PhotoIds.of(PhotoSourceKind.JELLYFIN, id),
            displayName = name,
            width = o.intOr("Width", 0),
            height = o.intOr("Height", 0),
            size = 0L,
            lastModified = created,
            dateAdded = created,
            source = PhotoSourceKind.JELLYFIN,
        )
    }

    private companion object {
        const val TAG = "JellyfinPhotoSource"
        const val PATH_ITEMS = "/Items"
        const val MAX_IMAGE_WIDTH = 1920
        const val DEFAULT_PAGE_SIZE = 500
        const val MAX_PAGES = 40
        const val MAX_PHOTOS = 20000
        const val UNKNOWN_TOTAL = -1

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

// ────────────────────────── JSON 取值（null 安全） ──────────────────────────

private fun JsonObject.stringOr(key: String, fallback: String?): String? {
    val el = get(key) ?: return fallback
    if (el.isJsonNull) return fallback
    return runCatching { el.asString }.getOrDefault(fallback)
}

private fun JsonObject.intOr(key: String, fallback: Int): Int {
    val el = get(key) ?: return fallback
    if (el.isJsonNull) return fallback
    return runCatching { el.asInt }.getOrDefault(fallback)
}

/**
 * ISO 8601 → **Unix 秒**
 *
 * ⛔ 刻意不用 `java.time`（`Instant.parse` / `OffsetDateTime` 都要 API 26，
 * 而本项目 `minSdk = 22`，电视是 Android 5.1.1）。用 `SimpleDateFormat` 手工归一化。
 *
 * Jellyfin 的 `DateCreated` 形如：
 * - `2026-09-23T10:11:12.3456789Z`
 * - `2026-09-23T10:11:12Z`
 * - `2026-09-23T10:11:12.3456789+08:00`（带偏移，罕见）
 *
 * 处理：截到秒（去掉小数与 `Z`），按 UTC 解析。
 * ⚠️ 带偏移的写法会丢掉偏移量 —— 对本用途（仅用于排序 / 指纹）足够，
 * 且 Jellyfin 默认返回 UTC `Z`。
 */
internal fun isoToEpochSeconds(iso: String?): Long {
    if (iso.isNullOrBlank()) return 0L
    return runCatching {
        val core = iso.substringBefore('.').substringBefore('Z').trim()
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        (fmt.parse(core)?.time ?: 0L) / 1000L
    }.getOrDefault(0L)
}

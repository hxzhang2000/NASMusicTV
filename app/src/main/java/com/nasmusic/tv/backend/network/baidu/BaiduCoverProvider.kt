package com.nasmusic.tv.backend.network.baidu

import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Base64

/**
 * 百度网盘封面获取
 *
 * 优先级链：
 * 1. 侧车封面图（同目录 cover.jpg / folder.jpg / {album}.jpg）→ 返回 dlink
 * 2. 内嵌 ID3 APIC 帧封面 → Range 请求文件头部，返回 data: URI（Coil 可直接加载）
 * 3. 返回 null → 上层走网络封面匹配
 *
 * ⚠️ 百度 dlink 返回的图片 URL 也需 `User-Agent: pan.baidu.com`，Coil 默认 UA 会 403。
 * 解决：[com.nasmusic.tv.NasMusicApp] 实现 coil.ImageLoaderFactory，OkHttpClient 注入百度 UA 拦截器
 * （BaiduHttpDataSourceFactory.createOkHttpClientForCoil）。
 */
class BaiduCoverProvider(
    private val api: BaiduPanApi,
    private val client: OkHttpClient,
    private val oauth: BaiduOAuthClient
) {

    suspend fun getCover(fsId: Long, title: String, artist: String?, path: String?): String? =
        withContext(Dispatchers.IO) {
            // 1. 侧车封面
            path?.let { findSidecarCover(it) }?.let { return@withContext it }
            // 2. 内嵌 APIC
            extractEmbeddedCover(fsId)?.let { return@withContext it }
            // 3. 上层 fallback
            null
        }

    /** 仅找同目录侧车封面图（不取 APIC）。专辑级封面链路第 1 步用。 */
    suspend fun findSidecarCoverOnly(songPath: String?): String? =
        if (songPath.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            findSidecarCover(songPath)
        }

    /** 仅取内嵌 ID3 APIC 帧封面（不找侧车）。专辑级封面链路第 4 步（取第一首 baidu 歌）用。 */
    suspend fun extractApicOnly(fsId: Long?): String? =
        if (fsId == null) null else withContext(Dispatchers.IO) {
            extractEmbeddedCover(fsId)
        }

    private suspend fun findSidecarCover(songPath: String): String? {
        val parentDir = songPath.substringBeforeLast('/').ifEmpty { "/" }
        val dirResult = api.listDir(parentDir, limit = BaiduNetdiskConfig.PAGE_SIZE)
        val coverFile = dirResult.files.firstOrNull {
            !it.isDir &&
                it.category == BaiduNetdiskConfig.CATEGORY_IMAGE &&
                it.serverFilename.substringBeforeLast('.').lowercase() in SIDE_CAR_NAMES
        } ?: return null
        // filemetas 拿 dlink（带 access_token 补齐由 StreamFactory 完成，这里直接用 dlink+token）
        val metas = api.fileMetas(listOf(coverFile.fsId))
        val dlink = metas.firstOrNull()?.dlink ?: return null
        // dlink 需补 access_token（与音频流一致）；Coil 的 OkHttpClient 会带 UA
        return ensureAccessToken(dlink)
    }

    /** 内嵌 APIC：Range 请求文件头部，解析 APIC 帧，返回 data: URI */
    private suspend fun extractEmbeddedCover(fsId: Long): String? {
        val metas = api.fileMetas(listOf(fsId))
        val meta = metas.firstOrNull()
        val dlink = meta?.dlink ?: return null
        val fileSize = meta.size

        // 1. 先读探测窗口（覆盖绝大多数 < 256KB 的 ID3 标签，常规情况只发 1 次请求）
        val probe = downloadRange(dlink, 0L, (ID3_PROBE_BYTES - 1).toLong()) ?: return null
        val tagTotal = Id3v2Parser.tagTotalSize(probe) ?: return null

        // 2. 标签超出探测窗口（大 APIC 封面）→ 按 ID3 标签总长补读，
        //    否则整帧会被解析器丢弃，导致「有内嵌封面却回退网络封面」
        val tagBytes = if (tagTotal <= ID3_PROBE_BYTES) {
            probe
        } else {
            val wantEnd = (tagTotal.toLong() - 1).coerceAtMost(MAX_ID3_TAG_BYTES)
            val safeEnd = if (fileSize > 0) wantEnd.coerceAtMost(fileSize - 1) else wantEnd
            AppLog.d(TAG, "extractEmbeddedCover: tagTotal=$tagTotal > probe, re-read bytes 0-$safeEnd")
            downloadRange(dlink, 0L, safeEnd) ?: return null
        }

        val (mime, picBytes) = Id3v2Parser.findApic(tagBytes) ?: return null
        val b64 = Base64.encodeToString(picBytes, Base64.NO_WRAP)
        val dataMime = if (mime.isBlank()) "image/jpeg" else mime
        return "data:$dataMime;base64,$b64"
    }

    /**
     * dlink 可能不含 access_token，需手动补（与 BaiduStreamFactory.resolveStreamUrl 一致）
     *
     * 修复：此前只拼了**空的** `access_token=`（从未取 token），导致侧车封面 dlink 一律 403。
     * 现改为真正调用 [BaiduOAuthClient.getValidAccessToken] 并 URL 编码；
     * 取不到 token 时返回 null，交由上层继续走内嵌 APIC / 网络封面 fallback。
     */
    private suspend fun ensureAccessToken(dlink: String): String? {
        if (dlink.contains("access_token=")) return dlink
        val token = oauth.getValidAccessToken() ?: run {
            AppLog.w(TAG, "ensureAccessToken: access_token 不可用，侧车封面 dlink 无法访问")
            return null
        }
        return dlink + (if (dlink.contains('?')) "&" else "?") +
            "access_token=" + java.net.URLEncoder.encode(token, "UTF-8")
    }

    private fun downloadRange(url: String, start: Long, end: Long): ByteArray? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", BaiduNetdiskConfig.BAIDU_UA)
                .header("Referer", BaiduNetdiskConfig.BAIDU_REFERER)
                .header("Range", "bytes=$start-$end")
                .build()
            client.newCall(req).execute().use { resp ->
                // 修复（M-14d）：只接受 206——服务器忽略 Range 返回 200 时，
                // body.bytes() 会把整文件读入内存（音频可上百 MB，OOM 风险）
                if (resp.code != 206) {
                    AppLog.w(TAG, "downloadRange: non-206 (code=${resp.code}), skip")
                    return null
                }
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "downloadRange error", e)
            null
        }
    }

    companion object {
        private const val TAG = "BaiduCover"
        /** 探测窗口：先读这么多字节；标签超过则按 tagTotalSize 补读 */
        private const val ID3_PROBE_BYTES = 256 * 1024
        /** 单次补读上限（避免异常/恶意超长 ID3 标签把内存打爆） */
        private const val MAX_ID3_TAG_BYTES = 16L * 1024 * 1024
        private val SIDE_CAR_NAMES = setOf("cover", "folder", "album", "front") // 2026-09-22 审查：移除 "cover.jpg" 死条目（比对前已 substringBeforeLast(.), 永不命中）
    }
}

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
    private val client: OkHttpClient
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
        val dlink = metas.firstOrNull()?.dlink ?: return null
        val headerBytes = downloadRange(dlink, 0L, (ID3_HEADER_BYTES - 1).toLong()) ?: return null
        val (mime, picBytes) = Id3v2Parser.findApic(headerBytes) ?: return null
        val b64 = Base64.encodeToString(picBytes, Base64.NO_WRAP)
        val dataMime = if (mime.isBlank()) "image/jpeg" else mime
        return "data:$dataMime;base64,$b64"
    }

    /** dlink 可能不含 access_token，需手动补（与 BaiduStreamFactory.resolveStreamUrl 一致） */
    private suspend fun ensureAccessToken(dlink: String): String {
        return if (dlink.contains("access_token=")) dlink
        else dlink + (if (dlink.contains('?')) "&" else "?") + "access_token="
    }

    private fun downloadRange(url: String, start: Long, end: Long): ByteArray? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", BaiduNetdiskConfig.BAIDU_UA)
                .header("Referer", BaiduNetdiskConfig.BAIDU_REFERER)
                .header("Range", "bytes=$start-$end")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in setOf(200, 206)) {
                    AppLog.w(TAG, "downloadRange failed code=${resp.code}")
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
        private const val ID3_HEADER_BYTES = 256 * 1024
        private val SIDE_CAR_NAMES = setOf("cover", "folder", "album", "front", "cover.jpg")
    }
}

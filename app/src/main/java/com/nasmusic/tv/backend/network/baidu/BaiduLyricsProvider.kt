package com.nasmusic.tv.backend.network.baidu

import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 百度网盘歌词获取
 *
 * 优先级链：
 * 1. 侧车 .lrc 文件（同目录、同名 .lrc）→ 用 dlink 下载 LRC 文本
 * 2. 内嵌 ID3 USLT 歌词帧 → Range 请求文件头部前 256KB，解析 USLT
 * 3. 返回 null → 上层 [com.nasmusic.tv.lyrics.LyricsManager] 走网络匹配 fallback
 */
class BaiduLyricsProvider(
    private val api: BaiduPanApi,
    private val client: OkHttpClient
) {

    /**
     * @param fsId  歌曲 fs_id
     * @param title 歌曲标题（备用）
     * @param artist 艺术家（备用）
     * @param path  网盘文件路径（查同目录侧车用）
     */
    suspend fun getLyrics(fsId: Long, title: String, artist: String?, path: String?): String? =
        withContext(Dispatchers.IO) {
            // 1. 侧车 LRC
            path?.let { findSidecarLrc(it) }?.let { return@withContext it }
            // 2. 内嵌 ID3 USLT
            extractEmbeddedLyrics(fsId)?.let { return@withContext it }
            // 3. 上层 fallback
            null
        }

    /** 查找同目录同名 .lrc 文件并下载文本 */
    private suspend fun findSidecarLrc(songPath: String): String? {
        val parentDir = songPath.substringBeforeLast('/').ifEmpty { "/" }
        val basename = songPath.substringAfterLast('/').substringBeforeLast('.')
        val dirResult = api.listDir(parentDir, limit = BaiduNetdiskConfig.PAGE_SIZE)
        val lrcFile = dirResult.files.firstOrNull {
            !it.isDir &&
                it.serverFilename.substringBeforeLast('.').equals(basename, ignoreCase = true) &&
                it.serverFilename.endsWith(".lrc", true)
        } ?: return null
        // 用 filemetas 拿 dlink，再下载文本
        val metas = api.fileMetas(listOf(lrcFile.fsId))
        val dlink = metas.firstOrNull()?.dlink ?: return null
        return downloadText(dlink)
    }

    /** Range 请求音频文件头部前 256KB，解析 ID3v2 USLT 帧 */
    private suspend fun extractEmbeddedLyrics(fsId: Long): String? {
        val metas = api.fileMetas(listOf(fsId))
        val dlink = metas.firstOrNull()?.dlink ?: return null
        val headerBytes = downloadRange(dlink, 0L, (ID3_HEADER_BYTES - 1).toLong()) ?: return null
        return Id3v2Parser.findUslt(headerBytes)
    }

    private fun downloadText(url: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", BaiduNetdiskConfig.BAIDU_UA)
                .header("Referer", BaiduNetdiskConfig.BAIDU_REFERER)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.w(TAG, "downloadText failed code=${resp.code}")
                    return null
                }
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "downloadText error", e)
            null
        }
    }

    /** Range 请求 [start, end] 字节范围 */
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
        private const val TAG = "BaiduLyrics"
        /** ID3v2 头部读取字节数（通常足够拿 USLT 帧，256KB） */
        private const val ID3_HEADER_BYTES = 256 * 1024
    }
}

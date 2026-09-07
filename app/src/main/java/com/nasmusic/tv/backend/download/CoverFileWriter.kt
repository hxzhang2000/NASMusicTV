package com.nasmusic.tv.backend.download

import com.nasmusic.tv.backend.local.ArtistCoverResolver
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.backend.local.ItunesCoverSearcher
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * 封面文件落盘（见方案 §6.5）
 *
 * - [writeArtistCover]：`artist.jpg` 落到歌手目录（已存在不覆盖）
 * - [writeAlbumCover]：`cover.jpg` 落到专辑目录（已存在不覆盖）
 *
 * 单曲级封面获取链（与批量 [AlbumCoverResolver.resolveCovers] 不同）：
 * - album cover：① song.coverUrl ② [NetworkMusicManager.searchCoverUrl] ③ [ItunesCoverSearcher.searchTrack]
 * - artist cover：① 新增 [ArtistCoverResolver.resolveArtistCoverUrl]（暴露现有私有的网易/酷狗/iTunes 链）
 *
 * 注意：URL → 字节流走 [fetchBytes]，失败返回 null 不阻断下载。
 */
class CoverFileWriter(
    private val client: OkHttpClient,
    private val network: NetworkMusicManager,
    private val itunes: ItunesCoverSearcher,
    private val artistCoverResolver: ArtistCoverResolver? = null
) {
    companion object {
        private const val TAG = "CoverFileWriter"
        const val ARTIST_COVER = "artist.jpg"
        const val ALBUM_COVER = "cover.jpg"
    }

    /**
     * 写 `cover.jpg` 到专辑目录（已存在不覆盖）。
     * @return 压缩后的 JPEG 字节流（供 [MediaTagWriter.embed] 内嵌使用，与 cover.jpg 同一份）；失败 null
     */
    suspend fun writeAlbumCover(albumDir: File, song: Song): ByteArray? =
        withContext(Dispatchers.IO) {
            val target = File(albumDir, ALBUM_COVER)
            if (target.exists()) {
                // 已有 cover.jpg：读出来供内嵌（如果可读）
                return@withContext runCatching { target.readBytes() }.getOrNull()
            }
            albumDir.mkdirs()
            val url = resolveAlbumCoverUrl(song) ?: return@withContext null
            val raw = fetchBytes(url) ?: return@withContext null
            val compressed = MediaTagWriter.compressCover(raw) ?: return@withContext null
            runCatching {
                FileOutputStream(target).use { it.write(compressed) }
            }.onFailure {
                AppLog.w(TAG, "writeAlbumCover failed: ${it.message}")
            }
            compressed
        }

    /**
     * 写 `artist.jpg` 到歌手目录（已存在不覆盖）。
     *
     * 歌手封面 URL 来源：暴露 [ArtistCoverResolver] 现有私有的网易/酷狗/iTunes 链，
     * 通过新增的 [ArtistCoverResolver.resolveArtistCoverUrl] 方法访问。
     */
    suspend fun writeArtistCover(artistDir: File, song: Song): Boolean =
        withContext(Dispatchers.IO) {
            val target = File(artistDir, ARTIST_COVER)
            if (target.exists()) return@withContext true
            artistDir.mkdirs()
            val artistName = com.nasmusic.tv.util.ArtistSplitter.split(song.artist).firstOrNull()
                ?: song.artist
            if (artistName.isBlank()) return@withContext false
            val url = artistCoverResolver?.resolveArtistCoverUrl(artistName)
                ?: song.coverUrl      // 兜底用歌曲封面作为歌手封面
                ?: return@withContext false
            val raw = fetchBytes(url) ?: return@withContext false
            val compressed = MediaTagWriter.compressCover(raw) ?: return@withContext false
            runCatching {
                FileOutputStream(target).use { it.write(compressed) }
            }.onFailure {
                AppLog.w(TAG, "writeArtistCover failed: ${it.message}")
                return@withContext false
            }
            true
        }

    private suspend fun resolveAlbumCoverUrl(song: Song): String? {
        song.coverUrl?.let { return it }
        try {
            network.searchCoverUrl(song.title, song.artist)?.let { return it }
        } catch (e: Exception) {
            AppLog.w(TAG, "network cover failed: ${e.message}")
        }
        try {
            itunes.searchTrack(song.title, song.artist)?.let { return it }
        } catch (e: Exception) {
            AppLog.w(TAG, "itunes cover failed: ${e.message}")
        }
        return null
    }

    private fun fetchBytes(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(url)
            .header("User-Agent", "NASMusicTV/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.bytes()
        }
    }.getOrNull()
}

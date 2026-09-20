package com.nasmusic.tv.backend.playlist

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.nasmusic.tv.data.model.PlaylistImportHistoryItem
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.AppPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

/**
 * 歌单导入编排器（docs/archive/playlist-import-feature-plan.md §4.2）。
 *
 * 职责：读流（不落盘）→ 嗅探选解析器 → 取名兜底 → 建歌单 → 去重入库 →
 * 返回 [ImportSummary]；成功后记录导入历史（§4.7.2）。
 *
 * URL 直链（§4.1.8 (2)）：HTTP / file:// / 绝对路径直接落地 [Song.streamUrl]
 * （isNetworkSong=false → [com.nasmusic.tv.data.prefs.AppPreferences] 的
 * stripVolatileStreamUrl 不清理本地歌曲），可立即播放；可达性由外部
 * [UrlReachabilityChecker] 在导入后后台批量测。
 */
class PlaylistImporter(
    private val app: Application,
    private val prefs: AppPreferences,
    @Suppress("unused") private val scope: CoroutineScope,
) {
    data class ImportSummary(
        val playlistId: String,
        val playlistName: String,
        val imported: Int,         // 实际入库条目数
        val skipped: Int,          // 文件内重复/无效条目/不支持路径
        val unrecognizedFormat: Boolean = false,
    )

    private val parsers: List<PlaylistParser> = listOf(
        M3uPlaylistParser(),
        NeteaseCloudPlaylistParser(),
        JsonPlaylistParser(),
        TextPlaylistParser(),
    )

    /**
     * 从 SAF Uri 导入（§4.2 源文件生命周期：只读流解析，不落盘，用完即关）。
     *
     * @param userConfirmedName 用户输入的歌单名（可空→走取名兜底链）
     * @param onProgress 进度回调（0/25/50/75/100，100 意味着解析+入库全完成）
     */
    suspend fun import(
        uri: Uri,
        userConfirmedName: String? = null,
        onProgress: (Int) -> Unit = {},
    ): ImportSummary {
        val fileName = queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
        val bytes = runCatching {
            app.contentResolver.openInputStream(uri)?.use { readLimited(it) }
        }.getOrNull() ?: return ImportSummary("", "", 0, 0, unrecognizedFormat = true)
        return importBytes(bytes, fileName, userConfirmedName, onProgress)
    }

    /** 核心：字节 → 解析 → 入库。独立暴露便于单测（绕过 Uri）。 */
    suspend fun importBytes(
        bytes: ByteArray,
        fileName: String,
        userConfirmedName: String? = null,
        onProgress: (Int) -> Unit = {},
    ): ImportSummary {
        onProgress(0)
        if (bytes.isEmpty()) return ImportSummary("", "", 0, 0, unrecognizedFormat = true)

        val text = PlaylistParsers.decode(bytes)
        val head = bytes.copyOfRange(0, minOf(bytes.size, PlaylistParsers.SNIFF_BYTES))

        // 嗅探选解析器（顺序即优先级；canParse 各自基于首 4KB/扩展名）
        val parser = parsers.firstOrNull { it.canParse(head, fileName) }
            ?: return ImportSummary("", "", 0, 0, unrecognizedFormat = true)

        val entries = parser.parse(text, fileName)
        val innerName = (parser as? PlaylistNameProvider)?.innerName

        val name = resolvePlaylistName(fileName, innerName, userConfirmedName)
        val playlist = prefs.createLocalPlaylist(name)

        // 文件内去重 + RELATIVE_PATH 不支持映射计 skipped（§4.1.8 (1)）
        val seen = HashSet<String>()
        var imported = 0
        var skipped = 0
        var lastReported = -1
        val total = maxOf(entries.size, 1)
        entries.forEach { raw ->
            if (raw.directUrlType == DirectUrlType.RELATIVE_PATH) {
                skipped++
            } else {
                val key = PlaylistParsers.normalizeKey(raw.title, raw.artist)
                if (!seen.add(key)) {
                    skipped++
                } else if (prefs.addSongToPlaylist(playlist.id, toBareSong(raw))) {
                    // stripVolatileStreamUrl 在 addSongToPlaylist 内触发，URL 直链安全持久化
                    imported++
                } else {
                    skipped++   // 歌单不存在/异常 → 计无效
                }
            }
            // 进度：每跨过 25% 步进回调一次（0/25/50/75/100）
            val pct = ((imported + skipped) * 100 / total).coerceIn(0, 100) / 25 * 25
            if (pct != lastReported) {
                lastReported = pct
                onProgress(pct)
            }
        }
        if (lastReported != 100) onProgress(100)

        // 记录导入历史（§4.7.2；重导入同歌单替换旧记录）
        prefs.recordPlaylistImport(
            PlaylistImportHistoryItem(
                playlistId = playlist.id,
                playlistName = playlist.name,
                importedAt = System.currentTimeMillis(),
                importedCount = imported,
            )
        )
        return ImportSummary(
            playlistId = playlist.id,
            playlistName = playlist.name,
            imported = imported,
            skipped = skipped,
        )
    }

    /** SAF 文件名：优先 DISPLAY_NAME，回退 path 末段。 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** 限量读取（≤ MAX_FILE_BYTES），防超大文件 OOM（§4.1.7 (3) 截断语义）。 */
    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < PlaylistParsers.MAX_FILE_BYTES) {
            val n = input.read(buf)
            if (n < 0) break
            val take = minOf(n, PlaylistParsers.MAX_FILE_BYTES - total)
            out.write(buf, 0, take)
            total += take
            if (take < n) break
        }
        return out.toByteArray()
    }

    /**
     * 歌单名兜底（§4.2）：userInput → innerName → 文件名（不在无意义白名单）→ 默认名。
     * 不做弹窗：名字取不到时直接沿用默认「导入的歌单 MM-dd HH:mm」。
     */
    fun resolvePlaylistName(
        fileName: String,       // "我的歌单.m3u" / "新建文本文档.txt"
        innerName: String?,     // #PLAYLIST / JSON name / txt 首行注释
        userInput: String?,     // 用户输入框（可空）
    ): String {
        userInput?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        innerName?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        if (fileName.isNotBlank() && !PlaylistParsers.isMeaninglessFileName(fileName)) {
            val base = PlaylistParsers.baseNameOf(fileName)
            if (base.isNotEmpty()) return base
        }
        return "导入的歌单 ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())}"
    }

    /**
     * 裸 Song 构造（§4.1.8 (2)）：
     * - HTTP URL：直接落地 streamUrl（isNetworkSong=false，可安全持久化）
     * - file:// / 绝对路径：同样直接落地 streamUrl
     * - 其他：不含 streamUrl，走补全链
     */
    private fun toBareSong(raw: RawSongEntry): Song {
        val stubId = PlaylistParsers.IMPORTED_ID_PREFIX + UUID.randomUUID()
        return when (raw.directUrlType) {
            DirectUrlType.HTTP -> Song(
                id = stubId,
                title = raw.title.trim(),
                artist = raw.artist.trim(),
                album = raw.album?.trim().orEmpty(),
                durationMs = raw.durationSec?.times(1000L) ?: 0L,
                streamUrl = raw.directUrl,     // ★ 关键：HTTP URL 直接落地
                isNetworkSong = false,         // 不走 networkMusicManager.resolvePlayUrl
            )
            DirectUrlType.LOCAL_URI, DirectUrlType.ABSOLUTE_PATH -> Song(
                id = stubId,
                title = raw.title.trim(),
                artist = raw.artist.trim(),
                album = raw.album?.trim().orEmpty(),
                durationMs = raw.durationSec?.times(1000L) ?: 0L,
                streamUrl = raw.directUrl,     // file:// / 绝对路径直接可播
            )
            else -> Song(
                id = stubId,
                title = raw.title.trim(),
                artist = raw.artist.trim(),
                album = raw.album?.trim().orEmpty(),
                durationMs = raw.durationSec?.times(1000L) ?: 0L,
                isNetworkSong = false,
                isLocalSong = false,
            )
        }
    }
}
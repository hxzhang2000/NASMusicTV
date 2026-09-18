package com.nasmusic.tv.backend.download

import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.ArtistSplitter
import java.io.File

/**
 * 下载目录与文件名构造器
 *
 * 规则（见方案 §4.3）：
 * - 主艺术家：[ArtistSplitter.split] 取第一个；空 → "未知歌手"
 * - 专辑空 → "单曲"
 * - 标题空 → "未命名"
 * - 非法字符 `[\/:*?"<>\|\x00-\x1F]` → `_`
 * - 连续空白折叠；首尾 trim；去掉结尾 `.` 与空格
 * - 单段 ≤ 80 字符
 * - 曲目号 > 0 → `%02d - ` 前缀
 * - 同目录已存在非本索引文件 → ` (2)`、` (3)` 后缀
 *
 * 路径结构：`<root>/<艺术家>/<专辑>/<曲目号 - 标题>.<ext>`
 * 临时文件：`<root>/.tmp/<finalName>.part`（与最终文件同分区，原子 rename）
 */
class DownloadPathBuilder(private val rootProvider: () -> File) {

    data class Paths(
        val artistDir: File,
        val albumDir: File,
        val baseName: String,       // 不含扩展名，旁路文件用
        val tmpFile: File,          // .tmp/<finalName>.part
        val finalFile: File
    )

    fun build(song: Song, ext: String): Paths {
        val root = rootProvider()
        val artistRaw = ArtistSplitter.split(song.artist).firstOrNull() ?: song.artist
        val artist = sanitize(artistRaw).ifBlank { "未知歌手" }
        val album = sanitize(song.album).ifBlank { DEFAULT_ALBUM }
        val title = sanitize(song.title).ifBlank { "未命名" }

        val artistDir = File(root, artist)
        val albumDir = File(artistDir, album)
        val baseName = if (song.trackNumber > 0)
            "%02d - %s".format(song.trackNumber, title)
        else
            title
        val finalFile = uniqueFile(albumDir, baseName, ext)

        val tmpDir = File(root, TMP_DIR).apply { mkdirs() }
        // 确保 .tmp 不被媒体扫描器读到
        File(tmpDir, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        val tmpFile = File(tmpDir, "${finalFile.name}.part")

        return Paths(artistDir, albumDir, finalFile.nameWithoutExtension, tmpFile, finalFile)
    }

    /** 仅构造最终文件路径（不创建目录，不创建临时文件），供 reindex 与歌曲行使用 */
    fun buildFinalPath(song: Song, ext: String): File {
        val root = rootProvider()
        val artistRaw = ArtistSplitter.split(song.artist).firstOrNull() ?: song.artist
        val artist = sanitize(artistRaw).ifBlank { "未知歌手" }
        val album = sanitize(song.album).ifBlank { DEFAULT_ALBUM }
        val title = sanitize(song.title).ifBlank { "未命名" }
        val baseName = if (song.trackNumber > 0)
            "%02d - %s".format(song.trackNumber, title)
        else
            title
        return uniqueFile(File(root, "$artist/$album"), baseName, ext)
    }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var candidate = File(dir, "$base.$ext")
        var i = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i).$ext")
            i++
        }
        return candidate
    }

    /**
     * 删除歌曲后回收空的专辑/歌手目录（避免遗留大量空目录污染用户视图）。
     *
     * 从 [albumDir] 向上回溯：目录为空且非根 → 删除。
     */
    fun cleanupEmptyDirs(artistDir: File, albumDir: File) {
        if (albumDir.exists() && albumDir.isDirectory && albumDir.listFiles()?.isEmpty() == true) {
            albumDir.delete()
        }
        if (artistDir.exists() && artistDir.isDirectory && artistDir.listFiles()?.isEmpty() == true) {
            artistDir.delete()
        }
    }

    /**
     * 从 URL 路径或 Content-Type 推断扩展名
     *
     * @param quality v2.35.0 多码率：**实际解析命中的档位**
     *        （`ResolveResult.actualQuality`），非请求档位。
     *        - 无损（999）→ 强制 `flac`（无损直链 URL 常无扩展名，
     *          靠 URL 后缀猜会把 FLAC 存成 `.mp3` 容器）
     *        - AUTO（0）→ 沿用旧逻辑（URL 后缀 → song.path 后缀 → mp3）
     *        - 其他具体档位 → `mp3`
     */
    fun extOf(url: String, song: Song, quality: Int = QualityTiers.AUTO): String {
        if (quality == QualityTiers.LOSSLESS) return "flac"
        if (quality != QualityTiers.AUTO) return "mp3"
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        if (fromUrl in AUDIO_EXTS) return fromUrl
        // 网络歌曲默认 mp3
        return when {
            song.path?.endsWith(".flac", true) == true -> "flac"
            song.path?.endsWith(".m4a", true) == true -> "m4a"
            song.path?.endsWith(".wav", true) == true -> "wav"
            song.path?.endsWith(".ogg", true) == true -> "ogg"
            else -> "mp3"
        }
    }

    /**
     * 文件名基名（不含扩展名）：非无损档追加档位后缀，无损档不加（多码率方案 §4.3）。
     *
     * @param quality **实际解析命中的档位**（`ResolveResult.actualQuality`），非请求档位。
     *        降级场景：请求无损、实际拿到 320 → `03 - 南方姑娘 (320)`，
     *        与文件名后缀、实际音频内容三者一致。
     *
     * 后缀规则：
     * - 999 无损 → 不加后缀（扩展名 .flac 已足以区分）
     * - 128/192/320 → ` (128)` / ` (192)` / ` (320)`
     * - 0 AUTO / 本地 / NAS / 网盘 → 不加后缀（无档位概念）
     */
    fun baseNameWithQuality(song: Song, quality: Int): String {
        val title = sanitize(song.title).ifBlank { "未命名" }
        val base = if (song.trackNumber > 0) "%02d - %s".format(song.trackNumber, title) else title
        val suffix = when (quality) {
            QualityTiers.STANDARD -> " (128)"
            QualityTiers.GOOD -> " (192)"
            QualityTiers.HIGH -> " (320)"
            else -> ""
        }
        return base + suffix
    }

    /**
     * 构造下载路径（带档位后缀版本，多码率方案 §4.3）。
     *
     * 与 [build] 的区别：基名经 [baseNameWithQuality] 处理，非无损档带档位后缀。
     *
     * @param quality 实际解析命中的档位
     */
    fun build(song: Song, ext: String, quality: Int): Paths {
        val root = rootProvider()
        val artistRaw = ArtistSplitter.split(song.artist).firstOrNull() ?: song.artist
        val artist = sanitize(artistRaw).ifBlank { "未知歌手" }
        val album = sanitize(song.album).ifBlank { DEFAULT_ALBUM }

        val artistDir = File(root, artist)
        val albumDir = File(artistDir, album)
        val baseName = baseNameWithQuality(song, quality)
        val finalFile = uniqueFile(albumDir, baseName, ext)

        val tmpDir = File(root, TMP_DIR).apply { mkdirs() }
        File(tmpDir, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        val tmpFile = File(tmpDir, "${finalFile.name}.part")

        return Paths(artistDir, albumDir, finalFile.nameWithoutExtension, tmpFile, finalFile)
    }

    companion object {
        private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
        private const val MAX_SEGMENT = 80
        const val TMP_DIR = ".tmp"
        const val DEFAULT_ALBUM = "单曲"
        val AUDIO_EXTS = setOf("mp3", "flac", "m4a", "mp4", "aac", "ogg", "opus", "wav")

        /** 清洗目录/文件名段：去非法字符、折叠空白、首尾 trim、80 字符截断 */
        fun sanitize(raw: String): String =
            ILLEGAL.replace(raw, "_")
                .replace(Regex("\\s+"), " ")
                .trim().trimEnd('.', ' ')
                .let { if (it.length > MAX_SEGMENT) it.take(MAX_SEGMENT).trimEnd() else it }
    }
}

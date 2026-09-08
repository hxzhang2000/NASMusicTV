package com.nasmusic.tv.lyrics

import com.nasmusic.tv.backend.network.baidu.Id3v2Parser
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * 本地歌词提供器
 *
 * 针对本地音乐（isLocalSong=true），按优先级获取歌词：
 * 1. 同目录同名 .lrc 文件（侧车歌词）
 * 2. 音乐文件内嵌歌词（ID3v2 USLT 帧）
 *
 * 编码处理：优先 UTF-8，若出现乱码回退 GBK（中文歌曲 ID3 常用 GBK 编码）。
 */
object LocalLyricsProvider {

    private const val TAG = "LocalLyrics"
    /** ID3v2 头部读取字节数（256KB 足够覆盖 USLT 帧） */
    private const val ID3_HEADER_SIZE = 256 * 1024

    /**
     * 查找歌曲同目录下的同名 .lrc 文件
     *
     * @param song 本地歌曲（path 为文件绝对路径或 file:// URI）
     * @return 找到的 .lrc 文件，未找到返回 null
     */
    fun findLrcFile(song: Song): File? {
        val audioPath = song.path ?: return null
        // 处理 file:// URI 前缀
        val realPath = audioPath.removePrefix("file://").removePrefix("content://")
        val audioFile = File(realPath)
        val parent = audioFile.parentFile ?: return null
        val baseName = audioFile.nameWithoutExtension
        return File(parent, "$baseName.lrc").takeIf { it.exists() && it.isFile }
    }

    /**
     * 读取 .lrc 文件内容（UTF-8 优先，GBK 回退）
     */
    fun readLrc(lrcFile: File): String? {
        val bytes = try {
            lrcFile.readBytes()
        } catch (e: Exception) {
            AppLog.w(TAG, "read bytes failed: ${e.message}")
            return null
        }
        // 先按 UTF-8 解码，检查是否含替换字符（乱码特征）
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) {
            // 有乱码 → 尝试 GBK
            try {
                bytes.toString(Charset.forName("GBK"))
            } catch (e: Exception) {
                utf8
            }
        } else {
            utf8
        }
    }

    /**
     * 获取本地歌曲的侧车 LRC 歌词（同目录同名 .lrc 文件）
     *
     * @param song 本地歌曲
     * @return LRC 文本，未找到或无效返回 null
     */
    fun getSidecarLyrics(song: Song): String? {
        if (!song.isLocalSong) return null
        val lrcFile = findLrcFile(song) ?: return null
        val text = readLrc(lrcFile) ?: return null
        if (!LrcParser.isValidLrc(text)) {
            AppLog.d(TAG, "sidecar LRC invalid: ${lrcFile.absolutePath}")
            return null
        }
        return text
    }

    /**
     * 获取本地歌曲的内嵌歌词（ID3v2 USLT 帧）
     *
     * @param song 本地歌曲
     * @return 歌词文本，无内嵌歌词返回 null
     */
    fun getEmbeddedLyrics(song: Song): String? {
        if (!song.isLocalSong) return null
        return extractEmbeddedLyrics(song)
    }

    /**
     * 获取本地歌曲的歌词（侧车 LRC 优先，fallback 内嵌）
     *
     * @param song 本地歌曲
     * @return 歌词文本，未找到返回 null
     */
    fun getLocalLyrics(song: Song): String? {
        return getSidecarLyrics(song) ?: getEmbeddedLyrics(song)
    }

    /**
     * 从音频文件路径获取歌词（不依赖 Song 对象，供已下载的非本地歌曲使用）
     *
     * 优先级：1. 内嵌 ID3v2 USLT 帧 → 2. 本地 sidecar .lrc 文件
     *
     * @param audioPath 音频文件绝对路径
     * @param lyricPath 显式的 .lrc 文件路径（旁路歌词），若已知可直接传入
     * @return LRC 文本，未找到返回 null
     */
    fun getLyricsFromPath(audioPath: String, lyricPath: String? = null): String? {
        val realPath = android.net.Uri.decode(audioPath.removePrefix("file://").removePrefix("content://"))
        val audioFile = File(realPath)
        if (!audioFile.exists() || !audioFile.isFile) return null

        // 1. 内嵌 ID3v2 USLT 帧（最高优先级）
        try {
            val headerSize = minOf(ID3_HEADER_SIZE.toLong(), audioFile.length())
            val headerBytes = ByteArray(headerSize.toInt())
            RandomAccessFile(audioFile, "r").use { raf -> raf.readFully(headerBytes) }
            val lyrics = Id3v2Parser.findUslt(headerBytes)
            if (!lyrics.isNullOrBlank() && LrcParser.isValidLrc(lyrics)) {
                AppLog.d(TAG, "getLyricsFromPath: embedded USLT hit")
                return lyrics
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "getLyricsFromPath embedded failed: ${e.message}")
        }

        // 2. 本地 sidecar .lrc 文件
        if (lyricPath != null) {
            val lrcFile = File(lyricPath)
            if (lrcFile.exists() && lrcFile.isFile) {
                readLrc(lrcFile)?.let { if (LrcParser.isValidLrc(it)) return it }
            }
        }

        // 2b. 同目录同名 .lrc（兜底）
        val parent = audioFile.parentFile
        val baseName = audioFile.nameWithoutExtension
        parent?.let {
            val sidecar = File(it, "$baseName.lrc")
            if (sidecar.exists() && sidecar.isFile) {
                readLrc(sidecar)?.let { if (LrcParser.isValidLrc(it)) return it }
            }
        }

        return null
    }

    /**
     * 从音频文件内嵌 ID3v2 元数据提取歌词（USLT 帧）
     *
     * 读取文件头部前 256KB 解析 ID3v2 帧，与百度网盘的 Id3v2Parser.findUslt() 复用同一逻辑。
     */
    private fun extractEmbeddedLyrics(song: Song): String? {
        val audioPath = song.path ?: return null
        val realPath = android.net.Uri.decode(audioPath.removePrefix("file://").removePrefix("content://"))
        val file = File(realPath)
        if (!file.exists() || !file.isFile) return null

        return try {
            // 读取文件头部 256KB（ID3v2 标签通常在此范围内）
            val headerSize = minOf(ID3_HEADER_SIZE.toLong(), file.length())
            val headerBytes = ByteArray(headerSize.toInt())
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(headerBytes)
            }
            val lyrics = Id3v2Parser.findUslt(headerBytes)
            if (!lyrics.isNullOrBlank()) {
                AppLog.d(TAG, "extracted embedded lyrics from: ${file.name}")
                lyrics
            } else {
                null
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "extract embedded lyrics failed: ${e.message}")
            null
        }
    }
}
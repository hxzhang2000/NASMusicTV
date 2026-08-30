package com.nasmusic.tv.lyrics

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import java.io.File
import java.nio.charset.Charset

/**
 * 本地 LRC 歌词提供器
 *
 * 针对本地音乐（isLocalSong=true），查找并读取歌曲同目录下的同名 .lrc 文件。
 * 规则：歌曲 /music/song.mp3 → 歌词 /music/song.lrc（同名同目录，扩展名 .lrc）。
 *
 * 编码处理：优先 UTF-8，若出现乱码回退 GBK（中文歌曲 ID3 常用 GBK 编码）。
 */
object LocalLyricsProvider {

    private const val TAG = "LocalLyrics"

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
     * 获取本地歌曲的同目录同名 LRC 歌词
     *
     * @param song 本地歌曲
     * @return LRC 文本，未找到或读取失败返回 null
     */
    fun getLocalLyrics(song: Song): String? {
        if (!song.isLocalSong) return null
        val lrcFile = findLrcFile(song) ?: return null
        val text = readLrc(lrcFile) ?: return null
        if (!LrcParser.isValidLrc(text)) {
            AppLog.d(TAG, "LRC invalid: ${lrcFile.absolutePath}")
            return null
        }
        return text
    }
}
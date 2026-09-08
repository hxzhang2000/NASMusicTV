package com.nasmusic.tv.backend.local

import com.nasmusic.tv.backend.network.baidu.Id3v2Parser
import com.nasmusic.tv.util.AppLog
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 从音频文件提取内嵌 APIC 封面，写入缓存目录供 Coil 加载。
 *
 * Coil 的 AsyncImage 无法直接从音频文件提取内嵌封面，
 * 需先用 [Id3v2Parser.findApic] 提取字节流，写入缓存文件，
 * 返回 "file://cache_path" 供 AsyncImage 加载。
 *
 * 使用 LRU 内存缓存避免重复提取同一文件。
 */
object EmbeddedCoverExtractor {
    private const val TAG = "EmbeddedCover"
    private const val ID3_HEADER_SIZE = 256 * 1024
    private const val MAX_CACHE_ENTRIES = 200

    // LRU: audioPath -> cacheFilePath
    private val cache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    /**
     * 从音频文件提取内嵌封面，返回可被 Coil 加载的 "file://" URI。
     *
     * @param audioPath 音频文件绝对路径
     * @param cacheDir 缓存目录（应用 cacheDir）
     * @return "file:///.../cover_cache_xxx.jpg" 或 null（无内嵌封面）
     */
    fun extractCoverUri(audioPath: String, cacheDir: File): String? {
        val realPath = audioPath.removePrefix("file://").removePrefix("content://")
        val audioFile = File(realPath)
        if (!audioFile.exists() || !audioFile.isFile) return null

        // 1. 内存缓存命中
        cache[realPath]?.let { cachedPath ->
            val cachedFile = File(cachedPath)
            if (cachedFile.exists()) return "file://$cachedPath"
            // 缓存文件被清理了，移除条目重新提取
            cache.remove(realPath)
        }

        // 2. 从音频文件提取 APIC 帧
        return try {
            val headerSize = minOf(ID3_HEADER_SIZE.toLong(), audioFile.length())
            val headerBytes = ByteArray(headerSize.toInt())
            RandomAccessFile(audioFile, "r").use { raf -> raf.readFully(headerBytes) }
            val apic = Id3v2Parser.findApic(headerBytes)
            if (apic == null) {
                AppLog.d(TAG, "no APIC frame in ${audioFile.name}")
                return null
            }

            val (_, pictureBytes) = apic
            // 写入缓存文件，用 audioPath 的 hash 做文件名避免冲突
            val hash = MessageDigest.getInstance("MD5")
                .digest(realPath.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val ext = if (apic.first.contains("png")) "png" else "jpg"
            val cacheFile = File(cacheDir, "cover_cache_$hash.$ext")
            cacheFile.writeBytes(pictureBytes)

            cache[realPath] = cacheFile.absolutePath
            AppLog.d(TAG, "extracted APIC cover: ${cacheFile.name} (${pictureBytes.size} bytes)")
            "file://${cacheFile.absolutePath}"
        } catch (e: Exception) {
            AppLog.w(TAG, "extractCoverUri failed: ${e.message}")
            null
        }
    }

    /** 清空内存缓存（不删文件，由系统清理 cacheDir） */
    fun clearMemoryCache() {
        cache.clear()
    }
}

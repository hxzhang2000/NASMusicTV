package com.nasmusic.tv.backend.local

import android.content.Context
import android.net.Uri
import com.nasmusic.tv.backend.network.baidu.Id3v2Parser
import com.nasmusic.tv.util.AppLog
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 从音频文件提取内嵌 APIC 封面，写入缓存目录供 Coil 加载。
 *
 * Coil 的 AsyncImage 无法直接从音频文件提取内嵌封面，
 * 需先用 [Id3v2Parser.findApic] 提取字节流，写入缓存文件，
 * 返回 "file://cache_path" 供 AsyncImage 加载。
 *
 * 支持两种来源：
 * - `content://` URI（MediaStore 扫描的本地歌曲）→ 通过 [Context.getContentResolver] 读取；
 * - `file://` / 裸路径（下载目录、USB）→ 直接以文件方式读取。
 *
 * 使用带同步保护的 LRU 内存缓存避免重复提取同一文件。
 */
object EmbeddedCoverExtractor {
    private const val TAG = "EmbeddedCover"
    private const val ID3_HEADER_SIZE = 256 * 1024
    private const val MAX_CACHE_ENTRIES = 200

    // LRU: cacheKey -> cacheFilePath（所有访问均在 lock 内，避免 LinkedHashMap 并发 CME）
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    /**
     * 从音频文件提取内嵌封面，返回可被 Coil 加载的 "file://" URI。
     *
     * @param audioPath 音频路径：content:// URI、file:// URI 或裸绝对路径
     * @param cacheDir 缓存目录（应用 cacheDir）
     * @param context 读取 content:// URI 时必需；仅文件路径时可传 null
     * @return "file:///.../cover_cache_xxx.jpg" 或 null（无内嵌封面）
     */
    fun extractCoverUri(audioPath: String, cacheDir: File, context: Context? = null): String? {
        val isContentUri = audioPath.startsWith("content://")
        // 修复：原实现对 content:// 做 removePrefix 后当文件路径用，导致 MediaStore 歌曲
        // 路径根本不存在 → 内嵌封面提取恒失败。现按 URI scheme 分流：content:// 走
        // ContentResolver，其余解码为文件路径。
        val cacheKey = if (isContentUri) audioPath else Uri.decode(audioPath.removePrefix("file://"))
        val audioFile: File? = if (isContentUri) null else File(cacheKey)
        if (!isContentUri && (audioFile == null || !audioFile.exists() || !audioFile.isFile)) return null

        // 1. 内存缓存命中
        synchronized(lock) {
            cache[cacheKey]?.let { cachedPath ->
                val cachedFile = File(cachedPath)
                if (cachedFile.exists()) {
                    AppLog.d(TAG, "extractCoverUri: cache hit")
                    return "file://$cachedPath"
                }
                cache.remove(cacheKey)
            }
        }

        // 2. 读取文件头（content:// 经 ContentResolver，文件路径直接读）
        return try {
            val headerBytes = readHeader(audioPath, cacheKey, audioFile, context) ?: return null
            AppLog.d(TAG, "extractCoverUri: read ${headerBytes.size} bytes, parsing APIC...")
            val apic = Id3v2Parser.findApic(headerBytes)
            if (apic == null) {
                AppLog.d(TAG, "extractCoverUri: no APIC frame in ${audioFile?.name ?: cacheKey}")
                return null
            }

            val (mime, pictureBytes) = apic
            AppLog.d(TAG, "extractCoverUri: APIC found, mime=$mime, size=${pictureBytes.size}")
            // 写入缓存文件，用路径/URI 的 hash 做文件名避免冲突
            val hash = MessageDigest.getInstance("MD5")
                .digest(cacheKey.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val ext = if (mime.contains("png")) "png" else "jpg"
            val cacheFile = File(cacheDir, "cover_cache_$hash.$ext")
            cacheFile.writeBytes(pictureBytes)

            synchronized(lock) { cache[cacheKey] = cacheFile.absolutePath }
            AppLog.d(TAG, "extracted APIC cover: ${cacheFile.name} (${pictureBytes.size} bytes)")
            "file://${cacheFile.absolutePath}"
        } catch (e: Exception) {
            AppLog.w(TAG, "extractCoverUri failed: ${e.message}")
            null
        }
    }

    /** 读取最多 [ID3_HEADER_SIZE] 字节的文件头，失败返回 null */
    private fun readHeader(
        audioPath: String,
        cacheKey: String,
        audioFile: File?,
        context: Context?
    ): ByteArray? {
        if (audioPath.startsWith("content://")) {
            val ctx = context ?: run {
                AppLog.w(TAG, "content:// cover extraction requires a Context; skipped ($cacheKey)")
                return null
            }
            return runCatching {
                ctx.contentResolver.openInputStream(Uri.parse(audioPath))?.use { input ->
                    readUpTo(input, ID3_HEADER_SIZE)
                }
            }.getOrNull()
        }
        val file = audioFile ?: return null
        // 2026-09-22 审查：256KB 固定窗口 → 智能读取（tagTotalSize 动态补读，
        // 大 APIC 封面不再恒提取失败）；content:// 分支保持 256KB 上限（流式
        // 无法二次定位，属平台限制）。
        return Id3v2Parser.readLocalHeaderSmart(file)
    }

    /** 从输入流读取至多 maxBytes 字节（不足则返回实际读到的部分） */
    private fun readUpTo(input: InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var remaining = maxBytes
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size, remaining))
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    /** 清空内存缓存（不删文件，由系统清理 cacheDir） */
    fun clearMemoryCache() {
        synchronized(lock) { cache.clear() }
    }
}

package com.nasmusic.tv.backend.network.baidu

import com.nasmusic.tv.util.AppLog

/**
 * 轻量 ID3v2 帧解析器（仅解析 USLT 歌词帧与 APIC 封面帧）
 *
 * 不引入 jaudiotagger-android 重型依赖。边角格式（非标准 ID3v2 帧、编码异常）不支持，
 * 解析失败返回 null，上层 fallback 到网络匹配（方案 §6.3.3/§6.3.4）。
 *
 * 支持 ID3v2.3 / ID3v2.4，文本编码支持 ISO-8859-1 / UTF-16 / UTF-8。
 */
object Id3v2Parser {

    private const val TAG = "Id3v2Parser"

    /** ID3v2 文本编码 */
    private fun decodeText(bytes: ByteArray, encoding: Int): String {
        return try {
            val charset = when (encoding) {
                0 -> Charsets.ISO_8859_1
                1 -> Charsets.UTF_16     // UTF-16 with BOM
                2 -> Charsets.UTF_16BE
                3 -> Charsets.UTF_8
                else -> Charsets.ISO_8859_1
            }
            // 去除尾部 BOM/零字节
            val s = String(bytes, charset)
            // 去除 ID3v2 中常见的尾部 null
            s.trimEnd('\u0000')
        } catch (e: Exception) {
            AppLog.w(TAG, "decodeText encoding=$encoding failed", e)
            ""
        }
    }

    /**
     * 在 ID3v2 数据（文件头部字节）中查找第一个 USLT 帧的歌词文本。
     * @param data 文件前 N 字节（建议前 256KB）
     * @return LRC 文本或纯文本歌词；未找到返回 null
     */
    fun findUslt(data: ByteArray): String? {
        val frames = parseFrames(data) ?: return null
        val uslt = frames.firstOrNull { it.id == "USLT" } ?: return null
        return parseUsltFrame(uslt.data)
    }

    /**
     * 在 ID3v2 数据中查找第一个 APIC 帧的封面图片字节。
     * @return (mime, pictureBytes)；未找到返回 null
     */
    fun findApic(data: ByteArray): Pair<String, ByteArray>? {
        val frames = parseFrames(data) ?: return null
        val apic = frames.firstOrNull { it.id == "APIC" } ?: return null
        return parseApicFrame(apic.data)
    }

    /**
     * 解析 ID3v2 标签总长度（含 10 字节头），用于按需读取整个标签。
     *
     * 背景（M5）：调用方原先固定只读文件前 256KB，若 APIC 封面帧起点位于其
     * 之后，[parseFrames] 会因 `pos + 10 + frameSize > tagEnd` 直接 break，
     * 整帧被丢弃 → 有内嵌封面却判为「无」。
     * @return 标签总字节数；无 ID3v2 标签 / v2.2 返回 null
     */
    /** 智能读取的探测窗口（覆盖绝大多数 < 256KB 的 ID3 标签，常规只读一次） */
    const val DEFAULT_PROBE_BYTES: Int = 256 * 1024

    /** 智能读取的标签总长上限（16MB，与 BaiduCoverProvider.MAX_ID3_TAG_BYTES 同口径） */
    const val DEFAULT_MAX_TAG_BYTES: Int = 16 * 1024 * 1024

    /**
     * 本地文件 ID3v2 头部智能读取（2026-09-22 审查：统一「256KB 固定窗口」遗留）。
     *
     * 先读探测窗口（覆盖绝大多数标签，常规只读一次），经 [tagTotalSize] 取标签
     * 总长；标签超出探测窗口（大 APIC/USLT 帧，常见于高清封面/双语文本）→ 按总长
     * 重读，上限 [maxTagBytes]。非 ID3v2 文件返回 null（调用方回退其他来源）。
     * 对齐 BaiduCoverProvider 的 M5 修复语义，供本地封面/歌词提取共用。
     */
    fun readLocalHeaderSmart(
        file: java.io.File,
        probeBytes: Int = DEFAULT_PROBE_BYTES,
        maxTagBytes: Int = DEFAULT_MAX_TAG_BYTES
    ): ByteArray? {
        return try {
            if (!file.exists() || file.length() <= 0L) return null
            java.io.RandomAccessFile(file, "r").use { raf ->
                val probeSize = minOf(probeBytes.toLong(), file.length()).toInt()
                val probe = ByteArray(probeSize)
                raf.readFully(probe)
                val tagTotal = tagTotalSize(probe) ?: return null
                if (tagTotal <= probeSize) return probe
                val want = tagTotal.coerceAtMost(maxTagBytes).coerceAtMost(file.length().toInt())
                val full = ByteArray(want)
                raf.seek(0)
                raf.readFully(full)
                full
            }
        } catch (e: Exception) {
            null
        }
    }

    fun tagTotalSize(data: ByteArray): Int? {
        if (data.size < 10) return null
        if (data[0] != 'I'.code.toByte() || data[1] != 'D'.code.toByte() || data[2] != '3'.code.toByte()) return null
        val versionMajor = data[3].toInt() and 0xFF
        if (versionMajor < 3) return null
        return 10 + synchsafeToInt(data, 6)
    }

    private data class RawFrame(val id: String, val data: ByteArray)

    private fun parseFrames(data: ByteArray): List<RawFrame>? {
        if (data.size < 10) return null
        // ID3v2 header: "ID3" + version(2) + flags(1) + size(4 synchsafe)
        if (data[0] != 'I'.code.toByte() || data[1] != 'D'.code.toByte() || data[2] != '3'.code.toByte()) {
            return null  // 无 ID3v2 标签
        }
        val versionMajor = data[3].toInt() and 0xFF
        // v2.2 帧结构不同（3字节ID），暂不支持
        if (versionMajor < 3) return null
        val headerSize = 10
        val tagSize = synchsafeToInt(data, 6)
        val tagEnd = (headerSize + tagSize).coerceAtMost(data.size)
        val frames = mutableListOf<RawFrame>()
        var pos = headerSize
        while (pos + 10 <= tagEnd) {
            val frameId = String(data, pos, 4, Charsets.ISO_8859_1)
            if (frameId[0] == '\u0000') break  // padding
            val frameSize = if (versionMajor == 4) {
                synchsafeToInt(data, pos + 4)
            } else {
                // v2.3: 普通 4 字节 big-endian
                ((data[pos + 4].toInt() and 0xFF) shl 24) or
                    ((data[pos + 5].toInt() and 0xFF) shl 16) or
                    ((data[pos + 6].toInt() and 0xFF) shl 8) or
                    (data[pos + 7].toInt() and 0xFF)
            }
            if (frameSize <= 0 || pos + 10 + frameSize > tagEnd) break
            val frameData = data.copyOfRange(pos + 10, pos + 10 + frameSize)
            frames.add(RawFrame(frameId, frameData))
            pos += 10 + frameSize
        }
        return frames
    }

    /** synchsafe integer（ID3v2.4 与标签总大小用） */
    private fun synchsafeToInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }

    /** USLT: encoding(1) + language(3) + content descriptor(null-terminated) + lyrics text */
    private fun parseUsltFrame(data: ByteArray): String? {
        if (data.size < 4) return null
        val encoding = data[0].toInt() and 0xFF
        // 跳过 language(3)，找 descriptor 的结束 null
        val descriptorEnd = findNullTerminator(data, 4, encoding)
        if (descriptorEnd < 0) return null
        val lyricsStart = descriptorEnd + terminatorLength(encoding)
        if (lyricsStart >= data.size) return null
        val lyricsBytes = data.copyOfRange(lyricsStart, data.size)
        return decodeText(lyricsBytes, encoding).takeIf { it.isNotBlank() }
    }

    /** APIC: encoding(1) + mime(null-terminated, ISO-8859-1) + picture type(1) + description(null-terminated) + picture data */
    private fun parseApicFrame(data: ByteArray): Pair<String, ByteArray>? {
        if (data.size < 2) return null
        val encoding = data[0].toInt() and 0xFF
        val mimeEnd = findNullTerminator(data, 1, 0)  // MIME 始终 ISO-8859-1
        if (mimeEnd < 0) return null
        val mime = String(data, 1, mimeEnd - 1, Charsets.ISO_8859_1)
        val picTypeIndex = mimeEnd + 1  // null 之后 1 字节 picture type
        if (picTypeIndex >= data.size) return null
        val descStart = picTypeIndex + 1
        val descEnd = findNullTerminator(data, descStart, encoding)
        if (descEnd < 0) return null
        val picStart = descEnd + terminatorLength(encoding)
        if (picStart >= data.size) return null
        val picBytes = data.copyOfRange(picStart, data.size)
        return mime to picBytes
    }

    /** 在 [start, end) 范围内找 null 终止符位置（按编码返回 null 起始字节索引） */
    private fun findNullTerminator(data: ByteArray, start: Int, encoding: Int): Int {
        var i = start
        when (encoding) {
            1, 2 -> {  // UTF-16: 双字节 null
                while (i + 1 < data.size) {
                    if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i
                    i += 2
                }
            }
            else -> {  // ISO-8859-1 / UTF-8: 单字节 null
                while (i < data.size) {
                    if (data[i] == 0.toByte()) return i
                    i++
                }
            }
        }
        return -1
    }

    private fun terminatorLength(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1
}

package com.nasmusic.tv.backend.playlist

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 歌单导入解析层：模型 + 解析器接口 + 通用工具。
 *
 * 设计索引：docs/archive/playlist-import-feature-plan.md §4.1
 * 原则：解析阶段不查网络、不读文件路径、不连 NAS，只把文本抽出来；
 * 后续补全 / 可达性阶段才决定条目归属。
 */

/**
 * 原始歌单条目（解析器输出，未入库）。
 *
 * @param title 必填；空则跳过并计数
 * @param artist 可空，"" 允许（txt 常见形态）
 * @param sourceHint 解析阶段已识别的本地/网络 hint（不持久化，仅供补全参考）
 * @param originalIndex 文件内位置（用于 UI 显示顺序）
 * @param directUrl 直接 URL（M3u 的 path hint 等）：非空时可直接构造 Song.streamUrl，
 *   无需经过「按 title/artist 搜索 → 解析流地址」的补全链（§4.1.8）
 * @param directUrlType directUrl 的来源类型（仅 directUrl 非空时有意义）
 */
data class RawSongEntry(
    val title: String,
    val artist: String = "",
    val album: String? = null,
    val durationSec: Int? = null,
    val sourceHint: SongSourceHint = SongSourceHint.NONE,
    val originalIndex: Int,
    val directUrl: String? = null,
    val directUrlType: DirectUrlType = DirectUrlType.NONE,
)

enum class SongSourceHint { NONE, NETWORK_METING, LOCAL_FILE, NAS }

/**
 * URL 分类（§4.1.8）：
 * - NONE：无 URL（txt/纯文本/网易云 JSON 路径）
 * - HTTP：远程 HTTP/HTTPS，需要可达性测试
 * - LOCAL_URI：file:// 形式（绝对路径），直接可播
 * - ABSOLUTE_PATH：以 / 开头的绝对路径（视为本地文件，需要映射到本机路径）
 * - RELATIVE_PATH：相对路径（m3u 文件所在目录 + 相对路径，本期不支持映射，记入 skipped）
 */
enum class DirectUrlType { NONE, HTTP, LOCAL_URI, ABSOLUTE_PATH, RELATIVE_PATH }

/**
 * 解析器接口。
 */
interface PlaylistParser {
    /** 是否能解析该文本（基于首 4KB 嗅探）；null 表示「不识别」 */
    fun canParse(headBytes: ByteArray, fileName: String): Boolean

    /** 抛 [PlaylistParseException] 让上层弹错误；返回空列表视为「不识别」 */
    fun parse(text: String, fileName: String): List<RawSongEntry>
}

/** 解析失败异常（格式损坏等）。message 可安全展示给用户。 */
class PlaylistParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 可选实现：解析器从内容中提取「歌单名 hint」。
 * 供 [PlaylistImporter] 取名兜底链使用（§4.2）：m3u `#PLAYLIST:` / JSON `name` /
 * txt 首行带冒号注释 / 网易云 `playlist.name`。
 */
interface PlaylistNameProvider {
    /** 最近一次 parse() 提取的歌单名（无则为 null）。 */
    val innerName: String?
}

/**
 * 通用解析工具。
 */
object PlaylistParsers {

    /** 单文件大小上限（字节）。§4.1.7 (3)：超过截断提示，防 OOM。 */
    const val MAX_FILE_BYTES = 5 * 1024 * 1024

    /**
     * 导入裸条目 stub 的 Song.id 统一前缀（§4.1.8 (2) / §4.3）。
     * PlaylistImporter 生成、PlaylistEnricher 用该前缀识别「待补全」条目。
     */
    const val IMPORTED_ID_PREFIX = "imported_"

    /** 单行长度上限（字符）。§4.1.7 测试：巨行（1MB 级）跳过，防止单行 OOM。 */
    const val MAX_LINE_LENGTH = 200_000

    /** 嗅探所用首字节数（4KB）。 */
    const val SNIFF_BYTES = 4 * 1024

    /** 无意义默认文件名白名单（大小写不敏感，不含扩展名）。§4.1.7 (5) */
    val MEANINGLESS_FILE_BASENAMES = setOf(
        "playlist", "list", "songs", "music",
        "新建文本文档", "无标题", "untitled", "default",
    )

    /**
     * 字节流解码：先按 UTF-8 严格解码，失败回退 GBK（中文社区 m3u/txt 常见 GBK）。
     * 返回时已剥离 UTF-8 BOM（若存在）。
     */
    fun decode(bytes: ByteArray): String {
        val trimmed = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) bytes.copyOfRange(3, bytes.size) else bytes

        if (trimmed.isEmpty()) return ""
        return try {
            strictDecode(trimmed, Charsets.UTF_8)
        } catch (_: Exception) {
            try {
                strictDecode(trimmed, Charset.forName("GBK"))
            } catch (_: Exception) {
                // 双重失败（罕见）：退回宽松 UTF-8（替换非法字节），绝不抛给上层
                String(trimmed, Charsets.UTF_8)
            }
        }
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    /**
     * 归一化歌曲标识（与 SearchAggregator.normalizeKey 同口径）：
     * 小写 + 去除首尾空白 + 合并连续空格。
     */
    fun normalizeKey(title: String, artist: String): String {
        fun collapse(s: String): String {
            val t = s.trim().lowercase()
            return t.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        }
        return "${collapse(title)}|${collapse(artist)}"
    }

    /** 文件名去除扩展名后的基础名（"我的歌单.m3u" → "我的歌单"）。 */
    fun baseNameOf(fileName: String): String {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        return name.substringBeforeLast('.', name).trim()
    }

    /** 是否为「无意义默认文件名」（命中白名单，跳过文件名兜底）。 */
    fun isMeaninglessFileName(fileName: String): Boolean {
        return baseNameOf(fileName).lowercase() in MEANINGLESS_FILE_BASENAMES
    }

    /** URL 行分类（§4.1.8）：HTTP / file:// / 绝对路径 / 相对路径 / 非 URL。 */
    fun classifyUrl(raw: String): DirectUrlType {
        val line = raw.trim()
        if (line.isEmpty()) return DirectUrlType.NONE
        val lower = line.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return DirectUrlType.HTTP
        if (lower.startsWith("file://")) return DirectUrlType.LOCAL_URI
        if (line.startsWith("/")) return DirectUrlType.ABSOLUTE_PATH
        // 形如 x.y（含扩展名）或含路径分隔符的相对路径；不含扩展名的纯词（如 md5 值）不视为路径
        if (line.contains('/') || line.contains('\\') || Regex("^[^/\\\\]+\\.[A-Za-z0-9]{1,8}$").matches(line)) {
            return DirectUrlType.RELATIVE_PATH
        }
        return DirectUrlType.NONE
    }

    /** 从 URL / 文件路径提取展示名（去协议、去目录、去扩展名）。 */
    fun displayNameFromUrl(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("file://")
        val schemeIdx = s.indexOf("://")
        if (schemeIdx > 0) s = s.substring(schemeIdx + 3)
        s = s.substringAfterLast('/').substringAfterLast('\\')
        if (s.isBlank()) return raw.trim()
        return s.substringBeforeLast('.', s).trim().ifEmpty { raw.trim() }
    }
}
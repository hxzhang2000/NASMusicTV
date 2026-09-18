package com.nasmusic.tv.backend.playlist

/**
 * M3u / M3u8 解析器。
 *
 * 设计：docs/playlist-import-feature-plan.md §4.1.1 / §4.1.4 / §4.1.8
 * - `#EXTINF:<sec>,<artist> - <title>`：按 ` - ` 拆第一个分隔（左侧 artist、右侧 title）；
 *   无 ` - ` 时整段当 title（artist 空）
 * - `#EXTINF` 之后的第一个非注释、非空行是该条目的 path hint → `directUrl`
 *   （按 §4.1.8 (1) URL 分类：HTTP / file:// / 绝对路径 / 相对路径）
 * - 无 `#EXTINF` 关联的裸 URL 行自成条目：title=URL 文件名（去扩展名）、artist 空
 * - 跳过 `#EXT-X-`（直播）、`#PLAYLIST:` 等头与空行
 * - 相对路径条目原样保留（type=RELATIVE_PATH），由上层 PlaylistImporter 计入 skipped
 */
class M3uPlaylistParser : PlaylistParser, PlaylistNameProvider {

    /** 最近一次 parse 提取的歌单名 hint（`#PLAYLIST:` 头，§4.2 取名兜底链）。 */
    override var innerName: String? = null
        private set

    override fun canParse(headBytes: ByteArray, fileName: String): Boolean {
        // 扩展名兜底：.m3u/.m3u8 直接走本解析器（§4.1.1「整文件只有 http
        // 单行导出、无 #EXTM3U 头的纯 URL 列表」也能识别）
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "m3u" || ext == "m3u8") return true
        if (headBytes.isEmpty()) return false
        val head = PlaylistParsers.decode(headBytes)
        // 头部嗅探优先于扩展名：从其他工具导出的 .txt 后缀 M3u 也能识别（§4.1.7 (4)）
        return head.contains("#EXTM3U") || head.contains("#EXTINF:")
    }

    override fun parse(text: String, fileName: String): List<RawSongEntry> {
        innerName = null
        val entries = mutableListOf<RawSongEntry>()
        var pendingExtinf: Pair<Int?, String?>? = null // (durationSec, displayLabel)
        var index = 0

        text.lineSequence().forEach { rawLine ->
            if (rawLine.length > PlaylistParsers.MAX_LINE_LENGTH) return@forEach
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF:")) {
                    pendingExtinf = parseExtinf(line)
                } else if (line.startsWith("#PLAYLIST:")) {
                    // §4.2 取名兜底：`#PLAYLIST:<name>` 头（部分导出工具写入）
                    val name = line.removePrefix("#PLAYLIST:").trim()
                    if (name.isNotEmpty()) innerName = name
                }
                // 其他 # 行（#EXTM3U / #EXT-X-）忽略
                return@forEach
            }

            // 非注释、非空行 = path hint（首个非注释行通常紧跟 EXTINF）
            val urlType = PlaylistParsers.classifyUrl(line)
            val isPathLike = urlType != DirectUrlType.NONE

            if (pendingExtinf != null) {
                val (sec, label) = pendingExtinf!!
                pendingExtinf = null
                val (artist, title) = splitExtinfLabel(label)
                val resolvedTitle = title.ifBlank { PlaylistParsers.displayNameFromUrl(line) }
                entries += RawSongEntry(
                    title = resolvedTitle,
                    artist = artist,
                    durationSec = sec,
                    originalIndex = index++,
                    directUrl = if (isPathLike) line else null,
                    directUrlType = if (isPathLike) urlType else DirectUrlType.NONE,
                )
            } else if (isPathLike) {
                // 裸 URL 行（无待归属 EXTINF）自成条目
                entries += RawSongEntry(
                    title = PlaylistParsers.displayNameFromUrl(line),
                    artist = "",
                    originalIndex = index++,
                    directUrl = line,
                    directUrlType = urlType,
                )
            }
            // 无 EXTINF 且非 URL 的裸文本行：非标准 m3u，忽略
        }
        return entries
    }

    /** 解析 `#EXTINF:<sec>,<剩余>`；label 可空（缺失逗号时）。 */
    private fun parseExtinf(line: String): Pair<Int?, String?> {
        val rest = line.removePrefix("#EXTINF:").trim()
        val comma = rest.indexOf(',')
        if (comma < 0) return (rest.toIntOrNull()?.coerceAtLeast(0)) to null
        val sec = rest.substring(0, comma).trim().toIntOrNull()?.coerceAtLeast(0)
        val label = rest.substring(comma + 1).trim()
        return sec to (label.ifBlank { null })
    }

    /**
     * 拆分 EXTINF 显示标签（§4.1.4 标准：`artist - title`）。
     * 只切第一个 ` - `；无分隔时整段当 title、artist 空。
     */
    private fun splitExtinfLabel(label: String?): Pair<String, String> {
        if (label.isNullOrBlank()) return "" to ""
        val sep = label.indexOf(" - ")
        return if (sep > 0) {
            label.substring(0, sep).trim() to label.substring(sep + 3).trim()
        } else {
            "" to label.trim()
        }
    }
}
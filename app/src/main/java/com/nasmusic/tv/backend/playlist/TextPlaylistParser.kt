package com.nasmusic.tv.backend.playlist

/**
 * 文本歌单解析器（用户自编辑文档）。
 *
 * 设计：docs/playlist-import-feature-plan.md §4.1.7
 * 行级格式：`[序号] [title] [分隔符 artist]`
 * - 分隔符按优先级取第一个匹配：Tab > " - " > " — " > " – " > " -- " > " :: " > 冒号(带前后空格) > 逗号(带前后空格, title>8字符)
 * - 单短横 `-`（无空格环绕）不作为分隔符，避免误识别 `U2-1`
 * - 只切第一个分隔符，右侧整体作为 artist（artist 可为空）
 * - 注释：`#`/`//` 开头；空行跳过；编号 `1.` `1、` `1)` `①` `（1）` 剥离
 * - 首行若为「带冒号的注释行」→ 提取 innerName（取名兜底链）
 * - BOM 已在 [PlaylistParsers.decode] 剥离（此处二次兜底）
 * - 巨行（> MAX_LINE_LENGTH）跳过并计入 lastSkippedCount（§4.1.7 测试要点）
 */
class TextPlaylistParser : PlaylistParser, PlaylistNameProvider {

    /** 最近一次 parse 提取的歌单名 hint（无则为 null）。 */
    override var innerName: String? = null
        private set

    /** 最近一次 parse 中因超长/无 title 被跳过的行数。 */
    var lastSkippedCount: Int = 0
        private set

    override fun canParse(headBytes: ByteArray, fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in setOf("txt", "list", "csv")
    }

    override fun parse(text: String, fileName: String): List<RawSongEntry> {
        innerName = null
        lastSkippedCount = 0
        val entries = mutableListOf<RawSongEntry>()
        var index = 0

        val source = if (text.startsWith('\uFEFF')) text.removePrefix("\uFEFF") else text
        val lines = source.split('\n')
        lines.forEachIndexed { lineNo, raw ->
            if (lineNo == 0) {
                // 首行：若为「带冒号的注释行」→ innerName；否则正常走歌单解析
                val first = raw.trim()
                if (first.startsWith("#") || first.startsWith("//")) {
                    val colon = first.indexOfFirst { it == ':' || it == '：' }
                    if (colon >= 0) {
                        val name = first.substring(colon + 1).trim()
                        if (name.isNotEmpty()) {
                            innerName = name
                            return@forEachIndexed
                        }
                    }
                    return@forEachIndexed // 普通注释行跳过
                }
            }

            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.length > PlaylistParsers.MAX_LINE_LENGTH) {
                lastSkippedCount++
                return@forEachIndexed
            }
            if (line.startsWith("#") || line.startsWith("//")) return@forEachIndexed

            val (title, artist) = splitLine(line)
            if (title.isEmpty()) {
                lastSkippedCount++
                return@forEachIndexed
            }
            entries += RawSongEntry(
                title = title,
                artist = artist,
                originalIndex = index++,
            )
        }
        return entries
    }

    /** 切分行：剥编号 → 按分隔符优先级找第一个匹配 → 切 title/artist（只切第一个，右侧整体作 artist）。 */
    internal fun splitLine(line: String): Pair<String, String> {
        var s = stripNumbering(line)
        if (s.isEmpty()) return "" to ""
        val sep = findSplitter(s) ?: return s.trim() to ""
        val title = s.substring(0, sep.first).trim()
        val artist = s.substring(sep.first + sep.second).trim()
        return title to artist
    }

    /** 剥离行首编号：`1.` `1、` `1)` `①` `（1）` `(1)`（可带前导空格）。 */
    private fun stripNumbering(line: String): String {
        val t = line.trimStart()
        if (t.isEmpty()) return ""
        // ① ② ③ ... ⑳
        if (t.first() in '\u2460'..'\u2473') return t.drop(1).trimStart()
        // （1）/ (1)
        val inParen = Regex("^[（(]\\d+[）)]").find(t)
        if (inParen != null) return t.substring(inParen.range.last + 1).trimStart()
        // 1. / 1、 / 1)（编号标记：数字 + 标点，后随非数字（含空白/行尾），避免误剥 `1.5倍速`）
        val numbered = Regex("^\\d+\\s*[.、)](?!\\d)").find(t)
        if (numbered != null) return t.substring(numbered.range.last + 1).trimStart()
        return t
    }

    /**
     * 按优先级返回 (分隔符起始下标, 分隔符长度)；无匹配返回 null。
     * 优先级（§4.1.7 (1)）：Tab > " - " > " — " > " – " > " -- " > " :: " > 冒号(带前后空格) > 逗号(半/全角, title>8字符)
     * 注意：按优先级顺序尝试第一个能匹配的分隔符（与出现位置无关）。
     */
    internal fun findSplitter(s: String): Pair<Int, Int>? {
        val tab = s.indexOf('\t')
        if (tab >= 0) return tab to 1

        // 带空格的符号分隔符（按优先级顺序；" :: " 必须先在 " : " 之前检测）
        val spacedSymbols = listOf(" - ", " — ", " – ", " -- ", " :: ", " : ", " ： ")
        for (sym in spacedSymbols) {
            val idx = s.indexOf(sym)
            if (idx >= 0) return idx to sym.length
        }

        // 逗号（优先级 8）：半角/全角均可，不带空格要求；仅当 title 段 > 8 字符时启用（§4.1.7 (1)）
        val commaIdx = s.indexOfFirst { it == ',' || it == '，' }
        if (commaIdx >= 0 && s.substring(0, commaIdx).trim().length > 8) {
            return commaIdx to 1
        }
        return null
    }
}
package com.nasmusic.tv.util

/**
 * 百度网盘文件名 → 歌手/歌名 解析
 *
 * 对照 BoxPlayer `musicMetadata.ts` 的 `guessArtistTitle` 实现：
 * - 清洗括号内注释（`(...)`/`[...]`/`（...）`/`【...】`）
 * - 清洗尾部标签（`- official`/`- MV`/`- 无损` 等）
 * - 去轨道号前缀（`01 - `/`CD1 03 - `）
 * - 按 ` - `/`_-_`/`－`/`–` 等分隔符切分歌手与歌名
 *
 * 解析失败时返回 `"" to <文件名去扩展名>`。
 */
object BaiduFilenameParser {

    private val BRACKETS_RE = Regex("""[\(\[（【][^\)\]）】]*[\)\]）】]""")
    private val TRAILING_TAGS_RE = Regex(
        """\s*-\s*(official|mv|hd|hq|lossless|live|remix|cover|伴奏|纯音乐|高清|无损|完整版|live现场)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val SEPARATORS = listOf(" - ", "_-_", "－", " -", "- ", " – ", "–")

    private fun clean(s: String): String =
        s.replace(BRACKETS_RE, " ")
            .replace(TRAILING_TAGS_RE, " ")
            .replace(Regex("""[_\.]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun stripTrackNo(s: String): String =
        s.replace(Regex("""^\s*(?:cd\s*\d+\s*)?\d{1,3}\s*[-–—]?\s*""", RegexOption.IGNORE_CASE), "").trim()

    /**
     * @param filename 含扩展名的文件名（如 `周杰伦 - 晴天.mp3`）
     * @return (artist, title)，artist 可能为空字符串
     */
    fun parse(filename: String): Pair<String, String> {
        val base = stripTrackNo(clean(filename.substringBeforeLast('.')))
        for (sep in SEPARATORS) {
            val i = base.indexOf(sep)
            if (i > 0) {
                val artist = stripTrackNo(base.substring(0, i).trim())
                val title = stripTrackNo(base.substring(i + sep.length).trim())
                if (artist.isNotBlank() && title.isNotBlank()) return artist to title
            }
        }
        return "" to base.ifBlank { filename.substringBeforeLast('.') }
    }
}

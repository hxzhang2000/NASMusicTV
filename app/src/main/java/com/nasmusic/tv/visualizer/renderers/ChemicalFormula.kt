package com.nasmusic.tv.visualizer.renderers

import kotlin.math.max

/**
 * ChemicalFormula —— **化学式专用**排版（E37「分子」）。
 *
 * ## 为什么不用 [FormulaLayout]
 *
 * [FormulaLayout] 是 E25「催眠」的数学排版器，面向 `e^{-x^2}` / `frac{a}{b}` / `√{x}`
 * 这类**基线为主、上标为辅**的公式：它的下标只是解析器预留（`SUB_LOWER = 0.25em`），
 * 而 0.25em 的下沉量小于下标字形自身的字高（0.62em × 0.72 ≈ 0.45em），
 * 画出来下标会**整块骑在主基线上**，与字母对不齐。
 * 另外它按「词」断行（[FormulaLayout] 内部 `splitWords`）、按行右对齐，
 * 化学式是整串不可断的文本，用不上却会被这套逻辑干扰。
 *
 * 化学式恰恰相反 —— 它是**下标为主**的文体：`H₂O` 的 `2` 必须整体沉到 `H` 的
 * 基线之下、并且紧贴 `H` 的右缘。本类按这个文体重新排版，不做断行、不做分式、
 * 只处理「基线串 + 下标 + 括号」。
 *
 * ## ⛔ 绘制约定（历史 BUG，改必读）
 *
 * [Result.runX] 是 **run 左缘**。绘制侧必须用 `Paint.Align.LEFT`。
 * 曾经分子渲染器用 `Paint.Align.RIGHT` 画 run，等于每个 run 再向左平移自身宽度，
 * 下标会整块压到前一个字母身上（用户反馈「下标位置不对，与字母对不上」）。
 * 回归护栏：`ChemicalFormulaTest.run 左缘 + 自身宽度 == 下一个 run 左缘`。
 *
 * ## 下标基线的算法（不再用魔法常数）
 *
 * 下标基线 = 主基线 + (−探针字形墨迹顶部) + 间隙。
 * 墨迹顶部由 [InkTopFn] 注入（生产侧 `Paint.getTextBounds`，单测侧注入近似值），
 * 探针固定用数字 `0` —— **所有下标共用同一条基线**，不能逐个 run 量自己的字高，
 * 否则 `10` 和 `2` 会高低不齐。
 *
 * **纯 JVM**：不 import 任何 Android 类，测量与字体度量全部注入，单测可断言。
 */
object ChemicalFormula {

    /** 文本测量：文本 + 字号 → 像素宽度 */
    fun interface MeasureFn {
        fun width(text: String, size: Float): Float
    }

    /**
     * 字体度量：返回 [text] 在 [size] 下**墨迹顶部**相对基线的 y 偏移。
     * 负值 = 在基线之上（与 `Paint.FontMetrics.ascent` 同符号约定，但只算墨迹、不含留白）。
     */
    fun interface InkTopFn {
        fun inkTop(text: String, size: Float): Float
    }

    /** 源标记解析出的一个 token。[sub] = true 表示下标 */
    class Token(val text: String, val sub: Boolean) {
        override fun toString(): String = if (sub) "_($text)" else text
    }

    /** 扁平布局结果：坐标均为屏幕绝对坐标，绘制侧每帧只做 drawText */
    class Result(
        val runText: Array<String>,
        val runX: FloatArray,      // 每个 run 的**左缘**
        val runY: FloatArray,      // 每个 run 的基线 y
        val runSize: FloatArray,   // 字号（下标 = em * [SUB_SCALE]）
        val runCount: Int,
        val totalWidth: Float,
        val em: Float              // 生效字号（可能被缩字号兜底压低）
    )

    /** 下标字号比例（相对基线字号） */
    const val SUB_SCALE = 0.62f

    /** 探针字符：量取下标字高用，保证所有下标共用同一条基线 */
    const val PROBE = "0"

    /** 下标墨迹顶部与主基线之间的间隙（相对 em） */
    private const val SUB_GAP_F = 0.06f

    /** 缩字号兜底下限（px）：再挤也不让步到看不清 */
    const val MIN_EM = 22f

    /** run 数上限（52 个分子式实测最长 9 run，留余量） */
    private const val MAX_RUNS = 20

    /** 兜底：注入的 inkTop 退化（返回 ≥ 0）时用这个比例 */
    private const val SUB_FALLBACK_F = 0.45f

    // ── 解析 ────────────────────────────────────────────────────

    /**
     * 解析化学式源标记。语法（比 [FormulaLayout] 简单得多，化学式用不到别的）：
     *   - `_{...}`  下标（不支持嵌套；不配对时该 `_` 被丢弃）
     *   - `_2`      无花括号的单个/连续数字下标
     *   - 其余字符（元素符号 / `(` `)` `[` `]` / `·`）按字面合并为基线 run
     */
    fun parse(src: String): List<Token> {
        val out = ArrayList<Token>()
        val sb = StringBuilder()
        var i = 0

        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(Token(sb.toString(), false))
                sb.setLength(0)
            }
        }

        while (i < src.length) {
            val c = src[i]
            if (c == '_') {
                flush()
                if (i + 1 < src.length && src[i + 1] == '{') {
                    val end = src.indexOf('}', i + 2)
                    if (end < 0) {
                        i++                       // 不配对：丢弃这个 '_'
                    } else {
                        val inner = src.substring(i + 2, end)
                        if (inner.isNotEmpty()) out.add(Token(inner, true))
                        i = end + 1
                    }
                } else {
                    var j = i + 1
                    while (j < src.length && src[j].isDigit()) j++
                    if (j > i + 1) {
                        out.add(Token(src.substring(i + 1, j), true))
                        i = j
                    } else {
                        i++                       // '_' 后没有数字：丢弃
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        flush()
        return out
    }

    // ── 布局 ────────────────────────────────────────────────────

    /** [tokens] 在字号 [e] 下的总宽（线性，缩字号兜底可直接按比例反解） */
    fun widthOf(tokens: List<Token>, e: Float, measure: MeasureFn): Float {
        var w = 0f
        for (t in tokens) w += measure.width(t.text, if (t.sub) e * SUB_SCALE else e)
        return w
    }

    /**
     * 解析 + 预布局：单行、右对齐到 [rightX]，垂直以 [centerY] 为基线字身中心。
     *
     * @param rightX   右缘（整条公式右对齐到此）
     * @param centerY  垂直中心（基线 = centerY + em * 0.35，与 [FormulaLayout] 视觉对齐）
     * @param bandW    宽度预算；总宽超出时按比例缩字号，下限 [MIN_EM]
     * @param inkTop   字体度量（下标基线下沉量由此算出，见类注释）
     */
    fun layout(
        src: String,
        rightX: Float,
        centerY: Float,
        bandW: Float,
        em: Float,
        measure: MeasureFn,
        inkTop: InkTopFn
    ): Result {
        val tokens = parse(src)

        // ── 1. 缩字号兜底：宽度与字号成正比，一次反解即可 ──
        var effEm = em
        val w0 = widthOf(tokens, em, measure)
        if (w0 > bandW && w0 > 0f) effEm = max(MIN_EM, em * bandW / w0)
        val total = if (effEm == em) w0 else widthOf(tokens, effEm, measure)

        val subSize = effEm * SUB_SCALE

        // ── 2. 下标基线下沉量：探针字形墨迹高度 + 间隙 ──
        val probeTop = inkTop.inkTop(PROBE, subSize)
        val subShift = if (probeTop < 0f) -probeTop + effEm * SUB_GAP_F else subSize * SUB_FALLBACK_F

        val baseline = centerY + effEm * 0.35f

        // ── 3. 逐 token 铺排（右对齐：行左缘 = rightX − 总宽）──
        val n = tokens.size.coerceAtMost(MAX_RUNS)
        val rt = Array(n) { "" }
        val rx = FloatArray(n)
        val ry = FloatArray(n)
        val rs = FloatArray(n)
        var x = rightX - total
        for (k in 0 until n) {
            val t = tokens[k]
            val size = if (t.sub) subSize else effEm
            rt[k] = t.text
            rx[k] = x
            ry[k] = if (t.sub) baseline + subShift else baseline
            rs[k] = size
            x += measure.width(t.text, size)
        }

        return Result(
            runText = rt,
            runX = rx,
            runY = ry,
            runSize = rs,
            runCount = n,
            totalWidth = total,
            em = effEm
        )
    }
}

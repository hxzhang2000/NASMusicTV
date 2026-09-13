package com.nasmusic.tv.visualizer.renderers

import kotlin.math.max

/**
 * FormulaLayout —— 数学公式源标记 → 排版 run（E25「催眠」专用）。
 *
 * 把 `FunctionDef.label` 的源标记解析并预布局为扁平 run 列表，渲染器每帧
 * 只做 `drawText` / `drawLine`，零测量、零解析、零分配。
 *
 * 支持的源标记语法（详见 docs/催眠频谱效果开发方案.md §2.5 / §11）：
 *   - `x^{...}`      上标（可嵌套：`e^{-x^2}` 渲染为 e 的上标 −x²）
 *   - `x_{...}`      下标（本清单暂未用到，解析器预留）
 *   - `^2` / `^3`    单字符上标（无花括号）
 *   - `²³¹` 等 Unicode 上标字符 → 与 `^n` 等价，统一转上标 run，
 *     绘制时画**普通字形**（缩小上移），不直接绘制 U+00B2/U+2070 区字符
 *   - `frac{a}{b}`   真分式（分子 / 横线 / 分母）
 *   - `√{...}`       根号字形 + 上横线（vinculum）
 *   - 其余字符按字面绘制（`·`、希腊字母直接作为基线文本）
 *
 * **纯 JVM**：不 import 任何 Android 类；文本测量通过 [MeasureFn] 注入，
 * 生产传 `Paint::measureText`，单测传字符数近似，保证可测性与可移植性。
 *
 * 容错：花括号不配对时降级为字面文本绘制，不抛异常（清单错误由单测拦截）。
 */
object FormulaLayout {

    /** 文本测量函数：文本 + 字号 → 像素宽度 */
    fun interface MeasureFn {
        fun width(text: String, textSize: Float): Float
    }

    // ── 解析模型（纯逻辑）────────────────────────────────────────

    sealed interface Expr

    /** 表达式项。level: 0 基线 / +1 上标 / -1 下标 */
    class Text(val text: String, val level: Int) : Expr

    /** 真分式：分子 / 分母（内部仅允许 [Text]，本清单不渲染连分式） */
    class Frac(val num: List<Text>, val den: List<Text>) : Expr

    /** 根号：√ 字形 + 上横线延伸到内容右缘 */
    class Sqrt(val inner: List<Text>) : Expr

    // ── 布局结果 ────────────────────────────────────────────────

    /**
     * 扁平布局结果。所有坐标为屏幕绝对坐标；run 与装饰线在
     * `DISSOLVE` 期由渲染器驱动碎散（装饰线跟随宿主 run）。
     */
    class Result(
        val runText: Array<String>,
        val runX: FloatArray,
        val runY: FloatArray,      // 基线 y
        val runSize: FloatArray,   // 字号（上标 = em * 0.65）
        val runLevel: IntArray,    // 0 / +1 / -1，溃散时上标额外纵向漂移
        val decor: FloatArray,     // L*4: x1, y1, x2, y2
        val decorHost: IntArray,   // L：装饰线宿主 run 下标
        val runCount: Int,
        val decorCount: Int,
        val totalHeight: Float
    )

    /** run 数上限（53 条清单中最长 B5 ≈12 run，留余量） */
    const val MAX_RUNS = 14

    /** 上标字号比例 / 基线抬升比例（相对 em） */
    private const val SUP_SCALE = 0.65f
    private const val SUP_RAISE = 0.42f
    private const val SUB_LOWER = 0.25f

    /** 缩字号兜底下限（px，≈16sp @2.0 density，§11.2） */
    private const val MIN_EM = 26f

    /** 断行最大行数 */
    private const val MAX_LINES = 3

    // ── 解析 ────────────────────────────────────────────────────

    /**
     * 解析源标记为表达式列表。相邻同 level 的普通字符合并为一个 [Text]。
     * 语法错误（不配对花括号）降级为字面文本，不抛异常。
     */
    fun parse(src: String): List<Expr> {
        val out = ArrayList<Expr>()
        parseSegment(src, 0, src.length, 0, out)
        return out
    }

    /** 解析 [from, to) 区间，level 为基准层级，结果追加到 [out] */
    private fun parseSegment(src: String, from: Int, to: Int, level: Int, out: ArrayList<Expr>) {
        var i = from
        val sb = StringBuilder()

        fun flush() {
            if (sb.isNotEmpty()) {
                mergeText(out, sb.toString(), level)
                sb.clear()
            }
        }

        while (i < to) {
            val c = src[i]
            when {
                // ── 上标 / 下标花括号组：^{...} / _{...} ──
                (c == '^' || c == '_') && i + 1 < to && src[i + 1] == '{' -> {
                    flush()
                    val end = findClosing(src, i + 1, to)
                    if (end < 0) {
                        // 不配对：剩余全部按字面
                        sb.append(src, i, to)
                        i = to
                    } else {
                        val inner = src.substring(i + 2, end)
                        parseSegment(inner, 0, inner.length, if (c == '^') level + 1 else level - 1, out)
                        i = end + 1
                    }
                }
                // ── 单字符上标：^2 / ^3 / ^n ──
                c == '^' && i + 1 < to && src[i + 1] != '{' -> {
                    flush()
                    mergeText(out, src[i + 1].toString(), level + 1)
                    i += 2
                }
                // ── 分式 frac{a}{b} ──
                src.startsWith("frac{", i) -> {
                    flush()
                    val aOpen = i + 4                       // 第一个 '{'
                    val aEnd = findClosing(src, aOpen, to)  // 分子后 '}'
                    if (aEnd < 0 || aEnd + 1 >= to || src[aEnd + 1] != '{') {
                        // 结构不完整 → 字面
                        sb.append("frac")
                        i += 4
                    } else {
                        val bEnd = findClosing(src, aEnd + 1, to)
                        if (bEnd < 0) {
                            sb.append(src, i, to)
                            i = to
                        } else {
                            val numBuf = ArrayList<Expr>()
                            val denBuf = ArrayList<Expr>()
                            parseSegment(src, aOpen + 1, aEnd, 0, numBuf)
                            parseSegment(src, aEnd + 2, bEnd, 0, denBuf)
                            val num = numBuf.filterIsInstance<Text>()
                            val den = denBuf.filterIsInstance<Text>()
                            if (num.isNotEmpty() && den.isNotEmpty()) {
                                out.add(Frac(num, den))
                            } else {
                                sb.append(src, i, bEnd + 1)   // 空分子/分母 → 字面
                            }
                            i = bEnd + 1
                        }
                    }
                }
                // ── 根号 √{...} ──
                c == '√' && i + 1 < to && src[i + 1] == '{' -> {
                    flush()
                    val end = findClosing(src, i + 1, to)
                    if (end < 0) {
                        sb.append('√')
                        i += 1
                    } else {
                        val innerBuf = ArrayList<Expr>()
                        parseSegment(src, i + 2, end, 0, innerBuf)
                        val inner = innerBuf.filterIsInstance<Text>()
                        if (inner.isNotEmpty()) out.add(Sqrt(inner)) else sb.append("√{}")
                        i = end + 1
                    }
                }
                // ── Unicode 上标字符 → 上标 run（绘制普通字形）──
                SUPERSCRIPT_MAP.containsKey(c) -> {
                    flush()
                    mergeText(out, SUPERSCRIPT_MAP.getValue(c).toString(), level + 1)
                    i += 1
                }
                else -> {
                    sb.append(c)
                    i += 1
                }
            }
        }
        flush()
    }

    /** 同 level 相邻文本合并；不同 level 独立成 run */
    private fun mergeText(out: ArrayList<Expr>, text: String, level: Int) {
        val last = out.lastOrNull()
        if (last is Text && last.level == level) {
            out[out.size - 1] = Text(last.text + text, level)
        } else {
            out.add(Text(text, level))
        }
    }

    /** 返回与 [openIdx] 处 '{' 配对的 '}' 下标，未找到返回 -1 */
    private fun findClosing(src: String, openIdx: Int, to: Int): Int {
        if (openIdx >= to || src[openIdx] != '{') return -1
        var depth = 0
        for (j in openIdx until to) {
            when (src[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return j
                }
            }
        }
        return -1
    }

    private val SUPERSCRIPT_MAP: Map<Char, Char> = mapOf(
        '²' to '2', '³' to '3', '¹' to '1', '⁰' to '0',
        '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7',
        '⁸' to '8', '⁹' to '9'
    )

    // ── 布局 ────────────────────────────────────────────────────

    /**
     * 解析 + 预布局。
     *
     * @param src     源标记
     * @param rightX  公式带右缘（所有行右对齐到此）
     * @param centerY 垂直居中锚点
     * @param bandW   公式带宽度预算
     * @param em      基准字号（px）
     * @param measure 文本测量
     */
    fun layout(
        src: String,
        rightX: Float,
        centerY: Float,
        bandW: Float,
        em: Float,
        measure: MeasureFn
    ): Result {
        val exprs = parse(src)

        // ── 1. 顶层展开为"原子"序列 ──
        // 原子 = 一个不可跨行拆分的绘制单元（基线 Text / Frac 整体 / Sqrt 整体）。
        // 基线 Text 按空格拆成词级原子（空格粘前词尾），断行只发生在词边界；
        // 上标/下标 Text 依附到第一个宿主原子，不产生新原子。
        fun splitWords(text: String): List<String> {
            if (!text.contains(' ') && !text.contains('+') && !text.contains('-')) {
                return listOf(text)
            }
            val out = ArrayList<String>()
            val cur = StringBuilder()
            for (ch in text) {
                // '+"/'-' 前断开：运算符粘后块开头（保持 "+sin(2.3x)" 数学语义完整）
                if ((ch == '+' || ch == '-') && cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
                cur.append(ch)
                // 空格粘前块尾部
                if (ch == ' ') {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }

        val atoms = ArrayList<Atom>()

        /** 上标/下标依附到**前一个**原子（e^{-x^2} 的上标属于 e） */
        fun attachSup(flat: Flat) {
            if (atoms.isEmpty()) {
                atoms.add(Atom.Plain(Text("", 0), listOf(flat)))
            } else {
                val last = atoms.removeAt(atoms.size - 1)
                atoms.add(
                    when (last) {
                        is Atom.Plain -> Atom.Plain(last.t, last.sup + flat)
                        is Atom.Fraction -> Atom.Fraction(last.f, last.sup + flat)
                        is Atom.Root -> Atom.Root(last.s, last.sup + flat)
                    }
                )
            }
        }

        for (e in exprs) {
            when (e) {
                is Text -> if (e.level == 0) {
                    for (word in splitWords(e.text)) {
                        atoms.add(Atom.Plain(Text(word, 0), emptyList()))
                    }
                } else {
                    attachSup(Flat(e.text, e.level, measure.width(e.text, em * SUP_SCALE)))
                }
                is Frac -> atoms.add(Atom.Fraction(e, emptyList()))
                is Sqrt -> atoms.add(Atom.Root(e, emptyList()))
            }
        }

        // ── 2. 原子宽度（参数化字号，供缩字号兜底复用）──
        fun supWidthAt(sup: List<Flat>, size: Float): Float {
            var w = 0f
            for (s in sup) w += measure.width(s.text, size * SUP_SCALE)
            return w
        }

        fun atomWidthAt(a: Atom, size: Float): Float = when (a) {
            is Atom.Plain -> measure.width(a.t.text, size) + supWidthAt(a.sup, size)
            is Atom.Fraction ->
                max(fracPartW(a.f.num, size, measure), fracPartW(a.f.den, size, measure)) +
                    size * 0.3f + supWidthAt(a.sup, size)
            is Atom.Root ->
                size * 0.75f + sqrtInnerW(a.s.inner, size, measure) + supWidthAt(a.sup, size)
        }

        // ── 1.5 缩字号兜底（§11.2）：3 行装不下时按比例缩字号，下限 26f ──
        var effEm = em
        run {
            var totalW = 0f
            for (a in atoms) totalW += atomWidthAt(a, em)
            if (totalW > bandW * MAX_LINES) {
                effEm = max(MIN_EM, em * bandW * MAX_LINES / totalW)
            }
        }
        val emf = effEm
        fun supWidth(sup: List<Flat>): Float = supWidthAt(sup, emf)
        fun atomWidth(a: Atom): Float = atomWidthAt(a, emf)

        val lines = ArrayList<List<Atom>>()
        var cur = ArrayList<Atom>()
        var curW = 0f
        for (a in atoms) {
            val w = atomWidth(a)
            if (cur.isNotEmpty() && curW + w > bandW) {
                lines.add(cur)
                cur = ArrayList()
                curW = 0f
            }
            cur.add(a)
            curW += w
        }
        if (cur.isNotEmpty()) lines.add(cur)

        // ── 3. 行块垂直排布（以 centerY 为中心）──
        val lineHeights = FloatArray(lines.size) { idx ->
            var h2 = emf * 1.35f
            for (a in lines[idx]) if (a !is Atom.Plain) h2 = max(h2, emf * 2.1f)
            h2
        }
        val totalH = lineHeights.sum()
        var lineTop = centerY - totalH / 2f

        // ── 4. 逐行逐原子布局（右对齐）──
        val rt = ArrayList<String>()
        val rx = ArrayList<Float>()
        val ry = ArrayList<Float>()
        val rs = ArrayList<Float>()
        val rl = ArrayList<Int>()
        val dcs = ArrayList<Float>()
        val dcsHost = ArrayList<Int>()

        for ((li, line) in lines.withIndex()) {
            var lw = 0f
            for (a in line) lw += atomWidth(a)
            var x = rightX - lw                    // 行左缘 = 右对齐
            val lineCenterY = lineTop + lineHeights[li] / 2f
            val baseline = lineCenterY + emf * 0.35f

            for (a in line) {
                when (a) {
                    is Atom.Plain -> {
                        if (a.t.text.isNotEmpty()) {
                            rt.add(a.t.text); rx.add(x); ry.add(baseline); rs.add(emf); rl.add(0)
                            x += measure.width(a.t.text, emf)
                        }
                        flatSup(a.sup, x, baseline, emf, measure, rt, rx, ry, rs, rl)
                        x += supWidth(a.sup)
                    }
                    is Atom.Fraction -> {
                        val numW = fracPartW(a.f.num, emf, measure)
                        val denW = fracPartW(a.f.den, emf, measure)
                        val innerW = max(numW, denW)
                        // 分子（分数线偏上，基线在 fracCenter 上方 0.30em）
                        var ix = x + emf * 0.15f + (innerW - numW) / 2f
                        val numBaseline = lineCenterY - emf * 0.30f
                        val numHost = rt.size
                        for (t in a.f.num) {
                            rt.add(t.text); rx.add(ix); ry.add(numBaseline); rs.add(emf); rl.add(0)
                            ix += measure.width(t.text, emf)
                        }
                        // 分数线（宿主 = 分子首 run）
                        dcs.add(x + emf * 0.15f); dcs.add(lineCenterY - emf * 0.02f)
                        dcs.add(x + emf * 0.15f + innerW); dcs.add(lineCenterY - emf * 0.02f)
                        dcsHost.add(numHost)
                        // 分母（基线在 fracCenter 下方 0.85em）
                        var dx = x + emf * 0.15f + (innerW - denW) / 2f
                        val denBaseline = lineCenterY + emf * 0.85f
                        for (t in a.f.den) {
                            rt.add(t.text); rx.add(dx); ry.add(denBaseline); rs.add(emf); rl.add(0)
                            dx += measure.width(t.text, emf)
                        }
                        x += innerW + emf * 0.3f
                        flatSup(a.sup, x, baseline, emf, measure, rt, rx, ry, rs, rl)
                        x += supWidth(a.sup)
                    }
                    is Atom.Root -> {
                        // √ 字形
                        rt.add("√"); rx.add(x); ry.add(baseline); rs.add(emf); rl.add(0)
                        val glyphW = emf * 0.75f
                        val innerW = sqrtInnerW(a.s.inner, emf, measure)
                        // 内部内容
                        var ix = x + glyphW
                        val innerHost = rt.size
                        for (t in a.s.inner) {
                            rt.add(t.text); rx.add(ix); ry.add(baseline); rs.add(emf); rl.add(0)
                            ix += measure.width(t.text, emf)
                        }
                        // 上横线：从 √ 字形顶部延伸到内容右缘（宿主 = 内部首 run）
                        val barY = baseline - emf * 0.92f
                        dcs.add(x + glyphW * 0.45f); dcs.add(barY)
                        dcs.add(x + glyphW + innerW); dcs.add(barY)
                        dcsHost.add(innerHost)
                        x += glyphW + innerW
                        flatSup(a.sup, x, baseline, emf, measure, rt, rx, ry, rs, rl)
                        x += supWidth(a.sup)
                    }
                }
            }
            lineTop += lineHeights[li]
        }

        // ── 5. 超限合并（防御：run 数 > MAX_RUNS 时合并相邻 run）──
        // 实测 53 条清单最长 ≈12 run，此分支正常不触发；触发时牺牲粒度保正确。
        if (rt.size > MAX_RUNS) {
            while (rt.size > MAX_RUNS) {
                val j = rt.size - 2
                rt[j] = rt[j] + rt[rt.size - 1]
                rt.removeAt(rt.size - 1)
                rx.removeAt(rx.size - 1); ry.removeAt(ry.size - 1)
                rs.removeAt(rs.size - 1); rl.removeAt(rl.size - 1)
                // 装饰线宿主下标收缩：被并 run 的宿主改绑到 j，更远的宿主前移
                for (k in dcsHost.indices) {
                    val h = dcsHost[k]
                    dcsHost[k] = when {
                        h >= rt.size -> j
                        else -> h
                    }
                }
            }
        }

        return Result(
            runText = rt.toTypedArray(),
            runX = rx.toFloatArray(),
            runY = ry.toFloatArray(),
            runSize = rs.toFloatArray(),
            runLevel = rl.toIntArray(),
            decor = dcs.toFloatArray(),
            decorHost = dcsHost.toIntArray(),
            runCount = rt.size,
            decorCount = dcsHost.size,
            totalHeight = totalH
        )
    }

    // ── 内部结构 ────────────────────────────────────────────────

    /** 扁平上标/下标文本 */
    private class Flat(val text: String, val level: Int, val w: Float)

    /** 不可跨行拆分的绘制单元 */
    private sealed class Atom {
        /** 基线文本 + 依附的上标/下标 */
        class Plain(val t: Text, val sup: List<Flat>) : Atom()
        class Fraction(val f: Frac, val sup: List<Flat>) : Atom()
        class Root(val s: Sqrt, val sup: List<Flat>) : Atom()
    }

    private fun fracPartW(part: List<Text>, em: Float, m: MeasureFn): Float {
        var w = 0f
        for (t in part) w += m.width(t.text, em)
        return w
    }

    private fun sqrtInnerW(inner: List<Text>, em: Float, m: MeasureFn): Float {
        var w = 0f
        for (t in inner) w += m.width(t.text, em)
        return w
    }

    /** 上标/下标平铺：紧贴在 [xStart] 之后。嵌套层级按深度缩小/抬高（e^{-x^2} 的 "2" 为 level 2） */
    private fun flatSup(
        sup: List<Flat>, xStart: Float, baseline: Float, em: Float, m: MeasureFn,
        rt: ArrayList<String>, rx: ArrayList<Float>, ry: ArrayList<Float>,
        rs: ArrayList<Float>, rl: ArrayList<Int>
    ) {
        var x = xStart
        for (s in sup) {
            val depth = if (s.level > 0) s.level else -s.level
            var size = em
            repeat(depth) { size *= SUP_SCALE }
            val y = when {
                s.level > 0 -> baseline - em * SUP_RAISE * s.level
                s.level < 0 -> baseline + em * SUB_LOWER
                else -> baseline
            }
            rt.add(s.text); rx.add(x); ry.add(y); rs.add(size); rl.add(s.level)
            x += m.width(s.text, size)
        }
    }
}

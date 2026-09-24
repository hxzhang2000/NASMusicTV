package com.nasmusic.tv.visualizer.photo

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 照片墙渲染契约的**源码扫描门禁**（G20，§14.4）—— 两组判据。
 *
 * ## 为什么需要它
 *
 * 这两类缺陷的共同点是：**编译过、lint 过、既有单测过，只有肉眼能发现**，
 * 而且症状都被用户读成同一句话（「照片墙不对劲」）：
 *
 * ### A. `PhotoGeometry.update(...)` 漏传 B 的尺寸
 *
 * `update(ctx, mode, aW, aH, bW = aW, bH = aH)` 的 `bW`/`bH` 有默认值（为「单图自转场」
 * 的 `b == a` 场景准备）。于是「只传 A 的尺寸」是一行**完全合法**的代码，但语义变成
 * 「按上一张的宽高比裁下一张」：
 * - `CROP`：`srcB` 比例按 A 算 ⇒ 竖版新图被裁成横版的形状（**铺不满屏幕**）
 * - A 比 B「高」时 `srcB` 会**越出 B 的位图边界** ⇒ 有一块区域永远画不到（黑边）
 * - HOLD 期 `photoB` 仍是刚入场的那张、`p` 恒为 1 ⇒ **整个停留期都在按错误几何绘制**，
 *   直到下一次切换把它换成 `a` 才「跳」回正确形状 —— 用户读作「入场动画没走完就停留了」
 *
 * 真实案例：v2.37.0 上机复验（2026-09-24），见 `docs/technical-overview.md` §10.182。
 *
 * ### B. 转场在 `p` 快到 1 时跳过新图绘制
 *
 * 转场的 `p = 1` 帧必须等价于「新图整幅绘制」—— 因为 `p` 到 1 之后**整个 HOLD 期都是 1**。
 * 若在 `p ∈ [0.5, 1)` 用 `continue`/`return` 跳掉新图（例如「偏移已收敛到亚像素，省掉逐块
 * 绘制」），画面就**只剩旧图**：停留期一直在显示**上一张**照片，下一次切换才补上新图。
 *
 * 真实案例：`GlitchTransition` 的 `if (abs(offset) < 0.5f && p > 0.95f) continue`
 * （同样是 v2.37.0 上机复验发现）。正确写法是**改画整幅**而不是跳过
 * （见 `GlitchTransition` 的 `SETTLED` 分支、`ScanlineDissolveTransition` 的 `p >= 1f` 分支）。
 *
 * ## 为什么是单测而不是脚本
 *
 * 与 `SmallTouchTargetScanTest` / `ChipContentWidthScanTest` 同范式：跑在已有的
 * `testDebugUnitTest` 里，CI 已阻塞，零新增依赖。
 *
 * ## 自证（缺一不可）
 *
 * 1. **负向用例**：修复前的写法必须被判违规（否则「扫描通过」可能只是扫描逻辑空转）。
 * 2. **正向用例**：修复后的写法必须放行。
 * 3. **误报防线**：合法的 `p >= 1f` 终帧兜底、`local <= 0f` 跳过不得被误判；
 *    注释里举例的写法不得被误判。
 * 4. **豁免标记**：标记必须真的能放行。
 * 5. **空转断言**：真实扫描里断言「扫到的调用点 / 文件数」> 0。
 * 6. **锚点自证**：把模式存进局部变量后**仍须扫到** —— 锚点太窄会让门禁**静默空转**
 *    （照样报「0 违规」）。本门禁第一版就栽在这里，由用例 3 判出（见 `updateArgLists`）。
 */
class PhotoRenderContractScanTest {

    // ═══════════════════ A. `PhotoGeometry.update` 必须传 B 的尺寸 ═══════════════════

    @Test
    fun `PhotoGeometry update 必须把 B 的尺寸也传进去`() {
        val root = mainSourceRoot()
        var scanned = 0
        var callSites = 0
        val violations = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                scanned++
                val raw = file.readText()
                val clean = stripComments(raw)
                val sites = updateArgLists(clean)
                callSites += sites.size
                val rel = file.relativeTo(root).path.replace('\\', '/')
                for (site in sites) {
                    if (site.args >= REQUIRED_UPDATE_ARGS) continue
                    if (isExempt(raw, site.line, GEOM_EXEMPT_MARKER)) continue
                    violations += "$rel:${site.line}（只传了 ${site.args} 个实参，应为 $REQUIRED_UPDATE_ARGS）"
                }
            }

        assertTrue("没有扫到任何源码文件 —— 源目录定位可能失败：${root.absolutePath}", scanned > 0)
        // ⚠️ 空转自证：一个调用点都没扫到 → 扫描逻辑失配（而非「代码干净」）
        assertTrue(
            "没有扫到任何 `PhotoGeometry.update(...)` 调用点 —— 扫描逻辑可能失配（空转），结果不可信",
            callSites > 0,
        )

        if (violations.isNotEmpty()) {
            fail(
                "以下 `PhotoGeometry.update(...)` 没有传 B（新图）的尺寸：\n" +
                    violations.joinToString("\n") { "  • $it" } +
                    "\n\n`bW`/`bH` 的默认值就是 A 的尺寸 ⇒ 新图的 src/dst 会按**上一张的宽高比**算：" +
                    "竖版图铺不满屏幕、`srcB` 越出位图边界留下黑边，且 HOLD 期一直在按错误几何绘制。\n" +
                    "修法：geom.update(ctx, ctx.photoScaleMode, a.width, a.height, b.width, b.height)\n" +
                    "确实只能拿到单图尺寸的，在调用上方加 `// $GEOM_EXEMPT_MARKER: <理由>`。",
            )
        }
    }

    /** 修复前的写法（只传 A 的尺寸）—— 必须命中（这是本门禁存在的理由） */
    @Test
    fun `只传 A 的尺寸必须被判违规`() {
        val src = """geom.update(ctx, ctx.photoScaleMode, a.width, a.height)"""
        assertTrue("护栏失效：只传 A 尺寸的写法没被判违规", violationsOf(src).isNotEmpty())
    }

    /** 压成一行的写法同样必须判违规（配对/计数逻辑最容易漏这类） */
    @Test
    fun `同一行且嵌套调用的写法也必须被判违规`() {
        val src = """geom.update(ctx, modeOf(settings), a.width, a.height)"""
        assertTrue("护栏失效：单行嵌套写法没被判违规", violationsOf(src).isNotEmpty())
    }

    /**
     * ⛔ **锚点自证**：模式存进局部变量、实参里不再出现 `photoScaleMode` 字面量时**仍须扫到**。
     *
     * 本门禁第一版只用「实参含 `photoScaleMode`」当锚点 —— 这条用例正是它的判据：
     * 漏扫时门禁**照样报「0 违规」**，但那是空转。
     */
    @Test
    fun `模式存进局部变量时也必须扫到`() {
        val src = """
            val mode = ctx.photoScaleMode
            geom.update(ctx, mode, a.width, a.height)
        """.trimIndent()
        assertTrue("漏扫：模式存进局部变量后调用点没被扫到（锚点太窄 ⇒ 门禁空转）", violationsOf(src).isNotEmpty())
    }

    /**
     * 实参里的**嵌套逗号**不得被算成顶层实参分隔符。
     *
     * 反例只有 **5** 个顶层实参（漏传 `bH`），但朴素的「按逗号切分」会数成 6 个 ⇒ **漏报**。
     */
    @Test
    fun `嵌套括号内的逗号不得被算成顶层实参`() {
        val src = """geom.update(ctx, ctx.photoScaleMode, a.width, a.height, maxOf(b.width, 1))"""
        assertTrue(
            "漏报：嵌套括号里的逗号被算成顶层实参，5 个实参被误判成 6 个",
            violationsOf(src).isNotEmpty(),
        )
    }

    /** 修复后的写法 —— 必须放行 */
    @Test
    fun `传了 B 的尺寸必须放行`() {
        val src = """geom.update(ctx, ctx.photoScaleMode, a.width, a.height, b.width, b.height)"""
        assertTrue("误报：传满 6 个实参的写法被判违规", violationsOf(src).isEmpty())
    }

    /** 多行写法（本项目真实写法）—— 必须放行 */
    @Test
    fun `多行且含嵌套括号的写法必须放行`() {
        val src = """
            geom.update(
                ctx,
                ctx.photoScaleMode,
                a.width,
                a.height,
                b.width,
                b.height,
            )
        """.trimIndent()
        assertTrue("误报：多行传满实参的写法被判违规", violationsOf(src).isEmpty())
    }

    /** 豁免标记必须真的能放行 */
    @Test
    fun `几何入参豁免标记必须生效`() {
        val src = """
            // $GEOM_EXEMPT_MARKER: 单测里的单图几何，B 恒等于 A
            geom.update(ctx, ctx.photoScaleMode, a.width, a.height)
        """.trimIndent()
        assertTrue("豁免标记失效：加了标记仍被判违规", violationsOf(src).isEmpty())
    }

    // ═══════════════════ B. 转场不得在 p 接近 1 时跳过新图 ═══════════════════

    @Test
    fun `转场不得在 p 接近 1 时跳过新图绘制`() {
        val dir = transitionsDir()
        var files = 0
        var bDraws = 0
        val violations = mutableListOf<String>()

        dir.listFiles { f: File -> f.isFile && f.extension == "kt" }?.sorted()?.forEach { file ->
            files++
            val raw = file.readText()
            val clean = stripComments(raw)
            bDraws += Regex("""drawPhoto(?:At)?\(\s*b\b""").findAll(clean).count()
            val rel = "transitions/${file.name}"
            for (line in skipViolations(clean, raw)) {
                violations += "$rel:$line"
            }
        }

        assertTrue("没有扫到任何转场实现文件 —— 目录定位可能失败：${dir.absolutePath}", files > 0)
        // ⚠️ 空转自证：一个「画新图」的点都没扫到 → 扫描逻辑失配
        assertTrue(
            "没有扫到任何 `drawPhoto(b, ...)` —— 扫描逻辑可能失配（空转），结果不可信",
            bDraws > 0,
        )

        if (violations.isNotEmpty()) {
            fail(
                "以下转场在 `p` 还没到 1 时用 `continue` / `return` 跳过了新图绘制：\n" +
                    violations.joinToString("\n") { "  • $it" } +
                    "\n\n`p` 到 1 之后**整个 HOLD 期都是 1** ⇒ 跳过新图等于「停留期一直在显示上一张照片」，" +
                    "下一次切换才补上新图（用户读作「入场动画没走完就停留」）。\n" +
                    "修法：把「跳过」改成「整幅画一次新图」——\n" +
                    "  if (1f - p <= SETTLED) { drawPhoto(b, geom.srcB, geom.dstB); return }\n" +
                    "（参考 GlitchTransition 的 SETTLED 分支、ScanlineDissolveTransition 的 p >= 1f 分支）",
            )
        }
    }

    /** 修复前的 `GlitchTransition` 写法 —— 必须命中 */
    @Test
    fun `p 接近 1 时 continue 跳过条带必须被判违规`() {
        val src = """if (abs(offset) < 0.5f && p > 0.95f) continue"""
        assertTrue("护栏失效：旧 GlitchTransition 写法没被判违规", skipViolationsOf(src).isNotEmpty())
    }

    /** `p > 0.9f` 形式的 `return` 同样必须命中（不能只认 `continue`） */
    @Test
    fun `p 接近 1 时 return 也必须被判违规`() {
        val src = """if (p > 0.9f) return"""
        assertTrue("护栏失效：`p > 0.9f` 形式的 return 没被判违规", skipViolationsOf(src).isNotEmpty())
    }

    /** 合法的终帧兜底（`p >= 1f` 时整幅画新图）—— 必须放行 */
    @Test
    fun `p 到 1 的终帧兜底必须放行`() {
        val src = """
            drawPhoto(a, geom.srcA, geom.dstA)
            if (p >= 1f) {
                drawPhoto(b, geom.srcB, geom.dstB)
                return
            }
        """.trimIndent()
        assertTrue("误报：`p >= 1f` 的终帧兜底被判违规", skipViolationsOf(src).isEmpty())
    }

    /** 与 `p` 无关的跳过（逐块 / 逐条的「还没轮到我」）—— 必须放行 */
    @Test
    fun `与 p 无关的 continue 必须放行`() {
        val src = """
            val local = ((p - delay) / denom).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val grow = size * Easing.easeOutQuad(local)
            if (grow <= 0f) continue
        """.trimIndent()
        assertTrue("误报：与 p 无关的 `continue` 被判违规", skipViolationsOf(src).isEmpty())
    }

    /** 误报防线：注释里举例的写法不算违规 */
    @Test
    fun `注释里举例的跳过写法不得被误判`() {
        val src = """
            // 反例：if (abs(offset) < 0.5f && p > 0.95f) continue
            val x = 1
        """.trimIndent()
        assertTrue("误报：注释里的举例被判违规（注释剥离失效）", skipViolationsOf(src).isEmpty())
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    private data class UpdateSite(val args: Int, val line: Int)

    /**
     * 抽出一处 `PhotoGeometry.update(...)` 的实参个数与行号。
     *
     * ⛔ 「这是不是 `PhotoGeometry.update`」**必须用两个锚点取并集**判定。
     * 只认「实参里出现 `photoScaleMode`」会**静默漏扫**：把模式存进局部变量
     * （`val mode = ctx.photoScaleMode` → `geom.update(ctx, mode, …)`）或换个实参写法，
     * 调用点就扫不到了 —— 而漏扫的门禁**照样报「0 违规」**，那是**空转**，不是「代码干净」。
     *
     * ⚠️ 这不是假想：本门禁第一版就是「只认 `photoScaleMode`」，自证用例
     * `同一行且嵌套调用的写法也必须被判违规`（实参写 `modeOf(settings)`）当场把它判了出来
     * —— 自证用例的价值正在于此。
     *
     * 锚点：
     * - **接收者**：项目里 `PhotoGeometry` 实例一律命名为 `geom` / `photoGeom` / `photoGeometry`
     * - **实参**：直接传 `ctx.photoScaleMode` 的写法
     *
     * `fun update(` 声明因接收者锚点要求 `geom.` 前缀而天然排除。
     */
    private fun updateArgLists(clean: String): List<UpdateSite> {
        val out = mutableListOf<UpdateSite>()
        for (m in UPDATE_CALL.findAll(clean)) {
            val open = clean.indexOf('(', m.range.first)
            if (open < 0) continue
            val close = matchDelimiter(clean, open, '(', ')')
            if (close < 0) continue
            val args = clean.substring(open + 1, close)
            val before = clean.substring(0, m.range.first)
            if (GEOM_RECEIVER.find(before) == null && !args.contains("photoScaleMode")) continue
            val line = before.count { it == '\n' } + 1
            out += UpdateSite(countTopLevelArgs(args), line)
        }
        return out
    }

    /** 顶层实参个数（跳过字符串、括号 / 中括号 / 大括号内的逗号） */
    private fun countTopLevelArgs(args: String): Int {
        if (args.isBlank()) return 0
        var count = 1
        var depth = 0
        var inStr = false
        var inChar = false
        var i = 0
        while (i < args.length) {
            val c = args[i]
            when {
                inStr -> {
                    if (c == '\\') { i += 2; continue }
                    if (c == '"') inStr = false
                }

                inChar -> {
                    if (c == '\\') { i += 2; continue }
                    if (c == '\'') inChar = false
                }

                c == '"' -> inStr = true
                c == '\'' -> inChar = true
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == ',' && depth == 0 -> count++
            }
            i++
        }
        return count
    }

    /**
     * **纯函数**：返回「在 `p` 接近 1 时跳过绘制」的违规行号（1-based）。
     *
     * 命中 = 某行出现 `continue` / `return`，且**本行或向前 3 行内**有
     * 「`p` 与 `[0.5, 1.0)` 区间的字面量做 `>` / `>=` 比较」。
     *
     * ⚠️ `p >= 1f`（字面量正好是 1）**刻意放行** —— 那是「终帧整幅画新图」的合法兜底写法。
     */
    private fun skipViolations(clean: String, raw: String): List<Int> {
        val hits = mutableListOf<Int>()
        val lines = clean.lines()
        lines.forEachIndexed { idx, line ->
            if (!SKIP_KEYWORD.containsMatchIn(line)) return@forEachIndexed
            val late = (maxOf(0, idx - 3)..idx).any { i ->
                LATE_P.findAll(lines.getOrElse(i) { "" }).any { m ->
                    val v = m.groupValues[1].toFloatOrNull()
                    v != null && v >= LATE_P_MIN && v < 1f
                }
            }
            if (!late) return@forEachIndexed
            val lineNo = idx + 1
            if (isExempt(raw, lineNo, SKIP_EXEMPT_MARKER)) return@forEachIndexed
            hits += lineNo
        }
        return hits
    }

    /** 豁免标记：写在违规行或其上方 3 行内 */
    private fun isExempt(raw: String, line: Int, marker: String): Boolean {
        val lines = raw.lines()
        return (maxOf(0, line - 4) until minOf(lines.size, line)).any { lines[it].contains(marker) }
    }

    private fun violationsOf(src: String): List<Int> =
        updateArgLists(stripComments(src))
            .filter { it.args < REQUIRED_UPDATE_ARGS }
            .filterNot { isExempt(src, it.line, GEOM_EXEMPT_MARKER) }
            .map { it.line }

    private fun skipViolationsOf(src: String): List<Int> = skipViolations(stripComments(src), src)

    /**
     * 定位 main 源码根目录。
     *
     * AGP 单测的 `user.dir` 是**模块目录**（`…/app`），但为稳妥起见同时尝试仓库根。
     * 定位失败时**直接失败**而不是跳过 —— 否则这道门禁会被静默禁用。
     */
    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertNotNull(
            "找不到源码目录（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found,
        )
        return found!!
    }

    private fun transitionsDir(): File {
        val dir = File(mainSourceRoot(), "visualizer/photo/transitions")
        assertTrue("找不到转场实现目录：${dir.absolutePath}", dir.isDirectory)
        return dir
    }
}

/** 豁免标记（几何入参）：写在调用行或其上方 3 行内，后跟理由 */
private const val GEOM_EXEMPT_MARKER = "PhotoGeomArgs-exempt"

/** 豁免标记（终帧跳过）：同上 */
private const val SKIP_EXEMPT_MARKER = "TransitionSettle-exempt"

/** `PhotoGeometry.update` 的实参个数（`ctx, mode, aW, aH, bW, bH`） */
private const val REQUIRED_UPDATE_ARGS = 6

/** 任何 `update(` 调用；是否属于 `PhotoGeometry` 由 [GEOM_RECEIVER] / 实参锚点判定 */
private val UPDATE_CALL = Regex("""\bupdate\(""")

/**
 * `PhotoGeometry` 实例的**接收者名**（`geom.` / `photoGeom.` / `photoGeometry.`，大小写不敏感）。
 *
 * 与「实参含 `photoScaleMode`」取**并集**使用 —— 单靠后者会漏扫（详见 `updateArgLists`）。
 * 项目里 `PhotoGeometry` 一律命名为 `geom`（`PhotoRenderer` 与 `PhotoTransition` 的
 * KDoc / 参数名都是 `geom`），所以这个锚点比字面量锚点稳。
 */
private val GEOM_RECEIVER = Regex("""(?i)(?:photo)?geom(?:etry)?\s*\.\s*$""")

/** `continue` / `return`（用词边界，避免匹配到 `returnValue` 之类的标识符） */
private val SKIP_KEYWORD = Regex("""\b(continue|return)\b""")

/** `p` 与字面量的 `>` / `>=` 比较；group(1) 是字面量 */
private val LATE_P = Regex("""\bp\s*(?:>=|>)\s*([0-9]*\.?[0-9]+)""")

/**
 * 「接近 1」的下界。
 *
 * 取 `0.5`（而不是 0.9）是**刻意放宽**：规则要拦的是「动画还没走完就按 `p` 的值把新图
 * 跳掉」这一类写法，而 `p > 0.5f` 这种分支同样会让后半程失去新图。
 * 确实需要在 `p ∈ [0.5, 1)` 跳过的，用豁免标记显式声明理由。
 */
private const val LATE_P_MIN = 0.5f

/**
 * 返回与 `text[start]`（值为 [open]）配对的 [close] 下标；找不到返回 -1。
 *
 * 处理字符串/字符字面量与转义 —— 否则 `Text(")")` 这类内容会让配对提前结束。
 */
private fun matchDelimiter(text: String, start: Int, open: Char, close: Char): Int {
    var depth = 0
    var inStr = false
    var inChar = false
    var i = start
    while (i < text.length) {
        val c = text[i]
        if (inStr) {
            if (c == '\\') { i += 2; continue }
            if (c == '"') inStr = false
        } else if (inChar) {
            if (c == '\\') { i += 2; continue }
            if (c == '\'') inChar = false
        } else {
            when {
                c == '"' -> inStr = true
                c == '\'' -> inChar = true
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        i++
    }
    return -1
}

/**
 * 把注释内容替换成空格（**保留长度与换行位置**，行号因此不变）。
 *
 * 字符串/字符字面量内的 `//` 不是注释 —— 必须先判字面量状态。
 */
private fun stripComments(src: String): String {
    val sb = StringBuilder(src.length)
    var i = 0
    var inStr = false
    var inChar = false
    var inLineComment = false
    var inBlockComment = false
    while (i < src.length) {
        val c = src[i]
        val n = if (i + 1 < src.length) src[i + 1] else '\u0000'
        when {
            inLineComment -> {
                if (c == '\n') { inLineComment = false; sb.append('\n') } else sb.append(' ')
                i++
            }

            inBlockComment -> {
                if (c == '*' && n == '/') { inBlockComment = false; sb.append("  "); i += 2 } else {
                    sb.append(if (c == '\n') '\n' else ' ')
                    i++
                }
            }

            inStr -> {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                if (c == '"') inStr = false
                i++
            }

            inChar -> {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                if (c == '\'') inChar = false
                i++
            }

            c == '/' && n == '/' -> { inLineComment = true; sb.append("  "); i += 2 }
            c == '/' && n == '*' -> { inBlockComment = true; sb.append("  "); i += 2 }
            else -> {
                if (c == '"') inStr = true
                if (c == '\'') inChar = true
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

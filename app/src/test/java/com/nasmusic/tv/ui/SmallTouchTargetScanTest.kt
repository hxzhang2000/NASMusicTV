package com.nasmusic.tv.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「小尺寸元素不得直接挂 `clickable`」的**门禁测试**。
 *
 * ## 为什么需要它
 *
 * 竖屏下 `LocalDensity` 被 `PHONE_UI_SCALE = 0.82` 缩放，物理 44dp 需要
 * **Compose ≥ 53.7dp**（见约定文档 §2.7）。但 `Modifier.size(8.dp).clickable { }`
 * 这种「小视觉元素直接当触摸目标」的写法，热区只有 6.6 物理 dp ——
 * 比 44/48 那些已知问题更糟，却**完全逃过 P0-26 的自查 grep**，
 * 因为那条 grep 只覆盖 `40~53dp` 区间。
 *
 * 真实案例（2026-09-19 review）：竖屏播放页 `PortraitModeIndicator` 的两个模式圆点
 * 写成 `.size(if (active) 8.dp else 6.dp) ... .clickable { }`。
 *
 * ## 为什么是单测而不是脚本
 *
 * 同类规则已有 Python 脚本 `audit_small_touch_target.py`（仓库根目录），
 * 但**脚本只能靠人记得跑**。这条规则在项目里已经漏过一次，故按
 * `ScreenUiModeCoverageTest` 同范式固化为门禁 —— 跑在已有的 `testDebugUnitTest` 里，
 * CI 已阻塞，零新增依赖。
 *
 * ## 判定规则
 *
 * 命中需**同时**满足：
 * ① 某行含 `.size(`，且其括号内容里**全部** `.dp` 字面量都 `< 40`；
 * ② **同一行**或同一 modifier 链（其后连续以 `.` 开头的行）里有 `clickable` / `combinedClickable`；
 * ③ 链上**没有**会把尺寸撑大的 modifier（`fillMaxSize` / `weight(` / `widthIn(min` …）；
 * ④ 不是 `Spacer(` / `Divider`（向前回看 3 行判定）；
 * ⑤ 不是整行注释（`//` / KDoc `*` / `/*`）。
 *
 * 合规写法（**不**命中）：外层承担热区、内层只做视觉 ——
 * `Box(Modifier.size(portraitTouchTarget(44.dp)).clickable { }) { Box(Modifier.size(8.dp)) }`
 * （`portraitTouchTarget(44.dp)` 含 44 → 不满足 ①，天然放行）。
 *
 * ## ⚠️ 本文件的三层自证（缺一不可）
 *
 * 1. **负向用例**（`小尺寸元素挂 clickable 必须被判违规`、`尺寸字面量写法也必须被判违规`）：
 *    证明"该命中时会命中"。没有它，"扫描通过"可能只是因为扫描逻辑空转。
 * 2. **误报防线**（`注释里举例的 size 加 clickable 不得被误判`）：加了同行判定后，
 *    注释里的举例会命中同行规则 → 必须有反例证明已正确排除注释。
 * 3. **空转断言**（真实扫描里断言扫到的 `.size(` 链数 > 0）：源目录定位失败或
 *    正则失配时直接失败，而不是静默通过。
 *
 * 约定见 `docs/conventions-adaptive-ui.md` §6.5 / §8。
 */
class SmallTouchTargetScanTest {

    // ─────────────────────────── 真实源码扫描 ───────────────────────────

    @Test
    fun `小尺寸元素不得直接挂 clickable`() {
        val root = mainSourceRoot()
        val violations = mutableListOf<String>()
        var scanned = 0
        var sizeChains = 0

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                scanned++
                val lines = file.readLines()
                sizeChains += lines.count { it.contains(TARGET_CALL) }
                if (lines.any { it.contains(EXEMPT_MARKER) }) return@forEach
                val rel = file.relativeTo(root).path.replace('\\', '/')
                smallTouchTargetHits(lines).forEach { ln -> violations += "$rel:$ln" }
            }

        assertTrue(
            "没有扫到任何源码文件 —— 源目录定位可能失败：${root.absolutePath}",
            scanned > 0,
        )
        // ⚠️ 空转自证：一条 `.size(` 都没有 → 说明扫描逻辑失配（而非"代码干净"）
        assertTrue(
            "没有扫到任何 `$TARGET_CALL` —— 扫描逻辑可能失配（空转），结果不可信",
            sizeChains > 0,
        )

        if (violations.isNotEmpty()) {
            fail(
                "以下位置把小尺寸元素直接当成了触摸目标（热区远小于 44 物理 dp）：\n" +
                    violations.joinToString("\n") { "  • $it" } +
                    "\n\n修法：视觉元素与热区分离 —— 外层承担热区、内层只做视觉：\n" +
                    "  Box(Modifier.size(portraitTouchTarget(44.dp)).clickable { }) {\n" +
                    "      Box(Modifier.size(8.dp))   // 内层无手势\n" +
                    "  }\n" +
                    "确实属于装饰性元素（不接收点击）的，去掉 `.clickable`；\n" +
                    "确需豁免的文件在顶部加 `$EXEMPT_MARKER: <理由>` 注释。\n" +
                    "约定见 docs/conventions-adaptive-ui.md §6.5。",
            )
        }
    }

    // ─────────────────── 负向用例：护栏本身必须有效 ───────────────────

    @Test
    fun `小尺寸元素挂 clickable 必须被判违规`() {
        val hits = smallTouchTargetHits(
            """
            Box(
                modifier = Modifier
                    .size(if (active) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .clickable { onSwitch(m) }
            )
            """.trimIndent().lines(),
        )
        assertTrue("护栏失效：小尺寸 + clickable 没被判违规（表达式尺寸是头号空转来源）", hits.isNotEmpty())
    }

    @Test
    fun `尺寸字面量写法也必须被判违规`() {
        val hits = smallTouchTargetHits(
            """
            Box(modifier = Modifier.size(8.dp).clickable { x() })
            """.trimIndent().lines(),
        )
        assertTrue("护栏失效：`size(8.dp).clickable` 没被判违规", hits.isNotEmpty())
    }

    /**
     * 与项目根 `audit_small_touch_target.py` 的 `注释里举例的 ... 不算违规` 用例同源。
     *
     * 加同行判定后的**误报防线**：KDoc / 行尾注释里举例的 `.size(8.dp).clickable`
     * 不是真实代码，不得被判违规。
     */
    @Test
    fun `注释里举例的 size 加 clickable 不得被误判`() {
        val hits = smallTouchTargetHits(
            """
            // 反例：Box(Modifier.size(8.dp).clickable { })
            """.trimIndent().lines(),
        )
        assertTrue("误报：注释行里的举例被判违规（注释剥离失效）", hits.isEmpty())
    }

    @Test
    fun `受认可的 portraitTouchTarget 写法必须放行`() {
        val hits = smallTouchTargetHits(
            """
            Box(
                modifier = Modifier
                    .size(portraitTouchTarget(44.dp))
                    .clickable { onSwitch(m) }
            )
            """.trimIndent().lines(),
        )
        assertTrue("误报：portraitTouchTarget 写法被当成违规", hits.isEmpty())
    }

    @Test
    fun `Spacer 与 Divider 不算触摸目标`() {
        // ⚠️ `Spacer(` 写在 modifier 链的**开头**（命中行的前几行）—— 只查当前行会漏
        val hits = smallTouchTargetHits(
            """
            Spacer(
                modifier = Modifier
                    .size(8.dp)
                    .clickable { x() }
            )
            """.trimIndent().lines(),
        )
        assertTrue("误报：Spacer 被当成触摸目标", hits.isEmpty())
    }

    @Test
    fun `没有 clickable 的纯视觉元素必须放行`() {
        val hits = smallTouchTargetHits(
            """
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.Red)
            )
            """.trimIndent().lines(),
        )
        assertTrue("误报：无手势的纯视觉元素被判违规", hits.isEmpty())
    }

    @Test
    fun `链上被撑大的尺寸必须放行`() {
        val hits = smallTouchTargetHits(
            """
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .fillMaxSize()
                    .clickable { x() }
            )
            """.trimIndent().lines(),
        )
        assertTrue("误报：链上有 fillMaxSize 撑大的写法被判违规", hits.isEmpty())
    }

    @Test
    fun `无字面量的尺寸表达式必须放行`() {
        // 尺寸是纯变量时无法判断，跳过而非误报
        val hits = smallTouchTargetHits(
            """
            Box(
                modifier = Modifier
                    .size(coverSide)
                    .clickable { x() }
            )
            """.trimIndent().lines(),
        )
        assertTrue("误报：无字面量的尺寸表达式被判违规", hits.isEmpty())
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    /**
     * 定位 main 源码根目录（扫描范围与脚本 `audit_small_touch_target.py` 一致）。
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
}

/** 豁免标记：写在文件顶部注释里，后跟理由 */
private const val EXEMPT_MARKER = "NasSmallTouchTarget-exempt"

/** 要扫的调用（只扫 `.size(`；`.height(` / `.width(` 由 §8 的 grep 兜底） */
private const val TARGET_CALL = ".size("

/** 字面量小于它才算「小尺寸」 */
private const val SMALL_LIMIT = 40.0

/** 最多向后看多少行仍属同一条 modifier 链 */
private const val CHAIN_LOOKAHEAD = 12

private val DP_LITERAL = Regex("""(\d+(?:\.\d+)?)\.dp""")

private val GESTURE = Regex("""^\s*\.(clickable|combinedClickable)""")

/**
 * **同行**写法：`Modifier.size(8.dp).clickable { }`。
 *
 * `GESTURE` 的 `^\s*\.` 锚点只认行首，匹配不到行中的 `.clickable` —— 首版护栏只扫
 * `.size(` **之后的行**，于是单行写法整条漏掉（负向用例 `尺寸字面量写法也必须被判违规`
 * 正是为此设的，曾因此报红）。判定逻辑与项目根 `audit_small_touch_target.py` 的
 * `CLICK_SAME_LINE` 逐字一致。
 */
private val GESTURE_SAME_LINE = Regex("""\.(clickable|combinedClickable)\b""")

/** 出现任一即说明实际尺寸会被撑大 → 不算违规 */
private val WIDENING = listOf(
    "fillMaxSize", "fillMaxWidth", "fillMaxHeight",
    "weight(", "widthIn(min", "heightIn(min",
)

/**
 * **纯函数**：返回命中的行号列表（1-based）。
 *
 * 移植自 `audit_small_touch_target.py` 的 `scan_lines()`，判定规则逐条一致。
 */
private fun smallTouchTargetHits(lines: List<String>): List<Int> {
    val hits = mutableListOf<Int>()
    for (i in lines.indices) {
        val line = lines[i]
        val idx = line.indexOf(TARGET_CALL)
        if (idx < 0) continue
        // 注释行不参与判定 —— 否则 KDoc / 说明里举例的 `.size(8.dp).clickable` 会被误报
        if (isCommentLine(line)) continue
        if (isNonInteractive(lines, i)) continue

        // 取 `.size(` 的**括号配对内容** —— 直接上正则匹配不到
        // `size(if (a) 8.dp else 6.dp)` 这类**表达式尺寸**，会静默空转
        val openP = line.indexOf('(', idx)
        var blob = line.substring(openP)
        var end = parenSpan(blob, 0)
        if (end < 0) {
            // 尺寸表达式可能跨行 → 向后拼接再配对
            for (k in (i + 1) until minOf(lines.size, i + 4)) {
                blob += "\n" + lines[k]
                end = parenSpan(blob, 0)
                if (end >= 0) break
            }
        }
        if (end < 0) continue

        val literals = DP_LITERAL.findAll(blob.substring(1, end))
            .map { it.groupValues[1].toDouble() }
            .toList()
        if (literals.isEmpty()) continue          // 纯变量 → 无法判断，跳过
        if (literals.any { it >= SMALL_LIMIT }) continue

        // 手势可能在**同一行**（`Modifier.size(8.dp).clickable { }`），也可能在**后续行**
        // （modifier 链纵向写）。⚠️ 只看后续行会漏掉单行写法 —— 首版护栏正是在此空转。
        val chain = chainAfter(lines, i)
        val gestureOnSameLine =
            GESTURE_SAME_LINE.containsMatchIn(line.substring(idx).substringBefore("//"))
        if (!gestureOnSameLine && chain.none { GESTURE.containsMatchIn(it) }) continue

        val window = line + "\n" + chain.joinToString("\n")
        if (WIDENING.any { window.contains(it) }) continue

        hits += i + 1
    }
    return hits
}

/**
 * 返回与 `text[openIdx] == '('` 配对的 `')'` 下标；处理字符串字面量与嵌套。
 * 配对失败返回 `-1`。
 */
private fun parenSpan(text: String, openIdx: Int): Int {
    var depth = 0
    var i = openIdx
    var inStr = false
    while (i < text.length) {
        val ch = text[i]
        if (inStr) {
            if (ch == '\\') {
                i += 2
                continue
            }
            if (ch == '"') inStr = false
        } else {
            if (ch == '"') {
                inStr = true
            } else if (ch == '(') {
                depth++
            } else if (ch == ')') {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

/**
 * 是否为**整行注释**（`//` / KDoc 的 `*` / `/*`）。
 *
 * 与 `isNonInteractive` 并列的必要性：加了同行手势判定后，KDoc 里举例的
 * `Box(Modifier.size(8.dp).clickable { })` 会命中同行规则 → 必须先排除注释行。
 */
private fun isCommentLine(line: String): Boolean {
    val t = line.trimStart()
    return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
}

/**
 * `Spacer(` / `Divider` 常写在 `.size(...)` 的**前几行**（modifier 链的开头），
 * 只检查当前行会漏 → 向前回看 3 行。
 */
private fun isNonInteractive(lines: List<String>, i: Int): Boolean {
    for (k in maxOf(0, i - 3)..i) {
        if (lines[k].contains("Spacer(") || lines[k].contains("Divider")) return true
    }
    return false
}

/**
 * 收集命中行**之后**连续以 `.` 开头的行（同一条 modifier 链），遇到手势即止。
 * modifier 链是**纵向**写的，只看一行判不出链上有没有 `clickable`。
 */
private fun chainAfter(lines: List<String>, i: Int, maxLines: Int = CHAIN_LOOKAHEAD): List<String> {
    val chain = mutableListOf<String>()
    var j = i + 1
    while (j < lines.size && chain.size < maxLines) {
        val nxt = lines[j]
        if (!nxt.trimStart().startsWith(".")) break
        chain += nxt
        if (GESTURE.containsMatchIn(nxt)) break
        j++
    }
    return chain
}

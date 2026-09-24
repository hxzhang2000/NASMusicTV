package com.nasmusic.tv.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「`FocusableSurface` 的内容不得用 `fillMaxSize()` 撑满行内剩余宽度」的**门禁测试**。
 *
 * ## 为什么需要它
 *
 * `Row` 给每个 wrap-content 子项的 `maxWidth` 是**本行剩余宽度**。若子项内容用了
 * `fillMaxSize()`，它会取这个最大值 ⇒ **第一个子项撑满整行，同排后续子项被挤成 0 宽**
 * （不报错、不警告，只是"选项不见了"）。
 *
 * 真实案例：v2.37.0 照片墙设置「画面适配」只显示「满屏」，「完整」不可见
 * （2026-09-24 用户上机发现）。同一个坑在 v2.35.0 已发生过一次 ——
 * `SettingActionButton` 默认 `fillMaxWidth()`，表现为「网络源音质只有『自动』一个选项」，
 * 当时的修法是"放在 Row 里时由调用方传固定宽度"（见 `SettingsComponents.kt` 的 KDoc）。
 *
 * ## 为什么是单测而不是脚本
 *
 * 与 `SmallTouchTargetScanTest` 同范式：跑在已有的 `testDebugUnitTest` 里，CI 已阻塞，
 * 零新增依赖（项目无 `compose-ui-test`，无法做真实布局断言）。
 *
 * ## 判定规则
 *
 * 命中需**同时**满足：
 * ① 某处 `FocusableSurface(` 调用的**实参区**（括号配对，不含尾随 lambda）里
 *    **没有**任何显式给定宽度的修饰符（`fillMaxWidth(` / `.width(` / `widthIn(` /
 *    `weight(` / `.size(`），且 `modifier =` 的**首行以 `Modifier` 开头**
 *    （宽度由调用方/变量传入时静态判不出来 → **跳过**，同 `SmallTouchTargetScanTest`
 *    对无字面量尺寸表达式的处理）；
 * ② 该调用的**内容 lambda**（大括号配对）里含 `fillMaxSize()`；
 * ③ 该调用所在行（或向前 3 行内）**没有**豁免标记 `ChipWidth-exempt: <理由>`；
 * ④ 注释（`//` 与 `/* … */`）先被剥离 —— 注释里举例的写法不算违规。
 *
 * ⚠️ `modifier = Modifier.height(...)` 且内容 `fillMaxSize()` 在**竖排**容器里是合法用法
 * （整行按钮），此时用豁免标记显式声明理由，不要放宽规则。
 *
 * ## 自证（缺一不可）
 *
 * 1. **负向用例**：修复前的写法（外层只给 height + 内容 `fillMaxSize()`）必须被判违规 ——
 *    没有它，"扫描通过"可能只是因为扫描逻辑空转。
 * 2. **同行写法用例**：压成一行的写法同样必须被判违规（正则/配对逻辑最容易漏这类）。
 * 3. **误报防线**：注释里举例的写法不得被误判（剥离注释生效）；宽度由变量传入的必须放行。
 * 4. **豁免标记用例**：标记必须真的能放行。
 * 5. **空转断言**：真实扫描里断言扫到的 `FocusableSurface(` 调用点数 > 0。
 *
 * 约定见 `docs/technical-overview.md` §10.181。
 */
class ChipContentWidthScanTest {

    // ─────────────────────────── 真实源码扫描 ───────────────────────────

    @Test
    fun `FocusableSurface 内容不得用 fillMaxSize 撑满行内剩余宽度`() {
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
                callSites += CALL.findAll(clean).count()
                val rel = file.relativeTo(root).path.replace('\\', '/')
                chipWidthViolations(clean, raw).forEach { violations += "$rel:$it" }
            }

        assertTrue(
            "没有扫到任何源码文件 —— 源目录定位可能失败：${root.absolutePath}",
            scanned > 0,
        )
        // ⚠️ 空转自证：一个调用点都没扫到 → 扫描逻辑失配（而非"代码干净"）
        assertTrue(
            "没有扫到任何 `FocusableSurface(` 调用点 —— 扫描逻辑可能失配（空转），结果不可信",
            callSites > 0,
        )

        if (violations.isNotEmpty()) {
            fail(
                "以下 `FocusableSurface` 的内容用了 `fillMaxSize()`，宽度会随「行内剩余宽度」膨胀：\n" +
                    violations.joinToString("\n") { "  • $it" } +
                    "\n\n症状：放在 `Row` 里时**第一个子项撑满整行、后续子项被挤成 0 宽**" +
                    "（表现为「选项不见了」，见 v2.35.0 音质档、v2.37.0 画面适配两次实例）。\n" +
                    "修法：内容容器只填高、宽度交给文字与 padding ——\n" +
                    "  Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { Text(...) }\n" +
                    "宽度确实由调用方/变量决定、静态判不出来的，在调用上方加 " +
                    "`// $EXEMPT_MARKER: <理由>`。",
            )
        }
    }

    // ─────────────────── 负向用例：护栏本身必须有效 ───────────────────

    /** 修复前的 `OptionChip` 写法 —— 必须命中（这是本门禁存在的理由） */
    @Test
    fun `外层只给高度且内容 fillMaxSize 必须被判违规`() {
        val src = """
            FocusableSurface(
                onClick = onClick,
                modifier = Modifier.height(portraitTouchTarget(48.dp)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = label)
                }
            }
        """.trimIndent()
        val hits = chipWidthViolations(stripComments(src), src)
        assertTrue("护栏失效：外层只给高度 + 内容 fillMaxSize 没被判违规", hits.isNotEmpty())
    }

    /** 压成一行的写法 —— 配对逻辑最容易漏这类 */
    @Test
    fun `同一行的写法也必须被判违规`() {
        val src = """FocusableSurface(onClick = {}, modifier = Modifier.height(48.dp)) { Box(Modifier.fillMaxSize()) }"""
        val hits = chipWidthViolations(stripComments(src), src)
        assertTrue("护栏失效：单行写法没被判违规", hits.isNotEmpty())
    }

    /** 修复后的 `OptionChip` 写法 —— 必须放行 */
    @Test
    fun `内容只填高且宽度由文字决定必须放行`() {
        val src = """
            FocusableSurface(
                onClick = onClick,
                modifier = Modifier.height(portraitTouchTarget(48.dp)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = label)
                }
            }
        """.trimIndent()
        val hits = chipWidthViolations(stripComments(src), src)
        assertTrue("误报：只填高的 chip 被判违规", hits.isEmpty())
    }

    /** 外层显式给宽度（全宽按钮/搜索框）—— 必须放行 */
    @Test
    fun `外层显式给出宽度必须放行`() {
        val src = """
            FocusableSurface(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Row(modifier = Modifier.fillMaxSize()) { Text(text = label) }
            }
        """.trimIndent()
        val hits = chipWidthViolations(stripComments(src), src)
        assertTrue("误报：外层已给 fillMaxWidth 的写法被判违规", hits.isEmpty())
    }

    /** 外层显式给尺寸（圆形按钮）—— 必须放行 */
    @Test
    fun `外层显式给出尺寸必须放行`() {
        val src = """
            FocusableSurface(
                onClick = onClick,
                modifier = Modifier.size(48.dp),
                shape = CircleShape
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(null) }
            }
        """.trimIndent()
        val hits = chipWidthViolations(stripComments(src), src)
        assertTrue("误报：外层已给 size 的写法被判违规", hits.isEmpty())
    }

    /** 豁免标记必须真的能放行（否则标记形同虚设，人会开始乱改规则） */
    @Test
    fun `豁免标记必须生效`() {
        // ⚠️ 这里刻意用 `Modifier.height(...)`（字面量链）—— 否则会被「变量宽度跳过」规则放行，
        //    用例就证明不了标记本身有效
        val src = """
            // $EXEMPT_MARKER: 该 chip 始终独占整行（竖排容器），fillMaxSize 是预期行为
            FocusableSurface(
                onClick = onClick,
                modifier = Modifier.height(48.dp)
            ) {
                Row(modifier = Modifier.fillMaxSize()) { Text(text = label) }
            }
        """.trimIndent()
        val hits = chipWidthViolations(stripComments(src), src)
        assertTrue("豁免标记失效：加了标记仍被判违规", hits.isEmpty())
    }

    /** 宽度由调用方/变量传入 → 静态判不出，必须跳过而非误报 */
    @Test
    fun `宽度由变量传入必须放行`() {
        val src = """
            FocusableSurface(
                onClick = onClick,
                modifier = glowModifier.then(Modifier.focusRequester(fr))
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(null) }
            }
        """.trimIndent()
        val hits = chipWidthViolations(stripComments(src), src)
        assertTrue("误报：宽度由变量传入的写法被判违规", hits.isEmpty())
    }

    /** 误报防线：注释里举例的写法不算违规 */
    @Test
    fun `注释里举例的写法不得被误判`() {
        val src = """
            // 反例：FocusableSurface(modifier = Modifier.height(48.dp)) { Box(Modifier.fillMaxSize()) }
            val x = 1
        """.trimIndent()
        val hits = chipWidthViolations(stripComments(src), src)
        assertTrue("误报：注释里的举例被判违规（注释剥离失效）", hits.isEmpty())
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

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
}

/** 豁免标记：写在调用行或其上方 3 行内，后跟理由 */
private const val EXEMPT_MARKER = "ChipWidth-exempt"

/** 要扫的调用 */
private val CALL = Regex("""\bFocusableSurface\(""")

/** 出现任一即说明宽度已被显式给定 → 不算违规 */
private val WIDTH_GIVEN = listOf("fillMaxWidth(", ".width(", "widthIn(", "weight(", ".size(")

/** 取 `modifier =` 的值（只取首行，足以判断是不是 `Modifier.` 字面量链） */
private val MODIFIER_ARG = Regex("""modifier\s*=\s*([^\n,]+)""")

private const val FILL_MAX_SIZE = "fillMaxSize()"

/**
 * **纯函数**：返回违规行号（1-based）。
 *
 * @param clean 已剥离注释的源码（用于扫描，长度与原文一致、换行位置一致）
 * @param raw   原始源码（用于查找豁免标记 —— 标记本身写在注释里）
 */
private fun chipWidthViolations(clean: String, raw: String): List<Int> {
    val hits = mutableListOf<Int>()
    val rawLines = raw.lines()

    for (m in CALL.findAll(clean)) {
        val open = clean.indexOf('(', m.range.first)
        if (open < 0) continue
        val close = matchDelimiter(clean, open, '(', ')')
        if (close < 0) continue

        // ① 实参区里必须没有显式宽度（尾随 lambda 不在括号内，天然被排除）
        val args = clean.substring(open + 1, close)
        if (WIDTH_GIVEN.any { args.contains(it) }) continue

        // ①' `modifier =` 不是 `Modifier.` 开头的字面量链（而是调用方参数/变量）→
        //     静态判不出宽度，跳过而非误报（同 SmallTouchTargetScanTest 对 `size(coverSide)` 的处理）
        val modifierExpr = MODIFIER_ARG.find(args)?.groupValues?.get(1)?.trim()
        if (modifierExpr != null && !modifierExpr.startsWith("Modifier")) continue

        // ② 内容 lambda 里含 fillMaxSize()
        var j = close + 1
        while (j < clean.length && clean[j].isWhitespace()) j++
        if (j >= clean.length || clean[j] != '{') continue
        val bodyEnd = matchDelimiter(clean, j, '{', '}')
        if (bodyEnd < 0) continue
        if (!clean.substring(j + 1, bodyEnd).contains(FILL_MAX_SIZE)) continue

        val line = clean.substring(0, m.range.first).count { it == '\n' } + 1
        // ③ 豁免标记（本行或向前 3 行 —— 标记常写在调用上方）
        val exempt = (maxOf(0, line - 4) until minOf(rawLines.size, line))
            .any { rawLines[it].contains(EXEMPT_MARKER) }
        if (exempt) continue

        hits += line
    }
    return hits
}

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

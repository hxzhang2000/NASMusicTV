package com.nasmusic.tv.util

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「源码不得有**未闭合的块注释**」的门禁测试
 *
 * ## 为什么需要它
 *
 * Kotlin 的块注释**支持嵌套**（与 Java 不同）。因此只要 KDoc 里出现
 * 「斜杠紧邻星号」的文本 —— 最常见的是**路径通配符** —— 就会在注释内部
 * 又开一层注释，而它**没有对应的结束符**，于是**吞掉其后全部代码**。
 *
 * 真实事故（本项目已发生两次）：
 * - 2026-09-20：KDoc 里裸写块注释起始符
 * - 2026-09-23：KDoc 里写 ``探测（`/mnt/media_rw/` + 星号）`` ——
 *   那个「斜杠 + 星号」被当成嵌套注释开始符
 *
 * **症状极具误导性**：Kotlin 报 `Syntax error: Unclosed comment`，
 * 行号落在**文件 EOF**（实测报 `:149:1`，而文件只有 148 行），
 * 且**依赖该文件的其他文件**会报一堆 `Unresolved reference` ——
 * 看起来像「新文件没被编译」，实际是这一处语法问题。若只看 `tail` 截断的输出，
 * 这些 `Unresolved` 会把真正的根因挤到屏幕外（实测被 `tail -40` 切掉）。
 *
 * ## 判定方式：真词法扫描，不是数注释符号的个数
 *
 * ⛔ **不能用计数法** —— 项目里 7 个文件在**字符串字面量里**含 glob 模式
 * （形如 双星号 + 斜杠 + 星号 + `.kt`），计数法会把它们全部误报。
 * 必须跳过：行注释 / 块注释（带嵌套深度）/ 普通字符串 / 字符字面量 / 三引号原始字符串。
 *
 * ⚠️ **本护栏覆盖「未闭合」，不覆盖「提前闭合」**：
 * KDoc 正文里若出现注释结束符，KDoc 会**提前关闭**，其后文字被当代码解析
 * ⇒ 编译器立刻报一堆 `Expecting a top level declaration`（自证明显，不需要护栏）。
 * 本护栏专治另一种 —— **未闭合**（报错行号落在文件 EOF，极易被误读成别的问题）。
 * ⛔ 本文件自己的 KDoc 因此**刻意不写**任何注释定界符字面量。
 *
 * ## 自证（缺一不可）
 *
 * 1. **负向自证**：路径通配符造成的未闭合**必须**被检出（否则护栏是空转的）
 * 2. **误报防线**：字符串里的 glob、合法嵌套块注释、KDoc 里避开通配的写法**必须**放行
 * 3. **空转断言**：真实扫描必须扫到 > 0 个文件、> 0 个块注释起始符
 */
class KotlinBlockCommentBalanceTest {

    // ─────────────────────────── 真实源码扫描 ───────────────────────────

    @Test
    fun `源码不得存在未闭合的块注释`() {
        val roots = sourceRoots()
        val violations = mutableListOf<String>()
        var scanned = 0
        var openers = 0

        for (root in roots) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    scanned++
                    val src = file.readText()
                    val r = scanBlockComments(src)
                    openers += r.openers
                    val at = r.unclosedAt
                    if (at != null) {
                        val line = src.take(at).count { it == '\n' } + 1
                        violations += "${file.relativeTo(root).path.replace('\\', '/')}:$line"
                    }
                }
        }

        // ⚠️ 空转自证：扫不到文件 / 扫不到注释 ⇒ 说明定位或扫描失配，结果不可信
        assertTrue(
            "没有扫到任何 .kt 文件 —— 源码目录定位失败（user.dir=${File("").absolutePath}）",
            scanned > 0,
        )
        assertTrue("没有扫到任何块注释起始符 —— 词法扫描可能失配（空转）", openers > 0)

        assertTrue(
            "以下文件存在**未闭合的块注释**（Kotlin 块注释支持嵌套；常见根因是 KDoc 里" +
                "写了路径通配符，斜杠与星号相邻）：\n" +
                violations.joinToString("\n") +
                "\n⇒ 修法：把通配写法拆开（写 `/mnt/usb0` 而不是 `路径 + 斜杠 + 星号`）",
            violations.isEmpty(),
        )
    }

    // ─────────────────────────── 负向自证 ───────────────────────────

    @Test
    fun `负向自证 - KDoc 里的路径通配符必须被判出未闭合`() {
        // ⛔ 这不是假想场景：2026-09-23 实际这么写了，报错行号落在文件 EOF
        val buggy = buildString {
            appendLine("package foo")
            appendLine()
            appendLine("/**")
            appendLine(" * 常见挂载点探测（`/mnt/usb0`、`/mnt/media_rw/" + "*`）。")
            appendLine(" */")
            appendLine("object A { fun f() = 1 }")
        }
        val r = scanBlockComments(buggy)
        assertTrue("路径通配符造成的未闭合必须被检出（否则护栏空转）", r.unclosedAt != null)
        // 起点报的是**最外层**注释的起始处 —— 它才是那个「没闭合的注释」。
        // （内层那个误开的 `/*` 只是把外层拖下水，指向它反而不好定位。）
        val line = buggy.take(r.unclosedAt!!).count { it == '\n' } + 1
        assertTrue("未闭合起点应落在第 3 行（外层 KDoc 起始），实际第 $line 行", line == 3)
        // openers 只统计**顶层**块注释起始符（嵌套的不计），此处只有那个 KDoc
        assertTrue("应扫到 1 个顶层块注释起始符，实际 ${r.openers}", r.openers == 1)
    }

    @Test
    fun `嵌套语义自证 - 内层已闭合而外层未闭合必须判出未闭合`() {
        // ⛔ 这个 fixture 专门用来区分「是否真的支持嵌套」：
        //    支持嵌套 → `/*`(1) → `/*`(2) → `*/`(1) → 永不归零 → **未闭合**
        //    不支持嵌套 → 第一个 `*/` 就把注释关掉 → 判定为「正常」（漏报）
        val nestedUnclosed = buildString {
            appendLine("package foo")
            appendLine("/* 外层 /* 内层" + " */")
            appendLine("object A { fun f() = 1 }")
        }
        assertTrue(
            "内层闭合、外层未闭合 —— 必须判出未闭合（否则说明扫描没实现嵌套语义，会漏报）",
            scanBlockComments(nestedUnclosed).unclosedAt != null,
        )
    }

    // ─────────────────────────── 误报防线 ───────────────────────────

    @Test
    fun `误报防线 - 字符串字面量里的 glob 不得被判出未闭合`() {
        val ok = buildString {
            appendLine("package foo")
            appendLine("object A {")
            appendLine("    val g = \"**/" + "*.kt\"")
            appendLine("    val h = \"src/main/**/" + "*.kt\"")
            appendLine("}")
        }
        assertTrue(
            "字符串字面量里的 glob 不能被误判（项目里有 7 个这样的文件）",
            scanBlockComments(ok).unclosedAt == null,
        )
    }

    @Test
    fun `误报防线 - 合法嵌套块注释必须放行`() {
        val ok = buildString {
            appendLine("package foo")
            appendLine("/* 外层 /* 嵌套 */ 结束 */")
            appendLine("object A { fun f() = 1 }")
        }
        assertTrue("合法的嵌套块注释必须放行", scanBlockComments(ok).unclosedAt == null)
    }

    @Test
    fun `误报防线 - KDoc 里避开通配的写法必须放行`() {
        val ok = buildString {
            appendLine("package foo")
            appendLine("/**")
            appendLine(" * 探测 `/mnt/media_rw/` 下的子目录。")
            appendLine(" */")
            appendLine("object A { fun f() = 1 }")
        }
        assertTrue("避开通配的 KDoc 必须放行", scanBlockComments(ok).unclosedAt == null)
    }

    @Test
    fun `误报防线 - 三引号原始字符串与字符字面量里的斜杠星号不得误判`() {
        val ok = buildString {
            appendLine("package foo")
            appendLine("object A {")
            appendLine("    val raw = \"\"\"path/**/" + "*.kt\"\"\"")
            appendLine("    val ch = '/'")
            appendLine("    val star = '*'")
            appendLine("}")
        }
        assertTrue(
            "三引号字符串 / 字符字面量里的斜杠星号不能被误判",
            scanBlockComments(ok).unclosedAt == null,
        )
    }

    // ─────────────────────────── 扫描实现 ───────────────────────────

    private class ScanResult(val unclosedAt: Int?, val openers: Int)

    /**
     * 词法扫描：返回**未闭合块注释的起始偏移**与块注释起始符个数。
     *
     * 跳过（按 Kotlin 词法）：双斜杠行注释、块注释（带嵌套深度）、
     * 三引号原始字符串、双引号字符串（含反斜杠转义）、单引号字符字面量。
     */
    private fun scanBlockComments(src: String): ScanResult {
        val n = src.length
        var i = 0
        var depth = 0
        var start = -1
        var openers = 0

        while (i < n) {
            if (depth > 0) {
                when {
                    src.startsWith("/*", i) -> { depth++; i += 2 }
                    src.startsWith("*/", i) -> {
                        depth--
                        i += 2
                        if (depth == 0) start = -1
                    }
                    else -> i++
                }
                continue
            }
            when {
                src.startsWith("//", i) -> {
                    val j = src.indexOf('\n', i)
                    i = if (j < 0) n else j + 1
                }
                src.startsWith("/*", i) -> {
                    depth++
                    openers++
                    start = i
                    i += 2
                }
                src.startsWith("\"\"\"", i) -> {
                    val j = src.indexOf("\"\"\"", i + 3)
                    i = if (j < 0) n else j + 3
                }
                src[i] == '"' -> i = skipQuoted(src, i, '"')
                src[i] == '\'' -> i = skipQuoted(src, i, '\'')
                else -> i++
            }
        }
        return ScanResult(if (depth > 0) start else null, openers)
    }

    /** 从 [from] 处的引号开始，返回闭合引号之后的下标（含 `\` 转义） */
    private fun skipQuoted(src: String, from: Int, quote: Char): Int {
        var i = from + 1
        while (i < src.length) {
            val c = src[i]
            if (c == '\\') { i += 2; continue }
            if (c == quote) return i + 1
            i++
        }
        return src.length
    }

    /** 同时扫 main 与 test（新写的测试文件也可能踩同一个坑） */
    private fun sourceRoots(): List<File> {
        val pairs = listOf(
            "src/main/java/com/nasmusic/tv" to "src/test/java/com/nasmusic/tv",
            "app/src/main/java/com/nasmusic/tv" to "app/src/test/java/com/nasmusic/tv",
            "../app/src/main/java/com/nasmusic/tv" to "../app/src/test/java/com/nasmusic/tv",
        )
        for ((mainPath, testPath) in pairs) {
            val main = File(mainPath)
            if (!main.isDirectory) continue
            val test = File(testPath)
            return listOfNotNull(main, test.takeIf { it.isDirectory })
        }
        fail(
            "找不到源码目录（user.dir=${File("").absolutePath}）；" +
                "已尝试：${pairs.joinToString { it.first }}"
        )
        return emptyList()
    }
}

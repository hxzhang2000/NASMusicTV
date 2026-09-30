package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * §八 G13 —— 全量性能预算契约（S1.6 · T1.6.1/T1.6.2 的门禁，v1.10 新增）。
 *
 * **源码扫描段**（范围 = `renderers/` 与 `photo/` 两个目录（glob 模式 `**`））：
 *  ① 每帧可达代码（`override fun DrawScope.draw(` / `drawContent(` 与全部
 *     `fun DrawScope.<helper>(` 扩展体）内不得出现**字符串模板**（`"…$…"`，§四 G16 / T1.6.1）；
 *  ② 同范围内不得出现**带参 `Rect(`**（§四 G15 / T1.6.2 的 8 处 + Waterfall drawRect 1 处；
 *     无参 `Rect()` 是缓存缓冲字段构造，放行）；行级豁免标记 `// Perf-exempt: 理由`
 *     （如 E38 `drawCornerMask` 的尺寸缓存守卫路径）可放行。
 *  ③ 同范围内不得出现每帧容器/lambda 分配（`listOf(` / `mutableListOf(` /
 *     `arrayListOf(` / `mapOf(` / `.map {` / `.sortedBy`，§四 G16 / T1.6.1 清单）。
 * **行为段**：
 *  ④ E16 数字雨字形缓存键 = 三元组 `(slot10, cell10, n)`（T1.6.1 的 glyphKey 字符串替代）——
 *     同值 200 帧恒不触发重建；任一维变化必触发。
 *
 * **负向自证**（§八 开头规矩：必须有，否则门禁可能是空转）：
 *  N1 带参 `Rect(` 样本必须被判违规；N2 无参 `Rect()` 与豁免行必须放行（且样本本身确实是带参 Rect）；
 *  N3 字符串模板样本必须被判违规；N4 缓存键去掉 `cell10` 维 ⇒ 破实现漏检、真实现能抓到；
 *  N5 每帧容器分配样本必须被判违规。
 *
 * ⚠️ 偏差记录（§12.4）：G13⑤「估算原语数 ≤ §7.5 表登记值」需运行时统计绘制调用，
 * JVM 单测不可行 ⇒ 以 §7.5 静态登记 + 真机 `dumpsys gfxinfo` 复核为准，未做成单测断言。
 */
class PerfBudgetContractTest {

    // ── 源码定位（照抄 RendererBaseContractTest 的 mainSourceRoot 手法） ──

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val cand = File(dir, "app/src/main/java/com/nasmusic/tv/visualizer")
            if (cand.isDirectory) return cand
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 visualizer 源码根：user.dir = ${System.getProperty("user.dir")}")
    }

    /**
     * 剥掉块注释（Kotlin 块注释可嵌套）与行注释。
     * ⛔ **必须保持行数与原文件一致**（块注释内每个换行都要落回输出）——
     * 豁免标记检测按原始行号进行，行号漂移会让豁免失效。
     */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var depth = 0
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') { inString = true; sb.append(c); i++; continue }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                var blockDepth = 1
                while (i < src.length && blockDepth > 0) {
                    if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') { blockDepth++; i += 2 }
                    else if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') { blockDepth--; i += 2 }
                    else {
                        if (src[i] == '\n') sb.append('\n')   // 保持行号对齐
                        i++
                    }
                }
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++   // 行注释：'\n' 留给下一轮 append
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun isExemptLine(originalLine: String): Boolean =
        originalLine.contains("Perf-exempt")

    /**
     * 抽出「每帧可达」的函数体行号：`override fun DrawScope.draw(` / `drawContent(` 与
     * `fun DrawScope.<名>(` 扩展函数。大括号配对截取函数体（stripComments 已剥注释，
     * 函数体内不应再有含花括号的字符串模板 —— 那本身就是 ① 的违规）。
     */
    private fun drawReachableLineIndexes(stripped: String): List<Int> {
        val out = mutableListOf<Int>()
        val funRegex = Regex(
            """(?:override\s+)?fun\s+DrawScope\.(?:draw|draw[A-Za-z0-9_]*)\s*\("""
        )
        val lines = stripped.split('\n')
        var i = 0
        while (i < lines.size) {
            if (funRegex.find(lines[i]) == null) { i++; continue }
            var depth = 0
            var started = false
            var j = i
            loop@ while (j < lines.size) {
                for (ch in lines[j]) {
                    when (ch) {
                        '{' -> { depth++; started = true }
                        '}' -> depth--
                    }
                }
                if (started && depth <= 0) break@loop
                j++
            }
            for (k in i..j) out.add(k)
            i = j + 1
        }
        return out
    }

    // ── 违规判据（对单行） ──

    /** 字符串模板：字符串字面量内出现 `$`（`"…$…"`）。 */
    private fun hasStringTemplate(line: String): Boolean =
        Regex(""""[^"\n]*\$[^"\n]*"""").containsMatchIn(line)

    /** 带参 Rect( —— `Rect(` 后跟非 `)` 字符（无参 `Rect()` 缓冲构造放行；`RectF` 不匹配）。 */
    private fun hasAllocRect(line: String): Boolean =
        Regex("""(?<![A-Za-z0-9_])Rect\(\s*[^)\s]""").containsMatchIn(line)

    /** 每帧容器/lambda 分配（§四 G16 / T1.6.1 清单）。 */
    private fun hasContainerAlloc(line: String): Boolean =
        Regex("""\b(listOf|mutableListOf|arrayListOf|mapOf)\s*\(""").containsMatchIn(line) ||
            Regex("""\.(map|sortedBy|sortedByDescending)\s*\{""").containsMatchIn(line)

    private val scannedRoots = listOf("renderers", "photo")

    private fun scanSources(): List<String> {
        val violations = mutableListOf<String>()
        val root = mainSourceRoot()
        for (sub in scannedRoots) {
            val dir = File(root, sub)
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val originalLines = f.readText().split('\n')
                val stripped = stripComments(f.readText())
                drawReachableLineIndexes(stripped).forEach { idx ->
                    if (idx >= originalLines.size) return@forEach
                    if (isExemptLine(originalLines[idx])) return@forEach
                    val line = stripped.split('\n')[idx]
                    if (hasStringTemplate(line)) violations.add("${f.name}:${idx + 1} 字符串模板: ${line.trim()}")
                    if (hasAllocRect(line)) violations.add("${f.name}:${idx + 1} 带参 Rect(: ${line.trim()}")
                    if (hasContainerAlloc(line)) violations.add("${f.name}:${idx + 1} 每帧容器分配: ${line.trim()}")
                }
            }
        }
        return violations
    }

    // ── 正向断言 ──

    @Test
    fun `draw 可达代码零字符串模板零带参 Rect 零每帧容器分配`() {
        val v = scanSources()
        assertTrue(
            "G13 源码扫描发现违规（应全部清零，豁免须带 // Perf-exempt: 理由）:\n" +
                v.joinToString("\n"),
            v.isEmpty()
        )
    }

    @Test
    fun `空转自证 扫描范围与函数体抽取都非空`() {
        val root = mainSourceRoot()
        val files = scannedRoots.flatMap { sub ->
            File(root, sub).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue("扫描文件数应 > 0", files.isNotEmpty())
        assertTrue("应扫到 AdvancedRenderers.kt", files.any { it.name == "AdvancedRenderers.kt" })
        val adv = files.first { it.name == "AdvancedRenderers.kt" }
        val stripped = stripComments(adv.readText())
        val reachable = drawReachableLineIndexes(stripped)
        assertTrue("AdvancedRenderers.kt 的 DrawScope 函数体行数应 > 0，实测 ${reachable.size}",
            reachable.isNotEmpty())
        // 剥注释后行号必须与原文件对齐（豁免机制的前提）
        assertEquals("stripComments 必须保持行数一致", adv.readText().split('\n').size, stripped.split('\n').size)
    }

    @Test
    fun `E16 字形缓存键三元组 同值 200 帧不触发重建`() {
        val r = MatrixRainRenderer()
        r.keySlot = 100; r.keyCell = 50; r.keyN = 32
        assertFalse("键相同 ⇒ 不重建", r.glyphCacheStale(100, 50, 32))
        repeat(200) { frame ->
            assertFalse("逐帧同值 ⇒ 恒不重建（第 $frame 帧）", r.glyphCacheStale(100, 50, 32))
        }
    }

    @Test
    fun `E16 字形缓存键三元组 任一维变化都触发重建`() {
        val r = MatrixRainRenderer()
        r.keySlot = 100; r.keyCell = 50; r.keyN = 32
        assertTrue("slot 变化 ⇒ 重建", r.glyphCacheStale(101, 50, 32))
        assertTrue("cell 变化 ⇒ 重建（§C4-O2 漏维 bug 的死穴）", r.glyphCacheStale(100, 51, 32))
        assertTrue("列数变化 ⇒ 重建", r.glyphCacheStale(100, 50, 33))
        // 「glyphs 未建 ⇒ 重建」由 draw 调用点的 `glyphs == null ||` 短路兜底（不在本函数）
        assertFalse("新实例键字段全 0、喂同值 ⇒ 不重建", MatrixRainRenderer().glyphCacheStale(0, 0, 0))
    }

    // ── 负向自证 ──

    @Test
    fun `负向N1 带参 Rect 样本必须被判违规`() {
        assertTrue(hasAllocRect("brightPath.addOval(Rect(Offset(p.x - pr, p.y - pr), Offset(p.x + pr, p.y + pr)))"))
        assertTrue(hasAllocRect("cb.drawRect(Rect(i * cw, y, (i + 1) * cw, y + 1f), paint)"))
        assertTrue(hasAllocRect("peakPaths[bucket].addOval(Rect(cx + cosA * pr - 2.5f, cy + sinA * pr - 2.5f,"))
    }

    @Test
    fun `负向N2 无参 Rect 缓冲与豁免行必须放行`() {
        assertFalse("无参 Rect() 是缓存缓冲构造，放行", hasAllocRect("private val inkRect = Rect()"))
        assertFalse("RectF 是另一个类型，放行", hasAllocRect("val rf = RectF(0f, 0f, 1f, 1f)"))
        val exempt = "addRoundRect(RoundRect(Rect(left, 0f, left + pictureW, h), r, r)) // Perf-exempt: 尺寸缓存守卫内一次性构建"
        assertTrue("样本本身是带参 Rect（证明豁免标记真的在豁免东西）", hasAllocRect(exempt))
        assertTrue("豁免行被识别", isExemptLine(exempt))
    }

    @Test
    fun `负向N3 字符串模板样本必须被判违规`() {
        val legacy = "\"" + "\${(slot * 10).toInt()}:\${(cellH * 10).toInt()}:\$n" + "\""
        assertTrue("旧 glyphKey 字符串模板（S1.6 前的现场）必须被抓", hasStringTemplate(legacy))
        assertTrue(hasStringTemplate("val s = \"frame_\$i\""))
        assertFalse("无模板的普通字符串放行", hasStringTemplate("drawText(\"0\", x, y, p)"))
    }

    @Test
    fun `负向N4 缓存键去掉 cell 维度必须漏检而真实现能抓到`() {
        val r = MatrixRainRenderer()
        r.keySlot = 100; r.keyCell = 50; r.keyN = 32
        // 破实现：只比 slot10（§C4-O2「只用一个维度做键」的典型 bug 形态）
        val brokenDetects = { slot10: Int, _: Int, _: Int -> r.keySlot != slot10 }
        val brokenMisses = !brokenDetects(100, 51, 32)          // 破实现漏检（返回 false）
        val realCatches = r.glyphCacheStale(100, 51, 32)        // 真实现抓到（返回 true）
        assertTrue("破实现漏检 + 真实现抓到 ⇒ 三元组缺一不可", brokenMisses && realCatches)
    }

    @Test
    fun `负向N5 每帧容器分配样本必须被判违规`() {
        assertTrue(hasContainerAlloc("val parts = listOf(a, b)"))
        assertTrue(hasContainerAlloc("items.sortedBy { it.second }"))
        assertTrue(hasContainerAlloc("val m = mapOf(\"k\" to v)"))
        assertFalse(hasContainerAlloc("val x = 1"))
    }
}

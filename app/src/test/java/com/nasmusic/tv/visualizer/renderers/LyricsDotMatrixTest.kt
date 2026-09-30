package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * E23 歌词点阵（§B7 · T4.7）门禁。
 *
 * 四段：
 * - **行为段**：**直接调生产函数** [LyricsDotMatrixRenderer.calculateRowQuota] /
 *   [LyricsDotMatrixRenderer.karaokePacing] —— ⛔ 不复制算法（复制必然漂移）；
 * - **性能段（P2–P6）**：源码形态判据（`getPixel` 必须为 0 / `getPixels` ≥ 2 / `step` 恒 1 /
 *   `rewind` 取代 `reset` / 全局量在循环外 / 死代码已删）；
 * - **观感段（Q2/Q4）**：亮档 alpha 数值 + `postFx` 字面量；
 * - **保留段（§13.5-D4）**：卡拉OK 三档亮度 + 6 次 `drawPath` + 守卫注释必须在位。
 *
 * ⛔ 负向自证 2 条 —— 缺一条就可能空转。
 * ⛔ 源码判据一律**先剥注释**（本仓库已踩 5 次：判据命中自己刚写的 KDoc ⇒ 假 FAIL）。
 * ⛔ 负向样本必须与正向**喂同一份谓词**（不要另写「预期为 false」的表达式）。
 */
class LyricsDotMatrixTest {

    private val C = LyricsDotMatrixRenderer.Companion

    // ═══════════════════════════ ① 迁移形态 ═══════════════════════════

    @Test
    fun `① 已迁移 RendererFx - 无 rng 成员、不覆写 final、摘掉旧 import`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        assertTrue("类体必须能切出来（空转自证）", body.isNotEmpty())
        assertTrue("必须 `: RendererFx()`", ": RendererFx()" in body)
        assertTrue("必须实现 drawContent",
            Regex("""fun\s+(?:[A-Za-z0-9_.]+\.)?drawContent\s*\(""").containsMatchIn(body))
        assertTrue("必须实现 onEnterContent", "fun onEnterContent(" in body)
        assertTrue("必须实现 onExitContent", "fun onExitContent(" in body)
        // ⛔ 模板方法是 final ⇒ 子类覆写会编译错
        assertFalse("⛔ 不得覆写 final 的 draw",
            Regex("""override\s+fun\s+DrawScope\.draw\s*\(""").containsMatchIn(body))
        assertFalse("⛔ 不得覆写 final 的 onEnter",
            Regex("""override\s+fun\s+onEnter\s*\(""").containsMatchIn(body))
        assertFalse("⛔ 不得覆写 final 的 onExit",
            Regex("""override\s+fun\s+onExit\s*\(""").containsMatchIn(body))
        // ⛔ 基类已提供 rng ⇒ 子类不得再声明一份
        assertFalse("⛔ 不得自己声明 rng（基类已提供）",
            Regex("""(?m)^\s*private\s+val\s+rng\s*=""").containsMatchIn(body))

        val raw = readFile(renderersFile("LyricsDotMatrixRenderer.kt"))
        assertFalse("⛔ 不得再 import VisualizerRenderer",
            "import com.nasmusic.tv.visualizer.VisualizerRenderer" in raw)
        assertFalse("⛔ 不得再 import VisualizerRandom",
            "import com.nasmusic.tv.visualizer.VisualizerRandom" in raw)
    }

    // ═══════════════════════════ ② P2 逐行批读 ═══════════════════════════

    @Test
    fun `② P2 采样必须逐行 getPixels - getPixel 为 0 且 rowBuf 复用`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        assertFalse("⛔ 不得再出现逐像素 getPixel(", usesGetPixel(body))
        val n = Regex("""\.getPixels\(""").findAll(body).count()
        assertTrue("两遍采样各一次 ⇒ `getPixels(` 至少 2 处（实际 $n）", n >= 2)
        assertTrue("必须用成员 rowBuf 跨次复用（`takeIf { it.size >= bmpW }`）",
            "rowBuf?.takeIf { it.size >= bmpW }" in body)
        assertFalse("⛔ 不得整图读 IntArray(bmpW * bmpH)（§B7 R12）",
            Regex("""IntArray\(\s*bmpW\s*\*\s*bmpH\s*\)""").containsMatchIn(body))
    }

    // ═══════════════════════════ ③ P6 step 恒 1 ═══════════════════════════

    @Test
    fun `③ P6 采样 step 必须恒为 1`() {
        val sample = funBody(classBody(codeOfE23(), "LyricsDotMatrixRenderer"), "sampleLine")
        assertTrue("sampleLine 必须能切出来（空转自证）", sample.isNotEmpty())
        assertTrue("必须 `val step = 1`",
            Regex("""val\s+step\s*=\s*1\b""").containsMatchIn(sample))
        assertFalse("⛔ 不得再有旧的 `coerceIn(1f, 3f)`",
            Regex("""coerceIn\(\s*1f\s*,\s*3f\s*\)""").containsMatchIn(sample))
        assertFalse("⛔ 不得再算 idealStep", "idealStep" in sample)
    }

    // ═══════════════════════════ ④ P5 rewind ═══════════════════════════

    @Test
    fun `④ P5 每帧必须 rewind 而非 reset`() {
        val draw = funBody(classBody(codeOfE23(), "LyricsDotMatrixRenderer"), "drawContent")
        assertTrue("必须 `for (p in paths) p.rewind()`", "p.rewind()" in draw)
        assertFalse("⛔ 不得再用 p.reset()", "p.reset()" in draw)
    }

    // ═══════════════════════════ ⑤ P3 全局量外提 ═══════════════════════════

    @Test
    fun `⑤ P3 全局量必须在 addLineToPaths 循环外算一次`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        val draw = funBody(body, "drawContent")
        val add = funBody(body, "addLineToPaths")
        assertTrue("addLineToPaths 必须能切出来（空转自证）", add.isNotEmpty())
        assertTrue("drawContent 必须算 sinB",
            Regex("""val\s+sinB\s*=\s*sin\(""").containsMatchIn(draw))
        assertTrue("drawContent 必须算 amp",
            Regex("""val\s+amp\s*=\s*2\.2f""").containsMatchIn(draw))
        // ⛔ 循环体内不得再重算（旧实现每点算一遍 = 7,200 次冗余）
        assertFalse("⛔ addLineToPaths 内不得再算 frame.bass.coerceIn", "frame.bass.coerceIn" in add)
        assertFalse("⛔ addLineToPaths 内不得再声明 amp",
            Regex("""val\s+amp\s*=""").containsMatchIn(add))
        assertTrue("breath 必须用提好的 sinB", "sinB * 0.10f" in add)
        assertTrue("floatY 的逐点 sin 必须保留（提不了）",
            "sin(globalT * 2.0f + localX * 10f)" in add)
    }

    // ═══════════════════════════ ⑥ P4 死代码 ═══════════════════════════

    @Test
    fun `⑥ P4 死代码必须已删`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        val add = funBody(body, "addLineToPaths")
        assertFalse("⛔ phaseVal 必须已删（零引用）", "phaseVal" in add)
        assertFalse("⛔ baseSize 必须已删（恒 1.0f）", "baseSize" in add)
        assertFalse("⛔ textLen 必须彻底删除（形参 + 两处实参）", "textLen" in body)
    }

    // ═══════════════════════ ⑦ 卡拉OK 必须保留（§13.5-D4）═══════════════════════

    @Test
    fun `⑦ 卡拉OK 三档亮度、6 次 drawPath 与守卫注释必须保留`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        val add = funBody(body, "addLineToPaths")
        // 三档亮度分支（§B7 专项评估 ①）
        assertTrue("必须有 `!hasLyrics -> 0.9f`", "!hasLyrics -> 0.9f" in add)
        assertTrue("必须有 `!isSinging ->`（待唱行）", "!isSinging ->" in add)
        assertTrue("必须有卡拉OK 前沿判定 `localX - kProgress`", "localX - kProgress" in add)
        assertTrue("必须有已唱段亮度 0.78f", "0.78f" in add)
        assertTrue("必须有前沿字亮度 1.0f", "1.0f" in add)
        // 三档量化
        assertTrue("tier 必须含 0.88f 阈值", "brightness * visibility > 0.88f" in add)
        assertTrue("tier 必须含 0.45f 阈值", "brightness * visibility > 0.45f" in add)
        // 6 条 Path 无条件绘制（draw 次数与卡拉OK 无关）
        val draw = funBody(body, "drawContent")
        assertEquals("必须恒 6 次 drawPath（3 档 × 2 层）",
            6, Regex("""drawPath\(paths\[\d\]""").findAll(draw).count())
        // 守卫注释（§15.7 第 18 条）—— 在**未剥注释**的原文里找
        val raw = readFile(renderersFile("LyricsDotMatrixRenderer.kt"))
        assertTrue("必须有「不得以性能为由删除」守卫注释（§15.7 第 18 条）",
            "不得以“性能”为由删除" in raw)
    }

    // ═══════════════════════════ ⑧ Q2 亮档去过曝 ═══════════════════════════

    @Test
    fun `⑧ Q2 亮档 alpha 必须已降过曝`() {
        val draw = funBody(classBody(codeOfE23(), "LyricsDotMatrixRenderer"), "drawContent")
        assertTrue("paths[4] core 峰值必须 0.62 + pulse×0.14（峰值 0.76）",
            "0.62f + frame.pulse * 0.14f" in draw)
        assertTrue("paths[5] 外辉光必须提到 0.26",
            "0.26f + frame.pulse * 0.10f" in draw)
        assertFalse("⛔ 不得再有旧的 0.80f + frame.pulse * 0.18f",
            "0.80f + frame.pulse * 0.18f" in draw)
    }

    // ═══════════════════════════ ⑨ Q4 postFx ═══════════════════════════

    @Test
    fun `⑨ Q4 postFx 字面量在位且判据正负双证`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        assertTrue("postFx 必须含 > 0 的数值字面量（门禁判据）", coveredByPostFx(body))
        assertTrue("必须是暗角 0.44", "vignette = 0.44f" in body)
        assertTrue("必须是颗粒 0.026", "grain = 0.026f" in body)
        // ⛔ 负向：具名常量必须静默判否（门禁只认字面量 —— 已知陷阱）
        assertFalse("⛔ 具名常量必须判否（否则门禁会静默放过）",
            coveredByPostFx("class X : RendererFx() {\n    override val postFx = PostFx(vignette = V, grain = G)\n}"))
        assertFalse("PostFx.NONE 必须判否",
            coveredByPostFx("class X : RendererFx() {\n    override val postFx = PostFx.NONE\n}"))
    }

    // ═══════════════════════════ ⑩ 行为：按行配额 ═══════════════════════════

    @Test
    fun `⑩ 行为 - calculateRowQuota 按占比配额且非空行至少 1`() {
        // 3 行：1 / 3 / 0 个有效像素，cap = 100
        val rows = intArrayOf(1, 3, 0)
        val q = C.calculateRowQuota(rows, 4, 100)
        assertEquals("空行配额必须为 0", 0, q[2])
        assertTrue("非空行配额必须 ≥ 1", q[0] >= 1 && q[1] >= 1)
        assertTrue("占比大的行配额必须更大（3 : 1）", q[1] > q[0])
        assertEquals("1/4 占比 ⇒ 25", 25, q[0])
        assertEquals("3/4 占比 ⇒ 75", 75, q[1])
        assertTrue("total = 0 ⇒ 全 0", C.calculateRowQuota(rows, 0, 100).all { it == 0 })
        assertTrue("cap = 0 ⇒ 全 0", C.calculateRowQuota(rows, 4, 0).all { it == 0 })
        // ⛔ 负向（喂同一份谓词）：把「非空行均分」当候选 ⇒ 它不是按占比
        val flat = intArrayOf(50, 50, 0)
        assertTrue("按占比的配额必须与「非空行均分」不同（判据有区分力）",
            q[0] != flat[0] || q[1] != flat[1])
    }

    // ═══════════════════════════ ⑪ 行为：卡拉OK 曲线 ═══════════════════════════

    @Test
    fun `⑪ 行为 - karaokePacing 单调、端点固定、快起慢落`() {
        assertEquals("p(0) 必须为 0", 0f, C.karaokePacing(0f), 1e-6f)
        assertEquals("p(1) 必须为 1", 1f, C.karaokePacing(1f), 1e-6f)
        // 逐点数值（三次缓出 `1-(1-u)³` 的解析值；⛔ 硬编码期望值，不复制算法）
        assertEquals("p(0.25) = 1 - 0.75³", 0.578125f, C.karaokePacing(0.25f), 1e-6f)
        assertEquals("p(1/3) = 1 - (2/3)³", 0.7037037f, C.karaokePacing(1f / 3f), 1e-5f)
        assertEquals("p(0.5) = 1 - 0.5³", 0.875f, C.karaokePacing(0.5f), 1e-6f)
        // 单调递增
        var prev = -1f
        for (i in 0..20) {
            val v = C.karaokePacing(i / 20f)
            assertTrue("必须单调递增（i=$i）", v >= prev - 1e-6f)
            prev = v
        }
        // 快起慢落：前 1/3 进度已过 0.70（线性只有 0.333）
        assertTrue("必须快起慢落（p(1/3) > 0.70）实际 ${C.karaokePacing(1f / 3f)}",
            C.karaokePacing(1f / 3f) > 0.70f)
        // 越界钳制
        assertEquals("越界必须钳到 0", 0f, C.karaokePacing(-1f), 1e-6f)
        assertEquals("越界必须钳到 1", 1f, C.karaokePacing(2f), 1e-6f)
        // ⛔ 负向（喂同一份谓词）：线性实现必须与生产不同
        val linear = { u: Float -> u.coerceIn(0f, 1f) }
        assertTrue("线性实现不得被判为等同（区分力自证）",
            abs(C.karaokePacing(0.5f) - linear(0.5f)) > 1e-3f)
    }

    // ═══════════════════ 负向 N1/N2：源码判据必须能抓到旧写法 ═══════════════════

    @Test
    fun `负向N1 旧逐像素 getPixel 必须被判据抓到`() {
        assertTrue("负向 N1：旧 `b.getPixel(x, y)` 必须被 getPixel 判据抓到",
            usesGetPixel(OLD_SNIPPET_GETPIXEL))
        assertFalse("正向：新实现的 sampleLine 不得有 getPixel",
            usesGetPixel(funBody(classBody(codeOfE23(), "LyricsDotMatrixRenderer"), "sampleLine")))
        // ⛔ 词边界：`getPixels(` 不得被误判为 `getPixel(`
        assertFalse("判据不得误伤 getPixels（词边界）",
            usesGetPixel("b.getPixels(buf, 0, bmpW, 0, y, bmpW, 1)"))
    }

    @Test
    fun `负向N2 旧 coerceIn(1f,3f) 与 reset 必须被判据抓到`() {
        val oldStep = """
            val idealStep = kotlin.math.sqrt(estPixels / cap.toFloat())
                .coerceIn(1f, 3f).toInt().coerceAtLeast(1)
            val step = idealStep
        """.trimIndent()
        assertTrue("负向 N2a：旧 step 计算必须被 `coerceIn(1f, 3f)` 判据抓到",
            Regex("""coerceIn\(\s*1f\s*,\s*3f\s*\)""").containsMatchIn(oldStep))
        assertFalse("正向：新实现的 sampleLine 不得有 coerceIn(1f, 3f)",
            Regex("""coerceIn\(\s*1f\s*,\s*3f\s*\)""")
                .containsMatchIn(funBody(classBody(codeOfE23(), "LyricsDotMatrixRenderer"), "sampleLine")))

        val oldReset = "for (p in paths) p.reset()"
        assertTrue("负向 N2b：旧 `p.reset()` 必须被 reset 判据抓到", "p.reset()" in oldReset)
        assertFalse("正向：新实现的 drawContent 不得有 p.reset()",
            "p.reset()" in funBody(classBody(codeOfE23(), "LyricsDotMatrixRenderer"), "drawContent"))
    }

    // ═══════════════════════════ 辅助 ═══════════════════════════

    /** 门禁原版判据（与 `FxCoverageScanTest` 逐字一致）：只认 `postFx` 里的**数值字面量** */
    private val postFxRe = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""")
    private val numRe = Regex("""=\s*([0-9]*\.?[0-9]+)f""")

    private fun coveredByPostFx(code: String): Boolean {
        val m = postFxRe.find(code) ?: return false
        return numRe.findAll(m.groupValues[1]).any { it.groupValues[1].toFloat() > 0f }
    }

    /** ⛔ 词边界：`getPixels(` 里 `getPixel` 后面是 `s`，不得匹配 */
    private fun usesGetPixel(code: String): Boolean =
        Regex("""(?<![A-Za-z0-9_])getPixel\(""").containsMatchIn(code)

    /** 旧实现片段（负向样本；**只**用于证明判据能抓到它） */
    private val OLD_SNIPPET_GETPIXEL = """
        for (y in 0 until bmpH step step) {
            var cnt = 0
            for (x in 0 until bmpW step step) {
                if (b.getPixel(x, y) and 0xFF000000.toInt() != 0) cnt++
            }
        }
    """.trimIndent()

    /** 切出 `class <name>` 的类体（到下一个顶层 class / 文件尾） */
    private fun classBody(txt: String, name: String): String {
        val m = Regex("""(?m)^\s*(?:(?:internal|open|abstract|private)\s+)*class\s+$name\b""").find(txt)
            ?: return ""
        val rest = txt.substring(m.range.last + 1)
        val nxt = Regex("""(?m)^\s*(?:(?:internal|open|abstract|private)\s+)*class\s+\w+""").find(rest)
        return if (nxt == null) txt.substring(m.range.first) else txt.substring(m.range.first, m.range.last + 1 + nxt.range.first)
    }

    /** 从**已剥注释**的类体里取某个函数的 `{...}` 体（签名要认 receiver `DrawScope.`） */
    private fun funBody(classBody: String, name: String): String {
        val m = Regex("""fun\s+(?:[A-Za-z0-9_.]+\.)?$name\s*\(""").find(classBody) ?: return ""
        val brace = classBody.indexOf('{', m.range.last)
        if (brace < 0) return ""
        var depth = 0
        var i = brace
        while (i < classBody.length) {
            when (classBody[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return classBody.substring(brace, i + 1)
                }
            }
            i++
        }
        return classBody.substring(brace)
    }

    /**
     * 去注释（**行注释 + 块注释（含嵌套）+ 字符串感知**）。
     * ⛔ 只去行注释不够 —— 判据会命中 KDoc 正文里举的写法（本仓库已踩 5 次）。
     */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '"') {
                sb.append(c); i++
                while (i < src.length) {
                    sb.append(src[i])
                    if (src[i] == '\\' && i + 1 < src.length) {
                        sb.append(src[i + 1]); i += 2; continue
                    }
                    i++
                    if (src[i - 1] == '"') break
                }
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i < src.length && depth > 0) {
                    if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') {
                        depth++; i += 2
                    } else if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') {
                        depth--; i += 2
                    } else {
                        if (src[i] == '\n') sb.append('\n')
                        i++
                    }
                }
                continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** `LyricsDotMatrixRenderer.kt` 的**已剥注释**全文 */
    private fun codeOfE23(): String = stripComments(readFile(renderersFile("LyricsDotMatrixRenderer.kt")))

    private fun readFile(f: File): String = f.readText()

    private fun renderersFile(name: String): File =
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/$name")

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        repeat(6) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 app/src/main/java")
    }
}

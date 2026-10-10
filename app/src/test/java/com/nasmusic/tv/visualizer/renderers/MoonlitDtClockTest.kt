package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 门禁 **G5 `MoonlitDtClockTest`**（`docs/moonlit-visualizer-plan.md` §10.2）——
 * E44「明月」的**时基红线**，正则扫描真实源码（仿 `RendererBaseContractTest:167` 的做法）。
 *
 * ## 四条判据
 * ① ⛔ `ctx.nowMs`：进入瞬间它是**墙钟**，与 `frame.timeMs` 的单调时钟不同源，两者相减得
 *    约 −1.7e12 ms ⇒ 整个生命周期冻结（E42/E43 同一个坑，已吃过两次）。
 * ② ⛔ `System.currentTimeMillis()` 出现在任何 `× 系数` / `+ 位移` 表达式里。
 *    真实月相**必须**读系统时钟（§4.5），但只许落在两个位置：`onEnterContent` 的锚定赋值、
 *    `MoonPhase` 的实参。⚠️ 本条不能简单禁掉这个调用 —— 那是需求 1 的入口。
 * ③ ⭐ **`t * spdMul` 形态**（绝对时间乘音频调制）：位移必须写成
 *    `x += speedK·spdMul·dtSec` 的**增量累加**。⚠️ 光有时钟门抓不到这条 ——
 *    它就是原型第四轮"云随鼓点抽搐"的根因（§3.0+ (f) 第 6 条）。
 * ④ ⚠️ `elapsedRealtimeNanos()` 的**单位**：它是纳秒，任何与毫秒预算比较的地方必须先
 *    `/ 1_000_000`。E43 在 `SeasideRenderer.kt:3131-3139` 漏掉这一步，583 行圆盘烘了 105 s
 *    —— 编译通过、单测通过、只有真机看得出来，所以这条必须进门禁。
 *
 * ## 防"空转"
 * 每条判据都配**负向自证**：把已知的错误写法喂进同一个判据函数，断言它**必须**报违规。
 * （E43 的教训：`RendererBaseContractTest` 曾把夹具字符串当被测对象，对真实代码恒为通过。）
 */
class MoonlitDtClockTest {

    /**
     * ⚠️ 扫描面是**两个**文件：T7 起云场的时基住在 `MoonClouds.kt`
     * （`b.x = wrap01(b.x + speedK·spdMul·dtSec)` 这一行就是 (f) 第 6 条的正主），
     * 只扫渲染器等于把 ③/③′ 判据对最容易写错的那半代码关掉。
     */
    private val targets = listOf("MoonlitRenderer.kt", "MoonClouds.kt")

    /** 每个文件**单独**过判据 ⇒ 报出来的行号能定位回原文件。 */
    private fun files(): List<Pair<String, String>> = targets.map { it to stripComments(readFile(it)) }

    private fun all(violations: (String) -> List<String>): List<String> =
        files().flatMap { (name, src) -> violations(src).map { "$name:$it" } }

    /** ① `ctx.nowMs` 一次都不许出现 */
    private fun ctxNowMsViolations(src: String): List<String> =
        src.lines().withIndex()
            .filter { it.value.contains("ctx.nowMs") || it.value.contains("context.nowMs") }
            .map { "${it.index + 1}: ${it.value.trim()}" }

    /**
     * ② 墙钟只许出现在「`*AnchorMs` 锚定赋值」或「`MoonPhase` 实参」两类行里。
     *
     * ⚠️ 判据按**行形态**判而不是按"是否在 drawContent 内"判：锚定值一旦写进 `drawContent`
     * 里的一个局部 `val utc = System.currentTimeMillis()` 就直接违规，而它不在这两类行里。
     */
    private fun wallClockViolations(src: String): List<String> {
        val anchorAssign = Regex("""\w*[Aa]nchorMs\s*=\s*System\.currentTimeMillis\(\)""")
        val phaseArg = Regex("""MoonPhase\.\w+\([^)]*System\.currentTimeMillis\(\)""")
        val out = mutableListOf<String>()
        src.lines().withIndex().forEach { (i, line) ->
            if (!line.contains("System.currentTimeMillis()")) return@forEach
            val code = line.substringBefore("//")
            if (anchorAssign.containsMatchIn(code) || phaseArg.containsMatchIn(code)) return@forEach
            out += "${i + 1}: ${line.trim()}"
        }
        return out
    }

    /**
     * ③ 绝对时间 × 音频调制。
     *
     * 时间侧用**变量名**认（`nowMs`/`now`/`t`/`timeSec`/`elapsed*`），音频侧同理
     * （`spd*`/`mul`/`beat`/`bass`/`mid`/`treb`/`ener`/`pulse`）。
     * ⚠️ 这是**名字**判据，不是数据流判据 —— 所以它只能挡住"照抄原型那行错写法"这一类，
     * 真正的正确性还得靠 `x += …·dtSec` 的形态（下面的 [incrementalMoveViolations]）。
     */
    private fun timeTimesAudioViolations(src: String): List<String> {
        val time = """\b(?:fx\.)?(?:nowMs|now|t|timeSec|timeMs|elapsedMs|elapsedSec|absT)\b"""
        val audio = """\b(?:spdMul|speedMul|\w*[Mm]ul|beat\w*|aBass|aMid|aTreb|aEnergy|aPulse|bass|mid|treb|energy)\b"""
        val re = Regex("""$time\s*\*\s*$audio|$audio\s*\*\s*$time""")
        val out = mutableListOf<String>()
        src.lines().withIndex().forEach { (i, line) ->
            val code = line.substringBefore("//")
            if (code.contains("dtSec") || code.contains("fx.dt")) return@forEach   // 增量形态不算违规
            if (re.containsMatchIn(code)) out += "${i + 1}: ${code.trim()}"
        }
        return out
    }

    /**
     * ③′ 任何"随音频变的位移"必须以 `dt`/`dtSec` 为乘子（增量累加），⛔ 直接等于绝对量。
     * 判据：行内同时出现 `+=` 与音频名，却没有 `dt` ⇒ 违规。
     */
    private fun incrementalMoveViolations(src: String): List<String> {
        val out = mutableListOf<String>()
        src.lines().withIndex().forEach { (i, line) ->
            val code = line.substringBefore("//")
            if (!code.contains("+=")) return@forEach
            if (!Regex("""\b(spdMul|speedMul|aBass|aMid|aTreb|aEnergy|aPulse|beatMul)\b""")
                    .containsMatchIn(code)
            ) return@forEach
            if (code.contains("dt")) return@forEach
            out += "${i + 1}: ${code.trim()}"
        }
        return out
    }

    /**
     * ④ `elapsedRealtimeNanos()` 必须换算成毫秒后才能与毫秒预算比较。
     * 判据：文件里出现该调用时，必须存在一处 `/ 1_000_000`（或其等价写法）除法。
     */
    private fun nanosUnitViolations(src: String): List<String> {
        if (!src.contains("elapsedRealtimeNanos")) return emptyList()
        val divided = Regex("""/\s*1_000_000(?:\.0)?\b""").containsMatchIn(src) ||
            src.contains("COEFFICIENT")   // 若改用常量换算，常量名必须显式出现
        return if (divided) emptyList() else listOf("elapsedRealtimeNanos() 没有除以 1e6 就当毫秒用")
    }

    // ── 对真实源码的断言 ─────────────────────────────────────────────────────────

    @Test
    fun `① 渲染器不使用 ctx 的墙钟 nowMs`() {
        val v = all(::ctxNowMsViolations)
        assertTrue("出现 ctx.nowMs（与 frame.timeMs 不同源，会让动画整体冻结）：\n${v.joinToString("\n")}",
            v.isEmpty())
    }

    @Test
    fun `② 墙钟只出现在锚定赋值与历算实参`() {
        val src = source()
        val v = all(::wallClockViolations)
        assertTrue("System.currentTimeMillis() 越界使用：\n${v.joinToString("\n")}", v.isEmpty())
        // 且它**必须**存在（月相是需求 1，读不到墙钟等于没接）
        assertEquals("onEnterContent 里应恰有一处墙钟锚定",
            1, src.lines().count { it.contains("System.currentTimeMillis()") })
        // 锚定行必须真的在 onEnterContent 里，⛔ 放在类字段初始化（那会在构造期读一次墙钟、
        // 且重入/切换效果时不再更新）
        assertTrue("墙钟锚定必须在 onEnterContent 内",
            functionBody(src, "onEnterContent").contains("System.currentTimeMillis()"))
    }

    @Test
    fun `③ 绝对时间不乘音频调制`() {
        val v = all { s -> timeTimesAudioViolations(s) + incrementalMoveViolations(s) }
        assertTrue("出现「绝对时间 × 音频」形态（第四轮'云随鼓点抽搐'的根因）：\n${v.joinToString("\n")}",
            v.isEmpty())
        // 动画时基的唯一来源是 fx.dt / fx.nowMs 锚点；云场侧则必须只见 dtSec 增量
        assertTrue(source().contains("fx.nowMs"))
        assertTrue("云场必须走 dtSec 增量形态",
            stripComments(readFile("MoonClouds.kt")).contains("b.speedK * spdMul * dtSec"))
    }

    @Test
    fun `④ 纳秒计时必须换算成毫秒`() {
        val v = all(::nanosUnitViolations)
        assertTrue("E43 的单位 bug（把纳秒当毫秒 → 烘焙慢 10⁶ 倍）不得重演：\n${v.joinToString("\n")}",
            v.isEmpty())
    }

    /** ⑤ 月相缓存有间隔，⛔ 逐帧算历（一次约 30 个三角函数 + 1 个对象分配） */
    @Test
    fun `⑤ 历算按间隔缓存`() {
        val src = source()
        assertTrue("必须走 REPHASE_INTERVAL_MS 缓存（§4.5）", src.contains("REPHASE_INTERVAL_MS"))
        val body = functionBody(src, "refreshPhase")
        assertTrue("缓存判定要用间隔常量", body.contains("REPHASE_INTERVAL_MS"))
        assertTrue("⛔ 不许每帧 MoonPhase.of：见间隔分支",
            Regex("MoonPhase\\.of").findAll(body).toList().size == 1)
    }

    // ── 负向自证：判据本身必须有判别力 ──────────────────────────────────────────

    /** ①′ 已知错误写法必须被 ① 抓到；注释里的提及靠 [stripComments] 挡下。 */
    @Test
    fun `负向自证 ctx nowMs 写法会被判红`() {
        assertTrue(ctxNowMsViolations("val t = ctx.nowMs * 0.001f").isNotEmpty())
        // ⚠️ 判据本身**不看注释**（它就是逐行 `contains`），"注释不算违规"这件事是
        //   [stripComments] 的职责 —— 所以这里必须把剥注释这一步放进自证，否则等于假装判据免疫。
        val mentioned = "// ctx.nowMs 只是注释里提一句\nval keep = 1f"
        assertTrue("不剥注释时自己的类 KDoc 就会把自己判红",
            ctxNowMsViolations(mentioned).isNotEmpty())
        assertTrue(ctxNowMsViolations(stripComments(mentioned)).isEmpty())
        // 行号不能漂：判据输出的行号要能定位回原文件
        val blocky = "/**\n * 引用 clipPath( 与 ctx.nowMs\n */\nval x = ctx.nowMs\n"
        assertEquals("剥注释必须保持行数，否则输出的行号不可用",
            4, stripComments(blocky).lines().withIndex()
                .first { it.value.contains("ctx.nowMs") }.index + 1)
    }

    /** ②′ 墙钟进动画表达式 / 进 drawContent 局部变量都必须判红，而锚定赋值必须放行。 */
    @Test
    fun `负向自证 墙钟越界用法会被判红`() {
        val src = """
            private fun DrawScope.drawContent() {
                val utc = System.currentTimeMillis()
                drift += utc * 0.0001f
            }
        """.trimIndent()
        assertEquals("锚定赋值必须放行", 0,
            wallClockViolations("        wallAnchorMs = System.currentTimeMillis() // §4.5 锚定").size)
        assertTrue("drawContent 里局部读墙钟必须判红", wallClockViolations(src).isNotEmpty())
        // 历算实参那一类也放行（本文允许的唯一另一种形态）
        assertEquals(0,
            wallClockViolations("val st = MoonPhase.of(System.currentTimeMillis())").size)
    }

    /** ③′ `t * spdMul` 与 `x += spdMul * absT` 都必须判红，增量累加必须放行。 */
    @Test
    fun `负向自证 绝对时间乘调制会被判红`() {
        assertTrue(timeTimesAudioViolations("val x = t * spdMul * 12f").isNotEmpty())
        assertTrue(timeTimesAudioViolations("val x = nowMs * aBass").isNotEmpty())
        assertTrue(incrementalMoveViolations("drift += spdMul * 40f").isNotEmpty())
        // 定稿要求的形态必须放行（两条判据都要）
        assertEquals(0, incrementalMoveViolations("drift += SPD_NEAR * spdMul * dtSec").size)
        assertEquals(0, timeTimesAudioViolations("drift += SPD_NEAR * spdMul * dtSec").size)
    }

    /** ④′ 少了除法必须判红（E43 真实缺陷的最小复现）。 */
    @Test
    fun `负向自证 纳秒不换算会被判红`() {
        assertTrue(nanosUnitViolations(
            "val stepMs = SystemClock.elapsedRealtimeNanos() - t0"
        ).isNotEmpty())
        assertEquals(0, nanosUnitViolations(
            "val stepMs = (SystemClock.elapsedRealtimeNanos() - t0).toDouble() / 1_000_000.0"
        ).size)
    }

    /** 判据不能对空文件"假绿"：**每个**目标文件都必须存在且非空。 */
    @Test
    fun `被扫描的文件确实存在`() {
        val markers = mapOf(
            "MoonlitRenderer.kt" to "class MoonlitRenderer(",
            "MoonClouds.kt" to "class MoonCloudField",
        )
        markers.forEach { (name, marker) ->
            val src = readFile(name)
            assertTrue("$name 内容为空 ⇒ 扫描会假绿", src.length > 2_000)
            assertTrue("$name 里找不到 $marker", stripComments(src).contains(marker))
        }
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────────

    private fun readFile(name: String): String =
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/$name").readText()

    /**
     * ⚠️ 判据一律跑在**剥掉注释**的源码上：本文件的类 KDoc 与红线说明里**原文引用**了
     * `ctx.nowMs` / `System.currentTimeMillis()` 这些被禁写法，不剥注释就等于自己判自己红
     * （而 `PerfBudgetContractTest` 的豁免机制正是靠 `// Perf-exempt` 这种注释行工作的，
     * 两套口径别混）。
     */
    private fun source(): String =
        files().joinToString("\n") { it.second }

    /** 剥块注释（Kotlin 可嵌套）与行注释，⚠️ 保持行数与原文件一致（行号不能漂）。 */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
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
                        if (src[i] == '\n') sb.append('\n')
                        i++
                    }
                }
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun functionBody(src: String, name: String): String {
        val head = Regex("""fun\s+(?:DrawScope\.)?$name\s*\(""").find(src) ?: error("找不到 $name")
        var i = head.range.last
        while (src[i] != '{') i++
        var depth = 0
        var j = i
        while (j < src.length) {
            when (src[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(i + 1, j)
                }
            }
            j++
        }
        error("$name 大括号不配对")
    }

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

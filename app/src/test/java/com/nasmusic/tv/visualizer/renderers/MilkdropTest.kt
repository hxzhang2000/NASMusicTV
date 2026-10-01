package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * E18 反馈残像（§B4 · T4.4）门禁。
 *
 * 三段：
 * - **常量段**：§B4 明文值 + TAP 表结构（长度 / 单调 / 和为 1）；
 * - **行为段**：把 `drawContent` 里的公式**逐字复刻**成纯函数后积分（帧率无关 / 段落 EMA）；
 * - **源码段**：衰减层位置与唯一性、3-tap 循环上界、`postFx` 数值字面量。
 *
 * ⛔ 负向自证 4 条 —— 缺一条就可能空转（§八 开头规矩）。
 */
class MilkdropTest {

    private val C = MilkdropRenderer.Companion

    // ═══════════ 行为段：与 drawContent 逐字对应的纯函数复刻 ═══════════

    /** 生产实现：`rotation += (0.3f + mid * 0.5f) * fx.dt * FPS_BASE` */
    private fun rotProd(seconds: Float, fps: Int, mid: Float): Float {
        val dt = 1f / fps
        var rot = 0f
        var t = 0f
        while (t < seconds - 1e-6f) {
            rot += (0.3f + mid * 0.5f) * dt * C.FPS_BASE
            t += dt
        }
        return rot
    }

    /** 旧实现：`rotation += 0.3f + mid * 0.5f`（**每帧**固定增量 ⇒ 与帧率绑定） */
    private fun rotOld(seconds: Float, fps: Int, mid: Float): Float {
        val dt = 1f / fps
        var rot = 0f
        var t = 0f
        while (t < seconds - 1e-6f) {
            rot += 0.3f + mid * 0.5f
            t += dt
        }
        return rot
    }

    /** 生产实现：`HUE_RATE_BASE + sectionHue * HUE_RATE_SECTION + treble * 2f` */
    private fun hueRate(sectionHue: Float, treble: Float): Float =
        C.HUE_RATE_BASE + sectionHue * C.HUE_RATE_SECTION + treble * 2f

    /** 生产实现：`sectionHue += (target - sectionHue) * (fx.dt * SECTION_RATE)`（dt 化） */
    private fun sectionProd(seconds: Float, fps: Int, target: Float): Float {
        val dt = 1f / fps
        var s = 0f
        var t = 0f
        while (t < seconds - 1e-6f) {
            s += (target - s) * (dt * C.SECTION_RATE).coerceIn(0f, 1f)
            t += dt
        }
        return s
    }

    /** 旧写法：**每帧**固定系数 EMA（`WorldRenderer:612` 的形态 ⇒ 与帧率绑定） */
    private fun sectionPerFrame(seconds: Float, fps: Int, target: Float, k: Float): Float {
        val dt = 1f / fps
        var s = 0f
        var t = 0f
        while (t < seconds - 1e-6f) {
            s += (target - s) * k
            t += dt
        }
        return s
    }

    // ═══════════════════════════ ①–⑧ 常量与行为 ═══════════════════════════

    @Test
    fun `① §B4 明文常量齐备且取值正确`() {
        assertEquals(0.06f, C.DECAY_ALPHA, 1e-6f)
        assertEquals(3, C.TAP_COUNT)
        assertEquals(1.0f, C.TAP_SCALE[0], 1e-6f)
        assertEquals(1.012f, C.TAP_SCALE[1], 1e-6f)
        assertEquals(1.024f, C.TAP_SCALE[2], 1e-6f)
        assertEquals(0.6f, C.TAP_ALPHA[0], 1e-6f)
        assertEquals(0.25f, C.TAP_ALPHA[1], 1e-6f)
        assertEquals(0.15f, C.TAP_ALPHA[2], 1e-6f)
        assertEquals(0.35f, C.SIDE_OFFSET, 1e-6f)
        assertEquals(0.22f, C.SIDE_LIT_DARK, 1e-6f)
        assertEquals(0.20f, C.SIDE_LIT_BRIGHT, 1e-6f)
        assertEquals(0.60f, C.SECTION_RATE, 1e-6f)
        assertEquals(0.35f, C.HUE_RATE_BASE, 1e-6f)
        assertEquals(1.20f, C.HUE_RATE_SECTION, 1e-6f)
        assertEquals(60f, C.FPS_BASE, 1e-6f)
    }

    @Test
    fun `② TAP 表结构 - 长度与 TAP_COUNT 一致、缩放严格递增、alpha 和为 1`() {
        assertEquals("TAP_SCALE 长度必须 == TAP_COUNT（循环上界是常量，两者会静默脱钩）",
            C.TAP_COUNT, C.TAP_SCALE.size)
        assertEquals("TAP_ALPHA 长度必须 == TAP_COUNT", C.TAP_COUNT, C.TAP_ALPHA.size)
        // 缩放严格递增 ⇒ 3 个 tap 是"由内到外"的同心副本，才能形成径向模糊
        assertTrue("TAP_SCALE 必须严格递增", C.TAP_SCALE[1] > C.TAP_SCALE[0] && C.TAP_SCALE[2] > C.TAP_SCALE[1])
        // ⭐ 和 == 1 是"单帧回绘总亮度与改造前持平"的**唯一**依据（§B4-② 口径）
        val sum = C.TAP_ALPHA.sum()
        assertEquals("TAP_ALPHA 之和必须为 1（否则总亮度会随 tap 数漂移）", 1.0f, sum, 1e-6f)
    }

    @Test
    fun `③ 衰减色调 0_06 且每帧保留比例小于 1`() {
        assertEquals(0.06f, C.DECAY_ALPHA, 1e-6f)
        // 抑制灰白的方向：每帧亮度乘 (1 - DECAY_ALPHA) ⇒ 稳态亮度上界 = 注入/(衰减) 而非累积到 1
        val keep = 1f - C.DECAY_ALPHA
        assertTrue("每帧保留比例必须 < 1", keep < 1f)
        assertEquals("每帧保留 0.94", 0.94f, keep, 1e-6f)
        // ⚠️ 2026-10-01 起本用例**不再**断言"与 §A4-2 的 E12 FADE_ALPHA 同值"：
        // E12 的衰减改押在「拖尾长度 == 缓冲行数」上（0.02 × 200 行），与本类的回绘累积是两个量。
    }

    @Test
    fun `④ 帧率无关 - 60 30 15fps 下 1 秒的旋转位移一致`() {
        val mid = 0.6f
        val a = rotProd(1f, 60, mid)
        val b = rotProd(1f, 30, mid)
        val c = rotProd(1f, 15, mid)
        assertEquals("60/30fps 位移必须一致", a, b, 1e-4f)
        assertEquals("60/15fps 位移必须一致", a, c, 1e-4f)
        // 解析解：(0.3 + mid*0.5) × FPS_BASE 度/秒
        assertEquals((0.3f + mid * 0.5f) * C.FPS_BASE, a, 1e-3f)
    }

    @Test
    fun `⑤ 60fps 下与旧实现逐像素等同（迁移不改变观感）`() {
        for (mid in floatArrayOf(0f, 0.35f, 1f)) {
            assertEquals("mid=$mid 时 60fps 位移必须与旧实现相等",
                rotOld(1f, 60, mid), rotProd(1f, 60, mid), 1e-4f)
        }
    }

    @Test
    fun `⑥ hue 基准流速与旧实现一致 且段落跨度随 sectionEnergy 单调`() {
        // 旧实现每帧增量 = 0.35 + treble*2 ⇒ sectionHue = 0 时必须完全一致
        for (treble in floatArrayOf(0f, 0.5f, 1f)) {
            assertEquals("treble=$treble 时基准流速必须等于旧实现",
                0.35f + treble * 2f, hueRate(0f, treble), 1e-6f)
        }
        // §B4-③：段落能量单调抬升流速
        assertTrue("sectionEnergy 升高必须加快色相流动",
            hueRate(1f, 0f) > hueRate(0.5f, 0f) && hueRate(0.5f, 0f) > hueRate(0f, 0f))
        assertEquals("满载跨度 = 基准 + HUE_RATE_SECTION",
            0.35f + 1.20f, hueRate(1f, 0f), 1e-6f)
    }

    @Test
    fun `⑦ 段落 EMA dt 化 - 三档帧率 1 秒后差异小于 2%`() {
        val target = 1f
        val a = sectionProd(1f, 60, target)
        val b = sectionProd(1f, 30, target)
        val c = sectionProd(1f, 15, target)
        assertTrue("60/15fps 的 EMA 结果必须接近（实际 $a vs $c）", abs(a - c) < 0.02f)
        assertTrue("60/30fps 的 EMA 结果必须接近（实际 $a vs $b）", abs(a - b) < 0.02f)
        // 且确实在向 target 收敛（不是恒 0 的假实现）
        assertTrue("EMA 必须真的向 target 收敛（实际 $a）", a > 0.40f && a < 0.50f)
    }

    @Test
    fun `⑧ 双边明暗 - 切向偏移正交于径向 且两侧明度差 = 0_42`() {
        // 切向单位向量 (-sin, cos) 与径向单位向量 (cos, sin) 点积恒为 0
        for (deg in intArrayOf(0, 37, 90, 143, 180, 271, 359)) {
            val a = deg * (Math.PI.toFloat() / 180f)
            val tx = -kotlin.math.sin(a)
            val ty = kotlin.math.cos(a)
            val rx = kotlin.math.cos(a)
            val ry = kotlin.math.sin(a)
            assertTrue("deg=$deg 时切向必须正交于径向", abs(tx * rx + ty * ry) < 1e-5f)
        }
        // 两侧沿切向错开 ±off ⇒ 分离量 = 2×off，径向分量 = 0
        val off = 1f * C.SIDE_OFFSET
        assertEquals("两侧间距 = 2 × SIDE_OFFSET × wdt", 0.7f, 2f * off, 1e-6f)
        // 明度差（§A2-1 的 −0.22 / +0.20）
        assertEquals("暗侧 + 亮侧的明度差", 0.42f, C.SIDE_LIT_DARK + C.SIDE_LIT_BRIGHT, 1e-6f)
    }

    // ═══════════════════════════ ⑨–⑪ 源码扫描段 ═══════════════════════════

    @Test
    fun `⑨ 源码 - 衰减层在 cb_restore 之后、恰好 1 处、且不是每帧新建 Paint`() {
        val body = codeOfE18()
        val restoreIdx = body.indexOf("cb.restore()")
        val decayIdx = body.indexOf("cb.drawRect(")
        assertTrue("必须能定位 cb.restore() 与衰减层", restoreIdx >= 0 && decayIdx >= 0)
        assertTrue("衰减层必须在 cb.restore() 之后（否则黑层会跟着旋转/缩放，四角露白）",
            decayIdx > restoreIdx)
        assertEquals("衰减层只能有 1 处", 1, Regex("cb\\.drawRect\\(").findAll(body).count())
        assertTrue("衰减层必须复用成员画笔（decayPaint）", body.contains("decayPaint"))
        // ⛔ 判据只扫 drawContent 体：成员初始化里的 `Paint()` 是构造期一次，合规。
        //    （踩过：把判据作用在整个类体上 ⇒ 对合法的成员画笔假 FAIL）
        val draw = funBody(body, "drawContent")
        assertTrue("必须能定位 drawContent 体", draw.isNotEmpty())
        assertFalse("⛔ 不得在每帧路径（drawContent）里新建 Paint",
            Regex("Paint\\(").containsMatchIn(draw))
    }

    @Test
    fun `⑩ 源码 - 3-tap 循环上界必须可被 §7_5 成本脚本解析`() {
        val body = codeOfE18()
        assertTrue("循环上界必须是 `for (t in 0 until TAP_COUNT)`（常量 ⇒ 符号表可解析）",
            body.contains("for (t in 0 until TAP_COUNT) {"))
        assertFalse("⛔ 不得用 `.indices` 作上界 —— 成本脚本的 resolve_bound() 对它返回 None，" +
            "会把循环体内调用记进「未解析」列 ⇒ §7.5 出现**假性降耗**",
            body.contains("TAP_SCALE.indices") || body.contains("TAP_ALPHA.indices"))
        assertTrue("TAP_COUNT 必须是 const val（脚本符号表只收 const val/val = 数字）",
            body.contains("const val TAP_COUNT = 3"))
        // 与 TAP 表长度一致（行为段 ② 已断言，这里再兜一次源码形态）
        assertEquals("drawImageRect 只能有 1 个调用点（循环内）",
            1, Regex("cb\\.drawImageRect\\(").findAll(body).count())
        assertEquals("双边明暗 ⇒ drawLine 恰好 2 个调用点",
            2, Regex("cb\\.drawLine\\(").findAll(body).count())
    }

    @Test
    fun `⑪ 源码 - postFx 必须是数值字面量（门禁原版正则正负双证）`() {
        // 门禁原版正则（照抄 FxCoverageScanTest）
        val postFxRe = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""")
        val numRe = Regex("""=\s*([0-9]*\.?[0-9]+)f""")
        val body = codeOfE18()
        val m = postFxRe.find(body)
        assertTrue("E18 必须声明 postFx（否则会被判『未覆盖后处理』）", m != null)
        val nums = numRe.findAll(m!!.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals("门禁原版正则必须取到 2 个字面量", listOf("0.48", "0.030"), nums)
        assertTrue("至少一个通道 > 0 才会被判『已覆盖』", nums.any { it.toFloat() > 0f })

        // 负向：具名常量写法必须被**静默判否**（证明这条判据真的认字面量）
        val named = "override val postFx = PostFx(vignette = VIGNETTE, grain = GRAIN)"
        val mn = postFxRe.find(named)
        assertTrue("具名常量仍能匹配到参数表", mn != null)
        assertTrue("⛔ 具名常量必须取不到数值字面量（这正是它会被静默判否的原因）",
            numRe.findAll(mn!!.groupValues[1]).none())
    }

    // ═══════════════════════════ 负向自证 ═══════════════════════════

    @Test
    fun `负向N1 旧「每帧固定增量」必须被判帧率绑定`() {
        val mid = 0.6f
        val at60 = rotOld(1f, 60, mid)
        val at30 = rotOld(1f, 30, mid)
        // 生产实现是帧率无关的；旧实现恰好差 2 倍 ⇒ 断言④/⑤ 真的能区分二者
        assertNotEquals("旧实现 30fps 位移不应等于 60fps（证明断言④能抓住该错误）", at60, at30, 1e-3f)
        assertEquals("旧实现 30fps 恰好是 60fps 的一半", 0.5f, at30 / at60, 1e-4f)
        assertEquals("生产实现在同条件下必须相等", rotProd(1f, 60, mid), rotProd(1f, 30, mid), 1e-4f)
    }

    @Test
    fun `负向N2 每帧固定系数 EMA 必须被判帧率绑定`() {
        val k = 0.02f   // WorldRenderer:612 的形态（每帧 ×0.02，不看 dt）
        val at60 = sectionPerFrame(1f, 60, 1f, k)
        val at15 = sectionPerFrame(1f, 15, 1f, k)
        assertTrue("每帧固定系数 EMA 必须表现出强帧率依赖（实际 $at60 vs $at15）",
            abs(at60 - at15) > 0.30f)
        // 生产实现同条件下必须接近 ⇒ 断言⑦能区分二者
        assertTrue("生产实现必须帧率无关", abs(sectionProd(1f, 60, 1f) - sectionProd(1f, 15, 1f)) < 0.02f)
    }

    @Test
    fun `负向N3 把 TAP_ALPHA 当绝对 alpha 用必须被判总亮度不符`() {
        val base = 0.9f   // alpha ∈ 0.88..0.94
        val relative = C.TAP_ALPHA.sum() * base            // 生产实现：相对 ⇒ 总亮度 == base
        val absolute = C.TAP_ALPHA.sum()                   // 误读：绝对 ⇒ 总亮度 == 1.0
        assertEquals("生产实现的总亮度必须等于基准 alpha", base, relative, 1e-6f)
        assertNotEquals("绝对 alpha 的误读必须与基准不同（否则这条口径无法被检测）",
            base, absolute, 1e-6f)
    }

    @Test
    fun `负向N4 用 indices 作循环上界必须被 ⑩ 的判据识别`() {
        // 模拟误改回去的写法 —— ⑩ 的 assertFalse 正是针对它
        val broken = "for (t in TAP_SCALE.indices) { cb.drawImageRect() }"
        assertTrue("判据必须能识别 .indices 写法", broken.contains("TAP_SCALE.indices"))
        assertFalse("正确写法不含 .indices", "for (t in 0 until TAP_COUNT) {".contains("indices"))
    }

    // ═══════════════════════════ 源码定位 ═══════════════════════════

    /** `UltraRenderers.kt` 里 `MilkdropRenderer` 类体，**已剥注释**（判据不得命中 KDoc 举例） */
    private fun codeOfE18(): String = stripComments(classBody(readUltra(), "MilkdropRenderer"))

    private fun readUltra(): String =
        mainSourceRoot().resolve("com/nasmusic/tv/visualizer/renderers/UltraRenderers.kt").readText()

    private fun classBody(src: String, name: String): String {
        val head = Regex("""(?m)^\s*(?:(?:internal|open|abstract|private|sealed|final)\s+)*class\s+$name\b""")
        val m = head.find(src) ?: return ""
        val brace = src.indexOf('{', m.range.last)
        if (brace < 0) return ""
        var depth = 0
        var i = brace
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(brace, i + 1)
                }
            }
            i++
        }
        return src.substring(brace)
    }

    /**
     * 从**已剥注释**的类体里取某个函数的 `{...}` 体。
     *
     * ⛔ 为什么必须切函数体：成员初始化（如 `private val decayPaint = Paint()...`）是**构造期一次**，
     * 合规；把判据作用在整个类体上会对它假 FAIL（本仓库踩过）。签名要认 receiver
     * （`override fun DrawScope.drawContent(` ⇒ `(?:[A-Za-z0-9_.]+\.)?`）。
     */
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
     *
     * ⛔ 只去行注释不够 —— 判据会命中 KDoc 正文里举的写法（本仓库踩过多次）。
     * ⚠️ 本 KDoc 正文不得出现块注释的定界符字面量，否则会提前闭合本注释。
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

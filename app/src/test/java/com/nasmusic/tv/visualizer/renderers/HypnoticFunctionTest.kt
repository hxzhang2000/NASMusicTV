package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E25 催眠（§B8 · T4.8）门禁。
 *
 * 四段：
 * - **迁移段**：`: RendererFx()` + 三个内容钩子 + 不覆写 `final` + 随机源偏差（`shuffleRng`）；
 * - **观感段（§B8-①~④）**：三级明度（alpha **与线宽**）/ 曲线双层 + 法线高光 / 方格纸 tile /
 *   `postFx` 字面量；
 * - **dt 段**：帧率绑定修复（`fx.dt` 取代「相位内累计时间」）+ **行为**验证帧率无关；
 * - **性能段**：全部 `draw*` 函数体内零堆分配。
 *
 * ⛔ 负向自证 2 条（N1 / N2）—— 缺一条就可能空转。
 * ⛔ 源码判据一律**先剥注释**（本仓库已踩 5 次：判据命中自己刚写的 KDoc ⇒ 假 FAIL）。
 * ⛔ 负向样本必须与正向**喂同一份谓词**（不要另写「预期为 false」的表达式）。
 */
class HypnoticFunctionTest {

    private val C = HypnoticFunctionRenderer.Companion

    /** 逐帧绘制函数（性能段扫描面：这些函数体里不得出现堆分配） */
    private val drawFns = listOf(
        "drawContent", "drawStroke", "drawStill", "drawDissolve", "drawDissolvePoints",
        "drawDissolveParticles", "drawGridAndAxes", "drawTickLabels", "drawFormula",
        "drawDecor", "drawGap",
    )

    // ═══════════════════════════ ① 迁移形态 ═══════════════════════════

    @Test
    fun `① 已迁移 RendererFx - 不覆写 final、三个内容钩子齐备`() {
        val body = classBody(codeOfE25(), "HypnoticFunctionRenderer")
        assertTrue("类体必须能切出来（空转自证）", body.isNotEmpty())
        assertTrue("必须 `: RendererFx()`", ": RendererFx()" in body)
        assertTrue(
            "必须实现 drawContent",
            Regex("""fun\s+(?:[A-Za-z0-9_.]+\.)?drawContent\s*\(""").containsMatchIn(body)
        )
        assertTrue("必须实现 onEnterContent", "fun onEnterContent(" in body)
        assertTrue("必须实现 onExitContent", "fun onExitContent(" in body)
        // ⛔ 模板方法是 final ⇒ 子类覆写会编译错
        assertFalse(
            "⛔ 不得覆写 final 的 draw",
            Regex("""override\s+fun\s+DrawScope\.draw\s*\(""").containsMatchIn(body)
        )
        assertFalse(
            "⛔ 不得覆写 final 的 onEnter",
            Regex("""override\s+fun\s+onEnter\s*\(""").containsMatchIn(body)
        )
        assertFalse(
            "⛔ 不得覆写 final 的 onExit",
            Regex("""override\s+fun\s+onExit\s*\(""").containsMatchIn(body)
        )
        // ⛔ 基类已提供 `rng`（VisualizerRandom）⇒ 不得再声明同名成员
        assertFalse(
            "⛔ 不得自己声明 rng（基类已提供）",
            Regex("""(?m)^\s*private\s+val\s+rng\s*=""").containsMatchIn(body)
        )
        // ⛔ 不得用 ctx.nowMs（基类帧时钟是唯一合法来源，§四 G13 重复 ⑥）
        assertFalse("⛔ 不得出现 ctx.nowMs", "ctx.nowMs" in body)

        val raw = readFile(renderersFile("HypnoticFunctionRenderer.kt"))
        assertFalse(
            "⛔ 不得再 import VisualizerRenderer",
            "import com.nasmusic.tv.visualizer.VisualizerRenderer" in raw
        )
        assertFalse(
            "⛔ 不得 import VisualizerRandom（基类已提供 rng）",
            "import com.nasmusic.tv.visualizer.VisualizerRandom" in raw
        )
    }

    // ═══════════════════ ② §B8-④ postFx 字面量（正负双证） ═══════════════════

    @Test
    fun `② postFx 字面量在位且判据正负双证`() {
        val body = classBody(codeOfE25(), "HypnoticFunctionRenderer")
        assertTrue("postFx 必须含 > 0 的数值字面量（门禁判据）", coveredByPostFx(body))
        assertTrue("必须是暗角 0.44", "vignette = 0.44f" in body)
        assertTrue("必须是颗粒 0.028", "grain = 0.028f" in body)
        // ⛔ 负向：具名常量必须静默判否（门禁只认字面量 —— 已知陷阱）
        assertFalse(
            "⛔ 具名常量必须判否（否则门禁会静默放过）",
            coveredByPostFx("class X : RendererFx() {\n    override val postFx = PostFx(vignette = V, grain = G)\n}")
        )
        assertFalse(
            "PostFx.NONE 必须判否",
            coveredByPostFx("class X : RendererFx() {\n    override val postFx = PostFx.NONE\n}")
        )
    }

    // ═════════════════════ ③ §B8-① 网格三级明度 ═════════════════════

    @Test
    fun `③ §B8-① 网格三级明度 - alpha 与线宽都要分档`() {
        val body = classBody(codeOfE25(), "HypnoticFunctionRenderer")
        val grid = funBody(body, "drawGridAndAxes")
        assertTrue("drawGridAndAxes 必须能切出来（空转自证）", grid.isNotEmpty())

        // 生产常量（直调生产 API，不复制数值）
        assertEquals("主刻度 alpha", 0.55f, HypnoticFunctionRenderer.AXIS_ALPHA, 1e-6f)
        assertEquals("次刻度 alpha", 0.30f, HypnoticFunctionRenderer.TICK_ALPHA, 1e-6f)
        assertEquals("细网格 alpha", 0.14f, HypnoticFunctionRenderer.GRID_ALPHA, 1e-6f)
        // ⛔ §B8 的问题描述是「同色**单线**」⇒ 只分 alpha 仍是同宽，必须一并分档
        assertTrue(
            "线宽必须三级递减：主轴 > 次刻度 > 细网格",
            HypnoticFunctionRenderer.AXIS_STROKE_W > HypnoticFunctionRenderer.TICK_STROKE_W &&
                HypnoticFunctionRenderer.TICK_STROKE_W > HypnoticFunctionRenderer.GRID_STROKE_W
        )
        assertTrue(
            "alpha 必须三级递减：主轴 > 次刻度 > 细网格",
            HypnoticFunctionRenderer.AXIS_ALPHA > HypnoticFunctionRenderer.TICK_ALPHA &&
                HypnoticFunctionRenderer.TICK_ALPHA > HypnoticFunctionRenderer.GRID_ALPHA
        )

        assertTrue("三级明度判据必须成立（正）", threeTierGrid(grid))
        // ⛔ 负向样本在 N2 里统一喂同一份谓词
    }

    // ═════════════════ ④ §B8-② 曲线双层 + 法线高光 ═════════════════

    @Test
    fun `④ §B8-② 曲线主线 2.2f 加 0.9f 高光线、上移 0.8px`() {
        val body = classBody(codeOfE25(), "HypnoticFunctionRenderer")
        assertEquals("主线宽必须 2.2f", 2.2f, HypnoticFunctionRenderer.MAIN_STROKE_W, 1e-6f)
        assertEquals("高光线宽必须 0.9f", 0.9f, HypnoticFunctionRenderer.HIGHLIGHT_STROKE_W, 1e-6f)
        assertEquals("主线 alpha 基值必须 0.9f", 0.9f, HypnoticFunctionRenderer.MAIN_ALPHA_BASE, 1e-6f)
        assertEquals(
            "高光线向白插值必须 0.6f", 0.6f,
            HypnoticFunctionRenderer.HIGHLIGHT_WHITE_MIX, 1e-6f
        )
        assertEquals("高光线上移量必须 0.8f px", 0.8f, HypnoticFunctionRenderer.HIGHLIGHT_OFFSET, 1e-6f)
        assertTrue(
            "主线必须比高光线粗（否则高光被完全盖住）",
            HypnoticFunctionRenderer.MAIN_STROKE_W > HypnoticFunctionRenderer.HIGHLIGHT_STROKE_W
        )
        assertTrue(
            "高光线插值量必须 > 0（否则不是高光）",
            HypnoticFunctionRenderer.HIGHLIGHT_WHITE_MIX > 0f
        )

        // DRAW 与 HOLD 两态都要有受光侧（否则 HOLD 期曲线又变回平色线）
        for (fn in listOf("drawStroke", "drawStill")) {
            val f = funBody(body, fn)
            assertTrue("$fn 必须能切出来（空转自证）", f.isNotEmpty())
            assertTrue("$fn 必须有受光侧判据（正）", hasLitEdge(f))
        }
        // 高光色必须在 palette 变化时缓存（⛔ 不得每帧算 towardWhite ⇒ 那是每帧 Color 分配）
        val refresh = funBody(body, "refreshColors")
        assertTrue("refreshColors 必须能切出来", refresh.isNotEmpty())
        assertTrue(
            "highlightColor 必须在 refreshColors 里算（零每帧分配）",
            "highlightColor = VisualizerMath.towardWhite(" in refresh
        )
        // ⛔ 负向样本在 N2 里统一喂同一份谓词
    }

    // ═════════════════════ ⑤ §B8-③ 方格纸底纹 ═════════════════════

    @Test
    fun `⑤ §B8-③ 方格纸 tile alpha 0.10`() {
        val body = classBody(codeOfE25(), "HypnoticFunctionRenderer")
        val draw = funBody(body, "drawContent")
        assertEquals("PAPER_ALPHA 必须 0.10f", 0.10f, HypnoticFunctionRenderer.PAPER_ALPHA, 1e-6f)
        assertTrue("drawContent 必须有 PAPER 底纹判据（正）", hasPaper(draw))
        // ⛔ 负向（喂同一份谓词）：把 PAPER 换成裸 alpha（无 tile 语义）必须判否
        assertFalse(
            "⛔ 未引用 Id.PAPER 的 drawImage 必须判否",
            hasPaper("drawImage(it, dstSize = IntSize(iw, ih), alpha = 0.10f)")
        )
        // ⛔ 必须在 drawContent 内调 ensure（首次组合 onEnter 拿 Size.Zero）
        assertTrue("ensure 必须在 drawContent 内调", "ProceduralTexture.ensure(iw, ih)" in draw)
    }

    // ═════════════════════ ⑥ dt 段：帧率绑定修复 ═════════════════════

    @Test
    fun `⑥ dt 化 - 只取 fx.dt 且不再有相位累计写法`() {
        val body = classBody(codeOfE25(), "HypnoticFunctionRenderer")
        val draw = funBody(body, "drawContent")
        assertTrue(
            "drawContent 必须把 fx.dt 传给描线/溃散",
            "drawStroke(frame, ctx, w, h, fx.dt)" in draw &&
                "drawDissolve(frame, ctx, w, h, elapsed, fx.dt)" in draw
        )
        assertFalse("⛔ drawContent 不得再有旧写法（判据正）", usesPhaseElapsedDt(draw))
        assertFalse("⛔ 全类体也不得再有旧写法", usesPhaseElapsedDt(body))
        // 描线累加必须走生产纯函数
        assertTrue(
            "drawStroke 必须调 advanceStroke",
            "drawAccumulator = advanceStroke(drawAccumulator, dt, speed)" in body
        )
        // 局部量不得再叫 `fx`（那个名字已被 FxFrame 占用，避免误读成帧对象）
        assertFalse(
            "⛔ drawStroke 内不得再声明 `val fx =`",
            Regex("""val\s+fx\s*=""").containsMatchIn(funBody(body, "drawStroke"))
        )
    }

    @Test
    fun `⑥b 行为 - advanceStroke 帧率无关`() {
        // 新写法（**直调生产纯函数**）：30 / 60 / 120 fps 下描线都必须 ≈ 8.0s
        for (fps in intArrayOf(30, 60, 120)) {
            var acc = 0f
            var frames = 0
            val dt = 1f / fps
            while (acc < HypnoticFunctionRenderer.DRAW_MS && frames < 100000) {
                acc = C.advanceStroke(acc, dt, 1f)
                frames++
            }
            val sec = frames.toFloat() / fps
            assertEquals("${fps}fps 下描线必须 ≈ 8.0s（实际 $sec s）", 8f, sec, 0.05f)
        }
        // speed 系数必须线性（音频驱动不能改变时长口径）
        assertEquals("speed=2 ⇒ 单位时间推进翻倍", 2000f, C.advanceStroke(0f, 1f, 2f), 1e-3f)
        assertEquals("speed=1 ⇒ 单位时间推进 1000ms", 1000f, C.advanceStroke(0f, 1f, 1f), 1e-3f)
        // 首帧 dt = 0（基类 FrameClock 契约）⇒ 累加器必须原地不动
        assertEquals("dt=0 必须不推进", 123f, C.advanceStroke(123f, 0f, 1.5f), 1e-6f)
    }

    // ═════════════════════ ⑦ 随机源偏差（§12.4） ═════════════════════

    @Test
    fun `⑦ 随机源不与基类 rng 撞名且 seed 注入仍生效`() {
        val body = classBody(codeOfE25(), "HypnoticFunctionRenderer")
        assertTrue("必须保留改名后的 shuffleRng", "private val shuffleRng = Random(seed)" in body)
        assertTrue(
            "weightedShuffle 必须收到 shuffleRng",
            "FunctionLibrary.weightedShuffle(shuffleRng, order, -1)" in body
        )
        assertFalse("⛔ 类体内不得出现 VisualizerRandom", "VisualizerRandom" in body)
        // seed 注入的确定性由 HypnoticScheduleTest 覆盖；这里只断言构造参数仍在
        assertTrue("构造参数 seed 必须保留（测试注入确定性）", "private val seed: Long" in body)
    }

    // ═════════════════════ ⑧ 性能段：每帧零堆分配 ═════════════════════

    @Test
    fun `⑧ 全部 draw 函数体内零堆分配`() {
        val body = classBody(codeOfE25(), "HypnoticFunctionRenderer")
        val offenders = mutableListOf<String>()
        var seen = 0
        for (fn in drawFns) {
            val f = funBody(body, fn)
            if (f.isEmpty()) continue
            seen++
            for ((label, re) in ALLOC_RES) {
                if (re.containsMatchIn(f)) offenders.add("$fn: $label")
            }
        }
        assertTrue("必须扫到至少 8 个 draw* 函数体（空转自证，实际 $seen）", seen >= 8)
        assertTrue("⛔ 每帧路径不得有堆分配：$offenders", offenders.isEmpty())
        // ⛔ 反向自证：把成员初始化那种真实分配喂进同一份谓词，必须报出
        val memberInit = "private val glowStroke = Stroke(width = 10f, cap = StrokeCap.Round)"
        assertTrue(
            "判据必须能抓到真实的 Stroke 分配（否则谓词是空转）",
            ALLOC_RES.any { (_, re) -> re.containsMatchIn(memberInit) }
        )
        // ⛔ Offset / IntSize 是 value class ⇒ 不得被当成分配
        assertTrue(
            "Offset 是 value class，不得被判为分配",
            ALLOC_RES.none { (_, re) -> re.containsMatchIn("drawCircle(c, r, Offset(a, b))") }
        )
        assertTrue(
            "IntSize 是 value class，不得被判为分配",
            ALLOC_RES.none { (_, re) -> re.containsMatchIn("drawImage(b, dstSize = IntSize(iw, ih))") }
        )
    }

    // ═══════════════════ 负向自证 N1 / N2 ═══════════════════

    @Test
    fun `负向N1 旧的相位累计 dt 必须被 dt 判据抓到`() {
        assertTrue(
            "⛔ N1：旧的 `(now - phaseStartMs) / 1000f` 必须被 dt 判据抓到",
            usesPhaseElapsedDt(OLD_DT_SNIPPET)
        )
        // 正向：生产代码不得命中
        val body = classBody(codeOfE25(), "HypnoticFunctionRenderer")
        assertFalse("正向：drawContent 不得命中", usesPhaseElapsedDt(funBody(body, "drawContent")))
        assertFalse("正向：全类体不得命中", usesPhaseElapsedDt(body))
        // ⛔ 判据必须有区分力：去掉 `/ 1000f` 的近似写法不得误报
        assertFalse(
            "判据不得误报 `(now - phaseStartMs)` 的其它用法（如 coerceAtLeast）",
            usesPhaseElapsedDt("val elapsed = (now - phaseStartMs).coerceAtLeast(0L)")
        )
    }

    @Test
    fun `负向N2 旧的网格与曲线形态必须被观感判据抓到`() {
        assertFalse("⛔ N2a：旧网格（三层同色单线）必须判否", threeTierGrid(OLD_GRID_SNIPPET))
        assertFalse("⛔ N2b：旧曲线（单条平色主线）必须判否", hasLitEdge(OLD_CURVE_SNIPPET))
        // 同一份谓词在**正向**样本上必须成立（区分力自证）
        val body = classBody(codeOfE25(), "HypnoticFunctionRenderer")
        assertTrue(
            "N2 自证：正向样本必须通过三级明度判据",
            threeTierGrid(funBody(body, "drawGridAndAxes"))
        )
        assertTrue(
            "N2 自证：正向样本必须通过受光侧判据",
            hasLitEdge(funBody(body, "drawStroke"))
        )
        assertTrue(
            "N2 自证：正向样本必须通过受光侧判据（HOLD 态）",
            hasLitEdge(funBody(body, "drawStill"))
        )
    }

    // ═══════════════════════════ 判据谓词 ═══════════════════════════

    /** 门禁原版判据（与 `FxCoverageScanTest` 逐字一致）：只认 `postFx` 里的**数值字面量** */
    private val postFxRe = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""")
    private val numRe = Regex("""=\s*([0-9]*\.?[0-9]+)f""")

    private fun coveredByPostFx(code: String): Boolean {
        val m = postFxRe.find(code) ?: return false
        return numRe.findAll(m.groupValues[1]).any { it.groupValues[1].toFloat() > 0f }
    }

    /** §B8-① 三级明度：三档 alpha 各就各位 **且** 三档线宽各不相同 */
    private fun threeTierGrid(code: String): Boolean =
        code.contains("GRID_ALPHA * alphaScale") && code.contains("style = gridStroke") &&
            code.contains("TICK_ALPHA * alphaScale") && code.contains("style = tickStroke") &&
            code.contains("AXIS_ALPHA * alphaScale") && code.contains("style = axisStroke")

    /** §B8-② 受光侧：整体上移 `HIGHLIGHT_OFFSET` 的高光线（`translate` + 专用 stroke/色） */
    private fun hasLitEdge(code: String): Boolean =
        code.contains("translate(0f, -HIGHLIGHT_OFFSET)") &&
            code.contains("style = highlightStroke") &&
            code.contains("highlightColor")

    /** §B8-③ 方格纸底纹：必须是 `Id.PAPER` tile（不是裸 alpha 的 drawImage） */
    private fun hasPaper(code: String): Boolean =
        code.contains("ProceduralTexture.Id.PAPER") && code.contains("PAPER_ALPHA")

    /** ⛔ 帧率绑定判据：旧的「相位内累计时间」写法（必须带 `/ 1000f` 才判定） */
    private fun usesPhaseElapsedDt(code: String): Boolean =
        Regex("""\(\s*now\s*-\s*phaseStartMs\s*\)\s*/\s*1000f""").containsMatchIn(code)

    /** 每帧路径的堆分配模式（⛔ `Offset` / `IntSize` / `Color` 是 value class，**不算**分配） */
    private val ALLOC_RES = listOf(
        "Stroke(" to Regex("""(?<![A-Za-z0-9_])Stroke\s*\("""),
        "Path()" to Regex("""(?<![A-Za-z0-9_])Path\s*\(\s*\)"""),
        "Paint(" to Regex("""(?<![A-Za-z0-9_])Paint\s*\("""),
        "IntArray(" to Regex("""(?<![A-Za-z0-9_])IntArray\s*\("""),
        "FloatArray(" to Regex("""(?<![A-Za-z0-9_])FloatArray\s*\("""),
        "ArrayList(" to Regex("""(?<![A-Za-z0-9_])ArrayList\s*\("""),
        "listOf(" to Regex("""(?<![A-Za-z0-9_])listOf\s*\("""),
        "mutableListOf(" to Regex("""(?<![A-Za-z0-9_])mutableListOf\s*\("""),
        "Rect(" to Regex("""(?<![A-Za-z0-9_])Rect\s*\("""),
        "createBitmap(" to Regex("""Bitmap\s*\.\s*createBitmap\s*\("""),
    )

    /** 旧实现片段（负向样本；**只**用于证明判据能抓到它） */
    private val OLD_GRID_SNIPPET = """
        drawPath(gridPath, axisColor.copy(alpha = 0.14f * alphaScale), style = mainStroke)
        drawPath(axisPath, axisColor.copy(alpha = 0.55f * alphaScale), style = mainStroke)
        drawPath(tickPath, axisColor.copy(alpha = 0.4f * alphaScale), style = mainStroke)
    """.trimIndent()

    private val OLD_CURVE_SNIPPET = """
        val glowA = 0.16f + frame.energy * 0.14f
        drawPath(curvePath, glowColor, alpha = glowA, style = glowStroke, blendMode = BlendMode.Plus)
        drawPath(curvePath, mainColor, alpha = (0.85f + frame.bass * 0.1f).coerceAtMost(1f), style = mainStroke)
    """.trimIndent()

    private val OLD_DT_SNIPPET =
        "val dt = ((now - phaseStartMs) / 1000f).coerceIn(0f, 0.1f)"

    // ═══════════════════════════ 辅助 ═══════════════════════════

    /** 切出 `class <name>` 的类体（到下一个顶层 class / 文件尾） */
    private fun classBody(txt: String, name: String): String {
        val m = Regex("""(?m)^\s*(?:(?:internal|open|abstract|private)\s+)*class\s+$name\b""").find(txt)
            ?: return ""
        val rest = txt.substring(m.range.last + 1)
        val nxt = Regex("""(?m)^\s*(?:(?:internal|open|abstract|private)\s+)*class\s+\w+""").find(rest)
        return if (nxt == null) {
            txt.substring(m.range.first)
        } else {
            txt.substring(m.range.first, m.range.last + 1 + nxt.range.first)
        }
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

    /** `HypnoticFunctionRenderer.kt` 的**已剥注释**全文 */
    private fun codeOfE25(): String =
        stripComments(readFile(renderersFile("HypnoticFunctionRenderer.kt")))

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

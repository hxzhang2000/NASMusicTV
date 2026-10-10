package com.nasmusic.tv.visualizer.fx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §八 G7 —— `FxCoverageScanTest`（**源码扫描** · T2.5 首次启用）。
 *
 * ## 判据（v1.18 起口径 · 本文件是唯一权威）
 * 每个**在册渲染器**必须"覆盖后处理"，下列二者之一即可：
 *  - **已迁移 `RendererFx`**（`class X : RendererFx()`）⇒ 必须有 `override val postFx`，
 *    且 `vignette` / `grain` / `scanline` **至少一个 > 0**；
 *  - **未迁移**（`class X : VisualizerRenderer`）⇒ `override fun DrawScope.draw(...)` 体内
 *    **至少调用一次 `OverlayFx.`**。
 *
 * ⚠️ **口径更新（见 §12.4）**：§八 G7 原文只写「`draw` 内至少调用一次 `OverlayFx.*`」——
 * 这在 §5.5 基类迁移之后**必然误判**：`RendererFx.draw` 是 `final`，子类只写 `drawContent`，
 * 后处理由基类按 `postFx` 施加 ⇒ 迁移后的渲染器**永远不会**在源码里出现 `OverlayFx.`。
 * 故判据改为上面两条并列。
 *
 * ## 豁免机制（见 §12.4 偏差）
 * §八 G7 原文用 `// Fx-exempt: 理由` 行级标记；本实现改为**测试内的集中名单 [exempt]**，
 * 阶段推进时把类名从 [exempt] 移进 [covered] 即可（T3.8 / T4.11 / T5.8 各移一批）。
 * 理由：标记要写进 24 个文件、且会在后续三个阶段被逐批删除 ⇒ 集中名单等价且免 churn。
 *
 * ## 负向自证（§八 开头规矩：必须有，否则门禁可能是空转）
 *  N1 从 [covered] 摘掉一个**真覆盖**的类 ⇒ 完整性断言必须挂；
 *  N2 把一个**未覆盖**的类塞进 [covered] ⇒ 覆盖断言必须挂
 *     （⚠️ 样本由 [pickUncoveredSample] 按**不变式**现场挑选，**不写死类名** ——
 *      E11/E14/E16/E18/E19/E20/E23/E25/E34/E35 都当过样本，写死则每迁移一套就过期一次）；
 *  N3 **View 型排除自证**：`WorldGlobeRenderer` 移出 [exempt] ⇒ 完整性断言必须挂
 *     （证明豁免名单真的在起作用，而不是"恰好没扫到"）；
 *  N4 判据函数自证：含 `OverlayFx.` 判覆盖 / 不含判未覆盖 / **注释里的不算** /
 *     `postFx` 非 `NONE` 判覆盖 / `PostFx.NONE` 判未覆盖。
 */
class FxCoverageScanTest {

    // ── 覆盖名单：已打开后处理（阶段推进时逐条从 [exempt] 移入）──
    private val covered = listOf(
        "CircularRingRenderer",        // E05 圆形频谱环（§A2-6）
        "LiquidGridRenderer",          // E13 液态网格（§A5-6）
        "LiquidRippleRenderer",        // E15 液态涟漪（§A6-5）
        "ConstellationRenderer",       // E17 星座（§A7-5）
        "EcgWaveRenderer",             // E24 心跳（§A8-4 CRT 后处理）
        "RadarGridRenderer",           // E30 雷达（§A9-3 CRT 后处理）
        "BeatFireworkRenderer",        // E14 节拍烟花（§B2-④ 暗角 0.44 + 颗粒 0.030）
        "MatrixRainRenderer",          // E16 数字雨（§B3-④ 暗角 0.50 + 颗粒 0.030 + 扫描线 0.16）
        "MilkdropRenderer",            // E18 反馈残像（§B4 + §12.4 补后处理 暗角 0.48 + 颗粒 0.030）
        "LyricsDotMatrixRenderer",     // E23 歌词点阵（§B7 暗角 0.44 + 颗粒 0.026）
        "HypnoticFunctionRenderer",    // E25 催眠（§B8 暗角 0.44 + 颗粒 0.028）
        "LightBeamsRenderer",          // E35 光轴（§B10 暗角 0.50 + 颗粒 0.030）
        "StarrySkyRenderer",           // E42 星空星轨（暗角 0.42 + 颗粒 0.026）
        "SeasideRenderer",             // E43 海边（§4.9.2 裁决：只保留暗角 0.30，去掉颗粒）
        "MoonlitRenderer",             // E44 明月（§9.2 填充账：只保留暗角 0.42，⛔ 不加颗粒 = 第二次全屏 drawRect）
    )

    // ── 豁免名单：阶段推进时逐条移入 covered（理由必须写明，便于复核） ──
    private val exempt = mapOf(
        // ⭐ 阶段 3 · 批次 A（E03/E05/E07/E12/E13/E15/E17/E24/E30/E31/E32）已**全部**移入 covered
        // ⭐ 阶段 4 · 批次 B 自 T4.1 起逐套移入，T4.10 收尾 ⇒ **10 套全部完成**
        //    （E11 / E14 / E16 / E18 / E19 / E20 / E23 / E25 / E34 / E35）
        // 阶段 5 · 批次 C 7 套
        "OrbitalRingsRenderer" to "S5 批次 C（E29 轨道）",
        "ConcentricGearsRenderer" to "S5 批次 C（E33 齿轮）",
        "MoleculeRenderer" to "S5 批次 C（E37 分子）",
        "VintageTvRenderer" to "S5 批次 C（E38 怀旧）· ⛔ 不继承基类（后处理与内容交错 + 画面已定稿）",
        "DnaRenderer" to "S5 批次 C（E40 DNA）",
        // 结构型豁免（非"还没做"，而是**按设计不适用**）
        "WorldGlobeRenderer" to "View 型（`isViewBased = true`）：draw 是空实现且根本不被调用",
        // M11 修复（2026-10-06，代码审查报告 §4）：WorldRenderer（旧 E41 2D 版，2106 行
        // 零实例化死代码）已整文件删除归档；本条目随之从豁免名单移除（stale 判据
        // 会在文件存在而名单缺失时报错，删除后名单必须同步，见 :266-267 的 stale 门禁）。
        "PhotoRenderer" to "E39 照片墙：§八 G7 原始口径即豁免 PHOTO_WALL（照片优先，暗角/颗粒降到 0.34 / 0.018）",
    )

    private val scannedRoots = listOf("renderers", "photo")

    // ── 源码定位（照抄 PerfBudgetContractTest 的 mainSourceRoot 手法）──

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
     * 剥掉块注释（Kotlin 块注释可嵌套）与行注释。⚠️ **必须保持行数与原文件一致**
     * —— 否则 `drawReachable` 之类按行号的定位会漂移（与 `PerfBudgetContractTest` 同一实现）。
     */
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
                var depth = 1
                while (i < src.length && depth > 0) {
                    if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') { depth++; i += 2 }
                    else if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') { depth--; i += 2 }
                    else { if (src[i] == '\n') sb.append('\n'); i++ }
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

    /** 一个在册渲染器的解析结果 */
    private data class Decl(
        val name: String,
        val file: String,
        val fxBase: Boolean,
        val view: Boolean,
        val body: String,
    )

    /**
     * ⛔ **必须是 `[ \t]*` 而不是 `\s*`**（v1.38 修）：`\s` 含换行，配合 `(?m)^` 后，
     * 类声明**前面有空行**时匹配起点会落在那个空行的 `\n` 上 ⇒
     * `lineStart = lastIndexOf('\n', first) + 1` 与 `lineEnd = indexOf('\n', first)`
     * 双双等于该 `\n` 的下标，`lineStart` 还多 1 ⇒ `substring` 抛
     * `StringIndexOutOfBoundsException: begin N, end N-1`（本类 8 个用例全挂）。
     * 改成只吃水平空白后，匹配起点恒在声明行的首个非空白字符上。
     */
    private val classRe = Regex("""(?m)^[ \t]*(?:(?:internal|open|abstract)[ \t]+)*class[ \t]+(\w+)""")
    private val viewRe = Regex("""isViewBased[^\n]*=\s*true""")
    private val drawFunRe = Regex("""override\s+fun\s+DrawScope\.draw\s*\(""")
    private val postFxRe = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""")
    private val numRe = Regex("""=\s*([0-9]*\.?[0-9]+)f""")

    /** 从 `{` 起做花括号配对，返回含首尾括号的整段 */
    private fun braceBody(text: String, openIdx: Int): String {
        var depth = 0
        var i = openIdx
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(openIdx, i + 1)
                }
            }
            i++
        }
        return text.substring(openIdx)
    }

    /**
     * 解析在册渲染器。⚠️ 必须支持**构造参数跨行**的类头
     * （`class MoleculeRenderer(\n  private val seed: Long = ...\n) : VisualizerRenderer {`）——
     * 只按单行正则匹配会**静默漏类**，门禁就变成空转。
     */
    private fun rendererDecls(): List<Decl> {
        val out = mutableListOf<Decl>()
        for (sub in scannedRoots) {
            val dir = File(mainSourceRoot(), sub)
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val stripped = stripComments(f.readText())
                for (m in classRe.findAll(stripped)) {
                    val lineStart = stripped.lastIndexOf('\n', m.range.first) + 1
                    val lineEnd = stripped.indexOf('\n', m.range.first).let { if (it < 0) stripped.length else it }
                    if (stripped.substring(lineStart, lineEnd).contains("abstract class")) continue
                    val name = m.groupValues[1]
                    if (name == "RendererFx") continue
                    // 类头 = 声明行起、到第一个 '{' 为止
                    val window = stripped.substring(lineStart, minOf(lineEnd + 400, stripped.length))
                    val brace = window.indexOf('{')
                    if (brace < 0) continue
                    val header = window.substring(0, brace)
                    val fxBase = header.contains(": RendererFx(")
                    if (!fxBase && !header.contains(": VisualizerRenderer")) continue
                    val body = braceBody(stripped, lineStart + brace)
                    out.add(Decl(name, f.name, fxBase, viewRe.containsMatchIn(body), body))
                }
            }
        }
        return out
    }

    /** 判据 A：已迁移基类 ⇒ `postFx` 至少一个通道 > 0 */
    private fun coveredByPostFx(classBody: String): Boolean {
        val m = postFxRe.find(classBody) ?: return false
        return numRe.findAll(m.groupValues[1]).any { it.groupValues[1].toFloat() > 0f }
    }

    /**
     * ⛔ **必须同时认 `with(OverlayFx)` 与 `OverlayFx.xxx()` 两种写法**（v1.38 修）。
     * 原来只找字面量 `"OverlayFx."`，而 `RendererFx` 迁移后生产代码一律写成
     * `with(OverlayFx) { ... }` ⇒ 该字面量在主源码里**根本不存在**（只出现在 import 与 KDoc）
     * ⇒ 判据 B 对 6 个未迁移渲染器**恒判「未覆盖」**，B 段整体空转。
     */
    private val overlayUseRe = Regex("""with\s*\(\s*OverlayFx\s*\)|OverlayFx\s*\.""")

    /** 判据 B：未迁移 ⇒ `draw` 体内至少一次使用 `OverlayFx`（两种写法都认） */
    private fun coveredByOverlayCall(classBody: String): Boolean {
        val m = drawFunRe.find(classBody) ?: return false
        val brace = classBody.indexOf('{', m.range.last)
        if (brace < 0) return false
        return overlayUseRe.containsMatchIn(braceBody(classBody, brace))
    }

    private fun isCovered(d: Decl): Boolean =
        if (d.fxBase) coveredByPostFx(d.body) else coveredByOverlayCall(d.body)

    /**
     * 结构型豁免（非「还没做」，而是**按设计不适用**：View 型 / 死代码 / G7 原始口径豁免）。
     * ⚠️ 只用于 [pickUncoveredSample] 的**偏好**（它们永远不覆盖，作样本区分力弱）——
     * 判据强度不依赖本集合，即使它过期，N2 也只是退回到拿结构型当样本，不会失真。
     */
    private val structuralExempt = setOf("WorldGlobeRenderer", "PhotoRenderer")  // WorldRenderer 已删除（M11）

    /**
     * N2 的样本选择（**不变式**，⛔ 不写死类名）。
     *
     * 优先从 [exempt] 里挑「**按设计可覆盖、只是还没做**」的类（排除 [structuralExempt]）；
     * 批次 B / C 全部做完后 [exempt] 只剩结构型，此时兜底仍会选中它们。
     * 返回 `null` ⇒ [exempt] 里没有任何未覆盖的类 ⇒ 负向自证失效，N2 必须报错。
     */
    private fun pickUncoveredSample(decls: Map<String, Decl>): String? {
        val prefer = exempt.keys.filter { it !in structuralExempt }
        return (prefer + exempt.keys).firstOrNull { n -> decls[n]?.let { !isCovered(it) } == true }
    }

    // ── 正向断言 ──

    @Test
    fun `在册渲染器全部有归属 名单无重叠无遗漏`() {
        val decls = rendererDecls()
        assertTrue("扫描到的渲染器类应 > 0（空转自证）", decls.isNotEmpty())
        assertTrue("渲染器类数应 ≥ 22（2026-10-05 删 9 个效果后 22；M11 修复（2026-10-06）再删死代码 WorldRenderer → 21；2026-10-08 新增 E44 明月 → 22），实测 ${decls.size}", decls.size >= 22)
        // ⛔ 解析器自证：多行构造参数的两个类必须被解析到，否则是"静默漏类"
        // ⚠️ 2026-10-05：原样本 `WaterfallRenderer` 随效果删除 ⇒ 只摘它一个，保留其余样本。
        for (n in listOf("MoleculeRenderer", "HypnoticFunctionRenderer", "PhotoRenderer")) {
            assertTrue("解析器应解析到 $n（漏类会让门禁空转）", decls.any { it.name == n })
        }

        val overlap = covered.toSet() intersect exempt.keys
        assertTrue("覆盖名单与豁免名单不得重叠：$overlap", overlap.isEmpty())

        val unassigned = decls.map { it.name }.filter { it !in covered && it !in exempt.keys }
        assertTrue(
            "下列渲染器既不在覆盖名单也不在豁免名单（新加渲染器必须显式归属）：$unassigned",
            unassigned.isEmpty()
        )

        val stale = (covered + exempt.keys).filter { n -> decls.none { it.name == n } }
        assertTrue("下列名单项在源码里已不存在（改名/删除后必须同步名单）：$stale", stale.isEmpty())
    }

    @Test
    fun `覆盖名单里的渲染器必须已覆盖后处理`() {
        val decls = rendererDecls().associateBy { it.name }
        for (n in covered) {
            val d = decls[n] ?: error("覆盖名单里的 $n 未在源码中找到")
            assertTrue(
                "$n 未覆盖后处理 —— RendererFx 子类需 `postFx` 非 NONE；未迁移类需在 draw 内调 `OverlayFx.*`",
                isCovered(d)
            )
        }
    }

    @Test
    fun `豁免名单里的渲染器当前确实未覆盖`() {
        val decls = rendererDecls().associateBy { it.name }
        val wrong = exempt.keys.filter { n -> decls[n]?.let { isCovered(it) } == true }
        assertTrue(
            "下列渲染器其实**已覆盖**后处理，应从 exempt 移入 covered" +
                "（否则豁免名单会藏住真实覆盖）：$wrong",
            wrong.isEmpty()
        )
    }

    @Test
    fun `View 型渲染器被识别并必须豁免`() {
        val decls = rendererDecls().associateBy { it.name }
        val view = decls.values.filter { it.view }.map { it.name }
        assertTrue("应识别到 WorldGlobeRenderer（判据 = `isViewBased … = true`），实测 $view",
            view.contains("WorldGlobeRenderer"))
        assertTrue("View 型必须豁免（其 draw 是空实现且不被调用，不豁免会恒判违规）：$view",
            view.all { it in exempt.keys })
    }

    // ── 负向自证 ──

    @Test
    fun `负向N1 覆盖名单摘掉真覆盖的类必须被完整性断言抓到`() {
        val decls = rendererDecls()
        // ⚠️ 2026-10-05：样本原为 `WaterfallRenderer`（E12），随效果删除 ⇒ 换成 `MilkdropRenderer`
        //   （E18，仍在 covered 里）。⚠️ 样本**必须仍在 [covered] 中**否则这条会退化成空转。
        val sample = "MilkdropRenderer"
        assertTrue("前提：样本 $sample 必须仍在 covered 里，否则本负向自证空转", sample in covered)
        val broken = covered - sample
        val unassigned = decls.map { it.name }.filter { it !in broken && it !in exempt.keys }
        assertTrue("摘掉真覆盖的类 ⇒ 它应落入「无归属」", unassigned.contains(sample))
    }

    @Test
    fun `负向N2 未覆盖的类塞进覆盖名单必须被覆盖断言抓到`() {
        val decls = rendererDecls().associateBy { it.name }
        // ⚠️ 样本必须是**当前确实未覆盖**的类。⛔ 不写死类名：E11 / E14 / E16 / E18 / E19 / E20 /
        //    E23 / E25 / E34 / E35 都当过样本，写死则**每迁移一套就过期一次**（本仓库第 N 次踩同类坑）
        //    ⇒ 改为**不变式**：从 [exempt] 里现场挑一个「按设计可覆盖、只是还没做」的类。
        val sample = pickUncoveredSample(decls)
        assertTrue("exempt 里必须存在**未覆盖**的类可作样本，否则本负向自证会退化成空转", sample != null)
        val d = decls.getValue(sample!!)
        assertFalse("前提：$sample 当前未覆盖（若它已覆盖，应移入 [covered] 而不是留在这里）", isCovered(d))
        val brokenCovered = covered + sample
        val bad = brokenCovered.filter { n -> decls[n]?.let { isCovered(it) } != true }
        assertTrue("塞进未覆盖的类 ⇒ 覆盖断言应报出它（样本 $sample）", bad.contains(sample))
    }

    @Test
    fun `负向N3 View 型移出豁免名单必须被完整性断言抓到`() {
        val decls = rendererDecls()
        val brokenExempt = exempt - "WorldGlobeRenderer"
        val unassigned = decls.map { it.name }.filter { it !in covered && it !in brokenExempt.keys }
        assertTrue(
            "View 型移出豁免 ⇒ 应落入「无归属」（证明豁免名单真的在起作用，而不是恰好没扫到）",
            unassigned.contains("WorldGlobeRenderer")
        )
    }

    @Test
    fun `负向N4 判据函数自证 注释里的调用不算数`() {
        val bodyWith = "class X : VisualizerRenderer {\n" +
            "    override fun DrawScope.draw(f: AudioFrame, c: RenderContext) {\n" +
            "        with(OverlayFx) { drawVignette(c, 0.44f) }\n    }\n}"
        assertTrue("含真实调用（with 接收者写法）⇒ 判覆盖", coveredByOverlayCall(bodyWith))

        val bodyDirect = "class X : VisualizerRenderer {\n" +
            "    override fun DrawScope.draw(f: AudioFrame, c: RenderContext) {\n" +
            "        OverlayFx.drawVignette(c, 0.44f)\n    }\n}"
        assertTrue("含真实调用（直接调用写法）⇒ 判覆盖", coveredByOverlayCall(bodyDirect))

        val bodyWithout = "class X : VisualizerRenderer {\n" +
            "    override fun DrawScope.draw(f: AudioFrame, c: RenderContext) {\n" +
            "        drawCircle(Color.Red, 1f)\n    }\n}"
        assertFalse("draw 内无调用 ⇒ 判未覆盖", coveredByOverlayCall(bodyWithout))

        val bodyCommented = "class X : VisualizerRenderer {\n" +
            "    override fun DrawScope.draw(f: AudioFrame, c: RenderContext) {\n" +
            "        // OverlayFx.drawGrain(c, 1L, 0.03f)\n    }\n}"
        assertFalse(
            "⛔ 注释里的 OverlayFx. 不得算数（stripComments 后应消失）",
            coveredByOverlayCall(stripComments(bodyCommented))
        )

        val bodyCommentedWith = "class X : VisualizerRenderer {\n" +
            "    override fun DrawScope.draw(f: AudioFrame, c: RenderContext) {\n" +
            "        // with(OverlayFx) { drawVignette(c, 0.44f) }\n    }\n}"
        assertFalse(
            "⛔ 注释里的 with(OverlayFx) 同样不得算数",
            coveredByOverlayCall(stripComments(bodyCommentedWith))
        )

        assertTrue("postFx 非 NONE ⇒ 判覆盖",
            coveredByPostFx("class X : RendererFx() {\n    override val postFx = PostFx(vignette = 0.44f, grain = 0.03f)\n}"))
        assertFalse("postFx = PostFx.NONE ⇒ 判未覆盖",
            coveredByPostFx("class X : RendererFx() {\n    override val postFx = PostFx.NONE\n}"))
    }
}

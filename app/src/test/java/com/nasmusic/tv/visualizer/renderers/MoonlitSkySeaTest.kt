package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E44「明月」**T6 接线门禁**（§3.2 层 1/2/10 + §3.3 色温 + 暗角 `edgeOverride`）。
 *
 * 管的是"渲染器**真的**按 §3.1 定稿接了这把尺"，⛔ 不是尺本身对不对（那归 [MoonSeascapeTest]），
 * 也不是星野会不会长成方阵（那归 [MoonlitStarfieldTest] = G11）。
 *
 * ## 为什么必须是源码扫描
 * 本批全部判据都落在**绘制现场**（层序、混合模式、色值来源），而 `DrawScope` 需要真 Canvas；
 * 真机上屏归 T10（所有者）。先例同形：[MoonPhaseShadowTest]（T5）与 `SeasideTest`（E43）。
 *
 * ## 三条"静默失效"是本类专门防的
 * 1. **色值退回字面量** —— §3.1 一改端点，画面上就多一块与色尺脱节的亮矩形（§十一 T6 的原话）。
 *    ①②③ 用"渲染器文件里一个 `0xFFRRGGBB` 都不许出现"把这条路堵死。
 * 2. **`postFx` 写成具名常量** —— `FxCoverageScanTest` 的 `postFxRe`/`numRe` 只认数值字面量，
 *    具名写法**不报错**、只是效果从后处理覆盖名单里**静默掉出**。④ 复刻那两条正则做正负双证
 *    （先例 `StarrySkyTest.⑧` / `MilkdropTest.⑪`）。
 * 3. **`Brush.verticalGradient(vararg)` 写进 `draw*` 体内** —— 每次调用分配 vararg 数组，
 *    破零分配红线；而 `PerfBudgetContractTest` 的禁项清单里**没有** `Brush`，这条洞必须自己补
 *    （E43 `SeasideTest:103` 同做法）。⑦。
 *
 * ⚠️ 所有判据跑在**剥掉注释**的源码上：类头红线里**原文引用**了 `clipPath(` / `clip(RoundRect`
 *   / `0xFF0A111D` 这些"要被抓"的写法，不剥注释就是自己判自己红。
 */
class MoonlitSkySeaTest {

    // ── ① 层 1：两块矩形 + 零字面色值 ──────────────────────────────────────────

    @Test
    fun `① 渲染器文件里一个十六进制色值都不许出现`() {
        val src = rendererSource()
        val hits = Regex("""0xFF[0-9A-Fa-f]{6}""").findAll(src).map { it.value }.toList()
        assertEquals(
            "⛔ 海/天色必须全部来自 [MoonSeascape] 这把尺（§3.1 一改色阶就得跟着走）；" +
                "字面量 ${hits} 是抄出来的第二份端点",
            emptyList<String>(),
            hits,
        )
        // 而尺本身确实持有这些端点（防"两边都没写、只是编译过了"）
        val ruler = seascapeSource()
        for (hex in listOf("0xFF03050C", "0xFF060B18")) {
            assertTrue("[MoonSeascape] 里应当有定稿字面量 $hex", ruler.contains(hex))
        }
    }

    @Test
    fun `①b 天空与海体是两块不相交矩形 各用一条渐变`() {
        val body = functionBody("drawContent", rendererSource())
        assertTrue("层 1a 必须铺天空渐变", body.contains("drawRect(brush = skyBrush"))
        assertTrue("层 1b 必须铺海体渐变", body.contains("seaBrush"))
        // ⛔ 不相交是 §9 记账 `SKY_BASE 填充 = 1.000` 的前提，也是 P-1 崩溃规避的一部分
        assertTrue("海矩形必须从 horizonY 起（⛔ 与天空重叠 ⇒ 填充记账翻倍 + API 22 TJunction 风险）",
            body.contains("topLeft = Offset(0f, horizonY"))
        assertTrue("海矩形高度必须是 h - horizonY", body.contains("Size(w, h - horizonY)"))
        assertEquals("层 1 只两块矩形（第三块 = 重叠或整屏覆盖）",
            2, Regex("""\bdrawRect\(""").findAll(body).count())
    }

    // ── ② 色阶来源：全部走尺 ───────────────────────────────────────────────────

    @Test
    fun `② 天空渐变的三档全部由色尺给出 地平档吃 altT`() {
        val init = initializerOf("skyBrush")
        assertTrue("天顶档必须取 MoonSeascape.SKY_TOP", init.contains("MoonSeascape.SKY_TOP"))
        assertTrue("中段档必须取 MoonSeascape.SKY_MID", init.contains("MoonSeascape.SKY_MID"))
        assertTrue("中段停靠位必须取 MoonSeascape.SKY_MID_STOP", init.contains("MoonSeascape.SKY_MID_STOP"))
        assertTrue("地平档必须**调用** skyHorizon(ALT_T)（§3.3 色温；⛔ 不能填 altT=0 的基准色）",
            init.contains("MoonSeascape.skyHorizon(MoonSeascape.ALT_T)"))
        assertEquals("停靠位必须正好三档", 3, Regex("""to\s+Color\(""").findAll(init).count())
    }

    @Test
    fun `②b 海体渐变的三档就是 sea 尺的三个端点 不许写死小数`() {
        // ⚠️ T9 之前它是字段 `seaBrush`（按中性 sb 建一条）；§八 的 `bass → 海面亮度` 接上之后，
        //    它变成**按桶缓存**的 `seaBrushOf`（[MoonlitSkySeaTest ②c] 负责钉"仍只三条渐变"）。
        //    判据的实质**一字未改**：三条色停必须全部**调用** [MoonSeascape.sea]、⛔ 不写死小数。
        val body = functionBody("seaBrushOf", rendererSource())
        assertEquals("海体渐变必须恰好三段", 3, Regex("""MoonSeascape\.sea\(""").findAll(body).count())
        assertTrue("浅水档 depth=0", body.contains("sea(0f, sb)"))
        assertTrue("分段档必须用尺上的 SEA_SPLIT（⛔ 重抄 0.35）",
            body.contains("sea(MoonSeascape.SEA_SPLIT, sb)"))
        assertTrue("深水档 depth=1", body.contains("sea(1f, sb)"))
        assertTrue("分段停靠位也必须用 SEA_SPLIT", body.contains("MoonSeascape.SEA_SPLIT to Color"))
        // ⭐ 新增（T9）：亮度系数只能取**桶中心**，⛔ 不能取当帧连续的 `sb` ——
        //    缓存里存的是"按桶建的那条渐变"，拿连续值核对就是"画的与算的对不上"（D10 的同族）。
        assertTrue("亮度系数必须来自 seaBrightOfBucket(bucket)",
            Regex("""seaBrightOfBucket\(\s*bucket\s*\)""").containsMatchIn(body))
        assertFalse("⛔ 体内不得用连续值 MoonAudio.seaBright(",
            body.contains("MoonAudio.seaBright("))
    }

    @Test
    fun `②c 三条纵向渐变全在缓存入口建好 不进任何 draw 体`() {
        val src = rendererSource()
        // ⚠️ T8 之前这里是「恰 2」= sky/sea；T8 落了 §7.6 地平带 ⇒ 第三条**必须是** [MoonlitRenderer]
        //    的缓存入口 `bandBrushOf`（按 `occl` 分桶），⛔ 不是第 3 条逐帧新建的渐变。
        //    判据的实质没变：**任何** `fun DrawScope.draw*` 体内都不得出现 `Brush.verticalGradient`。
        assertEquals("纵向渐变只许 sky/sea/地平带 三条",
            3, Regex("""Brush\.verticalGradient\(""").findAll(src).count())
        for (name in drawFunctionNames(src)) {
            assertFalse("⛔ `$name` 体内不得构造 Brush（vararg 数组 = 逐帧分配；" +
                "PerfBudgetContractTest 不查 Brush，这条洞由本例补）",
                functionBody(name, src).contains("Brush.verticalGradient"))
        }
        assertTrue("第三条必须落在按桶缓存的 bandBrushOf 里（⛔ 落在 drawHorizonBand 里就是逐帧分配）",
            functionBody("bandBrushOf", src).contains("Brush.verticalGradient"))
        // ⭐ T9 起海体那条也**必须**落在按桶缓存的 seaBrushOf 里（判据与地平带同一条形态）
        assertTrue("海体渐变必须落在按桶缓存的 seaBrushOf 里",
            functionBody("seaBrushOf", src).contains("Brush.verticalGradient"))
        // 具名属性必须是 `private val`（字段）而不是 `private fun`（每次调用重建）
        assertTrue("skyBrush 必须是字段", Regex("""private\s+val\s+skyBrush\s*=""").containsMatchIn(src))
        // ⛔ 但海体**不能**再是字段：§八 让它吃 `bass` ⇒ 一个字段只能存一条渐变。
        //    换成"字段数组 + 缓存入口"，负向自证在下面两行（缺任一个判据都会静默退化成逐帧新建）。
        assertFalse("⛔ 不许复活 `private val seaBrush`（一个中性钉挡不住 32 个桶）",
            Regex("""private\s+val\s+seaBrush\s*=""").containsMatchIn(src))
        assertTrue("seaBrushes 必须是字段数组",
            Regex("""private\s+val\s+seaBrushes\s*=\s*arrayOfNulls<Brush>\(\s*MoonAudio\.SEA_SB_BUCKETS\s*\)""")
                .containsMatchIn(src))
        assertTrue("缓存必须真的写回（只读不写 = 每次调用都重建 native 着色器）",
            Regex("""seaBrushes\[\s*bucket\s*\]\s*=\s*built""").containsMatchIn(functionBody("seaBrushOf", src)))
    }

    // ── ③ 色温与暗角 ───────────────────────────────────────────────────────────

    @Test
    fun `③ 暗角边色取自色尺 且经 edgeOverride 生效`() {
        val src = rendererSource()
        // ⚠️ 这里用**整条声明**的正则而不是 initializerOf —— 后者返回的是 `Color(…)` 括号**内**的片段，
        //    判"包了一层 Color"必须连着外层一起看。
        assertTrue(
            "VIGNETTE_EDGE 必须恰为 Color(MoonSeascape.sea(0f, SB_NEUTRAL))" +
                "（⛔ 重抄 0xFF0A111D、⛔ 用封面 accent）",
            Regex("""val\s+VIGNETTE_EDGE\s*=\s*Color\(\s*MoonSeascape\.sea\(\s*0f\s*,\s*SB_NEUTRAL\s*\)\s*\)""")
                .containsMatchIn(src),
        )
        val postFx = postFxArgs(src)
        assertTrue("postFx 必须挂 vignetteEdge（OverlayFx.drawVignette 的 edgeOverride 非 null 时**完全取代** accent，" +
            "见 OverlayFx.kt:44-52；漏了就是暖边冷夜盘）", postFx.contains("vignetteEdge = VIGNETTE_EDGE"))
        assertTrue("暗角强度必须是字面量 0.42f（§3.2 层 10）", postFx.contains("vignette = 0.42f"))
    }

    @Test
    fun `③b altT 只有一份 盘色温与天色同源`() {
        val bake = bakeSource()
        assertFalse("⛔ MoonDiskBake 不得再声明 ALT_T（§3.3+ 的教训：重抄端点会各自漂移）",
            Regex("""const\s+val\s+ALT_T""").containsMatchIn(bake))
        assertEquals("盘色温三行必须全部引用 MoonSeascape.ALT_T",
            3, Regex("""MoonSeascape\.ALT_T""").findAll(bake).count())
        // 天空那侧的引用点（地平档）也必须走同一份
        assertTrue("天空地平档引用的是同一个 ALT_T",
            initializerOf("skyBrush").contains("MoonSeascape.ALT_T"))
        assertEquals("MoonSeascape 才是唯一定义处",
            1, Regex("""val\s+ALT_T\s*:""").findAll(seascapeSource()).count())
    }

    // ── ④ postFx 的静默失效（复刻门禁正则做正负双证）───────────────────────────

    @Test
    fun `④ postFx 数值项必须字面量 具名写法会静默掉出覆盖名单`() {
        // 与 FxCoverageScanTest:150-151 逐字一致
        val postFxRe = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""")
        val numRe = Regex("""=\s*([0-9]*\.?[0-9]+)f""")
        val m = postFxRe.find(rendererSource())
        assertTrue("必须声明 postFx（否则被判『未覆盖后处理』）", m != null)
        val nums = numRe.findAll(m!!.groupValues[1]).map { it.groupValues[1] }.toList()
        // ⚠️ E44 是**多行**声明 + `vignetteEdge = <具名 Color>`：`[^)]*` 会一路吃到收尾的 `)`，
        //    所以多行与具名 Color 项都被容忍；被禁的只是**数值项**写成具名常量。
        assertEquals("门禁必须取到 1 个数值字面量（只有 vignette）", listOf("0.42"), nums)
        assertTrue("至少一个通道 > 0 才算已覆盖", nums.any { it.toFloat() > 0f })

        // 负向：把 0.42f 换成具名常量 ⇒ 参数表照样匹配、数值一个都取不到 ⇒ 静默判否
        val named = "override val postFx = PostFx(\n vignette = VIGNETTE_STRENGTH,\n vignetteEdge = VIGNETTE_EDGE,\n)"
        val mn = postFxRe.find(named)
        assertTrue("具名写法仍能匹配到参数表（所以它不会编译失败、也不会报错）", mn != null)
        assertTrue("⛔ 具名数值必须取不到字面量 ⇒ 这正是类头第 3 条禁它的原因",
            numRe.findAll(mn!!.groupValues[1]).none())
        // 负向：带类型标注 + getter 会让整条正则失配
        assertTrue("带类型标注的写法整条正则失配",
            postFxRe.find("override val postFx: PostFx = PostFx(vignette = 0.42f)") == null)
    }

    @Test
    fun `④b 本效果刻意不开颗粒与扫描线 因此不调 ensureTiled`() {
        val src = rendererSource()
        val postFx = postFxArgs(src)
        assertFalse("grain 是第二次全屏 drawRect（+1.00 屏）⇒ 撞破棘轮；" +
            "那笔账由 MoonOpBudgetTest 用 overdraw(HIGH)+GRAIN_EXTRA_FILL 机器化，" +
            "本例只钉『没开』这个事实（⛔ 别在这里手抄合计屏数，改表就会漂）",
            postFx.contains("grain"))
        assertFalse("同理不开 scanline", postFx.contains("scanline"))
        // ⚠️ 对 §十一 T6 行"ensureTiled() 不能省"的**偏离**（登记 §十五）：
        //   那条红线针对配了 grain/scanline 的效果（OverlayFx.drawGrain 读不到平铺槽会**静默不画**）。
        //   本效果两者皆 0 ⇒ applyPostFx 两支都不成立（RendererFx.kt:107-108），平铺槽一张也不读。
        assertFalse("不调 ensureTiled（前提：上一条已钉死 grain/scanline 都为 0）",
            src.contains("ensureTiled"))
    }

    // ── ⑤ 层序 ─────────────────────────────────────────────────────────────────

    @Test
    fun `⑤ 层序 天空 海 星野 月盘 过曝芯 相位阴影`() {
        val body = functionBody("drawContent", rendererSource())
        val order = listOf(
            "drawRect(brush = skyBrush",
            "brush = seaBrush",
            "drawStarfield(",
            "drawMoonDisk(",
            "drawMoonBloom(",
            "drawPhaseShadow(",
        )
        var prev = -1
        for (token in order) {
            val at = body.indexOf(token)
            assertTrue("层序缺 `$token`（或位置在 `$token` 之前）", at > prev)
            prev = at
        }
        // ⭐ 星野必须在**一切自发光体之前**：它是背景，压在任何月/晕/柱之上就不是星野而是噪点了
        assertTrue("星野必须排在月盘之前",
            body.indexOf("drawStarfield(") < body.indexOf("drawMoonDisk("))
    }

    // ── ⑥ 星野：1 op / 1 屏内 / 加法 / 零 clip ─────────────────────────────────

    @Test
    fun `⑥ 星野裁剪靠源区 1 比 1 拷贝 不靠 clip`() {
        val body = functionBody("drawStarfield", rendererSource())
        assertTrue("必须逐张点名 STARFIELD（⛔ ensure() 一次烘 6 张全屏纹理 = 真机首帧黑屏 6369 ms 的根因）",
            body.contains("ensureFullscreenOnly(ProceduralTexture.Id.STARFIELD"))
        assertFalse("⛔ 不得调用整组 ensure(", body.contains("ProceduralTexture.ensure("))
        // 1:1 源区裁剪：srcSize == dstSize ⇒ 不纵向拉伸；只取顶部 skyH 行 ⇒ 海面的星从未提交
        assertTrue("源区必须是 (sw, sh)", body.contains("srcSize = IntSize(sw, sh)"))
        assertTrue("目标区必须与源区**同尺寸**（不同 = 纵向拉伸星点）", body.contains("dstSize = IntSize(sw, sh)"))
        assertTrue("sh 必须由 skyH 夹紧（裁剪到天空区的唯一手段）", body.contains("sh = skyH.coerceAtMost(tex.height)"))
        assertTrue("skyH 必须由 horizonY 得出", body.contains("skyH = horizonY.toInt()"))
        // 取不到纹理就跳过（tile 返回 null 是设计内状态，⛔ 不许 NPE 崩溃）
        assertTrue("tile 判空早退", body.contains("?: return"))
        val draws = Regex("""\bdraw(Image|Rect|Line|Circle|Path|Arc|Points)\s*\(""").findAll(body).count()
        assertEquals("星野只 1 个提交（与 MoonOpItem.STARS 的 ops 三档均为 1 吻合）", 1, draws)
        for (bad in listOf("clipPath(", "clip(", "RoundedCornerShape")) {
            assertFalse("⛔ 全文件禁物 $bad（API 22 创维 hwui SIGSEGV 红线）", rendererSource().contains(bad))
        }
    }

    @Test
    fun `⑥b 星野 α 与混合模式逐字承原型 加法而非覆盖`() {
        val src = rendererSource()
        assertTrue("STAR_A 必须是 0.62f（原型 moonlit-preview.html:1002 的 globalAlpha，⛔ 不是 E13 的 0.16/0.28）",
            Regex("""STAR_A\s*=\s*0\.62f""").containsMatchIn(src))
        val body = functionBody("drawStarfield", src)
        assertTrue("α 必须走 STAR_A 常数", body.contains("alpha = STAR_A"))
        assertTrue("⛔ 必须是加法混合（退成默认 source-over 会把天空渐变**压掉**：星不亮、天上多一块暗斑）",
            body.contains("blendMode = BlendMode.Plus"))
    }

    @Test
    fun `⑥c 星野填充记账 = HORIZON_K 恒等式`() {
        // §9 表里 STARS 的填充是**几何恒等式**（天空区占比），不是实测值 ⇒ 改了 HORIZON_K 必须同步表
        assertEquals("MoonOpItem.STARS 的填充必须等于 HORIZON_K",
            MoonlitRenderer.HORIZON_K.toDouble(), MoonOpItem.STARS.fillLow, 1e-6)
        assertEquals("三档只砍数量不砍分辨率 ⇒ 星野三档同为 1 提交",
            1, MoonOpItem.STARS.opsLow)
        assertEquals(1, MoonOpItem.STARS.opsMed)
        assertEquals(1, MoonOpItem.STARS.opsHigh)
        assertEquals("烘焙边长钳位必须与 E13/E42 同口径",
            4096, Regex("""STAR_TEX_EDGE_MAX\s*=\s*(\d+)""").find(rendererSource())!!
                .groupValues[1].toInt())
    }

    // ── 夹具与助手 ─────────────────────────────────────────────────────────────

    private fun postFxArgs(src: String): String {
        val m = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""").find(src)
            ?: error("找不到 postFx 声明")
        return m.groupValues[1]
    }

    /** 取 `val <name> = <初值>` 的初值（从第一个 `(` 起做括号配对）。 */
    private fun initializerOf(name: String): String {
        val src = rendererSource()
        val head = Regex("""val\s+$name\s*=\s*""").find(src) ?: error("找不到 $name")
        var i = head.range.last
        while (i < src.length && src[i] != '(') i++
        assertTrue("$name 的初值不是函数调用形式（助手需要更新）", i < src.length)
        var depth = 0
        var j = i
        while (j < src.length) {
            when (src[j]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(i + 1, j)
                }
            }
            j++
        }
        error("$name 括号不配对")
    }

    private fun drawFunctionNames(src: String): List<String> =
        Regex("""fun\s+(?:DrawScope\.)?(draw\w+)\s*\(""").findAll(src)
            .map { it.groupValues[1] }.distinct().toList()

    private fun functionBody(name: String, src: String): String {
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

    private fun rendererSource(): String = stripComments(
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/MoonlitRenderer.kt").readText()
    )

    private fun seascapeSource(): String = stripComments(
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/MoonSeascape.kt").readText()
    )

    private fun bakeSource(): String = stripComments(
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/MoonDiskBake.kt").readText()
    )

    /** 剥块注释（Kotlin 可嵌套）与行注释，⛔ 必须保持行数一致（定位按原始行号）。 */
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

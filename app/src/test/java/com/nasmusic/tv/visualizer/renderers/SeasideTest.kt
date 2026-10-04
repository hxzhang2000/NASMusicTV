package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E43 [com.nasmusic.tv.data.model.VisualizerTheme.SEASIDE]「海边」渲染器的**源码扫描门禁**
 * （§4.9.4 陷阱 ①③④ + §4.9.5 配套源码扫描断言）。
 *
 * ## 为什么是源码扫描而不是行为断言
 * `SeasideRenderer` 的字段初始化就会建 `Path` / `Paint` / `ImageBitmap`
 * ⇒ **纯 JVM 单测根本无法 `new`**（`android.*` 抛 "not mocked"）。
 * ⛔ 因此本类**绝不构造 `SeasideRenderer()`** —— 与 `StarrySkyTest` / `OrbitalStarFieldTest`
 * 同一纪律。G11 / G12 / G13 三道**数值**门在 `SeasideOpBudget`（纯函数）里，
 * 本类只守**结构**：那些纯函数门看不见的写法禁令。
 *
 * ## 四件套（照抄 `StarrySkyTest`，逐字同源）
 * [stripComments]（**保行数**）/ [classBody] / [funBody] / [anyOffendingLine]。
 *
 * ## ⛔ 为什么每条都要负向自证
 * 源码扫描门最大的失败模式是「谓词写错 ⇒ 对真实代码恒为通过」（空转）。
 * 本类末尾的 `负向…` 一例对每条判据喂一份**破实现样本**，证明谓词真的能挂。
 *
 * ## 判据作用面分工（⛔ 别搞混）
 * - **代码类判据**读**已剥注释**的正文（禁物、零分配、模板方法、时基、资源、批次）。
 * - **注释类判据**（元素清单完整性）读**未剥注释**的原文 —— 标注写在 KDoc 里。
 *   两者靠 [stripComments] **保行数**对齐行号。
 */
class SeasideTest {

    // ══════════════════════════════ ① 禁物 ══════════════════════════════

    @Test
    fun `① 全文件零 clipPath 零圆角 clip 零 RoundedCornerShape`() {
        val v = clipViolations(strippedFile())
        assertTrue("§4.9.3：Android 5.1 真机三次复现 hwui SIGSEGV，禁令范围是**整个** clipPath" +
            "（不分圆角与否）：\n" + v.joinToString("\n"), v.isEmpty())
        // 自证：扫描面非空（不是「什么都没扫到」）
        assertTrue("扫描面应含本类的真实代码", strippedFile().contains("class SeasideRenderer"))
    }

    @Test
    fun `①b drawRipples 与 drawResidue 的签名不得含 clip 参数`() {
        for (name in listOf("drawRipples", "drawResidue")) {
            val decl = declOf(strippedBody(), name)
            assertTrue("必须找到 $name 的声明（找不到 ⇒ 判据空转）", decl.isNotEmpty())
            assertFalse("§4.9.3：$name 的签名不得含 clip（改逐列 wetAmt 门控），实测：$decl",
                decl.contains("clip"))
        }
    }

    // ══════════════════════════════ ② DrawScope 接收者 ══════════════════════════════

    @Test
    fun `② 每个每帧绘制函数都必须带 DrawScope 接收者`() {
        val v = drawFnsWithoutDrawScope(strippedBody())
        assertTrue(
            "§4.9.4 陷阱 ①：`PerfBudgetContractTest` 的每帧可达正则只匹配 `fun DrawScope.drawXxx(`" +
                " ⇒ 漏掉接收者的函数会**整体逃过**零分配扫描：\n" + v.joinToString("\n"),
            v.isEmpty()
        )
        // 空转自证：真的扫到了足够多的绘制函数
        assertTrue("扫到的 draw* 函数数应 ≥ 23（防扫描空转），实测 ${drawFnDecls(strippedBody()).size}",
            drawFnDecls(strippedBody()).size >= 23)
    }

    // ══════════════════════════ ③ 湿沙 1 次 + 高光 1 次 = 合计 2 次提交 ══════════════════════════

    @Test
    fun `③ drawWetWash 与 drawSheen 各恰好一次 drawPath 合计两次且无逐帧渐变`() {
        val v = wetWashViolations(strippedBody())
        assertTrue(
            "§4.9.2 把 97 个逐列 createLinearGradient 折成**各一条 path + 各一支缓存竖向渐变**" +
                "（镜面高光因 NonZero 并入湿区而不可见 ⇒ 必须恢复它自己的第二次提交，" +
                "预算表 SHEEN.ops* = 1）：\n" + v.joinToString("\n"),
            v.isEmpty()
        )
        // 正向自证：两个函数体都真的取到了（否则「各 1 次」是空转）
        assertTrue("drawWetWash 体必须非空", funBody(strippedBody(), "drawWetWash").isNotEmpty())
        assertTrue("drawSheen 体必须非空", funBody(strippedBody(), "drawSheen").isNotEmpty())
    }

    // ══════════════════════════════ ④ 每帧零分配 ══════════════════════════════

    @Test
    fun `④ 零带参 Rect 零每帧容器分配 零字符串模板`() {
        val body = strippedBody()
        val raw = rawBody()
        val rects = offendingLines(body, raw, ::hasAllocRect)
        val containers = offendingLines(body, raw, ::hasContainerAlloc)
        val templates = offendingLines(body, raw, ::hasStringTemplate)
        assertTrue("零带参 Rect(：\n" + rects.joinToString("\n"), rects.isEmpty())
        assertTrue("零每帧容器分配：\n" + containers.joinToString("\n"), containers.isEmpty())
        assertTrue("零字符串模板：\n" + templates.joinToString("\n"), templates.isEmpty())
    }

    @Test
    fun `⑤ 补项目门禁的洞 - 每帧路径零 Brush 构造`() {
        // ⚠️ `PerfBudgetContractTest` 只查字符串模板 / Rect / 容器分配，
        //   ⛔ **不查** `Brush.verticalGradient(vararg)` 的逐帧 vararg 分配 —— E42 的 KDoc 正好踩中。
        val v = perFrameBrushViolations(strippedBody())
        assertTrue(
            "逐帧路径不得出现 Brush. / ShaderBrush( 构造（须在 rebuildGeometry 的烘焙函数里）：\n" +
                v.joinToString("\n"),
            v.isEmpty()
        )
        // 空转自证：真的扫到了逐帧函数体
        val bodies = drawFnDecls(strippedBody()).map { funBody(strippedBody(), it.name) }
        assertTrue("应至少取到 10 个非空 draw* 函数体，实测 ${bodies.count { it.isNotEmpty() }}",
            bodies.count { it.isNotEmpty() } >= 10)
    }

    // ══════════════════════════════ ⑥ Stroke 烘焙期构造 + postFx 单通道 ══════════════════════════════

    @Test
    fun `⑥ Stroke 只在两个烘焙期函数里构造且缓存规模不超门限`() {
        assertEquals("§4.9.2 定的缓存规模是 12（4 类 × 3 距离档）", 12, SeasideOpBudget.laceStrokeCacheSize())
        val body = strippedBody()
        val bake = funBody(body, "buildLaceStrokes")
        assertTrue("buildLaceStrokes 体必须非空", bake.isNotEmpty())
        assertTrue("缓存规模必须直接取 SeasideOpBudget.laceStrokeCacheSize（不得另立一份）",
            bake.contains("SeasideOpBudget.laceStrokeCacheSize()"))
        assertTrue("buildCausticStrokes 体必须非空（焦散三档线宽的唯一构造点）",
            funBody(body, "buildCausticStrokes").isNotEmpty())
        // ⭐ 构造点集合 ⊆ {buildLaceStrokes, buildCausticStrokes}。
        // 这条门禁的**意图**是「逐帧不得构造 Stroke」（`Stroke.width` 不可变 ⇒ 每边 new 一个
        // 就是每帧几百次 native 分配，§4.9.1 的解释调用数禁令），
        // ⛔ **不是**「全文件永远只能有一个构造点」：焦散的三档线宽同样只能在这里烘。
        assertEquals("⛔ `Stroke.width` 不可变 ⇒ 构造点只允许 buildLaceStrokes 与 " +
            "buildCausticStrokes 两处（都是烘焙期）", emptyList<String>(),
            strokeCtorViolations(body))
        // 逐帧函数体内零 Stroke(
        for (d in drawFnDecls(body)) {
            assertFalse("${d.name} 内不得出现 Stroke(", funBody(body, d.name).contains("Stroke("))
        }
    }

    @Test
    fun `⑥b postFx 必须是纯字面量单通道`() {
        val body = strippedBody()
        val m = POST_FX_RE.find(body)
        assertTrue("⛔ 必须写成 `override val postFx = PostFx(...)`（无类型标注 / 无 getter / 无具名常量）" +
            "，否则 FxCoverageScanTest 会**静默判否**", m != null)
        val args = m!!.groupValues[1]
        val channels = Regex("""([A-Za-z]+)\s*=\s*([0-9]*\.?[0-9]+)f""").findAll(args).toList()
        assertEquals("§4.9.2：只保留**一个**通道（去掉 grain，省 2.07 Mpx/帧），实测 $args", 1, channels.size)
        assertEquals("唯一通道必须是 vignette", "vignette", channels[0].groupValues[1])
        assertTrue("vignette 必须 > 0（否则 G7 判「未覆盖后处理」）",
            channels[0].groupValues[2].toFloat() > 0f)
        assertTrue("⛔ 不得出现 grain / scanline 通道", !args.contains("grain") && !args.contains("scanline"))
    }

    // ══════════════════════════════ ⑦ 位图回收 ══════════════════════════════

    @Test
    fun `⑦ 每个 Bitmap 字段都被退出路径的 try catch recycle 覆盖`() {
        val raw = rawBody()
        val fields = bitmapFields(strippedBody())
        assertTrue("应识别到 ≥ 5 个 Bitmap 字段（沙纹理 / 场 / 颗粒 / 斑驳 / 泡沫贴图组），实测 $fields",
            fields.size >= 5)
        val exit = funBody(raw, "onExitContent")
        assertTrue("onExitContent 必须释放（API 22–25 位图像素在 native 堆，只置 null 会拖到 OOM）",
            exit.contains("releaseResources("))
        val rel = funBody(raw, "releaseResources")
        assertTrue("releaseResources 必须 try/catch 包住回收", rel.contains("try {") && rel.contains("catch"))
        for (f in fields) {
            assertTrue("位图字段 $f 未在释放路径出现", rel.contains(f))
        }
        assertTrue("⛔ 不得调 ProceduralTexture.release()（归舞台）/ OverlayFx.release()（归基类）",
            !rel.contains("ProceduralTexture.release") && !rel.contains("OverlayFx.release"))
        assertTrue("⛔ Path 没有 recycle()，只能用 reset()（Compose Path ≠ android.graphics.Path）",
            rel.contains("reset()"))
    }

    @Test
    fun `⑦b onEnterContent 首行必须 releaseResources 且不复位时间原点`() {
        val raw = rawBody()
        val firstCode = stripComments(funBody(raw, "onEnterContent"))
            .lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && it != "{" }
        assertTrue("必须找到 onEnterContent 的首个语句（找不到 ⇒ 判据空转），实测 $firstCode", firstCode != null)
        assertTrue("⛔ 首行必须是 releaseResources()（画质切换会**重入** onEnter），实测 $firstCode",
            firstCode!!.contains("releaseResources()"))
        // ⛔ 不得复位时间原点 / 包络（否则切画质时浪的行程与水线一帧跳变）
        val body = stripComments(funBody(raw, "onEnterContent"))
        assertFalse("⛔ onEnterContent 不得复位 t0Ms", body.contains("t0Ms ="))
        assertFalse("⛔ onEnterContent 不得复位音频包络", body.contains("audio.reset()"))
        assertFalse("⛔ onEnterContent 不得按 ctx.canvasSize 烘焙（此刻是 Size.Zero）",
            body.contains("rebuildGeometry("))
    }

    // ══════════════════════════════ ⑧ 类头单行（防门禁静默失效）══════════════════════

    @Test
    fun `⑧ 类头必须是单行 否则本类被 RendererBaseContractTest 与 FxCoverageScanTest 静默剔除`() {
        val raw = rawSrc()
        val line = raw.lines().firstOrNull { CLASS_DECL_RE.containsMatchIn(it) }
        assertTrue("必须找到类声明行", line != null)
        assertTrue("类头必须写成单行 `class SeasideRenderer : RendererFx() {`，实测：$line",
            line!!.contains(": RendererFx()") && line.trimEnd().endsWith("{"))
        // ⭐ 复刻两道门真实的判定逻辑（正向）
        assertTrue("FxCoverageScanTest 的类头扫描必须认到本类", seenByFxGate(strippedSrc(), horizontal = true))
        assertTrue("RendererBaseContractTest 的类头扫描必须认到本类", seenByFxGate(strippedSrc(), horizontal = false))
    }

    // ══════════════════════════════ ⑨ 元素清单完整性 ══════════════════════════════

    @Test
    fun `⑨ 每个绘制函数都在 KDoc 标注 SeaOpItem 且 SeaOpItem 每一项都有归属`() {
        val v = inventoryViolations(rawSrc(), strippedSrc())
        assertTrue(
            "§4.9.5 的元素清单必须与实现一一对应（防 oracle 风险「漏元素」）：\n" + v.joinToString("\n"),
            v.isEmpty()
        )
        // 空转自证
        assertEquals("预算表的元素数是 21（20 项 + PUDDLE 补充行）", 21, SeaOpItem.entries.size)
        assertTrue("应识别到 ≥ 23 个 draw* 函数", drawFnDecls(strippedSrc()).size >= 23)
    }

    // ══════════════════════════════ ⑩ 零向自证 ══════════════════════════════

    @Test
    fun `负向① clip 禁物 - 三种破实现必须被判违规`() {
        assertTrue("clipPath( 必须被判违规", clipViolations("clipPath(p) { }").isNotEmpty())
        assertTrue("clip(RoundRect 必须被判违规",
            clipViolations("clip(RoundRect(0f, 0f, 1f, 1f))").isNotEmpty())
        assertTrue("RoundedCornerShape 必须被判违规", clipViolations("val s = RoundedCornerShape(4f)").isNotEmpty())
        assertTrue("合法 clipRect 不在禁令内（§4.9.3 明确允许矩形 clip）",
            clipViolations("clipRect(top = 1f)").isEmpty())
    }

    @Test
    fun `负向② 漏掉 DrawScope 接收者必须被判违规`() {
        assertTrue("private fun drawFoamLace(...) 必须被判违规",
            drawFnsWithoutDrawScope("private fun drawFoamLace(layer: Int, kA: Float) { }").isNotEmpty())
        assertTrue("private fun drawRipples(t: Double, clip: Path) 必须被判违规（无接收者）",
            drawFnsWithoutDrawScope("private fun drawRipples(t: Double, clip: Path) { }").isNotEmpty())
        assertTrue("合法形态必须放行",
            drawFnsWithoutDrawScope("private fun DrawScope.drawFoamLace(x: Int) { }").isEmpty())
    }

    @Test
    fun `负向③ 湿沙高光的五种破实现必须被判违规`() {
        // 破 A：逐列 createLinearGradient
        // ⚠️ 注入串必须**真的含 `createLinearGradient`**：`android.graphics.LinearGradient` ⛔ **不含**
        //   这个子串 ⇒ 早先那版夹具是靠「基底 drawPath 数为 0」假阳性地过的，判别力是空的。
        assertTrue("逐列 createLinearGradient 必须被判违规",
            wetWashViolations(wetSheenFixture("val g = createLinearGradient(0f, 0f, 0f, 1f)")).isNotEmpty())
        // 破 B：逐帧 Brush.verticalGradient
        assertTrue("逐帧 Brush.verticalGradient 必须被判违规",
            wetWashViolations(wetSheenFixture("wetBrush = Brush.verticalGradient(0f to c)")).isNotEmpty())
        // 破 C：退回「合成 1 次提交」（高光只写子轮廓、不自己提交）—— ⛔ 这正是被推翻的旧形态
        val onePass = """
            private fun DrawScope.drawWetWash() { drawPath(p, b) }
            private fun DrawScope.drawSheen() { sheenPath.reset() }
        """.trimIndent()
        assertTrue("合成 1 次 drawPath 必须被判违规（旧形态，高光会并入湿区而不可见）",
            wetWashViolations(onePass).isNotEmpty())
        // 破 D：湿沙体内出现第二个 drawPath（第 3 次提交）
        val threePass = """
            private fun DrawScope.drawWetWash() { drawPath(p, b); drawPath(q, b) }
            private fun DrawScope.drawSheen() { drawPath(r, b) }
        """.trimIndent()
        assertTrue("任一体内第二个 drawPath 必须被判违规", wetWashViolations(threePass).isNotEmpty())
        // 破 E：退回去逐列 drawRect(brush =
        assertTrue("逐列 drawRect(brush = 必须被判违规",
            wetWashViolations(wetSheenFixture("drawRect(brush = b, left = x)")).isNotEmpty())
        // 正向放行：各自恰好 1 次、合计 2 次，且无逐帧渐变构造
        // ⚠️ 传空串 ⇒ 夹具就是纯合规基底（⛔ 不是「基底本身违规」的假阳性）
        assertTrue("合规形态必须放行", wetWashViolations(wetSheenFixture("")).isEmpty())
    }

    @Test
    fun `负向④ 每帧零分配的三种破实现必须被判违规`() {
        assertTrue("带参 Rect( 必须被判违规", hasAllocRect("val r = Rect(0f, 0f, w, h)"))
        assertFalse("无参 Rect() 是缓存缓冲构造，放行", hasAllocRect("private val inkRect = Rect()"))
        assertTrue(".sortedBy { 必须被判违规", hasContainerAlloc("waves.sortedBy { it.y }"))
        assertTrue(".filter { 必须被判违规", hasContainerAlloc("cols.filter { it > 0 }"))
        assertTrue(".toList() 必须被判违规", hasContainerAlloc("vals.toList()"))
        assertTrue("listOf( 必须被判违规", hasContainerAlloc("val a = listOf(1, 2)"))
        assertTrue("字符串模板必须被判违规", hasStringTemplate("val s = \"x=\$y\""))
        assertFalse("普通字符串放行", hasStringTemplate("val s = \"plain\""))
    }

    @Test
    fun `负向⑤ 每帧 Brush 构造必须被判违规`() {
        assertTrue("逐帧 Brush.verticalGradient 必须被判违规",
            perFrameBrushViolations("private fun DrawScope.drawSand() { b = Brush.verticalGradient(0f to c) }")
                .isNotEmpty())
        assertTrue("逐帧 ShaderBrush( 必须被判违规",
            perFrameBrushViolations("private fun DrawScope.drawSand() { b = ShaderBrush(f) }").isNotEmpty())
        assertTrue("烘焙期构造必须放行",
            perFrameBrushViolations("private fun buildWetBrush(w: Float, h: Float) { b = Brush.verticalGradient(0f to c) }")
                .isEmpty())
    }

    @Test
    fun `负向⑧ 类头多行必须被两道门静默剔除`() {
        // ⛔ §4.9.4 陷阱 ②：`{` 出现在 `: RendererFx(` **之前**（lambda 默认参数）
        //    ⇒ `window.substring(0, brace)` 不含 `: RendererFx(` ⇒ 该类被**不报错地**剔除。
        val broken = "class SeasideRenderer(private val seed: (Int) -> Int = { it }) : RendererFx() {\n}\n"
        assertFalse("横向空白正则（FxCoverageScanTest）必须判否", seenByFxGate(broken, horizontal = true))
        assertFalse("含换行的正则（RendererBaseContractTest）必须判否", seenByFxGate(broken, horizontal = false))
        // 正向：合规单行类头两道门都认
        val good = "class SeasideRenderer : RendererFx() {\n}\n"
        assertTrue("合规类头必须被认到", seenByFxGate(good, horizontal = true))
        assertTrue("合规类头必须被认到", seenByFxGate(good, horizontal = false))
    }

    @Test
    fun `负向⑨ 元素清单漏项必须被判违规`() {
        // 破 A：某绘制函数没标 SeaOpItem
        val noTag = """
            class X : RendererFx() {
                /** 就这么一句 */
                private fun DrawScope.drawFoo() { drawPath(p, b) }
            }
        """.trimIndent()
        assertTrue("未标注的绘制函数必须被判违规",
            unannotatedDrawFns(noTag, stripComments(noTag)).isNotEmpty())

        // 破 B：标注了不存在的 SeaOpItem 名字（拼错 ⇒ 门禁守住的是另一个效果）
        val typo = """
            class X : RendererFx() {
                /**
                 * 元素 SeaOpItem.SAND_BLITZ
                 */
                private fun DrawScope.drawSand() { drawPath(p, b) }
            }
        """.trimIndent()
        assertTrue("SeaOpItem 名字拼错必须被判违规",
            badItemNames(typo, stripComments(typo)).isNotEmpty())
        assertTrue("合规标注必须放行",
            badItemNames(completeFixture(null), stripComments(completeFixture(null))).isEmpty())

        // 破 C：预算表有一项既没实现、也没写成「**无**」的清单行
        val missing = completeFixture("DISTURBANCE")
        val v = unaccountedItems(missing, stripComments(missing))
        assertTrue("未被认领又没写明「无」的项必须被判违规（实测 $v）", v.isNotEmpty())
        assertTrue("违规项必须点名 DISTURBANCE", v.any { it.contains("DISTURBANCE") })

        // 破 D：补上「**无**」的清单行（显式声明「本档不画」）⇒ 同一判据必须放行
        val declared = missing + "| [SeaOpItem.DISTURBANCE] | **无** | 已删除 |\n"
        assertTrue("写成「**无**」的清单行后必须放行",
            unaccountedItems(declared, stripComments(declared)).isEmpty())
    }

    @Test
    fun `负向⑩ Stroke 构造点跑到逐帧函数里必须被判违规`() {
        // 夹具：两个允许的烘焙构造点齐全（正向形态）
        val ok = """
            class X : RendererFx() {
                private fun buildLaceStrokes() { a = Stroke(width = 1f) }
                private fun buildCausticStrokes() { b = Stroke(width = 2f) }
            }
        """.trimIndent()
        assertEquals("两个烘焙期构造点必须放行", emptyList<String>(), strokeCtorViolations(ok))

        // 破 A：⛔ 在 drawContent（逐帧）里塞一个构造点 ⇒ 必须判负
        val inFrame = """
            class X : RendererFx() {
                private fun buildLaceStrokes() { a = Stroke(width = 1f) }
                private fun buildCausticStrokes() { b = Stroke(width = 2f) }
                private fun DrawScope.drawContent() { s = Stroke(width = 3f) }
            }
        """.trimIndent()
        assertTrue("drawContent 内的 Stroke( 必须被判违规", strokeCtorViolations(inFrame).isNotEmpty())

        // 破 B：⛔ 在任一 DrawScope.drawXxx 里塞一个 ⇒ 必须判负
        val inDraw = """
            class X : RendererFx() {
                private fun buildLaceStrokes() { a = Stroke(width = 1f) }
                private fun buildCausticStrokes() { b = Stroke(width = 2f) }
                private fun DrawScope.drawFoamLace() { s = Stroke(width = w) }
            }
        """.trimIndent()
        assertTrue("drawFoamLace 内的 Stroke( 必须被判违规", strokeCtorViolations(inDraw).isNotEmpty())

        // 破 C：⛔ 第三个烘焙函数（不在允许集合里）⇒ 同样判负（集合是**闭**的）
        val third = """
            class X : RendererFx() {
                private fun buildLaceStrokes() { a = Stroke(width = 1f) }
                private fun buildCausticStrokes() { b = Stroke(width = 2f) }
                private fun buildFoamStrokes() { c = Stroke(width = 3f) }
            }
        """.trimIndent()
        assertTrue("允许集合之外的第三个构造点必须被判违规", strokeCtorViolations(third).isNotEmpty())

        // 破 D：⛔ 允许集合里的函数被删掉 ⇒ 判据必须**空转判负**（而不是静默放行）
        val missing = """
            class X : RendererFx() {
                private fun buildLaceStrokes() { a = Stroke(width = 1f) }
            }
        """.trimIndent()
        assertTrue("取不到 buildCausticStrokes 的体时必须判负（防判据空转）",
            strokeCtorViolations(missing).isNotEmpty())
    }

    // ══════════════════════════════ ⑪ `BitmapShader` 单点例外 ══════════════════════════════

    @Test
    fun `⑪ BitmapShader 只能出现在 drawSand 这一个单点例外里`() {
        val v = bitmapShaderViolations(strippedBody())
        assertTrue(
            "D11（2026-10-04 所有者裁决）：`clipPath` 的禁令有真机三次复现的 hwui SIGSEGV ⇒ 不可动；" +
                "`BitmapShader` 的禁令只是项目约定、且 drawSand 的位图本来就在自己手里并已手动 " +
                "recycle() ⇒ 单点解禁。但**必须**锁死在 drawSand（+ 其烘焙期构造点 " +
                "buildSandTexture），不得扩散：\n" + v.joinToString("\n"),
            v.isEmpty()
        )
        // 正向自证：那两处**真的**各司其职（否则「零违规」是空转）
        // - `buildSandTexture`：烘焙期构造着色器并挂到 drawSand 那一支画笔上
        assertTrue("buildSandTexture 体内必须真的构造 BitmapShader",
            funBody(strippedBody(), "buildSandTexture").contains("BitmapShader("))
        assertTrue("buildSandTexture 必须把着色器挂到 sandPaint（= drawSand 用的那一支）",
            funBody(strippedBody(), "buildSandTexture").contains("sandPaint.shader"))
        // - `drawSand`：逐帧**消费**它（⛔ drawSand 体内不再出现 `BitmapShader` 这个词 ——
        //   它只经 `sandPaint.shader` 间接使用，这正是「单点」在源码上的形状）
        assertTrue("drawSand 体内必须真的经 sandPaint.shader 消费那张沙纹理",
            funBody(strippedBody(), "drawSand").contains("sandPaint.shader"))
    }

    @Test
    fun `负向⑪ BitmapShader 挪到 drawCausticNet 里必须被判违规`() {
        // 基底 = 真实的合规形态（两个获准点齐全，⛔ 不是「基底本身违规」的假阳性）
        val ok = """
            private fun DrawScope.drawSand() { a = sandPaint.shader }
            private fun buildSandTexture() { sh = BitmapShader(bmp, TileMode.CLAMP, TileMode.CLAMP) }
        """.trimIndent()
        assertTrue("合规基底必须零违规（否则下面的负向自证是假阳性）",
            bitmapShaderViolations(ok).isEmpty())
        // 破 A：⛔ 挪到别的逐帧函数里 ⇒ 必须判负
        val moved = """
            private fun DrawScope.drawSand() { a = sandPaint.shader }
            private fun buildSandTexture() { sh = BitmapShader(bmp, TileMode.CLAMP, TileMode.CLAMP) }
            private fun DrawScope.drawCausticNet() { sh = BitmapShader(bmp, TileMode.CLAMP, TileMode.CLAMP) }
        """.trimIndent()
        assertTrue("BitmapShader 挪到 drawCausticNet 里必须被判违规",
            bitmapShaderViolations(moved).isNotEmpty())
        // 破 B：⛔ 获准集合里的函数被删掉 ⇒ 判据必须**空转判负**（而不是静默放行）
        val missing = """
            private fun buildSandTexture() { sh = BitmapShader(bmp, TileMode.CLAMP, TileMode.CLAMP) }
        """.trimIndent()
        assertTrue("取不到 drawSand 的体时必须判负（防判据空转）",
            bitmapShaderViolations(missing).isNotEmpty())
    }

    // ══════════════════════════════ 判据（正向与负向共用同一份）═════════════════════════════

    /** §4.9.3 的三个禁物 token。⛔ 检查**剥完注释**的正文（KDoc 里可以引用它们做说明）。 */
    private val FORBIDDEN_CLIP = listOf("clipPath(", "clip(RoundRect", "RoundedCornerShape")

    /** `Stroke(` 的**唯一允许**构造点（两个烘焙期函数，见 [strokeCtorViolations]）。 */
    private val STROKE_CTOR_SITES = listOf("buildLaceStrokes", "buildCausticStrokes")

    /**
     * ⭐ `BitmapShader` 的**唯一允许**出现点（2026-10-04 所有者裁决的单点例外，D11）。
     *
     * ## 为什么是两个函数名而不是一个
     * 裁决的原话是「`BitmapShader` 只允许出现在 `drawSand` 函数体内」；实际上**同一个单点
     * 例外**在源码上落在两处，本文件必须同时允许，否则会把「已裁决解禁」判成违规：
     * - [drawSand] —— 逐帧**使用**它（`sandPaint` 的填充刷 = 那张沙纹理）；
     * - [buildSandTexture] —— 烘焙期**构造**它（`BitmapShader(bmp, CLAMP, CLAMP)` +
     *   那次把位图缩放铺满的矩阵设置），并挂到 [drawSand] 那一支画笔上。
     *
     * ⛔ 其余任何位置出现 `BitmapShader` ⇒ 判负。⛔ 允许集合里任一函数被删掉 ⇒ **判据空转
     *   判负**（否则「一个都没匹配上」会被当成「零违规」静默通过，与 [strokeCtorViolations]
     *   的破 D 同款失效模式）。
     */
    private val BITMAP_SHADER_SITES = listOf("drawSand", "buildSandTexture")

    private fun clipViolations(stripped: String): List<String> {
        val out = mutableListOf<String>()
        for (t in FORBIDDEN_CLIP) {
            val n = countOccurrences(stripped, t)
            if (n > 0) out += "$t × $n（§4.9.3：禁令范围是整个 clipPath，不分圆角与否）"
        }
        return out
    }

    /**
     * §4.9.5 配套：湿沙**与**高光**各自**恰好 1 次 `drawPath(`（合计 2 次），
     * 且两者体内都不得逐帧造渐变 / 逐列 `drawRect(brush =`。
     *
     * ⛔ **判据形态变更（所有者裁决，推翻 §4.9.2 的「合成 1 次提交」）**：
     * 高光子轮廓嵌套在湿区内部，NonZero 填充规则下**并入**湿区 ⇒ 像素集与不加它时逐像素相同
     * ⇒ 折成一条 path 在结构上就**看不到**镜面高光；原型靠 `globalCompositeOperation = 'lighter'`
     * 的**第二次**提交才有亮度差 ⇒ 现在要求「各自 1 次」。
     *
     * ⛔ 保留**三道**负向自证：
     * ① 任一体内出现**第二个** `drawPath(`（= 第 3 次提交）⇒ 判负；
     * ② 三个渐变构造（`createLinearGradient` / `Brush.verticalGradient` / `drawRect(brush =`）
     *    出现在任一体内 ⇒ 判负；
     * ③ 两个函数体取不到（判据空转）⇒ 判负。
     */
    private fun wetWashViolations(strippedBody: String): List<String> {
        val wet = funBody(strippedBody, "drawWetWash")
        val sheen = funBody(strippedBody, "drawSheen")
        if (wet.isEmpty() || sheen.isEmpty()) return listOf("取不到 drawWetWash / drawSheen 的体（判据空转）")
        val both = wet + "\n" + sheen
        val out = mutableListOf<String>()
        if (both.contains("createLinearGradient")) out += "湿沙/高光体内出现 createLinearGradient（逐列渐变 = 97 次提交）"
        if (both.contains("Brush.verticalGradient")) out += "湿沙/高光体内出现 Brush.verticalGradient（须在 rebuildGeometry 烘一次）"
        if (both.contains("drawRect(brush =")) out += "湿沙/高光体内出现逐列 drawRect(brush ="
        // ① 各自恰好 1 次（⛔ 不是合计 1 次 —— 见本函数 KDoc）
        val wetPaths = countOccurrences(wet, "drawPath(")
        if (wetPaths != 1) out += "drawWetWash 体 drawPath( 次数必须恰好 1（现 $wetPaths）"
        val sheenPaths = countOccurrences(sheen, "drawPath(")
        if (sheenPaths != 1) out += "drawSheen 体 drawPath( 次数必须恰好 1（现 $sheenPaths）"
        val paths = wetPaths + sheenPaths
        if (paths != 2) out += "湿沙 + 高光合计 drawPath( 次数必须恰好 2（现 $paths）"
        return out
    }

    /**
     * §4.9.2 / §4.9.4：`Stroke(` 的**构造点集合**必须 ⊆ [STROKE_CTOR_SITES]（两个烘焙期函数）。
     *
     * ⛔ 这条判据的**意图**是「逐帧不得构造 `Stroke`」（`Stroke.width` 不可变 ⇒ 逐边 `new`
     *   就是每帧几百次 native 分配，API 22 Dalvik 无 JIT，§4.9.1），
     *   ⛔ **不是**「全文件永远只能有一个构造点」：蕾丝（4 类 × 3 档 = 12）与焦散（3 档线宽）
     *   的线宽都只能在这里烘，各需一个构造点。
     *
     * 判法：全文件 `Stroke(` 出现数 **必须等于**两个允许函数体内的出现数之和
     * —— 只要有第三个构造点（尤其实帧函数里），差值就非零 ⇒ 判负。
     * 另有一道**空转自证**：取不到允许集合里任一函数体时直接判负。
     */
    private fun strokeCtorViolations(strippedBody: String): List<String> {
        val out = mutableListOf<String>()
        var inside = 0
        for (name in STROKE_CTOR_SITES) {
            val f = funBody(strippedBody, name)
            if (f.isEmpty()) {
                out += "取不到允许的构造点函数 $name 的体（判据空转 ⇒ 无法证明逐帧零构造）"
                continue
            }
            inside += countOccurrences(f, "Stroke(")
        }
        val total = countOccurrences(strippedBody, "Stroke(")
        if (total != inside) {
            out += "全文件 `Stroke(` 共 $total 处，而允许集合 " +
                "${STROKE_CTOR_SITES.joinToString(" / ")} 内只有 $inside 处 ⇒ " +
                "存在集合之外的构造点（多半在逐帧函数里）"
        }
        return out
    }

    /**
     * ⭐ D11 单点例外：`BitmapShader` 的出现位置必须**全部落在** [BITMAP_SHADER_SITES] 里。
     *
     * 判法与 [strokeCtorViolations] 同构：先数允许集合内的出现数，再与全类体的总数比 ——
     * 只要有第三个出现点（尤其是某个逐帧函数里），差值就非零 ⇒ 判负。
     * 另有一道**空转自证**：取不到允许集合里任一函数体时直接判负。
     */
    private fun bitmapShaderViolations(strippedBody: String): List<String> {
        val out = mutableListOf<String>()
        var inside = 0
        for (name in BITMAP_SHADER_SITES) {
            val f = funBody(strippedBody, name)
            if (f.isEmpty()) {
                out += "取不到获准的 `BitmapShader` 使用点 $name 的体（判据空转）"
                continue
            }
            inside += countOccurrences(f, "BitmapShader")
        }
        val total = countOccurrences(strippedBody, "BitmapShader")
        if (total != inside) {
            out += "全类体 `BitmapShader` 共 $total 处，而获准集合 " +
                "${BITMAP_SHADER_SITES.joinToString(" / ")} 内只有 $inside 处 ⇒ " +
                "存在集合之外的使用点（D11 单点例外被扩大）"
        }
        return out
    }

    /** §4.9.4 陷阱 ① 的补洞：逐帧函数体内零 `Brush.` / `ShaderBrush(` 构造。 */
    private fun perFrameBrushViolations(strippedBody: String): List<String> {
        val out = mutableListOf<String>()
        for (d in drawFnDecls(strippedBody)) {
            val body = funBody(strippedBody, d.name)
            if (body.isEmpty()) continue
            if (body.contains("Brush.")) out += "${d.name} 内出现 `Brush.` 构造"
            if (body.contains("ShaderBrush(")) out += "${d.name} 内出现 ShaderBrush( 构造"
        }
        return out
    }

    /**
     * 每个 `fun drawXxx(` 都必须写成 `fun DrawScope.drawXxx(`。
     * 漏一个接收者，那个函数就**整体逃过** `PerfBudgetContractTest` 的每帧扫描（§4.9.4 陷阱 ①）。
     */
    private fun drawFnsWithoutDrawScope(stripped: String): List<String> {
        val out = mutableListOf<String>()
        for (m in DRAW_FN_RE.findAll(stripped)) {
            if (m.groupValues[1] != "DrawScope.") out += m.value.trim()
        }
        return out
    }

    /** 一个绘制函数的声明位置（供 KDoc 回溯与 `clip` 参数检查用）。 */
    private fun declOf(stripped: String, name: String): String {
        val d = drawFnDecls(stripped).firstOrNull { it.name == name } ?: return ""
        val brace = stripped.indexOf('{', d.range.first)
        return if (brace < 0) d.line.trim() else stripped.substring(d.range.first, brace)
    }

    private fun hasStringTemplate(s: String): Boolean = Regex(""""[^"\n]*\$[^"\n]*"""").containsMatchIn(s)

    private fun hasAllocRect(s: String): Boolean = Regex("""(?<![A-Za-z0-9_])Rect\(\s*[^)\s]""").containsMatchIn(s)

    private fun hasContainerAlloc(s: String): Boolean =
        Regex("""\b(listOf|mutableListOf|arrayListOf|mapOf|setOf)\s*\(""").containsMatchIn(s) ||
            Regex("""\.(map|sortedBy|sortedByDescending|filter)\s*[({]""").containsMatchIn(s) ||
            Regex("""\.(toList|toTypedArray|mapValues)\s*\(""").containsMatchIn(s)

    /**
     * 逐行判据，**逐行**放行 `// Perf-exempt:` 标记行 —— 与项目权威门禁
     * `PerfBudgetContractTest.isExemptLine` 的语义保持一致。
     *
     * ⚠️ [raw] 是**未剥注释**的同一函数体：豁免标记写在行尾注释里，而判据读的是
     * 剥完注释的 [stripped]（`Rect(` 之类必须看剥后的文本）。两者行号一一对应
     * （[stripComments] 保证换行不丢）。
     */
    private fun offendingLines(
        stripped: String,
        raw: String,
        pred: (String) -> Boolean,
    ): List<String> {
        val exempt = raw.split('\n').map { it.contains("Perf-exempt") }
        val out = mutableListOf<String>()
        stripped.split('\n').forEachIndexed { i, line ->
            if (exempt.getOrNull(i) == true) return@forEachIndexed
            if (pred(line)) out += "第 ${i + 1} 行：${line.trim()}"
        }
        return out
    }

    /**
     * ⭐ 元素清单完整性（§4.9.5 + oracle 风险「漏元素」）—— 拆成三段，好让每段都能单独负向自证。
     *
     * ① [unannotatedDrawFns]：每个 `draw*` 函数的 KDoc 必须提到 `SeaOpItem`
     *    （否则「这个函数画的是哪个元素」不可核对）。
     * ② [badItemNames]：KDoc 里提到的每个 `SeaOpItem.X` 名字**必须真实存在**
     *    （拼错 ⇒ 门禁守住的是另一个效果）。
     * ③ [unaccountedItems]：[SeaOpItem] 的每一项必须**要么**被某个函数认领，
     *    **要么**在原文里以「`**无**`」的清单行显式声明「本档不画 / 已删除 / 声明式」——
     *    ⛔ 不允许「既没实现也没说明」（那正是静默漏元素）。
     */
    private fun inventoryViolations(raw: String, stripped: String): List<String> =
        unannotatedDrawFns(raw, stripped) + badItemNames(raw, stripped) + unaccountedItems(raw, stripped)

    private fun unannotatedDrawFns(raw: String, stripped: String): List<String> {
        val out = mutableListOf<String>()
        val rawLines = raw.split('\n')
        for (d in drawFnDecls(stripped)) {
            val kdoc = kdocAbove(rawLines, d.lineIndex)
            if (!kdoc.contains("SeaOpItem")) {
                out += "${d.name} 的 KDoc 未标注它对应哪个 SeaOpItem（漏元素风险）"
            }
        }
        return out
    }

    private fun badItemNames(raw: String, stripped: String): List<String> {
        val out = mutableListOf<String>()
        val rawLines = raw.split('\n')
        for (d in drawFnDecls(stripped)) {
            val kdoc = kdocAbove(rawLines, d.lineIndex)
            for (m in Regex("""SeaOpItem\.([A-Z][A-Z_0-9]*)""").findAll(kdoc)) {
                val name = m.groupValues[1]
                if (SeaOpItem.entries.none { it.name == name }) {
                    out += "${d.name} 的 KDoc 标注了不存在的 SeaOpItem.$name（拼错 ⇒ 守的是另一个效果）"
                }
            }
        }
        return out
    }

    private fun unaccountedItems(raw: String, stripped: String): List<String> {
        val rawLines = raw.split('\n')
        val claimed = HashSet<String>()
        for (d in drawFnDecls(stripped)) {
            val kdoc = kdocAbove(rawLines, d.lineIndex)
            for (m in Regex("""SeaOpItem\.([A-Z][A-Z_0-9]*)""").findAll(kdoc)) claimed.add(m.groupValues[1])
        }
        val out = mutableListOf<String>()
        for (item in SeaOpItem.entries) {
            if (claimed.contains(item.name)) continue
            val declaredGone = rawLines.any { it.contains("SeaOpItem.${item.name}") && it.contains("**无**") }
            if (!declaredGone) {
                out += "SeaOpItem.${item.name} 既没有被任何绘制函数认领，也没有以「**无**」的清单行" +
                    "显式声明不实现（§4.9.5：这是最容易被静默漏掉的形态）"
            }
        }
        return out
    }

    /** 回溯 [d] 的声明行上方最近的 `/** … */` 块（读**原文**，KDoc 才在）。 */
    private fun kdocAbove(rawLines: List<String>, declLine: Int): String {
        var i = declLine - 1
        while (i >= 0 && rawLines[i].trim().isEmpty()) i--
        if (i < 0 || !rawLines[i].trim().endsWith("*/")) return ""
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, rawLines[i]).insert(0, '\n')
            if (rawLines[i].contains("/**")) return sb.toString()
            i--
        }
        return ""
    }

    // ══════════════════════════════ 类头门（复刻真实门禁的判定）═════════════════════════════

    /**
     * 逐字复刻 [com.nasmusic.tv.visualizer.fx.FxCoverageScanTest] 与
     * `RendererBaseContractTest` 的类头扫描：类声明行起 400 字符里 `indexOf('{')`，
     * 再看它**之前**的窗口是否含 `: RendererFx(`。
     *
     * @param horizontal `true` = 只吃水平空白（FxCoverageScanTest 的 `v1.38` 修）；
     *                    `false` = `\s*` 含换行（RendererBaseContractTest 的原写法）。
     */
    private fun seenByFxGate(stripped: String, horizontal: Boolean): Boolean {
        val re = if (horizontal) {
            Regex("""(?m)^[ \t]*(?:(?:internal|open|abstract)[ \t]+)*class[ \t]+SeasideRenderer\b""")
        } else {
            Regex("""(?m)^\s*(?:(?:internal|open|abstract|private|sealed|final)\s+)*class\s+SeasideRenderer\b""")
        }
        val m = re.find(stripped) ?: return false
        val lineStart = stripped.lastIndexOf('\n', m.range.first).let { if (it < 0) 0 else it + 1 }
        val window = stripped.substring(lineStart, minOf(lineStart + 400, stripped.length))
        val brace = window.indexOf('{')
        if (brace < 0) return false
        return window.substring(0, brace).contains(": RendererFx(")
    }

    // ══════════════════════════════ 源码定位 ══════════════════════════════

    private fun readSrc(): String = readMain("com/nasmusic/tv/visualizer/renderers/SeasideRenderer.kt")

    private fun readMain(rel: String): String = File(mainSourceRoot(), rel).readText()

    /** 整份源文件（**已剥注释**）。 */
    private fun strippedSrc(): String = stripComments(readSrc())

    /** 整份源文件（**未剥注释**）—— 注释类判据（元素清单）读它。 */
    private fun rawSrc(): String = readSrc()

    /** 类体（已剥注释）。 */
    private fun strippedBody(): String = classBody(strippedSrc(), "SeasideRenderer")

    /** 类体（未剥注释）。 */
    private fun rawBody(): String = classBody(rawSrc(), "SeasideRenderer")

    /** 整份源文件（已剥注释）—— 禁物扫描面比类体更严（含 companion 与顶层 import）。 */
    private fun strippedFile(): String = strippedSrc()

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        repeat(6) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 app/src/main/java")
    }

    // ══════════════════════════════ 扫描器原语 ══════════════════════════════

    /** `fun [DrawScope.]drawXxx(` —— 捕获组 1 = 接收者（空 ⇒ 违规）。 */
    private val DRAW_FN_RE = Regex("""fun[ \t\r\n]+(DrawScope\.)?(draw[A-Za-z0-9_]*)[ \t\r\n]*\(""")

    private val CLASS_DECL_RE = Regex("""^[ \t]*class[ \t]+SeasideRenderer\b""")

    private val POST_FX_RE = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""")

    private val BITMAP_FIELD_RE = Regex(
        """private[ \t]+(?:var|val)[ \t]+([A-Za-z0-9_]+)[ \t]*:[ \t]*(?:Array<)?ImageBitmap\?>?"""
    )

    /** 一个绘制函数的声明（名字 + 在剥注释文本里的位置 + 行号）。 */
    private class DrawFn(val name: String, val range: IntRange, val lineIndex: Int, val line: String)

    private fun drawFnDecls(stripped: String): List<DrawFn> {
        val lines = stripped.split('\n')
        val lineStarts = ArrayList<Int>(lines.size)
        var acc = 0
        for (l in lines) {
            lineStarts.add(acc)
            acc += l.length + 1
        }
        val out = mutableListOf<DrawFn>()
        for (m in DRAW_FN_RE.findAll(stripped)) {
            val li = lineIndexOf(lineStarts, m.range.first)
            out.add(DrawFn(m.groupValues[2], m.range, li, lines[li]))
        }
        return out
    }

    private fun lineIndexOf(lineStarts: List<Int>, offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun bitmapFields(stripped: String): List<String> {
        val out = mutableListOf<String>()
        for (m in BITMAP_FIELD_RE.findAll(stripped)) out.add(m.groupValues[1])
        return out
    }

    private fun countOccurrences(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var n = 0
        var i = hay.indexOf(needle)
        while (i >= 0) {
            n++
            i = hay.indexOf(needle, i + needle.length)
        }
        return n
    }

    /** 花括号配对切出 `class <name>` 的类体（含其内部 companion）。 */
    private fun classBody(txt: String, name: String): String {
        val m = Regex("""(?m)^[ \t]*(?:(?:internal|open|abstract|private|sealed|final)[ \t]+)*class[ \t]+$name\b""")
            .find(txt) ?: return ""
        val brace = txt.indexOf('{', m.range.last)
        if (brace < 0) return ""
        var depth = 0
        var i = brace
        while (i < txt.length) {
            when (txt[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return txt.substring(brace, i + 1)
                }
            }
            i++
        }
        return txt.substring(brace)
    }

    /** 从**已剥注释**的类体里取某个函数的 `{...}` 体（签名认 receiver `DrawScope.`）。 */
    private fun funBody(classBody: String, name: String): String {
        val m = Regex("""fun[ \t\r\n]+(?:[A-Za-z0-9_]+\.)?$name[ \t\r\n]*\(""").find(classBody) ?: return ""
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
     * 去注释（行注释 + **嵌套**块注释 + 字符串感知），块注释内换行保留以保持行号。
     *
     * ⚠️ 本 KDoc 正文刻意**不**写块注释定界符的字面量，否则会提前闭合本注释。
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
                    if (src[i] == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                    i++
                    if (sb[i - 1] == '"') break
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
                    if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') { depth++; i += 2 }
                    else if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') { depth--; i += 2 }
                    else { if (src[i] == '\n') sb.append('\n'); i++ }
                }
                continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    // ══════════════════════════════ 负向夹具 ══════════════════════════════

    /**
     * 一份「只有 drawWetWash / drawSheen + 一行待测代码」的最小夹具。
     *
     * ⚠️ **基底本身必须合规**（各恰好 1 个 `drawPath(`、无逐帧渐变构造）——
     * 否则每条破实现都会**因为基底违规**而判负，断言就成了空转（⛔ 负向自证失去判别力）。
     * 待测代码注入进 [drawWetWash] 体（造渐变的那三条禁令都作用在两个体上，任一处即可）。
     */
    private fun wetSheenFixture(drawLine: String): String = """
        private fun DrawScope.drawWetWash() {
            drawPath(wetRegionPath, wetBrush)
            $drawLine
        }
        private fun DrawScope.drawSheen() {
            sheenPath.reset()
            drawPath(sheenPath, sheenBrush)
        }
    """.trimIndent()

    /**
     * 生成一份「[SeaOpItem] 每一项都被某个 `draw*` 的 KDoc 认领」的夹具 ——
     * 用来把 [unaccountedItems] 的判别力单独钉死（否则一个恒空的谓词也能过正向）。
     *
     * @param exclude 要**故意漏掉**的那一项（`null` = 全认领）。
     */
    private fun completeFixture(exclude: String?): String {
        val sb = StringBuilder("class X : RendererFx() {\n")
        for (item in SeaOpItem.entries) {
            if (item.name == exclude) continue
            sb.append("    /** 元素 SeaOpItem.").append(item.name).append(" */\n")
            sb.append("    private fun DrawScope.draw").append(item.name.lowercase()).append("() { }\n")
        }
        sb.append("}\n")
        return sb.toString()
    }
}

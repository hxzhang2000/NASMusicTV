package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E34 分形树（§B9 · T4.9）门禁。
 *
 * 四段：
 * - **迁移段**：`: RendererFx()` + 内容钩子 + 不覆写 `final` + ⛔ 旧时钟（`ctx.nowMs` / `lastMs` 哨兵）已清；
 * - **观感段（§B9-①~③）**：几何锥度 + 逐层受光色 / 旋转椭圆叶（3 桶合批）/ 背景纵深（带盐渐变 + 星野）；
 * - **dt 段**：`fx.dt` 取代旧哨兵，且**行为**上 30 / 60 / 120 fps 恒等；
 * - **性能段**：全部 `draw*` 函数体内零堆分配。
 *
 * ⛔ 负向自证 2 条（N1 / N2）—— 缺一条就可能空转。
 * ⛔ 源码判据一律**先剥注释**（本仓库已踩 5 次：判据命中自己刚写的 KDoc ⇒ 假 FAIL）。
 * ⛔ 负向样本必须与正向**喂同一份谓词**。
 * ⛔ 行为段**直调生产纯函数**（`advanceDepth` / `growAt` / `leafBucketOf` / `leafRadiusOf` /
 *   `depthColorArgbOf`），**不复制算法** —— 复制必然漂移。
 */
class FractalTreeTest {

    private val C = FractalTreeRenderer.Companion

    /** 逐帧绘制函数（性能段扫描面：这些函数体里不得出现堆分配） */
    private val drawFns = listOf("drawContent", "buildLeaf", "drawArcJitter", "pow")

    // ═══════════════════════════ ① 迁移形态 ═══════════════════════════

    @Test
    fun `① 已迁移 RendererFx - 不覆写 final、内容钩子齐备、旧时钟已清`() {
        val body = classBody(codeOfE34(), "FractalTreeRenderer")
        assertTrue("类体必须能切出来（空转自证）", body.isNotEmpty())
        assertTrue("必须 `: RendererFx()`", ": RendererFx()" in body)
        assertTrue(
            "必须实现 drawContent",
            Regex("""fun\s+(?:[A-Za-z0-9_.]+\.)?drawContent\s*\(""").containsMatchIn(body)
        )
        assertTrue("必须实现 onEnterContent", "fun onEnterContent(" in body)
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
        assertFalse("⛔ 不得自建 rng（基类已提供 protected rng）", "private val rng = VisualizerRandom()" in body)
        assertFalse("⛔ 不得使用 ctx.nowMs（一律走 fx）", Regex("""ctx\.nowMs""").containsMatchIn(body))
        assertFalse("⛔ 不得再用 lastMs 哨兵（首帧 timeMs 可能恰为 0）", Regex("""\blastMs\b""").containsMatchIn(body))
        assertFalse("旧继承形态必须消失", "VisualizerRenderer" in body)
    }

    // ═══════════════════════════ ② postFx ═══════════════════════════

    @Test
    fun `② postFx 数值字面量 - 正负双证（门禁判据同源）`() {
        val body = classBody(codeOfE34(), "FractalTreeRenderer")
        assertTrue("postFx 必须是数值字面量（覆盖门禁判据）", coveredByPostFx(body))
        assertTrue("§B9-④ 必须 vignette = 0.48f", "vignette = 0.48f" in body)
        assertTrue("§B9-④ 必须 grain = 0.030f", "grain = 0.030f" in body)
        assertFalse("PostFx.NONE 不得被判为已覆盖", coveredByPostFx("override val postFx = PostFx.NONE"))
        assertFalse(
            "具名常量不得被判为已覆盖（门禁只认字面量）",
            coveredByPostFx("override val postFx = PostFx(vignette = VIG, grain = GRAIN)")
        )
    }

    // ═══════════════════════════ ③ §B9-① 锥度 + 受光 ═══════════════════════════

    @Test
    fun `③ §B9-① 几何锥度 + 逐层受光色（直调生产常量与纯函数）`() {
        val body = classBody(codeOfE34(), "FractalTreeRenderer")
        assertEquals("主干线宽", 2.6f, FractalTreeRenderer.TRUNK_STROKE_W, 1e-6f)
        assertEquals("每层衰减系数必须与长度衰减同值", 0.72f, FractalTreeRenderer.TAPER, 1e-6f)
        assertEquals("线宽下限", 0.7f, FractalTreeRenderer.MIN_STROKE_W, 1e-6f)
        assertEquals("顶梢向白插值比例", 0.55f, FractalTreeRenderer.TIP_LIGHT_MIX, 1e-6f)
        assertEquals("每层长度衰减必须与 TAPER 同值", FractalTreeRenderer.TAPER, FractalTreeRenderer.LEN_K, 1e-6f)
        assertTrue("线宽必须走几何递减 `TRUNK_STROKE_W * TAPER.pow(d)` + 下限", geometricTaper(body))
        assertTrue("必须用逐层色表（draw 期零 JNI）", body.contains("Color(depthColorArgb[d])"))
        assertFalse("⛔ 旧的线性锥度必须消失", oldLinearTaper(body))
        assertFalse("⛔ 旧的 alpha 曲线必须消失（否则「顶梢更亮」被抵消）", oldAlphaCurve(body))

        // ── 行为：逐层受光色（端点精确 / 单调 / 反序必须不同）──
        val base = 0xFF204060.toInt()
        val tip = 0xFFFFE0A0.toInt()
        assertEquals("第 0 层必须 == base", base, C.depthColorArgbOf(base, tip, 0, 8))
        assertEquals("第 cap 层必须 == tip", tip, C.depthColorArgbOf(base, tip, 8, 8))
        val lum = (0..8).map { d ->
            val c = C.depthColorArgbOf(base, tip, d, 8)
            ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
        }
        assertTrue("逐层亮度必须单调不减（越往梢越亮）：$lum", lum.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("必须真的在插值（首尾亮度必须不同）", lum.last() > lum.first())
        assertNotEquals(
            "⛔ 反序必须给出不同颜色（证明 t 真的在起作用）",
            C.depthColorArgbOf(base, tip, 2, 8),
            C.depthColorArgbOf(tip, base, 2, 8)
        )
        assertEquals("depth 越界必须钳到 tip", tip, C.depthColorArgbOf(base, tip, 99, 8))
        assertEquals("depthCap <= 0 必须退化为 base（除零护栏）", base, C.depthColorArgbOf(base, tip, 3, 0))
        // 通道夹紧：极端色也不得溢出
        val clamp = C.depthColorArgbOf(0x00000000, 0xFFFFFFFF.toInt(), 4, 8)
        for (sh in intArrayOf(24, 16, 8, 0)) {
            val ch = (clamp shr sh) and 0xFF
            assertTrue("通道必须落在 0..255（实测 $ch）", ch in 0..255)
        }
    }

    // ═══════════════════════════ ④ §B9-② 叶形 ═══════════════════════════

    @Test
    fun `④ §B9-② 末级叶形 - 旋转椭圆 + 3 桶固定分桶（直调生产纯函数）`() {
        val body = classBody(codeOfE34(), "FractalTreeRenderer")
        assertEquals("长轴 / 短轴", 2.2f, FractalTreeRenderer.LEAF_ASPECT, 1e-6f)
        assertEquals("3 档大小", 3, FractalTreeRenderer.LEAF_BUCKETS)
        assertEquals("椭圆 4 段三次贝塞尔", 4, FractalTreeRenderer.LEAF_CUBIC_SEGS)
        assertEquals("叶半长轴基准", 2.4f, FractalTreeRenderer.LEAF_R_MIN, 1e-6f)
        assertTrue("必须手写椭圆（moveTo + cubicTo + close）", leafEllipse(body))
        assertFalse("⛔ 不得用 addOval（Compose 只有对象重载 ⇒ 每片叶一次 Rect 分配）", "addOval(" in body)
        assertTrue("长轴必须按全库唯一主光向定向", body.contains("Shading2D.lightDir"))
        assertTrue("叶必须在生长前沿的末级节点", body.contains("if (d >= depthInt)"))
        assertTrue("叶尺寸必须 ∝ grow（抽芽感）", body.contains("} * grow"))

        // ── 行为：固定分桶（3 桶都要被用到）──
        assertEquals("3 桶必须全部可达（否则有桶恒空 ⇒ 有 1 条 Path 永远是空的）",
            setOf(0, 1, 2), (0..8).map { C.leafBucketOf(it) }.toSet())
        assertEquals("下标 3 必须回到桶 0（固定分桶，不逐帧跳变）", 0, C.leafBucketOf(3))
        assertTrue("⛔ 不得恒返回同一个桶", (0..8).map { C.leafBucketOf(it) }.distinct().size > 1)
        assertEquals("负数下标必须归一（防御）", 2, C.leafBucketOf(-1))

        // ── 行为：叶长由频谱驱动 ──
        assertEquals("静音时叶长 == 基准（静音也有叶）",
            FractalTreeRenderer.LEAF_R_MIN, C.leafRadiusOf(0f), 1e-5f)
        val full = FractalTreeRenderer.LEAF_R_MIN * (1f + FractalTreeRenderer.LEAF_R_GAIN)
        assertEquals("满频谱时叶长 == 基准 × (1 + 增益)", full, C.leafRadiusOf(1f), 1e-5f)
        assertTrue("必须随频谱单调增", C.leafRadiusOf(0.5f) > C.leafRadiusOf(0.1f))
        assertTrue("⛔ 静音不得退化为 0（否则看不见叶）", C.leafRadiusOf(0f) > 0f)
        assertEquals("越界必须钳到 1", full, C.leafRadiusOf(9f), 1e-5f)
        assertEquals("负值必须钳到 0", FractalTreeRenderer.LEAF_R_MIN, C.leafRadiusOf(-3f), 1e-5f)
        assertEquals("3 桶必须绑定 3 个不同频段", 3, FractalTreeRenderer.LEAF_BANDS.distinct().size)
        assertEquals("3 桶必须各有 alpha", 3, FractalTreeRenderer.LEAF_ALPHAS.size)
    }

    // ═══════════════════════════ ⑤ §B9-③ 背景纵深 ═══════════════════════════

    @Test
    fun `⑤ §B9-③ 背景纵深 - 带盐径向渐变 + 星野 tile`() {
        val body = classBody(codeOfE34(), "FractalTreeRenderer")
        assertTrue("必须径向纵深（半对角线半径 + 整体 alpha）", bgDepth(body))
        assertTrue("必须星野 tile", body.contains("ProceduralTexture.Id.STARFIELD"))
        assertTrue("必须 `ensure` 在 drawContent 内", body.contains("ProceduralTexture.ensure(iw, ih)"))
        assertTrue("星野必须按 (iw, ih) 铺满（dstSize 是 IntSize）", body.contains("dstSize = IntSize(iw, ih)"))
        assertEquals("盐必须是 E34 专属值（Shading2D 的 16 槽 Brush 缓存是进程级共享）",
            0x34343434L, FractalTreeRenderer.E34_KEY_SALT)
        assertTrue("缓存键必须覆盖 (w, h)（§四 G4 缓存键维度 ⊇ 依赖维度）", keyHasDims(body))
        assertTrue("必须有具名盐", body.contains("E34_KEY_SALT"))
        assertEquals("径向纵深整体 alpha", 0.30f, FractalTreeRenderer.BG_DEPTH_ALPHA, 1e-6f)
        assertEquals("星野 alpha", 0.32f, FractalTreeRenderer.STAR_ALPHA, 1e-6f)
    }

    // ═══════════════════════════ ⑥ dt 化 + 生长 ═══════════════════════════

    @Test
    fun `⑥ dt 化与生长 - 只吃 fx_dt（行为：三档帧率恒等）`() {
        val body = classBody(codeOfE34(), "FractalTreeRenderer")
        assertTrue("必须调 advanceDepth(depthF, fx.dt, frame.bass, maxDepth)", dtMigrated(body))
        assertFalse("⛔ 不得自算 dtSec", Regex("""val\s+dtSec\s*=""").containsMatchIn(body))

        // ── 行为：30 / 60 / 120 fps 下 1 秒的深度累计量必须相同 ──
        for (fps in listOf(30, 60, 120)) {
            var d = 0f
            var n = 0
            while (n < fps) {
                d = C.advanceDepth(d, 1f / fps, 0.5f, 1000)
                n++
            }
            assertEquals("${fps}fps 下 1 秒累计量必须一致（bass=0.5 ⇒ 1.05）", 1.05f, d, 1e-3f)
        }
        assertEquals("dt = 0 必须原地不动", 0.3f, C.advanceDepth(0.3f, 0f, 1f, 1000), 1e-6f)
        assertEquals("必须钳到 maxDepth", 7f, C.advanceDepth(6.9f, 1f, 1f, 7), 1e-6f)
        assertEquals("底盘速率必须让静音也生长", 0.15f, C.advanceDepth(0f, 1f, 0f, 1000), 1e-5f)

        // ── 行为：深度门控 ──
        assertEquals("d < depthInt 必须满长", 1f, C.growAt(2, 3, 0.5f), 1e-6f)
        assertEquals("d == depthInt 必须满长", 1f, C.growAt(3, 3, 0.5f), 1e-6f)
        assertEquals("d == depthInt + 1 必须是分数层（生长感）", 0.4f, C.growAt(4, 3, 0.5f), 1e-6f)
        assertEquals("d > depthInt + 1 必须不可见", 0f, C.growAt(5, 3, 0.5f), 1e-6f)
        assertEquals("depthFrac = 0 时分数层必须不可见", 0f, C.growAt(4, 3, 0f), 1e-6f)
        assertEquals("分数层系数必须是 GROW_FRACTIONAL_K", 0.8f, FractalTreeRenderer.GROW_FRACTIONAL_K, 1e-6f)
    }

    // ═══════════════════════════ ⑦ 零分配 ═══════════════════════════

    @Test
    fun `⑦ 每帧路径零堆分配（全部 draw 函数体）`() {
        val body = classBody(codeOfE34(), "FractalTreeRenderer")
        for (fn in drawFns) {
            val fb = funBody(body, fn)
            assertTrue("必须能切出 $fn 的函数体（空转自证）", fb.isNotEmpty())
            for ((label, re) in ALLOC_RES) {
                assertFalse("⛔ $fn 内不得出现 $label", re.containsMatchIn(fb))
            }
        }
        // 反向自证：判据真的能抓到分配
        assertTrue("判据必须能抓到 Path()", ALLOC_RES.any { (_, re) -> re.containsMatchIn("private val p = Path()") })
        assertTrue("判据必须能抓到 IntArray(", ALLOC_RES.any { (_, re) -> re.containsMatchIn("val a = IntArray(8)") })
        assertTrue("判据必须能抓到 Rect(", ALLOC_RES.any { (_, re) -> re.containsMatchIn("val r = Rect(1f, 2f, 3f, 4f)") })
        // ⛔ value class 不算分配
        assertTrue(
            "Offset / Color 是 value class，不得被误判",
            ALLOC_RES.none { (_, re) ->
                re.containsMatchIn("drawLine(Color(c), Offset(a, b), Offset(d, e), strokeWidth = 1f)")
            }
        )
        assertTrue(
            "drawImage 的 IntSize 不得被误判",
            ALLOC_RES.none { (_, re) -> re.containsMatchIn("drawImage(b, dstSize = IntSize(iw, ih))") }
        )
        // ⛔ 词边界：drawRect( 不得被 `Rect(` 误伤
        assertTrue(
            "`Rect(` 判据必须带词边界（否则 drawRect( 会假阳性）",
            ALLOC_RES.none { (_, re) -> re.containsMatchIn("drawRect(brush = b, alpha = 0.3f)") }
        )
    }

    // ═══════════════════════════ ⑧ 叶合批 ═══════════════════════════

    @Test
    fun `⑧ 叶形合批 - 单一 drawPath 调用点 × 3 桶（不是逐叶 drawPath）`() {
        val body = classBody(codeOfE34(), "FractalTreeRenderer")
        val dc = funBody(body, "drawContent")
        assertTrue("必须能切出 drawContent（空转自证）", dc.isNotEmpty())
        val dp = Regex("""(?<![A-Za-z0-9_])drawPath\s*\(""").findAll(dc).count()
        assertEquals("drawContent 内 `drawPath(` 必须恰 1 个调用点（合批）", 1, dp)
        val loopAt = dc.indexOf("while (lb < LEAF_BUCKETS)")
        val dpAt = dc.indexOf("drawPath(")
        assertTrue("调用点必须在 `while (lb < LEAF_BUCKETS)` 循环体内", loopAt >= 0 && loopAt < dpAt)
        assertEquals("循环上界必须是 LEAF_BUCKETS", 3, FractalTreeRenderer.LEAF_BUCKETS)
        assertEquals("rewind 也必须是 1 个调用点（同一循环）",
            1, Regex("""\.rewind\s*\(\)""").findAll(dc).count())
        assertEquals("叶几何的构建也必须是 1 个调用点（合批进 Path）",
            1, Regex("""(?<![A-Za-z0-9_])buildLeaf\s*\(""").findAll(dc).count())
        assertTrue(
            "叶 Path 必须是成员（构造期建一次，draw 期只 rewind）",
            Regex("""private val leafPaths = arrayOf\s*\(\s*Path\(\)""").containsMatchIn(body)
        )
    }

    // ═══════════════════════════ 负向自证 ═══════════════════════════

    @Test
    fun `负向N1 旧时钟哨兵（lastMs == 0L）必须被抓且不误伤 fx_dt`() {
        assertTrue("旧 `lastMs == 0L` 哨兵必须被判否", usesLastMsSentinel(OLD_CLOCK_SNIPPET))
        assertFalse("新 `fx.dt` 写法不得被误伤", usesLastMsSentinel(NEW_CLOCK_SNIPPET))
        // 同一份谓词喂正向真实代码
        val body = classBody(codeOfE34(), "FractalTreeRenderer")
        assertFalse("真实类体必须不含旧哨兵", usesLastMsSentinel(body))
    }

    @Test
    fun `负向N2 旧线性锥度与旧 alpha 曲线必须被抓`() {
        assertTrue("旧线性锥度必须被判否", oldLinearTaper(OLD_TAPER_SNIPPET))
        assertFalse("新几何锥度不得被误伤", oldLinearTaper(NEW_TAPER_SNIPPET))
        assertTrue("旧 alpha 曲线必须被判否", oldAlphaCurve(OLD_ALPHA_SNIPPET))
        assertFalse("新 alpha 曲线不得被误伤", oldAlphaCurve(NEW_ALPHA_SNIPPET))
        val body = classBody(codeOfE34(), "FractalTreeRenderer")
        assertFalse("真实类体必须不含旧线性锥度", oldLinearTaper(body))
        assertFalse("真实类体必须不含旧 alpha 曲线", oldAlphaCurve(body))
    }

    // ═══════════════════════════ 判据谓词 ═══════════════════════════

    /** 门禁原版判据（与 `FxCoverageScanTest` 逐字一致）：只认 `postFx` 里的**数值字面量** */
    private val postFxRe = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""")
    private val numRe = Regex("""=\s*([0-9]*\.?[0-9]+)f""")

    private fun coveredByPostFx(code: String): Boolean {
        val m = postFxRe.find(code) ?: return false
        return numRe.findAll(m.groupValues[1]).any { it.groupValues[1].toFloat() > 0f }
    }

    /** §B9-① 几何锥度：`TRUNK_STROKE_W * TAPER.pow(d)` + 下限夹紧 */
    private fun geometricTaper(code: String): Boolean =
        code.contains("TRUNK_STROKE_W * TAPER.pow(d)") && code.contains(".coerceAtLeast(MIN_STROKE_W)")

    /** §B9-② 手写椭圆：`moveTo` + `cubicTo` + `close`（⛔ 不用 `addOval`） */
    private fun leafEllipse(code: String): Boolean =
        code.contains("path.moveTo(") && code.contains("path.cubicTo(") && code.contains("path.close()")

    /** §B9-③ 径向纵深：带盐的 `shadeBrushCached` + 半对角线半径 + 整体 alpha */
    private fun bgDepth(code: String): Boolean =
        code.contains("Shading2D.shadeBrushCached(") && code.contains("E34_KEY_SALT") &&
            code.contains("sqrt(w * w + h * h) * 0.5f") && code.contains("alpha = BG_DEPTH_ALPHA")

    /** 缓存键必须覆盖 `(w, h)` 与 `accent`（§四 G4） */
    private fun keyHasDims(code: String): Boolean =
        code.contains("w.toRawBits().toLong() shl 32") &&
            code.contains("h.toRawBits().toLong()") &&
            code.contains("accent.toArgb().toLong()")

    /** dt 化：只吃 `fx.dt` */
    private fun dtMigrated(code: String): Boolean =
        code.contains("advanceDepth(depthF, fx.dt, frame.bass, maxDepth)")

    /** ⛔ 旧时钟哨兵：`lastMs == 0L`（首帧 timeMs 可能恰为 0 ⇒ 会误判为"已初始化"） */
    private fun usesLastMsSentinel(code: String): Boolean =
        Regex("""\blastMs\s*==\s*0L""").containsMatchIn(code)

    /** ⛔ 旧线性锥度（`2.6f - d * 0.28f`） */
    private fun oldLinearTaper(code: String): Boolean =
        Regex("""\(\s*2\.6f\s*-\s*d\s*\*\s*0\.28f\s*\)""").containsMatchIn(code)

    /** ⛔ 旧 alpha 曲线（`0.9f - d * 0.08f`） */
    private fun oldAlphaCurve(code: String): Boolean =
        Regex("""\(\s*0\.9f\s*-\s*d\s*\*\s*0\.08f\s*\)""").containsMatchIn(code)

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
    private val OLD_CLOCK_SNIPPET = """
        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
    """.trimIndent()

    private val NEW_CLOCK_SNIPPET = """
        depthF = advanceDepth(depthF, fx.dt, frame.bass, maxDepth)
    """.trimIndent()

    private val OLD_TAPER_SNIPPET =
        "val baseW = (2.6f - d * 0.28f).coerceAtLeast(0.8f)"

    private val NEW_TAPER_SNIPPET =
        "val baseW = (TRUNK_STROKE_W * TAPER.pow(d)).coerceAtLeast(MIN_STROKE_W)"

    private val OLD_ALPHA_SNIPPET =
        "val alpha = (0.9f - d * 0.08f).coerceAtLeast(0.4f)"

    private val NEW_ALPHA_SNIPPET =
        "val alpha = (SEG_ALPHA_BASE - d * SEG_ALPHA_FALLOFF).coerceAtLeast(SEG_ALPHA_MIN)"

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

    /** 从**已剥注释**的类体里取某个函数的 `{...}` 体（签名要认 receiver `Float.`） */
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
     * ⛔ 只去行注释不够 —— 判据会命中 KDoc 正文里举的旧写法（本仓库已踩 5 次）。
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

    /** `BatchFourRenderers.kt` 的**已剥注释**全文 */
    private fun codeOfE34(): String =
        stripComments(readFile(renderersFile("BatchFourRenderers.kt")))

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

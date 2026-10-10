package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * E44「明月」**T5 相位阴影**的门禁（`docs/moonlit-visualizer-plan.md` §4.2 末 + §4.4，
 * 含 §十一 T5 明写的三条产出断言）。
 *
 * ## 为什么这套断言走解析式而不是截图
 * 明暗界线是三层嵌套闭合 `Path`，观感判读归所有者（§十三 V1/V2/V3、T10），但它的**形状**
 * 由一个可闭式求出的量决定：暗区面积 / 整盘面积 `= (R + v) / (2R)`。
 * ⇒ 「层 0 的暗区恒等于 `1 − f`」就是**轴向与反号**的判别式（A3）。
 * ⛔ 别指望"验收那晚正好是半月"来发现写反：E44 的月相按**真实日期**走，上机那晚的 `f`
 * 不由人算（2026-10-09 实测 `f ≈ 0.05`，几乎全黑）——写反了只会看到"整盘亮"，
 * 而它看起来更像"月相没生效"，⛔ 不是像"几何错了"。
 *
 * ## 三层软化到底在软什么
 * `v_i = R(1−2f) − i·TERM_SOFT_K·R·sign(1−2f)`，三层同 α ⇒ 核心区 `0.945`、最外圈 `0.62`。
 * `sign` 只有一个作用：**永远让最外那一圈只被一层盖到**。少了它，`f > 0.5` 一侧的软带会整体
 * 往暗区**内部**收缩（界线变硬），而 `f → 1` 时更会把本该面积趋于 0 的暗带长成**盘缘暗斑**
 * （"满月变暗斑"）⇒ 定稿的处理是 `f ≥ 0.995` **整段跳过**（A4/A5 分别钉住这两件事）。
 *
 * ## ⛔ 不越盘 = 不需要裁剪（clipPath 红线的结构性自证）
 * A7 断言 `|v_i| ≤ R` 在**会被绘制的全程**（`f ∈ [0, 0.995)`）成立：暗区天生落在盘内。
 * 这不是"我们绕开了禁用 API"，而是**几何上不需要它** —— 与 §5.2 圆盘靠"圆外 alpha=0"
 * 免裁剪是同一个思路。
 */
class MoonPhaseShadowTest {

    /** 1080p 下的月盘半径：`MOON_R_K × min(1920,1080) = 0.150 × 1080`（§3.1） */
    private val r = 162f

    /** 三层**并集**的暗区占比（= 最外那一层的形状） */
    private fun unionDark(f: Float, radius: Float = r): Float {
        var max = 0f
        for (i in 0 until MoonPhase.SHADOW_LAYERS) {
            val a = MoonPhase.darkAreaFraction(MoonPhase.shadowVertexPx(f, radius, i), radius)
            if (a > max) max = a
        }
        return max
    }

    private fun layer0Dark(f: Float, radius: Float = r): Float =
        MoonPhase.darkAreaFraction(MoonPhase.shadowVertexPx(f, radius, 0), radius)

    // ── A. 形状 ──────────────────────────────────────────────────────────────────

    /** §十一 T5 产出 ①：`f = 0.25` 时暗区面积 ∈ [0.70, 0.80] 盘。 */
    @Test
    fun `A1 弦月以下时暗区约占四分之三盘`() {
        assertTrue("f=0.25 暗区占比 ${unionDark(0.25f)} 应 ∈ [0.70, 0.80]", unionDark(0.25f) in 0.70f..0.80f)
        // 层 0 是未软化的精确界线 ⇒ 解析值必须正好 1−f（软化只往外扩，不往里收）
        assertEquals(0.75f, layer0Dark(0.25f), 1e-4f)
    }

    /** §十一 T5 产出 ②：`f = 0.5` 时半短轴 = 0（一条直线弦）。 */
    @Test
    fun `A2 半明半暗时半短轴恰为零`() {
        assertEquals(0f, MoonPhase.shadowVertexPx(0.5f, r, 0), 1e-6f)
        assertEquals(0.5f, MoonPhase.darkAreaFraction(0f, r), 1e-6f)
        // ⚠️ f=0.5 时 `sign` 取 +1（原型写作 `Math.sign(1−2f || 1)`）⇒ 软化层只往**暗侧**收，
        //    所以并集仍是精确的半盘。把它写成 0 会让三层完全重合（软带消失），本条抓得住。
        assertEquals(0.5f, unionDark(0.5f), 1e-4f)
        // 层 1 的 |v| 已越过退化阈值 ⇒ 走半椭圆而不是零宽椭圆（零宽会画毛刺）
        assertTrue(abs(MoonPhase.shadowVertexPx(0.5f, r, 1)) > MoonPhase.SHADOW_VERTEX_EPS)
    }

    /**
     * A3 ⭐ **轴向判别式**：对全程 `f`，层 0 的暗区占比必须 `= 1 − f`。
     *
     * ⛔ 不要只在 `f = 0.25` 一个点上证：§4.2 初版写的 `b = R(2f−1)` 与定稿的 `v = R(1−2f)`
     * 是同一量在相反轴向的写法，两者**只在 `f = 0.5` 处相等**。
     */
    @Test
    fun `A3 层零的暗区恒等于 1 减照度`() {
        for (f in floatArrayOf(0f, 0.05f, 0.2f, 0.3f, 0.5f, 0.7f, 0.9f, 0.99f)) {
            val a = layer0Dark(f)
            assertEquals("f=$f 时暗区应 ${(1f - f)}，实为 $a", 1f - f, a, 1e-4f)
        }
        // 负向自证：写反轴向在 f=0.25 处给出 0.25 ⇒ A1 的带 [0.70,0.80] 必须挡住它
        val wrongAxis = MoonPhase.darkAreaFraction(r * (2f * 0.25f - 1f), r)
        assertTrue("反号写法算出 $wrongAxis，竟也落在 [0.70,0.80] ⇒ A1 失去判别力",
            wrongAxis !in 0.70f..0.80f)
    }

    /** A4 `sign` 只服务一件事：**最外圈永远只被一层盖到**（这才叫软化）。 */
    @Test
    fun `A4 三层嵌套永远让最外圈是单层`() {
        // f<0.5：暗区超过半盘，层 0 最外、层 2 是最暗的核心
        assertTrue(MoonPhase.shadowVertexPx(0.2f, r, 0) > MoonPhase.shadowVertexPx(0.2f, r, 2))
        // f>0.5：暗区不足半盘，方向**必须反过来**，层 2 才是最外
        assertTrue(MoonPhase.shadowVertexPx(0.8f, r, 2) > MoonPhase.shadowVertexPx(0.8f, r, 0))
        // 软带在面积上正好宽 k·盘（= 2 档 × k·R 的一半），⛔ 不许为 0（三层重合）
        assertEquals(MoonPhase.TERM_SOFT_K, unionDark(0.8f) - layer0Dark(0.8f), 1e-4f)
    }

    /**
     * A5 ⭐ **满月跳过的理由**（§4.2 末）——同时是负向自证：
     * 先证明"该几何在 `f = 1` 时确实会长出可见暗斑"，才让 [MoonPhase.skipPhaseShadow]
     * 不是多余的谨慎。
     */
    @Test
    fun `A5 近满月必须整段跳过阴影`() {
        assertTrue(MoonPhase.skipPhaseShadow(0.995f))
        assertTrue(MoonPhase.skipPhaseShadow(1f))
        // 边界逐字对齐原型 `st.f < 0.995`，⛔ "差不多就行"
        assertTrue(!MoonPhase.skipPhaseShadow(0.9949f))
        assertTrue(!MoonPhase.skipPhaseShadow(0.9f))
        // 层 0 在 f=1 时面积趋于 0，但层 2 长出 4.5% 盘的暗斑 ⇒ 只能整段跳
        assertEquals(0f, layer0Dark(1f), 1e-6f)
        val sliver = unionDark(1f)
        assertTrue("f=1 若仍画阴影，暗斑占比 $sliver 应 ≥0.04（这就是'满月变暗斑'）", sliver >= 0.04f)
        // ⛔ "画淡一点"不是解：软化的**面积下限**恒为 TERM_SOFT_K 个盘，与 α 无关。
        //   调小 α 只是把那块暗斑从 0.945 降到 0.62，几何上照样存在。
        assertTrue("f→1 时软化并集有下限 $sliver，应 ≥ TERM_SOFT_K", sliver >= MoonPhase.TERM_SOFT_K)
    }

    /** A6 三层**同 α**，核心区靠叠加得到 0.945（⛔ 不是逐层递减）。 */
    @Test
    fun `A6 三层同零点六二且核心区达零点九四五`() {
        assertEquals(0.62f, MoonPhase.SHADOW_ALPHA, 1e-6f)
        assertEquals(3, MoonPhase.SHADOW_LAYERS)
        val a = MoonPhase.SHADOW_ALPHA
        val core = 1f - (1f - a) * (1f - a) * (1f - a)
        assertEquals(0.945f, core, 1e-3f)
        // 递减写法（0.62/0.45/0.30）的核心区只有 0.74 —— 暗面会读作半透玻璃
        val descending = 1f - (1f - 0.62f) * (1f - 0.45f) * (1f - 0.30f)
        assertTrue("逐层递减给 $descending，与'三层同 α'的 0.945 不同 ⇒ 常量必须按同值使用",
            descending < core)
    }

    /**
     * A7 ⛔ **不越盘**（⇒ 几何上不需要 `clipPath`）：会被绘制的全程 `|v_i| ≤ R`。
     * 顺带证明暗区占比恒在 `[0, 1]`（画不出"比全黑还黑"或"负暗区"）。
     */
    @Test
    fun `A7 暗区顶点永不越出圆盘`() {
        var f = 0f
        while (f < 0.995f) {
            for (i in 0 until MoonPhase.SHADOW_LAYERS) {
                val v = MoonPhase.shadowVertexPx(f, r, i)
                assertTrue("f=$f 层$i 顶点 $v 越出 ±$r ⇒ 阴影会画到盘外（那就得裁剪了）",
                    abs(v) <= r + 1e-3f)
                val a = MoonPhase.darkAreaFraction(v, r)
                assertTrue("f=$f 层$i 暗区占比 $a 越界", a in 0f..1f)
            }
            f += 0.005f
        }
        // 半径退化也不能炸（⛔ 返回 NaN 会让 Path 静默画歪）
        assertEquals(0f, MoonPhase.darkAreaFraction(0f, 0f), 1e-6f)
    }

    // ── B. 朝向（§4.4）──────────────────────────────────────────────────────────

    /**
     * §十一 T5 产出 ③：`e = 180°` 时 `tiltDeg` 与 PA 无关（朔望退化保护）。
     * 换算到画布后必须恒为 `−90°`，⛔ 不是随 PA 一夜转 112°（§4.4 实测 10-25→10-26）。
     */
    @Test
    fun `B1 望处倾角与位置角无关`() {
        for (pa in floatArrayOf(-160f, -105.1f, -86.5f, 26f, 81.7f, 150f)) {
            assertEquals("PA=$pa 在 e=180 处不该影响倾角",
                0f, MoonPhase.shadowTiltDeg(pa, 180f), 1e-3f)
            assertEquals(-90f, MoonPhase.terminatorRotDeg(MoonPhase.shadowTiltDeg(pa, 180f)), 1e-3f)
        }
        // 另一端必须**吃满** PA，否则"加权"等于把朝向永远关掉
        assertEquals(-170f, MoonPhase.terminatorRotDeg(MoonPhase.shadowTiltDeg(80f, 90f)), 1e-2f)
    }

    /**
     * B2 ⭐ `+90` 与取负两个偏置（§4.4 末，⛔ 少一个月牙朝向整晚错 90° 或左右镜像）。
     *
     * ⚠️ 单位是**度**：原型写 `(−(tilt+90))×DEG_TO_RAD` 是因为 HTML `canvas.rotate` 收弧度，
     * 而 `android.graphics.Canvas.rotate` 收度数 ⇒ Kotlin 侧停在度数（登记 §十一 T5 落地偏离）。
     */
    @Test
    fun `B2 画布旋转取负并加九十度`() {
        assertEquals(-90f, MoonPhase.terminatorRotDeg(0f), 1e-6f)
        assertEquals(-180f, MoonPhase.terminatorRotDeg(90f), 1e-6f)
        assertEquals(0f, MoonPhase.terminatorRotDeg(-90f), 1e-6f)
        assertTrue("方向反了 ⇒ 月牙左右镜像", MoonPhase.terminatorRotDeg(45f) < MoonPhase.terminatorRotDeg(-45f))
        // ⛔ 有人把弧度乘回来：90° 会变成 1.57°，月牙看起来"几乎不倾斜"
        assertTrue("旋转量级不对（疑似又乘了 π/180）", abs(MoonPhase.terminatorRotDeg(0f)) > 10f)
    }

    /**
     * B3 ⭐ **真实历元走完整条链**：拿 §4.6 B 表那七天（北京 21:00）的历算结果喂进几何，
     * 断言「暗区 = 1−f ± 软带」且 10-26（f=0.998）确实落在**跳过**一侧。
     *
     * 这条是 A3 的"活体版"：A3 只测纯几何，B3 保证 `MoonPhase` 的输出**量纲**没被
     * 谁改成百分数（`f=99.8` 会让阴影整年不画，画面看起来"月相坏了"）。
     */
    @Test
    fun `B3 真实历元的照度喂进几何后自洽`() {
        val table = listOf(
            "2026-10-05" to 0.280f,
            "2026-10-08" to 0.051f,
            "2026-10-11" to 0.008f,
            "2026-10-14" to 0.146f,
            "2026-10-18" to 0.488f,
            "2026-10-21" to 0.765f,
            "2026-10-26" to 0.998f,
            "2026-10-29" to 0.849f,
        )
        for ((date, expectedF) in table) {
            val st = MoonPhase.of(bj2100(date))
            assertEquals("$date 照度应 ∈ 0..1", st.illum, expectedF, 0.005f)
            assertTrue("$date 的 f=${st.illum} 不在 0..1 ⇒ 量纲被改过", st.illum in 0f..1f)
            if (MoonPhase.skipPhaseShadow(st.illum)) {
                assertTrue("$date 落在跳过一侧的前提是 f ≥ 0.995，实为 ${st.illum}", st.illum >= 0.995f)
            } else {
                assertEquals("$date 暗区应 = 1−f ± 软带",
                    1f - st.illum, unionDark(st.illum), MoonPhase.TERM_SOFT_K + 1e-3f)
            }
        }
        // B 表里唯一被跳过的就是 10-26（f=0.998）
        assertTrue(MoonPhase.skipPhaseShadow(MoonPhase.of(bj2100("2026-10-26")).illum))
    }

    // ── C. 渲染器侧写法门禁 ──────────────────────────────────────────────────────

    /**
     * C1 阴影路径**零裁剪**：`MoonlitRenderer` 全文不许出现 `clipPath(` / 圆角 clip /
     * `RoundedCornerShape`（API 22 创维 hwui 三次真机 SIGSEGV，禁令**不分圆角与否**）。
     * 并检查暗区确实靠"闭合 Path + 两次 arcTo"画出来，⛔ 逐帧新建 `Path`/`Matrix`。
     */
    @Test
    fun `C1 渲染器全文零裁剪且路径对象复用`() {
        val src = rendererSource()
        for (forbidden in listOf("clipPath(", "clip(RoundRect", "RoundedCornerShape")) {
            assertEquals("出现禁用写法 $forbidden", 0, src.split(forbidden).size - 1)
        }
        assertTrue("暗区必须走闭合 Path", src.contains("p.close()"))
        assertTrue("半圆 + 半椭圆两次 arcTo", Regex("arcTo\\(").findAll(src).toList().size >= 2)
        assertTrue("⛔ 不许引入 Matrix 变换", !src.contains("Matrix("))
        assertEquals("Path 只许字段那一个构造点", 1, Regex("= Path\\(\\)").findAll(src).toList().size)
        assertEquals("RectF 只许 blitDst + shadowOval 两个复用字段",
            2, Regex("RectF\\(\\)").findAll(src).toList().size)
    }

    /**
     * C2 ⚠️ **层序即契约**（§3.2）：`BLOOM` 必须在相位阴影**之前**（月相要能压住过曝芯，
     * 只有满月/近满月才吃到那口过曝），极淡边缘在阴影**之后**。
     */
    @Test
    fun `C2 过曝芯排在相位阴影之前`() {
        val body = functionBody("drawContent")
        val disk = body.indexOf("drawMoonDisk(")
        val bloom = body.indexOf("drawMoonBloom(")
        val shadow = body.indexOf("drawPhaseShadow(")
        val edge = body.indexOf("drawDarkLimbEdge(")
        assertTrue("缺层：disk=$disk bloom=$bloom shadow=$shadow edge=$edge",
            disk >= 0 && bloom >= 0 && shadow >= 0 && edge >= 0)
        assertTrue("层序必须 盘 → 过曝芯 → 阴影 → 极淡边缘",
            disk < bloom && bloom < shadow && shadow < edge)
    }

    /**
     * C3 阴影的 α 只有一个来源（[MoonPhase.SHADOW_ALPHA]），⛔ 渲染器里再乘一次照度 `f`
     * ——`f` 已经体现在阴影**几何**里，再乘就是 §5.4 明令禁止的双重变暗（§3.0(d) 不变式 2）。
     */
    @Test
    fun `C3 阴影亮度不重复乘照度`() {
        val src = rendererSource()
        assertTrue("阴影 α 必须引用 SHADOW_ALPHA", src.contains("MoonPhase.SHADOW_ALPHA"))
        val body = functionBody("drawPhaseShadow")
        assertTrue("阴影里不许出现乘法改亮度：\n$body",
            !Regex("""\*\s*(f|st\.illum|illum)\b""").containsMatchIn(body))
        assertTrue("顶点必须走 MoonPhase.shadowVertexPx", body.contains("MoonPhase.shadowVertexPx"))
        assertTrue("朝向必须走 |sin e| 加权后的 tilt", body.contains("MoonPhase.shadowTiltDeg"))
        assertTrue("跳过判定必须走 skipPhaseShadow", body.contains("MoonPhase.skipPhaseShadow"))
        assertTrue("旋转必须走 terminatorRotDeg（+90 只许写在 MoonPhase 里）",
            body.contains("MoonPhase.terminatorRotDeg"))
    }

    /** C4 §6.5 的极淡边缘必须**有阈值**，⛔ 常亮（否则新月夜会多出一条不属于任何照度的白边）。 */
    @Test
    fun `C4 极淡边缘按照度阈值早退`() {
        val body = functionBody("drawDarkLimbEdge")
        assertTrue("必须按 DARK_EDGE_MAX_F 早退", body.contains("DARK_EDGE_MAX_F") && body.contains("return"))
        // ⚠️ 这里原本钉的是「ANTI_ALIAS Paint 恰好 2 处」——那个 2 是 T5 时代的**数量**代理，
        //    真正要防的是「Paint 被逐帧 new」。T7 加了画云椭圆的 `softPaint` 就把它判红了
        //    （字段多一个是完全正当的）。⇒ 判据改成钉**位置**：每一处都必须是类字段声明，
        //    写在任何方法体里（局部 `val`）一律红。数量随元素长无所谓。
        val offenders = paintOffenders(rendererSource())
        assertTrue("Paint 不在字段上（逐帧 new ⇒ 每帧一次分配）：\n${offenders.joinToString("\n")}",
            offenders.isEmpty())
        // 判据不能对空文件假绿：字段那几处必须**真的存在**
        assertTrue("一个 ANTI_ALIAS Paint 都没扫到 ⇒ 判据空转",
            Regex("""private val \w+ = Paint\(Paint\.ANTI_ALIAS_FLAG\)""").findAll(rendererSource()).toList().size >= 2)
        // 线宽随画幅 ⇒ 必须落在 setupDisk（尺寸分支），⛔ 写死常数、⛔ 放进每帧函数体
        assertTrue("描边线宽在尺寸分支里设",
            functionBody("setupDisk").contains("edgePaint.strokeWidth"))
    }

    /** 负向自证：同一个判据函数必须抓得住"把 Paint 挪进方法体"这种写法。 */
    @Test
    fun `C4b 负向自证 方法体里 new Paint 必须被抓到`() {
        val bad = """
            private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            private fun drawPhaseShadow() {
                val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            }
        """.trimIndent()
        val hit = paintOffenders(bad)
        assertEquals("判据对局部 new 失明 ⇒ C4 那条是空转", 1, hit.size)
        assertTrue(hit[0].contains("framePaint"))
    }

    /** 扫出所有**不在类字段上**的 `Paint(Paint.ANTI_ALIAS_FLAG)`（⛔ 逐帧分配的代理判据）。 */
    private fun paintOffenders(src: String): List<String> =
        src.lines().filter { it.contains("Paint(Paint.ANTI_ALIAS_FLAG)") }
            .filterNot { Regex("""^\s*private val \w+ = Paint\(Paint\.ANTI_ALIAS_FLAG\)(\.apply \{)?$""").matches(it) }

    // ── 夹具 ─────────────────────────────────────────────────────────────────────

    /**
     * ⚠️ 判据一律跑在**剥掉注释**的源码上：本渲染器的类 KDoc 与红线说明里**原文引用**了
     * `clipPath(` / `clip(RoundRect` 这些被禁写法（C1 要查的正是它们），不剥注释就是自己判自己红。
     * 顺带这也让 [functionBody] 的大括号配对不会被注释里的花括号带偏。
     */
    private fun rendererSource(): String = stripComments(
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/MoonlitRenderer.kt").readText()
    )

    /** 剥块注释（Kotlin 可嵌套）与行注释，⛔ 必须保持行数一致（豁免/定位按原始行号）。 */
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

    /** 取某个函数的体（大括号配对），层序/写法这类断言只能对**真实代码**判。 */
    private fun functionBody(name: String): String {
        val src = rendererSource()
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

    /** §4.6 B：北京 21:00 = UTC 13:00（⚠️ 只在测试里用 `Calendar`，生产代码 ⛔ 不读时区） */
    private fun bj2100(date: String): Long {
        val p = date.split('-')
        return GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt(), 13, 0, 0)
        }.timeInMillis
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

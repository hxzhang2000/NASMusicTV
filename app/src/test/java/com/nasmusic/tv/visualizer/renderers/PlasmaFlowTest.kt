package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import com.nasmusic.tv.visualizer.fx.ProceduralTexture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * E20 等离子流场（§B6 · T4.6）门禁。
 *
 * 四段：
 * - **常量段**：§B6 明文值（网格 / fbm / 桶 / 拉长比 / 帧率基准）；
 * - **行为段**：**直接调生产纯函数** [PlasmaFlowRenderer.Companion.fbmAt] /
 *   [PlasmaFlowRenderer.Companion.bucketOf] / [PlasmaFlowRenderer.Companion.ellipsePoint]
 *   —— ⛔ 不复制算法（复制必然漂移，见 §八 开头规矩）；
 * - **tile 段**：`ProceduralTexture.Id.PLASMA` 存在 + `plasmaRow` 逐行确定 + 三通道彩色；
 * - **源码段**：每帧 `drawCircle` / `drawOval` 均为 0、`drawPath` 单一调用点、`postFx` 字面量。
 *
 * ⛔ 负向自证 4 条 —— 缺一条就可能空转。
 * ⛔ 源码判据一律**先剥注释**（本仓库已踩 5 次：判据命中自己刚写的 KDoc ⇒ 假 FAIL）。
 * ⛔ 负向样本必须与正向**喂同一份谓词**（不要另写「预期为 false」的表达式）。
 */
class PlasmaFlowTest {

    private val C = PlasmaFlowRenderer.Companion

    // ═══════════ 行为段：直接调生产纯函数（⛔ 不复制算法） ═══════════

    /** 参考分解的**第一层**（与 [PlasmaFlowRenderer.Companion.fbmAt] 的 v1 同式） */
    private fun layer1(gx: Int, gy: Int, ev: Float): Float =
        sin(gx * C.N1_FX + ev) * cos(gy * C.N1_FY - ev * C.N1_EP)

    /** 参考分解的**第二层**（细节层） */
    private fun layer2(gx: Int, gy: Int, ev: Float): Float =
        sin(gx * C.N2_FX - ev * C.N2_EP) * cos(gy * C.N2_FY + ev * C.N2_EP2)

    /** 双线性插值（与生产 `sampleFlow` 同式的纯函数；测试自用） */
    private fun bilinear(f: FloatArray, gw: Int, gh: Int, u: Float, v: Float): Float {
        val x = (u * (gw - 1)).coerceIn(0f, gw - 1.001f)
        val y = (v * (gh - 1)).coerceIn(0f, gh - 1.001f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = x - x0
        val fy = y - y0
        val i00 = f[y0 * gw + x0]
        val i10 = f[y0 * gw + (x0 + 1).coerceAtMost(gw - 1)]
        val i01 = f[(y0 + 1).coerceAtMost(gh - 1) * gw + x0]
        val i11 = f[(y0 + 1).coerceAtMost(gh - 1) * gw + (x0 + 1).coerceAtMost(gw - 1)]
        val a = i00 + (i10 - i00) * fx
        val b = i01 + (i11 - i01) * fx
        return a + (b - a) * fy
    }

    /** 生产实现（`drawContent`）：`k = fx.dt × FPS_BASE` 折算后的 `evolve` 增量 */
    private fun evolveNew(fps: Int, seconds: Float, mid: Float): Float {
        var ev = 0f
        val k = (1f / fps) * C.FPS_BASE
        repeat((seconds * fps).toInt()) { ev += (C.EVOLVE_BASE + mid * C.EVOLVE_MID) * k }
        return ev
    }

    /** 旧实现（每帧固定增量，无 `dt` 折算） */
    private fun evolveOld(fps: Int, seconds: Float, mid: Float): Float {
        var ev = 0f
        repeat((seconds * fps).toInt()) { ev += C.EVOLVE_BASE + mid * C.EVOLVE_MID }
        return ev
    }

    /** 生产实现：粒子位移（`(SPEED_BASE + treble×SPEED_TREBLE)/1000 × k`） */
    private fun speedNew(fps: Int, treble: Float): Float =
        (C.SPEED_BASE + treble * C.SPEED_TREBLE) / 1000f * ((1f / fps) * C.FPS_BASE)

    /** 旧实现：粒子位移（每帧固定） */
    private fun speedOld(treble: Float): Float = (C.SPEED_BASE + treble * C.SPEED_TREBLE) / 1000f

    /** 生产实现：寿命衰减（`LIFE_DECAY × k`） */
    private fun lifeDecayNew(fps: Int): Float = C.LIFE_DECAY * ((1f / fps) * C.FPS_BASE)

    /**
     * **判据**：给定「按帧率 f 每帧演化一次」的实现，**1 秒后的累计量**必须三档一致。
     *
     * ⛔ `step(f)` 必须返回 **1 秒的累计量**（逐帧量要乘帧率）—— 只比单帧量的话
     * dt 化实现反而会被判成"差 2 倍"（每帧位移随帧率下降而增大，但帧数也变少）。
     * ⛔ 正 / 负向**必须喂同一份谓词**（另写一份「预期为 false」的表达式是空转自证）。
     */
    private fun frameRateIndependent(step: (Int) -> Float): Boolean {
        val a = step(60)
        val b = step(30)
        val c = step(15)
        return abs(a - b) < 1e-3f && abs(a - c) < 1e-3f
    }

    // ═══════════════════════════ ① 常量段 ═══════════════════════════

    @Test
    fun `① §B6 明文常量齐备且取值正确`() {
        // §B6-① 网格 16×9 → 24×14
        assertEquals(24, C.GW)
        assertEquals(14, C.GH)
        assertTrue("网格必须比旧的 16×9=144 更密（实际 ${C.GW * C.GH}）", C.GW * C.GH > 16 * 9)
        assertEquals(3, C.NOISE_EVERY)
        assertEquals(0.5f, C.FBM_K, 1e-6f)
        // §B6-② 等离子底色
        assertEquals(0.16f, C.PLASMA_ALPHA_BASE, 1e-6f)
        assertEquals(0.10f, C.PLASMA_ALPHA_ENERGY, 1e-6f)
        assertEquals(0.62f, C.CORE_RADIUS_K, 1e-6f)
        // §B6-③ 粒子短条
        assertEquals(8, C.BUCKETS)
        assertEquals(8, C.SEG)
        assertEquals(1.8f, C.ELONG, 1e-6f)
        assertEquals(3f, C.R_BASE, 1e-6f)
        assertEquals(5f, C.R_LIFE, 1e-6f)
        assertEquals(0.8f, C.ALPHA_K, 1e-6f)
        // 色相（与旧实现一致）
        assertEquals(60f, C.HUE_BASE, 1e-6f)
        assertEquals(135f, C.HUE_SPAN, 1e-6f)
        assertEquals(75f, C.HUE_OFF, 1e-6f)
        assertEquals(0.68f, C.PARTICLE_L, 1e-6f)
        // 帧率折算基准（60fps 下与旧实现逐像素等同）
        assertEquals(60f, C.FPS_BASE, 1e-6f)
        assertEquals(0.01f, C.EVOLVE_BASE, 1e-6f)
        assertEquals(0.03f, C.EVOLVE_MID, 1e-6f)
        assertEquals(0.35f, C.BEAT_KICK, 1e-6f)
        assertEquals(1.5f, C.SPEED_BASE, 1e-6f)
        assertEquals(5f, C.SPEED_TREBLE, 1e-6f)
        assertEquals(0.006f, C.LIFE_DECAY, 1e-6f)
        assertEquals(60, C.MIN_PARTICLES)
        assertTrue("缓存盐不得为 0（否则与默认槽撞键）", C.E20_KEY_SALT != 0L)
    }

    // ═══════════════════════════ ② fbm ═══════════════════════════

    @Test
    fun `② fbm - 逐点数值正确、权重挂在第二层、且与「先合并再插值」等价`() {
        val gx = 5
        val gy = 3
        val ev = 0.37f
        // 逐点：与参考分解 + FBM_K 权重逐字对齐
        val expect = layer1(gx, gy, ev) + C.FBM_K * layer2(gx, gy, ev)
        assertEquals("fbmAt 必须 = v1 + FBM_K × v2", expect, C.fbmAt(gx, gy, ev), 1e-6f)
        // ⛔ 负向样本（同一判据）：权重错位写成 `v1 × FBM_K + v2` 必须判否
        val swapped = layer1(gx, gy, ev) * C.FBM_K + layer2(gx, gy, ev)
        assertTrue(
            "权重必须挂在**第二层**上（权重错位必须与正解不同）",
            abs(expect - swapped) > 1e-6f
        )
        assertTrue(
            "权重错位的实现不得被判为正确",
            abs(C.fbmAt(gx, gy, ev) - swapped) > 1e-6f
        )

        // ⭐ 等价性：双线性是**线性算子** ⇒ bilinear(合并) ≡ bilinear(v1) + K·bilinear(v2)
        //    这正是「在更新循环里合并、而不是在 sampleFlow 里插值两次」的依据。
        val gw = C.GW
        val gh = C.GH
        val merged = FloatArray(gw * gh)
        val a = FloatArray(gw * gh)
        val b = FloatArray(gw * gh)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val i = y * gw + x
                merged[i] = C.fbmAt(x, y, ev)
                a[i] = layer1(x, y, ev)
                b[i] = layer2(x, y, ev)
            }
        }
        for (uv in listOf(0.13f to 0.71f, 0.5f to 0.5f, 0.87f to 0.22f, 0f to 1f)) {
            val (u, v) = uv
            val lhs = bilinear(merged, gw, gh, u, v)
            val rhs = bilinear(a, gw, gh, u, v) + C.FBM_K * bilinear(b, gw, gh, u, v)
            assertEquals(
                "双线性可交换（先合并再插值 ≡ 先插值再合并）@($u,$v)",
                rhs, lhs, 1e-4f
            )
        }
    }

    // ═══════════════════════════ ③ 色相分桶 ═══════════════════════════

    @Test
    fun `③ 色相分桶 - 桶号恒在 0~7、负 flow 不塌缩、8 桶全用满`() {
        for (flow in listOf(-3f, -1.5f, -1f, -0.5f, -0.01f, 0f, 0.3f, 0.75f, 1f, 1.5f, 3f)) {
            val b = C.bucketOf(flow)
            assertTrue("flow=$flow ⇒ 桶号 $b 越界 [0,${C.BUCKETS})", b in 0 until C.BUCKETS)
        }
        // ⛔ Kotlin 的 `%` 保留被除数符号 ⇒ 忘了归一的话 flow<0 会全被压到桶 0。
        //    用「负 flow 必须能落到多个桶」把这条钉住。
        val negUsed = listOf(-0.95f, -0.7f, -0.45f, -0.2f).map { C.bucketOf(it) }.toSet()
        assertTrue(
            "负 flow 必须能落到多个桶（否则是忘了取模归一）实际=$negUsed",
            negUsed.size >= 2
        )
        // 覆盖性：把 flow 扫过整个值域，8 个桶必须都被用到
        val used = (0 until 400).map { C.bucketOf(-1.5f + it * 3f / 400f) }.toSet()
        assertEquals("8 个桶必须都被用到", C.BUCKETS, used.size)
        // 与旧实现的色相式自洽：桶中心色相必须落在 [HUE_BASE, HUE_BASE + HUE_SPAN)
        val h0 = C.HUE_BASE + 0.5f * C.HUE_SPAN / C.BUCKETS
        val h7 = C.HUE_BASE + 7.5f * C.HUE_SPAN / C.BUCKETS
        assertTrue("桶中心色相必须落在色相区间内（$h0 / $h7）", h0 >= C.HUE_BASE && h7 < C.HUE_BASE + C.HUE_SPAN)
    }

    // ═══════════════════════════ ④ 参数方程椭圆 ═══════════════════════════

    @Test
    fun `④ 参数方程 - 长轴沿速度方向、长轴 = 短轴 × 1_8、8 段覆盖`() {
        val cx = 100f
        val cy = 200f
        val aMaj = 9f
        val bMin = 5f
        assertEquals("长轴 / 短轴必须 == ELONG", C.ELONG, aMaj / bMin, 1e-6f)
        val ca = cos(0.7f)
        val sa = sin(0.7f)

        // s = 0 ⇒ 沿速度方向 u = (ca, sa) 偏移 aMaj
        val p0 = C.ellipsePoint(0, cx, cy, aMaj, bMin, ca, sa)
        assertEquals(cx + aMaj * ca, p0.x, 1e-4f)
        assertEquals(cy + aMaj * sa, p0.y, 1e-4f)

        // s = SEG/4 ⇒ 沿法线 v = (−sa, ca) 偏移 bMin
        val pq = C.ellipsePoint(C.SEG / 4, cx, cy, aMaj, bMin, ca, sa)
        assertEquals(cx - bMin * sa, pq.x, 1e-4f)
        assertEquals(cy + bMin * ca, pq.y, 1e-4f)

        // 8 段多边形的**极值半径** = 半长轴 / 半短轴（即长轴确实沿 u）
        var maxD = 0f
        var minD = Float.MAX_VALUE
        for (s in 0 until C.SEG) {
            val p = C.ellipsePoint(s, cx, cy, aMaj, bMin, ca, sa)
            val dx = p.x - cx
            val dy = p.y - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d > maxD) maxD = d
            if (d < minD) minD = d
        }
        assertEquals("最远点距离 = 半长轴", aMaj, maxD, 1e-3f)
        assertEquals("最近点距离 = 半短轴", bMin, minD, 1e-3f)

        // ⭐ 决定性：长轴必须**随速度方向旋转** —— 这正是轴对齐 `drawOval` 做不到的
        val pRight = C.ellipsePoint(0, 0f, 0f, aMaj, bMin, 1f, 0f)
        assertTrue(
            "速度方向 0° ⇒ 长轴沿 +x（实际 ${pRight.x},${pRight.y}）",
            abs(pRight.x - aMaj) < 1e-4f && abs(pRight.y) < 1e-4f
        )
        val pDown = C.ellipsePoint(0, 0f, 0f, aMaj, bMin, 0f, 1f)
        assertTrue(
            "速度方向 90° ⇒ 长轴沿 +y（实际 ${pDown.x},${pDown.y}）",
            abs(pDown.x) < 1e-4f && abs(pDown.y - aMaj) < 1e-4f
        )
        // ⛔ 负向样本（同一判据）：轴对齐的 drawOval 无论方向都只能给出 (aMaj, 0)
        val axisAligned = Offset(aMaj, 0f)
        assertFalse(
            "轴对齐椭圆不得被判为「长轴随方向旋转」",
            abs(axisAligned.x - pDown.x) < 1e-4f && abs(axisAligned.y - pDown.y) < 1e-4f
        )
    }

    // ═══════════════════════════ ⑤⑥ 帧率无关 ═══════════════════════════

    @Test
    fun `⑤ 帧率无关 - evolve、位移、寿命衰减三档帧率一致`() {
        // ⚠️ 位移与寿命衰减要 ×fps 才是「1 秒累计量」（谓词比的是 1 秒，不是单帧）
        assertTrue(
            "正向：dt 化 evolve 必须帧率无关",
            frameRateIndependent { evolveNew(it, 1f, 0.6f) }
        )
        assertTrue(
            "正向：dt 化位移必须帧率无关（1 秒位移）",
            frameRateIndependent { speedNew(it, 0.4f) * it }
        )
        assertTrue(
            "正向：dt 化寿命衰减必须帧率无关（1 秒衰减）",
            frameRateIndependent { lifeDecayNew(it) * it }
        )

        // ⛔ 负向 N1：每帧固定增量（旧实现）**必须**被判为帧率绑定 —— 同一份谓词
        assertFalse(
            "负向 N1：旧「每帧固定增量」的 evolve 必须被判为帧率绑定",
            frameRateIndependent { evolveOld(it, 1f, 0.6f) }
        )
        assertFalse(
            "负向 N1：旧「每帧固定位移」必须被判为帧率绑定（1 秒位移）",
            frameRateIndependent { speedOld(0.4f) * it }
        )
        // 并钉住绑定的具体形态：30fps 恰为 60fps 的 50%
        assertEquals(
            "旧实现 30fps 必须恰为 60fps 的 50%（否则负向样本写错）",
            0.5f, evolveOld(30, 1f, 0.6f) / evolveOld(60, 1f, 0.6f), 1e-4f
        )
        // 1 秒后的绝对值（供后续复核）：mid = 0.6 ⇒ 每秒 (0.01 + 0.018) × 60 = 1.68
        assertEquals("1 秒后 evolve 增量", 1.68f, evolveNew(60, 1f, 0.6f), 1e-3f)
        assertEquals("1 秒位移（treble 0.4）", 0.21f, speedNew(60, 0.4f) * 60, 1e-4f)
        assertEquals("1 秒寿命衰减", 0.36f, lifeDecayNew(60) * 60, 1e-4f)
    }

    @Test
    fun `⑥ 60fps 下与旧实现逐像素等同（k == 1）`() {
        val k60 = (1f / 60f) * C.FPS_BASE
        assertTrue("60fps 下 k 必须为 1（实际 $k60）", abs(k60 - 1f) < 1e-5f)
        // 逐项：60fps 的折算结果 == 旧实现每帧固定增量
        assertEquals(
            "60fps 的 evolve 增量必须与旧实现一致",
            C.EVOLVE_BASE + 0.6f * C.EVOLVE_MID,
            (C.EVOLVE_BASE + 0.6f * C.EVOLVE_MID) * k60,
            1e-6f
        )
        assertEquals(
            "60fps 的寿命衰减必须与旧实现一致",
            C.LIFE_DECAY,
            lifeDecayNew(60),
            1e-6f
        )
        assertEquals(
            "60fps 的位移必须与旧实现一致",
            speedOld(0.4f),
            speedNew(60, 0.4f),
            1e-7f
        )
    }

    // ═══════════════════════════ ⑦ 源码段 ═══════════════════════════

    @Test
    fun `⑦ 源码 - drawContent 内 0 个 drawCircle、drawOval、drawPath 单一调用点、零 Rect 分配`() {
        val code = codeOfE20()
        val draw = funBody(classBody(code, "PlasmaFlowRenderer"), "drawContent")
        assertTrue("能切出 drawContent 体", draw.isNotEmpty())

        assertEquals(
            "每帧不得再逐粒子 drawCircle（合批后为 0）",
            0, Regex("""(?<![A-Za-z0-9_])drawCircle\(""").findAll(draw).count()
        )
        assertEquals(
            "不得用 drawOval（轴对齐椭圆表达不了速度方向）",
            0, Regex("""(?<![A-Za-z0-9_])drawOval\(""").findAll(draw).count()
        )
        assertEquals(
            "⛔ 不得有 Rect( 堆分配（词边界，避免误伤 drawImageRect）",
            0, Regex("""(?<![A-Za-z0-9_])Rect\(""").findAll(draw).count()
        )
        assertEquals(
            "drawPath 必须只有**一个调用点**（在桶循环内 ×8）",
            1, Regex("""(?<![A-Za-z0-9_])drawPath\(""").findAll(draw).count()
        )
        assertEquals(
            "等离子 tile 只画一次",
            1, Regex("""(?<![A-Za-z0-9_])drawImage\(""").findAll(draw).count()
        )
        assertTrue("必须调 ProceduralTexture.ensure（背景 tile）", "ProceduralTexture.ensure(" in draw)
        assertTrue("必须用 Id.PLASMA", "ProceduralTexture.Id.PLASMA" in draw)
        assertTrue("噪声更新必须走生产纯函数 fbmAt", "fbmAt(" in draw)
        assertTrue("分桶必须走生产纯函数 bucketOf", "bucketOf(" in draw)
        assertTrue("椭圆单点必须走生产纯函数 ellipsePoint", "ellipsePoint(" in draw)

        // ⭐ 单层采样：`sampleFlow` 体内**不得**再出现 fbmAt（两层已在更新循环里合并）
        val sample = funBody(classBody(code, "PlasmaFlowRenderer"), "sampleFlow")
        assertTrue("能切出 sampleFlow 体", sample.isNotEmpty())
        assertFalse(
            "sampleFlow 必须只做 1 次双线性（fbm 已在更新循环里合并）",
            "fbmAt(" in sample
        )

        // 合批路径必须是预分配的 `Array(BUCKETS) { Path() }`（⛔ 不是每帧新建）
        assertTrue(
            "bucketPaths 必须是构造期预分配的 Array(BUCKETS)",
            Regex("""private val bucketPaths = Array\(BUCKETS\) \{ Path\(\) \}""").containsMatchIn(code)
        )
        // ⛔ 迁移后不得再自带 rng（基类已提供）
        assertFalse(
            "E20 不得再声明 private val rng（基类已提供）",
            "private val rng = VisualizerRandom()" in code
        )
        // ⛔ 迁移后不得覆写 final 成员
        assertFalse("不得覆写 final 的 draw", "override fun draw(" in code)
        assertFalse("不得覆写 final 的 onEnter", "override fun onEnter(" in code)
        assertFalse("不得覆写 final 的 onExit", "override fun onExit(" in code)
    }

    @Test
    fun `⑧ postFx 必须是数值字面量（门禁原版正则正负双证）`() {
        val code = codeOfE20()
        assertTrue("正向：字面量写法必须被判为已覆盖", coveredByPostFx(code))
        val named = "override val postFx = PostFx(vignette = VIGNETTE, grain = GRAIN)"
        assertFalse("负向 N3：具名常量写法必须被静默判否", coveredByPostFx(named))
    }

    // ═══════════════════════════ ⑨ 等离子 tile ═══════════════════════════

    @Test
    fun `⑨ 等离子 tile - Id 存在、ensure 里生成、逐行确定、三通道彩色`() {
        assertTrue(
            "ProceduralTexture.Id 必须有 PLASMA（§B6-② 点名要的 tile）",
            ProceduralTexture.Id.entries.any { it.name == "PLASMA" }
        )
        // ensure() 里必须真的生成它（否则 tile() 恒返回 null ⇒ 背景永远不画）
        val pt = stripComments(readFile(proceduralTextureFile()))
        assertTrue(
            "ensure() 必须调 ensureFullscreen(Id.PLASMA, …)",
            "ensureFullscreen(Id.PLASMA," in pt
        )

        // 逐行确定性 + 三通道彩色 + alpha 不超上限
        val w = 64
        val h = 8
        val r1 = IntArray(w)
        val r2 = IntArray(w)
        ProceduralTexture.plasmaRow(r1, 3, w, h)
        ProceduralTexture.plasmaRow(r2, 3, w, h)
        assertArrayEquals("同 (y,w,h) 必须逐像素确定", r1, r2)
        var colored = 0
        for (i in 0 until w) {
            val a = (r1[i] ushr 24) and 0xFF
            val r = (r1[i] shr 16) and 0xFF
            val g = (r1[i] shr 8) and 0xFF
            val b = r1[i] and 0xFF
            assertTrue("alpha 必须 ≤ 上限 178（实际 $a）", a <= 178)
            if (r != g || g != b) colored++
        }
        assertTrue("必须有三通道彩色像素（否则是灰阶 tile）实际 $colored/$w", colored > w / 2)
        // 不同行必须不同（否则整屏是一条水平条纹）
        val r3 = IntArray(w)
        ProceduralTexture.plasmaRow(r3, 6, w, h)
        assertFalse("不同 y 必须不同", r1.contentEquals(r3))
    }

    // ═══════════ 负向 N2 / N4：源码判据必须能抓到旧写法 ═══════════

    @Test
    fun `负向N2 旧「每粒子 drawCircle」必须被判据抓到（且不误伤 drawPath）`() {
        assertTrue(
            "负向 N2：旧实现必须被 drawCircle 判据抓到",
            hasPerParticleDrawCircle(OLD_E20_SNIPPET)
        )
        assertFalse(
            "正向：新实现的 drawContent 不得出现 drawCircle",
            hasPerParticleDrawCircle(funBody(classBody(codeOfE20(), "PlasmaFlowRenderer"), "drawContent"))
        )
        assertFalse(
            "判据不得误伤 drawPath（词边界）",
            hasPerParticleDrawCircle("drawPath(bucketPaths[b], color)")
        )
    }

    @Test
    fun `负向N4 轴对齐 drawOval 必须被判据抓到（新实现为 0）`() {
        assertTrue(
            "负向 N4：轴对齐 drawOval 必须被判据抓到",
            usesDrawOval(OLD_E20_SNIPPET_OVAL)
        )
        assertFalse(
            "正向：新实现不得出现 drawOval",
            usesDrawOval(funBody(classBody(codeOfE20(), "PlasmaFlowRenderer"), "drawContent"))
        )
    }

    // ═══════════════════════════ 辅助 ═══════════════════════════

    /** 门禁原版判据（与 `FxCoverageScanTest` 逐字一致）：只认 `postFx` 里的**数值字面量** */
    private val postFxRe = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""")
    private val numRe = Regex("""=\s*([0-9]*\.?[0-9]+)f""")

    private fun coveredByPostFx(code: String): Boolean {
        val m = postFxRe.find(code) ?: return false
        val nums = numRe.findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
        return nums.any { it.toFloat() > 0f }
    }

    private fun hasPerParticleDrawCircle(code: String): Boolean =
        Regex("""(?<![A-Za-z0-9_])drawCircle\(""").containsMatchIn(code)

    private fun usesDrawOval(code: String): Boolean =
        Regex("""(?<![A-Za-z0-9_])drawOval\(""").containsMatchIn(code)

    /** 旧实现片段（负向样本；**只**用于证明判据能抓到它） */
    private val OLD_E20_SNIPPET = """
        drawCircle(
            color = VisualizerMath.hsl(60f + (flow * 135f + 75f) % 135f, 1.0f, 0.68f),
            radius = 3f + life[i] * 5f,
            center = Offset(xs[i] * w, ys[i] * h),
            alpha = life[i] * 0.8f,
            blendMode = BlendMode.Plus
        )
    """.trimIndent()

    private val OLD_E20_SNIPPET_OVAL = """
        drawOval(
            color = VisualizerMath.hsl(60f, 1.0f, 0.68f),
            topLeft = Offset(x, y),
            size = Size(r * 1.8f, r),
            alpha = 0.8f
        )
    """.trimIndent()

    /** 切出 `class <name>` 的类体（到下一个顶层 class / 文件尾） */
    private fun classBody(txt: String, name: String): String {
        val m = Regex("""(?m)^\s*(?:(?:internal|open|abstract|private)\s+)*class\s+$name\b""").find(txt)
            ?: return ""
        val rest = txt.substring(m.range.last + 1)
        val nxt = Regex("""(?m)^\s*(?:(?:internal|open|abstract|private)\s+)*class\s+\w+""").find(rest)
        return if (nxt == null) txt.substring(m.range.first) else txt.substring(m.range.first, m.range.last + 1 + nxt.range.first)
    }

    /**
     * 从**已剥注释**的类体里取某个函数的 `{...}` 体。
     *
     * ⛔ 必须切函数体：成员初始化（`private val bucketPaths = Array(BUCKETS) { Path() }`）
     * 是**构造期一次**，合规；判据作用在整个类体上会对它假 FAIL。
     * 签名要认 receiver（`override fun DrawScope.drawContent(`）。
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
     * ⛔ 只去行注释不够 —— 判据会命中 KDoc 正文里举的写法（本仓库已踩 5 次）。
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

    /** `UltraRenderers.kt` 的**已剥注释**全文 */
    private fun codeOfE20(): String = stripComments(readFile(renderersFile("UltraRenderers.kt")))

    private fun readFile(f: File): String = f.readText()

    private fun renderersFile(name: String): File =
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/$name")

    private fun proceduralTextureFile(): File =
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/fx/ProceduralTexture.kt")

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

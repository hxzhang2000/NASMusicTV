package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E29 轨道（`ORBITAL_RINGS`）**背景星野「视觉层级」不变式**门禁（§八 G14 · T5.1）。
 *
 * 背景装饰元素的四条不变式（§四 G18）：
 * 1. **尺寸层级**：`背景元素最大半径 < 场景最小实体半径`（E29 的最小实体 = 火卫一 Phobos）；
 * 2. **分布形态**：幂律（`u³`）而非均匀 —— 均匀分布会让"所有元素都一样大"；
 * 3. **位置**：不得进入主体区域（内太阳系不出现前景亮星 ⇒ 中心静默区）；
 * 4. **亮度与尺寸同源**：大元素更亮、小元素更暗（不要"小而亮"的孤立亮点 —— 那最抢眼）。
 *
 * - **数值段（①–⑤）**：在 `fillStars` 生成的 220 颗星数组上验算；
 * - **行为段（⑥）**：直调生产纯函数 `quietAlpha`；
 * - **源码段（⑦⑧）**：扫 `drawStars` / `onEnter` / `ensureLayout` 的**已剥注释**正文。
 *
 * ⛔ 5 条负向自证必须与正向**喂同一份谓词** —— 缺一条就可能空转（§八 开头规矩）。
 * ⛔ 源码判据一律**先剥注释**（本仓库已踩 5+ 次：判据命中自己刚写的 KDoc ⇒ 假 FAIL），
 *   并加「原文含 / 剥后不含」的成对自证。
 * ⛔ 行为 / 数值段**直调生产 API**（`fillStars` / `quietAlpha` / 生产常量），**不复制算法**。
 * ⛔ **不构造 `OrbitalRingsRenderer()`** —— 字段初始化会建 Compose `Path()`
 *   （`android.graphics.Path`）⇒ JVM 单测抛 "not mocked"。最小实体半径改为**扫源码**取得，
 *   行星表一改判据跟着变 ⇒ 同样不漂移。
 */
class OrbitalStarFieldTest {

    /** 逐帧绘制函数（⑦ 的扫描面）。⚠️ 只含每帧函数：`ensureLayout` 建 `Stroke` 是合法的一次性成本 */
    private val drawFns = listOf("drawStars")

    // ═══════════════════════ ① 尺寸层级 ═══════════════════════

    @Test
    fun `① 尺寸层级 - 最大星必须小于场景最小实体（Phobos）`() {
        val (x, y, r, a) = stars()
        assertTrue("星野必须真的生成了 220 颗（空转自证）", r.size == OrbitalRingsRenderer.STAR_MAX)
        assertTrue("位置 / 亮度数组尺寸必须一致", x.size == r.size && y.size == r.size && a.size == r.size)

        val cap = rCap()
        val (name, minEntity) = minEntityRadius()
        assertEquals("场景最小实体必须是火卫一 Phobos（世界半径 0.0024f）", "Phobos", name)
        assertEquals("Phobos 半径", 0.0024f, minEntity, 1e-6f)
        assertEquals("星点半径上限 = STAR_R_MIN + STAR_R_SPAN", 0.00150f, cap, 1e-7f)

        assertTrue(
            "⛔ 尺寸层级：最大星 $cap 必须 < 最小实体 $minEntity（改造前 0.0030 越界 125%）",
            sizeHierarchyOk(r, cap, minEntity)
        )
        // ⛔ 改造前的越界值必须被判失败（同一份谓词）
        assertFalse(
            "负向自证：上限 0.0030f（改造前）必须越界",
            sizeHierarchyOk(r, 0.0030f, minEntity)
        )
    }

    // ═══════════════════════ ② 幂律性 ═══════════════════════

    @Test
    fun `② 幂律性 - 中位不高于上限 40%、90 分位不低于上限 70%（非均匀分布）`() {
        val (_, _, r, _) = stars()
        val cap = rCap()
        assertTrue("必须满足幂律双条件（大量暗星 + 仍有少数亮星）", powerLawOk(r, cap))
        val sorted = r.sorted()
        val med = sorted[sorted.size / 2]
        val p90 = sorted[sorted.size * 9 / 10]
        assertTrue("中位 $med 必须 ≤ 上限的 40%（${cap * 0.40f}）", med <= cap * 0.40f)
        assertTrue("90 分位 $p90 必须 ≥ 上限的 70%（${cap * 0.70f}）", p90 >= cap * 0.70f)
        assertTrue("最大星必须真的达到上限附近（否则幂律被压平）", sorted.last() >= cap * 0.95f)
    }

    // ═══════════════════════ ③ 亮度上界 ═══════════════════════

    @Test
    fun `③ 亮度上界 - alpha 不高于 0_50 且中位不高于 0_30`() {
        val (_, _, _, a) = stars()
        val cap = aCap()
        assertEquals("alpha 上限 = STAR_A_MIN + STAR_A_SPAN", 0.50f, cap, 1e-7f)
        assertTrue("必须满足亮度上界双条件", alphaBoundOk(a, cap))
        assertTrue("alpha 必须 ≥ 下限（⛔ 不得低于 0.08 ⇒ 远景一片纯黑，§九 R19）",
            a.min() >= OrbitalRingsRenderer.STAR_A_MIN - 1e-6f)
    }

    // ═══════════════════════ ④ 尺寸 / 亮度同源 ═══════════════════════

    @Test
    fun `④ 尺寸亮度同源 - 秩相关大于 0_5（无小而亮的孤立亮点）`() {
        val (_, _, r, a) = stars()
        val rho = spearman(r, a)
        assertTrue("秩相关 $rho 必须 > 0.5（大星更亮、小星更暗）", sameOriginOk(r, a, 0.5))
        // ⛔ 若两者独立均匀采样，秩相关会退化到 ≈ 0 ⇒ 出现"小而亮"的孤立亮点
        assertTrue("秩相关必须显著高于独立采样的水平", rho > 0.5)
    }

    // ═══════════════════════ ⑤ 位置合法性 ═══════════════════════

    @Test
    fun `⑤ 位置合法性 - starX 与 starY 全在 0~1`() {
        val (x, y, _, _) = stars()
        assertTrue("starX / starY 必须按 w / h 归一化在 [0, 1]", positionsOk(x, y))
        // ⛔ 位置生成方式不得改（w/h 归一化、不参与 TILT、固定种子不重掷 ⇒ 零闪烁）
        assertFalse("负向自证：越界值必须被判失败", positionsOk(x, y.copyOf().also { it[0] = 1.5f }))
    }

    // ═══════════════════════ ⑥ 中心静默（行为段） ═══════════════════════

    @Test
    fun `⑥ 中心静默 - quietAlpha 端点正确且单调不减`() {
        val floor = OrbitalRingsRenderer.QUIET_FLOOR
        assertEquals("QUIET_FLOOR 必须与 §13.5-D5 一致", 0.25f, floor, 1e-7f)
        assertTrue("必须满足静默区契约（端点 + 单调）", quietContractOk({ e -> OrbitalRingsRenderer.quietAlpha(e) }, floor))
        assertEquals("中心 e = 0 必须恰好压到 QUIET_FLOOR", floor, OrbitalRingsRenderer.quietAlpha(0f), 1e-7f)
        assertEquals("边界 e = 1 必须恰好恢复 1", 1f, OrbitalRingsRenderer.quietAlpha(1f), 1e-7f)
        assertEquals("区外 e = 2 必须恒 1（不得继续被压暗）", 1f, OrbitalRingsRenderer.quietAlpha(2f), 1e-7f)
        // 静默区半径 = 世界单位 0.15，必须落在金星轨道（0.134）与地球轨道（0.176）之间
        assertEquals("QUIET_R 必须与 §13.5-D5 一致", 0.15f, OrbitalRingsRenderer.QUIET_R, 1e-7f)
    }

    // ═══════════════════════ ⑦ 零分配自证 ═══════════════════════

    @Test
    fun `⑦ 零分配自证 - drawStars 内无 Rect Path Brush radialGradient`() {
        val body = classBody(codeOfBatchTwo(), "OrbitalRingsRenderer")
        assertTrue("类体必须能切出来（空转自证）", body.isNotEmpty())
        val starsBody = funBody(body, "drawStars")
        assertTrue("drawStars 必须能切出来（空转自证）", starsBody.isNotEmpty())
        for (fn in drawFns) {
            val src = funBody(body, fn)
            assertTrue("$fn 必须能切出来（空转自证）", src.isNotEmpty())
            assertTrue("⛔ $fn 内不得出现堆分配原语", noHeavyAllocOk(src))
        }
        // 成对自证：同一份谓词喂「含这些写法」的片段必须判失败
        assertFalse("负向自证：含 Brush.radialGradient 的片段必须被判失败", noHeavyAllocOk(HEAVY_ALLOC_SNIPPET))
        assertFalse("负向自证：含 Rect( 的片段必须被判失败", noHeavyAllocOk("val r = Rect(0f, 0f, 1f, 1f)"))
        assertFalse("负向自证：含 Path() 的片段必须被判失败", noHeavyAllocOk("val p = Path()"))
    }

    // ═══════════════════════ ⑧ 生产接线（源码段） ═══════════════════════

    @Test
    fun `⑧ 生产接线 - onEnter 调 fillStars、ensureLayout 缓存静默倒数、数量与 draw 数不变`() {
        val code = codeOfBatchTwo()
        val body = classBody(code, "OrbitalRingsRenderer")
        assertTrue("类体必须能切出来（空转自证）", body.isNotEmpty())
        val onEnter = funBody(body, "onEnter")
        val layout = funBody(body, "ensureLayout")
        val starsBody = funBody(body, "drawStars")
        assertTrue("onEnter 必须能切出来", onEnter.isNotEmpty())
        assertTrue("ensureLayout 必须能切出来", layout.isNotEmpty())
        assertTrue("drawStars 必须能切出来", starsBody.isNotEmpty())

        assertTrue(
            "onEnter 必须调生产 fillStars（⛔ 不许内联复制一份生成逻辑）",
            onEnter.contains("fillStars(starX, starY, starR, starA)")
        )
        assertFalse("⛔ onEnter 不得再内联 LCG 常量", onEnter.contains("1664525u"))
        assertFalse("⛔ 旧均匀分布写法必须消失（半径）", onEnter.contains("starR[s] = 0.0010f +"))
        assertFalse("⛔ 旧均匀分布写法必须消失（亮度）", onEnter.contains("starA[s] = 0.25f +"))

        assertTrue("ensureLayout 必须缓存 quietInvX", layout.contains("quietInvX = 1f / (s * QUIET_R)"))
        assertTrue("ensureLayout 必须缓存 quietInvY（含 TILT）", layout.contains("quietInvY = 1f / (s * QUIET_R * TILT)"))
        assertTrue("drawStars 必须走生产 quietAlpha", starsBody.contains("quietAlpha("))
        assertFalse("⛔ drawStars 不得出现 sqrt（椭圆归一化不许开方）", starsBody.contains("sqrt"))
        assertEquals(
            "drawStars 每星恰好 1 次 drawCircle（⛔ 不新增 draw 调用）",
            1, Regex("""(?<![A-Za-z0-9_])drawCircle\(""").findAll(starsBody).count()
        )

        // ⛔ 数量不是主要矛盾（反事实 ④）：STAR_MAX 与档位星数不得改
        assertTrue("STAR_MAX 必须仍是 220", code.contains("const val STAR_MAX = 220"))
        assertTrue(
            "档位星数必须仍是 70 / 140 / STAR_MAX",
            body.contains("0 -> 70") && body.contains("1 -> 140") && body.contains("else -> STAR_MAX")
        )
        assertFalse("⛔ 不得引入 fx/ 依赖（P0 是纯算术，零新增分配 / 零新增 draw）", code.contains("com.nasmusic.tv.visualizer.fx"))
        // ⛔ 守卫注释：本轮最重要的一条 —— 否则后来者会把"所有星都一样大"当成 bug 修回均匀分布
        assertTrue(
            "KDoc 必须写明「不得改回均匀分布」（§15.7 第 19 条）",
            codeOfBatchTwoRaw().contains("不得改回均匀分布")
        )
    }

    // ═══════════════════════ 负向自证（5 条） ═══════════════════════

    @Test
    fun `负向① 半径改回均匀分布 ⇒ 尺寸层级与幂律必须判失败`() {
        // 复刻改造前：r = 0.0010 + u × 0.0020（均匀），STAR_MAX / 位置生成方式不变
        val (_, _, r, _) = stars(rMin = 0.0010f, rSpan = 0.0020f, sizePow = 1)
        val oldCap = 0.0010f + 0.0020f
        val (_, minEntity) = minEntityRadius()
        assertFalse(
            "⛔ 均匀分布下上限 0.0030 必须越界（> Phobos $minEntity）",
            sizeHierarchyOk(r, oldCap, minEntity)
        )
        assertFalse(
            "⛔ 均匀分布的中位数 0.0020 必须 > 上限的 40%（${oldCap * 0.40f}）",
            powerLawOk(r, oldCap)
        )
    }

    @Test
    fun `负向② alpha 回到 0_25 到 0_65 ⇒ 亮度上界必须判失败`() {
        val (_, _, _, a) = stars(aMin = 0.25f, aSpan = 0.65f)
        val oldCap = 0.25f + 0.65f
        assertFalse(
            "⛔ 旧 alpha 上限 0.90 必须被判失败（设计值 0.50）",
            alphaBoundOk(a, oldCap)
        )
        assertFalse(
            "⛔ 旧 alpha 中位 0.45 必须 > 0.30",
            alphaBoundOk(a, 0.50f)
        )
    }

    @Test
    fun `负向③ alpha 改独立均匀采样 ⇒ 尺寸亮度同源必须判失败`() {
        // 与正向同一套半径参数，只把 alpha 改成与半径无关的独立采样
        val (_, _, r, a) = stars(alphaShared = false)
        val rho = spearman(r, a)
        assertFalse(
            "⛔ 独立采样下秩相关 $rho 必须退化到 ≤ 0.5（出现「小而亮」的孤立亮点）",
            sameOriginOk(r, a, 0.5)
        )
    }

    @Test
    fun `负向④ 去掉 quietAlpha 截断 ⇒ 静默区契约必须判失败`() {
        val floor = OrbitalRingsRenderer.QUIET_FLOOR
        // 复刻"删掉 `if (e >= 1f) 1f`"：区外继续线性增长（e = 2 时 1.75 > 1）
        val noClamp = { e: Float -> floor + (1f - floor) * e }
        assertFalse(
            "⛔ 无截断版本必须被判失败（静默区外也被改亮度）",
            quietContractOk(noClamp, floor)
        )
        assertTrue(
            "对照：无截断版本在 e = 2 处确实 ≠ 1（说明该负向不是空转）",
            noClamp(2f) != 1f
        )
    }

    @Test
    fun `负向⑤ 逐星 Brush_radialGradient 做柔光 ⇒ 零分配自证必须判失败`() {
        // P1（可选柔光）若逐星新建 Brush 就违反 §四 G15 —— 必须走**预建缓存**
        assertFalse(
            "⛔ 逐星 Brush.radialGradient 必须被判失败",
            noHeavyAllocOk(HEAVY_ALLOC_SNIPPET)
        )
        assertTrue(
            "对照：该片段确实含 `Brush.`（说明该负向不是空转）",
            HEAVY_ALLOC_SNIPPET.contains("Brush.radialGradient(")
        )
    }

    // ═══════════════════════ 谓词（正向 / 负向**同一份**） ═══════════════════════

    /** ① 尺寸层级：`max ≤ 上限` **且** `上限 < 最小实体` */
    private fun sizeHierarchyOk(starR: FloatArray, cap: Float, minEntity: Float): Boolean =
        starR.max() <= cap && cap < minEntity

    /** ② 幂律性：中位 ≤ 上限 × 0.40（均匀分布是 0.50 ⇒ 必挂）且 90 分位 ≥ 上限 × 0.70 */
    private fun powerLawOk(starR: FloatArray, cap: Float): Boolean {
        val sorted = starR.sorted()
        val med = sorted[sorted.size / 2]
        val p90 = sorted[sorted.size * 9 / 10]
        return med <= cap * 0.40f && p90 >= cap * 0.70f
    }

    /** ③ 亮度上界：`max ≤ 上限` 且中位 ≤ 0.30 */
    private fun alphaBoundOk(starA: FloatArray, cap: Float): Boolean {
        val sorted = starA.sorted()
        return starA.max() <= cap && sorted[sorted.size / 2] <= 0.30f
    }

    /** ④ 尺寸 / 亮度同源：Spearman 秩相关 > [minRho] */
    private fun sameOriginOk(starR: FloatArray, starA: FloatArray, minRho: Double): Boolean =
        spearman(starR, starA) > minRho

    /** ⑤ 位置合法性：全部落在 [0, 1] */
    private fun positionsOk(starX: FloatArray, starY: FloatArray): Boolean =
        starX.all { it >= 0f && it <= 1f } && starY.all { it >= 0f && it <= 1f }

    /** ⑥ 静默区契约：`f(0) == floor`、`f(e ≥ 1) == 1`、且 `[0, 3]` 上单调不减 */
    private fun quietContractOk(f: (Float) -> Float, floor: Float): Boolean {
        if (f(0f) != floor) return false
        if (f(1f) != 1f || f(2f) != 1f) return false
        var prev = f(0f)
        var e = 0f
        while (e <= 3f) {
            val cur = f(e)
            if (cur < prev) return false
            prev = cur
            e += 0.01f
        }
        return true
    }

    /** ⑦ 零分配：不出现 `Rect(` / `Path(` / `Brush.` / `radialGradient`（词边界，⛔ 不误伤 `drawRect(`） */
    private fun noHeavyAllocOk(src: String): Boolean =
        !Regex("""(?<![A-Za-z0-9_])Rect\s*\(""").containsMatchIn(src) &&
            !Regex("""(?<![A-Za-z0-9_])Path\s*\(""").containsMatchIn(src) &&
            !src.contains("Brush.") &&
            !src.contains("radialGradient")

    // ═══════════════════════ 辅助 ═══════════════════════

    /** `fillStars` 生成的星野四数组（直调生产 API；形参只为负向自证提供对照） */
    private fun stars(
        rMin: Float = OrbitalRingsRenderer.STAR_R_MIN,
        rSpan: Float = OrbitalRingsRenderer.STAR_R_SPAN,
        aMin: Float = OrbitalRingsRenderer.STAR_A_MIN,
        aSpan: Float = OrbitalRingsRenderer.STAR_A_SPAN,
        sizePow: Int = 3,
        alphaShared: Boolean = true,
    ): StarSet {
        val n = OrbitalRingsRenderer.STAR_MAX
        val x = FloatArray(n)
        val y = FloatArray(n)
        val r = FloatArray(n)
        val a = FloatArray(n)
        OrbitalRingsRenderer.fillStars(x, y, r, a, rMin, rSpan, aMin, aSpan, sizePow, alphaShared)
        return StarSet(x, y, r, a)
    }

    /**
     * 星野四数组（普通类 + 手工 `componentN` ⇒ 可解构，且⛔ 不用 `data class`
     * —— 它会对 `FloatArray` 生成恒假的 `equals` / `hashCode`，反而误导）。
     */
    private class StarSet(
        val x: FloatArray, val y: FloatArray, val r: FloatArray, val a: FloatArray,
    ) {
        operator fun component1(): FloatArray = x
        operator fun component2(): FloatArray = y
        operator fun component3(): FloatArray = r
        operator fun component4(): FloatArray = a
    }

    private fun rCap(): Float = OrbitalRingsRenderer.STAR_R_MIN + OrbitalRingsRenderer.STAR_R_SPAN

    private fun aCap(): Float = OrbitalRingsRenderer.STAR_A_MIN + OrbitalRingsRenderer.STAR_A_SPAN

    /**
     * 扫 `buildSystem()` 源码解析全部**实体半径**（8 行星 + 11 卫星），取最小值。
     *
     * ⚠️ 为什么扫源码而不构造渲染器：`OrbitalRingsRenderer()` 的字段初始化会建
     * Compose `Path()`（→ `android.graphics.Path`）⇒ JVM 单测抛 "not mocked"。
     * 扫源码同时保证「行星表一改，判据跟着变」⇒ 不漂移。
     */
    private fun minEntityRadius(): Pair<String, Float> {
        val body = funBody(classBody(codeOfBatchTwo(), "OrbitalRingsRenderer"), "buildSystem")
        assertTrue("buildSystem 必须能切出来（空转自证）", body.isNotEmpty())
        val re = Regex("""(?:Planet|Moon)\("(\w+)",\s*Color\(0x[0-9A-Fa-f]+\.toInt\(\)\),\s*([0-9.]+)f,""")
        val all = re.findAll(body).map { it.groupValues[1] to it.groupValues[2].toFloat() }.toList()
        assertEquals("实体表必须是 8 行星 + 11 卫星 = 19 项（空转自证）", 19, all.size)
        return all.minByOrNull { it.second }!!
    }

    /**
     * 秩相关系数（Spearman ρ = 对秩做 Pearson）。
     *
     * ⚠️ **不是**「各自排序后直接相关」—— 那是顺序统计量，恒接近 1，判不出同源性。
     */
    private fun spearman(a: FloatArray, b: FloatArray): Double {
        val ra = ranksOf(a)
        val rb = ranksOf(b)
        val n = ra.size
        val ma = ra.average()
        val mb = rb.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        var i = 0
        while (i < n) {
            val x = ra[i] - ma
            val y = rb[i] - mb
            num += x * y
            da += x * x
            db += y * y
            i++
        }
        return num / (Math.sqrt(da) * Math.sqrt(db))
    }

    /** 平均秩（并列取平均） */
    private fun ranksOf(v: FloatArray): DoubleArray {
        val n = v.size
        val idx = (0 until n).sortedBy { v[it] }
        val r = DoubleArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && v[idx[j + 1]] == v[idx[i]]) j++
            val avg = (i + j) / 2.0 + 1.0
            var k = i
            while (k <= j) {
                r[idx[k]] = avg
                k++
            }
            i = j + 1
        }
        return r
    }

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
     * ⛔ 只去行注释不够 —— 判据会命中 KDoc 正文里举的写法（本仓库已踩 5+ 次）。
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

    /** `BatchTwoRenderers.kt` 的**已剥注释**全文 */
    private fun codeOfBatchTwo(): String = stripComments(codeOfBatchTwoRaw())

    /** `BatchTwoRenderers.kt` 原文（含注释；守卫注释判据必须用原文） */
    private fun codeOfBatchTwoRaw(): String = readFile(renderersFile("BatchTwoRenderers.kt"))

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

    private companion object {
        /** 逐星 `Brush.radialGradient` 柔光（负向⑤ / ⑦ 的配对样本） */
        val HEAVY_ALLOC_SNIPPET = """
            drawCircle(
                brush = Brush.radialGradient(listOf(c.copy(alpha = 0.30f), Color.Transparent)),
                radius = starR[s] * scale * 1.7f,
                center = Offset(px, py)
            )
        """.trimIndent()
    }
}

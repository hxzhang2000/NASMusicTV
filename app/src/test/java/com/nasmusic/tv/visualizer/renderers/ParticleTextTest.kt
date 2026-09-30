package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.visualizer.ParticlePool
import com.nasmusic.tv.visualizer.VisualizerRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * E19 粒子文字（§B5 · T4.5）门禁。
 *
 * 四段：
 * - **常量段**：§B5 明文值 + §7.3 段落平滑参数；
 * - **行为段**：把 `sample` / `drawContent` 里的公式**逐字复刻**成纯函数后验算
 *   （按行配额 / 墨迹适配 / 椭圆内外圈 / 圆模板系数 / 半径线性）；
 * - **池行为段**：**真跑** [ParticlePool.updateAttract]（不再是纯函数模拟）
 *   —— `count` 恒定、`LIFE <= 0` 从画布边缘重生；
 * - **源码段**：每帧路径零分配、恰好 3 条合批 `Path`、逐行批读 `getPixels`、`postFx` 字面量。
 *
 * ⛔ 负向自证 4 条 —— 缺一条就可能空转（§八 开头规矩）。
 * ⛔ 源码判据一律**先剥注释**（本仓库已踩 5 次：判据命中自己刚写的 KDoc ⇒ 假 FAIL）。
 */
class ParticleTextTest {

    private val C = ParticleTextRenderer.Companion

    // ═══════════ 行为段：与生产实现逐字对应的纯函数复刻 ═══════════

    /** 生产实现（`sample` 第二遍）：`quota[k] = rc[k]*poolCap/total`，非空行 `coerceAtLeast(1)` */
    private fun quotaOf(rowCount: IntArray, poolCap: Int): IntArray {
        val total = rowCount.sum()
        return IntArray(rowCount.size) { k ->
            if (rowCount[k] <= 0) 0
            else (rowCount[k].toLong() * poolCap / total).toInt().coerceAtLeast(1)
        }
    }

    /** 生产实现（`drawContent` Q2）：`min(w*FIT_W_K/inkW, h*FIT_H_K/inkH)` */
    private fun fitScale(sizeW: Float, sizeH: Float, inkW: Float, inkH: Float): Float =
        kotlin.math.min(sizeW * C.FIT_W_K / inkW, sizeH * C.FIT_H_K / inkH)

    /** 生产实现（`drawContent` Q2）：`(size - ink*scale)*0.5 - inkMin*scale` */
    private fun fitOffset(sizeExtent: Float, inkMin: Float, inkExtent: Float, scale: Float): Float =
        (sizeExtent - inkExtent * scale) * 0.5f - inkMin * scale

    /** 生产实现（`drawContent` Q3）：以墨迹半宽/半高为轴的**椭圆归一化距离** */
    private fun norm(px: Float, py: Float, cx: Float, cy: Float, halfW: Float, halfH: Float): Float {
        val invHalfW = 1f / halfW.coerceAtLeast(1f)
        val invHalfH = 1f / halfH.coerceAtLeast(1f)
        val nx = (px - cx) * invHalfW
        val ny = (py - cy) * invHalfH
        return sqrt(nx * nx + ny * ny) * C.INV_SQRT2
    }

    /** 生产实现（`drawContent` P2）：`DOT_R_BASE + pulse * DOT_R_PULSE` */
    private fun radius(pulse: Float): Float = C.DOT_R_BASE + pulse * C.DOT_R_PULSE

    // ═══════════════════════════ ①–⑥ 常量与行为 ═══════════════════════════

    @Test
    fun `① §B5 明文常量齐备且取值正确`() {
        assertEquals(660, C.SAMPLE_W)
        assertEquals(220, C.SAMPLE_H)
        assertEquals(1, C.SAMPLE_STEP)
        assertEquals(150f, C.TEXT_SIZE_MAX, 1e-6f)
        assertEquals(20f, C.TEXT_PAD, 1e-6f)
        assertEquals(0.78f, C.FIT_W_K, 1e-6f)
        assertEquals(0.50f, C.FIT_H_K, 1e-6f)
        assertEquals(0.42f, C.INNER_K, 1e-6f)
        assertEquals(0.25f, C.INNER_WHITEN, 1e-6f)
        assertEquals(0.38f, C.OUTER_DARKEN, 1e-6f)
        assertEquals(0.10f, C.ALPHA_SHIFT, 1e-6f)
        assertEquals(0.5f, C.GLOW_PULSE, 1e-6f)
        assertEquals(0.55f, C.GLOW_ALPHA, 1e-6f)
        assertEquals(40f, C.SECTION_HUE_SPAN, 1e-6f)
        // §7.3 明文：sectionEnergy 的 attack / release **均为 0.02**
        assertEquals(0.02f, C.SECTION_ATTACK, 1e-6f)
        assertEquals(0.02f, C.SECTION_RELEASE, 1e-6f)
        assertEquals(2.4f, C.DOT_R_BASE, 1e-6f)
        assertEquals(2.6f, C.DOT_R_PULSE, 1e-6f)
        assertEquals(0.35f, C.SPAWN_LIFE_MIN, 1e-6f)
        // 采样位图仍是 3:1（旧实现 `size*3 × size`，`size = 220`）
        assertEquals("SAMPLE_W 必须 == 3 × SAMPLE_H（P3 只改读法，不改分辨率）",
            3 * C.SAMPLE_H, C.SAMPLE_W)
    }

    @Test
    fun `② 按行配额 - 非空行至少 1 点、空行 0 点、且随行像素数单调`() {
        // 模拟字形：上 / 下为空行，中间笔画密集（"只覆盖上部几行"正是被 P4 修掉的缺陷）
        val rc = intArrayOf(0, 1, 5, 50, 500, 0)
        val cap = 350
        val q = quotaOf(rc, cap)
        assertEquals("空行不得占配额", 0, q[0])
        assertEquals("空行不得占配额", 0, q[5])
        // ⭐ 关键：**每个非空行至少 1 点** —— 否则细笔画行（如横线）会被整行丢掉
        for (k in intArrayOf(1, 2, 3, 4)) {
            assertTrue("第 $k 行非空 ⇒ 配额必须 ≥ 1（实际 ${q[k]}）", q[k] >= 1)
        }
        // 单调不减：像素多的行不得少于像素少的行
        for (i in 1 until 4) {
            assertTrue("配额必须随行像素数单调不减（$i: ${q[i]} vs ${q[i + 1]}）", q[i] <= q[i + 1])
        }
        // 总量与 cap 同量级（不足部分由第二遍"填满即停"补齐）
        assertTrue("配额总和不得超过 cap（实际 ${q.sum()} / $cap）", q.sum() <= cap)
        assertTrue("配额总和必须接近 cap（实际 ${q.sum()} / $cap）", q.sum() > cap * 0.9f)
    }

    @Test
    fun `③ 墨迹适配 - 宽高双约束取小 且 缩放后墨迹居中`() {
        val sizeW = 1920f
        val sizeH = 1080f
        // 宽约束生效：长标题（很宽、不高）
        val wide = fitScale(sizeW, sizeH, 1000f, 100f)
        assertEquals("宽约束必须生效", sizeW * C.FIT_W_K / 1000f, wide, 1e-3f)
        assertTrue("宽约束生效时不得顶破高度预算", 100f * wide <= sizeH * C.FIT_H_K + 1e-3f)
        // 高约束生效：竖排 / 大字
        val tall = fitScale(sizeW, sizeH, 100f, 1000f)
        assertEquals("高约束必须生效", sizeH * C.FIT_H_K / 1000f, tall, 1e-3f)
        assertTrue("高约束生效时不得顶破宽度预算", 100f * tall <= sizeW * C.FIT_W_K + 1e-3f)
        // 居中：墨迹中心映射后必须落在画布中心（对任意 inkMin 成立）
        for (inkMin in floatArrayOf(0f, 37f, 500f)) {
            for ((inkExt, sc) in listOf(1000f to wide, 100f to tall)) {
                val off = fitOffset(sizeW, inkMin, inkExt, sc)
                val centerX = (inkMin + inkExt * 0.5f) * sc + off
                assertEquals("inkMin=$inkMin 时墨迹中心必须居中", sizeW * 0.5f, centerX, 1e-2f)
            }
        }
    }

    @Test
    fun `④ 内外圈 - 椭圆归一化距离（中心 0 / 角点 1 / 轴端点 1 根号 2）`() {
        val halfW = 500f
        val halfH = 100f
        assertEquals("中心必须是 0", 0f, norm(0f, 0f, 0f, 0f, halfW, halfH), 1e-6f)
        assertEquals("角点必须是 1（INV_SQRT2 的作用）",
            1f, norm(500f, 100f, 0f, 0f, halfW, halfH), 1e-4f)
        assertEquals("长轴端点必须是 1/√2", C.INV_SQRT2,
            norm(500f, 0f, 0f, 0f, halfW, halfH), 1e-4f)
        assertEquals("INV_SQRT2 必须 == 1/√2", 1f / sqrt(2f), C.INV_SQRT2, 1e-6f)
        // 内外圈分界：内圈半轴 = INNER_K / INV_SQRT2 ≈ 0.594 × halfW ≈ 297
        val innerAxis = C.INNER_K / C.INV_SQRT2 * halfW
        assertEquals("内圈半轴 ≈ 0.594 × halfW", 0.594f * halfW, innerAxis, 0.5f)
        assertTrue("略小于内圈半轴 ⇒ 判内圈",
            norm(innerAxis * 0.99f, 0f, 0f, 0f, halfW, halfH) <= C.INNER_K)
        assertTrue("略大于内圈半轴 ⇒ 判外圈",
            norm(innerAxis * 1.01f, 0f, 0f, 0f, halfW, halfH) > C.INNER_K)
    }

    @Test
    fun `⑤ 圆模板 - 4 段 cubicTo 控制点系数 = 4 比 3 tan(pi 比 8)`() {
        // 4/3 · tan(π/8) ≈ 0.5522847 —— 标准"四段三次贝塞尔近似圆"系数
        val expected = (4f / 3f) * kotlin.math.tan(Math.PI.toFloat() / 8f)
        assertEquals("CIRCLE_K 必须等于 4/3·tan(π/8)", expected, C.CIRCLE_K, 1e-6f)
        assertTrue("系数必须 ∈ (0.5, 0.6)（否则不是圆）", C.CIRCLE_K > 0.5f && C.CIRCLE_K < 0.6f)
    }

    @Test
    fun `⑥ 粒子半径 - 随 pulse 线性且与 §B5 原文一致`() {
        assertEquals(2.4f, radius(0f), 1e-6f)
        assertEquals(5.0f, radius(1f), 1e-6f)
        assertEquals(3.7f, radius(0.5f), 1e-6f)
        assertTrue("半径必须随 pulse 单调递增", radius(1f) > radius(0.5f) && radius(0.5f) > radius(0f))
        // 线性：中点必须等于两端均值
        assertEquals("半径必须严格线性", (radius(0f) + radius(1f)) * 0.5f, radius(0.5f), 1e-6f)
    }

    // ═══════════════════════════ ⑦ 池行为段（真跑） ═══════════════════════════

    @Test
    fun `⑦ updateAttract - count 恒定、寿命耗尽从画布边缘重生`() {
        val cap = 8
        val pool = ParticlePool(cap, VisualizerRandom())
        val targets = FloatArray(cap * 2)
        for (i in 0 until cap) {
            targets[i * 2] = 100f + i * 10f
            targets[i * 2 + 1] = 200f
            pool.spawn(x = i * 5f, y = i * 5f, vx = 0f, vy = 0f, life = 1f, hue = 190f)
        }
        assertEquals("前提：池必须已填满", cap, pool.count)

        val w = 100f
        val h = 50f
        // 把 3 号粒子的寿命压到 < LIFE_DECAY ⇒ 本帧必然触发重生
        val victim = 3
        pool.data[victim * ParticlePool.STRIDE + ParticlePool.LIFE] = 0.001f
        pool.updateAttract(targets, 0.05f, 1f, w, h)

        val vo = victim * ParticlePool.STRIDE
        val vx = pool.data[vo + ParticlePool.X]
        val vy = pool.data[vo + ParticlePool.Y]
        val onEdge = vx == 0f || vx == w || vy == 0f || vy == h
        assertTrue("寿命耗尽必须从四边之一重生（实际 x=$vx, y=$vy）", onEdge)
        assertEquals("重生必须复位寿命", 1f, pool.data[vo + ParticlePool.LIFE], 1e-6f)

        // ⭐ 长跑：count 必须恒定（旧 swap-remove 会让它一路衰减到 0）
        for (f in 0 until 2000) pool.updateAttract(targets, 0.05f, 1f, w, h)
        assertEquals("⛔ count 必须恒 == capacity（旧实现约 250 帧后归零 ⇒ 永久空白）",
            cap, pool.count)
    }

    @Test
    fun `⑧ 池常量 - STRIDE 6 与 LIFE_DECAY 0_004（≈4_2 秒生命周期）`() {
        assertEquals(6, ParticlePool.STRIDE)
        assertEquals(0.004f, ParticlePool.LIFE_DECAY, 1e-6f)
        // 1/0.004 = 250 帧；60fps ⇒ ≈ 4.17 s
        assertEquals(250f, 1f / ParticlePool.LIFE_DECAY, 1e-3f)
        assertEquals(4.17f, (1f / ParticlePool.LIFE_DECAY) / 60f, 0.01f)
    }

    // ═══════════════════════════ ⑨–⑫ 源码扫描段 ═══════════════════════════

    @Test
    fun `⑨ 源码 - 每帧路径不新建 Paint Rect（切 drawContent 体 + 词边界）`() {
        val body = codeOfE19()
        val draw = funBody(body, "drawContent")
        assertTrue("必须能定位 drawContent 体", draw.isNotEmpty())
        // ⛔ 词边界：`AndroidPaint(` 含子串 `Paint(`、`drawRect(` 含子串 `Rect(`
        assertFalse("⛔ 不得在每帧路径里新建 Paint（`AndroidPaint` 也含子串 `Paint(` ⇒ 必须词边界）",
            Regex("(?<![A-Za-z0-9_])Paint\\(").containsMatchIn(draw))
        assertFalse("⛔ 不得在每帧路径里新建 Rect（`addOval(Rect(...))` 会每帧堆分配）",
            Regex("(?<![A-Za-z0-9_])Rect\\(").containsMatchIn(draw))
        // 整个类体也不得有 Rect：证明"没有 addOval(Rect)"这条改造真的落地
        assertEquals("全类体不得出现 `Rect(`（P2 用 4 段 cubicTo 替代 addOval(Rect)）",
            0, Regex("(?<![A-Za-z0-9_])Rect\\(").findAll(body).count())
    }

    @Test
    fun `⑩ 源码 - 恰好 3 条合批 drawPath、0 次 drawCircle、0 次 getPixel`() {
        val body = codeOfE19()
        assertEquals("350 次 drawCircle → 3 次 drawPath（外圈 / 内圈 / 高亮）",
            3, Regex("(?<![A-Za-z0-9_])drawPath\\(").findAll(body).count())
        assertEquals("⛔ 不得再有 drawCircle（旧实现每帧 350 次）",
            0, Regex("(?<![A-Za-z0-9_])drawCircle\\(").findAll(body).count())
        // ⛔ 单数 getPixel（逐像素 JNI）必须绝迹；复数 getPixels（逐行批读）是 P3 要求的
        assertEquals("⛔ 不得再有逐像素 getPixel（P3 要求逐行 getPixels）",
            0, Regex("(?<![A-Za-z0-9_])getPixel\\(").findAll(body).count())
        assertTrue("必须使用逐行批读 getPixels", body.contains("getPixels("))
        // 3 条 Path 必须是**成员**（每帧 rewind 复用），不是每帧 new
        for (name in listOf("outerPath", "innerPath", "glowPath", "dotPath")) {
            assertTrue("$name 必须是成员并 rewind 复用", body.contains("$name.rewind()"))
        }
    }

    @Test
    fun `⑪ 源码 - 采样逐行批读 + 行数上界可静态解析（JNI 次数 = SAMPLE_H）`() {
        val body = codeOfE19()
        assertTrue("采样位图尺寸必须绑定常量", body.contains("val bmpH = SAMPLE_H"))
        assertTrue("SAMPLE_H 必须是 const val（成本脚本符号表只收 const val）",
            body.contains("const val SAMPLE_H = 220"))
        assertTrue("第一遍必须是 `while (y < bmpH)`（上界可解析）",
            Regex("""while\s*\(\s*y\s*<\s*bmpH""").containsMatchIn(body))
        // ⚠️ 第二遍带 `&& n < poolCap` 的「填满即停」条件 ⇒ 判据必须用正则而非字面匹配
        //    （踩过：字面 `while (y2 < bmpH)` 直接假 FAIL）
        assertTrue("第二遍必须是 `while (y2 < bmpH …)`（上界可解析）",
            Regex("""while\s*\(\s*y2\s*<\s*bmpH""").containsMatchIn(body))
        // 两遍各 1 个调用点 ⇒ JNI 次数 = 行数（≤ 2×220），而非 220×660 逐像素
        assertEquals("getPixels 恰好 2 个调用点（第一遍统计 + 第二遍抽取）",
            2, Regex("getPixels\\(").findAll(body).count())
        assertTrue("P5 采样步长必须为 1（全字形覆盖）", body.contains("const val SAMPLE_STEP = 1"))
    }

    @Test
    fun `⑫ 源码 - postFx 必须是数值字面量（门禁原版正则正负双证）`() {
        // 门禁原版正则（照抄 FxCoverageScanTest）
        val postFxRe = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""")
        val numRe = Regex("""=\s*([0-9]*\.?[0-9]+)f""")
        val body = codeOfE19()
        val m = postFxRe.find(body)
        assertTrue("E19 必须声明 postFx（否则会被判『未覆盖后处理』）", m != null)
        val nums = numRe.findAll(m!!.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals("门禁原版正则必须取到 2 个字面量", listOf("0.46", "0.030"), nums)
        assertTrue("至少一个通道 > 0 才会被判『已覆盖』", nums.any { it.toFloat() > 0f })
    }

    // ═══════════════════════════ 负向自证 ═══════════════════════════

    @Test
    fun `负向N1 旧逐像素 getPixel 必须被判据抓到（且不误伤 getPixels）`() {
        val body = codeOfE19()
        val pixelRe = Regex("(?<![A-Za-z0-9_])getPixel\\(")
        // 模拟旧写法
        val broken = "for (x in 0 until w) { val c = bmp.getPixel(x, y) }"
        assertTrue("判据必须能抓到逐像素 getPixel", pixelRe.containsMatchIn(broken))
        // 复数形式不得被误伤（否则会把合规的 P3 实现判死）
        val good = "bmp.getPixels(buf, 0, bmpW, 0, y, bmpW, 1)"
        assertFalse("⛔ getPixels 不得被 getPixel 判据误伤", pixelRe.containsMatchIn(good))
        // 生产源码已无逐像素写法
        assertEquals(0, pixelRe.findAll(body).count())
    }

    @Test
    fun `负向N2 旧 swap-remove 必须被判目标错位 / 粒子归零`() {
        // 复刻旧实现：LIFE <= 0 时 swap-remove（末位粒子填到 i，count--）
        val cap = 8
        val life = FloatArray(cap) { 1f }
        val targetIdx = IntArray(cap) { it }        // 槽位 i ↔ 目标 i
        var count = cap
        var i = 0
        // 只让 0 号粒子死（其余寿命拉高），模拟"部分先死"
        life[0] = 0f
        while (i < count) {
            life[i] -= 0.004f
            if (life[i] <= 0f) {
                val last = count - 1
                life[i] = life[last]
                targetIdx[i] = targetIdx[last]
                count--
            } else {
                i++
            }
        }
        assertTrue("旧 swap-remove 必须让 count 下降", count < cap)
        // ⛔ 错位的定义：槽位 i 上的粒子，其目标索引不再是 i
        val misaligned = (0 until count).count { targetIdx[it] != it }
        assertTrue("旧实现必然产生目标错位（实际错位 $misaligned 个）", misaligned > 0)

        // 对照：生产实现 count 恒定 ⇒ 永不错位
        val pool = ParticlePool(cap, VisualizerRandom())
        val targets = FloatArray(cap * 2)
        for (k in 0 until cap) {
            targets[k * 2] = k * 10f
            targets[k * 2 + 1] = k * 10f
            pool.spawn(k * 1f, k * 1f, 0f, 0f, 1f, 190f)
        }
        for (f in 0 until 3000) pool.updateAttract(targets, 0.05f, 1f, 100f, 50f)
        assertEquals("生产实现 count 必须恒定 ⇒ 槽位与 targets 永远对齐", cap, pool.count)
    }

    @Test
    fun `负向N3 具名常量 postFx 必须被静默判否`() {
        val postFxRe = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""")
        val numRe = Regex("""=\s*([0-9]*\.?[0-9]+)f""")
        val named = "override val postFx = PostFx(vignette = VIGNETTE, grain = GRAIN)"
        val mn = postFxRe.find(named)
        assertTrue("具名常量仍能匹配到参数表", mn != null)
        assertTrue("⛔ 具名常量必须取不到数值字面量（这正是它会被静默判否的原因）",
            numRe.findAll(mn!!.groupValues[1]).none())
    }

    @Test
    fun `负向N4 旧 350 次 drawCircle 必须被判据抓到`() {
        val circleRe = Regex("(?<![A-Za-z0-9_])drawCircle\\(")
        val broken = "for (i in 0 until 350) { drawCircle(color, radius, Offset(x, y)) }"
        assertTrue("判据必须能抓到旧 drawCircle 写法", circleRe.containsMatchIn(broken))
        assertEquals("生产源码必须已无 drawCircle", 0, circleRe.findAll(codeOfE19()).count())
        // 顺带：`drawPath` 不得被子串误伤（与 drawCircle 无关，但同属"调用点计数"家族）
        assertNotEquals("drawPath 调用点数不得为 0（否则合批改造没落地）",
            0, Regex("(?<![A-Za-z0-9_])drawPath\\(").findAll(codeOfE19()).count())
    }

    // ═══════════════════════════ 源码定位 ═══════════════════════════

    /** `ParticleRenderers.kt` 里 `ParticleTextRenderer` 类体，**已剥注释** */
    private fun codeOfE19(): String =
        stripComments(classBody(readRenderers(), "ParticleTextRenderer"))

    private fun readRenderers(): String =
        mainSourceRoot().resolve("com/nasmusic/tv/visualizer/renderers/ParticleRenderers.kt").readText()

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
     * ⛔ 为什么必须切函数体：成员初始化（`private val dotPath = Path()`）是**构造期一次**，
     * 合规；把判据作用在整个类体上会对它假 FAIL。签名要认 receiver
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
     * ⛔ 只去行注释不够 —— 判据会命中 KDoc 正文里举的写法（本仓库已踩 5 次：
     * 例如 `updateAttract` 的 KDoc 里写着"原实现是 swap-remove / 不 removeAt"）。
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

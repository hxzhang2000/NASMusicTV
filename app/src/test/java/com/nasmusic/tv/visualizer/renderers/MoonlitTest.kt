package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 门禁 **G3 `MoonlitTest`**（`docs/moonlit-visualizer-plan.md` §10.2）—— E44「明月」§六
 * 云场的**对账 + 形状 + 接线**判据。
 *
 * ## 四组判据在防什么
 * 1. **对账（①②③）**：云场的每一个数都必须能在纯 JVM 里和原型 `moonlit-preview.html` 的
 *    **原文输出**逐位对上。基准文件 [MoonlitCloudBaseline] 由
 *    `docs/archive/verification/scripts/moonlit_cloud_golden.js` **从原型抽取**生成 ——
 *    ⛔ 手抄公式等于把哨兵拆了（Kotlin 改错一行、基线跟着错得一致）。
 *    播种只有 `+ × /` 与截断 ⇒ 用 **delta 0.0 精确比**；含 `sin/pow` 的几何用相对 1e-9。
 * 2. **形状（④–⑥、⑨–⑪）**：`occl` 是平滑核而不是二值门、遮挡盒**就是**画出来的 AABB、
 *    五个消费点同源于同一个 `occl`、位移只由**增量**累加 ⇒ 这些是第五/六轮翻过的车。
 * 3. **接线（⑬）**：层序、`step` 先于 `occl`、消费点吃同一个变量、⛔ 刻意常量 `DISK_A`/`BLOOM_A`/
 *    `DARK_EDGE_A` 不得复活、裁剪只用 `clipRect`、着色器构造不得进逐帧路径。
 *    ⚠️ 光有对账挡不住"算对了但没接上"—— E43 的 583 行圆盘就是这么漏的。
 * 4. **量化（⑫，偏差 D23）**：云色走 16×4×16 分桶缓存，必须把**最坏单通道误差**钉在个位数，
 *    并配"刻度降到 8 桶就会破线"的负向自证。
 *
 * ## 防"空转"
 * 每条判据都有**负向自证**（把已知错误写法喂进同一个判据函数，断言它必须报违规）；
 * 扫描类判据一律跑在**剥掉注释**的源码上（类 KDoc 里原文引用了被禁写法）。
 */
class MoonlitTest {

    private val b = MoonlitCloudBaseline

    // ── ① 播种逐字对账 ─────────────────────────────────────────────────────────

    @Test
    fun `① 播种与原型逐字一致（delta 0）`() {
        val f = field()
        val rows = f.far.map { seedRow(it) } + f.near.map { seedRow(it) }
        val expected = (b.SEED_FAR + b.SEED_NEAR).toList()
        assertRows("播种", expected, rows, 0.0)
        // 字段数口径也不能漂：98 = 10 标量 + 11 斑 × 8
        assertEquals(98, rows[0].size)
        assertEquals(b.SEED_SCALARS + b.SEED_PUFF_FIELDS * MoonClouds.PUFF_SEED_COUNT, rows[0].size)
    }

    @Test
    fun `①b 同一 seed 重播种必须逐位复现`() {
        val first = field().let { f -> (f.far + f.near).map { seedRow(it) } }
        val second = field().let { f -> (f.far + f.near).map { seedRow(it) } }
        assertRows("重播种", first, second, 0.0)
        // ⚠️ 换个 seed 必须**真的换一场云**（否则 seed 参数是个摆设，需求 2 的复现无从谈起）
        val other = field(20261009).let { f -> (f.far + f.near).map { seedRow(it) } }
        assertTrue("seed 不生效？", abs(other[0][0] - first[0][0]) > 1e-6)
    }

    // ── ② 几何逐字对账（⛔ 必须关掉过境钉、⛔ 必须用增量累加）──────────────────

    @Test
    fun `② 125 帧后的一帧几何与原型一致`() {
        val f = referenceFrame()
        val near = f.near.map { geomRow(it, b.NP_NEAR) }
        val far = f.far.map { geomRow(it, b.NP_FAR) }
        assertRows("近层几何", b.GEOM_NEAR.toList(), near, 1e-9)
        assertRows("远层几何", b.GEOM_FAR.toList(), far, 1e-9)
        assertEquals(b.GEOM_SCALARS + b.GEOM_PUFF_FIELDS * b.NP_NEAR, near[0].size)
    }

    @Test
    fun `②b 时基由增量累加得到，与原型逐位相同`() {
        val f = referenceFrame()
        // ⛔ 不是 `tSec = 12.5`：125 次 `+= 0.1` 的浮点路径与直接赋值不同（末位差 3 ulp）
        assertEquals(b.T_SEC, f.tSec, 0.0)
        assertTrue("增量累加应留下舍入痕迹", f.tSec != 12.5)
    }

    // ── ③ occl 单一真源：只由近层算 ────────────────────────────────────────────

    @Test
    fun `③ occl 只由近层云算出`() {
        val f = referenceFrame()
        val occl = f.occlusion(b.MOON_CX, b.MOON_CY, b.MOON_R, f.near, b.N_NEAR)
        assertTrue("occl 与原型不符：期望 ${b.OCCL} 实得 $occl", close(b.OCCL, occl, 1e-9))
        // 反面对照：拿远层算会得到另一个数（原型 `:953` 只喂 clouds.near）
        assertEquals(b.OCCL_FAR_IF_MISTAKENLY_USED, f.occlusion(b.MOON_CX, b.MOON_CY, b.MOON_R, f.far, b.N_FAR), 0.0)
        // 渲染器那一头必须真的传的是 near（判据不能只测纯函数）
        val body = functionBody(sourceOf(RENDERER), "drawContent")
        assertEquals("全场只该算一次遮挡（⛔ 水面另算一次是需求 3 的反例）",
            1, occurrences(body, Regex("clouds\\.occlusion\\(")))
        val args = callArgs(body, "clouds.occlusion(")
        assertTrue("clouds.occlusion 必须吃 clouds.near：$args", args.contains("clouds.near"))
        assertTrue("⛔ 远层不许参与遮挡：$args", !args.contains("clouds.far"))
    }

    // ── ④ 遮挡核是平滑的，不是二值门 ───────────────────────────────────────────

    @Test
    fun `④ 遮挡核平滑、单调、不越界`() {
        val w = 200.0
        val h = 60.0
        val r = 162.0
        val alpha = 0.7
        // 远离 ⇒ 恰为 0
        assertEquals(0.0, MoonClouds.plumeOccl(MoonClouds.overlapD2(9000.0, 9000.0, w, h, 0.0, 0.0, r), alpha), 0.0)
        // 正中 ⇒ 恰为 alphaK（核在 d²=0 取满值，⛔ 不是"alphaK 再乘个系数"）
        assertEquals(alpha, MoonClouds.plumeOccl(MoonClouds.overlapD2(0.0, 0.0, w, h, 0.0, 0.0, r), alpha), 1e-12)
        // 包络边 d²=1 ⇒ 归零（`if (d2 < 1.0)` 的边界）
        assertEquals(0.0, MoonClouds.plumeOccl(1.0, alpha), 0.0)

        // 400 点横向扫：单调不增 + 相邻差 < 0.01（二值门会在一点上跳 alphaK ⇒ 必被此判据拒掉）
        val denom = w + r * MoonClouds.OCCL_PAD
        var prev = MoonClouds.plumeOccl(MoonClouds.overlapD2(0.0, 0.0, w, h, 0.0, 0.0, r), alpha)
        var jumps = 0
        var i = 1
        while (i <= 400) {
            val t = i / 400.0
            val now = MoonClouds.plumeOccl(MoonClouds.overlapD2(t * denom, 0.0, w, h, 0.0, 0.0, r), alpha)
            assertTrue("遮挡随距离回弹（i=$i：$prev → $now）", now <= prev + 1e-12)
            if (prev - now > 0.01) jumps++
            prev = now
            i++
        }
        assertEquals("出现台阶 ⇒ 二值门", 0, jumps)

        // 缕心落在**月盘之外**（`cx > r`）、却仍在**包络之内**（`cx < w`）⇒ 遮挡必须已经显著。
        // 这就是 OCCL_PAD 那 60% 缓冲在画面上的意义：盘沿外的云也在吃盘，⛔ 不是"进到盘里才遮"。
        val cx = r + 18.0
        assertTrue("取样点没落在盘外 ⇒ 这一条退化成 ④ 的前半段：cx=$cx r=$r", cx > r)
        assertTrue("取样点跑出了包络 ⇒ 测不到 OCCL_PAD：cx=$cx w=$w", cx < w)
        val outside = MoonClouds.plumeOccl(MoonClouds.overlapD2(cx, 0.0, w, h, 0.0, 0.0, r), alpha)
        assertTrue("包络挨上却几乎不遮：$outside", outside > 0.3)
    }

    /** 负向自证：二值门写法过不了 ④ 的判据（判据本身有判别力）。 */
    @Test
    fun `④b 负向自证 二值门会被平滑判据拒掉`() {
        // 与 ④ 同一条 400 点扫描，只是把核换成 `if (d2 < 1) alphaK else 0`
        val binaryGate = { d2: Double, alphaK: Double -> if (d2 < 1.0) alphaK else 0.0 }
        var prev = binaryGate(0.0, 0.7)
        var jumps = 0
        var i = 1
        while (i <= 400) {
            val t = i / 400.0
            val now = binaryGate(t * t, 0.7)
            if (prev - now > 0.01) jumps++
            prev = now
            i++
        }
        assertTrue("二值门没被判据抓到 ⇒ 判据空转", jumps > 0)
    }

    // ── ⑤ 遮挡盒 == 画出来的 AABB（⛔ 不许"遮了但没画"）─────────────────────────

    @Test
    fun `⑤ 缕的包络就是斑椭圆的 AABB`() {
        val f = referenceFrame()
        checkEnvelope(f.far, b.N_FAR, b.NP_FAR, "远层")
        checkEnvelope(f.near, b.N_NEAR, b.NP_NEAR, "近层")
    }

    private fun checkEnvelope(list: Array<MoonPlume>, n: Int, np: Int, tag: String) {
        var i = 0
        while (i < n) {
            val plume = list[i]
            var ex = 0.0
            var ey = 0.0
            var j = 0
            while (j < np) {
                val p = plume.puffs[j]
                assertTrue("$tag[$i] 包络不含斑 $j（x）", abs(p.px - plume.cx) + p.rx <= plume.w + 1e-9)
                assertTrue("$tag[$i] 包络不含斑 $j（y）", abs(p.py - plume.cy) + p.ry <= plume.h + 1e-9)
                ex = maxOf(ex, abs(p.px - plume.cx) + p.rx)
                ey = maxOf(ey, abs(p.py - plume.cy) + p.ry)
                j++
            }
            // 且必须**恰好**是最紧的那个 ⇒ 遮挡用的盒没有一丝外扩
            assertEquals("$tag[$i] 横向包络不是最紧 AABB", ex, plume.w, 0.0)
            assertEquals("$tag[$i] 纵向包络不是最紧 AABB", ey, plume.h, 0.0)
            i++
        }
    }

    // ── ⑥ 五个消费点：满遮到下限、朔月有地板 ──────────────────────────────────

    @Test
    fun `⑥ 满遮态五个消费点都落到设计下限`() {
        assertEquals(0.08, MoonClouds.diskA(1.0), 1e-12)
        assertEquals(0.0128, MoonClouds.bloomA(1.0), 1e-12)
        assertEquals(0.0, MoonClouds.darkEdgeA(1.0), 0.0)
        // 晕比盘**先**软：满遮时只剩基准的 25%
        val base = MoonClouds.haloBase(1.0, 0.6875)
        assertEquals(0.25, MoonClouds.haloA(1.0, 0.6875, 1.0) / base, 1e-12)
        // 粼光是 diskA² ⇒ 满遮时**结构性消失**（低于 MIN_ALPHA，一个图元都不提交）
        val glit = MoonClouds.glitA(1.0, 1.0, 0.0)
        assertTrue("glitA(满遮)=$glit 应低于 MIN_ALPHA", glit < MoonClouds.MIN_ALPHA)
        assertEquals(0.003968, glit, 1e-12)
        // 倒影还活着（⛔ 与粼光同一个 α，否则云过盘时水面会整片熄灭）
        assertEquals(0.0352, MoonClouds.reflA(1.0, 1.0), 1e-12)
        // 朔月地板：f=0 时两者都靠 clamp 下界留一条极淡的路
        assertEquals(0.0264, MoonClouds.reflA(0.0, 0.0), 1e-12)
        assertEquals(0.0248, MoonClouds.glitA(0.0, 0.0, 0.0), 1e-12)
    }

    @Test
    fun `⑥b 水面与盘面同源（同乘 diskA，粼光再平方）`() {
        val fs = doubleArrayOf(0.0, 0.35, 1.0)
        val os = doubleArrayOf(0.0, 0.2, 0.53, 0.8, 1.0)
        fs.forEach { f ->
            os.forEach { o ->
                val ratio = MoonClouds.reflA(f, o) / MoonClouds.reflA(f, 0.0)
                assertTrue("倒影比值 ≠ diskA($o)：$ratio", abs(ratio - MoonClouds.diskA(o)) < 1e-12)
                val d = MoonClouds.diskA(o)
                val gr = MoonClouds.glitA(f, o, 0.0) / MoonClouds.glitA(f, 0.0, 0.0)
                assertTrue("粼光比值 ≠ diskA²：$gr", abs(gr - d * d) < 1e-12)
            }
        }
    }

    // ── ⑦ occl **分布**对账（偏差 D24 修订：⛔ 不是"自然场遮不满"，也不是手调上限）──

    /**
     * `MoonClouds.OCCL_NATURAL_MAX` 已删：它记的 0.53 出自一个**坏探针**（`minDim` 被局部
     * `const` 遮蔽 ⇒ `gw/gh = 0` ⇒ 缕包络只剩 `OCCL_PAD·moonR` 一小圈），按生产几何重扫，
     * 自然场 `occl` 的**均值是 0.306（探针量成 0.026，低 12 倍）、峰值顶到 clamp 的 1**。
     *
     * ⇒ 判据从"峰值 ≤ 常量"升级为**五项分布逐条对账**（`peak/mean/hot/top/over`，
     * 口径住在 [MoonlitCloudBaseline]，由 `moonlit_cloud_golden.js` 从原型原文跑出）：
     * 只比峰值会被"恰好同顶但分布完全不同"骗过，而均值与三个占比一起就把几何、亮度、
     * 速度三条漂移路径全钉死了。§6.6 的主张也换了措辞：过境提供的不是"能不能吞月"，
     * 而是**触顶时长**（实测 10% vs 自然 1.7%）。
     */
    @Test
    fun `⑦ occl 分布逐项对账，过境态的触顶时长显著更长`() {
        val nat = occlScan(transit = false)
        assertEquals("自然态峰值", b.NAT_PEAK, nat.peak, 1e-12)
        assertEquals("自然态均值", b.NAT_MEAN, nat.mean, 1e-9)
        assertEquals("自然态 occl>0.5 占比", b.NAT_HOT, nat.hot, 1e-12)
        assertEquals("自然态 occl≥0.999 占比", b.NAT_TOP, nat.top, 1e-12)
        assertEquals("自然态 occl>0.001 占比", b.NAT_OVER, nat.over, 1e-12)

        val tra = occlScan(transit = true)
        assertEquals("过境态峰值", b.TRA_PEAK, tra.peak, 1e-12)
        assertEquals("过境态均值", b.TRA_MEAN, tra.mean, 1e-9)
        assertEquals("过境态 occl>0.5 占比", b.TRA_HOT, tra.hot, 1e-12)
        assertEquals("过境态 occl≥0.999 占比", b.TRA_TOP, tra.top, 1e-12)
        assertEquals("过境态 occl>0.001 占比", b.TRA_OVER, tra.over, 1e-12)

        // ⭐ 棘轮（不是"上限"）：自然场**就是**能遮满 —— 谁把云收薄了，这条会红，
        //    红了对策是"重跑 golden 脚本 + 改文档"，⛔ 不是把断言改成 0.5。
        assertEquals("自然场应能走到 clamp 的满遮态", 1.0, nat.peak, 1e-12)
        // §6.6 的戏剧性 = 时长：触顶占比至少差 5 倍（实测 0.0998 / 0.0171 = 5.8）
        assertTrue("过境态触顶占比 ${tra.top} 没显著高于自然态 ${nat.top} ⇒ §6.6 的钉带位失效了",
            tra.top >= 5.0 * nat.top)
        assertTrue("过境态均值 ${tra.mean} 没高于自然态 ${nat.mean}", tra.mean > nat.mean)
    }

    /** 负向自证：把**远层**也喂进遮挡（§6.2 明令禁止的接法）⇒ 触顶时长必须显著变长。 */
    @Test
    fun `⑦b 负向自证 远层混进 occl 会让盘更容易被遮满`() {
        val near = occlScan(transit = false)
        val withFar = occlScan(transit = false, alsoFar = true)
        assertTrue("判据抓不到「多算一层」：near.top=${near.top} withFar.top=${withFar.top}",
            withFar.top > near.top)
        assertTrue("均值也应上升：${near.mean} → ${withFar.mean}", withFar.mean > near.mean)
    }

    /**
     * [MoonlitCloudBaseline] 的 `NAT_*` / `TRA_*` 同款扫描：`dt=0.1`、`spdMul=1`、
     * [MoonlitCloudBaseline.SCAN_FRAMES] 帧、§3.1 定稿构图。⛔ **不加过境钉**就是自然态。
     *
     * @param alsoFar 负向自证用：把远层也累进遮挡（生产里 ⛔ 只喂近层，原型 `:953`）。
     */
    private fun occlScan(transit: Boolean, alsoFar: Boolean = false, frames: Int = b.SCAN_FRAMES): OcclScan {
        val f = field()
        var peak = 0.0
        var sum = 0.0
        var hot = 0
        var top = 0
        var over = 0
        var k = 0
        while (k < frames) {
            f.step(0.1, SPD_NEUTRAL, b.W, b.HORIZON_Y, b.MIN_DIM,
                b.N_FAR, b.NP_FAR, b.N_NEAR, b.NP_NEAR,
                if (transit) MoonClouds.TRANSIT_INDEX else MoonClouds.TRANSIT_OFF)
            var o = f.occlusion(b.MOON_CX, b.MOON_CY, b.MOON_R, f.near, b.N_NEAR)
            if (alsoFar) {
                o = minOf(1.0, o + f.occlusion(b.MOON_CX, b.MOON_CY, b.MOON_R, f.far, b.N_FAR))
            }
            if (o > peak) peak = o
            sum += o
            if (o > 0.5) hot++
            if (o >= 0.999) top++
            if (o > 0.001) over++
            k++
        }
        // ⚠️ 三个占比必须转 Double 再除（Int/Int 会整除 ⇒ 全成 0 ⇒ 对账"假绿"）
        return OcclScan(peak, sum / frames, hot.toDouble() / frames, top.toDouble() / frames,
            over.toDouble() / frames)
    }

    /** 一次 `occl` 分布扫描的五项结果。 */
    private class OcclScan(val peak: Double, val mean: Double, val hot: Double, val top: Double, val over: Double)

    // ── ⑧ 切档只截断读取，⛔ 绝不重播种 ────────────────────────────────────────

    @Test
    fun `⑧ 切档不重播种，只改读取上界`() {
        assertEquals(MoonClouds.PUFF_NEAR_HIGH, MoonClouds.PUFF_SEED_COUNT)
        // §6.1 数量表：缕 远 `2/3/3` · 近 `2/3/4`（⚠️ 两个 LOW 都是 2，别把近层记成 3）
        assertEquals(2, MoonClouds.plumes(MoonLevel.LOW, false))
        assertEquals(2, MoonClouds.plumes(MoonLevel.LOW, true))
        assertEquals(3, MoonClouds.plumes(MoonLevel.MEDIUM, false))
        assertEquals(3, MoonClouds.plumes(MoonLevel.MEDIUM, true))
        assertEquals(3, MoonClouds.plumes(MoonLevel.HIGH, false))
        assertEquals(4, MoonClouds.plumes(MoonLevel.HIGH, true))
        assertEquals(4, MoonClouds.puffs(MoonLevel.LOW, false))
        assertEquals(10, MoonClouds.puffs(MoonLevel.MEDIUM, true))
        assertEquals(11, MoonClouds.puffs(MoonLevel.HIGH, true))

        val f = field()
        // ⚠️ 比的是**去掉 `x` 的播种行**：`x` 是 98 个播种字段里唯一随时间推进的那个
        //    （`stepList` 每帧 `x = wrap01(x + speedK·spdMul·dt)`），把它算进"切档后逐位不变"
        //    等于要求云不许飘。其余 97 个字段一旦被改写就说明切档在重播种。
        val seededBefore = (f.far + f.near).map { seedRowStatic(it) }
        val xBefore = (f.far + f.near).map { it.x }
        // HIGH 跑 60 帧 ⇒ 11 斑全部被写过
        step(f, 60, MoonClouds.PUFF_NEAR_HIGH, MoonClouds.PUFF_FAR_HIGH, MoonClouds.TRANSIT_INDEX)
        val tailBefore = f.near.map { it.puffs[10].px }
        // 切 LOW ⇒ 只读前 7 斑，尾部 4 斑的运行时字段必须**原封不动**（重播种会让云瞬移）
        step(f, 60, MoonClouds.PUFF_NEAR_LOW, MoonClouds.PUFF_FAR_LOW, MoonClouds.TRANSIT_INDEX)
        assertRows("切档后的播种字段", seededBefore, (f.far + f.near).map { seedRowStatic(it) }, 0.0)
        // 正对照：`x` 必须**确实在推进**，否则上面那条"逐位不变"只是因为画面冻住了
        assertTrue("x 没推进 ⇒ ⑧ 的不变性判据在空转", (f.far + f.near).map { it.x } != xBefore)
        f.near.forEachIndexed { i, plume ->
            assertEquals("近层[$i] 的第 11 斑在 LOW 档被重写 ⇒ 切档在重播种",
                tailBefore[i], plume.puffs[10].px, 0.0)
        }
        // 数组本身也恒为 11（截断靠上界，⛔ 不靠重建数组）
        assertEquals(MoonClouds.PUFF_SEED_COUNT, f.near[0].puffs.size)
    }

    // ── ⑨ 位移只由增量累加（第四轮"云随鼓点抽搐"的根因）───────────────────────

    @Test
    fun `⑨ 调制突变时云永不倒退`() {
        val f = field()
        val mul = doubleArrayOf(1.0, 0.2, 3.0, 0.5, 1.5)
        val prev = MutableList(f.near.size + f.far.size) { 0.0 }
        var regressions = 0
        var round = 0
        while (round < 40) {
            mul.forEach { m ->
                step(f, 1, MoonClouds.PUFF_NEAR_HIGH, MoonClouds.PUFF_FAR_HIGH, MoonClouds.TRANSIT_INDEX, m)
                val all = f.near + f.far
                all.forEachIndexed { i, plume ->
                    val before = prev[i]
                    val now = plume.x
                    prev[i] = now
                    // 只允许"绕回起点"这一种下降（ wrap01 后 x 从小于 0.1 处重新开始）
                    if (now < before - 1e-12 && before - now < 0.9) regressions++
                }
            }
            round++
        }
        assertEquals("出现非回绕的倒退 ⇒ 绝对时间参与了位移", 0, regressions)
    }

    /** 负向自证：把旧写法（绝对时间 × 调制）喂进同一判据 ⇒ 必须报倒退。 */
    @Test
    fun `⑨b 负向自证 旧绝对时间写法必然倒退`() {
        val speedK = MoonClouds.SPD_NEAR
        val baseX = 0.30
        var tSec = 0.0
        var prev = MoonClouds.wrap01(baseX + speedK * tSec * 1.0)
        var regressions = 0
        val mul = doubleArrayOf(1.0, 0.2, 3.0, 0.5, 1.5)
        var round = 0
        while (round < 12) {
            mul.forEach { m ->
                tSec += 1.0 / 60.0
                val now = MoonClouds.wrap01(baseX + speedK * tSec * m)
                if (now < prev - 1e-12 && prev - now < 0.9) regressions++
                prev = now
            }
            round++
        }
        assertTrue("旧写法没被判据抓到 ⇒ ⑨ 判据空转", regressions > 0)
    }

    // ── ⑩ §6.6 过境只平移，零新增提交 / 零新增填充 ─────────────────────────────

    @Test
    fun `⑩ 过境只改近层 0 号缕的 cy`() {
        val pinned = field()
        val free = field()
        step(pinned, 300, MoonClouds.PUFF_NEAR_HIGH, MoonClouds.PUFF_FAR_HIGH, MoonClouds.TRANSIT_INDEX)
        step(free, 300, MoonClouds.PUFF_NEAR_HIGH, MoonClouds.PUFF_FAR_HIGH, MoonClouds.TRANSIT_OFF)

        val t = pinned.near[MoonClouds.TRANSIT_INDEX]
        val u = free.near[MoonClouds.TRANSIT_INDEX]
        // 横向、尺寸、包络、亮度**逐位相同** ⇒ 过境不新增任何提交或填充（偏差 D16）
        assertEquals(u.x, t.x, 0.0)
        assertEquals(u.gw, t.gw, 0.0)
        assertEquals(u.gh, t.gh, 0.0)
        assertEquals(u.w, t.w, 0.0)
        // ⚠️ `h` 是唯一**不能**逐位比的量：`h = max(|py − cy| + ry)` 而 `py = cy + gh·(...)` ⇒
        //    钉住 cy 之后 `(cy+e) − cy` 走的舍入路径与自然带位不同，实测差 5 ulp
        //    （82.36914626437402 vs 82.36914626437397）。语义「纵向包络没变」用相对 1e-9 钉。
        assertTrue("纵向包络变了 ⇒ 过境并非只平移：${u.h} → ${t.h}", close(u.h, t.h, 1e-9))
        assertEquals(u.alphaK, t.alphaK, 0.0)
        var j = 0
        while (j < MoonClouds.PUFF_NEAR_HIGH) {
            assertEquals("斑 $j 的 α 被过境改动 ⇒ 填充变了", u.puffs[j].a, t.puffs[j].a, 0.0)
            assertEquals("斑 $j 的半径被过境改动", u.puffs[j].rx, t.puffs[j].rx, 0.0)
            j++
        }
        // 只有 cy 平移，且落在月心高度上（1080p ⇒ ≈216，⚠️ 不是逐位 216.0）
        assertTrue("过境缕没钉到月的高度：${t.cy}", abs(t.cy - b.MOON_CY) < 1e-3)
        assertTrue("过境缕与自然带位重合 ⇒ 这一缕本来就在月的高度，判据无从判定",
            abs(t.cy - u.cy) > 1e-6)
        // ⛔ 只许钉第 0 号缕：其它近层缕的 cy 必须与自然场**逐位相同**
        var i = 1
        while (i < b.N_NEAR) {
            assertEquals("近层[$i] 也被钉了 ⇒ 过境扩大成了全员同带", pinned.near[i].cy, free.near[i].cy, 0.0)
            i++
        }
    }

    @Test
    fun `⑩b 过境带位由两个构图 K 推出，落在近层带内`() {
        val k = MoonClouds.TRANSIT_BAND_Y
        assertEquals(0.3125, k, 1e-6)
        assertEquals(MoonlitRenderer.MOON_CY_K.toDouble() / MoonlitRenderer.HORIZON_K.toDouble(), k, 0.0)
        assertTrue("带位越界 ⇒ 是特例而非常量：$k",
            k in MoonClouds.BAND_NEAR..(MoonClouds.BAND_NEAR + MoonClouds.BAND_NEAR_SPAN))
        // 横穿周期 49~76 s（所有者两轮"还是太快"之后钉死的速度口径）
        val lo = 1.0 / (MoonClouds.SPD_NEAR * (MoonClouds.SPD_JIT_BASE + MoonClouds.SPD_JIT_SPAN))
        val hi = 1.0 / (MoonClouds.SPD_NEAR * MoonClouds.SPD_JIT_BASE)
        assertTrue("周期下界 $lo 应 ≈49 s", lo in 45.0..52.0)
        assertTrue("周期上界 $hi 应 ≈76 s", hi in 70.0..80.0)
        val f = field()
        f.near.forEach { plume ->
            assertTrue("speedK=${plume.speedK} 越出取值区间",
                plume.speedK in MoonClouds.SPD_NEAR * MoonClouds.SPD_JIT_BASE..
                    MoonClouds.SPD_NEAR * (MoonClouds.SPD_JIT_BASE + MoonClouds.SPD_JIT_SPAN) + 1e-12)
        }
    }

    // ── ⑪ 银边：只在半遮出现，门控自洽 ─────────────────────────────────────────

    @Test
    fun `⑪ 银边 α 只在半遮出现`() {
        assertEquals(0.0, MoonClouds.rimA(0.0), 0.0)
        assertEquals(0.0, MoonClouds.rimA(1.0), 0.0)
        var max = 0.0
        var argMax = 0.0
        var i = 0
        while (i <= 1000) {
            val o = i / 1000.0
            val a = MoonClouds.rimA(o)
            if (a > max) { max = a; argMax = o }
            i++
        }
        assertEquals("rimA 的极值点", 0.5, argMax, 1e-3)
        assertEquals("rimA 的解析满值", 0.14, max, 1e-6)
        // 分母 0.175 高于解析满值 ⇒ rimK 天然留了余量（⛔ 不要为了让 rimK 到 1 去改分母）
        assertTrue("峰值 $max 不该达到分母 ${MoonClouds.RIM_A_FULL}", max < MoonClouds.RIM_A_FULL)
        // 门必须打得开：`rimA` 的极大点在 occl=0.5，而⑦ 的分布实测自然场有 26% 的帧**超过** 0.5
        // ⇒ 银边不是纸面特性。（旧版这里吃已删除的 OCCL_NATURAL_MAX=0.53，那个数是坏探针量出来的。）
        assertTrue("自然场路过 occl>0.5 的占比为 0 ⇒ 银边只存在于纸面", b.NAT_HOT > 0.0)
        val rimK = MoonClouds.rimK(MoonClouds.rimA(0.5))
        assertTrue("occl=0.5 处 rimK=$rimK 打不开门槛 ⇒ 银边永远不出现", rimK > MoonClouds.RIM_MIN_K * 3)
        // 逐斑门：back 不够就不画
        assertEquals(0.0, MoonClouds.rimAlpha(0.0, 1.0, 1.0), 0.0)
        assertTrue(MoonClouds.rimAlpha(1.0, 1.0, 1.0) <= MoonClouds.RIM_A_MAX)
    }

    @Test
    fun `⑪b back 的过渡带与除零防线`() {
        val rx = 100.0
        val r = 162.0
        assertEquals(1.0, MoonClouds.backFromPd(rx * MoonClouds.BACK_EDGE_K, rx, r), 1e-12)
        assertEquals(0.0,
            MoonClouds.backFromPd(rx * MoonClouds.BACK_EDGE_K + r * MoonClouds.BACK_R_K, rx, r), 1e-12)
        var prev = 1.0
        var i = 1
        while (i <= 200) {
            val now = MoonClouds.backFromPd(rx * MoonClouds.BACK_EDGE_K + i / 200.0 * r * MoonClouds.BACK_R_K, rx, r)
            assertTrue("back 随距离回弹", now <= prev + 1e-12)
            prev = now
            i++
        }
        // 斑心与月心重合 ⇒ 距离取 1（原型的 `|| 1`），⛔ 不是 0（否则下面除 pd 得 NaN）
        assertEquals(1.0, MoonClouds.distanceToMoon(500.0, 200.0, 500.0, 200.0), 0.0)
        assertTrue(MoonClouds.backOf(500.0, 200.0, rx, 500.0, 200.0, r).isFinite())
        // 剪影与亮度不打架：back→1 时 k→0
        assertEquals(0.0, MoonClouds.toneOf(1.0, 1.0, 1.0), 0.0)
        assertTrue(MoonClouds.toneOf(1.0, 1.0, 0.0) > 0.5)
        // ⭐ 环境光地板 `0.56` 的**定义点**：`lit = 0.56 + 0.44·(1 − 0.55·d²)` ⇒ 线性项归零的
        //    那一点（`d² = 1/LIT_D2_K`）上 lit **恰为地板**，⛔ 不是 0（旧写法在这里直接归零，
        //    满屏云就只在月旁才现身）。
        // ⚠️ 再往外 lit **会继续降到 0** —— 原型 `moonlit-preview.html:1118` 就这么写的
        //    （clamp 到 [0,1]）⇒ "地板"说的是**参与遮挡的那一段近场**（`d² < 1`）不落空，
        //    ⛔ 不是"全定义域的下界"。第一版把这条断成 `litOf(9e6) == 0.56` 就是我读错了它。
        assertEquals("d²=0 满受光", 1.0, MoonClouds.litOf(0.0), 1e-12)
        assertEquals("线性项归零处 = 环境光地板", MoonClouds.LIT_FLOOR,
            MoonClouds.litOf(1.0 / MoonClouds.LIT_D2_K), 1e-12)
        // 近场（遮挡真正会计的那一段 d²<1）全程高于地板 ⇒ 云不会在盘沿上突然变成剪影
        assertTrue("近场落到地板下：${MoonClouds.litOf(0.999)}", MoonClouds.litOf(0.999) > MoonClouds.LIT_FLOOR)
        // 负向自证：没有地板的旧写法（`1 − d²`）在同一判定点直接归零 ⇒ 上面那条是有牙的
        assertEquals("旧写法竟也过得了地板判据", 0.0,
            (1.0 - 1.0 / MoonClouds.LIT_D2_K).coerceIn(0.0, 1.0), 1e-12)
        // 受光只随距离衰减，⛔ 不许回弹
        var prevLit = MoonClouds.litOf(0.0)
        var s = 1
        while (s <= 200) {
            val nowLit = MoonClouds.litOf(s / 40.0)
            assertTrue("lit 随距离回弹（d²=${s / 40.0}）", nowLit <= prevLit + 1e-12)
            prevLit = nowLit
            s++
        }
    }

    // ── ⑫ 着色器分桶的量化误差（偏差 D23）──────────────────────────────────────

    @Test
    fun `⑫ 云色分桶误差钉在个位数（生产口径）`() {
        // 生产可达域：altT ≡ MoonSeascape.ALT_T（固定构图），tone ≤ 0.8·(1−back)（toneOf 的上界）
        val alt = MoonSeascape.ALT_T.toDouble()
        val e = worstQuantization(alt, reachable = true, steps = 256)
        assertTrue("单通道误差 ${e.single} 超线（生产口径）", e.single <= 20)
        assertTrue("三通道合计 ${e.total} 超线（生产口径）", e.total <= 50)

        // 宽容口径：整个立方（含生产到不了的 altT 与 tone 组合）
        val c = worstQuantizationCube(64)
        assertTrue("全立方单通道 ${c.single} 超线", c.single <= 28)
        assertTrue("全立方合计 ${c.total} 超线", c.total <= 62)
    }

    /** 负向自证：刻度降到 8 桶必须破掉 ⑫ 的钉住值（⇒ 16 桶不是随手挑的）。 */
    @Test
    fun `⑫b 负向自证 刻度砍半误差就越线`() {
        val alt = MoonSeascape.ALT_T.toDouble()
        var single = 0
        val n = 8
        var i = 0
        while (i <= 256) {
            val back = i / 256.0
            val kMax = 0.8 * (1.0 - back)
            var j = 0
            while (j <= 256 * kMax) {
                val k = j / 256.0
                val actual = MoonClouds.cloudRgb(k, alt, back)
                val kb = minOf(((k * n).toInt()), n - 1)
                val ab = minOf((alt * MoonClouds.ALT_BUCKETS).toInt(), MoonClouds.ALT_BUCKETS - 1)
                val bb = minOf(((back * n).toInt()), n - 1)
                val bucket = MoonClouds.cloudRgb(
                    kb.toDouble() / (n - 1),
                    ab.toDouble() / (MoonClouds.ALT_BUCKETS - 1),
                    bb.toDouble() / (n - 1)
                )
                single = maxOf(single, maxChannelDiff(actual, bucket))
                j++
            }
            i++
        }
        assertTrue("8 桶误差 $single 仍在 20 以内 ⇒ ⑫ 的钉住值没有约束力", single > 20)
    }

    @Test
    fun `⑫c 索引与代表值互逆，实际在场着色器有上界`() {
        // 互逆（精确）：index 解出的三元组重新索引必须回到同一个 index
        var idx = 0
        while (idx < MoonClouds.CLOUD_SHADER_SLOTS) {
            val tone = MoonClouds.bucketTone(idx)
            val alt = MoonClouds.bucketAlt(idx)
            val back = MoonClouds.bucketBack(idx)
            assertEquals("桶 $idx 解包后不可逆", idx, MoonClouds.shaderIndex(tone, alt, back))
            idx++
        }
        // tone 的可达上界 ⇒ 顶部三个 k 桶永不建立
        assertEquals(12, MoonClouds.kBucket(0.8))
        assertTrue(MoonClouds.kBucket(MoonClouds.toneOf(1.0, 1.0, 0.0)) <= 12)

        // 真实在场槽数：跑 800 帧统计层 6 实际会**建立**几张着色器（每张一个 native 对象）
        val f = field()
        val slots = HashSet<Int>()
        val alt = MoonSeascape.ALT_T.toDouble()
        var k = 0
        while (k < 800) {
            step(f, 1, MoonClouds.PUFF_NEAR_HIGH, MoonClouds.PUFF_FAR_HIGH, MoonClouds.TRANSIT_INDEX)
            var i = 0
            while (i < b.N_NEAR) {
                val plume = f.near[i]
                val lit = MoonClouds.litOf(
                    MoonClouds.overlapD2(plume.cx, plume.cy, plume.w, plume.h, b.MOON_CX, b.MOON_CY, b.MOON_R)
                )
                var j = 0
                while (j < MoonClouds.PUFF_NEAR_HIGH) {
                    val p = plume.puffs[j]
                    val pd = MoonClouds.distanceToMoon(p.px, p.py, b.MOON_CX, b.MOON_CY)
                    val back = MoonClouds.backFromPd(pd, p.rx, b.MOON_R)
                    val tone = MoonClouds.toneOf(lit, p.altT, back)
                    slots.add(MoonClouds.shaderIndex(tone, alt, back))
                    j++
                }
                i++
            }
            k++
        }
        // 下界防"空转"（一场都没扫出来也会满足 ≤128），上界是 native 对象数量的红线
        assertTrue("在场着色器 ${slots.size} 张：应在 20..128（总槽 ${MoonClouds.CLOUD_SHADER_SLOTS}）",
            slots.size in 20..128)
    }

    private class Quant(val single: Int, val total: Int)

    private fun worstQuantization(alt: Double, reachable: Boolean, steps: Int): Quant {
        var single = 0
        var total = 0
        var i = 0
        while (i <= steps) {
            val back = i / steps.toDouble()
            val kMax = if (reachable) 0.8 * (1.0 - back) else 1.0
            var j = 0
            while (j <= steps * kMax) {
                val k = j / steps.toDouble()
                val bucket = MoonClouds.cloudRgbAtBucket(MoonClouds.shaderIndex(k, alt, back))
                val diff = diffChannels(MoonClouds.cloudRgb(k, alt, back), bucket)
                single = maxOf(single, diff.first)
                total = maxOf(total, diff.second)
                j++
            }
            i++
        }
        return Quant(single, total)
    }

    private fun worstQuantizationCube(steps: Int): Quant {
        var single = 0
        var total = 0
        var a = 0
        while (a <= 16) {
            val alt = a / 16.0
            var i = 0
            while (i <= steps) {
                val back = i / steps.toDouble()
                var j = 0
                while (j <= steps) {
                    val k = j / steps.toDouble()
                    val bucket = MoonClouds.cloudRgbAtBucket(MoonClouds.shaderIndex(k, alt, back))
                    val diff = diffChannels(MoonClouds.cloudRgb(k, alt, back), bucket)
                    single = maxOf(single, diff.first)
                    total = maxOf(total, diff.second)
                    j++
                }
                i++
            }
            a++
        }
        return Quant(single, total)
    }

    /** (最坏单通道, 三通道合计) */
    private fun diffChannels(actual: Int, bucket: Int): Pair<Int, Int> {
        var worst = 0
        var sum = 0
        listOf(16, 8, 0).forEach { shift ->
            val d = abs(((actual shr shift) and 0xFF) - ((bucket shr shift) and 0xFF))
            if (d > worst) worst = d
            sum += d
        }
        return worst to sum
    }

    private fun maxChannelDiff(a: Int, c: Int): Int = diffChannels(a, c).first

    // ── ⑬ 渲染器接线（对账通过 ≠ 接上了）───────────────────────────────────────

    @Test
    fun `⑬ 层序与遮挡真源在 drawContent 里`() {
        val body = functionBody(sourceOf(RENDERER), "drawContent")
        val order = listOf(
            "skyBrush", "seaBrush", "drawStarfield(", "drawHalo(", "drawCloudFar(",
            "drawMoonDisk(", "drawMoonBloom(", "drawPhaseShadow(", "drawCloudNear("
        )
        var prev = -1
        var prevToken = ""
        order.forEach { token ->
            val at = body.indexOf(token)
            assertTrue("缺层：$token", at >= 0)
            assertTrue("层序颠倒：$prevToken 在 $token 之后", at > prev)
            prev = at
            prevToken = token
        }
        // 云必须先推进再算遮挡（顺序反了会用上一帧的几何）
        assertTrue(body.indexOf("clouds.step(") in 0 until body.indexOf("clouds.occlusion("))
        // 五个消费点必须吃**同一个** occl 变量（T8 时 reflA / glitA 加入这张名单）
        listOf("MoonClouds.diskA(", "MoonClouds.haloA(", "MoonClouds.bloomA(", "MoonClouds.darkEdgeA(",
            "MoonClouds.rimA(").forEach { call ->
            val lines = body.lines().filter { it.contains(call) }
            assertEquals("$call 在 drawContent 里应只有一处", 1, lines.size)
            assertTrue("$call 没吃 occl ⇒ 另起了一个遮挡口径：${lines[0].trim()}", lines[0].contains("occl"))
        }
    }

    @Test
    fun `⑬b 刻意常量 DISK_A 类不得复活`() {
        val src = sourceOf(RENDERER)
        listOf("DISK_A", "BLOOM_A", "DARK_EDGE_A").forEach { name ->
            assertEquals("$name 仍在（T7 起必须走 occl 派生）：", 0, occurrences(src, Regex("\\b$name\\b")))
        }
        // 负向自证：判据本身能抓到
        assertEquals(1, occurrences("val a = DISK_A\nval c = b", Regex("\\bDISK_A\\b")))
        assertEquals(1, occurrences("val a = DARK_EDGE_A", Regex("\\bDARK_EDGE_A\\b")))
        // ⛔ 但 DARK_EDGE_MAX_F 是另一个东西，不能被误伤
        assertEquals(0, occurrences("val a = DARK_EDGE_MAX_F", Regex("\\bDARK_EDGE_A\\b")))
    }

    @Test
    fun `⑬c 裁剪只用矩形，银边不画弧`() {
        val src = sourceOf(RENDERER)          // 已剥注释：类 KDoc 里原文引用了被禁写法
        assertEquals("clipRect 只该有远/近两层", 2, occurrences(src, Regex("clipRect\\(0f, 0f, w, horizonY\\)")))
        listOf("clipPath(", "clip(", "RoundedCornerShape").forEach { banned ->
            assertEquals("API 22 红线：$banned", 0, occurrences(src, Regex(Regex.escape(banned))))
        }
        assertEquals(1, occurrences("canvas.clipPath(p)", Regex("clipPath\\(")))   // 判据有判别力
        val near = functionBody(src, "drawCloudNear")
        assertEquals(0, occurrences(near, Regex("drawArc\\(|softRing\\(")))
        assertTrue("银边偏移比例常量没接上", near.contains("RIM_OFF_K"))
        assertTrue("银边逐斑门没接上", near.contains("RIM_MIN_BACK"))
    }

    @Test
    fun `⑬d 着色器构造不进逐帧路径`() {
        val src = sourceOf(RENDERER)
        assertEquals("晕/盘的径向渐变各只在一处构造", 2, occurrences(src, Regex("Brush\\.radialGradient\\(")))
        drawScopeBodies(src).forEach { (name, body) ->
            listOf("Brush.", "RadialGradient(", "softShader(").forEach { banned ->
                assertEquals("逐帧函数 $name 里构造了 $banned", 0, occurrences(body, Regex(Regex.escape(banned))))
            }
        }
        // 晕的缓存入口与建张函数都是低频路径 ⇒ ⛔ 不带 DrawScope 接收者（否则会被上面的扫描覆盖，
        // 也会诱使人逐帧调）。T9 起 `buildHalo` 换成「入口 `ensureHaloBucket` + 建一桶 `buildHaloBucket`」
        assertTrue(src.contains("private fun ensureHaloBucket("))
        assertTrue(src.contains("private fun buildHaloBucket("))
        assertEquals(0, occurrences(src, Regex("fun DrawScope\\.ensureHaloBucket")))
        assertEquals(0, occurrences(src, Regex("fun DrawScope\\.buildHaloBucket")))
        val haloKey = functionBody(src, "ensureHaloBucket")
            .lines().first { it.contains("if (") }.trim()
        assertTrue("档位/画幅变化才整段作废重建晕：$haloKey",
            haloKey.contains("level != haloLevel") && haloKey.contains("moonR != haloMoonR") &&
                haloKey.contains("haloDirty"))
        // ⭐ 负向自证（T9 的决策）：**桶号不得进重建键**。写进去 ⇒ 每次跨桶清空 24 槽，
        //    而 `bass` 在桶边界来回抖时每帧都要重建 3 张 native 渐变 = 逐帧分配。
        //    先例是 [bandBrushOf]：延迟、逐桶、跨桶保留。
        assertFalse("⛔ 桶号不得进晕的重建键（跨桶清空 ⇒ 边界抖动逐帧重建）：$haloKey",
            haloKey.contains("bucket"))
        assertEquals("drawContent 只经入口拿晕渐变",
            1, occurrences(functionBody(src, "drawContent"), Regex("ensureHaloBucket\\(")))
    }

    @Test
    fun `⑬e 云场零 Android 依赖（G3 可纯 JVM 跑的前提）`() {
        val src = File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/MoonClouds.kt").readText()
        val imports = src.lines().filter { it.startsWith("import ") }
        assertTrue("MoonClouds 引入了 Android: $imports",
            imports.none { it.contains("android") || it.contains("androidx") || it.contains("compose") })
        // 负向自证
        assertTrue(importsWithAndroid("import android.graphics.Paint\nimport kotlin.math.PI"))
    }

    private fun importsWithAndroid(src: String): Boolean =
        src.lines().filter { it.startsWith("import ") }.any { it.contains("android") }

    // ── ⑭ 记账恒等式（数量表与预算表必须互相咬合）──────────────────────────────

    @Test
    fun `⑭ 云与晕的提交数等于几何恒等式`() {
        listOf(MoonLevel.LOW, MoonLevel.MEDIUM, MoonLevel.HIGH).forEach { level ->
            assertEquals("远层提交数",
                MoonClouds.plumes(level, false) * MoonClouds.puffs(level, false),
                opsOf(MoonOpItem.CLOUD_FAR, level))
            assertEquals("近层提交数",
                MoonClouds.plumes(level, true) * MoonClouds.puffs(level, true),
                opsOf(MoonOpItem.CLOUD_NEAR, level))
            assertEquals("晕的圈数",
                MoonClouds.haloSpec(level).size,
                opsOf(MoonOpItem.HALO, level))
        }
        // LOW 恒 0 是代码事实（那道 `tier !== LOW` 的门），⛔ 不是省预算
        assertEquals(0, MoonOpItem.CLOUD_RIM.opsLow)
        assertTrue(MoonOpItem.CLOUD_RIM.opsHigh > 0)
        // 档位单调
        assertTrue(MoonOpItem.CLOUD_NEAR.opsLow < MoonOpItem.CLOUD_NEAR.opsHigh)
        assertTrue(MoonOpItem.HALO.fillLow < MoonOpItem.HALO.fillHigh)
    }

    private fun opsOf(item: MoonOpItem, level: MoonLevel): Int = when (level) {
        MoonLevel.LOW -> item.opsLow
        MoonLevel.MEDIUM -> item.opsMed
        MoonLevel.HIGH -> item.opsHigh
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────────

    private companion object {
        const val RENDERER = "MoonlitRenderer.kt"
        const val SPD_NEUTRAL = 1.0
    }

    private fun field(seed: Int = MoonClouds.SEED): MoonCloudField =
        MoonCloudField().apply { reseed(seed) }

    /** §3.1 定稿构图 + `spdMul = 1` + 125 次 `+= 0.1` + ⛔ 关掉过境钉（几何对账口径）。 */
    private fun referenceFrame(): MoonCloudField =
        MoonCloudField().apply {
            reseed()
            repeat(b.FRAMES) {
                step(b.DT_SEC, SPD_NEUTRAL, b.W, b.HORIZON_Y, b.MIN_DIM,
                    b.N_FAR, b.NP_FAR, b.N_NEAR, b.NP_NEAR, MoonClouds.TRANSIT_OFF)
            }
        }

    private fun step(
        f: MoonCloudField,
        frames: Int,
        npNear: Int,
        npFar: Int,
        transitIndex: Int,
        spdMul: Double = SPD_NEUTRAL,
    ) {
        var i = 0
        while (i < frames) {
            f.step(0.1, spdMul, b.W, b.HORIZON_Y, b.MIN_DIM,
                b.N_FAR, npFar, b.N_NEAR, npNear, transitIndex)
            i++
        }
    }

    private fun seedRow(plume: MoonPlume): DoubleArray {
        val seed = MoonClouds.PUFF_SEED_COUNT
        val o = DoubleArray(10 + seed * 8)
        o[0] = plume.x
        o[1] = plume.bandY
        o[2] = plume.lenK
        o[3] = plume.thickK
        o[4] = plume.tilt
        o[5] = plume.wavK
        o[6] = plume.wavN.toDouble()
        o[7] = plume.speedK
        o[8] = plume.alphaK
        o[9] = plume.wobPhase
        var j = 0
        while (j < seed) {
            val p = plume.puffs[j]
            val k = 10 + j * 8
            o[k] = p.t
            o[k + 1] = p.rK
            o[k + 2] = p.sq
            o[k + 3] = p.off
            o[k + 4] = p.spd
            o[k + 5] = p.aK
            o[k + 6] = p.ph
            o[k + 7] = p.ph2
            j++
        }
        return o
    }

    /**
     * 播种行里**不随时间推进**的那 97 个字段。⚠️ 只用于「切档不许重播种」这类**跨帧**比较；
     * 与 [MoonlitCloudBaseline] 的 `SEED_*` 对账（t=0）请直接用 [seedRow]，⛔ 别把 `x` 漏掉。
     */
    private fun seedRowStatic(plume: MoonPlume): DoubleArray = seedRow(plume).drop(1).toDoubleArray()

    private fun geomRow(plume: MoonPlume, np: Int): DoubleArray {
        val o = DoubleArray(6 + np * 6)
        o[0] = plume.cx
        o[1] = plume.cy
        o[2] = plume.gw
        o[3] = plume.gh
        o[4] = plume.w
        o[5] = plume.h
        var j = 0
        while (j < np) {
            val p = plume.puffs[j]
            val k = 6 + j * 6
            o[k] = p.px
            o[k + 1] = p.py
            o[k + 2] = p.rx
            o[k + 3] = p.ry
            o[k + 4] = p.a
            o[k + 5] = p.altT
            j++
        }
        return o
    }

    private fun assertRows(tag: String, expected: List<DoubleArray>, actual: List<DoubleArray>, tol: Double) {
        val bad = mutableListOf<String>()
        assertEquals("$tag 缕数不符", expected.size, actual.size)
        expected.forEachIndexed { i, e ->
            val a = actual[i]
            assertEquals("$tag 第 $i 缕字段数", e.size, a.size)
            var j = 0
            while (j < e.size) {
                if (!close(e[j], a[j], tol)) bad += "$tag[$i][$j]: 期望 ${e[j]} 实得 ${a[j]}（差 ${a[j] - e[j]}）"
                j++
            }
        }
        assertTrue(bad.take(24).joinToString("\n"), bad.isEmpty())
    }

    /** tol = 0.0 ⇒ 逐位相同；小量用绝对容差，避免 0 附近除爆。 */
    private fun close(expected: Double, actual: Double, tol: Double): Boolean =
        abs(actual - expected) <= tol * maxOf(1.0, abs(expected))

    private fun occurrences(src: String, re: Regex): Int = re.findAll(src).toList().size

    /** 取 `call`（含结尾左括号）的**实参文本** —— 括号要配对，否则读不到跨行的实参。 */
    private fun callArgs(src: String, call: String): String {
        val at = src.indexOf(call)
        if (at < 0) error("找不到 $call")
        var i = at + call.length
        var depth = 1
        val sb = StringBuilder()
        while (i < src.length && depth > 0) {
            when (src[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth > 0) sb.append(src[i])
            i++
        }
        assertTrue("$call 实参括号不配对", depth == 0)
        return sb.toString()
    }

    private fun sourceOf(name: String): String =
        stripComments(File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/$name").readText())

    /** 找出所有 `fun DrawScope.drawXxx(...)` 的**函数体**（门禁只该管逐帧路径）。 */
    private fun drawScopeBodies(src: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Regex("""fun DrawScope\.(draw\w+)""").findAll(src).forEach { m ->
            out[m.groupValues[1]] = bodyFrom(src, m.range.first)
        }
        return out
    }

    private fun functionBody(src: String, name: String): String {
        val head = Regex("""fun\s+(?:DrawScope\.)?$name\s*\(""").find(src) ?: error("找不到 $name")
        return bodyFrom(src, head.range.first)
    }

    private fun bodyFrom(src: String, from: Int): String {
        var i = from
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
        error("大括号不配对（从 $from 起）")
    }

    /** 剥块注释（Kotlin 可嵌套）与行注释，⛔ 保持行数一致（输出的行号要能定位回原文件）。 */
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

package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 门禁 **G10 / G9 / G6 / G7 / G8**：[MoonDiskBake] 的 UV 反解、色彩曲线单位与烘焙质量
 * （规格 `docs/moonlit-visualizer-plan.md` §5.1 / §5.2+ / §十 10.2）。
 *
 * 纯 JVM：[MoonDiskBake] 不碰 `android.*`，源图用 **ImageIO** 直接从
 * `app/src/main/assets/globe/moon.jpg` 解成 `0xAARRGGBB` 整型数组（与
 * `Bitmap.getPixels` 同一打包），所以本类 ⛔ 不需要 Robolectric。
 *
 * ## ⚠️ G10 的 ② 条与规格不一致 —— 这是**实测推翻规格**，已按 2026-10-09 所有者裁决处理
 * 规格 §10.2 G10 写的是「盘内 p95 / 盘内均值 ≤ 1.25」。真素材实测（`R_tex=203`、
 * 全盘圆内像素、mean3 亮度、与 `moonlit_ref_match_check.py` 同一套统计口径）：
 *
 * | 变体 | 顶格占比（任一通道 = 255） | 盘内 R 均值 | p95/均值 |
 * |---|---|---|---|
 * | ① 定稿（`0.72+0.55g`、tint 含 `lumK×1.34`） | **5.4%** | **155.5** | 1.639 |
 * | ② 单位坑（再乘 255，§5.2+ ①） | 100% | 255.0 | 1.000 |
 * | ③ 旧曲线 `0.45+0.95g` 配 `DISK_GAIN 1.5`（§5.2+ ②） | 13.6% | 162.5 | 1.679 |
 * | ④ 漏乘 `DISK_GAIN` | 0.2% | 117.9 | 1.636 |
 *
 * ⇒ `p95/均值` 在**纹理层**根本到不了 1.25（定稿就是 1.639），而且它对②③这两类
 * "过曝成白盘"的历史缺陷**是反着动的**：越曝越**接近 1**（顶格把分布压平）。
 * 换句话说照规格写这条断言，要么恒红、要么把"死白圆片"判成合格。
 * ⇒ 本类把**判别主力换成"顶格占比"**（②③④三个反例分别是 100% / 13.6% / 不受影响，
 * 再加一条 **R 均值下限**抓住"整盘发暗"的④），`p95/均值` 降级为**分布形状哨兵**
 * （它只对②敏感：顶格抹平分布 ⇒ 1.000），⛔ 不许用它替代顶格判据。
 * 规格的偏离记录见 §十一 T4 条目与 `docs/technical-overview.md`。
 */
class MoonDiskBakeTest {

    // ══════════════════════ 公共夹具 ══════════════════════

    /** 1080p 定稿构图下的月盘半径（§3.1：`minDim × MOON_R_K`） */
    private fun moonR1080(): Float = minOf(1920f, 1080f) * MoonlitRenderer.MOON_R_K   // 162f

    private class Disc(
        val px: IntArray,
        val texR: Int,
        val inside: BooleanArray,
    ) {
        val t get() = texR * 2
        fun r(i: Int) = (px[i] ushr 16) and 0xFF
        fun g(i: Int) = (px[i] ushr 8) and 0xFF
        fun b(i: Int) = px[i] and 0xFF
        fun a(i: Int) = (px[i] ushr 24) and 0xFF
    }

    private fun bakeDisc(
        src: IntArray,
        srcW: Int,
        srcH: Int,
        texR: Int,
        tintR: Float,
        tintG: Float,
        tintB: Float,
        libW: Float = 0f,
        libB: Float = 0f,
    ): Disc {
        val out = IntArray(texR * 2 * texR * 2)
        MoonDiskBake.bakeRows(src, srcW, srcH, texR, 0, texR * 2, libW, libB, tintR, tintG, tintB, out, FloatArray(2))
        val inside = BooleanArray(out.size)
        val c = texR.toFloat()
        for (py in 0 until texR * 2) {
            for (px in 0 until texR * 2) {
                val x = (px - c) / texR
                val y = (c - py) / texR
                inside[py * texR * 2 + px] = (x * x + y * y) <= 1.0f
            }
        }
        return Disc(out, texR, inside)
    }

    /**
     * 真素材（⛔ 不用合成图当基准 —— 定稿判据全部是在这张图上量出来的）。
     *
     * ⚠️ 这里走**反射**调 `javax.imageio.ImageIO`，不是笔误：单元测试的**编译**期 classpath
     *   是 `android.jar`（里面没有 `java.awt` / `javax.imageio`），所以 `import` 直接编译不过；
     *   但测试**运行**期是完整的 JDK JVM，`java.desktop` 模块在 ⇒ 反射拿得到。
     *   这样本类就 ⛔ 不需要 Robolectric（更不需要 `@GraphicsMode(NATIVE)` 那份额外的
     *   native-graphics 运行时下载），也 ⛔ 不用把真素材的像素副本再存进仓库当夹具。
     *   `getRGB` 返回的正是 `0xAARRGGBB` —— 与 `Bitmap.getPixels` 同一打包。
     */
    private fun realSource(): Pair<IntArray, Pair<Int, Int>> {
        val f = moonAssetFile()
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val img = imageIo.getMethod("read", File::class.java).invoke(null, f)
            ?: error("ImageIO 解不开 $f")
        val w = img.javaClass.getMethod("getWidth").invoke(img) as Int
        val h = img.javaClass.getMethod("getHeight").invoke(img) as Int
        val px = IntArray(w * h)
        img.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(img, 0, 0, w, h, px, 0, w)
        return px to (w to h)
    }

    private fun bakedReal(scale: Float = 1f): Disc {
        val (src, wh) = realSource()
        val t = MoonDiskBake.TINT_R * scale
        val g = MoonDiskBake.TINT_G * scale
        val b = MoonDiskBake.TINT_B * scale
        return bakeDisc(src, wh.first, wh.second, MoonDiskBake.texRadius(moonR1080()), t, g, b)
    }

    private class Stats(val clipShare: Double, val rMean: Double, val ratio: Double, val whiteRows: Int)

    /** 与 `moonlit_ref_match_check.py` 同口径：亮度 = 三通道均值，p95 用**线性插值**分位 */
    private fun Disc.stats(): Stats {
        val lum = ArrayList<Double>(inside.size)
        var clipped = 0
        var rSum = 0L
        var n = 0
        for (i in px.indices) {
            if (!inside[i]) continue
            val rr = r(i); val gg = g(i); val bb = b(i)
            n++
            rSum += rr
            if (rr == 255 || gg == 255 || bb == 255) clipped++
            lum.add((rr + gg + bb) / 3.0)
        }
        lum.sort()
        val q = 0.95 * (lum.size - 1)
        val lo = floor(q)
        val hi = ceil(q).toInt().coerceAtMost(lum.size - 1)
        val p95 = lum[lo.toInt()] + (lum[hi] - lum[lo.toInt()]) * (q - lo)
        val mean = lum.average()
        var whiteRows = 0
        val t = texR * 2
        for (py in 0 until texR * 2) {
            var all = true
            var any = false
            for (pxx in 0 until t) {
                val i = py * t + pxx
                if (!inside[i]) continue
                any = true
                if (!(r(i) == 255 && g(i) == 255 && b(i) == 255)) { all = false; break }
            }
            if (any && all) whiteRows++
        }
        return Stats(100.0 * clipped / n, rSum.toDouble() / n, p95 / mean, whiteRows)
    }

    // ══════════════════ A. §5.1 UV 反解几何 ══════════════════

    @Test
    fun `A1 圆外像素 alpha 恒为 0、圆内恒为 255`() {
        val d = bakedReal()
        val c = d.texR.toFloat()
        // 四角必在圆外
        for (i in intArrayOf(0, d.t - 1, (d.t - 1) * d.t, d.t * d.t - 1)) {
            assertEquals("角点 $i 应透明", 0, d.a(i))
        }
        var insideCount = 0
        var holes = 0
        var leaks = 0
        for (i in d.px.indices) {
            if (d.inside[i]) {
                insideCount++
                if (d.a(i) != 255) holes++
            } else if (d.a(i) != 0) {
                leaks++
            }
        }
        assertEquals("圆内出现非 255 alpha（烘焙漏行 / 圆内被置 0）", 0, holes)
        assertEquals("圆外 alpha 泄漏 ⇒ 方块盘（clipPath 禁令下唯一的圆形保证）", 0, leaks)
        // 覆盖面：圆内像素数 ≈ πR²（容差 0.5%）⇒ f=1 时贴出来是**一个完整的亮圆**，没有洞
        val expect = PI * c * c
        assertTrue(
            "圆内像素数 $insideCount 偏离 πR²=$expect 超过 0.5%",
            kotlin.math.abs(insideCount - expect) / expect < 0.005,
        )
    }

    @Test
    fun `A2 无天平动时盘心采样到源图中央`() {
        val uv = FloatArray(2)
        val texR = 203
        assertTrue(MoonDiskBake.uv(texR, texR, texR, 1024, 512, 0f, 0f, uv))
        assertEquals(512f, uv[0], 0.5f)     // u = srcW/2（中央经线）
        assertEquals(256f, uv[1], 0.5f)     // v = srcH/2（月面赤道）
        // 圆盘边缘外 1 像素 ⇒ 落在圆外
        assertFalse(MoonDiskBake.uv(0, 0, texR, 1024, 512, 0f, 0f, uv))
    }

    @Test
    fun `A3 天平动经度把采样点整体东移 每度 1 360 圈`() {
        val uv0 = FloatArray(2)
        val uv1 = FloatArray(2)
        val texR = 203
        MoonDiskBake.uv(texR, texR, texR, 1024, 512, 0f, 0f, uv0)
        MoonDiskBake.uv(texR, texR, texR, 1024, 512, 6f, 0f, uv1)
        // §4.3 实测范围 libW ∈ [−6.2°, +4.8°] ⇒ 6° 是一个"看得见大月海进出 limb"的量级
        assertEquals(6.0 / 360.0 * 1024.0, (uv1[0] - uv0[0]).toDouble(), 0.02)
        assertEquals(uv0[1], uv1[1], 1e-3f)          // 纯经度天平动不该挪纬度
        // 反向：−6° 在 u 上必须回绕（wrap360），⛔ 出现负数
        MoonDiskBake.uv(texR, texR, texR, 1024, 512, -6f, 0f, uv1)
        assertTrue("负经度未回绕：u=${uv1[0]}", uv1[0] >= 0f)
    }

    @Test
    fun `A4 天平动量化 0 5 度一档`() {
        assertEquals(0, MoonDiskBake.quantizeLibration(0f))
        assertEquals(0, MoonDiskBake.quantizeLibration(0.49f))
        assertEquals(1, MoonDiskBake.quantizeLibration(0.5f))
        assertEquals(-1, MoonDiskBake.quantizeLibration(-0.1f))   // floor 语义，⛔ 不是截断
        assertEquals(-1, MoonDiskBake.quantizeLibration(-0.5f))
        // 一场播放（≤ 3 h）内峰值速率 1.3°/天 ⇒ 量化值必须**不变**（重烘≈0 次）
        assertEquals(MoonDiskBake.quantizeLibration(0f), MoonDiskBake.quantizeLibration(0.16f))
    }

    // ══════════════════ B. G10 定稿判据（真素材）══════════════════

    /**
     * G10 ①（输出通道 ≤ 255）。⚠️ 光断言"通道 ≤ 255"是**空转** —— `coerceIn` 之后恒成立。
     * 承重的判法是 **alpha 域**：打包式 `(r shl 16) or (g shl 8) or b`，任一通道没被钳位就会
     * 溢出到相邻域、最高位的 R 直接顶进 alpha ⇒ "圆内 alpha 恒为 255"同时钉住了钳位与位移量。
     */
    @Test
    fun `B1 G10 ① 通道钳位生效（用 alpha 域反证溢出）`() {
        val d = bakedReal()
        var clamped = 0
        for (i in d.px.indices) {
            assertTrue("通道越界（未钳位）：${d.px[i].toString(16)}", d.r(i) in 0..255)
            assertTrue("通道越界：${d.px[i].toString(16)}", d.g(i) in 0..255)
            assertTrue("通道越界：${d.px[i].toString(16)}", d.b(i) in 0..255)
            if (d.inside[i]) assertEquals("圆内 alpha 不是 255 ⇒ 有通道溢出进了 alpha 域", 255, d.a(i))
            if (d.r(i) == 255 || d.g(i) == 255 || d.b(i) == 255) clamped++
        }
        // 钳位确实在承重（否则 B3 的"顶格占比"根本无从发生）
        assertTrue("全盘没有任何通道顶到 255 ⇒ 该通路比定稿更暗，B3 判据需重定", clamped > 0)
    }

    @Test
    fun `B2 G10 ② 不存在整行三通道全 255`() {
        val s = bakedReal().stats()
        assertEquals(
            "出现 ${s.whiteRows} 行整行死白（§5.2+ ① 的单位 bug 形态：中心 5×5 全 255）",
            0, s.whiteRows,
        )
    }

    @Test
    fun `B3 G10 ③ 全盘顶格占比不大于 8 个百分点`() {
        val s = bakedReal().stats()
        // 读数进测试报告（system-out）⇒ §十 G10 的偏离记录用的是**本管线自己**量的数，
        // ⛔ 不是外部脚本的近似值
        println("[G10 实测] 顶格占比=${"%.3f".format(s.clipShare)}%  盘内R均值=${"%.1f".format(s.rMean)}  p95/均值=${"%.3f".format(s.ratio)}")
        assertTrue(
            "顶格占比 ${"%.2f".format(s.clipShare)}% > 8%（定稿实测 5.4%；单位坑 100%；旧曲线+GAIN1.5 13.6%）",
            s.clipShare <= 8.0,
        )
    }

    @Test
    fun `B4 G10 ④ 盘内 R 均值不小于 140`() {
        val s = bakedReal().stats()
        assertTrue(
            "盘内 R 均值 ${"%.1f".format(s.rMean)} < 140（定稿 155.5；漏乘 DISK_GAIN 会掉到 117.9 = 整盘发暗）",
            s.rMean >= 140.0,
        )
    }

    @Test
    fun `B5 G10 ⑤ p95 均值比落在分布哨兵带内`() {
        val s = bakedReal().stats()
        // ⛔ 这条**不是**过曝判据（见类 KDoc：它对着色缺陷是反着动的），只防"分布被压平"
        assertTrue(
            "p95/均值 ${"%.3f".format(s.ratio)} 越出 [1.55, 1.72]（定稿 1.639；整盘顶格时塌到 1.000）",
            s.ratio in 1.55..1.72,
        )
    }

    @Test
    fun `B6 盘心不是死白芯（中心 5x5 至少一个通道未顶格）`() {
        val d = bakedReal()
        val c = d.texR
        var allWhite = true
        for (py in c - 2..c + 2) {
            for (px in c - 2..c + 2) {
                val i = py * d.t + px
                if (!(d.r(i) == 255 && d.g(i) == 255 && d.b(i) == 255)) allWhite = false
            }
        }
        assertFalse("盘心 5×5 全 255 ⇒ 正是原型 §5.2+ ① 实测到的那张死白圆片", allWhite)
    }

    // ══════════════════ C. 负向自证（⛔ 缺了这些 B 组就没有判别力）══════════════════

    @Test
    fun `C1 负向 单位坑 tint 再乘 255 必须同时打中 B3 与 B5`() {
        val s = bakedReal(255f).stats()
        assertTrue("单位坑未被顶格判据抓住：share=${s.clipShare}%", s.clipShare > 90.0)
        assertTrue("单位坑未被分布哨兵抓住：ratio=${s.ratio}", s.ratio < 1.10)
        assertTrue("单位坑必须出现整行死白", s.whiteRows > 0)
    }

    @Test
    fun `C2 负向 增益过乘 12 必须被顶格判据抓住`() {
        // 等效于把 DISK_GAIN 从 1.34 推到约 1.5（§5.2+ ② 的"提亮只会更白"那条）
        val s = bakedReal(1.12f).stats()
        assertTrue("顶格占比 ${s.clipShare}% 没越过 8% ⇒ B3 对增益过乘无判别力", s.clipShare > 8.0)
        // ⛔ 而分布哨兵在这个反例上是**绿的**（1.573 仍在带内）⇒ 这正是它不能替代 B3 的实证
        assertTrue("ratio=${s.ratio} 越出了带 ⇒ B5 会把过曝反例误报（应只由 B3 判）", s.ratio in 1.55..1.72)
    }

    @Test
    fun `C3 负向 漏乘 DISK_GAIN 必须被 R 均值下限抓住`() {
        val s = bakedReal(1f / MoonDiskBake.DISK_GAIN).stats()
        assertTrue("盘内 R 均值 ${s.rMean} 没掉到 140 以下 ⇒ B4 对整盘发暗无判别力", s.rMean < 140.0)
    }

    // ══════════════════ D. §5.4 降级（门禁 G9）══════════════════

    @Test
    fun `D1 程序化月面有明暗结构 不是平色`() {
        val w = MoonDiskBake.FALLBACK_W
        val h = MoonDiskBake.FALLBACK_H
        val fb = IntArray(w * h)
        MoonDiskBake.synthesizeFallback(fb, w, h)
        val levels = HashSet<Int>()
        var min = 999
        var max = -1
        for (i in fb.indices) {
            assertEquals("降级源必须有满 alpha，否则圆盘会透明", 255, (fb[i] ushr 24) and 0xFF)
            val q = (fb[i] ushr 16) and 0xFF
            levels.add(q)
            if (q < min) min = q
            if (q > max) max = q
        }
        assertTrue("灰阶只有 ${levels.size} 档 ⇒ 近似平色（§5.4 ⛔ 不许平色盘）", levels.size >= 48)
        assertTrue("最暗 $min 不够暗 ⇒ 月海没压出来", min <= 60)
        assertTrue("最亮 $max 不够亮 ⇒ 射纹/高地没提起来", max >= 180)
    }

    @Test
    fun `D2 降级源走同一条烘焙管线 仍是一张不发白的圆盘`() {
        val w = MoonDiskBake.FALLBACK_W
        val h = MoonDiskBake.FALLBACK_H
        val fb = IntArray(w * h)
        MoonDiskBake.synthesizeFallback(fb, w, h)
        val d = bakeDisc(fb, w, h, MoonDiskBake.texRadius(moonR1080()),
            MoonDiskBake.TINT_R, MoonDiskBake.TINT_G, MoonDiskBake.TINT_B)
        val s = d.stats()
        var holes = 0
        var leaks = 0
        for (i in d.px.indices) {
            if (d.inside[i]) { if (d.a(i) != 255) holes++ } else if (d.a(i) != 0) leaks++
        }
        assertEquals("降级盘圆内有洞", 0, holes)
        assertEquals("降级盘圆外不透明", 0, leaks)
        assertTrue("降级盘顶格占比 ${"%.1f".format(s.clipShare)}% > 8% ⇒ 降级路径自己就是一张白盘", s.clipShare <= 8.0)
        assertTrue("降级盘盘内 R 均值 ${"%.1f".format(s.rMean)} 不在 [130, 190]（⛔ 既不能发暗也不能过曝）",
            s.rMean in 130.0..190.0)
        assertEquals("降级盘出现整行死白", 0, s.whiteRows)
    }

    @Test
    fun `D3 色温派生量与规格定稿值逐条对齐`() {
        // §3.3+：altT 是两个**固定构图比例**的商 ⇒ 与画幅无关，自 T6 起归色尺 [MoonSeascape] 所有
        //（⚠️ 本断言同时是"盘色温与水天色分家"的哨兵：下面三条 LIT_* 全部由它推出）
        assertEquals(0.6875, MoonSeascape.ALT_T.toDouble(), 1e-6)
        // moonHsl(37.72, 0.9969, 0.7309) → rgb(254.79, 203.99, 117.99)
        assertEquals(254.79, MoonDiskBake.LIT_R.toDouble(), 0.02)
        assertEquals(203.99, MoonDiskBake.LIT_G.toDouble(), 0.02)
        assertEquals(117.99, MoonDiskBake.LIT_B.toDouble(), 0.02)
        assertEquals(0.9921, MoonDiskBake.LUM_K.toDouble(), 1e-3)
        // ⭐ 喂进烘焙的 tint = tintMul × lumK × DISK_GAIN（原型 :976），⛔ 不是 tintMul
        assertEquals(1.3294, MoonDiskBake.TINT_R.toDouble(), 2e-3)
        assertEquals(1.0643, MoonDiskBake.TINT_G.toDouble(), 2e-3)
        assertEquals(0.6156, MoonDiskBake.TINT_B.toDouble(), 2e-3)
        // HOT_K = 255/mmax ⇒ 最大通道**正好** 255（⛔ 不得 >1.0 地再往上乘）
        assertEquals(255.0, MoonDiskBake.HOT_R.toDouble(), 0.6)
        assertEquals(204.0, MoonDiskBake.HOT_G.toDouble(), 0.6)
        assertEquals(118.0, MoonDiskBake.HOT_B.toDouble(), 0.6)
        assertEquals(0xFF_FF_CC_76.toInt(), MoonDiskBake.HOT_COLOR_INT)   // A=FF rgb(255,204,118)
        // 曲线与临边昏暗常数（§5.2+ ②③，⛔ 不是旧的 0.45+0.95g）
        assertEquals(0.72f, MoonDiskBake.GAIN_A, 1e-6f)
        assertEquals(0.55f, MoonDiskBake.GAIN_B, 1e-6f)
        assertEquals(0.16f, MoonDiskBake.LD_K, 1e-6f)
    }

    // ══════════════════ E. 源码扫描（G6 上下文泄漏 / G7 新增资产 / G8 位图分配）══════════════════

    private fun rendererSource(): String =
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/MoonlitRenderer.kt").readText()

    @Test
    fun `E1 G6 工厂只传 applicationContext 且 context 只用于 assets`() {
        val factory = File(mainSourceRoot(), "com/nasmusic/tv/visualizer/VisualizerRendererFactory.kt").readText()
        assertTrue(
            "工厂分支必须写成 MoonlitRenderer(context.applicationContext)（§5.3，防 Activity 泄漏）",
            factory.contains("MoonlitRenderer(context.applicationContext)"),
        )
        val r = rendererSource()
        assertTrue(r.contains("private val context: Context"))
        val used = Regex("""context\.(\w+)""").findAll(r).map { it.value }.toList().distinct()
        assertEquals("context 只许用于 assets，实测用到 $used", listOf("context.assets"), used)
    }

    @Test
    fun `E2 G7 assets 未新增月相关文件的任何一条`() {
        val root = mainSourceRoot().parentFile!!.resolve("assets")
        val moon = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile }.forEach {
            val rel = it.relativeTo(root).path.replace('\\', '/')
            if (rel.contains("moon", ignoreCase = true) || rel.contains("月球")) moon.add(rel)
        }
        assertEquals("E44 不允许新增资产（APK 体积硬约束），只许复用既有的那张：$moon",
            listOf("globe/moon.jpg"), moon)
    }

    @Test
    fun `E3 G8 createBitmap 调用点不超过 2 处且不在循环内`() {
        val r = rendererSource()
        val sites = Regex("Bitmap\\.createBitmap").findAll(r).toList()
        assertTrue("createBitmap 调用点 ${sites.size} 处 > 2（G8）", sites.size <= 2)
        sites.forEach { m ->
            val head = r.lastIndexOf("private fun", m.range.first).let { if (it < 0) 0 else it }
            val body = r.substring(head, m.range.first)
            assertFalse("圆盘位图在循环里分配：${body.take(80)}",
                body.contains("for (") || body.contains("while (") || body.contains("forEach"))
        }
    }

    @Test
    fun `E4 圆盘纹理半径钳位与边长关系`() {
        // §5.2：R_tex = clamp(moonR×1.25, 192, 512)
        assertEquals(203, MoonDiskBake.texRadius(162f))
        assertEquals(192, MoonDiskBake.texRadius(10f))
        assertEquals(512, MoonDiskBake.texRadius(4000f))
        // 4K 画幅（minDim=2160）⇒ moonR=324 ⇒ R_tex=405 < 512 上限，仍在钳位内
        assertEquals(405, MoonDiskBake.texRadius(minOf(3840f, 2160f) * MoonlitRenderer.MOON_R_K))
    }

    // ══════════════════ 夹具：源根定位 ══════════════════

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        repeat(6) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 app/src/main/java")
    }

    private fun moonAssetFile(): File {
        val assets = mainSourceRoot().parentFile!!.resolve("assets/globe/moon.jpg")
        assertTrue("G7 复用资产 $assets 不存在", assets.isFile)
        return assets
    }

}

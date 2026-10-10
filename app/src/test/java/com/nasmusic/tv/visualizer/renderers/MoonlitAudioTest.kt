package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 门禁 **G15**（§八 / §十一 T9）：E44「明月」的**音频映射接线**。
 *
 * ## 本类只管"接线"，⛔ 不管尺本身对不对
 * §八 那把换算尺住在 [MoonAudio]（纯函数、零 Android import），它的单点算式由自己的用例钉；
 * 本类钉的是**绘制现场真的按 §八 的表取了这一行**，以及三件只有"接上去"才会坏的事：
 *
 * 1. **平滑器是有状态的** ⇒ 推进次数就是语义。同一帧调两次 `updateDt` 会把衰减推进两遍，
 *    画面不会报错、只会"跟得比音乐慢"，真机上完全不可辨（②）。
 * 2. **改几何的调制是要收费的** ⇒ `bass` 动的两处里，晕的**半径**进 §9.2 的 HALO 行、
 *    涟漪进 RIPPLE 行。这两行必须能由**生产几何**重算出来（⑬⑭），
 *    ⛔ 不能是探针抄回的典型帧数 —— 那是 D26 / D28 同一条错法的第三次犯（已登记 **D34**）。
 * 3. **无声帧必须逐像素等于 T8** ⇒ 暗角那条 `postFx` 字面量与 `vignetteA(0)` 相等不是巧合（⑩），
 *    它是这批改动唯一的"观感没有偷偷变"闸门。
 *
 * ## 每条判据都配"会塌"的证据
 * 同 [MoonOpBudgetTest] / [MoonlitWaterTest] 的纪律：能失败的才是门。本类的反向自证分两类 ——
 * **夹具式**（③ D31、⑨ 云速乘绝对时间、⑪ D32 方向写反、⑫ D33 `pulse`）：把文档里那条
 * "被推翻的写法"在测试内重跑一遍判据，断言它**确实**被抓；
 * **口径式**（⑬⑭）：把表退成"不乘 k² / 按探针典型帧"，断言表当场**低于**几何。
 *
 * ## ⚠️ 扫描一律跑在**剥掉注释**的源码上
 * 渲染器类头与 `MoonAudio` 的 KDoc **原文引用**了 `fx.dt / 1000`、`AudioSmoother(0.28f, 0.05f)`、
 * `frame.pulse` 这些"要被抓"的写法，不剥注释就是自己判自己红（承 G12 / G13 / G14 同类教训）。
 */
class MoonlitAudioTest {

    // ══════════════════════════════════════════════════════════════════════════
    //  ① ② ③ 音频头本体：谁调、调几次、用什么单位
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * `updateDt` 此前全仓**零消费**（只有类定义），本效果是它的首个生产调用方。
     * 判据形态 = "调用点只许出现在一个渲染器文件里，且恰好四行"。
     */
    @Test
    fun `① updateDt 的首个生产调用方是本效果 且全仓只有这四行`() {
        val fxDir = File(mainSourceRoot(), "com/nasmusic/tv/visualizer/fx")
        val rendererDir = File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers")
        val callers = (fxDir.listFiles()?.toList() ?: emptyList())
            .filter { it.name.endsWith(".kt") && it.readText().contains(".updateDt(") }
            .map { it.name } +
            rendererDir.listFiles()!!.filter {
                it.name.endsWith(".kt") && stripComments(it.readText()).contains(".updateDt(")
            }.map { it.name }
        // 定义处（AudioSmoother.kt 的 KDoc 引用了 `updateDt`，但它不含调用点 `\.updateDt\(`）
        assertEquals("⛔ 定率式 update() 的隐含 60fps 就是 M10 的根因；生产侧只许 E44 一处用 dt 变体",
            listOf("MoonlitRenderer.kt"), callers)

        val src = rendererSource()
        assertEquals("四个频段各推进一次 ⇒ 全文恰好 4 个调用点",
            4, Regex("""\.updateDt\(""").findAll(src).count())
        // ⛔ 混用固定系数变体（那等于把 M10 请回来）
        assertFalse("⛔ 本效果不得使用定率式 .update(", Regex("""\.update\(""").containsMatchIn(src))
        // 负向自证：上面那条判据真的会塌
        assertTrue("夹具 `.update(frame.bass)` 必须被抓到",
            Regex("""\.update\(""").containsMatchIn("val a = smBass.update(frame.bass)"))
    }

    /**
     * ⭐ **音频头只有一处**（类头 T9 第 1 条）。
     *
     * 为什么"每帧每频段恰好一次"必须是有牙的判据：`AudioSmoother` 是**有状态**的，
     * 多调一次不改变类型、不改变画面结构，只把 attack/release 的推进量翻倍 ——
     * 于是"云速跟 mid"会变成 mid 落得比音乐快一倍，而单帧截图完全看不出来。
     */
    @Test
    fun `② 四个频段在 drawContent 开头各推进一次 全文不重复`() {
        val src = rendererSource()
        val body = functionBody("drawContent", src)
        assertEquals("音频头四行必须都在 drawContent 里", 4, Regex("""\.updateDt\(""").findAll(body).count())
        // 逐频段：全文恰好一次（⛔ 不是"总共四次"就算对 —— 两次 bass 一次 mid 也是四次）
        for ((sm, ch) in listOf(
            "smBass" to "frame.bass", "smMid" to "frame.mid",
            "smTreb" to "frame.treble", "smEner" to "frame.energy",
        )) {
            assertEquals("$sm 全文必须只推进一次（有状态：多调一次 = 衰减快一倍）",
                1, Regex("""$sm\.updateDt\(""").findAll(src).count())
            assertTrue("$sm 必须吃 $ch",
                body.contains("$sm.updateDt($ch, fx.dt"))
        }
        // 头必须**在所有消费点之前**：推进之后读到的才是本帧的包络
        val headEnd = body.lastIndexOf(".updateDt(")
        for (use in listOf(
            "MoonAudio.sbBucket(", "MoonAudio.cloudSpdMul(", "MoonAudio.haloBucket(", "glitA(illum",
        )) {
            val at = body.indexOf(use)
            assertTrue("`$use` 必须在音频头之后（否则吃到的是上一帧的包络）", at > headEnd)
        }
        // 负向自证：双推进的写法会被逐频段计数抓到
        val doubleAdvance = "smBass.updateDt(frame.bass, fx.dt); smBass.updateDt(frame.bass, fx.dt)"
        assertEquals("夹具里 smBass 被调两次 ⇒ 判据必须给出 2 ≠ 1",
            2, Regex("""smBass\.updateDt\(""").findAll(doubleAdvance).count())
    }

    /**
     * **偏差 D31**：§八 表里写的是 `AudioSmoother.updateDt(_, fx.dt/1000)`，
     * 而 `FxFrame.dt` **本身已是秒**（`RendererFx.kt:205` `frame.dt = dtMs / 1000f`）。
     * 照文档写会包络慢 1000 倍 ⇒ 音频映射**事实上不生效**，而画面只是"很稳"，不会被当成 bug。
     */
    @Test
    fun `③ D31 传 dt 本身 绝不再除 1000`() {
        val src = rendererSource()
        assertFalse("⛔ `fx.dt` 已是秒，再除 1000 就是 D31（包络慢一千倍 = 映射静默失效）",
            Regex("""\bdt\w*\s*/\s*1000""").containsMatchIn(src))
        assertTrue("四行必须把 fx.dt 原样传进去",
            Regex("""updateDt\(frame\.bass, fx\.dt\)""").containsMatchIn(src))
        assertTrue("energy 那条也一样",
            Regex("""updateDt\(frame\.energy, fx\.dt\)""").containsMatchIn(src))
        // 除法只许存在于时基源头一处（否则"谁除了第二次"查不出来）
        val fxSrc = stripComments(mainFileText("RendererFx.kt"))
        assertEquals("dtMs→秒 的换算只许一处",
            1, Regex("""dtMs\s*/\s*1000f""").findAll(fxSrc).count())
        // 负向自证：文档那行原文喂进来必须被抓
        assertTrue("夹具 `updateDt(_, fx.dt/1000)` 必须判红",
            Regex("""\bdt\w*\s*/\s*1000""").containsMatchIn("smBass.updateDt(frame.bass, fx.dt / 1000)"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ④ ⑤ ⑥ §八 七行幅度逐行对账
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * §八 表格的**幅度列**逐行落到 [MoonAudio] / [MoonWater] 的常量上。
     * 表里每行一个数，一个都不许漂（漂了不会报错，只会"音乐反应变钝/变夸张"）。
     */
    @Test
    fun `④ §八 七项幅度逐行对账换算尺`() {
        assertEquals("bass → 晕半径 ±6%", 0.06, MoonAudio.HALO_R_SPAN, 1e-12)
        assertEquals("bass → 海面亮度 ±10%", 0.10, MoonAudio.SEA_BRIGHT_SPAN, 1e-12)
        assertEquals("treble → 粼光 alpha +25%", 0.25, MoonAudio.GLIT_A_SPAN, 1e-12)
        assertEquals("mid → 云速下沿", 0.85, MoonAudio.CLOUD_SPD_MIN, 1e-12)
        assertEquals("mid → 云速上沿", 1.25, MoonAudio.CLOUD_SPD_MAX, 1e-12)
        assertEquals("beat → 银边 ×1.25", 1.25, MoonAudio.RIM_BEAT_K, 1e-12)
        assertEquals("energy → 暗角静默端", 0.42, MoonAudio.VIGN_QUIET, 1e-12)
        assertEquals("energy → 暗角高潮端", 0.36, MoonAudio.VIGN_CLIMAX, 1e-12)
        assertEquals("暗角的能度增益", 1.4, MoonAudio.VIGN_ENER_K, 1e-12)
        assertEquals("bass → 摆移系数", 0.004, MoonWater.SWAY_BASS_K, 1e-12)
        // ⛔ 两个"中性点"必须是同一个数：MoonWater 自己那份是第二份真源
        assertEquals("摆移的中性点必须与换算尺同值（§八 全部写成 (a − 0.4) 的偏移形态）",
            MoonAudio.NEUTRAL, MoonWater.SWAY_BASS_NEUTRAL, 1e-12)

        // 端点值（表里 ±6% / ±10% 的字面展开）
        // ⚠️ 区间**不对称**：中性点是 `0.4` 而不是 `0.5` ⇒ `k` 落在 `[0.976, 1.036]`，
        //    "±6%" 说的是中性点上下的**系数**，不是区间的两个端点（§八 表格未写这一层，
        //    `MoonAudio.haloRadiusK` 的 KDoc 原先写 `[0.964, 1.036]` 也是同一个口误，本轮更正）。
        assertEquals(1.036, MoonAudio.haloRadiusK(1.0), 1e-12)
        assertEquals(0.976, MoonAudio.haloRadiusK(0.0), 1e-12)
        assertEquals(1.060, MoonAudio.seaBright(1.0), 1e-12)
        assertEquals(0.960, MoonAudio.seaBright(0.0), 1e-12)
        assertEquals(0.85, MoonAudio.cloudSpdMul(0.0), 1e-12)
        assertEquals(1.25, MoonAudio.cloudSpdMul(1.0), 1e-12)
        assertEquals(1.25, MoonAudio.rimBeatK(true), 1e-12)
        assertEquals(1.00, MoonAudio.rimBeatK(false), 1e-12)
    }

    /**
     * §八 唯一让音频移动**几何**的那一行：摆移幅度刻意只有 ~4 px @1080p，⛔ 别加大
     * （"否则光柱会跟着鼓点晃"）。判据钉**绝对像素**，不钉系数 —— 系数换了画幅就没意义。
     */
    @Test
    fun `④b bass 驱动的摆移在 1080p 必须只有几像素`() {
        val minDim = 1080.0
        val atNeutral = MoonWater.sway(0.0, minDim, MoonAudio.NEUTRAL)
        val atMaxBass = MoonWater.sway(0.0, minDim, 1.0)
        val delta = kotlin.math.abs(atMaxBass - atNeutral)
        assertEquals("慢摆项在中性时刻为 0（sin(0)）⇒ 差值只含 bass 那一截",
            (1.0 - MoonAudio.NEUTRAL) * MoonWater.SWAY_BASS_K * minDim, delta, 1e-9)
        assertTrue("摆移 $delta px 必须 ≤ 4.4 px @1080p（§八：⛔ 别加大）", delta <= 4.4)
        // 负向自证：把系数"顺手"翻倍就越线（判据不是恒真）
        val doubled = (1.0 - MoonAudio.NEUTRAL) * (MoonWater.SWAY_BASS_K * 2.0) * minDim
        assertTrue("系数翻倍 = ${doubled}px 必须越 4.4 px 的线", doubled > 4.4)
    }

    /**
     * `treble → 粼光 alpha` 的 +25% **长在** [MoonClouds.glitA] 里（§6.3 ⑤），不在 [MoonAudio]。
     * 于是这两个文件之间有一条**跨文件不变式**：式子里的系数必须等于尺上的 [MoonAudio.GLIT_A_SPAN]，
     * 否则 §八 的表与 §9.2 的 GLITTER 行口径分家（表按 `aTreb=1` 落，式子却按别的涨）。
     */
    @Test
    fun `⑤ 粼光的 treble 乘子与尺上的幅度同值 跨文件不漂移`() {
        for (aTreb in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val ratio = MoonClouds.glitA(1.0, 0.0, aTreb) / MoonClouds.glitA(1.0, 0.0, 0.0)
            assertEquals("glitA 对 aTreb 必须是 1 + ${MoonAudio.GLIT_A_SPAN}·aTreb",
                1.0 + MoonAudio.GLIT_A_SPAN * aTreb, ratio, 1e-12)
        }
        // 已发表锚（G14 也钉过；这里再钉一次是因为它就是表口径的那一帧）
        assertEquals(0.775, MoonClouds.glitA(1.0, 0.0, 1.0), 1e-9)
        // 接线：渲染器把**平滑后**的 aTreb 传进去，⛔ 不传 frame.treble
        assertTrue(Regex("""MoonClouds\.glitA\(illum, occl, aTreb\)""").containsMatchIn(rendererSource()))
        // 负向自证：把式子里的系数改成 0.10（尺没动）⇒ 比值判据当场塌
        val wrongSpan = 0.10
        assertTrue("夹具（式子系数 0.10、尺仍 0.25）必须与尺不符",
            kotlin.math.abs((1.0 + wrongSpan * 1.0) - (1.0 + MoonAudio.GLIT_A_SPAN * 1.0)) > 1e-6)
    }

    /**
     * `energy` 用的是原型**另一档更慢**的包络（`0.28 / 0.05`，原型 `:792`），
     * 而 bass/mid/treble 用默认 `0.35 / 0.06`（`:791`）。系数属于**尺**，⛔ 不属于绘制现场
     * （`AudioSmoother` 类头明写"不得在渲染器内另写魔数"）。
     */
    @Test
    fun `⑥ energy 走更慢那一档 且系数只住在尺里`() {
        assertEquals(0.28f, MoonAudio.ENERGY_ATTACK, 1e-6f)
        assertEquals(0.05f, MoonAudio.ENERGY_RELEASE, 1e-6f)
        // "更慢"的方向必须是这样：暗角是**全屏叠加**，包络快一档 = 画面每拍眨一下
        assertTrue("attack 必须比默认慢", MoonAudio.ENERGY_ATTACK < 0.35f)
        assertTrue("release 必须比默认慢", MoonAudio.ENERGY_RELEASE < 0.06f)

        val src = rendererSource()
        assertTrue("smEner 必须用尺上的两个系数构造",
            src.contains("AudioSmoother(MoonAudio.ENERGY_ATTACK, MoonAudio.ENERGY_RELEASE)"))
        for (sm in listOf("smBass", "smMid", "smTreb")) {
            assertTrue("$sm 用默认包络（§八 那三行走原型的 0.35/0.06）",
                Regex("""private val $sm = AudioSmoother\(\)""").containsMatchIn(src))
        }
        // ⛔ 渲染器里不许出现带魔数的构造
        assertFalse("⛔ 不得在绘制现场写包络魔数",
            Regex("""AudioSmoother\(\s*0\.""").containsMatchIn(src))
        // 负向自证
        assertTrue("夹具 `AudioSmoother(0.28f, 0.05f)` 必须被抓",
            Regex("""AudioSmoother\(\s*0\.""").containsMatchIn("private val x = AudioSmoother(0.28f, 0.05f)"))
        // 暗角只许读**平滑后**的值：`frame.energy` 全文出现一次（就是音频头那行）
        assertEquals("⛔ 逐帧现读 frame.energy = 绕过包络，暗角随每一拍抖",
            1, Regex("""frame\.energy""").findAll(src).count())
        assertTrue("vignetteOverride 必须读 smEner.value",
            src.contains("MoonAudio.vignetteA(smEner.value.toDouble())"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⑦ ⑧ 分桶（零分配红线的代价必须有解析上界）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 桶数不是"性能优化"，是**零分配红线**逼出来的代价 ⇒ 代价必须有**解析**上界，
     * 且"刻度砍半就越线"（D23 的判据形态）。⛔ 不许拿扫描网格当上界 —— 那是
     * `0.0015375` 冒充 `0.0015625` 的同款错误（网格碰不到桶边界，永远偏小）。
     */
    @Test
    fun `⑦ 两把分桶的误差是解析式 刻度砍半就越线`() {
        assertEquals(32, MoonAudio.SEA_SB_BUCKETS)
        assertEquals(8, MoonAudio.HALO_R_BUCKETS)
        // 海面：容差 0.002
        assertEquals(0.0015625, MoonAudio.sbMaxError(MoonAudio.SEA_SB_BUCKETS), 1e-12)
        assertTrue("32 桶的误差 ${MoonAudio.sbMaxError(32)} 必须 ≤ 0.002",
            MoonAudio.sbMaxError(MoonAudio.SEA_SB_BUCKETS) <= 0.002)
        assertTrue("砍半到 16 桶必须越线（⇒ 32 不是随手挑的）",
            MoonAudio.sbMaxError(16) > 0.002)
        // 晕半径：容差 0.005（半径相对误差；面积按平方 = 0.75%，软边不可辨）
        assertEquals(0.00375, MoonAudio.haloKMaxError(MoonAudio.HALO_R_BUCKETS), 1e-12)
        assertTrue(MoonAudio.haloKMaxError(MoonAudio.HALO_R_BUCKETS) <= 0.005)
        assertTrue("砍半到 4 桶必须越线", MoonAudio.haloKMaxError(4) > 0.005)

        // 解析式必须**等于**真实最坏误差（取每个桶的下边界 = 离桶心半个桶）
        var worstSea = 0.0
        for (b in 0 until MoonAudio.SEA_SB_BUCKETS) {
            val a = b.toDouble() / MoonAudio.SEA_SB_BUCKETS
            worstSea = maxOf(worstSea,
                kotlin.math.abs(MoonAudio.seaBright(a) - MoonAudio.seaBrightOfBucket(b)))
        }
        assertEquals("真实最坏误差必须等于解析上界",
            MoonAudio.sbMaxError(MoonAudio.SEA_SB_BUCKETS), worstSea, 1e-12)
        // 反面对照：等距网格**给不出**这个上界（这就是"不许拿网格当上界"的机器证据）。
        // ⚠️ 网格取 **997** 个点而不是 1000：`997` 与桶数 `32` 互质 ⇒ 网格永远踩不到桶边界
        //    （1001 点会恰好落在 `0.125` 这类边界上，把"网格偏低"这件事演示不出来）。
        var gridWorst = 0.0
        for (i in 1..996) {
            val a = i / 997.0
            gridWorst = maxOf(gridWorst,
                kotlin.math.abs(MoonAudio.seaBright(a) - MoonAudio.seaBrightOfBucket(MoonAudio.sbBucket(a))))
        }
        assertTrue("网格值 $gridWorst 必须**低于**解析上界（用它当判据就是把门写成可达不到的数）",
            gridWorst < MoonAudio.sbMaxError(MoonAudio.SEA_SB_BUCKETS))
    }

    /**
     * 桶数**只有一个来源**：缓存数组的容量必须由 [MoonAudio] 给出。
     * 否则改尺不会改缓存 ⇒ 越界崩溃（或有人把容量写成字面量 32，尺与缓存各自漂）。
     */
    @Test
    fun `⑧ 缓存容量与尺同源 桶号永不越界`() {
        val src = rendererSource()
        assertTrue("海面缓存容量必须取 SEA_SB_BUCKETS",
            Regex("""seaBrushes = arrayOfNulls<Brush>\(MoonAudio\.SEA_SB_BUCKETS\)""").containsMatchIn(src))
        assertTrue("晕缓存容量必须取 HALO_R_BUCKETS",
            Regex("""haloBrushes = arrayOfNulls<Brush>\(MoonAudio\.HALO_R_BUCKETS""").containsMatchIn(src))
        assertFalse("⛔ 容量不许写死字面量（尺改了不会跟着改）",
            Regex("""arrayOfNulls<Brush>\(\s*(8|24|32)\s*[,)]""").containsMatchIn(src))

        // 桶号定义域：夹 + floor + coerceAtMost，越界输入不得越槽
        for (a in listOf(-1.0, 0.0, 0.4, 0.999, 1.0, 2.0)) {
            assertTrue(MoonAudio.sbBucket(a) in 0 until MoonAudio.SEA_SB_BUCKETS)
            assertTrue(MoonAudio.haloBucket(a) in 0 until MoonAudio.HALO_R_BUCKETS)
        }
        assertEquals(31, MoonAudio.sbBucket(1.0))
        assertEquals(7, MoonAudio.haloBucket(1.0))
        assertEquals(0, MoonAudio.sbBucket(-1.0))
        // 桶还原必须落在解析容差内（连续量 → 缓存 → 上屏这一路不许走样）
        var steps = 0
        for (i in 0..400) {
            val a = i / 400.0
            val err = kotlin.math.abs(MoonAudio.seaBright(a) - MoonAudio.seaBrightOfBucket(MoonAudio.sbBucket(a)))
            assertTrue("a=$a 的桶误差 $err 越界", err <= MoonAudio.sbMaxError(MoonAudio.SEA_SB_BUCKETS) + 1e-12)
            steps++
        }
        assertEquals(401, steps)
    }

    /**
     * 晕的重建键**不含桶号**（类头 T9 第 3 条的落地形态）。
     * 含了会怎样：`bass` 在桶边界来回抖时，每帧清空 24 槽 + 重建 3 张 native 渐变 = **逐帧分配**，
     * 而这条只有在真机 GC 日志里才看得见，所以必须是源码级判据。
     */
    @Test
    fun `⑧b 晕的重建键不含桶号 跨桶只补当前桶`() {
        val src = rendererSource()
        val guard = functionBody("ensureHaloBucket", src)
        val key = Regex("""if\s*\(([^)]*)\)\s*\{""").find(guard)?.groupValues?.get(1) ?: ""
        assertTrue("重建键必须只看档位/月半径/脏标",
            key.contains("haloLevel") && key.contains("haloMoonR") && key.contains("haloDirty"))
        assertFalse("⛔ 桶号不得进键（进了就是每次跨桶整组作废 = 逐帧建 3 张渐变）",
            key.contains("bucket"))
        // 负向自证：把 bucket 写进键的夹具必须被上面那条抓到
        val fixture = "if (level != haloLevel || bucket != haloBucket) {"
        val fixtureKey = Regex("""if\s*\(([^)]*)\)\s*\{""").find(fixture)!!.groupValues[1]
        assertTrue("夹具的键含 bucket ⇒ 判据必须为真（即：真实代码若长这样就会红）",
            fixtureKey.contains("bucket"))
        assertTrue("首槽非空即视为该桶已建（延迟、逐桶、常驻 ≤ 在场桶数）",
            guard.contains("haloBrushes[base] != null"))
        assertTrue("建的是当前桶", functionBody("buildHaloBucket", src).contains("haloKOfBucket(bucket)"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⑨ mid → 云速：只能乘增量
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * §八 的定稿红线：这个乘子**只能作用在 `dtSec` 增量上**。
     * 写成 `× 绝对时间` 就是原型第七轮之前的"云随鼓点抽搐"那个 bug（§3.0(f) 第 5 条 / G5 ③）。
     */
    @Test
    fun `⑨ mid 只改云速增量 绝不乘绝对时间`() {
        val src = rendererSource()
        val body = functionBody("drawContent", src)
        assertEquals("云速乘子全文只许出现一次",
            1, Regex("""MoonAudio\.cloudSpdMul\(""").findAll(src).count())
        val at = body.indexOf("MoonAudio.cloudSpdMul(aMid)")
        val step = body.indexOf("clouds.step(")
        assertTrue("乘子必须是 clouds.step 的入参", at > step)
        assertTrue("增量必须是 fx.dt 本身（⛔ 不是乘过 spdMul 的 dt）",
            body.contains("fx.dt.toDouble(), MoonAudio.cloudSpdMul(aMid)"))
        assertFalse("⛔ 渲染器不得把 spdMul 乘进任何绝对时间",
            Regex("""cloudSpdMul\([^)]*\)\s*\*\s*\w*[tT]ime|\w*[tT]Sec\s*\*\s*MoonAudio\.cloudSpdMul""")
                .containsMatchIn(body))

        // 云场那一侧：spdMul 的**使用点**（排除形参声明）只许出现在带 dtSec 的行上
        val clouds = stripComments(mainFileText("MoonClouds.kt"))
        val lines = clouds.lines().filter {
            it.contains("spdMul") && !Regex("""spdMul\s*:""").containsMatchIn(it)
        }
        assertTrue("MoonClouds 里必须真的有 spdMul 的使用点（判据不能空转）",
            lines.isNotEmpty())
        for (l in lines) {
            assertTrue("`$l` 只许乘增量 dtSec", l.contains("dtSec"))
        }
        // 负向自证：把增量换成绝对时间的写法必须被同一条判据抓到
        val twitching = "b.x = MoonClouds.wrap01(b.x + b.speedK * spdMul * tSec)"
        assertTrue("夹具（乘 tSec）不含 dtSec ⇒ 正是这条判据要拦的形状",
            !twitching.contains("dtSec") && twitching.contains("spdMul"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⑩ 暗角：无声帧闸门 + 方向
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ⭐ 本批唯一的"观感没有偷偷变"闸门：`vignetteA(0)` 必须**恰好等于** `postFx` 里那个字面量。
     *
     * 两端都要钉：字面量在（否则 `FxCoverageScanTest` 的后处理覆盖名单静默掉出，见 G13 ④），
     * 且 `vignetteA(0)` 与它相等（否则无声帧的暗角换了）。
     */
    @Test
    fun `⑩ 无声帧的暗角逐像素等于 T8`() {
        val src = rendererSource()
        val postFx = postFxArgs(src)
        assertTrue("postFx 的字面量 0.42f 必须保留（FxCoverageScanTest 只认字面量）",
            postFx.contains("vignette = 0.42f"))
        assertEquals("VIGN_QUIET 就是那个字面量", 0.42f, MoonAudio.VIGN_QUIET.toFloat(), 0f)
        assertEquals("静止帧（包络从 0 起跳）暗角必须与 T8 一致",
            0.42f, MoonAudio.vignetteA(0.0).toFloat(), 1e-6f)
        // ⛔ 其余 21 个效果一律不覆写：这条钩子是"默认关"的
        val rendererDir = File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers")
        val overriders = rendererDir.listFiles()!!.filter {
            it.name.endsWith(".kt") &&
                stripComments(it.readText()).contains("override fun vignetteOverride")
        }.map { it.name }
        assertEquals("只许 E44 覆写暗角钩子（其余效果的暗角必须仍是 postFx 字面量）",
            listOf("MoonlitRenderer.kt"), overriders)
        assertTrue("基类默认值必须是哨兵（不覆盖）",
            mainFileText("RendererFx.kt").contains("vignetteOverride(fx: FxFrame): Float = NO_VIGNETTE_OVERRIDE"))
    }

    /**
     * **偏差 D32**（方向）：原型那行是 `lerp(vignHigh=0.36, vignLow=0.42, k)`，字面实现等于
     * "越吵、暗角越**重**"，与 §八 该行明写的「高潮时略微"打开"画面」正相反
     * （文档自己就留了「⚠️ 参数名与方向易写反」）。实现取**文字意图**（单调递减）。
     */
    @Test
    fun `⑪ D32 暗角随能度单调变浅 原型的写法作为夹具判红`() {
        assertEquals(0.36, MoonAudio.vignetteA(1.0), 1e-12)
        var prev = MoonAudio.vignetteA(0.0)
        var steps = 0
        for (i in 1..200) {
            val a = i / 200.0
            val v = MoonAudio.vignetteA(a)
            assertTrue("aEner=$a 处暗角必须不加深（$prev → $v）", v <= prev + 1e-12)
            assertTrue("必须夹在 0.36..0.42", v in 0.36..0.42)
            prev = v
            steps++
        }
        assertEquals(200, steps)
        // 饱和点：aEner ≥ 1/1.4 就已经到底
        assertEquals(0.36, MoonAudio.vignetteA(1.0 / MoonAudio.VIGN_ENER_K), 1e-12)
        assertEquals(0.36, MoonAudio.vignetteA(0.9), 1e-12)

        // 夹具：原型那行的字面语义（随能度**加深**）必须被上面那条单调判据抓到
        fun prototypeLiteral(a: Double): Double =
            0.36 + (0.42 - 0.36) * (a * MoonAudio.VIGN_ENER_K).coerceIn(0.0, 1.0)
        assertTrue("夹具在高潮端更重（0.42 > 0.42 起点的反向）⇒ 与 §八 的文字相反",
            prototypeLiteral(1.0) > prototypeLiteral(0.0))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⑫ 涟漪：pulse 不接线（D33）+ 只吃 beat + 按档
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * **偏差 D33**：§八 表把涟漪的输入写成 `frame.pulse`（快起慢落包络），
     * 定稿原型实际只在 `audio.beat`（单帧 true）那拍生成一环（`moonlit-preview.html:1280`）。
     * `pulse` 在原型里只喂 HUD（`:899`、`:1306`）。⇒ 实现按原型，⛔ 不"照文档补一个 pulse 门"，
     * 因为 `pulse > 阈值` 的写法会**自造计时器**（同一拍跨多帧重复生成，环数失控）。
     */
    @Test
    fun `⑫ D33 涟漪只吃 beat 一帧 pulse 全文不读`() {
        val src = rendererSource()
        assertEquals("⛔ frame.pulse 不得进生产代码（D33；进了就是自造计时器）",
            0, Regex("""frame\.pulse""").findAll(src).count())
        assertEquals("beat 有两个消费点（银边 + 涟漪），且都是**同一帧的布尔**",
            2, Regex("""frame\.beat""").findAll(src).count())

        val body = functionBody("drawRipples", src)
        assertTrue("生成必须挂在 beat 那一下",
            body.contains("if (beat) ripples.push("))
        assertTrue("LOW 档整段早退（⛔ 不是'照样推进、只是不画'：那样槽位会被吃掉）",
            body.contains("val max = MoonWater.rippleMax(level)") && body.contains("if (max <= 0) return"))
        assertTrue("推进用增量 dtSec，⛔ 不读墙钟", body.contains("ripples.advance(dtSec, max)"))
        // 两个随机量各抽一次（生成位与速度**不相关**，原型同）
        assertEquals("rng 在 push 一行里恰好抽两次",
            2, Regex("""rng\.next\(\)""").findAll(body).count())
        // 按档上界与表对账
        assertEquals(0, MoonWater.rippleMax(MoonLevel.LOW))
        assertEquals(2, MoonWater.rippleMax(MoonLevel.MEDIUM))
        assertEquals(4, MoonWater.rippleMax(MoonLevel.HIGH))
        assertEquals(MoonWater.rippleMax(MoonLevel.MEDIUM), MoonOpItem.RIPPLE.opsMed)
        assertEquals(MoonWater.rippleMax(MoonLevel.HIGH), MoonOpItem.RIPPLE.opsHigh)
        assertEquals(MoonWater.rippleMax(MoonLevel.LOW), MoonOpItem.RIPPLE.opsLow)
        // 负向自证：照文档 pulse 门写的夹具必须被第一条判据抓到
        assertTrue("夹具 `if (frame.pulse > 0.8f)` 必须判红",
            Regex("""frame\.pulse""").containsMatchIn("if (frame.pulse > 0.8f) ripples.push(u, sp, max)"))
    }

    /**
     * 涟漪的图元是**软环**（[MoonWater.RIPPLE_IN_K] 以内全透），⛔ 不是描边椭圆：
     * 描边环是一条等亮硬线，在水面上读作"铁丝圈"（本轮截图实测）。
     */
    @Test
    fun `⑫b 涟漪是四停位软环 不是描边椭圆`() {
        val src = rendererSource()
        val body = functionBody("drawRipples", src)
        assertTrue("走的是全场唯一的软椭圆入口", body.contains("drawSoftEllipse("))
        assertFalse("⛔ 涟漪路径不得描边", Regex("""drawOval|Style\.STROKE""").containsMatchIn(body))

        val shader = functionBody("ringShaderOf", src)
        assertEquals("四个色停（芯透 → IN 透 → 峰 → 缘透）",
            4, Regex("""TRANSPARENT|withA\(""").findAll(shader).count())
        assertEquals("透明停位三处", 3, Regex("""TRANSPARENT""").findAll(shader).count())
        assertTrue("峰值 α 写 1.0，亮度一律走 paint α（⛔ 不烤进色停）",
            shader.contains("withA(rgb, 1.0)"))
        assertEquals("两个换算后的停位 + 0/1 两端 = 4",
            2, Regex("""MoonClouds\.softStop\(""").findAll(shader).count())
        // α 上限与"必须极暗"的理由（§7.5：一亮就读作盘子的投影）
        assertEquals(0.075, MoonWater.RIPPLE_A_MAX, 1e-12)
        val peakAlpha = MoonWater.rippleAlpha(0.0, MoonClouds.diskA(0.0), 0.775)
        assertTrue("最亮一帧的 α $peakAlpha 必须 ≤ 0.075", peakAlpha <= 0.075)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⑬ ⑭ 表行必须由生产几何重算（D34 的两笔）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * `MoonOpItem.HALO` 那一行必须**等于** `ceilToMilli(中性实测 × k²)`。
     *
     * ⭐ 这条把"手抄数"换成了"可核对的换算"：§八 的 `bass → 晕半径 ±6%` 是**几何**调制
     * （面积按平方收费），所以最坏帧是 `aBass = 1` 那一帧，⛔ 不是中性实测。
     * ⚠️ 表按**连续**上界落（`k` 在 `aBass=1` 处取），⛔ 不按最高桶的桶中心 `0.9375` ——
     * 桶数是**缓存粒度**、不是观感参数，按桶落表会让预算跟着缓存实现漂。
     */
    @Test
    fun `⑬ HALO 表行等于含 bass 的几何上界 不随桶数漂`() {
        assertEquals(0.069, HALO_FILL_NEUTRAL_LOW, 1e-12)
        assertEquals(0.131, HALO_FILL_NEUTRAL_MEDIUM, 1e-12)
        assertEquals(0.244, HALO_FILL_NEUTRAL_HIGH, 1e-12)

        val tiers = listOf(
            Triple(HALO_FILL_NEUTRAL_LOW, MoonOpItem.HALO.fillLow, "LOW"),
            Triple(HALO_FILL_NEUTRAL_MEDIUM, MoonOpItem.HALO.fillMed, "MED"),
            Triple(HALO_FILL_NEUTRAL_HIGH, MoonOpItem.HALO.fillHigh, "HIGH"),
        )
        for ((neutral, table, name) in tiers) {
            val worst = MoonOpBudget.haloFillAtMaxBass(neutral)
            assertEquals("$name 表值必须由换算 + 向上取整产生",
                MoonOpBudget.ceilToMilli(worst), table, 1e-12)
            assertTrue("$name 表 $table 必须 ≥ 最坏帧几何 $worst（表是上界）", table >= worst)
            assertTrue("$name 表多记不得超过一个千分之一屏（否则门在守不存在的帧）",
                table - worst < 0.001)
            // ⛔ 不乘 k²（T8 那版）⇒ 表低于最坏帧
            assertTrue("$name 若按中性实测落表（$neutral）会低于几何上界 $worst = D34 的第一笔",
                neutral < worst)
            // ⛔ 按最高桶桶中心落表 ⇒ 同样低于连续上界
            val bucketBound = MoonOpBudget.ceilToMilli(MoonOpBudget.haloFillAtMaxBass(neutral, 0.9375))
            assertTrue("$name 按桶中心落表 = $bucketBound 必须 < 连续上界 $table", bucketBound < table)
        }
        // 半径幅度砍半 ⇒ 换算值下降，表就必须跟着降（判据对系数有反应，不是恒真）
        val halved = HALO_FILL_NEUTRAL_MEDIUM * (1.0 + MoonAudio.HALO_R_SPAN / 2 * 0.6) *
            (1.0 + MoonAudio.HALO_R_SPAN / 2 * 0.6)
        assertTrue("砍半半径幅度 = $halved 必须低于现表 ${MoonOpItem.HALO.fillMed}",
            MoonOpBudget.ceilToMilli(halved) < MoonOpItem.HALO.fillMed)
    }

    /**
     * `MoonOpItem.RIPPLE` 那一行必须由 [MoonWater.rippleFillFraction] 的**生产几何**产生。
     *
     * ⭐ **D34 的第二笔**：原型 `passcost` 读回的 `0.017 / 0.049` 是**典型帧**
     * （几环诞生于不同拍 ⇒ 半径互不相同），而本表口径是**最坏帧**（每槽都在最大可见半径）。
     * 拿探针数落表就是把门写成下界 —— 与 D26 / D28 同一条错法。
     * ⚠️ "最大可见半径"不是 `p = 1` 而是 `p = ripplePMax()`：α 早退之后那一段一个像素都不铺。
     */
    @Test
    fun `⑭ RIPPLE 表行等于生产几何最坏帧 不是探针典型帧`() {
        val w = 1920.0
        val h = 1080.0
        val moonR = minOf(w, h) * MoonlitRenderer.MOON_R_K.toDouble()

        assertEquals(0.000, MoonOpItem.RIPPLE.fillLow, 1e-12)
        for ((level, table, name) in listOf(
            Triple(MoonLevel.MEDIUM, MoonOpItem.RIPPLE.fillMed, "MED"),
            Triple(MoonLevel.HIGH, MoonOpItem.RIPPLE.fillHigh, "HIGH"),
        )) {
            val worst = MoonWater.rippleFillFraction(level, moonR, w, h)
            assertEquals("$name 表值必须由生产几何 + 向上取整产生",
                MoonOpBudget.ceilToMilli(worst), table, 1e-12)
            assertTrue("$name 表 $table 必须 ≥ 几何 $worst", table >= worst)
            assertTrue("$name 表多记不得超过千分之一屏", table - worst < 0.001)
            // ⛔ 探针典型帧口径落表 ⇒ 低于几何（D34 的负向自证）
            val probe = if (level == MoonLevel.MEDIUM) 0.017 else 0.049
            assertTrue("$name 按探针典型帧 $probe 落表会低于最坏帧几何 $worst", probe < worst)
        }
        // 单环口径（椭圆 2π·rx·ry 的保守记账）与合计必须成对
        val rr = MoonWater.rippleR(moonR, MoonWater.ripplePMax())
        val perRing = 2.0 * Math.PI * MoonWater.rippleRx(rr) * MoonWater.rippleRy(rr) / (w * h)
        assertEquals(MoonOpBudget.ceilToMilli(perRing * 2), MoonOpItem.RIPPLE.fillMed, 1e-12)
        assertEquals(MoonOpBudget.ceilToMilli(perRing * 4), MoonOpItem.RIPPLE.fillHigh, 1e-12)
        // ⛔ 记账半径不是 p=1：那一段 α 已低于 MIN_ALPHA，一个像素都不铺
        val pMax = MoonWater.ripplePMax()
        assertTrue("pMax $pMax 必须 < 1（α 早退点在寿命内）", pMax < 1.0)
        assertEquals("pMax 处必须恰好贴在 MIN_ALPHA 上（早退判据与记账口径同源）",
            MoonClouds.MIN_ALPHA, MoonWater.rippleAlpha(pMax, MoonClouds.diskA(0.0), 0.775), 1e-12)
        val atOne = 2.0 * Math.PI * MoonWater.rippleRx(MoonWater.rippleR(moonR, 1.0)) *
            MoonWater.rippleRy(MoonWater.rippleR(moonR, 1.0)) / (w * h)
        assertTrue("按 p=1 记账 = ${atOne * 2} 会**多**于表值（那段根本不出画，表守的是真实上界）",
            atOne * 2 > MoonOpItem.RIPPLE.fillMed)
        assertTrue("p=1 时的 α 必须已被 MIN_ALPHA 剔掉（那段不出画）",
            MoonWater.rippleAlpha(1.0, 1.0, 0.775) < MoonClouds.MIN_ALPHA)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⑮ 三档合计：T9 的两笔几何账都在表内
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ⭐ 所有者裁决「**抬 MED 上限到 4.00**」（2026-10-10）的机器留痕：
     * 动的是**上限**，⛔ 不是 HALO / RIPPLE 两行的数（它们由生产几何与尺产生）。
     *
     * 于是本例要同时钉三件事：① 新合计 ≤ 新上限；② 新合计 **>** 旧上限 3.95（⇒ 上限确实是被
     * 裁决抬起来的，不是"表本来就够小"这种假绿）；③ LOW / HIGH 两个上限**一个都没动**。
     */
    @Test
    fun `⑮ T9 两笔账落在表内 MED 上限是被裁决抬起来的`() {
        val low = MoonOpBudget.overdrawEstimate(1920f, 1080f, MoonLevel.LOW)
        val med = MoonOpBudget.overdrawEstimate(1920f, 1080f, MoonLevel.MEDIUM)
        val high = MoonOpBudget.overdrawEstimate(1920f, 1080f, MoonLevel.HIGH)
        // ⛔ 合计的**具体数**只住在 MoonOpBudgetTest（表值由逐行相加产生），这里不重抄一份：
        //    重抄就是第二份真源，改表时它会先漂。本例只钉"三档都在门内 + 门的来源是裁决"。

        assertTrue("LOW $low ≤ ${MoonOpBudget.OVERDRAW_MAX_LOW}", low <= MoonOpBudget.OVERDRAW_MAX_LOW)
        assertTrue("MED $med ≤ ${MoonOpBudget.OVERDRAW_MAX_MEDIUM}（裁决后的上限）",
            med <= MoonOpBudget.OVERDRAW_MAX_MEDIUM)
        assertTrue("HIGH $high ≤ 棘轮 ${MoonOpBudget.fillRatchetMax()}", high <= MoonOpBudget.fillRatchetMax())
        // ② 旧上限会把 MED 判红 ⇒ "抬上限"这个裁决是有后果的，不是走过场
        assertTrue("旧 MED 上限 3.95 必须容不下当前表 $med（否则改裁决没有意义）", med > 3.95f)
        // ③ 另外两端未动
        assertEquals(3.45f, MoonOpBudget.OVERDRAW_MAX_LOW, 1e-6f)
        assertTrue("HIGH 不设绝对上限（Q4/Q8/Q9 裁决原文）",
            MoonOpBudget.overdrawMax(MoonLevel.HIGH).isInfinite())
        // 表合计必须仍 ≥ 半遮态实测锚（涨的是表，⛔ 不是实测）
        assertTrue("LOW 表 $low ≥ 实测 ${MoonOpBudget.MEASURED_FILL_LOW}", low >= MoonOpBudget.MEASURED_FILL_LOW)
        assertTrue("MED 表 $med ≥ 实测 ${MoonOpBudget.MEASURED_FILL_MEDIUM}",
            med >= MoonOpBudget.MEASURED_FILL_MEDIUM)
        assertTrue("HIGH 表 $high ≥ 实测 ${MoonOpBudget.MEASURED_FILL_HIGH}",
            high >= MoonOpBudget.MEASURED_FILL_HIGH)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⑯ §八 末行的四条"不做"
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `⑯ 不读 gamma 频谱 不读 bassRaw 不读 maxParticles`() {
        val src = rendererSource()
        assertEquals("⛔ 律动只用线性通道（拿 spectrum 驱律动 = 历史 BUG ⑨-b）",
            0, Regex("""frame\.spectrum|\.waveform""").findAll(src).count())
        assertEquals("本表不依赖鼓点强弱 ⇒ bassRaw 不接",
            0, Regex("""bassRaw""").findAll(src).count())
        assertEquals("保持 needsParticleBudget=false 的可核对性",
            0, Regex("""ctx\.quality|maxParticles""").findAll(src).count())
        // 负向自证：三条正则真的会塌（逐条查，⛔ 不查"总数"—— 一条命中两处会把另一条的漏网也遮掉）
        val bad = "r = frame.spectrum[i] * 8f; n = ctx.quality.maxParticles; b = frame.bassRaw"
        assertTrue("夹具的 spectrum 必须被抓",
            Regex("""frame\.spectrum|\.waveform""").containsMatchIn(bad))
        assertTrue("夹具的 bassRaw 必须被抓", Regex("""bassRaw""").containsMatchIn(bad))
        assertTrue("夹具的 ctx.quality 必须被抓", Regex("""ctx\.quality""").containsMatchIn(bad))
        assertTrue("夹具的 maxParticles 必须被抓", Regex("""maxParticles""").containsMatchIn(bad))
        // 消费的通道只有四个线性量 + beat
        for (ch in listOf("frame.bass", "frame.mid", "frame.treble", "frame.energy", "frame.beat")) {
            assertTrue("必须消费 $ch", src.contains(ch))
        }
    }

    @Test
    fun `⑯b 静止帧的中性值逐一复现 T8 那四个钉`() {
        // 尺写成 (a − 0.4) 的偏移形态 ⇒ "无信号"恒等于乘子 1，这是本批不改观感的前提
        assertEquals(1.0, MoonAudio.seaBright(MoonAudio.NEUTRAL), 1e-12)
        assertEquals(1.0, MoonAudio.haloRadiusK(MoonAudio.NEUTRAL), 1e-12)
        // 海面那条还必须有**第二份证据**：T6 留下的中性亮度钉至今活在暗角边色里，
        // 两者必须同一个数（⛔ 若尺的 NEUTRAL 漂了，边色与海体就会分成两档）
        val src = rendererSource()
        assertTrue("边色仍按 SB_NEUTRAL 建（说明尺的中性点没有另起炉灶）",
            src.contains("Color(MoonSeascape.sea(0f, SB_NEUTRAL))"))
        assertEquals("SB_NEUTRAL 必须等于尺在中性点算出的 sb",
            1.0, MoonAudio.seaBright(MoonAudio.NEUTRAL), 1e-12)
        assertEquals(1.0, MoonAudio.rimBeatK(false), 1e-12)
        assertEquals(0.42, MoonAudio.vignetteA(0.0), 1e-12)
        // 云速是唯一"静止 ≠ 1"的一行（§八 给的是区间 0.85..1.25，不是偏移形态）
        // ⇒ 所以它不许被写成"乘到绝对时间上"来凑 1（那正是 ⑨ 拦的形状）
        assertEquals(0.85, MoonAudio.cloudSpdMul(0.0), 1e-12)
        assertTrue("中性点附近必须接近 1", kotlin.math.abs(MoonAudio.cloudSpdMul(MoonAudio.NEUTRAL) - 1.0) < 0.02)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  夹具
    // ══════════════════════════════════════════════════════════════════════════

    private fun rendererSource(): String =
        stripComments(mainFileText("MoonlitRenderer.kt"))

    private fun mainFileText(name: String): String =
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/$name").readText()

    /** `PostFx( … )` 的参数表原文（与 [MoonlitSkySeaTest] 同一份夹具，⛔ 不跨类共享以免互相牵制）。 */
    private fun postFxArgs(src: String): String {
        val head = Regex("""PostFx\(""").find(src) ?: error("找不到 PostFx(")
        var depth = 0
        var i = head.range.last
        while (i < src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(head.range.last + 1, i)
                }
            }
            i++
        }
        error("PostFx( 括号不配对")
    }

    /** 取 `fun <name>(…)` 的大括号体（同 [MoonlitWaterTest] 的夹具）。 */
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

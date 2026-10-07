package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.visualizer.fx.ProceduralTexture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E35 光轴（§B10 · T4.10）门禁。
 *
 * 五段：
 * - **迁移段**：`: RendererFx()` + 内容钩子 + 不覆写 `final` + ⛔ 两条旧时钟红线（`lastMs == 0L`
 *   哨兵 / `frame.timeMs` 当相位）已清；
 * - **观感段（§B10-①~④）**：锥形渐变光束（`withTransform` 逐束旋转）/ `Id.FOG` 体积雾（过扫描漂移）
 *   / 3 条 `Path` 合批尘埃 / 镜头光斑（1 次 `shadeBrushCached` + 3 条 `drawLine`）；
 * - **dt 段**：`advanceBeamAngle` 在 30 / 60 / 120 fps 下**行为恒等**（直调生产纯函数）；
 * - **性能段**：每帧函数体内零堆分配；
 * - **名单段**：`FxCoverageScanTest.covered` 已含本类。
 *
 * ⛔ 负向自证 2 条（N1 / N2）—— 缺一条就可能空转。
 * ⛔ 源码判据一律**先剥注释**（本仓库已踩 5+ 次：判据命中自己刚写的 KDoc ⇒ 假 FAIL）；
 *   且**必须**加「原文含 / 剥后不含」的成对自证（本类的 KDoc 正文明写了那两条旧写法）。
 * ⛔ 负向样本必须与正向**喂同一份谓词**。
 * ⛔ 行为段**直调生产纯函数**（`advanceBeamAngle` / `beamAngleOf` / `dustBucketOf` / `wrap01` /
 *   `driftDelta`）与生产 `fogRow`，**不复制算法** —— 复制必然漂移。
 */
class LightBeamsTest {

    /**
     * 逐帧绘制函数（性能段扫描面：这些函数体里不得出现堆分配）。
     *
     * ⚠️ **只含每帧函数**。`ensureBeamGeometry` / `beamBrush` / `ensureColors` 故意不在列 ——
     * 它们由**缓存键**守卫（尺寸/配色不变即 `return`），构造 `Brush` / `Path` / `Stroke` 是
     * 合法的「一次性」成本。⑨ 里另有一条断言**证明该守卫存在**，否则这个排除就是漏洞。
     */
    private val drawFns = listOf("drawContent", "addDot")

    // ═══════════════════════════ ① 迁移形态 ═══════════════════════════

    @Test
    fun `① 已迁移 RendererFx - 不覆写 final、内容钩子齐备、两条旧时钟红线已清`() {
        val body = classBody(codeOfBatchFour(), "LightBeamsRenderer")
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
        assertFalse("⛔ 不得自建 rng（基类已提供 protected rng）", "private val rng = VisualizerRandom()" in body)
        assertFalse("⛔ 不得使用 ctx.nowMs（一律走 fx）", Regex("""ctx\.nowMs""").containsMatchIn(body))
        assertFalse("⛔ 不得再用 lastMs 哨兵（首帧 timeMs 可能恰为 0）", usesLastMsSentinel(body))
        assertFalse("⛔ 不得再用 frame.timeMs 当相位（大基数下 float 精度丢失）", usesWallClockPhase(body))
        assertTrue("必须走 dt 化时钟", dtFromFx(body))
        assertFalse("旧继承形态必须消失", "VisualizerRenderer" in body)
    }

    // ═══════════════════════════ ② postFx ═══════════════════════════

    @Test
    fun `② postFx 数值字面量 - 正负双证（门禁判据同源）`() {
        val body = classBody(codeOfBatchFour(), "LightBeamsRenderer")
        assertTrue("postFx 必须是数值字面量（覆盖门禁判据）", coveredByPostFx(body))
        assertTrue("§B10-⑤ 必须 vignette = 0.50f", "vignette = 0.50f" in body)
        assertTrue("§B10-⑤ 必须 grain = 0.030f", "grain = 0.030f" in body)
        assertFalse("PostFx.NONE 不得被判为已覆盖", coveredByPostFx("override val postFx = PostFx.NONE"))
        assertFalse(
            "具名常量不得被判为已覆盖（门禁只认字面量）",
            coveredByPostFx("override val postFx = PostFx(vignette = VIG, grain = GRAIN)")
        )
    }

    // ═══════════════════════════ ③ §B10-① 光束渐变 ═══════════════════════════

    @Test
    fun `③ §B10-① 光束改渐变 - 锥形多边形 + 4 个缓存 Brush + 5 维缓存键`() {
        val body = classBody(codeOfBatchFour(), "LightBeamsRenderer")
        assertEquals("§B10-① 明文：起点（外缘端）alpha", 0.42f, LightBeamsRenderer.BEAM_NEAR_ALPHA, 1e-6f)
        assertEquals("§B10-① 明文：终点（中心端）透明", 0f, LightBeamsRenderer.BEAM_FAR_ALPHA, 1e-6f)
        assertTrue(
            "芯线必须比体积体更亮（否则「芯」不可见）",
            LightBeamsRenderer.CORE_NEAR_ALPHA > LightBeamsRenderer.BEAM_NEAR_ALPHA
        )
        assertTrue(
            "体积体必须由粗到细（外缘端粗 / 中心端细 = 纵深）",
            LightBeamsRenderer.BEAM_HALF_W_NEAR > LightBeamsRenderer.BEAM_HALF_W_FAR
        )
        assertEquals("外缘端半径", 1.05f, LightBeamsRenderer.START_R_K, 1e-6f)
        assertEquals("中心端半径", 0.06f, LightBeamsRenderer.END_R_K, 1e-6f)

        assertTrue("必须是「锥形多边形 + 沿轴线性渐变」", taperedGradientBeam(body))
        assertTrue(
            "逐束旋转必须交给 withTransform（inline ⇒ 零分配，§15.4-A7）",
            body.contains("withTransform({ rotate(")
        )
        assertTrue("几何必须建在**规范朝向**（自中心沿 +X 伸出）", body.contains("body.moveTo(cx + startR, cy - wNear)"))
        assertTrue("必须缓存 4 个 Brush（体积体 / 芯线 × 双色）", fourCachedBrushes(body))
        assertTrue("缓存键必须覆盖 5 维（⛔ 少一维就会切歌/换尺寸后复用错色）", beamKeyDims(body))
        assertFalse("⛔ 旧「等宽硬边光柱」必须消失", oldHardEdgeBeam(body))
        assertFalse("⛔ 旧「纯色扇形多边形」必须消失", oldFanPolygon(body))
    }

    // ═══════════════════════════ ④ §B10-② 体积雾 ═══════════════════════════

    @Test
    fun `④ §B10-② 体积雾 - Id_FOG + alpha 0_12 + 过扫描漂移（不露边）`() {
        val body = classBody(codeOfBatchFour(), "LightBeamsRenderer")
        val draw = funBody(body, "drawContent")
        assertEquals("§B10-② 明文 alpha", 0.12f, LightBeamsRenderer.FOG_ALPHA, 1e-6f)
        assertTrue("必须叠 Id.FOG", body.contains("ProceduralTexture.Id.FOG"))
        assertEquals("雾必须 1 次 drawImage", 1, Regex("""(?<![A-Za-z0-9_])drawImage\(""").findAll(draw).count())
        assertTrue(
            "漂移必须由 sectionEnergy 驱动（§B10-② 明文）",
            body.contains("FOG_DRIFT * (0.35f + frame.sectionEnergy)")
        )
        assertTrue("漂移必须 dt 化", body.contains("wrap01(fogDriftX + fogSpeed * fx.dt)"))
        assertTrue("必须过扫描（漂移时画布仍被铺满）", body.contains("dstSize = IntSize(iw + ov * 2, ih + ov * 2)"))
        assertTrue("过扫描量必须为正", LightBeamsRenderer.FOG_OVERSCAN > 0)

        // ── 覆盖不变式：dstOffset ∈ [-2ov, 0] 且 dstSize = 画布 + 2ov ⇒ 画布必被铺满 ──
        val ov = LightBeamsRenderer.FOG_OVERSCAN
        for (t in 0..20) {
            val drift = (t / 20f) * 2f * ov - ov            // ∈ [-ov, ov]
            val d = -ov + drift.toInt()                     // ∈ [-2ov, 0]
            assertTrue("dstOffset 必须 ≤ 0（实测 $d）", d <= 0)
            assertTrue("右边缘必须盖过画布（实测 ${d + 1000 + ov * 2}）", d + 1000 + ov * 2 >= 1000)
        }
    }

    // ═══════════════════════════ ⑤ §B10-③ 尘埃（形态） ═══════════════════════════

    @Test
    fun `⑤ §B10-③ 尘埃 - 常量落在明文区间 + 3 条 Path 合批`() {
        val body = classBody(codeOfBatchFour(), "LightBeamsRenderer")
        assertTrue("§B10-③ 明文 40–60 个（实测 ${LightBeamsRenderer.DUST_N}）", LightBeamsRenderer.DUST_N in 40..60)
        assertEquals("§B10-③ 明文 3 条 Path", 3, LightBeamsRenderer.DUST_BUCKETS)
        assertEquals("3 桶半径表长度", 3, LightBeamsRenderer.DUST_R.size)
        assertEquals("3 桶 alpha 表长度", 3, LightBeamsRenderer.DUST_ALPHAS.size)
        for (r in LightBeamsRenderer.DUST_R) {
            assertTrue("半径必须落在 0.5–0.8（= §B10-③ 的 1–1.6px 直径），实测 $r", r >= 0.5f && r <= 0.8f)
        }
        for (a in LightBeamsRenderer.DUST_ALPHAS) {
            assertTrue("alpha 必须落在 §B10-③ 的 0.20–0.45，实测 $a", a >= 0.20f && a <= 0.45f)
        }
        assertTrue("必须 3 条 Path 合批（⛔ 不是 48 次 drawCircle）", dustBatched(body))
        assertFalse("⛔ 不得逐点 drawCircle", perDustCircle(body))
        assertTrue("尘埃必须按**固定下标**分桶（桶不逐帧跳变）", body.contains("DUST_R[dustBucketOf(i)]"))
    }

    // ═══════════════════════════ ⑥ §B10-③ 尘埃（行为） ═══════════════════════════

    @Test
    fun `⑥ §B10-③ 尘埃行为 - 固定分桶、环绕、dt 化漂移（直调生产纯函数）`() {
        // ── 固定分桶：3 桶全可达、负数归一、不恒返回同一桶 ──
        assertEquals(
            "3 桶必须全部可达（否则有桶恒空 ⇒ 有 1 条 Path 永远是空的）",
            setOf(0, 1, 2), (0..8).map { LightBeamsRenderer.dustBucketOf(it) }.toSet()
        )
        assertEquals("下标 3 必须回到桶 0（固定分桶，不逐帧跳变）", 0, LightBeamsRenderer.dustBucketOf(3))
        assertEquals("负数下标必须归一（防御）", 2, LightBeamsRenderer.dustBucketOf(-1))
        assertTrue(
            "⛔ 不得恒返回同一个桶",
            (0..8).map { LightBeamsRenderer.dustBucketOf(it) }.distinct().size > 1
        )

        // ── wrap01：环绕到 [0, 1) ──
        assertEquals(0.25f, LightBeamsRenderer.wrap01(0.25f), 1e-6f)
        assertEquals("负值必须绕到上半区", 0.75f, LightBeamsRenderer.wrap01(-0.25f), 1e-6f)
        assertEquals(">1 必须绕回", 0.25f, LightBeamsRenderer.wrap01(1.25f), 1e-6f)
        assertEquals("恰为 1 必须归 0", 0f, LightBeamsRenderer.wrap01(1f), 1e-6f)
        assertEquals(0f, LightBeamsRenderer.wrap01(0f), 1e-6f)
        for (v in listOf(-7.3f, -1f, -0.001f, 0f, 0.999f, 1f, 3.7f, 12.5f)) {
            val wv = LightBeamsRenderer.wrap01(v)
            assertTrue("wrap01($v) = $wv 必须落在 [0,1)", wv >= 0f && wv < 1f)
        }
        // ⛔ 对照自证：Kotlin 的 `%` **保留被除数符号**（-0.25 % 1 == -0.25）⇒ 不能拿它环绕
        assertTrue("对照：`(-0.25f % 1f) < 0f` 必须成立（所以实现不能用 `%`）", (-0.25f % 1f) < 0f)

        // ── driftDelta：dt 化（线性、零增量不推进） ──
        assertEquals(
            "1 秒的位移必须 == 速度",
            LightBeamsRenderer.DUST_DRIFT,
            LightBeamsRenderer.driftDelta(1f, LightBeamsRenderer.DUST_DRIFT), 1e-6f
        )
        assertEquals("dt = 0 不得推进", 0f, LightBeamsRenderer.driftDelta(0f, LightBeamsRenderer.DUST_DRIFT), 1e-6f)
        assertEquals(
            "必须对 dt 线性",
            LightBeamsRenderer.driftDelta(0.5f, 0.1f) * 2f,
            LightBeamsRenderer.driftDelta(1f, 0.1f), 1e-6f
        )
    }

    // ═══════════════════════════ ⑦ §B10-④ 镜头光斑 ═══════════════════════════

    @Test
    fun `⑦ §B10-④ 镜头光斑 - 1 次 shadeBrushCached（带盐）+ 3 条 drawLine`() {
        val body = classBody(codeOfBatchFour(), "LightBeamsRenderer")
        val draw = funBody(body, "drawContent")
        assertEquals("§B10-④ 明文：六芒 = 3 条 drawLine", 3, LightBeamsRenderer.FLARE_SPOKES)
        assertEquals("具名盐（§四 G4：16 槽进程级共享）", 0x35353535L, LightBeamsRenderer.E35_KEY_SALT)
        assertTrue("必须用 shadeBrushCached（⛔ 不每帧重建 Brush）", draw.contains("Shading2D.shadeBrushCached("))
        assertTrue("必须带具名盐", draw.contains("E35_KEY_SALT"))
        assertTrue("光晕必须落在交汇处（画布中心）", draw.contains("center = Offset(cx, cy)"))
        assertTrue("缓存键必须含 (w, h) 与 accent", keyHasDims(draw))
        assertEquals("光斑必须恰 1 次 shadeBrushCached", 1,
            Regex("""Shading2D\.shadeBrushCached\(""").findAll(draw).count())
        assertTrue("六芒必须绕中心对称（±dx / ±dy）", draw.contains("Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy)"))
        assertTrue("六芒必须自转（相位由 elapsedSec 驱动）", draw.contains("elapsedSec * FLARE_SPIN"))
    }

    // ═══════════════════════════ ⑧ dt 化（行为） ═══════════════════════════

    @Test
    fun `⑧ dt 化 - advanceBeamAngle 在 30_60_120 fps 下行为恒等（直调生产纯函数）`() {
        val speed = 0.53f
        val treb = 0.4f
        val a60 = accumulate(60, speed, treb)
        assertEquals("30 fps 与 60 fps 的 1 秒累计必须一致", a60, accumulate(30, speed, treb), 1e-3f)
        assertEquals("120 fps 与 60 fps 的 1 秒累计必须一致", a60, accumulate(120, speed, treb), 1e-3f)
        val expect = speed * (0.5f + treb * 0.8f) * 1f
        assertEquals("必须等于解析解 speed × (0.5 + treb×0.8) × t", expect, a60, 1e-2f)
        assertTrue("⛔ 不得为 0（否则上一条恒真）", a60 > 0.05f)
        assertEquals("dt = 0 不得推进", 0.25f, LightBeamsRenderer.advanceBeamAngle(0.25f, 0f, speed, treb), 1e-6f)
        assertTrue(
            "treble 必须加速（低通后的 treble 越大转速越快）",
            LightBeamsRenderer.advanceBeamAngle(0f, 1f, speed, 0.9f) >
                LightBeamsRenderer.advanceBeamAngle(0f, 1f, speed, 0f)
        )

        // beamAngleOf = 基准 + 累加 + 静态×0.1（三分量都要真的参与）
        assertEquals("基准 + 累加 + 静态×0.1", 1f + 2f + 0.3f, LightBeamsRenderer.beamAngleOf(1f, 2f, 3f), 1e-6f)
        assertEquals("静态错相位系数必须恰为 0.1", 0.1f, LightBeamsRenderer.beamAngleOf(0f, 0f, 1f), 1e-6f)
    }

    // ═══════════════════════════ ⑨ 每帧零堆分配 ═══════════════════════════

    @Test
    fun `⑨ 每帧零堆分配 - drawContent 与 addDot 函数体 × 10 条模式`() {
        val body = classBody(codeOfBatchFour(), "LightBeamsRenderer")
        for (fn in drawFns) {
            val fb = funBody(body, fn)
            assertTrue("必须能切出 $fn 体（空转自证）", fb.isNotEmpty())
            for ((label, re) in ALLOC_RES) {
                assertFalse("⛔ $fn 体内不得出现 $label（每帧零分配）", re.containsMatchIn(fb))
            }
        }
        // ⚠️ 排除 ensure* / beamBrush 的理由必须成立：它们由**缓存键**守卫，不是每帧重建
        val ensure = funBody(body, "ensureBeamGeometry")
        assertTrue("ensureBeamGeometry 必须能切出来", ensure.isNotEmpty())
        assertTrue("必须由 5 维缓存键守卫（否则「排除它」就是漏洞）", beamKeyDims(ensure))
        assertTrue("缓存命中必须直接 return", Regex("""\)\s*return""").containsMatchIn(ensure))
        assertTrue("onEnterContent 必须清空缓存（⛔ 否则切效果后复用旧尺寸的几何）", clearCacheOnEnter(body))
    }

    // ═══════════════════════════ ⑩ Id.FOG tile ═══════════════════════════

    @Test
    fun `⑩ Id_FOG tile - Id 末项 + ensure 接线 + fogRow 逐行确定（直调生产函数）`() {
        val fx = readFile(fxFile("ProceduralTexture.kt"))
        val ids = Regex("""enum class Id \{([^}]*)\}""").find(fx)?.groupValues?.get(1) ?: ""
        assertTrue("Id 枚举必须能切出来（空转自证）", ids.isNotEmpty())
        val names = ids.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue("Id 至少 8 项（实测 ${names.size}）", names.size >= 8)
        assertEquals("⛔ 新增必须追加到最后一项（ordinal 是 slots / keys 的下标基准）", "FOG", names.last())
        assertEquals("FOG 必须恰 1 项", 1, names.count { it == "FOG" })
        assertTrue("必须接线到 ensureFullscreen", fx.contains("ensureFullscreen(Id.FOG, w, h, fullKey)"))
        assertTrue("必须有 fogRow 生成函数", Regex("""internal fun fogRow\s*\(""").containsMatchIn(fx))

        // ── 行为：逐行确定性 + 逐行差异 + alpha/灰阶区间 + 成团（有透明区） ──
        val w = 1024
        val h = 1080
        var tot = 0
        var nz = 0
        var zero = 0
        var maxA = 0
        var badA = 0
        var badGray = 0
        var badRange = 0
        var yDiffer = 0
        var prev: IntArray? = null
        var y = 0
        while (y < h) {
            val row = IntArray(w)
            ProceduralTexture.fogRow(row, y, w, h)
            for (px in row) {
                val a = (px ushr 24) and 0xFF
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                tot++
                if (a > 0) nz++ else zero++
                if (a > maxA) maxA = a
                if (a > 229) badA++
                if (r != g || g != b) badGray++
                if (r < 196 || r > 255) badRange++
            }
            val p = prev
            if (p != null && !row.contentEquals(p)) yDiffer++
            prev = row
            y += 135
        }
        assertEquals("alpha 必须 ≤ FOG_A(229)", 0, badA)
        assertEquals("雾必须是灰阶（R == G == B）", 0, badGray)
        assertEquals("灰阶必须落在 196..255（浓处更白）", 0, badRange)
        assertTrue("必须有雾（alpha > 0 占比 ≥ 30%，实测 ${nz.toFloat() / tot}）", nz.toFloat() / tot >= 0.30f)
        assertTrue(
            "必须成团（有透明区，alpha == 0 占比 ≥ 20%，实测 ${zero.toFloat() / tot}）",
            zero.toFloat() / tot >= 0.20f
        )
        assertTrue("峰值必须够浓（≥ 180，实测 $maxA）", maxA >= 180)
        assertTrue("必须随 y 变化（不是竖条纹），实测变化行数 $yDiffer", yDiffer >= 4)

        val a1 = IntArray(256)
        val a2 = IntArray(256)
        ProceduralTexture.fogRow(a1, 7, 256, 256)
        ProceduralTexture.fogRow(a2, 7, 256, 256)
        assertTrue("同一 (y,w,h) 两次生成必须逐像素相同（确定性）", a1.contentEquals(a2))
        val a3 = IntArray(256)
        ProceduralTexture.fogRow(a3, 8, 256, 256)
        assertFalse("不同 y 必须不同（否则退化成竖条纹）", a1.contentEquals(a3))
    }

    // ═════════════════════ ⑩-b ProceduralTexture 只烘 FOG（首帧黑屏修复） ═════════════════════

    /**
     * E35 的纹理烘焙面（2026-10-07）。
     *
     * ⛔ 背景：`ProceduralTexture.ensure(w, h)` 一次烘**全部 6 张**全屏纹理
     * （1920×1080 × 6 ≈ 1240 万像素 Kotlin 逐像素 + 6480 次 JNI `setPixels`），而 E35
     * **只画 `Id.FOG` 一张**。又因 `ctx.canvasSize` 在 `onEnter` 时还是 `Size.Zero`，
     * 这份烘焙必然同步落在**首帧** ⇒ 真机实测冷启动首帧黑屏 6369 ms
     * （记录在 `ProceduralTexture.kt` 的 `ensureFullscreenOnly` KDoc 里）。
     *
     * ⛔ **负向自证 3 条**（缺一条就空转）：
     *  ① 剥注释必要性：KDoc 正文里就写着旧写法 ⇒ 判据必须跑在**已剥注释**的源码上；
     *  ② 旧片段喂**同一份谓词**必须被判否、新片段被判是；
     *  ③ 左括号边界：`ensureFullscreenOnly(` / `ensureTiled(` 都**不含** `ensure(`。
     */
    @Test
    fun `⑩b 只烘 FOG - 无裸 ensure 且只点名 FOG 且保留平铺槽`() {
        val body = classBody(codeOfBatchFour(), "LightBeamsRenderer")
        assertTrue("类体必须能切出来（空转自证）", body.isNotEmpty())

        // ⛔ 负向自证 ①：原文（含 KDoc）里确实有裸 `ProceduralTexture.ensure(` ⇒ 判据必须剥注释
        val raw = classBody(readFile(renderersFile("BatchFourRenderers.kt")), "LightBeamsRenderer")
        assertTrue(
            "剥注释自证：原文里确有裸 `ProceduralTexture.ensure(`（在 KDoc 里）",
            raw.contains("ProceduralTexture.ensure("),
        )
        assertFalse(
            "剥注释生效：剥注释后不得再命中裸 ensure(",
            body.contains("ProceduralTexture.ensure("),
        )

        // ⛔ 正向：只点名 FOG，且恰好一个调用点
        assertEquals(
            "ensureFullscreenOnly 必须只有一个调用点",
            1, Regex("""ProceduralTexture\.ensureFullscreenOnly\(""").findAll(body).count(),
        )
        assertTrue(
            "⛔ 必须只点名 FOG（不得一次烘多张）",
            Regex("""ProceduralTexture\.ensureFullscreenOnly\(\s*ProceduralTexture\.Id\.FOG""")
                .findAll(body).count() == 1,
        )
        // ⛔ 平铺槽：postFx.grain = 0.030f 经 OverlayFx.drawGrain 读 tile(Id.GRAIN)，
        //    读不到就静默不画；平铺槽的唯一生产者是 ensure() 内部的 ensureTiledSlots。
        assertTrue(
            "⛔ 必须调 ensureTiled()（否则 postFx.grain 的胶片颗粒层静默消失）",
            body.contains("ProceduralTexture.ensureTiled()"),
        )

        // ⛔ 负向自证 ③ + ②：喂同一份判据，旧片段判否 / 新片段判是
        val OLD = "ProceduralTexture.ensure(iw, ih)"
        val NEW = "ProceduralTexture.ensureTiled()\nProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.FOG, iw, ih)"
        assertTrue("旧片段必须被判否", OLD.contains("ProceduralTexture.ensure("))
        assertFalse("新片段不得被判否（ensureFullscreenOnly 不含 ensure( 的左括号形态）", NEW.contains("ProceduralTexture.ensure("))
        assertEquals(
            "新片段的 ensureFullscreenOnly 必须恰好一个调用点",
            1, Regex("""ProceduralTexture\.ensureFullscreenOnly\(""").findAll(NEW).count(),
        )
    }

    // ═══════════════════════════ ⑪ 覆盖门禁名单 ═══════════════════════════

    @Test
    fun `⑪ 覆盖门禁 - covered 已含 LightBeamsRenderer 且 exempt 已移除`() {
        val t = readFile(testFxFile("FxCoverageScanTest.kt"))
        val cov = namedList(t, "covered")
        val exe = namedList(t, "exempt")
        assertTrue("covered 必须能切出来（空转自证）", cov.isNotEmpty())
        assertTrue("exempt 必须能切出来（空转自证）", exe.isNotEmpty())
        assertTrue("covered 必须含 LightBeamsRenderer（T4.10）", "\"LightBeamsRenderer\"" in cov)
        assertFalse("⛔ exempt 不得再含 LightBeamsRenderer", "\"LightBeamsRenderer\"" in exe)
        // ⚠️ 2026-10-05：原样本 `FractalTreeRenderer`（T4.9）随效果删除 ⇒ 换成同属 T4 批次 B
        //   的 `MatrixRainRenderer`（E16 / §B3-④，仍在 covered 里）。本条只是「covered 非空且
        //   名单本身没被削空」的元断言，被断言的对象与本文件的主角（LightBeams）不同即可。
        assertTrue("covered 必须含 MatrixRainRenderer（T4 批次 B，防回退）", "\"MatrixRainRenderer\"" in cov)
        assertTrue("N2 必须仍是**不变式**（⛔ 不写死类名）", "pickUncoveredSample" in t)
    }

    // ═══════════════════════════ 负向自证 ═══════════════════════════

    @Test
    fun `负向N1 旧时钟（lastMs 哨兵 + frame_timeMs 当相位）必须被同一份谓词抓到`() {
        val body = classBody(codeOfBatchFour(), "LightBeamsRenderer")
        assertFalse("前提：新实现不得有 lastMs 哨兵", usesLastMsSentinel(body))
        assertFalse("前提：新实现不得用 frame.timeMs 当相位", usesWallClockPhase(body))
        assertTrue("前提：新实现必须是 dt 化", dtFromFx(body))

        // ⛔ 剥注释必要性自证：**原文**（含 KDoc）里确实写了这两个旧写法（作为「修掉了什么」的说明）
        val raw = classBody(readFile(renderersFile("BatchFourRenderers.kt")), "LightBeamsRenderer")
        assertTrue("剥注释自证：原文里确有 `lastMs == 0L`（在 KDoc 里）", usesLastMsSentinel(raw))
        assertTrue("剥注释自证：原文里确有 `frame.timeMs * 0.001f`（在 KDoc 里）", usesWallClockPhase(raw))

        // ⛔ 喂**同一份谓词**：旧片段必须被判否，新片段必须被判是
        assertTrue("旧哨兵必须被 usesLastMsSentinel 抓到", usesLastMsSentinel(OLD_CLOCK_SNIPPET))
        assertTrue("旧相位必须被 usesWallClockPhase 抓到", usesWallClockPhase(OLD_CLOCK_SNIPPET))
        assertFalse("旧片段不得被判为 dt 化", dtFromFx(OLD_CLOCK_SNIPPET))
        assertTrue("新片段必须被判为 dt 化", dtFromFx(NEW_CLOCK_SNIPPET))
        assertFalse("新片段不得被判为有哨兵", usesLastMsSentinel(NEW_CLOCK_SNIPPET))
        assertFalse("新片段不得被判为用墙钟相位", usesWallClockPhase(NEW_CLOCK_SNIPPET))
    }

    @Test
    fun `负向N2 旧等宽硬边光柱 + 纯色扇形必须被同一份谓词抓到`() {
        val body = classBody(codeOfBatchFour(), "LightBeamsRenderer")
        assertTrue("前提：新实现必须是锥形渐变", taperedGradientBeam(body))
        assertFalse("前提：新实现不得有等宽硬边光柱", oldHardEdgeBeam(body))
        assertFalse("前提：新实现不得有纯色扇形", oldFanPolygon(body))

        assertTrue("旧等宽光柱必须被 oldHardEdgeBeam 抓到", oldHardEdgeBeam(OLD_BEAM_SNIPPET))
        assertTrue("旧纯色扇形必须被 oldFanPolygon 抓到", oldFanPolygon(OLD_FAN_SNIPPET))
        assertFalse("旧片段不得被判为锥形渐变", taperedGradientBeam(OLD_BEAM_SNIPPET))
        assertTrue("新片段必须被判为锥形渐变", taperedGradientBeam(NEW_BEAM_SNIPPET))
        assertFalse("新片段不得被判为等宽硬边", oldHardEdgeBeam(NEW_BEAM_SNIPPET))
        assertFalse("新片段不得被判为纯色扇形", oldFanPolygon(NEW_BEAM_SNIPPET))
    }

    // ═══════════════════════════ 谓词（正 / 负向**共用**） ═══════════════════════════

    /** dt 化：只吃 `fx.dt`（相位增量式推进） */
    private fun dtFromFx(code: String): Boolean =
        code.contains("elapsedSec += fx.dt") &&
            code.contains("advanceBeamAngle(beamAng[i], fx.dt, beamSpeed[i], trebleSmooth)")

    /** ⛔ 旧时钟哨兵：`lastMs == 0L`（首帧 `timeMs` 可能恰为 0 ⇒ 误判为「已初始化」） */
    private fun usesLastMsSentinel(code: String): Boolean =
        Regex("""\blastMs\s*==\s*0L""").containsMatchIn(code)

    /** ⛔ 旧相位：`frame.timeMs * 0.001f`（开机毫秒是大基数 ⇒ float 尾数不足 ⇒ 冻结/跳变） */
    private fun usesWallClockPhase(code: String): Boolean =
        Regex("""frame\.timeMs\s*\*\s*0\.001f""").containsMatchIn(code)

    /** §B10-① 锥形多边形 + 沿轴线性渐变 */
    private fun taperedGradientBeam(code: String): Boolean =
        code.contains("drawPath(body,") && code.contains("drawPath(core,") &&
            code.contains("Brush.linearGradient(") && code.contains("BEAM_NEAR_ALPHA") &&
            code.contains("BEAM_FAR_ALPHA") && code.contains("body.close()")

    /** ⛔ 旧「等宽硬边光柱」：两条固定线宽（7f 辉光 + 1.6f 芯线）的 `drawLine` */
    private fun oldHardEdgeBeam(code: String): Boolean =
        code.contains("strokeWidth = 7f") && code.contains("strokeWidth = 1.6f")

    /** ⛔ 旧「纯色扇形多边形」（`alpha 0.05`，`halfW` 写死 0.045） */
    private fun oldFanPolygon(code: String): Boolean =
        code.contains("private fun DrawScope.drawFan(") && code.contains("val halfW = 0.045f")

    /** 4 个缓存 `Brush`（体积体 / 芯线 × accent / secondary） */
    private fun fourCachedBrushes(code: String): Boolean =
        code.contains("private var bodyBrushA: Brush?") && code.contains("private var coreBrushA: Brush?") &&
            code.contains("private var bodyBrushB: Brush?") && code.contains("private var coreBrushB: Brush?")

    /** 几何 / `Brush` 的 5 维缓存键（§四 G4：少一维就会复用错色或错尺寸） */
    private fun beamKeyDims(code: String): Boolean =
        code.contains("geoCx == cx") && code.contains("geoCy == cy") &&
            code.contains("geoMaxLen == maxLen") && code.contains("geoAccent == accent") &&
            code.contains("geoSecondary == secondary")

    /** `onEnterContent` 必须清空几何 / `Brush` / `Stroke` 缓存 */
    private fun clearCacheOnEnter(code: String): Boolean {
        val enter = funBody(code, "onEnterContent")
        return enter.contains("beamBody = null") && enter.contains("bodyBrushA = null") &&
            enter.contains("dashStroke = null") && enter.contains("geoCx = -1f")
    }

    /** 缓存键必须覆盖 `(w, h)` 与 `accent`（§四 G4） */
    private fun keyHasDims(code: String): Boolean =
        code.contains("w.toRawBits().toLong() shl 32") &&
            code.contains("h.toRawBits().toLong()") &&
            code.contains("accent.toArgb().toLong()")

    /** §B10-③ 尘埃合批：3 条 `Path` + 逐点追加 + 落笔 3 次 */
    private fun dustBatched(code: String): Boolean =
        code.contains("dustPaths[b].rewind()") &&
            Regex("""drawPath\(dustPaths\[b\]""").containsMatchIn(code) &&
            code.contains("addDot(dustPaths[dustBucketOf(k)]")

    /** ⛔ 逐点 `drawCircle`（§B10-③ 明确要求合批） */
    private fun perDustCircle(code: String): Boolean =
        Regex("""drawCircle\(\s*dustColor""").containsMatchIn(code)

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

    // ── 旧 / 新片段（负向样本；**只**用于证明判据能抓到旧写法）──

    private val OLD_CLOCK_SNIPPET = """
        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtSec = ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        val tSec = frame.timeMs * 0.001f
    """.trimIndent()

    private val NEW_CLOCK_SNIPPET = """
        elapsedSec += fx.dt
        beamAng[i] = advanceBeamAngle(beamAng[i], fx.dt, beamSpeed[i], trebleSmooth)
    """.trimIndent()

    private val OLD_BEAM_SNIPPET = """
        drawLine(color, Offset(cx + dx * startR, cy + dy * startR),
            Offset(cx + dx * endR, cy + dy * endR),
            strokeWidth = 7f, alpha = 0.10f, blendMode = BlendMode.Plus)
        drawLine(color, Offset(cx + dx * startR, cy + dy * startR),
            Offset(cx + dx * endR, cy + dy * endR),
            strokeWidth = 1.6f, alpha = 0.75f, blendMode = BlendMode.Plus)
    """.trimIndent()

    private val OLD_FAN_SNIPPET = """
        private fun DrawScope.drawFan(
            cx: Float, cy: Float, ang: Float, r: Float, color: Color, alpha: Float
        ) {
            val halfW = 0.045f
        }
    """.trimIndent()

    private val NEW_BEAM_SNIPPET = """
        body.moveTo(cx + startR, cy - wNear)
        body.close()
        drawPath(body, bBodyA!!, alpha = 1f, blendMode = BlendMode.Plus)
        drawPath(core, cCoreA!!, alpha = 1f, blendMode = BlendMode.Plus)
        Brush.linearGradient(listOf(base.copy(alpha = BEAM_NEAR_ALPHA),
            base.copy(alpha = BEAM_FAR_ALPHA)), start = near, end = far)
    """.trimIndent()

    // ═══════════════════════════ 辅助 ═══════════════════════════

    /** 同一墙钟 1 秒、按 [fps] 帧推进后的累计角度（帧数 = fps，dt = 1/fps） */
    private fun accumulate(fps: Int, speed: Float, treb: Float): Float {
        var a = 0f
        val dt = 1f / fps
        var i = 0
        while (i < fps) {
            a = LightBeamsRenderer.advanceBeamAngle(a, dt, speed, treb)
            i++
        }
        return a
    }

    /** 切出 `class <name>` 的类体（到下一个顶层 class / 文件尾），⛔ **含紧邻上方的 KDoc** */
    private fun classBody(txt: String, name: String): String {
        val m = Regex("""(?m)^\s*(?:(?:internal|open|abstract|private)\s+)*class\s+$name\b""").find(txt)
            ?: return ""
        // ⛔ v1.38 修：必须把**紧邻上方的 KDoc 一并纳入**。
        // 类的自述文档就在 KDoc 里（"⛔ 迁移顺带修掉的两条红线"那段写着 `lastMs == 0L`
        // 与 `frame.timeMs * 0.001f`），而这**正是「必须剥注释」的前提**。
        // 原实现只取 `class` 关键字之后 ⇒ 负向自证的见证文本落在 body 之外，
        // 于是「原文里确有旧写法」这条断言恒假。
        // 对**已剥过注释**的文本走本函数时 KDoc 已被清空 ⇒ 下面这段回退不触发，行为不变。
        var start = m.range.first
        var p = start - 1
        while (p >= 0 && txt[p].isWhitespace()) p--
        if (p >= 1 && txt[p] == '/' && txt[p - 1] == '*') {
            var open = txt.lastIndexOf("/*", p - 1)
            while (open >= 0 && txt.substring(open + 2, p).contains("*/")) {
                open = txt.lastIndexOf("/*", open - 1)
            }
            if (open >= 0) start = open
        }
        val rest = txt.substring(m.range.last + 1)
        val nxt = Regex("""(?m)^\s*(?:(?:internal|open|abstract|private)\s+)*class\s+\w+""").find(rest)
        return if (nxt == null) {
            txt.substring(start)
        } else {
            txt.substring(start, m.range.last + 1 + nxt.range.first)
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

    /** 切出 `private val <name> = (listOf|mapOf)(...)` 的括号块 */
    private fun namedList(txt: String, name: String): String {
        val m = Regex("""private\s+val\s+$name\s*=\s*(?:listOf|mapOf)\s*\(""").find(txt) ?: return ""
        val open = txt.indexOf('(', m.range.first)
        var depth = 0
        var i = open
        while (i < txt.length) {
            when (txt[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return txt.substring(open, i + 1)
                }
            }
            i++
        }
        return txt.substring(open)
    }

    /**
     * 去注释（**行注释 + 块注释（含嵌套）+ 字符串感知**）。
     * ⛔ 只去行注释不够 —— 判据会命中 KDoc 正文里举的旧写法（本仓库已踩 5+ 次）。
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
    private fun codeOfBatchFour(): String =
        stripComments(readFile(renderersFile("BatchFourRenderers.kt")))

    /** 门禁判据同源：`override val postFx = PostFx(<数值>…)` 且至少一个 > 0 */
    private fun coveredByPostFx(body: String): Boolean {
        val m = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""").find(body) ?: return false
        return Regex("""=\s*([0-9]*\.?[0-9]+)f""").findAll(m.groupValues[1])
            .any { it.groupValues[1].toFloat() > 0f }
    }

    private fun readFile(f: File): String = f.readText()

    private fun renderersFile(name: String): File =
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/$name")

    private fun fxFile(name: String): File =
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/fx/$name")

    private fun testFxFile(name: String): File {
        var dir = File(System.getProperty("user.dir")!!)
        repeat(6) {
            val c = File(dir, "app/src/test/java/com/nasmusic/tv/visualizer/fx/$name")
            if (c.exists()) return c
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 $name")
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

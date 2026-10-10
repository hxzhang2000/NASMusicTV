package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门禁 **G4**（§十 10.2）：E44「明月」的提交 / 填充 / native 堆三张预算表。
 *
 * ## 每条断言都配一条**负向自证**（「喂破实现 ⇒ 必须失败」）
 * 只断言通过、不证明它能失败的门禁等于没有门禁（E43 `SeasideOpBudgetTest` 同一条纪律）。
 *
 * ## ⚠️ 本表没有 `legacy` 列 ⇒ 负向自证靠**显式注入**
 * E43 的负向自证有"改造前形态"可切（逐块 blit / 逐列渐变）；E44 ⛔ **没有改造前** ——
 * 表是从定稿原型**实测**出来的。所以本类的破实现注入全部走显式常数与减法：
 * 名义粼光条数（[MoonOpBudget.NOMINAL_GLITTER_OPS_HIGH]）、颗粒（[MoonOpBudget.GRAIN_EXTRA_FILL]）、
 * 全屏 pass 记零、整屏方图烘圆盘、以及"旧上限 3.30/3.60/3.50"对新表的判别。
 */
class MoonOpBudgetTest {

    // ══════════════════════════════════════════════════════════════════════════
    //  契约自检
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `契约常量等于 §9 定稿值`() {
        assertEquals(90, MoonOpBudget.OPS_MAX_LOW)
        assertEquals(200, MoonOpBudget.OPS_MAX_MEDIUM)
        assertEquals(320, MoonOpBudget.OPS_MAX_HIGH)
        // ⭐ 2026-10-09 所有者裁决（Q4/Q8/Q9）：3.45 / 3.95 / HIGH 不设上限
        // ⭐ 2026-10-10 所有者裁决（T9 越限上报）：MED 3.95 → **4.00**（理由见该常量 KDoc）
        assertEquals(3.45f, MoonOpBudget.OVERDRAW_MAX_LOW, 1e-6f)
        assertEquals(4.00f, MoonOpBudget.OVERDRAW_MAX_MEDIUM, 1e-6f)
        assertTrue(
            "HIGH 的绝对上限必须是无穷（裁决 Q4/Q8/Q9）",
            MoonOpBudget.overdrawMax(MoonLevel.HIGH).isInfinite()
        )
        assertEquals(4.306f, MoonOpBudget.FILL_REF_HIGH, 1e-6f)
        assertEquals(1.10f, MoonOpBudget.FILL_RATCHET, 1e-6f)
        assertEquals(3_000_000, MoonOpBudget.NATIVE_PX_MAX)
        // 半遮态实测锚（passcost 70 s / 140 帧取 max）
        assertEquals(74, MoonOpBudget.MEASURED_OPS_LOW)
        assertEquals(159, MoonOpBudget.MEASURED_OPS_MEDIUM)
        assertEquals(229, MoonOpBudget.MEASURED_OPS_HIGH)
        assertEquals(3.263f, MoonOpBudget.MEASURED_FILL_LOW, 1e-6f)
        assertEquals(3.759f, MoonOpBudget.MEASURED_FILL_MEDIUM, 1e-6f)
        assertEquals(4.306f, MoonOpBudget.MEASURED_FILL_HIGH, 1e-6f)
    }

    /**
     * ⭐ 「HIGH 不设上限」（裁决原文）在代码里的**形态**：[MoonOpBudget.overdrawMax] 在 HIGH
     * 返回无穷，判据搬到 [MoonOpBudget.fillRatchetMax]（棘轮）+ 单调方向断言。
     *
     * 本测试守的是**判据存在性** —— 若哪天有人把 `OVERDRAW_MAX_HIGH` 写成一个很大的有限数
     * （"用大数字冒充无上限"），棘轮就变成装饰，门静默失效。
     */
    @Test
    fun `HIGH 的门是棘轮而不是绝对上限`() {
        assertTrue(
            "HIGH 的绝对上限必须是无穷（裁决 Q4/Q8/Q9）",
            MoonOpBudget.overdrawMax(MoonLevel.HIGH).isInfinite()
        )
        val ratchet = MoonOpBudget.fillRatchetMax()
        assertTrue("棘轮必须是有限数，否则 HIGH 根本没有判据", ratchet.isFinite())
        // 可满足：表值必须在棘轮内
        assertTrue("棘轮 $ratchet 必须容得下表值", ratchet >= overdraw(MoonLevel.HIGH))
        // 有判别力：多铺一次全屏 pass（+1.00 屏）必须撞破棘轮
        assertTrue(
            "棘轮必须紧到『多一次全屏 pass 就破门』：$ratchet vs ${overdraw(MoonLevel.HIGH) + 1.0}",
            ratchet < overdraw(MoonLevel.HIGH) + 1.0
        )
        // ⛔ 绝对上限的**排序**不能反过来（Q9 那个符号错的机器化：旧表写的是 3.30/3.60/3.50）
        assertTrue(MoonOpBudget.OVERDRAW_MAX_LOW < MoonOpBudget.OVERDRAW_MAX_MEDIUM)
    }

    @Test
    fun `逐元素清单覆盖 §9_1 表的 15 项`() {
        val ids = MoonOpItem.entries.map { it.name }.toSet()
        val required = listOf(
            "SKY_BASE", "STARS", "HALO", "CLOUD_FAR", "CLOUD_NEAR", "BLOOM", "CLOUD_RIM",
            "DISK", "TERMINATOR", "HORIZON_BAND", "REFLECTION", "GLITTER", "RIPPLE",
            "POST_FX", "DAMAGE_COALESCER"
        )
        for (r in required) assertTrue("缺元素：$r", ids.contains(r))
        assertEquals(required.size, MoonOpItem.entries.size)
    }

    @Test
    fun `逐元素系数全部非负`() {
        for (it in MoonOpItem.entries) {
            assertTrue("${it.name} opsLow<0", it.opsLow >= 0)
            assertTrue("${it.name} opsMed<0", it.opsMed >= 0)
            assertTrue("${it.name} opsHigh<0", it.opsHigh >= 0)
            assertTrue("${it.name} fillLow<0", it.fillLow >= 0.0)
            assertTrue("${it.name} fillMed<0", it.fillMed >= 0.0)
            assertTrue("${it.name} fillHigh<0", it.fillHigh >= 0.0)
        }
    }

    /**
     * 防「漏画被当成省预算」。两张豁免名单**刻意不同**：
     * - HIGH 提交数为 0：**无**（三档每一项都在场）。
     * - 填充为 0：**[MoonOpItem.DAMAGE_COALESCER]**（MED/HIGH 有全屏后处理 ⇒ 基类不补那次 blit）。
     * - LOW 提交数为 0：**[MoonOpItem.CLOUD_RIM] / [MoonOpItem.RIPPLE] / [MoonOpItem.POST_FX]**
     *   （`FxLevel.OFF` 跳过基类后处理，银边与涟漪的档位门都在 LOW 关）⇒ LOW 才必须补 coalescer。
     */
    @Test
    fun `每档都要真的画东西`() {
        for (it in MoonOpItem.entries) {
            // DAMAGE_COALESCER 是 LOW **专属**的基类补偿 pass ⇒ MED/HIGH 的 0 是事实
            if (it != MoonOpItem.DAMAGE_COALESCER) {
                assertTrue("${it.name} HIGH 提交数必须为正", it.opsHigh > 0)
                assertTrue("${it.name} HIGH 填充必须为正", it.fillHigh > 0.0)
                assertTrue("${it.name} MED 提交数必须为正", it.opsMed > 0)
                assertTrue("${it.name} MED 填充必须为正", it.fillMed > 0.0)
            }
            if (it != MoonOpItem.CLOUD_RIM && it != MoonOpItem.RIPPLE && it != MoonOpItem.POST_FX) {
                assertTrue("${it.name} LOW 提交数必须为正（LOW 的合法零项只有这三个）", it.opsLow > 0)
            }
        }
        // LOW 的三个合法零与 coalescer 是一体两面：LOW 不画暗角 ⇒ 基类补一次全屏合成
        assertEquals(0, MoonOpItem.POST_FX.opsLow)
        assertEquals(0.0, MoonOpItem.POST_FX.fillLow, 1e-12)
        assertEquals(1, MoonOpItem.DAMAGE_COALESCER.opsLow)
        assertEquals(1.00, MoonOpItem.DAMAGE_COALESCER.fillLow, 1e-12)
        assertEquals(0, MoonOpItem.DAMAGE_COALESCER.opsHigh)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·① 提交
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `G4 ops 三档都在门内`() {
        for (level in MoonLevel.entries) {
            val n = MoonOpBudget.estimate(level)
            assertTrue(
                "estimate($level) = $n 超出 ${MoonOpBudget.opsMax(level)}",
                n <= MoonOpBudget.opsMax(level)
            )
        }
    }

    /** ⛔ 精确合计（不是门限）：任何一行系数被改动都会在这里显形。 */
    @Test
    fun `G4 逐档合计被钉死`() {
        assertEquals(78, MoonOpBudget.estimate(MoonLevel.LOW))
        assertEquals(163, MoonOpBudget.estimate(MoonLevel.MEDIUM))
        assertEquals(235, MoonOpBudget.estimate(MoonLevel.HIGH))
        // ⚠️ T8d：`GLITTER` MED `0.087→0.088`、`REFLECTION` LOW `0.073→0.074` / MED `0.203→0.204`。
        //    三处都是**就近取整把表写到生产几何以下**（`MoonlitWaterTest` 的 ③/⑤ 逐颗重算显出来的），
        //    表必须**向上**取整到 3 位 —— `G4 表是半遮态实测的上界` 的前提就是"表 ≥ 真实"。
        // ⚠️ T9（§八 接入）：`HALO` 三档各乘 `bass` 的 `k²=1.0733`（+0.006/+0.010/+0.018）、
        //    `RIPPLE` 的 MED/HIGH 从探针**典型帧** `0.017/0.049` 补正为生产几何**最坏帧**
        //    `0.073/0.146`（+0.056/+0.097）⇒ 合计三笔一起涨，偏差 **D34**。
        //    MED 因此越过旧上限 `3.95` ⇒ 所有者裁决抬到 `4.00`（⛔ 不是把两行的数改小）。
        assertEquals(3.40804f, overdraw(MoonLevel.LOW), 1e-4f)
        assertEquals(3.96604f, overdraw(MoonLevel.MEDIUM), 1e-4f)
        assertEquals(4.57604f, overdraw(MoonLevel.HIGH), 1e-4f)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·② 填充
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `G4 fill 的 LOW 与 MED 在绝对门内`() {
        assertTrue(
            "LOW = ${overdraw(MoonLevel.LOW)} 必须 ≤ ${MoonOpBudget.OVERDRAW_MAX_LOW}",
            overdraw(MoonLevel.LOW) <= MoonOpBudget.OVERDRAW_MAX_LOW
        )
        assertTrue(
            "MED = ${overdraw(MoonLevel.MEDIUM)} 必须 ≤ ${MoonOpBudget.OVERDRAW_MAX_MEDIUM}",
            overdraw(MoonLevel.MEDIUM) <= MoonOpBudget.OVERDRAW_MAX_MEDIUM
        )
    }

    /**
     * HIGH 档的**单调方向**断言（接替被裁决作废的 `MAX_HIGH`）：
     * HIGH 必须仍是铺得最满的一档 —— 表口径与实测口径**都**验，缺一边都可能被绕。
     */
    @Test
    fun `G4 fill 的 HIGH 必须铺得比 MED 与 LOW 更满`() {
        assertTrue(
            "表口径单调：${overdraw(MoonLevel.LOW)} ≤ ${overdraw(MoonLevel.MEDIUM)} ≤ ${overdraw(MoonLevel.HIGH)}",
            overdraw(MoonLevel.HIGH) >= overdraw(MoonLevel.MEDIUM) &&
                overdraw(MoonLevel.MEDIUM) >= overdraw(MoonLevel.LOW)
        )
        assertTrue(
            "实测口径单调：${MoonOpBudget.MEASURED_FILL_LOW} ≤ ${MoonOpBudget.MEASURED_FILL_MEDIUM} ≤ ${MoonOpBudget.MEASURED_FILL_HIGH}",
            MoonOpBudget.MEASURED_FILL_HIGH >= MoonOpBudget.MEASURED_FILL_MEDIUM &&
                MoonOpBudget.MEASURED_FILL_MEDIUM >= MoonOpBudget.MEASURED_FILL_LOW
        )
        assertTrue("棘轮：${overdraw(MoonLevel.HIGH)} ≤ ${MoonOpBudget.fillRatchetMax()}",
            overdraw(MoonLevel.HIGH) <= MoonOpBudget.fillRatchetMax())
    }

    /**
     * ⭐ **表不得低于实测**（§十一 T2「输入必须取半遮态那组实测」的机器化）。
     * 这一条专门拦"把某行悄悄改小"：门内断言只防超标，不防**低算** ——
     * 而低算才是"门禁在守一个不存在的帧"的那种失效（E43 的 `POST_FX.fill` 记 0 同源）。
     */
    @Test
    fun `G4 表是半遮态实测的上界`() {
        for (level in MoonLevel.entries) {
            val measured = when (level) {
                MoonLevel.LOW -> MoonOpBudget.MEASURED_OPS_LOW
                MoonLevel.MEDIUM -> MoonOpBudget.MEASURED_OPS_MEDIUM
                MoonLevel.HIGH -> MoonOpBudget.MEASURED_OPS_HIGH
            }
            assertTrue(
                "$level 表 ops ${MoonOpBudget.estimate(level)} 必须 ≥ 实测 $measured（表是上界）",
                MoonOpBudget.estimate(level) >= measured
            )
            val fill = when (level) {
                MoonLevel.LOW -> MoonOpBudget.MEASURED_FILL_LOW
                MoonLevel.MEDIUM -> MoonOpBudget.MEASURED_FILL_MEDIUM
                MoonLevel.HIGH -> MoonOpBudget.MEASURED_FILL_HIGH
            }
            assertTrue(
                "$level 表 fill ${overdraw(level)} 必须 ≥ 实测 $fill（⛔ 拿满月锁定态落表就红在这里）",
                overdraw(level) >= fill
            )
        }
    }

    @Test
    fun `G4 填充与画幅尺度无关`() {
        assertEquals(
            overdraw(MoonLevel.HIGH),
            MoonOpBudget.overdrawEstimate(3840f, 2160f, MoonLevel.HIGH),
            1e-4f
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  负向自证
    // ══════════════════════════════════════════════════════════════════════════

    /** §9.1 那句「记账必须按实测，名义值 60×5=300 会把预算表带偏」的机器化。 */
    @Test
    fun `负向自证 粼光按名义条数落表则 ops 爆表`() {
        val measured = MoonOpBudget.opsOf(MoonOpItem.GLITTER, MoonLevel.HIGH)
        assertEquals(137, measured)
        val broken = MoonOpBudget.estimate(MoonLevel.HIGH) - measured +
            MoonOpBudget.NOMINAL_GLITTER_OPS_HIGH
        assertEquals(398, broken)
        assertTrue("按名义条数落表后 $broken 必须 > 320", broken > MoonOpBudget.OPS_MAX_HIGH)
        // 规格点名的那个注入（"把 GLITTER 的 High 改成 400 必须红"）同样成立
        assertTrue(
            "400 条更必须爆表",
            MoonOpBudget.estimate(MoonLevel.HIGH) - measured + 400 > MoonOpBudget.OPS_MAX_HIGH
        )
    }

    /** §9.4「颗粒刻意不开」不能只靠一句注释：开一次就把棘轮撞破。 */
    @Test
    fun `负向自证 开颗粒则 HIGH 撞破棘轮`() {
        assertEquals(1.00, MoonOpBudget.GRAIN_EXTRA_FILL, 0.0)
        val broken = overdraw(MoonLevel.HIGH) + MoonOpBudget.GRAIN_EXTRA_FILL
        assertTrue("HIGH + 颗粒 = $broken 必须破棘轮 ${MoonOpBudget.fillRatchetMax()}",
            broken > MoonOpBudget.fillRatchetMax())
        // MED 档同理：颗粒会把 MED 顶到绝对门外
        assertTrue(overdraw(MoonLevel.MEDIUM) + MoonOpBudget.GRAIN_EXTRA_FILL >
            MoonOpBudget.OVERDRAW_MAX_MEDIUM)
    }

    /**
     * 「全屏 pass 记 0」是 E43 `SeaOpItem.POST_FX` 明确定性的**记账漏洞**。
     * 注入它 ⇒ 合计掉到实测**以下** ⇒ `G4 表是半遮态实测的上界` 必然红（不是门内断言红）。
     */
    @Test
    fun `负向自证 全屏pass记零则表掉到实测以下`() {
        val medWithVignetteZeroed = overdraw(MoonLevel.MEDIUM) - MoonOpItem.POST_FX.fillMed
        assertTrue(
            "MED 若把暗角记 0 = $medWithVignetteZeroed 必须 < 实测 ${MoonOpBudget.MEASURED_FILL_MEDIUM}",
            medWithVignetteZeroed < MoonOpBudget.MEASURED_FILL_MEDIUM
        )
        val lowWithCoalescerZeroed = overdraw(MoonLevel.LOW) - MoonOpItem.DAMAGE_COALESCER.fillLow
        assertTrue(
            "LOW 若漏掉基类洼地合成 = $lowWithCoalescerZeroed 必须 < 实测 ${MoonOpBudget.MEASURED_FILL_LOW}",
            lowWithCoalescerZeroed < MoonOpBudget.MEASURED_FILL_LOW
        )
    }

    /**
     * 新上限**不是**为了让门变绿而放宽的：裁决前的 `3.30 / 3.60 / 3.50` 对本表逐档判别。
     * 三条都红 ⇒ 抬上限是"由实测推出"，⛔ 不是"把门涂绿"（§十六 裁决 + 偏差 D5 同一条纪律）。
     */
    @Test
    fun `负向自证 旧上限会把本表逐档判红`() {
        assertTrue(overdraw(MoonLevel.LOW) > 3.30f)
        assertTrue(overdraw(MoonLevel.MEDIUM) > 3.60f)
        assertTrue(overdraw(MoonLevel.HIGH) > 3.50f)
        // 且旧表还有个符号错（Q9）：MAX_HIGH 3.50 < MAX_MED 3.60
        assertTrue(3.50f < 3.60f)
    }

    @Test
    fun `负向自证 圆盘按整屏方图烘则 native 爆表`() {
        val spec = MoonOpBudget.nativePxBudget(1920f, 1080f)
        val wrong = MoonOpBudget.nativePxBudgetWithDiskSide(1920f, 1080f, 2 * 1080)
        assertTrue("规格口径 = $spec 必须 ≤ 3,000,000", spec <= MoonOpBudget.NATIVE_PX_MAX)
        assertTrue("按整屏方图烘 = $wrong 必须 > 3,000,000", wrong > MoonOpBudget.NATIVE_PX_MAX)
    }

    @Test
    fun `拒绝非法画布尺寸`() {
        var threw = 0
        try {
            MoonOpBudget.overdrawEstimate(0f, 900f, MoonLevel.HIGH)
        } catch (e: IllegalArgumentException) {
            threw++
        }
        try {
            MoonOpBudget.nativePxBudgetWithDiskSide(-1f, 900f, 406)
        } catch (e: IllegalArgumentException) {
            threw++
        }
        try {
            MoonOpBudget.nativePxBudgetWithDiskSide(1920f, 1080f, -1)
        } catch (e: IllegalArgumentException) {
            threw++
        }
        assertEquals("三处入参校验必须都生效", 3, threw)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·③ native 堆
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `G4 native 在适用区间内达标`() {
        // 16:9、长边 ≤ 1920（§9.3 的实测口径）
        val sizes = listOf(1280 to 720, 1600 to 900, 1920 to 1080)
        for ((w, h) in sizes) {
            val n = MoonOpBudget.nativePxBudget(w.toFloat(), h.toFloat())
            assertTrue("nativePxBudget(${w}x$h) = $n 超出 ${MoonOpBudget.NATIVE_PX_MAX}",
                n <= MoonOpBudget.NATIVE_PX_MAX)
        }
        assertEquals(2_762_724, MoonOpBudget.nativePxBudget(1920f, 1080f))
    }

    /**
     * ⚠️ **门的适用区间是 ≤1080p，破点在 1440p**（偏差 **D18**）。
     *
     * §9.3 原文只写了「4K 必然突破」，实测算下来 ⛔ **1440p 就破**：`Id.STARFIELD` 是
     * **全屏**纹理（`ProceduralTexture.kt:199`，⛔ 没有降采样），`2560×1440 = 3,686,400`
     * **单项**即超 `NATIVE_PX_MAX = 3,000,000`。本测试是**哨兵**，钉住"破点在哪"这个事实：
     * 若哪天给 STARFIELD 加了按比例降采样，它会红 —— 那时该做的是把 [MoonOpBudget] 的
     * `NATIVE_PX_OK_MAX_LONG_EDGE` 抬上去并在 §9.3 留痕，⛔ 不是删掉这条。
     */
    @Test
    fun `G4 native 的破点是 1440p 哨兵`() {
        val star1440 = MoonOpBudget.starfieldPx(2560f, 1440f)
        assertTrue("1440p 的 STARFIELD 单项 $star1440 必须已破 3,000,000",
            star1440 > MoonOpBudget.NATIVE_PX_MAX)
        val total1440 = MoonOpBudget.nativePxBudget(2560f, 1440f)
        assertTrue("1440p 合计 $total1440 必须破", total1440 > MoonOpBudget.NATIVE_PX_MAX)
        // 1080p 的 STARFIELD 占预算 69%，其余两项合计只占 23%
        val star1080 = MoonOpBudget.starfieldPx(1920f, 1080f)
        assertEquals(2_073_600L, star1080)
        assertTrue(star1080 > MoonOpBudget.NATIVE_PX_MAX * 0.6)
        // 门的适用区间常量与实测边界一致
        assertEquals(1920, MoonOpBudget.NATIVE_PX_OK_MAX_LONG_EDGE)
        assertEquals(2560, MoonOpBudget.NATIVE_PX_BREAK_LONG_EDGE)
        // ⛔ 门内测试⛔ 不许把 1440p 也列进去（那会让本效果唯一的共享大头项变成隐性破口）
        assertFalse(MoonOpBudget.NATIVE_PX_BREAK_LONG_EDGE <= MoonOpBudget.NATIVE_PX_OK_MAX_LONG_EDGE)
    }

    /** 圆盘边长走 [MoonDiskBake.texRadius]（⛔ 不复制常数）⇒ 逐档钉住实测边长。 */
    @Test
    fun `G4 圆盘边长与烘焙层同源`() {
        assertEquals(406, MoonOpBudget.diskSidePx(1080f))    // moonR 162 → texR 203
        assertEquals(540, MoonOpBudget.diskSidePx(1440f))    // moonR 216 → texR 270
        assertEquals(810, MoonOpBudget.diskSidePx(2160f))    // moonR 324 → texR 405
        assertEquals(384, MoonOpBudget.diskSidePx(720f))     // 被 TEX_R_MIN=192 钳住
        // 分解式必须恰好等于合计（⛔ 没有第四张位图、⛔ 回退源图不重复计）
        val w = 1920f
        val h = 1080f
        val parts = MoonOpBudget.srcPx() +
            MoonOpBudget.diskSidePx(minOf(w, h)).toLong() * MoonOpBudget.diskSidePx(minOf(w, h)) +
            MoonOpBudget.starfieldPx(w, h)
        assertEquals(parts.toInt(), MoonOpBudget.nativePxBudget(w, h))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  几何式 / 漂移对照
    // ══════════════════════════════════════════════════════════════════════════

    /** 表里那三个「几何恒等式」行必须真的能从几何推出来 —— 推不出就说明构图漂了。 */
    @Test
    fun `几何恒等式行与几何式吻合`() {
        assertEquals(MOON_HORIZON_FILL, MoonOpBudget.starsFillGeometry(), 1e-3)
        assertEquals(MOON_DISK_FILL, MoonOpBudget.diskFillGeometry(), 1e-3)
        assertEquals(MoonOpItem.HORIZON_BAND.fillHigh, MoonOpBudget.horizonBandFillGeometry(), 1e-3)
        // SKY_BASE 的 1.000：两块不相交矩形拼满整屏 ⇒ 恰好一屏（⛔ 不是两屏）
        assertEquals(1.000, MoonOpItem.SKY_BASE.fillHigh, 1e-12)
        assertEquals(MOON_HORIZON_FILL, MoonOpItem.STARS.fillHigh, 1e-12)
        // 地平带通铺全宽 ⇒ 带高就是填充，⛔ 与天空占比无关（D26）
        assertEquals(0.16, MoonOpBudget.horizonBandFillGeometry(), 1e-3)
        assertTrue(MoonOpBudget.horizonBandFullyOnScreen())
    }

    /**
     * **D26 的负向自证**：探针那行 `op('HZ', 0.16 × horizonK)` 把带子折成了"只有天空那份"，
     * 而实际矩形是 `fillRect(0, horizonY − 0.10h, W, 0.16h)` —— 全宽、且横跨地平线两侧。
     * 照探针口径落表 ⇒ 表**低于**真实光栅化面积，正是 §T2「表必须是上界」要拦的方向。
     */
    @Test
    fun `负向自证 地平带按探针口径落表则低于真实矩形面积`() {
        val probeAccounting = 0.16 * MoonlitRenderer.HORIZON_K
        val trueRect = MoonOpBudget.horizonBandFillGeometry()
        assertTrue("探针口径 $probeAccounting 必须 < 真实 $trueRect", probeAccounting < trueRect)
        assertEquals(0.102, probeAccounting, 1e-3)
        assertEquals(trueRect, MoonOpItem.HORIZON_BAND.fillHigh, 1e-3)
        // 地平线一旦把带子顶出屏，几何式必须跟着变小（哨兵：门不是写死的 0.16）
        assertEquals(0.16, MoonOpBudget.horizonBandFillGeometry(0.640f), 1e-3)
        assertEquals(0.10, MoonOpBudget.horizonBandFillGeometry(0.040f), 1e-3)
        assertEquals(0.0, MoonOpBudget.horizonBandFillGeometry(-0.50f), 1e-3)
        assertFalse(MoonOpBudget.horizonBandFullyOnScreen(0.040f))
    }

    /**
     * ⛔ **本类最容易过期的一条**：门禁与渲染器/烘焙层共用同一批几何常数。
     * 改了 `MoonlitRenderer.HORIZON_K` / `MOON_R_K` / `MoonDiskBake.TEX_SCALE` ⇒ 这里红，
     * 意思是「门禁正在守另一个构图」，⛔ 不是把这里的期望值改掉就完事（要一起回写 §9.2）。
     */
    @Test
    fun `预算常数与渲染器常数不漂移`() {
        assertEquals(MoonlitRenderer.HORIZON_K.toDouble(), MOON_HORIZON_FILL, 1e-3)
        // 月盘填充的几何式含 TEX_SCALE 与 MOON_R_K ⇒ 两者任一变动即显形
        assertEquals(0.062, MoonOpBudget.diskFillGeometry(), 1e-3)
        val scale = MoonDiskBake.TEX_SCALE.toDouble()
        assertEquals(
            MOON_DISK_FILL / (scale * scale) * MOON_TERM_LAYERS,
            MOON_TERM_FILL, 1e-9
        )
        assertEquals(3, MOON_TERM_LAYERS)
        assertEquals(1024 * 512, MoonOpBudget.srcPx())
    }

    /** 便捷：本测试里"表口径"的填充一律用 1600×900（与原型判读画幅同值）。 */
    private fun overdraw(level: MoonLevel): Float =
        MoonOpBudget.overdrawEstimate(1600f, 900f, level)
}

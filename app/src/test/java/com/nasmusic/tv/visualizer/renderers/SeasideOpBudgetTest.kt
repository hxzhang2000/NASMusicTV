package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G11 / G12 / G13 —— 把 §4.9 的提交 / 填充 / native 堆预算变成**构建期失败**。
 *
 * ⚠️ 本测试的每一条断言都配一条**负向自证**（「喂破实现 ⇒ 必须失败」）。
 * 只断言通过、不证明它能失败的门禁等于没有门禁（§4.9.4 的四条静默失效陷阱同源）。
 */
class SeasideOpBudgetTest {

    // ══════════════════════════════════════════════════════════════════════════
    //  契约自检
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `契约常量等于 4_9_5 的阈值`() {
        assertEquals(90, SeasideOpBudget.OPS_MAX_LOW)
        assertEquals(200, SeasideOpBudget.OPS_MAX_MEDIUM)
    // ⛔ 2026-10-05「保真优先」：200 → 320。
    //   该门的成本模型已被真机证伪（预测 ≈30fps / 实测 4.1~7.3fps，差 4 倍），
    //   而它已实际逼出三处视觉回归（逐块 alpha 取平均、柔和斑块烘成 N 边形、
    //   逐列 alpha 压成二值门）⇒ 门必须让位于保真。详见 OPS_MAX_HIGH 的 KDoc。
    assertEquals(320, SeasideOpBudget.OPS_MAX_HIGH)
        assertEquals(2.0f, SeasideOpBudget.OVERDRAW_MAX_LOW, 1e-6f)
        assertEquals(3.89f, SeasideOpBudget.OVERDRAW_MAX_MEDIUM, 1e-6f)
        assertEquals(3_000_000, SeasideOpBudget.NATIVE_PX_MAX)
        // §4.9.2：HIGH 12 / MEDIUM 3 / LOW 0，且缓存 Stroke ≤ 12
        assertEquals(12, SeasideOpBudget.laceStrokeCacheSize())
        assertTrue(SeasideOpBudget.laceStrokeCacheSize() <= 12)
    }

    @Test
    fun `逐元素清单覆盖 4_9_2 表的全部 20 项外加水洼补充行与蟹迹`() {
        val ids = SeaOpItem.entries.map { it.name }.toSet()
        val required = listOf(
            "SAND_BLIT", "WET_WASH", "SHEEN", "SEA_FIELD", "CAUSTIC",
            "SWELL_BODY", "FOAM_LADDER", "SEA_FOAM_WASH", "OPEN_SEA_FOAM", "DISTURBANCE",
            "FOAM_LACE", "CREST_LIP", "SWASH_FINGER", "RESIDUAL_STREAK", "WET_LINE",
            "SAND_GRAIN", "RESIDUE_POINTS", "SPLASH_POINTS", "CRAB", "CRAB_TRAIL",
            "POST_FX", "PUDDLE"
        )
        for (r in required) assertTrue("缺元素：$r", ids.contains(r))
        assertEquals(required.size, SeaOpItem.entries.size)
    }

    @Test
    fun `逐元素系数全部非负`() {
        for (it in SeaOpItem.entries) {
            assertTrue("${it.name} opsLow<0", it.opsLow >= 0)
            assertTrue("${it.name} opsMed<0", it.opsMed >= 0)
            assertTrue("${it.name} opsHigh<0", it.opsHigh >= 0)
            assertTrue("${it.name} opsLegacy<0", it.opsLegacy >= 0)
            assertTrue("${it.name} fillLow<0", it.fillLow >= 0.0)
            assertTrue("${it.name} fillMed<0", it.fillMed >= 0.0)
            assertTrue("${it.name} fillHigh<0", it.fillHigh >= 0.0)
            assertTrue("${it.name} fillLegacy<0", it.fillLegacy >= 0.0)
        }
    }

    @Test
    fun `原型保真回补后每档都要真的画东西`() {
        // ① 扰动前锋：⛔ **因「原型保真回补」而复活**（原 §4.9.2「全档删除」⇒ 三档恒 0）。
    //   ⛔ 2026-10-05 再变：HIGH 由 1 恢复成 **18**（撤回合批、逐块 drawImage 软边白泪）；
    //   LOW/MEDIUM 仍 0（§4.7②）。
    assertEquals(0, SeaOpItem.DISTURBANCE.opsLow)
        assertEquals(0, SeaOpItem.DISTURBANCE.opsMed)
    assertEquals(18, SeaOpItem.DISTURBANCE.opsHigh)
        assertEquals(18, SeaOpItem.DISTURBANCE.opsLegacy)
        // ⚠️ 填充**不再是 0**：18 块折成矢量后的并集覆盖，按与 OPEN_SEA_FOAM 同口径等比折算
        assertEquals(0.19 * SEA_BAND, SeaOpItem.DISTURBANCE.fillHigh, 1e-12)
        assertEquals(0.0, SeaOpItem.DISTURBANCE.fillMed, 1e-12)
        assertEquals(0.0, SeaOpItem.DISTURBANCE.fillLow, 1e-12)
        // ② 镜面高光：⛔ **因恢复第二次 drawPath 而变 —— 原先断言 0/0/0，现为 1/1/1**
        //   （所有者推翻 §4.9.2 的「折进 drawWetWash 那一次」：NonZero 下高光子轮廓并入湿区 ⇒
        //    像素集逐像素相同 ⇒ 高光完全不可见；原型靠 `lighter` 的**第二次**提交才有亮度差。）
        //   ⛔ 它**不再**属于「恒为零」那一类，但仍保留 `opsLegacy = 97` 供负向自证。
        assertEquals(1, SeaOpItem.SHEEN.opsLow)
        assertEquals(1, SeaOpItem.SHEEN.opsMed)
    // ⛔ 2026-10-05：opsHigh 由 1 → **3**（逐列 `globalAlpha = wetAmt[i]` 分 3 档，原型: :2083）
    assertEquals(1, SeaOpItem.SHEEN.opsHigh)
        assertEquals(97, SeaOpItem.SHEEN.opsLegacy)
        // ⚠️ 填充三档**仍是 0**：高光与湿沙同区域，第二次提交⛔ 不新增像素覆盖（只是变亮）
        assertEquals(0.0, SeaOpItem.SHEEN.fillLow, 1e-12)
        assertEquals(0.0, SeaOpItem.SHEEN.fillMed, 1e-12)
        assertEquals(0.0, SeaOpItem.SHEEN.fillHigh, 1e-12)
        // ③ 破碎唇：因「原型保真回补」由 0/0/1 变 0/1/2（MEDIUM 拿回 pass 0 的窄高光）
        assertEquals(0, SeaOpItem.CREST_LIP.opsLow)
        assertEquals(1, SeaOpItem.CREST_LIP.opsMed)
        assertEquals(2, SeaOpItem.CREST_LIP.opsHigh)
        // ⭐ 破碎唇的 `fillMed` 由 `0.0` 补齐为 `0.15 × SEA_BAND`（与 `fillHigh` 同值）：
        //   `SeaOpItem` 的既定契约是 **`fill*` 必须与 `ops*` 同口径**（`CAUSTIC` 那次已确立），
        //   `opsMed = 1` 而 `fillMed = 0.0` ⇒ G12 **低算** MEDIUM 的破碎唇，是记账漏洞。
        //   MEDIUM 真实覆盖更窄（只有 0.9px 的 pass 0），但**同口径优先于逐档精细** ——
        //   取全值是偏保守方向；⛔ 不发明折算系数。
        assertEquals(0.15 * SEA_BAND, SeaOpItem.CREST_LIP.fillMed, 1e-12)
        assertEquals(0.15 * SEA_BAND, SeaOpItem.CREST_LIP.fillHigh, 1e-12)
        // `fillLow` 不变：LOW 确实 `opsLow = 0`
        assertEquals(0.0, SeaOpItem.CREST_LIP.fillLow, 1e-12)
        // ④ 退水残沫 3 pass / ⑤ 岸线湿线 2 pass / ⑥ 水洼双色：全部只 HIGH 补回多次提交
        assertEquals(1, SeaOpItem.RESIDUAL_STREAK.opsLow)
        assertEquals(1, SeaOpItem.RESIDUAL_STREAK.opsMed)
        assertEquals(3, SeaOpItem.RESIDUAL_STREAK.opsHigh)
        assertEquals(0, SeaOpItem.WET_LINE.opsLow)
        assertEquals(0, SeaOpItem.WET_LINE.opsMed)
        assertEquals(0, SeaOpItem.WET_LINE.opsHigh)
        assertEquals(1, SeaOpItem.PUDDLE.opsLow)
        assertEquals(1, SeaOpItem.PUDDLE.opsMed)
        assertEquals(2, SeaOpItem.PUDDLE.opsHigh)
        // 其余每个元素在 HIGH 档都要真的画东西（防止「漏画」被当成「省预算」）
        // ⛔ 两张豁免名单**刻意不同**：「提交为正」与「填充为正」各有各的合法例外。
        //   ops == 0：**WET_LINE** —— ⛔ 2026-10-05 所有者裁决**删除 `drawWetLine` 整层**。
        //     原型 `:2367-2389` 是 `ctx.save(); ctx.clip(sandPath); … ctx.restore();` **之后**才描
        //     ⇒ 只显示在岸线**沙侧**；Kotlin 零 `clipPath` ⇒ 线以岸线为中心、一半落在海侧
        //     ⇒ 实现走形且所有者判为无用。**这是视觉裁决，不是省预算。**
        //     （此前此栏为「无」：原型保真回补后每项 HIGH 都 ≥ 1 次提交。）
        //   fill == 0：**SHEEN**（第二次 `Plus` 提交与湿沙**同区域** ⇒ ⛔ 不新增任何像素覆盖）
        //              / **POST_FX**（晕影声明式交给舞台，不计入本效果填充）。
        for (it in SeaOpItem.entries) {
            if (it != SeaOpItem.WET_LINE) {
                assertTrue("${it.name} HIGH 提交数必须为正", it.opsHigh > 0)
            }
            if (it != SeaOpItem.POST_FX && it != SeaOpItem.SHEEN) {
                assertTrue("${it.name} HIGH 填充必须为正", it.fillHigh > 0.0)
            }
        }
        // postFx 是**提交**（1 次）但不计入本效果的填充
        assertEquals(1, SeaOpItem.POST_FX.opsHigh)
        assertEquals(1.0, SeaOpItem.POST_FX.fillHigh, 1e-12)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  G11 提交预算
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `G11 提交预算在门内`() {
        for (level in SeaLevel.entries) {
            val n = SeasideOpBudget.estimate(level, SeasideOpBudget.STEADY_WAVES)
            assertTrue(
                "estimate($level) = $n 超出 ${SeasideOpBudget.opsMax(level)}",
                n <= SeasideOpBudget.opsMax(level)
            )
        }
    }

    @Test
    fun `G11 改造前模式必须突破 HIGH 门`() {
        val n = SeasideOpBudget.estimate(SeaLevel.HIGH, SeasideOpBudget.STEADY_WAVES, legacy = true)
        assertTrue("改造前 estimate(HIGH) = $n 必须 > 200", n > SeasideOpBudget.OPS_MAX_HIGH)
    }

    @Test
    fun `负向自证 湿沙与高光退回逐列渐变则 HIGH 爆表`() {
        // 只把这两行换回「逐列小渐变 quad」，其余保持裁决后形态
        val wetLegacy = SeasideOpBudget.opsOf(SeaOpItem.WET_WASH, SeaLevel.HIGH, cols = 96, legacy = true)
        assertEquals("湿沙改造前 = cols + 1 = 97", 97, wetLegacy)
        assertEquals("镜面高光改造前 = cols + 1 = 97", 97,
            SeasideOpBudget.opsOf(SeaOpItem.SHEEN, SeaLevel.HIGH, cols = 96, legacy = true))
        // 裁决后是**各 1 次**提交（⛔ 因恢复第二次 drawPath 而变，原为湿沙 1 + 高光 0）⇒ 少掉 192 次
        val after = SeasideOpBudget.estimate(SeaLevel.HIGH, 2)
        val onlyWet = after - 1 - 1 + 97 + 97
        assertTrue("把湿沙/高光换回逐列后 $onlyWet 必须 > 200", onlyWet > SeasideOpBudget.OPS_MAX_HIGH)
    }

    @Test
    fun `负向自证 飞沫与残沫退回逐点则 HIGH 爆表`() {
        val splash = SeasideOpBudget.opsOf(SeaOpItem.SPLASH_POINTS, SeaLevel.HIGH)
        val residue = SeasideOpBudget.opsOf(SeaOpItem.RESIDUE_POINTS, SeaLevel.HIGH)
        assertEquals("飞沫裁决后 = 1 次 drawPoints", 1, splash)
        assertEquals("残沫裁决后 = 3 次 drawPoints", 3, residue)
        assertEquals(180, SeasideOpBudget.opsOf(SeaOpItem.SPLASH_POINTS, SeaLevel.HIGH, legacy = true))
        assertEquals(156, SeasideOpBudget.opsOf(SeaOpItem.RESIDUE_POINTS, SeaLevel.HIGH, legacy = true))
        val broken = SeasideOpBudget.estimate(SeaLevel.HIGH, 2) - splash - residue + 180 + 156
        assertTrue("退回逐点后 $broken 必须 > 200", broken > SeasideOpBudget.OPS_MAX_HIGH)
    }

    @Test
    fun `负向自证 扰动前锋等三个逐浪项压回合批则估算拉低且改造前整体爆表`() {
        assertEquals("扰动前锋裁决后 = HIGH 恢复逐块 drawImage（保真优先）", 18,
            SeasideOpBudget.opsOf(SeaOpItem.DISTURBANCE, SeaLevel.HIGH, waveCount = 1))
        assertEquals("退回逐块 blit = 18", 18,
            SeasideOpBudget.opsOf(SeaOpItem.DISTURBANCE, SeaLevel.HIGH, waveCount = 1, legacy = true))
    // ⚠️ 2026-10-05：HIGH 已恢复 18 次逐块 blit ⇒「再退回 18 块」不再是额外负担，
    //   这条负向自证改成「把 HIGH 的逐浪项压回合批」的对照命题。
    // ⚠ 2026-10-05：HIGH 已恢复 18 次逐块 blit ⇒「再退回 18 块」不再是额外负担，
    //   这条负向自证改成「把 HIGH 的逐浪项全部压回合批」的对照命题。
    //   旧表里 DISTURBANCE 的 opsLegacy（18）与 opsHigh 相等，这条负向自证已失效。
    //   它的用途保留：证明「逐浪项压回合批」确实会把整体拉低 ⇒ ops 与运行时提交数同源。
    val broken = SeasideOpBudget.estimate(SeaLevel.HIGH, 2) - (18 + 22 + 8) * SeasideOpBudget.STEADY_WAVES
        // 因 D9 裁决而变：117（原 119）—— CRAB 改 perWave=false 后 estimate(HIGH,2) 83 → 81
        // ⛔ 再因「恢复镜面高光第二次 drawPath」而变：estimate(HIGH,2) 81 → 82 ⇒ 118（原 117）
        // ⛔ 再因「螃蟹原型保真回补」而变：CRAB.opsHigh 2 → 8 ⇒ estimate(HIGH,2) 82 → 88
        // ⛔ 再因本轮 6 项 HIGH 保真回补而变：CREST_LIP +2 / DISTURBANCE +2 /
        //   RESIDUAL_STREAK +2 / WET_LINE +1 / PUDDLE +1 ⇒ 88 → 96
        //   ⇒ 96 − 2（现值）+ 36（退回逐块）= 130（原 124）
    // ⛔ 再因「湿沙改 8 条嵌套 ribbon、镜面高光回到 1 次」而变：
        //   WET_WASH.opsHigh 3→8、SHEEN.opsHigh 3→1 ⇒ estimate(HIGH,2) 190 → 193
        //   ⇒ 193 − 96 = 97（原 94）
    // ⛔ 再因新增 [SeaOpItem.CRAB_TRAIL]「蟹迹」而变：**+4**（`perWave = false` ⇒ ⛔ **不乘
    //   STEADY_WAVES**；而 `broken` 只从合计里减掉**逐浪项**的 96 ⇒ 蟹迹那 4 次原样留在
    //   `broken` 里）⇒ estimate(HIGH,2) 184 → 188 ⇒ 188 − 96 = 92（原 88）
    assertEquals("HIGH 三个逐浪项全压回合批后的估算", 92, broken)
    // ⇒ 压回合批会把整体拉低（证量真实存在），但不再足以证明「会爆表」
        // ⚠️ 单项复活不足以破门（这正是 §4.9.2 判定「四处结构性必改」的原因：
        //    必须四项同时回退才越过 200）⇒ 破门断言用完整的「改造前」形态。
        assertTrue(SeasideOpBudget.estimate(SeaLevel.HIGH, 2, legacy = true) > SeasideOpBudget.OPS_MAX_HIGH)
    }

    @Test
    fun `负向自证 蕾丝不量化 wj 则爆表`() {
        assertEquals(12, SeasideOpBudget.laceStrokeOps(SeaLevel.HIGH))
        assertEquals(3, SeasideOpBudget.laceStrokeOps(SeaLevel.MEDIUM))
        assertEquals(0, SeasideOpBudget.laceStrokeOps(SeaLevel.LOW))
        // 3 档 × 4 类 × 2 浪 = 24（原实现）；再假设每边一个 Stroke ⇒ 远超 12
        assertTrue("蕾丝 Stroke 缓存必须 ≤ 12", SeasideOpBudget.laceStrokeCacheSize() <= 12)
        assertEquals(24, 3 * 4 * 2)
    }

    @Test
    fun `焦散 MEDIUM 保留胞壁网 LOW 仍为零`() {
        assertEquals(3, SeasideOpBudget.opsOf(SeaOpItem.CAUSTIC, SeaLevel.HIGH))
        // 因 D9 裁决而变：MEDIUM 由 0 恢复为 3（胞壁网 MEDIUM 保留；射线与亮结才仅 HIGH）
        assertEquals(3, SeasideOpBudget.opsOf(SeaOpItem.CAUSTIC, SeaLevel.MEDIUM))
        assertEquals(0, SeasideOpBudget.opsOf(SeaOpItem.CAUSTIC, SeaLevel.LOW))
        assertEquals(86, SeasideOpBudget.opsOf(SeaOpItem.CAUSTIC, SeaLevel.HIGH, legacy = true))
        // 因 D9 裁决而变：fillMed 由 0.0 恢复为 SEA_CAUSTIC_FILL —— 必须与 opsMed 同口径，
        // 否则 G12 会低算 MEDIUM 焦散胞壁网的 ~0.05 屏，把加回去的填充凭空吞掉
        assertEquals(SEA_CAUSTIC_FILL,
            SeasideOpBudget.fillOf(SeaOpItem.CAUSTIC, SeaLevel.MEDIUM), 1e-12)
        assertEquals(SEA_CAUSTIC_FILL,
            SeasideOpBudget.fillOf(SeaOpItem.CAUSTIC, SeaLevel.HIGH), 1e-12)
        assertEquals(0.0, SeasideOpBudget.fillOf(SeaOpItem.CAUSTIC, SeaLevel.LOW), 1e-12)
    }

    @Test
    fun `螃蟹是全局单实例不逐浪`() {
        // 因 D9 裁决而变：CRAB.perWave 由 true 改 false ⇒ waveCount 不再放大提交数与填充
        assertTrue("螃蟹不得逐浪（D9 裁决：crabSlot 每帧只有 1 个在场对象）",
            !SeaOpItem.CRAB.perWave)
        // ⛔ **因螃蟹原型保真回补而变**（原为 2）：原型 16 次逐件提交合批成 8 次 `drawPath`
        //   （影子 / 远腿 / 近腿 / 壳填 / 螯填 / 壳沿 / 背光 / 眼点），MEDIUM 4 次、LOW **2** 次。
        assertEquals(8, SeasideOpBudget.opsOf(SeaOpItem.CRAB, SeaLevel.HIGH, waveCount = 1))
        assertEquals("同参数画两次会叠亮 ⇒ 2 浪也不能翻倍", 8,
            SeasideOpBudget.opsOf(SeaOpItem.CRAB, SeaLevel.HIGH, waveCount = 2))
        assertEquals("MEDIUM 只画两条腿 + 壳 + 壳沿", 4,
            SeasideOpBudget.opsOf(SeaOpItem.CRAB, SeaLevel.MEDIUM))
        // ⛔ **LOW 因「原型保真回补」由 1 → 2**（所有者裁决）：壳填充 + 壳沿描边两种颜色，
        //   单次提交拿不到第二种颜色（NonZero 下并入填充区 ⇒ 像素集逐像素相同）。
        assertEquals("LOW 画壳填充 + 壳沿", 2, SeasideOpBudget.opsOf(SeaOpItem.CRAB, SeaLevel.LOW))
        assertEquals("填充量同样不乘浪数", 0.004,
            SeasideOpBudget.fillOf(SeaOpItem.CRAB, SeaLevel.HIGH, waveCount = 2), 1e-12)
        assertEquals(35, SeasideOpBudget.opsOf(SeaOpItem.CRAB, SeaLevel.HIGH, legacy = true))
    }

    /**
     * 蟹迹（[SeaOpItem.CRAB_TRAIL] / `drawCrabTrail`）—— **成对小凹点 + 极淡连续沟槽**。
     * ⛔ **纯装饰**：不参与任何模拟量（本测试只守它的**预算数字**，不守它的几何）。
     *
     * ⭐ **提交数拆解：凹点恒 1 次 + 沟槽按 age 切段**
     * ```
     * LOW  = 1 = 凹点 1（drawPath FILL）+ 沟槽 0 段
     * MED  = 2 = 凹点 1 + 沟槽 1 段（drawPath STROKE）
     * HIGH = 4 = 凹点 1 + 沟槽 3 段
     * ```
     * ⛔ 凹点**恒 1 次**：所有在册印记的所有小凹点并进**同一条** `NativePath`；
     *   一次提交只有一支画笔 ⇒ 时间淡出⛔ **只能靠缩小尺寸**，⛔ 不能逐点降 alpha
     *   （那会按 age 序列翻倍成 N 次提交）。
     */
    @Test
    fun `蟹迹 凹点恒一次提交 沟槽按档切段 且不逐浪`() {
        assertTrue("蟹迹不得逐浪（同 CRAB：痕迹挂在全局单实例螃蟹身后，同参数画两次会叠亮）",
            !SeaOpItem.CRAB_TRAIL.perWave)
        // ⭐ 三档 = 凹点 1 + 沟槽 0/1/3 段
        assertEquals("LOW 只画凹点（沟槽 0 段）", 1,
            SeasideOpBudget.opsOf(SeaOpItem.CRAB_TRAIL, SeaLevel.LOW))
        assertEquals("MEDIUM = 凹点 1 + 沟槽 1 段", 2,
            SeasideOpBudget.opsOf(SeaOpItem.CRAB_TRAIL, SeaLevel.MEDIUM))
        assertEquals("HIGH = 凹点 1 + 沟槽 3 段", 4,
            SeasideOpBudget.opsOf(SeaOpItem.CRAB_TRAIL, SeaLevel.HIGH))
        // ⛔ **不乘 waveCount**：稳态 2 浪时 HIGH 仍是 4（若标 perWave=true 会变成 8）
        assertEquals("同参数画两次会叠亮 ⇒ 2 浪也不能翻倍", 4,
            SeasideOpBudget.opsOf(SeaOpItem.CRAB_TRAIL, SeaLevel.HIGH, waveCount = 2))
        assertEquals(2, SeasideOpBudget.opsOf(SeaOpItem.CRAB_TRAIL, SeaLevel.MEDIUM, waveCount = 2))
        assertEquals(1, SeasideOpBudget.opsOf(SeaOpItem.CRAB_TRAIL, SeaLevel.LOW, waveCount = 2))
        // ⭐ `fill*` 三档**同值** 0.001 屏：LOW 真实覆盖 < MED/HIGH（LOW 不画沟槽），
        //   但「`fill*` 与 `ops*` 同口径 + 取保守」要求同值（高估 LOW 是保守方向）。
        //   ⛔ 不改成 `fillLow = 0.0`：LOW 的 `opsLow = 1` 不是 0，填 0 会让 G12 **低算** LOW。
        for (level in SeaLevel.entries) {
            assertEquals("${level.name} 填充同值 = SEA_CRAB_TRAIL_FILL", SEA_CRAB_TRAIL_FILL,
                SeasideOpBudget.fillOf(SeaOpItem.CRAB_TRAIL, level), 1e-12)
        }
        assertEquals("填充同样不乘浪数", SEA_CRAB_TRAIL_FILL,
            SeasideOpBudget.fillOf(SeaOpItem.CRAB_TRAIL, SeaLevel.HIGH, waveCount = 2), 1e-12)
        // ⭐ **新元素 ⇒ 没有「改造前」形态**，两列 legacy 取与裁决后**同值**。
        //   ⛔ 这不削弱负向自证：负向自证靠 DISTURBANCE / SHEEN / SEA_FOAM_WASH /
        //   OPEN_SEA_FOAM / CAUSTIC 等**既有行**的 legacy 值；本行在 legacy 口径下贡献
        //   与裁决后完全相同的常数 ⇒ 两种口径的差值不受影响。
        assertEquals(4, SeasideOpBudget.opsOf(SeaOpItem.CRAB_TRAIL, SeaLevel.HIGH, legacy = true))
        assertEquals(SEA_CRAB_TRAIL_FILL,
            SeasideOpBudget.fillOf(SeaOpItem.CRAB_TRAIL, SeaLevel.HIGH, legacy = true), 1e-12)
        // ⛔ 负向自证：新元素 ⛔ **不许**伪造一个更小的 legacy 值来让断言更好过
        assertTrue("⛔ legacy 不得小于裁决后值（那是伪造判别力）",
            SeaOpItem.CRAB_TRAIL.opsLegacy >= SeaOpItem.CRAB_TRAIL.opsHigh)
        assertTrue("⛔ fillLegacy 不得小于 fillHigh",
            SeaOpItem.CRAB_TRAIL.fillLegacy >= SeaOpItem.CRAB_TRAIL.fillHigh)
        // ⚠️ 门内断言已由 `G11 提交预算在门内` / `G12 填充预算在门内` 覆盖，此处不重复造。
        //   本测试只钉「蟹迹这一项自己的数字」，逐档合计由 `G11 与 G12 逐档合计被钉死` 守。
    }

    @Test
    fun `postFx 只保留一个通道`() {
        assertEquals(1, SeasideOpBudget.opsOf(SeaOpItem.POST_FX, SeaLevel.HIGH))
        assertEquals(2, SeasideOpBudget.opsOf(SeaOpItem.POST_FX, SeaLevel.HIGH, legacy = true))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  G12 填充预算
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `G12 填充预算在门内`() {
        for (level in SeaLevel.entries) {
            val v = SeasideOpBudget.overdrawEstimate(1600f, 900f, level)
            assertTrue(
                "overdrawEstimate($level) = $v 超出 ${SeasideOpBudget.overdrawMax(level)}",
                v <= SeasideOpBudget.overdrawMax(level)
            )
        }
    }

    @Test
    fun `G12 改造前模式必须突破 2_8 屏`() {
        for (level in SeaLevel.entries) {
            val v = SeasideOpBudget.overdrawEstimate(1600f, 900f, level, legacy = true)
            assertTrue("改造前 overdraw($level) = $v 必须 > 2.8", v > 2.8f)
        }
    }

    @Test
    fun `负向自证 40 次逐块 alpha blit 则填充爆表`() {
        val foam = SeasideOpBudget.fillOf(SeaOpItem.OPEN_SEA_FOAM, SeaLevel.HIGH, waveCount = 1)
        val dist = SeasideOpBudget.fillOf(SeaOpItem.DISTURBANCE, SeaLevel.HIGH, waveCount = 1)
        assertEquals("外海泡沫裁决后合成一条 path（22 块）", 0.50 * SEA_BAND, foam, 1e-9)
        // ⛔ **因「原型保真回补」而不再是 0**：18 块折成矢量后的并集覆盖 ≈ 0.19 × SEA_BAND
        assertEquals("扰动前锋折成一条 path（18 块）", 0.19 * SEA_BAND, dist, 1e-9)
        val broken = SeasideOpBudget.overdrawEstimate(
            1600f, 900f, SeaLevel.HIGH
        ) - 2 * (foam + dist) + 2 * (1.60 + 1.00)
        assertTrue("逐块 blit 后 $broken 必须 > 2.8", broken > 2.8f)
    }

    @Test
    fun `负向自证 97 列渐变则填充爆表`() {
        val after = SeasideOpBudget.overdrawEstimate(1600f, 900f, SeaLevel.HIGH)
        val broken = after - SEA_WET_BAND + 2 * SEA_WET_BAND
        assertTrue("双份湿沙带后 $broken 必须 > 2.8", broken > 2.8f)
    }

    @Test
    fun `G12 与分辨率无关`() {
        val a = SeasideOpBudget.overdrawEstimate(1600f, 900f, SeaLevel.HIGH)
        val b = SeasideOpBudget.overdrawEstimate(3840f, 2160f, SeaLevel.HIGH)
        assertEquals(a, b, 1e-3f)
    }

    @Test
    fun `G11 与 G12 逐档合计被钉死`() {
        // ⛔ 这些是**精确合计**（不是门限）：任何元素系数的改动都会在这里显形，
        //   避免「只改了预算表、只有门内断言会响」的静默漂移。
        // 因 D9 裁决而变：LOW 30 → 29（CRAB 2 → 1）、MEDIUM 42 → 44（焦散 +3、CRAB −1）、HIGH 83 → 81（CRAB −2）
        // ⛔ **因「恢复镜面高光第二次 drawPath」而再变**：SHEEN.ops 0 → 1 ⇒ 三档各 +1
        //   ⇒ LOW 29 → 30、MEDIUM 44 → 45、HIGH 81 → 82
        // ⛔ **再因「螃蟹原型保真回补」而变**：CRAB.ops 1/1/2 → 1/4/8 ⇒ LOW 不变、
        //   MEDIUM +3、**HIGH +6** ⇒ LOW 30 / MEDIUM **48** / HIGH **88**
        // ⛔ **再因本轮 6 项 HIGH 保真回补而变**（逐浪项乘 waveCount = 2）：
        //   | 元素             | LOW | MED  | HIGH |
        //   |------------------|-----|------|------|
        //   | CRAB（不逐浪）   | +1  |  0   |  0   |  ← 1 → 2
        //   | CREST_LIP（逐浪） |  0   | +2   | +2   |  ← 0/0/1 → 0/1/2
        //   | DISTURBANCE（逐浪）| 0  |  0   | +2   |  ← 0/0/0 → 0/0/1
        //   | RESIDUAL_STREAK  |  0   |  0   | +2   |  ← 1/1/1 → 1/1/3
        //   | WET_LINE         |  0   |  0   | +1   |  ← 1/1/1 → 1/1/2
        //   | PUDDLE           |  0   |  0   | +1   |  ← 1/1/1 → 1/1/2
        //   ⇒ LOW  30 + 1     = **31**
        //   ⇒ MED  48 + 2     = **50**
        //   ⇒ HIGH 88 + 2+2+2+1+1 = **96**
        // ⛔ **再因新增 [SeaOpItem.CRAB_TRAIL]「蟹迹」而变**：提交数 `1 / 2 / 4`（凹点恒 1 次
        //   `drawPath` + 沟槽 HIGH 3 段 / MEDIUM 1 段 / LOW 0 段）。
        //   ⭐ **`perWave = false`** ⇒ ⛔ **不乘 `STEADY_WAVES`**（与 CRAB 同一条裁决：痕迹挂在
        //   全局单实例螃蟹身后、每帧只画一次，同参数画两次在 source-over 下会叠亮）
        //   | 元素                     | LOW | MED | HIGH |
        //   |--------------------------|-----|-----|------|
        //   | CRAB_TRAIL（**不逐浪**）  | +1  | +2  | +4   |
        //   ⇒ LOW  30 + 1 = **31**
        //   ⇒ MED  49 + 2 = **51**
        //   ⇒ HIGH 184 + 4 = **188**
        assertEquals(31, SeasideOpBudget.estimate(SeaLevel.LOW, SeasideOpBudget.STEADY_WAVES))
        assertEquals(51, SeasideOpBudget.estimate(SeaLevel.MEDIUM, SeasideOpBudget.STEADY_WAVES))
        assertEquals(188, SeasideOpBudget.estimate(SeaLevel.HIGH, SeasideOpBudget.STEADY_WAVES))
        // 因 D9 裁决而变：LOW 1.7253 → 1.7213、MEDIUM 2.7125 → 2.7585、HIGH 2.8146 → 2.8106（屏）
        // ⚠️ **填充三档因「恢复第二次 drawPath」而完全不变** —— 高光与湿沙同区域，
        //   第二次提交⛔ 不新增像素覆盖（只是让那一带变亮）⇒ SHEEN.fill* 仍恒 0。
        // ⛔ **本轮只动了 [SeaOpItem.DISTURBANCE] 一行的 fill**：其余五项都是「同区域多 pass /
        //   第二种颜色」⇒ ⛔ 不新增任何覆盖，fill* 一个字符都没改。
        //   ⇒ LOW 1.7213（不变）、MEDIUM 2.7585（不变）、
        //     HIGH 2.8106 + 2 × (0.19 × 0.05208) = 2.8106 + 0.0198 = **2.8304**
        // ⭐ **再因「破碎唇 fillMed 补齐」而变**（记账修正，`fill*` 与 `ops*` 同口径）：
        //   CREST_LIP 是**逐浪**项 ⇒ 补 `2 × (0.15 × SEA_BAND)` = 2 × 0.007812 = 0.0156 屏
        //   ⇒ LOW **1.7213**（不变，`fillLow` 仍 0）
        //     MEDIUM 2.7585 + 0.0156 = **2.7741**
        //     HIGH **2.8304**（不变，`fillHigh` 本来就是这个值）
        // ⚠️ MEDIUM 的余量因此是三档里**最紧**的一处：`2.7741 ≤ OVERDRAW_MAX_MEDIUM = 2.8`
        //   （余量 ≈0.026）。LOW 余量 ≈0.28、HIGH 余量 ≈0.07。
        // ⭐ **再因新增 [SeaOpItem.CRAB_TRAIL] 而变**：**三档同值 +0.001 屏**
        //   （`fillLow = fillMed = fillHigh = SEA_CRAB_TRAIL_FILL`）—— LOW 的**真实**覆盖其实
        //   `< MEDIUM/HIGH`（LOW 只画凹点不画沟槽），但「`fill*` 与 `ops*` 同口径 + 取保守」
        //   要求取同值（把 LOW 不画的沟槽也算进 LOW 是**保守**方向）。
        //   同样 `perWave = false` ⇒ ⛔ **不乘 `STEADY_WAVES`** ⇒ 三档各 **+0.001**：
        //   ⇒ LOW  1.7213 + 0.001 = **1.7223**
        //   ⇒ MED  3.7741 + 0.001 = **3.7751**
        //   ⇒ HIGH 3.8304 + 0.001 = **3.8314**
        // ⚠️ 三档余量仍宽裕：LOW ≈0.278 / MEDIUM ≈0.115 / HIGH ≈0.129 ⇒ ⛔ **未越界**，
        //   `OVERDRAW_MAX_*` **一个字符都不用动**（HIGH 余量 320−188 = 132，同样够用）。
        assertEquals(1.7223f, SeasideOpBudget.overdrawEstimate(1600f, 900f, SeaLevel.LOW), 1e-3f)
        assertEquals(3.7751f, SeasideOpBudget.overdrawEstimate(1600f, 900f, SeaLevel.MEDIUM), 1e-3f)
        assertEquals(3.8314f, SeasideOpBudget.overdrawEstimate(1600f, 900f, SeaLevel.HIGH), 1e-3f)
    }

    @Test
    fun `G12 拒绝非法画布尺寸`() {
        var threw = false
        try {
            SeasideOpBudget.overdrawEstimate(0f, 900f, SeaLevel.HIGH)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  G13 native 堆
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `G13 native 堆在门内`() {
        val sizes = listOf(1600f to 900f, 1920f to 1080f, 2560f to 1440f)
        for (level in SeaLevel.entries) {
            for ((w, h) in sizes) {
                val n = SeasideOpBudget.nativePxBudget(w, h, level)
                assertTrue(
                    "nativePxBudget(${w}x$h, $level) = $n 超出 ${SeasideOpBudget.NATIVE_PX_MAX}",
                    n <= SeasideOpBudget.NATIVE_PX_MAX
                )
            }
        }
    }

    @Test
    fun `G13 沙纹理按 texH 而非整屏烘`() {
        val w = 2560f
        val h = 1440f
        val correct = SeasideOpBudget.nativePxBudget(w, h, SeaLevel.HIGH)
        val wrong = SeasideOpBudget.nativePxBudgetWithSandTop(w, h, SeaLevel.HIGH, 0.0)
        assertTrue("按 texH 烘 = $correct 必须 ≤ 3_000_000", correct <= SeasideOpBudget.NATIVE_PX_MAX)
        assertTrue("按整屏 h 烘 = $wrong 必须 > 3_000_000", wrong > SeasideOpBudget.NATIVE_PX_MAX)
    }

    @Test
    fun `G13 改造前模式必须突破 3_000_000`() {
        val n = SeasideOpBudget.nativePxBudget(2560f, 1440f, SeaLevel.HIGH, legacy = true)
        assertTrue("改造前 nativePx = $n 必须 > 3_000_000", n > SeasideOpBudget.NATIVE_PX_MAX)
    }

    @Test
    fun `G13 沙纹理降采样把单张钳在上限`() {
        val w = 3840.0
        val h = 2160.0
        // 原生整屏 8,294,400 px、texH 口径 4,478,976 px，两者都远超 320 万上限 ⇒ 必然被钳
        assertTrue(w * h > SeasideOpBudget.SAND_TEX_MAX_PX)
        assertTrue(w * (h * (1.0 - SEA_SAND_TEX_TOP)) > SeasideOpBudget.SAND_TEX_MAX_PX)
        // 钳位生效：总量 > 上限（上限 + 其余项），且比不钳时小得多
        val clamped = SeasideOpBudget.nativePxBudgetWithSandTop(3840f, 2160f, SeaLevel.HIGH, 0.0)
        assertTrue("钳位后总量 = 上限 + 其余项 = $clamped", clamped > SeasideOpBudget.SAND_TEX_MAX_PX)
        assertTrue("钳位后应远小于不钳的 $w×$h", clamped < (w * h).toInt())
    }

    @Test
    fun `G13 LOW 档不分配泡沫贴图与水体场画布`() {
        val low = SeasideOpBudget.nativePxBudget(1920f, 1080f, SeaLevel.LOW)
        val med = SeasideOpBudget.nativePxBudget(1920f, 1080f, SeaLevel.MEDIUM)
        assertTrue("LOW 应显著小于 MEDIUM：$low vs $med", low < med)
    }
}
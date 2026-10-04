package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * T2.16 音频接入（§4.6 + §14.3.4）—— 纯 JVM 单测。
 *
 * ⛔ 本测试**不含**任何 Robolectric / Compose 依赖 ⇒ 顺带证明 `SeasideAudioMap`
 * 真的做到了「零 Android import、零 Compose import」（若误引入 `FxLevel` / `Color`，
 * 这里会直接 `not mocked` 编译/运行失败）。
 */
class SeasideAudioMapTest {

    // ── 工具：构造一份可控频谱（⛔ 每例各自 new，不共享状态）────────────────────

    /** 64 柱：低频段 0.8、中频段 0.5、高频段 0.2（显示通道，0..1）。 */
    private fun spectrum(low: Double, mid: Double, high: Double, n: Int = 64): FloatArray {
        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            out[i] = when {
                i < SeasideAudioMap.BASS_END -> low.toFloat()
                i < SeasideAudioMap.MID_END -> mid.toFloat()
                i <= SeasideAudioMap.TREBLE_END -> high.toFloat()
                else -> 0f
            }
            i++
        }
        return out
    }

    private fun drive(
        map: SeasideAudioMap,
        frames: Int,
        dtSec: Double,
        spec: FloatArray,
        bassRaw: Double,
        beat: Boolean = false
    ) {
        var i = 0
        while (i < frames) {
            map.update(dtSec, spec, bassRaw, beat)
            i++
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  分频取样
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `分频边界等于 SpectrumContract 且柱数永远读 spectrum_size`() {
        assertEquals(39, SeasideAudioMap.BASS_END)
        assertEquals(55, SeasideAudioMap.MID_END)
        assertEquals(63, SeasideAudioMap.TREBLE_END)

        val bands = SeasideAudioMap.sampleBands(spectrum(0.8, 0.5, 0.2))
        assertEquals(0.8, bands[0], 1e-6)
        assertEquals(0.5, bands[1], 1e-6)
        assertEquals(0.2, bands[2], 1e-6)

        // ⛔ 柱数少于边界时不得越界（§2.3：柱数永远读 spectrum.size）
        val short = SeasideAudioMap.sampleBands(spectrum(1.0, 1.0, 1.0, n = 10))
        assertEquals(1.0, short[0], 1e-6)
        assertEquals(0.0, short[1], 1e-6)
        assertEquals(0.0, short[2], 1e-6)
        val empty = SeasideAudioMap.sampleBands(FloatArray(0))
        assertEquals(0.0, empty[0], 1e-12)
        assertEquals(0.0, empty[3], 1e-12)

        // 入口也必须记住真实柱数
        val map = SeasideAudioMap()
        map.update(0.033, spectrum(0.0, 0.0, 0.0, n = 32), 0.0, false)
        assertEquals(32, map.barCount)
    }

    @Test
    fun `分频取样不扣可见性地板`() {
        // MIN_AMPLITUDE 是「可见性」地板，只在要不要画这一项时用，不是分频取样的偏置
        val below = SeasideAudioMap.sampleBands(spectrum(0.01, 0.0, 0.0))
        assertEquals(0.01, below[0], 1e-9)
        assertEquals(0.02f, SeasideAudioMap.VISIBLE_FLOOR, 1e-6f)
    }

    @Test
    fun `显示增益逐字取 4_6`() {
        assertEquals(1.00, SeasideAudioMap.GAIN_LOW, 1e-9)
        assertEquals(1.15, SeasideAudioMap.GAIN_MID, 1e-9)
        assertEquals(1.95, SeasideAudioMap.GAIN_HIGH, 1e-9)
        assertEquals(1.35, SeasideAudioMap.GAIN_ENERGY, 1e-9)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⭐ 一阶平滑：attack 55 / release 260，⛔ 两端必须钳
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `时间常数逐字取 4_6`() {
        assertEquals(55.0, SeasideAudioMap.ATTACK_MS, 1e-9)
        assertEquals(260.0, SeasideAudioMap.RELEASE_MS, 1e-9)
        assertEquals(700.0, SeasideAudioMap.BASS_AVG_TAU_MS, 1e-9)
        assertEquals(500.0, SeasideAudioMap.SET_TAU_MS, 1e-9)
    }

    @Test
    fun `attack 系数 55ms 一帧爬升 release 260ms 一帧回落`() {
        // k_attack = 1 − exp(−33/55) ≈ 0.4512 ；k_release = 1 − exp(−33/260) ≈ 0.1192
        val ka = SeasideAudioMap.smootherK(33.0, SeasideAudioMap.ATTACK_MS)
        val kr = SeasideAudioMap.smootherK(33.0, SeasideAudioMap.RELEASE_MS)
        assertEquals(1.0 - Math.exp(-33.0 / 55.0), ka, 1e-12)
        assertEquals(1.0 - Math.exp(-33.0 / 260.0), kr, 1e-12)
        assertTrue("attack 必须比 release 快", ka > kr)
    }

    @Test
    fun `钳位 dt 为零时冻结`() {
        assertEquals(0.0, SeasideAudioMap.smootherK(0.0, 55.0), 1e-15)
        val map = SeasideAudioMap()
        drive(map, 30, 0.033, spectrum(0.9, 0.6, 0.3), 0.5)
        val before = map.sLow
        map.update(0.0, spectrum(0.0, 0.0, 0.0), 0.0, false)
        assertEquals("dt = 0 ⇒ 四个极点全部冻结", before, map.sLow, 1e-15)
    }

    @Test
    fun `负向自证 负 dt 会让一阶低通发散`() {
        // ⛔ 不钳的话：k = 1 − exp(+5000/55) ≈ −4.5e18 ⇒ v 一步就变成 ±1e81
        val kUnclamped = 1.0 - Math.exp(5000.0 / 55.0)
        assertTrue("未钳系数必须是灾难级的", abs(kUnclamped) > 1e18)
        // 本实现：两端都钳 ⇒ k ∈ [0,1]，且输出恒有限
        assertEquals(0.0, SeasideAudioMap.smootherK(-5000.0, 55.0), 1e-15)
        for (dt in doubleArrayOf(-1e9, -1000.0, -33.0, -1e-9, 0.0, 1e-9, 33.0, 1e9)) {
            val k = SeasideAudioMap.smootherK(dt, 55.0)
            assertTrue("k($dt) = $k 越界", k >= 0.0 && k <= 1.0)
        }
        // 端到端：喂负 dt 一整天，四个显示量仍必须有限且有界
        val map = SeasideAudioMap()
        drive(map, 30, 0.033, spectrum(0.8, 0.5, 0.2), 0.6)
        var i = 0
        while (i < 600) {
            map.update(-0.033, spectrum(1.0, 1.0, 1.0), 1.0, true)
            i++
        }
        assertTrue(map.sLow.isFinite() && map.sMid.isFinite() && map.sHigh.isFinite() && map.sEnergy.isFinite())
        assertTrue(map.setImp.isFinite() && map.setImp in 0.0..1.0)
        assertTrue(map.sLow <= SeasideAudioMap.GAIN_LOW + 1e-9)
    }

    @Test
    fun `attack 真的比 release 快`() {
        val rise = SeasideAudioMap()
        drive(rise, 10, 0.033, spectrum(1.0, 0.0, 0.0), 0.0)
        val fall = SeasideAudioMap()
        drive(fall, 10, 0.033, spectrum(1.0, 0.0, 0.0), 0.0)
        var i = 0
        while (i < 10) {
            fall.update(0.033, spectrum(0.0, 0.0, 0.0), 0.0, false)
            i++
        }
        assertTrue("上升 10 帧应到 ${rise.sLow}", rise.sLow > 0.99)
        assertTrue("下降 10 帧应仍明显高于 0（release 慢）：${fall.sLow}", fall.sLow > 0.25)
        assertTrue(rise.sLow > fall.sLow)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  bassAvg / setImp
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `bassAvg 与 setImp 的时间常数与判据逐字取规格`() {
        assertEquals(1.42, SeasideAudioMap.BEAT_RATIO, 1e-9)
        assertEquals(0.055, SeasideAudioMap.BEAT_OFFSET, 1e-9)
        assertEquals(2.4, SeasideAudioMap.BEAT_IMPULSE, 1e-9)

        // 判据：rawLow > bassAvg·1.42 + 0.055
        val map = SeasideAudioMap()
        // 先用 bassRaw = 0 预热：判据永不可能命中 ⇒ setImp 严格为 0
        drive(map, 60, 0.033, spectrum(0.0, 0.0, 0.0), 0.0)
        assertTrue("零输入不得命中鼓点", !map.beatHit)
        assertEquals(0.0, map.setImp, 1e-12)

        // 再喂一个恒定的 bassRaw ⇒ bassAvg（τ=700ms）收敛到它
        drive(map, 120, 0.033, spectrum(0.0, 0.0, 0.0), 0.10)
        assertEquals("bassAvg 收敛到 rawLow", 0.10, map.bassAvg, 0.02)
        assertTrue("基线以上一点点不构成鼓点（判据含 +0.055 与 ×1.42）", !map.beatHit)

        // 一次远超基线的鼓点 ⇒ 命中 + 冲量
        map.update(0.033, spectrum(0.0, 0.0, 0.0), 1.0, true)
        assertTrue("rawLow=1.0 应命中鼓点", map.beatHit)
        assertTrue("setImp 应被推高：${map.setImp}", map.setImp > 0.5)
        assertTrue(map.setImp <= 1.0)
    }

    @Test
    fun `setImp 按 500ms 指数回落且恒在 0 到 1`() {
        val map = SeasideAudioMap()
        drive(map, 120, 0.033, spectrum(0.0, 0.0, 0.0), 0.10)
        map.update(0.033, spectrum(0.0, 0.0, 0.0), 1.0, true)
        val peak = map.setImp
        assertTrue(peak > 0.0)
        var i = 0
        while (i < 120) {
            map.update(0.033, spectrum(0.0, 0.0, 0.0), 0.0, false)
            assertTrue("setImp 必须恒在 [0,1]：${map.setImp}", map.setImp in 0.0..1.0)
            i++
        }
        assertTrue("120 帧（≈4s = 8τ）后应基本归零：${map.setImp}", map.setImp < peak * 0.01)
        assertTrue("回落后必须 < 1e-3：${map.setImp}", map.setImp < 1e-3)
    }

    @Test
    fun `beatFlagGate 关闭时只用幅值判据`() {
        val a = SeasideAudioMap()
        drive(a, 120, 0.033, spectrum(0.0, 0.0, 0.0), 0.10)
        a.update(0.033, spectrum(0.0, 0.0, 0.0), 1.0, beat = false)
        assertTrue("默认不把 frame.beat 当附加闸门", a.beatHit)

        val b = SeasideAudioMap()
        b.useBeatFlag = true
        drive(b, 120, 0.033, spectrum(0.0, 0.0, 0.0), 0.10)
        b.update(0.033, spectrum(0.0, 0.0, 0.0), 1.0, beat = false)
        assertTrue("开启后必须同时满足 frame.beat", !b.beatHit)
        b.update(0.033, spectrum(0.0, 0.0, 0.0), 1.0, beat = true)
        assertTrue(b.beatHit)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  bandAmp / layerAlpha / 涌高 / 水色
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `bandAmp 逐字取 4_6 第 2 行`() {
        // 领头：a = 0.26 + 0.74·e（e = sLow）+ 0.55·setImp，钳 0..1.35
        assertEquals(0.26 + 0.74 * 1.0, SeasideAudioMap.band_amp(true, 1.0, 0.0, 0.0), 1e-9)
        assertEquals(0.26 + 0.74 * 0.0, SeasideAudioMap.band_amp(true, 0.0, 1.0, 0.0), 1e-9)
        assertEquals(0.26 + 0.74 * 1.0 + 0.55 * 0.3, SeasideAudioMap.band_amp(true, 1.0, 0.0, 0.3), 1e-9)
        // 钳上界 1.35：setImp 拉满时 1.55 被截到 1.35
        assertEquals(1.35, SeasideAudioMap.band_amp(true, 1.0, 0.0, 1.0), 1e-12)
        // 非领头：e = 0.70·sLow + 0.30·sMid，⛔ 不吃 setImp
        assertEquals(0.26 + 0.74 * (0.70 * 1.0 + 0.30 * 0.0), SeasideAudioMap.band_amp(false, 1.0, 0.0, 0.0), 1e-9)
        assertEquals(0.26 + 0.74 * (0.70 * 0.0 + 0.30 * 1.0), SeasideAudioMap.band_amp(false, 0.0, 1.0, 0.0), 1e-9)
        assertEquals(
            "⛔ 非领头浪不吃 setImp",
            SeasideAudioMap.band_amp(false, 1.0, 0.0, 0.0),
            SeasideAudioMap.band_amp(false, 1.0, 0.0, 1.0),
            1e-12
        )
        // 钳位上界 1.35
        assertEquals(1.35, SeasideAudioMap.band_amp(true, 5.0, 0.0, 5.0), 1e-12)
        assertEquals(0.0, SeasideAudioMap.band_amp(true, -5.0, 0.0, 0.0), 1e-12)
    }

    @Test
    fun `layerAlpha 逐字取 4_6 且 strength 下限是 0_32`() {
        assertEquals(0.60, SeasideAudioMap.ENERGY_TO_LAYERS, 1e-9)
        assertEquals(0.32, SeasideAudioMap.LAYER_STRENGTH_LO, 1e-9)
        assertEquals(0.68, SeasideAudioMap.LAYER_STRENGTH_SPAN, 1e-9)
        // energy = 1 ⇒ appear = 1 ⇒ layerAlpha = strength
        assertEquals(0.32, SeasideAudioMap.layer_alpha(0.0, 1.0), 1e-12)
        assertEquals(1.0, SeasideAudioMap.layer_alpha(1.0, 1.0), 1e-12)
        assertTrue(SeasideAudioMap.layer_alpha(0.0, 1.0) >= 0.32)
        // 足够大的 energy 下对 adv 单调递增（离岸越近越强）
        var prev = -1.0
        var a = 0.0
        while (a <= 1.0001) {
            val v = SeasideAudioMap.layer_alpha(a, 1.0)
            assertTrue("layerAlpha 必须随 adv 单调不减：adv=$a", v >= prev - 1e-12)
            prev = v
            a += 0.1
        }
        assertTrue(SeasideAudioMap.layer_alpha(1.0, 1.0) > SeasideAudioMap.layer_alpha(0.0, 1.0))
        // energy 越大越显形
        assertTrue(SeasideAudioMap.layer_alpha(0.2, 1.0) > SeasideAudioMap.layer_alpha(0.2, 0.0))
        // energy ≥ 1 ⇒ appear = 1 ⇒ layerAlpha = strength(adv)
        assertEquals(0.32 + 0.68 * 0.5, SeasideAudioMap.layer_alpha(0.5, 99.0), 1e-12)
    }

    @Test
    fun `涌高逐字取 4_6 且落在 0_065 到 0_115`() {
        val map = SeasideAudioMap()
        map.update(0.033, spectrum(0.0, 0.0, 0.0), 0.0, false)
        assertEquals(SeasideWaves.SWASH_REACH * 0.62, map.swashReachNow(), 1e-12)
        assertEquals(0.0651, map.swashReachNow(), 1e-9)

        drive(map, 120, 0.033, spectrum(1.0, 0.0, 0.0), 0.0)
        assertEquals(SeasideWaves.SWASH_REACH * (0.62 + 0.48), map.swashReachNow(), 1e-9)
        assertEquals(0.1155, map.swashReachNow(), 1e-9)
        // §4.6 写的是「0.065..0.115h」（四舍五入）；精确值 = 0.105 × (0.62+0.48) = 0.1155
        assertTrue("满程涌高 = 0.1155h（规格的 0.115 是取整）", map.swashReachNow() <= 0.1155 + 1e-9)
    }

    @Test
    fun `外观映射逐字取 4_6 其余各行`() {
        // 飞沫 / 残沫点数比例
        assertEquals(0.15, SeasideAudioMap.splashRatio(0.0), 1e-12)
        assertEquals(1.0, SeasideAudioMap.splashRatio(1.0), 1e-12)
        assertEquals(1.0, SeasideAudioMap.splashRatio(9.0), 1e-12)
        assertEquals(0.40, SeasideAudioMap.residueRatio(0.0), 1e-12)
        assertEquals(1.0, SeasideAudioMap.residueRatio(1.0), 1e-12)
        // 静默下也不是零：比例仍是 0.15 / 0.40（只是数量少）
        assertEquals(27, SeasideAudioMap().splashCount(180))
        assertEquals(21, SeasideAudioMap().residueCount(52))
        assertEquals(180, SeasideAudioMap().apply {
            drive(this, 300, 0.033, spectrum(1.0, 1.0, 1.0), 0.0)
        }.splashCount(180))

        // 浪花手指
        assertEquals(0.35, SeasideAudioMap.fingerLength(0.0), 1e-12)
        assertEquals(1.00, SeasideAudioMap.fingerLength(1.0), 1e-12)
        assertEquals(0.4, SeasideAudioMap.fingerAlpha(0.0), 1e-12)
        assertEquals(1.0, SeasideAudioMap.fingerAlpha(1.0), 1e-12)

        // 水色
        assertEquals(1.0, SeasideAudioMap.shadeBoost(0.0), 1e-12)
        assertEquals(1.28, SeasideAudioMap.shadeBoost(1.0), 1e-12)
        assertEquals(0.10, SeasideAudioMap.depthBias(0.0), 1e-12)
        assertEquals(0.10 + 0.55 * 0.45, SeasideAudioMap.depthBias(1.0), 1e-12)
        assertEquals(0.30, SeasideAudioMap.shoalWidth(0.0), 1e-12)
        assertEquals(0.52, SeasideAudioMap.shoalWidth(1.0), 1e-12)
        assertEquals(0.30, SeasideAudioMap.causticStrength(0.0), 1e-12)
        assertEquals(1.00, SeasideAudioMap.causticStrength(1.0), 1e-12)
    }

    @Test
    fun `shoreWaveBand 用的是另一条公式且恒按领头浪算`() {
        val map = SeasideAudioMap()
        drive(map, 120, 0.033, spectrum(1.0, 1.0, 1.0), 1.0, beat = true)
        val ampLead = SeasideAudioMap.band_amp(true, map.sLow, map.sMid, map.setImp)
        assertEquals(
            900.0 * SeasideWaves.SWELL_BAND_W * (0.55 + 0.75 * minOf(1.35, ampLead)),
            map.shoreWaveBand(900.0),
            1e-9
        )
        // ⛔ 它与逐浪的 w0（0.62 + 0.62·amp）是两条不同公式
        assertTrue(SeasideWaves.SWELL_BAND_W * (0.62 + 0.62 * ampLead) !=
            SeasideWaves.SWELL_BAND_W * (0.55 + 0.75 * minOf(1.35, ampLead)))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ⭐⭐ 非领头浪的 kA 不吃音频 —— 本模块最硬的一条
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `负向自证 非领头浪 kA 吃音频则必失败`() {
        // 破实现：把 layerAlpha 乘回非领头浪的 kA（初版缺陷）
        fun broken(amp: Double, fade: Double, adv: Double, energy: Double): Double =
            SeasideAudioMap.clamp(amp * SeasideAudioMap.layer_alpha(adv, energy) * fade, 0.0, 1.0)

        val amp = 0.698
        val fade = 0.8
        val quiet = broken(amp, fade, 0.30, 0.0)
        val loud = broken(amp, fade, 0.30, 1.0)
        assertTrue("破实现必须随音频变（$quiet vs $loud）", abs(quiet - loud) > 0.05)

        // 本实现：喂两组差异极大的音频，非领头浪 kA 必须**完全相同**
        val quietMap = SeasideAudioMap()
        drive(quietMap, 8, 0.033, spectrum(0.0, 0.0, 0.0), 0.0)
        // ⚠️ 只跑 8 帧：bassAvg 的 τ=700ms 还没爬到 1.0 ⇒ setImp 仍在高位，
        //    两组输入才真的「差异极大」（跑满 200 帧后基线收敛、鼓点自然不再命中）
        val loudMap = SeasideAudioMap()
        drive(loudMap, 8, 0.033, spectrum(1.0, 1.0, 1.0), 1.0, beat = true)

        assertTrue("静音与全噪必须真的不同（energy）", abs(quietMap.sEnergy - loudMap.sEnergy) > 0.3)
        assertTrue("静音与全噪的 setImp 必须真的不同", abs(quietMap.setImp - loudMap.setImp) > 0.05)

        val kaQuiet = quietMap.followKa(amp, fade)
        val kaLoud = loudMap.followKa(amp, fade)
        assertEquals("⭐ 非领头浪 kA 必须在两组音频下逐位相同", kaQuiet, kaLoud, 0.0)

        // 遍历一整圈 energy × adv：非领头浪那位纹丝不动，同一网格上领头浪那位在动
        var moved = false
        var e = 0.0
        while (e <= 1.35) {
            var adv = 0.0
            while (adv <= 1.0) {
                assertEquals(
                    "energy=$e adv=$adv 下非领头浪 kA 不得变化",
                    SeasideAudioMap.follow_wave_kA(amp, fade),
                    kaQuiet,
                    0.0
                )
                if (abs(SeasideAudioMap.lead_wave_kA(amp, fade, adv, e) - kaQuiet) > 1e-9) moved = true
                adv += 0.05
            }
            e += 0.05
        }
        assertTrue("同一网格上领头浪 kA 必须会动（否则这组对比没有判别力）", moved)
    }

    @Test
    fun `非领头浪 kA 逐字取 4_3_9_③`() {
        assertEquals(0.55, SeasideAudioMap.NON_LEAD_KA, 1e-12)
        assertEquals(0.698 * 0.55 * 0.8, SeasideAudioMap.follow_wave_kA(0.698, 0.8), 1e-12)
        assertEquals(1.0, SeasideAudioMap.follow_wave_kA(5.0, 5.0), 1e-12)
        assertEquals(0.0, SeasideAudioMap.follow_wave_kA(-1.0, 1.0), 1e-12)
    }

    @Test
    fun `领头浪 kA 确实吃音频能量`() {
        val quietMap = SeasideAudioMap()
        drive(quietMap, 200, 0.033, spectrum(0.0, 0.0, 0.0), 0.0)
        val loudMap = SeasideAudioMap()
        drive(loudMap, 200, 0.033, spectrum(1.0, 1.0, 1.0), 1.0, beat = true)
        val kaQuiet = quietMap.leadKa(0.698, 0.8, 0.6)
        val kaLoud = loudMap.leadKa(0.698, 0.8, 0.6)
        assertTrue("领头浪 kA 必须随音频上升（$kaQuiet → $kaLoud）", kaLoud > kaQuiet)
    }

    @Test
    fun `静默态也必须是动态的但 kA 不受影响`() {
        // §4.6 末行：全零频谱下队列仍按 WAVE_FOLLOW_Y 补浪、水线仍涌退
        // ⇒ 本模块在零输入下必须**不产出**任何音强表达，但也不能崩、不能卡住。
        val map = SeasideAudioMap()
        drive(map, 300, 0.033, spectrum(0.0, 0.0, 0.0), 0.0)
        assertEquals(0.0, map.sEnergy, 1e-12)
        assertEquals(0.0, map.sLow, 1e-12)
        assertEquals(0.0, map.setImp, 1e-12)
        // 静默时非领头浪 kA 只剩 amp·0.55·fade，仍 > 0.06（cull 阈值之上 ⇒ 位置可见）
        val ka = map.followKa(0.698, 1.0)
        assertTrue("静默时非领头浪仍须有浪花（位置指示器）：$ka", ka > 0.06)
        assertEquals(0.698 * 0.55, ka, 1e-12)
    }

    @Test
    fun `零分配热路径不新建缓冲`() {
        // ⛔ 源码级断言：update / step / smootherK 的函数体里不得出现缓冲构造
        val raw = readSource("SeasideAudioMap.kt")
        // ⛔ 必须先剥注释：KDoc 里提到「零 Random」这句话本身就会命中关键词
        val src = stripComments(raw)
        for (fn in listOf("fun update(dtSec: Double, spectrum", "private fun step(", "fun smootherK(")) {
            val body = extractBody(raw, fn)
            for (banned in listOf("FloatArray(", "DoubleArray(", "doubleArrayOf(", "listOf(", "Array(", "map {", ".map(")) {
                assertTrue("`$fn` 里出现了 `$banned`", !body.contains(banned))
            }
        }
        // ⛔ 绝无随机（只查调用形态，KDoc 里提到 `Random` 这个词不算）
        assertTrue("不得出现 Random(", !src.contains("Random("))
        assertTrue("不得出现 Math.random", !src.contains("Math.random"))
        assertTrue("不得 import kotlin.random", !src.contains("import kotlin.random"))
        // ⛔ 零 Android / Compose import
        assertTrue(!src.contains("import android."))
        assertTrue(!src.contains("import androidx."))
    }

    // ── 源码断言小工具（⛔ 只在本测试用，读的是同包下的主源文件）────────────────

    private fun readSource(name: String): String {
        var dir = java.io.File(System.getProperty("user.dir"))
        repeat(6) {
            val f = java.io.File(dir, "app/src/main/java/com/nasmusic/tv/visualizer/renderers/$name")
            if (f.isFile) return f.readText()
            val g = java.io.File(dir, "src/main/java/com/nasmusic/tv/visualizer/renderers/$name")
            if (g.isFile) return g.readText()
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError("找不到源文件 $name")
    }

    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var depth = 0
        while (i < src.length) {
            when {
                depth == 0 && src.startsWith("/*", i) -> { depth = 1; i += 2 }
                depth > 0 && src.startsWith("*/", i) -> { depth -= 1; i += 2; if (depth == 0) sb.append(' ') }
                depth > 0 -> i++
                depth == 0 && src.startsWith("//", i) -> {
                    while (i < src.length && src[i] != '\n') i++
                    sb.append(' ')
                }
                depth == 0 && src[i] == '"' -> {
                    // 字符串字面量：⛔ 不做转义解析（本文件的热路径里没有字符串）
                    sb.append(src[i]); i++
                    while (i < src.length && src[i] != '"') { sb.append(src[i]); i++ }
                    if (i < src.length) { sb.append(src[i]); i++ }
                }
                else -> { sb.append(src[i]); i++ }
            }
        }
        return sb.toString()
    }

    private fun extractBody(src: String, header: String): String {
        val i = src.indexOf(header)
        if (i < 0) throw AssertionError("找不到函数头 `$header`")
        var depth = 0
        var started = false
        var j = i
        while (j < src.length) {
            when (src[j]) {
                '{' -> { depth++; started = true }
                '}' -> { depth--; if (started && depth <= 0) return src.substring(i, j + 1) }
            }
            j++
        }
        throw AssertionError("`$header` 的函数体未闭合")
    }
}
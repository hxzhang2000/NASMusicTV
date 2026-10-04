package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.SpectrumContract
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * T2.16 ⭐ **音频接入**（§4.6 音频映射总表 + §14.3.4）—— 纯函数 / 纯 Kotlin，零 Android、零 Compose。
 *
 * ## ⛔ 第一原则：音频不碰时间轴（§4.6 首行）
 * 本文件**只产出外观量**。出浪节拍、浪的行程、消散、水线回退、逐列干燥**只吃 `dt`**
 * （见 [SeasideWaves.step]）⇒ 本文件里**没有任何**推进量、没有任何时间累加器参与队列语义。
 * 渲染层把 [swashReachNow] 与 [shoreWaveBand] 当**两个入参**喂给
 * `SeasideWaves.step(dtSec, tMs, swashReachNow, shoreW0)`，而它们在 `step` 内部的赋值点
 * **排在** `stepWaves(dt)` 与 `waterlineAdvance(dt)` **之后** ⇒ 结构上无法影响时序。
 *
 * ## 三条硬约束
 * 1. ⛔ **零 Android import、零 Compose import** ⇒ 可纯 JVM 单测（不像 `AudioSmoother`
 *    那样只能被 Robolectric 覆盖）。
 * 2. ⛔ **零分配**：分频取样是**一趟扫描 + 三个标量累加器**（⛔ 连缓冲都不需要，
 *    比「预分配缓冲」更省）；平滑器是三个标量；⛔ 无 `listOf` / `.map` / 装箱。
 * 3. ⛔ **零 `Random`** / `Math.random()` —— 本文件本来就不需要随机。
 *
 * ## 平滑器为什么「两端都要钳」（§4.6 末）
 * 一阶低通系数 `k = 1 − exp(−dt·1000/τ)`：⛔ `dt` 为**负**时 `k` 为负且 `|k| ≫ 1`
 * ⇒ 单极点 `v += (target − v)·k` **发散到 ±1e81**，污染下游每一个值（涌高、泡沫振幅、
 * 水色、飞沫点数…）。所以 [smootherK] 同时钳 `dt` 与 `k`，见其 KDoc。
 */
internal class SeasideAudioMap {

    // ══════════════════════════════════════════════════════════════════════════
    //  §4.6「音频读取与平滑」—— 分频边界 / 显示增益 / 时间常数
    // ══════════════════════════════════════════════════════════════════════════

    internal companion object {

        // ── 分频边界（⛔ 与 SpectrumContract 同源，见 [bandLow] 等的 KDoc）──────
        /** 低频段上界（不含）= 20–250 Hz。 */
        const val BASS_END = SpectrumContract.BASS_END

        /** 中频段上界（不含）= 250 Hz–3 kHz。 */
        const val MID_END = SpectrumContract.MID_END

        /** 高频段上界（含）= 3 k–20 kHz。 */
        const val TREBLE_END = SpectrumContract.TREBLE_END

        /** 可见性地板。⛔ 只在**显示**通道用，分频取样**不扣**它 —— 见 [bandMean] 的 KDoc。 */
        const val VISIBLE_FLOOR = SpectrumContract.MIN_AMPLITUDE

        // ── 显示增益（§4.6 末，⛔ 逐字取规格）────────────────────────────────
        const val GAIN_LOW = 1.00
        const val GAIN_MID = 1.15
        const val GAIN_HIGH = 1.95
        const val GAIN_ENERGY = 1.35

        // ── 一阶低通时间常数（ms）────────────────────────────────────────────
        /** 上升（attack）—— 跟得上瞬态。 */
        const val ATTACK_MS = 55.0

        /** 下降（release）—— 段落不会一帧掉下去。 */
        const val RELEASE_MS = 260.0

        /** `bassAvg` 时间常数（鼓点判据的基线）。 */
        const val BASS_AVG_TAU_MS = 700.0

        /** `setImp` 指数回落时间常数（⛔ 只加成领头浪的泡沫振幅）。 */
        const val SET_TAU_MS = 500.0

        // ── 鼓点判据（§5「鼓点判据」行，⛔ 逐字取规格）────────────────────────
        const val BEAT_RATIO = 1.42
        const val BEAT_OFFSET = 0.055
        const val BEAT_IMPULSE = 2.4

        // ── 映射系数（§4.6 各行）─────────────────────────────────────────────
        /** 涌高：`swashReachNow = SWASH_REACH·(SWASH_REACH_LO + SWASH_REACH_SPAN·sLow)`。 */
        const val SWASH_REACH_LO = 0.62
        const val SWASH_REACH_SPAN = 0.48

        /** 泡沫振幅 `bandAmp`：`a = BAND_AMP_LO + BAND_AMP_SPAN·e (+ SET_IMP_GAIN·setImp)`。 */
        const val BAND_AMP_LO = 0.26
        const val BAND_AMP_SPAN = 0.74

        /** 领头浪的鼓点加成系数。 */
        const val SET_IMP_GAIN = 0.55

        /** 非领头浪的 `e` 用中频加权（低频 0.70 + 中频 0.30）。 */
        const val FOLLOWER_MID_MIX = 0.30

        /** 响度 → 弱浪显形阈值（`thresh = ENERGY_TO_LAYERS·0.5·(1 − nearness)`）。 */
        const val ENERGY_TO_LAYERS = SeasideWaves.ENERGY_TO_LAYERS

        /** 层序强度下限（§4.6：⛔ 由 0.22 抬到 **0.32**）。 */
        const val LAYER_STRENGTH_LO = 0.32
        const val LAYER_STRENGTH_SPAN = 0.68

        /** 显形项下限（静音时 `appear = 0.30`）。 */
        const val LAYER_APPEAR_LO = 0.30
        const val LAYER_APPEAR_SPAN = 0.70

        /** ⭐ 非领头浪的 `kA` 系数（§4.3.9 ③ / §14.3.4：`kA = clamp(amp·0.55·fade, 0, 1)`）。 */
        const val NON_LEAD_KA = 0.55

        /** 飞沫点数比例：`floor(SPLASH_MAX·clamp(SPLASH_LO + SPLASH_SPAN·sHigh))`。 */
        const val SPLASH_LO = 0.15
        const val SPLASH_SPAN = 0.85

        /** 残沫白点数比例：`ceil(RESIDUE_MAX·clamp(RESIDUE_LO + RESIDUE_SPAN·sHigh))`。 */
        const val RESIDUE_LO = 0.40
        const val RESIDUE_SPAN = 0.60

        /** 浪花手指长度：`(0.35 + 0.65·sMid)`。 */
        const val FINGER_LO = 0.35
        const val FINGER_SPAN = 0.65

        /** 浪花手指 alpha 用 `0.4 + 0.6·sLow`。 */
        const val FINGER_ALPHA_LO = 0.4
        const val FINGER_ALPHA_SPAN = 0.6

        /** 水色：`shadeBoost = 1 + 0.28·e`、`depthBias = 0.10 + 0.55·ENERGY_TO_SHADE·e`。 */
        const val SHADE_BOOST_GAIN = 0.28
        const val ENERGY_TO_SHADE = 0.45
        const val DEPTH_BIAS_LO = 0.10
        const val DEPTH_BIAS_SPAN = 0.55

        /** 浅滩带宽度 `0.30 + 0.22·e`。 */
        const val SHOAL_LO = 0.30
        const val SHOAL_SPAN = 0.22

        /** 粼光网强度 `CAUSTIC_A·(0.30 + 0.70·e)`。 */
        const val CAUSTIC_LO = 0.30
        const val CAUSTIC_SPAN = 0.70

        /** 平滑器的时间步上限（ms）。`FxFrame.dt` 已钳 `[0, 0.1]` s ⇒ 天然落在 `[0, 100]`。 */
        const val DT_CLAMP_MS = 100.0

        // ── 纯函数工具 ───────────────────────────────────────────────────────

        /** ⛔ 与 JS `clamp` 同序（先下界后上界）。 */
        fun clamp(v: Double, a: Double, b: Double): Double = if (v < a) a else if (v > b) b else v

        fun smoothstep(e0: Double, e1: Double, x: Double): Double {
            val t = clamp((x - e0) / (e1 - e0), 0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }

        /**
         * ⭐⭐ **一阶低通系数 —— ⛔ 两端都必须钳**（§4.6 末的明确警告）。
         *
         * ```
         * k = 1 − exp(−dtMs / tau)      // tau = target ≥ cur ? attack : release
         * ```
         * - ⛔ **钳 `dtMs` 到 `[0, DT_CLAMP_MS]`**：`dtMs < 0` ⇒ `k < 0`，
         *   `v += (target − v)·k` 里 `|k|` 随 `|dt|` 指数增长 ⇒ 单极点发散到 ±1e81。
         *   （`FxFrame.dt` 钳了 `[0, 0.1]`，但**纯函数门不能假设调用方守规矩**。）
         * - ⛔ **再钳 `k` 到 `[0, 1]`**：双保险，且让「`dt = 0` ⇒ 冻结」成为结构性事实
         *   （`k = 0` ⇒ `v` 不动）而不是靠调用方记得传 0。
         */
        fun smootherK(dtMs: Double, tauMs: Double): Double {
            val d = clamp(dtMs, 0.0, DT_CLAMP_MS)
            val k = 1.0 - exp(-d / tauMs)
            return clamp(k, 0.0, 1.0)
        }

        /**
         * ⭐⭐ **非领头浪的 `kA` —— 位置指示器，不是音强表达**（§4.3.9 ③ / §14.3.4）。
         *
         * `kA = clamp(amp · [NON_LEAD_KA] · fade, 0, 1)`
         *
         * ⛔ **⛔ 不吃 `layerAlpha`**（⇒ 不吃音频能量门控），⛔ **不吃近岸斜坡**。
         * ⛔ 注意与 `amp` 的区别：`amp` 本身**按 §14.3.4 吃音频**
         * （`amp = 0.46 + 0.45·bandAmp(1 + (wiS % 2))`）—— 那是**浪带宽度 / 强度**，
         * 不是 `kA` 的门控。本函数是 `kA` 的**唯一**定义处，签名里**没有** `energy` /
         * `setImp` / `sLow` / `sMid` / `sEnergy` 任何一个入参 ⇒ **结构上无法吃音频**。
         *
         * ⭐ owner 的原话是「第二条浪不应该抑制浪花啊，应该一直保持浪花，
         * 我才知道第二条浪走到哪里了」⇒ 它的浪花是**位置指示器**。
         *
         * ⚠️ **诚实标注**（§4.6 末）：因此 V11「低频 → 浪更宽更白」在真机上**只能观察到
         * 领头浪**的变化；⛔ 不要把「第二条浪随音乐变强 / 变弱」写进验收项。
         */
        fun follow_wave_kA(amp: Double, fade: Double): Double =
            clamp(amp * NON_LEAD_KA * fade, 0.0, 1.0)

        /**
         * ⭐ **领头浪的 `kA`**（§14.3.4）：`clamp(amp · layer_alpha(adv, energy) · fade, 0, 1)`。
         *
         * ⚠️ `energy` **只**在这里进入 —— ⛔ 非领头浪那条支路走 [follow_wave_kA]。
         */
        fun lead_wave_kA(amp: Double, fade: Double, adv: Double, energy: Double): Double =
            clamp(amp * layer_alpha(adv, energy) * fade, 0.0, 1.0)

        /**
         * ⭐ 层序强度（§4.6 / §14.3.3）：`strength = 0.32 + 0.68·clamp(adv, 0, 1)`；
         * `thresh = ENERGY_TO_LAYERS·0.5·(1 − nearness)`；
         * `appear = 0.30 + 0.70·smoothstep(thresh, 1, energy)`；返回 `clamp(strength·appear, 0, 1)`。
         *
         * ⛔ **只领头浪用**；⛔ **不参与任何时序**（`energy` 只是入参）。
         * ⛔ `energy` ≥ 1 时 `appear = 1`（`smoothstep` 饱和）⇒ `layerAlpha = strength`。
         */
        fun layer_alpha(adv: Double, energy: Double): Double {
            val nearness = clamp(adv, 0.0, 1.0)
            val strength = LAYER_STRENGTH_LO + LAYER_STRENGTH_SPAN * nearness
            val thresh = ENERGY_TO_LAYERS * 0.5 * (1.0 - nearness)
            val appear = LAYER_APPEAR_LO + LAYER_APPEAR_SPAN * smoothstep(thresh, 1.0, energy)
            return clamp(strength * appear, 0.0, 1.0)
        }

        /**
         * ⭐ 泡沫振幅（§4.6 第 2 行）：`e = (领头 ? sLow : 0.70·sLow + 0.30·sMid)`，
         * `a = 0.26 + 0.74·e`（领头浪再加 `setImp·0.55`），钳 `0..1.35`。
         *
         * ⛔ §14.3.4 的警告：**不要把 lane 0 当成「领头浪」** —— `lane = 1 + serial%3` 恒 ≠ 0，
         * 所以原型里那条 `L == 0` 的分支**永远走不到**。端口按 [isLead] 显式分支。
         *
         * @param isLead ⛔ 绘制上的前浪 = 真正抵达滩上的那条（`W_REACHED`/`W_FADING`）。
         */
        fun band_amp(isLead: Boolean, sLow: Double, sMid: Double, setImp: Double): Double {
            val e = if (isLead) sLow else (1.0 - FOLLOWER_MID_MIX) * sLow + FOLLOWER_MID_MIX * sMid
            val a = BAND_AMP_LO + BAND_AMP_SPAN * e + (if (isLead) SET_IMP_GAIN * setImp else 0.0)
            return clamp(a, 0.0, 1.35)
        }

        // ── 分频取样（⛔ 一趟扫描 + 三个标量累加器，零缓冲零分配）────────────

        /**
         * ⭐ 分频取样（§4.6「分频取 ≤250Hz / ≤3kHz / 其余」，边界取 [SpectrumContract]）：
         * - 低频 `spectrum[0 until BASS_END)` = 20–250 Hz
         * - 中频 `spectrum[BASS_END until MID_END)` = 250 Hz–3 kHz
         * - 高频 `spectrum[MID_END..TREBLE_END]` = 3 k–20 kHz
         * - 全频 `spectrum[0..last]` = `rawAll`
         *
         * ⛔ **柱数永远读 `spectrum.size`**（§2.3：「渲染侧不得自行指定柱数」）⇒ 上界全部
         * `min(边界, size)` 夹一次，⛔ 不会越界。返回 `DoubleArray(4)` ——
         * ⛔ 那是**每帧**的分配，所以**不在热路径上用本函数**；热路径走 [SeasideAudioMap.update]
         * 里的内联一趟扫描（同样口径、同样的边界、⛔ 零分配）。本函数只给单测与调试用。
         *
         * ⚠️ **不扣 [VISIBLE_FLOOR]**：`spectrum` 是**显示**通道（已 gamma 压缩），
         * 扣掉 0.02 会整体压低 `sLow`/`sMid`/`sHigh`，与原型基线不符。`MIN_AMPLITUDE`
         * 是**可见性**地板，只在「要不要画这一项」时用（[splashCount] / [residueCount]）。
         */
        fun sampleBands(spectrum: FloatArray): DoubleArray {
            val n = spectrum.size
            var lo = 0.0
            var mid = 0.0
            var hi = 0.0
            var all = 0.0
            var i = 0
            val loEnd = min(BASS_END, n)
            val midEnd = min(MID_END, n)
            val hiEnd = min(TREBLE_END + 1, n)
            while (i < loEnd) { val v = spectrum[i].toDouble(); lo += v; all += v; i++ }
            while (i < midEnd) { val v = spectrum[i].toDouble(); mid += v; all += v; i++ }
            while (i < hiEnd) { val v = spectrum[i].toDouble(); hi += v; all += v; i++ }
            var k = hiEnd
            while (k < n) { all += spectrum[k].toDouble(); k++ }
            val loN = loEnd
            val midN = midEnd - loEnd
            val hiN = hiEnd - midEnd
            val allN = n
            return doubleArrayOf(
                if (loN > 0) lo / loN else 0.0,
                if (midN > 0) mid / midN else 0.0,
                if (hiN > 0) hi / hiN else 0.0,
                if (allN > 0) all / allN else 0.0
            )
        }

        /** 飞沫点数比例 `clamp(0.15 + 0.85·sHigh)`（§4.6「高频 → 飞沫点数」行）。 */
        fun splashRatio(sHigh: Double): Double = clamp(SPLASH_LO + SPLASH_SPAN * sHigh, 0.0, 1.0)

        /** 残沫白点数比例 `clamp(0.40 + 0.60·sHigh)`（§4.6 同行的另一半）。 */
        fun residueRatio(sHigh: Double): Double = clamp(RESIDUE_LO + RESIDUE_SPAN * sHigh, 0.0, 1.0)

        /** 浪花手指长度倍率 `(0.35 + 0.65·sMid)`。 */
        fun fingerLength(sMid: Double): Double = FINGER_LO + FINGER_SPAN * sMid

        /** 浪花手指 alpha 倍率 `0.4 + 0.6·sLow`。 */
        fun fingerAlpha(sLow: Double): Double = FINGER_ALPHA_LO + FINGER_ALPHA_SPAN * sLow

        /** 水色加深 `1 + 0.28·e`。 */
        fun shadeBoost(energy: Double): Double = 1.0 + SHADE_BOOST_GAIN * energy

        /** 水色深度偏置 `0.10 + 0.55·0.45·e`。 */
        fun depthBias(energy: Double): Double = DEPTH_BIAS_LO + DEPTH_BIAS_SPAN * ENERGY_TO_SHADE * energy

        /** 浅滩带宽度 `0.30 + 0.22·e`。 */
        fun shoalWidth(energy: Double): Double = SHOAL_LO + SHOAL_SPAN * energy

        /** 粼光网强度 `CAUSTIC_A·(0.30 + 0.70·e)` —— ⛔ 乘子，调用方乘自己的 `CAUSTIC_A`。 */
        fun causticStrength(energy: Double): Double = CAUSTIC_LO + CAUSTIC_SPAN * energy
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  实例状态 —— 全部标量，⛔ 零缓冲
    // ══════════════════════════════════════════════════════════════════════════

    /** 平滑后的低频（显示增益后，`0..GAIN_LOW = 1.0`）。 */
    var sLow: Double = 0.0
        private set

    /** 平滑后的中频（显示增益后，`0..1.15`）。 */
    var sMid: Double = 0.0
        private set

    /** 平滑后的高频（显示增益后，`0..1.95`）。 */
    var sHigh: Double = 0.0
        private set

    /** 平滑后的响度（显示增益后，`0..1.35`）。 */
    var sEnergy: Double = 0.0
        private set

    /** ⭐ 鼓点判据的基线：低频原始幅度的 `τ = 700ms` 滑动均值。 */
    var bassAvg: Double = 0.0
        private set

    /** ⭐ 鼓点冲量 `0..1`，`τ = 500ms` 指数回落。⛔ **只加成领头浪的泡沫振幅**。 */
    var setImp: Double = 0.0
        private set

    /** 本帧是否命中鼓点判据（`rawLow > bassAvg·1.42 + 0.055`）。⛔ 不产生额外浪、不推进行程。 */
    var beatHit: Boolean = false
        private set

    /** 本帧的原始低频幅值（显示增益**前**、平滑**前**，`0..1`）。 */
    var rawLow: Double = 0.0
        private set

    /** 上一次 [update] 用到的柱数（只读，供自检断言「柱数永远读 `spectrum.size`」）。 */
    var barCount: Int = 0
        private set

    /**
     * ⛔ 是否把 `frame.beat` 当成鼓点的**附加闸门**。
     *
     * §4.6 的表头写「⭐ `bassRaw` + `frame.beat`」，但 §5 的「鼓点判据」行**只**给了幅值判据
     * `rawLow > bassAvg·1.42 + 0.055`。本实现**默认按 §5 的判据**（`false`），
     * 需要严格「AND `frame.beat`」时置 `true`。⚠️ 这是一处**规格内部不一致**，
     * 已写进交付报告。
     */
    var useBeatFlag: Boolean = false

    // ── 平滑器的四个一阶极点（标量，⛔ 无缓冲）─────────────────────────────────

    private var fLow = 0.0
    private var fMid = 0.0
    private var fHigh = 0.0
    private var fAll = 0.0

    /** 恢复到静音态（画质切换 / 换歌时调）。⛔ 不复位任何时间原点。 */
    fun reset() {
        sLow = 0.0
        sMid = 0.0
        sHigh = 0.0
        sEnergy = 0.0
        bassAvg = 0.0
        setImp = 0.0
        beatHit = false
        rawLow = 0.0
        barCount = 0
        fLow = 0.0
        fMid = 0.0
        fHigh = 0.0
        fAll = 0.0
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  每帧入口
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 推进一帧。**⛔ 零分配**（一趟扫描 + 六个标量）。
     *
     * @param dtSec 帧时长（秒）。⛔ 传 `0` = 冻结（`k = 0` ⇒ 四个极点都不动）。
     * @param spectrum ⛔ **显示**通道（已 gamma 压缩）。柱数读 `spectrum.size`。
     * @param bassRaw 20–250 Hz 的**原始**线性能量（`AudioFrame.bassRaw`）——
     *        ⛔ **不是** `AudioFrame.bass`（后者走过峰值跟随，鼓点时刻恒为 1.0）。
     * @param beat `AudioFrame.beat`；只在 [useBeatFlag] 为 `true` 时才是附加闸门。
     */
    fun update(dtSec: Double, spectrum: FloatArray, bassRaw: Double, beat: Boolean) {
        val dtMs = dtSec * 1000.0
        val n = spectrum.size
        barCount = n

        // ── ① 分频取样：一趟扫描，三个累加器（口径与 sampleBands 完全一致）──────
        val loEnd = min(BASS_END, n)
        val midEnd = min(MID_END, n)
        val hiEnd = min(TREBLE_END + 1, n)
        var lo = 0.0
        var mid = 0.0
        var hi = 0.0
        var all = 0.0
        var i = 0
        while (i < loEnd) { val v = spectrum[i].toDouble(); lo += v; all += v; i++ }
        val loN = loEnd
        while (i < midEnd) { val v = spectrum[i].toDouble(); mid += v; all += v; i++ }
        val midN = midEnd - loEnd
        while (i < hiEnd) { val v = spectrum[i].toDouble(); hi += v; all += v; i++ }
        val hiN = hiEnd - midEnd
        i = hiEnd
        while (i < n) { all += spectrum[i].toDouble(); i++ }
        val allN = n
        val rawLo = if (loN > 0) lo / loN else 0.0
        val rawMid = if (midN > 0) mid / midN else 0.0
        val rawHi = if (hiN > 0) hi / hiN else 0.0
        val rawAll = if (allN > 0) all / allN else 0.0

        // ── ② 一阶平滑（attack 55 / release 260，⛔ 两端都钳在 smootherK 里）─────
        fLow = step(fLow, rawLo, dtMs)
        fMid = step(fMid, rawMid, dtMs)
        fHigh = step(fHigh, rawHi, dtMs)
        fAll = step(fAll, rawAll, dtMs)

        // ── ③ 显示增益（§4.6 末）：增益在平滑**之后**，⛔ 不参与极点的定义域 ──────
        val cLo = clamp(fLow, 0.0, 1.0)
        sLow = GAIN_LOW * cLo
        sMid = GAIN_MID * clamp(fMid, 0.0, 1.0)
        sHigh = GAIN_HIGH * clamp(fHigh, 0.0, 1.0)
        sEnergy = GAIN_ENERGY * clamp(fAll, 0.0, 1.0)

        // ── ④ 鼓点：基线 τ=700ms → 判据 → 冲量 τ=500ms 回落。⛔ 不碰时间轴 ────────
        rawLow = clamp(bassRaw, 0.0, 1.0)
        val kAvg = smootherK(dtMs, BASS_AVG_TAU_MS)
        bassAvg += (rawLow - bassAvg) * kAvg
        val hit = rawLow > bassAvg * BEAT_RATIO + BEAT_OFFSET && (!useBeatFlag || beat)
        beatHit = hit
        if (hit) {
            setImp = clamp(setImp + (rawLow - bassAvg) * BEAT_IMPULSE, 0.0, 1.0)
        } else {
            setImp *= exp(-dtMs / SET_TAU_MS)
            if (setImp < 1e-9) setImp = 0.0
        }
    }

    /** [update] 的 [AudioFrame] 便捷入口（[AudioFrame] 是纯 Kotlin 类，⛔ 无 Android import）。 */
    fun update(dtSec: Double, frame: AudioFrame) =
        update(dtSec, frame.spectrum, frame.bassRaw.toDouble(), frame.beat)

    /** 一阶低通的一步。`k` 由 [smootherK] 给出（attack / release 由大小关系选）。 */
    private fun step(cur: Double, target: Double, dtMs: Double): Double {
        val tau = if (target >= cur) ATTACK_MS else RELEASE_MS
        return cur + (target - cur) * smootherK(dtMs, tau)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  外观量（全部只影响「长什么样」，⛔ 不影响「什么时候」）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ⭐ **涌高**（占 h）= `SWASH_REACH·(0.62 + 0.48·sLow)` ⇒ `0.065..0.115`（§4.6 第 1 行）。
     *
     * ⛔ 只改「水线**最高能爬多高**」，⛔ **不改水线什么时候爬**。
     */
    fun swashReachNow(): Double =
        SeasideWaves.SWASH_REACH * (SWASH_REACH_LO + SWASH_REACH_SPAN * clamp(sLow, 0.0, 1.0))

    /**
     * ⭐ **岸线基准浪带宽**（px）= `h·SWELL_BAND_W·(0.55 + 0.75·min(1.35, bandAmp(领头)))`。
     *
     * ⚠️ §4.6 第 2 行末的警告：这条**用的是另一条公式**且**恒按领头浪算**，
     * 因为它算的是**水线**（`computeShore` 喂给 `fray` 的 `swashW0`）。
     * ⛔ 渲染层**不要**拿它去画逐浪的 `w0`。
     */
    fun shoreWaveBand(hPx: Double): Double =
        hPx * SeasideWaves.SWELL_BAND_W *
            (0.55 + 0.75 * min(1.35, band_amp(true, sLow, sMid, setImp)))

    /**
     * 逐浪的 `amp`（§14.3.4）：
     * `amp = if (isLead) 0.55 + 0.45·bandAmp(0) else 0.46 + 0.45·bandAmp(1 + (wiS % 2))`。
     *
     * @param wiS ⭐ **稳定外观索引** `serial and 3`（⛔ 不是绘制次序下标 `wi`）。
     */
    fun waveAmp(isLead: Boolean, wiS: Int): Double =
        if (isLead) 0.55 + 0.45 * band_amp(true, sLow, sMid, setImp)
        else 0.46 + 0.45 * band_amp(false, sLow, sMid, setImp)

    /** 泡沫振幅（§4.6 第 2 行）。⛔ 只到这里为止 —— ⛔ **不参与任何时序**。 */
    fun bandAmp(isLead: Boolean): Double = band_amp(isLead, sLow, sMid, setImp)

    /** 领头浪的 `kA`（唯一吃音频能量的那一条支路）。 */
    fun leadKa(amp: Double, fade: Double, adv: Double): Double =
        lead_wave_kA(amp, fade, adv, sEnergy)

    /** ⭐ 非领头浪的 `kA` —— ⛔ **不吃音频**（本方法签名里没有任何音频量）。 */
    fun followKa(amp: Double, fade: Double): Double = follow_wave_kA(amp, fade)

    /** 飞沫点数比例 `clamp(0.15 + 0.85·sHigh)`。 */
    fun splashRatio(): Double = splashRatio(sHigh)

    /** 残沫白点数比例 `clamp(0.40 + 0.60·sHigh)`。 */
    fun residueRatio(): Double = residueRatio(sHigh)

    /** 浪花手指长度倍率 `(0.35 + 0.65·sMid)`。 */
    fun fingerLength(): Double = fingerLength(sMid)

    /** 浪花手指 alpha 倍率 `0.4 + 0.6·sLow`。 */
    fun fingerAlpha(): Double = fingerAlpha(sLow)

    /** 水色加深 `1 + 0.28·sEnergy`。 */
    fun shadeBoost(): Double = shadeBoost(sEnergy)

    /** 水色深度偏置 `0.10 + 0.55·0.45·sEnergy`。 */
    fun depthBias(): Double = depthBias(sEnergy)

    /** 浅滩带宽度 `0.30 + 0.22·sEnergy`。 */
    fun shoalWidth(): Double = shoalWidth(sEnergy)

    /** 粼光网强度乘子 `0.30 + 0.70·sEnergy`。 */
    fun causticStrength(): Double = causticStrength(sEnergy)

    /** 飞沫点数 `floor(SPLASH_MAX·splashRatio())`（§4.6；`SPLASH_MAX = 180` 由渲染层给）。 */
    fun splashCount(splashMax: Int): Int = max(0, floor(splashMax * splashRatio()).toInt())

    /** 残沫白点数 `ceil(RESIDUE_MAX·residueRatio())`（`RESIDUE_MAX = 52` 由渲染层给）。 */
    fun residueCount(residueMax: Int): Int = max(0, ceil(residueMax * residueRatio()).toInt())
}
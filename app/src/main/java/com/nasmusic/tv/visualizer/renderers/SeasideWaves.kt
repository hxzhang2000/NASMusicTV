package com.nasmusic.tv.visualizer.renderers

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * E43「海边」—— **模拟核心**：离散浪队列 + 岸线（冲流线）+ 逐列湿润记忆。
 *
 * 权威来源：`docs/archive/seaside-visualizer-plan.md` §14.3.1 / §14.3.2 / §14.3.3，
 * 常量与公式逐项对齐 `docs/seaside-preview.html`（原型定稿）。⛔ 本文件**不含绘制层**
 * （`bfy` / `bwj` / `slopeLaw` / `farLaw` / `kA` / `punchHoles` 等属 T2.7 / T2.18）。
 *
 * ## ⛔ 本文件的硬约束（照抄时最容易改回去的）
 * 1. **零 Android import** —— 可纯 JVM 单测；只用 `kotlin.math`。
 * 2. **零音频引用** —— [stepWaves] / [finishWaves] / [waterlineAdvance] / [waveFade] /
 *    [breakerFrontY] 的函数体里**一个音频量都不出现**。唯一的音频入口是 [step] 的
 *    `swashReachNow` / `shoreW0` 两个入参（外观量），且它们的**赋值点排在**
 *    [stepWaves] 与 [waterlineAdvance] **之后** ⇒ 结构上无法影响时序（§4.3.3）。
 * 3. **每帧零分配** —— 所有数组预分配、槽位池固定（[WAVE_POOL]）；推进路径里
 *    ⛔ 不 new 对象 / new 数组 / 装箱。热循环一律用 `while`（`for (i in 0..n)` 会
 *    产生 `IntRange`，ART 上是真分配）。
 * 4. **模拟用 `Double`，绘制层边界再转 `Float`** —— 验收基线全部来自 JS 原型（双精度），
 *    用 `Float` 会在 90 秒累积后漂移、等价性断言失效。
 * 5. **随机一律走确定性 hash** —— ⛔ 绝不 `Random()` / `Math.random()`。
 *
 * ## 九条浪机制红线（本文件负责 1 / 3 / 4 / 6 / 8 / 9，逐条落在 KDoc 里）
 * - 红线 1 ⛔ **禁位置钳位**：[advanceWave] 里 `y += v·dms` 无上限。
 * - 红线 3 ⛔ **禁量化时间换种子**：所有随机场的相位来自连续量（`t` / `adv` / `y`）。
 * - 红线 4 ⛔ **禁取模回绕**：有界振荡一律用 [clamp] 或三角函数（见 `punchHoles`，绘制层）。
 * - 红线 6 ⛔ **禁依赖绘制次序**：[Wave.wiS] / [Wave.lane] 只由 `serial` 决定；
 *   撕裂场「写阵」与「取阵」是**同一个表达式** [tearSlot]。
 * - 红线 8 ⛔ **交接比较须同参数空间**：[waterlineAdvance] 的 `a` 与 `nextPush` 都是冲流 adv。
 * - 红线 9 ⛔ **起退水守卫必须含 `retreatT < 0`** —— 两处触发点都过 [retreatGuard]。
 *
 * ## 帧内调用顺序（⛔ 不可换）
 * ```
 * fillFray(0) → stepWaves(dt) → leadWave() → waterlineAdvance(dt·1000)
 *   → 逐列填 shoreYs / peak[] → finishWaves(dt) → commitWetMarks() → 沿岸 3 抽头平滑
 * ```
 * 早先把 `REACHED→FADING` 放在 [stepWaves] 里 ⇒ `peak[]` 永远是空的 ⇒
 * **湿沙一列都没被写过**（原型真实故障）。全部封装在 [step] 一次调用内。
 */
internal open class SeasideWaves(widthPx: Float, heightPx: Float) {

    // ══════════════════════════════════════════════════════════════════════════
    //  常量 —— §5.1 / §5.2，逐项取自 docs/seaside-preview.html 的实际定义
    // ══════════════════════════════════════════════════════════════════════════

    internal companion object {

        // ── 构图与岸线（§5.1）───────────────────────────────────────────────
        /** 横向采样列数。每条浪 + 岸线共用 ⇒ 逐列状态数组长度 = `COLS + 1` = 97。 */
        const val COLS = 96

        /** 岸线**平均**位置（占 h）。反馈七·2 所有者裁决 `0.740 → 0.620`。 */
        const val SHORE_K = 0.620

        /** 水体场与粼光网的裁剪下界（占 h）。必须 ≥ 水线最高位置。 */
        const val SEA_BOTTOM_K = SHORE_K + 0.20

        /** 浪完全涌上滩时水线高出平均岸线的距离（占 h）。满程 0.105 × 900 = 94.5px。 */
        const val SWASH_REACH = 0.105

        /** 浪带宽度基数（占 h）。 */
        const val SWELL_BAND_W = 0.042

        /** 逐列冲流滞后（占冲流 adv）⇒ 水线参差成舌。⛔ 上调会变成沙上的深缺口。 */
        const val SWASH_LAG = 0.16

        /**
         * 破碎线的出生水深（占 h）：`spawnFar = h·(SHORE_K − WAVE_SPAWN_DEPTH) = −0.04h`
         * （画面顶边上方 36px）⇒ 破碎线行程从 ~459px 拉长到 **~636px**。
         * ⛔ 不是 `WAVE_SPAWN_DEPTH_K`，原型里没有带 `_K` 的版本。
         */
        const val WAVE_SPAWN_DEPTH = 0.66

        /** 破碎线浪脊横向起伏幅度（占 h，峰峰 ~164px @1600×900）。 */
        const val CREST_AMP = 0.220

        /** 岸线浪脊起伏幅度（占 h，峰峰 ~97px）。⭐ 与 [SAND_TEX_TOP] 一起定住水线下界。 */
        const val CREST_AMP_SHORE = 0.130

        /** 相邻浪在剖面 `u` 上的错开量（**共用同一条** `crestProfile`，靠 `lane` 错开）。 */
        const val CREST_LANE_SHIFT = 0.18

        /** 破碎线浪脊相位随**该浪自己的 `adv`** 漂移的总量（u 单位）。⛔ 不挂全局时间。 */
        const val CREST_DRIFT_ADV = 0.35

        /** 交接地形态连续化的起点（adv）：从它起把整个位置连续插值到 `shoreYs`。 */
        const val MORPH_START = 0.55

        /** 潮汐振幅（占 h）。⛔ 刻意克制，早先按 `WAVE_A` 原样叠加出 ±0.10h，岸线像山脉。 */
        const val TIDE_AMP = 0.0060

        /**
         * 沙纹理起点（占 h）。⭐ 必须**高于水线的最大摆动**：
         * `SHORE_K − CREST_AMP_SHORE − TIDE_AMP = 0.484h`，取 0.46 留 22px 余量。
         * ⛔ 早先取 `0.62 = SHORE_K` ⇒ 退水时 `0.484h…0.62h` 露出**海水底色**（红线 7）。
         */
        const val SAND_TEX_TOP = 0.46

        // ── 离散浪队列（§5.2）──────────────────────────────────────────────
        /** 槽位上限；稳态只用 2 个（1 在最前 + 1 排队）。 */
        const val WAVE_POOL = 6

        /** 最小出浪间隔 —— ⛔ 只作「海上一条浪都没有」那条路径的闸门。 */
        const val WAVE_GAP_MS = 2360.0

        /** 补浪时机：滩上无浪 + 恰好一条 `ADVANCING` 且它已过 `y ≥ 0.62`。 */
        const val WAVE_FOLLOW_Y = 0.62

        /** 领队浪从出生跑到滩上的行程时间下界（ms）。 */
        const val WAVE_T_MIN = 4200.0

        /** 领队浪行程时间上界（ms）。 */
        const val WAVE_T_MAX = 5800.0

        /** 跟随浪（海面上第二条）的行程倍率 ⇒ `T ∈ 8400…11600ms`。 */
        const val FOLLOW_T_SLOW = 2.0

        /** `y` 到 1.0 = 「到达滩上」。⛔ 没有 `DEAD` —— 淡出完毕直接回写 [W_EMPTY]。 */
        const val W_REACH_Y = 1.00

        /** 出生时泡沫**时间**淡入（no pop）。 */
        const val WAVE_SPAWN_RAMP_MS = 700.0

        /** 出生时泡沫**行程**淡入（占 adv）。⭐ 与上一条**相乘**才构成完整 `waveFade`。 */
        const val WAVE_SPAWN_FADE_ADV = 0.20

        /** 消散时泡沫原地淡出（与退水**并行**，§4.3.2.3）。 */
        const val WAVE_FADE_MS = 900.0

        /** 破碎线走到这个 `adv` 就开始**驱动冲流**（`swashT` 起累加）。 */
        const val SWASH_LEAD_ADV = 0.85

        /** 冲流从 0 爬到满位所需时间（`push = clamp(owner.swashT / 900, 0, 1)`）。 */
        const val SWASH_RUNUP_MS = 900.0

        /** 退水时长**下限**（⛔ 不再是定值）。 */
        const val SWASH_RETREAT_MS = 440.0

        /** 退水时长**上限**（完全按后浪 ETA 自适应会把退水拉到 ~5.7s）。 */
        const val SWASH_RETREAT_MAX = 1600.0

        /** 干燥时间常数挂在出浪间隔上的比例。 */
        const val SWASH_DRY_FRAC = 0.60

        const val SWASH_DRY_MIN = 420.0
        const val SWASH_DRY_MAX = 2600.0

        /** 派生量：`clamp(2360·0.60, 420, 2600)` = **1416ms**。⛔ 不写死，挂在出浪间隔上。 */
        val dry_tau_ms: Double = min(max(WAVE_GAP_MS * SWASH_DRY_FRAC, SWASH_DRY_MIN), SWASH_DRY_MAX)

        // ── 生命周期状态（§5.2）────────────────────────────────────────────
        const val W_EMPTY = 0
        const val W_ADVANCING = 1
        const val W_REACHED = 2
        const val W_FADING = 3

        // ── swash 周期阶段（§4.3.8）─────────────────────────────────────────
        const val STAGE_UPRUSH = 0
        const val STAGE_RETREAT = 1
        const val STAGE_EXPOSED = 2

        // ── 分形破碎场 fillFray（§5.3）─────────────────────────────────────
        val FRAY_K = doubleArrayOf(0.0090, 0.0261, 0.0592)
        val FRAY_A = doubleArrayOf(0.55, 0.26, 0.12)
        val FRAY_W = doubleArrayOf(0.00016, -0.00034, 0.00064)
        const val FRAY_JIT = 0.055

        /** 前缘纵向位移 = 抖动 × [FRAY_FRONT] × 浪带宽。 */
        const val FRAY_FRONT = 0.35

        /** 整条带厚度一起缩放（保证窄带边界不交叉）。 */
        const val FRAY_WIDTH = 0.30

        /**
         * ⛔ `shoreYs` 与 `breakerFrontY` 共用的 fray 系数 —— **必须字面量一致**。
         * `shoreYs` 里已含前浪自己的 fray，破碎线的 fray 项要随 `morph` 被吸收，
         * 两者系数不同就会在交接处重复计入 / 漏计。
         */
        const val FRAY_SHORE_COEFF = 0.42

        /** 撕裂场按 `lane` 索引：`lane ∈ 0..3`（0 供岸线基线用）。 */
        const val LANE_SLOTS = 4

        // ── 层序强度（§4.3.9 ③ / §14.3.3）─────────────────────────────────
        const val ENERGY_TO_LAYERS = 0.60

        // ⛔ 死代码：逐列涌高系数 `reachK` 在原型里**已无读取方**（§5.2 / §5.5）⇒ 端口**不实现**
        //    （既不分配也不写入）。逐浪涌高系数保留在 [Wave.reach] 仅为对齐原型字段表。

        // ── 内部：sin 查表（原型 LUT_N = 4096，Float32Array 存储）──────────
        //    ⛔ 逐位对齐原型：`SIN[i] = float32(sin(i / SCALE))`，索引用 `(a·SCALE).toInt() and 4095`
        //    （截断向零 + 回绕，等价于 sin 但带 LUT 量化）。`fillFray` 靠它保证逐位复现。
        private const val SIN_LUT_N = 4096
        private const val SIN_LUT_MASK = SIN_LUT_N - 1
        private val SIN_LUT_SCALE = SIN_LUT_N / (Math.PI * 2.0)
        private val SIN_LUT = DoubleArray(SIN_LUT_N + 1) { sin(it / SIN_LUT_SCALE).toFloat().toDouble() }

        // ══════════════════════════════════════════════════════════════════════
        //  §14.3.1 确定性 hash / 噪声 / 剖面（纯函数，G2 直调）
        //  ⛔ 绝不 `Random()` / `Math.random()`。位运算逐位照抄 JS 的 32bit 语义。
        // ══════════════════════════════════════════════════════════════════════

        /** ⛔ 与 JS `clamp` 同序（先下界后上界）。 */
        fun clamp(v: Double, a: Double, b: Double): Double = if (v < a) a else if (v > b) b else v

        fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

        fun smoothstep(e0: Double, e1: Double, x: Double): Double {
            val t = clamp((x - e0) / (e1 - e0), 0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }

        /**
         * 确定性 32bit hash → `[0,1)`。
         *
         * 逐位照抄 `function hash32(x){ x = (x^61)^(x>>>16); x = (x+(x<<3))|0;
         * x = Math.imul(x, 0x27d4eb2d); x ^= x>>>15; return (x>>>0)/4294967296; }`：
         * `Int` 的溢出即 `|0`，`Int` 乘法即 `Math.imul`，`ushr` 即 `>>>`。
         *
         * ⭐ **2026-10-05 性能轮：末尾的 `/ 4294967296.0` 换成 `* INV_2_POW_32`。**
         * 两者**逐位相同**：`4294967296.0 = 2^32` 恰为 2 的幂，分子是 `[0, 2^32)` 的整数
         * （在 binary64 里可精确表示）⇒ 除与乘都只是把指数挪 32 位，**无舍入**。
         * ⭐ 已用 `2^24` 个输入做穷举校验：两个式子的 `Double` **全部相等**（0 处不符）。
         * 动机：真机实测 [com.nasmusic.tv.visualizer.renderers.SeasideRenderer] 的沙纹理烘焙
         * 每像素要跑 **26 次** `hash32`（= 4 个 `vnoise2` × 4 + 2 个层④）⇒ 这 26 次除法是
         * 单像素成本的大头；`fmul` 在旧 ARM 上比 `fdiv` 快数倍。
         * ⚠️ **不是**「fast」的意思，`INV_2_POW_32` 就是 `2^-32`。
         */
        fun hash32(xIn: Int): Double {
            var x = xIn
            x = (x xor 61) xor (x ushr 16)
            x = x + (x shl 3)
            x = x * 0x27d4eb2d
            x = x xor (x ushr 15)
            return (x.toLong() and 0xFFFFFFFFL) * INV_2_POW_32
        }

        /** `2^-32` = `1 / 4294967296`（[hash32] 的精确缩放因子，见那条 KDoc）。 */
        private const val INV_2_POW_32 = 2.3283064365386963E-10

        /**
         * 二维 hash（`i` 加盐）→ `[0,1)`。
         * `Math.imul(a,b)` ≡ Kotlin `Int` 乘法（同样按 2^32 回绕）⇒ 常量必须写成等价的
         * **有符号** Int：`2654435761` 作为整数字面量在 Kotlin 里是 `Long`，会改变乘法宽度。
         */
        fun hash2(i: Int, salt: Int): Double =
            hash32(((i + 1) * -1640531535) xor ((salt + 7) * 40503))

        /**
         * 确定性一维值噪声（hash 晶格 + smoothstep 插值）—— 沿 shore 轴的随机性来源。
         * ⚠️ `xi` 必须落在 `Int` 范围内（本类所有调用点的 `x` 都在 ±1e3 量级）。
         */
        fun noise1(x: Double, seed: Int): Double {
            val xi = floor(x)
            val xf = x - xi
            val s = xf * xf * (3.0 - 2.0 * xf)
            return lerp(hash2(xi.toInt(), seed), hash2(xi.toInt() + 1, seed), s)
        }

        /**
         * 分形叠加（fBm）。⭐ 八度比 **2.07**（频率互不成整数比 ⇒ 无可察觉重复）、
         * ⛔ 衰减 **0.60**（0.5 会让能量几乎全在最低八度 ⇒ 浪脊波长长、幅度小）。
         */
        fun fbm1(x: Double, seed: Int, oct: Int): Double {
            var a = 0.0
            var amp = 0.5
            var f = 1.0
            var o = 0
            while (o < oct) {
                a += amp * (noise1(x * f, seed + o * 977) * 2.0 - 1.0)
                f *= 2.07
                amp *= 0.60
                o++
            }
            return a
        }

        /**
         * ⭐ 把 fbm1 拉满到 `0..1` 供包络 / 密度场使用。
         * ⛔ `0.5 + 0.62·fbm1` 会把所有包络压在 `0.26..0.74` —— 既到不了 0
         * （做不出平静空档）也到不了 1（最强的也不够强）。
         */
        fun fbm_norm(x: Double, seed: Int, oct: Int): Double =
            clamp(0.5 + 1.45 * fbm1(x, seed, oct), 0.0, 1.0)

        /** 同 [fbm_norm]，但返回 `−1..1`（逐列双向偏移，例如推进滞后）。 */
        fun fbm_signed(x: Double, seed: Int, oct: Int): Double = (fbm_norm(x, seed, oct) - 0.5) * 2.0

        /**
         * ⭐⭐ 浪脊横向剖面 —— **域扭曲的 fBm，⛔ 不是正弦叠加**。
         * ```
         * wx = fbm1(u·0.83, seed+511, 2)      // 两个低频 fBm 偏移采样坐标
         * wy = fbm1(u·0.61, seed+733, 2)
         * v  = u + wx·1.70 + wy·0.85           // ① 域扭曲 ⇒ 浪脊自己拐弯、成 S
         * env = 0.12 + 0.88·(0.5 + 0.5·fbm1(u·0.37, seed+613, 2))   // ③ 包络
         * return (0.62·fbm1(v, seed+101, 4) + 0.38·fbm1(v·2.9, seed+211, 2)) · env
         * ```
         * ⛔ 包络下限 0.30 → 0.12：0.30~1.00 时所有岸段强度差不多，仍读成「一条平缓的带」。
         */
        fun crest_profile(u: Double, seed: Int): Double {
            val wx = fbm1(u * 0.83, seed + 511, 2)
            val wy = fbm1(u * 0.61, seed + 733, 2)
            val v = u + wx * 1.70 + wy * 0.85
            val env = 0.12 + 0.88 * (0.5 + 0.5 * fbm1(u * 0.37, seed + 613, 2))
            return (0.62 * fbm1(v, seed + 101, 4) + 0.38 * fbm1(v * 2.9, seed + 211, 2)) * env
        }

        /** 确定性 2D 值噪声（hash 晶格 + 双线性）—— 沙的斑驳与起伏带扭曲（烘焙层用）。
         *  ⛔ 手写四角哈希而不抽局部函数：⛔ 每帧路径上不引入任何函数对象。 */
        fun vnoise2(x: Double, y: Double, seed: Int): Double {
            val xi = floor(x)
            val yi = floor(y)
            val xf = x - xi
            val yf = y - yi
            val sx = xf * xf * (3.0 - 2.0 * xf)
            val sy = yf * yf * (3.0 - 2.0 * yf)
            val ix = xi.toInt()
            val iy = yi.toInt()
            val h00 = hash32((ix * 73856093) xor (iy * 19349663) xor seed)
            val h10 = hash32(((ix + 1) * 73856093) xor (iy * 19349663) xor seed)
            val h01 = hash32((ix * 73856093) xor ((iy + 1) * 19349663) xor seed)
            val h11 = hash32(((ix + 1) * 73856093) xor ((iy + 1) * 19349663) xor seed)
            return lerp(lerp(h00, h10, sx), lerp(h01, h11, sx), sy)
        }

        /** sin 查表（原型 `LUT_N = 4096`，`Float32Array` 存储 ⇒ 逐位复现）。 */
        fun fsin(a: Double): Double = SIN_LUT[((a * SIN_LUT_SCALE).toInt()) and SIN_LUT_MASK]

        /**
         * ⭐ 层序强度：按**离岸进度**而非层号 —— 一条浪离岸越近越强。
         * `strength = 0.32 + 0.68·nearness`；`thresh = ENERGY_TO_LAYERS·0.5·(1 − nearness)`；
         * `appear = 0.30 + 0.70·smoothstep(thresh, 1, energy)`。
         * ⛔ **只领头浪用**它；非领头浪的 `kA` 有意不吃音频（§4.3.9 ③）。
         * 本函数是**纯函数**（`energy` 只是入参），⛔ 不参与任何时序。
         */
        fun layer_alpha(adv: Double, energy: Double): Double {
            val nearness = clamp(adv, 0.0, 1.0)
            val strength = 0.32 + 0.68 * nearness
            val thresh = ENERGY_TO_LAYERS * 0.5 * (1.0 - nearness)
            val appear = 0.30 + 0.70 * smoothstep(thresh, 1.0, energy)
            return clamp(strength * appear, 0.0, 1.0)
        }

        /**
         * 水线摆动的**保守下界**（px）—— 红线 7 的可查询形式：
         * `swashFrontY = h·SHORE_K + 潮汐 + crestProfile·h·CREST_AMP_SHORE`，
         * 取 `|crestProfile| ≤ 1`、`|sin| ≤ 1` ⇒ 最小 y = `h·(SHORE_K − CREST_AMP_SHORE − TIDE_AMP)`。
         * ⛔ [SAND_TEX_TOP] 必须小于该值，否则退水时那段区间露出海水底色。
         */
        fun waterline_min_bound(h: Double): Double = h * (SHORE_K - CREST_AMP_SHORE - TIDE_AMP)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  §14.3.2 一个槽位 = 一条浪
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 一个槽位 = 一条独立的浪。`peak[]` 长度 [COLS] + 1。
     *
     * ⛔ [peak] 初始化 `-1e9`：早先写 `h` ⇒ `y > wetMark` 恒 false ⇒
     * 最高水位从来没被记下来，湿区一路撑到画面底边（一条 246px 的假水渍）。
     */
    internal class Wave(cols: Int) {
        /** 生命周期状态。[W_EMPTY] / [W_ADVANCING] / [W_REACHED] / [W_FADING]。⛔ 没有 `DEAD`。 */
        var state: Int = W_EMPTY

        /** ⭐ **破碎线行程**（adv 空间）：`0` = 出生，`[W_REACH_Y]` = 到达滩上。
         *  ⛔ 这**不是**像素位置；像素位置由 [SeasideWaves.breakerFrontY] 现算。 */
        var y: Double = 0.0

        /** 推进速度（每 ms 的 `Δy`）。⭐ 出生时定死、途中不变。 */
        var v: Double = 0.0

        // L14 修复（2026-10-06，代码审查报告 §5）：删除死字段 reach——逐浪涌高系数
        // 只写不读（原型 §5.2/§5.5 已移除逐列 reachK），保留仅为对齐原型字段表；
        // 连同 :676/:966 两处写入一并移除（原型端口不再携带该字段）。

        /** `3300 + serial·733` —— ⭐ 破碎线浪脊形状的唯一来源。 */
        var seed: Int = 0

        /** 出生序号，每出生 +1。⭐ 槽位会回收复用 ⇒ 身份索引与单浪跟踪全靠它。 */
        var serial: Int = -1

        /** 出生后已过的毫秒（时间淡入用）。 */
        var alive_t: Double = 0.0

        /** 消散已进行的毫秒。 */
        var fade_t: Double = 0.0

        /** 到过滩上？= 会留湿沙（由 `REACHED → FADING` 那一帧写上）。 */
        var hit: Boolean = false

        /** ⭐ **冲流计时**：`y ≥ [SWASH_LEAD_ADV]` 起累加 `dms`。
         *  ⚠️ 对 [W_REACHED] / [W_FADING] **也继续累加**（它们 `y` 恒为 1.0）
         *  ⇒ 「破碎线抵滩」与「冲流满位」**同一帧发生**，交接处位置天然连续。 */
        var swash_t: Double = 0.0

        /** 到达滩上那一瞬、逐列的水线 y（= 湿沙高水位）。由 [SeasideWaves.step] 逐列填。 */
        val peak: DoubleArray = DoubleArray(cols + 1) { -1e9 }

        /** ⭐ 稳定的外观索引之一：**只由身份决定、终身固定**。
         *  ⛔ 绝不可用绘制次序下标 `wi` —— 别的浪出生/回收时它会变。 */
        val wi_s: Int get() = serial and 3

        /** ⭐ 稳定的外观索引之二：泳道号 `1 + (serial % 3)`，**恒不为 0**、终身固定。
         *  ⛔ 绝不可写成 `isLead ? 0 : (wi and 1) + 1` —— `isLead` 翻转时 lane 会跳变，
         *  实测**每条浪一生恰好跳 2 次**，`fillFray`/`fillTears`/蕾丝/贴图整套噪声实现切换。 */
        val lane: Int get() = 1 + (serial % 3)

        /** 绘制上的「前浪」= 真正抵达滩上的那条。
         *  ⚠️ 这里**只作为事实判定**暴露给调用方；⛔ 绘制层不得用 `leadWave()` 判定它
         *  （`leadWave()` 在滩上无浪时返回还在外海的那条，判定错 ⇒ 整条带瞬移 187/387px）。 */
        val is_beach: Boolean get() = state == W_REACHED || state == W_FADING
    }

    // ── 尺寸 ─────────────────────────────────────────────────────────────────

    /** 画布宽（px）。[resize] 时更新。 */
    var w: Double = widthPx.toDouble()
        private set

    /** 画布高（px）。[resize] 时更新。 */
    var h: Double = heightPx.toDouble()
        private set

    /** 横向采样列数 = [COLS]。逐列数组长度 = `cols + 1`。 */
    val cols: Int = COLS

    /** ⛔ 逐列数组长度（`cols + 1`）。绘制层按 `x = w·i/cols` 取列。 */
    val colCount: Int = COLS + 1

    private var k_scale: Double = 1.0

    // ── 逐列状态（全部预分配，⛔ 每帧不得新建）────────────────────────────────

    /** 逐列 x（px）。 */
    val shore_xs: DoubleArray = DoubleArray(colCount)

    /** ⭐ 水线逐列 y（px）—— **冲流线**当前位置，全场景唯一权威。 */
    val shore_ys: DoubleArray = DoubleArray(colCount)

    /** 该列**历史上**被淹到的最内陆 y（⛔ 只增不减）。初始化 `-1e9`。 */
    val wet_mark: DoubleArray = DoubleArray(colCount) { -1e9 }

    /** 该列当前湿润度 `0..1`，退水后按 [dry_tau_ms] 指数衰减。 */
    val wet_amt: DoubleArray = DoubleArray(colCount)

    /** 本帧用于渲染的湿区外沿（随干燥向水线收拢）。 */
    val wet_edge: DoubleArray = DoubleArray(colCount)

    /** 该列干燥速率系数（`0.52 + 0.96·fbmNorm` ⇒ `0.52..1.48`）。 */
    val dry_k: DoubleArray = DoubleArray(colCount)

    /** 该列当前冲流推进量 `0..1`。 */
    val swash_pos: DoubleArray = DoubleArray(colCount)

    /** 沿岸 3 抽头平滑 [wet_amt] 用的临时缓冲。 */
    private val wet_tmp: DoubleArray = DoubleArray(colCount)

    /** 分形破碎场，**按 lane 索引**（0 = 岸线基线，1..3 = 逐浪）。⛔ 不是共享单份。 */
    private val frays: Array<DoubleArray> = Array(LANE_SLOTS) { DoubleArray(colCount) }

    /** 撕裂场，**按 lane 索引**。⭐ 写阵与取阵共用 [tearSlot] ⇒ 结构上不可能错位。 */
    private val tears: Array<DoubleArray> = Array(LANE_SLOTS) { DoubleArray(colCount) }

    // ── 队列标量 ─────────────────────────────────────────────────────────────

    private val waves: Array<Wave> = Array(WAVE_POOL) { Wave(COLS) }

    private var wave_serial: Int = 0
    private var spawn_acc: Double = 0.0

    /** 退水已进行的毫秒数；`< 0` = 不在退水（水线由浪驱动）。 */
    var retreat_t: Double = -1.0
        private set

    /** 退水时长（自适应：`max(SWASH_RETREAT_MS, min(SWASH_RETREAT_MAX, eta))`）。 */
    var retreat_dur: Double = SWASH_RETREAT_MS
        private set

    /** 本帧的队列级冲流推进量 `0..1`（[waterlineAdvance] 的返回值）。 */
    var push: Double = 0.0
        private set

    /** 本帧的 `swashReachNow`（= `SWASH_REACH·(0.62 + 0.48·sLow)`，音频只管「涌多高」）。 */
    var swash_reach_now: Double = SWASH_REACH * 0.62
        private set

    /** 本帧的岸线基准浪带宽（px）。 */
    var shore_w0: Double = 0.0
        private set

    /** swash 周期阶段：[STAGE_UPRUSH] / [STAGE_RETREAT] / [STAGE_EXPOSED]。 */
    var stage: Int = STAGE_EXPOSED
        private set

    /** 最近一次出浪算出的行程时间 `T`（ms）。单测用它断言「非有限 `T` = 0」。 */
    var last_spawn_travel_ms: Double = 0.0
        private set

    /** 累计出浪次数（槽位回收不重置，只有 [reset] 清零）。 */
    var spawn_count: Int = 0
        private set

    init {
        rebuild()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  对外只读接口
    // ══════════════════════════════════════════════════════════════════════════

    /** 逐列长度（= `COLS + 1`）。 */
    val column_count: Int get() = colCount

    /** 槽位总数（[WAVE_POOL]）。 */
    val pool_size: Int get() = WAVE_POOL

    /** 在册浪数（`state != W_EMPTY`）。harness 的「海面浪数直方图」就按它统计。 */
    val active_count: Int
        get() {
            var n = 0
            var i = 0
            while (i < WAVE_POOL) {
                if (waves[i].state != W_EMPTY) n++
                i++
            }
            return n
        }

    /** 按槽位取浪（`slot ∈ 0 until WAVE_POOL`）；空槽返回 `null`。 */
    fun wave_at(slot: Int): Wave? {
        if (slot < 0 || slot >= WAVE_POOL) return null
        val wv = waves[slot]
        return if (wv.state == W_EMPTY) null else wv
    }

    /**
     * 取**第 k 条在册浪**（按 [Wave.serial] 升序）。
     * ⛔ 返回的**下标不是身份** —— 取外观量请一律用 [Wave.serial] / [Wave.wi_s] / [Wave.lane]。
     */
    fun active_at(k: Int): Wave? {
        if (k < 0) return null
        var i = 0
        while (i < WAVE_POOL) {
            val wv = waves[i]
            if (wv.state != W_EMPTY) {
                var smaller = 0
                var j = 0
                while (j < WAVE_POOL) {
                    val other = waves[j]
                    if (other.state != W_EMPTY && other.serial < wv.serial) smaller++
                    j++
                }
                if (smaller == k) return wv
            }
            i++
        }
        return null
    }

    /**
     * ⭐ 领头的浪 —— ⛔ **只**在 [step] 里用（取 `seedLag` 与写 `peak[]`）。
     * ⛔ 必须把 [W_FADING] 也算进来（否则泡沫前缘与沙面脱开）；
     * ⛔ 滩上的浪优先于海里的浪。
     * ⛔⛔ **绘制层不得用它判定 `isLead`**（见 [Wave.is_beach] 的说明）。
     */
    fun lead_wave(): Wave? {
        var best: Wave? = null
        var i = 0
        while (i < WAVE_POOL) {
            val wv = waves[i]
            if (wv.state != W_EMPTY) {
                val b = best
                if (b == null) {
                    best = wv
                } else {
                    val bBeach = b.state == W_REACHED || b.state == W_FADING
                    val wBeach = wv.state == W_REACHED || wv.state == W_FADING
                    if (wBeach && !bBeach) {
                        best = wv
                    } else if (wBeach == bBeach && wv.y > b.y) {
                        best = wv
                    }
                }
            }
            i++
        }
        return best
    }

    /**
     * 峰值包络（湿沙高水位）—— 领头浪**抵滩那一瞬**的逐列水线 y。
     * @return 长度 [column_count] 的数组；无领头浪 / 尚未抵滩时返回 `null`
     *         （此时内容是 `-1e9`，⛔ 不是有效水位）。
     */
    fun lead_peak(): DoubleArray? {
        val lead = lead_wave() ?: return null
        return lead.peak
    }

    /** 峰值包络的有效长度（`COLS + 1`）。 */
    val peak_length: Int get() = colCount

    /** ⭐ 破碎线出生点（px）= `h·(SHORE_K − WAVE_SPAWN_DEPTH)` = `−0.04h`。 */
    val spawn_far_px: Double get() = h * (SHORE_K - WAVE_SPAWN_DEPTH)

    /** ⭐ 沙纹理上沿（px）= [SAND_TEX_TOP]·h。红线 7：必须 ≤ [waterline_min_bound_px]。 */
    val sand_tex_top_px: Double get() = h * SAND_TEX_TOP

    /** ⭐ 水线摆动的保守下界（px），供红线 7 自检。 */
    val waterline_min_bound_px: Double get() = waterline_min_bound(h)

    /** 逐列 x：`w·i/cols`。⛔ 无分配（供绘制层与断言复用同一列栅格）。 */
    fun col_x(i: Int): Double = w * i / cols

    // ══════════════════════════════════════════════════════════════════════════
    //  尺寸与复位
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 换尺寸：重建 `kScale`、按需重分配逐列数组、**清空队列与逐列记忆**。
     * ⛔ 换尺寸时不该留半个队列（`resize()` 必须调它）。
     */
    fun resize(widthPx: Float, heightPx: Float) {
        w = widthPx.toDouble()
        h = heightPx.toDouble()
        k_scale = 1920.0 / max(w, 1.0)
        // 逐列数组长度固定（COLS+1），但列栅格变了 ⇒ 岸线/破碎场的噪声相位随之改变，
        // 故湿沙记忆（绝对像素）必须一并清空，否则会留下一条按旧尺寸写的水渍。
        reset()
    }

    /** 恢复到「海上一条浪都没有」的初始态（并清空逐列湿润记忆）。 */
    fun reset() {
        rebuild()
    }

    private fun rebuild() {
        k_scale = 1920.0 / max(w, 1.0)
        var i = 0
        while (i <= cols) {
            shore_xs[i] = w * i / cols
            shore_ys[i] = 0.0
            wet_mark[i] = -1e9
            wet_amt[i] = 0.0
            wet_edge[i] = 0.0
            dry_k[i] = 0.0
            swash_pos[i] = 0.0
            wet_tmp[i] = 0.0
            i++
        }
        var l = 0
        while (l < LANE_SLOTS) {
            var c = 0
            while (c <= cols) {
                frays[l][c] = 0.0
                tears[l][c] = 0.0
                c++
            }
            l++
        }
        var s = 0
        while (s < WAVE_POOL) {
            val wv = waves[s]
            wv.state = W_EMPTY
            wv.y = 0.0
            wv.v = 0.0
            wv.seed = 0
            wv.serial = -1
            wv.alive_t = 0.0
            wv.fade_t = 0.0
            wv.hit = false
            wv.swash_t = 0.0
            var c = 0
            while (c <= cols) {
                wv.peak[c] = -1e9
                c++
            }
            s++
        }
        wave_serial = 0
        spawn_acc = 0.0
        retreat_t = -1.0
        retreat_dur = SWASH_RETREAT_MS
        push = 0.0
        swash_reach_now = SWASH_REACH * 0.62
        shore_w0 = 0.0
        stage = STAGE_EXPOSED
        last_spawn_travel_ms = 0.0
        spawn_count = 0
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  §14.3.3 帧推进入口（⛔ 帧内顺序不可换）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ⭐⭐ 一次调用完成整帧模拟。**⛔ 帧内顺序不可换**：
     * ```
     * fillFray(0) → stepWaves(dt) → lead_wave() → waterlineAdvance(dt·1000)
     *   → swash_reach_now 赋值 → 逐列 shore_ys / peak[] → finishWaves(dt)
     *   → commitWetMarks() → 沿岸 3 抽头平滑 wet_amt
     * ```
     * 早先把 `REACHED→FADING` 放在 `stepWaves` 里 ⇒ `peak[]` 永远是空的
     * ⇒ **湿沙一列都没被写过**（原型真实故障）。
     *
     * @param dtSec 帧时长（**秒**）。⛔ 暂停时传 `0` —— 队列 / 退水 / 干燥全部冻结。
     * @param tMs 绝对时间（**毫秒**，与画布尺寸同一时基）。⛔ 不是 `System.currentTimeMillis()`。
     * @param swashReachNow `SWASH_REACH·(0.62 + 0.48·sLow)` —— 音频只管「涌多高」，
     *        ⛔ **不改水线什么时候爬**；它在下面被赋值的时刻排在 `stepWaves` 与
     *        `waterlineAdvance` **之后** ⇒ 结构上无法影响时序。
     * @param shoreW0 岸线基准浪带宽（px）= `h·SWELL_BAND_W·(0.55 + 0.75·bandAmp(0))`，
     *        恒按领头浪算（因为它算的是水线）。⛔ 同样只影响外观。
     */
    fun step(dtSec: Double, tMs: Double, swashReachNow: Double, shoreW0: Double) {
        val dt = dtSec
        val dms = dt * 1000.0

        // ① 岸线基线的分形破碎场（lane 0）—— 原型 computeShore 的第一行
        fill_fray(tMs, 0)

        // ② 队列先走一步：出浪调度 + 推进 + 到达判定 + swashT 累加
        step_waves(dt)

        // ③ 领头浪（只用于写 peak[]；⛔ 绘制层不得拿它判定 isLead）
        val lead = lead_wave()

        // ④ 队列级冲流推进量 —— 两个位置量中的「冲流线」
        val p = waterline_advance(dms)

        // ⑤ ⛔ 音频量在此才落地（排在 ② ④ 之后）—— 见 KDoc
        swash_reach_now = swashReachNow
        shore_w0 = shoreW0
        push = p

        val dry_tau = dry_tau_ms
        // ⛔ **有意偏离原型**：原型写 `dt > 0 ? exp(...) : 0`，于是暂停（dt = 0）时
        //   `pow(0, dryK) = 0` ⇒ **湿沙在一帧内被清零**，而 §4.3.3 明确要求
        //   「暂停时 dt 传 0 ⇒ 队列 / 退水 / 干燥全部**冻结**」。这里取 `1.0`（恒等元）
        //   才满足规格。⛔ 不影响任何基线数值（基线跑的是 dt > 0）。
        val decay = if (dt > 0.0) exp(-dt * 1000.0 / dry_tau) else 1.0

        // 阶段：0 = 上涌/滩上有浪，1 = 退水，2 = 裸露干燥（⛔ 必须在 waterlineAdvance 之后算）
        var beach_any = false
        var adv_any = false
        var s = 0
        while (s < WAVE_POOL) {
            val st = waves[s].state
            if (st == W_REACHED || st == W_FADING) beach_any = true else if (st == W_ADVANCING) adv_any = true
            s++
        }
        val retreating = retreat_t >= 0.0 && retreat_t < retreat_dur
        stage = if (beach_any || adv_any) (if (retreating) STAGE_RETREAT else STAGE_UPRUSH) else STAGE_EXPOSED

        // ⛔ 原型此处的 `seedLag = lead?.seed ?: 2711` **只**用于算逐列涌高系数 `reachK`，
        // 而 `reachK` 在交接连续化时已被移除、当前**无读取方**（§5.2 / §5.5）⇒ 端口不实现，
        // `seedLag` 因此一并消失。逐浪差异改由 `T`（速度）、[Wave.seed]（浪脊形状）承担。
        val shore_base_fray = fray_of(0)

        var i = 0
        while (i <= cols) {
            val x = shore_xs[i]
            val uu = x * 0.0042 * k_scale
            // ⭐ 逐列推进滞后：同一个确定性噪声场（种子跟着领头浪走 ⇒ 每条浪舌头形状都不同）
            val lag = clamp(
                1.00 * fbm_signed(uu * 0.75 + 91.3, 4409, 2) +
                    0.35 * fbm_signed(uu * 2.30 + 17.7, 4523, 2), -1.0, 1.0
            )
            dry_k[i] = 0.52 + 0.96 * fbm_norm(uu * 1.63 + 41.7, 5701, 2)
            // ⛔ 不再对 y 做 smoothstep/三角：领头浪的推进量本身就是 y
            val adv = clamp(p - lag * SWASH_LAG, 0.0, 1.0)
            swash_pos[i] = adv
            val base = swash_front_y(x, tMs) + shore_base_fray[i] * shoreW0 * FRAY_FRONT * FRAY_SHORE_COEFF
            val y = base + h * swashReachNow * adv
            shore_ys[i] = y
            // ⭐ 湿沙高水位**只在浪到达滩上那一瞬**写入 `peak[]`（由 commitWetMarks 落盘）
            if (lead != null && lead.state == W_REACHED) lead.peak[i] = y
            // ⭐ 变干的判据是「这一列此刻是否裸露」，不是「全体有没有浪在推进」
            if (wet_mark[i] > y + 0.5 && decay < 1.0) wet_amt[i] *= decay.pow(dry_k[i])
            val inland = h * 0.012 * fbm_signed(x * 0.0075 * k_scale + 63.1, 6607, 2)
            wet_edge[i] = max(y, lerp(y, wet_mark[i], clamp(wet_amt[i], 0.0, 1.0).pow(0.55)) + inland)
            i++
        }

        // ⑥ ⛔ 顺序要紧：先让当帧到滩的浪拿到 peak[]，再让它转 FADING 并写湿沙
        finish_waves(dt)
        commit_wet_marks()

        // ⑦ 沿岸 3 抽头平滑（逐列记忆物理上没错，但相邻列不该差这么多）
        i = 1
        while (i < cols) {
            wet_tmp[i] = 0.25 * wet_amt[i - 1] + 0.50 * wet_amt[i] + 0.25 * wet_amt[i + 1]
            i++
        }
        wet_tmp[0] = 0.5 * wet_amt[0] + 0.5 * wet_amt[1]
        wet_tmp[cols] = 0.5 * wet_amt[cols] + 0.5 * wet_amt[cols - 1]
        i = 0
        while (i <= cols) {
            wet_amt[i] = wet_tmp[i]
            wet_edge[i] = max(
                shore_ys[i],
                lerp(shore_ys[i], wet_mark[i], clamp(wet_amt[i], 0.0, 1.0).pow(0.55))
            )
            i++
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  §14.3.2 第一段：出浪调度 + 推进 + 到达判定
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 第一段：⭐**补浪调度** + 推进 + 到达判定 + `swashT` 累加。
     *
     * - ⛔ 补浪不是固定定时器（`spawnAcc` / `WAVE_GAP_MS` 只当「海面空」的最小间隔闸）：
     *   滩上有 `REACHED`/`FADING` ⇒ 不补；`advN == 0` ⇒ 受闸；`advN == 1` 且
     *   `front.y >= WAVE_FOLLOW_Y` ⇒ 补一条排队（⇒ 稳态恒为「1 条在最前 + 1 条排队」，
     *   「两槽同框」帧占比 16% → **95%**）。
     * - ⛔⛔ **自由推进，不做位置钳位**（红线 1）：`y += v·dms` 无任何上限。
     *   冻结位置（曾用 `WAVE_SPACING_Y`）实测有害：后浪 `adv` **从未超过 0.6**、
     *   永远到不了滩，67% 的绘制调用因 `kA` 过低被 cull ⇒ 读作「走到中间就消失」。
     * - ⭐ **退水期间 `ADVANCING` 的浪照常推进**（⛔ 不得加 `retreat_t` 冻结闸）。
     * - ⛔ 不在这里做 `REACHED → FADING`（必须等 `peak[]` 写完）。
     * - ⛔ **零音频引用**。防御闸门：滩上有浪在消散时到达的浪只「等」不转态。
     */
    private fun step_waves(dt: Double) {
        val dms = dt * 1000.0

        spawn_acc += dms
        var adv_n = 0
        var beach_any = false
        var front: Wave? = null
        var i = 0
        while (i < WAVE_POOL) {
            val o = waves[i]
            val st = o.state
            if (st == W_REACHED || st == W_FADING) beach_any = true
            if (st == W_ADVANCING) {
                adv_n++
                val f = front
                if (f == null || o.y > f.y) front = o
            }
            i++
        }
        var want = false
        if (beach_any) {
            want = false
        } else if (adv_n == 0) {
            want = spawn_acc >= WAVE_GAP_MS
        } else if (adv_n == 1) {
            val f = front
            if (f != null && f.y >= WAVE_FOLLOW_Y) want = true
        }
        if (want && spawn_wave()) spawn_acc = 0.0

        // 防御性闸门：滩上还有浪在消散时，后来的浪贴岸**等待**（y 钳在 1.0 但不转态）——
        // 只是等，不是被消耗；滩上一空，下一帧立即到达。
        var shore_busy = false
        i = 0
        while (i < WAVE_POOL) {
            val st = waves[i].state
            if (st == W_REACHED || st == W_FADING) {
                shore_busy = true
                break
            }
            i++
        }
        i = 0
        while (i < WAVE_POOL) {
            val wv = waves[i]
            if (wv.state == W_EMPTY) {
                i++
                continue
            }
            wv.alive_t += dms
            // ⭐ 破碎线进入上涌区后开始**驱动冲流**（对 REACHED/FADING 也累加）
            if (wv.y >= SWASH_LEAD_ADV) wv.swash_t += dms
            if (wv.state == W_ADVANCING) {
                advance_wave(wv, dms)
                if (wv.y >= W_REACH_Y) {
                    wv.y = W_REACH_Y
                    if (!shore_busy) wv.state = W_REACHED
                }
            }
            i++
        }
    }

    /**
     * ⛔ 红线 1 的唯一实现：**自由推进，无位置钳位**。
     *
     * 这是一个 `open` 接缝，只为单测的**负向自证**（喂一个 `min(y, 0.55)` 的破实现进去，
     * 断言必须失败）。生产路径只有这一份实现。
     */
    internal open fun advance_wave(wv: Wave, dms: Double) {
        wv.y += wv.v * dms
    }

    /**
     * ⛔ 红线 9 的唯一实现：起退水的守卫 —— **必须是 `retreat_t < 0`**。
     *
     * 两个触发点（`REACHED → FADING` 与 `FADING` 结束兜底）都过它。⛔ 拿掉守卫是
     * 「顺手加固」时最容易做的事：退水进行中被重置 `retreat_t = 0`，下一帧推进量会从
     * 「已接近完全退回」（实测 `meanAdv 0.0685`）被瞬间拽回满位（`0.9611`）
     * ⇒ **水线一帧跳 78.79px**。
     *
     * 同为单测负向自证的接缝。
     */
    internal open fun retreat_guard(): Boolean = retreat_t < 0.0

    /**
     * 取第一个空槽；池满则这一拍不出浪（不丢帧、不跳变）⇒ 返回是否真的占到了槽位。
     *
     * ⛔⛔ `follower` 判据（池里是否已有 [W_ADVANCING]）**必须在写 `w.state` 之前**算，
     * 且**按槽位排除自身**。否则会扫到刚创建的自己：那时 `y = 0`、`v` 还是上一条浪残留的 0
     * ⇒ `T = Infinity` ⇒ `v = 0` ⇒ 这条浪永远不动（原型真实 bug，harness 实测
     * `ser0 T=Infinity v=0`、海上只剩 1 条不会动的浪）。
     *
     * ⭐ 不可能 overtake 的根据：`min(T_跟随) = 4200×2.0 = 8400 > max(T_领队) = 5800`
     * ⇒ 两档区间不重叠 ⇒ 到达顺序恒等于出浪顺序、间隔恒 ≥ 2600ms。
     */
    private fun spawn_wave(): Boolean {
        var slot = -1
        var i = 0
        while (i < WAVE_POOL) {
            if (waves[i].state == W_EMPTY) {
                slot = i
                break
            }
            i++
        }
        if (slot < 0) return false

        // ⛔ 必须在写 w.state 之前、且按槽位排除自身
        var follower = false
        i = 0
        while (i < WAVE_POOL) {
            if (i != slot && waves[i].state == W_ADVANCING) {
                follower = true
                break
            }
            i++
        }

        val wv = waves[slot]
        val sd = 3300 + wave_serial * 733
        val f = fbm_norm(wave_serial * 1.37, sd, 3)
        val travel = lerp(WAVE_T_MIN, WAVE_T_MAX, f) * (if (follower) FOLLOW_T_SLOW else 1.0)

        // ── 字段一次性写完（v 必须与 state 同批落地，不能留 0 给上面的扫描）────────
        wv.serial = wave_serial
        wave_serial++
        wv.state = W_ADVANCING
        wv.y = 0.0
        wv.v = 1.0 / travel
        wv.seed = sd
        wv.alive_t = 0.0
        wv.fade_t = 0.0
        wv.hit = false
        wv.swash_t = 0.0

        last_spawn_travel_ms = travel
        spawn_count++
        return true
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  §14.3.2 第二段：REACHED → FADING、淡出、回收、⭐ 起退水的双触发点
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 第二段：`REACHED → FADING`、淡出、槽位回收、⭐ **起退水的双触发点**。
     * 必须在写完 `peak[]` 之后调用。
     *
     * - ⛔ **不在这里补浪**（早先在这一帧顺手发一条会绕过「滩上不补」的约束 ⇒ 多一条浪）。
     * - ⭐ 触发点①：`REACHED → FADING` 且**滩上没有其他浪** ⇒ `retreat_t = 0`。
     *   这是 ⭐ **并行**而非先后：泡沫在 [WAVE_FADE_MS] 里淡出、退水在 `retreat_dur` 里回落。
     * - ⭐ 触发点②（兜底，⛔ 不可省）：滩上**最后一条** [W_FADING] 淡出完毕那一帧 ⇒
     *   `retreat_t = 0`。⛔ 不可省的原因：一条浪在前一条还在滩上时抵滩，① 会被
     *   `still_beach` 守卫跳过，而它的 `REACHED` 转换已发生、不再触发 ⇒ 整轮退水被吞。
     * - ⛔⛔ **两处守卫都必须含 `retreat_t < 0`**（[retreatGuard]）—— 见该函数 KDoc。
     * - ⛔ 零音频引用。
     */
    private fun finish_waves(dt: Double) {
        val dms = dt * 1000.0
        var i = 0
        while (i < WAVE_POOL) {
            val wv = waves[i]
            if (wv.state == W_REACHED) {
                wv.state = W_FADING
                wv.fade_t = 0.0
                wv.hit = true
                var still_beach = false
                var j = 0
                while (j < WAVE_POOL) {
                    val o = waves[j]
                    if (j != i && (o.state == W_REACHED || o.state == W_FADING)) {
                        still_beach = true
                        break
                    }
                    j++
                }
                if (!still_beach && retreat_guard()) retreat_t = 0.0
            }
            i++
        }
        i = 0
        while (i < WAVE_POOL) {
            val wv = waves[i]
            if (wv.state == W_FADING) {
                wv.fade_t += dms
                if (wv.fade_t >= WAVE_FADE_MS) {
                    wv.state = W_EMPTY
                    wv.y = 0.0
                    wv.fade_t = 0.0
                    wv.hit = false
                    var left = false
                    var j = 0
                    while (j < WAVE_POOL) {
                        val st = waves[j].state
                        if (st == W_REACHED || st == W_FADING) {
                            left = true
                            break
                        }
                        j++
                    }
                    if (!left && retreat_guard()) retreat_t = 0.0
                }
            }
            i++
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  §14.3.2 冲流推进量（两个位置量中的「冲流线」）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ⭐⭐ 队列级水线推进量 `0..1`。三个分支：
     *
     * ① **有拥有者、不在退水** ⇒ `push = clamp(owner.swash_t / [SWASH_RUNUP_MS], 0, 1)`。
     *    拥有者优先级：**滩上的浪（`REACHED`/`FADING`）优先**；否则取**破碎线已进入
     *    上涌区**（`swash_t > 0`）的浪中 `y` 最大者。无拥有者 ⇒ `0`（冲流完全退回）。
     * ② **退水中**（`retreat_t >= 0`）⇒ `a = max(0, 1 − retreat_t / retreat_dur)`。
     * ③ **交接**：若**非滩上** owner 的 `next_push >= a` ⇒ `retreat_t = −1`、返回 `next_push`。
     *
     * - ⛔⛔ **交接比较必须在同一参数化空间内**（红线 8）：`a` 与 `next_push` **都是冲流 adv**
     *   （`0` = 完全退回、`1` = 满位）。⛔ 不可写成 `front.y >= a` —— 那是拿 636px 尺度的
     *   破碎线 adv 比 81px 尺度的冲流 adv ⇒ 交接提前到 `adv 0.41~0.77`、整条带瞬移 150~387px。
     * - ⛔⛔ 必须**排除滩上 owner**：退水改为「抵滩即起」后，起退水时 owner 正是那条刚抵滩的
     *   浪、`swash_t` 已满 ⇒ `push = 1`，而 `a` 起始也是 1 ⇒ 退水在第一帧就被取消。
     * - ⛔⛔ 但⛔ **不能**写成 `front !== owner`：后浪进上涌区后自己就成了 owner ⇒ 交接永不
     *   触发 ⇒ `retreat_t` 永不重置为 `−1` ⇒ 之后所有起退水的守卫全被挡住 ⇒ 整轮退水被吞。
     * - ⭐ **退水自适应**：`retreat_dur = max([SWASH_RETREAT_MS], min([SWASH_RETREAT_MAX], eta))`，
     *   `eta = max(0, ([SWASH_LEAD_ADV] − front.y) / front.v)`（`v` 是 adv/ms ⇒ 直接是 ms），
     *   让退水**恰好在后浪进入上涌区那一刻收尾**。
     * - ⛔ 零音频引用。
     */
    private fun waterline_advance(dms: Double): Double {
        var owner: Wave? = null
        var owner_beach = false
        var front: Wave? = null
        var i = 0
        while (i < WAVE_POOL) {
            val wv = waves[i]
            if (wv.state != W_EMPTY) {
                val is_beach = wv.state == W_REACHED || wv.state == W_FADING
                if (is_beach) {
                    if (!owner_beach) {
                        owner = wv
                        owner_beach = true
                    }
                } else {
                    if (wv.state == W_ADVANCING) {
                        val f = front
                        if (f == null || wv.y > f.y) front = wv
                    }
                    if (wv.swash_t <= 0.0) {
                        i++
                        continue
                    }
                    val ow = owner
                    if (ow == null || (!owner_beach && wv.y > ow.y)) owner = wv
                }
            }
            i++
        }
        val ow = owner
        val p = if (ow != null) clamp(ow.swash_t / SWASH_RUNUP_MS, 0.0, 1.0) else 0.0

        if (retreat_t >= 0.0) {
            retreat_t += dms
            val f = front
            val eta = if (f != null) max(0.0, (SWASH_LEAD_ADV - f.y) / f.v) else 0.0
            retreat_dur = max(SWASH_RETREAT_MS, min(SWASH_RETREAT_MAX, eta))
            val a = max(0.0, 1.0 - retreat_t / retreat_dur)
            val incoming = if (ow != null && !owner_beach) ow else null
            val next_push = if (incoming != null && incoming.swash_t > 0.0) {
                clamp(incoming.swash_t / SWASH_RUNUP_MS, 0.0, 1.0)
            } else {
                0.0
            }
            if (next_push > 0.0 && next_push >= a) {
                retreat_t = -1.0
                return next_push
            }
            return a
        }
        return if (ow != null) p else 0.0
    }

    /**
     * 浪的泡沫可见度 = ⭐ **时间淡入 × 行程淡入**（[W_FADING] 时再乘消散项）：
     * ```
     * rampIn = clamp(alive_t / [WAVE_SPAWN_RAMP_MS], 0, 1) * clamp(y / [WAVE_SPAWN_FADE_ADV], 0, 1)
     * return state == W_FADING ? rampIn * clamp(1 − fade_t / [WAVE_FADE_MS], 0, 1) : rampIn
     * ```
     * ⛔ 只用时间淡入不可用：后浪一生约 10s，700ms 只占 7% ⇒ 读作「凭空出现」
     * （owner：「应该从最远端逐渐清晰化出现」）。⭐ 加上行程淡入后，它在最远端那 20%
     * 行程里逐渐清晰化，到岸附近时 `rampIn` 已满。
     * ⛔ 零音频引用。
     */
    fun wave_fade(wv: Wave?): Double {
        if (wv == null) return 0.0
        val ramp_in = clamp(wv.alive_t / WAVE_SPAWN_RAMP_MS, 0.0, 1.0) *
            clamp(wv.y / WAVE_SPAWN_FADE_ADV, 0.0, 1.0)
        if (wv.state != W_FADING) return ramp_in
        return ramp_in * clamp(1.0 - wv.fade_t / WAVE_FADE_MS, 0.0, 1.0)
    }

    /**
     * ⭐ 湿沙高水位**只在浪到达滩上那一瞬**写入（只处理 `W_FADING && hit`）。
     * ⛔ 上冲途中一个字都不写 —— 那里的沙还在水下。
     */
    private fun commit_wet_marks() {
        var i = 0
        while (i < WAVE_POOL) {
            val wv = waves[i]
            if (wv.state == W_FADING && wv.hit) {
                var c = 0
                while (c <= cols) {
                    if (wv.peak[c] > wet_mark[c]) wet_mark[c] = wv.peak[c]
                    if (wv.peak[c] > -1e8) wet_amt[c] = 1.0
                    c++
                }
            }
            i++
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  §14.3.2 / §14.3.4 破碎线前缘映射（两个位置量中的「破碎线」）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ⭐⭐ 破碎线（breaker）前缘 y（px）—— **画出来的白浪带**，也是「第二条浪全程可见」的依据。
     *
     * 出生在远海 `spawnFar = h·([SHORE_K] − [WAVE_SPAWN_DEPTH]) = −0.04h`，终点是
     * [shore_ys] ⇒ 行程 ≈ **636px** @1600×900（⛔ 不是 81px 的水线尺度 —— 压进水线空间
     * 会让整条带藏进领头浪的带里，owner 报「彻底看不到」）。
     *
     * @param isLead ⭐ **绘制上的前浪 = 真正抵达滩上的那条**（`REACHED` / `FADING`，
     *   即 [Wave.is_beach]）。⛔ 绝不是 `w === lead_wave()`。
     * @param lane 泳道号 = `1 + (serial % 3)`（⭐ 终身固定、恒 ≠ 0）。
     * @param waveSeed 该浪自己的 hash 种子（[Wave.seed]）—— 浪脊形状的唯一来源。
     * @param w0 该浪的浪带宽基数（px）。
     *
     * ⛔ **调用前必须先 [fill_fray] 同一个 `lane`**（原型在逐列循环前调用一次）。
     *
     * - ⭐ 位置参数化 `t_adv = adv + (1 − adv)·morph`，`morph = smoothstep([MORPH_START], 1, adv)`：
     *   单调、终点 1、且 `adv < MORPH_START` 时**恒等于 `adv`** ⇒ 全程可见移动。
     *   ⛔⛔ 不可写成 `lerp(spawnFar + crest, shoreYs, morph)`：那样前 55% 的旅程画面上
     *   几乎不动（`spawnFar` 是常数），之后一次性扫过去（owner：「过一会就以较快速度冲出去」）。
     * - ⛔⛔ `crest` 与 fray 项**都必须乘 `(1 − morph)`**：`shore_ys[i]` 里**已经包含**
     *   前浪自己的曲线（`swash_front_y` 内的 `crestProfile·h·[CREST_AMP_SHORE]`）与 fray
     *   （**同系数** [FRAY_SHORE_COEFF]）⇒ 不吸收就会**重复计入** ⇒ 交接处又出现跳变。
     * - ⛔ `isLead` 时直接返回 `shore_ys[i]` ⇒ `adv == 1` 时二者**严格相等** ⇒ 交接无缝。
     * - ⛔ 零音频引用。
     */
    fun breaker_front_y(
        adv: Double,
        i: Int,
        isLead: Boolean,
        lane: Int,
        waveSeed: Int,
        w0: Double
    ): Double {
        if (isLead) return shore_ys[i]
        val spawn_far = spawn_far_px
        val morph = smoothstep(MORPH_START, 1.0, adv)
        val one_minus = 1.0 - morph
        // ⛔⛔ `t_adv = adv + (1 - adv) * morph` —— 括号里是 **(1 - adv)**，
        //   ⛔ 不是 `(1 - morph)`（那会让终点掉到 0.9984，交接处前缘比水线低 ~1px）。
        //   单调、终点 1、且 `adv < MORPH_START` 时恒等于 `adv` ⇒ 全程可见移动。
        val t_adv = adv + (1.0 - adv) * morph
        // ⭐ 相位漂移挂在**该浪自己的 `adv`** 上；`- lane·CREST_LANE_SHIFT` 让逐浪错开
        val u_c = shore_xs[i] * 0.00340 * k_scale + adv * CREST_DRIFT_ADV - lane * CREST_LANE_SHIFT
        val crest = crest_profile(u_c, waveSeed) * h * CREST_AMP * one_minus
        return spawn_far + (shore_ys[i] - spawn_far) * t_adv + crest +
            one_minus * frays[lane_slot(lane)][i] * w0 * FRAY_FRONT * FRAY_SHORE_COEFF
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  §14.3.4 逐列场（分形破碎 / 撕裂）—— ⭐ 按 lane 索引，写取同一表达式
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ⭐ 逐列场（分形破碎 `fray` / 撕裂 `tear`）的**唯一**槽位索引表达式 ——
     * 「写阵」与「取阵」都走它 ⇒ 结构上不可能错位。
     *
     * ⛔⛔ 原型曾用 `(L & 1) === 0` **写** `ragA`/`ragB`、用 `(lane == 0 || lane == 1)` **取**，
     * 两个判据不等价（而 `lane = 1 + serial%3` 恒不取 0）⇒ 一条浪的撕裂场内容**取决于
     * 同帧里别的浪的绘制次序** ⇒ 违反红线 6、owner 报「后浪还是会跳变」。
     * ⇒ 端口改成**每条浪按 `lane` 自有一份**（[LANE_SLOTS] 份预分配），不再有共享数组。
     *
     * 这是一个 `open` 接缝，只为单测的**负向自证**（喂一个把 lane 1 映射到 lane 2 槽位的
     * 破实现进去，「该 lane 的场必须等于按该 lane 公式独立重算的结果」这条断言必须失败）。
     */
    internal open fun lane_slot(lane: Int): Int = lane

    /** 撕裂场缓冲（按 `lane` 索引）。⛔ 不要缓存引用 —— 内容每帧会被 [fill_tears] 覆写。 */
    fun tear_of(lane: Int): DoubleArray = tears[lane_slot(lane)]

    /** 分形破碎场缓冲（按 `lane` 索引）。lane 0 = 岸线基线（[step] 每帧填）。 */
    fun fray_of(lane: Int): DoubleArray = frays[lane_slot(lane)]

    /**
     * ⭐ 分形破碎场：三八度（振幅 ≈0.47 衰减）+ 每列固定 hash 抖动。
     * 光滑正弦边缘一眼就是 CG；真实白沫是大瓣 → 碎瓣 → 细丝的自相似结构。
     *
     * ⭐ 参数是**泳道号 `lane`**（`1 + serial%3`；`0` 供岸线基线），⛔ 不是绘制次序下标。
     *
     * @return 刚被写入的那个缓冲（= [fray_of] `lane`）。
     */
    fun fill_fray(tMs: Double, lane: Int): DoubleArray {
        val out = frays[lane_slot(lane)]
        val l1 = lane * 2.13
        val l2 = lane * 5.31
        val l3 = lane * 1.07
        var i = 0
        while (i <= cols) {
            val x = shore_xs[i]
            out[i] = FRAY_A[0] * fsin(x * FRAY_K[0] * k_scale + tMs * FRAY_W[0] + l1) +
                FRAY_A[1] * fsin(x * FRAY_K[1] * k_scale + tMs * FRAY_W[1] + l2) +
                FRAY_A[2] * fsin(x * FRAY_K[2] * k_scale + tMs * FRAY_W[2] + l3) +
                FRAY_JIT * (hash2(i, 300 + lane) - 0.5) * 2.0
            i++
        }
        return out
    }

    /**
     * 逐列带宽倍率（两套慢/快正弦）⇒ 参差前缘。
     *
     * ⭐⭐ **按 `lane` 选/写同一套场**（[tear_slot]）：⛔ 写阵与取阵若是两个「看似等价」的
     * 判据，就会退化成「外观依赖绘制次序」（红线 6）。
     *
     * @return 刚被写入的那个缓冲（= [tear_of] `lane`）。
     */
    fun fill_tears(lane: Int): DoubleArray {
        val out = tears[lane_slot(lane)]
        val slow = (lane and 1) == 0
        var i = 0
        while (i <= cols) {
            val x = shore_xs[i]
            out[i] = if (slow) {
                1.0 + 0.22 * sin(x * 0.0410 * k_scale + lane * 1.73) +
                    0.12 * sin(x * 0.1490 * k_scale + lane * 0.41)
            } else {
                1.0 + 0.15 * sin(x * 0.0885 * k_scale + lane * 2.91) +
                    0.08 * sin(x * 0.2630 * k_scale + lane * 1.09)
            }
            i++
        }
        return out
    }

    /**
     * ⭐ 岸线（= 冲流线）的**平均**位置：不含涌流推进量，真正的水线 = 这里 + 推进量。
     * `h·[SHORE_K] + 潮汐 + crestProfile(u, 2711)·h·[CREST_AMP_SHORE]`。
     *
     * ⚠️ 潮汐振幅刻意克制在 ±0.006·h（早先按 `WAVE_A` 原样叠加出 ±0.10·h，岸线像山脉）。
     * ⚠️ 这里用的是**全局时间**慢漂移 `t·0.0000105`（水线自身的呼吸），与逐浪挂在
     * `adv` 上的漂移（[CREST_DRIFT_ADV]）是两件事。
     */
    fun swash_front_y(x: Double, tMs: Double): Double {
        var y = h * SHORE_K
        y += h * TIDE_AMP * sin(tMs * 0.000042)
        val u = x * 0.00310 * k_scale + tMs * 0.0000105
        y += crest_profile(u, 2711) * h * CREST_AMP_SHORE
        return y
    }
}
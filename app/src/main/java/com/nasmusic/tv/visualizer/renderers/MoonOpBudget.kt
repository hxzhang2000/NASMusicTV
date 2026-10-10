package com.nasmusic.tv.visualizer.renderers

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min

/**
 * 画质档 —— ⛔ **刻意不是** `androidx.tv.material3` 的 `FxLevel`、也不是 `Color`。
 *
 * 为什么自建（`docs/moonlit-visualizer-plan.md` §10.2 G4，与 E43 §4.9.5 同一条硬前提）：
 * [MoonOpBudget] 的三道门（G4 的 ops / 填充 / native 三张表）必须在**纯 JVM 单测**里直接调，
 * 而带 Compose / material3 类型的签名在 JVM 单测里会 `not mocked`
 * （`FxLevel` 是 value class、`Color` 走 inline class 包装）。
 * 渲染层做**一次性映射**即可：`FxLevel.OFF → [MoonLevel.LOW]`、
 * `FxLevel.LITE → [MoonLevel.MEDIUM]`、`FxLevel.FULL → [MoonLevel.HIGH]`。
 *
 * ⚠️ **顺序语义**：`[LOW] < [MEDIUM] < [HIGH]`，与 §3.1 的三档门控一致
 * （HALO 层数 `1/2/3`、缕数与斑数逐档抬）。
 */
internal enum class MoonLevel {
    /** `FxLevel.OFF`：HALO 1 层、云 2 缕、粼光浅档、⛔ 无银边 / 无涟漪 / 无暗角。 */
    LOW,

    /** `FxLevel.LITE`：银边与涟漪回到场，光柱 2 雾片。 */
    MEDIUM,

    /** `FxLevel.FULL`：全量（§9.1/§9.2 的 HIGH 列）。 */
    HIGH
}

// ══════════════════════════════════════════════════════════════════════════════
//  几何常量 —— ⛔ 必须是**顶层** const 且**声明在枚举之前**：枚举的构造参数里引用自己
//  companion 的成员会被编译器拒绝；同文件内的前向引用同样不可靠。使用点被内联 ⇒
//  零运行期依赖、零初始化顺序问题（E43 `SeasideOpBudget.kt` 文件头同一条）。
// ══════════════════════════════════════════════════════════════════════════════

/** 星野填充 = 天空区占比（几何恒等式）。⛔ 必须与 `MoonlitRenderer.HORIZON_K` 同值。 */
internal const val MOON_HORIZON_FILL = 0.640

/** 月盘 blit 的填充（含 `TEX_SCALE` 外扩的方图口径），实测 `0.062`。 */
internal const val MOON_DISK_FILL = 0.062

/** 相位阴影层数（§4.2 末：暗面 / 临边 / 软化）。 */
internal const val MOON_TERM_LAYERS = 3

/** [MoonOpItem.TERMINATOR] 的解析填充：整盘（不含外扩）× 层数。 */
internal const val MOON_TERM_FILL = MOON_DISK_FILL / (1.25 * 1.25) * MOON_TERM_LAYERS

/** 一次全屏 pass 的填充（`POST_FX` 与 `DAMAGE_COALESCER` 共用，⛔ 不能记 0）。 */
internal const val MOON_POST_FILL = 1.000

/**
 * 地平带（§7.6）相对地平线的**上下沿**偏移（`h` 的占比）：
 * 带子画在 `[horizonY − 0.10h, horizonY + 0.06h]` ⇒ 带高恒为 `0.16h`、通铺全宽。
 *
 * ⚠️ 这两个数**必须**与渲染器画矩形时用的系数一致（`MoonOpBudgetTest` 有漂移哨兵），
 * 它们描述的是一条压暗带的**几何**，⛔ 不是"天空占比"的派生量（见偏差 D26）。
 */
internal const val MOON_BAND_ABOVE_K = 0.10
internal const val MOON_BAND_BELOW_K = 0.06

/**
 * 月晕（§3.1 `HALO_SPEC`）在 **`bass` 中性**时的实测填充（屏）。
 *
 * ⭐ 这三个数是 [MoonOpItem.HALO] 那一行的**唯一输入**：§八 的 `bass → 晕半径 ±6%` 接上之后
 * （T9，[MoonAudio.haloRadiusK]），晕按 `k²` 涨面积 ⇒ 表必须落在**含 bass 的上界**（偏差 **D34**）。
 * ⛔ 不要把它们当"当前表值"读 —— 表值另算，这两个只是 `k = 1` 那一档的原型 `passcost` 实测锚。
 */
internal const val HALO_FILL_NEUTRAL_LOW = 0.069
internal const val HALO_FILL_NEUTRAL_MEDIUM = 0.131
internal const val HALO_FILL_NEUTRAL_HIGH = 0.244

/**
 * 逐帧绘制元素 —— **一个元素一项**，系数 ⛔ **全部是实测值**（不是设计值）。
 *
 * ## 每一行两组数是什么
 * | 组 | 含义 | 喂给 |
 * |---|---|---|
 * | `opsLow/Med/High` | 该元素在该档位的**每帧提交数**（draw call 数） | [MoonOpBudget.estimate] |
 * | `fillLow/Med/High` | 该元素在该档位的**每帧填充量，单位「屏」**（`w·h` = 1 屏，⛔ **含该元素自己的全部提交**） | [MoonOpBudget.overdrawEstimate] |
 *
 * ⛔ **没有 `opsLegacy` / `fillLegacy` 列**（E43 有）。原因：本效果**不存在「改造前」形态** ——
 * E43 的 legacy 列记的是 §4.9.2 合批裁决**之前**的逐块 blit / 逐列渐变，而本表从落第一版起
 * 就是**从定稿原型实测**的（§9.1 的「初版计划」列在文档里追溯，⛔ 不进代码）。
 * 负向自证因此改用**显式注入**：[MoonOpBudget.NOMINAL_GLITTER_OPS_HIGH]（名义条数）、
 * [MoonOpBudget.GRAIN_EXTRA_FILL]（若开颗粒）等常量 + 单测里的减法，⛔ 不是靠 legacy 列。
 *
 * ## 逐项系数的来源（⛔ 改动前先读这四条）
 * 1. **口径 = 半遮态**（`occl` 在场、一缕近云真的过盘），⛔ **不是**满月锁定态 ——
 *    复现命令 `node docs/archive/verification/scripts/moonlit_visual_driver.mjs passcost <TIER> 70`。
 *    拿满月锁定态落表 = 把门写成下界（§十一 T2 明文）。
 * 2. **条件项照上界落表**（`TERMINATOR` / `CLOUD_RIM` / `RIPPLE` 都可能某帧为 0）⇒
 *    门守护的是**最坏帧**，⛔ 不是典型帧。
 * 3. **几何恒等式**（`SKY_BASE = 1.000`、`STARS = HORIZON_K`）与**实测值**（其余全部）分开写，
 *    每条自己的 KDoc 标了是哪一种。
 * 4. ⚠️ **`fill*` 是 16:9 口径**：以 `minDim` 定径的项（`DISK` / `HALO` / `BLOOM` / `TERMINATOR`）
 *    换算式里含 `h/w`，非 16:9 画幅按比例变（4:3 会**更大**）。本表钉的是电视横屏，
 *    ⛔ 不要把它当"与画幅完全无关" —— 与**尺度**无关，与**比例**有关。
 */
internal enum class MoonOpItem(
    /** 中文元素名（单测报告用）。 */
    val label: String,
    val opsLow: Int,
    val opsMed: Int,
    val opsHigh: Int,
    val fillLow: Double,
    val fillMed: Double,
    val fillHigh: Double
) {
    /**
     * 天空 + 海体两块竖向渐变矩形（§3.2 层 1）。
     *
     * ⭐ **填充是几何恒等式 `1.000`**：两块**不相交**、合起来恰好铺满整屏 ⇒ `0.640 + 0.360 = 1.000`，
     * ⛔ **不是 2 屏**（"一次提交 = 一块面积"的前提是它们不重叠，重叠才翻倍）。
     * ⛔ **不相交**同时是 P-1 崩溃规避的一部分（API 22 创维 hwui `Region::createTJunctionFreeRegion`
     * SIGSEGV，见 `AGENTS.md` / §2.3）—— 合并成一块全屏矩形**反而**是错的。
     */
    SKY_BASE("天空+海体", 2, 2, 2, 1.000, 1.000, 1.000),

    /**
     * 星野 blit（`Id.STARFIELD` 一次，裁剪到天空区）。
     * ⭐ 填充 = [MoonlitRenderer.HORIZON_K] 恒等式（天空区 `horizonY/h`，⛔ 不是整屏）。
     * 裁剪靠**矩形 blit 的源区**，⛔ 不靠 `clipPath`（全项目禁用）。
     */
    STARS("星野", 1, 1, 1, MOON_HORIZON_FILL, MOON_HORIZON_FILL, MOON_HORIZON_FILL),

    /**
     * 月晕（§3.1 `HALO_SPEC` 的同心径向渐变，层数 `1 / 2 / 3`）。
     * 填充实测 `0.069 / 0.131 / 0.244`：比 §9.1 初版估的 `0.07 / 0.22 / 0.49` **更省**
     * —— 因为 `HALO_SPEC` 的半径与 α 在定稿时一起收过（⛔ 不要按初版表加层）。
     *
     * ⭐ **T9 起这一行乘了 `k²`**（偏差 **D34**）：§八 的 `bass → 晕半径 ±6%` 是**几何**调制
     * （[MoonAudio.HALO_R_SPAN]），而面积按半径的**平方**收费 ⇒ 最坏帧 = `aBass = 1` 时的
     * `k = haloRadiusK(1) = 1.036`、`k² = 1.0733`：
     * `0.069 → 0.074057` / `0.131 → 0.140602` / `0.244 → 0.261884`，**向上取整到 3 位**
     * = 表里的 `0.075 / 0.141 / 0.262`（⛔ 就近取整会把 MED 得到 `0.141` 恰好一样、
     * 但把 LOW 得到 `0.074` —— 那**低于**换算值，表就又不是上界了；同 [GLITTER] 的取整条款）。
     * ⚠️ **实画的最大半径比这还小一点**：渐变按**桶**建（[MoonAudio.HALO_R_BUCKETS] = 8），
     * 最高桶 7 的桶中心是 `aBass = 0.9375` ⇒ `k = 1.03225` ⇒ 面积 ×`1.0655`。
     * 表按**连续**上界落（`1.036`）是有意的保守：⛔ 不要改成按桶，那会让表跟着桶数变、
     * 而桶数是**缓存粒度**、不是观感参数。判据由 `MoonlitAudioTest ⑬` 用
     * [MoonOpBudget.haloFillAtMaxBass] 逐档对账（含"砍半半径幅度就越线"的负向自证）。
     */
    HALO("月晕", 1, 2, 3, 0.075, 0.141, 0.262),

    /**
     * 远层云（§6.1：缕 × 斑）。提交数 = `CLOUD_FAR × BLOB_FAR` = `2×4 / 3×5 / 3×6`。
     * ⚠️ 单位是**斑**，不是缕 —— 按缕记会把 HIGH 少算 6 倍。
     */
    CLOUD_FAR("远层云", 8, 15, 18, 0.043, 0.110, 0.147),

    /**
     * 近层云（§6.1）。提交数 = `CLOUD_NEAR × BLOB_NEAR` = `2×7 / 3×10 / 4×11`。
     * ⭐ §6.6 的**确定性过境**缕是本表里第 0 号近层缕（`TRANSIT_INDEX = 0`）：
     * 只把它的 `bandY` 从种子随机钉成常量，⛔ **零新增提交、零新增填充**（§十六 Q7③ / 偏差 D16）。
     */
    CLOUD_NEAR("近层云", 14, 30, 44, 0.172, 0.308, 0.436),

    /**
     * 过曝芯（§5.2+ ④，`BLOOM_SCALE = 1.28` 的一次径向渐变 blit）。
     * ⭐ 定稿**新增项**，初版预算里根本没有 —— 它是"月亮亮得发白"的唯一手段，⛔ 不可为省 0.024 屏去掉。
     */
    BLOOM("过曝芯", 1, 1, 1, 0.024, 0.024, 0.024),

    /**
     * 云缝银边（§6.4）—— **条件项**，门槛是逐斑 `back > 0.34` 且档位 ≠ LOW。
     *
     * ⭐ 2026-10-09 补测（`passcost`）：MED 实测 `6~8` 个 / `0.0367` 屏，HIGH `12~13` 个 / `0.0639` 屏，
     * 表取上界 `9 / 15`。**LOW 恒 0 是代码事实**（原型 `S.tier !== 'LOW'` 那道门 + `FxLevel.OFF`
     * 跳过基类后处理），⛔ 不是"省预算"。
     *
     * ⚠️ **这一行的数字差点记错**：原方案写的补测办法（钉标量 `occlManual = 0.5`）**量不到它** ——
     * 钉标量改的是 α 不是几何，`RIM` 在 `fillBy` 里根本不出现（偏差 **D15**）。
     * 判据必须跑在**产生该量的几何**上。
     */
    CLOUD_RIM("云缝银边", 0, 9, 15, 0.0, 0.037, 0.064),

    /**
     * 月盘 blit（[MoonDiskBake] 烘好的圆内切位图，一次 `drawImageRect`）。
     *
     * ⭐ 填充是**几何量**：`π·(TEX_SCALE·MOON_R_K)²·(h/w)` @16:9 = `0.0621` ⇒ 实测 `0.062` 吻合。
     * 注意 `0.062` 里含了 `TEX_SCALE = 1.25` 的位图外扩（画的是 `2·1.25R` 的方图，圆内不透明），
     * ⛔ 别把它当"圆盘真实面积"—— [MoonOpItem.TERMINATOR] 用的那个**不含**外扩。
     */
    DISK("月盘", 1, 1, 1, MOON_DISK_FILL, MOON_DISK_FILL, MOON_DISK_FILL),

    /**
     * 相位阴影（§4.2 末 + §4.4，[MoonPhaseShadow]）—— **条件项**：`f ≥ 0.995` 整段跳过
     * ⇒ 满月夜实测 **0**（原型七轮全在 `phaseLock:'full'` 下跑，所以探针里这一行缺席）。
     *
     * ⭐ 填充 `0.119` 是**解析值**（实测拿不到 ⇒ 本表唯一一个"必须算"的行）：
     * ```
     * 圆盘真实（不含 TEX_SCALE 外扩）面积 = MOON_DISK_FILL / TEX_SCALE² = 0.0397 屏
     * 最坏阴影覆盖 = 整盘（深蛾眉，f → 0）  × 层数 3 = 0.1190 屏
     * ```
     * ⛔ 不要按"半月只遮一半"记 `0.5×`：本表口径是**最坏帧**，而 `f → 0` 时阴影 path
     * 确实铺满整个圆盘轮廓（`MoonPhaseShadow` 的裁剪几何是盘、不是半盘）。
     */
    TERMINATOR("相位阴影", 3, 3, 3, MOON_TERM_FILL, MOON_TERM_FILL, MOON_TERM_FILL),

    /**
     * 地平带（§7.6）：一次纵向渐变 `drawRect`。
     *
     * ⚠️ **初版记 0.01，实测 0.102 —— 差 10 倍**，是"凭感觉记小值"的现行反例（§9.2）。
     *
     * ⚠️ **但 0.102 仍然是错的，真值 `0.160`（偏差 D26）**。原型探针那行写的是
     * `op('HZ', 0.16 * S.horizonK)`，KDoc 据此解释成"它只铺天空侧那一段，所以乘 `HORIZON_K`" ——
     * 而它画的矩形是 `fillRect(0, horizonY - 0.10h, W, 0.16h)`：**通铺全宽**、且横跨地平线
     * **两侧**（`0.10h` 天空 + `0.06h` 海面）。乘 `HORIZON_K` 把一条压暗带折成了"只有天空那份"，
     * 与 `SKY_BASE` 的两块不相交矩形不是一回事（那是分割，这是重叠）。
     * ⭐ 所以本行改按**几何**落表（[MoonOpBudget.horizonBandFillGeometry] 会真算上下越界裁剪），
     * ⛔ 不再引用 `HORIZON_K` 当系数 —— 探针的 `op` 口径在这一行**低于**实际光栅化面积，
     * 拿它当"实测上界"会让门守一个不存在的帧（D10 的同类，只是方向相反：不是记小值，是**记小了口径**）。
     */
    HORIZON_BAND("地平带", 1, 1, 1, 0.160, 0.160, 0.160),

    /**
     * 水面光柱倒影（§7.2）—— ⛔ **不是**镜像切片数。
     * 提交数 = `1 柱头 + NFOG(1/2/3)` = `2 / 3 / 4`（初版的 `4/8/14` 随 14 段镜像模型一起废弃）。
     * ⭐ 填充 `0.074 / 0.204 / 0.379` 是**定稿重做的直接产物**：真实模型是
     * **盖满整条光带的大椭圆**，初版按 14 个小切片估出 0.01/0.02/0.03 ⇒ 失真 10 倍，
     * 也正是 §9.2 那次"越上限"的**主要来源**（三项合计 0.05 → 0.385）。
     * ⚠️ **口径 = 原型自己的记账** `TAU/4·rx·ry·4 / (W·H)` = **`2π·rx·ry`**（真椭圆面积 `π·rx·ry` 的
     * **两倍**，与 [GLITTER] 的 `len·hh` 包围盒同一类保守化，⛔ 不是笔误）；1080p 固定构图下
     * 逐颗解析值 = 柱头 `0.005248` + 雾片 `0.068134 / 0.129779 / 0.175578` ⇒ 累计
     * **`0.073383 / 0.203162 / 0.378740`**，且**与画幅尺度无关**（每一项都是 `minDim·seaH / (W·H)`）。
     * ⚠️ 旧表 `0.073 / 0.203` 是探针打印值的**就近取整**，比解析几何各低 `0.0004 / 0.0002`
     * ⇒ 表又落到实测以下（与 [GLITTER] 的 MED 同一处分量级错，T8d 一起改；
     * 判据 `⑤ 光柱提交与填充 == REFLECTION 行` 逐颗重算，⛔ 不抄数）。
     */
    REFLECTION("光柱倒影", 2, 3, 4, 0.074, 0.204, 0.379),

    /**
     * 粼光划（§7.3）—— ⛔ **实测 43 / 92 / 137，不是名义 `ROWS×PER = 60×5 = 300`**。
     *
     * 这三数是**行结构**（`per = fy<0.28 ? 1 : fy<0.62 ? 2 : PER`）在 `ROWS = 24/46/60` 上的
     * 精确求和（LOW `11+14+18` / MED `22+26+44` / HIGH `28+34+75`）。
     * ⚠️ 文档 §7.3 结论 1 原文那句「MED 的 `21+13+12` 行 ⇒ `21+26+48 ≈ 95` 条」是**拆账抄错**
     * （真实行数 `22/13/11` ⇒ 条数 `22+26+44 = 92`），表值 92 没错（偏差 **D27**）。
     * ⛔ **记账必须按结构/实测，不能按名义**：把名义 300 落表，HIGH 合计直接到 `235 − 137 + 300 = 398 > 320`
     * ⇒ 门会红，而红的是**账**、不是画面（负向自证 `G4 负向自证 粼光按名义条数落表则爆表` 钉这条）。
     *
     * ⭐ 填充 `0.039 / 0.088 / 0.137` 按 `len·hh` **包围盒**口径（≈ 真面积 ×1.27，与 E43 的表**可比**），
     * 取的是**最坏帧**（`f = 1`、`occl = 0`、`aTreb = 1` ⇒ `glitA = 0.775`，此时**没有一条划被
     * `α < 0.004` 剔掉** —— 在场条数正好等于上面的结构值，两个口径在同一帧对齐）：
     * 沿时间扫 1000 s（步长 0.25 s、4001 帧）得 `max 0.038962 / 0.087361 / 0.136744`、
     * `mean 0.0335 / 0.0793 / 0.1256`。⚠️ 旧表 `0.035 / 0.080 / 0.129` 是**均值口径**（偏差 **D28**）
     * ⇒ 表必须是最坏帧，否则门在守一个"平均帧"（与 [MoonOpItem.TERMINATOR] 按最坏相位落表同一条口径）。
     * ⚠️ **表按 3 位向上取整，⛔ 不是就近取整**：`0.087361 → 0.088`（就近会得到 `0.087`，
     * 而 `0.087` **低于**实测最坏帧 ⇒ G4「表是上界」当场作废）。这一条是 T8d 落
     * `MoonlitWaterTest ③ 粼光填充 = 生产几何最坏帧` 时显出来的，前两档本来就够（`0.038962 ≤ 0.039`、
     * `0.136744 ≤ 0.137`）。
     * ⭐ 这两个数由 [MoonWater.MoonGlitterFrame] 的**生产几何**算出，`MoonlitWaterTest` 逐条重算并
     * 与本表对账 ⇒ 本行不再是手抄数（D10 / D24 那类"表与几何各说各话"就是这么防的）。
     * ⚠️ **本表的口径是「`hh` 的 `1.2·density` 地板不触发」那一区间**（`density ≤ 1.5`，含验收机
     * 1920×1080 / density 240 ⇒ `OVERDRAW_MAX_*` 三档实测全部来自这台）。地板**会**在小画幅高密度屏上
     * 触发（偏差 **D29**）；下表由原型探针在**同一套几何**上扫 1000 s（步长 0.25 s、4001 帧）取最坏帧，
     * ⛔ 不是手写第二份口径 —— `MoonlitWaterTest` 会按 [MoonWater.MoonGlitterFrame] 的生产几何
     * 把 `density` 1 / 1.5 / 2 / 3 四档重算并与这张表对账：
     * | 画幅 × density | LOW | MED | HIGH |
     * |---|---|---|---|
     * | `≤ 1.5`（900p / 1080p / 1440p / 2160p 四档**逐位相同**） | 0.038962 | 0.087361 | 0.136744 |
     * | 1080p × 2 | 0.038981 | 0.087397 | 0.136795 |
     * | 900p × 2 | 0.039142 | 0.087689 | 0.137203 |
     * | 1080p × 3 | 0.039533 | 0.088381 | 0.138179 |
     * | 900p × 3（**网格最坏**） | 0.040063 | 0.089332 | 0.139536 |
     * 触发条件写得出来：最薄一行的自然厚 `0.002079·minDim`（900p `1.87` / 1080p `2.25` / 2160p `4.49`），
     * 地板 `1.2·density` ⇒ 只有 **`density > 0.00173·minDim`**（1080p 要 `density ≥ 2`；
     * 2160p 要 `density > 3.7`，实际不会）才顶到地板。**所以本表不能按最坏 density 落**：
     * 地板是**线性**的，`density → ∞` 时填充无上界，把它抄进常量表等于宣布这一行没有口径
     * （与 D26/D28 不同 —— 那两条是**口径写错**，本条是**表的参照系必须写明**）。
     * ⛔ 也不要为了"表 ≥ 一切设备"去改 `1.2` 地板：它是"最薄的划别细到消失"的观感底线（§7.3）。
     * ⭐ 处置：表守 `density ≤ 1.5` 参照系，**高密度那一截由 `MoonlitWaterTest` 的
     * 「高密度增量必须留在档位余量内」按生产几何重算并对着 `OVERDRAW_MAX_*` / [fillRatchetMax] 判**
     * （网格最坏 900p × 3 的增量 LOW `+0.0011` / MED `+0.0013` / HIGH `+0.0025`，而三档**换成该
     * 网格值重算合计**后的余量为 `0.047 / 0.049 / 0.273` ⇒ 门守的是**合计**，合计有余量就不必让每一行都记最坏设备）。
     * 与 `G4 填充与画幅尺度无关` 同口径：那条守**尺度**不变，这条守**密度**。
     */
    GLITTER("粼光", 43, 92, 137, 0.039, 0.088, 0.137),

    /**
     * 水面涟漪（§7.5）—— **节拍条件项**：无拍 ⇒ 0。
     *
     * ⛔ **MEDIUM 是 2 个 op，不是初版写的 `≤1`**（偏差 **D17**，`passcost` 半遮态实测）。
     * 填 1 会让 G4 的输入**低于**最坏帧实测 ⇒ 门写成下界。
     *
     * ⭐ **填充 `0.073 / 0.146` 是生产几何值，⛔ 不是探针的 `0.017 / 0.049`**（偏差 **D34** 的
     * 第二笔，也是 **D26 / D28** 那条错法的第三次犯）：原型 `passcost` 读回的是**典型帧**
     * （几环诞生于不同拍 ⇒ 同场环的半径互不相同），而本表口径是**最坏帧**（每一槽都在
     * 最大可见半径）。反解由 [MoonWater.rippleFillFraction] 给：
     * `rr = rippleR(moonR, ripplePMax())` ⇒ `pMax = 0.9312`（[MoonClouds.MIN_ALPHA] 早退点，
     * ⛔ 不是 `p = 1`，那一段一个像素都不铺）→ 单环 `2π·rx·ry = 0.036350` 屏 @1080p
     * → MED ×2 = `0.072700` / HIGH ×4 = `0.145400`，向上取整 3 位 = `0.073 / 0.146`。
     * ⚠️ 记账口径与 [REFLECTION] / [GLITTER] 同一条：椭圆按 `2π·rx·ry`（真面积的**两倍**）保守化。
     * ⭐ 这一行因此**不再是手抄数** —— `MoonlitAudioTest ⑭` 直接用 [MoonWater.rippleFillFraction]
     * 的生产几何逐档重算并与表对账（判据含"按探针口径落表 ⇒ 低于几何上界"的负向自证）。
     */
    RIPPLE("涟漪", 0, 2, 4, 0.000, 0.073, 0.146),

    /**
     * `postFx` 暗角（§3.2 层 7）：一次全屏 `drawRect` ⇒ ⛔ **填充必须记 `1.000`，不能记 0**
     * （E43 `SeaOpItem.POST_FX` 的 KDoc 把记 0 明确定性为"记账漏洞"）。
     *
     * ⚠️ `fillLow = 0.0` 是**事实**而非让步：`RendererFx.applyPostFx` 只在
     * `fx.level != FxLevel.OFF` 时施加，而 `MoonLevel.LOW ⟺ FxLevel.OFF` ⇒ LOW 档根本没画。
     * ⛔ 颗粒刻意不开（§9.4），成本见 [MoonOpBudget.GRAIN_EXTRA_FILL]。
     */
    POST_FX("postFx", 0, 1, 1, 0.0, MOON_POST_FILL, MOON_POST_FILL),

    /**
     * 基类洼地合成器（`RendererFx.kt:54`）—— LOW 无全屏后处理 ⇒ 基类补**一次全屏 `drawRect`**。
     *
     * ⚠️ **探针读不到它**（它在基类里，不在原型的 `fillBy` 里），所以 §9.2 的 LOW 合计
     * 必须**手工 +1.00**；这一行就是那 +1.00 的落账处 ⇒ ⛔ **不要**因为"实测没有"而删掉，
     * 删了 LOW 就从 `3.34` 掉回 `2.34`，门在守一个不存在的帧。
     * 与 E43 的差异：E43 的表里**没有**这一行（它的 LOW 合计 1.72 是"未含 coalescer"的口径）。
     */
    DAMAGE_COALESCER("洼地合成", 1, 0, 0, MOON_POST_FILL, 0.0, 0.0);

}

/**
 * ⭐ G4 三张门（提交 / 填充 / native 堆），把 §九 的预算变成**构建期失败**。
 *
 * ## 为什么是纯函数而不是源码扫描（§10.2）
 * 渲染器字段初始化就会建 `Path`/`Paint`/`Bitmap`，⛔ 在纯 JVM 里根本无法 `new`；
 * 源码抓取既测不出真实提交数、又会被注释与格式骗过 ⇒ 唯一可行形态是
 * **把计数做成纯函数**：渲染层按同一张表实现，本门禁按同一张表算，两边对不上就是回归。
 * 而"两边对不上"的另一半靠 `MoonDiskBakeTest` 的源码扫描（G6/G7/G8）与
 * [MoonOpBudget.nativePxBudget] 直接调 [MoonDiskBake.texRadius] 兜住 —— ⛔ **故意不复制常量**。
 *
 * ## 硬约束
 * 1. ⛔ **零 Android import、零 Compose import**（否则 JVM 单测 `not mocked`）。
 * 2. ⛔ **零 `Random`**、每帧路径**零分配**：唯一的表是枚举，函数体里只有 `while` 与标量累加。
 * 3. ⛔ **不要指望 `LowTierElementBudgetTest`** —— 它硬编码只扫 Galaxy / Constellation /
 *    LyricsDotMatrix，对本效果**零覆盖**（E43 `SeasideOpBudget` 的头注已记同一件事）。
 */
internal object MoonOpBudget {

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·① 提交预算
    // ══════════════════════════════════════════════════════════════════════════

    /** **G4 ops**：LOW 档每帧提交上限（与 E43 同值，`SeasideOpBudget.OPS_MAX_LOW`）。 */
    const val OPS_MAX_LOW = 90

    /**
     * MED 档每帧提交上限（与 E43 同值）。
     *
     * ⚠️ 2026-10-09 半遮态补测后**变紧**了：实测 MED `159` ⇒ 只剩 **41** 个 op 的空间
     * （加第 4 片近层云 = `+11` 勉强够，再加就顶到 200）。所有者本次**只裁了填充**，
     * `OPS_MAX_*` 三值一个都没动。
     */
    const val OPS_MAX_MEDIUM = 200

    /** HIGH 档每帧提交上限（与 E43 同值，实测 229 ⇒ 余量 1.40×）。 */
    const val OPS_MAX_HIGH = 320

    /**
     * 粼光的**名义**条数上限（`ROWS 60 × PER 5 = 300`），⛔ **不是**表里用的实测值 `137`。
     *
     * 只给负向自证用：证明"把名义上限当实测落表 ⇒ 门立刻红"，
     * 也就是 §9.1 那句「⛔ 记账必须按实测，名义值会把预算表带偏」的机器化。
     */
    const val NOMINAL_GLITTER_OPS_HIGH = 300

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·② 填充预算（单位「屏」，`w·h` = 1 屏）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * **G4 fill** LOW 上限 = **3.45 屏**。
     *
     * ⭐ 2026-10-09 所有者裁决（Q4/Q8/Q9）：「**将上限值调整合适，保证 HIGH 能体现所有效果，
     * 实际上 HIGH 不应该有上限**」⇒ 旧的 `3.30 / 3.60 / 3.50` 作废（含 `MAX_HIGH < MAX_MED`
     * 那个符号错，Q9）。推导：半遮态实测 `3.263` **+ 约 0.19 余量**。
     * 表内余量 `3.45 − 3.40804 = 0.042`。⚠️ D26 / D28 之前这里是 `0.11`、T9 之前是 `0.048` ——
     * 地平带从 `0.102` 补正到 `0.160`、粼光填充从均值口径改到最坏帧口径、`HALO` 再乘上
     * `bass` 的 `k²`（**D34**），三笔一起吃掉了大半余量。
     * 表**高出**实测那一截是条件项按上界落表（`TERMINATOR` / `CLOUD_RIM`
     * / `RIPPLE`，满月无拍时为 0），方向由 `G4 表是半遮态实测的上界` 守。
     */
    const val OVERDRAW_MAX_LOW = 3.45f

    /**
     * **G4 fill** MED 上限 = **4.00 屏**。
     *
     * ⭐ 2026-10-10 所有者裁决（T9 越限上报后的处置，原话「**抬 MED 上限到 4.00**」）：
     * §八 接上 `bass` 之后，MED 表里同时进两笔几何账 —— `HALO` `+0.010`（`k²`）与
     * `RIPPLE` `+0.056`（探针典型帧 `0.017` → 生产几何最坏帧 `0.073`，**D34**）——
     * 合计从 `3.90004` 涨到 `3.96604`，**越过**原 `3.95`。
     * ⛔ 按既有红线（[MoonOpBudget] 类头第 2 条：表是上界 ⇒ 表错了就**往上改表**，
     * ⛔ 不许为了绿而放松门）：这里动的**不是** `RIPPLE` / `HALO` 两行的数（它们由生产几何
     * 反解，往下砍等于把门写成下界），也不是任何观感参数 ⇒ 只动上限，且只抬 `0.05`，
     * 余量仍比 LOW 更紧（`4.00 − 3.96604 = 0.034`）。
     * ⚠️ LOW / HIGH 两个上限**一个都没动**：LOW 不受这两笔影响（`RIPPLE` LOW = 0、
     * `HALO` 只涨 `+0.006`），HIGH 本来就没有绝对上限（裁决原文）。
     */
    const val OVERDRAW_MAX_MEDIUM = 4.00f

    /**
     * ⛔ **HIGH 档没有绝对填充上限** —— 所有者 2026-10-09 裁决原文：
     * 「**将上限值调整合适，保证 HIGH 能体现所有效果，实际上 high 不应该有上限**」。
     *
     * ⚠️ **「不设上限」≠「没有判据」**。HIGH 侧改跑两条**真实**断言（见
     * [overdrawMax] 的用法与 `MoonOpBudgetTest`）：
     * 1. **单调方向**：`fill(HIGH) ≥ fill(MEDIUM) ≥ fill(LOW)` —— HIGH 必须仍是铺得最满的一档
     *    （防"高画质反而偷工"；它接替的正是被作废的 `MAX_HIGH` 想表达的东西）。
     * 2. **棘轮**：`fill(HIGH) ≤ [FILL_REF_HIGH] × [FILL_RATCHET]` —— 见那两个常量的 KDoc。
     *
     * ⛔ **不要用一个大数字冒充"无上限"**：写 `Float.MAX_VALUE` 会让 [overdrawMax] 在 HIGH
     * 变成常量真的无穷、单测里的"超限"分支永不可达。这里用 `POSITIVE_INFINITY` 并让
     * `MoonOpBudgetTest` ⛔ **断言 HIGH 走的是棘轮而不是绝对门**（`overdrawMax(HIGH)` 必须无穷、
     * `fillRatchetMax()` 必须有限）—— 判据的存在性由那条断言守住。
     */
    const val OVERDRAW_MAX_HIGH: Float = Float.POSITIVE_INFINITY

    /**
     * HIGH 档棘轮的**实测参考值**（半遮态 `passcost` 70 s / 140 帧采样的 `fillMax = 4.306`）。
     *
     * ⚠️ 它**不是**画质天花板：要超出只需把这一行常量抬上去，但 ⛔ **必须写理由**（改动即评审）。
     * 它的唯一职责是拦**静默增长** —— 记账失控恰恰是"没人打算改观感、但每行多算了一点"的时刻。
     */
    const val FILL_REF_HIGH = 4.306f

    /** 棘轮容差（`×1.10`）：给逐帧抖动留 10%，⛔ 再宽就等于没有门。 */
    const val FILL_RATCHET = 1.10f

    /**
     * 若开颗粒需要**多付**的填充（第二次全屏 `drawRect`，§9.4）。
     *
     * 只给负向自证：`4.576 + 1.00 = 5.576 > 棘轮 4.736` ⇒ §9.4 那条「颗粒刻意不开」
     * 有机器依据，而不只是一句注释。⛔ 本效果 `postFx.grain = 0f`。
     */
    const val GRAIN_EXTRA_FILL = 1.00

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·③ native 堆预算
    // ══════════════════════════════════════════════════════════════════════════

    /** 常驻 native 堆的 ARGB_8888 像素总数上限（与 E43 同值 `SeasideOpBudget.NATIVE_PX_MAX`）。 */
    const val NATIVE_PX_MAX = 3_000_000

    /**
     * 门的**适用区间**：16:9 画幅长边 ≤ [NATIVE_PX_OK_MAX_LONG_EDGE]。
     *
     * ⚠️ 这不是妥协，是**实测边界**：`Id.STARFIELD` 是**全屏**纹理（`ProceduralTexture.kt:199`
     * `ensureFullscreen(Id.STARFIELD, w, h, …)`，⛔ 没有降采样），`1920×1080 = 2,073,600` 单项
     * 就吃掉 69% 的预算 ⇒ `2560×1440` 时**光 STARFIELD 就 3,686,400 > 3,000,000**。
     * §9.3 原文只写了「4K 必然突破」，⛔ **真实破点是 1440p**（偏差 **D18**）。
     */
    const val NATIVE_PX_OK_MAX_LONG_EDGE = 1920

    /** 门**破掉**的那一档 16:9 长边（1440p），给"破点哨兵"断言用（见 [NATIVE_PX_OK_MAX_LONG_EDGE]）。 */
    const val NATIVE_PX_BREAK_LONG_EDGE = 2560

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·④ 半遮态实测锚（⛔ 表必须 ≥ 这些数，否则门在守一个不存在的帧）
    // ══════════════════════════════════════════════════════════════════════════

    /** 实测提交数（`passcost` 的 `opsMax`，LOW/MED/HIGH）。 */
    const val MEASURED_OPS_LOW = 74
    const val MEASURED_OPS_MEDIUM = 159
    const val MEASURED_OPS_HIGH = 229

    /**
     * 实测**每帧**填充（LOW 已含探针读不到的 [MoonOpItem.DAMAGE_COALESCER] `+1.00`）。
     * 原始探针值 `2.263 / 3.759 / 4.306`，LOW 的 `+1.00` 落在那一行里 ⇒ `3.263 / 3.759 / 4.306`。
     *
     * ⚠️ 这三数是**探针账本的原样**，其中地平带那行按 `0.16 × HORIZON_K = 0.102` 记账，
     * 比矩形真实光栅化面积少 `0.058`（偏差 D26）⇒ 本表合计因此**必然高出实测 0.058**。
     * ⛔ 不要"顺手"把实测也抬 0.058 去找齐 —— 实测锚的价值在于它是**当时那台探针 printed 的数**，
     * 改了就再没有东西能发现账本与几何分家。
     */
    const val MEASURED_FILL_LOW = 3.263f
    const val MEASURED_FILL_MEDIUM = 3.759f
    const val MEASURED_FILL_HIGH = 4.306f

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·① 提交预算
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 该档位的**每帧提交数上界**（条件项按上界计入 ⇒ 返回的是最坏帧，不是典型帧）。
     *
     * ⛔ **没有 `waveCount` 入参**（E43 有）：本效果没有"逐浪"元素，逐档差异全部已在
     * [MoonOpItem] 的三列里，乘任何东西都是错的。
     */
    fun estimate(level: MoonLevel): Int {
        var n = 0
        var i = 0
        val items = MoonOpItem.entries
        while (i < items.size) {
            n += items[i].opsOf(level)
            i++
        }
        return n
    }

    /** [estimate] 的对应档位上限（供门禁与报告共用，⛔ 避免两处各写一份数字）。 */
    fun opsMax(level: MoonLevel): Int = when (level) {
        MoonLevel.LOW -> OPS_MAX_LOW
        MoonLevel.MEDIUM -> OPS_MAX_MEDIUM
        MoonLevel.HIGH -> OPS_MAX_HIGH
    }

    /** 逐元素提交数（单测用它逐行核对 §9.1 的系数表）。 */
    fun opsOf(item: MoonOpItem, level: MoonLevel): Int = item.opsOf(level)

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·② 填充预算
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 该档位的**每帧填充量**，单位「屏」。
     *
     * ⚠️ 返回值**与 `w`/`h` 的尺度无关**（过绘制率是尺度不变量），`w`/`h` 只做入参校验；
     * ⛔ 但与画幅**比例**有关（见 [MoonOpItem] 的 KDoc 第 4 条，本表按 16:9 定）。
     */
    fun overdrawEstimate(w: Float, h: Float, level: MoonLevel): Float {
        // ⛔ 刻意不带 message：那是每次调用一次的字符串模板分配，而本函数要能挂遥测。
        require(w > 0f && h > 0f)
        var acc = 0.0
        var i = 0
        val items = MoonOpItem.entries
        while (i < items.size) {
            acc += fillOf(items[i], level)
            i++
        }
        return acc.toFloat()
    }

    /**
     * [overdrawEstimate] 的对应档位上限。⛔ HIGH 恒为 `+[Float.POSITIVE_INFINITY]`
     * （裁决 Q4/Q8/Q9）—— HIGH 的判据在 [fillRatchetMax] 与单调方向断言里，⛔ 不在这里。
     */
    fun overdrawMax(level: MoonLevel): Float = when (level) {
        MoonLevel.LOW -> OVERDRAW_MAX_LOW
        MoonLevel.MEDIUM -> OVERDRAW_MAX_MEDIUM
        MoonLevel.HIGH -> OVERDRAW_MAX_HIGH
    }

    /** HIGH 档棘轮的天花板 = [FILL_REF_HIGH] × [FILL_RATCHET]。 */
    fun fillRatchetMax(): Float = FILL_REF_HIGH * FILL_RATCHET

    /** 逐元素填充量（[overdrawEstimate] 的单项版本，单位「屏」）。 */
    fun fillOf(item: MoonOpItem, level: MoonLevel): Double = item.fillOf(level)

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·③ native 堆
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 常驻 **native 堆**的 ARGB_8888 位图像素总数（§9.3）。逐项：
     * | 位图 | 尺寸 | 计 |
     * |---|---|---|
     * | `moon.jpg` 解码源图 | [MoonDiskBake.SRC_W] × [MoonDiskBake.SRC_H] = 524,288 | ✔ |
     * | 圆盘位图 | `[diskSidePx]²`，边长 = `2 × `[MoonDiskBake.texRadius] | ✔ |
     * | `Id.STARFIELD`（**全屏**、跨效果共享） | `w × h` | ✔ |
     * | `Id.GRAIN ×8` | ⛔ **不分配**（§9.4 颗粒刻意不开，`postFx.grain = 0f`） | 0 |
     *
     * ⭐ 圆盘边长**直接调 [MoonDiskBake.texRadius]**，⛔ 不在这里复制 `TEX_SCALE`/`TEX_R_MIN`/
     * `TEX_R_MAX` 三个常数 —— 这是 §9.3 那条对策（"`DISK_TEX_R` 按屏比例而不是绝对值"）的
     * **防漂移**写法：烘焙层改了钳位，门禁的算术跟着变。
     * ⚠️ 「只分配这两张位图」的**镜像**门禁是源码扫描，已在 `MoonDiskBakeTest` 的 G8，⛔ 不重复。
     */
    fun nativePxBudget(w: Float, h: Float): Int =
        nativePxBudgetWithDiskSide(w, h, diskSidePx(min(w, h)))

    /**
     * [nativePxBudget] 的**可注入圆盘边长**版本 —— ⛔ 这就是负向自证的接缝：
     * 喂 `2 × minDim`（按整屏方图烘，⛔ 错）⇒ 必然突破 [NATIVE_PX_MAX]；
     * 喂 [diskSidePx]（规格口径）⇒ 在 1080p 达标。
     */
    fun nativePxBudgetWithDiskSide(w: Float, h: Float, diskSidePx: Int): Int {
        require(w > 0f && h > 0f)
        require(diskSidePx >= 0)
        val src = (MoonDiskBake.SRC_W * MoonDiskBake.SRC_H).toDouble()
        val disk = (diskSidePx.toLong() * diskSidePx.toLong()).toDouble()
        val starfield = w.toDouble() * h.toDouble()
        return (src + disk + starfield).toInt()
    }

    /**
     * 圆盘位图**边长**（像素）：`2 × texRadius(minDim × MOON_R_K)`。
     *
     * ⚠️ 与 [MoonlitRenderer] 的实测一致：`1080p → 406`（`moonR = 162`、`texR = 203`）、
     * `4K → 810`（`texR` 被 [MoonDiskBake.TEX_R_MAX] 钳到 405）。
     * `MOON_R_K` ⛔ 直接引用渲染器的常量（`const val` 编译期内联 ⇒ 纯 JVM 安全、零类加载）。
     */
    fun diskSidePx(minDim: Float): Int =
        MoonDiskBake.texRadius(minDim * MoonlitRenderer.MOON_R_K) * 2

    /** `Id.STARFIELD` 那一张全屏纹理的像素（单独暴露给"破点在 1440p"那条哨兵）。 */
    fun starfieldPx(w: Float, h: Float): Long = (w.toLong() * h.toLong())

    /**
     * 源图（等距圆柱）像素数 —— [nativePxBudget] 的第一项。
     *
     * ⚠️ 解码失败时 [MoonDiskBake.synthesizeFallback] 用的是 `1/4` 图（256×128），
     * ⛔ **不额外计入**本门：它复用同一块 `IntArray`，计入就是把 524,288 记两遍。
     */
    fun srcPx(): Int = MoonDiskBake.SRC_W * MoonDiskBake.SRC_H

    // ══════════════════════════════════════════════════════════════════════════
    //  换算辅助
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 月盘填充的**几何式**（`π·(TEX_SCALE·MOON_R_K)²·(h/w)`，16:9 ⇒ `0.0621`）。
     *
     * 表里存的是实测常量 [MOON_DISK_FILL]（`0.062`），本函数只用于单测**对照**：
     * 两者必须在 1e-3 内吻合 ⇒ 渲染器改了 `MOON_R_K` 或 `TEX_SCALE`，这里就会显形。
     */
    fun diskFillGeometry(aspectWOverH: Double = 16.0 / 9.0): Double {
        val rOverH = MoonDiskBake.TEX_SCALE.toDouble() * MoonlitRenderer.MOON_R_K.toDouble()
        return PI * rOverH * rOverH / aspectWOverH
    }

    /**
     * 星野裁剪后的填充占比（= `HORIZON_K`，几何恒等式）。
     * ⛔ 表里同样存 [MOON_HORIZON_FILL]，本函数给单测做漂移对照。
     */
    fun starsFillGeometry(): Double = MoonlitRenderer.HORIZON_K.toDouble()

    /**
     * 地平带填充的几何式（§7.6）：带高 `0.16·h`、**通铺全宽** ⇒ 恰好 `0.16` 屏。
     *
     * ⛔ **不乘 `HORIZON_K`**（初版这么写、探针也这么记账，见偏差 D26）：这条压暗带横跨地平线
     * **两侧**（上沿 [MOON_BAND_ABOVE_K] 天空、下沿 [MOON_BAND_BELOW_K] 海面），
     * 乘占比等于把海面那一份抹掉。
     *
     * 本函数仍做**越界裁剪**：地平线挪动到带子出屏时，真实光栅化面积会小于 `0.16`，
     * 这里就得跟着变小 ⇒ [MoonlitRenderer.HORIZON_K] 一改，这一行立刻显形。
     */
    fun horizonBandFillGeometry(
        horizonK: Float = MoonlitRenderer.HORIZON_K,
    ): Double {
        val top = (horizonK - MOON_BAND_ABOVE_K).coerceAtLeast(0.0)
        val bottom = (horizonK + MOON_BAND_BELOW_K).coerceAtMost(1.0)
        return (bottom - top).coerceAtLeast(0.0)
    }

    /** 地平带是否**完整**落在屏内（越界即 [horizonBandFillGeometry] 不再等于标称 `0.16`）。 */
    fun horizonBandFullyOnScreen(horizonK: Float = MoonlitRenderer.HORIZON_K): Boolean =
        horizonK - MOON_BAND_ABOVE_K >= 0.0 && horizonK + MOON_BAND_BELOW_K <= 1.0

    /**
     * 月晕填充的**含 bass 上界换算**（§八 + §9.2 [MoonOpItem.HALO] 行，偏差 D34）：
     * 中性实测 × `haloRadiusK(aBassMax)²`。
     *
     * ⭐ 面积按半径的**平方**收费，所以这一行的调制是**收费项**（§3.0(f) 第 3 条：改 α 免费、
     * 改几何收费）；`aBassMax` 默认取 **1.0**（连续上界），⛔ 不是最高桶的桶中心 `0.9375` ——
     * 后者是缓存粒度，把它当口径会让表随 `HALO_R_BUCKETS` 一起漂。
     * ⚠️ 平方的输入只有**外半径**：Kotlin 侧 `outer = moonR·kR·k` 且整圈渐变按同一个 `k` 缩放
     * （[MoonClouds.haloStop] 的换算里 `kR` 含 `k`），所以"内圈 `0.96·moonR` 不跟着涨"这条
     * 原型细节在 Compose 侧**不成立** ⇒ 面积就是干净的 `k²`（同 [diskFillGeometry] 的圆面积口径）。
     */
    fun haloFillAtMaxBass(fillNeutral: Double, aBassMax: Double = 1.0): Double {
        val k = MoonAudio.haloRadiusK(aBassMax)
        return fillNeutral * k * k
    }

    /**
     * 向上取整到 3 位（"屏"的记账精度）。
     *
     * ⭐ [MoonOpItem.HALO] / [MoonOpItem.GLITTER] 两行的表值都由它产生：表必须是**上界**，
     * 而就近取整会把落在 `x.xxx5` 之下的换算值（如 `0.074057 → 0.074`）**砍到实测以下**，
     * 门当场从"守最坏帧"退化成"守一个不存在的帧"（与 **D26 / D28** 同一处分量级错）。
     */
    fun ceilToMilli(value: Double): Double = Math.ceil(value * 1000.0) / 1000.0
}

/**
 * 档位取值（写成**文件级私有扩展**而不是枚举里的 `when`：
 * 枚举成员引用自身 companion 的成员会被编译器拒绝，E43 `SeasideOpBudget` 同因）。
 */
private fun MoonOpItem.opsOf(level: MoonLevel): Int = when (level) {
    MoonLevel.LOW -> opsLow
    MoonLevel.MEDIUM -> opsMed
    MoonLevel.HIGH -> opsHigh
}

private fun MoonOpItem.fillOf(level: MoonLevel): Double = when (level) {
    MoonLevel.LOW -> fillLow
    MoonLevel.MEDIUM -> fillMed
    MoonLevel.HIGH -> fillHigh
}

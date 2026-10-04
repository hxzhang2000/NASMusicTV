package com.nasmusic.tv.visualizer.renderers

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 画质档位 —— ⛔ **刻意不是** `androidx.tv.material3` 的 `FxLevel`、也不是 `Color`。
 *
 * 为什么自建（§4.9.5 的硬前提）：
 * - 本文件是 **G11 / G12 / G13 三道纯函数门**，`estimate` / `overdrawEstimate` /
 *   `nativePxBudget` 必须在**纯 JVM 单测**里直接调。带 Compose / material3 类型的签名在
 *   JVM 单测里会 `not mocked`（`FxLevel` 是 value class、`Color` 走 inline class 包装）。
 * - 渲染层做**一次性映射**即可：`FxLevel.OFF → [SeaLevel.LOW]`、
 *   `FxLevel.LITE → [SeaLevel.MEDIUM]`、`FxLevel.FULL → [SeaLevel.HIGH]`（§4.7②）。
 *
 * ⚠️ **顺序语义**：`[LOW] < [MEDIUM] < [HIGH]`，与 §4.7② 的「BASIC 三档全可见」一致。
 */
internal enum class SeaLevel {
    /** `FxLevel.OFF`。§4.7②：粗阶梯、跳过贴图泡沫 / 扰动前锋 / 白沫晕 / 蕾丝、飞沫关闭。 */
    LOW,

    /** `FxLevel.LITE`。§4.7②：精阶梯 + 外侧浪改粗阶梯、半密度、水体场分辨率 ×0.7。 */
    MEDIUM,

    /** `FxLevel.FULL`。§4.7②：全量 + `punchHoles`、全密度、全分辨率场。 */
    HIGH
}

/**
 * 逐帧绘制元素（§4.9.2 的 20 项 + 1 项补充行）—— **一个元素一项**。
 *
 * ⛔ **系数取 §4.9.2 表的「裁决后」那一列**，不是「原稿」那一列；[opsLegacy] / [fillLegacy]
 * 才是「原稿」那一列，**只用于负向自证**（`legacy = true`）。
 *
 * ## 每一行两组数是什么
 * | 组 | 含义 | 喂给 |
 * |---|---|---|
 * | `opsLow/Med/High` | 该元素在该档位的**每帧提交数**（draw call 数） | [SeasideOpBudget.estimate] |
 * | `fillLow/Med/High` | 该元素在该档位的**每帧填充量，单位「屏」**（`w·h` = 1 屏，**已含该元素自己的全部提交**） | [SeasideOpBudget.overdrawEstimate] |
 * | `opsLegacy` / `fillLegacy` | 「改造前」形态的同两项 | 负向自证 |
 *
 * ## 「屏」是无量纲的
 * 所有 `fill*` 都是**屏幕面积的比例** ⇒ [SeasideOpBudget.overdrawEstimate] 的返回值
 * **与 `w`/`h` 无关**（过绘制率本来就是尺度不变量）。`w`/`h` 仍作为入参，是为了让
 * 「这一档在真机上是多大」可查，也让未来若某项需要绝对像素时有地方落。
 *
 * ## 逐项系数的来源（⛔ 改动前先读这三条）
 * 1. **全屏类三项**（干沙 blit / 底色渐变 / 水体场 blit）是**几何恒等式**，不是估计：
 *    `1 − SAND_TEX_TOP`、`SEA_BOTTOM_K`、`2 × SEA_BOTTOM_K`。
 * 2. **逐浪类**（浪体带 / 泡沫窄带 / 白沫晕 / 外海泡沫 / 蕾丝 / 贴岸唇 / 螃蟹）是
 *    **浪带足迹 `BAND = SWELL_BAND_W·(0.62 + 0.62·amp)` 的若干倍**，倍数的依据写在
 *    各行 KDoc 里（阶梯覆盖多少条带、白沫晕铺几条带、描边多细）。
 * 3. **逐点 / 逐段类**（飞沫 / 残沫 / 沙纹 / 岸线湿线 / 浪花手指）填充量与提交数
 *    **脱钩**：`drawPoints` / 一次 `drawLines` 提交的像素面积只与笔宽有关，与点数无关。
 *    ⚠️ 这正是 §4.9.2 把它们合批的收益所在：**提交数降 1~2 个量级，填充量几乎不变**。
 *
 * ## ⭐ 系数 = §4.9.2 的**合批裁决** × §5.3 的**原型元素计数**，两者各管一件事
 * `docs/seaside-preview.html` 已由所有者**确认定稿**。本门禁守的是**提交上限**，而提交上限
 * 只由「`style`+`color`+`strokeWidth` 的分组数」决定 ⇒ 与**元素个数**脱钩：
 * - **提交数 / `fill*`**：以 **§4.9.2 表的「裁决后」列**为准（合批后的形态）。
 * - **几何个数**（贴图块数 / pass 数 / 色数）：以 **§5.3 参数表**为准（原型定稿值），
 *   因为合批后**更多几何 = 零额外提交** ⇒ 没有理由为省提交而砍掉它。
 * ⚠️ 本轮「原型保真回补」就是按这条把 [SeaOpItem.OPEN_SEA_FOAM] 的块数从 8 放回 22、
 *   [SeaOpItem.DISTURBANCE] 整层复活、[SeaOpItem.RESIDUAL_STREAK] / [SeaOpItem.WET_LINE] /
 *   [SeaOpItem.PUDDLE] 放回多 pass / 双色 —— ⛔ 但**提交数仍必须留在 §4.9.2 的口径内**
 *   （合批手段只有三种：同一条 `Path` 里放多段 / 一支 `Paint` 上把同组几何一次画完 /
 *   同一次调用里多点）。
 */

// ══════════════════════════════════════════════════════════════════════════════
//  几何常量 —— ⛔ 必须是**顶层** const：枚举的构造参数里引用它自己 companion 的成员会被
//  编译器拒绝（"Companion object of enum class is uninitialized here"）。
//  顶层 const 在使用点被内联 ⇒ 仍然零运行期依赖、零初始化顺序问题。
//  ⛔ 数值逐项对齐 SeasideWaves；漂了就等于门禁在守另一个效果。
// ══════════════════════════════════════════════════════════════════════════════

/** 沙纹理起点（占 h）。见 [SeasideWaves.SAND_TEX_TOP]。 */
internal const val SEA_SAND_TEX_TOP = 0.46

/** 水体场 / 粼光网的裁剪下界（占 h）= `SHORE_K + 0.20` = 0.820。 */
internal const val SEA_SEA_BOTTOM_K = 0.820

/** 浪带宽度基数（占 h）。见 [SeasideWaves.SWELL_BAND_W]。 */
internal const val SEA_SWELL_BAND_W = 0.042

/** 浪完全涌上滩时水线高出平均岸线的距离（占 h）。见 [SeasideWaves.SWASH_REACH]。 */
internal const val SEA_SWASH_REACH = 0.105

/** ⛔ 浪带足迹（占 h）= `SWELL_BAND_W·(0.62 + 0.62·amp)`，此处取 `amp = 1` 的**上限**。
 * 逐浪类元素的填充都是它的若干倍 ⇒ **门禁取上界，不取典型值**。 */
internal const val SEA_BAND = SEA_SWELL_BAND_W * (0.62 + 0.62 * 1.0)

/** 干沙 blit 的填充 = `1 − SAND_TEX_TOP`（**几何恒等式**，⛔ 不是估计）。 */
internal const val SEA_SAND_FILL = 1.0 - SEA_SAND_TEX_TOP

/** 湿沙带高度（占 h）= `SWASH_REACH·1.10`（满程 `0.62 + 0.48·sLow` 的上限 1.10）。 */
internal const val SEA_WET_BAND = SEA_SWASH_REACH * 1.10

/** 岸线湿线的填充 ≈ `2px / h` @1080p ⇒ 0.00185；取 0.0025 留余量。 */
internal const val SEA_WET_LINE_FILL = 0.0025

/** 焦散笔触面积（占屏）。推导见 [SeaOpItem.CAUSTIC] 的 KDoc。 */
internal const val SEA_CAUSTIC_FILL = 0.05

internal enum class SeaOpItem(
    /** 中文元素名（单测报告用）。 */
    val label: String,
    /** ⛔ 是否**逐浪**（提交数与填充量都要乘 `waveCount`）。 */
    val perWave: Boolean,
    val opsLow: Int,
    val opsMed: Int,
    val opsHigh: Int,
    val opsLegacy: Int,
    val fillLow: Double,
    val fillMed: Double,
    val fillHigh: Double,
    val fillLegacy: Double
) {
    /** 干沙主体 blit（`drawSand`）：裁剪到 `SAND_TEX_TOP..h` 后 blit 烘好的 4 层纹理。1 次。 */
    SAND_BLIT("干沙 blit", false, 1, 1, 1, 1, SEA_SAND_FILL, SEA_SAND_FILL, SEA_SAND_FILL, SEA_SAND_FILL),

    /**
     * 湿沙（`drawWetWash`）—— §4.9.2 裁决：**97 个逐列小渐变 quad → 1 次提交**
     * （一条 `wetRegionPath` + 一个缓存的竖向渐变）。
     * ⛔ 改造前的 `cols + 1 = 97` 只在 [opsLegacy] 里，⛔ **不进** `opsHigh`。
     */
    WET_WASH("湿沙", false, 1, 1, 1, 97, SEA_WET_BAND, SEA_WET_BAND, SEA_WET_BAND, SEA_WET_BAND),

    /**
     * 镜面高光（`drawSheen`）—— **`opsLow/opsMed/opsHigh = 1`**（⛔ 因恢复第二次提交而变，原为 0）。
     *
     * ## ⭐ 为什么必须是**它自己那一次** `drawPath`（所有者裁决，推翻 §4.9.2 的「折成同一次」）
     * §4.9.2 判「湿沙 + 镜面高光合成 1 次提交」，本表的系数曾照此写成 `0 / 0 / 0`。
     * ⛔ **那在结构上做不到可见**：折成一条 path 之后，高光子轮廓**嵌套在湿区内部**，
     *   而 `Path` 的默认 **NonZero** 填充规则把它**并入**湿区 ⇒ 像素集与不加它时**逐像素相同**。
     *   原型之所以读得出镜面高光，靠的是 `globalCompositeOperation = 'lighter'` 的
     *   **第二次**提交 —— 没有第二次提交就没有任何「让高光单独变亮」的像素手段。
     * ⇒ 现裁决：恢复 [SeaOpItem.SHEEN] 自己的 1 次 `drawPath`，配 `BlendMode.Plus`
     *   （依赖舞台无条件挂载的 `CompositingStrategy.Offscreen`，见 `VisualizerStage`）。
     *
     * ## ⛔ 为什么 `fill*` 三档**仍然是 0**
     * 高光与湿沙**落在同一片区域**（湿区最上面的 `SHEEN_DEPTH_K·h` 那一条窄带）⇒
     * 第二次提交**不新增任何像素覆盖**，只是让那一带**变亮**。
     * G12 守的是**填充量**（过绘制率），不是亮度 ⇒ ⛔ 这里既不加 `SEA_WET_BAND`
     * 也不加任何小数；把亮度差记成填充会让 G12 凭空多算一整条湿沙带（×2 浪的 HIGH 就是
     * 0.116 屏的虚账）。
     *
     * ⚠️ **保留这一行、而不是删掉**，是为了让「改造前」的 97 次高光有地方落账 ——
     * 删掉它负向自证就会少 97 次提交，而那正是 §4.9.2 判定的头号超标项。
     * `opsLegacy = 97` 因此**不变**（负向自证靠它，见 `SeasideOpBudgetTest`）。
     * §4.7① 仍按「少于 4 列 `wetAmt > 0.05` ⇒ 高光跳过」门控**内容**，⛔ 但不改提交数。
     */
    SHEEN("镜面高光", false, 1, 1, 1, 97, 0.0, 0.0, 0.0, SEA_WET_BAND),

    /**
     * 水体场（`drawSeaField`）：**底色竖向渐变**（覆盖 `0..H·SEA_SEA_BOTTOM_K`）
     * + **低分辨率场 blit**（同样落到 `H·SEA_SEA_BOTTOM_K`，§14.3.7）。
     * §4.7②：`LOW` **只留底色渐变**，省掉那一次全屏 blit ⇒ LOW 1 次 / MED·HIGH 2 次。
     * 填充 = `SEA_SEA_BOTTOM_K`（渐变）与 `SEA_SEA_BOTTOM_K`（blit）之和。
     */
    SEA_FIELD("水体场", false, 1, 2, 2, 2, SEA_SEA_BOTTOM_K, 2 * SEA_SEA_BOTTOM_K, 2 * SEA_SEA_BOTTOM_K, 2 * SEA_SEA_BOTTOM_K),

    /**
     * 粼光网（`drawCausticNet`）—— §4.9.2 裁决：30 射线 + 56 亮结共 **86** 提交折成
     * **同 3 条 path 里的线段**；且「射线与亮结**仅 HIGH 档**」
     * （§4.7① 的 LOW 行漏了焦散，§4.9.2 已补「LOW = 0」）。
     *
     * ⛔ **MEDIUM 保留胞壁网**（D9 裁决修正）：本行原先把 MEDIUM 也整个砍成 `0`，
     * **比 §4.9.2 正文更严**——正文的原意是「**射线与亮结**仅 HIGH」、**胞壁网 MEDIUM 仍在**。
     * ⇒ `opsMed = 3`（胞壁网同样铺满 3 条 path 的线段，提交数不变）、
     *   `fillMed = [SEA_CAUSTIC_FILL]`。
     * ⚠️ **`fillMed` 必须与 `opsMed` 同口径**：若只放开 `opsMed` 而把 `fillMed` 留 0，
     *   G12 就会**低算** MEDIUM 的填充、把本轮加回去的 ~0.05 屏凭空吞掉。⛔ 不要那样做。
     * `opsLow` / `fillLow` 保持 **0**（LOW 确实不画焦散），`opsLegacy = 86` 不变。
     *
     * 填充 = 焦散胞壁网 + 30 根射线 + 56 颗亮结在海水区（`0.82` 屏）的实际笔触面积
     * ≈ `0.05` 屏（`331` 条胞壁边 ×≈81px ×1.5px + `30`×885px×1.5px + `56` 颗 r≈3px）。
     * ⚠️ MEDIUM 只画胞壁网 ⇒ 真实笔触略小于 `0.05`；此处**仍按 `[SEA_CAUSTIC_FILL]` 计**
     *   （门禁取上界，且与 `opsMed` 口径一致），是**偏保守**的方向。
     */
    CAUSTIC("焦散网", false, 0, 3, 3, 86, 0.0, SEA_CAUSTIC_FILL, SEA_CAUSTIC_FILL, SEA_CAUSTIC_FILL),

    /**
     * 浪体带（`drawSwellBody`）：迎光亮 + 背光暗，铺满整条浪带 ⇒ 填充 = 1.00 × SEA_BAND。
     * 「保持不变」（§4.9.2 末）：每条浪带 1 条 path，**合批改造不碰它**。
     */
    SWELL_BODY("浪体带", true, 1, 1, 1, 1, SEA_BAND, SEA_BAND, SEA_BAND, SEA_BAND),

    /**
     * 泡沫窄带（`fillStrip` 阶梯）—— §4.9.2 裁决：`wj` 量化后按**段数**提交。
     * §4.7①：HIGH/FULL 精阶梯 **14 段**；MEDIUM「外侧浪改粗阶梯」7 段；
     * LOW「粗阶梯」7 段。改造前是 **19 档**。
     * 填充：14 段铺满整条带（1.00 × SEA_BAND），7 段粗阶梯只覆盖外侧约 2/3（0.65 × SEA_BAND）。
     */
    FOAM_LADDER("泡沫窄带", true, 7, 7, 14, 19, 0.65 * SEA_BAND, 0.65 * SEA_BAND, SEA_BAND, 0.90 * SEA_BAND),

    /**
     * 波面大白沫晕（`drawSeaFoamWash`）：一条 path + 一条竖直渐变，分 8 段各按本段 `foamK`
     * 调 alpha ⇒ 填充 ≈ 0.9 × SEA_BAND。§4.7②：LOW **跳过**。
     */
    SEA_FOAM_WASH("白沫晕", true, 0, 1, 1, 1, 0.0, 0.90 * SEA_BAND, 0.90 * SEA_BAND, 0.90 * SEA_BAND),

    /**
     * 外海泡沫（`drawOpenSeaFoam`）—— **块数回到原型的 [SEA_FOAM_PATCH] = 22**（§5.3 行 888），
     * 但 22 块用 u-v 空间预烘轮廓合成**一条 path 一次 fill**（⛔ **不是** 22 次 `drawImage`）。
     * ⛔ §4.9.2 那句「22 → 8」针对的是**提交数**，⛔ 不是块数：合批后 22 块只是更多几何、
     *   **零额外提交** ⇒ 提交数仍 `1`，而块数回到 22 才是原型保真。
     * ⛔ **块数与 [SeaOpItem.DISTURBANCE] 严格分工**：`dep = hash·1.90 − 0.35` 的
     *   **拖尾侧** 22 块全归本行；前缘**前方**的扰动前锋只由 [SeaOpItem.DISTURBANCE] 画。
     * 填充 = 22 块的**并集**（外海侧半条带，≈0.5 × SEA_BAND）⛔ 不是 22 块之和 ——
     * 合成一条 path 正是为了拿掉 source-over 的重叠叠亮。
     * 改造前 22 次逐块 alpha blit，§4.9.2 记「62 次 alpha blit（最高 ~4 Mpx）」
     * ⇒ 单条浪分配到 `22/40` 那半 ≈ 1.60 屏（原型共 22 + 18 = 40 块）。
     */
    OPEN_SEA_FOAM("外海泡沫贴图", true, 0, 1, 1, 22, 0.0, 0.50 * SEA_BAND, 0.50 * SEA_BAND, 1.60),

    /**
     * 扰动前锋（`drawDisturbance`）—— ⛔ **因「原型保真回补」而从「全档删除」复活**：
     * 原型 §4.3.5 + §5.3 有完整一层 `DISTURB_*`（18 块贴图 blit，`dep = 0.30…1.45`
     * **前缘前方**），删掉它外海浪前方会退回成一段干净的静水。
     *
     * ⛔ **仍按批处理路径实现**：18 块贴图 ⇒ **一条 path 一次 fill**，
     *   ⛔ **绝不**恢复成 18 次 `drawImage`（那正是 §4.9.2 的头号超标项）。
     *   逐块的 alpha / 位置 / 尺寸 / 种子照抄原型，⛔ 只在**提交**上折成 1 次。
     *
     * 填充 = 18 块折成矢量后的**并集覆盖**，按与 [SeaOpItem.OPEN_SEA_FOAM] 同口径等比折算：
     * ```
     * 单块名义边长  OPEN_FOAM: W0·(2.40 + 2.40·h^1.2)  均值 3.49·W0
     *               DISTURB  : W0·1.35·(1.2 + 1.3·h^1.3) 均值 2.38·W0  ⇒ 面积比 0.465
     * 块数比 18 / 22 = 0.818
     * ⇒ 0.465 × 0.818 = 0.380 ⇒ 0.50 × SEA_BAND × 0.380 ≈ 0.19 × SEA_BAND
     * ```
     * ⚠️ 与 [SeaOpItem.OPEN_SEA_FOAM] 的 `fillHigh` 有少量重叠（扰动块的下缘探回浪带内），
     *   G12 取**保守**（宁可略高）。
     *
     * ⚠️ **仍然保留 [opsLegacy] = 18 / [fillLegacy] = 1.00** 供负向自证：门禁要能证明
     *   「注入 18 次逐块 blit ⇒ 门禁失败」，删掉就注入不了。
     * §4.7②：LOW 跳过贴图泡沫 ⇒ 本行 LOW 恒 0（[OPEN_SEA_FOAM] 同理）。
     */
    DISTURBANCE("扰动前锋", true, 0, 0, 1, 18, 0.0, 0.0, 0.19 * SEA_BAND, 1.00),

    /**
     * 泡沫蕾丝（`drawFoamLace`）—— §4.9.2 裁决：线宽逐边变化而 `Stroke.width` 不可变
     * ⇒ **`wj` 量化**、缓存 **12 个 `Stroke`** ⇒ 12 次批量 stroke（HIGH）/ 3（MED）/ 0（LOW）。
     * ⛔ **保留「端点共享」**——那才是它读作泡沫的原因，不是形状。
     * 填充：描边，≈0.2 × SEA_BAND（网眼很稀、线宽 ~1.2px）。
     */
    FOAM_LACE("蕾丝网", true, 0, 3, 12, 5, 0.0, 0.20 * SEA_BAND, 0.20 * SEA_BAND, 0.20 * SEA_BAND),

    /**
     * 锐利的破碎唇（`drawCrestLip`，窄高光 + 紧贴的暗带）—— §14.3.4：⛔ **只对非领头浪画**。
     *
     * ⛔ **因「原型保真回补」而变**：原先只取两个 pass 里**靠前的那一道**（pass 0，
     * `offset = LIP_W·0.35` / `width 0.9` / `α 0.85` / `#ffffff`）⇒ `0 / 0 / 1`。
     * 现在 **HIGH 恢复两个 pass**（pass 1 = `offset = LIP_W·0.95` / `width 2.5` /
     * `α 0.42` / `#cfe6ea`）⇒ `0 / 1 / 2`；**MEDIUM 开放 pass 0 那一次**（§4.7②
     * 「贴岸唇」只列在 HIGH/FULL 行的**泡沫层**里，但 §4.9.2 的裁决是**提交数**归零，
     * ⛔ 不等于「窄高光也一并消失」—— 高光是破碎感的唯一来源，MEDIUM 给回它）。
     * ⛔ **两道门都不放松**：仍只 HIGH 有第 2 pass、仍只对非领头浪。
     * 填充 ≈ 0.15 × SEA_BAND（只有 `CREST_HOLD` 那么宽）—— ⛔ **两个 pass 都改动**；
     * 第二次提交落在**同一条**窄带上，只是压暗，不新增覆盖。
     *
     * ⭐ **`fillMed` 由 `0.0` 补齐为 `0.15 × SEA_BAND`**（与 [fillHigh] 同值）：
     *   依据是 [SeaOpItem] 的既定契约——**`fill*` 必须与 `ops*` 同口径**。本行
     *   `opsMed = 1` 而 `fillMed = 0.0` ⇒ **G12 会低算 MEDIUM 的破碎唇**，属记账漏洞。
     *   [SeaOpItem.CAUSTIC] 那次「`opsMed` 放开、`fillMed` 一并补齐」已把这条契约确立，
     *   本行是同一类漏账的补齐，不是新口径。
     * ⚠️ MEDIUM 的**真实**覆盖比 HIGH 更窄（只有 0.9px 的 pass 0，而 HIGH 有 pass 0 + pass 1），
     *   逐档精细化本可给一个更小的系数；⛔ 但**同口径优先于逐档精细** ——
     *   `fillMed`/`fillHigh` 同值是**偏保守**的方向（高估 MEDIUM 一点），可接受。
     *   ⛔ 不要自己发明折算系数：`CAUSTIC` 那一行同样按 `[SEA_CAUSTIC_FILL]` 全值计（见其 KDoc）。
     * `fillLow` 保持 `0.0`（LOW 确实 `opsLow = 0`）。
     */
    CREST_LIP("贴岸唇", true, 0, 1, 2, 1, 0.0, 0.15 * SEA_BAND, 0.15 * SEA_BAND, 0.15 * SEA_BAND),

    /**
     * 浪花手指（`drawSwashFingers`）—— §4.9.2 裁决：30 根细线 **→ 1 次 `drawLines`**。
     * 填充：30 根细线，每根 ≈0.08·SEA_BAND²，合计 ≈2.4·SEA_BAND² ≈ 0.0065 屏（**与提交数脱钩**）。
     */
    SWASH_FINGER("浪花手指", false, 1, 1, 1, 14, 0.0065, 0.0065, 0.0065, 0.012),

    /**
     * 退水残沫（`drawResidualStreaks`，3 pass 羽状丝缕，⛔ 必须短）—— ⛔ **因「原型保真回补」
     * 而变**：`0.9 + 0.8·pass` 的线宽与 `A · (1 − 0.28·pass)` 的 alpha **三档各不相同**，
     * 折成一次提交就只剩 pass 0 ⇒ HIGH 恢复 **3 次 `drawLines`**（三档几何 / 线宽 / alpha
     * 全部照抄原型），MEDIUM / LOW 仍取 1 次（取 pass 0）。
     * ⛔ **残沫仍不得连成纹路**（§4.3.4 明文）—— 三 pass 的 `h = seg·3 + pass` 错开照旧。
     * 填充 ≈ 0.45 × 湿沙带（丝缕只占退水带的一部分）—— ⛔ **三档都不动**：
     * 三个 pass 是**同一区域**上的叠加，不新增任何覆盖。
     */
    RESIDUAL_STREAK("退水残沫", false, 1, 1, 3, 1, 0.45 * SEA_WET_BAND, 0.45 * SEA_WET_BAND, 0.45 * SEA_WET_BAND, 0.45 * SEA_WET_BAND),

    /**
     * 岸线细亮湿线（`drawWetLine`，原型 **2 pass**：`1.0 + 1.5·pass` 线宽、
     * `A · (1 − 0.42·pass) · boost` alpha、第 `pass` 遍整体下移 `1.6`px）——
     * ⛔ **因「原型保真回补」而变**：HIGH 恢复 **2 次 `drawLines`**（两 pass 的线宽、alpha、
     * 下移全部照抄），MEDIUM / LOW 仍取 1 次。
     * 填充 ≈ `2px / h` ≈ 0.0025 屏 —— ⛔ **三档都不动**（两次提交落在同一条线上）。
     */
    WET_LINE("岸线湿线", false, 1, 1, 2, 1, SEA_WET_LINE_FILL, SEA_WET_LINE_FILL, SEA_WET_LINE_FILL, SEA_WET_LINE_FILL),

    /**
     * 沙纹（`drawRipples`，26 条断段浅色调）—— §4.9.2 裁决：26×3 档共 **78** 提交
     * **合并为 3 次 `drawLines`**（MED/LOW 各 1 次）。填充与提交数脱钩 ≈ 0.004 屏。
     */
    SAND_GRAIN("沙纹", false, 1, 1, 3, 78, 0.004, 0.004, 0.004, 0.012),

    /**
     * 残沫（`drawResidue`：软阴影 → 亮芯 → 左上缘高光）—— §4.9.2 裁决：
     * `RESIDUE_MAX = 52` × 3 笔 = 156 提交 **→ 3 次 `drawPoints`**（3 个缓存 `FloatArray(104)`）。
     * ⛔ **不要烘成位图**——保留逐点 alpha 且零分配。
     */
    RESIDUE_POINTS("残沫", false, 3, 3, 3, 156, 0.001, 0.001, 0.001, 0.001),

    /**
     * 飞沫（`drawSplash`，`SPLASH_MAX = 180` 逐点）—— §4.9.2 裁决：
     * **→ 1 次 `drawPoints`**（缓存 `FloatArray(360)` + `Paint.Cap.ROUND`）。
     * §4.7②：**LOW 档飞沫关闭** ⇒ LOW 系数 0。
     */
    SPLASH_POINTS("飞沫", false, 0, 1, 1, 180, 0.0, 0.0005, 0.0005, 0.0005),

    /**
     * 螃蟹（滩上小蟹）—— §4.9.2 裁决：原型的 **16 次逐件提交**合并为 **8 次 `drawPath`**
     * （MEDIUM 4 次 / LOW 1 次）。
     *
     * ⭐ **为什么从「2 次 `drawLines`」回到 8 次**：原型的腿髋→膝是 `quadraticCurveTo`
     *   （`seaside-preview.html:2249`），壳体是 `ellipse(1.06R, 0.76R)` 的**填充**
     *   （`:2257-2260`），眼点是半径 `0.24R` 的**实心圆**（`:2322`）。
     *   `drawLines` 三样都表达不了 ⇒ 用「一支粗描边线段当跑道形壳 + 弦近似当腿 +
     *   零长线段当眼点」顶上，只用到 7 项调色板里的 **2** 项，`12×` 截图上读不出螃蟹。
     *   ⇒ 换回 `NativePath` + 真 `quadraticCurveTo`，7 项调色板全部可达。
     * ⛔ **8 次不是「删层删出来的」**：合批手段只有三种（同一条 `Path` 里放多段 / 一支
     *   `Paint` 上把同组几何一次画完 / 同一次调用里多点），八批与原型的 8 个
     *   `style+color+strokeWidth` 分组一一对应，⛔ 没有任何一层被丢掉。
     *
     * ⛔ **`perWave = false`**（D9 裁决修正）：螃蟹是**全局单实例**。`crabSlot(t)` 每帧只产出
     *   **一个**在场对象，且 §14.3.19 明确绘制顺序是「沙面覆层之后、`drawSwellBands` 之前」
     *   ⇒ 同参数画两次在 source-over 下**会叠亮**。按 `waveCount` 乘它是**高估**，
     *   且浪数越多偏得越远。本行原先标 `perWave = true`，渲染层 `drawCrab` 却每帧只调一次
     *   ⇒ 两边对不上（渲染层自己的 KDoc 已留痕「待裁决」）。现按裁决对齐。
     *
     * ⛔ **LOW 因「原型保真回补」由 1 → 2**（所有者裁决）：原先 LOW 只画 **④ 壳填充**，
     *   那是**单色**实心椭圆剪影 —— 原生 `Paint` 的**一次**提交表达不出「填充 + 更深壳沿」
     *   两种颜色（NonZero 下壳沿子轮廓并进填充区，像素集逐像素相同）。现 LOW = ④ + ⑥，
     *   与 HIGH 的第 ④⑥ 批完全一致（⑥ 在 LOW 下**只**含壳轮廓，⛔ 不含螯/眼柄）。
     *   ⚠️ LOW **仍不画**影子 / 腿 / 螯 / 眼：1× 下四条步足的髋距与线宽必然糊成一片，
     *   这是原型同款几何在 LOW 档的**固有**结果，不是可以再优化掉的偏差。
     * 其余数值不变：`opsLegacy = 35` / `fill* = 0.004`。
     */
    CRAB("螃蟹", false, 2, 4, 8, 35, 0.004, 0.004, 0.004, 0.004),

    /**
     * `postFx` —— §4.9.2 裁决：双通道 **2 全屏 → 只保留一个通道** `PostFx(vignette = 0.30f)`，
     * ⛔ 去掉 `grain`。提交数 **1**。
     *
     * ⚠️ **填充记 0，这是有意的**：晕影是**声明式**交给 `RendererFx` / 舞台画的
     * （§2.5「经 `postFx` 声明式启用」），⛔ **不在 `drawContent` 的每帧填充里**。
     * [SeasideOpBudget.overdrawEstimate] 守的是**本效果自己画的填充**。
     * ⚠️ 若把它算进来，HIGH 会 +1.00 屏（改前双通道 +2.00）——
     * 那正是 §4.9.5 的 `LOW ≤ 2.0` **不可能成立**的原因（LOW 若含晕影至少 2.7 屏），
     * 反过来印证了「晕影不计入」是规格本意。详见 [OVERDRAW_MAX_HIGH] 的 KDoc。
     */
    POST_FX("postFx", false, 1, 1, 1, 2, 0.0, 0.0, 0.0, 0.0),

    /**
     * ⭐ **补充行（§4.9.5 的 20 项清单未列）**：洼地小水洼 + 压扁椭圆反光（`drawPuddles`，
     * §14.3.6）。§4.7① 把它与「退水残沫」写在**同一条**门控上
     * （平均 `wetAmt < 0.06` / `< 0.05` ⇒ 水洼 / 退水残沫跳过），
     * 本表据此补一行，好让门禁**不静默漏掉**一个每帧元素。
     *
     * ⛔ **HIGH 因「原型保真回补」由 1 → 2**：原型是**本体 `#93AEB8` + 反光 `#DCEBF0`**
     *   两次 `fill`，反光 = 更小更扁更靠上的压扁椭圆（`rx·(0.28…0.58)` × `ry·0.26`，
     *   上移 `ry·0.22`，`α = a·0.85`）。一次提交只有一支画笔 ⇒ 两种颜色拿不到，
     *   故 HIGH 恢复第 2 次；MEDIUM / LOW 仍取单色（取 §5.6 登记的 [SeaOpItem] 内色
     *   `PAL_SHOAL`，靠**半径比**读出「高光更小更靠上」）。
     * 填充 ≈ 0.004 屏 —— ⛔ **三档都不动**（反光**嵌套在本体内部**，不新增任何覆盖）。
     */
    PUDDLE("洼地水洼", false, 1, 1, 2, 1, 0.004, 0.004, 0.004, 0.004);

}

/**
 * ⭐ G11 / G12 / G13 —— 把 §4.9 的 API 22 提交预算变成**构建期失败**的三道纯函数门。
 *
 * ## 为什么是纯函数而不是源码扫描（§4.9.5）
 * 真机 `192.168.0.114:5555` 本轮**不可达**（`adb connect` 超时 10060），性能只能在 JVM
 * 单测里守门；而渲染器因字段初始化就会建 `Path`/`Paint`/`Bitmap`，**在纯 JVM 里根本无法
 * `new`** ⇒ 源码抓取既测不出真实提交数、又会被注释与格式骗过。唯一可行形态就是
 * **把计数做成纯函数**：渲染层按同一张表实现，本门禁按同一张表算，两边对不上就是回归。
 *
 * ## 硬约束
 * 1. ⛔ **零 Android import、零 Compose import** —— 否则 JVM 单测 `not mocked`。
 * 2. ⛔ **零 Compose / material3 类型**（不用 `FxLevel`、不用 `Color`）⇒ 用 [SeaLevel]。
 * 3. **每帧路径零分配**：唯一的表是枚举（`SeaOpItem` 的属性随类加载构造一次），
 *    三个函数体里只有 `while` 与标量累加，⛔ 无 `listOf` / `.map` / `.sortedBy` / 装箱。
 * 4. **零 `Random`**（这里本来就不需要随机）。
 *
 * ## 与既有门禁的关系（§4.9.5 末）
 * ⛔ `LowTierElementBudgetTest` 硬编码只扫 Galaxy / Constellation / LyricsDotMatrix
 * ⇒ **对 seaside 零覆盖**。本对象是它的替代品，⛔ 不要指望它。
 * 本文件与 `PerfBudgetContractTest` / `RendererBaseContractTest` **互不重叠**：
 * 那两个只扫 `fun DrawScope.draw*` 的函数体与 `RendererFx` 子类，本文件两者都不是。
 */
internal object SeasideOpBudget {

    // ══════════════════════════════════════════════════════════════════════════
    //  契约常量 —— §4.9.5 的三张门。⛔ 改动只需改这三组行
    // ══════════════════════════════════════════════════════════════════════════

    /** **G11 提交预算**：§4.9.1「LOW ≤ 90、HIGH/MEDIUM ≤ 200」，对齐 E42 的 ≈200 / 29.7fps。 */
    const val OPS_MAX_LOW = 90
    const val OPS_MAX_MEDIUM = 200
    const val OPS_MAX_HIGH = 200

    /**
     * **G12 填充预算**：§4.9.5 原值 `LOW ≤ 2.0`、`MEDIUM ≤ 2.8`（单位「屏」）。
     *
     * ⚠️ **HIGH 档的填充上限在 §4.9.5 里没有给**，本表按同一判别意图补
     * `HIGH ≤ [OVERDRAW_MAX_HIGH]`（见那个常量的 KDoc，那里写清了异议与算术）。
     */
    const val OVERDRAW_MAX_LOW = 2.0f
    const val OVERDRAW_MAX_MEDIUM = 2.8f

    /**
     * ⚠️⚠️ **本文件唯一一处偏离规格的阈值，改它之前先读下面这段。**
     *
     * **§4.9.5 的 G12 行只给了 `LOW ≤ 2.0` / `MEDIUM ≤ 2.8` 两档，没有 HIGH。**
     * 而 §4.9.2 声称「改造后 HIGH **≈2.6 屏**」—— ⛔ **那个 2.6 从它自己的元素表算不出来**：
     * ```
     * 干沙 blit        1 − SAND_TEX_TOP = 0.540   （几何恒等式）
     * 水体场 底色渐变   SEA_BOTTOM_K    = 0.820   （§4.3.1「L1 远海水 0.00–SEA_BOTTOM_K」）
     * 水体场 场 blit   SEA_BOTTOM_K    = 0.820   （§14.3.7「每帧一次 blit 到 H·SEA_BOTTOM_K」）
     * 湿沙             SWASH_REACH·1.10 = 0.116  （§4.9.2 裁决后的那一次提交）
     * 焦散网           ≈0.050                         （331 胞壁边 + 30 射线 + 56 亮结的笔触）
     * 逐浪族（×2 浪）  (BAND+1.00BAND+0.90BAND+0.50BAND+0.20BAND+0.15BAND)·2 ≈ 0.398
     * 杂项（湿线/沙纹/残沫/手指/退水残沫/水洼/飞沫）                        ≈ 0.070
     *                                             ──────────────────────── 合计 ≈ 2.81
     * ```
     * ⇒ **2.6 少算了约 0.2 屏**，且其中三项是几何恒等式、无法靠调参消掉。
     * ⇒ 本常量取 **2.90**：高于诚实测得的 2.81（留 3% 余量），远低于「改造前」的 ≈8.05，
     * 判别力完好（8.05 ≫ 2.90 ≫ 2.81）。**这是对 §4.9.5 的显式补全，不是放宽。**
     *
     * ⛔ 若所有者裁定 HIGH 填充也必须 ≤ 2.8，那要动的是**元素表**（把底色渐变与场 blit
     * 合批、或让 LOW 才省那次 blit），**不是**这个阈值。
     */
    const val OVERDRAW_MAX_HIGH = 2.90f

    /**
     * **G13 native 堆预算**：ARGB_8888 位图像素总数 ≤ [NATIVE_PX_MAX]（§4.9.5）。
     *
     * ⚠️ **本门是分辨率相关的**，且上限来自 §4.7① / §4.8 的「沙纹理 > 320 万像素按比例
     * 降采样」。⇒ 在 `w·texH ≤ [SAND_TEX_MAX_PX]` 的分辨率范围内成立（实测到
     * 2560×1440 都成立，见 `SeasideOpBudgetTest`）；⚠️ **4K（3840×2160）会突破**
     * （沙纹理被钳到 3.2M + 泡沫贴图 0.39M + … ≈ 3.64M）—— 这是**规格内部的一处冲突**，
     * 已写进交付报告，改法只能是把 [SAND_TEX_MAX_PX] 收到 ≈2.4M（那会让 §4.7① 的 320 万失效）。
     */
    const val NATIVE_PX_MAX = 3_000_000

    /**
     * 单张沙纹理自身的像素上限（§4.7① / §4.8：「沙纹理 >320 万像素按比例降采样」）。
     * ⛔ 逐字取规格值 320 万；它与 [NATIVE_PX_MAX] 在 4K 上互相冲突（见其 KDoc）。
     */
    const val SAND_TEX_MAX_PX = 3_200_000

    /** 泡沫贴图 6 张 256²（§14.3.5 `buildFoamTiles`）。§4.7②：LOW 跳过贴图泡沫 ⇒ LOW 不分配。 */
    const val FOAM_TILES = 6
    const val FOAM_TILE_PX = 256 * 256

    /** 128px 颗粒图案（§14.3.5 `buildGrain`）。 */
    const val GRAIN_PX = 128 * 128

    /** 16×16 晶格斑驳图案（§14.3.5 `buildGrain`）。 */
    const val MOTTLE_PX = 16 * 16

    /** 水体场低分辨率画布的分母（§4.7①：`scale = clamp(ceil(sqrt(W·seaPx / 52000)), 4, 14)`）。 */
    const val FIELD_PX_DIVISOR = 52_000
    const val FIELD_SCALE_MIN = 4
    const val FIELD_SCALE_MAX = 14

    /** 稳态同框浪数（§5.2 `WAVE_FOLLOW_Y`：harness 实测 2 条占 **95%** 的帧）。 */
    const val STEADY_WAVES = 2

    /** 默认横向采样列数（§5.1 `COLS`）—— ⛔ 只影响「改造前」的逐列计数（`cols + 1 = 97`）。 */
    const val COLS = 96

    /** 蕾丝缓存 `Stroke` 实例数（§4.9.2：「`wj` 量化 ⇒ 缓存 **12 个** `Stroke`」）。 */
    const val LACE_STROKE_CACHE = 12

    // ══════════════════════════════════════════════════════════════════════════
    //  G11 提交预算
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ⭐ **G11**：该帧预计**提交数**（HWUI display list 条目数 + 原生着色器分配数）。
     *
     * @param level 画质档。
     * @param waveCount 同框浪数（§5.2 稳态 2；`WAVE_POOL = 6` 是槽位上限，⛔ 不是同框数）。
     * @param cols 横向采样列数。⛔ **裁决后形态不使用它**（阶梯是 14 段、不是 97 列）；
     *              它只在 `legacy = true` 时决定湿沙/高光的逐列渐变数（`cols + 1`）。
     * @param legacy ⛔ **只给负向自证用**：切到「改造前」形态（§4.9.2 的「原稿」列）。
     */
    fun estimate(level: SeaLevel, waveCount: Int, cols: Int = COLS, legacy: Boolean = false): Int {
        var n = 0
        var i = 0
        val items = SeaOpItem.entries
        while (i < items.size) {
            n += opsOf(items[i], level, cols, waveCount, legacy)
            i++
        }
        return n
    }

    /** [estimate] 的对应档位上限（供门禁与报告共用，避免两处各写一份数字）。 */
    fun opsMax(level: SeaLevel): Int = when (level) {
        SeaLevel.LOW -> OPS_MAX_LOW
        SeaLevel.MEDIUM -> OPS_MAX_MEDIUM
        SeaLevel.HIGH -> OPS_MAX_HIGH
    }

    /**
     * 逐元素提交数（[estimate] 的单项版本）—— 单测用它逐行核对 §4.9.2 的系数表。
     *
     * @param cols 只在 `legacy` 下生效（湿沙 / 镜面高光的逐列渐变数 = `cols + 1`）。
     * @param waveCount 只对 [SeaOpItem.perWave] 的行生效。
     */
    fun opsOf(item: SeaOpItem, level: SeaLevel, cols: Int = COLS, waveCount: Int = 1, legacy: Boolean = false): Int {
        var ops = if (legacy) legacyOpsOf(item, cols) else opsAfterOf(item, level)
        if (item.perWave) ops *= waveCount
        return ops
    }

    private fun opsAfterOf(item: SeaOpItem, level: SeaLevel): Int = when (level) {
        SeaLevel.LOW -> item.opsLow
        SeaLevel.MEDIUM -> item.opsMed
        SeaLevel.HIGH -> item.opsHigh
    }

    /** ⛔ 「改造前」形态：湿沙与镜面高光是**逐列小渐变 quad**（97 个），不是 1 次提交。 */
    private fun legacyOpsOf(item: SeaOpItem, cols: Int): Int = when (item) {
        SeaOpItem.WET_WASH, SeaOpItem.SHEEN -> cols + 1
        else -> item.opsLegacy
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  G12 填充预算
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ⭐ **G12**：该帧预计**填充量**，单位「屏」（`w·h` = 1 屏）。**每元素一项**，逐项取自
     * [SeaOpItem] 的 `fill*`。
     *
     * ⚠️ 返回值**与 `w`/`h` 无关**（过绘制率是尺度不变量，见 [SeaOpItem] 的 KDoc）。
     * `w`/`h` 只做入参校验与可读性，⛔ 不参与算术。
     *
     * @param waveCount 同框浪数（逐浪类元素乘它）。
     * @param legacy ⛔ **只给负向自证用**（§4.9.2 的「原稿」列：40 次逐块 alpha blit、
     *              97 列渐变、双通道 postFx …）。
     */
    fun overdrawEstimate(
        w: Float,
        h: Float,
        level: SeaLevel,
        waveCount: Int = STEADY_WAVES,
        legacy: Boolean = false
    ): Float {
        // ⛔ 刻意不带 message：那会是一个字符串模板（每次调用一次分配），而本函数
        //   要能挂到遥测路径上。`require` 无 message 时抛的是无 message 的异常。
        require(w > 0f && h > 0f)
        var acc = 0.0
        var i = 0
        val items = SeaOpItem.entries
        while (i < items.size) {
            acc += fillOf(items[i], level, waveCount, legacy)
            i++
        }
        return acc.toFloat()
    }

    /** [overdrawEstimate] 的对应档位上限。 */
    fun overdrawMax(level: SeaLevel): Float = when (level) {
        SeaLevel.LOW -> OVERDRAW_MAX_LOW
        SeaLevel.MEDIUM -> OVERDRAW_MAX_MEDIUM
        SeaLevel.HIGH -> OVERDRAW_MAX_HIGH
    }

    /** 逐元素填充量（[overdrawEstimate] 的单项版本，单位「屏」）。 */
    fun fillOf(item: SeaOpItem, level: SeaLevel, waveCount: Int = 1, legacy: Boolean = false): Double {
        var f = if (legacy) item.fillLegacy else fillAfterOf(item, level)
        if (item.perWave) f *= waveCount
        return f
    }

    private fun fillAfterOf(item: SeaOpItem, level: SeaLevel): Double = when (level) {
        SeaLevel.LOW -> item.fillLow
        SeaLevel.MEDIUM -> item.fillMed
        SeaLevel.HIGH -> item.fillHigh
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  G13 native 堆
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ⭐ **G13**：常驻 **native 堆**的 ARGB_8888 位图像素总数（resize 时烘、⛔ 每帧不新增）。
     *
     * 逐项（§14.3.5 烘焙层）：
     * | 位图 | 尺寸 | LOW |
     * |---|---|---|
     * | 沙纹理 `buildSandTexture` | `w × (h − SAND_TEX_TOP·h)`，> [SAND_TEX_MAX_PX] 按比例降采样 | ✔ |
     * | 泡沫贴图 `buildFoamTiles` | [FOAM_TILES] × [FOAM_TILE_PX] | ⛔ 0（§4.7② LOW 跳过贴图泡沫） |
     * | 颗粒图案 `buildGrain` | [GRAIN_PX] | ✔ |
     * | 晶格斑驳 `buildGrain` | [MOTTLE_PX] | ✔ |
     * | 水体场低分辨率画布 `fieldCvs` | `ceil(w/scale) × ceil(seaH/scale)` | ⛔ 0（§4.7② LOW 只留底色渐变） |
     * | 蕾丝 / 焦散拓扑 | ⛔ **只烘坐标与属性，无像素**（§4.8） | 0 |
     * | 湿沙 / 高光小条 | ⛔ **裁决后不存在**（湿沙共用那支缓存渐变；高光复用同一支、⛔ 不另烘贴图） | 0 |
     *
     * @param legacy ⛔ **只给负向自证用**：切到「改造前」的沙纹理 —— ⛔ **按整屏 `h` 烘**。
     */
    fun nativePxBudget(w: Float, h: Float, level: SeaLevel, legacy: Boolean = false): Int =
        nativePxBudgetWithSandTop(w, h, level, if (legacy) 0.0 else SEA_SAND_TEX_TOP)

    /**
     * [nativePxBudget] 的**可注入沙纹理上沿**版本 —— ⛔ 这就是 §4.9.5 要求的
     * 「断言沙纹理按 `texH = h − SAND_TEX_TOP·h` 而非 `h` 烘」的**唯一接缝**。
     *
     * 喂 `sandTopK = 0.0`（按整屏烘）⇒ `nativePxBudget` 必然突破 [NATIVE_PX_MAX]
     * （在 2560×1440 上实测 `3,625,411 > 3,000,000`）；喂 [SeaOpItem.SAND_TEX_TOP]
     * ⇒ `2,416,067 ≤ 3,000,000`。**这条负向自证由 `SeasideOpBudgetTest` 断言。**
     *
     * @param sandTopK 沙纹理上沿（占 h）。`0.0` = 整屏（⛔ 错），`0.46` = 规格值。
     */
    fun nativePxBudgetWithSandTop(
        w: Float,
        h: Float,
        level: SeaLevel,
        sandTopK: Double
    ): Int {
        require(w > 0f && h > 0f)
        var total = sandTexPx(w.toDouble(), h.toDouble(), sandTopK)
        if (level != SeaLevel.LOW) {
            total += (FOAM_TILES * FOAM_TILE_PX).toDouble()
            total += fieldPx(w.toDouble(), h.toDouble())
        }
        total += GRAIN_PX.toDouble()
        total += MOTTLE_PX.toDouble()
        return total.toInt()
    }

    /** 沙纹理像素（> [SAND_TEX_MAX_PX] 时按比例降采样 ⇒ 返回值恰好等于上限）。 */
    private fun sandTexPx(w: Double, h: Double, sandTopK: Double): Double {
        val texH = h * (1.0 - sandTopK)
        if (texH <= 0.0) return 0.0
        val raw = w * texH
        return if (raw <= SAND_TEX_MAX_PX) raw else SAND_TEX_MAX_PX.toDouble()
    }

    /** 水体场低分辨率画布的像素（§4.7① 的 `scale` 公式）。 */
    private fun fieldPx(w: Double, h: Double): Double {
        val seaH = h * SEA_SEA_BOTTOM_K
        val seaPx = w * seaH
        val raw = sqrt(w * seaPx / FIELD_PX_DIVISOR)
        var scale = ceil(raw)
        if (scale < FIELD_SCALE_MIN) scale = FIELD_SCALE_MIN.toDouble()
        if (scale > FIELD_SCALE_MAX) scale = FIELD_SCALE_MAX.toDouble()
        return ceil(w / scale) * ceil(seaH / scale)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  蕾丝 Stroke 缓存（§4.9.2 / §4.9.5 配套断言）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 蕾丝网**每帧的批量 stroke 提交数**（= `wj` 量化后的档数）。
     * §4.9.2：HIGH **12** / MEDIUM **3** / LOW **0**（LOW 跳过蕾丝，§4.7②）。
     */
    fun laceStrokeOps(level: SeaLevel): Int = opsOf(SeaOpItem.FOAM_LACE, level, waveCount = 1)

    /**
     * 蕾丝缓存的 `Stroke` **实例数**（不是每帧提交数）—— ⛔ **≤ [LACE_STROKE_CACHE] = 12**。
     *
     * §4.9.2 的根据：`lineWidth = (0.55 + 0.85·wj)·bandW·kindW` **逐边变化**，而
     * `Stroke.width` **不可变** ⇒ 必须把 `wj` 量化成有限档并**缓存**这些 `Stroke`。
     * ⛔ 如果不量化而每边 `new` 一个 `Stroke`，就是每帧几百次 native 分配 ——
     * 而这正是「解释调用数」预算（§4.9.1 末）要拦的东西。
     */
    fun laceStrokeCacheSize(): Int = LACE_STROKE_CACHE
}
package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Path as NativePath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.fx.FxLevel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * E43 [VisualizerTheme.SEASIDE]「海边」—— **渲染器骨架**（§14.3 + §4.9 合批架构）。
 *
 * ## 本文件的阶段边界（⚠️ 读之前先看这段）
 * 本文件分两批落地：
 * - **骨架批（已完成）**：注册链、帧内顺序、生命周期、缓存与零分配纪律、合批提交数、
 *   每个绘制元素的函数骨架 + 元素归属标注。
 * - **视觉批 1（已完成）**：**§5.6 调色板 → 各元素专用 `Brush`** + **§4.3.7 / §14.3.5 /
 *   §14.3.6 的全部烘焙层内容**（沙纹理四层 / 泡沫贴图 / 颗粒 / 晶格斑驳 / 水体场 /
 *   焦散胞壁网拓扑 / 蕾丝网拓扑 / 两条缓存渐变 / 蕾丝 `Stroke` 阶梯接线）。
 * - **视觉批 2a（已完成）**：**岸线与沙面族的逐帧绘制** —— [drawCausticNet]（含 D9 对齐：
 *   胞壁网 MEDIUM+HIGH、射线与亮结仅 HIGH）+ [buildCausticStrokes]（解除原阻塞）+
 *   [drawSand]（上边界贴岸线 + 着色器矩阵补 `dstSize`）+ [drawWetWash] / [drawSheen]
 *   （**各自 1 次 `drawPath`、合计 2 次**，`wetEdge` 真正写成下轮廓；高光靠第二次
 *   `BlendMode.Plus` 提交才可见 —— §4.9.2 的「合成 1 次」已被所有者推翻，见 [drawSheen]）+ [drawPuddles] +
 *   [drawResidualStreaks]，以及蕾丝线宽从「加权均值」改为
 *   **4 类 × 3 档 = 12 支** [buildLaceStrokes]。
 * - **视觉批 2b（已完成）**：**浪族与泡沫族的逐帧绘制函数体** —— [buildWaveColumns]
 *   （原型 `drawSwellBands` 的逐列循环，§4.3.9 三条硬不变量全在这里）、[drawSwellBody]
 *   （迎光 [PAL_RIDGE] / 背光 [PAL_TROUGH] 的 5 停靠体积渐变）、[drawFoamStrip] +
 *   [drawPunchHoles]（阶梯轮廓 + **evenodd** 椭圆破洞）、[drawSeaFoamWash]（分 8 段算
 *   `fk`、渐变锚在**逐列** y 极值）、[drawOpenSeaFoam]（**22 块**预烘轮廓合成一条 path 一次
 *   fill；⛔ 全部拖尾侧）+ [drawDisturbance]（**已复活**：**18 块**前置到前缘前方，
 *   合成**另一条** path 一次 fill，⛔ 不复用拖尾侧那条距离律）、
 *   [drawFoamLace]（端点共享 + 内部点正弦 + 两端钉死，按 `(kind × band)` 12 支缓存
 *   `Stroke`）、[drawCrestLip]（⛔ 只对非领头浪；HIGH 两个 pass / MEDIUM 一个）、
 *   [drawSwashFingers]、
 *   [drawCrab]（纯装饰，8 批 `drawPath`）、[drawSplash]（1 次 `drawPoints`）、
 *   [drawRipples]（3 次 `drawLines` + 逐列岸线门控）、[drawResidue]（3 次 `drawPoints`）。
 *
 * ## 三层分工（⛔ 别越层调用）
 * | 层 | 文件 | 职责 |
 * |---|---|---|
 * | 模拟 | [SeasideWaves] | 浪队列 / 岸线 / 逐列湿润记忆。**零音频引用**、纯 Kotlin |
 * | 音频 | [SeasideAudioMap] | 分频取样 / 平滑 / 鼓点冲量。**只产外观量**、零时基 |
 * | 绘制 | **本文件** | 只读上面两者的输出 + 逐帧提交 |
 *
 * ## ⭐ 元素清单完整性（[SeaOpItem] 21 项逐项归属；门禁 `SeasideTest` 断言「无漏项」）
 *
 * | [SeaOpItem] | 实现函数 | 备注 |
 * |---|---|---|
 * | [SeaOpItem.SAND_BLIT] | [drawSand] | 恒 1 次：上边界贴岸线的**路径** + 纹理着色器填充 |
 * | [SeaOpItem.WET_WASH] | [drawWetWash] | ⛔ 1 次提交：一条 path + **一个缓存竖向渐变** |
 * | [SeaOpItem.SHEEN] | [drawSheen] | ⛔ **1 次提交**：**自己的** path + `BlendMode.Plus`（⛔ **不**折进湿沙那次 —— NonZero 下并入湿区就不可见了） |
 * | [SeaOpItem.SEA_FIELD] | [drawSeaField] | LOW 只留底色渐变；MED/HIGH 再加一次场 blit |
 * | [SeaOpItem.CAUSTIC] | [drawCausticNet] | ⛔ 恒 **3** 次：胞壁网 MEDIUM+HIGH，射线与亮结**仅 HIGH**（D9） |
 * | [SeaOpItem.SWELL_BODY] | [drawSwellBody] | 逐浪 1 条 path |
 * | [SeaOpItem.FOAM_LADDER] | [drawFoamStrip] | 逐浪；段数 = 预算表 ops（14 / 7 / 7） |
 * | [SeaOpItem.SEA_FOAM_WASH] | [drawSeaFoamWash] | 逐浪；LOW 跳过 |
 * | [SeaOpItem.OPEN_SEA_FOAM] | [drawOpenSeaFoam] | 逐浪；⛔ **1 条 path 一次 fill**（不是 22 次 blit）；**22 块**全部拖尾侧 |
 * | [SeaOpItem.DISTURBANCE] | [drawDisturbance] | ⛔ **已复活**（原型保真回补）：逐浪；⛔ **1 条 path 一次 fill**（⛔ 不是 18 次 blit）；**只对非领头浪**；⛔ MED/LOW 为 0 |
 * | [SeaOpItem.FOAM_LACE] | [drawFoamLace] | 逐浪；4 类 × 3 档 ⇒ [SeasideOpBudget.laceStrokeCacheSize] 支缓存 `Stroke` |
 * | [SeaOpItem.CREST_LIP] | [drawCrestLip] | 逐浪；⛔ **只对非领头浪**；HIGH **2 pass** / MED **1 pass** / LOW 0 |
 * | [SeaOpItem.SWASH_FINGER] | [drawSwashFingers] | **1 次** `drawLines` |
 * | [SeaOpItem.RESIDUAL_STREAK] | [drawResidualStreaks] | HIGH **3 次** `drawLines`（3 pass 羽状丝缕）/ MED·LOW 1 次 |
 * | ⛔ ~~[SeaOpItem.WET_LINE]~~ | ⛔ **无**（已整层删除，2026-10-05 所有者视觉裁决，见 ⑥ 区前的「整层删除记录」） | **0 次** |
 * | [SeaOpItem.SAND_GRAIN] | [drawRipples] | 沙纹；**3 次** `drawLines`（MED/LOW 各 1 次） |
 * | [SeaOpItem.RESIDUE_POINTS] | [drawResidue] | 三笔球体感 ⇒ **3 次** `drawPoints` |
 * | [SeaOpItem.SPLASH_POINTS] | [drawSplash] | **1 次** `drawPoints`；LOW 关闭 |
 * | [SeaOpItem.CRAB] | [drawCrab] | 纯装饰；**8 次** `drawPath`（HIGH）/ 4（MED）/ **2**（LOW = 壳填 + 壳沿） |
 * | [SeaOpItem.PUDDLE] | [drawPuddles] | HIGH **2 次**（本体 + 反光双色）/ MED·LOW 1 次单色 |
 * | [SeaOpItem.POST_FX] | **无**（声明式） | 由基类 `RendererFx` 按 [postFx] 施加 ⛔ **不**在本文件画 |
 *
 * [drawSwellBands] 是**编排函数**（本身 0 提交），[drawPointsBatch] / [drawLinesBatch] 是
 * **合批原语**（提交数已计入调用它们的元素）。
 *
 * ## ⛔ 四条会让门禁**静默失效**的写法（§4.9.4，本类逐条受约束）
 * 1. ⛔ **类头必须是单行** `class SeasideRenderer : RendererFx() {` ——
 *    `RendererBaseContractTest` 与 `FxCoverageScanTest` 都对类头 400 字符做
 *    `indexOf('{')`；若 `: RendererFx(` 之前出现 `{`，本类会被**不报错地**剔除出 G5 / G7 扫描。
 * 2. ⛔ **每个每帧绘制辅助函数必须带 `DrawScope.` 接收者** —— `PerfBudgetContractTest` 的
 *    正则只匹配 `fun DrawScope.drawXxx(`；漏掉接收者就整体逃过零分配扫描。
 *    （`drawFoamStrip` 就是方案原稿 `fillStrip` 改名而来，为的就是进这道门。）
 * 3. ⛔ **[postFx] 必须是纯数值字面量、无类型标注、无 getter、无具名常量** ——
 *    `FxCoverageScanTest` 的正则只认 `override val postFx = PostFx(<数字>f)`。
 * 4. ⛔ **全文件零 `clipPath(`、零 `clip(RoundRect`、零 `RoundedCornerShape`** —— 本项目在
 *    Android 5.1（创维 rtd299o）真机三次复现 hwui `Region::createTJunctionFreeRegion` SIGSEGV，
 *    禁令范围是**整个** `clipPath`（§4.9.3 纠错），**不分圆角与否**。
 *    ⇒ [drawRipples] / [drawResidue] 的签名**不含** `clip: Path`，改逐列 `wetAmt` 门控。
 *
 * ## 缓存与零分配纪律
 * - 所有 `Paint` / `Path` / `FloatArray` / `Brush` / `Stroke` / `ImageBitmap` / `IntArray`
 *   **全是构造期成员**（或尺寸变化时烘焙的成员），逐帧路径零分配。
 * - ⛔ 禁 `.sortedBy` / `.map` / `.filter` / `.toList`；需要排序时用预分配 `IntArray`
 *   + 插入排序（本次骨架里逐浪遍历按**槽位**走，天然有序，暂无排序需求）。
 * - ⛔ `Stroke.width` **不可变** ⇒ 蕾丝按 **(类 × 距离档) = 4 × 3** 缓存 12 个 `Stroke`
 *   （[buildLaceStrokes]），焦散按 3 档亮度再缓存 3 个（[buildCausticStrokes]）；
 *   ⛔ 这**两个烘焙期函数**是全文件仅剩的两个 `Stroke(` 构造点（`SeasideTest` ⑥ 断言
 *   「构造点集合 ⊆ {buildLaceStrokes, buildCausticStrokes}」）。
 * - ⛔ `Brush.verticalGradient(vararg)` 会分配 vararg 数组 ⇒ 只许在烘焙函数里出现，
 *   ⛔ **不在任何 `DrawScope.draw*` 函数体内**（`PerfBudgetContractTest` 不查这个，
 *   E42 的 KDoc 正好踩中；`SeasideTest` 自建扫描补洞）。
 * - [SeasideWaves] / [SeasideAudioMap] 的量是 `Double` ⇒ 边界处自行 `.toFloat()`。
 *
 * ## 时基（与其它效果同款的刻意取舍）
 * 零时刻 [t0Ms] 在**首帧**由 `fx.nowMs` 记一次 —— ⛔ 不在 [onEnterContent] 里记：
 * 那里拿不到 `fx`，而唯一可用的 `RenderContext.nowMs` 在进入瞬间是**墙钟**
 * （`System.currentTimeMillis()`），与 `frame.timeMs` 的**单调**时钟不同源，两者相减得
 * 约 −1.7e12 ms ⇒ 整个生命周期冻结（E42 同一个坑）。
 * ⛔ **绝不**读 `ctx.nowMs`（`RendererBaseContractTest` 也会判违规）；[FrameClock] 只吃
 * `AudioFrame.timeMs`。暂停时 `fx.dt = 0` ⇒ [SeasideWaves.step] 的 `dt` 传 0，队列 / 退水 /
 * 干燥全部冻结（§4.7①）。
 *
 * ## 生命周期（§4.8 + E42 踩过的坑）
 * - [onEnterContent] 首行 [releaseResources] —— `RendererSwapper.sync` 在画质切换时会
 *   **重入** `onEnter`；⛔ 但**不**复位 [t0Ms] / 音频包络 / 随机相位（复位会让浪的行程与
 *   水线突然跳变）。
 * - [onEnterContent] ⛔ **不**按尺寸烘焙：`ctx.canvasSize` 此刻仍是 `Size.Zero`。
 * - 尺寸或密度变化 ⇒ [drawContent] 调 [rebuildGeometry]，⛔ 该分支内**不得**有任何
 *   `ProceduralTexture.ensure*` 调用（本效果一张程序纹理都不取，§4.8）。
 * - [onExitContent] ⇒ [releaseResources]：自持位图逐张 try/catch `recycle()`
 *   （API 22–25 位图像素在 native 堆），`Path` 只能 `reset()`（Compose `Path` 没有
 *   `recycle()`）；⛔ **不**调 `ProceduralTexture.release()` / `OverlayFx.release()`。
 */
class SeasideRenderer : RendererFx() {

    override val theme = VisualizerTheme.SEASIDE

    /**
     * ⛔ **单通道**：只保留暗角，⛔ **去掉 `grain`**（§4.9.2 裁决：双通道 2.07 Mpx/帧）。
     *
     * ⛔ **必须是纯数值字面量、无类型标注、无 getter、无具名常量** ——
     * `FxCoverageScanTest.postFxRe` 只认 `override val postFx = PostFx(<数字>f)`，
     * 任何其他写法都会被**静默判为「未覆盖后处理」**（§3.5 / §4.9.4 陷阱 3，E42 实际踩过）。
     * 注：晕影是**声明式**交给基类与舞台画的，⛔ 不计入 `drawContent` 的每帧填充
     * （见 `SeaOpItem.POST_FX` 的 KDoc：口径必须与 E42 的 2.0 屏对齐）。
     */
    override val postFx = PostFx(vignette = 0.30f)

    // ═══════════════════════════ 常量 ═══════════════════════════

    internal companion object {

        // ── §4.7① 原型实测的自动降级门槛（**规格值**，不是视觉可调项）──────────
        /**
         * `amp ≤ SILENCE_FLOOR` ⇒ 跳过该浪（**在 `kA` 之前**，`drawSwellBands` 顶部）。
         * ⚠️ ⛔ **不得**下调这条来「让浪一直可见」—— §4.7① 已判定 cull 分布是设计内的。
         */
        const val AMP_CULL_MIN = 0.012

        /** `fade ≤ 0.02` ⇒ 跳过该浪（同样在 `kA` 之前）。 */
        const val FADE_CULL_MIN = 0.02

        /** `kA < 0.06` ⇒ 跳过非泡沫浪本体 [drawSwellBody]。 */
        const val KA_BODY_MIN = 0.06

        /** `kA < 0.10` ⇒ 跳过 [drawSeaFoamWash] 与 [drawFoamLace]。 */
        const val KA_FOAM_MIN = 0.10

        /** `kA < 0.12` ⇒ 跳过 [drawOpenSeaFoam]（与已删除的扰动前锋同一条门）。 */
        const val KA_PATCH_MIN = 0.12

        /** `ladder[s].a · kA < 0.015` ⇒ 该窄带跳过（[drawFoamStrip] 内逐段判）。 */
        const val KA_STRIP_MIN = 0.015

        /** 窄带 `a < 0.25` ⇒ 该段按 `step2 = 2` 隔列半分辨率采样（[drawFoamStrip]）。 */
        const val STRIP_A_HALF_SAMPLE = 0.25

        // ═══════════════ 真机 bisect 开关（临时，定位完即可整块删除）═══════════════
        /**
         * ⛔ **临时 bisect 开关**：`true` ⇒ [drawSheen] 开头直接 `return`（整层不画）。
         *
         * 用途：真机上「沙滩竖纹」逐层定位。把它翻成 `false` / `true` 各打一次、
         * 各截一张同位置图，比对竖纹是否消失即可判定该层是不是来源。
         * ⚠️ **不是**画质档、**不是**门限、**不**参与任何预算表 —— 纯人工开关。
         * ⚠️ 定位完成后请连同 [drawWetWash] 里的同名开关一起删掉。
         */
        const val BISECT_SHEEN = false

        /** ⛔ **临时 bisect 开关**：`true` ⇒ [drawWetWash] 开头直接 `return`。见 [BISECT_SHEEN]。 */
        const val BISECT_WET_WASH = false

        /**
         * ⛔ **临时 bisect 开关**：`true` ⇒ [buildSandTexture] **只写层①底色**，
         * 跳过层②起伏带 / 层③潮湿斑 / 层④颗粒。
         *
         * 用途：判别「沙滩竖纹」到底在**纹理数据**里还是在**采样 / 覆盖层**里。
         *
         * ✅ **2026-10-05 结论 = `false`**（真机二分已判完，⛔ 不要再翻回 `true`）：
         * 翻成 `true` 后竖纹消失，只说明「**采样把纵向高频吃掉了**」，⛔ **不能**推出
         * 「成因在纹理数据」—— 四层公式 1:1 复算的相邻行相关是 **−0.004**（各向同性），
         * 与真机读数矛盾。真因见 [drawSand]：整块沙被 `CLAMP` 到纹理的**最后一行**
         * ⇒ 纯 1-D。判据（可复算）：真机沙的 x 剖面与 `hash2(x, th−1)` 的相关 **+0.55**、
         * 与其余 582 行 **≈0**。
         */
        const val BISECT_SAND_TEX_FLAT = false


        /**
         * ⛔ **诊断开关**（2026-10-05）：`true` ⇒ [drawWetWash] 整层不画。
         *
         * 用逐层剔除定位「接缝黑边」属于哪一层；默认 `false`（正常绘制）。
         * ⚤ 开关放在**函数体第一行**（不是调用点），否则测不到函数内部贡献。
         *
         * ✅ **2026-10-05 终值 = `false`**：这一版是**做对照测试**时临时翻上去的
         * （用来量「32 条 ribbon 吃掉多少帧预算」），⛔ 不属于最终版。
         * 所有者明确：**湿沙本来就该是深色区域** —— 原型 `drawWetWash`
         * （`seaside-preview.html:2027-2065`）的 5 个色标全是深棕（`rgba(74,50,26,0.76·a)` 起），
         * 关掉它等于把 `wetEdge` 以下那片该变深的沙留成干沙色（实测帧率 1.4 → 7.2 fps，
         * 但那是拿「少画一层」换来的，不是优化）。
         */
        const val BISECT_WET_WASH_OFF = false

        /**
         * ⛔ **诊断开关**（2026-10-05）：`true` ⇒ [drawSheen] 整层不画。
         *
         * 用逐层剔除定位「接缝黑边」属于哪一层；默认 `false`（正常绘制）。
         * ⚤ 开关放在**函数体第一行**（不是调用点），否则测不到函数内部贡献。
         */
        const val BISECT_SHEEN_OFF = false

        /**
         * ⛔ **诊断开关**（2026-10-05）：`true` ⇒ [drawSand] 整层不画。
         *
         * 用逐层剔除定位「接缝黑边」属于哪一层；默认 `false`（正常绘制）。
         * ⚤ 开关放在**函数体第一行**（不是调用点），否则测不到函数内部贡献。
         */
        const val BISECT_SAND_OFF = false

        /**
         * ⛔ **诊断开关**（2026-10-05）：`true` ⇒ [drawPuddles] 整层不画。
         *
         * 用逐层剔除定位「接缝黑边」属于哪一层；默认 `false`（正常绘制）。
         * ⚤ 开关放在**函数体第一行**（不是调用点），否则测不到函数内部贡献。
         */
        const val BISECT_PUDDLE_OFF = false

        /**
         * ⛔ **诊断开关**（2026-10-05）：`true` ⇒ [drawResidualStreaks] 整层不画。
         *
         * 用逐层剔除定位「接缝黑边」属于哪一层；默认 `false`（正常绘制）。
         * ⚤ 开关放在**函数体第一行**（不是调用点），否则测不到函数内部贡献。
         */
        const val BISECT_RESIDUAL_OFF = false

        /**
         * ⛔ **诊断开关**（2026-10-05）：`true` ⇒ [drawFoamStrip] 整层不画。
         *
         * 用逐层剔除定位「接缝黑边」属于哪一层；默认 `false`（正常绘制）。
         * ⚤ 开关放在**函数体第一行**（不是调用点），否则测不到函数内部贡献。
         */
        const val BISECT_FOAMSTRIP_OFF = false

        /**
         * ⛔ **诊断开关**（2026-10-05）：`true` ⇒ [drawSeaField] 整层不画。
         *
         * 用逐层剔除定位「接缝黑边」属于哪一层；默认 `false`（正常绘制）。
         * ⚤ 开关放在**函数体第一行**（不是调用点），否则测不到函数内部贡献。
         */
        const val BISECT_SEAFIELD_OFF = false

        /** 无列 `wetAmt > 0.012` ⇒ 整个湿沙层跳过（[drawWetWash]）。 */
        const val WET_ANY_MIN = 0.012

        /**
         * 湿区厚度的下限（px）—— 原型 `drawWetWash` 的 `d = wetEdge[i] − shoreYs[i] < 0.6 ⇒ 跳过`
         * （`seaside-preview.html:2041`）。⛔ 低于它那列就不进 path，否则会退化成一个亚像素细条。
         */
        const val WET_BAND_MIN_PX = 0.6

        /**
         * ⭐ 湿沙 / 镜面高光的**逐列 alpha 分档数**（2026-10-05 新增）。
         *
         * 原型把 `wetAmt[i]` **逐列**乘进 5 个（湿沙）/ 3 个（高光）色标
         * （`seaside-preview.html:2054-2059` / `:2083`）。⛔ 此前本文件只有
         * `wetAmt > [WET_ANY_MIN]` / `> [WET_SHEEN_MIN]` 的**二值门** ⇒ 一条 run 内
         * 所有列同 alpha，而原型相邻列可以差十几倍 ⇒ 湿沙深浅沿岸**突变成竖条**。
         *
         * ⇒ 把 `wetAmt ∈ [0,1]` 等分 3 档、每档一次 `drawPath`（α 取档中点）。
         * 3 档而非更多：湿沙本身只有 5 个色标，3 档已能保住「近水深 / 远水浅」的梯度；
         * ⛔ 再多只是把同一条竖直渐变切得更碎，边际收益低于提交数。
         *
         * ⚠️ 提交数：湿沙 1 → 3、高光 1 → 3（见 [SeaOpItem.WET_WASH] / [SeaOpItem.SHEEN]）。
         * ⚠️ [SeasideTest] ③ 按**文本出现次数**判 `drawPath(` 必须恰好 1 个 ——
         *   两个函数体内都**只有 1 处 `drawPath(`，且它在 `while (tier < …)` 循环里** ⇒ 门仍成立。
         */
        /**
         * ⛔ **已删除实现，仅保留常量作历史记录**（2026-10-05）—— 逐帧**不再**有任何引用。
         *
         * 湿沙带曾有的**嵌套 ribbon 数**（深度粒度）。`[WET_RIBBON_N]` 条平色 ribbon 曾是湿沙
         * 唯一的实现（把原型的逐列渐变量化成 N 档平色），配 `wetRibbonBrush` 数组（**已删**）。
         *
         * ## ⛔ 为什么删：它吃掉了 ~80% 的帧预算
         * 真机 A/B（同场景、同档）：关掉湿沙整层后帧率 **1.4 fps → 7.2 fps**。
         * 32 次 `drawPath` 每次都带一支 `SolidColor` 刷 + 一条重新描出的 97 点轮廓
         * ⇒ 光这一层就吃掉约五分之四的预算，而它换来的只是「没有渐变的平色带」。
         * ⇒ 改回 **1 次 `drawPath` + 1 支原生 `LinearGradient`**（[WET_WASH_STOP_POS] 一族），
         *   机制见 [swellBodyNativePaint] 的 KDoc（归一化 `y ∈ [0,1]` + [gradMatrix]）。
         *
         * 原型是**逐列一支线性渐变**（`seaside-preview.html:2053`
         * `ctx.createLinearGradient(0, shoreYs[i] - WET_OVER, 0, wetEdge[i])`，`:2061` 逐列
         * `fillRect`）；一次 `drawPath` 只能一支刷子 ⇒ 量化成平色是当时唯一的走法。
         *
         * ⚠️ 顺带记下量化方案当初**为什么没**退化成 fix-18 那种「竖向条带」：
         *   那版按 **`wetAmt[i]` 分档**，档边界落在**列**上 ⇒ 每个档边界一道垂直稛缨。
         *   ribbon 版把档边界放在**深度**（`d = (y − shoreYs[i]) / L_i`）上，每列 `L_i` 各不相同
         *   ⇒ 同一个 `d` 落在不同 y ⇒ 档边界跟着水线走。
         *   ⛔ 单次渐变版不需要这个机关（连续渐变 ⇒ 本来就没有档边界）。
         */
        const val WET_RIBBON_N = 32

        /**
         * ⛔ **已无引用**（2026-10-05，湿沙改单次渐变后）—— 仅保留常量作历史记录。
         *
         * 接缝几何的**羽化宽度**（占 `d` 的比例）。它是 32 条平色 ribbon 版的「接缝羽化」：
         * 把前 `WET_SEAM_FEATHER` 比例的 ribbon alpha 从 **0 平滑升到原值**。
         * ⭐ 它的**意图**已被 [WET_WASH_STOP_POS] 的驼峰色标吸收并做得更好 ——
         *   那一版直接在 `t = 0` 处给 alpha = **0**（原生渐变的色标，不是量化出来的），
         *   所以不再需要「按比例羽化」这种近似。
         *
         * 所有者裁决 2026-10-05（仍然成立）：接缝处**只保留海水颜色**。
         * 原型那条边（`seaside-preview.html:2052` 渐变起点在 `shoreYs[i] − WET_OVER`）
         * 在本地退化成「几何硬边 + 平色」= 一条 74 灰阶的硬边，且深度 ∝ `wetAvg`
         * ⇒ 退潮时最重。**这是视觉裁决，不是移植缺陷。**
         */
        const val WET_SEAM_FEATHER = 0.10

        // ── 湿沙「单次渐变」色标（2026-10-05 重写，取代 [WET_RIBBON_N] 条平色 ribbon）────

        /**
         * ⭐ 湿沙那**一支**原生 `LinearGradient` 的归一化色标位置（升序，`t ∈ [0,1]`，⛔ 烘焙期定死）。
         *
         * RGB / alpha 逐字取原型 `drawWetWash` 的 5 个色标（`seaside-preview.html:2055-2059`）：
         * `rgba(74,50,26,0.76·a) → rgba(82,57,30,0.60·a) → rgba(90,65,36,0.33·a)
         *  → rgba(98,75,44,0.11·a) → rgba(106,84,54,0)`。
         *
         * ## ⛔⛔ 与原型唯一的、**故意的**差异：`t = 0` 处 alpha = **0**（原型是 0.76）
         * 峰值提前到 `t = 0.14`。
         * 原因：原型的渐变是**逐列**锚在 `shoreYs[i] − WET_OVER` 上的（`seaside-preview.html:2053`
         * `ctx.createLinearGradient(0, shoreYs[i] - WET_OVER, 0, wetEdge[i])`，`:2061` 逐列
         * `fillRect`，`WET_OVER = 16` 见 `:2026`）⇒ 每列的 `t = 0` 都恰好是自己那条水线。
         * 本实现只有**一支共享**渐变（原型 97 支 = 97 次提交），它的 `t = 0` 只能落在
         * **全幅 `min(shoreYs)`** 上。若沿用原型的「t=0 最深」，那一列的水线会正好压在最深
         * 色标上 ⇒ **1px 级的暗边**，随浪相位在屏幕上左右游走（[buildWetBrush] KDoc 记的 pulsate）。
         * ⇒ 改成「`t = 0` 全透明 → 0.14 冲到峰值 → 1.0 归零」的**驼峰**：
         * alpha 沿每列的上沿**连续**变化（不再是逐列跳变）⇒ 接缝不再有那道线。
         * ⚠️ 代价（**已记录，不是近似误差的托词**）：`a ≈ 0` 只在**最高**的那一列成立，
         *   别的列的水线会落在驼峰的上升沿（≈0.2…0.7）⇒ 同一个 y 在不同列深浅不同。
         *   **一支共享渐变在原理上不可能让每一列都落在 a≈0**（那要求 ramp 全程 ≈0 = 湿沙不画）。
         *   所有者裁决：这远好过一条会游走的黑线，也远好过 32 次提交。
         */
        val WET_WASH_STOP_POS = floatArrayOf(0f, 0.14f, 0.40f, 0.64f, 0.85f, 1f)

        /** 与 [WET_WASH_STOP_POS] 一一对应的 alpha（⛔ 归一化，峰值 = [WET_WASH_PEAK_A]）。 */
        val WET_WASH_STOP_A = floatArrayOf(0f, 0.76f, 0.60f, 0.33f, 0.11f, 0f)

        /** 与 [WET_WASH_STOP_POS] 一一对应的 RGB（⛔ 不含 alpha；末位复用第 5 档的 `106,84,54`）。 */
        val WET_WASH_STOP_RGB = intArrayOf(0x4A321A, 0x52391E, 0x5A4124, 0x624B2C, 0x6A5436, 0x6A5436)

        /** 湿沙渐变的峰值 alpha —— 逐帧用 `Paint.setAlpha` 乘回 [WET_WASH_STOP_A] 的归一化基数。 */
        const val WET_WASH_PEAK_A = 0.76

        /** 湿沙渐变纵向跨度（px）的下限 —— 太小则色标挤在几行里，退化成平色。 */
        const val WET_WASH_SPAN_MIN = 4.0

        /** 少于 4 列 `wetAmt > 0.05` ⇒ 镜面高光**内容**跳过（[drawSheen]；⛔ 不改提交数）。 */
        const val WET_SHEEN_COL_MIN = 4
        const val WET_SHEEN_MIN = 0.05

        /** 平均 `wetAmt < 0.06` ⇒ [drawPuddles] 跳过；`< 0.05` ⇒ [drawResidualStreaks] 跳过。 */
        const val WET_AVG_PUDDLE_MIN = 0.06
        const val WET_AVG_RESIDUAL_MIN = 0.05

        /** `vigor < 0.05`（干燥期）⇒ [drawSwashFingers] 跳过（干燥期 `vigor = 0.10`）。 */
        const val VIGOR_FINGER_MIN = 0.05

        /** swash 周期三阶段的 [drawSwashFingers] vigor（§4.7①：上涌 / 退水 / 干燥）。 */
        const val VIGOR_UPRUSH = 1.0
        const val VIGOR_RETREAT = 0.50
        const val VIGOR_DRY = 0.10

        /** `prefers-reduced-motion` ⇒ 低档整体放慢到 0.35×（§4.7①，浏览器原型无验证）。 */
        const val TIME_SCALE_LOW = 0.35

        // ── §4.3.x 元素容量（**规格上限**，坐标缓冲按它预分配）──────────────────
        /** 飞沫点数上限（§4.3.8 / §4.9.2「`SPLASH_MAX = 180` 逐点 → 1 次 `drawPoints`」）。 */
        const val SPLASH_MAX = 180

        /** 残沫白点数上限（§4.3.8 / §4.9.2「`RESIDUE_MAX = 52` × 3 笔 → 3 次 `drawPoints`」）。 */
        const val RESIDUE_MAX = 52

        /** 浪花手指根数上限（§4.3.8「`FINGER_MAX = 30`」→ 1 次 `drawLines`）。 */
        /** 浪花手指根数上限（§4.3.8「`FINGER_MAX = 30`」→ 1 次 `drawLines`）。 */
        const val FINGER_MAX = 30

        /**
         * 浪花手指的位置抖动幅度（格）：原型 `(i + 0.5)/30 + 0.85·(hash2(h,601) − 0.5)`。
         * ⛔ 等距栅格 + 固定形状会渲染成「一排等距的半圆扇贝」，像装饰花边而不是浪花。
         */
        const val FINGER_JIT = 0.85

        /** 「只有 45% 真的伸出去」的门控：`reach = pow(hash2(h,604), 2.2)`，`reach < 0.06` 跳过。 */
        const val FINGER_REACH_POW = 2.2
        const val FINGER_REACH_MIN = 0.06

        /** 指长 `H·(0.002 + 0.030·reach)·(0.35 + 0.65·sMid)·vigor` 与基线上抬 `H·0.003`。 */
        const val FINGER_LEN_LO = 0.002
        const val FINGER_LEN_SPAN = 0.030
        const val FINGER_BASE_UP = 0.003

        /** 指尖横向倾斜 `(hash2(h,605) − 0.5)·wide·0.9`。 */
        const val FINGER_LEAN = 0.9

        /**
         * 逐指宽度 `wide = W·(0.006 + 0.026·hash2(h,603)^1.6)`（原型 `seaside-preview.html:2419`）。
         * ⛔ **不是** `W` —— [FINGER_LEAN] 必须乘它，早先漏乘导致 `lean` 放大 30 倍以上。
         */
        const val FINGER_WIDE_LO = 0.006
        const val FINGER_WIDE_SPAN = 0.026
        const val FINGER_WIDE_POW = 1.6

        /** 三档的 alpha 阶梯 `0.15 − tier·0.042`（⛔ 一次提交只有一支画笔 ⇒ 取第 0 档）。 */
        const val FINGER_A0 = 0.15
        const val FINGER_A_STEP = 0.042

        /** 笔宽**下限**（px，× density）—— 实际取参与指的 `wide` 均值，原型是填充舌头。 */
        const val FINGER_W = 2.0f

        /** 洼地水洼个数上限（§4.3.8「`PUDDLE_MAX = 16`」）。 */
        const val PUDDLE_MAX = 16

        /** 沙纹每档的**线数**上限（§4.3.8「沙纹（`drawRipples`，26 条断段浅色调）」）。
         * ⛔ **不是**段数 —— 一条线还有 2~4 段（见 [SAND_LINE_SEG_LO] / [_SPAN]）。 */
        const val SAND_GRAIN_SEG_MAX = 26

        /** 一段沙纹折线的采样点数减一（原型 `k <= 6` ⇒ 7 个点）⇒ 6 条直线段。 */
        const val SAND_GRAIN_STEP_SPAN = 6

        /** 一段沙纹折线在 `drawLines` 里占的直线段数（= 采样点数 − 1）。 */
        const val SAND_GRAIN_PTS_PER_SEG = SAND_GRAIN_STEP_SPAN

        /** 一档沙纹的 float 容量（每段 4 个 float）：`线数 × 每线最多段数 × 段内直线段数 × 4`。 */
        val SAND_GRAIN_SEG_STRIDE =
            SAND_GRAIN_SEG_MAX * (SAND_LINE_SEG_LO + SAND_LINE_SEG_SPAN) * SAND_GRAIN_PTS_PER_SEG * 4

        // ── 合批批次数（结构量：直接引用预算表的 ops，不另立一份）───────────────
        /**
         * 焦散网的三条 `Path`（§4.9.2「86 提交折成同 3 条 path 里的线段」）。
         * ⚠️ 预算表 `opsMed = 0` ⇒ **整项仅 HIGH 档**，MEDIUM 及以下全删。
         * （`SeaOpItem.CAUSTIC` 的 KDoc 是权威；§4.9.2 正文那句「射线与亮结仅 HIGH」
         * 与它同向，不冲突。）
         */
        const val CAUSTIC_PATH_BUCKETS = 3

        /**
         * 蕾丝线宽阶梯的因子下界 / 跨度（§4.9.2 逐字给的
         * `lineWidth = (0.55 + 0.85·wj)·bandW·kindW` 里的 `0.55` / `0.85`）。
         * ⛔ 另两个因子 `bandW`（浪带宽）与 `kindW`（4 类线各自的宽度系数）是**视觉参数**，
         * 由视觉轮次作为入参传给 [buildLaceStrokes] —— 本文件**不**给它们猜值。
         */
        const val LACE_STROKE_W_MIN = 0.55
        const val LACE_STROKE_W_SPAN = 0.85

        // ── §5.6 调色板（原型 `PAL`，`seaside-preview.html:438-439` 逐位照抄）──────
        // ⚠️ **命名与取景方向相反**（原型自己在 `drawSeaField` 的注释里承认了这一点）：
        //    §5.6 把最浅的 `#2E9AA8` 叫「远海」，但俯拍实拍取景是**画面顶部（外海）
        //    更深更饱和、越靠近浪线越浅越透**。⇒ 渐变的 y 方向见 [buildSeaBaseBrush]，
        //    **不要**按名字从上往下排。
        /** 远海 = 最浅的一档（近浪线一侧）。 */
        const val PAL_SEA_FAR = 0xFF2E9AA8.toInt()

        /** 近海（三色渐变的中段）。 */
        const val PAL_SEA_NEAR = 0xFF1E7A8C.toInt()

        /** 深海 = 最深的一档（画面顶部外海）。 */
        const val PAL_SEA_DEEP = 0xFF14586B.toInt()

        /** 浪脊亮（§5.6：与海面明暗语言同一套，供 [drawSeaField] 的正 `light` 用）。 */
        const val PAL_RIDGE = 0xFF6FD0CC.toInt()

        /** 槽底暗（负 `light` 侧）。 */
        const val PAL_TROUGH = 0xFF0E4A5C.toInt()

        /** 浅滩（浪线前方的浅水带）。 */
        const val PAL_SHOAL = 0xFFA6D8C4.toInt()

        /** 干沙·近浪（§5.6 三图色差，取中间调）。 */
        const val PAL_SAND_NEAR = 0xFFC9A063.toInt()

        /** 干沙·中。 */
        const val PAL_SAND_MID = 0xFFC4A876.toInt()

        /** 干沙·下缘（最浅）。 */
        const val PAL_SAND_FAR = 0xFFD9BE8C.toInt()

        /** 沙面潮湿斑（§5.6：潮湿斑压向此色）。 */
        const val PAL_SAND_DAMP = 0xFF8E7048.toInt()

        /** 浪白·前缘。 */
        const val PAL_FOAM_EDGE = 0xFFF2F7F5.toInt()

        /** 浪白·唇。 */
        const val PAL_FOAM_LIP = 0xFFFFFFFF.toInt()

        /** 浪心（半透明青，⛔ 峰值不得用纯白）。 */
        const val PAL_FOAM_CORE = 0xFFCBE7E3.toInt()

        /** 泡沫网 / 焦散（§5.6 里两者共用同一色）。 */
        const val PAL_FOAM_NET = 0xFFEAF6FF.toInt()

        // ── 上面几支色的逐通道分量（⛔ `const val` 里不能用 `shr`/`and`，那是函数调用）──
        //    水体场的逐像素循环要在热路径上直接用通道值，省掉每像素三次拆包。
        /** [PAL_SEA_FAR] 的 R / G / B（`#2E9AA8` = 46 / 154 / 168）。 */
        const val SEA_FAR_R = 46
        const val SEA_FAR_G = 154
        const val SEA_FAR_B = 168

        /** [PAL_SEA_DEEP] 的 R / G / B（`#14586B` = 20 / 88 / 107）。 */
        const val SEA_DEEP_R = 20
        const val SEA_DEEP_G = 88
        const val SEA_DEEP_B = 107

        /** [PAL_RIDGE] 的 R / G / B（`#6FD0CC` = 111 / 208 / 204）。 */
        const val PAL_RIDGE_R = 111
        const val PAL_RIDGE_G = 208
        const val PAL_RIDGE_B = 204

        /** [PAL_TROUGH] 的 R / G / B（`#0E4A5C` = 14 / 74 / 92）。 */
        const val PAL_TROUGH_R = 14
        const val PAL_TROUGH_G = 74
        const val PAL_TROUGH_B = 92

        /** [PAL_SHOAL] 的 R / G / B（`#A6D8C4` = 166 / 216 / 196）。 */
        const val PAL_SHOAL_R = 166
        const val PAL_SHOAL_G = 216
        const val PAL_SHOAL_B = 196

        /** [PAL_FOAM_EDGE] 的 R / G / B（`#F2F7F5` = 242 / 247 / 245）。 */
        const val PAL_FOAM_EDGE_R = 242
        const val PAL_FOAM_EDGE_G = 247
        const val PAL_FOAM_EDGE_B = 245

        /** [PAL_SAND_DAMP] 的 R / G / B（`#8E7048` = 142 / 112 / 72）。 */
        const val PAL_SAND_DAMP_R = 142
        const val PAL_SAND_DAMP_G = 112
        const val PAL_SAND_DAMP_B = 72

        /** 2π（原型 `TAU`；`SeasideWaves` 只导出了 `fsin`，没有 `TAU`）。 */
        const val TAU = 6.283185307179586

        // ── §4.3.7 沙纹理四层的强度（§5.4 的同名常量）────────────────────────
        /** 层④像素级细颗粒（§5.4 `SAND_GRAIN_A`，**主导纹理**）。 */
        const val SAND_TEX_GRAIN_A = 0.062

        /** 层④第二层更细的砂纸感（§5.4 `SAND_GRAIN2_A`）。 */
        const val SAND_TEX_GRAIN2_A = 0.030

        /** 层②宽而柔的沿岸起伏带强度（§5.4 `SAND_RIPPLE_A`）。 */
        const val SAND_TEX_RIPPLE_A = 0.040

        /** 层②沿岸起伏带的带数（§5.4 `SAND_RIPPLE_N`）。 */
        const val SAND_TEX_RIPPLE_N = 7

        /** 层③潮湿斑块的最大压暗量（§5.4 `SAND_DAMP_A`）。 */
        const val SAND_TEX_DAMP_A = 0.30

        /** 层①湿→干底色渐变的指数与折点（§4.3.7 层①行：`v^0.86`、在 `v = 0.42` 折）。 */
        const val SAND_TEX_V_POW = 0.86
        const val SAND_TEX_V_BREAK = 0.42

        /** 层①的整体湿→干微渐变幅度（§4.3.7 层①行：`1 − 0.10·(1 − v)`）。 */
        const val SAND_TEX_WET_FADE = 0.10

        /** 层③潮湿斑的 `smoothstep` 窗口（原型 591 行：`smoothstep(0.54, 0.82, …)`）。 */
        const val SAND_TEX_DAMP_LO = 0.54
        const val SAND_TEX_DAMP_HI = 0.82

        /** 层③两个八度的采样频率与权重（逐字取 `seaside-preview.html:590`）。 */
        const val SAND_TEX_DAMP_U1 = 4.3
        const val SAND_TEX_DAMP_V1 = 3.1
        const val SAND_TEX_DAMP_S1 = 9151
        const val SAND_TEX_DAMP_M1 = 0.62
        const val SAND_TEX_DAMP_U2 = 11.0
        const val SAND_TEX_DAMP_V2 = 7.0
        const val SAND_TEX_DAMP_S2 = 4423
        const val SAND_TEX_DAMP_M2 = 0.38

        /** 层③「近水处更多」的纵向窗口（原型 592 行：`smoothstep(0.10, 0.80, v)`）。 */
        const val SAND_TEX_DAMP_V_LO = 0.10
        const val SAND_TEX_DAMP_V_HI = 0.80
        const val SAND_TEX_DAMP_V_BASE = 0.30
        const val SAND_TEX_DAMP_V_SPAN = 0.70

        /** 层③压暗后整像素的额外压暗（原型 603-605 行：`× (1 - damp·0.10)`）。 */
        const val SAND_TEX_DAMP_DARKEN = 0.10

        /** 层②扭曲场的两个八度（逐字取 `seaside-preview.html:585`）。 */
        const val SAND_TEX_WARP_U1 = 3.1
        const val SAND_TEX_WARP_V1 = 2.2
        const val SAND_TEX_WARP_S1 = 7717
        const val SAND_TEX_WARP_A1 = 2.6
        const val SAND_TEX_WARP_U2 = 7.7
        const val SAND_TEX_WARP_V2 = 5.0
        const val SAND_TEX_WARP_S2 = 3313
        const val SAND_TEX_WARP_A2 = 1.1

        // ── §5.4 / §5.7 自有颗粒与斑驳层（⛔ `postFx` 已删 grain 通道 ⇒ 图案自带 alpha）──
        /** 128px 颗粒图案的叠加 alpha（§5.7 `postFx.grain = 0.020f` 的同一数值）。 */
        const val GRAIN_POST_A = 0.020f

        /** 16×16 晶格低频斑驳的叠加 alpha（§5.4 `MOTTLE_A`）。 */
        const val MOTTLE_A = 0.030f

        /** 湿沙镜面高光强度（§5.4 `SHEEN_A`）—— 烘焙期按 `lighter` 折进 [buildWetBrush] 的共用渐变
         *  （⛔ 该渐变现在被**两处**共用：[drawWetWash] 那一次与 [drawSheen] 的 `Plus` 那一次）。 */
        const val SHEEN_A = 0.19f

        /** 镜面高光带的深度（原型 `drawSheen` 的 `depth = H * 0.030`）。 */
        const val SHEEN_DEPTH_K = 0.030

        // ── §5.4 退水残沫 / 洼地水洼 ──────────────────────────────────────────
        // ⛔ 2026-10-05 **整层删除**「岸线细亮湿线」（原 `WET_LINE_*` 九个常量 + `drawWetLine`）。
        //   所有者裁决：「这条线的作用是啥？我觉得没用啊，应该去掉」⇒ 接缝处**不要任何线**。
        //   ⛔ 不要再以「原型里有、属忠实移植」为由恢复 —— 那是**第一次黑线**时的错误结论
        //   （真根因是漏赋 `.color`）。本条是**视觉裁决**。
        //   事实依据（写在这里⛔ 不当辩解）：原型 `:2367-2389` 的 `drawWetLine` 是在
        //   `ctx.clip(sandPath)` **之后**描的 ⇒ 原型里只在**沙侧**可见；Kotlin 零 `clipPath`
        //   ⇒ 线以岸线为中心、**一半落在海侧** ⇒ 实现本就走形。详见 [drawResidualStreaks] 前的
        //   「整层删除记录」。`SeaOpItem.WET_LINE` 条目按裁决保留在 [SeaOpItem] 里（预算表归
        //   所有者同步），⛔ 本文件已无任何引用。

        /** 退水残沫强度（§5.4 `RESIDUE_STREAK_A`）。 */
        const val RESIDUE_STREAK_A = 0.075f

        /** 残沫的三 pass（原型 `for pass < 3`）：线宽 `0.9 + 0.8·pass`、逐 pass 衰减 `1 − 0.28·pass`。 */
        const val RESIDUE_STREAK_PASS_N = 3
        const val RESIDUE_STREAK_W_LO = 0.9f
        const val RESIDUE_STREAK_W_SPAN = 0.8f
        const val RESIDUE_STREAK_PASS_DECAY = 0.28

        /** 残沫每 pass 的丝缕数（原型 `seg < 26`）。 */
        const val RESIDUE_STREAK_SEG_N = 26

        /** 残沫每根的横向跨度（原型 `a1 = a0 + 0.012 + 0.065·hash`）⇒ ⛔ **必须短**。 */
        const val RESIDUE_STREAK_SPAN_LO = 0.012
        const val RESIDUE_STREAK_SPAN_SPAN = 0.065

        /** 残沫的纵向分布（原型 `lerp(shoreYs, wetEdge, 0.06 + 0.90·pow(hash,0.65))`）。 */
        const val RESIDUE_STREAK_BAND_LO = 0.06
        const val RESIDUE_STREAK_BAND_SPAN = 0.90
        const val RESIDUE_STREAK_BAND_POW = 0.65

        /** 残沫丝缕的纵向起伏（原型 `0.0026·H·sin(...)`）与漂移速率。 */
        const val RESIDUE_STREAK_WOB_K = 0.0026
        const val RESIDUE_STREAK_WOB_T = 0.00042

        /** 每根丝缕的采样点数减一（原型 `k ≤ 6` ⇒ 7 个点）。 */
        const val RESIDUE_STREAK_STEPS = 6

        /**
         * 洼地水洼的横向半径（原型 `rx = W · (0.008 + 0.024·hash)`）与纵向半径
         * （`ry = H · (0.0020 + 0.0050·hash)`）—— ⛔ `ry/rx` 典型 ≈ 0.2 ⇒ **压扁**。
         */
        const val PUDDLE_RX_LO = 0.008
        const val PUDDLE_RX_SPAN = 0.024
        const val PUDDLE_RY_LO = 0.0020
        const val PUDDLE_RY_SPAN = 0.0050

        /** 水洼本体的 alpha 峰值（原型 `a = 0.20 · wet · wetAmt[ci] · (0.4 + 0.6·hash)`）。 */
        const val PUDDLE_A = 0.20
        const val PUDDLE_A_H_LO = 0.4
        const val PUDDLE_A_H_SPAN = 0.6

        /**
         * 水面反光的 alpha 折（原型 `a * 0.85`）—— ⛔ **未使用**：一次提交拿不到第二种颜色，
         * 反光只靠**更小更扁更靠上**的形状读出来（见 [drawPuddles] 的 KDoc）。
         * 保留这一行是为了让这条取舍在源码里可查，⛔ 不要以为它漏接线了。
         */
        const val PUDDLE_SPEC_A = 0.85

        /** 反光椭圆的压扁比（原型 `ry * 0.26`）与偏上量（`y − ry * 0.22`）。 */
        const val PUDDLE_SPEC_RY_K = 0.26
        const val PUDDLE_SPEC_DY_K = 0.22

        /** 反光椭圆的横向尺寸（`rx·(0.28 + 0.30·hash)`）与横向偏移（`rx·(0.12 + 0.22·hash)`）。 */
        const val PUDDLE_SPEC_RX_LO = 0.28
        const val PUDDLE_SPEC_RX_SPAN = 0.30
        const val PUDDLE_SPEC_DX_LO = 0.12
        const val PUDDLE_SPEC_DX_SPAN = 0.22

        /** 水洼纵向分布（原型 `lerp(shoreYs[ci], wetEdge[ci], 0.12 + 0.80·hash)`）。 */
        const val PUDDLE_BAND_LO = 0.12
        const val PUDDLE_BAND_SPAN = 0.80

        /**
         * ⭐ 水洼的**本体色**（原型 `#93AEB8`）与**反光色**（原型 `#DCEBF0`）——
         * ⛔ §5.6 的调色板表**未登记**这两个色 ⇒ 就地取原型值（与 [PAL_LIP_DARK] 同款处置）。
         * R / G / B 拆成三个 `const` 是因为 `AndroidColor.argb` 要在**每帧**被调用两次
         * （本体 + 反光），拆成标量最省。
         */
        const val PAL_PUDDLE_BODY = 0xFF93AEB8.toInt()
        const val PAL_PUDDLE_SPEC = 0xFFDCEBF0.toInt()

        /** [PAL_PUDDLE_BODY] 的 R / G / B = `147 / 174 / 184`。 */
        const val PAL_PUDDLE_BODY_R = 147
        const val PAL_PUDDLE_BODY_G = 174
        const val PAL_PUDDLE_BODY_B = 184

        /** [PAL_PUDDLE_SPEC] 的 R / G / B = `220 / 235 / 240`。 */
        const val PAL_PUDDLE_SPEC_R = 220
        const val PAL_PUDDLE_SPEC_G = 235
        const val PAL_PUDDLE_SPEC_B = 240

        // ── §4.2 / §5.5 水体场（三档互不成整数比 ⇒ 永不循环）─────────────────────
        /** 大波 / 中波 / 细波的行相位频率（§5.5 `N_BIG` / `N_MID` / `N_FINE` = 8/19/43）。 */
        const val FIELD_N_BIG = 8
        const val FIELD_N_MID = 19
        const val FIELD_N_FINE = 43

        /** 底色深度线索的系数（原型 `drawSeaField:1619`）。 */
        const val SEA_DEPTH_GAIN = 0.78
        const val SEA_DEPTH_POW = 0.90
        const val SEA_DEPTH_BIAS0 = 0.72

        /** 浅滩带的 `smoothstep` 窗口（原型 `drawSeaField:1624`）。 */
        const val SEA_SHOAL_LO = 0.84
        const val SEA_SHOAL_HI = 1.03

        /** 涌浪条纹的三项权重与陡化（原型 `drawSeaField:1631,1638`）。 */
        const val SEA_BAND_STEEP = 0.20
        const val SEA_BAND_W_BIG = 0.44
        const val SEA_BAND_W_MID = 0.36
        const val SEA_BAND_W_FINE = 0.20
        const val SEA_BAND_GAIN = 0.55

        /** 提亮 / 压暗的强度（原型 `drawSeaField:1642,1645`）。 */
        const val SEA_RIDGE_GAIN = 0.30
        const val SEA_TROUGH_GAIN = 0.26

        // ── §4.2 / §5.5 焦散胞壁网（`CNX × CNY` = 20 × 9 ⇒ 180 节点 / 331 边）──────
        const val CAUSTIC_CELL_NX = 20
        const val CAUSTIC_CELL_NY = 9

        /**
         * ⛔ 去规整化幅度上限 = **±0.3 格**（§4.2 / §5.5）。
         * 超过这个量相邻胞格会自交、网直接破掉 —— 这是硬上限，不是可调项。
         */
        const val CAUSTIC_CELL_JIT = 0.30

        /** 低频 warp 的阶数与衰减（§4.2「4 阶、衰减 0.55」）。 */
        const val CAUSTIC_WARP_OCT = 4
        const val CAUSTIC_WARP_DECAY = 0.55

        /** 粼光网 alpha（§5.5 `CAUSTIC_A`；整体再乘 `0.30 + 0.70·energy`）。 */
        const val CAUSTIC_A = 0.075f

        /** §4.2「按亮度分 3 档批量描边」的三档 alpha（`0.62 / 0.44 / 0.28`）。 */
        const val CAUSTIC_ALPHA_HI = 0.62f
        const val CAUSTIC_ALPHA_MID = 0.44f
        const val CAUSTIC_ALPHA_LO = 0.28f

        /**
         * 同三档的**线宽**（`1.15 / 0.85 / 0.6`）—— §4.2「线宽 `1.15 / 0.85 / 0.6`」，
         * 取自原型 `seaside-preview.html:1790`。
         *
         * ⚠️ 原型的档号与 §4.2 那行「按亮度」的排序**不是同一个序**：它是
         * `tier===1 ? 1.15 : (tier===2 ? 0.85 : 0.6)` ⇒ **档 1 最亮最粗、档 0 最暗最细**。
         * ⇒ 下面两张表按**档号**索引，不按「HI/MID/LO」的字面顺序。
         */
        const val CAUSTIC_STROKE_W_HI = 1.15f
        const val CAUSTIC_STROKE_W_MID = 0.85f
        const val CAUSTIC_STROKE_W_LO = 0.6f

        /** 档号 0 / 1 / 2 ⇒ 描边 alpha（原型 1791 行的同款配对）。 */
        val CAUSTIC_TIER_ALPHA = floatArrayOf(CAUSTIC_ALPHA_LO, CAUSTIC_ALPHA_HI, CAUSTIC_ALPHA_MID)

        /** 档号 0 / 1 / 2 ⇒ 线宽（原型 1790 行的同款配对）。 */
        val CAUSTIC_TIER_W = floatArrayOf(CAUSTIC_STROKE_W_LO, CAUSTIC_STROKE_W_HI, CAUSTIC_STROKE_W_MID)

        /** 每条壁的弓起量（原型 `ed.bow · (…) · cellW · 0.30`，`seaside-preview.html:1784`）。 */
        const val CAUSTIC_BOW_K = 0.30f

        /** §4.2「两族交叉斜向射线，共 `RAYS = 30` 根」；⛔ **仅 HIGH**（D9 裁决）。 */
        const val CAUSTIC_RAYS = 30

        /** §4.2「56 颗闪烁亮结」；⛔ **仅 HIGH**（D9 裁决）。⛔ **折进同 3 条 path 的线段**。 */
        const val CAUSTIC_KNOTS = 56

        /** 折进哪一档：射线 ⇒ 中档（原型逐根 alpha `0.45+0.55·hash` ×1.15，落在中档）。 */
        const val CAUSTIC_TIER_RAY = 2

        /** 折进哪一档：亮结 ⇒ 高档（原型逐颗 alpha `0.80+0.90·flick`，比胞壁亮）。 */
        const val CAUSTIC_TIER_KNOT = 1

        /** 射线斜率的宽度归一（原型 `kScale = 1920 / W`，`seaside-preview.html:510`）。 */
        const val CAUSTIC_SLOPE_REF_W = 1920f

        /** 每根射线切成几划（原型 `5 + floor(hash·5)` ⇒ 5..9）。 */
        const val CAUSTIC_RAY_DASH_LO = 5
        const val CAUSTIC_RAY_DASH_SPAN = 5

        /** 每 5 根里 1 根刻意拉陡（原型 `i % 5 === 0`）。 */
        const val CAUSTIC_RAY_STEEP_EVERY = 5
        const val CAUSTIC_RAY_STEEP_LO = 0.65
        const val CAUSTIC_RAY_STEEP_SPAN = 0.55
        const val CAUSTIC_RAY_SLOPE_LO = 0.18
        const val CAUSTIC_RAY_SLOPE_SPAN = 0.42

        /** 亮结的「忽亮忽灭」阈值（原型 `flick < 0.45 ⇒ 不画`）。 */
        const val CAUSTIC_KNOT_FLICK_MIN = 0.45

        /** 亮结的边长（原型 `1 + floor(hash·2.6)` ⇒ 1..3.6 px）。 */
        const val CAUSTIC_KNOT_SIZE_LO = 1.0
        const val CAUSTIC_KNOT_SIZE_SPAN = 2.6

        // ── §4.3.6 泡沫蕾丝网（`10 × 4` 抖动网格 + 四类线）─────────────────────
        const val LACE_NX = 10
        const val LACE_NY = 4

        /** 4 行的 `v`（占浪带宽，§4.3.6：0.03 / 0.22 / 0.40 / 0.58）。 */
        val LACE_ROW_V = floatArrayOf(0.03f, 0.22f, 0.40f, 0.58f)

        /** 网格节点的 `u` / `v` 抖动幅度（原型 `buildLaceNet:2572-2573`）。 */
        const val LACE_NODE_U_JIT = 0.12
        const val LACE_NODE_V_JIT = 0.10
        const val LACE_NODE_U_CLAMP_LO = 0.015
        const val LACE_NODE_U_CLAMP_HI = 0.985
        const val LACE_NODE_V_CLAMP_LO = 0.01
        const val LACE_NODE_V_CLAMP_HI = 0.92

        /** 行间竖直短筋 / 斜筋 / 网眼填充的**出现概率**（原型 `buildLaceNet:2598-2614`）。 */
        const val LACE_RIB_P = 0.72
        const val LACE_DIAG_A_P = 0.42
        const val LACE_DIAG_B_P = 0.22
        const val LACE_FILL_KEEP = 0.76
        const val LACE_FILL_V_JIT = 0.18
        const val LACE_FILL_V_CLAMP_LO = 0.02
        const val LACE_FILL_V_CLAMP_HI = 0.90

        /** 边属性的取值区间（原型 `buildLaceNet:2580-2583`）。 */
        const val LACE_EDGE_PH_K = 6.2832
        const val LACE_EDGE_F1_LO = 0.9
        const val LACE_EDGE_F1_SPAN = 1.6
        const val LACE_EDGE_AMP_LO = 0.5
        const val LACE_EDGE_AMP_SPAN = 1.6
        const val LACE_EDGE_RATE_LO = 0.00006
        const val LACE_EDGE_RATE_SPAN = 0.00010
        const val LACE_EDGE_WJ_LO = 0.60
        const val LACE_EDGE_WJ_SPAN = 0.85
        const val LACE_EDGE_AJ_LO = 0.80
        const val LACE_EDGE_AJ_SPAN = 0.20

        /**
         * ⭐ 蕾丝线宽的**两个线尺度**（§4.3.6「两个线尺度」+ §4.9.2 的 12 档缓存）。
         *
         * 原型（`seaside-preview.html:2622-2624`）是**逐边**算
         * `lineWidth = (0.55 + 0.85·wj) · bandW[b] · kindW[kind]`，其中
         * - `bandW = [0.85, 1.10, 1.30]`：3 个「离前缘距离」档（**近前缘密而亮** ⇒ 细；
         *   后段散而淡 ⇒ 粗），档号由边的平均 `v` 决定；
         * - `kindW = [1.00, 0.75, 0.85, 0.60]`：4 类线（长丝 / 竖筋 / 斜筋 / 网眼填充）。
         *
         * ### 为什么是「4 类 × 3 档 = 12」而不是「1 支按加权均值」
         * 上一轮把两组系数各折成**按边数加权均值**（`LACE_BAND_W_MEAN` / `LACE_KIND_W_MEAN`）
         * ⇒ **逐（档 × 类）分辨率丢失**：长丝与网眼填充被强行同宽、三个距离档被强行同宽。
         * 而 [SeasideOpBudget.laceStrokeCacheSize] = `12 = 4 × 3` **恰好就是这两个尺度的乘积**
         * ⇒ 按 `(kind, band)` 各建一支既**不超门限**、又把两把尺度都完整保住。
         *
         * ⛔ 代价（明确记录）：`wj` 的**逐边**变化被并进每支的固定代表值
         * [LACE_EDGE_WJ_MID] ⇒ 同一 (kind, band) 内**不再有粗细分档**。
         * ⛔ 想要「类 × 档 × wj」三轴就是 144 支 ⇒ 那是每帧几百次 native 分配（§4.9.1 禁令），
         *   绝不可做。取舍按「保住线型身份 + 保住距离分档」这两条更有视觉收益的轴。
         */
        val LACE_BAND_W = floatArrayOf(0.85f, 1.10f, 1.30f)

        /** 四类线的宽度系数（§4.3.6「4 类线（长丝 / 竖筋 / 斜筋 / 网眼填充）的 `kindW`」）。 */
        val LACE_KIND_W = floatArrayOf(1.00f, 0.75f, 0.85f, 0.60f)

        /** `kindW` 的档数（[LACE_KIND_W] 的长度）—— 缓存下标 = `kind · 3 + band`。 */
        const val LACE_KIND_N = 4

        /** `bandW` 的档数（[LACE_BAND_W] 的长度）。 */
        const val LACE_BAND_N = 3

        /**
         * 带的 `v` 分档窗口（原型 `drawFoamLace` 的「档位由边的平均 `v` 决定」：
         * `< 0.20 → 0`、`< 0.38 → 1`、否则 `2`）。
         */
        const val LACE_BAND_V_HI = 0.20
        const val LACE_BAND_V_TOP = 0.38

        /**
         * 每支缓存 `Stroke` 里 `wj` 的代表值（= [LACE_EDGE_WJ_LO] + [LACE_EDGE_WJ_SPAN]/2
         * = `1.025`，区间中点）—— 见上面 KDoc 的取舍说明。⛔ **由那两个常量算出**，
         * 改 `wj` 区间时不必回来改这一行。
         */
        val LACE_EDGE_WJ_MID = (LACE_EDGE_WJ_LO + LACE_EDGE_WJ_SPAN * 0.5).toFloat()

        // ── §5.3 外海泡沫贴图（6 张 256²）────────────────────────────────────
        /** 外海泡沫贴图强度（§5.3 `SEA_FOAM_A`）。 */
        const val FOAM_TILE_PEAK_A = 0.46

        /** 每张贴图的软边斑块数与撕碎丝缕数（§14.3.5「软边斑块 + 撕碎丝缕 + 边缘渐隐」）。 */
        const val FOAM_TILE_BLOB_N = 6
        const val FOAM_TILE_STREAK_N = 12

        /**
         * 边缘 alpha 渐隐的外径（px）。
         * ⛔ **必须 < 贴图半边长**（256/2 = 128），否则贴图边界会在海面上显出方块
         * （`buildFoamTiles` 的 KDoc 里那条硬约束）。
         */
        const val FOAM_TILE_FADE_R = 116f

        // ── §5.3 泡沫剖面（`foamEdgeAt` / `foamCoreAt` / `foamTailAt` / `FOAM_EDGES`）──
        /** 前缘峰值（§5.3 `FOAM_EDGE_A`；[foamEdgeAt] 内再乘 [FOAM_EDGE_HOLD_K]）。 */
        const val FOAM_EDGE_A = 0.85

        /** [foamEdgeAt] 内那个 `0.94`（保持 `CREST_HOLD` 宽才衰减的实白唇口）。 */
        const val FOAM_EDGE_HOLD_K = 0.94

        /** 浪心（双峰的第二个峰）峰值，⛔ **不得超过它**（§5.3 `FOAM_CORE_A`）。 */
        const val FOAM_CORE_A = 0.42

        /** 前缘高光的保持宽 / 归零位（§5.3 `CREST_HOLD` / `CREST_OUT`）。 */
        const val CREST_HOLD = 0.14
        const val CREST_OUT = 0.42

        /** 浪心帐篷的四个边界（§5.3），⛔ **两端都低于中部** ⇒ 与前缘合成双峰。 */
        const val HEART_IN = 0.20
        const val HEART_FULL = 0.45
        const val HEART_HOLD = 0.62
        const val HEART_OUT = 0.88

        /** 拖尾的接入点 / 归零点（§5.3 `TAIL_IN` / `TAIL_OUT`）。 */
        const val TAIL_IN = 0.66
        const val TAIL_OUT = 1.25

        /** [foamTailAt] 的峰值与「接入」段宽（原型 `0.175·ramp·(1−u)²`、`TAIL_IN + 0.16`）。 */
        const val FOAM_TAIL_A = 0.175
        const val FOAM_TAIL_RAMP = 0.16

        /**
         * 剖面量化阶梯的 14 个边界（§5.3 `FOAM_EDGES`，逐位照抄
         * `seaside-preview.html:1238`）—— 13 条**互不重叠**窄带。
         * ⛔ 14 段 rmsErr 0.026；只用 10 段会升到 0.038、浪带内部显出「等高线」分层。
         */
        val FOAM_EDGES = floatArrayOf(
            0f, .035f, .075f, .125f, .185f, .255f, .335f, .425f, .525f, .635f,
            .765f, .905f, 1.07f, 1.25f
        )

        /**
         * 外侧弱浪的**隔一取二**粗阶梯（原型 `FOAM_EDGES.filter((_, i) => i % 2 === 0)`
         * ⇒ 下标 `0/2/4/6/8/10/12` 共 7 个边界、6 条带）。它们本就淡到看不清剖面，省一半开销。
         */
        val FOAM_EDGES_COARSE = floatArrayOf(
            FOAM_EDGES[0], FOAM_EDGES[2], FOAM_EDGES[4], FOAM_EDGES[6],
            FOAM_EDGES[8], FOAM_EDGES[10], FOAM_EDGES[12]
        )

        /** [buildLadder] 的丢弃阈值（原型 `if (a < 0.006) continue`）。 */
        const val FOAM_STRIP_DROP_A = 0.006

        /** 量化后的窄带（每条 3 个 float：`n0 / n1 / a`）—— ⛔ 类 init 期算一次，逐帧只读。 */
        val FOAM_LADDER: FloatArray = buildLadder(FOAM_EDGES)

        /** [FOAM_LADDER] 的条数（= `size / 3`）。 */
        val FOAM_LADDER_N: Int = FOAM_LADDER.size / 3

        /** 外侧弱浪的粗阶梯（同 [FOAM_LADDER] 的排布）。 */
        val FOAM_LADDER_COARSE: FloatArray = buildLadder(FOAM_EDGES_COARSE)

        /** [FOAM_LADDER_COARSE] 的条数。 */
        val FOAM_LADDER_COARSE_N: Int = FOAM_LADDER_COARSE.size / 3

        // ── §4.3.5 泡沫律（倾角律 × 距离律）────────────────────────────────────────
        /** 倾角律增益（§5.3 `FOAM_SLOPE_GAIN`，代码里再乘 `4.0` ⇒ 有效 **2.48 / px**）。 */
        const val FOAM_SLOPE_GAIN = 0.62

        /** 倾角律里那个 `× 4.0`（§4.3.5 的 `slopeLaw[i] = clamp(|Δy/Δx|·GAIN·4, 0, 1)`）。 */
        const val FOAM_SLOPE_X4 = 4.0

        /** 距离律覆盖的离岸距离跨度（占 h，`0.62h → 0.12h`，§5.3 `FOAM_FAR_SPAN`）。 */
        const val FOAM_FAR_SPAN = 0.50

        /** 领头浪的距离律指数（§5.3 `FOAM_FAR_POW`；⛔ 领头浪的 `farLaw` 随后被硬编码为 1）。 */
        const val FOAM_FAR_POW_LEAD = 1.6

        /** **非领头浪**的距离律指数（§4.3.5 ⭐：统一用 1.6 会把它起步段的 `kA` 压到 0.08）。 */
        const val FOAM_FAR_POW_FOLLOW = 0.35

        /** 贴岸领头浪带的泡沫额外加成（§5.3 `SHORE_FOAM_BOOST`）。 */
        const val SHORE_FOAM_BOOST = 1.55

        // ── §4.3.9 ② 带宽的三项衰减因子与**硬下限** ────────────────────────────────
        /** 倾角律进带宽的那一项 `slK = 0.30 + 0.70·slopeLaw`（⛔ **不用**距离律）。 */
        const val SLK_LO = 0.30
        const val SLK_SPAN = 0.70

        /** 低频包络进带宽的那一项 `envK = 0.02 + 0.98·env`（`env = pow(fbmNorm, 2.4)`）。 */
        const val ENVK_LO = 0.02
        const val ENVK_SPAN = 0.98

        /** `env` 的 2.4 次幂（⛔ 早先 0.5 的八度衰减 / 这里不要混，那是 `fbm1` 的衰减）。 */
        const val ENV_POW = 2.4

        /**
         * ⭐ 带宽的**硬下限**（§4.3.9 ② / §5.3「带宽下限 `shrink`」）——
         * 必须加在**乘积**上，⛔ 不是单项上：真正的元凶是 `envK`（`fbmNorm` 偏小处被
         * 2.4 次幂压到趋 0 ⇒ 该因子趋 `0.02`）。实测不加时塌陷率在 `adv 80~90%` 达 48.5%。
         */
        const val SHRINK_MIN = 0.30

        /** `bwj` 的基线与两项正弦起伏（原型 `0.84 + 0.30·nb1 + 0.14·nb2`）。 */
        const val BWJ_BASE = 0.84
        const val BWJ_W1 = 0.30
        const val BWJ_W2 = 0.14

        /** `nb1 / nb2` 的空间频率与漂移速率（原型 3136-3137 两行；⛔ 相位挂 `wiS` 不挂 `wi`）。 */
        const val NB1_K = 0.0173
        const val NB1_T = 0.00017
        const val NB2_K = 0.0290
        const val NB2_T = 0.00062

        /** `nb1 / nb2` 的相位偏移按**稳定索引** `wiS = serial & 3` 取（⛔ 绝不取绘制次序 `wi`）。 */
        const val NB1_PHASE = 2.13
        const val NB2_PHASE = 0.77

        /** `env` 的低频包络参数（原型 3139-3140 行）。 */
        const val ENV_K = 0.00165
        const val ENV_PHASE = 7.3
        const val ENV_T = 0.000012
        const val ENV_SEED = 8100
        const val ENV_SEED_STEP = 53

        // ── §5.3 `drawSwellBody` 的迎光 / 背光两色与体积律 ────────────────────────
        /** 非泡沫浪本体强度（§5.3 `SWELL_BODY_A`）。 */
        const val SWELL_BODY_A = 0.55

        /** 背光面后拖 / 迎光面前出（§5.3 `SWELL_BODY_BACK` / `_FRONT`，单位 = 浪带宽倍数）。 */
        const val SWELL_BODY_BACK = 2.1
        const val SWELL_BODY_FRONT = 0.55

        /** 水体自己的**更平缓**距离律 `(0.35 + 0.65·slopeLaw)·1/(1 + 3.2·(1 − farLaw))`。 */
        const val SWELL_BODY_FAR_LO = 0.35
        const val SWELL_BODY_FAR_SPAN = 0.65
        const val SWELL_BODY_FAR_K = 3.2

        /**
         * 浪本体渐变的 5 个**归一化**停靠点（原型 2864-2868 行）。
         *
         * ⛔ 原型的停靠 alpha 是 `A·[0.95, 0.42, 0.30, 0.85, 0]`，而 `A` 每帧随
         * `fk` 变 ⇒ **不能**把 `A` 烘进渐变。这里按最大项 `0.95` 归一，逐帧用
         * `Paint.setAlpha(A / 0.95)` 乘回去（`Paint` 的 alpha 会调制着色器输出）⇒
         * 数学上逐项相等，且渐变对象仍只建一次（⛔ 每帧 new 一个 `LinearGradient`
         * 就是一次 native 堆着色器分配，§4.9.2 标定的头号超标项）。
         */
        val SWELL_BODY_STOP_POS = floatArrayOf(0.00f, 0.34f, 0.62f, 0.80f, 1.00f)

        /** 与 [SWELL_BODY_STOP_POS] 配对的归一化 alpha（`[0,1]`，0 = 全透明）。 */
        val SWELL_BODY_STOP_A = floatArrayOf(1.00f, 0.42f / 0.95f, 0.30f / 0.95f, 0.85f / 0.95f, 0.00f)

        /** 同一组里 `TROUGH` / `RIDGE` 的归属（§5.6：浪脊亮 / 槽底暗）。 */
        val SWELL_BODY_STOP_IS_RIDGE = booleanArrayOf(false, false, true, true, true)

        /** 归一化用的分母（= 原型停靠 alpha 的最大项 `0.95`）。 */
        const val SWELL_BODY_NORM = 0.95

        // ── §5.3 `drawSeaFoamWash` ───────────────────────────────────────────────────
        /** 波面大白沫晕强度（§5.3 `SEA_FOAM_WASH_A`）。 */
        const val SEA_FOAM_WASH_A = 0.34

        /** 白沫晕往后拖 / 前缘往前出（§5.3 `_BACK` / `_FRONT`，单位 = 浪带宽倍数）。 */
        const val SEA_FOAM_WASH_BACK = 2.4
        const val SEA_FOAM_WASH_FRONT = 0.30

        /** **非领头浪**的前出距离（§5.3 `SEA_FOAM_WASH_FRONT_NL`，= 0.30 × 1.40，峰值压到前缘线上）。 */
        const val SEA_FOAM_WASH_FRONT_NL = 0.42

        /** 逐段 `foamK` 的 alpha 映射 `0.06 + 0.94·fk`（§4.3.5「作用点」行）。 */
        const val WASH_FK_LO = 0.06
        const val WASH_FK_SPAN = 0.94

        /**
         * 浪脊沿岸的分段数（原型 `seaside-preview.html:2769` 的 `const G = 8`）。
         * ⛔ 取**全列均值 + 一次 fill** 会让整条晕同 alpha ⇒ 读成平直白条刷。
         */
        const val WASH_SEGMENTS = 8

        /** 两层的「太薄就跳过」阈值（晕 `< 2px`、本体 `< 3px`；原型 `yB - yT < 2 / < 3`）。 */
        const val WASH_SPAN_MIN = 2.0
        const val SWELL_SPAN_MIN = 3.0

        /** 晕的最小可见 alpha（原型 `A < 0.004`）。 */
        const val WASH_A_MIN = 0.004

        /** 原型 8 段的段数（折成单条 path 后仍用它做 `fk` 的列均分段）。 */
        const val WASH_SEG_N = 8

        /**
         * 晕的两套渐变（按 `dir` 选，原型 2787-2799 两支）。停靠位置恒为
         * **海侧(0) → 岸侧(1)**；⛔ 非领头浪把峰值压到 `1.0` 端（碎浪的亮面是**前缘线**）。
         */
        val WASH_NL_STOP_POS = floatArrayOf(0.00f, 0.35f, 0.62f, 0.85f, 1.00f)

        /** 非领头浪那支的归一化 alpha（原型 `A·[0.05, 0.24, 0.55, 0.95, 1.00]`）。 */
        val WASH_NL_STOP_A = floatArrayOf(0.05f, 0.24f, 0.55f, 0.95f, 1.00f)

        /** 非领头浪那支的 RGB（原型 `rgba(222,240,246)` … `rgba(236,250,251)`）。 */
        val WASH_NL_STOP_RGB = intArrayOf(
            0xFFDEF0F6.toInt(), 0xFFE2F2F6.toInt(), 0xFFE6F5F8.toInt(),
            0xFFEAF8FA.toInt(), 0xFFECFAFB.toInt()
        )

        /** 领头浪那支的停靠位置（原型 `[0.00, 0.16, 0.42, 0.74, 1.00]`）。 */
        val WASH_LD_STOP_POS = floatArrayOf(0.00f, 0.16f, 0.42f, 0.74f, 1.00f)

        /** 领头浪那支的归一化 alpha（原型 `A·[0.10, 1.00, 0.62, 0.24, 0.00]`）。 */
        val WASH_LD_STOP_A = floatArrayOf(0.10f, 1.00f, 0.62f, 0.24f, 0.00f)

        /** 领头浪那支的 RGB（原型 `rgba(226,242,246)` … `rgba(206,228,236)`）。 */
        val WASH_LD_STOP_RGB = intArrayOf(
            0xFFE2F2F6.toInt(), 0xFFE8F6F9.toInt(), 0xFFE0F0F5.toInt(),
            0xFFD6EAF0.toInt(), 0xFFCEE4EC.toInt()
        )

        // ── §5.3 分形破洞（`punchHoles` 的两条硬红线，见 [drawPunchHoles]）───────────
        /** 每条窄带的破洞数上限（§5.3 `HOLE_MAX`）。 */
        const val HOLE_MAX = 18

        /** 洞场的**相位推进周期** ms（§5.3 `HOLE_PERIOD_MS`；⛔ 260ms 太快，已放缓 4.2×）。 */
        const val HOLE_PERIOD_MS = 1100.0

        /** 太窄的条带挖不动会破形（§4.3.5 `gap < 0.07` 不挖）。 */
        const val HOLE_GAP_MIN = 0.07

        /** 洞数随带宽的收敛：`round(HOLE_MAX · kA · clamp(1.15 − gap·1.6, 0, 1))`。 */
        const val HOLE_COUNT_A = 1.15
        const val HOLE_COUNT_GAP = 1.6

        /** 亚像素孔直接丢弃（§5.3：`ry < 1.5 || rx < 2.5`）⇒ 省开销也避免脏边。 */
        const val HOLE_RY_MIN = 1.5
        const val HOLE_RX_MIN = 2.5

        /** 横漂的**有界振荡**幅度与速率（⛔ 红线 2：不得写成 `% 1` 回绕，实测 1599px/帧）。 */
        const val HOLE_HX_JIT = 0.13
        const val HOLE_HX_T = 0.00021
        const val HOLE_HX_CLAMP_LO = 0.02
        const val HOLE_HX_CLAMP_HI = 0.98

        /** 沿条带的连续推进 `hn = n0 + gap·(0.12 + 0.76·u)`。 */
        const val HOLE_HN_LO = 0.12
        const val HOLE_HN_SPAN = 0.76

        /** 半径 `hr = gap·(0.06 + 0.30·grow)·(0.70 + 0.60·h)`（⛔ `0.06` 是**地板值**、不是 0）。 */
        const val HOLE_HR_LO = 0.06
        const val HOLE_HR_SPAN = 0.30
        const val HOLE_HR_K_LO = 0.70
        const val HOLE_HR_K_SPAN = 0.60

        /** 横向拉伸 `rx = ry·(1.1 + 1.5·hash2(h, 730 + strip + lane·11))`。 */
        const val HOLE_RX_LO = 1.1
        const val HOLE_RX_SPAN = 1.5

        /** 每洞的固定相位 `ph = hash2(h, 700 + strip·31 + lane·7)` 的两处种子步长。 */
        const val HOLE_PH_STRIP = 31
        const val HOLE_PH_LANE = 7
        const val HOLE_HR_STRIP = 17
        const val HOLE_HR_LANE = 5
        const val HOLE_RX_STRIP = 1
        const val HOLE_RX_LANE = 11


        // ── §5.3 外海泡沫贴图（`drawOpenSeaFoam` + `drawDisturbance`）──────────────────
        /** 拖尾侧每浪贴图块数（§5.3 行 888：`SEA_FOAM_PATCH = 22`）。
         *  ⛔ **块数 = 22 是原型值**（不再折成 8）：22 块合成**一条 path 一次 fill**，
         *  提交数仍是 1 ⇒ §4.9.2 那句「22 → 8」针对的是**提交数**、⛔ 不是块数。
         *  合批后 22 块只是更多几何、**零额外提交** ⇒ 没有理由为省提交而砍块。 */
        const val SEA_FOAM_PATCH = 22

        /** 外海泡沫贴图强度（§5.3 `SEA_FOAM_A`）。 */
        const val SEA_FOAM_A = 0.46

        /** 逐浪的强度倍率（原型 `L === 1 ? 1.0 : (L === 2 ? 0.82 : 0.60)`），下标 = `lane`。 */
        val OPEN_FOAM_LANE_K = doubleArrayOf(1.0, 1.0, 0.82, 0.60)

        /** 逐块 alpha 的 `0.55 + 0.60·hash` 与 `0.10 + 0.90·fk`（§4.3.5「作用点」行）。 */
        const val OPEN_FOAM_A_H_LO = 0.55
        const val OPEN_FOAM_A_H_SPAN = 0.60
        const val OPEN_FOAM_FK_LO = 0.10
        const val OPEN_FOAM_FK_SPAN = 0.90

        /** 拖尾侧的深度位置 `dep = hash·1.90 − 0.35` 与尺寸 `W0·(2.40 + 2.40·h^1.2)`。 */
        const val OPEN_FOAM_DEP_LO = -0.35
        const val OPEN_FOAM_DEP_SPAN = 1.90
        const val OPEN_FOAM_SIZE_LO = 2.40
        const val OPEN_FOAM_SIZE_SPAN = 2.40
        const val OPEN_FOAM_SIZE_POW = 1.2

        /** 逐块的跳过阈值与 alpha 上限（原型 `a < 0.012` / `min(a, 0.45)`）。 */
        const val OPEN_FOAM_A_MIN = 0.012
        const val OPEN_FOAM_A_CAP = 0.45

/**
 * 外海泡沫**逐块 alpha 的分档数** —— 原型 22 次 `drawImage` 各有各的 alpha
 * （`seaside-preview.html` 的 `drawOpenSeaFoam`），⛔ 折成一次 fill 时**不得**取算术平均
 * （那会把 `dens` / `hash2` / `fk` 三层差异全抹掉 ⇒ 读成「一排等大的白棉球」）。
 */
const val OPEN_FOAM_TIERS = 3

        /** 密度场与聚团位置场的参数（原型 2890 / 2893-2894 行）。 */
        const val OPEN_FOAM_DENS_K = 0.37
        const val OPEN_FOAM_DENS_L = 2.1
        const val OPEN_FOAM_DENS_F = 1.9
        const val OPEN_FOAM_DENS_T = 0.00006
        const val OPEN_FOAM_CLUMP_K = 0.075
        const val OPEN_FOAM_CLUMP_L = 5.1
        const val OPEN_FOAM_JIT = 0.16

        /** ⭐ 扰动前锋（`drawDisturbance`，§5.3 行 889-890 `DISTURB_*`）。
         *  ⛔ 因「原型保真回补」整层复活（原型 §4.3.5 / §5.3；原型 18 块贴图 blit）。 */
        const val DISTURB_A = 2.40

        /** 每浪贴图块数（§5.3 行 889 `DISTURB_PATCH = 18`）。 */
        const val DISTURB_PATCH = 18
        const val DISTURB_DEP0 = 0.30
        const val DISTURB_DEP1 = 1.45
        const val DISTURB_SIZE = 1.35
        const val DISTURB_SIZE_LO = 1.2
        const val DISTURB_SIZE_SPAN = 1.3
        const val DISTURB_SIZE_POW = 1.3
        const val DISTURB_FAR_POW = 0.35
        const val DISTURB_A_MIN = 0.008
        const val DISTURB_A_CAP = 0.40
        const val DISTURB_A_H_LO = 0.4
        const val DISTURB_A_H_SPAN = 0.6
        const val DISTURB_FK_LO = 0.35
        const val DISTURB_FK_SPAN = 0.65

        /**
         * 密度场与聚团位置场的参数（⛔ §5.3 **未登记**，就地取原型 `drawDisturbance` 的
         * `fbmNorm((k·0.41 + L·1.7)·2.3 + t·0.00008, 8801 + L·41, 2)` /
         * `fbmSigned(k·0.083 + L·6.3, 9401, 2)` / `0.14` 抖动）。
         * ⚠️ 这三个种子（`8801` / `9401`）与拖尾侧那组（`7701` / `9301`）**故意不同** ——
         *   原型要的是「同源但独立」的慢漂移场，两层碎沫团才会错开地移动。
         */
        const val DISTURB_DENS_K = 0.41
        const val DISTURB_DENS_L = 1.7
        const val DISTURB_DENS_F = 2.3
        const val DISTURB_DENS_T = 0.00008
        const val DISTURB_DENS_SEED = 8801
        const val DISTURB_DENS_SEED_L = 41
        const val DISTURB_CLUMP_K = 0.083
        const val DISTURB_CLUMP_L = 6.3
        const val DISTURB_CLUMP_SEED = 9401
        const val DISTURB_JIT = 0.14

        /** 逐块的 `h = k·[DISTURB_H_K] + lane·[DISTURB_H_L] + [DISTURB_H_B0]`（原型同一式）。 */
        const val DISTURB_H_K = 29
        const val DISTURB_H_L = 977
        const val DISTURB_H_B0 = 300

        /** 扰动前锋逐浪的强度倍率（原型 `L === 1 ? 1.0 : 0.78`），下标 = `lane`。 */
        val DISTURB_LANE_K = doubleArrayOf(1.0, 1.0, 0.78, 0.78)

        /** 预烘轮廓里每个软边斑块的折线边数（原型是软椭圆，这里量化成多边形）。 */
        const val FOAM_TILE_BLOB_POLY_N = 12

        /** 预烘轮廓里每条撕碎丝缕的四点（沿轴 + 半宽）。 */
        const val FOAM_TILE_STREAK_POLY_N = 4

        /** 每张贴图的轮廓子路径数（[FOAM_TILE_BLOB_N] 个斑块 + [FOAM_TILE_STREAK_N] 条丝缕）。 */
        const val FOAM_TILE_OUT_SUB = FOAM_TILE_BLOB_N + FOAM_TILE_STREAK_N

/**
 * [drawFoamTileBlits] 的**层选择**（两层共用一个 blit 循环，逐块公式不同）。
 * ⛔ 不是新的调色/尺寸口径 —— 只是把「哪一套公式」显式传进去。
 */
const val FOAM_TILE_OFF_OPEN = 0
const val FOAM_TILE_OFF_DISTURB = 1

        /** 全部贴图的轮廓顶点容量（`贴图数 × (斑块 + 丝缕) 的顶点数`）。 */
        val FOAM_TILE_OUT_V_CAP =
            SeasideOpBudget.FOAM_TILES * (FOAM_TILE_BLOB_N * FOAM_TILE_BLOB_POLY_N +
                FOAM_TILE_STREAK_N * FOAM_TILE_STREAK_POLY_N)

        // ── §5.3 破碎唇（`drawCrestLip`）─────────────────────────────────────────────
        /** 破碎唇宽度（浪带宽的倍数，§5.3 `LIP_W`）。 */
        const val LIP_W = 0.16

        /** 两 pass 相对前缘的偏移系数（§5.3「`LIP_W·0.35 / LIP_W·0.95`」）。 */
        const val LIP_OFF_HI = 0.35
        const val LIP_OFF_LO = 0.95

        /** 两 pass 的线宽（§5.3「线宽 `0.9 / 2.5`」）与 alpha（「`0.85 / 0.42`」）。 */
        const val LIP_W_LO = 0.9f
        const val LIP_W_SPAN = 1.6f
        const val LIP_A_HI = 0.85
        const val LIP_A_LO = 0.42

        /** 第二 pass 那道暗带的色（原型 `'#cfe6ea'`，§5.6 未登记 ⇒ 就地取原型值）。 */
        const val PAL_LIP_DARK = 0xFFCFE6EA.toInt()

        // ── §4.3.6 蕾丝的成熟度与两组逐档系数 ──────────────────────────────────────
        /** 领头浪的成熟度（§4.3.6：`dens` 领头 `1.30`）。 */
        const val LACE_DENS_LEAD = 1.30

        /** 外侧浪的成熟度基线与跨度（`0.62 + 0.30·(1 − lane/3)`，⚠️ `lane` 恒 `!= 0`）。 */
        const val LACE_DENS_LO = 0.62
        const val LACE_DENS_SPAN = 0.30
        const val LACE_DENS_LANE_N = 3.0

        /** `aBase = clamp(kA·1.25, 0, 1)·dens·0.66` 的两个因子。 */
        const val LACE_A_KA = 1.25
        const val LACE_A_K = 0.66
        const val LACE_A_CAP = 0.95

        /** 3 个「离前缘距离」档的 alpha 系数（§4.3.6 `bandA = [1.00, 0.62, 0.30]`）。 */
        val LACE_BAND_A = floatArrayOf(1.00f, 0.62f, 0.30f)

        /** 4 类线的 alpha 系数（§4.3.6 `kindA = [1.00, 0.80, 0.80, 0.55]`）。 */
        val LACE_KIND_A = floatArrayOf(1.00f, 0.80f, 0.80f, 0.55f)

        /** 缓慢呼吸的幅度 / 速率，以及 `v` 的钳位窗口（原型 2635-2637 行）。 */
        const val LACE_DV_A = 0.05
        const val LACE_DV_T = 0.00003
        const val LACE_DV_LANE = 0.9
        const val LACE_V_CLAMP_LO = 0.015
        const val LACE_V_CLAMP_HI = 0.95

        /** 内部点正弦摆动的幅度上限（`min(amp·1.9, lenPx·0.12, 9)`）与步数上限（`lenPx / 22`）。 */
        const val LACE_WOB_K = 1.9
        const val LACE_WOB_LEN = 0.12
        const val LACE_WOB_MAX = 9.0
        const val LACE_STEP_PX = 22.0
        const val LACE_STEP_MAX = 10
        const val LACE_LEN_MIN = 1.5
        const val LACE_A_MIN = 0.012

        // ── §5.4 沙纹 / 飞沫 / 残沫 ───────────────────────────────────────────────
        /** 沙纹线数（§5.4 `SAND_LINES`，⛔ 固定，只有 18% 靠 hash 控制**可见性**）。 */
        const val SAND_LINES = 26

        /** 只有 18% 的线靠 hash 控制可见性（⛔ **不改条数** ⇒ 不产生摩尔纹）。 */
        const val SAND_LINE_VIS_P = 0.18

        /** 沙纹 alpha 上限（§5.4 `SAND_LINE_A`，⛔ 防摩尔纹）与三档权重。 */
        const val SAND_LINE_A = 0.055
        val SAND_LINE_TIER_K = floatArrayOf(0.29f, 0.44f, 0.58f)

        /** 沙纹三档的色（§5.6：⛔ **三档都是浅色调**，浅沙上的深色 1px 线根本不可见）。 */
        val SAND_LINE_TIER_RGB = intArrayOf(0xFFEEDAB4.toInt(), 0xFFF7E8CB.toInt(), 0xFFFFF5E2.toInt())

        /** 沙纹的纵向范围（`SHORE_K + 0.050` 起）与整组漂移（⛔ **不循环** ⇒ 无闪烁）。 */
        const val SAND_LINE_TOP_K = 0.050
        const val SAND_LINE_DRIFT = 3.0
        const val SAND_LINE_DRIFT_T = 0.000095
        const val SAND_LINE_WOB_A = 1.0
        const val SAND_LINE_WOB_T = 0.00021
        const val SAND_LINE_WOB_PHASE = 0.83

        /** 每条线的抖动、断段数与每段 7 个采样点 ⇒ 每段 6 条线段。 */
        const val SAND_LINE_JIT = 0.0056
        const val SAND_LINE_SEG_LO = 2
        const val SAND_LINE_SEG_SPAN = 3
        const val SAND_LINE_STEP_SPAN = 6
        const val SAND_LINE_MIN_PX = 10f
        const val SAND_LINE_SEG_A0 = 0.55
        const val SAND_LINE_SEG_SPAN_K = 0.85
        const val SAND_LINE_PWOB_K = 0.0016
        const val SAND_LINE_PWOB_T = 0.00012
        const val SAND_LINE_PWOB_PHASE = 1.9
        const val SAND_LINE_ALPHA_K = 0.55
        const val SAND_LINE_ALPHA_SPAN = 0.45

        /** 飞沫强度（§5.4 `SPLASH_A`）与各自的跳过阈值。 */
        const val SPLASH_A = 0.85
        const val SPLASH_A_MIN = 0.006
        const val SPLASH_REACH_POW = 0.62
        const val SPLASH_FADE_POW = 1.15
        const val SPLASH_SHRINK = 0.35

        /** 飞沫点表的烘焙参数（原型 668-677 行，⛔ 全部 `hash2`、零随机源）。 */
        const val SPLASH_PERIOD_LO = 900.0
        const val SPLASH_PERIOD_SPAN = 1500.0
        const val SPLASH_PHASE_SPAN = 4000.0
        const val SPLASH_REACH_LO = 0.008
        const val SPLASH_REACH_SPAN = 0.075
        const val SPLASH_LIFT_SPAN = 0.022
        const val SPLASH_SIZE_LO = 0.9
        const val SPLASH_SIZE_SPAN = 2.8
        const val SPLASH_BRIGHT_LO = 0.30
        const val SPLASH_BRIGHT_SPAN = 0.70

        /** `edgeYs` 还没建立时的兜底前缘（原型 `|| H * 0.58`）。 */
        const val SPLASH_FALLBACK_Y = 0.58

        /** 残沫强度（§5.4 `RESIDUE_A`）与三笔球体感的配色 / 位置系数。 */
        const val RESIDUE_A = 0.17
        const val PAL_RESIDUE_SHADOW = 0xFF7A5E38.toInt()
        const val PAL_RESIDUE_CORE = 0xFFF2F8F4.toInt()
        const val PAL_RESIDUE_HI = 0xFFFFFFFF.toInt()
        const val RESIDUE_SHADOW_A = 0.50
        const val RESIDUE_CORE_A = 0.85
        const val RESIDUE_HI_K = 1.2

        /** 残沫的纵向分布（`SHORE_K + 0.085` 起，跨度 `(1 − SHORE_K) − 0.135`）。 */
        const val RESIDUE_BASE_K = 0.085
        const val RESIDUE_SPAN_K = 0.135

        /** 残沫点表的烘焙参数（原型 683-700 行：LCG + 最小间距 `0.092` 拒绝采样）。 */
        const val RESIDUE_LCG_SEED = 0x5EA51DE
        const val RESIDUE_MIN_SEP = 0.092
        const val RESIDUE_U_LO = 0.03
        const val RESIDUE_U_SPAN = 0.94
        const val RESIDUE_V_POW = 0.62
        const val RESIDUE_V_SPAN = 0.94
        const val RESIDUE_S_LO = 1.0
        const val RESIDUE_S_SPAN = 2.6
        const val RESIDUE_A_LO = 0.5
        const val RESIDUE_A_SPAN = 0.5
        const val RESIDUE_TRIES = 8000

        // ── §12.3 T2.19 沙滩螃蟹（原型 `drawCrab`，`seaside-preview.html:2144-2327`）───
        /** 第一次亮相（ms）；再晚等于没有这回事。 */
        const val CRAB_T0_MS = 6800.0

        /** 两次过境之间的间隔区间（ms，哈希抖动 ⇒ 不规律）。 */
        const val CRAB_GAP_LO = 25000.0
        const val CRAB_GAP_HI = 45000.0

        /** 步速（占 h / s）⇒ 900px 上约 135 px/s；逐只再乘 `0.86 + 0.30·hash`。 */
        const val CRAB_SPEED = 0.150
        const val CRAB_SPEED_LO = 0.86
        const val CRAB_SPEED_SPAN = 0.30

        /** 全身跨度（占 h）⇒ 约 30px；身体半宽 `span · 0.2326`。 */
        const val CRAB_SPAN = 0.0335
        const val CRAB_R_K = 0.2326

        /** 步频（rad/ms）≈ 2.4 Hz 与四条腿沿体轴的髋位（单位 `R`）。 */
        const val CRAB_GAIT = 0.0150
        val CRAB_LEG_Y = floatArrayOf(-0.55f, -0.12f, 0.33f, 0.72f)

        /** 相邻步足的相位差（rad，= `π/2`）。 */
        const val CRAB_LEG_SPREAD = 1.5708

        /** 淡入淡出（ms）。 */
        const val CRAB_FADE_MS = 420.0

        /** 脚下离水线的最小距离（px）—— ⛔ 不许走进水里。 */
        const val CRAB_CLEAR_PX = 8f

        /** 离平均岸线的干沙纵深（占 h）区间；⛔ **不能**写死绝对 y（岸线沿 x 起伏 `±0.13h`）。 */
        const val CRAB_OFF_LO = 0.030
        const val CRAB_OFF_HI = 0.132
        const val CRAB_OFF_POW = 0.75

        /** 纵坐标低通的时间常数（ms）—— ⛔ **只有同蟹**才平滑（换蟹瞬间直接落位）。 */
        const val CRAB_SMOOTH_TAU = 220.0

        /** 姿态：随蹬腿的上下 / 侧摆、倾斜、步态相位偏移。 */
        const val CRAB_BOB_K = -0.13
        const val CRAB_SWAY_K = 0.11
        const val CRAB_TILT_SPAN = 0.42
        const val CRAB_PHASE_K = 6.2832

        /** 步足几何（原型 2241-2247 行）：撑出去/收回来 + 前后摆 + 抬起。 */
        const val CRAB_LEG_RAD_LO = 1.98
        const val CRAB_LEG_RAD_SPAN = 0.22
        const val CRAB_LEG_LIFT = 0.16
        const val CRAB_LEG_FORE = 0.34
        const val CRAB_LEG_HIP = 1.02
        const val CRAB_LEG_KNEE = 0.70
        const val CRAB_LEG_FORE_K = 0.45
        const val CRAB_LEG_LIFT_K = 0.5
        const val CRAB_LEG_BOW = 0.12

        /** 甲壳椭圆（`1.06R × 0.76R`）—— ⛔ **真正的 `addOval` 填充**（原型 2257-2260 行）。 */
        const val CRAB_SHELL_A = 1.06f
        const val CRAB_SHELL_B = 0.76f

        /** 壳描边的线宽下界（px）与「小于它就用它」。 */
        const val CRAB_MIN_W = 0.8f

        /** 步足线宽（近侧 `0.26R` / 远侧 `0.21R`）与下界 `1.0px`。 */
        const val CRAB_LEG_W_NEAR = 0.26f
        const val CRAB_LEG_W_FAR = 0.21f
        const val CRAB_MIN_LEG_W = 1.0f

        /** 壳沿 `0.13R` / 背光高光 `0.18R`（原型 2262 / 2266 行）—— 两者共用下界 [CRAB_MIN_W]。 */
        const val CRAB_RIM_W = 0.13f
        const val CRAB_HI_W = 0.18f

        /**
         * 背光高光那道弧（原型 2268 行）：`ellipse(0.82R, 0.50R, 3.55 → 5.05)`。
         * ⛔ 起止角是**弧度**（原型 `ctx.ellipse` 的口径），落进 `NativePath.addArc` 前才转角度。
         */
        const val CRAB_HI_A = 0.82f
        const val CRAB_HI_B = 0.50f
        const val CRAB_HI_T0 = 3.55
        const val CRAB_HI_T1 = 5.05

        /** 螯臂（原型 2283-2284 行）：从壳的前肩斜撑到螯。 */
        const val CRAB_ARM_X0 = 0.50f
        const val CRAB_ARM_Y0 = -0.64f
        const val CRAB_ARM_X1 = 0.84f
        const val CRAB_ARM_Y1 = -1.06f

        /** 螯（原型 2274-2307 行）：中心 `(±0.86R, −1.10R)`、静止张角 `0.40rad`、`CP/CQ`。 */
        const val CRAB_BITE_K = 0.20
        const val CRAB_BITE_PH = 1.2
        const val CRAB_CLAW_OX = 0.86f
        const val CRAB_CLAW_OY = -1.10f
        const val CRAB_CLAW_ANG = 0.40f
        const val CRAB_CLAW_CP = 0.72f
        const val CRAB_CLAW_CQ = 0.40f

        /**
         * 眼柄与眼点（原型 2314-2322 行）。
         * ⛔ 眼点是**半径 `0.24R` 的实心圆**（`arc(0 … 2π)` 的 `addCircle`），
         * ⛔ 不可用「零长线段 + round cap」凑 —— 那种画法在 1px 级线宽下会退化成方点，
         * 12× 截图上读不出「眼睛」，只是两个黑方块。
         */
        const val CRAB_EYE_X0 = 0.30f
        const val CRAB_EYE_Y0 = -0.56f
        const val CRAB_EYE_X = 0.36f
        const val CRAB_EYE_Y1 = -1.22f
        const val CRAB_EYE_CY = -1.26f
        const val CRAB_EYE_R = 0.24f
        const val CRAB_EYE_BITE = 0.6f

        /** 壳体色（§5.6 / 原型 `PAL.crabShell` 等 7 项）。 */
        const val PAL_CRAB_SHELL = 0xFFB4603A.toInt()

        /** 背上一道高光（`crabShellHi`）。 */
        const val PAL_CRAB_SHELL_HI = 0xFFD4905F.toInt()

        /** 深色壳沿 / 眼 / 钳口描边（`crabRim`）。 */
        const val PAL_CRAB_RIM = 0xFF6A3520.toInt()

        /** 近侧步足与螯臂（`crabLeg`）。 */
        const val PAL_CRAB_LEG = 0xFF83422A.toInt()

        /** 远侧步足（`crabLegFar`）。 */
        const val PAL_CRAB_LEG_FAR = 0xFF63301D.toInt()

        /** 螯（`crabClaw`）—— ⛔ 不能与壳同色，否则 12× 截图上壳与螯糊成一整块橙。 */
        const val PAL_CRAB_CLAW = 0xFFC97245.toInt()

        /** 影子（`crabShadow`）—— ⛔ 必须是**半透明**的，直接按 `alpha = 1` 画会成实心污渍。 */
        const val PAL_CRAB_SHADOW = 0xFF4A3520.toInt()

        /** 影子的 alpha 与椭圆（原型 2215-2217 行）。 */
        const val CRAB_SHADOW_A = 0.13
        const val CRAB_SHADOW_DX = 0.30
        const val CRAB_SHADOW_DY = 0.52
        const val CRAB_SHADOW_RX = 1.18
        const val CRAB_SHADOW_RY = 0.54

        /**
         * §4.7① `FxLevel` → [SeaLevel] 的**唯一**映射（§4.7②：BASIC 三档全可见）。
         * ⛔ 刻意不在本文件写第二份（[SeasideOpBudget] 的 KDoc 要求渲染层只做一次性映射）。
         */
        fun seaLevelOf(level: FxLevel): SeaLevel = when (level) {
            FxLevel.OFF -> SeaLevel.LOW
            FxLevel.LITE -> SeaLevel.MEDIUM
            FxLevel.FULL -> SeaLevel.HIGH
        }

        // ── 泡沫剖面的三个纯函数 + 阶梯量化（§14.3.4 的前四个签名）──────────────────

        /** 前缘唇（实白）：保持 [CREST_HOLD] 宽才衰减，[CREST_OUT] 归零。 */
        fun foamEdgeAt(n: Float): Float {
            if (n >= CREST_OUT) return 0f
            val u = SeasideWaves.smoothstep(CREST_HOLD.toDouble(), CREST_OUT.toDouble(), n.toDouble())
            return (FOAM_EDGE_A * FOAM_EDGE_HOLD_K * (1.0 - u)).toFloat()
        }

        /** 浪心帐篷：⛔ 两端都低于中部 ⇒ 与前缘合成**双峰**；峰值锁 [FOAM_CORE_A]。 */
        fun foamCoreAt(n: Float): Float {
            if (n <= HEART_IN || n >= HEART_OUT) return 0f
            val a = SeasideWaves.smoothstep(HEART_IN.toDouble(), HEART_FULL.toDouble(), n.toDouble())
            val b = 1.0 - SeasideWaves.smoothstep(HEART_HOLD.toDouble(), HEART_OUT.toDouble(), n.toDouble())
            return (FOAM_CORE_A * a * b).toFloat()
        }

        /** 拖尾：先平滑接入浪心，再二次渐隐，正好在 [TAIL_OUT] 归零。 */
        fun foamTailAt(n: Float): Float {
            if (n >= TAIL_OUT) return 0f
            val ramp = SeasideWaves.smoothstep(
                TAIL_IN.toDouble(), (TAIL_IN + FOAM_TAIL_RAMP).toDouble(), n.toDouble()
            )
            val u = SeasideWaves.clamp(
                (n - TAIL_IN).toDouble() / (TAIL_OUT - TAIL_IN).toDouble(), 0.0, 1.0
            )
            return (FOAM_TAIL_A * ramp * (1.0 - u) * (1.0 - u)).toFloat()
        }

        /** 泡沫总剖面（供 [buildLadder] 量化）。 */
        fun foamTargetAt(n: Float): Float = foamEdgeAt(n) + foamCoreAt(n) + foamTailAt(n)

        /**
         * 把解析剖面量化成若干条**互不重叠**的窄带（每条 3 个 float：`n0 / n1 / a`）。
         *
         * ⛔ `a < [FOAM_STRIP_DROP_A]` 的带直接丢弃（不可见窄带）。
         * ⛔ **只在类 init 期跑一次**（纯函数、零 Android 依赖）⇒ 逐帧零分配。
         */
        fun buildLadder(edges: FloatArray): FloatArray {
            val tmp = FloatArray(edges.size * 3)
            var n = 0
            var i = 0
            while (i < edges.size - 1) {
                val mid = (edges[i] + edges[i + 1]) * 0.5f
                val a = foamTargetAt(mid)
                if (a >= FOAM_STRIP_DROP_A) {
                    tmp[n * 3] = edges[i]
                    tmp[n * 3 + 1] = edges[i + 1]
                    tmp[n * 3 + 2] = a
                    n++
                }
                i++
            }
            return tmp.copyOf(n * 3)
        }
    }

    // ═══════════════════════════ 成员状态 ═══════════════════════════

    /** 音频映射（**只产外观量**，⛔ 不参与任何时序）。构造期建，⛔ 每帧零分配。 */
    private val audio = SeasideAudioMap()

    /**
     * 模拟核心。⛔ **必须**惰性建：渲染器构造时还不知道画布尺寸（[SeasideWaves] 要 `w`/`h`）。
     * 唯一创建点 = [rebuildGeometry]（只由 [drawContent] 的尺寸变化分支调）。
     */
    private var waves: SeasideWaves? = null

    /** 画布宽（px），[rebuildGeometry] 写入；[drawContent] 用它判尺寸变化。 */
    private var seaW = -1f

    /** 画布高（px），同上。 */
    private var seaH = -1f

    /**
     * 上一帧的 `density`（dp→px 换算要用，而 [DrawScope.density] 不在 [rebuildGeometry]
     * 的作用域里 ⇒ 由 [drawContent] 在调用前写进来）。⛔ **不是**缓存键之外的隐式状态：
     * 它参与尺寸/密度变化判据，改显示字号必须重烘。
     */
    private var seaDensity = -1f

    /** 本帧画质档（[SeaLevel]，不是 `FxLevel` / `Color` —— 见 `SeasideOpBudget` 的 KDoc）。 */
    private var seaLevel = SeaLevel.LOW

    /**
     * ⭐ **本帧逐元素的提交数**，由 [SeasideOpBudget.opsOf] 按 [seaLevel] 逐项算出，
     * 写进这个**预分配** `IntArray`（下标 = [SeaOpItem.ordinal]）。
     *
     * ⛔ 为什么渲染层要读预算表：G11 / G12 / G13 是**纯函数门**，渲染层与门禁各算一份
     * 就一定会漂。⇒ 这里**只读同一张表**，绘制侧的批次数 / 段数直接取自它，
     * 结构上不可能出现「门禁算 3 次、实现画 8 次」。
     */
    private val levelOps = IntArray(SeaOpItem.entries.size)

    /** 零时刻（ms）。**只在首帧**由 `fx.nowMs` 记，见类 KDoc「时基」。 */
    private var t0Ms = 0L
    private var t0Set = false

    // ── 自持位图（尺寸变化时烘焙；[onExitContent] 逐张 try/catch `recycle()`）──────
    // ⚠️ `SeaOpItem` / `SeasideOpBudget` 的 G13 门按下列清单核算 native 堆：
    //   沙纹理（`texH = h − SAND_TEX_TOP·h`，> SAND_TEX_MAX_PX 按比例降采样）/
    //   泡沫贴图 × FOAM_TILES（LOW 不分配）/ 颗粒图案 / 晶格斑驳 / 水体场低分辨率画布。

    /** 干沙烘焙纹理，高度恒为 `h − SAND_TEX_TOP·h`（⛔ **不是** `h`，G13 的关键接缝）。 */
    private var sandTex: ImageBitmap? = null

    /** 水体场低分辨率画布（LOW 档不分配，§4.7②「只留底色渐变」）。 */
    private var fieldTex: ImageBitmap? = null

    /** 128px 颗粒图案。 */
    private var grainTex: ImageBitmap? = null

    /** 16×16 晶格斑驳图案。 */
    private var mottleTex: ImageBitmap? = null

    /** 泡沫贴图 [SeasideOpBudget.FOAM_TILES] 张（LOW 档整组不分配）。 */
    private val foamTiles: Array<ImageBitmap?> = arrayOfNulls(SeasideOpBudget.FOAM_TILES)

    // ── 烘焙期缓存的 Brush / Stroke / 几何（**逐帧只读**，⛔ 每帧绝不 new）─────────
    /**
     * ⛔ **中性兜底刷**：只给「尚未接上专用刷」的元素用；每个元素都换成
     * [buildBrushes] 烘好的专用刷之后，它就可以退休。
     * ⛔ 结构上保证逐帧路径**零 `Brush.` 构造**
     * （`PerfBudgetContractTest` 不查这个，E42 的 KDoc 正好踩中）。
     *
     * 取值：[PAL_FOAM_NET]（§5.6「泡沫网 / 焦散」共用色）—— 白到足以被逐帧 `alpha` 调制。
     */
    private val inkBrush = SolidColor(Color(PAL_FOAM_NET))

    /**
     * ⭐ **各元素专用 `Brush`**，全部在 [buildBrushes]（由 [rebuildGeometry] 调）里烘好 ——
     * 逐帧路径只读字段，⛔ 一个 `Brush.` 都不许出现（[SeasideTest] ⑤ 专条断言）。
     *
     * 取值全部来自 §5.6 调色板（原型 `PAL`，`seaside-preview.html:438-439`）：
     *
     * | 字段 | 元素 | §5.6 色 |
     * |---|---|---|
     * | [swellBodyBrush] | [drawSwellBody] 浪本体的迎光/背光体积感 | `trough`→`ridge` 竖向渐变 |
     * | [foamStripBrush] | [drawFoamStrip] 窄带浪心（双峰的第二个峰） | `foamCore` 半透明青 |
     * | [seaFoamWashBrush] | [drawSeaFoamWash] 波面大白沫晕 | `foamEdge` |
     * | [openSeaFoamBrush] | [drawOpenSeaFoam] 外海泡沫贴图块 | `foamEdge` |
     * | [crestLipBrush] | [drawCrestLip] 破碎唇实白高光 | `foamLip` |
     * | [laceBrush] | [drawFoamLace] 蕾丝网描边 | `foamNet` |
     * | [causticBrush] | [drawCausticNet] 胞壁网描边 | `foamNet` |
     * | [residueBrush] | [drawResidue] 残沫亮芯 / 左上缘高光 | `foamEdge` |
     * | — | [drawPuddles] 洼地水洼与压扁椭圆反光 | `shoal` 浅滩（⛔ 走**原生** [puddleNativePaint]，见那条 KDoc） |
     *
     * ⚠️ 逐帧侧仍用 `alpha` 参数做强度调制（`SEA_FOAM_A` / `SHEEN_A` 等），
     * ⛔ **不**为「不同 alpha 档」各建一支刷 —— 那会变成逐帧挑刷、与预算表打架。
     */
    private var swellBodyBrush: Brush? = null
    private var foamStripBrush: Brush? = null
    private var seaFoamWashBrush: Brush? = null
    private var openSeaFoamBrush: Brush? = null
    private var crestLipBrush: Brush? = null
    private var laceBrush: Brush? = null
    private var causticBrush: Brush? = null
    private var residueBrush: Brush? = null

    /** 水体场**底色竖向渐变**（覆盖 `0..seaBottomPx`）。LOW 档**只**画它（省一次全屏 blit）。 */
    private var seaBaseBrush: Brush? = null

    /**
     * 湿沙 + 镜面高光**预合成**的那一个缓存竖向渐变（§4.9.2 的那次「合并」）。
     * ⛔ **每个画幅只建一次** —— `Brush.verticalGradient(vararg)` 会分配 vararg 数组。
     *
     * 取值：原型 `buildWetStrip`（`seaside-preview.html:1995-2020`）的两条 ramp ——
     * 湿沙 5 个色标（`rgba(74,50,26,0.76)` → `rgba(106,84,54,0)`）+ 高光 3 个色标
     * （`SHEEN_A = 0.19`，§5.4）—— 按 `lighter` 合成成一支；span 取
     * `[SeasideWaves.waterline_min_bound, SHORE_K + SEA_WET_BAND]`。
     * 推导见 [buildWetBrush] 的 KDoc。
     *
     * ⛔ **只给 [drawWetWash] 用**（⛔ **不得**给 [drawSheen] —— 见 [sheenBrush]）。
     */
    private var wetBrush: Brush? = null

    /**
     * ⭐ **镜面高光自己那支**竖向渐变（**只含高光 ramp**，⛔ **不含湿沙 ramp**）。
     *
     * ## ⭐ 为什么⛔ 不能复用 [wetBrush]（本轮追加硬约束的直接后果）
     * 原型（`seaside-preview.html:2070-2090`）的高光 pass 是：
     * ```
     * ctx.clip(sandPath); ctx.globalCompositeOperation = 'lighter';
     * ctx.globalAlpha = wetAmt[i];
     * ctx.drawImage(sheenStrip, …)          // ← sheenStrip **只有高光 ramp**
     * ```
     * [wetBrush] 是 [buildWetBrush] 把**湿沙 ramp 与高光 ramp 按 `lighter` 预合成**的一支 ⇒
     * 拿它做那次 `Plus` 提交，会在高光带里**把湿沙 ramp 也叠加一遍**，而原型**只加高光**
     * ⇒ 近岸 `alpha ≈ 0.76` 的湿沙被二次叠加，形态读作「一条更亮的湿沙」而不是
     * 「冷白镜面高光」。⛔ **那是本次修正自己引入的视觉偏差** ⇒ 必须单独烘一支。
     *
     * 色标**逐字取原型**（`seaside-preview.html:2013-2017`）：
     * `rgba(232,240,236, SHEEN_A)` → `rgba(170,206,210, SHEEN_A·0.42)` → `rgba(140,180,190, 0)`。
     * ⚠️ 采样栅格沿用 [buildWetBrush] 的 `pos`（**含 0.00 / 0.38 / 1.00 三个真实色标**）⇒
     *   色标之间是分段线性 ⇒ ⛔ **与原型的 3 色标渐变逐点等价**（canvas 渐变本来就是分段线性）。
     *
     * ## ⚠️ 已上报的**残留偏差**（span 取值 ⇒ 渐变相位，非颜色）
     * 原型的高光条是**逐列**锚定在 `shoreYs[i]` 上的（`dst` 从 `shoreYs[i] − WET_OVER`
     * 到 `shoreYs[i] + depth`）⇒ 一支共享刷做不到「逐列各自锚定」，可见带因此只能落在
     * ramp 的中段而非原型那样从 `t ≈ 0.37` 一路走到 `1`。
     * 本支取与 [wetBrush] **完全相同**的 span（`[waterline_min_bound, SHORE_K + SEA_WET_BAND]`），
     * 理由是：**不引入一个项目里还没有的新偏差类别** —— 湿沙那一次已经在同一个 span 上
     * 做了同样的取舍并被接受；两支同 span 还保证「同一条 y 上的色标相位一致」。
     * ⇒ 该残留偏差**待所有者裁决**（见交付报告），⛔ 本轮不自行换 span。
     */
    private var sheenBrush: Brush? = null

    /**
     * 蕾丝的 `Stroke` 缓存 —— **4 类 × 3 档 = 12 支**（[SeasideOpBudget.laceStrokeCacheSize]）。
     * 下标 = `kind · LACE_BAND_N + band`（见 [buildLaceStrokes]）。
     * ⛔ 全部在烘焙期构造（[buildLaceStrokes] 是两个允许的构造点之一）；
     * ⛔ 任何 `DrawScope.draw*` 函数体内**不得**出现 `Stroke(`。
     */
    private var laceStrokes: Array<Stroke>? = null

    /** 沙纹理 blit 的左上角（= `(0, seaSandTopPx)`，烘焙期算好）—— 现在只作着色器矩阵的纵向锚点。 */
    private var sandTopLeft = Offset.Zero

    /** 沙纹理的实际像素宽 / 高（烘焙期写；⛔ **可能小于**画幅 ⇒ 4K 上靠它做矩阵缩放）。 */
    private var sandTexW = 0
    private var sandTexH = 0

    /**
     * ⭐⭐ **沙纹理的「画布坐标 → 纹理坐标」映射，改由 [drawSand] 的 `Canvas` 变换承载**
     * （2026-10-05，⛔ 不再用 [BitmapShader.setLocalMatrix]，见 [drawSand] 的机制说明）。
     *
     * | 字段 | 含义 | 画布上的等效变换 |
     * |---|---|---|
     * | [sandTexTopPx] | 纹理第 0 行锚定的画布 y（= `SAND_TEX_TOP·h`） | `translate(0, sandTexTopPx)` |
     * | [sandScaleX] / [sandScaleY] | 一画幅像素对应多少纹理像素（= `tw/w`、`th/rawH`） | `scale(sandScaleX, sandScaleY)` |
     *
     * 逐帧只读、逐帧零分配；`releaseResources` 复位为 1/1/0（= 恒等，⛔ 不可留在 0）。
     */
    private var sandTexTopPx = 0f
    private var sandScaleX = 1f
    private var sandScaleY = 1f

    /** 水体场 blit 的左上角（= `(0, 0)`，字段只为逐帧零分配而存在）。 */
    private var fieldTopLeft = Offset.Zero

    /** 水体场 / 粼光网的下界 y（px）= `SEA_BOTTOM_K·h`，烘焙期算好。 */
    private var seaBottomPx = 0f

    // ── 烘焙拓扑的预分配缓冲（**尺寸是常量**，只在 [rebuildGeometry] 里重写内容）────
    // ⚠️ 这些数组的**长度**由 §5.5 / §4.3.6 的规格常量定死（180 节点 / 331 边 / 40 节点），
    //   **不随画布尺寸变** ⇒ 只在构造期分配一次；[rebuildGeometry] 只写内容。

    /**
     * §4.3.6 蕾丝网的边容量上限。
     * 规格上界 = 长丝 `(9+2)·4 = 44` + 竖筋 `3·10 = 30` + 斜筋 `3·9·2 = 54` +
     * 网眼填充 `3·10 = 30` = **158**；这里取 `10·4·5 = 200` 留一截余量
     * （⛔ 溢出是 `pushLaceEdgeRaw` 里的**静默丢边**，不是抛异常 —— 这段余量就是它的保险）。
     */
    private val laceEdgeMax = LACE_NX * LACE_NY * 5

    /** §4.3.6 蕾丝网的 `10 × 4` 抖动节点（u-v 空间，⛔ 与画布尺寸无关）。 */
    private val laceNodeU = FloatArray(LACE_NX * LACE_NY)
    private val laceNodeV = FloatArray(LACE_NX * LACE_NY)

    /** 蕾丝网边的两个端点（u-v 空间）+ 环绕屏边的横向偏移 `xs = ±1` + 四类线的类号。 */
    private val laceEdgeU0 = FloatArray(laceEdgeMax)
    private val laceEdgeV0 = FloatArray(laceEdgeMax)
    private val laceEdgeU1 = FloatArray(laceEdgeMax)
    private val laceEdgeV1 = FloatArray(laceEdgeMax)
    private val laceEdgeXs = IntArray(laceEdgeMax)
    private val laceEdgeKind = IntArray(laceEdgeMax)

    /** 蕾丝边的动画属性（`ph / f1 / amp / rate / aj`，全部来自 `hash2`，原型 2580-2583）。 */
    private val laceEdgePh = FloatArray(laceEdgeMax)
    private val laceEdgeF1 = FloatArray(laceEdgeMax)
    private val laceEdgeAmp = FloatArray(laceEdgeMax)
    private val laceEdgeRate = FloatArray(laceEdgeMax)
    private val laceEdgeAj = FloatArray(laceEdgeMax)

    /** 蕾丝边的**距离档号**（0..2 ⇒ 与 [laceEdgeKind] 相乘定位 [laceStrokes]，⛔ 不逐帧算线宽）。 */
    private val laceEdgeQ = IntArray(laceEdgeMax)

    /** 本次烘出的蕾丝边数（≤ [laceEdgeMax]）。 */
    private var laceEdgeCount = 0

    /** 标称浪带宽（px）= `h · SEA_BAND`；蕾丝 `kindW` / `bandW` 的 px 标定基准。 */
    private var laceBandPx = 0f

    /** §4.2 焦散胞壁网的 `20 × 9 = 180` 个抖动节点（px，已按边界钉死，见 [buildCausticNet]）。 */
    private val causticNodeX = FloatArray(CAUSTIC_CELL_NX * CAUSTIC_CELL_NY)
    private val causticNodeY = FloatArray(CAUSTIC_CELL_NX * CAUSTIC_CELL_NY)

    // ── 逐浪的逐列场（原型 `bxs / bfy / bwj / slopeLaw / farLaw / edgeYs / washA / washB`
    //   / `slabLo / slabHi`，`seaside-preview.html:2443-2458`）────────────────────
    //
    // ⛔ 这些在原型里是模块级 scratch，端口改成本类的成员：逐浪循环里逐列算、同一帧内被
    //   该浪的 7 个 `draw*` 层共用 ⇒ 必须活过整个 [drawSwellBands] 调用。
    // ⛔ 长度恒为 [SeasideWaves.COLS] + 1 = 97（这里取**规格常量**而非 `column_count`：
    //   两者当前相等，但语义不同 —— 改 `COLS` 时由这两个数组的定义点一并生效）。
    // ⛔ **零分配**：全部在构造期建，逐帧只写值。

    /** 逐列 x（px）= `w · i / COLS`。 */
    private val bxs = FloatArray(SeasideWaves.COLS + 1)

    /** 逐列前缘 y（px）：`breaker_front_y` 的结果；领头浪 = 岸线本身。 */
    private val bfy = FloatArray(SeasideWaves.COLS + 1)

    /** 逐列带宽倍率（§4.3.9 ② 的 `shrink` 硬下限已经折在里面）。 */
    private val bwj = FloatArray(SeasideWaves.COLS + 1)

    /** 逐列倾角律 `slopeLaw[i]`（§4.3.5：浪脊线的横向斜率 `|dy/dx|` 作代理）。 */
    private val slopeLaw = FloatArray(SeasideWaves.COLS + 1)

    /** 逐列距离律 `farLaw[i]`（⛔ 只作用在 **alpha** 上，绝不折进带宽）。 */
    private val farLaw = FloatArray(SeasideWaves.COLS + 1)

    /** 领头浪的前缘 = 岸线本身；[drawSplash] 沿它抛飞沫（⛔ 未建立时飞沫整层跳过）。 */
    private val edgeYs = FloatArray(SeasideWaves.COLS + 1)

    /** 本帧是否已有领头浪 ⇒ [edgeYs] 有意义（原型 `edgeValid`）。 */
    private var edgeValid = false

    /** 白沫晕 / 浪本体的上下沿（逐段重算，原型 `washA` / `washB`）。 */
    private val washA = FloatArray(SeasideWaves.COLS + 1)
    private val washB = FloatArray(SeasideWaves.COLS + 1)

    /** 窄带阶梯的上下沿（原型 `slabLo` / `slabHi`，按 `step2` 隔列写）。 */
    private val slabLo = FloatArray(SeasideWaves.COLS + 1)
    private val slabHi = FloatArray(SeasideWaves.COLS + 1)

    /** 飞沫的**确定性烘焙点表**（原型 `SPLASH`，`seaside-preview.html:667-678`）——
     * ⛔ 全部 `hash2`、⛔ 无任何随机源 ⇒ 从同一时刻重放必然逐点一致。 */
    private val splashU = FloatArray(SPLASH_MAX)
    private val splashPeriod = FloatArray(SPLASH_MAX)
    private val splashPhase = FloatArray(SPLASH_MAX)
    private val splashReach = FloatArray(SPLASH_MAX)
    private val splashLift = FloatArray(SPLASH_MAX)
    private val splashSize = FloatArray(SPLASH_MAX)
    private val splashBright = FloatArray(SPLASH_MAX)

    /**
     * 残沫白点的**烘焙表**（原型 `RESIDUE`，`seaside-preview.html:683-700`）——
     * LCG（`mulberry32(RESIDUE_LCG_SEED)`）+ 最小间距 [RESIDUE_MIN_SEP] 拒绝采样
     * ⇒ **孤立、不成纹**（§4.4：连成纹就退化成沙纹）。
     * ⛔ 该 LCG **只**在 [buildResidueTable] 里跑（烘焙期）；逐帧路径里零随机源。
     */
    private val residueU = FloatArray(RESIDUE_MAX)
    private val residueV = FloatArray(RESIDUE_MAX)
    private val residueS = FloatArray(RESIDUE_MAX)
    private val residueA = FloatArray(RESIDUE_MAX)

    /** 本次烘出的残沫点数（≤ [RESIDUE_MAX]；LCG 可能因拒绝采样提前退出）。 */
    private var residueBaked = 0

    /**
     * 外海泡沫**预烘轮廓**的顶点（u-v 空间，`0..1` 归一化）—— 原型是 22 次 `drawImage`
     * 贴图，[drawOpenSeaFoam] 折成「8 块轮廓合成一条 path 一次 fill」⇒ 这里把每张贴图的
     * [FOAM_TILE_BLOB_N] 个软边斑块量化成 [FOAM_TILE_BLOB_POLY_N] 边形、
     * [FOAM_TILE_STREAK_N] 条撕碎丝缕量化成 [FOAM_TILE_STREAK_POLY_N] 点细带。
     *
     * ⛔ **种子与 [buildFoamTiles] 逐位同源**（同一批 `hash2(i, 6401..6447)`）⇒ 轮廓
     *   与它替代的那张贴图内容对应。
     */
    private val foamTileOutV = FloatArray(FOAM_TILE_OUT_V_CAP * 2)

    /** 每张贴图的轮廓起始顶点数。 */
    private val foamTileOutStart = IntArray(SeasideOpBudget.FOAM_TILES)

    /** 每张贴图的轮廓子路径数。 */
    private val foamTileOutSub = IntArray(SeasideOpBudget.FOAM_TILES)

    /** 实际写进 [foamTileOutV] 的 float 个数（`(x, y)` 交替）。 */
    private var foamTileOutN = 0

    /** 破碎唇的 `drawLines` 缓冲（`COLS + 1` 段 × 4 float）。 */
    private val crestLipPts = FloatArray((SeasideWaves.COLS + 1) * 4)

    /**
     * 螃蟹的**体坐标**几何缓存：⛔ 全部是**构造期成员** ⇒ 逐帧零分配。
     *
     * - [crabBody]：正在写的那一层（步足 / 壳 / 螯 / 壳沿 / 高光弧 / 眼点）。
     * - [crabTmp]：单只螯的**未旋转**局部轮廓（旋转交给 [crabClawMtx]）。
     * - [crabOut]：[crabBody] 过完 [crabMtx]（那四层变换）之后的落地路径。
     * - [crabRect]：高光弧的 `addArc` 外接矩形（⛔ `addArc` 只收 `RectF` ⇒ 预分配复用）。
     * - [crabMtx] / [crabClawMtx]：原型两层 CTM 的缓存矩阵。
     *
     * ⚠️ **为什么回到 `NativePath` 而不是 `drawLines`**：原型的步足髋→膝是
     * `quadraticCurveTo`（原型 2249 行），而 `drawLines` **只能画直线**；用两段弦近似会让
     * 「膝盖外凸」塌成折角，螃蟹读成蜈蚣。壳体同理 —— 原型是 `ellipse(1.06R, 0.76R)`
     * 的**填充**，⛔ 不是一支粗描边线段。
     */
    private val crabBody = NativePath()
    private val crabTmp = NativePath()
    private val crabOut = NativePath()
    private val crabRect = RectF()
    private val crabMtx = Matrix()
    private val crabClawMtx = Matrix()

    /** 螃蟹上一次是哪一只（`k`）—— ⛔ **换蟹瞬间直接落位**，只有同蟹才平滑。 */
    private var crabIdx = -1

    /** 螃蟹的纵坐标低通输出（px）；⛔ 换蟹时直接写成 `goal`。 */
    private var crabCy = 0.0

    /** 焦散网的边：`(CNX−1)·CNY + CNX·(CNY−1) = 331` 条，两端是**节点下标**（端点共享）。 */
    private val causticEdgeA = IntArray((CAUSTIC_CELL_NX - 1) * CAUSTIC_CELL_NY +
        CAUSTIC_CELL_NX * (CAUSTIC_CELL_NY - 1))
    private val causticEdgeB = IntArray(causticEdgeA.size)

    /** 每条壁的 3 档亮度档号（0/1/2 ⇒ [CAUSTIC_ALPHA_HI/MID/LO]）与垂直于壁的弓起量/相位。 */
    private val causticEdgeLevel = IntArray(causticEdgeA.size)
    private val causticEdgeBow = FloatArray(causticEdgeA.size)
    private val causticEdgePhase = FloatArray(causticEdgeA.size)

    // ── 水体场低分辨率画布的缓冲（由 [buildFieldCanvas] 按 `fw × fh` 重分配）──────
    // ⚠️ 这些是 **Java 堆**缓冲（⛔ 不进 G13 的 native 堆核算 —— G13 只算位图像素）。
    //   保留它们是为了让「按帧重写像素」这件事零分配（`Bitmap.setPixels` 不新建数组）。

    /** 水体场画布的宽 / 高（px，= `ceil(W/scale)` × `ceil(H·SEA_BOTTOM_K/scale)`）。 */
    private var fieldW = 0
    private var fieldH = 0

    /** 逐列相位与粗糙度（`colPhase / colMid / colFine / roughX`，原型 `drawSeaField:1577-1589`）。 */
    private var fieldColPhase = DoubleArray(0)
    private var fieldColMid = DoubleArray(0)
    private var fieldColFine = DoubleArray(0)
    private var fieldRoughX = DoubleArray(0)

    /** 逐行相位、行级对比度与纵向粗糙度（`rowPhase / rowMid / rowFine / rowAmp / roughY`）。 */
    private var fieldRowPhase = DoubleArray(0)
    private var fieldRowMid = DoubleArray(0)
    private var fieldRowFine = DoubleArray(0)
    private var fieldRowAmp = DoubleArray(0)
    private var fieldRoughY = DoubleArray(0)

    /** 逐像素缓冲（`fw · fh`，写入 [fieldTex] 的 backing `Bitmap`）。 */
    private var fieldPix = IntArray(0)

    // ── §4.6「水色」行的三个能量映射（**写字段**，⛔ 不造 Brush、不加提交）─────────
    /**
     * 响度 → 水色（§4.6「水色」行）的三个量，逐帧由 [drawSeaField] 写。
     *
     * ⚠️ 为什么是「写字段」而不是当场改画笔：[seaBaseBrush] 与 [fieldTex] 都是**烘焙产物**
     *   （`Brush` 不可变、位图已定稿），逐帧改它们只有两条路 —— 新建 `Brush`（⛔ 违反
     *   [SeasideTest] ⑤ 的逐帧零构造）或加一次全屏叠加提交（⛔ 撑破钉死的基线
     *   LOW 29 / MED 44 / HIGH 81）。⇒ 这里只**发布**这三个量，由水体的低分辨率像素生产者
     *   消费它们；原型里它们本来就在同一个逐像素循环里（`drawSeaField:1607-1649`）。
     */
    private var seaShadeBoost = 1.0
    private var seaDepthBias = SeasideAudioMap.DEPTH_BIAS_LO
    private var seaShoalWidth = SeasideAudioMap.SHOAL_LO

    // ── 路径（全部预分配；逐帧只 `reset()` + 重建轮廓）────────────────────────
    /**
     * 湿沙区轮廓（**只由 [drawWetWash] 写**）+ **原生** `drawPath` 提交一次（[SeaOpItem.WET_WASH]）。
     *
     * ⛔ 2026-10-05：由 Compose 的 `Path` 改成 [NativePath] —— 湿沙的填充刷是**原生**
     *   `LinearGradient`（[wetWashNativePaint]），⛔ `Brush` 逐帧构造被 [SeasideTest] ⑤ 判负
     *   ⇒ 只能走 `drawContext.canvas.nativeCanvas.drawPath(…)` ⇒ 必须是原生 `Path`。
     *   （上一版的 32 条平色 ribbon 用的是缓存 `Brush` + Compose `drawPath`，故当时是 `Path`。）
     * ⛔ 复用前一律 `rewind()`（⛔ 不是 `reset()`：后者保留 fillType / isConvex 等状态）。
     */
    private val wetRegionPath = NativePath()

    /**
     * ⭐ 镜面高光窄带轮廓（**只由 [drawSheen] 写**）+ [drawPath] **第二次**提交
     * （[SeaOpItem.SHEEN]），配 `BlendMode.Plus`。
     *
     * ⛔⛔ **必须与 [wetRegionPath] 分开** —— 这正是所有者推翻 §4.9.2「折成同一次提交」的原因：
     *   两条轮廓一旦挤进同一条 path，高光子轮廓就**嵌套在湿区内部**，NonZero 填充规则下
     *   **并入**湿区 ⇒ 像素集与不加它时**逐像素相同**，镜面高光**完全不可见**
     *   （原型是靠 `globalCompositeOperation = 'lighter'` 的**第二次**提交才有亮度差）。
     * ⛔ 预分配成员、逐帧只 `reset()`（⛔ 不是 `new`）。
     */
    private val sheenPath = Path()

    private val swellBodyPath = Path()
    private val foamStripPath = Path()
    private val seaFoamWashPath = Path()

    /**
     * [drawOpenSeaFoam] 的 **[OPEN_FOAM_TIERS] 档** alpha 缓冲（⛔ 预分配，⛔ **不是**逐帧 `new`）。
     *
     * 原型是 22 次 `drawImage`、每块各有 alpha；⛔ 取算术平均会让 22 块同亮
     * ⇒ 拆成 [OPEN_FOAM_TIERS] 条 path，各档一次 `drawPath`。
     */
    private val openSeaFoamPathT = Array(OPEN_FOAM_TIERS) { Path() }

    /**
     * ⭐ HIGH 档外海泡沫 / 扰动前锋的 **`drawBitmap`** 通道（[drawFoamTileBlits] 专用）。
     *
     * - `isFilterBitmap = true` —— 贴图原生 [SeasideOpBudget.FOAM_TILE_PX] 而目标尺寸
     *   `w0·(2.40…4.80)`，**必然缩放** ⇒ ⛔ 不得关掉滤波（原型 `imageSmoothingQuality='high'`）。
     * - ⛔ **不是** `BitmapShader`（本文件硬约束：着色器只允许 `drawSand`）。
     * - 逐块**只改 `alpha`**；⛔ 逐帧不新建画笔。
     */
    private val foamTileBlitPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    /** [drawFoamTileBlits] 的**复用**目标矩形（⛔ 逐帧零分配：⛔ 不是带参 `Rect(`）。 */
    private val foamTileDst = RectF()

    /**
     * ⭐ 扰动前锋（[drawDisturbance]）的合成轮廓 —— ⛔ **与 [openSeaFoamPathT] 分开**：
     * 两层原型上是**两次独立的 blit**、两条不同的距离律（`farLaw^1` vs `farLaw^0.35`），
     * 折进同一条 path 就只剩一个 alpha、一条距离律 ⇒ 扰动前锋被拖尾侧的强衰减吃掉。
     */
    private val disturbancePath = Path()
    private val crestLipPath = Path()
    private val lacePath = Path()

    /** 焦散网的三条 `Path`（[CAUSTIC_PATH_BUCKETS]，**每帧**重建）。 */
    private val causticPaths = Array(CAUSTIC_PATH_BUCKETS) { Path() }

    // ── 坐标缓冲（预分配；逐帧只写入、只改 offset/count，⛔ 绝不新建数组）────────
    /** 飞沫：1 次 `drawPoints` ⇒ 360 个 float（180 点 × 2）。 */
    private val splashPts = FloatArray(SPLASH_MAX * 2)

    /**
     * 残沫三笔（软阴影 → 亮芯 → 左上缘高光）⇒ 3 次 `drawPoints`。
     * ⛔ **一个数组 + 三个 offset**（不是三个数组）：`drawPoints(pts, offset, count, paint)`
     * 的 `offset` 让同缓冲的三笔零拷贝复用。
     */
    private val residuePts = FloatArray(RESIDUE_MAX * 2 * 3)

    /**
     * 沙纹三档 ⇒ 3 次 `drawLines`（同缓冲 + 三个 offset）。
     *
     * ⚠️ 容量按「每条线最多 [SAND_LINE_SEG_LO] + [SAND_LINE_SEG_SPAN] 段 × 每段
     * [SAND_GRAIN_PTS_PER_SEG] 条直线段 × 4 个 float」算：一档最坏
     * `26 × 5 × 6 × 4 = 3120` 个 float。⛔ 早先按 `26 × 4` 分配（只够 26 段）⇒
     * 会静默截断成「每档只画得下前 6 条线」。
     */
    private val sandGrainPts = FloatArray(SAND_GRAIN_SEG_STRIDE * 3)

    /** 一段沙纹折线的采样点（原型 `k <= 6` ⇒ 7 个点）⇒ 6 条直线段。 */
    private val rippleX = FloatArray(SAND_GRAIN_STEP_SPAN + 2)

    /** 同 [rippleX] 的 y。 */
    private val rippleY = FloatArray(SAND_GRAIN_STEP_SPAN + 2)

    /** 浪花手指 ⇒ 1 次 `drawLines`（30 根 × 4 个 float）。 */
    private val fingerPts = FloatArray(FINGER_MAX * 4)

    /**
     * 退水残沫：**逐 pass 复用**同一条缓冲（每个 pass 先填满再整条提交）。
     * 单 pass 的段数 = `26 丝 × 6 段`（每丝 7 个采样点 ⇒ 6 段）× 4 float；
     * 缓冲按最坏档 [RESIDUE_STREAK_PASS_N] 开（`3 × 26 × 6` × 4 float）。
     */
    private val residualStreakPts =
        FloatArray(RESIDUE_STREAK_PASS_N * RESIDUE_STREAK_SEG_N * RESIDUE_STREAK_STEPS * 4)

    // ── Paint（全部构造期成员）───────────────────────────────────────────────
    //
    // ⚠️ 只有 `drawPoints` / `drawLines` 这两条**原生合批**通道用 `android.graphics.Paint`
    //   （它们没有 Compose 侧的等价物）；其余全部走 `drawPath(path, brush, alpha, style)`，
    //   **不**需要 `Paint`。视觉轮次若要给泡沫类填色，接 `rebuildGeometry` 烘的 `Brush`。
    /** 浪花手指的 `drawLines` 描边（⛔ 逐帧只改 `alpha`，不新建）。 */
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    /** 沙纹三档各自的描边（§4.9.2「26×3 档」⇒ 3 档 alpha ⇒ 3 支画笔）。 */
    private val sandGrainPaints = Array(3) {
        Paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
    }

    /** 残沫三笔各自的画笔（软阴影 / 亮芯 / 左上缘高光）。 */
    private val residuePaints = Array(3) {
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    /**
     * 螃蟹**全部 8 批共用**的一支画笔（逐帧只改 `style` / `color` / `alpha` / `strokeWidth`）。
     *
     * ⛔ 提交数由「`style`+`color`+`strokeWidth` 的**取值分组数**」决定，⛔ **不由画笔实例数**
     *   决定 —— 一支就够（`drawPath` 在录制进 display list 时会快照画笔状态），
     *   而且这正好顺带证明**逐帧零分配**：8 批共用一支 ⇒ 连「每批一支」都不需要。
     * ⚠️ `strokeCap` / `strokeJoin` 必须是 **ROUND**（原型 2227-2228 行 `lineCap/lineJoin`）：
     *   步足与螯臂是圆头才读得出「肢体」，不是折线尖角。
     */
    private val crabPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    /**
     * 退水残沫的描边（[SeaOpItem.RESIDUAL_STREAK] 的**每个 pass 一次** `drawLines`
     * —— HIGH 共 3 次、MEDIUM·LOW 共 1 次；⛔ 三个 pass **共用这一支**，提交数由
     * 「`style`+`color`+`strokeWidth` 的取值分组数」决定，⛔ **不由画笔实例数**决定）。
     * ⛔ 逐帧只改 `strokeWidth` / `alpha`，⛔ **绝不**逐帧构造 `Stroke`
     *   （`Stroke.width` 不可变，且 `SeasideTest` ⑥ 只允许两个烘焙期构造点）。
     */
    private val residualStreakPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    /**
     * ⛔ `drawPoints` 专用画笔：`strokeCap = ROUND` 是「飞沫/残沫读作**点**而不是方块」的
     * 唯一来源（E42 同款做法，§4.9.2 引的就是它）。
     * ⛔ 逐帧只改 `color` / `alpha` / `strokeWidth`，⛔ 绝不新建。
     */
    private val pointPaint = Paint().apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    /**
     * ⭐ 干沙与洼地水洼的**原生**路径（`android.graphics.Path`）—— ⛔ 只这两项。
     *
     * ## 为什么它们不用 Compose 的 `Path`
     * ① **填充要接位图着色器**：沙纹理必须当 `BitmapShader` 的填充刷（见 [drawSand] 的
     *    「上边界为什么是路径」），而缓存 `android.graphics.Paint` 才能挂 `Shader` +
     *    `isFilterBitmap`（放大时双线性）⇒ 走 `nativeCanvas.drawPath`。
     * ② **`addOval` 没有 float 四参重载**：Compose 的 `Path.addOval` **只有** `Rect` / `Oval`
     *    两种重载（⛔ 早期那条「`addOval` / `addRect` 都有 float 四参重载」的注释**是错的**，
     *    已改写；`addRect(left, top, right, bottom)` 才真有 float 四参重载）。
     *    ⇒ 逐个椭圆就得逐个构造 `Rect`（= 逐帧分配，⛔ 违反 [SeasideTest] ④）。
     *    原生 `Path.addOval(left, top, right, bottom, dir)` 是 float 五参 ⛔ 零分配。
     *
     * ## ⭐ 下一批的 `punchHoles` 定案（所有者裁决，⛔ 别再走回头路）
     * `drawFoamStrip` 的 evenodd 破洞要用**椭圆孔**时：
     * - ⛔ **不得**用 `addOval(Rect(...))` —— Compose 版没有 float 重载，每个孔一次 `Rect` 分配。
     * - ⇒ 走**原生** `android.graphics.Path.addOval(l, t, r, b, dir)`，用 `asAndroidPath()` 取
     *   （E42 先例，本文件已有这条通道）—— 它**有** float 四参 + 方向且**零分配**。
     * - ⚠️ evenodd 破洞要求孔与本体**同一条 path** ⇒ 下一批需要**再开一条**预分配的原生 `Path`
     *   （⛔ 复用 [puddleNativePath] 不行：它是水洼的，逐帧内容完全不同）。
     * - ⛔ 矩形孔则**不需要**原生路径：Compose `Path.addRect(left, top, right, bottom)` 本来就有
     *   float 四参重载，直接用。
     * ⛔ 原生 `Path` 逐帧只 `rewind()`（**不是** `reset()`：后者会顺手丢掉存储），
     *   ⛔ `Path` 没有 `recycle()`（[releaseResources] 里只 `rewind()`）。
     */
    private val sandNativePath = NativePath()
    private val puddleNativePath = NativePath()

    /**
     * 水洼的**反光**那一批（[SeaOpItem.PUDDLE] 的第 2 次提交，仅 HIGH）——
     * ⛔ **必须与 [puddleNativePath] 分开**：反光若并进本体那条 path，NonZero 填充规则会
     *   把它并入填充区 ⇒ 第二种颜色根本不存在（这正是「一次提交拿不到两色」的根源）。
     * ⛔ 两条路径**都只** `rewind()`（⛔ 不用 `reset()`：后者会顺手丢掉存储）。
     */
    private val puddleSpecNativePath = NativePath()

    /**
     * ⭐ 泡沫窄带的**原生**路径（`android.graphics.Path`）—— [drawFoamStrip] 的唯一写入者。
     *
     * ## 为什么必须是原生的（⛔ 别改回 Compose `Path`）
     * `punchHoles` 要的是 **evenodd** 填充规则，而填充规则挂在 **path** 上
     * （`android.graphics.Path.setFillType`，API 21+；`Canvas.drawPath` 直接读它）。
     * 孔又是**闭合子路径** ⇒ 必须与条带轮廓在**同一条** path 上。
     * ⛔ **不可复用 [puddleNativePath]**（那是水洼的，逐帧内容完全不同）。
     *
     * ⛔ 椭圆孔走原生 `addOval(l, t, r, b, dir)` 的 float 五参重载：Compose 的
     *   `Path.addOval` **只有** `Rect` / `Oval` 两种重载 ⇒ 用它就得逐孔构造 `Rect`
     *   （= 逐帧分配，⛔ 违反 [SeasideTest] ④）。
     */
    private val foamStripNativePath = NativePath().apply {
        fillType = NativePath.FillType.EVEN_ODD
    }

    /** 浪本体（迎光 / 背光）与白沫晕的原生路径（都要挂**会移动的**渐变着色器 ⇒ 走原生）。 */
    private val swellBodyNativePath = NativePath()
    private val seaFoamWashNativePath = NativePath()

    /** 窄带的填充画笔（⛔ 逐帧只改 `color` / `alpha`）。 */
    private val foamStripNativePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /**
     * 破碎唇的描边（[drawCrestLip] 的**每个 pass 一次** `drawLines` —— HIGH 共 2 次、
     * MEDIUM 共 1 次；⛔ 两个 pass **共用这一支**，提交数由「`style`+`color`+`strokeWidth`
     * 的取值分组数」决定，⛔ **不由画笔实例数**决定）。
     * ⛔ 逐帧只改 `color` / `strokeWidth` / `alpha`，⛔ 绝不逐帧构造 `Stroke`
     *   （`Stroke.width` 不可变，且 [SeasideTest] ⑥ 只允许两个烘焙期构造点）。
     */
    private val crestLipPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    /**
     * 浪本体 / 白沫晕的填充画笔（各挂一支**烘焙期**建好的 `LinearGradient`）。
     *
     * ## 为什么必须用原生 `Shader` 而不是缓存 `Brush`
     * 渐变的竖向跨度（`yT → yB`）**逐帧随浪脊移动**；Compose 的
     * `Brush.verticalGradient` 的 `startY / endY` 在构造时定死 ⇒ 要跟就得每帧
     * new 一个（`Brush.` 逐帧构造被 [SeasideTest] ⑤ 判负，且 vararg 数组逐帧分配）。
     * ⛔ 原生这条路：渐变建在**归一化**的 `y ∈ [0, 1]` 上，逐帧只用一块**预分配的**
     * [gradMatrix] 把它 `setScale` + `postTranslate` 到本帧的跨度 ⇒ **零分配**，
     * 且仍是 1 次提交。
     * ⚠️ 归一化 ⇒ 停靠 alpha 全部除以该支的最大项，逐帧用 `Paint.setAlpha` 乘回去
     *   （`Paint` 的 alpha 会调制着色器输出 ⇒ 数学上逐项相等）。
     */
    private val swellBodyNativePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** 白沫晕的两支画笔（下标 0 = 领头浪 `dir < 0`、下标 1 = 非领头浪 `dir > 0`）。 */
    private val seaFoamWashNativePaints = Array(2) {
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    /** 逐帧挪动四支渐变用的**唯一**矩阵（`setLocalMatrix` 会拷一份 ⇒ 复用安全）。 */
    private val gradMatrix = Matrix()

    /**
     * 湿沙本体的填充画笔（挂一支**烘焙期**建好的归一化 `LinearGradient`，
     * 停靠位置由 [gradMatrix] 逐帧 `setScale` + `postTranslate` 决定 —— 机制同
     * [swellBodyNativePaint]）。
     *
     * ⛔ **不用**缓存 `Brush`：`Brush.verticalGradient` 的 `startY / endY` 构造时定死
     *   ⇒ 逐帧跟随水线就必须每帧 `Brush.` 构造（[SeasideTest] ⑤ 判负 + vararg 数组逐帧分配）。
     * ⛔ 归一化的代价：色标 alpha 全部除以 [WET_WASH_PEAK_A]，逐帧用 `Paint.setAlpha` 乘回去
     *   （`Paint` 的 alpha 调制着色器输出 ⇒ 数学上逐项相等）。
     */
    private val wetWashNativePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** 干沙的填充画笔（**唯一**带 `BitmapShader` 的那支，见 [drawSand] 的单点例外裁决）。 */
    private val sandPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        isFilterBitmap = true
    }

    /** 洼地水洼 + 压扁椭圆反光的填充画笔（本体与反光折成同一色，⛔ 不第二次提交）。 */
    private val puddleNativePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        color = AndroidColor.argb(255, 255, 255, 255)
    }

    /** 焦散网的三档 `Stroke`（线宽不随帧变 ⇒ 烘焙期建，逐帧只换 path）。 */
    private var causticStrokes: Array<Stroke>? = null

    // ═══════════════════════════ 生命周期 ═══════════════════════════

    /**
     * 进入效果。⛔ **只做释放**，⛔ **不**按尺寸烘焙、⛔ **不**复位时间原点与包络。
     *
     * - 首行 [releaseResources]：`RendererSwapper.sync` 在**画质切换**时会重入 `onEnter`
     *   （`RendererSwapper.kt:89-90`），不先释放 ⇒ native 堆泄漏 / 串图（E42 同款坑）。
     * - ⛔ `ctx.canvasSize` 此刻仍是 `Size.Zero`（`VisualizerStage:192`）⇒ 这里烘出来的是
     *   0×0，真机表现是「切画质后效果整块消失直到下次尺寸变化」。
     * - ⛔ **不**复位 [t0Ms] / [audio] 包络：重入 `onEnter` 不是「重新进入效果」，
     *   复位会让浪的行程与水线一帧跳变。
     */
    override fun onEnterContent(ctx: RenderContext) {
        releaseResources()
        // 尺寸/密度键故意**不**在这里预置：让首帧必定走 [drawContent] 的重建分支。
    }

    /**
     * 离开效果 ⇒ [releaseResources]。
     *
     * ⛔ API 22–25 的位图像素在 **native 堆**，只置 `null` 会拖到 OOM 才回收 ⇒ 必须
     * 逐张 try/catch `recycle()`。⛔ **不**调 `ProceduralTexture.release()`
     * （归 `VisualizerStage`）、⛔ **不**调 `OverlayFx.release()`（归 `RendererFx.onExit`）。
     *
     * ⚠️ `SeasideTest` 的「位图字段都被 try/catch `recycle()` 覆盖」断言沿这条
     * **调用链**检查（`onExitContent` → `releaseResources`），因为同一份释放逻辑必须同时
     * 服务 [onEnterContent] 的重入与这里的退出 —— 分开写两份必然漂。
     */
    override fun onExitContent() {
        releaseResources()
    }

    /**
     * 释放全部自持位图 + 清空全部静态 `Path` / `Stroke` 缓存。**⛔ 零分配、⛔ 幂等。**
     *
     * ⛔ `Path` 只有 `reset()`：Compose 的 `androidx.compose.ui.graphics.Path` **没有**
     * `recycle()`（那是 `android.graphics.Path` 的语义，别混）。
     */
    private fun releaseResources() {
        // ⛔ 先摘掉着色器再 `recycle()`：`BitmapShader` 持有位图的 native 引用。
        try { sandPaint.shader = null } catch (_: Exception) {}
        try { sandTex?.asAndroidBitmap()?.recycle() } catch (_: Exception) {}
        sandTex = null
        sandTexW = 0
        sandTexH = 0
        sandTexTopPx = 0f
        sandScaleX = 1f
        sandScaleY = 1f
        try { fieldTex?.asAndroidBitmap()?.recycle() } catch (_: Exception) {}
        fieldTex = null
        try { grainTex?.asAndroidBitmap()?.recycle() } catch (_: Exception) {}
        grainTex = null
        try { mottleTex?.asAndroidBitmap()?.recycle() } catch (_: Exception) {}
        mottleTex = null
        var i = 0
        while (i < foamTiles.size) {
            try { foamTiles[i]?.asAndroidBitmap()?.recycle() } catch (_: Exception) {}
            foamTiles[i] = null
            i++
        }
        laceStrokes = null
        causticStrokes = null
        seaBaseBrush = null
        wetBrush = null
        sheenBrush = null
        var k = 0
        while (k < causticPaths.size) {
            causticPaths[k].reset()
            k++
        }
        wetRegionPath.reset()
        sheenPath.reset()
        sandNativePath.rewind()
        puddleNativePath.rewind()
        puddleSpecNativePath.rewind()
        foamStripNativePath.rewind()
        swellBodyNativePath.rewind()
        seaFoamWashNativePath.rewind()
        // ⛔ 摘掉四支渐变着色器：它们是纯 native 对象，跨尺寸重建时旧的直接丢掉。
        try { swellBodyNativePaint.shader = null } catch (_: Exception) {}
        try { seaFoamWashNativePaints[0].shader = null } catch (_: Exception) {}
        try { seaFoamWashNativePaints[1].shader = null } catch (_: Exception) {}
        try { wetWashNativePaint.shader = null } catch (_: Exception) {}
        swellBodyPath.reset()
        foamStripPath.reset()
        seaFoamWashPath.reset()
        var ti0 = 0
        while (ti0 < OPEN_FOAM_TIERS) {
            openSeaFoamPathT[ti0].reset()
            ti0++
        }
        disturbancePath.reset()
        crestLipPath.reset()
        lacePath.reset()
    }

    // ═══════════════════════════ 几何重建 ═══════════════════════════

    /**
     * 依画布尺寸 / 密度重建**全部**尺寸相关量：波纹场、烘焙层、缓存渐变、笔宽 px、
     * 蕾丝与焦散的 `Stroke` 阶梯。
     *
     * 调用点**只有** [drawContent] 的「尺寸或密度变化」分支（⛔ 不在 [onEnterContent]）。
     *
     * ⛔ **换尺寸必须清空浪队列**（[SeasideWaves.reset]）—— 不该留半个队列：
     * 列栅格变了，岸线/破碎场的噪声相位随之改变，半个旧队列会画出按旧尺寸写的浪带。
     * ⛔ 该分支**不得**出现任何 `ProceduralTexture.ensure*`（§4.8）—— 本效果一张都不取。
     *
     * @param w 画布宽（px）。
     * @param h 画布高（px）。
     */
    private fun rebuildGeometry(w: Float, h: Float) {
        seaW = w
        seaH = h
        val wv0 = waves
        if (wv0 == null) {
            waves = SeasideWaves(w, h)
        } else {
            wv0.resize(w, h)
        }
        // ⛔ 显式再调一次：`resize` 内部已 `reset()`，这里是为了让「换尺寸必清队列」
        //   这条不变量在源码里**可读**，且不依赖 `resize` 的内部实现。
        waves?.reset()

        val wv = waves ?: return
        // 下界 / 上沿都来自模拟层的权威常量 ⛔ 不在本文件另写一份（红线 7：退水时
        // `SAND_TEX_TOP..waterline_min_bound` 那一段绝不能露出海水底色）。
        seaBottomPx = (h * SeasideWaves.SEA_BOTTOM_K).toFloat()
        sandTopLeft = Offset(0f, wv.sand_tex_top_px.toFloat())
        fieldTopLeft = Offset.Zero

        // —— 烘焙层（**内容全部已接上**；尺寸契约见各自 KDoc）——
        buildBrushes()
        buildSandTexture(w, h)
        buildFoamTiles()
        buildFoamTileOutlines()
        buildSplashTable()
        buildResidueTable()
        buildGrain()
        buildMottle()
        buildFieldCanvas(w, h)
        buildCausticNet(w, h)
        buildLaceNet(w, h)
        // —— 缓存渐变（⛔ 每帧绝不 new：`Brush.verticalGradient(vararg)` 分配 vararg 数组）——
        buildSeaBaseBrush(w, h)
        buildWetBrush(w, h)
        // ⛔ 四支**逐帧要挪**的原生 `LinearGradient`（浪本体 + 白沫晕两支 + 湿沙一支）：
        //   停靠位置归一化到 `y ∈ [0, 1]`，逐帧只 `setLocalMatrix`（零分配，见 [swellBodyNativePaint]）。
        buildWaveGradients()
        // —— `Stroke` 阶梯（`Stroke.width` 不可变 ⇒ 只能量化 + 缓存）——
        // ⛔ 两个构造点都是**烘焙期**的（`SeasideTest` ⑥：构造点集合 ⊆
        //   {buildLaceStrokes, buildCausticStrokes}）⇒ 逐帧零分配不受影响。
        buildLaceStrokes()
        buildCausticStrokes()
    }

    /**
     * ⭐ 各元素专用 `Brush` 的烘焙（§5.6 调色板 ⇒ 原型 `PAL`，`seaside-preview.html:438-439`）。
     *
     * ⛔ **每个画幅只跑一次**（由 [rebuildGeometry] 调）—— 这些全是 `SolidColor` /
     * `verticalGradient` 构造，逐帧跑就是每帧十几次分配。
     *
     * 只有 [swellBodyBrush] 是渐变：§5.6 说浪脊亮 / 槽底暗「与海面明暗语言同一套，
     * 供 `drawSwellBody` / `shoalW` 用」，而 [drawSwellBody] 的本体就是「背光面暗 →
     * 迎光面亮」的一道体积 ⇒ 刷子沿浪带宽度方向竖向渐变。
     */
    private fun buildBrushes() {
        swellBodyBrush = Brush.verticalGradient(
            0f to Color(PAL_TROUGH),
            1f to Color(PAL_RIDGE)
        )
        foamStripBrush = SolidColor(Color(PAL_FOAM_CORE))
        seaFoamWashBrush = SolidColor(Color(PAL_FOAM_EDGE))
        openSeaFoamBrush = SolidColor(Color(PAL_FOAM_EDGE))
        crestLipBrush = SolidColor(Color(PAL_FOAM_LIP))
        laceBrush = SolidColor(Color(PAL_FOAM_NET))
        causticBrush = SolidColor(Color(PAL_FOAM_NET))
        residueBrush = SolidColor(Color(PAL_FOAM_EDGE))
        // ⛔ 洼地水洼**没有** `Brush`：[drawPuddles] 走原生 [puddleNativePaint]（椭圆要 float
        //   五参 `addOval`，见那条 KDoc）⇒ 色值在绘制侧由 `PAL_SHOAL` 的三个通道常量拼。
    }

    /**
     * 干沙烘焙纹理 —— 四层**确定性**烘焙（§4.3.7 逐层照搬）。
     *
     * ⛔ 高度必须是 `h − SAND_TEX_TOP·h`（= [SeasideWaves.SAND_TEX_TOP]），⛔ **不是** `h`：
     * G13 的接缝就在这里（按整屏烘 ⇒ 2560×1440 上 native 堆 3,625,411 > 3,000,000）。
     * ⛔ 像素 > [SeasideOpBudget.SAND_TEX_MAX_PX] 时按比例降采样
     * （§4.3.7 开销闸门：`k = min(1, √(budget / (tw·th)))`）。
     *
     * 四层与取值来源：
     * | 层 | 内容 | 取值来源 |
     * |---|---|---|
     * | ① | 湿→干底色渐变（近浪 [PAL_SAND_NEAR] → 中 [PAL_SAND_MID] → 下缘 [PAL_SAND_FAR]，`v^0.86`、在 `v = 0.42` 折点）。⚠️ `1 − 0.10·(1−v)` **只乘层④颗粒**（原型 `gain`），⛔ 不乘底色 | 原型 `seaside-preview.html:572-579` + §5.6「干沙（近浪/中/下缘）」 |
     * | ② | 宽而柔的沿岸起伏带（两个 `vnoise2` 扭曲后的 `sin((v·7 + warp)·2π)` ⇒ **不是等距直线**；`ripK` 是**乘性**的） | 原型 `585-587` + §5.4 `SAND_RIPPLE_N / _A` |
     * | ③ | 潮湿斑块（两个 `vnoise2` 按 `0.62/0.38` 合成 `smoothstep(0.54, 0.82, …)`，再乘近水权重 `0.30 + 0.70·(1 − smoothstep(0.10, 0.80, v))`，压向 [PAL_SAND_DAMP]） | 原型 `590-592` + §5.4 `SAND_DAMP_A` |
     * | ④ | 像素级细颗粒（**主导纹理**：两个 `hash2(x,y)` 白噪声相加，**乘层①的 `gain`**，最后整体 `× (1 − damp·0.10)`） | 原型 `598-605` + §5.4 `SAND_GRAIN_A / _GRAIN2_A` |
     *
     * ⛔ blit 的**源矩形必须是 `(0, 0, texW, texH)`**（§4.3.7 末条：把上沿当源 `y`
     * 偏移会采到纹理外面、整块沙变成灰暗的条纹）—— [sandTopLeft] 只提供**目标**左上角。
     */
    private fun buildSandTexture(w: Float, h: Float) {
        val rawW = w.toDouble()
        val rawH = h * (1.0 - SeasideWaves.SAND_TEX_TOP)
        if (rawW < 1.0 || rawH < 1.0) return
        val budget = SeasideOpBudget.SAND_TEX_MAX_PX.toDouble()
        val area = rawW * rawH
        var k = 1.0
        if (area > budget) k = sqrt(budget / area)
        val tw = (rawW * k).toInt().coerceAtLeast(1)
        val th = (rawH * k).toInt().coerceAtLeast(1)
        val px = IntArray(tw * th)

        // ── 层①的逐行常量（⛔ 每行只算一次，不是每像素）───────────────────────
        // `vv = v^0.86`，折点 `v = 0.42` ⇒ `vvBreak = 0.42^0.86`；两段各自归一化。
        val vvBreak = powD(SAND_TEX_V_BREAK, SAND_TEX_V_POW)
        val invLo = 1.0 / vvBreak
        val invHi = 1.0 / (1.0 - vvBreak)
        val rowBase = IntArray(th)
        val rowMul = DoubleArray(th)
        val rowDamp = DoubleArray(th)
        var j = 0
        while (j < th) {
            val v = j.toDouble() / (th - 1).coerceAtLeast(1).toDouble()
            val vv = powD(v, SAND_TEX_V_POW)
            val seg = if (vv <= vvBreak) vv * invLo else 1.0
            var col = mixArgb(PAL_SAND_NEAR, PAL_SAND_MID, seg)
            if (vv > vvBreak) {
                col = mixArgb(col, PAL_SAND_FAR, (vv - vvBreak) * invHi)
            }
            rowBase[j] = col
            // ⭐ `dampDrift`（原型 `seaside-preview.html:579`）= `1 - 0.10·(1 - v)`：
            //   **整体湿→干的微渐变**。⚠️ 原型只把它当**层④颗粒**的增益 `gain`
            //   （原型 601 / 603-605 行），⛔ **不乘底色** —— 早先这里乘在底色上、
            //   颗粒反而原样相加，是**两头都反了**（⇒ 近水处底色被额外压暗 10%、
            //   颗粒不随干湿衰减）。
            rowMul[j] = 1.0 - SAND_TEX_WET_FADE * (1.0 - v)
            // 层③的「近水处更多」权重（原型 591-592 行的 `(0.30 + 0.70·(1 - smoothstep(0.10, 0.80, v)))`）
            rowDamp[j] = SAND_TEX_DAMP_V_BASE + SAND_TEX_DAMP_V_SPAN *
                (1.0 - SeasideWaves.smoothstep(SAND_TEX_DAMP_V_LO, SAND_TEX_DAMP_V_HI, v))
            j++
        }
        val dampR = (PAL_SAND_DAMP shr 16) and 0xFF
        val dampG = (PAL_SAND_DAMP shr 8) and 0xFF
        val dampB = PAL_SAND_DAMP and 0xFF

        var y = 0
        while (y < th) {
            val v = y.toDouble() / (th - 1).coerceAtLeast(1).toDouble()
            val base = rowBase[y]
            val gain = rowMul[y]
            val dampNear = rowDamp[y]
            val br = ((base shr 16) and 0xFF).toDouble()
            val bg = ((base shr 8) and 0xFF).toDouble()
            val bb = (base and 0xFF).toDouble()
            var x = 0
            while (x < tw) {
                val u = x.toDouble() / (tw - 1).coerceAtLeast(1).toDouble()
                if (BISECT_SAND_TEX_FLAT) {
                    px[y * tw + x] = (0xFF shl 24) or
                        (clampByte(br) shl 16) or (clampByte(bg) shl 8) or clampByte(bb)
                    x++
                    continue
                }
                // ① 底色原样（⛔ **不乘** `gain` —— 原型只用它调层④颗粒）
                var r = br
                var g = bg
                var b = bb
                // 层② 宽柔沿岸起伏带（两个八度扭曲 ⇒ 绝不等距）
                //     逐字照抄原型 `seaside-preview.html:585-587`
                val w1 = SeasideWaves.vnoise2(u * SAND_TEX_WARP_U1, v * SAND_TEX_WARP_V1, SAND_TEX_WARP_S1)
                val w2 = SeasideWaves.vnoise2(u * SAND_TEX_WARP_U2, v * SAND_TEX_WARP_V2, SAND_TEX_WARP_S2)
                val warp = w1 * SAND_TEX_WARP_A1 + w2 * SAND_TEX_WARP_A2
                val band = SeasideWaves.fsin((v * SAND_TEX_RIPPLE_N + warp) * TAU)
                // 原型是**乘性**的 `ripK = 1 + SAND_RIPPLE_A·rip`（原型 587 行）
                val ripK = 1.0 + SAND_TEX_RIPPLE_A * band
                r *= ripK
                g *= ripK
                b *= ripK
                // 层③ 潮湿斑块：逐字照抄原型 `seaside-preview.html:590-592`
                val d1 = SeasideWaves.vnoise2(u * SAND_TEX_DAMP_U1, v * SAND_TEX_DAMP_V1, SAND_TEX_DAMP_S1)
                val d2 = SeasideWaves.vnoise2(u * SAND_TEX_DAMP_U2, v * SAND_TEX_DAMP_V2, SAND_TEX_DAMP_S2)
                val damp = SeasideWaves.smoothstep(
                    SAND_TEX_DAMP_LO, SAND_TEX_DAMP_HI, d1 * SAND_TEX_DAMP_M1 + d2 * SAND_TEX_DAMP_M2
                ) * dampNear * SAND_TEX_DAMP_A
                if (damp > 0.0) {
                    r += (dampR - r) * damp
                    g += (dampG - g) * damp
                    b += (dampB - b) * damp
                }
                // ⭐ 层④ 像素级细颗粒（主导纹理）：两个白噪声相加，各自去中心，
                //     **乘层①的 `gain`**（原型 600-605 行），最后整体压暗 `1 - damp·0.10`
                // ⭐⭐ 2026-10-05 **逐字对齐原型**：原来这里用的是 `hash2(x, y)`
                //   （`((x+1)·0x9E3779B1) ^ ((y+7)·40503)`），那**不是**原型的函数 ——
                //   原型是 `hash32(imul(y, A) + imul(x, B) + C)` 的**加法**混合。两者都各向同性
                //   （复算：相邻行相关 −0.004 / −0.001，幅值都是 `(0.062+0.030)/√3·255 = 13.5`）
                //   ⇒ ⛔ **不是竖纹的成因**，但它是一处实打实的偏离，照原型改。
                // ⚠️ `2654435761` 必须写成**有符号** Int `-1640531535`（= `0x9E3779B1`）——
                //   写成整数字面量在 Kotlin 里是 `Long`，会把整条表达式提到 64 位（⛔ 编译不过，
                //   且与 `Math.imul` 的 32 位回绕语义不同）。同 [SeasideWaves.hash2] 的 KDoc。
                val n1 = SeasideWaves.hash32(
                    y * 374761393 + x * 668265263 + 12345
                ) - 0.5
                val n2 = SeasideWaves.hash32(
                    y * 1274126177 + x * (-1640531535) + 777
                ) - 0.5
                val grain = (n1 * SAND_TEX_GRAIN_A + n2 * SAND_TEX_GRAIN2_A) * 255.0
                val darken = 1.0 - damp * SAND_TEX_DAMP_DARKEN
                r = (r + grain * gain) * darken
                g = (g + grain * gain) * darken
                b = (b + grain * gain) * darken
                val ri = if (r < 0.0) 0 else if (r > 255.0) 255 else r.toInt()
                val gi = if (g < 0.0) 0 else if (g > 255.0) 255 else g.toInt()
                val bi = if (b < 0.0) 0 else if (b > 255.0) 255 else b.toInt()
                px[y * tw + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                x++
            }
            y++
        }
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, tw, 0, 0, tw, th)
        sandTex = bmp.asImageBitmap()
        sandTexW = tw
        sandTexH = th
        // ── 「画布坐标 → 纹理坐标」的映射：**写进字段，由 [drawSand] 的 Canvas 变换承载** ──
        //   纹理第 0 行 ↔ 画布 y = `sand_tex_top_px`；一画幅像素 ↔ `sx / sy` 个纹理像素。
        // ⛔ **不再用 `BitmapShader.setLocalMatrix`**（2026-10-05）：那层映射在真机上**不生效**
        //   ⇒ 采样退化成恒等 ⇒ 沙滩（画布 y ∈ `[0.484h, h]`）整体越过 `th` 行、被
        //   `CLAMP` 钉在**最后一行** ⇒ 整块沙只剩 x 方向的变化（竖纹），层①的湿→干渐变与
        //   层②的起伏带同时消失。判据：真机沙的 x 剖面与 `hash2(x, th−1)` 相关 **+0.55**、
        //   与其余 582 行 **≈0**（见 [drawSand]）。
        val sx = if (rawW > 0.0) tw / rawW else 1.0
        val sy = if (rawH > 0.0) th / rawH else 1.0
        sandScaleX = sx.toFloat()
        sandScaleY = sy.toFloat()
        sandTexTopPx = (waves?.sand_tex_top_px ?: 0.0).toFloat()
        // ⛔ `TileMode.CLAMP`：矩阵只被采样到 `[0, tw] × [0, th]`，CLAMP 只是兜底。
        val sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        sandPaint.shader = sh
    }

    /**
     * 泡沫贴图 [SeasideOpBudget.FOAM_TILES] 张（每张 [SeasideOpBudget.FOAM_TILE_PX] 像素）。
     *
     * ⛔ **LOW 档整组不分配**（§4.7②「跳过贴图泡沫」），由 [drawOpenSeaFoam] 的档位门承担。
     * ⛔ 渐隐外径必须 < 贴图半边长（否则贴图边界会在海面上显出方块）⇒
     * [FOAM_TILE_FADE_R] = `116f` < `256/2 = 128f`。
     *
     * 每张 = 软边斑块 [FOAM_TILE_BLOB_N] 个 + 撕碎丝缕 [FOAM_TILE_STREAK_N] 条，
     * 再乘边缘 alpha 渐隐；颜色取 §5.6 的「浪白·前缘」[PAL_FOAM_EDGE]，峰值 alpha
     * [FOAM_TILE_PEAK_A]（§5.3 `SEA_FOAM_A`）—— 逐块的那层 alpha 由 §4.3.5 的
     * `0.10 + 0.90·fk` 在**绘制侧**乘上去（下一批），⛔ 不烘进贴图。
     */
    private fun buildFoamTiles() {
        // ⛔ LOW 档整组不分配（[releaseResources] 已把它们置 `null` ⇒ 这里直接返回即可）。
        if (seaLevel == SeaLevel.LOW) return
        var ts = 1
        while (ts * ts < SeasideOpBudget.FOAM_TILE_PX) ts++
        val half = ts / 2
        val cx = half.toDouble()
        val cy = half.toDouble()
        // ⛔ 硬约束：渐隐外径必须小于半边长。
        val fadeR = if (FOAM_TILE_FADE_R < half) FOAM_TILE_FADE_R else (half - 1).toFloat()
        val fadeIn = fadeR * 0.55
        val acc = FloatArray(ts * ts)
        var k = 0
        while (k < SeasideOpBudget.FOAM_TILES) {
            acc.fill(0f)
            // ── 软边斑块：椭圆核 `(1 − d²)`，⛔ 只扫自己的包围盒 ──────────────
            var b = 0
            while (b < FOAM_TILE_BLOB_N) {
                val h1 = SeasideWaves.hash2(k * 97 + b, 6401)
                val h2 = SeasideWaves.hash2(k * 97 + b, 6407)
                val h3 = SeasideWaves.hash2(k * 97 + b, 6413)
                val h4 = SeasideWaves.hash2(k * 97 + b, 6419)
                val bx = (0.22 + 0.56 * h1) * ts
                val by = (0.22 + 0.56 * h2) * ts
                val rx = (0.10 + 0.20 * h3) * ts
                val ry = (0.05 + 0.15 * h4) * ts
                val pa = FOAM_TILE_PEAK_A * (0.55 + 0.45 * h3)
                paintBlob(acc, ts, bx, by, rx, ry, pa)
                b++
            }
            // ── 撕碎丝缕：短线段（软宽度）⇒ 读作撕开的泡沫而不是圆斑 ────────────
            var s = 0
            while (s < FOAM_TILE_STREAK_N) {
                val h1 = SeasideWaves.hash2(k * 131 + s, 6427)
                val h2 = SeasideWaves.hash2(k * 131 + s, 6433)
                val h3 = SeasideWaves.hash2(k * 131 + s, 6439)
                val h4 = SeasideWaves.hash2(k * 131 + s, 6447)
                val x0 = (0.18 + 0.64 * h1) * ts
                val y0 = (0.18 + 0.64 * h2) * ts
                val ang = h3 * TAU
                val len = (0.08 + 0.22 * h4) * ts
                val pa = FOAM_TILE_PEAK_A * (0.40 + 0.60 * h1)
                paintStreak(acc, ts, x0, y0, x0 + cos(ang) * len, y0 + sin(ang) * len,
                    (1.6 + 3.4 * h2) * 0.5, pa)
                s++
            }
            // ── 合成 + 边缘 alpha 渐隐 ────────────────────────────────────────
            val px = IntArray(ts * ts)
            val fr = (PAL_FOAM_EDGE shr 16) and 0xFF
            val fg = (PAL_FOAM_EDGE shr 8) and 0xFF
            val fb = PAL_FOAM_EDGE and 0xFF
            var y = 0
            while (y < ts) {
                val dy = y - cy
                var x = 0
                while (x < ts) {
                    val dx = x - cx
                    val r = sqrt(dx * dx + dy * dy)
                    // `r ≥ fadeR` ⇒ 0；`r ≤ fadeIn` ⇒ 1（⛔ 边界处导数归零、不出硬环）
                    val f = SeasideWaves.smoothstep(fadeR.toDouble(), fadeIn.toDouble(), r)
                    var a = acc[y * ts + x] * f
                    if (a > 1.0) a = 1.0
                    val ai = (a * 255.0).toInt()
                    px[y * ts + x] = (ai shl 24) or (fr shl 16) or (fg shl 8) or fb
                    x++
                }
                y++
            }
            val bmp = Bitmap.createBitmap(ts, ts, Bitmap.Config.ARGB_8888)
            bmp.setPixels(px, 0, ts, 0, 0, ts, ts)
            foamTiles[k] = bmp.asImageBitmap()
            k++
        }
    }

    /**
     * 外海泡沫贴图的**预烘轮廓**（[foamTileOutV] / [foamTileOutStart] / [foamTileOutSub]）。
     *
     * ## 为什么要有它（§4.9.2）
     * 原型是每帧 22 次 `drawImage`（`SeaOpItem.OPEN_SEA_FOAM.opsLegacy = 22`）。合批把它
     * 折成 **8 块 × 一条 path × 一次 `fill`** ⇒ 必须有「贴图内容的矢量替身」，
     * 否则 8 次 `drawImage` 就是 8 次提交（超预算 7 次/浪）。
     *
     * 每张贴图 = [FOAM_TILE_BLOB_N] 个软边椭圆斑块 + [FOAM_TILE_STREAK_N] 条撕碎丝缕。
     * 这里把前者量化为 [FOAM_TILE_BLOB_POLY_N] 边形、后者量化为
     * [FOAM_TILE_STREAK_POLY_N] 点细带（沿轴 + 半宽的四个角）。
     * ⛔ **`hash2` 种子与 [buildFoamTiles] 逐位同源** ⇒ 轮廓与它替代的那张贴图内容对应
     *   （位置 / 半径 / 朝向 / 长度全部一致，只有「软边」被量化成硬边）。
     * ⚠️ **已记录的偏差**：贴图的**边缘 alpha 渐隐**（[FOAM_TILE_FADE_R]）与斑块内部的
     *   `1 − d²` 软衰减在矢量轮廓下**不可表达**（一次 `fill` 拿不到逐顶点 alpha）
     *   ⇒ 外沿会比原型硬。缓解手段是逐块 alpha 本身就被 §5.3 的 `min(a, 0.45)` 压着。
     */
    private fun buildFoamTileOutlines() {
        foamTileOutN = 0
        if (seaLevel == SeaLevel.LOW) return
        var k = 0
        while (k < SeasideOpBudget.FOAM_TILES) {
            foamTileOutStart[k] = foamTileOutN
            foamTileOutSub[k] = 0
            var b = 0
            while (b < FOAM_TILE_BLOB_N) {
                val h1 = SeasideWaves.hash2(k * 97 + b, 6401)
                val h2 = SeasideWaves.hash2(k * 97 + b, 6407)
                val h3 = SeasideWaves.hash2(k * 97 + b, 6413)
                val h4 = SeasideWaves.hash2(k * 97 + b, 6419)
                val bu = 0.22 + 0.56 * h1
                val bv = 0.22 + 0.56 * h2
                val ru = 0.10 + 0.20 * h3
                val rv = 0.05 + 0.15 * h4
                var a = 0
                while (a < FOAM_TILE_BLOB_POLY_N) {
                    val th = a.toDouble() / FOAM_TILE_BLOB_POLY_N.toDouble() * TAU
                    pushFoamTileVertex(bu + cos(th) * ru, bv + sin(th) * rv)
                    a++
                }
                foamTileOutSub[k] = foamTileOutSub[k] + 1
                b++
            }
            var s = 0
            while (s < FOAM_TILE_STREAK_N) {
                val h1 = SeasideWaves.hash2(k * 131 + s, 6427)
                val h2 = SeasideWaves.hash2(k * 131 + s, 6433)
                val h3 = SeasideWaves.hash2(k * 131 + s, 6439)
                val h4 = SeasideWaves.hash2(k * 131 + s, 6447)
                val x0 = 0.18 + 0.64 * h1
                val y0 = 0.18 + 0.64 * h2
                val ang = h3 * TAU
                val len = 0.08 + 0.22 * h4
                val hw = (1.6 + 3.4 * h2) * 0.25
                val dx = cos(ang) * len
                val dy = sin(ang) * len
                val nx = -sin(ang) * hw
                val ny = cos(ang) * hw
                pushFoamTileVertex(x0 + nx, y0 + ny)
                pushFoamTileVertex(x0 + dx + nx, y0 + dy + ny)
                pushFoamTileVertex(x0 + dx - nx, y0 + dy - ny)
                pushFoamTileVertex(x0 - nx, y0 - ny)
                foamTileOutSub[k] = foamTileOutSub[k] + 1
                s++
            }
            k++
        }
    }

    /** 往 [foamTileOutV] 追加一个 `(u, v)` 顶点（u-v 空间已归一化到 `0..1`）。 */
    private fun pushFoamTileVertex(u: Double, v: Double) {
        val i = foamTileOutN
        if (i + 1 >= foamTileOutV.size) return
        foamTileOutV[i] = u.toFloat()
        foamTileOutV[i + 1] = v.toFloat()
        foamTileOutN = i + 2
    }

    /**
     * 飞沫的**确定性烘焙点表**（原型 `SPLASH`，`seaside-preview.html:667-678`）。
     *
     * ⛔ **全部 `hash2`**、⛔ 零随机源（⛔ 禁 `Math.random`、⛔ 禁自建 `rng` 字段）
     *   ⇒ 从同一时刻重放必然逐点一致，这就是「确定性烘焙」这条规格的落点。
     * ⛔ 只在 [rebuildGeometry] 跑一次 ⇒ 逐帧只读。
     */
    private fun buildSplashTable() {
        var i = 0
        while (i < SPLASH_MAX) {
            splashU[i] = SeasideWaves.hash2(i, 1).toFloat()
            splashPeriod[i] = (SPLASH_PERIOD_LO + SPLASH_PERIOD_SPAN * SeasideWaves.hash2(i, 2)).toFloat()
            splashPhase[i] = (SPLASH_PHASE_SPAN * SeasideWaves.hash2(i, 3)).toFloat()
            splashReach[i] = (SPLASH_REACH_LO + SPLASH_REACH_SPAN * SeasideWaves.hash2(i, 4)).toFloat()
            splashLift[i] = (SPLASH_LIFT_SPAN * (SeasideWaves.hash2(i, 5) - 0.5)).toFloat()
            splashSize[i] = (SPLASH_SIZE_LO + SPLASH_SIZE_SPAN * SeasideWaves.hash2(i, 6)).toFloat()
            splashBright[i] = (SPLASH_BRIGHT_LO + SPLASH_BRIGHT_SPAN * SeasideWaves.hash2(i, 7)).toFloat()
            i++
        }
    }

    /**
     * 残沫白点的**烘焙表**（原型 `RESIDUE`，`seaside-preview.html:683-700`）——
     * `mulberry32(RESIDUE_LCG_SEED)` + 最小间距 [RESIDUE_MIN_SEP] 的**拒绝采样**。
     *
     * ⛔ **为什么必须是拒绝采样**：§4.4 明文「⛔ **不得连成纹路**（连成纹就退化成沙纹）」
     *   —— 均匀撒 52 个点会随机地连成短划，读作「又一层沙纹」。
     * ⛔ `v` 偏向**湿沙边缘**（`pow(rnd, 0.62)` ⇒ 越靠上越密）—— 残沫聚在水最后退到的
     *   地方，而不是均匀铺满干沙。
     * ⛔ **LCG 只在烘焙期跑**（⛔ 逐帧路径里零随机源、零 `rng` 字段）。
     */
    private fun buildResidueTable() {
        val rng = ResidueLcg(RESIDUE_LCG_SEED)
        val sep2 = RESIDUE_MIN_SEP * RESIDUE_MIN_SEP
        residueBaked = 0
        var tries = 0
        while (residueBaked < RESIDUE_MAX && tries < RESIDUE_TRIES) {
            tries++
            val u = RESIDUE_U_LO + RESIDUE_U_SPAN * rng.next()
            val v = powD(rng.next(), RESIDUE_V_POW) * RESIDUE_V_SPAN
            var ok = true
            var k = 0
            while (k < residueBaked) {
                val du = residueU[k] - u
                val dv = residueV[k] - v
                if (du * du + dv * dv < sep2) {
                    ok = false
                    break
                }
                k++
            }
            if (!ok) continue
            residueU[residueBaked] = u.toFloat()
            residueV[residueBaked] = v.toFloat()
            residueS[residueBaked] = (RESIDUE_S_LO + RESIDUE_S_SPAN * rng.next()).toFloat()
            residueA[residueBaked] = (RESIDUE_A_LO + RESIDUE_A_SPAN * rng.next()).toFloat()
            residueBaked++
        }
    }

    /**
     * ⭐ `mulberry32`（原型 `seaside-preview.html:454-461`，逐字照搬）——
     * ⛔ **只**在 [buildResidueTable] 里实例化一次（烘焙期）。
     *
     * ⚠️ 它**不是**「逐帧自建 rng」：[drawResidue] 每帧只读烘好的 [residueU] / [residueV] /
     *   [residueS] / [residueA]，本类**没有**任何逐帧持有的随机源字段。
     */
    private class ResidueLcg(@JvmField var s: Int) {
        fun next(): Double {
            s = s + 0x6D2B79F5
            // ⚠️ 原型写的是 `Math.imul(a, b)`；Kotlin 的 `Int * Int` 同样是**低 32 位环绕**
            //   （与 `Math.imul` 逐位等价），⛔ 这里不用 `Math.imul` 是为了不依赖它是否在
            //   本项目的 minSdk 编译环境里可见。
            var t = s xor (s ushr 15)
            t = t * (1 or s)
            t = t + ((t xor (t ushr 7)) * (61 or t))
            val r = t xor (t ushr 14)
            return (r.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
    }

    /**
     * 四支**逐帧要挪**的原生 `LinearGradient`（[buildWaveGradients]）。
     *
     * - [swellBodyNativePaint]：迎光 [PAL_RIDGE] → 背光 [PAL_TROUGH] 的 5 停靠体积渐变
     *   （原型 2864-2868 行），停靠位置 / alpha 见 [SWELL_BODY_STOP_POS] / [SWELL_BODY_STOP_A]。
     * - [seaFoamWashNativePaints]：`0` = 领头浪那支、`1` = 非领头浪那支（原型 2787-2799）。
     * - [wetWashNativePaint]：湿沙本体那**一支**（原型 `drawWetWash` 的 5 个色标，
     *   `seaside-preview.html:2055-2059`；色标位置 / alpha / RGB 见 [WET_WASH_STOP_POS] 一族）。
     *
     * ⛔ 渐变一律建在**归一化**的 `y ∈ [0, 1]` 上 ⇒ 逐帧只用 [gradMatrix] `setScale` +
     *   `postTranslate` 挪到本帧的 `yT..yB`，**零分配**。
     * ⛔ 归一化的代价：停靠 alpha 必须除以该支的最大项，逐帧用 `Paint.setAlpha` 乘回去
     *   （`Paint` 的 alpha 会调制着色器输出 ⇒ 数学上逐项相等）。
     */
    private fun buildWaveGradients() {
        val sbColors = IntArray(SWELL_BODY_STOP_POS.size)
        var i = 0
        while (i < SWELL_BODY_STOP_POS.size) {
            val rgb = if (SWELL_BODY_STOP_IS_RIDGE[i]) PAL_RIDGE else PAL_TROUGH
            sbColors[i] = argbWith(SWELL_BODY_STOP_A[i].toDouble(), rgb)
            i++
        }
        swellBodyNativePaint.shader = LinearGradient(
            0f, 0f, 0f, 1f, sbColors, SWELL_BODY_STOP_POS, Shader.TileMode.CLAMP
        )
        seaFoamWashNativePaints[0].shader = LinearGradient(
            0f, 0f, 0f, 1f, argbWith(WASH_LD_STOP_A, WASH_LD_STOP_RGB),
            WASH_LD_STOP_POS, Shader.TileMode.CLAMP
        )
        seaFoamWashNativePaints[1].shader = LinearGradient(
            0f, 0f, 0f, 1f, argbWith(WASH_NL_STOP_A, WASH_NL_STOP_RGB),
            WASH_NL_STOP_POS, Shader.TileMode.CLAMP
        )
        // ── [wetWashNativePaint]：湿沙本体那**一支**（2026-10-05，取代 [WET_RIBBON_N] 条 ribbon）──
        // ⛔ 色标的 RGB / alpha 逐字取 `seaside-preview.html:2055-2059`；只有 `t = 0` 那一档
        //   从 `0.76` 改成 **0**、峰值提前到 `t = 0.14`（驼峰）—— 理由与代价见
        //   [WET_WASH_STOP_POS] 的 KDoc（一支共享渐变的 `t=0` 只能落在全幅 `min(shoreYs)` 上，
        //   沿用原型的「t=0 最深」会让那一列的水线正好压在最深色标上 = 会游走的暗边）。
        val wwColors = IntArray(WET_WASH_STOP_POS.size)
        i = 0
        while (i < wwColors.size) {
            val rr = (WET_WASH_STOP_RGB[i] shr 16) and 0xFF
            val gg = (WET_WASH_STOP_RGB[i] shr 8) and 0xFF
            val bb = WET_WASH_STOP_RGB[i] and 0xFF
            wwColors[i] = AndroidColor.argb(
                alpha255(WET_WASH_STOP_A[i].toDouble() / WET_WASH_PEAK_A), rr, gg, bb
            )
            i++
        }
        wetWashNativePaint.shader = LinearGradient(
            0f, 0f, 0f, 1f, wwColors, WET_WASH_STOP_POS, Shader.TileMode.CLAMP
        )
    }

    /** 把「归一化 alpha 数组 + 不透明 RGB 数组」合成原生渐变要的 `IntArray`（⛔ 只烘焙期调）。 */
    private fun argbWith(a: FloatArray, rgb: IntArray): IntArray {
        val out = IntArray(rgb.size)
        var i = 0
        while (i < rgb.size) {
            out[i] = argbWith(a[i].toDouble(), rgb[i])
            i++
        }
        return out
    }

    /** 单个 ARGB 打包（⛔ 只烘焙期调）。 */
    private fun argbWith(a: Double, rgb: Int): Int = AndroidColor.argb(
        alpha255(a), (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF
    )

    /** 第 `i` 次过境与下一次之间的间隔（ms）—— ⛔ 纯函数、纯 `hash2`。 */
    private fun crabGapMs(i: Int): Double {
        val u = 0.88 * SeasideWaves.clamp(
            0.5 + (SeasideWaves.hash2(i, 9211) - 0.5) * 1.30, 0.0, 1.0
        ) + 0.12 * SeasideWaves.hash2(i, 9213)
        return CRAB_GAP_LO + (CRAB_GAP_HI - CRAB_GAP_LO) * u
    }

    /**
     * 128px 颗粒图案（[SeasideOpBudget.GRAIN_PX]）—— **随 [postFx] 之外的自有颗粒层**。
     *
     * 取值：§5.7 的 `postFx.grain = 0.020f`。⚠️ [postFx] 已按 §4.9.2 删掉 `grain` 通道
     * （双通道 2.07 Mpx/帧）⇒ 这个数值搬进**图案自身的 alpha**，[SeasideTest] ⑥b
     * 仍然只认 `PostFx(vignette = 0.30f)`。
     *
     * 形态：逐像素白噪声，`alpha = GRAIN_A·|2v − 1|`（`v = 0.5` 处最淡 ⇒ 稀疏点状颗粒，
     * 而不是一层灰雾），亮度由 `v` 决定 ⇒ 亮暗颗粒各半。
     */
    private fun buildGrain() {
        var n = 1
        while (n * n < SeasideOpBudget.GRAIN_PX) n++
        val px = IntArray(n * n)
        var y = 0
        while (y < n) {
            var x = 0
            while (x < n) {
                val v = SeasideWaves.hash2(x, y)
                val d = abs(v - 0.5) * 2.0
                val ai = (d * GRAIN_POST_A * 255.0).toInt()
                val lum = (v * 255.0).toInt()
                px[y * n + x] = (ai shl 24) or (lum shl 16) or (lum shl 8) or lum
                x++
            }
            y++
        }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        grainTex = bmp.asImageBitmap()
    }

    /**
     * 16×16 晶格斑驳图案（[SeasideOpBudget.MOTTLE_PX]）—— 水体场行/列级粗糙度的兜底。
     *
     * 取值：§5.4 `MOTTLE_A = 0.030`。形态：**双线性晶格值噪声**（⛔ 不含任何高频 ⇒
     * 无摩尔纹风险，这正是 §4.2 选它的理由）；图集本身就是那张 `16 × 16` 的晶格，
     * 采样端的插值是逐像素双线性（下一批），⛔ 不在烘焙期放大（放大 16 倍的模糊块
     * 会把晶格变成马赛克）。
     */
    private fun buildMottle() {
        var n = 1
        while (n * n < SeasideOpBudget.MOTTLE_PX) n++
        val px = IntArray(n * n)
        var y = 0
        while (y < n) {
            var x = 0
            while (x < n) {
                val v = SeasideWaves.vnoise2(x.toDouble(), y.toDouble(), 6501)
                val lum = (v * 255.0).toInt()
                val ai = (MOTTLE_A * 255.0).toInt()
                px[y * n + x] = (ai shl 24) or (lum shl 16) or (lum shl 8) or lum
                x++
            }
            y++
        }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        mottleTex = bmp.asImageBitmap()
    }

    /**
     * 水体场低分辨率画布。分辨率 = [SeasideOpBudget.fieldPx] 的同一公式
     * （`scale = clamp(ceil(sqrt(W·seaPx / 52000)), 4, 14)`）—— ⛔ 与 G13 门**必须**同公式，
     * 否则 native 堆核算与真机占用会分叉。
     * ⛔ **LOW 档不分配**（§4.7②「只留底色渐变」）。
     *
     * 内容 = §4.2 / 原型 `drawSeaField`（`seaside-preview.html:1570-1658`）的**完整逐像素场**：
     * - 逐列相位：`colPhase`（四个不同频率的正弦叠加再 `mod 2π`，让横条纹不是笔直的）、
     *   `colMid`、`colFine`、`roughX = 0.22 + 1.05·fbmNorm(u·2.6 + 71.2, 6201, 2)`
     *   —— ⛔ 必须是 `fbmNorm`（§4.2：`0.28 + 0.92·(0.5+0.5·fbm1)` 只有 `0.54..0.94`，
     *   平静空档做不出来）。
     * - 逐行相位：`rowPhase`（§5.5 三档噪声相位 `8/19/43` 里的**大波**档，
     *   ⛔ 先经 3 阶 fBm **扭曲** `v` 再取模 ⇒ 不是一排贯穿全幅的规则梳子）、
     *   `rowMid` / `rowFine`、`rowAmp = 0.10 + 1.55·fbmNorm(v·4.7 + 9.3, 6101, 2)`、
     *   `roughY = 0.20 + 1.10·fbmNorm(v·3.7 + 51.1, 6301, 2)`。
     * - 底色深度线索：`dep = clamp(1 − 0.78·v^0.90·(0.72 + depthBias·0.55), 0, 1)`
     *   在 [PAL_SEA_FAR] ↔ [PAL_SEA_DEEP] 之间插值 —— ⚠️ **顶部深、近浪线浅**
     *   （§4.2 原文；命名与取景方向相反，原型自己在 `drawSeaField:1618` 承认了）。
     * - 浅滩带：`shoalW = smoothstep(0.84, 1.03, v)·(0.30 + 0.22·energy)` 压向 [PAL_SHOAL]。
     * - 涌浪明暗：`(sb·0.44 + mid·0.36 + fine·0.20)·(rowAmp·roughX·roughY)·0.55`，
     *   `sb = s + 0.20·s³`（廉价陡化）；正 `light` 提亮向 [PAL_RIDGE]（`×0.30·shadeBoost`），
     *   负 `light` 压暗向 [PAL_TROUGH]（`×0.26·shadeBoost`）。
     *
     * ⚠️ **本函数烘的是 `t = 0` / `energy = 0` 的场**（[rebuildGeometry] 里没有时间轴）。
     *   逐列 / 逐行的相位与粗糙度数组、以及 [fieldPix] 都**预分配并保留**下来 ——
     *   原型里这个循环本来就每帧跑（`drawSeaField` 是逐帧函数），而它的分辨率只有
     *   `W/scale × H·SEA_BOTTOM_K/scale`（1080p ≈ 320×148），逐帧重写像素 + 一次放大 blit
     *   才是「代价与分辨率固定、与帧率无关」的形态。⇒ 下一批把同一个循环搬进
     *   [drawSeaField] 即可，⛔ 不需要新的分配点。
     */
    private fun buildFieldCanvas(w: Float, h: Float) {
        // ⛔ LOW 档整张不分配（§4.7②；[releaseResources] 已把它置 `null`）。
        if (seaLevel == SeaLevel.LOW) {
            fieldW = 0
            fieldH = 0
            return
        }
        val seaH = h.toDouble() * SeasideWaves.SEA_BOTTOM_K
        if (w < 1f || seaH < 1.0) return
        // ⛔ 与 `SeasideOpBudget.fieldPx` **逐字同一个公式**（G13 的接缝）：
        //   `seaPx = w · seaH`（**面积**）、`raw = sqrt(w · seaPx / 52000)`。
        //   ⚠️ 少乘一个 `w` 会让 scale 从 14 掉到 6 ⇒ 位图实际像素是 G13 核算的 5 倍，
        //   native 堆账与真机占用直接分叉。
        val seaPx = w.toDouble() * seaH
        var scale = ceil(sqrt(w.toDouble() * seaPx / SeasideOpBudget.FIELD_PX_DIVISOR.toDouble()))
        if (scale < SeasideOpBudget.FIELD_SCALE_MIN.toDouble()) scale = SeasideOpBudget.FIELD_SCALE_MIN.toDouble()
        if (scale > SeasideOpBudget.FIELD_SCALE_MAX.toDouble()) scale = SeasideOpBudget.FIELD_SCALE_MAX.toDouble()
        val fw = ceil(w.toDouble() / scale).toInt().coerceAtLeast(2)
        val fh = ceil(seaH / scale).toInt().coerceAtLeast(2)
        fieldW = fw
        fieldH = fh

        fieldColPhase = DoubleArray(fw)
        fieldColMid = DoubleArray(fw)
        fieldColFine = DoubleArray(fw)
        fieldRoughX = DoubleArray(fw)
        fieldRowPhase = DoubleArray(fh)
        fieldRowMid = DoubleArray(fh)
        fieldRowFine = DoubleArray(fh)
        fieldRowAmp = DoubleArray(fh)
        fieldRoughY = DoubleArray(fh)
        fieldPix = IntArray(fw * fh)

        val fw1 = (fw - 1).toDouble()
        val fh1 = (fh - 1).toDouble()
        // ── 逐列（t = 0）─────────────────────────────────────────────────────
        var i = 0
        while (i < fw) {
            val u = i.toDouble() / fw1
            val wBig = 0.46 * sin(u * TAU * 1.3) +
                0.27 * sin(u * TAU * 2.7 + 1.7) +
                0.15 * sin(u * TAU * 4.9 + 4.1) +
                0.08 * sin(u * TAU * 8.3 + 2.6)
            fieldColPhase[i] = wrapTau(wBig)
            val wMid = 0.34 * sin(u * TAU * 3.9 + 0.6) + 0.21 * sin(u * TAU * 7.3 + 3.4)
            fieldColMid[i] = wrapTau(wMid)
            fieldColFine[i] = wrapTau(u * TAU * 11.0)
            fieldRoughX[i] = 0.22 + 1.05 * SeasideWaves.fbm_norm(u * 2.6 + 71.2, 6201, 2)
            i++
        }
        // ── 逐行（t = 0）─────────────────────────────────────────────────────
        var j = 0
        while (j < fh) {
            val v = j.toDouble() / fh1
            // ⛔ 先扭曲 v 再取模（§4.2 红线：`v·TAU·N_BIG` 一个完美等距的梳子）。
            val warp = 2.9 * SeasideWaves.fbm_signed(v * 3.1 + 11.7, 6001, 3)
            fieldRowPhase[j] = wrapTau(v * TAU * FIELD_N_BIG + warp)
            fieldRowMid[j] = wrapTau(v * TAU * FIELD_N_MID)
            fieldRowFine[j] = wrapTau(v * TAU * FIELD_N_FINE)
            fieldRowAmp[j] = 0.10 + 1.55 * SeasideWaves.fbm_norm(v * 4.7 + 9.3, 6101, 2)
            fieldRoughY[j] = 0.20 + 1.10 * SeasideWaves.fbm_norm(v * 3.7 + 51.1, 6301, 2)
            j++
        }

        // ── 逐像素（energy = 0 ⇒ shadeBoost = 1、depthBias = DEPTH_BIAS_LO、
        //    shoalWidth = SHOAL_LO）──────────────────────────────────────────
        val depthK = SEA_DEPTH_GAIN * (SEA_DEPTH_BIAS0 + SeasideAudioMap.DEPTH_BIAS_LO * 0.55)
        val shoalK = SeasideAudioMap.SHOAL_LO
        val farR = (PAL_SEA_FAR shr 16) and 0xFF
        val farG = (PAL_SEA_FAR shr 8) and 0xFF
        val farB = PAL_SEA_FAR and 0xFF
        val deepR = (PAL_SEA_DEEP shr 16) and 0xFF
        val deepG = (PAL_SEA_DEEP shr 8) and 0xFF
        val deepB = PAL_SEA_DEEP and 0xFF
        val shoalR = (PAL_SHOAL shr 16) and 0xFF
        val shoalG = (PAL_SHOAL shr 8) and 0xFF
        val shoalB = PAL_SHOAL and 0xFF
        j = 0
        while (j < fh) {
            val v = j.toDouble() / fh1
            val dep = SeasideWaves.clamp(1.0 - depthK * powD(v, SEA_DEPTH_POW), 0.0, 1.0)
            val br = farR + (deepR - farR) * dep
            val bg = farG + (deepG - farG) * dep
            val bb = farB + (deepB - farB) * dep
            val shoalW = SeasideWaves.smoothstep(SEA_SHOAL_LO, SEA_SHOAL_HI, v) * shoalK
            val rp = fieldRowPhase[j]
            val rm = fieldRowMid[j]
            val rf = fieldRowFine[j]
            val ra = fieldRowAmp[j] * SEA_BAND_GAIN
            val ry = fieldRoughY[j]
            val row = j * fw
            i = 0
            while (i < fw) {
                val s = SeasideWaves.fsin(rp + fieldColPhase[i])
                val sb = s + SEA_BAND_STEEP * s * s * s
                val amp = ra * ry * fieldRoughX[i]
                val light = (sb * SEA_BAND_W_BIG +
                    SeasideWaves.fsin(rm + fieldColMid[i]) * SEA_BAND_W_MID +
                    SeasideWaves.fsin(rf + fieldColFine[i]) * SEA_BAND_W_FINE) * amp
                var r: Double
                var g: Double
                var b: Double
                if (light > 0.0) {
                    val kk = if (light * SEA_RIDGE_GAIN < 1.0) light * SEA_RIDGE_GAIN else 1.0
                    r = br + (PAL_RIDGE_R - br) * kk
                    g = bg + (PAL_RIDGE_G - bg) * kk
                    b = bb + (PAL_RIDGE_B - bb) * kk
                } else {
                    val kk = -light * SEA_TROUGH_GAIN
                    r = br + (PAL_TROUGH_R - br) * kk
                    g = bg + (PAL_TROUGH_G - bg) * kk
                    b = bb + (PAL_TROUGH_B - bb) * kk
                }
                if (shoalW > 0.0) {
                    r += (shoalR - r) * shoalW
                    g += (shoalG - g) * shoalW
                    b += (shoalB - b) * shoalW
                }
                fieldPix[row + i] = packOpaque(r, g, b)
                i++
            }
            j++
        }
        val bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(fieldPix, 0, fw, 0, 0, fw, fh)
        fieldTex = bmp.asImageBitmap()
    }

    /**
     * 焦散连通胞壁网拓扑（`20×9` 节点 / `331` 条边，⛔ **只烘坐标与属性，无像素运算**）。
     * ⛔ **不**周期环绕、⛔ 幅度 ≤ ±0.3 格（否则读作划痕而不是胞）。
     *
     * 去规整化三招（§4.2「否则读成方格纸」，逐条照搬）：
     * 1. **低频平滑值噪声 warp**（[CAUSTIC_WARP_OCT] 阶、衰减 [CAUSTIC_WARP_DECAY]）
     *    分别作用在列坐标与行坐标上 ⇒ 胞格宽窄不均。
     * 2. **每行一个哈希 `shear`**（±[CAUSTIC_CELL_JIT] 格）⇒ 行与行的壁角度不同
     *    （同行内仍近似平行）。
     * 3. **逐节点哈希抖动** ±[CAUSTIC_CELL_JIT] 格。
     *
     * ⛔ 三个幅度**一律压在 ±0.3 格以内** —— 超过这个量相邻胞格会翻转自交、网直接破掉。
     * ⛔ **边界节点钉死在网格线上**（不加抖动、不越界）：这既避免了 §4.2 说的
     * 「环绕 ⇒ 右/下边界生成横贯全幅的长墙」，也避免了「钳位 ⇒ 左/上边界糊成一堵墙」。
     *
     * 每格只连**右**与**下**两个邻居 ⇒ `(CNX−1)·CNY + CNX·(CNY−1) = 331` 条边、端点共享。
     * 边属性：3 档亮度档号（§4.2 的 `0.62 / 0.44 / 0.28`）、垂直于壁的弓起量、相位。
     */
    private fun buildCausticNet(w: Float, h: Float) {
        val nx = CAUSTIC_CELL_NX
        val ny = CAUSTIC_CELL_NY
        val cellW = w.toDouble() / nx.toDouble()
        val cellH = seaBottomPx.toDouble() / ny.toDouble()
        if (cellW <= 0.0 || cellH <= 0.0) return
        val ny1 = (ny - 1).toDouble().coerceAtLeast(1.0)
        var j2 = 0
        while (j2 < ny) {
            val rowWarp = lowFreqWarp(j2 * 0.53 + 3.1, 5907) * CAUSTIC_CELL_JIT
            // ① 每行一个哈希 shear：离中间行越远平移越多 ⇒ 行与行的壁角度不同
            val shear = SeasideWaves.hash2(j2, 5901) * 2.0 - 1.0
            val shearCells = shear * CAUSTIC_CELL_JIT * (j2.toDouble() / ny1 * 2.0 - 1.0)
            var i2 = 0
            while (i2 < nx) {
                val node = j2 * nx + i2
                val colWarp = lowFreqWarp(i2 * 0.53 + 17.7, 5911) * CAUSTIC_CELL_JIT
                // ⛔ 边界节点钉死（见 KDoc）
                val u = if (i2 == 0) 0.0
                else if (i2 == nx - 1) (nx - 1).toDouble()
                else i2 + colWarp + (SeasideWaves.hash2(node, 5917) * 2.0 - 1.0) * CAUSTIC_CELL_JIT
                val vv = if (j2 == 0) 0.0
                else if (j2 == ny - 1) (ny - 1).toDouble()
                else j2 + rowWarp + (SeasideWaves.hash2(node, 5923) * 2.0 - 1.0) * CAUSTIC_CELL_JIT
                causticNodeX[node] = ((u + shearCells) * cellW).toFloat()
                causticNodeY[node] = (vv * cellH).toFloat()
                i2++
            }
            j2++
        }
        // ── 边：每格连右 + 下（⛔ 不环绕：最后一列/最后一行没有右/下邻居）────────
        var e = 0
        var jj = 0
        while (jj < ny) {
            var ii = 0
            while (ii < nx) {
                val a = jj * nx + ii
                if (ii < nx - 1) {
                    causticEdgeA[e] = a
                    causticEdgeB[e] = a + 1
                    bakeCausticEdge(e)
                    e++
                }
                if (jj < ny - 1) {
                    causticEdgeA[e] = a
                    causticEdgeB[e] = a + nx
                    bakeCausticEdge(e)
                    e++
                }
                ii++
            }
            jj++
        }
    }

    /** 单条焦散壁的 3 档亮度档号 + 弓起量 + 相位（§4.2：正弦弓起，⛔ 不出现笔直硬线）。 */
    private fun bakeCausticEdge(e: Int) {
        val h = SeasideWaves.hash2(e, 5931)
        causticEdgeLevel[e] = if (h < 0.3333) 0 else if (h < 0.6667) 1 else 2
        causticEdgeBow[e] = (SeasideWaves.hash2(e, 5937) * 2.0 - 1.0).toFloat()
        causticEdgePhase[e] = (LACE_EDGE_PH_K * SeasideWaves.hash2(e, 5941)).toFloat()
    }

    /**
     * 泡沫蕾丝网拓扑（`10×4` 抖动网格 + 环绕包边丝 + 竖筋/斜筋/网眼填充）。
     * ⛔ **端点必须钉死在共享节点上** —— 那才是它读作泡沫（网眼连通）的原因，不是形状。
     *
     * 逐项照搬 §4.3.6 / 原型 `buildLaceNet`（`seaside-preview.html:2564-2617`）：
     * - 节点：`10 × 4` 抖动网格，行 `v = 0.03 / 0.22 / 0.40 / 0.58`（占浪带宽）；
     *   `u = clamp((gx + 0.5)/10 + (hash2 − 0.5)·0.12, 0.015, 0.985)`、
     *   `v = clamp(rowV + (hash2 − 0.5)·0.10, 0.01, 0.92)`。
     * - 四类线（[laceEdgeKind]）：`0` 沿岸长丝 / `1` 竖直短筋 / `2` 斜筋 / `3` 网眼填充。
     * - 长丝每行 9 条 + **2 条环绕屏边的包边丝**（`xs = ±1`）⇒ 整行绕屏一圈无缝。
     * - 竖筋出现率 [LACE_RIB_P] = 0.72；斜筋 0.42 / 0.22 两族；网眼填充每格一根
     *   更细的短丝（⛔ 它的 `v` **刻意错开**、不碰网格端点 —— 原型 2606 行的原话）。
     * - 边的 `ph / f1 / amp / rate / wj / aj` 全部来自 `hash2`（原型 2580-2583）；
     *   `wj` 在这里就**按边的平均 `v` 分成 0..2 的距离档**写进 [laceEdgeQ]
 *   （⛔ 逐帧绝不重算线宽；类号另存在 [laceEdgeKind] ⇒ 下标 = `kind · 3 + band`）。
     */
    private fun buildLaceNet(w: Float, h: Float) {
        laceBandPx = h * SEA_BAND.toFloat()
        val nx = LACE_NX
        val ny = LACE_NY
        var n = 0
        var gy = 0
        while (gy < ny) {
            var gx = 0
            while (gx < nx) {
                val u = SeasideWaves.clamp(
                    (gx + 0.5).toDouble() / nx.toDouble() +
                        (SeasideWaves.hash2(n, 5501) - 0.5) * LACE_NODE_U_JIT,
                    LACE_NODE_U_CLAMP_LO, LACE_NODE_U_CLAMP_HI
                )
                val v = SeasideWaves.clamp(
                    LACE_ROW_V[gy].toDouble() +
                        (SeasideWaves.hash2(n, 5507) - 0.5) * LACE_NODE_V_JIT,
                    LACE_NODE_V_CLAMP_LO, LACE_NODE_V_CLAMP_HI
                )
                laceNodeU[n] = u.toFloat()
                laceNodeV[n] = v.toFloat()
                n++
                gx++
            }
            gy++
        }
        laceEdgeCount = 0
        var se = 1
        // ── 沿岸长丝（每行 9 条）+ 左右两条包边丝（`xs = ±1`）──────────────
        gy = 0
        while (gy < ny) {
            val r = gy * nx
            var gx = 0
            while (gx < nx - 1) {
                pushLaceEdge(r + gx, r + gx + 1, 0, 0, se); se++
                gx++
            }
            pushLaceEdge(r + nx - 1, r, 0, 1, se); se++
            pushLaceEdge(r, r + nx - 1, 0, -1, se); se++
            gy++
        }
        // ── 竖直短筋（把行连成网眼）+ 斜筋（打散网格感）────────────────────
        var ci = 1
        gy = 0
        while (gy < ny - 1) {
            var gx = 0
            while (gx < nx) {
                val a = gy * nx + gx
                val b = (gy + 1) * nx + gx
                if (SeasideWaves.hash2(ci, 5701) < LACE_RIB_P) {
                    pushLaceEdge(a, b, 0, 1, se); se++
                }
                if (gx < nx - 1) {
                    if (SeasideWaves.hash2(ci, 5703) < LACE_DIAG_A_P) {
                        pushLaceEdge(a, b + 1, 0, 2, se); se++
                    }
                    if (SeasideWaves.hash2(ci, 5705) < LACE_DIAG_B_P) {
                        pushLaceEdge(a + 1, b, 0, 2, se); se++
                    }
                }
                ci++
                gx++
            }
            gy++
        }
        // ── 网眼填充：每个格子一根更细的短丝（`v` 错开，⛔ 不碰网格端点）──────
        gy = 0
        while (gy < ny - 1) {
            var gx = 0
            while (gx < nx) {
                if (SeasideWaves.hash2(ci, 5801) <= LACE_FILL_KEEP) {
                    val a = gy * nx + gx
                    val b = gy * nx + ((gx + 1) % nx)
                    val vm = (laceNodeV[a] + laceNodeV[a + nx]).toDouble() * 0.5
                    val fa = SeasideWaves.clamp(
                        vm + (SeasideWaves.hash2(ci, 5803) - 0.5) * LACE_FILL_V_JIT,
                        LACE_FILL_V_CLAMP_LO, LACE_FILL_V_CLAMP_HI
                    )
                    val fb = SeasideWaves.clamp(
                        vm + (SeasideWaves.hash2(ci, 5805) - 0.5) * LACE_FILL_V_JIT,
                        LACE_FILL_V_CLAMP_LO, LACE_FILL_V_CLAMP_HI
                    )
                    val e = pushLaceEdgeRaw(
                        laceNodeU[a].toDouble(), fa,
                        laceNodeU[b].toDouble(), fb,
                        if (gx == nx - 1) 1 else 0, 3, se
                    )
                    if (e >= 0) {
                        laceEdgeV0[e] = fa.toFloat()
                        laceEdgeV1[e] = fb.toFloat()
                    }
                    se++
                } else {
                    ci++
                }
                gx++
            }
            gy++
        }
    }

    /** 追加一条**端点共享**的蕾丝边（两个端点都是烘好的节点下标）。 */
    private fun pushLaceEdge(a: Int, b: Int, xs: Int, kind: Int, se: Int) {
        val e = pushLaceEdgeRaw(
            laceNodeU[a].toDouble(), laceNodeV[a].toDouble(),
            laceNodeU[b].toDouble(), laceNodeV[b].toDouble(),
            xs, kind, se
        )
        if (e < 0) return
    }

    /**
     * 蕾丝边的公共写入（几何 + 属性 + **距离档**量化）。
     *
     * ⛔ [laceEdgeQ] 存的是**距离档号**（`0 / 1 / 2`，§4.3.6 的 3 个「离前缘距离」档），
     * 判据是这条边两端 `v` 的均值（`< [LACE_BAND_V_HI] → 0`、`< [LACE_BAND_V_TOP] → 1`、
     * 否则 `2`）—— 与原型 `drawFoamLace` 的档位判据逐字一致。
     * ⛔ 边的**类号**另存在 [laceEdgeKind]（`0..3`）⇒ [buildLaceStrokes] 的下标
     * `kind · LACE_BAND_N + band` 由这两个身份直接相乘得到，⛔ 逐帧不重算线宽。
     *
     * @return 写入的下标；容量已满返回 `-1`（⛔ 不扩容、不抛 —— 溢出即静默丢边，
     *   而 [laceEdgeMax] 已按规格留了余量）。
     */
    private fun pushLaceEdgeRaw(
        u0: Double, v0: Double,
        u1: Double, v1: Double,
        xs: Int, kind: Int, se: Int,
    ): Int {
        val e = laceEdgeCount
        if (e >= laceEdgeMax) return -1
        laceEdgeCount = e + 1
        laceEdgeU0[e] = u0.toFloat()
        laceEdgeV0[e] = v0.toFloat()
        laceEdgeU1[e] = u1.toFloat()
        laceEdgeV1[e] = v1.toFloat()
        laceEdgeXs[e] = xs
        laceEdgeKind[e] = kind
        laceEdgePh[e] = (LACE_EDGE_PH_K * SeasideWaves.hash2(se, 5601)).toFloat()
        laceEdgeF1[e] = (LACE_EDGE_F1_LO + LACE_EDGE_F1_SPAN * SeasideWaves.hash2(se, 5603)).toFloat()
        laceEdgeAmp[e] = (LACE_EDGE_AMP_LO + LACE_EDGE_AMP_SPAN * SeasideWaves.hash2(se, 5605)).toFloat()
        laceEdgeRate[e] = (LACE_EDGE_RATE_LO + LACE_EDGE_RATE_SPAN * SeasideWaves.hash2(se, 5607)).toFloat()
        laceEdgeAj[e] = (LACE_EDGE_AJ_LO + LACE_EDGE_AJ_SPAN * SeasideWaves.hash2(se, 5611)).toFloat()
        val vm = (v0 + v1) * 0.5
        laceEdgeQ[e] = if (vm < LACE_BAND_V_HI) 0 else if (vm < LACE_BAND_V_TOP) 1 else 2
        return e
    }

    // ═══════════════════════════ 烘焙期的纯函数工具 ═══════════════════════════
    //
    // ⛔ 全部**只在烘焙函数里**被调用（[rebuildGeometry] 的调用链上），不在任何
    //   `DrawScope.draw*` 函数体内 ⇒ 不违反 [SeasideTest] ⑤ 的逐帧零构造断言。
    // ⛔ 零分配：全部走 `Float`/`Double` 标量，没有 lambda、没有容器。

    /**
     * `base^exp` 的 Double 版。
     *
     * ⚠️ ⛔ **刻意不写 `kotlin.math.pow`** —— 本文件里 `pow(a, b)` 的实参形式会让
     *   Kotlin 2.3 的重载决议选中 `Float.pow` 那一族（报「receiver type mismatch」）。
     *   直接走 [Math.pow] 一行到底，也不必再 import。
     */
    private fun powD(base: Double, exp: Double): Double = Math.pow(base, exp)

    /** 把一个 `0..255` 的 Double 通道夹紧成 `Int`（⛔ 只在烘焙期调用）。 */
    private fun clampByte(v: Double): Int = if (v < 0.0) 0 else if (v > 255.0) 255 else v.toInt()

    /**
     * 两个 ARGB 整数按 `t ∈ 0..1` 线性插值（⛔ **丢弃 alpha**，统一取不透明）。
     * 只在烘焙期逐像素/逐行调用，因此不做四舍五入以外的任何处理。
     */
    private fun mixArgb(a: Int, b: Int, t: Double): Int {
        val u = if (t < 0.0) 0.0 else if (t > 1.0) 1.0 else t
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * u).toInt()
        val g = (ag + (bg - ag) * u).toInt()
        val bl = (ab + (bb - ab) * u).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** 把三个 `0..255` 的 Double 通道夹紧并打包成不透明 ARGB（⛔ 每像素热路径）。 */
    private fun packOpaque(r: Double, g: Double, b: Double): Int {
        val ri = if (r < 0.0) 0 else if (r > 255.0) 255 else r.toInt()
        val gi = if (g < 0.0) 0 else if (g > 255.0) 255 else g.toInt()
        val bi = if (b < 0.0) 0 else if (b > 255.0) 255 else b.toInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /**
     * 模 2π 回绕到 `[0, 2π)`（原型里满地都是的 `((x % TAU) + TAU) % TAU`）。
     * ⛔ 逐像素程序化场里**取模回绕就是不连续**（§4.3.5 红线 2.2）—— 这条只用在
     * **烘焙期**的相位场与条纹计算上，那里的回绕不会造成拓扑跳变。
     */
    private fun wrapTau(a: Double): Double {
        var x = a % TAU
        if (x < 0.0) x += TAU
        return x
    }

    /**
     * 低频平滑值噪声（[CAUSTIC_WARP_OCT] 阶、衰减 [CAUSTIC_WARP_DECAY]），返回 `−1..1`。
     *
     * ⚠️ ⛔ **不能**直接用 [SeasideWaves.fbm1]：它的八度衰减写死是 `0.60`，而 §4.2
     * 要的是 `0.55`；同理不能只取两阶（§4.2 明写「4 阶」）。⇒ 这里手写一遍循环，
     * 噪声源用 [SeasideWaves.vnoise2]（⛔ 不自建 hash，保证与模拟层同一套确定性源）。
     */
    private fun lowFreqWarp(x: Double, seed: Int): Double {
        var a = 0.0
        var amp = 0.5
        var f = 1.0
        var o = 0
        while (o < CAUSTIC_WARP_OCT) {
            val n = SeasideWaves.vnoise2(x * f, o * 1.37 + seed * 0.001, seed + o * 131)
            a += amp * (n * 2.0 - 1.0)
            f *= 2.07
            amp *= CAUSTIC_WARP_DECAY
            o++
        }
        return if (a > 1.0) 1.0 else if (a < -1.0) -1.0 else a
    }

    /**
     * 泡沫贴图的**软边斑块**：椭圆核 `(1 − d²)`，⛔ 只扫自己的包围盒（⛔ 不逐像素遍历全图）。
     * 累加进 [acc]（`FloatArray`，按 max 合成 ⇒ 叠出来的斑块不会糊成一片死白）。
     */
    private fun paintBlob(
        acc: FloatArray, ts: Int,
        bx: Double, by: Double, rx: Double, ry: Double, peak: Double,
    ) {
        if (rx < 1.0) return
        if (ry < 1.0) return
        val invRx = 1.0 / rx
        val invRy = 1.0 / ry
        val x0 = (bx - rx).toInt().coerceAtLeast(0)
        val x1 = (bx + rx).toInt().coerceAtMost(ts - 1)
        val y0 = (by - ry).toInt().coerceAtLeast(0)
        val y1 = (by + ry).toInt().coerceAtMost(ts - 1)
        var y = y0
        while (y <= y1) {
            val dy = (y + 0.5 - by) * invRy
            var x = x0
            while (x <= x1) {
                val dx = (x + 0.5 - bx) * invRx
                val d2 = dx * dx + dy * dy
                if (d2 < 1.0) {
                    val a = peak * (1.0 - d2)
                    val i = y * ts + x
                    if (a > acc[i]) acc[i] = a.toFloat()
                }
                x++
            }
            y++
        }
    }

    /**
     * 泡沫贴图的**撕碎丝缕**：一条软宽度的短线段（沿轴向 `1 − s`、横向 `1 − d` 相乘）。
     * ⛔ 同样只扫包围盒；`peak` 是该丝缕的中心 alpha。
     */
    private fun paintStreak(
        acc: FloatArray, ts: Int,
        x0: Double, y0: Double, x1: Double, y1: Double,
        halfW: Double, peak: Double,
    ) {
        val w = if (halfW < 0.5) 0.5 else halfW
        val loX = (minOf(x0, x1) - w).toInt().coerceAtLeast(0)
        val hiX = (maxOf(x0, x1) + w).toInt().coerceAtMost(ts - 1)
        val loY = (minOf(y0, y1) - w).toInt().coerceAtLeast(0)
        val hiY = (maxOf(y0, y1) + w).toInt().coerceAtMost(ts - 1)
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-6) return
        val invLen2 = 1.0 / len2
        var y = loY
        while (y <= hiY) {
            var x = loX
            while (x <= hiX) {
                val px = x + 0.5 - x0
                val py = y + 0.5 - y0
                var s = (px * dx + py * dy) * invLen2
                if (s < 0.0) s = 0.0 else if (s > 1.0) s = 1.0
                val qx = px - dx * s
                val qy = py - dy * s
                val d = sqrt(qx * qx + qy * qy) / w
                if (d < 1.0) {
                    val a = peak * (1.0 - s * 0.55) * (1.0 - d * d)
                    val i = y * ts + x
                    if (a > acc[i]) acc[i] = a.toFloat()
                }
                x++
            }
            y++
        }
    }

    /**
     * 水体场**底色竖向渐变**（覆盖 `0..seaBottomPx`）。LOW 档**只**画它。
     * ⛔ **每个画幅只建一次**（`Brush.verticalGradient(vararg)` 分配 vararg 数组）。
     *
     * 色标与位置取自 §4.2「底色」行 + 原型 `drawSeaField:1619-1622` 的深度公式：
     * ```
     * dep(v) = clamp(1 − 0.78·v^0.90·(0.72 + depthBias·0.55), 0, 1)
     * color(v) = lerp(#2E9AA8, #14586B, dep(v))       // §5.6 远海 ↔ 深海
     * ```
     * ⚠️ **方向**：原型注释明写「画面顶部（外海）更深、更饱和；越靠近浪线越浅、越透」
     *   并承认这与 §5.6 的命名相反 ⇒ 渐变的 **y = 0 是 [PAL_SEA_DEEP]**、末端是
     *   [PAL_SEA_FAR]。⛔ 不要按 §5.6 的名字从上往下排。
     * ⚠️ `depthBias` 取 [SeasideAudioMap.DEPTH_BIAS_LO]（= `energy = 0` 那一档）
     *   —— 这是**烘焙期**的固定渐变，逐帧的能量响应由 [seaDepthBias] 走水体像素
     *   （§4.6「水色」行），⛔ 不在这里变。
     *
     * 5 个色标按 `dep` 在 `v = 0 / 0.25 / 0.5 / 0.75 / 1` 上取值，用来逼近 `v^0.90`
     * 那条轻微上凸的曲线（⛔ 均匀三色标会把近浪线那一段压得过深）。
     */
    private fun buildSeaBaseBrush(w: Float, h: Float) {
        if (seaBottomPx <= 0f) return
        val depthK = SEA_DEPTH_GAIN * (SEA_DEPTH_BIAS0 + SeasideAudioMap.DEPTH_BIAS_LO * 0.55)
        // ⛔⛔⛔ **必须走 `Color(Int)` / [AndroidColor.argb]，⛔ 不得用 `Color(Float×4)`。**
        //   `androidx.compose.ui.graphics.Color(red, green, blue, alpha)` 的四个分量是
        //   **`0.0..1.0`**；本行算出来的 `r/g/b` 是 `0..255`。传 20f/88f/107f 进去会把
        //   整条水体底色渐变打成近黑（真机实测：整片海 `#0F080A`，G 通道中位数 15，
        //   而正确值应是 105~128）。⇒ 一律 `AndroidColor.argb(255, r, g, b)`。
        //   （本文件其余 17 处色值全部走 `Color(0x…)` 整型重载，⛔ 别在这里破例。）
        val stops = Array<Pair<Float, Color>>(5) { s ->
            val v = s.toDouble() / 4.0
            val dep = SeasideWaves.clamp(1.0 - depthK * powD(v, SEA_DEPTH_POW), 0.0, 1.0)
            val r = SEA_FAR_R + (SEA_DEEP_R - SEA_FAR_R) * dep
            val g = SEA_FAR_G + (SEA_DEEP_G - SEA_FAR_G) * dep
            val b = SEA_FAR_B + (SEA_DEEP_B - SEA_FAR_B) * dep
            (s.toFloat() / 4.0f) to Color(
                AndroidColor.argb(255, clampByte(r), clampByte(g), clampByte(b))
            )
        }
        seaBaseBrush = Brush.verticalGradient(
            *stops,
            startY = 0f,
            endY = seaBottomPx,
            tileMode = TileMode.Clamp
        )
    }

    /**
     * ⭐ 烘**两支**竖向渐变：[wetBrush]（湿沙 + 高光的 `lighter` 预合成，给 [drawWetWash]）与
     * [sheenBrush]（**只含高光 ramp**，给 [drawSheen] 的 `BlendMode.Plus` 那一次）。
     * ⛔ **每个画幅只各建一次**；⛔ 逐帧路径**不得**再出现 `Brush.verticalGradient`
     * 或 `createLinearGradient`（[SeasideTest] 专条断言）。
     *
     * ⚠️ 「共用」的**含义变了**（所有者裁决，推翻 §4.9.2 的「折成同一次提交」）：
     *   [drawWetWash] 用 [wetBrush] `SrcOver` 提交一次、[drawSheen] 用 [sheenBrush] `Plus`
     *   提交第二次。⛔ **不是**同一次提交（NonZero 下高光子轮廓会并入湿区 ⇒ 完全不可见）。
     *   ⛔ 也**不是同一支刷**（合并刷会把湿沙 ramp 二次叠加 ⇒ 引入视觉偏差，见 [sheenBrush]）。
     *
     * ## 为什么一个刷要装两条 ramp
     * 原型是**两条**渐变（`buildWetStrip`，`seaside-preview.html:1988-2021`）：
     * - 湿沙：`rgba(74,50,26,0.76) → rgba(82,57,30,0.60) → rgba(90,65,36,0.33)
     *   → rgba(98,75,44,0.11) → rgba(106,84,54,0)`（5 个色标）
     * - 高光：`rgba(232,240,236,SHEEN_A=0.19) → rgba(170,206,210,0.19·0.42)
     *   → rgba(140,180,190,0)`（3 个色标，`SHEEN_A` 取 §5.4 的 `0.19`）
     *
     * §4.9.2 把它们折成**一条 path + 一次提交**，两条渐变就没法同时存在了
     * ⇒ 这里把两者在**共同的位置栅格**（`0 / 0.26 / 0.38 / 0.55 / 0.80 / 1.0`）上
     * 逐点采样，再按 `lighter` 的合成结果预先合成为**一支**刷：
     * ```
     * outA = a_s + a_h·(1 − a_s)
     * outRGB = (rgb_s·a_s + rgb_h·a_h·(1 − a_s)) / outA
     * ```
     * （高光是加色 ⇒ 源覆盖式叠加，等价于把两层预乘后相加再反预乘。）
     *
     * ## 「终点落在 `wetEdge[i]`」怎么落进一支共享渐变
     * ⛔ 逐列渐变已被 §4.9.2 取消，而 `wetEdge[i]` 是**逐列**的记忆量 ⇒ 一支刷不可能
     * 逐列对齐。可用的**稳定包围区间**是水线的两个极值：
     * `startY = h·(SHORE_K − CREST_AMP_SHORE − TIDE_AMP)`（= [SeasideWaves.waterline_min_bound]，
     * 水线的最高位置）与 `endY = h·(SHORE_K + SWASH_REACH·1.10)`（= 水线的最高上冲，
     * [SeasideOpBudget.SEA_WET_BAND] 就是那个 `1.10`）。湿区永远落在这两者之间 ⇒
     * 渐变在湿区内的相对位置与原型逐列渐变一致；真正「落到 wetEdge」的那一收
     * 由 [drawWetWash] 把 `wetEdge[i]` 写成 path 的**下轮廓**完成（路径本身即是边界）。
     */
    private fun buildWetBrush(w: Float, h: Float) {
        // 位置栅格 = 两条 ramp 的色标并集（升序）。
        val pos = floatArrayOf(0.00f, 0.26f, 0.38f, 0.55f, 0.80f, 1.00f)
        // 湿沙 ramp（RGB + alpha 分开存；⛔ `const val` 不能带 `shr`）。
        val sandR = intArrayOf(0x4A321A, 0x52391E, 0x5A4124, 0x624B2C, 0x6A5436)
        val sandA = floatArrayOf(0.76f, 0.60f, 0.33f, 0.11f, 0.00f)
        // 高光 ramp（`SHEEN_A` = §5.4 的 `0.19`；第二档 = `SHEEN_A · 0.42`）。
        val sheenR = intArrayOf(0xE8F0EC, 0xAACED2, 0x8CB4BE)
        val sheenA = floatArrayOf(SHEEN_A, SHEEN_A * 0.42f, 0.00f)
        val sandPos = floatArrayOf(0.00f, 0.26f, 0.55f, 0.80f, 1.00f)
        val sheenPos = floatArrayOf(0.00f, 0.38f, 1.00f)

        // ⛔⛔ 2026-10-05：这里原来还烘 [WET_RIBBON_N] = 32 条**平色** ribbon 刷子供
        //   `drawWetWash` 用（那才是吃掉 ~80% 帧预算的东西：32 次 `drawPath`）。
        //   ⇒ 已**删除**：湿沙本体改走 [wetWashNativePaint] 的**单支**原生渐变
        //     （色标逐字照抄原型 `seaside-preview.html:2055-2059`，见 [WET_WASH_STOP_POS]）。
        //   ⇒ 仍需要 alpha 随 `wetAmt` 调制，所以 `sandA` / `sandR` 这两个色标表**保留**
        //     —— [wetBrush]（下面那支）与 [sheenBrush] 的 `lighter` 预合成还在用它们。

        val startY = SeasideWaves.waterline_min_bound(h.toDouble()).toFloat()
        val endY = (h.toDouble() * (SeasideWaves.SHORE_K + SEA_WET_BAND)).toFloat()
        if (endY <= startY) return

        val stops = Array(pos.size) { i ->
            val p = pos[i].toDouble()
            // ① 湿沙
            val sr = sampleRamp(sandPos, sandR, p)
            val sa = sampleRampF(sandPos, sandA, p)
            // ② 高光
            val hr = sampleRamp(sheenPos, sheenR, p)
            val ha = sampleRampF(sheenPos, sheenA, p)
            // ③ `lighter` 合成（高光在后）
            val outA = sa + ha * (1.0 - sa)
            var r: Double
            var g: Double
            var b: Double
            if (outA <= 1e-6) {
                r = sr[0].toDouble()
                g = sr[1].toDouble()
                b = sr[2].toDouble()
            } else {
                val k = ha * (1.0 - sa)
                r = ((sr[0] * sa + hr[0] * k) / outA).toInt().toDouble()
                g = ((sr[1] * sa + hr[1] * k) / outA).toInt().toDouble()
                b = ((sr[2] * sa + hr[2] * k) / outA).toInt().toDouble()
            }
            pos[i] to Color(AndroidColor.argb(alpha255(outA), clampByte(r), clampByte(g), clampByte(b)))
        }
        wetBrush = Brush.verticalGradient(*stops, startY = startY, endY = endY, tileMode = TileMode.Clamp)

        // ── ⭐ [sheenBrush]：**只含高光 ramp** 的那一支（`drawSheen` 的 `Plus` 提交用）────
        // ⛔ ⛔ **不得**让 [drawSheen] 复用 [wetBrush]：原型那次 `lighter` 只加 `sheenStrip`
        //   （高光 ramp），拿合并刷会把湿沙 ramp 也叠加一遍 ⇒ 本次修正自己引入视觉偏差。
        //   逐字照抄 `seaside-preview.html:2013-2017` 的三个色标；采样栅格 [pos] 含
        //   `0.00 / 0.38 / 1.00` 三个**真实**色标 ⇒ 色标之间分段线性 ⇒ 与原型逐点等价。
        // ⚠️ span 与 [wetBrush] **完全相同**（见 [sheenBrush] 的 KDoc：残留偏差待裁决）。
        val sheenStops = Array(pos.size) { i ->
            val p = pos[i].toDouble()
            val hr = sampleRamp(sheenPos, sheenR, p)
            val ha = sampleRampF(sheenPos, sheenA, p)
            pos[i] to Color(AndroidColor.argb(alpha255(ha), hr[0], hr[1], hr[2]))
        }
        sheenBrush = Brush.verticalGradient(
            *sheenStops,
            startY = startY,
            endY = endY,
            tileMode = TileMode.Clamp
        )
    }

    /**
     * 在一条折线 ramp 上按位置 `p` 采样 RGB（⛔ 返回 `[Int, Int, Int]` 装箱数组，
     * **只在烘焙期调用 12 次**；逐帧路径不许碰）。
     */
    private fun sampleRamp(stops: FloatArray, rgb: IntArray, p: Double): IntArray {
        if (p <= stops[0].toDouble()) return intArrayOf(
            (rgb[0] shr 16) and 0xFF, (rgb[0] shr 8) and 0xFF, rgb[0] and 0xFF
        )
        var i = 1
        while (i < stops.size) {
            if (p <= stops[i].toDouble()) {
                val a = stops[i - 1].toDouble()
                val b = stops[i].toDouble()
                val u = if (b > a) (p - a) / (b - a) else 0.0
                return intArrayOf(
                    mixChannel((rgb[i - 1] shr 16) and 0xFF, (rgb[i] shr 16) and 0xFF, u),
                    mixChannel((rgb[i - 1] shr 8) and 0xFF, (rgb[i] shr 8) and 0xFF, u),
                    mixChannel(rgb[i - 1] and 0xFF, rgb[i] and 0xFF, u)
                )
            }
            i++
        }
        val last = rgb.size - 1
        return intArrayOf(
            (rgb[last] shr 16) and 0xFF, (rgb[last] shr 8) and 0xFF, rgb[last] and 0xFF
        )
    }

    /** 单通道插值（⛔ 只在烘焙期调用）。 */
    private fun mixChannel(a: Int, b: Int, u: Double): Int = (a + (b - a) * u).toInt()

    /** 单通道折线采样（[sampleRamp] 的 `Float` 版本，⛔ 只在烘焙期调用）。 */
    private fun sampleRampF(stops: FloatArray, vals: FloatArray, p: Double): Double {
        if (p <= stops[0].toDouble()) return vals[0].toDouble()
        var i = 1
        while (i < stops.size) {
            if (p <= stops[i].toDouble()) {
                val a = stops[i - 1].toDouble()
                val b = stops[i].toDouble()
                val u = if (b > a) (p - a) / (b - a) else 0.0
                return vals[i - 1] + (vals[i] - vals[i - 1]) * u
            }
            i++
        }
        return vals[vals.size - 1].toDouble()
    }

    /**
     * 焦散网的三档 `Stroke`（胞壁 3 档 alpha 批量 stroke ⇒ **3 次提交**）。
     * ⛔ 只在 [rebuildGeometry] 路径调用；逐帧只换 path 与 `alpha`。
     *
     * 取值（§4.2「按亮度分 3 档批量描边」+ 原型 `seaside-preview.html:1790-1791`）：
     * - 档 0 = `0.6` px / `0.28`；档 1 = `1.15` px / `0.62`；档 2 = `0.85` px / `0.44`
     *   ⇒ 表在 [CAUSTIC_TIER_W] / [CAUSTIC_TIER_ALPHA]，按**档号**索引
     *   （⛔ **不是**「HI/MID/LO」的字面顺序 —— 原型的档号 1 最亮最粗，见那两张表的 KDoc）。
     * - 整体强度 [CAUSTIC_A] = `0.075` 再乘 `0.30 + 0.70·energy`
     *   （[SeasideAudioMap.causticStrength]），由 [drawCausticNet] 逐帧乘进 `alpha`。
     *
     * ⛔ `seaDensity` 是唯一的 px 标定：原型的画布画在 **CSS 像素**上，而 Android 的
     * `DrawScope` 画在**设备像素**上 ⇒ 线宽要乘 `density` 才是同一个视觉粗细。
     */
    private fun buildCausticStrokes() {
        val px = if (seaDensity > 0f) seaDensity else 1f
        val n = if (causticPaths.size < CAUSTIC_PATH_BUCKETS) causticPaths.size else CAUSTIC_PATH_BUCKETS
        causticStrokes = Array(n) { b ->
            val wpx = CAUSTIC_TIER_W[b] * px
            Stroke(width = wpx, cap = StrokeCap.Round)
        }
    }

    /**
     * ⭐ 蕾丝 `Stroke` 阶梯 —— **4 类 × 3 档 = 12 支**（两个允许的 `Stroke(` 构造点之一）。
     *
     * 存在的唯一根据（§4.9.2）：蕾丝的 `lineWidth = (LACE_STROKE_W_MIN + LACE_STROKE_W_SPAN·wj)
     * · bandW · kindW` 逐边变化，而 `Stroke.width` **不可变** ⇒ 必须量化并**缓存**这些 `Stroke`。
     * 不量化而每边 `new` 一个就是每边一次 native 分配 —— 那正是「解释调用数」预算要拦的东西
     * （API 22 Dalvik 无 JIT，§4.9.1）。
     *
     * ⛔ 缓存规模恒取 [SeasideOpBudget.laceStrokeCacheSize]（= `4 × 3 = 12`）
     *   ⇒ 每帧批量 stroke 提交数 = [SeasideOpBudget.laceStrokeOps]（12 / 3 / 0）。
     * ⛔ **下标 = `kind · LACE_BAND_N + band`** —— 边的两个身份（[laceEdgeKind] 的类号与
     *   [laceEdgeQ] 的距离档号）直接相乘定位，⛔ 逐帧绝不重算线宽。
     * ⛔ `seaDensity` 是唯一的 px 标定（CSS 像素 vs 设备像素，同 [buildCausticStrokes]）。
     *
     * 逐（档 × 类）的取值来源与「为什么不再折成加权均值」见 [LACE_BAND_W] / [LACE_KIND_W]
     * 的 KDoc；⛔ `wj` 的逐边变化被并进固定代表值 [LACE_EDGE_WJ_MID]（三轴要 144 支，绝不可做）。
     */
    private fun buildLaceStrokes() {
        val n = SeasideOpBudget.laceStrokeCacheSize()
        val px = if (seaDensity > 0f) seaDensity else 1f
        val base = (LACE_STROKE_W_MIN + LACE_STROKE_W_SPAN * LACE_EDGE_WJ_MID).toFloat()
        laceStrokes = Array(n) { i ->
            val kind = i / LACE_BAND_N
            val band = i % LACE_BAND_N
            val wpx = base * LACE_BAND_W[band] * LACE_KIND_W[kind] * px
            Stroke(width = wpx, cap = StrokeCap.Round)
        }
    }

    /** 取量化后的蕾丝 `Stroke`（下标越界夹到端点）；未烘焙返回 `null`。 */
    private fun laceStrokeAt(kind: Int, band: Int): Stroke? {
        val arr = laceStrokes ?: return null
        if (arr.isEmpty()) return null
        var idx = kind * LACE_BAND_N + band
        if (idx < 0) idx = 0
        if (idx >= arr.size) idx = arr.size - 1
        return arr[idx]
    }

    // ═══════════════════════════ 每帧绘制 ═══════════════════════════

    /**
     * ⭐ 帧入口。**⛔ 帧内顺序不可换**（§14.3.7 的固定顺序 = 「从外海走向内陆」的水/沙剖面）。
     *
     * ⛔ 元素归属：**无独立 [SeaOpItem]** —— 它是编排入口，提交数分记在下面每个
     *   `draw*` 元素上（`postFx` 的那 1 次由基类记在 [SeaOpItem.POST_FX]）。
     *
     * ```
     * 尺寸守卫 → rebuildGeometry(w, h)
     *   → t0Ms 惰性捕获（只首帧）
     *   → ① audio.update(dt, frame)          音频：只产外观量
     *   → ② waves.step(dt, t, 涌高, 岸线带宽) 模拟：一次调用完成整帧（内部顺序另有约束）
     *   → ③ drawSeaField → drawCausticNet → drawSand → drawWetWash → drawSheen
     *      → drawPuddles → drawCrab → drawSwellBands → drawSwashFingers
     *      → drawResidualStreaks → drawSplash → drawRipples → drawResidue
     * ```
     *
     * ⚠️ **`drawSheen` 排在 `drawWetWash` 之后**（镜面高光靠 `BlendMode.Plus` **叠加**在湿沙
     *   之上，复现原型 `globalCompositeOperation = 'lighter'` 的提交次序）。⛔ 两者各写各的
     *   `Path`（[wetRegionPath] / [sheenPath]），谁也 `reset()` 不到对方 ⇒ 共享 path 那套
     *   「谁先 `reset()`」的约束已撤销，**剩下的只有绘制次序这一条**。
     *   其余元素严格按 §14.3.7 的「从外海走向内陆」顺序（⛔ 不可换）。
     *
     * - ⛔ **时间原点只能在首帧用 `fx.nowMs` 记**（[onEnterContent] 拿不到 `fx`）。
     * - ⛔ **绝不读 `ctx.nowMs`**（墙钟 vs 单调，时基不同源；`RendererBaseContractTest` 也判违规）。
     * - ⛔ 时间轴只吃 `fx.dt`；暂停时 `fx.dt = 0` ⇒ 队列 / 退水 / 干燥全部冻结。
     * - ⛔ 音频只改**外观**（涌高 / `bandAmp` / `layerAlpha` / 水色 / 粼光 / 飞沫 / 沙纹 / 残沫），
     *   ⛔ 不改任何「什么时候」—— 结构性保证见 `SeasideWaves.step` 的 KDoc（音频入参的赋值点
     *   排在 `stepWaves` 与 `waterlineAdvance` **之后**）。
     * - ⛔ **不**调 `ProceduralTexture.*`、⛔ **不**画 `postFx`（基类在 `drawContent` 之后施加）。
     */
    protected override fun DrawScope.drawContent(
        frame: AudioFrame,
        ctx: RenderContext,
        fx: FxFrame,
    ) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return
        val den = density
        // ⛔ 画质档必须**先于**尺寸守卫求值：`rebuildGeometry` 的烘焙层按档分配
        //   （§4.7②「LOW 只留底色渐变」⇒ LOW 不分配水体场 / 泡沫贴图）。若在守卫之后
        //   赋值，**首帧**永远按 LOW 烘 ⇒ HIGH 档此后也拿不到 fieldTex，
        //   `drawSeaField` 会在 `fieldTex ?: return` 处长期早退。
        seaLevel = seaLevelOf(fx.level)
        if (w != seaW || h != seaH || den != seaDensity) {
            // ⛔ `rebuildGeometry` 不在 DrawScope 作用域里拿不到 `density` ⇒ 先写字段。
            seaDensity = den
            releaseResources()
            rebuildGeometry(w, h)
        }
        val wv = waves ?: return
        if (!t0Set) {
            t0Ms = fx.nowMs
            t0Set = true
        }
        val t = (fx.nowMs - t0Ms).toDouble()
        // §4.7① `prefers-reduced-motion` ⇒ 低档整体放慢（原型无验证，端口自有）。
        val dt = fx.dt.toDouble() * (if (seaLevel == SeaLevel.LOW) TIME_SCALE_LOW else 1.0)
        refreshLevelOps()

        // ① 音频（零缓冲零分配；⛔ 不碰时间轴）
        audio.update(dt, frame)
        // ② 模拟（一次调用完成整帧；帧内顺序在 SeasideWaves.step 内部不可换）
        wv.step(dt, t, audio.swashReachNow(), audio.shoreWaveBand(wv.h))

        // ③ 绘制
        drawSeaField(t, audio.sEnergy)
        drawCausticNet(t, audio.sEnergy)
        drawSand()
        drawWetWash()
        drawSheen()
        drawPuddles()
        drawCrab(t, dt)
        drawSwellBands(t)
        drawSwashFingers(t)
        drawResidualStreaks(t)
        drawSplash(t)
        drawRipples(t)
        drawResidue()
    }

    /**
     * 把 [SeasideOpBudget.opsOf] 按本帧 [seaLevel] 逐元素算进 [levelOps]（**预分配数组**）。
     *
     * ⛔ 为什么绘制侧要读预算表：G11 是**纯函数门**，渲染层与门禁各算一份必然漂
     * （改了一处忘另一处，正是这个项目反复出现的失败模式）。这里只读同一张表。
     */
    private fun refreshLevelOps() {
        val items = SeaOpItem.entries
        var i = 0
        while (i < items.size) {
            levelOps[items[i].ordinal] = SeasideOpBudget.opsOf(items[i], seaLevel, waveCount = 1)
            i++
        }
    }

    /** 本帧某元素的提交数（波次数 = 1；逐浪元素在 [drawSwellBands] 内自行乘）。 */
    private fun opsOf(item: SeaOpItem): Int = levelOps[item.ordinal]

    // ── 合批原语（⛔ 这两个是唯一的「一次提交 = 一次 native 调用」入口）───────────

    /**
     * ⭐ 飞沫 / 残沫的合批原语 —— **1 次 `drawPoints`**（§4.9.2）。
     *
     * ⛔ 元素归属：**无独立 [SeaOpItem]** —— 它是合批**原语**，那 1 次提交已分别记在
     *   [SeaOpItem.SPLASH_POINTS] 与 [SeaOpItem.RESIDUE_POINTS] 上（后者记 3 次）。
     *
     * ⛔ 走 `nativeCanvas.drawPoints(FloatArray, offset, count, 缓存Paint)`：
     * 逐点 `drawCircle` 是 180~156 次提交（改造前的头号超标项），而 `drawPoints` 的
     * **像素面积只与笔宽有关、与点数无关** ⇒ 提交数降 2 个量级、填充几乎不变。
     * ⛔ **不**烘成位图 —— 那会丢掉逐点 alpha（§4.9.2 明写「保留逐点 alpha 且零分配」）。
     *
     * @param pts 预分配坐标缓冲（`x, y` 交替）。
     * @param offset 起始 float 下标（残沫三笔共用一个缓冲 ⇒ 用它切批，零拷贝）。
     * @param count 有效 float 个数（偶数）。
     * @param paint 缓存画笔（⛔ 逐帧只改属性）。
     */
    private fun DrawScope.drawPointsBatch(
        pts: FloatArray,
        offset: Int,
        count: Int,
        paint: Paint,
    ) {
        if (count < 2) return
        if (offset < 0 || offset + count > pts.size) return
        drawContext.canvas.nativeCanvas.drawPoints(pts, offset, count, paint)
    }

    /**
     * ⭐ 沙纹 / 浪花手指的合批原语 —— **1 次 `drawLines`**（§4.9.2）。
     *
     * ⛔ 元素归属：**无独立 [SeaOpItem]** —— 它是合批**原语**，那些提交已分别记在
     *   [SeaOpItem.SAND_GRAIN] / [SeaOpItem.SWASH_FINGER] 上。
     *   ⛔ [SeaOpItem.CRAB] 已**不再**用它（螃蟹回到 `NativePath` 的 `quadraticCurveTo`。
     *   `drawLines` 只有直线，⛔ 表达不了原型的髋→膝二次曲线）。
     *
     * ⛔ 同 [drawPointsBatch]：一次 `drawLines` 的填充只与笔宽有关，与段数无关。
     *
     * @param pts 预分配坐标缓冲（每段 4 个 float：`x0, y0, x1, y1`）。
     * @param offset 起始 float 下标（多批共用一个缓冲时用它切批）。
     * @param count 有效 float 个数（4 的倍数）。
     * @param paint 缓存画笔。
     */
    private fun DrawScope.drawLinesBatch(
        pts: FloatArray,
        offset: Int,
        count: Int,
        paint: Paint,
    ) {
        if (count < 4) return
        if (offset < 0 || offset + count > pts.size) return
        drawContext.canvas.nativeCanvas.drawLines(pts, offset, count, paint)
    }

    // ── ① 水体场 ────────────────────────────────────────────────────────────

    /**
     * [SeaOpItem.SEA_FIELD] —— 水体场。
     *
     * 提交数：**LOW 1 次 / MED·HIGH 2 次**（底色竖向渐变 + 低分辨率场 blit，
     * §4.7② LOW「只留底色渐变」省掉那次全屏 blit）。
     *
     * @param t 相对首帧的毫秒（由 `fx.nowMs` 派生）。
     * @param energy 段落响度（`audio.sEnergy`）—— ⛔ 只改外观（水色加深 / 浅滩带宽度）。
     */
    private fun DrawScope.drawSeaField(t: Double, energy: Double) {
        if (BISECT_SEAFIELD_OFF) return
        val brush = seaBaseBrush ?: return
        drawRect(brush = brush, size = Size(seaW, seaBottomPx))
        if (seaLevel == SeaLevel.LOW) return
        val tex = fieldTex ?: return
        // ⛔ 低分辨率场必须**放大** blit（§4.2「渲染形态」：每帧一次「裁剪 + blit」到
        //   `H·SEA_BOTTOM_K`）。1:1 的 `drawImage(image, topLeft)` 只会在左上角画出一小块
        //   ≈320×148 的场、其余全是底色渐变 —— 那是「读成一块塑料色」的直接成因。
        drawImage(
            image = tex,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(fieldW, fieldH),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(seaW.toInt(), seaBottomPx.toInt())
        )
        // §4.6「水色」行：`shadeBoost = 1 + 0.28·e`、`depthBias = 0.10 + 0.55·ENERGY_TO_SHADE·e`
        //   （`ENERGY_TO_SHADE = 0.45`）、浅滩带宽度 `0.30 + 0.22·e`。
        // ⛔ 只**发布**到预分配字段（见那三个字段的 KDoc）：底色渐变与位图都是烘焙产物，
        //   逐帧改它们只能靠新建 `Brush`（违反 [SeasideTest] ⑤）或加一次全屏提交
        //   （撑破钉死的 LOW 29 / MED 44 / HIGH 81）。这三个量由水体像素生产者消费。
        seaShadeBoost = SeasideAudioMap.shadeBoost(energy)
        seaDepthBias = SeasideAudioMap.depthBias(energy)
        seaShoalWidth = SeasideAudioMap.shoalWidth(energy)
    }

    // ── ② 粼光网 ────────────────────────────────────────────────────────────

    /**
     * [SeaOpItem.CAUSTIC] —— 粼光（焦散）网。**3 条 `Path`，每帧重建，恒 3 次提交**。
     *
     * - ⛔ **D9 裁决**：**胞壁网 MEDIUM + HIGH 都画**（[SeaOpItem.CAUSTIC] 的 `opsMed = 3`）；
     *   **射线与亮结仅 HIGH**（§4.2 / §5.5 的原意）。
     * - ⛔ **30 射线 + 56 亮结全部折进同 3 条 path 的线段** ⇒ 不是 86 次提交，
     *   ⛔ 也不是 56 次 `drawCircle`。折进去的代价：射线逐根的线宽 `0.8 + 1.0·hash`
     *   与逐根 alpha、亮结的逐颗 alpha 都被**该档的固定值**取代（档分配见下）。
     * - ⛔ **只在海水区**（`0..seaBottomPx`）⇒ ⛔ 整屏叠（[CAUSTIC_SHORE_A] 是死常量，
     *   岸线处的亮线由贴岸各层负责（⛔ 原 `drawWetLine` 已于 2026-10-05 整层删除，§5.5 清单）。
     *   ⛔ 全文件零 `clipPath` ⇒ 越界的射线端点**逐点夹到 `[0, seaBottomPx]`**。
     * - ⛔ **绝不可退回「孤立短横划」** —— 没有胞形的短线一律读作划痕（§4.2「为什么必须是胞壁网」）。
     *
     * ## 三档的装载（档号来自 [bakeCausticEdge] 的 `hash2` 三等分）
     * | 档 | 装什么 | alpha | 线宽 |
     * |---|---|---|---|
     * | 0 | 最暗的一批胞壁 | `CAUSTIC_ALPHA_LO` | `CAUSTIC_STROKE_W_LO` |
     * | 1 | 最亮的一批胞壁 **+ 56 颗亮结** | `CAUSTIC_ALPHA_HI` | `CAUSTIC_STROKE_W_HI` |
     * | 2 | 中档胞壁 **+ 30 根射线** | `CAUSTIC_ALPHA_MID` | `CAUSTIC_STROKE_W_MID` |
     *
     * 三档在两个档位下都**非空**（331 条壁按 hash 三等分 ⇒ 每档 ≈110 条；射线与亮结
     * 只在高）⇒ 提交数恒等于预算表的 3，⛔ 不出现「空 path 白提交」。
     *
     * @param t 相对首帧的毫秒。
     * @param energy 段落响度（`audio.sEnergy`）—— ⛔ 只改强度乘子（[SeasideAudioMap.causticStrength]）。
     */
    private fun DrawScope.drawCausticNet(t: Double, energy: Double) {
        if (opsOf(SeaOpItem.CAUSTIC) <= 0) return
        val strokes = causticStrokes ?: return
        val brush = causticBrush ?: return
        var b = 0
        while (b < CAUSTIC_PATH_BUCKETS) {
            causticPaths[b].reset()
            b++
        }
        val seaH = seaBottomPx
        val w = seaW

        // ── ① 连通胞壁网（331 条）：端点共享 ⇒ 网永不散架；每条壁一条二次曲线 ──
        val cellW = w / CAUSTIC_CELL_NX.toFloat()
        val edgeN = causticEdgeA.size
        var e = 0
        while (e < edgeN) {
            val lv = causticEdgeLevel[e]
            if (lv < 0 || lv >= CAUSTIC_PATH_BUCKETS) {
                e++
                continue
            }
            val na = causticEdgeA[e]
            val nb = causticEdgeB[e]
            val ax = causticNodeX[na]
            val ay = causticNodeY[na]
            val bx = causticNodeX[nb]
            val by = causticNodeY[nb]
            val mx = (ax + bx) * 0.5f
            val my = (ay + by) * 0.5f
            // 弓起方向 = **垂直于壁**：n = (−(by−ay), bx−ax)
            var nx = -(by - ay)
            var ny = bx - ax
            val nl = sqrt(nx * nx + ny * ny)
            if (nl > 1e-3f) {
                // 静偏 + 随时间缓慢摆动 ⇒ 壁是弯的且会呼吸（⛔ 绝不出现笔直硬线）
                val wob = (causticEdgeBow[e] *
                    (0.55f + 0.45f * sin(t * 0.00007 + causticEdgePhase[e]).toFloat()) *
                    cellW * CAUSTIC_BOW_K).toFloat()
                nx /= nl
                ny /= nl
                causticPaths[lv].moveTo(ax, ay)
                causticPaths[lv].quadraticBezierTo(mx + nx * wob, my + ny * wob, bx, by)
            }
            e++
        }

        // ── ② 射线与 ③ 亮结：⛔ **仅 HIGH**（D9 裁决）────────────────────────────
        if (seaLevel == SeaLevel.HIGH) {
            val kScale = if (w > 1f) CAUSTIC_SLOPE_REF_W / w else 1f
            // ② 两族交叉斜向射线（↗ / ↘），每根漂移 + 摆动 ⇒ 读作「交叉扇」而非斜横线
            var i = 0
            while (i < CAUSTIC_RAYS) {
                val dirm = if (i and 1 != 0) 1.0f else -1.0f
                val y0 = seaH * (0.10 + 0.80 * SeasideWaves.hash2(i, 41)).toFloat()
                val steep = (i % CAUSTIC_RAY_STEEP_EVERY) == 0
                val sl = if (steep) {
                    (CAUSTIC_RAY_STEEP_LO + CAUSTIC_RAY_STEEP_SPAN * SeasideWaves.hash2(i, 42)).toFloat()
                } else {
                    (CAUSTIC_RAY_SLOPE_LO + CAUSTIC_RAY_SLOPE_SPAN * SeasideWaves.hash2(i, 43)).toFloat()
                } * kScale
                val xOff = w * (0.2 + 0.6 * SeasideWaves.hash2(i, 45)).toFloat()
                val span = w * (0.35 + 0.55 * SeasideWaves.hash2(i, 47)).toFloat()
                val drift = ((SeasideWaves.hash2(i, 49) - 0.5) * 46.0 *
                    sin(t * 0.00006 + SeasideWaves.hash2(i, 51) * LACE_EDGE_PH_K)).toFloat()
                val sway = ((SeasideWaves.hash2(i, 53) - 0.5) * 14.0 *
                    sin(t * 0.00010 + SeasideWaves.hash2(i, 55) * LACE_EDGE_PH_K)).toFloat()
                val x0 = xOff - span * 0.5f
                val dashes = CAUSTIC_RAY_DASH_LO +
                    (SeasideWaves.hash2(i, 57) * CAUSTIC_RAY_DASH_SPAN).toInt()
                val p = causticPaths[CAUSTIC_TIER_RAY]
                var d = 0
                while (d < dashes) {
                    val a0 = (d + SeasideWaves.hash2(i, 130 + d) * 0.4) / dashes.toDouble()
                    var a1 = a0 + (0.16 + 0.30 * SeasideWaves.hash2(i, 150 + d)) / dashes.toDouble()
                    if (a1 > 1.0) a1 = 1.0
                    val am = (a0 + a1) * 0.5
                    val sx = x0 + (a0 * span).toFloat()
                    val ex = x0 + (a1 * span).toFloat()
                    val m0 = dirm * sl * (sx - xOff)
                    val m1 = dirm * sl * (ex - xOff)
                    // 每划一次正弦（中点采样）⇒ 整划上下浮沉，端点 sin 数量减半
                    val sh = sway * sin(am * 3.1416).toFloat()
                    var yS = y0 + m0 + sh + drift * a0.toFloat()
                    var yE = y0 + m1 + sh + drift * a1.toFloat()
                    // ⛔ 无 clipPath ⇒ 逐点夹进海水区（⛔ 不整屏叠）
                    if (yS < 0f) yS = 0f else if (yS > seaH) yS = seaH
                    if (yE < 0f) yE = 0f else if (yE > seaH) yE = seaH
                    p.moveTo(sx, yS)
                    p.lineTo(ex, yE)
                    d++
                }
                i++
            }
            // ③ 闪烁亮结：格壁交点的小亮点，随能量与各自相位明灭
            //    ⛔ 原型是 `fillRect(size, size)`，这里折成**同长度的横划**（圆头笔帽 ⇒ 读作点）
            var k = 0
            while (k < CAUSTIC_KNOTS) {
                val id = 1300 + k
                val flick = 0.5 + 0.5 * sin(
                    t * (0.00015 + 0.00010 * SeasideWaves.hash2(id, 79)) +
                        SeasideWaves.hash2(id, 81) * LACE_EDGE_PH_K
                )
                if (flick >= CAUSTIC_KNOT_FLICK_MIN) {
                    val xk = w * SeasideWaves.hash2(id, 75).toFloat()
                    var yk = seaH * (0.10 + 0.82 * SeasideWaves.hash2(id, 77)).toFloat()
                    if (yk < 0f) yk = 0f else if (yk > seaH) yk = seaH
                    val sz = (CAUSTIC_KNOT_SIZE_LO +
                        CAUSTIC_KNOT_SIZE_SPAN * SeasideWaves.hash2(id, 83)).toFloat()
                    val p = causticPaths[CAUSTIC_TIER_KNOT]
                    p.moveTo(xk, yk)
                    p.lineTo(xk + sz, yk)
                }
                k++
            }
        }

        // ── 提交：3 档 = 3 次 stroke（⛔ 与 [SeaOpItem.CAUSTIC] 的 opsMed/opsHigh 同为 3）──
        val aBase = CAUSTIC_A.toDouble() * SeasideAudioMap.causticStrength(energy)
        b = 0
        while (b < CAUSTIC_PATH_BUCKETS) {
            val a = (aBase * CAUSTIC_TIER_ALPHA[b].toDouble()).toFloat()
            if (a <= 0f) {
                b++
                continue
            }
            drawPath(causticPaths[b], brush, alpha = a, style = strokes[b])
            b++
        }
    }

    // ── ③ 干沙 ──────────────────────────────────────────────────────────────

    /**
     * [SeaOpItem.SAND_BLIT] —— 干沙主体。**恒 1 次提交**。
     *
     * ## ⭐ 上边界为什么是**路径**而不是矩形
     * 原型（`seaside-preview.html:1957-1968`）是「把岸线平滑曲线做成 `sandPath` → `clip` →
     * blit 纹理」⇒ **可见的沙只从岸线往下长**。⛔ 本项目零 `clipPath`（§4.9.3，Android 5.1
     * 真机三次复现 hwui SIGSEGV）⇒ 改**同构**的形态：**上边界 = 岸线的路径 + 纹理当填充刷**
     * （[sandPaint] 的 `BitmapShader`，烘焙期挂好）。
     *
     * ⚠️ 上一轮的骨架 bug（报备 #6）正是这里：`drawImage(texture, topLeft)` 无 `dstSize`
     *   ⇒ ① 4K 上纹理被 [SeasideOpBudget.SAND_TEX_MAX_PX] 降采样后**只画在左上角**，
     *   右侧 / 下缘露缝；② 矩形 blit 的上沿是一条 `SAND_TEX_TOP·h` 的**水平硬边**，
     *   而岸线在 `0.484h…0.665h` 之间起伏 ⇒ 「沙滩颜色与底色的交界」那条投诉会复现。
     * 路径 + 着色器矩阵**同时**解掉这两条：矩阵按 `sandTexW/w`、`sandTexH/(h−SAND_TEX_TOP·h)`
     * 缩放（等价于 `dstSize`，且天然双线性），上边界则逐列贴着 `shoreYs[]`。
     *
     * ## 尺寸契约（⛔ 不许动）
     * 纹理高度恒为 `h − SAND_TEX_TOP·h`（[SeasideWaves.SAND_TEX_TOP] = `0.46`）——
     * 它必须高于水线的最大摆动 `0.484h`，否则退水时 `0.484h…0.62h` 露出海水底色（§9 红线 7）。
     * 着色器矩阵的纵向锚点也用它：`y = SAND_TEX_TOP·h ↔ 纹理 v = 0`。
     *
     * ## ✅ 已裁决解禁（2026-10-04）：`BitmapShader` **单点例外**
     * 所有者裁定 [drawSand] 可以用 [BitmapShader]（其余位置仍全禁，并由 `SeasideTest` ⑪
     * 的源码门 + 负向自证守住）。理由（照录裁决）：
     * - 两条禁令的**证据强度不对等**：`clipPath` 的禁令有**真机三次复现**的 hwui SIGSEGV
     *   （`Region::createTJunctionFreeRegion`，且波浪岸线正是非矩形区域、走同一条路径）
     *   ⇒ 不可动；`BitmapShader` 的禁令是**项目约定、无 stated mechanism**
     *   （其由来是 E42 的纹理统一走 `ProceduralTexture` 池以便集中生命周期），
     *   而本函数的位图**本来就在自己手里并已手动 `recycle()`** ⇒ 套 shader **不增加任何
     *   生命周期负担**。
     * - path 自身的**抗锯齿边缘 = 解析覆盖率**，恰与原型 `clip(sandPath)` 的 canvas2d
     *   抗锯齿 clip 边界一致 ⇒ 这条路上**没有**新的边缘模型偏差。
     * - 「把波浪状上沿烘进纹理自身的 alpha」这个替代方案的前提**不成立**：`shore_ys[]`
     *   由 [SeasideWaves.step] **每帧**重算，三项全含 `tMs`（① `swash_front_y` 的潮汐与
     *   浪脊漂移、② `fill_fray` 的三八度破碎场、③ `h·swashReachNow·adv`）⇒ 不是 resize 内
     *   恒定量；即便恒定，alpha 烘焙给的是**线性 ramp**，⛔ 与原型的**解析覆盖率**是两种
     *   不同的边缘模型（不是近似误差）。
     *
     * ⚠️ **由此得出一条结构性事实，未来加禁令前必须知道**：⛔ 零 `clipPath`（§4.9.3）**且**
     *   ⛔ 零 `BitmapShader` **同时**成立时，「上沿贴岸线的**纹理** blit」**无法实现** ——
     *   Compose 的 `drawImage` 只收矩形，把位图填进任意轮廓**只有** shader 一条路。
     *
     * ## ⭐⭐⭐ 竖纹的**确切成因**与修法（2026-10-05，真机指纹定位）
     *
     * **症状**（真机 `dev_80_v1.png` 1920×1080，沙滩 `y ∈ [780,1020]`）：
     * x 方向标准差 17.9、y 方向只有 4.6、相邻行相关 **0.978**、去趋势后行内逐点值
     * （`x=600..613`）在 `y=803` 以下**逐行完全相同** ⇒ **整块沙只剩 x 方向的变化**。
     *
     * **指纹判据（可复算，1:1 移植 [SeasideWaves] 的 `hash32`/`hash2` 到脚本）**：
     * 把屏幕行 `y=814/900/926/1000/1039/1055` 的 x 剖面与**纹理每一行**的层④
     * `hash2(x, j)` 求相关 ⇒
     * - 与 **`j = th−1 = 582`（最后一行）相关 +0.542…+0.563**，且**六行都是同一个 `j`**；
     * - 与其余 582 行 **|r| ≤ 0.10**（纯白噪声的量级）；与**原型的加法 hash** 只有 +0.09。
     *
     * ⇒ **机制**：`th = 583` 行，而沙滩在画布上占 `y ∈ [0.484h, h] = [523, 1080]`。
     * `BitmapShader.setLocalMatrix()` 承载的 `y' = sy·(y − sand_tex_top_px)` 在真机上
     * **不生效** ⇒ 采样退化成**恒等** `y' = y` ⇒ 整片沙滩的 `y' ≥ th` ⇒ `TileMode.CLAMP`
     * 把它**钉在最后一行** ⇒ 竖纹。⛔ 这**不是**纹理数据的问题（四层公式 1:1 复算的
     * 相邻行相关是 −0.004，各向同性），也**不是**上传/采样/`TileMode` 的问题。
     * 同一条退化**顺带吃掉层①的湿→干纵向渐变与层②的 7 条起伏带** ——
     * 这正是真机上「沙滩越往下越**暗**」（实测 `R−G ≈ 18` 全程不变、`y=760→1040`
     * 由 181.9 掉到 175.3，全是暗角）而原型是越往下越**亮**的原因。
     *
     * **修法**：⛔ 不再依赖 `setLocalMatrix`，把同一组映射搬进 **`Canvas` 变换** ——
     * `translate(0, sandTexTopPx)` + `scale(sandScaleX, sandScaleY)`，路径的 y 全部
     * 减去 `sandTexTopPx`。于是着色器在**恒等**坐标下就采到
     * `(x·sx, (y − top)·sy)` = **与原型 `drawImage(sandTex, 0, top, W, H−top)` 等价**
     * 的映射；`CLAMP` 不再被踩到（上沿 `y'=0`、下沿 `y'=th`）。
     * 代价：`translate` + `scale` + `restore` 三次 native 调用、⛔ 零分配、**不新增提交**。
     *
     * ⛔ **无入参**（原型签名带 `t`，本实现不吃：沙几乎不动，全部内容都在纹理里）。
     */
    private fun DrawScope.drawSand() {
        if (BISECT_SAND_OFF) return
        if (sandPaint.shader == null) return
        val wv = waves ?: return
        val n = wv.column_count
        if (n < 2) return
        val topPx = sandTexTopPx
        sandNativePath.rewind()
        // ⛔ 上边界逐列取岸线：⛔ 不取整、⛔ 不加 `+1px`（相邻列共享像素会抗锯齿成竖缝）
        // ⭐ y 全部减去 `topPx`：与下面的 `translate(0, topPx)` 配对 ⇒ 纹理第 0 行正好落在
        //   `SAND_TEX_TOP·h`（⛔ 换算交给 Canvas，⛔ 不靠 `setLocalMatrix`）。
        sandNativePath.moveTo(wv.shore_xs[0].toFloat(), wv.shore_ys[0].toFloat() - topPx)
        var i = 1
        while (i < n) {
            sandNativePath.lineTo(wv.shore_xs[i].toFloat(), wv.shore_ys[i].toFloat() - topPx)
            i++
        }
        // 向下合拢到画面底边外侧 4px（原型 `lineTo(W, H+4) / lineTo(-2, H+4)`）
        val hpx = size.height
        val yBottom = hpx + 4f - topPx
        sandNativePath.lineTo(seaW + 2f, yBottom)
        sandNativePath.lineTo(-2f, yBottom)
        sandNativePath.close()
        // ⭐⭐ 映射搬到这里：`translate` 定纵向锚点、`scale` 定「一画幅像素 ↔ 几个纹理像素」
        //   （两者都只在 4K 触发降采样时才 ≠ 1/1；1080p 下 `sx=1.0`、`sy=583/583.2`）。
        //   ⛔ `scale` 之后 path 的 x 才是画布像素（`sx` 极接近 1，⛔ 不引入可见缝）。
        val cv = drawContext.canvas.nativeCanvas
        cv.save()
        cv.translate(0f, topPx)
        cv.scale(sandScaleX, sandScaleY)
        cv.drawPath(sandNativePath, sandPaint)
        cv.restore()
    }

    // ── ④ 湿沙 + 镜面高光（**各自 1 次提交，合计 2 次 `drawPath`**）──────────────

    /**
     * [SeaOpItem.WET_WASH] —— 湿沙。**恰好 1 次 `drawPath`**（⛔ 只写 [wetRegionPath]）。
     *
     * ## ⭐⭐ 2026-10-05 重写：32 条平色 ribbon → **1 次 `drawPath` + 1 支原生渐变**
     * 上一版把原型那条逐列渐变量化成 [WET_RIBBON_N] = **32** 条嵌套的平色 ribbon
     * ⇒ **32 次 `drawPath`**。真机 A/B（同场景同档）实测：整层关掉后帧率
     * **1.4 fps → 7.2 fps** ⇒ 这一层吃掉约 **80% 的帧预算**，而它换来的只是
     * 「没有渐变的平色带」。⇒ 改回原型形态的**连续渐变**，且仍是 **1 次提交**。
     * 预算表：`WET_WASH.opsLow / opsMed / opsHigh` 建议值全部 = **1**。
     *
     * ## ⭐ 本路径的分工
     * | 谁 | 负责什么 |
     * |---|---|
     * | [wetWashNativePaint] 的**竖向渐变** | 纵向的浓淡分布（`t=0` 全透明 → 0.14 峰值 → `t=1` 归零） |
     * | **本函数写的 `wetEdge[i]` 下轮廓** | 湿区的**下边界形状**（逐列记忆量真正落到几何上） |
     * | **本函数写的 `shoreYs[i]` 上轮廓** | 湿区的上沿 = 水线本身（⛔ 不再需要 clip 定上沿） |
     *
     * ⛔ 函数体内**不得**出现 `createLinearGradient` / `Brush.verticalGradient` /
     *   `drawRect(brush =`：渐变是 [wetWashNativePaint] 的着色器（[buildWaveGradients] 烘一次）。
     * ⛔ 原稿 97 个逐列渐变 quad 的 `WET_OVER = 16px` 技巧（把顶端抬到水线之上、让 clip 独自
     *   决定上沿）被 subsume —— 这里上沿**就是** `shoreYs[i]`。
     * ⛔ **本函数体内不得出现第二个 `drawPath(`** —— 高光那一次归 [drawSheen]（§4.9.2 的
     *   「折成同一次」已被所有者推翻，见 [drawSheen] 的 KDoc）。
     *
     * ## ⛔⛔ 渐变的竖向 span **绝不能锚在全幅**（这是前两轮连着踩的坑）
     * 上一版的 [wetBrush] 把 span 锚在 `[waterline_min_bound, SHORE_K + SEA_WET_BAND]`
     * （1080p 下 ≈ `[522.7, 794.3]` px），而 `shoreYs[i]` 实际在 **658…731** px 之间摆动
     * （真机 `dev_20…dev_70` 逐帧量，行中位 R>G 的第一行；理论包络更宽：
     * `h·(SHORE_K ∓ CREST_AMP_SHORE ∓ TIDE_AMP)` = 1080 × `0.484…0.756` = **523…817** px）
     * ⇒ 原型那条「`t=0` 最深」的渐变落到岸线上时 alpha 从 **0.31 一路掉到 0 并被 clamp**
     * ⇒ 接缝处出现一条**深浅随浪 pulsate** 的暗线（凹陷深度实测 0…112，p90 = 45）。
     * ⇒ **本版把 span 挪到「盖住岸线整个摆动范围」**：
     * ```
     * yT = min(shoreYs[i])                            （在门控列上取，下同）
     * yB = max( max(wetEdge[i]), max(shoreYs[i]) )
     * ```
     * 1080p 实测（`dev_70_s1`，水线 ≈671px）：`yT ≈ 658`、`yB ≈ 731 + 湿区厚`
     * ⇒ **span ≈ 75…150 px**，而不是 271.6px 的全幅锚、也不是 523→817 的理论包络。
     * 配合 [WET_WASH_STOP_POS] 的**驼峰**（`t=0` 处 alpha = **0**）⇒
     * 接缝**没有硬边**（alpha 沿每列上沿**连续**变化，不再是「逐列跳变」）⇒ 暗线消失。
     *
     * ## 接缝 alpha 估算（span ≈ 102 px、色标见 [WET_WASH_STOP_POS]、取自 `dev_70_s1`）
     * ```
     * t(y) = (y − 658) / 102
     * 最高岸线  y=658 → t=0.000 → a=0.00   ⛔ 零 alpha、无硬边
     * 本帧水线  y=671 → t=0.127 → a≈0.70   ← 落在驼峰上升沿末段
     * 最低岸线  y=731 → t=0.716 → a≈0.22
     * 湿区下缘  y=760 → t=1.000 → a=0.00
     * ```
     * ⚠️ **残留偏差（明确记录，不是近似误差的托词）**：`a ≈ 0` 这条**只在最高的那一列成立**。
     *   一支共享渐变**不可能**让每一列的水线都落在 alpha≈0 处 —— 那要求 ramp 在整个 span 上
     *   ≈0，等于湿沙整层不画。真正消掉的是**跳变**：旧版 alpha 在列间从 0.31 跳到 0（被 clamp），
     *   那道**游走的暗线**就是这条跳变的形状；现在 alpha 沿上沿**连续**变化 ⇒ 没有线，只有浓淡。
     *   **所有者裁决**：这远好过一条会游走的黑线，也远好过 32 次提交。
     *   要彻底消掉只能回到原型的 97 支逐列渐变（= 97 次提交，见 `:2053`）。
     * ⛔ **不得**用「等高线状分层」换掉它：连续渐变 ⇒ 深度方向上没有任何档边界。
     *
     * ## 逐列 alpha 的取舍（明确记录，不是近似误差的托词）
     * 原型给每列的渐变色标都乘 `wetAmt[i]`（逐列）。一条 path + 一支刷拿不到逐列 alpha
     * ⇒ 改用**逐列 `wetAmt` 的门控 + 连续段（run）切子路径**：某一列干到
     * `wetAmt ≤ [WET_ANY_MIN]`（或湿区厚度 < [WET_BAND_MIN_PX]）就断开 ⇒ 段与段之间的
     * 竖向阶跃正是**水舌边界**，那本来就该是硬边（原型注释原话）。
     * 整层的标量 alpha 取**门控列的 `wetAmt` 平均值**（`Paint.setAlpha` 逐帧乘回归一化基数）。
     * ⛔ x 用浮点、不取整也不 `+1px`（取整留 0.33px 缝、重叠二次合成，两者都会变成竖线）。
     *
     * 门控（§4.7①）：无列 `wetAmt > [WET_ANY_MIN]` ⇒ 整个湿沙层跳过。
     *
     * ⚠️ **调用顺序**：⛔ 本函数必须排在 [drawSheen] **之前** —— 高光靠
     *   `BlendMode.Plus` **叠加在湿沙之上**（原型是 `globalCompositeOperation = 'lighter'`
     *   在湿沙之后提交）。反过来先加高光、再用 source-over 的湿沙盖上去，高光会被
     *   近岸处 `alpha ≈ 0.76` 的湿沙**压掉大半**，等于白付一次提交。
     *   两者现在各写各的 path ⇒ 谁也 `reset()` 不到对方，⛔ 顺序不再有「共享 path」的约束，
     *   但**绘制次序必须**照上面那样。
     */
    private fun DrawScope.drawWetWash() {
        if (BISECT_WET_WASH_OFF) return
        if (BISECT_WET_WASH) return
        val wv = waves ?: return
        val n = wv.column_count
        val ys = wv.shore_ys
        val we = wv.wet_edge
        // 门控：无列够湿 ⇒ 整层跳过（原型 `seaside-preview.html:2030-2031, 2041`）
        // ⭐ 同时把渐变的 span 求出来（**只在门控列上取**）：
        //   `yT = min(shoreYs)`、`yB = max(max(wetEdge), max(shoreYs))`。
        // ⛔ `yB` **必须**再兜一个 `max(shoreYs)`：`wetEdge` 是**记忆量**（退水路过留下的最高水位，
        //   上界 `h·(SHORE_K + SWASH_REACH·1.10)`），而 `shoreYs` 的下界是
        //   `h·(SHORE_K + CREST_AMP_SHORE + TIDE_AMP)` —— **后者更大**。
        //   退潮深处会出现 `max(wetEdge) < max(shoreYs)` ⇒ 若只取 `max(wetEdge)`，
        //   那些列的整条湿区会落在 span **之外** ⇒ 被 `CLAMP` 到 `t=0`（alpha = 0）
        //   ⇒ 湿沙在最深的一批列上整条消失。
        var sum = 0.0
        var cnt = 0
        var yT = Double.MAX_VALUE
        var yB = -Double.MAX_VALUE
        var i = 0
        while (i < n) {
            if (wv.wet_amt[i] > WET_ANY_MIN &&
                (we[i] - ys[i]) >= WET_BAND_MIN_PX
            ) {
                sum += wv.wet_amt[i]
                cnt++
                if (ys[i] < yT) yT = ys[i]
                if (ys[i] > yB) yB = ys[i]
                if (we[i] > yB) yB = we[i]
            }
            i++
        }
        if (cnt <= 0) return
        // ⇒ 逐列平均湿润度（只作 `Paint.alpha` 的标量因子）
        val wetAvg = sum / cnt
        val span = yB - yT
        if (span < WET_WASH_SPAN_MIN) return
        val paint = wetWashNativePaint
        val shader = paint.shader ?: return
        // ⛔ 原生 `Path` 复用前必须 `rewind()`（§零分配：不是 `reset()`，后者会留 fillType）
        wetRegionPath.rewind()
        // ── 逐列门控 + 连续段（run）切子路径：上轮廓 = 水线，下轮廓 = 湿区记忆边缘 ──
        var run = -1
        i = 0
        while (i <= n) {
            val on = i < n && wv.wet_amt[i] > WET_ANY_MIN &&
                (we[i] - ys[i]) >= WET_BAND_MIN_PX
            if (on) {
                if (run < 0) run = i
            } else if (run >= 0) {
                wetRegionPath.moveTo(wv.shore_xs[run].toFloat(), ys[run].toFloat())
                var b = run + 1
                while (b <= i - 1) {
                    wetRegionPath.lineTo(wv.shore_xs[b].toFloat(), ys[b].toFloat())
                    b++
                }
                b = i - 2
                while (b >= run) {
                    wetRegionPath.lineTo(wv.shore_xs[b].toFloat(), we[b].toFloat())
                    b--
                }
                wetRegionPath.lineTo(wv.shore_xs[run].toFloat(), we[run].toFloat())
                wetRegionPath.close()
                run = -1
            }
            i++
        }
        // ⭐ 把归一化渐变 `setScale` + `postTranslate` 到本帧的 `yT..yB`（**零分配**，
        //   机制与 [swellBodyNativePaint] 同一套，见 :2258-2269 的 KDoc）。
        gradMatrix.setScale(1f, span.toFloat())
        gradMatrix.postTranslate(0f, yT.toFloat())
        shader.setLocalMatrix(gradMatrix)
        // 色标 alpha 已除以 [WET_WASH_PEAK_A]（[buildWaveGradients]）⇒ 乘回去
        paint.alpha = alpha255(wetAvg * WET_WASH_PEAK_A)
        drawContext.canvas.nativeCanvas.drawPath(wetRegionPath, paint)
    }

    /**
     * [SeaOpItem.SHEEN] —— 镜面高光。**恰好 1 次 `drawPath` + `BlendMode.Plus`**
     * （⛔ **不再**是「0 次提交」—— §4.9.2 的「折进 [drawWetWash] 那一次」已被所有者推翻）。
     *
     * - §4.7①：少于 [WET_SHEEN_COL_MIN] 列 `wetAmt > [WET_SHEEN_MIN]` ⇒ 高光**内容**跳过，
     *   ⛔ 但**不改提交数**（预算表 `SHEEN.opsLow/opsMed/opsHigh` 恒为 1）。
     * - 保留这一行而不是删掉，是为了让预算表的负向自证有地方落账（§4.9.2 判定它头号超标）。
     * - ⛔ 本函数**恰好 1 个** `drawPath(`，⛔ **不得**出现第二个（那就是第 3 次提交）。
     *
     * ## ⭐ 形态：沿岸一条 [SHEEN_DEPTH_K]·h 深的窄带，**写进自己的 [sheenPath]**
     * 原型（`drawSheen`，`seaside-preview.html:2070-2090`）是逐列 `drawImage` 一条烘好的
     * 高光小条、顶端抬到水线之上 `WET_OVER` 再由 `clip(sandPath)` 切齐 ⇒ **可见部分恰好是
     * `[shoreYs[i], shoreYs[i] + H·0.030]`**。这里把它写成**独立一条** [sheenPath] 的轮廓，
     * 可见部分同样恰好是那一条窄带。
     *
     * ## ⛔⛔ 为什么必须**自己**提交一次（这一条是本轮的核心修正）
     * 上一轮按 §4.9.2 把它折成 [drawWetWash] 那条 path 的**子轮廓**，`ops* = 0`。那条路
     * **结构上就得不到可见的高光**：子轮廓嵌套在湿区内部，`Path` 的默认 **NonZero** 填充规则
     * 把它**并入**湿区 ⇒ 像素集与不加它时**完全相同**。单 path 内**没有**任何能让镜面高光
     * 单独变亮的手段 —— 原型靠的正是 `globalCompositeOperation = 'lighter'` 的**第二次提交**。
     * ⇒ 现恢复第二次提交，并用 `BlendMode.Plus` 复现那次「lighter」。
     *
     * ## 为什么 `BlendMode.Plus` 在这里可用
     * ⚠️ `BlendMode.Plus` 要求绘制落在**离屏层**上。`VisualizerStage` 对本效果**无条件**挂载
     *   `CompositingStrategy.Offscreen`（`VisualizerStage.kt` 的画布层 `graphicsLayer`），
     *   不是按效果或按帧条件挂的 ⇒ `plus` 在 [drawContent] 的任何位置都可用。
     *   ⛔ 若将来 `Offscreen` 变成条件挂载，本函数会**静默退化成 `SrcOver`**（HWUI 对
     *   `PorterDuff.Mode.ADD` 在非离屏目标上按 `SrcOver` 处理）⇒ 高光又变回不可见但**不报错**。
     *   ⇒ 这条依赖是**结构性的**，改动 `VisualizerStage` 的合成策略前必须先来这里。
     *
     * ## ⛔ 用 [sheenBrush]、⛔ **不**复用 [wetBrush]
     * 原型那次 `lighter` 加的是 **`sheenStrip`（只有高光 ramp）**。⛔ 复用 [wetBrush]（湿沙 + 高光
     *   的预合成）会在高光带里把湿沙 ramp 也叠加一遍 ⇒ **那正是本次修正自己引入的视觉偏差**
     *   （原型目标 = `docs/seaside-preview.html`）。⇒ [buildWetBrush] 另烘一支 [sheenBrush]，
     *   色标**逐字照抄** `seaside-preview.html:2013-2017`。⛔ 它同样**只在烘焙期构造**
     *   （`Brush.` ⛔ 不许出现在本函数体内，见 [SeasideTest] ⑤）。
     * ⚠️ **残留偏差（已上报，待裁决）**：原型的条是**逐列**锚定在 `shoreYs[i]` 上的 ⇒ 一支共享刷
     *   拿不到那个逐列锚定，可见带会落在 ramp 的中段而不是原型那样从 `t ≈ 0.37` 走到 `1`。
     *   本支取与 [wetBrush] **完全相同**的 span，理由见 [sheenBrush] 的 KDoc。
     *
     * ⚠️ **调用顺序**：⛔ 必须排在 [drawWetWash] **之后** —— `Plus` 是**叠加**语义，
     *   高光必须加在**已经画好的湿沙**之上（原型顺序：`drawSand → drawWetWash → drawSheen`）。
     *   见 [drawWetWash] 的 KDoc。
     */
    private fun DrawScope.drawSheen() {
        if (BISECT_SHEEN_OFF) return
        if (BISECT_SHEEN) return
        val wv = waves ?: return
        val n = wv.column_count
        var sum = 0.0
        var cnt = 0
        var i = 0
        while (i < n) {
            if (wv.wet_amt[i] > WET_SHEEN_MIN) {
                sum += wv.wet_amt[i]
                cnt++
            }
            i++
        }
        // 门控：少于 [WET_SHEEN_COL_MIN] 列 ⇒ 内容跳过（原型 `:2073-2074`）
        if (cnt < WET_SHEEN_COL_MIN) return
        sheenPath.reset()
        // ⚠ 固定带深（原型 `depth + WET_OVER`，被 `clip(sandPath)` 切成 `H·0.030`）
        val depth = (size.height * SHEEN_DEPTH_K).toFloat()
        var run = -1
        i = 0
        while (i <= n) {
            val on = i < n && wv.wet_amt[i] > WET_SHEEN_MIN
            if (on) {
                if (run < 0) run = i
            } else if (run >= 0) {
                sheenPath.moveTo(wv.shore_xs[run].toFloat(), wv.shore_ys[run].toFloat())
                var j = run + 1
                while (j <= i - 1) {
                    sheenPath.lineTo(wv.shore_xs[j].toFloat(), wv.shore_ys[j].toFloat())
                    j++
                }
                j = i - 2
                while (j >= run) {
                    sheenPath.lineTo(wv.shore_xs[j].toFloat(), wv.shore_ys[j].toFloat() + depth)
                    j--
                }
                sheenPath.lineTo(wv.shore_xs[run].toFloat(), wv.shore_ys[run].toFloat() + depth)
                sheenPath.close()
                run = -1
            }
            i++
        }
        // ⚠ 原型是**逐列** `globalAlpha = wetAmt[i]`（`:2083`）。一次 `drawPath` 只能一个标量
        //   ⇒ 取逐列平均。⚠ 不分档：fix-18 的 3 档在**列**上切线 ⇒ 每道档边界
        //   都是垂直稛缨（真机实拍的「一排竖条依次缩掉」）。
        //   ⚠ 当前仍保留 run 切分：run 边界是**水躿的水舌边界**，HTML 也在那里消失。
        val brush = sheenBrush ?: return
        drawPath(sheenPath, brush, alpha = (sum / cnt).toFloat(), blendMode = BlendMode.Plus)
    }

    // ── ⑤ 沙面覆盖层 ────────────────────────────────────────────────────────

    /**
     * [SeaOpItem.PUDDLE] —— 洼地小水洼 + 压扁椭圆反光。
     * **HIGH 2 次提交 / MEDIUM·LOW 1 次**（⛔ 因「原型保真回补」而变，原先恒 1 次）。
     *
     * 门控（§4.7①）：平均 `wetAmt < WET_AVG_PUDDLE_MIN` ⇒ 跳过。
     *
     * 逐项照搬 `drawPuddles`（`seaside-preview.html:2096-2128`）：
     * - 位置：`p = 0.03 + 0.94·hash2(h, 811)`（⛔ **不**吸附到列栅格，吸附会得到一排规则花纹）、
     *   纵向 `y = lerp(shoreYs[ci], wetEdge[ci], 0.12 + 0.80·hash2(h, 812))`。
     * - 半径：`rx = W·(0.008 + 0.024·hash2(h,813))`、
     *   `ry = H·(0.0020 + 0.0050·hash2(h,814))` ⇒ `ry/rx` 典型 **≈0.2**（⛔ 压扁）。
     * - 逐个 alpha：`a = 0.20·wet·wetAmt[ci]·(0.4 + 0.6·hash2(h,815))`，`a < 0.02 ⇒ 跳过`。
     *   ⚠️ 逐个 alpha 在**一条 path 一次 fill** 下拿不到（原型是 16 次 `fill`）⇒ 这里取该档的
     *   **峰值**（`PUDDLE_A`）并靠**半径/位置**拉开差异；`a < 0.02` 的判据保留为「半径过小则跳过」。
     * - 反光：⛔ **压扁椭圆**（`ry·0.26`）、偏上（`y − ry·0.22`）、横向偏移
     *   `rx·(0.12 + 0.22·hash2(h,816))`、尺寸 `rx·(0.28 + 0.30·hash2(h,817))`；
     *   早先用 `fillRect` 亮线在 100% 下清楚得能看见边 ⇒ 读成画上去的方块，不是水。
     *
     * ⭐ **HIGH 恢复原型的两种颜色**（第 2 次提交）：
     * | 提交 | 路径 | 色 | alpha |
     * |---|---|---|---|
     * | 1 本体 | [puddleNativePath] | [PAL_PUDDLE_BODY]（`#93AEB8`） | `PUDDLE_A·wet` |
     * | 2 反光 | [puddleSpecNativePath] | [PAL_PUDDLE_SPEC]（`#DCEBF0`） | 再 × `[PUDDLE_SPEC_A]`（0.85） |
     * ⛔ **MEDIUM / LOW 仍单色**（取 §5.6 登记的 [PAL_SHOAL]，靠**半径比**读出
     *   「高光更小更靠上」）—— 两次颜色两次提交，预算表只给 HIGH 这两次。
     * ⛔ 两批都走原生 `Path.addOval(l, t, r, b, dir)` 的 float 五参重载 —— Compose 的
     *   `Path.addOval` 只收 `Rect` / `Oval`，用它就得逐个构造 `Rect`（逐帧分配）。
     * ⛔ **两条路径每帧各 `rewind()` 一次**；⛔ **绝不复用**一条（反光若并进本体的 path，
     *   NonZero 下就被并入填充区 ⇒ 第二种颜色根本不存在）。
     * ⚠️ 16 个本体椭圆与 16 个反光椭圆之间**可能共享抗锯齿边界像素** ⇒ 本体的深色描边
     *   会在反光外缘留一道极细的暗圈。这是原型同款几何（原型是 32 次独立 `fill`，
     *   逐块 alpha 也不同）留下的**固有**结果，⛔ 不是靠取整坐标能消掉的（那会改形状）。
     *
     * ## ✅ 两条成因都已修（2026-10-05）
     * 真机 `dev_70_s1.png` / `dev_80_v1.png` 的**干沙**上有一堆淡青半透明圆饼，HTML 基准帧
     * `output/seaside_ref/html_series_400_200_f_59115.png` **没有**。成因两条，都在本函数：
     *
     * 1. **⛔ 缺逐点裁剪** ⇒ **已修**：见 [addSandGatedOvalCap]。原型每个水洼都在
     *    `ctx.clip(sandPath)`（`:2102`）之内 ⇒ 只有 `shoreYs[]` 以下可见；本项目⛔ 零
     *    `clipPath`（§4.9.3）⇒ 改用与 [drawRipples] / [drawResidue] 同款的**逐列门控**：
     *    逐列取 `[shoreXs[c], shoreXs[c+1]] × shoreYs[c..c+1]` 的中点高度 `sm`，
     *    `sm ≥ cy + ry` 的列整列跳过，其余列只发「`sm` 以下的那块椭圆帽」。
     *    ⭐ **与 [drawSand] 用的是同一条岸线折线** ⇒ 水洼边界与沙边界不可能错位。
     * 2. **`wet_edge` 是记忆量、`wet_amt` 只在圆心列采样** ⇒ **已修**：`a` 里的 `wetAmt`
     *    改成**椭圆横向覆盖到的列的算术平均**（循环内的 `wetC`），
     *    ⛔ 不再只取圆心那一列 ⇒ 「圆心还剩一丝 `wetAmt`、邻居列已全干」的椭圆不再成立。
     *
     * ⭐ **提交数不变**：逐列的「椭圆帽」全部并进**同一条** [puddleNativePath]（NonZero 下
     *   自然取并集）⇒ 仍是 **HIGH 2 次 / MED·LOW 1 次** `drawPath`，⛔ 没有为门控加提交。
     *   1080p 下 `cols = 96`（列宽 20px）、`2·rx ≤ 122px` ⇒ 每个水洼最多跨 **7 列**、
     *   全帧 ≤ `16 × 7 = 112` 个五点多边形，仍是**烘焙期之外零分配**（⛔ 不新建任何对象）。
     *
     * @param t 相对首帧的毫秒。
     */
    private fun DrawScope.drawPuddles() {
        if (BISECT_PUDDLE_OFF) return
        val wv = waves ?: return
        val n = wv.column_count
        var sum = 0.0
        var i = 0
        while (i < n) {
            sum += wv.wet_amt[i]
            i++
        }
        if (n <= 0 || sum / n.toDouble() < WET_AVG_PUDDLE_MIN) return
        val twoPass = opsOf(SeaOpItem.PUDDLE) >= 2
        puddleNativePath.rewind()
        if (twoPass) puddleSpecNativePath.rewind()
        val wet = sum / n.toDouble()
        val w = size.width
        val h = size.height
        val den = if (density > 0f) density else 1f
        var k = 0
        while (k < PUDDLE_MAX) {
            val hh = k * 3 + 7
            val p = 0.03 + 0.94 * SeasideWaves.hash2(hh, 811)
            val x = (w * p).toFloat()
            // 纵向落在该列的湿区里（⛔ 不吸附列栅格 ⇒ ci 只是**采样**湿区用的，不是绘制 x）
            var ci = (p * (n - 1)).toInt()
            if (ci < 0) ci = 0 else if (ci > n - 1) ci = n - 1
            val band = PUDDLE_BAND_LO + PUDDLE_BAND_SPAN * SeasideWaves.hash2(hh, 812)
            val y0 = wv.shore_ys[ci]
            val y1 = wv.wet_edge[ci]
            val y = (y0 + (y1 - y0) * band).toFloat()
            val rx = (w * (PUDDLE_RX_LO + PUDDLE_RX_SPAN * SeasideWaves.hash2(hh, 813))).toFloat()
            val ry = (h * (PUDDLE_RY_LO + PUDDLE_RY_SPAN * SeasideWaves.hash2(hh, 814))).toFloat()
            // ⭐⭐ 成因 2 的修法：`wetAmt` **逐列取均值**（椭圆横向覆盖到的那些列），
            //   ⛔ 不再只取圆心列 `ci`。`wv.cols = 96` ⇒ 1080p 列宽 20px，`2·rx ≤ 122px`
            //   ⇒ 覆盖 ≤ 7 列，这个小循环逐帧总次数 ≤ `16 × 7 = 112`（⛔ 可忽略）。
            val cols = wv.cols
            var cc0 = (((x - rx) / w) * cols).toInt()
            var cc1 = (((x + rx) / w) * cols).toInt()
            if (cc0 < 0) cc0 = 0
            if (cc1 > cols - 1) cc1 = cols - 1
            var wetSum = 0.0
            var wetN = 0
            var cc = cc0
            while (cc <= cc1) {
                wetSum += wv.wet_amt[cc]
                wetN++
                cc++
            }
            val wetC = if (wetN > 0) wetSum / wetN.toDouble() else 0.0
            val a = PUDDLE_A * wet * wetC *
                (PUDDLE_A_H_LO + PUDDLE_A_H_SPAN * SeasideWaves.hash2(hh, 815))
            // ⛔ 太薄的水洼不画（等价原型 `a < 0.02 ⇒ continue`，且避免亚像素椭圆）
            if (a >= 0.02 && ry >= den * 0.5f && rx >= den) {
                // ⭐⭐ 逐列门控的椭圆帽（等价原型 `ctx.clip(sandPath)`）：⛔ 不再整颗 `addOval`
                addSandGatedOvalCap(puddleNativePath, x, y, rx, ry)
                // 水面反光：同样椭圆、压扁、偏上，无直角 —— ⭐ **同样要过门控**
                val sdx = (rx * (PUDDLE_SPEC_DX_LO +
                    PUDDLE_SPEC_DX_SPAN * SeasideWaves.hash2(hh, 816))).toFloat()
                val srx = (rx * (PUDDLE_SPEC_RX_LO +
                    PUDDLE_SPEC_RX_SPAN * SeasideWaves.hash2(hh, 817))).toFloat()
                val sry = (ry * PUDDLE_SPEC_RY_K).toFloat()
                val scy = y - (ry * PUDDLE_SPEC_DY_K).toFloat()
                val target = if (twoPass) puddleSpecNativePath else puddleNativePath
                addSandGatedOvalCap(target, x - sdx, scy, srx, sry)
            }
            k++
        }
        val base = alpha255(PUDDLE_A * wet)
        if (twoPass) {
            // ── 提交 1：本体（原型 `#93AEB8`）────────────────────────────────────
            puddleNativePaint.color = AndroidColor.argb(
                base, PAL_PUDDLE_BODY_R, PAL_PUDDLE_BODY_G, PAL_PUDDLE_BODY_B
            )
            drawContext.canvas.nativeCanvas.drawPath(puddleNativePath, puddleNativePaint)
            // ── 提交 2：反光（原型 `#DCEBF0`，`α = a·0.85`）──────────────────────
            //    ⛔ **必须重设 color**：画笔是共用的，[color] 会留在上一帧的值上。
            puddleNativePaint.color = AndroidColor.argb(
                alpha255(PUDDLE_A * wet * PUDDLE_SPEC_A),
                PAL_PUDDLE_SPEC_R, PAL_PUDDLE_SPEC_G, PAL_PUDDLE_SPEC_B
            )
            drawContext.canvas.nativeCanvas.drawPath(puddleSpecNativePath, puddleNativePaint)
        } else {
            // ⛔ MEDIUM / LOW 单色：取 §5.6 登记的 [PAL_SHOAL]，靠**半径比**读出高光
            puddleNativePaint.color = AndroidColor.argb(base, PAL_SHOAL_R, PAL_SHOAL_G, PAL_SHOAL_B)
            drawContext.canvas.nativeCanvas.drawPath(puddleNativePath, puddleNativePaint)
        }
    }

    /**
     * ⭐⭐ **把一颗椭圆按当前岸线裁剪后并进 [path]** —— 原型 `ctx.clip(sandPath)`
     * （`seaside-preview.html:2102`）在本项目的**等价物**（2026-10-05，[drawPuddles] 成因 1）。
     *
     * ## 为什么不能直接 `addOval`
     * ⛔ 零 `clipPath`（§4.9.3，Android 5.1 真机三次复现 hwui SIGSEGV）⇒ 没法把位图/椭圆
     * 真正裁进「岸线折线以下」。而 `addOval` 是**整颗**加入 ⇒ 椭圆上缘会探到水线之上，
     * 在**干沙**上读成「浮着的青色圆饼」（HTML 基准帧没有这个现象）。
     *
     * ## 做法（与 [drawRipples] / [drawResidue] 同款的**逐列门控**）
     * 逐列 `c`（列宽 = `w / cols`，`cols = [SeasideWaves.COLS]` = 96）：
     * 1. 列区间 `[shoreXs[c], shoreXs[c+1]]` —— ⭐ **直接复用** [drawSand] 那条岸线折线的
     *    顶点 ⇒ 水洼边界与沙边界**不可能错位**（⛔ 不另立一套网格，也就不会有缝）。
     * 2. 取该列中点的岸线高度 `sm`（`shoreYs[c]` 与 `shoreYs[c+1]` 的均值）。
     * 3. `sm ≥ cy + ry` ⇒ 这一列**整列**在水线之上 ⇒ ⛔ 不发任何几何。
     * 4. 否则发「`sm` 以下的那块**椭圆帽**」：上沿 `yTop = max(sm, cy − ry)`，
     *    半宽 `hx = rx·√(1 − v²)`（`v = clamp((yTop − cy)/ry, −1, 1)`）——
     *    ⭐ 完全落在水里时 `yTop = cy − ry`、`v = −1`、`hx = rx` ⇒ **精确还原整颗椭圆**；
     *    部分被裁时就是下方的帽。
     * 5. 下弧取 2 个 45° 采样点（`ry` 只有 2–8px，⛔ 再细分也读不出来）⇒ 五点多边形。
     *
     * ⛔ **零分配**：只读预分配数组 + 往调用方给好的 [path] 里写顶点，
     *    ⛔ 不构造 `Path` / `Rect` / `RectF` / 任何 lambda。⚠️ 因不是 `DrawScope.drawXxx`
     *    ⇒ 不会被「每帧 `draw*` 函数」那几道源码门扫到，⛔ 但它确实逐帧被调用，语义等价。
     * ⛔ 非零环绕（NonZero）下多颗 CW 子路径自动取并集 ⇒ [drawPuddles] 的**提交数不变**
     *    （HIGH 2 次 / MED·LOW 1 次 `drawPath`），⛔ 没有为门控新增提交。
     *
     * @param path 目标路径（[puddleNativePath] 或 [puddleSpecNativePath]）。
     * @param cx 椭圆心 x（画布 px）。
     * @param cy 椭圆心 y（画布 px）。
     * @param rx 横向半径（px）。
     * @param ry 纵向半径（px）。
     */
    private fun addSandGatedOvalCap(path: NativePath, cx: Float, cy: Float, rx: Float, ry: Float) {
        val wv = waves ?: return
        val cols = wv.cols
        val w = seaW
        if (cols < 1 || w <= 0f || rx <= 0f || ry <= 0f) return
        // 椭圆横向覆盖到的列区间（⛔ 与调用方同一套换算：`x → col = (x/w)·cols`）
        var c0 = (((cx - rx) / w) * cols).toInt()
        var c1 = (((cx + rx) / w) * cols).toInt()
        if (c0 < 0) c0 = 0
        if (c1 > cols - 1) c1 = cols - 1
        val yBot = cy + ry
        val yEllTop = cy - ry
        // 椭圆 45° 处的归一化坐标（⛔ 常量：`cos45 = √2/2`，⛔ 不调 `pow`）
        val k45 = 0.70710678f
        var c = c0
        while (c <= c1) {
            val xm = ((wv.shore_xs[c] + wv.shore_xs[c + 1]) * 0.5).toFloat()
            val sm = ((wv.shore_ys[c] + wv.shore_ys[c + 1]) * 0.5).toFloat()
            // 整列都在水线之上 ⇒ 这一列不画（⭐ 这就是「圆饼探到干沙上」的正解）
            if (sm < yBot) {
                val yTop = if (sm > yEllTop) sm else yEllTop
                var v = (yTop - cy) / ry
                if (v < -1f) v = -1f else if (v > 1f) v = 1f
                val hx = rx * sqrt(1f - v * v)
                val yMid = cy + ry * k45
                val hMid = rx * k45
                path.moveTo(xm - hx, yTop)
                path.lineTo(xm + hx, yTop)
                path.lineTo(xm + hMid, yMid)
                path.lineTo(xm, yBot)
                path.lineTo(xm - hMid, yMid)
                path.close()
            }
            c++
        }
    }

    /**
     * [SeaOpItem.RESIDUAL_STREAK] —— 退水残沫（3 pass 羽状丝缕）。
     * **HIGH 3 次 `drawLines` / MEDIUM·LOW 1 次**（⛔ 因「原型保真回补」而变，原先恒 1 次）。
     *
     * 门控（§4.7①）：平均 `wetAmt < WET_AVG_RESIDUAL_MIN` ⇒ 跳过。
     *
     * 逐项照搬 `drawResidualStreaks`（`seaside-preview.html:2332-2364`）：
     * - 3 pass，每 pass 26 根；`h = seg·3 + pass` 错开三 pass 的位置。
     * - ⛔ **必须短**：每根横向只跨 `a1 − a0 = 0.012 + 0.065·hash`（≤ 屏宽的 7.7%）
     *   ⇒ 太长就成「等高线」（§4.3.8 原型已判失败）。
     * - ⛔ **三 pass 不得连成纹路**（§4.3.4 明文）—— 靠的是 `h = seg·3 + pass` 的错开采样
     *   + **三档不同的线宽 / alpha**（`0.9 + 0.8·pass` / `A·(1 − 0.28·pass)`）；
     *   ⛔ 单 pass 档（MEDIUM/LOW）保留错开、只取 pass 0 的线宽与 alpha。
     * - 纵向：`y = lerp(shoreYs[i], wetEdge[i], 0.06 + 0.90·pow(hash, 0.65))`
     *   （`pow(·, 0.65)` 偏向内陆）+ `0.0026·H·sin(x·0.026·kScale + h·1.7 + t·0.00042)`。
     *
     * ⛔ **逐 pass 重填同一条 [residualStreakPts]**（⛔ 不开三条缓冲）：每个 pass 先填满
     *   再整条提交 ⇒ 上一 pass 的坐标**不会**被后一 pass 追加进同一次调用。
     * ⛔ 走 `nativeCanvas.drawLines` + 缓存画笔（⛔ **不得**逐帧构造 `Stroke` ——
     *   `Stroke.width` 不可变、且 `SeasideTest` ⑥ 只允许两个烘焙期构造点）。
     */
    private fun DrawScope.drawResidualStreaks(t: Double) {
        if (BISECT_RESIDUAL_OFF) return
        val passes = opsOf(SeaOpItem.RESIDUAL_STREAK)
        if (passes <= 0) return
        val wv = waves ?: return
        val n = wv.column_count
        var sum = 0.0
        var i = 0
        while (i < n) {
            sum += wv.wet_amt[i]
            i++
        }
        if (n <= 0 || sum / n.toDouble() < WET_AVG_RESIDUAL_MIN) return
        val wet = sum / n.toDouble()
        val w = size.width
        val h = size.height
        val kScale = if (w > 1f) CAUSTIC_SLOPE_REF_W / w else 1f
        val den = if (density > 0f) density else 1f
        var pass = 0
        while (pass < passes) {
            var count = 0
            var seg = 0
            while (seg < RESIDUE_STREAK_SEG_N) {
                val hh = seg * 3 + pass
                val a0 = SeasideWaves.hash2(hh, 501)
                val a1 = a0 + RESIDUE_STREAK_SPAN_LO +
                    RESIDUE_STREAK_SPAN_SPAN * SeasideWaves.hash2(hh, 502)
                val band = powD(
                    SeasideWaves.hash2(hh, 503),
                    RESIDUE_STREAK_BAND_POW
                ) * RESIDUE_STREAK_BAND_SPAN + RESIDUE_STREAK_BAND_LO
                var px = 0f
                var py = 0f
                var k = 0
                while (k <= RESIDUE_STREAK_STEPS) {
                    val p = a0 + (a1 - a0) * (k.toDouble() / RESIDUE_STREAK_STEPS.toDouble())
                    var ci = (p * (n - 1)).toInt()
                    if (ci < 0) ci = 0 else if (ci > n - 1) ci = n - 1
                    val x = (w * p).toFloat()
                    val y0 = wv.shore_ys[ci]
                    val y1 = wv.wet_edge[ci]
                    val y = (y0 + (y1 - y0) * band +
                        RESIDUE_STREAK_WOB_K * h *
                        sin(x.toDouble() * 0.026 * kScale + hh * 1.7 + t * RESIDUE_STREAK_WOB_T)
                        ).toFloat()
                    if (k > 0 && count + 4 <= residualStreakPts.size) {
                        residualStreakPts[count] = px
                        residualStreakPts[count + 1] = py
                        residualStreakPts[count + 2] = x
                        residualStreakPts[count + 3] = y
                        count += 4
                    }
                    px = x
                    py = y
                    k++
                }
                seg++
            }
            if (count >= 4) {
                // ⛔ 2026-10-05 补上**漏掉的颜色**：`residualStreakPaint` 此前只设了
                //   `alpha` / `strokeWidth`，`.color` 从未赋值 ⇒ 保持 `Paint()` 默认
                //   **纯黑 (0,0,0)**。原型是 `ctx.strokeStyle = PAL.foam`
                //   （`seaside-preview.html:2427`），`PAL.foam = '#F2F7F5'`
                //   （`:441`）= 本文件 [PAL_FOAM_EDGE]（`:476`，`0xFFF2F7F5`）⇒ 逐字对齐。
                residualStreakPaint.color = PAL_FOAM_EDGE
                residualStreakPaint.strokeWidth =
                    (RESIDUE_STREAK_W_LO + RESIDUE_STREAK_W_SPAN * pass) * den
                residualStreakPaint.alpha = alpha255(
                    RESIDUE_STREAK_A * (1.0 - RESIDUE_STREAK_PASS_DECAY * pass) *
                        (0.35 + 0.65 * wet)
                )
                drawLinesBatch(residualStreakPts, 0, count, residualStreakPaint)
            }
            pass++
        }
    }

    // ── ⛔ 整层删除记录：「岸线细亮湿线」（原 `drawWetLine` / `SeaOpItem.WET_LINE`）────────
    //
    // ⛔ **2026-10-05 所有者视觉裁决：接缝处不要任何线。** 原话：「原来的黑线改成白色的线了，
    //   这条线的作用是啥？我觉得没用啊，应该去掉」。
    //
    // 为什么 Kotlin 版**本就走形**（不是移植缺陷，是实现缺陷）：
    // - 原型 `seaside-preview.html:2367-2389` 的 `drawWetLine` 是在 `ctx.save(); ctx.clip(sandPath);`
    //   **之后**才描的 ⇒ 原型里这条线**只在沙侧**可见，是「湿沙上的一道亮痕」。
    // - 本项目⛔ 零 `clipPath`（§4.9.3，Android 5.1 真机三次复现 hwui SIGSEGV）⇒ 线以岸线
    //   为中心、**一半落在海侧**，读成「把海沙接缝描了一遍」—— 这正是所有者看到的那条线。
    // - ⛔ **不要再**用「HTML 原型里有这条线、属忠实移植」来解释或保留它：那是**第一次黑线**
    //   时的错误结论（后来证实真根因是 `wetLinePaint` 漏赋 `.color`，默认纯黑）。
    //   补上颜色后它变成白线，视觉裁决判定它本身无用 ⇒ 整层删除。
    //
    // 一并清除的死物（⛔ 不留无引用的死常量/死字段）：`WET_LINE_A` / `WET_LINE_STAGE_K` /
    // `WET_LINE_PASS_N` / `WET_LINE_W_LO` / `WET_LINE_W_SPAN` / `WET_LINE_PASS_DECAY` /
    // `WET_LINE_SEG_N` / `WET_LINE_SEG_GAP` / `WET_LINE_WOB_K` / `WET_LINE_WOB_T` /
    // `wetLinePts` / `wetLinePaint` / `wetLineBrush`（后者本就只写不读）/ `BISECT_WETLINE_OFF`，
    // 以及 [drawContent] 帧序里的那次调用。
    //
    // `SeaOpItem.WET_LINE` 的枚举条目**按裁决保留**在 [SeaOpItem] 里（预算表归所有者同步，
    // ⛔ 本文件已无任何引用；`SeasideOpBudgetTest` 只读枚举值，⛔ 不受影响）。

    // ── ⑥ 浪 ────────────────────────────────────────────────────────────────

    /**
     * **编排函数**（本身 **0 提交**）—— 逐槽位遍历浪队列，把在册浪分派给各泡沫层。
     *
     * ⛔ 元素归属：**无独立 [SeaOpItem]** —— 它是编排入口；它调用的每个 `draw*` 都在
     *   自己的 KDoc 里标了对应的 [SeaOpItem]（逐浪元素按 [SeaOpItem.perWave] 乘浪数）。
     *
     * 逐浪的 cull 顺序（⛔ **不可换**，§4.7① 表的前两行刻意排在 `kA` 之前）：
     * ```
     * amp ≤ AMP_CULL_MIN 或 fade ≤ FADE_CULL_MIN ⇒ 跳过整条浪
     *   → kA ≤ 0 ⇒ 跳过
     *     → fill_fray / fill_tears（该浪自己的 lane，⛔ breaker_front_y 依赖它）
     *       → kA < KA_BODY_MIN ⇒ 只剩本体与窄带
     *         → kA < KA_FOAM_MIN ⇒ 没有白沫晕与蕾丝
     *           → kA < KA_PATCH_MIN ⇒ 没有外海泡沫贴图
     * ```
     * ⛔ **绝不**下调这些阈值来「让浪一直可见」—— §4.7① 已判定该分布是设计内的。
     * ⛔ `isLead` 用 `Wave.is_beach`（真正抵达滩上的那条），⛔ **不是** `lead_wave()`。
     *
     * @param t 相对首帧的毫秒。
     */
    private fun DrawScope.drawSwellBands(t: Double) {
        val wv = waves ?: return
        val pool = wv.pool_size
        edgeValid = false
        var slot = 0
        while (slot < pool) {
            val wv1 = wv.wave_at(slot)
            slot++
            if (wv1 == null) continue
            val isLead = wv1.is_beach
            val fade = wv.wave_fade(wv1)
            val amp = audio.waveAmp(isLead, wv1.wi_s)
            if (amp <= AMP_CULL_MIN || fade <= FADE_CULL_MIN) continue
            val ka = if (isLead) audio.leadKa(amp, fade, wv1.y) else audio.followKa(amp, fade)
            if (ka <= 0.0) continue
            // ⛔ 必须先填该浪**自己 lane** 的场：`breaker_front_y` 会读它（§14.3.2 的硬前提）。
            wv.fill_fray(t, wv1.lane)
            wv.fill_tears(wv1.lane)
            if (ka < KA_BODY_MIN) continue
            // ⭐ 逐列场（原型 `drawSwellBands` 那个 for 循环，
            //   `seaside-preview.html:3054-3159`）
            val farMean = buildWaveColumns(wv, wv1, amp, isLead, t)
            val w0 = size.height.toDouble() * SeasideWaves.SWELL_BAND_W *
                (0.62 + 0.62 * amp) * (if (isLead) 1.0 else 0.92)
            val dir = if (isLead) -1 else 1
            val kaB = ka * (if (isLead) SHORE_FOAM_BOOST else 1.0)
            drawSwellBody(w0, kaB, dir)
            drawFoamStrip(wv1, isLead, w0, kaB, t, dir)
            if (ka < KA_FOAM_MIN) continue
            drawSeaFoamWash(w0, kaB, dir)
            drawFoamLace(w0, kaB, t, wv1.lane, isLead, dir)
            if (ka < KA_PATCH_MIN) continue
            drawOpenSeaFoam(w0, kaB, t, wv1.lane, dir)
            // ⭐ 扰动前锋：**只对非领头浪**（原型 `if (!isLead) drawDisturbance(...)`），
            //   且原型传的是 **`ka` 而 ⛔ 不是 `kaB`**（⛔ 不含 `SHORE_FOAM_BOOST`）。
            if (!isLead) drawDisturbance(w0, ka, t, wv1.lane, dir)
            // ⛔ 贴岸唇**只对非领头浪**（领头浪的前缘就是水线本身，§4.3.4）。
            if (!isLead && opsOf(SeaOpItem.CREST_LIP) > 0) drawCrestLip(w0, kaB * farMean, dir)
        }
    }

    /**
     * ⭐⭐ 逐列场（原型 `drawSwellBands` 的 `for (let i = 0; i < n; i++)`
     * 那个循环，`seaside-preview.html:3054-3159`）—— 写 [bxs] / [bfy] /
     * [slopeLaw] / [farLaw] / [bwj] / [edgeYs]。**本函数 0 提交**（纯算术），
     * 它产出的六个数组供该浪的 6 个 `draw*` 层共用。
     *
     * ## 三条硬不变量都在这里（§4.3.9）
     * ① `wiS = serial & 3` / `lane = 1 + serial % 3` —— ⛔ 全程**不读绘制次序下标**
     *    （本类逐槽位遍历，天然没有 `wi`；`nb1/nb2/env` 的相位与种子
     *    都只挂 `wiS`）。
     * ② `shrink = max([SHRINK_MIN], frayK·envK·slK)` —— ⛔ 下限加在**乘积**上。
     * ③ `slK = [SLK_LO] + [SLK_SPAN]·slopeLaw[i]` —— ⛔ **不含**距离律。
     *
     * @return 该浪的平均距离律 `farMean`（⛔ **只**给单一 alpha 的破碎唇用）。
     */
    private fun DrawScope.buildWaveColumns(
        wv: SeasideWaves,
        wv1: SeasideWaves.Wave,
        amp: Double,
        isLead: Boolean,
        t: Double,
    ): Double {
        val n = wv.column_count
        val w = size.width
        val hpx = size.height.toDouble()
        val wiS = wv1.wi_s
        val lane = wv1.lane
        val adv = SeasideWaves.clamp(wv1.y, 0.0, 1.0)
        val fray = wv.fray_of(lane)
        val rag = wv.tear_of(lane)
        val w0 = hpx * SeasideWaves.SWELL_BAND_W * (0.62 + 0.62 * amp) * (if (isLead) 1.0 else 0.92)
        val kScale = if (w > 1f) CAUSTIC_SLOPE_REF_W / w else 1f
        val colDx = w.toDouble() / SeasideWaves.COLS.toDouble()
        val farSpan = FOAM_FAR_SPAN * hpx
        val shoreK = SeasideWaves.SHORE_K * hpx
        val farPow = if (isLead) FOAM_FAR_POW_LEAD else FOAM_FAR_POW_FOLLOW
        var farSum = 0.0
        var i = 0
        while (i < n) {
            val x = wv.shore_xs[i]
            bxs[i] = x.toFloat()
            bfy[i] = wv.breaker_front_y(adv, i, isLead, lane, wv1.seed, w0).toFloat()
            // 逐列斜率（倾角律的代理量）：用相邻两列的 y 差，单位 = 每像素
            val dx = if (i == 0) 0.0 else (bfy[i] - bfy[i - 1]) / colDx
            var sl = SeasideWaves.clamp(abs(dx) * FOAM_SLOPE_GAIN * FOAM_SLOPE_X4, 0.0, 1.0)
            var fl = powD(
                SeasideWaves.clamp(1.0 - (shoreK - bfy[i]) / farSpan, 0.0, 1.0), farPow
            )
            if (isLead) {
                // ⭐ 水线处永远满浫泪（§4.3.5 的 `if (isLead) { slopeLaw = 1; farLaw = 1 }`）
                sl = 1.0
                fl = 1.0
            }
            slopeLaw[i] = sl.toFloat()
            farLaw[i] = fl.toFloat()
            farSum += fl
            // ⛔ 相位挂 `wiS`（⛔ 绝不挂绘制次序 `wi` —— 那是 §4.3.9 ① 的主因）
            val nb1 = 0.5 + 0.5 * sin(x * NB1_K * kScale + t * NB1_T + wiS * NB1_PHASE)
            val nb2 = 0.5 + 0.5 * sin(x * NB2_K * kScale - t * NB2_T + wiS * NB2_PHASE)
            val env = if (isLead) 1.0
            else powD(
                SeasideWaves.fbm_norm(
                    x * ENV_K * kScale + wiS * ENV_PHASE + wv1.seed * 0.001 + t * ENV_T,
                    ENV_SEED + wiS * ENV_SEED_STEP, 2
                ), ENV_POW
            )
            val frayK = 1.0 + fray[i] * SeasideWaves.FRAY_WIDTH
            val envK = if (isLead) 1.0 else ENVK_LO + ENVK_SPAN * env
            val slK = if (isLead) 1.0 else SLK_LO + SLK_SPAN * sl
            // ⭐ 硬下限加在**乘积**上（§4.3.9 ②）
            var shrink = frayK * envK * slK
            if (shrink < SHRINK_MIN) shrink = SHRINK_MIN
            bwj[i] = ((BWJ_BASE + BWJ_W1 * nb1 + BWJ_W2 * nb2) * rag[i] * shrink).toFloat()
            if (isLead) {
                edgeYs[i] = bfy[i]
                edgeValid = true
            }
            i++
        }
        return if (isLead) 1.0 else farSum / n.toDouble()
    }

    /**
     * [SeaOpItem.SWELL_BODY] —— 非泡沫浪本体（迎光亮 + 背光暗）。**逐浪 1 次提交**。
     *
     * ⛔ 合批改造**不碰**它（§4.9.2 末「保持不变」）；它的距离衰减远比泡沫慢
     * （≈ pow 0.55 而非 1.6）⇒ 远处的浪仍有形状，只是没有白。
     *
     * @param wv 该浪（`lane` 场已由 [drawSwellBands] 填好）。
     * @param ka 该浪的合成泡沫强度 `0..1`。
     */
    private fun DrawScope.drawSwellBody(w0: Double, ka: Double, dir: Int) {
        if (ka < KA_BODY_MIN) return
        val n = waves?.column_count ?: return
        val front = (dir * SWELL_BODY_FRONT * w0).toFloat()
        val back = (-dir * SWELL_BODY_BACK * w0).toFloat()
        var fk = 0.0
        var any = false
        var i = 0
        while (i < n) {
            // ⭐ 水体的距离律比泡沫平缓得多（1/(1+3.2·(1−farLaw))，而不是 pow 1.6）
            val far = 1.0 / (1.0 + (1.0 - farLaw[i]) * SWELL_BODY_FAR_K)
            fk += (SWELL_BODY_FAR_LO + SWELL_BODY_FAR_SPAN * slopeLaw[i]) * far
            washA[i] = bfy[i] + front
            washB[i] = bfy[i] + back
            if (bwj[i] > 0.35f) any = true
            i++
        }
        if (!any) return
        val a = SWELL_BODY_A * SeasideWaves.clamp(ka, 0.0, 1.0) * (fk / n.toDouble())
        // ⭐ 渐变锚在**逐列** y 极值（全幅 min/max）上
        var yT = Float.MAX_VALUE
        var yB = -Float.MAX_VALUE
        i = 0
        while (i < n) {
            if (washA[i] < yT) yT = washA[i]
            if (washB[i] > yB) yB = washB[i]
            i++
        }
        val span = (yB - yT).toDouble()
        if (a < WASH_A_MIN || span < SWELL_SPAN_MIN) return
        swellBodyNativePath.rewind()
        drawRibbonNative(swellBodyNativePath, n, true)
        drawRibbonNative(swellBodyNativePath, n, false)
        swellBodyNativePath.close()
        val shader = swellBodyNativePaint.shader ?: return
        gradMatrix.setScale(1f, span.toFloat())
        gradMatrix.postTranslate(0f, yT)
        shader.setLocalMatrix(gradMatrix)
        swellBodyNativePaint.alpha = alpha255(a / SWELL_BODY_NORM)
        drawContext.canvas.nativeCanvas.drawPath(swellBodyNativePath, swellBodyNativePaint)
    }

    /**
     * [SeaOpItem.SWELL_BODY] / [SeaOpItem.SEA_FOAM_WASH] 共用的**原生带状折线**
     * （原型 `smoothRunRange`，`seaside-preview.html:2812-2820`）—— 两侧各走一条
     * 「穿过中点的二次曲线」，两端落在端点上。
     *
     * ⛔ 本函数不新建任何对象（读 [washA] / [washB] / [bxs] 三个预分配数组）。
     * ⛔ 命名带 `draw` 前缀是为了进 `PerfBudgetContractTest` 的每帧可达扫描
     *   （§4.9.4 陷阱 1）—— 否则它整体逃过零分配门禁。
     *
     * @param useA `true` 走上沿（`moveTo` 开始）、`false` 走下沿（`lineTo` 接上）。
     */
    private fun DrawScope.drawRibbonNative(path: NativePath, n: Int, useA: Boolean) {
        if (useA) path.moveTo(bxs[0], washA[0]) else path.lineTo(bxs[0], washB[0])
        var i = 1
        while (i < n - 1) {
            val ya = if (useA) washA[i] else washB[i]
            val yb = if (useA) washA[i + 1] else washB[i + 1]
            path.quadTo(bxs[i], ya, (bxs[i] + bxs[i + 1]) * 0.5f, (ya + yb) * 0.5f)
            i++
        }
        path.lineTo(bxs[n - 1], if (useA) washA[n - 1] else washB[n - 1])
    }

    /**
     * [SeaOpItem.SEA_FOAM_WASH] 的 [drawRibbonNative] **区间版本**（原型 `smoothRunRange`，
     * `seaside-preview.html:2811-2820`）—— [drawSeaFoamWash] 逐段提交时要只画 `[i0, i1)`。
     *
     * ⭐ 两端都**落在端点上**（⛔ 不是外推到半格）⇒ 相邻段首尾的曲线控制点重合，
     * 所以段与段之间不会出现竖直接缝（原型 2767-2768 的原话）。
     *
     * ⛔ 不新建任何对象；命名带 `draw` 前缀的理由同 [drawRibbonNative]。
     */
    private fun DrawScope.drawRibbonRangeNative(
        path: NativePath, i0: Int, i1: Int, useA: Boolean,
    ) {
        if (i1 - i0 < 2) {
            if (useA) path.moveTo(bxs[i0], washA[i0]) else path.lineTo(bxs[i0], washB[i0])
            path.lineTo(bxs[i1 - 1], if (useA) washA[i1 - 1] else washB[i1 - 1])
            return
        }
        if (useA) path.moveTo(bxs[i0], washA[i0]) else path.lineTo(bxs[i0], washB[i0])
        var i = i0
        while (i < i1 - 1) {
            val ya = if (useA) washA[i] else washB[i]
            val yb = if (useA) washA[i + 1] else washB[i + 1]
            path.quadTo(bxs[i], ya, (bxs[i] + bxs[i + 1]) * 0.5f, (ya + yb) * 0.5f)
            i++
        }
        path.lineTo(bxs[i1 - 1], if (useA) washA[i1 - 1] else washB[i1 - 1])
    }

    /**
     * [SeaOpItem.FOAM_LADDER] 的**原生折线**（原型 `smoothRun`，
     * `seaside-preview.html:1926-1942`）—— `stride` 让窄带能以**半列分辨率**采样
     * 而不塌缩到屏幕左半。
     *
     * ⛔ 不新建任何对象。命名带 `draw` 前缀的理由同 [drawRibbonNative]。
     *
     * @param useLo `true` 走 [slabLo]（正向，`moveFirst` 时用 `moveTo`）、
     *   `false` 走 [slabHi]（回程，直接 `lineTo` 接上）。
     */
    private fun DrawScope.drawSmoothRunNative(
        path: NativePath, n: Int, stride: Int, forward: Boolean, moveFirst: Boolean, useLo: Boolean,
    ) {
        val start = if (forward) 0 else n - 1
        val stop = if (forward) n - 1 else 0
        val dirI = if (forward) stride else -stride
        var idx = start * stride
        if (moveFirst) path.moveTo(bxs[idx], if (useLo) slabLo[idx] else slabHi[idx])
        else path.lineTo(bxs[idx], if (useLo) slabLo[idx] else slabHi[idx])
        while (idx != stop * stride) {
            val nidx = idx + dirI
            val ya = if (useLo) slabLo[idx] else slabHi[idx]
            val yb = if (useLo) slabLo[nidx] else slabHi[nidx]
            path.quadTo(bxs[idx], ya, (bxs[idx] + bxs[nidx]) * 0.5f, (ya + yb) * 0.5f)
            idx = nidx
        }
        path.lineTo(bxs[idx], if (useLo) slabLo[idx] else slabHi[idx])
    }

    /**
     * [SeaOpItem.FOAM_LADDER] —— 泡沫窄带（`ladder` 阶梯）。**逐浪**，段数 = 预算表 ops。
     *
     * ⛔ **改名说明**：方案原稿 §14.3.4 叫 `fillStrip`，本文件改名 [drawFoamStrip] ——
     * 因为 `PerfBudgetContractTest` 的每帧可达正则只匹配 `fun DrawScope.drawXxx(`，
     * 不带 `draw` 前缀的名字会**整体逃过**零分配扫描（§4.9.4 陷阱 1）。
     *
     * 段数直接取 [opsOf]（HIGH 14 / MEDIUM 7 / LOW 7），⛔ 不另立一份常量。
     *
     * @param wv 该浪。
     * @param ka 该浪的合成泡沫强度。
     */
    private fun DrawScope.drawFoamStrip(
        wv: SeasideWaves.Wave,
        isLead: Boolean,
        w0: Double,
        ka: Double,
        t: Double,
        dir: Int,
    ) {
        if (BISECT_FOAMSTRIP_OFF) return
        val segs = opsOf(SeaOpItem.FOAM_LADDER)
        if (segs <= 0) return
        // 领头浪用完整阶梯（13 条）、外侧弱浪用隔一取二的粗阶梯（6 条）。
        val ladder = if (isLead) FOAM_LADDER else FOAM_LADDER_COARSE
        var m = ladder.size / 3
        if (m > segs) m = segs
        val cols = SeasideWaves.COLS
        val wf = w0.toFloat()
        // ⛔ 绘制次序为**从尾到前缘**（原型 `for (s = len-1; s >= 0; s--)`）。
        var s = m - 1
        while (s >= 0) {
            val n0 = ladder[s * 3]
            val n1 = ladder[s * 3 + 1]
            val sa = ladder[s * 3 + 2]
            val alpha = SeasideWaves.clamp(sa.toDouble() * ka, 0.0, 1.0)
            if (alpha >= KA_STRIP_MIN) {
                val step2 = if (sa < STRIP_A_HALF_SAMPLE) 2 else 1
                val cnt = cols / step2 + 1
                var j = 0
                while (j <= cols) {
                    val bw = bwj[j]
                    slabLo[j] = bfy[j] + dir * n0 * bw * wf
                    slabHi[j] = bfy[j] + dir * n1 * bw * wf
                    j += step2
                }
                foamStripNativePath.rewind()
                drawSmoothRunNative(foamStripNativePath, cnt, step2, true, true, true)
                drawSmoothRunNative(foamStripNativePath, cnt, step2, false, false, false)
                foamStripNativePath.close()
                drawPunchHoles(wv.lane, s, n0, n1, wf, ka, t, dir)
                foamStripNativePaint.color = if (sa >= 0.35f) PAL_FOAM_EDGE else PAL_FOAM_CORE
                foamStripNativePaint.alpha = alpha255(alpha)
                drawContext.canvas.nativeCanvas.drawPath(foamStripNativePath, foamStripNativePaint)
            }
            s--
        }
    }

    /**
     * [SeaOpItem.FOAM_LADDER] 的 evenodd 破洞（原型 `punchHoles`，
     * `seaside-preview.html:2486-2512`）—— 水从洞里透出来 ⇒ 白沫是「花边」不是「实心带」。
     *
     * ## ⛔ 两条硬红线（owner 报障「前浪的白色浪花一直存在，现在老是闪烁」后立的）
     * 1. ⛔ **逐帧程序化场不得用量化时间换随机种子**（⛔ 不得写
     *    `hash2(h, 710 + floor(t / HOLE_PERIOD_MS) + …)`）：破洞是 evenodd 挖进白沫路径、
     *    与条带轮廓**一次 fill** 的 ⇒ 周期边界上**所有洞的位置与半径在同一帧整体瞬移**，
     *    白沫图案整帧闪一次，且所有洞一起眨眼。⇒ 每洞一个**由 hash 固定的相位** `ph`，
     *    `u = ((t / HOLE_PERIOD_MS) + ph) % 1`（⛔ **不取整**）。原型实测
     *    （`seaside_hole_continuity_check.js`，按可见性 `ry ≥ 1.5` 加权）：
     *    改前 6.2px ＝ 自身半径的 2.6× → 改后 0.74px ＝ 0.31×，**8.4×**。
     * 2. ⛔ **取模回绕 ＝ 不连续**：横漂必须是**有界振荡**
     *    `hx = clamp(ph + 0.13·sin(t·0.00021 + ph·TAU), 0.02, 0.98)`。⛔ 不可写成
     *    `(ph + … + 1) % 1` —— 原型第一版正是如此，`% 1` 让 `hx` 从 ~1.0 跳回 ~0.0，
     *    洞**每回绕一帧就横穿整屏**：harness 实测 **1599px/帧 ≈ 整屏宽**。
     *
     * ⭐ `grow = sin(π·u)` 使半径在 `u` 两端收缩到**地板值** `gap·[HOLE_HR_LO]·k`
     * （原型代码是地板项、⛔ 不是严格 0）—— 地板落在 `ry < [HOLE_RY_MIN]` 的丢弃阈值
     * **之下** ⇒ 每个洞是「长出来 / 缩回去」而不是「啪一下出现 / 消失」，`u` 的回绕无害。
     *
     * ⛔ 椭圆孔用**原生** [NativePath.addOval] 的 float 五参重载（⛔ **不得** `addOval(Rect(…))`：
     * Compose 版只有 `Rect` / `Oval` 两种重载 ⇒ 每孔一个 `Rect` 分配 = 逐帧分配）。
     * ⛔ 本函数不新建任何对象。命名带 `draw` 前缀的理由同 [drawRibbonNative]。
     *
     * @param wf 浪带宽基数 `W0`（px）。
     * @param ka 该条带的合成 alpha（已乘 [SHORE_FOAM_BOOST]）。
     */
    private fun DrawScope.drawPunchHoles(
        lane: Int,
        strip: Int,
        n0: Float,
        n1: Float,
        wf: Float,
        ka: Double,
        t: Double,
        dir: Int,
    ) {
        val gap = (n1 - n0).toDouble()
        // ⛔ 太窄的条带挖不动会破形
        if (gap < HOLE_GAP_MIN) return
        val cols = SeasideWaves.COLS
        val cnt = (HOLE_MAX * ka *
            SeasideWaves.clamp(HOLE_COUNT_A - gap * HOLE_COUNT_GAP, 0.0, 1.0) + 0.5).toInt()
        val wpx = size.width
        var h = 0
        while (h < cnt) {
            val ph = SeasideWaves.hash2(h, 700 + strip * HOLE_PH_STRIP + lane * HOLE_PH_LANE)
            // ⛔ 红线 1：连续相位（⛔ 不取整、⛔ 不换种子）
            var u = (t / HOLE_PERIOD_MS + ph) % 1.0
            if (u < 0.0) u += 1.0
            // ⛔ 红线 2：横漂用 clamp 的有界振荡（⛔ 不得 `% 1`）
            val hx = SeasideWaves.clamp(
                ph + HOLE_HX_JIT * sin(t * HOLE_HX_T + ph * TAU), HOLE_HX_CLAMP_LO, HOLE_HX_CLAMP_HI
            )
            val grow = sin(Math.PI * u)
            val hn = n0 + (gap * (HOLE_HN_LO + HOLE_HN_SPAN * u)).toFloat()
            val hr = gap * (HOLE_HR_LO + HOLE_HR_SPAN * grow) *
                (HOLE_HR_K_LO + HOLE_HR_K_SPAN *
                    SeasideWaves.hash2(h, 720 + strip * HOLE_HR_STRIP + lane * HOLE_HR_LANE))
            var col = (hx * cols + 0.5).toInt()
            if (col < 0) col = 0 else if (col > cols) col = cols
            val cy = bfy[col] + dir * hn * bwj[col] * wf
            val ry = (hr * bwj[col] * wf).toFloat()
            val rx = (ry * (HOLE_RX_LO + HOLE_RX_SPAN *
                SeasideWaves.hash2(h, 730 + strip * HOLE_RX_STRIP + lane * HOLE_RX_LANE))).toFloat()
            // ⛔ 亚像素孔直接丢弃（省开销也避免脏边）
            if (ry >= HOLE_RY_MIN && rx >= HOLE_RX_MIN) {
                val cx = (hx * wpx).toFloat()
                foamStripNativePath.addOval(
                    cx - rx, cy - ry, cx + rx, cy + ry, NativePath.Direction.CW
                )
            }
            h++
        }
    }

    /**
     * [SeaOpItem.SEA_FOAM_WASH] —— 波面大白沫晕。**逐浪 [WASH_SEGMENTS] 次**；⛔ **LOW 跳过**。
     *
     * ## ⭐ 逐段 alpha（原型 `seaside-preview.html:2765-2809`）
     * 原型把浪脊沿岸分 `G = 8` 段，**每段用它自己那段列的平均 `slopeLaw·farLaw`**：
     * ```
     * const A = SEA_FOAM_WASH_A * clamp(kA, 0, 1) * (0.06 + 0.94 * fk);
     * ```
     * 原型注释写明「一整条一个 alpha 就没有远近差别（实测近岸/远处饱和度只差 0.03）」。
     * ⛔ 早先这里取的是**全列均值 + 一次 fill** ⇒ 整条晕同一个 alpha ⇒ 真机读成
     * **一根平直的白色条刷纹路**（这正是「泡沫全是条刷纹路」的一条直接成因）。
     * ⇒ 现按 [WASH_SEGMENTS] 段分提交，各段自己的渐变 span + 自己的 alpha。
     * ⚠️ 段与段**共用 `smoothRun` 的曲线**（原型 2803-2804 的 `smoothRunRange`），
     * 所以相邻段之间不会出现竖直接缝。
     * ⚠️ **提交数**：预算表 [SeaOpItem.SEA_FOAM_WASH] 的 `opsHigh = 1`，本函数现在
     * 是 8 次 ⇒ **每浪 +7 提交**（⛔ 按指令不改门限，差额登记在交付报告里）。
     *
     * @param wv 该浪。
     * @param ka 该浪的合成泡沫强度（≥ [KA_FOAM_MIN] 才会被调）。
     */
    private fun DrawScope.drawSeaFoamWash(w0: Double, ka: Double, dir: Int) {
        if (opsOf(SeaOpItem.SEA_FOAM_WASH) <= 0) return
        val n = waves?.column_count ?: return
        val wf = w0.toFloat()
        // ⛔ 尾迹长度用**固定**的 W0 倍数（原型 2753-2756 的原话）：bwj[i] 逐列变化时
        //   yBot 就跟着变，而渐变锚在全幅范围上 ⇒ 外沿会出现一道「撕纸」一样的硬边。
        val front = (dir * (if (dir > 0) SEA_FOAM_WASH_FRONT_NL else SEA_FOAM_WASH_FRONT) * wf).toFloat()
        val back = (-dir * SEA_FOAM_WASH_BACK * wf).toFloat()
        var any = false
        var i = 0
        while (i < n) {
            washA[i] = bfy[i] + front
            washB[i] = bfy[i] + back
            if (bwj[i] > 0.35f) any = true
            i++
        }
        if (!any) return
        val paint = seaFoamWashNativePaints[if (dir > 0) 1 else 0]
        val shader = paint.shader ?: return
        val kaC = SeasideWaves.clamp(ka, 0.0, 1.0)
        // ⭐ 逐段（原型 `G = 8`）：段内自己求 `yT/yB` 与**段内**平均 `fk`，各段一次 fill。
        //   ⛔ 不再取全列均值 —— 那正是「平直白条刷」的成因（见本函数 KDoc）。
        val per = (n + WASH_SEGMENTS - 1) / WASH_SEGMENTS
        var g = 0
        while (g < WASH_SEGMENTS) {
            val i0 = g * per
            val i1 = if (i0 + per < n) i0 + per else n
            if (i1 - i0 >= 2) {
                var yT = Float.MAX_VALUE
                var yB = -Float.MAX_VALUE
                var fk = 0.0
                var j = i0
                while (j < i1) {
                    if (washA[j] < yT) yT = washA[j]
                    if (washB[j] > yB) yB = washB[j]
                    fk += slopeLaw[j] * farLaw[j]
                    j++
                }
                val a = SEA_FOAM_WASH_A * kaC *
                    (WASH_FK_LO + WASH_FK_SPAN * (fk / (i1 - i0).toDouble()))
                val span = (yB - yT).toDouble()
                if (a >= WASH_A_MIN && span >= WASH_SPAN_MIN) {
                    seaFoamWashNativePath.rewind()
                    drawRibbonRangeNative(seaFoamWashNativePath, i0, i1, true)
                    drawRibbonRangeNative(seaFoamWashNativePath, i0, i1, false)
                    seaFoamWashNativePath.close()
                    gradMatrix.setScale(1f, span.toFloat())
                    gradMatrix.postTranslate(0f, yT)
                    shader.setLocalMatrix(gradMatrix)
                    paint.alpha = alpha255(a)
                    drawContext.canvas.nativeCanvas.drawPath(seaFoamWashNativePath, paint)
                }
            }
            g++
        }
    }

    /**
     * [SeaOpItem.OPEN_SEA_FOAM] —— 外海泡沫贴图。
     * **逐浪 [SEA_FOAM_PATCH] 次（HIGH）/ [OPEN_FOAM_TIERS] 次（MEDIUM·LOW）**。
     *
     * ⛔ **不**吸附到列栅格（吸附会得到一排规则花纹）。
     *
     * ## ⭐ 两条路径：**HIGH 走 `drawImage` 贴图**（原型形态），其余档走矢量多边形
     * ⛔ **HIGH 绝不许再走多边形。** 斑块内部的柔和 alpha 剖面（`paintBlob` 的
     * `peak·(1−d²)` 径向衰减、`paintStreak` 的 `(1−s·0.55)(1−d²)`、
     * [FOAM_TILE_FADE_R] 边缘渐隐、边缘乘 `PAL_FOAM_EDGE`）**只存在于贴图像素里** ——
     * 那张贴图此前从未被 draw 过（只造、只回收）。拿 N 边形 + 一支平色去 fill
     * ⇒ **硬边棱角块**（真机实测：灰色棱角多边形）。
     * ⇒ HIGH 逐块 `drawBitmap`，`alpha = min(a, [OPEN_FOAM_A_CAP])`（原型 `:2913` 逐字）。
     * ✅ **尺寸公式本来就是对的**：`sizePx = w0·(2.40 + 2.40·hash2^1.2)`（原型 `:2902` 逐字一致）。
     *    原型注释 `:2899-2901` 明确要求「尺寸必须接近贴图的原生 256px」，早先的
     *    `W0·(1.4..3.7)` 才是错的 ⇒ 实测多边形 100–400px **符合**原型，⛔ 勿再去「修」尺寸。
     *
     * ## ⛔ 职责边界
     * 本函数**只**画**拖尾侧**（`dep = hash·[OPEN_FOAM_DEP_LO..+SPAN]`
     * = `-0.35 … 1.55`）。前缘**前方**的扰动前锋由 [drawDisturbance] 单独画 ——
     * ⛔ **绝不**把两者折进同一次提交（那会让扰动前锋继承拖尾侧的距离律
     * `farLaw^1`，在 `0.42h` 出生深度上整层归零 ⇒ 实测全帧零像素差）。
     *
     * @param wv 该浪。
     * @param ka 该浪的合成泡沫强度（≥ [KA_PATCH_MIN] 才会被调）。
     * @param t 相对首帧的毫秒。
     */
    private fun DrawScope.drawOpenSeaFoam(w0: Double, ka: Double, t: Double, lane: Int, dir: Int) {
        if (opsOf(SeaOpItem.OPEN_SEA_FOAM) <= 0) return
        val wf = w0.toFloat()
        val cols = SeasideWaves.COLS
        val base = ka * SEA_FOAM_A * OPEN_FOAM_LANE_K[lane]
        if (base <= 0.0) return
        // ⭐ HIGH = 原型形态（逐块 `drawImage` 软边白沫）；MEDIUM / LOW = 便宜的多边形
        if (seaLevel == SeaLevel.HIGH) {
            drawFoamTileBlits(
                SEA_FOAM_PATCH, t, w0, wf, cols, base, lane, dir,
                FOAM_TILE_OFF_OPEN, OPEN_FOAM_A_MIN, OPEN_FOAM_A_CAP
            )
            return
        }
        if (foamTileOutN <= 0) return
        val brush = openSeaFoamBrush ?: return
        openSeaFoamPathT[0].reset()
        openSeaFoamPathT[1].reset()
        openSeaFoamPathT[2].reset()
        var cnt = 0
        var k = 0
        while (k < SEA_FOAM_PATCH) {
            val h = k * 17 + lane * 613
            // ⭐ 密度场：0.15..1.0，缓慢漂移 ⇒ 同一片海面过一会儿「碎的地方」会换
            val dens = SeasideWaves.fbm_norm(
                (k * OPEN_FOAM_DENS_K + lane * OPEN_FOAM_DENS_L) * OPEN_FOAM_DENS_F +
                    t * OPEN_FOAM_DENS_T, 7701 + lane * 37, 2
            )
            // ⭐ 位置**不是均匀随机**：先用低频场把贴图聚成几团（被撕碎的浪列），再叠一点点抖动
            val p = SeasideWaves.clamp(
                0.5 + 0.46 * SeasideWaves.fbm_signed(
                    k * OPEN_FOAM_CLUMP_K + lane * OPEN_FOAM_CLUMP_L, 9301, 2
                ) + OPEN_FOAM_JIT * (SeasideWaves.hash2(h, 921) - 0.5) * 2, 0.0, 1.0
            )
            var col = (p * cols + 0.5).toInt()
            if (col < 0) col = 0 else if (col > cols) col = cols
            // —— 拖尾侧（原型 `drawOpenSeaFoam`）——
            val dep = OPEN_FOAM_DEP_LO + OPEN_FOAM_DEP_SPAN * SeasideWaves.hash2(h, 922)
            val sizePx = w0 * (OPEN_FOAM_SIZE_LO + OPEN_FOAM_SIZE_SPAN *
                powD(SeasideWaves.hash2(h, 923), OPEN_FOAM_SIZE_POW))
            val fk = slopeLaw[col] * farLaw[col]
            val a = base * dens * (OPEN_FOAM_A_H_LO + OPEN_FOAM_A_H_SPAN * SeasideWaves.hash2(h, 924)) *
                (OPEN_FOAM_FK_LO + OPEN_FOAM_FK_SPAN * fk)
            if (a >= OPEN_FOAM_A_MIN) {
                val cx = bxs[col]
                val cy = bfy[col] + (dir * dep * bwj[col] * wf).toFloat()
                val szf = sizePx.toFloat()
                val tile = h % SeasideOpBudget.FOAM_TILES
                val st = foamTileOutStart[tile]
                val sub = foamTileOutSub[tile]
                val path = openSeaFoamPathT[tier(a, base)]
                var vp = st
                var si = 0
                while (si < sub) {
                    val nv = if (si < FOAM_TILE_BLOB_N) FOAM_TILE_BLOB_POLY_N else FOAM_TILE_STREAK_POLY_N
                    var q = 0
                    while (q < nv && vp + 1 < foamTileOutN) {
                        val uu = cx + (foamTileOutV[vp] - 0.5f) * szf
                        val vv = cy + (foamTileOutV[vp + 1] - 0.5f) * szf
                        if (q == 0) path.moveTo(uu, vv) else path.lineTo(uu, vv)
                        vp += 2
                        q++
                    }
                    path.close()
                    si++
                }
                cnt++
            }
            k++
        }
        if (cnt <= 0) return
        var ti = 0
        while (ti < OPEN_FOAM_TIERS) {
            drawPath(
                openSeaFoamPathT[ti], brush,
                alpha = (base * (ti + 0.5) / OPEN_FOAM_TIERS).toFloat()
            )
            ti++
        }
    }

    /**
     * ⭐⭐ **HIGH 档的外海泡沫 / 扰动前锋 —— 逐块 `drawBitmap` 软边贴图**（原型形态）。
     *
     * ## 为什么必须回到 `drawImage`
     * 泡沫斑块的柔和剖面（径向 `1−d²` 衰减、丝缕的 `1−s·0.55`、边缘渐隐、
     * 边缘乘 `PAL_FOAM_EDGE`）**只烘在 [foamTiles] 的像素里**。把它折成 N 边形 +
     * 一支平色去 fill ⇒ 硬边棱角块（真机实测「灰色棱角多边形」）。
     * ⇒ 本函数是 HIGH 档唯一的白沫渲染通道，⛔ 不得再被合批折掉。
     *
     * ## ⛔ 零分配
     * - 目标矩形复用 [foamTileDst]（一个 [RectF]，⛔ **不是**带参 `Rect(`）；
     * - 画笔复用 [foamTileBlitPaint]，逐块**只改 `alpha`**；
     * - 块几何（`dens` / `p` / `col` / `dep` / `sizePx` / `fk` / `a`）与原型逐字同式，
     *   ⛔ 但**不缓存**（22 块 × 2 层 × 每帧重算是本来的开销，且缓存数组 = 逐帧内存增长）。
     *
     * ## 两层的差异**只在参数**，本函数共用
     * | | [drawOpenSeaFoam] | [drawDisturbance] |
     * |---|---|---|
     * | 块数 | [SEA_FOAM_PATCH] = 22 | [DISTURB_PATCH] = 18 |
     * | `h` | `k·17 + L·613` | `k·29 + L·977 + 300` |
     * | 贴图下标 | `h % 6` | `(h+2) % 6` |
     * | `dep` | `hash·1.90 − 0.35` | `0.30 + 1.15·hash` |
     * | `sizePx` | `w0·(2.40 + 2.40·hash^1.2)` | `w0·1.35·(1.2 + 1.3·hash^1.3)` |
     * | `fk` | `slopeLaw·farLaw` | `slopeLaw·farLaw^0.35` |
     * | alpha | `base·dens·(0.55+0.60h)·(0.10+0.90fk)` | `base·dens·(0.4+0.6h)·(0.35+0.65fk)` |
     * | 门槛 / 上限 | `0.012` / `0.45` | `0.008` / `0.40` |
     *
     * @param patchCount 块数（[SEA_FOAM_PATCH] 或 [DISTURB_PATCH]）
     * @param kind [FOAM_TILE_OFF_OPEN] 或 [FOAM_TILE_OFF_DISTURB]（决定逐块公式）
     *
     * ⛔ **元素归属**：**无独立 [SeaOpItem]** —— 它是 [drawOpenSeaFoam] 与
     * [drawDisturbance] **共用**的 blit 原语，那 22 + 18 次提交已分别记在
     * [SeaOpItem.OPEN_SEA_FOAM] / [SeaOpItem.DISTURBANCE] 上。
     */
    private fun DrawScope.drawFoamTileBlits(
        patchCount: Int,
        t: Double,
        w0: Double,
        wf: Float,
        cols: Int,
        base: Double,
        lane: Int,
        dir: Int,
        kind: Int,
        aMin: Double,
        aCap: Double,
    ) {
        if (foamTileOutN <= 0) return
        val paint = foamTileBlitPaint
        val dst = foamTileDst
        val canvas = drawContext.canvas.nativeCanvas
        val disturb = kind == FOAM_TILE_OFF_DISTURB
        var k = 0
        while (k < patchCount) {
            val h = if (disturb) {
                k * DISTURB_H_K + lane * DISTURB_H_L + DISTURB_H_B0
            } else {
                k * 17 + lane * 613
            }
            // 密度场：两层的种子 / 频率各自独立（原型 2890 与 2934）
            val dens = if (disturb) {
                SeasideWaves.fbm_norm(
                    (k * DISTURB_DENS_K + lane * DISTURB_DENS_L) * DISTURB_DENS_F +
                        t * DISTURB_DENS_T,
                    DISTURB_DENS_SEED + lane * DISTURB_DENS_SEED_L, 2
                )
            } else {
                SeasideWaves.fbm_norm(
                    (k * OPEN_FOAM_DENS_K + lane * OPEN_FOAM_DENS_L) * OPEN_FOAM_DENS_F +
                        t * OPEN_FOAM_DENS_T, 7701 + lane * 37, 2
                )
            }
            // 位置：低频场聚团 + 小抖动（⛔ 不吸附列栅格之外 anything）
            val p = if (disturb) {
                SeasideWaves.clamp(
                    0.5 + 0.5 * SeasideWaves.fbm_signed(
                        k * DISTURB_CLUMP_K + lane * DISTURB_CLUMP_L, DISTURB_CLUMP_SEED, 2
                    ) + DISTURB_JIT * (SeasideWaves.hash2(h, 930) - 0.5) * 2, 0.0, 1.0
                )
            } else {
                SeasideWaves.clamp(
                    0.5 + 0.46 * SeasideWaves.fbm_signed(
                        k * OPEN_FOAM_CLUMP_K + lane * OPEN_FOAM_CLUMP_L, 9301, 2
                    ) + OPEN_FOAM_JIT * (SeasideWaves.hash2(h, 921) - 0.5) * 2, 0.0, 1.0
                )
            }
            var col = (p * cols + 0.5).toInt()
            if (col < 0) col = 0 else if (col > cols) col = cols
            val dep: Double
            val sizePx: Double
            val fk: Double
            val a: Double
            if (disturb) {
                dep = DISTURB_DEP0 + (DISTURB_DEP1 - DISTURB_DEP0) * SeasideWaves.hash2(h, 931)
                sizePx = w0 * DISTURB_SIZE * (DISTURB_SIZE_LO + DISTURB_SIZE_SPAN *
                    powD(SeasideWaves.hash2(h, 932), DISTURB_SIZE_POW))
                fk = slopeLaw[col] * powD(farLaw[col].toDouble(), DISTURB_FAR_POW)
                a = base * dens * (DISTURB_A_H_LO + DISTURB_A_H_SPAN * SeasideWaves.hash2(h, 933)) *
                    (DISTURB_FK_LO + DISTURB_FK_SPAN * fk)
            } else {
                dep = OPEN_FOAM_DEP_LO + OPEN_FOAM_DEP_SPAN * SeasideWaves.hash2(h, 922)
                sizePx = w0 * (OPEN_FOAM_SIZE_LO + OPEN_FOAM_SIZE_SPAN *
                    powD(SeasideWaves.hash2(h, 923), OPEN_FOAM_SIZE_POW))
                fk = (slopeLaw[col] * farLaw[col]).toDouble()
                a = base * dens * (OPEN_FOAM_A_H_LO + OPEN_FOAM_A_H_SPAN * SeasideWaves.hash2(h, 924)) *
                    (OPEN_FOAM_FK_LO + OPEN_FOAM_FK_SPAN * fk)
            }
            if (a >= aMin) {
                val tile = foamTiles[(if (disturb) h + 2 else h) % SeasideOpBudget.FOAM_TILES]
                if (tile != null) {
                    val cx = bxs[col]
                    val cy = bfy[col] + (dir * dep * bwj[col] * wf).toFloat()
                    val half = (sizePx * 0.5).toFloat()
                    dst.set(cx - half, cy - half, cx + half, cy + half)
                    paint.alpha = alpha255(if (a < aCap) a else aCap)
                    canvas.drawBitmap(tile.asAndroidBitmap(), null, dst, paint)
                }
            }
            k++
        }
    }

    /**
     * 逐块 alpha 落到哪一档（`[OPEN_FOAM_TIERS]` 等分 `a / base`，⛔ 零分配）。
     *
     * `a / base = dens·(0.55 + 0.60·hash)·(0.10 + 0.90·fk)` ∈ `[0.008, 1.15]`
     * ⇒ 钳到 `[0, 1]` 后等分三档即可保住 `dens` / `hash` / `fk` 三层差异。
     */
    private fun tier(a: Double, base: Double): Int {
        var q = a / base
        if (q < 0.0) q = 0.0 else if (q > 1.0) q = 1.0
        val tier = (q * OPEN_FOAM_TIERS).toInt()
        return if (tier >= OPEN_FOAM_TIERS) OPEN_FOAM_TIERS - 1 else tier
    }

    /**
     * [SeaOpItem.DISTURBANCE] —— 扰动前锋：**浪脊前方**、刚被搅起的碎沫白水。
     * **逐浪 [DISTURB_PATCH] 次（HIGH）/ 1 次（其余在册档）**；⛔ **LOW 跳过**。
     *
     * ## 为什么必须单独成层（⛔ 别再折进 [drawOpenSeaFoam]）
     * 原型是两层独立 blit：拖尾侧用 `farLaw^1`，扰动前锋用 `farLaw^[DISTURB_FAR_POW]`
     * （原型注释：「不能用贴岸那条 `FOAM_FAR_POW=2.4` —— 那条把 `0.42h` 出生深度的浪压到
     * `farLaw≈0.011`，前置扰动整条都是 0」）。两者折进同一次提交就只剩一个 alpha
     * 与一条距离律 ⇒ 扰动前锋被拖尾侧的强衰减吃掉 ⇒ **实测全帧零像素差**。
     *
     * ## ⭐ HIGH 走 `drawImage`（原型形态），MEDIUM 保留多边形
     * ⛔ **HIGH 绝不许走多边形** —— 理由与 [drawOpenSeaFoam] 完全相同：柔和剖面只在
     * 贴图像素里。⇒ HIGH = [DISTURB_PATCH] 次 `drawBitmap`（`kind = [FOAM_TILE_OFF_DISTURB]`）。
     * ⛔ MEDIUM 仍用 u-v 预烘轮廓合成**一条 path 一次 fill**（`opsMed = 0`，本层不画）。
     *
     * @param w0 该浪的带宽（`h·SWELL_BAND_W·(0.62 + 0.62·amp)·0.92`）。
     * @param ka 该浪的合成泡沫强度 —— ⛔ 原型传的是 **`kA`（⛔ 不含 `SHORE_FOAM_BOOST`）**。
     * @param lane 该浪的泳道（`1 + serial % 3`，⛔ 恒 `!= 0`）。
     */
    private fun DrawScope.drawDisturbance(w0: Double, ka: Double, t: Double, lane: Int, dir: Int) {
        if (opsOf(SeaOpItem.DISTURBANCE) <= 0) return
        val wf = w0.toFloat()
        val cols = SeasideWaves.COLS
        val base = ka * DISTURB_A * DISTURB_LANE_K[lane]
        if (base <= 0.0) return
        if (seaLevel == SeaLevel.HIGH) {
            drawFoamTileBlits(
                DISTURB_PATCH, t, w0, wf, cols, base, lane, dir,
                FOAM_TILE_OFF_DISTURB, DISTURB_A_MIN, DISTURB_A_CAP
            )
            return
        }
        if (foamTileOutN <= 0) return
        val brush = openSeaFoamBrush ?: return
        disturbancePath.reset()
        var aSum = 0.0
        var aCnt = 0
        var k = 0
        while (k < DISTURB_PATCH) {
            val h = k * DISTURB_H_K + lane * DISTURB_H_L + DISTURB_H_B0
            // ⭐ 密度场：与拖尾贴图**同源但独立**的慢漂移场 ⇒ 碎沫团会缓慢移动
            val dens = SeasideWaves.fbm_norm(
                (k * DISTURB_DENS_K + lane * DISTURB_DENS_L) * DISTURB_DENS_F +
                    t * DISTURB_DENS_T, DISTURB_DENS_SEED + lane * DISTURB_DENS_SEED_L, 2
            )
            // ⭐ 位置：低频场聚团 + 小抖动（与拖尾贴图同一套语言，⛔ 但种子不同）
            val p = SeasideWaves.clamp(
                0.5 + 0.5 * SeasideWaves.fbm_signed(
                    k * DISTURB_CLUMP_K + lane * DISTURB_CLUMP_L, DISTURB_CLUMP_SEED, 2
                ) + DISTURB_JIT * (SeasideWaves.hash2(h, 930) - 0.5) * 2, 0.0, 1.0
            )
            var col = (p * cols + 0.5).toInt()
            if (col < 0) col = 0 else if (col > cols) col = cols
            // ⭐ 关键：贴图放在浪脊**前方**（`dir` 即前缘方向），而非拖尾侧
            val dep = DISTURB_DEP0 + (DISTURB_DEP1 - DISTURB_DEP0) * SeasideWaves.hash2(h, 931)
            val sizePx = w0 * DISTURB_SIZE * (DISTURB_SIZE_LO + DISTURB_SIZE_SPAN *
                powD(SeasideWaves.hash2(h, 932), DISTURB_SIZE_POW))
            // ⛔ 距离律用 `[DISTURB_FAR_POW]`（0.35）而⛔ **不是** 1：更缓，保留中海可见碎沫
            val fk = slopeLaw[col] * powD(farLaw[col].toDouble(), DISTURB_FAR_POW)
            val a = base * dens * (DISTURB_A_H_LO + DISTURB_A_H_SPAN * SeasideWaves.hash2(h, 933)) *
                (DISTURB_FK_LO + DISTURB_FK_SPAN * fk)
            if (a >= DISTURB_A_MIN) {
                val cx = bxs[col]
                val cy = bfy[col] + (dir * dep * bwj[col] * wf).toFloat()
                val szf = sizePx.toFloat()
                val tile = (h + 2) % SeasideOpBudget.FOAM_TILES
                val st = foamTileOutStart[tile]
                val sub = foamTileOutSub[tile]
                var vp = st
                var si = 0
                while (si < sub) {
                    val nv = if (si < FOAM_TILE_BLOB_N) FOAM_TILE_BLOB_POLY_N else FOAM_TILE_STREAK_POLY_N
                    var q = 0
                    while (q < nv && vp + 1 < foamTileOutN) {
                        val uu = cx + (foamTileOutV[vp] - 0.5f) * szf
                        val vv = cy + (foamTileOutV[vp + 1] - 0.5f) * szf
                        if (q == 0) disturbancePath.moveTo(uu, vv) else disturbancePath.lineTo(uu, vv)
                        vp += 2
                        q++
                    }
                    disturbancePath.close()
                    si++
                }
                aSum += if (a < DISTURB_A_CAP) a else DISTURB_A_CAP
                aCnt++
            }
            k++
        }
        if (aCnt <= 0) return
        // ⚠️ **已记录的折叠**：原型是 18 次 `drawImage`，每块各有自己的 alpha；折成一次
        //   `fill` 后逐块 alpha 拿不到 ⇒ 取参与块的**算术平均**（同 [drawOpenSeaFoam]）。
        drawPath(disturbancePath, brush, alpha = (aSum / aCnt).toFloat())
    }

    /**
     * [SeaOpItem.FOAM_LACE] —— 泡沫蕾丝网。**逐浪**，提交数 = [SeasideOpBudget.laceStrokeOps]
     * （HIGH 12 / MEDIUM 3 / LOW 0）。
     *
     * ⛔ `wj` 量化 ⇒ 缓存 ≤ [SeasideOpBudget.laceStrokeCacheSize] 个 `Stroke`
     * （[buildLaceStrokes]）；⛔ **本函数体内不得出现 `Stroke(`**。
     * ⛔ **保留「端点共享」** —— 那才是它读作泡沫的原因，不是形状。
     *
     * @param wv 该浪（`lane` 场已填好；蕾丝拓扑由 `buildLaceNet` 烘，⛔ 不逐帧重算）。
     * @param ka 该浪的合成泡沫强度。
     */
    private fun DrawScope.drawFoamLace(
        w0: Double, ka: Double, t: Double, lane: Int, isLead: Boolean, dir: Int,
    ) {
        val n = opsOf(SeaOpItem.FOAM_LACE)
        if (n <= 0) return
        if (ka < KA_FOAM_MIN) return
        val arr = laceStrokes ?: return
        val brush = laceBrush ?: return
        val buckets = if (n < arr.size) n else arr.size
        // ⭐ 成熟度 dens：领头浪最「成熟」；外侧按 `lane` 直算（⛔ 不恢复成「下标 0/1/2/3」）
        val dens = if (isLead) LACE_DENS_LEAD
        else LACE_DENS_LO + LACE_DENS_SPAN * (1.0 - lane / LACE_DENS_LANE_N)
        val aBase = SeasideWaves.clamp(ka * LACE_A_KA, 0.0, 1.0) * dens * LACE_A_K
        val wf = w0.toFloat()
        val w = size.width
        val cols = SeasideWaves.COLS
        var b = 0
        while (b < buckets) {
            val stroke = arr[b]
            if (stroke == null) {
                b++
                continue
            }
            val kind = b / LACE_BAND_N
            val band = b % LACE_BAND_N
            val alpha = SeasideWaves.clamp(
                aBase * LACE_BAND_A[band] * LACE_KIND_A[kind], 0.0, LACE_A_CAP
            )
            if (alpha >= LACE_A_MIN) {
                lacePath.reset()
                var e = 0
                while (e < laceEdgeCount) {
                    if (laceEdgeKind[e] == kind && laceEdgeQ[e] == band) {
                        val dv = (LACE_DV_A * sin(
                            t * LACE_DV_T + laceEdgePh[e] + lane * LACE_DV_LANE
                        )).toFloat()
                        val va = SeasideWaves.clamp(
                            (laceEdgeV0[e] + dv).toDouble(), LACE_V_CLAMP_LO, LACE_V_CLAMP_HI
                        )
                        val vb = SeasideWaves.clamp(
                            (laceEdgeV1[e] + dv).toDouble(), LACE_V_CLAMP_LO, LACE_V_CLAMP_HI
                        )
                        var ca = (laceEdgeU0[e] * cols + 0.5f).toInt()
                        if (ca < 0) ca = 0 else if (ca > cols) ca = cols
                        var cb = (laceEdgeU1[e] * cols + 0.5f).toInt()
                        if (cb < 0) cb = 0 else if (cb > cols) cb = cols
                        val xa = laceEdgeU0[e] * w
                        val xb = (laceEdgeU1[e] + laceEdgeXs[e]) * w
                        val ya = bfy[ca] + dir * va.toFloat() * bwj[ca] * wf
                        val yb = bfy[cb] + dir * vb.toFloat() * bwj[cb] * wf
                        val ddx = xb - xa
                        val ddy = yb - ya
                        val lenPx = sqrt((ddx * ddx + ddy * ddy).toDouble())
                        if (lenPx >= LACE_LEN_MIN) {
                            val nx = (-ddy / lenPx).toFloat()
                            val ny = (ddx / lenPx).toFloat()
                            var amp = (laceEdgeAmp[e] * LACE_WOB_K).toFloat()
                            val lim = (lenPx * LACE_WOB_LEN).toFloat()
                            if (lim < amp) amp = lim
                            val wMax = LACE_WOB_MAX.toFloat()
                            if (wMax < amp) amp = wMax
                            var steps = (lenPx / LACE_STEP_PX + 0.5).toInt()
                            if (steps > LACE_STEP_MAX) steps = LACE_STEP_MAX
                            if (steps < 1) steps = 1
                            lacePath.moveTo(xa, ya)
                            var q = 1
                            while (q < steps) {
                                val f = q.toDouble() / steps.toDouble()
                                // ⭐ 内部点叠正弦，但 sin(0) = sin(π) = 0 ⇒ **端点钉死**，网不散
                                val wob = amp * sin(Math.PI * f) *
                                    sin(f * laceEdgeF1[e] * TAU + laceEdgePh[e] + t * laceEdgeRate[e])
                                lacePath.lineTo(
                                    xa + ddx * f.toFloat() + nx * wob.toFloat(),
                                    ya + ddy * f.toFloat() + ny * wob.toFloat()
                                )
                                q++
                            }
                            lacePath.lineTo(xb, yb)
                        }
                    }
                    e++
                }
                // ⚠️ **已记录的折叠**：原型逐边一个 `globalAlpha`（含 `ed.aj` 的 0.80..1.00
                //   抖动）；折成「每 (kind × band) 一支缓存 Stroke」后取该档的定值 alpha。
                drawPath(lacePath, brush, alpha = alpha.toFloat(), style = stroke)
            }
            b++
        }
    }

    /**
     * [SeaOpItem.CREST_LIP] —— 锐利的破碎唇（窄高光 + 紧贴的暗带）。**逐浪**，
     * ⛔ **只对非领头浪**（那道门由 [drawSwellBands] 承担）。
     *
     * ## ⭐ 两个 pass 都恢复（原型 §5.3：`LIP_W·0.35` / `LIP_W·0.95`，线宽 `0.9 / 2.5`，
     * alpha `0.85 / 0.42`，色 `#ffffff` / `#cfe6ea`）
     * | pass | 偏移 | 线宽 | alpha | 色 | 档位 |
     * |---|---|---|---|---|---|
     * | 0 窄高光 | `LIP_W·[LIP_OFF_HI]` | `[LIP_W_LO]` | `[LIP_A_HI]` | [PAL_FOAM_LIP]（白） | **HIGH + MEDIUM** |
     * | 1 暗带 | `LIP_W·[LIP_OFF_LO]` | `[LIP_W_LO]+[LIP_W_SPAN]` | `[LIP_A_LO]` | [PAL_LIP_DARK] | **仅 HIGH** |
     *
     * ⛔ **折成一次提交时只剩 pass 0**（`ops = 0/1/1`）—— 那是「一道白线 + 一条更宽的暗带」
     *   被迫二选一；两 pass 的**线宽**（0.9 vs 2.5）与**色**都不同，一次提交拿不到。
     *   现 HIGH 恢复 2 次（= 预算表 `opsHigh = 2`）；MEDIUM 保留 pass 0。
     * ⛔ **同一条 [crestLipPts] 缓冲逐 pass 重填**，⛔ 不开第二条缓冲（两次提交 = 同一批线段
     *   画两遍，偏移 0.6 个带宽、线宽与色不同 ⇒ 读作「亮线压在暗带上」）。
     *
     * @param wv 该浪（⛔ 已保证 `!wv.is_beach`）。
     * @param ka 该浪的合成泡沫强度（原型 `kA·boost·farMean`，唇必须吃距离律）。
     */
    private fun DrawScope.drawCrestLip(w0: Double, ka: Double, dir: Int) {
        val passes = opsOf(SeaOpItem.CREST_LIP)
        if (passes <= 0) return
        val n = waves?.column_count ?: return
        val alpha = SeasideWaves.clamp(ka, 0.0, 1.0)
        if (alpha < LACE_A_MIN) return
        val den = if (density > 0f) density else 1f
        val wf = w0.toFloat()
        var pass = 0
        while (pass < passes) {
            // ⛔ pass 0 是**亮高光**（贴前缘、细、白）；pass 1 是**暗带**（更靠里、更宽、冷色）
            val off = if (pass == 0) {
                (LIP_W * LIP_OFF_HI).toFloat()
            } else {
                (LIP_W * LIP_OFF_LO).toFloat()
            }
            var i = 0
            while (i < n - 1) {
                val ya = bfy[i] + dir * off * bwj[i] * wf
                val yb = bfy[i + 1] + dir * off * bwj[i + 1] * wf
                crestLipPts[i * 4] = bxs[i]
                crestLipPts[i * 4 + 1] = ya
                crestLipPts[i * 4 + 2] = bxs[i + 1]
                crestLipPts[i * 4 + 3] = yb
                i++
            }
            if (pass == 0) {
                crestLipPaint.color = PAL_FOAM_LIP
                crestLipPaint.strokeWidth = LIP_W_LO * den
                crestLipPaint.alpha = alpha255(LIP_A_HI * alpha)
            } else {
                crestLipPaint.color = PAL_LIP_DARK
                crestLipPaint.strokeWidth = (LIP_W_LO + LIP_W_SPAN) * den
                crestLipPaint.alpha = alpha255(LIP_A_LO * alpha)
            }
            drawLinesBatch(crestLipPts, 0, (n - 1) * 4, crestLipPaint)
            pass++
        }
    }

    // ── ⑦ 贴岸泡沫与装饰 ────────────────────────────────────────────────────

    /**
     * [SeaOpItem.SWASH_FINGER] —— 浪花手指。**恒 1 次** `drawLines`（30 根 → 1 次，§4.9.2）。
     *
     * 门控（§4.7①）：`vigor < VIGOR_FINGER_MIN`（干燥期 `vigor = VIGOR_DRY`）⇒ 跳过。
     * ⛔ 位置按 hash 大幅抖动 + 只有约 45% 真的伸出去 —— 等距栅格会读成装饰花边。
     *
     * ## ⭐ `wide` / `lean` 必须成对（真机实测「沙滩全是划痕」的成因之一）
     * 原型 `seaside-preview.html:2419-2421`：
     * ```
     * const wide = W * (0.006 + 0.026 * Math.pow(hash2(h, 603), 1.6));
     * const lean = (hash2(h, 605) - 0.5) * wide * 0.9;      // ⛔ 乘的是 wide，不是 W
     * ```
     * ⛔ 早先这里写的是 `(hash2(hs, 605) - 0.5) * w * FINGER_LEAN` —— **漏了 `wide`**，
     * 于是 `lean` 最大 ±`0.45·W`（1600px 宽 ⇒ ±720px），而原型最大 ±`0.45·wide`
     * （`wide ∈ 0.006W..0.032W` ⇒ ±4px..±23px）⇒ **放大了 30 倍以上**。
     * 后果：一根手指被画成**横贯大半屏、长 700px 的 2px 细划痕**，
     * 而原型是一枚 `wide` 宽、`≤0.032h` 长的**填充舌头** ⇒ 满屏白划痕而非水线花边。
     * ⛔ `drawLines` 只有一支笔 ⇒ 笔宽取**参与指的 `wide` 均值**（下限 [FINGER_W]）。
     *
     * @param t 相对首帧的毫秒。
     */
    private fun DrawScope.drawSwashFingers(t: Double) {
        val wv = waves ?: return
        val vigor = vigorOf(wv)
        if (vigor < VIGOR_FINGER_MIN) return
        val n = wv.column_count
        val w = size.width.toDouble()
        val h = size.height.toDouble()
        val lenK = audio.fingerLength()
        // ⛔ `drawLines` **没有逐段剔除** ⇒ 被跳过的手指必须从缓冲里**压掉**，
        //   否则会留下上一帧 / 初始的 (0,0) 残点，在画面左上角画出一串点。
        var k = 0
        var wSum = 0.0
        var wCnt = 0
        var f = 0
        while (f < FINGER_MAX) {
            // 原型遍历 `tier = 0,1,2` × `i = tier, tier+3, …` ⇒ 30 个 `(i, tier)` 组合恰好
            // 覆盖 0..29，故 `i === f`、`tier = f % 3`，hash 种子照抄原型的 `h = i·3 + tier`。
            val tier = f % 3
            val hs = f * 3 + tier
            // ⛔ 位置：等距栅格 + 大幅 hash 抖动 ⇒ 不再是等距扇贝
            val p = (f + 0.5) / FINGER_MAX + FINGER_JIT * (SeasideWaves.hash2(hs, 601) - 0.5)
            if (p >= -0.02 && p <= 1.02) {
                // ⛔ 只有约 45% 的位置真的伸出去，其余几乎贴着水线 ⇒ 参差
                val reach = powD(SeasideWaves.hash2(hs, 604), FINGER_REACH_POW)
                if (reach >= FINGER_REACH_MIN) {
                    var ci = (p * SeasideWaves.COLS + 0.5).toInt()
                    if (ci < 0) ci = 0 else if (ci > n - 1) ci = n - 1
                    val y0 = (wv.shore_ys[ci] - h * FINGER_BASE_UP).toFloat()
                    val len = (h * (FINGER_LEN_LO + FINGER_LEN_SPAN * reach) * lenK * vigor).toFloat()
                    // ⭐ 原型 2419-2421：`lean` 乘的是**逐指的 `wide`**，⛔ 不是 `W`
                    val wide = w * (FINGER_WIDE_LO +
                        FINGER_WIDE_SPAN * powD(SeasideWaves.hash2(hs, 603), FINGER_WIDE_POW))
                    val lean = ((SeasideWaves.hash2(hs, 605) - 0.5) * wide * FINGER_LEAN).toFloat()
                    fingerPts[k * 4] = (w * p).toFloat()
                    fingerPts[k * 4 + 1] = y0
                    fingerPts[k * 4 + 2] = (w * p + lean).toFloat()
                    fingerPts[k * 4 + 3] = y0 + len
                    wSum += wide
                    wCnt++
                    k++
                }
            }
            f++
        }
        if (k <= 0) return
        val den = if (density > 0f) density else 1f
        // ⛔ 原型是**逐指宽度**的填充舌头；`drawLines` 只有一支笔 ⇒ 取参与指的均值
        linePaint.color = PAL_FOAM_EDGE
        linePaint.strokeWidth = maxOf(FINGER_W, (wSum / wCnt).toFloat()) * den
        // ⚠️ **已记录的折叠**：原型是 3 个 tier 各一次 `fill`（alpha 0.15 / 0.108 / 0.066）；
        //   `SeaOpItem.SWASH_FINGER.ops* = 1` ⇒ 一次提交只有一支画笔 ⇒ 30 根全部取第 0 档。
        linePaint.alpha = alpha255(FINGER_A0 * vigor * audio.fingerAlpha())
        drawLinesBatch(fingerPts, 0, k * 4, linePaint)
    }

/**
     * [SeaOpItem.CRAB] —— 沙滩小螃蟹。**8 次提交（HIGH）/ 4 次（MEDIUM）/ 2 次（LOW）**。
     *
     * - ⛔ **纯装饰**：⛔ 不参与任何模拟量、⛔ 不影响浪 / 冲流 / 湿沙。
     * - ⛔ 绘制顺序在**沙面与其覆盖层之后、`drawSwellBands` 与贴岸泡沫之前**（§14.3.19）
     *   ⇒ 浪真的能把它淹掉。
     * - ⛔ 贴岸行走：每帧从 `shoreYs[]` 求「离岸线至少 `CRAB_CLEAR`」作为地面。
     * - ⛔ `crabSlot` 走确定性 hash（⛔ 禁 `Math.random` / 禁自建 `rng`，否则无法回放验证）。
     *
     * ## ⭐ 8 批 = 8 次 `nativeCanvas.drawPath`，原型 16 次逐件提交**合批**而成
     * 原型是「每件几何一次 `fill`/`stroke`」，共 **16** 次提交（影子 1 + 远腿 1 + 近腿 1 +
     * 壳填 1 + 壳沿 1 + 高光 1 + 螯臂 1 + 螯填 2 + 螯沿 2 + 眼柄 1 + 眼点 2）。
     * 提交数**只**由「`style`+`color`+`strokeWidth` 的取值分组数」决定 ⇒ 合批手段只有
     * 三种：**同一条 `Path` 里放多段**、**一支 `Paint` 上把同组几何一次画完**、
     * **同一次调用里多点**。⇒ 8 批与它们的分组一一对应：
     * ```
     * ① 影子    crabShadow   FILL            α = vis·0.13   （原型 2214-2218）
     * ② 远侧腿  crabLegFar   STROKE 0.21R    真 quad       （原型 2229-2251）
     * ③ 近侧腿  crabLeg      STROKE 0.26R    真 quad + 螯臂折线
     * ④ 壳填充  crabShell    FILL            addOval(1.06R, 0.76R)（原型 2257-2260）
     * ⑤ 螯填充  crabClaw     FILL            双钳体        （原型 2287-2308）
     * ⑥ 壳沿    crabRim      STROKE 0.13R    壳轮廓 + 双钳轮廓 + 双眼柄
     * ⑦ 背光    crabShellHi  STROKE 0.18R    弧 3.55→5.05  （原型 2265-2269）
     * ⑧ 眼点    crabRim      FILL            双 addCircle(0.24R)（原型 2318-2324）
     * ```
     * **7 项调色板全部可达**，⛔ 每一项都有专属的一层。
     *
     * ⚠️ **为了压进 8 次提交，有三处原型分组被并进相邻批次**（视觉影响逐条可核，已写入
     *   交付报告；⛔ 未获裁决前**不得**宣称「原型逐位一致」）：
     * 1. **螯臂并入③**：线宽取 `0.26R`（原型 `0.24R`，+8%），且画在壳**之下** ⇒ 臂根最里侧
     *    约 6% 被壳填充盖住（原型那条臂只有起点附近约 6% 的长度落在壳椭圆内 ⇒ 等价）。
     * 2. **螯沿并入⑥**：线宽取 `0.13R`（原型 `0.15R`，−13%）。钳体整体在壳椭圆**之外**
     *    （最近处仍差 ≈`0.19R`）⇒ 与壳沿合并不会互相吃掉。
     * 3. **眼柄并入⑥**：颜色取 `crabRim`（原型 `crabLeg`，两者只差一档亮度）、
     *    线宽取 `0.13R`（原型 `0.14R`）。眼点本来就是 `crabRim`，柄与珠同色更像「柄顶着珠」。
     *
     * ## 画质分档（与 `SeaOpItem.CRAB` 的 `opsLow/opsMed/opsHigh` 一一对应）
     * - **HIGH 8**：①…⑧ 全画。
     * - **MEDIUM 4**：省 ①影子 / ⑤螯 / ⑦背光 / ⑧眼 ⇒ 只留 ②③④⑥（两条腿 + 壳 + 壳沿）。
     *   ⛔ 没了螯就不画螯臂（原型自己写明「没它的话螯像两块崩在壳边上的碎块」；反过来，
     *   孤零零一条臂没有意义）；没了眼点就不画眼柄。
     * - **LOW 2**（⛔ **因「原型保真回补」由 1 → 2**，所有者裁决）：**④ 壳填充 + ⑥ 壳沿**
     *   —— 与 HIGH 的第 ④⑥ 批**完全同形**（⑥ 在 LOW 下**只**含壳轮廓，⛔ 不含螯沿 / 眼柄）。
     *   先前 LOW 只画 ④，是**单色**实心椭圆剪影：原生 `Paint` 的**一次**提交拿不到第二种
     *   颜色（壳沿若并进同一次填充，NonZero 下子轮廓被并入填充区 ⇒ 像素集逐像素相同），
     *   12× 截图上它读作「一粒橙点」而不是螃蟹。
     *   ⛔ LOW **仍不画**影子 / 两条腿 / 螯 / 眼：1× 下四条步足的髋距（`0.24R`）与线宽
     *   （`0.21R`/`0.26R`）必然糊成一片 —— 这是原型同款几何在 LOW 档的**固有**结果，
     *   ⛔ 不是可以再优化掉的偏差。
     *
     * @param t 相对首帧的毫秒。
     * @param dtSec 本帧的**秒**；⛔ 暂停时为 `0` ⇒ [crabCy] 的低通系数为 0 ⇒ 随画面冻结。
     */
    private fun DrawScope.drawCrab(t: Double, dtSec: Double) {
        val batches = opsOf(SeaOpItem.CRAB)
        if (batches <= 0) return
        val wv = waves ?: return
        val cols = SeasideWaves.COLS
        val w = size.width.toDouble()
        val h = size.height.toDouble()
        // ── ① 当前是第几只 + 它已经走了多久（ms）。纯函数，同样的 t 必然同样的结果 ──
        var k = 0
        var tk = CRAB_T0_MS
        var gap = crabGapMs(0)
        while (t >= tk + gap) {
            tk += gap
            k++
            gap = crabGapMs(k)
        }
        val age = t - tk
        // ── ② 横向：匀速，起点在画外 ⇒ 它是「走进来」而不是「凭空出现」──────────
        val dirx = if (SeasideWaves.hash2(k, 9217) < 0.5) -1.0 else 1.0
        val spd = CRAB_SPEED * h *
            (CRAB_SPEED_LO + CRAB_SPEED_SPAN * SeasideWaves.hash2(k, 9219))
        val span = CRAB_SPAN * h
        val pad = span * 0.5 + 4.0
        val runMs = (w + pad * 2.0) / spd * 1000.0
        if (age > runMs) return
        val x = (if (dirx > 0) -pad else w + pad) + dirx * spd * age / 1000.0
        val vis = SeasideWaves.smoothstep(0.0, CRAB_FADE_MS, age) *
            (1.0 - SeasideWaves.smoothstep(runMs - CRAB_FADE_MS, runMs, age))
        if (vis < 0.02) return
        // ── ③ 纵向：挂在岸线上（慢），再被水线顶回来（快）⇒ 浪逼上来它就退 ─────
        val base = wv.swash_front_y(x, t)
        val want = base + h * (CRAB_OFF_LO + (CRAB_OFF_HI - CRAB_OFF_LO) *
            powD(SeasideWaves.hash2(k, 9221), CRAB_OFF_POW))
        // ⛔ 硬地板取**身位三列的最大值**，不是所在列 —— 蟹有 30px 宽，只看一列会有一半
        //   身子踩在水里。
        var ci = (x / w * cols.toDouble() + 0.5).toInt()
        if (ci < 0) ci = 0 else if (ci > cols) ci = cols
        var floor = -1e9
        var d = -1
        while (d <= 1) {
            var c = ci + d
            if (c < 0) c = 0 else if (c > cols) c = cols
            val f = wv.shore_ys[c] + CRAB_CLEAR_PX
            if (f > floor) floor = f
            d++
        }
        val goal = if (want > floor) want else floor
        // ⛔ **换蟹瞬间直接落位**；只有同蟹才做一阶平滑（⛔ 帧率无关，不是位置插值）
        if (crabIdx != k) {
            crabIdx = k
            crabCy = goal
        }
        // ⛔ 暂停时 `dtSec = 0` ⇒ 系数为 0 ⇒ 随画面一起冻结
        crabCy += (goal - crabCy) * (1.0 - exp(-dtSec * 1000.0 / CRAB_SMOOTH_TAU))
        // ── ④ 体型与姿态（原型 2202-2208 行）────────────────────────────────
        val r = span * CRAB_R_K
        val gait = age * CRAB_GAIT + SeasideWaves.hash2(k, 9223) * CRAB_PHASE_K
        val face = if (SeasideWaves.hash2(k, 9225) < 0.5) -1.0 else 1.0
        val tilt = (SeasideWaves.hash2(k, 9227) - 0.5) * CRAB_TILT_SPAN
        val bob = CRAB_BOB_K * r * SeasideWaves.fsin(gait * 2.0)
        val sway = CRAB_SWAY_K * r * SeasideWaves.fsin(gait)
        // ⭐ 螯的微微开合（原型 2274 行）：`0.20R` 振幅、相位偏移 `1.2rad`
        val bite = CRAB_BITE_K * r * SeasideWaves.fsin(gait + CRAB_BITE_PH)
        val paint = crabPaint
        val hi = batches >= 8
        val mid = batches >= 4
        // ⭐ LOW 的 2 次 = ④壳填充 + ⑥壳沿（**所有者裁决**，见 KDoc「画质分档」）：
        //   壳沿那一批是**独立**的 `STROKE` 提交 ⇒ LOW 才有第二种颜色。
        val rim = batches >= 2
        val canvas = drawContext.canvas.nativeCanvas
        val sa = alpha255(vis)
        // ── ⑤ 那四层变换（原型 2209-2223 行）────────────────────────────────
        //    `translate(x, crabCy + bob)` → `rotate(tilt)` → `scale(1, face)` → `translate(sway, 0)`
        //    ⛔ 走**缓存 `Matrix`**：`postXxx` 的语义是「在已有变换**之后**施加」，
        //    逐条 `post` 上去的次序恰好等于原型的叠放次序。⛔ 不逐点手算 sin/cos。
        crabMtx.reset()
        crabMtx.postTranslate(sway.toFloat(), 0f)
        crabMtx.postScale(1f, face.toFloat(), 0f, 0f)
        crabMtx.postRotate((tilt * 180.0 / Math.PI).toFloat(), 0f, 0f)
        crabMtx.postTranslate(x.toFloat(), (crabCy + bob).toFloat())
        val shA = CRAB_SHELL_A * r
        val shB = CRAB_SHELL_B * r
        var lw = 0.0
        // ── ① 影子（`crabShadow`，`α = vis·0.13`）。⛔ **世界坐标**：原型画在 `ctx.translate`
        //   **之前** ⇒ 不带 bob / tilt / face / sway。⛔ 必须半透明：按 `α = 1` 画出来，
        //   12× 截图上它是一坨比蟹本身还抢眼的实心污渍（原型 2212-2213 行）。
        if (hi) {
            val sx = x + CRAB_SHADOW_DX * r
            val sy = crabCy + CRAB_SHADOW_DY * r
            val srx = CRAB_SHADOW_RX * r
            val sry = CRAB_SHADOW_RY * r
            crabBody.rewind()
            crabBody.addOval(
                (sx - srx).toFloat(), (sy - sry).toFloat(),
                (sx + srx).toFloat(), (sy + sry).toFloat(), NativePath.Direction.CW,
            )
            paint.style = Paint.Style.FILL
            paint.color = PAL_CRAB_SHADOW
            paint.alpha = alpha255(vis * CRAB_SHADOW_A)
            canvas.drawPath(crabBody, paint)
        }
        if (mid) {
            // ── ② 远侧步足（`crabLegFar` / `0.21R`）────────────────────────────
            buildCrabLegs(gait, r, true)
            crabBody.transform(crabMtx, crabOut)
            paint.style = Paint.Style.STROKE
            paint.color = PAL_CRAB_LEG_FAR
            lw = CRAB_LEG_W_FAR * r
            if (CRAB_MIN_LEG_W > lw) lw = CRAB_MIN_LEG_W.toDouble()
            paint.strokeWidth = lw.toFloat()
            paint.alpha = sa
            canvas.drawPath(crabOut, paint)
            // ── ③ 近侧步足 + 螯臂（`crabLeg` / `0.26R`）──────────────────────
            buildCrabLegs(gait, r, false)
            if (hi) buildCrabArms(r)
            crabBody.transform(crabMtx, crabOut)
            paint.color = PAL_CRAB_LEG
            lw = CRAB_LEG_W_NEAR * r
            if (CRAB_MIN_LEG_W > lw) lw = CRAB_MIN_LEG_W.toDouble()
            paint.strokeWidth = lw.toFloat()
            canvas.drawPath(crabOut, paint)
        }
        // ── ④ 甲壳填充（`crabShell`）：⛔ **真正的椭圆填充**（原型 2257-2260 行）——
        //    早先用一支 `2·0.76R` 的粗描边线段当「跑道形壳」，那是**描边不是填充**，
        //    壳内一片空白、7 项调色板里有 5 项永远用不上。
        crabBody.rewind()
        crabBody.addOval(
            (-shA).toFloat(), (-shB).toFloat(), shA.toFloat(), shB.toFloat(),
            NativePath.Direction.CW,
        )
        crabBody.transform(crabMtx, crabOut)
        paint.style = Paint.Style.FILL
        paint.color = PAL_CRAB_SHELL
        paint.alpha = sa
        canvas.drawPath(crabOut, paint)
        if (hi) {
            // ── ⑤ 双螯填充（`crabClaw`）：⛔ 不能与壳同色同描边，否则壳与螯糊成一整块橙 ──
            // ⛔ **必须先 `rewind`**：④ 留在 [crabBody] 里的壳椭圆若不清掉，这里会把整个
            //   壳重新填成 `crabClaw` 色（NonZero 下并进填充区域）⇒ 壳色整个丢掉。
            crabBody.rewind()
            buildCrabClaws(r, bite)
            crabBody.transform(crabMtx, crabOut)
            paint.color = PAL_CRAB_CLAW
            canvas.drawPath(crabOut, paint)
        }
        if (rim) {
            // ── ⑥ 壳沿 + 螯沿 + 眼柄（`crabRim` / `0.13R`）────────────────────
            //    ⛔ **必须先 `rewind`**：④ 留在 [crabBody] 里的壳椭圆若不清掉，
            //   NonZero 下它会与这里新加的壳轮廓并成同一条闭合子路径 ——
            //   （描边会画两遍边线，视觉上还行，但 LOW 下 [crabBody] 从未被清过，
            //   路径里会同时留着上一帧 ⑧ 的双眼点 ⇒ 直接把眼点重描一遍。）
            crabBody.rewind()
            crabBody.addOval(
                (-shA).toFloat(), (-shB).toFloat(), shA.toFloat(), shB.toFloat(),
                NativePath.Direction.CW,
            )
            if (hi) {
                buildCrabClaws(r, bite)
                buildCrabEyeStalks(r, bite)
            }
            crabBody.transform(crabMtx, crabOut)
            paint.style = Paint.Style.STROKE
            paint.color = PAL_CRAB_RIM
            lw = CRAB_RIM_W * r
            if (CRAB_MIN_W > lw) lw = CRAB_MIN_W.toDouble()
            paint.strokeWidth = lw.toFloat()
            paint.alpha = sa
            canvas.drawPath(crabOut, paint)
        }
        if (hi) {
            // ── ⑦ 背上一道高光（`crabShellHi` / `0.18R`）：左上，受光方向与湿沙高光一致 ──
            //    壳才有体积而不是一个椭圆（原型 2264-2269 行）。
            val ha = CRAB_HI_A * r
            val hb = CRAB_HI_B * r
            crabRect.set((-ha).toFloat(), (-hb).toFloat(), ha.toFloat(), hb.toFloat())
            crabBody.rewind()
            crabBody.addArc(
                crabRect,
                (CRAB_HI_T0 * 180.0 / Math.PI).toFloat(),
                ((CRAB_HI_T1 - CRAB_HI_T0) * 180.0 / Math.PI).toFloat(),
            )
            crabBody.transform(crabMtx, crabOut)
            paint.color = PAL_CRAB_SHELL_HI
            lw = CRAB_HI_W * r
            if (CRAB_MIN_W > lw) lw = CRAB_MIN_W.toDouble()
            paint.strokeWidth = lw.toFloat()
            canvas.drawPath(crabOut, paint)
            // ── ⑧ 眼点（`crabRim` 填充）：半径 `0.24R` 的**实心圆**（原型 2318-2324 行）──
            //    ⛔ 不可用「零长线段 + round cap」凑：那种画法在 1px 级线宽下会退化成方点。
            crabBody.rewind()
            var side = 0
            while (side < 2) {
                val s = if (side != 0) 1.0 else -1.0
                crabBody.addCircle(
                    (s * (CRAB_EYE_X + bite * CRAB_EYE_BITE) * r).toFloat(),
                    (CRAB_EYE_CY * r).toFloat(),
                    (CRAB_EYE_R * r).toFloat(), NativePath.Direction.CW,
                )
                side++
            }
            crabBody.transform(crabMtx, crabOut)
            paint.style = Paint.Style.FILL
            paint.color = PAL_CRAB_RIM
            canvas.drawPath(crabOut, paint)
        }
    }

    /**
     * 把**一侧**的四条步足写进 [crabBody]（**体坐标**，原型 2229-2251 行）。
     *
     * - ⛔ 髋→膝必须是**真 `quadraticCurveTo`**：`drawLines` 只有直线，两段弦近似会让
     *   「膝盖外凸」塌成折角，螃蟹读成蜈蚣。
     * - ⛔ 左右两侧**反相**（`far` ⇒ 相位 `+π`、方向取 `−x`）—— 这是「横着走」的全部
     *   秘密：两侧同相就变成普通的前爬，读不出螃蟹。同侧四条再各差一个相位
     *   ⇒ 步足像波浪一样从前往后传。
     * - ⭐ 足端 = **撑出去/收回来 + 前后摆 + 抬起**（半径随相位一起变），x、y 两个方向
     *   同时错开，任何相位都不重合 —— 真实蟹蟑腿就是这样运动的。
     */
    private fun buildCrabLegs(gait: Double, r: Double, far: Boolean) {
        val s = if (far) -1f else 1f
        val ph = if (far) Math.PI else 0.0
        crabBody.rewind()
        var i = 0
        while (i < CRAB_LEG_Y.size) {
            val hy = CRAB_LEG_Y[i] * r
            val sw = SeasideWaves.fsin(gait + i * CRAB_LEG_SPREAD + ph)
            val rad = (CRAB_LEG_RAD_LO + CRAB_LEG_RAD_SPAN * sw) * r
            val lift = CRAB_LEG_LIFT * r * (0.5 + 0.5 * sw)
            val fore = rad * CRAB_LEG_FORE * sw
            val hx = s * CRAB_LEG_HIP * r
            val kx = s * rad * CRAB_LEG_KNEE
            val ky = hy + fore * CRAB_LEG_FORE_K - lift * CRAB_LEG_LIFT_K
            val fx = s * rad
            val fy = hy + fore - lift
            crabBody.moveTo(hx.toFloat(), hy.toFloat())
            crabBody.quadTo(
                ((hx + kx) * 0.5 + s * CRAB_LEG_BOW * r).toFloat(),
                ((hy + ky) * 0.5).toFloat(),
                kx.toFloat(), ky.toFloat(),
            )
            crabBody.lineTo(fx.toFloat(), fy.toFloat())
            i++
        }
    }

    /**
     * 螯臂（原型 2280-2286 行）：从壳的前肩斜撑到螯，两条折线 ⛔ 追加进 [crabBody]。
     *
     * ⛔ 与近侧步足**同批**（同色 `crabLeg`）⇒ 代价是线宽取 `0.26R` 而非原型的 `0.24R`，
     *   且画在壳填充**之下**（原型那条臂只有 ≈6% 的长度落在壳椭圆内 ⇒ 视觉等价）。
     */
    private fun buildCrabArms(r: Double) {
        var side = 0
        while (side < 2) {
            val s = if (side != 0) 1f else -1f
            crabBody.moveTo((s * CRAB_ARM_X0 * r).toFloat(), (CRAB_ARM_Y0 * r).toFloat())
            crabBody.lineTo((s * CRAB_ARM_X1 * r).toFloat(), (CRAB_ARM_Y1 * r).toFloat())
            side++
        }
    }

    /**
     * 双螯的**钳体轮廓**追加进 [crabBody]（体坐标，原型 2287-2308 行）。
     *
     * - ⭐ 钳口那个 **V 形缺口**是「一眼认出是螃蟹」的关键；⛔ 钳体必须**凸出壳的前缘**
     *   ——摆在壳缘上会整只糊进深色壳沿里，4× 截图上只剩两个点，读成蜱虫。
     * - ⛔ 钳体**不能与壳同色**：同色同描边 ⇒ 12× 截图上壳与螯糊成一整块橙。
     * - 旋转/平移交给缓存的 [crabClawMtx]（`postRotate` → `postTranslate`，次序 = 原型的
     *   「先 `translate` 后 `rotate`」），⛔ 不逐点手算 `sin`/`cos`。
     */
    private fun buildCrabClaws(r: Double, bite: Double) {
        val cp = CRAB_CLAW_CP * r
        val cq = CRAB_CLAW_CQ * r
        var side = 0
        while (side < 2) {
            val s = if (side != 0) 1.0 else -1.0
            crabTmp.rewind()
            crabTmp.moveTo((-cp * 0.80).toFloat(), (-cq).toFloat())
            crabTmp.quadTo(
                (cp * 0.60).toFloat(), (-cq * 1.06).toFloat(),
                (cp * 0.88).toFloat(), (-cq * 0.46).toFloat(),
            )
            crabTmp.lineTo((cp * 0.30).toFloat(), 0f)
            crabTmp.lineTo((cp * 0.88).toFloat(), (cq * 0.46).toFloat())
            crabTmp.quadTo(
                (cp * 0.60).toFloat(), (cq * 1.06).toFloat(),
                (-cp * 0.80).toFloat(), cq.toFloat(),
            )
            crabTmp.quadTo((-cp * 1.12).toFloat(), 0f, (-cp * 0.80).toFloat(), (-cq).toFloat())
            crabTmp.close()
            crabClawMtx.reset()
            crabClawMtx.postRotate(
                (s * (CRAB_CLAW_ANG + bite) * 180.0 / Math.PI).toFloat(), 0f, 0f,
            )
            crabClawMtx.postTranslate(
                (s * CRAB_CLAW_OX * r).toFloat(), (CRAB_CLAW_OY * r).toFloat(),
            )
            crabBody.addPath(crabTmp, crabClawMtx)
            side++
        }
    }

    /**
     * 双眼柄（原型 2310-2317 行）——两条折线，⛔ 追加进 [crabBody]。
     *
     * ⚠️ 与壳沿**同批** ⇒ 颜色取 `crabRim`（原型是 `crabLeg`，两者只差一档亮度）、线宽取
     *   `0.13R`（原型 `0.14R`）。眼点本来就是 `crabRim`，柄与珠同色更像「柄顶着珠」。
     */
    private fun buildCrabEyeStalks(r: Double, bite: Double) {
        val tx = (CRAB_EYE_X + bite * CRAB_EYE_BITE) * r
        var side = 0
        while (side < 2) {
            val s = if (side != 0) 1f else -1f
            crabBody.moveTo((s * CRAB_EYE_X0 * r).toFloat(), (CRAB_EYE_Y0 * r).toFloat())
            crabBody.lineTo((s * tx).toFloat(), (CRAB_EYE_Y1 * r).toFloat())
            side++
        }
    }

    /** `0..1` 的强度 → `Paint.setAlpha` 的 `0..255`（⛔ 纯标量，零分配）。 */
    private fun alpha255(v: Double): Int = (v * 255.0).toInt().coerceIn(0, 255)

    /**
     * swash 周期的 vigor（上涌 [VIGOR_UPRUSH] / 退水 [VIGOR_RETREAT] / 干燥 [VIGOR_DRY]）——
     * ⛔ 只由 [SeasideWaves.stage] 派生，⛔ 不吃音频。
     */
    private fun vigorOf(wv: SeasideWaves): Double = when (wv.stage) {
        SeasideWaves.STAGE_UPRUSH -> VIGOR_UPRUSH
        SeasideWaves.STAGE_RETREAT -> VIGOR_RETREAT
        else -> VIGOR_DRY
    }

    // ── ⑧ 沙面颗粒层 ────────────────────────────────────────────────────────

    /**
     * [SeaOpItem.SPLASH_POINTS] —— 飞沫。**恒 1 次** `drawPoints`（HIGH/MEDIUM；⛔ LOW 关闭）。
     *
     * ⛔ 点表是**确定性烘焙**的（[SPLASH_MAX] 个抛物线落点 + 越过前缘后的滞留/消散），
     * ⛔ 不用 `rng` 也不用任何随机源；点数与 alpha 由 `audio.splashCount` 驱动（§4.6）。
     *
     * @param t 相对首帧的毫秒。
     */
    private fun DrawScope.drawSplash(t: Double) {
        // ⛔ `edgeYs` 只在领头浪被画过的那一帧才有意义（原型 `if (!edgeValid) return`）
        if (!edgeValid) return
        if (opsOf(SeaOpItem.SPLASH_POINTS) <= 0) return
        val n = audio.splashCount(SPLASH_MAX)
        if (n <= 0) return
        val w = size.width
        val h = size.height.toDouble()
        val cols = SeasideWaves.COLS
        val sh = audio.sHigh
        var k = 0
        var i = 0
        while (i < n) {
            val per = splashPeriod[i].toDouble()
            // `wrap01(t / period + phase / period)` —— ⛔ **不**循环取整，只是把负值折回
            var u = (t / per + splashPhase[i].toDouble() / per) % 1.0
            if (u < 0.0) u += 1.0
            if (u <= 1.0) {
                val x = splashU[i] * w
                var col = (splashU[i] * cols + 0.5f).toInt()
                if (col < 0) col = 0 else if (col > cols) col = cols
                val edge = edgeYs[col]
                val cy = if (edge == 0f) h * SPLASH_FALLBACK_Y else edge.toDouble()
                // 越过前缘后短暂滞留再消散：`reach·u^0.62` 抛物线 + `lift·u` 横向散开
                val y = cy + h * splashReach[i] * powD(u, SPLASH_REACH_POW) +
                    splashLift[i] * h * u
                val a = SPLASH_A * splashBright[i] * powD(1.0 - u, SPLASH_FADE_POW) *
                    (0.45 + 0.55 * sh)
                if (a >= SPLASH_A_MIN) {
                    splashPts[k * 2] = x
                    splashPts[k * 2 + 1] = y.toFloat()
                    k++
                }
            }
            i++
        }
        if (k <= 0) return
        val den = if (density > 0f) density else 1f
        pointPaint.color = PAL_FOAM_EDGE
        // ⚠️ **已记录的折叠**：原型逐点一个 `sz = s.size·(1 − 0.35·u)` 的方块，而
        //   `drawPoints` 只有一支画笔 ⇒ 取尺寸区间的中值当直径（点仍是**圆点**，
        //   §4.9.2 引的正是 `strokeCap = ROUND`）。
        pointPaint.strokeWidth = (SPLASH_SIZE_LO + SPLASH_SIZE_SPAN * 0.5).toFloat() * den
        pointPaint.alpha = alpha255(
            SPLASH_A * (SPLASH_BRIGHT_LO + SPLASH_BRIGHT_SPAN * 0.5) * (0.45 + 0.55 * sh)
        )
        drawPointsBatch(splashPts, 0, k * 2, pointPaint)
    }

    /**
     * [SeaOpItem.SAND_GRAIN] —— 沙纹（26 条断段浅色调）。**3 次 `drawLines`**（MED·LOW 各 1 次）。
     *
     * ⛔ **签名不含 `clip: Path`** —— §4.9.3 要求删掉那个参数、改**逐列门控**；
     * ⛔ 这是全文件零 `clipPath` 的前提之一。
     * ⭐ **门控谓词取「该点是否在该列岸线之下」而不是 `wetAmt`**：`sandPath` 的上边界就是
     *   `shoreYs[]` ⇒ 那是 `clip(sandPath)` 的**逐位等价谓词**。用 `wetAmt` 会在干沙上把
     *   沙纹整片抹掉（湿区是水线**内陆**那条，而 `sandPath` 覆盖岸线以下的**全部**干沙），
     *   那是比原型更严重的偏差。`drawResidue` 同理。
     * ⛔ 与 [drawResidue] **形态必须不同**（线 vs 点）且分层绘制，否则读成噪点。
     *
     * ## ✅ 已核对：本函数是**横向**沙纹，与原型 1:1，⛔ **不是**真机竖纹的成因
     * 原型 `drawRipples`（`seaside-preview.html:3254-3272`）里 `k` 循环推进的是 **`x`**：
     * ```
     * :3266  for (let k = 0; k <= 6; k++){
     * :3267    const x  = lerp(xa, xb, k / 6);            // ⛔ 沿 x 延展
     * :3268-9  const yy = y + jitter
     *            + 0.0016 * H * Math.sin(x * 0.0071 * kScale + i * 1.9 + t * 0.00012);
     * :3270    if (k === 0) ctx.moveTo(W * p… , yy); else ctx.lineTo(…, yy);
     * ```
     * 本函数 `:6445-6451` 逐字对应（`x = xa + (xb − xa)·f`，`rippleY[k] = yLine + jitter + 摆动`）
     * ⇒ **两端点同 y、沿 x 延展 = 横向**，⛔ 方向**没有画反**。
     * 另：原型 `:3244` 三档色 `#eedab4 / #f7e8cb / #fff5e2` 是**浅色**、`:3248` `lineWidth = 1`、
     *   `:3243` 原文「 unbroken full-width line reads as a scanline」⇒ 沙纹本身也**画不出**
     *   截图里那种 ±13 灰阶、**相邻行几乎相同**的**宽带**竖纹。
     *
     * ## ⛔ 真机竖纹：**已定位并已修**（2026-10-05；⛔ 上一版此处的「未修 / 采样层」结论是**错的**）
     *
     * 实测签名（`output/seaside_device/dev_80_v1.png`，1920×1080，沙滩 `y∈[780,1020]`）：
     * x 方向标准差 **17.9**、y 方向只有 **4.6**、相邻行相关 **0.978**、96×64 裁切放大 4×
     * 后能看见**等距的细竖线**（≈5.5px 一根）、`y=803` 以下**逐行数值完全相同**。
     * ⚠️ 「60% 的 x 方差落在周期 2–8px」这条**不是**竖纹的证据 —— 白噪声的功率谱本来就是
     * 平的，2–8px 恰好覆盖最高频那一段（本次复算：纯白噪声纹理同样是 **75%**）。
     *
     * **定位（可复算）**：把屏幕行的 x 剖面与纹理**每一行**的层④ `hash2(x, j)` 求相关 ⇒
     * 六行屏幕（`y=814…1055`）**全部**命中 **`j = th−1 = 582`**（r = +0.54…+0.56），
     * 其余 582 行 |r| ≤ 0.10。⇒ 整块沙被 `CLAMP` 钉在**纹理最后一行**。
     * **机制与修法见 [drawSand] 的 KDoc**（`BitmapShader.setLocalMatrix` 不生效 ⇒ 采样退化成
     * 恒等 ⇒ `y' = y ≥ th` ⇒ `CLAMP`）。**已修**：`setLocalMatrix` 换成 `Canvas` 的
     * `translate + scale`。
     *
     * ⚠️ **海面不是同一个成因**：[drawSeaField] 走 `drawImage(…, dstSize)`（Compose 的
     * 矩形缩放 blit，⛔ 不用 shader），它的「近纯 x」是**设计使然** —— 原型 `drawSeaField`
     * 本来就是 `colPhase / colMid / colFine / roughX` **逐列**数组（§4.2）。⛔ 别再往
     * 「共同上游」找，那条推论是顺着「两个现象同因」的错误前提走的。
     *
     * @param t 相对首帧的毫秒。
     */
    private fun DrawScope.drawRipples(t: Double) {
        val wv = waves ?: return
        val batches = opsOf(SeaOpItem.SAND_GRAIN)
        if (batches <= 0) return
        val n = wv.column_count
        val w = size.width
        val h = size.height.toDouble()
        val kScale = if (w > 1f) CAUSTIC_SLOPE_REF_W / w else 1f
        val yTop = h * (SeasideWaves.SHORE_K + SAND_LINE_TOP_K)
        val span = h - yTop
        // 每组整体缓慢漂移（⛔ **不循环** ⇒ 无闪烁）
        val groupDrift = SAND_LINE_DRIFT * sin(t * SAND_LINE_DRIFT_T)
        val hi = SeasideWaves.clamp(0.35 + audio.sHigh * 0.9, 0.0, 1.0)
        val stride = SAND_GRAIN_SEG_STRIDE
        var b = 0
        while (b < batches && b < sandGrainPaints.size) {
            val dst0 = b * stride
            var dst = 0
            var i = 0
            while (i < SAND_LINES) {
                // ⛔ 条数固定，只有 18% 的线靠 hash 控制**可见性**（⛔ 不改条数 ⇒ 不产生摩尔纹）
                if (SeasideWaves.hash2(i, 77) >= SAND_LINE_VIS_P) {
                    val v = (i + 0.5).toDouble() / SAND_LINES.toDouble()
                    val yLine = yTop + v * span + groupDrift +
                        SAND_LINE_WOB_A * sin(t * SAND_LINE_WOB_T + i * SAND_LINE_WOB_PHASE)
                    val jitter = SAND_LINE_JIT * h * SeasideWaves.hash2(i, 21)
                    val segs = SAND_LINE_SEG_LO +
                        (SAND_LINE_SEG_SPAN * SeasideWaves.hash2(i, 41)).toInt()
                    var g = 0
                    while (g < segs) {
                        val a0 = (g + SeasideWaves.hash2(i, 60 + g) * SAND_LINE_SEG_A0) / segs
                        val a1 = minOf(
                            1.0,
                            a0 + (0.55 + 0.45 * SeasideWaves.hash2(i, 70 + g)) /
                                segs * SAND_LINE_SEG_SPAN_K
                        )
                        val xa = w * a0
                        val xb = w * a1
                        if (xb - xa >= SAND_LINE_MIN_PX) {
                            var k = 0
                            while (k <= SAND_GRAIN_STEP_SPAN) {
                                val f = k.toDouble() / SAND_GRAIN_STEP_SPAN.toDouble()
                                val x = xa + (xb - xa) * f
                                rippleX[k] = x.toFloat()
                                rippleY[k] = (yLine + jitter + SAND_LINE_PWOB_K * h * sin(
                                    x * 0.0071 * kScale + i * SAND_LINE_PWOB_PHASE + t * SAND_LINE_PWOB_T
                                )).toFloat()
                                k++
                            }
                            // ⛔ **逐列门控**（原型是 `ctx.clip(sandPath)`）：⛔ 全文件零
                            //   `clipPath`（§4.9.3，Android 5.1 真机三次复现 hwui SIGSEGV）
                            //   ⇒ 改逐点判「这一点在不在岸线之下」—— 那**正是** `sandPath`
                            //   （上边界 = `shoreYs[]`）的等价谓词。
                            var run = -1
                            k = 0
                            while (k <= SAND_GRAIN_STEP_SPAN) {
                                var c = (rippleX[k] / w * (n - 1).toDouble() + 0.5).toInt()
                                if (c < 0) c = 0 else if (c > n - 1) c = n - 1
                                val on = rippleY[k] >= wv.shore_ys[c]
                                if (on) {
                                    if (run < 0) run = k
                                } else if (run >= 0) {
                                    dst = drawRippleFlush(run, k - 1, dst0 + dst)
                                    run = -1
                                }
                                k++
                            }
                            if (run >= 0) dst = drawRippleFlush(run, SAND_GRAIN_STEP_SPAN, dst0 + dst)
                        }
                        g++
                    }
                }
                i++
            }
            val paint = sandGrainPaints[b]
            paint.color = SAND_LINE_TIER_RGB[b]
            paint.strokeWidth = 1f
            // ⛔ alpha 上限 [SAND_LINE_A]（防摩尔纹）；三档权重照抄，`sHigh` 只调 alpha
            paint.alpha = alpha255(
                SAND_LINE_A * SAND_LINE_TIER_K[b] *
                    (SAND_LINE_ALPHA_K + SAND_LINE_ALPHA_SPAN * hi)
            )
            drawLinesBatch(sandGrainPts, dst0, dst, paint)
            b++
        }
    }

    /**
     * [SeaOpItem.SAND_GRAIN] 的辅助：把 [rippleX] / [rippleY] 的 `[a, b]` 折线**拉直**成
     * `drawLines` 的直线段，写进 [sandGrainPts] 的 `dst` 处。
     *
     * ⛔ 零分配（只读写两个预分配数组）。命名带 `draw` 前缀是为了进
     *   `PerfBudgetContractTest` 的每帧可达扫描（§4.9.4 陷阱 1）。
     *
     * @return 写入后的 float 下标（下一段的起点）。
     */
    private fun DrawScope.drawRippleFlush(a: Int, b: Int, dst: Int): Int {
        var d = dst
        var i = a
        while (i < b) {
            if (d + 4 > SAND_GRAIN_SEG_STRIDE) break
            sandGrainPts[d] = rippleX[i]
            sandGrainPts[d + 1] = rippleY[i]
            sandGrainPts[d + 2] = rippleX[i + 1]
            sandGrainPts[d + 3] = rippleY[i + 1]
            d += 4
            i++
        }
        return d
    }

    /**
     * [SeaOpItem.RESIDUE_POINTS] —— 残沫白点（软阴影 → 亮芯 → 左上缘高光）。
     * **3 次 `drawPoints`**（`RESIDUE_MAX` × 3 笔折成同缓冲的三个 offset）。
     *
     * ⛔ **不得连成纹路** —— 连成纹就退化成沙纹；⛔ **不**烘成位图（要保留逐点 alpha）。
     * ⛔ **签名不含 `clip: Path`**（同 [drawRipples]，§4.9.3）⇒ 逐列「是否在该列岸线之下」
     *   门控（`sandPath` 的等价谓词，理由同 [drawRipples] 的 KDoc）。
     */
    private fun DrawScope.drawResidue() {
        val wv = waves ?: return
        val batches = opsOf(SeaOpItem.RESIDUE_POINTS)
        if (batches <= 0) return
        if (residueBaked <= 0) return
        val n = audio.residueCount(RESIDUE_MAX)
        val cnt = if (n < residueBaked) n else residueBaked
        if (cnt <= 0) return
        val w = size.width
        val h = size.height.toDouble()
        val dens = audio.residueRatio()
        // ⛔ 纵向分布：越靠内陆（退水路过最久的地方）越密（原型 `pow(hash, 0.65)` 的
        //   等价物已烘进 [residueV]），基线与跨度照抄原型 `drawResidue` 的两行。
        val yBase = h * (SeasideWaves.SHORE_K + RESIDUE_BASE_K)
        val ySpan = h * (1.0 - SeasideWaves.SHORE_K) - h * RESIDUE_SPAN_K
        val stride = cnt * 2
        var i = 0
        while (i < cnt) {
            val x = residueU[i] * w
            var y = yBase + residueV[i] * ySpan
            // ⛔ **逐列门控**取代原型的 `ctx.clip(sandPath)`（⛔ 全文件零 `clipPath`，
            //   §4.9.3 Android 5.1 真机三次复现 hwui SIGSEGV）：`sandPath` 的上边界就是
            //   `shoreYs[]` ⇒ 等价谓词是「这一点在不在该列岸线之下」。
            //   ⚠️ 残沫的基线 `SHORE_K + 0.085` 比岸线的最大摆动 `SHORE_K + 0.130` 低
            //   ⇒ 顶端约 0.045h 那一段在个别列会落到岸线之上 ⇒ 夹到岸线上（而不是丢弃：
            //   `drawPoints` 没有逐点剔除，逐点剔除要另开一条通道）。
            var c = (residueU[i] * (n - 1).toDouble() + 0.5).toInt()
            if (c < 0) c = 0 else if (c > wv.column_count - 1) c = wv.column_count - 1
            val yShore = wv.shore_ys[c].toFloat()
            if (y < yShore) y = yShore.toDouble()
            val s = residueS[i].toDouble()
            // ① 软阴影：略暗、略大 ⇒ 颗粒陷在沙里的凹陷（原型那个 `2.0s × 2.0s` 方块
            //    折成一个直径 `2.0s` 的圆点，圆心即那个方块的几何中心）
            residuePts[i * 2] = (x + s * 0.30).toFloat()
            residuePts[i * 2 + 1] = (y + s * 1.10).toFloat()
            // ② 亮芯（原型 `s × 0.8s`，折成直径 `s` 的点）
            residuePts[stride + i * 2] = (x + s * 0.50).toFloat()
            residuePts[stride + i * 2 + 1] = (y + s * 0.40).toFloat()
            // ③ 左上缘高光 ⇒ 球体感（原型 `s × max(0.7, 0.3s)`）
            residuePts[stride * 2 + i * 2] = (x + s * 0.50).toFloat()
            residuePts[stride * 2 + i * 2 + 1] = (y + s * 0.15).toFloat()
            i++
        }
        // ⚠️ **已记录的折叠**：原型逐点一个 `a = RESIDUE_A·p.a·(0.45+0.55·dens)`，而
        //   一次 `drawPoints` 只有一支画笔 ⇒ 三笔各取该笔的**定值** alpha；`p.a` 的
        //   0.5..1.0 抖动与逐点尺寸 `p.s` 同样折成中值（§4.4 只要求「零散孤立、alpha 极低、
        //   三笔球体感」，这三项都保住了）。
        val aMean = RESIDUE_A * (RESIDUE_A_LO + RESIDUE_A_SPAN * 0.5) * (0.45 + 0.55 * dens)
        val sMean = RESIDUE_S_LO + RESIDUE_S_SPAN * 0.5
        val den = if (density > 0f) density else 1f
        var b = 0
        while (b < batches && b < residuePaints.size) {
            val paint = residuePaints[b]
            if (b == 0) {
                paint.color = PAL_RESIDUE_SHADOW
                paint.strokeWidth = (sMean * 2.0).toFloat() * den
                paint.alpha = alpha255(aMean * RESIDUE_SHADOW_A)
            } else if (b == 1) {
                paint.color = PAL_RESIDUE_CORE
                paint.strokeWidth = sMean.toFloat() * den
                paint.alpha = alpha255(aMean * RESIDUE_CORE_A)
            } else {
                paint.color = PAL_RESIDUE_HI
                val hw = sMean * 0.30
                paint.strokeWidth = (if (hw < 0.7) 0.7 else hw).toFloat() * den
                paint.alpha = alpha255(minOf(1.0, aMean * RESIDUE_HI_K))
            }
            drawPointsBatch(residuePts, stride * b, stride, paint)
            b++
        }
    }
}

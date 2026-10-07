package com.nasmusic.tv.visualizer.fx

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.nasmusic.tv.visualizer.VisualizerMath
import kotlin.math.sin

/**
 * 程序化纹理仓库。**所有纹理在此生成一次并缓存**，`draw` 期只做 `drawImage`。
 *
 * ⛔ 生命周期：由渲染器基类 [com.nasmusic.tv.visualizer.renderers.RendererFx]
 * 的 `onExit()` 在每次效果切离时调一次 [release]（H4 修复，2026-10-06；
 * 旧文档声称由 `VisualizerStage` 调用，实际从未有过生产调用点，已订正）；
 * 渲染器**不得**自行调用（多个渲染器共享同一份缓存）。
 * ⛔ 缓存查找必须是**数组下标**（见 [slots]），不得用 `HashMap` —— 后者在
 * draw 路径上会因装箱产生分配。
 *
 * 实现说明：平铺型（GRAIN/SCANLINE）与球面贴图的像素生成是**纯 Kotlin 函数**
 * （`grainPixels` / `scanlinePixels` / `spherePixels`），可被 JVM 单测直接做逐像素
 * 确定性门禁（§八 G3），不依赖 Robolectric；全屏纹理（STARFIELD/PAPER/WATER/CAUSTIC/
 * PLASMA/FOG）逐行生成后 `setPixels` 写入（瞬时分锚只有一行，避免 1080p 下一次性 8MB 数组）。
 */
object ProceduralTexture {

    /**
     * 可平铺的固定尺寸纹理；每类固定占 [VARIANTS] 个槽位（`variant and 7`）。
     * ⛔ **新增必须追加到最后一项**（`ordinal` 是 [slots] / [keys] 的下标基准）。
     */
    enum class Id { GRAIN, SCANLINE, STARFIELD, PAPER, WATER, CAUSTIC, PLASMA, FOG }

    const val VARIANTS = 8
    const val GRAIN_TILE = 128      // px，见 §7.1
    const val SCANLINE_H = 3        // px，见 §7.1
    const val SPHERE_PX = 128       // 球面贴图边长

    private val slots = arrayOfNulls<ImageBitmap>(Id.entries.size * VARIANTS)

    /** 生成时的 (w,h,seed) 键；0L = 未生成。平铺型与画布尺寸无关，用常量键 */
    private val keys = LongArray(Id.entries.size * VARIANTS)

    /** 球面贴图按颜色缓存（行星/原子颜色是固定有限的几个） */
    private val sphereColors = IntArray(16)
    private val sphereTiles = arrayOfNulls<ImageBitmap>(16)
    private var sphereCount = 0

    private var ensuredW = 0
    private var ensuredH = 0

    /**
     * H4 修复（2026-10-06，代码审查报告 §3）：[ensure] 与 [ensureFullscreenOnly] 拆分记账。
     *
     * 旧缺陷：两类调用共享同一对 [ensuredW]/[ensuredH]，且 [ensure] 的尺寸早退位于
     * 所有逐 key 守卫之前 —— [ensureFullscreenOnly] 先烘过一张后，同尺寸下后续渲染器
     * 的 [ensure] 整体早退，其余 5 张全屏纹理永不生成（StarrySky → LightBeams 切换即触发）。
     *
     * 修复：[ensure] 的早退改为「平铺槽 + 全部 6 张全屏槽**逐槽**就绪」才返回；
     * [ensureFullscreenOnly] 只按自己的 key 槽判断，不再污染整组记账。
     */
    private fun ensureTiledSlots() {
        // GRAIN ×8（平铺型，与画布尺寸无关，只生成一次）
        val g = Id.GRAIN.ordinal * VARIANTS
        for (v in 0 until VARIANTS) {
            if (keys[g + v] == TILED_KEY && slots[g + v] != null) continue
            slots[g + v] = makeBitmap(GRAIN_TILE, GRAIN_TILE, grainPixels(v))
            keys[g + v] = TILED_KEY
        }
        // SCANLINE ×1
        val s = Id.SCANLINE.ordinal * VARIANTS
        if (keys[s] != TILED_KEY || slots[s] == null) {
            slots[s] = makeBitmap(1, SCANLINE_H, scanlinePixels())
            keys[s] = TILED_KEY
        }
    }

    /** [ensure] 的早退判据：平铺槽与全部 6 张全屏槽（当前尺寸 key）逐槽就绪才算已烘。 */
    private fun allSlotsReady(w: Int, h: Int): Boolean {
        if (ensuredW == w && ensuredH == h) {
            // 尺寸未变，但需逐槽确认（ensureFullscreenOnly 可能只烘过 1 张）
            val fullKey = fullscreenKey(w, h)
            val g = Id.GRAIN.ordinal * VARIANTS
            for (v in 0 until VARIANTS) {
                if (keys[g + v] != TILED_KEY || slots[g + v] == null) return false
            }
            val s = Id.SCANLINE.ordinal * VARIANTS
            if (keys[s] != TILED_KEY || slots[s] == null) return false
            for (id in FULLSCREEN_IDS) {
                val idx = id.ordinal * VARIANTS
                if (keys[idx] != fullKey || slots[idx] == null) return false
            }
            return true
        }
        return false
    }

    /** 平铺型纹理的常量键（与画布尺寸无关） */
    private const val TILED_KEY = -7L

    // 与 VisualizerRandom 同族的 LCG 常量（本地私有，避免消耗渲染器的随机序列）
    private fun lcg(state: UInt): UInt = state * 1664525u + 1013904223u
    private fun lcgFloat(state: UInt): Float = ((state shr 8) and 0xFFFFFFu).toFloat() / 16777216f

    // ── sin 查表（v2.38.4 烘焙提速；抄 SeasideWaves.kt:197-203 / :334 的同构实现）──────
    //
    // ⚠️ **为什么**：`kotlin.math.sin(x: Float)` 展开成 `(float) java.lang.Math.sin(x.toDouble())`
    //    —— 每次都是**双精度 libm 调用**（FDLIBM，含参数规约 + 象限归约 + 多项式求值）。
    //    而 5 个行填充器**每像素调用 2~4 次**（[waterRow] 4 次、[fogRow]/[plasmaRow] 3 次、
    //    [causticRow] 2 次、[paperRow] 2 次，其中一次还是更贵的 `sin(Double)`）
    //    ⇒ 1080p 单张全屏纹理 ≈ 210 万像素 ⇒ 400~800 万次 libm 调用。
    //    真机埋点拟合：sin 占烘焙时间的 **66~73%**（WATER 3501ms / FOG 2861ms / PAPER 2398ms）。
    //
    // ⛔ **精度不变量**：N=4096 + **四舍五入**取下标 ⇒ 最大相位量化误差 = `π/N ≈ 7.67e-4 rad**
    //    ⇒ 值域误差 `sin` 局部导数 ≤ 1 ⇒ `|Δsin| ≤ 7.67e-4` ⇒ 对 8-bit 输出 **< 0.2 LSB**。
    //    门禁：`ProceduralTextureSinLutTest`（≥2^20 随机相位，|Δ| ≤ 1e-3）。
    //
    // ⚠️ **「不改画面观感」的准确口径**（⛔ **不是逐位等价** —— 量化必然带来抖动）：
    //    各行填充器末尾都是 `.toInt()`，任何量化扰动都会让**极少数**像素跨 ±1 档。
    //    实测 WATER（1920×8 采样）：截断取整时 0.78% 像素跨档 ⇒ 改四舍五入后 **0.36%**
    //    （恰好减半 ⇒ 抖动源确认为量化本身，不是实现 bug）。
    //    ⇒ 即 0.36% 的像素上 alpha 差 1/255，而 WATER 自身 alpha 上限只有 0.137 ⇒ 肉眼不可见。
    //    门禁把**幅度（±1 档）**与**比例（< 1%）**都锁死；⛔ 「逐像素零差异」只有不用 LUT 才可能。
    private const val SIN_LUT_N = 4096
    private const val SIN_LUT_MASK = SIN_LUT_N - 1

    /**
     * 索引标度 = N/(2π)（与 [SIN_LUT_N] 同族，保持与 SeasideWaves 的常量级一致）。
     * ⛔ **只用于建表**（一次性）；查表用同值的 [SIN_LUT_SCALE_F] 走单精度乘法。
     */
    private val SIN_LUT_SCALE = SIN_LUT_N / (Math.PI * 2.0)

    /**
     * ⛔ 查表路径**必须用单精度标度** —— 用 `Double` 会把热循环里的 `a * SCALE` 重新变成
     * 「f2d 转换 + 双精度乘法 + d2i 转换」，正是本优化要消灭的那一类开销。
     * 精度核对：热循环相位最大 `|a| ≈ 302`（[paperRow] 的 `x·2π/40`）⇒ 下标 ≈ 1.97e5，
     * Float 24 位尾数在该量级的 ulp ≈ 0.015 index ⇒ 远小于 1 个 LUT 格。
     */
    private val SIN_LUT_SCALE_F = SIN_LUT_SCALE.toFloat()

    /** `SIN_LUT[i] = sin(i / SCALE)`；多存 1 项（i == N ⇒ sin(2π) ≈ 0）避免回绕处的毛刺。 */
    private val SIN_LUT = FloatArray(SIN_LUT_N + 1) { sin(it / SIN_LUT_SCALE).toFloat() }

    /**
     * 单精度 `sin` 查表：**四舍五入**取下标 + `and MASK` 回绕（等价于 `sin`，但带 LUT 量化）。
     *
     * ⚠️ **为什么是「四舍五入」而不是抄 [SeasideWaves.fsin] 的「截断向零」**：
     *    `toInt()` 是**截断向零**，下标误差可达**满 1 格** ⇒ 相位误差 `2π/N = 1.534e-3 rad`
     *    ⇒ 实测 `|Δsin|` 上界 **1.53e-3**（`ProceduralTextureSinLutTest` ② 首轮实测复现：
     *    0.0015338007），且会让 WATER 的 alpha 在 ~0.8% 的像素上跨 ±1 档。
     *    先按符号加/减 `0.5f` 再截断 ⇒ 误差减半到 **0.5 格** ⇒ 相位误差 `π/N ≈ 7.67e-4 rad`
     *    ⇒ `|Δsin| ≤ 7.67e-4`，满足门禁的 `1e-3`。
     *    代价是热循环里多一次比较 + 一次加法 —— 相对一次 libm `sin` 仍便宜两个数量级。
     *
     * ⛔ 有效相位域 `|a| ≤ ~3.3e6`（再大则 `(a·SCALE±0.5).toInt()` 溢出 Int）。
     *    本文件所有调用点的 `|a| ≤ 302`（[paperRow] @1920），余量充足。
     *
     * ⚠️ 只在**烘焙期**调用（`ensure`/`ensureFullscreenOnly` 的逐行填充），⛔ **不在 draw 路径上**。
     * ⛔ 返回值与 `sin(a: Float)` **不完全相同**（差 ≤ 7.67e-4），这是**有意的**：
     *    门禁 `ProceduralTextureSinLutTest` 锁住误差上界，`LightBeamsTest` ⑩ 的 FOG
     *    统计区间门禁余量充足（实测全部通过）。
     */
    internal fun fsin(a: Float): Float {
        val t = a * SIN_LUT_SCALE_F
        return SIN_LUT[((if (t >= 0f) t + 0.5f else t - 0.5f).toInt()) and SIN_LUT_MASK]
    }

    /** [SIN_LUT] 的长度（= 分辨率 + 1）；仅供门禁断言「表确实是 4096 项」。 */
    internal fun sinLutSize(): Int = SIN_LUT.size

    /**
     * 按画布尺寸准备全屏型纹理（STARFIELD / PAPER / WATER / CAUSTIC / PLASMA / FOG）。
     * 平铺型（GRAIN / SCANLINE）在此首次调用时生成一次。
     *
     * ⚠️ 必须在 **`onEnter` 或尺寸变化时**调用，⛔ 不得在 `draw` 内调用。
     * （⚠️ 已迁移的效果里有若干处例外，登记在 §12.4；⛔ 本注释刻意不写具体处数 ——
     * 那种数字会随任务推进过期。）
     */
    fun ensure(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        // H4 修复：早退判据从「尺寸相同」改为「逐槽就绪」（ensureFullscreenOnly
        // 只烘 1 张时其余 5 张为 null，这里必须继续烘而不是整体返回）
        if (allSlotsReady(w, h)) return
        ensuredW = w
        ensuredH = h

        // 平铺型（与画布尺寸无关，只生成一次）
        ensureTiledSlots()

        // 全屏型：键含 (w,h) —— 尺寸变化自动重建（§C4 O2 的缓存键纪律）
        val fullKey = fullscreenKey(w, h)
        // ⭐ STARFIELD 的一次性中间量（v2.38.4 烘焙提速）：**每张纹理算一次**。
        //    旧写法把 `starLayout(w, h)` 写在逐行 lambda 里 ⇒ 被算 **1080** 次
        //    （每次 230 颗星迭代 + 分配 690 个 float = 2.7 KB ⇒ 一次 ensure 多分配 2.9 MB）。
        //    ⛔ 提到这里但**不改下面那六行的形状**（LightBeamsTest / PlasmaFlowTest 的源码
        //    扫描门禁锁死的是 `ensureFullscreen(Id.X, w, h, fullKey)` 这段字面量）。
        val starPlan = starBake(w, h)
        ensureFullscreen(Id.STARFIELD, w, h, fullKey) { row, y -> starfieldRow(row, y, w, h, starPlan) }
        ensureFullscreen(Id.PAPER, w, h, fullKey) { row, y -> paperRow(row, y, w, h) }
        ensureFullscreen(Id.WATER, w, h, fullKey) { row, y -> waterRow(row, y, w, h) }
        ensureFullscreen(Id.CAUSTIC, w, h, fullKey) { row, y -> causticRow(row, y, w, h) }
        ensureFullscreen(Id.PLASMA, w, h, fullKey) { row, y -> plasmaRow(row, y, w, h) }
        ensureFullscreen(Id.FOG, w, h, fullKey) { row, y -> fogRow(row, y, w, h) }
    }

    /**
     * ⭐ 只确保 [id] 这一张**全屏型**纹理（其余全屏型**不**生成）。
     *
     * ⚠️ **为什么需要它**：[ensure] 一次生成 [FULLSCREEN_IDS] 的**全部 6 张**全屏纹理，
     * 每张都是 `Bitmap.createBitmap(w, h)` + 逐行 Kotlin 像素运算 + JNI `setPixels` ——
     * 1920×1080 × 6 ≈ **1240 万像素**的逐像素数学 + **6480 次** JNI `setPixels`，
     * 而且**同步**发生在**首帧**（`ctx.canvasSize` 在 `onEnter` 时还是 `Size.Zero`，
     * `ensure` 只能等到第一次 `drawContent` 才被触发，见 `VisualizerStage.kt:192`）。
     *
     * ⇒ 只用 1 张全屏纹理的效果（星空星轨 E42 只用 [Id.STARFIELD]）若调 [ensure]，
     * 会为 5 张**永远不画**的纹理付出全部代价 —— 真机实测冷启动首帧黑屏 **6369 ms**。
     *
     * 语义与 [ensure] 一致（同样的 `ensuredW`/`ensuredH` 记账、同样的 [fullscreenKey] 键），
     * 差别只在「生成哪几张」。⛔ 对**平铺型**（[Id.GRAIN] / [Id.SCANLINE]）调用会抛
     * [IllegalArgumentException] —— 那两类与画布尺寸无关，本就该由 [ensure] 生成。
     */
    fun ensureFullscreenOnly(id: Id, w: Int, h: Int) {
        require(id in FULLSCREEN_IDS) {
            "ensureFullscreenOnly 只接受全屏型 Id，$id 是平铺型（请改用 ensure）"
        }
        if (w <= 0 || h <= 0) return
        val key = fullscreenKey(w, h)
        // H4 修复：只按本 Id 自己的槽位判断，⛔ 不再以 ensuredW/ensuredH 早退——
        // 那会让同尺寸下后续 ensure 误以为「整组已烘」而跳过其余 5 张。
        val idx = id.ordinal * VARIANTS
        if (keys[idx] == key && slots[idx] != null) return
        ensuredW = w
        ensuredH = h
        ensureFullscreen(id, w, h, key, id.rowFiller(w, h))
    }

    /**
     * ⭐ 只确保**平铺型**纹理（GRAIN ×8 / SCANLINE ×1），**不**碰任何全屏型。
     *
     * ⚠️ **为什么需要它**：[OverlayFx.drawGrain] / [OverlayFx.drawScanlines] 直接读
     * `tile(Id.GRAIN, v)` / `tile(Id.SCANLINE)`，读不到就 **静默 `return`**（不报错、不画）。
     * 而平铺槽的**唯一**生产者是 [ensure] 内部的 [ensureTiledSlots] ⇒ 任何改用
     * [ensureFullscreenOnly] 的效果，其 `postFx.grain` / `postFx.scanline` 胶片颗粒层会
     * **静默消失**（E42 星空星轨就这样坏过一次：只调 `ensureFullscreenOnly(STARFIELD)`，
     * 却在 `postFx` 里仍配着 `grain = 0.026f`）。
     * ⇒ 改用 [ensureFullscreenOnly] 的效果必须**同时**调本函数。
     *
     * 成本可忽略：GRAIN 8×128×128 + SCANLINE 1×3 ≈ **13.1 万像素**，约为**一张**全屏纹理
     * （1920×1080 ≈ 207 万像素）的 **6%**、6 张全屏纹理的 **1%**；且 [ensureTiledSlots]
     * 逐槽跳过已生成项 ⇒ 实际只在首次（每次效果切入后）付一次。
     *
     * ⛔ **不接收 `(w, h)`、不写 [ensuredW]/[ensuredH]** —— 平铺型与画布尺寸无关，
     * 那对字段只记全屏型尺寸；[ensure] 的早退另有逐槽判据（[allSlotsReady]）兜底，
     * 不依赖本函数写没写尺寸。
     */
    internal fun ensureTiled() {
        ensureTiledSlots()
    }

    /** 全屏型 Id 清单（⛔ **仅引用既有 [Id]**、**不新增枚举项**、不改动 `Id.entries` 顺序）。 */
    private val FULLSCREEN_IDS = listOf(
        Id.STARFIELD, Id.PAPER, Id.WATER, Id.CAUSTIC, Id.PLASMA, Id.FOG,
    )

    /** 全屏型缓存键 = `(w << 32) | h`（尺寸变化自动重建，§C4 O2 的缓存键纪律）。 */
    private fun fullscreenKey(w: Int, h: Int): Long = (w.toLong() shl 32) or h.toLong()

    /**
     * 该 Id 的逐行像素填充器（[ensureFullscreenOnly] 专用）。
     *
     * ⚠️ 与 [ensure] 里那六行 `ensureFullscreen(Id.X, …) { … }` 是**同一组 lambda 的两份拷贝**，
     *    刻意不复用：[ensure] 的逐行展开是 `LightBeamsTest`（`ensureFullscreen(Id.FOG, w, h, fullKey)`）
     *    与 `PlasmaFlowTest`（`ensureFullscreen(Id.PLASMA,`）的**源码扫描门禁**锁死的字面量，
     *    改成循环或改写调用形状都会让那两条门禁**静默**失效。
     *    ⇒ ⛔ 增删全屏型 Id 时**两处都要改**；`ProceduralTextureRecycleTest` 的
     *    「六个全屏 Id 逐个走 ensureFullscreenOnly 都能烘出」一条专门防漏。
     */
    private fun Id.rowFiller(w: Int, h: Int): (IntArray, Int) -> Unit = when (this) {
        // ⭐ 同 [ensure]：星位表 + 行桶是**一次性**中间量。[starBake] 作为 `starRowFiller`
        //    的**实参**在 `when` 求值时（= 建 lambda 时）只求值一次 ⇒ [starLayout] 只调 1 次。
        //    ⛔ 若把 `starBake(w, h)` 写进 lambda 体就会退化成**每行 1 次**（1080p ⇒ 1080 次）——
        //    门禁 `ProceduralTextureStarBakeOnceTest` 的计数断言专门防这个静默回退。
        Id.STARFIELD -> starRowFiller(starBake(w, h))
        Id.PAPER -> { row, y -> paperRow(row, y, w, h) }
        Id.WATER -> { row, y -> waterRow(row, y, w, h) }
        Id.CAUSTIC -> { row, y -> causticRow(row, y, w, h) }
        Id.PLASMA -> { row, y -> plasmaRow(row, y, w, h) }
        Id.FOG -> { row, y -> fogRow(row, y, w, h) }
        // 平铺型不会走到这里（ensureFullscreenOnly 已 require 拦截；ensure 也不会遍历它们）
        Id.GRAIN, Id.SCANLINE -> { _, _ -> }
    }

    private inline fun ensureFullscreen(
        id: Id, w: Int, h: Int, key: Long, rowFiller: (IntArray, Int) -> Unit,
    ) {
        val idx = id.ordinal * VARIANTS
        if (keys[idx] == key && slots[idx] != null) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            rowFiller(row, y)
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        slots[idx] = bmp.asImageBitmap()
        keys[idx] = key
    }

    /**
     * 取纹理。`draw` 期唯一入口，**零分配**（数组下标）。
     * @return 未 [ensure] 时返回 `null`（调用方须判空后跳过绘制）
     */
    fun tile(id: Id, variant: Int = 0): ImageBitmap? =
        slots[id.ordinal * VARIANTS + (variant and (VARIANTS - 1))]

    /**
     * 球面贴图（**移动主体的球面光照**，见 §15.4-A4 的设计说明）。
     *
     * 贴图内容：① 径向衰减（中心 `towardWhite(base,0.35f)` → 边缘 `darken(base,0.45f)`）；
     * ② 边缘光 rim（背光侧一圈 `alpha 0.22` 的亮弧，光源方向固定 315° ⇒ 亮弧在 135° 一侧）。
     * ⛔ **不含方向性高光** —— 高光由调用方按 `Shading2D.specular(法线)` 单独画 1 个小圆，
     * 这样行星绕日公转时高光方向才能随位置变化（§G3）。
     *
     * 缓存键 = `base.toArgb()`；颜色种类固定有限（行星/原子 ≤ 16）。
     * 首次遇到新颜色会分配一次（建议在 `onEnter` 预热）；命中缓存后零分配。
     */
    fun sphereSprite(base: Color): ImageBitmap? {
        val argb = base.toArgb()
        for (i in 0 until sphereCount) {
            if (sphereColors[i] == argb) return sphereTiles[i]
        }
        if (sphereCount >= sphereColors.size) return null   // 缓存满（颜色种类固定有限）
        val bmp = makeBitmap(SPHERE_PX, SPHERE_PX, spherePixels(argb))
        sphereColors[sphereCount] = argb
        sphereTiles[sphereCount] = bmp
        sphereCount++
        return bmp
    }

    /** 释放全部纹理。可安全重复调用（与 `MilkdropRenderer.releaseBuffers` 同语义）。 */
    fun release() {
        for (i in slots.indices) {
            slots[i]?.asAndroidBitmap()?.recycle()
            slots[i] = null
            keys[i] = 0L
        }
        for (i in 0 until sphereCount) {
            sphereTiles[i]?.asAndroidBitmap()?.recycle()
            sphereTiles[i] = null
        }
        sphereCount = 0
        ensuredW = 0
        ensuredH = 0
    }

    // ─────────────────────────────────────────────────────────────
    // 纯像素生成（JVM 可测；与 android.graphics 完全解耦）
    // ─────────────────────────────────────────────────────────────

    /** GRAIN：128×128 ×8 变体。每像素 `alpha = rand() × 26` 的白色灰度噪点，每变体独立 LCG 种子 */
    internal fun grainPixels(variant: Int): IntArray {
        val n = GRAIN_TILE * GRAIN_TILE
        val out = IntArray(n)
        var st = 0x9E3779B9u + (variant.toUInt() * 0x85EBCA6Bu) + 1u
        for (i in 0 until n) {
            st = lcg(st)
            val a = (lcgFloat(st) * 26f).toInt().coerceIn(0, 26)
            out[i] = if (a == 0) 0 else (a shl 24) or 0x00FFFFFF
        }
        return out
    }

    /** SCANLINE：1×3。第 0 行黑 a=0.16、第 1 行 a=0.06、第 2 行全透明（§15.2.3 生成规格表） */
    internal fun scanlinePixels(): IntArray {
        val a0 = (0.16f * 255f + 0.5f).toInt()   // 41
        val a1 = (0.06f * 255f + 0.5f).toInt()   // 15
        return intArrayOf(
            a0 shl 24,            // 黑
            a1 shl 24,            // 黑（更淡）
            0                     // 透明
        )
    }

    /**
     * 球面贴图：径向衰减 + 背光侧 rim 亮弧。
     * 光源固定 315°（左上，[Shading2D.LIGHT_ANGLE_DEG]）⇒ rim 亮弧在 135° 方向一侧。
     */
    internal fun spherePixels(baseArgb: Int): IntArray {
        val base = Color(baseArgb)
        val center = VisualizerMath.towardWhite(base, 0.35f)
        val edge = VisualizerMath.darken(base, 0.45f)
        val cr = (center.toArgb() shr 16) and 0xFF
        val cg = (center.toArgb() shr 8) and 0xFF
        val cb = center.toArgb() and 0xFF
        val er = (edge.toArgb() shr 16) and 0xFF
        val eg = (edge.toArgb() shr 8) and 0xFF
        val eb = edge.toArgb() and 0xFF
        val half = SPHERE_PX / 2f
        val out = IntArray(SPHERE_PX * SPHERE_PX)
        // rim：亮弧中心角 135°，半宽 55°；只出现在边缘环带 [0.80, 1.0]
        val rimCenterRad = Math.toRadians(135.0)
        for (y in 0 until SPHERE_PX) {
            val dy = (y - half) / half
            for (x in 0 until SPHERE_PX) {
                val dx = (x - half) / half
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                val idx = y * SPHERE_PX + x
                if (d > 1f) { out[idx] = 0; continue }
                val t = d.coerceIn(0f, 1f)
                var r = (cr + (er - cr) * t).toInt().coerceIn(0, 255)
                var g = (cg + (eg - cg) * t).toInt().coerceIn(0, 255)
                var b = (cb + (eb - cb) * t).toInt().coerceIn(0, 255)
                var a = 255
                if (d >= 0.80f) {
                    // 角度距离（atan2 的 y 向下为正；屏幕极角 0° 指右、顺时针，与 VisualizerMath.polar 一致）
                    var ang = kotlin.math.atan2(dy, dx)
                    if (ang < 0) ang += (2.0 * Math.PI).toFloat()
                    var diff = Math.abs(ang - rimCenterRad).toFloat()
                    if (diff > Math.PI.toFloat()) diff = (2.0 * Math.PI).toFloat() - diff
                    if (diff <= Math.toRadians(55.0).toFloat()) {
                        val rimA = 0.22f * 255f * (1f - (d - 0.80f) / 0.20f * 0.5f)
                        val k = rimA / 255f
                        r = (r + (255 - r) * k).toInt().coerceIn(0, 255)
                        g = (g + (255 - g) * k).toInt().coerceIn(0, 255)
                        b = (b + (255 - b) * k).toInt().coerceIn(0, 255)
                    }
                }
                out[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /**
     * STARFIELD 星位表：`[x, y, tier] × n`（归一化坐标 0..1；tier ∈ {0,1,2}）。
     * 三档星：半径 0.6 / 1.0 / 1.6 px，alpha 0.20 / 0.35 / 0.60，LCG 均匀撒点，
     * 总数 = w×h/9000（§15.2.3 生成规格表）。
     */
    internal fun starLayout(w: Int, h: Int): FloatArray {
        val total = (w.toFloat() * h / 9000f).toInt().coerceAtLeast(3)
        val out = FloatArray(total * 3)
        var st = 0x5EEDF00Du
        // 🔬 门禁计数（v2.38.4）：⛔ 不影响本函数的任何输出（LCG 序列与 [out] 一字未改），
        //    只是让「[starBake] 每张纹理只调 1 次」这条判据可测（旧写法是 1080 次）。
        starLayoutCalls++
        for (i in 0 until total) {
            st = lcg(st); val x = lcgFloat(st)
            st = lcg(st); val y = lcgFloat(st)
            st = lcg(st)
            val tier = when {
                lcgFloat(st) < 0.60f -> 0      // 60% 小星
                else -> {                       // ⛔ 必须推进状态后再判定中星（旧写法同状态连读两次 ⇒ 中星永不可达）
                    st = lcg(st)
                    if (lcgFloat(st) < (0.25f / 0.40f)) 1 else 2   // 剩余 40% 中分：25% 中星 / 15% 亮星
                }
            }
            out[i * 3] = x
            out[i * 3 + 1] = y
            out[i * 3 + 2] = tier.toFloat()
        }
        return out
    }

    private val STAR_R = floatArrayOf(0.6f, 1.0f, 1.6f)
    private val STAR_A = intArrayOf((0.20f * 255).toInt(), (0.35f * 255).toInt(), (0.60f * 255).toInt())

    // ══════════════ STARFIELD 行区间分桶（v2.38.4 烘焙提速） ══════════════

    /**
     * 一次 [starBuckets] 的扁平 CSR 结果：**行 `y` 的星下标区间 = `[bucketStart[y], bucketStart[y+1])`**。
     *
     * ⛔ 零逐行分配（每张纹理烘焙前一次性分配 3 个数组，共约 13 KB @1080p）。
     */
    internal class StarBuckets(
        @JvmField val bucketStart: IntArray,
        @JvmField val items: IntArray,
    ) {
        /** 第 `y` 行实际重放的星数（门禁与诊断用；生产路径不读）。 */
        fun count(y: Int): Int = bucketStart[y + 1] - bucketStart[y]
    }

    /**
     * ⭐ 把 [layout] 的星按 y 跨度**预先分桶**，让每行只重放「可能命中本行」的星。
     *
     * ⚠️ **为什么**（1080p 实测）：[starLayout] 产 230 颗星、`Σ(2r) ≈ 460`
     *    ⇒ 平均每行只有 **~0.43** 颗星命中；而旧写法每行都要遍历**全部 230** 颗做 y 判定
     *    ⇒ 1080 × 230 = **248,400** 次判定里 **99.8% 是白做**。
     *    装桶后总星访问次数 ≈ `Σ(2·ceil(r)+3) ≈ 1.1k` ⇒ **两个数量级**的降幅。
     *
     * 🔒 **等价性三不变量**（由 `ProceduralTextureStarfieldBucketTest` 在 1920×1080
     *    上逐像素锁死；改动本函数必须让那条门禁继续通过）：
     *
     * ① **桶是保守超集**：真实命中行区间是 `y - sy ∈ [-r, r]`，即 `y ∈ [sy-r, sy+r]`。
     *    本实现取 `floor(sy-r) - 1` … `ceil(sy+r) + 1`，两端各留 1 行吸收 float 舍入
     *    （`sy = layout.y * h`、阈值比较全在 Float 下，误差上界 `h·2⁻²⁴ ≈ 6.4e-5 ≪ 1`）
     *    ⇒ **落在桶外的行，旧判定必然 `continue`**。
     * ② **桶内保留原判定**：[starfieldRow] 里那两行 `if (dy < -r || dy > r) continue` 一字未改
     *    ⇒ 命中集合逐像素一致。
     * ③ **行内顺序与旧写法一致**：pass 2 按**星下标升序**回填。重叠星是**后写覆盖**（不是混合）
     *    ⇒ 顺序一旦改变，像素就会变。
     *
     * ⚠️ 桶的上下界还额外**夹到 `[0, h-1]`** —— 超出画布的行旧写法也永远不会渲染
     *    （`ensureFullscreen` 只遍历 `0 until h`），夹取不丢任何有效行。
     */
    internal fun starBuckets(w: Int, h: Int, layout: FloatArray): StarBuckets {
        val n = layout.size / 3
        // 每颗星的保守整数行窗口 `[yLo, yHi]`（不变量 ①）
        val win = IntArray(n * 2)
        val counts = IntArray(h + 1)
        for (i in 0 until n) {
            val sy = layout[i * 3 + 1] * h
            val r = STAR_R[layout[i * 3 + 2].toInt()]
            var yLo = kotlin.math.floor(sy - r).toInt() - 1
            var yHi = kotlin.math.ceil(sy + r).toInt() + 1
            if (yLo < 0) yLo = 0
            if (yHi > h - 1) yHi = h - 1
            win[i * 2] = yLo
            win[i * 2 + 1] = yHi
            var y = yLo
            while (y <= yHi) { counts[y + 1]++; y++ }
        }
        // prefix sum ⇒ `counts[y]` 变成第 y 行桶的起点（末尾哨兵 `counts[h]` = 总数）
        for (y in 1..h) counts[y] += counts[y - 1]
        // pass 2：按星下标升序回填 ⇒ 行内顺序与旧写法一致（不变量 ③）
        val items = IntArray(if (h > 0) counts[h] else 0)
        val cursor = IntArray(h)
        for (i in 0 until n) {
            val yLo = win[i * 2]
            val yHi = win[i * 2 + 1]
            var y = yLo
            while (y <= yHi) { items[counts[y] + cursor[y]] = i; cursor[y]++; y++ }
        }
        return StarBuckets(counts, items)
    }

    /** 一张 STARFIELD 纹理烘焙所需的**全部一次性**中间量（⛔ 每张纹理只算一次，见 [starBake]）。 */
    internal class StarBake(
        @JvmField val w: Int,
        @JvmField val h: Int,
        @JvmField val layout: FloatArray,
        @JvmField val buckets: StarBuckets,
    )

    /**
     * 烘一张 STARFIELD 前的准备：**[starLayout] 只调 1 次**（旧写法在逐行 lambda 里调了 **1080** 次，
     * 每次迭代 230 颗星并分配 690 个 float ⇒ 1080 × 230 次 LCG + 1080 个 2.7 KB 数组）。
     *
     * ⛔ 调用点**只有两处**：[ensure] 的六行之一与 [rowFiller]；两处都必须是「一张纹理算一次」，
     *    ⛔ 不得放回逐行 lambda。门禁 `ProceduralTextureStarBakeOnceTest` 计数断言。
     */
    internal fun starBake(w: Int, h: Int): StarBake {
        val layout = starLayout(w, h)
        return StarBake(w, h, layout, starBuckets(w, h, layout))
    }

    /**
     * STARFIELD 的逐行填充器。
     *
     * ⚠️ [bake] 是**形参**而不是在 lambda 体里现算 —— 这样 [rowFiller] 的 `when` 分支
     *    `starRowFiller(starBake(w, h))` 只在**建 lambda 时**求值一次。
     */
    private fun starRowFiller(bake: StarBake): (IntArray, Int) -> Unit =
        { row, y -> starfieldRow(row, y, bake.w, bake.h, bake) }

    /** 🔬 门禁用：进程内 [starLayout] 被调次数。⛔ 不参与任何烘焙判据，⛔ [release] 不清零。 */
    private var starLayoutCalls = 0

    /** 🔬 门禁读数：[starBake] 里 [starLayout] 的累计调用次数。 */
    internal fun starLayoutCallCount(): Int = starLayoutCalls

    /** 🔬 门禁复位（测试专用；⛔ 生产路径不得调用）。 */
    internal fun resetStarLayoutCallCount() { starLayoutCalls = 0 }

    /** STARFIELD 的一行光栅化（把 [StarBake.buckets] 里落在本行的星画进 [out]） */
    internal fun starfieldRow(out: IntArray, y: Int, w: Int, h: Int, bake: StarBake) {
        java.util.Arrays.fill(out, 0)
        val layout = bake.layout
        val end = bake.buckets.bucketStart[y + 1]
        var p = bake.buckets.bucketStart[y]
        while (p < end) {
            val i = bake.buckets.items[p]
            p++
            val sx = layout[i * 3] * w
            val sy = layout[i * 3 + 1] * h
            val tier = layout[i * 3 + 2].toInt()
            val r = STAR_R[tier]
            val dy = y - sy
            if (dy < -r || dy > r) continue      // ⛔ 不变量 ②：桶内仍走原判定
            val dx = kotlin.math.sqrt((r * r - dy * dy).coerceAtLeast(0f))
            val x0 = (sx - dx).toInt().coerceIn(0, w - 1)
            val x1 = (sx + dx).toInt().coerceIn(0, w - 1)
            val argb = (STAR_A[tier] shl 24) or 0x00FFFFFF
            for (x in x0..x1) out[x] = argb
        }
    }

    /**
     * PAPER 的低频相位步长 = `2π/40`（**单精度常量**）。
     *
     * ⚠️ 原写法是内层的 `2.0 * Math.PI / 40.0` —— `Double` 连乘，**每像素重算一遍步长**
     *    再喂给 `sin(Double)`（全表最贵的单点）。这里折成 `const val Float`，
     *    值与 `2.0*Math.PI/40.0` 在 Float 精度内一致（0.15707964f vs 0.15707963268…）。
     */
    private const val PAPER_W = 0.15707964f

    /** PAPER：低频 `sin` 交叉纹（周期 40px，幅度 ±4 灰阶）+ 高频噪点，半透明 */
    internal fun paperRow(out: IntArray, y: Int, w: Int, h: Int) {
        var st = 0xC0FFEEu + y.toUInt()
        // ⛔ 固定 y 时 `sin(y·2π/40)` 是**行常量** —— 提到 x 循环外，每行算 1 次而非每像素 1 次。
        //    乘法顺序保持与原式**逐位同构**（`(sinX * sinY) * 4f`），避免引入舍入差异。
        val sinY = fsin(y * PAPER_W)
        for (x in 0 until w) {
            st = lcg(st)
            val low = fsin(x * PAPER_W) * sinY * 4f
            val noise = (lcgFloat(st) - 0.5f) * 4f
            val v = (low + noise).coerceIn(-6f, 6f)
            val a = (kotlin.math.abs(v) / 6f * 30f).toInt().coerceIn(0, 30)
            out[x] = if (v >= 0f) (a shl 24) or 0x00FFFFFF else (a shl 24)
        }
    }

    /** WATER：4 组不同频率/方向的 `sin` 叠加 → 水纹灰度，alpha 上限 0.14 */
    private val WATER_A = (0.14f * 255f).toInt()   // 35

    internal fun waterRow(out: IntArray, y: Int, w: Int, h: Int) {
        for (x in 0 until w) {
            val fx = x.toFloat()
            val fy = y.toFloat()
            val v = (fsin(fx * 0.021f + fy * 0.008f) +
                fsin(fx * 0.007f - fy * 0.017f) +
                fsin(fx * 0.013f + fy * 0.024f) +
                fsin(fx * -0.031f + fy * 0.004f)) * 0.25f   // -1..1
            val g = (128f + v * 60f).toInt().coerceIn(0, 255)
            val a = (WATER_A * kotlin.math.abs(v)).toInt().coerceIn(0, WATER_A)
            out[x] = (a shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    /** CAUSTIC：2 组 `abs(sin)` 相乘形成的细亮网（焦散），alpha 上限 0.10 */
    private val CAUSTIC_A = (0.10f * 255f).toInt()   // 25

    internal fun causticRow(out: IntArray, y: Int, w: Int, h: Int) {
        for (x in 0 until w) {
            val fx = x.toFloat()
            val fy = y.toFloat()
            val v = kotlin.math.abs(fsin(fx * 0.045f + fy * 0.018f)) *
                kotlin.math.abs(fsin(fx * -0.022f + fy * 0.051f))
            val net = (v - 0.72f).coerceAtLeast(0f) / 0.28f   // 只留网线
            val a = (CAUSTIC_A * net).toInt().coerceIn(0, CAUSTIC_A)
            out[x] = (a shl 24) or 0x00EAF6FF.toInt()
        }
    }

    /**
     * PLASMA：**3 通道**低频 `sin` 场合成（R / G / B 各一条，相位互差 120°）
     * ⇒ 大尺度彩色云团；alpha 按**三通道均值亮度**取（暗区近乎全透明）
     * ⇒ 只留亮部成"等离子丝"。alpha 上限 0.70（调用方再乘 `0.16 + energy*0.10`）。
     *
     * ⚠️ 每像素 **3 次 `sin` 查表**（比 [waterRow] 的 4 次更省）。本 tile 会在每次
     * `ensure(w, h)` 里**全屏生成一次**，是 `ensure` 的固定成本项之一。
     */
    private val PLASMA_A = (0.70f * 255f).toInt()   // 178

    /** G / B 通道的相位偏移（120° / 240°），使三通道在空间上错开 ⇒ 彩色云团而非灰阶 */
    private const val PLASMA_PHASE_G = 2.0943951f
    private const val PLASMA_PHASE_B = 4.1887902f

    internal fun plasmaRow(out: IntArray, y: Int, w: Int, h: Int) {
        val fy = y.toFloat()
        for (x in 0 until w) {
            val fx = x.toFloat()
            val r = (fsin(fx * 0.0113f + fy * 0.0071f) * 0.5f + 0.5f) * 255f
            val g = (fsin(fx * 0.0137f - fy * 0.0094f + PLASMA_PHASE_G) * 0.5f + 0.5f) * 255f
            val b = (fsin(fx * 0.0091f + fy * 0.0126f + PLASMA_PHASE_B) * 0.5f + 0.5f) * 255f
            val rr = r.toInt().coerceIn(0, 255)
            val gg = g.toInt().coerceIn(0, 255)
            val bb = b.toInt().coerceIn(0, 255)
            val lum = (r + g + b) / 765f                       // 0..1（三通道均值）
            val a = (PLASMA_A * lum).toInt().coerceIn(0, PLASMA_A)
            out[x] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    /**
     * FOG：**3 组超低频 `sin`** 叠加 → 大尺度灰白雾团。
     *
     * ① **只留亮部**（`v > [FOG_FLOOR]`）⇒ 暗区完全透明，得到「一团一团」的雾而不是
     *    均匀灰幕；② 灰阶随浓度上抬（`196 → 255`）⇒ 浓处更白，有厚薄感。
     *
     * 波长约 `2π/0.0268 ≈ 234 px` / `2π/0.0183 ≈ 343 px` / `2π/0.0094 ≈ 668 px`
     * ⇒ 1080p 上可见 3–5 团，与 `WATER`（同样 3–4 组 `sin`）同族但**更慢更团**。
     *
     * ⚠️ alpha 上限 **0.90**（调用方再乘 `FOG_ALPHA = 0.12` ⇒ 实际叠加 ≤ 0.108）。
     * 与 [PLASMA_A] 的 0.70 口径不同是**有意**的：PLASMA 的调用方乘 0.16–0.26，
     * 本 tile 的调用方只乘 0.12 ⇒ 上限抬高才能落到同一可见区间。
     *
     * ⚠️ 每像素 **3 次 `sin` 查表**（与 [plasmaRow] 同量级）。本 tile 会在每次
     * `ensure(w, h)` 里**全屏生成一次**，是 `ensure` 的固定成本项之一。
     */
    private val FOG_A = (0.90f * 255f).toInt()   // 229

    /** 第三组的相位偏移（黄金角 ⇒ 三组在空间上错开，不成规则波纹） */
    private const val FOG_PHASE = 2.3999632f

    /** 只保留 `v > FOG_FLOOR` 的亮部；负值 ⇒ 过半面积有雾（不然整屏会太干净） */
    private const val FOG_FLOOR = -0.05f

    internal fun fogRow(out: IntArray, y: Int, w: Int, h: Int) {
        val fy = y.toFloat()
        for (x in 0 until w) {
            val fx = x.toFloat()
            val v = (fsin(fx * 0.0183f + fy * 0.0121f) +
                fsin(fx * -0.0094f + fy * 0.0231f) +
                fsin(fx * 0.0268f + fy * 0.0043f + FOG_PHASE)) / 3f      // -1..1
            val net = ((v - FOG_FLOOR) / (1f - FOG_FLOOR)).coerceIn(0f, 1f)
            val a = (FOG_A * net).toInt().coerceIn(0, FOG_A)
            val g = (196f + net * 59f).toInt().coerceIn(0, 255)         // 浓处更白
            out[x] = (a shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    private fun makeBitmap(w: Int, h: Int, pixels: IntArray): ImageBitmap =
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

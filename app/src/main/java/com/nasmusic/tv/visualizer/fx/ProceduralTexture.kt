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
 * ⛔ 生命周期：由 `VisualizerStage` 在舞台离开时调一次 [release]；
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

    /** 平铺型纹理的常量键（与画布尺寸无关） */
    private const val TILED_KEY = -7L

    // 与 VisualizerRandom 同族的 LCG 常量（本地私有，避免消耗渲染器的随机序列）
    private fun lcg(state: UInt): UInt = state * 1664525u + 1013904223u
    private fun lcgFloat(state: UInt): Float = ((state shr 8) and 0xFFFFFFu).toFloat() / 16777216f

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
        if (ensuredW == w && ensuredH == h) return
        ensuredW = w
        ensuredH = h

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

        // 全屏型：键含 (w,h) —— 尺寸变化自动重建（§C4 O2 的缓存键纪律）
        val fullKey = (w.toLong() shl 32) or h.toLong()
        ensureFullscreen(Id.STARFIELD, w, h, fullKey) { row, y -> starfieldRow(row, y, w, h, starLayout(w, h)) }
        ensureFullscreen(Id.PAPER, w, h, fullKey) { row, y -> paperRow(row, y, w, h) }
        ensureFullscreen(Id.WATER, w, h, fullKey) { row, y -> waterRow(row, y, w, h) }
        ensureFullscreen(Id.CAUSTIC, w, h, fullKey) { row, y -> causticRow(row, y, w, h) }
        ensureFullscreen(Id.PLASMA, w, h, fullKey) { row, y -> plasmaRow(row, y, w, h) }
        ensureFullscreen(Id.FOG, w, h, fullKey) { row, y -> fogRow(row, y, w, h) }
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

    /** STARFIELD 的一行光栅化（把 [starLayout] 的星画进 [out]） */
    internal fun starfieldRow(out: IntArray, y: Int, w: Int, h: Int, layout: FloatArray) {
        java.util.Arrays.fill(out, 0)
        val n = layout.size / 3
        for (i in 0 until n) {
            val sx = layout[i * 3] * w
            val sy = layout[i * 3 + 1] * h
            val tier = layout[i * 3 + 2].toInt()
            val r = STAR_R[tier]
            val dy = y - sy
            if (dy < -r || dy > r) continue
            val dx = kotlin.math.sqrt((r * r - dy * dy).coerceAtLeast(0f))
            val x0 = (sx - dx).toInt().coerceIn(0, w - 1)
            val x1 = (sx + dx).toInt().coerceIn(0, w - 1)
            val argb = (STAR_A[tier] shl 24) or 0x00FFFFFF
            for (x in x0..x1) out[x] = argb
        }
    }

    /** PAPER：低频 `sin` 交叉纹（周期 40px，幅度 ±4 灰阶）+ 高频噪点，半透明 */
    internal fun paperRow(out: IntArray, y: Int, w: Int, h: Int) {
        var st = 0xC0FFEEu + y.toUInt()
        for (x in 0 until w) {
            st = lcg(st)
            val low = sin(x * (2.0 * Math.PI / 40.0)).toFloat() * sin(y * (2.0 * Math.PI / 40.0)).toFloat() * 4f
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
            val v = (sin(fx * 0.021f + fy * 0.008f) +
                sin(fx * 0.007f - fy * 0.017f) +
                sin(fx * 0.013f + fy * 0.024f) +
                sin(fx * -0.031f + fy * 0.004f)) * 0.25f   // -1..1
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
            val v = kotlin.math.abs(sin(fx * 0.045f + fy * 0.018f)) *
                kotlin.math.abs(sin(fx * -0.022f + fy * 0.051f))
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
     * ⚠️ 每像素 **3 次 `sin`**（比 [waterRow] 的 4 次更省）。本 tile 会在每次
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
            val r = (sin(fx * 0.0113f + fy * 0.0071f) * 0.5f + 0.5f) * 255f
            val g = (sin(fx * 0.0137f - fy * 0.0094f + PLASMA_PHASE_G) * 0.5f + 0.5f) * 255f
            val b = (sin(fx * 0.0091f + fy * 0.0126f + PLASMA_PHASE_B) * 0.5f + 0.5f) * 255f
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
     * ⚠️ 每像素 **3 次 `sin`**（与 [plasmaRow] 同量级）。本 tile 会在每次
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
            val v = (sin(fx * 0.0183f + fy * 0.0121f) +
                sin(fx * -0.0094f + fy * 0.0231f) +
                sin(fx * 0.0268f + fy * 0.0043f + FOG_PHASE)) / 3f      // -1..1
            val net = ((v - FOG_FLOOR) / (1f - FOG_FLOOR)).coerceIn(0f, 1f)
            val a = (FOG_A * net).toInt().coerceIn(0, FOG_A)
            val g = (196f + net * 59f).toInt().coerceIn(0, 255)         // 浓处更白
            out[x] = (a shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    private fun makeBitmap(w: Int, h: Int, pixels: IntArray): ImageBitmap =
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

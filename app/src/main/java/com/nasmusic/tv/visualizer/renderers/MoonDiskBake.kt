package com.nasmusic.tv.visualizer.renderers

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * E44「明月」§五 的**纯计算核**：等距圆柱月面 → 正射圆盘的 UV 反解 + 双线性采样 + 定稿色彩曲线。
 *
 * ## 为什么单独成文件
 * ⛔ 不含任何 Android 类型（`Bitmap` / `Context` 都不出现）：源图像素与输出像素都以 [IntArray]
 * （`ARGB_8888` 打包整型，与 [android.graphics.Bitmap.getPixels] 同一格式）传入
 * ⇒ 单测可以**直接喂构造像素**验证单位与色彩曲线（§十 G10），不需要图形设备。
 * 先例：E43 的 [SeasideWaves]（§14.3.1 的确定性 hash/噪声就是这样被 G2 直调测的），
 * 本文件的月面合成还**复用**它的 [SeasideWaves.vnoise2]，⛔ 不再写第四份噪声实现。
 *
 * ## ⚠️ 两条 2026-10-09 用真素材实测出来的红线（⛔ 别再抄回旧写法）
 * 1. **喂进曲线的 tint 不是 `tintMul`**，而是原型 `moonlit-preview.html:976` 的
 *    `tintMul × LUM_K × DISK_GAIN(=1.34)`（见 [TINT_R]）。只乘 `tintMul` 时盘内 R 均值实测
 *    134（原型截图锚点 204），整盘发暗 ⇒ 这是**漏乘常数**，不是观感偏好。
 * 2. **`src` 通道已经是 0..255**，[GAIN_A]/[GAIN_B] 只当增益用，⛔ 绝不再乘 255
 *    （§5.2+ ①；原型真犯过一次，全盘顶格）。
 *
 * ## 逐位对齐原型
 * - 双线性：经度方向**回绕**（跨 180° 接缝）、纬度方向**钳边**（`moonlit-preview.html:571-572`）；
 * - 输出量化：**截断**（`putImageData` 的 uint8 语义），⛔ 不是四舍五入；
 * - 圆外像素整体置 0（alpha 0）⇒ 贴上去天然就是圆盘，⛔ 不需要 `clipPath`。
 */
internal object MoonDiskBake {

    // ── §3.3+ 定稿色温的自变量 **不在本文件定义**：altT 属于构图色尺 [MoonSeascape.ALT_T]
    //    （由 `MoonSeascape.altitude()` 从 §3.1 的两个 K 值**推出**，不是手抄小数）。
    //    ⛔ 不要再在这里抄一份 `(HORIZON_K − MOON_CY_K)/HORIZON_K`：§3.3+ 的 `lumK` 教训就是
    //      旧写法把 ramp 端点重抄了一遍，改 ramp 时它悄悄没跟着改 ⇒ 盘色与水天色分家。

    /** §5.2+ ② 定稿对比曲线 `gain = 0.72 + 0.55·g`（⛔ 不是旧的 `0.45 + 0.95·g`）。 */
    const val GAIN_A = 0.72f
    const val GAIN_B = 0.55f

    /** §5.2+ ③ limb darkening 替身 `ld = 1 − 0.16·(r/R)²`（⛔ 不要真实临边昏暗公式）。 */
    const val LD_K = 0.16f

    /**
     * §3.1 / 原型 `:262` 盘体增益。
     * ⚠️ 实测（真素材、全盘顶格占比）：**1.34 ⇒ 1.79%**；旧曲线配 1.5 ⇒ 19.15%；
     * 单位坑 ⇒ 100%。⇒ 这条是 G10 顶格判据的**直接输入**，改它等于改门禁。
     */
    const val DISK_GAIN = 1.34f

    /** §5.2：纹理半径 = 盘半径 × 1.25，钳 `[192, 512]`（上限受源图 512 高的行采样限制）。 */
    const val TEX_SCALE = 1.25f
    const val TEX_R_MIN = 192
    const val TEX_R_MAX = 512

    /** §5.3 源图（`assets/globe/moon.jpg`）尺寸。 */
    const val SRC_W = 1024
    const val SRC_H = 512

    /** [synthesizeFallback] 的分辨率（原图的 1/2 边长 = 1/16 像素数，理由见该函数）。 */
    const val FALLBACK_W = SRC_W / 4
    const val FALLBACK_H = SRC_H / 4

    // ── 色温派生量（object 初始化算**一次**，⛔ 不在任何每帧路径上）──
    /** `moonHsl(36+2.5·altT, 0.99+0.01·altT, 0.70+0.045·altT)` 的 0..255 三元组 */
    val LIT_R: Float
    val LIT_G: Float
    val LIT_B: Float

    /** `clamp(0.58 + 0.42·(lit/0.745), 0.4, 1)` —— ⚠️ 引用**同一个** `lit`，⛔ 不重抄端点 */
    val LUM_K: Float

    /** ⭐ 真正喂给 [bakeRows] 的 tint = `tintMul × LUM_K × DISK_GAIN`（见文件头红线 1） */
    val TINT_R: Float
    val TINT_G: Float
    val TINT_B: Float

    /** `255 / max(moonHsl)` —— 把最大通道**正好**顶到 255；⛔ 再往上乘就是往纯白脱色 */
    val HOT_K: Float

    /** [HOT_K] 色（逐通道四舍五入后截到 255，与原型 `hotOf` 的 `Math.round + min(255,…)` 同序）
     *  —— §5.2+ ④ `BLOOM` 加法过曝芯的颜色 */
    val HOT_R: Float
    val HOT_G: Float
    val HOT_B: Float

    /** 上面三通道打包成 `ARGB_8888` 整型，供 `Color(Int)` 直接构造（⛔ 渲染侧别再各拼一份） */
    val HOT_COLOR_INT: Int

    init {
        val hue = 36.0 + 2.5 * MoonSeascape.ALT_T
        val sat = 0.99 + 0.01 * MoonSeascape.ALT_T
        val lit = 0.70 + 0.045 * MoonSeascape.ALT_T
        val rgb = DoubleArray(3)
        hslToRgb(hue, sat, lit, rgb)
        LIT_R = rgb[0].toFloat()
        LIT_G = rgb[1].toFloat()
        LIT_B = rgb[2].toFloat()
        val mmax = maxOf(rgb[0], rgb[1], rgb[2]).coerceAtLeast(1e-6)
        LUM_K = (0.58 + 0.42 * (lit / 0.745)).coerceIn(0.4, 1.0).toFloat()
        val bakeGain = LUM_K * DISK_GAIN
        TINT_R = (rgb[0] / mmax * bakeGain).toFloat()
        TINT_G = (rgb[1] / mmax * bakeGain).toFloat()
        TINT_B = (rgb[2] / mmax * bakeGain).toFloat()
        HOT_K = (255.0 / mmax).toFloat()
        HOT_R = (rgb[0] * HOT_K).coerceAtMost(255.0).toFloat()
        HOT_G = (rgb[1] * HOT_K).coerceAtMost(255.0).toFloat()
        HOT_B = (rgb[2] * HOT_K).coerceAtMost(255.0).toFloat()
        HOT_COLOR_INT = (0xFF shl 24) or
            ((rgb[0] * HOT_K).roundToInt().coerceAtMost(255) shl 16) or
            ((rgb[1] * HOT_K).roundToInt().coerceAtMost(255) shl 8) or
            (rgb[2] * HOT_K).roundToInt().coerceAtMost(255)
    }

    /**
     * HSL → RGB（0..255 写进 [out]，⛔ 不返回数组 ⇒ 调用点零分配）。与原型 `hsl()` 同构。
     * `hueDeg` 允许越界（内部规范化到 0..360）。
     */
    fun hslToRgb(hueDeg: Double, sat: Double, lit: Double, out: DoubleArray) {
        val h = (hueDeg % 360.0 + 360.0) % 360.0
        val c = (1.0 - abs(2.0 * lit - 1.0)) * sat
        val x = c * (1.0 - abs((h / 60.0) % 2.0 - 1.0))
        val m = lit - c / 2.0
        val r: Double
        val g: Double
        val b: Double
        when {
            h < 60.0 -> { r = c; g = x; b = 0.0 }
            h < 120.0 -> { r = x; g = c; b = 0.0 }
            h < 180.0 -> { r = 0.0; g = c; b = x }
            h < 240.0 -> { r = 0.0; g = x; b = c }
            h < 300.0 -> { r = x; g = 0.0; b = c }
            else -> { r = c; g = 0.0; b = x }
        }
        out[0] = (r + m) * 255.0
        out[1] = (g + m) * 255.0
        out[2] = (b + m) * 255.0
    }

    /** 圆盘纹理半径（像素，边长 `T = 2·texR`）。 */
    fun texRadius(moonR: Float): Int =
        (moonR * TEX_SCALE).roundToInt().coerceIn(TEX_R_MIN, TEX_R_MAX)

    /**
     * 原型 `moonlit-preview.html:926` 的 `hotOf(k)`：月色三元组乘 `k` 后**逐通道四舍五入并显式截到 255**，
     * 打包成不透明 `ARGB_8888`。
     *
     * ⚠️ `k > 1` 时**必须**自己夹 255 —— CSS 会宽容地画成过界值，Kotlin 侧的 `Int` 通道直接溢出成杂色
     * （原型 `:920` 的注释点名的就是这件事）。[HOT_K] 已经是"最大通道正好 255"的系数，
     * 所以 `k > HOT_K` 必然出现截断 ⇒ 再往上乘只会往纯白脱色。
     * ⛔ 渲染侧不要按 [LIT_R]/[LIT_G]/[LIT_B] 再拼一份（那是第三份端点抄写，`lumK` 的老坑）。
     */
    fun hotOf(k: Float): Int =
        (0xFF shl 24) or
            ((LIT_R * k).roundToInt().coerceAtMost(255) shl 16) or
            ((LIT_G * k).roundToInt().coerceAtMost(255) shl 8) or
            (LIT_B * k).roundToInt().coerceAtMost(255)

    /**
     * 原型 `moonlit-preview.html:932` 的 `whiteOf(t)`：月色三元组**逐通道往纯白插值** `t`，
     * 同样四舍五入并截到 255，打包成不透明 `ARGB_8888`。
     *
     * ## 为什么水面必须走这把尺，而不是 [hotOf]
     * 原型 `:928-931` 的 PIL 实测：参考图**最亮水波** = `rgb(248,220,150)`，而**盘内均值** =
     * `rgb(210,169,98)` —— 同一个光源，波峰却比盘面淡一档（比值 `(1,0.89,0.60)` 对 `(1,0.81,0.47)`），
     * 因为波峰是**过曝的镜面**。⛔ 拿月盘色（[hotOf]，走的是 `k` 增益）刷水，亮度被**色相**封死在
     * `lum ≈ 192`（原型实测卡在 178 上不去）；此时继续加 α 只会把暗海水一起染亮 ⇒ 发灰。
     *
     * 与 [hotOf] 的区别是**方向**：`hotOf` 是等比乘（三通道同乘 `k`，饱和度不变、只提亮度，
     * 过界靠截断），`whiteOf` 是向 255 收敛（越亮的通道涨得越少，饱和度**单调下降**）。
     * 两者⛔ 不可互换 —— §7.2/§7.3 的取色（光柱 `0.22`/`0.10`、粼光 `0.35`）全是 `whiteOf` 口径。
     *
     * ⚠️ [t] 必须落在 `0..1`：调用方若从音频量算 `t`，先夹（`t > 1` 会插到 255 之外，
     * 靠截断"看起来对"其实是三个通道各自被夹在不同位置 ⇒ 色相会偏）。
     * 与 [hotOf] 同样是**第三份抄写的禁令**：⛔ 渲染侧不要按 [LIT_R]/[LIT_G]/[LIT_B] 自己插值一遍。
     */
    fun whiteOf(t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        return (0xFF shl 24) or
            ((LIT_R + (255f - LIT_R) * tt).roundToInt().coerceAtMost(255) shl 16) or
            ((LIT_G + (255f - LIT_G) * tt).roundToInt().coerceAtMost(255) shl 8) or
            (LIT_B + (255f - LIT_B) * tt).roundToInt().coerceAtMost(255)
    }

    /**
     * §5.1 UV 反解：圆盘像素 `(px, py)` → 源图像素坐标，写进 [out]（`out[0]=u`、`out[1]=v`）。
     *
     * ⚠️ [cb]/[sb] 是 `cos(b0)`/`sin(b0)`，由调用方**在行循环外**算一次（旧 ARM 上逐像素
     * 三角函数是主要成本）；[uv] 只是"从角度算"的便捷入口，两者共用 [uvPre] ⇒ ⛔ 公式只有一份。
     *
     * @return `false` = 该像素在圆盘**外**（`X²+Y² > 1`），调用方必须置 alpha 0
     */
    fun uvPre(
        px: Int,
        py: Int,
        texR: Int,
        srcW: Int,
        srcH: Int,
        cb: Double,
        sb: Double,
        w0: Double,
        out: FloatArray,
    ): Boolean {
        val c = texR.toFloat()                       // T = 2·texR ⇒ 圆心 = texR
        val x = (px - c) / texR                       // 东向 −1..1
        val y = (c - py) / texR                       // 北向 −1..1（位图 y 向下）
        val r2 = x * x + y * y
        if (r2 > 1.0f) return false
        val z = sqrt(1.0f - r2)                       // 指向观测者的分量
        val lat = asin((y * cb + z * sb).toDouble())
        val lon = atan2(x.toDouble(), (z * cb - y * sb).toDouble()) + w0
        out[0] = (((Math.toDegrees(lon) + 180.0).mod(360.0)) / 360.0 * srcW).toFloat()
        out[1] = ((90.0 - Math.toDegrees(lat)) / 180.0 * srcH).toFloat()
        return true
    }

    /** [uvPre] 的角度版（每次自算 `cos/sin`，仅供单测与低频路径）。 */
    fun uv(
        px: Int,
        py: Int,
        texR: Int,
        srcW: Int,
        srcH: Int,
        libWDeg: Float,
        libBDeg: Float,
        out: FloatArray,
    ): Boolean {
        val b0 = Math.toRadians(libBDeg.toDouble())
        return uvPre(px, py, texR, srcW, srcH, cos(b0), sin(b0),
            Math.toRadians(libWDeg.toDouble()), out)
    }

    /**
     * 烘 [rowFrom, rowTo) 行圆盘纹理进 [out]（行主序、每行 `2·texR` 个像素）。
     *
     * ⚠️ [out] 是**整盘** `T × T` 的像素数组，行下标直接取 `py`（不是 `py - rowFrom`）
     *   ⇒ 分帧增量烘可以往同一个数组里续写，无需任何中间拷贝。
     * ⛔ **零分配**：[out] 与 [uvTmp] 都由调用方持有并复用，本函数内只有标量运算。
     * ⚠️ 这是**烘焙期**函数（渲染器按自适应步长分帧调用），⛔ 绝不允许一次烘完整盘
     *   —— 512 行同步烘 = 一次 80–150 ms 硬冻结（§5.2）。
     *
     * @param src 等距圆柱月面（`ARGB_8888` 打包，通道 0..255；解码所得或 [synthesizeFallback] 所得）
     * @param tintR/G/B 生产路径传 [TINT_R]/[TINT_G]/[TINT_B]（⛔ 不要在这里再乘 `DISK_GAIN`）
     */
    fun bakeRows(
        src: IntArray,
        srcW: Int,
        srcH: Int,
        texR: Int,
        rowFrom: Int,
        rowTo: Int,
        libWDeg: Float,
        libBDeg: Float,
        tintR: Float,
        tintG: Float,
        tintB: Float,
        out: IntArray,
        uvTmp: FloatArray,
    ) {
        val t = texR * 2
        val c = texR.toFloat()
        val b0 = Math.toRadians(libBDeg.toDouble())
        val cb = cos(b0)
        val sb = sin(b0)
        val w0 = Math.toRadians(libWDeg.toDouble())
        var py = rowFrom
        while (py < rowTo) {
            val base = py * t
            val yN = (c - py) / texR
            var px = 0
            while (px < t) {
                if (!uvPre(px, py, texR, srcW, srcH, cb, sb, w0, uvTmp)) {
                    out[base + px] = 0                 // 圆外透明 ⇒ ⛔ 不需要 clipPath
                } else {
                    val xN = (px - c) / texR
                    val r2 = xN * xN + yN * yN
                    // 双线性（经度回绕、纬度钳边）—— ⛔ 最近邻在 1024→~256 降采样下会出马赛克
                    var u = uvTmp[0]
                    var v = uvTmp[1]
                    if (u < 0.0) u = 0.0f
                    if (u > srcW - 1.001) u = srcW - 1.001f
                    if (v < 0.0) v = 0.0f
                    if (v > srcH - 1.001) v = srcH - 1.001f
                    val xu = floor(u)
                    val yv = floor(v)
                    val x0 = xu.toInt() % srcW
                    val y0 = yv.toInt()
                    val x1 = (x0 + 1) % srcW
                    val y1 = if (y0 + 1 > srcH - 1) srcH - 1 else y0 + 1
                    val fx = (u - xu).toDouble()
                    val fy = (v - yv).toDouble()
                    val wx1 = 1.0 - fx
                    val wy1 = 1.0 - fy
                    val sr = blend4(
                        src[y0 * srcW + x0] ushr 16 and 0xFF,
                        src[y0 * srcW + x1] ushr 16 and 0xFF,
                        src[y1 * srcW + x0] ushr 16 and 0xFF,
                        src[y1 * srcW + x1] ushr 16 and 0xFF,
                        wx1, fx, wy1, fy,
                    )
                    val sg = blend4(
                        src[y0 * srcW + x0] ushr 8 and 0xFF,
                        src[y0 * srcW + x1] ushr 8 and 0xFF,
                        src[y1 * srcW + x0] ushr 8 and 0xFF,
                        src[y1 * srcW + x1] ushr 8 and 0xFF,
                        wx1, fx, wy1, fy,
                    )
                    val sbl = blend4(
                        src[y0 * srcW + x0] and 0xFF,
                        src[y0 * srcW + x1] and 0xFF,
                        src[y1 * srcW + x0] and 0xFF,
                        src[y1 * srcW + x1] and 0xFF,
                        wx1, fx, wy1, fy,
                    )
                    // ⚠️ sr/sg/sbl 已是 0..255（⛔ 不再乘 255）；g 是归一化亮度 0..1
                    val gm = (GAIN_A + GAIN_B * ((0.2126 * sr + 0.7152 * sg + 0.0722 * sbl) / 255.0)) *
                        (1.0 - LD_K * r2)
                    out[base + px] = (0xFF shl 24) or
                        ((sr * gm * tintR).toInt().coerceIn(0, 255) shl 16) or       // 截断=putImageData 语义
                        ((sg * gm * tintG).toInt().coerceIn(0, 255) shl 8) or
                        (sbl * gm * tintB).toInt().coerceIn(0, 255)
                }
                px++
            }
            py++
        }
    }

    private inline fun blend4(
        p00: Int, p10: Int, p01: Int, p11: Int,
        wx1: Double, fx: Double, wy1: Double, fy: Double,
    ): Double = (p00 * wx1 + p10 * fx) * wy1 + (p01 * wx1 + p11 * fx) * fy

    /** 天平动量化（§5.2 重烘判定）：0.5° 一档 ⇒ 峰值速率 1.3°/天 ⇒ 约每 9 小时一次，一场播放通常 0 次。 */
    fun quantizeLibration(deg: Float): Int = floor(deg / 0.5f).toInt()

    /**
     * §5.4 降级：asset 缺失 / 解码失败时**程序化**生成一张等距圆柱月面源图（灰度，ARGB 打包）。
     *
     * ⛔ 不是平色圆盘（那等于把需求 1「真实月面」丢掉），⛔ 也不是"返回 null 什么都不画"。
     * 形态：高地基色 + 单八度值噪声斑驳 + 6 块深色月海（按近侧实际海名摆位）+ 2 处亮射纹斑
     * —— 下游 UV 反解 / 色彩曲线 / 贴图 / BLOOM **完全共用**，⛔ 不要再写第二条绘制分支。
     *
     * ⚠️ 分辨率只有原图的 **1/4 边长**（256×128）：本函数跑在 `onEnterContent` 的**同步**路径上，
     *   而每像素要过 6 个月海判定 + 1 次 `vnoise2`；按 E43 本机实测的逐像素烘焙成本外推，
     *   1024×512 版会把进入时间推到秒级。降采样后 [bakeRows] 的双线性自动补平滑，
     *   观感差异只在月海边缘的软硬（降级路径可接受）。
     */
    fun synthesizeFallback(out: IntArray, w: Int = FALLBACK_W, h: Int = FALLBACK_H) {
        var iy = 0
        while (iy < h) {
            val lat = 90.0 - (iy + 0.5) / h * 180.0
            var ix = 0
            while (ix < w) {
                val lon = -180.0 + (ix + 0.5) / w * 360.0
                // 高地基色：真素材（`moon.jpg`）灰度实测 mean 130.6 / p50 138 / p95 207，
                // ⇒ 基色带取 118..174（均值≈146）再被月海压到 ≈125，与真素材同量级。
                // ⚠️ 别往上抬：tint 已含 `DISK_GAIN 1.34`，基色到 190 级就会让降级盘变白盘。
                var v = 118.0 + 56.0 * SeasideWaves.vnoise2(ix / 9.0, iy / 9.0, 7703)
                var k = 0
                while (k < MARIA.size) {
                    val m = MARIA[k]
                    val dx = (lon - m[0]) / m[2]
                    val dy = (lat - m[1]) / m[3]
                    val d2 = dx * dx + dy * dy
                    if (d2 < 1.0) v -= m[4] * (1.0 - d2)
                    k++
                }
                if (abs(lon + 11.0) < 6.0 && abs(lat + 43.0) < 6.0) v += 46.0
                if (abs(lon - 20.0) < 5.0 && abs(lat + 20.0) < 5.0) v += 38.0
                val q = v.toInt().coerceIn(0, 255)
                out[iy * w + ix] = (0xFF shl 24) or (q shl 16) or (q shl 8) or q
                ix++
            }
            iy++
        }
    }

    /**
     * 月海椭圆 `{中心经°, 中心纬°, 半轴经°, 半轴纬°, 压暗量}`（近侧，经度以中央经线为 0）。
     *
     * ⚠️ 半轴按**经纬度直接开椭圆**：等距圆柱上高纬会把东西向拉长，所以高纬的海给的经度半轴
     *   已经按 `1/cos(lat)` 折算过（雨海/危海），⛔ 不要再在循环里补一次余弦修正。
     */
    private val MARIA = arrayOf(
        doubleArrayOf(-57.0, 38.0, 26.0, 20.0, 78.0),   // 风暴洋 Oceanus Procellarum
        doubleArrayOf(-15.0, 32.0, 20.0, 15.0, 82.0),   // 雨海 Mare Imbrium
        doubleArrayOf(17.0, 28.0, 13.0, 9.0, 66.0),     // 澄海 Mare Serenitatis
        doubleArrayOf(31.0, 8.0, 12.0, 9.0, 60.0),      // 静海 Mare Tranquillitatis
        doubleArrayOf(59.0, 17.0, 8.0, 6.0, 74.0),      // 危海 Mare Crisium
        doubleArrayOf(-38.0, -15.0, 11.0, 9.0, 68.0),   // 蛙海 Mare Humorum
    )
}

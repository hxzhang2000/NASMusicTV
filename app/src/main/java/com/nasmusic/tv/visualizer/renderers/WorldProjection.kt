package com.nasmusic.tv.visualizer.renderers

import kotlin.math.abs
import kotlin.math.floor

/**
 * 「世界」效果的地图投影：**Robinson**。
 *
 * ## 为什么是 Robinson
 * 「任何一帧单看都像一张海报」是本效果的核心审美要求。Robinson 是古典世界挂图的
 * 标准投影：赤道最长、纬线向两极收窄但不撕裂、高纬被温和压扁 —— 正是挂图的观感。
 * 代价是**没有严格保面积/保角**，但「世界」是装饰性可视化，不做等积统计，无所谓。
 *
 * ## 表的构成（19 行，每行 5°）
 * - **X 系数** [X_COEF]：纬向压缩系数，赤道 = 1.0000，两极 = 0.5322。
 * - **Y 增量** [Y_STEP]：每条 5° 纬带**跨越**的 y 距离，**不是**该纬线的 y 坐标！
 *   极点到赤道的原始总长 = Σ Y_STEP = **10.2609**（原始单位）。
 *   归一化后的 y 才是 Y_STEP 的**累加和 / 10.2609**，见 [Y_CUM]（init 期算好）。
 *   ⛔ 直接拿表里的 0.0620 / 0.1240 当 y 坐标是常见错误 —— 那是「增量」，
 *   用它会让高纬被压到赤道附近，整个地图缩成一条。
 *
 * 表内线性插值：纬度落在两条标准纬线之间时，X 系数与 Y 坐标都**在该 5° 带内线性插值**
 * （[coefficientAt] / [yAt]），这正是 Robinson 的构造方式 —— 相邻纬线间用直纹，
 * 所以 5° 之间是一条直线而不是曲线。
 *
 * ## 归一化（与经典常数 0.8487 的关系）
 * [robinsonX] = `(lon / 180) × 0.8487 × X(lat)`，即**赤道半宽 = 0.8487**、**极点 y = ±1**。
 * 0.8487 是 Robinson 构造里的经典赤道半宽常数（整图 1.6974 : 2.0），本项目沿用它：
 *
 * - `|robinsonX| ≤ 0.8487 < 1` ⇒ x 恒在 [-1,1] 内，比把赤道半宽拉满到 1.0 更好看：
 *   归一化坐标的 ±1 边界留给了别的东西，地图两侧天然有约 15% 的横向余量。
 * - 因为半宽(0.8487) < 半高(1.0)，**地图本身是竖向略高的**，
 *   所以 [mapScale] 以**短边**为基准才能竖向刚好铺满（见下）。
 *
 * ⛔ 零 Android 依赖、零分配、全部纯函数 —— 渲染层每帧算一次并缓存进字段即可。
 *
 * ## 地图像素包围盒（`mapLeft` / `mapRight` / `mapTop` / `mapBottom`）
 * **只为裁剪夜面填充而存在** —— 夜面是按经纬网格填出的整块多边形，水平范围比地图宽得多，
 * 早期在 1920×1080 上左右各溢出约 530 px 只能手工 `clipRect` 兜。
 * 这四个访问器把「半宽 = HALF_WIDTH × scale」这条推导收进本文件，
 * 渲染层不再各算一遍（详见它们的定义处注释与 ⛔ 不变式）。
 */
internal object WorldProjection {

    // ── 19 行标准表 ─────────────────────────────────────────────

    /**
     * X 系数：`X_COEF[i]` = |lat| = i×5° 处的纬向压缩系数。
     * 赤道 1.0000 → 两极 0.5322。
     */
    private val X_COEF = floatArrayOf(
        1.0000f, 0.9986f, 0.9954f, 0.9900f, 0.9822f, 0.9730f, 0.9600f, 0.9427f,
        0.9216f, 0.8962f, 0.8679f, 0.8350f, 0.7986f, 0.7597f, 0.7186f, 0.6732f,
        0.6213f, 0.5722f, 0.5322f
    )

    /**
     * Y **增量**（18 项，对应 19 行之间的 18 条 5° 纬带）：`Y_STEP[i]` 是从
     * |lat| = i×5° 走到 (i+1)×5° 跨越的 y 距离。Σ = 10.2609。
     */
    private val Y_STEP = floatArrayOf(
        0.0620f, 0.1240f, 0.1860f, 0.2480f, 0.3100f, 0.3720f, 0.4340f, 0.4958f,
        0.5571f, 0.6176f, 0.6769f, 0.7346f, 0.7903f, 0.8435f, 0.8936f, 0.9394f,
        0.9761f, 1.0000f
    )

    /** 表行数（19 行 = 0°..90°，步长 5°） */
    private const val ROWS = 19

    /**
     * 极点原始 y 总长 = Σ [Y_STEP]，经典值 **10.2609**。
     *
     * 由表**实算**而非写死常量：[Y_CUM] 的归一化分母必须是这张表自己累加出来的值
     * （浮点累加顺序不同会差几个 ULP），否则 `Y_CUM[18]` 得不到精确的 1.0f，
     * 而「极点 y == ±1」是被单测锁死的硬性质。
     */
    val Y_POLE_RAW: Float = run {
        var s = 0f
        for (v in Y_STEP) s += v
        s
    }

    /** [Y_STEP] 的归一化累加和（Y_CUM[0] = 0f，Y_CUM[18] = 1f **精确**） */
    private val Y_CUM = FloatArray(ROWS)

    /** 赤道半宽常数：robinsonX(±180, 0) = ±0.8487 */
    const val HALF_WIDTH = 0.8487f

    /** 经度（度）→ x 的合并系数 = [HALF_WIDTH] / 180 */
    private const val LON_TO_X = HALF_WIDTH / 180f

    init {
        var acc = 0f
        for (i in 0 until ROWS) {
            if (i > 0) acc += Y_STEP[i - 1]
            Y_CUM[i] = acc / Y_POLE_RAW
        }
    }

    // ── 投影本体 ───────────────────────────────────────────────

    /**
     * Robinson 归一化 x，∈ [-0.8487, 0.8487]，**随经度线性变化**（与纬度无关地线性）。
     *
     * = `(lonDeg / 180) × 0.8487 × X(latDeg)`。
     */
    fun robinsonX(lonDeg: Float, latDeg: Float): Float =
        lonDeg * LON_TO_X * coefficientAt(latDeg)

    /**
     * Robinson 归一化 y，∈ [-1, 1]，**+ 为北**（赤道 = 0，两极 = ±1）。
     *
     * = `sign(lat) × 累加和(Y_STEP) / 10.2609`，带 5° 带内线性插值。
     *
     * @param lonDeg ⛔ **不参与计算**：Robinson 在经度方向是直纹圆柱的，
     *   y 与经度无关。保留该参数是为了让 x/y 调用形状一致（渲染层
     *   `[WorldCities.ALL]` 的每个城市都拿同一对 (lon, lat) 调用，形状一致更好读）。
     */
    fun robinsonY(lonDeg: Float, latDeg: Float): Float {
        val a = abs(latDeg)
        if (a >= 90f) return if (latDeg < 0f) -1f else 1f
        val t = a * 0.2f // 每 5° 一档
        val i = floor(t.toDouble()).toInt().coerceIn(0, ROWS - 2)
        val f = t - i
        val y = Y_CUM[i] + (Y_CUM[i + 1] - Y_CUM[i]) * f
        return if (latDeg < 0f) -y else y
    }

    /** |lat| 处的 X 系数（5° 带内线性插值） */
    private fun coefficientAt(latDeg: Float): Float {
        val a = abs(latDeg)
        if (a >= 90f) return X_COEF[ROWS - 1]
        val t = a * 0.2f
        val i = floor(t.toDouble()).toInt().coerceIn(0, ROWS - 2)
        val f = t - i
        return X_COEF[i] + (X_COEF[i + 1] - X_COEF[i]) * f
    }

    // ── 屏幕映射 ───────────────────────────────────────────────

    /**
     * 以**短边**为基准的世界地图缩放（像素/归一化单位）。
     *
     * ```
     * scale = (1 - marginFraction) × min(canvasW, canvasH) / 2
     * ```
     *
     * 取短边而不是长边的原因：本投影的半宽(0.8487) < 半高(1.0)，地图是**竖向**更高，
     * 所以约束方永远是「竖直方向 vs 短边」；用长边算会让竖屏（1080×1920）地图溢出。
     * 该式对两种极值都安全（可证 `min(w,h)/2 ≤ min(w/1.6974, h/2)` 恒成立），
     * 因此任意长宽比都不会裁切，含南极。
     *
     * 实测（`marginFraction = 0.06`）：
     * - **1920×1080**：`min = 1080` ⇒ `scale = 0.94 × 540 = 507.6 px`。
     *   地图 861.5 × 1015.2 px：竖向恰好铺满可用高度（±507.6，可用 ±507.6），
     *   横向左右各空 529 px —— 这就是「海报」观感的来源。
     * - **1080×1920**：`min = 1080` ⇒ `scale` 同为 507.6 px，
     *   地图 861.5 × 1015.2 px，上下各空 452 px。
     *
     * [marginFraction] 钳在 [0, 0.45]，防止 ≥0.5 时算出负数/零。
     */
    fun mapScale(canvasW: Float, canvasH: Float, marginFraction: Float): Float {
        val short = if (canvasW <= canvasH) canvasW else canvasH
        if (short <= 0f) return 0f
        val m = marginFraction.coerceIn(0f, 0.45f)
        return (1f - m) * short * 0.5f
    }

    /** 地图水平中心（像素）：画布水平中心 */
    fun mapCenterX(canvasW: Float): Float = canvasW * 0.5f

    /** 地图垂直中心（像素）：画布垂直中心 */
    fun mapCenterY(canvasH: Float): Float = canvasH * 0.5f

    // ── 地图像素包围盒 ─────────────────────────────────────────
    //
    // 这四个访问器**为裁剪而存在**：晨昏线的「夜面」是按经纬度网格填出来的一整块多边形，
    // 它的水平范围天然比整幅 Robinson 地图宽（夜面横跨的经度段可以接近 180°），
    // 早期版本因此在 1920×1080 上左右各溢出约 530 px（= 960 − 0.8487 × 507.6），
    // 只能靠渲染层手算再 `clipRect` 兜住。把这段几何搬到这里，就不必在渲染层
    // 重复实现「半宽 = HALF_WIDTH × scale」这条推导 —— 也就不会各处 scale 取舍不一致。
    //
    // ⛔ 不变式（由 `WorldLogicTest` 锁死）：
    //   mapRight - mapLeft  == 2 × HALF_WIDTH × mapScale(…)
    //   mapBottom - mapTop  == 2 × mapScale(…)

    /**
     * 地图左边界（像素，**画布局部坐标**）。
     *
     * = `mapCenterX(canvasW) − HALF_WIDTH × mapScale(canvasW, canvasW, marginFraction)`。
     *
     * ⚠️ 两参重载只在 **`canvasW` 就是短边**时与实际绘制一致 —— 即竖屏 / 正方形
     * （1080×1920、手机竖屏）。横屏（1920×1080）的短边是**高度**，
     * [mapScale] 取的是 `min(w, h)`，此时必须用下面的三参重载。
     */
    fun mapLeft(canvasW: Float, marginFraction: Float): Float =
        mapCenterX(canvasW) - HALF_WIDTH * mapScale(canvasW, canvasW, marginFraction)

    /** 地图右边界（像素）。与 [mapLeft] 同一 [mapScale] ⇒ 两者之差恰为地图全宽。 */
    fun mapRight(canvasW: Float, marginFraction: Float): Float =
        mapCenterX(canvasW) + HALF_WIDTH * mapScale(canvasW, canvasW, marginFraction)

    /**
     * 地图上边界（像素，**画布局部坐标**，+ 为下 ⇒ 这里是数值较小的一侧）。
     *
     * ⚠️ 与 [mapLeft] 同样的限制：两参重载仅在 **`canvasH` 就是短边**时与实际绘制一致
     * （即横屏 1920×1080）。竖屏请用三参重载。
     */
    fun mapTop(canvasH: Float, marginFraction: Float): Float =
        mapCenterY(canvasH) - mapScale(canvasH, canvasH, marginFraction)

    /** 地图下边界（像素）。与 [mapTop] 同一 [mapScale] ⇒ 两者之差恰为地图全高。 */
    fun mapBottom(canvasH: Float, marginFraction: Float): Float =
        mapCenterY(canvasH) + mapScale(canvasH, canvasH, marginFraction)

    /**
     * 地图左边界（**任意长宽比都正确**的重载）：显式给出画布高度，
     * 于是 [mapScale] 拿得到真正的短边。
     *
     * 1920×1080（`marginFraction = 0.06`）：`scale = 507.6` ⇒ 左边界 = `960 − 430.8 = 529.2 px`，
     * 与三参版本逐位一致；1080×1920：`scale` 同为 507.6 ⇒ 左边界 = `540 − 430.8 = 109.2 px`。
     */
    fun mapLeft(canvasW: Float, canvasH: Float, marginFraction: Float): Float =
        mapCenterX(canvasW) - HALF_WIDTH * mapScale(canvasW, canvasH, marginFraction)

    /** 地图右边界（三参重载，见 [mapLeft]） */
    fun mapRight(canvasW: Float, canvasH: Float, marginFraction: Float): Float =
        mapCenterX(canvasW) + HALF_WIDTH * mapScale(canvasW, canvasH, marginFraction)

    /** 地图上边界（三参重载，见 [mapTop]） */
    fun mapTop(canvasW: Float, canvasH: Float, marginFraction: Float): Float =
        mapCenterY(canvasH) - mapScale(canvasW, canvasH, marginFraction)

    /** 地图下边界（三参重载，见 [mapTop]） */
    fun mapBottom(canvasW: Float, canvasH: Float, marginFraction: Float): Float =
        mapCenterY(canvasH) + mapScale(canvasW, canvasH, marginFraction)

    /**
     * 归一化 x → 屏幕像素 x。**唯一的 world→screen x 出口**，
     * 渲染层不得自行 `centerX + nx * scale`（避免各处 scale 取舍不一致）。
     */
    fun toScreenX(nx: Float, scale: Float, centerX: Float): Float = centerX + nx * scale

    /**
     * 归一化 y → 屏幕像素 y（**屏幕坐标，+ 为下**）。与 [toScreenX] 配对使用。
     */
    fun toScreenY(ny: Float, scale: Float, centerY: Float): Float = centerY + ny * scale

    /**
     * 归一化 y（**+ 为北**，[robinsonY] 的输出）→ 屏幕像素 y。
     *
     * 符号翻转只在这里发生一次，避免渲染层到处写 `if (north) -1f else 1f`。
     */
    fun nyToScreenY(ny: Float, scale: Float, centerY: Float): Float = toScreenY(-ny, scale, centerY)
}

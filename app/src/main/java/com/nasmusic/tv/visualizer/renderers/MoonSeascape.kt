package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.visualizer.VisualizerMath
import kotlin.math.roundToInt

/**
 * E44 `VisualizerTheme.MOONLIT`「明月」§3.2 层 1 的**色尺**：夜空与海水的定稿色基准。
 *
 * ## 为什么单独成文件（= §3.1 那条「⛔ 写死常数会刷出矩形亮块」的结构性防漂移）
 * §3.1 定稿只给了**两个**海/天色基准，而它们要被**三类**消费者读：
 * ① 层 1 的两块基矩形（本文件第一次被消费）；② §7.2 光柱的压暗带与纵向淡出、
 * ③ §7.3 粼光划的"所在深度的海水色"（T8）。原型把后两者收敛成一个 `seaC(t)` 函数，
 * 就是为了**只有一份端点**。⇒ 海水色一律走 [sea]，天空地平色一律走 [skyHorizon]，
 * ⛔ 任何消费者都不许自己抄 `rgb(5,9,17)` 这类小数。
 *
 * ## ⚠️ 与 §3.1 书面式的**一处**口径差（登记为偏差 D19）
 * §3.1 把海水第三档写作不带调制的字面量 `#02040a`，而原型的 `seaC(t)`
 * （`moonlit-preview.html:1168-1173`）对**三档一律**乘 `sb`。两者只在 `sb ≠ 1`（即 T9 接上
 * 低频调制）时才可分辨，差值 ≤ `0.15·(aBass−0.4)` 级 ⇒ 肉眼为零。
 * 本文件按**原型的函数**实现（一份尺、无特例），因为要防的是"两份端点各自漂移"，
 * 而不是"复刻原型的一处笔误"。
 *
 * ## 零 UI
 * ⛔ 不含 Android/Compose 类型：所有颜色返回**打包 ARGB Int**（与
 * [android.graphics.Bitmap.getPixels] 同格式，`Color(Int)` 直接可吃）⇒ 纯 JVM 单测。
 */
internal object MoonSeascape {

    /**
     * §3.3 色温自变量：月心相对**地平线**的高度比，`0` = 贴地平、`1` = 天顶。
     *
     * ⚠️ 这是**观感映射**，⛔ 不是大气质量公式 `1/cos z`（近地平发散、且本效果无真实高度角），
     * 差异登记 §十五 D3。
     * ⚠️ 分子分母同量纲 ⇒ 传归一化比例（`MOON_CY_K`/`HORIZON_K`）与传像素**同值**，
     * 这正是 [ALT_T] 能做成常数的原因（§3.1 固定构图，月不随歌曲移动）。
     */
    fun altitude(moonCy: Float, horizonY: Float): Float =
        if (horizonY <= 0f) 0f else ((horizonY - moonCy) / horizonY).coerceIn(0f, 1f)

    /**
     * 本效果的 altT —— ⭐ **由 [altitude] 推出，不手抄小数**。
     *
     * 它与 [MoonDiskBake] 的盘色温（`moonHue/moonSat/moonLit`）必须是**同一个数**：
     * §3.3+ 的教训是旧写法把 ramp 端点**重抄**了一遍，改 ramp 时亮度标度悄悄没跟着改。
     */
    val ALT_T: Float = altitude(MoonlitRenderer.MOON_CY_K, MoonlitRenderer.HORIZON_K)

    // ── 天空（§3.1 定稿：顶 → 0.62 → 地平线，前两档是**字面量**，只有地平档吃 altT）──
    //
    // ⚠️ 这两个是 `val` 而不是 `const val`：`0xFF03050C` 超出 `Int` 范围，Kotlin 会把它
    //    判成 **Long**（`Initializer type mismatch: expected 'Int', actual 'Long'` 实测），
    //    而 `const` 初值不接受 `.toInt()` 调用。写成的十有九个会漏的坑是**比较**：
    //    `seaColor == 0xFF0A111D` 里左边是负的 `Int`、右边是正的 `Long` ⇒ **恒 false**。
    //    ⇒ 拿这些值做断言一律走 `rgb(r, g, b)` 三元组（见测试里的 `argb` 助手）。

    /** 天顶 `#03050c` = rgb(3, 5, 12)。 */
    val SKY_TOP: Int = 0xFF03050C.toInt()

    /** 中段 `#060b18` = rgb(6, 11, 24)，停在 [SKY_MID_STOP]。 */
    val SKY_MID: Int = 0xFF060B18.toInt()

    /** 中段停靠位（归一化，与画幅无关）。 */
    const val SKY_MID_STOP: Float = 0.62f

    /** 天空**地平线**档：`rgb(9+16·altT, 15+12·altT, 30+6·altT)`。 */
    fun skyHorizon(altT: Float): Int {
        val t = altT.coerceIn(0f, 1f)
        return pack(9f + 16f * t, 15f + 12f * t, 30f + 6f * t)
    }

    // ── 海水（§3.1 定稿三段尺 + §八 的 `sb` 亮度调制）──

    /** 浅水/深水的分段点（`sea` 的**第一**段是 `[0, SEA_SPLIT]`，与原型 `seaC` 同形）。 */
    const val SEA_SPLIT: Float = 0.35f

    /**
     * 深度 `depth`（`0` = 地平线侧、`1` = 画面底）处的海水色，亮度乘子 `sb`。
     *
     * 端点逐字取自 §3.1：`rgb(10,17,29) → rgb(5,9,17) @0.35 → rgb(2,4,10)`。
     * ⚠️ 分段在 `SEA_SPLIT` 处**连续**（第一段末值 = 第二段起值），单测钉住这一点：
     * 若把它写成一条跨越 0→1 的插值，水面 1/3 处会出一道横向色阶 = §7.3 的"梯子"。
     */
    fun sea(depth: Float, sb: Float): Int {
        val d = depth.coerceIn(0f, 1f)
        val r: Float
        val g: Float
        val b: Float
        if (d < SEA_SPLIT) {
            val u = d / SEA_SPLIT
            r = VisualizerMath.lerp(10f, 5f, u)
            g = VisualizerMath.lerp(17f, 9f, u)
            b = VisualizerMath.lerp(29f, 17f, u)
        } else {
            val u = (d - SEA_SPLIT) / (1f - SEA_SPLIT)
            r = VisualizerMath.lerp(5f, 2f, u)
            g = VisualizerMath.lerp(9f, 4f, u)
            b = VisualizerMath.lerp(17f, 10f, u)
        }
        return pack(r * sb, g * sb, b * sb)
    }

    /** 截断到 8 位并封成不透明 ARGB Int（⛔ 不做加法溢出：`shl` 前一律先钳）。 */
    private fun pack(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or
            (r.roundToInt().coerceIn(0, 255) shl 16) or
            (g.roundToInt().coerceIn(0, 255) shl 8) or
            b.roundToInt().coerceIn(0, 255)
}

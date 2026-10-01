package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/**
 * 门禁 · E12 频谱瀑布的「衰减率 × 缓冲行数」耦合（v1.47 新增，v1.48 改为占比判据）。
 *
 * 拖影每帧上移 1 行、同时整张缓冲被纯黑 `alpha = FADE_ALPHA` 压一次 ⇒
 * 第 n 行（自底向上）的残留亮度 = `(1 - FADE_ALPHA)^n`。缓冲有 [BUFFER_ROWS] 行、且被
 * **整幅拉伸到全屏**，所以「第几行之后肉眼看不见」直接等于**屏幕上黑色占的高度占比**。
 *
 * ## 判据为什么是「占比」而不是「顶层残留落在某区间」
 * 顶层残留是个中间量，它要成立得先假设「衰减刚好在缓冲顶端结束」，而真正决定观感的是
 * **残留掉到可见阈以下的那一行**落在哪里。本机（创维 5.1.1 / 1080p）阈值为 **13%**，
 * 由两次真机标定夹出来：
 * - `0.06 × 200` 行 ⇒ 第 33 行到阈 ⇒ 用户反馈「上面都是黑色的」（底部约 1/6 有内容）
 * - `0.02 × 200` 行 ⇒ 第 101 行到阈 ⇒ 用户反馈「基本一半一半」
 * 两点都在同一阈上自洽 ⇒ 该模型可信，目标占比可直接由阈值反解。
 */
class WaterfallTrailFadeTest {

    private val C = WaterfallRenderer.Companion

    /** 真机肉眼可见阈（残留低于此值即视为「黑」），见类 KDoc 的两点标定 */
    private val VISIBLE_CUT = 0.13f

    /** 第 n 行的残留亮度 */
    private fun residual(alpha: Float, n: Int): Float =
        Math.pow((1.0 - alpha), n.toDouble()).toFloat()

    /** 残留 >= 可见阈的行数（即"屏幕上看得见的拖尾高度"，以缓冲行为单位） */
    private fun visibleRows(alpha: Float, rows: Int): Int {
        val n = ln(VISIBLE_CUT.toDouble()) / ln((1.0 - alpha))
        return (if (n.isNaN() || n.isInfinite()) rows + 1 else n.toInt()).coerceAtMost(rows)
    }

    /** 目标：可见拖尾占屏 4/5（顶部留 1/5 黑） */
    private val TARGET_VISIBLE = 0.80f

    @Test
    fun 可见拖尾必须约占屏幕五分之四() {
        val frac = visibleRows(C.FADE_ALPHA, C.BUFFER_ROWS) / C.BUFFER_ROWS.toFloat()
        assertTrue("可见拖尾占比 ${(frac * 100)}% 必须落在 ${(TARGET_VISIBLE - 0.05f) * 100}%–" +
            "${(TARGET_VISIBLE + 0.05f) * 100}%（顶部黑区即 1/5 ± 半档）",
            frac in (TARGET_VISIBLE - 0.05f)..(TARGET_VISIBLE + 0.05f))
    }

    @Test
    fun 衰减必须仍然有效() {
        // 拖尾的意义就是"越久越暗"：中间行必须已明显衰减，但刚写入的底行仍是全亮
        assertTrue("中间行必须已衰减", residual(C.FADE_ALPHA, C.BUFFER_ROWS / 2) < 0.5f)
        assertTrue("刚写入的行不得被压暗", residual(C.FADE_ALPHA, 1) > 0.95f)
    }

    @Test
    fun 缓冲顶端不得仍是亮的() {
        // 占比判据的漏洞侧：只要「到阈行数 ≥ 缓冲行数」占比就是 100%，
        // 但那时拖尾顶到屏幕上沿还亮着，等于没有衰减到黑。必须单独堵。
        assertTrue("顶层残留 ${(residual(C.FADE_ALPHA, C.BUFFER_ROWS) * 100)}% 必须已低于可见阈",
            residual(C.FADE_ALPHA, C.BUFFER_ROWS) < VISIBLE_CUT)
    }

    // ── 负向自证：判据必须能抓到历史上那两个错值 ──

    @Test
    fun 旧的零六必须被占比判据拒掉() {
        // 这就是 v1.47 改掉的那对值：判据抓不到它 ⇒ 上面几条断言是空转
        val frac = visibleRows(0.06f, 200) / 200f
        assertTrue("前提：旧参数确实只铺了底部一小条（实测可见占比 ${(frac * 100)}%）",
            frac < TARGET_VISIBLE - 0.05f)
        assertTrue("前提：旧参数顶层残留仅 ${(residual(0.06f, 200) * 10000).toInt()}‱，" +
            "上面 5/6 屏是一段纯黑而不是渐变", residual(0.06f, 200) < 0.001f)
    }

    @Test
    fun 上一版的零二必须被占比判据拒掉() {
        // v1.47 的第一次修正（×0.98）：真机反馈「基本一半一半」，正是占比判据该给的读数
        val frac = visibleRows(0.02f, 200) / 200f
        assertTrue("前提：0.02 的可见占比就是「一半」（实测 ${(frac * 100)}%）",
            frac in 0.45f..0.55f)
        assertTrue("前提：它落在目标带之外，占比判据能拒掉", frac < TARGET_VISIBLE - 0.05f)
        // 而"顶层残留落在 1%–5%"这条旧判据会把它放过 ⇒ 说明换判据是必要的
        assertTrue("前提：旧的顶层残留区间对 0.02 无效（实测 ${(residual(0.02f, 200) * 100)}%）",
            residual(0.02f, 200) in 0.01f..0.05f)
    }

    @Test
    fun 衰减过慢也必须被拒掉() {
        // 另一侧失效：0.005 × 200 行 ⇒ 到阈行数早已超出缓冲，占比判据给 100%
        val frac = visibleRows(0.005f, 200) / 200f
        assertTrue("前提：占比判据在这一侧会判成「铺满」（实测 ${(frac * 100)}%）",
            frac > TARGET_VISIBLE + 0.05f)
        assertTrue("故「缓冲顶端不得是亮的」一条不可省（实测顶层残留 " +
            "${(residual(0.005f, 200) * 100)}%）", residual(0.005f, 200) > VISIBLE_CUT)
    }
}

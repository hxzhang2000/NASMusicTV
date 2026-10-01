package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门禁 · E12 频谱瀑布的「衰减率 × 缓冲行数」耦合（v1.47 新增）。
 *
 * 拖影每帧上移 1 行、同时整张缓冲被纯黑 `alpha = FADE_ALPHA` 压一次 ⇒
 * 第 n 行的残留亮度 = `(1 - FADE_ALPHA)^n`。缓冲有 [BUFFER_ROWS] 行、且被**整幅拉伸到全屏**，
 * 所以衰减长度与缓冲长度必须匹配，否则多出来的那些行恒为全黑。
 *
 * 真机现象（2026-10-01 低画质扫描，用户反馈「上面都是黑色的」）：原 `0.06 × 200 行`
 * ⇒ 第 63 行就只剩 2%，**上面 2/3 屏是死的**；三档皆然，只是低画质此前根本进不到这套效果。
 */
class WaterfallTrailFadeTest {

    private val C = WaterfallRenderer.Companion

    /** 第 n 行的残留亮度 */
    private fun residual(alpha: Float, n: Int): Float = Math.pow((1.0 - alpha), n.toDouble()).toFloat()

    /** 缓冲里"看不见"（残留 < 2%）的行占比 */
    private fun deadFraction(alpha: Float, rows: Int): Float {
        val visible = (0..rows).count { residual(alpha, it) >= 0.02f }
        return 1f - visible / rows.toFloat()
    }

    @Test
    fun 顶层残留必须接近黑但不是全黑() {
        val top = residual(C.FADE_ALPHA, C.BUFFER_ROWS)
        assertTrue("顶层残留 ${(top * 100)}% 必须落在 1%–5%（太小=白铺一屏渐变看不出来，太大=顶部还亮）",
            top in 0.01f..0.05f)
    }

    @Test
    fun 缓冲不得出现大段恒黑死区() {
        val dead = deadFraction(C.FADE_ALPHA, C.BUFFER_ROWS)
        assertTrue("恒黑死区占比 ${(dead * 100)}% 必须 ≤ 10%（否则屏幕上就是一块黑）", dead <= 0.10f)
    }

    @Test
    fun 衰减必须仍然有效() {
        // 拖尾的意义就是"越久越暗"：中间行必须已明显衰减，但底行仍是全亮
        assertTrue("中间行必须已衰减", residual(C.FADE_ALPHA, C.BUFFER_ROWS / 2) < 0.5f)
        assertTrue("刚写入的行不得被压暗", residual(C.FADE_ALPHA, 1) > 0.95f)
    }

    // ── 负向自证 ──

    @Test
    fun 旧的零六乘二百行必须被死区判据拒掉() {
        // 这就是本次改掉的那对值：判据必须能抓到，否则上面两条断言是空转
        val dead = deadFraction(0.06f, 200)
        assertTrue("前提：旧参数确实制造了大段死区（实测 ${(dead * 100)}%）", dead > 0.10f)
        val top = residual(0.06f, 200)
        assertTrue("前提：旧参数顶层已是 ${(top * 10000)}‱ 级、不是「渐变到黑」而是「一段纯黑」",
            top < 0.01f)
    }

    @Test
    fun 衰减过慢也必须被拒掉() {
        // 另一侧失效：0.005 × 200 行 ⇒ 顶层还有 36.7%，拖尾顶到屏幕上沿还亮着
        val top = residual(0.005f, 200)
        assertTrue("前提：判据两个方向都有效（实测顶层残留 ${(top * 100)}%）", top > 0.05f)
        assertTrue("死区判据抓不到这一侧，故 ① 的区间上界不可省",
            deadFraction(0.005f, 200) <= 0.10f)
    }
}

package com.nasmusic.tv.visualizer.photo

import com.nasmusic.tv.visualizer.photo.transitions.BREATHE_SCALE
import com.nasmusic.tv.visualizer.photo.transitions.KEN_SCALE
import com.nasmusic.tv.visualizer.photo.transitions.PAN_RATIO
import com.nasmusic.tv.visualizer.photo.transitions.applyHoldMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 停留期运动几何门禁（§5.6，**纯 JVM** —— `applyHoldMotion` 不碰任何 Android 类）
 *
 * ## 它钉住什么
 *
 * Ken Burns / 音频呼吸是「每帧都在动」的效果，写错的表现是**缓慢漂移 / 抖动**，
 * 编译、lint、其余转场门禁全部照绿 —— 只有把几何公式单独钉住才能拦住。
 */
class PhotoHoldMotionTest {

    private val canvasW = 1920f
    private val canvasH = 1080f

    /** 一个**不在画布中心**的矩形（这样「中心不动」断言才有分辨力） */
    private fun offCenterRect(): PhotoRect = PhotoRect().apply { set(100f, 50f, 500f, 300f) }

    private fun center(r: PhotoRect): Pair<Float, Float> =
        Pair((r.left + r.right) * 0.5f, (r.top + r.bottom) * 0.5f)

    @Test
    fun `zero motion and zero boost is the identity transform`() {
        val r = offCenterRect()
        val (l, t, ri, b) = listOf(r.left, r.top, r.right, r.bottom)
        applyHoldMotion(r, 0f, 0f, canvasW, canvasH, pan = true)
        assertEquals(l, r.left, EPS)
        assertEquals(t, r.top, EPS)
        assertEquals(ri, r.right, EPS)
        assertEquals(b, r.bottom, EPS)
    }

    @Test
    fun `ken burns grows the rect by exactly 8 percent around its own center`() {
        val r = offCenterRect()
        val w = r.width
        val h = r.height
        val (cx, cy) = center(r)
        applyHoldMotion(r, 1f, 0f, canvasW, canvasH, pan = false)
        assertEquals("宽度应精确放大 8%", w * (1f + KEN_SCALE), r.width, EPS)
        assertEquals(h * (1f + KEN_SCALE), r.height, EPS)
        val (cx2, cy2) = center(r)
        assertTrue("缩放必须围绕自身中心（cx 不动）", abs(cx - cx2) < EPS)
        assertTrue(abs(cy - cy2) < EPS)
    }

    @Test
    fun `pan only applies in CROP and shifts by exactly 3 percent of the canvas`() {
        val crop = offCenterRect()
        val (cx, cy) = center(crop)
        applyHoldMotion(crop, 1f, 0f, canvasW, canvasH, pan = true)
        val (cx2, cy2) = center(crop)
        assertEquals("CROP 下中心应平移 3% 画布", cx + canvasW * PAN_RATIO, cx2, EPS)
        assertEquals(cy + canvasH * PAN_RATIO, cy2, EPS)

        val fit = offCenterRect()
        val (fx, fy) = center(fit)
        applyHoldMotion(fit, 1f, 0f, canvasW, canvasH, pan = false)
        val (fx2, fy2) = center(fit)
        assertTrue("FIT 下必须禁平移（否则露出黑边），中心不动", abs(fx - fx2) < EPS && abs(fy - fy2) < EPS)
    }

    @Test
    fun `audio boost adds exactly 2 percent scale on top of ken burns`() {
        val w0 = offCenterRect().width
        val onlyKen = offCenterRect()
        applyHoldMotion(onlyKen, 1f, 0f, canvasW, canvasH, pan = false)
        assertEquals(w0 * (1f + KEN_SCALE), onlyKen.width, EPS)

        val kenAndBoost = offCenterRect()
        applyHoldMotion(kenAndBoost, 1f, 1f, canvasW, canvasH, pan = false)
        assertEquals(
            "两层缩放是线性叠加（1 + 0.08 + 0.02），不是乘法",
            w0 * (1f + KEN_SCALE + BREATHE_SCALE),
            kenAndBoost.width,
            EPS,
        )

        // boost=1、motion=0 时的宽度 = 原宽 × 1.02（纯呼吸）
        val onlyBoost = offCenterRect()
        applyHoldMotion(onlyBoost, 0f, 1f, canvasW, canvasH, pan = false)
        assertEquals(w0 * (1f + BREATHE_SCALE), onlyBoost.width, EPS)
    }

    @Test
    fun `motion is monotonic so the push-in never drifts backwards`() {
        val prev = offCenterRect()
        applyHoldMotion(prev, 0f, 0f, canvasW, canvasH, pan = false)
        for (step in 1..10) {
            val cur = offCenterRect()
            applyHoldMotion(cur, step / 10f, 0f, canvasW, canvasH, pan = false)
            assertTrue(
                "推近必须单调（motion=$step/10 反而变小 ⇒ 每帧重算有状态泄漏）",
                cur.width >= prev.width - EPS,
            )
            prev.set(cur.left, cur.top, cur.right, cur.bottom)
        }
    }

    /**
     * **负向自证**：真实错法 = 「围绕**画布**中心缩放」（把 cx/cy 写成 canvasW/2、canvasH/2）。
     * 对不在画布中心的矩形，正确实现中心不动、错法中心必动 —— 本测试钉住这个分歧。
     */
    @Test
    fun `negative proof - scaling around the canvas center would drift an off-center rect`() {
        val correct = offCenterRect()
        val (cx, cy) = center(correct)
        applyHoldMotion(correct, 1f, 0f, canvasW, canvasH, pan = false)
        val (cx2, cy2) = center(correct)
        assertTrue(abs(cx - cx2) < EPS && abs(cy - cy2) < EPS)

        // 错法实现：围绕画布中心
        val wrong = offCenterRect()
        val w = wrong.width
        val scale = 1f + KEN_SCALE
        val ccx = canvasW * 0.5f
        val ccy = canvasH * 0.5f
        val hw = w * 0.5f * scale
        wrong.set(ccx - hw, ccy - hw, ccx + hw, ccy + hw)
        val (wx, _) = center(wrong)
        assertTrue(
            "错法（画布中心缩放）必须与正确实现分歧（中心横移）",
            abs(wx - cx) > 1f,
        )
    }

    private companion object {
        const val EPS = 0.01f
    }
}

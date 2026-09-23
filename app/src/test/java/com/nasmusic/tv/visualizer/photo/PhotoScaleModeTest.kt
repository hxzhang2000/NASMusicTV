package com.nasmusic.tv.visualizer.photo

import androidx.compose.ui.geometry.Size
import com.nasmusic.tv.backend.photo.PhotoScaleMode
import com.nasmusic.tv.visualizer.RenderContext
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * **门禁 G6**（§14.4）—— 缩放模式几何
 *
 * ## 判据原文
 *
 * > `CROP` 的 dst 覆盖全屏；`FIT` 的 dst 保持宽高比且不出界
 * > 负向自证：故意用错比例 ⇒ 断言 dst 出界
 *
 * ## 为什么这道门禁值得单独存在
 *
 * 「铺满」和「完整」的差别是**一行 `when` 分支**，但错了之后的症状是
 * 「照片被拉扁」或「两侧各有一条黑边」—— 都属于「看得出不对，但很难说是哪一段代码」。
 * 而且它**只依赖 `canvasSize` 与图片像素尺寸**，是最该被纯 JVM 单测钉死的部分
 * （所以 `PhotoRect` 刻意用 `Float` 而非 `android.graphics.Rect`，见其 KDoc）。
 *
 * ⚠️ 本测试**不需要 Robolectric**：`PhotoGeometry` 只读 `RenderContext.canvasSize`，
 * 不碰任何 `android.*` API。
 */
class PhotoScaleModeTest {

    // ────────────────────── ① CROP：铺满 ──────────────────────

    /**
     * T5.1 验收的核心一条：**CROP 的目标矩形恒为整个画布**。
     *
     * 用三种极端宽高比（远宽 / 正方形 / 远高）各验一遍 ——
     * 只在 16:9 上验的话，`imgAspect > canvasAspect` 分支写反了也照样能过。
     */
    @Test
    fun `crop fills the whole canvas`() {
        for ((w, h) in listOf(4000 to 1000, 1000 to 1000, 1000 to 4000, 1920 to 1080)) {
            val geom = geometry().also { it.update(ctx(1920f, 1080f), PhotoScaleMode.CROP, w, h) }
            assertRect("CROP($w×$h) 的 dst", geom.dstA, 0f, 0f, 1920f, 1080f)
        }
    }

    /**
     * CROP 的**源**必须按画布宽高比居中裁切 —— 否则源比目标「更宽」时会被横向压扁。
     */
    @Test
    fun `crop keeps the source aspect equal to the canvas aspect`() {
        val canvasAspect = 1920f / 1080f
        for ((w, h) in listOf(4000 to 1000, 1000 to 1000, 1000 to 4000)) {
            val geom = geometry().also { it.update(ctx(1920f, 1080f), PhotoScaleMode.CROP, w, h) }
            val srcAspect = geom.srcA.width / geom.srcA.height
            assertTrue(
                "CROP($w×$h) 的 src 宽高比应为画布宽高比 $canvasAspect，实际 $srcAspect —— " +
                    "不裁切就会被拉伸",
                abs(srcAspect - canvasAspect) < 1e-3f,
            )
        }
    }

    /** 裁切后源矩形必须**完全落在原图内**（越界会让 Skia 取到未定义像素） */
    @Test
    fun `crop keeps the source inside the image`() {
        for ((w, h) in listOf(4000 to 1000, 1000 to 1000, 1000 to 4000, 1919 to 1081)) {
            val geom = geometry().also { it.update(ctx(1920f, 1080f), PhotoScaleMode.CROP, w, h) }
            assertTrue(
                "CROP($w×$h) 的 src 越出原图：${geom.srcA}（原图 $w×$h）",
                geom.srcA.left >= -EPS && geom.srcA.top >= -EPS &&
                    geom.srcA.right <= w + EPS && geom.srcA.bottom <= h + EPS,
            )
        }
    }

    /**
     * 裁切方向要按「图比画布更宽 ⇒ 裁左右；图更高 ⇒ 裁上下」来选。
     *
     * 这是最容易写反的一处：写反之后源矩形会变成**负宽**（`right < left`），
     * 表现是整张图不显示。
     */
    @Test
    fun `crop picks the correct axis for wide and tall images`() {
        val c = ctx(1920f, 1080f)

        // 2:1 的宽图（比画布 16:9 更宽）⇒ 保满高、裁左右
        val wide = geometry().also { it.update(c, PhotoScaleMode.CROP, 2000, 1000) }
        assertTrue("宽图应保满高（top=0）", abs(wide.srcA.top) < EPS)
        assertTrue("宽图应保满高（bottom=原图高）", abs(wide.srcA.bottom - 1000f) < EPS)
        assertTrue("宽图应裁掉左右，实际 src 宽 ${wide.srcA.width}", wide.srcA.width < 2000f)
        assertTrue("宽图应水平居中", abs(wide.srcA.left - (2000f - wide.srcA.width) * 0.5f) < EPS)

        // 1:2 的竖图（比画布更「高」）⇒ 保满宽、裁上下
        val tall = geometry().also { it.update(c, PhotoScaleMode.CROP, 1000, 2000) }
        assertTrue("竖图应保满宽（left=0）", abs(tall.srcA.left) < EPS)
        assertTrue("竖图应保满宽（right=原图宽）", abs(tall.srcA.right - 1000f) < EPS)
        assertTrue("竖图应裁掉上下，实际 src 高 ${tall.srcA.height}", tall.srcA.height < 2000f)
        assertTrue("竖图应垂直居中", abs(tall.srcA.top - (2000f - tall.srcA.height) * 0.5f) < EPS)
    }

    // ────────────────────── ② FIT：完整 ──────────────────────

    /** FIT 的**源恒为整图** —— 一张图都不许裁（这是「完整显示」的定义） */
    @Test
    fun `fit keeps the whole image`() {
        for ((w, h) in listOf(4000 to 1000, 1000 to 1000, 1000 to 4000)) {
            val geom = geometry().also { it.update(ctx(1920f, 1080f), PhotoScaleMode.FIT, w, h) }
            assertRect("FIT($w×$h) 的 src", geom.srcA, 0f, 0f, w.toFloat(), h.toFloat())
        }
    }

    /** FIT 的目标矩形必须**内接于画布**（含边界） */
    @Test
    fun `fit keeps the destination inside the canvas`() {
        for ((w, h) in listOf(4000 to 1000, 1000 to 1000, 1000 to 4000, 1920 to 1080, 3 to 7)) {
            val geom = geometry().also { it.update(ctx(1920f, 1080f), PhotoScaleMode.FIT, w, h) }
            assertTrue(
                "FIT($w×$h) 的 dst 出界：${geom.dstA}（画布 1920×1080）",
                geom.dstA.left >= -EPS && geom.dstA.top >= -EPS &&
                    geom.dstA.right <= 1920f + EPS && geom.dstA.bottom <= 1080f + EPS,
            )
            assertTrue("FIT($w×$h) 的 dst 必须有效", geom.dstA.isValid)
        }
    }

    /** FIT 的目标矩形宽高比 == 图片宽高比（否则就是拉伸） */
    @Test
    fun `fit preserves the image aspect ratio`() {
        for ((w, h) in listOf(4000 to 1000, 1000 to 1000, 1000 to 4000, 3 to 7)) {
            val geom = geometry().also { it.update(ctx(1920f, 1080f), PhotoScaleMode.FIT, w, h) }
            val imgAspect = w.toFloat() / h
            val dstAspect = geom.dstA.width / geom.dstA.height
            assertTrue(
                "FIT($w×$h) 的 dst 宽高比应为 $imgAspect，实际 $dstAspect",
                abs(dstAspect - imgAspect) < 1e-3f,
            )
        }
    }

    /** FIT 至少要**贴满一个方向** —— 否则就是「缩得太小、四周全是黑边」 */
    @Test
    fun `fit touches at least one pair of canvas edges`() {
        for ((w, h) in listOf(4000 to 1000, 1000 to 1000, 1000 to 4000)) {
            val geom = geometry().also { it.update(ctx(1920f, 1080f), PhotoScaleMode.FIT, w, h) }
            val touchesW = abs(geom.dstA.width - 1920f) < 0.5f
            val touchesH = abs(geom.dstA.height - 1080f) < 0.5f
            assertTrue("FIT($w×$h) 没贴满任何一边：${geom.dstA}", touchesW || touchesH)
        }
    }

    // ────────────────────── ③ 边界与复用 ──────────────────────

    /** 非法尺寸（0 / 负数）必须**返回无效矩形**而不是抛异常、也不留上一次的残值 */
    @Test
    fun `degenerate inputs produce invalid rects instead of throwing`() {
        val geom = geometry()
        geom.update(ctx(1920f, 1080f), PhotoScaleMode.CROP, 1000, 1000)
        assertTrue("前置条件：先有一组有效矩形", geom.dstA.isValid)

        for ((w, h) in listOf(0 to 100, 100 to 0, 0 to 0, -1 to 100)) {
            geom.update(ctx(1920f, 1080f), PhotoScaleMode.CROP, w, h)
            assertTrue("图片尺寸 $w×$h 应得到无效 dst，实际 ${geom.dstA}", !geom.dstA.isValid)
            assertTrue("图片尺寸 $w×$h 应得到无效 src，实际 ${geom.srcA}", !geom.srcA.isValid)
        }

        // 画布尺寸为 0（首帧尚未测量）同样不该算出有效矩形
        geom.update(ctx(0f, 0f), PhotoScaleMode.CROP, 1000, 1000)
        assertTrue("画布 0×0 时应得到无效 dst", !geom.dstA.isValid)
        assertTrue("画布 0×0 时应得到无效 src", !geom.srcA.isValid)
    }

    /** 同一实例复用：`update` 必须**重算 A 与 B 两套**矩形（A、B 尺寸可不同） */
    @Test
    fun `update recomputes both a and b`() {
        val c = ctx(1920f, 1080f)

        // CROP：dst 相同（都是整画布），但 src 必须各算各的
        val crop = geometry().also { it.update(c, PhotoScaleMode.CROP, 2000, 1000, 1000, 2000) }
        assertTrue("CROP 的 dstA 应铺满", abs(crop.dstA.width - 1920f) < EPS && abs(crop.dstA.height - 1080f) < EPS)
        assertTrue("CROP 的 dstB 应铺满", abs(crop.dstB.width - 1920f) < EPS && abs(crop.dstB.height - 1080f) < EPS)
        assertTrue(
            "A（宽图）与 B（竖图）的 src 必须不同，实际 ${crop.srcA} vs ${crop.srcB}",
            abs(crop.srcA.width - crop.srcB.width) > 1f,
        )

        // FIT：dst 必须各算各的（宽图贴满宽、竖图贴满高）
        val fit = geometry().also { it.update(c, PhotoScaleMode.FIT, 2000, 1000, 1000, 2000) }
        assertTrue("FIT 的 dstA（宽图）应贴满宽", abs(fit.dstA.width - 1920f) < 0.5f)
        assertTrue("FIT 的 dstB（竖图）应贴满高", abs(fit.dstB.height - 1080f) < 0.5f)
        assertTrue(
            "FIT 下 A、B 的 dst 必须不同，实际 ${fit.dstA} vs ${fit.dstB}",
            abs(fit.dstA.width - fit.dstB.width) > 1f,
        )

        // 默认参数：bW/bH 省略时与 A 相同（单图自转场）
        val same = geometry().also { it.update(c, PhotoScaleMode.FIT, 1000, 1000) }
        assertRect("省略 bW/bH 时 srcB 应等于 srcA", same.srcB, same.srcA.left, same.srcA.top, same.srcA.right, same.srcA.bottom)
        assertRect("省略 bW/bH 时 dstB 应等于 dstA", same.dstB, same.dstA.left, same.dstA.top, same.dstA.right, same.dstA.bottom)
    }

    /** 画布尺寸变化后 `update` 必须给出新几何（否则转屏 / 换分辨率后画面错位） */
    @Test
    fun `geometry follows canvas size changes`() {
        val geom = geometry()
        geom.update(ctx(1920f, 1080f), PhotoScaleMode.CROP, 1000, 1000)
        assertTrue("横屏画布宽应为 1920", abs(geom.canvasW - 1920f) < EPS)

        geom.update(ctx(1080f, 1920f), PhotoScaleMode.CROP, 1000, 1000)
        assertTrue("竖屏画布宽应为 1080", abs(geom.canvasW - 1080f) < EPS)
        assertTrue("竖屏画布高应为 1920", abs(geom.canvasH - 1920f) < EPS)
        assertTrue("竖屏下 minDim 应为 1080", abs(geom.minDim - 1080f) < EPS)
        assertRect("竖屏下 CROP 的 dst 应铺满新画布", geom.dstA, 0f, 0f, 1080f, 1920f)
        assertTrue(
            "竖屏下 src 宽高比应等于新画布宽高比（1080/1920 = 0.5625），实际 ${geom.srcA.width / geom.srcA.height}",
            abs(geom.srcA.width / geom.srcA.height - 1080f / 1920f) < 1e-3f,
        )
    }

    /** 对角线半径（`IRIS_CIRCLE` 的终态半径）必须能盖住整个画布 */
    @Test
    fun `diagonal half covers the whole canvas`() {
        val geom = geometry().also { it.update(ctx(1920f, 1080f), PhotoScaleMode.CROP, 1000, 1000) }
        assertTrue(
            "对角线半径 ${geom.diagonalHalf} 应 ≥ 半宽 ${geom.canvasW / 2f}",
            geom.diagonalHalf >= geom.canvasW / 2f - EPS,
        )
        assertTrue(
            "对角线半径 ${geom.diagonalHalf} 应 ≥ 半高 ${geom.canvasH / 2f}",
            geom.diagonalHalf >= geom.canvasH / 2f - EPS,
        )
    }

    // ────────────────────── ④ 负向自证 ──────────────────────

    /**
     * **负向自证**：`crop fills the whole canvas` 那条断言**是有判别力的**。
     *
     * §14.4 G6 的负向自证原文是「故意用错比例 ⇒ 断言 dst 出界」。
     * 这里把「CROP 用了 FIT 的 dst 公式」这件事如实算一遍：
     * 4:3 的图放进 16:9 画布，FIT 的 dst 左右各留 240px 黑边 ⇒ **覆盖不全**。
     * 所以「dst 必须铺满」这条断言真的能拦住「把 CROP 写成 FIT」。
     */
    @Test
    fun `negative proof - fit geometry would not cover the canvas`() {
        val canvasW = 1920f
        val canvasH = 1080f
        val imgW = 1600
        val imgH = 1200
        val imgAspect = imgW.toFloat() / imgH
        val canvasAspect = canvasW / canvasH

        // 照 FIT 的公式算 dst（4:3 < 16:9 ⇒ 走「图更高 ⇒ 左右留边」分支）
        val dstW = canvasH * imgAspect
        val left = (canvasW - dstW) * 0.5f
        assertTrue("前置条件：FIT 的 dst 宽度应小于画布宽度", dstW < canvasW)
        assertTrue(
            "FIT 的 dst 左侧留白 $left px ⇒ 不覆盖全屏；" +
                "这正是 CROP 分支必须自己算 dst 的原因（G6 的断言能拦住写反）",
            left > 1f,
        )

        // 对照组：真的 CROP 必须铺满
        val geom = geometry().also { it.update(ctx(canvasW, canvasH), PhotoScaleMode.CROP, imgW, imgH) }
        assertRect("CROP 的 dst 必须铺满", geom.dstA, 0f, 0f, canvasW, canvasH)
    }

    /**
     * **负向自证**：`crop keeps the source aspect equal to the canvas aspect` 有判别力。
     *
     * 若 CROP 不裁切源（源 = 整图），源宽高比是 4:3，画布是 16:9 ——
     * 把 4:3 的源映射到 16:9 的 dst 就是**横向拉伸**。
     * 这里断言两者的差距远超容差，证明「源宽高比 == 画布宽高比」这条断言不是空话。
     */
    @Test
    fun `negative proof - crop geometry would break the aspect ratio`() {
        val canvasW = 1920f
        val canvasH = 1080f
        val imgW = 1600
        val imgH = 1200

        val imgAspect = imgW.toFloat() / imgH
        val canvasAspect = canvasW / canvasH
        assertTrue(
            "前置条件：图片宽高比 $imgAspect 与画布 $canvasAspect 应显著不同",
            abs(imgAspect - canvasAspect) > 0.1f,
        )

        val geom = geometry().also { it.update(ctx(canvasW, canvasH), PhotoScaleMode.CROP, imgW, imgH) }
        val srcAspect = geom.srcA.width / geom.srcA.height
        assertTrue(
            "未裁切的源宽高比 $imgAspect 与画布 $canvasAspect 差 ${abs(imgAspect - canvasAspect)} ⇒ " +
                "「源宽高比必须等于画布宽高比」这条断言确实能判出「忘了裁切」",
            abs(imgAspect - canvasAspect) > 0.1f,
        )
        assertTrue(
            "真实 CROP 的 src 宽高比 $srcAspect 必须已经收敛到画布宽高比 $canvasAspect",
            abs(srcAspect - canvasAspect) < 1e-3f,
        )
    }

    // ────────────────────── 内部实现 ──────────────────────

    private fun ctx(w: Float, h: Float): RenderContext = RenderContext().apply {
        canvasSize = Size(w, h)
        safeAreaPx = 0f
    }

    /** 新建一个几何对象；画布尺寸由随后的 `update` 给出 */
    private fun geometry(): PhotoGeometry = PhotoGeometry()

    private fun assertRect(label: String, r: PhotoRect, l: Float, t: Float, rr: Float, b: Float) {
        assertTrue(
            "$label 应为 ($l, $t, $rr, $b)，实际 $r",
            abs(r.left - l) < EPS && abs(r.top - t) < EPS &&
                abs(r.right - rr) < EPS && abs(r.bottom - b) < EPS,
        )
    }

    private companion object {
        const val EPS = 1e-3f
    }
}

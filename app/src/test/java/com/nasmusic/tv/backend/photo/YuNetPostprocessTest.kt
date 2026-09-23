package com.nasmusic.tv.backend.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YuNet 后处理的门禁（G16）
 *
 * ⚠️ 这是**纯 JVM** 测试：`YuNetPostprocess` 不碰 ORT / Bitmap / Context。
 * 「推理结果对不对」本机验证不了 ⇒ 能钉住的只有这一段，所以它的断言必须把
 * 两种真实错法都模拟进去（见各 `negative proof` 用例）。
 */
class YuNetPostprocessTest {

    // ────────────────────────── decode ──────────────────────────

    @Test
    fun `decode produces the OpenCV box formula exactly`() {
        // bbox = (dx=0.5, dy=0.25, ln(4), ln(2))；stride=8，cols=20 ⇒ idx=26 → (r=1, c=6)
        val cls = FloatArray(40).also { it[26] = 1f }
        val obj = FloatArray(40).also { it[26] = 1f }
        val bbox = FloatArray(160).also {
            it[26 * 4 + 0] = 0.5f
            it[26 * 4 + 1] = 0.25f
            it[26 * 4 + 2] = kotlin.math.ln(4f)
            it[26 * 4 + 3] = kotlin.math.ln(2f)
        }
        val out = mutableListOf<YuNetPostprocess.Box>()
        YuNetPostprocess.decode(
            stride = 8, cols = 20, rows = 2,
            cls = cls, obj = obj, bbox = bbox,
            scoreThreshold = 0.6f, out = out,
        )
        // idx = r*cols + c ⇒ idx=26 对应 (r=1, c=6)
        // cx = (6 + 0.5) * 8 = 52 ; cy = (1 + 0.25) * 8 = 10
        assertEquals(1, out.size)
        val b = out[0]
        assertEquals(52f - 16f, b.x1, 1e-4f)
        assertEquals(10f - 8f, b.y1, 1e-4f)
        assertEquals(32f, b.w, 1e-4f)
        assertEquals(16f, b.h, 1e-4f)
        assertEquals(1f, b.score, 1e-4f)
    }

    @Test
    fun `score is sqrt of clamped cls times obj`() {
        // cls = 2.0（越界，应 clamp 到 1）、obj = 0.36 ⇒ score = sqrt(1 * 0.36) = 0.6
        val cls = FloatArray(4).also { it[1] = 2f }
        val obj = FloatArray(4).also { it[1] = 0.36f }
        val bbox = FloatArray(16)
        val out = mutableListOf<YuNetPostprocess.Box>()
        YuNetPostprocess.decode(8, 2, 2, cls, obj, bbox, 0.6f, out)
        assertEquals(1, out.size)
        assertEquals(0.6f, out[0].score, 1e-5f)
    }

    @Test
    fun `negative proof - without clamp a negative logit becomes NaN and always passes the threshold`() {
        // 真实错法：直接 sqrt(cls * obj)。cls = -0.04 ⇒ NaN；`NaN < threshold` 为 false
        // ⇒ 这张图会被误判为「有人脸」。
        val cls = FloatArray(4).also { it[0] = -0.04f }
        val obj = FloatArray(4).also { it[0] = 0.9f }
        val wrong: (Float, Float) -> Float = { c, o -> kotlin.math.sqrt(c * o) }
        val score = wrong(cls[0], obj[0])
        assertTrue(
            "前置条件：不加 clamp 时负 logit 必须产生 NaN（否则这条自证没有测到真实错法）",
            score.isNaN(),
        )
        assertTrue("NaN 与任何阈值比较都是 false ⇒ 旧写法会把它当候选保留", !(score < 0.6f))

        // 正确实现：clamp 后 sqrt(0 * 0.9) = 0 ⇒ 低于阈值被丢弃
        val out = mutableListOf<YuNetPostprocess.Box>()
        YuNetPostprocess.decode(8, 2, 2, cls, obj, FloatArray(16), 0.6f, out)
        assertEquals("clamp 后负 logit 必须被丢弃", 0, out.size)
    }

    @Test
    fun `negative proof - forgetting exp deflates boxes so NMS can no longer merge duplicates`() {
        // 真实错法：w = bbox[b+2] * stride（漏了 exp）。模型输出的是 ln(w/stride)
        //（典型量级 ±2），漏 exp 后框宽变成「几像素到二十几像素」—— 与 stride（8px）
        // 的格距同量级 ⇒ 相邻 anchor 的框不再重叠 ⇒ NMS 把同一张脸数成好几张。
        val stride = 8f
        val bboxVal = kotlin.math.ln(4f)                    // ln(w/stride)，真实输出量级
        val rightW = kotlin.math.exp(bboxVal) * stride      // 32px（正确）
        val wrongW = bboxVal * stride                       // ≈11.1px（错法）

        // 两个相邻 anchor（间距 = stride = 8px）
        val aWrong = YuNetPostprocess.Box(0f, 0f, wrongW, wrongW, 0.9f)
        val bWrong = YuNetPostprocess.Box(stride, 0f, wrongW, wrongW, 0.8f)
        assertTrue(
            "前置条件：漏 exp 时相邻 anchor 的 IoU 必须落到 NMS 阈值(0.3)之下（否则这条自证没有测到真实错法）",
            YuNetPostprocess.iou(aWrong, bWrong) <= 0.3f,
        )
        // 正确宽度（32px > 8px 格距）⇒ 明显重叠 ⇒ NMS 能合并
        val aRight = YuNetPostprocess.Box(0f, 0f, rightW, rightW, 0.9f)
        val bRight = YuNetPostprocess.Box(stride, 0f, rightW, rightW, 0.8f)
        assertTrue("exp 之后相邻 anchor 必然超过 NMS 阈值（才能被合并）", YuNetPostprocess.iou(aRight, bRight) > 0.3f)
    }

    @Test
    fun `candidates below the threshold are dropped and short arrays are ignored`() {
        val cls = FloatArray(8).also { it[0] = 1f }
        val obj = FloatArray(8).also { it[0] = 0.16f }   // score = 0.4 < 0.6
        val out = mutableListOf<YuNetPostprocess.Box>()
        YuNetPostprocess.decode(8, 4, 2, cls, obj, FloatArray(32), 0.6f, out)
        assertEquals(0, out.size)

        // 数据不足 ⇒ 直接返回（不抛下标越界）
        YuNetPostprocess.decode(8, 100, 100, FloatArray(3), FloatArray(3), FloatArray(3), 0.6f, out)
        assertEquals(0, out.size)
    }

    // ────────────────────────── nms / iou ──────────────────────────

    @Test
    fun `nms keeps the highest score and drops heavy overlaps`() {
        val boxes = listOf(
            YuNetPostprocess.Box(0f, 0f, 100f, 100f, 0.95f),
            YuNetPostprocess.Box(10f, 0f, 100f, 100f, 0.90f),   // 与第一个几乎重合
            YuNetPostprocess.Box(500f, 500f, 100f, 100f, 0.80f), // 独立的一张脸
        )
        val out = YuNetPostprocess.nms(boxes, iouThreshold = 0.3f, topK = 0, out = mutableListOf())
        assertEquals(2, out.size)
        assertEquals(0.95f, out[0].score, 1e-6f)
        assertEquals(0.80f, out[1].score, 1e-6f)
    }

    @Test
    fun `negative proof - skipping nms inflates faceCount for the same face`() {
        // 真实错法：不做 NMS，把所有过阈值的 anchor 都算成人脸。
        val boxes = listOf(
            YuNetPostprocess.Box(0f, 0f, 100f, 100f, 0.95f),
            YuNetPostprocess.Box(10f, 0f, 100f, 100f, 0.90f),
            YuNetPostprocess.Box(-10f, 5f, 100f, 100f, 0.85f),
        )
        val kept = YuNetPostprocess.nms(boxes, 0.3f, 0, mutableListOf())
        assertTrue(
            "前置条件：三个高重叠框必须被 NMS 合并成 1 个（否则这条自证没有测到真实错法）",
            kept.size == 1,
        )
        assertEquals("不做 NMS 会把同一张脸数成 3 张", 3, boxes.size)
    }

    @Test
    fun `topK limits candidates considered before nms`() {
        val boxes = (0 until 10).map {
            YuNetPostprocess.Box(0f, 0f, 100f, 100f, 0.5f + it * 0.01f)
        }
        // topK=1 ⇒ 只考虑最高分的一个 ⇒ 结果恰好 1 个
        val out = YuNetPostprocess.nms(boxes, 0.3f, topK = 1, out = mutableListOf())
        assertEquals(1, out.size)
        assertEquals(0.59f, out[0].score, 1e-6f)
    }

    @Test
    fun `iou is 1 for identical boxes and 0 for disjoint or degenerate ones`() {
        val a = YuNetPostprocess.Box(0f, 0f, 10f, 10f, 1f)
        assertEquals(1f, YuNetPostprocess.iou(a, a.copy()), 1e-6f)
        assertEquals(0f, YuNetPostprocess.iou(a, a.copy(x1 = 100f)), 1e-6f)
        // 退化框（宽为 0）不算重叠
        assertEquals(0f, YuNetPostprocess.iou(a, a.copy(w = 0f)), 1e-6f)
    }

    @Test
    fun `decode accumulates across strides into the same list`() {
        // 三个 stride 各贡献一个候选（模拟 8/16/32 三个头各检出一张脸）
        val out = mutableListOf<YuNetPostprocess.Box>()
        val cls = FloatArray(4).also { it[0] = 1f }
        val obj = FloatArray(4).also { it[0] = 1f }
        val bbox = FloatArray(16)
        YuNetPostprocess.decode(8, 2, 2, cls, obj, bbox, 0.6f, out)
        YuNetPostprocess.decode(16, 2, 2, cls, obj, bbox, 0.6f, out)
        YuNetPostprocess.decode(32, 2, 2, cls, obj, bbox, 0.6f, out)
        assertEquals(3, out.size)
        // 不同 stride 的框尺寸不同 ⇒ 互不重叠（各是一张脸）
        val kept = YuNetPostprocess.nms(out, 0.3f, 0, mutableListOf())
        assertEquals(3, kept.size)
    }
}

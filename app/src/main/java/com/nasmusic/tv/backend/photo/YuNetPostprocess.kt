package com.nasmusic.tv.backend.photo

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * YuNet（ONNX）**后处理** —— 从 `cls/obj/bbox` 三个头的原始张量解出人脸框并做 NMS。
 *
 * ## 为什么单独成类
 *
 * 这一步是**纯数值计算**，不碰 `OrtSession` / `Bitmap` / `Context`。
 * 抽出来之后它可以在**纯 JVM 单测**里用合成张量验证（门禁 G16）——
 * 而「ONNX 推理对不对」本机**验证不了**（没有真机、也没有可跑的 ORT 环境）。
 * 也就是说：**能被钉住的只有这一段，所以必须把它和被钉不住的那段切开。**
 *
 * ## 算法来源（⚠️ 逐行照抄，不要「优化」）
 *
 * OpenCV `FaceDetectorYNImpl::postProcess`（`modules/objdetect/src/face_detect.cpp@4.x`）
 * 与 `NMSFast_`（`modules/dnn/src/nms.inl.hpp@4.x`）：
 *
 * ```
 * cls_score = clamp(cls[idx], 0, 1); obj_score = clamp(obj[idx], 0, 1)
 * score     = sqrt(cls_score * obj_score)
 * cx = (c + bbox[idx*4 + 0]) * stride
 * cy = (r + bbox[idx*4 + 1]) * stride
 * w  = exp(bbox[idx*4 + 2]) * stride
 * h  = exp(bbox[idx*4 + 3]) * stride
 * x1 = cx - w / 2 ; y1 = cy - h / 2
 * ```
 *
 * 三个 stride（8 / 16 / 32）各自一张特征图，`cols = padW / stride`、`rows = padH / stride`。
 *
 * ## ⛔ 两个极易写错的细节
 *
 * | # | 坑 | 后果 |
 * |---|---|---|
 * | 1 | `score` 忘了 `clamp(0,1)` | 模型输出的 logit 可能是负数 / >1 ⇒ `sqrt(负数)` = `NaN`，`NaN < threshold` 为 `false` ⇒ **整张图被误判为「有人脸」** |
 * | 2 | 宽高忘了 `exp(...)` | 得到的框是 `ln(w/stride)` 量级（几十分之一像素）⇒ NMS 认为互不重叠 ⇒ 同一张脸被数成几十张 |
 *
 * ## NMS 的口径
 *
 * OpenCV `FaceDetectorYN` 调 `NMSBoxes(..., eta = 1.f, top_k)`，而 `NMSFast_` 里
 * 只有 `eta < 1` 才启用自适应阈值 ⇒ `eta = 1` 就是**标准贪心 NMS**
 * （按分数降序，与已保留框的 IoU 超过阈值就丢弃）。这里照此实现。
 *
 * ⚠️  overlaps 用的是 **IoU**（`rectOverlap = 1 - jaccardDistance`），不是「交集 / 最小面积」。
 */
object YuNetPostprocess {

    /** 三个检测头的步长（顺序**必须**与模型的 `*_8 / *_16 / *_32` 一致） */
    val STRIDES: IntArray = intArrayOf(8, 16, 32)

    /** 一个候选框（**未**做 NMS） */
    data class Box(
        val x1: Float,
        val y1: Float,
        val w: Float,
        val h: Float,
        val score: Float,
    )

    /**
     * 解码一个 stride 的预测。
     *
     * @param cls 形状 `[1, anchors, 1]` 的**扁平**浮点数据（长度 = `rows * cols`）
     * @param obj 同上
     * @param bbox 形状 `[1, anchors, 4]` 的**扁平**数据（长度 = `rows * cols * 4`，XYWH 顺序）
     * @param out 候选框追加到这里（**不清空** —— 调用方负责，便于跨 stride 复用同一个表）
     */
    fun decode(
        stride: Int,
        cols: Int,
        rows: Int,
        cls: FloatArray,
        obj: FloatArray,
        bbox: FloatArray,
        scoreThreshold: Float,
        out: MutableList<Box>,
    ) {
        val anchors = cols * rows
        if (anchors <= 0) return
        if (cls.size < anchors || obj.size < anchors || bbox.size < anchors * 4) return

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c

                // ⛔ clamp 不能省：logit 出界会让 sqrt 得到 NaN（见类 KDoc 坑 1）
                val cs = cls[idx].coerceIn(0f, 1f)
                val os = obj[idx].coerceIn(0f, 1f)
                val score = sqrt(cs * os)
                if (score < scoreThreshold) continue

                val b = idx * 4
                // ⛔ 宽高是 **exp**（见类 KDoc 坑 2）
                val w = exp(bbox[b + 2]) * stride
                val h = exp(bbox[b + 3]) * stride
                val cx = (c + bbox[b]) * stride
                val cy = (r + bbox[b + 1]) * stride

                out.add(Box(cx - w * 0.5f, cy - h * 0.5f, w, h, score))
            }
        }
    }

    /**
     * 贪心 NMS（OpenCV `NMSFast_` 在 `eta = 1` 时的行为）。
     *
     * ⚠️ [topK] 是「**进 NMS 之前**最多考虑多少个候选」（OpenCV `GetMaxScoreIndex` 的语义），
     * 不是「最多保留几个」—— 所以它是**加速**手段，不是结果上限。
     *
     * @return 保留下来的框（按分数降序）；直接复用调用方给的 [out]（`clear()` 后写入）
     */
    fun nms(
        boxes: List<Box>,
        iouThreshold: Float,
        topK: Int,
        out: MutableList<Box>,
    ): MutableList<Box> {
        out.clear()
        if (boxes.isEmpty()) return out

        val sorted = boxes.sortedByDescending { it.score }
        val limit = if (topK > 0) topK else sorted.size
        val considered = if (limit < sorted.size) sorted.subList(0, limit) else sorted

        for (box in considered) {
            var keep = true
            for (k in out.indices) {
                if (iou(box, out[k]) > iouThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) out.add(box)
        }
        return out
    }

    /** 交并比（IoU）；任一框退化（宽或高 ≤ 0）返回 0 */
    fun iou(a: Box, b: Box): Float {
        val aw = a.w
        val ah = a.h
        val bw = b.w
        val bh = b.h
        if (aw <= 0f || ah <= 0f || bw <= 0f || bh <= 0f) return 0f

        val left = maxOf(a.x1, b.x1)
        val top = maxOf(a.y1, b.y1)
        val right = minOf(a.x1 + aw, b.x1 + bw)
        val bottom = minOf(a.y1 + ah, b.y1 + bh)

        val iw = right - left
        val ih = bottom - top
        if (iw <= 0f || ih <= 0f) return 0f

        val inter = iw * ih
        val union = aw * ah + bw * bh - inter
        if (union <= 0f) return 0f
        return inter / union
    }
}

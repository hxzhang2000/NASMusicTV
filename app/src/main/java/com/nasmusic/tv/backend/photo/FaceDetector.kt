package com.nasmusic.tv.backend.photo

/**
 * 人脸检测的**两个接缝**（§10.1）—— 存在的唯一理由是「让不能跑 ONNX 的本机也能验证逻辑」。
 *
 * ONNX Runtime 的推理结果**本机验证不了**（没有可跑的 ORT 环境，也没有真机），
 * 所以所有「能被钉住的逻辑」必须与「跑推理的那一段」彻底切开：
 *
 * | 接缝 | 生产实现 | 单测替身 |
 * |---|---|---|
 * | [FaceDetector] | `YuNetFaceDetector`（ORT + `assets/models/yunet_face.onnx`） | 假实现：按 `PhotoRef.id` 决定有没有脸 |
 * | [PhotoThumbProvider] | `PhotoThumbnailProvider`（`openStream` + `BitmapFactory` 降采样） | 假实现：直接返回合成 `FaceThumb` |
 *
 * ⛔ 两个接缝都在 **`FaceThumb` 这一层**交接，**不经过 `Bitmap`** ——
 * 否则单测就得拉起 Robolectric 才能造一张位图，等于又把「数值逻辑」和「Android」绑回去。
 */

/**
 * 一张缩略图的**张量形态**：CHW + **BGR** 通道序 + `0..255` 浮点。
 *
 * ⛔ **BGR 不是笔误**：OpenCV 的 `FaceDetectorYN` 走 `blobFromImage(image)`，
 * 它的 `swapRB` 默认是 `false` ⇒ 喂进模型的是 OpenCV 原生的 **BGR** 顺序。
 * 写成 RGB 不会崩，但检测率会明显下降（模型是按 BGR 训的）。
 *
 * ⛔ **不做归一化**（不减均值、不除 255）：同上，`blobFromImage` 的 `scalefactor` 也是 1.0。
 *
 * @param w 实际宽度（正方形边长）
 * @param h 实际高度
 * @param bgr 长度 `3 * w * h` 的平面数据：`[B 平面][G 平面][R 平面]`
 */
class FaceThumb(
    val w: Int,
    val h: Int,
    val bgr: FloatArray,
)

/**
 * 「这张缩略图里有几张人脸」。
 *
 * ⚠️ 返回值语义是「**NMS 之后**的人脸数」，`0` = 没检出。
 * 「这张照片含不含人像」= `detect(thumb) > 0`。
 */
interface FaceDetector {

    /**
     * 准备推理环境（加载模型 / 建 session）。**幂等**，失败可重试。
     *
     * ⛔ 必须**单独于 [detect]**：`FaceScanManager` 要在**开跑之前**就知道模型能不能用
     * ⇒ 否则「ORT 加载失败」会退化成「每张照片都返回 0」⇒ 用户看到的是
     * 「开了仅显示人像后照片墙空了」，而真正的原因是模型没起来。
     *
     * @return `false` = 不可用（调用方应转为「本功能不可用」，而不是继续跑 1 万张空推理）
     */
    fun warmUp(): Boolean

    fun detect(thumb: FaceThumb): Int
}

/**
 * 把 [PhotoRef] 解码成 [FaceThumb]（**只喂缩略图，不喂原图** ——
 * §10.2：这是耗时降一个数量级的关键）。
 *
 * @return 读不出来（拔盘 / 坏文件 / 网络失败）返回 `null`，调用方直接跳过这张
 */
fun interface PhotoThumbProvider {
    suspend fun decode(ref: PhotoRef): FaceThumb?
}

package com.nasmusic.tv.backend.photo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.visualizer.photo.PhotoBufferMath
import java.nio.FloatBuffer

/**
 * YuNet 人脸检测（`assets/models/yunet_face.onnx` + ONNX Runtime）
 *
 * ## 模型
 *
 * OpenCV Zoo 的 `face_detection_yunet_2026may`（229,738 字节，**动态输入尺寸**）。
 * ⚠️ 文档 §14.1 写的是「337 KB」—— 实际拿到的是**动态尺寸版**，体积略小。
 * 选动态尺寸版是因为它的 `height` / `width` 是符号维 ⇒ 可以**直接喂 320×320**；
 * 静态版（`2023mar`）的输入被钉死在 640×640，喂 320 会直接报 shape 不匹配。
 *
 * ## 三个头的形状
 *
 * 模型输出 12 个张量（每个 stride 一组）：
 *
 * ```
 * cls_8/16/32   [1, anchors, 1]   是否人脸
 * obj_8/16/32   [1, anchors, 1]   是否前景
 * bbox_8/16/32  [1, anchors, 4]   (dx, dy, ln w, ln h)
 * kps_8/16/32   [1, anchors, 10]  5 个关键点 —— ⛔ 本项目**不用**（只要「有没有脸」）
 * ```
 *
 * 只请求前 9 个（`run` 的 `requestedOutputs` 参数）—— 少解三个张量，省一次内存拷贝。
 *
 * ## ⛔ 两个 API 陷阱（都栽过）
 *
 * | # | 陷阱 | 处理 |
 * |---|---|---|
 * | 1 | `OrtSession.Result.get(String)` 返回 **`java.util.Optional`**（需 API 24，本项目 minSdk 22） | 一律用 `Result` 的 **迭代器**取输出，**绝不**调 `get(String)` |
 * | 2 | `warmUp` 里加载失败会抛 `UnsatisfiedLinkError`（**不是** `Exception`） | 兜 `Throwable`，否则整个扫描任务直接崩 |
 *
 * ## 阈值选择
 *
 * OpenCV 官方 demo 用 `score_threshold = 0.9`。这里用 **0.6**，理由是**两侧代价不对称**：
 * - 误报（没脸判成有脸）⇒ 照片墙里多混进一张风景照 —— 代价低；
 * - 漏报（有脸判成没脸）⇒ 用户开了「仅显示含人像」却找不到家人的照片 —— 代价高。
 *
 * @param context 用来读 assets（只取 `applicationContext`，不持有 Activity）
 * @param inputSize 检测边长（正方形）。§10.2 要求 320 —— **不要调大**：
 *   耗时与边长平方成正比，640 会让老电视的单张耗时从 ~100ms 涨到 ~400ms
 */
class YuNetFaceDetector(
    private val context: Context,
    private val inputSize: Int = DEFAULT_INPUT_SIZE,
    private val scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
    private val iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
    private val topK: Int = DEFAULT_TOP_K,
) : FaceDetector {

    private val appContext = context.applicationContext

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var failed = false

    private val candidates = ArrayList<YuNetPostprocess.Box>(64)
    private val kept = ArrayList<YuNetPostprocess.Box>(8)

    /** 9 个输出张量的数据（尺寸恒定 ⇒ 只分配一次，避免每照片分配 9 个数组） */
    private val scratch: Array<FloatArray?> = arrayOfNulls(STRIDE_COUNT * 3)

    /** 本轮哪些 slot 真的拿到了数据（模型少给一个头时不要拿上一张的旧数据凑数） */
    private val filled = BooleanArray(STRIDE_COUNT * 3)

    override fun warmUp(): Boolean {
        session?.let { return true }
        if (failed) return false
        return try {
            val bytes = appContext.assets.open(ASSET_PATH).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                // 扫描跑在单条后台线程上，且**不能抢播放线程**的 CPU ⇒ 单线程 intra-op
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            val env = OrtEnvironment.getEnvironment()
            // ⛔ 绝不 close env：它是进程级单例（与 DemucsSeparator 同一条约定）
            session = env.createSession(bytes, opts)
            AppLog.i(TAG, "YuNet session ready: ${bytes.size} bytes, input=$inputSize")
            true
        } catch (t: Throwable) {
            // ⛔ 必须兜 Throwable（不是 Exception）：ORT 加载失败会抛 UnsatisfiedLinkError
            failed = true
            AppLog.e(TAG, "YuNet session failed: ${t.message}", t)
            false
        }
    }

    override fun detect(thumb: FaceThumb): Int {
        val s = session ?: return 0
        val w = thumb.w
        val h = thumb.h
        if (w <= 0 || h <= 0) return 0

        val input = try {
            OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                FloatBuffer.wrap(thumb.bgr),
                longArrayOf(1, 3, h.toLong(), w.toLong()),
            )
        } catch (t: Throwable) {
            AppLog.e(TAG, "create input tensor failed: ${t.message}", t)
            return 0
        }
        try {
            val result = s.run(mapOf(INPUT_NAME to input), REQUESTED_OUTPUTS)
            try {
                readOutputs(result)
                candidates.clear()
                for (i in 0 until STRIDE_COUNT) {
                    if (!filled[i * 3] || !filled[i * 3 + 1] || !filled[i * 3 + 2]) continue
                    val stride = YuNetPostprocess.STRIDES[i]
                    val cols = w / stride
                    val rows = h / stride
                    if (cols <= 0 || rows <= 0) continue

                    YuNetPostprocess.decode(
                        stride = stride, cols = cols, rows = rows,
                        cls = scratch[i * 3]!!,
                        obj = scratch[i * 3 + 1]!!,
                        bbox = scratch[i * 3 + 2]!!,
                        scoreThreshold = scoreThreshold,
                        out = candidates,
                    )
                }
                YuNetPostprocess.nms(candidates, iouThreshold, topK, kept)
                return kept.size
            } finally {
                result.close()
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "inference failed: ${t.message}", t)
            return 0
        } finally {
            input.close()
        }
    }

    /**
     * 把 9 个输出拷进 [scratch]。
     *
     * ⛔ **用 `Result` 的迭代器取输出，不要用 `result.get(name)`** —— 后者返回
     * `java.util.Optional`（需 API 24，本项目 minSdk 22 ⇒ `NoClassDefFoundError`）。
     *
     * ⛔ 数据必须**拷出来**再用：`result.close()` 之后 `FloatBuffer` 直接失效。
     */
    private fun readOutputs(result: OrtSession.Result) {
        filled.fill(false)
        for (entry in result) {
            val slot = SLOT_OF[entry.key] ?: continue
            val v = entry.value as? OnnxTensor ?: continue
            val src = v.floatBuffer ?: continue
            val n = src.remaining()
            if (n <= 0) continue
            var dst = scratch[slot]
            if (dst == null || dst.size < n) {
                dst = FloatArray(n)
                scratch[slot] = dst
            }
            src.get(dst, 0, n)
            filled[slot] = true
        }
    }

    /** 释放 session（不释放 `OrtEnvironment`）。幂等。 */
    fun close() {
        try {
            session?.close()
        } catch (t: Throwable) {
            AppLog.w(TAG, "close session failed: ${t.message}")
        }
        session = null
    }

    companion object {
        const val TAG = "YuNetFaceDetector"

        /** §10.2：只喂 320×320 缩略图 */
        const val DEFAULT_INPUT_SIZE = 320

        /** 见类 KDoc「阈值选择」：0.6 而不是 OpenCV demo 的 0.9 */
        const val DEFAULT_SCORE_THRESHOLD = 0.6f

        const val DEFAULT_IOU_THRESHOLD = 0.3f

        const val DEFAULT_TOP_K = 5_000

        const val ASSET_PATH = "models/yunet_face.onnx"

        private const val INPUT_NAME = "input"

        private const val STRIDE_COUNT = 3

        /** 输出名 → scratch 下标（顺序：每个 stride 的 cls / obj / bbox 交替） */
        private val SLOT_OF: Map<String, Int> = mapOf(
            "cls_8" to 0, "obj_8" to 1, "bbox_8" to 2,
            "cls_16" to 3, "obj_16" to 4, "bbox_16" to 5,
            "cls_32" to 6, "obj_32" to 7, "bbox_32" to 8,
        )

        /** 只请求前 9 个张量（关键点 `kps_*` 不用） */
        private val REQUESTED_OUTPUTS: Set<String> = SLOT_OF.keys

        /**
         * 位图 → [FaceThumb]（**保持长宽比**地装进 `size × size`，右下补 0）。
         *
         * ⛔ **不用 `createScaledBitmap(bmp, size, size)` 直接拉满**：那会把 16:9 的照片
         * 横向压扁 ⇒ 人脸变成宽脸，检测率下降。OpenCV 的 `padWithDivisor` 也是补右下角。
         *
         * ⛔ 通道序是 **BGR**（见 [FaceThumb] 的 KDoc）。
         */
        fun bitmapToFaceThumb(src: Bitmap, size: Int): FaceThumb {
            val sw = src.width
            val sh = src.height
            if (sw <= 0 || sh <= 0 || size <= 0) return FaceThumb(0, 0, FloatArray(0))

            val scale = minOf(size.toFloat() / sw, size.toFloat() / sh)
            val dw = maxOf(1, (sw * scale).toInt()).coerceAtMost(size)
            val dh = maxOf(1, (sh * scale).toInt()).coerceAtMost(size)

            // ⛔ lint 建议改用 KTX 的 `Bitmap.scale`，但**刻意不用**：`createScaledBitmap`
            //    在「目标尺寸 == 原尺寸」时直接返回原对象（不复制像素），上面的 `!== src`
            //    判定依赖这一行为；KTX scale 每次都新建位图 ⇒ 扫 1 万张时多 1 万次像素拷贝。
            @Suppress("UseKtx")
            val scaled = if (dw == sw && dh == sh) src else Bitmap.createScaledBitmap(src, dw, dh, true)
            val pixels = IntArray(dw * dh)
            scaled.getPixels(pixels, 0, dw, 0, 0, dw, dh)
            if (scaled !== src) scaled.recycle()

            val plane = size * size
            val bgr = FloatArray(plane * 3)
            for (y in 0 until dh) {
                val row = y * dw
                val dstRow = y * size
                for (x in 0 until dw) {
                    val p = pixels[row + x]
                    val d = dstRow + x
                    bgr[d] = (p and 0xFF).toFloat()                       // B
                    bgr[plane + d] = ((p shr 8) and 0xFF).toFloat()        // G
                    bgr[2 * plane + d] = ((p shr 16) and 0xFF).toFloat()   // R
                }
            }
            return FaceThumb(size, size, bgr)
        }
    }
}

/**
 * 把 [PhotoRef] 解码成 320×320 的 [FaceThumb]。
 *
 * ## 为什么单独走一遍解码（而不是复用 `PhotoBuffer`）
 *
 * `PhotoBuffer` 缓存的是**全屏尺寸**位图（几 MB 一张），且容量只有 3 张；
 * 人脸扫描要的是**一次性、扫完即弃**的 320×320 小图，两者生命周期完全不同。
 * 混用会让扫描把播放用的缓存挤掉 —— 用户正在看照片墙时后台在扫 ⇒ 每次换图都要重新解码。
 *
 * @param sources 已构造好的来源（与 `PhotoWallController` 用的是同一批）
 * @param size 检测边长
 */
class PhotoThumbnailProvider(
    private val sources: Map<PhotoSourceKind, PhotoSource>,
    private val size: Int = YuNetFaceDetector.DEFAULT_INPUT_SIZE,
) : PhotoThumbProvider {

    override suspend fun decode(ref: PhotoRef): FaceThumb? {
        val source = sources[ref.source] ?: return null
        val bytes = try {
            source.openStream(ref)?.use { it.readBytes() }
        } catch (t: Throwable) {
            // 拔盘 / 坏文件 / 网络失败 —— 扫描要继续，不能因为一张图炸掉整个任务
            null
        } ?: return null

        val bitmap = decodeBitmap(ref, bytes) ?: return null
        return try {
            YuNetFaceDetector.bitmapToFaceThumb(bitmap, size)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * ⚠️ 这里复用了 `visualizer.photo.PhotoBufferMath.computeInSampleSize`（`internal`，
     * 同模块可见，已有单测覆盖）—— **刻意不重写一遍**：那条「不能过度降采样」的规则
     * 很微妙，抄一份迟早会与播放路径不一致。
     *
     * 解码结果可能略大于 `size`（`inSampleSize` 是 2 的幂），由
     * `bitmapToFaceThumb` 缩到确切尺寸。
     */
    private fun decodeBitmap(ref: PhotoRef, bytes: ByteArray): Bitmap? {
        return try {
            if (ref.width > 0 && ref.height > 0) {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = PhotoBufferMath.computeInSampleSize(ref.width, ref.height, size, size)
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = PhotoBufferMath.computeInSampleSize(
                        bounds.outWidth, bounds.outHeight, size, size,
                    )
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (t: Throwable) {
            null
        }
    }
}

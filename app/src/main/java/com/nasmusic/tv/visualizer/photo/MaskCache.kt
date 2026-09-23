package com.nasmusic.tv.visualizer.photo

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nasmusic.tv.visualizer.VisualizerRandom

/**
 * 预生成遮罩位图缓存（§14.3「遮罩资源」）
 *
 * ## 为什么是 `object`（全进程单例）
 *
 * 遮罩是**只读**的灰度图，与画布尺寸 / 画质 / 当前照片都无关 —— 一张 256×256 的噪声图
 * 可以被 `NOISE_DISSOLVE`、以及 P2 里所有 M3 降级项**共用**。
 * 文档要求「全进程只生成一次」，做成单例是最直接的实现：**每个 `PhotoRenderer` 实例
 * 各自持有一份就会变成「每次进效果重新生成」**（生成要逐像素算，256×256 有 6.5 万像素）。
 *
 * ⚠️ 因此**没有 `release()`**：单例不能因为某个渲染器退出就把共享位图回收掉
 * （其他实例可能正在用）。常驻内存 = 256×256×4 ≈ 256 KB，可以接受。
 * 需要清空缓存的只有单测（[clearForTest]）。
 *
 * ## 生成时机
 *
 * **只在 `PhotoTransition.prepare()` 里被调用**（= 切转场 / 切画质那一刻），
 * 稳态每帧不碰 —— 见 [PhotoTransition] 的约束 2。
 *
 * ## 为什么用 `ARGB_8888` 而不是 `ALPHA_8`
 *
 * 遮罩要能直接喂给 `drawImage` 的 `BlendMode.DstIn` / `DstOut`，那需要**alpha 通道**。
 * `ALPHA_8` 更省（64 KB），但 `Bitmap.setPixels` 对它的取值约定在各版本实现里不一致，
 * 而 `ARGB_8888` 写 `alpha shl 24`（RGB 留 0）行为确定、内存也完全够用。
 */
object MaskCache {

    /** 遮罩边长（2 的幂，便于按需采样；不要随意改 —— 视觉密度是按 256 调的） */
    const val SIZE = 256

    /** ⚠️ 只被主线程访问（`prepare` 在主线程），不加锁 */
    private var noiseMask: ImageBitmap? = null

    /** 生成次数（单测用：验证「只生成一次」） */
    internal var noiseGenerationCount = 0
        private set

    /**
     * 噪声遮罩（`NOISE_DISSOLVE` 及 P2 各 M3 降级项共用）。
     *
     * 形态：多倍频**值噪声**（fBm，3 个八度）—— 比白噪声更像「云雾溶解」，
     * 比真柏林噪声实现简单得多，而在 256×256 上肉眼看不出差别。
     */
    fun noise(): ImageBitmap {
        noiseMask?.let { return it }
        val created = generateNoise()
        noiseMask = created
        noiseGenerationCount++
        return created
    }

    /** 仅供单测：清空缓存，使下一次 [noise] 重新生成 */
    internal fun clearForTest() {
        noiseMask = null
        noiseGenerationCount = 0
    }

    // ────────────────────────── 生成 ──────────────────────────

    private fun generateNoise(seed: UInt = NOISE_SEED): ImageBitmap {
        val rnd = VisualizerRandom(seed)
        // 三个八度：格点数越少越「大块」，越多越「细碎」
        val octaves = OCTAVE_CELLS
        val grids = Array(octaves.size) { i ->
            val n = octaves[i]
            FloatArray((n + 1) * (n + 1)) { rnd.next() }
        }
        val pixels = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            val row = y * SIZE
            for (x in 0 until SIZE) {
                var sum = 0f
                var norm = 0f
                var amp = 1f
                for (o in octaves.indices) {
                    sum += amp * sampleBilinear(grids[o], octaves[o], x, y)
                    norm += amp
                    amp *= 0.5f
                }
                val v = ((sum / norm) * 255f).toInt().coerceIn(0, 255)
                // 只用 alpha 通道表达遮罩强度（RGB 留 0）
                pixels[row + x] = v shl 24
            }
        }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        return bmp.asImageBitmap()
    }

    /** 在 `n×n` 格点上做双线性插值采样（值噪声的核心） */
    private fun sampleBilinear(grid: FloatArray, n: Int, x: Int, y: Int): Float {
        val fx = x.toFloat() / SIZE * n
        val fy = y.toFloat() / SIZE * n
        val x0 = fx.toInt().coerceIn(0, n - 1)
        val y0 = fy.toInt().coerceIn(0, n - 1)
        val tx = fx - x0
        val ty = fy - y0
        val stride = n + 1
        val v00 = grid[y0 * stride + x0]
        val v10 = grid[y0 * stride + x0 + 1]
        val v01 = grid[(y0 + 1) * stride + x0]
        val v11 = grid[(y0 + 1) * stride + x0 + 1]
        val top = v00 + (v10 - v00) * tx
        val bottom = v01 + (v11 - v01) * tx
        return top + (bottom - top) * ty
    }

    /** 固定种子 ⇒ 每次生成的噪声图一致（可复现，便于单测与「同一张遮罩」的预期） */
    private const val NOISE_SEED = 0x5EED_1234u

    private val OCTAVE_CELLS = intArrayOf(4, 8, 16)
}

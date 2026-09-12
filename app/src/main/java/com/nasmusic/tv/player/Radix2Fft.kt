package com.nasmusic.tv.player

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 迭代式 radix-2 复数 FFT（零分配）。
 *
 * P6 降级通道要自己算频谱，但开发方案明确 **不引入 JTransforms** ——
 * 只为 1024 点 FFT 拉一个几百 KB 的依赖不划算；自实现 < 1ms，
 * 旋转因子与位反转表在构造时预计算，运行期不分配任何对象。
 *
 * 用法（单实例复用，非线程安全）：
 * ```
 * val fft = Radix2Fft(1024)
 * fft.magnitude(windowedInput, outMagnitudes)   // out.size >= 512
 * ```
 */
class Radix2Fft(val size: Int) {

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT 长度必须为 2 的幂，当前 $size" }
    }

    /** 位反转置换表（自逆置换，可直接用于乱序写入） */
    private val reverse = IntArray(size)

    /** 旋转因子 exp(-2πi·k/N)，k ∈ [0, N/2) */
    private val cosT = FloatArray(size / 2)
    private val sinT = FloatArray(size / 2)

    // 工作缓冲（复用，零分配）
    private val re = FloatArray(size)
    private val im = FloatArray(size)

    init {
        val levels = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            var x = i
            var r = 0
            for (b in 0 until levels) {
                r = (r shl 1) or (x and 1)
                x = x ushr 1
            }
            reverse[i] = r
        }
        val half = size / 2
        for (i in 0 until half) {
            val ang = -2.0 * Math.PI * i / size
            cosT[i] = cos(ang).toFloat()
            sinT[i] = sin(ang).toFloat()
        }
    }

    /**
     * 计算幅值谱。
     *
     * @param input 时域样本（只取前 [size] 个；不足则视为 0）
     * @param out   输出幅值，长度须 ≥ [size]/2（单位与输入一致）
     */
    fun magnitude(input: FloatArray, out: FloatArray) {
        val n = size
        val inLen = input.size

        // 位反转装载（逆序写入，省一次显式乱序）
        for (i in 0 until n) {
            val j = reverse[i]
            re[j] = if (i < inLen) input[i] else 0f
            im[j] = 0f
        }

        // 蝴蝶运算
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                var idx = i
                while (k < half) {
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val j2 = idx + half
                    val tr = re[j2] * wr - im[j2] * wi
                    val ti = re[j2] * wi + im[j2] * wr
                    val ur = re[idx]
                    val ui = im[idx]
                    re[idx] = ur + tr
                    im[idx] = ui + ti
                    re[j2] = ur - tr
                    im[j2] = ui - ti
                    idx++
                    k++
                }
                i += len
            }
            len = len shl 1
        }

        val halfN = n / 2
        val outLen = minOf(halfN, out.size)
        for (i in 0 until outLen) {
            val r = re[i]
            val m = im[i]
            out[i] = sqrt(r * r + m * m)
        }
        for (i in outLen until out.size) out[i] = 0f
    }
}

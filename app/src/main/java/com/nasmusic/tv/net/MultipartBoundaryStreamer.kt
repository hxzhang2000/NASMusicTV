package com.nasmusic.tv.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * multipart/form-data 结束边界的流式扫描器。
 *
 * 用 KMP（前缀函数）匹配边界，正确处理**自重叠边界**：
 * 朴素匹配在失配时把 matched 重置为 0，会漏掉「已读字节的后缀 + 当前字节」构成的新起点。
 * 例：边界 `aab` 出现在内容 `aaab` 中时，朴素匹配读第 3 个字节失配后把前两个 `a` 直接写出，
 * 导致第 4 个字节 `b` 无法与之组成 `aab`，漏判边界 → 文件多写入 1 字节并把边界写进文件。
 *
 * 调用方负责已消费首个 boundary 与 part headers；本类只做
 * 「读到结束边界为止，把边界**之前**的字节写入 output」。
 *
 * 时间复杂度 O(n)，每字节的分摊比较次数为 O(1)；写入按 64KB 批量 flush。
 */
internal class MultipartBoundaryStreamer(private val boundary: ByteArray) {

    init {
        require(boundary.isNotEmpty()) { "boundary must not be empty" }
    }

    /** 是否命中完整边界（未命中表示 EOF，剩余数据已按内容处理） */
    var boundaryFound: Boolean = false
        private set

    /** KMP 前缀函数：fail[i] = boundary[0..i] 的最长真前缀且同时是后缀的长度 */
    private val fail: IntArray = IntArray(boundary.size).also { f ->
        var k = 0
        for (i in 1 until boundary.size) {
            while (k > 0 && boundary[i] != boundary[k]) k = f[k - 1]
            if (boundary[i] == boundary[k]) k++
            f[i] = k
        }
    }

    /**
     * 从 [input] 读取直到遇到 [boundary]（边界本身被消费且不写入），
     * 其前的所有字节写入 [output]。
     *
     * @return 写入 [output] 的字节数
     */
    fun stream(input: InputStream, output: OutputStream): Long {
        val bLen = boundary.size
        // hold[0..matched) 恒等于 boundary[0..matched)
        val hold = ByteArray(bLen)
        var matched = 0
        var totalWritten = 0L
        val writeBuf = ByteArrayOutputStream(128 * 1024)
        val buf = ByteArray(128 * 1024)

        fun flushBuf() {
            if (writeBuf.size() > 0) {
                writeBuf.writeTo(output)
                totalWritten += writeBuf.size()
                writeBuf.reset()
            }
        }

        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            for (i in 0 until n) {
                val b = buf[i]
                // KMP 回退：找到最大的 t，使 hold[0..t) == boundary[0..t) 且 b 可能接在其后
                var t = matched
                while (t > 0 && b != boundary[t]) t = fail[t - 1]

                if (b == boundary[t]) {
                    // 新匹配 = hold[0..t) + b，长度 t+1
                    if (matched > t) writeBuf.write(hold, t, matched - t)
                    hold[t] = b
                    matched = t + 1
                    if (matched == bLen) {
                        // 完整命中边界：hold 即边界，丢弃不写入
                        flushBuf()
                        boundaryFound = true
                        return totalWritten
                    }
                } else {
                    // t == 0 且 b != boundary[0]：b 不可能开启新匹配
                    if (matched > 0) writeBuf.write(hold, 0, matched)
                    writeBuf.write(b.toInt())
                    matched = 0
                }

                if (writeBuf.size() >= 64 * 1024) flushBuf()
            }
        }

        // EOF：未遇到边界，残留的 hold 属于内容，需要写出
        if (matched > 0) writeBuf.write(hold, 0, matched)
        flushBuf()
        return totalWritten
    }
}

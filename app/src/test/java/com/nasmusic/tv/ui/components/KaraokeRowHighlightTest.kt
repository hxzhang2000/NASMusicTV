package com.nasmusic.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐字高亮「折行推进」的**门禁测试**（v2.36.7）。
 *
 * ## 为什么需要它
 *
 * 一句歌词太长被排成 2~3 个可视行（折行）时，逐字高亮的边界由
 * `KaraokeLineText` 的 `drawWithContent` 逐可视行裁剪得到。真机症状是
 * **「第一折行没走完，出现一个返回的动画效果，然后走第二折行」**。
 *
 * 根因（已核对 AOSP `android.text.Layout`）：
 * 边界插值原本用 `getHorizontalPosition(boundaryOffset + 1)` 当终点。当
 * `boundaryOffset + 1 == lineEnd`（即边界落在**本行最后一个字**上）时，该 offset 是
 * **软换行边界**；`Layout.getPrimaryHorizontal` 内部先做 `getLineForOffset(offset)`
 * （二分条件 `getLineStart(guess) > offset`，边界 offset 归属**下一行**），
 * 于是返回**下一可视行行首的 x**。居中对齐时它远小于本行右缘 ⇒ 插值终点跑到本行左半边
 * ⇒ 边界随进度**向左倒带**。又因为逐字节奏是 `progress^0.6`（前快后慢），句尾字耗时最长，
 * 这个倒带窗口被显著拉长，肉眼非常明显。
 *
 * ## 三层自证
 *
 * 1. **契约用例**：覆盖比例必须「前一行走完才轮到下一行」；
 * 2. **单调用例**：边界 x 随覆盖字符数**单调不减**（含把「软换行边界返回下一行行首 x」
 *    这一 AOSP 行为如实模拟进 `xOfOffset`）；
 * 3. **负向自证**：同一套断言喂给**旧写法**（用 `xOfOffset(base + 1)` 当终点）必须判失败
 *    —— 证明护栏不是空转。
 */
class KaraokeRowHighlightTest {

    // ─────────────────────── ① 逐行推进契约 ───────────────────────

    @Test
    fun `第一折行没走完时第二折行必须为 0`() {
        val rows = listOf(10, 10)
        assertEquals(listOf(0.5f, 0f), karaokeRowCoverage(rows, 5f))
        assertEquals(listOf(0.9f, 0f), karaokeRowCoverage(rows, 9f))
    }

    @Test
    fun `第一折行走完才开始第二折行`() {
        val rows = listOf(10, 10, 10)
        assertEquals(listOf(1f, 0f, 0f), karaokeRowCoverage(rows, 10f))
        assertEquals(listOf(1f, 0.5f, 0f), karaokeRowCoverage(rows, 15f))
        assertEquals(listOf(1f, 1f, 1f), karaokeRowCoverage(rows, 30f))
    }

    @Test
    fun `超出总字数时全部按满覆盖钳制`() {
        assertEquals(listOf(1f, 1f), karaokeRowCoverage(listOf(10, 10), 999f))
    }

    @Test
    fun `零长度可视行不消耗覆盖字数`() {
        // 硬换行/空行可能产生 0 长度可视行：不得吞掉进度，否则后续行会提前亮起
        assertEquals(listOf(1f, 0f, 0.5f), karaokeRowCoverage(listOf(10, 0, 10), 15f))
    }

    @Test
    fun `覆盖比例随进度单调不减`() {
        val rows = listOf(7, 11, 5)
        val total = rows.sum()
        var prev = List(rows.size) { -1f }
        for (step in 0..(total * 4)) {
            val covered = step / 4f
            val now = karaokeRowCoverage(rows, covered)
            for (i in rows.indices) {
                assertTrue(
                    "第 $i 行覆盖比例倒退了：covered=$covered, ${prev[i]} -> ${now[i]}",
                    now[i] >= prev[i],
                )
            }
            prev = now
        }
    }

    // ─────────────────────── ② 边界像素单调不回退 ───────────────────────

    /**
     * 模拟 AOSP 行为：`xOfOffset(rowLength)`（软换行边界）返回**下一可视行行首的 x**，
     * 而不是本行右缘。这是真机上出现「返回动画」的直接原因。
     */
    private fun aospLikeXOfOffset(rowLength: Int, rowLeft: Float, charWidth: Float): (Int) -> Float =
        { i -> if (i >= rowLength) rowLeft else rowLeft + i * charWidth }

    @Test
    fun `行尾字符的边界不得倒带`() {
        val rowLength = 10
        val rowLeft = 50f
        val charWidth = 10f
        val rowRight = rowLeft + rowLength * charWidth // 150f
        val xOfOffset = aospLikeXOfOffset(rowLength, rowLeft, charWidth)

        var prev = -1f
        for (step in 0..(rowLength * 8)) {
            val coveredInRow = step / 8f
            val x = karaokeRowBoundaryX(coveredInRow, rowLength, xOfOffset, rowRight)
            assertTrue(
                "边界倒带：coveredInRow=$coveredInRow, $prev -> $x",
                x >= prev - 0.001f,
            )
            assertTrue("边界越界（超出本行右缘）：coveredInRow=$coveredInRow, x=$x", x <= rowRight + 0.001f)
            prev = x
        }
        // 本行走满 -> 恰好落在本行右缘（而不是下一行行首）
        assertEquals(rowRight, karaokeRowBoundaryX(rowLength.toFloat(), rowLength, xOfOffset, rowRight), 0.001f)
    }

    @Test
    fun `行尾插值终点用本行右缘而不是下一行行首`() {
        val xOfOffset = aospLikeXOfOffset(rowLength = 10, rowLeft = 50f, charWidth = 10f)
        // 边界落在最后一个字（下标 9）的正中间
        val x = karaokeRowBoundaryX(9.5f, 10, xOfOffset, rowRight = 150f)
        // x1 = 140（第 9 字左缘），x2 必须取 rowRight=150（而不是下一行行首 50）
        assertEquals(145f, x, 0.001f)
    }

    @Test
    fun `边界在行首或已满时直接给出确定值`() {
        val xOfOffset = aospLikeXOfOffset(rowLength = 4, rowLeft = 20f, charWidth = 5f)
        assertEquals(20f, karaokeRowBoundaryX(0f, 4, xOfOffset, rowRight = 40f), 0.001f)
        assertEquals(40f, karaokeRowBoundaryX(4f, 4, xOfOffset, rowRight = 40f), 0.001f)
        assertEquals(40f, karaokeRowBoundaryX(9f, 4, xOfOffset, rowRight = 40f), 0.001f)
    }

    // ─────────────────────── ③ 负向自证：旧写法必须被判失败 ───────────────────────

    /** 旧实现（v2.36.6 及以前）：无脑用 `xOfOffset(base + 1)` 当插值终点。 */
    private fun legacyBoundaryX(
        coveredInRow: Float,
        rowLength: Int,
        xOfOffset: (Int) -> Float,
        rowRight: Float,
    ): Float {
        if (coveredInRow >= rowLength) return rowRight
        val base = coveredInRow.toInt().coerceIn(0, rowLength - 1)
        val frac = (coveredInRow - base).coerceIn(0f, 1f)
        val x1 = xOfOffset(base)
        val x2 = xOfOffset(base + 1)
        return x1 + (x2 - x1) * frac
    }

    @Test
    fun `负向自证：旧写法在行尾必然倒带`() {
        val rowLength = 10
        val xOfOffset = aospLikeXOfOffset(rowLength, rowLeft = 50f, charWidth = 10f)
        var rewound = false
        var prev = -1f
        for (step in 0..(rowLength * 8)) {
            val x = legacyBoundaryX(step / 8f, rowLength, xOfOffset, rowRight = 150f)
            if (x < prev - 0.001f) rewound = true
            prev = x
        }
        assertTrue(
            "护栏空转：旧写法在「软换行边界返回下一行行首 x」的布局下都没被判出倒带 —— " +
                "说明单调断言没覆盖到真实缺陷",
            rewound,
        )
    }

    @Test
    fun `负向自证：旧写法在行尾停在下一行行首附近`() {
        val xOfOffset = aospLikeXOfOffset(rowLength = 10, rowLeft = 50f, charWidth = 10f)
        // 旧写法在最后一个字上把边界从 140 拉回到 95（下一行行首 50 参与插值）
        val legacy = legacyBoundaryX(9.5f, 10, xOfOffset, rowRight = 150f)
        val fixed = karaokeRowBoundaryX(9.5f, 10, xOfOffset, rowRight = 150f)
        assertEquals(95f, legacy, 0.001f)
        assertTrue("修复后必须比旧写法更靠右（不回退）", fixed > legacy)
    }
}

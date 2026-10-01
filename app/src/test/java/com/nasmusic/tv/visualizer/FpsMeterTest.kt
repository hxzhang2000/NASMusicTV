package com.nasmusic.tv.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FpsMeter] 契约测试（真机验收采集口径的纯逻辑部分）。
 *
 * 帧率读数只有两种死法：**窗口选错**（累计平均 ⇒ 切效果后半天不更新，读数永远是被历史
 * 拖平的假值）和**除数用错**（拿窗口标称长度当实际长度 ⇒ 掉帧时读数反而"正常"）。
 * 本类先各护一条正向，再用**同一份驱动序列**把两种错误实现喂一遍，要求它们与真实现
 * 产生**可观测分歧** —— 否则断言只是恒真。
 *
 * 全为纯 JVM、零 Android 依赖。
 */
class FpsMeterTest {

    private val window = FpsMeter.DEFAULT_WINDOW_NS

    /** 按「段」驱动：每段 (帧数, 帧率)，返回**每段最后一次闭合窗口时**的读数。 */
    private fun drive(onFrame: (Long) -> Unit, read: () -> Float, segments: List<Pair<Int, Float>>): List<Float> {
        var ns = 1_000_000_000L
        val out = mutableListOf<Float>()
        for ((frames, fps) in segments) {
            val step = (1_000_000_000L / fps).toLong()
            var last = 0f
            repeat(frames) {
                ns += step
                onFrame(ns)
                last = read()
            }
            out += last
        }
        return out
    }

    @Test
    fun `① 首帧不闭合窗口`() {
        val m = FpsMeter()
        assertFalse(m.onFrame(1_000_000_000L))
        assertEquals(0f, m.fps, 0f)
    }

    @Test
    fun `② 恒定 60fps 时间戳 → 读数等于 60`() {
        val m = FpsMeter()
        val got = drive({ m.onFrame(it) }, { m.fps }, listOf(60 to 60f))
        assertEquals("60 帧里至少闭合过一次窗口", true, got[0] > 0f)
        assertEquals(60f, got[0], 60f * 0.01f)
    }

    @Test
    fun `③ 窗口是滚动的 → 掉帧后一个窗口内就跟上新帧率`() {
        val m = FpsMeter()
        val got = drive({ m.onFrame(it) }, { m.fps }, listOf(60 to 60f, 60 to 15f))
        assertEquals(60f, got[0], 60f * 0.01f)
        assertEquals("切换后仍读到旧帧率 ⇒ 窗口没有滚动", 15f, got[1], 15f * 0.05f)
    }

    @Test
    fun `④ 不足一个窗口不闭合，跨过标称窗口即闭合`() {
        val m = FpsMeter()
        val step = window / 5
        assertFalse("首帧只设锚点", m.onFrame(0L))
        for (i in 1..4) {
            assertFalse("$i 个间隔 = ${i * 100} ms < 窗口，不该闭合", m.onFrame(i * step))
        }
        assertTrue("第 5 个间隔正好铺满窗口 ⇒ 闭合", m.onFrame(5 * step))
        assertEquals("100 ms 间隔 = 10 fps", 10f, m.fps, 0.01f)
    }

    @Test
    fun `⑤ 读数只依赖实际时间跨度，不依赖帧数标称值`() {
        // 同样 60 帧：一份 60fps 的间隔、一份 30fps 的间隔 ⇒ 读数必须差一倍
        val fast = FpsMeter().let { drive({ n -> it.onFrame(n) }, { it.fps }, listOf(60 to 60f)) }[0]
        val slow = FpsMeter().let { drive({ n -> it.onFrame(n) }, { it.fps }, listOf(60 to 30f)) }[0]
        assertEquals(fast / 2f, slow, slow * 0.05f)
    }

    @Test
    fun `负向① 累计平均实现必须跟不上帧率突变（与 ③ 同一份序列）`() {
        var n = 0
        var t0 = -1L
        var cum = 0f
        val got = drive(
            { ns -> if (t0 < 0L) { t0 = ns; n = 1 } else { n++; cum = (n - 1) * 1_000_000_000f / (ns - t0) } },
            { cum },
            listOf(60 to 60f, 60 to 15f)
        )
        assertEquals("前提：累计平均在稳态下也是对的", 60f, got[0], 60f * 0.02f)
        assertTrue(
            "累计平均被历史拖住 ⇒ 与滚动窗口产生可观测分歧（否则 ③ 是空转门禁）：got=${got[1]}",
            kotlin.math.abs(got[1] - 15f) > 3f
        )
    }

    @Test
    fun `负向② 按帧数除标称窗口的实现必须对真实帧率视而不见（与 ⑤ 同一份驱动）`() {
        // 变体：每 30 帧报一次 `30 / 0.5s = 60fps`，**完全不看时间戳**
        fun naive(frames: Int): Float {
            var count = 0
            var last = 0f
            repeat(frames) {
                count++
                if (count == 30) { last = 30 * 1_000_000_000f / window; count = 0 }
            }
            return last
        }
        assertEquals("真实现区分得开 30fps 与 60fps", 30f,
            FpsMeter().let { drive({ n -> it.onFrame(n) }, { it.fps }, listOf(60 to 30f)) }[0], 1.5f)
        assertEquals(60f,
            FpsMeter().let { drive({ n -> it.onFrame(n) }, { it.fps }, listOf(60 to 60f)) }[0], 0.6f)
        // ⇒ 而 naive 只看帧数：同样喂 60 帧，真速 30 与真速 60 它都报 60
        assertEquals("naive 对 30fps 也报 60 ⇒ 若门禁只喂单一速率就抓不到它", 60f, naive(60), 0.01f)
    }
}

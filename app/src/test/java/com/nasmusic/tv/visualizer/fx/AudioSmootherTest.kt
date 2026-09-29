package com.nasmusic.tv.visualizer.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §八 G6：attack/release 分离包络（含负向自证：对调 attack/release 必须判失败） */
class AudioSmootherTest {

    /** 从 0 升到 target ≥ threshold 需要几帧 */
    private fun framesToRise(s: AudioSmoother, target: Float, threshold: Float, max: Int = 500): Int {
        var n = 0
        while (n < max) {
            n++
            if (s.update(target) >= threshold) return n
        }
        return max
    }

    /** 从当前值降到 ≤ threshold 需要几帧 */
    private fun framesToFall(s: AudioSmoother, target: Float, threshold: Float, max: Int = 500): Int {
        var n = 0
        while (n < max) {
            n++
            if (s.update(target) <= threshold) return n
        }
        return max
    }

    @Test
    fun `上升快于下降 - attack 帧数 少于 release 帧数`() {
        val s = AudioSmoother()
        s.update(1f)
        val rise = framesToRise(s, 1f, 0.9f)
        s.reset()
        s.update(1f)
        val fall = framesToFall(s, 0f, 0.1f)
        assertTrue("rise=$rise fall=$fall —— 上升应明显快于下降", rise < fall)
    }

    @Test
    fun `reset 后 value 归零`() {
        val s = AudioSmoother()
        repeat(10) { s.update(1f) }
        assertTrue(s.value > 0.5f)
        s.reset()
        assertEquals(0f, s.value, 1e-6f)
    }

    @Test
    fun `单调逼近目标 - 上升单调不减、下降单调不增`() {
        val s = AudioSmoother()
        var last = 0f
        repeat(20) {
            val v = s.update(1f)
            assertTrue(v >= last - 1e-6f)
            last = v
        }
        repeat(40) {
            val v = s.update(0f)
            assertTrue(v <= last + 1e-6f)
            last = v
        }
        assertTrue(last < 0.1f)
    }

    @Test
    fun `负向 - attack 与 release 对调后 快升慢降判据必须判失败`() {
        // 构造"对调后"的平滑器，证明 framesToRise < framesToFall 这条判据真的能区分二者
        val swapped = AudioSmoother(attack = 0.06f, release = 0.35f)
        swapped.update(1f)
        val rise = framesToRise(swapped, 1f, 0.9f)
        swapped.reset()
        swapped.update(1f)
        val fall = framesToFall(swapped, 0f, 0.1f)
        assertTrue(
            "对调参数后 rise($rise) 应 ≥ fall($fall) —— 否则判据『rise < fall』是空转",
            rise >= fall
        )
    }
}

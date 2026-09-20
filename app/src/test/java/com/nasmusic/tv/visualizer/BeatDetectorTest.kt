package com.nasmusic.tv.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BeatDetector 单元测试 —— 呼吸感引擎的核心。
 *
 * 覆盖矩阵（对照 docs/archive/music-visualizer-dev-plan.md §10.1）：
 *   - 稳定 120 BPM：20 拍检出 >= 18，BPM 估值 115~125
 *   - 静音：0 拍
 *   - 恒定能量（无节奏）：总量 <= 1 拍，且 1s 之后不再触发
 *   - 快歌 180 BPM：20 拍检出 >= 16
 *   - 低于 MIN_BASS 的"相对跃升"被门限拦下
 *   - pulse 快起慢落
 *   - reset 后需重新预热历史窗口
 *
 * 帧步进取 20ms —— 与 SpectrumContract.CAPTURE_INTERVAL_US(20000µs / 50Hz) 一致；
 * HISTORY = 43 帧 ≈ 860ms。
 */

/** 帧间隔（ms）：50Hz */
private const val FRAME_MS = 20L

/** 环境底噪级（远低于 MIN_BASS=0.12，不构成节拍） */
private const val QUIET = 0.02f

/** 鼓点峰值 */
private const val LOUD = 0.6f

class BeatDetectorTest {

    /** 按固定 20ms 步进喂数据的小夹具 */
    private class Feed {
        val detector = BeatDetector()
        private var t = 0L
        var beats = 0
            private set
        var lastPulse = 0f
            private set
        var lastBpm = 0f
            private set

        fun step(bass: Float): BeatDetector.BeatInfo {
            val info = detector.onFrame(bass, t)
            if (info.isBeat) beats++
            lastPulse = info.pulse
            lastBpm = info.bpm
            t += FRAME_MS
            return info
        }

        fun quiet(n: Int) {
            repeat(n) { step(QUIET) }
        }
    }

    @Test
    fun `detects 120 BPM`() {
        val f = Feed()
        f.quiet(30)                       // 预热：HISTORY/2 = 21 帧后才可能命中
        repeat(20) {                      // 20 拍，每拍 500ms（1 帧 LOUD + 24 帧静默）
            f.step(LOUD)
            f.quiet(24)
        }
        assertTrue("expected >= 18 beats, got ${f.beats}", f.beats >= 18)
        assertTrue("BPM should be ~120, got ${f.lastBpm}", f.lastBpm in 115f..125f)
    }

    @Test
    fun `silence never produces a beat`() {
        val f = Feed()
        repeat(300) { f.step(0f) }        // 6s 全静音
        assertEquals("silence must not trigger beats", 0, f.beats)
        assertEquals("pulse must decay to zero", 0f, f.lastPulse, 0.0001f)
    }

    @Test
    fun `constant energy does not keep triggering`() {
        val f = Feed()
        var beatsAfterFirstSecond = 0
        repeat(400) { i ->                // 8s @50Hz
            val info = f.step(0.5f)
            if (info.isBeat && i > 50) beatsAfterFirstSecond++
        }
        assertTrue("ramp-up may fire at most once, got ${f.beats}", f.beats <= 1)
        assertEquals(
            "constant energy must not keep triggering after warm-up",
            0,
            beatsAfterFirstSecond
        )
    }

    @Test
    fun `detects 180 BPM`() {
        val f = Feed()
        f.quiet(30)
        repeat(20) {                      // 每拍 340ms ≈ 176 BPM（> MIN_BEAT_MS 240ms）
            f.step(LOUD)
            f.quiet(16)
        }
        assertTrue("expected >= 16 beats, got ${f.beats}", f.beats >= 16)
        assertTrue("BPM should be ~176, got ${f.lastBpm}", f.lastBpm in 160f..195f)
    }

    @Test
    fun `spike below MIN_BASS is gated out`() {
        val f = Feed()
        repeat(300) { f.step(0f) }
        repeat(300) { f.step(0.004f) }    // 0.004 < MIN_BASS(0.005)：数字底噪级的相对变化，不构成鼓点
        assertEquals("sub-threshold energy must not trigger", 0, f.beats)
    }

    @Test
    fun `pulse rises on beat and decays afterwards`() {
        val f = Feed()
        f.quiet(30)
        val onBeat = f.step(LOUD)
        assertTrue("loud spike should be a beat", onBeat.isBeat)
        val p0 = onBeat.pulse               // 快起后立即衰减一帧，故 ≈0.9
        assertTrue("pulse should be high on beat, got $p0", p0 > 0.8f)

        repeat(5) { f.step(QUIET) }
        assertTrue(
            "pulse must decay after the beat: $p0 -> ${f.lastPulse}",
            f.lastPulse < p0 * 0.7f
        )
    }

    @Test
    fun `reset clears history so detector must re-warm`() {
        val f = Feed()
        f.quiet(30)
        f.detector.reset()

        var beatsWhileCold = 0
        repeat(20) { if (f.step(LOUD).isBeat) beatsWhileCold++ }
        assertEquals(
            "after reset the history window is empty -> no beat within 20 frames",
            0,
            beatsWhileCold
        )
    }
}

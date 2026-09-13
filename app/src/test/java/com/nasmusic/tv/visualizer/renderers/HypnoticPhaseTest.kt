package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * E25 催眠 · 状态机时间推进测试（§13.1 HypnoticPhaseTest）。
 *
 * 通过 [HypnoticFunctionRenderer.nextPhase] 纯函数断言四态时长：
 * DRAW 8000ms → HOLD 3000ms → DISSOLVE 2400ms → GAP 500ms → DRAW，周期 13900ms。
 */
class HypnoticPhaseTest {

    private val DRAW = HypnoticFunctionRenderer.Phase.DRAW
    private val HOLD = HypnoticFunctionRenderer.Phase.HOLD
    private val DISSOLVE = HypnoticFunctionRenderer.Phase.DISSOLVE
    private val GAP = HypnoticFunctionRenderer.Phase.GAP

    @Test
    fun `draw takes 8000ms`() {
        assertEquals(null, HypnoticFunctionRenderer.nextPhase(DRAW, 7999, drawDone = false))
        assertEquals(HOLD, HypnoticFunctionRenderer.nextPhase(DRAW, 7999, drawDone = true))
        // 未描完但未超时：不迁移
        assertEquals(null, HypnoticFunctionRenderer.nextPhase(DRAW, 9000, drawDone = false))
    }

    @Test
    fun `hold lasts exactly 3000ms`() {
        assertNull(HypnoticFunctionRenderer.nextPhase(HOLD, 2999, drawDone = true))
        assertEquals(DISSOLVE, HypnoticFunctionRenderer.nextPhase(HOLD, 3000, drawDone = true))
    }

    @Test
    fun `dissolve lasts 2400ms and gap 500ms`() {
        assertNull(HypnoticFunctionRenderer.nextPhase(DISSOLVE, 2399, drawDone = true))
        assertEquals(GAP, HypnoticFunctionRenderer.nextPhase(DISSOLVE, 2400, drawDone = true))
        assertNull(HypnoticFunctionRenderer.nextPhase(GAP, 499, drawDone = true))
        assertEquals(DRAW, HypnoticFunctionRenderer.nextPhase(GAP, 500, drawDone = true))
    }

    @Test
    fun `full cycle is 13900ms and wraps to draw`() {
        var phase = DRAW
        var elapsed = 0L
        // 按"阶段完成"逐段推进：8000 + 3000 + 2400 + 500 = 13900
        val durations = mapOf(
            DRAW to 8000L, HOLD to 3000L, DISSOLVE to 2400L, GAP to 500L
        )
        var transitions = 0
        while (transitions < 4) {
            elapsed += durations[phase]!!
            val next = HypnoticFunctionRenderer.nextPhase(phase, elapsed, drawDone = true)
            if (next != null) {
                phase = next
                elapsed = 0L
                transitions++
            }
        }
        assertEquals(DRAW, phase)
    }

    @Test
    fun `timeout guard advances stuck draw phase`() {
        // 描线未完成但阶段超时（24s）：强制推进到 HOLD
        assertEquals(HOLD, HypnoticFunctionRenderer.nextPhase(DRAW, (8000 * 3 + 1).toLong(), drawDone = false))
    }

    @Test
    fun `hold and later phases have no accumulator dependency`() {
        // HOLD/DISSOLVE/GAP 只由 elapsed 推进，drawDone 无关紧要
        assertEquals(DISSOLVE, HypnoticFunctionRenderer.nextPhase(HOLD, 3000, drawDone = false))
        assertEquals(GAP, HypnoticFunctionRenderer.nextPhase(DISSOLVE, 2400, drawDone = false))
        assertEquals(DRAW, HypnoticFunctionRenderer.nextPhase(GAP, 500, drawDone = false))
    }

    @Test
    fun `dissolve thresholds stay in the effective band`() {
        val rng = Random(5)
        repeat(1000) {
            val curve = HypnoticFunctionRenderer.curveVanishThreshold(rng)
            assertTrue("curve vanish $curve", curve in 0.35f..1.0f)
            val run = HypnoticFunctionRenderer.runVanishThreshold(rng)
            assertTrue("run vanish $run", run in 0.30f..0.85f)
        }
    }

    @Test
    fun `run alpha decays 15 percent faster than curve`() {
        // 曲线 alpha = (1-p)*0.95；公式 alpha = (1-p*1.15)*224/224
        val p = 0.5f
        val curve = (1f - p) * 0.95f
        val run = HypnoticFunctionRenderer.runAlpha(p) / 224f
        assertTrue("run=$run should be ~15% faster than curve=$curve", run < curve * 0.95f)
        // p >= 0.87 时公式已完全消失
        assertEquals(0f, HypnoticFunctionRenderer.runAlpha(0.9f), 0.0001f)
        assertEquals(0f, HypnoticFunctionRenderer.runAlpha(1f), 0.0001f)
    }
}

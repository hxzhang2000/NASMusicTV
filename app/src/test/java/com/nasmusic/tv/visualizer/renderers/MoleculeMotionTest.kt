package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E37「分子」运动相位测试（v2.36.4 真机回归）。
 *
 * 症状：刻画阶段分子整体**剧烈抖动**，原子闪现/消失。
 * 根因：旋转与描线进度都用「绝对 now × 含 pulse 的速率」算相位 ——
 * `now` 是开机毫秒（1e7 量级），pulse 每帧抖动被放大千万倍
 * （now=5e6 时 pulse 差 0.3 就能让旋转角一帧跳 90 弧度）。
 * 修法：全部改成 dt 累加，且 DRAW 期旋转恒速不吃 pulse。
 *
 * 本文件锁死这两条性质；每条都带负向自证。
 */
class MoleculeMotionTest {

    private val draw = MoleculeRenderer.Phase.DRAW
    private val hold = MoleculeRenderer.Phase.HOLD

    // ── ① DRAW 期旋转恒速（刻画动画本身已足够，不叠律动）─────────

    @Test
    fun `DRAW spin does not react to pulse`() {
        val a = MoleculeRenderer.spinDelta(16L, draw, 0f)
        val b = MoleculeRenderer.spinDelta(16L, draw, 1f)
        assertEquals("DRAW 期旋转必须与 pulse 无关", a, b, 0f)
        assertTrue("DRAW 期也必须有自转，否则本断言空转", a > 0f)
    }

    /** 负向自证：HOLD 期**确实**吃 pulse，证明上一条不是因为 pulse 参数没接上 */
    @Test
    fun `negative proof - HOLD spin does react to pulse`() {
        val a = MoleculeRenderer.spinDelta(16L, hold, 0f)
        val b = MoleculeRenderer.spinDelta(16L, hold, 1f)
        assertTrue("HOLD 期必须随 pulse 加速（$a vs $b）", b > a * 1.2f)
    }

    // ── ② 单帧增量必须有界（不能"一帧跳几百度"）─────────────────

    @Test
    fun `single frame spin delta is tiny`() {
        for (pulse in floatArrayOf(0f, 0.5f, 1f)) {
            val d = MoleculeRenderer.spinDelta(16L, hold, pulse)
            assertTrue("一帧（16ms）自转增量 $d 必须 < 0.01 rad", d < 0.01f)
        }
    }

    /** 负向自证：旧的「绝对 now × 速率」写法在同样条件下会跳几十弧度 */
    @Test
    fun `negative proof - absolute now times pulse rate explodes`() {
        val now = 5_000_000L                       // 开机约 83 分钟，很常见
        fun oldRot(pulse: Float) = now * 0.00012f * (1f + pulse * 0.5f)
        val jump = kotlin.math.abs(oldRot(0.6f) - oldRot(0.3f))
        assertTrue("旧写法单帧跳变 $jump rad 必须 >> 0.01", jump > 1f)
        // 新写法在同样的 pulse 变化下只走 dt 的量级
        val newJump = MoleculeRenderer.spinDelta(16L, hold, 0.6f)
        assertTrue("新写法必须远小于旧写法（$newJump vs $jump）", newJump < jump / 1000f)
    }

    // ── ③ dt 必须钳制（切后台回来不暴走）───────────────────────

    @Test
    fun `huge dt is clamped`() {
        val huge = MoleculeRenderer.spinDelta(10_000_000L, draw, 1f)
        val max = MoleculeRenderer.spinDelta(MoleculeRenderer.MAX_DT_MS, draw, 1f)
        assertEquals("超长 dt 必须被钳到 MAX_DT_MS", max, huge, 0f)
    }

    /** 负向自证：钳制区间内的 dt 仍然是线性可分辨的（不是一律返回同一个值） */
    @Test
    fun `negative proof - dt within the clamp still matters`() {
        val a = MoleculeRenderer.spinDelta(8L, draw, 0f)
        val b = MoleculeRenderer.spinDelta(32L, draw, 0f)
        assertTrue("8ms 与 32ms 必须不同（$a vs $b）", b > a * 2f)
    }

    @Test
    fun `negative dt never rewinds`() {
        assertEquals(0f, MoleculeRenderer.spinDelta(-100L, draw, 1f), 0f)
    }

    // ── ④ 累加 500 帧：DRAW 期总角度只由累计时间决定 ────────────

    @Test
    fun `accumulated DRAW rotation equals rate times elapsed`() {
        val rng = kotlin.random.Random(1234)
        var acc = 0f
        var totalMs = 0L
        repeat(500) {
            val dt = 8L + rng.nextInt(24)          // 8..31ms，模拟不规则帧间隔
            val pulse = rng.nextFloat()            // pulse 每帧乱跳
            val before = acc
            acc += MoleculeRenderer.spinDelta(dt, draw, pulse)
            assertTrue("每帧增量必须为正", acc > before)
            totalMs += dt
        }
        val expected = totalMs / 1000f * MoleculeRenderer.ROT_SPEED
        assertEquals("DRAW 期累计角度应等于 速率 × 累计时间", expected, acc, 0.01f)
    }
}

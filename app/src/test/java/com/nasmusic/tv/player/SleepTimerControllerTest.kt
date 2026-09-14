package com.nasmusic.tv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SleepTimerController 状态机单测（F2-2）
 *
 * 时间源注入 fake clock。到期调度自 P1#4（2026-09-14）起为协程 `delay`：
 * - 多数用例用**不推进虚拟时间**的 StandardTestDispatcher，使 delay 永不到期
 *   （等价于改造前的 `noopHandler` 桩），到期路径经 [SleepTimerController.tickExpired] 验证
 * - `start schedules expiry via coroutine delay` / `cancel prevents pending expiry`
 *   两条用 `runTest` 的虚拟时间**真正驱动调度**——这是协程化带来的新增覆盖，
 *   原 `Handler` 版本在 Robolectric 下不驱动 Looper，测不到调度本身
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class) // TestScope.advanceTimeBy 在 1.7.3 标记为实验 API
class SleepTimerControllerTest {

    private class FakeClock(var now: Long = 1_000_000L)

    // P1#4：调度器由 Handler 改为注入 CoroutineScope。此处 StandardTestDispatcher 的
    // 虚拟时间从不推进，故 delay 永不到期 —— 与改造前 noopHandler 的语义一致。
    private val noopScope by lazy { CoroutineScope(StandardTestDispatcher()) }

    private fun newTimer(clock: FakeClock, onExpired: () -> Unit = {}) =
        SleepTimerController(
            onExpired = onExpired,
            scope = noopScope,
            nowMsProvider = { clock.now }
        )

    @Test
    fun `start sets running state and remaining`() {
        val clock = FakeClock()
        val timer = newTimer(clock)
        timer.start(30)
        assertTrue(timer.isRunning())
        assertEquals(30 * 60_000L, timer.remaining())
        assertEquals(30, timer.remainingMinutes())
    }

    @Test
    fun `cancel resets to off`() {
        val clock = FakeClock()
        val timer = newTimer(clock)
        timer.start(60)
        timer.cancel()
        assertFalse(timer.isRunning())
        assertEquals(0, timer.remaining())
        assertTrue(timer.state.value is SleepTimerController.State.Off)
    }

    @Test
    fun `zero or negative minutes treated as cancel`() {
        val timer = newTimer(FakeClock())
        timer.start(0)
        assertFalse(timer.isRunning())
        timer.start(-5)
        assertFalse(timer.isRunning())
    }

    @Test
    fun `remaining floors at zero after clock passes end`() {
        val clock = FakeClock()
        val timer = newTimer(clock)
        timer.start(15)
        clock.now += 16 * 60_000L // 超时
        assertEquals(0, timer.remaining())
        assertEquals(0, timer.remainingMinutes())
    }

    @Test
    fun `remainingMinutes rounds up`() {
        val clock = FakeClock()
        val timer = newTimer(clock)
        timer.start(10)
        clock.now += 30_000 // 半分钟
        assertEquals(10, timer.remainingMinutes()) // 9.5 分钟向上取整
        clock.now += 30_000 // 整分钟
        assertEquals(9, timer.remainingMinutes())
    }

    @Test
    fun `tickExpired fires callback once and moves to finished`() {
        val clock = FakeClock()
        var expiredCount = 0
        val timer = newTimer(clock) { expiredCount++ }
        timer.start(20)
        timer.tickExpired()
        assertEquals(1, expiredCount)
        assertTrue(timer.state.value is SleepTimerController.State.Finished)
        // 二次 tick 不重复触发
        timer.tickExpired()
        assertEquals(1, expiredCount)
    }

    @Test
    fun `tickExpired ignored when off`() {
        val timer = newTimer(FakeClock())
        var expiredCount = 0
        timer.tickExpired()
        assertEquals(0, expiredCount)
        assertTrue(timer.state.value is SleepTimerController.State.Off)
    }

    // ── P1#4 新增：真实调度路径（虚拟时间驱动，无需真实等待）──

    @Test
    fun `start schedules expiry via coroutine delay`() = runTest {
        var expiredCount = 0
        val timer = SleepTimerController(
            onExpired = { expiredCount++ },
            scope = this,
            nowMsProvider = { 0L }
        )
        timer.start(15)
        assertTrue(timer.isRunning())

        // 差 1 毫秒不到期
        advanceTimeBy(15 * 60_000L - 1)
        assertEquals(0, expiredCount)
        assertTrue(timer.isRunning())

        // 越过到期点 → 恰好触发一次
        advanceTimeBy(2)
        assertEquals(1, expiredCount)
        assertTrue(timer.state.value is SleepTimerController.State.Finished)
    }

    @Test
    fun `cancel prevents pending expiry`() = runTest {
        var expiredCount = 0
        val timer = SleepTimerController(
            onExpired = { expiredCount++ },
            scope = this,
            nowMsProvider = { 0L }
        )
        timer.start(15)
        timer.cancel()
        advanceTimeBy(20 * 60_000L)
        assertEquals(0, expiredCount)
        assertTrue(timer.state.value is SleepTimerController.State.Off)
    }

    @Test
    fun `restart supersedes previous schedule`() = runTest {
        var expiredCount = 0
        val timer = SleepTimerController(
            onExpired = { expiredCount++ },
            scope = this,
            nowMsProvider = { 0L }
        )
        timer.start(10)
        timer.start(30) // 重启：10 分钟的调度应被取消
        advanceTimeBy(10 * 60_000L + 1)
        assertEquals(0, expiredCount) // 旧调度不再触发
        assertTrue(timer.isRunning())
        advanceTimeBy(20 * 60_000L)
        assertEquals(1, expiredCount) // 新调度生效
    }
}

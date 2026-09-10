package com.nasmusic.tv.player

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
 * 时间源注入 fake clock；Handler 用不执行桩（到期路径经 [tickExpired] 公开方法验证）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepTimerControllerTest {

    private class FakeClock(var now: Long = 1_000_000L)

    // Robolectric 下主线程 Handler.postDelayed 不自动执行（无 Looper idle 驱动），
    // 到期路径由 tickExpired 显式驱动
    private val noopHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private fun newTimer(clock: FakeClock, onExpired: () -> Unit = {}) =
        SleepTimerController(
            onExpired = onExpired,
            handler = noopHandler,
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
}

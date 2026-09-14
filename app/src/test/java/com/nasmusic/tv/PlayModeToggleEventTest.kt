package com.nasmusic.tv

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * L3 回归测试：播放模式切换事件（`NasMusicApp.playModeToggleEvents`）。
 *
 * 断言的是**订阅生命周期语义**而非"能不能收到"——这正是把可变闭包字段换成 SharedFlow 的
 * 全部价值所在：
 *
 * 1. 无订阅者时事件被丢弃，且**不滞留给迟到的订阅者**（否则会出现"一进应用播放模式自己
 *    跳了一档"）；
 * 2. 订阅者所在作用域取消后**自动退订**，无需像旧实现那样在 `onDestroy` 手动置 null——
 *    旧实现的那次清理被 `if (!isFinishing) return` 前置拦截，配置重建时根本走不到。
 *
 * 用例用 Robolectric 取得真实的 `NasMusicApp`（manifest 声明的 application 类），
 * 与 `ProviderMirrorTest` / `CloudDriveConfigTest` 等既有 Robolectric 用例同路径。
 *
 * ⚠️ 本机 `testDebugUnitTest` 因 Gradle 测试 worker 环境问题无法运行（exit 268435466），
 * 这些用例**只验证了源码可编译**，实际通过与否须由 CI 判定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayModeToggleEventTest {

    private val app: NasMusicApp get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `emit without subscriber is dropped and never replayed to a late subscriber`() = runTest {
        assertFalse("无 UI 订阅时应返回 false（事件按设计丢弃）", app.requestPlayModeToggle())

        val received = AtomicInteger(0)
        val job = launch { app.playModeToggleEvents.collect { received.incrementAndGet() } }
        runCurrent()

        assertEquals("迟到的订阅者不应收到滞留事件", 0, received.get())
        job.cancelAndJoin()
    }

    @Test
    fun `emit with active subscriber delivers exactly once`() = runTest {
        val received = AtomicInteger(0)
        val job = launch { app.playModeToggleEvents.collect { received.incrementAndGet() } }
        runCurrent()

        assertTrue("有订阅者时应返回 true", app.requestPlayModeToggle())
        assertEquals(1, received.get())

        job.cancelAndJoin()
    }

    @Test
    fun `cancelled subscriber unsubscribes without manual cleanup`() = runTest {
        val received = AtomicInteger(0)
        val job = launch { app.playModeToggleEvents.collect { received.incrementAndGet() } }
        runCurrent()

        assertTrue(app.requestPlayModeToggle())
        assertEquals(1, received.get())

        // L3 核心：订阅方作用域取消（等价于 Activity 销毁 → lifecycleScope 取消）后自动退订，
        // Application 侧不再残留任何指向已销毁 Activity 的 ViewModel 的引用。
        job.cancelAndJoin()
        runCurrent()

        assertFalse("退订后投递应返回 false", app.requestPlayModeToggle())
        assertEquals("退订后不应再收到事件", 1, received.get())
    }
}

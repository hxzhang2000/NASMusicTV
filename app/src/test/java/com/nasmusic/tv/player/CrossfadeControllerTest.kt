package com.nasmusic.tv.player

import android.content.Context
import androidx.media3.common.MediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CrossfadeController 边界条件矩阵单测（F2-5）
 *
 * ExoPlayer 实例创建依赖完整媒体栈，边界条件（开关/模式/队列/URL 校验）
 * 在 maybeStartCrossfade 前置判断层——用不满足条件返回 false 的路径验证
 * （不实际启动淡入淡出，无 player 泄漏风险）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossfadeControllerTest {

    private val controller = CrossfadeController(
        context = org.mockito.Mockito.mock(Context::class.java),
        mainPlayerProvider = { null },
        onCrossfadeComplete = { }
    )

    private val itemFactory: (Int) -> MediaItem? = { MediaItem.fromUri("http://example.com/song.mp3") }

    @Test
    fun `disabled returns false`() {
        assertFalse(controller.maybeStartCrossfade(
            enabled = false, durationSec = 4, suppressPlayback = false, repeatOne = false,
            queueSize = 10, currentIndex = 0, nextMediaItemFactory = itemFactory))
    }

    @Test
    fun `zero duration returns false`() {
        assertFalse(controller.maybeStartCrossfade(
            enabled = true, durationSec = 0, suppressPlayback = false, repeatOne = false,
            queueSize = 10, currentIndex = 0, nextMediaItemFactory = itemFactory))
    }

    @Test
    fun `suppressPlayback karaoke or mtv returns false`() {
        assertFalse(controller.maybeStartCrossfade(
            enabled = true, durationSec = 4, suppressPlayback = true, repeatOne = false,
            queueSize = 10, currentIndex = 0, nextMediaItemFactory = itemFactory))
    }

    @Test
    fun `repeatOne returns false`() {
        assertFalse(controller.maybeStartCrossfade(
            enabled = true, durationSec = 4, suppressPlayback = false, repeatOne = true,
            queueSize = 10, currentIndex = 0, nextMediaItemFactory = itemFactory))
    }

    @Test
    fun `single-song queue returns false`() {
        assertFalse(controller.maybeStartCrossfade(
            enabled = true, durationSec = 4, suppressPlayback = false, repeatOne = false,
            queueSize = 1, currentIndex = 0, nextMediaItemFactory = itemFactory))
    }

    @Test
    fun `queue tail sequential returns false`() {
        assertFalse(controller.maybeStartCrossfade(
            enabled = true, durationSec = 4, suppressPlayback = false, repeatOne = false,
            queueSize = 5, currentIndex = 4, nextMediaItemFactory = itemFactory))
    }

    @Test
    fun `null next media item (empty streamUrl) returns false`() {
        assertFalse(controller.maybeStartCrossfade(
            enabled = true, durationSec = 4, suppressPlayback = false, repeatOne = false,
            queueSize = 10, currentIndex = 0, nextMediaItemFactory = { null }))
    }

    @Test
    fun `main player missing returns false`() {
        // mainPlayerProvider 返回 null（未初始化）→ 不启动
        assertFalse(controller.maybeStartCrossfade(
            enabled = true, durationSec = 4, suppressPlayback = false, repeatOne = false,
            queueSize = 10, currentIndex = 0, nextMediaItemFactory = itemFactory))
    }

    @Test
    fun `initial state off and not fading`() {
        assertFalse(controller.isFading())
        assertTrue(controller.state.value is CrossfadeController.State.Off)
    }

    @Test
    fun `abort is safe when never started`() {
        // 未启动时 abort 不崩溃（幂等清理）
        controller.abort()
        assertFalse(controller.isFading())
    }
}

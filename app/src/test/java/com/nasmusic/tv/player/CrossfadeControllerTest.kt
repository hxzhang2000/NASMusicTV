package com.nasmusic.tv.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * CrossfadeController 边界条件矩阵单测（F2-5）
 *
 * 边界条件（开关/模式/队列/URL 校验）在 maybeStartCrossfade 前置判断层——
 * 用不满足条件返回 false 的路径验证（不实际启动淡入淡出，无 player 泄漏风险）。
 *
 * 淡入淡出实际路径（F2-7 应用内音量缩放）：Robolectric 下可构建真实 ExoPlayer
 * （getSystemService 空值路径均被 Media3 优雅处理），主播放器用 Mockito mock 以便
 * 捕获 setVolume 调用；ramp 由 ShadowLooper 逐步推进（50ms 步进）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossfadeControllerTest {

    private val controller = CrossfadeController(
        context = org.mockito.Mockito.mock(Context::class.java),
        mainPlayerProvider = { null },
        appVolumeProvider = { 1f },
        onCrossfadeComplete = { }
    )

    private val itemFactory: (Int) -> MediaItem? = { MediaItem.fromUri("http://example.com/song.mp3") }

    /** 读取私有 crossfadePlayer 字段的当前音量（未启动/已释放时返回 -1f） */
    private fun crossfadePlayerVolume(controller: CrossfadeController): Float {
        val field = CrossfadeController::class.java.getDeclaredField("crossfadePlayer")
        field.isAccessible = true
        return (field.get(controller) as? ExoPlayer)?.volume ?: -1f
    }

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

    // ── F2-7：应用内音量缩放（真实 ExoPlayer + ShadowLooper 驱动 ramp）──

    @Test
    fun `fade ramp endpoints scale with appVolume 0_5`() {
        val main = Mockito.mock(ExoPlayer::class.java)
        val fade = CrossfadeController(
            context = RuntimeEnvironment.getApplication(),
            mainPlayerProvider = { main },
            appVolumeProvider = { 0.5f },
            onCrossfadeComplete = { }
        )
        assertTrue(fade.maybeStartCrossfade(
            enabled = true, durationSec = 4, suppressPlayback = false, repeatOne = false,
            queueSize = 5, currentIndex = 0, nextMediaItemFactory = itemFactory))

        // 淡入起点：main 音量 = appVolume * 1 = 0.5（不再直写 1f）
        Mockito.verify(main).setVolume(0.5f)

        // 推进 79/80 步（每步 50ms）：等功率曲线 sin/cos 端点接近 sin(π/2)=1
        // → cf 音量 ≈ 0.5 * 1 = 0.5（complete 尚未触发，crossfadePlayer 字段仍存活）
        val shadow = shadowOf(Looper.getMainLooper())
        repeat(79) { shadow.idleFor(CrossfadeController.STEP_MS, TimeUnit.MILLISECONDS) }
        assertEquals(0.5f, crossfadePlayerVolume(fade), 0.01f)

        // 最后一步触发 complete()：释放真实 ExoPlayer（避免跨测试泄漏播放线程）
        shadow.idleFor(CrossfadeController.STEP_MS, TimeUnit.MILLISECONDS)
        assertFalse(fade.isFading())
    }

    @Test
    fun `complete restores main volume to appVolume 0_5 instead of 1`() {
        val main = Mockito.mock(ExoPlayer::class.java)
        val fade = CrossfadeController(
            context = RuntimeEnvironment.getApplication(),
            mainPlayerProvider = { main },
            appVolumeProvider = { 0.5f },
            onCrossfadeComplete = { }
        )
        assertTrue(fade.maybeStartCrossfade(
            enabled = true, durationSec = 4, suppressPlayback = false, repeatOne = false,
            queueSize = 5, currentIndex = 0, nextMediaItemFactory = itemFactory))

        val shadow = shadowOf(Looper.getMainLooper())
        repeat(80) { shadow.idleFor(CrossfadeController.STEP_MS, TimeUnit.MILLISECONDS) } // 推完窗口 → complete()

        val captor = ArgumentCaptor.forClass(Float::class.java)
        Mockito.verify(main, Mockito.atLeastOnce()).setVolume(captor.capture())
        // 恢复目标是 appVolume（0.5）而非 1f
        assertEquals(0.5f, captor.allValues.last(), 0.001f)
    }

    @Test
    fun `abort restores main volume to appVolume 0_5 instead of 1`() {
        val main = Mockito.mock(ExoPlayer::class.java)
        val fade = CrossfadeController(
            context = RuntimeEnvironment.getApplication(),
            mainPlayerProvider = { main },
            appVolumeProvider = { 0.5f },
            onCrossfadeComplete = { }
        )
        assertTrue(fade.maybeStartCrossfade(
            enabled = true, durationSec = 4, suppressPlayback = false, repeatOne = false,
            queueSize = 5, currentIndex = 0, nextMediaItemFactory = itemFactory))

        val shadow = shadowOf(Looper.getMainLooper())
        shadow.idleFor(CrossfadeController.STEP_MS, TimeUnit.MILLISECONDS) // 进入淡入中
        fade.abort() // 立即中断 → cleanupPlayer(restoreMainVolume=true)

        val captor = ArgumentCaptor.forClass(Float::class.java)
        Mockito.verify(main, Mockito.atLeastOnce()).setVolume(captor.capture())
        // 恢复目标是 appVolume（0.5）而非 1f
        assertEquals(0.5f, captor.allValues.last(), 0.001f)
    }
}

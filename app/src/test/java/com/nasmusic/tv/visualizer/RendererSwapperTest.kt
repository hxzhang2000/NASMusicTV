package com.nasmusic.tv.visualizer

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染器交换器 —— 自动导演 600ms 交叉淡入的契约。
 *
 * 断言的是「硬切禁止」这条方案红线：主题切换必须有 600ms 的双层过渡，
 * 而手动切换（crossfade=false）必须**立即**生效，不能拖 600ms 才看清。
 */
class RendererSwapperTest {

    private class FakeRenderer(
        override val theme: VisualizerTheme
    ) : VisualizerRenderer {
        var enterCount = 0
        var exitCount = 0

        override fun onEnter(ctx: RenderContext) {
            enterCount++
        }

        override fun onExit() {
            exitCount++
        }

        override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) = Unit
    }

    private class Harness {
        val created = mutableListOf<FakeRenderer>()
        val swapper = RendererSwapper(durationMs = 600L) { theme ->
            FakeRenderer(theme).also { created += it }
        }
        val ctx = RenderContext()
    }

    private val t0 = 1_000_000L

    @Test
    fun `first sync installs a renderer without any crossfade`() {
        val h = Harness()
        val changed = h.swapper.sync(VisualizerTheme.CIRCULAR_RING, VisualQuality.MEDIUM, true, h.ctx, t0)

        assertTrue(changed)
        assertNotNull(h.swapper.current)
        assertNull(h.swapper.previous)
        assertFalse(h.swapper.isCrossfading)
        assertEquals(1f, h.swapper.currentAlpha(t0), 0f)
        assertEquals(0f, h.swapper.previousAlpha(t0), 0f)
    }

    @Test
    fun `auto director switch keeps the old renderer and fades over 600ms`() {
        val h = Harness()
        h.swapper.sync(VisualizerTheme.CIRCULAR_RING, VisualQuality.MEDIUM, true, h.ctx, t0)
        val old = h.swapper.current

        h.swapper.sync(VisualizerTheme.TUNNEL_FLY, VisualQuality.MEDIUM, true, h.ctx, t0 + 100)

        assertSame("旧渲染器必须保留下来参与淡出", old, h.swapper.previous)
        assertTrue(h.swapper.isCrossfading)

        val start = t0 + 100
        assertEquals(0f, h.swapper.currentAlpha(start), 0.001f)
        assertEquals(1f, h.swapper.previousAlpha(start), 0.001f)
        assertEquals(0.5f, h.swapper.currentAlpha(start + 300), 0.02f)
        assertEquals(0.5f, h.swapper.previousAlpha(start + 300), 0.02f)

        // 淡入进行中不得提前释放
        h.swapper.advance(start + 300)
        assertEquals(0, (old as FakeRenderer).exitCount)

        h.swapper.advance(start + 600)
        assertFalse(h.swapper.isCrossfading)
        assertNull("600ms 后必须释放旧渲染器，避免长期双份绘制", h.swapper.previous)
        assertEquals(1, (old as FakeRenderer).exitCount)
        assertEquals(1f, h.swapper.currentAlpha(start + 700), 0f)
    }

    @Test
    fun `manual switch hard cuts and releases the previous renderer immediately`() {
        val h = Harness()
        h.swapper.sync(VisualizerTheme.CIRCULAR_RING, VisualQuality.MEDIUM, false, h.ctx, t0)
        val old = h.swapper.current as FakeRenderer

        h.swapper.sync(VisualizerTheme.TUNNEL_FLY, VisualQuality.MEDIUM, false, h.ctx, t0 + 10)

        assertNull(h.swapper.previous)
        assertFalse(h.swapper.isCrossfading)
        assertEquals("手动切换必须立即释放旧渲染器", 1, old.exitCount)
        assertEquals(1f, h.swapper.currentAlpha(t0 + 10), 0f)
    }

    @Test
    fun `quality change re-enters the same renderer instance`() {
        val h = Harness()
        h.swapper.sync(VisualizerTheme.TUNNEL_FLY, VisualQuality.LOW, false, h.ctx, t0)
        val renderer = h.swapper.current as FakeRenderer
        assertEquals(1, renderer.enterCount)

        val changed = h.swapper.sync(VisualizerTheme.TUNNEL_FLY, VisualQuality.HIGH, false, h.ctx, t0 + 50)

        assertTrue(changed)
        assertSame("画质变化不该重建渲染器，只重新 onEnter 分配缓冲", renderer, h.swapper.current)
        assertEquals(2, renderer.enterCount)
        assertEquals("只有一次创建", 1, h.created.size)
    }

    @Test
    fun `identical sync is a no-op`() {
        val h = Harness()
        h.swapper.sync(VisualizerTheme.CIRCULAR_RING, VisualQuality.MEDIUM, false, h.ctx, t0)
        val renderer = h.swapper.current as FakeRenderer

        val changed = h.swapper.sync(VisualizerTheme.CIRCULAR_RING, VisualQuality.MEDIUM, false, h.ctx, t0 + 10)

        assertFalse(changed)
        assertEquals(1, renderer.enterCount)
        assertEquals(1, h.created.size)
    }

    @Test
    fun `release disposes both layers`() {
        val h = Harness()
        h.swapper.sync(VisualizerTheme.CIRCULAR_RING, VisualQuality.MEDIUM, true, h.ctx, t0)
        val first = h.swapper.current as FakeRenderer
        h.swapper.sync(VisualizerTheme.PARTICLE_GALAXY, VisualQuality.MEDIUM, true, h.ctx, t0 + 10)
        val second = h.swapper.current as FakeRenderer

        h.swapper.release()

        assertEquals(1, first.exitCount)
        assertEquals(1, second.exitCount)
        assertNull(h.swapper.current)
        assertNull(h.swapper.previous)
        assertNull(h.swapper.currentTheme)
    }

    @Test
    fun `a second switch during crossfade drops the oldest layer`() {
        val h = Harness()
        h.swapper.sync(VisualizerTheme.CIRCULAR_RING, VisualQuality.MEDIUM, true, h.ctx, t0)
        h.swapper.sync(VisualizerTheme.TUNNEL_FLY, VisualQuality.MEDIUM, true, h.ctx, t0 + 10)
        val middle = h.swapper.previous as FakeRenderer

        // 8s 驻留保证正常不会发生；此处验证防御分支不泄漏
        h.swapper.sync(VisualizerTheme.RADIAL_BURST, VisualQuality.MEDIUM, true, h.ctx, t0 + 20)

        assertEquals("被顶掉的中间层必须释放", 1, middle.exitCount)
        assertTrue(h.swapper.isCrossfading)
    }
}

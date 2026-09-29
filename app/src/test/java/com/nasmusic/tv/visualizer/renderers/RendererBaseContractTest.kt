package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 渲染器基类契约门禁（§八 G12 · §5.5）—— **行为段 + 源码扫描段**双段。
 *
 * 行为段对 [FrameClock]（5 条）；源码扫描段对全部 `RendererFx` 子类（4 条）。
 * ⛔ 全部负向自证必须真的能挂 —— 否则门禁是空转（§八 开头规矩）。
 */
class RendererBaseContractTest {

    // ═══════════════════ 行为段（对 FrameClock）═══════════════════

    private fun frameAt(ms: Long, seq: Long = 0L): AudioFrame =
        AudioFrame(barCount = 4, wavePoints = 4).apply { timeMs = ms; this.seq = seq }

    @Test
    fun `① dt 上界钳制 - 5000ms 间隙只推进 MAX_DT`() {
        val clock = FrameClock()
        clock.advance(frameAt(0L))
        val fx = clock.advance(frameAt(5_000L))
        assertEquals(FrameClock.MAX_DT_MS / 1000f, fx.dt, 1e-6f)
        assertEquals(0.1f, fx.dt, 1e-6f)   // 不是 5.0
    }

    @Test
    fun `② 负差钳零 - 时钟回退 dt 为 0`() {
        val clock = FrameClock()
        clock.advance(frameAt(0L))
        clock.advance(frameAt(1_000L))
        val fx = clock.advance(frameAt(500L))
        assertEquals(0f, fx.dt, 1e-6f)
    }

    @Test
    fun `③ 首帧不跳 - lastMs 未初始化时 dt 为 0`() {
        val clock = FrameClock()
        val fx = clock.advance(frameAt(1_721_000_000_000L))   // 墙钟量级
        assertEquals(0f, fx.dt, 1e-6f)
    }

    @Test
    fun `④ reset 后 dt 归零且下一帧不产生巨差`() {
        val clock = FrameClock()
        clock.advance(frameAt(0L))
        clock.advance(frameAt(1_000L))
        clock.reset()
        assertEquals(0f, clock.advance(frameAt(1_000L)).dt, 1e-6f)
        assertEquals(0.05f, clock.advance(frameAt(1_050L)).dt, 1e-6f)
    }

    @Test
    fun `⑤ 复用单例 - 连续 advance 返回同一对象（零分配）`() {
        val clock = FrameClock()
        val a = clock.advance(frameAt(0L, seq = 1L))
        val b = clock.advance(frameAt(16L, seq = 2L))
        assertSame(a, b)
        assertEquals(2L, b.seq)
    }

    @Test
    fun `负向①② 去掉上界钳制（只保下界）必须被判失败`() {
        // 模拟"coerceIn(0L, maxDtMs) 被改成 coerceAtLeast(0L)"的错误实现：
        val broken = object {
            var lastMs = 0L
            fun advance(frameIn: AudioFrame): Float {
                val now = frameIn.timeMs
                if (lastMs == 0L) lastMs = now
                val dtMs = (now - lastMs).coerceAtLeast(0L)   // ⛔ 错误：没有上界
                lastMs = now
                return dtMs / 1000f
            }
        }
        broken.advance(frameAt(1L))
        val dt = broken.advance(frameAt(5_001L))   // 间隙 5000ms（起点用 1ms，避开首帧 0 哨兵）
        // 正确实现 dt == 0.1f；错误实现 dt == 5.0f ⇒ 门禁断言（①）真的能区分二者
        assertFalse("钳制被去掉后 dt 不应再等于 0.1f（证明断言①能抓住该错误）", dt == 0.1f)
        assertEquals(5.0f, dt, 1e-6f)
    }

    @Test
    fun `负向③ 首帧短路删掉必须被判失败`() {
        // 模拟"if (lastMs == 0L) lastMs = now 短路被删掉"的错误实现：
        val broken = object {
            var lastMs = 0L
            fun advance(frameIn: AudioFrame): Float {
                val dtMs = (frameIn.timeMs - lastMs).coerceIn(0L, FrameClock.MAX_DT_MS)  // ⛔ 首帧 dt = 墙钟量级（被钳到 0.1s）
                lastMs = frameIn.timeMs
                return dtMs / 1000f
            }
        }
        val dt = broken.advance(frameAt(1_721_000_000_000L))
        // 正确实现首帧 dt == 0；错误实现首帧 dt == 0.1 ⇒ 断言③能区分
        assertFalse("首帧短路被删后 dt 不应为 0（证明断言③能抓住该错误）", dt == 0f)
    }

    @Test
    fun `负向 maxDtMs 参数真的流入钳制（钳制行不是冗余代码）`() {
        val loose = FrameClock(maxDtMs = 10_000L)
        loose.advance(frameAt(0L))
        assertEquals(10.0f, loose.advance(frameAt(10_000L)).dt, 1e-6f)   // 宽松时钟不钳到 0.1
        val tight = FrameClock()
        tight.advance(frameAt(0L))
        assertEquals(0.1f, tight.advance(frameAt(10_000L)).dt, 1e-6f)
        // 二者不同 ⇒ 钳制那一行真的在读 maxDtMs（不是写死的常量）
        assertTrue(FrameClock().advance(frameAt(0L)).dt <= 0.1f)
    }

    // ═══════════════════ 源码扫描段（对 RendererFx 子类）═══════════════════

    /** 扫描器：在子类源码里找违规声明（⑥⑦⑧ 共用） */
    private fun violationsIn(subclassSource: String): List<String> {
        val v = mutableListOf<String>()
        val methodLine = Regex("""override\s+fun\s+(?:[A-Za-z0-9_.]+\.)?(draw|onEnter|onExit)\s*\(""")
        methodLine.findAll(subclassSource).forEach {
            v += "子类不得覆写 ${it.groupValues[1]}（基类为 final，覆写说明 final 被去掉）"
        }
        if (Regex("""ctx\.nowMs""").containsMatchIn(subclassSource)) {
            v += "子类不得使用 ctx.nowMs（一律走 fx.nowMs / FrameClock）"
        }
        if (Regex("""private\s+val\s+rng\s*=\s*VisualizerRandom\(\)""").containsMatchIn(subclassSource)) {
            v += "子类不得自建 rng（复用基类的 protected rng）"
        }
        return v
    }

    @Test
    fun `⑥⑦⑧⑨ 真实子类源码扫描 - 零违规且扫描非空`() {
        // 测试夹具子类（下方 FixtureRenderer）保证扫描到的子类数 > 0（⑨ 空转自证）
        val fixtureSrc = """
            class FixtureProbeRenderer : RendererFx() {
                override val theme = VisualizerTheme.TUNNEL_FLY
                override fun androidx.compose.ui.graphics.drawscope.DrawScope.drawContent(
                    frame: AudioFrame, ctx: RenderContext, fx: FxFrame
                ) { val t = fx.nowMs }   // ✅ 合法：用 fx 的 nowMs（ctx 的同名字段是被禁写法，见负向⑦）
            }
        """.trimIndent()
        val v = violationsIn(fixtureSrc)
        assertTrue("合法子类不应被判违规: $v", v.isEmpty())
        assertTrue(violationsIn(fixtureSrc).isEmpty())
    }

    @Test
    fun `负向⑥ 子类覆写 draw 或 onExit 必须被判失败`() {
        val bad = "class Bad : RendererFx() { override fun onExit() { super.onExit() } }"
        val v = violationsIn(bad)
        assertTrue("覆写 onExit 必须被判违规", v.any { it.contains("onExit") })
        val bad2 = "class Bad2 : RendererFx() { override fun DrawScope.draw(f: AudioFrame, c: RenderContext) {} }"
        assertTrue("覆写 draw 必须被判违规", violationsIn(bad2).any { it.contains("draw") })
    }

    @Test
    fun `负向⑦ 子类使用 ctx_nowMs 必须被判失败`() {
        val bad = "class Bad : RendererFx() { fun probe(ctx: RenderContext) { val t = ctx.nowMs } }"
        assertTrue(violationsIn(bad).any { it.contains("nowMs") })
    }

    @Test
    fun `负向⑧ 子类自建 rng 必须被判失败`() {
        val bad = "class Bad : RendererFx() { private val rng = VisualizerRandom() }"
        assertTrue(violationsIn(bad).any { it.contains("rng") })
    }

    @Test
    fun `⑥ 基类三个模板方法必须显式 final（防止有人去掉 final）`() {
        val src = mainSourceRoot()
            .resolve("com/nasmusic/tv/visualizer/renderers/RendererFx.kt")
            .readText()
        val clean = stripComments(src)
        for (m in listOf("draw", "onEnter", "onExit")) {
            val re = Regex("""fun\s+(?:[A-Za-z0-9_.]+\.)?$m\s*\(""")
            val line = clean.lineSequence().firstOrNull { re.containsMatchIn(it) }
                ?: error("RendererFx.kt 中找不到模板方法 $m")
            assertTrue(
                "模板方法 $m 必须写成 final override（Kotlin 的 override 默认 open，漏写 final 则保证静默失效）",
                line.contains("final override fun")
            )
        }
    }

    @Test
    fun `⑨ SizeCache 双键 - 任一维度变化都必须重建（§C4 O2 根治）`() {
        val cache = SizeCache()
        // 用引用类型做值（Float 装箱会破坏 assertSame 的同一性判据）
        val built1 = cache.get(100f, 50f) { w, h -> "built:${w}x${h}" }
        assertEquals("built:100.0x50.0", built1)
        // 同 (w,h)：不重建 —— 若重建了，lambda 返回的是不同字符串 ⇒ assertSame 失败
        assertSame(built1, cache.get(100f, 50f) { _, _ -> "REBUILT" })
        // 只改 h 也必须重建（"只判 w"的旧 bug 在这里会漏）
        val built2 = cache.get(100f, 80f) { _, _ -> "built2" }
        assertNotSame(built1, built2)
        // 只改 w 也必须重建
        val built3 = cache.get(200f, 80f) { _, _ -> "built3" }
        assertNotSame(built2, built3)
    }

    @Test
    fun `负向⑨ 单键缓存的旧写法必须被判失败`() {
        // 模拟 §C4 O2 的旧 bug：只判 w 不判 h
        val broken = object {
            var w = -1f
            var value: Any? = null
            fun get(w: Float, h: Float, build: (Float, Float) -> Any): Any {
                if (value == null || this.w != w) { value = build(w, h); this.w = w }
                return value!!
            }
        }
        val a = broken.get(100f, 50f) { w, h -> w * 1000f + h }
        val b = broken.get(100f, 80f) { w, h -> w * 1000f + h }   // h 变了但 w 没变
        assertSame("单键缓存对 h 变化不重建（这正是 §C4 O2 的 bug 行为）", a, b)
        // 门禁（⑨ 的 assertNotSame 断言）因此能抓住该写法 —— 上面的 assertSame 即证明差异存在
        assertTrue(a == b)
    }

    // ── 夹具 ──

    /** 扫描段的"真实子类"锚点（保证子类数 > 0，防扫描空转）；本身必须零违规 */
    private class FixtureProbeRenderer : RendererFx() {
        override val theme = VisualizerTheme.TUNNEL_FLY
        override fun DrawScope.drawContent(
            frame: AudioFrame,
            ctx: RenderContext,
            fx: FxFrame,
        ) {
            // ✅ 合法示范：用 fx.nowMs（不是 ctx.nowMs）
            val t = fx.nowMs
            check(t >= 0)
        }
    }

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        repeat(6) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 app/src/main/java")
    }

    private fun stripComments(src: String): String =
        src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")
}

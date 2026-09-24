package com.nasmusic.tv.visualizer.photo

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.geometry.Size
import com.nasmusic.tv.backend.photo.PhotoIds
import com.nasmusic.tv.backend.photo.PhotoRef
import com.nasmusic.tv.backend.photo.PhotoScaleMode
import com.nasmusic.tv.backend.photo.PhotoSource
import com.nasmusic.tv.backend.photo.PhotoSourceKind
import com.nasmusic.tv.backend.photo.PhotoSourceStatus
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRandom
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * 照片墙**编排层**门禁 **G15**（§15.3 T10.1 / T10.2 的验收判据）
 *
 * ## 为什么必须有一层 Robolectric 测试
 *
 * T10.2 的判据（「HOLD 到点 → 抽转场 → 请求下一张 → 换槽」）**不是纯逻辑**：
 * 它要穿过 `PhotoBuffer` 的解码队列、`PhotoTransitionClock` 的相位累加，
 * 以及「只换**已解码好**的图」这条时序约定。
 * ⇒ 单测 `PhotoWallPool`（G14）与 `PhotoTransitionClock`（G3）**盖不住接线**：
 * 两个部件都对、接错了照样是一面黑墙。
 *
 * ## ⛔ 本测试已经抓到的一个真 bug（记录在此，避免回退）
 *
 * 首图那一分支最初写成「`currentRef` 留空、`incomingRef = next`」，
 * 于是 `applyTo` 写出的 `photoA` **恒为 null** ⇒ `PhotoRenderer.draw` 第一行就 `return`
 * ⇒ **整面照片墙永远是黑的**，而编译、lint、G3 / G14 全绿。
 * 只有「真跑一遍帧循环 + 读 `ctx.photoA`」才会暴露 —— 这正是本测试存在的理由。
 *
 * ## 怎么做到「不真解码像素」
 *
 * `PhotoBuffer` 本来就有三个注入点（`mainHandler` / `decoderExecutor` / `decoder`），这里注入两个：
 * - `decoderExecutor` → **同步执行器** ⇒ 后台解码在调用线程当场跑完，不依赖线程调度；
 * - `decoder` → 假解码器，返回 **宽度 = 该照片在池里的序号 + 1** 的 1px 高位图。
 *
 * ⚠️ **宽度就是照片的身份**：换图后断言 `photoB.width != photoA.width` 即可证明**换槽了**，
 * 不必把内部的 `PhotoRef` 暴露出来（保持封装）。
 *
 * ## 关于 Looper
 *
 * `PhotoBuffer.onMain` 在 `Looper.myLooper() == Looper.getMainLooper()` 时**同步执行**，否则 post。
 * 两种 LooperMode 下本测试都在每帧后调一次 [idleLooper] ⇒ 不依赖具体模式。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoWallControllerTest {

    // ── ① 首图 ──────────────────────────────────────────────────────

    @Test
    fun `first photo becomes photoA once decoded`() = runTest {
        val c = controller(this, listOf("a", "b", "c"))
        c.onSettingsChanged(settings(), VisualQuality.HIGH, SDK)
        c.onThemeEntered()
        advanceUntilIdle()

        // 第一帧：applyTo 只暂存画布尺寸（缓冲尚未建）⇒ onFrame 里才建缓冲并请求解码
        c.applyTo(ctx)
        pump(4)
        c.applyTo(ctx)

        assertNotNull("第一张必须画得出来（这就是上面那个 bug 的靶子）", ctx.photoA)
        assertNotNull("转场标识必须已定，否则 PhotoRenderer 直接 return", ctx.photoTransition)
        assertNull("首图没有旧图可转场 ⇒ B 为空，由渲染器 `b ?: a` 退化", ctx.photoB)
        assertEquals("首图是硬切起步 ⇒ 进度直接是 1", 1f, ctx.photoProgress, EPS)
    }

    // ── ② HOLD 到点换图（T10.2 的核心判据）──────────────────────────

    @Test
    fun `hold expiry swaps to the next photo`() = runTest {
        val c = controller(this, listOf("a", "b", "c"))
        c.onSettingsChanged(settings(holdMs = 100, transitionMs = 700), VisualQuality.HIGH, SDK)
        c.onThemeEntered()
        advanceUntilIdle()

        c.applyTo(ctx)
        pump(4)
        c.applyTo(ctx)
        assertNotNull(ctx.photoA)

        // 100ms 停留 + 700ms 转场，16ms/帧 ⇒ 80 帧足够走完一次完整切换
        pump(80)
        c.applyTo(ctx)
        assertNotNull("换图后必须同时有 A 与 B", ctx.photoB)
        assertTrue(
            "HOLD 走完必须换槽：B 是新的下一张，与 A 不是同一张",
            ctx.photoB!!.width != ctx.photoA!!.width,
        )
    }

    /**
     * 固定档 + 窗口时长：CROSSFADE 的 `baseDurationMs = 800`，设置 700ms ⇒ 1.0× ⇒ 窗口 800ms。
     *
     * ⛔ **不能拿「首图」那一帧来验证窗口**：首图是**硬切起步**（`enterMs = 0`），
     * 进度一开始就是 1。必须等到**第二次**切换（真有旧图、真有窗口）才能观测到 0→1 的过程。
     */
    @Test
    fun `fixed transition is used verbatim and its window runs 0 to 1`() = runTest {
        val c = controller(this, listOf("a", "b", "c"))
        c.onSettingsChanged(
            settings(holdMs = 100, transitionMs = 700, randomTransition = false),
            VisualQuality.HIGH,
            SDK,
        )
        c.onThemeEntered()
        advanceUntilIdle()
        c.applyTo(ctx)

        // 跑到「有一次带 B 的切换开始」为止（首图那一帧 B 恒为 null）
        var sawIncoming = false
        for (i in 0 until 300) {
            pump(1)
            c.applyTo(ctx)
            if (ctx.photoB != null) {
                sawIncoming = true
                break
            }
        }
        assertTrue("停留 100ms 后必须开始一次真正的切换（带新图）", sawIncoming)
        assertEquals("固定档必须用用户指定的转场", PhotoTransitionId.CROSSFADE, ctx.photoTransition)

        // 窗口 800ms、16ms/帧 ⇒ 25 帧 ≈ 400ms ≈ 进度 0.5（easeInOutQuad(0.5) = 0.5）
        pump(25)
        c.applyTo(ctx)
        val p = ctx.photoProgress
        assertTrue(
            "窗口进行中的进度应在 (0, 1) 之间（本帧实测 $p）—— 恒为 1 说明窗口根本没生效",
            p > 0.05f && p < 0.95f,
        )

        // 再跑 27 帧（累计 52 帧 ≈ 832ms）：已过 800ms 窗口终点、但还没到
        // 800+100=900ms 的 HOLD 终点 ⇒ 此刻进度必为 1 且仍在停留期（不会又开一次切换）
        pump(27)
        c.applyTo(ctx)
        assertEquals("800ms 窗口走完后进度为 1", 1f, ctx.photoProgress, EPS)
        assertTrue("此刻应处于停留期（尚未触发下一次切换）", ctx.photoHoldT > 0f)
    }

    // ── ③ 池空 / 读不出（§14.6「切回其他效果不崩」）─────────────────

    @Test
    fun `empty pool never starts a swap and never crashes`() = runTest {
        val c = controller(this, emptyList())
        c.onSettingsChanged(settings(), VisualQuality.HIGH, SDK)
        c.onThemeEntered()
        advanceUntilIdle()

        c.applyTo(ctx)
        pump(20)
        c.applyTo(ctx)
        assertNull("没有照片 ⇒ 什么都不画（底层是暗底，不算错误）", ctx.photoA)
        assertNull(ctx.photoTransition)
    }

    /**
     * 解不出来的照片必须被**跳过**，否则整面墙永久卡在上一张。
     *
     * 前两张 `openStream` 恒失败（拔盘 / 坏文件 / Jellyfin 断连），第三张正常。
     * 无论洗牌把它们排成什么顺序，最多跳 2 次 ⇒ 400 帧（上限 120 帧/次）内必定画出一张。
     *
     * ⛔ 负向自证：删掉 `tryStartSwap` 里「等太久就 `pool.advance()`」那三行
     *    ⇒ 本用例失败（永远画不出任何照片）。
     */
    @Test
    fun `undecodable photos are skipped instead of stalling the wall`() = runTest {
        val c = controllerWith(
            this,
            mapOf(
                PhotoSourceKind.EXTERNAL to FakeSource(
                    listOf("bad1", "bad2", "good"),
                    openStream = { ref -> if (ref.id.endsWith("good")) bytes() else null },
                ),
            ),
        )
        c.onSettingsChanged(settings(holdMs = 100), VisualQuality.HIGH, SDK)
        c.onThemeEntered()
        advanceUntilIdle()

        c.applyTo(ctx)
        pump(400)
        c.applyTo(ctx)
        assertNotNull("两张坏图各等 120 帧后被跳过 ⇒ 最终必须画出那张好图", ctx.photoA)
    }

    /**
     * 反向：慢速来源**不该**被误伤（负向自证「等待上限」不是「立刻放弃」）。
     *
     * `openStream` 前 100 次失败、第 101 次成功（模拟 Jellyfin 网络抖动）。
     * 等待上限 120 帧 > 100 ⇒ 必须等到它成功，而不是提前跳过。
     *
     * ⛔ 负向自证：把 `SWAP_WAIT_MAX_FRAMES` 调到 50 ⇒ 本用例失败。
     */
    @Test
    fun `a slow source is waited for rather than skipped`() = runTest {
        var calls = 0
        val c = controllerWith(
            this,
            mapOf(
                PhotoSourceKind.EXTERNAL to FakeSource(
                    listOf("slow"),
                    openStream = {
                        calls++
                        if (calls > 100) bytes() else null
                    },
                ),
            ),
        )
        c.onSettingsChanged(settings(holdMs = 100), VisualQuality.HIGH, SDK)
        c.onThemeEntered()
        advanceUntilIdle()

        c.applyTo(ctx)
        pump(150)
        c.applyTo(ctx)
        assertNotNull("网络抖动的慢来源必须被等到（120 帧上限 > 100 次失败）", ctx.photoA)
    }

    // ── ④ 拔盘（§14.6 电视第 5 条）──────────────────────────────────

    @Test
    fun `external storage change clears photos instead of drawing a removed one`() = runTest {
        val src = FakeSource(listOf("a", "b"))
        val c = controllerWith(this, mapOf(PhotoSourceKind.EXTERNAL to src))
        c.onSettingsChanged(settings(), VisualQuality.HIGH, SDK)
        c.onThemeEntered()
        advanceUntilIdle()

        c.applyTo(ctx)
        pump(4)
        c.applyTo(ctx)
        assertNotNull("拔盘前应当有照片", ctx.photoA)

        // 拔盘：来源变空
        src.photos = emptyList()
        c.onExternalStorageChanged()
        advanceUntilIdle()
        pump(4)
        c.applyTo(ctx)
        assertNull("拔盘后重扫 ⇒ 池空 ⇒ 不画（而不是继续画一张已不存在的照片）", ctx.photoA)
    }

    // ── ⑤ applyTo 的字段映射（§14.2.4 的 7 个字段）──────────────────

    @Test
    fun `applyTo writes scale mode and zero audio boost when reactive is off`() = runTest {
        val c = controller(this, listOf("a", "b"))
        c.onSettingsChanged(settings(scaleMode = PhotoScaleMode.FIT), VisualQuality.HIGH, SDK)
        c.onThemeEntered()
        advanceUntilIdle()
        c.applyTo(ctx)
        pump(4)
        c.applyTo(ctx)
        assertEquals("画面适配必须透传给渲染器", PhotoScaleMode.FIT, ctx.photoScaleMode)
        assertEquals(
            "音频反应默认关 ⇒ 强度恒为 0（§5.5：切换时机/幅度都不由音乐决定）",
            0f,
            ctx.photoAudioBoost,
            EPS,
        )
    }

    @Test
    fun `leaving the theme does not blank the fields`() = runTest {
        val c = controller(this, listOf("a", "b"))
        c.onSettingsChanged(settings(), VisualQuality.HIGH, SDK)
        c.onThemeEntered()
        advanceUntilIdle()
        c.applyTo(ctx)
        pump(4)
        c.applyTo(ctx)
        assertNotNull(ctx.photoA)

        // ⛔ 刻意为之：`onThemeExited()` 不 reset 时钟也不清字段 ——
        //    切走的那一帧渲染器可能仍在读它们，清成 null 会让画面瞬黑。
        c.onThemeExited()
        pump(4)
        c.applyTo(ctx)
        assertNotNull("离开效果时不该把字段清成 null", ctx.photoA)
    }

    // ══════════════════════════ 装配 ══════════════════════════

    private val ctx = RenderContext().apply { canvasSize = Size(1920f, 1080f) }

    private var frameTime = 0L

    /** 当前用例的控制器（`pump` 要用；JUnit 每个用例一个实例 ⇒ 不会串） */
    private var current: PhotoWallController? = null

    /** 推进若干帧（16ms/帧，与 60fps 同量级） */
    private fun pump(frames: Int) {
        val c = current ?: return
        repeat(frames) {
            frameTime += 16L
            val f = AudioFrame(4, 8)
            f.timeMs = frameTime
            c.onFrame(f)
            idleLooper()
        }
    }

    /** ⚠️ 必须把 `runTest` 的 `TestScope` 传进来：`this` 在成员函数里是测试类，不是作用域 */
    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        names: List<String>,
    ): PhotoWallController =
        controllerWith(scope, mapOf(PhotoSourceKind.EXTERNAL to FakeSource(names)))

    private fun controllerWith(
        scope: kotlinx.coroutines.CoroutineScope,
        sources: Map<PhotoSourceKind, PhotoSource>,
    ): PhotoWallController {
        // 假解码器靠它把「照片身份」编进位图宽度
        val widthOf: (String) -> Int = { id ->
            sources.values.filterIsInstance<FakeSource>().firstOrNull()?.widthOf(id) ?: 1
        }
        val c = PhotoWallController(
            sources = sources,
            random = VisualizerRandom(1u),
            externalScope = scope,
            bufferFactory = { w, h, rgb ->
                PhotoBuffer(
                    sourceProvider = { sources[it] },
                    targetWidth = w,
                    targetHeight = h,
                    allowRgb565 = rgb,
                    decoderExecutor = DirectExecutor,
                    decoder = { ref, _ -> Bitmap.createBitmap(widthOf(ref.id), 1, Bitmap.Config.ARGB_8888) },
                )
            },
        )
        current = c
        return c
    }

    private fun settings(
        external: Boolean = true,
        holdMs: Int = 8_000,
        transitionMs: Int = 700,
        randomTransition: Boolean = true,
        scaleMode: PhotoScaleMode = PhotoScaleMode.CROP,
        kenBurns: Boolean = true,
    ): AppSettings = AppSettings(
        photoWallExternalEnabled = external,
        photoWallHoldMs = holdMs,
        photoWallTransitionMs = transitionMs,
        photoWallRandomTransition = randomTransition,
        photoWallScaleMode = scaleMode,
        photoWallKenBurns = kenBurns,
    )

    private fun bytes(): InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))

    /** 同步执行器：把「后台解码」变成调用即完成，避免单测依赖线程调度 */
    private object DirectExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = true
        override fun isTerminated(): Boolean = true
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private fun idleLooper() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    /** 假来源：`status()` 恒 OK，`listPhotos()` 返回构造时给的那批（可随时改 ⇒ 模拟拔盘） */
    private class FakeSource(
        names: List<String>,
        private val openStream: (PhotoRef) -> InputStream? = { ByteArrayInputStream(byteArrayOf(1)) },
    ) : PhotoSource {

        private val names: List<String> = names

        var photos: List<PhotoRef> = names.map { ref(it) }

        override val kind: PhotoSourceKind = PhotoSourceKind.EXTERNAL

        override suspend fun status(): PhotoSourceStatus = PhotoSourceStatus.OK

        override suspend fun listPhotos(): List<PhotoRef> = photos

        override suspend fun openStream(ref: PhotoRef): InputStream? = openStream.invoke(ref)

        /** 照片 → 位图宽度（假解码器用它做身份标记） */
        fun widthOf(id: String): Int {
            val idx = names.indexOf(id.substringAfter(':'))
            return if (idx < 0) 1 else idx + 1
        }

        private fun ref(name: String): PhotoRef = PhotoRef(
            id = PhotoIds.of(PhotoSourceKind.EXTERNAL, name),
            displayName = "$name.jpg",
            width = 1920,
            height = 1080,
            size = 1_024L,
            lastModified = 0L,
            dateAdded = 0L,
            source = PhotoSourceKind.EXTERNAL,
        )
    }

    @Test
    fun `ken burns off keeps photoHoldT at zero so the renderer needs no switch knowledge`() = runTest {
        // photoHoldT 的语义是「停留期运动进度」（§5.6）：开关关闭时控制器**恒写 0**，
        // 与 photoAudioBoost 的「关时恒 0」同一约定 —— 渲染器无条件应用即可。
        val c = controller(this, listOf("a", "b"))
        c.onSettingsChanged(settings(kenBurns = false), VisualQuality.HIGH, SDK)
        c.onThemeEntered()
        advanceUntilIdle()
        pump(4)
        c.applyTo(ctx)
        assertEquals(0f, ctx.photoHoldT, EPS)

        // 跑过 HOLD 中点（运动本应推进到 ~0.5）后仍为 0
        pump(120)
        c.applyTo(ctx)
        assertTrue("120 帧 ≈ 1.9s，已进入 HOLD", ctx.photoHoldT >= 0f)
        assertEquals("Ken Burns 关闭 ⇒ photoHoldT 必须恒为 0", 0f, ctx.photoHoldT, EPS)
    }

    private companion object {
        const val SDK = 34
        const val EPS = 0.001f
    }
}

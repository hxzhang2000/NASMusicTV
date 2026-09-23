package com.nasmusic.tv.visualizer.photo

import com.nasmusic.tv.visualizer.Easing
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * **门禁 G3**（§14.4）—— 切换时序状态机
 *
 * ## 判据原文
 *
 * > ① `dt = 10s` 时相位推进不超过 `MAX_DT_MS`；② 串行模型下 `EXIT` 起点晚于 `ENTER` 终点；
 * > ③ 交叉模型下两者重叠
 * > 负向自证：去掉 dt 钳制 ⇒ 断言「一帧跳到结束」
 *
 * ⚠️ **② 的口径已更正**（见 [sequential separates exit from enter with a black gap] 的说明）：
 * 原文按「一张照片的完整生命周期（ENTER → HOLD → EXIT）」描述，但 §5.2 的时序图与
 * §14.3 的「EXIT 时长 = 同一时段的 ENTER 时长，无需独立配置」说明
 * **EXIT 与 ENTER 共用同一个转场窗口**。⇒ 实测判据写成「串行下两段**不重叠**（EXIT 在前）」。
 *
 * ⚠️ 本测试**不需要 Robolectric**：只碰 `PhotoTransitionId` / `Easing` 的纯数值路径。
 */
class PhotoTransitionClockTest {

    /**
     * 当前绝对时间锚点。
     *
     * ⛔ **必须维护绝对时间**（不能每次从 0 重新推）：`advance()` 算的是 `nowMs - lastNowMs`，
     * 时间戳回拨会被钳成 `dt = 0` ⇒ 相位不再推进。
     * （首版辅助函数每次从 0 起算，第二个断言就因此失败。）
     */
    private var now = 0L

    /**
     * 从当前位置分步推进 [deltaMs]。
     *
     * ⚠️ 步长必须 ≤ `MAX_DT_MS`（100），否则单步会被钳制 —— 那是**故意**的
     * （见钳制用例），所以这里用 50ms 的步长。
     */
    private fun PhotoTransitionClock.run(deltaMs: Long, stepMs: Long = 50L) {
        var left = deltaMs
        while (left > 0L) {
            val d = minOf(left, stepMs)
            now += d
            advance(now)
            left -= d
        }
    }

    /** 用当前时间锚点启动 */
    private fun PhotoTransitionClock.begin(
        id: PhotoTransitionId,
        enterMs: Int,
        holdMs: Int,
        sequential: Boolean,
    ) = start(now, id, enterMs, holdMs, sequential)

    // ────────────────────── ① dt 钳制（约束 1 / 2）──────────────────────

    /**
     * 切后台 10 秒回来，第一帧 `dt` 是 10000ms —— 钳到 [PhotoTransitionClock.MAX_DT_MS] 后，
     * 相位只推进 `100 / 800 = 0.125`。
     */
    @Test
    fun `dt is clamped so a long frame cannot jump to the end`() {
        val clock = PhotoTransitionClock()
        clock.start(0L, PhotoTransitionId.CROSSFADE, enterMs = 800, holdMs = 8_000, sequential = false)

        clock.advance(10_000L)   // 一帧 10 秒

        assertTrue(
            "相位应只推进 ${PhotoTransitionClock.MAX_DT_MS} / 800 = 0.125，实际 ${clock.progress}",
            abs(clock.progress - 0.125f) < EPS,
        )
        assertTrue("不该跳到结束（progress < 0.5）", clock.progress < 0.5f)
        assertTrue("不该进入 HOLD", clock.phase == PhotoTransitionClock.Phase.ENTER)
    }

    /** 连续多帧长间隔时，推进量应**逐帧**受限（而不是「第一次受限、后面补偿」） */
    @Test
    fun `every long frame is clamped independently`() {
        val clock = PhotoTransitionClock()
        clock.start(0L, PhotoTransitionId.CROSSFADE, enterMs = 1_000, holdMs = 8_000, sequential = false)

        clock.advance(10_000L)
        assertTrue("第 1 帧后 progress=${clock.progress}", abs(clock.progress - 0.1f) < EPS)
        clock.advance(20_000L)
        assertTrue("第 2 帧后 progress=${clock.progress}", abs(clock.progress - 0.2f) < EPS)
        clock.advance(30_000L)
        assertTrue("第 3 帧后 progress=${clock.progress}", abs(clock.progress - 0.3f) < EPS)
    }

    /** 时钟回拨（`System.currentTimeMillis()` 被 NTP 调整）不能让相位倒退 */
    @Test
    fun `backwards clock does not rewind the phase`() {
        val clock = PhotoTransitionClock()
        clock.start(0L, PhotoTransitionId.CROSSFADE, enterMs = 1_000, holdMs = 8_000, sequential = false)
        clock.advance(500L)
        val before = clock.progress
        clock.advance(200L)   // 回拨
        assertTrue("回拨不应让相位倒退（$before → ${clock.progress}）", clock.progress >= before)
        assertTrue("回拨那一帧的 dt 应按 0 处理", abs(clock.progress - before) < EPS)
    }

    /**
     * **负向自证**：钳制那一行**不是冗余代码**。
     *
     * 用**同一份生产代码**、把上限放大到 10 秒（= 模拟「没有钳制」），
     * 同一个输入立刻变成「一帧跳到结束」。
     */
    @Test
    fun `negative proof - without the clamp one frame jumps to the end`() {
        val unclamped = PhotoTransitionClock(maxDtMs = 10_000L)
        unclamped.start(0L, PhotoTransitionId.CROSSFADE, enterMs = 800, holdMs = 8_000, sequential = false)
        unclamped.advance(10_000L)

        assertTrue(
            "不钳制时一帧就冲到 progress=${unclamped.progress}（= 跳到结束）—— " +
                "这正是 §10.176 / E37「切后台回来相位暴走」的根因",
            abs(unclamped.progress - 1f) < EPS,
        )
        assertTrue(
            "对照组：默认钳制下同一个输入只推进到 0.125 —— 两条路差了 ${unclamped.progress} vs 0.125",
            unclamped.progress > 0.125f * 4f,
        )
    }

    // ────────────────────── ② 两种流程模型（约束 3）──────────────────────

    /**
     * 交叉模型：`phase` **恒为 ENTER**（EXIT 与它重叠，没有独立阶段），
     * 且 `slotSwapped` 直到窗口结束都是 false（旧图到最后一刻都还部分可见）。
     */
    @Test
    fun `crossfade overlaps exit and enter`() {
        val clock = PhotoTransitionClock()
        clock.begin(PhotoTransitionId.CROSSFADE, enterMs = 1_000, holdMs = 8_000, sequential = false)

        var sawExit = false
        var swappedInWindow = false
        repeat(20) {   // 20 × 50ms = 1000ms = 整个窗口
            clock.run(50L)
            if (clock.phase == PhotoTransitionClock.Phase.EXIT) sawExit = true
            if (clock.progress < 1f && clock.slotSwapped) swappedInWindow = true
        }
        assertTrue("交叉模型下不该出现独立的 EXIT 阶段（EXIT 与 ENTER 重叠在同一窗口）", !sawExit)
        assertTrue("交叉模型下窗口内不得提前换图（旧图到 p=1 前都还可见）", !swappedInWindow)

        // 窗口结束 ⇒ HOLD + 已换图
        clock.run(50L)
        assertTrue("窗口结束后应进入 HOLD，实际 ${clock.phase}", clock.phase == PhotoTransitionClock.Phase.HOLD)
        assertTrue("窗口结束后必须已换图", clock.slotSwapped)
    }

    /**
     * 串行模型：前半段 EXIT（旧图退场）、后半段 ENTER（新图入场），交界处是暗场。
     *
     * ⚠️ **这是对 G3 ② 原文的口径更正**：原文说「`EXIT` 起点晚于 `ENTER` 终点」，
     * 那是按「一张照片的完整生命周期」描述的；但 §5.2 的时序图明确画出
     * `旧图 |--HOLD--|--EXIT--|` →（间隙）→ `新图 |--ENTER--|`，且 §14.3 写明
     * 「EXIT 时长 = 同一时段的 ENTER 时长」⇒ 两段共用**同一个转场窗口**。
     * ⇒ 可执行判据是「**两段不重叠，且 EXIT 在前**」。
     */
    @Test
    fun `sequential separates exit from enter with a black gap`() {
        val clock = PhotoTransitionClock()
        clock.begin(PhotoTransitionId.FADE_BLACK, enterMs = 1_000, holdMs = 8_000, sequential = true)

        // p = 0.3 ⇒ EXIT（旧图退场）
        clock.run(300L)
        assertTrue("p=0.3 应在 EXIT（实际 phase=${clock.phase}）", clock.phase == PhotoTransitionClock.Phase.EXIT)
        assertTrue("p=0.3 不该是暗场", !clock.inGap)
        assertTrue("p=0.3 还没到暗场点，不该换图", !clock.slotSwapped)

        // p = 0.5 ⇒ 暗场（两张图都不可见）
        clock.run(200L)
        assertTrue("p=0.5 应在暗场（实际 inGap=${clock.inGap}）", clock.inGap)
        assertTrue("暗场点之后旧图已不可见 ⇒ 可以换图（提前释放缓冲槽）", clock.slotSwapped)

        // p = 0.8 ⇒ ENTER（新图入场）
        clock.run(300L)
        assertTrue("p=0.8 应在 ENTER（实际 phase=${clock.phase}）", clock.phase == PhotoTransitionClock.Phase.ENTER)
        assertTrue("p=0.8 已过暗场", !clock.inGap)
    }

    /**
     * 暗场区间必须**真的存在且落在窗口内**，且暗场点必须与 `FadeBlackTransition` 的分界点一致。
     *
     * ⚠️ 这条不是「常量等于常量」的空转断言：`FadeBlackTransition` 在 `p < 0.5` 画旧图淡出、
     * `p >= 0.5` 画新图淡入 —— 若 `BLACK_POINT` 被改成别的值而不改转场实现，
     * 「黑场那一帧」与「两张图都不可见的区间」就会错位（画面会先亮起再黑）。
     */
    @Test
    fun `black point matches the fade black split`() {
        assertTrue(
            "BLACK_POINT 必须与 FadeBlackTransition 的分界点一致（0.5），实际 ${PhotoTransitionClock.BLACK_POINT}",
            PhotoTransitionClock.BLACK_POINT == 0.5f,
        )
        assertTrue("暗场半宽应为正数（否则「间隙」退化成一个瞬时点、看不出来）",
            PhotoTransitionClock.GAP_HALF_WIDTH > 0f)
        assertTrue(
            "暗场区间应完全落在窗口内：[${
                PhotoTransitionClock.BLACK_POINT - PhotoTransitionClock.GAP_HALF_WIDTH
            }, ${PhotoTransitionClock.BLACK_POINT + PhotoTransitionClock.GAP_HALF_WIDTH}]",
            PhotoTransitionClock.BLACK_POINT - PhotoTransitionClock.GAP_HALF_WIDTH > 0f &&
                PhotoTransitionClock.BLACK_POINT + PhotoTransitionClock.GAP_HALF_WIDTH < 1f,
        )
    }

    /** 暗场只在串行模型出现（交叉模型没有间隙） */
    @Test
    fun `gap only exists in the sequential model`() {
        val clock = PhotoTransitionClock()
        clock.begin(PhotoTransitionId.FADE_BLACK, enterMs = 1_000, holdMs = 8_000, sequential = false)
        repeat(20) {
            clock.run(50L)
            assertTrue("交叉模型不该有暗场（progress=${clock.progress}）", !clock.inGap)
        }
    }

    /**
     * **模型在 `start()` 那一刻定死**（约束 3）。
     *
     * 这里故意传入「模型与 id 自身声明**不一致**」的组合：
     * `CROSSFADE` 的 `requiresSequential == false`，但 `start(sequential = true)`。
     * 时钟必须**照 `start()` 的参数走**（出现 EXIT 段）—— 证明模型来自 `start()`、
     * 而不是在每帧重新读 id（那样切换进行中改设置就会让画面跳变）。
     */
    @Test
    fun `model comes from start and is not re-derived from the id`() {
        val clock = PhotoTransitionClock()
        assertTrue("前置条件：CROSSFADE 自身不是串行效果", !PhotoTransitionId.CROSSFADE.requiresSequential)

        clock.begin(PhotoTransitionId.CROSSFADE, enterMs = 1_000, holdMs = 8_000, sequential = true)
        clock.run(300L)
        assertTrue(
            "start(sequential = true) 后即便 id 是 CROSSFADE，也必须走 EXIT 段（实际 phase=${clock.phase}）",
            clock.phase == PhotoTransitionClock.Phase.EXIT,
        )
        assertTrue("sequential 标志应被 start 定死", clock.sequential)
    }

    // ────────────────────── ③ 硬切与 HOLD ──────────────────────

    /** §5.8 硬切：`enterMs = 0` ⇒ 直接进 HOLD，且已换图 */
    @Test
    fun `hard cut skips the transition window`() {
        val clock = PhotoTransitionClock()
        clock.start(0L, PhotoTransitionId.BEAT_CUT, enterMs = 0, holdMs = 500, sequential = false)
        assertTrue("硬切应直接进入 HOLD，实际 ${clock.phase}", clock.phase == PhotoTransitionClock.Phase.HOLD)
        assertTrue("硬切应已换图", clock.slotSwapped)
        assertTrue("硬切时进度应为 1", abs(clock.progress - 1f) < EPS)
        assertTrue("硬切时不该有暗场", !clock.inGap)
        assertTrue("硬切时 HOLD 进度从 0 开始", abs(clock.holdT) < EPS)
    }

    /** HOLD 内 `holdT` 从 0 走到 1，走完后 `finished = true`（控制器据此启动下一次切换） */
    @Test
    fun `hold progress runs from zero to one and then finishes`() {
        val clock = PhotoTransitionClock()
        clock.begin(PhotoTransitionId.CROSSFADE, enterMs = 1_000, holdMs = 2_000, sequential = false)

        clock.run(1_000L)
        assertTrue("窗口结束时应在 HOLD，实际 ${clock.phase}", clock.phase == PhotoTransitionClock.Phase.HOLD)
        assertTrue("HOLD 起点 holdT=${clock.holdT}", abs(clock.holdT) < EPS)
        assertTrue("HOLD 刚开始不该 finished", !clock.finished)

        clock.run(1_000L)
        assertTrue("HOLD 中点 holdT=${clock.holdT}", abs(clock.holdT - 0.5f) < EPS)
        assertTrue("HOLD 中点不该 finished", !clock.finished)

        clock.run(1_000L)
        assertTrue("HOLD 终点 holdT=${clock.holdT}", abs(clock.holdT - 1f) < EPS)
        assertTrue("HOLD 走完应 finished", clock.finished)
        assertTrue(
            "finished 后阶段仍是 HOLD（不能清成 IDLE，否则最后一帧丢转场信息）",
            clock.phase == PhotoTransitionClock.Phase.HOLD,
        )
        assertTrue("finished 后 transitionId 不该被清掉", clock.transitionId == PhotoTransitionId.CROSSFADE)
    }

    /** 硬切 + `holdMs = 0` ⇒ 一帧即完成（不会卡住，也不会出现负时长） */
    @Test
    fun `zero hold finishes immediately`() {
        val clock = PhotoTransitionClock()
        clock.start(0L, PhotoTransitionId.BEAT_CUT, enterMs = 0, holdMs = 0, sequential = false)
        assertTrue("enterMs=0 / holdMs=0 应立即 finished", clock.finished)
        assertTrue("holdT 应为 1", abs(clock.holdT - 1f) < EPS)
    }

    /** 负的时长参数按 0 处理（设置项被写坏时不至于让时钟算出负相位） */
    @Test
    fun `negative durations are coerced to zero`() {
        val clock = PhotoTransitionClock()
        clock.start(0L, PhotoTransitionId.CROSSFADE, enterMs = -100, holdMs = -50, sequential = false)
        assertTrue("enterMs 应被钳到 0，实际 ${clock.enterMs}", clock.enterMs == 0)
        assertTrue("holdMs 应被钳到 0，实际 ${clock.holdMs}", clock.holdMs == 0)
        assertTrue("应直接 finished", clock.finished)
    }

    // ────────────────────── ④ 缓动接线（§14.3「缓动」列）──────────────────────

    /**
     * `eased` 必须走**该效果自己的**缓动，而不是统一一个 —— 否则 §14.3 的「缓动」列白写了。
     *
     * 三个对照组覆盖三种类别：滑动（`easeOutCubic`）/ 溶解（`linear`）/ 淡化（`easeInOutQuad`）。
     */
    @Test
    fun `eased uses the effect specific easing`() {
        // 滑动类：easeOutCubic(0.25) = 1 - 0.75^3 = 0.578125
        val slide = PhotoTransitionClock()
        slide.begin(PhotoTransitionId.SLIDE_LEFT, enterMs = 1_000, holdMs = 1_000, sequential = false)
        slide.run(250L)
        assertTrue("progress 应为 0.25，实际 ${slide.progress}", abs(slide.progress - 0.25f) < EPS)
        assertTrue(
            "SLIDE_LEFT 的 eased 应为 easeOutCubic(0.25) = ${Easing.easeOutCubic(0.25f)}，实际 ${slide.eased}",
            abs(slide.eased - Easing.easeOutCubic(0.25f)) < EPS,
        )

        // 溶解类：linear ⇒ eased == progress
        val dissolve = PhotoTransitionClock()
        dissolve.begin(PhotoTransitionId.NOISE_DISSOLVE, enterMs = 1_000, holdMs = 1_000, sequential = false)
        dissolve.run(250L)
        assertTrue(
            "NOISE_DISSOLVE 是 linear ⇒ eased 应等于 progress，实际 ${dissolve.eased} vs ${dissolve.progress}",
            abs(dissolve.eased - dissolve.progress) < EPS,
        )

        // 淡化类：easeInOutQuad(0.25) = 2 × 0.0625 = 0.125
        val fade = PhotoTransitionClock()
        fade.begin(PhotoTransitionId.CROSSFADE, enterMs = 1_000, holdMs = 1_000, sequential = false)
        fade.run(250L)
        assertTrue(
            "CROSSFADE 的 eased 应为 easeInOutQuad(0.25) = ${Easing.easeInOutQuad(0.25f)}，实际 ${fade.eased}",
            abs(fade.eased - Easing.easeInOutQuad(0.25f)) < EPS,
        )
    }

    /**
     * **负向自证**：`eased` 与 `progress` **不是同一个东西** —— 否则「缓动接线」无从验证。
     *
     * 若有人把 `eased = progress` 写死（最省事的错法），上面那条断言会失败；
     * 这里直接量化两者的差：滑动类在 p=0.25 处差了约 0.328。
     */
    @Test
    fun `negative proof - eased is not the same as raw progress`() {
        val clock = PhotoTransitionClock()
        clock.begin(PhotoTransitionId.SLIDE_LEFT, enterMs = 1_000, holdMs = 1_000, sequential = false)
        clock.run(250L)
        val gap = abs(clock.eased - clock.progress)
        assertTrue(
            "eased 与 progress 的差值为 $gap —— 若为 0，说明缓动没接上（`eased = progress`）",
            gap > 0.2f,
        )
    }

    // ────────────────────── ⑤ 生命周期 ──────────────────────

    @Test
    fun `advance before start is ignored`() {
        val clock = PhotoTransitionClock()
        clock.advance(1_000L)
        assertTrue("未 start 时 phase 应为 IDLE，实际 ${clock.phase}", clock.phase == PhotoTransitionClock.Phase.IDLE)
        assertTrue("未 start 时 progress 应为 0", abs(clock.progress) < EPS)
        assertTrue("未 start 时 transitionId 应为 null", clock.transitionId == null)
        assertTrue("未 start 时不该 finished", !clock.finished)
    }

    @Test
    fun `reset returns to idle`() {
        val clock = PhotoTransitionClock()
        clock.begin(PhotoTransitionId.FADE_BLACK, enterMs = 800, holdMs = 500, sequential = true)
        clock.run(1_300L)
        assertTrue("前置条件：已 finished", clock.finished)

        clock.reset()
        assertTrue("reset 后 phase 应为 IDLE", clock.phase == PhotoTransitionClock.Phase.IDLE)
        assertTrue("reset 后 transitionId 应为 null", clock.transitionId == null)
        assertTrue("reset 后 progress 应为 0", abs(clock.progress) < EPS)
        assertTrue("reset 后 eased 应为 0", abs(clock.eased) < EPS)
        assertTrue("reset 后 holdT 应为 0", abs(clock.holdT) < EPS)
        assertTrue("reset 后不该 finished", !clock.finished)
        assertTrue("reset 后 sequential 应复位", !clock.sequential)
        assertTrue("reset 后 slotSwapped 应复位", !clock.slotSwapped)
        assertTrue("reset 后 inGap 应复位", !clock.inGap)
        assertTrue("reset 后 enterMs/holdMs 应复位", clock.enterMs == 0 && clock.holdMs == 0)
    }

    /**
     * `start()` 必须**重置相位**（新一次切换从 0 开始），且不把上一次的 `dt` 残留算进来。
     *
     * 若 `lastNowMs` 没重置：`start(100_000, …)` 后第一次 `advance(100_016)` 会把
     * `dt = 100_016 - <上一次的 lastNowMs>` 算进来 —— 新切换凭空多推进一截。
     */
    @Test
    fun `start resets the phase and the frame anchor`() {
        val clock = PhotoTransitionClock()
        clock.begin(PhotoTransitionId.CROSSFADE, enterMs = 1_000, holdMs = 1_000, sequential = false)
        clock.run(900L)

        // 新一次切换：时间锚点跳到 100_000
        now = 100_000L
        clock.begin(PhotoTransitionId.SLIDE_LEFT, enterMs = 1_000, holdMs = 1_000, sequential = false)
        assertTrue("start 后 progress 应归零，实际 ${clock.progress}", abs(clock.progress) < EPS)
        assertTrue("start 后不该 finished", !clock.finished)
        assertTrue("start 后 slotSwapped 应复位", !clock.slotSwapped)

        clock.run(16L)
        assertTrue(
            "start 后第一帧只该推进 16ms（progress≈0.016），实际 ${clock.progress} —— " +
                "偏大说明 start 没重置 lastNowMs",
            abs(clock.progress - 0.016f) < 0.002f,
        )
    }

    private companion object {
        const val EPS = 1e-4f
    }
}

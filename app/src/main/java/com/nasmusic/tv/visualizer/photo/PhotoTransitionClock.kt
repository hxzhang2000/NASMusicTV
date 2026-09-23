package com.nasmusic.tv.visualizer.photo

/**
 * 切换时序状态机（§5.1 / §5.2 / §5.3）
 *
 * ```
 * [IDLE] ──start()──▶ [转场窗口] ──窗口走完──▶ [HOLD] ──holdMs 走完──▶ finished = true
 *                        │
 *                        └─ 交叉模型：全程 phase = ENTER（EXIT 与之重叠）
 *                           串行模型：p < 0.5 → EXIT，p ≥ 0.5 → ENTER（中间是暗场）
 * ```
 *
 * ## ⛔ 三条必须写对的实现约束（全部来自真实事故，见项目记忆 E37 / §10.176）
 *
 * | # | 约束 | 写错的后果 |
 * |---|---|---|
 * | 1 | **相位一律 `+= dt` 累加，绝不用 `nowMs × 系数`** | `ctx.nowMs` 是 `System.currentTimeMillis()`（1.7e12 量级）或 `frame.timeMs`（单调毫秒），乘上任何实时系数会把每帧抖动放大千万倍 → 画面闪现跳档 |
 * | 2 | **`dt` 必须钳上限**（[MAX_DT_MS] = 100） | 切后台再回来，一帧 `dt` 可能是几十秒 → 相位瞬间跳到结束 |
 * | 3 | **模型（交叉 / 串行）必须在 [start] 那一刻定死并贯穿本次切换** | 切换进行中改模型 → 正在跑的两张图从交叉切成串行，画面跳变（§5.9） |
 *
 * 约束 1 与 2 的落地方式是同一个：[advance] **只接受绝对时间戳**，内部算差、钳制、再累加。
 * 外部拿不到「相位」的写入口 ⇒ 无法绕过。
 *
 * ## 用哪个时钟
 *
 * 用 `frame.timeMs`（`AudioFrame` 的单调时钟，契约即「动画相位用」）。
 * **不要用 `ctx.nowMs`** —— 它在 `VisualizerStage` 三个调用点语义不一致
 * （135 行传 `System.currentTimeMillis()`，248/283 行传 `f.timeMs`）。
 *
 * ## 与「一次切换内的阶段」的命名冲突
 *
 * 本类的 [Phase] 是**一次切换内的阶段**；`PhotoTransitionId.Phase` 是**交付分期**（P0/P1/P2）。
 * 两者刻意同名（各自贴合自己的语境），⚠️ 同时 import 时务必写全限定名。
 *
 * @param maxDtMs 单帧推进上限（ms）。默认 [MAX_DT_MS]；
 *   ⚠️ 暴露它是为了**负向自证**：传一个很大的值即可模拟「去掉钳制」，
 *   断言相位会一帧跳到结束（证明钳制那一行不是冗余代码）
 */
class PhotoTransitionClock(private val maxDtMs: Long = MAX_DT_MS) {

    /** 一次切换内的阶段 */
    enum class Phase { IDLE, ENTER, HOLD, EXIT }

    /** 当前阶段。⚠️ 交叉模型下**恒为 [Phase.ENTER]** —— EXIT 与它重叠，没有独立阶段 */
    var phase: Phase = Phase.IDLE
        private set

    /** 本次切换的转场；[reset] 后为 `null` */
    var transitionId: PhotoTransitionId? = null
        private set

    /** 转场窗口内的**原始**进度 `0..1` */
    var progress: Float = 0f
        private set

    /** 缓动后的进度 `0..1` —— 交给 `PhotoTransition.render` 的就是它 */
    var eased: Float = 0f
        private set

    /** HOLD 内 `0..1`（Ken Burns 用）；转场窗口内恒为 0 */
    var holdT: Float = 0f
        private set

    /** 本次切换是否已把「新图」提升为「当前图」 */
    var slotSwapped: Boolean = false
        private set

    /** 串行模型下处于「暗场」（两张图都不可见） */
    var inGap: Boolean = false
        private set

    /** 本次切换的流程模型（[start] 时定死，**中途不可改**） */
    var sequential: Boolean = false
        private set

    /**
     * HOLD 已走完 —— 控制器据此启动下一次切换。
     *
     * ⚠️ **不要用 `phase == IDLE` 当结束信号**：那样 HOLD 结束的那一帧会把
     * `transitionId` / `progress` 一起清掉，渲染器读到 `photoTransition == null` 会闪一下。
     * [Phase.IDLE] 只出现在「尚未 [start] 或已 [reset]」的状态。
     */
    var finished: Boolean = false
        private set

    /** 转场窗口总时长（ms）—— 等于 `enterMs`，EXIT 与它同时段（§5.3） */
    var enterMs: Int = 0
        private set

    /** 停留时长（ms）—— 用户设置项 `photoWallHoldMs` */
    var holdMs: Int = 0
        private set

    /** 累计相位（ms）。**只通过 [advance] 的 `+= dt` 增长**（约束 1） */
    private var elapsedMs: Long = 0L

    /** 上一帧时间戳；[start] 时重置，避免把「上一次切换的残留 dt」算进来 */
    private var lastNowMs: Long = 0L

    private var started: Boolean = false

    /**
     * 开始一次切换。
     *
     * ⛔ **模型在这里定死**（约束 3）—— 之后改设置（含 `requiresSequential` 变化）
     * **不会**影响正在跑的这次切换，只影响下一次 [start]。
     *
     * ⚠️ **不要在窗口进行中重复调用**：那会把 `elapsedMs` 归零 = 从 0 重启，
     * 画面跳变（§5.8「转场打断」）。控制器应把「手动切图」请求排队到本次窗口结束。
     *
     * @param nowMs 当前时间戳（`frame.timeMs`）
     * @param id 本次切换的转场
     * @param enterMs 转场窗口时长；`0` = 硬切（§5.8 `BEAT_CUT`）
     * @param holdMs 停留时长；`0` = 不停留
     * @param sequential 是否走串行模型（由 `PhotoTransitionId.requiresSequential` 决定）
     */
    fun start(
        nowMs: Long,
        id: PhotoTransitionId,
        enterMs: Int,
        holdMs: Int,
        sequential: Boolean,
    ) {
        transitionId = id
        this.enterMs = enterMs.coerceAtLeast(0)
        this.holdMs = holdMs.coerceAtLeast(0)
        this.sequential = sequential
        elapsedMs = 0L
        lastNowMs = nowMs
        started = true
        slotSwapped = false
        finished = false
        recompute()
    }

    /**
     * 推进一帧。**只吃绝对时间戳**（约束 1 / 2 的落地方式）。
     *
     * `dt` 为负（时钟回拨）按 0 处理；超过 [maxDtMs] 按 [maxDtMs] 处理。
     */
    fun advance(nowMs: Long) {
        if (!started) return
        val dt = (nowMs - lastNowMs).coerceIn(0L, maxDtMs)
        lastNowMs = nowMs
        elapsedMs += dt
        recompute()
    }

    /** 回到未启动状态（退出照片墙 / 切走主题时调用） */
    fun reset() {
        phase = Phase.IDLE
        transitionId = null
        progress = 0f
        eased = 0f
        holdT = 0f
        slotSwapped = false
        inGap = false
        sequential = false
        finished = false
        enterMs = 0
        holdMs = 0
        elapsedMs = 0L
        lastNowMs = 0L
        started = false
    }

    private fun recompute() {
        val enter = enterMs.toLong()

        if (elapsedMs < enter) {
            // ── 转场窗口 ──
            progress = (elapsedMs.toFloat() / enter).coerceIn(0f, 1f)
            holdT = 0f
            finished = false
            if (sequential) {
                // 串行：前半段旧图退场（EXIT），后半段新图入场（ENTER），
                // 交界处是「暗场」（§5.2 的「间隙：黑场/白场」）
                phase = if (progress < BLACK_POINT) Phase.EXIT else Phase.ENTER
                inGap = progress >= BLACK_POINT - GAP_HALF_WIDTH && progress < BLACK_POINT + GAP_HALF_WIDTH
                // 暗场点之后旧图已完全不可见 ⇒ 可以提前释放它的缓冲槽（给下一张预取用）
                slotSwapped = progress >= BLACK_POINT
            } else {
                // 交叉：EXIT 与 ENTER 重叠在同一个窗口，没有独立阶段
                phase = Phase.ENTER
                inGap = false
                // 旧图到窗口结束前都还部分可见 ⇒ 必须等到 p = 1 才能换
                slotSwapped = false
            }
        } else {
            // ── HOLD ──
            val hold = holdMs.toLong()
            progress = 1f
            phase = Phase.HOLD
            inGap = false
            slotSwapped = true
            holdT = if (hold <= 0L) 1f else ((elapsedMs - enter).toFloat() / hold).coerceIn(0f, 1f)
            finished = elapsedMs >= enter + hold
        }

        eased = transitionId?.easing?.invoke(progress) ?: progress
    }

    companion object {

        /**
         * 单帧推进上限（ms）。
         *
         * 60fps 的帧间隔约 16.7ms ⇒ 100ms 已经是「严重掉帧」的量级。
         * 取这个值的意义不是「精确」，而是**给相位推进一个硬上限**：
         * 切后台 30 秒回来，第一帧 `dt` 是 30000ms，钳到 100ms 后画面只是「慢了一帧」，
         * 而不是直接跳到本次切换的终点。
         */
        const val MAX_DT_MS = 100L

        /**
         * 串行模型的暗场点（进度）。
         *
         * `0.5` = 前一半退场、后一半入场 —— 与 `FadeBlackTransition` 的分界点一致
         * （它在 `p < 0.5` 画旧图淡出、`p >= 0.5` 画新图淡入）。
         * ⚠️ 改这里必须同步改 `FadeTransitions.kt`，否则「黑场那一帧」与「两张图都不可见的区间」错位。
         */
        const val BLACK_POINT = 0.5f

        /**
         * 暗场半宽（进度）。
         *
         * 串行模型的「间隙」**不是一个独立时长**（§5.3：EXIT 与 ENTER 共用同一个窗口，
         * 没有第三个时长参数），而是暗场点两侧的一小段：`[0.46, 0.54]`。
         * 按 800ms 的窗口算约 64ms（≈4 帧），肉眼是一个「顿」。
         */
        const val GAP_HALF_WIDTH = 0.04f
    }
}

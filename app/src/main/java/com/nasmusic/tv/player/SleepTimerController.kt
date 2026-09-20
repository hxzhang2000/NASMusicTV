package com.nasmusic.tv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 睡眠定时器（F2-2）：到点自动暂停播放。
 *
 * 设计（docs/archive/feature-dev-plan-2026-09.md §2.3）：
 * - 不持久化：电视场景"定时 = 今晚"，重启即重置（设计取舍）
 * - 预设档位 15/30/60/90 分钟
 * - 到期回调 [onExpired]，由 PlayerManager 触发 pause() + 通知刷新
 * - remaining() 供通知栏显示剩余分钟
 *
 * P1#4（2026-09-14）：到期调度由 `Handler.postDelayed` 改为**协程 `delay`**。
 * 原实现自持一个 `Handler`，并用 `AtomicLong` 令牌防御 cancel 与到期的竞争；
 * 协程化后取消语义由 [Job] 直接承载（`cancel()` 即取消在途 delay），令牌守卫不再必要，
 * 与本项目"协程优先"的调度风格统一，且**单测可用虚拟时间验证真实到期路径**
 * （原 `Handler` 版本在 Robolectric 下不驱动 Looper，只能靠公开的 [tickExpired]
 * 手工驱动，测不到调度本身）。
 *
 * 时间源与调度器均可注入（[scope] / [nowMsProvider]），单测无需真实等待。
 * [scope] 默认主线程 immediate 调度器，与原先 `Handler(Looper.getMainLooper())` 等价；
 * 其生命周期与 [PlayerManager]（app 级单例）一致。
 */
class SleepTimerController(
    private val onExpired: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
    private val nowMsProvider: () -> Long = System::currentTimeMillis
) {
    sealed interface State {
        data object Off : State
        data class Running(val endsAtMs: Long, val totalMinutes: Int) : State
        data object Finished : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    /** 在途的到期调度（P1#4：以 Job 承载取消语义，替代原 Handler + AtomicLong 令牌） */
    private var expiryJob: Job? = null

    /** 剩余毫秒（未运行返回 0） */
    fun remaining(): Long = when (val s = _state.value) {
        is State.Running -> (s.endsAtMs - nowMsProvider()).coerceAtLeast(0)
        else -> 0
    }

    /** 剩余分钟（向上取整，供通知显示） */
    fun remainingMinutes(): Int = ((remaining() + 59_999) / 60_000).toInt()

    /** 启动定时；minutes <= 0 视为取消 */
    fun start(minutes: Int) {
        cancel()
        if (minutes <= 0) return
        val endsAt = nowMsProvider() + minutes * 60_000L
        _state.value = State.Running(endsAt, minutes)
        expiryJob = scope.launch {
            delay(minutes * 60_000L)
            tickExpired()
        }
    }

    /**
     * 到期处理（单测公开入口）：仅 Running 态触发一次回调，转 Finished。
     * 非 Running 态调用为 no-op（幂等，不重复触发）。
     */
    fun tickExpired() {
        if (_state.value is State.Running) {
            _state.value = State.Finished
            onExpired()
        }
    }

    /** 取消定时（含 Finished 状态复位） */
    fun cancel() {
        expiryJob?.cancel()
        expiryJob = null
        _state.value = State.Off
    }

    /** 是否处于计时中 */
    fun isRunning(): Boolean = _state.value is State.Running
}

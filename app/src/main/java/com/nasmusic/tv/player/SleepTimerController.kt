package com.nasmusic.tv.player

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 睡眠定时器（F2-2）：到点自动暂停播放。
 *
 * 设计（docs/feature-dev-plan-2026-09.md §2.3）：
 * - 不持久化：电视场景"定时 = 今晚"，重启即重置（设计取舍）
 * - 预设档位 15/30/60/90 分钟
 * - 到期回调 [onExpired]，由 PlayerManager 触发 pause() + 通知刷新
 * - remaining() 供通知栏显示剩余分钟
 *
 * 时间源可注入（nowMsProvider），单测无需真实等待。
 */
class SleepTimerController(
    private val onExpired: () -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val nowMsProvider: () -> Long = System::currentTimeMillis
) {
    sealed interface State {
        data object Off : State
        data class Running(val endsAtMs: Long, val totalMinutes: Int) : State
        data object Finished : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

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
        val token = AtomicLong(endsAt)
        handler.postDelayed({
            // 到期校验：cancel 会移除本 runnable，这里防御状态被并发改动
            if (token.get() == endsAt) {
                tickExpired()
            }
        }, minutes * 60_000L)
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
        handler.removeCallbacksAndMessages(null)
        _state.value = State.Off
    }

    /** 是否处于计时中 */
    fun isRunning(): Boolean = _state.value is State.Running
}

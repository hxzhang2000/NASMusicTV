package com.nasmusic.tv.util

import com.nasmusic.tv.BuildConfig

/**
 * 应用日志工具
 *
 * 仅在 Debug 构建中输出日志，Release 构建中所有调用均为空操作。
 * 避免在 Release 包中泄露调试信息或产生不必要的 I/O 开销。
 */
object AppLog {
    /** Debug 级别日志，仅在 BuildConfig.DEBUG 为 true 时输出 */
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(tag, message)
        }
    }

    /** Info 级别日志，仅在 BuildConfig.DEBUG 为 true 时输出 */
    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.i(tag, message)
        }
    }

    /** Warning 级别日志，仅在 BuildConfig.DEBUG 为 true 时输出 */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            android.util.Log.w(tag, message, throwable)
        }
    }

    /** Error 级别日志，始终输出（错误信息在生产环境也需要可见） */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        android.util.Log.e(tag, message, throwable)
    }
}

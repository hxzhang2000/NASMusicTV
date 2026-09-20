// 独立 JVM 验证用 AppLog 桩（仅存在于 logs_temp，不进入仓库构建）
// 真实 AppLog 依赖 BuildConfig + android.util.Log，此类在 harness 中替代之。
package com.nasmusic.tv.util

object AppLog {
    fun d(tag: String, message: String) {}
    fun i(tag: String, message: String) {}
    fun w(tag: String, message: String, throwable: Throwable? = null) {}
    fun e(tag: String, message: String, throwable: Throwable? = null) {}
}

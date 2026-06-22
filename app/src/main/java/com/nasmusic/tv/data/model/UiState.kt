package com.nasmusic.tv.data.model

/**
 * B-12: 统一异步数据加载状态
 *
 * 替代以前 "空列表表示未加载 / 加载中 / 已加载 / 加载失败" 混为一体的做法。
 * 每个异步数据源用一个 [UiState] 表述其完整生命周期。
 *
 * [Loading]      — 首次加载或刷新中
 * [Success]      — 加载成功，携带数据
 * [Error]        — 加载失败，携带错误信息和重试闭包
 */
sealed class UiState<out T> {

    /** 加载中 */
    data object Loading : UiState<Nothing>()

    /** 加载成功 */
    data class Success<T>(val data: T) : UiState<T>()

    /** 加载失败 */
    data class Error(
        val message: String,
        val retry: (() -> Unit)? = null
    ) : UiState<Nothing>()

    /** 便捷方法：获取数据（仅 Success 有值） */
    fun dataOrNull(): T? = when (this) {
        is Loading -> null
        is Success -> data
        is Error -> null
    }

    /** 是否为成功状态 */
    val isSuccess: Boolean get() = this is Success

    /** 是否为加载中 */
    val isLoading: Boolean get() = this is Loading

    /** 是否为错误 */
    val isError: Boolean get() = this is Error
}

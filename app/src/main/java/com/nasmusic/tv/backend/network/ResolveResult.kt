package com.nasmusic.tv.backend.network

/**
 * 网络音乐直链解析结果（多码率方案 §3.2）。
 *
 * 承载**降级信号**：`actualQuality != requestedQuality` 时表示发生了静默降级，
 * 播放侧据此提示用户，下载侧据此决定文件名后缀与落库档位。
 *
 * @param url 可直接播放/下载的直链；null 表示所有档位与端点均失败（无源可降）
 * @param actualQuality 实际命中的档位值（0/128/192/320/999）。
 *        解析失败时等于请求档位；AUTO 档请求时恒为 [QualityTiers.AUTO]，
 *        因此 AUTO 档永远不会被判定为"降级"。
 */
data class ResolveResult(
    val url: String?,
    val actualQuality: Int
) {
    /** 是否解析成功 */
    val isSuccess: Boolean get() = !url.isNullOrBlank()

    /**
     * 相对请求档位是否发生降级。
     *
     * AUTO 档恒返回 false —— "端点默认给什么"不构成降级语义。
     */
    fun isDowngradedFrom(requested: Int): Boolean =
        isSuccess && requested != QualityTiers.AUTO && actualQuality != requested

    companion object {
        /** 解析失败（无源可降） */
        fun failure(requested: Int): ResolveResult = ResolveResult(null, requested)
    }
}

/**
 * 音质档位的生效范围（两级模型，方案 §2.3）。
 */
enum class QualityScope {
    /** 全部歌曲：写全局默认档位 */
    ALL,

    /** 仅本次播放：写单曲覆盖，不污染全局默认 */
    THIS_SONG
}

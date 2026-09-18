package com.nasmusic.tv.backend.download

import com.nasmusic.tv.backend.network.ResolveResult

/**
 * 网络歌曲的播放源决策（多码率方案 §3.6）。
 *
 * **为什么抽成独立对象**：这段逻辑此前内联在 `PlayerViewModel.resolveNetworkStreamUrl()` 里，
 * 而 `PlayerViewModel` 是 `AndroidViewModel`、依赖 `NasMusicApp` 全局单例，
 * 单测无法构造。抽出来后可用 lambda 注入假实现，覆盖 §3.6 的全部 6 条断言。
 *
 * 背景（修 G7）：改造前是 `playableLocalUri(song) ?: resolvePlayUrl(...)` ——
 * **只要该曲下载过（任意档位）就永远播本地文件**，导致"切到无损/128"对已下载歌曲完全失效。
 * 现在按**有效档位**查本地，规则如下。
 */
object NetworkPlaybackResolver {

    /**
     * 决策结果。
     *
     * @param url 最终要交给 ExoPlayer 的地址（本地 file:// 或在线直链）
     * @param fromLocal 是否走了本地文件（用于日志/诊断）
     * @param actualQuality 实际档位（在线解析时来自 [ResolveResult.actualQuality]；
     *        本地命中时等于命中的那个档位）
     */
    data class Decision(
        val url: String,
        val fromLocal: Boolean,
        val actualQuality: Int
    )

    /**
     * 按 §3.6 规则决策播放源。
     *
     * 优先级：
     * 1. **该档已下载** → 播本地（离线优先，不发网络请求）
     * 2. 该档未下载 → 走网络解析
     * 3. 解析**降级**且降级后的档恰好已下载 → 回退本地（省流量，内容一致）
     * 4. 解析**彻底失败** → 任意已下载档兜底（保证"能播就行"）
     *
     * @param effectiveQuality 有效档位（单曲覆盖 ?: 全局默认），由调用方解析
     * @param localUriFor 查询"该档是否已有本地文件"；无则 null
     * @param localUriAny 查询"任意档的本地文件"；无则 null
     * @param resolveOnline 在线解析（带降级信号）
     */
    suspend fun decide(
        effectiveQuality: Int,
        localUriFor: suspend (Int) -> String?,
        localUriAny: suspend () -> String?,
        resolveOnline: suspend (Int) -> ResolveResult
    ): Decision? {
        // 1. 该档已下载 → 播本地
        localUriFor(effectiveQuality)?.let {
            return Decision(it, fromLocal = true, actualQuality = effectiveQuality)
        }
        // 2. 走网络解析
        val result = resolveOnline(effectiveQuality)
        if (result.url == null) {
            // 4. 解析彻底失败 → 任意已下载档兜底
            val fallback = localUriAny()
                ?: return null   // 无源可播
            return Decision(fallback, fromLocal = true, actualQuality = effectiveQuality)
        }
        // 3. 降级且降级后的档已下载 → 优先本地
        if (result.isDowngradedFrom(effectiveQuality)) {
            localUriFor(result.actualQuality)?.let {
                return Decision(it, fromLocal = true, actualQuality = result.actualQuality)
            }
        }
        return Decision(result.url!!, fromLocal = false, actualQuality = result.actualQuality)
    }
}

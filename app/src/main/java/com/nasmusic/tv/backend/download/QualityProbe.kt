package com.nasmusic.tv.backend.download

import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 可用码率探测（多码率方案 §5.2.1）。
 *
 * **为什么必须探测而不能查表**：Meting / GD 音乐台 API 只有
 * `search` / `url` / `lyric` / `pic` 四个端点，**不存在"列出该曲可用码率"的接口**。
 * 可用档位只能靠"逐档请求 URL 解析接口、看是否返回非空"探测得出。
 *
 * 探测结果**只决定 UI 形态**（多档→弹窗 / 单档→直下 / 零档→报错），
 * 不决定下载正确性 —— 真实下载仍走完整降级链，因此探测超时最多导致
 * "弹窗少列一档"，不会导致下载失败。
 */
object QualityProbe {

    private const val TAG = "QualityProbe"

    /**
     * 单档探测超时（毫秒）。
     *
     * 注意：这是对**单档整个 fallback 链**（多端点）封顶，不是对单次 HTTP 请求封顶。
     * 弱网 + 多端点时可能因此把某档误判为不可用 —— 属可接受的降级
     * （用户少一个可选档，但下载本身不受影响）。
     */
    const val PROBE_TIMEOUT_MS = 1200L

    /**
     * 探测该曲实际可获取的码率档位。
     *
     * 并发探测整条链（排除 AUTO），每档独立短超时；
     * 只请求 URL 解析接口，不下载音频数据，因此开销极小（总耗时 ≈ 单档耗时）。
     *
     * @param resolver 直链解析器（内部复用 `resolveDetailed`，保证与下载路径同一实现）
     * @param tiers 待探测档位，默认全部真实码率档（降序）
     * @param dispatcher 调度器；默认 IO。**可注入**是为了让单测用虚拟时间断言并发性
     *        （硬编码 IO 会让 `runTest` 的虚拟时间失效）
     * @return 可用档位列表，按码率从高到低排序；空列表表示无源可降
     */
    suspend fun probeAvailableQualities(
        song: Song,
        resolver: StreamUrlResolver,
        tiers: List<Int> = QualityTiers.availableTiers,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): List<Int> = withContext(dispatcher) {
        // ⚠️ 必须用 async/awaitAll 并发：直接 map 里调 suspend 是**串行**的，
        //    总耗时会变成 4 × 单档（最坏 4.8s），与"总耗时 ≈1.2s"的设计目标不符。
        //    单测 QualityProbeTest 用虚拟时间锁定该性质（串行实现会失败）。
        tiers.map { tier ->
            async {
                val ok = runCatching {
                    withTimeout(PROBE_TIMEOUT_MS) {
                        resolver.resolveDetailed(song, tier, dispatcher).isSuccess
                    }
                }.getOrElse { e ->
                    AppLog.d(TAG, "probe tier=$tier timeout/failed: ${e.message}")
                    false
                }
                tier to ok
            }
        }.awaitAll()
            .filter { it.second }
            .map { it.first }   // 输入已降序，输出保持降序
    }
}

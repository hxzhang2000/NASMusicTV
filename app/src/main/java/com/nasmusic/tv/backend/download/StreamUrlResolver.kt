package com.nasmusic.tv.backend.download

import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.backend.network.ResolveResult
import com.nasmusic.tv.backend.network.baidu.BaiduStreamFactory
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 多源直链解析 + 可下载性判定（见方案 §5.4）
 *
 * - 网络歌曲：[NetworkMusicManager.resolvePlayUrl]（5 分钟内存缓存）
 * - 百度源：[BaiduStreamFactory]（必须带 UA 拦截器，否则 403/限速）
 * - NAS 歌曲：[BackendAdapter.getStreamUrl]（同步 API，包一层 IO）
 * - 不可下载：本地歌曲、天气电台
 */
class StreamUrlResolver(
    private val adapter: () -> BackendAdapter?,
    private val network: suspend (Song) -> String?,
    private val baidu: suspend (Song) -> String? = { null },
    /**
     * v2.35.0 多码率：带降级信号的网络解析。
     *
     * 可空且默认 null，保持既有单测的构造兼容（不传则 [resolveDetailed]
     * 退化为 `ResolveResult(network(song), quality)`，无降级信息）。
     */
    private val networkDetailed: (suspend (Song, Int) -> ResolveResult)? = null
) {
    companion object {
        private const val TAG = "StreamUrlResolver"
        /** 天气电台的 networkSource 标识（与 RadioStation.SOURCE_ID 一致，但避免循环依赖） */
        private const val RADIO_SOURCE = "weather"
        /** 百度源 networkSource 标识 */
        private const val BAIDU_SOURCE = "baidu"
    }

    suspend fun resolve(song: Song): String? = when {
        song.isLocalSong -> song.streamUrl ?: song.path  // 本地歌曲直接返回文件路径
        song.networkSource == RADIO_SOURCE -> null
        song.networkSource == BAIDU_SOURCE -> withContext(Dispatchers.IO) {
            runCatching { baidu(song) }.getOrNull()
        }
        song.isNetworkSong -> withContext(Dispatchers.IO) {
            runCatching { network(song) }.getOrNull()
        }
        else -> withContext(Dispatchers.IO) {
            runCatching { adapter()?.getStreamUrl(song.id) }
                .onFailure { AppLog.w(TAG, "NAS resolve failed: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * 解析直链（**带档位与降级信号**，下载主路径使用）。
     *
     * 网络歌曲走 [networkDetailed]（内部是 MetingApiService 的完整降级链）；
     * 非网络歌曲、或未注入 detailed lambda 时，退化为无降级信息的解析。
     *
     * @param quality 请求档位；返回的 [ResolveResult.actualQuality] 为**实际命中**档位
     */
    suspend fun resolveDetailed(song: Song, quality: Int): ResolveResult = when {
        !song.isNetworkSong || song.networkSource == RADIO_SOURCE -> ResolveResult(resolve(song), quality)
        networkDetailed == null -> ResolveResult(resolve(song), quality)
        else -> withContext(Dispatchers.IO) {
            runCatching { networkDetailed.invoke(song, quality) }
                .onFailure { AppLog.w(TAG, "resolveDetailed failed: ${it.message}") }
                .getOrElse { ResolveResult.failure(quality) }
        }
    }

    /** 电台等纯流媒体不可下载；本地歌曲无需下载 */
    fun isDownloadable(song: Song): Boolean =
        !song.isLocalSong && song.networkSource != RADIO_SOURCE

    /** 推导歌曲的源类型字符串（用于 [DownloadSongEntity.sourceType]） */
    fun sourceTypeOf(song: Song): String = when {
        song.isLocalSong -> "LOCAL"
        song.networkSource == RADIO_SOURCE -> "WEATHER_RADIO"
        song.networkSource == BAIDU_SOURCE -> "BAIDU_PAN"
        song.networkSource == "jamendo" -> "JAMENDO"
        song.isNetworkSong -> "NETWORK_MUSIC"
        else -> "NAS"
    }
}

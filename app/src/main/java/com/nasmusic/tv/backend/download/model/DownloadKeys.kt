package com.nasmusic.tv.backend.download.model

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.RadioStation
import com.nasmusic.tv.backend.network.QualityTiers

/**
 * 歌曲下载相关扩展属性（不改 Song 数据类本身）。
 *
 * 设计要点：
 * - [downloadKey]：稳定主键，跨源不冲突（local_/nas_/ntwk_ 前缀分流）。
 * - [dedupeKey]：跨源去重键（标题+艺术家规范化），用于"同曲跨平台不重复下载"。
 */

/**
 * 下载主键。
 *
 * - 本地歌曲：`local_$id`（无需下载，仅作幂等键）
 * - 网络歌曲：`ntwk_${networkSource}_${networkId ?: id}` —— 即使同 id 跨源也不冲突
 * - NAS 歌曲：`nas_$id`
 */
val Song.downloadKey: String
    get() = when {
        isLocalSong -> "local_$id"
        isNetworkSong -> "ntwk_${networkSource ?: "unknown"}_${networkId ?: id}"
        else -> "nas_$id"
    }

/**
 * 带音质档位的下载主键（多码率方案 §4.2.2）。
 *
 * 规则：
 * - [QualityTiers.AUTO]（0）→ **返回与 [downloadKey] 完全相同的字符串**。
 *   这样存量行（升级前写入、无 `:q` 后缀）天然等于新格式，无需回填即可被命中，
 *   也不会导致升级后"已下载歌曲被重复下载"。
 * - 具体档位（128/192/320/999）且源支持多码率（Meting）→ 追加 `:q<quality>`，
 *   实现同曲多档共存。
 * - 本地 / NAS / 非 Meting 网络源 → 忽略档位，返回 [downloadKey]。
 *
 * ⚠️ 下载链路的 key **一律经本函数生成**，禁止调用方自行拼字符串。
 */
fun Song.downloadKeyOf(quality: Int): String {
    if (quality <= QualityTiers.AUTO) return downloadKey
    if (isLocalSong || !isNetworkSong) return downloadKey
    // 仅 Meting 源支持多码率（见方案 §2.5）
    if (networkSource != METING_SOURCE) return downloadKey
    return "$downloadKey:q$quality"
}

/** Meting 源标识（与 MetingApiService.SOURCE_ID 一致，此处避免循环依赖） */
private const val METING_SOURCE = "meting"

/**
 * 在下载状态表中查询某曲的 UI 状态（多码率方案 §4.2.2 配套）。
 *
 * **为什么不能直接 `downloadStates[song.downloadKey]`**：v2.35.0 起下载主键
 * 对 Meting 源的具体档位带 `:q<quality>` 后缀（如 `ntwk_meting_1:q320`），
 * 而 UI 只知道"这首歌"，不知道用户当时选的档位。直接按无后缀 key 查会
 * **永远 miss**，导致已下载歌曲显示为未下载（✓ 变 ⬇）。
 *
 * 匹配优先级：
 * 1. AUTO 档的精确 key（= 旧格式，覆盖存量行与"自动"档下载）
 * 2. 该曲任意档位的状态；多档并存时按"下载中 > 已入队 > 已完成 > 失败"优先，
 *    保证正在进行的任务有可见反馈
 *
 * @param states SongDownloadManager 维护的状态表（key 为 songKey）
 */
fun Map<String, DownloadState>.stateOfSong(song: Song): DownloadState {
    // 1. AUTO / 存量格式精确命中（最常见）
    this[song.downloadKey]?.let { return it }
    if (!song.isNetworkSong || song.networkSource != METING_SOURCE) return DownloadState.None
    // 2. 前缀匹配该曲的所有档位（`ntwk_meting_<id>:q*`）
    val prefix = "${song.downloadKey}:q"
    val candidates = this.filterKeys { it.startsWith(prefix) }.values
    if (candidates.isEmpty()) return DownloadState.None
    // 3. 优先级：进行中 > 排队 > 完成 > 失败
    return candidates.firstOrNull { it is DownloadState.Downloading }
        ?: candidates.firstOrNull { it is DownloadState.Queued }
        ?: candidates.firstOrNull { it is DownloadState.Completed }
        ?: candidates.first()
}

/**
 * 跨源去重键：标题+艺术家规范化（小写 + 去除内部空白）。
 *
 * 用于"同一首歌不同平台 sourceId 不同"时避免重复下载。
 * 命中后即使 [downloadKey] 不同也视为已下载（UI 显示 ✓，不再发起下载）。
 */
val Song.dedupeKey: String
    get() = listOf(title, artist)
        .joinToString("|") { it.trim().lowercase().replace(Regex("\\s+"), "") }

/** RadioStation 源标识（与 RadioStation.SOURCE_ID 一致，但避免循环依赖） */
private const val RADIO_SOURCE = "weather"

/**
 * 是否可下载。
 *
 * 不可下载的源：
 * - 本地歌曲（已经在本地，无需下载）
 * - 天气电台（纯流媒体，无歌曲文件）
 */
fun isDownloadableSong(song: Song): Boolean =
    !song.isLocalSong && song.networkSource != RADIO_SOURCE

/** 下载运行时配置（来自 AppSettings） */
data class DownloadSettings(
    val downloadEnabled: Boolean,
    val autoDownloadOnPlay: Boolean,
    val autoDownloadLimit: Int,
    val downloadLocation: String        // 当前仅 "INTERNAL"，CUSTOM 为 P1 预留
)

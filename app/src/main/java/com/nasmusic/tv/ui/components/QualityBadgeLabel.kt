package com.nasmusic.tv.ui.components

import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.data.model.Song

/**
 * 音质徽标文案计算（多码率方案 §3.5 / §5.1）。
 *
 * 两种形态：
 * - **网络歌曲**：显示当前档位标签（如「无损」「320k」），可点击切换档位
 * - **本地 / NAS / 下载歌曲**：显示**真实码率**（如「♪ 320 kbps」/「♪ 无损 1.4M」），
 *   只读不可点击 —— 这类歌曲码率由文件本身决定，无法切换，但用户理应看得到
 *
 * 数据来源：
 * - `Song.bitrate`：NAS 适配器（Jellyfin `BitRate/1000`、Navidrome/Subsonic `bitRate`）
 *   在解析时填充；飞牛为 0（`BITRATE_UNVERIFIED`，单位未确认故不填）
 * - `Song.resolvedQuality`：已下载歌曲的实际落盘档位（网络歌曲解析后回填）
 *
 * @param networkQualityLabel 网络歌曲的档位标签（由调用方经 `stringResource` 解析，
 *        保持本函数为纯函数、可单测）
 * @return null 表示无可用码率信息，**不渲染徽标**（而非显示 "0 kbps" 这类错误数据）
 */
fun qualityBadgeLabel(currentSong: Song?, networkQualityLabel: String): String? {
    if (currentSong == null) return null

    // 网络歌曲：显示档位（AUTO 也显示「自动」，让用户知道可点）
    if (currentSong.isNetworkSong) {
        return "♪ " + networkQualityLabel.ifBlank { "自动" }
    }

    // 本地 / NAS / 下载歌曲：优先真实码率
    val br = currentSong.bitrate
    if (br > 0) {
        // 高码率（如 FLAC 1411 kbps）按无损呈现，更符合用户认知
        return if (br >= 1000) {
            val k = br / 1000.0
            "♪ 无损 ${"%.1f".format(k)}M"
        } else {
            "♪ $br kbps"
        }
    }

    // 回退：已下载歌曲的实际落盘档位
    val rq = currentSong.resolvedQuality
    if (rq == QualityTiers.LOSSLESS) return "♪ 无损"
    if (rq > QualityTiers.AUTO) return "♪ $rq kbps"

    // 无任何码率信息（如飞牛后端）→ 不渲染，避免展示 "♪ 0 kbps"
    return null
}

package com.nasmusic.tv.backend.network

import com.nasmusic.tv.R

/**
 * 音质档位：单一真相源（多码率方案 §2.2）。
 *
 * 设计要点：
 * - [LOSSLESS] 的 value=999 是 Meting-API 约定的 **FLAC 标识符**，不是真实比特率，
 *   不要用于 bitrate 显示、文件大小估算或统计（否则日志里会出现 "bitrate=999 kbps"）。
 * - [AUTO]=0 表示"不传 br，走端点默认"，不保证等于任何具体档位。
 * - 档位与"真实码率"是两个概念：真实码率看 `Song.bitrate` / `DownloadSongEntity.bitrate`，
 *   档位看本对象的常量值（见方案 §4.2.1）。
 *
 * 与 D-Music 的 `Qualities` 语义一致（128/192/320/999），但额外保留 AUTO 档。
 */
object QualityTiers {

    /** 自动：不传 `br`，完全交给端点决定 */
    const val AUTO: Int = 0

    /** 标准：128 kbps MP3 */
    const val STANDARD: Int = 128

    /** 高品：192 kbps MP3（v2.35.0 新增档位） */
    const val GOOD: Int = 192

    /** 极高：320 kbps MP3 */
    const val HIGH: Int = 320

    /** 无损：FLAC（API 约定标识符，**非真实码率**） */
    const val LOSSLESS: Int = 999

    /** 全部档位（含 AUTO），设置页/弹窗遍历用，顺序即 UI 展示顺序 */
    val all: List<Int> = listOf(AUTO, STANDARD, GOOD, HIGH, LOSSLESS)

    /**
     * 可用于探测与降级的真实码率档，**按码率降序**。
     * 不含 AUTO —— AUTO 无法"探测是否可用"（它只是不传 br）。
     */
    val availableTiers: List<Int> = listOf(LOSSLESS, HIGH, GOOD, STANDARD)

    /** 是否为"不传 br"的自动档 */
    fun isNoBr(tier: Int): Boolean = tier == AUTO

    /** 档位是否合法（用于防御非法持久化值） */
    fun isValid(tier: Int): Boolean = tier in all

    /**
     * 档位值 → 期望的容器扩展名。
     * 无损落 flac，其余（含 AUTO）落 mp3。
     */
    fun preferredExtOf(tier: Int): String = if (tier == LOSSLESS) "flac" else "mp3"

    /** 档位值 → 展示用字符串资源 id（勿在 Compose 里硬编码档位标签） */
    fun labelResOf(tier: Int): Int = when (tier) {
        LOSSLESS -> R.string.quality_tier_lossless
        HIGH -> R.string.quality_tier_high
        GOOD -> R.string.quality_tier_good
        STANDARD -> R.string.quality_tier_standard
        else -> R.string.quality_tier_auto
    }

    /**
     * 档位值 → 简短描述（"320 kbps" / "FLAC"），用于面板副标题与降级提示文案。
     * 无损档返回 "FLAC" 而非 "999 kbps"（999 不是码率）。
     */
    fun descriptionOf(tier: Int): String = when (tier) {
        LOSSLESS -> "FLAC"
        AUTO -> "auto"
        else -> "$tier kbps"
    }

    /**
     * 降级链：目标档失败时依次尝试更低的档，最后一个元素一定是最低可用档。
     *
     * - [AUTO] → `listOf(null)`，不传 br，完全交给端点（**不产生降级提示**）
     * - [LOSSLESS] → `999 → 320 → 192 → 128`
     * - [HIGH] → `320 → 192 → 128`
     * - [GOOD] → `192 → 128`
     * - [STANDARD] / 其他 → `tier → 128`
     *
     * 与改造前 `MetingApiService` 的 `listOf(tier, 320, 128)` 语义一致，但补齐了 192。
     */
    fun fallbackChainOf(tier: Int): List<Int?> = when (tier) {
        AUTO -> listOf(null)
        LOSSLESS -> listOf(LOSSLESS, HIGH, GOOD, STANDARD)
        HIGH -> listOf(HIGH, GOOD, STANDARD)
        GOOD -> listOf(GOOD, STANDARD)
        // 已是标准档（或未知值）：只尝试自身，不重复追加 128
        else -> listOf(tier)
    }
}

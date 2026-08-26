package com.nasmusic.tv.data.model

import androidx.compose.ui.graphics.Color

/**
 * 统一音乐来源标识
 *
 * 涵盖所有已知数据源：NAS 后端、网络音乐（Meting-API）、百度网盘、电台、Jamendo。
 * 每个类型包含中文显示名、图标字符、主题色，供 UI 组件（SourceBadge 等）使用。
 */
enum class MusicSourceType(
    /** 中文显示名（用于 UI 标签） */
    val displayName: String,
    /** 图标字符 */
    val icon: String,
    /** 主题色 */
    val color: Color
) {
    NAS("NAS", "🎵", Color(0xFF60A5FA)),           // 蓝色
    NETWORK_MUSIC("网络", "🌐", Color(0xFF34D399)),  // 绿色
    BAIDU_PAN("百度", "☁", Color(0xFFFBBF24)),      // 橙色
    RADIO("电台", "📻", Color(0xFFA78BFA)),          // 紫色
    JAMENDO("Jamendo", "♪", Color(0xFFF472B6)),     // 粉色
    WEATHER_RADIO("天气电台", "🌤", Color(0xFF67E8F9)) // 天蓝色
}

/**
 * 歌曲来源类型扩展属性
 *
 * 从 Song 的 isNetworkSong + networkSource 字段自动推导来源类型。
 * 不改动现有 Song 数据类字段，仅通过扩展属性提供统一访问。
 */
val Song.sourceType: MusicSourceType
    get() = when {
        !isNetworkSong -> MusicSourceType.NAS
        networkSource == RadioStation.SOURCE_ID -> MusicSourceType.RADIO
        networkSource == "baidu" -> MusicSourceType.BAIDU_PAN
        networkSource == "weather" -> MusicSourceType.WEATHER_RADIO
        // Jamendo 歌曲的 networkSource 为 "jamendo"
        networkSource == "jamendo" -> MusicSourceType.JAMENDO
        // 其他网络歌曲（meting/alapi/jiosaavn 等）统一归为 NETWORK_MUSIC
        isNetworkSong -> MusicSourceType.NETWORK_MUSIC
        else -> MusicSourceType.NETWORK_MUSIC
    }

/**
 * 搜索结果项，携带来源标签和匹配分
 */
data class RankedSong(
    val song: Song,
    val source: MusicSourceType,
    /** 匹配分（0-100），用于跨源排序；同分时按 source 优先级 */
    val matchScore: Int = 80
) {
    companion object {
        /** 来源优先级排序（数值越小优先级越高） */
        private val SOURCE_PRIORITY = mapOf(
            MusicSourceType.NAS to 0,
            MusicSourceType.NETWORK_MUSIC to 1,
            MusicSourceType.BAIDU_PAN to 2,
            MusicSourceType.JAMENDO to 3,
            MusicSourceType.RADIO to 4,
            MusicSourceType.WEATHER_RADIO to 5
        )

        /** 按来源优先级 + 匹配分降序排序 */
        fun sortByPriority(ranked: List<RankedSong>): List<RankedSong> =
            ranked.sortedWith(compareBy<RankedSong> {
                SOURCE_PRIORITY[it.source] ?: 99
            }.thenByDescending { it.matchScore })
    }
}

/**
 * 搜索结果聚合
 */
data class SearchAggregatorResult(
    /** 所有源的搜索结果（已去重、排序） */
    val allResults: List<RankedSong>,
    /** 各源命中数 */
    val sourceBreakdown: Map<MusicSourceType, Int>
)

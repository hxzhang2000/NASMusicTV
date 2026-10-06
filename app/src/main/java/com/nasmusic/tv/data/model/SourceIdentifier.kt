package com.nasmusic.tv.data.model

import androidx.compose.ui.graphics.Color
import com.nasmusic.tv.R
import androidx.annotation.StringRes

/**
 * 统一音乐来源标识
 *
 * 涵盖所有已知数据源：NAS 后端、网络音乐（Meting-API）、百度网盘、电台、Jamendo、本地音乐。
 * 每个类型包含中文显示名、图标字符、主题色，供 UI 组件（SourceBadge 等）使用。
 */
enum class MusicSourceType(
    /** 显示名资源 ID（本地化展示用） */
    @StringRes val displayNameRes: Int,
    /** 中文显示名（数据用途，如搜索词/持久化；UI 展示走 [displayNameRes]） */
    val displayName: String,
    /** 图标字符 */
    val icon: String,
    /** 主题色 */
    val color: Color
) {
    NAS(R.string.music_source_type_nas, "NAS", "🎵", Color(0xFF60A5FA)),           // 蓝色
    NETWORK_MUSIC(R.string.music_source_type_network_music, "网络", "🌐", Color(0xFF34D399)),  // 绿色
    BAIDU_PAN(R.string.music_source_type_baidu_pan, "百度", "☁", Color(0xFFFBBF24)),      // 橙色
    RADIO(R.string.music_source_type_radio, "电台", "📻", Color(0xFFA78BFA)),          // 紫色
    JAMENDO(R.string.music_source_type_jamendo, "Jamendo", "♪", Color(0xFFF472B6)),     // 粉色
    WEATHER_RADIO(R.string.music_source_type_weather_radio, "天气电台", "🌤", Color(0xFF67E8F9)), // 天蓝色
    LOCAL(R.string.music_source_type_local, "本地", "📱", Color(0xFFFB923C)),          // 橙色（本地音乐）
    DOWNLOAD(R.string.music_source_type_download, "已下载", "⬇", Color(0xFF22D3EE)),     // 青色（应用专属目录下载的歌曲）
    IMPORTED(R.string.music_source_type_imported, "导入", "📥", Color(0xFF9CA3AF));       // 灰色（歌单导入的裸 stub，尚未补全）

    companion object {
        /** 默认参与搜索的来源（排除 RADIO / WEATHER_RADIO / DOWNLOAD，它们不是搜索源） */
        val DEFAULT_SEARCH_SOURCES: Set<MusicSourceType> = setOf(
            NAS,
            NETWORK_MUSIC,
            BAIDU_PAN,
            JAMENDO,
            LOCAL
        )
    }
}

/**
 * 歌曲来源类型扩展属性
 *
 * 从 Song 的 isLocalSong / isNetworkSong + networkSource 字段自动推导来源类型。
 * 不改动现有 Song 数据类字段，仅通过扩展属性提供统一访问。
 */
val Song.sourceType: MusicSourceType
    get() = when {
        // 已下载歌曲优先识别（path 在应用专属目录且 storageType="DOWNLOAD"）
        storageType == "DOWNLOAD" -> MusicSourceType.DOWNLOAD
        // 本地音乐优先识别
        isLocalSong -> MusicSourceType.LOCAL
        // 导入的裸 stub（id 以 imported_ 前缀标记）——尚未补全，不归属任何后端
        id.startsWith("imported_") -> MusicSourceType.IMPORTED
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
            MusicSourceType.LOCAL to 0,
            MusicSourceType.DOWNLOAD to 0,    // 已下载与本地同等优先级（均为本地可播）
            MusicSourceType.NAS to 1,
            MusicSourceType.NETWORK_MUSIC to 2,
            MusicSourceType.BAIDU_PAN to 3,
            MusicSourceType.JAMENDO to 4,
            MusicSourceType.RADIO to 5,
            MusicSourceType.WEATHER_RADIO to 6
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

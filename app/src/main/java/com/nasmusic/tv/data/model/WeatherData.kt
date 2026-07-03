package com.nasmusic.tv.data.model

/**
 * 天气数据模型
 *
 * 来源于 Open-Meteo API 的返回数据精简结构。
 * 位置信息通过 IP 定位（ip-api.com）获取。
 */
data class WeatherData(
    val temperature: Double,           // 当前温度（°C）
    val humidity: Double,              // 相对湿度（%）
    val windSpeed: Double,             // 风速（km/h）
    val weatherCode: Int,              // WMO 天气代码（https://open-meteo.com/en/docs#weathervariables）
    val isDay: Boolean = true,         // 是否为白天
    val cityName: String = "未知位置",  // 城市名称
    val description: String = ""       // 天气描述文本
)

/**
 * IP 定位结果
 */
data class IpLocation(
    val city: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val success: Boolean = false
)

/**
 * 天气电台队列
 *
 * 由 WeatherRadioManager 根据当天天气和 mood 构建。
 * @param songs 电台歌曲列表（混合 NAS 收藏 + 网络歌曲）
 * @param mood 当前匹配的 mood
 * @param queries 用于构建此队列的搜索关键词
 */
data class WeatherRadioQueue(
    val songs: List<Song> = emptyList(),
    val mood: WeatherMood = WeatherMood.SUNNY,
    val queries: List<String> = emptyList(),
    val nasCount: Int = 0,
    val networkCount: Int = 0
)

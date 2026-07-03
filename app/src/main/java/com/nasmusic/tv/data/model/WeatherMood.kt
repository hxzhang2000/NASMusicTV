package com.nasmusic.tv.data.model

/**
 * 天气心情（mood）枚举
 *
 * 映射自 Mineradio 的 weatherRadioSeedQueries() 逻辑。
 * 每个 mood 对应一组搜索关键词，用于构建天气电台歌曲队列。
 */
enum class WeatherMood(
    val displayName: String,
    val icon: String,
    /** 用于 Meting API 搜索的关键词列表 */
    val searchQueries: List<String>,
    /** 匹配的 WMO 天气代码（https://open-meteo.com/en/docs#weathervariables） */
    val matchingWeatherCodes: List<IntRange>
) {
    SUNNY("阳光 · 轻音乐", "\u2600\uFE0F", listOf("阳光", "轻音乐", "民谣", "清新"),
        listOf(0..0)),                          // 晴天
    CLOUDY("多云 · 民谣", "\u2601\uFE0F", listOf("多云", "民谣", "轻音乐", "午后"),
        listOf(1..3, 4..6, 7..10)),              // 多云/阴天/雾
    RAINY("雨天 · 钢琴", "\uD83C\uDF27\uFE0F", listOf("钢琴", "雨声", "治愈", "抒情"),
        listOf(45..48, 51..57, 61..67, 80..82)), // 雾雨/毛毛雨/雨
    SNOWY("雪天 · 温暖", "\u2744\uFE0F", listOf("温暖", "安静", "圣诞", "冬日"),
        listOf(71..77, 85..86)),                 // 雪
    WINDY("风天 · 激昂", "\uD83C\uDF2C\uFE0F", listOf("激昂", "摇滚", "电影原声", "大气"),
        listOf(5..5)),                           // 大风
    THUNDER("雷雨 · 史诗", "\u26A1", listOf("史诗", "暗黑", "氛围", "交响"),
        listOf(95..99)),                         // 雷暴
    NIGHT("夜晚 · 爵士", "\uD83C\uDF19", listOf("爵士", "蓝调", "慢歌", "电音"),
        listOf(0..0));                           // 夜间用 SUNNY code + isDay=false

    /**
     * 判断当前天气代码是否匹配此 mood
     */
    fun matchesWeather(code: Int, isDay: Boolean): Boolean {
        if (this == NIGHT && !isDay && code == 0) return true  // 晴天 + 夜晚
        return matchingWeatherCodes.any { range -> code in range }
    }

    companion object {
        /**
         * 根据天气数据返回推荐的 mood
         */
        fun fromWeather(weather: WeatherData): WeatherMood {
            // 夜晚特殊处理
            if (!weather.isDay && weather.weatherCode == 0) return NIGHT
            return entries.firstOrNull { it.matchesWeather(weather.weatherCode, weather.isDay) }
                ?: CLOUDY // 默认多云
        }

        /**
         * 获取除 NIGHT 外的所有快速切换选项
         */
        fun quickSwitches(): List<WeatherMood> = entries.filter { it != NIGHT }
    }
}

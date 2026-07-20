package com.nasmusic.tv.data.model

/**
 * 天气预报数据（单日）
 */
data class WeatherForecast(
    val date: String = "",                  // "2024-01-15"
    val temperatureHigh: Double = 0.0,      // 最高温（°C）
    val temperatureLow: Double = 0.0,       // 最低温（°C）
    val humidity: Double = 0.0,             // 湿度（%）
    val weatherCode: Int = 0,               // WMO 天气代码
    val description: String = "",           // 描述
    val iconCode: String = "01d"            // OpenWeatherMap 图标代码
)

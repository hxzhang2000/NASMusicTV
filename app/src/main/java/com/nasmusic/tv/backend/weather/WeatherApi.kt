package com.nasmusic.tv.backend.weather

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.data.model.IpLocation
import com.nasmusic.tv.data.model.WeatherData
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 天气 API 封装
 *
 * 数据来源（自动 fallback）：
 * 1. Open-Meteo（免费公开天气 API，无需 API Key）
 * 2. OpenWeatherMap（需 API Key，国内网络更稳定，免费注册：https://openweathermap.org/api）
 * 3. ip-api.com（免费 IP 定位，无需 API Key）
 *
 * Open-Meteo API 文档：https://open-meteo.com/
 * OpenWeatherMap 文档：https://openweathermap.org/current
 * ip-api.com 文档：https://ip-api.com/docs
 */
class WeatherApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        private const val TAG = "WeatherApi"
        // Open-Meteo API 端点（免费，无需 Key）
        private const val OPEN_METEO_BASE = "https://api.open-meteo.com/v1/forecast"
        // OpenWeatherMap API 端点（需 API Key，作 Open-Meteo 不可用时的备选）
        private const val OPEN_WEATHER_MAP_BASE = "https://api.openweathermap.org/data/2.5/weather"
        // IP 定位端点（免费版，不支持 HTTPS）
        private const val IP_API_BASE = "http://ip-api.com/json/"
    }

    /**
     * 获取当前 IP 的地理位置
     */
    suspend fun getIpLocation(): IpLocation = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(IP_API_BASE)
                .header("User-Agent", "NASMusicTV/2.6")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext IpLocation()
            val json = gson.fromJson(body, JsonObject::class.java)
            if (json.get("status")?.asString == "success") {
                IpLocation(
                    city = json.get("city")?.asString ?: "",
                    lat = json.get("lat")?.asDouble ?: 0.0,
                    lon = json.get("lon")?.asDouble ?: 0.0,
                    success = true
                )
            } else {
                AppLog.w(TAG, "IP location failed: ${json.get("message")?.asString ?: "unknown"}")
                IpLocation()
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "getIpLocation failed: ${e.message}", e)
            IpLocation()
        }
    }

    /**
     * 根据经纬度获取当前天气
     *
     * 尝试顺序：
     * 1. Open-Meteo（免费，无需 Key，国际网络环境可用）
     * 2. OpenWeatherMap（需要 API key，提供者传参）
     *
     * @param lat 纬度
     * @param lon 经度
     * @param openWeatherMapApiKey 可选 OpenWeatherMap API Key
     */
    suspend fun getWeather(lat: Double, lon: Double, openWeatherMapApiKey: String? = null): WeatherData? = withContext(Dispatchers.IO) {
        // 1. 先试 Open-Meteo
        val openMeteo = try {
            val url = buildString {
                append("$OPEN_METEO_BASE?latitude=$lat&longitude=$lon")
                append("&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,is_day")
                append("&timezone=auto")
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NASMusicTV/2.6")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = gson.fromJson(body, JsonObject::class.java)
            val current = json.getAsJsonObject("current") ?: return null

            WeatherData(
                temperature = current.get("temperature_2m")?.asDouble ?: 0.0,
                humidity = current.get("relative_humidity_2m")?.asDouble ?: 0.0,
                windSpeed = current.get("wind_speed_10m")?.asDouble ?: 0.0,
                weatherCode = current.get("weather_code")?.asInt ?: 0,
                isDay = current.get("is_day")?.asInt == 1,
                description = describeWeatherCode(current.get("weather_code")?.asInt ?: 0)
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "Open-Meteo failed: ${e.message}")
            null
        }
        if (openMeteo != null) return@withContext openMeteo

        // 2. Open-Meteo 不可用，尝试 OpenWeatherMap（需要 API Key）
        if (openWeatherMapApiKey.isNullOrBlank()) {
            AppLog.w(TAG, "Open-Meteo failed and no OpenWeatherMap API key configured")
            return null
        }
        return getWeatherOpenWeatherMap(lat, lon, openWeatherMapApiKey)
    }

    /**
     * 通过 OpenWeatherMap API 获取天气
     * 当 Open-Meteo 不可用时的备选方案
     */
    private suspend fun getWeatherOpenWeatherMap(lat: Double, lon: Double, apiKey: *** WeatherData? {
        try {
            val url = "$OPEN_WEATHER_MAP_BASE?lat=$lat&lon=$lon&appid=$apiKey&units=metric&lang=zh_cn"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NASMusicTV/2.6")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = gson.fromJson(body, JsonObject::class.java)

            // OpenWeatherMap 响应格式
            val main = json.getAsJsonObject("main") ?: return null
            val wind = json.getAsJsonObject("wind")
            val weatherArr = json.getAsJsonArray("weather")
            val weatherObj = weatherArr?.firstOrNull()?.asJsonObject
            val sys = json.getAsJsonObject("sys")

            val isDay = sys?.let {
                val sunrise = it.get("sunrise")?.asLong
                val sunset = it.get("sunset")?.asLong
                if (sunrise != null && sunset != null) {
                    val now = System.currentTimeMillis() / 1000
                    now in sunrise..sunset
                } else true
            } ?: true

            WeatherData(
                temperature = main.get("temp")?.asDouble ?: 0.0,
                humidity = main.get("humidity")?.asDouble ?: 0.0,
                windSpeed = wind?.get("speed")?.asDouble ?: 0.0,
                weatherCode = mapOpenWeatherMapCode(weatherObj?.get("id")?.asInt ?: 0),
                isDay = isDay,
                description = weatherObj?.get("description")?.asString ?: "未知"
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "OpenWeatherMap failed: ${e.message}", e)
            null
        }
    }

    /**
     * OpenWeatherMap weather condition code → WMO weather code 映射
     * OpenWeatherMap codes: https://openweathermap.org/weather-conditions
     */
    private fun mapOpenWeatherMapCode(owmCode: Int): Int = when (owmCode) {
        in 200..232 -> 95  // 雷暴
        in 300..321 -> 50  // 毛毛雨
        in 500..531 -> 60  // 雨
        in 600..622 -> 70  // 雪
        in 701..781 -> 20  // 雾/霾
        800 -> 0           // 晴天
        801 -> 1           // 少云
        802 -> 2           // 多云
        803, 804 -> 3      // 阴天
        else -> 0
    }

    /**
     * 一键获取当前位置天气
     *
     * 依次尝试：
     * 1. ip-api.com IP 定位（失败则回退到北京）
     * 2. Open-Meteo 获取天气（国际网络）
     * 3. OpenWeatherMap 获取天气（需 API Key，国内网络备选）
     *
     * @param manualCity 可选的手动城市名（JSON 写入 cityName，但坐标仍用经纬度）
     * @param openWeatherMapApiKey 可选 OpenWeatherMap API Key，用于 Open-Meteo 不可用时的备选
     */
    suspend fun fetchCurrentWeather(manualCity: String? = null, openWeatherMapApiKey: String? = null): WeatherData? {
        var location = getIpLocation()
        if (!location.success) {
            AppLog.w(TAG, "IP location failed, falling back to Beijing (39.9042, 116.4074)")
            location = IpLocation(city = "北京", lat = 39.9042, lon = 116.4074, success = true)
        }

        val weather = getWeather(location.lat, location.lon, openWeatherMapApiKey) ?: return null
        return weather.copy(
            cityName = manualCity ?: location.city
        )
    }

    /**
     * WMO 天气代码 → 中文描述
     */
    private fun describeWeatherCode(code: Int): String = when (code) {
        0 -> "晴天"
        1 -> "少云"
        2 -> "多云"
        3 -> "阴天"
        4, 5, 6 -> "大风"
        7 -> "扬沙"
        8 -> "风暴"
        9 -> "沙尘"
        10, 11, 12  -> "雾"
        13, 14, 15, 16, 17, 18 -> "雷电"
        19, 20, 21, 22, 23, 24, 25 -> "霾"
        26, 27, 28, 29 -> "浮尘"
        30, 31, 32, 33, 34, 35 -> "沙尘暴"
        36, 37, 38, 39 -> "雪"
        40, 41, 42, 43, 44, 45, 46, 47, 48, 49 -> "雾"
        50, 51, 52, 53, 54, 55, 56, 57 -> "毛毛雨"
        60, 61, 62, 63, 64, 65, 66, 67, 68, 69 -> "雨"
        70, 71, 72, 73, 74, 75, 76, 77 -> "雪"
        78, 79, 80, 81, 82 -> "阵雨"
        83, 84, 85, 86 -> "阵雪"
        90, 91, 92, 93, 94, 95, 96, 97, 98, 99 -> "雷暴"
        else -> "未知"
    }
}

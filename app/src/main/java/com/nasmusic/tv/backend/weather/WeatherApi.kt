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
 * 数据来源：
 * - Open-Meteo（免费公开天气 API，无需 API Key）
 * - ip-api.com（免费 IP 定位，无需 API Key）
 *
 * Open-Meteo API 文档：https://open-meteo.com/
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
        // Open-Meteo API 端点
        private const val OPEN_METEO_BASE = "https://api.open-meteo.com/v1/forecast"
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
     * @param lat 纬度
     * @param lon 经度
     */
    suspend fun getWeather(lat: Double, lon: Double): WeatherData? = withContext(Dispatchers.IO) {
        try {
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
            val body = response.body?.string() ?: return@withContext null
            val json = gson.fromJson(body, JsonObject::class.java)

            val current = json.getAsJsonObject("current") ?: return@withContext null

            WeatherData(
                temperature = current.get("temperature_2m")?.asDouble ?: 0.0,
                humidity = current.get("relative_humidity_2m")?.asDouble ?: 0.0,
                windSpeed = current.get("wind_speed_10m")?.asDouble ?: 0.0,
                weatherCode = current.get("weather_code")?.asInt ?: 0,
                isDay = current.get("is_day")?.asInt == 1,
                description = describeWeatherCode(current.get("weather_code")?.asInt ?: 0)
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "getWeather failed: ${e.message}", e)
            null
        }
    }

    /**
     * 一键获取当前位置天气
     *
     * @param manualCity 可选的手动城市名（JSON 写入 cityName，但坐标仍用经纬度）
     */
    suspend fun fetchCurrentWeather(manualCity: String? = null): WeatherData? {
        var location = getIpLocation()
        if (!location.success) {
            AppLog.w(TAG, "IP location failed, falling back to Beijing (39.9042, 116.4074)")
            location = IpLocation(city = "北京", lat = 39.9042, lon = 116.4074, success = true)
        }

        val weather = getWeather(location.lat, location.lon) ?: return null
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

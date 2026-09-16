package com.nasmusic.tv.backend.weather

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.data.model.IpLocation
import com.nasmusic.tv.data.model.WeatherData
import com.nasmusic.tv.data.model.WeatherForecast
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
        private const val OPEN_WEATHER_MAP_FORECAST_BASE = "https://api.openweathermap.org/data/2.5/forecast"
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
            // 必须 use{} 关闭 Response，否则连接/连接池资源泄漏
            val body = client.newCall(request).execute().use { response ->
                response.body?.string()
            } ?: return@withContext IpLocation()
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
                append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,is_day")
                append("&timezone=auto")
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NASMusicTV/2.6")
                .build()
            val body = client.newCall(request).execute().use { response ->
                response.body?.string()
            } ?: return@withContext null
            val json = gson.fromJson(body, JsonObject::class.java)
            val current = json.getAsJsonObject("current") ?: return@withContext null

            WeatherData(
                temperature = current.get("temperature_2m")?.asDouble ?: 0.0,
                feelsLike = current.get("apparent_temperature")?.asDouble,
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
            return@withContext null
        }
        return@withContext getWeatherOpenWeatherMap(lat, lon, openWeatherMapApiKey)
    }

    /**
     * 通过 OpenWeatherMap API 获取天气
     * 当 Open-Meteo 不可用时的备选方案
     */
    private suspend fun getWeatherOpenWeatherMap(lat: Double, lon: Double, apiKey: String): WeatherData? {
        return try {
            val url = "$OPEN_WEATHER_MAP_BASE?lat=$lat&lon=$lon&appid=$apiKey&units=metric&lang=zh_cn"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NASMusicTV/2.6")
                .build()
            val body = client.newCall(request).execute().use { response ->
                response.body?.string()
            } ?: return null
            val json = gson.fromJson(body, JsonObject::class.java)
            val main = json.getAsJsonObject("main") ?: return null
            val wind = json.getAsJsonObject("wind")
            val weatherArr = json.getAsJsonArray("weather")
            val weatherObj = weatherArr?.firstOrNull()?.asJsonObject
            val sys = json.getAsJsonObject("sys")

            val isDay = sys?.let {
                val sunrise = it.get("sunrise")?.asLong ?: 0L
                val sunset = it.get("sunset")?.asLong ?: 0L
                if (sunrise > 0 && sunset > 0) {
                    val now = System.currentTimeMillis() / 1000
                    now in sunrise..sunset
                } else true
            } ?: true

            WeatherData(
                temperature = main.get("temp")?.asDouble ?: 0.0,
                feelsLike = main.get("feels_like")?.asDouble,
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
     * 获取天气预报（未来 5 天）
     *
     * 需要 OpenWeatherMap API Key
     */
    suspend fun getForecast(lat: Double, lon: Double, apiKey: String): List<WeatherForecast> = withContext(Dispatchers.IO) {
        try {
            // cnt 修正：5 天/3 小时接口按 3 小时一条返回；cnt=5 只覆盖约 15 小时
            // （最多 2 个不同日期），拿不到 5 天预报。取满 40 条（5 天 × 8 条）再按天去重。
            val url = "$OPEN_WEATHER_MAP_FORECAST_BASE?lat=$lat&lon=$lon&appid=$apiKey&units=metric&lang=zh_cn&cnt=40"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NASMusicTV/2.6")
                .build()
            val body = client.newCall(request).execute().use { response ->
                response.body?.string()
            } ?: return@withContext emptyList()
            val json = gson.fromJson(body, JsonObject::class.java)
            val list = json.getAsJsonArray("list") ?: return@withContext emptyList()

            val forecasts = mutableListOf<WeatherForecast>()
            // 按天去重，每天取一条代表
            val seenDates = mutableSetOf<String>()
            for (i in 0 until list.size()) {
                val item = list[i].asJsonObject
                val dtTxt = item.get("dt_txt")?.asString ?: continue
                val date = dtTxt.take(10) // "2024-01-15"
                if (date in seenDates) continue
                seenDates.add(date)

                val main = item.getAsJsonObject("main")
                val weatherArr = item.getAsJsonArray("weather")
                val weatherObj = weatherArr?.firstOrNull()?.asJsonObject

                forecasts.add(WeatherForecast(
                    date = date,
                    temperatureHigh = main?.get("temp_max")?.asDouble ?: 0.0,
                    temperatureLow = main?.get("temp_min")?.asDouble ?: 0.0,
                    humidity = main?.get("humidity")?.asDouble ?: 0.0,
                    weatherCode = mapOpenWeatherMapCode(weatherObj?.get("id")?.asInt ?: 0),
                    description = weatherObj?.get("description")?.asString ?: "未知",
                    iconCode = weatherObj?.get("icon")?.asString ?: "01d"
                ))

                if (forecasts.size >= 5) break
            }
            forecasts
        } catch (e: Exception) {
            AppLog.e(TAG, "getForecast failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * OpenWeatherMap weather condition code → **WMO** weather code 映射
     * OpenWeatherMap codes: https://openweathermap.org/weather-conditions
     *
     * P3-5 修复（2026-09-16）：原实现返回 20/50/60/70 这些**非 WMO 代码**，
     * 而下游 `WeatherMood.matchingWeatherCodes` 全部按 WMO 区间判定
     * （RAINY = 45..48/51..57/61..67/80..82，SNOWY = 71..77/85..86 …）。
     * 于是走 OpenWeatherMap 这条路时：毛毛雨 50、雨 60、雪 70、雾霾 20 **全部落不进任何区间**，
     * `WeatherMood.fromWeather` 一律回退成 CLOUDY——下雨天放"多云 · 民谣"，且无任何报错。
     * 现在改为返回真正的 WMO 代码（51 / 61 / 71 / 45），与 Open-Meteo 路径（直接给 WMO）统一。
     */
    private fun mapOpenWeatherMapCode(owmCode: Int): Int = when (owmCode) {
        in 200..232 -> 95  // 雷暴 → WMO 95 Thunderstorm
        in 300..321 -> 51  // 毛毛雨 → WMO 51 Light drizzle
        in 500..531 -> 61  // 雨 → WMO 61 Slight rain
        in 600..622 -> 71  // 雪 → WMO 71 Slight snow fall
        in 701..781 -> 45  // 雾/霾/沙尘 → WMO 45 Fog
        800 -> 0           // 晴天
        801 -> 1           // 少云
        802 -> 2           // 多云
        803, 804 -> 3      // 阴天
        else -> 0
    }

    /**
     * 一键获取当前位置天气
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
     * 获取当前位置的天气预报
     */
    suspend fun fetchForecast(openWeatherMapApiKey: String?): List<WeatherForecast> {
        if (openWeatherMapApiKey.isNullOrBlank()) return emptyList()

        var location = getIpLocation()
        if (!location.success) {
            location = IpLocation(city = "北京", lat = 39.9042, lon = 116.4074, success = true)
        }
        return getForecast(location.lat, location.lon, openWeatherMapApiKey)
    }

    /**
     * WMO 天气代码 → 中文描述。
     *
     * P3-5 修复（2026-09-16）：原实现是一张**自造区间表**（4/5/6 大风、7 扬沙、8 风暴、
     * 9 沙尘、10-12 雾、13-18 雷电、19-25 霾、30-35 沙尘暴、36-39 雪…），与 WMO 标准并不对应：
     * - 51~77 段被拉平成「50..57 毛毛雨 / 60..69 雨 / 70..77 雪」，把 WMO 里不存在的
     *   偶数代码（52/54/62/64/70/72/74/76…）也纳入，而 WMO 真正的分段
     *   （51/53/55 毛毛雨、56/57 冻毛毛雨、61/63/65 雨、66/67 冻雨、71/73/75 雪、77 雪粒）
     *   全被糊在一起——"小雨/中雨/大雨"无法区分；
     * - 4~44 段那些分支在 WMO 表里根本不存在（Open-Meteo 永不会返回），属死分支。
     * 现改为严格对照 WMO 表（https://open-meteo.com/en/docs#weathervariables），
     * 未列入的代码一律回 "未知"，不再落进某个臆造的桶里。
     *
     * 注：本函数只产出展示文本（HomeScreen / WeatherRadioScreen 直接显示）；
     * 情绪匹配走 `WeatherData.weatherCode` + `WeatherMood.matchingWeatherCodes`，不受影响。
     */
    private fun describeWeatherCode(code: Int): String = when (code) {
        0 -> "晴天"
        1 -> "少云"
        2 -> "多云"
        3 -> "阴天"
        45 -> "雾"
        48 -> "雾凇"
        51 -> "小毛毛雨"
        53 -> "毛毛雨"
        55 -> "浓毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80 -> "小阵雨"
        81 -> "中阵雨"
        82 -> "强阵雨"
        85 -> "小阵雪"
        86 -> "大阵雪"
        95 -> "雷暴"
        96 -> "雷暴伴小冰雹"
        99 -> "雷暴伴大冰雹"
        else -> "未知"
    }
}

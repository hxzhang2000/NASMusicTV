package com.nasmusic.tv.backend.radio

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nasmusic.tv.data.model.RadioStation
import com.nasmusic.tv.util.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * radio-browser.info 电台 API 客户端（纯公共 API，无 key）
 *
 * 特性：
 * - 多服务器容灾：预设公共服务器列表，按序尝试，失败换下一台（官方推荐做法）
 * - User-Agent 必须可识别（radio-browser 规范）
 * - 播放上报（/json/url）保持数据库良性（义务）
 *
 * 注意：本项目不自建后台——本客户端仅直连 radio-browser 公共服务器。
 */
class RadioBrowserClient(
    private val client: OkHttpClient = defaultHttpClient(),
    // 预设服务器（官方推荐从 all.api.radio-browser.info DNS 解析，这里用知名节点 + 首节点优先）
    serverSeeds: List<String> = listOf(
        "https://de1.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://all.api.radio-browser.info"
    )
) {
    private val servers: List<String> = serverSeeds
    private val gson = Gson()

    companion object {
        private const val TAG = "RadioBrowser"
        private const val USER_AGENT = "NASMusicTV/2.21"

        /** 预置快捷分类标签（电台页顶部筛选行） */
        val PRESET_TAGS = listOf("pop", "rock", "classical", "jazz", "instrumental", "news", "chinese")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 搜索电台。
     *
     * @param query 关键词（台名/标签），null 忽略
     * @param tag 标签筛选，null 忽略
     * @param countryCode 国家代码（如 "CN"），null 忽略
     * @param limit 返回条数
     */
    suspend fun searchStations(
        query: String? = null,
        tag: String? = null,
        countryCode: String? = null,
        limit: Int = 50
    ): List<RadioStation> {
        val params = StringBuilder("?limit=$limit&order=votes&reverse=true&hidebroken=true")
        query?.takeIf { it.isNotBlank() }?.let { params.append("&name=").append(encode(it)) }
        tag?.takeIf { it.isNotBlank() }?.let { params.append("&tag=").append(encode(it)) }
        countryCode?.takeIf { it.isNotBlank() }?.let { params.append("&countrycode=").append(encode(it)) }

        for (server in servers) {
            try {
                val url = "$server/json/stations/search$params"
                val body = get(url) ?: continue
                val arr = gson.fromJson(body, Array<JsonObject>::class.java) ?: continue
                return arr.mapNotNull { parseStation(it) }
            } catch (e: Exception) {
                AppLog.w(TAG, "searchStations: $server failed: ${e.message}")
            }
        }
        return emptyList()
    }

    /**
     * 热门标签（用于电台页筛选行展示，按电台数排序）
     */
    suspend fun popularTags(limit: Int = 20): List<Pair<String, Int>> {
        for (server in servers) {
            try {
                val url = "$server/json/tags?order=stationcount&reverse=true&limit=$limit"
                val body = get(url) ?: continue
                val arr = gson.fromJson(body, Array<JsonObject>::class.java) ?: continue
                return arr.mapNotNull { obj ->
                    val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                    val count = obj.get("stationcount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                    name to count
                }.filter { it.first.isNotBlank() }
            } catch (e: Exception) {
                AppLog.w(TAG, "popularTags: $server failed: ${e.message}")
            }
        }
        return emptyList()
    }

    /**
     * 按 uuid 查单台（收藏恢复用）
     */
    suspend fun stationByUuid(uuid: String): RadioStation? {
        if (uuid.isBlank()) return null
        for (server in servers) {
            try {
                val url = "$server/json/stations/byuuid?uuids=${encode(uuid)}"
                val body = get(url) ?: continue
                val arr = gson.fromJson(body, Array<JsonObject>::class.java) ?: continue
                return arr.firstOrNull()?.let { parseStation(it) }
            } catch (e: Exception) {
                AppLog.w(TAG, "stationByUuid: $server failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * 播放上报（radio-browser 义务，帮助维持数据库良性）
     */
    suspend fun reportClick(station: RadioStation) {
        for (server in servers) {
            try {
                get("$server/json/url?uuid=${encode(station.uuid)}")
                return
            } catch (e: Exception) {
                AppLog.w(TAG, "reportClick: $server failed: ${e.message}")
            }
        }
    }

    // ── 内部 ──

    private suspend fun get(url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                AppLog.w(TAG, "GET $url -> ${resp.code}")
                null
            } else {
                resp.body?.string()
            }
        }
    }

    private fun parseStation(obj: JsonObject): RadioStation? {
        try {
            val uuid = obj.get("stationuuid")?.takeIf { !it.isJsonNull }?.asString ?: return null
            val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return null
            val urlResolved = obj.get("url_resolved")?.takeIf { !it.isJsonNull }?.asString
                ?: obj.get("url")?.takeIf { !it.isJsonNull }?.asString ?: return null
            val favicon = obj.get("favicon")?.takeIf { !it.isJsonNull }?.asString
            val countryCode = obj.get("countrycode")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val country = obj.get("country")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val tags = obj.get("tags")?.takeIf { !it.isJsonNull }?.asString
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val votes = obj.get("votes")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val bitrate = obj.get("bitrate")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val codec = obj.get("codec")?.takeIf { !it.isJsonNull }?.asString ?: ""
            return RadioStation(
                uuid = uuid,
                name = name,
                urlResolved = urlResolved,
                faviconUrl = favicon?.takeIf { it.startsWith("http") },
                countryCode = countryCode,
                country = country,
                tags = tags,
                votes = votes,
                bitrate = bitrate,
                codec = codec
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "parseStation error: ${e.message}")
            return null
        }
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
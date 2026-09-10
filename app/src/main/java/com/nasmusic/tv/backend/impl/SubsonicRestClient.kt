package com.nasmusic.tv.backend.impl

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.RetryConfig
import com.nasmusic.tv.util.UrlSanitizer
import com.nasmusic.tv.util.withRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigInteger
import java.security.MessageDigest

/**
 * R-10 Subsonic 公共层：salt/token 认证、REST URL 构造、封面 URL、请求执行（带重试）。
 *
 * NavidromeAdapter / SubsonicAdapter 共用的同构逻辑下沉于此（工具类而非基类继承——
 * 保持两个适配器行为逐行不变，避免继承层引入回归面）。适配器持有实例并传入
 * 自身 OkHttpClient 与日志 TAG。
 *
 * 认证规范说明：token = md5(password + salt) 的 hex 表示，这是 Subsonic API 协议
 * 规定的认证方式（非"过时"），Navidrome 完全兼容。salt 在 initialize 时固定一次
 * （B11 修复），保证封面/流 URL 稳定、可被 HTTP 缓存复用。
 */
internal class SubsonicRestClient(
    private val tag: String,
    private val client: OkHttpClient,
    private val gson: Gson = Gson()
) {

    companion object {
        /** Subsonic API 协议版本号（非客户端版本；升级前提：服务端明确要求更高版本） */
        const val API_VERSION = "1.16.1"

        /** 客户端标识（Subsonic API c 参数，服务端用于区分客户端类型） */
        const val CLIENT_NAME = "NASMusicTV"
    }

    /** 认证状态（initialize 时由适配器写入） */
    internal var baseUrl: String = ""
    internal var username: String = ""
    internal var password: String = ""
    internal var salt: String = ""

    /**
     * 构造带认证参数的 REST URL（f=json）。
     * salt/token 现场计算（盐在 initialize 已固定，见 B11 注释）。
     */
    fun buildRestUrl(method: String): String {
        val token = md5(password + salt)
        return "$baseUrl/rest/$method.view?" +
                "u=$username&" +
                "t=$token&" +
                "s=$salt&" +
                "v=$API_VERSION&" +
                "c=$CLIENT_NAME&" +
                "f=json"
    }

    /** 封面 URL（getCoverArt + id + size=512） */
    fun buildCoverUrl(coverArtId: String): String =
        buildRestUrl("getCoverArt") + "&id=$coverArtId&size=512"

    /** 执行 GET 并解析 JsonObject（3 次重试、F-1 脱敏日志、GBK 兼容读取） */
    suspend fun executeRequest(url: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            withRetry(
                config = RetryConfig(maxAttempts = 3, baseDelayMs = 500L),
                onError = { attempt, e ->
                    AppLog.w(tag, "executeRequest retry attempt=$attempt for ${UrlSanitizer.sanitize(url)}", e)
                }
            ) {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    val rawBytes = response.body?.bytes() ?: return@use null
                    val utf8Body = String(rawBytes, Charsets.UTF_8)
                    if (response.isSuccessful) {
                        gson.fromJson(utf8Body, JsonObject::class.java)
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e(tag, "executeRequest failed for ${UrlSanitizer.sanitize(url)}", e)
            null
        }
    }

    /** md5 hex（32 位小写补零） */
    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return BigInteger(1, md.digest(input.toByteArray()))
            .toString(16).padStart(32, '0')
    }
}

package com.nasmusic.tv.backend.network.baidu

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nasmusic.tv.BuildConfig
import com.nasmusic.tv.data.model.BaiduTokens
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 百度网盘 OAuth 客户端（设备码模式 + token 刷新）
 *
 * TV 场景首选设备码模式（用户零打字）：
 * 1. [requestDeviceCode] 请求设备码 → 返回 user_code + verification_url
 * 2. 电视显示 user_code，用户手机访问 verification_url 输入
 * 3. [pollDeviceToken] 轮询 token 端点，直到成功或超时
 * 4. token 过期前用 refresh_token 刷新（[getValidAccessToken]）
 *
 * ⚠️ refresh_token 单次有效：刷新成功后响应带新值，必须原子写回。
 */
class BaiduOAuthClient(
    private val client: OkHttpClient,
    private val prefs: AppPreferences,
    /** Token 端点（测试可注入 MockWebServer URL 覆盖默认线上端点） */
    private val tokenUrl: String = BaiduNetdiskConfig.TOKEN_URL
) {
    private val gson = Gson()
    private val TAG = "BaiduOAuth"

    /** 并发刷新加锁（避免两个协程各用同一 refresh_token 各刷一次、其一必失败） */
    private val refreshLock = Mutex()

    /** 解析 AppKey/SecretKey：优先用户自填，否则用编译期嵌入的默认值 */
    private fun resolveAppKey(): String? = prefs.getBaiduCustomAppKeySync() ?: BuildConfig.BAIDU_APP_ID.takeIf { it.isNotBlank() }
    private fun resolveSecretKey(): String? = prefs.getBaiduCustomSecretKeySync() ?: BuildConfig.BAIDU_APP_SECRET.takeIf { it.isNotBlank() }

    /** 设备码请求结果（供 UI 显示） */
    data class DeviceCodeResult(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val qrcodeUrl: String?,
        val expiresIn: Int,
        val interval: Int
    )

    /** 轮询结果 */
    sealed class PollResult {
        data class Success(val tokens: BaiduTokens) : PollResult()
        /** 用户尚未完成授权，继续轮询 */
        object Pending : PollResult()
        /** 用户拒绝授权 */
        object Declined : PollResult()
        /** 轮询过频，需增加间隔 */
        data class SlowDown(val newInterval: Int) : PollResult()
        /** 超时/异常 */
        data class Failed(val message: String) : PollResult()
    }

    /**
     * 请求设备码（TV 显示 user_code，用户手机访问 verification_url 输入）
     */
    suspend fun requestDeviceCode(): DeviceCodeResult? = withContext(Dispatchers.IO) {
        val appKey = resolveAppKey() ?: run {
            AppLog.e(TAG, "requestDeviceCode: AppKey 未配置")
            return@withContext null
        }
        try {
            val url = "${BaiduNetdiskConfig.DEVICE_CODE_URL}?" +
                "response_type=device_code" +
                "&client_id=$appKey" +
                "&scope=${BaiduNetdiskConfig.SCOPE}"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java) ?: return@use null
                if (resp.code != 200) {
                    AppLog.w(TAG, "requestDeviceCode failed: code=${resp.code} body=${body.take(200)}")
                    return@use null
                }
                DeviceCodeResult(
                    deviceCode = json.get("device_code")?.asString ?: return@use null,
                    userCode = json.get("user_code")?.asString ?: "",
                    verificationUrl = json.get("verification_url")?.asString
                        ?: BaiduNetdiskConfig.VERIFICATION_URL,
                    qrcodeUrl = json.get("qrcode_url")?.asString,
                    expiresIn = json.get("expires_in")?.asInt ?: BaiduNetdiskConfig.DEVICE_CODE_EXPIRE_SEC,
                    interval = json.get("interval")?.asInt ?: BaiduNetdiskConfig.DEFAULT_POLL_INTERVAL_SEC
                )
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "requestDeviceCode error", e)
            null
        }
    }

    /**
     * 轮询设备码授权结果（单次轮询，由 UI 调用方循环）
     *
     * @param deviceCode [DeviceCodeResult.deviceCode]
     */
    suspend fun pollDeviceToken(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        val appKey = resolveAppKey() ?: return@withContext PollResult.Failed("AppKey 未配置")
        val secret = resolveSecretKey() ?: return@withContext PollResult.Failed("SecretKey 未配置")
        try {
            val form = FormBody.Builder()
                .add("grant_type", "device_token")
                .add("code", deviceCode)
                .add("client_id", appKey)
                .add("client_secret", secret)
                .build()
            val req = Request.Builder().url(BaiduNetdiskConfig.TOKEN_URL).post(form).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return@use PollResult.Failed("空响应")
                val json = gson.fromJson(body, JsonObject::class.java)
                    ?: return@use PollResult.Failed("响应解析失败: ${body.take(100)}")
                if (resp.code == 200 && json.has("access_token")) {
                    val tokens = BaiduTokens(
                        accessToken = json.get("access_token").asString,
                        refreshToken = json.get("refresh_token").asString,
                        expiresAt = System.currentTimeMillis() +
                            (json.get("expires_in")?.asLong ?: 2592000L) * 1000,
                        scope = json.get("scope")?.asString ?: BaiduNetdiskConfig.SCOPE
                    )
                    prefs.saveBaiduTokensSync(tokens)
                    AppLog.i(TAG, "device code auth success")
                    return@use PollResult.Success(tokens)
                }
                // 错误码判定
                val error = json.get("error")?.asString
                when (error) {
                    "authorization_pending" -> PollResult.Pending
                    "authorization_declined" -> PollResult.Declined
                    "slow_down" -> PollResult.SlowDown(BaiduNetdiskConfig.DEFAULT_POLL_INTERVAL_SEC * 2)
                    else -> {
                        val desc = json.get("error_description")?.asString ?: error ?: body.take(100)
                        AppLog.w(TAG, "pollDeviceToken error: $desc")
                        PollResult.Failed(desc)
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "pollDeviceToken error", e)
            PollResult.Failed(e.message ?: "网络错误")
        }
    }

    /**
     * 获取有效 access_token，过期自动刷新（提前 5 分钟）。
     * @return 有效 token；未登录或刷新失败返回 null
     */
    suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val tokens = prefs.getBaiduTokensSync() ?: return@withContext null
        if (!tokens.needsRefresh()) return@withContext tokens.accessToken
        refreshAccessToken(tokens.refreshToken)
    }

    /**
     * 刷新 access_token（并发加锁 + 原子写回新 refresh_token）。
     *
     * ⚠️ 百度官方语义：refresh_token 单次有效，刷新响应带新值须一并保存；
     * 刷新失败旧 refresh_token 即失效，需重新走设备码授权。
     */
    suspend fun refreshAccessToken(refreshToken: String): String? = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            // 加锁后复查：可能已有协程刷新过
            val current = prefs.getBaiduTokensSync()
            if (current != null && current.refreshToken != refreshToken && !current.needsRefresh()) {
                return@withContext current.accessToken
            }
            val appKey = resolveAppKey() ?: return@withContext null
            val secret = resolveSecretKey() ?: return@withContext null
            try {
                val form = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", appKey)
                    .add("client_secret", secret)
                    .build()
                val req = Request.Builder().url(tokenUrl).post(form).build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use null
                    val json = gson.fromJson(body, JsonObject::class.java) ?: return@use null
                    if (resp.code == 200 && json.has("access_token")) {
                        val newTokens = BaiduTokens(
                            accessToken = json.get("access_token").asString,
                            refreshToken = json.get("refresh_token").asString,
                            expiresAt = System.currentTimeMillis() +
                                (json.get("expires_in")?.asLong ?: 2592000L) * 1000,
                            scope = json.get("scope")?.asString ?: BaiduNetdiskConfig.SCOPE
                        )
                        prefs.saveBaiduTokensSync(newTokens)
                        AppLog.i(TAG, "access_token refreshed")
                        newTokens.accessToken
                    } else {
                        AppLog.w(TAG, "refresh failed: ${body.take(200)}")
                        null
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "refreshAccessToken error", e)
                null
            }
        }
    }

    /** 登出：清除 token */
    suspend fun logout() {
        prefs.clearBaiduTokensSync()
    }

    companion object {
        private const val TAG = "BaiduOAuth"

        /**
         * 构建百度专用 OkHttpClient（守护线程池 + 信任所有证书，与 MetingApiService 一致）
         */
        fun buildClient(): OkHttpClient {
            val daemonExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
                Thread(r, "Baidu-OkHttp").apply { isDaemon = true }
            }
            val trustAllManager: X509TrustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }
            return try {
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustAllManager), java.security.SecureRandom())
                OkHttpClient.Builder()
                    .dispatcher(okhttp3.Dispatcher(daemonExecutor))
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .sslSocketFactory(sslContext.socketFactory, trustAllManager)
                    .hostnameVerifier(trustAllHostnameVerifier)
                    .build()
            } catch (e: Exception) {
                AppLog.e(TAG, "buildClient SSL fallback", e)
                OkHttpClient.Builder()
                    .dispatcher(okhttp3.Dispatcher(daemonExecutor))
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
            }
        }
    }
}

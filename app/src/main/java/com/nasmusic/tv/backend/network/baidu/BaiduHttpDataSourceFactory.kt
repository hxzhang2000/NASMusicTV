package com.nasmusic.tv.backend.network.baidu

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nasmusic.tv.util.AppLog
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 百度网盘 HTTP DataSource 工厂
 *
 * 模式参考：NeriPlayer `ConditionalHttpDataSourceFactory.transformDataSpec`。
 *
 * 关键：NAS 歌曲（jellyfin/navidrome 域名）、Meting 流量、百度 dlink 共用同一 DataSource 链路，
 * 按 URL 域名自动区分注入请求头——命中百度 dlink 域名时附加 `User-Agent: pan.baidu.com` +
 * `Referer: https://pan.baidu.com/`，否则原样透传。无需为百度源切换整个 Factory。
 *
 * 实现方式：[OkHttpDataSource.Factory]（包装 OkHttp client），通过 OkHttp Interceptor 按 host
 * 条件注入请求头。默认 UA 设为百度 UA（百度 host 不重复设；非百度 host 用默认 UA 也不影响，
 * 因为只在命中时覆盖）。
 *
 * dlink 请求约束（BoxPlayer adapter.ts:53-56 实证）：
 * - >20MB 文件不带 `User-Agent: pan.baidu.com` 会 403
 * - 加 `Referer: https://pan.baidu.com/` 双保险
 */
object BaiduHttpDataSourceFactory {

    private const val TAG = "BaiduDataSource"

    /**
     * 创建共享的 DataSource.Factory：DefaultDataSource 包装 OkHttpDataSource（带百度 UA 拦截器）。
     *
     * 用于 PlaybackService 的 ExoPlayer（主播放器）与 MvPlaybackScreen 的第二 ExoPlayer。
     */
    fun create(context: Context): DataSource.Factory {
        val okClient = buildOkClientWithBaiduInterceptor()
        // Media3 1.2.1 的 OkHttpDataSource.Factory 仅接受 OkHttpClient；
        // UA/Referer 由下方拦截器按 host 注入（百度 dlink / B 站 / 其他透传）。
        val httpFactory = OkHttpDataSource.Factory(okClient)
            .setDefaultRequestProperties(emptyMap())
        return DefaultDataSource.Factory(context, httpFactory)
    }

    private fun buildOkClientWithBaiduInterceptor(): OkHttpClient {
        val daemonExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "Baidu-Exo-OkHttp").apply { isDaemon = true }
        }
        val trustAllManager: X509TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }
        val baiduInterceptor = Interceptor { chain ->
            val req = chain.request()
            val urlStr = req.url.toString()
            val host = req.url.host
            // 百度 dlink 域名：注入 pan.baidu.com UA + Referer
            val isBaiduHost = BaiduNetdiskConfig.DLINK_HOST_MARKERS.any { host.contains(it) || urlStr.contains(it) }
            // B 站域名：注入浏览器 UA + bilibili Referer（与 BilibiliMvService 防盗链一致）
            val isBilibiliHost = host.contains("bilibili") || host.contains("bilivideo") ||
                host.contains("bilivideo") || urlStr.contains("bilivideo")
            val newReq = when {
                isBaiduHost -> req.newBuilder()
                    .header("User-Agent", BaiduNetdiskConfig.BAIDU_UA)
                    .header("Referer", BaiduNetdiskConfig.BAIDU_REFERER)
                    .build()
                isBilibiliHost -> req.newBuilder()
                    .header("User-Agent", BILIBILI_UA)
                    .header("Referer", "https://www.bilibili.com")
                    .build()
                else -> req
            }
            if (isBaiduHost || isBilibiliHost) {
                AppLog.d(TAG, "inject headers host=$host baidu=$isBaiduHost bili=$isBilibiliHost")
            }
            chain.proceed(newReq)
        }
        return try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAllManager), java.security.SecureRandom())
            OkHttpClient.Builder()
                .dispatcher(okhttp3.Dispatcher(daemonExecutor))
                .addInterceptor(baiduInterceptor)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustAllManager)
                .hostnameVerifier(trustAllHostnameVerifier)
                .build()
        } catch (e: Exception) {
            AppLog.e(TAG, "SSL init failed, fallback", e)
            OkHttpClient.Builder()
                .dispatcher(okhttp3.Dispatcher(daemonExecutor))
                .addInterceptor(baiduInterceptor)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    /** 供 Coil 封面加载复用的 OkHttpClient（同样带百度 UA 拦截器） */
    fun createOkHttpClientForCoil(): OkHttpClient = buildOkClientWithBaiduInterceptor()

    /** B 站防盗链 UA（与 BilibiliMvService 一致） */
    private const val BILIBILI_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}

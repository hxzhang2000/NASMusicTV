package com.nasmusic.tv.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * 网络状态监听器
 * 封装 ConnectivityManager.NetworkCallback 的注册与注销
 *
 * 防抖策略（修复 WiFi 抖动导致重连风暴）：
 * - onCapabilitiesChanged 高频触发（WiFi 信号波动、网络切换），不能直接调用 onNetworkLost/onNetworkAvailable
 * - 仅在状态真正转换时回调：无 internet → 有 internet 触发 onNetworkAvailable；有 → 无 由 onLost 触发
 * - onNetworkLost 只由 onLost 触发，避免 capabilities 暂时丢失 internet 时的误报
 *
 * 这样可避免"网络抖动 → onNetworkLost → 重置重连计数 → onNetworkAvailable → 重连 NAS → 创建新 OkHttpClient"
 * 的正反馈循环导致电视 WiFi 栈过载。
 *
 * @param networkRequest 可选的已构造 NetworkRequest（测试注入用）。
 *   为 null 时内部用默认构建器（NET_CAPABILITY_INTERNET）构造。
 *   Robolectric 对 NetworkRequest.Builder.addCapability 无 shadow 实现，
 *   单元测试可通过此参数注入 mock 请求绕过框架桩方法。
 */
class NetworkMonitor(
    private val context: Context,
    private val onNetworkAvailable: () -> Unit,
    private val onNetworkLost: () -> Unit,
    private val networkRequest: NetworkRequest? = null
) {

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isRegistered = false

    /**
     * 上一次 capabilities 中是否含有 internet 能力。
     * 仅在状态转换（false → true）时回调 onNetworkAvailable，避免高频误触发。
     * onLost 时重置为 false。
     */
    @Volatile
    private var lastHasInternet = false

    /**
     * 注册网络状态回调
     */
    fun register() {
        if (isRegistered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                // onAvailable 表示新网络已就绪，仅在未连接时回调一次
                if (!lastHasInternet) {
                    lastHasInternet = true
                    onNetworkAvailable()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                // onLost 是真正的断网事件（网络已丢失），仅在已连接时回调一次
                if (lastHasInternet) {
                    lastHasInternet = false
                    onNetworkLost()
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, capabilities)
                // onCapabilitiesChanged 在 WiFi 信号波动、网络切换时高频触发，
                // 不能直接调用 onNetworkLost/onNetworkAvailable。
                // 仅在"无 internet → 有 internet"的状态转换时触发 onNetworkAvailable；
                // 不在此处触发 onNetworkLost（避免抖动），断网由 onLost 负责。
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (hasInternet && !lastHasInternet) {
                    lastHasInternet = true
                    onNetworkAvailable()
                }
            }
        }
        networkCallback = callback

        val request = networkRequest ?: NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        isRegistered = true
        AppLog.d("NetworkMonitor", "Network callback registered")
    }

    /**
     * 注销网络状态回调
     */
    fun unregister() {
        if (!isRegistered) return
        networkCallback?.let { callback ->
            connectivityManager?.unregisterNetworkCallback(callback)
            AppLog.d("NetworkMonitor", "Network callback unregistered")
        }
        networkCallback = null
        connectivityManager = null
        isRegistered = false
        lastHasInternet = false
    }
}

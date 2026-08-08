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
     * 注册网络状态回调
     */
    fun register() {
        if (isRegistered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                onNetworkAvailable()
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                onNetworkLost()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, capabilities)
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (hasInternet) {
                    onNetworkAvailable()
                } else {
                    onNetworkLost()
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
    }
}

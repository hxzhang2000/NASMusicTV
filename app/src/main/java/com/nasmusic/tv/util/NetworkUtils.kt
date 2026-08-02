package com.nasmusic.tv.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网络工具
 *
 * 获取设备在局域网中的 IP 地址，用于生成扫码输入的 URL。
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * 获取设备在局域网中的 IPv4 地址（点分十进制，如 "192.168.1.100"）
     *
     * 遍历所有网络接口（Wi-Fi / 以太网），返回第一个非回环 IPv4 地址。
     * 不需要 ACCESS_WIFI_STATE 权限，兼容所有 API 级别。
     *
     * @return IP 地址字符串；无可用网络时返回 null
     */
    fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses?.asSequence() ?: emptySequence() }
                ?.filter { !it.isLoopbackAddress && it is Inet4Address }
                ?.map { it.hostAddress }
                ?.firstOrNull()
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to get local IP", e)
            null
        }
    }
}
